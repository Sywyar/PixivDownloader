'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const test = require('node:test');

const BATCH_ROOT = path.join(__dirname, '..', '..', 'main', 'resources', 'static', 'pixiv-batch');
const SEARCH_SOURCE = fs.readFileSync(path.join(BATCH_ROOT, 'modes', 'search.js'), 'utf8');
const FILTER_SOURCE = fs.readFileSync(path.join(BATCH_ROOT, 'batch-filters.js'), 'utf8');
const PIXIV_SOURCE = fs.readFileSync(path.join(BATCH_ROOT, 'pixiv-queue-type.js'), 'utf8');

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
            return vm.runInContext('searchStatText(' + JSON.stringify(metric) + ', ' + Number(count) + ')',
                sandbox);
        },
        warnings
    };
}

function pixivDescriptor() {
    let descriptor = null;
    const controller = new AbortController();
    const context = {
        type: 'illust',
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
    vm.runInContext(PIXIV_SOURCE, sandbox);
    assert.ok(descriptor && descriptor.acquisition && descriptor.acquisition.search,
        'Pixiv descriptor should register');
    return descriptor;
}

test('宿主使用来源统计格式化钩子并在异常时回退中性文案', () => {
    const contributed = hostFormatter({
        formatStats(metric, stats) { return 'source-' + metric + '-' + stats.count + '-' + stats.submode; }
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

test('Pixiv 行为模块贡献来源自有的搜索统计标签', () => {
    const pixiv = pixivDescriptor().acquisition.search;
    assert.strictEqual(pixiv.formatStats('total', {count: 12}), 'Pixiv 总数 12');
});

test('Pixiv 行为模块按媒体与取得来源贡献队列标签', () => {
    const descriptor = pixivDescriptor();
    const ugoira = descriptor.acquisition.search.buildQueueMeta({
        id: '1', title: 'Animated', illustType: 2, aiType: 2
    });
    const ugoiraTags = descriptor.queueTags(Object.assign({id: '1'}, ugoira));
    assert.deepStrictEqual(Array.from(ugoiraTags, tag => tag.id), ['media.ugoira', 'attribute.ai']);

    const collection = descriptor.acquisition.quick.buildQueueMeta({
        id: '2', title: 'Image', illustType: 0
    }, {inner: {type: 'collection', id: 'showcase-2'}});
    const collectionTags = descriptor.queueTags(Object.assign({id: '2'}, collection));
    assert.deepStrictEqual(Array.from(collectionTags, tag => tag.id),
        ['media.image', 'origin.collection']);

    const merged = descriptor.mergeQueueTypeData(
        {sourceType: 'collection'}, {illustType: 1});
    assert.strictEqual(merged.typeData.sourceType, 'collection');
    assert.strictEqual(merged.typeData.illustType, 1);
});

test('搜索批量抓取与筛选状态统一走来源统计格式化入口', () => {
    assert.ok(!SEARCH_SOURCE.includes("bt('search.summary.pixiv-total'"));
    assert.ok(!FILTER_SOURCE.includes("bt('search.summary.pixiv-total'"));
    assert.match(SEARCH_SOURCE, /parts\.push\(searchStatText\('total', searchState\.total\)\)/);
    assert.match(SEARCH_SOURCE, /searchStatText\('batch-fetched', searchState\.rawResults\.length\)/);
    assert.match(FILTER_SOURCE, /searchStatText\('current-page', stats\.rawCount\)/);
    assert.match(FILTER_SOURCE, /searchStatText\('total', searchState\.total\)/);
});
