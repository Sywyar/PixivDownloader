'use strict';

const {test} = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const {resolve} = require('node:path');
const vm = require('node:vm');
const {MiniElement} = require('./pixiv-layout-feedback-test-dom');

test('存为计划读取当前搜索输入，清空输入不回退到上次搜索词', () => {
    const nodes = new Map();
    const document = {
        getElementById: id => nodes.get(id),
        createElement: tag => new MiniElement(tag),
        querySelectorAll: () => [],
        body: {appendChild(node) { nodes.set(node.id, node); }}
    };
    const runtime = {
        typesForDataSource: () => [{type: 'illust'}],
        resolveSelectionForMode: () => 'illust',
        acquisition: () => ({type: 'illust'})
    };
    const context = vm.createContext({document, window: {PixivBatch: {queueTypes: runtime}, PixivBatchAlt: {}},
        state: {mode: 'search', settings: {}},
        userState: {}, seriesState: {}, searchState: {word: 'previous', source: 'pixiv', kind: 'illust'},
        QUICK_FETCH_MODE: 'quick-fetch', searchApiMode: () => 'all'});
    for (const name of ['commitQueueItemPatch', 'addItemsToQueue', 'removeFromQueue', 'renderQueue',
        'updateStats', 'syncAllResultsQueueState', 'getCookie', 'getCookieFmt', 'getStoredCookie',
        'setStoredCookie', 'removeStoredCookie', 'parseCookieToHeaderString', 'getCookieHeaderStringFor']) {
        context[name] = () => {};
    }
    vm.runInContext(readFileSync(resolve(__dirname,
        '../../main/resources/static/pixiv-batch-alt/alt-extensions.js'), 'utf8'), context);
    const input = document.createElement('input');
    input.id = 'abSearchInput';
    input.value = 'edited';
    document.body.appendChild(input);
    context.altScheduleSourceContext(1);
    assert.equal(nodes.get('search-word').value, 'edited');
    assert.equal(context.searchState.word, 'previous', '不篡改已展示搜索结果的来源');
    input.value = '';
    context.altScheduleSourceContext(1);
    assert.equal(nodes.get('search-word').value, '');
    nodes.delete('abSearchInput');
    context.altScheduleSourceContext(1);
    assert.equal(nodes.get('search-word').value, 'previous');
});
