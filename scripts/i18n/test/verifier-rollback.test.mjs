'use strict';
/**
 * Verifier rollback 禁止测试（Gate Epoch 2 新标准）：
 * trusted base 必须同时满足 ancestry（root <= base < candidate）与当前 verifier capability
 * （contractVersion >= 4 / schemaVersion >= 3 / verifier 本体文件齐全）。
 * 旧 verifier（contract v3 root 等）即使满足 root <= base < candidate 也必须 FAIL CLOSED。
 *
 * DAG 矩阵：
 * - Case A：R(v3) ─ V4 ─ C，base = V4            → PASS（当前标准 predecessor 正常链）
 * - Case B：R(v3) ─ V4 ─ C，base = R（显式 input）→ FAIL（contractVersion 3 < 4）
 * - Case C：R ─ V4 ─ B / ─ C（sibling）base = B   → FAIL（ancestry）
 * - Case D：R ─ V4 ─ C ─ B，base = B              → FAIL（base 是 candidate 后代）
 * - Case E：复制合法 V4 再删除 gate-surface.json   → FAIL（verifier 本体缺文件）
 * - Case F：contractVersion 降到 3                → FAIL（capability）
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const SCRIPTS_DIR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const REPO_ROOT = path.resolve(SCRIPTS_DIR, '..', '..');
const RESOLVER = path.join(REPO_ROOT, 'scripts', 'ci', 'resolve-trusted-base.mjs');

const VERIFIER_FILES = [
    'scripts/ci/gate-surface.json',
    'scripts/ci/gate-invariants.json',
    'scripts/ci/gate-parity.mjs',
    'scripts/ci/resolve-trusted-base.mjs',
    'scripts/ci/materialize-trusted-gate.sh',
    'scripts/ci/doctor-github-ruleset.mjs',
];

function git(args, cwd, opts = {}) {
    const result = spawnSync('git', args, { cwd, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024, ...opts });
    if (result.status !== 0 && !opts.allowFailure) {
        throw new Error('git ' + args.join(' ') + ' failed: ' + (result.stderr || result.stdout));
    }
    return result;
}

/**
 * 构造 DAG 夹具：
 * - R0：Epoch 2 历史 root（v3 时代：contractVersion 3 / schemaVersion 2，无 gate-surface.json，
 *   与真实 cb587e01 同构）——root tag 指向它（root 只是历史信任纪元起点，不要求当前能力）；
 * - V4：当前标准 verifier（contract 4 / schema 3 / 全部 verifier 本体文件）；
 * - C：candidate（V4 后代）。
 * @param {Object} opts { v4Contract, withSurface } 控制 V4 的 capability 变异
 */
function makeDagRepo({ v4Contract = 4, withSurface = true } = {}) {
    const dir = path.join(os.tmpdir(), 'pixiv rollback repo ' + Date.now() + '-' + Math.random().toString(36).slice(2));
    fs.mkdirSync(dir, { recursive: true });
    git(['init', '-q'], dir);
    git(['config', 'user.email', 't@example.com'], dir);
    git(['config', 'user.name', 'test'], dir);
    git(['config', 'core.autocrlf', 'false'], dir);
    fs.writeFileSync(path.join(dir, '.gitignore'), 'build/\n', 'utf8');

    // R0：v3 时代 root（contract 3 / schema 2，无 gate-surface.json，与 cb587e01 同构）
    fs.mkdirSync(path.join(dir, 'scripts', 'i18n'), { recursive: true });
    fs.mkdirSync(path.join(dir, 'scripts', 'ci'), { recursive: true });
    fs.writeFileSync(path.join(dir, 'scripts', 'i18n', 'gate-policy.json'),
        JSON.stringify({
            schemaVersion: 2, gateEpoch: 2, contractVersion: 3,
            i18nEnforcementStartCommit: '05f4ebed7ce00f0b923fe48ec2e0971610511547',
            requiredPaths: [], protectedBranches: ['refs/heads/master'],
            requiredWorkflowJobs: ['java-tests'], requiredWorkflowFiles: [],
            requiredPackageScripts: [], requiredExternalChecks: [],
        }, null, 2) + '\n', 'utf8');
    git(['add', '-A'], dir);
    git(['commit', '-q', '-m', 'R0 (epoch 2 historical root, contract v3)'], dir);
    const r0 = git(['rev-parse', 'HEAD'], dir).stdout.trim();
    git(['tag', 'i18n-gate-epoch-2-root', r0], dir);

    // V4（或变异）：当前标准 verifier
    const policy = {
        schemaVersion: 3, gateEpoch: 2, contractVersion: v4Contract,
        i18nEnforcementStartCommit: r0,
        requiredPaths: [], protectedBranches: ['refs/heads/master'],
        requiredWorkflowJobs: ['java-tests'], requiredWorkflowFiles: [],
        requiredPackageScripts: [], requiredExternalChecks: [],
    };
    fs.writeFileSync(path.join(dir, 'scripts', 'i18n', 'gate-policy.json'),
        JSON.stringify(policy, null, 2) + '\n', 'utf8');
    for (const rel of VERIFIER_FILES) {
        if (!withSurface && rel === 'scripts/ci/gate-surface.json') {
            continue;
        }
        fs.mkdirSync(path.dirname(path.join(dir, ...rel.split('/'))), { recursive: true });
        fs.copyFileSync(path.join(REPO_ROOT, ...rel.split('/')), path.join(dir, ...rel.split('/')));
    }
    git(['add', '-A'], dir);
    git(['commit', '-q', '-m', 'V4 verifier'], dir);
    const v4 = git(['rev-parse', 'HEAD'], dir).stdout.trim();

    // C：candidate
    fs.writeFileSync(path.join(dir, 'feature.txt'), 'feature\n', 'utf8');
    git(['add', '-A'], dir);
    git(['commit', '-q', '-m', 'C candidate'], dir);
    const c = git(['rev-parse', 'HEAD'], dir).stdout.trim();

    return { dir, r0, v4, c };
}

function runResolver(root, args) {
    return spawnSync('node', [RESOLVER, '--repo-root', root, ...args],
        { cwd: root, encoding: 'utf8' });
}

function cleanRepo(root) {
    if (!root) {
        return;
    }
    for (let attempt = 0; attempt < 6; attempt += 1) {
        try {
            fs.rmSync(root, { recursive: true, force: true });
            return;
        } catch (e) {
            if (attempt === 5) {
                throw e;
            }
            execFileSync('bash', ['-c', 'sleep 0.5'], { stdio: 'ignore' });
        }
    }
}

test('verifier rollback Case A：合法 current verifier（R ─ V4 ─ C，base = V4）→ PASS', () => {
    const { dir, v4, c } = makeDagRepo();
    try {
        const run = runResolver(dir, ['--event-name', 'pull_request', '--candidate', c,
            '--pr-base', v4, '--mode']);
        assert.equal(run.status, 0, run.stdout + run.stderr);
        const j = JSON.parse(run.stdout);
        assert.equal(j.mode, 'NORMAL');
        assert.equal(j.base, v4, '当前标准 predecessor 正常链必须可用');
    } finally {
        cleanRepo(dir);
    }
});

test('verifier rollback Case B：旧 verifier 是祖先（base = R(v3)，root <= base < candidate 成立）→ FAIL', () => {
    const { dir, r0, c } = makeDagRepo();
    try {
        // 显式 trusted_base_sha = R0：即使 root <= R0 < C 成立，capability 太旧也必须 FAIL
        const run = runResolver(dir, ['--event-name', 'pull_request', '--candidate', c,
            '--pr-base', r0, '--mode']);
        assert.notEqual(run.status, 0, '旧 verifier 作为 trusted base 必须 FAIL CLOSED');
        assert.match(run.stderr, /contractVersion 3 < current minimum 4|verifier rollback is refused/,
            '必须显式回归：ancestry 成立但 verifier capability 太旧 → FAIL');
        // input 显式传入同一旧 base 也必须 FAIL
        const viaInput = runResolver(dir, ['--event-name', 'workflow_dispatch', '--candidate', c,
            '--input-base', r0, '--default-branch', 'master', '--mode']);
        assert.notEqual(viaInput.status, 0, '显式 trusted_base_sha = 旧 verifier 必须 FAIL');
        assert.match(viaInput.stderr, /contractVersion 3 < current minimum 4|verifier rollback is refused/);
    } finally {
        cleanRepo(dir);
    }
});

test('verifier rollback Case C：sibling base（R ─ V4 ─ B / ─ C）→ FAIL（ancestry）', () => {
    const { dir, v4, c } = makeDagRepo();
    try {
        git(['checkout', '-q', '-b', 'sibling-b', v4], dir);
        fs.writeFileSync(path.join(dir, 'b.txt'), 'b\n', 'utf8');
        git(['add', '-A'], dir);
        git(['commit', '-q', '-m', 'B sibling'], dir);
        const b = git(['rev-parse', 'HEAD'], dir).stdout.trim();
        git(['checkout', '-q', 'master'], dir);
        const run = runResolver(dir, ['--event-name', 'pull_request', '--candidate', c,
            '--pr-base', b, '--mode']);
        assert.notEqual(run.status, 0, 'sibling base 必须 FAIL');
        assert.match(run.stderr, /not an ancestor of the candidate/);
    } finally {
        cleanRepo(dir);
    }
});

test('verifier rollback Case D：base 是 candidate 后代（R ─ V4 ─ C ─ B）→ FAIL（ancestry）', () => {
    const { dir, c } = makeDagRepo();
    try {
        fs.writeFileSync(path.join(dir, 'b.txt'), 'b\n', 'utf8');
        git(['add', '-A'], dir);
        git(['commit', '-q', '-m', 'B descendant of candidate'], dir);
        const b = git(['rev-parse', 'HEAD'], dir).stdout.trim();
        const run = runResolver(dir, ['--event-name', 'pull_request', '--candidate', c,
            '--pr-base', b, '--mode']);
        assert.notEqual(run.status, 0, 'base 是 candidate 后代必须 FAIL');
        assert.match(run.stderr, /not an ancestor of the candidate/);
    } finally {
        cleanRepo(dir);
    }
});

test('verifier rollback Case E：无 gate-surface.json 的 verifier → FAIL（verifier 本体缺文件）', () => {
    const { dir, v4, c } = makeDagRepo({ withSurface: false });
    try {
        const run = runResolver(dir, ['--event-name', 'pull_request', '--candidate', c,
            '--pr-base', v4, '--mode']);
        assert.notEqual(run.status, 0, '缺 gate-surface.json 的 verifier 必须 FAIL');
        assert.match(run.stderr, /missing scripts\/ci\/gate-surface.json|verifier baseline/);
    } finally {
        cleanRepo(dir);
    }
});

test('verifier rollback Case F：contractVersion 降到 3 → FAIL（capability）', () => {
    const { dir, v4, c } = makeDagRepo({ v4Contract: 3 });
    try {
        const run = runResolver(dir, ['--event-name', 'pull_request', '--candidate', c,
            '--pr-base', v4, '--mode']);
        assert.notEqual(run.status, 0, 'contractVersion 3 的 verifier 必须 FAIL');
        assert.match(run.stderr, /contractVersion 3 < current minimum 4|verifier rollback is refused/);
    } finally {
        cleanRepo(dir);
    }
});
