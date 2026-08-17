'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const source = fs.readFileSync(path.join(__dirname, '..', '..', 'main', 'resources',
    'static', 'pixiv-batch-alt', 'alt-extensions.js'), 'utf8');
const scheduleSource = fs.readFileSync(path.join(__dirname, '..', '..', 'main', 'resources',
    'static', 'pixiv-batch-alt', 'alt-schedule.js'), 'utf8');
const modesSource = fs.readFileSync(path.join(__dirname, '..', '..', 'main', 'resources',
    'static', 'pixiv-batch-alt', 'alt-modes.js'), 'utf8');
const initSource = fs.readFileSync(path.join(__dirname, '..', '..', 'main', 'resources',
    'static', 'pixiv-batch-alt', 'alt-init.js'), 'utf8');
const chromeSource = fs.readFileSync(path.join(__dirname, '..', '..', 'main', 'resources',
    'static', 'pixiv-batch-alt', 'alt-chrome.js'), 'utf8');
const settingsSource = fs.readFileSync(path.join(__dirname, '..', '..', 'main', 'resources',
    'static', 'pixiv-batch-alt', 'alt-settings.js'), 'utf8');
const queueSource = fs.readFileSync(path.join(__dirname, '..', '..', 'main', 'resources',
    'static', 'pixiv-batch-alt', 'alt-queue.js'), 'utf8');
const engineSource = fs.readFileSync(path.join(__dirname, '..', '..', 'main', 'resources',
    'static', 'pixiv-batch-alt', 'alt-engine.js'), 'utf8');
const filtersSource = fs.readFileSync(path.join(__dirname, '..', '..', 'main', 'resources',
    'static', 'pixiv-batch-alt', 'alt-filters.js'), 'utf8');
const coreSource = fs.readFileSync(path.join(__dirname, '..', '..', 'main', 'resources',
    'static', 'pixiv-batch-alt', 'alt-core.js'), 'utf8');
const pageSource = fs.readFileSync(path.join(__dirname, '..', '..', 'main', 'resources',
    'static', 'pixiv-batch-alt.html'), 'utf8');
const classicPageSource = fs.readFileSync(path.join(__dirname, '..', '..', 'main', 'resources',
    'static', 'pixiv-batch.html'), 'utf8');
const classicCoreSource = fs.readFileSync(path.join(__dirname, '..', '..', 'main', 'resources',
    'static', 'pixiv-batch', 'batch-core.js'), 'utf8');
const classicDownloadSource = fs.readFileSync(path.join(__dirname, '..', '..', 'main', 'resources',
    'static', 'pixiv-batch', 'batch-download.js'), 'utf8');
const cssSource = fs.readFileSync(path.join(__dirname, '..', '..', 'main', 'resources',
    'static', 'pixiv-batch-alt', 'pixiv-batch-alt.css'), 'utf8');

const contributions = [
    {
        type: 'illust', bareDefault: true, sectionType: 'artwork', sectionAliases: ['illust'],
        matchUrl(line) {
            const match = String(line).match(/artworks\/(\d+)/);
            return match ? match[1] : null;
        },
        buildItem(id, title) { return {id: String(id), kind: 'illust', title}; },
        source: 'single-import'
    },
    {
        type: 'external', sectionType: 'external',
        matchUrl(line) {
            const match = String(line).match(/example\.test\/works\/([a-z0-9_-]+)/i);
            return match ? {id: match[1], url: match[0]} : null;
        },
        buildItem(match, title) {
            return {id: 'x-' + match.id, kind: 'external', canonicalUrl: match.url, title};
        },
        source: 'single-import-external'
    },
    {
        type: 'douyin', sectionType: 'douyin',
        matchUrl(line) {
            return /^\d+$/.test(String(line).trim()) ? String(line).trim() : null;
        },
        buildItem(id, title) {
            return {id: 'd-' + id, douyinId: String(id), kind: 'douyin', title};
        },
        source: 'single-import-douyin'
    }
];

const noop = () => {};

function slotTargets(...sources) {
    const targets = new Set();
    sources.forEach(text => {
        for (const match of text.matchAll(/data-qt-slot=["']([^"']+)["']/g)) targets.add(match[1]);
        for (const match of text.matchAll(/setAttribute\(["']data-qt-slot["'],\s*["']([^"']+)["']\)/g)) {
            targets.add(match[1]);
        }
    });
    return Array.from(targets).sort();
}

const sandbox = {
    window: {
        PixivBatch: {
            queueTypes: {
                contributionsOf: key => key === 'import' ? contributions : [],
                i18nNamespaces: async () => ['novel', 'common']
            },
            scheduleSources: {i18nNamespaces: () => ['schedule-extra', 'novel']}
        },
        PixivBatchAlt: {}
    },
    URLSearchParams,
    console,
    bt: (key, fallback) => fallback,
    commitQueueItemPatch: noop,
    addItemsToQueue: noop,
    removeFromQueue: noop,
    renderQueue: noop,
    updateStats: noop,
    syncAllResultsQueueState: noop,
    getCookie: noop,
    getCookieFmt: noop,
    getStoredCookie: noop,
    setStoredCookie: noop,
    removeStoredCookie: noop,
    parseCookieToHeaderString: noop,
    getCookieHeaderStringFor: noop
};
vm.createContext(sandbox);
vm.runInContext(source, sandbox);

const parsed = sandbox.altParseImportText([
    '123 | bare',
    'https://www.pixiv.net/artworks/456 | pixiv',
    'https://example.test/works/abc_1 | external',
    'https://example.test/works/abc_1 | duplicate'
].join('\n'));

assert.deepStrictEqual(Array.from(parsed.items, item => String(item.id)), ['123', '456', 'x-abc_1']);
assert.strictEqual(parsed.items[2].source, 'single-import-external');
assert.strictEqual(parsed.rejected.length, 0);
const numericDefault = sandbox.altParseImportText('123456');
assert.strictEqual(numericDefault.items.length, 1);
assert.strictEqual(numericDefault.items[0].kind, 'illust');
const explicitDouyin = sandbox.altParseImportText('douyin:\n123456');
assert.strictEqual(explicitDouyin.items.length, 1);
assert.strictEqual(explicitDouyin.items[0].kind, 'douyin');
assert.strictEqual(explicitDouyin.items[0].douyinId, '123456');
assert.strictEqual(sandbox.altNextCursor({nextCursor: '2'}, '1', true), '2');
assert.throws(() => sandbox.altNextCursor({nextCursor: '1'}, '1', true), /分页游标未推进/);
sandbox.window.PixivBatchAlt.schedule = {};
vm.runInContext(scheduleSource, sandbox);
assert.strictEqual(sandbox.isValidProxyHostPort('127.0.0.1:65535'), true);
assert.strictEqual(sandbox.isValidProxyHostPort('127.0.0.1:65536'), false);
assert.strictEqual(sandbox.isValidProxyHostPort('http://127.0.0.1:7890'), false);
assert.strictEqual(sandbox.scheduleTaskKind({presentation: {attributes: {kind: 'external'}}}), 'external');
assert.strictEqual(sandbox.scheduleTaskKind({presentation: {}}), null);

(async () => {
    const feedbackCalls = [];
    const feedbackSandbox = {
        window: {
            PixivBatchAlt: {},
            PixivFeedback: {
                confirm(options) {
                    feedbackCalls.push(options);
                    assert(options.confirmLabel);
                    return Promise.resolve(true);
                }
            }
        },
        document: {addEventListener() {}},
        console,
        setTimeout,
        clearTimeout
    };
    vm.createContext(feedbackSandbox);
    vm.runInContext(coreSource, feedbackSandbox);
    assert.strictEqual(await feedbackSandbox.window.PixivBatchAlt.core.abConfirm(
        'dialog.confirm-clear-queue', '确认清除队列？'), true);
    assert.strictEqual(feedbackCalls[0].confirmLabel, '确认');
    assert(queueSource.includes("clearBtn.addEventListener('click', handleClear);"));
    assert.deepStrictEqual(Array.from(await sandbox.altI18nNamespaces()),
        ['batch-alt', 'batch', 'common', 'tour', 'layout-feedback', 'novel', 'schedule-extra']);
    assert(pageSource.includes('data-nav-link-class="ab-topnav-link"'));
    assert(pageSource.includes('data-nav-current="download-workbench"'));
    assert(pageSource.includes('href="/pixiv-batch.html"'));
    assert(pageSource.includes('data-i18n-title="page.switch-to-old-layout"'));
    assert(pageSource.includes('data-i18n-aria-label="page.switch-to-old-layout"'));
    assert(pageSource.includes('data-icon="grid"'));
    assert(!pageSource.includes('data-i18n="page.switch-to-old-layout"'));
    const topbarOrder = [
        'id="abCookieChip"', 'id="abLangAnchor"', 'id="abVersion"', 'id="abScriptsBtn"',
        'href="/pixiv-batch.html"', 'id="abThemeAnchor"', 'data-qt-slot="topbar-actions"',
        'id="abDockToggle"', 'id="abAuthBtn"'
    ].map(marker => pageSource.indexOf(marker));
    assert(topbarOrder.every((position, index) => position >= 0
        && (index === 0 || position > topbarOrder[index - 1])));
    const classicTopbarOrder = [
        'id="batchLangAnchor"', 'id="batchThemeAnchor"', 'data-qt-slot="topbar-actions"'
    ].map(marker => classicPageSource.indexOf(marker));
    assert(classicTopbarOrder.every((position, index) => position >= 0
        && (index === 0 || position > classicTopbarOrder[index - 1])));
    assert(classicCoreSource.includes("mountPoint: document.getElementById('batchLangAnchor')"));
    assert(classicCoreSource.includes("mountPoint: document.getElementById('batchThemeAnchor')"));
    assert(pageSource.includes('<span id="abVersionText">加载中…</span>'));
    assert(!pageSource.includes('id="abVersionText" data-i18n='));
    assert(chromeSource.includes("fetch('/api/app/info', {credentials: 'same-origin'})"));
    assert(chromeSource.includes("btn.setAttribute('data-i18n', isAdmin ? 'auth.logout' : 'auth.login');"));
    assert(cssSource.includes('.ab-topnav-link svg'));
    assert(cssSource.includes('.ab-backend-banner[hidden]'));
    assert(/\.ab-seg\s*\{[^}]*align-self:\s*flex-start[^}]*border-radius:\s*999px/s.test(cssSource));
    assert(modesSource.includes('return smallSeg(sources.map(src => [src.id, src.label]), current, onSelect);'));
    assert(pageSource.includes('/js/pixiv-tour.js'));
    assert(pageSource.includes('/js/pixiv-onboarding.js'));
    assert(pageSource.includes('/css/pixiv-onboarding.css'));
    assert(!pageSource.includes('id="abOnboarding"'));
    assert(!pageSource.includes('id="abHelpBtn"'));
    assert(!chromeSource.includes('function renderOnboarding'));
    assert(initSource.includes('PixivOnboarding.boot(buildOnboardingConfig(savedName))'));
    assert(initSource.includes('beforeStart: () => openDock()'));
    assert(modesSource.includes("importBtn.id = 'abBtnImport'"));
    assert(modesSource.includes("importBtn.addEventListener('click', () => runImportParse(false));"));
    assert(modesSource.includes('runImportParse(true);'));
    assert(!modesSource.includes("bt('import.parse'"));
    assert.strictEqual((modesSource.match(/bt\('import\.enqueue'/g) || []).length, 1);
    assert(modesSource.includes("bt('batch:input.single-import.placeholder'"));
    assert(modesSource.includes("const help = el('details', 'ab-import-help')"));
    assert(modesSource.includes("bt('batch:label.import-format'"));
    assert(modesSource.includes("bt('batch:hint.import-section-header'"));
    assert(cssSource.includes('.ab-import-help summary'));
    assert(!cssSource.includes('.ab-import-format-title'));
    assert(/\.pixiv-theme-toggle--topbar svg\s*\{[^}]*fill:\s*none[^}]*stroke:\s*currentColor/s.test(cssSource));
    assert(queueSource.includes("el('div', 'ab-queue-item')"));
    assert(!queueSource.includes("el('div', 'ab-queue-item card')"));
    assert(/\.ab-queue-item\s*\{[^}]*border-radius:\s*0 4px 4px 0[^}]*background:\s*var\(--surface-2\)/s.test(cssSource));
    // —— 插件槽位同步（settings-card / cookie-tools / import-hint 与旧布局同契约）——
    assert(pageSource.includes('/js/pixiv-vue.js'));
    assert(pageSource.includes('/pixiv-batch-alt/alt-queue-vue.js'));
    assert(pageSource.includes('data-qt-slot="topbar-actions"'));
    assert(chromeSource.includes("setAttribute('data-qt-slot', 'cookie-tools')"));
    assert(chromeSource.includes('refreshAltSlots();'));
    assert(modesSource.includes("setAttribute('data-qt-slot', 'import-hint')"));
    assert(modesSource.includes('refreshAltSlots();'));
    assert(settingsSource.includes("setAttribute('data-qt-slot', 'settings-card')"));
    assert(settingsSource.includes("novelGroup.id = 'novel-settings-card'"));
    assert(settingsSource.includes('refreshAltSlots();'));
    assert(/\[data-vue-slot\]\s*\{\s*display:\s*contents/.test(cssSource));
    assert(/\[data-vue-slot\]:empty\s*\{\s*display:\s*none/.test(cssSource));
    const semanticAltSlots = [
        'kind-option-quick', 'kind-option-search', 'kind-option-user',
        'quick-actions-bookmarks', 'quick-actions-mine', 'search-filter'
    ];
    assert.deepStrictEqual(
        Array.from(new Set([...slotTargets(pageSource, chromeSource, modesSource, settingsSource),
            ...semanticAltSlots])).sort(),
        slotTargets(classicPageSource),
        '旧版每个插件槽位都必须在新版有直接宿主或声明过的语义适配');
    assert(source.includes('runtime.dataSourcesForMode(mode)')
        && source.includes('runtime.typesForDataSource(mode, sourceId)')
        && source.includes("runtime.acquisitionList('quick')")
        && filtersSource.includes('let extraFilters = defaultSearchFilters();'));
    assert(classicPageSource.includes('/js/pixiv-vue.js')
        && classicPageSource.includes('/pixiv-batch/batch-queue-types.js')
        && pageSource.includes('/js/pixiv-vue.js')
        && pageSource.includes('/pixiv-batch/batch-queue-types.js'));
    [
        ['quick-fetch', 'QUICK_FETCH_MODE'], ['single-import', 'SINGLE_IMPORT_MODE'],
        ['user', "id: 'user'"], ['search', "id: 'search'"],
        ['series', "id: 'series'"], ['schedule', "id: 'schedule'"]
    ].forEach(([classicMode, altMode]) => {
        assert(classicPageSource.includes(`switchMode('${classicMode}')`));
        assert(modesSource.includes(altMode));
    });
    [
        ['data-pixiv-click="handleStart()"', "startBtn.addEventListener('click', handleStart)"],
        ['data-pixiv-click="handlePause()"', "pauseBtn.addEventListener('click', handlePause)"],
        ['data-pixiv-click="handleRetry()"', "retryBtn.addEventListener('click', handleRetry)"],
        ['data-pixiv-click="handleClear()"', "clearBtn.addEventListener('click', handleClear)"],
        ['data-pixiv-click="handleExport()"', "exportAllBtn.addEventListener('click', handleExport)"],
        ['data-pixiv-click="handleExportFailed()"', "exportUndlBtn.addEventListener('click', handleExportFailed)"],
        ['data-pixiv-click="triggerAdminPack()"', "packBtn.addEventListener('click', triggerAdminPack)"]
    ].forEach(([classicAction, altAction]) => {
        assert(classicPageSource.includes(classicAction));
        assert(queueSource.includes(altAction));
    });
    assert(engineSource.includes('async function handleStart()')
        && engineSource.includes('function handlePause()')
        && engineSource.includes('async function triggerAdminPack()'));
    [classicDownloadSource, engineSource].forEach(downloadSource => {
        assert(downloadSource.includes('other.rawMetaJson = rawMetaJson;'));
        assert(downloadSource.includes('meta.rawMetaJson || null'));
    });
    assert(queueSource.includes('queueItemCard: queueItemRow'),
        '新版队列命名空间必须导出已定义的队列卡片函数');
    assert(initSource.includes('onChange: applyPageLanguageViews'));
    assert(initSource.includes('pageLangSwitcher.refresh(pageI18n);'));
    assert(!initSource.includes('PixivI18n.onLanguageChange('));
    // —— 下载坞 Vue 岛门面（统计 / 当前卡 / 队列列表 reactive 主渲染，命令式回退）——
    assert(queueSource.includes('altQueueVueActive()'));
    assert(queueSource.includes('ensureDockVue();'));
    assert(initSource.includes('renderDock();'));
    console.log('batch-alt-extensions.test.js: runtime, i18n and chrome regressions passed ✓');
})().catch(error => {
    console.error(error);
    process.exitCode = 1;
});
