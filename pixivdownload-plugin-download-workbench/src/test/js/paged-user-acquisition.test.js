'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const test = require('node:test');
const vm = require('vm');

const STATIC = path.join(__dirname, '..', '..', 'main', 'resources', 'static', 'pixiv-batch');
const modeSource = files => files
    .map(file => fs.readFileSync(path.join(STATIC, 'modes', file), 'utf8')).join('\n');
const USER_SOURCE = modeSource(['user-core.js', 'user-data.js', 'user-view.js', 'user.js']);
const QUICK_SOURCE = modeSource([
    'quick-fetch-core.js', 'quick-fetch-outer.js', 'quick-fetch-inner.js', 'quick-fetch.js'
]);

function deferred() {
    let resolve;
    let reject;
    const promise = new Promise((ok, fail) => { resolve = ok; reject = fail; });
    return {promise, resolve, reject};
}

function element() {
    const classes = new Set();
    return {
        innerHTML: '', textContent: '', style: {}, disabled: false, value: '',
        classList: {
            toggle(name, force) { if (force ?? !classes.has(name)) classes.add(name); else classes.delete(name); },
            remove(name) { classes.delete(name); }, contains(name) { return classes.has(name); }
        },
        querySelectorAll() { return []; }
    };
}

function userHarness(acquisition, fetchImpl) {
    const statuses = [];
    const addCalls = [];
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
        setStatus(message, level) { statuses.push({message, level}); },
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
        uiLang() { return 'en-US'; },
        summaryJoin(parts) { return parts.join(' / '); },
        summarySeparator() { return ' / '; },
        getInlineSearchBookmarkCount() { return null; },
        getCachedSearchMeta() { return null; },
        fetchThumbnailBlobUrl: async () => null,
        addItemsToQueue(ids, metas) {
            addCalls.push({ids: Array.from(ids), metas: Array.from(metas)});
            return new Set(ids).size;
        },
        removeFromQueue() { return false; },
        saveSettings() {},
        applyKindSwitcherUI() {},
        applyNovelSettingsVisibility() {},
        applySearchKindUI() {},
        uiAlertKey: async () => {},
        uiConfirmKey: async () => true
    };
    vm.createContext(sandbox);
    const fixtureBindings = {...sandbox};
    vm.runInContext(fs.readFileSync(path.join(STATIC, 'queue-display.js'), 'utf8'), sandbox);
    vm.runInContext(fs.readFileSync(path.join(STATIC, 'batch-core.js'), 'utf8'), sandbox);
    Object.assign(sandbox, fixtureBindings);
    vm.runInContext(USER_SOURCE
        + '\nwindow.__pagedUserTest = {userState, loadUserPreviewPage, renderUserResults,'
        + ' buildUserQueueMeta, addAllUserResultsToQueue, resetUserState, clearUserPreview};', sandbox);
    const state = sandbox.window.__pagedUserTest.userState;
    Object.assign(state, {
        kind: 'demo', variant: 'demo', userId: 'user-1', username: 'User',
        pagedAcquisition: typeof acquisition.fetchPage === 'function',
        total: 4, totalPages: 2, allIds: [], pageCache: new Map(), pageCursors: new Map()
    });
    return {
        sandbox,
        state,
        elements,
        statuses,
        addCalls,
        loadPage: sandbox.window.__pagedUserTest.loadUserPreviewPage,
        renderResults: sandbox.window.__pagedUserTest.renderUserResults,
        buildMeta: sandbox.window.__pagedUserTest.buildUserQueueMeta,
        addAll: sandbox.window.__pagedUserTest.addAllUserResultsToQueue,
        reset: sandbox.window.__pagedUserTest.resetUserState,
        clear: sandbox.window.__pagedUserTest.clearUserPreview
    };
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

test('user 空态钩子获得 variant 上下文并替换通用文案', async () => {
    const contexts = [];
    const acquisition = {
        pageSize: 2,
        fetchPage() {
            return Promise.resolve({items: [], total: 0, nextCursor: '', hasMore: false});
        },
        queueId(item) { return String(item.id); },
        cardId(index) { return `card-${index}`; },
        render() {},
        buildQueueMeta() { return {}; },
        emptyMessage(context) {
            contexts.push({...context});
            return 'Fixture empty list';
        }
    };
    const h = userHarness(acquisition, () => {
        throw new Error('paged acquisition must not call fetch directly');
    });
    h.state.variant = 'demo-hidden-list';
    h.sandbox.bt = key => key;

    await h.loadPage(1);

    assert.match(h.elements['user-results-area'].innerHTML, /Fixture empty list/);
    const status = h.statuses[h.statuses.length - 1];
    assert.strictEqual(status.level, 'warning');
    assert.deepStrictEqual(JSON.parse(JSON.stringify(status.message.userEmpty.context)), {
        userId: 'user-1', username: 'User', variant: 'demo-hidden-list'
    });
    assert.deepStrictEqual(contexts, [{
        userId: 'user-1', username: 'User', variant: 'demo-hidden-list'
    }]);

    h.renderResults();
    assert.strictEqual(contexts.length, 2);
    assert.strictEqual(contexts[1].variant, 'demo-hidden-list');

    acquisition.emptyMessage = () => null;
    h.renderResults();
    assert.match(h.elements['user-results-area'].innerHTML, /status.user-no-artworks/);
});

test('user 单项与全部入队 meta 上下文均传递 variant', async () => {
    const itemContexts = [];
    const idContexts = [];
    const acquisition = {
        pageSize: 2,
        queueId(item) { return String(item.id); },
        cardId(index) { return `card-${index}`; },
        render() {},
        buildQueueMeta(item, context) {
            itemContexts.push({item: item.id, context: {...context}});
            return {id: item.id};
        },
        buildQueueMetaFromId(id, context) {
            idContexts.push({id, context: {...context}});
            return {id};
        }
    };
    const h = userHarness(acquisition, () => {
        throw new Error('legacy allIds fast path must not call fetch');
    });
    Object.assign(h.state, {
        variant: 'demo-secondary',
        pagedAcquisition: false,
        allIds: ['1', '2'],
        total: 2
    });

    h.buildMeta({id: 'single'});
    await h.addAll();

    const expected = {userId: 'user-1', username: 'User', variant: 'demo-secondary'};
    assert.deepStrictEqual(itemContexts, [{item: 'single', context: expected}]);
    assert.deepStrictEqual(idContexts, [
        {id: '1', context: expected},
        {id: '2', context: expected}
    ]);
    assert.deepStrictEqual(h.addCalls[0].ids, ['1', '2']);
});

test('中性 user fetchPage 的旧响应不会覆盖后发分页结果', async () => {
    const first = deferred();
    const second = deferred();
    const signals = [];
    const acquisition = {
        pageSize: 2,
        fetchPage(_userId, context) {
            signals.push(context.signal);
            return context.page === 1 ? first.promise : second.promise;
        },
        queueId(item) { return String(item.id); },
        cardId(index) { return `card-${index}`; },
        render() {},
        buildQueueMeta() { return {}; }
    };
    const h = userHarness(acquisition, () => Promise.reject(new Error('unexpected fetch')));
    h.state.pageCursors.set(2, 'cursor-2');

    const oldLoad = h.loadPage(1);
    const newLoad = h.loadPage(2);
    assert.strictEqual(signals[0].aborted, true);
    assert.strictEqual(signals[1].aborted, false);
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

test('清空 user 预览会中止当前分页链且不显示取消错误', async () => {
    const started = deferred();
    let requestSignal = null;
    const acquisition = {
        pageSize: 2,
        fetchPage(_userId, context) {
            requestSignal = context.signal;
            started.resolve();
            return new Promise((_resolve, reject) => {
                const rejectAbort = () => {
                    const error = new Error('aborted');
                    error.name = 'AbortError';
                    reject(error);
                };
                if (context.signal.aborted) rejectAbort();
                else context.signal.addEventListener('abort', rejectAbort, {once: true});
            });
        },
        queueId(item) { return String(item.id); },
        cardId(index) { return `card-${index}`; },
        render() {},
        buildQueueMeta() { return {}; }
    };
    const h = userHarness(acquisition, () => Promise.reject(new Error('unexpected fetch')));
    h.reset('demo', 'demo');
    Object.assign(h.state, {
        userId: 'user-a', username: 'User A', pagedAcquisition: true,
        total: 2, totalPages: 1
    });

    const load = h.loadPage(1);
    await started.promise;
    h.clear();

    assert.ok(requestSignal);
    assert.strictEqual(requestSignal.aborted, true);
    await load;
    assert.strictEqual(h.statuses.some(status => status.level === 'error'), false);
    assert.strictEqual(h.state.pageCache.has(1), false);
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
    const elements = new Map();
    const listeners = new Map();
    const registry = {
        resolveTypeForMode(value) { return value; },
        acquisition() { return acquisition; },
        acquisitionList() { return [acquisition]; },
        prepareAcquisitionRequest(_type, _mode, url) {
            return {url, init: {}, assertCurrent() {}};
        },
        supports() { return true; }
    };
    const acquisition = {
        type: 'demo', pageSize: 2,
        actions: {
            following: {userWorkTypes: ['demo']},
            collections: {buildCollectionWorksRequest(id) { return {endpoint: `/api/collection/${id}`}; }}
        },
        innerCardHtml(item) { return `<div>${item.id}</div>`; },
        render(items) { elements.get('quick-preview-area').innerHTML = items.map(item => item.id).join(','); },
        skipThumbnail: true,
        buildUserPageRequest(_userId, context) {
            contexts.push({...context});
            return {endpoint: `/api/user?page=${context.page}`};
        }
    };
    const sandbox = {
        window: {PixivBatch: {queueTypes: registry, modes: {}}},
        document: {
            addEventListener(name, listener) { listeners.set(name, listener); },
            querySelector() { return null; }, querySelectorAll() { return []; },
            getElementById(id) {
                if (!elements.has(id)) elements.set(id, {...element(), scrollIntoView() {}});
                return elements.get(id);
            }
        },
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
        normalizeSearchFilters(value) { return value || {}; },
        getSearchFiltersFromUI() { return {}; },
        hasBookmarkFilter() { return false; }, hasExtraSearchFilter() { return false; },
        matchSearchFilters() { return true; },
        computeFilteredItems: async items => ({filtered: items, stats: {rawCount: items.length,
            filteredCount: items.length, bookmarkMetaMissing: 0, bookmarkFilterActive: false}}),
        updateExtraFiltersCardVisibility() {},
        updateSaveScheduleCardVisibility() {},
        applyNovelSettingsVisibility() {}
    };
    vm.createContext(sandbox);
    vm.runInContext(QUICK_SOURCE + '\nwindow.__pagedQuickTest = '
        + '{quickState, quickInner, fetchQuickInnerUserPage, fetchQuickInnerCollectionPage, '
        + 'quickAddAllToQueue, quickUserAcquisitionsForAction, quickEnterFollowingUser, quickEnterCollection};', sandbox);
    const exposed = sandbox.window.__pagedQuickTest;
    exposed.quickInner.userId = 'user-1';
    exposed.quickInner.userPageStates = new Map();
    return {sandbox, acquisition, elements, listeners, ...exposed};
}

for (const [label, name, args] of [
    ['收藏', 'loadQuickIllustBookmarks', page => ['demo', 'show', page]],
    ['我的作品', 'loadQuickMyWorks', page => ['demo', page]],
    ['约稿', 'loadQuickMyRequest', page => ['demo', page]],
    ['关注', 'loadQuickFollowing', page => ['show', (page - 1) * 24, 'demo']],
    ['关注新作', 'loadQuickFollowingNew', page => ['demo', page]]
]) {
    test(`经典${label}后发分页完成后不再显示旧分页响应`, async () => {
        const old = deferred();
        const h = quickHarness([old, {items: [{id: 'new'}], users: [{userId: 'new'}], total: 120}], []);
        Object.assign(h.acquisition, {
            buildCardsRequest: () => '/api/cards',
            actions: {fixture: {buildPageRequest: () => '/api/page',
                buildIdsRequest: () => '/api/ids', buildCardsRequest: () => '/api/cards'}}
        });
        Object.assign(h.quickState, {kind: 'demo', action: 'fixture', uid: 'account', accountOwner: 'demo',
            allIds: ['1', '2', '3', '4', '5', '6']});
        h.elements.set('quick-preview-area', element());
        h.sandbox.renderQuickFollowingGrid = () => {};
        const pending = h.sandbox[name](...args(2));
        await h.sandbox[name](...args(3));
        old.resolve({items: [{id: 'old'}], users: [{userId: 'old'}], total: 120});
        await Promise.allSettled([pending]);
        assert.strictEqual(h.quickState.page, 3);
        const ids = name === 'loadQuickFollowing' ? h.quickState.followingAll.map(item => item.userId)
            : h.quickState.items.map(item => item.id);
        assert.deepStrictEqual(Array.from(ids), ['new']);
    });
}

test('经典关注切换用户后迟到的旧 ID 不能替换当前缓存', async () => {
    const old = deferred();
    const h = quickHarness([old, {ids: ['new-id']}, {items: [{id: 'new-card'}]}], []);
    delete h.acquisition.buildUserPageRequest;
    h.acquisition.buildUserIdsRequest = id => ({endpoint: `/api/user/${id}/ids`});
    h.acquisition.buildCardsRequest = id => ({endpoint: `/api/user/${id}/cards`});
    h.quickState.action = 'following';
    h.quickState.followingAll = [{userId: 'old-user'}, {userId: 'new-user'}];
    const pending = h.quickEnterFollowingUser(0);
    await h.quickEnterFollowingUser(1);
    old.resolve({ids: ['old-id']});
    await pending;
    assert.deepStrictEqual(Array.from(h.quickInner.idsByType.get('demo')), ['new-id']);
    assert.deepStrictEqual(Array.from(h.quickInner.rawItems, item => item.id), ['new-card']);
});

for (const collection of [false, true]) {
    test(`经典${collection ? '珍藏集' : '关注用户'}重复打开后旧失败不能覆盖当前预览`, async () => {
        const old = deferred();
        const body = collection ? {works: [{id: 'current', kind: 'demo'}]}
            : {items: [{id: 'current'}], total: 1, hasMore: false};
        const h = quickHarness([old, body], []);
        h.quickState.action = collection ? 'collections' : 'following';
        h.quickState.items = [{id: 'same-collection'}];
        h.quickState.followingAll = [{userId: 'same-user'}];
        const enter = collection ? h.quickEnterCollection : h.quickEnterFollowingUser;
        const pending = enter(0);
        await enter(0);
        const current = h.elements.get('quick-inner-area').innerHTML;
        assert.ok(current.includes('current'));
        old.reject(new Error('obsolete request failed'));
        await pending;
        assert.strictEqual(h.elements.get('quick-inner-area').innerHTML, current);
    });

    test(`经典${collection ? '珍藏集' : '关注用户'}当前读取失败仍显示错误并可重试`, async () => {
        const failed = deferred();
        const body = collection ? {works: [{id: 'retry', kind: 'demo'}]}
            : {items: [{id: 'retry'}], total: 1, hasMore: false};
        const h = quickHarness([failed, body], []);
        h.sandbox.bt = key => key;
        h.quickState.action = collection ? 'collections' : 'following';
        h.quickState.items = [{id: 'collection'}];
        h.quickState.followingAll = [{userId: 'user'}];
        const enter = collection ? h.quickEnterCollection : h.quickEnterFollowingUser;
        const pending = enter(0);
        failed.reject(new Error('current request failed'));
        await pending;
        assert.ok(h.elements.get('quick-inner-area').innerHTML.includes('quick.error.load-failed'));
        await enter(0);
        assert.ok(h.elements.get('quick-inner-area').innerHTML.includes('retry'));
    });
}

test('经典关注用户取得 ID 后的当前卡片失败仍显示错误', async () => {
    const failed = deferred();
    const h = quickHarness([{ids: ['id']}, failed], []);
    delete h.acquisition.buildUserPageRequest;
    h.acquisition.buildUserIdsRequest = id => ({endpoint: `/api/user/${id}/ids`});
    let requested;
    const started = new Promise(resolve => { requested = resolve; });
    h.acquisition.buildCardsRequest = id => {
        requested();
        return {endpoint: `/api/user/${id}/cards`};
    };
    h.sandbox.bt = key => key;
    h.quickState.action = 'following';
    h.quickState.followingAll = [{userId: 'user'}];
    const pending = h.quickEnterFollowingUser(0);
    await started;
    failed.reject(new Error('current cards failed'));
    await pending;
    assert.ok(h.elements.get('quick-inner-area').innerHTML.includes('quick.error.load-failed'));
});

for (const collection of [false, true]) {
    test(`经典${collection ? '珍藏集' : '关注用户'}分页失败显示提示且保留重试`, async () => {
        const body = (id, hasMore) => ({items: [{id, kind: 'demo'}], total: 3,
            nextCursor: hasMore ? 'next' : '', hasMore});
        const h = quickHarness([body('first', true), {error: 'page failed'}, body('retry', false)], []);
        h.sandbox.bt = key => key;
        h.acquisition.actions.collections.buildCollectionWorksPageRequest = () => '/api/collection/page';
        h.quickState.action = collection ? 'collections' : 'following';
        h.quickState.items = [{id: 'collection'}];
        h.quickState.followingAll = [{userId: 'user'}];
        await (collection ? h.quickEnterCollection : h.quickEnterFollowingUser)(0);
        const load = h.quickInner._jumpFn;
        let pending;
        h.quickInner._jumpFn = page => {
            pending = load(page);
            pending.catch(() => {});
            return pending;
        };
        await h.sandbox.quickInnerJumpPage(2);
        await pending.catch(() => {});
        assert.ok(h.elements.get('quick-inner-area').innerHTML.includes('quick.error.load-failed'));
        await h.sandbox.quickInnerJumpPage(2);
        assert.ok(h.elements.get('quick-inner-area').innerHTML.includes('retry'));
        assert.strictEqual(h.quickInner.page, 2);
    });
}

test('经典外层分页忽略旧失败，当前失败显示提示且动作可以重试', async () => {
    const old = deferred();
    const h = quickHarness([old, {items: [{id: 'new'}]}, {error: 'page failed'}, {items: [{id: 'retry'}]}], []);
    h.acquisition.actions.fixture = {buildPageRequest: () => '/api/page'};
    Object.assign(h.quickState, {kind: 'demo', action: 'fixture'});
    h.elements.set('quick-preview-area', element());
    h.sandbox.bt = key => key;
    h.quickState._jumpFn = page => h.sandbox.loadQuickFollowingNew('demo', page);
    const pending = h.sandbox.quickJumpPage(2);
    await h.sandbox.quickJumpPage(3);
    old.reject(new Error('obsolete failure'));
    await pending;
    assert.strictEqual(h.elements.get('quick-preview-area').innerHTML, 'new');
    await h.sandbox.quickJumpPage(4);
    assert.ok(h.elements.get('quick-preview-area').innerHTML.includes('quick.error.load-failed'));
    await h.sandbox.loadQuickFollowingNew('demo', 1);
    assert.strictEqual(h.elements.get('quick-preview-area').innerHTML, 'retry');
});

test('经典关注切换作品类型的失败沿用分页提示并可重新选择', async () => {
    const h = quickHarness([{ids: ['work']}, {items: [{id: 'first'}]},
        {error: 'cards failed'}, {items: [{id: 'retry'}]}], []);
    delete h.acquisition.buildUserPageRequest;
    h.acquisition.buildUserIdsRequest = () => '/api/ids';
    h.acquisition.buildCardsRequest = () => '/api/cards';
    h.quickState.action = 'following';
    h.quickState.followingAll = [{userId: 'user'}];
    h.sandbox.bt = key => key;
    await h.quickEnterFollowingUser(0);
    h.listeners.get('change')({target: {name: 'quick-inner-kind', value: 'demo'}});
    await new Promise(resolve => setImmediate(resolve));
    assert.ok(h.elements.get('quick-inner-area').innerHTML.includes('quick.error.load-failed'));
    h.listeners.get('change')({target: {name: 'quick-inner-kind', value: 'demo'}});
    await new Promise(resolve => setImmediate(resolve));
    assert.ok(h.elements.get('quick-inner-area').innerHTML.includes('retry'));
});

test('经典关注加载新类型期间不提供旧列表的批量入队操作', async () => {
    const cards = deferred();
    const h = quickHarness([{ids: ['first']}, {items: [{id: 'first'}]}, cards], []);
    delete h.acquisition.buildUserPageRequest;
    h.acquisition.buildUserIdsRequest = () => '/api/ids';
    h.acquisition.buildCardsRequest = () => '/api/cards';
    h.quickState.action = 'following';
    h.quickState.followingAll = [{userId: 'user'}];
    await h.quickEnterFollowingUser(0);
    h.quickInner.idsByType.set('other', ['second']);
    h.acquisition.actions.following.userWorkTypes.push('other');
    assert.strictEqual(h.elements.get('quick-inner-add-all').style.display, '');

    h.listeners.get('change')({target: {name: 'quick-inner-kind', value: 'other'}});
    assert.strictEqual(h.elements.get('quick-inner-add-all').style.display, 'none');
    assert.strictEqual(h.elements.get('quick-inner-add-page').style.display, 'none');
    cards.resolve({items: [{id: 'second'}]});
    await new Promise(resolve => setImmediate(resolve));
    assert.strictEqual(h.elements.get('quick-inner-add-all').style.display, '');
    assert.deepStrictEqual(Array.from(h.quickInner.rawItems, item => [item.id, item.kind]), [['second', 'other']]);
});

for (const requestWorks of [false, true]) {
    for (const stage of ['account', 'ids']) {
        test(`经典${requestWorks ? '约稿' : '我的作品'}在旧 ${stage} 返回后不继续读取或覆盖新分页`, async () => {
            const old = deferred();
            const responses = [old, ...(stage === 'account' ? [{id: 'current-account'}] : []),
                {ids: ['new-1', 'new-2', 'new-3']}, {items: [{id: 'current'}]}];
            const h = quickHarness(responses, []);
            let requests = 0;
            const fetch = h.sandbox.fetch;
            h.sandbox.fetch = (...args) => { requests++; return fetch(...args); };
            Object.assign(h.acquisition, {account: {buildRequest: () => '/api/account', readId: body => body.id},
                buildMyWorksIdsRequest: () => '/api/ids', buildCardsRequest: () => '/api/cards',
                actions: {fixture: {buildIdsRequest: () => '/api/ids', buildCardsRequest: () => '/api/cards'}}});
            Object.assign(h.quickState, {kind: 'demo', action: 'fixture',
                uid: stage === 'account' ? null : 'current-account', accountOwner: 'demo'});
            h.elements.set('quick-preview-area', element());
            const load = requestWorks ? h.sandbox.loadQuickMyRequest : h.sandbox.loadQuickMyWorks;
            const pending = load('demo', 1);
            await load('demo', 2);
            old.resolve(stage === 'account' ? {id: 'obsolete-account'} : {ids: ['obsolete']});
            await pending;
            assert.strictEqual(h.quickState.uid, 'current-account');
            assert.deepStrictEqual(Array.from(h.quickState.allIds), ['new-1', 'new-2', 'new-3']);
            assert.strictEqual(h.quickState.page, 2);
            assert.strictEqual(h.elements.get('quick-preview-area').innerHTML, 'current');
            assert.strictEqual(requests, stage === 'account' ? 4 : 3);
        });
    }
}

test('经典外层分页筛选迟到时不能恢复旧卡片或旧页码', async () => {
    const filtering = deferred();
    const h = quickHarness([{items: [{id: 'old'}]}, {items: [{id: 'new'}]}], []);
    h.acquisition.actions.fixture = {buildPageRequest: () => '/api/page'};
    Object.assign(h.quickState, {kind: 'demo', action: 'fixture'});
    h.elements.set('quick-preview-area', element());
    let started;
    const ready = new Promise(resolve => { started = resolve; });
    const compute = h.sandbox.computeFilteredItems;
    h.sandbox.computeFilteredItems = (items, ...args) => {
        if (items[0].id === 'old') { started(); return filtering.promise; }
        return compute(items, ...args);
    };
    const pending = h.sandbox.loadQuickFollowingNew('demo', 2);
    await ready;
    await h.sandbox.loadQuickFollowingNew('demo', 3);
    filtering.resolve({filtered: [{id: 'old'}], stats: {rawCount: 1, filteredCount: 1}});
    await pending;
    assert.strictEqual(h.quickState.page, 3);
    assert.strictEqual(h.elements.get('quick-preview-area').innerHTML, 'new');
    assert.ok(h.elements.get('quick-pagination').innerHTML.includes('disabled>3</button>'));
});

test('经典外层翻页保留正在全部取得的按钮禁用状态', async () => {
    const h = quickHarness([{items: [{id: 'page'}]}], []);
    h.acquisition.actions.fixture = {buildPageRequest: () => '/api/page'};
    Object.assign(h.quickState, {kind: 'demo', action: 'fixture'});
    h.elements.set('quick-preview-area', element());
    h.sandbox.setQuickBtnLoading('quick-add-all', true);
    await h.sandbox.loadQuickFollowingNew('demo', 2);
    assert.strictEqual(h.elements.get('quick-add-all').disabled, true);
});

for (const collection of [false, true]) {
    test(`经典${collection ? '珍藏集' : '用户'}全部取得的旧完成不能解锁新操作`, async () => {
        const old = deferred(), current = deferred();
        const body = (id, hasMore) => ({items: [{id, kind: 'demo'}], total: 3,
            nextCursor: hasMore ? 'next' : '', hasMore});
        const h = quickHarness([body('old-first', true), old, body('new-first', true), current], []);
        Object.assign(h.acquisition, {queueId: item => item.id, buildQueueMeta: () => ({kind: 'demo'})});
        h.acquisition.actions.collections.buildCollectionWorksPageRequest = () => '/api/collection/page';
        const added = [];
        Object.assign(h.sandbox, {setStatus() {}, uiConfirmKey: async () => true,
            addItemsToQueue(ids) { added.push(Array.from(ids)); return ids.length; }});
        h.quickState.action = collection ? 'collections' : 'following';
        h.quickState.items = [{id: 'old'}, {id: 'new'}];
        h.quickState.followingAll = [{userId: 'old'}, {userId: 'new'}];
        const enter = collection ? h.quickEnterCollection : h.quickEnterFollowingUser;
        await enter(0);
        const first = h.sandbox.quickInnerAddAllToQueue();
        await new Promise(resolve => setImmediate(resolve));
        await enter(1);
        assert.strictEqual(h.elements.get('quick-inner-add-all').disabled, false);
        const second = h.sandbox.quickInnerAddAllToQueue();
        await new Promise(resolve => setImmediate(resolve));
        old.resolve(body('old-last', false));
        await first;
        assert.strictEqual(h.elements.get('quick-inner-add-all').disabled, true);
        current.resolve(body('new-last', false));
        await second;
        assert.strictEqual(h.elements.get('quick-inner-add-all').disabled, false);
        assert.deepStrictEqual(added, [['new-first', 'new-last']]);
    });
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
    return {exposed, requests, addCalls, statuses, action, registry, button};
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

test('经典按页全部加入期间翻页不会遗漏后来显示的页面', async () => {
    const reading = deferred();
    let started;
    const ready = new Promise(resolve => { started = resolve; });
    const h = quickAddAllHarness([], url => {
        const page = Number(new URL(url, 'https://local.test').searchParams.get('page'));
        if (page === 2) {
            started();
            return reading.promise.then(body => ({ok: true, json: async () => body}));
        }
        return Promise.resolve({ok: true, json: async () => ({items: [{id: String(page)}]})});
    });
    h.action.cursorPaging = false;
    h.action.buildPageRequest = context => ({endpoint: `/api/fixture?page=${context.page}`});
    Object.assign(h.exposed.quickState, {total: 6, rawItems: [{id: '1'}], items: [{id: '1'}]});
    const pending = h.exposed.quickAddAllToQueue();
    await ready;
    Object.assign(h.exposed.quickState, {page: 3, rawItems: [{id: '3'}], items: [{id: '3'}]});
    reading.resolve({items: [{id: '2'}]});
    await pending;
    assert.deepStrictEqual(h.addCalls.map(call => call.ids), [['1', '2', '3']]);
});

test('经典按页全部加入在页响应交付时失效也不得提交旧结果', async () => {
    const h = quickAddAllHarness([{items: [{id: 'stale'}]}]);
    h.action.cursorPaging = false;
    h.registry.prepareAcquisitionRequest = (_type, _mode, url) => ({url, init: {},
        assertCurrent() { queueMicrotask(() => { h.exposed.quickState.loadSeq++; }); }});
    await h.exposed.quickAddAllToQueue();
    assert.deepStrictEqual(h.addCalls, []);
    assert.strictEqual(h.statuses.some(status => status.level === 'error'), false);
});

for (const cursor of [false, true]) {
    test(`经典${cursor ? '游标' : '按页'}全部加入的旧操作结束不能解锁新操作按钮`, async () => {
        const old = deferred(), current = deferred();
        const reads = [old, current];
        const h = quickAddAllHarness([], () => reads.shift().promise.then(body => ({ok: true, json: async () => body})));
        h.action.cursorPaging = cursor;
        const first = h.exposed.quickAddAllToQueue();
        await new Promise(resolve => setImmediate(resolve));
        h.exposed.quickState.loadSeq++;
        const second = h.exposed.quickAddAllToQueue();
        await new Promise(resolve => setImmediate(resolve));
        assert.strictEqual(h.requests.length, 2);
        assert.strictEqual(h.button.disabled, true);
        old.resolve({items: [{id: 'old'}], hasMore: false});
        await first;
        assert.strictEqual(h.button.disabled, true);
        current.resolve({items: [{id: 'current'}], hasMore: false});
        await second;
        assert.strictEqual(h.button.disabled, false);
        assert.strictEqual(h.addCalls.length, 1);
    });
}

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
