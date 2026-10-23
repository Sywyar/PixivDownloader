'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const altStatic = path.join(__dirname, '..', '..', 'main', 'resources',
    'static', 'pixiv-batch-alt');
const readAltFiles = (...names) => names
    .map(name => fs.readFileSync(path.join(altStatic, name), 'utf8'))
    .join('\n');
const source = fs.readFileSync(path.join(__dirname, '..', '..', 'main', 'resources',
    'static', 'pixiv-batch-alt', 'alt-extensions.js'), 'utf8');
const scheduleSource = readAltFiles(
    'alt-schedule.js', 'alt-schedule-actions.js', 'alt-schedule-editor.js');
const coreSource = readAltFiles('../pixiv-batch/queue-display.js', 'alt-core.js');
const pageSource = fs.readFileSync(path.join(__dirname, '..', '..', 'main', 'resources',
    'static', 'pixiv-batch-alt.html'), 'utf8');

// HTML 交付物保留跨模块声明入口，运行行为由各自的脚本测试负责。
assert(pageSource.includes('data-nav-slot="app.top"'));
assert(pageSource.includes('data-nav-current="download-workbench"'));
assert(pageSource.includes('data-qt-slot="topbar-actions"'));
const layoutButton = pageSource.match(/<button\b[^>]*data-download-page="pixiv-batch.html"[^>]*>/)[0];
assert(layoutButton.includes('data-i18n-title="page.switch-to-old-layout"'));
assert(layoutButton.includes('data-i18n-aria-label="page.switch-to-old-layout"'));
assert(!/\sdata-i18n=/.test(layoutButton), '按钮子节点负责文字翻译，保留图标');

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
    bt: key => key,
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

const authRefreshes = [];
sandbox.renderAuthButton = () => authRefreshes.push('auth');
sandbox.updateButtonsState = () => authRefreshes.push('actions');
sandbox.updateAuthButtons();
sandbox.updateAdminPackButton();
assert.deepStrictEqual(authRefreshes, ['auth', 'actions'], '贡献模块的会话失效应刷新新版登录与操作状态');

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
assert.throws(() => sandbox.altNextCursor({nextCursor: '1'}, '1', true), /pagination.error.cursor-stalled/);
sandbox.window.PixivBatchAlt.schedule = {};
vm.runInContext(scheduleSource, sandbox);
assert.strictEqual(sandbox.isValidProxyHostPort('127.0.0.1:65535'), true);
assert.strictEqual(sandbox.isValidProxyHostPort('127.0.0.1:65536'), false);
assert.strictEqual(sandbox.isValidProxyHostPort('http://127.0.0.1:7890'), false);
assert.strictEqual(sandbox.scheduleTaskKind({presentation: {attributes: {kind: 'external'}}}), 'external');
assert.strictEqual(sandbox.scheduleTaskKind({presentation: {}}), null);
const originalBt = sandbox.bt;
let typeLabelLocale = 'en-US', typeAvailable = true;
sandbox.window.PixivBatch.queueTypes.manifestDescriptor = type => typeAvailable && type === 'external'
    ? {displayNamespace: 'fixture', displayI18nKey: 'work.type'} : null;
sandbox.bt = (key, fallback) => key === 'fixture:work.type' ? typeLabelLocale + ' provider' : originalBt(key, fallback);
assert.strictEqual(sandbox.scheduleKindLabel('external'), 'en-US provider', '计划使用贡献类型的所属词典');
typeLabelLocale = 'ja-JP';
assert.strictEqual(sandbox.scheduleKindLabel('external'), 'ja-JP provider', '每次展示读取当前语言');
typeAvailable = false;
assert.strictEqual(sandbox.scheduleKindLabel('external'), 'external', '贡献撤回后保留作品类型身份');
assert.strictEqual(sandbox.scheduleKindLabel('unknown-work'), 'unknown-work', '未知类型不得显示为插画');
sandbox.bt = originalBt;
for (const kind of ['illust', 'novel', 'mixed']) {
    assert.strictEqual(sandbox.scheduleKindLabel(kind), 'schedule.kind.' + kind);
}
sandbox.window.PixivBatch.scheduleSources.credentialTaskPresentation = (_sourceType, task) =>
    task.suspendCode === 'pixiv.schedule.overuse'
        ? {statusLabel: 'Provider policy detail', lightTone: 'red', lightText: 'Provider policy detail'}
        : null;
const suspendedTask = {
    enabled: true,
    sourceType: 'user-new',
    lastStatus: 'OVERUSE_PAUSED',
    suspendReason: 'POLICY',
    suspendCode: 'pixiv.schedule.overuse'
};
assert.strictEqual(sandbox.scheduleStatusLight(suspendedTask).text, 'Provider policy detail');
assert.strictEqual(sandbox.scheduleStatusLabel(suspendedTask), 'Provider policy detail');

(async () => {
    const runtime = sandbox.window.PixivBatch.queueTypes;
    const descriptor = {type: 'external'};
    runtime.dataSourcesForMode = mode => {
        assert.strictEqual(mode, 'search');
        return [{id: 'fixture', displayNamespace: 'fixture', displayI18nKey: 'source', types: ['external']}];
    };
    runtime.typesForDataSource = (mode, source) => mode === 'search' && source === 'fixture' ? [descriptor] : [];
    runtime.resolveSelectionForMode = selection => selection === 'external-alias' ? 'external' : selection;
    runtime.acquisition = () => ({request: 'fixture-request'});
    assert.deepStrictEqual(JSON.parse(JSON.stringify(sandbox.altSourcesForMode('search'))),
        [{id: 'fixture', label: 'fixture:source', types: ['external']}]);
    assert.strictEqual(sandbox.altSelectionForMode('search', 'fixture', 'external-alias'), 'external-alias');
    assert.strictEqual(sandbox.altSelectionForMode('search', 'fixture', 'missing'), 'external');
    assert.strictEqual(sandbox.altSelectionForMode('search', 'withdrawn', 'external'), null);
    assert.strictEqual(sandbox.altAcquisition('search', 'withdrawn', 'external'), null);
    assert.deepStrictEqual(JSON.parse(JSON.stringify(sandbox.altAcquisition('search', 'fixture', 'external-alias'))),
        {request: 'fixture-request', type: 'external'});

    const requestInit = {credentials: 'same-origin'};
    let current = true, finishBody;
    const operationContext = {signal: 'fixture-signal'};
    runtime.prepareAcquisitionRequest = (type, mode, url, operation, context) => {
        assert.strictEqual(type, 'external');
        assert.strictEqual(mode, 'search');
        assert.strictEqual(url, '/fixture?fixed=1&q=a+b&q=%E4%BD%9C%E5%93%81&page=0');
        assert.strictEqual(operation, 'search');
        assert.strictEqual(context, operationContext);
        return {url, init: requestInit, assertCurrent() { if (!current) throw new Error('withdrawn'); }};
    };
    sandbox.fetch = async (url, init) => {
        assert.strictEqual(init, requestInit);
        assert.strictEqual(url.startsWith('/fixture?'), true);
        return {ok: true, json: () => new Promise(resolve => { finishBody = resolve; })};
    };
    const acquire = () => sandbox.altAcquisitionJson('external', 'search',
        {endpoint: '/fixture?fixed=1', params: {q: ['a b', '作品'], page: 0, absent: null}},
        'search', operationContext);
    const pending = acquire();
    await new Promise(resolve => setImmediate(resolve));
    current = false;
    finishBody({items: ['stale']});
    await assert.rejects(pending, /withdrawn/);
    current = true;
    const active = acquire();
    await new Promise(resolve => setImmediate(resolve));
    const data = {items: ['current']};
    finishBody(data);
    assert.strictEqual(await active, data);
    const failed = acquire();
    await new Promise(resolve => setImmediate(resolve));
    finishBody({error: 'provider-error'});
    await assert.rejects(failed, /provider-error/);

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
    feedbackSandbox.bt = key => key;
    assert.strictEqual(await feedbackSandbox.window.PixivBatchAlt.core.abConfirm(
        'dialog.confirm-clear-queue', '确认清除队列？'), true);
    assert.strictEqual(feedbackCalls[0].confirmLabel, 'common.confirm');
    assert.strictEqual(feedbackCalls[0].message, 'dialog.confirm-clear-queue');
    assert.deepStrictEqual(Array.from(await sandbox.altI18nNamespaces()),
        ['batch-alt', 'batch', 'common', 'tour', 'layout-feedback', 'novel', 'schedule-extra']);
    const pendingLanguages = [];
    let appliedLanguage = '';
    sandbox.document = {};
    sandbox.pageI18n = null;
    sandbox.PixivI18n = sandbox.window.PixivI18n = {
        create: ({namespaces}) => new Promise(resolve => pendingLanguages.push({namespaces, resolve}))
    };
    const firstLanguage = sandbox.refreshAltI18n();
    while (pendingLanguages.length < 1) await new Promise(resolve => setImmediate(resolve));
    sandbox.window.PixivBatch.queueTypes.i18nNamespaces = async () => ['new-owner'];
    const newestLanguage = sandbox.refreshAltI18n();
    while (pendingLanguages.length < 2) await new Promise(resolve => setImmediate(resolve));
    assert(pendingLanguages[1].namespaces.includes('new-owner'));
    const disposedLanguages = [];
    pendingLanguages[1].resolve({apply() { appliedLanguage = 'new-owner'; },
        dispose() { disposedLanguages.push('new-owner'); }});
    await newestLanguage;
    pendingLanguages[0].resolve({apply() { appliedLanguage = 'stale-owner'; },
        dispose() { disposedLanguages.push('stale-owner'); }});
    await firstLanguage;
    assert.strictEqual(appliedLanguage, 'new-owner', '迟到的资源响应不得覆盖新的插件语言快照');
    assert.deepStrictEqual(disposedLanguages, ['stale-owner'], '仅释放被新刷新淘汰的词典');
    console.log('batch-alt-extensions.test.js: extension adapter regressions passed ✓');
})().catch(error => {
    console.error(error);
    process.exitCode = 1;
});
