'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

test('队列同步实例独立保留快照策略，卸载后以同一业务队列重建显示', () => {
    const sandbox = vm.createContext({});
    vm.runInContext(fs.readFileSync(path.resolve(__dirname,
        '../../main/resources/static/pixiv-batch/queue-row-sync.js'), 'utf8'), sandbox);
    const queue = [{id: 'same', progress: 1}];
    const first = sandbox.createQueueRowSync(q => q.id, q => ({value: q.progress}));
    const second = sandbox.createQueueRowSync(q => q.id, q => ({value: q.progress * 10}));
    const left = {items: []}, right = {items: []};
    first.sync(queue, left);
    second.sync(queue, right);
    queue[0].progress = 2;
    first.mark(queue[0]);
    first.sync(queue, left);
    second.sync(queue, right);
    assert.equal(left.items[0].value, 2);
    assert.equal(right.items[0].value, 10, '另一实例未收到变化时不改写快照');
    second.mark(queue[0]);
    second.clear();
    second.sync(queue, right);
    assert.equal(right.items[0].value, 20, '重挂即使队列引用和长度相同也必须重建');
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
        sync(); api.flush();
        const array = store.items, snapshots = array.slice();
        lookups = 0;
        state.queue[3].progress = 1;
        sync(state.queue[3]); api.flush();
        assert.equal(lookups, 1, '同步成本不随未变化行数量增加');
        assert.equal(store.items, array);
        assert.equal(array.filter((q, i) => q === snapshots[i]).length, 499);
        assert.equal(array[3].progress, 1);
        state.queue[3].liveStatus = {phase: 'running'};
        sync(state.queue[3]); api.flush();
        const row = array[3];
        state.queue[3].liveStatus.phase = 'finished';
        sync(state.queue[3]); api.flush();
        assert.notEqual(array[3], row, '嵌套对象原地修改仍通知子组件');
        assert.equal(array[3].liveStatus.phase, 'finished');
        const removed = state.queue[3];
        sync(removed);
        state.queue.splice(3, 1, {id: 3, kind: 'novel', progress: 7});
        sync(); api.flush();
        assert.equal(store.items[3].kind, 'novel');
        sync(removed); api.flush();
        assert.equal(store.items[3].progress, 7);
        state.queue.reverse(); sync(); api.flush();
        assert.equal(store.items[0].id, 499);
        state.queue = []; sync(); api.flush();
        assert.equal(store.items.length, 0);
        api.__test.reset();
    });
}
