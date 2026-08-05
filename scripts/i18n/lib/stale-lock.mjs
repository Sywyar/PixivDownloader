'use strict';
/**
 * stale 翻译锁文件（i18n/catalog-lock.json）。
 * 记录 (locale, module, baseName, key) 的已审核 source hash 与 translation hash；
 * hash 基于解析、反转义后的规范值（统一换行、去除首尾空白），不受文件排序与注释变化影响。
 */

import fs from 'fs';
import path from 'path';
import crypto from 'crypto';
import { canonicalValue } from './properties-parser.mjs';

const LOCK_PATH = path.join('i18n', 'catalog-lock.json');
const LOCK_VERSION = 1;

function hashValue(value) {
    return crypto.createHash('sha256').update(canonicalValue(value), 'utf8').digest('hex');
}

function load(repoRoot) {
    const file = path.join(repoRoot, LOCK_PATH);
    let raw;
    try {
        raw = fs.readFileSync(file, 'utf8');
    } catch (e) {
        return { version: LOCK_VERSION, entries: [] };
    }
    try {
        const parsed = JSON.parse(raw);
        return {
            version: parsed.version ?? LOCK_VERSION,
            entries: Array.isArray(parsed.entries) ? parsed.entries : [],
        };
    } catch (e) {
        throw new Error('cannot parse i18n/catalog-lock.json: ' + e.message);
    }
}

function save(repoRoot, lock) {
    const file = path.join(repoRoot, LOCK_PATH);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    const payload = {
        version: LOCK_VERSION,
        note: '已审核翻译基线：acceptedSourceHash 变化即该 key 进入 stale，需重新审核翻译后执行 i18n:accept。',
        entries: [...lock.entries].sort(compareEntries),
    };
    fs.writeFileSync(file, JSON.stringify(payload, null, 2) + '\n', 'utf8');
}

function compareEntries(a, b) {
    return String(a.locale).localeCompare(String(b.locale))
        || String(a.module).localeCompare(String(b.module))
        || String(a.baseName).localeCompare(String(b.baseName))
        || String(a.key).localeCompare(String(b.key));
}

function entryKey(entry) {
    return entry.locale + '\u0000' + entry.module + '\u0000' + entry.baseName + '\u0000' + entry.key;
}

function index(lock) {
    const map = new Map();
    for (const entry of lock.entries) {
        map.set(entryKey(entry), entry);
    }
    return map;
}

export {  load, save, hashValue, entryKey, index, LOCK_PATH  };

export default { load, save, hashValue, entryKey, index, LOCK_PATH };
