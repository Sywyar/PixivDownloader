/** 布局调查行为测试共享夹具与断言入口。 */
'use strict';

const fs = require('fs');

const path = require('path');

const vm = require('vm');

const assert = require('assert');

const {webcrypto} = require('crypto');

const SOURCE_NAMES = [
    'pixiv-layout-feedback-core.js',
    'pixiv-layout-feedback-server.js',
    'pixiv-layout-feedback-state.js',
    'pixiv-layout-feedback-survey.js',
    'pixiv-layout-feedback-dialog.js',
    'pixiv-layout-feedback.js'
];

const SOURCE_PATHS = SOURCE_NAMES.map(name => path.join(__dirname, '..', '..', 'main',
    'resources', 'static', 'pixiv-layout-feedback', name));

const CONFIG_SOURCE_PATH = path.join(__dirname, '..', '..', 'main', 'resources', 'static',
    'pixiv-layout-feedback', 'posthog-config.js');

const CSS_PATH = path.join(__dirname, '..', '..', 'main', 'resources', 'static',
    'pixiv-layout-feedback', 'pixiv-layout-feedback.css');

const EMBED_SOURCE_PATH = path.join(__dirname, '..', '..', 'main', 'resources', 'static',
    'pixiv-layout-feedback', 'embed.js');

const POSTHOG_SOURCE_PATH = path.join(__dirname, '..', '..', '..', '..',
    'pixivdownload-plugin-posthog', 'src', 'main', 'resources', 'static',
    'pixiv-posthog', 'pixiv-posthog.js');

const SOURCES = SOURCE_PATHS.map(sourcePath => fs.readFileSync(sourcePath, 'utf8'));

const SOURCE = SOURCES.join('\n');

const CONFIG_SOURCE = fs.readFileSync(CONFIG_SOURCE_PATH, 'utf8');

const CSS = fs.readFileSync(CSS_PATH, 'utf8');

const EMBED_SOURCE = fs.readFileSync(EMBED_SOURCE_PATH, 'utf8');

const POSTHOG_SOURCE = fs.readFileSync(POSTHOG_SOURCE_PATH, 'utf8');

const LAYOUT_IDS = ['pixiv-batch-landscape', 'pixiv-batch-portrait', 'pixiv-batch-alt'];

const STATE_KEY = 'pixiv:layout-feedback:state:v1';

const SEEN_KEY = 'pixiv:layout-feedback:seen:v1';

const SNOOZE_MS = 7 * 24 * 60 * 60 * 1000;

const SUGGESTION_MAX = 1000;

const CONFIG_WINDOW = {};

vm.runInNewContext(CONFIG_SOURCE, {window: CONFIG_WINDOW});

SOURCES.forEach((source, index) => vm.runInNewContext(source, {window: CONFIG_WINDOW}, {
    filename: SOURCE_NAMES[index]
}));

const SURVEY_ID = CONFIG_WINDOW.PixivLayoutFeedback._internals.POSTHOG.surveyId;

const SUBMISSION_ID = '018f35a1-7c40-8abc-8def-0123456789ab';

let passed = 0;

function ok(label, condition) {
    assert.ok(condition, label);
    passed++;
}

function eq(label, actual, expected) {
    assert.strictEqual(actual, expected, label);
    passed++;
}

function jsonEq(label, actual, expected) {
    assert.strictEqual(JSON.stringify(actual), JSON.stringify(expected), label);
    passed++;
}

const {
    MiniClassList,
    MiniCustomEvent,
    MiniElement,
    MiniEventTarget,
    MiniStorage,
    createFakeAdapter,
    createFakeI18n,
    createFakeTimers,
    dataAttributeName,
    matchSelector,
    walkAll
} = require('./pixiv-layout-feedback-test-dom');

function doesNotThrow(label, action) {
    assert.doesNotThrow(action, label);
    passed++;
}

function createHarness(options) {
    options = options || {};
    const html = new MiniElement('html');
    const head = new MiniElement('head');
    const body = new MiniElement('body');

    const document = {
        documentElement: html,
        head,
        body,
        activeElement: null,
        visibilityState: options.visibilityState || 'visible',
        createElement(tagName) {
            const element = new MiniElement(tagName, document);
            if (tagName.toLowerCase() === 'script') {
                scripts.push(element);
            }
            return element;
        },
        createElementNS(namespace, tagName) {
            return this.createElement(tagName);
        },
        getElementById(id) {
            let found = null;
            (function walk(node) {
                if (node.id === id) { found = node; return; }
                for (const child of node.children) {
                    walk(child);
                    if (found) return;
                }
            })(html);
            return found;
        },
        querySelector(selector) {
            if (html.matches(selector)) return html;
            return html.querySelector(selector);
        },
        querySelectorAll(selector) {
            const found = [];
            if (html.matches(selector)) found.push(html);
            return found.concat(html.querySelectorAll(selector));
        },
        addEventListener(type, listener) {
            document._events = document._events || new Map();
            const list = document._events.get(type) || [];
            if (list.indexOf(listener) < 0) list.push(listener);
            document._events.set(type, list);
        },
        removeEventListener(type, listener) {
            const list = (document._events && document._events.get(type)) || [];
            document._events && document._events.set(type, list.filter(item => item !== listener));
        },
        dispatchEvent(event) {
            if (!event || !event.type) throw new Error('event.type is required');
            if (typeof event.preventDefault !== 'function') {
                event.preventDefault = function () { this.defaultPrevented = true; };
            }
            if (typeof event.stopImmediatePropagation !== 'function') {
                event.stopImmediatePropagation = function () { this.stopped = true; };
            }
            if (typeof event.stopPropagation !== 'function') {
                event.stopPropagation = function () {};
            }
            ((document._events && document._events.get(event.type)) || []).slice()
                .forEach(listener => {
                    if (event.stopped) return;
                    listener.call(document, event);
                });
            return !event.defaultPrevented;
        },
        contains(node) {
            return html.contains(node);
        }
    };
    html.appendChild(head);
    html.appendChild(body);
    if (options.batchLayout) html.setAttribute('data-batch-layout', options.batchLayout);

    const storage = new MiniStorage(options.storage || {});
    storage.throwOnGet = !!options.throwOnGet;
    storage.throwOnSet = !!options.throwOnSet;
    storage.throwOnRemove = !!options.throwOnRemove;

    const windowEvents = new MiniEventTarget();
    const timers = createFakeTimers(options.initialWall);
    const fetchCalls = [];
    const toastCalls = [];
    const consoleWarn = [];
    const scripts = [];
    const serverPosts = [];
    const serverAbortCalls = [];
    const serverFetchGate = {pending: false, resolve: null, reject: null};
    const serverStateHolder = {value: options.serverState};
    let getStateFetchCount = 0;

    const publicConfig = {
        projectToken: 'phc_nBnHrYwgVVN6CvzAsQ5r4NxuSJyVPmceeHwwcpcgbG3k',
        surveyId: SURVEY_ID,
        apiHost: 'https://layout-survey.sywyar.top',
        uiHost: 'https://us.posthog.com'
    };

    // 服务端模拟的「当前权威视图」：命令应用后携带到后续 POST / 视图响应
    //（record_seen 等命令的响应必须保留既有状态视图，与真实服务端一致）。
    // 初始视图来自 options.serverState（与真实服务端加载状态文件一致）。
    let simulatedView = options.serverState && options.serverState.status
        ? {
            status: options.serverState.status,
            canShow: options.serverState.canShow,
            retryAfterMs: options.serverState.retryAfterMs || 0
        }
        : null;

    function viewRank(status) {
        if (status === 'submitted') return 3;
        if (status === 'never') return 2;
        if (status === 'snoozed') return 1;
        return 0;
    }

    function serverSnapshotFromBody(body) {
        // 默认 POST 响应：像真实服务端一样基于当前权威视图合并命令结果（revision 递增，
        // 不返回任何服务端绝对时间点；snooze 时长由「服务端时钟」决定，默认与客户端
        // 假时钟一致）。
        const current = defaultServerGetResponse();
        const snapshot = {
            available: true,
            stateAvailable: true,
            distinctId: current.distinctId,
            submissionId: current.submissionId,
            revision: (typeof current.revision === 'number' ? current.revision : 0) + 1,
            status: simulatedView ? simulatedView.status : null,
            canShow: simulatedView ? simulatedView.canShow : true,
            retryAfterMs: simulatedView ? simulatedView.retryAfterMs : 0,
            seenLayouts: current.seenLayouts.slice()
        };
        if (body.command === 'submitted') {
            simulatedView = {status: 'submitted', canShow: false, retryAfterMs: 0};
        } else if (body.command === 'never') {
            if (viewRank(simulatedView ? simulatedView.status : null) < 2) {
                simulatedView = {status: 'never', canShow: false, retryAfterMs: 0};
            }
        } else if (body.command === 'snooze') {
            if (viewRank(simulatedView ? simulatedView.status : null) <= 1) {
                simulatedView = {status: 'snoozed', canShow: false, retryAfterMs: SNOOZE_MS};
            }
        }
        if (body.command === 'record_seen' && Array.isArray(body.layoutIds)) {
            body.layoutIds.forEach(id => {
                if (snapshot.seenLayouts.indexOf(id) < 0) snapshot.seenLayouts.push(id);
            });
        }
        snapshot.status = simulatedView ? simulatedView.status : null;
        snapshot.canShow = simulatedView ? simulatedView.canShow : true;
        snapshot.retryAfterMs = simulatedView ? simulatedView.retryAfterMs : 0;
        return snapshot;
    }

    function defaultServerGetResponse() {
        const base = {
            available: true,
            stateAvailable: true,
            distinctId: options.serverScopedId !== undefined ? options.serverScopedId : SERVER_SCOPED_ID,
            submissionId: options.serverSubmissionId !== undefined
                ? options.serverSubmissionId : SUBMISSION_ID,
            revision: 0,
            status: null,
            canShow: true,
            retryAfterMs: 0,
            seenLayouts: []
        };
        const data = Object.assign({}, base, serverStateHolder.value || {});
        if (data.distinctId === undefined) data.distinctId = base.distinctId;
        return data;
    }

    const sandbox = {
        document,
        window: null,
        self: null,
        location: {pathname: options.pathname || '/pixiv-batch.html'},
        localStorage: storage,
        console: {
            warn(...args) { consoleWarn.push(args); },
            log() {},
            error() {}
        },
        fetch(url, init) {
            fetchCalls.push({url, init});
            if (typeof options.fetchImpl === 'function') {
                return options.fetchImpl(url, init);
            }
            if (options.fetch === 'fail') {
                return Promise.reject(new Error('network down'));
            }
            if (url.indexOf('/api/layout-feedback/state') >= 0) {
                if (init && init.signal) {
                    init.signal.addEventListener('abort', () => serverAbortCalls.push(url));
                }
                if (init && init.method === 'POST') {
                    serverPosts.push({url, body: JSON.parse(init.body || '{}')});
                    if (process.env.DBG_POSTS && init.method === 'POST') { console.log('DBG-POST', JSON.parse(init.body||'{}').command, new Error().stack.split('\n').slice(1,6).join(' | ')); }
                    if (options.serverPostResponse === 'fail') {
                        return Promise.reject(new Error('server down'));
                    }
                    if (typeof options.serverPostResponse === 'function') {
                        const custom = options.serverPostResponse({url, body: serverPosts[serverPosts.length - 1].body});
                        if (custom === undefined) {
                            // 未覆盖的命令走默认合并快照
                            const snapshot = serverSnapshotFromBody(serverPosts[serverPosts.length - 1].body);
                            return Promise.resolve({ok: true, json: () => Promise.resolve(snapshot)});
                        }
                        if (custom && typeof custom.then === 'function') return custom;
                        const isError = !!(custom && custom.status && custom.status >= 400);
                        return Promise.resolve({
                            ok: isError ? false : (custom ? custom.ok !== false : true),
                            status: custom && custom.status ? custom.status : 200,
                            json: () => (custom && typeof custom.json === 'function'
                                ? custom.json()
                                : Promise.resolve(custom || {}))
                        });
                    }
                    const snapshot = serverSnapshotFromBody(serverPosts[serverPosts.length - 1].body);
                    return Promise.resolve({ok: true, json: () => Promise.resolve(snapshot)});
                }
                getStateFetchCount++;
                if (options.serverFetch === 'fail') {
                    return Promise.reject(new Error('server down'));
                }
                if (typeof options.serverFetch === 'function') {
                    // 逐次自定义 GET 响应（首次装载成功后模拟后续 refresh 的各种失败）
                    return Promise.resolve(options.serverFetch());
                }
                if (typeof options.serverFetch === 'number') {
                    // 数字 = 固定 HTTP 状态（>=400 视为非 ok）
                    return Promise.resolve({
                        ok: options.serverFetch < 400,
                        status: options.serverFetch,
                        json: () => Promise.resolve({})
                    });
                }
                if (options.serverFetch === 'bad-json') {
                    return Promise.resolve({ok: true, json: () => Promise.reject(new Error('bad json'))});
                }
                if (options.serverFetch === '403') {
                    return Promise.resolve({ok: false, status: 403, json: () => Promise.resolve({})});
                }
                if (options.serverFetch === 'pending') {
                    serverFetchGate.pending = true;
                    return new Promise((resolve, reject) => {
                        serverFetchGate.resolve = resolve;
                        serverFetchGate.reject = reject;
                    });
                }
                if (options.serverState !== undefined || serverStateHolder.value !== undefined) {
                    return Promise.resolve({ok: true, json: () => Promise.resolve(defaultServerGetResponse())});
                }
                return Promise.resolve({ok: true, json: () => Promise.resolve({
                    available: true,
                    stateAvailable: false,
                    distinctId: SERVER_SCOPED_ID,
                    submissionId: SUBMISSION_ID,
                    revision: 0,
                    status: null,
                    canShow: false,
                    retryAfterMs: 0,
                    seenLayouts: []
                })});
            }
            if (options.fetch === 'no-version') {
                return Promise.resolve({ok: true, json: () => Promise.resolve({name: 'x'})});
            }
            if (url === publicConfig.apiHost + '/e/') {
                return Promise.resolve({ok: !sandbox.posthog || sandbox.posthog.ackOk !== false});
            }
            return Promise.resolve({ok: true, json: () => Promise.resolve({name: 'x', version: '1.2.3'})});
        },
        PixivFeedback: {
            toast(options) { toastCalls.push(options); }
        },
        PixivBatch: {
            layout: {
                currentLayout() {
                    return html.getAttribute('data-batch-layout');
                }
            }
        },
        Map,
        Set,
        Promise,
        Object,
        Array,
        JSON,
        Date,
        CustomEvent: MiniCustomEvent,
        AbortController,
        URL,
        Uint8Array,
        crypto: webcrypto,
        setTimeout: timers.setTimeout,
        clearTimeout: timers.clearTimeout
    };
    sandbox.PixivLayoutFeedbackOfficialRelease = options.officialRelease !== false;
    sandbox.window = sandbox;
    sandbox.self = sandbox;
    sandbox.addEventListener = windowEvents.addEventListener.bind(windowEvents);
    sandbox.removeEventListener = windowEvents.removeEventListener.bind(windowEvents);
    sandbox.dispatchEvent = windowEvents.dispatchEvent.bind(windowEvents);

    vm.createContext(sandbox);
    vm.runInContext(CONFIG_SOURCE, sandbox, {filename: 'posthog-config.js'});
    if (options.posthogAvailable !== false) {
        vm.runInContext(POSTHOG_SOURCE, sandbox, {filename: 'pixiv-posthog.js'});
    }
    SOURCES.forEach((source, index) => vm.runInContext(source, sandbox, {
        filename: SOURCE_NAMES[index]
    }));

    const api = sandbox.PixivLayoutFeedback;
    return {
        api,
        sandbox,
        document,
        html,
        body,
        storage,
        timers,
        fetchCalls,
        serverPosts,
        serverAbortCalls,
        serverFetchGate,
        serverStateHolder,
        toastCalls,
        consoleWarn,
        windowEvents,
        config: publicConfig,
        stateFetchCount() {
            return getStateFetchCount;
        },
        setServerState(value) {
            serverStateHolder.value = value;
            simulatedView = value && value.status ? {
                status: value.status,
                canShow: value.canShow,
                retryAfterMs: value.retryAfterMs || 0
            } : null;
        },
        dispatchStorage(key, newValue) {
            windowEvents.dispatchEvent({type: 'storage', key, newValue, storageArea: storage});
        },
        dispatchLayoutChanged(layout, previousLayout) {
            document.dispatchEvent({
                type: 'pixiv:batch-layout-changed',
                detail: {layout, previousLayout}
            });
        },
        // 首次下载完成触发：alt 下载引擎在首个作品完成时派发的真实事件形态。
        dispatchFirstDownload() {
            document.dispatchEvent({type: 'pixiv:first-download-completed'});
        },
        dialogRoot() {
            return document.querySelector('.plf-backdrop');
        },
        dialog() {
            return document.querySelector('.plf-dialog');
        },
        radios() {
            return document.querySelectorAll('input[type="radio"]');
        },
        submitButton() {
            const buttons = document.querySelectorAll('button[data-plf-action="submit"]');
            return buttons[0] || null;
        },
        actionButton(action) {
            const buttons = document.querySelectorAll('button[data-plf-action="' + action + '"]');
            return buttons[0] || null;
        },
        textarea() {
            return document.getElementById('plf-suggestion-input');
        },
        counter() {
            return document.querySelector('[data-plf-counter]');
        },
        error() {
            return document.querySelector('[data-plf-error]');
        },
        scriptElements() {
            return scripts.slice();
        },
        fireScriptLoad() {
            scripts.forEach(script => script.dispatchEvent({type: 'load'}));
        },
        fireScriptError() {
            scripts.forEach(script => script.dispatchEvent({type: 'error'}));
        }
    };
}

function initHarness(options) {
    const harness = createHarness(options);
    const adapter = options.adapter === null
        ? null
        : (options.adapter || createFakeAdapter({surveys: options.surveys !== undefined
            ? options.surveys
            : [defaultSurvey()]}));
    const i18n = options.i18n === undefined
        ? createFakeI18n(options.i18nMessages || {})
        : options.i18n;
    harness.sandbox.posthog = adapter;
    harness.api.init({
        page: options.page || 'batch',
        i18n,
        storage: options.storageForInit !== undefined ? options.storageForInit : harness.storage,
        timers: harness.timers,
        fetchImpl: harness.sandbox.fetch.bind(harness.sandbox),
        currentLayoutId: options.currentLayoutId
    });
    harness.adapter = adapter;
    return harness;
}

function defaultSurvey() {
    return {
        id: SURVEY_ID,
        type: 'api',
        start_date: '2026-08-01T00:00:00Z',
        questions: [
            {
                type: 'single_choice',
                id: 'q-layout',
                optional: false,
                choices: [
                    {id: 'pixiv-batch-landscape', label: 'Landscape'},
                    {id: 'pixiv-batch-portrait', label: 'Portrait'},
                    {id: 'pixiv-batch-alt', label: 'Alt'}
                ]
            },
            {
                type: 'open',
                id: 'q-suggestion',
                optional: true
            }
        ]
    };
}

function captureEvents(harness) {
    return (harness.adapter && harness.adapter.calls.capture || []).map(c => c.name)
        .concat(ackEvents(harness).map(event => event.event));
}

function captureProps(harness, name) {
    const call = (harness.adapter.calls.capture || []).find(c => c.name === name);
    if (call) return call.properties;
    const event = ackEvents(harness).find(item => item.event === name);
    return event ? event.properties : null;
}

function ackEvents(harness) {
    return harness.fetchCalls
        .filter(call => call.url === harness.config.apiHost + '/e/' && call.init && call.init.body)
        .map(call => JSON.parse(call.init.body));
}

function waitForFlush() {
    // 冲刷全部微任务（macrotask 之后运行，覆盖 Promise 链与假 adapter 的微任务回调）
    return new Promise(resolve => setImmediate(resolve));
}

function seenSeed() {
    const seen = {};
    LAYOUT_IDS.forEach((id, index) => {
        seen[id] = {firstSeenAt: 1, lastSeenAt: 1 + index};
    });
    return {[SEEN_KEY]: JSON.stringify(seen)};
}

function seenObject() {
    const seen = {};
    LAYOUT_IDS.forEach((id, index) => {
        seen[id] = {firstSeenAt: 1, lastSeenAt: 1 + index};
    });
    return seen;
}

function listenerCountFor(h, type) {
    return ((h.document._events || {}).get(type) || []).length;
}

function reinitOptions(h, adapter) {
    return {
        page: 'alt',
        adapter: adapter === undefined ? h.adapter : adapter,
        i18n: createFakeI18n({}),
        storage: h.storage,
        timers: h.timers,
        fetchImpl: h.sandbox.fetch.bind(h.sandbox)
    };
}

function selectChoice(h, layoutId) {
    const radio = h.radios().find(r => r.value === layoutId);
    if (!radio) throw new Error('radio missing for ' + layoutId);
    radio.checked = true;
    radio.dispatchEvent({type: 'change', target: radio});
    return radio;
}

function submitWithCaptureOverride(override) {
    const h = initHarness({
        adapter: createFakeAdapter({surveys: [defaultSurvey()], capture: override}),
        batchLayout: 'landscape'
    });
    return h.api.open().then(() => waitForFlush()).then(() => {
        selectChoice(h, 'pixiv-batch-landscape');
        h.submitButton().click();
        return waitForFlush();
    }).then(() => h);
}

function crossTabState(stateStatus, snoozedUntil) {
    return JSON.stringify({
        surveyId: SURVEY_ID,
        status: stateStatus,
        updatedAt: 999,
        snoozedUntil: snoozedUntil || 0
    });
}

function hasLoneSurrogates(text) {
    return /[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/.test(text);
}

const SERVER_SCOPED_ID = 'plf_' + 'ab'.repeat(32);

const SERVER_RAW_UUID = '11111111-2222-4333-8444-555555555555';

function serverStateResponse(overrides) {
    return Object.assign({
        available: true,
        stateAvailable: true,
        distinctId: SERVER_SCOPED_ID,
        submissionId: SUBMISSION_ID,
        revision: 0,
        status: null,
        canShow: true,
        retryAfterMs: 0,
        seenLayouts: []
    }, overrides || {});
}

function waitForServerContext(h) {
    // 等待 init 时的服务端上下文装载与微任务链完成
    return waitForFlush();
}

function localStateValue(status, snoozedUntil, surveyId) {
    return JSON.stringify({
        surveyId: surveyId === undefined ? SURVEY_ID : surveyId,
        status,
        updatedAt: 100,
        snoozedUntil: snoozedUntil === undefined ? 0 : snoozedUntil
    });
}

function surveyState(status, snoozedUntil, updatedAt) {
    return {
        surveyId: SURVEY_ID,
        status: status,
        updatedAt: updatedAt === undefined ? 999 : updatedAt,
        snoozedUntil: snoozedUntil === undefined ? 0 : snoozedUntil
    };
}

function submittedView(overrides) {
    return Object.assign({status: 'submitted', canShow: false, retryAfterMs: 0, seenLayouts: []}, overrides || {});
}

function neverView(overrides) {
    return Object.assign({status: 'never', canShow: false, retryAfterMs: 0, seenLayouts: []}, overrides || {});
}

function snoozedView(retryAfterMs, overrides) {
    return Object.assign({
        status: 'snoozed', canShow: false,
        retryAfterMs: retryAfterMs === undefined ? SNOOZE_MS : retryAfterMs,
        seenLayouts: []
    }, overrides || {});
}

function crossTabFallbackMatrix(stateStatus, snoozedUntil, label) {
    const h = initHarness({
        page: 'alt',
        serverState: serverStateResponse({seenLayouts: []})
    });
    let shownBefore = 0;
    return h.api.open().then(() => waitForFlush()).then(() => {
        eq(label + '：弹窗已打开', h.document.querySelectorAll('.plf-backdrop').length, 1);
        shownBefore = captureEvents(h).filter(e => e === 'survey shown').length;
        const incoming = JSON.stringify(surveyState(stateStatus, snoozedUntil));
        h.storage.values.set(STATE_KEY, incoming);
        h.dispatchStorage(STATE_KEY, incoming);
        return waitForFlush();
    }).then(() => {
        const submitted = stateStatus === 'submitted';
        eq(label + '：仅 submitted 关闭弹窗', h.document.querySelectorAll('.plf-backdrop').length,
            submitted ? 0 : 1);
        eq(label + '：不发送 dismissed', captureEvents(h).indexOf('survey dismissed'), -1);
        eq(label + '：localStorage 保留 fallback', JSON.parse(h.storage.getItem(STATE_KEY)).status, stateStatus);
        eq(label + '：pendingLocalState 已合并', h.api._internals.effectiveState().status, stateStatus);
        eq(label + '：仅 submitted 显示已处理提示', h.toastCalls.length, submitted ? 1 : 0);
        // 服务器 refresh 返回旧空状态（SAME / 无变化）：fallback 保留，调查不重新展示。
        h.dispatchFirstDownload();
        return waitForFlush();
    }).then(() => {
        eq(label + '：触发不重复展示', h.document.querySelectorAll('.plf-backdrop').length,
            stateStatus === 'submitted' ? 0 : 1);
        eq(label + '：触发不补发 shown', captureEvents(h).filter(e => e === 'survey shown').length, shownBefore);
    });
}

function snoozeStorageValue(snoozedUntil) {
    return JSON.stringify(surveyState('snoozed', snoozedUntil, 100));
}

function directRefresh(h) {
    return h.api._internals.refreshServerContext(h.api._internals.currentGeneration());
}

function refreshSecond(second) {
    // 状态 GET 序列：第一次返回合法视图（建立 serverBacked），之后返回 second
    // （响应对象 / Promise / 函数）。
    let calls = 0;
    return () => {
        calls++;
        if (calls === 1) {
            return {ok: true, json: () => Promise.resolve(serverStateResponse({seenLayouts: LAYOUT_IDS.slice()}))};
        }
        return typeof second === 'function' ? second(calls) : second;
    };
}

function validFirst(overrides) {
    return () => ({ok: true, json: () => Promise.resolve(serverStateResponse(
        overrides || {seenLayouts: LAYOUT_IDS.slice()}))});
}

function refreshWith(initialOverrides, second) {
    let calls = 0;
    return () => {
        calls++;
        if (calls === 1) {
            return {ok: true, json: () => Promise.resolve(serverStateResponse(initialOverrides))};
        }
        return typeof second === 'function' ? second(calls) : second;
    };
}

function assertFailClosedInvariants(h, label) {
    eq(label + '：不发送 survey sent', captureEvents(h).filter(e => e === 'survey sent').length, 0);
    eq(label + '：弹窗保持打开', h.document.querySelectorAll('.plf-backdrop').length, 1);
    const radio = h.radios().find(r => r.checked);
    eq(label + '：布局选择保留', radio && radio.value, 'pixiv-batch-portrait');
    eq(label + '：textarea 保留', h.textarea().value, ' 保留的建议 ');
    eq(label + '：控件恢复（radio 可用）', radio.disabled, false);
    eq(label + '：控件恢复（textarea 可用）', h.textarea().disabled, false);
    eq(label + '：提交按钮恢复可用', h.submitButton().disabled, false);
    eq(label + '：显示状态验证错误', h.error().hidden, false);
    eq(label + '：错误文案为 error-state-verification', h.error().textContent, '调查状态暂不可用，请稍后重试。');
    eq(label + '：不发送 dismissed', captureEvents(h).indexOf('survey dismissed'), -1);
    eq(label + '：不写 submitted', h.storage.getItem(STATE_KEY) === null, true);
    eq(label + '：不发送 submitted 命令', h.serverPosts.filter(p => p.body.command === 'submitted').length, 0);
}

function openAndPrepareSubmit(h) {
    return h.api.open().then(() => waitForFlush()).then(() => {
        selectChoice(h, 'pixiv-batch-portrait');
        h.textarea().value = ' 保留的建议 ';
        h.textarea().dispatchEvent({type: 'input'});
    });
}

async function runTests(label, entries) {
    const before = passed;
    for (const [name, test] of entries) {
        await test();
        process.stderr.write('STEP-DONE: ' + name + '\n');
    }
    console.log('\n' + label + ': ' + (passed - before) + ' assertions passed ✓');
}

module.exports = {
    CONFIG_SOURCE,
    CONFIG_SOURCE_PATH,
    CONFIG_WINDOW,
    CSS,
    CSS_PATH,
    EMBED_SOURCE,
    EMBED_SOURCE_PATH,
    LAYOUT_IDS,
    MiniClassList,
    MiniCustomEvent,
    MiniElement,
    MiniEventTarget,
    MiniStorage,
    POSTHOG_SOURCE,
    POSTHOG_SOURCE_PATH,
    SEEN_KEY,
    SERVER_RAW_UUID,
    SERVER_SCOPED_ID,
    SNOOZE_MS,
    SOURCE,
    SOURCES,
    SOURCE_NAMES,
    SOURCE_PATHS,
    STATE_KEY,
    SUBMISSION_ID,
    SUGGESTION_MAX,
    SURVEY_ID,
    ackEvents,
    assert,
    assertFailClosedInvariants,
    captureEvents,
    captureProps,
    createFakeAdapter,
    createFakeI18n,
    createFakeTimers,
    createHarness,
    crossTabFallbackMatrix,
    crossTabState,
    dataAttributeName,
    defaultSurvey,
    directRefresh,
    doesNotThrow,
    eq,
    fs,
    hasLoneSurrogates,
    initHarness,
    jsonEq,
    listenerCountFor,
    localStateValue,
    matchSelector,
    neverView,
    ok,
    openAndPrepareSubmit,
    path,
    refreshSecond,
    refreshWith,
    reinitOptions,
    runTests,
    seenObject,
    seenSeed,
    selectChoice,
    serverStateResponse,
    snoozeStorageValue,
    snoozedView,
    submitWithCaptureOverride,
    submittedView,
    surveyState,
    validFirst,
    vm,
    waitForFlush,
    waitForServerContext,
    walkAll
};
