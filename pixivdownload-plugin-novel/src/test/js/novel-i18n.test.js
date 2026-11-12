'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const test = require('node:test');
const vm = require('node:vm');

for (const [name, createTime, uploadTimestamp, expected] of [
    ['创建时间优先', 1000, 2000, 1000],
    ['旧记录回退上传时间', null, 2000, 2000],
    ['没有发布时间则省略', null, null, null],
]) {
    test('小说详情只保留发布时间：' + name, () => {
        const meta = {}, messages = [];
        const sandbox = {window: {PixivNovel: {}}, document: {getElementById: () => meta}, escapeHtml: String,
            pageI18n: {t(key, fallback, args) { messages.push({key, args}); return key; }}};
        vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../../main/resources/static/pixiv-novel/novel-content.js'), 'utf8'), sandbox);
        sandbox.renderMetaRow({wordCount: 12, createTime, uploadTimestamp, downloadTime: 3000, updateTime: 4000, reuploadTime: 5000});
        assert.deepEqual(messages.map(message => message.key), expected ? ['meta.word-count', 'meta.published'] : ['meta.word-count']);
        if (expected) assert.equal(messages[1].args.time, new Date(expected).toLocaleString());
        assert.match(meta.innerHTML, /meta.word-count/);
    });
}

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

test('切换界面语言后继续显示当前译文简介，空译文简介回退原文', async () => {
    const source = fs.readFileSync(path.join(I18N, '../../static/pixiv-novel/novel-content.js'), 'utf8');
    const original = {title: 'Original title', description: 'Original description'};
    const sandbox = {window: {PixivNovel: {}}, novelId: '1', novelView: original,
        rerenderPayload: {data: original}, cachedSeriesNav: null,
        fetch: async () => ({ok: true, json: async () => ({content: 'Translated body', translated: true,
            translatedTitle: 'Translated title', translatedDescription: 'Translated description'})})};
    vm.createContext(sandbox);
    vm.runInContext(source, sandbox);
    let description;
    Object.assign(sandbox, {renderContent() {}, buildImageResolver() {}, applyTitleForLang() {},
        renderBadges() {}, renderMetaRow() {}, renderTags() {}, updateHeart() {},
        renderDescription(value) { description = value; }});
    await sandbox.loadContent('zh-CN@youdao');
    sandbox.rerenderDynamic();
    assert.strictEqual(description, 'Translated description');
    sandbox.rerenderPayload.translation.translatedDescription = '';
    sandbox.rerenderDynamic();
    assert.strictEqual(description, original.description);
});
