'use strict';

const assert = require('node:assert/strict');
const {test} = require('node:test');
const {readFileSync} = require('node:fs');
const {resolve} = require('node:path');
const vm = require('node:vm');
const root = resolve(__dirname, '../../main/resources/static');
const load = (context, file) => vm.runInContext(readFileSync(resolve(root, file), 'utf8'), context);

test('布局切换等待设置和偏好保存后访问统一入口，保留参数与锚点，失败可重试', async () => {
    const button = {dataset: {downloadPage: 'pixiv-batch-alt.html'}};
    const calls = [], messages = [], events = {};
    let release, fail = false;
    const context = vm.createContext({
        document: {getElementById: () => button},
        location: {pathname: '/pixiv-batch.html', search: '?tab=schedule', hash: '#settings', assign: value => calls.push(value),
            reload: () => calls.push('reload')},
        addEventListener: (name, handler) => { events[name] = handler; },
        PixivFeedback: {toast: value => messages.push(value)},
        fetch: async (url, options) => {
            calls.push([url, options]);
            return {ok: !fail, status: fail ? 500 : 204};
        }
    });
    context.window = context;
    load(context, 'pixiv-batch/page-layout.js');
    const bind = (admin, flush) => context.PixivBatchPageLayout.bind(admin, () => 'localized failure', flush);
    bind(false, () => {});
    assert.equal(button.hidden, true);
    assert.equal(button.onclick, null);
    bind(true, () => new Promise(resolve => { release = resolve; }));
    const pending = button.onclick();
    await button.onclick();
    assert.equal(button.disabled, true);
    assert.equal(calls.length, 0);
    release();
    await pending;
    assert.equal(calls[0][0], '/api/batch/page');
    assert.equal(calls[0][1].method, 'POST');
    assert.deepEqual(JSON.parse(calls[0][1].body), {page: 'pixiv-batch-alt.html'});
    assert.equal(calls[1], 'reload');
    assert.equal(context.location.search + context.location.hash, '?tab=schedule#settings');
    assert.equal(button.disabled, false);
    events.pageshow({persisted: false});
    assert.equal(calls.length, 2);
    events.pageshow({persisted: true});
    assert.equal(calls.at(-1), 'reload');
    calls.length = 0;
    bind(true, async () => { throw Error('save failed'); });
    await button.onclick();
    assert.equal(calls.length, 0);
    assert.equal(messages.at(-1).message, 'localized failure');
    bind(true, async () => {});
    fail = true;
    await button.onclick();
    assert.equal(calls.length, 1);
    assert.equal(messages.length, 2);
    fail = false;
    context.location.pathname = '/pixiv-batch-alt.html';
    button.dataset.downloadPage = 'pixiv-batch.html';
    await button.onclick();
    assert.equal(calls.at(-1), '/pixiv-batch.html?tab=schedule#settings');
});

for (const variant of ['classic', 'alternate']) {
    test(variant + ' 切换前排空自动保存，新快照不会被旧请求覆盖，持久化失败可观察', async () => {
        const requests = [], timers = new Map();
        let timerId = 0;
        const context = vm.createContext({
            serverState: {}, appMode: 'solo', BASE: '', PixivBatch: {}, PixivBatchAlt: {state: {}}, location: {},
            setTimeout: fn => { timers.set(++timerId, fn); return timerId; },
            clearTimeout: id => timers.delete(id),
            fetch: (url, options) => new Promise(resolve => requests.push({url, options, resolve}))
        });
        context.window = context;
        if (variant === 'classic') load(context, 'pixiv-batch/batch-storage.js');
        else {
            load(context, 'pixiv-batch/batch-download-defaults.js'); load(context, 'pixiv-batch-alt/alt-state.js');
            vm.runInContext("appMode = 'solo'", context);
        }
        context.storeSet('preference', 'older');
        const first = context.flushServerState();
        await Promise.resolve();
        await Promise.resolve();
        assert.equal(requests.length, 1);
        context.storeSet('preference', 'latest');
        const second = context.flushServerState();
        assert.equal(requests.length, 1);
        requests[0].resolve({ok: true});
        await first;
        await Promise.resolve();
        await Promise.resolve();
        assert.equal(requests.length, 2);
        assert.equal(JSON.parse(requests[1].options.body).state.preference, 'latest');
        requests[1].resolve({ok: false, status: 500});
        await assert.rejects(second, /HTTP 500/);
        assert.equal(timers.size, 0);
        context.storeSet('pixiv_cookie', 'fixture-cookie');
        const saving = context.flushServerState();
        await new Promise(resolve => setImmediate(resolve));
        const logout = context.doLogout();
        assert.equal(requests.length, 3);
        requests[2].resolve({ok: true});
        await saving;
        await new Promise(resolve => setImmediate(resolve));
        assert.equal(JSON.parse(requests[3].options.body).state.pixiv_cookie, undefined);
        requests[3].resolve({ok: true});
        await new Promise(resolve => setImmediate(resolve));
        assert.equal(requests[4].url, '/api/auth/logout');
        requests[4].resolve({ok: true});
        await logout;
        assert.equal(context.location.href, '/pixiv-batch.html');
        vm.runInContext("appMode = 'multi'", context);
        await context.flushServerState();
        assert.equal(requests.length, 5);
    });
}
