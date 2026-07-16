'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');

const I18N = path.join(__dirname, '..', '..', 'main', 'resources', 'i18n', 'web');
const ZH = fs.readFileSync(path.join(I18N, 'douyin.properties'), 'utf8');
const EN = fs.readFileSync(path.join(I18N, 'douyin_en.properties'), 'utf8');

function propertyKeys(source) {
    return new Set(String(source).split(/\r?\n/)
        .map(line => line.trim())
        .filter(line => line && !line.startsWith('#') && line.includes('='))
        .map(line => line.slice(0, line.indexOf('=')).trim()));
}

const zhKeys = propertyKeys(ZH);
const enKeys = propertyKeys(EN);
const requiredKeys = [
    'user.kind.works',
    'user.kind.liked',
    'user.visibility-hint',
    'user.empty.works',
    'user.empty.liked',
    'user.error.liked-hidden',
    'series.data-source.douyin',
    'series.browser.favorite-folders',
    'series.browser.favorite-folders.loading',
    'series.browser.favorite-folders.empty',
    'series.browser.favorite-folders.item',
    'series.type.favorite-folder',
    'schedule.source.account-favorite-folder.name',
    'schedule.source.account-favorite-folder.description',
    'schedule.field.folder-id',
    'error.stale-request',
    'search.summary.current-page',
    'search.summary.total',
    'search.summary.returned',
    'search.summary.fetched'
];

assert.deepStrictEqual(Array.from(zhKeys).sort(), Array.from(enKeys).sort(),
    'Douyin 中英文 bundle 的 key 集合应一致');
requiredKeys.forEach(key => {
    assert.ok(zhKeys.has(key), `Douyin 中文 bundle 缺少 ${key}`);
    assert.ok(enKeys.has(key), `Douyin 英文 bundle 缺少 ${key}`);
});

console.log(`douyin-i18n.test.js: ${requiredKeys.length * 2 + 1} assertions passed ✓`);
