'use strict';
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');

const SOURCE = fs.readFileSync(path.join(__dirname, '..', '..', '..', '..',
    'pixivdownload-plugin-download-workbench', 'src', 'main', 'resources', 'static',
    'pixiv-batch', 'batch-queue-types.js'), 'utf8');
const INIT_SOURCE = fs.readFileSync(path.join(__dirname, '..', '..', '..', '..',
    'pixivdownload-plugin-download-workbench', 'src', 'main', 'resources', 'static',
    'pixiv-batch', 'batch-init.js'), 'utf8');
const SETTINGS_SOURCE = fs.readFileSync(path.join(__dirname, '..', '..', '..', '..',
    'pixivdownload-plugin-download-workbench', 'src', 'main', 'resources', 'static',
    'pixiv-batch', 'batch-settings.js'), 'utf8');
const DOWNLOAD_SOURCE = fs.readFileSync(path.join(__dirname, '..', '..', '..', '..',
    'pixivdownload-plugin-download-workbench', 'src', 'main', 'resources', 'static',
    'pixiv-batch', 'batch-download.js'), 'utf8');
const SSE_SOURCE = fs.readFileSync(path.join(__dirname, '..', '..', '..', '..',
    'pixivdownload-plugin-download-workbench', 'src', 'main', 'resources', 'static',
    'pixiv-batch', 'batch-sse.js'), 'utf8');
const AI_SLOT_SOURCE = fs.readFileSync(path.join(__dirname, '..', '..', '..', '..',
    'pixivdownload-plugin-ai', 'src', 'main', 'resources', 'static',
    'pixiv-ai', 'download-novel-ai-settings-slot.js'), 'utf8');
const NOVEL_QUEUE_SOURCE = fs.readFileSync(path.join(__dirname, '..', '..', '..', '..',
    'pixivdownload-plugin-novel', 'src', 'main', 'resources', 'static',
    'pixiv-novel-download', 'novel-queue-type.js'), 'utf8');

class El {
    constructor(tag) {
        this.tag = tag;
        this.dataset = {};
        this.attributes = {};
        this.children = [];
        this.parentNode = null;
        this.onload = null;
        this.onerror = null;
        this.src = '';
    }
    appendChild(child) {
        child.parentNode = this;
        this.children.push(child);
        if (typeof this.onAppend === 'function') this.onAppend(child);
        return child;
    }
    insertBefore(child, reference) {
        child.parentNode = this;
        const index = this.children.indexOf(reference);
        if (index < 0) this.children.push(child);
        else this.children.splice(index, 0, child);
        return child;
    }
    setAttribute(name, value) {
        this.attributes[name] = String(value);
    }
    getAttribute(name) {
        return Object.prototype.hasOwnProperty.call(this.attributes, name) ? this.attributes[name] : null;
    }
    querySelectorAll(selector) {
        const out = [];
        const matches = node => selector === 'template[data-qt-slot]'
            ? node.tag === 'template' && node.getAttribute('data-qt-slot') !== null
            : selector === '[data-vue-slot]' && node.getAttribute('data-vue-slot') !== null;
        const visit = node => {
            node.children.forEach(child => {
                if (matches(child)) out.push(child);
                visit(child);
            });
        };
        visit(this);
        return out;
    }
    insertAdjacentHTML(position, html) {
        if (position !== 'beforeend') throw new Error('unsupported test DOM position: ' + position);
        const fragment = new El('#html');
        fragment.html = String(html);
        this.appendChild(fragment);
    }
    replaceChildren() {
        this.children.forEach(child => { child.parentNode = null; });
        this.children = [];
    }
    remove() {
        if (!this.parentNode) return;
        const index = this.parentNode.children.indexOf(this);
        if (index >= 0) this.parentNode.children.splice(index, 1);
        this.parentNode = null;
    }
}

function typeDescriptor(overrides = {}) {
    return Object.assign({
        contractVersion: 1,
        type: 'demo',
        ownerPluginId: 'demo-owner',
        packageId: 'demo-package',
        pluginGeneration: 1,
        publicationId: 1,
        order: 10,
        moduleUrl: '/modules/demo.js',
        acquisitionModes: ['single-import', 'user', 'search', 'series', 'quick']
    }, overrides);
}

function uiSlotDescriptor(overrides = {}) {
    return Object.assign({
        slotId: 'ai.settings',
        target: 'settings-card',
        moduleUrl: '/modules/ui-slot.js',
        order: 20,
        metadata: {},
        owner: {pluginId: 'ai', packageId: 'ai-package', generation: 1, publicationId: 10}
    }, overrides);
}

function manifest(revision, types, epoch = 'epoch-a', uiSlots = []) {
    return {epoch, revision, downloadTypes: types, tabs: [], uiSlots};
}

function fakeTimerClock() {
    let now = 0;
    let sequence = 0;
    const pending = new Map();
    return {
        setTimeout(callback, delay) {
            const id = ++sequence;
            pending.set(id, {at: now + Math.max(0, Number(delay) || 0), callback});
            return id;
        },
        clearTimeout(id) {
            pending.delete(id);
        },
        advance(milliseconds) {
            now += Math.max(0, Number(milliseconds) || 0);
            while (true) {
                const due = Array.from(pending.entries())
                    .filter(entry => entry[1].at <= now)
                    .sort((a, b) => (a[1].at - b[1].at) || (a[0] - b[0]));
                if (!due.length) return;
                due.forEach(([id, task]) => {
                    if (!pending.delete(id)) return;
                    task.callback();
                });
            }
        }
    };
}

function harness(manifests, moduleScripts, options = {}) {
    const head = new El('head');
    const body = new El('body');
    let slotParent = null;
    let slotMarker = null;
    if (options.slotTarget) {
        slotParent = new El('section');
        slotMarker = new El('template');
        slotMarker.setAttribute('data-qt-slot', options.slotTarget);
        slotParent.appendChild(slotMarker);
        body.appendChild(slotParent);
    }
    const document = {
        head,
        body,
        documentElement: new El('html'),
        currentScript: null,
        createElement: tag => new El(tag),
        querySelectorAll: selector => body.querySelectorAll(selector)
    };
    const requests = [];
    const loads = [];
    const attempts = new Map();
    let fetchIndex = 0;
    const vueRecord = {mounts: 0, unmounts: 0, pendingMounts: []};
    const listeners = new Map();
    const testWindow = {
        location: {origin: 'https://local.test'},
        addEventListener(type, listener) {
            if (!listeners.has(type)) listeners.set(type, new Set());
            listeners.get(type).add(listener);
        },
        removeEventListener(type, listener) {
            const entries = listeners.get(type);
            if (entries) entries.delete(listener);
        },
        dispatchEvent(event) {
            Array.from(listeners.get(event.type) || []).forEach(listener => listener(event));
        }
    };
    if (options.pixivVue) {
        const completeVueMount = (host, component) => {
            host.insertAdjacentHTML('beforeend', component.template);
            return {
                app: {unmount() { vueRecord.unmounts++; host.replaceChildren(); }},
                el: host
            };
        };
        vueRecord.releaseNextMount = () => {
            const pending = vueRecord.pendingMounts.shift();
            if (!pending) throw new Error('no deferred Vue mount is pending');
            pending.resolve(completeVueMount(pending.host, pending.component));
        };
        testWindow.PixivVue = {
            prepareSlotHosts() {},
            mountOn(host, component) {
                vueRecord.mounts++;
                if (options.deferVueMount) {
                    return new Promise(resolve => {
                        vueRecord.pendingMounts.push({host, component, resolve});
                    });
                }
                return Promise.resolve(completeVueMount(host, component));
            }
        };
    }
    const clock = options.fakeTimers ? fakeTimerClock() : null;
    const sandbox = {
        window: testWindow,
        document,
        BASE: '',
        URL,
        AbortController,
        CustomEvent: function CustomEvent(type, init) { return {type, detail: init && init.detail}; },
        Node: undefined,
        Promise,
        setTimeout: clock ? clock.setTimeout : setTimeout,
        clearTimeout: clock ? clock.clearTimeout : clearTimeout,
        pageI18n: {apply() {}},
        console: {warn() {}, log() {}, error() {}},
        testState: {contexts: [], disposed: []},
        fetch(url) {
            requests.push(String(url));
            const data = manifests[Math.min(fetchIndex++, manifests.length - 1)];
            return Promise.resolve({ok: true, status: 200, json: () => Promise.resolve(data)});
        }
    };
    vm.createContext(sandbox);
    vm.runInContext(SOURCE, sandbox);
    const qt = sandbox.window.PixivBatch.queueTypes;

    head.onAppend = script => {
        if (script.tag !== 'script') return;
        const parsed = new URL(script.src, sandbox.window.location.origin);
        const pathname = parsed.pathname;
        const attempt = (attempts.get(pathname) || 0) + 1;
        attempts.set(pathname, attempt);
        loads.push(script.src);
        const spec = moduleScripts[pathname];
        const delay = Array.isArray(spec && spec.delays) ? Number(spec.delays[attempt - 1] || 0) : 0;
        setTimeout(() => {
            if (spec && spec.never) return;
            if (!spec || (spec.failCount || 0) >= attempt) {
                if (typeof script.onerror === 'function') script.onerror(new Error('404'));
                return;
            }
            const evaluatedScript = spec.forgeCurrentScript ? new El('script') : script;
            if (spec.forgeCurrentScript) Object.assign(evaluatedScript.dataset, script.dataset);
            document.currentScript = evaluatedScript;
            if (spec.source) {
                sandbox.registrationResult = vm.runInContext(spec.source, sandbox);
            } else {
                const register = spec.ui ? 'registerUiModule' : 'registerModule';
                sandbox.registrationResult = vm.runInContext(
                    `window.PixivBatch.queueTypes.${register}(${spec.initializer})`, sandbox);
            }
            document.currentScript = null;
            if (typeof script.onload === 'function') script.onload();
        }, delay);
    };
    return {
        sandbox, qt, requests, loads, attempts, slotParent, slotMarker, vueRecord,
        listenerCount(type) { return (listeners.get(type) || new Set()).size; },
        advanceTimers(milliseconds) {
            if (!clock) throw new Error('fake timers are not enabled');
            clock.advance(milliseconds);
        }
    };
}

let passed = 0;
function ok(label, condition) {
    assert.ok(condition, label);
    passed++;
}

async function waitUntil(predicate) {
    for (let i = 0; i < 50; i++) {
        if (predicate()) return;
        await new Promise(resolve => setTimeout(resolve, 0));
    }
    throw new Error('timed out waiting for test condition');
}

const BASIC_INITIALIZER = `(function (context) {
    testState.contexts.push(context);
    return {
        descriptor: {
            process: function () { return 'v' + context.manifest.pluginGeneration; },
            slots: {'cookie-tools': '<span data-slot-generation="' + context.manifest.pluginGeneration + '"></span>'},
            import: {matchUrl: function () { return 'x'; }, buildItem: function () { return {}; }},
            acquisition: {
                user: {
                    parseInput: function (value) { return String(value); },
                    fetchMeta: function () { return ''; },
                    fetchIds: function () { return []; },
                    cardsEndpoint: function () { return '/api/demo/cards'; },
                    queueId: function (item) { return String(item.id); },
                    cardId: function (index) { return 'demo-user-' + index; },
                    render: function () {},
                    buildQueueMeta: function () { return {}; },
                    buildQueueMetaFromId: function () { return {}; }
                },
                search: {
                    type: 'forged-nested-type',
                    requestInit: function () {
                        return {
                            method: 'POST', credentials: 'include', body: 'forged-body',
                            headers: {
                                Authorization: 'secret', Cookie: 'secret',
                                Accept: 'application/json',
                                'X-Acquisition-Credential': 'acquisition-session',
                                'X-Pixiv-Cookie': 'blocked-legacy-header',
                                'X-Evil': 'blocked', 'Content-Type': 'application/json',
                                'X-Bad\\r\\nHeader': 'blocked'
                            }
                        };
                    },
                    buildRequest: function () { return {endpoint: '/api/demo/search'}; },
                    buildRangeRequest: function () { return {endpoint: '/api/demo/search/range'}; },
                    queueId: function (item) { return String(item.id); },
                    render: function () {},
                    buildQueueMeta: function () { return {}; },
                    run: function () { testState.searchRuns = (testState.searchRuns || 0) + 1; }
                },
                series: {
                    apiPath: function () { return '/api/demo/series'; },
                    parseUrl: function () { return {seriesId: 1}; },
                    resolveSeriesId: function () { return 1; },
                    typeLabel: function () { return 'demo'; },
                    queueId: function (item) { return String(item.id); },
                    cardId: function (index) { return 'demo-series-' + index; },
                    render: function () {},
                    buildQueueMeta: function () { return {}; },
                    run: function () { return 'series'; },
                    asyncRun: function () {
                        return new Promise(function (resolve) { testState.resolveAsync = resolve; });
                    }
                },
                quick: {
                    queueId: function (item) { return String(item.id); },
                    gridCardId: function (_prefix, index) { return 'demo-quick-' + index; },
                    innerCardHtml: function () { return ''; },
                    render: function () {},
                    buildQueueMeta: function () { return {}; }
                }
            }
        },
        dispose: function () { testState.disposed.push(context.manifest.pluginGeneration); }
    };
})`;

const REQUEST_OWNER_INITIALIZER = BASIC_INITIALIZER.replace(
    'user: {\n                    parseInput:',
    `user: {
                    accepts: function (selection) {
                        return selection === context.type || selection === 'request';
                    },
                    parseInput:`
);

const LEGACY_SOURCE = `(function () {
    var queueTypes = window.PixivBatch.queueTypes;
    testState.legacyWrongOwner = queueTypes.register('legacy', {
        pluginId: 'forged-owner', type: 'legacy', process: function () {}
    });
    testState.legacyRegistered = queueTypes.register('legacy', {
        pluginId: 'legacy-owner', type: 'legacy',
        process: function () { return 'legacy-process'; },
        import: {
            sectionType: 'legacy', bareDefault: false,
            matchUrl: function (line) { return String(line).indexOf('/legacy/') >= 0 ? '7' : null; },
            buildItem: function (id) { return {id: String(id), kind: 'legacy'}; }
        },
        acquisition: {
            user: {
                parseInput: function (value) { return String(value); },
                fetchMeta: function () { return ''; },
                fetchIds: function () { return []; },
                cardsEndpoint: function () { return '/api/legacy/cards'; },
                queueId: function (item) { return String(item.id); },
                cardId: function (index) { return 'legacy-user-' + index; },
                render: function () {},
                buildQueueMeta: function () { return {}; },
                buildQueueMetaFromId: function () { return {}; }
            },
            search: {buildRequest: function () { return {}; }}
        },
        slots: {'kind-option-user': '<span data-legacy-slot="true"></span>'}
    });
})()`;

const FAILING_INITIALIZER = `(function (context) {
    testState.failedContext = context;
    context.onCleanup(function () {
        testState.failedCleanup = (testState.failedCleanup || 0) + 1;
    });
    throw new Error('initializer failed after registering cleanup');
})`;

const PENDING_INITIALIZER = `(function (context) {
    testState.pendingContext = context;
    context.onCleanup(function () {
        testState.pendingCleanup = (testState.pendingCleanup || 0) + 1;
    });
    return new Promise(function () {});
})`;

const IN_FLIGHT_PROCESS_INITIALIZER = `(function (context) {
    testState.contexts.push(context);
    return {descriptor: {process: function (item, invocation) {
        testState.processInvocations = testState.processInvocations || [];
        testState.processInvocations.push(invocation);
        item.started = true;
        return new Promise(function (resolve, reject) {
            var abort = function () {
                try { invocation.assertActive(); }
                catch (error) { reject(error); }
            };
            if (invocation.signal.aborted) abort();
            else invocation.signal.addEventListener('abort', abort, {once: true});
        });
    }}};
})`;

const LATE_QUEUE_INITIALIZER = `(function (context) {
    testState.lateQueueContext = context;
    return new Promise(function (resolve) {
        testState.resolveLateQueue = function () {
            try {
                context.onCleanup(function () {
                    testState.lateQueueCleanup = (testState.lateQueueCleanup || 0) + 1;
                });
            } catch (e) {
                testState.lateQueueCleanupRejected = (testState.lateQueueCleanupRejected || 0) + 1;
            }
            resolve({
                descriptor: {process: function () {}},
                dispose: function () {
                    testState.lateQueueDispose = (testState.lateQueueDispose || 0) + 1;
                }
            });
        };
    });
})`;

const UI_INITIALIZER = `(function (context) {
    testState.uiContexts = testState.uiContexts || [];
    testState.uiContexts.push(context);
    var listener = function () { testState.uiEvents = (testState.uiEvents || 0) + 1; };
    window.addEventListener('ui-probe', listener);
    context.onCleanup(function () {
        window.removeEventListener('ui-probe', listener);
        testState.uiCleanups = (testState.uiCleanups || 0) + 1;
    });
})`;

const LATE_UI_INITIALIZER = `(function (context) {
    testState.lateUiContext = context;
    return new Promise(function (resolve) {
        testState.resolveLateUi = function () {
            try {
                context.onCleanup(function () {
                    testState.lateUiCleanup = (testState.lateUiCleanup || 0) + 1;
                });
            } catch (e) {
                testState.lateUiCleanupRejected = (testState.lateUiCleanupRejected || 0) + 1;
            }
            resolve({dispose: function () {
                testState.lateUiDispose = (testState.lateUiDispose || 0) + 1;
            }});
        };
    });
})`;

(async function () {
    {
        const legacy = typeDescriptor({
            type: 'legacy', ownerPluginId: 'legacy-owner', packageId: 'legacy-package',
            publicationId: 9, moduleUrl: '/modules/legacy.js', acquisitionModes: [],
            legacyContract: true
        });
        const h = harness([manifest(1, [legacy]), manifest(2, [])], {
            '/modules/legacy.js': {source: LEGACY_SOURCE}
        }, {slotTarget: 'kind-option-user'});
        await h.qt.bootstrap();
        ok('旧模块伪造 owner 的登记会被拒绝', h.sandbox.testState.legacyWrongOwner === false);
        ok('冻结的 v1 register(type, descriptor) 模块可在盖章 script 内激活',
            h.sandbox.testState.legacyRegistered === true && h.qt.has('legacy'));
        ok('旧模块只暴露实际通过校验的取得模式',
            h.qt.typesForMode('single-import').join(',') === 'legacy'
            && h.qt.typesForMode('user').join(',') === 'legacy'
            && h.qt.typesForMode('search').length === 0
            && h.qt.typesForMode('series').length === 0
            && h.qt.typesForMode('quick').length === 0);
        ok('旧模块 process 进入与现代模块相同的受控调用路径',
            h.qt.get('legacy').process() === 'legacy-process');
        const host = h.slotParent.children.find(
            node => node.getAttribute('data-vue-slot') === 'kind-option-user');
        ok('旧模块已有 UI 槽位仍可在成熟页面锚点渲染', !!host && host.children.length === 1
            && host.children[0].html.includes('data-legacy-slot'));
        const oldProcess = h.qt.get('legacy').process;
        await h.qt.refresh();
        ok('旧模块卸载后类型与槽位立即消失', !h.qt.has('legacy') && host.children.length === 0);
        assert.throws(() => oldProcess(), /stale/);
        passed++;
    }

    {
        const h = harness([
            manifest(1, [typeDescriptor({
                acquisitionModes: ['series'],
                owner: {pluginId: 'nested-owner', packageId: 'nested-package', generation: 5, publicationId: 6}
            })])
        ], {'/modules/demo.js': {initializer: BASIC_INITIALIZER}});
        ok('作用域外不能登记模块', h.qt.registerModule(() => ({})) === false);
        await h.qt.bootstrap();
        ok('后端声明的 series 模式可见', h.qt.supports('demo', 'series'));
        ok('未声明 search 即使模块提供 hook 也不可见', !h.qt.supports('demo', 'search'));
        ok('未声明 search hook 永不返回', h.qt.acquisition('demo', 'search') === null);
        ok('typesForMode 只含已声明且实现的类型', h.qt.typesForMode('series').join(',') === 'demo');
        ok('resolveTypeForMode 不回退到未声明模式', h.qt.resolveTypeForMode('demo', 'search') === null);
        ok('owner 由后端 manifest 注入', h.qt.descriptor('demo').owner.ownerPluginId === 'nested-owner');
        ok('manifestDescriptor 只读暴露后端 owner/i18n 视图',
            h.qt.manifestDescriptor('demo').owner.pluginId === 'nested-owner'
            && Object.isFrozen(h.qt.manifestDescriptor('demo').acquisitionModes));
        ok('模块不能用 descriptor 覆盖 type', h.qt.descriptor('demo').type === 'demo');
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
        ok('pending initializer 会在 5000ms 受控超时后释放 bootstrap', true);
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
        const h = harness([
            manifest(1, [typeDescriptor({
                pluginGeneration: 1, publicationId: 11, uiSlots: ['cookie-tools']
            })], 'epoch-a', [uiSlotDescriptor({
                slotId: 'demo.cookie', target: 'cookie-tools', moduleUrl: '/modules/demo.js',
                owner: {pluginId: 'demo-owner', packageId: 'demo-package', generation: 1, publicationId: 11}
            })]),
            manifest(2, []),
            manifest(3, [typeDescriptor({
                pluginGeneration: 2, publicationId: 22, uiSlots: ['cookie-tools']
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
        const h = harness([
            manifest(1, [typeDescriptor({
                pluginGeneration: 1, publicationId: 11, uiSlots: ['cookie-tools']
            })], 'epoch-a', [uiSlotDescriptor({
                slotId: 'demo.cookie', target: 'cookie-tools', moduleUrl: '/modules/demo.js',
                owner: {pluginId: 'demo-owner', packageId: 'demo-package', generation: 1, publicationId: 11}
            })]),
            manifest(2, [typeDescriptor({
                pluginGeneration: 2, publicationId: 22, uiSlots: ['cookie-tools']
            })], 'epoch-a', [uiSlotDescriptor({
                slotId: 'demo.cookie', target: 'cookie-tools', moduleUrl: '/modules/demo.js',
                owner: {pluginId: 'demo-owner', packageId: 'demo-package', generation: 2, publicationId: 22}
            })])
        ], {'/modules/demo.js': {initializer: BASIC_INITIALIZER}}, {
            slotTarget: 'cookie-tools', pixivVue: true, deferVueMount: true
        });
        const firstBootstrap = h.qt.bootstrap();
        await waitUntil(() => h.vueRecord.pendingMounts.length === 1);
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
        ok('Pixiv 在途历史请求绑定 publication signal，卸载后暂停且不误记失败',
            requestSignal.aborted && item.status === 'paused'
            && item.lastMessage === 'queue.message.type-unavailable');
    }

    {
        const novelType = typeDescriptor({
            type: 'novel', ownerPluginId: 'novel-owner', packageId: 'novel-package',
            moduleUrl: '/modules/novel.js', acquisitionModes: []
        });
        const h = harness([manifest(1, [novelType]), manifest(2, [])], {
            '/modules/novel.js': {source: NOVEL_QUEUE_SOURCE}
        });
        h.sandbox.SINGLE_IMPORT_NOVEL_SOURCE = 'single-import-novel';
        h.sandbox.QUICK_PAGE_SIZE_NOVEL = 24;
        h.sandbox.bt = key => key;
        h.sandbox.apiGet = () => Promise.resolve({});
        h.sandbox.state = {settings: {skipHistory: true, redownloadDeleted: false}, queue: []};
        h.sandbox.getCookie = () => '';
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
        const item = {id: 'n42', novelId: '42', kind: 'novel', status: 'downloading'};
        const processing = h.sandbox.window.PixivBatch.download.processSingle(item);
        await waitUntil(() => !!requestSignal);
        await h.qt.refresh();
        await processing;
        ok('Novel 在途历史请求绑定 publication signal，卸载后暂停且不误记失败',
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
    }

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
        const badSlotInitializer = `(function () {
            return {descriptor: {
                process: function () {},
                slots: {'cookie-tools': function () { throw new Error('broken slot'); }}
            }};
        })`;
        const goodType = typeDescriptor({uiSlots: ['cookie-tools']});
        const badType = typeDescriptor({
            type: 'bad-slot', ownerPluginId: 'bad-slot-owner', packageId: 'bad-slot-package',
            publicationId: 2, moduleUrl: '/modules/bad-slot.js', acquisitionModes: [],
            uiSlots: ['cookie-tools']
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
        const novelType = typeDescriptor({
            type: 'novel',
            ownerPluginId: 'novel-owner',
            packageId: 'novel-package',
            moduleUrl: '/modules/novel.js',
            acquisitionModes: [],
            filters: ['novel-words'],
            settings: ['novel-settings-card']
        });
        const h = harness([manifest(1, [novelType])], {
            '/modules/novel.js': {source: NOVEL_QUEUE_SOURCE}
        });
        h.sandbox.SINGLE_IMPORT_NOVEL_SOURCE = 'single-import-novel';
        h.sandbox.QUICK_PAGE_SIZE_NOVEL = 24;
        h.sandbox.bt = (_key, fallback) => fallback;
        h.sandbox.apiGet = url => Promise.resolve({bookmarkCount: 77, url});
        await h.qt.bootstrap();
        const filter = h.qt.filtersFor('novel');
        const setting = h.qt.settingsFor('novel');
        const bookmark = await filter.bookmarkCountFetch('42');
        ok('真实 Novel queue module 的字数 filter 经 runtime 聚合后保持行为',
            filter.type === 'novel'
            && filter.matchExtra({wordCount: 1200}, {wordsMin: 1000, wordsMax: 1500})
            && !filter.matchExtra({wordCount: 400}, {wordsMin: 500, wordsMax: null}));
        ok('真实 Novel queue module 的 bookmark hook 经 runtime 聚合后可调用',
            bookmark.bookmarkCount === 77
            && bookmark.url === '/api/pixiv/novel/42/bookmark-count');
        ok('真实 Novel queue module 的 settings 卡经 runtime 聚合并保留类型身份',
            setting.type === 'novel' && setting.cardId === 'novel-settings-card');
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
    ok('AI 下载设置槽位使用受控 uiSlot initializer',
        AI_SLOT_SOURCE.includes('queueTypes.registerUiModule(function (context)'));
    ok('AI 下载设置槽位在 activation cleanup 中移除 listener 与 DOM',
        AI_SLOT_SOURCE.includes("removeEventListener('pixivbatch:slotsrendered'")
        && AI_SLOT_SOURCE.includes('context.onCleanup(removeRendered)'));
    ok('槽位运行时串行 renderSlots 并无条件清理 stale record',
        SOURCE.includes('let slotRenderTail = Promise.resolve()')
        && SOURCE.includes('slotRenderTail.catch(() => undefined)')
        && /snapshot !== current\) \{[\s\S]*?cleanupSlotRecord\(record\);[\s\S]*?slotMounts\.get\(target\)/
            .test(SOURCE));

    console.log(`batch-queue-types.test.js: ${passed} assertions passed ✓`);
})().catch(error => {
    console.error('TEST FAILED:', error && error.stack ? error.stack : error);
    process.exit(1);
});
