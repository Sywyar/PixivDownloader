'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const test = require('node:test');

const ROOT = path.join(__dirname, '..', '..', '..', '..');
const SEARCH_SOURCE = read('pixivdownload-plugin-download-workbench', 'src', 'main', 'resources',
    'static', 'pixiv-batch', 'modes', 'search.js');
const FILTER_SOURCE = read('pixivdownload-plugin-download-workbench', 'src', 'main', 'resources',
    'static', 'pixiv-batch', 'batch-filters.js');
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

function hostFormatter(acquisition) {
    const warnings = [];
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
        console: {warn() { warnings.push(Array.from(arguments)); }, log() {}, error() {}}
    };
    vm.createContext(sandbox);
    vm.runInContext(SEARCH_SOURCE, sandbox);
    vm.runInContext("searchState.kind = 'demo'", sandbox);
    return {
        format(metric, count) {
            return vm.runInContext(`searchStatText(${JSON.stringify(metric)}, ${Number(count)})`, sandbox);
        },
        warnings
    };
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
        `${type} search acquisition should register`);
    return descriptor.acquisition.search;
}

function propertyKeys(moduleName, fileName) {
    const text = read(moduleName, 'src', 'main', 'resources', 'i18n', 'web', fileName);
    return new Set(text.split(/\r?\n/)
        .map(line => line.trim())
        .filter(line => line && !line.startsWith('#') && !line.startsWith('!'))
        .map(line => line.split('=', 1)[0].trim()));
}

test('宿主使用来源统计格式化钩子并在异常时回退中性文案', () => {
    const contributed = hostFormatter({
        formatStats(metric, stats) { return `source-${metric}-${stats.count}-${stats.submode}`; }
    });
    assert.strictEqual(contributed.format('total', 12), 'source-total-12-search');

    const blank = hostFormatter({formatStats() { return '   '; }});
    assert.strictEqual(blank.format('total', 12), '来源总数 12');

    const failed = hostFormatter({formatStats() { throw new Error('broken formatter'); }});
    assert.strictEqual(failed.format('returned', 7), '来源返回 7 个');
    assert.strictEqual(failed.warnings.length, 1);

    const absent = hostFormatter({});
    assert.strictEqual(absent.format('batch-fetched', 9), '已抓取去重 9 个');
});

test('Pixiv 小说与抖音行为模块分别贡献搜索统计标签', () => {
    const pixiv = sourceSearchAcquisition(PIXIV_SOURCE, 'illust');
    const novel = sourceSearchAcquisition(NOVEL_SOURCE, 'novel');
    const douyin = sourceSearchAcquisition(DOUYIN_SOURCE, 'douyin');

    assert.strictEqual(pixiv.formatStats('total', {count: 12}), 'Pixiv 总数 12');
    assert.strictEqual(novel.formatStats('current-page', {count: 12}), '小说当前页 12 部');
    assert.strictEqual(douyin.formatStats('batch-fetched', {count: 12}), '已抓取去重 12 个抖音作品');
});

test('搜索批量抓取与筛选状态统一走来源统计格式化入口', () => {
    assert.ok(!SEARCH_SOURCE.includes("bt('search.summary.pixiv-total'"));
    assert.ok(!FILTER_SOURCE.includes("bt('search.summary.pixiv-total'"));
    assert.match(SEARCH_SOURCE, /parts\.push\(searchStatText\('total', searchState\.total\)\)/);
    assert.match(SEARCH_SOURCE, /searchStatText\('batch-fetched', searchState\.rawResults\.length\)/);
    assert.match(FILTER_SOURCE, /searchStatText\('current-page', stats\.rawCount\)/);
    assert.match(FILTER_SOURCE, /searchStatText\('total', searchState\.total\)/);
});

test('来源统计文案中英文键集合保持一致', () => {
    const pairs = [
        ['pixivdownload-plugin-download-workbench', 'batch.properties', 'batch_en.properties', [
            'search.summary.source-total', 'search.summary.source-returned',
            'search.batch.summary.pixiv-fetched'
        ]],
        ['pixivdownload-plugin-novel', 'novel.properties', 'novel_en.properties', [
            'batch.search.summary.current-page', 'batch.search.summary.total',
            'batch.search.summary.returned', 'batch.search.summary.fetched'
        ]],
        ['pixivdownload-plugin-douyin', 'douyin.properties', 'douyin_en.properties', [
            'search.summary.current-page', 'search.summary.total',
            'search.summary.returned', 'search.summary.fetched'
        ]]
    ];
    pairs.forEach(([moduleName, zhName, enName, expected]) => {
        const zh = propertyKeys(moduleName, zhName);
        const en = propertyKeys(moduleName, enName);
        assert.deepStrictEqual(Array.from(en).sort(), Array.from(zh).sort(), `${moduleName} i18n keys`);
        expected.forEach(key => assert.ok(zh.has(key), `${moduleName} should define ${key}`));
    });
});
