'use strict';
/**
 * 真实 Git 快照物化：让检查器只基于被检查的 Git 快照工作，绝不混入工作树内容。
 *
 * - materializeWorktree()：直接使用仓库工作树（不复制）；
 * - materializeIndex()：git checkout-index --all --prefix=<临时目录>/ 物化 Git index；
 * - materializeRef(ref)：独立临时 index + git read-tree <ref> + git checkout-index，
 *   严格读取给定 commit / tree 的内容；
 *
 * 安全要求：
 * - prefix 必须是绝对路径并以目录分隔符结尾（git checkout-index 契约）；
 * - 不得修改仓库真实 index（GIT_INDEX_FILE 指向临时文件）；
 * - 不得调用会改变当前分支的 checkout；
 * - 临时目录必须位于系统临时目录；删除前做路径边界检查（禁止删除仓库根 / 系统根）；
 * - 不使用 tar / rsync 等非 Windows 默认工具；
 * - 所有调用方必须在 finally 中 cleanup；异常路径也不得残留临时目录。
 */

import { execFileSync } from 'child_process';
import fs from 'fs';
import os from 'os';
import path from 'path';

// 惰性创建：只有真正物化快照时才建临时根（导入本模块的进程不残留空目录）。
let TEMP_ROOT = null;

function tempRoot() {
    if (TEMP_ROOT === null) {
        TEMP_ROOT = fs.mkdtempSync(path.join(os.tmpdir(), 'pixivdownload-i18n-snapshot-'));
    }
    return TEMP_ROOT;
}

function tempDir(label) {
    const dir = fs.mkdtempSync(path.join(tempRoot(), label + '-'));
    return dir;
}

function git(args, cwd, env = {}, input) {
    // -c core.autocrlf=false：物化快照必须与 Git index 字节完全一致，
    // 不做工作树换行转换（否则 CRLF 化文件与生成器输出 LF 比较时永远 stale）。
    return execFileSync('git', ['-c', 'core.autocrlf=false', ...args],
        { cwd, encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'], env: { ...process.env, ...env }, input });
}function assertSafeTempDir(dir) {
    const resolved = path.resolve(dir);
    const tmpResolved = path.resolve(os.tmpdir());
    if (!resolved.startsWith(tmpResolved + path.sep) && resolved !== tmpResolved) {
        throw new Error('refusing to use non-temp directory for snapshot materialization: ' + resolved);
    }
}

/**
 * 删除临时目录（路径边界检查：只允许删除系统临时目录下的内容，且不得是仓库根 / 系统根）。
 * Windows 上子进程句柄可能短暂残留导致 rm 失败，重试几次再放弃。
 */
function removeTempDir(dir) {
    const resolved = path.resolve(dir);
    assertSafeTempDir(resolved);
    for (let attempt = 0; attempt < 6; attempt += 1) {
        try {
            fs.rmSync(resolved, { recursive: true, force: true });
            return;
        } catch (e) {
            if (attempt === 5) {
                throw e;
            }
            // 幂等重试：不能依赖 bash（本模块被 check.mjs 等纯 Node 进程使用）
            const deadline = Date.now() + 300;
            while (Date.now() < deadline) {
                // busy-wait 短间隔
            }
        }
    }
}

/** 检查 ref 在仓库中可解析，避免把任意字符串塞给 git read-tree。 */
function assertRefExists(repoRoot, ref) {
    git(['rev-parse', '--verify', '--quiet', ref + '^{commit}'], repoRoot);
}

/**
 * 物化 Git index 到临时目录。
 * @returns {{root: string, cleanup: () => void}} root 为绝对路径（含尾部语义）
 */
function materializeIndex(repoRoot) {
    const root = tempDir('index');
    const prefix = root.endsWith(path.sep) ? root : root + path.sep;
    git(['checkout-index', '--all', '--prefix=' + prefix], repoRoot);
    return {
        root,
        cleanup() {
            removeTempDir(root);
        },
    };
}

/**
 * 物化给定 commit / ref 的 tree 到临时目录（独立临时 index，不改仓库 index）。
 * @returns {{root: string, cleanup: () => void}}
 */
function materializeRef(repoRoot, ref) {
    assertRefExists(repoRoot, ref);
    const root = tempDir('ref');
    const indexFile = path.join(tempRoot(), 'tmp-index-' + process.pid + '-' + Math.random().toString(36).slice(2));
    try {
        git(['read-tree', ref], repoRoot, { GIT_INDEX_FILE: indexFile });
        const prefix = root.endsWith(path.sep) ? root : root + path.sep;
        git(['checkout-index', '--all', '--prefix=' + prefix], repoRoot, { GIT_INDEX_FILE: indexFile });
    } finally {
        fs.rmSync(indexFile, { force: true });
    }
    return {
        root,
        cleanup() {
            removeTempDir(root);
        },
    };
}

/**
 * 从给定 ref（commit / tree）物化指定相对路径子集。
 * 路径从 git ls-tree -r 精确枚举，只物化匹配路径前缀（允许目录）或精确路径（文件）的条目；
 * 未命中任何条目时返回空快照（root 存在、无文件），由调用方决定兼容语义。
 * 独立临时 index + read-tree + checkout-index --stdin，绝不使用 tar / rsync / zip。
 * @param {string} repoRoot
 * @param {string} ref 必须是仓库内可解析的 commit / tree（会被 rev-parse 验证）
 * @param {Array<string>} paths 相对仓库根的路径前缀（如 'scripts/i18n'）
 * @returns {{root: string, cleanup: () => void}}
 */
function materializePaths(repoRoot, ref, paths) {
    assertRefExists(repoRoot, ref);
    const root = tempDir('paths');
    const indexFile = path.join(tempRoot(), 'tmp-index-' + process.pid + '-' + Math.random().toString(36).slice(2));
    try {
        git(['read-tree', ref], repoRoot, { GIT_INDEX_FILE: indexFile });
        const selected = selectPaths(repoRoot, ref, paths);
        if (selected.length > 0) {
            checkoutIndexTo(repoRoot, root, { indexFile, paths: selected });
        }
    } finally {
        fs.rmSync(indexFile, { force: true });
    }
    return {
        root,
        cleanup() {
            removeTempDir(root);
        },
    };
}

/**
 * 从 ls-tree 枚举匹配给定路径前缀 / 精确路径的条目名（确定性排序）。
 */
function selectPaths(repoRoot, ref, paths) {
    const wanted = paths.map((p) => p.split(path.sep).join('/'));
    const prefixes = wanted.map((p) => p.endsWith('/') ? p : p + '/');
    const listed = git(['ls-tree', '-r', '--name-only', ref], repoRoot).split('\n').filter(Boolean);
    return listed.filter((name) =>
        wanted.includes(name) || prefixes.some((prefix) => name.startsWith(prefix)));
}

/**
 * checkout-index 到指定输出目录（core.symlinks=true：POSIX 下按 Git 语义物化符号链接；
 * Windows 无法创建符号链接的场景由调用方预先检测并显式失败，绝不静默跳过）。
 */
function checkoutIndexTo(repoRoot, outputDir, { indexFile, paths } = {}) {
    fs.mkdirSync(outputDir, { recursive: true });
    const prefix = outputDir.endsWith(path.sep) ? outputDir : outputDir + path.sep;
    const args = ['-c', 'core.autocrlf=false', '-c', 'core.symlinks=true', 'checkout-index'];
    if (paths) {
        args.push('--stdin');
    } else {
        args.push('--all');
    }
    args.push('--prefix=' + prefix);
    const env = indexFile ? { GIT_INDEX_FILE: indexFile } : {};
    if (paths) {
        git(args, repoRoot, env, paths.join('\n') + '\n');
    } else {
        git(args, repoRoot, env);
    }
}

/** Git index 内容直接物化到用户指定输出目录（不经过临时目录复制）。 */
function materializeIndexTo(repoRoot, outputDir) {
    checkoutIndexTo(repoRoot, outputDir);
    return outputDir;
}

/** 给定 ref 的 tree 直接物化到用户指定输出目录（独立临时 index，不改仓库 index）。 */
function materializeRefTo(repoRoot, ref, outputDir) {
    assertRefExists(repoRoot, ref);
    const indexFile = path.join(tempRoot(), 'tmp-index-' + process.pid + '-' + Math.random().toString(36).slice(2));
    try {
        git(['read-tree', ref], repoRoot, { GIT_INDEX_FILE: indexFile });
        checkoutIndexTo(repoRoot, outputDir, { indexFile });
    } finally {
        fs.rmSync(indexFile, { force: true });
    }
    return outputDir;
}

/** 给定 ref 的指定路径子集直接物化到用户指定输出目录。 */
function materializePathsTo(repoRoot, ref, paths, outputDir) {
    assertRefExists(repoRoot, ref);
    const indexFile = path.join(tempRoot(), 'tmp-index-' + process.pid + '-' + Math.random().toString(36).slice(2));
    try {
        git(['read-tree', ref], repoRoot, { GIT_INDEX_FILE: indexFile });
        const selected = selectPaths(repoRoot, ref, paths);
        if (selected.length > 0) {
            checkoutIndexTo(repoRoot, outputDir, { indexFile, paths: selected });
        }
    } finally {
        fs.rmSync(indexFile, { force: true });
    }
    return outputDir;
}

/** 真实 index 中的指定路径子集直接物化到用户指定输出目录（ls-files 精确枚举）。 */
function materializeIndexPathsTo(repoRoot, paths, outputDir) {
    const wanted = paths.map((p) => p.split(path.sep).join('/'));
    const prefixes = wanted.map((p) => p.endsWith('/') ? p : p + '/');
    let listed = [];
    try {
        listed = git(['ls-files', '--cached', '--stage'], repoRoot)
            .split('\n').filter(Boolean).map((line) => line.split('\t').pop());
    } catch (e) {
        throw new Error('cannot enumerate the git index: ' + e.message);
    }
    const selected = listed.filter((name) =>
        wanted.includes(name) || prefixes.some((prefix) => name.startsWith(prefix)));
    if (selected.length > 0) {
        checkoutIndexTo(repoRoot, outputDir, { paths: selected });
    }
    return outputDir;
}

/** 树中是否存在 symlink 条目（mode 120000）。 */
function hasSymlinksInTree(repoRoot, ref) {
    try {
        const entries = git(['ls-tree', '-r', ref], repoRoot);
        return entries.split('\n').some((line) => line.startsWith('120000'));
    } catch (e) {
        return false;
    }
}

/** index 中是否存在 symlink 条目（mode 120000）。 */
function hasSymlinksInIndex(repoRoot) {
    try {
        const entries = git(['ls-files', '--stage'], repoRoot);
        return entries.split('\n').some((line) => line.startsWith('120000'));
    } catch (e) {
        return false;
    }
}

/** worktree 模式：直接使用仓库根（不做复制）；cleanup 为 no-op。 */
function materializeWorktree(repoRoot) {
    return {
        root: repoRoot,
        cleanup() {
            // nothing to clean: worktree 属于用户
        },
    };
}

/** 会话级临时目录清理（进程退出时由调用方显式调用；幂等）。 */
function cleanupAll() {
    if (TEMP_ROOT !== null) {
        removeTempDir(TEMP_ROOT);
        TEMP_ROOT = null;
    }
}

export {
    materializeWorktree, materializeIndex, materializeRef, materializePaths, cleanupAll,
    materializeIndexTo, materializeRefTo, materializePathsTo, materializeIndexPathsTo,
    hasSymlinksInTree, hasSymlinksInIndex,
};

export default {
    materializeWorktree, materializeIndex, materializeRef, materializePaths, cleanupAll,
    materializeIndexTo, materializeRefTo, materializePathsTo, materializeIndexPathsTo,
    hasSymlinksInTree, hasSymlinksInIndex,
};
