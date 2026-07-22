'use strict';
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');

const STATIC = path.join(__dirname, '..', '..', 'main', 'resources', 'static', 'pixiv-batch');
const SOURCE = fs.readFileSync(path.join(STATIC, 'batch-queue-types.js'), 'utf8');
const INIT_SOURCE = fs.readFileSync(path.join(STATIC, 'batch-init.js'), 'utf8');
const SETTINGS_SOURCE = fs.readFileSync(path.join(STATIC, 'batch-settings.js'), 'utf8');
const DOWNLOAD_SOURCE = fs.readFileSync(path.join(STATIC, 'batch-download.js'), 'utf8');
const SSE_SOURCE = fs.readFileSync(path.join(STATIC, 'batch-sse.js'), 'utf8');

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
    const defaults = {
        contractVersion: 1,
        type: 'demo',
        displayNamespace: 'demo',
        displayI18nKey: 'type.demo',
        order: 10,
        iconKey: 'download',
        colorToken: 'green',
        moduleUrl: '/modules/demo.js',
        acquisitionModes: ['single-import', 'user', 'search', 'series', 'quick'],
        cancelSupported: true,
        filters: [],
        settings: [],
        i18nNamespace: 'demo-i18n',
        owner: {pluginId: 'demo-owner', packageId: 'demo-package', generation: 1, publicationId: 1}
    };
    const descriptor = Object.assign({}, defaults, overrides);
    const suppliedOwner = overrides.owner && typeof overrides.owner === 'object' ? overrides.owner : {};
    descriptor.owner = {
        pluginId: Object.prototype.hasOwnProperty.call(overrides, 'ownerPluginId')
            ? overrides.ownerPluginId : (suppliedOwner.pluginId || defaults.owner.pluginId),
        packageId: Object.prototype.hasOwnProperty.call(overrides, 'packageId')
            ? overrides.packageId : (suppliedOwner.packageId || defaults.owner.packageId),
        generation: Object.prototype.hasOwnProperty.call(overrides, 'pluginGeneration')
            ? overrides.pluginGeneration
            : (suppliedOwner.generation == null ? defaults.owner.generation : suppliedOwner.generation),
        publicationId: Object.prototype.hasOwnProperty.call(overrides, 'publicationId')
            ? overrides.publicationId
            : (suppliedOwner.publicationId == null ? defaults.owner.publicationId : suppliedOwner.publicationId)
    };
    delete descriptor.ownerPluginId;
    delete descriptor.packageId;
    delete descriptor.pluginGeneration;
    delete descriptor.publicationId;
    return descriptor;
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
    return {epoch, revision, downloadTypes: types, uiSlots};
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
            scheduledSse: false,
            scheduledQueueItem: function (item) { return {id: 'owned-' + item.id}; },
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
                    formatStats: function (metric, stats) { return metric + ':' + stats.count; },
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

function acquisitionDataSourceInitializer(mode, sourceLiteral, initializer = BASIC_INITIALIZER) {
    const token = mode === 'single-import' ? 'import' : mode;
    return initializer.replace(
        `${token}: {`,
        `${token}: {
                    dataSource: ${sourceLiteral},`
    );
}

function quickDataSourceInitializer(sourceLiteral) {
    return acquisitionDataSourceInitializer('quick', sourceLiteral);
}

function importDataSourceInitializer(sourceLiteral) {
    return acquisitionDataSourceInitializer('single-import', sourceLiteral);
}

function quickAndImportDataSourceInitializer(sourceLiteral) {
    return acquisitionDataSourceInitializer(
        'single-import', sourceLiteral, quickDataSourceInitializer(sourceLiteral));
}

function queueTagsInitializer(hookLiteral) {
    return BASIC_INITIALIZER.replace(
        'scheduledSse: false,',
        `scheduledSse: false,
            queueTags: ${hookLiteral},`
    );
}

function seriesBrowserInitializer(browserLiteral) {
    return BASIC_INITIALIZER.replace(
        'series: {\n                    apiPath:',
        `series: {
                    browser: ${browserLiteral},
                    apiPath:`
    );
}

const REQUEST_OWNER_INITIALIZER = BASIC_INITIALIZER.replace(
    'user: {\n                    parseInput:',
    `user: {
                    accepts: function (selection) {
                        return selection === context.type || selection === 'request';
                    },
                    parseInput:`
);

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

const PATCH_PROCESS_INITIALIZER = `(function () {
    return {descriptor: {process: function (item, invocation) {
        testState.patchItem = item;
        testState.patchInvocation = invocation;
        return invocation.type;
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

function quickActionInitializer(...actions) {
    const entries = actions.map(action => `'${action}': {load: function () {}}`).join(',');
    return BASIC_INITIALIZER.replace(
        `quick: {
                    queueId:`,
        `quick: {
                    actions: {${entries}},
                    queueId:`
    );
}

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
        ok('scheduledQueueItem 调用 owner hook', h.qt.scheduledQueueItem('demo', {id: '7'}, {}).id === 'owned-7');
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
