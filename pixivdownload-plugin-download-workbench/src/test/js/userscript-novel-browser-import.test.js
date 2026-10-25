'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const repositoryRoot = path.resolve(__dirname, '../../../..');
const sharedPath = path.join(repositoryRoot, 'scripts', 'shared', 'novel-browser-import.js');

test('回环后端将浏览器取得的小说响应交换为短期下载票据', async () => {
    const requests = [];
    const sandbox = {
        URL,
        GM_xmlhttpRequest(options) {
            requests.push(options);
            queueMicrotask(() => {
                if (requests.length === 1) {
                    options.onload({status: 200, responseText: '{"token":"upload-token"}'});
                } else {
                    options.onload({status: 200, responseText: '{"fetchToken":"fetch-token"}'});
                }
            });
        }
    };
    vm.createContext(sandbox);
    const source = fs.readFileSync(sharedPath, 'utf8');
    vm.runInContext(`${source}\nglobalThis.browserImport = NovelBrowserImport;`, sandbox);

    const token = await sandbox.browserImport.importResponse(
        'http://localhost:6999', 42, '{"error":false,"body":{"id":"42"}}');

    assert.equal(token, 'fetch-token');
    assert.equal(requests.length, 2);
    assert.equal(requests[0].url, 'http://localhost:6999/api/novel/browser-import/token');
    assert.equal(requests[0].anonymous, true);
    assert.equal(requests[1].url, 'http://localhost:6999/api/novel/browser-import/42');
    assert.equal(requests[1].anonymous, true);
    assert.equal(requests[1].headers.Origin, 'http://localhost:6999');
    assert.equal(requests[1].headers['X-Novel-Import-Token'], 'upload-token');
    assert.equal(requests[1].data, '{"error":false,"body":{"id":"42"}}');
});

test('远端后端不接收浏览器取得的小说响应', async () => {
    const sandbox = {
        URL,
        GM_xmlhttpRequest() {
            throw new Error('remote backend must not receive browser-fetched novel content');
        }
    };
    vm.createContext(sandbox);
    const source = fs.readFileSync(sharedPath, 'utf8');
    vm.runInContext(`${source}\nglobalThis.browserImport = NovelBrowserImport;`, sandbox);

    assert.equal(await sandbox.browserImport.importResponse(
        'https://downloads.example', 42, '{}'), null);
});

test('本机后端缺少导入端点时回退普通下载', async () => {
    let requests = 0;
    const sandbox = {
        URL,
        GM_xmlhttpRequest(options) {
            requests++;
            queueMicrotask(() => options.onload({status: 404, responseText: '{}'}));
        }
    };
    vm.createContext(sandbox);
    const source = fs.readFileSync(sharedPath, 'utf8');
    vm.runInContext(`${source}\nglobalThis.browserImport = NovelBrowserImport;`, sandbox);

    assert.equal(await sandbox.browserImport.importResponse(
        'http://127.0.0.1:6999', 42, '{}'), null);
    assert.equal(requests, 1);
});

test('队列在取得导入令牌期间清空时不再上传小说正文', async () => {
    const requests = [];
    const context = vm.createContext({URL, GM_xmlhttpRequest: options => requests.push(options)});
    vm.runInContext(fs.readFileSync(sharedPath, 'utf8') + '\nthis.browserImport = NovelBrowserImport;', context);
    const controller = new AbortController();
    const operation = context.browserImport.importResponse('http://localhost:6999', 42, '{}', controller.signal);
    const rejected = assert.rejects(operation, {name: 'AbortError'});
    controller.abort();
    requests[0].onload({status: 200, responseText: '{"token":"upload-token"}'});
    await rejected;
    assert.equal(requests.length, 1);
});

test('四个独立下载脚本在小说元数据中保留导入票据', async () => {
    const scripts = [
        'Pixiv 单作品图片下载器(Java后端版).user.js',
        'Pixiv User 批量下载器(User Batch).user.js',
        'Pixiv URL 批量导入单作品下载器(URL Batch).user.js',
        'Pixiv 页面批量下载器(Page Scrape).user.js'
    ];
    for (const name of scripts) {
        const source = fs.readFileSync(path.join(repositoryRoot, name), 'utf8');
        const requests = [], imports = [];
        const context = vm.createContext({
            serverBase: 'http://localhost:6999',
            GM_xmlhttpRequest: options => requests.push(options),
            buildNovelMetaFromPixivBody: id => ({novelId: Number(id)}),
            NovelBrowserImport: {importResponse: async (...args) => { imports.push(args); return 'fetch-ticket'; }}
        });
        vm.runInContext(fs.readFileSync(path.join(repositoryRoot, 'scripts/shared/download-queue-runner.js'), 'utf8'), context);
        if (name.includes('Java')) {
            vm.runInContext(source.slice(source.indexOf('    async function getNovelMeta('),
                source.indexOf('    // 小说三态判重')), context);
        } else {
            vm.runInContext(source.slice(source.indexOf('    const Api = {'),
                source.indexOf('    // >>> SHARED:sse-manager.js')) + '\nthis.getNovelMeta = id => Api.getNovelMeta(id);', context);
        }
        const operation = context.getNovelMeta('42');
        const responseText = '{"body":{"id":"42","content":"正文"}}';
        requests[0].onload({status: 200, responseText});
        const meta = await operation;
        assert.equal(meta.novelId, 42);
        assert.equal(meta.fetchToken, 'fetch-ticket');
        assert.deepEqual(Array.from(imports[0].slice(0, 3)), ['http://localhost:6999', '42', responseText]);
    }
});
