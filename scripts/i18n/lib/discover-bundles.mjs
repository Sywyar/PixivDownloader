'use strict';
/**
 * 发现仓库内第一方 i18n bundle（自动扫描，不维护写死的模块数组）。
 * 扫描各模块 src/main/resources/i18n 目录下的 *.properties，模块归属按路径第一段计算。
 */

import fs from 'fs';
import path from 'path';

const EXCLUDED_DIRS = new Set([
    '.git', 'node_modules', 'target', 'build', 'plugins', 'dist', '.idea', '.vscode',
    'eclipse', '.run', '.gradle', 'temp', 'tmp',
]);

const I18N_DIR = path.join('src', 'main', 'resources', 'i18n');

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

/**
 * 把相对仓库根的 properties 路径归属到 (module, baseName, suffix)。
 * @returns {{module, baseName, suffix, relPath} | null}
 */
function attribute(repoRoot, file, catalog) {
    const rel = path.relative(repoRoot, file).split(path.sep).join('/');
    const marker = '/src/main/resources/i18n/';
    const markerIndex = rel.indexOf(marker);
    if (markerIndex < 0) {
        return null;
    }
    const module = rel.slice(0, markerIndex);
    const relInI18n = rel.slice(markerIndex + marker.length);
    const fileName = path.basename(relInI18n);
    const dir = path.dirname(relInI18n);

    // 后缀检测：文件名（去 .properties）最后一个 _ 之后的部分若匹配已知非空 resourceSuffix 则为该语言文件；
    // 否则视为「未知语言后缀文件」（如 *_ja.properties 而 ja-JP 不在 catalog）。
    const leaf = fileName.slice(0, -'.properties'.length);
    let baseNameLeaf = leaf;
    let suffix = '';
    let unknownSuffix = false;
    const underscoreIndex = leaf.lastIndexOf('_');
    if (underscoreIndex >= 0) {
        const candidate = leaf.slice(underscoreIndex + 1);
        const descriptor = catalog.locales.find((d) => d.resourceSuffix !== '' && d.resourceSuffix === candidate);
        if (descriptor) {
            suffix = candidate;
            baseNameLeaf = leaf.slice(0, underscoreIndex);
        } else {
            unknownSuffix = true;
            suffix = candidate;
        }
    }
    const baseName = dir === '.' ? baseNameLeaf : dir + '/' + baseNameLeaf;
    return { module, baseName, suffix, relPath: rel, unknownSuffix };
}

/**
 * 发现全部第一方 bundle。
 * @returns {{bundles: Map<string, object>, rawFiles: Array, unknownSuffixFiles: Array}}
 *   bundles key = `${module}__${baseName}`；rawFiles = [{relPath, module, baseName, suffix, localeTag}]
 */
function discover(repoRoot, catalog) {
    const files = [];
    walk(repoRoot, files);

    const rawFiles = [];
    const unknownSuffixFiles = [];
    for (const file of files) {
        const attrs = attribute(repoRoot, file, catalog);
        if (!attrs) {
            continue;
        }
        let localeTag = null;
        if (attrs.unknownSuffix) {
            unknownSuffixFiles.push({ ...attrs, localeTag: null, filePath: file });
            continue;
        }
        if (attrs.suffix === '') {
            localeTag = catalog.locales.find((d) => d.resourceSuffix === '')?.tag || null;
        } else {
            const descriptor = catalog.locales.find((d) => d.resourceSuffix === attrs.suffix);
            localeTag = descriptor ? descriptor.tag : null;
            if (!descriptor) {
                unknownSuffixFiles.push({ ...attrs, localeTag: null, filePath: file });
                continue;
            }
        }
        const entry = {
            relPath: attrs.relPath,
            module: attrs.module,
            baseName: attrs.baseName,
            suffix: attrs.suffix,
            localeTag,
            filePath: file,
        };
        rawFiles.push(entry);
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

function namespaceOf(baseName) {
    return baseName.startsWith('web/') ? baseName.slice('web/'.length) : baseName;
}

/** bundle 展示 id：module__baseName（web/ 前缀去掉，与 Agent prompt 文件名一致）。 */
function bundleKey(module, baseName) {
    return module + '__' + namespaceOf(baseName);
}

export {  discover, bundleKey, namespaceOf  };

export default { discover, bundleKey, namespaceOf };
