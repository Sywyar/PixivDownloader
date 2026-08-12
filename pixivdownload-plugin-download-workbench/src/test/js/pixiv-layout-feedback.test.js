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
const POSTHOG_SOURCE_PATH = path.join(__dirname, '..', '..', '..', '..',
    'pixivdownload-plugin-posthog', 'src', 'main', 'resources', 'static',
    'pixiv-posthog', 'pixiv-posthog.js');
const SOURCE = fs.readFileSync(SOURCE_PATH, 'utf8');
const CSS = fs.readFileSync(CSS_PATH, 'utf8');
const POSTHOG_SOURCE = fs.readFileSync(POSTHOG_SOURCE_PATH, 'utf8');

const LAYOUT_IDS = ['pixiv-batch-landscape', 'pixiv-batch-portrait', 'pixiv-batch-alt'];
const STATE_KEY = 'pixiv:layout-feedback:state:v1';
const SEEN_KEY = 'pixiv:layout-feedback:seen:v1';
const SNOOZE_MS = 7 * 24 * 60 * 60 * 1000;
const SUGGESTION_MAX = 1000;
const SURVEY_ID = '019fce31-c9ce-0000-934a-375b3ddbbd6c';

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

function createFakeTimers(initialWall) {
    // 客户端墙钟（Date.now() 风格）与客户端单调钟（performance.now() 风格）独立：
    // 测试可用 setWallNow 模拟用户改系统时间导致墙钟突然前跳 / 后跳，单调钟不受影响
    //（advance 同时推进两个时钟；RTT / 请求中点估算只使用单调钟）。
    let wall = initialWall === undefined ? 1000000 : initialWall;
    let mono = 100;
    let nextId = 1;
    const queue = [];
    return {
        now: () => wall,
        wallNow: () => wall,
        monotonicNow: () => mono,
        setWallNow(value) {
            wall = value;
        },
        setMonotonicNow(value) {
            mono = value;
        },
        setTimeout(fn, ms) {
            const id = nextId++;
            queue.push({id, at: wall + Math.max(0, Number(ms) || 0), fn});
            return id;
        },
        clearTimeout(id) {
            const index = queue.findIndex(t => t.id === id);
            if (index >= 0) queue.splice(index, 1);
        },
        advance(ms) {
            const target = wall + ms;
            let guard = 0;
            // 按到期顺序逐个触发，触发前把墙钟推进到该定时器的到期时刻（单调钟同步
            // 推进同样时长），使回调内新注册的定时器按真实语义（当前时间 + 延迟）
            // 计算到期点。
            while (true) {
                const next = queue.filter(t => t.at <= target).sort((a, b) => a.at - b.at)[0];
                if (!next) break;
                const index = queue.indexOf(next);
                queue.splice(index, 1);
                mono += next.at - wall;
                wall = next.at;
                next.fn();
                if (++guard > 1000) throw new Error('fake timer runaway');
            }
            mono += target - wall;
            wall = target;
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
        init(token, config, name) {
            calls.init.push({token, config, name});
            sdkConfig = config;
            // 真实 1.409.5：bootstrap.distinctID（isIdentifiedID=false 时走匿名
            // register 分支）设置 SDK distinct ID；sdkConfig.distinct_id 不参与初始化。
            sdkDistinctId = null;
            if (config && config.bootstrap
                    && typeof config.bootstrap.distinctID === 'string') {
                sdkDistinctId = config.bootstrap.distinctID;
            }
            return adapter;
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
        AbortController,
        URL,
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
    if (options.posthogAvailable !== false) {
        vm.runInContext(POSTHOG_SOURCE, sandbox, {filename: 'pixiv-posthog.js'});
    }
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
        fetchImpl: harness.sandbox.fetch.bind(harness.sandbox)
    });
    harness.adapter = adapter;
    return harness;
}

function defaultSurvey() {
    return {
        id: SURVEY_ID,
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
        page: 'alt',
        adapter: adapter === undefined ? h.adapter : adapter,
        i18n: createFakeI18n({}),
        storage: h.storage,
        timers: h.timers,
        fetchImpl: h.sandbox.fetch.bind(h.sandbox)
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

function testSubmittedNeverSnoozedGatesTrigger() {
    const surveyId = SURVEY_ID;
    const state = (status, snoozedUntil) => JSON.stringify({
        surveyId, status, updatedAt: 100, snoozedUntil: snoozedUntil || 0
    });
    return Promise.resolve().then(() => {
        const h = initHarness({page: 'alt', storage: Object.assign(seenSeed(), {[STATE_KEY]: state('submitted')})});
        h.dispatchFirstDownload();
        return waitForFlush().then(() => {
            eq('submitted 后首次下载完成不展示', h.document.querySelectorAll('.plf-backdrop').length, 0);
        });
    }).then(() => {
        const h = initHarness({page: 'alt', storage: Object.assign(seenSeed(), {[STATE_KEY]: state('never')})});
        h.dispatchFirstDownload();
        return waitForFlush().then(() => {
            eq('never 后首次下载完成不展示', h.document.querySelectorAll('.plf-backdrop').length, 0);
        });
    }).then(() => {
        const h = initHarness({page: 'alt', storage: Object.assign(seenSeed(), {[STATE_KEY]: state('snoozed', 2000000)})});
        h.dispatchFirstDownload();
        return waitForFlush().then(() => {
            eq('snoozed 未到期不展示', h.document.querySelectorAll('.plf-backdrop').length, 0);
        });
    }).then(() => {
        const h = initHarness({page: 'alt', storage: Object.assign(seenSeed(), {[STATE_KEY]: state('snoozed', 1000000)})});
        h.dispatchFirstDownload();
        return waitForFlush().then(() => {
            eq('snoozed 到期后展示', h.document.querySelectorAll('.plf-backdrop').length, 1);
        });
    }).then(() => {
        const h = initHarness({page: 'alt', storage: Object.assign(seenSeed(), {[STATE_KEY]: JSON.stringify({
            surveyId: 'other-survey-id-000', status: 'never', updatedAt: 100, snoozedUntil: 0
        })}), initialWall: 1000000});
        h.dispatchFirstDownload();
        return waitForFlush().then(() => {
            eq('Survey ID 变化后旧状态不拦截', h.document.querySelectorAll('.plf-backdrop').length, 1);
        });
    });
}

function testCorruptStateIsCleaned() {
    const h = initHarness({
        page: 'alt',
        storage: {[STATE_KEY]: '{not json', [SEEN_KEY]: '[[['}
    });
    const removeCallsBefore = h.storage.removeCalls.length;
    h.dispatchFirstDownload();
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
        page: 'alt',
        storage: {[STATE_KEY]: '{not json', [SEEN_KEY]: '[[['},
        throwOnRemove: true
    });
    h.dispatchFirstDownload();
    return waitForFlush().then(() => {
        eq('removeItem 抛错时损坏状态仍安全降级', h.document.querySelectorAll('.plf-backdrop').length, 1);
        ok('removeItem 抛错时仍尝试清理', h.storage.removeCalls
            .some(k => k === STATE_KEY || k === SEEN_KEY));
    });
}

function testStorageThrowSafe() {
    return Promise.resolve().then(() => {
        const h = initHarness({page: 'alt', throwOnGet: true});
        h.dispatchFirstDownload();
        return waitForFlush().then(() => {
            eq('getItem 抛错不影响调查展示', h.document.querySelectorAll('.plf-backdrop').length, 1);
        });
    }).then(() => {
        const h = initHarness({batchLayout: 'landscape', throwOnSet: true});
        return h.api.open().then(() => waitForFlush()).then(() => {
            selectChoice(h, 'pixiv-batch-landscape');
            h.actionButton('snooze').click();
            eq('setItem 抛错仍关闭弹窗', h.document.querySelectorAll('.plf-backdrop').length, 0);
        });
    }).then(() => {
        const h = initHarness({batchLayout: 'landscape', throwOnRemove: true});
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
    // 另一标签页写入 submitted 后，本标签页收到 storage 事件并同步状态；
    // 首次下载完成触发被阻断状态拦截，不展示。
    const h2 = initHarness({
        page: 'alt',
        storage: seenSeed()
    });
    const submitted = JSON.stringify({
        surveyId: SURVEY_ID, status: 'submitted',
        updatedAt: 999, snoozedUntil: 0
    });
    h2.storage.values.set(STATE_KEY, submitted);
    h2.dispatchStorage(STATE_KEY, submitted);
    h2.dispatchFirstDownload();
    return waitForFlush().then(() => {
        eq('storage 事件同步后不展示', h2.document.querySelectorAll('.plf-backdrop').length, 0);
    });
}

/* ============================================================
   SDK / flags / survey 超时（35-37, 58-60）
============================================================ */

function testSdkLoadFailure() {
    const h = initHarness({page: 'alt', adapter: null});
    const promise = h.api.open();
    return waitForFlush().then(() => {
        // 服务端上下文 resolve 后脚本才插入；脚本加载失败静默结束
        eq('SDK 脚本已插入', h.scriptElements().length, 1);
        h.fireScriptError();
        return promise.then(() => waitForFlush());
    }).then(() => {
        eq('SDK 加载失败静默结束', h.document.querySelectorAll('.plf-backdrop').length, 0);
        h.dispatchFirstDownload();
        return waitForFlush();
    }).then(() => {
        eq('SDK 加载失败后不重新插入脚本', h.scriptElements().length, 1);
        eq('SDK 加载失败后触发不再动作', h.document.querySelectorAll('.plf-backdrop').length, 0);
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

/* ============================================================
   预加载（点击「开始下载」后预热 SDK，弹窗无空白等待）
============================================================ */

function testPreloadWarmsSdkBeforeFirstDownload() {
    const h = initHarness({page: 'alt'});
    const preloadPromise = h.api.preload();
    return Promise.all([preloadPromise, waitForFlush()]).then(() => {
        eq('预加载已完成 SDK 初始化', h.adapter.calls.init.length, 1);
        eq('预加载不弹窗', h.document.querySelectorAll('.plf-backdrop').length, 0);
        h.dispatchFirstDownload();
        return waitForFlush();
    }).then(() => {
        eq('首次下载完成事件到达后直接展示弹窗', h.document.querySelectorAll('.plf-backdrop').length, 1);
        eq('展示流程复用预加载的 SDK 不重复初始化', h.adapter.calls.init.length, 1);
    });
}

function testPreloadLoadsSdkScriptEarly() {
    const h = initHarness({page: 'alt', adapter: null});
    const preloadPromise = h.api.preload();
    return waitForFlush().then(() => {
        eq('预加载即插入 SDK 脚本', h.scriptElements().length, 1);
        h.sandbox.posthog = createFakeAdapter({surveys: [defaultSurvey()]});
        h.fireScriptLoad();
        return Promise.all([preloadPromise, waitForFlush()]);
    }).then(() => {
        eq('预加载完成不弹窗', h.document.querySelectorAll('.plf-backdrop').length, 0);
        h.dispatchFirstDownload();
        return waitForFlush();
    }).then(() => {
        eq('脚本已就绪时事件到达即展示', h.document.querySelectorAll('.plf-backdrop').length, 1);
    });
}

function testPreloadSkipsSdkInitWhenStateBlocks() {
    const state = JSON.stringify({
        surveyId: SURVEY_ID,
        status: 'submitted', updatedAt: 100, snoozedUntil: 0
    });
    const h = initHarness({page: 'alt', storage: Object.assign(seenSeed(), {[STATE_KEY]: state})});
    const preloadPromise = h.api.preload();
    return Promise.all([preloadPromise, waitForFlush()]).then(() => {
        eq('状态阻断时预加载不初始化 SDK', h.adapter.calls.init.length, 0);
        h.dispatchFirstDownload();
        return waitForFlush();
    }).then(() => {
        eq('状态阻断时事件到达不展示', h.document.querySelectorAll('.plf-backdrop').length, 0);
    });
}

function testPreloadBeforeInitIsNoop() {
    const h = initHarness({page: 'alt'});
    h.api.destroy();
    return h.api.preload().then(() => waitForFlush()).then(() => {
        eq('destroy 后 preload 是安全 no-op', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('destroy 后 preload 不加载脚本', h.scriptElements().length, 0);
    });
}

function testDisabledConfigDoesNothing() {
    const h = initHarness({officialRelease: false});
    return h.api.open().then(() => waitForFlush()).then(() => {
        eq('非官方发行不展示调查', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('非官方发行不加载 SDK', h.scriptElements().length, 0);
        h.dispatchFirstDownload();
        return waitForFlush();
    }).then(() => {
        eq('非官方发行触发不动作', h.document.querySelectorAll('.plf-backdrop').length, 0);
    });
}

function testMissingPostHogPluginTreatedAsDisabled() {
    const h = initHarness({posthogAvailable: false});
    h.dispatchFirstDownload();
    return waitForFlush().then(() => {
        eq('PostHog 插件缺失时静默关闭', h.document.querySelectorAll('.plf-backdrop').length, 0);
    });
}

function testFirstDownloadTriggerConditions() {
    return Promise.resolve().then(() => {
        const h = initHarness({page: 'alt', batchLayout: 'landscape'});
        h.dispatchFirstDownload();
        return waitForFlush().then(() => {
            eq('alt 页首次下载完成即展示（无布局体验阈值）', h.document.querySelectorAll('.plf-backdrop').length, 1);
            const shown = captureEvents(h).filter(e => e === 'survey shown').length;
            eq('展示发送一次 shown', shown, 1);
        });
    }).then(() => {
        const h = initHarness({page: 'alt', throwOnGet: true});
        h.dispatchFirstDownload();
        return waitForFlush().then(() => {
            eq('getItem 抛错不影响调查展示', h.document.querySelectorAll('.plf-backdrop').length, 1);
        });
    }).then(() => {
        const h = initHarness({page: 'batch', batchLayout: 'landscape'});
        h.dispatchFirstDownload();
        return waitForFlush().then(() => {
            eq('batch 页忽略首次下载完成事件（不参与调查）', h.document.querySelectorAll('.plf-backdrop').length, 0);
        });
    });
}

function testTriggerBlockedOverlaySkipsThenAllows() {
    const h = initHarness({page: 'alt'});
    h.body.classList.add('pixiv-feedback-open');
    h.dispatchFirstDownload();
    return waitForFlush().then(() => {
        eq('有其它弹窗时暂缓且不加载 SDK', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('阻塞时未消耗触发机会（不请求 Survey）', h.adapter.calls.getSurveys.length, 0);
        h.body.classList.remove('pixiv-feedback-open');
        h.dispatchFirstDownload();
        return waitForFlush();
    }).then(() => {
        eq('阻塞解除后再次触发可展示', h.document.querySelectorAll('.plf-backdrop').length, 1);
    });
}

/* ============================================================
   布局体验记录（55-56）
============================================================ */

function testSeenRecording() {
    return Promise.resolve().then(() => {
        const h = initHarness({page: 'batch', storage: {}});
        h.dispatchLayoutChanged('portrait', 'landscape');
        h.dispatchLayoutChanged('landscape', 'portrait');
        const seen = JSON.parse(h.storage.getItem(SEEN_KEY));
        ok('layout changed 更新 seen', seen['pixiv-batch-portrait'] && seen['pixiv-batch-portrait'].lastSeenAt > 0);
        ok('seen 记录两个布局', h.api._internals.distinctSeenCount(seen) === 2);
    }).then(() => {
        const h = initHarness({page: 'batch', storage: {}, batchLayout: 'portrait'});
        h.dispatchFirstDownload();
        return waitForFlush().then(() => {
            eq('batch 页触发事件不展示（无需体验阈值语义）', h.document.querySelectorAll('.plf-backdrop').length, 0);
        });
    }).then(() => {
        const h = initHarness({page: 'alt', storage: {}});
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

function testDntGateTriggerSilent() {
    const h = initHarness({
        page: 'alt',
        adapter: createFakeAdapter({surveys: [defaultSurvey()], optedOut: true})
    });
    h.dispatchFirstDownload();
    return waitForFlush().then(() => {
        eq('DNT opt-out 触发不显示', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('DNT opt-out 触发不发 shown', h.adapter.calls.capture.length, 0);
        eq('DNT opt-out 触发不写状态', h.storage.getItem(STATE_KEY) === null, true);
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
   首次下载完成触发（一次性）
============================================================ */

function testFirstDownloadTriggersOnce() {
    const h = initHarness({page: 'alt'});
    h.dispatchFirstDownload();
    return waitForFlush().then(() => {
        eq('首次下载完成触发启动一次 Survey 流程', h.adapter.calls.getSurveys.length, 1);
        h.dispatchFirstDownload();
        return waitForFlush();
    }).then(() => {
        eq('重复事件不启动第二个 Survey 流程', h.adapter.calls.getSurveys.length, 1);
        eq('重复事件不重复 shown', h.adapter.calls.capture
            .filter(c => c.name === 'survey shown').length, 1);
        eq('弹窗最多一个', h.document.querySelectorAll('.plf-backdrop').length, 1);
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
        surveyId: SURVEY_ID,
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
            eq('共享 SDK script 不随单个消费者销毁', scripts[0].parentNode !== null, true);
            eq('共享 loader 保留 load listener', (scripts[0].listeners.get('load') || []).length, 1);
            eq('共享 loader 保留 error listener', (scripts[0].listeners.get('error') || []).length, 1);
            eq('destroy 后不显示弹窗', h.document.querySelectorAll('.plf-backdrop').length, 0);
            eq('destroy 后不写反馈状态', h.storage.getItem(STATE_KEY) === null, true);
            eq('destroy 后无 Toast', h.toastCalls.length, 0);
            h.timers.advance(30000);
            return waitForFlush();
        });
    }).then(() => {
        eq('SDK 加载取消后无任何后续动作', h.document.querySelectorAll('.plf-backdrop').length, 0);
        const scripts = h.scriptElements();
        eq('共享 loader 超时后移除 script', scripts[0].parentNode === null, true);
        eq('共享 loader 超时后移除 load listener', (scripts[0].listeners.get('load') || []).length, 0);
        eq('共享 loader 超时后移除 error listener', (scripts[0].listeners.get('error') || []).length, 0);
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
        eq('首次下载完成监听不重复', listenerCountFor(h, 'pixiv:first-download-completed'), 1);
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

function testIdentityMismatchFailsClosed() {
    const h = initHarness({batchLayout: 'landscape'});
    let surveysBefore = 0;
    let captureBefore = 0;
    return h.api.open().then(() => waitForFlush()).then(() => {
        eq('配置 A 正常展示', h.document.querySelectorAll('.plf-backdrop').length, 1);
        surveysBefore = h.adapter.calls.getSurveys.length;
        captureBefore = h.adapter.calls.capture.length;
        h.api.destroy();
        h.setServerState(serverStateResponse({distinctId: 'plf_' + 'b'.repeat(64)}));
        h.api.init(reinitOptions(h));
        return h.api.open().then(() => waitForFlush());
    }).then(() => {
        eq('身份变化 fail closed：不展示', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('身份变化不请求 Survey', h.adapter.calls.getSurveys.length, surveysBefore);
        eq('身份变化不发送事件', h.adapter.calls.capture.length, captureBefore);
        const warnings = JSON.stringify(h.consoleWarn);
        ok('记录安全 warning', warnings.indexOf('different configuration') >= 0);
        ok('warning 不含调查身份实际值', warnings.indexOf('plf_' + 'b'.repeat(64)) < 0);
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
        eq('首次下载完成监听唯一', listenerCountFor(h, 'pixiv:first-download-completed'), 1);
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
    eq('destroy 后无首次下载完成监听', listenerCountFor(h, 'pixiv:first-download-completed'), 0);
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
    ok('PostHog 插件源码不再包含 enable_heatmaps 配置字段', POSTHOG_SOURCE.indexOf('enable_heatmaps') < 0);
    ok('PostHog 插件源码使用 capture_heatmaps', POSTHOG_SOURCE.indexOf('capture_heatmaps') >= 0);
}

/* ============================================================
   solo 服务端模式（/api/layout-feedback/state）
============================================================ */

const SERVER_SCOPED_ID = 'plf_' + 'ab'.repeat(32);
const SERVER_RAW_UUID = '11111111-2222-4333-8444-555555555555';

function serverStateResponse(overrides) {
    return Object.assign({
        available: true,
        stateAvailable: true,
        distinctId: SERVER_SCOPED_ID,
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

function testBootstrapIdentitySemantics() {
    // 服务端 seenLayouts 为空：本页首次体验当前布局需要以 record_seen 命令提交；
    // 首次下载完成触发仍正常启动展示流程（无历史体验限制）。
    const h = initHarness({
        page: 'alt',
        serverState: serverStateResponse({seenLayouts: []})
    });
    return waitForServerContext(h).then(() => {
        h.dispatchFirstDownload();
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
            ok('record_seen 携带 surveyId 且不含 expectedRevision / 客户端时间',
                p.body.surveyId === h.config.surveyId
                && p.body.expectedRevision === undefined
                && p.body.snoozedUntil === undefined
                && p.body.updatedAt === undefined);
        });
        const localStateRaw = h.storage.getItem(STATE_KEY);
        ok('server 无 state 时协调缓存不残留 STATE_KEY',
            localStateRaw === null || JSON.parse(localStateRaw).surveyId !== h.config.surveyId);
    });
}

function testBootstrapIdentitySemanticsLocalCache() {
    const h = initHarness({
        batchLayout: 'landscape',
        serverState: serverStateResponse({seenLayouts: LAYOUT_IDS.slice()})
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
        page: 'alt',
        adapter: createFakeAdapter({surveys: [defaultSurvey()], distinctId: 'some-other-id'}),
        serverState: serverStateResponse({seenLayouts: LAYOUT_IDS.slice()})
    });
    return waitForServerContext(h).then(() => {
        h.dispatchFirstDownload();
        return waitForFlush();
    }).then(() => {
        eq('get_distinct_id 不一致 fail closed：不展示', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('不一致不请求 Survey', h.adapter.calls.getSurveys.length, 0);
        eq('不一致不发送事件', h.adapter.calls.capture.length, 0);
        const warnings = JSON.stringify(h.consoleWarn);
        ok('记录安全 warning', warnings.indexOf('does not match') >= 0);
        ok('warning 不含 scoped ID / token / survey', warnings.indexOf(SERVER_SCOPED_ID) < 0
            && warnings.indexOf(h.config.projectToken) < 0
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

function testServerModeSubmittedStateGatesTrigger() {
    const h = initHarness({
        page: 'alt',
        serverState: serverStateResponse({
            status: 'submitted',
            canShow: false,
            retryAfterMs: 0,
            seenLayouts: LAYOUT_IDS.slice()
        })
    });
    // 先让服务端状态装载完成再派发首次下载完成事件
    return waitForServerContext(h).then(() => {
        h.dispatchFirstDownload();
        return waitForFlush();
    }).then(() => {
        eq('服务端 submitted 不再展示', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('不发送 shown', captureEvents(h).filter(e => e === 'survey shown').length, 0);
        eq('不初始化 SDK', h.adapter.sdkConfig() === null, true);
    });
}

function testServerModeSubmitPersistsToServer() {
    const h = initHarness({
        page: 'alt',
        serverState: serverStateResponse({seenLayouts: LAYOUT_IDS.slice()})
    });
    return waitForServerContext(h).then(() => {
        h.dispatchFirstDownload();
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
        eq('submitted 命令不携带建议 / 布局回答 / 完整状态 / expectedRevision / 时间戳',
            post.body.suggestion === undefined
            && post.body.selectedChoice === undefined
            && post.body.state === undefined && post.body.seen === undefined
            && post.body.expectedRevision === undefined
            && post.body.updatedAt === undefined, true);
    });
}

function testServerModeSnoozeAndNeverPersist() {
    return Promise.resolve().then(() => {
        const h = initHarness({
            page: 'alt',
            serverState: serverStateResponse({seenLayouts: LAYOUT_IDS.slice()})
        });
        return waitForServerContext(h).then(() => {
            h.dispatchFirstDownload();
            return waitForFlush();
        }).then(() => {
            h.actionButton('snooze').click();
            return waitForFlush();
        }).then(() => {
            const post = h.serverPosts.find(p => p.body.command === 'snooze');
            ok('稍后再说以 snooze 命令持久化', !!post);
            eq('snooze 不携带客户端时间戳 / expectedRevision', post.body.snoozedUntil === undefined
                && post.body.updatedAt === undefined
                && post.body.retryAfterMs === undefined
                && post.body.expectedRevision === undefined, true);
            const localState = JSON.parse(h.storage.getItem(STATE_KEY));
            eq('本地协调缓存写 snoozed', localState.status, 'snoozed');
        });
    }).then(() => {
        const h = initHarness({
            page: 'alt',
            serverState: serverStateResponse({seenLayouts: LAYOUT_IDS.slice()})
        });
        return waitForServerContext(h).then(() => {
            h.dispatchFirstDownload();
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
        serverState: serverStateResponse({})
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
        ok('record_seen 不携带完整 seen / state / expectedRevision',
            posts.every(p => !p.body.seen && !p.body.state && p.body.expectedRevision === undefined));
        ok('serverBacked 仍写 SEEN_KEY 本地协调缓存',
            h.storage.getItem(SEEN_KEY) !== null);
    });
}

function testServerModeUnavailableFallsBackToLocal() {
    return Promise.resolve().then(() => {
        const h = initHarness({
            page: 'alt',
            serverFetch: '403'
        });
        h.dispatchFirstDownload();
        return waitForFlush().then(() => {
            eq('403（multi 模式）回退 localStorage 展示', h.document.querySelectorAll('.plf-backdrop').length, 1);
            const cfg = h.adapter.sdkConfig();
            eq('回退模式 sdk config 不包含 bootstrap', cfg.bootstrap === undefined, true);
            eq('回退模式不包含 distinct_id 初始化字段', cfg.distinct_id, undefined);
        });
    }).then(() => {
        const h = initHarness({
            page: 'alt',
            serverFetch: 'fail'
        });
        h.dispatchFirstDownload();
        return waitForFlush().then(() => {
            eq('服务端不可达回退 localStorage 展示', h.document.querySelectorAll('.plf-backdrop').length, 1);
            eq('服务端不可达不设置 bootstrap', h.adapter.sdkConfig().bootstrap === undefined, true);
        });
    }).then(() => {
        const h = initHarness({
            page: 'alt',
            serverFetch: 'pending'
        });
        h.dispatchFirstDownload();
        // 服务端 GET 超时（SERVER_STATE_TIMEOUT_MS=3s）后回退 local 模式，流程继续。
        h.timers.advance(3000);
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
        ok('GET 携带 surveyId 查询参数', url.url.indexOf('surveyId=' + encodeURIComponent(SURVEY_ID)) >= 0);
        ok('GET 使用 same-origin credentials', url.init.credentials === 'same-origin');
    });
}

function testServerBackedStateAndSeenFromAuthoritativeSnapshot() {
    const h = initHarness({
        page: 'alt',
        serverState: serverStateResponse({
            status: 'snoozed',
            canShow: false,
            retryAfterMs: 20 * 60 * 1000,
            seenLayouts: LAYOUT_IDS.slice()
        })
    });
    return waitForServerContext(h).then(() => {
        h.dispatchFirstDownload();
        return waitForFlush();
    }).then(() => {
        eq('权威视图 snooze 生效：有效 snooze 不展示', h.document.querySelectorAll('.plf-backdrop').length, 0);
        const localState = JSON.parse(h.storage.getItem(STATE_KEY));
        eq('权威视图同步到本地协调缓存', localState.status, 'snoozed');
        eq('本地截止时间 = clientNow + retryAfterMs', localState.snoozedUntil,
            1000000 + 20 * 60 * 1000);
        const localSeen = JSON.parse(h.storage.getItem(SEEN_KEY));
        ok('权威 seenLayouts 同步到本地协调缓存',
            localSeen && localSeen['pixiv-batch-alt']
                && typeof localSeen['pixiv-batch-alt'].lastSeenAt === 'number'
                && localSeen['pixiv-batch-alt'].lastSeenAt > 0);
    });
}

function testServerModeSubmitPreflightBlocksOnFreshServerState() {
    // 弹窗打开后另一设备把服务端写成 submitted：提交前 preflight GET 必须发现并取消。
    const h = initHarness({
        page: 'alt',
        serverState: serverStateResponse({seenLayouts: LAYOUT_IDS.slice()})
    });
    return waitForServerContext(h).then(() => {
        h.dispatchFirstDownload();
        return waitForFlush();
    }).then(() => {
        selectChoice(h, 'pixiv-batch-landscape');
        h.setServerState(serverStateResponse({
            revision: 2,
            status: 'submitted',
            canShow: false,
            retryAfterMs: 0,
            seenLayouts: LAYOUT_IDS.slice()
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
            page: 'alt',
            serverState: serverStateResponse({seenLayouts: LAYOUT_IDS.slice()})
        });
        return waitForServerContext(h).then(() => {
            h.dispatchFirstDownload();
            return waitForFlush();
        }).then(() => {
            selectChoice(h, 'pixiv-batch-portrait');
            h.setServerState(serverStateResponse({
                revision: 2,
                status: 'never',
                canShow: false,
                retryAfterMs: 0,
                seenLayouts: LAYOUT_IDS.slice()
            }));
            h.submitButton().click();
            return waitForFlush();
        }).then(() => {
            eq('preflight 发现 never：不发送 survey sent', captureEvents(h).filter(e => e === 'survey sent').length, 0);
            eq('preflight never 拦截后关闭弹窗', h.document.querySelectorAll('.plf-backdrop').length, 0);
        });
    }).then(() => {
        const h = initHarness({
            page: 'alt',
            serverState: serverStateResponse({seenLayouts: LAYOUT_IDS.slice()})
        });
        return waitForServerContext(h).then(() => {
            h.dispatchFirstDownload();
            return waitForFlush();
        }).then(() => {
            selectChoice(h, 'pixiv-batch-alt');
            h.setServerState(serverStateResponse({
                revision: 2,
                status: 'snoozed',
                canShow: false,
                retryAfterMs: 20 * 60 * 1000,
                seenLayouts: LAYOUT_IDS.slice()
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
        page: 'alt',
        serverState: serverStateResponse({seenLayouts: LAYOUT_IDS.slice()})
    });
    return waitForServerContext(h).then(() => {
        h.dispatchFirstDownload();
        return waitForFlush();
    }).then(() => {
        selectChoice(h, 'pixiv-batch-landscape');
        h.submitButton().click();
        return waitForFlush();
    }).then(() => {
        eq('preflight 允许后正常 capture', captureEvents(h).filter(e => e === 'survey sent').length, 1);
        const posts = h.serverPosts.filter(p => p.body.command === 'submitted');
        eq('capture 接受后发送 submitted 命令', posts.length, 1);
        eq('submitted 命令不含 expectedRevision', posts[0].body.expectedRevision, undefined);
    });
}

function testServerCommandNetworkFailureSafeDegrade() {
    const h = initHarness({
        page: 'alt',
        serverState: serverStateResponse({seenLayouts: LAYOUT_IDS.slice()}),
        serverPostResponse: 'fail'
    });
    return waitForServerContext(h).then(() => {
        h.dispatchFirstDownload();
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
                && text.indexOf(h.config.projectToken) < 0
                && text.indexOf(h.config.surveyId) < 0;
        }));
    });
}

function testServerModeCrossTabCoordination() {
    // 标签页 A（serverBacked）提交 → 标签页 B 收到 storage 事件即时关闭弹窗，
    // 并触发一次有限服务端刷新；storage 消息不直接伪造 serverRevision。
    return Promise.resolve().then(() => {
        const hA = initHarness({
            page: 'alt',
            serverState: serverStateResponse({seenLayouts: LAYOUT_IDS.slice()})
        });
        return waitForServerContext(hA).then(() => {
            hA.dispatchFirstDownload();
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
                serverState: serverStateResponse({seenLayouts: LAYOUT_IDS.slice()})
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
            serverState: serverStateResponse({seenLayouts: LAYOUT_IDS.slice()})
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
        surveyId: SURVEY_ID,
        status: 'submitted', updatedAt: 100, snoozedUntil: 0
    });
    const h = initHarness({
        batchLayout: 'landscape',
        storage: {[STATE_KEY]: submitted, [SEEN_KEY]: JSON.stringify(seenObject())},
        serverState: serverStateResponse({seenLayouts: []})
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
        surveyId: SURVEY_ID,
        status: 'never', updatedAt: 100, snoozedUntil: 0
    });
    const h = initHarness({
        batchLayout: 'landscape',
        storage: {[STATE_KEY]: localNever},
        serverState: serverStateResponse({
            status: 'snoozed',
            canShow: false,
            retryAfterMs: 20 * 60 * 1000,
            seenLayouts: []
        })
    });
    return waitForServerContext(h).then(() => {
        const posts = h.serverPosts.filter(p => p.body.command === 'never');
        eq('本地 never + 服务端 snoozed → 回放 never 升级', posts.length, 1);
    });
}

function testLocalStateReconciliationNeverDowngrades() {
    const localSnoozed = JSON.stringify({
        surveyId: SURVEY_ID,
        status: 'snoozed', updatedAt: 100, snoozedUntil: 2000000
    });
    const h = initHarness({
        batchLayout: 'landscape',
        storage: {[STATE_KEY]: localSnoozed},
        serverState: serverStateResponse({
            status: 'submitted',
            canShow: false,
            retryAfterMs: 0,
            seenLayouts: []
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
                surveyId: SURVEY_ID,
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
            serverStateResponse({seenLayouts: LAYOUT_IDS.slice()}))});
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
        ok('re-init 重新探测服务端（不复用旧视图）', h.stateFetchCount() > 1);
        h.api.destroy();
        h.timers.advance(30000);
        return waitForFlush();
    }).then(() => {
        eq('destroy 后无定时器残留', h.timers.pending().length, 0);
    });
}

function testDestroyDuringServerCommand() {
    const h = initHarness({
        page: 'alt',
        serverState: serverStateResponse({seenLayouts: LAYOUT_IDS.slice()}),
        serverPostResponse: 'pending'
    });
    return waitForServerContext(h).then(() => {
        h.dispatchFirstDownload();
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
        posthogAvailable: false,
        batchLayout: 'landscape'
    });
    return h.api.open().then(() => waitForFlush()).then(() => {
        eq('enabled=false 不展示调查', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('enabled=false 不加载 SDK', h.scriptElements().length, 0);
        eq('enabled=false 不请求 server state', h.fetchCalls.filter(c =>
            c.url.indexOf('/api/layout-feedback/state') >= 0).length, 0);
        h.dispatchFirstDownload();
        return waitForFlush();
    }).then(() => {
        eq('enabled=false 触发不动作', h.document.querySelectorAll('.plf-backdrop').length, 0);
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
            serverStateResponse({seenLayouts: LAYOUT_IDS.slice()}))});
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
        page: 'alt',
        serverState: serverStateResponse({seenLayouts: LAYOUT_IDS.slice()})
    });
    return waitForServerContext(h).then(() => {
        h.dispatchFirstDownload();
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
        page: 'alt',
        serverState: serverStateResponse({seenLayouts: LAYOUT_IDS.slice()})
    });
    return waitForServerContext(h).then(() => {
        h.dispatchFirstDownload();
        return waitForFlush();
    }).then(() => {
        eq('第一代正常展示', h.document.querySelectorAll('.plf-backdrop').length, 1);
        const surveysBefore = h.adapter.calls.getSurveys.length;
        h.api.destroy();
        // 身份变化（同一页面重新 init 后服务器下发另一个 scoped ID）
        h.setServerState(serverStateResponse({
            distinctId: 'plf_' + 'cd'.repeat(32),
            seenLayouts: LAYOUT_IDS.slice()
        }));
        h.api.init(reinitOptions(h));
        return h.api.open().then(() => waitForFlush());
    }).then(() => {
        eq('身份变化不静默复用旧 singleton（fail closed 不展示）',
            h.document.querySelectorAll('.plf-backdrop').length, 0);
        const warnings = JSON.stringify(h.consoleWarn);
        ok('记录安全 warning', warnings.indexOf('different configuration') >= 0);
        ok('warning 不含 scoped ID', warnings.indexOf('plf_') < 0);
    });
}


/* ============================================================
   reconciliation 等待语义与缓存语义（A-I）
============================================================ */

function localStateValue(status, snoozedUntil, surveyId) {
    return JSON.stringify({
        surveyId: surveyId === undefined ? SURVEY_ID : surveyId,
        status,
        updatedAt: 100,
        snoozedUntil: snoozedUntil === undefined ? 0 : snoozedUntil
    });
}

function testReconcileSubmittedReplaysAndWaits() {
    // A. local submitted + server null + replay 成功：
    // loadServerContext 必须等待 replay；确认后 server 视图为 submitted、
    // localStorage 为权威视图转换；SDK 不加载、Survey 不请求、弹窗不显示。
    let release = null;
    const h = initHarness({
        // 不设置 batchLayout：避免 init 的 recordSeen 触发 400ms record_seen flush，
        // 使 revision 只由被 gate 的 submitted 回放推进（同 revision 内容一致性校验
        // 要求 mock 响应与当前 revision 严格一致）。
        storage: {
            [STATE_KEY]: localStateValue('submitted'),
            [SEEN_KEY]: JSON.stringify(seenObject())
        },
        serverState: serverStateResponse({seenLayouts: LAYOUT_IDS.slice()}),
        serverPostResponse: ({body}) => {
            if (body.command !== 'submitted') return undefined;
            const gate = new Promise(resolve => {
                release = () => resolve({
                    ok: true,
                    json: () => Promise.resolve(serverStateResponse({
                        revision: 1,
                        status: 'submitted',
                        canShow: false,
                        retryAfterMs: 0,
                        seenLayouts: LAYOUT_IDS.slice()
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
    // 首次下载完成触发被阻断：SDK 不加载、Survey 不请求、弹窗不显示。
    const h = initHarness({
        page: 'alt',
        storage: {
            [STATE_KEY]: localStateValue('submitted'),
            [SEEN_KEY]: JSON.stringify(seenObject())
        },
        serverState: serverStateResponse({seenLayouts: []}),
        serverPostResponse: 'fail'
    });
    return waitForFlush().then(() => {
        h.dispatchFirstDownload();
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
        serverState: serverStateResponse({seenLayouts: []}),
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
        page: 'alt',
        storage: {
            [STATE_KEY]: localStateValue('snoozed', snoozedUntil),
            [SEEN_KEY]: JSON.stringify(seenObject())
        },
        serverState: serverStateResponse({seenLayouts: []}),
        serverPostResponse: 'fail'
    });
    return waitForFlush().then(() => {
        h.dispatchFirstDownload();
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
    // local seen 不被清空；effectiveSeen 仍有两个布局。
    const localSeen = {};
    localSeen['pixiv-batch-landscape'] = {firstSeenAt: 1, lastSeenAt: 100};
    localSeen['pixiv-batch-portrait'] = {firstSeenAt: 2, lastSeenAt: 200};
    const h = initHarness({
        batchLayout: 'landscape',
        storage: {[SEEN_KEY]: JSON.stringify(localSeen)},
        serverState: serverStateResponse({seenLayouts: []}),
        serverPostResponse: 'fail'
    });
    return waitForServerContext(h).then(() => {
        const stored = JSON.parse(h.storage.getItem(SEEN_KEY));
        ok('local seen 不被清空', stored['pixiv-batch-landscape'] && stored['pixiv-batch-portrait']);
        const effective = h.api._internals.effectiveSeen();
        eq('effectiveSeen 仍有两个布局',
            h.api._internals.distinctSeenCount(effective), 2);
    });
}

function testReconcileSuccessSyncsAuthoritativeSnapshot() {
    // F. replay 成功：pending fallback 清理；权威 server 视图转换写回 localStorage；
    // 首次下载完成触发被服务端 submitted 阻断，不展示。
    const h = initHarness({
        page: 'alt',
        storage: {
            [STATE_KEY]: localStateValue('submitted'),
            [SEEN_KEY]: JSON.stringify(seenObject())
        },
        serverState: serverStateResponse({seenLayouts: LAYOUT_IDS.slice()})
    });
    return waitForFlush().then(() => {
        const posts = h.serverPosts.filter(p => p.body.command === 'submitted');
        eq('replay 成功', posts.length, 1);
        const localState = JSON.parse(h.storage.getItem(STATE_KEY));
        eq('权威视图写回 localStorage', localState.status, 'submitted');
        ok('localStorage 与服务器视图一致（updatedAt 为客户端时钟域）',
            typeof localState.updatedAt === 'number');
        const effective = h.api._internals.effectiveState();
        eq('effectiveState 为服务器确认的 submitted', effective.status, 'submitted');
        h.dispatchFirstDownload();
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
            status: 'submitted',
            canShow: false,
            retryAfterMs: 0,
            seenLayouts: LAYOUT_IDS.slice()
        })
    });
    return waitForServerContext(h).then(() => {
        const downgrades = h.serverPosts.filter(p => p.body.command === 'snooze'
            || p.body.command === 'never' || p.body.command === 'submitted');
        eq('本地 snoozed 不回放降级命令', downgrades.length, 0);
        const localState = JSON.parse(h.storage.getItem(STATE_KEY));
        eq('localStorage 覆盖为服务端 submitted', localState.status, 'submitted');
        eq('localStorage 使用客户端时钟域时间戳（不复制服务端时间）',
            localState.updatedAt, 1000000);
        eq('effectiveState 为 submitted', h.api._internals.effectiveState().status, 'submitted');
    });
}

function testReconcileDecisionThenSeenOrdering() {
    // H. decision 与 seen 顺序：decision 命令先发出；seen 命令在 decision 确认后
    // 才发出（命令顺序固定，不制造并发状态竞态）。
    let releaseDecision = null;
    const h = initHarness({
        batchLayout: 'landscape',
        storage: {
            [STATE_KEY]: localStateValue('submitted'),
            [SEEN_KEY]: JSON.stringify(seenObject())
        },
        serverState: serverStateResponse({seenLayouts: []}),
        serverPostResponse: ({body}) => {
            if (body.command !== 'submitted') return undefined;
            const gate = new Promise(resolve => {
                releaseDecision = () => resolve({
                    ok: true,
                    json: () => Promise.resolve(serverStateResponse({
                        revision: 1,
                        status: 'submitted',
                        canShow: false,
                        retryAfterMs: 0,
                        seenLayouts: []
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
        eq('seen 命令不含 expectedRevision', seenPosts[0].body.expectedRevision, undefined);
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
        serverState: serverStateResponse({seenLayouts: []}),
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
   前端 no-store 与严格视图校验
============================================================ */

function testStateGetsUseNoStoreCache() {
    const h = initHarness({
        batchLayout: 'landscape',
        serverState: serverStateResponse({seenLayouts: LAYOUT_IDS.slice()})
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

function testApplyServerViewRejectsInvalidShapes() {
    // 非法视图字段 / 非法组合 / 缺失身份：整份拒绝，不初始化错误 identity，
    // 回退 local 模式（open 走浏览器匿名身份）。
    const withServerState = (overrides) => {
        const h = initHarness({
            batchLayout: 'landscape',
            serverState: serverStateResponse(overrides)
        });
        return h.api.open().then(() => waitForFlush()).then(() => h);
    };
    return withServerState({revision: 1.5}).then(h => {
        eq('非整数 revision 视图整体拒绝', h.adapter.sdkConfig().bootstrap === undefined, true);
    }).then(() => withServerState({revision: -1})).then(h => {
        eq('负数 revision 视图整体拒绝', h.adapter.sdkConfig().bootstrap === undefined, true);
    }).then(() => withServerState({revision: Number.NaN})).then(h => {
        eq('NaN revision 视图整体拒绝', h.adapter.sdkConfig().bootstrap === undefined, true);
    }).then(() => withServerState({distinctId: ''})).then(h => {
        eq('available=true 但 distinctId 缺失视图整体拒绝', h.adapter.sdkConfig().bootstrap === undefined, true);
    }).then(() => withServerState({status: 'bogus', canShow: false, retryAfterMs: 0})).then(h => {
        eq('未知 status 视图整体拒绝', h.adapter.sdkConfig().bootstrap === undefined, true);
    }).then(() => withServerState({status: 'submitted', canShow: true, retryAfterMs: 0})).then(h => {
        eq('submitted + canShow=true 组合非法拒绝', h.adapter.sdkConfig().bootstrap === undefined, true);
    }).then(() => withServerState({status: 'snoozed', canShow: true, retryAfterMs: 100})).then(h => {
        eq('snoozed canShow=true + retryAfterMs>0 组合非法拒绝', h.adapter.sdkConfig().bootstrap === undefined, true);
    }).then(() => withServerState({status: 'snoozed', canShow: false, retryAfterMs: 0})).then(h => {
        eq('snoozed canShow=false + retryAfterMs=0 组合非法拒绝', h.adapter.sdkConfig().bootstrap === undefined, true);
    }).then(() => withServerState({status: null, canShow: false, retryAfterMs: 0})).then(h => {
        eq('status=null 必须 canShow=true：组合非法拒绝', h.adapter.sdkConfig().bootstrap === undefined, true);
    }).then(() => withServerState({status: null, canShow: true, retryAfterMs: 50})).then(h => {
        eq('status=null 必须 retryAfterMs=0：组合非法拒绝', h.adapter.sdkConfig().bootstrap === undefined, true);
    }).then(() => withServerState({
        stateAvailable: false, status: 'never', canShow: false, retryAfterMs: 0
    })).then(h => {
        eq('stateAvailable=false 必须 status=null：组合非法拒绝', h.adapter.sdkConfig().bootstrap === undefined, true);
    }).then(() => withServerState({
        status: 'submitted', canShow: false, retryAfterMs: 0,
        seenLayouts: ['pixiv-batch-landscape', 'pixiv-batch-landscape']
    })).then(h => {
        eq('seenLayouts 重复视图整体拒绝', h.adapter.sdkConfig().bootstrap === undefined, true);
    }).then(() => withServerState({
        status: 'submitted', canShow: false, retryAfterMs: 0,
        seenLayouts: ['pixiv-batch-unknown']
    })).then(h => {
        eq('seenLayouts 未知布局视图整体拒绝', h.adapter.sdkConfig().bootstrap === undefined, true);
    }).then(() => withServerState({
        status: 'submitted', canShow: false, retryAfterMs: 0,
        seenLayouts: ['pixiv-batch-landscape', 'pixiv-batch-portrait', 'pixiv-batch-alt', 'pixiv-batch-landscape']
    })).then(h => {
        eq('seenLayouts 超过三个视图整体拒绝', h.adapter.sdkConfig().bootstrap === undefined, true);
    }).then(() => withServerState({canShow: 'yes'})).then(h => {
        eq('canShow 非 boolean 视图整体拒绝', h.adapter.sdkConfig().bootstrap === undefined, true);
    }).then(() => withServerState({retryAfterMs: -1})).then(h => {
        eq('负数 retryAfterMs 视图整体拒绝', h.adapter.sdkConfig().bootstrap === undefined, true);
    });
}

/* ============================================================
   乱序 / 迟到响应 / 跨标签 fallback / snooze 强度 / operation Set
   / GET-reconciliation timeout 分离 / storage 去重（A-J）
============================================================ */

function surveyState(status, snoozedUntil, updatedAt) {
    return {
        surveyId: SURVEY_ID,
        status: status,
        updatedAt: updatedAt === undefined ? 999 : updatedAt,
        snoozedUntil: snoozedUntil === undefined ? 0 : snoozedUntil
    };
}

// 服务端权威视图的提交形态（客户端时钟域伪状态由 serverViewAsState 转换）。
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

function testSnapshotRevisionMonotonic() {
    // A. 先应用 revision=2 submitted；再送达 revision=1 空状态（低 revision 迟到响应）。
    // 不设置 batchLayout：避免 init 的 record_seen 触发 400ms flush 推进 revision。
    const h = initHarness({
        serverState: serverStateResponse({
            revision: 2,
            status: 'submitted',
            canShow: false,
            retryAfterMs: 0,
            seenLayouts: []
        })
    });
    return waitForServerContext(h).then(() => {
        eq('初始视图已应用（revision=2）', h.api._internals.currentServerRevision(), 2);
        // 另一标签页写入 submitted fallback → 当前标签页合并进 pending
        h.dispatchStorage(STATE_KEY, JSON.stringify(surveyState('submitted')));
        return waitForFlush();
    }).then(() => {
        eq('storage 事件合并后 pending 生效', h.api._internals.effectiveState().status, 'submitted');
        // 服务器 refresh 返回低 revision 空状态：STALE，不得覆盖
        h.setServerState(serverStateResponse({revision: 1, status: null, canShow: true, retryAfterMs: 0, seenLayouts: []}));
        h.dispatchStorage(STATE_KEY, JSON.stringify(surveyState('submitted')));
        return waitForFlush();
    }).then(() => {
        eq('低 revision 不覆盖：serverRevision 仍为 2', h.api._internals.currentServerRevision(), 2);
        eq('effectiveState 仍 submitted', h.api._internals.effectiveState().status, 'submitted');
        eq('STATE_KEY 仍 submitted', JSON.parse(h.storage.getItem(STATE_KEY)).status, 'submitted');
        eq('pending 不被 STALE 响应清理', h.api._internals.effectiveState().status, 'submitted');
    });
}

function testSnapshotSameRevisionPersistentContent() {
    // B. 同 revision 持久化字段（status）不同 → INVALID 拒绝；
    // 同 revision 完全相同 → SAME 无副作用。
    const h = initHarness({
        serverState: serverStateResponse({
            revision: 2,
            status: 'submitted',
            canShow: false,
            retryAfterMs: 0,
            seenLayouts: []
        })
    });
    let warnsBefore = 0;
    return waitForServerContext(h).then(() => {
        h.setServerState(serverStateResponse({
            revision: 2,
            status: null,
            canShow: true,
            retryAfterMs: 0,
            seenLayouts: []
        }));
        warnsBefore = h.consoleWarn.length;
        h.dispatchStorage(STATE_KEY, JSON.stringify(surveyState('submitted')));
        return waitForFlush();
    }).then(() => {
        eq('同 revision status 不同被拒绝：serverRevision 仍为 2', h.api._internals.currentServerRevision(), 2);
        eq('不覆盖：effectiveState 仍 submitted', h.api._internals.effectiveState().status, 'submitted');
        eq('不覆盖：STATE_KEY 仍 submitted', JSON.parse(h.storage.getItem(STATE_KEY)).status, 'submitted');
        const newWarns = h.consoleWarn.slice(warnsBefore);
        ok('记录安全 warning', JSON.stringify(newWarns).indexOf('conflicting content for the same revision') >= 0);
        const warnText = JSON.stringify(newWarns);
        ok('warning 不含 token', warnText.indexOf(h.config.projectToken) < 0);
        ok('warning 不含 Survey ID', warnText.indexOf(h.config.surveyId) < 0);
        ok('warning 不含 scoped ID', warnText.indexOf('plf_') < 0);
    }).then(() => {
        // 同 revision 完全相同 → SAME：无副作用、无新 warning
        const h2 = initHarness({
            serverState: serverStateResponse({
                revision: 2,
                status: 'submitted',
                canShow: false,
                retryAfterMs: 0,
                seenLayouts: []
            })
        });
        return waitForServerContext(h2).then(() => {
            const warnsBefore = h2.consoleWarn.length;
            h2.setServerState(serverStateResponse({
                revision: 2,
                status: 'submitted',
                canShow: false,
                retryAfterMs: 0,
                seenLayouts: []
            }));
            h2.dispatchStorage(STATE_KEY, JSON.stringify(surveyState('submitted')));
            return waitForFlush();
        }).then(() => {
            eq('SAME：revision 不变', h2.api._internals.currentServerRevision(), 2);
            eq('SAME：无新 warning', h2.consoleWarn.length, warnsBefore);
            eq('SAME：effectiveState 不变', h2.api._internals.effectiveState().status, 'submitted');
        });
    });
}

function testSameRevisionDynamicViewUpdate() {
    // C. 同 revision 只有动态字段（canShow / retryAfterMs）变化：
    // - retryAfterMs 递减（snoozed canShow=false）→ 合法 VIEW_UPDATED；
    // - snoozed 从 canShow=false 变为 canShow=true（服务端到期）→ 合法 VIEW_UPDATED。
    return Promise.resolve().then(() => {
        const h = initHarness({
            serverFetch: refreshWith(
                snoozedView(20 * 60 * 1000, {revision: 2}),
                () => ({ok: true, json: () => Promise.resolve(serverStateResponse({
                    revision: 2,
                    status: 'snoozed',
                    canShow: false,
                    retryAfterMs: 10 * 60 * 1000,
                    seenLayouts: []
                }))}))
        });
        return waitForServerContext(h).then(() => {
            eq('初始 retryAfterMs 20 分钟', h.api._internals.serverRetryAfterMs(), 20 * 60 * 1000);
            eq('serverLocalBlockUntil = now + 20 分钟', h.api._internals.serverLocalBlockUntil(),
                1000000 + 20 * 60 * 1000);
            return directRefresh(h).then(result => {
                eq('retryAfterMs 递减 → fresh', result.status, 'fresh');
                eq('retryAfterMs 递减 → viewResult=updated', result.viewResult, 'updated');
                eq('revision 不变', h.api._internals.currentServerRevision(), 2);
                eq('动态字段已更新：retryAfterMs 10 分钟', h.api._internals.serverRetryAfterMs(), 10 * 60 * 1000);
                eq('serverLocalBlockUntil 已更新', h.api._internals.serverLocalBlockUntil(),
                    1000000 + 10 * 60 * 1000);
                eq('有效状态仍为 snoozed（本地截止时间更新）',
                    h.api._internals.effectiveState().status, 'snoozed');
                eq('无新 warning', h.consoleWarn.length, 0);
            });
        });
    }).then(() => {
        // 服务端到达 snoozedUntil：canShow=false → true，retryAfterMs=0。
        const h = initHarness({
            serverFetch: refreshWith(
                snoozedView(20 * 60 * 1000, {revision: 2}),
                () => ({ok: true, json: () => Promise.resolve(serverStateResponse({
                    revision: 2,
                    status: 'snoozed',
                    canShow: true,
                    retryAfterMs: 0,
                    seenLayouts: []
                }))}))
        });
        return waitForServerContext(h).then(() => {
            return directRefresh(h).then(result => {
                eq('snoozed canShow false→true → fresh', result.status, 'fresh');
                eq('snoozed canShow false→true → viewResult=updated', result.viewResult, 'updated');
                eq('revision 不变', h.api._internals.currentServerRevision(), 2);
                eq('canShow 已更新', h.api._internals.serverCanShow(), true);
                eq('retryAfterMs 清零', h.api._internals.serverRetryAfterMs(), 0);
                eq('有效状态不再阻断（服务端已到期）', h.api._internals.effectiveState(), null);
                const localState = JSON.parse(h.storage.getItem(STATE_KEY));
                eq('本地 snooze 被清理（服务端已到期）', localState, null);
            });
        });
    });
}

function testLateResponseAfterCommandTimeout() {
    // D. server command 超时完成后再触发其 HTTP 响应：无副作用。
    let release = null;
    let warnsBefore = 0;
    let warnsAfterTimeout = 0;
    const h = initHarness({
        serverState: serverStateResponse({seenLayouts: LAYOUT_IDS.slice()}),
        serverPostResponse: () => new Promise(resolve => {
            release = () => resolve({
                ok: true,
                json: () => Promise.resolve(serverStateResponse({
                    revision: 9,
                    status: 'submitted',
                    canShow: false,
                    retryAfterMs: 0,
                    seenLayouts: LAYOUT_IDS.slice()
                }))
            });
        })
    });
    return waitForServerContext(h).then(() => h.api.open()).then(() => waitForFlush()).then(() => {
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
    // E. refresh GET 超时后迟到响应：不 apply / 不 sync / 不修改 serverRevision。
    const h = initHarness({
        serverFetch: 'pending',
        serverState: serverStateResponse({
            revision: 2, status: 'submitted', canShow: false, retryAfterMs: 0, seenLayouts: []
        })
    });
    return waitForFlush().then(() => {
        h.serverFetchGate.resolve({ok: true, json: () => Promise.resolve(serverStateResponse({
            revision: 2,
            status: 'submitted',
            canShow: false,
            retryAfterMs: 0,
            seenLayouts: []
        }))});
        return waitForFlush();
    }).then(() => {
        eq('初始视图已应用', JSON.parse(h.storage.getItem(STATE_KEY)).status, 'submitted');
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
            status: 'never',
            canShow: false,
            retryAfterMs: 0,
            seenLayouts: []
        }))});
        return waitForFlush();
    }).then(() => {
        eq('迟到 refresh 不修改 revision', h.api._internals.currentServerRevision(), 2);
        eq('迟到 refresh 不修改 state', h.api._internals.effectiveState().status, 'submitted');
        eq('迟到 refresh 不同步 localStorage', JSON.parse(h.storage.getItem(STATE_KEY)).status, 'submitted');
        eq('无残留定时器', h.timers.pending().length, 0);
    });
}

function testOutOfOrderCommandResponses() {
    // D. record_seen 的 revision 1 响应延迟；submitted 命令先以 revision 2 成功；
    // 最后 record_seen 的 revision 1 响应到达：低 revision 无副作用。
    let releaseRecordSeen = null;
    let submittedCount = 0;
    const h = initHarness({
        batchLayout: 'landscape',
        serverState: serverStateResponse({seenLayouts: []}),
        serverPostResponse: ({body}) => {
            if (body.command === 'record_seen') {
                return new Promise(resolve => {
                    releaseRecordSeen = () => resolve({
                        ok: true,
                        json: () => Promise.resolve(serverStateResponse({
                            revision: 1,
                            status: null,
                            canShow: true,
                            retryAfterMs: 0,
                            seenLayouts: []
                        }))
                    });
                });
            }
            if (body.command === 'submitted') {
                submittedCount++;
                return {
                    ok: true,
                    json: () => Promise.resolve(serverStateResponse({
                        revision: 2,
                        status: 'submitted',
                        canShow: false,
                        retryAfterMs: 0,
                        seenLayouts: ['pixiv-batch-portrait']
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
        eq('submitted 成功后 revision 为 2', h.api._internals.currentServerRevision(), 2);
        eq('本地为 submitted', JSON.parse(h.storage.getItem(STATE_KEY)).status, 'submitted');
        ok('flush 的 record_seen 已发出且 gated',
            h.serverPosts.filter(p => p.body.command === 'record_seen').length >= 2);
        releaseRecordSeen();
        return waitForFlush();
    }).then(() => {
        eq('迟到 revision 1 不覆盖：revision 仍为 2', h.api._internals.currentServerRevision(), 2);
        eq('状态仍 submitted', h.api._internals.effectiveState().status, 'submitted');
        eq('本地缓存不被旧视图覆盖', JSON.parse(h.storage.getItem(STATE_KEY)).status, 'submitted');
        const seen = JSON.parse(h.storage.getItem(SEEN_KEY));
        ok('localStorage seen 仍保留 portrait', seen && seen['pixiv-batch-portrait']);
        eq('operation 集合为空', h.api._internals.serverCommandOperations.size, 0);
    });
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
        eq(label + '：弹窗关闭', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq(label + '：不发送 dismissed', captureEvents(h).indexOf('survey dismissed'), -1);
        eq(label + '：localStorage 保留 fallback', JSON.parse(h.storage.getItem(STATE_KEY)).status, stateStatus);
        eq(label + '：pendingLocalState 已合并', h.api._internals.effectiveState().status, stateStatus);
        eq(label + '：显示已在其他标签页处理', h.toastCalls.length, 1);
        // 服务器 refresh 返回旧空状态（SAME / 无变化）：fallback 保留，调查不重新展示。
        h.dispatchFirstDownload();
        return waitForFlush();
    }).then(() => {
        eq(label + '：触发不重新展示', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq(label + '：触发不补发 shown', captureEvents(h).filter(e => e === 'survey shown').length, shownBefore);
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
    // B 的服务器 GET 返回空 seenLayouts 时 localStorage 不清空、seenCount 不下降。
    const localSeen = {};
    localSeen['pixiv-batch-landscape'] = {firstSeenAt: 1, lastSeenAt: 100};
    localSeen['pixiv-batch-portrait'] = {firstSeenAt: 2, lastSeenAt: 200};
    const h = initHarness({
        batchLayout: 'landscape',
        serverState: serverStateResponse({seenLayouts: []}),
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
        // 服务端只提供剩余时长（retryAfterMs），本地剩余时长按本地截止时间计算——
        // 只比较两个「剩余时长」，不比较任何绝对时间点。
        const serverRetry = DAY;
        const localUntil = 1000000 + 7 * DAY;
        const h = initHarness({
            batchLayout: 'landscape',
            storage: {[STATE_KEY]: snoozeStorageValue(localUntil)},
            serverState: serverStateResponse({
                status: 'snoozed',
                canShow: false,
                retryAfterMs: serverRetry,
                seenLayouts: []
            })
        });
        return waitForServerContext(h).then(() => {
            const posts = h.serverPosts.filter(p => p.body.command === 'snooze');
            eq('本地更强：回放 snooze 命令', posts.length, 1);
            // 服务端按自己的时钟保存 7 天 snooze；命令确认后本地截止时间由服务端
            // retryAfterMs（7 天）重新生成。
            eq('命令确认后采用服务端剩余时长（本地截止 = clientNow + 7 天）',
                JSON.parse(h.storage.getItem(STATE_KEY)).snoozedUntil, 1000000 + SNOOZE_MS);
        });
    }).then(() => {
        // server snooze 7 天，local snooze 1 天：server 更强，不回放，pending 可清理。
        const serverRetry = 7 * DAY;
        const localUntil = 1000000 + DAY;
        const h = initHarness({
            batchLayout: 'landscape',
            storage: {[STATE_KEY]: snoozeStorageValue(localUntil)},
            serverState: serverStateResponse({
                status: 'snoozed',
                canShow: false,
                retryAfterMs: serverRetry,
                seenLayouts: []
            })
        });
        return waitForServerContext(h).then(() => {
            const posts = h.serverPosts.filter(p => p.body.command === 'snooze'
                || p.body.command === 'never' || p.body.command === 'submitted');
            eq('服务端更强：不回放', posts.length, 0);
            console.log('DBG serverStatus=',h.api._internals.serverStatus(),'backed=',h.api._internals.serverBacked(),'canShow=',h.api._internals.serverCanShow(),'retry=',h.api._internals.serverRetryAfterMs(),'until=',h.api._internals.serverLocalBlockUntil(),'local=',JSON.parse(h.storage.getItem(STATE_KEY)));
            eq('effectiveState 为服务端剩余时长（本地截止 = clientNow + 7 天）',
                h.api._internals.effectiveState().snoozedUntil, 1000000 + 7 * DAY);
            eq('localStorage 覆盖为服务端剩余时长', JSON.parse(h.storage.getItem(STATE_KEY)).snoozedUntil,
                1000000 + 7 * DAY);
        });
    }).then(() => {
        // 本地 snooze 与服务器剩余时长在容差内等价：不回放（服务器已提供至少相同的
        // 阻断效果）。
        const localUntil = 1000000 + 7 * DAY;
        const h = initHarness({
            batchLayout: 'landscape',
            storage: {[STATE_KEY]: snoozeStorageValue(localUntil)},
            serverState: serverStateResponse({
                status: 'snoozed',
                canShow: false,
                retryAfterMs: 7 * DAY - 1000,
                seenLayouts: []
            })
        });
        return waitForServerContext(h).then(() => {
            const posts = h.serverPosts.filter(p => p.body.command === 'snooze');
            eq('服务器剩余时长与本地在容差内：不回放', posts.length, 0);
        });
    });
}

function testConcurrentCommandOperations() {
    // H. 三个并发 operation：A(snooze) → C(never) → B(record_seen) 完成顺序。
    // 完成 A 不删除 B/C；完成 C 不删除 B；B 最终正常完成；Set 最终为空。
    // 服务端 seenLayouts 缺 portrait：切换布局后 portrait 触发 record_seen 去抖 flush。
    const gates = [];
    const h = initHarness({
        serverState: serverStateResponse({
            seenLayouts: ['pixiv-batch-landscape']
        }),
        serverPostResponse: ({body}) => {
            return new Promise(resolve => gates.push(() => resolve({
                ok: true,
                json: () => Promise.resolve(serverStateResponse({
                    revision: body.command === 'never' ? 2 : 1,
                    status: body.command === 'submitted'
                        ? 'submitted'
                        : body.command === 'never'
                            ? 'never'
                            : body.command === 'snooze'
                                ? 'snoozed'
                                : null,
                    canShow: body.command === 'snooze' ? false : body.command === 'submitted' ? false
                        : body.command === 'never' ? false : true,
                    retryAfterMs: body.command === 'snooze' ? SNOOZE_MS : 0,
                    seenLayouts: ['pixiv-batch-landscape', 'pixiv-batch-portrait']
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
    // 服务端 seenLayouts 缺 portrait：切换布局后 portrait 触发 record_seen 去抖 flush。
    const gates = [];
    let storedBefore = null;
    let seenBefore = null;
    const h = initHarness({
        serverState: serverStateResponse({
            seenLayouts: ['pixiv-batch-landscape']
        }),
        serverPostResponse: () => new Promise(resolve => gates.push(() => resolve({
            ok: true,
            json: () => Promise.resolve(serverStateResponse({
                revision: 5,
                status: null,
                canShow: true,
                retryAfterMs: 0,
                seenLayouts: LAYOUT_IDS.slice()
            }))
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
        serverState: serverStateResponse({seenLayouts: []}),
        serverPostResponse: ({body}) => {
            if (body.command !== 'submitted') return undefined;
            return new Promise(resolve => {
                release = () => resolve({
                    ok: true,
                    json: () => Promise.resolve(serverStateResponse({
                        revision: 1,
                        status: 'submitted',
                        canShow: false,
                        retryAfterMs: 0,
                        // seen 与本地 fallback 相同：reconcileSeen 无需再发 record_seen，
                        // 避免默认 mock 的空视图覆盖 submitted。
                        seenLayouts: LAYOUT_IDS.slice()
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
        eq('确认后 localStorage 为服务端 submitted（同业务状态保留本地 updatedAt，不复制服务端时间戳）',
            JSON.parse(h.storage.getItem(STATE_KEY)).updatedAt, 100);
        // 手动 open 的弹窗在 reconciliation 完成后正常打开（skipStateGate 不受
        // 服务端 submitted 门禁影响；触发式门禁由其它测试覆盖）。
        eq('手动 open 流程正常完成', h.document.querySelectorAll('.plf-backdrop').length, 1);
        eq('不会叠加第二个弹窗', h.document.querySelectorAll('.plf-backdrop').length, 1);
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
        serverState: serverStateResponse({seenLayouts: []}),
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
        // 手动 open 的弹窗（skipStateGate）正常打开。
        eq('手动 open 流程正常完成', h.document.querySelectorAll('.plf-backdrop').length, 1);
        eq('不叠加第二个弹窗', h.document.querySelectorAll('.plf-backdrop').length, 1);
        // 弹窗焦点定时器（0ms）等一次性定时器已执行，无残留。
        h.timers.advance(0);
        return waitForFlush();
    }).then(() => {
        eq('无残留定时器', h.timers.pending().length, 0);
    });
}

function testStorageWriteDedup() {
    // J. syncServerViewToLocalCache 写入与现有值相同：不重复 setItem，
    // 不产生无意义 storage 协调。
    const h = initHarness({
        batchLayout: 'landscape',
        serverState: serverStateResponse({seenLayouts: []})
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

function testRefreshResultContract() {
    const submittedSnapshot = (revision) => ({
        ok: true,
        json: () => Promise.resolve(serverStateResponse({
            revision, status: 'submitted', canShow: false, retryAfterMs: 0, seenLayouts: []
        }))
    });
    return Promise.resolve().then(() => {
        // A1. APPLIED → fresh / viewResult=applied
        const h = initHarness({
            serverFetch: refreshSecond(() => ({
                ok: true,
                json: () => Promise.resolve(serverStateResponse({
                    revision: 1, status: 'submitted', canShow: false, retryAfterMs: 0, seenLayouts: []
                }))
            }))
        });
        return waitForServerContext(h).then(() => directRefresh(h)).then(result => {
            eq('APPLIED → status=fresh', result.status, 'fresh');
            eq('APPLIED → viewResult=applied', result.viewResult, 'applied');
            eq('APPLIED 已提交视图', h.api._internals.currentServerRevision(), 1);
        });
    }).then(() => {
        // A2. SAME → fresh，无副作用
        const h = initHarness({
            serverFetch: refreshWith(
                submittedView({revision: 2}),
                submittedSnapshot(2))
        });
        return waitForServerContext(h).then(() => {
            const warnsBefore = h.consoleWarn.length;
            const stateBefore = h.storage.getItem(STATE_KEY);
            return directRefresh(h).then(result => {
                eq('SAME → status=fresh', result.status, 'fresh');
                eq('SAME → viewResult=same', result.viewResult, 'same');
                eq('SAME 不推进 revision', h.api._internals.currentServerRevision(), 2);
                eq('SAME 无新 warning', h.consoleWarn.length, warnsBefore);
                eq('SAME 不改写协调缓存', h.storage.getItem(STATE_KEY), stateBefore);
            });
        });
    }).then(() => {
        // A3. STALE → fresh，当前高 revision 状态不变
        const h = initHarness({
            serverFetch: refreshWith(
                submittedView({revision: 2}),
                () => ({
                    ok: true,
                    json: () => Promise.resolve(serverStateResponse({
                        revision: 1, status: null, canShow: true, retryAfterMs: 0, seenLayouts: []
                    }))
                }))
        });
        return waitForServerContext(h).then(() => {
            const warnsBefore = h.consoleWarn.length;
            return directRefresh(h).then(result => {
                eq('STALE → status=fresh', result.status, 'fresh');
                eq('STALE → viewResult=stale', result.viewResult, 'stale');
                eq('STALE 不覆盖高 revision', h.api._internals.currentServerRevision(), 2);
                eq('STALE 状态不变', h.api._internals.effectiveState().status, 'submitted');
                eq('STALE 无新 warning', h.consoleWarn.length, warnsBefore);
            });
        });
    }).then(() => {
        // A4. 同 revision 动态字段更新（retryAfterMs 递减）→ fresh / updated
        const h = initHarness({
            serverFetch: refreshWith(
                snoozedView(20 * 60 * 1000, {revision: 2}),
                () => ({ok: true, json: () => Promise.resolve(serverStateResponse({
                    revision: 2,
                    status: 'snoozed',
                    canShow: false,
                    retryAfterMs: 10 * 60 * 1000,
                    seenLayouts: []
                }))}))
        });
        return waitForServerContext(h).then(() => directRefresh(h)).then(result => {
            eq('同 revision 动态更新 → status=fresh', result.status, 'fresh');
            eq('同 revision 动态更新 → viewResult=updated', result.viewResult, 'updated');
            eq('动态字段应用：retryAfterMs 10 分钟', h.api._internals.serverRetryAfterMs(), 10 * 60 * 1000);
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
                submittedView({revision: 2}),
                () => ({
                    ok: true,
                    json: () => Promise.resolve(serverStateResponse({
                        revision: 2, status: null, canShow: true, retryAfterMs: 0, seenLayouts: []
                    }))
                }))
        });
        return waitForServerContext(h).then(() => {
            const warnsBefore = h.consoleWarn.length;
            return directRefresh(h).then(result => {
                eq('同 revision status 冲突 → invalid', result.status, 'invalid');
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
                revision: 9, status: 'submitted', canShow: false, retryAfterMs: 0, seenLayouts: []
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
            serverState: serverStateResponse({seenLayouts: LAYOUT_IDS.slice()})
        });
        return waitForServerContext(h).then(() => openAndPrepareSubmit(h)).then(() => {
            h.setServerState(serverStateResponse({
                distinctId: 'plf_' + 'cd'.repeat(32),
                seenLayouts: LAYOUT_IDS.slice()
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
                && warns.indexOf(h.config.projectToken) < 0
                && warns.indexOf(h.config.surveyId) < 0);
        });
    }).then(() => {
        // 2. 同 revision 持久化字段冲突：同样 fail-closed
        const h = initHarness({
            batchLayout: 'landscape',
            serverState: serverStateResponse({seenLayouts: LAYOUT_IDS.slice()})
        });
        return waitForServerContext(h).then(() => openAndPrepareSubmit(h)).then(() => {
            // 以当前真实 revision 构造同 revision 不同持久化内容响应（reconcile 可能已推进
            // revision，不能写死 0，否则会落入 STALE 而不是冲突）。
            const currentRevision = h.api._internals.currentServerRevision();
            h.setServerState(serverStateResponse({
                revision: currentRevision,
                status: 'submitted',
                canShow: false,
                retryAfterMs: 0,
                seenLayouts: LAYOUT_IDS.slice()
            }));
            h.submitButton().click();
            return waitForFlush();
        }).then(() => {
            assertFailClosedInvariants(h, '同 revision 冲突');
            const warns = JSON.stringify(h.consoleWarn);
            ok('同 revision 冲突 warning 不含 token / Survey ID / scoped ID',
                warns.indexOf(h.config.projectToken) < 0
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
                {revision: 2, status: null, canShow: true, retryAfterMs: 0, seenLayouts: []},
                () => ({
                    ok: true,
                    json: () => Promise.resolve(serverStateResponse({
                        revision: 1,
                        status: 'submitted',
                        canShow: false,
                        retryAfterMs: 0,
                        seenLayouts: []
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
                {revision: 2, status: null, canShow: true, retryAfterMs: 0, seenLayouts: []},
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
                revision: 1, status: null, canShow: true, retryAfterMs: 0, seenLayouts: []
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
                return {ok: true, json: () => Promise.resolve(serverStateResponse({seenLayouts: []}))};
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
                        status: 'submitted',
                        canShow: false,
                        retryAfterMs: 0,
                        seenLayouts: LAYOUT_IDS.slice()
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
        warnsBefore = h.consoleWarn.length;
        h.api.destroy();
        h.api.init(reinitOptions(h));
        return waitForFlush();
    }).then(() => {
        // gen2 init 完成后快照 seen（gen2 自己的布局记录属于合法写入）。
        storedBefore.seen = h.storage.getItem(SEEN_KEY);
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
                return {ok: true, json: () => Promise.resolve(serverStateResponse({seenLayouts: []}))};
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
                        status: null,
                        canShow: true,
                        retryAfterMs: 0,
                        seenLayouts: LAYOUT_IDS.slice()
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
        // gen2 init 完成后重新快照（gen2 自己的布局记录属于合法写入）。
        storedBefore.seen = h.storage.getItem(SEEN_KEY);
        storedBefore.state = h.storage.getItem(STATE_KEY);
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
    // C. reconciliation 结束前 destroy：旧链不得执行 syncServerViewToLocalCache。
    // gen1 若错误同步会把 SEEN_KEY 覆盖为服务端 landscape-only 视图，观察 setItem 次数。
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
                    status: null,
                    canShow: true,
                    retryAfterMs: 0,
                    seenLayouts: ['pixiv-batch-landscape']
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
                        status: 'submitted',
                        canShow: false,
                        retryAfterMs: 0,
                        seenLayouts: ['pixiv-batch-landscape']
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
            serverState: serverStateResponse({status: 'submitted', canShow: false, retryAfterMs: 0,
                seenLayouts: LAYOUT_IDS.slice()})
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
            serverState: serverStateResponse({status: 'submitted', canShow: false, retryAfterMs: 0,
                seenLayouts: LAYOUT_IDS.slice()})
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
            serverState: serverStateResponse({status: 'never', canShow: false, retryAfterMs: 0,
                seenLayouts: LAYOUT_IDS.slice()})
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
                status: 'snoozed',
                canShow: false,
                retryAfterMs: SEVEN_DAYS - 1000000,
                seenLayouts: []
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
                status: 'snoozed',
                canShow: false,
                retryAfterMs: ONE_DAY - 1000000,
                seenLayouts: []
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
            serverState: serverStateResponse({status: 'never', canShow: false, retryAfterMs: 0, seenLayouts: []})
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
            serverState: serverStateResponse({seenLayouts: []})
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
            serverState: serverStateResponse({seenLayouts: []}),
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
   同强度幂等 / 服务端视图转换 / localStorage 时钟域（新增）
============================================================ */

function testServerViewToLocalState() {
    // serverViewToLocalState 纯函数：服务端 retryAfterMs 只按本地时长转换，
    // 与客户端 / 服务端绝对时间差异无关；submitted / never 保留同业务状态旧对象。
    const internals = initHarness({}).api._internals;
    const localStateValue = (status, snoozedUntil) => ({
        surveyId: SURVEY_ID,
        status,
        updatedAt: 100,
        snoozedUntil: snoozedUntil === undefined ? 0 : snoozedUntil
    });
    return Promise.resolve().then(() => {
        // A. server snoozed + retryAfterMs=20 分钟：本地 snoozedUntil = clientNow + 20 分钟
        const view = {state: localStateValue('snoozed', 1000000 + 20 * 60 * 1000), source: 'server'};
        const out = internals.serverViewToLocalState(view, 1000000, null);
        eq('本地 snoozedUntil = clientNow + 20 分钟', out.snoozedUntil, 1000000 + 20 * 60 * 1000);
        // B. 结果只依赖 duration：无论「浏览器时间与服务器差多少」，转换只依赖
        //    clientNow + retryAfterMs（这里 clientNow 就是 1000000）。
        eq('转换只依赖本地时长', out.snoozedUntil - 1000000, 20 * 60 * 1000);
        // C. 已有同 Survey 本地 snooze 且差距 <= 5 秒：保留旧对象（不无意义重写）
        const existing = localStateValue('snoozed', 1000000 + 20 * 60 * 1000 - 3000);
        const kept = internals.serverViewToLocalState(view, 1000000, existing);
        eq('容差内保留旧对象（updatedAt 保留）', kept.updatedAt, 100);
        eq('容差内保留旧对象（snoozedUntil 保留）', kept.snoozedUntil, existing.snoozedUntil);
        // D. 已有同 Survey 本地 snooze 但差距超过容差：写新截止时间
        const far = localStateValue('snoozed', 1000000 + 20 * 60 * 1000 - 30 * 1000);
        const rewritten = internals.serverViewToLocalState(view, 1000000, far);
        eq('超容差重写为服务端剩余时长', rewritten.snoozedUntil, 1000000 + 20 * 60 * 1000);
        // E. server submitted：已有相同 submitted 保留旧对象；否则新建
        const subView = {state: localStateValue('submitted'), source: 'server'};
        const keptSub = internals.serverViewToLocalState(subView, 1000000,
            localStateValue('submitted'));
        eq('server submitted 保留已有同状态对象', keptSub.updatedAt, 100);
        const freshSub = internals.serverViewToLocalState(subView, 1000000, null);
        eq('server submitted 无旧对象时新建', freshSub.status, 'submitted');
        // F. server never：本地已有 submitted 时保留 submitted（更强状态由
        //    effectiveStateRecord 保证，这里验证转换不降级）
        const neverView = {state: localStateValue('never'), source: 'server'};
        const neverOut = internals.serverViewToLocalState(neverView, 1000000,
            localStateValue('submitted'));
        eq('server never 不覆盖已有本地 submitted', neverOut.status, 'submitted');
    });
}

function testWriteStateIdempotentAcrossTime() {
    const DAY = 24 * 60 * 60 * 1000;
    const stateSets = (h) => h.storage.setCalls.filter(c => c[0] === STATE_KEY).length;
    const postsOf = (h, command) => h.serverPosts.filter(p => p.body.command === command).length;
    return Promise.resolve().then(() => {
        // A. repeated submitted：时间前进后重复写不刷新 updatedAt / 不重复写 / 不重复命令
        const h = initHarness({
            serverState: serverStateResponse({seenLayouts: []}),
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
            serverState: serverStateResponse({seenLayouts: []}),
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
            serverState: serverStateResponse({seenLayouts: []}),
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
            serverState: serverStateResponse({seenLayouts: []}),
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
            serverState: serverStateResponse({seenLayouts: []}),
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
            serverState: serverStateResponse({seenLayouts: LAYOUT_IDS.slice()}),
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
                status: 'submitted',
                canShow: false,
                retryAfterMs: 0,
                seenLayouts: LAYOUT_IDS.slice()
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

/* ============================================================
   服务端计划任务到期 / localStorage 时钟域回退 / 无 CAS 命令
   （新增，替换旧 serverTime / 时钟采样 / 409 确认测试）
============================================================ */

function testServerSnoozeExpiryByRetryAfterMs() {
    // 服务端独立判断到期：浏览器只消费 retryAfterMs 并转换为本地截止时间。
    // A. 距到期 20 分钟：本地截止 = clientNow + 20 分钟；页面不展示；
    //    首次下载完成触发在本地截止过后才重新 GET 服务端权威状态。
    // B. 服务端到期（canShow=true / retryAfterMs=0）：本地 snooze 清理，可重新展示。
    return Promise.resolve().then(() => {
        // 服务器剩余时间随请求递减（真实服务端每次返回当前剩余时长）。
        let getCalls = 0;
        const h = initHarness({
            page: 'alt',
            initialWall: 1000000,
            serverFetch: () => {
                getCalls++;
                if (getCalls === 1) {
                    return {ok: true, json: () => Promise.resolve(serverStateResponse({
                        status: 'snoozed',
                        canShow: false,
                        retryAfterMs: 20 * 60 * 1000,
                        seenLayouts: LAYOUT_IDS.slice()
                    }))};
                }
                // 同 revision 动态视图：retryAfterMs 递减到 1 分钟（服务端尚未到期）。
                return {ok: true, json: () => Promise.resolve(serverStateResponse({
                    revision: 0,
                    status: 'snoozed',
                    canShow: false,
                    retryAfterMs: 60 * 1000,
                    seenLayouts: LAYOUT_IDS.slice()
                }))};
            }
        });
        return waitForServerContext(h).then(() => {
            const localState = JSON.parse(h.storage.getItem(STATE_KEY));
            eq('localStorage 保存 clientNow + retryAfterMs（20 分钟）',
                localState.snoozedUntil, 1000000 + 20 * 60 * 1000);
            eq('serverLocalBlockUntil = clientNow + 20 分钟',
                h.api._internals.serverLocalBlockUntil(), 1000000 + 20 * 60 * 1000);
            // 服务端到期前触发：不展示。
            h.dispatchFirstDownload();
            return waitForFlush();
        }).then(() => {
            eq('服务端 canShow=false：不展示', h.document.querySelectorAll('.plf-backdrop').length, 0);
            // 浏览器设备时间任意调快：本地截止时间（clientNow + retryAfterMs）随之
            // 提前，不提前展示（服务端视图 canShow=false 仍是旧值，本地截止未到）。
            h.timers.setWallNow(1000000 + 19 * 60 * 1000);
            return waitForFlush();
        }).then(() => {
            eq('设备时间调快 19 分钟：仍不展示（本地截止未到）',
                h.document.querySelectorAll('.plf-backdrop').length, 0);
            // 本地截止时间过后：触发时允许重新 GET 服务端权威状态。
            h.timers.setWallNow(1000000 + 21 * 60 * 1000);
            h.dispatchFirstDownload();
            return waitForFlush();
        }).then(() => {
            eq('本地截止过后触发重新 GET（stateFetchCount 增加）', h.stateFetchCount() >= 2, true);
            eq('服务端尚未到期（剩余 1 分钟）：仍不展示', h.document.querySelectorAll('.plf-backdrop').length, 0);
            eq('动态视图更新：本地截止 = 重新 GET 时 now + 1 分钟',
                h.api._internals.serverRetryAfterMs(), 60 * 1000);
        });
    }).then(() => {
        // 服务端到达 snoozedUntil：GET 返回 canShow=true / retryAfterMs=0。
        const h = initHarness({
            page: 'alt',
            initialWall: 1000000,
            serverFetch: refreshWith(
                {status: 'snoozed', canShow: false, retryAfterMs: 20 * 60 * 1000, seenLayouts: []},
                () => ({ok: true, json: () => Promise.resolve(serverStateResponse({
                    revision: 1,
                    status: 'snoozed',
                    canShow: true,
                    retryAfterMs: 0,
                    seenLayouts: []
                }))}))
        });
        return waitForServerContext(h).then(() => {
            h.timers.advance(21 * 60 * 1000);
            h.dispatchFirstDownload();
            return waitForFlush();
        }).then(() => {
            eq('服务端到期后本地 snooze 清理', h.storage.getItem(STATE_KEY), null);
            eq('页面重新满足展示条件', h.document.querySelectorAll('.plf-backdrop').length, 1);
        });
    });
}

function testLocalStorageFallbackAcrossReload() {
    // 服务端 snooze 20 分钟 → destroy + re-init 且服务端 GET 超时：
    // 本地缓存继续阻断约 20 分钟（不提前显示、不额外延长）。
    let getCalls = 0;
    const h = initHarness({
        page: 'alt',
        initialWall: 1000000,
        serverFetch: () => {
            getCalls++;
            if (getCalls === 1) {
                return {
                    ok: true,
                    json: () => Promise.resolve(serverStateResponse({
                        status: 'snoozed',
                        canShow: false,
                        retryAfterMs: 20 * 60 * 1000,
                        seenLayouts: LAYOUT_IDS.slice()
                    }))
                };
            }
            return Promise.reject(new Error('server unavailable'));
        }
    });
    return waitForServerContext(h).then(() => {
        const localState = JSON.parse(h.storage.getItem(STATE_KEY));
        eq('localStorage 保存 clientNow + 20 分钟', localState.snoozedUntil, 1000000 + 20 * 60 * 1000);
        h.api.destroy();
        h.api.init(reinitOptions(h, null));
        return waitForFlush();
    }).then(() => {
        h.dispatchFirstDownload();
        return waitForFlush();
    }).then(() => {
        eq('服务器不可用时不提前展示', h.document.querySelectorAll('.plf-backdrop').length, 0);
        const localState = JSON.parse(h.storage.getItem(STATE_KEY));
        eq('fallback 仍剩约 20 分钟（不额外延长）', localState.snoozedUntil, 1000000 + 20 * 60 * 1000);
        // 本地截止到达后：fallback 过期且无 terminal 状态，按 availability 策略继续。
        h.timers.advance(21 * 60 * 1000);
        h.dispatchFirstDownload();
        return waitForFlush().then(() => {
            // reinit 使用真实 script 加载路径：补上 SDK 全局再触发 load。
            h.sandbox.posthog = createFakeAdapter({surveys: [defaultSurvey()]});
            h.fireScriptLoad();
            return waitForFlush();
        });
    }).then(() => {
        eq('本地 fallback 过期后允许展示', h.document.querySelectorAll('.plf-backdrop').length, 1);
    });
}

function testServerTerminalReloadStillBlocks() {
    // server submitted / never：重载且服务端不可用时仍阻断。
    return ['submitted', 'never'].reduce((chain, status) => chain.then(() => {
        let getCalls = 0;
        const serverFetch = () => {
            getCalls++;
            if (getCalls === 1) {
                return {
                    ok: true,
                    json: () => Promise.resolve(serverStateResponse({
                        status,
                        canShow: false,
                        retryAfterMs: 0,
                        seenLayouts: LAYOUT_IDS.slice()
                    }))
                };
            }
            return Promise.reject(new Error('server unavailable'));
        };
        const h = initHarness({
            page: 'alt',
            serverFetch
        });
        return waitForServerContext(h).then(() => {
            const localState = JSON.parse(h.storage.getItem(STATE_KEY));
            eq(status + ' 本地缓存状态保持', localState.status, status);
            h.api.destroy();
            h.api.init(reinitOptions(h, null));
            return waitForFlush();
        }).then(() => {
            h.dispatchFirstDownload();
            return waitForFlush();
        }).then(() => {
            eq(status + ' 重载且服务器不可用时仍阻断',
                h.document.querySelectorAll('.plf-backdrop').length, 0);
        });
    }), Promise.resolve());
}

function testServerSnoozeExpiredClearsLocalSnooze() {
    // server snoozed canShow=true：清理同 Survey 本地 snooze；
    // 不清理本地 submitted / never（终端状态由 effectiveState 强度保证）。
    const h = initHarness({
        batchLayout: 'landscape',
        initialWall: 1000000,
        serverState: serverStateResponse({
            status: 'snoozed',
            canShow: false,
            retryAfterMs: 20 * 60 * 1000,
            seenLayouts: LAYOUT_IDS.slice()
        })
    });
    return waitForServerContext(h).then(() => {
        eq('初始本地有 snooze', JSON.parse(h.storage.getItem(STATE_KEY)).status, 'snoozed');
        h.setServerState(serverStateResponse({
            revision: 1,
            status: 'snoozed',
            canShow: true,
            retryAfterMs: 0,
            seenLayouts: LAYOUT_IDS.slice()
        }));
        return directRefresh(h).then(() => waitForFlush());
    }).then(() => {
        eq('服务端到期后本地 snooze 清理', h.storage.getItem(STATE_KEY), null);
    });
}

function testRepeatedServerSnoozeNoMeaninglessWrites() {
    // 同一服务端 snooze 重复 GET：本地截止时间差在容差内，不产生无意义 localStorage
    // 写入（setStorageIfChanged 去重 + serverViewToLocalState 容差）。
    // 断言必须是精确相等（===），不能用 >= 冒充「没有额外写入」。
    let getCalls = 0;
    let stateKeyBefore = null;
    let setsBeforeRefresh = 0;
    let removesBeforeRefresh = 0;
    let postsBefore = 0;
    const h = initHarness({
        batchLayout: 'landscape',
        initialWall: 1000000,
        serverFetch: () => {
            getCalls++;
            return {
                ok: true,
                json: () => Promise.resolve(serverStateResponse({
                    status: 'snoozed',
                    canShow: false,
                    retryAfterMs: 20 * 60 * 1000,
                    seenLayouts: LAYOUT_IDS.slice()
                }))
            };
        }
    });
    return waitForServerContext(h).then(() => {
        stateKeyBefore = h.storage.getItem(STATE_KEY);
        setsBeforeRefresh = h.storage.setCalls.filter(c => c[0] === STATE_KEY).length;
        removesBeforeRefresh = h.storage.removeCalls.filter(c => c === STATE_KEY).length;
        postsBefore = h.serverPosts.length;
        h.timers.advance(2000);
        return waitForFlush();
    }).then(() => {
        // 第二次 GET（storage 事件触发）返回同一 snooze：不重复写 STATE_KEY。
        h.dispatchStorage(STATE_KEY, JSON.stringify(surveyState('snoozed', 1000000 + 20 * 60 * 1000)));
        return waitForFlush();
    }).then(() => {
        const setsAfterRefresh = h.storage.setCalls.filter(c => c[0] === STATE_KEY).length;
        eq('重复服务端 snooze 零额外 STATE_KEY 写入', setsAfterRefresh, setsBeforeRefresh);
        eq('STATE_KEY 序列化内容完全不变', h.storage.getItem(STATE_KEY), stateKeyBefore);
        const state = JSON.parse(h.storage.getItem(STATE_KEY));
        eq('updatedAt 不变', state.updatedAt, 1000000);
        eq('snoozedUntil 在容差内保留旧值', state.snoozedUntil, 1000000 + 20 * 60 * 1000);
        eq('无额外 STATE_KEY remove', h.storage.removeCalls.filter(c => c === STATE_KEY).length,
            removesBeforeRefresh);
        eq('无额外服务端命令', h.serverPosts.length, postsBefore);
    });
}

function testServerSnoozeDeadlineChangeWritesOnce() {
    // 正向对照：新的 retryAfterMs 使本地截止时间变化超过容差时，恰好增加一次
    // STATE_KEY 写入（截止时间更新），不产生第二次多余写入。
    let getCalls = 0;
    let setsAfterFirstWrite = 0;
    let setsBefore = 0;
    const h = initHarness({
        batchLayout: 'landscape',
        initialWall: 1000000,
        serverFetch: () => {
            getCalls++;
            return {
                ok: true,
                json: () => Promise.resolve(serverStateResponse({
                    revision: 0,
                    status: 'snoozed',
                    canShow: false,
                    retryAfterMs: getCalls === 1 ? 20 * 60 * 1000 : 20 * 60 * 1000 + 10 * 1000,
                    seenLayouts: LAYOUT_IDS.slice()
                }))
            };
        }
    });
    return waitForServerContext(h).then(() => {
        setsBefore = h.storage.setCalls.filter(c => c[0] === STATE_KEY).length;
        h.dispatchStorage(SEEN_KEY, JSON.stringify(seenObject()));
        return waitForFlush();
    }).then(() => {
        const setsAfter = h.storage.setCalls.filter(c => c[0] === STATE_KEY).length;
        eq('截止时间变化超过容差：恰好一次额外 STATE_KEY 写入', setsAfter, setsBefore + 1);
        setsAfterFirstWrite = setsAfter;
        eq('截止时间更新为新值', JSON.parse(h.storage.getItem(STATE_KEY)).snoozedUntil,
            1000000 + 20 * 60 * 1000 + 10 * 1000);
        // 立即再次刷新（同一新值）：不再写入。
        h.dispatchStorage(SEEN_KEY, JSON.stringify(seenObject()));
        return waitForFlush();
    }).then(() => {
        eq('相同新值不再产生第二次多余写入',
            h.storage.setCalls.filter(c => c[0] === STATE_KEY).length, setsAfterFirstWrite);
    });
}

function testSeenLayoutsToLocalSeen() {
    // seenLayouts 转本地 seen：不复制服务端时间戳（本地保留自己的时间戳，
    // 服务端新增而本地没有的布局用当前客户端时间）。
    const h = initHarness({
        batchLayout: 'landscape',
        initialWall: 1000000,
        serverState: serverStateResponse({
            seenLayouts: ['pixiv-batch-landscape', 'pixiv-batch-portrait']
        })
    });
    return waitForServerContext(h).then(() => {
        const localSeen = JSON.parse(h.storage.getItem(SEEN_KEY));
        ok('本地 seen 含服务端布局', localSeen['pixiv-batch-landscape'] && localSeen['pixiv-batch-portrait']);
        eq('服务端新增布局使用当前客户端时间', localSeen['pixiv-batch-portrait'].firstSeenAt, 1000000);
        ok('本地时间戳为客户端时钟域（不是服务端时间）',
            localSeen['pixiv-batch-portrait'].firstSeenAt >= 0);
    });
}

function testNoCasCommandProtocol() {
    // 命令 body 不含 expectedRevision；单次 attempt；snooze 响应 never/submitted 采用
    // 更强状态；never 响应 submitted 成功；record_seen 缺目标布局失败且 pending 保留。
    return Promise.resolve().then(() => {
        const h = initHarness({
            page: 'alt',
            serverState: serverStateResponse({seenLayouts: LAYOUT_IDS.slice()})
        });
        return waitForServerContext(h).then(() => {
            h.dispatchFirstDownload();
            return waitForFlush();
        }).then(() => {
            h.actionButton('snooze').click();
            return waitForFlush();
        }).then(() => {
            const posts = h.serverPosts.filter(p => p.body.command === 'snooze');
            eq('snooze 只发送一次（无 409 重试）', posts.length, 1);
            eq('body 不含 expectedRevision / 时间戳', posts[0].body.expectedRevision === undefined
                && posts[0].body.snoozedUntil === undefined, true);
            eq('snooze 确认后本地为 snoozed', JSON.parse(h.storage.getItem(STATE_KEY)).status, 'snoozed');
        });
    }).then(() => {
        // snooze 响应 never：成功且采用更强状态。
        const h = initHarness({
            page: 'alt',
            serverState: serverStateResponse({seenLayouts: LAYOUT_IDS.slice()}),
            serverPostResponse: ({body}) => {
                if (body.command !== 'snooze') return undefined;
                return {
                    ok: true,
                    json: () => Promise.resolve(serverStateResponse({
                        revision: 1,
                        status: 'never',
                        canShow: false,
                        retryAfterMs: 0,
                        seenLayouts: LAYOUT_IDS.slice()
                    }))
                };
            }
        });
        return waitForServerContext(h).then(() => {
            h.dispatchFirstDownload();
            return waitForFlush();
        }).then(() => {
            h.actionButton('snooze').click();
            return waitForFlush();
        }).then(() => {
            eq('snooze 响应 never：成功并采用更强状态',
                JSON.parse(h.storage.getItem(STATE_KEY)).status, 'never');
        });
    }).then(() => {
        // never 响应 submitted：成功。
        const h = initHarness({
            page: 'alt',
            serverState: serverStateResponse({seenLayouts: LAYOUT_IDS.slice()}),
            serverPostResponse: ({body}) => {
                if (body.command !== 'never') return undefined;
                return {
                    ok: true,
                    json: () => Promise.resolve(serverStateResponse({
                        revision: 1,
                        status: 'submitted',
                        canShow: false,
                        retryAfterMs: 0,
                        seenLayouts: LAYOUT_IDS.slice()
                    }))
                };
            }
        });
        return waitForServerContext(h).then(() => {
            h.dispatchFirstDownload();
            return waitForFlush();
        }).then(() => {
            h.actionButton('never').click();
            return waitForFlush();
        }).then(() => {
            eq('never 响应 submitted：成功并采用更强状态',
                JSON.parse(h.storage.getItem(STATE_KEY)).status, 'submitted');
        });
    }).then(() => {
        // record_seen 缺目标布局：失败；pending 保留。
        const h = initHarness({
            batchLayout: 'landscape',
            serverState: serverStateResponse({seenLayouts: []}),
            serverPostResponse: ({body}) => {
                if (body.command !== 'record_seen') return undefined;
                return {
                    ok: true,
                    json: () => Promise.resolve(serverStateResponse({
                        revision: 1,
                        status: null,
                        canShow: true,
                        retryAfterMs: 0,
                        seenLayouts: []
                    }))
                };
            }
        });
        return waitForServerContext(h).then(() => {
            h.timers.advance(600);
            return waitForFlush();
        }).then(() => {
            const effective = h.api._internals.effectiveSeen();
            ok('record_seen 未被确认：pending 保留',
                h.api._internals.distinctSeenCount(effective) >= 1);
            ok('本地 SEEN_KEY 保留布局', h.storage.getItem(SEEN_KEY) !== null);
        });
    });
}

/* ============================================================
   首次下载完成触发（服务端 snooze / 代际隔离）
============================================================ */

function testTriggerAfterServerSnoozeDeadline() {
    // 服务端 snoozed / canShow=false / retryAfterMs=1000：本地截止未到前触发
    // 不展示、不重新 GET；本地截止过后触发会先重新 GET 权威状态，服务端确认
    // canShow=true 后才进入 SDK；SDK / Survey 网络流程只启动一次；重复事件
    // 不启动第二次。
    let getCalls = 0;
    let gateResolve = null;
    const h = initHarness({
        page: 'alt',
        initialWall: 1000000,
        serverFetch: () => {
            getCalls++;
            if (getCalls === 1) {
                return {
                    ok: true,
                    json: () => Promise.resolve(serverStateResponse({
                        revision: 0,
                        status: 'snoozed',
                        canShow: false,
                        retryAfterMs: 1000,
                        seenLayouts: LAYOUT_IDS.slice()
                    }))
                };
            }
            // 本地截止到达后的权威刷新：在途等待测试确认（模拟服务端延迟响应）。
            return new Promise(resolve => { gateResolve = resolve; });
        }
    });
    return waitForServerContext(h).then(() => {
        // 本地截止（now + 1000）前触发：不展示、不重新 GET、不加载 SDK。
        h.dispatchFirstDownload();
        return waitForFlush();
    }).then(() => {
        eq('本地截止前不展示', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('本地截止前不加载 SDK', h.adapter.calls.getSurveys.length, 0);
        eq('本地截止前不重新 GET', h.stateFetchCount(), 1);
        // 本地截止到达后触发：权威刷新在途。
        h.timers.advance(2000);
        h.dispatchFirstDownload();
        return waitForFlush();
    }).then(() => {
        eq('刷新在途时未展示', h.document.querySelectorAll('.plf-backdrop').length, 0);
        // 服务端确认 canShow=true。
        gateResolve({
            ok: true,
            json: () => Promise.resolve(serverStateResponse({
                revision: 1,
                status: 'snoozed',
                canShow: true,
                retryAfterMs: 0,
                seenLayouts: LAYOUT_IDS.slice()
            }))
        });
        return waitForFlush();
    }).then(() => {
        eq('服务端确认后进入 SDK', h.adapter.calls.getSurveys.length, 1);
        eq('SDK 流程只启动一次', h.adapter.calls.init.length, 1);
        eq('弹窗最多一个', h.document.querySelectorAll('.plf-backdrop').length, 1);
        // 重复事件不启动第二次。
        h.dispatchFirstDownload();
        return waitForFlush();
    }).then(() => {
        eq('重复事件不启动第二次 Survey 流程', h.adapter.calls.getSurveys.length, 1);
    });
}

function testTriggerDestroyDuringFlow() {
    // 触发流程中（权威刷新在途）destroy：迟到响应不重新展示、不加载 SDK、
    // 无残留 timer；destroy 后重新 init 再触发可正常展示。
    let getCalls = 0;
    let gateResolve = null;
    const h = initHarness({
        page: 'alt',
        initialWall: 1000000,
        serverFetch: () => {
            getCalls++;
            if (getCalls === 1) {
                return {
                    ok: true,
                    json: () => Promise.resolve(serverStateResponse({
                        revision: 0,
                        status: 'snoozed',
                        canShow: false,
                        retryAfterMs: 1000,
                        seenLayouts: LAYOUT_IDS.slice()
                    }))
                };
            }
            return new Promise(resolve => { gateResolve = resolve; });
        }
    });
    return waitForServerContext(h).then(() => {
        h.timers.advance(2000);
        h.dispatchFirstDownload();
        return waitForFlush();
    }).then(() => {
        eq('刷新在途：未展示', h.document.querySelectorAll('.plf-backdrop').length, 0);
        h.api.destroy();
        return waitForFlush();
    }).then(() => {
        // 迟到响应到达：不重新展示。
        gateResolve({
            ok: true,
            json: () => Promise.resolve(serverStateResponse({
                revision: 1,
                status: 'snoozed',
                canShow: true,
                retryAfterMs: 0,
                seenLayouts: LAYOUT_IDS.slice()
            }))
        });
        return waitForFlush();
    }).then(() => {
        eq('迟到响应不加载 SDK', h.adapter.calls.getSurveys.length, 0);
        eq('迟到响应不打开弹窗', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('无残留定时器', h.timers.pending().length, 0);
        // destroy 后重新 init 再触发：gen2 的服务端 GET 在途，释放后正常展示。
        h.api.init(reinitOptions(h));
        const promise = h.api.open();
        return waitForFlush().then(() => {
            gateResolve({
                ok: true,
                json: () => Promise.resolve(serverStateResponse({
                    revision: 0,
                    status: null,
                    canShow: true,
                    retryAfterMs: 0,
                    seenLayouts: LAYOUT_IDS.slice()
                }))
            });
            return promise.then(() => waitForFlush());
        });
    }).then(() => {
        eq('destroy 后重新 init 的 open 不受影响', h.document.querySelectorAll('.plf-backdrop').length, 1);
    });
}

function testTriggerProtocolInvalidFailClosed() {
    // 触发后权威刷新返回非法视图：不加载 SDK、不展示；一次性标记已消耗，
    // 重复事件不再重试；手动 open 不被破坏。
    let getCalls = 0;
    const h = initHarness({
        page: 'alt',
        initialWall: 1000000,
        serverFetch: () => {
            getCalls++;
            if (getCalls === 1) {
                return {
                    ok: true,
                    json: () => Promise.resolve(serverStateResponse({
                        revision: 0,
                        status: 'snoozed',
                        canShow: false,
                        retryAfterMs: 1000,
                        seenLayouts: LAYOUT_IDS.slice()
                    }))
                };
            }
            // 非法视图（available=false）。
            return {ok: true, json: () => Promise.resolve({available: false})};
        }
    });
    return waitForServerContext(h).then(() => {
        h.timers.advance(2000);
        h.dispatchFirstDownload();
        return waitForFlush();
    }).then(() => {
        eq('INVALID 不加载 SDK', h.adapter.calls.getSurveys.length, 0);
        eq('INVALID 不展示', h.document.querySelectorAll('.plf-backdrop').length, 0);
        // 一次性标记已消耗：重复事件不重试。
        h.dispatchFirstDownload();
        return waitForFlush();
    }).then(() => {
        eq('INVALID 后不无限重试', h.adapter.calls.getSurveys.length, 0);
        // 手动 open 不受影响。
        return h.api.open().then(() => waitForFlush());
    }).then(() => {
        eq('手动 open 仍可用', h.document.querySelectorAll('.plf-backdrop').length, 1);
    });
}

function testTriggerUnavailableFailOpen() {
    // 服务端暂时不可用 + 本地无阻断状态：触发时允许进入 SDK（fail-open）；
    // 本地存在阻断状态：不进入 SDK、不展示。
    return Promise.resolve().then(() => {
        let getCalls = 0;
        const h = initHarness({
            page: 'alt',
            initialWall: 1000000,
            serverFetch: () => {
                getCalls++;
                if (getCalls === 1) {
                    return {
                        ok: true,
                        json: () => Promise.resolve(serverStateResponse({
                            revision: 0,
                            status: 'snoozed',
                            canShow: false,
                            retryAfterMs: 1000,
                            seenLayouts: LAYOUT_IDS.slice()
                        }))
                    };
                }
                return Promise.reject(new Error('network down'));
            }
        });
        return waitForServerContext(h).then(() => {
            h.timers.advance(2000);
            h.dispatchFirstDownload();
            return waitForFlush();
        }).then(() => {
            eq('unavailable 本地无阻断：允许进入 SDK', h.adapter.calls.getSurveys.length, 1);
            eq('只启动一次', h.adapter.calls.init.length, 1);
            eq('弹窗展示', h.document.querySelectorAll('.plf-backdrop').length, 1);
        });
    }).then(() => {
        // 本地存在阻断状态（另一标签页写入 submitted fallback）：不进入 SDK。
        let getCalls = 0;
        const h = initHarness({
            page: 'alt',
            initialWall: 1000000,
            serverFetch: () => {
                getCalls++;
                if (getCalls === 1) {
                    return {
                        ok: true,
                        json: () => Promise.resolve(serverStateResponse({
                            revision: 0,
                            status: 'snoozed',
                            canShow: false,
                            retryAfterMs: 1000,
                            seenLayouts: LAYOUT_IDS.slice()
                        }))
                    };
                }
                return Promise.reject(new Error('network down'));
            }
        });
        return waitForServerContext(h).then(() => {
            // 另一标签页刚写入 submitted：storage 事件合并进 pendingLocalState。
            h.dispatchStorage(STATE_KEY, JSON.stringify(surveyState('submitted')));
            return waitForFlush();
        }).then(() => {
            h.timers.advance(2000);
            h.dispatchFirstDownload();
            return waitForFlush();
        }).then(() => {
            eq('本地阻断：不进入 SDK', h.adapter.calls.getSurveys.length, 0);
            eq('本地阻断：不展示', h.document.querySelectorAll('.plf-backdrop').length, 0);
        });
    });
}

function testTriggerOldGenerationIsolation() {
    // generation 1 触发流程（权威刷新在途）；destroy → generation 2 init →
    // generation 2 触发；释放 generation 1 的旧 refresh 迟到响应：不得影响
    // generation 2 的流程（不展示、不加载 SDK、无新 warning）；
    // 完成 generation 2 后只启动一次 SDK / Survey 流程。
    let getCalls = 0;
    let gate1Resolve = null;
    let gate2Resolve = null;
    const h = initHarness({
        page: 'alt',
        initialWall: 1000000,
        serverFetch: () => {
            getCalls++;
            if (getCalls === 1 || getCalls === 3) {
                // init GET（gen1 / gen2 各一次）：snoozed 阻断视图。
                return Promise.resolve({
                    ok: true,
                    json: () => Promise.resolve(serverStateResponse({
                        revision: 0,
                        status: 'snoozed',
                        canShow: false,
                        retryAfterMs: 1000,
                        seenLayouts: LAYOUT_IDS.slice()
                    }))
                });
            }
            if (getCalls === 2) {
                // generation 1 的触发权威刷新：保持 pending。
                return new Promise(resolve => { gate1Resolve = resolve; });
            }
            // getCalls === 4：generation 2 的触发权威刷新：保持 pending。
            return new Promise(resolve => { gate2Resolve = resolve; });
        }
    });
    let gen1 = 0;
    let warningsBefore = 0;
    return waitForServerContext(h).then(() => {
        h.timers.advance(1000);
        h.dispatchFirstDownload();
        return waitForFlush();
    }).then(() => {
        gen1 = h.api._internals.currentGeneration();
        h.api.destroy();
        return waitForFlush();
    }).then(() => {
        h.api.init(reinitOptions(h));
        return waitForFlush();
    }).then(() => {
        eq('generation 2 已初始化（generation 递增）',
            h.api._internals.currentGeneration() > gen1, true);
        h.timers.advance(1000);
        h.dispatchFirstDownload();
        return waitForFlush();
    }).then(() => {
        warningsBefore = h.consoleWarn.length;
        // 释放 generation 1 的旧 refresh：canShow=true 迟到响应。
        gate1Resolve({
            ok: true,
            json: () => Promise.resolve(serverStateResponse({
                revision: 1,
                status: null,
                canShow: true,
                retryAfterMs: 0,
                seenLayouts: LAYOUT_IDS.slice()
            }))
        });
        return waitForFlush();
    }).then(() => {
        eq('generation 1 不加载 SDK', h.adapter.calls.getSurveys.length, 0);
        eq('generation 1 不打开弹窗', h.document.querySelectorAll('.plf-backdrop').length, 0);
        eq('generation 1 不输出新 warning', h.consoleWarn.length, warningsBefore);
        // 完成 generation 2 的权威刷新。
        gate2Resolve({
            ok: true,
            json: () => Promise.resolve(serverStateResponse({
                revision: 1,
                status: null,
                canShow: true,
                retryAfterMs: 0,
                seenLayouts: LAYOUT_IDS.slice()
            }))
        });
        return waitForFlush();
    }).then(() => {
        eq('generation 2 完成后只启动一次 SDK / Survey 流程', h.adapter.calls.getSurveys.length, 1);
        eq('弹窗最多一个', h.document.querySelectorAll('.plf-backdrop').length, 1);
        eq('generation 2 没有启动第二条流程', h.adapter.calls.getSurveys.length, 1);
        eq('只有 generation 1 的请求被取消（abort 恰好一次）', h.serverAbortCalls.length, 1);
    });
}

function testTriggerLateResultsNoSideEffects() {
    // generation 1 触发流程在途时 destroy → re-init → generation 2 触发；
    // generation 1 的旧 refresh 迟到响应按三种 payload 释放（blocked 形 /
    // invalid 形 / started 形），都不得影响 generation 2 的流程：
    // 不展示、不加载 SDK、不输出新 warning；随后完成 generation 2 只启动一次流程。
    const buildHarness = () => {
        let getCalls = 0;
        let gate1Resolve = null;
        let gate2Resolve = null;
        const h = initHarness({
            page: 'alt',
            initialWall: 1000000,
            serverFetch: () => {
                getCalls++;
                if (getCalls === 1 || getCalls === 3) {
                    return Promise.resolve({
                        ok: true,
                        json: () => Promise.resolve(serverStateResponse({
                            revision: 0,
                            status: 'snoozed',
                            canShow: false,
                            retryAfterMs: 1000,
                            seenLayouts: LAYOUT_IDS.slice()
                        }))
                    });
                }
                if (getCalls === 2) {
                    return new Promise(resolve => { gate1Resolve = resolve; });
                }
                return new Promise(resolve => { gate2Resolve = resolve; });
            }
        });
        return {h, gate1: () => gate1Resolve, gate2: () => gate2Resolve};
    };
    const runCase = (lateView) => {
        const built = buildHarness();
        const h = built.h;
        let warningsBefore = 0;
        return waitForServerContext(h).then(() => {
            h.timers.advance(1000);
            h.dispatchFirstDownload();
            return waitForFlush();
        }).then(() => {
            h.api.destroy();
            return waitForFlush();
        }).then(() => {
            h.api.init(reinitOptions(h));
            return waitForFlush();
        }).then(() => {
            h.timers.advance(1000);
            h.dispatchFirstDownload();
            return waitForFlush();
        }).then(() => {
            warningsBefore = h.consoleWarn.length;
            built.gate1()(lateView);
            return waitForFlush();
        }).then(() => {
            eq('迟到响应不展示', h.document.querySelectorAll('.plf-backdrop').length, 0);
            eq('迟到响应不加载 SDK', h.adapter.calls.getSurveys.length, 0);
            eq('迟到响应不输出新 warning', h.consoleWarn.length, warningsBefore);
            built.gate2()({
                ok: true,
                json: () => Promise.resolve(serverStateResponse({
                    revision: 1,
                    status: null,
                    canShow: true,
                    retryAfterMs: 0,
                    seenLayouts: LAYOUT_IDS.slice()
                }))
            });
            return waitForFlush();
        }).then(() => {
            eq('generation 2 完成后只启动一次流程', h.adapter.calls.getSurveys.length, 1);
            eq('弹窗最多一个', h.document.querySelectorAll('.plf-backdrop').length, 1);
        });
    };
    const lateViews = [
        // A. blocked 形：迟到 blocked 不得影响 generation 2。
        {
            ok: true,
            json: () => Promise.resolve(serverStateResponse({
                revision: 1, status: 'snoozed', canShow: false, retryAfterMs: 7000,
                seenLayouts: LAYOUT_IDS.slice()
            }))
        },
        // B. invalid 形：迟到 invalid 不得影响 generation 2。
        {ok: true, json: () => Promise.resolve({available: false})},
        // D. started 形：迟到 opened/started 不得影响 generation 2。
        {
            ok: true,
            json: () => Promise.resolve(serverStateResponse({
                revision: 1, status: null, canShow: true, retryAfterMs: 0,
                seenLayouts: LAYOUT_IDS.slice()
            }))
        }
    ];
    return lateViews.reduce((chain, view) => chain.then(() => runCase(view)),
        Promise.resolve());
}

function testRevisionSafeIntegerBoundary() {
    // revision 边界：Number.MAX_SAFE_INTEGER 合法；
    // MAX_SAFE_INTEGER+1 / 非整数 / Infinity / NaN / string → VIEW_INVALID。
    const MAX_SAFE = Number.MAX_SAFE_INTEGER;
    const withRevision = (revision) => {
        const h = initHarness({
            page: 'alt',
            serverState: serverStateResponse({revision})
        });
        return h.api.open().then(() => waitForFlush()).then(() => h);
    };
    return withRevision(MAX_SAFE).then(h => {
        ok('revision=Number.MAX_SAFE_INTEGER 合法',
            !!(h.adapter.sdkConfig() && h.adapter.sdkConfig().bootstrap));
    }).then(() => withRevision(MAX_SAFE + 1)).then(h => {
        eq('revision=MAX_SAFE_INTEGER+1 → VIEW_INVALID', h.adapter.sdkConfig().bootstrap === undefined, true);
    }).then(() => withRevision(1.5)).then(h => {
        eq('revision=非整数 → VIEW_INVALID', h.adapter.sdkConfig().bootstrap === undefined, true);
    }).then(() => withRevision(Infinity)).then(h => {
        eq('revision=Infinity → VIEW_INVALID', h.adapter.sdkConfig().bootstrap === undefined, true);
    }).then(() => withRevision(NaN)).then(h => {
        eq('revision=NaN → VIEW_INVALID', h.adapter.sdkConfig().bootstrap === undefined, true);
    }).then(() => withRevision('5')).then(h => {
        eq('revision=string → VIEW_INVALID', h.adapter.sdkConfig().bootstrap === undefined, true);
    });
}

/* ============================================================
   retryAfterMs 安全整数边界
============================================================ */

function testRetryAfterMsSafeIntegerBoundary() {
    // retryAfterMs 边界：0 / 1 / Number.MAX_SAFE_INTEGER 合法（合法最大值进入
    // safeClientTimeAdd 后仍为安全整数，不视为协议错误）；
    // MAX_SAFE_INTEGER+1 / 1.5 / -1 / Infinity / NaN / '1000' → VIEW_INVALID；
    // 非法响应不得修改 serverRevision / serverLocalBlockUntil、不启动 SDK。
    const MAX_SAFE = Number.MAX_SAFE_INTEGER;
    const viewFor = (retryAfterMs) => retryAfterMs === 0
        ? {status: null, canShow: true, retryAfterMs: 0, seenLayouts: LAYOUT_IDS.slice()}
        : {status: 'snoozed', canShow: false, retryAfterMs, seenLayouts: LAYOUT_IDS.slice()};
    const withRetryAfter = (retryAfterMs, manualOpen) => {
        const h = initHarness({
            page: 'alt',
            initialWall: 1000000,
            serverState: serverStateResponse(Object.assign({revision: 3}, viewFor(retryAfterMs)))
        });
        return (manualOpen ? h.api.open().then(() => waitForFlush()) : waitForServerContext(h))
            .then(() => h);
    };
    return withRetryAfter(0, true).then(h => {
        eq('retryAfterMs=0 合法（status=null + canShow=true）', h.adapter.sdkConfig() !== null, true);
        ok('合法视图应用 bootstrap 身份',
            !!(h.adapter.sdkConfig().bootstrap && h.adapter.sdkConfig().bootstrap.distinctID));
        eq('retryAfterMs=0 不产生阻断截止', h.api._internals.serverLocalBlockUntil(), 0);
    }).then(() => withRetryAfter(1, true)).then(h => {
        eq('retryAfterMs=1 合法', h.adapter.sdkConfig() !== null, true);
        eq('serverRevision 已应用', h.api._internals.currentServerRevision(), 3);
        eq('serverLocalBlockUntil = now + 1', h.api._internals.serverLocalBlockUntil(), 1000000 + 1);
    }).then(() => withRetryAfter(MAX_SAFE, true)).then(h => {
        eq('retryAfterMs=Number.MAX_SAFE_INTEGER 合法', h.adapter.sdkConfig() !== null, true);
        const until = h.api._internals.serverLocalBlockUntil();
        ok('合法最大值进入 safeClientTimeAdd 后仍为安全整数',
            Number.isSafeInteger(until) && until >= 0 && until <= MAX_SAFE);
    }).then(() => withRetryAfter(MAX_SAFE + 1, false)).then(h => {
        eq('retryAfterMs=MAX_SAFE_INTEGER+1 → VIEW_INVALID', h.adapter.sdkConfig(), null);
        eq('非法响应不修改 serverRevision', h.api._internals.currentServerRevision(), 0);
        eq('非法响应不修改 serverLocalBlockUntil', h.api._internals.serverLocalBlockUntil(), 0);
        eq('非法响应不启动 SDK', h.adapter.calls.init.length, 0);
    }).then(() => withRetryAfter(1.5, false)).then(h => {
        eq('retryAfterMs=1.5 → VIEW_INVALID', h.adapter.sdkConfig(), null);
    }).then(() => withRetryAfter(-1, false)).then(h => {
        eq('retryAfterMs=-1 → VIEW_INVALID', h.adapter.sdkConfig(), null);
    }).then(() => withRetryAfter(Infinity, false)).then(h => {
        eq('retryAfterMs=Infinity → VIEW_INVALID', h.adapter.sdkConfig(), null);
    }).then(() => withRetryAfter(NaN, false)).then(h => {
        eq('retryAfterMs=NaN → VIEW_INVALID', h.adapter.sdkConfig(), null);
    }).then(() => withRetryAfter('1000', false)).then(h => {
        eq("retryAfterMs='1000' → VIEW_INVALID", h.adapter.sdkConfig(), null);
    });
}

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
    await step('testSubmittedNeverSnoozedGatesTrigger', testSubmittedNeverSnoozedGatesTrigger);
    await step('testCorruptStateIsCleaned', testCorruptStateIsCleaned);
    await step('testCorruptStateRemoveThrowsStillSafe', testCorruptStateRemoveThrowsStillSafe);
    await step('testStorageThrowSafe', testStorageThrowSafe);
    await step('testCrossTabStorageSync', testCrossTabStorageSync);
    await step('testSdkLoadFailure', testSdkLoadFailure);
    await step('testSdkLoadSuccessThroughScript', testSdkLoadSuccessThroughScript);
    await step('testSdkLoadTimeout', testSdkLoadTimeout);
    await step('testFlagsTimeout', testFlagsTimeout);
    await step('testSurveyFetchTimeout', testSurveyFetchTimeout);
    await step('testPreloadWarmsSdkBeforeFirstDownload', testPreloadWarmsSdkBeforeFirstDownload);
    await step('testPreloadLoadsSdkScriptEarly', testPreloadLoadsSdkScriptEarly);
    await step('testPreloadSkipsSdkInitWhenStateBlocks', testPreloadSkipsSdkInitWhenStateBlocks);
    await step('testPreloadBeforeInitIsNoop', testPreloadBeforeInitIsNoop);
    await step('testDisabledConfigDoesNothing', testDisabledConfigDoesNothing);
    await step('testMissingPostHogPluginTreatedAsDisabled', testMissingPostHogPluginTreatedAsDisabled);
    await step('testFirstDownloadTriggerConditions', testFirstDownloadTriggerConditions);
    await step('testTriggerBlockedOverlaySkipsThenAllows', testTriggerBlockedOverlaySkipsThenAllows);
    await step('testSeenRecording', testSeenRecording);
    await step('testLanguageSwitchPreservesInput', testLanguageSwitchPreservesInput);
    await step('testReducedMotionAndA11yBasics', testReducedMotionAndA11yBasics);
    await step('testCurrentLayoutBadge', testCurrentLayoutBadge);
    await step('testCaptureResultAcceptanceMatrix', testCaptureResultAcceptanceMatrix);
    await step('testBeforeSendTopLevelFields', testBeforeSendTopLevelFields);
    await step('testSdkInitCapturesConfigForBeforeSend', testSdkInitCapturesConfigForBeforeSend);
    await step('testDntGateSilentSkip', testDntGateSilentSkip);
    await step('testIsCapturingFalseSilentSkip', testIsCapturingFalseSilentSkip);
    await step('testDntGateTriggerSilent', testDntGateTriggerSilent);
    await step('testDntGateNormalCapturingStillShows', testDntGateNormalCapturingStillShows);
    await step('testFirstDownloadTriggersOnce', testFirstDownloadTriggersOnce);
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
    await step('testIdentityMismatchFailsClosed', testIdentityMismatchFailsClosed);
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
    await step('testServerModeSubmittedStateGatesTrigger', testServerModeSubmittedStateGatesTrigger);
    await step('testServerModeSubmitPersistsToServer', testServerModeSubmitPersistsToServer);
    await step('testServerModeSnoozeAndNeverPersist', testServerModeSnoozeAndNeverPersist);
    await step('testServerModeSeenRecordsServerSide', testServerModeSeenRecordsServerSide);
    await step('testServerModeUnavailableFallsBackToLocal', testServerModeUnavailableFallsBackToLocal);
    await step('testServerGetUrlCarriesEncodedSurveyId', testServerGetUrlCarriesEncodedSurveyId);
    await step('testServerBackedStateAndSeenFromAuthoritativeSnapshot', testServerBackedStateAndSeenFromAuthoritativeSnapshot);
    await step('testServerModeSubmitPreflightBlocksOnFreshServerState', testServerModeSubmitPreflightBlocksOnFreshServerState);
    await step('testServerModeSubmitPreflightNeverAndSnooze', testServerModeSubmitPreflightNeverAndSnooze);
    await step('testServerModePreflightAllowsCaptureThenSendsSubmitted', testServerModePreflightAllowsCaptureThenSendsSubmitted);
    await step('testServerCommandNetworkFailureSafeDegrade', testServerCommandNetworkFailureSafeDegrade);
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
    await step('testApplyServerViewRejectsInvalidShapes', testApplyServerViewRejectsInvalidShapes);
    await step('testSnapshotRevisionMonotonic', testSnapshotRevisionMonotonic);
    await step('testSnapshotSameRevisionPersistentContent', testSnapshotSameRevisionPersistentContent);
    await step('testSameRevisionDynamicViewUpdate', testSameRevisionDynamicViewUpdate);
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
    await step('testServerViewToLocalState', testServerViewToLocalState);
    await step('testWriteStateIdempotentAcrossTime', testWriteStateIdempotentAcrossTime);
    await step('testDismissedIdempotentAcrossTime', testDismissedIdempotentAcrossTime);
    await step('testServerSnoozeExpiryByRetryAfterMs', testServerSnoozeExpiryByRetryAfterMs);
    await step('testLocalStorageFallbackAcrossReload', testLocalStorageFallbackAcrossReload);
    await step('testServerTerminalReloadStillBlocks', testServerTerminalReloadStillBlocks);
    await step('testServerSnoozeExpiredClearsLocalSnooze', testServerSnoozeExpiredClearsLocalSnooze);
    await step('testRepeatedServerSnoozeNoMeaninglessWrites', testRepeatedServerSnoozeNoMeaninglessWrites);
    await step('testServerSnoozeDeadlineChangeWritesOnce', testServerSnoozeDeadlineChangeWritesOnce);
    await step('testSeenLayoutsToLocalSeen', testSeenLayoutsToLocalSeen);
    await step('testNoCasCommandProtocol', testNoCasCommandProtocol);
    await step('testTriggerAfterServerSnoozeDeadline', testTriggerAfterServerSnoozeDeadline);
    await step('testTriggerDestroyDuringFlow', testTriggerDestroyDuringFlow);
    await step('testTriggerProtocolInvalidFailClosed', testTriggerProtocolInvalidFailClosed);
    await step('testTriggerUnavailableFailOpen', testTriggerUnavailableFailOpen);
    await step('testRevisionSafeIntegerBoundary', testRevisionSafeIntegerBoundary);
    await step('testTriggerOldGenerationIsolation', testTriggerOldGenerationIsolation);
    await step('testTriggerLateResultsNoSideEffects', testTriggerLateResultsNoSideEffects);
    await step('testRetryAfterMsSafeIntegerBoundary', testRetryAfterMsSafeIntegerBoundary);
    console.log(`\npixiv-layout-feedback.test.js: ${passed} assertions passed ✓`);
}

let currentTest = '';

run().catch(error => {
    console.error(error && error.stack ? error.stack : error);
    process.exitCode = 1;
});
