#!/usr/bin/env node
'use strict';
/**
 * 从 locales.json 与核心 app 的 exact bundle 生成静态站点 i18n 资源：
 *   pixivdownload-app/src/main/resources/static/i18n-static/
 *     meta.json                       —— 与 /api/i18n/meta 语言策略一致（default 场景）
 *     <namespace>.<tag>.json          —— effective bundle（目标 → fallback → source）
 *
 * 只生成 source 与 supported；candidate/disabled 不发布给普通用户。
 * 只生成核心 app 模块的 web namespaces：app boot jar 不得携带外置插件 i18n（CLAUDE.md 架构约束），
 * 外置插件页面与后端 i18n API 同生命周期，静态回退没有消费场景，因此插件 bundle 不静态化；
 * 插件缺席时前端 fetch 静态 bundle 自然 404，回退到 key 本身。
 * 确定性排序；生成文件需提交，CI 会以 git diff --exit-code 验证同步。
 */

import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

import catalogLib from './lib/catalog.mjs';
import parser from './lib/properties-parser.mjs';
import discover from './lib/discover-bundles.mjs';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');

/** 静态化只覆盖核心 app 模块（classpath:/static 的唯一拥有者）。 */
const CORE_MODULE = 'pixivdownload-app';

function effectiveMessages(catalog, bundle, localeTag, parsed) {
    // 回退链合并：源语言 → fallback → 目标语言，后写入者覆盖
    const chain = catalogLib.fallbackChain(catalog, localeTag);
    const merged = new Map();
    for (let i = chain.length - 1; i >= 0; i -= 1) {
        const file = bundle.files[chain[i]];
        if (!file) {
            continue;
        }
        const result = parsed.get(file.relPath);
        if (!result) {
            continue;
        }
        for (const entry of result.entries) {
            merged.set(entry.key, entry.value);
        }
    }
    return merged;
}

/**
 * 生成静态 i18n 资源。
 * @returns {{outputDir: string, files: Array<string>}}
 */
export function runGenerate(repoRoot) {
    const catalog = catalogLib.load(repoRoot);
    const discovery = discover.discover(repoRoot, catalog);
    const parsed = new Map();
    for (const entry of discovery.rawFiles) {
        parsed.set(entry.relPath, parser.parse(fs.readFileSync(entry.filePath, 'utf8')));
    }

    const outputDir = path.join(repoRoot,
        'pixivdownload-app', 'src', 'main', 'resources', 'static', 'i18n-static');
    fs.rmSync(outputDir, { recursive: true, force: true });
    fs.mkdirSync(outputDir, { recursive: true });

    const visible = catalog.locales.filter((d) => d.status === 'source' || d.status === 'supported');
    // 只有核心 app 的 web namespace 是前端 /api/i18n/messages/{namespace} 契约的静态部分
    const coreBundles = [...discovery.bundles.values()]
        .filter((b) => b.module === CORE_MODULE && b.baseName.startsWith('web/'))
        .sort((a, b) => a.bundleId.localeCompare(b.bundleId));
    const namespaces = coreBundles.map((b) => b.namespace).sort();

    // meta.json：与后端 /api/i18n/meta 同形状（default 场景的 currentLang）
    const meta = {
        currentLang: catalog.defaultLocale,
        sourceLang: catalog.sourceLocale,
        defaultLang: catalog.defaultLocale,
        fallbackLang: catalog.fallbackLocale,
        languageCookieName: catalog.languageCookieName,
        languageParamName: catalog.languageParameterName,
        supportedLocales: visible.map((d) => ({
            tag: d.tag,
            label: d.nativeName,
            nativeName: d.nativeName,
            direction: d.direction,
            status: d.status,
        })),
        supportedNamespaces: namespaces,
    };
    fs.writeFileSync(path.join(outputDir, 'meta.json'), JSON.stringify(meta, null, 2) + '\n', 'utf8');

    for (const locale of visible) {
        for (const bundle of coreBundles) {
            const messages = effectiveMessages(catalog, bundle, locale.tag, parsed);
            if (messages.size === 0) {
                continue;
            }
            const payload = {
                namespace: bundle.namespace,
                lang: locale.tag,
                defaultLang: catalog.defaultLocale,
                messages: Object.fromEntries([...messages.entries()].sort((a, b) => a[0].localeCompare(b[0]))),
            };
            fs.writeFileSync(
                path.join(outputDir, bundle.namespace + '.' + locale.tag + '.json'),
                JSON.stringify(payload, null, 2) + '\n',
                'utf8');
        }
    }

    const files = fs.readdirSync(outputDir).sort();
    return { outputDir, files };
}

function main() {
    const result = runGenerate(REPO_ROOT);
    console.log('i18n:generate-static: wrote ' + result.files.length + ' file(s) to '
        + path.relative(REPO_ROOT, result.outputDir).split(path.sep).join('/'));
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    main();
}
