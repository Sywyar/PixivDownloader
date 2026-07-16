'use strict';

const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('node:assert/strict');
const test = require('node:test');
const {isMainThread, parentPort, workerData, Worker} = require('node:worker_threads');

const STATIC = path.join(__dirname, '..', '..', 'main', 'resources', 'static', 'pixiv-batch');
const DOWNLOAD_SOURCE_PATH = path.join(STATIC, 'batch-download.js');
const SCENARIO_TIMEOUT_MS = 2000;

function createWorkerHarness(options) {
    const calls = {
        processed: [], sharedSseOpened: 0, sharedSseClosed: 0, statuses: [], requests: []
    };
    const elements = new Map();
    const state = {
        queue: options.queue.map(item => Object.assign({kind: 'demo'}, item)),
        isRunning: String(options.action).startsWith('ensure-workers'),
        isPaused: false,
        stopRequested: false,
        activeWorkers: options.activeWorkers || 0,
        currentItemId: null,
        settings: {concurrent: options.concurrent || 1},
        sseListeners: {},
        sseRefs: {},
        stats: {success: 0, failed: 0, active: 0, skipped: 0}
    };
    const descriptor = {
        process(item) {
            calls.processed.push(item.id);
            item.status = 'completed';
            return Promise.resolve();
        }
    };
    const sandbox = {
        window: {
            PixivBatch: {
                download: {},
                queueTypes: {get: () => descriptor}
            }
        },
        document: {
            getElementById(id) {
                if (!elements.has(id)) {
                    elements.set(id, {disabled: false, textContent: '', style: {display: ''}});
                }
                return elements.get(id);
            }
        },
        state,
        BASE: '',
        isAdmin: false,
        Promise,
        AbortController,
        TextDecoder,
        Uint8Array,
        URL,
        setTimeout,
        clearTimeout,
        setInterval,
        clearInterval,
        console: {warn() {}, log() {}, error() {}},
        bt: key => key,
        fetch(url) {
            calls.requests.push(String(url));
            return Promise.resolve({ok: true, status: 204, json: () => Promise.resolve({})});
        },
        checkBackend: () => Promise.resolve(true),
        refreshBatchCollections: () => Promise.resolve(),
        updateStats() {},
        saveQueue() {},
        renderQueue() {},
        setCurrent() {},
        setStatus(message, type) { calls.statuses.push({message, type}); },
        getIntervalMs: () => 0,
        sleep: () => Promise.resolve(),
        ensureSharedSSE() { calls.sharedSseOpened++; },
        closeAllSSE() { calls.sharedSseClosed++; },
        uiAlertKey: () => Promise.resolve(),
        syncAllResultsQueueState() {}
    };
    vm.createContext(sandbox);
    vm.runInContext(fs.readFileSync(DOWNLOAD_SOURCE_PATH, 'utf8'), sandbox);
    if (options.quotaEnabled) vm.runInContext('quotaInfo.enabled = true', sandbox);
    return {sandbox, state, calls};
}

async function waitForBatchToFinish(state) {
    const deadline = Date.now() + 500;
    while (state.isRunning || state.activeWorkers !== 0) {
        if (Date.now() >= deadline) {
            throw new Error(`batch did not finish: running=${state.isRunning}, workers=${state.activeWorkers}`);
        }
        await new Promise(resolve => setTimeout(resolve, 5));
    }
}

async function executeWorkerScenario(options) {
    const {sandbox, state, calls} = createWorkerHarness(options);
    if (options.action === 'ensure-workers' || options.action === 'ensure-workers-full') {
        vm.runInContext('ensureWorkers()', sandbox);
    } else if (options.action === 'start-twice') {
        await Promise.all([
            sandbox.window.PixivBatch.download.start(),
            sandbox.window.PixivBatch.download.start()
        ]);
    } else {
        await sandbox.window.PixivBatch.download.start();
    }
    if (options.action !== 'ensure-workers-full') await waitForBatchToFinish(state);
    return {
        queue: state.queue,
        isRunning: state.isRunning,
        activeWorkers: state.activeWorkers,
        calls
    };
}

function runIsolatedScenario(options) {
    return new Promise((resolve, reject) => {
        const worker = new Worker(__filename, {workerData: options});
        let settled = false;
        const finish = (callback, value) => {
            if (settled) return;
            settled = true;
            clearTimeout(timer);
            callback(value);
        };
        const timer = setTimeout(() => {
            finish(reject, new Error(`scenario timed out after ${SCENARIO_TIMEOUT_MS}ms: ${options.name}`));
            worker.terminate();
        }, SCENARIO_TIMEOUT_MS);
        worker.once('message', message => {
            if (message.error) {
                finish(reject, new Error(message.error));
                return;
            }
            finish(resolve, message.result);
        });
        worker.once('error', error => finish(reject, error));
        worker.once('exit', code => {
            if (code !== 0) finish(reject, new Error(`scenario worker exited with code ${code}: ${options.name}`));
        });
    });
}

if (!isMainThread) {
    executeWorkerScenario(workerData).then(
        result => parentPort.postMessage({result}),
        error => parentPort.postMessage({error: error && (error.stack || error.message) || String(error)})
    );
} else {
    test('ensureWorkers 对高并发全终态队列有限退出', async () => {
        const result = await runIsolatedScenario({
            name: 'terminal ensureWorkers',
            action: 'ensure-workers',
            concurrent: 4,
            queue: [{id: 'completed', status: 'completed'}, {id: 'skipped', status: 'skipped'}]
        });
        assert.equal(result.isRunning, false);
        assert.equal(result.activeWorkers, 0);
        assert.deepEqual(result.calls.processed, []);
    });

    test('ensureWorkers 在 worker 已满时仍幂等确保聚合 SSE', async () => {
        const result = await runIsolatedScenario({
            name: 'full worker pool restores SSE',
            action: 'ensure-workers-full',
            concurrent: 2,
            activeWorkers: 2,
            queue: [{id: 'pending', status: 'pending'}]
        });
        assert.equal(result.isRunning, true);
        assert.equal(result.activeWorkers, 2);
        assert.deepEqual(result.calls.processed, []);
        assert.equal(result.calls.sharedSseOpened, 1);
        assert.equal(result.calls.sharedSseClosed, 0);
    });

    for (const scenario of [
        {name: 'skipped-only', queue: [{id: 'skipped', status: 'skipped'}]},
        {name: 'completed-only', quotaEnabled: true, queue: [{id: 'completed', status: 'completed'}]},
        {
            name: 'mixed-terminal',
            queue: [{id: 'completed', status: 'completed'}, {id: 'skipped', status: 'skipped'}]
        }
    ]) {
        test(`start 对 ${scenario.name} 队列直接收尾且不打开 SSE`, async () => {
            const result = await runIsolatedScenario(Object.assign({
                action: 'start',
                concurrent: 3
            }, scenario));
            assert.equal(result.isRunning, false);
            assert.equal(result.activeWorkers, 0);
            assert.deepEqual(result.calls.processed, []);
            assert.equal(result.calls.sharedSseOpened, 0);
            assert.equal(result.calls.sharedSseClosed, 0);
            assert.deepEqual(result.calls.requests, []);
            assert.equal(result.calls.statuses.at(-1).message, 'status.batch-finished');
        });
    }

    test('start 只处理 skipped + idle + pending 队列中的可运行项并正常收尾', async () => {
        const result = await runIsolatedScenario({
            name: 'skipped plus idle and pending',
            action: 'start',
            concurrent: 3,
            queue: [
                {id: 'skipped', status: 'skipped'},
                {id: 'idle', status: 'idle'},
                {id: 'pending', status: 'pending'}
            ]
        });
        assert.equal(result.isRunning, false);
        assert.equal(result.activeWorkers, 0);
        assert.deepEqual(result.calls.processed, ['idle', 'pending']);
        assert.deepEqual(result.queue.map(item => item.status), ['skipped', 'completed', 'completed']);
        assert.equal(result.calls.sharedSseOpened, 1);
        assert.equal(result.calls.sharedSseClosed, 1);
        assert.equal(result.calls.statuses.filter(status => status.message === 'status.batch-finished').length, 1);
        assert.equal(result.calls.statuses.at(-1).message, 'status.batch-finished');
    });

    test('重复 start 不会重置已启动 worker 或重复处理队列项', async () => {
        const result = await runIsolatedScenario({
            name: 'concurrent starts',
            action: 'start-twice',
            concurrent: 2,
            queue: [{id: 'idle', status: 'idle'}]
        });
        assert.equal(result.isRunning, false);
        assert.equal(result.activeWorkers, 0);
        assert.deepEqual(result.calls.processed, ['idle']);
        assert.equal(result.calls.sharedSseOpened, 1);
        assert.equal(result.calls.statuses.filter(status => status.message === 'status.start-download').length, 1);
    });
}
