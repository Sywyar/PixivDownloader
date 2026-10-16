const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

test('小说工作台历史查询错误使当前条目失败且不提交下载', async () => {
    for (const response of [{ok: false, status: 503}, {ok: true, json: async () => ({})}, null]) {
        const shared = {novelAcquisitionCredentialHeaders: () => ({})};
        const context = {
            window: {PixivBatch: {queueTypes: {registerSubmodule: init => init(shared)}, pathActions: {handleError: () => false}}},
            BASE: '', state: {settings: {skipHistory: true}}, bt: key => key,
            getCookie: () => '', setCurrent() {}, renderQueue() {}, updateStats() {}, saveQueue() {},
            fetch: async url => {
                assert.ok(url.endsWith('/downloaded'), '历史失败后不能请求下载或抓取正文');
                if (response === null) throw new Error('offline');
                return response;
            }
        };
        vm.createContext(context);
        vm.runInContext(fs.readFileSync(path.join(__dirname, '../../main/resources/static/pixiv-novel-download/novel-queue-download.js'), 'utf8'), context);
        const item = {id: 'n7', status: 'downloading'};
        await assert.rejects(shared.processNovelItem(item, {assertActive() {}, signal: new AbortController().signal}));
        assert.equal(item.status, 'failed');
        assert.ok(item.endTime);
    }
});

test('小说下载完成保存消息键，重新渲染不保留下载时语言', async () => {
    const shared = {novelAcquisitionCredentialHeaders: () => ({})};
    const context = {
        window: {PixivBatch: {queueTypes: {registerSubmodule: init => init(shared)}, pathActions: {
            submit: async () => ({res: {ok: true, status: 200}, data: {}}), handleError: () => false
        }}},
        BASE: '', state: {settings: {}}, isAdmin: false, STATUS_TIMEOUT_MS: 10000,
        bt: key => 'initial-language:' + key,
        getCookie: () => '', setCurrent() {}, renderQueue() {}, updateStats() {}, saveQueue() {},
        setTimeout: callback => setTimeout(callback, 0), clearTimeout,
        evaluateDownloadFilterSkip: () => null,
        refreshBatchCollections: async () => ({collectionId: null}),
        defaultNovelTranslateLang: () => 'fixture',
        fetchJsonWithProgress: async () => ({ok: true, json: async () => ({title: 'Novel'})}),
        fetch: async () => ({ok: true, json: async () => ({completed: true, failed: false})})
    };
    vm.createContext(context);
    vm.runInContext(fs.readFileSync(path.join(__dirname,
        '../../main/resources/static/pixiv-novel-download/novel-queue-download.js'), 'utf8'), context);
    const item = {id: 'n7', status: 'downloading', statusMessageKey: 'stale:key'};
    await shared.processNovelItem(item, {assertActive() {}, signal: new AbortController().signal});
    const persisted = JSON.parse(JSON.stringify(item));
    assert.equal(persisted.status, 'completed');
    assert.equal(persisted.statusMessageKey, 'batch:queue.message.completed');
    assert.equal(persisted.lastMessage, '');
});

for (const [scenario, expectedStatus] of [
    ['history', 'skipped'], ['filter', 'skipped'], ['already-downloaded', 'skipped'],
    ['completed', 'completed'], ['backend-failed', 'failed'], ['timeout', 'failed'], ['exception', 'failed']
]) {
    test(`小说下载 ${scenario} 在保存终态后刷新队列`, async () => {
        const events = [];
        const item = {id: 'n42', status: 'downloading'};
        const shared = {novelAcquisitionCredentialHeaders: () => ({})};
        const snapshot = event => events.push({event, ...JSON.parse(JSON.stringify(item))});
        const context = {
            window: {PixivBatch: {queueTypes: {registerSubmodule: init => init(shared)}, pathActions: {
                submit: async () => {
                    if (scenario === 'exception') throw new Error('offline');
                    return {res: {ok: true, status: 200}, data: {alreadyDownloaded: scenario === 'already-downloaded'}};
                }, handleError: () => false
            }}},
            BASE: '', state: {settings: {skipHistory: scenario === 'history'}}, isAdmin: false,
            STATUS_TIMEOUT_MS: scenario === 'timeout' ? 0 : 10000,
            bt: key => key, getCookie: () => '', setCurrent() {},
            updateStats: () => snapshot('stats'), saveQueue: () => snapshot('save'), renderQueue: () => snapshot('render'),
            setTimeout: callback => setTimeout(callback, 0), clearTimeout,
            evaluateDownloadFilterSkip: () => scenario === 'filter' ? 'filtered' : null,
            refreshBatchCollections: async () => ({collectionId: null}), defaultNovelTranslateLang: () => 'fixture',
            fetchJsonWithProgress: async () => ({ok: true, json: async () => ({title: 'Novel'})}),
            fetch: async url => {
                if (scenario === 'history') {
                    assert.equal(url, '/api/novel/42/downloaded');
                    return {ok: true, json: async () => ({downloaded: true, deleted: false})};
                }
                assert.equal(url, '/api/novel/status/42');
                return {ok: true, json: async () => ({completed: true, failed: scenario === 'backend-failed'})};
            }
        };
        vm.createContext(context);
        vm.runInContext(fs.readFileSync(path.join(__dirname,
            '../../main/resources/static/pixiv-novel-download/novel-queue-download.js'), 'utf8'), context);
        const running = shared.processNovelItem(item, {assertActive() {}, signal: new AbortController().signal});
        if (scenario === 'exception') await assert.rejects(running, /offline/);
        else await running;
        assert.equal(item.status, expectedStatus);
        assert.ok(Number.isFinite(Date.parse(item.endTime)));
        const terminal = events.filter(event => event.endTime);
        assert.deepEqual(terminal.map(event => event.event), ['stats', 'save', 'render']);
        for (const event of terminal) {
            assert.equal(event.status, expectedStatus);
            assert.equal(event.endTime, item.endTime);
        }
    });
}
