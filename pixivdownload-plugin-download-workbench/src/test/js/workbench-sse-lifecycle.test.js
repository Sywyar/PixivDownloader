'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.resolve(__dirname, '../../main/resources/static');
const flush = () => new Promise(resolve => setImmediate(resolve));

function runtime(alt) {
    const streams = [], requests = [], timers = new Map(), messages = [], samples = [];
    let sequence = 0, starts = 0, stops = 0;
    const state = {sseRefs: {}, sseListeners: {}};
    class EventSource {
        constructor(url) {
            assert.equal(url, '/api/sse/download');
            this.handlers = new Map(); this.closes = 0; streams.push(this);
        }
        addEventListener(name, listener) { this.handlers.set(name, listener); }
        emit(name, data) { this.handlers.get(name)?.({data}); }
        close() { this.closes++; }
    }
    const c = vm.createContext({state, EventSource, BASE: '', window: {PixivBatch: {}},
        startSpeedMeter() { starts++; }, stopSpeedMeter() { stops++; },
        accumulateDownloadSpeed: (id, data) => samples.push([id, data.downloadedCount]),
        setDockStatus: (message, tone) => messages.push([message, tone]),
        setTimeout(fn, delay) { timers.set(++sequence, {fn, delay}); return sequence; },
        clearTimeout: id => timers.delete(id),
        fetch(url, options) {
            assert.equal(options.method, 'POST');
            assert.equal(options.credentials, 'same-origin');
            assert.equal(options.keepalive, true);
            return new Promise((resolve, reject) => requests.push({url, resolve, reject}));
        }});
    const html = fs.readFileSync(path.join(root, alt ? 'pixiv-batch-alt.html' : 'pixiv-batch.html'), 'utf8');
    for (const [, src] of html.matchAll(/<script\b[^>]*\bsrc="([^"]+)"/g)) {
        if (/\/(download-sse|batch-sse|alt-engine-stream)\.js$/.test(src)) {
            vm.runInContext(fs.readFileSync(path.join(root, '.' + src), 'utf8'), c, {filename: src});
        }
    }
    return {c, state, streams, requests, timers, messages, samples,
        starts: () => starts, stops: () => stops,
        timeout() {
            const [id, timer] = timers.entries().next().value;
            assert.equal(timer.delay, 1000); timers.delete(id); timer.fn();
        }};
}

for (const alt of [false, true]) {
    const label = alt ? '新版' : '经典';
    test(label + '关闭后立即重开时，旧事件不能污染新进度、连接身份或提示', async () => {
        const r = runtime(alt), events = [];
        r.c.ensureSharedSSE();
        r.streams[0].emit('aggregated-ready', 'old/id');
        r.c.closeAllSSE();
        r.c.ensureSharedSSE();
        r.streams[1].emit('aggregated-ready', 'new/id');
        r.c.addSSEListener('42', data => events.push(data.downloadedCount));
        r.streams[0].emit('aggregated-ready', 'old-late');
        r.streams[0].emit('download-status', '{"artworkId":42,"downloadedCount":99}');
        r.streams[0].emit('plugin-unavailable', 'old failure');
        assert.deepEqual(events, []);
        assert.deepEqual(r.samples, []);
        assert.deepEqual(r.messages, []);
        assert.equal(r.state.sharedSseConnectionId, 'new/id');
        r.streams[1].emit('download-status', '{"artworkId":42,"downloadedCount":1}');
        r.streams[1].emit('plugin-unavailable', 'current failure');
        assert.deepEqual(events, [1]);
        assert.deepEqual(r.samples, [['42', 1]]);
        assert.deepEqual(r.messages, alt ? [['current failure', 'error']] : []);
        r.c.closeAllSSE();
        assert.deepEqual(r.requests.map(request => request.url), [
            '/api/sse/close/aggregated/old%2Fid', '/api/sse/close/aggregated/new%2Fid']);
        r.timeout();
        assert.equal(r.streams[0].closes, 1);
        assert.equal(r.streams[1].closes, 0);
        r.requests[0].resolve({ok: true});
        r.requests[1].reject(new Error('offline'));
        await flush();
        assert.equal(r.streams[0].closes, 1);
        assert.equal(r.streams[1].closes, 1);
        assert.equal(r.timers.size, 0);
    });

    test(label + '单个监听在回调中移除自己时，同作品其它监听仍收到当前事件', () => {
        const r = runtime(alt), events = [];
        r.c.ensureSharedSSE();
        const first = () => { events.push('first'); r.c.removeSSEListener('42', first); };
        r.c.addSSEListener('42', first);
        r.c.addSSEListener('42', () => events.push('second'));
        r.c.addSSEListener('other', () => assert.fail('wrong artwork'));
        r.streams[0].emit('download-status', '{"artworkId":42,"completed":true}');
        assert.deepEqual(events, ['first', 'second']);
        r.streams[0].emit('download-status', '{"artworkId":42,"completed":true}');
        assert.deepEqual(events, ['first', 'second', 'second']);
    });

    test(label + '服务端关闭只释放对应连接，单项移除保留共享连接并拒绝无效消息', async () => {
        const r = runtime(alt), events = [];
        r.c.ensureSharedSSE(); r.c.ensureSharedSSE();
        assert.equal(r.streams.length, 1);
        assert.equal(r.starts(), 2);
        r.c.openSSE('42'); r.c.openSSE('42');
        r.c.addSSEListener('42', data => events.push(data));
        r.c.closeSSE('42');
        assert.equal(r.state.sseRefs['42'], 1);
        assert.equal(r.streams[0].closes, 0);
        r.c.closeSSE('42');
        assert.equal(r.state.sseRefs['42'], undefined);
        for (const data of ['invalid', 'null', '{}', '{"artworkId":42}']) {
            r.streams[0].emit('download-status', data);
        }
        assert.deepEqual(events, []);
        r.streams[0].emit('sse-closing');
        assert.equal(r.state.sharedSse, null);
        r.c.ensureSharedSSE();
        r.streams[0].emit('sse-closing');
        assert.equal(r.state.sharedSse, r.streams[1]);
        r.c.closeAllSSE();
        await flush();
        assert.equal(r.streams[1].closes, 1);
        assert.equal(r.stops(), 1);
        assert.equal(r.requests.length, 0);
        assert.equal(r.timers.size, 0);
    });
}
