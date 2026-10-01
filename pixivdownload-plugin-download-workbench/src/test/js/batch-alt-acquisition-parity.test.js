'use strict';

const assert = require('node:assert/strict');
const {test} = require('node:test');
const {readFileSync} = require('node:fs');
const {resolve} = require('node:path');
const vm = require('node:vm');
const {MiniElement} = require('./pixiv-layout-feedback-test-dom');

const staticRoot = resolve(__dirname, '../../main/resources/static/pixiv-batch-alt');

function harness(acquisitions, respond = () => ({})) {
    const calls = [], batches = [], toasts = [];
    const root = new MiniElement('div');
    const document = {
        addEventListener() {},
        getElementById: id => root.querySelector('#' + id),
        querySelector: selector => root.querySelector(selector),
        querySelectorAll: selector => root.querySelectorAll(selector),
        createElement(tag) {
            const node = new MiniElement(tag);
            node.append = (...children) => children.forEach(child => node.appendChild(child));
            node.prepend = (...children) => children.reverse().forEach(child => node.insertBefore(child, node.firstChild));
            node.replaceChildren = (...children) => { node.children.slice().forEach(child => node.removeChild(child)); children.forEach(child => node.appendChild(child)); };
            return node;
        }
    };
    ['abQuickStage', 'abUserStage', 'abSeriesStage', 'abSearchStage'].forEach(id => {
        const stage = document.createElement('div'); stage.id = id; root.appendChild(stage);
    });
    let current = true;
    const runtime = {
        acquisitionList: mode => Object.values(acquisitions[mode] || {}),
        acquisition: (type, mode) => acquisitions[mode]?.[type],
        acquisitionLease() {
            return {signal: new AbortController().signal,
                assertCurrent() { if (!current) throw new Error('stale activation'); }};
        }
    };
    const c = vm.createContext({document, console, URLSearchParams, Map, Set, AbortController,
        setTimeout, clearTimeout, addEventListener() {},
        PixivBatchAlt: {state: {}, modes: {}}, PixivBatch: {queueTypes: runtime}});
    c.window = c;
    for (const name of ['alt-core.js', 'alt-state.js', 'alt-settings.js', 'alt-modes.js',
        'alt-mode-capture.js', 'alt-mode-discovery.js', 'alt-mode-series.js']) {
        vm.runInContext(readFileSync(resolve(staticRoot, name), 'utf8'), c, {filename: name});
    }
    Object.assign(c, {
        bt: (key, fallback, args) => Object.entries(args || {}).reduce(
            (s, [k, v]) => s.replaceAll('{' + k + '}', String(v)), fallback || key),
        altQueueTypes: () => runtime,
        altTypesForSource: mode => Object.values(acquisitions[mode] || {}),
        altAcquisition: (mode, _source, type) => acquisitions[mode]?.[type] || null,
        altTypeLabel: type => type,
        altAcquisitionJson: async (type, mode, spec, operation, context) => {
            calls.push({type, mode, spec, operation, context});
            return respond({type, mode, spec, operation, context});
        },
        altNextCursor: (data, cursor) => {
            if (data.nextCursor == null || String(data.nextCursor) === String(cursor)) throw new Error('stalled cursor');
            return String(data.nextCursor);
        },
        extraFilters: {},
        hasExtraSearchFilter: () => !!c.extraFilters.enabled,
        computeFilteredItems: async (items, filters, _kind, isStale = () => false) =>
            isStale() ? null : {filtered: filters.enabled ? items.filter(item => item.keep) : items,
                stats: {filteredCount: items.length}},
        abConfirm: async () => true,
        abToast: (tone, text) => toasts.push({tone, text}),
        addItemsToQueue: (ids, metas) => { batches.push({ids: Array.from(ids), metas: Array.from(metas)}); return ids.length; },
        renderQuickStage() {}, renderUserStage() {}, renderSeriesStage() {}, renderSearchStage() {},
        syncAllResultsQueueState() {}, storeSet() {}, storeGet: () => null,
        abIconEl: () => document.createElement('span'),
        loadingGrid: () => document.createElement('progress'),
        errorBox: () => document.createElement('aside'),
        enqueueBar: options => { const node = document.createElement('nav'); node.options = options; return node; },
        worksGrid: (items, options) => { const node = document.createElement('section'); node.items = items; node.options = options; return node; },
        paginationBar: options => { const node = document.createElement('footer'); node.options = options; return node; },
    });
    c.run = code => vm.runInContext(code, c);
    return {c, calls, batches, toasts, root, revoke: () => { current = false; }};
}

function quickType(type, extra = {}) {
    return {
        type, pageSize: 2, dataSource: {id: 'fixture'},
        queueId: item => type + ':' + item.id,
        buildQueueMeta: (item, ctx) => ({title: item.title || item.id, kind: type,
            canonicalUrl: item.url, typeData: {owner: type, author: ctx.userId}}),
        buildUserIdsRequest: id => ({endpoint: '/ids/' + id}),
        buildCardsRequest: (id, ids) => ({endpoint: '/cards/' + id, ids}),
        ...extra
    };
}

test('快捷获取空作品与越界空页不请求卡片，仍发布完整空结果', async () => {
    for (const [ids, page] of [[[], 1], [['1', '2'], 2]]) {
        const descriptor = {allIdsFastPath: true};
        const acquisition = quickType('image', {buildMyWorksIdsRequest: id => ({endpoint: '/ids/' + id})});
        const h = harness({quick: {image: acquisition}}, ({operation}) => {
            assert.equal(operation, 'ids');
            return {ids};
        });
        h.c.run("Object.assign(quickState, {source:'fixture', kind:'image', uid:'author'})");
        await h.c.loadQuickWorks({id: 'my-works', descriptor}, page);
        assert.equal(h.c.run('quickState.error'), '');
        assert.equal(h.c.run('quickState.loading'), false);
        assert.equal(h.c.run('quickState.items.length'), 0);
        assert.equal(h.c.run('quickState.total'), ids.length);
        assert.equal(h.calls.length, 1);
    }
});

test('关注用户展开支持所有已贡献类型、完整分页和全量入队', async () => {
    const action = {viewType: 'following-list', userWorkTypes: ['image', 'text']};
    const image = quickType('image', {actions: {following: action}});
    const text = quickType('text');
    const h = harness({quick: {image, text}}, ({spec}) =>
        spec.endpoint.startsWith('/ids/') ? {ids: ['1', '2', '3', '4', '5']}
            : {items: spec.ids.map(id => ({id, url: 'https://example.test/' + id}))});
    h.c.run("quickState.source='fixture'; quickState.kind='image'; quickState.action='following'");
    await h.c.drillQuickUser({userId: 'author', userName: 'Writer'});
    assert.equal(h.c.run('quickState.drill.total'), 5);
    assert.deepEqual(Array.from(h.c.run('quickState.drillItems'), i => i.id), ['image:1', 'image:2']);
    await h.c.loadQuickDrillPage(3);
    assert.deepEqual(Array.from(h.c.run('quickState.drillItems'), i => i.id), ['image:5']);
    h.c.run("quickState.drill.kind='text'; quickState.drill.ids=null; quickState.drill.cursors=new Map()");
    await h.c.loadQuickDrillPage(1);
    await h.c.enqueueQuickDrillAll();
    assert.deepEqual(h.batches[0].ids, ['text:1', 'text:2', 'text:3', 'text:4', 'text:5']);
    assert.equal(h.batches[0].metas[4].typeData.author, 'author');
    assert.equal(h.batches[0].metas[4].canonicalUrl, 'https://example.test/5');
});

test('空画师列表不请求卡片，保持正常空结果与分页状态', async () => {
    const acquisition = quickType('image', {
        parseInput: value => value,
        fetchIds: async () => [],
        cardsEndpoint: id => '/cards/' + id
    });
    const h = harness({user: {image: acquisition}}, () => {
        throw new Error('empty card request');
    });
    h.c.run("Object.assign(userState, {source:'fixture',kind:'image',input:'author'})");
    await h.c.loadUserWorks(1);
    assert.equal(h.c.run('userState.error'), '');
    assert.equal(h.c.run('userState.loading'), false);
    assert.equal(h.c.run('userState.rawItems.length'), 0);
    assert.equal(h.c.run('userState.total'), 0);
    assert.equal(h.calls.length, 0);
});

test('游标关注作品翻回已访问页使用对应游标，不借用下一页游标', async () => {
    const acquisition = quickType('image', {
        actions: {following: {viewType: 'following-list'}}, initialCursor: 'start',
        buildUserPageRequest: (_id, ctx) => ({endpoint: '/paged', cursor: ctx.cursor}),
    });
    const h = harness({quick: {image: acquisition}}, ({spec}) => spec.cursor === 'start'
        ? {items: [{id: '1'}, {id: '2'}], hasMore: true, nextCursor: 'tail'}
        : {items: [{id: '3'}], hasMore: false});
    h.c.run("quickState.source='fixture'; quickState.kind='image'; quickState.action='following'");
    await h.c.drillQuickUser({userId: 'writer'});
    await h.c.loadQuickDrillPage(2);
    await h.c.loadQuickDrillPage(1);
    assert.deepEqual(h.calls.map(call => call.spec.cursor), ['start', 'tail', 'start']);
    assert.equal(h.c.run('quickState.drill.total'), 3);
});

test('珍藏集全量获取跨游标页，保留混合类型贡献元数据', async () => {
    const image = quickType('image', {actions: {collection: {
        viewType: 'collection-list', initialCursor: 'start',
        buildCollectionWorksPageRequest: (_id, ctx) => ({endpoint: '/collection', cursor: ctx.cursor})
    }}});
    const h = harness({quick: {image, text: quickType('text')}}, ({spec}) => spec.cursor === 'start'
        ? {items: [{id: '1', kind: 'image'}], hasMore: true, nextCursor: 'tail', total: 2}
        : {items: [{id: '2', kind: 'text'}], hasMore: false, total: 2});
    h.c.run("quickState.source='fixture'; quickState.kind='image'; quickState.action='collection'");
    await h.c.drillQuickCollection({id: 'folder'});
    await h.c.enqueueQuickDrillAll();
    assert.deepEqual(h.batches[0].ids, ['image:1', 'text:2']);
    assert.equal(h.batches[0].metas[1].typeData.owner, 'text');
});

test('空钻取渲染为空列表，失败可重试且不会持续显示加载动画', async () => {
    const acq = quickType('image', {actions: {following: {viewType: 'following-list'}}});
    const h = harness({quick: {image: acq}}, () => ({ids: []}));
    h.c.run("quickState.kind='image'; quickState.action='following'");
    await h.c.drillQuickUser({userId: 'empty'});
    const stage = h.c.document.createElement('div');
    h.c.renderQuickDrill(stage);
    assert.equal(stage.querySelectorAll('progress').length, 0);
    assert.equal(stage.querySelectorAll('section').length, 1);
    h.c.altAcquisitionJson = async () => { throw new Error('fetch failed'); };
    await h.c.drillQuickUser({userId: 'bad'});
    const retryStage = h.c.document.createElement('div');
    h.c.renderQuickDrill(retryStage);
    assert.equal(retryStage.querySelectorAll('aside').length, 1);
    assert.equal(retryStage.querySelectorAll('progress').length, 0);
});

test('关闭或切换钻取后完成的旧请求不得覆盖新预览', async () => {
    let release;
    const acq = quickType('image', {actions: {following: {viewType: 'following-list'}}});
    const h = harness({quick: {image: acq}}, ({spec}) =>
        spec.endpoint === '/ids/slow' ? new Promise(resolve => { release = resolve; }) : {ids: []});
    h.c.run("quickState.kind='image'; quickState.action='following'");
    const pending = h.c.drillQuickUser({userId: 'slow'});
    await h.c.drillQuickUser({userId: 'new'});
    release({ids: []});
    await pending;
    assert.equal(h.c.run('quickState.drill.id'), 'new');
    assert.equal(h.c.run('quickState.drill.loading'), false);
});

test('快捷获取全部入队沿用原有下载期筛选语义，预览只筛当前页', async () => {
    const acq = quickType('image', {actions: {following: {viewType: 'following-list'}}});
    const h = harness({quick: {image: acq}}, ({spec}) =>
        spec.endpoint.startsWith('/ids/') ? {ids: ['1', '2']}
            : {items: [{id: '1', keep: true}, {id: '2', keep: false}]});
    h.c.extraFilters.enabled = true;
    h.c.run("quickState.kind='image'; quickState.action='following'");
    await h.c.drillQuickUser({userId: 'author'});
    assert.equal(h.c.run('quickState.drillItems.length'), 1);
    await h.c.enqueueQuickDrillAll();
    assert.deepEqual(h.batches[0].ids, ['image:1', 'image:2']);
});

test('ID 型画师全部入队补齐卡片后应用筛选并保存贡献队列 ID', async () => {
    const user = quickType('text', {cardsEndpoint: id => '/cards/' + id});
    const h = harness({user: {text: user}}, ({spec}) => ({items: spec.params.ids.map(id => ({id, keep: id !== '2'}))}));
    h.c.extraFilters.enabled = true;
    h.c.run("Object.assign(userState,{source:'fixture',kind:'text',ids:['1','2','3'],total:3,pageSize:2,userId:'author',username:'Writer'})");
    await h.c.enqueueUserAll();
    assert.deepEqual(h.batches[0].ids, ['text:1', 'text:3']);
    assert.equal(h.calls.length, 2);
    assert.equal(h.batches[0].metas[1].typeData.author, 'author');
});

test('画师全量补齐期间贡献撤回不会提交部分结果', async () => {
    const user = quickType('text', {cardsEndpoint: id => '/cards/' + id});
    const h = harness({user: {text: user}}, ({spec}) => {
        h.revoke(); return {items: spec.params.ids.map(id => ({id, keep: true}))};
    });
    h.c.extraFilters.enabled = true;
    h.c.run("Object.assign(userState,{source:'fixture',kind:'text',ids:['1','2'],total:2,pageSize:1,userId:'author'})");
    await h.c.enqueueUserAll();
    assert.equal(h.batches.length, 0);
    assert.equal(h.toasts[0].tone, 'error');
});

test('系列全部入队使用所有分页筛选并拒绝静默回退当前页', async () => {
    const series = quickType('image', {
        apiPath: (_id, page) => ({endpoint: '/series', page}),
        buildQueueMeta: (item, order, ctx) => ({title: item.id, seriesId: ctx.seriesId, seriesOrder: order})
    });
    const h = harness({series: {image: series}}, ({spec}) => ({
        items: [{id: String(spec.page), keep: spec.page === 2}], isLastPage: spec.page === 2
    }));
    h.c.extraFilters.enabled = true;
    h.c.run("Object.assign(seriesState,{source:'fixture',kind:'image',info:{seriesId:'s',title:'S'},page:1})");
    await h.c.enqueueSeriesAll();
    assert.deepEqual(h.batches[0].ids, ['image:2']);
});

test('系列前后翻页使用各页游标', async () => {
    const series = quickType('image', {
        initialCursor: 'start', parseUrl: () => ({seriesId: 's'}),
        apiPath: (_id, page, context) => ({endpoint: '/series', page, cursor: context.cursor}),
        buildQueueMeta: (item, order, ctx) => ({title: item.id, seriesId: ctx.seriesId, seriesOrder: order})
    });
    const h = harness({series: {image: series}}, ({spec}) => ({
        items: [{id: String(spec.page)}], series: {title: 'S', total: 2},
        hasMore: spec.page === 1, nextCursor: spec.page === 1 ? 'tail' : null
    }));
    h.c.run("Object.assign(seriesState,{source:'fixture',kind:'image',url:'series'})");
    await h.c.loadSeries(1); await h.c.loadSeries(2); await h.c.loadSeries(1);
    assert.deepEqual(h.calls.map(call => call.spec.cursor), ['start', 'tail', 'start']);
});

test('即时批量搜索不发送计划任务专属负一结束页', async () => {
    const search = quickType('image', {buildRangeRequest: ctx => ({endpoint: '/search', ...ctx})});
    const h = harness({search: {image: search}}, () => ({items: []}));
    h.c.run("isAdmin=true; Object.assign(searchState,{source:'fixture',kind:'image',word:'cat',submode:'batch',startPage:3,endPage:-1})");
    await h.c.runSearch(1);
    assert.equal(h.calls[0].spec.endPage, 3);
    assert.equal(h.c.run('searchState.endPage'), -1, '保留计划快照所用的哨兵输入');
});

test('官方约稿快捷入口使用其专属 ID 请求而非我的作品请求', async () => {
    const acq = quickType('illust', {
        actions: {'my-request-artworks': {allIdsFastPath: true, pageSize: 2,
            buildIdsRequest: () => ({endpoint: '/requests'})}},
        buildMyWorksIdsRequest: () => ({endpoint: '/my-works'})
    });
    const h = harness({quick: {illust: acq}}, ({spec}) =>
        spec.endpoint === '/requests' ? {ids: ['1']} : {items: [{id: '1'}]});
    h.c.run("quickState.kind='illust'; quickState.action='my-request-artworks'; quickState.uid='me'");
    const action = h.c.quickActionDefs()[0];
    await h.c.loadQuickWorks(action, 1);
    assert.equal(h.calls[0].spec.endpoint, '/requests');
    assert.equal(h.c.run('quickState.total'), 1);
});

test('批量页码沿用旧页的逆序交换、无效值与访客范围限制', () => {
    const h = harness({});
    h.c.run("isAdmin=false; appMode='multi'; multiModeLimitPage=3; searchState.startPage=8; searchState.endPage=2");
    h.c.normalizeAltBatchRange();
    assert.equal(h.c.run('searchState.startPage'), 2);
    assert.equal(h.c.run('searchState.endPage'), 4);
    h.c.run("searchState.startPage=0; searchState.endPage=-1");
    h.c.normalizeAltBatchRange();
    assert.equal(h.c.run('searchState.startPage'), 1);
    assert.equal(h.c.run('searchState.endPage'), 1);
});

test('系列浏览支持返回上一页并等待贡献的异步选择', async () => {
    let selected = false, drawer;
    const acquisition = quickType('image', {browser: {
        initialCursor: 'start', pageSize: 1, title: () => 'Collections', loadingLabel: () => 'Loading',
        buildPageRequest: ctx => ({cursor: ctx.cursor}), readPage: data => data,
        itemLabel: item => item.name, select: async item => { await Promise.resolve(); selected = true; return {seriesId: item.id}; }
    }});
    const h = harness({series: {image: acquisition}}, ({spec}) => ({
        items: [{id: spec.cursor === 'start' ? 'a' : 'b', name: 'Collection'}],
        hasMore: spec.cursor === 'start', nextCursor: 'tail'
    }));
    h.c.openDrawer = spec => { drawer = spec; };
    h.c.closeDrawer = () => {};
    h.c.renderStage = () => {};
    h.c.loadSeries = () => {};
    await h.c.openSeriesBrowser(acquisition);
    drawer.body.querySelectorAll('.ab-btn')[1].click();
    await new Promise(resolve => setImmediate(resolve));
    drawer.body.querySelectorAll('.ab-btn')[0].click();
    await new Promise(resolve => setImmediate(resolve));
    assert.deepEqual(h.calls.map(call => call.spec.cursor), ['start', 'tail', 'start']);
    drawer.body.querySelector('.ab-collection-card').click();
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(selected, true);
    assert.equal(h.c.run('seriesState.url'), 'a');
});

test('已知总页数的快捷全量和系列全量不截断于旧的局部护栏', async () => {
    for (const mode of ['quick', 'series']) {
        const pages = 1001;
        const descriptor = {pageSize: 1, buildPageRequest: ctx => ({page: ctx.page})};
        const acquisition = quickType('image', {pageSize: 1, actions: {works: descriptor},
            apiPath: (_id, page) => ({page}), buildQueueMeta: item => ({title: item.id})});
        const h = harness({[mode]: {image: acquisition}}, ({spec}) => ({
            totalPages: pages, items: [{id: String(spec.page)}]
        }));
        if (mode === 'quick') {
            h.c.run("Object.assign(quickState,{source:'fixture',kind:'image',action:'works'})");
            await h.c.enqueueQuickAll({id: 'works', descriptor});
        } else {
            h.c.run("Object.assign(seriesState,{source:'fixture',kind:'image',info:{seriesId:'s'}})");
            await h.c.enqueueSeriesAll();
        }
        assert.equal(h.calls.length, pages, mode);
        assert.equal(h.batches[0].ids.length, pages, mode);
        assert.equal(h.toasts.some(toast => toast.tone === 'error'), false);
    }
});

test('未知游标第1000页完成可入队，仍有下一页时整批拒绝', async () => {
    for (const mode of ['quick', 'series']) for (const complete of [true, false]) {
        const descriptor = {pageSize: 1, cursorPaging: true, initialCursor: '0',
            buildPageRequest: ctx => ({page: ctx.page})};
        const acquisition = quickType('image', {pageSize: 1, initialCursor: '0', actions: {works: descriptor},
            apiPath: (_id, page) => ({page}), buildQueueMeta: item => ({title: item.id})});
        const h = harness({[mode]: {image: acquisition}}, ({spec}) => ({
            items: [{id: String(spec.page)}], hasMore: !complete || spec.page < 1000,
            nextCursor: String(spec.page)
        }));
        if (mode === 'quick') {
            h.c.run("Object.assign(quickState,{source:'fixture',kind:'image',action:'works'})");
            await h.c.enqueueQuickAll({id: 'works', descriptor});
        } else {
            h.c.run("Object.assign(seriesState,{source:'fixture',kind:'image',info:{seriesId:'s'}})");
            await h.c.enqueueSeriesAll();
        }
        assert.equal(h.calls.length, 1000, mode);
        assert.equal(h.batches.length, complete ? 1 : 0, mode);
        assert.equal(h.toasts.some(toast => toast.tone === 'error'), !complete, mode);
    }
});

test('关注用户全量已知ID列表不截断，未知游标使用同一1000页边界', async () => {
    for (const known of [true, false]) for (const complete of known ? [true] : [true, false]) {
        const descriptor = {viewType: 'following-list'};
        const type = quickType('image', {pageSize: 1, actions: {following: descriptor},
            ...(known ? {} : {initialCursor: '0', buildUserPageRequest: (_id, ctx) => ({page: ctx.page})})});
        const h = harness({quick: {image: type}}, ({spec}) => known
            ? spec.ids ? {items: spec.ids.map(id => ({id}))} : {ids: Array.from({length: 1001}, (_, n) => String(n))}
            : {items: [{id: String(spec.page)}], hasMore: !complete || spec.page < 1000, nextCursor: String(spec.page)});
        h.c.run("Object.assign(quickState,{source:'fixture',kind:'image',action:'following'})");
        await h.c.drillQuickUser({userId: 'author', userName: 'Author'});
        await h.c.enqueueQuickDrillAll();
        assert.equal(h.batches.length, complete ? 1 : 0);
        if (complete) assert.equal(h.batches[0].ids.length, known ? 1001 : 1000);
        assert.equal(h.toasts.some(toast => toast.tone === 'error'), !complete);
    }
});
