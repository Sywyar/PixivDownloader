'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

function harness(file) {
    const nodes = new Map(), writes = [], reads = [], stored = new Map();
    const checkbox = {checked: false, disabled: false, addEventListener(type, listener) { this[type] = listener; }};
    const row = {dataset: {id: '9'}, querySelector: () => checkbox};
    const node = id => {
        if (!nodes.has(id)) {
            const classes = new Set();
            nodes.set(id, {innerHTML: '', textContent: '', style: {}, value: '', disabled: false,
                classList: {add: c => classes.add(c), remove: c => classes.delete(c), contains: c => classes.has(c), toggle() {}},
                addEventListener(type, listener) { this[type] = listener; },
                querySelectorAll: selector => id === 'addToCollectionList' ? (selector.includes('input') ? [checkbox] : [row]) : []});
        }
        return nodes.get(id);
    };
    const state = {artworkId: 77, activeArtworkId: 77, collections: [{id: 9, name: 'Collection', artworkCount: 0}], collectionMembership: new Set()};
    const h = {node, state, checkbox, writes, reads, stored, holdReads: false};
    const runtime = vm.createContext({
        window: {PixivArtwork: {}, PixivGallery: {}}, state, collectionLoadRevision: 0, HEART_SVG: '', iconHtml: () => '', escapeHtml: String,
        wt: (key, fallback) => fallback, t: (key, fallback) => fallback, listSeparator: () => ' · ', toast() {}, loadCollections: async () => {},
        document: {getElementById: node, addEventListener() {}, querySelectorAll: () => []},
        api(url, options = {}) {
            if (options.method) return new Promise((resolve, reject) => writes.push({url, options, reject, resolve(data = {}) {
                const workId = Number(url.split('/').at(-1));
                if (url !== '/api/collections') stored.set(workId, options.method === 'POST' ? [Number(url.split('/')[3])] : []);
                resolve(data);
            }}));
            if (url === '/api/collections') return Promise.resolve({collections: state.collections});
            if (h.holdReads) return new Promise(resolve => reads.push({url, resolve}));
            return Promise.resolve({collectionIds: stored.get(Number(url.split('/').at(-1))) || []});
        }
    });
    if (file.includes('gallery-views')) {
        vm.runInContext(fs.readFileSync(path.join(__dirname, '../../main/resources/static/pixiv-gallery/gallery-collections.js'), 'utf8'), runtime);
    }
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../../main/resources/static', file), 'utf8'), runtime);
    if (file.includes('gallery-views')) runtime.loadCollections = async () => {};
    return Object.assign(h, {runtime});
}

for (const file of ['pixiv-artwork/artwork-collections.js', 'pixiv-gallery/gallery-views.js']) {
    test(`${file} 收藏写入期间禁用选择，成功与失败后均与确认状态一致`, async () => {
        const h = harness(file);
        h.runtime.renderAddToCollectionList();
        h.checkbox.checked = true;
        const first = h.checkbox.change();
        assert.equal(h.checkbox.disabled, true);
        h.runtime.renderAddToCollectionList();
        assert.equal(h.checkbox.disabled, true, '重绘不能重新启用在途操作');
        h.checkbox.checked = false;
        const duplicate = h.checkbox.change();
        assert.equal(h.writes.length, 1);
        await duplicate;
        h.writes[0].resolve(); await first;
        assert.deepEqual([...h.state.collectionMembership], [9]);
        assert.equal(h.checkbox.checked, true);
        assert.equal(h.checkbox.disabled, false);
        h.checkbox.checked = false;
        const failed = h.checkbox.change();
        h.writes[1].reject(new Error('write failed')); await failed;
        assert.deepEqual([...h.state.collectionMembership], [9]);
        assert.equal(h.checkbox.checked, true);
        assert.equal(h.checkbox.disabled, false);
    });

    test(`${file} 写后刷新不能被旧查询覆盖`, async () => {
        const h = harness(file);
        h.runtime.renderAddToCollectionList(); h.holdReads = true;
        const oldRead = file.includes('gallery-views') ? h.runtime.refreshCollectionMembership() : h.runtime.loadMembership();
        h.checkbox.checked = true;
        const saving = h.checkbox.change();
        h.holdReads = false; h.writes[0].resolve(); await saving;
        h.reads[0].resolve({collectionIds: []}); await oldRead;
        assert.deepEqual([...h.state.collectionMembership], [9]);
        assert.equal(h.checkbox.checked, true);
    });
}

for (const file of ['pixiv-artwork/artwork-collections.js']) {
    test(`${file} 快速新建保留原目标，失败后恢复操作入口`, async () => {
        const h = harness(file);
        h.node('quickCreateName').value = 'First';
        const saving = h.node('quickCreateBtn').click();
        await h.node('quickCreateBtn').click();
        assert.equal(h.writes.length, 1);
        h.runtime.closeAddToCollectionModal();
        h.state.artworkId = 88;
        h.node('quickCreateName').value = 'Second';
        h.writes[0].resolve({id: 12, name: 'First', sortOrder: 0});
        for (let i = 0; i < 8; i++) await Promise.resolve();
        assert.equal(h.writes[1].url, '/api/collections/12/artworks/77');
        h.writes[1].reject(new Error('link failed')); await saving;
        assert.equal(h.node('quickCreateName').value, 'Second');
        assert.equal(h.node('quickCreateBtn').disabled, false);
        assert.deepEqual([...h.state.collectionMembership], []);
    });
}

test('画廊收藏弹窗丢弃旧作品读取，关闭后迟到响应不能重新打开', async () => {
    const h = harness('pixiv-gallery/gallery-views.js');
    h.holdReads = true;
    const first = h.runtime.openAddToCollectionModal(1);
    const second = h.runtime.openAddToCollectionModal(2);
    h.reads[1].resolve({collectionIds: [202]}); await second;
    h.reads[0].resolve({collectionIds: [101]}); await first;
    assert.equal(h.state.activeArtworkId, 2);
    assert.deepEqual([...h.state.collectionMembership], [202]);
    const late = h.runtime.openAddToCollectionModal(3);
    h.runtime.closeAddToCollectionModal();
    h.reads[2].resolve({collectionIds: [303]}); await late;
    assert.equal(h.node('modalAddToCollection').classList.contains('open'), false);
    assert.equal(h.state.activeArtworkId, null);
});

test('画廊卡片同一作品的旧收藏响应不能覆盖新标记', async () => {
    const h = harness('pixiv-gallery/gallery-views.js');
    let liked = false;
    const label = {textContent: ''};
    const heart = {classList: {toggle: (_name, value) => { liked = value; }}};
    h.runtime.document.querySelector = () => ({querySelector: selector => selector === '.thumb-heart' ? heart : label});
    const old = h.runtime.loadMembershipsForCurrentPage([77]);
    const current = h.runtime.loadMembershipsForCurrentPage([77]);
    h.writes[1].resolve({memberships: {77: [9]}}); await current;
    h.writes[0].resolve({memberships: {77: []}}); await old;
    assert.equal(liked, true);
    assert.equal(label.textContent, '♥ Collection');
});
