#!/usr/bin/env node
'use strict';
/**
 * 本地可信 Gate Anchor 管理命令（Gate Epoch 2 单一标准）。
 *
 * 用法：
 *   npm run i18n:trust-gate -- --show
 *   npm run i18n:trust-gate -- --adopt-root --ref HEAD --epoch 2
 *   npm run i18n:trust-gate -- --advance --ref HEAD
 *   npm run i18n:trust-gate -- --version
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

const TRUST_CLI_VERSION = '2';
// 当前新标准 verifier 最低能力由 lib/trusted-gate.mjs 的 CURRENT_MIN_* + REQUIRED_VERIFIER_FILES 定义；
// adopt-root / advance 前必须断言候选与 trusted anchor 满足该 baseline（低于 → OUTDATED GATE VERIFIER）。

function fail(message) {
    console.error('trust-gate ERROR: ' + message);
    process.exit(1);
}

function git(args, repoRoot, opts = {}) {
    return execFileSync('git', args, {
        cwd: repoRoot, encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'], ...opts,
    }).trim();
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
function materializeAndLoadPolicy(repoRoot, sha, gateDir) {
    trustedGate.materializeTrustedGate(repoRoot, sha, gateDir);
    const policy = trustedGate.loadPolicyFromDir(gateDir);
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

function runAdoptRoot(repoRoot, refArg, epochArg) {
    if (trustedGate.isCI()) {
        fail('root adoption is forbidden in CI (CI=true); the Epoch 2 trust root must be'
            + ' established by a human locally');
    }
    if (String(epochArg) !== String(trustedGate.CURRENT_GATE_EPOCH)) {
        fail('--epoch must be exactly ' + trustedGate.CURRENT_GATE_EPOCH
            + ' (Epoch 2 is the only supported gate epoch; got ' + epochArg + ')');
    }
    const current = trustedGate.getTrustedRef(repoRoot);
    if (current) {
        fail('a local trust anchor already exists at ' + current
            + '; root adoption is only for establishing a NEW Epoch 2 root —'
            + ' use --advance to move the existing anchor, or explicitly remove both'
            + ' pixiv.i18n.trustedGateEpoch / pixiv.i18n.trustedGateRef first');
    }
    const sha = trustedGate.resolveCommit(repoRoot, refArg);
    if (!sha) {
        fail('ref "' + refArg + '" must resolve to a full commit; worktree paths are not accepted');
    }
    assertCleanState(repoRoot);

    const gateDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv-adopt-root-'));
    try {
        // ROOT ADMISSION：物化候选自身的 gate bundle（候选 = root candidate）
        console.log('trust-gate: ROOT ADMISSION — ' + sha
            + ' is the Gate Epoch 2 root candidate; running the full root admission suite...');
        const policy = materializeAndLoadPolicy(repoRoot, sha, gateDir);
        // 新标准 root admission 也必须满足当前 verifier baseline（旧 v3 root 候选不再有资格；
        // 旧提交按旧标准存在，新标准只接受具备当前能力的 verifier）
        try {
            trustedGate.assertSupportedTrustedVerifierDir(gateDir);
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
        console.log('trust-gate: running gate parity against the Epoch 2 invariants manifest...');
        runGateParity(repoRoot, sha, gateDir, true);
    } finally {
        rmrfRetry(gateDir);
    }

    console.error('');
    console.error('This is the explicit Gate Epoch 2 root adoption decision.');
    console.error('The repository state at ' + sha + ' becomes the local Epoch 2 trust root.');
    console.error('Only an explicit trust command can advance it.');
    console.error('');
    trustedGate.setTrustedAnchor(repoRoot, sha);
    console.log('trust-gate: Epoch 2 root adopted; local anchor set to '
        + 'git config --local pixiv.i18n.trustedGateEpoch ' + trustedGate.CURRENT_GATE_EPOCH
        + ' + pixiv.i18n.trustedGateRef ' + sha);
}

function runAdvance(repoRoot, refArg) {
    if (trustedGate.isCI()) {
        fail('advance trust is forbidden in CI (CI=true); CI must never modify the user local git config');
    }
    const current = trustedGate.getTrustedRef(repoRoot);
    if (!current) {
        fail('no trusted gate anchor; establish the Epoch 2 root first:'
            + ' npm run i18n:trust-gate -- --adopt-root --ref HEAD --epoch 2');
    }
    if (!trustedGate.isTrustedEpochCurrent(repoRoot)) {
        fail('OBSOLETE GATE EPOCH: the local anchor belongs to epoch '
            + (trustedGate.getTrustedEpoch(repoRoot) || '<missing>') + ' (' + trustedGate.describeTrustedEpoch(repoRoot)
            + '). Epoch 1 anchors are not migrated and have no automatic upgrade rights;'
            + ' run the explicit Epoch 2 root adoption command instead:'
            + ' npm run i18n:trust-gate -- --adopt-root --ref <new-root> --epoch 2');
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
            fail('the current anchor ' + current + ' does not descend from the Gate Epoch 2 trust root '
                + root + '; re-adopt the root first:'
                + ' npm run i18n:trust-gate -- --adopt-root --ref HEAD --epoch 2');
        }
        if (!trustedGate.isAncestor(repoRoot, root, sha)) {
            fail('candidate ' + sha + ' does not descend from the Gate Epoch 2 trust root ' + root
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
        console.log('gateEpochRootTag: ' + (root || 'refs/tags/i18n-gate-epoch-2-root <missing>'));
        console.log('run: npm run i18n:trust-gate -- --adopt-root --ref HEAD --epoch 2');
        return;
    }
    let contractVersion = 'n/a';
    let baseline = 'n/a';
    const gateDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv-trust-show-'));
    try {
        try {
            trustedGate.materializeTrustedGate(repoRoot, current, gateDir);
            const policy = trustedGate.loadPolicyFromDir(gateDir);
            if (policy) {
                contractVersion = String(policy.contractVersion);
            }
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
    console.log('gateEpochRootTag: ' + (root || 'refs/tags/i18n-gate-epoch-2-root <missing (install after admission)>'));
}

function parseArgs(argv) {
    const args = { command: null, ref: null, epoch: null, version: false };
    for (let i = 0; i < argv.length; i += 1) {
        const arg = argv[i];
        if (arg === '--adopt-root' || arg === '--advance' || arg === '--show') {
            args.command = arg.slice(2);
        } else if (arg === '--ref') {
            args.ref = argv[++i];
        } else if (arg === '--epoch') {
            args.epoch = argv[++i];
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
            throw new Error('--epoch 2 is required for adopt-root');
        }
    }
    if (args.command === 'advance') {
        if (!args.ref) {
            throw new Error('--ref <commit> is required for advance');
        }
    }
    if (!args.command) {
        throw new Error('usage: trust-gate.mjs --show | --adopt-root --ref HEAD --epoch 2 | --advance --ref HEAD');
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
