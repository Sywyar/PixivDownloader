'use strict';
/*
 * 下载工作台布局偏好调查（pixiv-layout-feedback.js）运行态契约测试。
 *
 * 无浏览器 / 无 jsdom：用 Node vm + 最小 DOM / EventTarget / localStorage /
 * 可控假定时器 + 注入式 fake PostHog adapter 加载真实生产脚本，覆盖布局映射、
 * schema 校验、事件结构、本地弱去重、before_send 过滤、提交锁与降级矩阵。
 * 自动化测试与生产环境使用同一份业务逻辑。
 *
 * 运行：node pixivdownload-plugin-download-workbench/src/test/js/pixiv-layout-feedback.test.js
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');

const SOURCE_PATH = path.join(__dirname, '..', '..', 'main', 'resources', 'static',
    'pixiv-layout-feedback', 'pixiv-layout-feedback.js');
const CSS_PATH = path.join(__dirname, '..', '..', 'main', 'resources', 'static',
    'pixiv-layout-feedback', 'pixiv-layout-feedback.css');
const SOURCE = fs.readFileSync(SOURCE_PATH, 'utf8');
const CSS = fs.readFileSync(CSS_PATH, 'utf8');

const LAYOUT_IDS = ['pixiv-batch-landscape', 'pixiv-batch-portrait', 'pixiv-batch-alt'];
const STATE_KEY = 'pixiv:layout-feedback:state:v1';
const SEEN_KEY = 'pixiv:layout-feedback:seen:v1';
const SNOOZE_MS = 7 * 24 * 60 * 60 * 1000;
const SUGGESTION_MAX = 1000;

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
function doesNotThrow(label, action) {
    assert.doesNotThrow(action, label);
    passed++;
}

/* ============================================================
   最小 DOM 基础设施
============================================================ */

class MiniEventTarget {
    constructor() {
        this.listeners = new Map();
    }
    addEventListener(type, listener) {
        if (typeof listener !== 'function') return;
        const list = this.listeners.get(type) || [];
        if (list.indexOf(listener) >= 0) return;
        list.push(listener);
        this.listeners.set(type, list);
    }
    removeEventListener(type, listener) {
        const list = this.listeners.get(type) || [];
        this.listeners.set(type, list.filter(item => item !== listener));
    }
    dispatchEvent(event) {
        if (!event || !event.type) throw new Error('event.type is required');
        if (typeof event.preventDefault !== 'function') {
            event.preventDefault = function () { this.defaultPrevented = true; };
        }
        if (event.target === undefined) event.target = this;
        event.currentTarget = this;
        (this.listeners.get(event.type) || []).slice().forEach(listener => listener.call(this, event));
        return !event.defaultPrevented;
    }
    listenerCount(type) {
        return (this.listeners.get(type) || []).length;
    }
}

class MiniClassList {
    constructor(element) {
        this.element = element;
    }
    _set(tokens) {
        this.element.setAttribute('class', tokens.join(' '));
    }
    _tokens() {
        const raw = this.element.getAttribute('class') || '';
        return raw.split(/\s+/).filter(Boolean);
    }
    add(...names) {
        const tokens = this._tokens();
        names.forEach(name => { if (tokens.indexOf(name) < 0) tokens.push(name); });
        this._set(tokens);
    }
    remove(...names) {
        this._set(this._tokens().filter(name => names.indexOf(name) < 0));
    }
    toggle(name, force) {
        const tokens = this._tokens();
        const has = tokens.indexOf(name) >= 0;
        const want = force === undefined ? !has : !!force;
        if (want && !has) { tokens.push(name); this._set(tokens); }
        if (!want && has) { this._set(tokens.filter(t => t !== name)); }
        return want;
    }
    contains(name) {
        return this._tokens().indexOf(name) >= 0;
    }
}

function dataAttributeName(property) {
    return 'data-' + String(property).replace(/[A-Z]/g, ch => '-' + ch.toLowerCase());
}

class MiniElement extends MiniEventTarget {
    constructor(tagName, ownerDocument) {
        super();
        this.tagName = String(tagName).toUpperCase();
        this.attributes = {};
        this.children = [];
        this.parentNode = null;
        this.ownerDocument = ownerDocument || null;
        this.hidden = false;
        this.disabled = false;
        this.checked = false;
        this.type = '';
        this.name = '';
        this.value = '';
        this.placeholder = '';
        this.rows = 0;
        this.tabIndex = 0;
        this._textContent = '';
        this.style = {};
        this.classList = new MiniClassList(this);
        this.dataset = new Proxy({}, {
            get: (_, property) => this.getAttribute(dataAttributeName(property)),
            set: (_, property, value) => {
                this.setAttribute(dataAttributeName(property), value);
                return true;
            }
        });
    }
    setAttribute(name, value) {
        this.attributes[name] = String(value);
    }
    getAttribute(name) {
        return Object.prototype.hasOwnProperty.call(this.attributes, name) ? this.attributes[name] : null;
    }
    hasAttribute(name) {
        return Object.prototype.hasOwnProperty.call(this.attributes, name);
    }
    removeAttribute(name) {
        delete this.attributes[name];
    }
    get id() { return this.getAttribute('id') || ''; }
    set id(value) { this.setAttribute('id', value); }
    get className() { return this.getAttribute('class') || ''; }
    set className(value) { this.setAttribute('class', value); }
    get type() { return this.getAttribute('type') || ''; }
    set type(value) { this.setAttribute('type', value); }
    get name() { return this.getAttribute('name') || ''; }
    set name(value) { this.setAttribute('name', value); }
    get value() { return this.getAttribute('value') || ''; }
    set value(value) { this.setAttribute('value', value); }
    get placeholder() { return this.getAttribute('placeholder') || ''; }
    set placeholder(value) { this.setAttribute('placeholder', value); }
    get rows() { return Number(this.getAttribute('rows') || 0); }
    set rows(value) { this.setAttribute('rows', value); }
    get textContent() { return this._textContent; }
    set textContent(value) { this._textContent = value == null ? '' : String(value); }
    get nextSibling() {
        if (!this.parentNode) return null;
        const index = this.parentNode.children.indexOf(this);
        return index >= 0 ? this.parentNode.children[index + 1] || null : null;
    }
    get nextElementSibling() { return this.nextSibling; }
    detachChild(child) {
        if (!child.parentNode) return;
        const oldParent = child.parentNode;
        const index = oldParent.children.indexOf(child);
        if (index >= 0) oldParent.children.splice(index, 1);
        if (this.ownerDocument && this.ownerDocument.activeElement === child) {
            this.ownerDocument.activeElement = null;
        }
        child.parentNode = null;
    }
    appendChild(child) {
        this.detachChild(child);
        child.parentNode = this;
        child.ownerDocument = this.ownerDocument;
        this.children.push(child);
        return child;
    }
    insertBefore(child, reference) {
        if (reference === child) return child;
        if (reference != null && reference.parentNode !== this) throw new Error('reference is not a child');
        this.detachChild(child);
        const index = reference == null ? this.children.length : this.children.indexOf(reference);
        child.parentNode = this;
        child.ownerDocument = this.ownerDocument;
        this.children.splice(index, 0, child);
        return child;
    }
    removeChild(child) {
        if (child.parentNode !== this) throw new Error('child is not attached');
        this.detachChild(child);
        return child;
    }
    matches(selector) {
        return matchSelector(this, selector);
    }
    dispatchEvent(event) {
        if (!event || !event.type) throw new Error('event.type is required');
        if (typeof event.preventDefault !== 'function') {
            event.preventDefault = function () { this.defaultPrevented = true; };
        }
        if (event.target === undefined) event.target = this;
        if (typeof event.stopPropagation !== 'function') {
            event.stopPropagation = function () { this.propagationStopped = true; };
        }
        let node = this;
        while (node) {
            event.currentTarget = node;
            (node.listeners.get(event.type) || []).slice().forEach(listener => listener.call(node, event));
            if (event.propagationStopped) break;
            node = node.parentNode;
        }
        return !event.defaultPrevented;
    }
    closest(selector) {
        let node = this;
        while (node) {
            if (node.matches && node.matches(selector)) return node;
            node = node.parentNode;
        }
        return null;
    }
    querySelector(selector) {
        return this.querySelectorAll(selector)[0] || null;
    }
    querySelectorAll(selector) {
        const found = [];
        (function walk(node) {
            for (const child of node.children) {
                if (child.matches(selector)) found.push(child);
                walk(child);
            }
        })(this);
        return found;
    }
    click() {
        this.dispatchEvent({type: 'click', defaultPrevented: false});
    }
    focus() {
        if (this.ownerDocument) this.ownerDocument.activeElement = this;
    }
    contains(node) {
        let current = node;
        while (current) {
            if (current === this) return true;
            current = current.parentNode;
        }
        return false;
    }
}

function matchSelector(element, selector) {
    const parts = [];
    let rest = selector.trim();
    while (rest.length > 0) {
        const token = /^([a-zA-Z][a-zA-Z0-9-]*)/.exec(rest);
        const dot = /^\.([a-zA-Z0-9_-]+)/.exec(rest);
        const hash = /^#([a-zA-Z0-9_-]+)/.exec(rest);
        const attr = /^\[([a-zA-Z0-9_-]+)(?:="([^"]*)")?\]/.exec(rest);
        const not = /^:not\(\[([a-zA-Z0-9_-]+)\]\)/.exec(rest);
        if (token) {
            parts.push({kind: 'tag', value: token[1].toUpperCase()});
            rest = rest.slice(token[0].length);
        } else if (dot) {
            parts.push({kind: 'class', value: dot[1]});
            rest = rest.slice(dot[0].length);
        } else if (hash) {
            parts.push({kind: 'id', value: hash[1]});
            rest = rest.slice(hash[0].length);
        } else if (attr) {
            parts.push({kind: 'attr', name: attr[1], value: attr[2]});
            rest = rest.slice(attr[0].length);
        } else if (not) {
            parts.push({kind: 'notAttr', name: not[1]});
            rest = rest.slice(not[0].length);
        } else {
            rest = rest.slice(1);
        }
    }
    return parts.every(part => {
        if (part.kind === 'tag') return element.tagName === part.value;
        if (part.kind === 'class') {
            return (element.getAttribute('class') || '').split(/\s+/).filter(Boolean).indexOf(part.value) >= 0;
        }
        if (part.kind === 'id') return element.id === part.value;
        if (part.kind === 'attr') {
            if (!element.hasAttribute(part.name)) return false;
            return part.value === undefined || element.getAttribute(part.name) === part.value;
        }
        if (part.kind === 'notAttr') return !element.hasAttribute(part.name);
        return false;
    });
}

class MiniStorage {
    constructor(seed) {
        this.values = new Map(Object.entries(seed || {}));
        this.throwOnGet = false;
        this.throwOnSet = false;
        this.throwOnRemove = false;
        this.getCalls = [];
        this.setCalls = [];
        this.removeCalls = [];
    }
    getItem(key) {
        this.getCalls.push(String(key));
        if (this.throwOnGet) throw new Error('getItem failed');
        return this.values.has(String(key)) ? this.values.get(String(key)) : null;
    }
    setItem(key, value) {
        this.setCalls.push([String(key), String(value)]);
        if (this.throwOnSet) throw new Error('setItem failed');
        this.values.set(String(key), String(value));
    }
    removeItem(key) {
        this.removeCalls.push(String(key));
        if (this.throwOnRemove) throw new Error('removeItem failed');
        this.values.delete(String(key));
    }
}

class MiniCustomEvent {
    constructor(type, options) {
        this.type = type;
        this.detail = options && options.detail;
        this.defaultPrevented = false;
    }
    preventDefault() { this.defaultPrevented = true; }
    stopImmediatePropagation() {}
    stopPropagation() {}
}

function createFakeTimers() {
    let now = 1000000;
    let nextId = 1;
    const queue = [];
    return {
        now: () => now,
        setTimeout(fn, ms) {
            const id = nextId++;
            queue.push({id, at: now + Math.max(0, Number(ms) || 0), fn});
            return id;
        },
        clearTimeout(id) {
            const index = queue.findIndex(t => t.id === id);
            if (index >= 0) queue.splice(index, 1);
        },
        advance(ms) {
            const target = now + ms;
            let guard = 0;
            // 按到期顺序逐个触发，触发前把 now 推进到该定时器的到期时刻，
            // 使回调内新注册的定时器按真实语义（当前时间 + 延迟）计算到期点。
            while (true) {
                const next = queue.filter(t => t.at <= target).sort((a, b) => a.at - b.at)[0];
                if (!next) break;
                const index = queue.indexOf(next);
                queue.splice(index, 1);
                now = next.at;
                next.fn();
                if (++guard > 1000) throw new Error('fake timer runaway');
            }
            now = target;
        },
        pending() {
            return queue.slice();
        }
    };
}

function createFakeI18n(messages) {
    const client = {
        lang: 'zh-CN',
        t(key, fallback) {
            return messages && Object.prototype.hasOwnProperty.call(messages, key)
                ? messages[key]
                : fallback;
        },
        apply(root) {
            walkAll(root, element => {
                const key = element.getAttribute && element.getAttribute('data-i18n');
                if (key) {
                    element.textContent = client.t(key, element.textContent);
                }
                const placeholderKey = element.getAttribute && element.getAttribute('data-i18n-placeholder');
                if (placeholderKey) {
                    element.placeholder = client.t(placeholderKey, element.placeholder);
                }
                const ariaKey = element.getAttribute && element.getAttribute('data-i18n-aria-label');
                if (ariaKey) {
                    element.setAttribute('aria-label', client.t(ariaKey, element.getAttribute('aria-label')));
                }
            });
        }
    };
    return client;
}

function walkAll(root, fn) {
    fn(root);
    (root.children || []).forEach(child => walkAll(child, fn));
}

function createFakeAdapter(overrides) {
    overrides = overrides || {};
    const calls = {init: [], capture: [], onFeatureFlags: [], getSurveys: [], results: []};
    let flagsListener = null;
    let sdkConfig = null;
    let sdkDistinctId = null;
    const adapter = {
        calls,
        surveys: overrides.surveys || [],
        lastSurveyCallback: null,
        sdkConfig() { return sdkConfig; },
        // 真实 posthog-js 1.409.5 语义：当前 SDK distinct ID。
        get_distinct_id() {
            return overrides.distinctId !== undefined ? overrides.distinctId : sdkDistinctId;
        },
        emitFlags() {
            if (flagsListener) flagsListener();
        },
        init(token, config) {
            calls.init.push({token, config});
            sdkConfig = config;
            // 真实 1.409.5：bootstrap.distinctID（isIdentifiedID=false 时走匿名
            // register 分支）设置 SDK distinct ID；sdkConfig.distinct_id 不参与初始化。
            sdkDistinctId = null;
            if (config && config.bootstrap
                    && typeof config.bootstrap.distinctID === 'string') {
                sdkDistinctId = config.bootstrap.distinctID;
            }
        },
        capture(name, properties) {
            calls.capture.push({name, properties});
            if (overrides.capture === 'throw') throw new Error('capture failed');
            if (overrides.capture === 'undefined') return undefined;
            if (overrides.capture === 'null') return null;
            if (overrides.capture === 'false') return false;
            if (overrides.capture === 'reject') return undefined;
            // 真实 posthog-js 1.409.5 语义：capture 构造 CaptureResult 事件对象，
            // timestamp 为 Date（b.timestamp = (options.timestamp) || new Date()），
            // 并把当前 SDK distinct_id 写入 properties，
            // before_send 返回 null/undefined 时事件被 SDK 丢弃（capture 返回 undefined）。
            // 测试可通过 overrides.timestamp 覆盖 timestamp（Date / ISO string /
            // null / undefined / 非法对象），hasOwnProperty 用于区分「未提供」与
            // 「显式提供 undefined」。
            const event = {
                uuid: 'evt-' + String(calls.capture.length),
                event: name,
                timestamp: Object.prototype.hasOwnProperty.call(overrides, 'timestamp')
                    ? overrides.timestamp
                    : new Date(),
                properties: Object.assign({}, properties || {})
            };
            if (sdkDistinctId) {
                event.properties.distinct_id = sdkDistinctId;
            }
            if (sdkConfig && typeof sdkConfig.before_send === 'function') {
                const filtered = sdkConfig.before_send(event);
                calls.results.push(filtered);
                if (!filtered) return undefined;
                return filtered;
            }
            calls.results.push(event);
            return event;
        },
        has_opted_out_capturing() {
            return !!overrides.optedOut;
        },
        is_capturing() {
            if (overrides.isCapturing !== undefined) return !!overrides.isCapturing;
            return !overrides.optedOut;
        },
        onFeatureFlags(cb) {
            calls.onFeatureFlags.push(cb);
            flagsListener = cb;
            if (overrides.syncFlagsCallback) {
                // 真实 SDK 竞态：flags 已加载时同步调用 callback，然后才返回 off。
                cb();
                return function off() {
                    calls.offCalls = (calls.offCalls || 0) + 1;
                    if (flagsListener === cb) flagsListener = null;
                };
            }
            if (!overrides.stallFlags) {
                Promise.resolve().then(() => {
                    if (flagsListener === cb) cb();
                });
            }
            return function off() {
                calls.offCalls = (calls.offCalls || 0) + 1;
                if (flagsListener === cb) flagsListener = null;
            };
        },
        getActiveMatchingSurveys(cb, forceReload) {
            calls.getSurveys.push({forceReload});
            adapter.lastSurveyCallback = cb;
            if (!overrides.stallSurveys) cb(adapter.surveys || []);
        }
    };
    return adapter;
}

/* ============================================================
   Harness：加载真实生产脚本
============================================================ */

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
    const timers = createFakeTimers();
    const fetchCalls = [];
    const toastCalls = [];
    const consoleWarn = [];
    const scripts = [];
    const serverPosts = [];
    const serverAbortCalls = [];
    const serverFetchGate = {pending: false, resolve: null, reject: null};
    const serverStateHolder = {value: options.serverState};
    let getStateFetchCount = 0;

    const defaultConfig = {
        enabled: true,
        projectToken: 'phc_test_project_token',
        surveyId: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
        apiHost: 'https://proxy.example.com',
        uiHost: 'https://us.i.posthog.com'
    };
    const publicConfig = Object.assign({}, defaultConfig, options.publicConfig || {});

    // 服务端模拟的「当前权威 state」：状态命令应用后携带到后续 POST / 快照响应
    //（record_seen 等命令的快照必须保留既有 state，与真实服务端一致）。
    let simulatedState = (options.serverState && options.serverState.state) || null;

    function serverSnapshotFromBody(body) {
        // 默认 POST 响应：像真实服务端一样基于当前权威状态合并命令结果（revision 递增）。
        // serverTime 用模拟服务端当前时间（默认与客户端假时钟一致，无偏差；
        // 偏差场景由测试通过自定义 serverPostResponse 显式覆盖）。
        const current = defaultServerGetResponse();
        const snapshot = {
            available: true,
            stateAvailable: true,
            distinctId: current.distinctId,
            serverTime: timers.now(),
            revision: (typeof body.expectedRevision === 'number' ? body.expectedRevision : 0) + 1,
            state: simulatedState,
            seen: Object.assign({}, current.seen)
        };
        if (body.command === 'submitted') {
            simulatedState = {
                surveyId: body.surveyId,
                status: 'submitted',
                updatedAt: timers.now(),
                snoozedUntil: 0
            };
            snapshot.state = simulatedState;
        } else if (body.command === 'never') {
            if (!simulatedState || decisionRank('never') > decisionRank(simulatedState.status)) {
                simulatedState = {
                    surveyId: body.surveyId,
                    status: 'never',
                    updatedAt: timers.now(),
                    snoozedUntil: 0
                };
            }
            snapshot.state = simulatedState;
        } else if (body.command === 'snooze') {
            // 与真实服务端一致：snooze 使用新的服务端时间（同等级也刷新，不倒退）。
            if (!simulatedState || decisionRank('snoozed') >= decisionRank(simulatedState.status)) {
                simulatedState = {
                    surveyId: body.surveyId,
                    status: 'snoozed',
                    updatedAt: timers.now(),
                    snoozedUntil: timers.now() + SNOOZE_MS
                };
            }
            snapshot.state = simulatedState;
        }
        if (body.command === 'record_seen' && Array.isArray(body.layoutIds)) {
            body.layoutIds.forEach(id => {
                snapshot.seen[id] = {firstSeenAt: timers.now(), lastSeenAt: timers.now()};
            });
        }
        return snapshot;
    }

    function decisionRank(status) {
        if (status === 'submitted') return 3;
        if (status === 'never') return 2;
        if (status === 'snoozed') return 1;
        return 0;
    }

    function defaultServerGetResponse() {
        const base = {
            available: true,
            stateAvailable: true,
            distinctId: options.serverScopedId !== undefined ? options.serverScopedId : SERVER_SCOPED_ID,
            serverTime: timers.now(),
            revision: 0,
            state: null,
            seen: {}
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
                return Promise.resolve({ok: true, json: () => Promise.resolve({available: false})});
            }
            if (options.fetch === 'no-version') {
                return Promise.resolve({ok: true, json: () => Promise.resolve({name: 'x'})});
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
        AbortController
    };
    sandbox.window = sandbox;
    sandbox.self = sandbox;
    sandbox.addEventListener = windowEvents.addEventListener.bind(windowEvents);
    sandbox.removeEventListener = windowEvents.removeEventListener.bind(windowEvents);
    sandbox.dispatchEvent = windowEvents.dispatchEvent.bind(windowEvents);

    vm.createContext(sandbox);
    sandbox.PixivLayoutFeedbackPublicConfig = Object.freeze(publicConfig);
    vm.runInContext(SOURCE, sandbox, {filename: 'pixiv-layout-feedback.js'});

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
            simulatedState = (value && value.state) || null;
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
    harness.api.init({
        page: options.page || 'batch',
        adapter: adapter || null,
        i18n,
        storage: options.storageForInit !== undefined ? options.storageForInit : harness.storage,
        timers: harness.timers,
        fetchImpl: harness.sandbox.fetch.bind(harness.sandbox),
        minDistinctLayoutsSeen: options.minDistinct !== undefined ? options.minDistinct : 2,
        autoDelayMs: options.autoDelayMs !== undefined ? options.autoDelayMs : 10000
    });
    harness.adapter = adapter;
    return harness;
}

function defaultSurvey() {
    return {
        id: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
        type: 'api',
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
    return (harness.adapter && harness.adapter.calls.capture || []).map(c => c.name);
}

function captureProps(harness, name) {
    const call = (harness.adapter.calls.capture || []).find(c => c.name === name);
    return call ? call.properties : null;
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
        page: 'batch',
        adapter: adapter === undefined ? h.adapter : adapter,
        i18n: createFakeI18n({}),
        storage: h.storage,
        timers: h.timers,
        fetchImpl: h.sandbox.fetch.bind(h.sandbox),
        minDistinctLayoutsSeen: 2,
        autoDelayMs: 10000
    };
}

/* ============================================================
   布局映射（1-4）
============================================================ */

function testLayoutMapping() {
    let h = initHarness({page: 'batch', batchLayout: 'landscape'});
    eq('landscape 映射为稳定 ID', h.api.currentLayoutId(), 'pixiv-batch-landscape');

    h = initHarness({page: 'batch', batchLayout: 'portrait'});
    eq('portrait 映射为稳定 ID', h.api.currentLayoutId(), 'pixiv-batch-portrait');

    h = initHarness({page: 'alt'});
    eq('alt 页面固定返回 pixiv-batch-alt', h.api.currentLayoutId(), 'pixiv-batch-alt');

    h = initHarness({page: 'batch', batchLayout: 'landscape'});
    h.document.documentElement.setAttribute('data-batch-layout', 'portrait');
    eq('dataset 变化后 currentLayoutId 跟随', h.api.currentLayoutId(), 'pixiv-batch-portrait');

    ok('模块不使用物理屏幕方向 API',
        SOURCE.indexOf('matchMedia') < 0
        && SOURCE.indexOf('orientation') < 0
        && SOURCE.indexOf('screen.orientation') < 0);
    ok('模块不使用宽高比判断', SOURCE.indexOf('aspect-ratio') < 0 && SOURCE.indexOf('innerWidth') < 0);
}

/* ============================================================
   init / destroy（5-7）
============================================================ */

function testInitDestroy() {
    const h = initHarness({});
    const before = (h.document._events && h.document._events.get('pixiv:batch-layout-changed') || []).length;
    const storageBefore = h.windowEvents.listenerCount('storage');
    doesNotThrow('init 幂等', () => h.api.init({page: 'batch'}));
    const after = (h.document._events && h.document._events.get('pixiv:batch-layout-changed') || []).length;
    eq('init 重复调用不重复注册监听', after, before);
    eq('storage 监听不重复', h.windowEvents.listenerCount('storage'), storageBefore);

    doesNotThrow('destroy 安全', () => h.api.destroy());
    doesNotThrow('destroy 可重复调用', () => h.api.destroy());

    const afterDestroy = (h.document._events && h.document._events.get('pixiv:batch-layout-changed') || []).length;
    eq('destroy 移除布局监听', afterDestroy, 0);
    eq('destroy 移除 storage 监听', h.windowEvents.listenerCount('storage'), 0);

    doesNotThrow('destroy 后可重新 init', () => h.api.init({page: 'batch'}));
}

function testSingleDialogAtMostOne() {
    const h = initHarness({});
    return h.api.open().then(() => waitForFlush()).then(() => {
        eq('open 后恰好一个弹窗', h.document.querySelectorAll('.plf-backdrop').length, 1);
        return h.api.open().then(() => waitForFlush());
    }).then(() => {
        eq('重复 open 不创建第二个弹窗', h.document.querySelectorAll('.plf-backdrop').length, 1);
    });
}

/* ============================================================
   schema 校验与调查匹配（14-20）
============================================================ */

function testChoiceSchemaVariants() {
    const internals = initHarness({}).api._internals;

    const valid = defaultSurvey();
    ok('合法单选用例通过', internals.resolveChoiceQuestion(valid) !== null);

    const noId = defaultSurvey();
    noId.questions[0].id = '';
    eq('id 空则无效', internals.resolveChoiceQuestion(noId), null);

    const wrongType = defaultSurvey();
    wrongType.questions[0].type = 'multiple_choice';
    eq('type 非 single_choice 无效', internals.resolveChoiceQuestion(wrongType), null);

    const optional = defaultSurvey();
    optional.questions[0].optional = true;
    eq('optional=true 无效', internals.resolveChoiceQuestion(optional), null);

    const missingChoice = defaultSurvey();
    missingChoice.questions[0].choices = [
        {id: 'pixiv-batch-landscape'},
        {id: 'pixiv-batch-portrait'}
    ];
    eq('choices 缺 ID 无效', internals.resolveChoiceQuestion(missingChoice), null);

    const extraChoice = defaultSurvey();
    extraChoice.questions[0].choices.push({id: 'pixiv-batch-unknown'});
    eq('choices 多出未知选项无效', internals.resolveChoiceQuestion(extraChoice), null);

    const shuffled = defaultSurvey();
    shuffled.questions[0].choices = shuffled.questions[0].choices.slice().reverse();
    ok('不依赖后台选项顺序', internals.resolveChoiceQuestion(shuffled) !== null);

    const stringChoices = defaultSurvey();
    stringChoices.questions[0].choices = ['pixiv-batch-alt', 'pixiv-batch-landscape', 'pixiv-batch-portrait'];
    ok('choices 字符串数组兼容', internals.resolveChoiceQuestion(stringChoices) !== null);

    const duplicate = defaultSurvey();
    duplicate.questions.push(JSON.parse(JSON.stringify(duplicate.questions[0])));
    eq('多个匹配单选题视为 schema 歧义', internals.resolveChoiceQuestion(duplicate), null);
}

function testSuggestionSchemaVariants() {
    const internals = initHarness({}).api._internals;

    eq('缺失建议题返回 q-suggestion', internals.resolveSuggestionQuestion(defaultSurvey()).id, 'q-suggestion');
    const noSuggestion = defaultSurvey();
    noSuggestion.questions = [noSuggestion.questions[0]];
    eq('第二题缺失返回 null', internals.resolveSuggestionQuestion(noSuggestion), null);

    const openText = defaultSurvey();
    openText.questions[1].type = 'open_text';
    eq('open_text 兼容', internals.resolveSuggestionQuestion(openText).id, 'q-suggestion');

    const required = defaultSurvey();
    required.questions[1].optional = false;
    eq('optional=false 视为异常不显示 textarea', internals.resolveSuggestionQuestion(required), null);

    const ambiguous = defaultSurvey();
    ambiguous.questions.push({type: 'open', id: 'q-other', optional: true});
    eq('多个开放题不猜测', internals.resolveSuggestionQuestion(ambiguous), null);

    const noIdSuggestion = defaultSurvey();
    noIdSuggestion.questions[1].id = '';
    eq('建议题 id 为空视为缺失', internals.resolveSuggestionQuestion(noIdSuggestion), null);
}

function testSurveyNotFoundOrHidden() {
    const h = initHarness({surveys: []});
    return h.api.open().then(() => waitForFlush()).then(() => {
        eq('无目标调查时不展示', h.document.querySelectorAll('.plf-backdrop').length, 0);
    }).then(() => {
        const h2 = initHarness({surveys: [Object.assign({}, defaultSurvey(), {id: 'other-id'})]});
        return h2.api.open().then(() => waitForFlush()).then(() => {
            eq('surveyId 不匹配不展示', h2.document.querySelectorAll('.plf-backdrop').length, 0);
        });
    }).then(() => {
        const h3 = initHarness({surveys: [Object.assign({}, defaultSurvey(), {type: 'popover'})]});
        return h3.api.open().then(() => waitForFlush()).then(() => {
            eq('type 非 api 不展示', h3.document.querySelectorAll('.plf-backdrop').length, 0);
        });
    });
}

/* ============================================================
   事件结构（14-15, 21-27, 33-34）
============================================================ */

function testSurveyShownOncePerDialog() {
    const h = initHarness({batchLayout: 'landscape'});
    return h.api.open().then(() => waitForFlush()).then(() => {
        eq('shown 发送一次', captureEvents(h).filter(e => e === 'survey shown').length, 1);
        h.api.refreshLanguage(createFakeI18n({}));
        h.api.refreshLanguage(createFakeI18n({}));
        eq('语言切换不重复 shown', captureEvents(h).filter(e => e === 'survey shown').length, 1);
        const shown = captureProps(h, 'survey shown');
        eq('shown 带 $survey_id', shown['$survey_id'], h.config.surveyId);
        eq('shown 带 survey_schema_version', shown.survey_schema_version, '1');
        eq('shown 带 current_layout', shown.current_layout, 'pixiv-batch-landscape');
        eq('shown 带 app_version', shown.app_version, '1.2.3');
    });
}

function selectChoice(h, layoutId) {
    const radio = h.radios().find(r => r.value === layoutId);
    if (!radio) throw new Error('radio missing for ' + layoutId);
    radio.checked = true;
    radio.dispatchEvent({type: 'change', target: radio});
    return radio;
}

function testSubmitSendsOnceWithQuestionId() {
    const h = initHarness({batchLayout: 'landscape'});
    return h.api.open().then(() => waitForFlush()).then(() => {
        selectChoice(h, 'pixiv-batch-portrait');
        const submit = h.submitButton();
        eq('选择后提交按钮可用', submit.disabled, false);
        submit.click();
        submit.click();
        submit.dispatchEvent({type: 'click'});
        return waitForFlush();
    }).then(() => {
        const sent = captureEvents(h).filter(e => e === 'survey sent');
        eq('双击 / 重复触发只发一次 survey sent', sent.length, 1);
        const props = captureProps(h, 'survey sent');
        eq('回答属性使用 question.id', props['$survey_response_q-layout'], 'pixiv-batch-portrait');
        ok('回答属性不以数组位置构造', Object.keys(props).every(k => k !== '$survey_response' && k !== '$survey_response_1'));
        eq('sent 带 $survey_id', props['$survey_id'], h.config.surveyId);
        eq('sent 带 app_version', props.app_version, '1.2.3');
        eq('sent 带 current_layout 快照', props.current_layout, 'pixiv-batch-landscape');
        const state = JSON.parse(h.storage.getItem(STATE_KEY));
        eq('提交后写 submitted', state.status, 'submitted');
        eq('submitted 绑定 surveyId', state.surveyId, h.config.surveyId);
        eq('提交后关闭弹窗', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('提交后 toast 成功', h.toastCalls.length, 1);
    });
}

function testThreeLayoutsSubmit() {
    const promises = LAYOUT_IDS.map(layoutId => {
        const h = initHarness({batchLayout: 'landscape'});
        return h.api.open().then(() => waitForFlush()).then(() => {
            selectChoice(h, layoutId);
            h.submitButton().click();
            return waitForFlush();
        }).then(() => {
            const props = captureProps(h, 'survey sent');
            eq('稳定 ID ' + layoutId + ' 可提交', props['$survey_response_q-layout'], layoutId);
        });
    });
    return Promise.all(promises);
}

function testSuggestionHandling() {
    return Promise.resolve().then(() => {
        const h1 = initHarness({batchLayout: 'landscape'});
        return h1.api.open().then(() => waitForFlush()).then(() => {
            selectChoice(h1, 'pixiv-batch-landscape');
            h1.textarea().value = '  信息密度可以更高  ';
            h1.textarea().dispatchEvent({type: 'input'});
            h1.submitButton().click();
            return waitForFlush();
        }).then(() => {
            const props = captureProps(h1, 'survey sent');
            eq('建议 trim 后发送', props['$survey_response_q-suggestion'], '信息密度可以更高');
        });
    }).then(() => {
        const h2 = initHarness({batchLayout: 'landscape'});
        return h2.api.open().then(() => waitForFlush()).then(() => {
            selectChoice(h2, 'pixiv-batch-alt');
            h2.textarea().value = '   ';
            h2.textarea().dispatchEvent({type: 'input'});
            h2.submitButton().click();
            return waitForFlush();
        }).then(() => {
            const props = captureProps(h2, 'survey sent');
            ok('纯空白建议不发送第二题属性', Object.keys(props).every(k => k.indexOf('$survey_response_q-suggestion') !== 0));
            ok('不发送空字符串', props['$survey_response_q-suggestion'] === undefined);
        });
    }).then(() => {
        const h3 = initHarness({batchLayout: 'landscape'});
        return h3.api.open().then(() => waitForFlush()).then(() => {
            ok('不再使用 UTF-16 语义的原生 maxlength', h3.textarea().getAttribute('maxlength') === null);
            selectChoice(h3, 'pixiv-batch-landscape');
            const emoji = '\ud83d\ude00';
            h3.textarea().value = emoji.repeat(500);
            h3.textarea().dispatchEvent({type: 'input'});
            eq('代理对按 code point 计数', h3.counter().textContent.split(' ')[0], '500');
            h3.submitButton().click();
            return waitForFlush();
        }).then(() => {
            const props = captureProps(h3, 'survey sent');
            ok('1000 个 code point 内可提交', props['$survey_response_q-suggestion'] !== undefined);
        });
    }).then(() => {
        const h4 = initHarness({batchLayout: 'landscape'});
        return h4.api.open().then(() => waitForFlush()).then(() => {
            selectChoice(h4, 'pixiv-batch-landscape');
            h4.textarea().value = 'a'.repeat(1001);
            h4.textarea().dispatchEvent({type: 'input'});
            eq('input 事件把 1001 个字符截断为 1000', h4.textarea().value.length, 1000);
            eq('截断后计数器显示 1000', h4.counter().textContent.split(' ')[0], '1000');
            h4.submitButton().click();
            return waitForFlush();
        }).then(() => {
            const props = captureProps(h4, 'survey sent');
            eq('截断后按 1000 code point 提交', props['$survey_response_q-suggestion'], 'a'.repeat(1000));
        });
    });
}

function testSuggestionMissingHidesTextarea() {
    const survey = defaultSurvey();
    survey.questions = [survey.questions[0]];
    const h = initHarness({surveys: [survey], batchLayout: 'landscape'});
    return h.api.open().then(() => waitForFlush()).then(() => {
        eq('第二题缺失时不显示 textarea', h.textarea() === null || h.textarea().parentNode.hidden, true);
        selectChoice(h, 'pixiv-batch-landscape');
        h.submitButton().click();
        return waitForFlush();
    }).then(() => {
        const props = captureProps(h, 'survey sent');
        ok('第二题缺失时只收集布局选择', Object.keys(props).some(k => k === '$survey_response_q-layout'));
    });
}

function testNoChoiceCannotSubmit() {
    const h = initHarness({batchLayout: 'landscape'});
    return h.api.open().then(() => waitForFlush()).then(() => {
        eq('未选择前提交按钮禁用', h.submitButton().disabled, true);
        h.submitButton().click();
        return waitForFlush();
    }).then(() => {
        eq('未选择不发送 survey sent', captureEvents(h).filter(e => e === 'survey sent').length, 0);
        eq('未选择显示错误', h.error().hidden, false);
    });
}

function testSnoozeNeverDismissSemantics() {
    return Promise.resolve().then(() => {
        const h = initHarness({batchLayout: 'landscape'});
        return h.api.open().then(() => waitForFlush()).then(() => {
            h.actionButton('snooze').click();
            const state = JSON.parse(h.storage.getItem(STATE_KEY));
            eq('稍后再说写 snoozed', state.status, 'snoozed');
            ok('snoozedUntil 约为 7 天后', Math.abs(state.snoozedUntil - (h.timers.now() + SNOOZE_MS)) < 2000);
            eq('稍后再说不发送 dismissed', captureEvents(h).indexOf('survey dismissed'), -1);
            eq('稍后再说关闭弹窗', h.document.querySelectorAll('.plf-backdrop').length, 0);
        });
    }).then(() => {
        const h = initHarness({batchLayout: 'landscape'});
        return h.api.open().then(() => waitForFlush()).then(() => {
            h.actionButton('never').click();
            return waitForFlush();
        }).then(() => {
            const state = JSON.parse(h.storage.getItem(STATE_KEY));
            eq('不再询问写 never', state.status, 'never');
            eq('不再询问发送 dismissed', captureEvents(h).indexOf('survey dismissed') >= 0, true);
        });
    });
}

function testEscapeOverlayCloseSendNoDismissed() {
    return Promise.resolve().then(() => {
        const h = initHarness({batchLayout: 'landscape'});
        return h.api.open().then(() => waitForFlush()).then(() => {
            h.document.dispatchEvent({type: 'keydown', key: 'Escape'});
            eq('Escape 关闭弹窗', h.document.querySelectorAll('.plf-backdrop').length, 0);
            eq('Escape 不发送 dismissed', captureEvents(h).indexOf('survey dismissed'), -1);
            const state = JSON.parse(h.storage.getItem(STATE_KEY));
            eq('Escape 等同稍后再说', state.status, 'snoozed');
        });
    }).then(() => {
        const h = initHarness({batchLayout: 'landscape'});
        return h.api.open().then(() => waitForFlush()).then(() => {
            h.actionButton('close').click();
            eq('关闭按钮等同稍后再说', h.document.querySelectorAll('.plf-backdrop').length, 0);
            eq('关闭按钮不发送 dismissed', captureEvents(h).indexOf('survey dismissed'), -1);
            const state = JSON.parse(h.storage.getItem(STATE_KEY));
            eq('关闭按钮写 snoozed', state.status, 'snoozed');
        });
    }).then(() => {
        const h = initHarness({batchLayout: 'landscape'});
        return h.api.open().then(() => waitForFlush()).then(() => {
            const backdrop = h.document.querySelector('.plf-backdrop');
            backdrop.dispatchEvent({type: 'mousedown', target: backdrop});
            eq('点击遮罩关闭弹窗', h.document.querySelectorAll('.plf-backdrop').length, 0);
            eq('遮罩不发送 dismissed', captureEvents(h).indexOf('survey dismissed'), -1);
        });
    });
}

function testSubmitFailureAndRetry() {
    const h = initHarness({
        adapter: createFakeAdapter({surveys: [defaultSurvey()], capture: 'throw'}),
        batchLayout: 'landscape'
    });
    return h.api.open().then(() => waitForFlush()).then(() => {
        selectChoice(h, 'pixiv-batch-landscape');
        h.textarea().value = ' 保留的建议 ';
        h.textarea().dispatchEvent({type: 'input'});
        h.submitButton().click();
        return waitForFlush();
    }).then(() => {
        const stateRaw = h.storage.getItem(STATE_KEY);
        eq('capture 同步抛错不写 submitted', stateRaw === null || JSON.parse(stateRaw).status !== 'submitted', true);
        eq('失败保留弹窗', h.document.querySelectorAll('.plf-backdrop').length, 1);
        eq('失败显示错误', h.error().hidden, false);
        eq('失败恢复提交按钮', h.submitButton().disabled, false);
        const radio = h.radios().find(r => r.checked);
        eq('失败保留布局选择', radio && radio.value, 'pixiv-batch-landscape');
        eq('失败保留建议文本', h.textarea().value, ' 保留的建议 ');
    });
}

function testSubmitLockDuringInFlight() {
    const h = initHarness({batchLayout: 'landscape'});
    return h.api.open().then(() => waitForFlush()).then(() => {
        selectChoice(h, 'pixiv-batch-alt');
        h.submitButton().click();
        h.submitButton().click();
        h.submitButton().dispatchEvent({type: 'click'});
        return waitForFlush();
    }).then(() => {
        eq('提交锁保证单次 survey sent', captureEvents(h).filter(e => e === 'survey sent').length, 1);
    });
}

function testDntRejectionFailsSubmit() {
    const h = initHarness({
        adapter: createFakeAdapter({surveys: [defaultSurvey()], capture: 'reject'}),
        batchLayout: 'landscape'
    });
    return h.api.open().then(() => waitForFlush()).then(() => {
        selectChoice(h, 'pixiv-batch-landscape');
        h.submitButton().click();
        return waitForFlush();
    }).then(() => {
        eq('SDK 拒绝事件视为提交失败', captureEvents(h).filter(e => e === 'survey sent').length, 1);
        eq('SDK 拒绝不写 submitted', h.storage.getItem(STATE_KEY) === null, true);
        eq('SDK 拒绝保留弹窗可重试', h.document.querySelectorAll('.plf-backdrop').length, 1);
    });
}

function testAppVersionUnknown() {
    const h = initHarness({fetch: 'fail', batchLayout: 'landscape'});
    return h.api.open().then(() => waitForFlush()).then(() => {
        const shown = captureProps(h, 'survey shown');
        eq('版本获取失败时为 unknown', shown.app_version, 'unknown');
    }).then(() => {
        const h2 = initHarness({fetch: 'no-version', batchLayout: 'landscape'});
        return h2.api.open().then(() => waitForFlush()).then(() => {
            const shown = captureProps(h2, 'survey shown');
            eq('版本字段缺失时为 unknown', shown.app_version, 'unknown');
        });
    });
}

/* ============================================================
   before_send 与隐私（43-51）
============================================================ */

function testBeforeSendFilter() {
    const filter = initHarness({}).api._internals.beforeSendFilter;
    const base = {
        event: 'survey sent',
        properties: {
            distinct_id: 'anon-123',
            token: 'phc_x',
            time: 123,
            $lib: 'web',
            $lib_version: '1.409.5',
            '$survey_id': 's1',
            '$survey_response_q-layout': 'pixiv-batch-landscape',
            '$current_url': 'http://localhost:6999/pixiv-batch.html',
            '$referrer': 'http://evil.example',
            '$referring_domain': 'evil.example',
            pathname: '/pixiv-batch.html',
            hostname: 'localhost',
            $browser: 'Chrome',
            $os: 'Windows',
            $screen_width: 1920
        }
    };
    const filtered = filter(base);
    ok('保留 distinct_id', filtered.properties.distinct_id === 'anon-123');
    ok('保留 $lib / $lib_version 协议字段', filtered.properties.$lib === 'web');
    ok('保留 $survey_id', filtered.properties.$survey_id === 's1');
    ok('保留 $survey_response_*', filtered.properties['$survey_response_q-layout'] === 'pixiv-batch-landscape');
    ok('删除 $current_url', filtered.properties.$current_url === undefined);
    ok('删除 $referrer', filtered.properties.$referrer === undefined);
    ok('删除 $referring_domain', filtered.properties.$referring_domain === undefined);
    ok('删除 pathname', filtered.properties.pathname === undefined);
    ok('删除 hostname', filtered.properties.hostname === undefined);
    ok('删除无关浏览器环境属性', filtered.properties.$browser === undefined
        && filtered.properties.$os === undefined
        && filtered.properties.$screen_width === undefined);

    eq('拒绝 $pageview', filter({event: '$pageview', properties: {}}), null);
    eq('拒绝 $pageleave', filter({event: '$pageleave', properties: {}}), null);
    eq('拒绝 $autocapture', filter({event: '$autocapture', properties: {}}), null);
    eq('拒绝 $exception', filter({event: '$exception', properties: {}}), null);
    eq('拒绝 $web_vitals', filter({event: '$web_vitals', properties: {}}), null);
    eq('拒绝 $snapshot', filter({event: '$snapshot', properties: {}}), null);
    eq('拒绝 dead click', filter({event: '$dead_click', properties: {}}), null);
    eq('拒绝 rage click', filter({event: '$rageclick', properties: {}}), null);
    eq('拒绝任意非调查事件', filter({event: 'custom_event', properties: {}}), null);
    ok('放行 survey shown', filter({event: 'survey shown', properties: {distinct_id: 'a'}}) !== null);
    ok('放行 survey dismissed', filter({event: 'survey dismissed', properties: {distinct_id: 'a'}}) !== null);
}

function testSdkInitConfigPrivacy() {
    const h = initHarness({batchLayout: 'landscape'});
    return h.api.open().then(() => waitForFlush()).then(() => {
        const init = h.adapter.calls.init[0];
        eq('init 使用 projectToken', init.token, h.config.projectToken);
        const c = init.config;
        eq('api_host 来自配置', c.api_host, h.config.apiHost);
        eq('ui_host 来自配置', c.ui_host, h.config.uiHost);
        eq('autocapture 关闭', c.autocapture, false);
        eq('pageview 关闭', c.capture_pageview, false);
        eq('pageleave 关闭', c.capture_pageleave, false);
        eq('replay 关闭', c.disable_session_recording, true);
        eq('heatmap 关闭（capture_heatmaps）', c.capture_heatmaps, false);
        eq('弃用的 enable_heatmaps 不再作为配置字段', c.enable_heatmaps, undefined);
        eq('error tracking 关闭', c.capture_exceptions, false);
        eq('web vitals 关闭', c.capture_performance, false);
        eq('dead clicks 关闭', c.capture_dead_clicks, false);
        eq('surveys 保持启用', c.disable_surveys, false);
        eq('person_profiles 不创建匿名 Person', c.person_profiles, 'identified_only');
        eq('persistence 使用 localStorage', c.persistence, 'localStorage');
        eq('cross_subdomain_cookie 关闭', c.cross_subdomain_cookie, false);
        eq('DNT 尊重', c.respect_dnt, true);
        eq('campaign params 关闭', c.save_campaign_params, false);
        eq('referrer 不保存', c.save_referrer, false);
        eq('rageclick 关闭', c.rageclick, false);
        eq('SDK 默认 Survey 自动展示关闭', c.disable_surveys_automatic_display, true);
        eq('flags 只评估 Survey 相关', c.advanced_only_evaluate_survey_feature_flags, true);
        eq('外部脚本依赖关闭', c.disable_external_dependency_loading, true);
        eq('flags 请求超时较短', c.feature_flag_request_timeout_ms, 5000);
        eq('surveys 请求超时较短', c.surveys_request_timeout_ms, 15000);
        ok('before_send 已注册', typeof c.before_send === 'function');
    });
}

function testPayloadPrivacy() {
    const h = initHarness({batchLayout: 'landscape'});
    return h.api.open().then(() => waitForFlush()).then(() => {
        selectChoice(h, 'pixiv-batch-landscape');
        h.submitButton().click();
        return waitForFlush();
    }).then(() => {
        const allProps = [];
        h.adapter.calls.capture.forEach(c => allProps.push(c.properties));
        const forbidden = ['cookie', 'PHPSESSID', 'user_id', 'username', 'displayname', 'session',
            'authorization', 'artwork', 'novel', 'path', 'download', 'directory', 'filename'];
        const json = JSON.stringify(allProps).toLowerCase();
        forbidden.forEach(term => {
            ok('payload 不含 ' + term, json.indexOf(term) < 0);
        });
        ok('payload 不含 URL / referrer', json.indexOf('http') < 0 && json.indexOf('referrer') < 0);
    });
}

function testSuggestionNeverLogged() {
    const h = initHarness({batchLayout: 'landscape'});
    return h.api.open().then(() => waitForFlush()).then(() => {
        selectChoice(h, 'pixiv-batch-landscape');
        h.textarea().value = 'SECRET_SUGGESTION_TOP_SECRET';
        h.textarea().dispatchEvent({type: 'input'});
        h.submitButton().click();
        return waitForFlush();
    }).then(() => {
        const logs = JSON.stringify(h.consoleWarn);
        ok('用户建议不进入日志', logs.indexOf('SECRET_SUGGESTION_TOP_SECRET') < 0);
    });
}

/* ============================================================
   本地弱去重（28-31, 57）
============================================================ */

function testSubmittedNeverSnoozedGatesAutoShow() {
    const surveyId = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee';
    const state = (status, snoozedUntil) => JSON.stringify({
        surveyId, status, updatedAt: 100, snoozedUntil: snoozedUntil || 0
    });
    return Promise.resolve().then(() => {
        const h = initHarness({storage: Object.assign(seenSeed(), {[STATE_KEY]: state('submitted')}), batchLayout: 'landscape'});
        h.timers.advance(11000);
        return waitForFlush().then(() => {
            eq('submitted 后自动流程不展示', h.document.querySelectorAll('.plf-backdrop').length, 0);
        });
    }).then(() => {
        const h = initHarness({storage: Object.assign(seenSeed(), {[STATE_KEY]: state('never')}), batchLayout: 'landscape'});
        h.timers.advance(11000);
        return waitForFlush().then(() => {
            eq('never 后自动流程不展示', h.document.querySelectorAll('.plf-backdrop').length, 0);
        });
    }).then(() => {
        const h = initHarness({storage: Object.assign(seenSeed(), {[STATE_KEY]: state('snoozed', 2000000)}), batchLayout: 'landscape'});
        h.timers.advance(11000);
        return waitForFlush().then(() => {
            eq('snoozed 未到期不展示', h.document.querySelectorAll('.plf-backdrop').length, 0);
        });
    }).then(() => {
        const h = initHarness({storage: Object.assign(seenSeed(), {[STATE_KEY]: state('snoozed', 1000000)}), batchLayout: 'landscape'});
        h.timers.advance(11000);
        return waitForFlush().then(() => {
            eq('snoozed 到期后自动展示', h.document.querySelectorAll('.plf-backdrop').length, 1);
        });
    }).then(() => {
        const h = initHarness({storage: Object.assign(seenSeed(), {[STATE_KEY]: JSON.stringify({
            surveyId: 'other-survey-id-000', status: 'never', updatedAt: 100, snoozedUntil: 0
        })}), batchLayout: 'landscape'});
        h.timers.advance(11000);
        return waitForFlush().then(() => {
            eq('Survey ID 变化后旧状态不拦截', h.document.querySelectorAll('.plf-backdrop').length, 1);
        });
    });
}

function testCorruptStateIsCleaned() {
    const h = initHarness({
        storage: {[STATE_KEY]: '{not json', [SEEN_KEY]: '[[['},
        batchLayout: 'landscape',
        minDistinct: 1
    });
    const removeCallsBefore = h.storage.removeCalls.length;
    h.timers.advance(11000);
    return waitForFlush().then(() => {
        eq('损坏状态安全清理后仍可展示', h.document.querySelectorAll('.plf-backdrop').length, 1);
        ok('损坏 SEEN_KEY 在记录/清理路径中被 removeItem', h.storage.removeCalls
            .some(k => k === SEEN_KEY));
        ok('损坏 STATE_KEY 在门禁检查路径中被 removeItem', h.storage.removeCalls
            .slice(removeCallsBefore).some(k => k === STATE_KEY));
    });
}

function testCorruptStateRemoveThrowsStillSafe() {
    const h = initHarness({
        storage: {[STATE_KEY]: '{not json', [SEEN_KEY]: '[[['},
        batchLayout: 'landscape',
        minDistinct: 1,
        throwOnRemove: true
    });
    h.timers.advance(11000);
    return waitForFlush().then(() => {
        eq('removeItem 抛错时损坏状态仍安全降级', h.document.querySelectorAll('.plf-backdrop').length, 1);
        ok('removeItem 抛错时仍尝试清理', h.storage.removeCalls
            .some(k => k === STATE_KEY || k === SEEN_KEY));
    });
}

function testStorageThrowSafe() {
    return Promise.resolve().then(() => {
        const h = initHarness({throwOnGet: true, batchLayout: 'landscape', minDistinct: 1});
        h.timers.advance(11000);
        return waitForFlush().then(() => {
            eq('getItem 抛错不影响调查展示', h.document.querySelectorAll('.plf-backdrop').length, 1);
        });
    }).then(() => {
        const h = initHarness({throwOnSet: true, batchLayout: 'landscape'});
        return h.api.open().then(() => waitForFlush()).then(() => {
            selectChoice(h, 'pixiv-batch-landscape');
            h.actionButton('snooze').click();
            eq('setItem 抛错仍关闭弹窗', h.document.querySelectorAll('.plf-backdrop').length, 0);
        });
    }).then(() => {
        const h = initHarness({throwOnRemove: true, batchLayout: 'landscape'});
        return h.api.open().then(() => waitForFlush()).then(() => {
            selectChoice(h, 'pixiv-batch-landscape');
            h.submitButton().click();
            return waitForFlush().then(() => {
                eq('removeItem 抛错不影响提交', captureEvents(h).filter(e => e === 'survey sent').length, 1);
            });
        });
    });
}

function testCrossTabStorageSync() {
    // 另一标签页写入 submitted 后，本标签页收到 storage 事件并同步状态。
    const h2 = initHarness({
        storage: seenSeed(),
        batchLayout: 'landscape'
    });
    const submitted = JSON.stringify({
        surveyId: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee', status: 'submitted',
        updatedAt: 999, snoozedUntil: 0
    });
    h2.storage.values.set(STATE_KEY, submitted);
    h2.dispatchStorage(STATE_KEY, submitted);
    h2.timers.advance(11000);
    return waitForFlush().then(() => {
        eq('storage 事件同步后不展示', h2.document.querySelectorAll('.plf-backdrop').length, 0);
    });
}

/* ============================================================
   SDK / flags / survey 超时（35-37, 58-60）
============================================================ */

function testSdkLoadFailure() {
    const h = initHarness({adapter: null, batchLayout: 'landscape'});
    const promise = h.api.open();
    return waitForFlush().then(() => {
        // 服务端上下文 resolve 后脚本才插入；脚本加载失败静默结束
        eq('SDK 脚本已插入', h.scriptElements().length, 1);
        h.fireScriptError();
        return promise.then(() => waitForFlush());
    }).then(() => {
        eq('SDK 加载失败静默结束', h.document.querySelectorAll('.plf-backdrop').length, 0);
        h.timers.advance(11000);
        return waitForFlush();
    }).then(() => {
        eq('SDK 加载失败后不重新插入脚本', h.scriptElements().length, 1);
        eq('SDK 加载失败后自动流程不再动作', h.document.querySelectorAll('.plf-backdrop').length, 0);
    });
}

function testSdkLoadSuccessThroughScript() {
    const h = initHarness({adapter: null, batchLayout: 'landscape'});
    const promise = h.api.open();
    return waitForFlush().then(() => {
        h.sandbox.posthog = h.adapter ? null : createFakeAdapter({surveys: [defaultSurvey()]});
        h.fireScriptLoad();
        return promise.then(() => waitForFlush());
    }).then(() => {
        eq('SDK 加载成功后走真实 posthog 全局', h.document.querySelectorAll('.plf-backdrop').length, 1);
    });
}

function testSdkLoadTimeout() {
    const h = initHarness({adapter: null, batchLayout: 'landscape'});
    const promise = h.api.open();
    return waitForFlush().then(() => {
        eq('SDK 脚本已插入', h.scriptElements().length, 1);
        h.timers.advance(15000);
        return promise.then(() => waitForFlush());
    }).then(() => {
        eq('SDK 加载超时静默结束', h.document.querySelectorAll('.plf-backdrop').length, 0);
    });
}

function testFlagsTimeout() {
    const adapter = createFakeAdapter({surveys: [defaultSurvey()], stallFlags: true});
    const h = initHarness({adapter, batchLayout: 'landscape'});
    const promise = h.api.open();
    return waitForFlush().then(() => {
        eq('flags 未就绪时不展示', h.document.querySelectorAll('.plf-backdrop').length, 0);
        h.timers.advance(15000);
        return promise.then(() => waitForFlush());
    }).then(() => {
        eq('flags 超时后仍可获取调查', h.document.querySelectorAll('.plf-backdrop').length, 1);
    });
}

function testSurveyFetchTimeout() {
    const adapter = createFakeAdapter({surveys: [defaultSurvey()], stallSurveys: true});
    const h = initHarness({adapter, batchLayout: 'landscape'});
    const promise = h.api.open();
    return waitForFlush().then(() => {
        h.timers.advance(40000);
        return promise.then(() => waitForFlush());
    }).then(() => {
        eq('Survey 获取超时静默结束', h.document.querySelectorAll('.plf-backdrop').length, 0);
    });
}

function testDisabledConfigDoesNothing() {
    const h = initHarness({publicConfig: {
        enabled: false, projectToken: '', surveyId: '', apiHost: '', uiHost: ''
    }});
    return h.api.open().then(() => waitForFlush()).then(() => {
        eq('enabled=false 不展示调查', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('enabled=false 不加载 SDK', h.scriptElements().length, 0);
        h.timers.advance(11000);
        return waitForFlush();
    }).then(() => {
        eq('enabled=false 自动流程不动作', h.document.querySelectorAll('.plf-backdrop').length, 0);
    });
}

function testPartialConfigTreatedAsDisabled() {
    const h = initHarness({publicConfig: {
        enabled: false, projectToken: 'only-token', surveyId: '', apiHost: '', uiHost: ''
    }});
    h.timers.advance(11000);
    return waitForFlush().then(() => {
        eq('部分配置按 disabled 处理（构建期已拒绝半配置）', h.document.querySelectorAll('.plf-backdrop').length, 0);
    });
}

function testAutoShowConditions() {
    return Promise.resolve().then(() => {
        const h = initHarness({batchLayout: 'landscape', storage: seenSeed()});
        h.timers.advance(11000);
        return waitForFlush().then(() => {
            eq('满足条件延迟 10s 后自动展示', h.document.querySelectorAll('.plf-backdrop').length, 1);
            const shown = captureEvents(h).filter(e => e === 'survey shown').length;
            eq('自动展示发送一次 shown', shown, 1);
        });
    }).then(() => {
        const h = initHarness({throwOnGet: true, batchLayout: 'landscape', minDistinct: 1});
        h.timers.advance(11000);
        return waitForFlush().then(() => {
            eq('getItem 抛错不影响调查展示', h.document.querySelectorAll('.plf-backdrop').length, 1);
        });
    }).then(() => {
        const h = initHarness({minDistinct: 1, batchLayout: 'landscape'});
        h.timers.advance(11000);
        return waitForFlush().then(() => {
            eq('测试环境 minDistinct=1 可自动展示', h.document.querySelectorAll('.plf-backdrop').length, 1);
        });
    });
}

function testBlockedOverlayBoundedRetry() {
    const h = initHarness({batchLayout: 'landscape', storage: seenSeed()});
    h.body.classList.add('pixiv-feedback-open');
    h.timers.advance(11000);
    return waitForFlush().then(() => {
        eq('有其它弹窗时暂缓', h.document.querySelectorAll('.plf-backdrop').length, 0);
        h.body.classList.remove('pixiv-feedback-open');
        h.timers.advance(6000);
        return waitForFlush();
    }).then(() => {
        eq('有限延迟检查后展示', h.document.querySelectorAll('.plf-backdrop').length, 1);
    });
}

/* ============================================================
   布局体验记录（55-56）
============================================================ */

function testSeenRecordingAndThreshold() {
    return Promise.resolve().then(() => {
        const h = initHarness({storage: {}, minDistinct: 2});
        h.dispatchLayoutChanged('portrait', 'landscape');
        h.dispatchLayoutChanged('landscape', 'portrait');
        const seen = JSON.parse(h.storage.getItem(SEEN_KEY));
        ok('layout changed 更新 seen', seen['pixiv-batch-portrait'] && seen['pixiv-batch-portrait'].lastSeenAt > 0);
        ok('seen 记录两个布局', h.api._internals.distinctSeenCount(seen) === 2);
    }).then(() => {
        const h = initHarness({storage: {}, minDistinct: 2, batchLayout: 'portrait'});
        h.timers.advance(11000);
        return waitForFlush().then(() => {
            eq('仅体验一个布局不自动展示', h.document.querySelectorAll('.plf-backdrop').length, 0);
        });
    }).then(() => {
        const h = initHarness({storage: {}, minDistinct: 2});
        h.dispatchLayoutChanged('landscape', 'portrait');
        h.dispatchLayoutChanged('portrait', 'landscape');
        h.timers.advance(11000);
        return waitForFlush().then(() => {
            eq('体验两个不同布局后自动展示', h.document.querySelectorAll('.plf-backdrop').length, 1);
        });
    }).then(() => {
        const h = initHarness({page: 'alt', storage: {}, minDistinct: 2});
        const seen = JSON.parse(h.storage.getItem(SEEN_KEY));
        ok('alt 页面记录 pixiv-batch-alt', seen['pixiv-batch-alt'] && seen['pixiv-batch-alt'].lastSeenAt > 0);
    });
}

/* ============================================================
   语言切换与无障碍（53-54）
============================================================ */

function testLanguageSwitchPreservesInput() {
    const h = initHarness({i18nMessages: {}, batchLayout: 'landscape'});
    return h.api.open().then(() => waitForFlush()).then(() => {
        selectChoice(h, 'pixiv-batch-portrait');
        h.textarea().value = '保留的建议';
        h.textarea().dispatchEvent({type: 'input'});
        const en = createFakeI18n({
            'layout-feedback:title': 'Help us choose the default layout',
            'layout-feedback:option-portrait': 'Portrait classic layout',
            'layout-feedback:submit': 'Submit feedback'
        });
        h.api.refreshLanguage(en);
        eq('语言切换不创建第二个弹窗', h.document.querySelectorAll('.plf-backdrop').length, 1);
        const radio = h.radios().find(r => r.checked);
        eq('语言切换保留布局选择', radio && radio.value, 'pixiv-batch-portrait');
        eq('语言切换保留建议文本', h.textarea().value, '保留的建议');
        eq('语言切换刷新文案', h.document.getElementById('plf-title').textContent, 'Help us choose the default layout');
        eq('语言切换不重复 shown', captureEvents(h).filter(e => e === 'survey shown').length, 1);
    });
}

function testReducedMotionAndA11yBasics() {
    ok('CSS 遵循 prefers-reduced-motion', CSS.indexOf('@media (prefers-reduced-motion: reduce)') >= 0);
    ok('模块使用 aria-modal', SOURCE.indexOf("'aria-modal'") >= 0);
    ok('模块使用 aria-labelledby', SOURCE.indexOf("'aria-labelledby'") >= 0);
    ok('模块使用 aria-describedby', SOURCE.indexOf("'aria-describedby'") >= 0);
    ok('模块使用原生 radio', SOURCE.indexOf("input.type = 'radio'") >= 0);
    ok('模块使用 radiogroup', SOURCE.indexOf("'radiogroup'") >= 0);
    ok('模块处理 Tab 焦点陷阱', SOURCE.indexOf("event.key !== 'Tab'") >= 0);
    ok('模块处理 Escape', SOURCE.indexOf("event.key === 'Escape'") >= 0);
    ok('模块使用 aria-live 错误', SOURCE.indexOf("'aria-live'") >= 0);
    ok('模块使用 aria-busy', SOURCE.indexOf("'aria-busy'") >= 0);
    ok('不使用 innerHTML 插入用户输入', SOURCE.indexOf('innerHTML') < 0);
    ok('不使用 inline onclick', SOURCE.indexOf('onclick') < 0);
    const h = initHarness({batchLayout: 'landscape'});
    return h.api.open().then(() => waitForFlush()).then(() => {
        const dialog = h.dialog();
        eq('弹窗 role=dialog', dialog.getAttribute('role'), 'dialog');
        eq('弹窗 aria-modal', dialog.getAttribute('aria-modal'), 'true');
        eq('弹窗 aria-labelledby', dialog.getAttribute('aria-labelledby'), 'plf-title');
        eq('弹窗 aria-describedby', dialog.getAttribute('aria-describedby'), 'plf-description');
        ok('关闭按钮有 aria-label', h.actionButton('close').getAttribute('aria-label') !== null);
    });
}

function testCurrentLayoutBadge() {
    const h = initHarness({batchLayout: 'portrait'});
    return h.api.open().then(() => waitForFlush()).then(() => {
        const cards = h.document.querySelectorAll('.plf-card');
        eq('三张布局卡片', cards.length, 3);
        const current = cards.find(c => c.getAttribute('data-plf-layout') === 'pixiv-batch-portrait');
        const badge = current.querySelector('.plf-current-badge');
        ok('当前布局徽标显示', badge && badge.hidden === false);
        const other = cards.find(c => c.getAttribute('data-plf-layout') === 'pixiv-batch-landscape');
        const otherBadge = other.querySelector('.plf-current-badge');
        ok('非当前布局徽标隐藏', otherBadge && otherBadge.hidden === true);
    });
}

/* ============================================================
   capture 返回值矩阵与 before_send 顶层字段
============================================================ */

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

function testCaptureResultAcceptanceMatrix() {
    return Promise.resolve().then(() => submitWithCaptureOverride(null)).then(h => {
        eq('CaptureResult 对象 → 写 submitted',
            JSON.parse(h.storage.getItem(STATE_KEY)).status, 'submitted');
        eq('CaptureResult 对象 → 关闭弹窗', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('CaptureResult 对象 → 成功 Toast 恰好一次', h.toastCalls.length, 1);
    }).then(() => submitWithCaptureOverride('undefined')).then(h => {
        eq('capture 返回 undefined → 不写 submitted', h.storage.getItem(STATE_KEY) === null, true);
        eq('capture 返回 undefined → 保留弹窗', h.document.querySelectorAll('.plf-backdrop').length, 1);
        eq('capture 返回 undefined → 显示可重试错误', h.error().hidden, false);
        eq('capture 返回 undefined → 不显示成功 Toast', h.toastCalls.length, 0);
    }).then(() => submitWithCaptureOverride('null')).then(h => {
        eq('capture 返回 null → 不写 submitted', h.storage.getItem(STATE_KEY) === null, true);
        eq('capture 返回 null → 保留弹窗', h.document.querySelectorAll('.plf-backdrop').length, 1);
    }).then(() => submitWithCaptureOverride('false')).then(h => {
        eq('capture 返回 false → 不写 submitted（防御兼容）', h.storage.getItem(STATE_KEY) === null, true);
        eq('capture 返回 false → 保留弹窗', h.document.querySelectorAll('.plf-backdrop').length, 1);
    }).then(() => submitWithCaptureOverride('throw')).then(h => {
        eq('capture 同步抛错 → 不写 submitted', h.storage.getItem(STATE_KEY) === null, true);
        eq('capture 同步抛错 → 保留弹窗可重试', h.document.querySelectorAll('.plf-backdrop').length, 1);
    }).then(() => {
        // before_send 返回 null → SDK 丢弃事件 → capture 返回 undefined（真实 1.409.5 语义）
        const h = initHarness({
            adapter: (() => {
                const a = createFakeAdapter({surveys: [defaultSurvey()]});
                a.capture = function (name, properties) {
                    const event = {
                        uuid: 'evt-x', event: name, timestamp: 't',
                        properties: Object.assign({}, properties)
                    };
                    const config = this.sdkConfig();
                    if (config && typeof config.before_send === 'function') {
                        config.before_send(event);
                    }
                    // 模拟 before_send 链中后续过滤器返回 null：
                    // SDK 丢弃整个事件，capture() 返回 undefined。
                    return undefined;
                };
                return a;
            })(),
            batchLayout: 'landscape'
        });
        return h.api.open().then(() => waitForFlush()).then(() => {
            selectChoice(h, 'pixiv-batch-landscape');
            h.submitButton().click();
            return waitForFlush();
        }).then(() => {
            eq('before_send 返回 null → 不写 submitted', h.storage.getItem(STATE_KEY) === null, true);
        });
    });
}

function testBeforeSendTopLevelFields() {
    const filter = initHarness({}).api._internals.beforeSendFilter;
    const withSet = {
        uuid: 'evt-1',
        event: 'survey sent',
        timestamp: '2026-01-01T00:00:00.000Z',
        $set: {'$survey_test_responded': true},
        $set_once: {'$initial_test': 'x'},
        $unset: ['old'],
        properties: {
            distinct_id: 'anon-1',
            token: 'phc_x',
            '$survey_id': 's1',
            '$survey_response_q-layout': 'pixiv-batch-portrait',
            '$survey_response_q-suggestion': 'keep me',
            $current_url: 'http://localhost:6999/pixiv-batch.html'
        }
    };
    const out = filter(withSet);
    eq('顶层 $set 被删除', out.$set, undefined);
    eq('顶层 $set_once 被删除', out.$set_once, undefined);
    eq('顶层 $unset 被删除', out.$unset, undefined);
    eq('保留 uuid', out.uuid, 'evt-1');
    eq('保留 event', out.event, 'survey sent');
    eq('保留 timestamp', out.timestamp, '2026-01-01T00:00:00.000Z');
    ok('保留 distinct_id / token / $survey_id', out.properties.distinct_id === 'anon-1'
        && out.properties.token === 'phc_x' && out.properties.$survey_id === 's1');
    eq('Survey response 不丢失', out.properties['$survey_response_q-layout'], 'pixiv-batch-portrait');
    eq('建议响应不丢失', out.properties['$survey_response_q-suggestion'], 'keep me');
    eq('环境属性仍被过滤', out.properties.$current_url, undefined);
    ok('输出不携带多余顶层字段', Object.keys(out).every(k => ['uuid', 'event', 'timestamp', 'properties'].indexOf(k) >= 0));
    eq('非 Survey 事件仍返回 null', filter({uuid: 'e', event: '$pageview', properties: {}}), null);
}

function testSdkInitCapturesConfigForBeforeSend() {
    const h = initHarness({batchLayout: 'landscape'});
    return h.api.open().then(() => waitForFlush()).then(() => {
        ok('fake adapter 保存了 SDK config', h.adapter.sdkConfig() !== null);
        eq('config 含 before_send', typeof h.adapter.sdkConfig().before_send, 'function');
    });
}

/* ============================================================
   DNT / opt-out 门禁
============================================================ */

function testDntGateSilentSkip() {
    const h = initHarness({
        adapter: createFakeAdapter({surveys: [defaultSurvey()], optedOut: true}),
        batchLayout: 'landscape'
    });
    return h.api.open().then(() => waitForFlush()).then(() => {
        eq('DNT opt-out 时不显示', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('DNT opt-out 不调用 getActiveMatchingSurveys', h.adapter.calls.getSurveys.length, 0);
        eq('DNT opt-out 不调用 capture', h.adapter.calls.capture.length, 0);
        eq('DNT opt-out 不写任何反馈状态', h.storage.getItem(STATE_KEY) === null, true);
        eq('DNT opt-out 无错误提示', h.document.querySelectorAll('[data-plf-error]').length, 0);
    });
}

function testIsCapturingFalseSilentSkip() {
    const h = initHarness({
        adapter: createFakeAdapter({surveys: [defaultSurvey()], isCapturing: false}),
        batchLayout: 'landscape'
    });
    return h.api.open().then(() => waitForFlush()).then(() => {
        eq('is_capturing() false 时不显示', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('is_capturing() false 不请求 Survey', h.adapter.calls.getSurveys.length, 0);
        eq('is_capturing() false 不调用 capture', h.adapter.calls.capture.length, 0);
    });
}

function testDntGateAutoFlowSilent() {
    const h = initHarness({
        adapter: createFakeAdapter({surveys: [defaultSurvey()], optedOut: true}),
        storage: seenSeed(),
        batchLayout: 'landscape'
    });
    h.timers.advance(11000);
    return waitForFlush().then(() => {
        eq('DNT opt-out 自动流程不显示', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('DNT opt-out 自动流程不发 shown', h.adapter.calls.capture.length, 0);
        eq('DNT opt-out 自动流程不写状态', h.storage.getItem(STATE_KEY) === null, true);
    });
}

function testDntGateNormalCapturingStillShows() {
    const h = initHarness({
        adapter: createFakeAdapter({surveys: [defaultSurvey()], optedOut: false}),
        batchLayout: 'landscape'
    });
    return h.api.open().then(() => waitForFlush()).then(() => {
        eq('正常 capturing 状态仍可显示', h.document.querySelectorAll('.plf-backdrop').length, 1);
        eq('正常 capturing 请求 Survey', h.adapter.calls.getSurveys.length, 1);
    });
}

/* ============================================================
   自动展示状态机
============================================================ */

function testAutoShowWaitsForSecondLayout() {
    const h = initHarness({storage: {}, minDistinct: 2, batchLayout: 'landscape'});
    h.timers.advance(11000);
    return waitForFlush().then(() => {
        eq('第一次 10s 检查只有一个布局不展示', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('未达阈值不加载 SDK', h.adapter.calls.getSurveys.length, 0);
        h.dispatchLayoutChanged('portrait', 'landscape');
        return waitForFlush();
    }).then(() => {
        h.timers.advance(0);
        return waitForFlush();
    }).then(() => {
        eq('切换第二个布局后自动展示', h.document.querySelectorAll('.plf-backdrop').length, 1);
    });
}

function testAutoShowVisibilityReschedule() {
    const h = initHarness({
        storage: seenSeed(),
        batchLayout: 'landscape',
        visibilityState: 'hidden'
    });
    h.timers.advance(11000);
    return waitForFlush().then(() => {
        eq('初次检查时页面 hidden 不展示', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('页面 hidden 不消耗自动流程机会', h.adapter.calls.getSurveys.length, 0);
        h.document.visibilityState = 'visible';
        h.document.dispatchEvent({type: 'visibilitychange'});
        return waitForFlush();
    }).then(() => {
        h.timers.advance(0);
        return waitForFlush();
    }).then(() => {
        eq('页面 visible 后重新调度并展示', h.document.querySelectorAll('.plf-backdrop').length, 1);
    });
}

function testAutoShowOverlayRetryLimit() {
    const h = initHarness({batchLayout: 'landscape', storage: seenSeed()});
    h.body.classList.add('pixiv-feedback-open');
    h.timers.advance(11000);
    return waitForFlush().then(() => {
        eq('阻塞弹窗存在时暂缓', h.document.querySelectorAll('.plf-backdrop').length, 0);
        h.body.classList.remove('pixiv-feedback-open');
        h.timers.advance(6000);
        return waitForFlush();
    }).then(() => {
        eq('有限重试后展示', h.document.querySelectorAll('.plf-backdrop').length, 1);
    }).then(() => {
        const h2 = initHarness({batchLayout: 'landscape', storage: seenSeed()});
        h2.body.classList.add('pixiv-feedback-open');
        h2.timers.advance(11000);
        h2.timers.advance(10000);
        h2.timers.advance(10000);
        h2.timers.advance(10000);
        return waitForFlush().then(() => {
            eq('超过重试上限后停止', h2.document.querySelectorAll('.plf-backdrop').length, 0);
            h2.body.classList.remove('pixiv-feedback-open');
            h2.timers.advance(6000);
            return waitForFlush();
        }).then(() => {
            eq('超过重试上限后不再展示', h2.document.querySelectorAll('.plf-backdrop').length, 0);
        });
    });
}

function testAutoFlowStartsSurveyFlowOnce() {
    const h = initHarness({batchLayout: 'landscape', storage: seenSeed()});
    h.timers.advance(11000);
    return waitForFlush().then(() => {
        eq('自动流程启动一次', h.adapter.calls.getSurveys.length, 1);
        h.dispatchLayoutChanged('portrait', 'landscape');
        h.dispatchLayoutChanged('landscape', 'portrait');
        return waitForFlush();
    }).then(() => {
        h.timers.advance(0);
        return waitForFlush();
    }).then(() => {
        eq('多次 layout changed 不启动第二个 Survey 流程', h.adapter.calls.getSurveys.length, 1);
        eq('自动流程开始后不重复请求 Survey', h.adapter.calls.capture
            .filter(c => c.name === 'survey shown').length, 1);
    });
}

/* ============================================================
   onFeatureFlags 同步回调竞态
============================================================ */

function testSyncFlagsCallbackRace() {
    const adapter = createFakeAdapter({surveys: [defaultSurvey()], syncFlagsCallback: true});
    const h = initHarness({adapter, batchLayout: 'landscape'});
    const promise = h.api.open();
    return waitForFlush().then(() => {
        eq('同步 callback 后 off 被调用', (adapter.calls.offCalls || 0) >= 1, true);
        eq('getActiveMatchingSurveys 只调用一次', adapter.calls.getSurveys.length, 1);
        return promise.then(() => waitForFlush());
    }).then(() => {
        eq('同步 flags 场景正常展示', h.document.querySelectorAll('.plf-backdrop').length, 1);
    });
}

function testSyncFlagsCallbackWithStalledSurveys() {
    const adapter = createFakeAdapter({
        surveys: [defaultSurvey()],
        syncFlagsCallback: true,
        stallSurveys: true
    });
    const h = initHarness({adapter, batchLayout: 'landscape'});
    const promise = h.api.open();
    return waitForFlush().then(() => {
        eq('flags 超时与同步 callback 竞争只请求一次', adapter.calls.getSurveys.length, 1);
        h.timers.advance(40000);
        return promise.then(() => waitForFlush());
    }).then(() => {
        eq('竞争场景总超时后安全结束', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('竞争场景 off 已注销', (adapter.calls.offCalls || 0) >= 1, true);
    });
}

function testDestroyCancelsSurveyFetch() {
    const adapter = createFakeAdapter({
        surveys: [defaultSurvey()],
        syncFlagsCallback: true,
        stallSurveys: true
    });
    const h = initHarness({adapter, batchLayout: 'landscape'});
    const promise = h.api.open();
    return waitForFlush().then(() => {
        h.api.destroy();
        return waitForFlush();
    }).then(() => {
        h.timers.advance(50000);
        return promise.then(() => waitForFlush());
    }).then(() => {
        eq('destroy 后不再展示调查', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('destroy 后不留下活动 flags 监听', (adapter.calls.offCalls || 0) >= 1, true);
    });
}

/* ============================================================
   跨标签页弱去重
============================================================ */

function crossTabState(stateStatus, snoozedUntil) {
    return JSON.stringify({
        surveyId: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
        status: stateStatus,
        updatedAt: 999,
        snoozedUntil: snoozedUntil || 0
    });
}

function testCrossTabSubmittedClosesOtherTab() {
    const h2 = initHarness({batchLayout: 'landscape'});
    return h2.api.open().then(() => waitForFlush()).then(() => {
        eq('标签页 B 弹窗已打开', h2.document.querySelectorAll('.plf-backdrop').length, 1);
        const submitted = crossTabState('submitted');
        h2.storage.values.set(STATE_KEY, submitted);
        h2.dispatchStorage(STATE_KEY, submitted);
        return waitForFlush();
    }).then(() => {
        eq('标签页 B 收到 submitted storage 事件后关闭', h2.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('标签页 B 不发送第二条 dismissed', captureEvents(h2).indexOf('survey dismissed'), -1);
        eq('标签页 B 不写状态覆盖', JSON.parse(h2.storage.getItem(STATE_KEY)).status, 'submitted');
        ok('标签页 B 显示非阻塞提示', h2.toastCalls.length === 1);
    });
}

function testCrossTabNeverAndSnoozeClosesOtherTab() {
    return Promise.resolve().then(() => {
        const h2 = initHarness({batchLayout: 'landscape'});
        return h2.api.open().then(() => waitForFlush()).then(() => {
            const never = crossTabState('never');
            h2.storage.values.set(STATE_KEY, never);
            h2.dispatchStorage(STATE_KEY, never);
            return waitForFlush();
        }).then(() => {
            eq('never 关闭另一标签页', h2.document.querySelectorAll('.plf-backdrop').length, 0);
        });
    }).then(() => {
        const h3 = initHarness({batchLayout: 'landscape'});
        return h3.api.open().then(() => waitForFlush()).then(() => {
            const snoozed = crossTabState('snoozed', 2000000);
            h3.storage.values.set(STATE_KEY, snoozed);
            h3.dispatchStorage(STATE_KEY, snoozed);
            return waitForFlush();
        }).then(() => {
            eq('有效 snooze 关闭另一标签页', h3.document.querySelectorAll('.plf-backdrop').length, 0);
        });
    });
}

function testFreshCheckPreventsDuplicateSubmit() {
    // 标签页 B：弹窗打开、已选布局，但在提交瞬间另一标签页已写入 submitted。
    const h2 = initHarness({batchLayout: 'landscape'});
    return h2.api.open().then(() => waitForFlush()).then(() => {
        selectChoice(h2, 'pixiv-batch-portrait');
        h2.storage.values.set(STATE_KEY, crossTabState('submitted'));
        h2.submitButton().click();
        return waitForFlush();
    }).then(() => {
        eq('提交前 fresh check 阻止重复提交', captureEvents(h2).filter(e => e === 'survey sent').length, 0);
        eq('fresh check 拦截后关闭弹窗', h2.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('不额外发送 dismissed', captureEvents(h2).indexOf('survey dismissed'), -1);
    });
}

/* ============================================================
   Unicode 1000 code point 统一
============================================================ */

function hasLoneSurrogates(text) {
    return /[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/.test(text);
}

function testUnicodeLengthMatrix() {
    const emoji = '\ud83d\ude00';
    return Promise.resolve().then(() => {
        const h = initHarness({batchLayout: 'landscape'});
        return h.api.open().then(() => waitForFlush()).then(() => {
            selectChoice(h, 'pixiv-batch-landscape');
            h.textarea().value = 'a'.repeat(1000);
            h.textarea().dispatchEvent({type: 'input'});
            eq('1000 个普通字符允许', h.counter().textContent.split(' ')[0], '1000');
            h.submitButton().click();
            return waitForFlush();
        }).then(() => {
            eq('1000 个普通字符可提交', captureProps(h, 'survey sent')['$survey_response_q-suggestion'].length, 1000);
        });
    }).then(() => {
        const h = initHarness({batchLayout: 'landscape'});
        return h.api.open().then(() => waitForFlush()).then(() => {
            selectChoice(h, 'pixiv-batch-landscape');
            h.textarea().value = 'a'.repeat(1001);
            h.textarea().dispatchEvent({type: 'input'});
            eq('1001 个普通字符截断为 1000', h.textarea().value.length, 1000);
        });
    }).then(() => {
        const h = initHarness({batchLayout: 'landscape'});
        return h.api.open().then(() => waitForFlush()).then(() => {
            selectChoice(h, 'pixiv-batch-landscape');
            h.textarea().value = emoji.repeat(1000);
            h.textarea().dispatchEvent({type: 'input'});
            eq('1000 个 Emoji 计数器一致', h.counter().textContent.split(' ')[0], '1000');
            eq('1000 个 Emoji 允许（2000 UTF-16 单元不受 maxlength 影响）', h.textarea().value.length, 2000);
            h.submitButton().click();
            return waitForFlush();
        }).then(() => {
            const sent = captureProps(h, 'survey sent')['$survey_response_q-suggestion'];
            eq('Emoji 提交校验与计数器一致', Array.from(sent).length, 1000);
            ok('Emoji 提交内容完整无孤立代理', !hasLoneSurrogates(sent) && sent === emoji.repeat(1000));
        });
    }).then(() => {
        const h = initHarness({batchLayout: 'landscape'});
        return h.api.open().then(() => waitForFlush()).then(() => {
            selectChoice(h, 'pixiv-batch-landscape');
            h.textarea().value = emoji.repeat(1001);
            h.textarea().dispatchEvent({type: 'input'});
            eq('1001 个 Emoji 截断为 1000 个 code point', h.counter().textContent.split(' ')[0], '1000');
            eq('截断后为 2000 个 UTF-16 单元', h.textarea().value.length, 2000);
            ok('截断结果不包含孤立代理项', !hasLoneSurrogates(h.textarea().value));
            eq('截断结果为完整 Emoji 序列', h.textarea().value, emoji.repeat(1000));
            h.submitButton().click();
            return waitForFlush();
        }).then(() => {
            eq('截断后 Emoji 可提交', captureProps(h, 'survey sent')['$survey_response_q-suggestion'] !== undefined, true);
        });
    });
}

/* ============================================================
   入口
============================================================ */

/* ============================================================
   SDK loader 取消与 runtime generation（异步销毁语义）
============================================================ */

function testDestroyDuringSdkLoad() {
    const h = initHarness({adapter: null, batchLayout: 'landscape'});
    let resolved = false;
    const promise = h.api.open().then(value => { resolved = true; return value; });
    return waitForFlush().then(() => {
        eq('SDK 加载中恰好插入一个 script', h.scriptElements().length, 1);
        h.api.destroy();
        return waitForFlush().then(() => {
            ok('open Promise 在 destroy 后安全完成（不永久 pending）', resolved);
            const scripts = h.scriptElements();
            eq('模块创建的未完成 script 被移除出 DOM', scripts[0].parentNode === null, true);
            eq('load listener 已移除', (scripts[0].listeners.get('load') || []).length, 0);
            eq('error listener 已移除', (scripts[0].listeners.get('error') || []).length, 0);
            eq('destroy 后不显示弹窗', h.document.querySelectorAll('.plf-backdrop').length, 0);
            eq('destroy 后不写反馈状态', h.storage.getItem(STATE_KEY) === null, true);
            eq('destroy 后无 Toast', h.toastCalls.length, 0);
            h.timers.advance(30000);
            return waitForFlush();
        });
    }).then(() => {
        eq('SDK 加载取消后无任何后续动作', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('destroy 后定时器无残留', h.timers.pending().length, 0);
        eq('destroy 后无监听残留', listenerCountFor(h, 'pixiv:batch-layout-changed') === 0
            && h.windowEvents.listenerCount('storage') === 0, true);
    });
}

function testLateScriptLoadAfterDestroy() {
    const h = initHarness({adapter: null, batchLayout: 'landscape'});
    const promise = h.api.open();
    return waitForFlush().then(() => {
        eq('SDK 加载中插入一个 script', h.scriptElements().length, 1);
        h.api.destroy();
        h.sandbox.posthog = createFakeAdapter({surveys: [defaultSurvey()]});
        h.fireScriptLoad();
        return promise.then(() => waitForFlush());
    }).then(() => {
        eq('旧 script 迟到 load 不显示弹窗', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('旧 script 迟到 load 不写反馈状态', h.storage.getItem(STATE_KEY) === null, true);
        eq('旧 script 迟到 load 无 Toast', h.toastCalls.length, 0);
    });
}

function testDestroyAndReInit() {
    const h = initHarness({batchLayout: 'landscape'});
    return h.api.open().then(() => waitForFlush()).then(() => {
        eq('第一次 init 弹窗正常', h.document.querySelectorAll('.plf-backdrop').length, 1);
        h.api.destroy();
        eq('destroy 关闭弹窗', h.document.querySelectorAll('.plf-backdrop').length, 0);
        h.api.init(reinitOptions(h));
        return h.api.open().then(() => waitForFlush());
    }).then(() => {
        eq('re-init 后弹窗可再次打开', h.document.querySelectorAll('.plf-backdrop').length, 1);
        eq('同时最多一个调查弹窗', h.document.querySelectorAll('.plf-backdrop').length, 1);
        eq('storage 监听不重复', h.windowEvents.listenerCount('storage'), 1);
        eq('visibility 监听不重复', listenerCountFor(h, 'visibilitychange'), 1);
        eq('layout 监听不重复', listenerCountFor(h, 'pixiv:batch-layout-changed'), 1);
    });
}

function testReuseLoadedSdk() {
    const adapter = createFakeAdapter({surveys: [defaultSurvey()]});
    const h = initHarness({adapter: null, batchLayout: 'landscape'});
    h.sandbox.posthog = adapter;
    return h.api.open().then(() => waitForFlush()).then(() => {
        eq('复用已存在 SDK 不插入 script', h.scriptElements().length, 0);
        eq('SDK init 恰好一次', adapter.calls.init.length, 1);
        eq('已加载 SDK 正常展示', h.document.querySelectorAll('.plf-backdrop').length, 1);
        return h.api.open().then(() => waitForFlush());
    }).then(() => {
        eq('重复 open 相同配置不重复 init', adapter.calls.init.length, 1);
        eq('重复 open 不插入 script', h.scriptElements().length, 0);
        h.api.destroy();
        h.api.init(reinitOptions(h, null));
        return h.api.open().then(() => waitForFlush());
    }).then(() => {
        eq('destroy 后重新 init 复用已加载 SDK', h.scriptElements().length, 0);
        eq('destroy 后重新 init 相同签名不重复 init', adapter.calls.init.length, 1);
        eq('destroy 后弹窗可再次打开', h.document.querySelectorAll('.plf-backdrop').length, 1);
    });
}

function testConfigMismatchFailsClosed() {
    const h = initHarness({batchLayout: 'landscape'});
    let surveysBefore = 0;
    let captureBefore = 0;
    return h.api.open().then(() => waitForFlush()).then(() => {
        eq('配置 A 正常展示', h.document.querySelectorAll('.plf-backdrop').length, 1);
        surveysBefore = h.adapter.calls.getSurveys.length;
        captureBefore = h.adapter.calls.capture.length;
        h.api.destroy();
        // 配置 B：projectToken / apiHost 变化
        h.sandbox.PixivLayoutFeedbackPublicConfig = Object.freeze({
            enabled: true,
            projectToken: 'phc_second_project_token',
            surveyId: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
            apiHost: 'https://proxy2.example.com',
            uiHost: 'https://us.i.posthog.com'
        });
        h.api.init(reinitOptions(h));
        return h.api.open().then(() => waitForFlush());
    }).then(() => {
        eq('配置签名变化 fail closed：不展示', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('配置签名变化不请求 Survey', h.adapter.calls.getSurveys.length, surveysBefore);
        eq('配置签名变化不发送事件', h.adapter.calls.capture.length, captureBefore);
        const warnings = JSON.stringify(h.consoleWarn);
        ok('记录安全 warning', warnings.indexOf('different public configuration') >= 0);
        ok('warning 不含 Project token 实际值', warnings.indexOf('phc_second_project_token') < 0
            && warnings.indexOf('phc_test_project_token') < 0);
    });
}

function testDestroyDuringAppVersionWait() {
    let resolveAppInfo = null;
    const adapter = createFakeAdapter({surveys: [defaultSurvey()]});
    const h = initHarness({
        adapter,
        batchLayout: 'landscape',
        fetchImpl: (url, init) => {
            if (url.indexOf('/api/app/info') >= 0) {
                return new Promise(resolve => { resolveAppInfo = resolve; });
            }
            // server state 快速 resolve（local 模式），只卡住 app version
            return Promise.resolve({ok: true, json: () => Promise.resolve({available: false})});
        }
    });
    const promise = h.api.open();
    return waitForFlush().then(() => {
        eq('Survey 流程开始（弹窗已打开）', h.document.querySelectorAll('.plf-backdrop').length, 1);
        ok('appVersion fetch 尚未完成', typeof resolveAppInfo === 'function');
        h.api.destroy();
        resolveAppInfo({ok: true, json: () => Promise.resolve({name: 'x', version: '9.9.9'})});
        return promise.then(() => waitForFlush());
    }).then(() => {
        eq('destroy 后 appVersion 完成不补发 shown', captureEvents(h).filter(e => e === 'survey shown').length, 0);
        eq('destroy 后不重新打开弹窗', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('destroy 后无 Toast', h.toastCalls.length, 0);
        eq('destroy 后不写反馈状态', h.storage.getItem(STATE_KEY) === null, true);
    });
}

function testDestroyDuringSurveyWait() {
    const adapter = createFakeAdapter({surveys: [defaultSurvey()], stallSurveys: true});
    const h = initHarness({adapter, batchLayout: 'landscape'});
    const promise = h.api.open();
    return waitForFlush().then(() => {
        ok('Survey 回调已注册', typeof adapter.lastSurveyCallback === 'function');
        eq('Survey 等待期间不展示', h.document.querySelectorAll('.plf-backdrop').length, 0);
        h.api.destroy();
        adapter.lastSurveyCallback([defaultSurvey()]);
        return promise.then(() => waitForFlush());
    }).then(() => {
        eq('destroy 后迟到 Survey 回调不打开弹窗', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('destroy 后不发送 shown', captureEvents(h).length, 0);
        eq('destroy 后不写反馈状态', h.storage.getItem(STATE_KEY) === null, true);
    });
}

function testDestroyDuringSubmit() {
    const h = initHarness({batchLayout: 'landscape'});
    return h.api.open().then(() => waitForFlush()).then(() => {
        selectChoice(h, 'pixiv-batch-landscape');
        h.submitButton().click();
        h.api.destroy();
        return waitForFlush();
    }).then(() => {
        eq('destroy 后提交完成不写 submitted', h.storage.getItem(STATE_KEY) === null, true);
        eq('destroy 后不显示成功 Toast', h.toastCalls.length, 0);
        eq('destroy 后无失败错误显示', h.document.querySelectorAll('[data-plf-error]').length, 0);
        eq('destroy 后无弹窗', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('destroy 后不发送 survey sent', captureEvents(h).filter(e => e === 'survey sent').length, 0);
    });
}

function testOldGenerationCannotAffectNewGeneration() {
    const adapter = createFakeAdapter({surveys: [defaultSurvey()], stallSurveys: true});
    const h = initHarness({adapter, batchLayout: 'landscape'});
    const oldOpen = h.api.open();
    return waitForFlush().then(() => {
        const gen1Callback = adapter.lastSurveyCallback;
        ok('generation 1 已注册 Survey 回调', typeof gen1Callback === 'function');
        h.api.destroy();
        h.api.init(reinitOptions(h));
        const newOpen = h.api.open();
        return waitForFlush().then(() => {
            h.timers.advance(10000);
            return waitForFlush();
        }).then(() => {
            eq('generation 2 等待 Survey 期间不展示', h.document.querySelectorAll('.plf-backdrop').length, 0);
            gen1Callback([defaultSurvey()]);
            adapter.lastSurveyCallback([defaultSurvey()]);
            return Promise.all([oldOpen, newOpen]).then(() => waitForFlush());
        });
    }).then(() => {
        eq('旧 generation 迟到回调不影响新 generation 弹窗', h.document.querySelectorAll('.plf-backdrop').length, 1);
        eq('shown 只来自新 generation', captureEvents(h).filter(e => e === 'survey shown').length, 1);
        eq('storage 监听唯一', h.windowEvents.listenerCount('storage'), 1);
        eq('visibility 监听唯一', listenerCountFor(h, 'visibilitychange'), 1);
        eq('layout 监听唯一', listenerCountFor(h, 'pixiv:batch-layout-changed'), 1);
        eq('旧 generation 回调不再关闭新弹窗', h.document.querySelectorAll('.plf-backdrop').length, 1);
    });
}

function testDestroyIdempotence() {
    const h = initHarness({batchLayout: 'landscape'});
    doesNotThrow('destroy 一次', () => h.api.destroy());
    doesNotThrow('destroy 二次', () => h.api.destroy());
    doesNotThrow('destroy 三次', () => h.api.destroy());
    eq('destroy 后无 storage 监听', h.windowEvents.listenerCount('storage'), 0);
    eq('destroy 后无 layout 监听', listenerCountFor(h, 'pixiv:batch-layout-changed'), 0);
    eq('destroy 后无 visibility 监听', listenerCountFor(h, 'visibilitychange'), 0);
    eq('destroy 后定时器为空', h.timers.pending().length, 0);
    doesNotThrow('从未 init 时 destroy 安全', () => createHarness({}).api.destroy());
    const h2 = createHarness({batchLayout: 'landscape'});
    doesNotThrow('无配置环境 destroy 安全', () => h2.api.destroy());
}

function testOpenAfterDestroyIsNoop() {
    const h = initHarness({batchLayout: 'landscape'});
    h.api.destroy();
    const promise = h.api.open();
    return waitForFlush().then(() => promise).then(value => {
        eq('destroy 后 open 解析为 null', value, null);
        eq('destroy 后 open 不插入 script', h.scriptElements().length, 0);
        eq('destroy 后 open 不打开弹窗', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('destroy 后 open 不写反馈状态', h.storage.getItem(STATE_KEY) === null, true);
        eq('destroy 后 open 无 Toast', h.toastCalls.length, 0);
        eq('destroy 后 open 不注册 listener', listenerCountFor(h, 'pixiv:batch-layout-changed') === 0
            && h.windowEvents.listenerCount('storage') === 0, true);
        h.timers.advance(30000);
        return waitForFlush();
    }).then(() => {
        eq('destroy 后 open 无定时器残留', h.timers.pending().length, 0);
    });
}

function testOpenBeforeInitIsNoop() {
    const h = createHarness({batchLayout: 'landscape'});
    return waitForFlush().then(() => h.api.open()).then(value => {
        eq('从未 init 时 open 解析为 null', value, null);
        eq('从未 init 时 open 不插 script', h.scriptElements().length, 0);
        eq('从未 init 时 open 不打开弹窗', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('从未 init 时 open 不写反馈状态', h.storage.getItem(STATE_KEY) === null, true);
    });
}

function testOpenAfterDestroyDoesNotBlockReinit() {
    const h = initHarness({batchLayout: 'landscape'});
    h.api.destroy();
    return h.api.open().then(() => {
        h.api.init(reinitOptions(h));
        return h.api.open().then(() => waitForFlush());
    }).then(() => {
        eq('destroy→open→init→open 正常展示', h.document.querySelectorAll('.plf-backdrop').length, 1);
        eq('旧 open 未把 flowRunning 留为 true（不阻塞后续展示）',
            h.document.querySelectorAll('.plf-backdrop').length, 1);
        eq('storage 监听唯一', h.windowEvents.listenerCount('storage'), 1);
    });
}

function testIsDateObjectNoThrow() {
    const isDateObject = initHarness({}).api._internals.isDateObject;
    ok('正常 Date 返回 true', isDateObject(new Date('2026-01-01T00:00:00.000Z')) === true);
    ok('非法 Date 返回 false', isDateObject(new Date('invalid')) === false);
    ok('null 返回 false', isDateObject(null) === false);
    ok('undefined 返回 false', isDateObject(undefined) === false);
    ok('普通对象返回 false', isDateObject({}) === false);
    ok('Symbol.toStringTag 伪装 Date 但无 getTime 返回 false',
        isDateObject({[Symbol.toStringTag]: 'Date'}) === false);
    ok('伪装对象 getTime 返回 NaN 返回 false',
        isDateObject({[Symbol.toStringTag]: 'Date', getTime() { return NaN; }}) === false);
    ok('伪装对象 getTime 抛错返回 false',
        isDateObject({[Symbol.toStringTag]: 'Date', getTime() { throw new Error('boom'); }}) === false);
    ok('Object.prototype.toString 抛错的 Proxy 返回 false',
        isDateObject(new Proxy({}, {
            get(target, prop) {
                if (prop === Symbol.toStringTag) throw new Error('trap');
                return target[prop];
            }
        })) === false);
    ok('getTime 抛错的 Proxy 返回 false',
        isDateObject(new Proxy({}, {
            get(target, prop) {
                if (prop === Symbol.toStringTag) return 'Date';
                if (prop === 'getTime') return function () { throw new Error('trap'); };
                return target[prop];
            }
        })) === false);
}

/* ============================================================
   before_send timestamp（Date / string / 未知类型）
============================================================ */

function testBeforeSendTimestampMatrix() {
    const filter = initHarness({}).api._internals.beforeSendFilter;
    const base = {uuid: 'evt-1', event: 'survey sent', properties: {distinct_id: 'anon'}};

    const dateTs = new Date('2026-03-01T12:00:00.000Z');
    const outDate = filter(Object.assign({}, base, {timestamp: dateTs}));
    ok('Date timestamp 保留原始对象', outDate.timestamp === dateTs);
    eq('Date 时间值未被替换为当前时间', outDate.timestamp.getTime(), dateTs.getTime());
    ok('保留的是 Date 而非字符串', Object.prototype.toString.call(outDate.timestamp) === '[object Date]');

    const iso = '2026-03-01T12:00:00.000Z';
    const outIso = filter(Object.assign({}, base, {timestamp: iso}));
    eq('ISO string timestamp 保留', outIso.timestamp, iso);

    eq('null timestamp 省略', filter(Object.assign({}, base, {timestamp: null})).timestamp, undefined);
    eq('undefined timestamp 省略', filter(Object.assign({}, base, {timestamp: undefined})).timestamp, undefined);
    eq('普通对象 timestamp 省略', filter(Object.assign({}, base, {timestamp: {evil: true}})).timestamp, undefined);
    eq('非法 Date 省略', filter(Object.assign({}, base, {timestamp: new Date('invalid')})).timestamp, undefined);

    ok('Date timestamp 不影响 uuid / event / properties',
        outDate.uuid === 'evt-1' && outDate.event === 'survey sent' && outDate.properties.distinct_id === 'anon');
}

function testBeforeSendDateTimestampWithSurveyFields() {
    const filter = initHarness({}).api._internals.beforeSendFilter;
    const timestamp = new Date('2026-03-01T12:00:00.000Z');
    const out = filter({
        uuid: 'evt-2',
        event: 'survey shown',
        timestamp,
        $set: {'$survey_x_responded': true},
        $set_once: {'$initial_x': 'v'},
        $unset: ['old'],
        properties: {
            distinct_id: 'anon-2',
            token: 'phc_x',
            '$survey_id': 's1',
            '$survey_response_q-layout': 'pixiv-batch-portrait',
            $current_url: 'http://localhost:6999/pixiv-batch.html'
        }
    });
    ok('顶层 $set / $set_once / $unset 仍被删除',
        out.$set === undefined && out.$set_once === undefined && out.$unset === undefined);
    eq('$survey_id 保留', out.properties['$survey_id'], 's1');
    eq('$survey_response_* 保留', out.properties['$survey_response_q-layout'], 'pixiv-batch-portrait');
    ok('Date timestamp 与其他顶层字段并存', out.timestamp === timestamp);
    ok('输出顶层字段仅 uuid / event / timestamp / properties',
        Object.keys(out).every(k => ['uuid', 'event', 'timestamp', 'properties'].indexOf(k) >= 0));
}

function testFakeAdapterDefaultTimestampIsDate() {
    const h = initHarness({batchLayout: 'landscape'});
    return h.api.open().then(() => waitForFlush()).then(() => {
        const result = h.adapter.calls.results.find(r => r && r.event === 'survey shown');
        ok('fake adapter 默认 timestamp 为 Date 对象', result
            && Object.prototype.toString.call(result.timestamp) === '[object Date]');
    });
}

function testFakeAdapterTimestampOverrides() {
    return Promise.resolve().then(() => {
        const adapter = createFakeAdapter({surveys: [defaultSurvey()], timestamp: null});
        const h = initHarness({adapter, batchLayout: 'landscape'});
        return h.api.open().then(() => waitForFlush()).then(() => {
            const result = h.adapter.calls.results.find(r => r && r.event === 'survey shown');
            eq('null timestamp 在 before_send 后被省略', result.timestamp, undefined);
        });
    }).then(() => {
        const adapter = createFakeAdapter({surveys: [defaultSurvey()], timestamp: {bad: true}});
        const h = initHarness({adapter, batchLayout: 'landscape'});
        return h.api.open().then(() => waitForFlush()).then(() => {
            const result = h.adapter.calls.results.find(r => r && r.event === 'survey shown');
            eq('非法对象 timestamp 被省略', result.timestamp, undefined);
        });
    }).then(() => {
        const fixed = new Date('2026-06-01T00:00:00.000Z');
        const adapter = createFakeAdapter({surveys: [defaultSurvey()], timestamp: fixed});
        const h = initHarness({adapter, batchLayout: 'landscape'});
        return h.api.open().then(() => waitForFlush()).then(() => {
            const result = h.adapter.calls.results.find(r => r && r.event === 'survey shown');
            ok('Date 覆盖值原样保留', result && result.timestamp === fixed);
        });
    });
}

/* ============================================================
   SDK 初始化配置：Heatmap 弃用字段迁移
============================================================ */

function testSdkConfigHeatmapMigration() {
    ok('生产源码不再包含 enable_heatmaps 配置字段', SOURCE.indexOf('enable_heatmaps') < 0);
    ok('生产源码使用 capture_heatmaps', SOURCE.indexOf('capture_heatmaps') >= 0);
}

/* ============================================================
   solo 服务端模式（/api/layout-feedback/state）
============================================================ */

const SERVER_SCOPED_ID = 'plf_' + 'ab'.repeat(32);
const SERVER_RAW_UUID = '11111111-2222-4333-8444-555555555555';
// serverStateResponse 基准 serverTime：与假时钟初始时间一致（默认零偏差）。
const SERVER_BASE_TIME = 1000000;

function serverStateResponse(overrides) {
    return Object.assign({
        available: true,
        stateAvailable: true,
        distinctId: SERVER_SCOPED_ID,
        serverTime: SERVER_BASE_TIME,
        revision: 0,
        state: null,
        seen: {}
    }, overrides || {});
}

function waitForServerContext(h) {
    // 等待 init 时的服务端上下文装载与微任务链完成
    return waitForFlush();
}

function testBootstrapIdentitySemantics() {
    // 服务端 seen 为空：本页首次体验当前布局需要以 record_seen 命令提交；
    // minDistinct=1 保证自动展示门禁仍触发（服务端无历史体验）。
    const h = initHarness({
        batchLayout: 'landscape',
        serverState: serverStateResponse({seen: {}}),
        minDistinct: 1
    });
    return waitForServerContext(h).then(() => {
        h.timers.advance(11000);
        return waitForFlush();
    }).then(() => {
        eq('solo 服务端模式自动展示', h.document.querySelectorAll('.plf-backdrop').length, 1);
        const cfg = h.adapter.sdkConfig();
        ok('sdk config 使用 bootstrap', cfg && cfg.bootstrap);
        eq('bootstrap.distinctID 为 scoped ID', cfg.bootstrap.distinctID, SERVER_SCOPED_ID);
        eq('bootstrap.isIdentifiedID === false', cfg.bootstrap.isIdentifiedID, false);
        eq('sdk config 不再包含 distinct_id 初始化字段', cfg.distinct_id, undefined);
        ok('sdk.get_distinct_id() 等于 scoped ID', h.adapter.get_distinct_id() === SERVER_SCOPED_ID);
        const shown = h.adapter.calls.results.find(r => r && r.event === 'survey shown');
        ok('shown 事件携带 scoped distinct_id',
            shown && shown.properties.distinct_id === SERVER_SCOPED_ID);
        const posts = h.serverPosts.filter(p => p.body.command === 'record_seen');
        ok('布局体验以 record_seen 命令提交（不发送完整 seen）', posts.length >= 1);
        posts.forEach(p => {
            ok('record_seen 只含合法布局 ID', Array.isArray(p.body.layoutIds)
                && p.body.layoutIds.every(id => LAYOUT_IDS.indexOf(id) >= 0));
            ok('record_seen 不携带完整 state / seen', !p.body.state && !p.body.seen);
            ok('record_seen 携带 expectedRevision 与 surveyId',
                typeof p.body.expectedRevision === 'number' && p.body.surveyId === h.config.surveyId);
        });
        const localStateRaw = h.storage.getItem(STATE_KEY);
        ok('server 无 state 时协调缓存不残留 STATE_KEY',
            localStateRaw === null || JSON.parse(localStateRaw).surveyId !== h.config.surveyId);
    });
}

function testBootstrapIdentitySemanticsLocalCache() {
    const h = initHarness({
        batchLayout: 'landscape',
        serverState: serverStateResponse({seen: seenObject()})
    });
    return waitForServerContext(h).then(() => {
        h.timers.advance(600);
        return waitForFlush();
    }).then(() => {
        const localSeen = JSON.parse(h.storage.getItem(SEEN_KEY));
        ok('serverBacked 用权威快照维护 SEEN_KEY 协调缓存',
            localSeen && LAYOUT_IDS.every(id => localSeen[id]
                && typeof localSeen[id].lastSeenAt === 'number' && localSeen[id].lastSeenAt > 0));
        const localState = h.storage.getItem(STATE_KEY);
        ok('server 无 state 时协调缓存不残留旧 STATE_KEY',
            localState === null || JSON.parse(localState).surveyId !== h.config.surveyId);
    });
}

function testBootstrapIdentityMismatchFailsClosed() {
    const h = initHarness({
        adapter: createFakeAdapter({surveys: [defaultSurvey()], distinctId: 'some-other-id'}),
        batchLayout: 'landscape',
        serverState: serverStateResponse({seen: seenObject()})
    });
    return waitForServerContext(h).then(() => {
        h.timers.advance(11000);
        return waitForFlush();
    }).then(() => {
        eq('get_distinct_id 不一致 fail closed：不展示', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('不一致不请求 Survey', h.adapter.calls.getSurveys.length, 0);
        eq('不一致不发送事件', h.adapter.calls.capture.length, 0);
        const warnings = JSON.stringify(h.consoleWarn);
        ok('记录安全 warning', warnings.indexOf('does not match') >= 0);
        ok('warning 不含 scoped ID / token / survey', warnings.indexOf(SERVER_SCOPED_ID) < 0
            && warnings.indexOf('phc_test_project_token') < 0
            && warnings.indexOf(h.config.surveyId) < 0);
    });
}

function testBootstrapIdentityMismatchViaSurveyFlowNeverShown() {
    // 手动 open 场景：身份不一致同样 fail closed。
    const h = initHarness({
        adapter: createFakeAdapter({surveys: [defaultSurvey()], distinctId: 'stale-id'}),
        batchLayout: 'landscape',
        serverState: serverStateResponse({})
    });
    return h.api.open().then(() => waitForFlush()).then(() => {
        eq('身份不一致时不显示弹窗', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('身份不一致不发送 shown', captureEvents(h).length, 0);
    });
}

function testServerModeSubmittedStateGatesAutoShow() {
    const h = initHarness({
        batchLayout: 'landscape',
        serverState: serverStateResponse({
            state: {
                surveyId: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
                status: 'submitted',
                updatedAt: 1,
                snoozedUntil: 0
            },
            seen: seenObject()
        })
    });
    // 先让服务端状态装载完成再推进自动评估
    return waitForServerContext(h).then(() => {
        h.timers.advance(11000);
        return waitForFlush();
    }).then(() => {
        eq('服务端 submitted 不再展示', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('不发送 shown', captureEvents(h).filter(e => e === 'survey shown').length, 0);
        eq('不初始化 SDK', h.adapter.sdkConfig() === null, true);
    });
}

function testServerModeSubmitPersistsToServer() {
    const h = initHarness({
        batchLayout: 'landscape',
        serverState: serverStateResponse({seen: seenObject()})
    });
    return waitForServerContext(h).then(() => {
        h.timers.advance(11000);
        return waitForFlush();
    }).then(() => {
        selectChoice(h, 'pixiv-batch-landscape');
        h.submitButton().click();
        return waitForFlush();
    }).then(() => {
        eq('服务端模式提交成功', captureEvents(h).filter(e => e === 'survey sent').length, 1);
        const localState = JSON.parse(h.storage.getItem(STATE_KEY));
        eq('本地协调缓存写 submitted', localState.status, 'submitted');
        eq('本地缓存绑定 surveyId', localState.surveyId, h.config.surveyId);
        const post = h.serverPosts.find(p => p.body.command === 'submitted');
        ok('PostHog 接受后发送 submitted 命令', !!post);
        eq('submitted 命令携带 surveyId', post.body.surveyId, h.config.surveyId);
        eq('submitted 命令不携带建议 / 布局回答 / 完整状态',
            post.body.suggestion === undefined
            && post.body.selectedChoice === undefined
            && post.body.state === undefined && post.body.seen === undefined, true);
        ok('submitted 命令携带 expectedRevision', typeof post.body.expectedRevision === 'number');
    });
}

function testServerModeSnoozeAndNeverPersist() {
    return Promise.resolve().then(() => {
        const h = initHarness({
            batchLayout: 'landscape',
            serverState: serverStateResponse({seen: seenObject()})
        });
        return waitForServerContext(h).then(() => {
            h.timers.advance(11000);
            return waitForFlush();
        }).then(() => {
            h.actionButton('snooze').click();
            return waitForFlush();
        }).then(() => {
            const post = h.serverPosts.find(p => p.body.command === 'snooze');
            ok('稍后再说以 snooze 命令持久化', !!post);
            eq('snooze 不携带客户端 snoozedUntil / 时间戳', post.body.snoozedUntil === undefined
                && post.body.updatedAt === undefined
                && post.body.firstSeenAt === undefined
                && post.body.lastSeenAt === undefined, true);
            const localState = JSON.parse(h.storage.getItem(STATE_KEY));
            eq('本地协调缓存写 snoozed', localState.status, 'snoozed');
        });
    }).then(() => {
        const h = initHarness({
            batchLayout: 'landscape',
            serverState: serverStateResponse({seen: seenObject()})
        });
        return waitForServerContext(h).then(() => {
            h.timers.advance(11000);
            return waitForFlush();
        }).then(() => {
            h.actionButton('never').click();
            return waitForFlush();
        }).then(() => {
            const post = h.serverPosts.find(p => p.body.command === 'never');
            ok('不再询问以 never 命令持久化', !!post);
            eq('never 不携带 layoutIds', post.body.layoutIds === undefined, true);
        });
    });
}

function testServerModeSeenRecordsServerSide() {
    const h = initHarness({
        batchLayout: 'landscape',
        serverState: serverStateResponse({}),
        minDistinct: 2
    });
    return waitForServerContext(h).then(() => {
        h.dispatchLayoutChanged('portrait', 'landscape');
        h.timers.advance(600);
        return waitForFlush();
    }).then(() => {
        const posts = h.serverPosts.filter(p => p.body.command === 'record_seen');
        ok('布局体验以 record_seen 命令提交', posts.length >= 1);
        const layoutIds = posts[posts.length - 1].body.layoutIds;
        ok('record_seen 布局 ID 去重且合法', Array.isArray(layoutIds)
            && layoutIds.indexOf('pixiv-batch-portrait') >= 0
            && layoutIds.every((id, index) => layoutIds.indexOf(id) === index));
        ok('record_seen 不携带完整 seen / state', posts.every(p => !p.body.seen && !p.body.state));
        ok('serverBacked 仍写 SEEN_KEY 本地协调缓存',
            h.storage.getItem(SEEN_KEY) !== null);
    });
}

function testServerModeUnavailableFallsBackToLocal() {
    return Promise.resolve().then(() => {
        const h = initHarness({
            batchLayout: 'landscape',
            serverFetch: '403',
            minDistinct: 1
        });
        h.timers.advance(11000);
        return waitForFlush().then(() => {
            eq('403（multi 模式）回退 localStorage 展示', h.document.querySelectorAll('.plf-backdrop').length, 1);
            const cfg = h.adapter.sdkConfig();
            eq('回退模式 sdk config 不包含 bootstrap', cfg.bootstrap === undefined, true);
            eq('回退模式不包含 distinct_id 初始化字段', cfg.distinct_id, undefined);
        });
    }).then(() => {
        const h = initHarness({
            batchLayout: 'landscape',
            serverFetch: 'fail',
            minDistinct: 1
        });
        h.timers.advance(11000);
        return waitForFlush().then(() => {
            eq('服务端不可达回退 localStorage 展示', h.document.querySelectorAll('.plf-backdrop').length, 1);
            eq('服务端不可达不设置 bootstrap', h.adapter.sdkConfig().bootstrap === undefined, true);
        });
    }).then(() => {
        const h = initHarness({
            batchLayout: 'landscape',
            serverFetch: 'pending',
            minDistinct: 1
        });
        h.timers.advance(11000);
        return waitForFlush().then(() => {
            eq('服务端超时回退 localStorage 展示', h.document.querySelectorAll('.plf-backdrop').length, 1);
            eq('超时后 Promise 不悬挂（SDK 已初始化）', h.adapter.sdkConfig() !== null, true);
        });
    });
}

function testServerGetUrlCarriesEncodedSurveyId() {
    const h = initHarness({
        batchLayout: 'landscape',
        serverState: serverStateResponse({})
    });
    return waitForServerContext(h).then(() => {
        const url = h.fetchCalls.find(c => c.url.indexOf('/api/layout-feedback/state') >= 0
            && !(c.init && c.init.method === 'POST'));
        ok('GET 请求存在', !!url);
        ok('GET 携带 surveyId 查询参数', url.url.indexOf('surveyId=aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee') >= 0);
        ok('GET 使用 same-origin credentials', url.init.credentials === 'same-origin');
    });
}

function testServerBackedStateAndSeenFromAuthoritativeSnapshot() {
    const h = initHarness({
        batchLayout: 'landscape',
        serverState: serverStateResponse({
            state: {
                surveyId: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
                status: 'snoozed',
                updatedAt: 100,
                snoozedUntil: 2000000
            },
            seen: seenObject()
        })
    });
    return waitForServerContext(h).then(() => {
        h.timers.advance(11000);
        return waitForFlush();
    }).then(() => {
        eq('权威快照 state 生效：有效 snooze 不展示', h.document.querySelectorAll('.plf-backdrop').length, 0);
        const localState = JSON.parse(h.storage.getItem(STATE_KEY));
        eq('权威快照同步到本地协调缓存', localState.status, 'snoozed');
        const localSeen = JSON.parse(h.storage.getItem(SEEN_KEY));
        ok('权威 seen 同步到本地协调缓存',
            localSeen && localSeen['pixiv-batch-alt']
                && typeof localSeen['pixiv-batch-alt'].lastSeenAt === 'number'
                && localSeen['pixiv-batch-alt'].lastSeenAt > 0);
    });
}

function testServerModeSubmitPreflightBlocksOnFreshServerState() {
    // 弹窗打开后另一设备把服务端写成 submitted：提交前 preflight GET 必须发现并取消。
    const h = initHarness({
        batchLayout: 'landscape',
        serverState: serverStateResponse({seen: seenObject()})
    });
    return waitForServerContext(h).then(() => {
        h.timers.advance(11000);
        return waitForFlush();
    }).then(() => {
        selectChoice(h, 'pixiv-batch-landscape');
        h.setServerState(serverStateResponse({
            revision: 2,
            state: {
                surveyId: h.config.surveyId,
                status: 'submitted',
                updatedAt: 999,
                snoozedUntil: 0
            },
            seen: seenObject()
        }));
        h.submitButton().click();
        return waitForFlush();
    }).then(() => {
        eq('preflight 发现 submitted：不发送 survey sent', captureEvents(h).filter(e => e === 'survey sent').length, 0);
        eq('preflight 拦截后关闭弹窗', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('不额外发送 dismissed', captureEvents(h).indexOf('survey dismissed'), -1);
        ok('显示已在其他页面处理提示', h.toastCalls.length === 1);
        const posts = h.serverPosts.filter(p => p.body.command === 'submitted');
        eq('不发送 submitted 命令', posts.length, 0);
    });
}

function testServerModeSubmitPreflightNeverAndSnooze() {
    return Promise.resolve().then(() => {
        const h = initHarness({
            batchLayout: 'landscape',
            serverState: serverStateResponse({seen: seenObject()})
        });
        return waitForServerContext(h).then(() => {
            h.timers.advance(11000);
            return waitForFlush();
        }).then(() => {
            selectChoice(h, 'pixiv-batch-portrait');
            h.setServerState(serverStateResponse({
                revision: 2,
                state: {surveyId: h.config.surveyId, status: 'never', updatedAt: 999, snoozedUntil: 0},
                seen: seenObject()
            }));
            h.submitButton().click();
            return waitForFlush();
        }).then(() => {
            eq('preflight 发现 never：不发送 survey sent', captureEvents(h).filter(e => e === 'survey sent').length, 0);
            eq('preflight never 拦截后关闭弹窗', h.document.querySelectorAll('.plf-backdrop').length, 0);
        });
    }).then(() => {
        const h = initHarness({
            batchLayout: 'landscape',
            serverState: serverStateResponse({seen: seenObject()})
        });
        return waitForServerContext(h).then(() => {
            h.timers.advance(11000);
            return waitForFlush();
        }).then(() => {
            selectChoice(h, 'pixiv-batch-alt');
            h.setServerState(serverStateResponse({
                revision: 2,
                state: {surveyId: h.config.surveyId, status: 'snoozed', updatedAt: 999, snoozedUntil: 2000000},
                seen: seenObject()
            }));
            h.submitButton().click();
            return waitForFlush();
        }).then(() => {
            eq('preflight 发现有效 snooze：不发送 survey sent', captureEvents(h).filter(e => e === 'survey sent').length, 0);
            eq('preflight snooze 拦截后关闭弹窗', h.document.querySelectorAll('.plf-backdrop').length, 0);
        });
    });
}

function testServerModePreflightAllowsCaptureThenSendsSubmitted() {
    const h = initHarness({
        batchLayout: 'landscape',
        serverState: serverStateResponse({seen: seenObject()})
    });
    return waitForServerContext(h).then(() => {
        h.timers.advance(11000);
        return waitForFlush();
    }).then(() => {
        selectChoice(h, 'pixiv-batch-landscape');
        h.submitButton().click();
        return waitForFlush();
    }).then(() => {
        eq('preflight 允许后正常 capture', captureEvents(h).filter(e => e === 'survey sent').length, 1);
        const posts = h.serverPosts.filter(p => p.body.command === 'submitted');
        eq('capture 接受后发送 submitted 命令', posts.length, 1);
        ok('submitted 命令携带服务器返回的最新 revision',
            typeof posts[0].body.expectedRevision === 'number');
    });
}

function testServerCommandConflictRetriesOnce() {
    let postCount = 0;
    const h = initHarness({
        batchLayout: 'landscape',
        serverState: serverStateResponse({seen: seenObject()}),
        serverPostResponse: ({body}) => {
            if (body.command !== 'submitted') return undefined;
            postCount++;
            if (postCount === 1) {
                // 第一次冲突：服务端已推进到 revision 5（state=null，submitted 可升级）
                return {
                    status: 409,
                    json: () => Promise.resolve(serverStateResponse({
                        revision: 5,
                        state: null,
                        seen: seenObject()
                    }))
                };
            }
            return {
                ok: true,
                json: () => Promise.resolve(serverStateResponse({
                    revision: 6,
                    state: {
                        surveyId: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
                        status: 'submitted',
                        updatedAt: 1,
                        snoozedUntil: 0
                    },
                    seen: seenObject()
                }))
            };
        }
    });
    return waitForServerContext(h).then(() => {
        h.timers.advance(11000);
        return waitForFlush();
    }).then(() => {
        // 用户提交后发送 submitted：首次 409，更新快照后重试一次成功。
        selectChoice(h, 'pixiv-batch-landscape');
        h.submitButton().click();
        return waitForFlush();
    }).then(() => {
        const submittedPosts = h.serverPosts.filter(p => p.body.command === 'submitted');
        eq('409 后基于最新 revision 重试一次', submittedPosts.length, 2);
        eq('第二次请求携带最新 revision', submittedPosts[1].body.expectedRevision, 5);
        const localState = JSON.parse(h.storage.getItem(STATE_KEY));
        eq('冲突快照同步到本地协调缓存', localState.status, 'submitted');
    });
}

function testServerCommandConflictDoesNotDowngradeSubmitted() {
    let postCount = 0;
    const h = initHarness({
        batchLayout: 'landscape',
        serverState: serverStateResponse({seen: seenObject()}),
        serverPostResponse: ({body}) => {
            if (body.command !== 'never') return undefined;
            postCount++;
            if (postCount === 1) {
                return {
                    status: 409,
                    json: () => Promise.resolve(serverStateResponse({
                        revision: 5,
                        state: {
                            surveyId: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
                            status: 'submitted',
                            updatedAt: 1,
                            snoozedUntil: 0
                        },
                        seen: seenObject()
                    }))
                };
            }
            return {
                ok: true,
                json: () => Promise.resolve(serverStateResponse({
                    revision: 6,
                    state: {
                        surveyId: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
                        status: 'never',
                        updatedAt: 1,
                        snoozedUntil: 0
                    },
                    seen: seenObject()
                }))
            };
        }
    });
    return waitForServerContext(h).then(() => {
        h.timers.advance(11000);
        return waitForFlush();
    }).then(() => {
        // 用户点「不再询问」：服务端已是 submitted → 409 后不得重试降级。
        h.actionButton('never').click();
        return waitForFlush();
    }).then(() => {
        const neverPosts = h.serverPosts.filter(p => p.body.command === 'never');
        eq('当前 submitted 时 never 只尝试一次（不降级、不重试）', neverPosts.length, 1);
        const localState = JSON.parse(h.storage.getItem(STATE_KEY));
        eq('本地协调缓存不被降级为 never', localState.status, 'submitted');
    });
}

function testServerCommandNetworkFailureSafeDegrade() {
    const h = initHarness({
        batchLayout: 'landscape',
        serverState: serverStateResponse({seen: seenObject()}),
        serverPostResponse: 'fail'
    });
    return waitForServerContext(h).then(() => {
        h.timers.advance(11000);
        return waitForFlush();
    }).then(() => {
        h.actionButton('snooze').click();
        return waitForFlush();
    }).then(() => {
        eq('服务端命令失败仍关闭弹窗', h.document.querySelectorAll('.plf-backdrop').length, 0);
        const localState = JSON.parse(h.storage.getItem(STATE_KEY));
        eq('失败保留本地协调缓存回退', localState.status, 'snoozed');
        ok('记录不含用户数据的 warning', h.consoleWarn.some(args => {
            const text = JSON.stringify(args);
            return text.indexOf('server state save failed') >= 0
                && text.indexOf('phc_test_project_token') < 0
                && text.indexOf(h.config.surveyId) < 0;
        }));
    });
}

function testServerCommandRecordSeenConflictRetries() {
    let postCount = 0;
    // 服务端 seen 缺 portrait：切换布局后 portrait 需要 record_seen 提交，
    // 从而覆盖 409 重试路径（按布局 ID 存在性，服务端已有布局不重复提交）。
    const serverSeenWithoutPortrait = {
        'pixiv-batch-landscape': {firstSeenAt: 1, lastSeenAt: 1},
        'pixiv-batch-alt': {firstSeenAt: 2, lastSeenAt: 2}
    };
    const h = initHarness({
        batchLayout: 'landscape',
        serverState: serverStateResponse({seen: serverSeenWithoutPortrait}),
        serverPostResponse: () => {
            postCount++;
            if (postCount === 1) {
                return {
                    status: 409,
                    json: () => Promise.resolve(serverStateResponse({
                        revision: 7,
                        state: null,
                        seen: serverSeenWithoutPortrait
                    }))
                };
            }
            return {
                ok: true,
                json: () => Promise.resolve(serverStateResponse({
                    revision: 8,
                    state: null,
                    seen: Object.assign(serverSeenWithoutPortrait, {
                        'pixiv-batch-portrait': {firstSeenAt: 1, lastSeenAt: 1}
                    })
                }))
            };
        }
    });
    return waitForServerContext(h).then(() => {
        h.timers.advance(11000);
        return waitForFlush();
    }).then(() => {
        h.dispatchLayoutChanged('portrait', 'landscape');
        h.timers.advance(600);
        return waitForFlush();
    }).then(() => {
        const seenPosts = h.serverPosts.filter(p => p.body.command === 'record_seen');
        ok('record_seen 409 后重试', seenPosts.length >= 2);
        ok('重试携带最新 revision',
            seenPosts.slice(1).some(p => p.body.expectedRevision === 7));
    });
}

function testServerModeCrossTabCoordination() {
    // 标签页 A（serverBacked）提交 → 标签页 B 收到 storage 事件即时关闭弹窗，
    // 并触发一次有限服务端刷新；storage 消息不直接伪造 serverRevision。
    return Promise.resolve().then(() => {
        const hA = initHarness({
            batchLayout: 'landscape',
            serverState: serverStateResponse({seen: seenObject()})
        });
        return waitForServerContext(hA).then(() => {
            hA.timers.advance(11000);
            return waitForFlush();
        }).then(() => {
            selectChoice(hA, 'pixiv-batch-landscape');
            hA.submitButton().click();
            return waitForFlush();
        }).then(() => {
            const localState = JSON.parse(hA.storage.getItem(STATE_KEY));
            eq('标签页 A 本地协调缓存为 submitted', localState.status, 'submitted');
            // 标签页 B：共享同一 localStorage（协调缓存）
            const hB = initHarness({
                batchLayout: 'landscape',
                storage: {[STATE_KEY]: JSON.stringify(localState), [SEEN_KEY]: hA.storage.getItem(SEEN_KEY)},
                serverState: serverStateResponse({seen: seenObject()})
            });
            return waitForServerContext(hB).then(() => hB.api.open()).then(() => waitForFlush())
                .then(() => {
                    eq('标签页 B 弹窗已打开', hB.document.querySelectorAll('.plf-backdrop').length, 1);
                    hB.dispatchStorage(STATE_KEY, JSON.stringify(localState));
                    return waitForFlush();
                }).then(() => {
                    eq('标签页 B 收到 storage 事件后关闭', hB.document.querySelectorAll('.plf-backdrop').length, 0);
                    eq('标签页 B 不重复发送 dismissed', captureEvents(hB).indexOf('survey dismissed'), -1);
                    eq('storage 事件触发一次有限服务端刷新', hB.stateFetchCount() >= 2, true);
                });
        });
    }).then(() => {
        // 永不伪造 serverRevision：storage 值再强也不直接改 revision
        const h = initHarness({
            batchLayout: 'landscape',
            serverState: serverStateResponse({seen: seenObject()})
        });
        let before = 0;
        return waitForServerContext(h).then(() => {
            before = h.stateFetchCount();
            h.dispatchStorage(STATE_KEY, JSON.stringify({
                surveyId: h.config.surveyId, status: 'submitted', updatedAt: 1, snoozedUntil: 0
            }));
            return waitForFlush();
        }).then(() => {
            ok('storage 事件后发起有限刷新', h.stateFetchCount() > before);
            eq('刷新次数有限（仅一次事件一次刷新）', h.stateFetchCount() - before, 1);
        });
    });
}

function testLocalStateReconciliation() {
    // 服务端恢复返回空状态，本地曾提交 submitted → 有限回放 submitted 命令。
    const submitted = JSON.stringify({
        surveyId: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
        status: 'submitted', updatedAt: 100, snoozedUntil: 0
    });
    const h = initHarness({
        batchLayout: 'landscape',
        storage: {[STATE_KEY]: submitted, [SEEN_KEY]: JSON.stringify(seenObject())},
        serverState: serverStateResponse({seen: {}})
    });
    return waitForServerContext(h).then(() => {
        const posts = h.serverPosts.filter(p => p.body.command === 'submitted');
        eq('本地 submitted + 服务端空状态 → 回放 submitted', posts.length, 1);
        const seenPosts = h.serverPosts.filter(p => p.body.command === 'record_seen');
        ok('本地 seen 合并为 record_seen 命令', seenPosts.length >= 1);
        ok('回放不发送 PostHog 事件', h.adapter.calls.capture.length === 0);
    });
}

function testLocalStateReconciliationNeverOverSnoozed() {
    const localNever = JSON.stringify({
        surveyId: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
        status: 'never', updatedAt: 100, snoozedUntil: 0
    });
    const h = initHarness({
        batchLayout: 'landscape',
        storage: {[STATE_KEY]: localNever},
        serverState: serverStateResponse({
            state: {
                surveyId: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
                status: 'snoozed', updatedAt: 1, snoozedUntil: 2000000
            },
            seen: {}
        })
    });
    return waitForServerContext(h).then(() => {
        const posts = h.serverPosts.filter(p => p.body.command === 'never');
        eq('本地 never + 服务端 snoozed → 回放 never 升级', posts.length, 1);
    });
}

function testLocalStateReconciliationNeverDowngrades() {
    const localSnoozed = JSON.stringify({
        surveyId: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
        status: 'snoozed', updatedAt: 100, snoozedUntil: 2000000
    });
    const h = initHarness({
        batchLayout: 'landscape',
        storage: {[STATE_KEY]: localSnoozed},
        serverState: serverStateResponse({
            state: {
                surveyId: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
                status: 'submitted', updatedAt: 1, snoozedUntil: 0
            },
            seen: {}
        })
    });
    return waitForServerContext(h).then(() => {
        const posts = h.serverPosts.filter(p => p.body.command === 'snooze'
            || p.body.command === 'never' || p.body.command === 'submitted');
        eq('服务端 submitted 时本地 snoozed 不回放（不降级）', posts.length, 0);
    });
}

function testLocalStateReconciliationIgnoresInvalidOrOtherSurvey() {
    return Promise.resolve().then(() => {
        const h = initHarness({
            batchLayout: 'landscape',
            storage: {[STATE_KEY]: JSON.stringify({
                surveyId: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
                status: 'weird', updatedAt: 100, snoozedUntil: 0
            })},
            serverState: serverStateResponse({})
        });
        return waitForServerContext(h).then(() => {
            const posts = h.serverPosts.filter(p => p.body.command !== 'record_seen');
            eq('非法本地状态不上传', posts.length, 0);
        });
    }).then(() => {
        const h = initHarness({
            batchLayout: 'landscape',
            storage: {[STATE_KEY]: JSON.stringify({
                surveyId: 'old-survey-id-123456',
                status: 'submitted', updatedAt: 100, snoozedUntil: 0
            })},
            serverState: serverStateResponse({})
        });
        return waitForServerContext(h).then(() => {
            const posts = h.serverPosts.filter(p => p.body.command !== 'record_seen');
            eq('旧 Survey 本地状态不上传', posts.length, 0);
        });
    });
}

function testDestroyDuringServerLoad() {
    const h = initHarness({serverFetch: 'pending', batchLayout: 'landscape'});
    let resolved = false;
    const promise = h.api.open().then(value => { resolved = true; return value; });
    return waitForFlush().then(() => {
        ok('server GET 已发出', h.stateFetchCount() >= 1);
        h.api.destroy();
        return waitForFlush();
    }).then(() => {
        ok('destroy 后 open Promise 安全完成', resolved);
        eq('destroy abort 了在途 GET', h.serverAbortCalls.length >= 1, true);
        // 迟到响应不得写任何状态
        h.serverFetchGate.resolve({ok: true, json: () => Promise.resolve(
            serverStateResponse({seen: seenObject()}))});
        return waitForFlush();
    }).then(() => {
        eq('迟到 GET 响应不初始化 SDK', h.adapter.sdkConfig() === null, true);
        eq('迟到 GET 响应不显示弹窗', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('迟到 GET 响应不写反馈状态', h.storage.getItem(STATE_KEY) === null, true);
    });
}

function testDestroyThenReinitReProbesServer() {
    const h = initHarness({
        batchLayout: 'landscape',
        serverState: serverStateResponse({})
    });
    return waitForServerContext(h).then(() => {
        const initialGets = h.stateFetchCount();
        ok('init 探测服务端', initialGets >= 1);
        h.api.destroy();
        h.api.init(reinitOptions(h));
        return waitForFlush();
    }).then(() => {
        ok('re-init 重新探测服务端（不复用旧快照）', h.stateFetchCount() > 1);
        h.api.destroy();
        h.timers.advance(30000);
        return waitForFlush();
    }).then(() => {
        eq('destroy 后无定时器残留', h.timers.pending().length, 0);
    });
}

function testDestroyDuringServerCommand() {
    const h = initHarness({
        batchLayout: 'landscape',
        serverState: serverStateResponse({seen: seenObject()}),
        serverPostResponse: 'pending'
    });
    return waitForServerContext(h).then(() => {
        h.timers.advance(11000);
        return waitForFlush();
    }).then(() => {
        h.actionButton('snooze').click();
        return waitForFlush();
    }).then(() => {
        h.api.destroy();
        // 迟到 POST 响应不得影响新 generation
        return waitForFlush();
    }).then(() => {
        eq('destroy 后无弹窗', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('destroy 后无 Toast', h.toastCalls.length, 0);
        eq('destroy 后定时器已清理', h.timers.pending().length, 0);
    });
}

function testServerSeenDebounceTimerClearedOnDestroy() {
    const h = initHarness({
        batchLayout: 'landscape',
        serverState: serverStateResponse({})
    });
    return waitForServerContext(h).then(() => {
        h.dispatchLayoutChanged('portrait', 'landscape');
        ok('seen 去抖定时器已调度', h.timers.pending().length > 0);
        const postsBefore = h.serverPosts.filter(p => p.body.command === 'record_seen').length;
        h.api.destroy();
        eq('destroy 清除 seen 去抖定时器', h.timers.pending().length, 0);
        h.timers.advance(5000);
        return waitForFlush();
    }).then(() => {
        const posts = h.serverPosts.filter(p => p.body.command === 'record_seen');
        eq('destroy 后不再发送 record_seen', posts.length, 1);
    });
}

function testDisabledConfigDoesNotProbeServer() {
    const h = initHarness({
        publicConfig: {
            enabled: false, projectToken: '', surveyId: '', apiHost: '', uiHost: ''
        },
        batchLayout: 'landscape'
    });
    return h.api.open().then(() => waitForFlush()).then(() => {
        eq('enabled=false 不展示调查', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('enabled=false 不加载 SDK', h.scriptElements().length, 0);
        eq('enabled=false 不请求 server state', h.fetchCalls.filter(c =>
            c.url.indexOf('/api/layout-feedback/state') >= 0).length, 0);
        h.timers.advance(11000);
        return waitForFlush();
    }).then(() => {
        eq('enabled=false 自动流程不动作', h.document.querySelectorAll('.plf-backdrop').length, 0);
    });
}

function testWaitForIdentityBeforeSdkInit() {
    // server GET 慢：open() 等待身份，SDK 初始化发生在 GET 完成后。
    const h = initHarness({
        batchLayout: 'landscape',
        serverFetch: 'pending',
        minDistinct: 1
    });
    const promise = h.api.open();
    return waitForFlush().then(() => {
        eq('server GET 完成前 SDK 未初始化', h.adapter.sdkConfig() === null, true);
        eq('server GET 完成前不显示弹窗', h.document.querySelectorAll('.plf-backdrop').length, 0);
        h.serverFetchGate.resolve({ok: true, json: () => Promise.resolve(
            serverStateResponse({seen: seenObject()}))});
        return promise.then(() => waitForFlush());
    }).then(() => {
        eq('server GET 完成后 SDK 才初始化', h.adapter.sdkConfig() !== null, true);
        eq('bootstrap 使用 scoped ID', h.adapter.sdkConfig().bootstrap.distinctID, SERVER_SCOPED_ID);
        eq('身份确定后正常展示', h.document.querySelectorAll('.plf-backdrop').length, 1);
    });
}

function testOpenWaitsForServer403Fallback() {
    const h = initHarness({
        batchLayout: 'landscape',
        serverFetch: '403',
        minDistinct: 1
    });
    return h.api.open().then(() => waitForFlush()).then(() => {
        eq('403 后浏览器匿名模式初始化', h.adapter.sdkConfig() !== null, true);
        eq('403 后不设置 bootstrap', h.adapter.sdkConfig().bootstrap === undefined, true);
        eq('403 后本地模式可展示', h.document.querySelectorAll('.plf-backdrop').length, 1);
    });
}

function testServerModePrivacyNoRawUuid() {
    const h = initHarness({
        batchLayout: 'landscape',
        serverState: serverStateResponse({seen: seenObject()})
    });
    return waitForServerContext(h).then(() => {
        h.timers.advance(11000);
        return waitForFlush();
    }).then(() => {
        const allJson = JSON.stringify({
            fetchCalls: h.fetchCalls.map(c => c.url),
            posts: h.serverPosts,
            warns: h.consoleWarn
        });
        ok('前端任何地方不出现原始安装 UUID', allJson.indexOf(SERVER_RAW_UUID) < 0);
        ok('前端不出现非 plf 前缀身份', allJson.indexOf('install-00000000') < 0);
        const stateRaw = h.storage.getItem(STATE_KEY);
        ok('本地协调缓存不含原始安装 UUID', !stateRaw || stateRaw.indexOf(SERVER_RAW_UUID) < 0);
    });
}

function testServerModeReinitIdentityChangeFailsClosed() {
    const h = initHarness({
        batchLayout: 'landscape',
        serverState: serverStateResponse({seen: seenObject()})
    });
    return waitForServerContext(h).then(() => {
        h.timers.advance(11000);
        return waitForFlush();
    }).then(() => {
        eq('第一代正常展示', h.document.querySelectorAll('.plf-backdrop').length, 1);
        const surveysBefore = h.adapter.calls.getSurveys.length;
        h.api.destroy();
        // 身份变化（同一页面重新 init 后服务器下发另一个 scoped ID）
        h.setServerState(serverStateResponse({
            distinctId: 'plf_' + 'cd'.repeat(32),
            seen: seenObject()
        }));
        h.api.init(reinitOptions(h));
        return h.api.open().then(() => waitForFlush());
    }).then(() => {
        eq('身份变化不静默复用旧 singleton（fail closed 不展示）',
            h.document.querySelectorAll('.plf-backdrop').length, 0);
        const warnings = JSON.stringify(h.consoleWarn);
        ok('记录安全 warning', warnings.indexOf('different public configuration') >= 0);
        ok('warning 不含 scoped ID', warnings.indexOf('plf_') < 0);
    });
}


/* ============================================================
   reconciliation 等待语义与缓存语义（A-I）
============================================================ */

function localStateValue(status, snoozedUntil, surveyId) {
    return JSON.stringify({
        surveyId: surveyId === undefined ? 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee' : surveyId,
        status,
        updatedAt: 100,
        snoozedUntil: snoozedUntil === undefined ? 0 : snoozedUntil
    });
}

function testReconcileSubmittedReplaysAndWaits() {
    // A. local submitted + server null + replay 成功：
    // loadServerContext 必须等待 replay；确认后 server state 为 submitted、
    // localStorage 为权威快照；SDK 不加载、Survey 不请求、弹窗不显示。
    let release = null;
    const h = initHarness({
        // 不设置 batchLayout：避免 init 的 recordSeen 触发 400ms record_seen flush，
        // 使 revision 只由被 gate 的 submitted 回放推进（同 revision 内容一致性校验
        // 要求 mock 响应与当前 revision 严格一致）。
        storage: {
            [STATE_KEY]: localStateValue('submitted'),
            [SEEN_KEY]: JSON.stringify(seenObject())
        },
        serverState: serverStateResponse({seen: seenObject()}),
        serverPostResponse: ({body}) => {
            if (body.command !== 'submitted') return undefined;
            const gate = new Promise(resolve => {
                release = () => resolve({
                    ok: true,
                    json: () => Promise.resolve(serverStateResponse({
                        revision: 1,
                        state: {
                            surveyId: h.config.surveyId,
                            status: 'submitted',
                            updatedAt: 200,
                            snoozedUntil: 0
                        },
                        seen: seenObject()
                    }))
                });
            });
            return gate;
        }
    });
    return waitForFlush().then(() => {
        const posts = h.serverPosts.filter(p => p.body.command === 'submitted');
        eq('replay 命令已发出', posts.length, 1);
        // 未释放确认前推进自动评估：command 超时（4000ms）先于自动评估（10000ms）
        // 结束 reconciliation，loadServerContext 有限结束；SDK 不得初始化。
        h.timers.advance(10000);
        return waitForFlush();
    }).then(() => {
        eq('replay 未确认前 SDK 不加载（loadServerContext 等待 replay）',
            h.adapter.sdkConfig() === null, true);
        // 超时后迟到响应不得应用：不修改 revision / state / localStorage。
        release();
        return waitForFlush();
    }).then(() => {
        eq('replay 成功只发送一次', h.serverPosts.filter(p => p.body.command === 'submitted').length, 1);
        const localState = JSON.parse(h.storage.getItem(STATE_KEY));
        eq('确认后 localStorage 保留 local fallback', localState.status, 'submitted');
        eq('超时后的迟到响应不修改时间戳（保留本地 updatedAt）', localState.updatedAt, 100);
        eq('迟到响应不推进 revision', h.api._internals.currentServerRevision(), 0);
        eq('SDK 不加载', h.adapter.sdkConfig() === null, true);
        eq('Survey 不请求', h.adapter.calls.getSurveys.length, 0);
        eq('弹窗不显示', h.document.querySelectorAll('.plf-backdrop').length, 0);
    });
}

function testReconcileSubmittedReplayNetworkFailure() {
    // B. local submitted + server null + replay 网络失败：
    // local submitted 保留；pending fallback 保留（effectiveState 为 submitted）；
    // SDK 不加载、Survey 不请求、弹窗不显示。
    const h = initHarness({
        batchLayout: 'landscape',
        storage: {
            [STATE_KEY]: localStateValue('submitted'),
            [SEEN_KEY]: JSON.stringify(seenObject())
        },
        serverState: serverStateResponse({seen: {}}),
        serverPostResponse: 'fail'
    });
    return waitForFlush().then(() => {
        h.timers.advance(11000);
        return waitForFlush();
    }).then(() => {
        eq('local submitted 保留', JSON.parse(h.storage.getItem(STATE_KEY)).status, 'submitted');
        eq('effectiveState 仍为 submitted（pending fallback 保留）',
            h.api._internals.effectiveState().status, 'submitted');
        eq('SDK 不加载', h.adapter.sdkConfig() === null, true);
        eq('Survey 不请求', h.adapter.calls.getSurveys.length, 0);
        eq('弹窗不显示', h.document.querySelectorAll('.plf-backdrop').length, 0);
        h.timers.advance(5000);
        return waitForFlush();
    }).then(() => {
        eq('失败后无残留定时器', h.timers.pending().length, 0);
    });
}

function testReconcileNeverReplayTimeout() {
    // C. local never + server null + replay 超时：
    // local never 保留；自动流程不展示；reconciliation 在有限时间内结束
    // （decision 超时后 seen 回放仍继续推进，证明前一阶段已 settle）。
    const h = initHarness({
        batchLayout: 'landscape',
        storage: {
            [STATE_KEY]: localStateValue('never'),
            [SEEN_KEY]: JSON.stringify(seenObject())
        },
        serverState: serverStateResponse({seen: {}}),
        serverPostResponse: () => new Promise(() => {})
    });
    return waitForFlush().then(() => {
        const posts = h.serverPosts.map(p => p.body.command);
        ok('never 命令已发出', posts.indexOf('never') >= 0);
        h.timers.advance(12000);
        return waitForFlush();
    }).then(() => {
        const posts = h.serverPosts.map(p => p.body.command);
        ok('decision 超时后 seen 回放继续（reconciliation 未永久 pending）',
            posts.indexOf('record_seen') > posts.indexOf('never'));
        eq('local never 保留', JSON.parse(h.storage.getItem(STATE_KEY)).status, 'never');
        eq('effectiveState 为 never', h.api._internals.effectiveState().status, 'never');
        eq('自动流程不展示', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('SDK 不加载', h.adapter.sdkConfig() === null, true);
        // 失败链路逐段收敛（decision/seen timeout → recordSeen 补记 → flush 失败终止），
        // 循环推进直到没有残留定时器（带上限保护）。
        return (function drain(rounds) {
            if (h.timers.pending().length === 0) return Promise.resolve();
            if (rounds > 50) throw new Error('drain runaway');
            h.timers.advance(10000);
            return waitForFlush().then(() => drain(rounds + 1));
        })(0);
    }).then(() => {
        eq('超时结束后无残留定时器', h.timers.pending().length, 0);
    });
}

function testReconcileSnoozedReplayFailure() {
    // D. local 有效 snoozed + server null + replay 失败：
    // local snoozedUntil 保留；未到期不展示。
    const snoozedUntil = 2000000;
    const h = initHarness({
        batchLayout: 'landscape',
        storage: {
            [STATE_KEY]: localStateValue('snoozed', snoozedUntil),
            [SEEN_KEY]: JSON.stringify(seenObject())
        },
        serverState: serverStateResponse({seen: {}}),
        serverPostResponse: 'fail'
    });
    return waitForFlush().then(() => {
        h.timers.advance(11000);
        return waitForFlush();
    }).then(() => {
        const localState = JSON.parse(h.storage.getItem(STATE_KEY));
        eq('local snoozed 保留', localState.status, 'snoozed');
        eq('snoozedUntil 保留', localState.snoozedUntil, snoozedUntil);
        eq('未到期不展示', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('SDK 不加载', h.adapter.sdkConfig() === null, true);
    });
}

function testReconcileSeenReplayFailure() {
    // E. local seen 两个布局 + server seen 空 + record_seen 失败：
    // local seen 不被清空；effectiveSeen 仍有两个布局；达到布局阈值。
    const localSeen = {};
    localSeen['pixiv-batch-landscape'] = {firstSeenAt: 1, lastSeenAt: 100};
    localSeen['pixiv-batch-portrait'] = {firstSeenAt: 2, lastSeenAt: 200};
    const h = initHarness({
        batchLayout: 'landscape',
        storage: {[SEEN_KEY]: JSON.stringify(localSeen)},
        serverState: serverStateResponse({seen: {}}),
        serverPostResponse: 'fail'
    });
    return waitForServerContext(h).then(() => {
        const stored = JSON.parse(h.storage.getItem(SEEN_KEY));
        ok('local seen 不被清空', stored['pixiv-batch-landscape'] && stored['pixiv-batch-portrait']);
        const effective = h.api._internals.effectiveSeen();
        eq('effectiveSeen 仍有两个布局',
            h.api._internals.distinctSeenCount(effective), 2);
        ok('达到布局阈值', h.api._internals.distinctSeenCount(effective)
            >= h.api._internals.MIN_DISTINCT_LAYOUTS_SEEN);
    });
}

function testReconcileSuccessSyncsAuthoritativeSnapshot() {
    // F. replay 成功：pending fallback 清理；权威 server snapshot 写回 localStorage。
    const h = initHarness({
        batchLayout: 'landscape',
        storage: {
            [STATE_KEY]: localStateValue('submitted'),
            [SEEN_KEY]: JSON.stringify(seenObject())
        },
        serverState: serverStateResponse({seen: seenObject()})
    });
    return waitForFlush().then(() => {
        const posts = h.serverPosts.filter(p => p.body.command === 'submitted');
        eq('replay 成功', posts.length, 1);
        const localState = JSON.parse(h.storage.getItem(STATE_KEY));
        eq('权威快照写回 localStorage', localState.status, 'submitted');
        ok('localStorage 与服务器快照一致（updatedAt 来自服务器）',
            typeof localState.updatedAt === 'number');
        const effective = h.api._internals.effectiveState();
        eq('effectiveState 为服务器确认的 submitted', effective.status, 'submitted');
        h.timers.advance(11000);
        return waitForFlush();
    }).then(() => {
        eq('弹窗不显示', h.document.querySelectorAll('.plf-backdrop').length, 0);
    });
}

function testServerSubmittedWinsOverLocalSnoozed() {
    // G. server 已 submitted + local snoozed：server submitted 胜出；
    // local snoozed 被覆盖；不发降级命令。
    const h = initHarness({
        batchLayout: 'landscape',
        storage: {
            [STATE_KEY]: localStateValue('snoozed', 2000000),
            [SEEN_KEY]: JSON.stringify(seenObject())
        },
        serverState: serverStateResponse({
            state: {
                surveyId: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
                status: 'submitted',
                updatedAt: 500,
                snoozedUntil: 0
            },
            seen: seenObject()
        })
    });
    return waitForServerContext(h).then(() => {
        const downgrades = h.serverPosts.filter(p => p.body.command === 'snooze'
            || p.body.command === 'never' || p.body.command === 'submitted');
        eq('本地 snoozed 不回放降级命令', downgrades.length, 0);
        const localState = JSON.parse(h.storage.getItem(STATE_KEY));
        eq('localStorage 覆盖为服务端 submitted', localState.status, 'submitted');
        eq('localStorage 使用服务端时间戳', localState.updatedAt, 500);
        eq('effectiveState 为 submitted', h.api._internals.effectiveState().status, 'submitted');
    });
}

function testReconcileDecisionThenSeenOrdering() {
    // H. decision 与 seen 顺序：decision 命令先发出；seen 命令在 decision 确认后
    // 才发出，并使用 decision 返回的新 revision（不主动制造 409）。
    let releaseDecision = null;
    const h = initHarness({
        batchLayout: 'landscape',
        storage: {
            [STATE_KEY]: localStateValue('submitted'),
            [SEEN_KEY]: JSON.stringify(seenObject())
        },
        serverState: serverStateResponse({seen: {}}),
        serverPostResponse: ({body}) => {
            if (body.command !== 'submitted') return undefined;
            const gate = new Promise(resolve => {
                releaseDecision = () => resolve({
                    ok: true,
                    json: () => Promise.resolve(serverStateResponse({
                        revision: 1,
                        state: {
                            surveyId: h.config.surveyId,
                            status: 'submitted',
                            updatedAt: 200,
                            snoozedUntil: 0
                        },
                        seen: {}
                    }))
                });
            });
            return gate;
        }
    });
    return waitForFlush().then(() => {
        eq('decision 先于 seen（seen 未发出）', h.serverPosts.map(p => p.body.command).join(','), 'submitted');
        releaseDecision();
        return waitForFlush();
    }).then(() => {
        const seenPosts = h.serverPosts.filter(p => p.body.command === 'record_seen');
        ok('decision 确认后 seen 才发出', seenPosts.length >= 1);
        eq('seen 命令使用 decision 返回的新 revision', seenPosts[0].body.expectedRevision, 1);
    });
}

function testCommandTimeoutDestroyClearsTimers() {
    // I. command 超时：destroy 后无残留定时器，Promise 安全结束。
    const h = initHarness({
        batchLayout: 'landscape',
        storage: {
            [STATE_KEY]: localStateValue('never'),
            [SEEN_KEY]: JSON.stringify(seenObject())
        },
        serverState: serverStateResponse({seen: {}}),
        serverPostResponse: () => new Promise(() => {})
    });
    return waitForFlush().then(() => {
        ok('命令已发出且未解决', h.serverPosts.length >= 1);
        h.api.destroy();
        return waitForFlush();
    }).then(() => {
        eq('destroy 后无残留定时器', h.timers.pending().length, 0);
    });
}

/* ============================================================
   前端 no-store 与严格快照校验
============================================================ */

function testStateGetsUseNoStoreCache() {
    const h = initHarness({
        batchLayout: 'landscape',
        serverState: serverStateResponse({seen: seenObject()})
    });
    return waitForServerContext(h).then(() => {
        h.dispatchStorage(STATE_KEY, localStateValue('submitted'));
        return waitForFlush();
    }).then(() => {
        const gets = h.fetchCalls.filter(c => c.url.indexOf('/api/layout-feedback/state') >= 0
            && !(c.init && c.init.method === 'POST'));
        ok('存在状态 GET 请求', gets.length >= 1);
        gets.forEach(c => {
            eq('状态 GET 的 fetch init.cache 为 no-store', c.init.cache, 'no-store');
            eq('状态 GET 携带 Accept: application/json', c.init.headers.Accept, 'application/json');
        });
    });
}

function testApplyServerSnapshotRejectsOtherSurveyState() {
    // 服务端返回别的 Survey state：整份快照拒绝；不初始化错误 identity；
    // 不修改现有 revision / state / seen（回退 local 模式，open 走浏览器匿名身份）。
    const h = initHarness({
        batchLayout: 'landscape',
        serverState: serverStateResponse({
            state: {
                surveyId: 'aaaaaaaa-bbbb-cccc-dddd-ffffffffffff',
                status: 'submitted',
                updatedAt: 1,
                snoozedUntil: 0
            },
            seen: seenObject()
        })
    });
    return h.api.open().then(() => waitForFlush()).then(() => {
        ok('SDK 已按 local 回退初始化', h.adapter.sdkConfig() !== null);
        eq('拒绝快照：不使用被拒快照的 scoped identity', h.adapter.sdkConfig().bootstrap === undefined, true);
        eq('get_distinct_id 不是被拒快照的 scoped ID', h.adapter.get_distinct_id() !== SERVER_SCOPED_ID, true);
    });
}

function testApplyServerSnapshotRejectsInvalidShapes() {
    const withServerState = (overrides) => {
        const h = initHarness({
            batchLayout: 'landscape',
            serverState: serverStateResponse(overrides)
        });
        return h.api.open().then(() => waitForFlush()).then(() => h);
    };
    return withServerState({revision: 1.5, seen: seenObject()}).then(h => {
        eq('非整数 revision 快照整体拒绝', h.adapter.sdkConfig().bootstrap === undefined, true);
    }).then(() => withServerState({revision: -1, seen: seenObject()})).then(h => {
        eq('负数 revision 快照整体拒绝', h.adapter.sdkConfig().bootstrap === undefined, true);
    }).then(() => withServerState({
        state: {surveyId: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee', status: 'snoozed',
            updatedAt: 1.5, snoozedUntil: 100},
        seen: seenObject()
    })).then(h => {
        eq('非整数 updatedAt 快照整体拒绝', h.adapter.sdkConfig().bootstrap === undefined, true);
    }).then(() => withServerState({
        seen: {
            'pixiv-batch-landscape': {firstSeenAt: 10, lastSeenAt: 1},
            'pixiv-batch-portrait': {firstSeenAt: 1, lastSeenAt: 2}
        }
    })).then(h => {
        eq('lastSeenAt < firstSeenAt 快照整体拒绝', h.adapter.sdkConfig().bootstrap === undefined, true);
    }).then(() => withServerState({
        distinctId: '',
        seen: seenObject()
    })).then(h => {
        eq('available=true 但 distinctId 缺失快照整体拒绝', h.adapter.sdkConfig().bootstrap === undefined, true);
    }).then(() => withServerState({
        revision: Number.NaN,
        seen: seenObject()
    })).then(h => {
        eq('NaN revision 快照整体拒绝', h.adapter.sdkConfig().bootstrap === undefined, true);
    });
}

/* ============================================================
   乱序 / 迟到响应 / 跨标签 fallback / snooze 强度 / operation Set
   / GET-reconciliation timeout 分离 / storage 去重（A-J）
============================================================ */

function surveyState(status, snoozedUntil, updatedAt) {
    return {
        surveyId: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
        status: status,
        updatedAt: updatedAt === undefined ? 999 : updatedAt,
        snoozedUntil: snoozedUntil === undefined ? 0 : snoozedUntil
    };
}

function testSnapshotRevisionMonotonic() {
    // A. 先应用 revision=2 submitted；再送达 revision=1 state=null（低 revision 迟到响应）。
    // 不设置 batchLayout：避免 init 的 record_seen 触发 400ms flush 推进 revision。
    const h = initHarness({
        serverState: serverStateResponse({
            revision: 2,
            state: surveyState('submitted', 0, 1),
            seen: {}
        })
    });
    return waitForServerContext(h).then(() => {
        eq('初始快照已应用（revision=2）', h.api._internals.currentServerRevision(), 2);
        // 另一标签页写入 submitted fallback → 当前标签页合并进 pending
        h.dispatchStorage(STATE_KEY, JSON.stringify(surveyState('submitted')));
        return waitForFlush();
    }).then(() => {
        eq('storage 事件合并后 pending 生效', h.api._internals.effectiveState().status, 'submitted');
        // 服务器 refresh 返回低 revision 空状态：STALE，不得覆盖
        h.setServerState(serverStateResponse({revision: 1, state: null, seen: {}}));
        h.dispatchStorage(STATE_KEY, JSON.stringify(surveyState('submitted')));
        return waitForFlush();
    }).then(() => {
        eq('低 revision 不覆盖：serverRevision 仍为 2', h.api._internals.currentServerRevision(), 2);
        eq('effectiveState 仍 submitted', h.api._internals.effectiveState().status, 'submitted');
        eq('STATE_KEY 仍 submitted', JSON.parse(h.storage.getItem(STATE_KEY)).status, 'submitted');
        eq('pending 不被 STALE 响应清理', h.api._internals.effectiveState().status, 'submitted');
    });
}

function testSnapshotSameRevisionContent() {
    // B. 同 revision 不同内容 → INVALID 拒绝；同 revision 完全相同 → SAME 无副作用。
    const h = initHarness({
        serverState: serverStateResponse({
            revision: 2,
            state: surveyState('submitted', 0, 1),
            seen: {}
        })
    });
    let warnsBefore = 0;
    return waitForServerContext(h).then(() => {
        h.setServerState(serverStateResponse({
            revision: 2,
            state: null,
            seen: {}
        }));
        warnsBefore = h.consoleWarn.length;
        h.dispatchStorage(STATE_KEY, JSON.stringify(surveyState('submitted')));
        return waitForFlush();
    }).then(() => {
        eq('同 revision 不同内容被拒绝：serverRevision 仍为 2', h.api._internals.currentServerRevision(), 2);
        eq('不覆盖：effectiveState 仍 submitted', h.api._internals.effectiveState().status, 'submitted');
        eq('不覆盖：STATE_KEY 仍 submitted', JSON.parse(h.storage.getItem(STATE_KEY)).status, 'submitted');
        const newWarns = h.consoleWarn.slice(warnsBefore);
        ok('记录安全 warning', JSON.stringify(newWarns).indexOf('conflicting content for the same revision') >= 0);
        const warnText = JSON.stringify(newWarns);
        ok('warning 不含 token', warnText.indexOf('phc_test_project_token') < 0);
        ok('warning 不含 Survey ID', warnText.indexOf(h.config.surveyId) < 0);
        ok('warning 不含 scoped ID', warnText.indexOf('plf_') < 0);
    }).then(() => {
        // 同 revision 完全相同 → SAME：无副作用、无新 warning
        const h2 = initHarness({
            serverState: serverStateResponse({
                revision: 2,
                state: surveyState('submitted', 0, 1),
                seen: {}
            })
        });
        return waitForServerContext(h2).then(() => {
            const warnsBefore = h2.consoleWarn.length;
            h2.setServerState(serverStateResponse({
                revision: 2,
                state: surveyState('submitted', 0, 1),
                seen: {}
            }));
            h2.dispatchStorage(STATE_KEY, JSON.stringify(surveyState('submitted', 0, 1)));
            return waitForFlush();
        }).then(() => {
            eq('SAME：revision 不变', h2.api._internals.currentServerRevision(), 2);
            eq('SAME：无新 warning', h2.consoleWarn.length, warnsBefore);
            eq('SAME：effectiveState 不变', h2.api._internals.effectiveState().status, 'submitted');
        });
    });
}

function testLateResponseAfterCommandTimeout() {
    // C. server command 超时完成后再触发其 HTTP 响应：无副作用。
    let release = null;
    let warnsBefore = 0;
    let warnsAfterTimeout = 0;
    const h = initHarness({
        serverState: serverStateResponse({seen: seenObject()}),
        serverPostResponse: () => new Promise(resolve => {
            release = () => resolve({
                ok: true,
                json: () => Promise.resolve(serverStateResponse({
                    revision: 9,
                    state: surveyState('submitted', 0, 999),
                    seen: seenObject()
                }))
            });
        })
    });
    return waitForServerContext(h).then(() => h.api.open()).then(() => waitForFlush()).then(() => {
        h.timers.advance(11000);
        return waitForFlush();
    }).then(() => {
        h.actionButton('never').click();
        return waitForFlush();
    }).then(() => {
        warnsBefore = h.consoleWarn.length;
        h.timers.advance(4000);
        return waitForFlush();
    }).then(() => {
        ok('超时产生失败 warning', h.consoleWarn.length > warnsBefore);
        warnsAfterTimeout = h.consoleWarn.length;
        release();
        return waitForFlush();
    }).then(() => {
        eq('迟到响应不推进 revision', h.api._internals.currentServerRevision(), 0);
        eq('迟到响应不修改 state', h.api._internals.effectiveState().status, 'never');
        eq('迟到响应不修改 localStorage', JSON.parse(h.storage.getItem(STATE_KEY)).status, 'never');
        eq('迟到响应不写 seen', JSON.parse(h.storage.getItem(SEEN_KEY)) !== null, true);
        ok('迟到响应无新 warning', h.consoleWarn.length === warnsAfterTimeout);
        eq('operation 集合为空', h.api._internals.serverCommandOperations.size, 0);
    });
}

function testLateResponseAfterRefreshTimeout() {
    // C2. refresh GET 超时后迟到响应：不 apply / 不 sync / 不修改 serverRevision。
    const h = initHarness({
        serverFetch: 'pending',
        serverState: serverStateResponse({revision: 2, state: null, seen: {}})
    });
    return waitForFlush().then(() => {
        h.serverFetchGate.resolve({ok: true, json: () => Promise.resolve(serverStateResponse({
            revision: 2,
            state: surveyState('submitted', 0, 1),
            seen: {}
        }))});
        return waitForFlush();
    }).then(() => {
        eq('初始快照已应用', JSON.parse(h.storage.getItem(STATE_KEY)).status, 'submitted');
        // storage 事件触发 refresh：第二次 GET 进入 pending
        h.dispatchStorage(STATE_KEY, JSON.stringify(surveyState('submitted')));
        return waitForFlush();
    }).then(() => {
        h.timers.advance(3000);
        return waitForFlush();
    }).then(() => {
        // 迟到响应携带高 revision 的 never
        h.serverFetchGate.resolve({ok: true, json: () => Promise.resolve(serverStateResponse({
            revision: 9,
            state: surveyState('never'),
            seen: {}
        }))});
        return waitForFlush();
    }).then(() => {
        eq('迟到 refresh 不修改 revision', h.api._internals.currentServerRevision(), 2);
        eq('迟到 refresh 不修改 state', h.api._internals.effectiveState().status, 'submitted');
        eq('迟到 refresh 不同步 localStorage', JSON.parse(h.storage.getItem(STATE_KEY)).status, 'submitted');
        h.timers.advance(11000);
        return waitForFlush();
    }).then(() => {
        eq('无残留定时器', h.timers.pending().length, 0);
    });
}

function testOutOfOrderCommandResponses() {
    // D. record_seen 的 revision 1 响应延迟；submitted 409 → retry rev2 成功；
    // 最后 record_seen 的 revision 1 响应到达：旧 revision 无副作用。
    let releaseRecordSeen = null;
    let submittedCount = 0;
    const h = initHarness({
        batchLayout: 'landscape',
        serverState: serverStateResponse({seen: {}}),
        serverPostResponse: ({body}) => {
            if (body.command === 'record_seen') {
                return new Promise(resolve => {
                    releaseRecordSeen = () => resolve({
                        ok: true,
                        json: () => Promise.resolve(serverStateResponse({
                            revision: 1,
                            state: null,
                            seen: {}
                        }))
                    });
                });
            }
            if (body.command === 'submitted') {
                submittedCount++;
                if (submittedCount === 1) {
                    return {
                        status: 409,
                        json: () => Promise.resolve(serverStateResponse({revision: 1, state: null, seen: {}}))
                    };
                }
                return {
                    ok: true,
                    json: () => Promise.resolve(serverStateResponse({
                        revision: 2,
                        state: surveyState('submitted', 0, 999),
                        seen: {'pixiv-batch-portrait': {firstSeenAt: 1, lastSeenAt: 1}}
                    }))
                };
            }
            return undefined;
        }
    });
    return waitForServerContext(h).then(() => {
        // reconcileSeen 因本地 landscape fallback 发出第一个 record_seen（gated）；
        // 释放它（rev1 空状态）后 loadServerContext 才完成。
        ok('reconcileSeen record_seen 已发出',
            h.serverPosts.filter(p => p.body.command === 'record_seen').length >= 1);
        releaseRecordSeen();
        return waitForFlush();
    }).then(() => h.api.open()).then(() => waitForFlush()).then(() => {
        h.dispatchLayoutChanged('portrait', 'landscape');
        return waitForFlush();
    }).then(() => {
        selectChoice(h, 'pixiv-batch-portrait');
        h.submitButton().click();
        return waitForFlush();
    }).then(() => {
        h.timers.advance(400);
        return waitForFlush();
    }).then(() => {
        eq('submitted 重试后 revision 为 2', h.api._internals.currentServerRevision(), 2);
        eq('本地为 submitted', JSON.parse(h.storage.getItem(STATE_KEY)).status, 'submitted');
        ok('flush 的 record_seen 已发出且 gated',
            h.serverPosts.filter(p => p.body.command === 'record_seen').length >= 2);
        releaseRecordSeen();
        return waitForFlush();
    }).then(() => {
        eq('迟到 revision 1 不覆盖：revision 仍为 2', h.api._internals.currentServerRevision(), 2);
        eq('状态仍 submitted', h.api._internals.effectiveState().status, 'submitted');
        eq('本地缓存不被旧快照覆盖', JSON.parse(h.storage.getItem(STATE_KEY)).status, 'submitted');
        const seen = JSON.parse(h.storage.getItem(SEEN_KEY));
        ok('localStorage seen 仍保留 portrait', seen && seen['pixiv-batch-portrait']);
        eq('operation 集合为空', h.api._internals.serverCommandOperations.size, 0);
    });
}

function crossTabFallbackMatrix(stateStatus, snoozedUntil, label) {
    const h = initHarness({
        batchLayout: 'landscape',
        serverState: serverStateResponse({seen: {}})
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
        eq(label + '：弹窗关闭', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq(label + '：不发送 dismissed', captureEvents(h).indexOf('survey dismissed'), -1);
        eq(label + '：localStorage 保留 fallback', JSON.parse(h.storage.getItem(STATE_KEY)).status, stateStatus);
        eq(label + '：pendingLocalState 已合并', h.api._internals.effectiveState().status, stateStatus);
        eq(label + '：显示已在其他标签页处理', h.toastCalls.length, 1);
        // 服务器 refresh 返回旧空状态（SAME / 无变化）：fallback 保留，调查不重新展示。
        h.timers.advance(11000);
        return waitForFlush();
    }).then(() => {
        eq(label + '：自动流程不重新展示', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq(label + '：自动流程不补发 shown', captureEvents(h).filter(e => e === 'survey shown').length, shownBefore);
    });
}

function testCrossTabStateFallback() {
    // E. 标签页 A 写 submitted / never / 有效 snoozed；标签页 B 收到 STATE_KEY 后
    // 合并 pending fallback；B 的服务器 GET 返回空状态时 fallback 仍保留。
    return Promise.resolve()
        .then(() => crossTabFallbackMatrix('submitted', 0, 'submitted'))
        .then(() => crossTabFallbackMatrix('never', 0, 'never'))
        .then(() => crossTabFallbackMatrix('snoozed', 2000000, 'snoozed'));
}

function testCrossTabSeenFallback() {
    // F. 标签页 A 写两个布局 seen；标签页 B 收到 SEEN_KEY 后合并 pendingLocalSeen；
    // B 的服务器 GET 返回空 seen 时 localStorage 不清空、seenCount 不下降。
    const localSeen = {};
    localSeen['pixiv-batch-landscape'] = {firstSeenAt: 1, lastSeenAt: 100};
    localSeen['pixiv-batch-portrait'] = {firstSeenAt: 2, lastSeenAt: 200};
    const h = initHarness({
        batchLayout: 'landscape',
        serverState: serverStateResponse({seen: {}}),
        serverPostResponse: 'fail'
    });
    return waitForServerContext(h).then(() => {
        h.storage.values.set(SEEN_KEY, JSON.stringify(localSeen));
        h.dispatchStorage(SEEN_KEY, JSON.stringify(localSeen));
        return waitForFlush();
    }).then(() => {
        // init 的 record_seen 去抖 flush 失败 settle；服务器旧 seen 不清理 pending。
        return (function drain(rounds) {
            if (h.timers.pending().length === 0) return Promise.resolve();
            if (rounds > 50) throw new Error('drain runaway');
            h.timers.advance(10000);
            return waitForFlush().then(() => drain(rounds + 1));
        })(0);
    }).then(() => {
        const effective = h.api._internals.effectiveSeen();
        eq('pendingLocalSeen 合并两个布局', h.api._internals.distinctSeenCount(effective), 2);
        const stored = JSON.parse(h.storage.getItem(SEEN_KEY));
        eq('localStorage 不被清空（保留两个布局）', h.api._internals.distinctSeenCount(stored), 2);
        ok('seenCount 达到 2', h.api._internals.distinctSeenCount(effective) >= 2);
    });
}

function snoozeStorageValue(snoozedUntil) {
    return JSON.stringify(surveyState('snoozed', snoozedUntil, 100));
}

function testSnoozeStrength() {
    const DAY = 24 * 60 * 60 * 1000;
    return Promise.resolve().then(() => {
        // server snooze 1 天，local snooze 7 天：local 更强，必须回放，pending 不提前清除。
        const serverUntil = 1000000 + DAY;
        const localUntil = 1000000 + 7 * DAY;
        const h = initHarness({
            batchLayout: 'landscape',
            storage: {[STATE_KEY]: snoozeStorageValue(localUntil)},
            serverState: serverStateResponse({
                state: surveyState('snoozed', serverUntil, 1),
                seen: {}
            })
        });
        return waitForServerContext(h).then(() => {
            const posts = h.serverPosts.filter(p => p.body.command === 'snooze');
            eq('本地更强：回放 snooze 命令', posts.length, 1);
            // 服务端已按自己的时钟保存 snooze（7 天）：命令确认后采用服务端权威时间，
            // 与服务端时钟一致时数值与本地 7 天相同。
            eq('命令确认后采用服务端权威时间（与服务端时钟一致的 7 天）',
                h.api._internals.effectiveState().snoozedUntil, localUntil);
            eq('localStorage 采用服务端权威时间', JSON.parse(h.storage.getItem(STATE_KEY)).snoozedUntil, localUntil);
        });
    }).then(() => {
        // server snooze 7 天，local snooze 1 天：server 更强，不回放，pending 可清理。
        const serverUntil = 1000000 + 7 * DAY;
        const localUntil = 1000000 + DAY;
        const h = initHarness({
            batchLayout: 'landscape',
            storage: {[STATE_KEY]: snoozeStorageValue(localUntil)},
            serverState: serverStateResponse({
                state: surveyState('snoozed', serverUntil, 1),
                seen: {}
            })
        });
        return waitForServerContext(h).then(() => {
            const posts = h.serverPosts.filter(p => p.body.command === 'snooze'
                || p.body.command === 'never' || p.body.command === 'submitted');
            eq('服务端更强：不回放', posts.length, 0);
            eq('effectiveState 为服务端 7 天', h.api._internals.effectiveState().snoozedUntil, serverUntil);
            eq('localStorage 覆盖为服务端 7 天', JSON.parse(h.storage.getItem(STATE_KEY)).snoozedUntil, serverUntil);
        });
    }).then(() => {
        // 409 返回服务端已保存的更短 snooze：命令按状态确认（不得比较跨时钟
        // 绝对 snoozedUntil），采用服务端权威时间，不重试。
        // 不设置 batchLayout：避免 init 的 record_seen 去抖 flush 以空 state 覆盖
        // 已确认的 snooze（真实服务端 record_seen 不触碰 state）。
        const localUntil = 1000000 + 7 * DAY;
        const serverUntil = 1000000 + DAY;
        let count = 0;
        const h = initHarness({
            storage: {[STATE_KEY]: snoozeStorageValue(localUntil)},
            serverState: serverStateResponse({seen: {}}),
            serverPostResponse: ({body}) => {
                if (body.command !== 'snooze') return undefined;
                count++;
                return {
                    status: 409,
                    json: () => Promise.resolve(serverStateResponse({
                        revision: 1,
                        state: surveyState('snoozed', serverUntil, 1),
                        seen: {}
                    }))
                };
            }
        });
        return waitForServerContext(h).then(() => {
            h.timers.advance(4000);
            return waitForFlush();
        }).then(() => {
            eq('409 只尝试一次（服务端已 snoozed 即确认，不重试）', count, 1);
            eq('localStorage 采用服务端权威时间（更短 snooze 由服务端权威覆盖）',
                JSON.parse(h.storage.getItem(STATE_KEY)).snoozedUntil, serverUntil);
            eq('effectiveState 为服务端权威时间',
                h.api._internals.effectiveState().snoozedUntil, serverUntil);
            h.timers.advance(11000);
            return waitForFlush();
        }).then(() => {
            eq('无残留定时器', h.timers.pending().length, 0);
        });
    });
}

function testConcurrentCommandOperations() {
    // H. 三个并发 operation：A(snooze) → C(never) → B(record_seen) 完成顺序。
    // 完成 A 不删除 B/C；完成 C 不删除 B；B 最终正常完成；Set 最终为空。
    // 服务端 seen 缺 portrait：切换布局后 portrait 触发 record_seen 去抖 flush。
    const gates = [];
    const gateSeen = {
        'pixiv-batch-landscape': {firstSeenAt: 1, lastSeenAt: 1},
        'pixiv-batch-portrait': {firstSeenAt: 1, lastSeenAt: 1}
    };
    const h = initHarness({
        serverState: serverStateResponse({
            seen: {'pixiv-batch-landscape': {firstSeenAt: 1, lastSeenAt: 1}}
        }),
        serverPostResponse: ({body}) => {
            return new Promise(resolve => gates.push(() => resolve({
                ok: true,
                json: () => Promise.resolve(serverStateResponse({
                    revision: body.command === 'never' ? 2 : 1,
                    state: body.command === 'submitted'
                        ? surveyState('submitted', 0, 999)
                        : body.command === 'never'
                            ? surveyState('never')
                            : null,
                    seen: gateSeen
                }))
            })));
        }
    });
    return waitForServerContext(h).then(() => h.api.open()).then(() => waitForFlush()).then(() => {
        h.actionButton('snooze').click();
        return waitForFlush();
    }).then(() => {
        h.dispatchLayoutChanged('portrait', 'landscape');
        return waitForFlush();
    }).then(() => h.api.open().then(() => waitForFlush())).then(() => {
        h.actionButton('never').click();
        return waitForFlush();
    }).then(() => {
        h.timers.advance(400);
        return waitForFlush();
    }).then(() => {
        eq('三个命令并发在途', h.api._internals.serverCommandOperations.size, 3);
        gates[0]();
        return waitForFlush();
    }).then(() => {
        eq('A 完成不删除 B/C', h.api._internals.serverCommandOperations.size, 2);
        gates[1]();
        return waitForFlush();
    }).then(() => {
        eq('C 完成不删除 B', h.api._internals.serverCommandOperations.size, 1);
        gates[2]();
        return waitForFlush();
    }).then(() => {
        eq('B 最终正常完成', h.api._internals.serverCommandOperations.size, 0);
        eq('Set 最终为空', h.api._internals.serverCommandOperations.size, 0);
    });
}

function testDestroyCancelsInFlightCommands() {
    // H2. A 完成、B/C 在途、destroy：B/C 被 abort、Promise 结束、timeout 清除、
    // 无残留 operation、迟到响应无副作用。
    // 服务端 seen 缺 portrait：切换布局后 portrait 触发 record_seen 去抖 flush。
    const gates = [];
    let storedBefore = null;
    let seenBefore = null;
    const h = initHarness({
        serverState: serverStateResponse({
            seen: {'pixiv-batch-landscape': {firstSeenAt: 1, lastSeenAt: 1}}
        }),
        serverPostResponse: () => new Promise(resolve => gates.push(() => resolve({
            ok: true,
            json: () => Promise.resolve(serverStateResponse({revision: 5, state: null, seen: seenObject()}))
        })))
    });
    return waitForServerContext(h).then(() => h.api.open()).then(() => waitForFlush()).then(() => {
        h.actionButton('snooze').click();
        return waitForFlush();
    }).then(() => {
        h.dispatchLayoutChanged('portrait', 'landscape');
        return waitForFlush();
    }).then(() => h.api.open().then(() => waitForFlush())).then(() => {
        h.actionButton('never').click();
        return waitForFlush();
    }).then(() => {
        h.timers.advance(400);
        return waitForFlush();
    }).then(() => {
        eq('三个命令在途', h.api._internals.serverCommandOperations.size, 3);
        gates[0]();
        return waitForFlush();
    }).then(() => {
        eq('A 完成后 B/C 仍在途', h.api._internals.serverCommandOperations.size, 2);
        storedBefore = h.storage.getItem(STATE_KEY);
        seenBefore = h.storage.getItem(SEEN_KEY);
        h.api.destroy();
        return waitForFlush();
    }).then(() => {
        eq('destroy 后无残留 operation', h.api._internals.serverCommandOperations.size, 0);
        eq('destroy 后无残留定时器', h.timers.pending().length, 0);
        // 迟到响应：B/C 的 gate 现在才解析
        gates[1]();
        gates[2]();
        return waitForFlush();
    }).then(() => {
        eq('迟到响应不修改 localStorage', h.storage.getItem(STATE_KEY), storedBefore);
        eq('迟到响应不修改 seen 缓存', h.storage.getItem(SEEN_KEY), seenBefore);
        eq('destroy 后无弹窗', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('无残留定时器', h.timers.pending().length, 0);
    });
}

function testGetReconciliationTimeoutSeparation() {
    // I. GET 快速成功；reconciliation POST 在 3500ms 成功：
    // GET timeout(3000) 在进入 reconciliation 前已清除，3 秒时 loadServerContext 未结束，
    // 3.5 秒 POST 成功后才结束；SDK 在此之前不初始化；无并发 recordSeen。
    let release = null;
    let settled = false;
    const h = initHarness({
        storage: {
            [STATE_KEY]: localStateValue('submitted'),
            [SEEN_KEY]: JSON.stringify(seenObject())
        },
        serverState: serverStateResponse({seen: {}}),
        serverPostResponse: ({body}) => {
            if (body.command !== 'submitted') return undefined;
            return new Promise(resolve => {
                release = () => resolve({
                    ok: true,
                    json: () => Promise.resolve(serverStateResponse({
                        revision: 1,
                        state: surveyState('submitted', 0, 200),
                        // seen 与本地 fallback 相同：reconcileSeen 无需再发 record_seen，
                        // 避免默认 mock 的空 simulatedState 覆盖 submitted。
                        seen: seenObject()
                    }))
                });
            });
        }
    });
    const promise = h.api.open().then(v => { settled = true; return v; });
    return waitForFlush().then(() => {
        eq('决策回放命令已发出', h.serverPosts.filter(p => p.body.command === 'submitted').length, 1);
        h.timers.advance(3000);
        return waitForFlush();
    }).then(() => {
        eq('GET timeout 不覆盖 reconciliation：3 秒时 loadServerContext 未结束', settled, false);
        eq('reconciliation 完成前 SDK 不加载', h.adapter.sdkConfig() === null, true);
        eq('决策 POST 在途时不产生并发 recordSeen',
            h.serverPosts.filter(p => p.body.command === 'record_seen').length, 0);
        h.timers.advance(500);
        release();
        return promise.then(() => waitForFlush());
    }).then(() => {
        eq('3.5 秒 POST 成功后才结束', settled, true);
        eq('确认后 localStorage 为服务端快照', JSON.parse(h.storage.getItem(STATE_KEY)).updatedAt, 200);
        // 手动 open 的弹窗在 reconciliation 完成后正常打开（skipStateGate 不受
        // 服务端 submitted 门禁影响；自动流程门禁由其它测试覆盖）。
        eq('手动 open 流程正常完成', h.document.querySelectorAll('.plf-backdrop').length, 1);
        h.timers.advance(11000);
        return waitForFlush();
    }).then(() => {
        eq('自动流程不再叠加第二个弹窗', h.document.querySelectorAll('.plf-backdrop').length, 1);
    });
}

function testReconciliationCommandTimeoutBounded() {
    // I2. GET 快速成功；reconciliation POST 永不完成：4000ms command timeout 后
    // loadServerContext 有限结束；local fallback 保留；门禁使用 effectiveState；
    // 无永久 pending；无残留 timer。
    let settled = false;
    const h = initHarness({
        // 不设置 batchLayout：避免 init 的 recordSeen 触发 reconcileSeen 的
        // record_seen（也会被 gate），保证只有 decision 命令一个 4000ms timeout。
        storage: {[STATE_KEY]: localStateValue('never')},
        serverState: serverStateResponse({seen: {}}),
        serverPostResponse: () => new Promise(() => {})
    });
    const promise = h.api.open().then(v => { settled = true; return v; });
    return waitForFlush().then(() => {
        h.timers.advance(4000);
        return waitForFlush();
    }).then(() => {
        eq('command 超时后 loadServerContext 有限结束', settled, true);
        eq('local fallback 保留', JSON.parse(h.storage.getItem(STATE_KEY)).status, 'never');
        eq('门禁使用 effectiveState', h.api._internals.effectiveState().status, 'never');
        // 手动 open 的弹窗（skipStateGate）正常打开，自动流程不再叠加。
        eq('手动 open 流程正常完成', h.document.querySelectorAll('.plf-backdrop').length, 1);
        h.timers.advance(11000);
        return waitForFlush();
    }).then(() => {
        eq('自动流程不叠加第二个弹窗', h.document.querySelectorAll('.plf-backdrop').length, 1);
        eq('无残留定时器', h.timers.pending().length, 0);
    });
}

function testStorageWriteDedup() {
    // J. syncEffectiveCacheToLocal 写入与现有值相同：不重复 setItem，
    // 不产生无意义 storage 协调。
    const h = initHarness({
        batchLayout: 'landscape',
        serverState: serverStateResponse({seen: {}})
    });
    let seenSets = 0;
    return waitForServerContext(h).then(() => {
        seenSets = h.storage.setCalls.filter(c => c[0] === SEEN_KEY).length;
        const stateSets = h.storage.setCalls.filter(c => c[0] === STATE_KEY).length;
        ok('初始同步写入过协调缓存', seenSets >= 1);
        eq('无状态时不写 STATE_KEY（走 remove 路径）', stateSets, 0);
        h.dispatchStorage(STATE_KEY, JSON.stringify(surveyState('submitted')));
        return waitForFlush();
    }).then(() => {
        // 相同 SEEN_KEY 值（dispatch 未改变 seen）不重复 setItem
        eq('相同 SEEN_KEY 值不重复 setItem',
            h.storage.setCalls.filter(c => c[0] === SEEN_KEY).length, seenSets);
        eq('STATE_KEY 只写一次（值不同才写）',
            h.storage.setCalls.filter(c => c[0] === STATE_KEY).length, 1);
        // 再触发一次相同值同步：仍然去重
        h.dispatchStorage(STATE_KEY, JSON.stringify(surveyState('submitted')));
        return waitForFlush();
    }).then(() => {
        eq('第二次相同值同步不重复写 STATE_KEY',
            h.storage.setCalls.filter(c => c[0] === STATE_KEY).length, 1);
        eq('不产生多余 removeItem',
            h.storage.removeCalls.filter(k => k === STATE_KEY).length, 0);
    });
}

/* ============================================================
   refresh 结果契约 / 提交前 fail-closed / reconciliation 代际
   隔离 / writeState 单调性（K-T）
============================================================ */

function directRefresh(h) {
    return h.api._internals.refreshServerContext(h.api._internals.currentGeneration());
}

function refreshSecond(second) {
    // 状态 GET 序列：第一次返回合法快照（建立 serverBacked），之后返回 second
    // （响应对象 / Promise / 函数）。
    let calls = 0;
    return () => {
        calls++;
        if (calls === 1) {
            return {ok: true, json: () => Promise.resolve(serverStateResponse({seen: seenObject()}))};
        }
        return typeof second === 'function' ? second(calls) : second;
    };
}

function validFirst(overrides) {
    return () => ({ok: true, json: () => Promise.resolve(serverStateResponse(overrides || {seen: seenObject()}))});
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

function testRefreshResultContract() {
    const DAY = 24 * 60 * 60 * 1000;
    const submittedSnapshot = (revision) => ({
        ok: true,
        json: () => Promise.resolve(serverStateResponse({
            revision, state: surveyState('submitted', 0, 1), seen: seenObject()
        }))
    });
    return Promise.resolve().then(() => {
        // A1. APPLIED → fresh / snapshotResult=applied
        const h = initHarness({
            serverFetch: refreshSecond(() => ({
                ok: true,
                json: () => Promise.resolve(serverStateResponse({
                    revision: 1, state: surveyState('submitted', 0, 1), seen: seenObject()
                }))
            }))
        });
        return waitForServerContext(h).then(() => directRefresh(h)).then(result => {
            eq('APPLIED → status=fresh', result.status, 'fresh');
            eq('APPLIED → snapshotResult=applied', result.snapshotResult, 'applied');
            eq('APPLIED 已提交快照', h.api._internals.currentServerRevision(), 1);
        });
    }).then(() => {
        // A2. SAME → fresh，无副作用
        const h = initHarness({
            serverFetch: refreshWith(
                {revision: 2, state: surveyState('submitted', 0, 1), seen: seenObject()},
                submittedSnapshot(2))
        });
        return waitForServerContext(h).then(() => {
            const warnsBefore = h.consoleWarn.length;
            const stateBefore = h.storage.getItem(STATE_KEY);
            return directRefresh(h).then(result => {
                eq('SAME → status=fresh', result.status, 'fresh');
                eq('SAME → snapshotResult=same', result.snapshotResult, 'same');
                eq('SAME 不推进 revision', h.api._internals.currentServerRevision(), 2);
                eq('SAME 无新 warning', h.consoleWarn.length, warnsBefore);
                eq('SAME 不改写协调缓存', h.storage.getItem(STATE_KEY), stateBefore);
            });
        });
    }).then(() => {
        // A3. STALE → fresh，当前高 revision 状态不变
        const h = initHarness({
            serverFetch: refreshWith(
                {revision: 2, state: surveyState('submitted', 0, 1), seen: seenObject()},
                () => ({
                    ok: true,
                    json: () => Promise.resolve(serverStateResponse({revision: 1, state: null, seen: {}}))
                }))
        });
        return waitForServerContext(h).then(() => {
            const warnsBefore = h.consoleWarn.length;
            return directRefresh(h).then(result => {
                eq('STALE → status=fresh', result.status, 'fresh');
                eq('STALE → snapshotResult=stale', result.snapshotResult, 'stale');
                eq('STALE 不覆盖高 revision', h.api._internals.currentServerRevision(), 2);
                eq('STALE 状态不变', h.api._internals.effectiveState().status, 'submitted');
                eq('STALE 无新 warning', h.consoleWarn.length, warnsBefore);
            });
        });
    }).then(() => {
        // B1. 网络 reject → unavailable
        const h = initHarness({serverFetch: refreshSecond(() => Promise.reject(new Error('network down')))});
        return waitForServerContext(h).then(() => directRefresh(h)).then(result => {
            eq('网络 reject → unavailable', result.status, 'unavailable');
        });
    }).then(() => {
        // B2. 超时 → unavailable
        let gateResolve = null;
        const h = initHarness({serverFetch: refreshSecond(() => new Promise(resolve => { gateResolve = resolve; }))});
        return waitForServerContext(h).then(() => {
            const p = directRefresh(h);
            return waitForFlush().then(() => h.timers.advance(3000)).then(() => p).then(result => {
                eq('超时 → unavailable', result.status, 'unavailable');
                eq('超时 reason=timeout', result.reason, 'timeout');
            });
        });
    }).then(() => {
        // B3. HTTP 408 / 429 / 500 / 503 → unavailable
        return [408, 429, 500, 503].reduce((chain, status) => chain.then(() => {
            const h = initHarness({serverFetch: refreshSecond(() => ({
                ok: false, status, json: () => Promise.resolve({})
            }))});
            return waitForServerContext(h).then(() => directRefresh(h)).then(result => {
                eq('HTTP ' + status + ' → unavailable', result.status, 'unavailable');
            });
        }), Promise.resolve());
    }).then(() => {
        // C1. scoped 身份变化 → invalid
        const h = initHarness({
            serverFetch: refreshSecond(() => ({
                ok: true,
                json: () => Promise.resolve(serverStateResponse({
                    distinctId: 'plf_' + 'cd'.repeat(32), seen: seenObject()
                }))
            }))
        });
        return waitForServerContext(h).then(() => directRefresh(h)).then(result => {
            eq('身份变化 → invalid', result.status, 'invalid');
        });
    }).then(() => {
        // C2. 同 revision 内容冲突 → invalid
        const h = initHarness({
            serverFetch: refreshWith(
                {revision: 2, state: surveyState('submitted', 0, 1), seen: seenObject()},
                () => ({
                    ok: true,
                    json: () => Promise.resolve(serverStateResponse({
                        revision: 2, state: null, seen: seenObject()
                    }))
                }))
        });
        return waitForServerContext(h).then(() => {
            const warnsBefore = h.consoleWarn.length;
            return directRefresh(h).then(result => {
                eq('同 revision 内容冲突 → invalid', result.status, 'invalid');
                eq('冲突不修改当前状态', h.api._internals.effectiveState().status, 'submitted');
                ok('冲突记录安全 warning', h.consoleWarn.length > warnsBefore);
            });
        });
    }).then(() => {
        // C3. 2xx 非 JSON → invalid
        const h = initHarness({serverFetch: refreshSecond(() => ({
            ok: true, json: () => Promise.reject(new Error('bad json'))
        }))});
        return waitForServerContext(h).then(() => directRefresh(h)).then(result => {
            eq('2xx 非 JSON → invalid', result.status, 'invalid');
        });
    }).then(() => {
        // C4. 2xx schema 非法 → invalid
        const h = initHarness({serverFetch: refreshSecond(() => ({
            ok: true, json: () => Promise.resolve({available: 'yes'})
        }))});
        return waitForServerContext(h).then(() => directRefresh(h)).then(result => {
            eq('2xx schema 非法 → invalid', result.status, 'invalid');
        });
    }).then(() => {
        // C5. HTTP 400 / 401 / 403 / 404 → invalid
        return [400, 401, 403, 404].reduce((chain, status) => chain.then(() => {
            const h = initHarness({serverFetch: refreshSecond(() => ({
                ok: false, status, json: () => Promise.resolve({})
            }))});
            return waitForServerContext(h).then(() => directRefresh(h)).then(result => {
                eq('HTTP ' + status + ' → invalid', result.status, 'invalid');
            });
        }), Promise.resolve());
    }).then(() => {
        // D1. destroy → cancelled
        let gateResolve = null;
        const h = initHarness({serverFetch: refreshSecond(() => new Promise(resolve => { gateResolve = resolve; }))});
        return waitForServerContext(h).then(() => {
            const g = h.api._internals.currentGeneration();
            const p = h.api._internals.refreshServerContext(g);
            return waitForFlush().then(() => {
                h.api.destroy();
                return p.then(result => {
                    eq('destroy → cancelled', result.status, 'cancelled');
                    eq('取消原因为 destroy', result.reason, 'destroy');
                });
            });
        });
    }).then(() => {
        // D2. generation stale → cancelled
        const h = initHarness({serverFetch: validFirst()});
        return waitForServerContext(h).then(() => {
            const g = h.api._internals.currentGeneration();
            return h.api._internals.refreshServerContext(g + 5).then(result => {
                eq('generation stale → cancelled', result.status, 'cancelled');
            });
        });
    }).then(() => {
        // D3. 迟到 response → 无副作用
        let gateResolve = null;
        const h = initHarness({serverFetch: refreshSecond(() => new Promise(resolve => { gateResolve = resolve; }))});
        let warnsBefore = 0;
        return waitForServerContext(h).then(() => {
            const g = h.api._internals.currentGeneration();
            const p = h.api._internals.refreshServerContext(g);
            return waitForFlush().then(() => {
                h.api.destroy();
                return p.then(result => {
                    eq('destroy 后 refresh 结果 cancelled', result.status, 'cancelled');
                });
            });
        }).then(() => {
            warnsBefore = h.consoleWarn.length;
            gateResolve({ok: true, json: () => Promise.resolve(serverStateResponse({
                revision: 9, state: surveyState('submitted', 0, 1), seen: {}
            }))});
            return waitForFlush();
        }).then(() => {
            eq('迟到响应不推进 revision', h.api._internals.currentServerRevision(), 0);
            eq('迟到响应不写状态', h.storage.getItem(STATE_KEY), null);
            eq('迟到响应无新 warning', h.consoleWarn.length, warnsBefore);
            eq('无残留定时器', h.timers.pending().length, 0);
        });
    });
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

function testSubmitPreflightFailClosed() {
    return Promise.resolve().then(() => {
        // 1. scoped 身份变化：fail-closed，输入保留，控件恢复，安全 warning
        const h = initHarness({
            batchLayout: 'landscape',
            serverState: serverStateResponse({seen: seenObject()})
        });
        return waitForServerContext(h).then(() => openAndPrepareSubmit(h)).then(() => {
            h.setServerState(serverStateResponse({
                distinctId: 'plf_' + 'cd'.repeat(32),
                seen: seenObject()
            }));
            h.submitButton().click();
            return waitForFlush();
        }).then(() => {
            assertFailClosedInvariants(h, '身份冲突');
            h.api.refreshLanguage(createFakeI18n({
                'layout-feedback:error-state-verification':
                    'The survey state could not be verified. Please try again later.'
            }));
            eq('语言切换后错误文案刷新', h.error().textContent,
                'The survey state could not be verified. Please try again later.');
            eq('语言切换不重复 shown', captureEvents(h).filter(e => e === 'survey shown').length, 1);
            const warns = JSON.stringify(h.consoleWarn);
            ok('warning 不含 A/B 身份、token、Survey ID',
                warns.indexOf('plf_') < 0
                && warns.indexOf('phc_test_project_token') < 0
                && warns.indexOf(h.config.surveyId) < 0);
        });
    }).then(() => {
        // 2. 同 revision 内容冲突：同样 fail-closed
        const h = initHarness({
            batchLayout: 'landscape',
            serverState: serverStateResponse({seen: seenObject()})
        });
        return waitForServerContext(h).then(() => openAndPrepareSubmit(h)).then(() => {
            // 以当前真实 revision 构造同 revision 不同内容响应（reconcile 可能已推进
            // revision，不能写死 0，否则会落入 STALE 而不是冲突）。
            const currentRevision = h.api._internals.currentServerRevision();
            h.setServerState(serverStateResponse({
                revision: currentRevision,
                state: surveyState('submitted', 0, 999),
                seen: seenObject()
            }));
            h.submitButton().click();
            return waitForFlush();
        }).then(() => {
            assertFailClosedInvariants(h, '同 revision 冲突');
            const warns = JSON.stringify(h.consoleWarn);
            ok('同 revision 冲突 warning 不含 token / Survey ID / scoped ID',
                warns.indexOf('phc_test_project_token') < 0
                && warns.indexOf(h.config.surveyId) < 0
                && warns.indexOf('plf_') < 0);
        });
    }).then(() => {
        // 3. 2xx 响应 malformed JSON：fail-closed
        const h = initHarness({
            batchLayout: 'landscape',
            serverFetch: refreshSecond(() => ({
                ok: true, json: () => Promise.reject(new Error('bad json'))
            }))
        });
        return waitForServerContext(h).then(() => openAndPrepareSubmit(h)).then(() => {
            h.submitButton().click();
            return waitForFlush();
        }).then(() => {
            assertFailClosedInvariants(h, 'malformed 响应');
        });
    }).then(() => {
        // 4. HTTP 403：fail-closed
        const h = initHarness({
            batchLayout: 'landscape',
            serverFetch: refreshSecond(() => ({
                ok: false, status: 403, json: () => Promise.resolve({})
            }))
        });
        return waitForServerContext(h).then(() => openAndPrepareSubmit(h)).then(() => {
            h.submitButton().click();
            return waitForFlush();
        }).then(() => {
            assertFailClosedInvariants(h, 'HTTP 403');
        });
    });
}

function testSubmitPreflightUnavailableFailOpen() {
    return Promise.resolve().then(() => {
        // 5. 网络超时 → 按设计 fail-open：本地无阻断状态时仍发送一次 survey sent
        let gateResolve = null;
        const h = initHarness({
            batchLayout: 'landscape',
            serverFetch: refreshSecond(() => new Promise(resolve => { gateResolve = resolve; }))
        });
        return waitForServerContext(h).then(() => openAndPrepareSubmit(h)).then(() => {
            h.submitButton().click();
            return waitForFlush();
        }).then(() => {
            h.timers.advance(3000);
            return waitForFlush();
        }).then(() => {
            eq('超时 fail-open：发送一次 survey sent', captureEvents(h).filter(e => e === 'survey sent').length, 1);
            eq('提交后关闭弹窗', h.document.querySelectorAll('.plf-backdrop').length, 0);
            eq('提交后写 submitted', JSON.parse(h.storage.getItem(STATE_KEY)).status, 'submitted');
            ok('记录不含用户数据的安全 warning',
                h.consoleWarn.some(args => JSON.stringify(args).indexOf('preflight state refresh unavailable') >= 0));
        });
    }).then(() => {
        // 6. 超时后 localStorage 出现 submitted（无 storage 事件）：unavailable 也阻止提交
        let gateResolve = null;
        const h = initHarness({
            batchLayout: 'landscape',
            serverFetch: refreshSecond(() => new Promise(resolve => { gateResolve = resolve; }))
        });
        return waitForServerContext(h).then(() => openAndPrepareSubmit(h)).then(() => {
            h.submitButton().click();
            return waitForFlush();
        }).then(() => {
            h.storage.values.set(STATE_KEY, JSON.stringify(surveyState('submitted')));
            h.timers.advance(3000);
            return waitForFlush();
        }).then(() => {
            eq('unavailable + 本地 submitted：不发送 survey sent',
                captureEvents(h).filter(e => e === 'survey sent').length, 0);
            eq('弹窗关闭', h.document.querySelectorAll('.plf-backdrop').length, 0);
            eq('显示已在其他页面处理', h.toastCalls.length >= 1, true);
            eq('不发送 dismissed', captureEvents(h).indexOf('survey dismissed'), -1);
        });
    });
}

function testSubmitPreflightDestroyDuring() {
    // 7. preflight 在途时 destroy：不发送、不恢复已销毁 DOM、不显示错误
    const h = initHarness({
        batchLayout: 'landscape',
        serverFetch: refreshSecond(() => new Promise(() => {}))
    });
    return waitForServerContext(h).then(() => openAndPrepareSubmit(h)).then(() => {
        h.submitButton().click();
        return waitForFlush();
    }).then(() => {
        h.api.destroy();
        return waitForFlush();
    }).then(() => {
        eq('destroy during preflight 不发送 survey sent', captureEvents(h).filter(e => e === 'survey sent').length, 0);
        eq('不发送 dismissed', captureEvents(h).indexOf('survey dismissed'), -1);
        eq('destroy 后无弹窗', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('无错误显示（已销毁 DOM）', h.document.querySelectorAll('.plf-error').length, 0);
        eq('无 toast', h.toastCalls.length, 0);
        eq('无残留定时器', h.timers.pending().length, 0);
    });
}

function testSubmitPreflightStale() {
    return Promise.resolve().then(() => {
        // 8a. 当前高 revision 无状态 → STALE 迟到低 revision submitted → 允许提交
        // （不设置 batchLayout：避免 init 的 record_seen 推进 revision，保证低 revision
        // 响应真实落在 STALE 分支而不是冲突分支）。
        const h = initHarness({
            serverFetch: refreshWith(
                {revision: 2, state: null, seen: {}},
                () => ({
                    ok: true,
                    json: () => Promise.resolve(serverStateResponse({
                        revision: 1, state: surveyState('submitted', 0, 999), seen: {}
                    }))
                }))
        });
        return waitForServerContext(h).then(() => openAndPrepareSubmit(h)).then(() => {
            h.submitButton().click();
            return waitForFlush();
        }).then(() => {
            eq('STALE 按当前无状态判断：允许提交', captureEvents(h).filter(e => e === 'survey sent').length, 1);
        });
    }).then(() => {
        // 8b. preflight 在途时另一标签页写入 submitted → 即使 refresh 最终返回
        // 迟到低 revision（STALE），仍按当前更高 revision 的 submitted 阻止提交。
        let gateResolve = null;
        const h = initHarness({
            serverFetch: refreshWith(
                {revision: 2, state: null, seen: {}},
                () => new Promise(resolve => { gateResolve = resolve; }))
        });
        return waitForServerContext(h).then(() => openAndPrepareSubmit(h)).then(() => {
            h.submitButton().click();
            return waitForFlush();
        }).then(() => {
            h.dispatchStorage(STATE_KEY, JSON.stringify(surveyState('submitted')));
            return waitForFlush();
        }).then(() => {
            gateResolve({ok: true, json: () => Promise.resolve(serverStateResponse({
                revision: 1, state: null, seen: {}
            }))});
            return waitForFlush();
        }).then(() => {
            eq('STALE + 当前已 submitted：阻止提交', captureEvents(h).filter(e => e === 'survey sent').length, 0);
            eq('不发送 dismissed', captureEvents(h).indexOf('survey dismissed'), -1);
            eq('弹窗已关闭', h.document.querySelectorAll('.plf-backdrop').length, 0);
        });
    });
}

function testReconcileDecisionInFlightDestroyReinit() {
    // A. gen1 decision command 在途 → destroy → gen2 init → resolve gen1 command：
    // gen1 不进入 reconcileSeen；gen2 pending/state/seen/storage 不变；无旧 warning。
    let releaseDecision = null;
    let calls = 0;
    const h = initHarness({
        storage: {
            [STATE_KEY]: localStateValue('submitted'),
            [SEEN_KEY]: JSON.stringify(seenObject())
        },
        serverFetch: () => {
            calls++;
            if (calls === 1) {
                return {ok: true, json: () => Promise.resolve(serverStateResponse({seen: {}}))};
            }
            return {ok: true, json: () => Promise.resolve({available: false})};
        },
        serverPostResponse: ({body}) => {
            if (body.command !== 'submitted') return undefined;
            return new Promise(resolve => {
                releaseDecision = () => resolve({
                    ok: true,
                    json: () => Promise.resolve(serverStateResponse({
                        revision: 1,
                        state: surveyState('submitted', 0, 200),
                        seen: seenObject()
                    }))
                });
            });
        }
    });
    const storedBefore = {};
    let warnsBefore = 0;
    return waitForFlush().then(() => {
        eq('gen1 决策命令已发出', h.serverPosts.filter(p => p.body.command === 'submitted').length, 1);
        storedBefore.state = h.storage.getItem(STATE_KEY);
        storedBefore.seen = h.storage.getItem(SEEN_KEY);
        warnsBefore = h.consoleWarn.length;
        h.api.destroy();
        h.api.init(reinitOptions(h));
        return waitForFlush();
    }).then(() => {
        releaseDecision();
        return waitForFlush();
    }).then(() => {
        eq('gen1 不进入 reconcileSeen（无 record_seen 命令）',
            h.serverPosts.filter(p => p.body.command === 'record_seen').length, 0);
        eq('gen2 不发送新命令', h.serverPosts.length, 1);
        eq('gen2 localStorage 不被旧链改写', h.storage.getItem(STATE_KEY), storedBefore.state);
        eq('gen2 seen 缓存不被旧链改写', h.storage.getItem(SEEN_KEY), storedBefore.seen);
        eq('gen2 serverRevision 不被旧链改变', h.api._internals.currentServerRevision(), 0);
        eq('旧 generation 迟到完成无 warning', h.consoleWarn.length, warnsBefore);
        ok('无弹窗 / 无 toast / 无错误', h.document.querySelectorAll('.plf-backdrop').length === 0
            && h.toastCalls.length === 0
            && h.document.querySelectorAll('.plf-error').length === 0);
    });
}

function testReconcileSeenInFlightDestroyReinit() {
    // B. gen1 decision 完成、seen command 在途 → destroy → gen2 init →
    // resolve 旧 seen command：无副作用。
    let releaseSeen = null;
    let calls = 0;
    const h = initHarness({
        storage: {
            [SEEN_KEY]: JSON.stringify(seenObject())
        },
        serverFetch: () => {
            calls++;
            if (calls === 1) {
                return {ok: true, json: () => Promise.resolve(serverStateResponse({seen: {}}))};
            }
            return {ok: true, json: () => Promise.resolve({available: false})};
        },
        serverPostResponse: ({body}) => {
            if (body.command !== 'record_seen') return undefined;
            return new Promise(resolve => {
                releaseSeen = () => resolve({
                    ok: true,
                    json: () => Promise.resolve(serverStateResponse({
                        revision: 1,
                        state: null,
                        seen: seenObject()
                    }))
                });
            });
        }
    });
    const storedBefore = {};
    let warnsBefore = 0;
    return waitForFlush().then(() => {
        eq('gen1 seen 命令已发出', h.serverPosts.filter(p => p.body.command === 'record_seen').length, 1);
        storedBefore.seen = h.storage.getItem(SEEN_KEY);
        storedBefore.state = h.storage.getItem(STATE_KEY);
        warnsBefore = h.consoleWarn.length;
        h.api.destroy();
        h.api.init(reinitOptions(h));
        return waitForFlush();
    }).then(() => {
        releaseSeen();
        return waitForFlush();
    }).then(() => {
        eq('旧 seen 命令迟到无副作用：SEEN_KEY 不变', h.storage.getItem(SEEN_KEY), storedBefore.seen);
        eq('STATE_KEY 不变', h.storage.getItem(STATE_KEY), storedBefore.state);
        eq('gen2 不发送新命令', h.serverPosts.length, 1);
        eq('gen2 serverRevision 不变', h.api._internals.currentServerRevision(), 0);
        eq('旧 generation 迟到完成无 warning', h.consoleWarn.length, warnsBefore);
        ok('无弹窗 / 无 toast / 无错误', h.document.querySelectorAll('.plf-backdrop').length === 0
            && h.toastCalls.length === 0
            && h.document.querySelectorAll('.plf-error').length === 0);
    });
}

function testReconcileFinalSyncGuard() {
    // C. reconciliation 结束前 destroy：旧链不得执行 syncEffectiveCacheToLocal。
    // gen1 若错误同步会把 SEEN_KEY 覆盖为服务端 landscape-only 快照，观察 setItem 次数。
    let releaseDecision = null;
    let calls = 0;
    const h = initHarness({
        storage: {
            [STATE_KEY]: localStateValue('submitted'),
            [SEEN_KEY]: JSON.stringify(seenObject())
        },
        serverFetch: () => {
            calls++;
            if (calls === 1) {
                return {ok: true, json: () => Promise.resolve(serverStateResponse({
                    revision: 0,
                    state: null,
                    seen: {'pixiv-batch-landscape': {firstSeenAt: 5, lastSeenAt: 5}}
                }))};
            }
            return {ok: true, json: () => Promise.resolve({available: false})};
        },
        serverPostResponse: ({body}) => {
            if (body.command !== 'submitted') return undefined;
            return new Promise(resolve => {
                releaseDecision = () => resolve({
                    ok: true,
                    json: () => Promise.resolve(serverStateResponse({
                        revision: 1,
                        state: surveyState('submitted', 0, 200),
                        seen: {'pixiv-batch-landscape': {firstSeenAt: 5, lastSeenAt: 5}}
                    }))
                });
            });
        }
    });
    let seenSetsBefore = 0;
    let stateBefore = null;
    return waitForFlush().then(() => {
        seenSetsBefore = h.storage.setCalls.filter(c => c[0] === SEEN_KEY).length;
        stateBefore = h.storage.getItem(STATE_KEY);
        h.api.destroy();
        releaseDecision();
        return waitForFlush();
    }).then(() => {
        eq('destroy 后旧链不执行最终缓存同步',
            h.storage.setCalls.filter(c => c[0] === SEEN_KEY).length, seenSetsBefore);
        eq('localStorage 状态未变', h.storage.getItem(STATE_KEY), stateBefore);
        eq('serverRevision 未被旧链推进', h.api._internals.currentServerRevision(), 0);
        eq('无残留定时器', h.timers.pending().length, 0);
    });
}

function testWriteStateMonotonic() {
    const DAY = 24 * 60 * 60 * 1000;
    const ONE_DAY = 1000000 + DAY;
    const SEVEN_DAYS = 1000000 + 7 * DAY;
    return Promise.resolve().then(() => {
        // 1. submitted + snooze：submitted 保留，无 snooze POST
        const h = initHarness({
            batchLayout: 'landscape',
            serverState: serverStateResponse({state: surveyState('submitted', 0, 1), seen: seenObject()})
        });
        return waitForServerContext(h).then(() => h.api.open()).then(() => waitForFlush()).then(() => {
            h.actionButton('snooze').click();
            return waitForFlush();
        }).then(() => {
            eq('server submitted + snooze：本地仍 submitted',
                JSON.parse(h.storage.getItem(STATE_KEY)).status, 'submitted');
            eq('无 snooze POST', h.serverPosts.filter(p => p.body.command === 'snooze').length, 0);
        });
    }).then(() => {
        // 2. submitted + never：submitted 保留，无 never POST，无 dismissed
        const h = initHarness({
            batchLayout: 'landscape',
            serverState: serverStateResponse({state: surveyState('submitted', 0, 1), seen: seenObject()})
        });
        return waitForServerContext(h).then(() => h.api.open()).then(() => waitForFlush()).then(() => {
            h.actionButton('never').click();
            return waitForFlush();
        }).then(() => {
            eq('server submitted + never：本地仍 submitted',
                JSON.parse(h.storage.getItem(STATE_KEY)).status, 'submitted');
            eq('无 never POST', h.serverPosts.filter(p => p.body.command === 'never').length, 0);
            eq('已有 submitted 时不发送 dismissed', captureEvents(h).indexOf('survey dismissed'), -1);
        });
    }).then(() => {
        // 3. never + snooze：never 保留，无 snooze POST
        const h = initHarness({
            batchLayout: 'landscape',
            serverState: serverStateResponse({state: surveyState('never', 0, 1), seen: seenObject()})
        });
        return waitForServerContext(h).then(() => h.api.open()).then(() => waitForFlush()).then(() => {
            h.actionButton('snooze').click();
            return waitForFlush();
        }).then(() => {
            eq('server never + snooze：本地仍 never',
                JSON.parse(h.storage.getItem(STATE_KEY)).status, 'never');
            eq('无 snooze POST', h.serverPosts.filter(p => p.body.command === 'snooze').length, 0);
        });
    }).then(() => {
        // 4. localStorage snooze 7 天 + 新 snooze 1 天：保留 7 天，不发送较短命令
        const h = initHarness({
            storage: {[STATE_KEY]: localStateValue('snoozed', SEVEN_DAYS)},
            serverState: serverStateResponse({
                state: surveyState('snoozed', SEVEN_DAYS, 1),
                seen: {}
            })
        });
        return waitForServerContext(h).then(() => {
            const result = h.api._internals.writeState('snoozed', ONE_DAY);
            return waitForFlush().then(() => {
                eq('较短 snooze 不被接受', result.transitionAccepted, false);
                eq('有效状态保留 7 天', result.effectiveState.snoozedUntil, SEVEN_DAYS);
                eq('不发送较短 snooze 命令', h.serverPosts.filter(p => p.body.command === 'snooze').length, 0);
                eq('本地协调缓存仍为 7 天', JSON.parse(h.storage.getItem(STATE_KEY)).snoozedUntil, SEVEN_DAYS);
            });
        });
    }).then(() => {
        // 5. localStorage snooze 1 天 + 新 snooze 7 天：7 天生效，发送一次 snooze POST
        const h = initHarness({
            storage: {[STATE_KEY]: localStateValue('snoozed', ONE_DAY)},
            serverState: serverStateResponse({
                state: surveyState('snoozed', ONE_DAY, 1),
                seen: {}
            })
        });
        return waitForServerContext(h).then(() => {
            const result = h.api._internals.writeState('snoozed', SEVEN_DAYS);
            return waitForFlush().then(() => {
                eq('更强 snooze 被接受', result.transitionAccepted, true);
                eq('生效状态为 7 天', result.effectiveState.snoozedUntil, SEVEN_DAYS);
                eq('发送一次 snooze POST', h.serverPosts.filter(p => p.body.command === 'snooze').length, 1);
                eq('本地协调缓存更新为 7 天', JSON.parse(h.storage.getItem(STATE_KEY)).snoozedUntil, SEVEN_DAYS);
            });
        });
    }).then(() => {
        // 6. never + submitted：submitted 生效，发送 submitted POST
        const h = initHarness({
            serverState: serverStateResponse({state: surveyState('never', 0, 1), seen: {}})
        });
        return waitForServerContext(h).then(() => {
            const result = h.api._internals.writeState('submitted');
            return waitForFlush().then(() => {
                eq('submitted 升级 never', result.transitionAccepted, true);
                eq('生效状态为 submitted', result.effectiveState.status, 'submitted');
                eq('发送 submitted POST', h.serverPosts.filter(p => p.body.command === 'submitted').length, 1);
                eq('本地协调缓存为 submitted', JSON.parse(h.storage.getItem(STATE_KEY)).status, 'submitted');
            });
        });
    }).then(() => {
        // 7. 多标签近同时写入：storage 最终保留最强状态
        const h = initHarness({
            storage: {[STATE_KEY]: localStateValue('submitted')},
            serverState: serverStateResponse({seen: {}})
        });
        return waitForServerContext(h).then(() => {
            const snoozeResult = h.api._internals.writeState('snoozed', SEVEN_DAYS);
            const neverResult = h.api._internals.writeState('never');
            return waitForFlush().then(() => {
                eq('弱写入不被接受', snoozeResult.transitionAccepted === false
                    && neverResult.transitionAccepted === false, true);
                eq('storage 保留最强状态', JSON.parse(h.storage.getItem(STATE_KEY)).status, 'submitted');
                eq('不发送降级命令', h.serverPosts.filter(p => p.body.command === 'snooze'
                    || p.body.command === 'never').length, 0);
            });
        });
    }).then(() => {
        // 8. 相同状态重复写：setItem 调用次数不增加
        const h = initHarness({
            serverState: serverStateResponse({seen: {}}),
            serverPostResponse: 'fail'
        });
        return waitForServerContext(h).then(() => {
            const first = h.api._internals.writeState('snoozed', 2000000);
            eq('第一次写被接受', first.transitionAccepted, true);
            eq('首次写入一次', h.storage.setCalls.filter(c => c[0] === STATE_KEY).length, 1);
            const second = h.api._internals.writeState('snoozed', 2000000);
            eq('相同状态重复写不再 setItem',
                h.storage.setCalls.filter(c => c[0] === STATE_KEY).length, 1);
            eq('重复写不发送第二次命令', h.serverPosts.filter(p => p.body.command === 'snooze').length, 1);
            eq('重复写保持状态', second.effectiveState.snoozedUntil, 2000000);
        });
    });
}

/* ============================================================
   同强度幂等 / 跨设备时钟 / 命令确认 / Content-Type 语义（新增）
=========================================================== */

function testDecisionEntriesEqual() {
    const internals = initHarness({}).api._internals;
    const a = {surveyId: 's', status: 'never', updatedAt: 5, snoozedUntil: 0};
    eq('线格式对象完全相同', internals.decisionEntriesEqual(a, Object.assign({}, a)), true);
    eq('updatedAt 不同不算完全相同', internals.decisionEntriesEqual(
        a, Object.assign({}, a, {updatedAt: 6})), false);
    eq('null 与 null 相同', internals.decisionEntriesEqual(null, null), true);
    eq('null 与状态不同', internals.decisionEntriesEqual(null, a), false);
}

function testWriteStateIdempotentAcrossTime() {
    const DAY = 24 * 60 * 60 * 1000;
    const stateSets = (h) => h.storage.setCalls.filter(c => c[0] === STATE_KEY).length;
    const postsOf = (h, command) => h.serverPosts.filter(p => p.body.command === command).length;
    return Promise.resolve().then(() => {
        // A. repeated submitted：时间前进后重复写不刷新 updatedAt / 不重复写 / 不重复命令
        const h = initHarness({
            serverState: serverStateResponse({seen: {}}),
            serverPostResponse: 'fail'
        });
        return waitForServerContext(h).then(() => {
            const first = h.api._internals.writeState('submitted');
            const updatedAt = first.effectiveState.updatedAt;
            eq('首次 submitted 被接受', first.transitionAccepted, true);
            eq('首次写一次 setItem', stateSets(h), 1);
            eq('首次 submitted 命令一次', postsOf(h, 'submitted'), 1);
            h.timers.advance(5000);
            return waitForFlush();
        }).then(() => {
            const second = h.api._internals.writeState('submitted');
            eq('重复 submitted 不被接受', second.transitionAccepted, false);
            eq('updatedAt 保持原值', second.effectiveState.updatedAt, 1000000);
            eq('不重复 setItem', stateSets(h), 1);
            eq('不重复 submitted 命令', postsOf(h, 'submitted'), 1);
            ok('previousState 为已有最强状态', second.previousState !== null
                && second.previousState.status === 'submitted');
        });
    }).then(() => {
        // B. repeated never：同样验证
        const h = initHarness({
            serverState: serverStateResponse({seen: {}}),
            serverPostResponse: 'fail'
        });
        return waitForServerContext(h).then(() => {
            const first = h.api._internals.writeState('never');
            eq('首次 never 被接受', first.transitionAccepted, true);
            h.timers.advance(5000);
            return waitForFlush();
        }).then(() => {
            const second = h.api._internals.writeState('never');
            eq('重复 never 不被接受', second.transitionAccepted, false);
            eq('updatedAt 保持原值', second.effectiveState.updatedAt, 1000000);
            eq('不重复 setItem', stateSets(h), 1);
            eq('不重复 never 命令', postsOf(h, 'never'), 1);
        });
    }).then(() => {
        // C. repeated same snooze：相同 snoozedUntil，now 前进
        const h = initHarness({
            serverState: serverStateResponse({seen: {}}),
            serverPostResponse: 'fail'
        });
        return waitForServerContext(h).then(() => {
            const first = h.api._internals.writeState('snoozed', 2000000);
            eq('首次 snooze 被接受', first.transitionAccepted, true);
            h.timers.advance(5000);
            return waitForFlush();
        }).then(() => {
            const second = h.api._internals.writeState('snoozed', 2000000);
            eq('相同 snoozedUntil 不被接受', second.transitionAccepted, false);
            eq('updatedAt 保持原值', second.effectiveState.updatedAt, 1000000);
            eq('snoozedUntil 保持', second.effectiveState.snoozedUntil, 2000000);
            eq('不重复 setItem', stateSets(h), 1);
            eq('不重复 snooze 命令', postsOf(h, 'snooze'), 1);
        });
    }).then(() => {
        // D. longer snooze：接受、updatedAt 更新、发送命令
        const h = initHarness({
            serverState: serverStateResponse({seen: {}}),
            serverPostResponse: 'fail'
        });
        return waitForServerContext(h).then(() => {
            h.api._internals.writeState('snoozed', 1000000 + DAY);
            h.timers.advance(1000);
            return waitForFlush();
        }).then(() => {
            const longer = h.api._internals.writeState('snoozed', 1000000 + 7 * DAY);
            eq('更长 snooze 被接受', longer.transitionAccepted, true);
            eq('updatedAt 更新', longer.effectiveState.updatedAt, 1001000);
            eq('发送一次 snooze 命令', postsOf(h, 'snooze'), 2);
        });
    }).then(() => {
        // E. shorter snooze：拒绝、保留原对象
        const h = initHarness({
            serverState: serverStateResponse({seen: {}}),
            serverPostResponse: 'fail'
        });
        return waitForServerContext(h).then(() => {
            h.api._internals.writeState('snoozed', 1000000 + 7 * DAY);
            h.timers.advance(1000);
            return waitForFlush();
        }).then(() => {
            const shorter = h.api._internals.writeState('snoozed', 1000000 + DAY);
            eq('更短 snooze 被拒绝', shorter.transitionAccepted, false);
            eq('保留原对象', shorter.effectiveState.snoozedUntil, 1000000 + 7 * DAY);
            eq('snooze 命令仍只有一次', postsOf(h, 'snooze'), 1);
        });
    });
}

function testDismissedIdempotentAcrossTime() {
    return Promise.resolve().then(() => {
        // 首次 never：transitionAccepted=true + 一条 dismissed；时间推进后再次
        // 通过测试入口打开并触发 never：不重复 dismissed / updatedAt 不变 /
        // localStorage 不重复写 / 不重复 never 命令。
        let firstUpdatedAt = 0;
        let setsAfterFirst = 0;
        let neverPostsAfterFirst = 0;
        const h = initHarness({
            batchLayout: 'landscape',
            serverState: serverStateResponse({seen: seenObject()}),
            serverPostResponse: 'fail'
        });
        return waitForServerContext(h).then(() => h.api.open()).then(() => waitForFlush()).then(() => {
            h.actionButton('never').click();
            return waitForFlush();
        }).then(() => {
            const state = JSON.parse(h.storage.getItem(STATE_KEY));
            eq('首次 never 写状态', state.status, 'never');
            firstUpdatedAt = state.updatedAt;
            eq('首次 dismissed 发送一次', captureEvents(h).filter(e => e === 'survey dismissed').length, 1);
            setsAfterFirst = h.storage.setCalls.filter(c => c[0] === STATE_KEY).length;
            neverPostsAfterFirst = h.serverPosts.filter(p => p.body.command === 'never').length;
            eq('首次 never 命令一次', neverPostsAfterFirst, 1);
            h.timers.advance(5000);
            return waitForFlush();
        }).then(() => h.api.open().then(() => waitForFlush())).then(() => {
            h.actionButton('never').click();
            return waitForFlush();
        }).then(() => {
            eq('重复 never 不重复 dismissed', captureEvents(h).filter(e => e === 'survey dismissed').length, 1);
            const state = JSON.parse(h.storage.getItem(STATE_KEY));
            eq('updatedAt 不变化', state.updatedAt, firstUpdatedAt);
            eq('localStorage 不重复写', h.storage.setCalls.filter(c => c[0] === STATE_KEY).length, setsAfterFirst);
            eq('不重复 never 命令', h.serverPosts.filter(p => p.body.command === 'never').length, neverPostsAfterFirst);
        });
    }).then(() => {
        // 已 submitted 后 never：dismissed 为 0
        const h = initHarness({
            batchLayout: 'landscape',
            serverState: serverStateResponse({
                state: surveyState('submitted', 0, 1),
                seen: seenObject()
            })
        });
        return waitForServerContext(h).then(() => h.api.open()).then(() => waitForFlush()).then(() => {
            h.actionButton('never').click();
            return waitForFlush();
        }).then(() => {
            eq('已 submitted 后 never 不发送 dismissed', captureEvents(h).filter(e => e === 'survey dismissed').length, 0);
        });
    });
}

function testServerTimeValidation() {
    return Promise.resolve().then(() => {
        // 1. 缺失 serverTime：快照非法 → refresh invalid；offset 不更新；preflight fail-closed
        const h = initHarness({
            serverFetch: refreshSecond(() => ({
                ok: true,
                json: () => Promise.resolve({
                    available: true, stateAvailable: true,
                    distinctId: SERVER_SCOPED_ID, revision: 0, state: null, seen: {}
                })
            }))
        });
        return waitForServerContext(h).then(() => {
            eq('初始快照已应用（含 serverTime）', h.api._internals.isServerSnapshotInitialized(), true);
            eq('初始采样后 offset 为 0', h.api._internals.serverClockOffsetMs(), 0);
            return directRefresh(h).then(result => {
                eq('缺失 serverTime → invalid', result.status, 'invalid');
                eq('invalid 不更新 offset', h.api._internals.serverClockOffsetMs(), 0);
            });
        });
    }).then(() => {
        // 2. 非有限整数 / 负数 serverTime：同样 invalid 且 offset 不变
        const bad = [-1, 1.5, Number.NaN, Number.POSITIVE_INFINITY, '123', 1000.25];
        return bad.reduce((chain, value) => chain.then(() => {
            const h = initHarness({
                serverFetch: refreshWith(
                    {seen: seenObject()},
                    () => ({
                        ok: true,
                        json: () => Promise.resolve({
                            available: true, stateAvailable: true,
                            distinctId: SERVER_SCOPED_ID, revision: 1, state: null, seen: {},
                            serverTime: value
                        })
                    }))
            });
            return waitForServerContext(h).then(() => {
                const before = h.api._internals.serverClockOffsetMs();
                return directRefresh(h).then(result => {
                    eq('非法 serverTime=' + String(value) + ' → invalid', result.status, 'invalid');
                    eq('非法 serverTime 不更新 offset', h.api._internals.serverClockOffsetMs(), before);
                });
            });
        }), Promise.resolve());
    }).then(() => {
        // 3. preflight 遇非法 serverTime：fail-closed，不发送 survey sent
        const h = initHarness({
            batchLayout: 'landscape',
            serverFetch: refreshSecond(() => ({
                ok: true,
                json: () => Promise.resolve({
                    available: true, stateAvailable: true,
                    distinctId: SERVER_SCOPED_ID, revision: 1, state: null, seen: {},
                    serverTime: 'nope'
                })
            }))
        });
        return waitForServerContext(h).then(() => openAndPrepareSubmit(h)).then(() => {
            h.submitButton().click();
            return waitForFlush();
        }).then(() => {
            assertFailClosedInvariants(h, '非法 serverTime');
        });
    });
}

function testServerTimeSameRevisionDifferentValues() {
    // 同 revision / state / seen 相同、serverTime 不同：仍为 SNAPSHOT_SAME，
    // 不判定同 revision 内容冲突；serverTime 不参与内容一致性比较。
    const h = initHarness({
        serverFetch: refreshWith(
            {revision: 2, state: surveyState('submitted', 0, 1), seen: seenObject()},
            () => ({
                ok: true,
                json: () => Promise.resolve(serverStateResponse({
                    revision: 2,
                    serverTime: 987654321,
                    state: surveyState('submitted', 0, 1),
                    seen: seenObject()
                }))
            }))
    });
    let warnsBefore = 0;
    return waitForServerContext(h).then(() => {
        warnsBefore = h.consoleWarn.length;
        return directRefresh(h).then(result => {
            eq('同 revision 不同 serverTime → fresh/same', result.status, 'fresh');
            eq('snapshotResult=same', result.snapshotResult, 'same');
            eq('revision 不变', h.api._internals.currentServerRevision(), 2);
            eq('状态不变', h.api._internals.effectiveState().status, 'submitted');
            eq('不判定内容冲突（无新 warning）', h.consoleWarn.length, warnsBefore);
        });
    });
}

function testServerClockSamplingGuards() {
    return Promise.resolve().then(() => {
        // 1. 命令超时后迟到响应：不更新 offset
        let gateResolve = null;
        const h = initHarness({
            batchLayout: 'landscape',
            serverState: serverStateResponse({seen: seenObject()}),
            serverPostResponse: () => new Promise(resolve => { gateResolve = resolve; })
        });
        return waitForServerContext(h).then(() => h.api.open()).then(() => waitForFlush()).then(() => {
            h.timers.advance(11000);
            return waitForFlush();
        }).then(() => {
            h.actionButton('snooze').click();
            return waitForFlush();
        }).then(() => {
            const offsetBefore = h.api._internals.serverClockOffsetMs();
            h.timers.advance(4000);
            return waitForFlush();
        }).then(() => {
            gateResolve({ok: true, json: () => Promise.resolve(serverStateResponse({
                revision: 9, serverTime: 999999999, state: null, seen: seenObject()
            }))});
            return waitForFlush();
        }).then(() => {
            eq('超时后迟到响应不更新 offset', h.api._internals.serverClockOffsetMs(), 0);
        });
    }).then(() => {
        // 2. destroy 后迟到响应：不更新 offset（destroy 同时重置估计）
        let gateResolve = null;
        const h = initHarness({
            batchLayout: 'landscape',
            serverState: serverStateResponse({seen: seenObject()}),
            serverPostResponse: () => new Promise(resolve => { gateResolve = resolve; })
        });
        return waitForServerContext(h).then(() => h.api.open()).then(() => waitForFlush()).then(() => {
            h.timers.advance(11000);
            return waitForFlush();
        }).then(() => {
            h.actionButton('snooze').click();
            return waitForFlush();
        }).then(() => {
            h.api.destroy();
            return waitForFlush();
        }).then(() => {
            eq('destroy 重置时钟估计', h.api._internals.serverClockKnown(), false);
            gateResolve({ok: true, json: () => Promise.resolve(serverStateResponse({
                revision: 9, serverTime: 999999999, state: null, seen: seenObject()
            }))});
            return waitForFlush();
        }).then(() => {
            eq('destroy 后迟到响应不更新 offset', h.api._internals.serverClockOffsetMs(), 0);
        });
    }).then(() => {
        // 3. record_seen 409 后第二次 attempt 成功：两次 APPLIED 均采样，最终 offset
        //    由最后一次成功响应决定；409 冲突快照（未满足）也合法采样。
        let attempts = 0;
        const h = initHarness({
            batchLayout: 'landscape',
            serverState: serverStateResponse({seen: {}}),
            serverPostResponse: ({body}) => {
                if (body.command !== 'record_seen') return undefined;
                attempts++;
                if (attempts === 1) {
                    return {
                        status: 409,
                        json: () => Promise.resolve(serverStateResponse({
                            revision: 2, serverTime: 900000, state: null,
                            seen: {}
                        }))
                    };
                }
                return {
                    ok: true,
                    json: () => Promise.resolve(serverStateResponse({
                        revision: 3, serverTime: 1100000, state: null,
                        seen: {'pixiv-batch-landscape': {firstSeenAt: 1, lastSeenAt: 1}}
                    }))
                };
            }
        });
        return waitForServerContext(h).then(() => {
            h.timers.advance(600);
            return waitForFlush();
        }).then(() => {
            eq('第二次 attempt 成功后可更新', h.api._internals.serverClockOffsetMs(), 100000);
            eq('offset 来自最后一次成功响应', h.api._internals.serverClockSampleRttMs() !== null, true);
            eq('确认后 pending 清理（有效 seen 含 landscape）',
                h.api._internals.effectiveSeen()['pixiv-batch-landscape'] !== undefined, true);
        });
    });
}

function testClockSkewSnoozeConfirmation() {
    const MIN = 60 * 1000;
    const serverSnoozeHarness = (serverTimeAtInit) => {
        const h = initHarness({
            batchLayout: 'landscape',
            serverState: serverStateResponse({
                serverTime: serverTimeAtInit,
                revision: 0,
                state: null,
                seen: seenObject()
            }),
            serverPostResponse: ({body}) => {
                if (body.command !== 'snooze') return undefined;
                return {
                    ok: true,
                    json: () => Promise.resolve(serverStateResponse({
                        revision: 1,
                        serverTime: serverTimeAtInit,
                        state: surveyState('snoozed', serverTimeAtInit + SNOOZE_MS, serverTimeAtInit),
                        seen: seenObject()
                    }))
                };
            }
        });
        return h;
    };
    return Promise.resolve().then(() => {
        // A. 客户端快 10 分钟：snooze 命令确认成功、pending 清理、
        //    localStorage 使用服务端 snoozedUntil、不重复 reconciliation、无保存失败。
        const serverTime = 1000000 - 10 * MIN;
        const h = serverSnoozeHarness(serverTime);
        return waitForServerContext(h).then(() => h.api.open()).then(() => waitForFlush()).then(() => {
            h.actionButton('snooze').click();
            return waitForFlush();
        }).then(() => {
            const posts = h.serverPosts.filter(p => p.body.command === 'snooze');
            eq('snooze 命令发送一次', posts.length, 1);
            eq('offset 为 -10 分钟（客户端快）', h.api._internals.serverClockOffsetMs(), -10 * MIN);
            const localState = JSON.parse(h.storage.getItem(STATE_KEY));
            eq('localStorage 使用服务端 snoozedUntil', localState.snoozedUntil, serverTime + SNOOZE_MS);
            ok('无保存失败 warning', !h.consoleWarn.some(args =>
                JSON.stringify(args).indexOf('server state save failed') >= 0));
            ok('pending 已清理（effectiveState 为服务端权威）',
                h.api._internals.effectiveState().snoozedUntil === serverTime + SNOOZE_MS);
            h.timers.advance(11000);
            return waitForFlush();
        }).then(() => {
            eq('不重复 reconciliation（snooze 命令仍一次）',
                h.serverPosts.filter(p => p.body.command === 'snooze').length, 1);
        });
    }).then(() => {
        // B. 客户端慢 10 分钟：同样确认成功并使用服务端权威时间。
        const serverTime = 1000000 + 10 * MIN;
        const h = serverSnoozeHarness(serverTime);
        return waitForServerContext(h).then(() => h.api.open()).then(() => waitForFlush()).then(() => {
            h.actionButton('snooze').click();
            return waitForFlush();
        }).then(() => {
            eq('snooze 命令发送一次', h.serverPosts.filter(p => p.body.command === 'snooze').length, 1);
            eq('offset 为 +10 分钟（客户端慢）', h.api._internals.serverClockOffsetMs(), 10 * MIN);
            const localState = JSON.parse(h.storage.getItem(STATE_KEY));
            eq('localStorage 使用服务端 snoozedUntil', localState.snoozedUntil, serverTime + SNOOZE_MS);
            ok('无保存失败 warning', !h.consoleWarn.some(args =>
                JSON.stringify(args).indexOf('server state save failed') >= 0));
            eq('snooze 命令不重复', h.serverPosts.filter(p => p.body.command === 'snooze').length, 1);
        });
    });
}

function testServerSnoozeValidityByServerClock() {
    const MIN = 60 * 1000;
    // 初始服务端 submitted：阻断 init 后的自动流程；随后用带 serverTime 的 snooze
    // 快照替换，验证过期判断完全由服务端时钟估计决定。
    const initialBlocked = () => serverStateResponse({
        state: surveyState('submitted', 0, 1),
        seen: seenObject()
    });
    return Promise.resolve().then(() => {
        // C. 客户端时钟快 30 分钟，server snooze 尚余 20 分钟：
        //    按 serverClockOffset 判断仍有效，不提前展示调查。
        const h = initHarness({
            serverState: initialBlocked()
        });
        return waitForServerContext(h).then(() => {
            h.timers.advance(2000000);
            return waitForFlush();
        }).then(() => {
            const clientNow = h.timers.now();
            const serverNow = clientNow - 30 * MIN;
            const snoozedUntil = serverNow + 20 * MIN;
            h.setServerState(serverStateResponse({
                revision: 1,
                serverTime: serverNow,
                state: surveyState('snoozed', snoozedUntil, serverNow),
                seen: seenObject()
            }));
            return directRefresh(h).then(() => waitForFlush());
        }).then(() => {
            eq('offset 为 -30 分钟', h.api._internals.serverClockOffsetMs(), -30 * MIN);
            eq('按服务端时钟判断 snooze 仍有效', h.api._internals.effectiveState().status, 'snoozed');
            // 重新调度一次自动评估：仍不提前展示。
            h.dispatchLayoutChanged('portrait', 'landscape');
            h.timers.advance(11000);
            return waitForFlush();
        }).then(() => {
            eq('不提前展示调查', h.document.querySelectorAll('.plf-backdrop').length, 0);
        });
    }).then(() => {
        // D. 客户端时钟慢 30 分钟，服务端 snooze 已过期：按服务端时钟判断已过期，
        //    不因客户端慢钟延长，调查可重新满足展示条件。
        const h = initHarness({
            serverState: initialBlocked()
        });
        return waitForServerContext(h).then(() => {
            h.timers.advance(2000000);
            return waitForFlush();
        }).then(() => {
            const clientNow = h.timers.now();
            const serverNow = clientNow + 30 * MIN;
            // 服务端时钟域已过期（until < serverNow），客户端慢钟下数值仍大于 clientNow。
            const snoozedUntil = serverNow - 5 * MIN;
            h.setServerState(serverStateResponse({
                revision: 1,
                serverTime: serverNow,
                state: surveyState('snoozed', snoozedUntil, serverNow),
                seen: seenObject()
            }));
            return directRefresh(h).then(() => waitForFlush());
        }).then(() => {
            eq('offset 为 +30 分钟', h.api._internals.serverClockOffsetMs(), 30 * MIN);
            eq('按服务端时钟判断 snooze 已过期（effective 无状态）',
                h.api._internals.effectiveState(), null);
            // 重新调度自动评估：服务端 snooze 已过期，调查重新满足展示条件。
            h.dispatchLayoutChanged('portrait', 'landscape');
            h.timers.advance(11000);
            return waitForFlush();
        }).then(() => {
            eq('调查可重新满足展示条件', h.document.querySelectorAll('.plf-backdrop').length, 1);
        });
    });
}

function testSeenCrossClockSemantics() {
    return Promise.resolve().then(() => {
        // A. record_seen 200 成功，服务端 lastSeenAt 数值小于客户端：命令 acknowledged、
        //    pending 清理、不重复 record_seen。
        const h = initHarness({
            batchLayout: 'landscape',
            serverState: serverStateResponse({seen: {}}),
            serverPostResponse: ({body}) => {
                if (body.command !== 'record_seen') return undefined;
                return {
                    ok: true,
                    json: () => Promise.resolve(serverStateResponse({
                        revision: 1,
                        state: null,
                        seen: {'pixiv-batch-landscape': {firstSeenAt: 1, lastSeenAt: 1}}
                    }))
                };
            }
        });
        return waitForServerContext(h).then(() => {
            h.timers.advance(600);
            return waitForFlush();
        }).then(() => {
            const posts = h.serverPosts.filter(p => p.body.command === 'record_seen');
            eq('record_seen 提交一次', posts.length, 1);
            ok('服务端时间戳更小也视为已记录',
                h.api._internals.effectiveSeen()['pixiv-batch-landscape'] !== undefined);
            h.timers.advance(600);
            return waitForFlush();
        }).then(() => {
            eq('不重复 record_seen（按布局 ID 存在性）',
                h.serverPosts.filter(p => p.body.command === 'record_seen').length, 1);
        });
    }).then(() => {
        // B. 普通 GET 已包含目标布局（时间戳小于本地）：pending 对应布局可清理，
        //    seenCount 不下降。
        const h = initHarness({
            batchLayout: 'landscape',
            serverState: serverStateResponse({seen: {}}),
            serverPostResponse: 'fail'
        });
        return waitForServerContext(h).then(() => {
            h.timers.advance(600);
            return waitForFlush();
        }).then(() => {
            eq('flush 失败：pending 保留', h.api._internals.distinctSeenCount(h.api._internals.effectiveSeen()), 1);
            h.setServerState(serverStateResponse({
                revision: 1,
                state: null,
                seen: {'pixiv-batch-landscape': {firstSeenAt: 1, lastSeenAt: 1}}
            }));
            h.dispatchStorage(SEEN_KEY, JSON.stringify({}));
            return waitForFlush();
        }).then(() => {
            const effective = h.api._internals.effectiveSeen();
            eq('普通 GET 按布局 ID 清理 pending', h.api._internals.distinctSeenCount(effective), 1);
            eq('seenCount 不下降', h.api._internals.distinctSeenCount(effective), 1);
        });
    }).then(() => {
        // C. 服务端不含布局：pending 保留。
        const h = initHarness({
            batchLayout: 'landscape',
            serverState: serverStateResponse({seen: {}}),
            serverPostResponse: 'fail'
        });
        return waitForServerContext(h).then(() => {
            h.timers.advance(600);
            return waitForFlush();
        }).then(() => {
            h.setServerState(serverStateResponse({revision: 1, state: null, seen: {}}));
            h.dispatchStorage(SEEN_KEY, JSON.stringify({}));
            return waitForFlush();
        }).then(() => {
            eq('服务端不含布局：pending 保留',
                h.api._internals.distinctSeenCount(h.api._internals.effectiveSeen()), 1);
        });
    }).then(() => {
        // D. 客户端时钟偏差不影响布局数量。
        const h = initHarness({
            batchLayout: 'landscape',
            serverState: serverStateResponse({
                serverTime: 1000000 - 10 * 60 * 1000,
                state: null,
                seen: {
                    'pixiv-batch-landscape': {firstSeenAt: 1, lastSeenAt: 1},
                    'pixiv-batch-portrait': {firstSeenAt: 2, lastSeenAt: 2}
                }
            })
        });
        return waitForServerContext(h).then(() => {
            eq('时钟偏差下 seenCount 只按布局 ID 计数',
                h.api._internals.distinctSeenCount(h.api._internals.effectiveSeen()), 2);
        });
    });
}

/* ============================================================
   入口
=========================================================== */

async function run() {
    const step = async (name, fn) => {
        currentTest = name;
        await fn();
        process.stderr.write('STEP-DONE: ' + name + '\n');
    };
    await step('testLayoutMapping', testLayoutMapping);
    await step('testInitDestroy', testInitDestroy);
    await step('testSingleDialogAtMostOne', testSingleDialogAtMostOne);
    await step('testChoiceSchemaVariants', testChoiceSchemaVariants);
    await step('testSuggestionSchemaVariants', testSuggestionSchemaVariants);
    await step('testSurveyNotFoundOrHidden', testSurveyNotFoundOrHidden);
    await step('testSurveyShownOncePerDialog', testSurveyShownOncePerDialog);
    await step('testSubmitSendsOnceWithQuestionId', testSubmitSendsOnceWithQuestionId);
    await step('testThreeLayoutsSubmit', testThreeLayoutsSubmit);
    await step('testSuggestionHandling', testSuggestionHandling);
    await step('testSuggestionMissingHidesTextarea', testSuggestionMissingHidesTextarea);
    await step('testNoChoiceCannotSubmit', testNoChoiceCannotSubmit);
    await step('testSnoozeNeverDismissSemantics', testSnoozeNeverDismissSemantics);
    await step('testEscapeOverlayCloseSendNoDismissed', testEscapeOverlayCloseSendNoDismissed);
    await step('testSubmitFailureAndRetry', testSubmitFailureAndRetry);
    await step('testSubmitLockDuringInFlight', testSubmitLockDuringInFlight);
    await step('testDntRejectionFailsSubmit', testDntRejectionFailsSubmit);
    await step('testAppVersionUnknown', testAppVersionUnknown);
    await step('testBeforeSendFilter', testBeforeSendFilter);
    await step('testSdkInitConfigPrivacy', testSdkInitConfigPrivacy);
    await step('testPayloadPrivacy', testPayloadPrivacy);
    await step('testSuggestionNeverLogged', testSuggestionNeverLogged);
    await step('testSubmittedNeverSnoozedGatesAutoShow', testSubmittedNeverSnoozedGatesAutoShow);
    await step('testCorruptStateIsCleaned', testCorruptStateIsCleaned);
    await step('testCorruptStateRemoveThrowsStillSafe', testCorruptStateRemoveThrowsStillSafe);
    await step('testStorageThrowSafe', testStorageThrowSafe);
    await step('testCrossTabStorageSync', testCrossTabStorageSync);
    await step('testSdkLoadFailure', testSdkLoadFailure);
    await step('testSdkLoadSuccessThroughScript', testSdkLoadSuccessThroughScript);
    await step('testSdkLoadTimeout', testSdkLoadTimeout);
    await step('testFlagsTimeout', testFlagsTimeout);
    await step('testSurveyFetchTimeout', testSurveyFetchTimeout);
    await step('testDisabledConfigDoesNothing', testDisabledConfigDoesNothing);
    await step('testPartialConfigTreatedAsDisabled', testPartialConfigTreatedAsDisabled);
    await step('testAutoShowConditions', testAutoShowConditions);
    await step('testBlockedOverlayBoundedRetry', testBlockedOverlayBoundedRetry);
    await step('testSeenRecordingAndThreshold', testSeenRecordingAndThreshold);
    await step('testLanguageSwitchPreservesInput', testLanguageSwitchPreservesInput);
    await step('testReducedMotionAndA11yBasics', testReducedMotionAndA11yBasics);
    await step('testCurrentLayoutBadge', testCurrentLayoutBadge);
    await step('testCaptureResultAcceptanceMatrix', testCaptureResultAcceptanceMatrix);
    await step('testBeforeSendTopLevelFields', testBeforeSendTopLevelFields);
    await step('testSdkInitCapturesConfigForBeforeSend', testSdkInitCapturesConfigForBeforeSend);
    await step('testDntGateSilentSkip', testDntGateSilentSkip);
    await step('testIsCapturingFalseSilentSkip', testIsCapturingFalseSilentSkip);
    await step('testDntGateAutoFlowSilent', testDntGateAutoFlowSilent);
    await step('testDntGateNormalCapturingStillShows', testDntGateNormalCapturingStillShows);
    await step('testAutoShowWaitsForSecondLayout', testAutoShowWaitsForSecondLayout);
    await step('testAutoShowVisibilityReschedule', testAutoShowVisibilityReschedule);
    await step('testAutoShowOverlayRetryLimit', testAutoShowOverlayRetryLimit);
    await step('testAutoFlowStartsSurveyFlowOnce', testAutoFlowStartsSurveyFlowOnce);
    await step('testSyncFlagsCallbackRace', testSyncFlagsCallbackRace);
    await step('testSyncFlagsCallbackWithStalledSurveys', testSyncFlagsCallbackWithStalledSurveys);
    await step('testDestroyCancelsSurveyFetch', testDestroyCancelsSurveyFetch);
    await step('testCrossTabSubmittedClosesOtherTab', testCrossTabSubmittedClosesOtherTab);
    await step('testCrossTabNeverAndSnoozeClosesOtherTab', testCrossTabNeverAndSnoozeClosesOtherTab);
    await step('testFreshCheckPreventsDuplicateSubmit', testFreshCheckPreventsDuplicateSubmit);
    await step('testUnicodeLengthMatrix', testUnicodeLengthMatrix);
    await step('testDestroyDuringSdkLoad', testDestroyDuringSdkLoad);
    await step('testLateScriptLoadAfterDestroy', testLateScriptLoadAfterDestroy);
    await step('testDestroyAndReInit', testDestroyAndReInit);
    await step('testReuseLoadedSdk', testReuseLoadedSdk);
    await step('testConfigMismatchFailsClosed', testConfigMismatchFailsClosed);
    await step('testDestroyDuringAppVersionWait', testDestroyDuringAppVersionWait);
    await step('testDestroyDuringSurveyWait', testDestroyDuringSurveyWait);
    await step('testDestroyDuringSubmit', testDestroyDuringSubmit);
    await step('testOldGenerationCannotAffectNewGeneration', testOldGenerationCannotAffectNewGeneration);
    await step('testDestroyIdempotence', testDestroyIdempotence);
    await step('testOpenAfterDestroyIsNoop', testOpenAfterDestroyIsNoop);
    await step('testOpenBeforeInitIsNoop', testOpenBeforeInitIsNoop);
    await step('testOpenAfterDestroyDoesNotBlockReinit', testOpenAfterDestroyDoesNotBlockReinit);
    await step('testIsDateObjectNoThrow', testIsDateObjectNoThrow);
    await step('testBeforeSendTimestampMatrix', testBeforeSendTimestampMatrix);
    await step('testBeforeSendDateTimestampWithSurveyFields', testBeforeSendDateTimestampWithSurveyFields);
    await step('testFakeAdapterDefaultTimestampIsDate', testFakeAdapterDefaultTimestampIsDate);
    await step('testFakeAdapterTimestampOverrides', testFakeAdapterTimestampOverrides);
    await step('testSdkConfigHeatmapMigration', testSdkConfigHeatmapMigration);
    await step('testBootstrapIdentitySemantics', testBootstrapIdentitySemantics);
    await step('testBootstrapIdentitySemanticsLocalCache', testBootstrapIdentitySemanticsLocalCache);
    await step('testBootstrapIdentityMismatchFailsClosed', testBootstrapIdentityMismatchFailsClosed);
    await step('testBootstrapIdentityMismatchViaSurveyFlowNeverShown', testBootstrapIdentityMismatchViaSurveyFlowNeverShown);
    await step('testServerModeSubmittedStateGatesAutoShow', testServerModeSubmittedStateGatesAutoShow);
    await step('testServerModeSubmitPersistsToServer', testServerModeSubmitPersistsToServer);
    await step('testServerModeSnoozeAndNeverPersist', testServerModeSnoozeAndNeverPersist);
    await step('testServerModeSeenRecordsServerSide', testServerModeSeenRecordsServerSide);
    await step('testServerModeUnavailableFallsBackToLocal', testServerModeUnavailableFallsBackToLocal);
    await step('testServerGetUrlCarriesEncodedSurveyId', testServerGetUrlCarriesEncodedSurveyId);
    await step('testServerBackedStateAndSeenFromAuthoritativeSnapshot', testServerBackedStateAndSeenFromAuthoritativeSnapshot);
    await step('testServerModeSubmitPreflightBlocksOnFreshServerState', testServerModeSubmitPreflightBlocksOnFreshServerState);
    await step('testServerModeSubmitPreflightNeverAndSnooze', testServerModeSubmitPreflightNeverAndSnooze);
    await step('testServerModePreflightAllowsCaptureThenSendsSubmitted', testServerModePreflightAllowsCaptureThenSendsSubmitted);
    await step('testServerCommandConflictRetriesOnce', testServerCommandConflictRetriesOnce);
    await step('testServerCommandConflictDoesNotDowngradeSubmitted', testServerCommandConflictDoesNotDowngradeSubmitted);
    await step('testServerCommandNetworkFailureSafeDegrade', testServerCommandNetworkFailureSafeDegrade);
    await step('testServerCommandRecordSeenConflictRetries', testServerCommandRecordSeenConflictRetries);
    await step('testServerModeCrossTabCoordination', testServerModeCrossTabCoordination);
    await step('testLocalStateReconciliation', testLocalStateReconciliation);
    await step('testLocalStateReconciliationNeverOverSnoozed', testLocalStateReconciliationNeverOverSnoozed);
    await step('testLocalStateReconciliationNeverDowngrades', testLocalStateReconciliationNeverDowngrades);
    await step('testLocalStateReconciliationIgnoresInvalidOrOtherSurvey', testLocalStateReconciliationIgnoresInvalidOrOtherSurvey);
    await step('testDestroyDuringServerLoad', testDestroyDuringServerLoad);
    await step('testDestroyThenReinitReProbesServer', testDestroyThenReinitReProbesServer);
    await step('testDestroyDuringServerCommand', testDestroyDuringServerCommand);
    await step('testServerSeenDebounceTimerClearedOnDestroy', testServerSeenDebounceTimerClearedOnDestroy);
    await step('testDisabledConfigDoesNotProbeServer', testDisabledConfigDoesNotProbeServer);
    await step('testWaitForIdentityBeforeSdkInit', testWaitForIdentityBeforeSdkInit);
    await step('testOpenWaitsForServer403Fallback', testOpenWaitsForServer403Fallback);
    await step('testServerModePrivacyNoRawUuid', testServerModePrivacyNoRawUuid);
    await step('testServerModeReinitIdentityChangeFailsClosed', testServerModeReinitIdentityChangeFailsClosed);
    await step('testReconcileSubmittedReplaysAndWaits', testReconcileSubmittedReplaysAndWaits);
    await step('testReconcileSubmittedReplayNetworkFailure', testReconcileSubmittedReplayNetworkFailure);
    await step('testReconcileNeverReplayTimeout', testReconcileNeverReplayTimeout);
    await step('testReconcileSnoozedReplayFailure', testReconcileSnoozedReplayFailure);
    await step('testReconcileSeenReplayFailure', testReconcileSeenReplayFailure);
    await step('testReconcileSuccessSyncsAuthoritativeSnapshot', testReconcileSuccessSyncsAuthoritativeSnapshot);
    await step('testServerSubmittedWinsOverLocalSnoozed', testServerSubmittedWinsOverLocalSnoozed);
    await step('testReconcileDecisionThenSeenOrdering', testReconcileDecisionThenSeenOrdering);
    await step('testCommandTimeoutDestroyClearsTimers', testCommandTimeoutDestroyClearsTimers);
    await step('testStateGetsUseNoStoreCache', testStateGetsUseNoStoreCache);
    await step('testApplyServerSnapshotRejectsOtherSurveyState', testApplyServerSnapshotRejectsOtherSurveyState);
    await step('testApplyServerSnapshotRejectsInvalidShapes', testApplyServerSnapshotRejectsInvalidShapes);
    await step('testSnapshotRevisionMonotonic', testSnapshotRevisionMonotonic);
    await step('testSnapshotSameRevisionContent', testSnapshotSameRevisionContent);
    await step('testLateResponseAfterCommandTimeout', testLateResponseAfterCommandTimeout);
    await step('testLateResponseAfterRefreshTimeout', testLateResponseAfterRefreshTimeout);
    await step('testOutOfOrderCommandResponses', testOutOfOrderCommandResponses);
    await step('testCrossTabStateFallback', testCrossTabStateFallback);
    await step('testCrossTabSeenFallback', testCrossTabSeenFallback);
    await step('testSnoozeStrength', testSnoozeStrength);
    await step('testConcurrentCommandOperations', testConcurrentCommandOperations);
    await step('testDestroyCancelsInFlightCommands', testDestroyCancelsInFlightCommands);
    await step('testGetReconciliationTimeoutSeparation', testGetReconciliationTimeoutSeparation);
    await step('testReconciliationCommandTimeoutBounded', testReconciliationCommandTimeoutBounded);
    await step('testStorageWriteDedup', testStorageWriteDedup);
    await step('testRefreshResultContract', testRefreshResultContract);
    await step('testSubmitPreflightFailClosed', testSubmitPreflightFailClosed);
    await step('testSubmitPreflightUnavailableFailOpen', testSubmitPreflightUnavailableFailOpen);
    await step('testSubmitPreflightDestroyDuring', testSubmitPreflightDestroyDuring);
    await step('testSubmitPreflightStale', testSubmitPreflightStale);
    await step('testReconcileDecisionInFlightDestroyReinit', testReconcileDecisionInFlightDestroyReinit);
    await step('testReconcileSeenInFlightDestroyReinit', testReconcileSeenInFlightDestroyReinit);
    await step('testReconcileFinalSyncGuard', testReconcileFinalSyncGuard);
    await step('testWriteStateMonotonic', testWriteStateMonotonic);
    await step('testDecisionEntriesEqual', testDecisionEntriesEqual);
    await step('testWriteStateIdempotentAcrossTime', testWriteStateIdempotentAcrossTime);
    await step('testDismissedIdempotentAcrossTime', testDismissedIdempotentAcrossTime);
    await step('testServerTimeValidation', testServerTimeValidation);
    await step('testServerTimeSameRevisionDifferentValues', testServerTimeSameRevisionDifferentValues);
    await step('testServerClockSamplingGuards', testServerClockSamplingGuards);
    await step('testClockSkewSnoozeConfirmation', testClockSkewSnoozeConfirmation);
    await step('testServerSnoozeValidityByServerClock', testServerSnoozeValidityByServerClock);
    await step('testSeenCrossClockSemantics', testSeenCrossClockSemantics);
    console.log(`\npixiv-layout-feedback.test.js: ${passed} assertions passed ✓`);
}

let currentTest = '';

run().catch(error => {
    console.error(error && error.stack ? error.stack : error);
    process.exitCode = 1;
});
