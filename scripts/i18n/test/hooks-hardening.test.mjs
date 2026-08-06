'use strict';
/**
 * i18n 门禁加固回归测试（第二批）：
 * - accept：核心库 runAcceptCore 与 CLI 边界策略分离；CI=true 拒绝危险参数；
 * - disabled 语言拒绝 accept / 不进 bootstrap / prune 清理历史条目；
 * - --bootstrap --force 重建完整基线（不保留 orphan / candidate / disabled / 已删 key / 未知 locale）；
 * - pre-commit 不可变 checker：工作树 / 暂存 checker 篡改都不改变 HEAD checker 判定；
 * - pre-push：双远端新分支漏检、checker 来自待推送 ref tip、tag / force push / 多 ref 去重、
 *   remote SHA 不在本地、失败输出包含真实诊断、无 tar / 无 --remotes=*；
 * - doctor：Windows 平台 bash 检查（存在 / 缺失 / 语法错误）；
 * - docs：tracked Markdown 相对链接目标存在；hooks 不引用不存在的本地文档；
 * - snapshot-cli：materialize-index / materialize-ref / materialize-paths。
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { runGenerate } from '../generate-static.mjs';
import { runAcceptCore, validateCliPolicy } from '../accept.mjs';
import staleLock from '../lib/stale-lock.mjs';

const SCRIPTS_DIR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const REPO_ROOT = path.resolve(SCRIPTS_DIR, '..', '..');

const CATALOG = `{
  "schemaVersion": 1,
  "sourceLocale": "zh-CN",
  "defaultLocale": "en-US",
  "fallbackLocale": "en-US",
  "languageCookieName": "pixiv_lang",
  "languageParameterName": "lang",
  "locales": [
    {"tag": "zh-CN", "nativeName": "简体中文", "resourceSuffix": "", "status": "source", "direction": "ltr", "aliases": ["zh", "zh-Hans"]},
    {"tag": "en-US", "nativeName": "English", "resourceSuffix": "en", "status": "supported", "direction": "ltr", "aliases": ["en"]},
    {"tag": "ja-JP", "nativeName": "日本語", "resourceSuffix": "ja", "status": "candidate", "direction": "ltr", "aliases": ["ja"]},
    {"tag": "es-ES", "nativeName": "Español", "resourceSuffix": "es", "status": "disabled", "direction": "ltr", "aliases": ["es"]}
  ]
}`;

const APP_I18N = path.join('pixivdownload-app', 'src', 'main', 'resources', 'i18n');
const GOOD_ZH = 'greeting=你好 {name}\ntitle=作品标题\n';
const GOOD_EN = 'greeting=Hello {name}\ntitle=Artwork title\n';
const BAD_EN = 'greeting=Hello {wrong}\ntitle=Artwork title\n';
const GOOD_JA = 'greeting=こんにちは {name}\ntitle=タイトル\n';

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

/** 与 hooks.test.mjs 同构的临时仓库夹具。 */
function makeGitRepo(base = os.tmpdir()) {
    const dir = path.join(base, 'pixiv harden repo ' + Date.now() + '-' + Math.random().toString(36).slice(2));
    fs.mkdirSync(dir, { recursive: true });
    git(['init', '-q'], dir);
    git(['config', 'user.email', 't@example.com'], dir);
    git(['config', 'user.name', 'test'], dir);
    git(['config', 'core.autocrlf', 'false'], dir);
    fs.cpSync(path.join(REPO_ROOT, 'scripts', 'i18n'), path.join(dir, 'scripts', 'i18n'), { recursive: true });
    fs.rmSync(path.join(dir, 'scripts', 'i18n', 'test'), { recursive: true, force: true });
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
    git(['add', '--chmod=+x', 'scripts/hooks/pre-commit', 'scripts/hooks/pre-push', 'scripts/hooks/pre-push-guard.sh'], dir);
    git(['add', '-A'], dir);
    git(['commit', '-q', '-m', 'init'], dir);
    git(['config', '--local', 'core.hooksPath', 'scripts/hooks'], dir);
    return dir;
}

function writeBundles(root, zh, en) {
    const i18nDir = path.join(root, APP_I18N);
    fs.writeFileSync(path.join(i18nDir, 'web', 'common.properties'), zh, 'utf8');
    fs.writeFileSync(path.join(i18nDir, 'web', 'common_en.properties'), en, 'utf8');
    runGenerate(root);
}

function writeExtra(root, rel, content) {
    fs.writeFileSync(path.join(root, APP_I18N, 'web', rel), content, 'utf8');
    runGenerate(root);
}

function commitAll(root, message, opts = {}) {
    git(['add', '-A'], root);
    if (opts.bypass) {
        git(['-c', 'core.hooksPath=/dev/null', 'commit', '-q', '-m', message], root);
    } else {
        git(['commit', '-q', '-m', message], root);
    }
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

function fixtureLockEntries(root) {
    return staleLock.load(root).entries;
}

// ---------------------------------------------------------------------------
// accept：核心 / CLI 边界
// ---------------------------------------------------------------------------

test('CLI CI=true：拒绝 bootstrap / allow-unchanged / force；普通 i18n:check 不受影响；环境无污染', () => {
    const previousCI = process.env.CI;
    const root = makeGitRepo();
    const env = { ...process.env, CI: 'true' };
    try {
        for (const args of [['--bootstrap'], ['--locale', 'en-US', '--allow-unchanged'], ['--bootstrap', '--force']]) {
            const run = spawnSync('node', [path.join(SCRIPTS_DIR, 'accept.mjs'), ...args],
                { cwd: root, encoding: 'utf8', env });
            assert.notEqual(run.status, 0, 'CI=true 必须拒绝: ' + args.join(' '));
            assert.match(run.stderr, /refusing/);
        }
        // 普通检查不受 CI 影响
        const check = spawnSync('node', [path.join(SCRIPTS_DIR, 'check.mjs'), '--snapshot', 'index'],
            { cwd: root, encoding: 'utf8', env });
        assert.equal(check.status, 0, check.stdout + check.stderr);
        // 核心库不接受任何安全参数组合：CI=false 也不得放行危险模式（由 CLI 边界判定，不依赖进程内状态）
        assert.throws(() => validateCliPolicy({ bootstrap: true }, { isCI: true }), /refusing/);
        assert.throws(() => validateCliPolicy({ allowUnchanged: true }, { isCI: '1' }), /refusing/);
        assert.throws(() => validateCliPolicy({ force: true }, { isCI: 'true' }), /refusing/);
        validateCliPolicy({ bootstrap: true }, { isCI: false });
    } finally {
        cleanRepo(root);
    }
    // 测试结束后环境无污染：本测试进程从未全局修改 process.env.CI（含 CI=true 的 CI 环境）
    assert.equal(process.env.CI, previousCI);
});

test('核心库 runAcceptCore：CI=true 环境变量存在时 bootstrap 仍可用（不读全局 env）', () => {
    const root = makeGitRepo();
    const previous = process.env.CI;
    try {
        process.env.CI = 'true';
        // makeGitRepo 已建立初始锁：核心库路径下 force bootstrap 属于状态机操作，不受 CI 环境影响
        const result = runAcceptCore(root, { bootstrap: true, force: true });
        assert.equal(result.ok, true, result.refused.join('\n'));
        assert.ok(fixtureLockEntries(root).length > 0);
    } finally {
        if (previous === undefined) {
            delete process.env.CI;
        } else {
            process.env.CI = previous;
        }
        cleanRepo(root);
    }
    assert.equal(process.env.CI, previous);
});

test('disabled：普通 accept / --key accept 被拒绝；不进入 bootstrap；prune 清理历史条目；candidate 仍可 accept', () => {
    const root = makeGitRepo();
    try {
        writeExtra(root, 'common_es.properties', 'greeting=Hola {name}\ntitle=Título\n');
        assert.throws(() => runAcceptCore(root, { locale: 'es-ES' }),
            /disabled locale cannot be accepted because it is outside translation coverage and review/);
        assert.throws(() => runAcceptCore(root, { locale: 'es-ES', key: 'greeting' }), /disabled/);

        // bootstrap（force 重建）只处理 supported：锁里没有 es-ES / ja-JP 条目
        const boot = runAcceptCore(root, { bootstrap: true, force: true });
        assert.equal(boot.ok, true, boot.refused.join('\n'));
        const locales = new Set(fixtureLockEntries(root).map((e) => e.locale));
        assert.ok(locales.has('en-US'));
        assert.ok(!locales.has('es-ES'));
        assert.ok(!locales.has('ja-JP'));

        // candidate accept 仍允许
        writeExtra(root, 'common_ja.properties', GOOD_JA);
        const candidate = runAcceptCore(root, { locale: 'ja-JP' });
        assert.equal(candidate.ok, true);
        assert.ok(fixtureLockEntries(root).some((e) => e.locale === 'ja-JP'));

        // 旧锁中塞入 disabled 历史条目 → prune 删除
        const lock = staleLock.load(root);
        lock.entries.push({
            locale: 'es-ES', module: 'pixivdownload-app', baseName: 'web/common',
            key: 'title', acceptedSourceHash: '0'.repeat(64), acceptedTranslationHash: '0'.repeat(64),
        });
        staleLock.save(root, lock);
        const pruned = runAcceptCore(root, { prune: true });
        assert.equal(pruned.ok, true);
        assert.match(pruned.messages[0], /pruned 1/);
        assert.ok(!fixtureLockEntries(root).some((e) => e.locale === 'es-ES'));
    } finally {
        cleanRepo(root);
    }
});

test('--bootstrap --force：重建完整基线，只保留当前 supported 的当前 key', async () => {
    const root = makeGitRepo();
    try {
        // 1) 重建基线（supported en-US；candidate ja 已 accept）
        writeExtra(root, 'common_ja.properties', GOOD_JA);
        assert.equal(runAcceptCore(root, { bootstrap: true, force: true }).ok, true);
        assert.equal(runAcceptCore(root, { locale: 'ja-JP' }).ok, true);

        // 2) 演化：删除 key、新增 key、源文本变化
        writeBundles(root,
            'greeting=你好呀 {name}\nstatus=状态\n',
            'greeting=Hello there {name}\nstatus=Status\n');
        writeExtra(root, 'common_es.properties', 'greeting=Hola {name}\nstatus=Estado\n');
        writeExtra(root, 'common_ja.properties', 'greeting=こんにちは {name}\nstatus=ステータス\n');
        const accept = runAcceptCore(root, { locale: 'en-US' });
        assert.equal(accept.ok, true, accept.refused.join('\n'));

        // 3) 往锁里塞历史垃圾：已删 key 的旧条目（title 仍在）、candidate、disabled、未知 locale、orphan
        const lock = staleLock.load(root);
        lock.entries.push(
            { locale: 'ja-JP', module: 'pixivdownload-app', baseName: 'web/common', key: 'orphan.ja',
                acceptedSourceHash: '2'.repeat(64), acceptedTranslationHash: '2'.repeat(64) },
            { locale: 'es-ES', module: 'pixivdownload-app', baseName: 'web/common', key: 'greeting',
                acceptedSourceHash: '3'.repeat(64), acceptedTranslationHash: '3'.repeat(64) },
            { locale: 'fr-FR', module: 'pixivdownload-app', baseName: 'web/common', key: 'greeting',
                acceptedSourceHash: '4'.repeat(64), acceptedTranslationHash: '4'.repeat(64) },
            { locale: 'en-US', module: 'pixivdownload-app', baseName: 'web/common', key: 'deleted.old',
                acceptedSourceHash: '5'.repeat(64), acceptedTranslationHash: '5'.repeat(64) });
        staleLock.save(root, lock);
        assert.ok(fixtureLockEntries(root).length >= 6);

        // 4) force bootstrap：重建
        const forced = runAcceptCore(root, { bootstrap: true, force: true });
        assert.equal(forced.ok, true, forced.refused.join('\n'));
        assert.match(forced.messages.join(' '), /rebuilt/);
        const entries = fixtureLockEntries(root);
        const expected = new Set(['en-US/pixivdownload-app/web/common/greeting', 'en-US/pixivdownload-app/web/common/status']);
        assert.deepEqual(
            new Set(entries.map((e) => e.locale + '/' + e.module + '/' + e.baseName + '/' + e.key)),
            expected, '只保留当前 supported 的当前 key');

        // 5) 重建后 check 通过
        const { runCheck } = await import('../check.mjs');
        const report = runCheck(root);
        assert.equal(report.issues.filter((i) => i.severity === 'error').length, 0);

        // 6) force + 过滤器拒绝
        const refused = runAcceptCore(root, { bootstrap: true, force: true, module: 'pixivdownload-app' });
        assert.equal(refused.ok, false);
        assert.match(refused.refused[0], /cannot be combined/);
    } finally {
        cleanRepo(root);
    }
});

// ---------------------------------------------------------------------------
// pre-commit：不可变 checker
// ---------------------------------------------------------------------------

function tamperChecker(root, body) {
    fs.writeFileSync(path.join(root, 'scripts', 'i18n', 'check.mjs'), body, 'utf8');
}

const EXIT_ZERO_CHECKER = '#!/usr/bin/env node\nprocess.exit(0);\n';

test('pre-commit：index 坏翻译 + 工作树 checker 篡改为 exit 0 → 必须失败（HEAD checker 判定）', () => {
    if (!hasBash()) {
        test.skip('bash 不可用');
        return;
    }
    const root = makeGitRepo();
    try {
        writeBundles(root, GOOD_ZH, BAD_EN);
        git(['add', '-A'], root);
        // 篡改工作树 check.mjs（不暂存）——不得改变判定
        tamperChecker(root, EXIT_ZERO_CHECKER);
        const result = bash(['scripts/hooks/pre-commit'], root);
        assert.notEqual(result.status, 0, 'index 是坏翻译，工作树篡改 checker 不得放行');
        assert.match(result.stdout + result.stderr, /trusted HEAD checker/);
        assert.match(result.stdout + result.stderr, /I18N CHECK FAILED|FAILED/);
        // 工作树篡改仍在（pre-commit 不得修改文件）
        assert.equal(fs.readFileSync(path.join(root, 'scripts', 'i18n', 'check.mjs'), 'utf8'), EXIT_ZERO_CHECKER);
    } finally {
        cleanRepo(root);
    }
});

test('pre-commit：index 暂存 exit-0 checker + 坏翻译 → HEAD checker 仍然失败', () => {
    if (!hasBash()) {
        test.skip('bash 不可用');
        return;
    }
    const root = makeGitRepo();
    try {
        tamperChecker(root, EXIT_ZERO_CHECKER);
        writeBundles(root, GOOD_ZH, BAD_EN);
        git(['add', '-A'], root); // 坏翻译 + 篡改 checker 一起暂存
        const result = bash(['scripts/hooks/pre-commit'], root);
        assert.notEqual(result.status, 0, '暂存 exit-0 checker 不得让坏翻译通过');
        assert.match(result.stdout + result.stderr, /trusted HEAD checker/);
    } finally {
        cleanRepo(root);
    }
});

test('pre-commit：HEAD checker 与 index checker 都正常 → 通过；i18n 表面变化触发完整检查', () => {
    if (!hasBash()) {
        test.skip('bash 不可用');
        return;
    }
    const root = makeGitRepo();
    try {
        writeBundles(root, GOOD_ZH + 'status=状态\n', GOOD_EN + 'status=Status\n');
        runGenerate(root);
        const accept = runAcceptCore(root, { locale: 'en-US' });
        assert.equal(accept.ok, true);
        git(['add', '-A'], root);
        const result = bash(['scripts/hooks/pre-commit'], root);
        assert.equal(result.status, 0, result.stdout + result.stderr);
        assert.match(result.stdout, /trusted HEAD checker/);
    } finally {
        cleanRepo(root);
    }
});

test('pre-commit：暂存新 checker（合法增强版）时运行 staged index checker；两者都通过才允许', () => {
    if (!hasBash()) {
        test.skip('bash 不可用');
        return;
    }
    const root = makeGitRepo();
    try {
        // 暂存一个「合法增强」的 checker：在 check.mjs 末尾追加一行无副作用注释（内容仍通过 --version 自检）
        const checkerPath = path.join(root, 'scripts', 'i18n', 'check.mjs');
        const original = fs.readFileSync(checkerPath, 'utf8');
        fs.writeFileSync(checkerPath, original + '\n// staged enhancement\n', 'utf8');
        // 同时暂存一个普通业务文件变化，触发 pre-commit（checker 变化 → index checker 必须运行）
        fs.mkdirSync(path.join(root, 'pixivdownload-app', 'src', 'main', 'resources', 'static', 'js'), { recursive: true });
        fs.writeFileSync(path.join(root, 'pixivdownload-app', 'src', 'main', 'resources', 'static', 'js', 'x.js'),
            'var x = 1;\n', 'utf8');
        git(['add', '-A'], root);
        const result = bash(['scripts/hooks/pre-commit'], root);
        assert.equal(result.status, 0, result.stdout + result.stderr);
        assert.match(result.stdout, /running staged index checker/);
    } finally {
        cleanRepo(root);
    }
});

// ---------------------------------------------------------------------------
// pre-push：ref / remote 处理
// ---------------------------------------------------------------------------

test('pre-push：双远端 —— upstream 已有坏 commit、origin 没有；推 origin 拦截、推 upstream 只检增量', () => {
    if (!hasBash()) {
        test.skip('bash 不可用');
        return;
    }
    const root = makeGitRepo();
    const upstream = path.join(os.tmpdir(), 'pixiv bare upstream ' + Date.now());
    const origin = path.join(os.tmpdir(), 'pixiv bare origin ' + Date.now());
    try {
        fs.mkdirSync(upstream);
        fs.mkdirSync(origin);
        git(['init', '-q', '--bare', upstream], root);
        git(['init', '-q', '--bare', origin], root);
        git(['remote', 'add', 'upstream', upstream], root);
        git(['remote', 'add', 'origin', origin], root);

        // A = 坏 commit，先以「无 hooks 的远端机器」身份进入 upstream
        writeBundles(root, GOOD_ZH, BAD_EN);
        commitAll(root, 'A bad', { bypass: true });
        const seed = git(['-c', 'core.hooksPath=/dev/null', 'push', 'upstream', 'master'], root, { allowFailure: true });
        assert.equal(seed.status, 0, seed.stdout + seed.stderr);
        // B = 修复 commit（仅本地）
        writeBundles(root, GOOD_ZH, GOOD_EN);
        commitAll(root, 'B good', { bypass: true });

        // 推 origin：origin 没有任何 tracking ref → 保守检查全部可达 → 命中 A
        const pushOrigin = git(['push', 'origin', 'master'], root, { allowFailure: true });
        assert.notEqual(pushOrigin.status, 0, 'A 不在 origin 上，pre-push 必须拦截');
        assert.match(pushOrigin.stdout + pushOrigin.stderr, /does not pass the i18n gate/);
        assert.match(pushOrigin.stdout + pushOrigin.stderr, /I18N CHECK FAILED|missing key|placeholder/);

        // 推 upstream：目标 remote 参数生效 → remote_sha=A 已知 → 只检增量 {B}
        const pushUpstream = git(['push', 'upstream', 'master'], root, { allowFailure: true });
        assert.equal(pushUpstream.status, 0, pushUpstream.stdout + pushUpstream.stderr);
        assert.match(pushUpstream.stdout + pushUpstream.stderr, /all \d+ pushed commit/);

        // 新分支（origin 上不存在、包含 A）→ 仍必须拦截
        git(['checkout', '-q', '-b', 'feature'], root);
        const pushFeature = git(['push', 'origin', 'feature'], root, { allowFailure: true });
        assert.notEqual(pushFeature.status, 0, '新分支包含 origin 不可达的坏 commit，必须拦截');
        assert.match(pushFeature.stdout + pushFeature.stderr, /does not pass the i18n gate/);

        // origin 必须没有任何更新
        const lsRemote = git(['ls-remote', origin], root);
        assert.equal(lsRemote.stdout.trim(), '');
    } finally {
        cleanRepo(root);
        fs.rmSync(upstream, { recursive: true, force: true });
        fs.rmSync(origin, { recursive: true, force: true });
    }
});

test('pre-push：checker 来自待推送 ref tip（当前 HEAD 无关）', () => {
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

        // 分支 other：tip 的 checker 被篡改为 exit 0（该分支自带契约），且历史含坏翻译
        git(['checkout', '-q', '-b', 'other'], root);
        tamperChecker(root, EXIT_ZERO_CHECKER);
        writeBundles(root, GOOD_ZH, BAD_EN);
        commitAll(root, 'tampered checker + bad translation', { bypass: true });

        // 停留在 master（HEAD 是正常 checker）推送 other → 必须使用 other tip 的 checker
        git(['checkout', '-q', 'master'], root);
        const push = git(['push', 'origin', 'other'], root, { allowFailure: true });
        assert.equal(push.status, 0, 'checker 必须来自待推送 ref tip（篡改后的分支 checker），而不是 HEAD: '
            + push.stdout + push.stderr);
        assert.match(push.stdout + push.stderr, /checker from/);

        // 对照组：分支 other2 用正常 checker + 坏翻译 → HEAD 无关，仍被其自身 tip checker 拦截
        git(['checkout', '-q', 'other'], root);
        git(['checkout', '-q', '-b', 'other2'], root);
        git(['checkout', '-q', 'master'], root);
        git(['branch', '-D', 'other'], root);
        // 在 other2 上恢复 master 的真实 checker（此前 other 分支篡改了它），并写入不同的坏翻译
        git(['checkout', '-q', 'other2'], root);
        git(['checkout', 'master', '--', 'scripts/i18n/check.mjs'], root);
        writeBundles(root, GOOD_ZH, 'greeting=Hello {wrong2}\ntitle=Artwork title\n');
        commitAll(root, 'good checker + bad translation', { bypass: true });
        const blocked = git(['push', 'origin', 'other2'], root, { allowFailure: true });
        assert.notEqual(blocked.status, 0, '正常 checker 的分支坏翻译必须被拦截');
        assert.match(blocked.stdout + blocked.stderr, /does not pass the i18n gate/);
    } finally {
        cleanRepo(root);
        fs.rmSync(remote, { recursive: true, force: true });
    }
});

test('pre-push：annotated tag / 多 ref / 同一 commit 多 ref 去重 / force push', () => {
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

        // 合法演进：新增 key 并 accept（正常 pre-commit 路径提交）
        writeBundles(root, GOOD_ZH + 'status=状态\n', GOOD_EN + 'status=Status\n');
        runGenerate(root);
        assert.equal(runAcceptCore(root, { locale: 'en-US' }).ok, true);
        commitAll(root, 'add status key');

        git(['tag', 'light'], root);
        git(['tag', '-a', 'annotated', '-m', 'annotated tag'], root);

        // 多 ref：master + 两个 tag（lightweight 与 annotated），annotated 需 peel 到 commit
        const multi = git(['push', 'origin', 'master', 'light', 'annotated'], root, { allowFailure: true });
        assert.equal(multi.status, 0, multi.stdout + multi.stderr);
        assert.match(multi.stdout + multi.stderr, /all \d+ pushed commit/);

        // force push：改写历史后强制推送（新提交合法）
        git(['reset', '-q', '--hard', 'HEAD~1'], root);
        fs.writeFileSync(path.join(root, APP_I18N, 'web', 'common.properties'),
            GOOD_ZH + 'status2=状态二\n', 'utf8');
        fs.writeFileSync(path.join(root, APP_I18N, 'web', 'common_en.properties'),
            GOOD_EN + 'status2=Status two\n', 'utf8');
        runGenerate(root);
        assert.equal(runAcceptCore(root, { locale: 'en-US' }).ok, true);
        commitAll(root, 'rewritten history', { bypass: true });
        const forced = git(['push', 'origin', 'master', '--force'], root, { allowFailure: true });
        assert.equal(forced.status, 0, forced.stdout + forced.stderr);
        assert.match(forced.stdout + forced.stderr, /all \d+ pushed commit/);
    } finally {
        cleanRepo(root);
        fs.rmSync(remote, { recursive: true, force: true });
    }
});

test('pre-push：remote SHA 不在本地 object database → 回退目标 remote tracking refs，保守不放过', () => {
    if (!hasBash()) {
        test.skip('bash 不可用');
        return;
    }
    const root = makeGitRepo();
    const remote = path.join(os.tmpdir(), 'pixiv bare remote ' + Date.now());
    const clone = path.join(os.tmpdir(), 'pixiv clone ' + Date.now());
    try {
        fs.mkdirSync(remote);
        git(['init', '-q', '--bare', remote], root);
        git(['remote', 'add', 'origin', remote], root);
        const first = git(['push', 'origin', 'master'], root, { allowFailure: true });
        assert.equal(first.status, 0, first.stdout + first.stderr);

        // 第二台机器（clone）在远端 master 上推进一个未知 commit（无 hooks）
        git(['clone', '-q', remote, clone], root);
        git(['config', 'user.email', 't@example.com'], clone);
        git(['config', 'user.name', 'test'], clone);
        writeBundles(clone, GOOD_ZH, BAD_EN);
        git(['add', '-A'], clone);
        git(['-c', 'core.hooksPath=/dev/null', 'commit', '-q', '-m', 'remote-only bad'], clone);
        const remoteAdvance = git(['-c', 'core.hooksPath=/dev/null', 'push', 'origin', 'master'], clone, { allowFailure: true });
        assert.equal(remoteAdvance.status, 0, remoteAdvance.stdout + remoteAdvance.stderr);

        // 本地 fetch 建立 tracking refs（origin/master = 远端新 tip）
        git(['fetch', '-q', 'origin'], root);
        // 第二台机器继续推进一个未知 commit（bad）——本地只有旧 tracking refs
        writeBundles(clone, GOOD_ZH, GOOD_EN);
        git(['add', '-A'], clone);
        git(['-c', 'core.hooksPath=/dev/null', 'commit', '-q', '-m', 'remote-only good'], clone);
        writeBundles(clone, GOOD_ZH, BAD_EN);
        git(['add', '-A'], clone);
        git(['-c', 'core.hooksPath=/dev/null', 'commit', '-q', '-m', 'remote-only bad2'], clone);
        const remoteAdvance2 = git(['-c', 'core.hooksPath=/dev/null', 'push', 'origin', 'master'], clone, { allowFailure: true });
        assert.equal(remoteAdvance2.status, 0, remoteAdvance2.stdout + remoteAdvance2.stderr);

        // 本地基于旧基线提交一个合法 commit；推送时 remote_sha 是未知的远端 commit
        writeBundles(root, GOOD_ZH, GOOD_EN);
        commitAll(root, 'local good');
        const push = git(['push', 'origin', 'master'], root, { allowFailure: true });
        // 远端更新被 git 拒绝（非快进）属于预期；pre-push 必须明确走 tracking refs 回退路径
        assert.match(push.stdout + push.stderr, /not present locally/);
        assert.match(push.stdout + push.stderr, /tracking refs/);
    } finally {
        cleanRepo(root);
        cleanRepo(clone);
        fs.rmSync(remote, { recursive: true, force: true });
    }
});

test('pre-push / pre-commit：不依赖 tar；不使用 --remotes=*；不从 remote_ref 推导 remote 名；输出含真实诊断', () => {
    for (const hook of ['pre-commit', 'pre-push']) {
        const content = fs.readFileSync(path.join(REPO_ROOT, 'scripts', 'hooks', hook), 'utf8')
            // 去掉注释（hook 文档头允许提到这些工具名），只检查真实命令用法
            .split('\n').filter((line) => !/^\s*#/.test(line)).join('\n');
        assert.doesNotMatch(content, /(git archive|\btar\s|\brsync\s|\bzip\s|\bpython\w*[\s.]|\bpowershell\w*[\s.])/i,
            hook + ' 不得依赖 tar / rsync / zip / Python / PowerShell');
        assert.doesNotMatch(content, /--remotes=\*/i, hook + ' 不得使用 --remotes=*');
        assert.doesNotMatch(content, /remote_ref%%\/\*/i, hook + ' 不得从 remote_ref 推导 remote 名');
    }
    const prePush = fs.readFileSync(path.join(REPO_ROOT, 'scripts', 'hooks', 'pre-push'), 'utf8');
    assert.match(prePush, /remote_name="\$\{1:-\}"/, 'pre-push 必须从 $1 读取 remote name');
    const preCommit = fs.readFileSync(path.join(REPO_ROOT, 'scripts', 'hooks', 'pre-commit'), 'utf8');
    assert.doesNotMatch(preCommit, /remote_name=/, 'pre-commit 不涉及 remote 处理');
});

// ---------------------------------------------------------------------------
// doctor：Windows bash 检查
// ---------------------------------------------------------------------------

test('doctor：Windows mock 平台下 bash 可用 → 通过（X_OK 不作为硬要求）', () => {
    if (!hasBash()) {
        test.skip('bash 不可用');
        return;
    }
    const root = makeGitRepo();
    try {
        const doctor = spawnSync('node', [path.join(SCRIPTS_DIR, 'doctor-hooks.mjs')],
            { cwd: root, encoding: 'utf8', env: { ...process.env, PIXIV_DOCTOR_MOCK_PLATFORM: 'win32' } });
        assert.equal(doctor.status, 0, doctor.stdout + doctor.stderr);
    } finally {
        cleanRepo(root);
    }
});

test('doctor：bash 缺失（PATH 注入失败 bash）→ hooks 已配置时失败并提示 Git for Windows', () => {
    if (!hasBash()) {
        test.skip('bash 不可用');
        return;
    }
    const root = makeGitRepo();
    const fake = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv-no-bash-'));
    try {
        // libuv 只解析精确文件名 + PATHEXT；用假的 bash.exe 让 bashAvailable() 失败
        fs.writeFileSync(path.join(fake, 'bash.exe'), 'not a real executable', 'utf8');
        const pathWithFake = fake + path.delimiter + process.env.PATH;
        const doctor = spawnSync('node', [path.join(SCRIPTS_DIR, 'doctor-hooks.mjs')],
            { cwd: root, encoding: 'utf8', env: { ...process.env, PATH: pathWithFake } });
        assert.notEqual(doctor.status, 0, 'bash 不可用时 doctor 必须失败');
        assert.match(doctor.stdout + doctor.stderr, /bash is not available/);
    } finally {
        cleanRepo(root);
        fs.rmSync(fake, { recursive: true, force: true });
    }
});

test('doctor：bash 语法错误 → 失败并定位 hook', () => {
    if (!hasBash()) {
        test.skip('bash 不可用');
        return;
    }
    const root = makeGitRepo();
    try {
        fs.writeFileSync(path.join(root, 'scripts', 'hooks', 'pre-commit'),
            '#!/usr/bin/env bash\nif [[[ then\n', 'utf8');
        const doctor = spawnSync('node', [path.join(SCRIPTS_DIR, 'doctor-hooks.mjs')],
            { cwd: root, encoding: 'utf8' });
        assert.notEqual(doctor.status, 0);
        assert.match(doctor.stdout + doctor.stderr, /pre-commit failed bash syntax check/);
    } finally {
        cleanRepo(root);
    }
});

// ---------------------------------------------------------------------------
// docs：链接完整性
// ---------------------------------------------------------------------------

test('docs：tracked Markdown 相对链接目标存在；外部链接格式合法；hooks 不引用不存在的本地文档', () => {
    const mdFiles = git(['ls-files', '--', '*.md'], REPO_ROOT).stdout.split('\n').filter(Boolean)
        .filter((f) => !f.includes('vendor/'));
    assert.ok(mdFiles.length >= 5, '至少应扫描 README 与截图文档');
    const missing = [];
    const badExternal = [];
    for (const file of mdFiles) {
        const content = fs.readFileSync(path.join(REPO_ROOT, file), 'utf8');
        const dir = path.posix.dirname(file);
        for (const match of content.matchAll(/!?\[[^\]]*\]\(([^)]+)\)/g)) {
            const target = match[1].trim();
            if (!target || target.startsWith('#')) {
                continue;
            }
            if (/^https?:\/\//.test(target)) {
                // 外部链接只做格式检查，不让 CI 因短暂网络故障失败
                if (!/^https?:\/\/[^\s]+$/.test(target)) {
                    badExternal.push(file + ' -> ' + target);
                }
                continue;
            }
            const anchorIndex = target.indexOf('#');
            const pathPart = anchorIndex >= 0 ? target.slice(0, anchorIndex) : target;
            if (!pathPart) {
                continue;
            }
            const resolved = path.posix.normalize(path.posix.join(dir, pathPart));
            if (!resolved.startsWith('..')) {
                const onDisk = path.join(REPO_ROOT, ...resolved.split('/'));
                if (!fs.existsSync(onDisk)) {
                    missing.push(file + ' -> ' + target + ' (resolved ' + resolved + ')');
                }
            }
            // 解析到仓库外（如 GitHub 相对 URL ../../releases）跳过：属于 GitHub 链接约定
        }
    }
    assert.deepEqual(missing, [], '相对链接目标必须存在');
    assert.deepEqual(badExternal, [], '外部链接格式必须合法');

    // hooks 中声明的本地文档路径必须存在于仓库（不存在即失败）
    for (const hook of ['pre-commit', 'pre-push']) {
        const content = fs.readFileSync(path.join(REPO_ROOT, 'scripts', 'hooks', hook), 'utf8');
        assert.doesNotMatch(content, /docs\/i18n-workflow\.md/,
            hook + ' 不得引用不存在的本地 docs/i18n-workflow.md');
        for (const match of content.matchAll(/docs\/[A-Za-z0-9_./-]+\.md/g)) {
            const tracked = git(['ls-files', '--', match[0]], REPO_ROOT).stdout.trim();
            assert.ok(tracked, hook + ' 引用的本地文档必须被仓库跟踪: ' + match[0]);
        }
    }
});

// ---------------------------------------------------------------------------
// 静态 metadata：default / fallback 契约
// ---------------------------------------------------------------------------

test('静态 meta.json：default=en-US、fallback=en-US、source=zh-CN；alias 输出；_ / - 规范化等价', async () => {
    const root = makeGitRepo();
    try {
        const meta = JSON.parse(fs.readFileSync(
            path.join(root, 'pixivdownload-app', 'src', 'main', 'resources', 'static', 'i18n-static', 'meta.json'), 'utf8'));
        assert.equal(meta.sourceLang, 'zh-CN');
        assert.equal(meta.defaultLang, 'en-US');
        assert.equal(meta.fallbackLang, 'en-US');
        assert.equal(meta.currentLang, 'en-US');
        const zh = meta.supportedLocales.find((l) => l.tag === 'zh-CN');
        assert.deepEqual(zh.aliases, ['zh', 'zh-Hans']);
        // candidate / disabled 不暴露
        assert.equal(meta.supportedLocales.some((l) => l.status === 'candidate' || l.status === 'disabled'), false);
        // _ / - 规范化等价：catalog 校验共享同一 canonicalTag 契约
        const catalogLib = await import('../lib/catalog.mjs');
        assert.equal(catalogLib.default.canonicalTag('ZH_HANS'), catalogLib.default.canonicalTag('zh-Hans'));
        assert.equal(catalogLib.default.canonicalTag('zh-Hans'), 'zh-Hans');
        assert.equal(catalogLib.default.canonicalTag('not a tag'), null);
    } finally {
        cleanRepo(root);
    }
});

// ---------------------------------------------------------------------------
// snapshot-cli：统一物化
// ---------------------------------------------------------------------------

test('snapshot-cli：materialize-index / materialize-ref / materialize-paths 可用且清理', () => {
    const root = makeGitRepo();
    const outBase = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv-snapshot-cli-'));
    try {
        const head = git(['rev-parse', 'HEAD'], root).stdout.trim();
        const cli = path.join(SCRIPTS_DIR, 'snapshot-cli.mjs');

        const outIndex = path.join(outBase, 'index');
        const indexRun = spawnSync('node', [cli, 'materialize-index', '--output', outIndex, '--repo-root', root],
            { cwd: root, encoding: 'utf8' });
        assert.equal(indexRun.status, 0, indexRun.stdout + indexRun.stderr);
        assert.ok(fs.existsSync(path.join(outIndex, 'scripts', 'i18n', 'check.mjs')));

        const outRef = path.join(outBase, 'ref');
        const refRun = spawnSync('node', [cli, 'materialize-ref', '--ref', head, '--output', outRef, '--repo-root', root],
            { cwd: root, encoding: 'utf8' });
        assert.equal(refRun.status, 0, refRun.stdout + refRun.stderr);
        assert.ok(fs.existsSync(path.join(outRef, 'scripts', 'hooks', 'pre-push-guard.sh')));

        const outPaths = path.join(outBase, 'paths');
        const pathsRun = spawnSync('node',
            [cli, 'materialize-paths', '--ref', head, '--paths', 'scripts/i18n', 'scripts/hooks', '--output', outPaths, '--repo-root', root],
            { cwd: root, encoding: 'utf8' });
        assert.equal(pathsRun.status, 0, pathsRun.stdout + pathsRun.stderr);
        assert.ok(fs.existsSync(path.join(outPaths, 'scripts', 'i18n', 'lib', 'repository-snapshot.mjs')));
        assert.ok(fs.existsSync(path.join(outPaths, 'scripts', 'hooks', 'pre-push-guard.sh')));
        // 路径子集物化：不得带出无关目录
        assert.equal(fs.existsSync(path.join(outPaths, 'pixivdownload-app')), false);

        // 非法命令 / 缺失参数 → 非零退出
        const bad = spawnSync('node', [cli, 'materialize-paths', '--ref', head],
            { cwd: root, encoding: 'utf8' });
        assert.notEqual(bad.status, 0);
    } finally {
        cleanRepo(root);
        fs.rmSync(outBase, { recursive: true, force: true });
    }
});
