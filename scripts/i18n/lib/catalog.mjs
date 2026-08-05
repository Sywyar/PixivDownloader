'use strict';
/**
 * locales.json 目录加载与校验（与 Java LocaleCatalogLoader 的校验规则保持一致）。
 * 这是 Node 侧唯一读取语言清单的入口：检查器、静态生成器、硬编码守卫都从这里取 tag / alias / 后缀。
 *
 * BCP 47 规范化使用 Intl.getCanonicalLocales（标准实现，与 Java Locale.forLanguageTag
 * 在同一 fixture 上保持一致）：zh-hant → zh-Hant、sr-latn → sr-Latn、en-us → en-US、pt-br → pt-BR。
 *
 * 校验规则（Java / Node 共享同一组 fixture）：
 * - tag 必须是规范形式；sourceLocale / defaultLocale / fallbackLocale 同样规范；
 * - alias 规范化后不得重复；alias 不得与任意其他正式 tag 冲突；alias 与自己的 tag 重复拒绝；
 *   tag 不得与之前声明的 alias 冲突；声明顺序不影响冲突检查结果；
 * - resourceSuffix 先 trim 再检查唯一性；source locale 的 resourceSuffix 必须为空；
 *   其他语言不得使用空 suffix；suffix 不得含路径分隔符、`..` 或非法文件字符；
 * - direction 仅 ltr / rtl；exactly one source；default 必须可见；fallback 必须 source 或 supported。
 */

import fs from 'fs';
import path from 'path';

const CATALOG_PATH = path.join(
    'pixivdownload-app', 'src', 'main', 'resources', 'i18n', 'locales.json');
const EXPECTED_SCHEMA_VERSION = 1;
const STATUSES = new Set(['source', 'supported', 'candidate', 'disabled']);

const ILLEGAL_SUFFIX = /[/\\:*?"<>|\u0000-\u001f]|\.\./;

/**
 * BCP 47 规范化：容忍 `_` / `-` 混用与大小写差异，返回 Intl 的规范形式。
 * 非法 tag 返回 null。
 */
function canonicalTag(tag) {
    if (typeof tag !== 'string' || !tag.trim()) {
        return null;
    }
    const normalized = tag.trim().replace(/_/g, '-');
    try {
        const canonical = Intl.getCanonicalLocales(normalized);
        if (canonical.length !== 1) {
            return null;
        }
        return canonical[0];
    } catch (e) {
        return null;
    }
}

function validate(raw) {
    let root;
    try {
        root = JSON.parse(raw);
    } catch (e) {
        throw new Error('locale catalog is not valid JSON: ' + e.message);
    }
    if (!root || typeof root !== 'object' || Array.isArray(root)) {
        throw new Error('locale catalog must be a JSON object');
    }
    if (root.schemaVersion !== EXPECTED_SCHEMA_VERSION) {
        throw new Error('unsupported locale catalog schemaVersion: ' + root.schemaVersion
            + ' (expected ' + EXPECTED_SCHEMA_VERSION + ')');
    }
    for (const field of ['sourceLocale', 'defaultLocale', 'fallbackLocale',
        'languageCookieName', 'languageParameterName']) {
        if (typeof root[field] !== 'string' || !root[field].trim()) {
            throw new Error('locale catalog requires non-empty string field: ' + field);
        }
    }
    if (!Array.isArray(root.locales) || root.locales.length === 0) {
        throw new Error('locale catalog requires a non-empty locales array');
    }

    const byTag = new Map();
    const descriptors = [];
    const suffixes = new Map();
    let sourceCount = 0;

    // 第一遍：tag 与 suffix 校验（tag 集合先完整建立，alias 冲突检查与声明顺序无关）
    for (const item of root.locales) {
        if (!item || typeof item !== 'object') {
            throw new Error('locale entry must be a JSON object');
        }
        for (const field of ['tag', 'nativeName', 'resourceSuffix', 'status', 'direction']) {
            if (typeof item[field] !== 'string') {
                throw new Error('locale entry requires string field: ' + field);
            }
        }
        const tag = canonicalTag(item.tag);
        if (tag === null) {
            throw new Error('invalid locale tag: ' + item.tag);
        }
        if (tag !== item.tag.trim()) {
            throw new Error('locale tag is not canonical (expected "' + tag + '", got "' + item.tag + '")');
        }
        if (!item.nativeName.trim()) {
            throw new Error('locale ' + tag + ' has empty nativeName');
        }
        if (!STATUSES.has(item.status)) {
            throw new Error('unknown locale status: ' + item.status + ' (locale ' + tag + ')');
        }
        if (item.direction !== 'ltr' && item.direction !== 'rtl') {
            throw new Error('locale ' + tag + ' has invalid direction: ' + item.direction);
        }
        if (byTag.has(tag)) {
            throw new Error('duplicate locale tag: ' + tag);
        }
        byTag.set(tag, item);

        const suffix = item.resourceSuffix.trim();
        if (ILLEGAL_SUFFIX.test(suffix)) {
            throw new Error('locale ' + tag + ' has illegal resourceSuffix "' + item.resourceSuffix
                + '" (path separators, ".." and invalid file characters are not allowed)');
        }
        if (item.status === 'source' && suffix !== '') {
            throw new Error('source locale ' + tag + ' must use an empty resourceSuffix');
        }
        if (item.status !== 'source' && suffix === '') {
            throw new Error('locale ' + tag + ' must use a non-empty resourceSuffix');
        }
        if (suffixes.has(suffix)) {
            throw new Error('conflicting resourceSuffix for locale ' + tag + ': ' + suffix
                + ' (also used by ' + suffixes.get(suffix) + ')');
        }
        suffixes.set(suffix, tag);
        if (item.status === 'source') {
            sourceCount += 1;
        }
        if (item.aliases != null && !Array.isArray(item.aliases)) {
            throw new Error('locale ' + tag + ': aliases must be an array');
        }
        descriptors.push({
            tag,
            nativeName: item.nativeName.trim(),
            resourceSuffix: suffix,
            status: item.status,
            direction: item.direction,
            aliases: [],
        });
    }

    // 第二遍：alias 规范化与冲突检查（对完整 tag 集合，声明顺序无关）
    const byAlias = new Map();
    for (let i = 0; i < root.locales.length; i += 1) {
        const item = root.locales[i];
        const tag = descriptors[i].tag;
        const aliases = [];
        for (const alias of item.aliases == null ? [] : item.aliases) {
            if (typeof alias !== 'string' || !alias.trim()) {
                throw new Error('locale ' + tag + ': alias must be a non-empty string');
            }
            const canonical = canonicalTag(alias);
            if (canonical === null) {
                throw new Error('locale ' + tag + ': alias is not a valid BCP 47 tag: "' + alias + '"');
            }
            if (canonical === tag) {
                throw new Error('locale ' + tag + ': alias duplicates its own tag: "' + alias + '"');
            }
            if (byTag.has(canonical)) {
                throw new Error('alias "' + alias + '" of locale ' + tag
                    + ' conflicts with the tag of locale ' + byTag.get(canonical).tag);
            }
            if (byAlias.has(canonical)) {
                throw new Error('alias conflict for "' + alias + '" between '
                    + byAlias.get(canonical).tag + ' and ' + tag);
            }
            byAlias.set(canonical, { tag, raw: alias.trim() });
            aliases.push(alias.trim());
        }
        descriptors[i] = { ...descriptors[i], aliases };
    }

    if (sourceCount !== 1) {
        throw new Error('exactly one source locale is required, found ' + sourceCount);
    }
    const source = resolvePointer(root.sourceLocale, byTag, 'sourceLocale');
    if (!source) {
        throw new Error('sourceLocale does not match any locale tag: ' + root.sourceLocale);
    }
    if (source.status !== 'source') {
        throw new Error('sourceLocale must point to the source locale');
    }
    const def = resolvePointer(root.defaultLocale, byTag, 'defaultLocale');
    if (!def) {
        throw new Error('defaultLocale does not match any locale tag: ' + root.defaultLocale);
    }
    if (def.status !== 'source' && def.status !== 'supported') {
        throw new Error('defaultLocale must be a visible locale: ' + def.tag);
    }
    const fallback = resolvePointer(root.fallbackLocale, byTag, 'fallbackLocale');
    if (!fallback) {
        throw new Error('fallbackLocale does not match any locale tag: ' + root.fallbackLocale);
    }
    if (fallback.status !== 'source' && fallback.status !== 'supported') {
        throw new Error('fallbackLocale must be source or supported: ' + fallback.tag);
    }

    return {
        schemaVersion: root.schemaVersion,
        sourceLocale: source.tag,
        defaultLocale: def.tag,
        fallbackLocale: fallback.tag,
        languageCookieName: root.languageCookieName.trim(),
        languageParameterName: root.languageParameterName.trim(),
        locales: descriptors,
        byTag,
        byAlias,
    };
}

function load(repoRoot) {
    const file = path.join(repoRoot, CATALOG_PATH);
    let raw;
    try {
        raw = fs.readFileSync(file, 'utf8');
    } catch (e) {
        throw new Error('missing locale catalog resource: ' + CATALOG_PATH + ' (' + e.message + ')');
    }
    return validate(raw);
}

function descriptorByTag(catalog, tag) {
    const canonical = canonicalTag(tag);
    return catalog.byTag.get(canonical) || null;
}

/** 回退链：目标语言 → fallback → source（去重、保持顺序）。 */
function fallbackChain(catalog, tag) {
    const target = descriptorByTag(catalog, tag) || descriptorByTag(catalog, catalog.defaultLocale);
    const chain = [];
    for (const t of [target.tag, catalog.fallbackLocale, catalog.sourceLocale]) {
        if (!chain.includes(t)) {
            chain.push(t);
        }
    }
    return chain;
}

/**
 * 顶层语言指针：值必须是规范 tag 形式（与 Java findByTag 的精确匹配一致，
 * 不隐式规范化大小写 / 下划线）。
 */
function resolvePointer(fieldValue, byTag, field) {
    const normalized = String(fieldValue).trim().replace(/_/g, '-');
    const canonical = canonicalTag(fieldValue);
    if (canonical === null || canonical !== normalized) {
        throw new Error(field + ' is not canonical: ' + fieldValue);
    }
    return byTag.get(canonical) || null;
}

export { load, validate, canonicalTag, descriptorByTag, fallbackChain, CATALOG_PATH };

export default { load, validate, canonicalTag, descriptorByTag, fallbackChain, CATALOG_PATH };
