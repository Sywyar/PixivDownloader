'use strict';
/*
 * 批量预览请求凭据归属测试。
 *
 * 运行：node src/test/js/preview-request-ownership.test.js
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');

const ROOT = path.join(__dirname, '..', '..', '..', '..');
const PIXIV_PATH = path.join(ROOT, 'pixivdownload-plugin-download-workbench', 'src', 'main', 'resources',
    'static', 'pixiv-batch', 'pixiv-queue-type.js');
const NOVEL_PATH = path.join(ROOT, 'pixivdownload-plugin-novel', 'src', 'main', 'resources',
    'static', 'pixiv-novel-download', 'novel-queue-type.js');
const DOUYIN_PATH = path.join(ROOT, 'pixivdownload-plugin-douyin', 'src', 'main', 'resources',
    'static', 'pixiv-douyin-download', 'douyin-queue-type.js');
const SERIES_PATH = path.join(ROOT, 'pixivdownload-plugin-download-workbench', 'src', 'main', 'resources',
    'static', 'pixiv-batch', 'modes', 'series.js');
const USER_PATH = path.join(ROOT, 'pixivdownload-plugin-download-workbench', 'src', 'main', 'resources',
    'static', 'pixiv-batch', 'modes', 'user.js');
const SEARCH_PATH = path.join(ROOT, 'pixivdownload-plugin-download-workbench', 'src', 'main', 'resources',
    'static', 'pixiv-batch', 'modes', 'search.js');
const QUICK_PATH = path.join(ROOT, 'pixivdownload-plugin-download-workbench', 'src', 'main', 'resources',
    'static', 'pixiv-batch', 'modes', 'quick-fetch.js');

const PIXIV_SOURCE = fs.readFileSync(PIXIV_PATH, 'utf8');
const NOVEL_SOURCE = fs.readFileSync(NOVEL_PATH, 'utf8');
const DOUYIN_SOURCE = fs.readFileSync(DOUYIN_PATH, 'utf8');
const SERIES_SOURCE = fs.readFileSync(SERIES_PATH, 'utf8');
const USER_SOURCE = fs.readFileSync(USER_PATH, 'utf8');
const SEARCH_SOURCE = fs.readFileSync(SEARCH_PATH, 'utf8');
const QUICK_SOURCE = fs.readFileSync(QUICK_PATH, 'utf8');

let passed = 0;
function ok(label, condition) {
    assert.ok(condition, label);
    passed++;
}

function noOp() {}

function loadDescriptor(source, type, globals) {
    let initializer = null;
    const window = {
        PixivBatch: {
            queueTypes: {
                registerModule(candidate) {
                    initializer = candidate;
                    return true;
                }
            }
        }
    };
    const sandbox = Object.assign({
        window,
        console: {warn() {}, log() {}, error() {}},
        AbortController,
        URL,
        URLSearchParams,
        setTimeout: () => 0,
        clearTimeout() {}
    }, globals || {});
    vm.createContext(sandbox);
    vm.runInContext(source, sandbox);
    assert.strictEqual(typeof initializer, 'function', `${type} 模块应注册 initializer`);
    const controller = new AbortController();
    const activation = initializer({
        type,
        signal: controller.signal,
        isActive: () => true,
        assertActive() {},
        onCleanup() {}
    });
    return activation && activation.descriptor ? activation.descriptor : activation;
}

const pixivDescriptor = loadDescriptor(PIXIV_SOURCE, 'illust', {
    getCookie: () => 'pixiv-secret',
    processIllustItem: noOp,
    SINGLE_IMPORT_MODE: 'single-import-artwork',
    parseUserIdInput: noOp,
    getUserMeta: noOp,
    getUserRequestArtworks: noOp,
    getUserArtworks: noOp,
    renderPixivUserResults: noOp,
    renderPixivSearchResults: noOp,
    parsePixivSeriesInput: noOp,
    resolveSeriesIdFromArtwork: noOp,
    renderPixivSeriesResults: noOp,
    QUICK_PAGE_SIZE_ILLUST: 60,
    renderQuickIllustGrid: noOp,
    pixivQuickInnerCard: noOp,
    loadQuickIllustBookmarks: noOp,
    loadQuickMyWorks: noOp,
    loadQuickMyRequest: noOp,
    loadQuickFollowing: noOp,
    loadQuickFollowingNew: noOp,
    loadQuickCollections: noOp,
    bt: (_key, fallback) => fallback
});
const novelDescriptor = loadDescriptor(NOVEL_SOURCE, 'novel', {
    getCookie: () => 'novel-pixiv-secret',
    parseUserIdInput: noOp,
    getUserMeta: noOp,
    SINGLE_IMPORT_NOVEL_SOURCE: 'single-import-novel',
    QUICK_PAGE_SIZE_NOVEL: 24,
    bt: (_key, fallback) => fallback
});
const douyinDescriptor = loadDescriptor(DOUYIN_SOURCE, 'douyin', {
    localStorage: {
        getItem(key) {
            return key === 'pixiv_douyin_cookie'
                ? 'ttwid=tt; passport_csrf_token=csrf; sessionid=sid'
                : '';
        },
        setItem() {},
        removeItem() {}
    }
});
const novelWordsFilter = novelDescriptor.filters['novel-words'];

ok('真实 Novel 嵌套 filter 执行字数范围匹配与跳过判定',
    novelWordsFilter.matchExtra({wordCount: 1200}, {wordsMin: 1000, wordsMax: 1500})
    && !novelWordsFilter.matchExtra({wordCount: 400}, {wordsMin: 500, wordsMax: null})
    && !!novelWordsFilter.evaluateSkip({wordCount: 400}, {wordsMin: 500, wordsMax: null}));
ok('真实 Novel descriptor 在声明 key 下保留收藏抓取与设置卡行为',
    typeof novelWordsFilter.bookmarkCountFetch === 'function'
    && novelDescriptor.settings['novel-settings-card'].cardId === 'novel-settings-card');

for (const mode of ['user', 'search', 'series', 'quick']) {
    const pixivInit = pixivDescriptor.acquisition[mode].requestInit();
    ok(`Pixiv ${mode} 预览请求携带中性取得凭证`,
        pixivInit.headers['X-Acquisition-Credential'] === 'pixiv-secret'
        && !pixivInit.headers['X-Pixiv-Cookie']);
    ok(`Pixiv ${mode} 预览请求保持同源凭据策略`, pixivInit.credentials === 'same-origin');

    const novelInit = novelDescriptor.acquisition[mode].requestInit();
    ok(`Novel ${mode} 预览请求携带中性取得凭证`,
        novelInit.headers['X-Acquisition-Credential'] === 'novel-pixiv-secret'
        && !novelInit.headers['X-Pixiv-Cookie']);
    ok(`Novel ${mode} 预览请求保持同源凭据策略`, novelInit.credentials === 'same-origin');
}

const douyinInit = douyinDescriptor.acquisition.series.requestInit();
ok('Douyin series 预览请求携带中性取得凭证且不使用来源专用头',
    douyinInit.headers['X-Acquisition-Credential'].includes('passport_csrf_token=csrf')
    && !douyinInit.headers['X-Pixiv-Cookie']
    && !douyinInit.headers['X-Douyin-Cookie']);
ok('Douyin series 预览请求保持同源凭据策略', douyinInit.credentials === 'same-origin');

ok('user 宿主卡片请求经 runtime 受控门构建',
    USER_SOURCE.includes('queueTypes.prepareAcquisitionRequest('));
ok('search 宿主搜索与范围请求经 runtime 受控门构建',
    SEARCH_SOURCE.includes('queueTypes.prepareAcquisitionRequest(')
    && SEARCH_SOURCE.includes("prepareSearchRequest(acq, 'search'")
    && SEARCH_SOURCE.includes("prepareSearchRequest(acq, 'range'"));
ok('series 宿主页请求经 runtime 受控门构建',
    SERIES_SOURCE.includes('queueTypes.prepareAcquisitionRequest('));
ok('Pixiv user/series 解析与请求已移回 owner 模块',
    PIXIV_SOURCE.includes('function parsePixivUserInput(')
    && PIXIV_SOURCE.includes('function parsePixivSeriesUrl(')
    && !USER_SOURCE.includes('function parseUserIdInput(')
    && !SERIES_SOURCE.includes('function parsePixivSeriesInput('));

const requests = [];
const descriptors = {illust: pixivDescriptor, novel: novelDescriptor, douyin: douyinDescriptor};
const seriesWindow = {
    PixivBatch: {
        queueTypes: {
            acquisition(type, mode) {
                return descriptors[type].acquisition[mode];
            },
            prepareAcquisitionRequest(type, mode, url) {
                const init = descriptors[type].acquisition[mode].requestInit();
                return {url, init, assertCurrent() {}};
            }
        },
        modes: {}
    }
};
const seriesSandbox = {
    window: seriesWindow,
    BASE: '',
    URL,
    URLSearchParams,
    Map,
    fetch(url, options) {
        requests.push({url: String(url), options: options || {}});
        return Promise.resolve({
            ok: true,
            json: () => Promise.resolve({items: [], isLastPage: true})
        });
    },
    pixivHeader() {
        throw new Error('series 宿主不得自行读取 Pixiv Cookie');
    }
};
vm.createContext(seriesSandbox);
vm.runInContext(SERIES_SOURCE + [
    '',
    'window.__testFetchSeriesPage = function (kind, seriesId, page) {',
    '    seriesState.kind = kind;',
    '    seriesState.seriesId = seriesId;',
    '    return fetchSeriesPage(page);',
    '};'
].join('\n'), seriesSandbox);

(async function () {
    await seriesWindow.__testFetchSeriesPage('illust', 11, 1);
    await seriesWindow.__testFetchSeriesPage('novel', 22, 2);
    await seriesWindow.__testFetchSeriesPage('douyin', 33, 3);

    const pixivRequest = requests.find(request => request.url.includes('/api/pixiv/series/11'));
    const novelRequest = requests.find(request => request.url.includes('/api/pixiv/novel/series/22'));
    const douyinRequest = requests.find(request => request.url.includes('/api/douyin/series/33'));
    ok('真实宿主 Pixiv series 请求携带中性取得凭证',
        pixivRequest.options.headers['X-Acquisition-Credential'] === 'pixiv-secret'
        && !pixivRequest.options.headers['X-Pixiv-Cookie']);
    ok('真实宿主 Novel series 请求携带中性取得凭证',
        novelRequest.options.headers['X-Acquisition-Credential'] === 'novel-pixiv-secret'
        && !novelRequest.options.headers['X-Pixiv-Cookie']);
    ok('真实宿主 Douyin series 请求携带中性取得凭证',
        douyinRequest.options.headers['X-Acquisition-Credential'].includes('passport_csrf_token=csrf')
        && !douyinRequest.options.headers['X-Douyin-Cookie']);

    let currentPublication = 'A';
    let releaseAccountRequest = null;
    let accountWrites = 0;
    const uidElement = {style: {}, value: ''};
    Object.defineProperty(uidElement, 'textContent', {
        get() { return this.value; },
        set(value) { this.value = value; accountWrites++; }
    });
    const hintElement = {style: {}, textContent: ''};
    let pixivCredentialMissing = false;
    const quickAcquisition = {
        type: 'illust',
        dataSource: {
            id: 'pixiv',
            displayNamespace: 'batch',
            displayI18nKey: 'quick.data-source.pixiv',
            order: 10
        },
        actions: {'pixiv-action': {viewType: 'works-list'}},
        account: {
            credentialMissing() { return pixivCredentialMissing; },
            buildRequest() { return {endpoint: '/api/pixiv/account'}; },
            readId(data) { return data.id; }
        }
    };
    const douyinQuickAcquisition = {
        type: 'douyin',
        dataSource: {
            id: 'douyin',
            displayNamespace: 'douyin',
            displayI18nKey: 'source.douyin',
            order: 20
        },
        actions: {'douyin-action': {viewType: 'works-list'}},
        account: {
            credentialMissing() { return false; },
            buildRequest() { return {endpoint: '/api/douyin/account'}; },
            readId(data) { return data.id; }
        }
    };
    let quickAcquisitions = [quickAcquisition, douyinQuickAcquisition];
    const quickButtons = ['pixiv-action', 'douyin-action'].map(action => ({
        dataset: {quick: action}, disabled: false, hidden: false, title: '',
        classList: {contains() { return false; }, toggle() {}}
    }));
    function quickClassList() {
        const values = new Set();
        return {
            contains(name) { return values.has(name); },
            toggle(name, force) {
                const active = force == null ? !values.has(name) : !!force;
                if (active) values.add(name);
                else values.delete(name);
                return active;
            }
        };
    }
    function quickElement(tagName) {
        return {
            tagName: String(tagName).toUpperCase(),
            dataset: {},
            style: {},
            children: [],
            attributes: {},
            classList: quickClassList(),
            appendChild(child) { this.children.push(child); },
            replaceChildren() { this.children = []; },
            setAttribute(name, value) { this.attributes[name] = String(value); },
            getAttribute(name) { return this.attributes[name] || null; },
            querySelector(selector) {
                return selector === 'input[type=radio]'
                    ? (this.children.find(child => child.tagName === 'INPUT' && child.type === 'radio') || null)
                    : null;
            }
        };
    }
    const quickSourceSwitcher = quickElement('div');
    const accountRequestUrls = [];
    const quickWindow = {
        PixivBatch: {
            queueTypes: {
                acquisitionList() { return quickAcquisitions; },
                prepareAcquisitionRequest(_type, _mode, url) {
                    const publication = currentPublication;
                    return {
                        url,
                        init: {},
                        isCurrent() { return currentPublication === publication; },
                        assertCurrent() {
                            if (currentPublication !== publication) throw new Error('stale publication');
                        }
                    };
                }
            },
            modes: {}
        }
    };
    const quickSandbox = {
        window: quickWindow,
        document: {
            addEventListener() {},
            getElementById(id) {
                if (id === 'quick-account-uid') return uidElement;
                if (id === 'quick-account-hint') return hintElement;
                if (id === 'quick-data-source-switcher') return quickSourceSwitcher;
                return null;
            },
            createElement(tagName) { return quickElement(tagName); },
            querySelectorAll(selector) {
                if (selector === '.quick-action') return quickButtons;
                if (selector === '#quick-data-source-switcher label') return quickSourceSwitcher.children;
                return [];
            }
        },
        console: {warn() {}, log() {}, error() {}},
        URL,
        URLSearchParams,
        Promise,
        Map,
        Set,
        state: {mode: 'quick-fetch'},
        QUICK_FETCH_MODE: 'quick-fetch',
        bt: (_key, fallback) => fallback,
        fetch(url) {
            accountRequestUrls.push(String(url));
            return new Promise(resolve => { releaseAccountRequest = resolve; });
        }
    };
    vm.createContext(quickSandbox);
    vm.runInContext(QUICK_SOURCE
        + '\nwindow.__quickOwnershipTest = {quickState, applyQuickActionCredentialUi, '
        + 'updateQuickAccountBar, invalidateQuickAccount, quickDataSources, '
        + 'renderQuickDataSourceSwitcher, selectQuickDataSource};', quickSandbox);
    const quickOwnership = quickWindow.__quickOwnershipTest;
    quickOwnership.renderQuickDataSourceSwitcher();
    ok('quick 数据来源切换器按插件元数据渲染 Pixiv 与 Douyin 两个选项',
        quickSourceSwitcher.children.length === 2
        && quickSourceSwitcher.children[0].children[0].value === 'pixiv'
        && quickSourceSwitcher.children[0].children[1].getAttribute('data-i18n')
            === 'batch:quick.data-source.pixiv'
        && quickSourceSwitcher.children[1].children[0].value === 'douyin'
        && quickSourceSwitcher.children[1].children[1].getAttribute('data-i18n')
            === 'douyin:source.douyin');
    ok('默认选择排序靠前的 Pixiv 来源并只显示其快捷动作',
        quickOwnership.quickState.dataSourceId === 'pixiv'
        && quickButtons[0].hidden === false
        && quickButtons[1].hidden === true);
    quickOwnership.selectQuickDataSource('douyin', false);
    ok('切换到 Douyin 后只显示 Douyin 快捷动作',
        quickOwnership.quickState.dataSourceId === 'douyin'
        && quickButtons[0].hidden === true
        && quickButtons[1].hidden === false);
    quickAcquisitions = [];
    quickOwnership.renderQuickDataSourceSwitcher(true);
    ok('queue type loading 空快照会隐藏动作但保留期望的 Douyin 来源',
        quickOwnership.quickState.dataSourceId === 'douyin'
        && quickSourceSwitcher.children.length === 0
        && quickButtons.every(button => button.hidden));
    quickAcquisitions = [quickAcquisition, douyinQuickAcquisition];
    quickOwnership.renderQuickDataSourceSwitcher();
    ok('queue type ready 后若 Douyin 仍存在则恢复原选择而不回退 Pixiv',
        quickOwnership.quickState.dataSourceId === 'douyin'
        && quickSourceSwitcher.children.length === 2
        && quickButtons[0].hidden === true
        && quickButtons[1].hidden === false);
    quickOwnership.selectQuickDataSource('pixiv', false);
    pixivCredentialMissing = true;
    quickOwnership.applyQuickActionCredentialUi();
    ok('quick credential gate 只禁用缺少凭据的 Pixiv action，不会误挡 Douyin action',
        quickButtons[0].disabled === true && quickButtons[1].disabled === false);
    pixivCredentialMissing = false;
    const pendingAccountUpdate = quickOwnership.updateQuickAccountBar();
    assert.strictEqual(typeof releaseAccountRequest, 'function', '账号请求应进入延迟状态');
    quickSandbox.state.mode = 'user';
    currentPublication = 'B';
    uidElement.textContent = 'B-account';
    accountWrites = 0;
    releaseAccountRequest({ok: true, json: () => Promise.resolve({id: 'A-account'})});
    await pendingAccountUpdate;
    ok('切走 quick 且 publication A→B 后，旧账号请求 catch 不回写新页面',
        uidElement.textContent === 'B-account' && accountWrites === 0);

    quickSandbox.state.mode = 'quick-fetch';
    const requestsBeforeForeignRefresh = accountRequestUrls.length;
    const accountSeqBeforeForeignRefresh = quickOwnership.quickState.accountSeq;
    quickOwnership.invalidateQuickAccount('douyin');
    await quickOwnership.updateQuickAccountBar('douyin');
    ok('非当前来源的插件失效与刷新不会取消 Pixiv 请求或覆盖账号栏',
        quickOwnership.quickState.dataSourceId === 'pixiv'
        && quickOwnership.quickState.accountSeq === accountSeqBeforeForeignRefresh
        && accountRequestUrls.length === requestsBeforeForeignRefresh
        && uidElement.textContent === 'B-account');
    quickOwnership.selectQuickDataSource('douyin', false);
    const douyinAccountUpdate = quickOwnership.updateQuickAccountBar('douyin');
    assert.strictEqual(accountRequestUrls.at(-1), '/api/douyin/account',
        'Douyin owner 应使用自己的账号 provider');
    releaseAccountRequest({ok: true, json: () => Promise.resolve({id: 'douyin-account'})});
    await douyinAccountUpdate;
    ok('owner 定向账号 provider 只发布对应 UID',
        quickOwnership.quickState.accountOwner === 'douyin'
        && quickOwnership.quickState.uid === 'douyin-account'
        && uidElement.textContent === 'douyin-account');
    quickOwnership.invalidateQuickAccount('douyin');
    ok('账号凭据变更会清除对应 owner UID 缓存与当前显示',
        quickOwnership.quickState.uid === null
        && !quickOwnership.quickState.accountIdsByOwner.has('douyin')
        && uidElement.textContent === '-');

    quickAcquisitions = [
        Object.assign({type: 'illust'}, pixivDescriptor.acquisition.quick),
        Object.assign({type: 'novel'}, novelDescriptor.acquisition.quick),
        Object.assign({type: 'douyin'}, douyinDescriptor.acquisition.quick)
    ];
    const realSources = quickOwnership.quickDataSources();
    ok('真实 Pixiv 插画与小说 quick contribution 合并为同一个数据来源',
        realSources.length === 2
        && realSources[0].id === 'pixiv'
        && realSources[0].ownerTypes.join(',') === 'illust,novel'
        && realSources[0].displayNamespace === 'batch'
        && realSources[0].displayI18nKey === 'quick.data-source.pixiv'
        && realSources[0].order === 10);
    ok('真实 Douyin quick contribution 保持独立且排在 Pixiv 来源之后',
        realSources[1].id === 'douyin'
        && realSources[1].ownerTypes.join(',') === 'douyin'
        && realSources[1].displayNamespace === 'douyin'
        && realSources[1].displayI18nKey === 'source.douyin'
        && realSources[1].order === 20);

    console.log(`\npreview-request-ownership.test.js: ${passed} assertions passed ✓`);
})().catch(err => {
    console.error('TEST FAILED:', err && err.message ? err.message : err);
    process.exit(1);
});
