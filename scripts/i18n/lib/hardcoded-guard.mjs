'use strict';
/**
 * 生产代码硬编码语言守卫。
 * 从 locales.json 动态构造检查集合（tags + aliases + Locale.* 常量 + 语言清单标识符），
 * 不维护写死的 zh-CN|en-US 正则 —— 新增语言后守卫自动覆盖。
 */

import fs from 'fs';
import path from 'path';

const ALLOWED_REL_PREFIXES = [
    'pixivdownload-app/src/main/resources/i18n/locales.json',
    'pixivdownload-app/src/main/resources/static/i18n-static',
    'scripts/i18n',
    'scripts/hooks',
    'i18n', // catalog-lock.json / ignore.json
    'build',
];

// 外部数据格式中的 locale 键豁免（不是应用语言清单硬编码），必须写明原因。
const EXPLICIT_ALLOWED_FILES = {
    'pixivdownload-app/src/main/java/top/sywyar/pixivdownload/tools/PixivArtworkMetaDump.java':
        '作品元数据转储文件使用 "zh" 等键作为外部 JSON 数据格式字段，不是应用语言配置。',
    'pixivdownload-app/src/main/java/top/sywyar/pixivdownload/tools/ArtworksBackFill.java':
        '读取外部作品元数据 JSON 的 translations 映射（"en" 为数据字段），不是应用语言配置。',
    'pixivdownload-app/src/main/java/top/sywyar/pixivdownload/plugin/catalog/model/PluginCatalogMarketMeta.java':
        '插件市场 catalog 的 wire 格式用 BCP47 键描述本地化名称（Javadoc 示例），不是应用语言清单。',
    'pixivdownload-core-api/src/main/java/top/sywyar/pixivdownload/i18n/LegacyLocaleBundlePolicy.java':
        'core-api 面向旧第三方插件的二进制兼容策略：明确命名并集中封装的 legacy 例外，'
        + '只保证旧版 root=zh-CN + _en=en-US 约定，禁止新代码使用；'
        + '新策略走 LocaleBundlePolicy / CatalogLocaleBundlePolicy，常量不进入其它文件。',
    'pixivdownload-plugin-download-workbench/src/main/java/top/sywyar/pixivdownload/download/controller/PixivProxyController.java':
        '向 Pixiv 外部 API 传递 lang=zh 查询参数并读取其 translations JSON 数据字段，属于外部协议，不是应用语言配置。',
    'pixivdownload-plugin-download-workbench/src/main/java/top/sywyar/pixivdownload/download/PixivFetchService.java':
        '向 Pixiv 外部 API 传递 lang=zh 查询参数并读取其 translations JSON 数据字段，属于外部协议，不是应用语言配置。',
    'pixivdownload-plugin-novel/src/main/java/top/sywyar/pixivdownload/novel/controller/NovelPixivProxyController.java':
        '向 Pixiv 外部 API 传递 lang=zh 查询参数并读取其 translations JSON 数据字段，属于外部协议，不是应用语言配置。',
    'pixivdownload-plugin-novel/src/main/java/top/sywyar/pixivdownload/novel/schedule/PixivScheduledNovelWorkExecutor.java':
        '向 Pixiv 外部 API 传递 lang=zh 查询参数，属于外部协议，不是应用语言配置。',
    'pixivdownload-plugin-novel/src/main/java/top/sywyar/pixivdownload/novel/schedule/PixivScheduledNovelMetadata.java':
        '读取外部作品元数据 JSON 的 translations 映射（"en" 为数据字段），不是应用语言配置。',
    'pixivdownload-plugin-mail/src/main/java/top/sywyar/pixivdownload/notification/MailNotificationSink.java':
        '外置插件无法依赖 app catalog：VERIFY_LOCALES 是发送前校验插件自有通知文案两种语言均存在的运行时检查，语言集与插件自有 bundle 一致。',
    'pixivdownload-plugin-push/src/main/java/top/sywyar/pixivdownload/notification/PushNotificationSink.java':
        '外置插件无法依赖 app catalog：VERIFY_LOCALES 是发送前校验插件自有通知文案两种语言均存在的运行时检查，语言集与插件自有 bundle 一致。',
    'pixivdownload-plugin-tts/src/main/java/top/sywyar/pixivdownload/tts/EdgeTtsClient.java':
        'Edge TTS 外部协议的 SSML xml:lang 与日期解析（Locale.US 仅为格式化时区无关模式），不是应用语言配置。',
    'pixivdownload-plugin-tts/src/main/java/top/sywyar/pixivdownload/tts/EdgeTtsVoiceService.java':
        'Edge TTS 官方音色目录（voice 名称 + BCP47 语言码），是外部语音服务的数据，不是应用语言清单。',
    'pixivdownload-plugin-tts/src/main/resources/static/pixiv-tts/tts/tts-voices.js':
        'Edge TTS 官方音色目录的语音匹配逻辑（按语言码挑选音色），是外部语音服务的数据，不是应用语言清单。',
    'scripts/market-curation.json':
        '插件市场策展数据文件：zh/en 是市场 manifest 的外部数据键，不是应用语言清单。',
    'scripts/generate-market-manifest.ps1':
        '市场 manifest 的外部格式以 zh 为基准语言拼 _<locale> 后缀，属于外部数据格式，不是应用语言配置。',
    'scripts/package-local.ps1':
        'Inno Setup 安装器的语言集（en / zh-CN）受安装器自带语言文件限制，与应用 UI 语言目录相互独立。',
    'scripts/userscript-snippets/pixiv-userscript-i18n.js':
        'userscript 是运行在 pixiv.net 的独立运行时，不依赖应用后端与 catalog，语言集由 sync-shared-snippets.ps1 原样嵌入生成的 .user.js。',
    'pixivdownload-plugin-download-workbench/src/main/resources/static/pixiv-batch-alt/alt-extensions.js':
        'AI 翻译目标语言的默认值（novelTranslateLang 设置缺省时的产品默认），是数据默认值而非语言清单/菜单/切换；batch 与 alt 两页默认语义本就不同，统一迁移留待页面合并。',
};

const ALLOWED_DIRS = [
    'src/test',
    'test', // scripts/i18n/test
    'docs',
];

const ALLOWED_FILE_NAMES = [
    'CLAUDE.md', 'AGENTS.md', 'README.md', 'README_en.md', 'CHANGELOG.md',
    'plugin-templates', // 模板含 example 文案（与仓库规范一致，示例页 i18n 走 data-i18n）
];

// 文件扩展名：仅检查代码 / 构建脚本，不检查 .properties 资源与生成 JSON
const SCAN_EXTENSIONS = new Set(['.java', '.js', '.mjs', '.html', '.ps1', '.sh', '.yml', '.yaml', '.json']);

function scanExtensions() {
    return SCAN_EXTENSIONS;
}

function isAllowed(relPath) {
    const normalized = relPath.split(path.sep).join('/');
    if (Object.prototype.hasOwnProperty.call(EXPLICIT_ALLOWED_FILES, normalized)) {
        return true;
    }
    if (ALLOWED_REL_PREFIXES.some((prefix) => normalized === prefix
        || normalized.startsWith(prefix + '/'))) {
        return true;
    }
    if (ALLOWED_DIRS.some((dir) => normalized.startsWith(dir + '/'))) {
        return true;
    }
    const leaf = normalized.split('/').pop();
    if (ALLOWED_FILE_NAMES.some((name) => leaf === name)) {
        return true;
    }
    // plugin-templates 目录整体允许：示例页面文案与仓库规范一致
    if (normalized.startsWith('plugin-templates/')) {
        return true;
    }
    return false;
}

function buildPatterns(catalog) {
    const patterns = [];
    for (const tag of [catalog.sourceLocale, catalog.fallbackLocale, ...catalog.locales.map((d) => d.tag)]) {
        patterns.push({ kind: 'tag', pattern: quote(tag), label: 'hardcoded language tag "' + tag + '"' });
    }
    for (const descriptor of catalog.locales) {
        for (const alias of descriptor.aliases) {
            patterns.push({ kind: 'alias', pattern: quote(alias), label: 'hardcoded language alias "' + alias + '"' });
        }
    }
    patterns.push({ kind: 'identifier', pattern: /Locale\.(US|ENGLISH|SIMPLIFIED_CHINESE|TRADITIONAL_CHINESE)/g, label: 'hardcoded Locale constant' });
    patterns.push({ kind: 'identifier', pattern: /SUPPORTED_LOCALES/g, label: 'static supported-locales constant' });
    patterns.push({ kind: 'identifier', pattern: /DEFAULT_LOCALE/g, label: 'static default-locale constant' });
    // 只拦截字面量赋值：defaultLang/supportedLocales 从 meta 读取是正常契约，写死语言值才是违规
    patterns.push({ kind: 'identifier', pattern: /defaultLang\s*[:=]\s*['"][^'"]+['"]/g, label: 'hardcoded defaultLang literal' });
    patterns.push({ kind: 'identifier', pattern: /supportedLocales\s*[:=]\s*\[\s*['"]/g, label: 'static supportedLocales array with literal entries' });
    return patterns;
}

function quote(value) {
    const escaped = value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    return new RegExp('[\'"`]' + escaped + '[\'"`]', 'g');
}

/**
 * 扫描生产代码：各模块 src/main 与 scripts（排除 scripts/i18n、scripts/hooks）。
 * 只在这些根下递归，避免扫到仓库根目录的本地杂物（log/、bugs-data/、.codex-tmp/ 等）。
 * @returns {Array<{relPath, line, label}>} 命中列表
 */
function scan(repoRoot, catalog) {
    const hits = [];
    const patterns = buildPatterns(catalog);

    function scanFile(full) {
        const relPath = path.relative(repoRoot, full).split(path.sep).join('/');
        if (isAllowed(relPath)) {
            return;
        }
        let text;
        try {
            text = fs.readFileSync(full, 'utf8');
        } catch (e) {
            return;
        }
        const lines = text.split('\n');
        for (let i = 0; i < lines.length; i += 1) {
            const line = lines[i];
            for (const item of patterns) {
                item.pattern.lastIndex = 0;
                if (!item.pattern.test(line)) {
                    continue;
                }
                // <html lang="TAG"> 是文档默认语言声明（JS 加载后按 meta 覆盖），不是语言清单
                if ((item.kind === 'tag' || item.kind === 'alias')
                    && /^\s*<html[^>]*\blang=["'][^"']+["']/.test(line)) {
                    break;
                }
                hits.push({ relPath, line: i + 1, label: item.label, text: line.trim() });
                break;
            }
        }
    }

    function walk(dir) {
        let entries;
        try {
            entries = fs.readdirSync(dir, { withFileTypes: true });
        } catch (e) {
            return;
        }
        for (const entry of entries) {
            const full = path.join(dir, entry.name);
            if (entry.isDirectory()) {
                if (['node_modules', 'target', 'build', 'dist', '.idea', '.vscode'].includes(entry.name)) {
                    continue;
                }
                // vendored 第三方库（chartjs 等自带语言字符串），不是本仓库生产代码
                if (entry.name === 'vendor' && dir.endsWith(path.join('static'))) {
                    continue;
                }
                walk(full);
            } else if (entry.isFile() && SCAN_EXTENSIONS.has(path.extname(entry.name).toLowerCase())) {
                scanFile(full);
            }
        }
    }

    function scanMainSubtree(moduleDir) {
        const main = path.join(moduleDir, 'src', 'main');
        if (fs.existsSync(main)) {
            walk(main);
        }
    }

    let entries;
    try {
        entries = fs.readdirSync(repoRoot, { withFileTypes: true });
    } catch (e) {
        return hits;
    }
    for (const entry of entries) {
        const full = path.join(repoRoot, entry.name);
        if (!entry.isDirectory()) {
            continue;
        }
        if (/^pixivdownload-/.test(entry.name)) {
            scanMainSubtree(full);
        } else if (entry.name === 'plugin-templates') {
            let templates;
            try {
                templates = fs.readdirSync(full, { withFileTypes: true });
            } catch (e) {
                continue;
            }
            for (const template of templates) {
                if (template.isDirectory() && !template.name.startsWith('.')) {
                    scanMainSubtree(path.join(full, template.name));
                }
            }
        } else if (entry.name === 'scripts') {
            walk(full);
        }
    }
    return hits;
}

export {  scan, isAllowed, buildPatterns  };

export default { scan, isAllowed, buildPatterns };
