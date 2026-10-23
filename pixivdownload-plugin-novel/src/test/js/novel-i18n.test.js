'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const test = require('node:test');

const I18N = path.join(__dirname, '..', '..', 'main', 'resources', 'i18n', 'web');
const ZH = fs.readFileSync(path.join(I18N, 'novel.properties'), 'utf8');
const EN = fs.readFileSync(path.join(I18N, 'novel_en.properties'), 'utf8');
const ROOT = path.join(__dirname, '..', '..', '..', '..');
const WORKBENCH_I18N = path.join(ROOT, 'pixivdownload-plugin-download-workbench', 'src', 'main',
    'resources', 'i18n', 'web');
const BATCH_ZH = fs.readFileSync(path.join(WORKBENCH_I18N, 'batch.properties'), 'utf8');
const BATCH_EN = fs.readFileSync(path.join(WORKBENCH_I18N, 'batch_en.properties'), 'utf8');

const requiredKeys = [
    'batch.search.summary.current-page',
    'batch.search.summary.total',
    'batch.search.summary.returned',
    'batch.search.summary.fetched'
];
const ownerStatusKeys = [
    'queue.translate.label',
    'queue.message.translate-waiting',
    'queue.message.translate-wait-series',
    'queue.message.translate-resolving',
    'queue.message.translating',
    'queue.message.translate-merging',
    'queue.message.translate-same-lang',
    'queue.message.translate-done',
    'queue.message.translate-failed'
];
requiredKeys.push(...ownerStatusKeys);

test('小说搜索与翻译状态文案由小说命名空间持有', async () => {
    const {parse} = await import('../../../../scripts/i18n/lib/properties-parser.mjs');
    for (const [label, novel, batch] of [['中文', ZH, BATCH_ZH], ['英文', EN, BATCH_EN]]) {
        const novelKeys = new Set(parse(novel).entries.map(entry => entry.key));
        const batchKeys = new Set(parse(batch).entries.map(entry => entry.key));
        for (const key of requiredKeys) {
            assert.ok(novelKeys.has(key), `Novel ${label} bundle 缺少 ${key}`);
        }
        for (const key of ownerStatusKeys) {
            assert.ok(!batchKeys.has(key), `Workbench ${label} bundle 不得拥有小说状态文案 ${key}`);
        }
    }
});
