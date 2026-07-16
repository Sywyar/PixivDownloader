'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const test = require('node:test');
const vm = require('vm');

const STATIC = path.join(__dirname, '..', '..', 'main', 'resources', 'static', 'pixiv-batch');
const USER_SOURCE = fs.readFileSync(path.join(STATIC, 'modes', 'user.js'), 'utf8');
const QUICK_SOURCE = fs.readFileSync(path.join(STATIC, 'modes', 'quick-fetch.js'), 'utf8');

function deferred() {
    let resolve;
    let reject;
    const promise = new Promise((ok, fail) => { resolve = ok; reject = fail; });
    return {promise, resolve, reject};
}

function element() {
    return {
        innerHTML: '', textContent: '', style: {}, disabled: false, value: '',
        classList: {toggle() {}, remove() {}},
        querySelectorAll() { return []; }
    };
}

function userHarness(acquisition, fetchImpl) {
    const elements = {
        'user-results-area': element(),
        'user-pagination': element(),
        'btn-user-add-page': element(),
        'btn-user-add-all': element()
    };
    const registry = {
        acquisition() { return acquisition; },
        resolveTypeForMode(value) { return value; },
        acquisitionLease() {
            return {signal: new AbortController().signal, assertCurrent() {}, isCurrent() { return true; }};
        },
        prepareAcquisitionRequest(_kind, _mode, url) {
            return {url, init: {}, assertCurrent() {}};
        },
        supports() { return true; }
    };
    const sandbox = {
        window: {PixivBatch: {queueTypes: registry, modes: {}}},
        document: {getElementById(id) { return elements[id] || null; }},
        state: {settings: {userKind: 'demo'}, queue: []},
        searchState: {},
        console: {warn() {}, log() {}, error() {}},
        Map, Set, Promise, URL, URLSearchParams, AbortController,
        fetch: fetchImpl,
        bt(_key, fallback) { return fallback; },
        esc(value) { return String(value); },
        resetPreviewCollapse() {},
        updateUserQueueButtons: undefined,
        setStatus() {},
        normalizeSearchFilters(value) { return value || {}; },
        getSearchFiltersFromUI() { return {}; },
        saveSearchFilterPrefs() {},
        hasBookmarkFilter() { return false; },
        hasExtraSearchFilter() { return false; },
        computeFilteredItems: async items => ({
            filtered: items,
            stats: {rawCount: items.length, filteredCount: items.length,
                bookmarkMetaMissing: 0, bookmarkFilterActive: false}
        }),
        defaultSearchFilters() { return {}; },
        uiLang() { return 'zh-CN'; },
        summaryJoin(parts) { return parts.join(' / '); },
        summarySeparator() { return ' / '; },
        getInlineSearchBookmarkCount() { return null; },
        getCachedSearchMeta() { return null; },
        fetchThumbnailBlobUrl: async () => null,
        addItemsToQueue() { return 0; },
        removeFromQueue() { return false; },
        saveSettings() {},
        applyKindSwitcherUI() {},
        applyNovelSettingsVisibility() {},
        applySearchKindUI() {},
        uiAlertKey: async () => {},
        uiConfirmKey: async () => true
    };
    vm.createContext(sandbox);
    vm.runInContext(USER_SOURCE + '\nwindow.__pagedUserTest = {userState, loadUserPreviewPage};', sandbox);
    const state = sandbox.window.__pagedUserTest.userState;
    Object.assign(state, {
        kind: 'demo', variant: 'demo', userId: 'user-1', username: 'User',
        pagedAcquisition: typeof acquisition.fetchPage === 'function',
        total: 4, totalPages: 2, allIds: [], pageCache: new Map(), pageCursors: new Map()
    });
    return {sandbox, state, loadPage: sandbox.window.__pagedUserTest.loadUserPreviewPage};
}

test('中性 user fetchPage 连续两页推进 offset/cursor 且不调用 cards', async () => {
    const calls = [];
    const acquisition = {
        pageSize: 2,
        fetchPage(_userId, context) {
            calls.push({...context});
            return Promise.resolve(context.page === 1
                ? {items: [{id: '1'}, {id: '2'}], total: 4, nextCursor: 'cursor-2', hasMore: true}
                : {items: [{id: '3'}, {id: '4'}], total: 4, nextCursor: '', hasMore: false});
        },
        queueId(item) { return String(item.id); },
        cardId(index) { return `card-${index}`; },
        render() {},
        buildQueueMeta() { return {}; }
    };
    const h = userHarness(acquisition, () => {
        throw new Error('paged acquisition must not call the legacy cards endpoint');
    });

    await h.loadPage(1);
    await h.loadPage(2);

    assert.deepStrictEqual(calls.map(call => ({
        page: call.page, offset: call.offset, limit: call.limit, cursor: call.cursor
    })), [
        {page: 1, offset: 0, limit: 2, cursor: null},
        {page: 2, offset: 2, limit: 2, cursor: 'cursor-2'}
    ]);
    assert.deepStrictEqual(Array.from(h.state.rawItems, item => item.id), ['3', '4']);
});

test('中性 user fetchPage 的旧响应不会覆盖后发分页结果', async () => {
    const first = deferred();
    const second = deferred();
    const acquisition = {
        pageSize: 2,
        fetchPage(_userId, context) { return context.page === 1 ? first.promise : second.promise; },
        queueId(item) { return String(item.id); },
        cardId(index) { return `card-${index}`; },
        render() {},
        buildQueueMeta() { return {}; }
    };
    const h = userHarness(acquisition, () => Promise.reject(new Error('unexpected fetch')));
    h.state.pageCursors.set(2, 'cursor-2');

    const oldLoad = h.loadPage(1);
    const newLoad = h.loadPage(2);
    second.resolve({items: [{id: 'new'}], total: 4, nextCursor: '', hasMore: false});
    await newLoad;
    first.resolve({items: [{id: 'old'}], total: 4, nextCursor: 'cursor-2', hasMore: true});
    await oldLoad;

    assert.strictEqual(h.state.currentPage, 2);
    assert.deepStrictEqual(Array.from(h.state.rawItems, item => item.id), ['new']);
});

test('切换用户后旧 fetchPage 不会写入新用户的 pageCache/cursor', async () => {
    const userA = deferred();
    const userB = deferred();
    const acquisition = {
        pageSize: 2,
        fetchPage(userId) { return userId === 'user-a' ? userA.promise : userB.promise; },
        queueId(item) { return String(item.id); },
        cardId(index) { return `card-${index}`; },
        render() {},
        buildQueueMeta() { return {}; }
    };
    const h = userHarness(acquisition, () => Promise.reject(new Error('unexpected fetch')));
    Object.assign(h.state, {userId: 'user-a', total: 2, totalPages: 1});
    const oldCache = h.state.pageCache;
    const oldCursors = h.state.pageCursors;
    const oldLoad = h.loadPage(1);

    h.state.userId = 'user-b';
    h.state.pageCache = new Map();
    h.state.pageCursors = new Map();
    h.state.requestSeq++;
    const newCache = h.state.pageCache;
    const newCursors = h.state.pageCursors;
    const newLoad = h.loadPage(1);
    userB.resolve({items: [{id: 'b'}], total: 1, nextCursor: '', hasMore: false});
    await newLoad;
    userA.resolve({items: [{id: 'a'}], total: 2, nextCursor: 'a-next', hasMore: true});
    await oldLoad;

    assert.strictEqual(h.state.pageCache, newCache);
    assert.strictEqual(h.state.pageCursors, newCursors);
    assert.deepStrictEqual(Array.from(newCache.get(1).items, item => item.id), ['b']);
    assert.strictEqual(newCursors.has(2), false);
    assert.strictEqual(oldCache.has(1), false);
    assert.strictEqual(oldCursors.has(2), false);
    assert.deepStrictEqual(Array.from(h.state.rawItems, item => item.id), ['b']);
});

test('旧 user acquisition 仍走 fetchIds/cards 兼容路径', async () => {
    const requests = [];
    const acquisition = {
        pageSize: 2,
        cardsEndpoint() { return '/api/legacy/cards'; },
        queueId(item) { return String(item.id); },
        cardId(index) { return `card-${index}`; },
        render() {},
        buildQueueMeta() { return {}; },
        buildQueueMetaFromId() { return {}; }
    };
    const h = userHarness(acquisition, (url) => {
        requests.push(String(url));
        return Promise.resolve({ok: true, json: () => Promise.resolve({items: [{id: 'legacy-1'}]})});
    });
    Object.assign(h.state, {pagedAcquisition: false, allIds: ['legacy-1'], total: 1, totalPages: 1});

    await h.loadPage(1);

    assert.strictEqual(requests.length, 1);
    assert.match(requests[0], /^\/api\/legacy\/cards\?/);
    assert.deepStrictEqual(Array.from(h.state.rawItems, item => item.id), ['legacy-1']);
});

test('Pixiv 来源内 /request URL 自动切回旧 userKind=request 且不跨来源探测', async () => {
    const calls = [];
    const stored = new Map();
    let douyinDetectCalls = 0;
    const selection = {sourceId: 'pixiv', type: 'novel'};
    const base = {
        dataSource: {id: 'pixiv'},
        pageSize: 30,
        parseInput(value) {
            const match = String(value).match(/users\/(\d+)/);
            return match ? match[1] : null;
        },
        fetchMeta: async () => 'Pixiv user',
        fetchIds: async () => [],
        cardsEndpoint: () => '/api/cards',
        queueId: item => String(item.id),
        cardId: index => `card-${index}`,
        render() {},
        buildQueueMeta() { return {}; },
        buildQueueMetaFromId() { return {}; }
    };
    const illust = Object.assign({
        type: 'illust',
        accepts(value) { return value === 'illust' || value === 'request'; },
        detectVariant(raw, current) { return /\/request\b/.test(raw) ? 'request' : current; }
    }, base);
    const novel = Object.assign({
        type: 'novel',
        accepts(value) { return value === 'novel'; }
    }, base);
    const douyin = Object.assign({}, base, {
        type: 'douyin',
        dataSource: {id: 'douyin'},
        accepts(value) { return value === 'douyin' || value === 'request'; },
        detectVariant() { douyinDetectCalls++; return 'request'; }
    });
    const acquisitions = [douyin, illust, novel];
    const elements = {
        'user-id-input': Object.assign(element(), {value: 'https://www.pixiv.net/users/42/request'}),
        'user-info-display': element(),
        'user-results-area': element(),
        'user-pagination': element(),
        'btn-user-add-page': element(),
        'btn-user-add-all': element()
    };
    const registry = {
        acquisition(type) { return acquisitions.find(candidate => candidate.type === type) || null; },
        acquisitionList() { return acquisitions; },
        typesForDataSource(_mode, sourceId) {
            return sourceId === 'pixiv' ? [{type: 'illust'}, {type: 'novel'}] : [{type: 'douyin'}];
        },
        resolveTypeForMode(value) { return acquisitions.some(candidate => candidate.type === value) ? value : null; },
        acquisitionLease() {
            return {signal: new AbortController().signal, assertCurrent() {}, isCurrent() { return true; }};
        }
    };
    const modeControls = {
        selection() { return {...selection}; },
        selectType(mode, type, notify) {
            calls.push(['selectType', mode, type, notify]);
            selection.type = type;
            return true;
        }
    };
    const sandbox = {
        window: {PixivBatch: {queueTypes: registry, modeControls, modes: {}}},
        document: {getElementById(id) { return elements[id] || null; }},
        state: {mode: 'user', settings: {userKind: 'novel'}, queue: []},
        searchState: {},
        console: {warn() {}, log() {}, error() {}},
        Map, Set, Promise, URL, URLSearchParams, AbortController,
        fetch() { throw new Error('empty user result must not request cards'); },
        bt(_key, fallback) { return fallback; },
        esc(value) { return String(value); },
        resetPreviewCollapse() {}, setStatus() {}, saveSettings() {},
        applyKindSwitcherUI(id, kind) { calls.push([id, kind]); },
        applyNovelSettingsVisibility() {}, applySearchKindUI() {},
        normalizeSearchFilters(value) { return value || {}; },
        getSearchFiltersFromUI() { return {}; }, saveSearchFilterPrefs() {},
        hasBookmarkFilter() { return false; }, hasExtraSearchFilter() { return false; },
        computeFilteredItems: async items => ({filtered: items, stats: {rawCount: items.length,
            filteredCount: items.length, bookmarkMetaMissing: 0, bookmarkFilterActive: false}}),
        defaultSearchFilters() { return {}; }, uiLang() { return 'zh-CN'; },
        summaryJoin(parts) { return parts.join(' / '); }, summarySeparator() { return ' / '; },
        getInlineSearchBookmarkCount() { return null; }, getCachedSearchMeta() { return null; },
        fetchThumbnailBlobUrl: async () => null, addItemsToQueue() { return 0; },
        removeFromQueue() { return false; }, uiAlertKey: async () => {},
        storeGet(key) { return stored.has(key) ? stored.get(key) : null; },
        storeSet(key, value) { stored.set(key, String(value)); },
        storeRemove(key) { stored.delete(key); }
    };
    vm.createContext(sandbox);
    vm.runInContext(USER_SOURCE + '\nwindow.__requestDetectionTest = {loadUserPreview, userState, userKindOwner};', sandbox);

    assert.strictEqual(sandbox.window.__requestDetectionTest.userKindOwner('request'), 'illust');
    await sandbox.window.__requestDetectionTest.loadUserPreview();

    assert.deepStrictEqual(calls, [
        ['selectType', 'user', 'illust', false],
        ['user-kind-switcher', 'request']
    ]);
    assert.deepStrictEqual(selection, {sourceId: 'pixiv', type: 'illust'});
    assert.strictEqual(sandbox.state.settings.userKind, 'request');
    assert.strictEqual(elements['user-id-input'].value, 'https://www.pixiv.net/users/42/request');
    assert.strictEqual(stored.get('pixiv_batch_user_input:pixiv'),
        'https://www.pixiv.net/users/42/request');
    assert.strictEqual(douyinDetectCalls, 0);
    assert.strictEqual(sandbox.window.__requestDetectionTest.userState.kind, 'illust');
    assert.strictEqual(sandbox.window.__requestDetectionTest.userState.variant, 'request');
});

function quickHarness(responses, contexts) {
    const registry = {
        resolveTypeForMode(value) { return value; },
        acquisition() { return acquisition; },
        prepareAcquisitionRequest(_type, _mode, url) {
            return {url, init: {}, assertCurrent() {}};
        },
        supports() { return true; }
    };
    const acquisition = {
        type: 'demo', pageSize: 2,
        buildUserPageRequest(_userId, context) {
            contexts.push({...context});
            return {endpoint: `/api/user?page=${context.page}`};
        }
    };
    const sandbox = {
        window: {PixivBatch: {queueTypes: registry, modes: {}}},
        document: {addEventListener() {}, getElementById() { return null; }, querySelectorAll() { return []; }},
        state: {mode: 'quick-fetch', settings: {}, queue: []},
        console: {warn() {}, log() {}, error() {}},
        Map, Set, Promise, URL, URLSearchParams, AbortController,
        BASE: '', QUICK_FETCH_MODE: 'quick-fetch',
        fetch() {
            const response = responses.shift();
            return response && response.promise
                ? response.promise.then(body => ({ok: true, json: () => Promise.resolve(body)}))
                : Promise.resolve({ok: true, json: () => Promise.resolve(response || {})});
        },
        bt(_key, fallback) { return fallback; },
        esc(value) { return String(value); },
        resetPreviewCollapse() {},
        updateExtraFiltersCardVisibility() {},
        updateSaveScheduleCardVisibility() {},
        applyNovelSettingsVisibility() {}
    };
    vm.createContext(sandbox);
    vm.runInContext(QUICK_SOURCE + '\nwindow.__pagedQuickTest = '
        + '{quickState, quickInner, fetchQuickInnerUserPage, fetchQuickInnerCollectionPage, '
        + 'quickAddAllToQueue, quickUserAcquisitionsForAction};', sandbox);
    const exposed = sandbox.window.__pagedQuickTest;
    exposed.quickInner.userId = 'user-1';
    exposed.quickInner.userPageStates = new Map();
    return {sandbox, acquisition, ...exposed};
}

test('quick user-page hook 连续两页推进 cursor 且外层陈旧请求被拒绝', async () => {
    const contexts = [];
    const stale = deferred();
    const responses = [
        {items: [{id: '1'}], total: 3, nextCursor: 'cursor-2', hasMore: true},
        {items: [{id: '2'}], total: 3, nextCursor: 'cursor-3', hasMore: true},
        stale
    ];
    const h = quickHarness(responses, contexts);

    const first = await h.fetchQuickInnerUserPage(h.acquisition, 'user-1', 1);
    const second = await h.fetchQuickInnerUserPage(h.acquisition, 'user-1', 2);
    assert.strictEqual(first.items[0].id, '1');
    assert.strictEqual(second.items[0].id, '2');
    assert.deepStrictEqual(contexts.slice(0, 2).map(context => ({
        offset: context.offset, limit: context.limit, cursor: context.cursor
    })), [
        {offset: 0, limit: 2, cursor: null},
        {offset: 2, limit: 2, cursor: 'cursor-2'}
    ]);

    const staleRequest = h.fetchQuickInnerUserPage(h.acquisition, 'user-1', 3);
    h.quickState.loadSeq++;
    stale.resolve({items: [{id: 'stale'}], total: 5, nextCursor: '', hasMore: false});
    await assert.rejects(staleRequest, error => error && error.code === 'STALE_ACQUISITION');
    assert.strictEqual(h.quickInner.userPageStates.get('demo').pages.has(3), false);
});

test('quick collection-page hook 连续两页推进 cursor 且只消费页内 works', async () => {
    const contexts = [];
    const h = quickHarness([
        {works: [{id: 'c-1'}], total: 3, nextCursor: 'collection-2', hasMore: true},
        {works: [{id: 'c-2'}], total: 3, nextCursor: '', hasMore: false}
    ], []);
    const action = {
        ownerType: 'demo',
        buildCollectionWorksPageRequest(_collectionId, context) {
            contexts.push({...context});
            return {endpoint: `/api/collection?page=${context.page}`};
        }
    };
    h.quickInner.collectionPageState = {pages: new Map(), cursors: new Map(), total: 0};

    const first = await h.fetchQuickInnerCollectionPage(action, 'collection-1', 1);
    const second = await h.fetchQuickInnerCollectionPage(action, 'collection-1', 2);

    assert.deepStrictEqual(Array.from(first.items, item => item.id), ['c-1']);
    assert.deepStrictEqual(Array.from(second.items, item => item.id), ['c-2']);
    assert.deepStrictEqual(contexts.map(context => ({
        offset: context.offset, limit: context.limit, cursor: context.cursor
    })), [
        {offset: 0, limit: 2, cursor: null},
        {offset: 2, limit: 2, cursor: 'collection-2'}
    ]);
});

test('quick host 保留旧 buildUserIdsRequest + buildCardsRequest 降级分支', () => {
    assert.match(QUICK_SOURCE,
        /typeof acq\.buildUserIdsRequest === 'function'[\s\S]*typeof acq\.buildCardsRequest === 'function'/);
    assert.match(QUICK_SOURCE,
        /if \(typeof acq\.buildUserPageRequest === 'function'\)[\s\S]*else \{/);
    assert.match(QUICK_SOURCE,
        /typeof action\.buildCollectionWorksPageRequest !== 'function'[\s\S]*typeof action\.buildCollectionWorksRequest !== 'function'/);
});

function quickAddAllHarness(pageResponses, fetchImpl) {
    const requests = [];
    const addCalls = [];
    const statuses = [];
    const button = element();
    const action = {
        viewType: 'works-list', kind: 'douyin', cursorPaging: true, initialCursor: '0',
        buildPageRequest(context) {
            return {endpoint: `/api/douyin/me/liked?cursor=${encodeURIComponent(context.cursor)}&pageSize=${context.limit}`};
        },
        load() {}
    };
    const douyin = {
        type: 'douyin', pageSize: 2,
        dataSource: {id: 'douyin', displayNamespace: 'douyin', displayI18nKey: 'source.douyin'},
        actions: {'douyin-liked': action},
        queueId(item) { return String(item.id); },
        gridCardId(_prefix, index) { return `quick-${index}`; },
        buildQueueMeta(item) { return {kind: 'douyin', title: item.title || '', typeData: {douyinId: item.id}}; },
        buildQueueMetaFromId(id, context) {
            return {kind: 'douyin', typeData: {douyinId: String(id), accountId: context.accountId}};
        }
    };
    const illust = {
        type: 'illust', pageSize: 2,
        dataSource: {id: 'pixiv', displayNamespace: 'batch', displayI18nKey: 'data-source.pixiv'},
        actions: {},
        buildUserIdsRequest() {}, buildCardsRequest() {}
    };
    const novel = {
        type: 'novel', pageSize: 2,
        dataSource: {id: 'pixiv', displayNamespace: 'batch', displayI18nKey: 'data-source.pixiv'},
        actions: {},
        buildUserPageRequest() {}
    };
    const acquisitions = [illust, novel, douyin];
    const registry = {
        acquisitionList() { return acquisitions; },
        acquisition(type) { return acquisitions.find(candidate => candidate.type === type) || null; },
        resolveTypeForMode(value) { return value; },
        supports(type) { return acquisitions.some(candidate => candidate.type === type); },
        prepareAcquisitionRequest(_type, _mode, url) {
            return {url, init: {}, assertCurrent() {}, isCurrent() { return true; }};
        }
    };
    const sandbox = {
        window: {PixivBatch: {queueTypes: registry, modes: {}}},
        document: {
            addEventListener() {},
            getElementById(id) { return id === 'quick-add-all' ? button : null; },
            querySelectorAll() { return []; }
        },
        state: {mode: 'quick-fetch', settings: {}, queue: []},
        console: {warn() {}, log() {}, error() {}},
        Map, Set, Promise, URL, URLSearchParams, AbortController,
        BASE: '', QUICK_FETCH_MODE: 'quick-fetch',
        fetch(url) {
            requests.push(String(url));
            if (fetchImpl) return fetchImpl(url);
            const body = pageResponses.shift() || {};
            return Promise.resolve({ok: true, json: () => Promise.resolve(body)});
        },
        bt(_key, fallback, args) {
            let text = fallback;
            Object.entries(args || {}).forEach(([key, value]) => {
                text = text.replace(`{${key}}`, String(value));
            });
            return text;
        },
        esc(value) { return String(value); },
        uiConfirmKey: async () => true,
        setStatus(message, level) { statuses.push({message, level}); },
        addItemsToQueue(ids, metas) {
            addCalls.push({ids: Array.from(ids), metas: Array.from(metas)});
            return ids.length;
        },
        resetPreviewCollapse() {},
        updateExtraFiltersCardVisibility() {},
        updateSaveScheduleCardVisibility() {},
        applyNovelSettingsVisibility() {}
    };
    vm.createContext(sandbox);
    vm.runInContext(QUICK_SOURCE + '\nwindow.__quickAddAllTest = '
        + '{quickState, quickAddAllToQueue, quickUserAcquisitionsForAction};', sandbox);
    const exposed = sandbox.window.__quickAddAllTest;
    Object.assign(exposed.quickState, {
        action: 'douyin-liked', ownerType: 'douyin', kind: 'douyin',
        rawItems: [{id: 'current'}], items: [{id: 'current'}], allIds: [],
        total: 4, page: 1, pageSize: 2, viewType: null
    });
    return {exposed, requests, addCalls, statuses, action};
}

test('cursor quick 全部加入顺序推进真实游标、去重并只在完整成功后入队', async () => {
    const h = quickAddAllHarness([
        {items: [{id: '1'}, {id: '2'}], total: 4, nextCursor: 'c2', hasMore: true},
        {items: [{id: '2'}, {id: '3'}], total: 4, nextCursor: 'c3', hasMore: true},
        {items: [{id: '4'}], total: 4, nextCursor: '', hasMore: false}
    ]);
    await h.exposed.quickAddAllToQueue();

    assert.deepStrictEqual(h.requests.map(url => new URL(url, 'https://local.test').searchParams.get('cursor')),
        ['0', 'c2', 'c3']);
    assert.strictEqual(h.addCalls.length, 1);
    assert.deepStrictEqual(h.addCalls[0].ids, ['1', '2', '3', '4']);
});

test('cursor quick 游标重复或为空时不会把部分结果加入队列', async () => {
    for (const badNextCursor of ['c2', '']) {
        const responses = badNextCursor
            ? [
                {items: [{id: '1'}], nextCursor: 'c2', hasMore: true},
                {items: [{id: '2'}], nextCursor: badNextCursor, hasMore: true}
            ]
            : [{items: [{id: '1'}], nextCursor: '', hasMore: true}];
        const h = quickAddAllHarness(responses);
        await h.exposed.quickAddAllToQueue();
        assert.strictEqual(h.addCalls.length, 0);
        assert.ok(h.statuses.some(status => status.level === 'error'));
    }
});

test('cursor quick 全部加入在来源切换后静默丢弃旧请求结果', async () => {
    const pending = deferred();
    const h = quickAddAllHarness([], () => pending.promise.then(body => ({
        ok: true,
        json: () => Promise.resolve(body)
    })));
    const operation = h.exposed.quickAddAllToQueue();
    await Promise.resolve();
    h.exposed.quickState.loadSeq++;
    h.exposed.quickState.action = null;
    pending.resolve({items: [{id: 'stale'}], nextCursor: '', hasMore: false});

    await operation;

    assert.strictEqual(h.addCalls.length, 0);
    assert.strictEqual(h.statuses.some(status => status.level === 'error'), false);
});

test('quick allIds 快路径不再分页请求并把 owner 账号上下文写入全部 meta', async () => {
    const h = quickAddAllHarness([]);
    h.action.cursorPaging = false;
    h.action.allIdsFastPath = true;
    Object.assign(h.exposed.quickState, {
        accountOwner: 'douyin', uid: 'douyin-owner',
        rawItems: [], items: [], allIds: ['1', '2', '3'], total: 3
    });

    await h.exposed.quickAddAllToQueue();

    assert.strictEqual(h.requests.length, 0);
    assert.strictEqual(h.addCalls.length, 1);
    assert.deepStrictEqual(h.addCalls[0].ids, ['1', '2', '3']);
    assert.deepStrictEqual(h.addCalls[0].metas.map(meta => meta.typeData.accountId),
        ['douyin-owner', 'douyin-owner', 'douyin-owner']);
});

test('Pixiv 关注用户钻取只选择 action 显式允许的类型，不混入 Douyin user acquisition', () => {
    const h = quickAddAllHarness([]);
    const selected = h.exposed.quickUserAcquisitionsForAction({
        ownerType: 'illust', userWorkTypes: ['illust', 'novel']
    });
    assert.deepStrictEqual(Array.from(selected, acquisition => acquisition.type), ['illust', 'novel']);
});
