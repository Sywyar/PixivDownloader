'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {harness, manifest, typeDescriptor, waitUntil, DOWNLOAD_SOURCE} = require('./batch-queue-types-fixture');
const root = path.resolve(__dirname, '../../main/resources/static/pixiv-batch');

async function fixture(seriesResponse) {
    const descriptor = typeDescriptor({acquisitionModes: []});
    const h = harness([manifest(1, [descriptor]), manifest(2, [typeDescriptor({acquisitionModes: [], publicationId: 2})])], {
        '/modules/demo.js': {initializer: `(function () {
            return {descriptor: {async process(item, invocation) {
                testState.signals.push(invocation.signal);
                await sendDownload(item.id, ['https://example.test/image.png'], 'Artwork', false, '',
                    7, 'Author', 0, false, null, '', [], item.series, 0, null, invocation);
                invocation.assertActive();
                item.status = 'completed';
            }}};
        })`}
    });
    const c = h.sandbox, requests = [], payloads = [];
    c.testState.signals = [];
    Object.assign(c, {
        state: {queue: [], settings: {concurrent: 1, fileNameTemplate: 'series_{artwork_id}'},
            activeWorkers: 0, isRunning: false, sseListeners: {}, sseRefs: {}},
        isAdmin: true, bt: key => key, getCookie: () => '', getImageDelayMs: () => 0,
        pixivHeader: () => ({}), resolveBatchCollectionIdForDownload: async () => null,
        checkBackend: async () => true, refreshBatchCollections: async () => {},
        updateStats() {}, saveQueue() {}, renderQueue() {}, setStatus() {},
        getIntervalMs: () => 0, sleep: async () => {}, ensureSharedSSE() {}, closeAllSSE() {},
        setInterval, clearInterval
    });
    for (const file of ['media-settings.js', 'batch-path-actions.js']) {
        vm.runInContext(fs.readFileSync(path.join(root, file), 'utf8'), c, {filename: file});
    }
    vm.runInContext(DOWNLOAD_SOURCE, c);
    c.updateButtonsState = () => {};
    await h.qt.bootstrap();
    const manifestFetch = c.fetch;
    c.fetch = async (url, options = {}) => {
        if (url.includes('/api/download/extensions')) return manifestFetch(url, options);
        if (url.startsWith('/api/pixiv/series/')) {
            requests.push({url, options});
            return seriesResponse(requests.length);
        }
        assert.equal(url, '/api/download/pixiv');
        payloads.push(JSON.parse(options.body));
        return {ok: true, status: 200, json: async () => ({})};
    };
    return {h, c, requests, payloads,
        async batch(ids, series = {seriesId: 17}) {
            c.state.queue = ids.map(id => ({id: String(id), kind: 'demo', status: 'pending', series}));
            await c.start();
            await waitUntil(() => !c.state.isRunning && c.state.activeWorkers === 0);
            assert.ok(c.state.queue.every(item => item.status === 'completed'));
        },
        close() { h.qt.dispose(); c.endPathActionBatch(); }
    };
}

const metadata = caption => ({ok: true, status: 200,
    json: async () => ({series: {caption, coverUrl: 'https://example.test/cover.png'}})});

test('系列元数据在同一下载批次复用，结束后同一 publication 的新批次重新取得', async t => {
    const f = await fixture(n => metadata('caption-' + n)); t.after(f.close);
    await f.batch([11, 12]);
    assert.equal(f.requests.length, 1);
    assert.deepEqual(f.payloads.map(p => p.other.seriesDescription), ['caption-1', 'caption-1']);
    await f.batch([13, 14]);
    assert.equal(f.c.testState.signals[0], f.c.testState.signals[2]);
    assert.equal(f.c.testState.signals[0].aborted, false);
    assert.equal(f.requests.length, 2);
    assert.deepEqual(f.payloads.map(p => p.other.seriesDescription), ['caption-1', 'caption-1', 'caption-2', 'caption-2']);
    assert.equal(f.requests[0].url, '/api/pixiv/series/17?page=1');
    assert.equal(f.requests[0].options.credentials, 'same-origin');
});

for (const failure of ['http', 'json', 'network', 'empty']) {
    test('系列补充信息失败不阻断作品提交，新批次可恢复：' + failure, async t => {
        const f = await fixture(n => {
            if (n > 1) return metadata('recovered');
            if (failure === 'network') throw new Error('offline');
            return {ok: failure !== 'http', status: failure === 'http' ? 503 : 200, json: async () => {
                if (failure === 'json') throw new Error('invalid JSON');
                return {};
            }};
        });
        t.after(f.close);
        await f.batch([21, 22]);
        assert.equal(f.requests.length, 1);
        assert.ok(f.payloads.every(p => !Object.hasOwn(p.other, 'seriesDescription')));
        await f.batch([23]);
        assert.equal(f.requests.length, 2);
        assert.equal(f.payloads.at(-1).other.seriesDescription, 'recovered');
    });
}

test('已有系列简介或封面时直接透传，不额外查询系列', async t => {
    const f = await fixture(() => { throw new Error('unexpected series request'); }); t.after(f.close);
    await f.batch([31], {seriesId: 17, seriesOrder: 2, seriesTitle: 'Series', seriesDescription: 'Provided'});
    assert.equal(f.requests.length, 0);
    assert.equal(f.payloads[0].other.seriesDescription, 'Provided');
    assert.equal(f.payloads[0].other.seriesOrder, 2);
    assert.equal(f.payloads[0].other.seriesTitle, 'Series');
});

test('同批次更换类型 publication 不复用旧系列补充结果', async t => {
    const f = await fixture(n => metadata('caption-' + n)); t.after(f.close);
    f.c.beginPathActionBatch();
    await f.h.qt.get('demo').process({id: '41', series: {seriesId: 17}});
    await f.h.qt.refresh();
    await f.h.qt.get('demo').process({id: '42', series: {seriesId: 17}});
    assert.notEqual(f.c.testState.signals[0], f.c.testState.signals[1]);
    assert.equal(f.requests.length, 2);
    assert.deepEqual(f.payloads.map(p => p.other.seriesDescription), ['caption-1', 'caption-2']);
});

test('同批次并发作品共用在途系列请求', async t => {
    let release;
    const f = await fixture(() => ({ok: true, status: 200,
        json: () => new Promise(resolve => { release = () => resolve({series: {caption: 'shared'}}); })}));
    t.after(f.close);
    f.c.state.settings.concurrent = 2;
    const batch = f.batch([51, 52]);
    await waitUntil(() => !!release && f.c.testState.signals.length === 2);
    assert.equal(f.requests.length, 1);
    release(); await batch;
    assert.deepEqual(f.payloads.map(p => p.other.seriesDescription), ['shared', 'shared']);
});

test('撤回期间迟到的系列正文不能提交旧作品或污染替代 publication', async t => {
    let release;
    const f = await fixture(n => n > 1 ? metadata('replacement') : {ok: true, status: 200,
        json: () => new Promise(resolve => { release = () => resolve({series: {caption: 'old'}}); })});
    t.after(f.close);
    f.c.beginPathActionBatch();
    const old = f.h.qt.get('demo').process({id: '61', series: {seriesId: 17}}).then(() => null, error => error);
    await waitUntil(() => !!release);
    await f.h.qt.refresh();
    assert.equal(f.requests[0].options.signal.aborted, true);
    await f.h.qt.get('demo').process({id: '62', series: {seriesId: 17}});
    release();
    assert.equal((await old).code, 'STALE_QUEUE_TYPE');
    assert.equal(f.requests.length, 2);
    assert.deepEqual(f.payloads.map(p => [p.artworkId, p.other.seriesDescription]), [[62, 'replacement']]);
});

test('没有活动批次时不把系列结果保存为页面级缓存', async t => {
    const f = await fixture(n => metadata('caption-' + n)); t.after(f.close);
    await f.h.qt.get('demo').process({id: '71', series: {seriesId: 17}});
    f.c.endPathActionBatch();
    await f.h.qt.get('demo').process({id: '72', series: {seriesId: 17}});
    assert.equal(f.requests.length, 2);
    assert.deepEqual(f.payloads.map(p => p.other.seriesDescription), ['caption-1', 'caption-2']);
});

test('清空队列取消在途系列查询，迟到正文不能提交旧作品', async t => {
    let release, processing;
    const f = await fixture(() => ({ok: true, status: 200,
        json: () => new Promise(resolve => { release = () => resolve({series: {caption: 'old'}}); })}));
    t.after(f.close);
    Object.assign(f.c, {clearSavedQueue() {}, syncAllResultsQueueState() {}, forceClearBackendQueue: async () => {}});
    const process = f.c.processSingle;
    f.c.processSingle = item => (processing = process(item));
    f.c.state.queue = [{id: '81', kind: 'demo', status: 'pending', series: {seriesId: 17}}];
    await f.c.start();
    await waitUntil(() => !!release);
    f.c.clearDownloadQueue();
    release();
    await processing;
    assert.equal(f.requests[0].options.signal.aborted, true);
    assert.equal(f.payloads.length, 0);
    assert.equal(f.c.state.queue.length, 0);
});
