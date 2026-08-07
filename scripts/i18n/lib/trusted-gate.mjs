'use strict';
/**
 * 本地可信 Gate Anchor 库（Gate Epoch 2 单一标准）。
 *
 * 信任模型：
 * - 本地 Git hooks 是开发便利性门禁：用户始终可以主动修改 hook、修改 .git/config 或使用
 *   --no-verify，因此不能宣称其绝对不可绕过。真正的最终门禁必须由 GitHub Ruleset /
 *   分支保护 / required check 提供，并且可信 workflow / 检查器不能由同一个候选提交自行批准。
 * - 候选提交不能作为自己的唯一检查者。Epoch 2 信任根是仓库外的受保护 annotated tag
 *   `refs/tags/i18n-gate-epoch-2-root`（由仓库管理员人工创建，代码不写 root SHA）。
 *   本地锚点由 `git config --local pixiv.i18n.trustedGateEpoch <epoch>` 与
 *   `git config --local pixiv.i18n.trustedGateRef <commit-sha>` 持有：
 *   - 配置存在于 .git/config，不提交到仓库，候选提交不能修改；
 *   - hooks 只从该 commit 物化可信 checker、contract、policy 与 signature guard；
 *   - 锚点不随 HEAD 自动更新，只有显式信任命令（trust-gate.mjs）才能推进；
 *   - Epoch 2 root 采用显式人工 root adoption（--adopt-root）：没有任何提交能自动证明
 *     自己可信，root 由人工 review + 全量自动检查 + root admission 门禁共同建立；
 *   - 之后只能 --advance，advance 由「当前 Epoch 2 trusted contract 审核候选」完成，
 *     候选不能自我批准；Epoch 1 及更早 anchor 不迁移、不兼容、无自动升级权。
 *
 * 门禁事实来源只允许：
 * - trusted ref 中的 gate-policy.json（gateEpoch / required paths / contract version /
 *   enforcement start / protected branches / required jobs / scripts / external checks）；
 * - trusted ref 中物化的 check.mjs / gate-contract.mjs / pre-push-guard.sh；
 * - 候选快照（index / ref）只作为被检查对象，不参与自身批准。
 */

import { execFileSync, spawnSync } from 'child_process';
import fs from 'fs';
import path from 'path';

import snapshot from './repository-snapshot.mjs';

export const TRUSTED_REF_KEY = 'pixiv.i18n.trustedGateRef';
export const TRUSTED_EPOCH_KEY = 'pixiv.i18n.trustedGateEpoch';

/** 当前唯一受支持的 Gate Epoch。epoch < 2 视为 obsolete；epoch > 2 视为 unsupported future。 */
export const CURRENT_GATE_EPOCH = 2;

/** Epoch 2 信任根 tag（仓库内容之外的不可自我修改锚点，由管理员人工创建并受 Ruleset 保护）。 */
export const ROOT_TAG_NAME = 'i18n-gate-epoch-2-root';

/** hooks 与 contract 从可信锚点物化的路径范围。 */
export const GATE_PATHS = [
    'scripts/i18n',
    'scripts/hooks',
    'scripts/ci',
    '.github/workflows/quality-gate.yml',
    'package.json',
    'package-lock.json',
];

/** gate-policy.json 相对仓库根的路径。 */
export const POLICY_REL = path.posix.join('scripts', 'i18n', 'gate-policy.json');

/** gate-contract.mjs 相对仓库根的路径。 */
export const CONTRACT_REL = path.posix.join('scripts', 'i18n', 'gate-contract.mjs');

/** quality-gate.yml 相对仓库根的路径。 */
export const WORKFLOW_REL = path.posix.join('.github', 'workflows', 'quality-gate.yml');

/** package.json 相对仓库根的路径。 */
export const PACKAGE_JSON_REL = path.posix.join('package.json');

const REF_RE = /^refs\/heads\/[A-Za-z0-9._/-]+$/;

function validateRefList(name, list) {
    if (!Array.isArray(list) || list.length === 0) {
        throw new Error('gate-policy.json: ' + name + ' must be a non-empty array');
    }
    const seen = new Set();
    for (const entry of list) {
        if (typeof entry !== 'string' || !REF_RE.test(entry)) {
            throw new Error('gate-policy.json: ' + name + ' entries must be full refs (refs/heads/...): '
                + JSON.stringify(entry));
        }
        if (seen.has(entry)) {
            throw new Error('gate-policy.json: ' + name + ' must not contain duplicates: ' + entry);
        }
        seen.add(entry);
    }
}

function validateJobIdList(list) {
    if (!Array.isArray(list) || list.length === 0) {
        throw new Error('gate-policy.json: requiredWorkflowJobs must be a non-empty array');
    }
    const seen = new Set();
    for (const entry of list) {
        if (typeof entry !== 'string' || entry.length === 0 || !/^[A-Za-z0-9._-]+$/.test(entry)) {
            throw new Error('gate-policy.json: requiredWorkflowJobs entries must be job ids: '
                + JSON.stringify(entry));
        }
        if (seen.has(entry)) {
            throw new Error('gate-policy.json: requiredWorkflowJobs must not contain duplicates: ' + entry);
        }
        seen.add(entry);
    }
}

/**
 * 本次信任链起点迁移的 legacy 常量已整体移除：Epoch 1 及更早 anchor 不再参与正常门禁运行，
 * 不迁移、不兼容、无自动升级权；遇到 epoch != CURRENT_GATE_EPOCH 一律 fail closed。
 */

export const SHA_RE = /^[0-9a-f]{40}$/;

function git(args, repoRoot, opts = {}) {
    // (execFileSync(...) || '')：stdio:'ignore' 时 execFileSync 返回 null（Node 怪癖），归一化为 ''
    return (execFileSync('git', args, {
        cwd: repoRoot, encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'], ...opts,
    }) || '').trim();
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

/** 读取本地 trustedGateEpoch；未配置返回 null。 */
export function getTrustedEpoch(repoRoot) {
    try {
        const value = git(['config', '--local', '--get', TRUSTED_EPOCH_KEY], repoRoot);
        return value || null;
    } catch (e) {
        return null;
    }
}

/**
 * 写本地 Epoch 2 trust anchor（epoch + ref 一起写，只写 local 配置）并回读验证。
 * 这是 root adoption / advance 的唯一写入路径。
 */
export function setTrustedAnchor(repoRoot, sha) {
    if (!SHA_RE.test(sha)) {
        throw new Error('refusing to trust a non-commit value: ' + sha);
    }
    git(['config', '--local', TRUSTED_EPOCH_KEY, String(CURRENT_GATE_EPOCH)], repoRoot);
    git(['config', '--local', TRUSTED_REF_KEY, sha], repoRoot);
    const actualRef = getTrustedRef(repoRoot);
    const actualEpoch = getTrustedEpoch(repoRoot);
    if (actualRef !== sha || actualEpoch !== String(CURRENT_GATE_EPOCH)) {
        throw new Error('trusted gate anchor verification failed: expected epoch '
            + CURRENT_GATE_EPOCH + ' + ref ' + sha + ', got epoch ' + actualEpoch + ' + ref ' + actualRef);
    }
}

/**
 * 解析本地 Epoch 2 root tag（refs/tags/i18n-gate-epoch-2-root^{commit}）。
 * tag 不存在 / 不是 commit / 不是完整 SHA → 返回 null（fail closed 由调用方处理）。
 */
export function resolveRootTag(repoRoot) {
    return resolveCommit(repoRoot, 'refs/tags/' + ROOT_TAG_NAME);
}

/** 本地仓库中是否存在 Epoch 2 root tag。 */
export function hasRootTag(repoRoot) {
    try {
        git(['rev-parse', '--verify', '--quiet', 'refs/tags/' + ROOT_TAG_NAME + '^{commit}'], repoRoot);
        return true;
    } catch (e) {
        return false;
    }
}

/** 本地 anchor 是否属于当前 Epoch：epoch 配置必须精确等于 CURRENT_GATE_EPOCH。 */
export function isTrustedEpochCurrent(repoRoot) {
    return getTrustedEpoch(repoRoot) === String(CURRENT_GATE_EPOCH);
}

/** 本地 anchor 状态描述（hook fail-closed 提示用）。 */
export function describeTrustedEpoch(repoRoot) {
    const epoch = getTrustedEpoch(repoRoot);
    if (epoch === null) {
        return 'uninitialized';
    }
    if (epoch < String(CURRENT_GATE_EPOCH)) {
        return 'obsolete';
    }
    if (epoch > String(CURRENT_GATE_EPOCH)) {
        return 'unsupported future';
    }
    return 'current';
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
    // Epoch 单一标准：只支持 CURRENT_GATE_EPOCH；epoch < 2 是 obsolete，epoch > 2 是 unsupported
    if (policy.gateEpoch !== CURRENT_GATE_EPOCH) {
        throw new Error('gate-policy.json: gateEpoch must be exactly ' + CURRENT_GATE_EPOCH
            + ' (obsolete / unsupported future epochs fail closed; got ' + policy.gateEpoch + ')');
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
    if (policy.protectedBranches !== undefined) {
        validateRefList('protectedBranches', policy.protectedBranches);
    }
    if (policy.requiredWorkflowJobs !== undefined) {
        validateJobIdList(policy.requiredWorkflowJobs);
    }
    if (policy.requiredPackageScripts !== undefined) {
        if (!Array.isArray(policy.requiredPackageScripts) || policy.requiredPackageScripts.length === 0) {
            throw new Error('gate-policy.json: requiredPackageScripts must be a non-empty array');
        }
        const seen = new Set();
        for (const entry of policy.requiredPackageScripts) {
            if (typeof entry !== 'string' || entry.length === 0 || !/^[A-Za-z0-9._:-]+$/.test(entry)) {
                throw new Error('gate-policy.json: requiredPackageScripts entries must be script names: '
                    + JSON.stringify(entry));
            }
            if (seen.has(entry)) {
                throw new Error('gate-policy.json: requiredPackageScripts must not contain duplicates: ' + entry);
            }
            seen.add(entry);
        }
    }
    if (policy.requiredExternalChecks !== undefined) {
        if (!Array.isArray(policy.requiredExternalChecks) || policy.requiredExternalChecks.length === 0) {
            throw new Error('gate-policy.json: requiredExternalChecks must be a non-empty array');
        }
        const seen = new Set();
        for (const entry of policy.requiredExternalChecks) {
            if (typeof entry !== 'string' || entry.length === 0 || seen.has(entry)) {
                throw new Error('gate-policy.json: requiredExternalChecks entries must be distinct strings: '
                    + JSON.stringify(entry));
            }
            seen.add(entry);
        }
    }
}

/** trusted 集合相对 candidate 是否被减少（trusted 缺失视为空集；candidate 缺失视为空集）。 */
export function policySetReduced(trustedList, candidateList) {
    const trusted = new Set(trustedList || []);
    const candidate = new Set(candidateList || []);
    return [...trusted].filter((entry) => !candidate.has(entry));
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
    // Epoch 2 单一标准：policy 是强制组成，缺 policy 的 anchor 一律 fail closed
    if (!fs.existsSync(path.join(materialized.root, 'scripts', 'i18n', 'gate-policy.json'))) {
        materialized.cleanup();
        throw new Error('trusted gate anchor ' + trustedSha
            + ' has no Epoch 2 gate policy (scripts/i18n/gate-policy.json missing);'
            + ' obsolete-epoch anchors are not migrated');
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

/**
 * required paths 并集校验（5.3）：trusted ∪ candidate。
 * - 所有 trusted 路径（含 candidate 共享的）沿用 checkRequiredPaths 语义：
 *   缺失 fail closed；predates 只报告；
 * - candidate 新声明的路径必须在同一个候选快照中真实存在（无 predates 豁免），
 *   不能让 candidate 声明一个不存在的 required path 后仍被接受。
 * @returns {{missing: Array<string>, predated: Array<string>}}
 */
export function checkUnionRequiredPaths(repoRoot, historyRef, candidateDir, trustedPaths, candidatePaths) {
    const candidateOnly = (candidatePaths || []).filter((p) => !(trustedPaths || []).includes(p));
    const result = checkRequiredPaths(repoRoot, historyRef, candidateDir, trustedPaths);
    const candidateMissing = candidateOnly.filter((p) => !hasPathInDir(candidateDir, p));
    result.missing.push(...candidateMissing);
    return result;
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
    TRUSTED_EPOCH_KEY,
    CURRENT_GATE_EPOCH,
    ROOT_TAG_NAME,
    GATE_PATHS,
    POLICY_REL,
    CONTRACT_REL,
    WORKFLOW_REL,
    PACKAGE_JSON_REL,
    SHA_RE,
    isCI,
    getTrustedRef,
    getTrustedEpoch,
    setTrustedAnchor,
    resolveRootTag,
    hasRootTag,
    isTrustedEpochCurrent,
    describeTrustedEpoch,
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
    checkUnionRequiredPaths,
    runI18nTestSuite,
    policySetReduced,
};
