'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const test = require('node:test');

const flush = () => new Promise(resolve => setImmediate(resolve));
const running = {id: 'original', pluginId: 'sample', version: '2.3.4', repositoryId: 'official',
    createdAt: '2025-01-02T10:20:30Z', started: true, finished: false, operation: 'DOWNLOADING'};

function fixture() {
    const events = {}, pageEvents = {}, timers = new Map(), pending = [];
    let timerId = 0, requests = 0, language = 'en-US';
    const document = {hidden: false, activeElement: null, addEventListener(type, fn) { events[type] = fn; }};
    function node(tag) {
        return {
            tag, children: [], attrs: {}, events: {}, parentNode: null, value: '',
            appendChild(child) { return this.insertBefore(child, null); },
            insertBefore(child, before) {
                child.remove();
                this.children.splice(before ? this.children.indexOf(before) : this.children.length, 0, child);
                child.parentNode = this;
                return child;
            },
            remove() {
                if (this.parentNode) this.parentNode.children.splice(this.parentNode.children.indexOf(this), 1);
                this.parentNode = null;
            },
            setAttribute(key, value) { this.attrs[key] = value; },
            addEventListener(type, fn) { this.events[type] = fn; },
            focus() { document.activeElement = this; },
            get textContent() { return this.value + this.children.map(child => child.textContent).join(''); },
            set textContent(value) { this.value = value; this.children = []; }
        };
    }
    document.createElement = node;
    const market = {currentLang: () => language, t: (key, fallback, values) =>
        language + ':' + (fallback || key) + ' ' + JSON.stringify(values || {}),
        api: {fetchOperations() {
            requests++;
            return new Promise((resolve, reject) => pending.push({resolve, reject}));
        }}};
    const context = {document, window: {PixivPluginMarket: market,
        addEventListener(type, fn) { pageEvents[type] = fn; }},
        setTimeout(fn, delay) { assert.equal(delay, 2000); timers.set(++timerId, fn); return timerId; },
        clearTimeout(id) { timers.delete(id); }};
    for (const name of ['data', 'operations'])
        vm.runInNewContext(fs.readFileSync(path.join(__dirname,
            '../../main/resources/static/plugin-market/plugin-market-' + name + '.js'), 'utf8'), context);
    market.operations.mount();
    const trigger = node('span'), host = node('div');
    market.operations.mountButton(trigger);
    market.operations.mountPanel(host);
    function find(root, cls) {
        if ((root.className || '').split(' ').includes(cls)) return root;
        return root.children.map(child => find(child, cls)).find(Boolean);
    }
    return {market, document, events, pageEvents, timers, pending, trigger, host, find,
        get requests() { return requests; }, language(value) { language = value; market.operations.render(); }};
}

test('安装记录按需展开，保留行、焦点与诊断展开状态，并区分真实终态', async () => {
    const f = fixture(), button = f.trigger.children[0];
    assert.equal(f.host.hidden, true);
    assert.match(f.host.textContent, /loading/);
    f.pending.shift().resolve([]); await flush();
    assert.equal(f.host.hidden, true);
    button.events.click();
    assert.equal(f.host.hidden, false);
    assert.equal(button.attrs['aria-expanded'], 'true');
    assert.match(f.host.textContent, /operations.empty/);
    const variants = [
        {activated: true, accepted: true}, {accepted: true, effectiveAfterRestart: true},
        {rolledBack: true}, {recoveryBlocked: true, activated: true}, {accepted: true}, {}
    ];
    f.pending.shift().resolve([running, ...variants.map((result, i) => ({
        ...running, id: 'done-' + i, finished: true, result
    }))]); await flush();
    const list = f.find(f.host, 'pmk-operation-list'), row = list.children[0];
    const states = list.children.map(child => f.find(child, 'pmk-operation-status'));
    assert.deepEqual(states.map(n => n.textContent.trim()), [
        'en-US:DOWNLOADING {}', 'en-US:activated {}', 'en-US:pending-restart {}',
        'en-US:rolled-back {}', 'en-US:recovery-blocked {}', 'en-US:accepted {}', 'en-US:FAILED {}'
    ]);
    assert.match(states[4].className, /--bad/);
    const details = f.find(row, 'pmk-operation-details');
    details.open = true;
    details.children[0].focus();
    f.market.operations.refresh();
    f.pending.shift().reject(new Error('offline')); await flush();
    assert.equal(list.children[0], row);
    assert.equal(details.open, true);
    assert.equal(f.document.activeElement, details.children[0]);
    assert.match(f.find(f.host, 'pmk-operation-notice').textContent, /operations.query-failed/);
    assert.equal(f.find(f.host, 'pmk-operation-notice').attrs.role, 'alert');
    f.market.operations.refresh();
    f.pending.shift().resolve([{...running, finished: true, transactionId: 'parent-transaction',
        failure: {error: '<img src=x onerror=alert(1)>', dependencyInstallResults: [
            {pluginId: 'dependency', version: '2.3.4', accepted: true, transactionId: 'dependency-transaction'}
        ]}}]); await flush();
    assert.equal(list.children.length, 1);
    assert.equal(list.children[0], row);
    assert.match(row.textContent, /parent-transaction/);
    assert.match(row.textContent, /dependency-transaction/);
    assert.match(row.textContent, /<img src=x onerror=alert\(1\)>/);
    assert.equal(f.find(row, 'pmk-operation-message').children.length, 0);
    f.language('ja-JP');
    assert.equal(list.children[0], row);
    assert.equal(details.open, true);
    assert.match(row.textContent, /ja-JP/);
    const panel = f.host.children[0];
    panel.events.keydown({key: 'Escape', stopPropagation() {}});
    assert.equal(f.host.hidden, true);
    assert.equal(f.document.activeElement, button);
    assert.equal(f.timers.size, 0);
});

test('离页丢弃迟到响应，返回查询原操作；安装结束时合并在途刷新且不并发', async () => {
    const f = fixture();
    const phases = [];
    f.market.operations.watch('original', phase => phases.push(phase));
    assert.equal(f.requests, 1);
    f.pending.shift().resolve([running]); await flush();
    assert.equal(f.requests, 2);
    assert.deepEqual(phases, ['DOWNLOADING']);
    f.pending.shift().resolve([running]); await flush();
    assert.equal(f.timers.size, 1);
    f.document.hidden = true; f.events.visibilitychange();
    assert.equal(f.timers.size, 0);
    await f.market.operations.refresh(); assert.equal(f.requests, 2);
    f.document.hidden = false; f.events.visibilitychange();
    assert.equal(f.requests, 3);
    const phaseCount = phases.length;
    f.pageEvents.pagehide();
    f.pending.shift().resolve([{...running, pluginId: 'stale-response'}]); await flush();
    assert.doesNotMatch(f.host.textContent, /stale-response/);
    assert.equal(f.timers.size, 0);
    assert.equal(phases.length, phaseCount);
    f.pageEvents.pageshow(); assert.equal(f.requests, 4);
    f.market.operations.settled('original');
    assert.equal(f.pending.length, 1);
    f.pending.shift().resolve([running]); await flush();
    assert.equal(f.requests, 5);
    f.pending.shift().resolve([{...running, finished: true, result: {accepted: true}}]); await flush();
    assert.equal(f.timers.size, 0);
    assert.equal(phases.length, phaseCount);
    f.market.operations.mount();
    assert.equal(f.requests, 5);
});
