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
 * - materialize-paths：物化给定 ref 的指定路径子集（scripts/i18n、scripts/hooks 等），
 *   供 hooks 在物化可信 checker 后复用 Node 实现做后续快照；
 * - 绝不使用 tar / rsync / zip / Python；Git Bash 与 POSIX shell 共用；
 * - 临时目录位于系统临时目录，进程退出自动清理（finally / signal）。
 *
 * 安全边界：
 * - shell hooks 不得把「工作树中未暂存的 snapshot-cli」当作唯一可信入口：
 *   物化可信 checker 的最小 Git 命令仍由 hook 自身以 HEAD / index / ref 物化后执行，
 *   一旦可信 checker 就位，后续快照统一复用本实现。
 * - 输出目录必须显式给出；拒绝相对仓库根的意外覆盖（--output 会新建目录，绝不 rm 已有目录）。
 */

import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

import snapshot from './lib/repository-snapshot.mjs';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');

function fail(message) {
    console.error('snapshot-cli ERROR: ' + message);
    process.exit(2);
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

    let materialized = null;
    try {
        if (command === 'materialize-index') {
            for (let i = 0; i < args.length; i += 1) {
                if (args[i] === '--output') {
                    const output = requireOutput(args, i);
                    fs.mkdirSync(output, { recursive: true });
                    materialized = snapshot.materializeIndex(repoRoot);
                    copySnapshot(materialized.root, output);
                    materialized.cleanup();
                    materialized = null;
                    snapshot.cleanupAll();
                    console.log('materialized git index (' + fs.readdirSync(output).length + ' top-level entries)');
                    console.log(output);
                    return;
                }
            }
            fail('--output <dir> is required');
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
            fs.mkdirSync(output, { recursive: true });
            materialized = snapshot.materializeRef(repoRoot, ref);
            copySnapshot(materialized.root, output);
            materialized.cleanup();
            materialized = null;
            snapshot.cleanupAll();
            console.log('materialized ref ' + ref + ' (' + fs.readdirSync(output).length + ' top-level entries)');
            console.log(output);
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
            fs.mkdirSync(output, { recursive: true });
            materialized = snapshot.materializePaths(repoRoot, ref, paths);
            copySnapshot(materialized.root, output);
            materialized.cleanup();
            materialized = null;
            snapshot.cleanupAll();
            console.log('materialized paths [' + paths.join(', ') + '] from ref ' + ref);
            console.log(output);
        } else {
            fail('unknown command: ' + command);
        }
    } catch (e) {
        if (materialized) {
            try {
                materialized.cleanup();
            } catch (ignored) {
                // cleanup 失败不能掩盖原始错误
            }
        }
        snapshot.cleanupAll();
        fail(e.message);
    }
}

/** 把临时物化目录内容复制到用户指定输出目录（只处理文件与目录，跳过符号链接）。 */
function copySnapshot(from, to) {
    const entries = fs.readdirSync(from, { withFileTypes: true });
    for (const entry of entries) {
        const src = path.join(from, entry.name);
        const dst = path.join(to, entry.name);
        if (entry.isDirectory()) {
            fs.cpSync(src, dst, { recursive: true });
        } else if (entry.isFile()) {
            fs.copyFileSync(src, dst);
        }
    }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    main();
}
