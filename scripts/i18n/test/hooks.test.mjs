'use strict';
/**
 * 真实临时 Git 仓库回归测试：
 * - pre-commit 检查 index 快照（暂存坏/工作树好 → 失败；暂存好/工作树坏 → 通过）；
 * - pre-push 检查实际待推送 commit（中间 commit 坏、tip 修好 → 检测中间 commit）；
 * - pre-push 不受未提交工作树修复影响；
 * - 新分支 / 删除 ref / 多 ref 去重；
 * - 签名守卫检查待推送 commit；
 * - 临时目录清理、Windows 路径含空格。
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { runGenerate } from '../generate-static.mjs';
import { runAcceptCore } from '../accept.mjs';
import staleLock from '../lib/stale-lock.mjs';

const SCRIPTS_DIR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const REPO_ROOT = path.resolve(SCRIPTS_DIR, '..', '..');

// pre-commit/pre-push 内的 trusted contract 使用 yaml 解析候选 workflow：
// fixture 仓库没有 node_modules，通过 NODE_PATH 指向真实仓库的 node_modules
// （与 CI 的 npm ci 等价），子进程（bash hooks / node checker / git push）继承该环境。
process.env.NODE_PATH = process.env.NODE_PATH || path.join(REPO_ROOT, 'node_modules');

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
const BAD_EN = 'greeting=Hello {wrong}\ntitle=Artwork title\n';

function git(args, cwd, opts = {}) {
    const result = spawnSync('git', args, { cwd, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024, ...opts });
    if (result.status !== 0 && !opts.allowFailure) {
        throw new Error('git ' + args.join(' ') + ' failed: ' + (result.stderr || result.stdout));
    }
    return result;
}

function bash(args, cwd, opts = {}) {
    return spawnSync('bash', args, { cwd, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024, ...opts });
}

function hasBash() {
    try {
        execFileSync('bash', ['--version'], { stdio: 'ignore' });
        return true;
    } catch (e) {
        return false;
    }
}

/**
 * 建立带真实 scripts/i18n 与 scripts/hooks 的临时 git 仓库。
 * 提交历史与真实仓库同构：
 * - C1（root，enforcement start）：gate bundle（scripts/i18n + scripts/hooks）+ bundle + lock + 静态资源，
 *   但**不**带 gate-policy.json（真实仓库中 policy 晚于 enforcement start 引入，05f4ebed 同样没有 policy）；
 * - C2：引入 gate-policy.json（i18nEnforcementStartCommit = C1），作为本地 trusted anchor。
 * 之后启用 core.hooksPath 并写入 pixiv.i18n.trustedGateRef = C2。
 * 这样后续每个历史 commit 都满足 i18n 门禁，pre-push 的逐 commit 检查只在故意注入的坏提交上失败。
 * fullGate=true 时 C1 还包含 scripts/ci、.github/workflows/quality-gate.yml 与 package.json
 * （候选 gate 完整 bundle；用于 workflow/package scripts 弱化场景）。
 */
function makeGitRepo(base = os.tmpdir(), opts = {}) {
    const dir = path.join(base, 'pixiv test repo ' + Date.now() + '-' + Math.random().toString(36).slice(2));
    fs.mkdirSync(dir, { recursive: true });
    git(['init', '-q'], dir);
    git(['config', 'user.email', 't@example.com'], dir);
    git(['config', 'user.name', 'test'], dir);
    git(['config', 'core.autocrlf', 'false'], dir);

    // WSL bash 不继承 Windows 进程的 NODE_PATH：junction node_modules，让 hook 内的
    // trusted contract 与 CI（npm ci）一样从 repoRoot 解析 yaml
    fs.writeFileSync(path.join(dir, '.gitignore'), 'build/\nnode_modules/\n', 'utf8');
    try {
        if (process.platform === 'win32') {
            fs.symlinkSync(path.join(REPO_ROOT, 'node_modules'), path.join(dir, 'node_modules'), 'junction');
        } else {
            fs.symlinkSync(path.join(REPO_ROOT, 'node_modules'), path.join(dir, 'node_modules'));
        }
    } catch (e) {
        // node_modules 不可链接时静默跳过：候选 predates workflow 的场景不需要 yaml
    }

    // 复制真实检查器与 hooks（hooks 经 core.hooksPath 生效）；测试目录不需要且含签名标记字样
    fs.cpSync(path.join(REPO_ROOT, 'scripts', 'i18n'), path.join(dir, 'scripts', 'i18n'), { recursive: true });
    fs.rmSync(path.join(dir, 'scripts', 'i18n', 'test'), { recursive: true, force: true });
    // C1 不带 gate-policy.json（与真实 enforcement start 05f4ebed 同构：policy 在后续提交引入）
    fs.rmSync(path.join(dir, 'scripts', 'i18n', 'gate-policy.json'), { force: true });
    fs.cpSync(path.join(REPO_ROOT, 'scripts', 'hooks'), path.join(dir, 'scripts', 'hooks'), { recursive: true });
    if (opts.fullGate) {
        fs.cpSync(path.join(REPO_ROOT, 'scripts', 'ci'), path.join(dir, 'scripts', 'ci'), { recursive: true });
        fs.mkdirSync(path.join(dir, '.github', 'workflows'), { recursive: true });
        fs.copyFileSync(path.join(REPO_ROOT, '.github', 'workflows', 'quality-gate.yml'),
            path.join(dir, '.github', 'workflows', 'quality-gate.yml'));
        fs.copyFileSync(path.join(REPO_ROOT, 'package.json'), path.join(dir, 'package.json'));
    }

    // 初始 bundle + 静态资源 + lock（全部合法）
    const i18nDir = path.join(dir, APP_I18N);
    fs.mkdirSync(path.join(i18nDir, 'web'), { recursive: true });
    fs.writeFileSync(path.join(i18nDir, 'locales.json'), CATALOG, 'utf8');
    writeBundles(dir, GOOD_ZH, GOOD_EN);
    // 核心库路径 bootstrap：不受外部 CI=true 环境变量污染（CLI 安全策略只在 main() 生效）
    const bootstrap = runAcceptCore(dir, { bootstrap: true });
    if (!bootstrap.ok) {
        throw new Error('fixture bootstrap failed: ' + bootstrap.refused.join('\n'));
    }
    runGenerate(dir);

    git(['add', '--chmod=+x', 'scripts/hooks/pre-commit', 'scripts/hooks/pre-push', 'scripts/hooks/pre-push-guard.sh', 'scripts/hooks/execfile-shim.cjs'], dir);
    git(['add', '-A'], dir);
    git(['commit', '-q', '-m', 'init'], dir); // C1 = enforcement start
    const start = git(['rev-parse', 'HEAD'], dir).stdout.trim();

    // C2：引入 gate-policy.json（start = C1）
    const policyPath = path.join(dir, 'scripts', 'i18n', 'gate-policy.json');
    const policy = JSON.parse(fs.readFileSync(path.join(REPO_ROOT, 'scripts', 'i18n', 'gate-policy.json'), 'utf8'));
    policy.i18nEnforcementStartCommit = start;
    fs.writeFileSync(policyPath, JSON.stringify(policy, null, 2) + '\n', 'utf8');
    git(['add', '-A'], dir);
    git(['commit', '-q', '-m', 'add gate policy'], dir);
    const anchor = git(['rev-parse', 'HEAD'], dir).stdout.trim();

    // 激活本地 hooks 并写入 trusted anchor（模拟已 bootstrap 的状态）
    git(['config', '--local', 'core.hooksPath', 'scripts/hooks'], dir);
    git(['config', '--local', 'pixiv.i18n.trustedGateRef', anchor], dir);
    return dir;
}

function writeBundles(root, zh, en) {
    const i18nDir = path.join(root, APP_I18N);
    fs.writeFileSync(path.join(i18nDir, 'web', 'common.properties'), zh, 'utf8');
    fs.writeFileSync(path.join(i18nDir, 'web', 'common_en.properties'), en, 'utf8');
    runGenerate(root);
}

/** 提交；bypass=true 时跳过本地 hooks（仅用于在测试夹具中构造坏 commit，
 * 模拟「在未安装 hooks 的机器上产生的历史坏提交」——被推者仍会被 pre-push 拦截）。 */
function commitAll(root, message, opts = {}) {
    git(['add', '-A'], root);
    if (opts.bypass) {
        git(['-c', 'core.hooksPath=/dev/null', 'commit', '-q', '-m', message], root);
    } else {
        git(['commit', '-q', '-m', message], root);
    }
}

/** 写入 bundle 后接受基线并提交（fixture 的初始合法状态；核心库路径，不受 CI 环境变量影响）。 */
function bootstrapRepo(root) {
    const bootstrap = runAcceptCore(root, { bootstrap: true });
    assert.equal(bootstrap.ok, true, 'bootstrap 必须成功: ' + bootstrap.refused.join('\n'));
    runGenerate(root);
    git(['add', '-A'], root);
    git(['commit', '-q', '-m', 'i18n baseline'], root);
}

/**
 * 持久泄漏快照目录计数：只统计存在超过 30 秒的目录。
 * 并行测试进程的临时快照目录生命周期只有毫秒级，持续存在的目录才代表真正的泄漏
 * （crash / 未清理路径），这样断言不依赖并发时序。
 */
function snapshotLeakCount() {
    const cutoff = Date.now() - 30 * 1000;
    return fs.readdirSync(os.tmpdir(), { withFileTypes: true })
        .filter((e) => e.isDirectory() && e.name.startsWith('pixivdownload-i18n-snapshot-'))
        .filter((e) => fs.statSync(path.join(os.tmpdir(), e.name)).mtimeMs < cutoff)
        .length;
}

/** 测试文件以独立进程并行执行，共享系统临时目录；检查器进程在 finally 中清理，等待并发进程清理完毕。
 * 注意：gate contract / trust-gate 等长耗时检查进程可能持续 30-60s，等待窗口必须覆盖它们。 */
function waitForLeakCount(target) {
    for (let i = 0; i < 240; i += 1) {
        if (snapshotLeakCount() <= target) {
            return;
        }
        execFileSync('bash', ['-c', 'sleep 0.5'], { stdio: 'ignore' });
    }
}

function cleanRepo(root) {
    if (!root) {
        return;
    }
    // Windows 下子进程句柄可能短暂残留，重试几次再放弃
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

test('pre-commit：暂存坏英文、工作树修好但不 add → 必须失败', () => {
    if (!hasBash()) {
        test.skip('bash 不可用');
        return;
    }
    const before = snapshotLeakCount();
    const root = makeGitRepo();
    try {
        // 工作树与 index 都改为坏英文，然后 git add（index 坏）
        writeBundles(root, GOOD_ZH, BAD_EN);
        git(['add', '-A'], root);

        // 工作树修好，但不 git add
        writeBundles(root, GOOD_ZH, GOOD_EN);

        const result = bash(['scripts/hooks/pre-commit'], root);
        assert.notEqual(result.status, 0, 'index 中是坏翻译，pre-commit 必须失败');
        assert.match(result.stdout + result.stderr, /I18N CHECK FAILED|FAILED/);

        // 工作树保持用户的修复，pre-commit 不得修改文件
        const worktree = fs.readFileSync(path.join(root, APP_I18N, 'web', 'common_en.properties'), 'utf8');
        assert.equal(worktree, GOOD_EN);

        // 重新暂存修复后通过
        git(['add', '-A'], root);
        const ok = bash(['scripts/hooks/pre-commit'], root);
        assert.equal(ok.status, 0, 'index 修复后 pre-commit 必须通过: ' + ok.stdout + ok.stderr);
    } finally {
        cleanRepo(root);
    }
    waitForLeakCount(before);
    assert.equal(snapshotLeakCount(), before, '临时快照必须全部清理');
});

test('pre-commit：trusted anchor 缺失 → fail closed 并提示 bootstrap', () => {
    if (!hasBash()) {
        test.skip('bash 不可用');
        return;
    }
    const root = makeGitRepo();
    try {
        git(['config', '--local', '--unset', 'pixiv.i18n.trustedGateRef'], root);
        // 暂存一个普通业务文件触发 hook
        fs.mkdirSync(path.join(root, 'pixivdownload-app', 'src', 'main', 'resources', 'static', 'js'), { recursive: true });
        fs.writeFileSync(path.join(root, 'pixivdownload-app', 'src', 'main', 'resources', 'static', 'js', 'x.js'),
            'var x = 1;\n', 'utf8');
        git(['add', '-A'], root);
        const result = bash(['scripts/hooks/pre-commit'], root);
        assert.notEqual(result.status, 0, 'anchor 缺失必须 fail closed');
        assert.match(result.stdout + result.stderr, /no trusted gate anchor/);
        assert.match(result.stdout + result.stderr, /i18n:trust-gate -- --bootstrap --ref HEAD/);
    } finally {
        cleanRepo(root);
    }
});

test('pre-commit：trusted anchor 指向不存在的 commit → fail closed', () => {
    if (!hasBash()) {
        test.skip('bash 不可用');
        return;
    }
    const root = makeGitRepo();
    try {
        git(['config', '--local', 'pixiv.i18n.trustedGateRef', 'deadbeefdeadbeefdeadbeefdeadbeefdeadbeef'], root);
        fs.mkdirSync(path.join(root, 'pixivdownload-app', 'src', 'main', 'resources', 'static', 'js'), { recursive: true });
        fs.writeFileSync(path.join(root, 'pixivdownload-app', 'src', 'main', 'resources', 'static', 'js', 'x.js'),
            'var x = 1;\n', 'utf8');
        git(['add', '-A'], root);
        const result = bash(['scripts/hooks/pre-commit'], root);
        assert.notEqual(result.status, 0);
        assert.match(result.stdout + result.stderr, /does not resolve to a commit/);
    } finally {
        cleanRepo(root);
    }
});

test('pre-commit：两步降级第一步 —— 只把 check.mjs 改成 exit(0)（无坏翻译）→ 必须失败', () => {
    if (!hasBash()) {
        test.skip('bash 不可用');
        return;
    }
    const root = makeGitRepo();
    try {
        fs.writeFileSync(path.join(root, 'scripts', 'i18n', 'check.mjs'),
            '#!/usr/bin/env node\nprocess.exit(0);\n', 'utf8');
        git(['add', '-A'], root);
        const result = bash(['scripts/hooks/pre-commit'], root);
        assert.notEqual(result.status, 0, '暂存 no-op checker（无坏翻译）也必须被 trusted contract 拦截');
        assert.match(result.stdout + result.stderr, /GATE CONTRACT FAILED|contract/);
    } finally {
        cleanRepo(root);
    }
});

test('pre-commit：两步降级第二步 —— HEAD checker 为 no-op、anchor 仍指向旧正常 commit、index 坏翻译 → 必须失败', () => {
    if (!hasBash()) {
        test.skip('bash 不可用');
        return;
    }
    const root = makeGitRepo();
    try {
        const anchor = git(['config', '--local', '--get', 'pixiv.i18n.trustedGateRef'], root).stdout.trim();
        // 夹具绕过第一步：直接提交 no-op HEAD（bypass），trustedGateRef 不动
        fs.writeFileSync(path.join(root, 'scripts', 'i18n', 'check.mjs'),
            '#!/usr/bin/env node\nprocess.exit(0);\n', 'utf8');
        git(['add', '-A'], root);
        git(['-c', 'core.hooksPath=/dev/null', 'commit', '-q', '-m', 'noop head'], root);
        assert.equal(git(['config', '--local', '--get', 'pixiv.i18n.trustedGateRef'], root).stdout.trim(), anchor);

        // index 加入坏翻译
        writeBundles(root, GOOD_ZH, BAD_EN);
        git(['add', '-A'], root);
        const result = bash(['scripts/hooks/pre-commit'], root);
        assert.notEqual(result.status, 0, 'HEAD no-op 时 trusted anchor checker 必须仍拦截坏翻译');
        assert.match(result.stdout + result.stderr, /trusted gate checker/);
        assert.match(result.stdout + result.stderr, /I18N CHECK FAILED|FAILED/);
    } finally {
        cleanRepo(root);
    }
});

test('pre-commit：git rm scripts/i18n/check.mjs（required checker 删除）→ 必须失败', () => {
    if (!hasBash()) {
        test.skip('bash 不可用');
        return;
    }
    const root = makeGitRepo();
    try {
        git(['rm', '-q', 'scripts/i18n/check.mjs'], root);
        const result = bash(['scripts/hooks/pre-commit'], root);
        assert.notEqual(result.status, 0, 'required checker 删除必须 fail closed');
        assert.match(result.stdout + result.stderr, /GATE CONTRACT FAILED|required gate files|incomplete/);
    } finally {
        cleanRepo(root);
    }
});

test('pre-commit：git rm scripts/hooks/pre-push（required hook 删除）→ 必须失败', () => {
    if (!hasBash()) {
        test.skip('bash 不可用');
        return;
    }
    const root = makeGitRepo();
    try {
        git(['rm', '-q', 'scripts/hooks/pre-push'], root);
        const result = bash(['scripts/hooks/pre-commit'], root);
        assert.notEqual(result.status, 0, 'required hook 删除必须 fail closed');
        assert.match(result.stdout + result.stderr, /GATE CONTRACT FAILED|required gate files|incomplete/);
    } finally {
        cleanRepo(root);
    }
});

test('pre-commit：git rm scripts/i18n/gate-contract.mjs（gate contract 删除）→ 必须失败', () => {
    if (!hasBash()) {
        test.skip('bash 不可用');
        return;
    }
    const root = makeGitRepo();
    try {
        git(['rm', '-q', 'scripts/i18n/gate-contract.mjs'], root);
        const result = bash(['scripts/hooks/pre-commit'], root);
        assert.notEqual(result.status, 0, 'gate contract 删除必须 fail closed');
        assert.match(result.stdout + result.stderr, /GATE CONTRACT FAILED|required gate files|incomplete/);
    } finally {
        cleanRepo(root);
    }
});

test('pre-commit：合法 gate 升级（暂存合法增强的 checker）→ trusted contract 通过', () => {
    if (!hasBash()) {
        test.skip('bash 不可用');
        return;
    }
    const root = makeGitRepo();
    try {
        const checkerPath = path.join(root, 'scripts', 'i18n', 'check.mjs');
        const original = fs.readFileSync(checkerPath, 'utf8');
        fs.writeFileSync(checkerPath, original + '\n// staged enhancement\n', 'utf8');
        fs.mkdirSync(path.join(root, 'pixivdownload-app', 'src', 'main', 'resources', 'static', 'js'), { recursive: true });
        fs.writeFileSync(path.join(root, 'pixivdownload-app', 'src', 'main', 'resources', 'static', 'js', 'x.js'),
            'var x = 1;\n', 'utf8');
        git(['add', '-A'], root);
        const result = bash(['scripts/hooks/pre-commit'], root);
        assert.equal(result.status, 0, '合法 gate 升级必须通过 trusted contract: ' + result.stdout + result.stderr);
        assert.match(result.stdout, /GATE CONTRACT OK|gate contract/);
    } finally {
        cleanRepo(root);
    }
});

test('pre-commit：静态资源未暂存（工作树 stale）→ 按 index 快照通过', () => {
    if (!hasBash()) {
        test.skip('bash 不可用');
        return;
    }
    const root = makeGitRepo();
    try {
        // 工作树静态资源改坏但不暂存；只暂存普通业务文件
        const staticFile = path.join(root, 'pixivdownload-app', 'src', 'main', 'resources', 'static', 'i18n-static', 'meta.json');
        const original = fs.readFileSync(staticFile, 'utf8');
        fs.writeFileSync(staticFile, original + '\n// stale\n', 'utf8');
        fs.mkdirSync(path.join(root, 'pixivdownload-app', 'src', 'main', 'resources', 'static', 'js'), { recursive: true });
        fs.writeFileSync(path.join(root, 'pixivdownload-app', 'src', 'main', 'resources', 'static', 'js', 'x.js'),
            'var x = 1;\n', 'utf8');
        git(['add', path.join('pixivdownload-app', 'src', 'main', 'resources', 'static', 'js', 'x.js')], root);
        const result = bash(['scripts/hooks/pre-commit'], root);
        assert.equal(result.status, 0, '未暂存的静态资源变化不得影响 index 判定: ' + result.stdout + result.stderr);
        fs.writeFileSync(staticFile, original, 'utf8');
    } finally {
        cleanRepo(root);
    }
});

test('pre-commit：暂存正确英文、工作树改坏但不 add → 按暂存快照通过', () => {
    if (!hasBash()) {
        test.skip('bash 不可用');
        return;
    }
    const root = makeGitRepo();
    try {
        // 工作树改坏（不暂存）
        writeBundles(root, GOOD_ZH, BAD_EN);

        const result = bash(['scripts/hooks/pre-commit'], root);
        assert.equal(result.status, 0, 'index 仍是好翻译，pre-commit 必须按 index 通过: '
            + result.stdout + result.stderr);
    } finally {
        cleanRepo(root);
    }
});

test('pre-commit：新增暂存文件触发的完整检查；无暂存文件时快速退出', () => {
    if (!hasBash()) {
        test.skip('bash 不可用');
        return;
    }
    const root = makeGitRepo();
    try {
        // 无暂存 → 快速退出
        const empty = bash(['scripts/hooks/pre-commit'], root);
        assert.equal(empty.status, 0);
        assert.match(empty.stdout, /nothing staged/);

        // 暂存一个普通业务文件（含硬编码语言）→ 硬编码守卫必须命中
        const staticJs = path.join(root, 'pixivdownload-app', 'src', 'main', 'resources', 'static', 'js');
        fs.mkdirSync(staticJs, { recursive: true });
        fs.writeFileSync(path.join(staticJs, 'some-feature.js'),
            "const supportedLocales = ['en-US', 'zh-CN'];\n", 'utf8');
        git(['add', '-A'], root);
        const result = bash(['scripts/hooks/pre-commit'], root);
        assert.notEqual(result.status, 0, '普通业务文件硬编码语言必须被 pre-commit 拦截');
        assert.match(result.stdout + result.stderr, /hardcoded-locale|I18N CHECK FAILED/);
    } finally {
        cleanRepo(root);
    }
});

test('pre-commit：删除暂存文件场景不崩溃', () => {
    if (!hasBash()) {
        test.skip('bash 不可用');
        return;
    }
    const root = makeGitRepo();
    try {
        // 删除 en 文件并暂存删除 → 完整检查应报 missing-language-file
        fs.rmSync(path.join(root, APP_I18N, 'web', 'common_en.properties'));
        runGenerate(root);
        git(['add', '-A'], root);
        const result = bash(['scripts/hooks/pre-commit'], root);
        assert.notEqual(result.status, 0);
        assert.match(result.stdout + result.stderr, /missing-language-file|I18N CHECK FAILED/);
    } finally {
        cleanRepo(root);
    }
});

test('pre-push：中间 commit 坏、tip 修好 → 必须检测中间 commit；未提交修复不影响', () => {
    if (!hasBash()) {
        test.skip('bash 不可用');
        return;
    }
    const root = makeGitRepo();
    const remote = path.join(os.tmpdir(), 'pixiv bare remote ' + Date.now());
    try {
        fs.mkdirSync(remote);
        git(['init', '-q', '--bare', remote], root);
        git(['remote', 'add', 'origin', remote], root);

        // 提交 A：坏翻译（bypass 本地 pre-commit，模拟远端机器产生的历史坏提交）
        writeBundles(root, GOOD_ZH, BAD_EN);
        commitAll(root, 'commit A bad', { bypass: true });

        // 提交 B：修好
        writeBundles(root, GOOD_ZH, GOOD_EN);
        commitAll(root, 'commit B good', { bypass: true });

        // 工作树再改坏（未提交）——不得影响判定
        writeBundles(root, GOOD_ZH, BAD_EN);

        const push = git(['push', 'origin', 'master'], root, { allowFailure: true });
        assert.notEqual(push.status, 0, '待推送范围包含坏 commit A，pre-push 必须失败');
        assert.match(push.stdout + push.stderr, /commit A bad|FAILED/);
        assert.match(push.stdout + push.stderr, /does not pass the i18n gate/);

        // 远端必须没有任何更新
        const lsRemote = git(['ls-remote', remote], root);
        assert.equal(lsRemote.stdout.trim(), '');

        // 把工作树修复并提交（fixup）→ 历史坏 commit A 仍在推送范围，仍然失败
        writeBundles(root, GOOD_ZH, GOOD_EN);
        fs.writeFileSync(path.join(root, 'fixup.txt'), 'fixup\n', 'utf8');
        commitAll(root, 'commit C fixup');
        const stillBad = git(['push', 'origin', 'master'], root, { allowFailure: true });
        assert.notEqual(stillBad.status, 0, 'fixup 不消除历史坏 commit，pre-push 必须仍然失败');
        assert.match(stillBad.stdout + stillBad.stderr, /commit A bad|FAILED/);

        // 测试夹具内移除坏 commit 后，推送通过
        git(['reset', '-q', '--hard', 'HEAD~3'], root);
        const ok = git(['push', 'origin', 'master'], root, { allowFailure: true });
        assert.equal(ok.status, 0, '移除坏 commit 后 pre-push 必须通过: ' + ok.stdout + ok.stderr);
        assert.match(ok.stdout + ok.stderr, /all \d+ pushed commit\(s\) pass/);
    } finally {
        cleanRepo(root);
        fs.rmSync(remote, { recursive: true, force: true });
    }
});

test('pre-push：普通更新与删除 ref；新分支 commit 全部被验证', () => {
    if (!hasBash()) {
        test.skip('bash 不可用');
        return;
    }
    const root = makeGitRepo();
    const remote = path.join(os.tmpdir(), 'pixiv bare remote ' + Date.now());
    try {
        fs.mkdirSync(remote);
        git(['init', '-q', '--bare', remote], root);
        git(['remote', 'add', 'origin', remote], root);

        // 基线推送成功（makeGitRepo 初始提交即基线）
        const first = git(['push', 'origin', 'master'], root, { allowFailure: true });
        assert.equal(first.status, 0, first.stdout + first.stderr);

        // 新分支（feature）：包含坏 commit → 必须被检测
        git(['checkout', '-q', '-b', 'feature'], root);
        writeBundles(root, GOOD_ZH, BAD_EN);
        commitAll(root, 'feature bad', { bypass: true });
        const featurePush = git(['push', 'origin', 'feature'], root, { allowFailure: true });
        assert.notEqual(featurePush.status, 0, '新分支的坏 commit 必须被检测');
        assert.match(featurePush.stdout + featurePush.stderr, /does not pass the i18n gate/);

        // 测试夹具内移除坏 commit 后，新分支推送通过
        git(['reset', '-q', '--hard', 'HEAD~1'], root);
        const featureOk = git(['push', 'origin', 'feature'], root, { allowFailure: true });
        assert.equal(featureOk.status, 0, featureOk.stdout + featureOk.stderr);

        // 删除 ref：本地 sha 全零 → 跳过
        const del = git(['push', 'origin', '--delete', 'feature'], root, { allowFailure: true });
        assert.equal(del.status, 0, del.stdout + del.stderr);
        assert.match(del.stdout + del.stderr, /skipping deletion/);

        // 多 ref：master 新增合法 commit + 重建 feature → commit 去重后全部通过
        git(['checkout', '-q', 'master'], root);
        fs.writeFileSync(path.join(root, APP_I18N, 'web', 'common.properties'),
            GOOD_ZH + 'status=状态\n', 'utf8');
        fs.writeFileSync(path.join(root, APP_I18N, 'web', 'common_en.properties'),
            GOOD_EN + 'status=Status\n', 'utf8');
        runGenerate(root);
        const accept = spawnSync('node',
            [path.join(root, 'scripts', 'i18n', 'accept.mjs'), '--locale', 'en-US'],
            { cwd: root, encoding: 'utf8' });
        assert.equal(accept.status, 0, accept.stdout + accept.stderr);
        commitAll(root, 'master new key');
        git(['branch', '-f', 'feature', 'master'], root);
        const multi = git(['push', 'origin', 'master', 'feature'], root, { allowFailure: true });
        assert.equal(multi.status, 0, multi.stdout + multi.stderr);
        assert.match(multi.stdout + multi.stderr, /all \d+ pushed commit\(s\) pass/);
    } finally {
        cleanRepo(root);
        fs.rmSync(remote, { recursive: true, force: true });
    }
});

test('pre-push 签名守卫：待推送 commit 含标记 → 失败并指出 SHA', () => {
    if (!hasBash()) {
        test.skip('bash 不可用');
        return;
    }
    const root = makeGitRepo();
    const remote = path.join(os.tmpdir(), 'pixiv bare remote ' + Date.now());
    try {
        fs.mkdirSync(remote);
        git(['init', '-q', '--bare', remote], root);
        git(['remote', 'add', 'origin', remote], root);

        const badJavaDir = path.join(root, 'pixivdownload-app', 'src', 'main', 'java');
        fs.mkdirSync(badJavaDir, { recursive: true });
        fs.writeFileSync(path.join(badJavaDir, 'Bad.java'),
            'class Bad { String s = "DouyinXBogusSigner"; }\n', 'utf8');
        git(['add', '-A'], root);
        commitAll(root, 'bad signature marker', { bypass: true });

        const push = git(['push', 'origin', 'master'], root, { allowFailure: true });
        assert.notEqual(push.status, 0, '含签名标记的 commit 必须被 pre-push 拦截');
        assert.match(push.stdout + push.stderr, /signature guard|reverse-engineered/);
        assert.match(push.stdout + push.stderr, /does not pass the signature guard/);

        // 历史坏 commit 无法修补：测试夹具内 reset 掉它（临时仓库允许），
        // 远端仍必须没有任何更新
        git(['reset', '-q', '--hard', 'HEAD~1'], root);
        const lsRemote = git(['ls-remote', remote], root);
        assert.equal(lsRemote.stdout.trim(), '');
        const ok = git(['push', 'origin', 'master'], root, { allowFailure: true });
        assert.equal(ok.status, 0, '无标记提交后 pre-push 必须通过: ' + ok.stdout + ok.stderr);
        assert.match(ok.stdout + ok.stderr, /signature guard/);
    } finally {
        cleanRepo(root);
        fs.rmSync(remote, { recursive: true, force: true });
    }
});

test('check --snapshot index/ref 不读取工作树；异常退出也不残留临时目录', () => {
    const root = makeGitRepo();
    try {
        // index 快照检查通过（已提交内容合法）
        const indexOk = spawnSync('node',
            [path.join(root, 'scripts', 'i18n', 'check.mjs'), '--snapshot', 'index'],
            { cwd: root, encoding: 'utf8' });
        assert.equal(indexOk.status, 0, indexOk.stdout + indexOk.stderr);

        // 工作树改坏：index 快照检查仍然通过（不读工作树）
        writeBundles(root, GOOD_ZH, BAD_EN);
        const indexStill = spawnSync('node',
            [path.join(root, 'scripts', 'i18n', 'check.mjs'), '--snapshot', 'index'],
            { cwd: root, encoding: 'utf8' });
        assert.equal(indexStill.status, 0, 'index 快照必须不读取工作树');

        // ref 快照检查（HEAD）同样不读工作树
        const refOk = spawnSync('node',
            [path.join(root, 'scripts', 'i18n', 'check.mjs'), '--snapshot', 'ref', '--ref', 'HEAD'],
            { cwd: root, encoding: 'utf8' });
        assert.equal(refOk.status, 0, refOk.stdout + refOk.stderr);

        // 非法 ref → 失败但不残留临时目录
        const badRef = spawnSync('node',
            [path.join(root, 'scripts', 'i18n', 'check.mjs'), '--snapshot', 'ref', '--ref', 'deadbeef'],
            { cwd: root, encoding: 'utf8' });
        assert.notEqual(badRef.status, 0);
    } finally {
        cleanRepo(root);
    }
    waitForLeakCount(0);
    assert.equal(snapshotLeakCount(), 0, '临时快照目录必须全部清理');
});

test('pre-push：candidate tip checker = exit(0) + 非法翻译 → 必须失败（不再自批准）', () => {
    if (!hasBash()) {
        test.skip('bash 不可用');
        return;
    }
    const root = makeGitRepo();
    const remote = path.join(os.tmpdir(), 'pixiv bare remote ' + Date.now());
    try {
        fs.mkdirSync(remote);
        git(['init', '-q', '--bare', remote], root);
        git(['remote', 'add', 'origin', remote], root);

        // 分支 other：tip 的 checker 被篡改为 exit 0 且历史含坏翻译
        git(['checkout', '-q', '-b', 'other'], root);
        fs.writeFileSync(path.join(root, 'scripts', 'i18n', 'check.mjs'),
            '#!/usr/bin/env node\nprocess.exit(0);\n', 'utf8');
        writeBundles(root, GOOD_ZH, BAD_EN);
        commitAll(root, 'tampered checker + bad translation', { bypass: true });

        // 停留在 master（HEAD 正常）推送 other → 必须失败：候选 checker 不能自我批准
        git(['checkout', '-q', 'master'], root);
        const push = git(['push', 'origin', 'other'], root, { allowFailure: true });
        assert.notEqual(push.status, 0, '篡改后的分支 checker 必须不能放行坏翻译');
        assert.match(push.stdout + push.stderr, /trusted gate/);
        assert.match(push.stdout + push.stderr, /does not pass the i18n gate|GATE CONTRACT FAILED/);
    } finally {
        cleanRepo(root);
        fs.rmSync(remote, { recursive: true, force: true });
    }
});

test('pre-push：candidate tip checker = exit(0) + 翻译合法 → candidate gate contract 失败', () => {
    if (!hasBash()) {
        test.skip('bash 不可用');
        return;
    }
    const root = makeGitRepo();
    const remote = path.join(os.tmpdir(), 'pixiv bare remote ' + Date.now());
    try {
        fs.mkdirSync(remote);
        git(['init', '-q', '--bare', remote], root);
        git(['remote', 'add', 'origin', remote], root);

        // 只把 checker 改成 exit 0（翻译全部合法）
        git(['checkout', '-q', '-b', 'noop'], root);
        fs.writeFileSync(path.join(root, 'scripts', 'i18n', 'check.mjs'),
            '#!/usr/bin/env node\nprocess.exit(0);\n', 'utf8');
        commitAll(root, 'noop checker only', { bypass: true });
        git(['checkout', '-q', 'master'], root);

        const push = git(['push', 'origin', 'noop'], root, { allowFailure: true });
        assert.notEqual(push.status, 0, 'no-op checker 的 gate proposal 必须被 trusted contract 拒绝');
        assert.match(push.stdout + push.stderr, /does not pass the trusted gate contract|GATE CONTRACT FAILED/);
    } finally {
        cleanRepo(root);
        fs.rmSync(remote, { recursive: true, force: true });
    }
});

test('pre-push：中间 commit 引入 no-op checker、tip 修复 → 历史降级仍必须失败', () => {
    if (!hasBash()) {
        test.skip('bash 不可用');
        return;
    }
    const root = makeGitRepo();
    const remote = path.join(os.tmpdir(), 'pixiv bare remote ' + Date.now());
    try {
        fs.mkdirSync(remote);
        git(['init', '-q', '--bare', remote], root);
        git(['remote', 'add', 'origin', remote], root);

        // commit1：no-op checker（翻译合法）；commit2：恢复真实 checker（tip 修复）
        git(['checkout', '-q', '-b', 'downgrade'], root);
        fs.writeFileSync(path.join(root, 'scripts', 'i18n', 'check.mjs'),
            '#!/usr/bin/env node\nprocess.exit(0);\n', 'utf8');
        commitAll(root, 'noop checker', { bypass: true });
        fs.writeFileSync(path.join(root, 'scripts', 'i18n', 'check.mjs'),
            fs.readFileSync(path.join(REPO_ROOT, 'scripts', 'i18n', 'check.mjs'), 'utf8'), 'utf8');
        commitAll(root, 'restore checker', { bypass: true });
        git(['checkout', '-q', 'master'], root);

        const push = git(['push', 'origin', 'downgrade'], root, { allowFailure: true });
        assert.notEqual(push.status, 0, '中间 commit 的 no-op checker 必须被 trusted contract 拦截（即使 tip 修复）');
        assert.match(push.stdout + push.stderr, /does not pass the trusted gate contract|GATE CONTRACT FAILED/);
    } finally {
        cleanRepo(root);
        fs.rmSync(remote, { recursive: true, force: true });
    }
});

test('pre-push：临时 remote refs 清理（成功与失败路径都不残留）', () => {
    if (!hasBash()) {
        test.skip('bash 不可用');
        return;
    }
    const root = makeGitRepo();
    const remote = path.join(os.tmpdir(), 'pixiv bare remote ' + Date.now());
    try {
        fs.mkdirSync(remote);
        git(['init', '-q', '--bare', remote], root);
        git(['remote', 'add', 'origin', remote], root);

        const okPush = git(['push', 'origin', 'master'], root, { allowFailure: true });
        assert.equal(okPush.status, 0, okPush.stdout + okPush.stderr);
        const refsAfterOk = git(['for-each-ref', '--format=%(refname)', 'refs/pixiv-i18n-prepush/'], root);
        assert.equal(refsAfterOk.stdout.trim(), '', '成功推送后不得残留临时 remote namespace refs');

        // 失败路径：坏翻译 commit → push 失败 → 同样清理
        writeBundles(root, GOOD_ZH, BAD_EN);
        commitAll(root, 'bad for cleanup test', { bypass: true });
        const badPush = git(['push', 'origin', 'master'], root, { allowFailure: true });
        assert.notEqual(badPush.status, 0);
        const refsAfterBad = git(['for-each-ref', '--format=%(refname)', 'refs/pixiv-i18n-prepush/'], root);
        assert.equal(refsAfterBad.stdout.trim(), '', '失败推送后不得残留临时 remote namespace refs');
    } finally {
        cleanRepo(root);
        fs.rmSync(remote, { recursive: true, force: true });
    }
});

/** 真实迁移前历史 fixture：A（无 locales.json）/ B（无 i18n checker）/ C（enforcement start，完整 i18n 无 policy）/ D（policy + 正常后续）。 */
function makeEnforcementRepo() {
    const dir = path.join(os.tmpdir(), 'pixiv enforcement repo ' + Date.now() + '-' + Math.random().toString(36).slice(2));
    fs.mkdirSync(dir, { recursive: true });
    git(['init', '-q'], dir);
    git(['config', 'user.email', 't@example.com'], dir);
    git(['config', 'user.name', 'test'], dir);
    git(['config', 'core.autocrlf', 'false'], dir);

    fs.writeFileSync(path.join(dir, 'README.md'), '# pre-enforcement\n', 'utf8');
    git(['add', '-A'], dir);
    git(['commit', '-q', '-m', 'A: no locales'], dir);

    fs.mkdirSync(path.join(dir, 'pixivdownload-app', 'src', 'main', 'java'), { recursive: true });
    fs.writeFileSync(path.join(dir, 'pixivdownload-app', 'src', 'main', 'java', 'App.java'), 'class App {}\n', 'utf8');
    git(['add', '-A'], dir);
    git(['commit', '-q', '-m', 'B: no i18n checker'], dir);

    // C：enforcement start —— 完整 i18n gate bundle（无 policy）
    fs.cpSync(path.join(REPO_ROOT, 'scripts', 'i18n'), path.join(dir, 'scripts', 'i18n'), { recursive: true });
    fs.rmSync(path.join(dir, 'scripts', 'i18n', 'test'), { recursive: true, force: true });
    fs.rmSync(path.join(dir, 'scripts', 'i18n', 'gate-policy.json'), { force: true });
    fs.cpSync(path.join(REPO_ROOT, 'scripts', 'hooks'), path.join(dir, 'scripts', 'hooks'), { recursive: true });
    const i18nDir = path.join(dir, APP_I18N);
    fs.mkdirSync(path.join(i18nDir, 'web'), { recursive: true });
    fs.writeFileSync(path.join(i18nDir, 'locales.json'), CATALOG, 'utf8');
    writeBundles(dir, GOOD_ZH, GOOD_EN);
    const bootstrap = runAcceptCore(dir, { bootstrap: true });
    if (!bootstrap.ok) {
        throw new Error('fixture bootstrap failed: ' + bootstrap.refused.join('\n'));
    }
    runGenerate(dir);
    git(['add', '--chmod=+x', 'scripts/hooks/pre-commit', 'scripts/hooks/pre-push', 'scripts/hooks/pre-push-guard.sh', 'scripts/hooks/execfile-shim.cjs'], dir);
    git(['add', '-A'], dir);
    git(['commit', '-q', '-m', 'C: enforcement start'], dir);
    const start = git(['rev-parse', 'HEAD'], dir).stdout.trim();

    // D：policy（start = C）+ 正常后续提交
    const policy = JSON.parse(fs.readFileSync(path.join(REPO_ROOT, 'scripts', 'i18n', 'gate-policy.json'), 'utf8'));
    policy.i18nEnforcementStartCommit = start;
    fs.writeFileSync(path.join(dir, 'scripts', 'i18n', 'gate-policy.json'),
        JSON.stringify(policy, null, 2) + '\n', 'utf8');
    git(['add', '-A'], dir);
    git(['commit', '-q', '-m', 'D: policy + follow-up'], dir);
    const anchor = git(['rev-parse', 'HEAD'], dir).stdout.trim();
    git(['config', '--local', 'core.hooksPath', 'scripts/hooks'], dir);
    git(['config', '--local', 'pixiv.i18n.trustedGateRef', anchor], dir);
    return dir;
}

test('pre-push：空远端 + enforcement 前历史 —— A/B 不执行新 i18n check，C/D 执行；tag 豁免；branch 缺 root fail closed', () => {
    if (!hasBash()) {
        test.skip('bash 不可用');
        return;
    }
    const root = makeEnforcementRepo();
    const remote = path.join(os.tmpdir(), 'pixiv bare remote ' + Date.now());
    try {
        fs.mkdirSync(remote);
        git(['init', '-q', '--bare', remote], root);
        git(['remote', 'add', 'origin', remote], root);

        // 推送整个仓库到空远端：A/B 不因缺 locales.json 失败，C/D 通过
        const first = git(['push', 'origin', 'master'], root, { allowFailure: true });
        assert.equal(first.status, 0, 'enforcement 前历史 + 完整 i18n 的推送必须通过: ' + first.stdout + first.stderr);

        // D 后续坏翻译 → 失败
        writeBundles(root, GOOD_ZH, BAD_EN);
        commitAll(root, 'bad translation after enforcement', { bypass: true });
        const bad = git(['push', 'origin', 'master'], root, { allowFailure: true });
        assert.notEqual(bad.status, 0, 'enforcement 后的坏翻译必须被拦截');
        assert.match(bad.stdout + bad.stderr, /does not pass the i18n gate/);
        git(['reset', '-q', '--hard', 'HEAD~1'], root);

        // 指向 enforcement 前历史的 tag：豁免 i18n gate，guard 仍执行 → 通过
        git(['tag', 'pre-enforcement-tag', 'HEAD~2'], root); // B
        const tagPush = git(['push', 'origin', 'pre-enforcement-tag'], root, { allowFailure: true });
        assert.equal(tagPush.status, 0, 'pre-enforcement tag 必须豁免 i18n gate: ' + tagPush.stdout + tagPush.stderr);
        assert.match(tagPush.stdout + tagPush.stderr, /pre-enforcement history|signature guard/);

        // 分支历史不包含 enforcement root → fail closed
        git(['branch', 'legacy', 'HEAD~2'], root); // B
        const legacyPush = git(['push', 'origin', 'legacy'], root, { allowFailure: true });
        assert.notEqual(legacyPush.status, 0, '不含 enforcement root 的普通分支必须 fail closed');
        assert.match(legacyPush.stdout + legacyPush.stderr, /does not include the i18n enforcement root/);
    } finally {
        cleanRepo(root);
        fs.rmSync(remote, { recursive: true, force: true });
    }
});

test('pre-push：stale tracking ref —— 本地 origin/old 含坏 commit A、远端已删除；新分支再次包含 A → 必须被检查', () => {
    if (!hasBash()) {
        test.skip('bash 不可用');
        return;
    }
    const root = makeGitRepo();
    const remote = path.join(os.tmpdir(), 'pixiv bare remote ' + Date.now());
    const helper = path.join(os.tmpdir(), 'pixiv helper clone ' + Date.now());
    try {
        fs.mkdirSync(remote);
        git(['init', '-q', '--bare', remote], root);
        git(['remote', 'add', 'origin', remote], root);
        const seed = git(['push', 'origin', 'master'], root, { allowFailure: true });
        assert.equal(seed.status, 0, seed.stdout + seed.stderr);

        // 坏 commit A 先经「远端机器」进入 origin 的 old 分支（无 hooks）
        writeBundles(root, GOOD_ZH, BAD_EN);
        commitAll(root, 'A bad', { bypass: true });
        const aSha = git(['rev-parse', 'HEAD'], root).stdout.trim();
        const seedOld = git(['-c', 'core.hooksPath=/dev/null', 'push', 'origin', 'HEAD:refs/heads/old'], root, { allowFailure: true });
        assert.equal(seedOld.status, 0, seedOld.stdout + seedOld.stderr);
        git(['fetch', '-q', 'origin'], root); // 本地 tracking ref = A

        // 远端侧（辅助 clone，无 hooks 配置）删除 old —— 不影响本地的 stale tracking ref
        git(['clone', '-q', remote, helper], root);
        git(['config', 'user.email', 't@example.com'], helper);
        git(['config', 'user.name', 'test'], helper);
        const deleteOld = git(['push', 'origin', '--delete', 'old'], helper, { allowFailure: true });
        assert.equal(deleteOld.status, 0, deleteOld.stdout + deleteOld.stderr);
        // 本地 tracking ref 仍指向 A（从未 fetch 删除，push --delete 在本地仓库执行会顺带清理 tracking，
        // 因此远端侧操作必须在辅助 clone 中完成）
        assert.equal(git(['for-each-ref', '--format=%(objectname)', 'refs/remotes/origin/old'], root).stdout.trim(), aSha);

        // 新分支 feature 从 A 出发（再次包含 A）→ 推 origin → A 必须被检查并阻止
        git(['checkout', '-q', '-b', 'feature', aSha], root);
        const push = git(['push', 'origin', 'feature'], root, { allowFailure: true });
        assert.notEqual(push.status, 0, 'stale tracking ref 不得让 A 漏检');
        assert.match(push.stdout + push.stderr, /does not pass the i18n gate/);
    } finally {
        cleanRepo(root);
        cleanRepo(helper);
        fs.rmSync(remote, { recursive: true, force: true });
    }
});

test('pre-push：远端强制回退 —— 本地 tracking 指向已回退的旧 tip，新分支重推坏 commit → 必须被检查', () => {
    if (!hasBash()) {
        test.skip('bash 不可用');
        return;
    }
    const root = makeGitRepo();
    const remote = path.join(os.tmpdir(), 'pixiv bare remote ' + Date.now());
    const helper = path.join(os.tmpdir(), 'pixiv helper clone ' + Date.now());
    try {
        fs.mkdirSync(remote);
        git(['init', '-q', '--bare', remote], root);
        git(['remote', 'add', 'origin', remote], root);
        const seed = git(['push', 'origin', 'master'], root, { allowFailure: true });
        assert.equal(seed.status, 0, seed.stdout + seed.stderr);

        // 坏 commit A 进入 origin/master（无 hooks），本地 fetch 建立 tracking
        writeBundles(root, GOOD_ZH, BAD_EN);
        commitAll(root, 'A bad', { bypass: true });
        const seedA = git(['-c', 'core.hooksPath=/dev/null', 'push', 'origin', 'master'], root, { allowFailure: true });
        assert.equal(seedA.status, 0, seedA.stdout + seedA.stderr);
        git(['fetch', '-q', 'origin'], root);
        const aSha = git(['rev-parse', 'HEAD'], root).stdout.trim();

        // 远端侧（辅助 clone）强制回退 master（A 不再可达）；本地 tracking 保持指向 A
        git(['clone', '-q', remote, helper], root);
        git(['config', 'user.email', 't@example.com'], helper);
        git(['config', 'user.name', 'test'], helper);
        const rewind = git(['push', 'origin', 'HEAD~1:refs/heads/master', '--force'], helper, { allowFailure: true });
        assert.equal(rewind.status, 0, rewind.stdout + rewind.stderr);
        assert.equal(git(['for-each-ref', '--format=%(objectname)', 'refs/remotes/origin/master'], root).stdout.trim(), aSha);

        // 新分支（基于含 A 的历史）重推 → A 必须被检查并阻止
        git(['checkout', '-q', '-b', 'replay', aSha], root);
        const push = git(['push', 'origin', 'replay'], root, { allowFailure: true });
        assert.notEqual(push.status, 0, '远端强制回退后旧 tracking 不得让 A 漏检');
        assert.match(push.stdout + push.stderr, /does not pass the i18n gate/);
    } finally {
        cleanRepo(root);
        cleanRepo(helper);
        fs.rmSync(remote, { recursive: true, force: true });
    }
});

test('Windows 路径含空格：hooks 与快照物化均可用', () => {
    if (!hasBash()) {
        test.skip('bash 不可用');
        return;
    }
    const base = path.join(os.tmpdir(), 'pixiv space dir ' + Date.now());
    fs.mkdirSync(base, { recursive: true });
    const root = makeGitRepo(base);
    try {
        const result = bash(['scripts/hooks/pre-commit'], root);
        assert.equal(result.status, 0, '含空格路径下 pre-commit 必须可用: ' + result.stdout + result.stderr);
        // 工作树改坏 → index 检查仍通过
        writeBundles(root, GOOD_ZH, BAD_EN);
        const index = spawnSync('node',
            [path.join(root, 'scripts', 'i18n', 'check.mjs'), '--snapshot', 'index'],
            { cwd: root, encoding: 'utf8' });
        assert.equal(index.status, 0, '含空格路径下 index 快照必须可用');
    } finally {
        cleanRepo(root);
        fs.rmSync(base, { recursive: true, force: true });
    }
});

test('pre-commit：暂存 workflow 删除关键 job → 必须失败', () => {
    if (!hasBash()) {
        test.skip('bash 不可用');
        return;
    }
    const root = makeGitRepo(os.tmpdir(), { fullGate: true });
    try {
        const workflowPath = path.join(root, '.github', 'workflows', 'quality-gate.yml');
                const YAML = createRequire(path.join(REPO_ROOT, 'package.json'))('yaml');
        const doc = YAML.parse(fs.readFileSync(workflowPath, 'utf8'));
        delete doc.jobs['java-tests'];
        fs.writeFileSync(workflowPath, YAML.stringify(doc), 'utf8');
        git(['add', '-A'], root);
        const result = bash(['scripts/hooks/pre-commit'], root);
        assert.notEqual(result.status, 0, '删除关键 job 的 workflow 必须被 trusted contract 拒绝');
        assert.match(result.stdout + result.stderr, /GATE CONTRACT FAILED|job java-tests|workflow/);
    } finally {
        cleanRepo(root);
    }
});

test('pre-commit：暂存 workflow 关键命令改为 true → 必须失败', () => {
    if (!hasBash()) {
        test.skip('bash 不可用');
        return;
    }
    const root = makeGitRepo(os.tmpdir(), { fullGate: true });
    try {
        const workflowPath = path.join(root, '.github', 'workflows', 'quality-gate.yml');
                const YAML = createRequire(path.join(REPO_ROOT, 'package.json'))('yaml');
        const doc = YAML.parse(fs.readFileSync(workflowPath, 'utf8'));
        const testsStep = doc.jobs['i18n-check'].steps.find((s) => typeof s.run === 'string'
            && s.run.includes('npm run test:i18n'));
        testsStep.run = 'true';
        fs.writeFileSync(workflowPath, YAML.stringify(doc), 'utf8');
        git(['add', '-A'], root);
        const result = bash(['scripts/hooks/pre-commit'], root);
        assert.notEqual(result.status, 0, '关键命令改为 true 的 workflow 必须被 trusted contract 拒绝');
        assert.match(result.stdout + result.stderr, /GATE CONTRACT FAILED|echo \/ true|test:i18n/);
    } finally {
        cleanRepo(root);
    }
});

test('pre-commit：暂存 package.json test:i18n = true → 必须失败', () => {
    if (!hasBash()) {
        test.skip('bash 不可用');
        return;
    }
    const root = makeGitRepo(os.tmpdir(), { fullGate: true });
    try {
        const pkgPath = path.join(root, 'package.json');
        const pkg = JSON.parse(fs.readFileSync(pkgPath, 'utf8'));
        pkg.scripts['test:i18n'] = 'true';
        fs.writeFileSync(pkgPath, JSON.stringify(pkg, null, 2) + '\n', 'utf8');
        git(['add', '-A'], root);
        const result = bash(['scripts/hooks/pre-commit'], root);
        assert.notEqual(result.status, 0, 'test:i18n = true 必须被 trusted contract 拒绝');
        assert.match(result.stdout + result.stderr, /GATE CONTRACT FAILED|test:i18n/);
    } finally {
        cleanRepo(root);
    }
});

test('pre-push：protected master —— 远端 master 存在时 force push 回退 → 必须失败', () => {
    if (!hasBash()) {
        test.skip('bash 不可用');
        return;
    }
    const root = makeGitRepo();
    const remote = path.join(os.tmpdir(), 'pixiv bare remote ' + Date.now());
    try {
        fs.mkdirSync(remote);
        git(['init', '-q', '--bare', remote], root);
        git(['remote', 'add', 'origin', remote], root);
        const seed = git(['push', 'origin', 'master'], root, { allowFailure: true });
        assert.equal(seed.status, 0, seed.stdout + seed.stderr);
        const remoteTip = git(['rev-parse', 'HEAD'], root).stdout.trim();

        // 本地重写历史到 enforcement 之前（模拟 force push 回退到旧 gate commit）
        git(['reset', '-q', '--hard', 'HEAD~1'], root);
        const oldSha = git(['rev-parse', 'HEAD'], root).stdout.trim();
        assert.notEqual(oldSha, remoteTip);

        const forced = git(['push', 'origin', 'master', '--force'], root, { allowFailure: true });
        assert.notEqual(forced.status, 0, 'protected master force rewind 必须被 pre-push 拒绝');
        assert.match(forced.stdout + forced.stderr, /protected|refusing|trusted gate anchor/);

        // 远端必须没有任何更新（仍指向原 tip）
        const lsRemote = git(['ls-remote', remote], root).stdout;
        assert.ok(lsRemote.includes(remoteTip + '\trefs/heads/master'),
            '远端 master 必须保持原 tip: ' + lsRemote);
    } finally {
        cleanRepo(root);
        fs.rmSync(remote, { recursive: true, force: true });
    }
});

test('pre-push：protected master —— tip 不包含 trusted anchor（旧 gate commit）→ 必须失败', () => {
    if (!hasBash()) {
        test.skip('bash 不可用');
        return;
    }
    const root = makeGitRepo();
    const remote = path.join(os.tmpdir(), 'pixiv bare remote ' + Date.now());
    try {
        fs.mkdirSync(remote);
        git(['init', '-q', '--bare', remote], root);
        git(['remote', 'add', 'origin', remote], root);

        // 远端 master = enforcement start（C1）：不含 trusted anchor（C2）
        const c1 = git(['rev-parse', 'HEAD~1'], root).stdout.trim();
        const seed = git(['-c', 'core.hooksPath=/dev/null', 'push', 'origin', c1 + ':refs/heads/master'], root, { allowFailure: true });
        assert.equal(seed.status, 0, seed.stdout + seed.stderr);

        // 在 C1 之上提交 X（X 是 C1 后代、但历史不包含 trusted anchor C2）→ 推送必须被拒绝
        git(['reset', '-q', '--hard', 'HEAD~1'], root);
        fs.writeFileSync(path.join(root, 'extra.txt'), 'extra\n', 'utf8');
        commitAll(root, 'commit without anchor', { bypass: true });
        const pushed = git(['push', 'origin', 'master'], root, { allowFailure: true });
        assert.notEqual(pushed.status, 0, 'tip 不含 trusted anchor 的 master 推送必须失败');
        assert.match(pushed.stdout + pushed.stderr, /protected|trusted gate anchor|refusing/);
    } finally {
        cleanRepo(root);
        fs.rmSync(remote, { recursive: true, force: true });
    }
});

test('pre-push：final tip 始终检查 —— 非保护分支 force push 重写坏 tip（range 为空）→ 必须失败', () => {
    if (!hasBash()) {
        test.skip('bash 不可用');
        return;
    }
    const root = makeGitRepo();
    const remote = path.join(os.tmpdir(), 'pixiv bare remote ' + Date.now());
    try {
        fs.mkdirSync(remote);
        git(['init', '-q', '--bare', remote], root);
        git(['remote', 'add', 'origin', remote], root);

        // 非保护分支 feature 先推送合法提交 G（远端 tip = G）
        git(['checkout', '-q', '-b', 'feature'], root);
        const seed = git(['push', 'origin', 'feature'], root, { allowFailure: true });
        assert.equal(seed.status, 0, seed.stdout + seed.stderr);
        const g = git(['rev-parse', 'HEAD'], root).stdout.trim();

        // 重写历史：回到 G 的父提交，写入坏翻译 B 并 force push。
        // range = G..B 为空（B 不是 G 的后代），但 final tip B 必须仍被检查。
        git(['reset', '-q', '--hard', 'HEAD~1'], root);
        writeBundles(root, GOOD_ZH, BAD_EN);
        commitAll(root, 'rewritten bad tip', { bypass: true });
        const b = git(['rev-parse', 'HEAD'], root).stdout.trim();
        assert.equal(git(['merge-base', '--is-ancestor', g, b], root, { allowFailure: true }).status, 1,
            '测试前提：B 不是 G 的后代（range 为空）');

        const forced = git(['push', 'origin', 'feature', '--force'], root, { allowFailure: true });
        assert.notEqual(forced.status, 0, 'range 为空也必须检查 final tip（坏 tip 必须被拦截）');
        assert.match(forced.stdout + forced.stderr, /does not pass the i18n gate/);

        // 合法重写（tip 合法）→ 非保护分支允许 force push
        git(['reset', '-q', '--hard', 'HEAD~1'], root);
        fs.writeFileSync(path.join(root, APP_I18N, 'web', 'common.properties'),
            GOOD_ZH + 'status=状态\n', 'utf8');
        fs.writeFileSync(path.join(root, APP_I18N, 'web', 'common_en.properties'),
            GOOD_EN + 'status=Status\n', 'utf8');
        runGenerate(root);
        const accept = runAcceptCore(root, { locale: 'en-US' });
        assert.equal(accept.ok, true, accept.refused.join('\n'));
        commitAll(root, 'rewritten good tip', { bypass: true });
        const okPush = git(['push', 'origin', 'feature', '--force'], root, { allowFailure: true });
        assert.equal(okPush.status, 0, '非保护分支的合法 force push 必须通过: ' + okPush.stdout + okPush.stderr);
        assert.match(okPush.stdout + okPush.stderr, /all \d+ pushed commit/);
    } finally {
        cleanRepo(root);
        fs.rmSync(remote, { recursive: true, force: true });
    }
});

test('install-hooks 幂等且只写 local 配置；doctor 校验 executable mode', () => {
    const root = makeGitRepo();
    try {
        // 已配置 hooksPath，重复安装幂等
        const run = () => spawnSync('node',
            [path.join(SCRIPTS_DIR, 'install-hooks.mjs')], { cwd: root, encoding: 'utf8' });
        const first = run();
        assert.equal(first.status, 0, first.stdout + first.stderr);
        assert.equal(git(['config', '--local', '--get', 'core.hooksPath'], root).stdout.trim(), 'scripts/hooks');
        const second = run();
        assert.equal(second.status, 0);

        // doctor：hooks 已提交（makeGitRepo 初始提交）且带 executable bit → 通过
        const doctor = spawnSync('node',
            [path.join(SCRIPTS_DIR, 'doctor-hooks.mjs')], { cwd: root, encoding: 'utf8' });
        assert.equal(doctor.status, 0, doctor.stdout + doctor.stderr);

        // 破坏 executable bit → doctor 失败
        git(['update-index', '--chmod=-x', 'scripts/hooks/pre-commit'], root);
        const doctorBad = spawnSync('node',
            [path.join(SCRIPTS_DIR, 'doctor-hooks.mjs')], { cwd: root, encoding: 'utf8' });
        assert.notEqual(doctorBad.status, 0);
        assert.match(doctorBad.stdout + doctorBad.stderr, /executable/);
        git(['update-index', '--chmod=+x', 'scripts/hooks/pre-commit'], root);
    } finally {
        cleanRepo(root);
    }
});

test('stale-lock：确定性排序、原子写、hash 空白敏感', () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'i18n-lock-'));
    try {
        const entries = [
            { locale: 'en-US', module: 'm', baseName: 'b', key: 'z', acceptedSourceHash: 'a'.repeat(64), acceptedTranslationHash: 'b'.repeat(64) },
            { locale: 'en-US', module: 'm', baseName: 'b', key: 'a', acceptedSourceHash: 'c'.repeat(64), acceptedTranslationHash: 'd'.repeat(64) },
        ];
        staleLock.save(root, { version: 1, entries });
        const loaded = staleLock.load(root);
        assert.equal(loaded.entries.length, 2);
        assert.equal(loaded.entries[0].key, 'a');
        assert.equal(loaded.entries[1].key, 'z');

        // hash 空白敏感（不 trim），仅 CRLF 归一化
        assert.notEqual(staleLock.hashValue(' x\r\ny '), staleLock.hashValue('x\ny'));
        assert.equal(staleLock.hashValue('x\r\ny'), staleLock.hashValue('x\ny'));

        // 原子写：临时文件不残留
        assert.equal(fs.readdirSync(path.join(root, 'i18n')).filter((f) => f.includes('.tmp-')).length, 0);
    } finally {
        fs.rmSync(root, { recursive: true, force: true });
    }
});
