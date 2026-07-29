'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const repositoryRoot = path.resolve(__dirname, '../../../..');
const sharedSourcePath = path.join(
    repositoryRoot, 'scripts', 'shared', 'illust-exact-cancel.js');
const sharedSource = fs.readFileSync(sharedSourcePath, 'utf8');
const userscriptPaths = [
    'Pixiv User 批量下载器(User Batch).user.js',
    'Pixiv 页面批量下载器(Page Scrape).user.js',
    'Pixiv URL 批量导入单作品下载器(URL Batch).user.js'
].map(name => path.join(repositoryRoot, name));
const validOwnerUuid = '01234567-89ab-cdef-0123-456789abcdef';

function loadFactory() {
    const context = {};
    vm.runInNewContext(
        sharedSource + '\nthis.factory = createIllustExactCancelClient;',
        context);
    return context.factory;
}

function createHarness(initialServerBase = 'http://localhost:6999') {
    const requests = [];
    const failures = [];
    let currentServerBase = initialServerBase;
    let currentOwnerUuid = validOwnerUuid;
    const client = loadFactory()({
        initialServerBase,
        currentServerBase: () => currentServerBase,
        currentOwnerUuid: () => currentOwnerUuid,
        request: request => requests.push(request),
        onFailure: failure => failures.push(failure)
    });
    return {
        client,
        requests,
        failures,
        setCurrentServerBase(value) {
            currentServerBase = value;
        },
        setCurrentOwnerUuid(value) {
            currentOwnerUuid = value;
        }
    };
}

function respond(request, status, payload) {
    request.onload({
        status,
        responseText: typeof payload === 'string' ? payload : JSON.stringify(payload)
    });
}

async function waitForRequest(requests, count) {
    for (let attempt = 0; attempt < 20 && requests.length < count; attempt += 1) {
        await new Promise(resolve => setImmediate(resolve));
    }
    assert.equal(requests.length, count, `应已发出 ${count} 个请求`);
    return requests[count - 1];
}

function publicationResponse(owner = {}) {
    return {
        downloadTypes: [{
            type: 'illust',
            cancelSupported: true,
            owner: {
                pluginId: 'download-workbench',
                packageId: 'download-workbench',
                generation: 7,
                publicationId: 11,
                ...owner
            }
        }]
    };
}

test('页面初始化时捕获一次插画 publication，并以完整 owner 精确取消', async () => {
    const harness = createHarness();

    assert.equal(harness.requests.length, 1, '创建客户端时应立即捕获 publication');
    const extensionRequest = harness.requests[0];
    assert.equal(extensionRequest.method, 'GET');
    assert.equal(
        extensionRequest.url,
        'http://localhost:6999/api/download/extensions');
    assert.equal(extensionRequest.headers.Accept, 'application/json');
    respond(extensionRequest, 200, publicationResponse());

    const cancellation = harness.client.cancel('123456');
    const cancelRequest = await waitForRequest(harness.requests, 2);
    assert.equal(cancelRequest.method, 'POST');
    assert.equal(
        cancelRequest.url,
        'http://localhost:6999/api/download/queue/illust/cancel');
    assert.equal(cancelRequest.headers.Accept, 'application/json');
    assert.equal(cancelRequest.headers['Content-Type'], 'application/json');
    assert.equal(cancelRequest.headers['X-User-UUID'], validOwnerUuid);
    assert.deepEqual(JSON.parse(cancelRequest.data), {
        workKey: '123456',
        owner: {
            pluginId: 'download-workbench',
            packageId: 'download-workbench',
            generation: 7,
            publicationId: 11
        }
    });

    respond(cancelRequest, 200, {success: true});
    assert.equal(await cancellation, true);
    assert.equal(harness.failures.length, 0);
    assert.equal(
        harness.requests.filter(request => request.method === 'GET').length,
        1,
        '成功取消也不得刷新 publication');
});

test('服务器地址变化后拒绝把旧 publication 发送到新服务器', async () => {
    const harness = createHarness();
    respond(harness.requests[0], 200, publicationResponse());
    harness.setCurrentServerBase('http://localhost:7000');

    assert.equal(await harness.client.cancel('123456'), false);
    assert.equal(harness.requests.length, 1, '地址变化后不得发送取消请求');
    assert.equal(harness.failures.length, 1);
    assert.equal(harness.failures[0].code, 'QUEUE_CANCEL_DESCRIPTOR_STALE');
});

test('缺失插画 publication 会缓存失败并在每次取消时显式提示', async () => {
    const harness = createHarness();
    respond(harness.requests[0], 200, {downloadTypes: []});

    assert.deepEqual(
        await Promise.all([
            harness.client.cancel('123456'),
            harness.client.cancel('654321')
        ]),
        [false, false]);
    assert.equal(harness.requests.length, 1, '失败关闭后不得悄悄重绑新 publication');
    assert.deepEqual(
        harness.failures.map(failure => failure.code),
        [
            'QUEUE_CANCEL_DESCRIPTOR_UNAVAILABLE',
            'QUEUE_CANCEL_DESCRIPTOR_UNAVAILABLE'
        ]);
});

test('后端拒绝过期 owner 时返回失败并保留机器码和状态码', async () => {
    const harness = createHarness();
    respond(harness.requests[0], 200, publicationResponse());

    const cancellation = harness.client.cancel('123456');
    const cancelRequest = await waitForRequest(harness.requests, 2);
    respond(cancelRequest, 409, {
        success: false,
        code: 'QUEUE_CANCEL_DESCRIPTOR_STALE'
    });

    assert.equal(await cancellation, false);
    assert.equal(harness.failures.length, 1);
    assert.equal(harness.failures[0].code, 'QUEUE_CANCEL_DESCRIPTOR_STALE');
    assert.equal(harness.failures[0].status, 409);
});

test('仅有二百响应但没有明确 success true 时仍失败关闭', async () => {
    const harness = createHarness();
    respond(harness.requests[0], 200, publicationResponse());

    const cancellation = harness.client.cancel('123456');
    const cancelRequest = await waitForRequest(harness.requests, 2);
    respond(cancelRequest, 200, {});

    assert.equal(await cancellation, false);
    assert.equal(harness.failures.length, 1);
    assert.equal(harness.failures[0].code, 'QUEUE_CANCEL_FAILED');
});

test('三份分发用户脚本均只调用精确取消并提供双语失败提示', () => {
    for (const userscriptPath of userscriptPaths) {
        const source = fs.readFileSync(userscriptPath, 'utf8');
        assert.doesNotMatch(
            source,
            /\/api\/download\/cancel/,
            `${path.basename(userscriptPath)} 不得再调用旧取消端点`);
        assert.match(source, /\/\/ >>> SHARED:illust-exact-cancel\.js/);
        assert.match(source, /\/\/ <<< SHARED:illust-exact-cancel\.js/);
        assert.match(
            source,
            /return illustExactCancelClient\.cancel\(artworkId\);/);
        assert.match(
            source,
            /\/api\/download\/queue\/' \+ QUEUE_TYPE \+ '\/cancel/);
        assert.match(source, /Cancellation failed\./);
        assert.match(source, /取消失败：下载类型信息可能不可用或已过期/);
    }
});
