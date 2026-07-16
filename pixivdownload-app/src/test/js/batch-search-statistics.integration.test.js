'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const test = require('node:test');

const ROOT = path.join(__dirname, '..', '..', '..', '..');
const SEARCH_SOURCE = read('pixivdownload-plugin-download-workbench', 'src', 'main', 'resources',
    'static', 'pixiv-batch', 'modes', 'search.js');
const PIXIV_SOURCE = read('pixivdownload-plugin-download-workbench', 'src', 'main', 'resources',
    'static', 'pixiv-batch', 'pixiv-queue-type.js');
const NOVEL_SOURCE = read('pixivdownload-plugin-novel', 'src', 'main', 'resources',
    'static', 'pixiv-novel-download', 'novel-queue-type.js');
const DOUYIN_SOURCE = read('pixivdownload-plugin-douyin', 'src', 'main', 'resources',
    'static', 'pixiv-douyin-download', 'douyin-queue-type.js');

function read() {
    return fs.readFileSync(path.join(ROOT, ...arguments), 'utf8');
}

function interpolate(template, vars) {
    return String(template).replace(/\{([a-zA-Z0-9_.-]+)\}/g, (match, key) =>
        vars && Object.prototype.hasOwnProperty.call(vars, key) ? String(vars[key]) : match);
}

function hostFormatter(type, acquisition) {
    const sandbox = {
        window: {
            PixivBatch: {
                queueTypes: {acquisition() { return acquisition; }},
                modes: {search: {}}
            }
        },
        defaultSearchFilters() { return {}; },
        bt(_key, fallback, vars) { return interpolate(fallback, vars); },
        URLSearchParams,
        console: {warn() {}, log() {}, error() {}}
    };
    vm.createContext(sandbox);
    vm.runInContext(SEARCH_SOURCE, sandbox);
    vm.runInContext('searchState.kind = ' + JSON.stringify(type), sandbox);
    return (metric, count) =>
        vm.runInContext('searchStatText(' + JSON.stringify(metric) + ', ' + Number(count) + ')', sandbox);
}

function sourceSearchAcquisition(source, type) {
    let descriptor = null;
    const controller = new AbortController();
    const context = {
        type,
        manifest: {pluginGeneration: 1},
        signal: controller.signal,
        isActive() { return true; },
        assertActive() {},
        onCleanup() {}
    };
    const window = {
        PixivBatch: {
            cookie: null,
            queueTypes: {
                registerModule(initializer) {
                    const registration = initializer(context);
                    descriptor = registration && registration.descriptor;
                    return true;
                }
            }
        },
        addEventListener() {},
        removeEventListener() {}
    };
    const sandbox = {
        window,
        document: {
            getElementById() { return null; },
            querySelectorAll() { return []; },
            createElement() { return {}; }
        },
        localStorage: {getItem() { return null; }, setItem() {}, removeItem() {}},
        BASE: '',
        QUICK_PAGE_SIZE_ILLUST: 60,
        QUICK_PAGE_SIZE_NOVEL: 24,
        SINGLE_IMPORT_MODE: 'single-import',
        SINGLE_IMPORT_NOVEL_SOURCE: 'single-import-novel',
        processIllustItem() {},
        renderPixivSearchResults() {},
        renderQuickIllustGrid() {},
        pixivQuickInnerCard() { return ''; },
        bt(_key, fallback, vars) { return interpolate(fallback, vars); },
        esc(value) { return String(value == null ? '' : value); },
        state: {queue: [], settings: {}},
        URL,
        URLSearchParams,
        AbortController,
        Promise,
        fetch() { throw new Error('unexpected fetch'); },
        setTimeout() { return 1; },
        clearTimeout() {},
        setInterval() { return 1; },
        clearInterval() {},
        console: {warn() {}, log() {}, error() {}}
    };
    vm.createContext(sandbox);
    vm.runInContext(source, sandbox);
    assert.ok(descriptor && descriptor.acquisition && descriptor.acquisition.search,
        type + ' search acquisition should register');
    return descriptor.acquisition.search;
}

test('宿主可端到端消费 Pixiv 小说与抖音的来源统计格式化契约', () => {
    const cases = [
        ['illust', PIXIV_SOURCE, 'total', 'Pixiv 总数 12'],
        ['novel', NOVEL_SOURCE, 'current-page', '小说当前页 12 部'],
        ['douyin', DOUYIN_SOURCE, 'batch-fetched', '已抓取去重 12 个抖音作品']
    ];
    cases.forEach(([type, source, metric, expected]) => {
        const acquisition = sourceSearchAcquisition(source, type);
        assert.strictEqual(hostFormatter(type, acquisition)(metric, 12), expected);
    });
});
