'use strict';
/**
 * trust-gate CLI 测试：--show / --bootstrap / --advance。
 * - bootstrap 是 TOFU：只接受完整 commit、干净状态（--allow-dirty 显式豁免）、
 *   完整 i18n tests、ref snapshot check、signature guard、required files，CI 禁止；
 * - advance 由旧 trusted contract 审核候选：no-op checker / no-op contract /
 *   删除 required file / 弱化 policy 一律拒绝；不自动发生；只写 local config。
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { runAcceptCore } from '../accept.mjs';
import { runGenerate } from '../generate-static.mjs';

const SCRIPTS_DIR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const REPO_ROOT = path.resolve(SCRIPTS_DIR, '..', '..');
const CLI = path.join(SCRIPTS_DIR, 'trust-gate.mjs');

const CATALOG = `{
  "schemaVersion": 1,
  "sourceLocale": "zh-CN",
  "defaultLocale": "en-US",
  "fallbackLocale": "en-US",
  "languageCookieName": "pixiv_lang",
  "languageParameterName": "lang",
  "locales": [
    {"tag": "zh-CN", "nativeName": "简体中文", "resourceSuffix": "", "status": "source", "direction": "ltr", "aliases": ["zh", "zh-Hans"]},
    {"tag": "en-US", "nativeName": "English", "resourceSuffix": "en", "status": "supported", "direction": "ltr", "aliases": ["en"]}
  ]
}`;

const APP_I18N = path.join('pixivdownload-app', 'src', 'main', 'resources', 'i18n');
const GOOD_ZH = 'greeting=你好 {name}\ntitle=作品标题\n';
const GOOD_EN = 'greeting=Hello {name}\ntitle=Artwork title\n';
const EXIT_ZERO_CHECKER = '#!/usr/bin/env node\nprocess.exit(0);\n';

function git(args, cwd, opts = {}) {
    const result = spawnSync('git', args, { cwd, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024, ...opts });
    if (result.status !== 0 && !opts.allowFailure) {
        throw new Error('git ' + args.join(' ') + ' failed: ' + (result.stderr || result.stdout));
    }
    return result;
}

function hasBash() {
    try {
        execFileSync('bash', ['--version'], { stdio: 'ignore' });
        return true;
    } catch (e) {
        return false;
    }
}

/** 与 hooks 测试同构的夹具；withAnchor=false 时不写 trustedGateRef（供 CLI 自行建立）。 */
function makeRepo(withAnchor = false) {
    const dir = path.join(os.tmpdir(), 'pixiv trust repo ' + Date.now() + '-' + Math.random().toString(36).slice(2));
    fs.mkdirSync(dir, { recursive: true });
    git(['init', '-q'], dir);
    git(['config', 'user.email', 't@example.com'], dir);
    git(['config', 'user.name', 'test'], dir);
    git(['config', 'core.autocrlf', 'false'], dir);
    // 与真实仓库一致：build/ 忽略（checker/contract 的报告目录）
    fs.writeFileSync(path.join(dir, '.gitignore'), 'build/\nnode_modules/\n', 'utf8');
    fs.cpSync(path.join(REPO_ROOT, 'scripts', 'i18n'), path.join(dir, 'scripts', 'i18n'), { recursive: true });
    fs.rmSync(path.join(dir, 'scripts', 'i18n', 'test'), { recursive: true, force: true });
    fs.rmSync(path.join(dir, 'scripts', 'i18n', 'gate-policy.json'), { force: true });
    fs.cpSync(path.join(REPO_ROOT, 'scripts', 'hooks'), path.join(dir, 'scripts', 'hooks'), { recursive: true });
    const i18nDir = path.join(dir, APP_I18N);
    fs.mkdirSync(path.join(i18nDir, 'web'), { recursive: true });
    fs.writeFileSync(path.join(i18nDir, 'locales.json'), CATALOG, 'utf8');
    fs.writeFileSync(path.join(i18nDir, 'web', 'common.properties'), GOOD_ZH, 'utf8');
    fs.writeFileSync(path.join(i18nDir, 'web', 'common_en.properties'), GOOD_EN, 'utf8');
    const bootstrap = runAcceptCore(dir, { bootstrap: true });
    if (!bootstrap.ok) {
        throw new Error('fixture bootstrap failed: ' + bootstrap.refused.join('\n'));
    }
    runGenerate(dir);
    git(['add', '--chmod=+x', 'scripts/hooks/pre-commit', 'scripts/hooks/pre-push', 'scripts/hooks/pre-push-guard.sh'], dir);
    git(['add', '-A'], dir);
    git(['commit', '-q', '-m', 'init'], dir); // C1
    const start = git(['rev-parse', 'HEAD'], dir).stdout.trim();
    const policy = JSON.parse(fs.readFileSync(path.join(REPO_ROOT, 'scripts', 'i18n', 'gate-policy.json'), 'utf8'));
    policy.i18nEnforcementStartCommit = start;
    fs.writeFileSync(path.join(dir, 'scripts', 'i18n', 'gate-policy.json'),
        JSON.stringify(policy, null, 2) + '\n', 'utf8');
    git(['add', '-A'], dir);
    git(['commit', '-q', '-m', 'add gate policy'], dir); // C2
    git(['config', '--local', 'core.hooksPath', 'scripts/hooks'], dir);
    if (withAnchor) {
        const anchor = git(['rev-parse', 'HEAD'], dir).stdout.trim();
        git(['config', '--local', 'pixiv.i18n.trustedGateRef', anchor], dir);
    }
    return dir;
}

function commitBypass(root, message) {
    git(['add', '-A'], root);
    git(['-c', 'core.hooksPath=/dev/null', 'commit', '-q', '-m', message], root);
}

function runCli(root, args, env = {}) {
    // clearCI：CI 环境的测试进程会继承 CI=true，CLI 会因此拒绝 bootstrap/advance；
    // 除了「CI 禁止」专项测试外，其余测试都要显式清除 CI（等价于非 CI 机器）。
    const merged = { ...process.env, ...env };
    if (env.clearCI) {
        delete merged.CI;
        delete merged.clearCI;
    }
    return spawnSync('node', [CLI, ...args], { cwd: root, encoding: 'utf8', env: merged });
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

test('trust-gate：--show 未设置时提示 bootstrap', () => {
    const root = makeRepo();
    try {
        const show = runCli(root, ['--show']);
        assert.equal(show.status, 0, show.stdout + show.stderr);
        assert.match(show.stdout, /<not set>/);
        assert.match(show.stdout, /--bootstrap --ref HEAD/);
    } finally {
        cleanRepo(root);
    }
});

test('trust-gate：bootstrap 写入 local config；--show 输出 SHA 与 contract version', () => {
    const root = makeRepo();
    try {
        const head = git(['rev-parse', 'HEAD'], root).stdout.trim();
        const boot = runCli(root, ['--bootstrap', '--ref', 'HEAD'], { clearCI: true });
        assert.equal(boot.status, 0, boot.stdout + boot.stderr);
        assert.match(boot.stdout + boot.stderr, /This is the initial local trust decision/);
        assert.match(boot.stdout + boot.stderr, /trust anchor set to/);
        const configured = git(['config', '--local', '--get', 'pixiv.i18n.trustedGateRef'], root).stdout.trim();
        assert.equal(configured, head, 'bootstrap 必须写入当前 HEAD 的完整 SHA');

        const show = runCli(root, ['--show']);
        assert.equal(show.status, 0, show.stdout + show.stderr);
        assert.match(show.stdout, new RegExp('trustedGateRef: ' + head));
        assert.match(show.stdout, /contractVersion: 1/);
    } finally {
        cleanRepo(root);
    }
});

test('trust-gate：bootstrap 只写 local 配置（不写 global）', () => {
    const root = makeRepo();
    try {
        const boot = runCli(root, ['--bootstrap', '--ref', 'HEAD'], { clearCI: true });
        assert.equal(boot.status, 0, boot.stdout + boot.stderr);
        const origin = git(['config', '--show-origin', '--get', 'pixiv.i18n.trustedGateRef'], root, { allowFailure: true });
        assert.match(origin.stdout, /\.git[/\\]config/);
        const global = git(['config', '--global', '--get', 'pixiv.i18n.trustedGateRef'], root, { allowFailure: true });
        assert.notEqual(global.status, 0, 'global 配置不得写入');
    } finally {
        cleanRepo(root);
    }
});

test('trust-gate：CI 环境禁止 bootstrap trust', () => {
    const root = makeRepo();
    try {
        const boot = runCli(root, ['--bootstrap', '--ref', 'HEAD'], { CI: 'true' });
        assert.notEqual(boot.status, 0, 'CI=true 必须拒绝 bootstrap');
        assert.match(boot.stderr, /forbidden in CI/);
        const configured = git(['config', '--local', '--get', 'pixiv.i18n.trustedGateRef'], root, { allowFailure: true });
        assert.notEqual(configured.status, 0, 'CI 拒绝后不得写入配置');
    } finally {
        cleanRepo(root);
    }
});

test('trust-gate：bootstrap 只接受完整 commit（拒绝工作树路径 / 非 commit ref）', () => {
    const root = makeRepo();
    try {
        for (const bad of ['HEAD^{tree}', './scripts', 'README.md', 'not-a-ref']) {
            const boot = runCli(root, ['--bootstrap', '--ref', bad], { clearCI: true });
            assert.notEqual(boot.status, 0, '必须拒绝: ' + bad);
            assert.match(boot.stderr, /must resolve to a full commit/);
        }
    } finally {
        cleanRepo(root);
    }
});

test('trust-gate：bootstrap 脏工作树拒绝；--allow-dirty 显式豁免（迁移流程用）', () => {
    const root = makeRepo();
    try {
        fs.writeFileSync(path.join(root, 'dirty.txt'), 'dirty\n', 'utf8');
        const refused = runCli(root, ['--bootstrap', '--ref', 'HEAD'], { clearCI: true });
        assert.notEqual(refused.status, 0, '脏工作树必须拒绝');
        assert.match(refused.stderr, /worktree is not clean/);
        // index 暂存改动永远拒绝（即使 --allow-dirty）
        git(['add', 'dirty.txt'], root);
        const staged = runCli(root, ['--bootstrap', '--ref', 'HEAD', '--allow-dirty'], { clearCI: true });
        assert.notEqual(staged.status, 0, '暂存改动 + --allow-dirty 仍必须拒绝');
        assert.match(staged.stderr, /staged changes/);
        git(['reset', '-q', 'HEAD', '--', 'dirty.txt'], root, { allowFailure: true });
        fs.rmSync(path.join(root, 'dirty.txt'));
        // --allow-dirty 只豁免未暂存工作树改动
        fs.writeFileSync(path.join(root, 'dirty.txt'), 'dirty\n', 'utf8');
        const allowed = runCli(root, ['--bootstrap', '--ref', 'HEAD', '--allow-dirty'], { clearCI: true });
        assert.equal(allowed.status, 0, allowed.stdout + allowed.stderr);
        const configured = git(['config', '--local', '--get', 'pixiv.i18n.trustedGateRef'], root).stdout.trim();
        assert.ok(/^[0-9a-f]{40}$/.test(configured));
    } finally {
        cleanRepo(root);
    }
});

test('trust-gate：bootstrap 测试套件失败 → 拒绝；已有 anchor → 拒绝并提示 advance', () => {
    const root = makeRepo();
    try {
        // 放入一个必失败的测试文件并提交（bypass：工作树保持干净）→ suite 失败 → bootstrap 拒绝
        const testDir = path.join(root, 'scripts', 'i18n', 'test');
        fs.mkdirSync(testDir, { recursive: true });
        fs.writeFileSync(path.join(testDir, 'failing.test.mjs'),
            "import { test } from 'node:test';\nimport assert from 'node:assert/strict';\ntest('must fail', () => assert.equal(1, 2));\n", 'utf8');
        git(['add', '-A'], root);
        git(['-c', 'core.hooksPath=/dev/null', 'commit', '-q', '-m', 'add failing test'], root);
        const boot = runCli(root, ['--bootstrap', '--ref', 'HEAD'], { clearCI: true });
        assert.notEqual(boot.status, 0, '测试套件失败时 bootstrap 必须拒绝\nSTDOUT: '
            + boot.stdout + '\nSTDERR: ' + boot.stderr);
        assert.match(boot.stderr, /full i18n tests failed/);
        git(['reset', '-q', '--hard', 'HEAD~1'], root);

        const ok = runCli(root, ['--bootstrap', '--ref', 'HEAD'], { clearCI: true });
        assert.equal(ok.status, 0, ok.stdout + ok.stderr);
        const again = runCli(root, ['--bootstrap', '--ref', 'HEAD'], { clearCI: true });
        assert.notEqual(again.status, 0, '已 bootstrap 后必须拒绝再次 bootstrap');
        assert.match(again.stderr, /use --advance/);
    } finally {
        cleanRepo(root);
    }
});

test('trust-gate：advance 合法推进锚点；不自动发生；无 anchor 拒绝', () => {
    if (!hasBash()) {
        test.skip('bash 不可用');
        return;
    }
    const root = makeRepo();
    try {
        const c2 = git(['rev-parse', 'HEAD'], root).stdout.trim();
        const boot = runCli(root, ['--bootstrap', '--ref', 'HEAD'], { clearCI: true });
        assert.equal(boot.status, 0, boot.stdout + boot.stderr);

        // 正常提交 C3（经 pre-commit）→ 锚点不自动推进
        const jsDir = path.join(root, 'pixivdownload-app', 'src', 'main', 'resources', 'static', 'js');
        fs.mkdirSync(jsDir, { recursive: true });
        fs.writeFileSync(path.join(jsDir, 'x.js'), 'var x = 1;\n', 'utf8');
        git(['add', '-A'], root);
        const commit = git(['commit', '-q', '-m', 'normal commit'], root);
        assert.equal(commit.status, 0, commit.stdout + commit.stderr);
        const afterCommit = git(['config', '--local', '--get', 'pixiv.i18n.trustedGateRef'], root).stdout.trim();
        assert.equal(afterCommit, c2, '普通提交不得自动推进锚点');

        // advance --ref HEAD → 合法推进
        const head = git(['rev-parse', 'HEAD'], root).stdout.trim();
        const advance = runCli(root, ['--advance', '--ref', 'HEAD'], { clearCI: true });
        assert.equal(advance.status, 0, advance.stdout + advance.stderr);
        const afterAdvance = git(['config', '--local', '--get', 'pixiv.i18n.trustedGateRef'], root).stdout.trim();
        assert.equal(afterAdvance, head, 'advance 必须推进到候选 SHA');
        assert.notEqual(afterAdvance, c2);

        // 已推进后的 --show
        const show = runCli(root, ['--show']);
        assert.match(show.stdout, new RegExp('trustedGateRef: ' + head));
    } finally {
        cleanRepo(root);
    }
});

test('trust-gate：advance 无 anchor 拒绝；候选非 commit 拒绝；CI 拒绝', () => {
    const root = makeRepo();
    try {
        const noAnchor = runCli(root, ['--advance', '--ref', 'HEAD'], { clearCI: true });
        assert.notEqual(noAnchor.status, 0, '无 anchor 必须提示 bootstrap');
        assert.match(noAnchor.stderr, /run bootstrap first/);

        const boot = runCli(root, ['--bootstrap', '--ref', 'HEAD'], { clearCI: true });
        assert.equal(boot.status, 0, boot.stdout + boot.stderr);
        const badRef = runCli(root, ['--advance', '--ref', 'HEAD^{tree}'], { clearCI: true });
        assert.notEqual(badRef.status, 0, '非 commit 候选必须拒绝');
        assert.match(badRef.stderr, /must resolve to a full commit/);
        const ci = runCli(root, ['--advance', '--ref', 'HEAD'], { CI: 'true' });
        assert.notEqual(ci.status, 0, 'CI 环境禁止 advance（不得由 CI 修改 local config）');
        assert.match(ci.stderr, /forbidden in CI/);
    } finally {
        cleanRepo(root);
    }
});

test('trust-gate：advance 删除 required file → 拒绝；no-op checker → 拒绝；no-op contract → 拒绝；policy 弱化 → 拒绝', () => {
    if (!hasBash()) {
        test.skip('bash 不可用');
        return;
    }
    const root = makeRepo();
    try {
        const boot = runCli(root, ['--bootstrap', '--ref', 'HEAD'], { clearCI: true });
        assert.equal(boot.status, 0, boot.stdout + boot.stderr);
        const anchor = git(['config', '--local', '--get', 'pixiv.i18n.trustedGateRef'], root).stdout.trim();

        // 1) 删除 required file
        git(['rm', '-q', 'scripts/i18n/check.mjs'], root);
        commitBypass(root, 'delete checker');
        const del = runCli(root, ['--advance', '--ref', 'HEAD'], { clearCI: true });
        assert.notEqual(del.status, 0, '删除 required file 必须拒绝 advance');
        assert.match(del.stderr, /GATE CONTRACT FAILED|required gate files|incomplete/);
        git(['reset', '-q', '--hard', 'HEAD~1'], root);

        // 2) no-op checker
        fs.writeFileSync(path.join(root, 'scripts', 'i18n', 'check.mjs'), EXIT_ZERO_CHECKER, 'utf8');
        commitBypass(root, 'noop checker');
        const noopChecker = runCli(root, ['--advance', '--ref', 'HEAD'], { clearCI: true });
        assert.notEqual(noopChecker.status, 0, 'no-op checker 必须拒绝 advance');
        assert.match(noopChecker.stderr, /GATE CONTRACT FAILED/);
        git(['reset', '-q', '--hard', 'HEAD~1'], root);

        // 3) no-op contract（自保护）
        fs.writeFileSync(path.join(root, 'scripts', 'i18n', 'gate-contract.mjs'), EXIT_ZERO_CHECKER, 'utf8');
        commitBypass(root, 'noop contract');
        const noopContract = runCli(root, ['--advance', '--ref', 'HEAD'], { clearCI: true });
        assert.notEqual(noopContract.status, 0, 'no-op contract 必须拒绝 advance（不能保护下一次升级）');
        assert.match(noopContract.stderr, /GATE CONTRACT FAILED/);
        git(['reset', '-q', '--hard', 'HEAD~1'], root);

        // 4) policy 弱化：requiredPaths 减少
        const policyPath = path.join(root, 'scripts', 'i18n', 'gate-policy.json');
        const policy = JSON.parse(fs.readFileSync(policyPath, 'utf8'));
        policy.requiredPaths = policy.requiredPaths.filter((p) => p !== 'scripts/i18n/check.mjs');
        fs.writeFileSync(policyPath, JSON.stringify(policy, null, 2) + '\n', 'utf8');
        commitBypass(root, 'weaken policy');
        const weaken = runCli(root, ['--advance', '--ref', 'HEAD'], { clearCI: true });
        assert.notEqual(weaken.status, 0, 'requiredPaths 减少必须拒绝 advance');
        assert.match(weaken.stderr, /GATE CONTRACT FAILED|required paths/);
        git(['reset', '-q', '--hard', 'HEAD~1'], root);

        // 5) policy 弱化：enforcement start 后移（向后移动）
        const policy2 = JSON.parse(fs.readFileSync(policyPath, 'utf8'));
        policy2.i18nEnforcementStartCommit = 'ffffffffffffffffffffffffffffffffffffffff';
        fs.writeFileSync(policyPath, JSON.stringify(policy2, null, 2) + '\n', 'utf8');
        commitBypass(root, 'move enforcement start');
        const moved = runCli(root, ['--advance', '--ref', 'HEAD'], { clearCI: true });
        assert.notEqual(moved.status, 0, 'enforcement start 后移必须拒绝 advance');
        assert.match(moved.stderr, /GATE CONTRACT FAILED|enforcement/);
        git(['reset', '-q', '--hard', 'HEAD~1'], root);

        // 锚点未被任何失败 advance 改动
        const still = git(['config', '--local', '--get', 'pixiv.i18n.trustedGateRef'], root).stdout.trim();
        assert.equal(still, anchor, '失败 advance 不得修改锚点');
    } finally {
        cleanRepo(root);
    }
});
