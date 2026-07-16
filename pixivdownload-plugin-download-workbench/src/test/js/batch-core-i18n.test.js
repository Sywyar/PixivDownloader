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

const PLUGIN_RESOURCES = path.join(__dirname, '..', '..', 'main', 'resources');
const CORE_PATH = path.join(PLUGIN_RESOURCES, 'static', 'pixiv-batch', 'batch-core.js');
const HTML_PATH = path.join(PLUGIN_RESOURCES, 'static', 'pixiv-batch.html');
const BATCH_ZH_PATH = path.join(PLUGIN_RESOURCES, 'i18n', 'web', 'batch.properties');
const BATCH_EN_PATH = path.join(PLUGIN_RESOURCES, 'i18n', 'web', 'batch_en.properties');
const SOURCE = fs.readFileSync(CORE_PATH, 'utf8');
const HTML = fs.readFileSync(HTML_PATH, 'utf8');
const BATCH_ZH = fs.readFileSync(BATCH_ZH_PATH, 'utf8');
const BATCH_EN = fs.readFileSync(BATCH_EN_PATH, 'utf8');

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
    const importSourceRenderArgs = [];
    let deferRaceCreate = false;
    let resolveRaceCreate = null;
    const sandbox = {
        window: {
            PixivBatch: {
                queueTypes: {
                    i18nNamespaces: () => Promise.resolve(['plugin-a', 'plugin-b', 'batch', '', null])
                },
                modeControls: {
                    renderSupportedImportSources(preserveSelection) {
                        importSourceRenderArgs.push(preserveSelection);
                    }
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
    const dataSourceKeys = [
        'data-source.label',
        'data-source.supported'
    ];
    const baseKindKeys = [
        'batch.user.kind-illust',
        'batch.user.kind-request',
        'batch.search.kind-illust'
    ];
    const searchStatisticsKeys = [
        'search.summary.source-total',
        'search.summary.source-returned',
        'search.batch.summary.pixiv-fetched'
    ];
    const queueTagKeys = [
        'queue.source.schedule',
        'queue.tag.image',
        'queue.tag.illust',
        'queue.tag.manga',
        'queue.tag.ugoira',
        'queue.tag.collection',
        'queue.tag.bookmark',
        'queue.tag.request',
        'queue.tag.ai'
    ];

    ok('默认 namespace 仍被加载', ['batch', 'common', 'ai', 'tour'].every(ns => namespaces.includes(ns)));
    ok('batch 中英文 bundle 的 key 集合保持一致',
        JSON.stringify(Array.from(zhKeys).sort()) === JSON.stringify(Array.from(enKeys).sort()));
    searchStatisticsKeys.forEach(key => {
        ok('中文 batch bundle 提供来源统计文案: ' + key, zhKeys.has(key));
        ok('英文 batch bundle 提供来源统计文案: ' + key, enKeys.has(key));
    });
    queueTagKeys.forEach(key => {
        ok('中文 batch bundle 提供队列标签文案: ' + key, zhKeys.has(key));
        ok('英文 batch bundle 提供队列标签文案: ' + key, enKeys.has(key));
    });
    ok('固定 namespace 不依赖任一可选插件', !fixedNamespaces.includes("'plugin-a'"));
    ok('控制器仍从活动下载类型动态收集 namespace', SOURCE.includes('await qt.i18nNamespaces()'));
    ok('第一个插件 descriptor namespace 被动态合并', namespaces.includes('plugin-a'));
    ok('第二个插件 descriptor namespace 被动态合并', namespaces.includes('plugin-b'));
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
    const importRendersBeforeLanguageChange = importSourceRenderArgs.length;
    await switcherOnChange(languageClient);
    ok('语言切换会保留选择并重渲染动态导入来源分隔符',
        importSourceRenderArgs.length === importRendersBeforeLanguageChange + 1
        && importSourceRenderArgs.at(-1) === true);
    const releaseRaceCreate = resolveRaceCreate;
    resolveRaceCreate = null;
    releaseRaceCreate();
    await racingRefresh;
    ok('旧语言 client 完成后会以最新语言重建',
        createCalls.some(call => call.lang === 'en-US' && call.namespaces.includes('schedule-race')));
    ok('动态 namespace 竞态不会把页面语言回退',
        sandbox.uiLang() === 'en-US' && switcherClient.lang === 'en-US');
    ok('动态 namespace 竞态不会丢失新 namespace', switcherClient.namespaces.includes('schedule-race'));
    dataSourceKeys.forEach(key => {
        ok('中性数据来源控件 HTML 使用 batch namespace: ' + key,
            HTML.includes('data-i18n="' + key + '"'));
        ok('中性数据来源控件 HTML 不引用可选插件 namespace: ' + key,
            !HTML.includes('data-i18n="plugin-a:' + key + '"'));
        ok('中文 batch bundle 提供中性数据来源文案: ' + key, zhKeys.has(key));
        ok('英文 batch bundle 提供中性数据来源文案: ' + key, enKeys.has(key));
    });
    baseKindKeys.forEach(key => {
        ok('既有作品类型 HTML 继续使用 batch namespace: ' + key,
            HTML.includes('data-i18n="' + key + '"'));
        ok('既有作品类型 HTML 不引用可选插件 namespace: ' + key,
            !HTML.includes('data-i18n="plugin-a:' + key + '"'));
        ok('中文 batch bundle 继续提供既有作品类型文案: ' + key, zhKeys.has(key));
        ok('英文 batch bundle 继续提供既有作品类型文案: ' + key, enKeys.has(key));
    });
    ok('User 与 Search 保留既有 kind switcher 及插件槽位',
        HTML.includes('id="user-kind-switcher"')
        && HTML.includes('data-qt-slot="kind-option-user"')
        && HTML.includes('id="search-kind-switcher"')
        && HTML.includes('data-qt-slot="kind-option-search"'));
    ['quick', 'user', 'search', 'series'].forEach(mode => {
        ok(`${mode} 不新增顶部通用作品类型控件`,
            !HTML.includes(`id="${mode}-work-type-control"`)
            && !HTML.includes(`id="${mode}-work-type-switcher"`));
    });
    const seriesSourceKeys = [
        'input.series.placeholder', 'status.series-empty', 'status.series-url-invalid',
        'series.browser.title', 'series.browser.loading', 'series.browser.empty',
        'series.browser.previous', 'series.browser.next', 'series.browser.page',
        'series.browser.load-failed', 'series.pagination.cursor-stalled',
        'series.pagination.cursor-missing'
    ];
    seriesSourceKeys.forEach(key => {
        ok('中文 batch bundle 提供系列来源文案: ' + key, zhKeys.has(key));
        ok('英文 batch bundle 提供系列来源文案: ' + key, enKeys.has(key));
    });
    ok('系列来源标签与中性输入提示由静态页面 i18n key 驱动',
        HTML.includes('data-i18n="data-source.label"')
        && HTML.includes('data-i18n-placeholder="input.series.placeholder"')
        && HTML.includes('data-i18n="status.series-empty"'));
    const batchScheduleErrorKeys = [
        'schedule.error.source-editor-ambiguous',
        'schedule.error.source-definition-invalid'
    ];
    batchScheduleErrorKeys.forEach(key => {
        ok('中文 batch bundle 提供计划来源错误文案: ' + key, zhKeys.has(key));
        ok('英文 batch bundle 提供计划来源错误文案: ' + key, enKeys.has(key));
    });
    console.log(`\nbatch-core-i18n.test.js: ${passed} assertions passed`);
})().catch(err => {
    console.error('TEST FAILED:', err && err.stack ? err.stack : err);
    process.exit(1);
});
