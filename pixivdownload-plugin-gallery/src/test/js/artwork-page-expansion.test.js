'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

function harness() {
    const nodes = new Map(), requests = [], observers = [], storage = new Map(), events = new Map(), toasts = [];
    class Element {
        constructor(tagName = 'DIV') {
            this.tagName = tagName;
            this.style = {setProperty(name, value) {this[name] = value;}};
            this.dataset = {}; this.children = [];
            this.attributes = new Map();
            this.listeners = new Map();
            this.complete = false;
            this.rect = {top: 0, left: 0, right: 400, bottom: 500};
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
        addEventListener(name, callback) { this.listeners.set(name, callback); }
        replaceChildren(...children) { this.innerHTML = ''; children.forEach(child => this.appendChild(child)); }
        pause() { this.paused = true; }
        load() { this.reloaded = true; }
        getBoundingClientRect() { return this.rect; }
        decode() { return Promise.resolve(); }
        finish(width = 768, height = 1024) {
            this.naturalWidth = width; this.naturalHeight = height; this.complete = true;
            return this.onload?.();
        }
        setAttribute(name, value) { this.attributes.set(name, value); }
        getAttribute(name) { return this.attributes.get(name); }
        removeAttribute(name) {
            this.attributes.delete(name);
            if (name === 'data-preview-src') delete this.dataset.previewSrc;
        }
        get src() { return this.attributes.get('src'); }
        set src(value) { this.complete = false; this.attributes.set('src', value); requests.push({image:this, url:value}); }
        querySelector(selector) { return this.children.find(c => c.tagName === selector.toUpperCase()); }
    }
    const sandbox = {
        fetch: async () => ({ok: true, headers: new Map([['content-type', 'image/png']])}),
        window: {location: {origin: 'http://localhost'}, innerWidth: 1440, innerHeight: 900,
            addEventListener(name, callback) {
                const list = events.get(name) || []; list.push(callback); events.set(name, list);
            }}, location: {search: ''},
        localStorage: {getItem: key => storage.get(key), setItem: (key, value) => storage.set(key, value)},
        requestAnimationFrame: callback => { callback(); return 1; },
        sessionStorage: {getItem: () => null}, URL, URLSearchParams, AbortController,
        document: {
            getElementById(id) {
                if (!nodes.has(id)) { const node = new Element(); node.id = id; nodes.set(id, node); }
                return nodes.get(id);
            },
            createElement: name => new Element(name.toUpperCase()),
            addEventListener(name, callback) {
                const key = 'document:' + name;
                const list = events.get(key) || []; list.push(callback); events.set(key, list);
            },
            visibilityState: 'visible'
        },
        Image: class extends Element { constructor() { super('IMG'); } },
        IntersectionObserver: class {
            constructor(callback) { this.callback = callback; observers.push(this); }
            observe() {}
            unobserve() {}
            disconnect() { this.disconnected = true; }
            emit(target, isIntersecting) { this.callback([{target, isIntersecting}]); }
        }
    };
    sandbox.window.PixivLayout = {previewUrl(url, element) {
        element.dataset.previewSrc = url;
        return url + '?size=1024';
    }};
    vm.createContext(sandbox);
    for (const name of ['artwork-core.js', 'artwork-images.js', 'artwork-viewer.js']) {
        vm.runInContext(fs.readFileSync(path.join(__dirname, '../../main/resources/static/pixiv-artwork', name), 'utf8'), sandbox);
    }
    const state = sandbox.window.PixivArtwork.core.state;
    sandbox.toast = message => toasts.push(message);
    state.artwork = {artworkId:123, count:100}; state.artworkId = 123;
    const displayedRequests = () => requests.filter(r => r.image.parentElement || r.image === nodes.get('lightboxImage'));
    return {sandbox, state, nodes, requests, observers, storage, events, displayedRequests, toasts};
}

test('视频探测迟到不回写已关闭区域，关闭后停止播放并释放资源地址', async () => {
    const {sandbox} = harness();
    const target = sandbox.document.getElementById('videoTarget');
    let respond, requestSignal;
    sandbox.fetch = (url, options) => {
        requestSignal = options.signal;
        return new Promise(resolve => { respond = resolve; });
    };
    const cancelled = new AbortController();
    const pending = sandbox.loadImageToElement('/api/downloaded/image/123/0', target, {signal: cancelled.signal});
    cancelled.abort();
    respond({ok: true, headers: new Map([['content-type', 'video/mp4']])});
    await pending;
    assert.equal(requestSignal.aborted, true);
    assert.equal(target.children.length, 0);
    sandbox.fetch = async () => ({ok: true, headers: new Map([['content-type', 'video/mp4']])});
    const playing = new AbortController();
    await sandbox.loadImageToElement('/api/downloaded/image/123/0', target, {signal: playing.signal});
    const video = target.children[0];
    assert.equal(video.tagName, 'VIDEO');
    assert.equal(video.controls, true);
    playing.abort();
    assert.equal(video.paused, true);
    assert.equal(video.reloaded, true);
    assert.equal(video.src, undefined);
    assert.equal(target.children.length, 0);
});

test('仅有 MP4 的作品详情保留视频播放，切换作品释放播放器', async () => {
    const {sandbox, state, nodes} = harness();
    state.artwork = {artworkId: 123, count: 1, extensions: 'mp4'};
    sandbox.fetch = async () => ({ok: true, headers: new Map([['content-type', 'video/mp4']])});
    sandbox.renderViewer();
    await new Promise(setImmediate);
    const main = nodes.get('mainImage');
    const video = main.querySelector('video');
    assert.ok(video, '作品入口必须加载可播放资源');
    assert.equal(video.src, '/api/downloaded/image/123/0');
    assert.equal(video.controls, true);
    sandbox.openLightbox(0);
    assert.equal(nodes.get('lightbox').classList.contains('open'), false);
    state.artwork = {artworkId: 456, count: 1, extensions: 'jpg'};
    state.artworkId = 456;
    sandbox.renderViewer();
    assert.equal(video.paused, true);
    assert.equal(video.src, undefined);
    assert.match(main.querySelector('img').src, /\/thumbnail\/456\/0/);
});

test('灯箱只请求当前适屏预览，关闭后不再参与尺寸刷新', () => {
    const {sandbox, state, nodes, requests} = harness();
    sandbox.openLightbox(0);
    assert.equal(requests.length, 1);
    assert.equal(requests[0].url, '/api/downloaded/thumbnail/123/0?size=1024');
    assert.equal(nodes.has('morePages'), false);
    for (let i = 0; i < 3; i++) sandbox.lightboxNav(1);
    assert.equal(state.lightboxIndex, 3);
    assert.equal(nodes.get('lightboxImage').src, '/api/downloaded/thumbnail/123/3?size=1024');
    sandbox.openLightbox(99); sandbox.lightboxNav(1);
    assert.equal(state.lightboxIndex, 0);
    sandbox.lightboxNav(-1);
    assert.equal(state.lightboxIndex, 99);
    nodes.get('lightboxImage').finish();
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
    const {sandbox, nodes, requests, observers, displayedRequests} = harness();
    await sandbox.expandAll(100); await sandbox.expandAll(100);
    const boxes = nodes.get('morePages').children;
    assert.equal(boxes.length, 99); assert.equal(requests.length, 0);
    const observer = observers[0];
    observer.emit(boxes[0], true); observer.emit(boxes[0], true); observer.emit(boxes[1], true);
    const firstImage = boxes[0].querySelector('img');
    const secondImage = boxes[1].querySelector('img');
    assert.equal(requests.length, 2);
    assert.equal(requests[0].url, '/api/downloaded/thumbnail/123/1?size=1024');
    requests[0].image.finish();
    await new Promise(setImmediate);
    assert.equal(boxes[0].style.aspectRatio, '768 / 1024');
    observer.emit(boxes[0], false);
    assert.equal(requests[0].image.src, undefined);
    assert.equal(boxes[0].children.length, 0);
    assert.equal(boxes[0].style.aspectRatio, '768 / 1024');
    observer.emit(boxes[0], true);
    const reloadedImage = boxes[0].querySelector('img');
    assert.equal(displayedRequests().length, 2);
    sandbox.collapseAll();
    assert.equal(observer.disconnected, true);
    assert.equal(nodes.get('morePages').children.length, 0);
    for (const image of [firstImage, secondImage, reloadedImage]) {
        assert.equal(image.src, undefined); assert.equal(image.onload, null);
    }
    const requestCount = requests.length;
    await sandbox.expandAll(100);
    observer.emit(boxes[0], true);
    assert.equal(requests.length, requestCount);
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
    requests[1].image.finish(); await new Promise(setImmediate);
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

test('已访问预览按数量和像素预算复用，原图不进入缓存，文档退出释放引用', async () => {
    for (const [width, height, maxRetained] of [[100, 100, 32], [1200, 1600, 26]]) {
        const {sandbox, requests, events} = harness();
        const box = sandbox.document.getElementById('testImage');
        for (let p = 0; p < 40; p++) {
            const controller = new AbortController();
            const loaded = sandbox.loadImageToElement(`/api/downloaded/thumbnail/123/${p}`, box, {signal: controller.signal});
            box.querySelector('img').finish(width, height);
            await loaded;
            controller.abort();
        }
        const retained = requests.filter(r => r.image.src && !r.image.parentElement);
        assert.equal(retained.length, maxRetained);
        assert.ok(retained.some(r => r.url.endsWith('/39?size=1024')));
        const before = requests.length;
        const loaded = sandbox.loadImageToElement('/api/downloaded/thumbnail/123/39', box);
        box.querySelector('img').finish(width, height); await loaded;
        assert.equal(requests.length, before + 1, '复用同一 URL 不建立第二份保持引用');
        const original = sandbox.loadImageToElement('/api/downloaded/image/123/0', box);
        await new Promise(resolve => setImmediate(resolve));
        box.querySelector('img').finish(6000, 8000); await original;
        assert.equal(requests.length, before + 2, '原图只有显示节点，没有额外缓存引用');
        for (const callback of events.get('pagehide')) callback({});
        for (const {image} of retained) assert.equal(image.src, undefined);
    }
});

test('勾选仅为可见主图加载原图，解码完成前保留预览，取消后恢复响应式预览', async () => {
    const {sandbox, nodes, requests, observers, storage} = harness();
    sandbox.initOriginalImages(); sandbox.renderViewer();
    const box = nodes.get('mainImage'), image = box.querySelector('img');
    observers[0].emit(box, true);
    sandbox.setOriginalImagesEnabled(true);
    assert.equal(requests.filter(r => r.url.includes('/image/')).length, 0);
    image.finish();
    const original = requests.find(r => r.url === '/api/downloaded/image/123/0');
    assert.ok(original);
    assert.equal(image.src, '/api/downloaded/thumbnail/123/0?size=1024');
    await original.image.finish(3000, 4000);
    assert.equal(image.src, '/api/downloaded/image/123/0');
    assert.equal(box.dataset.previewSrc, undefined);
    assert.equal(original.image.src, undefined);
    sandbox.setOriginalImagesEnabled(false);
    assert.equal(image.src, '/api/downloaded/thumbnail/123/0?size=1024');
    assert.equal(box.dataset.previewSrc, '/api/downloaded/thumbnail/123/0');
    assert.equal(nodes.get('originalImagesToggle').checked, false);
    assert.equal(nodes.get('lightboxOriginalImagesToggle').checked, false);
    assert.equal([...storage.values()][0], 'false');
});

test('预览失败仍可勾选加载可用原图，关闭与取消不会反复重试', async () => {
    const {sandbox, nodes, requests, observers} = harness();
    sandbox.initOriginalImages(); sandbox.renderViewer();
    const box = nodes.get('mainImage'), image = box.querySelector('img');
    observers[0].emit(box, true);
    image.complete = true; image.naturalWidth = 0; image.onerror();
    sandbox.setOriginalImagesEnabled(true);
    const original = requests.find(r => r.url === '/api/downloaded/image/123/0');
    assert.ok(original);
    await original.image.finish(3000, 4000);
    image.finish(3000, 4000);
    assert.equal(box.querySelector('img'), image);
    assert.equal(image.hidden, false);
    assert.equal(image.src, '/api/downloaded/image/123/0');
    sandbox.setOriginalImagesEnabled(false);
    assert.equal(image.src, '/api/downloaded/thumbnail/123/0?size=1024');
    image.complete = true; image.naturalWidth = 0; image.onerror();
    assert.equal(requests.filter(r => r.url.includes('/image/')).length, 2);
    sandbox.openLightbox(1);
    const lightboxImage = nodes.get('lightboxImage');
    sandbox.setOriginalImagesEnabled(true);
    lightboxImage.complete = true; lightboxImage.naturalWidth = 0; lightboxImage.onerror();
    assert.ok(requests.some(r => r.url === '/api/downloaded/image/123/1'));
    sandbox.closeLightbox();
    assert.equal(lightboxImage.src, undefined);
});

test('附页附近只取预览，真正进入视口才取原图，离开立即取消原图', async () => {
    const {sandbox, nodes, requests, observers} = harness();
    sandbox.renderViewer(); await sandbox.expandAll(3);
    sandbox.setOriginalImagesEnabled(true);
    const box = nodes.get('morePages').children[0];
    observers[1].emit(box, true);
    const image = box.querySelector('img'); image.finish();
    assert.equal(requests.some(r => r.url.includes('/image/')), false);
    observers[0].emit(box, true);
    const original = requests.find(r => r.url === '/api/downloaded/image/123/1');
    assert.ok(original);
    const lateLoad = original.image.onload;
    observers[0].emit(box, false);
    assert.equal(original.image.src, undefined);
    await lateLoad();
    assert.equal(image.src, '/api/downloaded/thumbnail/123/1?size=1024');
    assert.equal(box.querySelector('img'), image);
    sandbox.collapseAll();
});

test('翻页与关闭取消旧原图，迟到的解码不能覆盖当前页', async () => {
    const {sandbox, nodes, requests} = harness();
    sandbox.setOriginalImagesEnabled(true); sandbox.openLightbox(0);
    const image = nodes.get('lightboxImage'); image.finish();
    const first = requests.find(r => r.url === '/api/downloaded/image/123/0').image;
    let finishDecode;
    first.decode = () => new Promise(resolve => { finishDecode = resolve; });
    const completion = first.finish(3000, 4000);
    sandbox.lightboxNav(1);
    assert.equal(first.src, undefined);
    finishDecode(); await completion;
    assert.equal(image.src, '/api/downloaded/thumbnail/123/1?size=1024');
    image.finish();
    const second = requests.find(r => r.url === '/api/downloaded/image/123/1').image;
    const lateLoad = second.onload;
    sandbox.closeLightbox(); await lateLoad();
    assert.equal(second.src, undefined); assert.equal(image.src, undefined);
});

test('原图失败保留预览且不反复重试，重新勾选可重试', () => {
    for (const hasPreview of [true, false]) {
        const {sandbox, nodes, requests, toasts} = harness();
        sandbox.wt = key => key;
        sandbox.setOriginalImagesEnabled(true); sandbox.openLightbox(2);
        const image = nodes.get('lightboxImage'); image.finish(hasPreview ? 768 : 0, hasPreview ? 1024 : 0);
        const original = requests.find(r => r.url === '/api/downloaded/image/123/2').image;
        original.onerror(); sandbox.refreshOriginalVisibility();
        assert.equal(image.src, '/api/downloaded/thumbnail/123/2?size=1024');
        assert.equal(requests.filter(r => r.url === '/api/downloaded/image/123/2').length, 1);
        assert.deepEqual(toasts, [hasPreview ? 'status.original-load-failed' : 'status.load-failed']);
        sandbox.setOriginalImagesEnabled(false); sandbox.setOriginalImagesEnabled(true);
        assert.equal(requests.filter(r => r.url === '/api/downloaded/image/123/2').length, 2);
    }
});

test('原图偏好只保存在浏览器，存储变化同步控件，存储不可用仍可操作', () => {
    const {sandbox, nodes, storage, events} = harness();
    sandbox.initOriginalImages();
    nodes.get('originalImagesToggle').listeners.get('change')({target: {checked: true}});
    assert.equal(nodes.get('lightboxOriginalImagesToggle').checked, true);
    const key = [...storage.keys()][0]; storage.set(key, 'false');
    for (const callback of events.get('storage')) callback({key});
    assert.equal(nodes.get('originalImagesToggle').checked, false);
    sandbox.localStorage.setItem = () => { throw Error('Storage disabled'); };
    assert.doesNotThrow(() => sandbox.setOriginalImagesEnabled(true));
    assert.equal(nodes.get('lightboxOriginalImagesToggle').checked, true);
});
