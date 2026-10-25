'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const {resolve} = require('node:path');
const vm = require('node:vm');
const root = resolve(__dirname, '../../main/resources/static/pixiv-batch/modes');

function page() {
    let finish, current = true;
    const created = [], revoked = [], images = new Map();
    const context = vm.createContext({
        window: {PixivBatch: {modes: {}, queueTypes: {
            resolveTypeForMode: kind => kind,
            acquisition: () => ({thumbnailEndpoint: url => '/thumbnail?url=' + encodeURIComponent(url)}),
            prepareAcquisitionRequest: (_kind, _mode, url) => ({url, init: {}, assertCurrent() {
                if (!current) throw new Error('withdrawn');
            }})
        }}},
        document: {addEventListener() {}, getElementById(id) {
            if (!images.has(id)) images.set(id, {src: '', innerHTML: '', isConnected: true,
                style: {}, value: '', disabled: false, addEventListener() {}, querySelectorAll: () => [],
                insertAdjacentHTML(_where, html) { this.innerHTML += html; }});
            return images.get(id);
        }},
        userState: {kind: 'demo', renderToken: 1, activeBlobUrls: []},
        seriesState: {kind: 'demo', renderToken: 1, activeBlobUrls: []},
        quickState: {kind: 'demo', renderToken: 1, blobUrls: []},
        quickInner: {kind: 'demo', renderToken: 1, blobUrls: []},
        defaultSearchFilters: () => ({}), URLSearchParams, console,
        esc: value => String(value), bt: key => key,
        URL: {createObjectURL() { const url = 'blob:' + (created.length + 1); created.push(url); return url; },
            revokeObjectURL(url) { revoked.push(url); }},
        fetch: async () => ({ok: true, blob: () => new Promise(resolve => { finish = resolve; })})
    });
    for (const file of ['search.js', 'user-data.js', 'user-view.js', 'series-data.js',
        'series-view.js', 'quick-fetch-outer.js', 'quick-fetch-inner.js']) {
        vm.runInContext(readFileSync(resolve(root, file), 'utf8'), context, {filename: file});
    }
    vm.runInContext("searchState.kind = 'demo'; searchState.requestSeq = 1", context);
    return {context, created, revoked, images,
        async finish() { for (let i = 0; !finish && i < 20; i++) await Promise.resolve();
            assert.ok(finish); finish({}); },
        withdraw() { current = false; }};
}

async function searchPage() {
    const p = page(), c = p.context, callbacks = {}, statuses = [];
    c.state = {settings: {searchKind: 'demo'}};
    c.appMode = 'solo'; c.isAdmin = false;
    c.document.querySelector = () => ({value: 'all'});
    c.document.getElementById('search-word').value = 'query';
    c.document.getElementById('batch-start-page').value = '1';
    c.document.getElementById('batch-end-page').value = '2';
    const batch = c.window.PixivBatch, boundary = new Error('mode binding complete');
    c.window.addEventListener = () => {};
    c.stopSchedulePolling = () => {};
    c.PixivUserscriptDetection = {probe() {}};
    c.SCRIPT_ID_TOOLBOX = 'toolbox'; c.SCRIPT_ID_ALL_IN_ONE = 'all';
    c.importCookieViaScript = () => {};
    batch.layout = {applyStoredLayout() {}};
    batch.modes.user = {handleUserModeControlChange() {}};
    batch.settings = {bindDelegatedSettingAutosave() {}};
    batch.modeControls = {bind() {}, renderAll() {}, syncType() {}, selection: () => null,
        onChange() { throw boundary; }};
    Object.assign(batch.queueTypes, {bootstrap() {}, acquisitionList: () => [{type: 'demo'}],
        resolveSelectionForMode: () => 'demo',
        acquisition: () => ({buildRequest: () => ({endpoint: '/search'}),
            buildRangeRequest: () => ({endpoint: '/range'})}),
        prepareAcquisitionRequest: (_kind, _mode, url) => ({url, init: {},
            assertCurrent() {}, isCurrent: () => true})});
    vm.runInContext(readFileSync(resolve(root, '../batch-init.js'), 'utf8'), c);
    for (const name of ['bindDeclarativeActions', 'detectMode', 'detectAuthState', 'loadServerState',
        'applyCookieHint', 'updateAuthButtons', 'setCookieFmt', 'applyCookieDependentUi',
        'applySearchKindUI', 'applyNovelSettingsVisibility', 'resetPreviewCollapse',
        'renderSearchResults', 'renderSearchPagination', 'updateBatchLimitNote',
        'updateScheduleFetchLimitVisibility']) c[name] = () => {};
    c.storeGet = c.getCookieFmt = () => '';
    c.bindKindSwitcher = (id, _key, callback) => { callbacks[id] = callback; };
    await assert.rejects(c.init(), error => error === boundary);
    c.setStatus = (message, tone) => statuses.push({message, tone});
    c.statusAcquisitionOwner = () => null;
    c.hasExtraSearchFilter = () => false;
    c.applyCurrentSearchFilters = async () => ({});
    let finish;
    c.fetch = () => new Promise(resolve => { finish = resolve; });
    return {...p, statuses, clear: {
        submode: () => c.applySubmodeUI('search', {clear: true}),
        kind: () => callbacks['search-kind-switcher']('demo'),
        source: () => c.clearSearchPreviewForSourceChange()
    }, finish(ok = true) { assert.ok(finish); finish({ok, json: async () => ({items: [{id: 'old'}], total: 1})}); }};
}

for (const action of ['performSearch', 'runBatchFetch']) {
    for (const reason of ['submode', 'kind', 'source']) {
        test(`${action} 在 ${reason} 清空后拒绝旧结果，并恢复批量获取入口`, async () => {
            for (const ok of [true, false]) {
                const p = await searchPage(), pending = p.context[action](1);
                p.clear[reason](); p.finish(ok); await pending;
                assert.equal(vm.runInContext('searchState.results.length', p.context), 0);
                assert.equal(vm.runInContext('searchState.rawResults.length', p.context), 0);
                assert.deepEqual(p.statuses, []);
                assert.equal(p.context.document.getElementById('btn-batch-fetch').disabled, false);
            }
        });
    }
    test(`${action} 当前请求仍发布结果并恢复获取入口`, async () => {
        const p = await searchPage(), pending = p.context[action](1);
        p.finish(); await pending;
        assert.equal(vm.runInContext('searchState.results[0].id', p.context), 'old');
        assert.equal(p.statuses.at(-1).tone, 'success');
        assert.equal(p.context.document.getElementById('btn-batch-fetch').disabled, false);
    });
}

test('关注列表重新筛选后，旧头像不得写到相同下标的新用户', async () => {
    const p = page();
    p.context.renderQuickFollowingGrid([{userId: 'old', profileImageUrl: 'https://example.invalid/old'}]);
    p.context.renderQuickFollowingGrid([{userId: 'new'}]);
    await p.finish();
    for (let i = 0; i < 10; i++) await Promise.resolve();
    assert.deepEqual(p.created, []);
    assert.equal(p.context.document.getElementById('quick-follow-ava-0').innerHTML, '');
});

for (const [render, item] of [
    ['renderQuickFollowingGrid', {userId: '42', profileImageUrl: 'https://example.invalid/avatar'}],
    ['renderQuickCollectionGrid', {id: '42', coverUrl: 'https://example.invalid/cover'}]
]) {
    test(`${render} 清理预览后不发布迟到图片`, async () => {
        const p = page();
        p.context[render]([item]);
        p.context.cleanupQuickBlobUrls();
        await p.finish();
        for (let i = 0; i < 10; i++) await Promise.resolve();
        assert.deepEqual(p.created, []);
    });
}

const modes = [
    ['search', 'loadSingleThumbnail', 'cleanupBlobUrls', 'searchState.activeBlobUrls', 'thumb-img-0'],
    ['user', 'loadSingleUserThumbnail', 'cleanupUserBlobUrls', 'userState.activeBlobUrls', 'user-thumb-img-0'],
    ['series', 'loadSingleSeriesThumbnail', 'cleanupSeriesBlobUrls', 'seriesState.activeBlobUrls', 'series-thumb-img-0'],
    ['quick', 'loadQuickSingleThumbnail', 'cleanupQuickBlobUrls', 'quickState.blobUrls', 'quick-thumb-img-0'],
    ['inner', 'loadQuickInnerSingleThumbnail', 'quickCleanupInnerBlobUrls', 'quickInner.blobUrls', 'quick-inner-img-0']
];
for (const [mode, load, cleanup, store, imageId] of modes) {
    const start = p => p.context[load]({id: '42', kind: 'demo', thumbnailUrl: 'https://example.invalid/image'},
        ...(mode === 'quick' ? ['quick', 0, 1] : [0, 1]));
    test(`${mode} 缩略图在预览清理后返回时不创建失去归属的 Blob URL`, async () => {
        const p = page(), pending = start(p);
        p.context[cleanup]();
        await p.finish(); await pending;
        assert.deepEqual(p.created, []);
        assert.equal(vm.runInContext(store, p.context).length, 0);
        assert.equal(p.images.get(imageId).src, '');
    });
    test(`${mode} 当前缩略图可显示并在清理时释放，类型撤回不能创建 URL`, async () => {
        const p = page(), pending = start(p);
        await p.finish(); await pending;
        assert.equal(p.images.get(imageId).src, 'blob:1');
        assert.deepEqual(Array.from(vm.runInContext(store, p.context)), ['blob:1']);
        p.context[cleanup]();
        assert.deepEqual(p.revoked, ['blob:1']);
        const withdrawn = page(), old = start(withdrawn);
        withdrawn.withdraw(); await withdrawn.finish(); await old;
        assert.deepEqual(withdrawn.created, []);
    });
}
