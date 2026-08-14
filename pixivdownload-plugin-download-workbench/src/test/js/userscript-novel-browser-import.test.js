'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const repositoryRoot = path.resolve(__dirname, '../../../..');
const sharedPath = path.join(repositoryRoot, 'scripts', 'shared', 'novel-browser-import.js');

test('browser import exchanges a loopback Pixiv response for a short-lived fetch ticket', async () => {
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
    assert.equal(requests[1].headers['X-Novel-Import-Token'], 'upload-token');
    assert.equal(requests[1].data, '{"error":false,"body":{"id":"42"}}');
});

test('browser import is skipped for non-loopback backends', async () => {
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

test('browser import falls back when the local backend predates the import endpoint', async () => {
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

test('all novel-capable standalone scripts attach the imported fetch ticket', () => {
    const scripts = [
        'Pixiv 单作品图片下载器(Java后端版).user.js',
        'Pixiv User 批量下载器(User Batch).user.js',
        'Pixiv URL 批量导入单作品下载器(URL Batch).user.js',
        'Pixiv 页面批量下载器(Page Scrape).user.js'
    ];
    for (const name of scripts) {
        const source = fs.readFileSync(path.join(repositoryRoot, name), 'utf8');
        assert.match(source, /SHARED:novel-browser-import\.js/);
        assert.match(source,
            /NovelBrowserImport\.importResponse\(serverBase, novelId, (?:res|response)\.responseText\)/);
        assert.match(source, /fetchToken:\s*meta\.fetchToken\s*\|\|\s*null/);
    }
});
