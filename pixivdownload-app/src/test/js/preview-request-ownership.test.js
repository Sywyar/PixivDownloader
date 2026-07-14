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
    const quickAcquisition = {
        type: 'demo',
        account: {
            credentialMissing() { return false; },
            buildRequest() { return {endpoint: '/api/demo/account'}; },
            readId(data) { return data.id; }
        }
    };
    const quickWindow = {
        PixivBatch: {
            queueTypes: {
                acquisitionList() { return [quickAcquisition]; },
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
                return null;
            },
            querySelectorAll() { return []; }
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
        fetch() {
            return new Promise(resolve => { releaseAccountRequest = resolve; });
        }
    };
    vm.createContext(quickSandbox);
    vm.runInContext(QUICK_SOURCE, quickSandbox);
    const pendingAccountUpdate = quickSandbox.updateQuickAccountBar();
    assert.strictEqual(typeof releaseAccountRequest, 'function', '账号请求应进入延迟状态');
    quickSandbox.state.mode = 'user';
    currentPublication = 'B';
    uidElement.textContent = 'B-account';
    accountWrites = 0;
    releaseAccountRequest({ok: true, json: () => Promise.resolve({id: 'A-account'})});
    await pendingAccountUpdate;
    ok('切走 quick 且 publication A→B 后，旧账号请求 catch 不回写新页面',
        uidElement.textContent === 'B-account' && accountWrites === 0);

    console.log(`\npreview-request-ownership.test.js: ${passed} assertions passed ✓`);
})().catch(err => {
    console.error('TEST FAILED:', err && err.message ? err.message : err);
    process.exit(1);
});
