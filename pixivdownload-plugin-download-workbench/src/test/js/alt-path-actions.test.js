'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.join(__dirname, '../../main/resources/static');

function harness(choice) {
    const requests = [];
    let prompts = 0;
    const context = vm.createContext({
        AbortController, console, BASE: '', bt: key => key,
        window: {PixivBatch: {}, PixivBatchAlt: {state: {}, settings: {}, engine: {}},
            PixivFeedback: {choose: async options => {
                assert.equal(options.title, 'batch:path.overflow.title');
                prompts++;
                return choice;
            }}},
        setTimeout() { return 1; }, clearTimeout() {},
        checkBackend: async () => true, getCookie: () => '',
        updateStats() {}, saveQueue() {}, renderQueue() {}, renderCurrent() {},
        updateButtonsState() {}, setDockStatus() {}, syncAllResultsQueueState() {}, clearSavedQueue() {},
        ensureSharedSSE() {}, closeAllSSE() {}, openSSE() {}, closeSSE() {},
        evaluateDownloadFilterSkip: () => null,
        sleep: async () => {},
        apiGet: async url => url.endsWith('/meta')
            ? {illustTitle: 'Example', userId: 2, illustType: 0}
            : {urls: ['https://i.pximg.net/example.jpg']},
        fetch: async (url, options) => {
            if (url.endsWith('/api/collections')) return {ok: true, json: async () => ({collections: []})};
            const payload = JSON.parse(options.body);
            requests.push(payload);
            const action = payload.other.pathOverflowAction;
            return {ok: action === 'DEFAULT_NAME', status: action === 'DEFAULT_NAME' ? 200 : 409,
                json: async () => action === 'ASK' ? {
                    code: 'DOWNLOAD_PATH_ACTION_REQUIRED',
                    pathProblem: {originalPath: '/long', truncatedPath: '/short', defaultPath: '/id'}
                } : action === 'CANCEL' ? {code: 'DOWNLOAD_PATH_CANCELLED'} : {alreadyDownloaded: true}};
        }
    });
    for (const file of ['pixiv-batch-alt/alt-state.js', 'pixiv-batch-alt/alt-settings.js',
        'pixiv-batch-alt/alt-engine.js', 'pixiv-batch-alt/alt-engine-workers.js', 'pixiv-batch/batch-path-actions.js']) {
        vm.runInContext(fs.readFileSync(path.join(root, file), 'utf8'), context, {filename: file});
    }
    vm.runInContext("isAdmin = true; state.settings.skipHistory = false; state.settings.interval = 0;", context);
    context.window.PixivBatch.queueTypes = {get: () => ({process: context.processIllustItem})};
    const state = vm.runInContext('state', context);
    async function run(ids) {
        state.queue = ids.map(id => ({id: String(id), kind: 'illust', status: 'idle'}));
        await context.start();
        for (let i = 0; state.isRunning && i < 100; i++) await new Promise(resolve => setImmediate(resolve));
        assert.equal(state.isRunning, false);
        return state.queue;
    }
    return {context, state, requests, run, prompts: () => prompts};
}

test('新版设置保存与重载保留默认行为，未知值回退询问', () => {
    const h = harness(null);
    assert.equal(h.state.settings.pathOverflowAction, 'ASK');
    h.state.settings.pathOverflowAction = 'DEFAULT_NAME';
    h.context.saveSettings();
    h.state.settings.pathOverflowAction = 'ASK';
    h.context.loadSettings();
    assert.equal(h.state.settings.pathOverflowAction, 'DEFAULT_NAME');
    h.state.settings.pathOverflowAction = 'INVALID';
    h.context.saveSettings();
    h.context.loadSettings();
    assert.equal(h.state.settings.pathOverflowAction, 'ASK');
});

test('新版真实 worker 提交询问并按批次记住取消，下一批重新询问', async () => {
    const h = harness({value: 'CANCEL', remember: true});
    const queue = await h.run([1, 2]);
    assert.equal(h.prompts(), 1);
    assert.ok(queue.every(item => item.status === 'skipped'
        && item.statusMessageKey === 'batch:path.overflow.cancelled'));
    assert.equal(h.state.settings.pathOverflowAction, 'ASK');
    await h.run([3]);
    assert.equal(h.prompts(), 2);
});

test('新版暂不处理保留暂停作品，预设短名直接随请求提交', async () => {
    const h = harness(null);
    const queue = await h.run([1]);
    assert.equal(queue[0].status, 'paused');
    assert.equal(queue[0].statusMessageKey, 'batch:path.overflow.waiting');
    h.state.settings.pathOverflowAction = 'DEFAULT_NAME';
    await h.run([2]);
    assert.equal(h.requests.at(-1).other.pathOverflowAction, 'DEFAULT_NAME');
    assert.equal(h.prompts(), 1);
});

test('新版下载设置经共用来源模块存入计划快照', () => {
    const h = harness(null);
    let initializer, source;
    h.context.window.PixivBatch.scheduleSources = {registerModule: (_url, value) => { initializer = value; }};
    h.context.document = {getElementById: id => id === 'user-id-input' ? {value: '42'} : null};
    h.context.getSearchFiltersFromUI = () => ({});
    h.context.defaultNovelTranslateLang = () => 'en-US';
    h.context.syncSettings = h.context.saveSettings;
    vm.runInContext(fs.readFileSync(path.join(root, 'pixiv-batch/pixiv-schedule-sources.js'), 'utf8'), h.context);
    initializer({descriptors: [{sourceType: 'user-new'}], registerSource: (_id, value) => { source = value; }});
    for (const value of ['ASK', 'TRUNCATE', 'DEFAULT_NAME', 'CANCEL']) {
        h.state.settings.pathOverflowAction = value;
        const snapshot = JSON.parse(JSON.stringify(source.capture({mode: 'user'}).params));
        assert.equal(snapshot.download.pathOverflowAction, value);
    }
});
