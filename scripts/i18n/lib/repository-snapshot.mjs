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

function git(args, cwd, env = {}) {
    // -c core.autocrlf=false：物化快照必须与 Git index 字节完全一致，
    // 不做工作树换行转换（否则 CRLF 化文件与生成器输出 LF 比较时永远 stale）。
    return execFileSync('git', ['-c', 'core.autocrlf=false', ...args],
        { cwd, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], env: { ...process.env, ...env } });
}

function assertSafeTempDir(dir) {
    const resolved = path.resolve(dir);
    const tmpResolved = path.resolve(os.tmpdir());
    if (!resolved.startsWith(tmpResolved + path.sep) && resolved !== tmpResolved) {
        throw new Error('refusing to use non-temp directory for snapshot materialization: ' + resolved);
    }
}

/**
 * 删除临时目录（路径边界检查：只允许删除系统临时目录下的内容，且不得是仓库根 / 系统根）。
 */
function removeTempDir(dir) {
    const resolved = path.resolve(dir);
    assertSafeTempDir(resolved);
    fs.rmSync(resolved, { recursive: true, force: true });
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

export { materializeWorktree, materializeIndex, materializeRef, cleanupAll };

export default { materializeWorktree, materializeIndex, materializeRef, cleanupAll };
