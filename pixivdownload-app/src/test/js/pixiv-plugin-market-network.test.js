'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

test('插件状态读取保持同源凭据并刷新独立恢复面板', async () => {
    const requests = [];
    const status = {recoveryMode: true, recoveryReasons: [{pluginId: 'required', status: 'MISSING_REQUIRED'}]};
    let refreshed = 0;
    const market = {recovery: {refresh: async () => { refreshed++; }}};
    vm.runInNewContext(fs.readFileSync(path.join(__dirname,
        '../../main/resources/static/plugin-market/plugin-market-api.js'), 'utf8'), {
        window: {PixivPluginMarket: market},
        fetch: async (url, options) => {
            requests.push({url, options});
            return {ok: true, json: async () => status};
        }
    });
    assert.equal(await market.api.fetchPluginStatus(), status);
    assert.equal(refreshed, 1);
    assert.equal(requests.length, 1);
    assert.equal(requests[0].url, '/api/plugins/status');
    assert.equal(requests[0].options.credentials, 'same-origin');
});

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

test('读取取消覆盖正文解析期间的成功和 HTTP 错误', async () => {
    for (const ok of [true, false]) {
        const market = {};
        let finish;
        vm.runInNewContext(fs.readFileSync(path.join(__dirname,
            '../../main/resources/static/plugin-market/plugin-market-api.js'), 'utf8'), {
            window: {PixivPluginMarket: market},
            fetch: async () => ({ok, status: ok ? 200 : 503, json: () => new Promise(resolve => { finish = resolve; })})
        });
        const controller = new AbortController();
        const pending = market.api.fetchRepositories(controller.signal);
        await new Promise(resolve => setImmediate(resolve));
        controller.abort(); finish({enabled: true, error: 'obsolete'});
        await assert.rejects(pending, {name: 'AbortError'});
    }
});

test('离页前未完成的安装预览和确认不得继续提交，恢复也不重放', async () => {
    for (const waitAt of ['preview', 'confirmation', 'restored']) {
        const events = {};
        const sandbox = {console, window: null, clearTimeout,
            document: {readyState: 'loading', addEventListener() {}, getElementById: () => null},
            addEventListener(name, handler) { events[name] = handler; }}; sandbox.window = sandbox;
        vm.createContext(sandbox);
        for (const part of ['core', 'data', 'init'])
            vm.runInContext(fs.readFileSync(path.join(__dirname,
                '../../main/resources/static/plugin-market/plugin-market-' + part + '.js'), 'utf8'), sandbox);
        const market = sandbox.PixivPluginMarket;
        const preview = {fingerprint: 'a'.repeat(64), packages: [], conflicts: []};
        const calls = [];
        let finish;
        market.api = {
            cancelCatalog() {},
            previewInstall: () => waitAt === 'preview' ? new Promise(resolve => { finish = () => resolve(preview); }) : Promise.resolve(preview),
            installPlugin: async () => { calls.push('install'); return {kind: 'install', body: {accepted: true}}; }
        };
        sandbox.PixivFeedback = {confirm: () => {
            calls.push('confirm');
            return waitAt !== 'preview' ? new Promise(resolve => { finish = () => resolve(true); }) : Promise.resolve(true);
        }};
        const pending = market.installPluginWithConfirmation('repo', 'plugin', '1.2.3');
        await new Promise(resolve => setImmediate(resolve));
        events.pagehide();
        if (waitAt === 'restored') events.pageshow({persisted: true});
        finish();
        const response = await pending;
        assert.equal(response.body.code, 'CANCELLED');
        assert.deepEqual(calls, waitAt === 'preview' ? [] : ['confirm']);
        events.pageshow({persisted: true});
        await new Promise(resolve => setImmediate(resolve));
        assert.ok(!calls.includes('install'));
    }
});
