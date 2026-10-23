'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {harness, manifest, typeDescriptor, waitUntil, DOWNLOAD_SOURCE, SSE_SOURCE}
    = require('./batch-queue-types-fixture');
const root = path.join(__dirname, '../../main/resources/static');
const read = file => fs.readFileSync(path.join(root, file), 'utf8');

async function fixture(alt, stage) {
    const h = harness([
        manifest(1, [typeDescriptor({acquisitionModes: []})]), manifest(2, [])
    ], {'/modules/demo.js': {initializer: `(function () {
        return {descriptor: {process: processIllustItem}};
    })`}});
    const c = h.sandbox;
    const item = {id: '42', kind: 'demo', status: 'downloading'};
    const writes = [], requests = [], timers = new Map(), intervals = new Map();
    let timerId = 0, delayed, release;
    c.window.PixivBatchAlt = {engine: {}};
    Object.assign(c, {
        state: {settings: {skipHistory: ['history', 'history-meta', 'recovery'].includes(stage),
            verifyHistoryFiles: true, fileNameTemplate: 'worker_{artwork_id}'}, queue: [item], sseListeners: {}, sseRefs: {}},
        dockState: {quota: {enabled: false}}, isAdmin: true,
        bt: key => key, getCookie: () => '', getImageDelayMs: () => 0,
        normalizeAuthorId: value => Number(value) || null,
        resolveBatchCollectionIdForDownload: async () => null,
        evaluateDownloadFilterSkip: () => null,
        renderQueue() {}, updateStats() {}, setCurrent() {}, renderCurrent() {},
        clearSavedQueue() {}, syncAllResultsQueueState() {}, stopSpeedMeter() {},
        setStatus() {}, setDockStatus() {}, updateButtonsState() {},
        saveQueue() { writes.push(JSON.parse(JSON.stringify(item))); },
        setInterval, clearInterval,
        mergeUgoiraProgress: (previous, incoming) => incoming === undefined ? previous : incoming
    });
    vm.runInContext(read('pixiv-batch/media-settings.js'), c);
    vm.runInContext(read('pixiv-batch/batch-path-actions.js'), c);
    if (alt) {
        for (const file of ['pixiv-batch/batch-archive.js', 'pixiv-batch/filename-template.js',
            'pixiv-batch/batch-artwork-requests.js', 'pixiv-batch/download-workers.js',
            'pixiv-batch/download-sse.js', 'pixiv-batch-alt/alt-engine.js',
            'pixiv-batch-alt/alt-engine-stream.js', 'pixiv-batch-alt/alt-engine-workers.js']) {
            vm.runInContext(read(file), c, {filename: file});
        }
    } else {
        vm.runInContext(DOWNLOAD_SOURCE, c);
        vm.runInContext(SSE_SOURCE, c);
    }
    await h.qt.bootstrap();
    assert.ok(h.qt.has('demo'));
    c.updateButtonsState = () => {};
    Object.assign(c, {
        setTimeout: callback => { timers.set(++timerId, callback); return timerId; },
        clearTimeout: id => timers.delete(id),
        setInterval: callback => { intervals.set(++timerId, callback); return timerId; },
        clearInterval: id => intervals.delete(id)
    });
    const manifestFetch = c.fetch;
    c.fetch = async (url, init = {}) => {
        if (String(url).includes('/api/download/extensions')) return manifestFetch(url, init);
        const request = {url: String(url), init};
        requests.push(request);
        let data, point;
        if (url.includes('/recover-metadata')) { point = 'recovery'; data = {title: 'Recovered'}; }
        else if (url.includes('/api/downloaded/')) {
            point = 'history'; data = {artworkId: 42, title: stage === 'history' ? 'Existing' : ''};
        } else if (url.endsWith('/meta')) {
            point = stage === 'history-meta' ? 'history-meta' : 'meta';
            data = {illustTitle: 'Example', authorId: 7, illustType: stage === 'ugoira' ? 2 : 0};
        } else if (url.endsWith('/pages')) { point = 'pages'; data = {urls: ['https://example.test/image.png']}; }
        else if (url.endsWith('/ugoira')) { point = 'ugoira'; data = {zipUrl: 'https://example.test/animation.zip', delays: [100]}; }
        else if (url.endsWith('/api/download/pixiv')) { point = 'submit'; data = {}; }
        else if (url.endsWith('/api/download/queue/clear')) { point = 'clear'; data = {}; }
        else if (url.includes('/api/download/status/')) { point = stage; data = {completed: true, downloadedCount: 1}; }
        else throw new Error('Unexpected request: ' + url);
        return {ok: true, status: 200, json: () => {
            if (point !== stage) return Promise.resolve(data);
            delayed = request;
            // 保留已收到正文的迟到结果，验证取消信号之外的 publication 检查。
            return new Promise(resolve => { release = () => resolve(data); });
        }};
    };
    c.apiGet = async (url, init) => (await c.fetch(url, init)).json();
    const emit = data => [...(c.state.sseListeners['42'] || [])].forEach(listener => listener(data));
    return {h, c, item, writes, requests, timers, intervals, emit,
        delayed: () => delayed, release: () => release && release(),
        cleanup() { h.qt.dispose(); c.endPathActionBatch(); emit({cancelled: true}); }};
}

for (const alt of [false, true]) {
    const layout = alt ? '新版' : '经典';
    test(`${layout} 清空后重新下载同一作品，旧正文结算不移除新进度等待`, async t => {
        const f = await fixture(alt, 'meta');
        t.after(f.cleanup);
        const oldProcess = f.c.processSingle(f.item);
        await waitUntil(() => !!f.delayed());
        f.c.clearDownloadQueue();
        const fetch = f.c.fetch;
        f.c.fetch = (url, options) => String(url).endsWith('/meta')
            ? Promise.resolve({ok: true, json: async () => ({illustTitle: 'New request', authorId: 7, illustType: 0})})
            : fetch(url, options);
        const next = {id: '42', kind: 'demo', status: 'downloading'};
        f.c.state.queue = [next];
        const newProcess = f.c.processSingle(next);
        await waitUntil(() => f.intervals.size > 0);
        f.release();
        await oldProcess;
        assert.equal(f.c.state.sseListeners['42']?.length, 1);
        assert.equal(f.c.state.sseRefs['42'], 1);
        f.emit({completed: true, downloadedCount: 1});
        await newProcess;
        assert.equal(next.status, 'completed');
        assert.equal(f.timers.size, 0);
        assert.equal(f.intervals.size, 0);
    });
    for (const stage of ['history', 'history-meta', 'recovery', 'meta', 'pages', 'ugoira', 'submit', 'status', 'status-confirm']) {
        test(`${layout} ${stage} 正文迟到时，清空队列取消旧调用且不再提交或保存`, async t => {
            const f = await fixture(alt, stage);
            t.after(f.cleanup);
            const processing = f.c.processSingle(f.item);
            if (stage.startsWith('status')) {
                await waitUntil(() => f.intervals.size > 0);
                [...(stage === 'status' ? f.intervals : f.timers).values()][0]();
            }
            await waitUntil(() => !!f.delayed());
            const requestCount = f.requests.length, previousWrites = f.writes.length;
            f.c.clearDownloadQueue();
            f.release();
            f.emit({completed: true, downloadedCount: 1});
            await processing;
            assert.equal(f.requests.filter(r => !r.url.endsWith('/queue/clear')).length, requestCount);
            assert.equal(f.writes.length, previousWrites);
            assert.equal(f.c.state.queue.length, 0);
            assert.equal(f.delayed().init.signal?.aborted, true);
            assert.equal(f.timers.size, 0);
            assert.equal(f.intervals.size, 0);
            assert.equal(f.c.state.sseListeners['42'], undefined);
        });

        test(`${layout} ${stage} 正文迟到时，撤回类型会暂停且不继续请求或提交终态`, async t => {
            const f = await fixture(alt, stage);
            t.after(f.cleanup);
            const processing = f.c.processSingle(f.item);
            if (stage.startsWith('status')) {
                await waitUntil(() => f.intervals.size > 0);
                [...(stage === 'status' ? f.intervals : f.timers).values()][0]();
            }
            await waitUntil(() => !!f.delayed());
            const requestCount = f.requests.length, previousWrites = f.writes.length;
            await f.h.qt.refresh();
            f.release();
            // 旧实现未中止 SSE 时仍让夹具结算，以观察错误写入而不遗留等待。
            f.emit({completed: true, downloadedCount: 1});
            await processing;
            assert.equal(f.requests.length, requestCount);
            assert.equal(f.item.status, 'paused');
            assert.equal(f.item.endTime, null);
            assert.equal(f.item.lastMessage, 'queue.message.type-unavailable');
            assert.deepEqual(f.writes.slice(previousWrites).map(item => item.status), ['paused']);
            assert.equal(f.delayed().init.signal?.aborted, true);
            assert.equal(f.timers.size, 0);
            assert.equal(f.intervals.size, 0);
            assert.equal(f.c.state.sseListeners['42'], undefined);
        });
    }

    test(`${layout} 撤回类型中止在途历史请求，传输拒绝仍保留暂停记录`, async t => {
        const f = await fixture(alt, 'history');
        t.after(f.cleanup);
        const manifestFetch = f.c.fetch;
        let signal;
        f.c.fetch = (url, init) => {
            if (String(url).includes('/api/download/extensions')) return manifestFetch(url, init);
            signal = init.signal;
            return new Promise((_resolve, reject) => {
                signal.addEventListener('abort', () => reject(new Error('aborted')), {once: true});
            });
        };
        const processing = f.c.processSingle(f.item);
        await waitUntil(() => !!signal);
        await f.h.qt.refresh();
        await processing;
        assert.equal(signal.aborted, true);
        assert.equal(f.item.status, 'paused');
        assert.equal(f.item.lastMessage, 'queue.message.type-unavailable');
        assert.deepEqual(f.writes.map(item => item.status), ['paused']);
    });

    test(`${layout} 撤回类型立即释放 SSE 等待，旧监听不修改同 ID 的新队列项`, async t => {
        const f = await fixture(alt, 'sse');
        t.after(f.cleanup);
        let settled = false;
        const processing = f.c.processSingle(f.item).finally(() => { settled = true; });
        await waitUntil(() => f.intervals.size > 0);
        const oldListener = f.c.state.sseListeners['42'][0];
        const replacement = {id: '42', status: 'pending', downloadedCount: 0};
        f.c.state.queue = [replacement];
        await f.h.qt.refresh();
        oldListener({downloadedCount: 9, imageProgress: {progress: 99}});
        try {
            await waitUntil(() => settled);
            await processing;
            assert.deepEqual(replacement, {id: '42', status: 'pending', downloadedCount: 0});
            assert.equal(f.item.status, 'paused');
            assert.equal(f.timers.size, 0);
            assert.equal(f.intervals.size, 0);
            assert.equal(f.c.state.sseListeners['42'], undefined);
        } finally {
            f.emit({cancelled: true});
            await processing;
        }
    });

    test(`${layout} 活动类型正常完成后清理等待器并保留完成记录`, async t => {
        const f = await fixture(alt, 'sse');
        t.after(f.cleanup);
        const processing = f.c.processSingle(f.item);
        await waitUntil(() => f.intervals.size > 0);
        f.emit({completed: true, downloadedCount: 1});
        await processing;
        assert.equal(f.item.status, 'completed');
        assert.equal(f.item.downloadedCount, 1);
        assert.equal(f.writes.at(-1).status, 'completed');
        assert.equal(f.timers.size, 0);
        assert.equal(f.intervals.size, 0);
    });
}
