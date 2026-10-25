'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const response = (data, status = 200) => ({ok: status === 200, status, json: async () => data});
const tick = async () => { for (let i = 0; i < 12; i++) await Promise.resolve(); };

function harness(gallery) {
    const nodes = new Map(), writes = [], reads = [], stored = new Map(), notices = [];
    const checkbox = {checked: false, disabled: false, addEventListener(type, listener) { this[type] = listener; }};
    const row = {dataset: {id: '9'}, querySelector: () => checkbox};
    const node = id => {
        if (!nodes.has(id)) {
            const classes = new Set();
            nodes.set(id, {innerHTML: '', textContent: '', style: {}, value: '', disabled: false,
                classList: {add: c => classes.add(c), remove: c => classes.delete(c), contains: c => classes.has(c), toggle() {}},
                addEventListener(type, listener) { this[type] = listener; },
                querySelectorAll: () => id === 'addToCollectionList' ? [row] : []});
        }
        return nodes.get(id);
    };
    const state = {collections: [{id: 9, name: 'Collection', novelCount: 0}]};
    const h = {node, state, checkbox, writes, reads, stored, notices, holdReads: false};
    const runtime = vm.createContext({
        window: {PixivNovel: {}, addEventListener() {}}, state, novelId: 77, collectionLoadRevision: 0,
        collectionState: {list: state.collections, membership: new Set()},
        HEART_SVG: '', HEART_DEFAULT_SVG: '', escapeHtml: String, esc: String,
        pageI18n: {t: (key, fallback) => fallback}, toast: (...args) => notices.push(args),
        loadCollections: async () => {}, renderCollections() {}, renderCollectionFilterChips() {},
        bindNovelGalleryReturn() {}, openDeleteNovelModal() {}, closeDeleteNovelModal() {}, confirmDeleteNovel() {},
        setupAdminMode() {}, loadAll() {}, closeMobileSidebar() {}, openMobileSidebar() {}, releaseCollectionIconPreview() {},
        PixivActions: {bind() {}}, sessionStorage: {setItem() {}}, location: {pathname: '/', search: ''},
        document: {getElementById: node, addEventListener() {}, querySelectorAll: () => []},
        fetch(url, options = {}) {
            if (options.method) return new Promise(resolve => writes.push({url, options, resolve(data = {}, status = 200) {
                if (status === 200 && url !== '/api/collections') {
                    const workId = Number(url.split('/').at(-1));
                    stored.set(workId, options.method === 'POST' ? [Number(url.split('/')[3])] : []);
                }
                resolve(response(data, status));
            }}));
            if (url === '/api/collections') return Promise.resolve(response({collections: state.collections}));
            if (h.holdReads) return new Promise(resolve => reads.push({url, resolve: data => resolve(response(data))}));
            return Promise.resolve(response({collectionIds: stored.get(Number(url.split('/').at(-1))) || []}));
        }
    });
    const directory = gallery ? 'pixiv-novel-gallery' : 'pixiv-novel';
    const prefix = gallery ? 'novel-gallery' : 'novel';
    const read = name => fs.readFileSync(path.join(__dirname, '../../main/resources/static', directory, name), 'utf8');
    vm.runInContext(read(`${prefix}-collections.js`), runtime);
    if (gallery) {
        vm.runInContext(read(`${prefix}-views.js`), runtime);
        runtime.loadCollections = async () => {};
    }
    if (gallery) vm.runInContext('collectionState.activeNovelId = 77', runtime);
    let init = read(`${prefix}-init.js`);
    if (gallery) init = init.replace(/\ninit\(\);\s*$/, '');
    vm.runInContext(init, runtime);
    return Object.assign(h, {runtime, collection: vm.runInContext('collectionState', runtime)});
}

for (const gallery of [false, true]) {
    const label = gallery ? '小说画廊' : '小说详情';
    test(`${label} 收藏写入阻止重入，并在失败后恢复已确认状态`, async () => {
        const h = harness(gallery);
        h.runtime.renderAddToCollectionList();
        h.checkbox.checked = true;
        const first = h.checkbox.change();
        assert.equal(h.checkbox.disabled, true);
        h.runtime.renderAddToCollectionList();
        assert.equal(h.checkbox.disabled, true);
        await h.checkbox.change();
        assert.equal(h.writes.length, 1);
        h.writes[0].resolve(); await first;
        assert.deepEqual([...h.collection.membership], [9]);
        assert.equal(h.checkbox.checked, true);
        h.checkbox.checked = false;
        const failed = h.checkbox.change();
        h.writes[1].resolve({}, 500); await failed;
        assert.equal(h.checkbox.checked, true);
        assert.equal(h.checkbox.disabled, false);
        assert.equal(h.notices.at(-1)[1], 'error');
    });

    test(`${label} 快速新建的关联失败不能报告成功`, async () => {
        const h = harness(gallery);
        h.node('quickCreateName').value = 'Created collection';
        const saving = h.node('quickCreateBtn').click();
        assert.equal(h.node('quickCreateBtn').disabled, true);
        await h.node('quickCreateBtn').click();
        assert.equal(h.writes.length, 1);
        h.writes[0].resolve({id: 12, name: 'Created collection', sortOrder: 0}); await tick();
        assert.equal(h.writes[1].url, '/api/collections/12/novels/77');
        h.writes[1].resolve({}, 500); await saving;
        assert.equal(h.collection.membership.has(12), false);
        assert.equal(h.node('quickCreateBtn').disabled, false);
        assert.deepEqual(h.notices.map(n => n[1]), ['error']);
    });

    test(`${label} 写入使在途旧读取失效`, async () => {
        const h = harness(gallery);
        h.runtime.renderAddToCollectionList(); h.holdReads = true;
        const oldRead = gallery ? h.runtime.refreshCollectionMembership() : h.runtime.refreshMembership();
        h.checkbox.checked = true;
        const saving = h.checkbox.change();
        h.holdReads = false; h.writes[0].resolve(); await saving;
        h.reads[0].resolve({collectionIds: []}); await oldRead;
        assert.deepEqual([...h.collection.membership], [9]);
        assert.equal(h.checkbox.checked, true);
    });
}

test('小说画廊快速新建绑定原作品，并保留重开弹窗的草稿', async () => {
    const h = harness(true);
    h.node('quickCreateName').value = 'First draft';
    const saving = h.node('quickCreateBtn').click();
    h.runtime.closeAddToCollectionModal();
    await h.runtime.openAddToCollectionModal(88);
    h.node('quickCreateName').value = 'Second draft';
    h.writes[0].resolve({id: 12, name: 'First draft', sortOrder: 0}); await tick();
    assert.equal(h.writes[1].url, '/api/collections/12/novels/77');
    h.writes[1].resolve(); await saving;
    assert.equal(h.node('quickCreateName').value, 'Second draft');
    assert.equal(h.collection.activeNovelId, 88);
    assert.deepEqual([...h.collection.membership], []);
});

test('小说画廊旧作品读取和关闭前的读取不能覆盖新弹窗', async () => {
    const h = harness(true); h.holdReads = true;
    const first = h.runtime.openAddToCollectionModal(1);
    const second = h.runtime.openAddToCollectionModal(2);
    h.reads[1].resolve({collectionIds: [202]}); await second;
    h.reads[0].resolve({collectionIds: [101]}); await first;
    assert.deepEqual([...h.collection.membership], [202]);
    const late = h.runtime.openAddToCollectionModal(3);
    h.runtime.closeAddToCollectionModal();
    h.reads[2].resolve({collectionIds: [303]}); await late;
    assert.equal(h.node('modalAddToCollection').classList.contains('open'), false);
    assert.equal(h.collection.activeNovelId, null);
});

test('小说卡片同一作品的旧收藏响应不能覆盖新标记', async () => {
    const h = harness(true);
    let liked = false;
    const label = {textContent: ''};
    const heart = {classList: {toggle: (_name, value) => { liked = value; }}};
    h.runtime.document.querySelector = () => ({querySelector: selector => selector === '.thumb-heart' ? heart : label});
    const old = h.runtime.loadMembershipsForNovels([77]);
    const current = h.runtime.loadMembershipsForNovels([77]);
    h.writes[1].resolve({memberships: {77: [9]}}); await current;
    h.writes[0].resolve({memberships: {77: []}}); await old;
    assert.equal(liked, true);
    assert.equal(label.textContent, '♥ Collection');
});
