'use strict';

const {test} = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const {resolve} = require('node:path');
const vm = require('node:vm');
const {MiniElement} = require('./pixiv-layout-feedback-test-dom');

const root = resolve(__dirname, '../../main/resources/static');
function harness(page) {
    const calls = {sse: 0, finished: 0, processed: [], errors: [], sleeps: []};
    const state = {queue: [], settings: {concurrent: 2}, sseListeners: {}, isRunning: true,
        isPaused: false, stopRequested: false, activeWorkers: 0};
    const c = vm.createContext({state, AbortController, window: {PixivBatch: {}, PixivBatchAlt: {engine: {}}},
        console: {error: (...args) => calls.errors.push(args)}, bt: key => key,
        saveQueue() {}, renderQueue() {}, sendDownload() {},
        clearSavedQueue() {}, syncAllResultsQueueState() {}, updateStats() {},
        checkBackend: async () => true, refreshBatchCollections: async () => {},
        setStatus() {}, setDockStatus() {}, isAdmin: true, quotaExceededHandled: false,
        ensureSharedSSE() { calls.sse++; }, closeAllSSE() {}, openSSE() {}, closeSSE() {},
        initQuota() {}, showArchiveCard() {}, triggerAdminPack() {}, getIntervalMs: () => 17,
        sleep: async ms => { calls.sleeps.push(ms); }});
    const html = readFileSync(resolve(root, page), 'utf8');
    vm.runInContext(readFileSync(resolve(root, 'pixiv-batch/batch-path-actions.js'), 'utf8'), c);
    for (const [, src] of html.matchAll(/<script\b[^>]*\bsrc="([^"]+)"/g)) {
        if (/\/(download-workers|batch-download-workers|alt-engine-workers)\.js$/.test(src)) {
            vm.runInContext(readFileSync(resolve(root, '.' + src), 'utf8'), c, {filename: src});
        }
    }
    c.finishBatch = () => { calls.finished++; state.isRunning = false; };
    c.updateButtonsState = () => {};
    c.forceClearBackendQueue = async () => {};
    c.processSingle = async item => { calls.processed.push(item.id); item.status = 'completed'; };
    return {c, state, calls};
}
const drain = () => new Promise(resolve => setImmediate(resolve));

for (const page of ['pixiv-batch.html', 'pixiv-batch-alt.html']) {
    test(`${page} worker 池同步预留计数、跳过终态条目并只收尾一次`, async () => {
        const {c, state, calls} = harness(page);
        state.queue = [{id: 'previous', status: 'skipped'},
            {id: 'one', status: 'pending'}, {id: 'two', status: 'pending'}];
        const pending = [];
        c.processSingle = item => new Promise(resolve => {
            calls.processed.push(item.id);
            pending.push(() => { item.status = 'completed'; resolve(); });
        });
        c.ensureWorkers();
        assert.equal(state.activeWorkers, 2);
        c.ensureWorkers();
        assert.deepEqual(calls.processed, ['one', 'two']);
        assert.equal(calls.sse, 2);
        assert.equal(calls.finished, 0);
        pending.forEach(resolve => resolve());
        await drain();
        assert.equal(state.activeWorkers, 0);
        assert.equal(calls.finished, 1);
        assert.equal(state.queue[0].status, 'skipped');
        assert.deepEqual(calls.sleeps, [17, 17]);
        c.ensureWorkers();
        assert.equal(calls.sse, 2);
    });

    test(`${page} 暂停不领取任务，停止和并发下调释放空闲 worker`, async () => {
        const {c, state, calls} = harness(page);
        state.isPaused = true;
        let resume;
        c.sleep = ms => { calls.sleeps.push(ms); return new Promise(resolve => { resume = resolve; }); };
        state.activeWorkers = 1;
        state.queue = [{id: 'one', status: 'pending'}];
        const paused = c.workerLoop();
        assert.deepEqual(calls.sleeps, [500]);
        assert.deepEqual(calls.processed, []);
        state.stopRequested = true;
        resume();
        await paused;
        assert.equal(state.activeWorkers, 0);
        assert.equal(state.queue[0].status, 'pending');
        state.stopRequested = false;
        state.settings.concurrent = 1;
        state.activeWorkers = 2;
        await c.workerLoop();
        assert.equal(state.activeWorkers, 1);
        assert.deepEqual(calls.processed, []);
    });

    test(`${page} 单项失败保留错误和间隔，循环结束释放计数`, async () => {
        const {c, state, calls} = harness(page);
        state.activeWorkers = 1;
        state.queue = [{id: 'one', status: 'pending'}];
        const error = new Error('fixture failure');
        c.processSingle = async () => { state.stopRequested = true; throw error; };
        await c.workerLoop();
        assert.equal(state.activeWorkers, 0);
        assert.equal(calls.errors.length, 1);
        assert.equal(calls.errors[0][0], 'download.log.process-single-error');
        assert.equal(calls.errors[0][1], error);
        assert.deepEqual(calls.sleeps, [17]);
        c.ensureWorkers();
        assert.equal(calls.sse, 0);
    });

    test(`${page} 停止领取后等待在途 worker 退出，再释放批次且不重复完成处理`, async () => {
        const {c, state, calls} = harness(page), pending = [];
        c.beginPathActionBatch();
        const signal = vm.runInContext('pathActionBatch.controller.signal', c);
        state.queue = [{id: 'one', status: 'pending'}, {id: 'two', status: 'pending'}];
        c.processSingle = () => new Promise(resolve => pending.push(resolve));
        c.ensureWorkers();
        state.isRunning = false;
        state.stopRequested = true;
        pending[0]();
        await drain();
        assert.equal(state.activeWorkers, 1);
        assert.equal(signal.aborted, false);
        pending[1]();
        await drain();
        assert.equal(state.activeWorkers, 0);
        assert.equal(signal.aborted, true);
        assert.equal(calls.finished, 0);
    });

    test(`${page} 清空后旧 worker 退出不减新队列计数或结束新批次`, async () => {
        const {c, state, calls} = harness(page), pending = [];
        state.settings.concurrent = 1;
        state.queue = [{id: 'old', status: 'pending'}];
        c.processSingle = item => new Promise(resolve => pending.push(() => { item.status = 'completed'; resolve(); }));
        c.ensureWorkers();
        c.clearDownloadQueue();
        state.queue = [{id: 'new', status: 'pending'}];
        await c.start();
        assert.equal(state.activeWorkers, 1);
        pending[0](); await drain();
        assert.equal(state.activeWorkers, 1);
        assert.equal(state.isRunning, true);
        assert.equal(calls.finished, 0);
        pending[1](); await drain();
        assert.equal(state.activeWorkers, 0);
        assert.equal(calls.finished, 1);
    });

    for (const stage of ['reconcileRestoredQueue', 'checkBackend', 'refreshBatchCollections']) {
        test(`${page} ${stage} 启动预检迟到后不能启动清空后加入的新队列`, async () => {
            const {c, state, calls} = harness(page);
            let release;
            state.isRunning = false;
            state.queue = [{id: 'old', status: 'pending', recoveryState: stage === 'reconcileRestoredQueue' ? 'checking' : null}];
            c[stage] = () => new Promise(resolve => { release = () => resolve(true); });
            const starting = c.start();
            await drain();
            assert.equal(typeof release, 'function');
            c.clearDownloadQueue();
            state.queue = [{id: 'new', status: 'pending'}];
            release(); await starting;
            assert.equal(state.isRunning, false);
            assert.deepEqual(calls.processed, []);
            assert.equal(state.queue[0].status, 'pending');
        });
    }

    test(`${page} 新队列开始前等待已发起的后端清空结算`, async () => {
        const {c, state, calls} = harness(page);
        let release;
        c.forceClearBackendQueue = () => new Promise(resolve => { release = resolve; });
        c.clearDownloadQueue();
        state.queue = [{id: 'new', status: 'pending'}];
        const starting = c.start();
        await drain();
        assert.deepEqual(calls.processed, []);
        release(); await starting; await drain();
        assert.deepEqual(calls.processed, ['new']);
        assert.equal(calls.finished, 1);
    });

    test(`${page} 清空释放旧启动占用，旧预检失败不覆盖新启动状态`, async () => {
        const {c, state, calls} = harness(page);
        state.isRunning = false;
        state.queue = [{id: 'old', status: 'pending'}];
        let failOld, finishNew;
        c.checkBackend = () => new Promise((resolve, reject) => { failOld = reject; });
        const oldStart = c.start(true);
        await drain();
        c.clearDownloadQueue();
        state.queue = [{id: 'new', status: 'pending'}];
        c.checkBackend = () => new Promise(resolve => { finishNew = () => resolve(true); });
        const newStart = c.start();
        await drain();
        assert.equal(typeof finishNew, 'function');
        let staleStatus = 0;
        c.setStatus = c.setDockStatus = () => { staleStatus++; };
        failOld(new Error('old preflight failed'));
        await oldStart;
        assert.equal(staleStatus, 0);
        assert.equal(state.isStarting, true);
        finishNew(); await newStart; await drain();
        assert.deepEqual(calls.processed, ['new']);
        assert.equal(state.isStarting, false);
    });

    test(`${page} 清空后启动预检的旧收藏夹正文不能重置新选择`, async () => {
        const {c, state} = harness(page);
        const alt = page.includes('-alt');
        c.window.PixivBatch.settings = {};
        c.window.PixivBatchAlt.settings = {};
        Object.assign(c, {BASE: '', appMode: 'solo', storeSet() {},
            document: {getElementById: () => new MiniElement('select'),
                createElement: tag => new MiniElement(tag)}});
        for (const file of ['pixiv-batch/filename-template.js', alt
            ? 'pixiv-batch-alt/alt-settings.js' : 'pixiv-batch/batch-settings.js']) {
            vm.runInContext(readFileSync(resolve(root, file), 'utf8'), c, {filename: file});
        }
        state.isRunning = false;
        state.queue = [{id: 'old', status: 'pending'}];
        let release, signal;
        c.fetch = async (url, options) => {
            assert.equal(url, '/api/collections');
            signal = options.signal;
            return {ok: true, json: () => new Promise(resolve => {
                release = () => resolve({collections: []});
            })};
        };
        const starting = c.start();
        await drain();
        assert.equal(typeof release, 'function');
        c.clearDownloadQueue();
        state.settings.collectionId = 7;
        release(); await starting;
        assert.equal(state.settings.collectionId, 7);
        assert.equal(signal.aborted, true);
    });
}
