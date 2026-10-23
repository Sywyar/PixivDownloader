'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const test = require('node:test');

const ROOT = path.join(__dirname, '..', '..', 'main', 'resources', 'static');
const CORE = fs.readFileSync(path.join(ROOT, 'pixiv-novel', 'novel-core.js'), 'utf8');
const INIT = fs.readFileSync(path.join(ROOT, 'pixiv-novel', 'novel-init.js'), 'utf8');
const GALLERY_INIT = fs.readFileSync(path.join(ROOT, 'pixiv-novel-gallery', 'novel-gallery-init.js'), 'utf8');

function load(storage) {
    const nodes = new Map();
    const sandbox = {
        URL, URLSearchParams, Map, Set,
        location: {origin: 'http://localhost', pathname: '/pixiv-novel.html', search: '?id=1'},
        sessionStorage: storage,
        document: {getElementById(id) {
            if (!nodes.has(id)) nodes.set(id, {addEventListener(type, listener) { this[type] = listener; }});
            return nodes.get(id);
        }},
        setTimeout, clearTimeout
    };
    sandbox.window = sandbox;
    vm.createContext(sandbox);
    vm.runInContext(CORE, sandbox);
    return sandbox;
}

test('小说详情只接受同源小说画廊地址，存储不可用时使用默认入口', () => {
    const valid = '/pixiv-novel-gallery.html?view=authors';
    assert.strictEqual(load({getItem: () => valid}).PixivNovel.core.novelGalleryReturnTo, valid);
    for (const stored of [null, '', '/pixiv-gallery.html?view=all', 'https://example.invalid/steal',
        '//example.invalid/steal', '/\\example.invalid/steal', '/pixiv-novel-gallery.html#fragment']) {
        assert.strictEqual(load({getItem: () => stored}).PixivNovel.core.novelGalleryReturnTo,
            '/pixiv-novel-gallery.html?view=all');
    }
    assert.strictEqual(load({getItem() { throw new Error('storage unavailable'); }}).PixivNovel.core.novelGalleryReturnTo,
        '/pixiv-novel-gallery.html?view=all');
});

test('详情初始化绑定返回链接，普通点击替换历史，修饰键保留浏览器行为', () => {
    const target = '/pixiv-novel-gallery.html?view=series';
    const h = load({getItem: () => target});
    const replaced = [];
    h.location.replace = url => replaced.push(url);
    Object.assign(h, {PixivActions: {bind() {}}, openAddToCollectionModal() {}, closeAddToCollectionModal() {},
        openDeleteNovelModal() {}, closeDeleteNovelModal() {}, confirmDeleteNovel() {}, setupAdminMode() {}, loadAll() {}});
    vm.runInContext(INIT, h);
    const link = h.document.getElementById('backToGalleryLink');
    assert.strictEqual(link.href, target);
    let prevented = 0;
    const click = {button: 0, preventDefault() { prevented++; }};
    for (const override of [{button: 1}, {ctrlKey: true}, {metaKey: true}, {shiftKey: true}, {altKey: true}]) {
        link.click({...click, ...override});
    }
    assert.deepStrictEqual(replaced, []);
    assert.strictEqual(prevented, 0);
    link.click(click);
    assert.deepStrictEqual(replaced, [target]);
    assert.strictEqual(prevented, 1);
});

test('画廊进入和离页保存当前筛选地址，存储拒绝不影响生命周期', () => {
    const values = new Map(), listeners = new Map();
    let rejectWrite = false;
    const storage = {getItem: key => values.get(key), setItem(key, value) {
        if (rejectWrite) throw new Error('storage unavailable');
        values.set(key, value);
    }};
    const h = load(storage);
    h.location.pathname = '/pixiv-novel-gallery.html';
    h.location.search = '?view=authors';
    Object.assign(h, {addEventListener(type, listener) {
        if (!listeners.has(type)) listeners.set(type, []);
        listeners.get(type).push(listener);
    }, releaseCollectionIconPreview() {}, closeMobileSidebar() {}, openMobileSidebar() {},
    closeAddToCollectionModal() {}, restoreSidebarState() {}, PixivActions: {bind() {}},
    // 将无关的异步页面初始化停在词典加载边界，保留完整入口脚本。
    PixivI18n: {create: () => new Promise(() => {})}});
    const gallery = vm.createContext({...h, window: null});
    gallery.window = gallery;
    vm.runInContext(GALLERY_INIT, gallery);
    assert.strictEqual(load(storage).PixivNovel.core.novelGalleryReturnTo, '/pixiv-novel-gallery.html?view=authors');
    gallery.location.search = '?view=series&seriesId=7';
    for (const listener of listeners.get('pagehide')) listener();
    assert.strictEqual(load(storage).PixivNovel.core.novelGalleryReturnTo, '/pixiv-novel-gallery.html?view=series&seriesId=7');
    rejectWrite = true;
    for (const listener of listeners.get('pagehide')) assert.doesNotThrow(() => listener());
});
