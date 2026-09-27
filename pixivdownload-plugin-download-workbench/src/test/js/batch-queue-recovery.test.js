'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const root = path.join(__dirname, '../../main/resources/static');
const source = name => fs.readFileSync(path.join(root, name), 'utf8');

function harness(queue = []) {
    const timers = new Map();
    const listeners = new Map();
    const calls = {saved: 0, queries: [], started: 0, confirmations: 0};
    let nextTimer = 0;
    const behavior = {queryDownloadStatus: async () => null};
    const context = vm.createContext({
        appMode: 'solo', isAdmin: true, BASE: '',
        state: {queue, stats: {}, settings: {}, isRunning: false, isPaused: false},
        window: {
            PixivBatch: {queueTypes: {get: () => behavior}},
            PixivFeedback: {confirm: async () => { calls.confirmations++; return true; }},
            addEventListener: (type, listener) => listeners.set(type, listener)
        },
        document: {getElementById: () => null},
        AbortController, Date, Promise,
        setTimeout(fn, ms) { timers.set(++nextTimer, {fn, ms}); return nextTimer; },
        clearTimeout: id => timers.delete(id),
        bt: key => key,
        renderQueue() {}, updateStats() {}, updateButtonsState() {},
        saveQueue() { calls.saved++; },
        storeGet: () => JSON.stringify({queue}),
        storageKey: () => 'queue',
        dedupeQueueItems: items => items,
        normalizeImportMode: mode => mode,
        checkBackend: async () => true,
        refreshBatchCollections: async () => {},
        setStatus() {}, setDockStatus() {},
        beginPathActionBatch() {},
        desiredConcurrency: () => 1,
        getIntervalMs: () => 0,
        ensureWorkers() { calls.started++; },
        quotaExceededHandled: false
    });
    vm.runInContext(source('pixiv-batch/batch-queue-recovery.js'), context);
    return {context, behavior, timers, listeners, calls};
}

function loadQueue(context, layout) {
    const file = source(layout === 'classic' ? 'pixiv-batch/batch-queue.js' : 'pixiv-batch-alt/alt-queue.js');
    vm.runInContext(file.slice(file.indexOf('function loadQueueForMode()'),
        file.indexOf('function clearSavedQueue()')), context);
    context.loadQueueForMode();
}

function loadStart(context, layout) {
    const file = source(layout === 'classic'
        ? 'pixiv-batch/batch-download-workers.js' : 'pixiv-batch-alt/alt-engine-workers.js');
    vm.runInContext(file.slice(file.indexOf('async function start()'),
        file.indexOf('function desiredConcurrency()')), context);
}

test('alt: initial page load initializes recovery once and language refresh only renders', async () => {
    const calls = [];
    const context = vm.createContext({
        window: {addEventListener() {}, dispatchEvent() {},
            matchMedia: () => ({matches: false, addEventListener() {}})},
        document: {addEventListener() {}, getElementById: () => null},
        appMode: 'solo', isAdmin: true, chromeState: {}, state: {}, searchState: {}, pageI18n: null,
        lastAcquisitionMode: 'single-import',
        AB_MODES: [{id: 'single-import'}], QUICK_FETCH_MODE: 'single-import', SINGLE_IMPORT_MODE: 'single-import',
        storeGet: () => null, checkBackend: async () => true,
        debounce: fn => fn, moveRailIndicator() {},
        loadQueueForMode: () => calls.push('load'),
        renderDock: () => calls.push('render'),
        initQueueRecovery: () => calls.push('recovery')
    });
    vm.runInContext(source('pixiv-batch-alt/alt-init.js'), context);
    for (const name of ['hydrateIcons', 'initPageI18n', 'detectMode', 'detectAuthState',
        'loadServerState', 'loadSettings', 'loadSearchFilterPrefs', 'bindChrome', 'bindScheduleMenus', 'loadAppInfo',
        'renderAuthButton', 'refreshCookieUi', 'refreshBatchCollections', 'bootstrapAltExtensions',
        'renderRail', 'renderStage', 'renderBackendBanner', 'syncFilterButtonBadge',
        'setupOnboardingOrTour', 'refreshGuideFab']) context[name] = () => {};
    context.bt = key => key;
    await context.init();
    assert.deepEqual(calls, ['load', 'render', 'recovery']);
    context.applyPageLanguageViews(null);
    assert.deepEqual(calls, ['load', 'render', 'recovery', 'render']);
});

for (const layout of ['classic', 'alt']) {
    test(layout + ': reopen preserves uncertain downloads and start cannot resubmit them', async () => {
        const h = harness([{id: '1', kind: 'illust', status: 'downloading'},
            {id: '2', status: 'pending'}, {id: '3', status: 'completed'}]);
        loadQueue(h.context, layout);
        const item = h.context.state.queue[0];
        assert.equal(item.status, 'paused');
        assert.equal(item.recoveryState, 'unknown');
        assert.equal(item.statusMessageKey, 'batch:queue.recovery.unknown');
        loadStart(h.context, layout);
        await h.context.start();
        assert.equal(h.calls.started, 0);
        assert.equal(h.context.state.queue[1].status, 'pending');
        assert.equal(h.context.state.queue[2].status, 'completed');
        assert.equal(h.context.state.isRunning, false);
        h.behavior.queryDownloadStatus = async () => ({status: 'completed', totalImages: 2, downloadedCount: 2});
        await h.context.reconcileRestoredQueue();
        assert.equal(item.status, 'completed');
        assert.equal(item.downloadedCount, 2);
        assert.equal(item.recoveryState, undefined);
        await h.context.start();
        assert.equal(h.calls.started, 1);
        assert.equal(h.context.state.queue[0].status, 'completed');
    });
}

test('active downloads are polled serially, concurrent checks share one run, and terminal results stop polling', async () => {
    const h = harness([{id: '1', kind: 'illust', status: 'downloading'},
        {id: '2', kind: 'illust', status: 'downloading'}]);
    h.context.state.queue.forEach(h.context.restoreInterruptedQueueItem);
    let release;
    h.behavior.queryDownloadStatus = (item, signal) => {
        assert.equal(signal.aborted, false);
        h.calls.queries.push(item.id);
        return new Promise(resolve => { release = resolve; });
    };
    const first = h.context.reconcileRestoredQueue();
    assert.equal(h.context.reconcileRestoredQueue(), first);
    assert.deepEqual(h.calls.queries, ['1']);
    release({status: 'running', totalImages: 4, downloadedCount: 1});
    await new Promise(resolve => setImmediate(resolve));
    assert.deepEqual(h.calls.queries, ['1', '2']);
    release({status: 'failed'});
    await first;
    assert.equal(h.context.state.queue[0].status, 'downloading');
    assert.equal(h.context.state.queue[1].status, 'failed');
    assert.equal(h.timers.size, 1);
    const poll = [...h.timers.values()][0];
    h.behavior.queryDownloadStatus = async () => ({status: 'completed', totalImages: 4, downloadedCount: 4});
    await poll.fn();
    assert.equal(h.timers.size, 0);
    assert.equal(h.context.state.queue[0].status, 'completed');
});

test('missing capability, missing status and query failure remain unconfirmed across another reload', async () => {
    for (const query of [undefined, async () => null, async () => { throw new Error('offline'); }]) {
        const h = harness([{id: '1', status: 'paused', recoveryState: 'unknown'}]);
        h.behavior.queryDownloadStatus = query;
        await h.context.reconcileRestoredQueue();
        assert.equal(h.context.state.queue[0].status, 'paused');
        assert.equal(h.context.state.queue[0].recoveryState, 'unknown');
        loadQueue(h.context, 'classic');
        assert.equal(h.context.state.queue[0].status, 'paused');
        assert.equal(h.context.state.queue[0].recoveryState, 'unknown');
        assert.equal(h.timers.size, 0);
    }
});

test('removed queue items and replaced plugin publications cannot receive late results', async () => {
    for (const change of ['remove', 'replace']) {
        const item = {id: '1', kind: 'illust', status: 'paused', recoveryState: 'unknown'};
        const h = harness([item]);
        let release;
        h.behavior.queryDownloadStatus = () => new Promise(resolve => { release = resolve; });
        const checking = h.context.reconcileRestoredQueue();
        if (change === 'remove') h.context.state.queue = [];
        else h.context.window.PixivBatch.queueTypes.get = () => ({});
        release({status: 'completed'});
        await checking;
        assert.equal(item.status, 'paused');
        assert.equal(item.recoveryState, 'unknown');
    }
});

test('retry requires confirmation and checks again for a backend task that started during the dialog', async () => {
    for (const answer of [false, true]) {
        const h = harness([{id: '1', status: 'paused', recoveryState: 'unknown'}]);
        h.context.window.PixivFeedback.confirm = async options => {
            assert.equal(options.danger, true);
            return answer;
        };
        await h.context.retryUnconfirmedQueueItems();
        assert.equal(h.context.state.queue[0].recoveryState, answer ? undefined : 'unknown');
        assert.equal(h.context.state.queue[0].status, 'paused');
        assert.equal(h.calls.started, 0);
    }
    const h = harness([{id: '1', status: 'paused', recoveryState: 'unknown'}]);
    h.context.window.PixivFeedback.confirm = async () => {
        h.behavior.queryDownloadStatus = async () => ({status: 'running'});
        return true;
    };
    await h.context.retryUnconfirmedQueueItems();
    assert.equal(h.context.state.queue[0].recoveryState, 'running');
    assert.equal(h.calls.started, 0);
});

test('request timeout and leaving the page abort checks without declaring failure; returning checks again', async () => {
    const h = harness([{id: '1', status: 'paused', recoveryState: 'unknown'}]);
    let signal;
    h.behavior.queryDownloadStatus = (_, value) => {
        signal = value;
        return new Promise((resolve, reject) => value.addEventListener('abort',
            () => reject(new Error('aborted')), {once: true}));
    };
    const pending = h.context.reconcileRestoredQueue();
    [...h.timers.values()][0].fn();
    await pending;
    assert.equal(signal.aborted, true);
    assert.equal(h.context.state.queue[0].recoveryState, 'unknown');
    assert.equal(h.timers.size, 0);
    h.context.initQueueRecovery();
    const leaving = h.context.reconcileRestoredQueue();
    h.listeners.get('pagehide')();
    assert.equal(signal.aborted, true);
    await leaving;
    assert.equal(h.timers.size, 0);
    h.behavior.queryDownloadStatus = async () => ({status: 'cancelled'});
    h.listeners.get('pageshow')();
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(h.context.state.queue[0].statusMessageKey, 'batch:queue.recovery.cancelled');
    assert.equal(h.context.state.queue[0].recoveryState, undefined);
});

test('leave warning follows unfinished work and does not change multi or guest behavior', () => {
    const h = harness([{id: '1', status: 'pending'}]);
    h.context.initQueueRecovery();
    h.context.state.isRunning = true;
    let prevented = false;
    h.listeners.get('beforeunload')({preventDefault() { prevented = true; }});
    assert.equal(prevented, true);
    h.context.state.isPaused = true;
    assert.equal(h.context.queueNeedsLeaveWarning(), false);
    h.context.state.queue[0].status = 'downloading';
    assert.equal(h.context.queueNeedsLeaveWarning(), true);
    h.context.appMode = 'multi';
    assert.equal(h.context.queueNeedsLeaveWarning(), false);
    assert.equal(h.context.restoreInterruptedQueueItem(h.context.state.queue[0]), false);
    h.context.appMode = 'solo';
    h.context.isAdmin = false;
    assert.equal(h.context.restoreInterruptedQueueItem(h.context.state.queue[0]), false);
});

test('Pixiv recovery reads status only, rejects unavailable or mismatched results and preserves partial failure', async () => {
    const h = harness();
    let descriptor;
    let response;
    let active = true;
    Object.assign(h.context, {
        processIllustItem() { assert.fail('recovery must not submit'); },
        renderPixivSearchResults() {}, renderQuickIllustGrid() {}, pixivQuickInnerCard() {},
        QUICK_PAGE_SIZE_ILLUST: 60, QUICK_PAGE_SIZE_NOVEL: 24,
        SINGLE_IMPORT_MODE: 'single-import', SINGLE_IMPORT_NOVEL_SOURCE: 'single-import-novel',
        fetch: async (url, options) => {
            assert.equal(url, '/api/download/status/123');
            assert.equal(options.method, undefined);
            assert.equal(options.credentials, 'same-origin');
            assert.equal(options.signal.aborted, false);
            return response;
        }
    });
    h.context.window.PixivBatch.queueTypes.registerModule = initializer => {
        descriptor = initializer({type: 'illust', assertActive() {
            if (!active) throw new Error('stale');
        }}).descriptor;
    };
    vm.runInContext(source('pixiv-batch/pixiv-queue-type.js'), h.context);
    const query = () => descriptor.queryDownloadStatus({id: '123'}, new AbortController().signal);
    for (const [data, expected] of [
        [{success: false, artworkId: 123}, null],
        [{success: true, artworkId: 456, completed: true}, null],
        [{success: true, artworkId: 123}, 'running'],
        [{success: true, artworkId: 123, cancelled: true}, 'cancelled'],
        [{success: true, artworkId: 123, failed: true}, 'failed'],
        [{success: true, artworkId: 123, completed: true, totalImages: 2, downloadedCount: 1}, 'failed'],
        [{success: true, artworkId: 123, completed: true, totalImages: 2, downloadedCount: 2}, 'completed']
    ]) {
        response = {ok: true, json: async () => data};
        assert.equal((await query())?.status || null, expected);
    }
    response = {ok: false, status: 401, json: () => assert.fail('unauthorized body')};
    assert.equal(await query(), null);
    active = false;
    await assert.rejects(query(), /stale/);
});
