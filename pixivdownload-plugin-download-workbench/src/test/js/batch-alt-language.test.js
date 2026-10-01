'use strict';

const assert = require('node:assert/strict');
const {test} = require('node:test');
const {readFileSync} = require('node:fs');
const {resolve} = require('node:path');
const vm = require('node:vm');
const {MiniElement} = require('./pixiv-layout-feedback-test-dom');

const resources = resolve(__dirname, '../../main/resources');
function client(lang) {
    const suffix = lang === 'zh-CN' ? '' : '_en';
    const bundles = Object.fromEntries(['batch-alt', 'batch'].map(namespace => [namespace,
        Object.fromEntries(readFileSync(resolve(resources, `i18n/web/${namespace}${suffix}.properties`), 'utf8')
            .split(/\r?\n/).filter(line => line && !line.startsWith('#') && line.includes('='))
            .map(line => [line.slice(0, line.indexOf('=')), line.slice(line.indexOf('=') + 1)]))]));
    return {lang, t(key, fallback, args) {
        const [namespace, id] = key.split(':');
        const value = bundles[namespace]?.[id] ?? fallback ?? key;
        return String(value).replace(/\{([^}]+)\}/g, (match, name) => args?.[name] ?? match);
    }, apply(root) {
        (root?.querySelectorAll('[data-i18n]') || []).forEach(node => {
            node.textContent = this.t(node.getAttribute('data-i18n'), node.textContent);
        });
    }};
}

function harness(alt = true) {
    const c = vm.createContext({console, setTimeout, clearTimeout, Map, Set,
        document: {createElement: tag => new MiniElement(tag), getElementById: () => null, addEventListener() {}},
        openFiltersDrawer() {}, openSettingsDrawer() {},
        PixivBatchAlt: {}, PixivBatch: {}});
    c.window = c;
    c.load = name => vm.runInContext(readFileSync(resolve(resources, 'static/pixiv-batch-alt', name), 'utf8'), c);
    c.run = code => vm.runInContext(code, c);
    vm.runInContext(readFileSync(resolve(resources, 'static', alt ? 'pixiv-batch-alt/alt-core.js'
        : 'pixiv-batch/batch-core.js'), 'utf8'), c);
    return c;
}

test('经典筛选摘要保存本次计数，热切语言更新全部片段且不重新筛选', async () => {
    const c = harness(false);
    const status = new MiniElement('p'); status.style = {};
    c.document.getElementById = id => id === 'status-bar' ? status : null;
    vm.runInContext(readFileSync(resolve(resources, 'static/pixiv-batch/modes/series-view.js'), 'utf8'), c);
    let filterCalls = 0;
    c.searchState = {};
    c.seriesState = {kind: 'fixture', filterSeq: 0, rawItems: [{id: '1'}, {id: '2'}]};
    c.normalizeSearchFilters = value => value; c.getSearchFiltersFromUI = () => ({});
    c.saveSearchFilterPrefs = c.renderSeriesResults = c.updateSeriesQueueButtons = () => {};
    c.hasBookmarkFilter = () => false; c.hasExtraSearchFilter = () => true;
    c.computeFilteredItems = async () => {
        filterCalls++;
        return {filtered: [{id: '1'}], stats: {rawCount: 2, filteredCount: 1, bookmarkMetaMissing: 1}};
    };
    c.client = client('zh-CN'); c.run('pageI18n=client');
    await c.applySeriesFilters({setStatus: true});
    const before = status.textContent;
    c.seriesState.rawItems = [];
    c.client = client('en-US'); c.run('pageI18n=client'); c.renderStatus();
    assert.notEqual(status.textContent, before);
    assert.equal(status.textContent, c.bt('status.search-filters-applied') + [
        c.bt('search.summary.current-page', '', {count: 2}),
        c.bt('search.summary.extra-filtered', '', {count: 1}),
        c.bt('search.summary.bookmark-missing', '', {count: 1})
    ].join(c.punct('enum')));
    assert.equal(filterCalls, 1);
});

test('来源状态热切时重译原始计数，换代后不调用旧或替代来源', () => {
    const c = harness(false);
    const status = new MiniElement('p'); status.style = {};
    c.document.getElementById = id => id === 'status-bar' ? status : null;
    c.defaultSearchFilters = () => ({});
    vm.runInContext(readFileSync(resolve(resources, 'static/pixiv-batch/modes/search.js'), 'utf8'), c);
    let owner = {pluginId: 'fixture', generation: 1, publicationId: 1};
    let calls = 0;
    c.PixivBatch.queueTypes = {
        manifestDescriptor: () => ({owner}),
        acquisition: () => ({formatStats(metric, facts) {
            calls++;
            assert.equal(metric, 'total'); assert.equal(facts.count, 23);
            return c.bt('search.summary.pixiv-total', '', {count: facts.count});
        }})
    };
    c.run("searchState.kind='fixture'; searchState.submode='search'");
    c.client = client('zh-CN'); c.run('pageI18n=client');
    const message = JSON.parse(JSON.stringify(c.searchStatMessage('total', 23)));
    c.setStatus(message);
    c.client = client('en-US'); c.run('pageI18n=client'); c.renderStatus();
    assert.equal(status.textContent, c.bt('search.summary.pixiv-total', '', {count: 23}));
    assert.equal(calls, 2);
    owner = {...owner, publicationId: 2};
    c.renderStatus();
    assert.equal(calls, 2);
    assert.equal(status.textContent, c.bt('search.summary.source-total', '', {count: '23'}));
    c.PixivBatch.queueTypes.manifestDescriptor = () => null;
    c.renderStatus();
    assert.equal(calls, 2);
});

test('Cookie 校验的嵌套原因与警告跟随语言，原始解析错误保留', () => {
    const c = harness(false);
    const status = new MiniElement('p'); status.style = {};
    c.document.getElementById = id => id === 'cookie-status' ? status : null;
    vm.runInContext(readFileSync(resolve(resources, 'static/pixiv-batch/batch-cookie.js'), 'utf8'), c);
    c.client = client('zh-CN'); c.run('pageI18n=client');
    const invalid = c.validateAndParseCookie('[]', 'json');
    c.setCookieStatus({key: 'status.cookie-save-failed', args: {message: invalid.errorMessage}}, 'error');
    c.client = client('en-US'); c.run('pageI18n=client'); c.renderCookieStatus();
    assert.equal(status.textContent, c.bt('status.cookie-save-failed', '', {
        message: c.bt('cookie.error.parse-failed', '', {message: c.bt('cookie.error.invalid-json')})
    }));
    c.client = client('zh-CN'); c.run('pageI18n=client');
    const result = c.validateAndParseCookie('fixture=value', 'header');
    c.setCookieStatus({key: 'status.cookie-saved-warning', args: {
        count: result.count, warnings: {parts: result.warningMessages, separator: 'semicolon'}
    }}, 'warning');
    c.client = client('en-US'); c.run('pageI18n=client'); c.renderCookieStatus();
    assert.equal(status.textContent, c.bt('status.cookie-saved-warning', '', {
        count: 1, warnings: c.bt('cookie.warning.no-phpsessid')
    }));
    const parseError = c.validateAndParseCookie('{broken', 'json');
    assert.equal(typeof parseError.errorMessage.args.message, 'string');
    assert.ok(parseError.error.includes(parseError.errorMessage.args.message));
});

for (const alt of [false, true]) {
    test(`${alt ? '新版' : '经典'}整批完成状态保留语言键并随当前语言重绘`, () => {
        const c = harness(alt);
        const status = new MiniElement('p');
        status.style = {};
        c.document.getElementById = id => ['abDockStatus', 'status-bar'].includes(id) ? status : null;
        for (const name of ['sendDownload', 'ensureSharedSSE', 'closeAllSSE', 'openSSE', 'closeSSE',
            'initQuota', 'showArchiveCard', 'triggerAdminPack']) c[name] = () => {};
        if (alt) {
            c.load('alt-state.js');
            c.load('alt-queue.js');
            c.load('alt-engine-workers.js');
        } else {
            c.state = {queue: [], isRunning: true};
            c.quotaInfo = {enabled: false};
            vm.runInContext(readFileSync(resolve(resources, 'static/pixiv-batch/batch-download-workers.js'), 'utf8'), c);
        }
        for (const name of ['endPathActionBatch', 'closeAllSSE', 'saveQueue', 'updateButtonsState']) c[name] = () => {};
        c.client = client('zh-CN'); c.run('pageI18n=client');
        c.finishBatch();
        assert.equal(status.textContent, c.client.t('batch:status.batch-finished'));
        c.client = client('en-US'); c.run('pageI18n=client');
        if (alt) c.renderDockStatus();
        else c.renderStatus();
        assert.equal(status.textContent, c.client.t('batch:status.batch-finished'));
        const setStatus = alt ? c.setDockStatus : c.setStatus;
        setStatus({key: 'status.start-download', args: {concurrent: 2, intervalMs: 300}}, 'warning');
        assert.equal(status.textContent, c.client.t((alt ? 'batch-alt:' : 'batch:') + 'status.start-download', null,
            {concurrent: 2, intervalMs: 300}));
        setStatus('upstream diagnostic', 'error');
        if (alt) c.renderDockStatus();
        else c.renderStatus();
        assert.equal(status.textContent, 'upstream diagnostic');
    });
}

test('新版工作台共享键回退既有 batch 词典，显式 namespace 和参数不被重写', () => {
    const c = harness();
    c.client = client('en-US'); c.run('pageI18n=client');
    for (const key of ['queue.tag.illust', 'queue.tag.ugoira', 'queue.unknown', 'queue.message.completed']) {
        assert.equal(c.bt(key, 'fallback'), c.client.t('batch:' + key));
    }
    assert.equal(c.bt('card.type.illust'), c.client.t('batch-alt:card.type.illust'));
    assert.equal(c.bt('batch:queue.status.completed'), c.client.t('batch:queue.status.completed'));
    assert.equal(c.bt('queue.message.completed-images', '', {count: '{count}'}),
        c.client.t('batch-alt:queue.message.completed-images', '', {count: '{count}'}));
    c.client = client('zh-CN'); c.run('pageI18n=client');
    assert.equal(c.bt('queue.tag.illust'), c.client.t('batch:queue.tag.illust'));
});

test('下载区的筛选和设置按钮原位更新语言并保留交互节点', () => {
    const c = harness();
    c.load('alt-state.js'); c.load('alt-modes.js');
    c.client = client('zh-CN'); c.run('pageI18n=client');
    c.abIconEl = () => new MiniElement('span');
    const root = new MiniElement('div');
    const filters = c.filterButton('abQueueFilterBtn');
    const settings = c.settingsButton('abQueueSettingsBtn');
    root.appendChild(filters); root.appendChild(settings);
    client('en-US').apply(root);
    assert.equal(filters.querySelector('[data-i18n]').textContent,
        client('en-US').t('batch-alt:filters.title'));
    assert.equal(settings.querySelector('[data-i18n]').textContent,
        client('en-US').t('batch-alt:settings.title'));
    assert.equal(root.children[0], filters);
    assert.equal(root.children[1], settings);
});

test('扩展词典加载与切换语言并发时保留最新语言和扩展 namespace', async () => {
    const c = harness();
    for (const name of ['commitQueueItemPatch', 'addItemsToQueue', 'removeFromQueue', 'renderQueue',
        'updateStats', 'syncAllResultsQueueState', 'getCookie', 'getCookieFmt', 'getStoredCookie',
        'setStoredCookie', 'removeStoredCookie', 'parseCookieToHeaderString', 'getCookieHeaderStringFor']) {
        c[name] = () => {};
    }
    c.PixivBatch.queueTypes = {i18nNamespaces: async () => ['fixture']};
    c.client = client('zh-CN'); c.run('pageI18n=client');
    let release;
    const calls = [];
    c.PixivI18n = {create: async options => {
        calls.push(options);
        if (calls.length === 1) await new Promise(resolve => {release = resolve;});
        return {...client(options.lang), namespaces: options.namespaces};
    }};
    c.load('alt-extensions.js');
    const pending = c.refreshAltI18n();
    while (!release) await Promise.resolve();
    c.client = client('en-US'); c.run('pageI18n=client');
    release(); await pending;
    assert.equal(c.uiLang(), 'en-US');
    assert.equal(calls.at(-1).lang, 'en-US');
    assert.ok(calls.at(-1).namespaces.includes('fixture'));
});

for (const alt of [false, true]) {
    for (const confirmed of [false, true]) {
        test(`${alt ? '新版' : '经典'}插画完成状态保存原始事实并在重载后按当前语言显示${confirmed ? '（补查确认）' : ''}`, async () => {
            const c = harness();
            c.client = client('zh-CN'); c.run('pageI18n=client');
            const result = {completed: true, downloadedCount: 2,
                bookmarkResult: {status: 'success'}, collectionResult: {status: 'exists'}};
            Object.assign(c, {state: {settings: {}}, quotaInfo: {enabled: false},
                dockState: {quota: {enabled: false}}, STATUS_TIMEOUT_MS: 1000,
                getArtworkMeta: async () => ({illustTitle: 'fixture', illustType: 0}),
                getArtworkPages: async () => ['image1', 'image2'], normalizeAuthorId: value => value,
                evaluateDownloadFilterSkip: () => null, sendDownload: async () => ({}),
                waitForFinalStatusBySSE: async () => confirmed ? null : result,
                getDownloadStatus: async () => result, mergeUgoiraProgress: () => null,
                handlePathActionError: () => false, assertProcessInvocation() {}});
            for (const name of ['renderQueue', 'setCurrent', 'setStatus', 'setDockStatus', 'saveQueue',
                'openSSE', 'closeSSE', 'ensureSharedSSE', 'closeAllSSE', 'initQuota', 'showArchiveCard',
                'triggerAdminPack', 'updateStats', 'renderCurrent', 'notifyFirstDownloadCompleted',
                'loadQueueForMode', 'clearSavedQueue', 'ensureDockVue', 'altQueueVueActive']) c[name] = () => {};
            vm.runInContext(readFileSync(resolve(resources, 'static', alt ? 'pixiv-batch-alt/alt-engine-workers.js'
                : 'pixiv-batch/batch-download-workers.js'), 'utf8'), c);
            const item = {id: '42', status: 'downloading', statusMessageKey: 'stale:key'};
            await c.processIllustItem(item);
            const restored = JSON.parse(JSON.stringify(item));
            assert.equal(restored.status, 'completed');
            assert.equal(restored.statusMessageKey, 'batch:queue.message.' + (confirmed ? 'completed-confirmed' : 'completed-images'));
            assert.equal(restored.lastMessage, '');
            c.client = client('en-US'); c.run('pageI18n=client');
            const expected = c.client.t(restored.statusMessageKey, '', {count: 2});
            if (alt) {
                c.load('alt-queue.js');
                assert.equal(c.queueItemMessage(restored), expected);
            } else {
                vm.runInContext(readFileSync(resolve(resources, 'static/pixiv-batch/batch-queue-model.js'), 'utf8'), c);
                c.statusColor = () => 'color'; c.punct = () => '; ';
                const parts = c.queueMessageModel(restored, expected);
                assert.equal(parts[0].text, expected + '; ');
                assert.equal(parts[1].text, c.bt('queue.outcome.pixiv-bookmark.success') + '; ');
                assert.equal(parts[2].text, c.bt('queue.outcome.collection.exists'));
            }
            c.PixivBatch.queueTypes = {get: () => ({process: async value => {
                value.statusMessageKey = 'batch:queue.message.completed';
                throw Object.assign(new Error('withdrawn'), {code: 'STALE_QUEUE_TYPE'});
            }})};
            c.updateStats = () => {}; c.renderQueue = () => {}; c.saveQueue = () => {};
            await c.processSingle(restored);
            assert.equal(restored.status, 'paused');
            assert.equal(restored.statusMessageKey, null);
            assert.equal(restored.lastMessage, c.bt('queue.message.type-unavailable'));
        });
    }
}
