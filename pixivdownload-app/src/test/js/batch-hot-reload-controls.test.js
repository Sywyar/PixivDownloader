'use strict';
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');

const STATIC = path.join(__dirname, '..', '..', '..', '..',
    'pixivdownload-plugin-download-workbench', 'src', 'main', 'resources', 'static', 'pixiv-batch');
const SETTINGS_SOURCE = fs.readFileSync(path.join(STATIC, 'batch-settings.js'), 'utf8');
const INIT_SOURCE = fs.readFileSync(path.join(STATIC, 'batch-init.js'), 'utf8');
const SEARCH_SOURCE = fs.readFileSync(path.join(STATIC, 'modes', 'search.js'), 'utf8');
const USER_SOURCE = fs.readFileSync(path.join(STATIC, 'modes', 'user.js'), 'utf8');
const SERIES_SOURCE = fs.readFileSync(path.join(STATIC, 'modes', 'series.js'), 'utf8');
const QUICK_SOURCE = fs.readFileSync(path.join(STATIC, 'modes', 'quick-fetch.js'), 'utf8');

function makeLabel(kind) {
    const input = {checked: false};
    return {
        tagName: 'LABEL',
        dataset: {kind},
        parentNode: null,
        active: false,
        classList: {toggle(_name, value) { this.owner.active = value; }, owner: null},
        querySelector(selector) { return selector === 'input[type=radio]' ? input : null; },
        input
    };
}

const root = {
    dataset: {},
    labels: [],
    handlers: new Map(),
    addEventListener(type, handler) { this.handlers.set(type, handler); },
    querySelectorAll(selector) { return selector === 'label' ? this.labels : []; }
};

function attach(label) {
    label.parentNode = root;
    label.classList.owner = label;
    return label;
}

const documentHandlers = new Map();
const novelSettingsCard = {style: {display: 'none'}};
const document = {
    getElementById(id) {
        if (id === 'user-kind-switcher') return root;
        if (id === 'novel-settings-card') return novelSettingsCard;
        return null;
    },
    addEventListener(type, handler) { documentHandlers.set(type, handler); }
};
const sandbox = {
    window: {PixivBatch: {queueTypes: {
        contributionsOf(kind) {
            return kind === 'settings'
                ? [{type: 'novel', contributionKey: 'novel-settings-card', cardId: 'novel-settings-card'}]
                : [];
        }
    }}},
    document,
    state: {settings: {userKind: 'illust'}, mode: 'user'},
    SINGLE_IMPORT_MODE: 'single-import',
    QUICK_FETCH_MODE: 'quick-fetch',
    isAdmin: false,
    storeSet() { sandbox.saved = (sandbox.saved || 0) + 1; },
    console,
    Set,
    JSON
};
vm.createContext(sandbox);
vm.runInContext(SETTINGS_SOURCE, sandbox);

let passed = 0;
function ok(label, condition) {
    assert.ok(condition, label);
    passed++;
}

function modeElement({html = 'A preview', text = 'A meta', display = 'flex'} = {}) {
    return {
        innerHTML: html,
        textContent: text,
        style: {display},
        disabled: false,
        dataset: {},
        classList: {toggle() {}, remove() {}},
        querySelectorAll() { return []; }
    };
}

function realModeHarness(source, stateExpression, elements, extra = {}) {
    const registry = {
        supports() { return false; },
        resolveTypeForMode() { return null; },
        acquisition() { return null; }
    };
    const modeDocument = {
        getElementById(id) { return elements[id] || null; },
        querySelectorAll() { return []; },
        querySelector() { return null; },
        addEventListener() {}
    };
    const modeSandbox = Object.assign({
        window: {PixivBatch: {queueTypes: registry, modes: {}}},
        document: modeDocument,
        state: {mode: 'user', settings: {userKind: 'owner-a', searchKind: 'owner-a'}, queue: []},
        console: {warn() {}, log() {}, error() {}},
        URL,
        URLSearchParams,
        Map,
        Set,
        Promise,
        QUICK_FETCH_MODE: 'quick-fetch',
        defaultSearchFilters: () => ({content: 'all'}),
        bt: (_key, fallback) => fallback,
        esc: value => String(value),
        resetPreviewCollapse() {},
        updateExtraFiltersCardVisibility() {},
        updateSaveScheduleCardVisibility() {},
        applyNovelSettingsVisibility() {}
    }, extra);
    vm.createContext(modeSandbox);
    vm.runInContext(source + '\nwindow.__realModeState = ' + stateExpression + ';', modeSandbox);
    return {sandbox: modeSandbox, state: modeSandbox.window.__realModeState};
}

sandbox.state.settings.userKind = 'novel';
sandbox.window.PixivBatch.settings.applyNovelSettingsVisibility();
ok('嵌套 settings contribution 在对应 user kind 下显示真实卡片',
    novelSettingsCard.style.display === '');
sandbox.state.settings.userKind = 'illust';
sandbox.window.PixivBatch.settings.applyNovelSettingsVisibility();
ok('嵌套 settings contribution 在其它 user kind 下隐藏真实卡片',
    novelSettingsCard.style.display === 'none');

const oldIllust = attach(makeLabel('illust'));
const oldNovel = attach(makeLabel('novel'));
root.labels = [oldIllust, oldNovel];
sandbox.testChanges = [];
vm.runInContext("bindKindSwitcher('user-kind-switcher', 'userKind', function (next) { testChanges.push(next); })", sandbox);
ok('kind switcher 只在稳定 root 绑定一个 click listener', root.handlers.size === 1);

const newIllust = attach(makeLabel('illust'));
const newNovel = attach(makeLabel('novel'));
root.labels = [newIllust, newNovel];
const nestedTarget = {tagName: 'SPAN', parentNode: newNovel};
root.handlers.get('click')({target: nestedTarget});
ok('hot reload 后新 kind label click 仍更新 setting', sandbox.state.settings.userKind === 'novel');
ok('hot reload 后新 kind label click 仍触发原回调', sandbox.testChanges.join(',') === 'novel');
ok('hot reload 后新 kind label click 仍同步选中 UI', newNovel.active && newNovel.input.checked
    && !newIllust.active && !newIllust.input.checked);
ok('hot reload 后新 kind label click 仍持久化设置', sandbox.saved === 1);

let autosaves = 0;
sandbox.window.PixivBatch.settings.bindDelegatedSettingAutosave(document, () => { autosaves++; });
const reloadedNovelSetting = {id: 's-novel-format', type: 'select-one'};
documentHandlers.get('change')({type: 'change', target: reloadedNovelSetting});
ok('novel setting reload 后 change 仍触发 autosave', autosaves === 1);
const reloadedAiSetting = {id: 's-novel-translate-lang', type: 'text'};
documentHandlers.get('input')({type: 'input', target: reloadedAiSetting});
ok('AI setting reload 后 input 仍触发 autosave', autosaves === 2);
ok('batch init 使用稳定 document 设置委托',
    INIT_SOURCE.includes('bindDelegatedSettingAutosave(document)'));
ok('search submode switcher 使用稳定 root 事件委托',
    SEARCH_SOURCE.includes("root.addEventListener('click', event =>")
    && !SEARCH_SOURCE.includes("lbl.addEventListener('click'"));

{
    const elements = {
        'user-results-area': modeElement(),
        'user-pagination': modeElement({html: 'A pages'}),
        'btn-user-add-page': modeElement(),
        'btn-user-add-all': modeElement()
    };
    const h = realModeHarness(USER_SOURCE, 'userState', elements);
    Object.assign(h.state, {
        kind: 'owner-a',
        variant: 'owner-a',
        userId: '123',
        username: 'A user',
        allIds: ['1'],
        rawItems: [{id: '1'}],
        items: [{id: '1'}],
        cardCache: new Map([['owner-a:1', {id: '1'}]]),
        filterSummary: {rawCount: 1, filteredCount: 1, bookmarkMetaMissing: 0, bookmarkFilterActive: true}
    });
    const reconciled = h.sandbox.window.PixivBatch.modes.user.reconcileUserTypeAvailability();
    ok('真实 user reconcile 清空 publication A 的预览 state',
        reconciled && h.state.kind === null && h.state.userId === '' && h.state.username === ''
        && h.state.allIds.length === 0 && h.state.rawItems.length === 0 && h.state.items.length === 0
        && h.state.cardCache.size === 0 && h.sandbox.state.settings.userKind === 'owner-a');
    ok('真实 user reconcile 清掉旧结果 DOM 与分页',
        !elements['user-results-area'].innerHTML.includes('A preview')
        && elements['user-pagination'].style.display === 'none'
        && elements['user-pagination'].innerHTML === '');
    ok('真实 user reconcile 禁用旧预览入队按钮',
        elements['btn-user-add-page'].disabled && elements['btn-user-add-all'].disabled);
    h.state.items = [{id: 'B-1'}];
    h.state.allIds = ['B-1'];
    h.sandbox.updateUserQueueButtons();
    ok('publication B 新结果可重新启用 user 入队按钮',
        !elements['btn-user-add-page'].disabled && !elements['btn-user-add-all'].disabled);
}

{
    const elements = {
        'search-results-area': modeElement(),
        'search-pagination': modeElement({html: 'A pages'}),
        'btn-batch-add-page': modeElement(),
        'btn-batch-add-all': modeElement()
    };
    const h = realModeHarness(SEARCH_SOURCE, 'searchState', elements);
    Object.assign(h.state, {
        kind: 'owner-a',
        rawResults: [{id: '1'}],
        results: [{id: '1'}],
        total: 1,
        currentPage: 3,
        submode: 'batch',
        localPage: 2,
        batchInfo: {startPage: 1},
        currentWord: 'A word',
        noCookie: true,
        metaCache: {'1': {bookmarkCount: 10}},
        pixivPageCount: 1
    });
    const reconciled = h.sandbox.window.PixivBatch.modes.search.reconcileSearchTypeAvailability();
    ok('真实 search reconcile 清空 publication A 的预览 state',
        reconciled && h.state.kind === null && h.state.rawResults.length === 0
        && h.state.results.length === 0 && h.state.total === 0 && h.state.currentPage === 1
        && h.state.submode === 'search' && h.state.currentWord === ''
        && Object.keys(h.state.metaCache).length === 0
        && h.sandbox.state.settings.searchKind === 'owner-a');
    ok('真实 search reconcile 清掉旧结果 DOM 与分页',
        !elements['search-results-area'].innerHTML.includes('A preview')
        && elements['search-pagination'].style.display === 'none'
        && elements['search-pagination'].innerHTML === '');
    ok('真实 search reconcile 禁用旧预览入队按钮',
        elements['btn-batch-add-page'].disabled && elements['btn-batch-add-all'].disabled);
    h.state.submode = 'batch';
    h.state.results = [{id: 'B-1'}];
    h.sandbox.updateBatchQueueButtons();
    ok('publication B 新结果可重新启用 search 入队按钮',
        !elements['btn-batch-add-page'].disabled && !elements['btn-batch-add-all'].disabled);
}

{
    const elements = {
        'series-results-area': modeElement(),
        'series-meta-display': modeElement(),
        'series-pagination': modeElement({html: 'A pages'}),
        'btn-series-add-page': modeElement(),
        'btn-series-add-all': modeElement()
    };
    const h = realModeHarness(SERIES_SOURCE, 'seriesState', elements);
    Object.assign(h.state, {
        kind: 'owner-a',
        seriesId: '88',
        seriesTitle: 'A series',
        seriesAuthorId: '9',
        seriesAuthorName: 'A author',
        seriesTotal: 1,
        rawItems: [{id: '1'}],
        items: [{id: '1'}],
        allItems: [{id: '1'}],
        itemsByPage: new Map([[1, [{id: '1'}]]]),
        totalPages: 2
    });
    const reconciled = h.sandbox.window.PixivBatch.modes.series.reconcileSeriesTypeAvailability();
    ok('真实 series reconcile 清空 publication A 的预览 state',
        reconciled && h.state.kind === null && h.state.seriesId === null && h.state.seriesTitle === ''
        && h.state.rawItems.length === 0 && h.state.items.length === 0
        && h.state.allItems.length === 0 && h.state.itemsByPage.size === 0
        && h.sandbox.state.settings.userKind === 'owner-a');
    ok('真实 series reconcile 清掉旧结果 DOM、元数据与分页',
        !elements['series-results-area'].innerHTML.includes('A preview')
        && elements['series-meta-display'].textContent === ''
        && elements['series-pagination'].style.display === 'none'
        && elements['series-pagination'].innerHTML === '');
    ok('真实 series reconcile 禁用旧预览入队按钮',
        elements['btn-series-add-page'].disabled && elements['btn-series-add-all'].disabled);
    h.state.seriesId = 'B-88';
    h.state.items = [{id: 'B-1'}];
    h.sandbox.updateSeriesQueueButtons();
    ok('publication B 新结果可重新启用 series 入队按钮',
        !elements['btn-series-add-page'].disabled && !elements['btn-series-add-all'].disabled);
}

{
    const elements = {
        'quick-preview-area': modeElement(),
        'quick-pagination': modeElement({html: 'A pages'}),
        'quick-inner-section': modeElement(),
        'quick-preview-toolbar': modeElement(),
        'quick-add-page': modeElement(),
        'quick-add-all': modeElement(),
        'quick-following-search': modeElement()
    };
    const h = realModeHarness(QUICK_SOURCE, '({outer: quickState, inner: quickInner})', elements);
    Object.assign(h.state.outer, {
        action: 'owner-a-action',
        ownerType: 'owner-a',
        uid: '123',
        accountOwner: 'owner-a',
        viewType: 'works-list',
        kind: null,
        rawItems: [{id: '1'}],
        items: [{id: '1'}],
        allIds: ['1'],
        total: 1
    });
    Object.assign(h.state.inner, {
        open: true,
        type: 'following-user',
        userId: '456',
        kind: null,
        allIds: ['1'],
        rawItems: [{id: '1'}],
        items: [{id: '1'}],
        total: 1
    });
    const reconciled = h.sandbox.window.PixivBatch.modes.quick.reconcileQuickTypeAvailability();
    ok('真实 quick reconcile 清空 publication A 的外层与内层 state',
        reconciled && h.state.outer.action === null && h.state.outer.ownerType === null
        && h.state.outer.kind === null
        && h.state.outer.uid === null && h.state.outer.rawItems.length === 0
        && h.state.outer.items.length === 0 && h.state.outer.allIds.length === 0
        && !h.state.inner.open && h.state.inner.type === null && h.state.inner.kind === null
        && h.state.inner.allIds.length === 0 && h.state.inner.items.length === 0
        && h.sandbox.state.settings.userKind === 'owner-a');
    ok('真实 quick reconcile 清掉旧结果 DOM、分页与内层区域',
        !elements['quick-preview-area'].innerHTML.includes('A preview')
        && elements['quick-pagination'].style.display === 'none'
        && elements['quick-pagination'].innerHTML === ''
        && elements['quick-inner-section'].style.display === 'none');
    ok('真实 quick reconcile 隐藏并禁用旧预览入队按钮',
        elements['quick-preview-toolbar'].style.display === 'none'
        && elements['quick-add-page'].style.display === 'none'
        && elements['quick-add-page'].disabled
        && elements['quick-add-all'].style.display === 'none'
        && elements['quick-add-all'].disabled);
    h.sandbox.quickShowToolbar({showAdd: true, showSearch: false});
    ok('publication B 新结果可重新显示并启用 quick 入队按钮',
        elements['quick-preview-toolbar'].style.display === ''
        && elements['quick-add-page'].style.display === ''
        && !elements['quick-add-page'].disabled
        && elements['quick-add-all'].style.display === ''
        && !elements['quick-add-all'].disabled);
}

{
    let capturedContext = null;
    const contributedSource = Object.freeze({
        sourceType: 'owner-a.collection',
        source: {collectionId: 'collection-a'},
        kind: 'owner-a'
    });
    const registry = {
        acquisitionList(mode) {
            return mode === 'quick' ? [{
                type: 'owner-a',
                actions: {
                    'owner-a-collections': {
                        scheduleSource(context) {
                            capturedContext = context;
                            return contributedSource;
                        }
                    }
                }
            }] : [];
        },
        supports() { return true; },
        resolveTypeForMode(kind) { return kind; },
        acquisition() { return null; }
    };
    const h = realModeHarness(QUICK_SOURCE, '({outer: quickState, inner: quickInner})', {}, {
        window: {PixivBatch: {queueTypes: registry, modes: {}}}
    });
    h.sandbox.state.mode = 'quick-fetch';
    Object.assign(h.state.outer, {
        action: 'owner-a-collections',
        accountOwner: 'owner-a',
        uid: 'account-a',
        kind: 'owner-a'
    });
    Object.assign(h.state.inner, {
        open: true,
        type: 'collection',
        id: 'collection-a',
        name: 'Collection A',
        kind: 'owner-a'
    });
    const selected = h.sandbox.window.PixivBatch.modes.quick.quickScheduleSource();
    ok('quick 二层来源优先交给当前 owner 动作贡献',
        selected === contributedSource
        && capturedContext.action === 'owner-a-collections'
        && capturedContext.accountOwner === 'owner-a'
        && capturedContext.inner.id === 'collection-a');

    h.state.outer.action = 'owner-without-schedule-hook';
    Object.assign(h.state.inner, {
        open: true,
        type: 'following-user',
        userId: 'legacy-user',
        name: 'Legacy user',
        kind: 'illust'
    });
    const legacy = h.sandbox.window.PixivBatch.modes.quick.quickScheduleSource();
    ok('owner 未贡献二层来源时保留既有 Pixiv 用户回退',
        legacy.sourceType === 'user-new' && legacy.source.userId === 'legacy-user');
}

const runtimeListeners = new Map();
const previewClears = {user: 0, search: 0, series: 0, quick: 0};
let settingReconciles = 0;
const initSandbox = {
    window: {
        PixivBatch: {
            layout: {applyStoredLayout() {}},
            settings: {reconcileQueueTypeSettings() { settingReconciles++; }},
            modes: {
                user: {reconcileUserTypeAvailability() { previewClears.user++; }},
                search: {reconcileSearchTypeAvailability() { previewClears.search++; }},
                series: {reconcileSeriesTypeAvailability() { previewClears.series++; }},
                quick: {reconcileQuickTypeAvailability() { previewClears.quick++; }}
            }
        },
        addEventListener(type, listener) { runtimeListeners.set(type, listener); }
    },
    document: {addEventListener() {}, visibilityState: 'hidden'},
    state: {mode: 'user'},
    QUICK_FETCH_MODE: 'quick-fetch',
    applyCookieHint() {},
    updateBatchLimitNote() {},
    updateButtonsState() {},
    updateQuickAccountBar() {},
    updateSaveScheduleCardVisibility() {},
    loadScheduleTasks() {},
    refreshPageI18nNamespaces: () => Promise.resolve(false),
    console: {warn() {}, log() {}, error() {}},
    Promise
};
vm.createContext(initSandbox);
vm.runInContext(INIT_SOURCE, initSandbox);
runtimeListeners.get('pixivbatch:queuetypeschanged')({detail: {ready: false}});
ok('queue type loading 事件会立即调用四个模式的预览失效收敛',
    Object.values(previewClears).every(count => count === 1));
ok('loading 窗口不改写持久化 kind 设置', settingReconciles === 0);
runtimeListeners.get('pixivbatch:queuetypeschanged')({detail: {ready: true}});
ok('ready 事件再收敛设置与预览且不丢失事件链',
    settingReconciles === 1 && Object.values(previewClears).every(count => count === 2));

console.log(`batch-hot-reload-controls.test.js: ${passed} assertions passed ✓`);
