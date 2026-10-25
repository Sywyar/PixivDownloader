'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.resolve(__dirname, '../../../..');
const flush = () => new Promise(resolve => setImmediate(resolve));
const scripts = [
    ['Pixiv User 批量下载器(User Batch).user.js', 'pixiv', false],
    ['Pixiv 页面批量下载器(Page Scrape).user.js', 'pixiv-page', false],
    ['Pixiv URL 批量导入单作品下载器(URL Batch).user.js', 'pixiv-single-import', false],
    ['Pixiv 单作品图片下载器(Java后端版).user.js', 'pixiv-single', true]
];

function runtime(name, prefix, single) {
    const source = fs.readFileSync(path.join(root, name), 'utf8');
    const timers = new Map(), requests = [], listeners = new Map(), nodes = new Map();
    let sequence = 0, now = 0, failNextRequest = false;
    function node(id) {
        if (!nodes.has(id)) nodes.set(id, {style: {}, textContent: '', innerHTML: ''});
        return nodes.get(id);
    }
    function timer(callback, delay, repeat) {
        timers.set(++sequence, {callback, delay, repeat, at: now + delay});
        return sequence;
    }
    const window = {
        addEventListener(type, callback) {
            if (!listeners.has(type)) listeners.set(type, []);
            listeners.get(type).push(callback);
        }
    };
    const base = 'http://localhost:6999';
    function request(options) {
        if (failNextRequest) {
            failNextRequest = false;
            throw new Error('request unavailable');
        }
        const item = {options, aborted: false};
        requests.push(item);
        return {abort() {
            item.aborted = true;
            if (item.abortError) throw new Error('abort unavailable');
            options.onabort?.();
        }};
    }
    const context = vm.createContext({
        window, document: {getElementById: node}, console,
        Date: class extends Date { static now() { return now; } },
        setTimeout: (callback, delay) => timer(callback, delay, false),
        setInterval: (callback, delay) => timer(callback, delay, true),
        clearTimeout: id => timers.delete(id), clearInterval: id => timers.delete(id),
        GM_xmlhttpRequest: request, serverBase: base,
        t: (key, fallback, args) => args?.time || key,
        translateStatusText: value => value
    });
    const shared = source.match(/\/\/ >>> SHARED:archive-monitor\.js([\s\S]*?)\/\/ <<< SHARED:archive-monitor\.js/);
    assert.ok(shared, name);
    vm.runInContext(shared[1], context);
    if (single) {
        const start = source.indexOf('    function renderSingleArchiveCard(');
        const end = source.indexOf('    function showQuotaExceededUI(', start);
        assert.ok(start >= 0 && end > start);
        const monitor = source.match(/    const singleArchiveMonitor = [^;]+;/);
        assert.ok(monitor, name);
        vm.runInContext(monitor[0], context);
        vm.runInContext(source.slice(start, end) + '\nthis.show = renderSingleArchiveCard;', context);
    } else {
        const declaration = source.match(/    class UI \{[\s\S]*?\n    \}/);
        assert.ok(declaration, name);
        vm.runInContext(declaration[0] + '\nUI.prototype._build = () => {}; this.ui = new UI();', context);
        context.show = (...args) => context.ui.restoreArchiveCard(...args);
    }
    return {
        show: (...args) => context.show(...args), requests, timers,
        node: suffix => node(prefix + '-ac-' + suffix),
        async tick(milliseconds) {
            const end = now + milliseconds;
            while (true) {
                const due = [...timers].filter(([, value]) => value.at <= end)
                    .sort((a, b) => a[1].at - b[1].at)[0];
                if (!due) break;
                now = due[1].at;
                if (due[1].repeat) due[1].at += due[1].delay;
                else timers.delete(due[0]);
                due[1].callback();
                await flush();
            }
            now = end;
        },
        async respond(index, data, status = 200) {
            requests[index].options.onload({status, responseText: typeof data === 'string' ? data : JSON.stringify(data)});
            await flush();
        },
        setBase(value) { context.serverBase = value; },
        failNextRequest() { failNextRequest = true; },
        event(type) { for (const callback of listeners.get(type) || []) callback({persisted: true}); }
    };
}

for (const [name, prefix, single] of scripts) {
    test(`${name}：新归档显示后，旧请求不能恢复旧下载链接`, async () => {
        const r = runtime(name, prefix, single);
        r.show('old-token', 60, false);
        await r.tick(2000);
        r.show('new-token', 120, true);
        await r.respond(0, {status: 'ready', expireSeconds: 60});
        assert.match(r.node('dl').innerHTML, /new-token/);
        assert.doesNotMatch(r.node('dl').innerHTML, /old-token/);
    });

    test(`${name}：慢查询不重叠，离页后停止并隔离迟到响应`, async () => {
        const r = runtime(name, prefix, single);
        r.show('token', 60, false);
        await r.tick(6000);
        assert.equal(r.requests.length, 1);
        r.event('pagehide');
        assert.equal(r.requests[0].aborted, true);
        await r.respond(0, {status: 'ready', expireSeconds: 60});
        assert.equal(r.timers.size, 0);
        assert.doesNotMatch(r.node('dl').innerHTML, /api\/archive\/download/);
        r.event('pageshow');
        await r.tick(2000);
        assert.equal(r.requests.length, 2);
    });

    test(`${name}：重建同一归档卡片保留剩余期限`, async () => {
        const r = runtime(name, prefix, single);
        r.show('token', 10, true);
        await r.tick(6000);
        r.show('token', 10, true);
        await r.tick(4000);
        assert.equal(r.node('expired').style.display, 'block');
        assert.equal(r.node('dl').style.display, 'none');
        assert.equal(r.timers.size, 0);
    });

    test(`${name}：失败按原间隔重试，终态停止查询且零期限立即失效`, async () => {
        const r = runtime(name, prefix, single);
        r.show('token', 60, false);
        await r.tick(2000);
        assert.equal(r.requests[0].options.timeout, 10000);
        await r.respond(0, {status: 'ready'}, 500);
        await r.tick(1999);
        assert.equal(r.requests.length, 1);
        await r.tick(1);
        r.requests[1].options.ontimeout();
        await r.tick(2000);
        await r.respond(2, 'invalid json');
        await r.tick(2000);
        await r.respond(3, {status: 'ready', expireSeconds: 0});
        assert.equal(r.node('expired').style.display, 'block');
        assert.equal(r.timers.size, 0);
        r.show('empty-token', 60, false);
        await r.tick(2000);
        await r.respond(4, {status: 'empty'});
        assert.equal(r.node('status').textContent, 'common.archive.empty');
        assert.equal(r.timers.size, 0);
    });

    test(`${name}：页面暂停不延长期限，服务器变化后不交付旧结果`, async () => {
        const r = runtime(name, prefix, single);
        r.show('token', 5, true);
        r.event('pagehide');
        await r.tick(6000);
        r.event('pageshow');
        assert.equal(r.node('expired').style.display, 'block');
        assert.equal(r.timers.size, 0);
        r.show('next', 60, false);
        await r.tick(2000);
        r.setBase('http://localhost:7000');
        await r.respond(0, {status: 'ready'});
        assert.doesNotMatch(r.node('dl').innerHTML, /download\/next/);
        assert.equal(r.timers.size, 0);
    });

    test(`${name}：请求与中止异常后仍可恢复，同一归档就绪时拒绝旧结果`, async () => {
        const r = runtime(name, prefix, single);
        r.failNextRequest();
        r.show('token', 60, false);
        await r.tick(2000);
        assert.equal(r.requests.length, 0);
        await r.tick(2000);
        assert.equal(r.requests.length, 1);
        r.requests[0].abortError = true;
        r.show('token', 5, true);
        assert.equal(r.requests[0].aborted, true);
        await r.respond(0, {status: 'expired'});
        assert.equal(r.node('dl').style.display, 'block');
        await r.tick(5000);
        assert.equal(r.node('expired').style.display, 'block');
        assert.equal(r.timers.size, 0);
    });
}
