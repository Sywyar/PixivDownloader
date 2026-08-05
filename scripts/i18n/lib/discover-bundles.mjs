'use strict';
/**
 * 发现仓库内第一方 i18n bundle（自动扫描，不维护写死的模块数组）。
 *
 * 下划线判定（不能因为文件名最后包含 `_xxx` 就认定它是未知语言文件）：
 * 1. 已知 resourceSuffix 精确匹配 → 语言文件；
 * 2. 未知 `_xxx` 后缀：只有同一目录中存在同 baseName 的源 bundle（`B.properties`）
 *    或其它已知语言 sibling（`B_<knownSuffix>.properties`）时，才认定为未知语言文件；
 * 3. 单独 `download_status.properties`（无 sibling 证据）→ 视为 source bundle；
 * 4. 同一目录中的判定必须确定性（先收集证据再归属，结果与目录枚举顺序无关）；
 * 5. 目标路径生成与 discovery 使用同一命名逻辑（targetPathFor）。
 */

import fs from 'fs';
import path from 'path';

const EXCLUDED_DIRS = new Set([
    '.git', 'node_modules', 'target', 'build', 'plugins', 'dist', '.idea', '.vscode',
    'eclipse', '.run', '.gradle', 'temp', 'tmp',
]);

function walk(dir, out) {
    let entries;
    try {
        entries = fs.readdirSync(dir, { withFileTypes: true });
    } catch (e) {
        return;
    }
    for (const entry of entries) {
        const full = path.join(dir, entry.name);
        if (entry.isDirectory()) {
            if (EXCLUDED_DIRS.has(entry.name)) {
                continue;
            }
            walk(full, out);
        } else if (entry.isFile() && entry.name.endsWith('.properties')) {
            out.push(full);
        }
    }
}

function knownDescriptor(catalog, suffix) {
    if (!suffix) {
        return null;
    }
    return catalog.locales.find((d) => d.resourceSuffix === suffix) || null;
}

/** 单个文件：相对仓库根路径与 i18n 目录内定位。 */
function locate(repoRoot, file) {
    const rel = path.relative(repoRoot, file).split(path.sep).join('/');
    const marker = '/src/main/resources/i18n/';
    const markerIndex = rel.indexOf(marker);
    if (markerIndex < 0) {
        return null;
    }
    return {
        module: rel.slice(0, markerIndex),
        relInI18n: rel.slice(markerIndex + marker.length),
        relPath: rel,
    };
}

/**
 * 目录级归属。
 * @returns {Array<{module, baseName, suffix, relPath, unknownSuffix, filePath}>}
 */
function attributeDir(repoRoot, dirFiles, catalog) {
    const located = [];
    for (const file of dirFiles) {
        const loc = locate(repoRoot, file);
        if (loc) {
            located.push({ ...loc, filePath: file });
        }
    }

    // 第一遍：收集证据（每个 baseLeaf 是否存在源文件或已知语言 sibling）
    const evidence = new Map(); // baseLeaf -> {source, knownLang}
    for (const entry of located) {
        const fileName = path.basename(entry.relInI18n);
        const leaf = fileName.slice(0, -'.properties'.length);
        if (!leaf.includes('_')) {
            const ev = evidence.get(leaf) || { source: false, knownLang: false };
            ev.source = true;
            evidence.set(leaf, ev);
            continue;
        }
        const baseLeaf = leaf.slice(0, leaf.lastIndexOf('_'));
        const candidate = leaf.slice(leaf.lastIndexOf('_') + 1);
        if (knownDescriptor(catalog, candidate)) {
            const ev = evidence.get(baseLeaf) || { source: false, knownLang: false };
            ev.knownLang = true;
            evidence.set(baseLeaf, ev);
        }
    }

    // 第二遍：归属（确定性）
    const results = [];
    for (const entry of located) {
        const fileName = path.basename(entry.relInI18n);
        const dir = path.dirname(entry.relInI18n);
        const leaf = fileName.slice(0, -'.properties'.length);

        if (!leaf.includes('_')) {
            results.push({
                module: entry.module,
                baseName: dir === '.' ? leaf : dir + '/' + leaf,
                suffix: '',
                relPath: entry.relPath,
                unknownSuffix: false,
                filePath: entry.filePath,
            });
            continue;
        }

        const baseLeaf = leaf.slice(0, leaf.lastIndexOf('_'));
        const candidate = leaf.slice(leaf.lastIndexOf('_') + 1);
        const descriptor = knownDescriptor(catalog, candidate);
        const baseName = dir === '.' ? baseLeaf : dir + '/' + baseLeaf;
        if (descriptor) {
            results.push({
                module: entry.module,
                baseName,
                suffix: candidate,
                relPath: entry.relPath,
                unknownSuffix: false,
                filePath: entry.filePath,
            });
            continue;
        }

        const ev = evidence.get(baseLeaf) || null;
        if (ev && (ev.source || ev.knownLang)) {
            results.push({
                module: entry.module,
                baseName,
                suffix: candidate,
                relPath: entry.relPath,
                unknownSuffix: true,
                filePath: entry.filePath,
            });
        } else {
            results.push({
                module: entry.module,
                baseName: dir === '.' ? leaf : dir + '/' + leaf,
                suffix: '',
                relPath: entry.relPath,
                unknownSuffix: false,
                filePath: entry.filePath,
            });
        }
    }
    return results;
}

/**
 * 发现全部第一方 bundle。
 * @returns {{bundles: Map<string, object>, rawFiles: Array, unknownSuffixFiles: Array, conflicts: Array}}
 */
function discover(repoRoot, catalog) {
    const files = [];
    walk(repoRoot, files);

    // 按目录分组（排序保证确定性）
    const byDir = new Map();
    for (const file of files) {
        const rel = path.relative(repoRoot, file);
        const dir = path.dirname(rel);
        if (!byDir.has(dir)) {
            byDir.set(dir, []);
        }
        byDir.get(dir).push(file);
    }

    const attributed = [];
    for (const dirFiles of byDir.values()) {
        dirFiles.sort((a, b) => a.localeCompare(b));
        for (const attrs of attributeDir(repoRoot, dirFiles, catalog)) {
            attributed.push(attrs);
        }
    }

    const rawFiles = [];
    const unknownSuffixFiles = [];
    for (const attrs of attributed) {
        if (attrs.unknownSuffix) {
            unknownSuffixFiles.push({
                module: attrs.module,
                baseName: attrs.baseName,
                suffix: attrs.suffix,
                relPath: attrs.relPath,
                localeTag: null,
                filePath: attrs.filePath,
            });
            continue;
        }
        let localeTag = null;
        if (attrs.suffix === '') {
            localeTag = catalog.locales.find((d) => d.resourceSuffix === '')?.tag || null;
        } else {
            const descriptor = knownDescriptor(catalog, attrs.suffix);
            localeTag = descriptor ? descriptor.tag : null;
            if (!descriptor) {
                unknownSuffixFiles.push({
                    module: attrs.module,
                    baseName: attrs.baseName,
                    suffix: attrs.suffix,
                    relPath: attrs.relPath,
                    localeTag: null,
                    filePath: attrs.filePath,
                });
                continue;
            }
        }
        rawFiles.push({
            relPath: attrs.relPath,
            module: attrs.module,
            baseName: attrs.baseName,
            suffix: attrs.suffix,
            localeTag,
            filePath: attrs.filePath,
        });
    }

    // 同一 bundle 内同一 locale 出现多个物理文件 → 冲突
    const conflicts = [];
    const seen = new Map();
    for (const entry of rawFiles) {
        if (entry.localeTag === null) {
            continue;
        }
        const key = `${entry.module}__${entry.baseName}__${entry.localeTag}`;
        if (seen.has(key)) {
            conflicts.push({ ...seen.get(key), other: entry });
        } else {
            seen.set(key, entry);
        }
    }

    const bundles = new Map();
    for (const entry of rawFiles) {
        if (entry.localeTag === null) {
            continue;
        }
        const bundleId = bundleKey(entry.module, entry.baseName);
        if (!bundles.has(bundleId)) {
            bundles.set(bundleId, {
                module: entry.module,
                baseName: entry.baseName,
                bundleId,
                namespace: namespaceOf(entry.baseName),
                files: {},
            });
        }
        bundles.get(bundleId).files[entry.localeTag] = entry;
    }

    return { bundles, rawFiles, unknownSuffixFiles, conflicts };
}

/** 目标语言文件在仓库中的精确相对路径（翻译 Agent 可直接创建 / 修改）。 */
function targetPathFor(module, baseName, suffix) {
    const dir = baseName.includes('/')
        ? baseName.slice(0, baseName.lastIndexOf('/'))
        : '';
    const leaf = baseName.includes('/')
        ? baseName.slice(baseName.lastIndexOf('/') + 1)
        : baseName;
    const fileName = leaf + (suffix ? '_' + suffix : '') + '.properties';
    return module + '/src/main/resources/i18n/'
        + (dir ? dir + '/' : '') + fileName;
}

function namespaceOf(baseName) {
    return baseName.startsWith('web/') ? baseName.slice('web/'.length) : baseName;
}

/** bundle 展示 id：module__baseName（web/ 前缀去掉，与 Agent prompt 文件名一致）。 */
function bundleKey(module, baseName) {
    return module + '__' + namespaceOf(baseName);
}

export { discover, bundleKey, namespaceOf, targetPathFor };

export default { discover, bundleKey, namespaceOf, targetPathFor };
