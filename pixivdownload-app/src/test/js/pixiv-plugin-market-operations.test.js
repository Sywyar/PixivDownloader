'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const test = require('node:test');

test('操作记录离页停止轮询，返回只读查询且最多一个请求', async () => {
    const events = {}, pageEvents = {}, timers = new Map();
    const list = { children: [], appendChild(node) { this.children.push(node); },
        set textContent(value) { this.children = []; } };
    const button = { addEventListener(type, fn) { events.click = fn; } };
    const document = { hidden: false, getElementById: id => id === 'pmk-operation-list' ? list : button,
        createElement: () => ({}), addEventListener(type, fn) { events[type] = fn; } };
    let requests = 0, resolveQuery, timerId = 0;
    const PMK = { t: (key, fallback, values) => key + ' ' + JSON.stringify(values || {}),
        api: { fetchOperations() { requests++; return new Promise(resolve => { resolveQuery = resolve; }); } } };
    const context = { document, window: { PixivPluginMarket: PMK, addEventListener(type, fn) { pageEvents[type] = fn; } },
        setTimeout(fn) { timers.set(++timerId, fn); return timerId; }, clearTimeout(id) { timers.delete(id); } };
    vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../../main/resources/static/plugin-market/plugin-market-operations.js'), 'utf8'), context);
    const flush = () => new Promise(resolve => setImmediate(resolve));
    const running = { id: 'original', pluginId: 'sample', version: '1.0.0', repositoryId: 'official',
        started: true, finished: false, operation: 'DOWNLOADING' };
    PMK.operations.mount();
    PMK.operations.refresh();
    assert.equal(requests, 1);
    resolveQuery([running]); await flush();
    assert.equal(timers.size, 1);
    document.hidden = true; events.visibilitychange();
    assert.equal(timers.size, 0);
    await PMK.operations.refresh(); assert.equal(requests, 1);
    document.hidden = false; events.visibilitychange();
    assert.equal(requests, 2);
    pageEvents.pagehide();
    resolveQuery([running]); await flush();
    assert.equal(timers.size, 0);
    pageEvents.pageshow(); assert.equal(requests, 3);
    resolveQuery([{...running, finished: true, operation: 'FAILED', transactionId: 'parent-transaction',
        failure: {error: 'download failed', dependencyInstallResults: [{pluginId: 'dependency', version: '1.0.0', transactionId: 'dependency-transaction'}]}}]);
    await flush();
    assert.equal(timers.size, 0);
    assert.match(list.children[0].textContent, /parent-transaction/);
    assert.match(list.children[0].textContent, /dependency-transaction/);
    assert.match(list.children[0].textContent, /download failed/);
    assert.equal(button.disabled, false);
});
