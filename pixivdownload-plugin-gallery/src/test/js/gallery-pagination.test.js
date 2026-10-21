'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

function harness(saved, respond) {
    const nodes = new Map();
    const storage = new Map([['pixiv:gallery-state-v1', JSON.stringify(saved)]]);
    const timers = new Map();
    const requests = [], rendered = [];
    const sandbox = {
        URLSearchParams, console, window: {PixivGallery: {}},
        setTimeout(fn) { timers.set(fn, fn); return fn; },
        clearTimeout(fn) { timers.delete(fn); },
        storageGet: key => storage.get(key),
        storageSet: (key, value) => storage.set(key, value),
        document: {getElementById(id) {
            if (!nodes.has(id)) nodes.set(id, {innerHTML: '', textContent: '', querySelectorAll: () => []});
            return nodes.get(id);
        }},
        t: (key, fallback, values = {}) => JSON.stringify({key, values}),
        escapeHtml: value => value,
        buildPageWindow: (_, total) => Array.from({length: total}, (_, i) => i),
        releaseThumbnails() {},
        setSearchEmptyState(value) { sandbox.empty = value; },
        api(url) {
            const params = new URLSearchParams(url.split('?')[1]);
            requests.push(Object.fromEntries(params));
            return respond(params, requests.length);
        }
    };
    vm.createContext(sandbox);
    for (const file of ['gallery-state.js', 'gallery-collections.js', 'gallery-views.js']) {
        vm.runInContext(fs.readFileSync(path.join(__dirname, '../../main/resources/static/pixiv-gallery', file), 'utf8'), sandbox);
    }
    sandbox.renderGallery = items => rendered.push(items);
    sandbox.restoreGalleryState();
    return {
        sandbox, requests, rendered, nodes,
        state: vm.runInContext('state', sandbox),
        persisted() {
            for (const fn of timers.values()) fn();
            timers.clear();
            return JSON.parse(storage.get('pixiv:gallery-state-v1'));
        }
    };
}

test('恢复越界页码后补查首页，保留搜索筛选排序并保存校正页码', async () => {
    const saved = {page: 2, search: 'sample', searchType: 'title', sort: 'artworkId', order: 'asc',
        r18: 'no', ai: 'no', formats: ['png'], collectionIds: [7],
        tagFilters: {must: [8], not: [9], or: [10]},
        authorFilters: {must: [11], not: [12], or: [13]}, seriesFilter: {id: 14, title: 'series'}};
    const h = harness(saved, params => ({totalPages: 1, totalElements: 1,
        content: params.get('page') === '0' ? [{id: 123}] : []}));
    await h.sandbox.loadGallery();
    assert.equal(h.requests.length, 2);
    assert.deepEqual(h.requests[1], {...h.requests[0], page: '0'});
    assert.equal(h.requests[0].page, '2');
    assert.equal(h.requests[1].search, 'sample');
    assert.equal(h.requests[1].authorIds, '11');
    assert.deepEqual(h.rendered, [[{id: 123}]]);
    assert.deepEqual(JSON.parse(h.nodes.get('galleryStatus').textContent),
        {key: 'status.gallery-range', values: {total: 1, from: 1, to: 1}});
    assert.equal(h.nodes.get('pagination').innerHTML, '');
    const persisted = h.persisted();
    assert.equal(persisted.page, 0);
    for (const key of Object.keys(saved).filter(key => key !== 'page')) {
        if (key.endsWith('Filters')) continue;
        assert.deepEqual(persisted[key], saved[key]);
    }
});

test('零结果归零并显示 0-0，不补查或丢失筛选条件', async () => {
    const h = harness({page: 2, search: 'absent'}, () => ({totalPages: 0, totalElements: 0, content: []}));
    await h.sandbox.loadGallery();
    assert.equal(h.requests.length, 1);
    assert.equal(h.persisted().page, 0);
    assert.equal(h.persisted().search, 'absent');
    assert.deepEqual(JSON.parse(h.nodes.get('galleryStatus').textContent),
        {key: 'status.gallery-range', values: {total: 0, from: 0, to: 0}});
    assert.equal(h.sandbox.empty, true);
});

test('有效分页记忆保持原页且只请求一次', async () => {
    const h = harness({page: 1}, () => ({totalPages: 3, totalElements: 50, content: [{id: 25}]}));
    await h.sandbox.loadGallery();
    assert.equal(h.requests.length, 1);
    assert.equal(h.persisted().page, 1);
    assert.deepEqual(JSON.parse(h.nodes.get('galleryStatus').textContent),
        {key: 'status.gallery-range', values: {total: 50, from: 25, to: 48}});
    assert.match(h.nodes.get('pagination').innerHTML, /data-page="1"/);
});

test('补查期间结果缩为零仍只补查一次', async () => {
    const h = harness({page: 2}, (_, call) => call === 1
        ? {totalPages: 1, totalElements: 1, content: []}
        : {totalPages: 0, totalElements: 0, content: []});
    await h.sandbox.loadGallery();
    assert.equal(h.requests.length, 2);
    assert.deepEqual(JSON.parse(h.nodes.get('galleryStatus').textContent),
        {key: 'status.gallery-range', values: {total: 0, from: 0, to: 0}});
});

test('旧请求的越界响应不能覆盖新查询或发起补查', async () => {
    let finishOld;
    const h = harness({page: 2}, (_, call) => call === 1
        ? new Promise(resolve => { finishOld = resolve; })
        : {totalPages: 1, totalElements: 1, content: [{id: 222}]});
    const old = h.sandbox.loadGallery();
    h.state.page = 0;
    h.state.search = 'new';
    await h.sandbox.loadGallery();
    finishOld({totalPages: 1, totalElements: 1, content: []});
    await old;
    assert.equal(h.requests.length, 2);
    assert.deepEqual(h.rendered, [[{id: 222}]]);
    assert.equal(h.persisted().search, 'new');
});

test('补查响应晚于新查询时不能覆盖新结果', async () => {
    let finishCorrection;
    const h = harness({page: 2}, (_, call) => call === 1
        ? {totalPages: 1, totalElements: 1, content: []}
        : call === 2 ? new Promise(resolve => { finishCorrection = resolve; })
            : {totalPages: 1, totalElements: 1, content: [{id: 333}]});
    const old = h.sandbox.loadGallery();
    await Promise.resolve();
    h.state.search = 'new';
    await h.sandbox.loadGallery();
    finishCorrection({totalPages: 1, totalElements: 1, content: [{id: 111}]});
    await old;
    assert.equal(h.requests.length, 3);
    assert.deepEqual(h.rendered, [[{id: 333}]]);
});

for (const [render, stateKey, container, load] of [
    ['renderPagination', null, 'pagination', 'loadGallery'],
    ['renderAuthorsPagination', 'authors', 'authorPagination', 'loadAuthorsView'],
    ['renderSeriesPagination', 'series', 'authorPagination', 'loadSeriesView']
]) {
    test(`${render} 的按钮和跳页只更新所属视图并保留边界检查`, () => {
        const h = harness({}, () => ({}));
        const view = stateKey ? h.state[stateKey] : h.state;
        Object.assign(view, {page: 2, totalPages: 8});
        const clicked = [], buttons = [1, 3, -1, 8].map(page => ({
            dataset: {page: String(page)}, disabled: false,
            addEventListener(_event, fn) { this.click = fn; }
        }));
        const input = {value: '8', events: {},
            addEventListener(event, fn) { this.events[event] = fn; },
            blur() { this.events.blur(); }};
        h.nodes.set(container, {innerHTML: '', querySelectorAll(selector) {
            return selector === '.page-btn' ? buttons : [input];
        }});
        h.sandbox[load] = () => clicked.push(view.page);
        let scrolled = 0;
        h.sandbox.scrollGalleryToTop = () => scrolled++;
        h.sandbox[render]();
        assert.match(h.nodes.get(container).innerHTML, /class="page-btn active" data-page="2"/);
        buttons.forEach(button => button.click());
        assert.deepEqual(clicked, [1, 3]);
        buttons[0].disabled = true;
        buttons[0].click();
        input.events.keydown({key: 'Enter', preventDefault() {}});
        assert.deepEqual(clicked, [1, 3, 7]);
        assert.equal(scrolled, 3, 'Enter 后 blur 不重复翻页');
        for (const totalPages of [0, 1]) {
            view.totalPages = totalPages;
            h.sandbox[render]();
            assert.equal(h.nodes.get(container).innerHTML, '');
        }
        if (stateKey) assert.equal(h.state.page, 0, '其它视图的页码不变');
    });
}
