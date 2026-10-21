'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function context() {
    const nodes = new Map();
    const image = {id: 'lightboxImage', dataset: {}, hidden: false, after(video) { nodes.set(video.id, video); }};
    nodes.set(image.id, image);
    nodes.set('lightbox', {classList: {remove() {}}});
    const runtime = vm.createContext({
        document: {
            getElementById: id => nodes.get(id),
            querySelectorAll: () => [],
            addEventListener() {},
            createElement: tag => ({tagName: tag, remove() { nodes.delete(this.id); }}),
        },
        IntersectionObserver: class { observe() {} },
        fetch: async () => ({ok: true, headers: {get: () => 'video/mp4'}}),
        loadImage: async url => url,
    });
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../../main/resources/static/pixiv-showcase/showcase-render.js'), 'utf8'), runtime);
    return {runtime, nodes, image};
}

test('展示页复用媒体位置，关闭放大查看时移除播放器', async () => {
    const {runtime, nodes, image} = context();
    await runtime.setArtworkMedia(image, '/api/downloaded/image/1/0');
    const video = nodes.get('lightboxImage-video');
    assert.equal(video.tagName, 'video');
    assert.equal(video.controls, true);
    assert.equal(video.src, '/api/downloaded/image/1/0');
    assert.equal(image.hidden, true);
    await runtime.setArtworkMedia(image, '/api/downloaded/image/1/0');
    assert.notEqual(nodes.get('lightboxImage-video'), video);
    assert.equal([...nodes.keys()].filter(id => id.endsWith('-video')).length, 1);
    runtime.closeLightbox();
    assert.equal(nodes.has('lightboxImage-video'), false);
});

test('切换回图片时恢复图片，已关闭的查看器拒绝迟到探测结果', async () => {
    const {runtime, nodes, image} = context();
    await runtime.setArtworkMedia(image, '/video');
    runtime.fetch = async () => ({ok: true, headers: {get: () => 'image/png'}});
    await runtime.setArtworkMedia(image, '/image');
    assert.equal(image.hidden, false);
    assert.equal(image.src, '/image');
    assert.equal(nodes.has('lightboxImage-video'), false);
    let respond;
    runtime.fetch = () => new Promise(resolve => { respond = resolve; });
    const pending = runtime.setArtworkMedia(image, '/video');
    runtime.closeLightbox();
    respond({ok: true, headers: {get: () => 'video/mp4'}});
    await pending;
    assert.equal(nodes.has('lightboxImage-video'), false);
    runtime.fetch = async () => ({ok: true, headers: {get: () => 'image/png'}});
    let finishImage;
    runtime.loadImage = () => new Promise(resolve => { finishImage = resolve; });
    const loading = runtime.setArtworkMedia(image, '/late-image');
    await new Promise(setImmediate);
    runtime.closeLightbox();
    finishImage('/late-image');
    await loading;
    assert.equal(image.src, '/image');
});

test('展示页推荐作品可逐组浏览全部结果，边界按钮禁用并在重新加载后归零', async () => {
    const {runtime, nodes} = context();
    for (const id of ['relatedPrev', 'relatedNext', 'lightboxClose', 'lightboxPrev', 'lightboxNext', 'modalCloseBtn', 'addToCollectionDoneBtn']) {
        nodes.set(id, {addEventListener(type, callback) { this[type] = callback; }});
    }
    nodes.get('lightbox').addEventListener = () => {};
    const grid = {innerHTML: '', querySelectorAll() { return []; }};
    nodes.set('relatedGrid', grid);
    const items = Array.from({length: 23}, (_, index) => ({artworkId: index + 1, title: 'Work ' + index}));
    runtime.state = {relatedItems: items, relatedOffset: 0, artworkId: 100};
    runtime.escapeHtml = value => value;
    runtime.wt = (key, fallback) => fallback;
    runtime.initPageI18n = async () => {};
    runtime.loadArtwork = () => {};
    runtime.closeAddToCollectionModal = () => {};
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../../main/resources/static/pixiv-showcase/showcase-init.js'), 'utf8'), runtime);
    await new Promise(setImmediate);
    const visibleIds = () => [...grid.innerHTML.matchAll(/data-id="(\d+)"/g)].map(match => Number(match[1]));
    runtime.renderRelatedGrid();
    assert.deepEqual(visibleIds(), [1,2,3,4,5,6,7,8,9,10]);
    assert.equal(nodes.get('relatedPrev').disabled, true);
    assert.equal(nodes.get('relatedNext').disabled, false);
    nodes.get('relatedNext').click();
    assert.deepEqual(visibleIds(), [11,12,13,14,15,16,17,18,19,20]);
    nodes.get('relatedNext').click();
    assert.deepEqual(visibleIds(), [21,22,23]);
    assert.equal(nodes.get('relatedNext').disabled, true);
    nodes.get('relatedNext').click();
    assert.deepEqual(visibleIds(), [21,22,23]);
    nodes.get('relatedPrev').click();
    assert.deepEqual(visibleIds(), [11,12,13,14,15,16,17,18,19,20]);
    runtime.api = async () => items.slice(0, 1);
    await runtime.loadRelated();
    assert.deepEqual(visibleIds(), [1]);
    assert.equal(nodes.get('relatedPrev').disabled, true);
    assert.equal(nodes.get('relatedNext').disabled, true);
    runtime.api = async () => { throw new Error('offline'); };
    await runtime.loadRelated();
    assert.deepEqual(visibleIds(), []);
});
