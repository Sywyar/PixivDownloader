'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

function loadRowSync() {
    const sandbox = vm.createContext({});
    vm.runInContext(fs.readFileSync(path.resolve(__dirname,
        '../../main/resources/static/pixiv-batch/queue-row-sync.js'), 'utf8'), sandbox);
    return sandbox.createQueueRowSync;
}

function emptyStore() {
    return {rows: new Map(), rowKeys: [], statusRevision: 0};
}

test('队列同步实例独立保留快照策略，卸载后以同一业务队列重建显示', () => {
    const createQueueRowSync = loadRowSync();
    const queue = [{id: 'same', progress: 1}];
    const first = createQueueRowSync(q => q.id, q => ({value: q.progress}));
    const second = createQueueRowSync(q => q.id, q => ({value: q.progress * 10}));
    const left = emptyStore(), right = emptyStore();
    first.sync(queue, left);
    second.sync(queue, right);
    queue[0].progress = 2;
    first.mark(queue[0]);
    first.sync(queue, left);
    second.sync(queue, right);
    assert.equal(left.rows.get('same').value, 2);
    assert.equal(right.rows.get('same').value, 10, '另一实例未收到变化时不改写快照');
    second.mark(queue[0]);
    second.clear();
    second.sync(queue, right);
    assert.equal(right.rows.get('same').value, 20, '重挂即使队列引用和长度相同也必须重建');
});

test('结构同步只重建变动行，并识别未标记的顶层字段变化', () => {
    const createQueueRowSync = loadRowSync();
    const queue = ['a', 'b', 'c', 'd'].map(id => ({id, status: 'pending', extra: true}));
    let built = 0;
    const rowSync = createQueueRowSync(q => q.id, q => { built++; return {...q}; });
    const store = emptyStore();
    rowSync.sync(queue, store);
    const before = new Map(store.rows);
    built = 0;
    // 完成项沉底：只改变顺序，内容未变的行沿用原快照。
    queue.push(queue.shift());
    rowSync.markStructure();
    rowSync.sync(queue, store);
    assert.deepEqual(Array.from(store.rowKeys), ['b', 'c', 'd', 'a']);
    assert.equal(built, 0, '重排不重建内容未变的行');
    before.forEach((row, key) => assert.equal(store.rows.get(key), row));
    // 未标记的原地修改在结构同步时按顶层字段识别：替换值、删除字段、新增字段。
    queue[0].status = 'completed';
    delete queue[1].extra;
    queue[2].note = 'added';
    rowSync.markStructure();
    rowSync.sync(queue, store);
    assert.equal(built, 3);
    assert.equal(store.rows.get('b').status, 'completed');
    assert.equal('extra' in store.rows.get('c'), false);
    assert.equal(store.rows.get('d').note, 'added');
    assert.equal(store.rows.get('a'), before.get('a'));
    // 移出队列的行同时释放快照。
    queue.pop();
    rowSync.markStructure();
    rowSync.sync(queue, store);
    assert.deepEqual(Array.from(store.rows.keys()).sort(), ['b', 'c', 'd']);
    // 完整同步重建全部快照，承接语言刷新等不改变业务字段的变化。
    built = 0;
    rowSync.mark(null);
    rowSync.sync(queue, store);
    assert.equal(built, 3);
});

test('状态修订号只在结构或行状态变化时递增，纯数值进度不触发', () => {
    const createQueueRowSync = loadRowSync();
    const queue = [{id: 'a', status: 'downloading', progress: 0}, {id: 'b', status: 'pending'}];
    const rowSync = createQueueRowSync(q => q.id, q => ({...q}));
    const store = emptyStore();
    rowSync.sync(queue, store);
    const initial = store.statusRevision;
    for (let progress = 1; progress <= 50; progress++) {
        queue[0].progress = progress;
        rowSync.mark(queue[0]);
        rowSync.sync(queue, store);
    }
    assert.equal(store.statusRevision, initial);
    assert.equal(store.rows.get('a').progress, 50);
    queue[0].status = 'completed';
    rowSync.mark(queue[0]);
    rowSync.sync(queue, store);
    assert.equal(store.statusRevision, initial + 1);
    queue.reverse();
    rowSync.markStructure();
    rowSync.sync(queue, store);
    assert.equal(store.statusRevision, initial + 2);
});

for (const variant of ['classic', 'alt']) {
    test(variant + '：500 项队列按脏行同步，结构变动重建索引且不复活旧对象', async () => {
        const state = {queue: Array.from({length: 500}, (_, id) => ({id, kind: 'illust', progress: 0}))};
        const element = {querySelector: () => element};
        const document = {createElement: () => ({}), querySelector: () => element, getElementById: () => element, contains: () => true};
        let lookups = 0;
        const sandbox = {console, document, state, requestAnimationFrame() {}, setTimeout,
            queueProgressSnapshot:q=>Object.assign({},q),
            PixivBatchAlt: {}, PixivBatch: {state: {state}, queueTypes: {queueKey(q) { lookups++; return q.kind + ':' + q.id; }}}};
        sandbox.window = sandbox;
        vm.createContext(sandbox, {codeGeneration: {strings: false, wasm: false}});
        vm.runInContext(fs.readFileSync(path.resolve(__dirname, '../../../../pixivdownload-app/src/main/resources/static/vendor/vue/vue.global.prod.js'), 'utf8'), sandbox);
        sandbox.PixivVue = {ensure: async () => sandbox.Vue, mountOn: async () => ({app: {unmount() {}}})};
        const file = variant === 'alt' ? 'pixiv-batch-alt/alt-queue-vue.js' : 'pixiv-batch/batch-queue-vue.js';
        vm.runInContext(fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/pixiv-batch/queue-row-sync.js'), 'utf8'), sandbox);
        vm.runInContext(fs.readFileSync(path.resolve(__dirname, '../../main/resources/static', file), 'utf8'), sandbox);
        const api = variant === 'alt' ? sandbox.PixivBatchAlt.queueVue : sandbox.PixivBatch.queueVue;
        await (variant === 'alt' ? api.ensure() : api.mountDownloadQueue());
        const sync = variant === 'alt' ? api.syncList : api.syncDownloadList;
        const store = variant === 'alt' ? api.__test.dockStore() : api.__test.downloadStore();
        const rows = () => sandbox.Vue.toRaw(store.rows);
        const rowAt = index => rows().get(store.rowKeys[index]);
        sync(); api.flush();
        const keys = store.rowKeys, snapshots = state.queue.map((_, index) => rowAt(index));
        lookups = 0;
        state.queue[3].progress = 1;
        sync(state.queue[3]); api.flush();
        assert.equal(lookups, 1, '同步成本不随未变化行数量增加');
        assert.equal(store.rowKeys, keys);
        assert.equal(snapshots.filter((q, index) => q === rowAt(index)).length, 499);
        assert.equal(rowAt(3).progress, 1);
        state.queue[3].liveStatus = {phase: 'running'};
        sync(state.queue[3]); api.flush();
        const row = rowAt(3);
        state.queue[3].liveStatus.phase = 'finished';
        sync(state.queue[3]); api.flush();
        assert.notEqual(rowAt(3), row, '嵌套对象原地修改仍通知子组件');
        assert.equal(rowAt(3).liveStatus.phase, 'finished');
        const removed = state.queue[3];
        sync(removed);
        state.queue.splice(3, 1, {id: 3, kind: 'novel', progress: 7});
        sync(); api.flush();
        assert.equal(rowAt(3).kind, 'novel');
        assert.equal(rows().has('illust:3'), false, '替换后释放旧行快照');
        sync(removed); api.flush();
        assert.equal(rowAt(3).progress, 7);
        state.queue.reverse(); sync(); api.flush();
        assert.equal(rowAt(0).id, 499);
        state.queue = []; sync(); api.flush();
        assert.equal(store.rowKeys.length, 0);
        assert.equal(rows().size, 0);
        api.__test.reset();
    });
}
