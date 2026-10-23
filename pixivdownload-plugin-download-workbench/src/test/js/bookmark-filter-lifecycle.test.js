'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const {resolve} = require('node:path');
const vm = require('node:vm');
const {harness, typeDescriptor, manifest, waitUntil} = require('./batch-queue-types-fixture');
const root = resolve(__dirname, '../../main/resources/static');
const initializer = `(context => ({descriptor: {process() {}, filters: {counts: {
    bookmarkCountFetch(id) {
        testState.receiverType = this.type;
        return testState.fetchCount(id, context.manifest.pluginGeneration);
    }
}}}}))`;

async function page(alt) {
    const h = harness([manifest(1, [typeDescriptor({filters: ['counts']})]),
        manifest(2, [typeDescriptor({filters: ['counts'], pluginGeneration: 2, publicationId: 2})]),
        manifest(3, [])], {'/modules/demo.js': {initializer}});
    const c = h.sandbox;
    c.window.PixivBatchAlt = {filters: {}, state: {}, modes: {}};
    for (const name of ['pixiv-batch/batch-download-defaults.js', 'pixiv-batch/state-storage.js',
        'pixiv-batch/filename-template.js', 'pixiv-batch/search-filters.js',
        alt ? 'pixiv-batch-alt/alt-state.js' : 'pixiv-batch/modes/search.js',
        alt ? 'pixiv-batch-alt/alt-filters.js' : 'pixiv-batch/batch-filters.js']) {
        vm.runInContext(readFileSync(resolve(root, name), 'utf8'), c, {filename: name});
    }
    const calls = [];
    c.testState.fetchCount = async (id, generation) => {
        calls.push([id, generation]);
        return {bookmarkCount: generation === 1 ? 12 : 2};
    };
    await h.qt.bootstrap();
    return {h, c, calls, filter(items = [{id: '42'}], stale = () => false) {
        return c.computeFilteredItems(items, c.normalizeSearchFilters({bookmarkMin: 10}), 'demo', stale);
    }};
}

test('经典筛选重用计算入口时，较早的无补查结果不能覆盖后一次筛选', async () => {
    const p = await page(false), fields = new Map();
    p.c.document.getElementById = id => {
        if (!fields.has(id)) fields.set(id, {});
        return fields.get(id);
    };
    Object.assign(p.c, {storeSet() {}, renderSearchResults() {}, renderSearchPagination() {},
        updateBatchQueueButtons() {}});
    vm.runInContext("searchState.rawResults = [{id:'first',bookmarkCount:12}]", p.c);
    const first = p.c.applyCurrentSearchFilters({filters: p.c.defaultSearchFilters()});
    vm.runInContext("searchState.rawResults = [{id:'second',bookmarkCount:12}]", p.c);
    const second = p.c.applyCurrentSearchFilters({filters: p.c.defaultSearchFilters()});
    assert.equal(await first, null);
    assert.equal((await second).filteredCount, 1);
    assert.deepEqual(Array.from(vm.runInContext('searchState.results', p.c), item => item.id), ['second']);
});

for (const mode of ['user', 'series']) {
    test(`${mode} 预览清空后不发布较早的无补查筛选结果`, async () => {
        const p = await page(false), state = {kind: 'demo', filterSeq: 0,
            rawItems: [{id: 'old', bookmarkCount: 12}], items: []};
        p.c[mode + 'State'] = state;
        const file = `pixiv-batch/modes/${mode}-${mode === 'user' ? 'data' : 'view'}.js`;
        vm.runInContext(readFileSync(resolve(root, file), 'utf8'), p.c, {filename: file});
        let renders = 0;
        const name = mode === 'user' ? 'User' : 'Series';
        Object.assign(p.c, {storeSet() {}});
        p.c['render' + name + 'Results'] = () => { renders++; };
        p.c['update' + name + 'QueueButtons'] = () => {};
        const pending = p.c['apply' + name + 'Filters']({filters: p.c.defaultSearchFilters()});
        state.filterSeq++;
        state.rawItems = [];
        assert.equal(await pending, null);
        assert.deepEqual(state.items, []);
        assert.equal(renders, 0);
    });
}

for (const mode of ['outer', 'inner', 'mixed']) {
    for (const change of ['filter', 'load', ...(mode === 'outer' ? ['page'] : [])]) {
        test(`经典快捷 ${mode} 的 ${change} 变化后不回填旧筛选，当前筛选仍可显示`, async () => {
            const p = await page(false);
            vm.runInContext(readFileSync(resolve(root, 'pixiv-batch/modes/quick-fetch-core.js'), 'utf8'), p.c);
            let renders = 0;
            p.c.window.PixivBatch.queueTypes = {...p.h.qt, resolveTypeForMode: kind => kind,
                acquisition: () => ({render() { renders++; }})};
            p.c.getSearchFiltersFromUI = () => p.c.defaultSearchFilters();
            p.c.renderQuickInnerGrid = () => { renders++; };
            const stateName = mode === 'outer' ? 'quickState' : 'quickInner';
            const state = vm.runInContext(stateName, p.c);
            Object.assign(state, {kind: 'demo', type: mode === 'mixed' ? 'collection' : 'following-user',
                rawItems: [{id: 'old', kind: 'demo'}]});
            const run = mode === 'outer' ? p.c.quickRenderOuterWorks : p.c.quickApplyInnerFilters;
            const pending = run();
            if (change === 'filter') state.filterSeq++;
            if (change === 'load') state.loadSeq++;
            if (change === 'page') state.pageSeq++;
            state.rawItems = [];
            await pending;
            assert.equal(state.items.length, 0);
            assert.equal(renders, 0);
            state.rawItems = [{id: 'current', kind: 'demo'}];
            await run();
            assert.deepEqual(Array.from(state.items, item => item.id), ['current']);
            assert.equal(renders, 1);
        });
    }
}

async function altPreviewPage() {
    const p = await page(true);
    for (const module of ['alt-modes.js', 'alt-mode-capture.js', 'alt-mode-discovery.js', 'alt-mode-series.js']) {
        vm.runInContext(readFileSync(resolve(root, 'pixiv-batch-alt/' + module), 'utf8'), p.c,
            {filename: module});
    }
    p.c.document.getElementById = () => ({});
    p.c.altAcquisition = () => ({type: 'demo'});
    return p;
}

for (const [mode, action, render] of [
    ['user', 'applyUserFilters', 'renderUserStage'],
    ['series', 'applySeriesFilters', 'renderSeriesStage'],
    ['quick', 'applyQuickFilters', 'renderQuickStage']
]) {
    test(`新版 ${mode} 仅发布当前筛选，较早的无补查计算不能触发重绘`, async () => {
        for (const change of ['filter', 'items', 'source', 'kind', 'state', mode === 'quick' ? 'load' : 'request']) {
            const p = await altPreviewPage();
            let renders = 0;
            p.c[render] = () => { renders++; };
            const state = vm.runInContext(mode + 'State', p.c);
            Object.assign(state, {kind: 'demo', rawItems: [{id: 'old'}]});
            const pending = p.c[action]();
            if (change === 'filter') vm.runInContext('searchState.filterSeq++', p.c);
            if (change === 'items') state.rawItems = [];
            if (change === 'source') state.source = 'changed';
            if (change === 'kind') state.kind = 'changed';
            if (change === 'load') state.loadSeq++;
            if (change === 'request') state.requestSeq = (state.requestSeq || 0) + 1;
            if (change === 'state') vm.runInContext(`${mode}State = {...${mode}State}`, p.c);
            await pending;
            const current = vm.runInContext(mode + 'State', p.c);
            assert.equal(current.items.length, 0, change);
            assert.equal(renders, 0, change);
            current.rawItems = [{id: 'current'}];
            await p.c[action]();
            assert.deepEqual(Array.from(current.items, item => item.id), ['current'], change);
            assert.equal(renders, 1, change);
        }
    });
}

test('新版快捷内层补查完成前替换状态，不写回作品或重绘旧预览', async () => {
    const p = await altPreviewPage();
    let finish, renders = 0;
    p.c.testState.fetchCount = () => new Promise(resolve => { finish = resolve; });
    p.c.renderQuickStage = () => { renders++; };
    vm.runInContext(`extraFilters = normalizeSearchFilters({bookmarkMin: 10});
        Object.assign(quickState, {kind: 'demo', rawItems: [{id: 'outer', bookmarkCount: 12}],
            drill: {kind: 'demo', rawItems: [{id: 'inner'}]}});`, p.c);
    const pending = p.c.applyQuickFilters();
    await waitUntil(() => !!finish);
    vm.runInContext('quickState = {...quickState, drill: null, items: [], drillItems: []}', p.c);
    finish({bookmarkCount: 12});
    await pending;
    assert.equal(renders, 0);
    assert.equal(vm.runInContext('quickState.items.length + quickState.drillItems.length', p.c), 0);
});

for (const alt of [false, true]) {
    const label = alt ? '新版' : '经典';
    test(`${label}收藏数缓存随查询能力换代失效，同代复用并保留原始作品 ID`, async () => {
        const p = await page(alt);
        const item = alt ? {id: 'queue42', __acquisitionId: '42'} : {id: '42'};
        assert.equal((await p.filter([item, item])).filtered.length, 2);
        assert.equal((await p.filter([item])).filtered.length, 1);
        assert.deepEqual(p.calls, [['42', 1]]);
        assert.equal(p.c.testState.receiverType, 'demo');
        await p.h.qt.refresh();
        assert.equal((await p.filter([item])).filtered.length, 0);
        assert.deepEqual(p.calls, [['42', 1], ['42', 2]]);
        await p.h.qt.refresh();
        const missing = await p.filter([item]);
        assert.equal(missing.filtered.length, 0);
        assert.equal(missing.stats.bookmarkMetaMissing, 1);
        assert.equal(p.calls.length, 2);
    });

    test(`${label}插件换代后的迟到成功与失败均不污染新缓存`, async () => {
        for (const fail of [false, true]) {
            const p = await page(alt);
            let finish;
            p.c.testState.fetchCount = (id, generation) => {
                p.calls.push([id, generation]);
                return generation === 1 ? new Promise((resolve, reject) => {
                    finish = () => fail ? reject(new Error('unavailable')) : resolve({bookmarkCount: 12});
                }) : Promise.resolve({bookmarkCount: 2});
            };
            const old = p.filter();
            await waitUntil(() => !!finish);
            await p.h.qt.refresh();
            finish();
            assert.equal(await old, null);
            assert.equal((await p.filter()).filtered.length, 0);
            assert.deepEqual(p.calls, [['42', 1], ['42', 2]]);
        }
    });

    test(`${label}收藏数补查至多六路，取消后不领取余项或回填结果`, async () => {
        const p = await page(alt), pending = [];
        let active = 0, peak = 0, stale = false;
        p.c.testState.fetchCount = id => {
            p.calls.push(id);
            active++; peak = Math.max(peak, active);
            return new Promise(resolve => pending.push(() => { active--; resolve({bookmarkCount: 12}); }));
        };
        const old = p.filter(Array.from({length: 9}, (_, id) => ({id: String(id)})), () => stale);
        await waitUntil(() => pending.length === 6);
        stale = true;
        pending.splice(0).forEach(finish => finish());
        assert.equal(await old, null);
        assert.equal(peak, 6);
        assert.equal(active, 0);
        assert.equal(p.calls.length, 6);
        p.c.testState.fetchCount = async id => { p.calls.push(id); return {bookmarkCount: 12}; };
        assert.equal((await p.filter([{id: '0'}])).filtered.length, 1);
        assert.equal(p.calls.length, 7);
    });

    test(`${label}内联计数不补查，普通失败按未知处理并在同代复用`, async () => {
        const p = await page(alt);
        p.c.testState.fetchCount = async id => { p.calls.push(id); throw new Error('offline'); };
        const items = [{id: 'inline', bookmarkCount: 12}, {id: 'zero', bookmarkCount: 0}, {id: 'missing'}];
        for (let i = 0; i < 2; i++) {
            const result = await p.filter(items);
            assert.deepEqual(Array.from(result.filtered, item => item.id), ['inline']);
            assert.equal(result.stats.bookmarkMetaMissing, 1);
        }
        assert.deepEqual(p.calls, ['missing']);
    });
}
