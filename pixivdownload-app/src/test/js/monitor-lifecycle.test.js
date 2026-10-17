'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const flush = () => new Promise(resolve => setImmediate(resolve));
const response = value => ({ok: true, json: async () => value});

function page() {
    const requests = [], streams = [], timers = new Map(), events = new Map(), elements = new Map();
    let timerId = 0;
    const context = vm.createContext({
        AbortController, console: {error() {}},
        setTimeout(fn, delay) { timers.set(++timerId, {fn, delay}); return timerId; },
        clearTimeout(id) { timers.delete(id); },
        setInterval(fn, delay) { timers.set(++timerId, {fn, delay, interval: true}); return timerId; },
        clearInterval(id) { timers.delete(id); },
        document: {
            hidden: false,
            addEventListener(type, fn) { events.set(type, fn); },
            getElementById(id) {
                if (!elements.has(id)) elements.set(id, {textContent: '', innerHTML: '', classList: {add() {}, remove() {}}});
                return elements.get(id);
            }
        },
        window: {addEventListener(type, fn) { events.set(type, fn); }},
        fetch(url, options = {}) {
            return new Promise((resolve, reject) => requests.push({url, options, resolve, reject}));
        },
        EventSource: class {
            constructor(url) { this.url = url; this.closed = false; streams.push(this); }
            addEventListener(type, fn) { this[type] = fn; }
            close() { this.closed = true; }
            send(data) { this['download-status']({data: JSON.stringify(data)}); }
        }
    });
    for (const file of ['monitor-core.js', 'monitor-sse.js', 'monitor-init.js']) {
        vm.runInContext(fs.readFileSync(path.join(__dirname, '../../main/resources/static/monitor', file), 'utf8'), context);
    }
    vm.runInContext('function loadStatistics() {} function loadAllDataForChart() {}', context);
    return {context, requests, streams, timers, events, elements,
        sync: () => vm.runInContext('syncActiveIds()', context),
        count: () => elements.get('activeCount')?.textContent,
        async discover(id) {
            const started = requests.length, pending = this.sync();
            requests[started].resolve(response({artworkIds: [id]}));
            await flush();
            requests[started + 1].resolve(response({success: true, artworkId: id, title: 'sample', totalImages: 2}));
            await pending;
        }
    };
}

test('活跃查询 HTTP 失败仍安排下一次轮询', async () => {
    const p = page(), pending = p.sync();
    p.requests[0].resolve({ok: false});
    await pending;
    assert.equal(p.timers.size, 1);
    [...p.timers.values()][0].fn();
    assert.equal(p.requests.length, 2);
    p.requests[1].resolve(response({artworkIds: []}));
    await flush();
});

test('前台事件与轮询共用一个在途查询，不重复添加活跃作品', async () => {
    const p = page(), pending = p.sync();
    p.events.get('visibilitychange')();
    p.events.get('visibilitychange')();
    assert.equal(p.requests.length, 1);
    p.requests[0].resolve(response({artworkIds: [42]}));
    await flush();
    p.requests[1].resolve(response({success: true, artworkId: 42}));
    await pending;
    assert.equal(p.count(), 1);
    assert.equal(p.streams.length, 1);
    assert.equal(p.timers.size, 1);
});

test('详情查询失败会取消同轮其余请求，下一轮可以继续同步', async () => {
    const p = page(), pending = p.sync();
    p.requests[0].resolve(response({artworkIds: [42, 43]}));
    await flush();
    p.requests[1].reject(new Error('network failed'));
    await pending;
    assert.equal(p.requests[2].options.signal.aborted, true);
    assert.equal(p.timers.size, 1);
    p.requests[2].resolve(response({artworkId: 43}));
    await flush();
    assert.equal(p.streams.length, 0);
    const recovered = p.sync();
    p.requests[3].resolve(response({artworkIds: []}));
    await recovered;
    assert.equal(p.count(), 0);
});

test('离页取消在途查询，迟到响应不重开连接；返回页面恢复查询', async () => {
    const p = page();
    await p.discover(42);
    const pending = p.sync();
    p.events.get('pagehide')({persisted: true});
    assert.equal(p.requests[2].options.signal.aborted, true);
    assert.equal(p.streams[0].closed, true);
    assert.equal(p.timers.size, 0);
    p.requests[2].resolve(response({artworkIds: [43]}));
    await pending;
    assert.equal(p.requests.length, 3);
    assert.equal(p.timers.size, 0);
    p.events.get('pageshow')({persisted: true});
    assert.equal(p.requests.length, 4);
    p.requests[3].resolve(response({artworkIds: []}));
    await flush();
    assert.equal(p.count(), 0);
    assert.equal(p.streams.at(-1).closed, true);
    p.events.get('pagehide')({persisted: true});
    assert.equal(p.timers.size, 0);
});

test('旧连接的迟到终态不移除重开后的作品，活动连接仍更新进度并关闭空列表', async () => {
    const p = page();
    await p.discover(42);
    const first = p.streams[0];
    first.send({artworkId: 42, completed: true});
    assert.equal(p.count(), 0);
    assert.equal(first.closed, true);
    await p.discover(42);
    first.send({artworkId: 42, completed: true});
    assert.equal(p.count(), 1);
    const current = p.streams[1];
    current.send({artworkId: 99, completed: true});
    current.send({artworkId: 42, downloadedCount: 1, totalImages: 2, progress: 50});
    assert.match(p.elements.get('activeDownloadsList').innerHTML, /50\.0%/);
    current.send({artworkId: 42, cancelled: true});
    assert.equal(p.count(), 0);
    assert.equal(current.closed, true);
});
