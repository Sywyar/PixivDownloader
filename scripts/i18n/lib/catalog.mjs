'use strict';
/**
 * locales.json 目录加载与校验（与 Java LocaleCatalogLoader 的校验规则保持一致）。
 * 这是 Node 侧唯一读取语言清单的入口：检查器、静态生成器、硬编码守卫都从这里取 tag / alias / 后缀。
 */

import fs from 'fs';
import path from 'path';

const CATALOG_PATH = path.join(
    'pixivdownload-app', 'src', 'main', 'resources', 'i18n', 'locales.json');
const EXPECTED_SCHEMA_VERSION = 1;
const STATUSES = new Set(['source', 'supported', 'candidate', 'disabled']);

function canonicalTag(tag) {
    if (typeof tag !== 'string' || !tag.trim()) {
        return null;
    }
    const normalized = tag.trim().replace(/_/g, '-');
    const parts = normalized.split('-');
    if (parts.length === 0 || !/^[a-zA-Z]{2,8}$/.test(parts[0])) {
        return null;
    }
    let out = parts[0].toLowerCase();
    for (let i = 1; i < parts.length; i += 1) {
        const part = parts[i];
        if (/^[a-zA-Z]{4}$/.test(part)) {
            out += '-' + part.toLowerCase(); // script
        } else if (/^[a-zA-Z]{2}$/.test(part) || /^[0-9]{3}$/.test(part)) {
            out += '-' + part.toUpperCase(); // region
        } else if (/^[a-zA-Z0-9]{1,8}$/.test(part)) {
            out += '-' + part; // variant / extension
        } else {
            return null;
        }
    }
    return out;
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
    const byAlias = new Map();
    const suffixes = new Set();
    const descriptors = [];
    let sourceCount = 0;

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
        if (tag !== item.tag.trim().replace(/_/g, '-')) {
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
        if (suffixes.has(item.resourceSuffix)) {
            throw new Error('conflicting resourceSuffix for locale ' + tag + ': ' + item.resourceSuffix);
        }
        suffixes.add(item.resourceSuffix);
        if (item.status === 'source') {
            sourceCount += 1;
        }
        const aliases = [];
        if (item.aliases != null) {
            if (!Array.isArray(item.aliases)) {
                throw new Error('locale ' + tag + ': aliases must be an array');
            }
            for (const alias of item.aliases) {
                if (typeof alias !== 'string' || !alias.trim()) {
                    throw new Error('locale ' + tag + ': alias must be a non-empty string');
                }
                const key = alias.trim().toLowerCase();
                if (byAlias.has(key)) {
                    throw new Error('alias conflict for \'' + alias + '\' between '
                        + byAlias.get(key).tag + ' and ' + tag);
                }
                byAlias.set(key, item);
                aliases.push(alias.trim());
            }
        }
        descriptors.push({
            tag,
            nativeName: item.nativeName.trim(),
            resourceSuffix: item.resourceSuffix.trim(),
            status: item.status,
            direction: item.direction,
            aliases,
        });
    }

    if (sourceCount !== 1) {
        throw new Error('exactly one source locale is required, found ' + sourceCount);
    }
    const source = byTag.get(root.sourceLocale.trim().replace(/_/g, '-'));
    if (!source) {
        throw new Error('sourceLocale does not match any locale tag: ' + root.sourceLocale);
    }
    if (source.status !== 'source') {
        throw new Error('sourceLocale must point to the source locale');
    }
    const def = byTag.get(root.defaultLocale.trim().replace(/_/g, '-'));
    if (!def) {
        throw new Error('defaultLocale does not match any locale tag: ' + root.defaultLocale);
    }
    if (def.status !== 'source' && def.status !== 'supported') {
        throw new Error('defaultLocale must be a visible locale: ' + def.tag);
    }
    const fallback = byTag.get(root.fallbackLocale.trim().replace(/_/g, '-'));
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

export {  load, validate, canonicalTag, descriptorByTag, fallbackChain, CATALOG_PATH  };

export default { load, validate, canonicalTag, descriptorByTag, fallbackChain, CATALOG_PATH };
