'use strict';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import catalogLib from '../lib/catalog.mjs';

const VALID = `{
  "schemaVersion": 1,
  "sourceLocale": "zh-CN",
  "defaultLocale": "zh-CN",
  "fallbackLocale": "en-US",
  "languageCookieName": "pixiv_lang",
  "languageParameterName": "lang",
  "locales": [
    {"tag": "zh-CN", "nativeName": "简体中文", "resourceSuffix": "", "status": "source", "direction": "ltr", "aliases": ["zh", "zh-Hans"]},
    {"tag": "en-US", "nativeName": "English", "resourceSuffix": "en", "status": "supported", "direction": "ltr", "aliases": ["en"]},
    {"tag": "ja-JP", "nativeName": "日本語", "resourceSuffix": "ja", "status": "candidate", "direction": "ltr", "aliases": ["ja"]},
    {"tag": "es-ES", "nativeName": "Español", "resourceSuffix": "es", "status": "disabled", "direction": "ltr", "aliases": ["es"]}
  ]
}`;

function withLocales(localesJson, patch = {}) {
    const base = `{
  "schemaVersion": 1,
  "sourceLocale": "zh-CN",
  "defaultLocale": "zh-CN",
  "fallbackLocale": "en-US",
  "languageCookieName": "pixiv_lang",
  "languageParameterName": "lang",
  "locales": [${localesJson}]
}`;
    let out = base;
    for (const [from, to] of Object.entries(patch)) {
        out = out.replace(from, to);
    }
    return out;
}

test('合法 catalog 成功读取，source/default/fallback 正确', () => {
    const catalog = catalogLib.validate(VALID);
    assert.equal(catalog.schemaVersion, 1);
    assert.equal(catalog.sourceLocale, 'zh-CN');
    assert.equal(catalog.defaultLocale, 'zh-CN');
    assert.equal(catalog.fallbackLocale, 'en-US');
    assert.equal(catalog.languageCookieName, 'pixiv_lang');
    assert.equal(catalog.languageParameterName, 'lang');
    assert.equal(catalog.locales.length, 4);
});

test('schemaVersion 不可识别立即失败', () => {
    assert.throws(() => catalogLib.validate(VALID.replace('"schemaVersion": 1', '"schemaVersion": 2')),
        /schemaVersion/);
});

test('非法 JSON / 非对象 / 空 locales 立即失败', () => {
    assert.throws(() => catalogLib.validate('not json'), /not valid JSON/);
    assert.throws(() => catalogLib.validate('[]'), /must be a JSON object/);
    assert.throws(() => catalogLib.validate('{"schemaVersion": 1, "locales": []}'), /(locales array|sourceLocale)/);
});

test('tag 非法 / 非规范化立即失败', () => {
    assert.throws(() => catalogLib.validate(withLocales(`
        {"tag": "123", "nativeName": "x", "resourceSuffix": "", "status": "source", "direction": "ltr", "aliases": []},
        {"tag": "en-US", "nativeName": "English", "resourceSuffix": "en", "status": "supported", "direction": "ltr", "aliases": []}`)),
        /invalid locale tag/);
    assert.throws(() => catalogLib.validate(withLocales(`
        {"tag": "zh-cn", "nativeName": "x", "resourceSuffix": "", "status": "source", "direction": "ltr", "aliases": []},
        {"tag": "en-US", "nativeName": "English", "resourceSuffix": "en", "status": "supported", "direction": "ltr", "aliases": []}`)),
        /not canonical/);
});

test('tag 重复 / alias 冲突 / resourceSuffix 冲突立即失败', () => {
    assert.throws(() => catalogLib.validate(withLocales(`
        {"tag": "zh-CN", "nativeName": "x", "resourceSuffix": "", "status": "source", "direction": "ltr", "aliases": []},
        {"tag": "zh-CN", "nativeName": "y", "resourceSuffix": "x", "status": "supported", "direction": "ltr", "aliases": []},
        {"tag": "en-US", "nativeName": "English", "resourceSuffix": "en", "status": "supported", "direction": "ltr", "aliases": []}`)),
        /duplicate locale tag/);
    assert.throws(() => catalogLib.validate(withLocales(`
        {"tag": "zh-CN", "nativeName": "x", "resourceSuffix": "", "status": "source", "direction": "ltr", "aliases": ["zh"]},
        {"tag": "en-US", "nativeName": "English", "resourceSuffix": "", "status": "supported", "direction": "ltr", "aliases": []}`)),
        /(conflicting resourceSuffix|non-empty resourceSuffix)/);
    assert.throws(() => catalogLib.validate(withLocales(`
        {"tag": "zh-CN", "nativeName": "x", "resourceSuffix": "", "status": "source", "direction": "ltr", "aliases": ["zh"]},
        {"tag": "en-US", "nativeName": "English", "resourceSuffix": "en", "status": "supported", "direction": "ltr", "aliases": ["zh"]}`)),
        /alias conflict/);
});

test('恰好一个 source；fallback 必须 source/supported；default 必须可见', () => {
    assert.throws(() => catalogLib.validate(withLocales(`
        {"tag": "zh-CN", "nativeName": "x", "resourceSuffix": "", "status": "source", "direction": "ltr", "aliases": []},
        {"tag": "zh-HK", "nativeName": "y", "resourceSuffix": "zh-HK", "status": "source", "direction": "ltr", "aliases": []},
        {"tag": "en-US", "nativeName": "English", "resourceSuffix": "en", "status": "supported", "direction": "ltr", "aliases": []}`)),
        /(exactly one source|source locale .* empty resourceSuffix|conflicting resourceSuffix)/);
    assert.throws(() => catalogLib.validate(withLocales(`
        {"tag": "zh-CN", "nativeName": "x", "resourceSuffix": "", "status": "source", "direction": "ltr", "aliases": []},
        {"tag": "en-US", "nativeName": "English", "resourceSuffix": "en", "status": "candidate", "direction": "ltr", "aliases": []}`)),
        /fallbackLocale must be source or supported/);
    assert.throws(() => catalogLib.validate(withLocales(`
        {"tag": "zh-CN", "nativeName": "x", "resourceSuffix": "", "status": "source", "direction": "ltr", "aliases": []},
        {"tag": "en-US", "nativeName": "English", "resourceSuffix": "en", "status": "supported", "direction": "ltr", "aliases": []}`)
        .replace('"defaultLocale": "zh-CN"', '"defaultLocale": "fr-FR"')),
        /defaultLocale/);
});

test('未知状态 / 非法 direction / 空 nativeName 立即失败', () => {
    assert.throws(() => catalogLib.validate(withLocales(`
        {"tag": "zh-CN", "nativeName": "x", "resourceSuffix": "", "status": "weird", "direction": "ltr", "aliases": []},
        {"tag": "en-US", "nativeName": "English", "resourceSuffix": "en", "status": "supported", "direction": "ltr", "aliases": []}`)),
        /unknown locale status/);
    assert.throws(() => catalogLib.validate(withLocales(`
        {"tag": "zh-CN", "nativeName": "x", "resourceSuffix": "", "status": "source", "direction": "rtl+", "aliases": []},
        {"tag": "en-US", "nativeName": "English", "resourceSuffix": "en", "status": "supported", "direction": "ltr", "aliases": []}`)),
        /invalid direction/);
    assert.throws(() => catalogLib.validate(withLocales(`
        {"tag": "zh-CN", "nativeName": "  ", "resourceSuffix": "", "status": "source", "direction": "ltr", "aliases": []},
        {"tag": "en-US", "nativeName": "English", "resourceSuffix": "en", "status": "supported", "direction": "ltr", "aliases": []}`)),
        /empty nativeName/);
});

test('fallbackChain：目标 → fallback → source（去重）', () => {
    const catalog = catalogLib.validate(VALID);
    assert.deepEqual(catalogLib.fallbackChain(catalog, 'ja-JP'), ['ja-JP', 'en-US', 'zh-CN']);
    assert.deepEqual(catalogLib.fallbackChain(catalog, 'en-US'), ['en-US', 'zh-CN']);
    assert.deepEqual(catalogLib.fallbackChain(catalog, 'zh-CN'), ['zh-CN', 'en-US']);
    assert.deepEqual(catalogLib.fallbackChain(catalog, 'fr-FR'), ['zh-CN', 'en-US']);
});

test('真实仓库 locales.json 可加载', () => {
    const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
    const catalog = catalogLib.load(repoRoot);
    assert.equal(catalog.sourceLocale, 'zh-CN');
    assert.equal(catalog.fallbackLocale, 'en-US');
    assert.equal(catalog.locales.length, 2);
});

test('共享 fixture：Java 与 Node 必须同时拒绝同一批非法 catalog（15 例）', () => {
    const fixturesDir = path.join(path.dirname(fileURLToPath(import.meta.url)), 'fixtures');
    const shared = JSON.parse(fs.readFileSync(path.join(fixturesDir, 'catalog-invalid-shared.json'), 'utf8'));
    assert.ok(shared.cases.length >= 15);
    for (const c of shared.cases) {
        assert.throws(() => catalogLib.validate(JSON.stringify(c.json)),
            new RegExp(c.expected), 'Node 必须拒绝: ' + c.id);
    }
    // 合法 fixture 必须通过
    const valid = fs.readFileSync(path.join(fixturesDir, 'catalog-valid.json'), 'utf8');
    const catalog = catalogLib.validate(valid);
    assert.equal(catalog.locales.length, 5);
    assert.equal(catalog.byAlias.get('zh-Hant-HK').tag, 'zh-HK');
});
