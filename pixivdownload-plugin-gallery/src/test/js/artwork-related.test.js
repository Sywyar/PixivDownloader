'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

function harness(nativeObserver = true) {
    const events = new Map(), observers = [], requests = [], frames = new Map(), nodes = new Map();
    let frameId = 0;
    const listen = (name, callback) => {
        if (!events.has(name)) events.set(name, new Set());
        events.get(name).add(callback);
    };
    class Element {
        constructor() { this.dataset = {}; this.children = []; this.style = {}; this.rect = {left: 0, right: 400, top: 0, bottom: 100, width: 400, height: 100}; }
        querySelector() { return this.children[0] || null; }
        querySelectorAll() { return this.children; }
        getBoundingClientRect() { return this.rect; }
        appendChild(child) { child.parentElement = this; this.children.push(child); }
        removeAttribute(name) { if (name === 'data-preview-src') delete this.dataset.previewSrc; else if (name === 'src') delete this.source; else delete this[name]; }
        remove() { this.parentElement.children = this.parentElement.children.filter(item => item !== this); }
        set src(url) { this.source = url; requests.push(url); }
        get src() { return this.source; }
        scrollBy(options) { this.scrollLeft += options.left; this.onscroll?.(); }
        set innerHTML(value) { this.html = value; this.children = []; }
    }
    const document = {
        visibilityState: 'visible', addEventListener: listen,
        createElement: () => new Element(),
        getElementById(id) { if (!nodes.has(id)) nodes.set(id, new Element()); return nodes.get(id); }
    };
    const sandbox = {
        document, state: {artworkId: 1, artwork: {}},
        requestAnimationFrame(callback) { frames.set(++frameId, callback); return frameId; },
        cancelAnimationFrame(id) { frames.delete(id); },
        window: {innerWidth: 800, innerHeight: 600, addEventListener: listen,
            removeEventListener(name, callback) { events.get(name)?.delete(callback); },
            PixivArtwork: {}, PixivLayout: {previewUrl(url, box) { box.dataset.previewSrc = url; return url + '?size=256'; }}},
        wt: (key, fallback) => fallback, escapeHtml: value => value,
        IntersectionObserver: nativeObserver ? class {
            constructor(callback) { this.callback = callback; this.targets = []; observers.push(this); }
            observe(box) { this.targets.push(box); }
            disconnect() { this.disconnected = true; }
            show(indices) { this.callback(this.targets.map((target, index) => ({target, isIntersecting: indices.includes(index), intersectionRatio: indices.includes(index) ? 1 : 0}))); }
        } : undefined
    };
    vm.createContext(sandbox);
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../../main/resources/static/pixiv-artwork/artwork-related.js'), 'utf8'), sandbox);
    const list = document.getElementById('byAuthorList');
    const boxes = Array.from({length: 15}, (_, index) => {
        const box = new Element(); box.dataset.src = '/api/downloaded/thumbnail/' + index + '/0'; list.appendChild(box); return box;
    });
    return {sandbox, list, boxes, observers, requests, nodes,
        loaded: () => boxes.flatMap((box, index) => box.children.length ? [index] : []),
        event(name) { for (const callback of events.get(name) || []) callback(); },
        frame() { const pending = [...frames.values()]; frames.clear(); pending.forEach(callback => callback()); }};
}

test('旁栏只加载可见作品及前后各三张，离开释放且保留占位', () => {
    const h = harness();
    h.sandbox.observeRelatedThumbnails(h.list);
    assert.equal(h.requests.length, 0);
    h.observers[0].show([0, 1, 2]);
    assert.deepEqual(h.loaded(), [0, 1, 2, 3, 4, 5]);
    assert.equal(h.requests[0], '/api/downloaded/thumbnail/0/0?size=256');
    const first = h.boxes[0].children[0];
    h.observers[0].show([0, 1, 2]);
    assert.equal(h.requests.length, 6);
    h.observers[0].show([7, 8]);
    assert.deepEqual(h.loaded(), [4, 5, 6, 7, 8, 9, 10, 11]);
    assert.equal(h.boxes[0].dataset.previewSrc, undefined);
    assert.equal(h.boxes[0].dataset.src, '/api/downloaded/thumbnail/0/0');
    assert.equal(first.src, undefined);
    h.observers[0].show([]);
    assert.deepEqual(h.loaded(), []);
    assert.equal(h.list.children.length, 15);
});

test('重绑、后台隐藏及页面退出释放图片，旧观察回调不能恢复请求', () => {
    const h = harness();
    h.sandbox.observeRelatedThumbnails(h.list); h.observers[0].show([1]);
    h.sandbox.observeRelatedThumbnails(h.list);
    assert.equal(h.observers[0].disconnected, true);
    const count = h.requests.length;
    h.observers[0].show([12]);
    assert.equal(h.requests.length, count);
    h.observers[1].show([1]);
    h.sandbox.document.visibilityState = 'hidden'; h.event('visibilitychange');
    assert.deepEqual(h.loaded(), []);
    h.sandbox.document.visibilityState = 'visible'; h.observers[1].show([1]);
    assert.ok(h.loaded().length > 0);
    h.event('pagehide');
    assert.deepEqual(h.loaded(), []);
    assert.equal(h.observers[1].disconnected, true);
    h.event('pageshow');
    h.observers[2].show([13, 14]);
    assert.deepEqual(h.loaded(), [10, 11, 12, 13, 14]);
});

test('无观察器时依据窗口和滚动容器交集加载，滚动事件合批', () => {
    const h = harness(false);
    h.boxes.forEach((box, index) => { box.rect = {left: index * 100, right: (index + 1) * 100, top: 0, bottom: 100, width: 100, height: 100}; });
    h.sandbox.observeRelatedThumbnails(h.list);
    assert.deepEqual(h.loaded(), [0, 1, 2, 3, 4, 5, 6]);
    h.list.rect.top = 650; h.list.rect.bottom = 750;
    h.event('scroll'); h.event('scroll'); h.frame();
    assert.deepEqual(h.loaded(), []);
    h.list.rect.top = 0; h.list.rect.bottom = 100;
    h.event('resize'); h.frame();
    assert.deepEqual(h.loaded(), [0, 1, 2, 3, 4, 5, 6]);
    h.sandbox.clearRelatedThumbnails(); h.event('scroll'); h.frame();
    assert.deepEqual(h.loaded(), []);
});

test('重新绑定滚动条不会叠加处理器，前后按钮保持可用', () => {
    const h = harness(), prev = {}, next = {};
    Object.assign(h.list, {scrollLeft: 0, clientWidth: 300, scrollWidth: 540});
    h.sandbox.bindThumbStripArrows(h.list, prev, next); h.frame();
    assert.equal(prev.disabled, true); assert.equal(next.disabled, false);
    h.sandbox.bindThumbStripArrows(h.list, prev, next); h.frame();
    next.onclick(); assert.equal(h.list.scrollLeft, 240);
    assert.equal(prev.disabled, false); assert.equal(next.disabled, true);
    prev.onclick(); assert.equal(h.list.scrollLeft, 0);
});

test('切换作品后迟到的旁栏响应不能覆盖新作品', async () => {
    const h = harness();
    let resolve;
    h.sandbox.api = () => new Promise(done => { resolve = done; });
    const loading = h.sandbox.loadByAuthor();
    h.sandbox.state.artworkId = 2;
    resolve([{artworkId: 3, title: 'Old artwork'}]); await loading;
    assert.equal(h.list.html, undefined);
    assert.equal(h.observers.length, 0);
});
