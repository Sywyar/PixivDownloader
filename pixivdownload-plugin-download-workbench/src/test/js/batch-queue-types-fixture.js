'use strict';
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const STATIC = path.join(__dirname, '..', '..', 'main', 'resources', 'static', 'pixiv-batch');
const SOURCE = [
    'batch-queue-types-normalize.js',
    'batch-queue-types-runtime.js',
    'batch-queue-types.js'
].map(file => fs.readFileSync(path.join(STATIC, file), 'utf8')).join('\n');
const INIT_SOURCE = fs.readFileSync(path.join(STATIC, 'batch-init.js'), 'utf8');
const SETTINGS_SOURCE = fs.readFileSync(path.join(STATIC, 'batch-settings.js'), 'utf8');
const DOWNLOAD_SOURCE = [
    'batch-download-quota.js', 'batch-download-artwork.js',
    'batch-download-workers.js', 'batch-download.js'
].map(file => fs.readFileSync(path.join(STATIC, file), 'utf8')).join('\n');
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
        for (let index = 0; index < (options.slotCount || 1); index++) {
            const markerParent = options.slotCount > 1 ? new El('div') : slotParent;
            const marker = new El('template');
            marker.setAttribute('data-qt-slot', options.slotTarget);
            markerParent.appendChild(marker);
            if (markerParent !== slotParent) slotParent.appendChild(markerParent);
            if (!slotMarker) slotMarker = marker;
        }
        body.appendChild(slotParent);
    }
    const document = {
        head,
        body,
        documentElement: new El('html'),
        currentScript: null,
        createElement: tag => new El(tag),
        querySelectorAll: selector => body.querySelectorAll(selector),
        getElementById(id) {
            let found = null;
            (function walk(n) {
                if (found) return;
                n.children.forEach(c => {
                    if (found) return;
                    if (c.getAttribute('id') === id) found = c;
                    walk(c);
                });
            })(body);
            return found;
        }
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
                if (options.mountFail || options.mountFailAt === vueRecord.mounts) {
                    return Promise.resolve(null);
                }
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
                const register = script.dataset.queueTypeSubmoduleToken
                    ? 'registerSubmodule'
                    : (spec.ui ? 'registerUiModule' : 'registerModule');
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
            scheduledQueueItem: function (item) {
                testState.scheduledOwnerCalls = (testState.scheduledOwnerCalls || 0) + 1;
                return {id: 'owned-' + item.id, ownerMapped: true};
            },
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

function queueLiveStatusInitializer(hookLiteral) {
    return BASIC_INITIALIZER.replace(
        'scheduledSse: false,',
        `scheduledSse: false,
            queueLiveStatus: ${hookLiteral},`
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

module.exports = {
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
};
