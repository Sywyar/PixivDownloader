#!/usr/bin/env node
'use strict';
/**
 * 本地可信 Gate Anchor 管理命令（Gate Epoch 2 单一标准）。
 *
 * 用法：
 *   npm run i18n:trust-gate -- --show
 *   npm run i18n:trust-gate -- --prepare-root --epoch 3
 *   npm run i18n:trust-gate -- --adopt-root --ref HEAD --epoch 2
 *   npm run i18n:trust-gate -- --advance --ref HEAD
 *   npm run i18n:trust-gate -- --version
 *
 * prepare-root（Epoch 2 → 3 一次性 first admission）：
 * - 当前工作树脚本只负责从 pixiv.i18n.trustedGateRef 物化并启动 bridge；实际批准代码、
 *   library 与 bridge spec 必须逐字来自该 trusted bundle；
 * - trusted source 必须属于受保护 origin/master 历史、包含 Epoch 2 root，且必须精确等于
 *   staged Epoch 3 root 的单一 parent；candidate 只作为被审核对象；
 * - bridge 只归一化 sourceEpoch=2 → targetEpoch=3、root 身份与已知 GATE-03 context 身份纠正，
 *   其它内容仍由 Epoch 2 trusted contract/parity 审核；
 * - 成功后只写仓库外的一次性 ticket，绑定双 epoch、trusted source、parent 与 tree；
 *   失败不推进 anchor，也不写部分 ticket。
 *
 * adopt-root（人工 root adoption / TOFU；Epoch 2 root 的唯一建立方式）：
 * - 新的 root 不可能由自己自动证明自己可信：root 由人工 code review + 完整自动测试 +
 *   root admission 门禁共同建立，本命令只执行 root-specific 自动检查；
 * - 只能对完整 commit 执行，不接受工作树路径；ref 必须精确解析为 commit；
 * - 工作区和 index 必须干净；
 * - 必须满足：epoch == 2、policy 有效（gateEpoch == 2、contractVersion >= 3）、
 *   all required paths present、完整 i18n tests、ref snapshot、signature guard、
 *   workflow contract（candidate contract 对自身 + --force-self-protection）、
 *   package contract、gate parity（--invariants）、self-protection、
 *   snapshot hardening、hook hardening（后两者在完整 i18n 测试套件内）；
 * - 全部通过才写入 git config --local pixiv.i18n.trustedGateEpoch 2 +
 *   pixiv.i18n.trustedGateRef <sha>；
 * - 不写全局 config，不修改仓库文件；CI 中禁止。
 *
 * advance（已有 Epoch 2 anchor 时推进，单调变严格）：
 * 1. 当前 anchor 必须存在且 epoch == 2（epoch1 / 未初始化 anchor → OBSOLETE GATE EPOCH，
 *    只允许重新 --adopt-root，不迁移、不兼容、无自动升级权）；
 * 2. 候选必须是当前锚点的后代（向后 / sibling / 无共同历史一律拒绝）；
 *    若本地存在 Epoch 2 root tag，候选与当前锚点都必须包含该 root；
 * 3. 从当前 trusted ref 物化 trusted contract + policy；由 trusted contract 验证候选；
 *    candidate policy 的 gateEpoch 必须 == 2（epoch 升级属于另一轮人工 root admission）；
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
    'epoch-2-first-admission.json');
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
    const fields = ['sourceEpoch', 'targetEpoch', 'trustedSource', 'parent', 'tree'];
    return fields[FIRST_ADMISSION_KEYS.indexOf(key)];
}

function setFirstAdmissionTicket(repoRoot, ticket) {
    if (ticketHasValues(getFirstAdmissionTicket(repoRoot))) {
        throw new Error('an unconsumed first-admission ticket already exists');
    }
    const values = [ticket.sourceEpoch, ticket.targetEpoch, ticket.trustedSource,
        ticket.parent, ticket.tree];
    try {
        for (let i = 0; i < FIRST_ADMISSION_KEYS.length; i += 1) {
            git(['config', '--local', FIRST_ADMISSION_KEYS[i], String(values[i])], repoRoot);
        }
        const actual = getFirstAdmissionTicket(repoRoot);
        if (JSON.stringify(actual) !== JSON.stringify({
            sourceEpoch: String(ticket.sourceEpoch),
            targetEpoch: String(ticket.targetEpoch),
            trustedSource: ticket.trustedSource,
            parent: ticket.parent,
            tree: ticket.tree,
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

/** 物化 ref 的 gate bundle 并读取 policy（无 policy → fail closed，Epoch 2 不迁移旧 anchor）。 */
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
        throw new Error('trusted anchor lacks the Epoch 2 contract/policy;'
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

function loadFirstAdmissionSpec(bundleRoot) {
    const file = path.join(bundleRoot, ...FIRST_ADMISSION_SPEC_REL.split('/'));
    if (!fs.existsSync(file)) {
        throw new Error('trusted verifier has no Epoch 2 first-admission bridge specification');
    }
    const spec = JSON.parse(fs.readFileSync(file, 'utf8'));
    if (spec.schemaVersion !== 1 || spec.sourceEpoch !== 2 || spec.targetEpoch !== 3
        || spec.protectedBranchRef !== 'refs/remotes/origin/master'
        || !spec.requiredContextReplacements
        || Object.keys(spec.requiredContextReplacements).length !== 6
        || !Array.isArray(spec.allowedChangedPaths) || spec.allowedChangedPaths.length === 0) {
        throw new Error('invalid Epoch 2 first-admission bridge specification');
    }
    return spec;
}

function assertExecutingTrustedBundle(repoRoot, trustedSource) {
    const bundleRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
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

function assertProtectedFirstAdmissionSource(repoRoot, trustedSource, spec) {
    const configuredSource = trustedGate.getTrustedRef(repoRoot);
    const configuredEpoch = trustedGate.getTrustedEpoch(repoRoot);
    if (configuredSource !== trustedSource || configuredEpoch !== String(spec.sourceEpoch)) {
        throw new Error('first-admission source must exactly match local trustedGateRef + sourceEpoch');
    }
    if (!trustedGate.resolveCommit(repoRoot, trustedSource)
        || !trustedGate.resolveCommit(repoRoot, spec.protectedBranchRef)
        || !trustedGate.isAncestor(repoRoot, trustedSource, spec.protectedBranchRef)) {
        throw new Error('trusted first-admission source is not in protected origin/master history');
    }
    const previousRoot = trustedGate.resolveRootTag(repoRoot, spec.sourceEpoch);
    if (!previousRoot || !trustedGate.isAncestor(repoRoot, previousRoot, trustedSource)) {
        throw new Error('protected previous-epoch root is missing from the trusted source chain');
    }
    const head = trustedGate.resolveCommit(repoRoot, 'HEAD');
    if (head !== trustedSource) {
        throw new Error('Epoch 3 root parent must exactly equal the trusted Epoch 2 source');
    }
}

function assertExactContextCorrection(repoRoot, candidateSha, trustedSource, spec) {
    const oldContexts = Object.keys(spec.requiredContextReplacements);
    const newContexts = Object.values(spec.requiredContextReplacements);
    const sourceRules = loadJsonAtRef(repoRoot, trustedSource,
        'scripts/ci/github-ruleset-invariants.json');
    const candidateRules = loadJsonAtRef(repoRoot, candidateSha,
        'scripts/ci/github-ruleset-invariants.json');
    if (JSON.stringify(sourceRules.master.requiredChecks) !== JSON.stringify(oldContexts)
        || JSON.stringify(candidateRules.master.requiredChecks) !== JSON.stringify(newContexts)) {
        throw new Error('first admission permits only the exact known required-context identity correction');
    }
    const sourceRootName = trustedGate.rootTagNameForEpoch(spec.sourceEpoch);
    const targetRootName = trustedGate.rootTagNameForEpoch(spec.targetEpoch);
    if (!candidateRules[sourceRootName]
        || JSON.stringify(candidateRules[sourceRootName]) !== JSON.stringify(sourceRules[sourceRootName])
        || JSON.stringify(candidateRules[targetRootName]) !== JSON.stringify(sourceRules[sourceRootName])) {
        throw new Error('Epoch 3 ruleset invariants must preserve Epoch 2 root protection and add identical Epoch 3 protection');
    }

    const sourcePolicy = loadJsonAtRef(repoRoot, trustedSource, 'scripts/i18n/gate-policy.json');
    const candidatePolicy = loadJsonAtRef(repoRoot, candidateSha, 'scripts/i18n/gate-policy.json');
    if (sourcePolicy.gateEpoch !== spec.sourceEpoch || candidatePolicy.gateEpoch !== spec.targetEpoch) {
        throw new Error('first admission requires the exact sourceEpoch=2 and targetEpoch=3 transition');
    }
    const definitions = candidatePolicy.requiredExternalCheckDefinitions || [];
    if (definitions.length !== 1 || definitions[0].requiredContext !== 'check-shared-snippets') {
        throw new Error('Epoch 3 requiredExternalCheckDefinitions.requiredContext must be check-shared-snippets');
    }
    for (const list of [candidatePolicy.requiredPaths,
        candidatePolicy.minimumTrustedVerifier && candidatePolicy.minimumTrustedVerifier.requiredFiles]) {
        if (!Array.isArray(list) || list.includes(FIRST_ADMISSION_SPEC_REL)) {
            throw new Error('Epoch 3 policy must remove the one-time Epoch 2 bridge specification');
        }
    }
    const bridgeAtCandidate = run(['git', 'cat-file', '-e', candidateSha + ':' + FIRST_ADMISSION_SPEC_REL],
        { cwd: repoRoot });
    if (bridgeAtCandidate.status === 0) {
        throw new Error('Epoch 3 root must not retain the Epoch 2 first-admission bridge specification');
    }
    const grepArgs = ['git', 'grep', '-n', '-F'];
    for (const oldContext of oldContexts) {
        grepArgs.push('-e', oldContext);
    }
    grepArgs.push(candidateSha, '--', '.');
    const oldContextScan = run(grepArgs, { cwd: repoRoot });
    if (oldContextScan.status === 0) {
        throw new Error('Epoch 3 root still contains an old or dual required context:\n'
            + (oldContextScan.stdout || '').slice(0, 4000));
    }
    if (oldContextScan.status !== 1) {
        throw new Error('cannot scan the Epoch 3 candidate for forbidden old contexts');
    }

    const allowed = new Set(spec.allowedChangedPaths);
    const changes = git(['diff', '--name-status', trustedSource, candidateSha], repoRoot)
        .split('\n').filter(Boolean);
    for (const line of changes) {
        const fields = line.split('\t');
        const status = fields[0];
        const rel = fields[fields.length - 1];
        if (!allowed.has(rel) || (status !== 'M' && !(status === 'D' && rel === FIRST_ADMISSION_SPEC_REL))) {
            throw new Error('first admission refuses out-of-scope or non-mechanical change: ' + line);
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
        const paths = git(['ls-tree', '-r', '--name-only', candidateSha, '--',
            ...trustedGate.GATE_PATHS], repoRoot).split('\n').filter(Boolean);
        const targetRoot = trustedGate.rootTagNameForEpoch(spec.targetEpoch);
        const sourceRoot = trustedGate.rootTagNameForEpoch(spec.sourceEpoch);
        for (const rel of paths) {
            if (rel === 'scripts/i18n/gate-policy.json'
                || rel === 'scripts/ci/github-ruleset-invariants.json') {
                continue;
            }
            const original = gitBuffer(['show', candidateSha + ':' + rel], repoRoot);
            const text = original.toString('utf8');
            const normalized = text
                .replaceAll(targetRoot, sourceRoot)
                .replaceAll('CURRENT_GATE_EPOCH = ' + spec.targetEpoch + ';',
                    'CURRENT_GATE_EPOCH = ' + spec.sourceEpoch + ';')
                .replaceAll('"gateEpoch": ' + spec.targetEpoch,
                    '"gateEpoch": ' + spec.sourceEpoch);
            if (normalized !== text) {
                updateIndexBlob(repoRoot, indexFile, candidateSha, rel, Buffer.from(normalized, 'utf8'));
            }
        }

        const policy = loadJsonAtRef(repoRoot, candidateSha, 'scripts/i18n/gate-policy.json');
        policy.gateEpoch = spec.sourceEpoch;
        const reverse = new Map(Object.entries(spec.requiredContextReplacements)
            .map(([oldValue, newValue]) => [newValue, oldValue]));
        for (const definition of policy.requiredExternalCheckDefinitions || []) {
            definition.requiredContext = reverse.get(definition.requiredContext) || definition.requiredContext;
        }
        for (const [list, sourceList] of [
            [policy.requiredPaths, loadJsonAtRef(repoRoot, trustedSource,
                'scripts/i18n/gate-policy.json').requiredPaths],
            [policy.minimumTrustedVerifier.requiredFiles, loadJsonAtRef(repoRoot, trustedSource,
                'scripts/i18n/gate-policy.json').minimumTrustedVerifier.requiredFiles],
        ]) {
            if (sourceList.includes(FIRST_ADMISSION_SPEC_REL) && !list.includes(FIRST_ADMISSION_SPEC_REL)) {
                list.push(FIRST_ADMISSION_SPEC_REL);
            }
        }
        updateIndexBlob(repoRoot, indexFile, candidateSha, 'scripts/i18n/gate-policy.json',
            Buffer.from(JSON.stringify(policy, null, 2) + '\n', 'utf8'));

        const rules = loadJsonAtRef(repoRoot, candidateSha,
            'scripts/ci/github-ruleset-invariants.json');
        rules.master.requiredChecks = Object.keys(spec.requiredContextReplacements);
        delete rules[targetRoot];
        updateIndexBlob(repoRoot, indexFile, candidateSha,
            'scripts/ci/github-ruleset-invariants.json',
            Buffer.from(JSON.stringify(rules, null, 2) + '\n', 'utf8'));

        updateIndexBlob(repoRoot, indexFile, trustedSource, FIRST_ADMISSION_SPEC_REL,
            gitBuffer(['show', trustedSource + ':' + FIRST_ADMISSION_SPEC_REL], repoRoot));
        const tree = git(['write-tree'], repoRoot, { env });
        return git(['commit-tree', tree, '-p', trustedSource], repoRoot,
            { input: 'Normalized Epoch 2 first-admission candidate\n' });
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
        assertExactContextCorrection(repoRoot, candidate.sha, trustedSource, trustedExecution.spec);
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
        });
    } finally {
        rmrfRetry(candidateDir);
        rmrfRetry(trustedDir);
    }
    console.log('trust-gate: Gate Epoch ' + trustedExecution.spec.targetEpoch
        + ' root prepared for one exact commit by trusted source ' + trustedSource + ': parent '
        + candidate.parent + ', tree ' + candidate.tree);
}

function runPrepareRoot(repoRoot, epochArg, trustedSourceArg) {
    if (trustedGate.isCI()) {
        fail('root preparation is forbidden in CI (CI=true); it is an explicit local trust decision');
    }
    if (trustedSourceArg) {
        runPrepareRootFromTrustedBundle(repoRoot, epochArg, trustedSourceArg);
        return;
    }
    const current = trustedGate.getTrustedRef(repoRoot);
    if (!current || !trustedGate.resolveCommit(repoRoot, current)) {
        fail('root preparation requires a resolvable local trustedGateRef');
    }
    const gateDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv-first-admission-source-'));
    try {
        trustedGate.materializeTrustedGate(repoRoot, current, gateDir);
        const bridgeSpec = path.join(gateDir, ...FIRST_ADMISSION_SPEC_REL.split('/'));
        if (!fs.existsSync(bridgeSpec)) {
            fail('trusted verifier has no one-time Epoch 2 first-admission bridge');
        }
        const cli = path.join(gateDir, 'scripts', 'i18n', 'trust-gate.mjs');
        const result = run(['node', cli, '--prepare-root', '--epoch', String(epochArg),
            '--trusted-source', current], { cwd: repoRoot });
        process.stdout.write(result.stdout || '');
        process.stderr.write(result.stderr || '');
        if (result.status !== 0) {
            fail('trusted first-admission bridge rejected the staged root candidate');
        }
    } finally {
        rmrfRetry(gateDir);
    }
}

function setAnchorAndConsumeFirstAdmission(repoRoot, sha, epoch, previousRef, previousEpoch, ticket) {
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
    if (current) {
        if (previousEpoch === trustedGate.CURRENT_GATE_EPOCH) {
            fail('a local trust anchor already exists at ' + current
                + '; use --advance within the current epoch');
        }
        const prepared = getFirstAdmissionTicket(repoRoot);
        const parents = git(['rev-list', '--parents', '-n', '1', sha], repoRoot).split(/\s+/);
        const tree = git(['rev-parse', sha + '^{tree}'], repoRoot);
        if (previousEpoch !== trustedGate.CURRENT_GATE_EPOCH - 1
            || prepared.sourceEpoch !== String(previousEpoch)
            || prepared.targetEpoch !== String(trustedGate.CURRENT_GATE_EPOCH)
            || prepared.trustedSource !== current
            || parents.length !== 2 || prepared.parent !== parents[1]
            || prepared.parent !== current || prepared.tree !== tree
            || !trustedGate.isAncestor(repoRoot, current, 'refs/remotes/origin/master')) {
            fail('existing-anchor root adoption requires an unconsumed trusted first-admission ticket'
                + ' with the exact source epoch, target epoch, trusted source, parent and tree');
        }
        const previousRoot = trustedGate.resolveRootTag(repoRoot, previousEpoch);
        if (!previousRoot || !trustedGate.isAncestor(repoRoot, previousRoot, current)) {
            fail('first-admission ticket source no longer belongs to the protected previous root chain');
        }
        firstAdmissionTicket = prepared;
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
                current, previousEpoch, firstAdmissionTicket);
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
    // 若本地已安装 Epoch 2 root tag：当前锚点与候选都必须包含该 root
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
        // 1. 从当前 trusted ref 物化 Epoch 2 contract + policy
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
        if (arg === '--prepare-root' || arg === '--adopt-root' || arg === '--advance' || arg === '--show') {
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
    if (args.command === 'advance') {
        if (!args.ref) {
            throw new Error('--ref <commit> is required for advance');
        }
    }
    if (!args.command) {
        throw new Error('usage: trust-gate.mjs --show | --prepare-root --epoch <next> |'
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

main();
