'use strict';
/**
 * snapshot 物化加固测试（20.6）：
 * - 逐级 symlink 检查：output 自身或任意上级组件是 symlink → 写入前 fail closed；
 * - 精确物化验证：Git mode / blob hash / symlink 类型与内容 / executable bit /
 *   不得多文件 / 不得缺文件（git ls-tree / git ls-files --stage / git hash-object）；
 * - output 不存在 / 已存在 / 仓库根 / .git / 祖先目录 / 文件系统根；
 * - 路径含空格；失败无残留（临时快照目录全部清理）。
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
import snapshot from '../lib/repository-snapshot.mjs';

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

const GATE_PATHS = ['scripts/i18n', 'scripts/hooks'];

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

/** C1 = enforcement start（gate bundle 无 policy）；C2 = policy。 */
function makeGitRepo(base = os.tmpdir()) {
    const dir = path.join(base, 'pixiv snapshot repo ' + Date.now() + '-' + Math.random().toString(36).slice(2));
    fs.mkdirSync(dir, { recursive: true });
    git(['init', '-q'], dir);
    git(['config', 'user.email', 't@example.com'], dir);
    git(['config', 'user.name', 'test'], dir);
    git(['config', 'core.autocrlf', 'false'], dir);
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
    git(['commit', '-q', '-m', 'init'], dir);
    const start = git(['rev-parse', 'HEAD'], dir).stdout.trim();
    const policy = JSON.parse(fs.readFileSync(path.join(REPO_ROOT, 'scripts', 'i18n', 'gate-policy.json'), 'utf8'));
    policy.i18nEnforcementStartCommit = start;
    fs.writeFileSync(path.join(dir, 'scripts', 'i18n', 'gate-policy.json'),
        JSON.stringify(policy, null, 2) + '\n', 'utf8');
    git(['add', '-A'], dir);
    git(['commit', '-q', '-m', 'add gate policy'], dir);
    return dir;
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

/** 创建指向 dir 的 junction / symlink（Windows 用 junction 避免管理员权限）。 */
function linkTo(linkPath, target) {
    if (process.platform === 'win32') {
        fs.symlinkSync(target, linkPath, 'junction');
    } else {
        fs.symlinkSync(target, linkPath);
    }
}

function snapshotLeakCount() {
    const cutoff = Date.now() - 30 * 1000;
    return fs.readdirSync(os.tmpdir(), { withFileTypes: true })
        .filter((e) => e.isDirectory() && e.name.startsWith('pixivdownload-i18n-snapshot-'))
        .filter((e) => fs.statSync(path.join(os.tmpdir(), e.name)).mtimeMs < cutoff)
        .length;
}

// 本测试进程直接调用物化 API：进程退出时必须清理会话级临时快照目录
process.on('exit', () => {
    try {
        snapshot.cleanupAll();
    } catch (ignored) {
        // 清理失败不掩盖 verdict
    }
});

test('snapshot：output 不存在 / 已存在 / 嵌套路径 → 物化成功且精确验证通过', () => {
    const root = makeGitRepo();
    const head = git(['rev-parse', 'HEAD'], root).stdout.trim();
    const outBase = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv-snap-out-'));
    try {
        // output 不存在（嵌套两级）
        const fresh = path.join(outBase, 'deep', 'nested', 'out');
        snapshot.materializePathsTo(root, head, GATE_PATHS, fresh);
        assert.ok(fs.existsSync(path.join(fresh, 'scripts', 'i18n', 'check.mjs')));
        assert.ok(fs.existsSync(path.join(fresh, 'scripts', 'hooks', 'pre-push-guard.sh')));

        // output 已存在 → 再次物化（覆盖语义）仍成功
        snapshot.materializePathsTo(root, head, GATE_PATHS, fresh);
        assert.ok(fs.existsSync(path.join(fresh, 'scripts', 'i18n', 'lib', 'trusted-gate.mjs')));

        // 精确验证：blob hash / executable bit（非 Windows）
        const checkFile = path.join(fresh, 'scripts', 'hooks', 'pre-commit');
        if (process.platform !== 'win32') {
            assert.notEqual(fs.statSync(checkFile).mode & 0o111, 0, 'hooks 必须带 executable bit');
        }
        const expected = git(['ls-tree', head, '--', 'scripts/hooks/pre-commit'], root).stdout
            .match(/^100\d{3} (blob) ([0-9a-f]{40})\t/);
        assert.ok(expected, 'ls-tree 必须列出 pre-commit blob');
        const actual = spawnSync('git', ['hash-object', path.join(fresh, 'scripts', 'hooks', 'pre-commit')],
            { encoding: 'utf8' }).stdout.trim();
        assert.equal(actual, expected[2], '物化文件的 blob hash 必须与 ls-tree 一致');
    } finally {
        cleanRepo(root);
        fs.rmSync(outBase, { recursive: true, force: true });
    }
});

test('snapshot：output = 仓库根 / .git / 祖先目录 / 文件系统根 → 精确验证拒绝（不得多文件）', () => {
    const root = makeGitRepo();
    const head = git(['rev-parse', 'HEAD'], root).stdout.trim();
    try {
        // 仓库根：包含大量非 gate 文件 → extra files → 拒绝
        assert.throws(() => snapshot.verifyMaterializedTree(root, head, root, GATE_PATHS),
            /extra file/, '仓库根物化必须被 extra-file 检查拒绝');

        // .git：目录含非 gate 内容 → 拒绝
        assert.throws(() => snapshot.verifyMaterializedTree(root, head, path.join(root, '.git'), GATE_PATHS),
            /extra file/, '.git 物化必须被拒绝');

        // 祖先目录（repo 的父目录包含仓库自身）→ 拒绝
        const ancestor = path.dirname(root);
        assert.throws(() => snapshot.verifyMaterializedTree(root, head, ancestor, GATE_PATHS),
            /extra file/, '祖先目录物化必须被拒绝');

        // 文件系统根：只读验证（不写入）→ 拒绝
        const fsRoot = path.parse(os.tmpdir()).root;
        assert.throws(() => snapshot.verifyMaterializedTree(root, head, fsRoot, GATE_PATHS),
            /extra file/, '文件系统根物化必须被拒绝');
    } finally {
        cleanRepo(root);
    }
});

test('snapshot：output 自身 / 任意上级组件为 symlink → 写入前 fail closed；失败无残留', () => {
    const root = makeGitRepo();
    const head = git(['rev-parse', 'HEAD'], root).stdout.trim();
    const before = snapshotLeakCount();
    const outBase = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv-snap-link-'));
    try {
        // 上级组件 symlink（outBase/link/sub/out，link → 别处）
        const target = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv-snap-target-'));
        try {
            linkTo(path.join(outBase, 'link'), target);
            const throughLink = path.join(outBase, 'link', 'sub', 'out');
            assert.throws(() => snapshot.materializePathsTo(root, head, GATE_PATHS, throughLink),
                /symlink path component/, '上级 symlink 组件必须在写入前被拒绝');
            assert.equal(fs.existsSync(path.join(throughLink, 'scripts')), false, '不得写入任何文件');
            assert.equal(fs.readdirSync(target).length, 0, 'symlink 目标不得被写入');

            // output 自身是 symlink
            linkTo(path.join(outBase, 'out-link'), target);
            assert.throws(() => snapshot.materializePathsTo(root, head, GATE_PATHS, path.join(outBase, 'out-link')),
                /symlink path component/, 'output 自身是 symlink 必须被拒绝');
            assert.equal(fs.readdirSync(target).length, 0, 'symlink 目标不得被写入');
        } finally {
            fs.rmSync(target, { recursive: true, force: true });
        }
    } finally {
        cleanRepo(root);
        fs.rmSync(outBase, { recursive: true, force: true });
    }
    for (let i = 0; i < 120; i += 1) {
        if (snapshotLeakCount() <= before) {
            break;
        }
        execFileSync('bash', ['-c', 'sleep 0.5'], { stdio: 'ignore' });
    }
    assert.ok(snapshotLeakCount() <= before, '失败的物化不得新增残留临时快照目录');
});

test('snapshot：篡改物化文件 → blob hash 验证拒绝；executable bit 翻转（非 Windows）→ 拒绝', () => {
    const root = makeGitRepo();
    const head = git(['rev-parse', 'HEAD'], root).stdout.trim();
    const outBase = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv-snap-tamper-'));
    try {
        const out = path.join(outBase, 'out');
        snapshot.materializePathsTo(root, head, GATE_PATHS, out);
        snapshot.verifyMaterializedTree(root, head, out, GATE_PATHS); // 未篡改 → 通过

        // 篡改内容 → hash 不一致 → 拒绝
        const checkFile = path.join(out, 'scripts', 'i18n', 'check.mjs');
        fs.appendFileSync(checkFile, '\n// tampered\n', 'utf8');
        assert.throws(() => snapshot.verifyMaterializedTree(root, head, out, GATE_PATHS),
            /blob hash mismatch/, '篡改后的 blob hash 必须被拒绝');

        // 重新物化（干净副本）→ 验证通过
        const out2 = path.join(outBase, 'out2');
        snapshot.materializePathsTo(root, head, GATE_PATHS, out2);
        snapshot.verifyMaterializedTree(root, head, out2, GATE_PATHS);
        // 物化字节的 blob hash 必须与 ls-tree 一致（hash-object --stdin 原始字节，与
        // autocrlf 环境无关；hash-object <file> 受仓库 autocrlf 影响，不能用于精确比较）
        const blobHash = spawnSync('git', ['hash-object', '--stdin'],
            { input: fs.readFileSync(path.join(out2, 'scripts', 'i18n', 'check.mjs')), encoding: 'utf8' }).stdout.trim();
        assert.equal(blobHash, git(['ls-tree', head, '--', 'scripts/i18n/check.mjs'], root)
            .stdout.split('\t')[0].split(' ')[2]);

        if (process.platform !== 'win32') {
            const hookFile = path.join(out2, 'scripts', 'hooks', 'pre-commit');
            fs.chmodSync(hookFile, 0o644);
            assert.throws(() => snapshot.verifyMaterializedTree(root, head, out2, GATE_PATHS),
                /executable bit mismatch/, 'executable bit 丢失必须被拒绝');
        }
    } finally {
        cleanRepo(root);
        fs.rmSync(outBase, { recursive: true, force: true });
    }
});

test('snapshot：symlink 条目的 mode 与内容验证（非 Windows）', { skip: process.platform === 'win32' ? 'Windows 无法创建 symlink，跳过' : false }, () => {
    const root = makeGitRepo();
    const head = git(['rev-parse', 'HEAD'], root).stdout.trim();
    const outBase = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv-snap-sym-'));
    try {
        // 在 fixture 仓库中加入 symlink 条目（mode 120000，指向一个真实存在的相对目标）：
        // 目标内容 = scripts/hooks/link-to-check 相对 ../i18n/check.mjs 的真实相对路径；
        // hash-object -w 必须写 object database（--cacheinfo 要求对象已存在）；
        // blob 不含尾随换行（git 真实 symlink blob = 链接目标原始字节）。
        const targetBlob = spawnSync('git', ['hash-object', '-w', '--stdin'], { cwd: root,
            input: '../i18n/check.mjs', encoding: 'utf8' }).stdout.trim();
        assert.ok(/^[0-9a-f]{40}$/.test(targetBlob), 'symlink blob 必须写入 object database');
        git(['update-index', '--add', '--cacheinfo', '120000,' + targetBlob + ',scripts/hooks/link-to-check'], root);
        git(['commit', '-q', '-m', 'add symlink'], root);
        const symHead = git(['rev-parse', 'HEAD'], root).stdout.trim();

        const out = path.join(outBase, 'out');
        snapshot.materializePathsTo(root, symHead, GATE_PATHS, out);
        // symlink 类型与内容验证通过
        snapshot.verifyMaterializedTree(root, symHead, out, GATE_PATHS);
        const linkStat = fs.lstatSync(path.join(out, 'scripts', 'hooks', 'link-to-check'));
        assert.ok(linkStat.isSymbolicLink(), '物化产物必须是 symlink');
        assert.equal(fs.readlinkSync(path.join(out, 'scripts', 'hooks', 'link-to-check')),
            '../i18n/check.mjs', 'symlink 内容必须与 blob 一致');
        // 真实相对目标必须解析到物化目录内真实存在的文件
        const target = path.resolve(path.join(out, 'scripts', 'hooks', 'link-to-check'), '..', '..', 'i18n', 'check.mjs');
        assert.ok(fs.existsSync(target), 'symlink 相对目标必须真实存在（../i18n/check.mjs）');

        // 把 symlink 换成普通文件 → 类型验证拒绝
        fs.rmSync(path.join(out, 'scripts', 'hooks', 'link-to-check'));
        fs.writeFileSync(path.join(out, 'scripts', 'hooks', 'link-to-check'), 'not a symlink\n', 'utf8');
        assert.throws(() => snapshot.verifyMaterializedTree(root, symHead, out, GATE_PATHS),
            /expected symlink/, 'symlink 类型被替换必须被拒绝');
    } finally {
        cleanRepo(root);
        fs.rmSync(outBase, { recursive: true, force: true });
    }
});

test('snapshot：路径含空格 → 物化与验证可用', () => {
    const base = path.join(os.tmpdir(), 'pixiv snapshot space ' + Date.now());
    fs.mkdirSync(base, { recursive: true });
    const root = makeGitRepo(base);
    try {
        const head = git(['rev-parse', 'HEAD'], root).stdout.trim();
        const out = path.join(base, 'out with spaces');
        snapshot.materializePathsTo(root, head, GATE_PATHS, out);
        snapshot.verifyMaterializedTree(root, head, out, GATE_PATHS);
        assert.ok(fs.existsSync(path.join(out, 'scripts', 'i18n', 'check.mjs')));
    } finally {
        cleanRepo(root);
        fs.rmSync(base, { recursive: true, force: true });
    }
});
