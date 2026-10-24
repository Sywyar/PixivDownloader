'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.resolve(__dirname,
    '../../../../Pixiv 体验增强工具箱(Toolbox).user.js'), 'utf8');

function harness(cookie = 'PHPSESSID=test-session; language=en') {
    const requests = [];
    const timers = new Map();
    let timerId = 0;
    const context = vm.createContext({
        serverBase: 'http://localhost:8101',
        document: {cookie},
        location: {host: 'www.pixiv.net', protocol: 'https:'},
        URL, console: {warn() {}, error() {}},
        t: (_key, fallback) => fallback,
        setTimeout(callback, delay) {
            const id = ++timerId;
            timers.set(id, {callback, delay});
            return id;
        },
        clearTimeout: id => timers.delete(id),
        GM_xmlhttpRequest: request => { requests.push(request); }
    });
    const start = source.indexOf('function gmRequest(');
    const end = source.indexOf('/* ========== 门槛求值', start);
    assert.ok(start >= 0 && end > start);
    vm.runInContext(source.slice(start, end)
        + '\nthis.status = ServerStatus;', context);
    const respond = (index, body, status = 200) => requests[index].onload({
        status, responseText: typeof body === 'string' ? body : JSON.stringify(body)
    });
    return {context, requests, timers, respond};
}

const flush = () => new Promise(resolve => setImmediate(resolve));

test('服务器切换立即撤回旧状态，只串行查询最后选中的服务器', async () => {
    const h = harness();
    const seen = [];
    h.context.status.onChange(state => seen.push({...state}));
    const first = h.context.status.refresh();
    const duplicate = h.context.status.refresh();
    assert.equal(h.requests.length, 2);
    h.context.serverBase = 'https://intermediate.example';
    const intermediate = h.context.status.refresh();
    h.context.serverBase = 'https://current.example';
    const current = h.context.status.refresh();
    assert.equal(h.context.status.get().phase, 'checking');
    assert.equal(h.context.status.get().local, false);
    assert.equal(h.requests.length, 2, '旧查询退出前不得叠加网络请求');

    h.respond(0, {mode: 'solo'});
    h.respond(1, {valid: true});
    await flush();
    assert.equal(h.requests.length, 4);
    assert.ok(h.requests.slice(2).every(r => r.url.startsWith('https://current.example/')));
    assert.equal(seen.some(state => state.phase === 'resolved'), false);
    h.respond(2, {mode: 'solo'});
    h.respond(3, {valid: false});
    await Promise.all([first, duplicate, intermediate, current]);
    assert.deepEqual({...h.context.status.get()}, {
        phase: 'resolved', local: false, mode: 'solo', loggedIn: false
    });
    assert.equal(h.timers.size, 0);
});

test('旧服务器失败不会发布不可达状态，后续查询仍可重试', async () => {
    const h = harness();
    const seen = [];
    h.context.status.onChange(state => seen.push({...state}));
    const first = h.context.status.refresh();
    h.context.serverBase = 'http://127.0.0.1:8102';
    const next = h.context.status.refresh();
    h.requests[0].onerror();
    await flush();
    assert.equal(h.requests.length, 2, '同轮另一个请求结束前不得开始新查询');
    h.respond(1, {valid: true});
    await flush();
    assert.equal(seen.some(state => state.phase === 'unreachable'), false);
    assert.equal(h.requests.length, 4);
    h.respond(2, {mode: 'solo'});
    h.respond(3, {valid: true});
    await Promise.all([first, next]);
    assert.equal(h.context.status.get().loggedIn, true);

    const retry = h.context.status.refresh();
    h.requests[4].onerror();
    h.respond(5, {valid: true});
    await retry;
    assert.equal(h.context.status.get().phase, 'unreachable');
    assert.equal(h.context.status.get().loggedIn, false);
    assert.equal(h.timers.size, 0);
});

test('Cookie 同步保留完整已有状态并只替换自己的字段', async () => {
    const h = harness();
    const pending = h.context.pushPixivCookieToServer(h.context.serverBase);
    await flush();
    assert.equal(h.requests[0].method, 'GET');
    h.respond(0, {state: {queue: ['keep'], nested: {x: 1}, pixiv_cookie: 'old'}});
    await flush();
    const posted = JSON.parse(h.requests[1].data).state;
    assert.deepEqual(posted.queue, ['keep']);
    assert.deepEqual(posted.nested, {x: 1});
    assert.equal(posted.pixiv_cookie, h.context.document.cookie);
    assert.equal(posted.pixiv_cookie_fmt, 'header');
    assert.equal(posted.pixiv_cookie_sync_status, 'ok');
    assert.match(posted.pixiv_cookie_sync_at, /^\d+$/);
    h.respond(1, '');
    assert.equal((await pending).code, 'ok');
    assert.equal(h.timers.size, 0);
});

test('损坏或非对象的已有状态使 Cookie 同步失败，不发送覆盖请求', async () => {
    for (const response of ['<html>error</html>', 'null', '{}', '{"state":null}',
        '{"state":[]}', '{"state":"invalid"}']) {
        const h = harness();
        const pending = h.context.pushPixivCookieToServer(h.context.serverBase);
        await flush();
        h.respond(0, response);
        await flush();
        assert.equal(h.requests.length, 1, response);
        assert.equal((await pending).code, 'network');
        assert.equal(h.timers.size, 0);
    }
});

test('缺少登录 Cookie 时仍报告同步结果并保留已保存 Cookie', async () => {
    for (const [cookie, code] of [['language=en', 'no-php'], ['', 'empty'],
        ['PHPSESSID=; language=en', 'no-php'], ['PHPSESSID=   ', 'no-php']]) {
        const h = harness(cookie);
        const pending = h.context.pushPixivCookieToServer(h.context.serverBase);
        await flush();
        h.respond(0, {state: {pixiv_cookie: 'keep', pixiv_cookie_fmt: 'json'}});
        await flush();
        const posted = JSON.parse(h.requests[1].data).state;
        assert.equal(posted.pixiv_cookie, 'keep');
        assert.equal(posted.pixiv_cookie_fmt, 'json');
        assert.equal(posted.pixiv_cookie_sync_status, code);
        h.respond(1, '');
        assert.equal((await pending).code, code);
    }
});

test('Cookie 读取和保存失败保留既有 HTTP 结果语义', async () => {
    for (const status of [401, 403, 503]) {
        const h = harness();
        const pending = h.context.pushPixivCookieToServer(h.context.serverBase);
        await flush();
        h.respond(0, '', status);
        const result = await pending;
        assert.equal(result.code, status === 401 ? 'unauthorized' : 'http');
        assert.equal(h.requests.length, 1);
    }
    const h = harness();
    const pending = h.context.pushPixivCookieToServer(h.context.serverBase);
    await flush();
    h.respond(0, {state: {}});
    await flush();
    h.respond(1, '', 403);
    assert.deepEqual({...await pending}, {code: 'http', status: 403});
});

test('Cookie API 的同步回调、Promise 和错误都会释放超时等待', async () => {
    for (const list of [(_details, callback) => callback([{name: 'x', value: 'y'}]),
        () => Promise.resolve([{name: 'x', value: 'y'}]),
        () => { throw new Error('failed'); }]) {
        const h = harness();
        await h.context.gmCookieList({list}, {});
        assert.equal(h.timers.size, 0);
    }
    const h = harness();
    const pending = h.context.gmCookieList({list() {}}, {});
    assert.equal(h.timers.size, 1);
    const timer = [...h.timers.values()][0];
    assert.equal(timer.delay, 4000);
    timer.callback();
    assert.equal(await pending, null);
    assert.equal(h.timers.size, 0);
});
