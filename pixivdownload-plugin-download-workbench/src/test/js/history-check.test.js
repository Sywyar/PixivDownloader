'use strict';

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const test = require('node:test');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../../../..');
const staticRoot = path.resolve(__dirname, '../../main/resources/static');

function loadWeb(respond) {
    const context = {state: {settings: {verifyHistoryFiles: true}}, BASE: '',
        bt: key => key, fetch: respond, apiGet: respond, console: {warn() {}}};
    vm.createContext(context);
    vm.runInContext(fs.readFileSync(path.join(staticRoot,
        'pixiv-batch/batch-artwork-requests.js'), 'utf8'), context);
    return context;
}

function loadUserscript(file, respond, novel = false) {
    const source = fs.readFileSync(path.join(root, file), 'utf8');
    const single = file.includes('Java后端版');
    const name = novel ? 'checkNovelDownloaded' : 'checkDownloaded';
    const start = source.indexOf((single ? '    async function ' : '        ') + name + '(');
    const end = single ? source.indexOf('\n    }', start) + 6 : source.indexOf(novel ? '\n        sendNovelDownloadRequest' : '\n        get', start);
    const method = source.slice(start, end).trim();
    const context = {GM_xmlhttpRequest: respond, serverBase: 'http://localhost',
        CONFIG: {CHECK_DOWNLOADED_URL: 'http://localhost/api/downloaded'},
        getDownloadedCheckBase: () => 'http://localhost/api/downloaded', handleUnauthorized() {}, t: key => key};
    vm.createContext(context);
    if (!single) vm.runInContext(fs.readFileSync(path.join(root,
        'scripts/shared/download-queue-runner.js'), 'utf8'), context);
    vm.runInContext(single ? method + '; this.check = ' + name + ';' : 'this.check = ({' + method + '}).' + name + ';', context);
    return context.check;
}

test('两版工作台共享历史检查：仅明确无记录时继续，服务错误和损坏响应中止判重', async () => {
    let result = {status: 400};
    const check = loadWeb(async () => result).checkDownloaded;
    assert.equal(await check(42), null);
    result = {status: 200, json: async () => ({artworkId: 42, deleted: true})};
    assert.equal((await check(42)).deleted, true);
    for (const status of [401, 403, 404, 503]) {
        result = {status};
        await assert.rejects(() => check(42));
    }
    result = {status: 200, json: async () => ({})};
    await assert.rejects(() => check(42));
    await assert.rejects(loadWeb(async () => { throw new Error('offline'); }).checkDownloaded(42));
});

test('作品资料请求保留路径、原始响应与服务端错误', async () => {
    let response = {title: 'fixture', urls: ['image-url']};
    const paths = [];
    const api = loadWeb(async path => { paths.push(path); return response; });
    assert.equal(await api.getArtworkMeta('42'), response);
    assert.equal(await api.getArtworkPages('42'), response.urls);
    assert.equal(await api.getUgoiraMeta('42'), response);
    assert.deepEqual(paths, ['/api/pixiv/artwork/42/meta', '/api/pixiv/artwork/42/pages',
        '/api/pixiv/artwork/42/ugoira']);
    response = {};
    assert.deepEqual(Array.from(await api.getArtworkPages('42')), []);
    response = {error: 'source unavailable'};
    for (const method of ['getArtworkMeta', 'getArtworkPages', 'getUgoiraMeta']) {
        await assert.rejects(api[method]('42'), /source unavailable/);
    }
});

test('作品请求在执行失效后拒绝迟到结果并透传取消信号', async () => {
    for (const method of ['getArtworkMeta', 'getArtworkPages', 'getUgoiraMeta',
        'checkDownloaded', 'recoverArtworkMetadata', 'getDownloadStatus']) {
        const signal = new AbortController().signal;
        let active = true;
        let requests = 0;
        const invocation = {signal, assertActive() { if (!active) throw new Error('stale'); }};
        const api = loadWeb(async (_path, options) => {
            requests++;
            assert.equal(options.signal, signal);
            active = false;
            return {status: 200, json: async () => ({artworkId: 42})};
        });
        const args = method === 'recoverArtworkMetadata' ? ['42', {}, invocation] : ['42', invocation];
        await assert.rejects(api[method](...args), /stale/);
        assert.equal(requests, 1);
        await assert.rejects(api[method](...args), /stale/);
        assert.equal(requests, 1, '失效执行不得再发送请求');
    }
});

test('元数据补齐保留请求体且普通失败只返回缺席结果', async () => {
    let result = {status: 503};
    const metadata = {title: 'fixture', tags: ['tag']};
    const api = loadWeb(async (path, options) => {
        assert.equal(path, '/api/downloaded/42/recover-metadata');
        assert.equal(options.method, 'POST');
        assert.equal(options.headers['Content-Type'], 'application/json');
        assert.deepEqual(JSON.parse(options.body), metadata);
        return result;
    });
    assert.equal(await api.recoverArtworkMetadata('42', metadata), null);
    const recovered = {artworkId: 42};
    result = {status: 200, json: async () => recovered};
    assert.equal(await api.recoverArtworkMetadata('42', metadata), recovered);
    assert.equal(await loadWeb(async () => { throw new Error('offline'); })
        .recoverArtworkMetadata('42', metadata), null);
});

for (const file of ['Pixiv User 批量下载器(User Batch).user.js',
    'Pixiv URL 批量导入单作品下载器(URL Batch).user.js', 'Pixiv 页面批量下载器(Page Scrape).user.js',
    'Pixiv 单作品图片下载器(Java后端版).user.js']) {
    test(file + '：历史检查失败不能降级成未下载', async () => {
        let respond = request => request.onload({status: 400});
        const check = loadUserscript(file, request => respond(request));
        assert.equal(await check(42, true), null);
        respond = request => request.onload({status: 200, responseText: '{"artworkId":42,"deleted":true}'});
        assert.equal((await check(42)).deleted, true);
        for (const status of [401, 403, 404, 503]) {
            respond = request => request.onload({status});
            await assert.rejects(() => check(42));
        }
        for (const responseText of ['{}', 'invalid json']) {
            respond = request => request.onload({status: 200, responseText});
            await assert.rejects(() => check(42));
        }
        for (const event of ['onerror', 'ontimeout']) {
            respond = request => request[event]();
            await assert.rejects(() => check(42));
        }
    });
}

for (const file of ['Pixiv User 批量下载器(User Batch).user.js',
    'Pixiv URL 批量导入单作品下载器(URL Batch).user.js', 'Pixiv 页面批量下载器(Page Scrape).user.js']) {
    test(file + '：历史查询失败结束当前条目且不发起下载', async () => {
        const source = fs.readFileSync(path.join(root, file), 'utf8');
        const start = source.indexOf('        async _processSingle(');
        const end = source.indexOf('\n        }', start) + 10;
        let saves = 0;
        const context = {Api: {
            checkDownloaded: async () => { throw new Error('history unavailable'); },
            getArtworkMeta: async () => assert.fail('history error must stop download')
        }};
        vm.createContext(context);
        vm.runInContext('this.process = ({' + source.slice(start, end) + '})._processSingle;', context);
        const item = {id: '42', title: 'fixture', status: 'downloading'};
        await context.process.call({queue: [item], skipHistory: true,
            _queueController: new AbortController(),
            globalSettings: {skipHistory: true},
            sse: {close() {}}, ui: {renderQueue() {}, setStatus() {}, setCurrent() {}},
            updateStats() {}, saveToStorage() { saves++; }
        }, {idx: 0, item});
        assert.equal(item.status, 'failed');
        assert.ok(item.endTime);
        assert.ok(saves > 0);
    });
}

for (const file of ['Pixiv User 批量下载器(User Batch).user.js',
    'Pixiv URL 批量导入单作品下载器(URL Batch).user.js', 'Pixiv 页面批量下载器(Page Scrape).user.js',
    'Pixiv 单作品图片下载器(Java后端版).user.js']) {
    test(file + '：小说判重保留软删除状态，异常响应中止下载', async () => {
        let response = {status: 200, responseText: '{"downloaded":false,"deleted":false}'};
        const check = loadUserscript(file, request => request.onload(response), true);
        assert.equal(await check(42), null);
        response.responseText = '{"downloaded":true,"deleted":true}';
        assert.equal((await check(42)).deleted, true);
        for (const status of [400, 401, 404, 503]) {
            response = {status};
            await assert.rejects(() => check(42));
        }
        for (const responseText of ['{}', 'invalid json']) {
            response = {status: 200, responseText};
            await assert.rejects(() => check(42));
        }
        for (const event of ['onerror', 'ontimeout']) {
            await assert.rejects(loadUserscript(file, request => request[event](), true)(42));
        }
    });
}
