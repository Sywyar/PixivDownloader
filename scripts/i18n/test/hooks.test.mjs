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
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { runGenerate } from '../generate-static.mjs';
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

/** 建立带真实 scripts/i18n 与 scripts/hooks 的临时 git 仓库。
 * 初始提交同时包含 bundle / lock / 静态资源（i18n 合法），此时 hooks 尚未激活；
 * 之后启用 core.hooksPath。这样后续每个历史 commit 都满足 i18n 门禁，
 * pre-push 的逐 commit 检查只会在「故意注入的坏提交」上失败。 */
function makeGitRepo(base = os.tmpdir()) {
    const dir = path.join(base, 'pixiv test repo ' + Date.now() + '-' + Math.random().toString(36).slice(2));
    fs.mkdirSync(dir, { recursive: true });
    git(['init', '-q'], dir);
    git(['config', 'user.email', 't@example.com'], dir);
    git(['config', 'user.name', 'test'], dir);
    git(['config', 'core.autocrlf', 'false'], dir);

    // 复制真实检查器与 hooks（hooks 经 core.hooksPath 生效）；测试目录不需要且含签名标记字样
    fs.cpSync(path.join(REPO_ROOT, 'scripts', 'i18n'), path.join(dir, 'scripts', 'i18n'), { recursive: true });
    fs.rmSync(path.join(dir, 'scripts', 'i18n', 'test'), { recursive: true, force: true });
    fs.cpSync(path.join(REPO_ROOT, 'scripts', 'hooks'), path.join(dir, 'scripts', 'hooks'), { recursive: true });

    // 初始 bundle + 静态资源 + lock（全部合法）
    const i18nDir = path.join(dir, APP_I18N);
    fs.mkdirSync(path.join(i18nDir, 'web'), { recursive: true });
    fs.writeFileSync(path.join(i18nDir, 'locales.json'), CATALOG, 'utf8');
    writeBundles(dir, GOOD_ZH, GOOD_EN);
    const accept = spawnSync('node',
        [path.join(dir, 'scripts', 'i18n', 'accept.mjs'), '--bootstrap'],
        { cwd: dir, encoding: 'utf8' });
    if (accept.status !== 0) {
        throw new Error('fixture bootstrap failed: ' + accept.stdout + accept.stderr);
    }
    runGenerate(dir);

    git(['add', '--chmod=+x', 'scripts/hooks/pre-commit', 'scripts/hooks/pre-push', 'scripts/hooks/pre-push-guard.sh'], dir);
    git(['add', '-A'], dir);
    git(['commit', '-q', '-m', 'init'], dir);
    // 激活本地 hooks
    git(['config', '--local', 'core.hooksPath', 'scripts/hooks'], dir);
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

/** 写入 bundle 后接受基线并提交（fixture 的初始合法状态）。 */
function bootstrapRepo(root) {
    const accept = spawnSync('node',
        [path.join(root, 'scripts', 'i18n', 'accept.mjs'), '--bootstrap'],
        { cwd: root, encoding: 'utf8' });
    assert.equal(accept.status, 0, 'bootstrap 必须成功: ' + accept.stdout + accept.stderr);
    runGenerate(root);
    git(['add', '-A'], root);
    git(['commit', '-q', '-m', 'i18n baseline'], root);
}

function snapshotLeakCount() {
    const tmp = fs.readdirSync(os.tmpdir()).filter((name) => name.startsWith('pixivdownload-i18n-snapshot-'));
    return tmp.length;
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
    assert.equal(snapshotLeakCount(), before, '临时快照必须全部清理');
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
    assert.equal(snapshotLeakCount(), 0, '临时快照目录必须全部清理');
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
