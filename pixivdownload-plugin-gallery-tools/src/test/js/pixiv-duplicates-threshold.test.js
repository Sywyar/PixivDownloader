const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname,
    '../../main/resources/static/pixiv-duplicates/pixiv-duplicates.js'), 'utf8');
const storeKeys = ['pixiv_duplicates_dhash_threshold', 'pixiv_duplicates_ahash_threshold'];

async function openPage(raw, storageFails = false, handleFetch) {
    const controls = Object.fromEntries(['thresholdRange', 'thresholdInput', 'ahashRange', 'ahashInput',
        'statusBanner', 'resultSummary', 'groupsGrid', 'scanStatus', 'scanProgress', 'scanButton',
        'rescanButton', 'prevPage', 'nextPage', 'pager', 'pagerInfo'].map(id =>
        [id, { value: '', hidden: false, disabled: false, textContent: '', style: {}, attributes: {}, listeners: {},
            classList: { add() {}, remove() {}, toggle() {} },
            setAttribute(key, value) { this.attributes[key] = value; },
            removeAttribute(key) { delete this.attributes[key]; },
            querySelectorAll() { return []; },
            addEventListener(event, callback) { this.listeners[event] = callback; } }]));
    const requests = [];
    const calls = [];
    const writes = [];
    const events = {};
    const intervals = new Map();
    const timeouts = new Map();
    let timerId = 0;
    const location = { href: '', pathname: '/pixiv-duplicates.html', search: '' };
    let ready;
    vm.runInNewContext(source, {
        URLSearchParams, AbortController, location,
        document: {
            getElementById: id => controls[id] || null,
            querySelectorAll: () => [],
            addEventListener(event, callback) { if (event === 'DOMContentLoaded') ready = callback; }
        },
        window: { location, addEventListener(event, callback) { events[event] = callback; },
            setInterval(callback) { intervals.set(++timerId, callback); return timerId; },
            clearInterval(id) { intervals.delete(id); },
            setTimeout(callback) { timeouts.set(++timerId, callback); return timerId; },
            clearTimeout(id) { timeouts.delete(id); },
            localStorage: {
            getItem(key) {
                assert.ok(storeKeys.includes(key));
                if (storageFails) throw new Error('storage unavailable');
                return raw;
            },
            setItem(key, value) {
                if (storageFails) throw new Error('storage unavailable');
                writes.push([key, value]);
            }
        } },
        PixivI18n: { create: async () => ({ t: (key, fallback, vars) => key + JSON.stringify(vars || {}), apply() {} }) },
        PixivLangSwitcher: { mount: async () => {} },
        PixivTheme: { mount() {} },
        fetch: async (url, options) => {
            requests.push(new URL(url, 'http://test.invalid'));
            calls.push({ url, options });
            if (handleFetch) return handleFetch(url, options);
            return response(groups(0));
        }
    });
    await ready();
    return { controls, requests, calls, writes, events, intervals, timeouts, location };
}

const tick = () => new Promise(resolve => setImmediate(resolve));
function deferred() {
    let resolve, reject;
    const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
    return { promise, resolve, reject };
}
function response(payload, status = 200) {
    return { ok: status >= 200 && status < 300, status, json: async () => payload };
}
function groups(total, scan = 'IDLE') {
    return { page: 0, size: 20, totalGroups: total, groups: [], scan: { state: scan, processed: 1, total: 2 } };
}
function change(page, value) {
    page.controls.thresholdInput.value = String(value);
    page.controls.thresholdInput.listeners.change();
}

test('页面恢复两种阈值时保留空值回退、数值舍入与边界', async () => {
    for (const [raw, expected] of [[null, 0], ['', 0], ['bad', 0], ['Infinity', 0], ['-1', 0],
        ['0', 0], ['3.49', 3], ['3.5', 4], ['32', 32], ['99', 32]]) {
        const { controls, requests, writes } = await openPage(raw);
        assert.equal(requests.length, 1);
        for (const key of ['threshold', 'ahashThreshold']) {
            assert.equal(requests[0].searchParams.get(key), String(expected), String(raw));
        }
        for (const id of ['thresholdRange', 'thresholdInput', 'ahashRange', 'ahashInput']) {
            assert.equal(controls[id].value, String(expected));
        }
        assert.deepEqual(writes, []);
    }
});

test('滑动预览不保存或查询，确认后保存并查询规范化值', async () => {
    const { controls, requests, writes } = await openPage('7');
    controls.thresholdRange.value = '3.5';
    controls.thresholdRange.listeners.input();
    assert.equal(controls.thresholdInput.value, '4');
    assert.equal(requests.length, 1);
    assert.deepEqual(writes, []);
    controls.thresholdRange.listeners.change();
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(requests.at(-1).searchParams.get('threshold'), '4');
    controls.ahashInput.value = 'invalid';
    controls.ahashInput.listeners.change();
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(controls.ahashInput.value, '7');
    assert.equal(controls.ahashRange.value, '7');
    assert.equal(requests.at(-1).searchParams.get('ahashThreshold'), '7');
    assert.deepEqual(writes, [[storeKeys[0], '4'], [storeKeys[1], '7']]);
});

test('存储不可用时仍以回退阈值启动并允许本次页面调整', async () => {
    const { controls, requests, writes } = await openPage('9', true);
    assert.equal(requests[0].searchParams.get('threshold'), '0');
    assert.equal(requests[0].searchParams.get('ahashThreshold'), '0');
    controls.thresholdInput.value = '40';
    controls.thresholdInput.listeners.change();
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(controls.thresholdInput.value, '32');
    assert.equal(controls.thresholdRange.value, '32');
    assert.equal(requests.at(-1).searchParams.get('threshold'), '32');
    assert.deepEqual(writes, []);
});

test('旧筛选的迟到正文和失败不覆盖当前列表或扫描状态', async () => {
    const body = deferred(), failure = deferred();
    const page = await openPage(null, false, url => {
        const threshold = new URL(url, 'http://test.invalid').searchParams.get('threshold');
        if (threshold === '1') return { ok: true, status: 200, json: () => body.promise };
        if (threshold === '3') return failure.promise;
        return response(groups(Number(threshold)));
    });
    change(page, 1); await tick();
    change(page, 2); await tick();
    body.resolve(groups(9, 'RUNNING')); await tick();
    assert.match(page.controls.resultSummary.textContent, /"total":2/);
    assert.equal(page.intervals.size, 0);
    assert.equal(page.calls[1].options.signal.aborted, true);
    change(page, 3); change(page, 4); await tick();
    failure.reject(new Error('old failure')); await tick();
    assert.match(page.controls.resultSummary.textContent, /"total":4/);
    assert.equal(page.controls.statusBanner.attributes['data-i18n'], 'status.empty');
});

test('被替代的未认证响应不跳转，当前未认证响应仍跳转登录', async () => {
    const old = deferred();
    const page = await openPage(null, false, url => {
        const threshold = new URL(url, 'http://test.invalid').searchParams.get('threshold');
        return threshold === '1' ? old.promise : response(groups(0), threshold === '3' ? 401 : 200);
    });
    change(page, 1); change(page, 2); await tick();
    old.resolve(response({}, 401)); await tick();
    assert.equal(page.location.href, '');
    change(page, 3); await tick();
    assert.equal(page.location.href, '/login.html?redirect=%2Fpixiv-duplicates.html');
});

test('扫描读取保持单个在途，筛选替换后旧终态不停止新轮询', async () => {
    const status = deferred();
    const page = await openPage(null, false, url => url.endsWith('/status') ? status.promise : response(groups(2, 'RUNNING')));
    const poll = [...page.intervals.values()][0];
    poll(); poll(); await tick();
    assert.equal(page.requests.filter(url => url.pathname.endsWith('/status')).length, 1);
    const oldCall = page.calls.at(-1);
    change(page, 3); await tick();
    status.resolve(response({ state: 'DONE' })); await tick();
    assert.equal(oldCall.options.signal.aborted, true);
    assert.equal(page.intervals.size, 1);
    assert.match(page.controls.scanStatus.textContent, /^scan.running/);
    assert.equal(page.requests.length, 3);
});

test('离页取消读请求和计时器，恢复按原筛选重查且不重发写入', async () => {
    const status = deferred();
    const page = await openPage('7', false, url => url.endsWith('/status') ? status.promise : response(groups(1, 'RUNNING')));
    [...page.intervals.values()][0](); await tick();
    const read = page.calls.at(-1);
    page.events.pagehide();
    assert.equal(page.intervals.size, 0);
    assert.equal(page.timeouts.size, 0);
    assert.equal(read.options.signal.aborted, true);
    status.resolve(response({ state: 'DONE' })); await tick();
    assert.equal(page.calls.length, 2);
    page.events.pageshow(); await tick();
    assert.equal(page.calls.length, 3);
    assert.equal(page.requests.at(-1).searchParams.get('threshold'), '7');
    assert.equal(page.intervals.size, 1);
    page.events.pageshow(); await tick();
    assert.equal(page.calls.length, 3);
    assert.ok(page.calls.every(call => call.options.method !== 'POST'));
});

test('重新分组的写入保留，成功后刷新当前筛选并释放提示计时器', async () => {
    const write = deferred();
    const page = await openPage(null, false, (url, options) => options.method === 'POST'
        ? write.promise : response(groups(Number(new URL(url, 'http://test.invalid').searchParams.get('threshold')))));
    const pending = page.controls.rescanButton.listeners.click();
    page.controls.rescanButton.listeners.click();
    change(page, 2); await tick();
    assert.equal(page.calls.filter(call => call.options.method === 'POST').length, 1);
    assert.equal(page.calls.find(call => call.options.method === 'POST').options.signal, undefined);
    write.resolve(response(groups(99, 'RUNNING'))); await pending;
    assert.match(page.controls.resultSummary.textContent, /"total":2/);
    assert.equal(page.controls.rescanButton.disabled, false);
    assert.equal(page.intervals.size, 0);
    assert.equal(page.timeouts.size, 1);
    page.events.pagehide();
    assert.equal(page.timeouts.size, 0);
});

test('扫描写入离页后完成不更新旧页面，恢复读取真实终态', async () => {
    const write = deferred();
    const page = await openPage(null, false, (url, options) => options.method === 'POST' ? write.promise : response(groups(0, 'DONE')));
    const pending = page.controls.scanButton.listeners.click();
    page.controls.scanButton.listeners.click();
    page.events.pagehide();
    write.resolve(response({ state: 'RUNNING' })); await pending;
    assert.equal(page.intervals.size, 0);
    assert.equal(page.calls.length, 2);
    assert.equal(page.controls.scanButton.disabled, false);
    page.events.pageshow(); await tick();
    assert.equal(page.calls.length, 3);
    assert.equal(page.intervals.size, 0);
    assert.match(page.controls.scanStatus.textContent, /^scan.done/);
});

test('页面恢复时尚未完成的写入只刷新一次当前结果，不重发命令', async () => {
    const write = deferred();
    const page = await openPage(null, false, (url, options) => options.method === 'POST' ? write.promise : response(groups(2)));
    const pending = page.controls.rescanButton.listeners.click();
    page.events.pagehide();
    page.events.pageshow(); await tick();
    assert.equal(page.controls.rescanButton.disabled, true);
    const before = page.calls.length;
    write.resolve(response(groups(99))); await pending;
    assert.equal(page.calls.length, before + 1);
    assert.equal(page.calls.filter(call => call.options.method === 'POST').length, 1);
    assert.match(page.controls.resultSummary.textContent, /"total":2/);
    assert.equal(page.controls.rescanButton.disabled, false);
});

test('扫描终态刷新分组，当前状态读取失败停止轮询', async () => {
    let mode = 'running';
    const page = await openPage(null, false, url => url.endsWith('/status')
        ? mode === 'failed' ? Promise.reject(new Error('status failed')) : response({state: 'DONE'})
        : response(groups(2, mode === 'done' ? 'DONE' : 'RUNNING')));
    mode = 'done';
    await [...page.intervals.values()][0]();
    assert.equal(page.intervals.size, 0);
    assert.equal(page.calls.filter(call => call.url.includes('/groups?')).length, 2);
    assert.match(page.controls.scanStatus.textContent, /^scan.done/);
    mode = 'running';change(page, 2);await tick();
    mode = 'failed';await [...page.intervals.values()][0]();
    assert.equal(page.intervals.size, 0);
});

test('当前列表和写入失败保留错误提示并允许重试', async () => {
    let fail = false;
    const page = await openPage(null, false, () => fail ? response({error: 'failed'}, 500) : response(groups(0)));
    fail = true;
    change(page, 1); await tick();
    assert.equal(page.controls.statusBanner.attributes['data-i18n'], 'status.error');
    await page.controls.scanButton.listeners.click();
    assert.equal(page.controls.statusBanner.attributes['data-i18n'], 'status.scan-error');
    assert.equal(page.controls.scanButton.disabled, false);
    await page.controls.rescanButton.listeners.click();
    assert.equal(page.controls.statusBanner.attributes['data-i18n'], 'status.error');
    assert.equal(page.controls.rescanButton.disabled, false);
    fail = false;
    await page.controls.scanButton.listeners.click();
    assert.equal(page.controls.statusBanner.attributes['data-i18n'], 'status.empty');
});
