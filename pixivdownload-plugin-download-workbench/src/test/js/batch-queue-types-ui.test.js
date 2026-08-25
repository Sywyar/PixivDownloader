'use strict';
/* 下载类型 UiSlot 渲染、失败隔离与刷新收敛契约。 */
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
        const h = harness([manifest(1, [typeDescriptor({uiSlots: ['cookie-tools']})])], {
            '/modules/demo.js': {initializer: BASIC_INITIALIZER}
        }, {slotTarget: 'cookie-tools'});
        await h.qt.bootstrap();
        const hosts = h.slotParent.children.filter(node => node.getAttribute('data-vue-slot') === 'cookie-tools');
        ok('类型 descriptor 自报槽位但顶层 manifest 未发布时不渲染',
            hosts.length === 0 || hosts.every(host => host.children.length === 0));
    }

    {
        const legacyUiSlotsInitializer = `(function () {
            return {descriptor: {
                process: function () {},
                uiSlots: {'cookie-tools': '<span data-legacy-ui-slot></span>'}
            }};
        })`;
        const h = harness([manifest(1, [typeDescriptor()], 'epoch-a', [
            uiSlotDescriptor({
                slotId: 'demo.cookie', target: 'cookie-tools', moduleUrl: '/modules/demo.js',
                owner: {pluginId: 'demo-owner', packageId: 'demo-package', generation: 1, publicationId: 1}
            })
        ])], {
            '/modules/demo.js': {initializer: legacyUiSlotsInitializer}
        }, {slotTarget: 'cookie-tools'});
        await h.qt.bootstrap();
        const host = h.slotParent.children.find(node => node.getAttribute('data-vue-slot') === 'cookie-tools');
        const behavior = h.qt.descriptor('demo');
        ok('模块返回的旧 uiSlots capability bag 即使后端发布槽位也不会渲染',
            (!host || host.children.length === 0)
            && Object.keys(behavior.slots).length === 0
            && !Object.prototype.hasOwnProperty.call(behavior, 'uiSlots'));
    }

    {
        const badSlotInitializer = `(function () {
            return {descriptor: {
                process: function () {},
                slots: {'cookie-tools': function () { throw new Error('broken slot'); }}
            }};
        })`;
        const goodType = typeDescriptor();
        const badType = typeDescriptor({
            type: 'bad-slot', ownerPluginId: 'bad-slot-owner', packageId: 'bad-slot-package',
            publicationId: 2, moduleUrl: '/modules/bad-slot.js', acquisitionModes: []
        });
        const h = harness([manifest(1, [goodType, badType], 'epoch-a', [
            uiSlotDescriptor({
                slotId: 'demo.cookie', target: 'cookie-tools', moduleUrl: '/modules/demo.js',
                owner: {pluginId: 'demo-owner', packageId: 'demo-package', generation: 1, publicationId: 1}
            }),
            uiSlotDescriptor({
                slotId: 'bad.cookie', target: 'cookie-tools', moduleUrl: '/modules/bad-slot.js',
                owner: {pluginId: 'bad-slot-owner', packageId: 'bad-slot-package', generation: 1, publicationId: 2}
            })
        ])], {
            '/modules/demo.js': {initializer: BASIC_INITIALIZER},
            '/modules/bad-slot.js': {initializer: badSlotInitializer}
        }, {slotTarget: 'cookie-tools'});
        await h.qt.bootstrap();
        const host = h.slotParent.children.find(node => node.getAttribute('data-vue-slot') === 'cookie-tools');
        ok('单个槽位 hook 抛错只隔离自身，同 target 健康贡献仍渲染',
            !!host && host.children.length === 1
            && host.children[0].html.includes('data-slot-generation'));
    }

    {
        const badInitializer = `(function () {
            return {descriptor: {
                process: function () {},
                acquisition: {search: {
                    buildRequest: function () { return {endpoint: '/api/bad'}; }
                }}
            }};
        })`;
        const h = harness([manifest(1, [
            typeDescriptor(),
            typeDescriptor({
                type: 'bad', ownerPluginId: 'bad-owner', packageId: 'bad-package',
                publicationId: 2, moduleUrl: '/modules/bad.js', acquisitionModes: ['search']
            })
        ])], {
            '/modules/demo.js': {initializer: BASIC_INITIALIZER},
            '/modules/bad.js': {initializer: badInitializer}
        });
        await h.qt.bootstrap();
        ok('不完整 mode hook 只隔离该 mode，不阻断同 publication 健康类型',
            h.qt.has('bad') && !h.qt.supports('bad', 'search') && h.qt.supports('demo', 'search'));
    }

    {
        const contributionInitializer = `(function () {
            return {descriptor: {
                process: function () {},
                filters: {
                    'allowed-filter': {
                        extraSelector: '.allowed',
                        type: 'forged',
                        contributionKey: 'forged',
                        matchExtra: function (item, filters) {
                            return Number(item.wordCount) >= Number(filters.wordsMin);
                        },
                        bookmarkCountFetch: function (id) {
                            return {bookmarkCount: Number(id)};
                        },
                        collision: 'first'
                    },
                    'conflicting-filter': {collision: 'second', uniqueFlag: true},
                    evil: {extraSelector: '.evil'}
                },
                settings: {
                    'allowed-setting': {cardId: 'allowed-card', type: 'forged'},
                    evil: {cardId: 'evil-card'}
                }
            }};
        })`;
        const h = harness([manifest(1, [typeDescriptor({
            acquisitionModes: [],
            filters: ['allowed-filter', 'conflicting-filter'],
            settings: ['allowed-setting']
        })])], {'/modules/demo.js': {initializer: contributionInitializer}});
        await h.qt.bootstrap();
        const filter = h.qt.filtersFor('demo');
        const setting = h.qt.settingsFor('demo');
        const filterList = h.qt.contributionsOf('filters');
        const settingList = h.qt.contributionsOf('settings');
        ok('filters/settings 仅暴露 backend 精确声明的 key 且 runtime 盖章身份',
            filter.extraSelector === '.allowed' && setting.cardId === 'allowed-card'
            && filterList.length === 2 && filterList[0].type === 'demo'
            && filterList[0].contributionKey === 'allowed-filter'
            && filterList[1].type === 'demo'
            && filterList[1].contributionKey === 'conflicting-filter'
            && settingList.length === 1 && settingList[0].type === 'demo'
            && settingList[0].contributionKey === 'allowed-setting'
            && !filterList.some(value => value.extraSelector === '.evil')
            && !settingList.some(value => value.cardId === 'evil-card'));
        ok('嵌套 filter 投影保留真实行为钩子与无冲突字段',
            filter.matchExtra({wordCount: 1200}, {wordsMin: 1000})
            && !filter.matchExtra({wordCount: 500}, {wordsMin: 1000})
            && filter.bookmarkCountFetch('7').bookmarkCount === 7
            && filter.uniqueFlag === true);
        ok('多个已声明 filter group 的同名字段冲突时 fail closed',
            !Object.prototype.hasOwnProperty.call(filter, 'collision'));
    }

    {
        const hanging = typeDescriptor({
            type: 'hanging', ownerPluginId: 'hanging-owner', packageId: 'hanging-package',
            publicationId: 3, moduleUrl: '/modules/hanging.js'
        });
        const h = harness([manifest(1, [typeDescriptor(), hanging])], {
            '/modules/demo.js': {initializer: BASIC_INITIALIZER},
            '/modules/hanging.js': {never: true}
        }, {fakeTimers: true});
        const bootstrapping = h.qt.bootstrap();
        await waitUntil(() => h.loads.length === 2 && h.sandbox.testState.contexts.length === 1);
        h.advanceTimers(5000);
        await bootstrapping;
        ok('模块 script 网络永不回调时 5s 释放 bootstrap 且健康类型继续发布',
            h.qt.has('demo') && !h.qt.has('hanging'));
    }

    {
        let releaseFirst;
        const first = new Promise(resolve => { releaseFirst = resolve; });
        const next = manifest(2, [typeDescriptor({pluginGeneration: 2, publicationId: 22})]);
        const h = harness([first, next], {
            '/modules/demo.js': {initializer: BASIC_INITIALIZER}
        });
        const firstRefresh = h.qt.refresh(false);
        const queuedRefresh = h.qt.refresh(false);
        releaseFirst(manifest(1, [typeDescriptor({pluginGeneration: 1, publicationId: 11})]));
        await Promise.all([firstRefresh, queuedRefresh]);
        ok('refresh 安装期的新通知会自动补拉而不被吞掉',
            h.requests.length === 2 && h.qt.descriptor('demo').process() === 'v2');
    }

    {
        const h = harness([
            manifest(1, [typeDescriptor({pluginGeneration: 1, publicationId: 11})]),
            manifest(2, [typeDescriptor({pluginGeneration: 2, publicationId: 22})])
        ], {'/modules/demo.js': {initializer: BASIC_INITIALIZER}});
        await h.qt.bootstrap();
        const writes = [];
        const releases = [];
        const requestSignals = [];
        const pending = [
            ['user', '/api/demo/user'],
            ['search', '/api/demo/search'],
            ['search', '/api/demo/search/range'],
            ['series', '/api/demo/series'],
            ['quick', '/api/demo/quick']
        ].map(([mode, endpoint], index) => {
            const request = h.qt.prepareAcquisitionRequest('demo', mode, endpoint, 'delayed-' + index, {});
            requestSignals.push(request.signal);
            const delayed = new Promise(resolve => { releases.push(resolve); });
            return delayed.then(value => {
                request.assertCurrent();
                writes.push(value);
            });
        });
        await h.qt.refresh(false);
        ok('publication A 的所有 host request signal 在 B 发布时已 abort',
            requestSignals.every(signal => signal.aborted));
        releases.forEach((resolve, index) => resolve('late-' + index));
        const results = await Promise.allSettled(pending);
        ok('真实延迟 A→B 下 user/search/range/series/quick 旧响应全部在写入前被拒绝',
            writes.length === 0 && results.every(result => result.status === 'rejected'));
    }

    ok('页面 focus 会主动刷新 queue type manifest',
        /addEventListener\('focus',[\s\S]*?refreshQueueTypeManifest\(\)/.test(INIT_SOURCE));
    ok('页面恢复可见会主动刷新 queue type manifest',
        /visibilityState === 'visible'[\s\S]*?refreshQueueTypeManifest\(\)/.test(INIT_SOURCE));
    const reconcileStart = INIT_SOURCE.indexOf('function reconcileQueueTypeUi');
    const reconcileEnd = INIT_SOURCE.indexOf("window.addEventListener('focus'", reconcileStart);
    const reconcileSource = INIT_SOURCE.slice(reconcileStart, reconcileEnd);
    ok('queue type ready 事件只触发 UI 重算而不反向 refresh',
        /addEventListener\('pixivbatch:queuetypeschanged', reconcileQueueTypeUi\)/.test(INIT_SOURCE)
        && reconcileStart >= 0 && reconcileEnd > reconcileStart
        && !reconcileSource.includes('refreshQueueTypeManifest('));
    {
        // settings-card 槽位：类型 typed settings 声明的 cardId 已被宿主原生渲染时不再注入片段。
        const settingsCardInitializer = `(function () {
            return {descriptor: {
                process: function () {},
                settings: {
                    'allowed-setting': {cardId: 'native-settings-card', type: 'forged'},
                    other: {cardId: 'other-card'}
                },
                slots: {'settings-card': '<section class="plugin-card" data-settings-card="1"></section>'}
            }};
        })`;
        const settingsCardSlot = () => uiSlotDescriptor({
            slotId: 'demo.settings', target: 'settings-card', moduleUrl: '/modules/demo.js',
            owner: {pluginId: 'demo-owner', packageId: 'demo-package', generation: 1, publicationId: 1}
        });
        const noNative = harness([manifest(1, [typeDescriptor({
            settings: ['allowed-setting', 'other']
        })], 'epoch-a', [settingsCardSlot()])], {
            '/modules/demo.js': {initializer: settingsCardInitializer}
        }, {slotTarget: 'settings-card'});
        await noNative.qt.bootstrap();
        const injectedHost = noNative.slotParent.children.find(
            node => node.getAttribute('data-vue-slot') === 'settings-card');
        ok('无原生同 cardId 卡片时 settings-card 片段照常注入',
            !!injectedHost && injectedHost.children.length === 1
            && /data-settings-card/.test(injectedHost.children[0].html));

        const nativeCard = new El('div');
        nativeCard.setAttribute('id', 'native-settings-card');
        const withNative = harness([manifest(1, [typeDescriptor({
            settings: ['allowed-setting', 'other']
        })], 'epoch-a', [settingsCardSlot()])], {
            '/modules/demo.js': {initializer: settingsCardInitializer}
        }, {slotTarget: 'settings-card'});
        withNative.sandbox.document.body.appendChild(nativeCard);
        await withNative.qt.bootstrap();
        const skippedHost = withNative.slotParent.children.find(
            node => node.getAttribute('data-vue-slot') === 'settings-card');
        ok('typed settings 的 cardId 已原生在场时 settings-card 片段不再注入（避免双份设置卡）',
            (!skippedHost || skippedHost.children.length === 0)
            && withNative.slotMarker.parentNode === withNative.slotParent);
    }

    {
        // renderSlots 导出为幂等可重入的槽位重渲染入口（alt 布局动态重建视图后重挂用）。
        const h = harness([manifest(1, [typeDescriptor()])], {
            '/modules/demo.js': {initializer: BASIC_INITIALIZER}
        }, {slotTarget: 'cookie-tools'});
        await h.qt.bootstrap();
        ok('queueTypes 门面导出幂等 renderSlots', typeof h.qt.renderSlots === 'function');
        const renderAgain = h.qt.renderSlots();
        ok('renderSlots 返回 Promise 且重入不抛', renderAgain && typeof renderAgain.catch === 'function');
        await renderAgain;
        ok('renderSlots 重入后锚点与模板仍在', h.slotMarker.parentNode === h.slotParent);
    }

    console.log(`batch-queue-types-ui.test.js: ${passed} assertions passed ✓`);
})().catch(error => {
    console.error('TEST FAILED:', error && error.stack ? error.stack : error);
    process.exit(1);
});
