'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

function harness(variant) {
    const timers = new Map(), requests = [], renders = [], stored = new Map();
    let serializations = 0, timerId = 0;
    const context = vm.createContext({console, Date, Promise, AbortController,
        document: {getElementById: () => null},
        location: {}, PixivBatch: {}, PixivBatchAlt: {state: {}, engine: {}, queue: {}},
        setTimeout(fn) { timers.set(++timerId, fn); return timerId; },
        clearTimeout(id) { timers.delete(id); },
        localStorage: {getItem: key => stored.get(key) ?? null,
            setItem: (key, value) => stored.set(key, String(value)), removeItem: key => stored.delete(key)},
        fetch: async (url, options) => { requests.push({url, body: options.body && JSON.parse(options.body)}); return {ok: true}; },
        JSON: {...JSON, parse: JSON.parse, stringify(value) {
            if (value && value.queue) serializations++;
            return JSON.stringify(value);
        }},
        bt: key => key, novelTranslateMessage() {},
        sendDownload() {}, ensureSharedSSE() {}, closeAllSSE() {}, openSSE() {}, closeSSE() {},
        initQuota() {}, showArchiveCard() {}, triggerAdminPack() {},
        requestQueueItemCancel() {}, bindQueueActions() {}, reconcileQueueItemTypeData() {},
        ensureWorkers() {},
    });
    context.window = context;
    const run = code => vm.runInContext(code, context);
    const load = name => run(fs.readFileSync(path.resolve(__dirname, '../../main/resources/static', name), 'utf8'));
    run("const BASE = '';");
    if (variant === 'alt') {
        load('pixiv-batch-alt/alt-state.js');
        load('pixiv-batch-alt/alt-queue.js');
        load('pixiv-batch-alt/alt-engine-workers.js');
    } else {
        run("let appMode = 'solo', serverState = {}, state = {queue: [], stats: {}};");
        load('pixiv-batch/batch-storage.js');
        for (const name of ['batch-queue-model.js','batch-queue-actions.js','batch-queue-view.js','batch-queue.js','batch-download-workers.js']) {
            load('pixiv-batch/' + name);
        }
    }
    const render = context.renderQueue;
    context.renderQueue = (item, statusChanged) => renders.push({item, statusChanged});
    context.updateStats = () => {};
    run("state.queue = Array.from({length: 500}, (_, id) => ({id: String(id), kind: 'illust', status: 'pending'})); state.isPaused = false;");
    return {context, run, timers, requests, renders, stored, render,
        serializations: () => serializations,
        async flush() {
            const callbacks = [...timers.values()]; timers.clear();
            callbacks.forEach(fn => fn());
            await new Promise(resolve => setImmediate(resolve));
        }};
}

for (const variant of ['classic', 'alt']) {
    test(variant + '：连续状态迁移只刷新变动行，保存合批后只序列化一次', async () => {
        const h = harness(variant);
        for (let i = 0; i < 100; i++) {
            const item = h.context.getNextPending();
            assert.equal(item.status, 'downloading');
            assert.equal(h.renders.at(-1).item, item);
            assert.equal(h.renders.at(-1).statusChanged, true);
            h.context.pauseUnavailableQueueType(item);
        }
        assert.equal(h.renders.length, 200);
        assert.equal(h.serializations(), 0);
        assert.equal(h.timers.size, 1);
        await h.flush();
        assert.equal(h.serializations(), 1);
        const raw = h.requests[0].body.state.pixiv_batch_queue;
        assert.equal(typeof raw, 'string');
        const saved = JSON.parse(raw);
        assert.equal(saved.queue.length, 500);
        assert.equal(saved.queue.filter(item => item.status === 'paused').length, 100);
        assert.equal(h.context.storeGet('pixiv_batch_queue'), raw);
        assert.equal(h.serializations(), 1);
    });

    test(variant + '：同步读取、清空和注销使用最新队列，多人模式仍同步保存', async () => {
        const h = harness(variant);
        h.context.saveQueue();
        const first = h.context.storeGet('pixiv_batch_queue');
        assert.equal(h.serializations(), 1);
        h.run("state.isPaused = true; state.queue[0].status = 'completed'; saveQueue(); serverState.pixiv_cookie = 'fixture';");
        await h.context.doLogout();
        const saved = JSON.parse(h.requests[0].body.state.pixiv_batch_queue);
        assert.equal(saved.queue.find(item => item.id === '0').status, 'completed');
        assert.equal(saved.isPaused, true);
        assert.equal(h.requests[0].body.state.pixiv_cookie, undefined);
        assert.equal(h.requests[1].url, '/api/auth/logout');
        assert.equal(JSON.parse(first).queue[0].status, 'pending');
        h.context.saveQueue();
        const beforeClear = h.serializations();
        h.context.clearSavedQueue();
        assert.equal(h.context.storeGet('pixiv_batch_queue'), null);
        await h.flush();
        assert.equal(h.serializations(), beforeClear);
        assert.equal(h.requests.at(-1).body.state.pixiv_batch_queue, undefined);
        h.run("appMode = 'multi'; saveQueue();");
        assert.equal(h.serializations(), beforeClear + 1);
        assert.equal(JSON.parse(h.stored.get('pixiv_batch_queue')).isPaused, true);
    });

    test(variant + '：命令式单行更新保留其它节点并更新恢复与当前下载控件', () => {
        const h = harness(variant);
        const writes = [];
        const nodes = Array.from({length: 500}, (_, i) => ({set outerHTML(value) { writes.push(i); }}));
        const list = {children: nodes, replaceChild(row, old) { writes.push(nodes.indexOf(old)); }};
        let recovery = 0, current = 0, pack = 0;
        Object.assign(h.context, {document: {getElementById: () => list},
            requestAnimationFrame: fn => h.timers.set('frame', fn),
            downloadQueueVueActive: () => false, altQueueVueActive: () => false,
            renderQueueRecovery: () => recovery++, refreshCurrentCard: () => current++,
            updateAdminPackButton: () => pack++, buildQueueItemHtml: () => 'updated', queueItemRow: () => ({})});
        const item = h.run('state.queue[42]');
        item.status = 'completed';
        h.render(item, true);
        h.timers.get('frame')();
        assert.deepEqual(writes, [42]);
        assert.equal(recovery, 1);
        assert.equal(current, 1);
        if (variant === 'classic') assert.equal(pack, 1);
        assert.equal(list.children[41], nodes[41]);
    });
}

test('新版队列整批前置，终态沉底，保存恢复与从上往下领取复用同一条目', async () => {
    const h = harness('alt');
    Object.assign(h.context, {
        normalizeAuthorId: value => value == null ? null : String(value),
        syncAllResultsQueueState() {}, ensureWorkers() {},
        restoreInterruptedQueueItem: () => false
    });
    h.run("state.queue = [{id:'active', status:'downloading'}, {id:'older', status:'pending'}, {id:'done', status:'completed'}]; state.isRunning = true;");
    const active = h.run('state.queue[0]');
    const ids = () => Array.from(h.run('state.queue'), item => item.id);
    assert.equal(h.context.addItemsToQueue(['new-a', 'new-b', 'new-a', 'active'], [], 'user'), 2);
    assert.deepEqual(ids(), ['new-a', 'new-b', 'active', 'older', 'done']);
    assert.equal(h.run('state.queue[2]'), active);
    assert.equal(active.status, 'downloading');
    const first = h.context.getNextPending();
    assert.equal(first.id, 'new-a');
    h.run("commitQueueItemPatch(state.queue[0], {status:'completed'});");
    assert.deepEqual(ids(), ['new-b', 'active', 'older', 'new-a', 'done']);
    assert.equal(h.run('state.queue[3]'), first);
    const second = h.context.getNextPending();
    assert.equal(second.id, 'new-b');
    h.run("commitQueueItemPatch(state.queue[0], {status:'skipped'});");
    assert.deepEqual(ids(), ['active', 'older', 'new-b', 'new-a', 'done']);
    const saved = JSON.parse(h.context.storeGet('pixiv_batch_queue'));
    assert.deepEqual(saved.queue.map(item => item.id), ids());
    h.context.loadQueueForMode();
    assert.deepEqual(ids(), saved.queue.map(item => item.id));
    assert.equal(h.context.getNextPending().id, 'older');

    const synced = [];
    Object.assign(h.context, {
        renderQueueRecovery() {}, refreshCurrentCard() {},
        altQueueVueActive: () => true,
        altQueueVue: () => ({syncList: item => synced.push(item)})
    });
    h.render(second, true);
    assert.equal(synced.pop(), null, '重排必须同步行结构，不能把旧下标当成新下标');
    const pendingArray = h.run('state.queue');
    const item = pendingArray[1];
    for (let i = 0; i < 100; i++) {
        item.downloadedCount = i;
        h.render(item);
        assert.equal(synced.pop(), item, '纯进度保持单行同步');
        assert.equal(h.run('state.queue'), pendingArray);
    }
});

test('经典打包按钮跳过纯进度扫描，完成、清空和权限刷新仍立即生效', () => {
    const h = harness('classic');
    h.run(fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/pixiv-batch/batch-download.js'), 'utf8'));
    const button = {style: {}, disabled: false};
    Object.assign(h.context, {
        isAdmin: true,
        document: {getElementById: id => id === 'admin-pack-btn' ? button : {}},
        downloadQueueVueActive: () => true,
        refreshCurrentCard() {}, renderQueueRecovery() {},
        queueVue: () => ({syncDownloadList() {}})
    });
    h.render();assert.equal(button.disabled, true);
    const queue = h.run('state.queue');
    let reads = 0;
    for (const item of queue) {
        let status = item.status;
        Object.defineProperty(item, 'status', {get() { reads++; return status; }, set(value) { status = value; }});
    }
    for (let i = 0; i < 100; i++) { queue[0].downloadedCount = i; h.render(queue[0]); }
    assert.equal(reads, 0);
    assert.equal(button.disabled, true);
    queue[0].status = 'completed';h.render(queue[0], true);
    assert.equal(button.disabled, false);
    queue[0].status = 'pending';h.render(queue[0], true);
    assert.equal(button.disabled, true);
    queue[0].status = 'completed';h.context.updateButtonsState();
    assert.equal(button.disabled, false);
    h.context.isAdmin = false;h.context.updateButtonsState();
    assert.equal(button.disabled, true);assert.equal(button.style.display, 'none');
    h.context.isAdmin = true;h.context.updateButtonsState();
    assert.equal(button.disabled, false);assert.equal(button.style.display, 'inline-flex');
    h.run('state.queue = []');h.render();
    assert.equal(button.disabled, true);
});

test('新版队列延迟序列化保持凭据写入顺序，失败后后续保存仍可执行', async () => {
    const h = harness('alt');
    let release;
    h.context.fetch = async (url, options) => {
        h.requests.push(JSON.parse(options.body));
        if (h.requests.length === 1) return new Promise(resolve => { release = resolve; });
        return {ok: true};
    };
    h.context.saveQueue();
    const first = h.context.persistStoreEntries({});
    const credential = h.context.persistStoreEntries({pixiv_cookie: 'new-fixture'});
    await new Promise(resolve => setImmediate(resolve));
    h.run("state.queue[0].status = 'completed'; saveQueue();");
    const last = h.context.persistStoreEntries({});
    release({ok: false, status: 503});
    await assert.rejects(first, /503/);
    await credential;
    await last;
    assert.equal(h.requests[2].state.pixiv_cookie, 'new-fixture');
    assert.equal(JSON.parse(h.requests[2].state.pixiv_batch_queue).queue.find(item => item.id === '0').status, 'completed');
    assert.equal(h.serializations(), 2);
});
