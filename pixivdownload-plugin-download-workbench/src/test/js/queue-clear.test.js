'use strict';

const assert = require('node:assert/strict');
const {test} = require('node:test');
const {readFileSync} = require('node:fs');
const {resolve} = require('node:path');
const vm = require('node:vm');

const root = resolve(__dirname, '../../main/resources/static');
for (const alt of [false, true]) {
    for (const backend of ['pending', 'rejected', 'http-error']) {
        test(`${alt ? '新版' : '经典'}清空立即释放本页等待并保留后端${backend}语义`, async () => {
            const calls = [];
            const queue = [{id: 'a', status: 'paused'}, {id: 'b', status: 'downloading'}];
            const state = {queue, stats: {success: 4, active: 2}, isRunning: true, isPaused: true,
                sseListeners: {a: [notify('a1'), notify('a2')], b: [notify('b')], empty: null}};
            function notify(id) {
                return value => {
                    assert.equal(value.cancelled, true);
                    assert.equal(state.stopRequested, true);
                    assert.equal(state.isRunning, false);
                    assert.equal(state.isPaused, false);
                    calls.push(id);
                };
            }
            let settle;
            const request = new Promise((resolve, reject) => {
                settle = () => backend === 'rejected' ? reject(new Error('offline')) : resolve({ok: false, status: 503});
            });
            if (backend !== 'pending') settle();
            const c = vm.createContext({state, BASE: '', console, AbortController,
                window: {PixivBatch: {}, PixivBatchAlt: {engine: {}}},
                fetch(url, options) {
                    assert.equal(url, '/api/download/queue/clear');
                    assert.equal(options.method, 'POST');
                    assert.equal(options.credentials, 'same-origin');
                    calls.push('request');
                    return request;
                }});
            for (const name of ['start', 'sendDownload', 'processSingle', 'processIllustItem',
                'ensureSharedSSE', 'closeAllSSE', 'closeSSE', 'openSSE', 'initQuota', 'showArchiveCard', 'triggerAdminPack']) c[name] = () => {};
            for (const file of ['pixiv-batch/download-workers.js', alt
                ? 'pixiv-batch-alt/alt-engine-workers.js' : 'pixiv-batch/batch-download.js']) {
                vm.runInContext(readFileSync(resolve(root, file), 'utf8'), c, {filename: file});
            }
            for (const name of ['endPathActionBatch', 'closeAllSSE', 'clearSavedQueue',
                'renderQueue', 'updateButtonsState', 'updateStats', 'syncAllResultsQueueState']) {
                c[name] = () => calls.push(name);
            }
            c.setStatus = c.setDockStatus = (message, tone) => {
                assert.equal(message.key, 'status.queue-cleared'); assert.equal(tone, 'info'); calls.push('status');
            };
            const facade = alt ? c.window.PixivBatchAlt.engine : c.window.PixivBatch.download;
            assert.equal(facade.stopAndClear(), undefined);
            assert.equal(state.queue.length, 0);
            assert.notEqual(state.queue, queue);
            assert.deepEqual(JSON.parse(JSON.stringify(state.stats)), {success: 0, failed: 0, active: 0, skipped: 0});
            const expected = ['endPathActionBatch', 'a1', 'a2', 'b', 'closeAllSSE',
                'clearSavedQueue', 'renderQueue',
                'updateButtonsState', 'updateStats', 'syncAllResultsQueueState', 'status'];
            assert.deepEqual(calls, expected);
            if (backend === 'pending') settle();
            await new Promise(resolve => setImmediate(resolve));
            assert.deepEqual(calls, [...expected, 'request']);
        });
    }
}
