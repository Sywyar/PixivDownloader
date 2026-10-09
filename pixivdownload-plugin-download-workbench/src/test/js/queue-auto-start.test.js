'use strict';

const assert = require('node:assert/strict');
const {test} = require('node:test');
const {readFileSync} = require('node:fs');
const {resolve} = require('node:path');
const vm = require('node:vm');

const staticRoot = resolve(__dirname, '../../main/resources/static');
const flush = () => new Promise(resolve => setImmediate(resolve));

function harness(layout, {enabled = true, admin = true, backend} = {}) {
    const state = {
        queue: [], isRunning: false, isPaused: false, activeWorkers: 0,
        settings: {autoStartOnEnqueue: enabled, concurrent: 2}
    };
    const calls = {checks: 0, workers: 0, alerts: 0, statuses: []};
    const context = vm.createContext({
        state, isAdmin: admin, console,
        window: {PixivBatch: {}, PixivBatchAlt: {queue: {}, engine: {}}},
        SINGLE_IMPORT_MODE: 'single-import', SINGLE_IMPORT_NOVEL_SOURCE: 'single-import-novel',
        QUICK_FETCH_MODE: 'quick-fetch', quotaExceededHandled: false,
        normalizeAuthorId: value => value,
        normalizeImportMode: value => value,
        reconcileQueueTypeData: () => ({typeData: null}),
        reconcileQueueItemTypeData() {},
        activeQueueDataSource: () => null,
        normalizeQueueDataSource: () => null,
        normalizeQueueCancelWorkKey: value => value || null,
        normalizeQueueCanonicalUrl: value => value || null,
        queueItemCanonicalUrl: () => null,
        checkBackend: async () => { calls.checks++; return backend ? backend() : true; },
        refreshBatchCollections: async () => {},
        reconcileRestoredQueue: async () => {},
        getIntervalMs: () => 0,
        uiAlertKey: async () => { calls.alerts++; },
        abAlert: async () => { calls.alerts++; }
    });
    for (const name of [
        'pause', 'resume', 'stopAndClear', 'handleStart', 'handlePause', 'handleRetry', 'handleClear',
        'sendDownload', 'processSingle', 'processIllustItem', 'ensureSharedSSE', 'closeAllSSE',
        'openSSE', 'closeSSE', 'initQuota', 'showArchiveCard', 'triggerAdminPack',
        'requestQueueItemCancel', 'queueItemRow', 'ensureDockVue', 'altQueueVueActive',
        'beginPathActionBatch', 'endPathActionBatch', 'updateStats', 'saveQueue',
        'renderQueue', 'updateButtonsState', 'syncAllResultsQueueState'
    ]) context[name] = () => {};
    const files = layout === 'classic'
        ? ['pixiv-batch/batch-queue-actions.js', 'pixiv-batch/batch-download-workers.js']
        : ['pixiv-batch-alt/alt-queue.js', 'pixiv-batch-alt/alt-engine-workers.js'];
    for (const file of files) vm.runInContext(readFileSync(resolve(staticRoot, file), 'utf8'), context, {filename: file});
    for (const name of ['updateStats', 'saveQueue', 'renderQueue', 'updateButtonsState']) context[name] = () => {};
    context.queueItemCanonicalUrl = () => null;
    context.setStatus = context.setDockStatus = status => calls.statuses.push(status);
    context.ensureWorkers = () => { calls.workers++; };
    return {context, state, calls, add: ids => context.addItemsToQueue(ids)};
}

for (const layout of ['classic', 'alt']) {
    test(layout + ': 关闭开关或非管理员入队时保持等待', async () => {
        for (const options of [{enabled: false}, {admin: false}]) {
            const h = harness(layout, options);
            h.add(['1']);
            await flush();
            assert.equal(h.state.queue[0].status, 'idle');
            assert.equal(h.calls.checks, 0);
        }
    });

    test(layout + ': 新作品入队启动一次，重复或空入队不启动，也不重试失败和暂停项', async () => {
        const h = harness(layout);
        h.state.queue.push(
            {id: 'failed', status: 'failed'}, {id: 'paused', status: 'paused'},
            {id: 'completed', status: 'completed'}, {id: 'external', status: 'idle', taskObserved: true}
        );
        h.add([]);
        h.add(['failed']);
        await flush();
        assert.equal(h.calls.checks, 0);
        h.add(['1']);
        h.add(['2']);
        await flush();
        assert.equal(h.calls.checks, 1);
        assert.equal(h.calls.workers, 1);
        assert.equal(h.state.isRunning, true);
        assert.equal(h.state.queue.find(q => q.id === '1').status, 'pending');
        assert.equal(h.state.queue.find(q => q.id === '2').status, 'pending');
        for (const [id, status] of [['failed', 'failed'], ['paused', 'paused'], ['completed', 'completed'], ['external', 'idle']]) {
            assert.equal(h.state.queue.find(q => q.id === id).status, status);
        }
        h.add(['1']);
        assert.equal(h.calls.workers, 1);
        h.add(['3']);
        assert.equal(h.calls.workers, 2);
        assert.equal(h.calls.checks, 1);
    });

    test(layout + ': 入队保留手动暂停，已有运行中的队列仍接收新项', async () => {
        const h = harness(layout);
        h.state.isPaused = true;
        h.add(['1']);
        await flush();
        assert.equal(h.calls.checks, 0);
        assert.equal(h.state.isPaused, true);
        h.state.isRunning = true;
        h.add(['2']);
        assert.equal(h.state.isPaused, true);
        assert.equal(h.state.queue.find(q => q.id === '2').status, 'pending');
    });

    test(layout + ': 启动检查期间关闭开关、暂停或清空会取消自动启动', async () => {
        for (const cancel of [
            state => { state.settings.autoStartOnEnqueue = false; },
            state => { state.isPaused = true; },
            state => { state.queue = []; }
        ]) {
            let ready;
            const h = harness(layout, {backend: () => new Promise(resolve => { ready = resolve; })});
            h.add(['1']);
            cancel(h.state);
            ready(true);
            await flush();
            assert.equal(h.state.isRunning, false);
            assert.equal(h.state.isStarting, false);
            assert.equal(h.calls.workers, 0);
        }
    });

    test(layout + ': 后端不可用或异常时保留等待项，后续可手动开始', async () => {
        for (const backend of [async () => false, async () => { throw new Error('offline'); }]) {
            const h = harness(layout, {backend});
            h.add(['1']);
            await flush();
            assert.equal(h.state.isRunning, false);
            assert.equal(h.state.isStarting, false);
            assert.equal(h.state.queue[0].status, 'idle');
            assert.equal(h.calls.alerts + h.calls.statuses.length, 1);
            h.context.checkBackend = async () => true;
            await h.context.start();
            assert.equal(h.state.isRunning, true);
        }
    });

    test(layout + ': 未确认的恢复任务阻止自动重投，完成后可再次入队启动', async () => {
        const h = harness(layout);
        h.state.queue.push({id: 'recovering', status: 'paused', recoveryState: 'unconfirmed'});
        h.add(['1']);
        await flush();
        assert.equal(h.calls.workers, 0);
        h.state.queue = [{id: 'done', status: 'completed'}];
        h.add(['2']);
        await flush();
        assert.equal(h.state.isRunning, true);
        assert.equal(h.calls.workers, 1);
    });
}
