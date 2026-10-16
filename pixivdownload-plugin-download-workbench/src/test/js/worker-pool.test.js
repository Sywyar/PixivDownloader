'use strict';

const {test} = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const {resolve} = require('node:path');
const vm = require('node:vm');

const root = resolve(__dirname, '../../main/resources/static');
function harness(page) {
    const calls = {sse: 0, finished: 0, processed: [], errors: [], sleeps: []};
    const state = {queue: [], settings: {concurrent: 2}, isRunning: true,
        isPaused: false, stopRequested: false, activeWorkers: 0};
    const c = vm.createContext({state, window: {PixivBatchAlt: {engine: {}}},
        console: {error: (...args) => calls.errors.push(args)}, bt: key => key,
        saveQueue() {}, renderQueue() {}, sendDownload() {},
        ensureSharedSSE() { calls.sse++; }, closeAllSSE() {}, openSSE() {}, closeSSE() {},
        initQuota() {}, showArchiveCard() {}, triggerAdminPack() {}, getIntervalMs: () => 17,
        sleep: async ms => { calls.sleeps.push(ms); }});
    const html = readFileSync(resolve(root, page), 'utf8');
    for (const [, src] of html.matchAll(/<script\b[^>]*\bsrc="([^"]+)"/g)) {
        if (/\/(download-workers|batch-download-workers|alt-engine-workers)\.js$/.test(src)) {
            vm.runInContext(readFileSync(resolve(root, '.' + src), 'utf8'), c, {filename: src});
        }
    }
    c.finishBatch = () => { calls.finished++; state.isRunning = false; };
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
}
