'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/pixiv-gallery/gallery-core.js'), 'utf8');

function api(fetch) {
    const location = {pathname: '/pixiv-gallery.html', search: '?page=2', href: ''};
    const context = vm.createContext({window: {location}, location, fetch, URL, document: {}});
    vm.runInContext(source, context);
    return {call: () => vm.runInContext('api("/api/gallery/artworks")', context), location};
}

test('画廊保留稳定错误码与状态，服务器与旧式错误使用安全提示', async () => {
    const detail = 'Handler dispatch failed: java.lang.NoSuchMethodError internal-probe';
    for (const [status, body] of [[500, {code: 'error.unexpected', error: detail}],
        [400, {message: detail}], [400, {error: detail}], [502, null]]) {
        const page = api(async () => ({ok: false, status, json: async () => body}));
        await assert.rejects(page.call(), error => {
            assert.equal(error.status, status);
            assert.equal(error.code, body?.code || '');
            assert.doesNotMatch(error.message, /internal-probe|NoSuchMethodError|Handler dispatch/);
            assert.ok(error.message.length > 0);
            return true;
        });
    }
});

test('画廊保留业务提示、登录回跳与取消语义', async () => {
    const business = api(async () => ({ok: false, status: 409,
        json: async () => ({code: 'work.delete.file-failed', error: 'Files are in use. Please retry.'})}));
    await assert.rejects(business.call(), {code: 'work.delete.file-failed', status: 409,
        message: 'Files are in use. Please retry.'});
    const unauthorized = api(async () => ({status: 401}));
    await assert.rejects(unauthorized.call(), {status: 401});
    assert.equal(unauthorized.location.href, '/login.html?redirect=%2Fpixiv-gallery.html%3Fpage%3D2');
    const abort = Object.assign(new Error('cancelled'), {name: 'AbortError'});
    await assert.rejects(api(async () => { throw abort; }).call(), error => error === abort);
    await assert.rejects(api(async () => { throw new Error('internal-probe'); }).call(), error => {
        assert.equal(error.code, 'NETWORK_ERROR');
        assert.equal(error.status, 0);
        assert.doesNotMatch(error.message, /internal-probe/);
        return true;
    });
});

test('画廊不展示成功响应中的 JSON 解析错误与响应片段', async () => {
    const page = api(async () => ({ok: true, status: 200, headers: {get: () => 'application/json'},
        json: async () => { throw new SyntaxError('Unexpected token internal-probe'); }}));
    await assert.rejects(page.call(), error => {
        assert.equal(error.code, 'INVALID_RESPONSE');
        assert.equal(error.status, 200);
        assert.doesNotMatch(error.message, /internal-probe|Unexpected token/);
        return true;
    });
});

test('画廊保留数据库、插件停用和 Pixiv 上游失败的受控处理建议', async () => {
    for (const code of ['DATABASE_UNAVAILABLE', 'plugin.unavailable.quiesced',
        'error.pixiv.upstream.unauthorized', 'error.pixiv.upstream.failed', 'error.pixiv.response.too-large']) {
        const page = api(async () => ({ok: false, status: 503,
            json: async () => ({code, error: 'Controlled recovery instructions'})}));
        await assert.rejects(page.call(), {code, status: 503, message: 'Controlled recovery instructions'});
    }
});
