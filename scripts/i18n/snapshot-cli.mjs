#!/usr/bin/env node
'use strict';
/**
 * 统一 Git 快照物化 CLI（单一 Git snapshot contract）。
 *
 * 用法：
 *   node scripts/i18n/snapshot-cli.mjs materialize-index --output <dir>
 *   node scripts/i18n/snapshot-cli.mjs materialize-ref --ref <sha> --output <dir>
 *   node scripts/i18n/snapshot-cli.mjs materialize-paths --ref <sha> --paths a b c --output <dir>
 *
 * 所有子命令都输出物化目录的绝对路径（最后一行），并打印使用的 Git 元信息。
 * 语义：
 * - materialize-index：物化仓库 Git index 全部内容；
 * - materialize-ref：物化给定 commit / tree 的全部内容；
 * - materialize-paths：物化给定 ref 的指定路径子集（scripts/i18n、scripts/hooks 等）；
 * - 直接以 checkout-index 物化到最终 output（无临时目录复制）；绝不使用 tar / rsync / zip / Python。
 *
 * 输出目录安全边界（--output）：
 * - output 路径不得已经存在（即使是空目录也默认拒绝）：不得覆盖现有文件、不得混合旧快照；
 * - 不得是仓库根、不得是仓库根祖先、不得是文件系统根、不得是 .git 或其子路径；
 * - parent 必须存在或安全创建；output 或 parent 路径链中的异常 symlink 一律拒绝；
 * - 失败不留下半成品目录（任何错误都会清理已创建的 output）；
 * - 物化完成后做精确校验：物化文件集合必须与 Git tree / index 完全一致，缺 / 多都失败。
 *
 * 符号链接语义：
 * - POSIX 下按 Git 语义物化（core.symlinks=true，checkout-index 原生处理）；
 * - Windows 无法创建符号链接时明确失败，绝不静默跳过、绝不把 symlink 目标当普通文件复制。
 */

import fs from 'fs';
import path from 'path';
import { spawnSync } from 'child_process';
import { fileURLToPath } from 'url';

import snapshot from './lib/repository-snapshot.mjs';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');

// 进程退出（含 fail() 的 process.exit 路径）也必须清理会话级临时快照目录
process.on('exit', () => {
    try {
        snapshot.cleanupAll();
    } catch (ignored) {
        // 退出清理失败不掩盖 verdict
    }
});

function fail(message) {
    console.error('snapshot-cli ERROR: ' + message);
    process.exit(2);
}

function git(args, cwd) {
    const result = spawnSync('git', ['-c', 'core.autocrlf=false', ...args],
        { cwd, encoding: 'utf8', maxBuffer: 128 * 1024 * 1024, stdio: ['pipe', 'pipe', 'pipe'] });
    if (result.status !== 0) {
        throw new Error('git ' + args.join(' ') + ' failed: ' + (result.stderr || result.stdout));
    }
    return result.stdout.trim();
}

function requireOutput(args, index) {
    const output = args[index + 1];
    if (!output) {
        fail('--output <dir> is required');
    }
    return path.resolve(output);
}

function parseFlag(args, index, name) {
    const value = args[index + 1];
    if (!value) {
        fail('--' + name + ' requires a value');
    }
    return value;
}

/**
 * 校验输出路径（规则见文件头）。违规直接 fail。
 * @returns {string} 解析后的绝对路径
 */
function validateOutputDir(output, repoRoot) {
    const resolved = path.resolve(output);
    const rootResolved = path.resolve(repoRoot);
    const rootOf = (p) => path.parse(p).root;

    if (resolved === rootResolved) {
        fail('output must not be the repository root: ' + resolved);
    }
    if (resolved === rootOf(resolved)) {
        fail('output must not be the filesystem root: ' + resolved);
    }
    if (rootResolved.startsWith(resolved + path.sep)) {
        fail('output must not be an ancestor of the repository root: ' + resolved);
    }
    if (resolved.startsWith(rootResolved + path.sep)) {
        const rel = resolved.slice(rootResolved.length + 1).split(path.sep);
        if (rel[0] === '.git') {
            fail('output must not be inside .git: ' + resolved);
        }
    }

    // 已存在 → 拒绝（空目录同样拒绝，防止混合旧快照）
    let stat;
    try {
        stat = fs.lstatSync(resolved);
    } catch (e) {
        stat = null;
    }
    if (stat) {
        fail('output path already exists: ' + resolved
            + ' (the output must not exist; delete it or choose a fresh path)');
    }

    // 路径链 symlink 检查：从 output 向上找最近存在的段，必须是真实目录
    let cur = resolved;
    let existing = null;
    for (;;) {
        try {
            const st = fs.lstatSync(cur);
            existing = { seg: cur, st };
            break;
        } catch (e) {
            // 不存在，继续向上
        }
        if (cur === rootOf(cur)) {
            break;
        }
        cur = path.dirname(cur);
    }
    if (existing) {
        if (existing.st.isSymbolicLink()) {
            fail('output path contains a symlink: ' + existing.seg + ' (refusing to materialize through it)');
        }
        if (!existing.st.isDirectory()) {
            fail('output parent is not a directory: ' + existing.seg);
        }
    }

    // parent 安全创建（只创建 parent，不创建 output 本身）
    const parent = path.dirname(resolved);
    fs.mkdirSync(parent, { recursive: true });

    // 创建 parent 后再次确认 output 不存在（不变量）
    try {
        fs.lstatSync(resolved);
        fail('output path appeared during preparation: ' + resolved);
    } catch (e) {
        // 期望不存在
    }
    return resolved;
}

/**
 * 物化后精确校验：output 的文件集合必须与 Git tree / index 完全一致（gitlink 除外）。
 */
function verifyMaterialization(repoRoot, expectedNames, outputDir) {
    const expected = new Set(expectedNames);
    const actual = new Set();
    const walk = (dir, rel) => {
        for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
            const relPath = rel ? rel + '/' + entry.name : entry.name;
            if (entry.isDirectory()) {
                walk(path.join(dir, entry.name), relPath);
            } else {
                actual.add(relPath);
            }
        }
    };
    walk(outputDir, '');
    const missing = [...expected].filter((n) => !actual.has(n)).sort();
    const extra = [...actual].filter((n) => !expected.has(n)).sort();
    if (missing.length > 0 || extra.length > 0) {
        throw new Error('materialized file set does not match the Git tree'
            + (missing.length > 0 ? '; missing: ' + missing.slice(0, 10).join(', ') : '')
            + (extra.length > 0 ? '; extra: ' + extra.slice(0, 10).join(', ') : ''));
    }
}

function treeNames(repoRoot, ref) {
    return git(['ls-tree', '-r', ref], repoRoot).split('\n')
        .filter((line) => line && !line.startsWith('160000'))
        .map((line) => line.split('\t').pop());
}

function indexNames(repoRoot) {
    return git(['ls-files', '--stage'], repoRoot).split('\n')
        .filter((line) => line && !line.startsWith('160000'))
        .map((line) => line.split('\t').pop());
}

function treeNamesFiltered(repoRoot, ref, paths) {
    const wanted = paths.map((p) => p.split(path.sep).join('/'));
    const prefixes = wanted.map((p) => p.endsWith('/') ? p : p + '/');
    return treeNames(repoRoot, ref)
        .filter((name) => wanted.includes(name) || prefixes.some((prefix) => name.startsWith(prefix)));
}

/**
 * 执行物化；任何失败清理已创建的 output，不留下半成品。
 */
function materialize(repoRoot, outputDir, fn) {
    let created = false;
    try {
        outputDir = validateOutputDir(outputDir, repoRoot);
        fn(outputDir);
        created = true;
        return outputDir;
    } catch (e) {
        if (created || fs.existsSync(outputDir)) {
            try {
                fs.rmSync(outputDir, { recursive: true, force: true });
            } catch (ignored) {
                // 清理失败不能掩盖原始错误
            }
        }
        fail(e.message);
        return null;
    }
}

function main() {
    const argv = process.argv.slice(2);
    if (argv.length === 0) {
        fail('usage: snapshot-cli.mjs materialize-index|materialize-ref|materialize-paths ...');
    }
    const command = argv[0];
    const args = argv.slice(1);

    let repoRoot = REPO_ROOT;
    for (let i = 0; i < args.length; i += 1) {
        if (args[i] === '--repo-root') {
            repoRoot = path.resolve(parseFlag(args, i, 'repo-root'));
            break;
        }
    }
    let gitDirOk = false;
    try {
        git(['rev-parse', '--git-dir'], repoRoot);
        gitDirOk = true;
    } catch (e) {
        gitDirOk = false;
    }
    if (!gitDirOk) {
        fail('not inside a git repository: ' + repoRoot);
    }

    if (command === 'materialize-index') {
        let output = null;
        for (let i = 0; i < args.length; i += 1) {
            if (args[i] === '--output') {
                output = requireOutput(args, i);
            }
        }
        if (!output) {
            fail('--output <dir> is required');
        }
        if (process.platform === 'win32' && snapshot.hasSymlinksInIndex(repoRoot)) {
            fail('the git index contains symlink entries which cannot be materialized on Windows; refusing');
        }
        materialize(repoRoot, output, (out) => {
            snapshot.materializeIndexTo(repoRoot, out);
            verifyMaterialization(repoRoot, indexNames(repoRoot), out);
            console.log('materialized git index (' + fs.readdirSync(out).length + ' top-level entries)');
            console.log(out);
        });
    } else if (command === 'materialize-ref') {
        let ref = null;
        let output = null;
        for (let i = 0; i < args.length; i += 1) {
            if (args[i] === '--ref') {
                ref = parseFlag(args, i, 'ref');
            } else if (args[i] === '--output') {
                output = requireOutput(args, i);
            }
        }
        if (!ref) {
            fail('--ref <sha> is required');
        }
        if (!output) {
            fail('--output <dir> is required');
        }
        if (process.platform === 'win32' && snapshot.hasSymlinksInTree(repoRoot, ref)) {
            fail('the ref ' + ref + ' contains symlink entries which cannot be materialized on Windows; refusing');
        }
        materialize(repoRoot, output, (out) => {
            snapshot.materializeRefTo(repoRoot, ref, out);
            verifyMaterialization(repoRoot, treeNames(repoRoot, ref), out);
            console.log('materialized ref ' + ref + ' (' + fs.readdirSync(out).length + ' top-level entries)');
            console.log(out);
        });
    } else if (command === 'materialize-paths') {
        let ref = null;
        let output = null;
        const paths = [];
        for (let i = 0; i < args.length; i += 1) {
            if (args[i] === '--ref') {
                ref = parseFlag(args, i, 'ref');
            } else if (args[i] === '--paths') {
                while (i + 1 < args.length && !args[i + 1].startsWith('--')) {
                    paths.push(args[i + 1]);
                    i += 1;
                }
            } else if (args[i] === '--output') {
                output = requireOutput(args, i);
            }
        }
        if (!ref) {
            fail('--ref <sha> is required');
        }
        if (paths.length === 0) {
            fail('--paths requires at least one path');
        }
        if (!output) {
            fail('--output <dir> is required');
        }
        if (process.platform === 'win32' && snapshot.hasSymlinksInTree(repoRoot, ref)) {
            fail('the ref ' + ref + ' contains symlink entries which cannot be materialized on Windows; refusing');
        }
        materialize(repoRoot, output, (out) => {
            snapshot.materializePathsTo(repoRoot, ref, paths, out);
            verifyMaterialization(repoRoot, treeNamesFiltered(repoRoot, ref, paths), out);
            console.log('materialized paths [' + paths.join(', ') + '] from ref ' + ref);
            console.log(out);
        });
    } else {
        fail('unknown command: ' + command);
    }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    main();
}
