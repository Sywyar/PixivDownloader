#!/usr/bin/env node
'use strict';
/**
 * 本地可信 Gate Anchor 管理命令。
 *
 * 用法：
 *   npm run i18n:trust-gate -- --show
 *   npm run i18n:trust-gate -- --bootstrap --ref HEAD [--allow-dirty]
 *   npm run i18n:trust-gate -- --advance --ref HEAD [--allow-dirty]
 *   npm run i18n:trust-gate -- --version
 *
 * bootstrap（首次初始化，明确的 Trust On First Use）：
 * - 只能对完整 commit 执行，不接受工作树路径；ref 必须精确解析为 commit；
 * - 工作区和 index 必须干净（--allow-dirty 显式豁免，仅用于首次迁移的既定流程）；
 * - 执行完整 i18n tests、ref snapshot check、signature guard、检查 required gate files；
 * - 输出醒目提示后写入本地 config：git config --local pixiv.i18n.trustedGateRef <sha>；
 * - 不写全局 config，不修改仓库文件；CI 中禁止 bootstrap trust。
 *
 * advance（已有 trusted ref 时推进）：
 * 1. 从当前 trusted ref 物化旧 contract；由旧 contract 验证 candidate ref；
 * 2. 验证 candidate checker 行为、candidate contract 不可简单弱化、required files；
 * 3. 完整 tests 通过；candidate ref snapshot check 通过；signature guard 通过；
 * 4. candidate 必须是完整 commit；所有检查通过后才更新 local config。
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

const TRUST_CLI_VERSION = '1';

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

/** 校验工作区状态；脏时列出文件。allowDirty 只豁免工作树（index 暂存改动永远拒绝）。 */
function assertCleanState(repoRoot, allowDirty) {
    if (!trustedGate.isIndexClean(repoRoot)) {
        fail('the git index has staged changes; commit or reset them before running trust commands');
    }
    if (!trustedGate.isWorktreeClean(repoRoot)) {
        if (!allowDirty) {
            const dirty = git(['status', '--porcelain'], repoRoot).split('\n').filter(Boolean);
            fail('the worktree is not clean (' + dirty.length + ' dirty file(s); first ones: '
                + dirty.slice(0, 5).join(' | ')
                + '); commit the changes first, or pass --allow-dirty for the documented one-time migration');
        }
        console.warn('trust-gate: worktree is dirty; --allow-dirty accepted. '
            + 'The anchor is the committed ref; all checks run against ref snapshots.');
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

/** 从给定 ref 物化 gate bundle 并执行 ref snapshot check + signature guard + required files。 */
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

/** required gate files（candidate 视角）：policy.requiredPaths，缺 policy 时用 legacy 集合。 */
function requiredFilesOf(policy) {
    return policy ? policy.requiredPaths : trustedGate.LEGACY_REQUIRED_PATHS;
}

function runBootstrap(repoRoot, refArg, allowDirty) {
    if (trustedGate.isCI()) {
        fail('bootstrap trust is forbidden in CI (CI=true); the trust anchor must be established by a human locally');
    }
    const current = trustedGate.getTrustedRef(repoRoot);
    if (current) {
        fail('trust is already bootstrapped at ' + current + '; use --advance to move it');
    }
    const sha = trustedGate.resolveCommit(repoRoot, refArg);
    if (!sha) {
        fail('ref "' + refArg + '" must resolve to a full commit; worktree paths are not accepted');
    }
    assertCleanState(repoRoot, allowDirty);

    const gateDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv-trust-gate-'));
    try {
        // 物化 ref 的 gate bundle（TOFU 对象 = 该 commit 自身）
        trustedGate.materializeTrustedGate(repoRoot, sha, gateDir);
        const policy = trustedGate.loadPolicyFromDir(gateDir);
        if (policy) {
            // enforcement start 必须存在并属于当前仓库
            const start = trustedGate.resolveCommit(repoRoot, policy.i18nEnforcementStartCommit);
            if (!start) {
                fail('gate-policy.json: i18nEnforcementStartCommit ' + policy.i18nEnforcementStartCommit
                    + ' does not resolve to a commit in this repository');
            }
        } else {
            console.warn('trust-gate: trusted ref predates the gate policy/contract (legacy anchor). '
                + 'This is the documented one-time chain-start migration; required legacy files are enforced.');
        }
        const required = requiredFilesOf(policy);
        const missing = required.filter((p) =>
            !fs.existsSync(path.join(gateDir, ...p.split('/'))));
        if (missing.length > 0) {
            fail('required gate files missing at ' + sha + ': ' + missing.join(', '));
        }

        console.log('trust-gate: running the full i18n test suite...');
        runFullSuite(repoRoot);
        console.log('trust-gate: running ref snapshot check and signature guard for ' + sha + '...');
        validateRefWithGate(repoRoot, sha, gateDir);
    } finally {
        rmrfRetry(gateDir);
    }

    console.error('');
    console.error('This is the initial local trust decision.');
    console.error('The repository state at ' + sha + ' becomes the local gate trust anchor.');
    console.error('Only an explicit trust command can advance it.');
    console.error('');
    trustedGate.setTrustedRef(repoRoot, sha);
    console.log('trust-gate: local trust anchor set to ' + sha
        + ' (git config --local pixiv.i18n.trustedGateRef)');
}

function runAdvance(repoRoot, refArg, allowDirty) {
    if (trustedGate.isCI()) {
        fail('advance trust is forbidden in CI (CI=true); CI must never modify the user local git config');
    }
    const current = trustedGate.getTrustedRef(repoRoot);
    if (!current) {
        fail('no trusted gate anchor; run bootstrap first: ' + trustedGate.BOOTSTRAP_HINT);
    }
    const sha = trustedGate.resolveCommit(repoRoot, refArg);
    if (!sha) {
        fail('ref "' + refArg + '" must resolve to a full commit; worktree paths are not accepted');
    }
    assertCleanState(repoRoot, allowDirty);
    if (sha === current) {
        console.log('trust-gate: candidate ref equals the current anchor; nothing to do.');
        return;
    }

    const trustedDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv-advance-trusted-'));
    const candidateDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv-advance-candidate-'));
    try {
        // 1. 从当前 trusted ref 物化旧 contract + policy
        trustedGate.materializeTrustedGate(repoRoot, current, trustedDir);
        const trustedPolicy = trustedGate.loadPolicyFromDir(trustedDir);
        const hasContract = fs.existsSync(path.join(trustedDir, 'scripts', 'i18n', 'gate-contract.mjs'));

        // 2. 旧 contract 验证 candidate ref（policy / required files / checker / hooks / 自保护）
        if (hasContract && trustedPolicy) {
            console.log('trust-gate: running the trusted gate contract against ' + sha + '...');
            const contractRun = run(['node',
                path.join(trustedDir, 'scripts', 'i18n', 'gate-contract.mjs'),
                '--repo-root', repoRoot, '--candidate-ref', sha]);
            if (contractRun.status !== 0) {
                fail('the trusted gate contract rejected ' + sha + '\n---\n'
                    + (contractRun.output || '').slice(-8000));
            }
        } else {
            console.warn('trust-gate: current anchor predates the gate contract (legacy anchor). '
                + 'Using the documented one-time legacy advance: candidate policy + required files '
                + 'are validated directly; the chain becomes contract-protected after this advance.');
            trustedGate.materializeTrustedGate(repoRoot, sha, candidateDir);
            const candidatePolicy = trustedGate.loadPolicyFromDir(candidateDir);
            if (candidatePolicy) {
                const start = trustedGate.resolveCommit(repoRoot, candidatePolicy.i18nEnforcementStartCommit);
                if (!start) {
                    fail('candidate gate-policy.json: i18nEnforcementStartCommit '
                        + candidatePolicy.i18nEnforcementStartCommit
                        + ' does not resolve to a commit in this repository');
                }
            }
            const required = requiredFilesOf(candidatePolicy);
            const missing = required.filter((p) =>
                !fs.existsSync(path.join(candidateDir, ...p.split('/'))));
            if (missing.length > 0) {
                fail('candidate required gate files missing: ' + missing.join(', '));
            }
        }

        // 6. 完整 tests 通过（工作树；advance 前必须干净）
        console.log('trust-gate: running the full i18n test suite...');
        runFullSuite(repoRoot);

        // 7. candidate ref snapshot check（trusted checker）
        // 8. signature guard（trusted guard）
        console.log('trust-gate: running ref snapshot check and signature guard for ' + sha + '...');
        validateRefWithGate(repoRoot, sha, trustedDir);

        // 10. 全部通过后更新 local config
        trustedGate.setTrustedRef(repoRoot, sha);
        console.log('trust-gate: local trust anchor advanced from ' + current + ' to ' + sha);
    } finally {
        rmrfRetry(trustedDir);
        rmrfRetry(candidateDir);
    }
}

function runShow(repoRoot) {
    const current = trustedGate.getTrustedRef(repoRoot);
    if (!current) {
        console.log('trustedGateRef: <not set>');
        console.log('run: ' + trustedGate.BOOTSTRAP_HINT);
        return;
    }
    let contractVersion = 'n/a';
    let policy = null;
    const gateDir = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv-trust-show-'));
    try {
        try {
            trustedGate.materializeTrustedGate(repoRoot, current, gateDir);
            policy = trustedGate.loadPolicyFromDir(gateDir);
        } catch (e) {
            // anchor 无法物化时只提示
        }
        if (policy) {
            contractVersion = String(policy.contractVersion);
        }
    } finally {
        rmrfRetry(gateDir);
    }
    console.log('trustedGateRef: ' + current);
    console.log('contractVersion: ' + contractVersion);
    if (!policy) {
        console.log('note: the anchor predates the gate policy/contract (legacy anchor); advance to a current commit.');
    }
}

function parseArgs(argv) {
    const args = { command: null, ref: null, allowDirty: false, version: false };
    for (let i = 0; i < argv.length; i += 1) {
        const arg = argv[i];
        if (arg === '--bootstrap' || arg === '--advance' || arg === '--show') {
            args.command = arg.slice(2);
        } else if (arg === '--ref') {
            args.ref = argv[++i];
        } else if (arg === '--allow-dirty') {
            args.allowDirty = true;
        } else if (arg === '--version') {
            args.version = true;
        } else {
            throw new Error('unknown argument: ' + arg);
        }
    }
    if (args.version) {
        args.command = 'version';
    }
    if (args.command === 'bootstrap' || args.command === 'advance') {
        if (!args.ref) {
            throw new Error('--ref <commit> is required for ' + args.command);
        }
    }
    if (!args.command) {
        throw new Error('usage: trust-gate.mjs --show | --bootstrap --ref HEAD | --advance --ref HEAD');
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
    }    if (args.command === 'version') {
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
    if (args.command === 'bootstrap') {
        runBootstrap(repoRoot, args.ref, args.allowDirty);
        return;
    }
    if (args.command === 'advance') {
        runAdvance(repoRoot, args.ref, args.allowDirty);
        return;
    }
    fail('unknown command: ' + args.command);
}

main();
