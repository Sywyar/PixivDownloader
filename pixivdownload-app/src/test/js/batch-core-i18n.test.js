'use strict';
/*
 * 下载页 i18n 初始化 smoke。
 *
 * 验证 batch-core.js 创建页面 i18n client 前，会从下载类型 descriptor 收集动态 namespace。
 *
 * 运行： node src/test/js/batch-core-i18n.test.js
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');

const PLUGIN_RESOURCES = path.join(__dirname, '..', '..', '..', '..',
    'pixivdownload-plugin-download-workbench', 'src', 'main', 'resources');
const CORE_PATH = path.join(PLUGIN_RESOURCES, 'static', 'pixiv-batch', 'batch-core.js');
const HTML_PATH = path.join(PLUGIN_RESOURCES, 'static', 'pixiv-batch.html');
const BATCH_ZH_PATH = path.join(PLUGIN_RESOURCES, 'i18n', 'web', 'batch.properties');
const BATCH_EN_PATH = path.join(PLUGIN_RESOURCES, 'i18n', 'web', 'batch_en.properties');
const DOUYIN_RESOURCES = path.join(__dirname, '..', '..', '..', '..',
    'pixivdownload-plugin-douyin', 'src', 'main', 'resources', 'i18n', 'web');
const DOUYIN_ZH_PATH = path.join(DOUYIN_RESOURCES, 'douyin.properties');
const DOUYIN_EN_PATH = path.join(DOUYIN_RESOURCES, 'douyin_en.properties');
const SOURCE = fs.readFileSync(CORE_PATH, 'utf8');
const HTML = fs.readFileSync(HTML_PATH, 'utf8');
const BATCH_ZH = fs.readFileSync(BATCH_ZH_PATH, 'utf8');
const BATCH_EN = fs.readFileSync(BATCH_EN_PATH, 'utf8');
const DOUYIN_ZH = fs.readFileSync(DOUYIN_ZH_PATH, 'utf8');
const DOUYIN_EN = fs.readFileSync(DOUYIN_EN_PATH, 'utf8');

function propertyKeys(source) {
    return new Set(String(source).split(/\r?\n/)
        .map(line => line.trim())
        .filter(line => line && !line.startsWith('#') && line.includes('='))
        .map(line => line.slice(0, line.indexOf('=')).trim()));
}

let passed = 0;
function ok(label, cond) {
    assert.ok(cond, label);
    passed++;
}

(async function () {
    const created = [];
    const createCalls = [];
    let scheduleRefreshes = 0;
    let scheduleNamespaces = ['schedule-only'];
    let switcherClient = null;
    let switcherOnChange = null;
    let deferRaceCreate = false;
    let resolveRaceCreate = null;
    const sandbox = {
        window: {
            PixivBatch: {
                queueTypes: {
                    i18nNamespaces: () => Promise.resolve(['novel', 'douyin', 'batch', '', null])
                },
                scheduleSources: {
                    refresh: () => { scheduleRefreshes++; return Promise.resolve(); },
                    i18nNamespaces: () => scheduleNamespaces.slice()
                }
            }
        },
        document: {
            body: {},
            title: '',
            getElementById: () => null
        },
        console: {warn() {}, log() {}, error() {}},
        Promise,
        Set,
        PixivI18n: {
            create: opts => {
                const requestedNamespaces = (opts && opts.namespaces || []).slice();
                const requestedLang = (opts && opts.lang) || 'zh-CN';
                created.push(requestedNamespaces);
                createCalls.push({lang: requestedLang, namespaces: requestedNamespaces});
                const client = {
                    lang: requestedLang,
                    supportedLocales: [],
                    t(key, fallback) {
                        return key === 'batch:page.title' ? 'Batch title' : (fallback || key);
                    },
                    apply() { return client; },
                    setLanguage(lang) {
                        return sandbox.PixivI18n.create({lang, namespaces: client.namespaces.slice()});
                    }
                };
                client.namespaces = requestedNamespaces;
                if (deferRaceCreate
                    && requestedLang === 'zh-CN'
                    && requestedNamespaces.includes('schedule-race')) {
                    deferRaceCreate = false;
                    return new Promise(resolve => {
                        resolveRaceCreate = () => resolve(client);
                    });
                }
                return Promise.resolve(client);
            }
        },
        PixivLangSwitcher: {mount: options => {
            switcherClient = options.i18n;
            switcherOnChange = options.onChange;
            return Promise.resolve({
                refresh(nextClient) { switcherClient = nextClient; }
            });
        }},
        PixivTheme: {mount() {}},
        applyCookieHint() {},
        syncCookieToggleLabel() {},
        refreshNovelTranslateLangDefault() {},
        renderQuotaBar() {},
        updateStats() {},
        renderQueue() {},
        setCurrent() {},
        updateButtonsState() {},
        renderSearchResults() {},
        renderSearchPagination() {},
        updateBatchLimitNote() {},
        refreshBatchCollections: () => Promise.resolve(),
        refreshGuideFab() {},
        state: {currentItemId: null, queue: [], mode: 'user'},
        seriesState: {seriesId: null},
        userState: {allIds: []},
        _userscriptsLoaded: false
    };
    vm.createContext(sandbox);
    vm.runInContext(SOURCE, sandbox);

    await sandbox.initPageI18n();
    const namespaces = created[0] || [];
    const fixedNamespaces = (SOURCE.match(/const BATCH_I18N_NAMESPACES = \[([^\]]*)\]/) || [])[1] || '';
    const zhKeys = propertyKeys(BATCH_ZH);
    const enKeys = propertyKeys(BATCH_EN);
    const douyinZhKeys = propertyKeys(DOUYIN_ZH);
    const douyinEnKeys = propertyKeys(DOUYIN_EN);
    const baseKindKeys = [
        'batch.user.kind-illust',
        'batch.user.kind-request',
        'batch.search.kind-illust'
    ];

    ok('默认 namespace 仍被加载', ['batch', 'common', 'ai', 'tour'].every(ns => namespaces.includes(ns)));
    ok('固定 namespace 不再依赖可选 novel', !fixedNamespaces.includes("'novel'"));
    ok('控制器仍从活动下载类型动态收集 namespace', SOURCE.includes('await qt.i18nNamespaces()'));
    ok('可选 novel namespace 由活动 descriptor 动态合并', namespaces.includes('novel'));
    ok('插件 descriptor namespace 被合并进页面 i18n', namespaces.includes('douyin'));
    ok('source-only 计划来源 namespace 在首次 client 创建前已预取',
        scheduleRefreshes === 1 && namespaces.includes('schedule-only'));
    ok('重复 namespace 去重', namespaces.filter(ns => ns === 'batch').length === 1);
    ok('空 namespace 被忽略', !namespaces.includes(''));
    ok('页面静态翻译仍执行', sandbox.document.title === 'Batch title');
    scheduleNamespaces = ['schedule-only', 'schedule-late'];
    await sandbox.refreshPageI18nNamespaces();
    ok('运行期新来源 namespace 会重建页面 client',
        created.some(value => value.includes('schedule-late'))
        && switcherClient.namespaces.includes('schedule-late'));
    const switchedClient = await switcherClient.setLanguage('en-US');
    ok('语言切换沿用已扩展 client 并保留动态来源 namespace',
        switchedClient.lang === 'en-US' && switchedClient.namespaces.includes('schedule-late'));
    scheduleNamespaces = ['schedule-only', 'schedule-late', 'schedule-race'];
    deferRaceCreate = true;
    const racingRefresh = sandbox.refreshPageI18nNamespaces();
    for (let i = 0; i < 5 && !resolveRaceCreate; i++) {
        await Promise.resolve();
    }
    ok('动态 namespace 重建已停在旧语言 client 创建点', typeof resolveRaceCreate === 'function');
    const languageClient = await switcherClient.setLanguage('en-US');
    switcherClient = languageClient;
    await switcherOnChange(languageClient);
    const releaseRaceCreate = resolveRaceCreate;
    resolveRaceCreate = null;
    releaseRaceCreate();
    await racingRefresh;
    ok('旧语言 client 完成后会以最新语言重建',
        createCalls.some(call => call.lang === 'en-US' && call.namespaces.includes('schedule-race')));
    ok('动态 namespace 竞态不会把页面语言回退',
        sandbox.uiLang() === 'en-US' && switcherClient.lang === 'en-US');
    ok('动态 namespace 竞态不会丢失新 namespace', switcherClient.namespaces.includes('schedule-race'));
    baseKindKeys.forEach(key => {
        ok('基础下载类型 HTML 使用 batch namespace: ' + key,
            HTML.includes('data-i18n="' + key + '"'));
        ok('基础下载类型 HTML 不再引用 novel namespace: ' + key,
            !HTML.includes('data-i18n="novel:' + key + '"'));
        ok('中文 batch bundle 提供基础下载类型文案: ' + key, zhKeys.has(key));
        ok('英文 batch bundle 提供基础下载类型文案: ' + key, enKeys.has(key));
    });
    const seriesSourceKeys = [
        'series.data-source.label', 'series.data-source.pixiv',
        'input.series.placeholder', 'status.series-empty', 'status.series-url-invalid'
    ];
    seriesSourceKeys.forEach(key => {
        ok('中文 batch bundle 提供系列来源文案: ' + key, zhKeys.has(key));
        ok('英文 batch bundle 提供系列来源文案: ' + key, enKeys.has(key));
    });
    ok('系列来源标签与中性输入提示由静态页面 i18n key 驱动',
        HTML.includes('data-i18n="series.data-source.label"')
        && HTML.includes('data-i18n-placeholder="input.series.placeholder"')
        && HTML.includes('data-i18n="status.series-empty"'));
    ok('Douyin 中英文 bundle 均提供系列来源名称',
        douyinZhKeys.has('series.data-source.douyin')
        && douyinEnKeys.has('series.data-source.douyin'));

    console.log(`\nbatch-core-i18n.test.js: ${passed} assertions passed`);
})().catch(err => {
    console.error('TEST FAILED:', err && err.stack ? err.stack : err);
    process.exit(1);
});
