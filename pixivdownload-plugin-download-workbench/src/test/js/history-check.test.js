'use strict';

const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const test = require('node:test');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../../../..');
const staticRoot = path.resolve(__dirname, '../../main/resources/static');

function loadWeb(file, respond) {
    const context = {state: {settings: {verifyHistoryFiles: true}}, BASE: '',
        bt: key => key, fetch: respond};
    vm.createContext(context);
    vm.runInContext(fs.readFileSync(path.join(staticRoot, file), 'utf8') + '\nthis.check = checkDownloaded;', context);
    return context.check;
}

function loadUserscript(file, respond, novel = false) {
    const source = fs.readFileSync(path.join(root, file), 'utf8');
    const single = file.includes('Java后端版');
    const name = novel ? 'checkNovelDownloaded' : 'checkDownloaded';
    const start = source.indexOf((single ? '    async function ' : '        ') + name + '(');
    const end = single ? source.indexOf('\n    }', start) + 6 : source.indexOf(novel ? '\n        sendNovelDownloadRequest' : '\n        get', start);
    const method = source.slice(start, end).trim();
    const context = {GM_xmlhttpRequest: respond, serverBase: 'http://localhost',
        CONFIG: {CHECK_DOWNLOADED_URL: 'http://localhost/api/downloaded'},
        getDownloadedCheckBase: () => 'http://localhost/api/downloaded', handleUnauthorized() {}, t: key => key};
    vm.createContext(context);
    vm.runInContext(single ? method + '; this.check = ' + name + ';' : 'this.check = ({' + method + '}).' + name + ';', context);
    return context.check;
}

for (const file of ['pixiv-batch/batch-download-artwork.js', 'pixiv-batch-alt/alt-engine.js']) {
    test(file + '：仅明确无记录时允许继续，服务错误和损坏响应中止判重', async () => {
        let result = {status: 400};
        const check = loadWeb(file, async () => result);
        assert.equal(await check(42), null);
        result = {status: 200, json: async () => ({artworkId: 42, deleted: true})};
        assert.equal((await check(42)).deleted, true);
        for (const status of [401, 403, 404, 503]) {
            result = {status};
            await assert.rejects(() => check(42));
        }
        result = {status: 200, json: async () => ({})};
        await assert.rejects(() => check(42));
        await assert.rejects(loadWeb(file, async () => { throw new Error('offline'); })(42));
    });
}

for (const file of ['Pixiv User 批量下载器(User Batch).user.js',
    'Pixiv URL 批量导入单作品下载器(URL Batch).user.js', 'Pixiv 页面批量下载器(Page Scrape).user.js',
    'Pixiv 单作品图片下载器(Java后端版).user.js']) {
    test(file + '：历史检查失败不能降级成未下载', async () => {
        let respond = request => request.onload({status: 400});
        const check = loadUserscript(file, request => respond(request));
        assert.equal(await check(42, true), null);
        respond = request => request.onload({status: 200, responseText: '{"artworkId":42,"deleted":true}'});
        assert.equal((await check(42)).deleted, true);
        for (const status of [401, 403, 404, 503]) {
            respond = request => request.onload({status});
            await assert.rejects(() => check(42));
        }
        for (const responseText of ['{}', 'invalid json']) {
            respond = request => request.onload({status: 200, responseText});
            await assert.rejects(() => check(42));
        }
        for (const event of ['onerror', 'ontimeout']) {
            respond = request => request[event]();
            await assert.rejects(() => check(42));
        }
    });
}

for (const file of ['Pixiv User 批量下载器(User Batch).user.js',
    'Pixiv URL 批量导入单作品下载器(URL Batch).user.js', 'Pixiv 页面批量下载器(Page Scrape).user.js']) {
    test(file + '：历史查询失败结束当前条目且不发起下载', async () => {
        const source = fs.readFileSync(path.join(root, file), 'utf8');
        const start = source.indexOf('        async _processSingle(');
        const end = source.indexOf('\n        }', start) + 10;
        let saves = 0;
        const context = {Api: {
            checkDownloaded: async () => { throw new Error('history unavailable'); },
            getArtworkMeta: async () => assert.fail('history error must stop download')
        }};
        vm.createContext(context);
        vm.runInContext('this.process = ({' + source.slice(start, end) + '})._processSingle;', context);
        const item = {id: '42', title: 'fixture', status: 'downloading'};
        await context.process.call({queue: [item], skipHistory: true,
            globalSettings: {skipHistory: true},
            sse: {close() {}}, ui: {renderQueue() {}, setStatus() {}, setCurrent() {}},
            updateStats() {}, saveToStorage() { saves++; }
        }, {idx: 0, item});
        assert.equal(item.status, 'failed');
        assert.ok(item.endTime);
        assert.ok(saves > 0);
    });
}

for (const file of ['Pixiv User 批量下载器(User Batch).user.js',
    'Pixiv URL 批量导入单作品下载器(URL Batch).user.js', 'Pixiv 页面批量下载器(Page Scrape).user.js',
    'Pixiv 单作品图片下载器(Java后端版).user.js']) {
    test(file + '：小说判重保留软删除状态，异常响应中止下载', async () => {
        let response = {status: 200, responseText: '{"downloaded":false,"deleted":false}'};
        const check = loadUserscript(file, request => request.onload(response), true);
        assert.equal(await check(42), null);
        response.responseText = '{"downloaded":true,"deleted":true}';
        assert.equal((await check(42)).deleted, true);
        for (const status of [400, 401, 404, 503]) {
            response = {status};
            await assert.rejects(() => check(42));
        }
        for (const responseText of ['{}', 'invalid json']) {
            response = {status: 200, responseText};
            await assert.rejects(() => check(42));
        }
        for (const event of ['onerror', 'ontimeout']) {
            await assert.rejects(loadUserscript(file, request => request[event](), true)(42));
        }
    });
}
