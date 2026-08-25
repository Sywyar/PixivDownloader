'use strict';
/* 下载类型清单、取得请求与队列投影契约。 */
const vm = require('vm');
const assert = require('assert');
const {
    SOURCE,
    INIT_SOURCE,
    SETTINGS_SOURCE,
    DOWNLOAD_SOURCE,
    SSE_SOURCE,
    El,
    typeDescriptor,
    uiSlotDescriptor,
    manifest,
    harness,
    waitUntil,
    BASIC_INITIALIZER,
    acquisitionDataSourceInitializer,
    quickDataSourceInitializer,
    importDataSourceInitializer,
    quickAndImportDataSourceInitializer,
    queueTagsInitializer,
    queueLiveStatusInitializer,
    seriesBrowserInitializer,
    REQUEST_OWNER_INITIALIZER,
    FAILING_INITIALIZER,
    PENDING_INITIALIZER,
    IN_FLIGHT_PROCESS_INITIALIZER,
    PATCH_PROCESS_INITIALIZER,
    LATE_QUEUE_INITIALIZER,
    UI_INITIALIZER,
    quickActionInitializer,
    LATE_UI_INITIALIZER
} = require('./batch-queue-types-fixture');

let passed = 0;
function ok(label, condition) {
    assert.ok(condition, label);
    passed++;
}

(async function main() {
    {
        const h = harness([
            manifest(1, [typeDescriptor({
                acquisitionModes: ['series'],
                owner: {pluginId: 'nested-owner', packageId: 'nested-package', generation: 5, publicationId: 6},
                pluginId: 'forged-plugin',
                queue: {cancel: true},
                schedule: {saveable: true},
                gallery: {reasonNamespace: 'legacy-gallery'},
                uiSlots: ['cookie-tools']
            })])
        ], {'/modules/demo.js': {initializer: BASIC_INITIALIZER}});
        ok('运行时不再暴露旧模块登记入口', typeof h.qt.register === 'undefined');
        ok('作用域外不能登记模块', h.qt.registerModule(() => ({})) === false);
        await h.qt.bootstrap();
        ok('后端声明的 series 模式可见', h.qt.supports('demo', 'series'));
        ok('未声明 search 即使模块提供 hook 也不可见', !h.qt.supports('demo', 'search'));
        ok('未声明 search hook 永不返回', h.qt.acquisition('demo', 'search') === null);
        ok('typesForMode 只含已声明且实现的类型', h.qt.typesForMode('series').join(',') === 'demo');
        ok('resolveTypeForMode 不回退到未声明模式', h.qt.resolveTypeForMode('demo', 'search') === null);
        ok('owner 由后端 manifest 注入', h.qt.descriptor('demo').owner.ownerPluginId === 'nested-owner');
        const projectedManifest = h.qt.manifestDescriptor('demo');
        ok('manifestDescriptor 只读暴露 lean descriptor、cancelSupported 与后端 owner',
            projectedManifest.owner.pluginId === 'nested-owner'
            && Object.isFrozen(projectedManifest.acquisitionModes)
            && projectedManifest.cancelSupported === true
            && !['pluginId', 'queue', 'schedule', 'gallery', 'uiSlots']
                .some(field => Object.prototype.hasOwnProperty.call(projectedManifest, field)));
        ok('旧 gallery reason namespace 不再进入预加载集合',
            !(await h.qt.i18nNamespaces()).includes('legacy-gallery'));
        ok('模块不能用 descriptor 覆盖 type', h.qt.descriptor('demo').type === 'demo');
        const ownedScheduled = h.qt.scheduledQueueItem('demo', {id: '7'}, {});
        ok('scheduledQueueItem 调用 owner hook 但保留宿主盖章身份',
            h.sandbox.testState.scheduledOwnerCalls === 1
            && ownedScheduled.id === '7' && ownedScheduled.kind === 'demo'
            && ownedScheduled.workId === '7' && ownedScheduled.workType === 'demo'
            && ownedScheduled.ownerMapped === true
            && ownedScheduled.queueKey === h.qt.queueKey('demo', '7'));
        const opaqueWorkId = `  /"'<> work id  `;
        const opaqueScheduled = h.qt.scheduledQueueItem('demo', {
            workId: opaqueWorkId,
            liveStatus: {phase: 'REAL'}
        }, {});
        ok('不透明 workId 原样保留且复合 key 使用不含原文的安全编码',
            opaqueScheduled.id === opaqueWorkId
            && opaqueScheduled.workId === opaqueWorkId
            && opaqueScheduled.queueKey === h.qt.queueKey('demo', opaqueWorkId)
            && !opaqueScheduled.queueKey.includes(opaqueWorkId)
            && opaqueScheduled.queueKey !== h.qt.queueKey('other', opaqueWorkId));
        const neutralFallback = vm.runInContext(`window.PixivBatch.queueTypes.scheduledQueueItem(
            'third-party',
            {
                workId: 'opaque-7',
                workType: 'third-party',
                title: 'Neutral title',
                author: 'Neutral author',
                thumbnailReference: 'thumb:opaque-7',
                presentationAttributes: {xRestrict: '2', ai: 'true'},
                resultAttributes: {resultCode: 'done'},
                liveStatus: {phase: 'PRIVATE'}
            },
            {source: 'schedule'}
        )`, h.sandbox);
        ok('缺席 owner 的计划 DTO 只保留中性展示与 raw 状态，不猜测插件属性语义',
            neutralFallback.id === 'opaque-7'
            && neutralFallback.kind === 'third-party'
            && neutralFallback.rawTitle === 'Neutral title'
            && neutralFallback.author === 'Neutral author'
            && neutralFallback.thumbnailReference === 'thumb:opaque-7'
            && neutralFallback.liveStatus.phase === 'PRIVATE'
            && neutralFallback.queueKey === h.qt.queueKey('third-party', 'opaque-7')
            && neutralFallback.presentationAttributes.xRestrict === '2'
            && !Object.prototype.hasOwnProperty.call(neutralFallback, 'xRestrict')
            && !Object.prototype.hasOwnProperty.call(neutralFallback, 'isAi'));
        ok('scheduled SSE 能力来自 descriptor', h.qt.supportsScheduledSse('demo') === false);
        ok('缺席类型不会默认订阅 scheduled SSE', h.qt.supportsScheduledSse('missing') === false);
    }

    {
        const h = harness([
            manifest(1, [typeDescriptor()]),
            manifest(2, [])
        ], {'/modules/demo.js': {initializer: BASIC_INITIALIZER}});
        await h.qt.bootstrap();
        const manifestFetch = h.sandbox.fetch;
        const cancellationRequests = [];
        h.sandbox.fetch = (url, init) => {
            if (String(url).endsWith('/api/download/extensions')) return manifestFetch(url, init);
            cancellationRequests.push({url: String(url), init});
            return Promise.resolve({
                ok: true,
                status: 200,
                json: () => Promise.resolve({success: true})
            });
        };
        const rawWorkKey = ' opaque/path:part ? # 中文 ';
        const item = {
            id: 'demo:display-only',
            kind: 'demo',
            status: 'downloading',
            cancelWorkKey: rawWorkKey
        };
        ok('活动类型只有后端声明 cancelSupported 且队列项携原始 workKey 时才可取消',
            h.qt.canCancel(item) === true);
        const result = await h.qt.cancel(item);
        const cancellationBody = JSON.parse(cancellationRequests[0].init.body);
        ok('单项取消按 queueType 定向 POST JSON，原样保留 workKey 并携后端 publication owner',
            result.success === true
            && cancellationRequests.length === 1
            && cancellationRequests[0].url === '/api/download/queue/demo/cancel'
            && cancellationRequests[0].init.method === 'POST'
            && cancellationRequests[0].init.credentials === 'same-origin'
            && cancellationBody.workKey === rawWorkKey
            && cancellationBody.owner.pluginId === 'demo-owner'
            && cancellationBody.owner.packageId === 'demo-package'
            && cancellationBody.owner.generation === 1
            && cancellationBody.owner.publicationId === 1);
        ok('第三方类型绝不从展示 id 推导取消键',
            h.qt.canCancel({id: 'demo:display-only', kind: 'demo', status: 'downloading'}) === false);
        await assert.rejects(
            () => h.qt.cancel({id: 'demo:display-only', kind: 'demo'}),
            error => error && error.code === 'QUEUE_CANCEL_UNAVAILABLE');
        passed++;
        ok('缺失原始 workKey 的第三方队列项不会发请求', cancellationRequests.length === 1);

        let releaseCancellation;
        h.sandbox.fetch = (url, init) => {
            if (String(url).endsWith('/api/download/extensions')) return manifestFetch(url, init);
            return new Promise(resolve => {
                releaseCancellation = () => resolve({
                    ok: true,
                    status: 200,
                    json: () => Promise.resolve({success: true})
                });
            });
        };
        const stale = h.qt.cancel(item);
        await waitUntil(() => typeof releaseCancellation === 'function');
        await h.qt.refresh(false);
        releaseCancellation();
        await assert.rejects(stale, /stale/);
        passed++;
        ok('类型 publication 失活后取消能力立即撤回', h.qt.canCancel(item) === false);
    }

    {
        const illust = typeDescriptor({type: 'illust'});
        const h = harness([manifest(1, [illust])], {
            '/modules/demo.js': {initializer: BASIC_INITIALIZER}
        });
        await h.qt.bootstrap();
        ok('illust 与其它类型一样必须显式携带顶层 cancelWorkKey',
            h.qt.canCancel({id: '123456', kind: 'illust'}) === false
            && h.qt.canCancel({id: '123456', kind: 'illust', cancelWorkKey: '123456'}) === true);
    }

    {
        const explicit = harness([manifest(1, [typeDescriptor()])], {
            '/modules/demo.js': {initializer: quickDataSourceInitializer(`{
                        id: '  demo-source  ',
                        displayNamespace: '  demo-source-i18n  ',
                        displayI18nKey: '  source.demo  ',
                        order: '7'
                    }`)}
        });
        await explicit.qt.bootstrap();
        const explicitSource = explicit.qt.acquisition('demo', 'quick').dataSource;
        ok('quick dataSource 元数据会去除空白并规范化排序值',
            explicitSource.id === 'demo-source'
            && explicitSource.displayNamespace === 'demo-source-i18n'
            && explicitSource.displayI18nKey === 'source.demo'
            && explicitSource.order === 7);
        ok('quick dataSource 经运行时投影后为只读冻结快照',
            Object.isFrozen(explicitSource));
        ok('quick dataSource 自有 i18n namespace 会加入运行时 namespace 集合',
            (await explicit.qt.i18nNamespaces()).includes('demo-source-i18n'));
        assert.throws(() => vm.runInContext(
            "'use strict'; window.PixivBatch.queueTypes.acquisition('demo', 'quick').dataSource.id = 'forged'",
            explicit.sandbox), /read only|Cannot assign/i);
        passed++;
        ok('冻结的 quick dataSource 拒绝改写后仍保留规范化 id',
            explicit.qt.acquisition('demo', 'quick').dataSource.id === 'demo-source');

        const series = harness([manifest(1, [typeDescriptor()])], {
            '/modules/demo.js': {initializer: acquisitionDataSourceInitializer('series', `{
                        id: 'series-source',
                        displayNamespace: 'series-source-i18n',
                        displayI18nKey: 'source.series',
                        order: 9
                    }`)}
        });
        await series.qt.bootstrap();
        const seriesSource = series.qt.acquisition('demo', 'series').dataSource;
        ok('series dataSource 复用通用取得元数据规范化与冻结边界',
            seriesSource.id === 'series-source'
            && seriesSource.displayNamespace === 'series-source-i18n'
            && seriesSource.displayI18nKey === 'source.series'
            && seriesSource.order === 9
            && Object.isFrozen(seriesSource));
        ok('series dataSource 自有 i18n namespace 会加入运行时 namespace 集合',
            (await series.qt.i18nNamespaces()).includes('series-source-i18n'));

        const singleImport = harness([manifest(1, [typeDescriptor({
            acquisitionModes: ['single-import']
        })])], {
            '/modules/demo.js': {initializer: importDataSourceInitializer(`{
                        id: '  import-source  ',
                        displayNamespace: '  import-source-i18n  ',
                        displayI18nKey: '  source.import  ',
                        order: '11'
                    }`)}
        });
        await singleImport.qt.bootstrap();
        const importSource = singleImport.qt.acquisition('demo', 'single-import').dataSource;
        ok('single-import dataSource 复用取得元数据规范化与冻结边界',
            importSource.id === 'import-source'
            && importSource.displayNamespace === 'import-source-i18n'
            && importSource.displayI18nKey === 'source.import'
            && importSource.order === 11
            && Object.isFrozen(importSource));
        ok('single-import dataSource 自有 i18n namespace 会加入运行时 namespace 集合',
            (await singleImport.qt.i18nNamespaces()).includes('import-source-i18n'));

        const browserHarness = harness([manifest(1, [typeDescriptor({acquisitionModes: ['series']})])], {
            '/modules/demo.js': {initializer: seriesBrowserInitializer(`{
                        initialCursor: 'folder-start',
                        pageSize: '12',
                        title: function () { return 'Folders'; },
                        loadingLabel: function () { return 'Loading folders'; },
                        emptyLabel: function () { return 'No folders'; },
                        buildPageRequest: function (context) {
                            return {endpoint: '/api/demo/folders', params: {cursor: context.cursor}};
                        },
                        readPage: function (data) {
                            return {items: data.folders, nextCursor: data.nextCursor, hasMore: data.hasMore};
                        },
                        itemId: function (item) { return item.id; },
                        itemLabel: function (item) { return item.title; },
                        select: function (item) {
                            return {seriesId: 'folder:' + item.id, seriesTitle: item.title};
                        }
                    }`)}
        });
        await browserHarness.qt.bootstrap();
        const browser = browserHarness.qt.acquisition('demo', 'series').browser;
        const folder = {id: 'folder-7', title: 'Travel'};
        const page = browser.readPage({folders: [folder], nextCursor: 'folder-next', hasMore: true});
        ok('series browser 仅暴露规范化、冻结的中性浏览钩子',
            Object.isFrozen(browser)
            && browser.initialCursor === 'folder-start'
            && browser.pageSize === 12
            && browser.title() === 'Folders'
            && browser.loadingLabel() === 'Loading folders'
            && browser.emptyLabel() === 'No folders');
        ok('series browser 保留分页读取、项目投影与选择钩子',
            browser.buildPageRequest({cursor: 'opaque-cursor'}).params.cursor === 'opaque-cursor'
            && page.items[0].id === 'folder-7'
            && page.nextCursor === 'folder-next'
            && page.hasMore === true
            && browser.itemId(folder) === 'folder-7'
            && browser.itemLabel(folder) === 'Travel'
            && browser.select(folder).seriesId === 'folder:folder-7');

        const invalidBrowser = harness([manifest(1, [typeDescriptor({acquisitionModes: ['series']})])], {
            '/modules/demo.js': {initializer: seriesBrowserInitializer(`{
                        buildPageRequest: function () { return {}; },
                        readPage: function () { return {items: []}; },
                        itemId: function (item) { return item.id; },
                        itemLabel: function (item) { return item.title; }
                    }`)}
        });
        await invalidBrowser.qt.bootstrap();
        ok('缺少必需 select 钩子的 series browser 不进入运行时投影',
            !Object.prototype.hasOwnProperty.call(
                invalidBrowser.qt.acquisition('demo', 'series'), 'browser'));

        const fallback = harness([manifest(1, [typeDescriptor({
            order: 27,
            displayNamespace: 'demo-manifest',
            displayI18nKey: 'type.demo'
        })])], {
            '/modules/demo.js': {initializer: quickDataSourceInitializer(`{
                        id: 'fallback-source'
                    }`)}
        });
        await fallback.qt.bootstrap();
        const fallbackSource = fallback.qt.acquisition('demo', 'quick').dataSource;
        ok('quick dataSource 缺省展示元数据时回退后端 manifest',
            fallbackSource.id === 'fallback-source'
            && fallbackSource.displayNamespace === 'demo-manifest'
            && fallbackSource.displayI18nKey === 'type.demo'
            && fallbackSource.order === 27);
        ok('计划队列优先采用类型显式贡献的数据来源而非其它模式的旧式类型回退',
            fallback.qt.dataSourceForType('demo', 'schedule').id === 'fallback-source');

        const blank = harness([manifest(1, [typeDescriptor()])], {
            '/modules/demo.js': {initializer: quickDataSourceInitializer(`{id: '   '}`)}
        });
        await blank.qt.bootstrap();
        ok('空白 quick dataSource id 会被运行时丢弃',
            !Object.prototype.hasOwnProperty.call(blank.qt.acquisition('demo', 'quick'), 'dataSource'));

        const oversizedId = 'x'.repeat(65);
        const oversized = harness([manifest(1, [typeDescriptor()])], {
            '/modules/demo.js': {initializer: quickDataSourceInitializer(`{id: '${oversizedId}'}`)}
        });
        await oversized.qt.bootstrap();
        ok('超过长度上限的 quick dataSource id 会被运行时丢弃',
            !Object.prototype.hasOwnProperty.call(oversized.qt.acquisition('demo', 'quick'), 'dataSource'));
        ok('非法 dataSource 元数据在来源聚合时按作品类型回退为独立来源',
            oversized.qt.dataSourcesForMode('quick').length === 1
            && oversized.qt.dataSourcesForMode('quick')[0].id === 'demo');
    }

    {
        const h = harness([manifest(1, [
            typeDescriptor({
                type: 'legacy', ownerPluginId: 'legacy-owner', packageId: 'legacy-package',
                publicationId: 1, order: 30, moduleUrl: '/modules/legacy-source.js',
                displayNamespace: 'legacy-i18n', displayI18nKey: 'type.legacy',
                iconKey: 'legacy-icon', colorToken: 'legacy-color'
            }),
            typeDescriptor({
                type: 'type-a', ownerPluginId: 'owner-a', packageId: 'package-a',
                publicationId: 2, order: 10, moduleUrl: '/modules/type-a-source.js',
                displayNamespace: 'type-a-i18n', displayI18nKey: 'type.a',
                iconKey: 'type-a-icon', colorToken: 'type-a-color'
            }),
            typeDescriptor({
                type: 'type-b', ownerPluginId: 'owner-b', packageId: 'package-b',
                publicationId: 3, order: 20, moduleUrl: '/modules/type-b-source.js',
                displayNamespace: 'type-b-i18n', displayI18nKey: 'type.b',
                iconKey: 'type-b-icon', colorToken: 'type-b-color'
            }),
            typeDescriptor({
                type: 'type-c', ownerPluginId: 'owner-c', packageId: 'package-c',
                publicationId: 4, order: 15, moduleUrl: '/modules/type-c-source.js',
                displayNamespace: 'type-c-i18n', displayI18nKey: 'type.c',
                iconKey: 'type-c-icon', colorToken: 'type-c-color'
            })
        ])], {
            '/modules/legacy-source.js': {initializer: BASIC_INITIALIZER},
            '/modules/type-a-source.js': {initializer: quickAndImportDataSourceInitializer(`{
                        id: 'source-a', displayNamespace: 'sources',
                        displayI18nKey: 'source.a', order: 10
                    }`)},
            '/modules/type-b-source.js': {initializer: quickAndImportDataSourceInitializer(`{
                        id: 'source-a', displayNamespace: 'sources',
                        displayI18nKey: 'source.a', order: 10
                    }`)},
            '/modules/type-c-source.js': {initializer: quickAndImportDataSourceInitializer(`{
                        id: 'source-b', displayNamespace: 'sources',
                        displayI18nKey: 'source.b', order: 5
                    }`)}
        });
        await h.qt.bootstrap();
        const sources = h.qt.dataSourcesForMode('quick');
        ok('dataSourcesForMode 按来源 order/id 聚合排序并为旧 descriptor 生成独立来源',
            sources.map(source => source.id).join(',') === 'source-b,source-a,legacy'
            && sources[2].displayNamespace === 'legacy-i18n'
            && sources[2].displayI18nKey === 'type.legacy'
            && sources[2].order === 30);
        const sourceA = sources.find(source => source.id === 'source-a');
        ok('同一来源的作品类型按 manifest order/type 排序且保留展示字段',
            sourceA.types.map(type => type.type).join(',') === 'type-a,type-b'
            && sourceA.types[0].displayNamespace === 'type-a-i18n'
            && sourceA.types[0].displayI18nKey === 'type.a'
            && sourceA.types[0].iconKey === 'type-a-icon'
            && sourceA.types[0].colorToken === 'type-a-color');
        ok('dataSourcesForMode 返回来源、类型及数组均深冻结的只读快照',
            Object.isFrozen(sources)
            && sources.every(source => Object.isFrozen(source) && Object.isFrozen(source.types))
            && sources.every(source => source.types.every(Object.isFrozen)));
        assert.throws(() => { sourceA.types[0].type = 'forged'; }, /read only|Cannot assign/i);
        passed++;
        const sourceATypes = h.qt.typesForDataSource('quick', 'source-a');
        ok('typesForDataSource 仅返回指定模式和来源的冻结类型集合',
            sourceATypes.map(type => type.type).join(',') === 'type-a,type-b'
            && Object.isFrozen(sourceATypes)
            && h.qt.typesForDataSource('quick', 'source-b').map(type => type.type).join(',') === 'type-c');
        const missingSourceTypes = h.qt.typesForDataSource('quick', 'missing');
        const unknownModeSources = h.qt.dataSourcesForMode('unknown');
        ok('未知来源或模式返回冻结空数组',
            missingSourceTypes.length === 0 && Object.isFrozen(missingSourceTypes)
            && unknownModeSources.length === 0 && Object.isFrozen(unknownModeSources));

        const singleImport = h.qt.dataSourcesForMode('single-import');
        ok('single-import 也从活动 import contributions 聚合只读支持来源',
            singleImport.map(source => source.id).join(',') === 'source-b,source-a,legacy'
            && singleImport.find(source => source.id === 'source-a').types.length === 2);
        const typeASource = h.qt.dataSourceForType('type-a', 'quick');
        const scheduledTypeCSource = h.qt.dataSourceForType('type-c', 'schedule');
        ok('dataSourceForType 优先解析指定取得模式并为计划模式确定性回退活动来源',
            typeASource.id === 'source-a' && typeASource.type === 'type-a'
            && scheduledTypeCSource.id === 'source-b' && scheduledTypeCSource.type === 'type-c'
            && Object.isFrozen(typeASource) && Object.isFrozen(scheduledTypeCSource));
        ok('dataSourceForType 对旧类型使用 manifest 展示元数据，缺席类型返回 null',
            h.qt.dataSourceForType('legacy', 'quick').id === 'legacy'
            && h.qt.dataSourceForType('missing', 'quick') === null);
    }

    {
        const h = harness([manifest(1, [typeDescriptor()])], {
            '/modules/demo.js': {initializer: queueTagsInitializer(`function (item) {
                testState.queueTagSnapshotFrozen = Object.isFrozen(item)
                    && Object.isFrozen(item.typeData);
                testState.queueTagSnapshotHasMessage = Object.prototype.hasOwnProperty.call(
                    item, 'lastMessage');
                try { item.typeData.origin = 'forged'; } catch (e) {}
                return [
                    {id: ' Media.Image ', label: ' Image '},
                    {id: 'media.image', label: 'duplicate'},
                    {id: 'bad id', label: 'invalid'},
                    {id: 'origin.collection', label: 'Collection'}
                ];
            }`)}
        });
        await h.qt.bootstrap();
        const sourceItem = {
            id: '7', kind: 'demo', typeData: {origin: 'collection'},
            lastMessage: 'large volatile progress message'
        };
        const tags = h.qt.queueTags(sourceItem);
        ok('queueTags 使用冻结快照并规范化稳定 id、标签文本及重复项',
            h.sandbox.testState.queueTagSnapshotFrozen === true
            && h.sandbox.testState.queueTagSnapshotHasMessage === false
            && tags.map(tag => tag.id).join(',') === 'media.image,origin.collection'
            && tags[0].label === 'Image' && sourceItem.typeData.origin === 'collection');
        ok('queueTags 返回深冻结的纯文本快照',
            Object.isFrozen(tags) && tags.every(Object.isFrozen));

        const failing = harness([manifest(1, [typeDescriptor()])], {
            '/modules/demo.js': {initializer: queueTagsInitializer(
                `function () { throw new Error('tag failure'); }`)}
        });
        await failing.qt.bootstrap();
        const fallbackTags = failing.qt.queueTags({kind: 'demo'});
        ok('queueTags 插件异常时隔离失败并返回冻结空数组',
            fallbackTags.length === 0 && Object.isFrozen(fallbackTags));

        const asyncFailing = harness([manifest(1, [typeDescriptor()])], {
            '/modules/demo.js': {initializer: queueTagsInitializer(
                `function () { return Promise.reject(new Error('async tag failure')); }`)}
        });
        await asyncFailing.qt.bootstrap();
        const asyncFallbackTags = asyncFailing.qt.queueTags({kind: 'demo'});
        await new Promise(resolve => setTimeout(resolve, 0));
        ok('queueTags 拒绝异步结果并吸收 rejected Promise',
            asyncFallbackTags.length === 0 && Object.isFrozen(asyncFallbackTags));

        const lifecycle = harness([
            manifest(1, [typeDescriptor()]),
            manifest(2, [])
        ], {
            '/modules/demo.js': {initializer: queueTagsInitializer(
                `function () { return [{id: 'media.demo', label: 'Demo'}]; }`)}
        });
        await lifecycle.qt.bootstrap();
        const activeTags = lifecycle.qt.queueTags({kind: 'demo'});
        await lifecycle.qt.refresh();
        ok('queueTags 在 publication 撤回后立即缺席',
            activeTags.length === 1 && lifecycle.qt.queueTags({kind: 'demo'}).length === 0);
    }

    {
        const maliciousInitializer = BASIC_INITIALIZER
            .replace(
                `scheduledQueueItem: function (item) {
                testState.scheduledOwnerCalls = (testState.scheduledOwnerCalls || 0) + 1;
                return {id: 'owned-' + item.id, ownerMapped: true};
            },`,
                `scheduledQueueItem: function () {
                return {
                    id: 'forged-id',
                    kind: 'forged-kind',
                    workId: 'forged-work-id',
                    workType: 'forged-work-type',
                    queueKey: 'forged-key',
                    liveStatus: {phase: 'FORGED'},
                    ownerMapped: true
                };
            },`)
            .replace(
                'scheduledSse: false,',
                `scheduledSse: false,
            queueLiveStatus: function (item) {
                testState.liveDispatchKind = item.kind;
                testState.liveDispatchPhase = item.liveStatus && item.liveStatus.phase;
                return {label: 'Owner', message: item.liveStatus.phase, tone: 'info'};
            },`);
        const h = harness([manifest(1, [typeDescriptor()])], {
            '/modules/demo.js': {initializer: maliciousInitializer}
        });
        await h.qt.bootstrap();
        const mapped = vm.runInContext(`window.PixivBatch.queueTypes.scheduledQueueItem(
            'demo',
            {workId: 'same-id', liveStatus: {phase: 'REAL'}},
            {}
        )`, h.sandbox);
        const status = h.qt.queueLiveStatus(Object.assign({}, mapped, {kind: 'forged-after-host'}));
        ok('恶意 owner 不能改写 kind/id/复合 key/raw liveStatus，解释器仍按 manifest workType 派发',
            mapped.id === 'same-id'
            && mapped.kind === 'demo'
            && mapped.workId === 'same-id'
            && mapped.workType === 'demo'
            && mapped.queueKey === h.qt.queueKey('demo', 'same-id')
            && mapped.liveStatus.phase === 'REAL'
            && mapped.ownerMapped === true
            && h.sandbox.testState.liveDispatchKind === 'demo'
            && h.sandbox.testState.liveDispatchPhase === 'REAL'
            && status && status.message === 'REAL');
    }

    {
        const h = harness([manifest(1, [typeDescriptor()])], {
            '/modules/demo.js': {initializer: queueLiveStatusInitializer(`function (item) {
                testState.liveStatusSnapshotFrozen = Object.isFrozen(item)
                    && Object.isFrozen(item.liveStatus)
                    && Object.isFrozen(item.liveStatus.details);
                testState.liveStatusSnapshotHasMessage = Object.prototype.hasOwnProperty.call(
                    item, 'lastMessage');
                try { item.liveStatus.phase = 'FORGED'; } catch (e) {}
                return {label: ' Status ', message: ' Working ', tone: ' SUCCESS '};
            }`)}
        });
        await h.qt.bootstrap();
        const sourceItem = {
            id: '8',
            kind: 'demo',
            liveStatus: {phase: 'RUNNING', details: {attempt: 1}},
            lastMessage: 'volatile host message'
        };
        const status = h.qt.queueLiveStatus(sourceItem);
        ok('queueLiveStatus 使用隔离的深冻结 raw 快照并规范化纯文本结果',
            h.sandbox.testState.liveStatusSnapshotFrozen === true
            && h.sandbox.testState.liveStatusSnapshotHasMessage === false
            && sourceItem.liveStatus.phase === 'RUNNING'
            && status.label === 'Status'
            && status.message === 'Working'
            && status.tone === 'success'
            && Object.isFrozen(status));

        const invalid = harness([manifest(1, [typeDescriptor()])], {
            '/modules/demo.js': {initializer: queueLiveStatusInitializer(`function (item) {
                if (item.liveStatus.phase === 'LABEL') {
                    return {label: 'x'.repeat(49), message: 'ok', tone: 'info'};
                }
                if (item.liveStatus.phase === 'MESSAGE') {
                    return {label: 'ok', message: 'x'.repeat(257), tone: 'info'};
                }
                return {label: 'ok', message: 'ok', tone: 'custom-css'};
            }`)}
        });
        await invalid.qt.bootstrap();
        ok('queueLiveStatus 拒绝超长文本与 tone 白名单外结果',
            invalid.qt.queueLiveStatus({kind: 'demo', liveStatus: {phase: 'LABEL'}}) === null
            && invalid.qt.queueLiveStatus({kind: 'demo', liveStatus: {phase: 'MESSAGE'}}) === null
            && invalid.qt.queueLiveStatus({kind: 'demo', liveStatus: {phase: 'TONE'}}) === null);

        const failing = harness([manifest(1, [typeDescriptor()])], {
            '/modules/demo.js': {initializer: queueLiveStatusInitializer(
                `function () { throw new Error('live status failure'); }`)}
        });
        await failing.qt.bootstrap();
        ok('queueLiveStatus 隔离 owner hook 异常并安全降级',
            failing.qt.queueLiveStatus({kind: 'demo', liveStatus: {phase: 'RUNNING'}}) === null);

        const asyncFailing = harness([manifest(1, [typeDescriptor()])], {
            '/modules/demo.js': {initializer: queueLiveStatusInitializer(
                `function () { return Promise.reject(new Error('async live status failure')); }`)}
        });
        await asyncFailing.qt.bootstrap();
        const asyncFallback = asyncFailing.qt.queueLiveStatus({
            kind: 'demo', liveStatus: {phase: 'RUNNING'}
        });
        await new Promise(resolve => setTimeout(resolve, 0));
        ok('queueLiveStatus 拒绝异步结果并吸收 rejected Promise', asyncFallback === null);

        const lifecycle = harness([
            manifest(1, [typeDescriptor()]),
            manifest(2, [])
        ], {
            '/modules/demo.js': {initializer: queueLiveStatusInitializer(
                `function () { return {label: 'Demo', message: 'Running', tone: 'info'}; }`)}
        });
        await lifecycle.qt.bootstrap();
        const oldBehavior = lifecycle.qt.descriptor('demo');
        const activeStatus = lifecycle.qt.queueLiveStatus({
            kind: 'demo', liveStatus: {phase: 'RUNNING'}
        });
        await lifecycle.qt.refresh();
        assert.throws(() => oldBehavior.queueLiveStatus({liveStatus: {phase: 'RUNNING'}}), /stale/);
        passed++;
        ok('queueLiveStatus 随 publication 撤回且旧 hook 不能越代继续解释状态',
            activeStatus.message === 'Running'
            && lifecycle.qt.queueLiveStatus({kind: 'demo', liveStatus: {phase: 'RUNNING'}}) === null);
    }

    {
        const h = harness([manifest(1, [
            typeDescriptor({
                type: 'third-party', ownerPluginId: 'third-owner', packageId: 'third-package',
                publicationId: 2, order: 1, moduleUrl: '/modules/third.js',
                acquisitionModes: ['user']
            }),
            typeDescriptor({
                type: 'illust', ownerPluginId: 'illust-owner', packageId: 'illust-package',
                publicationId: 3, order: 10, moduleUrl: '/modules/illust.js',
                acquisitionModes: ['user']
            })
        ])], {
            '/modules/third.js': {initializer: BASIC_INITIALIZER},
            '/modules/illust.js': {initializer: REQUEST_OWNER_INITIALIZER}
        });
        await h.qt.bootstrap();
        ok('user=request 由 accepts owner 解析而非回退最低 order 类型',
            h.qt.resolveSelectionForMode('request', 'user') === 'illust');

        h.sandbox.state = {
            mode: 'user',
            settings: {userKind: 'request', searchKind: 'illust'}
        };
        h.sandbox.seriesState = {kind: 'illust'};
        vm.runInContext(SETTINGS_SOURCE, h.sandbox, {filename: 'batch-settings.js'});
        ok('计划来源上下文沿用 owner 解析后的 request 作品类型',
            vm.runInContext('currentModeKind()', h.sandbox) === 'illust');
    }

    {
        const h = harness([manifest(1, [
            typeDescriptor(),
            typeDescriptor({
                type: 'broken', ownerPluginId: 'broken-owner', packageId: 'broken-package',
                publicationId: 2, order: 20, moduleUrl: '/modules/broken.js'
            })
        ])], {
            '/modules/demo.js': {initializer: BASIC_INITIALIZER},
            '/modules/broken.js': {initializer: FAILING_INITIALIZER}
        });
        await h.qt.bootstrap();
        ok('失败 initializer 登记的 cleanup 会立即执行', h.sandbox.testState.failedCleanup === 1);
        ok('失败模块的 context 会立即失效', h.sandbox.testState.failedContext.isActive() === false);
        ok('失败模块不会注册为可用类型', !h.qt.has('broken'));
        ok('失败模块清理不影响同 publication 的成功模块', h.qt.has('demo')
            && h.sandbox.testState.disposed.length === 0);
    }

    console.log(`batch-queue-types.test.js: ${passed} assertions passed ✓`);
})().catch(error => {
    console.error('TEST FAILED:', error && error.stack ? error.stack : error);
    process.exit(1);
});
