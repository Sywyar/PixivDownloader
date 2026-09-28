'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

function harness(observed = false) {
    const images = [], requests = [], watched = new Set();
    const root = {querySelectorAll: () => images};
    let notify;
    class Observer {
        constructor(callback) { notify = callback; }
        observe(img) { watched.add(img); }
        unobserve(img) { watched.delete(img); }
    }
    const window = {PixivLayout: {previewUrl: url => url}};
    if (observed) window.IntersectionObserver = Observer;
    const sandbox = {window, IntersectionObserver: Observer, state: {view: 'all'},
        document: {querySelectorAll: () => images, getElementById: () => root}};
    vm.createContext(sandbox);
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../../main/resources/static/pixiv-gallery/gallery-core.js'), 'utf8'), sandbox);
    function add(id) {
        const listeners = new Map();
        const img = {dataset: {src: '/thumbnail/' + id}, isConnected: true, complete: false,
            closest: () => null,
            addEventListener: (type, fn) => listeners.set(type, fn),
            removeEventListener: (type, fn) => { if (listeners.get(type) === fn) listeners.delete(type); },
            removeAttribute(name) { if (name === 'src') this.url = null; },
            set src(url) { this.url = url; requests.push(url); },
            listeners};
        images.push(img);
        sandbox.loadThumbnail(img);
        return img;
    }
    return {sandbox, images, requests, watched, root, add, intersect: img => notify([{target: img, isIntersecting: true}])};
}

test('取消旧列表时停止在途和待加载图片，迟到事件不会挤占新列表额度', () => {
    const h = harness();
    for (let i = 0; i < 24; i++) h.add(i);
    assert.equal(h.requests.length, 4);
    const late = h.images.slice(0, 4).map(img => img.listeners.get('load'));
    h.sandbox.releaseThumbnails(h.root);
    assert.ok(h.images.every(img => !img.url && img.listeners.size === 0));
    h.images.splice(0).forEach(img => { img.isConnected = false; });
    for (let i = 24; i < 30; i++) h.add(i);
    assert.equal(h.requests.length, 8);
    late.forEach(fn => fn());
    assert.equal(h.requests.length, 8);
    h.images[0].listeners.get('load')();
    assert.equal(h.requests.length, 9);
});

test('队列跳过已移除节点，页面离开后不接受新任务，返回恢复当前视图', () => {
    const h = harness();
    for (let i = 0; i < 8; i++) h.add(i);
    h.images.slice(4).forEach(img => { img.isConnected = false; });
    h.images[0].listeners.get('error')();
    assert.equal(h.requests.length, 4);
    h.sandbox.suspendThumbnails();
    h.add(9);
    assert.equal(h.requests.length, 4);
    h.sandbox.resumeThumbnails();
    assert.equal(h.requests.length, 8);
    assert.ok(h.images.slice(4, 8).every(img => !img.url));
});

test('观察器仅加载进入范围的图片，释放后忽略旧通知且返回可重新观察', () => {
    const h = harness(true);
    const img = h.add(1);
    assert.equal(h.requests.length, 0);
    h.sandbox.suspendThumbnails();
    assert.equal(h.watched.size, 0);
    h.intersect(img);
    assert.equal(h.requests.length, 0);
    h.sandbox.resumeThumbnails();
    assert.equal(h.watched.size, 1);
    h.intersect(img);
    assert.equal(h.requests.length, 1);
});
