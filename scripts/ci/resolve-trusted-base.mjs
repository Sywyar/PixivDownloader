#!/usr/bin/env node
'use strict';
/**
 * 可信 base 解析（CI 共享实现；Gate Epoch 2 单一标准）。
 *
 * 本脚本由 GitHub Actions quality-gate 的 bootstrap shell 从 **trusted base** 物化后执行，
 * 与 workflow 内联解析逻辑互为镜像：inline 结果与本脚本输出不一致时 job 失败（fail closed）。
 * 候选提交中的同名脚本永远不会被直接运行（候选不能自我批准）。
 *
 * Epoch 2 规则（与 .github/workflows/quality-gate.yml 的 bootstrap 保持一致）：
 * 0. 解析 root tag refs/tags/i18n-gate-epoch-2-root^{commit}：
 *    - tag 缺失：仅当显式 workflow_dispatch root_admission=true 且 root_candidate_sha == candidate
 *      时进入 ROOT_ADMISSION（root = candidate）；否则 fail closed
 *      （"Gate Epoch 2 trust root has not been installed."）；
 *    - candidate == root → ROOT_ADMISSION（root 自身 gate + 全量 root self-protection）；
 *    - candidate 是 root 后代 → NORMAL；
 *    - 其它（不包含 Epoch 2 root 的 candidate）→ fail closed，不尝试任何 v1/legacy 路径。
 * 1. NORMAL 模式下 trusted base 解析（优先级从高到低）：
 *    - inputs.trusted_base_sha 非空 → 使用它（workflow_call 的 github.event_name 是调用方
 *      的原始 event，不能依赖 event 猜测；调用者只 propose，本脚本负责 prove）；
 *    - 否则按当前 event：pull_request → base.sha；merge_group → base_sha；
 *      push 到受保护默认分支 → event.before；其它分支 push（无论 before 是否非零）→
 *      merge-base(candidate, 受保护默认分支) 的 fork base；
 *      workflow_dispatch → 默认分支远端 ref；其它 → fail closed；
 *    - 每个来源的 base 都必须满足完整 provenance：base 是 commit、base != candidate、
 *      root ancestor base、base ancestor candidate（root <= base < candidate）。
 *      sibling / descendant / unrelated / pre-root base 全部 fail closed；
 *    - 所有 NORMAL base 都必须属于受保护默认分支历史；显式 trusted_base_sha 还必须
 *      精确等于 candidate 相对受保护默认分支的 predecessor，不能任意选择更老提交；
 *    - base 降级到 Epoch 1 历史一律 fail closed；
 * 8.4 每个 base（含 ROOT_ADMISSION 的 root candidate）还必须满足本 verifier 自身 policy 的
 *    minimumTrustedVerifier（contractVersion / schemaVersion / requiredFiles）；
 *    缺任何一项 → FAIL CLOSED（verifier rollback / 旧 verifier 审核新标准一律拒绝，
 *    不存在 predates / fallback / legacy / transition 兼容路径）。
 * 2. ROOT_ADMISSION 模式下 base = root（candidate 自身；这是唯一人工 root 例外）。
 * 3. 结果验证（写 GITHUB_ENV 前）：40 位小写 hex commit SHA；在本地 object database 中存在。
 *
 * 输出：默认只打印 base SHA；--mode 时打印 JSON {"mode","base","root"}（root = root SHA）。
 */
import { execFileSync } from 'child_process';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const ZERO = '0000000000000000000000000000000000000000';
const SHA_RE = /^[0-9a-f]{40}$/;
const ROOT_TAG = 'refs/tags/i18n-gate-epoch-2-root';

const VERIFIER_POLICY_REL = 'scripts/i18n/gate-policy.json';
const OWN_POLICY_FILE = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', 'i18n', 'gate-policy.json');

function fail(message) {
    console.error('resolve-trusted-base ERROR: ' + message);
    process.exit(2);
}

function git(args, repoRoot) {
    return execFileSync('git', args, {
        cwd: repoRoot, encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'],
    }).trim();
}

function resolveCommit(repoRoot, ref) {
    try {
        const sha = git(['rev-parse', '--verify', '--quiet', ref + '^{commit}'], repoRoot);
        return SHA_RE.test(sha) ? sha : null;
    } catch (e) {
        return null;
    }
}

function isAncestor(repoRoot, ancestorSha, descendantRef) {
    try {
        git(['merge-base', '--is-ancestor', ancestorSha, descendantRef], repoRoot);
        return true;
    } catch (e) {
        return false;
    }
}

function fetchDefaultBranch(repoRoot, defaultBranch) {
    try {
        execFileSync('git', ['fetch', '-q', 'origin', 'refs/heads/' + defaultBranch + ':refs/remotes/origin/' + defaultBranch],
            { cwd: repoRoot, encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'] });
        return true;
    } catch (e) {
        return false;
    }
}

function resolveDefaultBranch(repoRoot, defaultBranch) {
    if (!defaultBranch) {
        return null;
    }
    const existing = resolveCommit(repoRoot, 'refs/remotes/origin/' + defaultBranch);
    if (existing) {
        return existing;
    }
    if (fetchDefaultBranch(repoRoot, defaultBranch)) {
        return resolveCommit(repoRoot, 'refs/remotes/origin/' + defaultBranch);
    }
    return null;
}

/** 读 ref 处的文件内容；不存在返回 null。 */
function readFileAt(repoRoot, sha, rel) {
    try {
        return git(['show', sha + ':' + rel], repoRoot);
    } catch (e) {
        return null;
    }
}

/** ref 处是否存在文件。 */
function hasFileAt(repoRoot, sha, rel) {
    try {
        git(['cat-file', '-e', sha + ':' + rel], repoRoot);
        return true;
    } catch (e) {
        return false;
    }
}

function loadMinimumTrustedVerifier() {
    let policy;
    try {
        policy = JSON.parse(fs.readFileSync(OWN_POLICY_FILE, 'utf8'));
    } catch (e) {
        fail('cannot read this verifier\'s gate-policy.json minimumTrustedVerifier: ' + e.message);
        return null;
    }
    const minimum = policy && policy.minimumTrustedVerifier;
    if (!minimum || !Number.isInteger(minimum.contractVersion)
        || !Number.isInteger(minimum.schemaVersion) || !Array.isArray(minimum.requiredFiles)
        || minimum.requiredFiles.length === 0
        || minimum.requiredFiles.some((rel) => typeof rel !== 'string' || rel.length === 0)) {
        fail('this verifier has no valid gate-policy.json minimumTrustedVerifier; fail closed');
        return null;
    }
    return minimum;
}

/**
 * 校验 trusted base 是否满足执行中 verifier policy 声明的 minimumTrustedVerifier。
 * minimum 来自本脚本同一 trusted bundle，不能由待审核 base 自行降低。
 * 缺任何一项 → FAIL CLOSED（verifier rollback 一律拒绝，无 predates / fallback / legacy 路径）。
 */
function assertSupportedTrustedVerifier(repoRoot, base, minimum) {
    const policyText = readFileAt(repoRoot, base, VERIFIER_POLICY_REL);
    if (policyText === null) {
        fail('trusted base ' + base + ' has no gate-policy.json; it does not satisfy the'
            + ' current Gate Epoch 2 verifier baseline; fail closed');
        return;
    }
    let policy;
    try {
        policy = JSON.parse(policyText);
    } catch (e) {
        fail('trusted base ' + base + ' gate-policy.json is invalid; fail closed');
        return;
    }
    if (policy.gateEpoch !== 2) {
        fail('trusted base ' + base + ' gateEpoch ' + policy.gateEpoch
            + ' != 2; it does not satisfy the current verifier baseline; fail closed');
        return;
    }
    if (!Number.isInteger(policy.contractVersion)
        || policy.contractVersion < minimum.contractVersion) {
        fail('trusted base ' + base + ' contractVersion ' + policy.contractVersion
            + ' < current minimum ' + minimum.contractVersion
            + '; the trusted verifier is too old to review current-standard candidates'
            + ' (verifier rollback is refused); fail closed');
        return;
    }
    if (!Number.isInteger(policy.schemaVersion)
        || policy.schemaVersion < minimum.schemaVersion) {
        fail('trusted base ' + base + ' schemaVersion ' + policy.schemaVersion
            + ' < current minimum ' + minimum.schemaVersion
            + '; it does not satisfy the current verifier baseline; fail closed');
        return;
    }
    for (const rel of minimum.requiredFiles) {
        if (!hasFileAt(repoRoot, base, rel)) {
            fail('trusted base ' + base + ' is missing ' + rel + '; it does not satisfy the'
                + ' current Gate Epoch 2 verifier baseline; fail closed');
            return;
        }
    }
}

function parseArgs(argv) {
    const args = {
        repoRoot: null, eventName: null, candidate: null, before: null, gitRef: null,
        prBase: null, mergeGroupBase: null, inputBase: null, defaultBranch: null,
        rootAdmission: null, rootCandidateSha: null, mode: false, version: false,
    };
    for (let i = 0; i < argv.length; i += 1) {
        const arg = argv[i];
        const value = () => argv[++i];
        if (arg === '--repo-root') {
            args.repoRoot = value();
        } else if (arg === '--event-name') {
            args.eventName = value();
        } else if (arg === '--candidate') {
            args.candidate = value();
        } else if (arg === '--before') {
            args.before = value();
        } else if (arg === '--ref') {
            args.gitRef = value();
        } else if (arg === '--pr-base') {
            args.prBase = value();
        } else if (arg === '--merge-group-base') {
            args.mergeGroupBase = value();
        } else if (arg === '--input-base') {
            args.inputBase = value();
        } else if (arg === '--default-branch') {
            args.defaultBranch = value();
        } else if (arg === '--root-admission') {
            args.rootAdmission = value();
        } else if (arg === '--root-candidate-sha') {
            args.rootCandidateSha = value();
        } else if (arg === '--mode') {
            args.mode = true;
        } else if (arg === '--version') {
            args.version = true;
        } else {
            throw new Error('unknown argument: ' + arg);
        }
    }
    return args;
}

/** 新分支（before 全零）的 fork base：merge-base(candidate, 受保护默认分支)。 */
function resolveForkBase(repoRoot, candidate, defaultBranch) {
    const tip = resolveDefaultBranch(repoRoot, defaultBranch);
    if (!tip) {
        return null;
    }
    let mb;
    try {
        mb = git(['merge-base', candidate, tip], repoRoot);
    } catch (e) {
        return null;
    }
    return SHA_RE.test(mb) ? mb : null;
}

function resolveProtectedPredecessor(repoRoot, candidate, defaultBranch) {
    const tip = resolveDefaultBranch(repoRoot, defaultBranch);
    if (!tip) {
        return null;
    }
    if (tip === candidate) {
        return resolveCommit(repoRoot, candidate + '^');
    }
    try {
        const base = git(['merge-base', candidate, tip], repoRoot);
        return SHA_RE.test(base) ? base : null;
    } catch (e) {
        return null;
    }
}

/** 根据 event / inputs 解析 NORMAL 模式 trusted base（input 优先级最高）。 */
function resolveNormalBase(repoRoot, args) {
    // 1. 显式 input 优先（reusable workflow 的 event_name 是调用方原始 event，不能依赖它）
    if (SHA_RE.test(args.inputBase || '')) {
        return args.inputBase;
    }
    const event = args.eventName;
    if (event === 'pull_request') {
        return SHA_RE.test(args.prBase || '') ? args.prBase : null;
    }
    if (event === 'merge_group') {
        return SHA_RE.test(args.mergeGroupBase || '') ? args.mergeGroupBase : null;
    }
    if (event === 'push') {
        if (!args.gitRef) {
            fail('push event is missing --ref; protected-branch provenance cannot be proved');
            return null;
        }
        if (args.gitRef === 'refs/heads/' + args.defaultBranch
            && SHA_RE.test(args.before || '') && args.before !== ZERO) {
            const before = resolveCommit(repoRoot, args.before);
            if (!before) {
                fail('event.before ' + args.before + ' is not present in the local object database');
                return null;
            }
            return before;
        }
        // 非受保护分支不能信任 event.before：它可能是上一次 CI 已失败的恶意 verifier。
        const forkBase = resolveForkBase(repoRoot, args.candidate, args.defaultBranch);
        if (!forkBase) {
            fail('cannot determine a fork base between the candidate and the protected default'
                + ' branch (merge-base(candidate, refs/remotes/origin/<default>) missing); fail closed');
            return null;
        }
        return forkBase;
    }
    if (event === 'workflow_dispatch') {
        return resolveDefaultBranch(repoRoot, args.defaultBranch);
    }
    return null;
}

function main() {
    let args;
    try {
        args = parseArgs(process.argv.slice(2));
    } catch (e) {
        fail(e.message);
        return;
    }
    if (args.version) {
        console.log('resolve-trusted-base 4');
        return;
    }
    if (!args.repoRoot || !args.eventName || !args.candidate) {
        fail('--repo-root, --event-name and --candidate are required');
        return;
    }
    const repoRoot = path.resolve(args.repoRoot);
    if (!fs.existsSync(path.join(repoRoot, '.git'))) {
        fail('not a git repository: ' + repoRoot);
        return;
    }
    const candidate = resolveCommit(repoRoot, args.candidate);
    if (!candidate) {
        fail('candidate ' + args.candidate + ' does not resolve to a commit');
        return;
    }
    const minimum = loadMinimumTrustedVerifier();
    if (!minimum) {
        return;
    }

    // 0. Epoch 2 root tag 解析 + 运行模式判定
    const root = resolveCommit(repoRoot, ROOT_TAG);
    let mode;
    if (!root) {
        const admission = args.eventName === 'workflow_dispatch'
            && String(args.rootAdmission || '') === 'true'
            && SHA_RE.test(args.rootCandidateSha || '')
            && args.rootCandidateSha === candidate;
        if (admission) {
            mode = 'ROOT_ADMISSION';
        } else {
            fail('Gate Epoch 2 trust root has not been installed'
                + ' (refs/tags/i18n-gate-epoch-2-root missing); only an explicit'
                + ' workflow_dispatch root_admission=true with root_candidate_sha may enter'
                + ' ROOT_ADMISSION; fail closed');
            return;
        }
    } else if (candidate === root) {
        mode = 'ROOT_ADMISSION';
    } else if (isAncestor(repoRoot, root, candidate)) {
        mode = 'NORMAL';
    } else {
        fail('candidate does not descend from the Gate Epoch 2 trust root (' + root
            + '); v1 / legacy / transition compatibility paths are retired; fail closed');
        return;
    }

    let base;
    if (mode === 'ROOT_ADMISSION') {
        base = root || candidate;
        console.error('ROOT ADMISSION MODE: candidate is the Gate Epoch 2 trust root candidate'
            + ' (root tag missing or pointing at the candidate); the root gate runs with the full'
            + ' root self-protection suite.');
    } else {
        base = resolveNormalBase(repoRoot, args);
        if (!base) {
            fail('cannot determine a trusted base for ' + candidate + ' (event ' + args.eventName
                + ', no explicit trusted_base_sha input); fail closed');
            return;
        }
        if (base === candidate) {
            fail('trusted base ' + base + ' equals the candidate; fail closed');
            return;
        }
        const resolved = resolveCommit(repoRoot, base);
        if (!resolved) {
            fail('trusted base ' + base + ' is not present in the local object database; fail closed');
            return;
        }
        base = resolved;
        // 8.2：trusted base 必须包含 Epoch 2 root（不允许降级到 Epoch 1 历史）
        if (!isAncestor(repoRoot, root, base)) {
            fail('trusted base ' + base + ' does not descend from the Gate Epoch 2 trust root '
                + root + '; fail closed');
            return;
        }
        // 8.3：trusted base 必须是 candidate 的真实祖先（root <= base < candidate）。
        // sibling / unrelated / descendant / pre-root base 一律拒绝——仅验证
        // root ancestor base 无法排除「从未进入受保护历史的 feature branch gate」。
        if (!isAncestor(repoRoot, base, candidate)) {
            fail('trusted base ' + base + ' is not an ancestor of the candidate ' + candidate
                + '; sibling / unrelated / descendant / pre-root trusted bases are refused; fail closed');
            return;
        }
        const protectedTip = resolveDefaultBranch(repoRoot, args.defaultBranch);
        if (!protectedTip || !isAncestor(repoRoot, base, protectedTip)) {
            fail('trusted base ' + base + ' is not in the protected default branch history; fail closed');
            return;
        }
        if (SHA_RE.test(args.inputBase || '')) {
            const expected = resolveProtectedPredecessor(repoRoot, candidate, args.defaultBranch);
            if (!expected || base !== expected) {
                fail('explicit trusted_base_sha ' + base + ' is not the protected predecessor '
                    + (expected || '<unresolved>') + ' for candidate ' + candidate + '; fail closed');
                return;
            }
        }
        if (args.eventName === 'push' && args.gitRef === 'refs/heads/' + args.defaultBranch
            && args.before && SHA_RE.test(args.before) && args.before !== ZERO && base !== args.before) {
            fail('trusted base ' + base + ' does not resolve to the protected push before commit '
                + args.before);
            return;
        }
    }
    // 8.4：trusted base（或 ROOT_ADMISSION 的 root candidate）必须同时满足 ancestry 与
    // 当前 verifier capability（contract / schema / verifier 本体文件）。缺任何一项 → FAIL CLOSED。
    assertSupportedTrustedVerifier(repoRoot, base, minimum);

    if (args.mode) {
        console.log(JSON.stringify({ mode, base, root: root || candidate }));
        return;
    }
    console.log(base);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    main();
}
