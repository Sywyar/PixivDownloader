'use strict';

const {test} = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const {resolve} = require('node:path');
const vm = require('node:vm');
const {MiniElement, MiniStorage} = require('./pixiv-layout-feedback-test-dom');

async function setup(mode = 'solo') {
    const elements = new Map(), timers = new Map(), requests = [], statuses = [], controls = [];
    const document = {addEventListener() {}, getElementById(id) {
        if (!elements.has(id)) {
            const element = new MiniElement('button', document);
            element.id = id;
            elements.set(id, element);
        }
        return elements.get(id);
    }};
    const context = vm.createContext({document, console, BASE: '', AbortController,
        PixivBatch: {layout: {applyStoredLayout() {}}, queueTypes: {bootstrap: async () => {}},
            modeControls: {bind() { controls.push('bind'); }, renderAll() { controls.push('render'); }}},
        PixivUserscriptDetection: {probe() {}}, SCRIPT_ID_TOOLBOX: 'fixture-toolbox', SCRIPT_ID_ALL_IN_ONE: 'fixture-all',
        localStorage: new MiniStorage(), addEventListener() {},
        stopSchedulePolling() {},
        setTimeout(fn) { const id = Symbol(); timers.set(id, fn); return id; },
        clearTimeout: id => timers.delete(id),
        fetch: async (url, options) => {
            requests.push({url, body: JSON.parse(options.body)});
            return {ok: true};
        },
        bt: key => key, resolveStatusMessage: message => message.key,
        uiConfirmKey: async () => true,
    });
    context.window = context;
    const run = code => vm.runInContext(code, context);
    const root = resolve(__dirname, '../../main/resources/static');
    const html = readFileSync(resolve(root, 'pixiv-batch.html'), 'utf8');
    const modules = new Set(['batch-download-defaults.js', 'batch-state.js', 'state-storage.js',
        'batch-storage.js', 'cookie-storage.js', 'cookie-sync.js', 'batch-cookie.js', 'batch-init.js']);
    for (const [, src] of html.matchAll(/<script\b[^>]*\bsrc="([^"]+)"/g)) {
        if (modules.has(src.split('/').pop())) {
            vm.runInContext(readFileSync(resolve(root, '.' + src), 'utf8'), context, {filename: src});
        }
    }
    run('appMode = ' + JSON.stringify(mode));
    const previous = 'PHPSESSID=previous';
    run('serverState = {pixiv_cookie: ' + JSON.stringify(previous) + '}');
    context.localStorage.setItem('pixiv_cookie', previous);
    // Cookie 事件由真实页面入口绑定；后续设置装配不属于此夹具。
    const settingsBoundary = new Error('settings fixture boundary');
    context.PixivBatch.settings = {bindDelegatedSettingAutosave(target) {
        assert.equal(target, document);
        throw settingsBoundary;
    }};
    for (const name of ['bindDeclarativeActions', 'detectMode', 'detectAuthState', 'loadServerState',
        'applyCookieHint', 'updateBatchLimitNote', 'updateAuthButtons', 'applyCookieDependentUi', 'initQuota']) {
        context[name] = () => {};
    }
    context.setCookieStatus = (message, tone) => statuses.push({message, tone});
    await assert.rejects(context.init(), error => error === settingsBoundary);
    assert.deepEqual(controls, ['bind', 'render']);
    const click = async id => {
        const node = document.getElementById(id);
        for (const listener of node.listeners.get('click') || []) await listener({target: node});
    };
    return {context, run, click, timers, requests, statuses, document,
        input: document.getElementById('cookie-input'), previous};
}

test('经典页等待 Cookie 写入成功才发布保存状态，保存期间拒绝重复操作', async () => {
    const h = await setup();
    let release;
    h.context.fetch = (url, options) => {
        h.requests.push({url, body: JSON.parse(options.body)});
        return new Promise(resolve => { release = resolve; });
    };
    h.input.value = 'PHPSESSID=new';
    const pending = h.click('cookie-save');
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(h.context.getCookie(), h.previous);
    assert.equal(h.statuses.length, 0);
    assert.equal(h.document.getElementById('cookie-save').disabled, true);
    await h.click('cookie-save');
    assert.equal(h.requests.length, 1);
    release({ok: true});
    await pending;
    assert.equal(h.context.getCookie(), 'PHPSESSID=new');
    assert.equal(h.statuses.at(-1).message.key, 'status.cookie-saved');
    assert.equal(h.document.getElementById('cookie-save').disabled, false);
});

test('经典页手动保存和清除取消旧导入，迟到正文不能改回旧凭据', async () => {
    for (const action of ['cookie-save', 'cookie-clear']) {
        const h = await setup();
        let poll, releaseBody, signal;
        h.context.setTimeout = (fn, delay) => { if (delay === 1500) poll = fn; return 1; };
        h.context.open = () => ({close() {}});
        h.context.fetch = async (_url, options) => {
            if (options.body) return {ok: true};
            signal = options.signal;
            return {ok: true, json: () => new Promise(resolve => { releaseBody = resolve; })};
        };
        h.context.runScriptCookieImport();
        const pending = poll();
        await new Promise(resolve => setImmediate(resolve));
        h.input.value = 'PHPSESSID=new-manual';
        await h.click(action);
        const expected = action === 'cookie-save' ? 'PHPSESSID=new-manual' : '';
        assert.equal(h.context.getCookie(), expected);
        assert.equal(signal.aborted, true);
        const status = h.statuses.at(-1);
        releaseBody({state: {pixiv_cookie: 'PHPSESSID=older-import', pixiv_cookie_fmt: 'header',
            pixiv_cookie_sync_at: 'new-sync', pixiv_cookie_sync_status: 'ok'}});
        await pending;
        assert.equal(h.context.getCookie(), expected);
        assert.equal(h.statuses.at(-1), status);
    }
});

test('经典页 Cookie 保存或清除失败保留原值和草稿，失败后可重试且不回显凭据', async () => {
    for (const mode of ['solo', 'multi']) {
        const h = await setup(mode);
        h.input.value = 'PHPSESSID=new';
        h.context.fetch = async () => { throw new Error('PHPSESSID=new'); };
        h.context.localStorage.throwOnSet = true;
        await h.click('cookie-save');
        assert.equal(h.context.getCookie(), h.previous);
        assert.equal(h.input.value, 'PHPSESSID=new');
        assert.equal(h.statuses.at(-1).tone, 'error');
        assert.equal(h.statuses.at(-1).message.key, 'status.cookie-persist-failed');
        assert.ok(!JSON.stringify(h.statuses).includes('PHPSESSID='));
        h.context.localStorage.throwOnRemove = true;
        await h.click('cookie-clear');
        assert.equal(h.context.getCookie(), h.previous);
        assert.equal(h.input.value, 'PHPSESSID=new');
        assert.equal(h.statuses.at(-1).tone, 'error');
        h.context.fetch = async () => ({ok: true});
        h.context.localStorage.throwOnSet = false;
        h.context.localStorage.throwOnRemove = false;
        await h.click('cookie-save');
        assert.equal(h.context.getCookie(), 'PHPSESSID=new');
        await h.click('cookie-clear');
        assert.equal(h.context.getCookie(), '');
        assert.equal(h.input.value, '');
        assert.equal(h.statuses.at(-1).message.key, 'status.cookie-cleared');
    }
});

test('HTTP 拒绝和浏览器存储读取失败都恢复操作按钮，不发布未保存的 Cookie', async () => {
    for (const mode of ['solo', 'multi']) {
        const h = await setup(mode);
        h.input.value = 'PHPSESSID=new';
        h.context.fetch = async () => ({ok: false, status: 403});
        const storage = h.context.localStorage;
        const getItem = storage.getItem.bind(storage);
        storage.getItem = key => {
            if (key === 'pixiv_cookie') throw new Error('read denied');
            return getItem(key);
        };
        await h.click('cookie-save');
        storage.getItem = getItem;
        assert.equal(h.context.getCookie(), h.previous);
        assert.equal(h.input.value, 'PHPSESSID=new');
        assert.equal(h.statuses.at(-1).message.key, 'status.cookie-persist-failed');
        assert.equal(h.document.getElementById('cookie-save').disabled, false);
        assert.equal(h.document.getElementById('cookie-clear').disabled, false);
    }
});
