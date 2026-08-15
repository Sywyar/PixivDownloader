#!/usr/bin/env node
'use strict';
/**
 * 本地可信 Gate Anchor 管理命令（Gate Epoch 3 单一标准）。
 *
 * 用法：
 *   npm run i18n:trust-gate -- --show
 *   node <Epoch-3-trusted-bundle>/scripts/i18n/trust-gate.mjs --prepare-root --epoch 4
 *     --trusted-source <exact-sha>
 *   node <Epoch-3-trusted-bundle>/scripts/i18n/trust-gate.mjs --seal-root --ref HEAD
 *     --trusted-source <exact-sha>
 *   npm run i18n:trust-gate -- --adopt-root --ref HEAD --epoch 3
 *   npm run i18n:trust-gate -- --advance --ref HEAD
 *   npm run i18n:trust-gate -- --version
 *
 * prepare-root（Epoch 3 → 4 一次性 first admission）：
 * - 第一条 first-admission 执行代码必须直接来自已物化的 Epoch 3 trusted bundle；当前工作树
 *   candidate CLI 不代理、不启动 bridge；
 * - trusted source 必须精确等于实时 origin/master tip、包含 Epoch 3 root，且必须精确等于
 *   staged Epoch 4 root 的单一 parent；candidate 只作为被审核对象；
 * - bridge 只接纳声明中精确限定的发布门禁核心缩减，并把候选还原为可信来源树后交给
 *   Epoch 3 trusted contract/parity 审核；
 * - prepare 成功后只写仓库外的一次性 ticket，绑定双 epoch、trusted source、parent 与 tree；
 *   commit 后由同一 trusted bridge 的 seal-root 唯一绑定 candidate SHA；失败不推进 anchor，
 *   也不写部分 ticket。
 *
 * adopt-root（人工 root adoption / TOFU；当前 root 的唯一建立方式）：
 * - 新的 root 不可能由自己自动证明自己可信：root 由人工 code review + 完整自动测试 +
 *   root admission 门禁共同建立，本命令只执行 root-specific 自动检查；
 * - 只能对完整 commit 执行，不接受工作树路径；ref 必须精确解析为 commit；
 * - 工作区和 index 必须干净；
 * - 必须满足当前 epoch、有效 policy 与 verifier baseline、
 *   all required paths present、完整 i18n tests、ref snapshot、signature guard、
 *   workflow contract（candidate contract 对自身 + --force-self-protection）、
 *   package contract、gate parity（--invariants）、self-protection、
 *   snapshot hardening、hook hardening（后两者在完整 i18n 测试套件内）；
 * - 全部通过才写入 git config --local pixiv.i18n.trustedGateEpoch <current> +
 *   pixiv.i18n.trustedGateRef <sha>；
 * - 不写全局 config，不修改仓库文件；CI 中禁止。
 *
 * advance（已有当前 Epoch anchor 时推进，单调变严格）：
 * 1. 当前 anchor 必须存在且 epoch == 当前值（旧值 / 未初始化 anchor → OBSOLETE GATE EPOCH，
 *    只允许重新 --adopt-root，不迁移、不兼容、无自动升级权）；
 * 2. 候选必须是当前锚点的后代（向后 / sibling / 无共同历史一律拒绝）；
 *    若本地存在当前 Epoch root tag，候选与当前锚点都必须包含该 root；
 * 3. 从当前 trusted ref 物化 trusted contract + policy；由 trusted contract 验证候选；
 *    candidate policy 的 gateEpoch 必须 == 3（epoch 升级属于另一轮人工 root admission）；
 * 4. gate parity（trusted vs candidate）：任何门禁集合 / 命令 / 步骤减少一律拒绝；
 * 5. 完整 tests 通过；candidate ref snapshot check 通过；signature guard 通过；
 * 6. 候选必须是完整 commit；所有检查通过后才更新 local config。
 * - hooks 不自动 advance；push 不自动 advance；CI 不自动修改 local config；
 * - 仅因 candidate 的 --version 返回 0 不构成信任；candidate checker 不能决定自己是否可信。
 *
 * 本地 Git hooks 是开发便利性门禁，用户始终可以主动修改 hook、修改 .git/config 或使用
 * --no-verify，因此不能宣称其绝对不可绕过。真正的最终门禁必须由 GitHub Ruleset /
 * 分支保护 / required check 提供。
 */

import { execFileSync, spawnSync } from 'child_process';
import fs from 'fs';
import os from 'os';
import path from 'path';
import { fileURLToPath } from 'url';

import trustedGate from './lib/trusted-gate.mjs';
import snapshot from './lib/repository-snapshot.mjs';

const TRUST_CLI_VERSION = '3';
const FIRST_ADMISSION_SPEC_REL = path.posix.join('scripts', 'i18n',
    'epoch-3-first-admission.json');
// verifier 最低能力由 trusted gate-policy.json 的 minimumTrustedVerifier 定义；NORMAL contract
// 保证该声明只能单调增强，adopt-root / advance 前均按声明 fail closed。

function fail(message) {
    console.error('trust-gate ERROR: ' + message);
    process.exit(1);
}

function git(args, repoRoot, opts = {}) {
    return execFileSync('git', args, {
        cwd: repoRoot, encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'], ...opts,
    }).trim();
}

function gitBuffer(args, repoRoot, opts = {}) {
    return execFileSync('git', args, {
        cwd: repoRoot, encoding: null, stdio: ['pipe', 'pipe', 'pipe'], ...opts,
    });
}

function hasBash() {
    try {
        execFileSync('bash', ['--version'], { stdio: 'ignore' });
        return true;
    } catch (e) {
        return false;
    }
}

function run(args, opts = {}) {
    return spawnSync(args[0], args.slice(1), {
        encoding: 'utf8', maxBuffer: 128 * 1024 * 1024, stdio: ['pipe', 'pipe', 'pipe'], ...opts,
    });
}

/** bash 参数里的路径必须用正斜杠（反斜杠会被 bash 当作转义吃掉）。 */
function toPosix(p) {
    return p.split(path.sep).join('/');
}

/** Windows 上删除刚被 bash 用作 cwd 的目录可能短暂失败，重试几次。 */
function rmrfRetry(dir) {
    for (let attempt = 0; attempt < 6; attempt += 1) {
        try {
            fs.rmSync(dir, { recursive: true, force: true });
            return;
        } catch (e) {
            if (attempt === 5) {
                throw e;
            }
            execFileSync('bash', ['-c', 'sleep 0.3'], { stdio: 'ignore' });
        }
    }
}

// 进程退出（含 fail() 的 process.exit 路径）也必须清理会话级临时快照目录
process.on('exit', () => {
    try {
        snapshot.cleanupAll();
    } catch (ignored) {
        // 退出清理失败不掩盖 verdict
    }
});

function resolveRepoRoot() {
    try {
        return git(['rev-parse', '--show-toplevel'], process.cwd());
    } catch (e) {
        fail('not inside a git repository');
        return null;
    }
}

/** 校验工作区状态；index 与 worktree 必须干净（无 --allow-dirty 豁免）。 */
function assertCleanState(repoRoot) {
    if (!trustedGate.isIndexClean(repoRoot)) {
        fail('the git index has staged changes; commit or reset them before running trust commands');
    }
    if (!trustedGate.isWorktreeClean(repoRoot)) {
        const dirty = git(['status', '--porcelain'], repoRoot).split('\n').filter(Boolean);
        fail('the worktree is not clean (' + dirty.length + ' dirty file(s); first ones: '
            + dirty.slice(0, 5).join(' | ')
            + '); commit the changes first — trust anchors are established on full commits only');
    }
}

/** Root preparation accepts a staged candidate only; unstaged/untracked files could mask its tests. */
function assertRootPreparationState(repoRoot) {
    if (trustedGate.isIndexClean(repoRoot)) {
        fail('root preparation requires a staged candidate index');
    }
    try {
        git(['diff', '--quiet'], repoRoot);
    } catch (e) {
        fail('root preparation refuses unstaged tracked changes; stage the exact candidate first');
    }
    const untracked = git(['ls-files', '--others', '--exclude-standard'], repoRoot);
    if (untracked) {
        fail('root preparation refuses untracked files that could affect validation: '
            + untracked.split('\n').slice(0, 5).join(' | '));
    }
}

const FIRST_ADMISSION_KEYS = [
    trustedGate.FIRST_ADMISSION_SOURCE_EPOCH_KEY,
    trustedGate.FIRST_ADMISSION_TARGET_EPOCH_KEY,
    trustedGate.FIRST_ADMISSION_TRUSTED_SOURCE_KEY,
    trustedGate.FIRST_ADMISSION_PARENT_KEY,
    trustedGate.FIRST_ADMISSION_TREE_KEY,
    trustedGate.FIRST_ADMISSION_CANDIDATE_KEY,
];

function getFirstAdmissionTicket(repoRoot) {
    const get = (key) => {
        try {
            return git(['config', '--local', '--get', key], repoRoot) || null;
        } catch (e) {
            return null;
        }
    };
    return {
        sourceEpoch: get(trustedGate.FIRST_ADMISSION_SOURCE_EPOCH_KEY),
        targetEpoch: get(trustedGate.FIRST_ADMISSION_TARGET_EPOCH_KEY),
        trustedSource: get(trustedGate.FIRST_ADMISSION_TRUSTED_SOURCE_KEY),
        parent: get(trustedGate.FIRST_ADMISSION_PARENT_KEY),
        tree: get(trustedGate.FIRST_ADMISSION_TREE_KEY),
        candidate: get(trustedGate.FIRST_ADMISSION_CANDIDATE_KEY),
    };
}

function ticketHasValues(ticket) {
    return Object.values(ticket).some((value) => value !== null);
}

function clearFirstAdmissionTicket(repoRoot, strict = false) {
    for (const key of FIRST_ADMISSION_KEYS) {
        try {
            git(['config', '--local', '--unset-all', key], repoRoot);
        } catch (e) {
            if (strict && getFirstAdmissionTicket(repoRoot)[ticketFieldForKey(key)] !== null) {
                throw e;
            }
        }
    }
    if (strict && ticketHasValues(getFirstAdmissionTicket(repoRoot))) {
        throw new Error('first-admission ticket could not be cleared atomically');
    }
}

function ticketFieldForKey(key) {
    const fields = ['sourceEpoch', 'targetEpoch', 'trustedSource', 'parent', 'tree', 'candidate'];
    return fields[FIRST_ADMISSION_KEYS.indexOf(key)];
}

function setFirstAdmissionTicket(repoRoot, ticket) {
    if (ticketHasValues(getFirstAdmissionTicket(repoRoot))) {
        throw new Error('an unconsumed first-admission ticket already exists');
    }
    const values = [ticket.sourceEpoch, ticket.targetEpoch, ticket.trustedSource,
        ticket.parent, ticket.tree, ticket.candidate];
    try {
        for (let i = 0; i < FIRST_ADMISSION_KEYS.length; i += 1) {
            if (values[i] !== null && values[i] !== undefined) {
                git(['config', '--local', FIRST_ADMISSION_KEYS[i], String(values[i])], repoRoot);
            }
        }
        const actual = getFirstAdmissionTicket(repoRoot);
        if (JSON.stringify(actual) !== JSON.stringify({
            sourceEpoch: String(ticket.sourceEpoch),
            targetEpoch: String(ticket.targetEpoch),
            trustedSource: ticket.trustedSource,
            parent: ticket.parent,
            tree: ticket.tree,
            candidate: ticket.candidate || null,
        })) {
            throw new Error('first-admission ticket verification failed');
        }
    } catch (e) {
        try {
            clearFirstAdmissionTicket(repoRoot, true);
        } catch (cleanupError) {
            throw new Error(e.message + '; partial ticket cleanup failed: ' + cleanupError.message);
        }
        throw e;
    }
}

function sealFirstAdmissionCandidate(repoRoot, ticket, candidateSha) {
    const current = getFirstAdmissionTicket(repoRoot);
    if (JSON.stringify(current) !== JSON.stringify({ ...ticket, candidate: null })) {
        throw new Error('first-admission ticket changed before candidate sealing');
    }
    try {
        git(['config', '--local', trustedGate.FIRST_ADMISSION_CANDIDATE_KEY, candidateSha], repoRoot);
        if (getFirstAdmissionTicket(repoRoot).candidate !== candidateSha) {
            throw new Error('first-admission candidate SHA verification failed');
        }
    } catch (e) {
        try {
            git(['config', '--local', '--unset-all', trustedGate.FIRST_ADMISSION_CANDIDATE_KEY], repoRoot);
        } catch (ignored) {
            // The key may not have been written. Preserve the prepared ticket either way.
        }
        if (getFirstAdmissionTicket(repoRoot).candidate !== null) {
            throw new Error(e.message + '; partial candidate seal cleanup failed');
        }
        throw e;
    }
}

function createIndexCandidateCommit(repoRoot) {
    const parent = trustedGate.resolveCommit(repoRoot, 'HEAD');
    if (!parent) {
        fail('HEAD must resolve to a full commit before root preparation');
    }
    const tree = git(['write-tree'], repoRoot);
    const sha = git(['commit-tree', tree, '-p', parent], repoRoot,
        { input: 'Gate root preparation validation\n' });
    if (!trustedGate.SHA_RE.test(tree) || !trustedGate.SHA_RE.test(sha)) {
        fail('cannot create the isolated staged root candidate');
    }
    return { parent, tree, sha };
}

/** 运行完整 i18n 测试套件（排除本 CLI 自身测试文件，避免递归）。 */
function runFullSuite(repoRoot) {
    const result = trustedGate.runI18nTestSuite(repoRoot, 'trust-gate.test.mjs');
    if (!result.ok) {
        fail('full i18n tests failed; refusing to update the trust anchor\n---\n'
            + result.output.slice(-8000));
    }
    return result.output;
}

/** 从给定 ref 物化 gate bundle 并执行 ref snapshot check + signature guard。 */
function validateRefWithGate(repoRoot, refSha, gateDir) {
    const checks = [];
    const checkFile = path.join(gateDir, 'scripts', 'i18n', 'check.mjs');
    const guardFile = path.join(gateDir, 'scripts', 'hooks', 'pre-push-guard.sh');
    // report-root 指向临时目录：trust 命令不得弄脏用户仓库（build/reports 不进工作树）
    const reportRoot = path.join(gateDir, 'reports');
    if (!fs.existsSync(checkFile)) {
        throw new Error('gate bundle missing scripts/i18n/check.mjs at ' + refSha);
    }
    const checkRun = run(['node', checkFile, '--repo-root', repoRoot, '--report-root', reportRoot,
        '--snapshot', 'ref', '--ref', refSha]);
    checks.push({ name: 'ref snapshot check', ok: checkRun.status === 0, output: checkRun.output });
    if (checkRun.status !== 0) {
        throw new Error('ref snapshot check failed for ' + refSha + '\n---\n'
            + (checkRun.output || '').slice(-6000));
    }
    if (!hasBash()) {
        throw new Error('bash is required to run the signature guard; install bash (Git for Windows on Windows)');
    }
    if (!fs.existsSync(guardFile)) {
        throw new Error('gate bundle missing scripts/hooks/pre-push-guard.sh at ' + refSha);
    }
    // bash 以 cwd=repoRoot + stdin 运行 guard（--repo-root .）：
    // WSL / Git Bash 都无法可靠解析 Windows 绝对路径参数，相对 cwd 的路径两种环境都安全。
    const guardScript = fs.readFileSync(guardFile, 'utf8');
    const guardRun = run(['bash', '-s', '--', '--repo-root', '.', '--ref', refSha],
        { cwd: repoRoot, input: guardScript });
    if (guardRun.status !== 0) {
        throw new Error('signature guard failed for ' + refSha + '\n---\n'
            + (guardRun.output || '').slice(-4000));
    }
    return checks;
}

/** 物化 ref 的 gate bundle 并读取 policy（无 policy → fail closed，不迁移旧 anchor）。 */
function materializeAndLoadPolicy(repoRoot, sha, gateDir, expectedEpoch = trustedGate.CURRENT_GATE_EPOCH) {
    trustedGate.materializeTrustedGate(repoRoot, sha, gateDir);
    const policy = trustedGate.loadPolicyFromDir(gateDir, expectedEpoch);
    if (!policy) {
        throw new Error('gate bundle at ' + sha + ' has no gate-policy.json;'
            + ' obsolete-epoch anchors are not migrated; fail closed');
    }
    const start = trustedGate.resolveCommit(repoRoot, policy.i18nEnforcementStartCommit);
    if (!start) {
        throw new Error('gate-policy.json: i18nEnforcementStartCommit '
            + policy.i18nEnforcementStartCommit + ' does not resolve to a commit in this repository');
    }
    return policy;
}

/** 运行候选自身的 contract（root admission：无 predecessor，契约对自身 + 强制自保护）。 */
function runRootContract(repoRoot, candidateSha, gateDir) {
    const contractFile = path.join(gateDir, 'scripts', 'i18n', 'gate-contract.mjs');
    const policyFile = path.join(gateDir, 'scripts', 'i18n', 'gate-policy.json');
    if (!fs.existsSync(contractFile) || !fs.existsSync(policyFile)) {
        throw new Error('root admission requires the candidate to carry gate-contract.mjs and'
            + ' gate-policy.json; fail closed');
    }
    const contractRun = run(['node', contractFile, '--repo-root', repoRoot,
        '--candidate-ref', candidateSha, '--force-self-protection']);
    if (contractRun.status !== 0) {
        throw new Error('root contract self-test rejected ' + candidateSha + '\n---\n'
            + (contractRun.output || '').slice(-8000));
    }
}

/** 由 trusted contract 验证候选（normal advance 路径；候选不能自我批准）。 */
function runTrustedContract(repoRoot, candidateSha, trustedDir) {
    const contractFile = path.join(trustedDir, 'scripts', 'i18n', 'gate-contract.mjs');
    const policyFile = path.join(trustedDir, 'scripts', 'i18n', 'gate-policy.json');
    if (!fs.existsSync(contractFile) || !fs.existsSync(policyFile)) {
        throw new Error('trusted anchor lacks the current contract/policy;'
            + ' obsolete-epoch anchors are not migrated; fail closed');
    }
    const contractRun = run(['node', contractFile, '--repo-root', repoRoot,
        '--candidate-ref', candidateSha]);
    if (contractRun.status !== 0) {
        throw new Error('the trusted gate contract rejected ' + candidateSha + '\n---\n'
            + (contractRun.output || '').slice(-8000));
    }
}

/** gate parity 审计：advance = trusted vs candidate；adopt-root = candidate vs invariants。 */
function runGateParity(repoRoot, candidateSha, trustedDir, invariantsOnly) {
    const fromTrusted = trustedDir ? path.join(trustedDir, 'scripts', 'ci', 'gate-parity.mjs') : null;
    const parityPath = (fromTrusted && fs.existsSync(fromTrusted))
        ? fromTrusted
        : path.join(repoRoot, 'scripts', 'ci', 'gate-parity.mjs');
    if (!fs.existsSync(parityPath)) {
        throw new Error('scripts/ci/gate-parity.mjs is missing; fail closed');
    }
    const args = ['node', parityPath, '--repo-root', repoRoot, '--candidate-ref', candidateSha];
    if (invariantsOnly) {
        args.push('--invariants');
    } else {
        args.push('--trusted-dir', trustedDir);
    }
    const parityRun = run(args);
    if (parityRun.status !== 0) {
        throw new Error('gate parity audit rejected ' + candidateSha + '\n---\n'
            + (parityRun.output || '').slice(-8000));
    }
}

function validateFirstAdmissionSpec(spec) {
    if (spec.schemaVersion !== 1 || spec.sourceEpoch !== 3 || spec.targetEpoch !== 4
        || spec.protectedRemote !== 'origin'
        || spec.protectedBranch !== 'refs/heads/master'
        || spec.protectedBranchRef !== 'refs/remotes/origin/master'
        || !spec.targetPolicy || typeof spec.targetPolicy !== 'object'
        || !spec.targetInvariants || typeof spec.targetInvariants !== 'object'
        || !spec.targetRuleset || typeof spec.targetRuleset !== 'object'
        || !Array.isArray(spec.allowedChangedPaths) || spec.allowedChangedPaths.length === 0) {
        throw new Error('invalid Epoch 3 first-admission bridge specification');
    }
    return spec;
}

function loadFirstAdmissionSpec(bundleRoot) {
    const file = path.join(bundleRoot, ...FIRST_ADMISSION_SPEC_REL.split('/'));
    if (!fs.existsSync(file)) {
        throw new Error('trusted verifier has no Epoch 3 first-admission bridge specification');
    }
    return validateFirstAdmissionSpec(JSON.parse(fs.readFileSync(file, 'utf8')));
}

function loadFirstAdmissionSpecAtRef(repoRoot, ref) {
    return validateFirstAdmissionSpec(loadJsonAtRef(repoRoot, ref, FIRST_ADMISSION_SPEC_REL));
}

function assertExecutingTrustedBundle(repoRoot, trustedSource) {
    const bundleRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
    if (fs.realpathSync(bundleRoot) === fs.realpathSync(repoRoot)) {
        throw new Error('first-admission bridge must execute from an external materialized trusted bundle');
    }
    const trustedPaths = git(['ls-tree', '-r', '--name-only', trustedSource, '--',
        ...trustedGate.GATE_PATHS], repoRoot).split('\n').filter(Boolean);
    for (const rel of trustedPaths) {
        const own = path.join(bundleRoot, ...rel.split('/'));
        if (!fs.existsSync(own)) {
            throw new Error('executing bridge bundle is missing ' + rel);
        }
        const trusted = gitBuffer(['show', trustedSource + ':' + rel], repoRoot);
        if (!fs.readFileSync(own).equals(trusted)) {
            throw new Error('first-admission bridge must execute from the exact trusted bundle: ' + rel);
        }
    }
    return { bundleRoot, spec: loadFirstAdmissionSpec(bundleRoot) };
}

function loadJsonAtRef(repoRoot, ref, rel) {
    return JSON.parse(git(['show', ref + ':' + rel], repoRoot));
}

function resolveLiveProtectedTip(repoRoot, spec) {
    const lookup = run(['git', 'ls-remote', '--exit-code', spec.protectedRemote,
        spec.protectedBranch], {
        cwd: repoRoot,
        env: { ...process.env, GIT_TERMINAL_PROMPT: '0' },
    });
    const lines = (lookup.stdout || '').trim().split(/\r?\n/).filter(Boolean);
    if (lookup.status !== 0 || lines.length !== 1) {
        throw new Error('cannot resolve the live protected master tip from origin');
    }
    const fields = lines[0].trim().split(/\s+/);
    if (fields.length !== 2 || fields[1] !== spec.protectedBranch
        || !trustedGate.SHA_RE.test(fields[0])) {
        throw new Error('origin returned an invalid protected master tip');
    }
    return fields[0];
}

function assertProtectedFirstAdmissionSource(repoRoot, trustedSource, spec) {
    const configuredSource = trustedGate.getTrustedRef(repoRoot);
    const configuredEpoch = trustedGate.getTrustedEpoch(repoRoot);
    if (configuredSource !== trustedSource || configuredEpoch !== String(spec.sourceEpoch)) {
        throw new Error('first-admission source must exactly match local trustedGateRef + sourceEpoch');
    }
    const localProtectedTip = trustedGate.resolveCommit(repoRoot, spec.protectedBranchRef);
    const liveProtectedTip = resolveLiveProtectedTip(repoRoot, spec);
    if (!trustedGate.resolveCommit(repoRoot, trustedSource)
        || localProtectedTip !== trustedSource || liveProtectedTip !== trustedSource) {
        throw new Error('trusted first-admission source must equal the local and live protected master tip');
    }
    const previousRoot = trustedGate.resolveRootTag(repoRoot, spec.sourceEpoch);
    if (!previousRoot || !trustedGate.isAncestor(repoRoot, previousRoot, trustedSource)) {
        throw new Error('protected previous-epoch root is missing from the trusted source chain');
    }
}

function assertExactCoreReduction(repoRoot, candidateSha, trustedSource, spec) {
    const sourceRules = loadJsonAtRef(repoRoot, trustedSource,
        'scripts/ci/github-ruleset-invariants.json');
    const candidateRules = loadJsonAtRef(repoRoot, candidateSha,
        'scripts/ci/github-ruleset-invariants.json');
    if (JSON.stringify(candidateRules.master) !== JSON.stringify(spec.targetRuleset.master)) {
        throw new Error('Epoch 4 master ruleset invariants do not match the trusted transition contract');
    }
    const sourceRootName = trustedGate.rootTagNameForEpoch(spec.sourceEpoch);
    const targetRootName = trustedGate.rootTagNameForEpoch(spec.targetEpoch);
    if (!candidateRules[sourceRootName]
        || JSON.stringify(candidateRules[sourceRootName]) !== JSON.stringify(sourceRules[sourceRootName])
        || JSON.stringify(candidateRules[targetRootName]) !== JSON.stringify(sourceRules[sourceRootName])) {
        throw new Error('Epoch 4 ruleset invariants must preserve Epoch 3 root protection and add identical Epoch 4 protection');
    }

    const sourcePolicy = loadJsonAtRef(repoRoot, trustedSource, 'scripts/i18n/gate-policy.json');
    const candidatePolicy = loadJsonAtRef(repoRoot, candidateSha, 'scripts/i18n/gate-policy.json');
    if (sourcePolicy.gateEpoch !== spec.sourceEpoch || candidatePolicy.gateEpoch !== spec.targetEpoch) {
        throw new Error('first admission requires the exact sourceEpoch=3 and targetEpoch=4 transition');
    }
    if (JSON.stringify(candidatePolicy) !== JSON.stringify(spec.targetPolicy)) {
        throw new Error('Epoch 4 gate policy does not match the trusted transition contract');
    }
    const candidateInvariants = loadJsonAtRef(repoRoot, candidateSha, 'scripts/ci/gate-invariants.json');
    if (JSON.stringify(candidateInvariants) !== JSON.stringify(spec.targetInvariants)) {
        throw new Error('Epoch 4 trusted release core invariants do not match the transition contract');
    }
    const bridgeAtCandidate = run(['git', 'cat-file', '-e', candidateSha + ':' + FIRST_ADMISSION_SPEC_REL],
        { cwd: repoRoot });
    if (bridgeAtCandidate.status === 0) {
        throw new Error('Epoch 4 root must not retain the one-time Epoch 3 bridge specification');
    }
    for (const rel of spec.targetPolicy.minimumTrustedVerifier.requiredFiles) {
        const present = run(['git', 'cat-file', '-e', candidateSha + ':' + rel], { cwd: repoRoot });
        if (present.status !== 0) {
            throw new Error('Epoch 4 root is missing trusted release core file: ' + rel);
        }
    }

    const allowed = new Set(spec.allowedChangedPaths);
    const changes = git(['diff', '--name-status', trustedSource, candidateSha], repoRoot)
        .split('\n').filter(Boolean);
    for (const line of changes) {
        const fields = line.split('\t');
        const status = fields[0];
        const rel = fields[fields.length - 1];
        if (!allowed.has(rel) || !/^[AMD]$/.test(status)) {
            throw new Error('first admission refuses an out-of-scope change: ' + line);
        }
    }
}

function updateIndexBlob(repoRoot, indexFile, refForMode, rel, bytes) {
    const env = { ...process.env, GIT_INDEX_FILE: indexFile };
    const modeLine = git(['ls-tree', refForMode, '--', rel], repoRoot);
    const mode = modeLine ? modeLine.split(/\s+/)[0] : '100644';
    const blob = git(['hash-object', '-w', '--stdin'], repoRoot, { input: bytes });
    git(['update-index', '--add', '--cacheinfo', mode, blob, rel], repoRoot, { env });
}

function normalizeFirstAdmissionCandidate(repoRoot, candidateSha, trustedSource, spec) {
    const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv-first-admission-normalized-'));
    const indexFile = path.join(temp, 'index');
    const env = { ...process.env, GIT_INDEX_FILE: indexFile };
    try {
        git(['read-tree', candidateSha], repoRoot, { env });
        for (const rel of spec.allowedChangedPaths) {
            const sourceEntry = run(['git', 'cat-file', '-e', trustedSource + ':' + rel], { cwd: repoRoot });
            if (sourceEntry.status === 0) {
                updateIndexBlob(repoRoot, indexFile, trustedSource, rel,
                    gitBuffer(['show', trustedSource + ':' + rel], repoRoot));
            } else {
                git(['update-index', '--force-remove', '--', rel], repoRoot, { env });
            }
        }
        const tree = git(['write-tree'], repoRoot, { env });
        const sourceTree = git(['rev-parse', trustedSource + '^{tree}'], repoRoot);
        if (tree !== sourceTree) {
            throw new Error('normalized first-admission tree differs from the trusted source');
        }
        return git(['commit-tree', tree, '-p', trustedSource], repoRoot,
            { input: 'Normalized Epoch 3 first-admission candidate\n' });
    } finally {
        rmrfRetry(temp);
    }
}

function runPrepareRootFromTrustedBundle(repoRoot, epochArg, trustedSource) {
    if (trustedGate.isCI()) {
        fail('root preparation is forbidden in CI (CI=true); it is an explicit local trust decision');
    }
    let trustedExecution;
    try {
        trustedExecution = assertExecutingTrustedBundle(repoRoot, trustedSource);
        if (Number(epochArg) !== trustedExecution.spec.targetEpoch) {
            throw new Error('--epoch must be exactly ' + trustedExecution.spec.targetEpoch);
        }
        assertProtectedFirstAdmissionSource(repoRoot, trustedSource, trustedExecution.spec);
        if (trustedGate.resolveCommit(repoRoot, 'HEAD') !== trustedSource) {
            throw new Error('Epoch 4 root parent must exactly equal the trusted Epoch 3 source');
        }
    } catch (e) {
        fail(e.message);
    }
    assertRootPreparationState(repoRoot);
    if (ticketHasValues(getFirstAdmissionTicket(repoRoot))) {
        fail('an unconsumed first-admission ticket already exists');
    }
    const candidate = createIndexCandidateCommit(repoRoot);
    const candidateDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv-prepare-root-candidate-'));
    const trustedDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv-prepare-root-trusted-'));
    try {
        console.log('trust-gate: trusted Epoch ' + trustedExecution.spec.sourceEpoch
            + ' first-admission bridge is auditing Gate Epoch ' + trustedExecution.spec.targetEpoch
            + ' staged tree '
            + candidate.tree + '...');
        assertExactCoreReduction(repoRoot, candidate.sha, trustedSource, trustedExecution.spec);
        const normalized = normalizeFirstAdmissionCandidate(repoRoot, candidate.sha,
            trustedSource, trustedExecution.spec);
        trustedGate.materializeTrustedGate(repoRoot, trustedSource, trustedDir);
        trustedGate.assertSupportedTrustedVerifierDir(trustedDir,
            trustedExecution.spec.sourceEpoch);
        runTrustedContract(repoRoot, normalized, trustedDir);
        runGateParity(repoRoot, normalized, trustedDir, false);

        const policy = materializeAndLoadPolicy(repoRoot, candidate.sha, candidateDir,
            trustedExecution.spec.targetEpoch);
        trustedGate.assertSupportedTrustedVerifierDir(candidateDir,
            trustedExecution.spec.targetEpoch,
            policy.minimumTrustedVerifier);
        const missing = policy.requiredPaths
            .filter((p) => !fs.existsSync(path.join(candidateDir, ...p.split('/'))));
        if (missing.length > 0) {
            fail('required gate files missing from staged root candidate: ' + missing.join(', '));
        }
        runFullSuite(repoRoot);
        validateRefWithGate(repoRoot, candidate.sha, candidateDir);
        runRootContract(repoRoot, candidate.sha, candidateDir);
        runGateParity(repoRoot, candidate.sha, candidateDir, true);
        setFirstAdmissionTicket(repoRoot, {
            sourceEpoch: trustedExecution.spec.sourceEpoch,
            targetEpoch: trustedExecution.spec.targetEpoch,
            trustedSource,
            parent: candidate.parent,
            tree: candidate.tree,
            candidate: null,
        });
    } finally {
        rmrfRetry(candidateDir);
        rmrfRetry(trustedDir);
    }
    console.log('trust-gate: Gate Epoch ' + trustedExecution.spec.targetEpoch
        + ' root tree prepared by trusted source ' + trustedSource + ': parent '
        + candidate.parent + ', tree ' + candidate.tree
        + '. Commit it once, then run the trusted bridge with --seal-root --ref HEAD.');
}

function runPrepareRoot(repoRoot, epochArg, trustedSourceArg) {
    if (!trustedSourceArg) {
        fail('prepare-root must execute directly from a materialized Epoch 3 trusted bundle'
            + ' with --trusted-source <exact-sha>; candidate launchers are forbidden');
    }
    runPrepareRootFromTrustedBundle(repoRoot, epochArg, trustedSourceArg);
}

function runSealRoot(repoRoot, refArg, trustedSource) {
    if (trustedGate.isCI()) {
        fail('root sealing is forbidden in CI (CI=true); it is an explicit local trust decision');
    }
    if (!trustedSource) {
        fail('seal-root must execute directly from a materialized Epoch 3 trusted bundle'
            + ' with --trusted-source <exact-sha>; candidate launchers are forbidden');
    }
    let trustedExecution;
    try {
        trustedExecution = assertExecutingTrustedBundle(repoRoot, trustedSource);
        assertProtectedFirstAdmissionSource(repoRoot, trustedSource, trustedExecution.spec);
    } catch (e) {
        fail(e.message);
    }
    assertCleanState(repoRoot);
    const candidateSha = trustedGate.resolveCommit(repoRoot, refArg);
    if (!candidateSha || candidateSha !== trustedGate.resolveCommit(repoRoot, 'HEAD')) {
        fail('seal-root requires --ref to resolve to the current HEAD commit');
    }
    const ticket = getFirstAdmissionTicket(repoRoot);
    const expected = {
        sourceEpoch: String(trustedExecution.spec.sourceEpoch),
        targetEpoch: String(trustedExecution.spec.targetEpoch),
        trustedSource,
        parent: trustedSource,
        tree: git(['rev-parse', candidateSha + '^{tree}'], repoRoot),
        candidate: null,
    };
    if (JSON.stringify(ticket) !== JSON.stringify(expected)) {
        fail('seal-root requires the exact unconsumed prepared ticket and candidate tree');
    }
    const parents = git(['rev-list', '--parents', '-n', '1', candidateSha], repoRoot).split(/\s+/);
    if (parents.length !== 2 || parents[1] !== trustedSource) {
        fail('sealed Epoch 4 root candidate must have the exact trusted source as its single parent');
    }
    try {
        assertExactCoreReduction(repoRoot, candidateSha, trustedSource, trustedExecution.spec);
        sealFirstAdmissionCandidate(repoRoot, ticket, candidateSha);
    } catch (e) {
        fail(e.message);
    }
    console.log('trust-gate: trusted first-admission bridge sealed the unique Epoch '
        + trustedExecution.spec.targetEpoch + ' root candidate ' + candidateSha);
}

function assertFirstAdmissionCommitPoint(repoRoot, sha, previousRef, previousEpoch, ticket, sourceSpec) {
    try {
        assertProtectedFirstAdmissionSource(repoRoot, previousRef, sourceSpec);
        const currentTicket = getFirstAdmissionTicket(repoRoot);
        const parents = git(['rev-list', '--parents', '-n', '1', sha], repoRoot).split(/\s+/);
        const tree = git(['rev-parse', sha + '^{tree}'], repoRoot);
        if (trustedGate.getTrustedRef(repoRoot) !== previousRef
            || trustedGate.getTrustedEpoch(repoRoot) !== String(previousEpoch)
            || trustedGate.resolveCommit(repoRoot, sourceSpec.protectedBranchRef) !== previousRef
            || JSON.stringify(currentTicket) !== JSON.stringify(ticket)
            || currentTicket.candidate !== sha
            || parents.length !== 2 || parents[1] !== previousRef
            || tree !== ticket.tree
            || trustedGate.resolveCommit(repoRoot, 'HEAD') !== sha
            || !trustedGate.isIndexClean(repoRoot)
            || !trustedGate.isWorktreeClean(repoRoot)) {
            throw new Error('trusted source, ticket, root commit or repository state changed');
        }
    } catch (e) {
        throw new Error('first-admission commit-point revalidation failed: ' + e.message);
    }
}

function setAnchorAndConsumeFirstAdmission(repoRoot, sha, epoch, previousRef, previousEpoch,
    ticket, sourceSpec) {
    assertFirstAdmissionCommitPoint(repoRoot, sha, previousRef, previousEpoch, ticket, sourceSpec);
    try {
        trustedGate.setTrustedAnchor(repoRoot, sha, epoch);
        clearFirstAdmissionTicket(repoRoot, true);
    } catch (e) {
        const rollbackErrors = [];
        try {
            trustedGate.setTrustedAnchor(repoRoot, previousRef, previousEpoch);
        } catch (rollbackError) {
            rollbackErrors.push('anchor rollback failed: ' + rollbackError.message);
        }
        try {
            clearFirstAdmissionTicket(repoRoot);
            setFirstAdmissionTicket(repoRoot, ticket);
        } catch (rollbackError) {
            rollbackErrors.push('ticket rollback failed: ' + rollbackError.message);
        }
        throw new Error(e.message + (rollbackErrors.length > 0
            ? '; ' + rollbackErrors.join('; ') : ''));
    }
}

function runAdoptRoot(repoRoot, refArg, epochArg) {
    if (trustedGate.isCI()) {
        fail('root adoption is forbidden in CI (CI=true); the Gate Epoch '
            + trustedGate.CURRENT_GATE_EPOCH + ' trust root must be'
            + ' established by a human locally');
    }
    if (String(epochArg) !== String(trustedGate.CURRENT_GATE_EPOCH)) {
        fail('--epoch must be exactly ' + trustedGate.CURRENT_GATE_EPOCH
            + ' (the executing verifier supports exactly one gate epoch; got ' + epochArg + ')');
    }
    const current = trustedGate.getTrustedRef(repoRoot);
    const previousEpoch = Number(trustedGate.getTrustedEpoch(repoRoot));
    const sha = trustedGate.resolveCommit(repoRoot, refArg);
    if (!sha) {
        fail('ref "' + refArg + '" must resolve to a full commit; worktree paths are not accepted');
    }
    assertCleanState(repoRoot);

    let firstAdmissionTicket = null;
    let firstAdmissionSourceSpec = null;
    if (current) {
        if (previousEpoch === trustedGate.CURRENT_GATE_EPOCH) {
            fail('a local trust anchor already exists at ' + current
                + '; use --advance within the current epoch');
        }
        let sourceSpec;
        try {
            sourceSpec = loadFirstAdmissionSpecAtRef(repoRoot, current);
            assertProtectedFirstAdmissionSource(repoRoot, current, sourceSpec);
        } catch (e) {
            fail('first-admission adoption source check failed: ' + e.message);
        }
        const prepared = getFirstAdmissionTicket(repoRoot);
        const parents = git(['rev-list', '--parents', '-n', '1', sha], repoRoot).split(/\s+/);
        const tree = git(['rev-parse', sha + '^{tree}'], repoRoot);
        if (previousEpoch !== trustedGate.CURRENT_GATE_EPOCH - 1
            || prepared.sourceEpoch !== String(previousEpoch)
            || prepared.targetEpoch !== String(trustedGate.CURRENT_GATE_EPOCH)
            || prepared.trustedSource !== current
            || prepared.candidate !== sha
            || parents.length !== 2 || prepared.parent !== parents[1]
            || prepared.parent !== current || prepared.tree !== tree
            || !trustedGate.isAncestor(repoRoot, current, 'refs/remotes/origin/master')) {
            fail('existing-anchor root adoption requires an unconsumed trusted first-admission ticket'
                + ' with the exact source epoch, target epoch, trusted source, parent, tree and sealed candidate SHA');
        }
        const previousRoot = trustedGate.resolveRootTag(repoRoot, previousEpoch);
        if (!previousRoot || !trustedGate.isAncestor(repoRoot, previousRoot, current)) {
            fail('first-admission ticket source no longer belongs to the protected previous root chain');
        }
        firstAdmissionTicket = prepared;
        firstAdmissionSourceSpec = sourceSpec;
    } else if (ticketHasValues(getFirstAdmissionTicket(repoRoot))) {
        fail('a first-admission ticket cannot be consumed without its trusted source anchor');
    }

    const gateDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv-adopt-root-'));
    try {
        // ROOT ADMISSION：物化候选自身的 gate bundle（候选 = root candidate）
        console.log('trust-gate: ROOT ADMISSION — ' + sha
            + ' is the Gate Epoch ' + trustedGate.CURRENT_GATE_EPOCH
            + ' root candidate; running the full root admission suite...');
        const policy = materializeAndLoadPolicy(repoRoot, sha, gateDir);
        // 新标准 root admission 也必须满足当前 verifier baseline（旧 v3 root 候选不再有资格；
        // 旧提交按旧标准存在，新标准只接受具备当前能力的 verifier）
        try {
            trustedGate.assertSupportedTrustedVerifierDir(gateDir,
                trustedGate.CURRENT_GATE_EPOCH, policy.minimumTrustedVerifier);
        } catch (e) {
            fail('OUTDATED GATE VERIFIER: ' + e.message);
        }
        const required = policy.requiredPaths;
        const missing = required.filter((p) => !fs.existsSync(path.join(gateDir, ...p.split('/'))));
        if (missing.length > 0) {
            fail('required gate files missing at ' + sha + ': ' + missing.join(', '));
        }

        console.log('trust-gate: running the full i18n test suite (incl. snapshot / hook hardening)...');
        runFullSuite(repoRoot);
        console.log('trust-gate: running ref snapshot check and signature guard for ' + sha + '...');
        validateRefWithGate(repoRoot, sha, gateDir);
        console.log('trust-gate: running the root contract self-test (workflow / package / self-protection)...');
        runRootContract(repoRoot, sha, gateDir);
        console.log('trust-gate: running gate parity against the Gate Epoch '
            + trustedGate.CURRENT_GATE_EPOCH + ' invariants manifest...');
        runGateParity(repoRoot, sha, gateDir, true);
    } finally {
        rmrfRetry(gateDir);
    }

    console.error('');
    console.error('This is the explicit Gate Epoch ' + trustedGate.CURRENT_GATE_EPOCH
        + ' root adoption decision.');
    console.error('The repository state at ' + sha + ' becomes the local Gate Epoch '
        + trustedGate.CURRENT_GATE_EPOCH + ' trust root.');
    console.error('Only an explicit trust command can advance it.');
    console.error('');
    if (firstAdmissionTicket) {
        try {
            setAnchorAndConsumeFirstAdmission(repoRoot, sha, trustedGate.CURRENT_GATE_EPOCH,
                current, previousEpoch, firstAdmissionTicket, firstAdmissionSourceSpec);
        } catch (e) {
            fail('root adoption state transaction failed: ' + e.message);
        }
    } else {
        trustedGate.setTrustedAnchor(repoRoot, sha);
    }
    console.log('trust-gate: Gate Epoch ' + trustedGate.CURRENT_GATE_EPOCH
        + ' root adopted; local anchor set to '
        + 'git config --local pixiv.i18n.trustedGateEpoch ' + trustedGate.CURRENT_GATE_EPOCH
        + ' + pixiv.i18n.trustedGateRef ' + sha);
}

function runAdvance(repoRoot, refArg) {
    if (trustedGate.isCI()) {
        fail('advance trust is forbidden in CI (CI=true); CI must never modify the user local git config');
    }
    const current = trustedGate.getTrustedRef(repoRoot);
    if (!current) {
        fail('no trusted gate anchor; run --adopt-root for the current Gate Epoch first');
    }
    if (!trustedGate.isTrustedEpochCurrent(repoRoot)) {
        fail('OBSOLETE GATE EPOCH: the local anchor belongs to epoch '
            + (trustedGate.getTrustedEpoch(repoRoot) || '<missing>') + ' (' + trustedGate.describeTrustedEpoch(repoRoot)
            + '). Obsolete anchors are not migrated and have no automatic upgrade rights;'
            + ' run --adopt-root for the current Gate Epoch instead');
    }
    const sha = trustedGate.resolveCommit(repoRoot, refArg);
    if (!sha) {
        fail('ref "' + refArg + '" must resolve to a full commit; worktree paths are not accepted');
    }
    assertCleanState(repoRoot);
    if (sha === current) {
        console.log('trust-gate: candidate ref equals the current anchor; nothing to do.');
        return;
    }
    // 单调推进：contract 运行前先验证候选是当前锚点的后代（向后 / sibling /
    // 无共同历史一律拒绝）。candidate 等于 current 已在上面处理（no-op）。
    if (!trustedGate.isAncestor(repoRoot, current, sha)) {
        fail('candidate trust anchor is not a descendant of the current anchor ('
            + current + '); refusing to advance');
    }
    // 若本地已安装当前 Epoch root tag：当前锚点与候选都必须包含该 root
    const root = trustedGate.resolveRootTag(repoRoot);
    if (root) {
        if (!trustedGate.isAncestor(repoRoot, root, current)) {
            fail('the current anchor ' + current + ' does not descend from the Gate Epoch '
                + trustedGate.CURRENT_GATE_EPOCH + ' trust root ' + root + '; re-adopt the root first');
        }
        if (!trustedGate.isAncestor(repoRoot, root, sha)) {
            fail('candidate ' + sha + ' does not descend from the Gate Epoch '
                + trustedGate.CURRENT_GATE_EPOCH + ' trust root ' + root
                + '; v1 / legacy / transition compatibility paths are retired; refusing to advance');
        }
    }

    const trustedDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv-advance-trusted-'));
    try {
        // 1. 从当前 trusted ref 物化当前 contract + policy
        trustedGate.materializeTrustedGate(repoRoot, current, trustedDir);
        // 1.5 当前 trusted verifier 必须满足当前 verifier baseline（能力只增不减，不兼容旧 verifier）
        try {
            trustedGate.assertSupportedTrustedVerifierDir(trustedDir);
        } catch (e) {
            fail('OUTDATED GATE VERIFIER: ' + e.message);
        }

        // 2. trusted contract 验证 candidate ref（policy / required files / checker / hooks / 自保护）
        console.log('trust-gate: running the trusted gate contract against ' + sha + '...');
        runTrustedContract(repoRoot, sha, trustedDir);

        // 3. gate parity：trusted vs candidate（门禁不减审计）
        console.log('trust-gate: running gate parity (trusted vs candidate)...');
        runGateParity(repoRoot, sha, trustedDir, false);

        // 4. 完整 tests 通过（工作树；advance 前必须干净）
        console.log('trust-gate: running the full i18n test suite...');
        runFullSuite(repoRoot);

        // 5. candidate ref snapshot check（trusted checker）+ signature guard（trusted guard）
        console.log('trust-gate: running ref snapshot check and signature guard for ' + sha + '...');
        validateRefWithGate(repoRoot, sha, trustedDir);

        // 6. 全部通过后更新 local config（epoch 不变，只推进 ref）
        trustedGate.setTrustedAnchor(repoRoot, sha);
        console.log('trust-gate: local trust anchor advanced from ' + current + ' to ' + sha);
    } finally {
        rmrfRetry(trustedDir);
    }
}

function runShow(repoRoot) {
    const current = trustedGate.getTrustedRef(repoRoot);
    const epoch = trustedGate.getTrustedEpoch(repoRoot);
    const root = trustedGate.resolveRootTag(repoRoot);
    if (!current || !trustedGate.isTrustedEpochCurrent(repoRoot)) {
        console.log('trustedGateEpoch: ' + (epoch || '<not set>'));
        console.log('trustedGateRef: ' + (current || '<not set>'));
        console.log('gateEpochRootTag: ' + (root || 'refs/tags/'
            + trustedGate.rootTagNameForEpoch(trustedGate.CURRENT_GATE_EPOCH) + ' <missing>'));
        console.log('run: npm run i18n:trust-gate -- --adopt-root --ref HEAD --epoch '
            + trustedGate.CURRENT_GATE_EPOCH);
        return;
    }
    let contractVersion = 'n/a';
    let baseline = 'n/a';
    const gateDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv-trust-show-'));
    try {
        try {
            trustedGate.materializeTrustedGate(repoRoot, current, gateDir);
            const policy = JSON.parse(fs.readFileSync(
                path.join(gateDir, 'scripts', 'i18n', 'gate-policy.json'), 'utf8'));
            contractVersion = Number.isInteger(policy.contractVersion)
                ? String(policy.contractVersion) : 'n/a';
            try {
                trustedGate.assertSupportedTrustedVerifierDir(gateDir);
                baseline = 'OK';
            } catch (e) {
                baseline = 'BELOW CURRENT VERIFIER BASELINE (OUTDATED GATE VERIFIER)';
            }
        } catch (e) {
            // anchor 无法物化时只提示
        }
    } finally {
        rmrfRetry(gateDir);
    }
    console.log('trustedGateEpoch: ' + epoch);
    console.log('trustedGateRef: ' + current);
    console.log('contractVersion: ' + contractVersion);
    console.log('verifierBaseline: ' + baseline);
    console.log('gateEpochRootTag: ' + (root || 'refs/tags/'
        + trustedGate.rootTagNameForEpoch(trustedGate.CURRENT_GATE_EPOCH)
        + ' <missing (install after admission)>'));
}

function parseArgs(argv) {
    const args = { command: null, ref: null, epoch: null, trustedSource: null, version: false };
    for (let i = 0; i < argv.length; i += 1) {
        const arg = argv[i];
        if (arg === '--prepare-root' || arg === '--seal-root' || arg === '--adopt-root'
            || arg === '--advance' || arg === '--show') {
            args.command = arg.slice(2);
        } else if (arg === '--ref') {
            args.ref = argv[++i];
        } else if (arg === '--epoch') {
            args.epoch = argv[++i];
        } else if (arg === '--trusted-source') {
            args.trustedSource = argv[++i];
        } else if (arg === '--version') {
            args.version = true;
        } else {
            throw new Error('unknown argument: ' + arg);
        }
    }
    if (args.version) {
        args.command = 'version';
    }
    if (args.command === 'adopt-root') {
        if (!args.ref) {
            throw new Error('--ref <commit> is required for adopt-root');
        }
        if (!args.epoch) {
            throw new Error('--epoch <current-epoch> is required for adopt-root');
        }
    }
    if (args.command === 'prepare-root' && !args.epoch) {
        throw new Error('--epoch <next-epoch> is required for prepare-root');
    }
    if (args.command === 'seal-root' && !args.ref) {
        throw new Error('--ref <commit> is required for seal-root');
    }
    if (args.command === 'advance') {
        if (!args.ref) {
            throw new Error('--ref <commit> is required for advance');
        }
    }
    if (!args.command) {
        throw new Error('usage: trust-gate.mjs --show | --prepare-root --epoch <next> |'
            + ' --seal-root --ref HEAD |'
            + ' --adopt-root --ref HEAD --epoch <current> | --advance --ref HEAD');
    }
    return args;
}

function main() {
    let args;
    try {
        args = parseArgs(process.argv.slice(2));
    } catch (e) {
        fail(e.message);
        return;
    }
    if (args.command === 'version') {
        console.log('i18n-trust-gate ' + TRUST_CLI_VERSION);
        return;
    }
    const repoRoot = resolveRepoRoot();
    if (!repoRoot) {
        return;
    }
    if (args.command === 'show') {
        runShow(repoRoot);
        return;
    }
    if (args.command === 'prepare-root') {
        runPrepareRoot(repoRoot, args.epoch, args.trustedSource);
        return;
    }
    if (args.command === 'seal-root') {
        runSealRoot(repoRoot, args.ref, args.trustedSource);
        return;
    }
    if (args.command === 'adopt-root') {
        runAdoptRoot(repoRoot, args.ref, args.epoch);
        return;
    }
    if (args.command === 'advance') {
        runAdvance(repoRoot, args.ref);
        return;
    }
    fail('unknown command: ' + args.command);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    main();
}

export { assertExactCoreReduction, normalizeFirstAdmissionCandidate, validateFirstAdmissionSpec };
