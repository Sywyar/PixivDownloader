'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

function load() {
    const root = path.join(__dirname, '../../main/resources/static');
    const nodes = new Map(), timers = new Map(), requests = [];
    function node(id) {
        if (!nodes.has(id)) {
            const classes = new Set(), listeners = new Map();
            nodes.set(id, {value: '', textContent: '', innerHTML: '', style: {}, dataset: {},
                classList: {add: key => classes.add(key), remove: key => classes.delete(key), contains: key => classes.has(key),
                    toggle(key, on = !classes.has(key)) { if (on) classes.add(key); else classes.delete(key); return on; }},
                addEventListener: (type, fn) => listeners.set(type, fn),
                click() { listeners.get('click')?.({target: this}); },
                dispatchEvent(event) { listeners.get(event.type)?.({...event, target: this}); },
                querySelector: () => null, querySelectorAll: () => [], setAttribute() {}, replaceChildren() {}
            });
        }
        return nodes.get(id);
    }
    const sandbox = {
        console, URLSearchParams, URL, location: {pathname: '/pixiv-gallery.html', search: '', href: 'http://localhost/pixiv-gallery.html'},
        history: {replaceState() {}},
        document: {getElementById: node, querySelectorAll: () => [], addEventListener() {}, body: node('body')},
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
    return {sandbox, nodes, timers, requests, state: vm.runInContext('state', sandbox)};
}

for (const entry of ['reset', 'collection']) {
    test(`${entry} 清空内容筛选及延迟搜索，只请求一次最终结果`, () => {
        const h = load(), s = h.state;
        s.page = 3; s.search = 'keep'; s.searchType = 'title'; s.sort = 'artworkId'; s.order = 'asc';
        s.r18 = 'r18'; s.ai = 'yes'; s.formats.add('png'); s.collectionIds.add(9);
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
