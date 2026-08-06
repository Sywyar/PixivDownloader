'use strict';
/**
 * snapshot-cli 输出目录加固测试（13.x）：
 * output 不得已存在（空目录也拒绝）、不得覆盖、不得是仓库根 / 祖先 / .git / 文件系统根、
 * symlink 拒绝、parent 安全创建、失败不留半成品、物化集合与 Git tree 精确一致、
 * 删除后重新物化不残留旧文件、路径含空格可用、POSIX symlink 按 Git 语义物化。
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const SCRIPTS_DIR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const CLI = path.join(SCRIPTS_DIR, 'snapshot-cli.mjs');

function git(args, cwd, opts = {}) {
    const result = spawnSync('git', args, { cwd, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024, ...opts });
    if (result.status !== 0 && !opts.allowFailure) {
        throw new Error('git ' + args.join(' ') + ' failed: ' + (result.stderr || result.stdout));
    }
    return result;
}

/** 简单 fixture：根文件 + 子目录文件（含空格文件名）。 */
function makeRepo() {
    const dir = path.join(os.tmpdir(), 'pixiv snapshot repo ' + Date.now() + '-' + Math.random().toString(36).slice(2));
    fs.mkdirSync(dir, { recursive: true });
    git(['init', '-q'], dir);
    git(['config', 'user.email', 't@example.com'], dir);
    git(['config', 'user.name', 'test'], dir);
    git(['config', 'core.autocrlf', 'false'], dir);
    fs.writeFileSync(path.join(dir, 'README.md'), '# snapshot\n', 'utf8');
    fs.mkdirSync(path.join(dir, 'src', 'nested'), { recursive: true });
    fs.writeFileSync(path.join(dir, 'src', 'nested', 'a.txt'), 'a\n', 'utf8');
    fs.writeFileSync(path.join(dir, 'src', 'b file.txt'), 'b\n', 'utf8');
    git(['add', '-A'], dir);
    git(['commit', '-q', '-m', 'init'], dir);
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

function materializeRef(root, ref, output, extra = []) {
    return spawnSync('node', [CLI, 'materialize-ref', '--ref', ref, '--output', output,
        '--repo-root', root, ...extra], { cwd: root, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
}

function walkFiles(dir, rel = '') {
    const out = [];
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
        const relPath = rel ? rel + '/' + entry.name : entry.name;
        if (entry.isDirectory()) {
            out.push(...walkFiles(path.join(dir, entry.name), relPath));
        } else {
            out.push(relPath);
        }
    }
    return out.sort();
}

function treeNames(root) {
    return git(['ls-tree', '-r', '--name-only', 'HEAD'], root).stdout.trim().split('\n').filter(Boolean).sort();
}

test('snapshot-cli：output 不存在 → 成功；物化集合与 Git tree 精确一致；路径含空格可用', () => {
    const root = makeRepo();
    const base = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv snap out '));
    try {
        const out = path.join(base, 'out dir', 'snapshot'); // parent 不存在 + 空格
        const run = materializeRef(root, 'HEAD', out);
        assert.equal(run.status, 0, run.stdout + run.stderr);
        assert.match(run.stdout, /materialized ref/);
        assert.deepEqual(walkFiles(out), treeNames(root), '物化集合必须与 Git tree 精确一致');
        // 最后一行输出绝对路径
        const lastLine = run.stdout.trim().split('\n').pop();
        assert.ok(path.isAbsolute(lastLine));
    } finally {
        cleanRepo(root);
        fs.rmSync(base, { recursive: true, force: true });
    }
});

test('snapshot-cli：output 已存在（空目录 / 非空目录 / 文件）→ 拒绝', () => {
    const root = makeRepo();
    const base = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv snap existing '));
    try {
        const empty = path.join(base, 'empty');
        fs.mkdirSync(empty);
        const emptyRun = materializeRef(root, 'HEAD', empty);
        assert.notEqual(emptyRun.status, 0, '空目录也必须拒绝');
        assert.match(emptyRun.stderr, /already exists/);

        const nonEmpty = path.join(base, 'nonempty');
        fs.mkdirSync(nonEmpty);
        fs.writeFileSync(path.join(nonEmpty, 'x.txt'), 'x', 'utf8');
        const nonEmptyRun = materializeRef(root, 'HEAD', nonEmpty);
        assert.notEqual(nonEmptyRun.status, 0, '非空目录必须拒绝');
        assert.match(nonEmptyRun.stderr, /already exists/);

        const asFile = path.join(base, 'afile');
        fs.writeFileSync(asFile, 'file', 'utf8');
        const fileRun = materializeRef(root, 'HEAD', asFile);
        assert.notEqual(fileRun.status, 0, 'output 是文件必须拒绝');
        assert.match(fileRun.stderr, /already exists/);
    } finally {
        cleanRepo(root);
        fs.rmSync(base, { recursive: true, force: true });
    }
});

test('snapshot-cli：output 是仓库根 / .git / 祖先 / 文件系统根 → 拒绝', () => {
    const base = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv snap forbidden '));
    const root = makeRepo(); // 仓库位于 os.tmpdir() 下，与 base 是兄弟目录
    try {
        const repoRootRun = materializeRef(root, 'HEAD', root);
        assert.notEqual(repoRootRun.status, 0, '仓库根必须拒绝');
        assert.match(repoRootRun.stderr, /repository root/);

        const gitRun = materializeRef(root, 'HEAD', path.join(root, '.git'));
        assert.notEqual(gitRun.status, 0, '.git 必须拒绝');
        assert.match(gitRun.stderr, /\.git/);

        // 真正的祖先：把仓库放进一个父目录，output 指向该父目录
        const parent = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv snap parent '));
        const nestedRoot = path.join(parent, 'repo');
        fs.renameSync(root, nestedRoot);
        const ancestorRun = materializeRef(nestedRoot, 'HEAD', parent);
        assert.notEqual(ancestorRun.status, 0, '仓库根祖先必须拒绝');
        assert.match(ancestorRun.stderr, /ancestor of the repository root/);
        fs.renameSync(nestedRoot, root);

        const fsRoot = path.parse(root).root;
        const fsRootRun = materializeRef(root, 'HEAD', fsRoot);
        assert.notEqual(fsRootRun.status, 0, '文件系统根必须拒绝');
        assert.match(fsRootRun.stderr, /filesystem root/);
    } finally {
        cleanRepo(root);
        fs.rmSync(base, { recursive: true, force: true });
    }
});

test('snapshot-cli：output 是 symlink → 拒绝；parent 链中 symlink → 拒绝', () => {
    const root = makeRepo();
    const base = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv snap symlink '));
    try {
        const realDir = path.join(base, 'real');
        fs.mkdirSync(realDir);
        const link = path.join(base, 'link');
        let made = true;
        try {
            fs.symlinkSync(realDir, link, 'junction');
        } catch (e) {
            made = false;
        }
        if (made) {
            const run = materializeRef(root, 'HEAD', path.join(link, 'out'));
            assert.notEqual(run.status, 0, 'parent 链 symlink 必须拒绝');
            assert.match(run.stderr, /symlink/);

            const outLink = path.join(base, 'out-link');
            fs.symlinkSync(realDir, outLink, 'junction');
            const run2 = materializeRef(root, 'HEAD', outLink);
            assert.notEqual(run2.status, 0, 'output 本身是 symlink 必须拒绝');
            assert.match(run2.stderr, /already exists|symlink/);
        }
    } finally {
        cleanRepo(root);
        fs.rmSync(base, { recursive: true, force: true });
    }
});

test('snapshot-cli：删除后重新物化不残留旧文件（精确集合）', () => {
    const root = makeRepo();
    const base = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv snap redo '));
    try {
        const out = path.join(base, 'out');
        const first = materializeRef(root, 'HEAD', out);
        assert.equal(first.status, 0, first.stdout + first.stderr);
        assert.deepEqual(walkFiles(out), treeNames(root));
        // 删除部分文件后整个删除再重新物化 → 精确集合，无旧文件残留
        fs.rmSync(out, { recursive: true, force: true });
        const second = materializeRef(root, 'HEAD', out);
        assert.equal(second.status, 0, second.stdout + second.stderr);
        assert.deepEqual(walkFiles(out), treeNames(root), '重新物化必须与 tree 精确一致');
    } finally {
        cleanRepo(root);
        fs.rmSync(base, { recursive: true, force: true });
    }
});

test('snapshot-cli：失败不留下半成品目录（非法 ref 在创建 output 前失败）', () => {
    const root = makeRepo();
    const base = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv snap cleanup '));
    try {
        const out = path.join(base, 'out');
        const run = materializeRef(root, 'deadbeef', out);
        assert.notEqual(run.status, 0);
        assert.equal(fs.existsSync(out), false, '非法 ref 不得创建 output');
        // parent 仍可复用；后续合法物化成功
        const ok = materializeRef(root, 'HEAD', out);
        assert.equal(ok.status, 0, ok.stdout + ok.stderr);
        assert.deepEqual(walkFiles(out), treeNames(root));
    } finally {
        cleanRepo(root);
        fs.rmSync(base, { recursive: true, force: true });
    }
});

test('snapshot-cli：POSIX 下 tree 内 symlink 按 Git 语义物化（win32 明确失败）', (t) => {
    if (process.platform === 'win32') {
        t.skip('symlink 物化验证仅在 POSIX 下运行（Windows 无法创建 symlink 时 CLI 明确失败，代码路径在拒绝分支）');
        return;
    }
    const root = makeRepo();
    const base = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv snap treesym '));
    try {
        fs.symlinkSync('src/nested/a.txt', path.join(root, 'link.txt'));
        git(['add', '-A'], root);
        git(['commit', '-q', '-m', 'add symlink'], root);
        const mode = git(['ls-tree', '-r', 'HEAD'], root).stdout;
        assert.ok(mode.includes('120000'), 'fixture 必须含 symlink 条目');
        const out = path.join(base, 'out');
        const run = materializeRef(root, 'HEAD', out);
        assert.equal(run.status, 0, run.stdout + run.stderr);
        const st = fs.lstatSync(path.join(out, 'link.txt'));
        assert.ok(st.isSymbolicLink(), 'POSIX 下必须物化为真实 symlink（不得复制目标内容）');
        assert.deepEqual(walkFiles(out), treeNames(root));
    } finally {
        cleanRepo(root);
        fs.rmSync(base, { recursive: true, force: true });
    }
});
