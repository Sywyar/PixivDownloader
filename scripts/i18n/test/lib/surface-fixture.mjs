'use strict';
/**
 * 测试夹具共享 helper：把真实仓库的「外围 gate surface」文件复制进 fixture 仓库。
 * 单一事实来源，避免各测试文件手写不同清单（与 scripts/ci/gate-surface.json 一致）。
 */
import fs from 'node:fs';
import path from 'node:path';

/** 除 scripts/ci（已整目录复制）外的额外 gate surface 文件。 */
export const GATE_SURFACE_FILES = [
    'scripts/sync-shared-snippets.ps1',
    '.github/workflows/shared-snippets-check.yml',
    '.github/workflows/release.yml',
    '.github/workflows/nightly.yml',
    '.github/workflows/publish-plugins.yml',
];

/** 把真实仓库的额外 gate surface 文件复制进 fixture（幂等）。 */
export function copyGateSurfaceFiles(srcRoot, dstRoot) {
    fs.mkdirSync(path.join(dstRoot, 'scripts'), { recursive: true });
    fs.mkdirSync(path.join(dstRoot, '.github', 'workflows'), { recursive: true });
    for (const rel of GATE_SURFACE_FILES) {
        fs.copyFileSync(path.join(srcRoot, rel), path.join(dstRoot, rel));
    }
}
