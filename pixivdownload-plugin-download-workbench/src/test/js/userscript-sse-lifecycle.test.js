const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const flush = () => new Promise(resolve => setImmediate(resolve));

function runtime(onRequest) {
    const requests = [], timers = new Map();
    let timerId = 0;
    const context = vm.createContext({
        TextDecoder, userUUID: 'queue-owner', console: {warn() {}, error() {}},
        CONFIG: {SSE_BASE: 'http://localhost/events', SSE_CLOSE_BASE: 'http://localhost/events/close'},
        setTimeout(fn, delay) { timers.set(++timerId, {fn, delay}); return timerId; },
        clearTimeout(id) { timers.delete(id); },
        GM_xmlhttpRequest(options) {
            const request = {options, aborts: 0};
            requests.push(request);
            if (onRequest) onRequest(request);
            return {abort() { request.aborts++; options.onerror(new Error('aborted')); }};
        }
    });
    const source = path.resolve(__dirname, '../../../../scripts/shared/sse-manager.js');
    vm.runInContext(fs.readFileSync(source, 'utf8') + '\nglobalThis.manager = new SSEManager();', context);
    return {manager: context.manager, requests, timers,
        fire(delay) {
            const entry = [...timers].find(([, timer]) => timer.delay === delay);
            assert.ok(entry, `missing ${delay} ms timer`);
            timers.delete(entry[0]);
            entry[1].fn();
        }
    };
}

function connect(request, cancel) {
    let controller;
    const stream = new ReadableStream({start(value) { controller = value; }, cancel});
    request.options.onloadstart({response: stream});
    return {
        send(value) { controller.enqueue(new TextEncoder().encode(value)); },
        close() { controller.close(); },
        fail() { controller.error(new Error('stream failed')); }
    };
}

test('关闭后立即重开时，旧流取消与请求回调不能终止新连接', async () => {
    const r = runtime();
    r.manager.openShared();
    connect(r.requests[0]);
    r.manager.closeAll();
    r.manager.openShared();
    const current = connect(r.requests[1]);
    const events = [];
    r.manager.addListener('42', event => events.push(event.status));
    await flush();
    r.requests[0].options.onerror(new Error('late failure'));
    r.requests[0].options.ontimeout();
    const late = new ReadableStream();
    r.requests[0].options.onloadstart({response: late});
    assert.equal(late.locked, false);
    current.send('event: download-status\ndata: {"artworkId":42,"status":"completed"}\n\n');
    await flush();
    assert.deepEqual(events, ['completed']);
    assert.equal(r.requests[0].aborts, 1);
    assert.equal(r.requests[1].aborts, 0);
    assert.equal([...r.timers.values()].filter(timer => timer.delay === 2000).length, 0);
    r.manager.closeAll();
    r.fire(1000);
    r.fire(1000);
    await flush();
    assert.equal(r.requests[1].aborts, 1);
});

test('旧关闭定时器不缩短新连接的关闭窗口，通知绑定各自连接和用户', async () => {
    const r = runtime();
    r.manager.openShared();
    connect(r.requests[0]).send('event: aggregated-ready\ndata: old/id\n\n');
    await flush();
    r.manager.closeAll();
    r.manager.openShared();
    connect(r.requests[2]).send('event: aggregated-ready\ndata: new/id\n\n');
    await flush();
    r.manager.closeAll();
    const notifications = r.requests.filter(request => request.options.method === 'POST');
    assert.deepEqual(notifications.map(request => request.options.url), [
        'http://localhost/events/close/old%2Fid', 'http://localhost/events/close/new%2Fid'
    ]);
    assert.ok(r.requests.every(request => request.options.headers['X-User-UUID'] === 'queue-owner'));
    r.fire(1000);
    assert.equal(r.requests[2].aborts, 0);
    r.fire(1000);
    await flush();
    assert.equal(r.requests[2].aborts, 1);
});

for (const end of ['close', 'fail']) {
    test(`活动流 ${end} 后只重连一次，批次关闭后不再重连`, async () => {
        const r = runtime();
        r.manager.openShared();
        const stream = connect(r.requests[0]);
        r.manager.openShared();
        assert.equal(r.requests.length, 1);
        stream[end]();
        await flush();
        assert.equal(r.timers.size, 1);
        r.fire(2000);
        const replacement = connect(r.requests[1]);
        replacement[end]();
        await flush();
        r.manager.closeAll();
        assert.equal(r.timers.size, 0);
        assert.equal(r.requests.length, 2);
    });
}

test('服务端关闭事件后丢弃同一块中的旧状态，不重连已关闭的流', async () => {
    const r = runtime();
    r.manager.openShared();
    const stream = connect(r.requests[0]);
    const events = [];
    r.manager.addListener(42, event => events.push(event));
    stream.send('event: sse-closing\n\nevent: download-status\ndata: {"artworkId":42}\n\n');
    await flush();
    assert.deepEqual(events, []);
    assert.equal(r.requests[0].aborts, 1);
    assert.equal(r.timers.size, 0);
});

test('取消流被拒绝时仍释放请求，旧取消失败不能影响重新打开的流', async () => {
    const r = runtime();
    r.manager.openShared();
    connect(r.requests[0], () => Promise.reject(new Error('cancel failed')));
    r.manager.closeAll();
    r.manager.openShared();
    connect(r.requests[1]);
    await flush();
    assert.equal(r.requests[0].aborts, 1);
    assert.equal(r.requests[1].aborts, 0);
    r.manager.closeAll();
    r.fire(1000);
    r.fire(1000);
    await flush();
});

test('请求同步失败时释放迟到的句柄，正常重连仍可接收分块状态', async () => {
    const r = runtime(request => {
        if (r.requests.length === 1) request.options.ontimeout();
    });
    r.manager.openShared();
    assert.equal(r.requests[0].aborts, 1);
    r.fire(2000);
    const stream = connect(r.requests[1]);
    const events = [];
    r.manager.addListener(42, event => events.push(event.status));
    r.manager.addListener(43, () => assert.fail('wrong artwork'));
    stream.send('event: download-status\ndata: {"artworkId":42,');
    await flush();
    assert.deepEqual(events, []);
    stream.send('"status":"running"}\n\n');
    await flush();
    assert.deepEqual(events, ['running']);
    r.manager.close(42);
    stream.send('event: download-status\ndata: {"artworkId":42,"status":"completed"}\n\n');
    await flush();
    assert.deepEqual(events, ['running']);
    assert.equal(r.requests[1].aborts, 0);
    r.manager.closeAll();
    r.fire(1000);
    await flush();
});
