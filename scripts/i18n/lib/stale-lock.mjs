'use strict';
/**
 * stale 翻译锁文件（i18n/catalog-lock.json）。
 *
 * 锁同时约束两个 hash：
 *   acceptedSourceHash      —— 已审核的源文案 hash
 *   acceptedTranslationHash —— 已审核的翻译 hash
 * 只有二者都与当前内容一致（accepted 状态）才计入正式语言 translated 覆盖率。
 *
 * hash 基于解析、反转义后的规范值（仅 CRLF → LF，不 trim）：
 * 前导 / 尾随空白、转义空格与换行都保留，仅空白变化不会被错误视为相同翻译。
 *
 * 磁盘按 (module, baseName, key) 聚合；每个翻译三元组依次是 locale、源 hash、译文 hash。
 * 读取时严格校验：version、字段、64 位十六进制 hash、重复 entry 全部 fail-fast；
 * 保存时确定性排序 + 临时文件原子替换（避免写一半损坏）。
 */

import fs from 'fs';
import path from 'path';
import crypto from 'crypto';
import { canonicalValue } from './properties-parser.mjs';

const LOCK_PATH = path.join('i18n', 'catalog-lock.json');
const LOCK_VERSION = 2;

const REQUIRED_FIELDS = [
    'locale', 'module', 'baseName', 'key', 'acceptedSourceHash', 'acceptedTranslationHash',
];

const HEX64 = /^[0-9a-f]{64}$/;

function hashValue(value) {
    return crypto.createHash('sha256').update(canonicalValue(value), 'utf8').digest('hex');
}

/**
 * 结构校验（不依赖 catalog / bundle，只校验锁文件自身形态）。
 * 非法即抛出 Error；重复 entry 由 index() 前先调用本函数保证不静默覆盖。
 */
function validateStructure(lock) {
    if (!lock || typeof lock !== 'object' || Array.isArray(lock)) {
        throw new Error('catalog-lock.json: root must be a JSON object');
    }
    if (lock.version !== LOCK_VERSION) {
        throw new Error('catalog-lock.json: unsupported lock version ' + lock.version
            + ' (expected ' + LOCK_VERSION + ')');
    }
    if (!Array.isArray(lock.entries)) {
        throw new Error('catalog-lock.json: entries must be an array');
    }
    const seen = new Set();
    for (const entry of lock.entries) {
        if (!entry || typeof entry !== 'object' || Array.isArray(entry)) {
            throw new Error('catalog-lock.json: each entry must be a JSON object');
        }
        for (const field of REQUIRED_FIELDS) {
            if (typeof entry[field] !== 'string' || !entry[field].trim()) {
                throw new Error('catalog-lock.json: entry requires non-empty string field "' + field + '"');
            }
        }
        for (const field of ['acceptedSourceHash', 'acceptedTranslationHash']) {
            if (!HEX64.test(entry[field])) {
                throw new Error('catalog-lock.json: ' + field + ' must be a 64-digit hex SHA-256 for '
                    + entry.locale + '/' + entry.module + '/' + entry.baseName + '/' + entry.key
                    + ' (got "' + entry[field] + '")');
            }
        }
        const key = entryKey(entry);
        if (seen.has(key)) {
            throw new Error('catalog-lock.json: duplicate lock entry for '
                + entry.locale + ' / ' + entry.module + ' / ' + entry.baseName + ' / ' + entry.key);
        }
        seen.add(key);
    }
}

function load(repoRoot) {
    const file = path.join(repoRoot, LOCK_PATH);
    let raw;
    try {
        raw = fs.readFileSync(file, 'utf8');
    } catch (e) {
        return { version: LOCK_VERSION, entries: [] };
    }
    let parsed;
    try {
        parsed = JSON.parse(raw);
    } catch (e) {
        throw new Error('cannot parse i18n/catalog-lock.json: ' + e.message);
    }
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
        throw new Error('catalog-lock.json: root must be a JSON object');
    }
    if (parsed.version === 1) {
        const lock = { version: LOCK_VERSION, entries: parsed.entries };
        validateStructure(lock);
        return lock;
    }
    if (parsed.version !== LOCK_VERSION) {
        throw new Error('catalog-lock.json: unsupported lock version ' + parsed.version
            + ' (expected ' + LOCK_VERSION + ')');
    }
    if (!Array.isArray(parsed.entries)) {
        throw new Error('catalog-lock.json: entries must be an array');
    }
    const entries = [];
    const seenGroups = new Set();
    for (const group of parsed.entries) {
        if (!group || typeof group !== 'object' || Array.isArray(group)
            || !['module', 'baseName', 'key'].every((field) => typeof group[field] === 'string' && group[field].trim())
            || !Array.isArray(group.translations) || group.translations.length === 0) {
            throw new Error('catalog-lock.json: invalid grouped entry');
        }
        const groupKey = JSON.stringify([group.module, group.baseName, group.key]);
        if (seenGroups.has(groupKey)) {
            throw new Error('catalog-lock.json: duplicate lock entry for '
                + group.module + ' / ' + group.baseName + ' / ' + group.key);
        }
        seenGroups.add(groupKey);
        for (const hashes of group.translations) {
            if (!Array.isArray(hashes) || hashes.length !== 3) {
                throw new Error('catalog-lock.json: translation entry must contain locale and two hashes');
            }
            entries.push({
                locale: hashes[0], module: group.module, baseName: group.baseName, key: group.key,
                acceptedSourceHash: hashes[1], acceptedTranslationHash: hashes[2],
            });
        }
    }
    const lock = { version: LOCK_VERSION, entries };
    validateStructure(lock);
    return lock;
}

function save(repoRoot, lock) {
    validateStructure(lock);
    const file = path.join(repoRoot, LOCK_PATH);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    const groups = new Map();
    for (const entry of lock.entries) {
        const groupKey = JSON.stringify([entry.module, entry.baseName, entry.key]);
        if (!groups.has(groupKey)) {
            groups.set(groupKey, {
                module: entry.module, baseName: entry.baseName, key: entry.key, translations: [],
            });
        }
        groups.get(groupKey).translations.push([
            entry.locale, entry.acceptedSourceHash, entry.acceptedTranslationHash,
        ]);
    }
    const entries = [...groups.values()].sort((a, b) =>
        a.module.localeCompare(b.module) || a.baseName.localeCompare(b.baseName)
            || a.key.localeCompare(b.key));
    for (const group of entries) {
        group.translations.sort((a, b) => a[0].localeCompare(b[0]));
    }
    const payload = {
        version: LOCK_VERSION,
        note: '已审核翻译基线：accept 同时记录 acceptedSourceHash 与 acceptedTranslationHash，二者都匹配当前内容才算 accepted。',
        entries,
    };
    // 每个 key 单独一行，保留可定位的 Git diff，避免数组缩进放大生成文件。
    const serialized = [
        '{',
        '  "version": ' + payload.version + ',',
        '  "note": ' + JSON.stringify(payload.note) + ',',
        '  "entries": [',
        ...entries.map((entry, i) => '    ' + JSON.stringify(entry) + (i < entries.length - 1 ? ',' : '')),
        '  ]',
        '}',
        '',
    ].join('\n');
    const tmp = path.join(path.dirname(file), '.catalog-lock.json.tmp-' + process.pid);
    fs.writeFileSync(tmp, serialized, 'utf8');
    try {
        fs.renameSync(tmp, file);
    } catch (e) {
        fs.rmSync(tmp, { force: true });
        throw e;
    }
}

function entryKey(entry) {
    return entry.locale + '\u0000' + entry.module + '\u0000' + entry.baseName + '\u0000' + entry.key;
}

function index(lock) {
    validateStructure(lock);
    const map = new Map();
    for (const entry of lock.entries) {
        map.set(entryKey(entry), entry);
    }
    return map;
}

/**
 * 对照 catalog 与已发现 bundle 校验锁条目：
 * - locale 必须存在于 catalog（source locale 不得有翻译审核 entry）；
 * - (module, baseName) 必须能映射到当前源 bundle；key 必须存在于源 bundle；
 * - 不满足以上任一条件的条目是 orphan（对应资源 / key 已删除）。
 * @returns {{orphans: Array<Object>, errors: Array<string>}}
 *   errors 是必须人工处理的配置错误（如 source locale entry、未知 locale）；
 *   orphans 可通过 prune 一键清理。
 */
function validateAgainstCatalog(lock, catalog, bundles, sourceMaps) {
    const errors = [];
    const orphans = [];
    for (const entry of lock.entries) {
        const descriptor = catalog.byTag.get(entry.locale) || null;
        if (!descriptor) {
            errors.push('lock entry references unknown locale "' + entry.locale
                + '" (' + entry.module + '/' + entry.baseName + '/' + entry.key + ')');
            orphans.push(entry);
            continue;
        }
        if (descriptor.status === 'source') {
            errors.push('lock entry must not exist for the source locale "' + entry.locale
                + '" (' + entry.module + '/' + entry.baseName + '/' + entry.key + ')');
            continue;
        }
        const bundle = findBundle(bundles, entry.module, entry.baseName);
        if (!bundle) {
            orphans.push(entry);
            continue;
        }
        const sourceMap = sourceMaps ? sourceMaps.get(bundle.bundleId) : null;
        if (sourceMap && !sourceMap.has(entry.key)) {
            orphans.push(entry);
        }
    }
    return { orphans, errors };
}

function findBundle(bundles, module, baseName) {
    if (!bundles) {
        return null;
    }
    for (const bundle of bundles.values()) {
        if (bundle.module === module && bundle.baseName === baseName) {
            return bundle;
        }
    }
    return null;
}

/**
 * 删除 orphan 条目并原子保存。
 * disabled locale 的旧条目一并清理：disabled 语言处于翻译覆盖与审核范围之外，
 * 永远不能再被 accept（accept 拒绝 disabled），因此其历史锁记录是无用残留（legacy）。
 * @returns {number} 清理的条目数
 */
function prune(repoRoot, lock, catalog, bundles, sourceMaps) {
    const { orphans, errors } = validateAgainstCatalog(lock, catalog, bundles, sourceMaps);
    const legacyKeys = new Set();
    for (const entry of lock.entries) {
        const descriptor = catalog.byTag.get(entry.locale) || null;
        if (descriptor && descriptor.status === 'disabled') {
            legacyKeys.add(entryKey(entry));
        }
    }
    const dropKeys = new Set([...orphans.map(entryKey), ...legacyKeys]);
    if (dropKeys.size === 0) {
        return 0;
    }
    const before = lock.entries.length;
    lock.entries = lock.entries.filter((entry) => !dropKeys.has(entryKey(entry)));
    save(repoRoot, lock);
    return before - lock.entries.length;
}

export {
    load, save, hashValue, entryKey, index, validateAgainstCatalog, prune, validateStructure,
    LOCK_PATH, LOCK_VERSION,
};

export default {
    load, save, hashValue, entryKey, index, validateAgainstCatalog, prune, validateStructure,
    LOCK_PATH, LOCK_VERSION,
};
