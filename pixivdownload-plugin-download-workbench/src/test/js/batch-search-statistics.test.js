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

function translation(key, _fallback, vars) {
    return JSON.stringify([key, vars]);
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
        bt: translation,
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

function pixivDescriptor(bt = translation) {
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
                },
                resolveTypeForMode(kind) { return kind == null ? null : String(kind); }
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
        bt,
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
    assert.deepStrictEqual(JSON.parse(blank.format('total', 12)),
        ['search.summary.source-total', {count: '12'}]);

    const failed = hostFormatter({formatStats() { throw new Error('broken formatter'); }});
    assert.deepStrictEqual(JSON.parse(failed.format('returned', 7)),
        ['search.summary.source-returned', {count: '7'}]);
    assert.strictEqual(failed.warnings.length, 1);

    const absent = hostFormatter({});
    assert.deepStrictEqual(JSON.parse(absent.format('batch-fetched', 9)),
        ['search.batch.summary.fetched', {count: '9'}]);
});

test('Pixiv 行为模块贡献来源自有的搜索统计标签', () => {
    const pixiv = pixivDescriptor().acquisition.search;
    assert.deepStrictEqual(JSON.parse(pixiv.formatStats('total', {count: 12})),
        ['search.summary.pixiv-total', {count: '12'}]);
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

test('Pixiv 行为模块拥有二层快捷计划来源映射', () => {
    const actions = pixivDescriptor().acquisition.quick.actions;
    const followingIllust = actions['my-following-show'].scheduleSource({
        inner: {type: 'following-user', userId: '42', name: 'Painter', kind: 'illust'}
    });
    const followingNovel = actions['my-following-hide'].scheduleSource({
        inner: {type: 'following-user', userId: '43', name: 'Writer', kind: 'novel'}
    });
    const collection = actions['my-collections'].scheduleSource({
        inner: {type: 'collection', id: '77', name: 'Mixed collection'}
    });

    assert.deepStrictEqual(JSON.parse(JSON.stringify(followingIllust)), {
        sourceType: 'user-new', type: 'USER_NEW', source: {userId: '42'},
        kind: 'illust', label: JSON.stringify(['quick.schedule.source.user', {name: 'Painter', id: '42'}])
    });
    assert.deepStrictEqual(JSON.parse(JSON.stringify(followingNovel)), {
        sourceType: 'user-new', type: 'USER_NEW', source: {userId: '43'},
        kind: 'novel', label: JSON.stringify(['quick.schedule.source.user', {name: 'Writer', id: '43'}])
    });
    assert.deepStrictEqual(JSON.parse(JSON.stringify(collection)), {
        sourceType: 'collection', type: 'COLLECTION', source: {collectionId: '77'},
        kind: 'mixed', label: JSON.stringify(['quick.schedule.source.collection', {name: 'Mixed collection', id: '77'}]),
        workTypes: ['illust', 'novel']
    });
    assert.strictEqual(actions['my-following-show'].scheduleSource({inner: null}), null);
    assert.strictEqual(actions['my-collections'].scheduleSource({inner: null}), null);
});

test('Pixiv 各取得入口在顶层携带原始数字取消键', () => {
    const descriptor = pixivDescriptor();
    const search = descriptor.acquisition.search.buildQueueMeta({id: 123456, title: 'Image'});
    const imported = descriptor.import.buildItem('234567', 'Imported');
    const quick = descriptor.acquisition.quick.buildQueueMetaFromId('345678', {});
    const scheduled = descriptor.scheduledQueueItem({
        workId: '456789',
        workType: 'illust',
        title: 'Scheduled image',
        author: 'Scheduled artist',
        thumbnailReference: 'thumb:illust:456789',
        presentationAttributes: {xRestrict: '1', ai: 'false'},
        resultAttributes: {xRestrict: '2', ai: 'true'}
    }, {sourceType: 'search'});

    assert.strictEqual(search.cancelWorkKey, '123456');
    assert.strictEqual(imported.cancelWorkKey, '234567');
    assert.strictEqual(quick.cancelWorkKey, '345678');
    assert.strictEqual(scheduled.cancelWorkKey, '456789');
    assert.strictEqual(scheduled.rawTitle, 'Scheduled image');
    assert.strictEqual(scheduled.authorName, 'Scheduled artist');
    assert.strictEqual(scheduled.thumbnailReference, 'thumb:illust:456789');
    assert.strictEqual(scheduled.xRestrict, 2);
    assert.strictEqual(scheduled.isAi, true);
});

test('无标题插画导入只保存原始值，显示和重载随当前语言派生标题', () => {
    let language = 'en';
    const bt = (key, _fallback, args) => `${language}:${JSON.stringify([key, args])}`;
    const importer = pixivDescriptor(bt).import;
    const context = vm.createContext({bt});
    vm.runInContext(fs.readFileSync(path.join(BATCH_ROOT, 'queue-display.js'), 'utf8'), context);
    const item = importer.buildItem('42', '');
    assert.strictEqual(item.title, '');
    assert.strictEqual(context.queueItemDisplayTitle(item),
        'en:' + JSON.stringify(['queue.artwork-fallback', {id: '42'}]));
    const restored = JSON.parse(JSON.stringify(item));
    language = 'ja';
    assert.strictEqual(context.queueItemDisplayTitle(restored),
        'ja:' + JSON.stringify(['queue.artwork-fallback', {id: '42'}]));
    const titled = importer.buildItem('43', 'Original title');
    assert.strictEqual(context.queueItemDisplayTitle(titled), 'Original title');
});

test('搜索和批量筛选摘要按来源渲染，切换语言保留原始计数', async () => {
    let lang = 'en';
    const calls = [];
    const nodes = new Map();
    const sandbox = vm.createContext({
        window: {}, console, URLSearchParams,
        storeSet() {},
        document: {getElementById(id) {
            if (!nodes.has(id)) nodes.set(id, {style: {}, value: '', textContent: ''});
            return nodes.get(id);
        }}
    });
    vm.runInContext(fs.readFileSync(path.join(BATCH_ROOT, 'queue-display.js'), 'utf8'), sandbox);
    vm.runInContext(fs.readFileSync(path.join(BATCH_ROOT, 'batch-core.js'), 'utf8'), sandbox);
    vm.runInContext(fs.readFileSync(path.join(BATCH_ROOT, 'search-filters.js'), 'utf8'), sandbox);
    vm.runInContext(FILTER_SOURCE, sandbox);
    vm.runInContext(SEARCH_SOURCE, sandbox);
    sandbox.window.PixivBatch.queueTypes = {
        manifestDescriptor: () => ({owner: {pluginId: 'fixture', generation: 1, publicationId: 1}}),
        filtersFor: () => null,
        acquisition: () => ({formatStats(metric, facts) {
            calls.push([metric, facts.count, facts.submode]);
            return `${lang}:${metric}:${facts.count}`;
        }})
    };
    sandbox.renderSearchResults = sandbox.renderSearchPagination = sandbox.updateBatchQueueButtons = () => {};
    sandbox.translator = {t: translation};
    vm.runInContext('pageI18n = translator', sandbox);
    for (const submode of ['search', 'batch']) {
        calls.length = 0;
        lang = 'en';
        vm.runInContext(`Object.assign(searchState, {
            kind: 'fixture', submode: '${submode}', currentWord: 'fixture', total: 91,
            rawResults: [{id: '1'}, {id: '2'}]
        })`, sandbox);
        const stats = await sandbox.applyCurrentSearchFilters({setStatus: true, filters: {}});
        assert.strictEqual(stats.rawCount, 2);
        assert.strictEqual(stats.filteredCount, 2);
        const metric = submode === 'batch' ? 'batch-fetched' : 'current-page';
        assert.ok(nodes.get('status-bar').textContent.includes(`en:${metric}:2`));
        assert.ok(nodes.get('status-bar').textContent.includes('en:total:91'));
        vm.runInContext('searchState.rawResults = []; searchState.total = 0', sandbox);
        lang = 'ja';
        sandbox.renderStatus();
        assert.ok(nodes.get('status-bar').textContent.includes(`ja:${metric}:2`));
        assert.ok(nodes.get('status-bar').textContent.includes('ja:total:91'));
        assert.deepStrictEqual(calls, [
            [metric, 2, submode], ['total', 91, submode],
            [metric, 2, submode], ['total', 91, submode]
        ]);
    }
});
