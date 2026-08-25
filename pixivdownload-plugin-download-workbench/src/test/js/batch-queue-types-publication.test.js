'use strict';
/* 下载类型与 UiSlot publication 生命周期、换代和失活契约。 */
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
            manifest(1, [], 'process-a', [uiSlotDescriptor()]),
            manifest(2, [], 'process-a'),
            manifest(3, [], 'process-a', [uiSlotDescriptor({
                owner: {pluginId: 'ai', packageId: 'ai-package', generation: 2, publicationId: 20}
            })])
        ], {'/modules/ui-slot.js': {initializer: UI_INITIALIZER, ui: true}});
        await h.qt.bootstrap();
        const firstContext = h.sandbox.testState.uiContexts[0];
        ok('uiSlot initializer 获得后端嵌套 owner 与 epoch', firstContext.epoch === 'process-a'
            && firstContext.owner.pluginId === 'ai' && firstContext.owner.publicationId === 10);
        ok('uiSlot A 只登记一个 listener', h.listenerCount('ui-probe') === 1);
        h.sandbox.window.dispatchEvent({type: 'ui-probe'});
        ok('uiSlot A listener 生效', h.sandbox.testState.uiEvents === 1);
        await h.qt.refresh();
        ok('uiSlot A unload 立即 abort 并 cleanup listener', firstContext.signal.aborted
            && h.sandbox.testState.uiCleanups === 1 && h.listenerCount('ui-probe') === 0);
        h.sandbox.window.dispatchEvent({type: 'ui-probe'});
        ok('uiSlot unload 后旧 listener 不再响应', h.sandbox.testState.uiEvents === 1);
        await h.qt.refresh();
        ok('uiSlot B reload 只恢复一个新 listener', h.listenerCount('ui-probe') === 1
            && h.sandbox.testState.uiContexts.length === 2);
        h.sandbox.window.dispatchEvent({type: 'ui-probe'});
        ok('uiSlot B 不与旧 listener 重复响应', h.sandbox.testState.uiEvents === 2);
        ok('uiSlot publication 切换使用不同 cachebuster', h.loads.length === 2
            && h.loads[0] !== h.loads[1]);
    }

    {
        const demo = typeDescriptor({
            acquisitionModes: ['quick'], i18nNamespace: 'demo-i18n'
        });
        const foreign = typeDescriptor({
            type: 'foreign', ownerPluginId: 'foreign-owner', packageId: 'foreign-package',
            publicationId: 2, order: 5, moduleUrl: '/modules/foreign.js',
            acquisitionModes: ['quick'], i18nNamespace: 'foreign-i18n'
        });
        const demoUi = uiSlotDescriptor({
            slotId: 'demo.settings', moduleUrl: '/modules/ui-slot.js',
            owner: {pluginId: 'demo-owner', packageId: 'demo-package', generation: 1, publicationId: 1}
        });
        const h = harness([
            manifest(1, [demo, foreign], 'ui-contract', [demoUi]),
            manifest(2, [], 'ui-contract')
        ], {
            '/modules/demo.js': {initializer: quickActionInitializer('demo-featured', 'collision')},
            '/modules/foreign.js': {initializer: quickActionInitializer('foreign-featured', 'collision')},
            '/modules/ui-slot.js': {initializer: UI_INITIALIZER, ui: true}
        });
        const dispatched = [];
        h.sandbox.window.PixivBatch.modes = {quick: {
            quickLoad(action) { dispatched.push(action); return 'sent:' + action; }
        }};
        await h.qt.bootstrap();
        const context = h.sandbox.testState.uiContexts[0];
        ok('UI context supports 只暴露同 owner 的活动取得能力',
            context.supports('demo', 'quick') === true
            && context.supports('foreign', 'quick') === false
            && context.supports('demo', 'search') === false);
        ok('UI context 只派发同 owner 已声明的 quick action',
            context.dispatchQuickAction('demo-featured') === 'sent:demo-featured'
            && context.dispatchQuickAction('foreign-featured') === false
            && context.dispatchQuickAction('collision') === false
            && context.dispatchQuickAction('missing') === false
            && dispatched.join(',') === 'demo-featured');
        await h.qt.refresh();
        ok('UI publication 失效后 supports 立即关闭', context.supports('demo', 'quick') === false);
        assert.throws(() => context.dispatchQuickAction('demo-featured'), /stale/);
        passed++;
    }

    {
        const h = harness([manifest(1, [
            typeDescriptor(),
            typeDescriptor({
                type: 'pending', ownerPluginId: 'pending-owner', packageId: 'pending-package',
                publicationId: 3, order: 30, moduleUrl: '/modules/pending.js'
            })
        ])], {
            '/modules/demo.js': {initializer: BASIC_INITIALIZER},
            '/modules/pending.js': {initializer: PENDING_INITIALIZER}
        }, {fakeTimers: true});
        const bootstrapping = h.qt.bootstrap();
        await waitUntil(() => !!h.sandbox.testState.pendingContext);
        h.advanceTimers(5000);
        await bootstrapping;
        ok('pending initializer 超时会立即 abort 并 cleanup', h.sandbox.testState.pendingContext.signal.aborted
            && h.sandbox.testState.pendingCleanup === 1);
        ok('pending 类型被隔离而健康类型继续发布', !h.qt.has('pending') && h.qt.has('demo'));
        ok('pending 清理不影响健康类型 activation', h.sandbox.testState.contexts[0].isActive()
            && h.sandbox.testState.disposed.length === 0);
    }

    {
        const h = harness([manifest(1, [typeDescriptor({
            type: 'late', ownerPluginId: 'late-owner', packageId: 'late-package',
            publicationId: 4, moduleUrl: '/modules/late.js'
        })])], {'/modules/late.js': {initializer: LATE_QUEUE_INITIALIZER}}, {fakeTimers: true});
        const bootstrapping = h.qt.bootstrap();
        await waitUntil(() => !!h.sandbox.testState.lateQueueContext);
        h.advanceTimers(5000);
        await bootstrapping;
        ok('queue initializer 超时后 scope 已 abort 且类型缺席',
            h.sandbox.testState.lateQueueContext.signal.aborted && !h.qt.has('late'));
        h.sandbox.testState.resolveLateQueue();
        await Promise.resolve();
        await Promise.resolve();
        ok('queue late onCleanup 会立即执行 callback 后拒绝',
            h.sandbox.testState.lateQueueCleanup === 1
            && h.sandbox.testState.lateQueueCleanupRejected === 1);
        ok('queue late 返回 disposer 会立即执行且不会复活类型',
            h.sandbox.testState.lateQueueDispose === 1 && !h.qt.has('late'));
    }

    {
        const h = harness([
            manifest(1, [], 'process-a', [uiSlotDescriptor({moduleUrl: '/modules/late-ui.js'})])
        ], {'/modules/late-ui.js': {initializer: LATE_UI_INITIALIZER, ui: true}}, {fakeTimers: true});
        const bootstrapping = h.qt.bootstrap();
        await waitUntil(() => !!h.sandbox.testState.lateUiContext);
        h.advanceTimers(5000);
        await bootstrapping;
        ok('ui initializer 超时后 scope 已 abort', h.sandbox.testState.lateUiContext.signal.aborted);
        h.sandbox.testState.resolveLateUi();
        await Promise.resolve();
        await Promise.resolve();
        ok('ui late onCleanup 会立即执行 callback 后拒绝',
            h.sandbox.testState.lateUiCleanup === 1
            && h.sandbox.testState.lateUiCleanupRejected === 1);
        ok('ui late 返回 disposer 会立即执行', h.sandbox.testState.lateUiDispose === 1);
    }

    {
        const invalidEpoch = manifest(1, [typeDescriptor()]);
        invalidEpoch.epoch = '   ';
        const h = harness([invalidEpoch], {'/modules/demo.js': {initializer: BASIC_INITIALIZER}});
        await h.qt.bootstrap();
        ok('空 epoch 的 manifest 被拒绝', !h.qt.has('demo') && h.loads.length === 0);
    }

    {
        const flatOwner = typeDescriptor();
        const owner = flatOwner.owner;
        delete flatOwner.owner;
        flatOwner.ownerPluginId = owner.pluginId;
        flatOwner.packageId = owner.packageId;
        flatOwner.pluginGeneration = owner.generation;
        flatOwner.publicationId = owner.publicationId;
        const h = harness([manifest(1, [flatOwner])], {
            '/modules/demo.js': {initializer: BASIC_INITIALIZER}
        });
        await h.qt.bootstrap();
        ok('下载类型拒绝旧 flattened owner 字段，只接受后端嵌套 owner',
            !h.qt.has('demo') && h.loads.length === 0);
    }

    {
        const h = harness([
            manifest(1, [typeDescriptor({
                pluginGeneration: 1, publicationId: 11
            })], 'epoch-a', [uiSlotDescriptor({
                slotId: 'demo.cookie', target: 'cookie-tools', moduleUrl: '/modules/demo.js',
                owner: {pluginId: 'demo-owner', packageId: 'demo-package', generation: 1, publicationId: 11}
            })]),
            manifest(2, []),
            manifest(3, [typeDescriptor({
                pluginGeneration: 2, publicationId: 22
            })], 'epoch-a', [uiSlotDescriptor({
                slotId: 'demo.cookie', target: 'cookie-tools', moduleUrl: '/modules/demo.js',
                owner: {pluginId: 'demo-owner', packageId: 'demo-package', generation: 2, publicationId: 22}
            })])
        ], {'/modules/demo.js': {initializer: BASIC_INITIALIZER}}, {
            slotTarget: 'cookie-tools', pixivVue: true
        });
        await h.qt.bootstrap();
        const hostsAfterA = h.slotParent.children.filter(node => node.getAttribute('data-vue-slot') === 'cookie-tools');
        const stableHost = hostsAfterA[0];
        ok('A 激活后槽位在稳定 host 内渲染一次', hostsAfterA.length === 1
            && stableHost.children.length === 1 && stableHost.children[0].html.includes('generation="1"'));
        ok('首次渲染保留 template 锚点', h.slotMarker.parentNode === h.slotParent);
        await h.qt.refresh();
        ok('A 到 unload 会清空旧槽位 DOM', stableHost.children.length === 0);
        ok('A 到 unload 会先卸载旧 Vue app', h.vueRecord.unmounts === 1);
        ok('unload 后稳定 host 与 template 均保留', stableHost.parentNode === h.slotParent
            && h.slotMarker.parentNode === h.slotParent);
        await h.qt.refresh();
        const hostsAfterB = h.slotParent.children.filter(node => node.getAttribute('data-vue-slot') === 'cookie-tools');
        ok('unload 到 B 在同一 host 恢复且不重复', hostsAfterB.length === 1
            && hostsAfterB[0] === stableHost && stableHost.children.length === 1
            && stableHost.children[0].html.includes('generation="2"') && h.vueRecord.mounts === 2);
    }

    {
        const h = harness([manifest(1, [typeDescriptor()], 'epoch-a', [uiSlotDescriptor({
            slotId: 'demo.cookie', target: 'cookie-tools', moduleUrl: '/modules/demo.js',
            owner: {pluginId: 'demo-owner', packageId: 'demo-package', generation: 1, publicationId: 1}
        })])], {'/modules/demo.js': {initializer: BASIC_INITIALIZER}}, {
            slotTarget: 'cookie-tools', slotCount: 2, pixivVue: true, mountFailAt: 2
        });
        await h.qt.bootstrap();
        const hosts = h.slotParent.querySelectorAll('[data-vue-slot]');
        ok('部分 Vue 挂载失败会先卸载成功 app，再对全部物理锚点使用命令式回退',
            h.vueRecord.mounts === 2 && h.vueRecord.unmounts === 1 && hosts.length === 2
            && hosts.every(host => host.children.length === 1
                && host.children[0].html.includes('data-slot-generation')));
    }

    {
        const target = 'cookie-tools\"]:not(*)';
        const initializer = BASIC_INITIALIZER.replace("'cookie-tools'", JSON.stringify(target));
        const h = harness([manifest(1, [typeDescriptor()], 'epoch-a', [uiSlotDescriptor({
            slotId: 'demo.hostile-target', target, moduleUrl: '/modules/demo.js',
            owner: {pluginId: 'demo-owner', packageId: 'demo-package', generation: 1, publicationId: 1}
        })])], {'/modules/demo.js': {initializer}}, {slotTarget: target, pixivVue: true});
        await h.qt.bootstrap();
        const host = h.slotParent.children.find(node => node.getAttribute('data-vue-slot') === target);
        ok('包含 CSS 元字符的 target 仍按属性精确值挂载，不进入选择器解释',
            !!host && host.children.length === 1 && host.children[0].html.includes('data-slot-generation'));
    }

    {
        const imperativeInitializer = `(function () {
            return {descriptor: {
                process: function () {},
                slots: {'cookie-tools': function () {
                    return {mount: function (host) {
                        testState.imperativeMounts = (testState.imperativeMounts || 0) + 1;
                        host.insertAdjacentHTML('beforeend', '<span data-imperative-slot></span>');
                        return function () {
                            testState.imperativeCleanups = (testState.imperativeCleanups || 0) + 1;
                        };
                    }};
                }}
            }};
        })`;
        const h = harness([
            manifest(1, [typeDescriptor()], 'epoch-a', [uiSlotDescriptor({
                slotId: 'demo.cookie', target: 'cookie-tools', moduleUrl: '/modules/demo.js',
                owner: {pluginId: 'demo-owner', packageId: 'demo-package', generation: 1, publicationId: 1}
            })]),
            manifest(2, [])
        ], {'/modules/demo.js': {initializer: imperativeInitializer}}, {slotTarget: 'cookie-tools', pixivVue: true});
        await h.qt.bootstrap();
        const host = h.slotParent.children.find(node => node.getAttribute('data-vue-slot') === 'cookie-tools');
        ok('命令式槽位贡献挂载到稳定宿主', h.sandbox.testState.imperativeMounts === 1
            && host.children.length === 1 && /data-imperative-slot/.test(host.children[0].html));
        await h.qt.refresh();
        ok('publication 失效会执行命令式 cleanup 并清空稳定宿主',
            h.sandbox.testState.imperativeCleanups === 1 && host.children.length === 0);
    }

    {
        const h = harness([
            manifest(1, [typeDescriptor({
                pluginGeneration: 1, publicationId: 11
            })], 'epoch-a', [uiSlotDescriptor({
                slotId: 'demo.cookie', target: 'cookie-tools', moduleUrl: '/modules/demo.js',
                owner: {pluginId: 'demo-owner', packageId: 'demo-package', generation: 1, publicationId: 11}
            })]),
            manifest(2, [typeDescriptor({
                pluginGeneration: 2, publicationId: 22
            })], 'epoch-a', [uiSlotDescriptor({
                slotId: 'demo.cookie', target: 'cookie-tools', moduleUrl: '/modules/demo.js',
                owner: {pluginId: 'demo-owner', packageId: 'demo-package', generation: 2, publicationId: 22}
            })])
        ], {'/modules/demo.js': {initializer: BASIC_INITIALIZER}}, {
            slotTarget: 'cookie-tools', pixivVue: true, deferVueMount: true
        });
        let firstBootstrapResolved = false;
        const firstBootstrap = h.qt.bootstrap().then(value => {
            firstBootstrapResolved = true;
            return value;
        });
        await waitUntil(() => h.vueRecord.pendingMounts.length === 1);
        await Promise.resolve();
        ok('bootstrap 会等待槽位 Vue 挂载完成后才发布', firstBootstrapResolved === false);
        const stableHost = h.slotParent.children.find(
            node => node.getAttribute('data-vue-slot') === 'cookie-tools');
        h.qt.dispose();
        const reloadBootstrap = h.qt.bootstrap();
        h.vueRecord.releaseNextMount();
        await waitUntil(() => h.vueRecord.mounts === 2 && h.vueRecord.pendingMounts.length === 1);
        ok('dispose 后迟到的旧 Vue mount 会卸载且不回写槽位',
            h.vueRecord.unmounts === 1 && stableHost.children.length === 0);
        h.vueRecord.releaseNextMount();
        await Promise.all([firstBootstrap, reloadBootstrap]);
        ok('reload 等旧 mount 清理后才在共享 host 挂载新 publication',
            h.vueRecord.mounts === 2 && h.vueRecord.unmounts === 1
            && stableHost.children.length === 1
            && stableHost.children[0].html.includes('generation="2"'));
    }

    {
        const h = harness([
            manifest(1, [typeDescriptor()])
        ], {'/modules/demo.js': {initializer: BASIC_INITIALIZER, forgeCurrentScript: true}});
        await h.qt.bootstrap();
        ok('伪造 currentScript 即使复制 token 也不能注册', h.sandbox.registrationResult === false);
        ok('owner/load token 不匹配时类型不可用', !h.qt.isTypeAvailable('demo'));
    }

    {
        const h = harness([manifest(1, [typeDescriptor()])], {
            '/modules/demo.js': {initializer: `(function () {
                return {descriptor: {type: 'forged', ownerPluginId: 'forged', process: function () {}}};
            })`}
        });
        await h.qt.bootstrap();
        ok('模块自报 type/owner 时整份行为注册被拒绝', !h.qt.has('demo'));
    }

    {
        const unchangedOwner = typeDescriptor({pluginGeneration: 4, publicationId: 44});
        const h = harness([
            manifest(9, [unchangedOwner], 'process-a'),
            manifest(9, [unchangedOwner], 'process-b')
        ], {'/modules/demo.js': {initializer: BASIC_INITIALIZER, delays: [0, 30]}});
        await h.qt.bootstrap();
        const oldContext = h.sandbox.testState.contexts[0];
        const replacing = h.qt.refresh();
        await new Promise(resolve => setTimeout(resolve, 5));
        ok('仅 epoch 改变也会立即 abort 旧 activation', oldContext.signal.aborted === true);
        ok('epoch 切换加载窗口不暴露旧 handler', !h.qt.has('demo'));
        await replacing;
        ok('仅 epoch 改变会重新激活相同 owner publication', h.qt.has('demo'));
        ok('epoch 参与脚本 cachebuster', h.loads.length === 2 && h.loads[0] !== h.loads[1]
            && h.loads[0].includes('process-a') && h.loads[1].includes('process-b'));
    }

    {
        const h = harness([
            manifest(1, [typeDescriptor({pluginGeneration: 1, publicationId: 11})]),
            manifest(2, [typeDescriptor({pluginGeneration: 2, publicationId: 22})]),
            manifest(3, [])
        ], {'/modules/demo.js': {initializer: BASIC_INITIALIZER, delays: [0, 30]}});
        await h.qt.bootstrap();
        const oldBehavior = h.qt.descriptor('demo');
        const pendingOldAsync = h.qt.acquisition('demo', 'series').asyncRun();
        const oldContext = h.sandbox.testState.contexts[0];
        ok('初始 activation A 生效', oldBehavior.process() === 'v1');
        const replacing = h.qt.refresh();
        await new Promise(resolve => setTimeout(resolve, 5));
        ok('B 模块尚未完成时 A 已收到 abort', oldContext.signal.aborted === true);
        ok('B 加载窗口不继续暴露 A acquisition', h.qt.acquisition('demo', 'search') === null);
        h.sandbox.testState.resolveAsync('late-result');
        await assert.rejects(pendingOldAsync, /stale/);
        passed++;
        await replacing;
        ok('同 URL 新 publication B 生效', h.qt.descriptor('demo').process() === 'v2');
        ok('A 在 B 替换时执行 disposer', h.sandbox.testState.disposed.includes(1));
        assert.throws(() => oldBehavior.process(), /stale/);
        passed++;
        ok('同 URL 代际切换使用不同 cachebuster', h.loads.length === 2 && h.loads[0] !== h.loads[1]);
        await h.qt.refresh();
        ok('A 到 unload 后类型立即缺席', !h.qt.has('demo'));
        ok('B 在 unload 时收到 abort/dispose', h.sandbox.testState.contexts[1].signal.aborted
            && h.sandbox.testState.disposed.includes(2));
    }

    {
        const repeated = manifest(7, [typeDescriptor({publicationId: 70})]);
        const h = harness([repeated, repeated], {
            '/modules/demo.js': {initializer: BASIC_INITIALIZER, failCount: 1}
        });
        await h.qt.bootstrap();
        ok('首次 404 不产生半注册类型', !h.qt.has('demo'));
        await h.qt.refresh();
        ok('相同 manifest 在 404 后可重试成功', h.qt.has('demo'));
        ok('失败 URL 不进入永久缓存', h.attempts.get('/modules/demo.js') === 2);
    }

    {
        const h = harness([
            manifest(1, [typeDescriptor({pluginGeneration: 1, publicationId: 11})]),
            manifest(2, [])
        ], {'/modules/demo.js': {initializer: IN_FLIGHT_PROCESS_INITIALIZER}});
        await h.qt.bootstrap();
        const item = {};
        const inFlight = h.qt.get('demo').process(item);
        const invocation = h.sandbox.testState.processInvocations[0];
        const hostWrites = {stats: 0, saves: 0, renders: 0, current: []};
        const hostSandbox = {
            window: {PixivBatch: {queueTypes: h.qt, download: {}}},
            Promise, AbortController, TextDecoder, Uint8Array,
            setTimeout, clearTimeout, setInterval, clearInterval,
            console: {warn() {}, log() {}, error() {}},
            bt(key) { return key; },
            updateStats() { hostWrites.stats++; },
            saveQueue() { hostWrites.saves++; },
            renderQueue() { hostWrites.renders++; },
            setCurrent(value) { hostWrites.current.push(value); }
        };
        vm.createContext(hostSandbox);
        vm.runInContext(DOWNLOAD_SOURCE, hostSandbox);
        const queuedItem = {kind: 'demo', status: 'downloading', endTime: 'stale-completion'};
        const queuedProcess = hostSandbox.window.PixivBatch.download.processSingle(queuedItem);
        ok('process 收到当前模块 publication 固定的调用上下文', item.started
            && invocation.signal === h.sandbox.testState.contexts[0].signal
            && invocation.isActive());
        await h.qt.refresh();
        ok('卸载会 abort 在途 process 的模块级 signal', invocation.signal.aborted
            && !invocation.isActive());
        await assert.rejects(inFlight, error => error && error.code === 'STALE_QUEUE_TYPE');
        passed++;
        await queuedProcess;
        ok('卸载中的在途队列项按类型不可用暂停且不会误记失败', queuedItem.status === 'paused'
            && queuedItem.endTime === null
            && queuedItem.lastMessage === 'queue.message.type-unavailable'
            && hostWrites.stats === 1 && hostWrites.saves === 1 && hostWrites.renders === 1
            && hostWrites.current.length === 0);
        assert.throws(() => invocation.assertActive(), error => error && error.code === 'STALE_QUEUE_TYPE');
        passed++;
    }

    {
        const h = harness([
            manifest(1, [typeDescriptor({
                acquisitionModes: [], i18nNamespace: 'demo-i18n'
            })]),
            manifest(2, [])
        ], {'/modules/demo.js': {initializer: PATCH_PROCESS_INITIALIZER}});
        const commits = [];
        h.sandbox.window.PixivBatch.queue = {
            commitQueueItemPatch(item, patch) {
                commits.push({item, patch});
                return 'committed';
            }
        };
        await h.qt.bootstrap();
        ok('process context 固定绑定当前调用的活动队列项', vm.runInContext(
            "window.PixivBatch.queueTypes.get('demo').process({id:'patch-item'})", h.sandbox) === 'demo');
        ok('process context updateItem 由宿主 bridge 原子提交受控 patch', vm.runInContext(
            "testState.patchInvocation.updateItem({status:'failed',statusMessageKey:'demo-i18n:error.queue'})",
            h.sandbox) === 'committed'
            && commits.length === 1
            && commits[0].item === h.sandbox.testState.patchItem
            && commits[0].patch.status === 'failed'
            && commits[0].patch.statusMessageKey === 'demo-i18n:error.queue'
            && Object.getPrototypeOf(commits[0].patch) === null);
        assert.throws(() => vm.runInContext(
            "testState.patchInvocation.updateItem({statusMessageKey:'foreign:error.queue'})", h.sandbox),
        /i18n namespace/);
        passed++;
        ok('跨 namespace 文案键在到达宿主 bridge 前即被拒绝', commits.length === 1);
        await h.qt.refresh();
        assert.throws(() => vm.runInContext(
            "testState.patchInvocation.updateItem({status:'completed'})", h.sandbox),
        error => error && error.code === 'STALE_QUEUE_TYPE');
        passed++;
        ok('旧 publication 的 updateItem 不会跨代提交', commits.length === 1);
    }

    {
        const h = harness([
            manifest(1, [typeDescriptor({acquisitionModes: []})]),
            manifest(2, [])
        ], {'/modules/demo.js': {initializer: `(function () {
            return {descriptor: {process: processIllustItem}};
        })`}});
        h.sandbox.state = {settings: {skipHistory: true, verifyHistoryFiles: false}, queue: []};
        h.sandbox.bt = key => key;
        h.sandbox.updateStats = function () {};
        h.sandbox.saveQueue = function () {};
        h.sandbox.renderQueue = function () {};
        h.sandbox.setCurrent = function () {};
        vm.runInContext(DOWNLOAD_SOURCE, h.sandbox);
        await h.qt.bootstrap();
        let requestSignal = null;
        h.sandbox.fetch = function (url, init) {
            if (String(url).includes('/api/download/extensions')) {
                return Promise.resolve({ok: true, status: 200, json: () => Promise.resolve(manifest(2, []))});
            }
            requestSignal = init && init.signal;
            return new Promise((_resolve, reject) => {
                requestSignal.addEventListener('abort', () => reject(new Error('aborted')), {once: true});
            });
        };
        const item = {id: '42', kind: 'demo', status: 'downloading'};
        const processing = h.sandbox.window.PixivBatch.download.processSingle(item);
        await waitUntil(() => !!requestSignal);
        await h.qt.refresh();
        await processing;
        ok('通用队列类型在途历史请求绑定 publication signal，卸载后暂停且不误记失败',
            requestSignal.aborted && item.status === 'paused'
            && item.lastMessage === 'queue.message.type-unavailable');
    }

    {
        const controller = new AbortController();
        let active = true;
        const invocation = {
            signal: controller.signal,
            isActive() { return active && !controller.signal.aborted; },
            assertActive() {
                if (this.isActive()) return;
                const error = new Error('queue type activation is stale');
                error.code = 'STALE_QUEUE_TYPE';
                throw error;
            }
        };
        const sseSandbox = {
            window: {PixivBatch: {sse: {}}},
            state: {sseListeners: {}, sseRefs: {}, queue: []},
            Promise, setTimeout, clearTimeout, setInterval, clearInterval,
            console: {warn() {}, log() {}, error() {}}
        };
        vm.createContext(sseSandbox);
        vm.runInContext(SSE_SOURCE, sseSandbox);
        const waiting = sseSandbox.window.PixivBatch.sse.waitForFinalStatusBySSE('42', 60000, invocation);
        ok('SSE 等待在 publication 有效时登记精确作品 listener',
            sseSandbox.state.sseListeners['42'].length === 1);
        active = false;
        controller.abort();
        await assert.rejects(waiting, error => error && error.code === 'STALE_QUEUE_TYPE');
        passed++;
        ok('publication abort 会立即移除 SSE listener 与轮询等待',
            !sseSandbox.state.sseListeners['42']);
    }

    {
        const h = harness([manifest(1, [typeDescriptor()])], {
            '/modules/demo.js': {initializer: BASIC_INITIALIZER}
        });
        await h.qt.bootstrap();
        const request = h.qt.prepareAcquisitionRequest(
            'demo', 'search', '/api/demo/search?q=ok', 'search', {});
        ok('request gate 固定 GET/same-origin/no-store 且只保留显式允许的 header',
            request.init.method === 'GET'
            && request.init.credentials === 'same-origin'
            && request.init.cache === 'no-store'
            && !Object.prototype.hasOwnProperty.call(request.init, 'body')
            && request.init.headers.Accept === 'application/json'
            && request.init.headers['X-Acquisition-Credential'] === 'acquisition-session'
            && !Object.prototype.hasOwnProperty.call(request.init.headers, 'X-Pixiv-Cookie')
            && !Object.prototype.hasOwnProperty.call(request.init.headers, 'Authorization')
            && !Object.prototype.hasOwnProperty.call(request.init.headers, 'Cookie')
            && !Object.prototype.hasOwnProperty.call(request.init.headers, 'X-Evil')
            && !Object.prototype.hasOwnProperty.call(request.init.headers, 'Content-Type'));
        assert.throws(() => h.qt.prepareAcquisitionRequest(
            'demo', 'search', 'https://evil.test/steal', 'search', {}), /same-origin/);
        passed++;
        assert.throws(() => h.qt.prepareAcquisitionRequest(
            'demo', 'search', '//evil.test/steal', 'search', {}), /same-origin/);
        passed++;
        for (const endpoint of [
            '/api/x/%2e%2e/admin', '/api/x/%2Fadmin', '/api/x/%5cadmin', '/api/x/../admin'
        ]) {
            assert.throws(() => h.qt.prepareAcquisitionRequest(
                'demo', 'search', endpoint, 'search', {}), /traversal/);
            passed++;
        }
        const encodedQuery = h.qt.prepareAcquisitionRequest(
            'demo', 'search', '/api/demo/thumbnail?url=https%3A%2F%2Fimg.test%2Fa.jpg', 'search', {});
        ok('endpoint gate 允许 URLSearchParams 在 query 中的 percent 编码',
            encodedQuery.url.includes('https%3A%2F%2Fimg.test%2Fa.jpg'));
        ok('acquisitionList 用 runtime canonical type 覆盖模块嵌套伪造值',
            h.qt.acquisitionList('search')[0].type === 'demo');
        ok('受控 search acquisition 保留来源统计格式化钩子',
            h.qt.acquisition('demo', 'search').formatStats('total', {count: 12}) === 'total:12');
    }

    console.log(`batch-queue-types-publication.test.js: ${passed} assertions passed ✓`);
})().catch(error => {
    console.error('TEST FAILED:', error && error.stack ? error.stack : error);
    process.exit(1);
});
