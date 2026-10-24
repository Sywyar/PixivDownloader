'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {MiniElement} = require('./pixiv-layout-feedback-test-dom');

const root = path.resolve(__dirname, '../../../..');
const singleSource = fs.readFileSync(path.join(root, 'Pixiv 单作品图片下载器(Java后端版).user.js'), 'utf8');
const flush = () => new Promise(resolve => setImmediate(resolve));
const batchFiles = ['Pixiv URL 批量导入单作品下载器(URL Batch).user.js',
    'Pixiv User 批量下载器(User Batch).user.js', 'Pixiv 页面批量下载器(Page Scrape).user.js'];

test('单作品面板重建后，旧后端查询不能覆盖当前状态', async () => {
    const pending = [], document = {};
    document.createElement = tag => {
        const node = new MiniElement(tag, document);
        node.remove = () => node.parentNode?.removeChild(node);
        Object.defineProperty(node, 'isConnected', {get: () => document.body.contains(node)});
        return node;
    };
    document.body = document.createElement('body');
    document.getElementById = id => document.body.querySelector('#' + id);
    const context = vm.createContext({
        document, serverBase: 'http://localhost:8101',
        getPageTarget: () => ({kind: 'illust', id: '123'}),
        GM_getValue: (_key, fallback) => fallback,
        t: key => key, quotaInfo: {enabled: false},
        KEY_SKIP_HISTORY: 'history', KEY_VERIFY_HISTORY_FILES: 'verify',
        KEY_BOOKMARK_AFTER_DL: 'bookmark', KEY_REDOWNLOAD_DELETED: 'deleted', KEY_NOVEL_FORMAT: 'format',
        VERIFY_HISTORY_FILES_TOOLTIP: 'verify',
        downloadImages() {}, buildLangSwitcher: () => document.createElement('select'),
        mountUserscriptDownloadOptions() {}, applySinglePanelCollapsed() {},
        singleArchiveMonitor: {snapshot: () => null},
        checkBackendStatus: () => new Promise(resolve => pending.push(resolve))
    });
    vm.runInContext(singleSource.slice(singleSource.indexOf('    function createDownloadUI()'),
        singleSource.indexOf('    function renderSingleQuotaBar()')), context);
    context.createDownloadUI();
    context.createDownloadUI();
    pending[1](true);
    await flush();
    const status = document.getElementById('backend-status');
    assert.equal(status.textContent, 'single.backend.available');
    pending[0](false);
    await flush();
    assert.equal(status.textContent, 'single.backend.available');
    context.createDownloadUI();
    context.serverBase = 'http://localhost:8102';
    pending[2](true);
    await flush();
    assert.equal(document.getElementById('backend-status').textContent, 'single.backend.checking');
});

for (const file of batchFiles) {
    const source = fs.readFileSync(path.join(root, file), 'utf8');
    test(`${file}：小说提交遇到损坏响应时结算失败，保留鉴权与配额结果`, async () => {
        for (const [status, responseText, outcome] of [
            [500, 'null', 'failed'], [429, 'null', 'failed'],
            [200, '<html>error</html>', 'failed'], [200, '[]', 'failed'],
            [200, 'null', 'failed'], [200, '{"ok":true}', 'success'],
            [401, 'null', 'unauthorized'], [429, '{"quotaExceeded":true}', 'quota']
        ]) {
            const requests = [];
            let unauthorized = false, settled = false, actual;
            const context = vm.createContext({
                URL, serverBase: 'http://localhost:8101', userUUID: null,
                GM_xmlhttpRequest: request => requests.push(request),
                t: key => key, handleUnauthorized: () => { unauthorized = true; }
            });
            vm.runInContext(fs.readFileSync(path.join(root, 'scripts/shared/download-queue-runner.js'), 'utf8'), context);
            vm.runInContext(source.slice(source.indexOf('    const Api = {'),
                source.indexOf('    // >>> SHARED:sse-manager.js')) + '\nthis.api = Api;', context);
            const operation = context.api.sendNovelDownloadRequest({novelId: 123}).then(
                () => { actual = 'success'; },
                error => { actual = error.quotaData?.quotaExceeded ? 'quota' : 'failed'; }
            ).finally(() => { settled = true; });
            assert.doesNotThrow(() => requests[0].onload({status, responseText}));
            await flush();
            assert.equal(settled, true, '响应回调不得遗留未结算的下载请求');
            await operation;
            assert.equal(unauthorized ? 'unauthorized' : actual, outcome);
        }
    });
}

function queueHarness(file, api) {
    const source = fs.readFileSync(path.join(root, file), 'utf8');
    const calls = {submitted: 0, statuses: [], renders: 0, cleared: 0};
    const ui = new Proxy({
        elements: {concurrent: {value: 1}, interval: {value: 0}, imageDelay: {value: 0}},
        setStatus: status => calls.statuses.push(status),
        updateStats: stats => { calls.stats = {...stats}; },
        setCurrent: item => { calls.current = item; },
        renderQueue: () => calls.renders++
    }, {get: (target, key) => target[key] || (() => {})});
    const context = vm.createContext({
        URL, console, AbortController, DOMException, setTimeout, clearTimeout, setInterval, clearInterval,
        CONFIG: {STATUS_TIMEOUT_MS: 300000}, serverBase: 'http://localhost:8101', username: 'test', userUUID: null,
        quotaInfo: {enabled: false}, GM_getValue: (_key, fallback) => fallback,
        GM_setValue() {}, GM_deleteValue() {}, confirm: () => true, alert() {},
        t: key => key, buildForwardMetaJson: () => null,
        UserscriptDownloadOptions: {matches: () => true, other: async () => ({}), text: key => key},
        SSEManager: class {openShared() {} closeAll() {} close() {}},
        Api: {
            _seriesMetaPromises: new Map(),
            checkBackend: async () => true,
            clearQueue: async () => { calls.cleared++; },
            sendDownloadRequest: async () => { calls.submitted++; return {alreadyDownloaded: true}; },
            sendNovelDownloadRequest: async () => { calls.submitted++; return {alreadyDownloaded: true}; },
            ...api
        }
    });
    vm.runInContext(fs.readFileSync(path.join(root, 'scripts/shared/download-queue-runner.js'), 'utf8'), context);
    vm.runInContext(source.slice(source.indexOf('    class DownloadManager {'),
        source.indexOf('    // >>> SHARED:archive-monitor.js')) + '\nthis.Manager = DownloadManager;', context);
    const manager = new context.Manager(ui);
    manager.queue = [{id: '123', novelId: '123', title: 'test', status: 'idle'}];
    return {manager, calls, context};
}

function apiHarness(file) {
    const requests = [];
    let unauthorized = false, aborted = 0;
    const context = vm.createContext({
        URL,
        serverBase: 'http://localhost:8101', userUUID: null,
        CONFIG: {BACKEND_URL: 'http://localhost:8101/api/download/pixiv'},
        GM_xmlhttpRequest: request => {
            requests.push(request);
            return {abort: () => { aborted++; request.onabort?.(); }};
        },
        t: key => key.endsWith('need-login') ? 'Login required' : key,
        handleUnauthorized: () => { unauthorized = true; },
        UserscriptDownloadOptions: {other: async () => ({}), text: key => key}
    });
    const source = fs.readFileSync(path.join(root, file), 'utf8');
    vm.runInContext(fs.readFileSync(path.join(root, 'scripts/shared/download-queue-runner.js'), 'utf8'), context);
    vm.runInContext(source.slice(source.indexOf('    const Api = {'),
        source.indexOf('    // >>> SHARED:sse-manager.js')) + '\nthis.api = Api;', context);
    return {context, requests, get unauthorized() { return unauthorized; }, get aborted() { return aborted; }};
}

for (const file of batchFiles) {
    test(`${file}：后端写请求携带目标来源，清空保留捕获地址`, async () => {
        const r = apiHarness(file);
        r.context.serverBase = 'https://downloads.example:8443/pixiv';
        r.context.CONFIG.BACKEND_URL = r.context.serverBase + '/api/download/pixiv';
        r.context.CONFIG.QUOTA_INIT_URL = r.context.serverBase + '/api/quota/init';
        const novel = r.context.api.sendNovelDownloadRequest({novelId: 42});
        assert.equal(r.requests[0].headers.Origin, 'https://downloads.example:8443');
        r.requests[0].onload({status: 200, responseText: '{}'});
        await novel;
        const quota = r.context.api.initQuota();
        assert.equal(r.requests[1].headers.Origin, 'https://downloads.example:8443');
        r.requests[1].onload({status: 200, responseText: '{}'});
        await quota;
        const clear = r.context.api.clearQueue('http://[::1]:8101/api/download/queue/clear', 'old-owner');
        assert.equal(r.requests[2].headers.Origin, 'http://[::1]:8101');
        assert.equal(r.requests[2].headers['X-User-UUID'], 'old-owner');
        r.requests[2].onload({status: 200});
        await clear;
    });
}

for (const file of batchFiles) {
    test(`${file}：清空队列后，迟到的作品信息不能继续提交或覆盖清空状态`, async () => {
        for (const kind of ['illust', 'novel']) {
            let completeMeta;
            const pendingMeta = new Promise(resolve => { completeMeta = resolve; });
            const {manager, calls} = queueHarness(file, {
                getArtworkMeta: () => pendingMeta, getNovelMeta: () => pendingMeta
            });
            manager.queue[0].kind = kind;
            const run = manager.start();
            await flush();
            assert.equal(manager.queue[0].status, 'downloading');
            await manager.stopAndClear(true);
            const status = calls.statuses.at(-1), renders = calls.renders;
            completeMeta({illustTitle: 'late', pageCount: 1, urls: {original: 'https://i.pximg.net/image.jpg'}});
            await run;
            assert.equal(calls.submitted, 0);
            assert.equal(calls.cleared, 1);
            assert.equal(calls.statuses.at(-1), status);
            assert.equal(calls.renders, renders);
            assert.equal(manager.queue.length, 0);
        }
    });

    test(`${file}：英语鉴权失败停止队列，不再领取后续作品`, async () => {
        for (const kind of ['illust', 'novel']) {
            const backend = apiHarness(file);
            const {manager} = queueHarness(file, {
                getArtworkMeta: async () => ({pageCount: 1, urls: {original: 'https://i.pximg.net/image.jpg'}}),
                getNovelMeta: async () => ({fetchToken: 'fetch-ticket'}),
                sendDownloadRequest: (...args) => backend.context.api.sendDownloadRequest(...args),
                sendNovelDownloadRequest: (...args) => backend.context.api.sendNovelDownloadRequest(...args)
            });
            manager.queue[0].kind = kind;
            manager.queue.push({id: '456', status: 'idle'});
            const run = manager.start();
            await flush();
            assert.equal(backend.requests.length, 1);
            if (kind === 'novel') assert.equal(JSON.parse(backend.requests[0].data).fetchToken, 'fetch-ticket');
            backend.requests[0].onload({status: 401, responseText: 'null'});
            await run;
            assert.equal(backend.unauthorized, true);
            assert.equal(manager.stopRequested, true);
            assert.equal(manager.queue[1].status, 'failed');
        }
    });

    test(`${file}：清空等待请求串行完成后才启动新队列，旧 worker 退出不改变新队列`, async () => {
        let finishOld, finishNew;
        const oldMeta = new Promise(resolve => { finishOld = resolve; });
        const newMeta = new Promise(resolve => { finishNew = resolve; });
        const clears = [];
        let reads = 0;
        const {manager, calls} = queueHarness(file, {
            getArtworkMeta: () => ++reads === 1 ? oldMeta : newMeta,
            clearQueue: () => new Promise(resolve => clears.push(resolve))
        });
        const oldRun = manager.start();
        await flush();
        await manager.stopAndClear(true);
        await manager.stopAndClear(true);
        assert.equal(clears.length, 1);
        manager.queue.push({id: '456', title: 'new', status: 'idle'});
        const newRun = manager.start();
        await flush();
        assert.equal(reads, 1);
        clears[0](true);
        await flush();
        assert.equal(clears.length, 2);
        assert.equal(reads, 1);
        clears[1](true);
        await flush();
        assert.equal(reads, 2);
        finishOld({});
        await oldRun;
        assert.equal(manager.isRunning, true);
        assert.equal(manager.queue[0].status, 'downloading');
        assert.equal(calls.submitted, 0);
        finishNew({pageCount: 1, urls: {original: 'https://i.pximg.net/image.jpg'}});
        await newRun;
        assert.equal(manager.queue[0].status, 'skipped');
        assert.equal(calls.submitted, 1);
    });

    test(`${file}：清空撤销实际作品读取，旧响应不再交付给队列`, async () => {
        const backend = apiHarness(file);
        const {manager, calls} = queueHarness(file, {
            getArtworkMeta: (...args) => backend.context.api.getArtworkMeta(...args)
        });
        const run = manager.start();
        await flush();
        assert.equal(backend.requests.length, 1);
        await manager.stopAndClear(true);
        await run;
        assert.equal(backend.aborted, 1);
        const renders = calls.renders;
        backend.requests[0].onload({status: 200, responseText: '{"body":{"pageCount":1}}'});
        await flush();
        assert.equal(calls.renders, renders);
        assert.equal(calls.submitted, 0);
    });

    test(`${file}：清空发生在下载设置复验期间时不再发送插画命令`, async () => {
        const backend = apiHarness(file);
        let finishOptions;
        backend.context.UserscriptDownloadOptions.other = () => new Promise(resolve => { finishOptions = resolve; });
        const controller = new AbortController();
        const args = ['123', ['https://i.pximg.net/image.jpg'], 'test'];
        if (file.includes('User Batch')) args.push('user');
        args.push(42, 'author', 0, false, null, 0, false, '', [], null, null, controller.signal);
        const operation = backend.context.api.sendDownloadRequest(...args);
        const rejected = assert.rejects(operation, {name: 'AbortError'});
        controller.abort();
        finishOptions({});
        await rejected;
        assert.equal(backend.requests.length, 0);
    });

    test(`${file}：插画提交按 HTTP 状态处理鉴权并拒绝损坏成功响应`, async () => {
        for (const [status, responseText] of [[401, '<html>login</html>'], [200, 'null'], [200, '[]'], [200, 'broken']]) {
            const backend = apiHarness(file);
            const operation = backend.context.api.sendDownloadRequest('123', [], 'test');
            const rejected = assert.rejects(operation);
            await flush();
            backend.requests[0].onload({status, responseText});
            await rejected;
            assert.equal(backend.unauthorized, status === 401);
        }
    });
}

test('终态等待只保留一个补查，撤销后释放等待并忽略旧事件与旧响应', async () => {
    const timers = new Map(), intervals = new Map();
    let id = 0, queries = 0, completeQuery, listener, closed = 0;
    const {manager, context, calls} = queueHarness(batchFiles[0], {
        getDownloadStatus: () => { queries++; return new Promise(resolve => { completeQuery = resolve; }); }
    });
    context.setTimeout = fn => { timers.set(++id, fn); return id; };
    context.clearTimeout = key => timers.delete(key);
    context.setInterval = fn => { intervals.set(++id, fn); return id; };
    context.clearInterval = key => intervals.delete(key);
    manager.sse = {addListener: (_id, fn) => { listener = fn; }, close: () => closed++};
    const controller = new AbortController();
    const wait = context.waitForUserscriptDownload(manager, '123', 300000, controller.signal);
    const poll = [...intervals.values()][0];
    const inFlight = poll();
    await poll();
    assert.equal(queries, 1);
    listener({downloadedCount: 2});
    assert.equal(manager.queue[0].downloadedCount, 2);
    controller.abort();
    assert.equal(await wait, null);
    assert.equal(timers.size, 0);
    assert.equal(intervals.size, 0);
    assert.equal(closed, 1);
    manager.queue = [{id: '123', downloadedCount: 0}];
    const renders = calls.renders;
    listener({downloadedCount: 9});
    completeQuery({completed: true, downloadedCount: 9});
    await inFlight;
    assert.equal(manager.queue[0].downloadedCount, 0);
    assert.equal(calls.renders, renders);
    assert.equal(closed, 1);
});

for (const file of batchFiles) {
    test(`${file}：清空同时刷新统计和当前下载卡，不等待旧 worker 退出`, async () => {
        const {manager, calls} = queueHarness(file, {});
        manager.queue[0].status = 'downloading';
        manager.updateStats();
        manager.ui.setCurrent(manager.queue[0]);
        assert.equal(calls.stats.active, 1);
        await manager.stopAndClear(true);
        assert.equal(manager.queue.length, 0);
        assert.equal(calls.stats.active, 0);
        assert.equal(calls.current, null);
    });
}

test('单作品小说下载拒绝损坏响应，且不消耗本地配额或显示提交成功', async () => {
    for (const [status, responseText, outcome] of [
        [500, 'null', 'failed'], [429, 'null', 'failed'],
        [200, '<html>error</html>', 'failed'], [200, '[]', 'failed'],
        [200, 'null', 'failed'], [200, '{"message":"queued"}', 'success'],
        [401, 'null', 'unauthorized'], [429, '{"quotaExceeded":true}', 'quota']
    ]) {
        const requests = [], alerts = [];
        let settled = false, unauthorized = false, quotaShown = false, rebuilt = false;
        const quotaInfo = {enabled: true, artworksUsed: 2, maxArtworks: 10};
        const context = vm.createContext({
            URL, serverBase: 'http://localhost:8101', userUUID: null, quotaInfo,
            getNovelId: () => '123', checkBackendStatus: async () => true,
            getNovelMeta: async () => ({}),
            GM_getValue: (_key, fallback) => fallback,
            GM_xmlhttpRequest: request => requests.push(request),
            KEY_SKIP_HISTORY: 'history', KEY_NOVEL_FORMAT: 'format', KEY_BOOKMARK_AFTER_DL: 'bookmark',
            UserscriptDownloadOptions: {matches: () => true, other: async () => ({}), text: key => key},
            t: key => key, alert: message => alerts.push(message), console: {warn() {}, error() {}},
            handleUnauthorized: () => { unauthorized = true; },
            showQuotaExceededUI: () => { quotaShown = true; },
            createDownloadUI: () => { rebuilt = true; }
        });
        vm.runInContext(singleSource.slice(singleSource.indexOf('    async function downloadNovel()'),
            singleSource.indexOf('    // 获取图片URL')), context);
        const operation = context.downloadNovel().finally(() => { settled = true; });
        await flush();
        assert.equal(requests.length, 1);
        assert.doesNotThrow(() => requests[0].onload({status, responseText}));
        await flush();
        assert.equal(settled, true, '响应回调不得遗留未结算的下载请求');
        await operation;
        assert.equal(rebuilt, outcome === 'success');
        assert.equal(quotaInfo.artworksUsed, outcome === 'success' ? 3 : 2);
        assert.equal(alerts.includes('single.alert.download-submitted'), outcome === 'success');
        assert.equal(alerts.includes('single.alert.download-failed'), ['failed', 'unauthorized'].includes(outcome));
        assert.equal(unauthorized, outcome === 'unauthorized');
        assert.equal(quotaShown, outcome === 'quota');
    }
});

test('单作品插画按 HTTP 状态处理登录失败，并保留成功与配额响应', async () => {
    for (const [status, responseText, outcome] of [
        [401, '<html>login</html>', 'unauthorized'], [200, 'null', 'failed'],
        [200, '{"success":true}', 'success'], [429, '{"quotaExceeded":true}', 'quota']
    ]) {
        let request, unauthorized = false;
        const context = vm.createContext({
            URL, userUUID: null, getBackendURL: () => 'http://localhost:8101/api/download/pixiv',
            UserscriptDownloadOptions: {other: async () => ({}), text: key => key},
            GM_xmlhttpRequest: options => { request = options; },
            handleUnauthorized: () => { unauthorized = true; }, t: key => key
        });
        vm.runInContext(singleSource.slice(singleSource.indexOf('    async function sendDownloadRequest('),
            singleSource.indexOf('    // 检查后端服务状态')), context);
        const result = context.sendDownloadRequest('123', 'title', [], {}).then(
            () => 'success', error => error.quotaData?.quotaExceeded ? 'quota' : 'failed');
        await flush();
        assert.equal(request.headers.Origin, 'http://localhost:8101');
        request.onload({status, responseText});
        const actual = await result;
        assert.equal(unauthorized ? 'unauthorized' : actual, outcome);
    }
});

for (const file of batchFiles) {
    test(`${file}：旧自动打包回执不能覆盖清空或服务器切换后的状态`, async () => {
        for (const replace of ['clear', 'server']) {
            const {manager, context, calls} = queueHarness(file);
            let request;
            context.userUUID = null;
            context.GM_xmlhttpRequest = options => { request = options; };
            const pack = manager._autoPackAfterQueue();
            assert.equal(request.method, 'POST');
            assert.equal(request.headers.Origin, 'http://localhost:8101');
            if (replace === 'clear') await manager.stopAndClear(true);
            else context.serverBase = 'http://localhost:8102';
            const statuses = calls.statuses.length;
            request.onload({status: 200, responseText: '{"archiveToken":"old"}'});
            await pack;
            assert.equal(calls.statuses.length, statuses);
        }
    });
}

for (const file of batchFiles.slice(1)) {
    test(`${file}：清空后的系列合订回执不再更新当前队列`, async () => {
        const {manager, context, calls} = queueHarness(file);
        let request;
        context.userUUID = null;
        context.GM_xmlhttpRequest = options => { request = options; };
        manager.globalSettings.mergeNovelSeries = true;
        manager.queue[0] = {kind: 'novel', status: 'completed', seriesId: 42};
        manager._mergeFinishedNovelSeries();
        assert.equal(request.method, 'POST');
        assert.equal(request.headers.Origin, 'http://localhost:8101');
        await manager.stopAndClear(true);
        const statuses = calls.statuses.length;
        request.onload({status: 200, responseText: '{"success":true}'});
        assert.equal(calls.statuses.length, statuses);
    });
}

for (const file of batchFiles) {
    test(`${file}：排队的清空命令保持发起时的后端与用户身份`, async () => {
        const backend = apiHarness(file);
        backend.context.userUUID = 'first-owner';
        Object.defineProperty(backend.context.CONFIG, 'CLEAR_QUEUE_URL', {
            get: () => backend.context.serverBase + '/api/download/queue/clear'
        });
        const {manager, context} = queueHarness(file, {
            clearQueue: (...args) => backend.context.api.clearQueue(...args)
        });
        context.userUUID = 'first-owner';
        Object.defineProperty(context.CONFIG, 'CLEAR_QUEUE_URL', {
            get: () => context.serverBase + '/api/download/queue/clear'
        });
        let release;
        manager._clearRequest = new Promise(resolve => { release = resolve; });
        await manager.stopAndClear(true);
        context.serverBase = backend.context.serverBase = 'http://localhost:8102';
        context.userUUID = backend.context.userUUID = 'second-owner';
        release();
        await flush();
        assert.equal(backend.requests[0].url, 'http://localhost:8101/api/download/queue/clear');
        assert.equal(backend.requests[0].headers['X-User-UUID'], 'first-owner');
        backend.requests[0].onload({status: 200});
        await manager._clearRequest;
    });
}

test('重新解析 URL 队列时，取消保留活动任务，确认后撤回旧任务再替换', async t => {
    let completeMeta;
    const {manager, context, calls} = queueHarness(batchFiles[0], {
        getArtworkMeta: () => new Promise(resolve => { completeMeta = resolve; })
    });
    const run = manager.start();
    await flush();
    t.after(async () => {
        await manager.stopAndClear(true);
        completeMeta?.({});
        await run;
    });
    context.confirm = () => false;
    await manager.parseAndSetFromText('https://www.pixiv.net/artworks/456');
    assert.equal(manager.queue[0].id, '123');
    context.confirm = () => true;
    await manager.parseAndSetFromText('https://www.pixiv.net/artworks/456');
    completeMeta({pageCount: 1, urls: {original: 'https://i.pximg.net/image.jpg'}});
    await run;
    assert.equal(calls.submitted, 0);
    assert.equal(calls.cleared, 1);
    assert.equal(manager.queue[0].id, '456');
    assert.equal(manager.queue[0].status, 'idle');
});

test('用户页切换或清空后，旧作品列表不能加入当前队列或自动开始下载', async () => {
    const source = fs.readFileSync(path.join(root, batchFiles[1]), 'utf8');
    for (const change of ['user', 'clear']) {
        let finish;
        const {manager, context, calls} = queueHarness(batchFiles[1], {
            getAllUserArtworkIds: () => new Promise(resolve => { finish = resolve; })
        });
        manager.userId = '11';
        vm.runInContext('this.actions = {' + source.slice(source.indexOf('        async handleFetch(onlyNew)'),
            source.indexOf('        handleStart()', source.indexOf('        async handleFetch(onlyNew)'))) + '};', context);
        let starts = 0;
        const ui = {manager, setStatus: (...args) => manager.ui.setStatus(...args),
            elements: {skipHistory: {}}, updateSkipHistoryVisibility() {}, handleStart: () => starts++};
        const operation = context.actions.handleFetch.call(ui, true);
        if (change === 'user') manager.initForUser('22');
        else manager.stopAndClear();
        const statuses = calls.statuses.length;
        finish(['456']);
        await operation;
        assert.equal(manager.queue.length, 0);
        assert.equal(starts, 0);
        assert.equal(calls.statuses.length, statuses);
    }
});

test('返回同一用户页保留活动队列，换用户后旧名称不能覆盖新用户', async () => {
    const source = fs.readFileSync(path.join(root, batchFiles[1]), 'utf8');
    const {manager, context} = queueHarness(batchFiles[1]);
    let uid = '11', tick;
    const pending = [];
    context.manager = manager;
    context.ui = manager.ui;
    context.getCurrentUserId = () => uid;
    context.maybeInitQuota = () => {};
    context.Api.getUserMeta = id => new Promise(resolve => pending.push({id, resolve}));
    context.setInterval = fn => { tick = fn; };
    context.console = {log() {}, warn() {}};
    vm.runInContext(source.slice(source.indexOf('    let lastUid = null;'),
        source.indexOf('    GM_registerMenuCommand', source.indexOf('    let lastUid = null;'))), context);
    tick();
    manager.queue.push({id: '123', status: 'downloading'});
    manager.isRunning = true;
    const queue = manager.queue;
    uid = null;
    tick();
    uid = '11';
    tick();
    assert.equal(manager.queue, queue);
    assert.equal(manager.queue[0].status, 'downloading');
    uid = '22';
    tick();
    pending.at(-1).resolve('new-name');
    await flush();
    pending[0].resolve('old-name');
    await flush();
    assert.equal(manager.userId, '22');
    assert.equal(manager.userName, 'new-name');
});

test('页面相关作品查询在清空后不再加入队列，当前查询仍去重加入', async () => {
    const source = fs.readFileSync(path.join(root, batchFiles[2]), 'utf8');
    for (const clear of [false, true]) {
        let finish;
        const {manager, context, calls} = queueHarness(batchFiles[2], {
            getRelatedArtworks: () => new Promise(resolve => { finish = resolve; })
        });
        context.location = {pathname: '/artworks/123'};
        vm.runInContext('this.actions = {' + source.slice(source.indexOf('        async handleScrapeRelated()'),
            source.indexOf('        updateSinglePageButtonsVisibility()')) + '};', context);
        const operation = context.actions.handleScrapeRelated.call({manager, setStatus: manager.ui.setStatus});
        if (clear) manager.stopAndClear();
        const statuses = calls.statuses.length;
        finish({illusts: [{id: '123'}, {id: '456'}], nextIds: ['456', '789']});
        await operation;
        assert.deepEqual(Array.from(manager.queue, item => item.id), clear ? [] : ['123', '456', '789']);
        assert.equal(calls.statuses.length, statuses + (clear ? 0 : 1));
    }
});

for (const file of ['Pixiv 单作品图片下载器(Java后端版).user.js', ...batchFiles]) {
    test(`${file}：收藏必须收到有效成功响应，损坏正文不能显示成功`, async () => {
        const source = fs.readFileSync(path.join(root, file), 'utf8');
        for (const [responseText, expected] of [
            ['null', 'failed'], ['<html>error</html>', 'failed'], ['[]', 'failed'],
            ['', 'failed'], ['{}', 'failed'], ['{"error":true,"message":"denied"}', 'failed'],
            ['{"error":false,"body":{}}', 'success']
        ]) {
            let request, resets = 0;
            const context = vm.createContext({
                fetchPixivCsrfToken: async () => 'test-token', _resetPixivCsrfToken: () => resets++,
                GM_xmlhttpRequest: options => { request = options; },
                t: key => key, UserscriptDownloadOptions: {text: key => key}
            });
            vm.runInContext(fs.readFileSync(path.join(root, 'scripts/shared/download-queue-runner.js'), 'utf8'), context);
            vm.runInContext(source.slice(source.indexOf('    async function _pixivBookmark('),
                source.indexOf('    function pixivBookmarkArtwork(')), context);
            const operation = context._pixivBookmark('https://www.pixiv.net/ajax/illusts/bookmarks/add', {illust_id: '123'});
            await flush();
            request.onload({status: 200, responseText});
            assert.equal((await operation).status, expected);
            if (responseText.includes('denied')) assert.equal(resets, 1);
        }
    });
}

for (const file of batchFiles) {
    test(`${file}：取得小说票据后更换服务器，旧票据不能提交到新地址`, async () => {
        const {manager, context, calls} = queueHarness(file, {
            getNovelMeta: async () => ({fetchToken: 'old-server-ticket'})
        });
        context.UserscriptDownloadOptions.other = async () => {
            context.serverBase = 'http://localhost:8102';
            return {};
        };
        manager.queue[0].kind = 'novel';
        await manager.start();
        assert.equal(calls.submitted, 0);
        assert.equal(manager.queue[0].status, 'failed');
    });
}

test('单作品再次下载时重新读取系列资料，不沿用页面中早先的成功或失败', async () => {
    const requests = [];
    const context = vm.createContext({
        GM_xmlhttpRequest: options => requests.push(options), _pn_extractSeriesCoverUrl: () => ''
    });
    vm.runInContext(singleSource.slice(singleSource.indexOf('    function fetchSeriesEnrichment('),
        singleSource.indexOf('    // 获取作品元数据')), context);
    const first = context.fetchSeriesEnrichment(42);
    requests[0].onerror();
    assert.equal(await first, null);
    const second = context.fetchSeriesEnrichment(42);
    assert.equal(requests.length, 2);
    requests[1].onload({responseText: '{"body":{"illustSeries":[{"caption":"updated"}]}}'});
    assert.equal((await second).caption, 'updated');
});

test('页面缩略图未变时保留勾选文字，队列移除仍更新标记', () => {
    const source = fs.readFileSync(path.join(root, batchFiles[2]), 'utf8');
    let writes = 0;
    const document = {createElement: tag => {
        const node = new MiniElement(tag, document);
        node.remove = () => node.parentNode?.removeChild(node);
        Object.defineProperty(node, 'textContent', {
            get: () => node._textContent,
            set: value => { writes++; node._textContent = value; }
        });
        return node;
    }};
    const anchor = document.createElement('a');
    anchor.href = 'https://www.pixiv.net/artworks/123';
    anchor.appendChild(document.createElement('img'));
    document.querySelectorAll = selector => selector.includes('/artworks/') ? [anchor] : [];
    const context = vm.createContext({document, t: key => key, getComputedStyle: () => ({position: 'relative'})});
    const method = source.slice(source.indexOf('        updateOverlays() {'),
        source.indexOf('    /* ========== 初始化 ========== */')).trim();
    vm.runInContext('this.actions = {' + method.slice(0, method.lastIndexOf('}')) + '};', context);
    const ui = {manager: {queue: [{id: '123'}], globalSettings: {}}, _injectOverlayStyles() {}};
    context.actions.updateOverlays.call(ui);
    const overlay = anchor.querySelector('.pixiv-queue-overlay');
    assert.equal(overlay.textContent, '✓');
    const before = writes;
    context.actions.updateOverlays.call(ui);
    assert.equal(writes, before);
    assert.equal(anchor.querySelector('.pixiv-queue-overlay'), overlay);
    ui.manager.queue = [];
    context.actions.updateOverlays.call(ui);
    assert.equal(overlay.textContent, '');
    assert.equal(anchor.querySelector('.pixiv-queue-frame'), null);
});

for (const file of ['Pixiv 单作品图片下载器(Java后端版).user.js', ...batchFiles]) {
    test(`${file}：小说准备期间更换服务器，不向新地址导入正文`, async () => {
        const source = fs.readFileSync(path.join(root, file), 'utf8');
        const backend = apiHarness(file.includes('Java后端版') ? batchFiles[0] : file);
        let imports = 0;
        Object.assign(backend.context, {
            buildNovelMetaFromPixivBody: () => ({}),
            NovelBrowserImport: {importResponse: async () => { imports++; return 'ticket'; }},
            UserscriptDownloadOptions: {text: key => key}
        });
        if (file.includes('Java后端版')) {
            vm.runInContext(source.slice(source.indexOf('    async function getNovelMeta('),
                source.indexOf('    // 小说三态判重')), backend.context);
        }
        const operation = (file.includes('Java后端版')
            ? backend.context.getNovelMeta('123') : backend.context.api.getNovelMeta('123'));
        const rejected = assert.rejects(operation);
        backend.context.serverBase = 'http://localhost:8102';
        await backend.requests[0].onload({status: 200, responseText: '{"body":{"id":"123"}}'});
        await rejected;
        assert.equal(imports, 0);
    });
}


for (const phase of ['before-poll', 'after-poll', 'event']) {
    test(`服务器切换 ${phase} 后停止旧作品观察，不读取或接受新服务器的同名任务`, async () => {
        const timers = new Map(), intervals = new Map();
        let id = 0, queries = 0, finishQuery, listener;
        const {manager, context} = queueHarness(batchFiles[0], {
            getDownloadStatus: () => { queries++; return new Promise(resolve => { finishQuery = resolve; }); }
        });
        context.setTimeout = fn => { timers.set(++id, fn); return id; };
        context.clearTimeout = key => timers.delete(key);
        context.setInterval = fn => { intervals.set(++id, fn); return id; };
        context.clearInterval = key => intervals.delete(key);
        manager.sse = {addListener: (_id, fn) => { listener = fn; }, close() {}};
        const result = context.waitForUserscriptDownload(manager, '123', 300000, new AbortController().signal)
            .then(value => ({value}), error => ({error}));
        const poll = [...intervals.values()][0];
        let inFlight;
        if (phase === 'after-poll') inFlight = poll();
        context.serverBase = 'http://localhost:8102';
        if (phase === 'before-poll') {
            inFlight = poll();
            await flush();
            assert.equal(queries, 0);
            await inFlight;
        } else if (phase === 'after-poll') {
            finishQuery({completed: true, downloadedCount: 1});
            await inFlight;
        } else listener({completed: true, downloadedCount: 1});
        assert.equal(timers.size, 0);
        assert.equal(intervals.size, 0);
        assert.ok((await result).error);
    });
}


for (const file of batchFiles) {
    test(`${file}：切换服务器后丢弃旧提交和状态结果，不对新服务器触发登录提示`, async () => {
        for (const method of ['sendDownloadRequest', 'sendNovelDownloadRequest', 'getDownloadStatus', 'getNovelStatus']) {
            const r = apiHarness(file);
            const operation = method === 'sendDownloadRequest' ? r.context.api[method]('123', [], 'test')
                : r.context.api[method](method === 'sendNovelDownloadRequest' ? {novelId: 123} : '123');
            const rejected = assert.rejects(operation);
            await flush();
            r.context.serverBase = 'http://localhost:8102';
            r.requests[0].onload({status: 401, responseText: '{"completed":true}'});
            await rejected;
            assert.equal(r.unauthorized, false);
        }
    });
}


for (const file of batchFiles) {
    test(`${file}：系列资料在批次内复用，下一批重新查询先前失败的系列`, async () => {
        const r = apiHarness(file);
        const {manager} = queueHarness(file, {_seriesMetaPromises: r.context.api._seriesMetaPromises});
        manager._processSingle = async ({item}, signal) => {
            const first = r.context.api.getSeriesEnrichment(42, signal);
            const reused = r.context.api.getSeriesEnrichment(42, signal);
            assert.equal(r.requests.length, expectedRequests);
            r.requests.at(-1).onerror(new Error('temporary failure'));
            assert.equal(await first, null);
            assert.equal(await reused, null);
            item.status = 'completed';
        };
        let expectedRequests = 1;
        await manager.start();
        manager.queue[0].status = 'idle';
        expectedRequests = 2;
        await manager.start();
        assert.equal(r.requests.length, 2);
    });
}

test('新服务器作品忽略旧 SSE 同名事件，仍可通过状态补查完成', async () => {
    let listener, poll, closed = false;
    const {manager, context} = queueHarness(batchFiles[0], {getDownloadStatus: async () => ({completed: true, downloadedCount: 1})});
    context.setTimeout = () => 1;
    context.clearTimeout = () => {};
    context.setInterval = fn => { poll = fn; return 2; };
    context.clearInterval = () => {};
    context.CONFIG.SSE_BASE = 'http://localhost:8102/events';
    manager.sse = {url: 'http://localhost:8101/events', addListener: (_id, fn) => { listener = fn; }, close: () => { closed = true; }};
    const result = context.waitForUserscriptDownload(manager, '123', 300000, new AbortController().signal);
    listener({completed: true, downloadedCount: 9});
    assert.equal(closed, false);
    await poll();
    assert.equal((await result).downloadedCount, 1);
    assert.equal(closed, true);
});

test('单作品小说切换服务器后不提交旧票据，旧响应不触发新地址的登录或配额状态', async () => {
    for (const phase of ['options', 'response']) {
        let finishOptions, unauthorized = false;
        const requests = [], alerts = [], quotaInfo = {enabled: true, artworksUsed: 2, maxArtworks: 10};
        const context = vm.createContext({
            URL, serverBase: 'http://localhost:8101', userUUID: null, quotaInfo,
            getNovelId: () => '123', checkBackendStatus: async () => true,
            getNovelMeta: async () => ({fetchToken: 'old-ticket'}),
            GM_getValue: (_key, fallback) => fallback,
            GM_xmlhttpRequest: request => requests.push(request),
            KEY_SKIP_HISTORY: 'history', KEY_NOVEL_FORMAT: 'format', KEY_BOOKMARK_AFTER_DL: 'bookmark',
            UserscriptDownloadOptions: {matches: () => true, text: key => key,
                other: () => new Promise(resolve => { finishOptions = resolve; })},
            t: key => key, alert: message => alerts.push(message), console: {warn() {}, error() {}},
            handleUnauthorized: () => { unauthorized = true; },
            createDownloadUI: () => assert.fail('旧响应不能重建当前面板')
        });
        vm.runInContext(singleSource.slice(singleSource.indexOf('    async function downloadNovel()'),
            singleSource.indexOf('    // 获取图片URL')), context);
        const operation = context.downloadNovel();
        await flush();
        if (phase === 'options') context.serverBase = 'http://localhost:8102';
        finishOptions({});
        await flush();
        if (phase === 'response') {
            assert.equal(JSON.parse(requests[0].data).fetchToken, 'old-ticket');
            context.serverBase = 'http://localhost:8102';
            requests[0].onload({status: 401, responseText: 'null'});
        } else assert.equal(requests.length, 0);
        await operation;
        assert.equal(quotaInfo.artworksUsed, 2);
        assert.equal(unauthorized, false);
        assert.equal(alerts.includes('single.alert.download-submitted'), false);
        assert.equal(alerts.includes('single.alert.download-failed'), true);
    }
});

test('单作品插画准备或等待回执时换址，不向新地址提交或显示旧登录提示', async () => {
    for (const phase of ['options', 'response']) {
        let finishOptions, unauthorized = false, url = 'http://localhost:8101/api/download/pixiv';
        const requests = [];
        const context = vm.createContext({
            URL, userUUID: null, getBackendURL: () => url,
            UserscriptDownloadOptions: {text: key => key, other: () => new Promise(resolve => { finishOptions = resolve; })},
            GM_xmlhttpRequest: request => requests.push(request),
            handleUnauthorized: () => { unauthorized = true; }, t: key => key
        });
        vm.runInContext(singleSource.slice(singleSource.indexOf('    async function sendDownloadRequest('),
            singleSource.indexOf('    // 检查后端服务状态')), context);
        const rejected = assert.rejects(context.sendDownloadRequest('123', 'test', [], {}));
        if (phase === 'options') url = 'http://localhost:8102/api/download/pixiv';
        finishOptions({});
        await flush();
        if (phase === 'response') {
            url = 'http://localhost:8102/api/download/pixiv';
            requests[0].onload({status: 401, responseText: 'null'});
        } else {
            assert.equal(requests.length, 0);
        }
        await rejected;
        assert.equal(unauthorized, false);
    }
});
