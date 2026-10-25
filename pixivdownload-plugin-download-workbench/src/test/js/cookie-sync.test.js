'use strict';

const {test} = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const {resolve} = require('node:path');
const vm = require('node:vm');

function harness() {
    const timers = new Map(), listeners = new Map(), imported = [], timeouts = [], requests = [];
    let now = 0, closed = 0;
    const c = vm.createContext({BASE: '', AbortController,
        Date: {now: () => now},
        setTimeout(fn, delay) { const key = Symbol(); timers.set(key, {fn, delay}); return key; },
        clearTimeout: key => timers.delete(key),
        addEventListener: (name, fn) => listeners.set(name, fn),
        fetch: async (url, options) => { requests.push({url, options}); return {ok: false}; }
    });
    c.window = c;
    vm.runInContext(readFileSync(resolve(__dirname, '../../main/resources/static/pixiv-batch/cookie-sync.js'), 'utf8'), c);
    return {c, timers, requests, imported, timeouts, listeners,
        start: () => c.startCookieSync({close() { closed++; }}, 'baseline',
            value => imported.push(value), () => timeouts.push(true)),
        tick() { const [id, timer] = timers.entries().next().value; timers.delete(id); return timer.fn(); },
        advance: value => { now += value; }, closed: () => closed};
}

test('Cookie 同步串行轮询，变化时间戳交付快照并释放当前操作', async () => {
    const h = harness();
    h.start();
    assert.equal(h.requests.length, 0);
    await h.tick();
    assert.equal(h.requests.length, 1);
    assert.equal(h.requests[0].url, '/api/batch/state');
    assert.equal(h.requests[0].options.credentials, 'same-origin');
    assert.equal(h.timers.size, 1);
    h.c.fetch = async () => ({ok: true, json: async () => ({state: {
        pixiv_cookie: 'PHPSESSID=imported', pixiv_cookie_fmt: 'header',
        pixiv_cookie_sync_at: 'next', pixiv_cookie_sync_status: 'ok'
    }})});
    await h.tick();
    assert.deepEqual(JSON.parse(JSON.stringify(h.imported)), [{cookie: 'PHPSESSID=imported',
        fmt: 'header', syncAt: 'next', syncStatus: 'ok'}]);
    assert.equal(h.closed(), 1);
    assert.equal(h.timers.size, 0);
    assert.equal(h.timeouts.length, 0);
});

test('Cookie 同步替换及离页取消请求，旧正文和失败不恢复轮询', async () => {
    for (const reject of [false, true]) {
        const h = harness();
        let completeBody, signal;
        h.c.fetch = async (_url, options) => {
            signal = options.signal;
            return {ok: true, json: () => new Promise((resolve, fail) => {
                completeBody = reject ? () => fail(new Error('transport failed'))
                    : () => resolve({state: {pixiv_cookie_sync_at: 'next'}});
            })};
        };
        h.start();
        const pending = h.tick();
        await new Promise(resolve => setImmediate(resolve));
        assert.equal(h.timers.size, 0, '在途查询不叠加轮询');
        h.start();
        assert.equal(signal.aborted, true);
        assert.equal(h.timers.size, 1);
        completeBody();
        await pending;
        assert.equal(h.imported.length, 0);
        assert.equal(h.timers.size, 1);
        h.listeners.get('pagehide')();
        assert.equal(h.timers.size, 0);
        assert.equal(h.closed(), 0);
    }
});

test('Cookie 同步保留无结果重试与超时提示，不把空状态当作导入成功', async () => {
    const h = harness();
    h.c.fetch = async () => ({ok: true, json: async () => ({state: {pixiv_cookie_sync_at: 'baseline'}})});
    h.start();
    await h.tick();
    assert.equal(h.imported.length, 0);
    h.advance(26000);
    await h.tick();
    assert.equal(h.timeouts.length, 1);
    assert.equal(h.timers.size, 0);
    assert.equal(h.closed(), 0);
    h.c.cancelCookieSync();
    assert.equal(h.timeouts.length, 1);
});
