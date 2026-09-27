'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

function harness() {
    const nodes = new Map(), requests = [], observers = [];
    class Element {
        constructor(tagName = 'DIV') {
            this.tagName = tagName;
            this.style = {setProperty(name, value) {this[name] = value;}};
            this.dataset = {}; this.children = [];
            this.attributes = new Map();
            const classes = new Set();
            this.classList = {add: n => classes.add(n), remove: n => classes.delete(n), contains: n => classes.has(n)};
            this.naturalWidth = 3000; this.naturalHeight = 4000;
        }
        appendChild(child) { child.parentElement = this; this.children.push(child); }
        remove() {
            if (this.parentElement) this.parentElement.children = this.parentElement.children.filter(c => c !== this);
            this.parentElement = null;
        }
        get isConnected() { return Boolean(this.id || this.parentElement?.isConnected); }
        set innerHTML(value) { for (const child of [...this.children]) child.remove(); }
        get innerHTML() { return ''; }
        addEventListener() {}
        setAttribute(name, value) { this.attributes.set(name, value); }
        getAttribute(name) { return this.attributes.get(name); }
        removeAttribute(name) {
            this.attributes.delete(name);
            if (name === 'data-preview-src') delete this.dataset.previewSrc;
        }
        get src() { return this.attributes.get('src'); }
        set src(value) { this.attributes.set('src', value); requests.push({image:this, url:value}); }
        querySelector() { return this.children.find(c => c.tagName === 'IMG'); }
    }
    const sandbox = {
        window: {location: {origin: 'http://localhost'}}, location: {search: ''},
        sessionStorage: {getItem: () => null}, URL, URLSearchParams, AbortController,
        document: {
            getElementById(id) {
                if (!nodes.has(id)) { const node = new Element(); node.id = id; nodes.set(id, node); }
                return nodes.get(id);
            },
            createElement: name => new Element(name.toUpperCase()), addEventListener() {}
        },
        Image: class extends Element { constructor() { super('IMG'); } },
        IntersectionObserver: class {
            constructor(callback) { this.callback = callback; observers.push(this); }
            observe() {}
            disconnect() { this.disconnected = true; }
            emit(target, isIntersecting) { this.callback([{target, isIntersecting}]); }
        }
    };
    sandbox.window.PixivLayout = {previewUrl(url, element) {
        element.dataset.previewSrc = url;
        return url + '?size=1024';
    }};
    vm.createContext(sandbox);
    for (const name of ['artwork-core.js', 'artwork-viewer.js']) {
        vm.runInContext(fs.readFileSync(path.join(__dirname, '../../main/resources/static/pixiv-artwork', name), 'utf8'), sandbox);
    }
    const state = sandbox.window.PixivArtwork.core.state;
    state.artwork = {artworkId:123, count:100}; state.artworkId = 123;
    return {sandbox, state, nodes, requests, observers};
}

test('灯箱只请求当前适屏预览，原图链接跟随翻页，关闭后不再参与尺寸刷新', () => {
    const {sandbox, state, nodes, requests} = harness();
    sandbox.openLightbox(0);
    assert.equal(requests.length, 1);
    assert.equal(requests[0].url, '/api/downloaded/thumbnail/123/0?size=1024');
    assert.equal(nodes.get('lightboxOriginalLink').href, '/api/downloaded/image/123/0');
    assert.equal(nodes.has('morePages'), false);
    for (let i = 0; i < 3; i++) sandbox.lightboxNav(1);
    assert.equal(state.lightboxIndex, 3);
    assert.equal(nodes.get('lightboxImage').src, '/api/downloaded/thumbnail/123/3?size=1024');
    assert.equal(nodes.get('lightboxOriginalLink').href, '/api/downloaded/image/123/3');
    sandbox.openLightbox(99); sandbox.lightboxNav(1);
    assert.equal(state.lightboxIndex, 0);
    sandbox.lightboxNav(-1);
    assert.equal(state.lightboxIndex, 99);
    nodes.get('lightboxImage').onload();
    assert.equal(nodes.get('lightbox').style['--lightbox-image-ratio'], 0.75);
    const frame = sandbox.document.createElement('div');
    frame.classList.add('lightbox-image-frame');
    sandbox.handleLightboxClick({target: frame});
    assert.equal(nodes.get('lightboxImage').src, undefined);
    assert.equal(nodes.get('lightboxImage').onload, null);
    assert.equal(nodes.get('lightboxImage').dataset.previewSrc, undefined);
    assert.equal(nodes.get('lightbox').classList.contains('open'), false);
    sandbox.openLightbox(100);
    assert.equal(nodes.get('lightboxImage').src, undefined);
});

test('动图格式保留原始播放资源，静态图片使用预览', () => {
    const {sandbox, state, nodes} = harness();
    for (const extensions of ['gif', 'WEBP', 'jpg,webp', 'apng']) {
        state.artwork.extensions = extensions;
        sandbox.openLightbox(1);
        assert.equal(nodes.get('lightboxImage').src, '/api/downloaded/image/123/1');
        sandbox.closeLightbox();
    }
    for (const extensions of ['jpg', 'png,jpg', 'jpeg', '']) {
        state.artwork.extensions = extensions;
        sandbox.openLightbox(1);
        assert.equal(nodes.get('lightboxImage').src, '/api/downloaded/thumbnail/123/1?size=1024');
        sandbox.closeLightbox();
    }
});

test('展开只加载视口附近的页，离开释放图像并保留比例，收起取消在途请求', async () => {
    const {sandbox, nodes, requests, observers} = harness();
    await sandbox.expandAll(100); await sandbox.expandAll(100);
    const boxes = nodes.get('morePages').children;
    assert.equal(boxes.length, 99); assert.equal(requests.length, 0);
    const observer = observers[0];
    observer.emit(boxes[0], true); observer.emit(boxes[0], true); observer.emit(boxes[1], true);
    assert.equal(requests.length, 2);
    assert.equal(requests[0].url, '/api/downloaded/thumbnail/123/1?size=1024');
    requests[0].image.onload();
    await new Promise(setImmediate);
    assert.equal(boxes[0].style.aspectRatio, '3000 / 4000');
    observer.emit(boxes[0], false);
    assert.equal(requests[0].image.src, undefined);
    assert.equal(boxes[0].children.length, 0);
    assert.equal(boxes[0].style.aspectRatio, '3000 / 4000');
    observer.emit(boxes[0], true);
    assert.equal(requests.length, 3);
    sandbox.collapseAll();
    assert.equal(observer.disconnected, true);
    assert.equal(nodes.get('morePages').children.length, 0);
    for (const {image} of requests) { assert.equal(image.src, undefined); assert.equal(image.onload, null); }
    await sandbox.expandAll(100);
    observer.emit(boxes[0], true);
    assert.equal(requests.length, 3);
    assert.equal(nodes.get('morePages').children.length, 99);
});

test('已取消请求不加载图片，失败页离开后再次进入可以重试', async () => {
    const {sandbox, nodes, requests, observers} = harness();
    const controller = new AbortController(); controller.abort();
    assert.equal(await sandbox.loadImageToElement('/image', sandbox.document.getElementById('mainImage'), {signal:controller.signal}), null);
    assert.equal(requests.length, 0);
    await sandbox.expandAll(100);
    const box = nodes.get('morePages').children[0];
    observers[0].emit(box, true); requests[0].image.onerror();
    observers[0].emit(box, false); observers[0].emit(box, true);
    assert.equal(requests.length, 2);
    requests[1].image.onload(); await new Promise(setImmediate);
    assert.equal(box.classList.contains('loading'), false);
    sandbox.collapseAll();
});

test('没有观察器时使用原生懒加载并在收起时释放图片', async () => {
    const {sandbox, requests} = harness(); sandbox.IntersectionObserver = undefined;
    await sandbox.expandAll(3);
    assert.equal(requests.length, 2);
    for (const {image} of requests) assert.equal(image.loading, 'lazy');
    sandbox.collapseAll();
    for (const {image} of requests) assert.equal(image.src, undefined);
});
