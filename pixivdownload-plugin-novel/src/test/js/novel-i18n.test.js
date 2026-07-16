'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');

const I18N = path.join(__dirname, '..', '..', 'main', 'resources', 'i18n', 'web');
const ZH = fs.readFileSync(path.join(I18N, 'novel.properties'), 'utf8');
const EN = fs.readFileSync(path.join(I18N, 'novel_en.properties'), 'utf8');

function propertyKeys(source) {
    return new Set(String(source).split(/\r?\n/)
        .map(line => line.trim())
        .filter(line => line && !line.startsWith('#') && line.includes('='))
        .map(line => line.slice(0, line.indexOf('=')).trim()));
}

const zhKeys = propertyKeys(ZH);
const enKeys = propertyKeys(EN);
const requiredKeys = [
    'batch.search.summary.current-page',
    'batch.search.summary.total',
    'batch.search.summary.returned',
    'batch.search.summary.fetched'
];

assert.deepStrictEqual(Array.from(zhKeys).sort(), Array.from(enKeys).sort(),
    'Novel 中英文 bundle 的 key 集合应一致');
requiredKeys.forEach(key => {
    assert.ok(zhKeys.has(key), 'Novel 中文 bundle 缺少 ' + key);
    assert.ok(enKeys.has(key), 'Novel 英文 bundle 缺少 ' + key);
});

console.log('novel-i18n.test.js: ' + (requiredKeys.length * 2 + 1) + ' assertions passed ✓');
