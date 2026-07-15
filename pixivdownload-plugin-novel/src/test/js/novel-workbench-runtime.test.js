'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const SOURCE = fs.readFileSync(path.join(__dirname, '..', '..', 'main', 'resources', 'static',
    'pixiv-novel-download', 'novel-queue-type.js'), 'utf8');
const ROOT = path.join(__dirname, '..', '..', '..', '..');
const WORKBENCH = path.join(ROOT, 'pixivdownload-plugin-download-workbench', 'src', 'main',
    'resources', 'static', 'pixiv-batch');
const QUEUE_TYPES_SOURCE = fs.readFileSync(path.join(WORKBENCH, 'batch-queue-types.js'), 'utf8');
const DOWNLOAD_SOURCE = fs.readFileSync(path.join(WORKBENCH, 'batch-download.js'), 'utf8');

class El {
    constructor(tag) {
        this.tag = tag;
        this.dataset = {};
        this.attributes = {};
        this.children = [];
        this.parentNode = null;
        this.onload = null;
        this.onerror = null;
        this.src = '';
    }
    appendChild(child) {
        child.parentNode = this;
        this.children.push(child);
        if (typeof this.onAppend === 'function') this.onAppend(child);
        return child;
    }
    setAttribute(name, value) { this.attributes[name] = String(value); }
    getAttribute(name) {
        return Object.prototype.hasOwnProperty.call(this.attributes, name) ? this.attributes[name] : null;
    }
    querySelectorAll() { return []; }
    remove() {
        if (!this.parentNode) return;
        const index = this.parentNode.children.indexOf(this);
        if (index >= 0) this.parentNode.children.splice(index, 1);
        this.parentNode = null;
    }
}

function manifest(revision, downloadTypes) {
    return {epoch: 'novel-test', revision, downloadTypes, tabs: [], uiSlots: []};
}

function novelType() {
    return {
        contractVersion: 1,
        type: 'novel',
        ownerPluginId: 'novel-owner',
        packageId: 'novel-package',
        pluginGeneration: 1,
        publicationId: 1,
        order: 10,
        moduleUrl: '/modules/novel.js',
        acquisitionModes: [],
        filters: ['novel-words'],
        settings: ['novel-settings-card']
    };
}

function runtimeHarness(manifests) {
    const head = new El('head');
    const body = new El('body');
    const document = {
        head,
        body,
        documentElement: new El('html'),
        currentScript: null,
        createElement: tag => new El(tag),
        querySelectorAll: () => []
    };
    let fetchIndex = 0;
    const listeners = new Map();
    const sandbox = {
        window: {
            location: {origin: 'https://local.test'},
            addEventListener(type, listener) {
                if (!listeners.has(type)) listeners.set(type, new Set());
                listeners.get(type).add(listener);
            },
            removeEventListener(type, listener) {
                const entries = listeners.get(type);
                if (entries) entries.delete(listener);
            },
            dispatchEvent(event) {
                Array.from(listeners.get(event.type) || []).forEach(listener => listener(event));
            }
        },
        document,
        BASE: '',
        URL,
        URLSearchParams,
        AbortController,
        CustomEvent: function CustomEvent(type, init) { return {type, detail: init && init.detail}; },
        Node: undefined,
        Promise,
        Set,
        Map,
        setTimeout,
        clearTimeout,
        setInterval,
        clearInterval,
        pageI18n: {apply() {}},
        console: {warn() {}, log() {}, error() {}},
        SINGLE_IMPORT_NOVEL_SOURCE: 'single-import-novel',
        QUICK_PAGE_SIZE_NOVEL: 24,
        getCookie: () => '',
        parseUserIdInput() {},
        getUserMeta() {},
        bt: (_key, fallback) => fallback,
        apiGet: url => Promise.resolve({bookmarkCount: 77, url}),
        fetch() {
            const data = manifests[Math.min(fetchIndex++, manifests.length - 1)];
            return Promise.resolve({ok: true, status: 200, json: () => Promise.resolve(data)});
        }
    };
    vm.createContext(sandbox);
    vm.runInContext(QUEUE_TYPES_SOURCE, sandbox);
    const qt = sandbox.window.PixivBatch.queueTypes;
    head.onAppend = script => {
        if (script.tag !== 'script') return;
        document.currentScript = script;
        vm.runInContext(SOURCE, sandbox);
        document.currentScript = null;
        if (typeof script.onload === 'function') script.onload();
    };
    return {sandbox, qt};
}

async function waitUntil(predicate) {
    for (let i = 0; i < 50; i++) {
        if (predicate()) return;
        await new Promise(resolve => setTimeout(resolve, 0));
    }
    throw new Error('timed out waiting for test condition');
}

(async function () {
    let initializer = null;
    let requestSignal = null;
    const sandbox = {
        window: {PixivBatch: {queueTypes: {registerModule(candidate) { initializer = candidate; }}}},
        console: {warn() {}, log() {}, error() {}},
        AbortController,
        URL,
        URLSearchParams,
        Promise,
        Set,
        Map,
        BASE: '',
        SINGLE_IMPORT_NOVEL_SOURCE: 'single-import-novel',
        QUICK_PAGE_SIZE_NOVEL: 24,
        getCookie: () => '',
        parseUserIdInput() {},
        getUserMeta() {},
        bt: (_key, fallback) => fallback,
        apiGet: url => Promise.resolve({bookmarkCount: 77, url}),
        state: {settings: {skipHistory: true, redownloadDeleted: false}, queue: []},
        setCurrent() {},
        renderQueue() {},
        updateStats() {},
        saveQueue() {},
        fetch(_url, init) {
            requestSignal = init && init.signal;
            return new Promise((_resolve, reject) => {
                requestSignal.addEventListener('abort', () => reject(new Error('aborted')), {once: true});
            });
        }
    };
    vm.createContext(sandbox);
    vm.runInContext(SOURCE, sandbox);
    assert.strictEqual(typeof initializer, 'function', 'Novel 模块应注册受控 initializer');

    const controller = new AbortController();
    let active = true;
    const context = {
        type: 'novel',
        signal: controller.signal,
        manifest: {pluginGeneration: 1},
        isActive() { return active && !controller.signal.aborted; },
        assertActive() {
            if (!this.isActive()) {
                const error = new Error('stale novel publication');
                error.code = 'STALE_QUEUE_TYPE';
                throw error;
            }
        },
        onCleanup() {}
    };
    const activation = initializer(context);
    const descriptor = activation.descriptor;
    const filter = descriptor.filters['novel-words'];
    const bookmark = await filter.bookmarkCountFetch('42');

    assert.ok(filter.matchExtra({wordCount: 1200}, {wordsMin: 1000, wordsMax: 1500}));
    assert.ok(!filter.matchExtra({wordCount: 400}, {wordsMin: 500, wordsMax: null}));
    assert.strictEqual(bookmark.bookmarkCount, 77);
    assert.strictEqual(bookmark.url, '/api/pixiv/novel/42/bookmark-count');
    assert.strictEqual(descriptor.settings['novel-settings-card'].cardId, 'novel-settings-card');

    const item = {id: 'n42', novelId: '42', kind: 'novel', status: 'downloading'};
    const processing = descriptor.process(item, context);
    for (let i = 0; i < 10 && !requestSignal; i++) await Promise.resolve();
    assert.strictEqual(requestSignal, controller.signal,
        'Novel 在途历史请求应使用当前 publication signal');
    active = false;
    controller.abort();
    await assert.rejects(processing, error => error && error.code === 'STALE_QUEUE_TYPE');
    assert.strictEqual(item.status, 'downloading', '过期 publication 不应由 provider 误记为普通失败');

    const h = runtimeHarness([manifest(1, [novelType()]), manifest(2, [])]);
    h.sandbox.bt = key => key;
    h.sandbox.state = {settings: {skipHistory: true, redownloadDeleted: false}, queue: []};
    h.sandbox.updateStats = function () {};
    h.sandbox.saveQueue = function () {};
    h.sandbox.renderQueue = function () {};
    h.sandbox.setCurrent = function () {};
    vm.runInContext(DOWNLOAD_SOURCE, h.sandbox);
    await h.qt.bootstrap();

    const runtimeFilter = h.qt.filtersFor('novel');
    const runtimeSetting = h.qt.settingsFor('novel');
    const runtimeBookmark = await runtimeFilter.bookmarkCountFetch('42');
    assert.ok(runtimeFilter.matchExtra({wordCount: 1200}, {wordsMin: 1000, wordsMax: 1500}));
    assert.strictEqual(runtimeBookmark.url, '/api/pixiv/novel/42/bookmark-count');
    assert.strictEqual(runtimeSetting.type, 'novel');
    assert.strictEqual(runtimeSetting.cardId, 'novel-settings-card');

    let runtimeSignal = null;
    h.sandbox.fetch = function (url, init) {
        if (String(url).includes('/api/download/extensions')) {
            return Promise.resolve({ok: true, status: 200, json: () => Promise.resolve(manifest(2, []))});
        }
        runtimeSignal = init && init.signal;
        return new Promise((_resolve, reject) => {
            runtimeSignal.addEventListener('abort', () => reject(new Error('aborted')), {once: true});
        });
    };
    const runtimeItem = {id: 'n42', novelId: '42', kind: 'novel', status: 'downloading'};
    const runtimeProcessing = h.sandbox.window.PixivBatch.download.processSingle(runtimeItem);
    await waitUntil(() => !!runtimeSignal);
    await h.qt.refresh();
    await runtimeProcessing;
    assert.ok(runtimeSignal.aborted && runtimeItem.status === 'paused'
        && runtimeItem.lastMessage === 'queue.message.type-unavailable',
    `Novel 在途历史请求在 publication 撤回后应由真实 workbench runtime 暂停且不误记失败：`
        + `aborted=${runtimeSignal.aborted}, status=${runtimeItem.status}, message=${runtimeItem.lastMessage}`);

    console.log('novel-workbench-runtime.test.js: 14 assertions passed ✓');
})().catch(error => {
    console.error('TEST FAILED:', error && error.stack ? error.stack : error);
    process.exit(1);
});
