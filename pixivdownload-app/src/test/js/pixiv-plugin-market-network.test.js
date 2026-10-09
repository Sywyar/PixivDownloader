'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

test('切换仓库终止旧请求，旧请求结束不能清掉新请求的取消能力', async () => {
    const requests = [];
    const market = {};
    const context = {window: {PixivPluginMarket: market}, AbortController,
        fetch(url, options) {
            return new Promise((resolve, reject) => {
                requests.push({url, options, resolve, reject});
                options.signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')));
            });
        }};
    vm.runInNewContext(fs.readFileSync(path.join(__dirname,
        '../../main/resources/static/plugin-market/plugin-market-api.js'), 'utf8'), context);
    const first = market.api.fetchCatalog('official');
    const rejected = assert.rejects(first, {name: 'AbortError'});
    const second = market.api.fetchCatalog('community');
    assert.equal(requests[0].options.signal.aborted, true);
    await rejected;
    assert.equal(requests[1].options.signal.aborted, false);
    const secondRejected = assert.rejects(second, {name: 'AbortError'});
    market.api.cancelCatalog();
    await secondRejected;
    const third = market.api.fetchCatalog('official');
    requests[2].resolve({ok: true, json: async () => ({entries: [{pluginId: 'sample'}]})});
    assert.equal((await third).entries[0].pluginId, 'sample');
    market.api.cancelCatalog();
    assert.equal(requests[2].options.signal.aborted, false);
});
