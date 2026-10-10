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

test('历史投影刷新在上下文失效时停止，目录换代时重取首页，重复游标时失败', async () => {
    for (const scenario of ['stale', 'generation', 'cycle', 'failure']) {
        const market = {};
        vm.runInNewContext(fs.readFileSync(path.join(__dirname,
            '../../main/resources/static/plugin-market/plugin-market-api.js'), 'utf8'),
        {window: {PixivPluginMarket: market}});
        const first = {versionsGeneration: 'old', packages: [{version: '2.0'}], nextVersionCursor: 'older'};
        const previous = {...first, packages: [{version: '2.0'}, {version: '1.0'}]};
        let current = true;
        const cursors = [];
        market.api.fetchPluginDetail = async (repository, plugin, options) => {
            cursors.push(options?.cursor);
            if (cursors.length === 1) return structuredClone(first);
            if (scenario === 'stale') current = false;
            if (scenario === 'failure') throw new Error('Network unavailable');
            if (scenario === 'generation') return {versionsGeneration: 'new', packages: [{version: '3.0'}]};
            return {versionsGeneration: 'old', packages: [], nextVersionCursor: 'older'};
        };
        const pending = market.api.refreshPluginDetail('repo', 'sample', previous, () => current);
        if (scenario === 'cycle' || scenario === 'failure') {
            await assert.rejects(pending);
        } else {
            const refreshed = await pending;
            if (scenario === 'stale') assert.equal(refreshed, null);
            else {
                assert.equal(refreshed.versionsGeneration, 'new');
                assert.deepEqual(refreshed.packages.map(pkg => pkg.version), ['3.0']);
            }
        }
        assert.deepEqual(cursors, scenario === 'generation' ? [undefined, 'older', undefined] : [undefined, 'older']);
        assert.equal(previous.packages.length, 2, '失败或过期响应不能修改原详情');
    }
});
