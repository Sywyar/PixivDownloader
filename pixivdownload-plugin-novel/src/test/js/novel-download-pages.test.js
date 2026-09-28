'use strict';

const assert = require('node:assert/strict');
const {test} = require('node:test');
const {readFileSync} = require('node:fs');
const {resolve} = require('node:path');
const vm = require('node:vm');

const workbench = resolve(__dirname,
    '../../../../pixivdownload-plugin-download-workbench/src/main/resources/static');
const download = readFileSync(resolve(__dirname,
    '../../main/resources/static/pixiv-novel-download/novel-queue-download.js'), 'utf8');

function pageContext(page, fetch) {
    const context = vm.createContext({
        fetch, TextDecoder, Uint8Array,
        window: {PixivBatch: {}, PixivBatchAlt: {settings: {}}},
        document: {
            getElementById: () => ({style: {}, appendChild() {}}),
            createElement: () => ({})
        }
    });
    const html = readFileSync(resolve(workbench, page), 'utf8');
    // 由实际页面依赖加载网络与设置模块，防止测试自行补入页面遗漏的函数。
    for (const [, src] of html.matchAll(/<script\b[^>]*\bsrc="([^"]+)"/g)) {
        if (!/\/(batch-(fetch-json|download-quota|settings)|alt-settings)\.js$/.test(src)) continue;
        vm.runInContext(readFileSync(resolve(workbench, '.' + src), 'utf8'), context,
            {filename: src});
    }
    return context;
}

for (const page of ['pixiv-batch.html', 'pixiv-batch-alt.html']) {
    test(`${page} 小说下载读取流式正文后提交票据并完成队列`, async () => {
        const controller = new AbortController();
        const meta = {title: '中文小说', fetchToken: 'test-ticket'};
        const bytes = new TextEncoder().encode(JSON.stringify(meta));
        const progress = [];
        const coverProgress = [];
        const submitted = [];
        const authRefreshes = [];
        let polls = 0;
        const context = pageContext(page, async (url, options) => {
            assert.equal(options.credentials, 'same-origin');
            assert.equal(options.signal, controller.signal);
            if (url === '/api/collections') {
                return Response.json({collections: [{id: 7, name: 'Test'}]});
            }
            if (url === '/api/novel/series/8/merge?format=epub') {
                assert.equal(options.method, 'POST');
                return Response.json({error: 'expired'}, {status: 401});
            }
            if (url === '/api/pixiv/novel/42/meta') {
                assert.equal(options.headers['X-Test-Credential'], 'test-only');
                return new Response(new ReadableStream({start(stream) {
                    // 字节逐个到达，包含被拆开的 UTF-8 字符。
                    bytes.forEach(byte => stream.enqueue(new Uint8Array([byte])));
                    stream.close();
                }}), {headers: {'Content-Length': String(bytes.length)}});
            }
            assert.equal(url, '/api/novel/status/42');
            if (++polls === 1) return Response.json({
                stage: 'downloading-cover', coverTotalBytes: 20, coverDownloadedBytes: 10
            });
            return Response.json({completed: true, failed: false});
        });
        const item = {id: 'n42', novelId: '42', kind: 'novel', status: 'downloading', mergeAfterSeriesId: 8};
        const shared = {novelAcquisitionCredentialHeaders: () => ({'X-Test-Credential': 'test-only'})};
        Object.assign(context, {
            window: {PixivBatch: {
                queueTypes: {registerSubmodule: init => init(shared)},
                pathActions: {
                    async submit(url, body, invocation, headers) {
                        submitted.push({url, body, headers});
                        assert.equal(invocation.signal, controller.signal);
                        return {res: {ok: true, status: 202}, data: {}};
                    },
                    handleError: () => false
                }
            }},
            BASE: '', STATUS_TIMEOUT_MS: 10000, isAdmin: true, appMode: 'solo',
            state: {settings: {collectionId: 7, mergeNovelSeries: true}, queue: [item]},
            bt: key => key, setCurrent() {}, getCookie: () => '',
            renderQueue() {
                if (item.novelText) progress.push({...item.novelText});
                if (item.novelCover) coverProgress.push({...item.novelCover});
            },
            updateStats() {}, saveQueue() {}, evaluateDownloadFilterSkip: () => null,
            updateAuthButtons: () => authRefreshes.push('auth'),
            updateAdminPackButton: () => authRefreshes.push('actions'), setStatus() {},
            defaultNovelTranslateLang: () => 'en', setTimeout, clearTimeout
        });
        vm.runInContext(download, context);
        await shared.processNovelItem(item, {
            signal: controller.signal, assertActive() {controller.signal.throwIfAborted();}
        });
        assert.equal(item.status, 'completed');
        assert.equal(item.title, meta.title);
        assert.equal(item.downloadedCount, 1);
        assert.equal(item.novelText, null);
        assert.ok(progress.some(value => value.done > 0 && value.total === bytes.length));
        assert.equal(submitted.length, 1);
        assert.equal(submitted[0].url, '/api/novel/download');
        assert.equal(submitted[0].body.fetchToken, meta.fetchToken);
        assert.equal(submitted[0].body.other.collectionId, 7);
        assert.ok(coverProgress.some(value => value.done === 10 && value.total === 20));
        assert.deepEqual(authRefreshes, ['auth', 'actions']);
        assert.equal(context.isAdmin, false);
    });
}

test('流式 JSON 保留错误响应与取消信号，不把失败读取当成成功', async () => {
    const denied = Response.json({error: 'denied'}, {status: 403});
    const context = pageContext('pixiv-batch-alt.html', async () => denied);
    assert.equal(await context.fetchJsonWithProgress('/meta', {}), denied);
    const controller = new AbortController();
    const canceled = new DOMException('Canceled', 'AbortError');
    context.fetch = async (_url, options) => {
        assert.equal(options.signal, controller.signal);
        return new Response(new ReadableStream({start(stream) {
            controller.signal.addEventListener('abort', () => stream.error(canceled));
        }}));
    };
    const pending = context.fetchJsonWithProgress('/meta', {signal: controller.signal});
    controller.abort();
    await assert.rejects(pending, error => error === canceled);
    const fallback = {ok: true, status: 200, body: null, json: async () => ({title: 'Fallback'})};
    context.fetch = async () => fallback;
    assert.equal(await context.fetchJsonWithProgress('/meta', {}), fallback);
});

test('新版收藏夹请求随任务取消，失效结果不覆盖设置；无权限时清空选择', async () => {
    const controller = new AbortController();
    const context = pageContext('pixiv-batch-alt.html', async (_url, options) => {
        assert.equal(options.signal, controller.signal);
        return {ok: true, json: async () => {
            controller.abort();
            return {collections: []};
        }};
    });
    Object.assign(context, {BASE: '', appMode: 'solo', isAdmin: true,
        state: {settings: {collectionId: 7}}});
    await assert.rejects(context.refreshBatchCollections({
        signal: controller.signal, assertActive() {controller.signal.throwIfAborted();}
    }), {name: 'AbortError'});
    assert.equal(context.state.settings.collectionId, 7);
    context.fetch = async () => Response.json({error: 'denied'}, {status: 403});
    assert.equal((await context.refreshBatchCollections()).collectionId, null);
    assert.equal(context.state.settings.collectionId, null);
});
