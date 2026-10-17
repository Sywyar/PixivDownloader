'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { MiniElement } = require('../../../../pixivdownload-plugin-download-workbench/src/test/js/pixiv-layout-feedback-test-dom');

const flush = () => new Promise(setImmediate);

function trackingPage() {
    const requests = [], downloads = [], timers = new Map(), events = new Map();
    let timerId = 0;
    const window = {
        setInterval(fn, delay) { timers.set(++timerId, {fn, delay}); return timerId; },
        clearInterval(id) { timers.delete(id); },
        clearTimeout() {},
        addEventListener(type, callback) { events.set(type, callback); }
    };
    const document = {
        readyState: 'complete', getElementById() { return null; },
        body: {appendChild() {}},
        createElement() { return {click() { downloads.push(this.href); }, remove() {}}; }
    };
    vm.runInNewContext(fs.readFileSync(path.join(__dirname,
        '../../main/resources/static/js/pixiv-side-modules.js'), 'utf8'), {
        window, document, AbortController,
        fetch(url, options) { return new Promise((resolve, reject) => requests.push({url, options, resolve, reject})); }
    });
    return {window, requests, downloads, timers, events,
        poll: () => [...timers.values()].find(timer => timer.delay === 2000).fn(),
        list: () => [...timers.values()].find(timer => timer.delay === 3000).fn(),
        reply(request, status) { request.resolve({ok: true, json: async () => ({status})}); }
    };
}

test('慢归档查询不并行重叠，同一就绪回执只下载和通知一次', async () => {
    const page = trackingPage();
    let ready = 0;
    page.window.PixivSideModules.trackArchive('sample', {onReady() { ready++; }});
    page.poll();
    page.poll();
    for (const request of page.requests) page.reply(request, 'ready');
    await flush();
    assert.deepEqual({requests: page.requests.length, downloads: page.downloads.length, ready},
        {requests: 1, downloads: 1, ready: 1});
    assert.equal(page.timers.size, 0);
});

test('重新跟踪同一归档后，旧查询不消费新回调', async () => {
    const page = trackingPage();
    let oldReady = 0, newReady = 0;
    page.window.PixivSideModules.trackArchive('sample', {onReady() { oldReady++; }});
    page.poll();
    page.window.PixivSideModules.trackArchive('sample', {onReady() { newReady++; }});
    page.reply(page.requests[0], 'ready');
    await flush();
    assert.equal(oldReady, 0);
    assert.equal(page.downloads.length, 0);
    page.poll();
    page.reply(page.requests[1], 'ready');
    await flush();
    assert.equal(newReady, 1);
    assert.equal(page.downloads.length, 1);
});

test('离页中止归档查询和计时器，返回后保留跟踪且拒绝迟到下载', async () => {
    const page = trackingPage();
    page.window.PixivSideModules.trackArchive('sample');
    page.poll();
    page.events.get('pagehide')({persisted: true});
    assert.equal(page.requests[0].options.signal.aborted, true);
    assert.equal(page.timers.size, 0);
    page.reply(page.requests[0], 'ready');
    await flush();
    assert.equal(page.downloads.length, 0);
    page.events.get('pageshow')({persisted: true});
    page.poll();
    page.reply(page.requests[1], 'ready');
    await flush();
    assert.equal(page.downloads.length, 1);
    assert.equal(page.timers.size, 0);
});

test('列表查询不重叠，离页取消后可重新查询', async () => {
    const page = trackingPage();
    page.window.PixivSideModules.trackArchive('sample');
    page.list();
    page.list();
    assert.equal(page.requests.length, 1);
    page.events.get('pagehide')({persisted: true});
    assert.equal(page.requests[0].options.signal.aborted, true);
    page.events.get('pageshow')({persisted: true});
    page.list();
    page.requests[0].resolve({ok: true, json: async () => ({tasks: []})});
    await flush();
    page.list();
    assert.equal(page.requests.length, 2);
    page.requests[1].resolve({ok: true, json: async () => ({tasks: []})});
    await flush();
    page.list();
    assert.equal(page.requests.length, 3);
    page.events.get('pagehide')({persisted: false});
    assert.equal(page.requests[2].options.signal.aborted, true);
});

test('归档失败独立通知，网络失败和非成功响应留待下轮重试', async () => {
    const page = trackingPage(), failures = [];
    page.window.PixivSideModules.trackArchive('first', {onFailed(status) { failures.push(status); }});
    page.window.PixivSideModules.trackArchive('second', {onReady() { throw new Error('consumer failure'); }});
    page.poll();
    page.requests[0].reject(new Error('network failure'));
    await flush();
    page.requests[1].resolve({ok: false});
    await flush();
    assert.equal(page.timers.size, 2);
    page.poll();
    page.reply(page.requests[2], 'empty');
    await flush();
    page.reply(page.requests[3], 'ready');
    await flush();
    assert.deepEqual(failures, ['empty']);
    assert.equal(page.downloads.length, 1);
    assert.equal(page.timers.size, 0);
});

test('side module labels follow the latest language even when translations arrive out of order', async () => {
    const sidebar = new MiniElement('aside');
    let tabs;
    sidebar.insertAdjacentElement = (_, element) => { tabs = element; };
    const pending = [];
    const disposed = [];
    let onLanguageChange;
    const window = {
        addEventListener() {},
        PixivI18n: {
            create(options) { return new Promise(resolve => pending.push({ options, resolve })); },
            onLanguageChange(callback) { onLanguageChange = callback; }
        }
    };
    const document = {
        readyState: 'complete', body: new MiniElement('body'),
        getElementById: id => id === 'sidebar' ? sidebar : null,
        createElement: tag => {
            const element = new MiniElement(tag);
            Object.defineProperty(element, 'innerHTML', { set() { element.children = []; } });
            return element;
        }
    };
    vm.runInNewContext(fs.readFileSync(path.join(__dirname,
        '../../main/resources/static/js/pixiv-side-modules.js'), 'utf8'), {
        window, document, MutationObserver: class { observe() {} }
    });
    const initial = pending.shift();
    onLanguageChange({ lang: 'en-US' });
    pending.shift().resolve({ t: key => 'en:' + key, dispose() { disposed.push('en'); } });
    await new Promise(setImmediate);
    const labels = () => tabs.children.map(tab => tab.children[0].textContent);
    assert.deepEqual(labels(), ['en:common:side-modules.nav', 'en:common:side-modules.tasks']);
    initial.resolve({ t: key => 'old:' + key, dispose() { disposed.push('old'); } });
    await new Promise(setImmediate);
    assert.deepEqual(labels(), ['en:common:side-modules.nav', 'en:common:side-modules.tasks']);
    assert.deepEqual(disposed, ['old']);
    onLanguageChange({lang: 'ja-JP'});
    pending.shift().resolve({t: key => 'ja:' + key});
    await flush();
    assert.deepEqual(disposed, ['old', 'en']);
    assert.deepEqual(labels(), ['ja:common:side-modules.nav', 'ja:common:side-modules.tasks']);
});
