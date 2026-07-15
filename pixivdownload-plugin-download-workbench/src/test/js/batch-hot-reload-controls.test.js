'use strict';
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');

const STATIC = path.join(__dirname, '..', '..', 'main', 'resources', 'static', 'pixiv-batch');
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
        },
        resolveSelectionForMode(selection) { return selection; }
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

function kindSwitcherElement(kinds) {
    const switcher = {
        hidden: false,
        style: {display: ''},
        dataset: {},
        labels: [],
        querySelectorAll(selector) {
            return selector === 'label' || selector === 'label[data-kind]' ? this.labels : [];
        }
    };
    switcher.labels = kinds.map(kind => {
        const label = makeLabel(kind);
        label.hidden = false;
        label.style = {display: ''};
        label.parentNode = switcher;
        label.classList.owner = label;
        return label;
    });
    return switcher;
}

function sourceKindRegistry() {
    const acquisitions = [
        {type: 'owner-a', accepts(kind) { return kind === 'kind-a' || kind === 'kind-c'; }},
        {type: 'owner-b', accepts(kind) { return kind === 'kind-b'; }},
        {type: 'owner-c', accepts(kind) { return kind === 'kind-d'; }}
    ];
    return {
        acquisitionList() { return acquisitions; },
        typesForDataSource(_mode, sourceId) {
            return sourceId === 'source-a'
                ? [{type: 'owner-a'}, {type: 'owner-b'}]
                : sourceId === 'source-b' ? [{type: 'owner-c'}] : [];
        },
        supports(type) { return acquisitions.some(candidate => candidate.type === type); }
    };
}

function realModeHarness(source, stateExpression, elements, extra = {}) {
    const registry = Object.assign({
        supports() { return false; },
        resolveTypeForMode() { return null; },
        resolveSelectionForMode(selection) { return selection || null; },
        acquisition() { return null; },
        acquisitionList() { return []; },
        dataSourcesForMode() { return []; },
        typesForDataSource() { return []; }
    }, extra.registry || {});
    const modeDocument = {
        getElementById(id) { return elements[id] || null; },
        querySelectorAll(selector) {
            return typeof elements.querySelectorAll === 'function' ? elements.querySelectorAll(selector) : [];
        },
        querySelector(selector) {
            return typeof elements.querySelector === 'function' ? elements.querySelector(selector) : null;
        },
        addEventListener() {}
    };
    const pixivBatch = {queueTypes: registry, modes: {}};
    if (extra.modeControls) pixivBatch.modeControls = extra.modeControls;
    const modeSandbox = Object.assign({
        window: {PixivBatch: pixivBatch},
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
    return {sandbox: modeSandbox, state: modeSandbox.window.__realModeState, registry};
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
    const synced = [];
    sandbox.window.PixivBatch.modeControls = {
        syncType(mode, type) { synced.push([mode, type]); return true; }
    };
    sandbox.window.PixivBatch.modes = {};
    sandbox.applySearchKindUI = () => undefined;
    sandbox.updateExtraFiltersCardVisibility = () => undefined;
    sandbox.state.settings.userKind = 'owner-c';
    sandbox.state.settings.searchKind = 'owner-a';
    sandbox.window.PixivBatch.settings.reconcileQueueTypeSettings();
    ok('设置收敛保留无可见 label 的来源内部 owner',
        sandbox.state.settings.userKind === 'owner-c'
        && synced.some(([mode, type]) => mode === 'user' && type === 'owner-c'));
}

{
    const controlRenders = [];
    const selection = {sourceId: 'source-a', type: 'owner-a'};
    const elements = {
        'user-results-area': modeElement(),
        'user-pagination': modeElement({html: 'A pages'}),
        'btn-user-add-page': modeElement(),
        'btn-user-add-all': modeElement()
    };
    const h = realModeHarness(USER_SOURCE, 'userState', elements, {
        modeControls: {
            render(mode, preserveSelection) {
                controlRenders.push([mode, preserveSelection]);
                return {sourceChanged: false, typeChanged: false};
            },
            selection() { return selection; }
        }
    });
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
    const requestSeqBefore = h.state.requestSeq;
    const reconciled = h.sandbox.window.PixivBatch.modes.user.reconcileUserTypeAvailability(false);
    ok('user loading reconcile 保留来源选择并清空 publication A 的预览 state',
        reconciled && h.state.kind === 'owner-a' && h.state.variant === 'owner-a'
        && h.state.userId === '' && h.state.username === ''
        && h.state.allIds.length === 0 && h.state.rawItems.length === 0 && h.state.items.length === 0
        && h.state.requestSeq === requestSeqBefore + 1
        && h.state.cardCache.size === 0 && h.sandbox.state.settings.userKind === 'owner-a'
        && selection.sourceId === 'source-a' && selection.type === 'owner-a'
        && controlRenders.length === 1 && controlRenders[0][0] === 'user'
        && controlRenders[0][1] === true);
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
    const controlRenders = [];
    const selection = {sourceId: 'source-a', type: 'owner-a'};
    const elements = {
        'search-results-area': modeElement(),
        'search-pagination': modeElement({html: 'A pages'}),
        'btn-add-all': modeElement(),
        'btn-batch-add-page': modeElement(),
        'btn-batch-add-all': modeElement()
    };
    const h = realModeHarness(SEARCH_SOURCE, 'searchState', elements, {
        modeControls: {
            render(mode, preserveSelection) {
                controlRenders.push([mode, preserveSelection]);
                return {sourceChanged: false, typeChanged: false};
            },
            selection() { return selection; }
        }
    });
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
    const reconciled = h.sandbox.window.PixivBatch.modes.search.reconcileSearchTypeAvailability(false);
    ok('search loading reconcile 保留来源选择并清空 publication A 的预览 state',
        reconciled && h.state.kind === 'owner-a' && h.state.rawResults.length === 0
        && h.state.results.length === 0 && h.state.total === 0 && h.state.currentPage === 1
        && h.state.submode === 'batch' && h.state.currentWord === ''
        && Object.keys(h.state.metaCache).length === 0
        && h.sandbox.state.settings.searchKind === 'owner-a'
        && selection.sourceId === 'source-a' && selection.type === 'owner-a'
        && controlRenders.length === 1 && controlRenders[0][0] === 'search'
        && controlRenders[0][1] === true);
    ok('真实 search reconcile 清掉旧结果 DOM 与分页',
        !elements['search-results-area'].innerHTML.includes('A preview')
        && elements['search-pagination'].style.display === 'none'
        && elements['search-pagination'].innerHTML === '');
    ok('真实 search reconcile 禁用旧预览入队按钮',
        elements['btn-add-all'].disabled
        && elements['btn-batch-add-page'].disabled && elements['btn-batch-add-all'].disabled);
    h.state.submode = 'batch';
    h.state.results = [{id: 'B-1'}];
    h.sandbox.updateBatchQueueButtons();
    ok('publication B 新结果可重新启用 search 入队按钮',
        !elements['btn-batch-add-page'].disabled && !elements['btn-batch-add-all'].disabled);
}

{
    const selection = {sourceId: 'source-b', type: 'owner-c'};
    const switcher = kindSwitcherElement(['kind-a', 'kind-b', 'kind-c']);
    const h = realModeHarness(USER_SOURCE, 'userState', {'user-kind-switcher': switcher}, {
        registry: sourceKindRegistry(),
        modeControls: {selection() { return selection; }},
        applyKindSwitcherUI(_id, value) {
            switcher.labels.forEach(label => {
                const active = label.dataset.kind === value;
                label.classList.toggle('active', active);
                label.input.checked = active;
            });
        }
    });
    h.sandbox.state.settings.userKind = 'kind-a';

    const selectedSingleTypeSource = h.sandbox.window.PixivBatch.modes.user.applyUserSourceKindAvailability();
    ok('User 选择单类型来源时不显示冗余二级类型并直接路由到内部 owner',
        selectedSingleTypeSource && switcher.hidden && switcher.style.display === 'none'
        && switcher.labels.every(label => label.hidden && label.input.disabled)
        && h.sandbox.state.settings.userKind === 'owner-c');

    Object.assign(selection, {sourceId: 'source-a', type: 'owner-a'});
    const restoredMultiTypeSource = h.sandbox.window.PixivBatch.modes.user.applyUserSourceKindAvailability();
    ok('User 切回多类型来源后恢复插件贡献的类型切换',
        restoredMultiTypeSource && !switcher.hidden && switcher.style.display === ''
        && switcher.labels.every(label => !label.hidden && !label.input.disabled)
        && h.sandbox.state.settings.userKind === 'kind-a');
}

{
    const selection = {sourceId: 'source-b', type: 'owner-c'};
    const switcher = kindSwitcherElement(['kind-a', 'kind-b']);
    const h = realModeHarness(SEARCH_SOURCE, 'searchState', {'search-kind-switcher': switcher}, {
        registry: sourceKindRegistry(),
        modeControls: {selection() { return selection; }},
        applyKindSwitcherUI(_id, value) {
            switcher.labels.forEach(label => {
                const active = label.dataset.kind === value;
                label.classList.toggle('active', active);
                label.input.checked = active;
            });
        }
    });
    h.sandbox.state.settings.searchKind = 'kind-a';

    const selectedSingleTypeSource = h.sandbox.window.PixivBatch.modes.search.applySearchSourceKindAvailability();
    ok('Search 选择单类型来源时不显示冗余二级类型并直接路由到内部 owner',
        selectedSingleTypeSource && switcher.hidden && switcher.style.display === 'none'
        && switcher.labels.every(label => label.hidden && label.input.disabled)
        && h.sandbox.state.settings.searchKind === 'owner-c');

    Object.assign(selection, {sourceId: 'source-a', type: 'owner-a'});
    const restoredMultiTypeSource = h.sandbox.window.PixivBatch.modes.search.applySearchSourceKindAvailability();
    ok('Search 切回多类型来源后恢复插件贡献的类型切换',
        restoredMultiTypeSource && !switcher.hidden && switcher.style.display === ''
        && switcher.labels.every(label => !label.hidden && !label.input.disabled)
        && h.sandbox.state.settings.searchKind === 'kind-a');
}

{
    const selection = {sourceId: 'source-a', type: 'owner-a'};
    const sourceControl = modeElement();
    sourceControl.hidden = false;
    const switcher = kindSwitcherElement(['kind-a']);
    const h = realModeHarness(SEARCH_SOURCE, 'searchState', {
        'search-data-source-control': sourceControl,
        'search-kind-switcher': switcher
    }, {
        registry: {
            acquisitionList() {
                return [{type: 'owner-a', accepts(kind) { return kind === 'kind-a'; }}];
            },
            typesForDataSource() { return [{type: 'owner-a'}]; },
            supports(type) { return type === 'owner-a'; }
        },
        modeControls: {selection() { return selection; }},
        applyKindSwitcherUI() {}
    });
    h.sandbox.state.settings.searchKind = 'kind-a';

    const changed = h.sandbox.window.PixivBatch.modes.search.applySearchSourceKindAvailability();
    ok('当前来源恰好一个可见类型时只保留数据来源控件并隐藏二级切换',
        !changed && !sourceControl.hidden && switcher.hidden && switcher.style.display === 'none'
        && !switcher.labels[0].hidden && !switcher.labels[0].input.disabled
        && h.sandbox.state.settings.searchKind === 'kind-a');
}

{
    let secondSelectedOwnerAccepts = false;
    let selectedAcceptCalls = 0;
    let foreignAcceptCalls = 0;
    let selectedBuildCalls = 0;
    let foreignBuildCalls = 0;
    const ownerA = {
        type: 'owner-a',
        accepts(kind) { selectedAcceptCalls++; return kind === 'shared-kind'; },
        buildRequest() {
            selectedBuildCalls++;
            return {endpoint: '/api/source-a/search', params: {word: 'demo'}};
        }
    };
    const ownerB = {
        type: 'owner-b',
        accepts(kind) { return secondSelectedOwnerAccepts && kind === 'shared-kind'; }
    };
    const foreignOwner = {
        type: 'owner-c',
        accepts() { foreignAcceptCalls++; return true; },
        buildRequest() {
            foreignBuildCalls++;
            return {endpoint: '/api/source-b/search'};
        }
    };
    const acquisitions = [foreignOwner, ownerA, ownerB];
    const h = realModeHarness(SEARCH_SOURCE, 'searchState', {}, {
        modeControls: {selection() { return {sourceId: 'source-a', type: 'owner-a'}; }},
        registry: {
            acquisitionList() { return acquisitions; },
            typesForDataSource(_mode, sourceId) {
                return sourceId === 'source-a'
                    ? [{type: 'owner-a'}, {type: 'owner-b'}]
                    : [{type: 'owner-c'}];
            },
            acquisition(type) { return acquisitions.find(candidate => candidate.type === type) || null; },
            prepareAcquisitionRequest(type, mode, url) {
                return {type, mode, url, init: {}, assertCurrent() {}};
            }
        }
    });
    vm.runInContext('window.__selectedSearchSourceTest = {searchKindOwner, prepareSearchRequest};', h.sandbox);
    const api = h.sandbox.window.__selectedSearchSourceTest;
    const resolved = api.searchKindOwner('shared-kind');
    api.prepareSearchRequest(h.registry.acquisition(resolved), 'search', {word: 'demo'});
    ok('Search 解析和请求只调用所选来源的 owner hooks',
        resolved === 'owner-a'
        && selectedAcceptCalls === 1 && selectedBuildCalls === 1
        && foreignAcceptCalls === 0 && foreignBuildCalls === 0);

    secondSelectedOwnerAccepts = true;
    ok('Search 所选来源内部多个 owner 同时 accepts 时拒绝歧义且不探测其它来源',
        api.searchKindOwner('shared-kind') === null
        && foreignAcceptCalls === 0 && foreignBuildCalls === 0);
}

{
    const statuses = [];
    let acquisitionCalls = 0;
    let fetchCalls = 0;
    const searchWord = modeElement();
    searchWord.value = 'retained preview';
    const contentFilter = modeElement();
    contentFilter.value = 'all';
    const batchStart = modeElement();
    batchStart.value = '1';
    const batchEnd = modeElement();
    batchEnd.value = '2';
    const elements = {
        'search-word': searchWord,
        'search-content-filter': contentFilter,
        'batch-start-page': batchStart,
        'batch-end-page': batchEnd,
        'search-results-area': modeElement({html: 'retained preview'}),
        'search-pagination': modeElement({html: 'retained pages'}),
        'btn-batch-add-page': modeElement(),
        'btn-batch-add-all': modeElement(),
        querySelector(selector) {
            if (selector === 'input[name="search-smode"]:checked') return {value: 's_tag'};
            if (selector === 'input[name="search-order"]:checked') return {value: 'date_d'};
            return null;
        }
    };
    const retainedSelection = {sourceId: 'pixiv', type: 'owner-a'};
    const h = realModeHarness(SEARCH_SOURCE, 'searchState', elements, {
        modeControls: {selection() { return retainedSelection; }},
        registry: {
            supports(type, mode) { return !(type === 'owner-a' && mode === 'search'); },
            acquisition() { acquisitionCalls++; return null; }
        },
        setStatus(message, level) { statuses.push({message, level}); },
        fetch() { fetchCalls++; throw new Error('unsupported search must not request'); },
        appMode: 'solo',
        isAdmin: false,
        multiModeLimitPage: 0
    });
    Object.assign(h.state, {
        kind: 'owner-a',
        rawResults: [{id: 'old'}],
        results: [{id: 'old'}],
        total: 1,
        currentPage: 4,
        submode: 'search',
        currentWord: 'old word',
        activeBlobUrls: ['blob:retained']
    });
    h.sandbox.window.PixivBatch.modes.search.performSearch(1);
    h.sandbox.window.PixivBatch.modes.search.runBatchFetch();
    ok('search 保留旧 type 但能力已撤回时两种入口都只报告类型不可用',
        statuses.length === 2
        && statuses.every(status => status.level === 'warning'
            && status.message.includes('该类型当前不可用')));
    ok('unsupported search 两种入口不进入 acquisition 或网络请求',
        acquisitionCalls === 0 && fetchCalls === 0);
    ok('unsupported search 两种入口不清空既有预览 state 与 DOM',
        h.state.kind === 'owner-a'
        && h.state.rawResults.length === 1 && h.state.results.length === 1
        && h.state.currentPage === 4 && h.state.activeBlobUrls[0] === 'blob:retained'
        && elements['search-results-area'].innerHTML === 'retained preview'
        && elements['search-pagination'].innerHTML === 'retained pages');
}

{
    const elements = {
        'series-results-area': modeElement(),
        'series-meta-display': modeElement(),
        'series-pagination': modeElement({html: 'A pages'}),
        'btn-series-add-page': modeElement(),
        'btn-series-add-all': modeElement()
    };
    const h = realModeHarness(SERIES_SOURCE, 'seriesState', elements, {
        registry: {
            supports(type, mode) { return type === 'owner-a' && mode === 'series'; },
            acquisitionList(mode) {
                return mode === 'series' ? [{
                    type: 'owner-a',
                    dataSource: {
                        id: 'pixiv', displayNamespace: 'batch',
                        displayI18nKey: 'series.data-source.pixiv', order: 10
                    }
                }] : [];
            },
            manifestDescriptor() {
                return {
                    owner: {pluginId: 'owner', packageId: 'package', generation: 1, publicationId: 2}
                };
            }
        }
    });
    Object.assign(h.state, {
        dataSourceId: 'pixiv',
        kind: 'owner-a',
        ownerIdentity: 'owner:package:1:2:owner-a',
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
    const sameSourceSelected = h.sandbox.window.PixivBatch.modes.series.selectSeriesDataSource('pixiv');
    ok('计划任务回灌显式选择同一 series 来源时也清空旧预览',
        sameSourceSelected && h.state.dataSourceId === 'pixiv' && h.state.kind === null
        && h.state.seriesId === null && h.state.rawItems.length === 0
        && h.state.items.length === 0 && h.state.allItems.length === 0
        && h.state.itemsByPage.size === 0
        && !elements['series-results-area'].innerHTML.includes('A preview'));

    Object.assign(h.state, {
        kind: 'owner-a',
        ownerIdentity: 'owner:package:1:1:owner-a',
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
    elements['series-results-area'].innerHTML = 'A preview';
    elements['series-meta-display'].textContent = 'A meta';
    elements['series-pagination'].innerHTML = 'A pages';
    elements['series-pagination'].style.display = 'flex';
    const reconciled = h.sandbox.window.PixivBatch.modes.series.reconcileSeriesTypeAvailability();
    ok('真实 series reconcile 在同来源 owner publication 变化后清空旧预览 state',
        reconciled && h.state.dataSourceId === 'pixiv' && h.state.kind === null
        && h.state.ownerIdentity === null && h.state.seriesId === null && h.state.seriesTitle === ''
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
    const quickActionClasses = new Set(['quick-active', 'is-loading']);
    const quickAction = modeElement();
    quickAction.dataset.quick = 'owner-a-action';
    quickAction.disabled = true;
    quickAction.classList = {
        contains(name) { return quickActionClasses.has(name); },
        toggle(name, enabled) {
            if (enabled) quickActionClasses.add(name);
            else quickActionClasses.delete(name);
        },
        remove(name) { quickActionClasses.delete(name); }
    };
    const elements = {
        'quick-preview-area': modeElement(),
        'quick-pagination': modeElement({html: 'A pages'}),
        'quick-inner-section': modeElement(),
        'quick-preview-toolbar': modeElement(),
        'quick-add-page': modeElement(),
        'quick-add-all': modeElement(),
        'quick-following-search': modeElement(),
        querySelectorAll(selector) { return selector === '.quick-action' ? [quickAction] : []; }
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
    const reconciled = h.sandbox.window.PixivBatch.modes.quick.reconcileQuickTypeAvailability(false);
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
    ok('queue type loading 暂态使用普通空态，不遗留类型不可用提示',
        elements['quick-preview-area'].innerHTML.includes('点击上方按钮加载内容')
        && !elements['quick-preview-area'].innerHTML.includes('该类型当前不可用'));
    ok('真实 quick reconcile 隐藏并禁用旧预览入队按钮',
        elements['quick-preview-toolbar'].style.display === 'none'
        && elements['quick-add-page'].style.display === 'none'
        && elements['quick-add-page'].disabled
        && elements['quick-add-all'].style.display === 'none'
        && elements['quick-add-all'].disabled);
    ok('真实 quick reconcile 清除旧 action 的高亮、loading 与残留禁用态',
        !quickActionClasses.has('quick-active')
        && !quickActionClasses.has('is-loading')
        && !quickAction.disabled);
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
const initDocumentListeners = new Map();
const previewClears = {user: 0, search: 0, series: 0, quick: 0};
const quickReadyStates = [];
let settingReconciles = 0;
const initSandbox = {
    window: {
        PixivBatch: {
            layout: {applyStoredLayout() {}},
            settings: {reconcileQueueTypeSettings() { settingReconciles++; }},
            modes: {
                user: {reconcileUserTypeAvailability() { previewClears.user++; }},
                search: {reconcileSearchTypeAvailability() { previewClears.search++; }},
                series: {
                    reconcileSeriesTypeAvailability() { previewClears.series++; }
                },
                quick: {reconcileQuickTypeAvailability(ready) {
                    previewClears.quick++;
                    quickReadyStates.push(ready);
                }}
            }
        },
        addEventListener(type, listener) { runtimeListeners.set(type, listener); }
    },
    document: {
        addEventListener(type, listener) { initDocumentListeners.set(type, listener); },
        visibilityState: 'hidden'
    },
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
ok('queue type reconcile 把 loading/ready 状态传给 quick 来源选择收敛',
    quickReadyStates.length === 2 && quickReadyStates[0] === false && quickReadyStates[1] === true);
ok('batch init 将新增来源控件与只读导入来源交给稳定 modeControls 委托',
    INIT_SOURCE.includes('window.PixivBatch.modeControls.bind()')
    && INIT_SOURCE.includes('window.PixivBatch.modeControls.renderAll()'));

console.log(`batch-hot-reload-controls.test.js: ${passed} assertions passed ✓`);
