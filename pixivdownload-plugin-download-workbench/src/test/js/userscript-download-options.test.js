'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const test = require('node:test');
const {MiniElement} = require('./pixiv-layout-feedback-test-dom');
const shared = path.resolve(__dirname, '../../../../scripts/shared');

function runtime() {
    let saved = {};
    let response = () => ({status: 200, data: {collections: [{id: 7, name: '<collection>'}]}});
    const requests = [], events = {}, timers = new Map(), dictionaries = {};
    let timerId = 0;
    const context = vm.createContext({
        console, serverBase: 'http://localhost:6999', userUUID: 'owner',
        USERSCRIPT_DOWNLOAD_OPTIONS_KEY: 'test-options',
        GM_getValue: () => saved,
        GM_xmlhttpRequest(request) {
            requests.push(request);
            Promise.resolve().then(() => response(request)).then(result => {
                request.onload({status: result.status, responseText: JSON.stringify(result.data)});
            }, () => request.onerror());
        },
        PixivUserscriptI18n: {
            register: value => Object.assign(dictionaries, value),
            t: (key, fallback, args) => (dictionaries['en-US'][key] || fallback || key)
                .replace(/\{(\w+)\}/g, (match, name) => args?.[name] ?? match)
        },
        window: {addEventListener: (name, fn) => { events[name] = fn; }},
        setTimeout: fn => { timers.set(++timerId, fn); return timerId; },
        clearTimeout: id => timers.delete(id)
    });
    for (const name of ['download-options-i18n', 'download-options', 'novel-translate-monitor', 'download-queue-runner']) {
        vm.runInContext(fs.readFileSync(path.join(shared, name + '.js'), 'utf8'), context);
    }
    vm.runInContext('globalThis.options = UserscriptDownloadOptions; globalThis.translation = UserscriptNovelTranslation;', context);
    return {context, requests, events, dictionaries, timers,
        settings: value => { saved = value; },
        respond: fn => { response = fn; },
        async tick() {
            const [id, fn] = timers.entries().next().value;
            timers.delete(id);
            await fn();
        }
    };
}

function settingsPanel(r) {
    if (!r.context.document) {
        const document = {};
        document.createElement = tag => {
            const element = new MiniElement(tag, document);
            element.append = (...children) => children.forEach(child => element.appendChild(child));
            element.replaceChildren = (...children) => {
                element.children.slice().forEach(child => element.removeChild(child));
                element.append(...children);
            };
            Object.defineProperties(element, {
                options: {get: () => element.children},
                isConnected: {get: () => document.body.contains(element)}
            });
            return element;
        };
        document.body = document.createElement('body');
        document.head = document.createElement('head');
        document.getElementById = id => document.head.querySelector('#' + id);
        r.context.document = document;
        vm.runInContext(fs.readFileSync(path.join(shared, 'download-options-ui.js'), 'utf8'), r.context);
    }
    const container = r.context.document.createElement('div');
    r.context.document.body.appendChild(container);
    const details = r.context.mountUserscriptDownloadOptions(container);
    return {container, details, collection: details.querySelector('[data-download-option="collection"]'),
        refresh: () => details.querySelector('button').listeners.get('click')[0]()};
}

test('重建设置面板后，旧收藏夹查询的成功、HTTP失败或网络失败不清除新选择', async t => {
    for (const outcome of ['success', 'http-failure', 'network-failure']) {
        await t.test(outcome, async () => {
            const r = runtime(), options = r.context.options;
            options.selectCollection(7);
            let resolve, reject;
            const pending = new Promise((yes, no) => { resolve = yes; reject = no; });
            r.respond(() => pending);
            const old = settingsPanel(r), refreshing = old.refresh();
            await Promise.resolve();
            r.context.document.body.removeChild(old.container);
            r.respond(() => ({status: 200, data: {collections: [{id: 9, name: 'New choice'}]}}));
            const current = settingsPanel(r);
            await current.refresh();
            current.collection.value = '9';
            current.collection.dispatchEvent({type: 'change'});
            assert.equal(options.selectedCollectionId(), 9);
            if (outcome === 'network-failure') reject(new Error('offline'));
            else resolve({status: outcome === 'success' ? 200 : 500, data: {collections: [{id: 7}]}});
            await refreshing;
            assert.equal(options.selectedCollectionId(), 9);
            assert.equal(current.collection.value, '9');
            assert.equal(current.collection.disabled, false);
        });
    }
});

test('当前面板刷新失败清除旧选择，下载前复验不能清除请求期间的新选择', async () => {
    const r = runtime(), options = r.context.options;
    options.selectCollection(7);
    const panel = settingsPanel(r);
    r.respond(() => ({status: 403, data: {}}));
    await panel.refresh();
    assert.equal(options.selectedCollectionId(), null);
    assert.equal(panel.collection.value, '');
    options.selectCollection(7);
    let finish;
    r.respond(() => new Promise(resolve => { finish = resolve; }));
    const preparing = options.other('illust');
    await Promise.resolve();
    options.selectCollection(9);
    finish({status: 200, data: {collections: [{id: 7}]}});
    await assert.rejects(preparing);
    assert.equal(options.selectedCollectionId(), 9);
    r.respond(() => ({status: 503, data: {}}));
    await assert.rejects(options.other('illust'));
    assert.equal(options.selectedCollectionId(), 9);
    r.respond(() => ({status: 200, data: {collections: [{id: 9}]}}));
    assert.equal((await options.other('illust')).collectionId, 9);
});

test('下载筛选组合分级、AI、翻译标签和数值范围，并按作品类型取对应字段', () => {
    const r = runtime(), options = r.context.options;
    const meta = {xRestrict: 1, aiType: 2, illustType: 1, pageCount: 5, bookmarkCount: 20,
        wordCount: 800, tags: {tags: [{tag: '猫', translation: {en: 'Cat'}}, {tag: 'Blue sky'}]}};
    for (const [content, expected] of [['all', true], ['safe', false], ['r18plus', true], ['r18', true], ['r18g', false]]) {
        r.settings({content}); assert.equal(options.matches(meta, 'illust'), expected);
    }
    r.settings({ai: 'exclude'}); assert.equal(options.matches(meta, 'illust'), false);
    r.settings({content: 'r18plus'}); assert.equal(options.matches({...meta, xRestrict: 'unknown'}, 'illust'), false);
    r.settings({ai: 'only'}); assert.equal(options.matches({...meta, aiType: 'unknown'}, 'illust'), false);
    r.settings({ai: 'only', tagsExact: 'CAT，猫', tagsFuzzy: 'SKY', bookmarkMin: 20,
        pageMin: 6, pageMax: 4, type: 'manga', wordsMin: 900});
    assert.equal(options.matches(meta, 'illust'), true);
    assert.equal(options.matches(meta, 'novel'), false);
    r.settings({wordsMin: 800, pageMin: 999, type: 'ugoira'});
    assert.equal(options.matches(meta, 'novel'), true);
    r.settings({bookmarkMin: 0});
    assert.equal(options.matches({...meta, bookmarkCount: -1}, 'novel'), false);
    r.settings({tagsExact: 'cat,missing'}); assert.equal(options.matches(meta, 'illust'), false);
    r.settings({}); assert.equal(options.matches({...meta, xRestrict: 0}, 'illust', true), false);
    assert.equal(options.read().content, 'all');
    const keys = Object.keys(r.dictionaries['en-US']).sort();
    for (const values of Object.values(r.dictionaries)) assert.deepEqual(Object.keys(values).sort(), keys);
});

test('下载参数重验收藏夹和管理员身份，切换后端不复用选择且不发送 Pixiv Cookie', async () => {
    const r = runtime(), options = r.context.options;
    r.settings({fileNameTemplate: '{artwork_id}', autoTranslate: true, autoTranslateLanguage: 'en',
        autoTranslateSegmentSize: 600, autoTranslateMerge: true, autoTranslateMergeFormat: 'html'});
    options.selectCollection(7);
    r.respond(request => ({status: 200, data: request.url.endsWith('/check') ? {valid: true} : {collections: [{id: 7}]}}));
    const other = await options.other('novel');
    assert.deepEqual(JSON.parse(JSON.stringify(other)), {
        fileNameTemplate: '{artwork_id}', collectionId: 7, autoTranslate: true,
        autoTranslateLanguage: 'en', autoTranslateSegmentSize: 600, autoTranslateMerge: true, autoTranslateMergeFormat: 'html'
    });
    assert.ok(r.requests.every(request => request.timeout > 0 && !('Cookie' in request.headers) && !request.data));
    r.respond(() => ({status: 200, data: {collections: []}}));
    await assert.rejects(options.other('illust'));
    assert.equal(options.selectedCollectionId(), null);
    options.selectCollection(7);
    r.context.serverBase = 'https://other.example';
    assert.equal((await options.other('illust')).collectionId, null);
    r.respond(() => ({status: 200, data: {valid: false}}));
    await assert.rejects(options.other('novel'));
    r.settings({fileNameTemplate: 'x'.repeat(513)});
    await assert.rejects(options.other('illust'));
    r.settings({autoTranslate: true});
    r.respond(() => {
        r.context.serverBase = 'https://switched.example';
        return {status: 200, data: {valid: true}};
    });
    await assert.rejects(options.other('novel'));
});

test('运行中并发和间隔变化用于后续领取，暂停保留队列且不重复领取', async () => {
    const r = runtime(), started = [], releases = [];
    let limit = 1, interval = 100000;
    let observedPause = false;
    const manager = {
        queue: Array.from({length: 5}, (_, id) => ({id, status: 'pending'})),
        isRunning: true, isPaused: false, stopRequested: false,
        getIntervalMs: () => interval,
        _sleep() {
            if (this.isPaused) observedPause = true;
            return new Promise(resolve => setTimeout(resolve, 1));
        },
        _getNextPending() {
            const item = this.queue.find(item => item.status === 'pending');
            if (item) item.status = 'downloading';
            return item;
        },
        _processSingle(item) {
            started.push(item.id);
            return new Promise(resolve => releases.push(() => { item.status = 'completed'; resolve(); }));
        }
    };
    const until = async fn => {
        for (let i = 0; i < 1000 && !fn(); i++) await new Promise(resolve => setTimeout(resolve, 1));
        assert.ok(fn(), 'queue reached expected state');
    };
    const run = r.context.runUserscriptDownloadQueue(manager, () => limit);
    await until(() => started.length === 1);
    limit = 3;
    await until(() => started.length === 3);
    limit = 1;
    releases.shift()(); releases.shift()(); releases.shift()();
    await new Promise(resolve => setTimeout(resolve, 15));
    assert.equal(started.length, 3);
    manager.isPaused = true;
    interval = 0;
    await until(() => observedPause);
    assert.equal(started.length, 3);
    manager.isPaused = false;
    await until(() => started.length === 4);
    releases.shift()();
    await until(() => started.length === 5);
    releases.shift()();
    await run;
    assert.deepEqual(started, [0, 1, 2, 3, 4]);
    assert.ok(manager.queue.every(item => item.status === 'completed'));
});

test('小说翻译独立观察原始状态，隐藏上游错误，页面离开和失效条目释放观察', async () => {
    const r = runtime(), item = {};
    let current = true;
    r.respond(() => ({status: 200, data: {phase: 'TRANSLATING', elapsedSeconds: 12}}));
    r.context.translation.watch(item, 42, () => current, () => {});
    await r.tick();
    assert.equal(item.translatePhase, 'TRANSLATING');
    assert.ok(r.context.translation.label(item).includes('12'));
    r.respond(() => ({status: 200, data: {phase: 'FAILED', failed: true, failureReason: 'secret-upstream-response'}}));
    await r.tick();
    assert.equal(item.translatePhase, 'FAILED');
    assert.ok(!JSON.stringify(item).includes('secret'));
    assert.equal(r.timers.size, 0);
    r.context.translation.watch(item, 42, () => current, () => {});
    current = false;
    const count = r.requests.length;
    await r.tick();
    assert.equal(r.requests.length, count);
    assert.equal(r.timers.size, 0);
    current = true;
    r.context.translation.watch(item, 42, () => current, () => {});
    r.events.pagehide();
    assert.equal(item.translatePhase, 'STOPPED');
    assert.equal(r.timers.size, 0);
    r.events.pageshow();
    r.context.translation.watch(item, 42, () => current, () => {});
    assert.equal(r.timers.size, 1);
});

test('翻译观察限制累计条目与并发请求，缺失状态和超时会停止轮询', async () => {
    const r = runtime(), items = Array.from({length: 129}, () => ({}));
    let release;
    const response = new Promise(resolve => { release = resolve; });
    r.respond(() => response);
    for (const [id, item] of items.entries()) r.context.translation.watch(item, id, () => true, () => {});
    assert.equal(items.at(-1).translatePhase, 'STOPPED');
    const polling = r.tick();
    await Promise.resolve();
    assert.equal(r.requests.length, 4);
    r.context.translation.watch(items[0], 0, () => true, () => {});
    assert.equal(r.timers.size, 0);
    release({status: 200, data: {phase: 'TRANSLATING'}});
    await polling;
    r.events.pagehide();
    assert.equal(r.timers.size, 0);

    const missing = runtime(), item = {};
    missing.respond(() => ({status: 204, data: null}));
    missing.context.translation.watch(item, 42, () => true, () => {});
    for (let count = 0; count < 10; count++) await missing.tick();
    assert.equal(item.translatePhase, 'UNAVAILABLE');
    assert.equal(missing.timers.size, 0);
    let now = 0;
    missing.context.Date = class extends Date { static now() { return now; } };
    missing.context.translation.watch(item, 42, () => true, () => {});
    now = 31 * 60 * 1000;
    await missing.tick();
    assert.equal(item.translatePhase, 'STOPPED');
    assert.equal(missing.timers.size, 0);
});
