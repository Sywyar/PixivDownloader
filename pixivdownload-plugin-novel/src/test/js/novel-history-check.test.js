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
