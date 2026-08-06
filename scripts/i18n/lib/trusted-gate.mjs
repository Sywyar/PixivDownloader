'use strict';
/**
 * 本地可信 Gate Anchor 库。
 *
 * 信任模型：
 * - 本地 Git hooks 是开发便利性门禁：用户始终可以主动修改 hook、修改 .git/config 或使用
 *   --no-verify，因此不能宣称其绝对不可绕过。真正的最终门禁必须由 GitHub Ruleset /
 *   分支保护 / required check 提供，并且可信 workflow / 检查器不能由同一个候选提交自行批准。
 * - 候选提交不能作为自己的唯一检查者。本地可信锚点由
 *   `git config --local pixiv.i18n.trustedGateRef <commit-sha>` 持有：
 *   - 配置存在于 .git/config，不提交到仓库，候选提交不能修改；
 *   - hooks 只从该 commit 物化可信 checker、contract、policy 与 signature guard；
 *   - 锚点不随 HEAD 自动更新，只有显式信任命令（trust-gate.mjs）才能推进；
 *   - 首次信任是明确的 Trust On First Use（--bootstrap），之后只能 --advance，
 *     advance 由「旧 trusted contract 审核候选」完成，候选不能自我批准。
 *
 * 门禁事实来源只允许：
 * - trusted ref 中的 gate-policy.json（required paths / contract version / enforcement start）；
 * - trusted ref 中物化的 check.mjs / gate-contract.mjs / pre-push-guard.sh；
 * - 候选快照（index / ref）只作为被检查对象，不参与自身批准。
 */

import { execFileSync, spawnSync } from 'child_process';
import fs from 'fs';
import path from 'path';

import snapshot from './repository-snapshot.mjs';

export const TRUSTED_REF_KEY = 'pixiv.i18n.trustedGateRef';

/** hooks 与 contract 从可信锚点物化的路径范围。 */
export const GATE_PATHS = ['scripts/i18n', 'scripts/hooks'];

/** gate-policy.json 相对仓库根的路径。 */
export const POLICY_REL = path.posix.join('scripts', 'i18n', 'gate-policy.json');

/** gate-contract.mjs 相对仓库根的路径。 */
export const CONTRACT_REL = path.posix.join('scripts', 'i18n', 'gate-contract.mjs');

/**
 * 本次信任链起点迁移的明确 legacy bootstrap ref。
 * 仓库历史中唯一「缺 contract 的 trusted base」允许按显式迁移路径处理（见 14.2），
 * 其它缺 contract 的 base 一律 fail closed。bootstrap 路径只在迁移当次可用。
 */
export const LEGACY_BOOTSTRAP_REF = '19c3bc47387762130d593ddfcf7e2c4acbc992bd';

/** legacy anchor（19c3bc47 之前的架构）下回退的 i18n enforcement start（基础设施首次引入点）。 */
export const LEGACY_ENFORCEMENT_START = '05f4ebed7ce00f0b923fe48ec2e0971610511547';

/** legacy anchor 下仍强制存在的 required gate 文件（contract/policy 尚未引入）。 */
export const LEGACY_REQUIRED_PATHS = [
    'scripts/i18n/check.mjs',
    'scripts/i18n/lib/repository-snapshot.mjs',
    'scripts/hooks/pre-commit',
    'scripts/hooks/pre-push',
    'scripts/hooks/pre-push-guard.sh',
];

export const BOOTSTRAP_HINT = 'npm run i18n:trust-gate -- --bootstrap --ref HEAD';

export const SHA_RE = /^[0-9a-f]{40}$/;

function git(args, repoRoot, opts = {}) {
    return execFileSync('git', args, {
        cwd: repoRoot, encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'], ...opts,
    }).trim();
}

/** CI 判定（同 accept.mjs 的契约：CI / true / 1 都是 CI）。 */
export function isCI(env = process.env) {
    const value = env.CI;
    if (value === undefined) {
        return false;
    }
    const normalized = String(value).trim().toLowerCase();
    return normalized === 'true' || normalized === '1';
}

/** 读取本地 trustedGateRef；未配置返回 null。 */
export function getTrustedRef(repoRoot) {
    try {
        const value = git(['config', '--local', '--get', TRUSTED_REF_KEY], repoRoot);
        return value || null;
    } catch (e) {
        return null;
    }
}

/** 写本地 trustedGateRef（只写 local 配置）并回读验证。 */
export function setTrustedRef(repoRoot, sha) {
    if (!SHA_RE.test(sha)) {
        throw new Error('refusing to trust a non-commit value: ' + sha);
    }
    git(['config', '--local', TRUSTED_REF_KEY, sha], repoRoot);
    const actual = getTrustedRef(repoRoot);
    if (actual !== sha) {
        throw new Error('trusted gate ref verification failed: expected ' + sha + ', got ' + actual);
    }
}

/** ref 必须精确解析为完整 commit；不接受工作树路径 / 非 commit。 */
export function resolveCommit(repoRoot, ref) {
    if (typeof ref !== 'string' || ref.length === 0 || ref.includes('\0')) {
        return null;
    }
    let sha;
    try {
        sha = git(['rev-parse', '--verify', '--quiet', ref + '^{commit}'], repoRoot);
    } catch (e) {
        return null;
    }
    return SHA_RE.test(sha) ? sha : null;
}

/** ancestorSha 是否是 descendantRef 的祖先（或等于）。 */
export function isAncestor(repoRoot, ancestorSha, descendantRef) {
    try {
        git(['merge-base', '--is-ancestor', ancestorSha, descendantRef], repoRoot);
        return true;
    } catch (e) {
        return false;
    }
}

/** index 无暂存改动。 */
export function isIndexClean(repoRoot) {
    try {
        git(['diff', '--cached', '--quiet'], repoRoot);
        return true;
    } catch (e) {
        return false;
    }
}

/** 工作树无改动（含未跟踪文件）。 */
export function isWorktreeClean(repoRoot) {
    try {
        const status = git(['status', '--porcelain'], repoRoot);
        return status.length === 0;
    } catch (e) {
        return false;
    }
}

/** 校验 policy 结构。非法时抛出带原因的 Error。 */
export function validatePolicyStructure(policy) {
    if (!policy || typeof policy !== 'object' || Array.isArray(policy)) {
        throw new Error('gate-policy.json: root must be a JSON object');
    }
    if (!Number.isInteger(policy.schemaVersion) || policy.schemaVersion < 1) {
        throw new Error('gate-policy.json: schemaVersion must be an integer >= 1');
    }
    if (!Number.isInteger(policy.contractVersion) || policy.contractVersion < 1) {
        throw new Error('gate-policy.json: contractVersion must be an integer >= 1');
    }
    if (!SHA_RE.test(policy.i18nEnforcementStartCommit || '')) {
        throw new Error('gate-policy.json: i18nEnforcementStartCommit must be a full 40-char lowercase hex commit sha');
    }
    if (!Array.isArray(policy.requiredPaths) || policy.requiredPaths.length === 0) {
        throw new Error('gate-policy.json: requiredPaths must be a non-empty array');
    }
    for (const p of policy.requiredPaths) {
        if (typeof p !== 'string' || p.length === 0 || p.startsWith('/') || p.endsWith('/')
            || p.split('/').includes('..') || p.includes('\\')) {
            throw new Error('gate-policy.json: invalid required path: ' + JSON.stringify(p));
        }
    }
}

/** 从给定目录读取并校验 gate-policy.json（trusted 物化或候选快照）。 */
export function loadPolicyFromDir(dir) {
    const file = path.join(dir, POLICY_REL);
    if (!fs.existsSync(file)) {
        return null;
    }
    let policy;
    try {
        policy = JSON.parse(fs.readFileSync(file, 'utf8'));
    } catch (e) {
        throw new Error('gate-policy.json: cannot parse: ' + e.message);
    }
    validatePolicyStructure(policy);
    return policy;
}

/**
 * 从 ref 物化 scripts/i18n 到临时目录并读取 policy。
 * @returns {{policy: Object|null, root: string, cleanup: () => void}}
 */
export function loadPolicyFromRef(repoRoot, ref) {
    const materialized = snapshot.materializePaths(repoRoot, ref, GATE_PATHS);
    try {
        const policy = loadPolicyFromDir(materialized.root);
        return { policy, root: materialized.root, cleanup: materialized.cleanup };
    } catch (e) {
        materialized.cleanup();
        throw e;
    }
}

/** 从可信锚点物化完整 gate bundle（scripts/i18n + scripts/hooks）。 */
export function materializeTrustedGate(repoRoot, trustedSha, outDir) {
    const materialized = snapshot.materializePaths(repoRoot, trustedSha, GATE_PATHS);
    if (!fs.existsSync(path.join(materialized.root, 'scripts', 'i18n', 'check.mjs'))) {
        materialized.cleanup();
        throw new Error('trusted gate anchor ' + trustedSha
            + ' has no complete gate bundle (scripts/i18n/check.mjs missing)');
    }
    if (!fs.existsSync(path.join(materialized.root, 'scripts', 'hooks', 'pre-push-guard.sh'))) {
        materialized.cleanup();
        throw new Error('trusted gate anchor ' + trustedSha
            + ' has no complete gate bundle (scripts/hooks/pre-push-guard.sh missing)');
    }
    if (fs.existsSync(outDir)) {
        fs.rmSync(outDir, { recursive: true, force: true });
    }
    fs.mkdirSync(outDir, { recursive: true });
    fs.cpSync(materialized.root, outDir, { recursive: true });
    materialized.cleanup();
    // 会话级 tempRoot 立即回收：长时间运行的 trust 命令（bootstrap/advance 会跑完整测试套件）
    // 不得让空的快照临时根常驻（泄漏检测会把超过 30s 的目录计为泄漏）
    snapshot.cleanupAll();
    return outDir;
}

/** 候选快照中是否存在路径（物化目录语义）。 */
export function hasPathInDir(root, relPath) {
    return fs.existsSync(path.join(root, ...relPath.split('/')));
}

/**
 * required files 校验：
 * - 候选有该路径 → OK；
 * - 候选没有 → 若该路径在候选历史中已被引入（被删除）→ fail closed（candidate gate bundle
 *   incomplete → fail closed）；
 * - 若该路径从未在候选历史中出现 → 候选早于该路径引入（enforcement start 自身等）→ 只报告不阻断。
 * @param {string} repoRoot
 * @param {string} historyRef 候选的「历史根」：ref 模式 = 候选 sha；index 模式 = HEAD
 * @param {string} candidateDir 物化后的候选快照根
 * @param {Array<string>} requiredPaths 来自 trusted policy
 * @returns {{missing: Array<string>, predated: Array<string>}} missing 为硬失败
 */
export function checkRequiredPaths(repoRoot, historyRef, candidateDir, requiredPaths) {
    const missing = [];
    const predated = [];
    for (const p of requiredPaths) {
        if (hasPathInDir(candidateDir, p)) {
            continue;
        }
        let introduced = '';
        try {
            introduced = git(['log', '--diff-filter=A', '--format=%H', historyRef, '--', p], repoRoot);
        } catch (e) {
            // 无法枚举历史时按 fail closed 处理
        }
        if (introduced) {
            missing.push(p);
        } else {
            predated.push(p);
        }
    }
    return { missing, predated };
}

/** 运行完整 i18n 测试套件（排除本命令自身所在文件，避免递归）。返回 {ok, output}。 */
export function runI18nTestSuite(repoRoot, excludeFile) {
    const testDir = path.join(repoRoot, 'scripts', 'i18n', 'test');
    if (!fs.existsSync(testDir)) {
        return { ok: true, output: 'no scripts/i18n/test directory; suite trivially passes' };
    }
    const files = fs.readdirSync(testDir)
        .filter((f) => f.endsWith('.test.mjs') && (!excludeFile || f !== excludeFile))
        .map((f) => path.join(testDir, f));
    if (files.length === 0) {
        return { ok: true, output: 'no i18n test files to run' };
    }
    // 嵌套 test runner 会继承 NODE_TEST_CONTEXT 进入 child 模式（退出码失真），必须清除
    const env = { ...process.env };
    delete env.NODE_TEST_CONTEXT;
    const result = spawnSync('node', ['--test', ...files], {
        cwd: repoRoot, encoding: 'utf8', maxBuffer: 128 * 1024 * 1024,
        stdio: ['ignore', 'pipe', 'pipe'], env,
    });
    const output = (result.stdout || '') + (result.stderr || '');
    return { ok: result.status === 0, output };
}

export default {
    TRUSTED_REF_KEY,
    GATE_PATHS,
    POLICY_REL,
    CONTRACT_REL,
    LEGACY_BOOTSTRAP_REF,
    LEGACY_ENFORCEMENT_START,
    LEGACY_REQUIRED_PATHS,
    BOOTSTRAP_HINT,
    SHA_RE,
    isCI,
    getTrustedRef,
    setTrustedRef,
    resolveCommit,
    isAncestor,
    isIndexClean,
    isWorktreeClean,
    validatePolicyStructure,
    loadPolicyFromDir,
    loadPolicyFromRef,
    materializeTrustedGate,
    hasPathInDir,
    checkRequiredPaths,
    runI18nTestSuite,
};
