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
const {MiniElement} = require('./pixiv-layout-feedback-test-dom');

const PLUGIN_RESOURCES = path.join(__dirname, '..', '..', 'main', 'resources');
const CORE_PATH = path.join(PLUGIN_RESOURCES, 'static', 'pixiv-batch', 'batch-core.js');
const HTML_PATH = path.join(PLUGIN_RESOURCES, 'static', 'pixiv-batch.html');
const BATCH_ZH_PATH = path.join(PLUGIN_RESOURCES, 'i18n', 'web', 'batch.properties');
const BATCH_EN_PATH = path.join(PLUGIN_RESOURCES, 'i18n', 'web', 'batch_en.properties');
const SOURCE = fs.readFileSync(path.join(PLUGIN_RESOURCES, 'static/pixiv-batch/queue-display.js'), 'utf8')
    + '\n' + fs.readFileSync(CORE_PATH, 'utf8');
const HTML = fs.readFileSync(HTML_PATH, 'utf8');
const BATCH_ZH = fs.readFileSync(BATCH_ZH_PATH, 'utf8');
const BATCH_EN = fs.readFileSync(BATCH_EN_PATH, 'utf8');

let passed = 0;
function ok(label, cond) {
    assert.ok(cond, label);
    passed++;
}

(async function () {
    const {parse} = await import('../../../../scripts/i18n/lib/properties-parser.mjs');
    const created = [];
    const createCalls = [];
    const clients = [];
    let scheduleRefreshes = 0;
    let scheduleNamespaces = ['schedule-only'];
    let switcherClient = null;
    let switcherOnChange = null;
    const anchors = new Map(Array.from(HTML.matchAll(/<([a-z][\w-]*)\b[^>]*\bid="([^"]+)"[^>]*>/gi),
        ([, tag, id]) => [id, new MiniElement(tag)]));
    const langAnchor = anchors.get('batchLangAnchor');
    const themeAnchor = anchors.get('batchThemeAnchor');
    assert.ok(langAnchor);
    assert.ok(themeAnchor);
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
            getElementById: id => anchors.get(id) || null
        },
        console: {warn() {}, log() {}, error() {}},
        Promise,
        Set,
        PixivI18n: {
            create: opts => {
                const requestedNamespaces = (opts && opts.namespaces || []).slice();
                const requestedLang = (opts && opts.lang) || 'en-US';
                created.push(requestedNamespaces);
                createCalls.push({lang: requestedLang, namespaces: requestedNamespaces});
                const client = {
                    lang: requestedLang,
                    supportedLocales: [],
                    t(key, fallback) {
                        return key === 'batch:page.title' ? 'Batch title' : (fallback || key);
                    },
                    apply() { return client; },
                    disposed: false,
                    dispose() { client.disposed = true; },
                    setLanguage(lang) {
                        return sandbox.PixivI18n.create({lang, namespaces: client.namespaces.slice()});
                    }
                };
                client.namespaces = requestedNamespaces;
                clients.push(client);
                if (deferRaceCreate
                    && requestedLang === 'en-US'
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
            assert.strictEqual(options.mountPoint, langAnchor);
            switcherClient = options.i18n;
            switcherOnChange = options.onChange;
            return Promise.resolve({
                refresh(nextClient) { switcherClient = nextClient; }
            });
        }},
        PixivTheme: {mount(options) { assert.strictEqual(options.mountPoint, themeAnchor); }},
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
    const zhKeys = new Set(parse(BATCH_ZH).entries.map(entry => entry.key));
    const enKeys = new Set(parse(BATCH_EN).entries.map(entry => entry.key));
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
    searchStatisticsKeys.forEach(key => {
        ok('中文 batch bundle 提供来源统计文案: ' + key, zhKeys.has(key));
        ok('英文 batch bundle 提供来源统计文案: ' + key, enKeys.has(key));
    });
    queueTagKeys.forEach(key => {
        ok('中文 batch bundle 提供队列标签文案: ' + key, zhKeys.has(key));
        ok('英文 batch bundle 提供队列标签文案: ' + key, enKeys.has(key));
    });
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
    const switchedClient = await switcherClient.setLanguage('ja-JP');
    ok('语言切换沿用已扩展 client 并保留动态来源 namespace',
        switchedClient.lang === 'ja-JP' && switchedClient.namespaces.includes('schedule-late'));
    scheduleNamespaces = ['schedule-only', 'schedule-late', 'schedule-race'];
    deferRaceCreate = true;
    const racingRefresh = sandbox.refreshPageI18nNamespaces();
    for (let i = 0; i < 5 && !resolveRaceCreate; i++) {
        await Promise.resolve();
    }
    ok('动态 namespace 重建已停在旧语言 client 创建点', typeof resolveRaceCreate === 'function');
    const staleClient = clients.at(-1);
    const languageClient = await switcherClient.setLanguage('ja-JP');
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
        createCalls.some(call => call.lang === 'ja-JP' && call.namespaces.includes('schedule-race')));
    ok('动态 namespace 竞态不会把页面语言回退',
        sandbox.uiLang() === 'ja-JP' && switcherClient.lang === 'ja-JP');
    ok('被语言切换淘汰的词典释放恢复资源', staleClient.disposed);
    ok('当前页面词典保留恢复能力', !switcherClient.disposed);
    ok('动态 namespace 竞态不会丢失新 namespace', switcherClient.namespaces.includes('schedule-race'));
    sandbox.window.PixivBatch.queueTypes.i18nNamespaces = async () => [];
    await sandbox.refreshPageI18nNamespaces();
    ok('撤回插件后移除其词典并保留页面和计划来源词典',
        !switcherClient.namespaces.includes('plugin-a') && !switcherClient.namespaces.includes('plugin-b')
        && switcherClient.namespaces.includes('batch') && switcherClient.namespaces.includes('schedule-race'));
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
    const body = new MiniElement('body');
    sandbox.document.getElementById = id => body.querySelector('#' + id);
    sandbox.document.querySelectorAll = selector => body.querySelectorAll(selector);
    const area = new MiniElement('div'); area.id = 'search-results-area'; body.appendChild(area);
    const pager = new MiniElement('div'); pager.id = 'search-pagination'; body.appendChild(pager);
    const buttons = [0, 1].map(() => {
        const button = new MiniElement('button');
        button.className = 'preview-collapse-btn';
        button.setAttribute('data-pixiv-click', "togglePreviewCollapse(this,'search-results-area','search-pagination')");
        const label = new MiniElement('span'); label.className = 'preview-collapse-label';
        button.appendChild(label); body.appendChild(button);
        sandbox.syncPreviewCollapseBtn(button, true);
        return button;
    });
    sandbox.applyPreviewCollapsed(area.id, pager.id, true);
    sandbox.resetPreviewCollapse(area.id, pager.id);
    ok('重新加载展开预览及分页', !area.classList.contains('preview-collapsed')
        && !pager.classList.contains('preview-collapsed'));
    ok('同一预览的全部按钮同步展开状态及翻译键', buttons.every(button =>
        !button.classList.contains('is-collapsed')
        && button.querySelector('.preview-collapse-label').getAttribute('data-i18n') === 'preview.collapse'));
    console.log(`\nbatch-core-i18n.test.js: ${passed} assertions passed`);
})().catch(err => {
    console.error('TEST FAILED:', err && err.stack ? err.stack : err);
    process.exit(1);
});
