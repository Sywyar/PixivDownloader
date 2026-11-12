'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

function load() {
    const root = path.join(__dirname, '../../main/resources/static');
    const nodes = new Map(), timers = new Map(), requests = [];
    let sequence = 0;
    function node(id) {
        if (!nodes.has(id)) {
            const classes = new Set(), listeners = new Map();
            nodes.set(id, {value: '', textContent: '', innerHTML: '', style: {}, dataset: {},
                classList: {add: key => classes.add(key), remove: key => classes.delete(key), contains: key => classes.has(key),
                    toggle(key, on = !classes.has(key)) { if (on) classes.add(key); else classes.delete(key); return on; }},
                addEventListener: (type, fn) => listeners.set(type, fn),
                click() { return listeners.get('click')?.({target: this}); },
                dispatchEvent(event) { listeners.get(event.type)?.({...event, target: this}); },
                setCustomValidity(message) { this.validationMessage = message; },
                reportValidity() { return !this.validationMessage; },
                children: [], focus() {},
                append(...items) { this.children.push(...items); },
                get lastElementChild() { return this.children.at(-1); },
                querySelector(selector) { return this.children.find(item => item.tagName === selector) || null; },
                querySelectorAll: () => [], setAttribute() {}, replaceChildren() { this.children = []; }
            });
        }
        return nodes.get(id);
    }
    const sandbox = {
        console, URLSearchParams, URL, FormData, location: {pathname: '/pixiv-gallery.html', search: '', href: 'http://localhost/pixiv-gallery.html'},
        history: {replaceState() {}},
        document: {createElement(tag) { const item = node("created-" + sequence++); item.tagName = tag; return item; }, getElementById: node, querySelectorAll: () => [], addEventListener() {}, body: node('body')},
        localStorage: {getItem: () => null, setItem() {}}, sessionStorage: {getItem: () => null, setItem() {}},
        addEventListener() {}, PixivActions: {bind() {}},
        setTimeout(fn) { timers.set(fn, fn); return fn; }, clearTimeout: fn => timers.delete(fn),
        fetch: () => new Promise(() => {})
    };
    sandbox.window = sandbox;
    vm.createContext(sandbox);
    const html = fs.readFileSync(path.join(root, 'pixiv-gallery.html'), 'utf8');
    for (const match of html.matchAll(/<script src="(\/pixiv-gallery\/[^"]+)"/g)) {
        if (match[1].endsWith('gallery-init.js')) sandbox.initPageI18n = () => new Promise(() => {});
        vm.runInContext(fs.readFileSync(path.join(root, match[1]), 'utf8'), sandbox, {filename: match[1]});
    }
    sandbox.loadGallery = () => requests.push(JSON.parse(JSON.stringify(sandbox.serializeGalleryState())));
    return {sandbox, nodes, node, timers, requests, state: vm.runInContext('state', sandbox)};
}

for (const entry of ['reset', 'collection']) {
    test(`${entry} 清空内容筛选及延迟搜索，只请求一次最终结果`, () => {
        const h = load(), s = h.state;
        s.page = 3; s.search = 'keep'; s.searchType = 'title'; s.sort = 'artworkId'; s.order = 'asc';
        s.r18 = 'r18'; s.ai = 'yes'; s.formats.add('png'); s.collectionIds.add(9);
        s.dateFilters = [{field: 'uploadTime', start: '2020-01-01', end: '2020-01-31'}];
        s.seriesFilter = {id: 10, title: 'series'}; s.authorNames.set(11, 'author');
        for (const kind of ['tag', 'author']) {
            h.sandbox.setFilterSelectionMode(kind, 12, 'not');
            s[kind + 'Filters'].mode = 'or';
        }
        for (const kind of ['tag', 'series', 'author']) {
            s[kind + 'FilterText'] = 'old';
            h.nodes.get(kind + 'SearchInput').value = 'late';
            h.nodes.get(kind + 'SearchInput').dispatchEvent({type: 'input'});
        }
        if (entry === 'reset') h.nodes.get('filterReset').click();
        else h.sandbox.switchCollectionFilter(7);
        assert.equal(s.page, 0);
        assert.equal(s.r18, 'any'); assert.equal(s.ai, 'any'); assert.equal(s.formats.size, 0);
        assert.equal(Object.keys(h.sandbox.galleryTimeRanges()).length, 0);
        assert.deepEqual([...s.collectionIds], entry === 'reset' ? [] : [7]);
        assert.equal(s.seriesFilter.id, null); assert.equal(s.authorNames.size, 0);
        for (const kind of ['tag', 'author']) {
            assert.equal(s[kind + 'Filters'].mode, 'must');
            for (const mode of ['must', 'not', 'or']) {
                assert.equal(s[kind + 'Filters'][mode].size, 0);
                assert.equal(s[kind + 'Filters'].queue[mode].length, 0);
            }
        }
        for (const fn of h.timers.values()) fn();
        for (const kind of ['tag', 'series', 'author']) {
            assert.equal(s[kind + 'FilterText'], '');
            assert.equal(h.nodes.get(kind + 'SearchInput').value, '');
        }
        assert.equal(s.search, 'keep'); assert.equal(s.searchType, 'title');
        assert.equal(s.sort, 'artworkId'); assert.equal(s.order, 'asc');
        assert.equal(h.requests.length, 1);
        assert.equal(h.requests[0].page, 0);
        assert.equal(h.requests[0].r18, 'any'); assert.equal(h.requests[0].ai, 'any');
        assert.deepEqual(h.requests[0].formats, []);
        assert.deepEqual(h.requests[0].collectionIds, entry === 'reset' ? [] : [7]);
    });
}

test('重复选择唯一收藏夹时不刷新，切换作者视图中的收藏夹时只请求一次', () => {
    const h = load();
    h.state.collectionIds.add(7);
    h.sandbox.switchCollectionFilter(7);
    assert.equal(h.requests.length, 0);
    h.state.view = 'authors';
    h.sandbox.switchCollectionFilter(7);
    assert.equal(h.state.view, 'all');
    assert.equal(h.requests.length, 1);
});

test('收藏夹保存固定原对象和图标，重复提交不发请求且完成后保留新表单', async () => {
    const h = load(), calls = [];
    h.sandbox.api = (url, options) => new Promise(resolve => calls.push({url, options, resolve}));
    h.sandbox.loadCollections = async () => {};
    h.sandbox.toast = () => {};
    h.sandbox.openCollectionFormModal({id: 1, name: 'First'});
    h.node('collectionFormName').value = 'Saved first';
    h.node('collectionDownloadRootEnabled').checked = true;
    h.node('collectionDownloadRoot').value = '/first';
    const file = new File(['first'], 'first.png');
    h.state.pendingIconFile = file;
    const saving = h.node('collectionFormSubmit').click();
    const duplicate = h.node('collectionFormSubmit').click();
    assert.equal(calls.length, 1, '保存中不能再次提交');
    await duplicate;
    h.sandbox.closeCollectionFormModal();
    h.sandbox.openCollectionFormModal({id: 2, name: 'Second'});
    h.node('collectionFormName').value = 'Second draft';
    h.state.pendingIconFile = new File(['second'], 'second.png');
    calls[0].resolve({}); await new Promise(setImmediate);
    assert.equal(calls[1].url, '/api/collections/1/download-root');
    assert.equal(JSON.parse(calls[1].options.body).downloadRoot, '/first');
    calls[1].resolve({}); await new Promise(setImmediate);
    assert.equal(calls[2].url, '/api/collections/1/icon');
    assert.equal(calls[2].options.body.get('file'), file);
    calls[2].resolve({}); await saving;
    assert.equal(h.state.editingCollection.id, 2);
    assert.equal(h.node('collectionFormName').value, 'Second draft');
    assert.equal(h.node('modalCollectionForm').classList.contains('open'), true);
    assert.equal(h.node('collectionFormSubmit').disabled, false);
});

function dateInputs(h) {
    h.sandbox.renderDateFilters();
    const row = h.node('dateFilters').children[0];
    return [row.children[1].children[1], row.children[2].children[1]];
}

test('多个日期条件同时保存、交接并进入批量快照，重置清除全部范围', () => {
    const h = load();
    const rows = [
        {field: 'date', start: '2021-05-01', end: '2021-05-02'},
        {field: 'uploadTime', start: '2020-02-03', end: '2020-02-03'},
        {field: 'createTime', start: '2019-01-01', end: ''}
    ];
    h.sandbox.applyCrossTransferToGallery({dateFilters: rows, sort: 'date', order: 'asc'});
    const saved = h.sandbox.serializeGalleryState();
    h.sandbox.applyPersistedGalleryState(saved);
    const snapshot = h.sandbox.currentBatchFilterSnapshot();
    assert.equal(snapshot.downloadedFrom, new Date('2021-05-01T00:00:00').getTime());
    assert.equal(snapshot.downloadedBefore, new Date('2021-05-03T00:00:00').getTime());
    assert.equal(snapshot.uploadedBefore, new Date('2020-02-04T00:00:00').getTime());
    assert.equal(snapshot.createdFrom, new Date('2019-01-01T00:00:00').getTime());
    assert.equal(snapshot.sort, 'date');
    assert.equal(snapshot.order, 'asc');
    assert.deepEqual(JSON.parse(JSON.stringify(saved.dateFilters)), rows);
    saved.dateFilters[0].start = '';
    assert.equal(h.state.dateFilters[0].start, '2021-05-01');
    h.sandbox.resetGalleryFilters([]);
    assert.deepEqual(Object.keys(h.sandbox.galleryTimeRanges()), []);
});

for (const field of ['date', 'uploadTime']) {
    test(`${field} 拒绝倒置区间，修正后仅发送一次请求`, () => {
        const h = load();
        h.state.dateFilters = [{field, start: '', end: ''}];
        const [from, to] = dateInputs(h);
        from.value = '2021-05-02'; to.value = '2021-05-01';
        to.dispatchEvent({type: 'change'});
        assert.equal(h.requests.length, 0);
        assert.ok(to.validationMessage);
        from.value = '';
        from.dispatchEvent({type: 'change'});
        assert.equal(h.requests.length, 1);
        assert.equal(h.state.dateFilters[0].end, '2021-05-01');
    });
}

test('切换和移除日期类别后旧类别不再参与查询，新增空行不限制结果', () => {
    const h = load();
    h.state.dateFilters = [{field: 'uploadTime', start: '2020-01-01', end: ''}];
    h.sandbox.renderDateFilters();
    const select = h.node('dateFilters').children[0].children[0];
    select.value = 'createTime'; select.dispatchEvent({type: 'change'});
    assert.equal(h.sandbox.galleryTimeRanges().uploadedFrom, undefined);
    assert.equal(h.sandbox.galleryTimeRanges().createdFrom, new Date('2020-01-01T00:00:00').getTime());
    h.node('dateFilters').children[0].lastElementChild.children[1].click();
    assert.equal(h.state.dateFilters.length, 2);
    assert.deepEqual(Object.keys(h.sandbox.galleryTimeRanges()), ['createdFrom']);
    h.node('dateFilters').children[0].lastElementChild.children[0].click();
    assert.deepEqual(Object.keys(h.sandbox.galleryTimeRanges()), []);
});

test('恢复旧日期偏好并拒绝无效日期及重复类别', () => {
    const h = load();
    h.sandbox.restoreTimeRanges({downloadStartDate: '2021-05-02', uploadEndDate: '2021-02-29'});
    assert.deepEqual(Object.keys(h.sandbox.galleryTimeRanges()), ['downloadedFrom']);
    h.sandbox.restoreTimeRanges({dateFilters: [
        {field: 'createTime', start: '2020-01-01'}, {field: 'createTime', start: '2021-01-01'},
        {field: 'unknown', start: '2021-01-01'}
    ]});
    assert.equal(h.state.dateFilters.length, 1);
    assert.equal(h.sandbox.galleryTimeRanges().createdFrom, new Date('2020-01-01T00:00:00').getTime());
    h.sandbox.restoreTimeRanges({dateFilters: [{field: 'date', start: '2021-05-02', end: '2021-05-01'}]});
    assert.deepEqual(Object.keys(h.sandbox.galleryTimeRanges()), []);
});

test('日期排序从其他排序切换回来时恢复选中态', () => {
    const h = load();
    h.state.sort = 'artworkId'; h.sandbox.syncDateSort();
    assert.equal(h.node('dateSort').classList.contains('active'), false);
    h.state.sort = 'createTime'; h.sandbox.syncDateSort();
    assert.equal(h.node('dateSort').classList.contains('active'), true);
    assert.equal(h.node('dateSort').value, 'createTime');
});
