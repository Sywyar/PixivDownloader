'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const modes = path.join(__dirname, '../../main/resources/static/pixiv-batch/modes');

function harness(inner, {existing = false, downloading = false, keep = false, changed = false} = {}) {
    const calls = [];
    const statuses = [];
    const cards = new Map();
    const kind = inner ? 'item-type' : 'outer-type';
    const id = kind + ':42';
    const queue = existing ? [{id, status: downloading ? 'downloading' : 'idle'}] : [];
    const sandbox = {
        window: {PixivBatch: {queueTypes: {
            acquisition(type) {
                return {
                    queueId(item) { return type + ':' + item.id; },
                    gridCardId(prefix, index) { return `${prefix}-${index}`; },
                    buildQueueMeta(item) {
                        return {kind: type, title: item.title, authorId: 'author', authorName: 'Author'};
                    }
                };
            }
        }}},
        document: {
            addEventListener() {},
            getElementById(cardId) {
                return {classList: {toggle(name, value) { cards.set(cardId + ':' + name, value); }}};
            }
        },
        state: {queue}, QUICK_FETCH_MODE: 'quick-fetch',
        setStatus(message, level) { statuses.push({key: message.key, title: message.args.title, level}); },
        reconcileQueueItemTypeData(item, meta, reason) {
            calls.push(['merge', item.id, meta.kind, reason]);
            return {keepExisting: keep, changed};
        },
        updateStats() { calls.push('stats'); },
        saveQueue() { calls.push('save'); },
        renderQueue() { calls.push('render'); },
        removeFromQueue(value) {
            calls.push(['remove', value]);
            if (downloading) return false;
            queue.splice(0, 1);
            return true;
        },
        addItemsToQueue(ids, metas, mode, username, authorId, authorName) {
            calls.push(['add', ids[0], metas[0].kind, mode, username, authorId, authorName]);
            queue.push({id: ids[0]});
            return 1;
        }
    };
    vm.createContext(sandbox);
    for (const name of ['quick-fetch-core.js', 'quick-fetch-outer.js', 'quick-fetch-inner.js']) {
        vm.runInContext(fs.readFileSync(path.join(modes, name), 'utf8'), sandbox, {filename: name});
    }
    vm.runInContext(`
        quickState.kind = 'outer-type';
        quickState.viewType = 'works-list';
        quickState.items = [{id: '42', title: 'Outer'}];
        quickInner.kind = 'inner-fallback';
        quickInner.open = true;
        quickInner.items = [{id: '42', kind: 'item-type', title: 'Inner'}];
    `, sandbox);
    return {calls, statuses, cards, queue, id, sandbox,
        toggle: index => inner ? sandbox.quickInnerToggleQueue(index) : sandbox.quickToggleItemQueue(index)};
}

for (const inner of [false, true]) {
    test(`${inner ? '内层' : '外层'}整页入队保留每件作品的类型与元数据`, () => {
        const h = harness(inner);
        const state = vm.runInContext(inner ? 'quickInner' : 'quickState', h.sandbox);
        state.items.push({id: '43', kind: 'second-type', title: 'Second'});
        let submitted;
        h.sandbox.addItemsToQueue = (ids, metas) => {
            submitted = {ids: Array.from(ids), metas: JSON.parse(JSON.stringify(metas))};
            return ids.length;
        };
        if (inner) h.sandbox.quickInnerAddCurrentPageToQueue();
        else h.sandbox.quickAddCurrentPageToQueue();
        const kinds = inner ? ['item-type', 'second-type'] : ['outer-type', 'outer-type'];
        assert.deepEqual(submitted.ids, kinds.map((kind, index) => `${kind}:${42 + index}`));
        assert.deepEqual(submitted.metas.map(meta => meta.kind), kinds);
        assert.deepEqual(submitted.metas.map(meta => meta.title), [inner ? 'Inner' : 'Outer', 'Second']);
    });
}

for (const inner of [false, true]) {
    const label = inner ? '内层' : '外层';
    test(`${label}切换按自身类型加入队列并同步两层卡片`, () => {
        const h = harness(inner);
        h.toggle(0);
        assert.deepEqual(h.calls, [['add', h.id, inner ? 'item-type' : 'outer-type',
            'quick-fetch', '', 'author', 'Author']]);
        assert.deepEqual(h.queue, [{id: h.id}]);
        assert.deepEqual(h.statuses, [{key: 'status.added-to-queue', title: inner ? 'Inner' : 'Outer', level: 'success'}]);
        assert.equal(h.cards.get('quick-0:in-queue'), !inner);
        assert.equal(h.cards.get('quick-inner-card-0:in-queue'), inner);
    });

    for (const downloading of [false, true]) {
        test(`${label}已有项${downloading ? '下载中禁止移除' : '可移除并刷新卡片'}`, () => {
            const h = harness(inner, {existing: true, downloading});
            h.toggle(0);
            assert.deepEqual(h.calls, [['merge', h.id, inner ? 'item-type' : 'outer-type', 'toggle'], ['remove', h.id]]);
            assert.equal(h.queue.length, downloading ? 1 : 0);
            assert.equal(h.statuses[0].key, downloading ? 'status.cannot-remove-downloading' : 'status.removed-from-queue');
            assert.equal(h.statuses[0].level, downloading ? 'warning' : 'info');
            assert.equal(h.cards.get(inner ? 'quick-inner-card-0:in-queue' : 'quick-0:in-queue'), downloading);
        });
    }

    for (const changed of [false, true]) {
        test(`${label}类型合并保留已有项且${changed ? '只保存刷新一次' : '无变化不保存刷新'}`, () => {
            const h = harness(inner, {existing: true, keep: true, changed});
            h.toggle(0);
            assert.deepEqual(h.calls, [['merge', h.id, inner ? 'item-type' : 'outer-type', 'toggle'],
                ...(changed ? ['stats', 'save', 'render'] : [])]);
            assert.equal(h.queue.length, 1);
            assert.equal(h.statuses[0].key, 'status.already-in-queue');
            assert.equal(h.cards.size, 0);
        });
    }

    test(`${label}不存在的卡片不改变队列或提示`, () => {
        const h = harness(inner);
        h.toggle(3);
        assert.deepEqual(h.calls, []);
        assert.deepEqual(h.statuses, []);
        assert.equal(h.queue.length, 0);
        assert.equal(h.cards.size, 0);
    });
}
