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
const pageSource = fs.readFileSync(path.join(__dirname, '..', '..', 'main', 'resources',
    'static', 'pixiv-batch-alt.html'), 'utf8');
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
    assert.deepStrictEqual(Array.from(await sandbox.altI18nNamespaces()),
        ['batch-alt', 'common', 'tour', 'novel', 'schedule-extra']);
    assert(pageSource.includes('data-nav-link-class="ab-topnav-link"'));
    assert(pageSource.includes('data-nav-current="download-workbench"'));
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
    console.log('batch-alt-extensions.test.js: runtime, i18n and chrome regressions passed ✓');
})().catch(error => {
    console.error(error);
    process.exitCode = 1;
});
