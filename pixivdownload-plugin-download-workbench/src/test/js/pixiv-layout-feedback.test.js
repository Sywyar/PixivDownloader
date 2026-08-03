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
    const calls = {init: [], capture: [], onFeatureFlags: [], getSurveys: []};
    let flagsListener = null;
    const adapter = {
        calls,
        surveys: overrides.surveys || [],
        emitFlags() {
            if (flagsListener) flagsListener();
        },
        init(token, config) {
            calls.init.push({token, config});
        },
        capture(name, properties) {
            calls.capture.push({name, properties});
            if (overrides.capture === 'throw') throw new Error('capture failed');
            if (overrides.capture === 'reject') return false;
            return true;
        },
        onFeatureFlags(cb) {
            calls.onFeatureFlags.push(cb);
            flagsListener = cb;
            if (!overrides.stallFlags) {
                Promise.resolve().then(() => {
                    if (flagsListener === cb) cb();
                });
            }
            return function off() {
                if (flagsListener === cb) flagsListener = null;
            };
        },
        getActiveMatchingSurveys(cb, forceReload) {
            calls.getSurveys.push({forceReload});
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

    const defaultConfig = {
        enabled: true,
        projectToken: 'phc_test_project_token',
        surveyId: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
        apiHost: 'https://proxy.example.com',
        uiHost: 'https://us.i.posthog.com'
    };
    const publicConfig = Object.assign({}, defaultConfig, options.publicConfig || {});

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
            if (options.fetch === 'fail') {
                return Promise.reject(new Error('network down'));
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
        CustomEvent: MiniCustomEvent
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
        toastCalls,
        consoleWarn,
        windowEvents,
        config: publicConfig,
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
            eq('maxlength 属性为 1000', h3.textarea().getAttribute('maxlength'), String(SUGGESTION_MAX));
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
            h4.submitButton().click();
            return waitForFlush();
        }).then(() => {
            eq('超过 1000 字不发送', captureEvents(h4).filter(e => e === 'survey sent').length, 0);
            eq('超长显示错误', h4.error().hidden, false);
            eq('超长弹窗未关闭', h4.document.querySelectorAll('.plf-backdrop').length, 1);
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
        eq('heatmap 关闭', c.enable_heatmaps, false);
        eq('error tracking 关闭', c.capture_exceptions, false);
        eq('web vitals 关闭', c.capture_performance, false);
        eq('dead clicks 关闭', c.capture_dead_clicks, false);
        eq('surveys 保持启用', c.disable_surveys, false);
        eq('person_profiles 不创建匿名 Person', c.person_profiles, 'identified_only');
        eq('persistence 使用 localStorage', c.persistence, 'localStorage');
        eq('cross_subdomain_cookie 关闭', c.cross_subdomain_cookie, false);
        eq('DNT 尊重', c.respect_dnt, true);
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
    h.timers.advance(11000);
    return waitForFlush().then(() => {
        eq('损坏状态安全清理后仍可展示', h.document.querySelectorAll('.plf-backdrop').length, 1);
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
    const h = initHarness({batchLayout: 'landscape'});
    return h.api.open().then(() => waitForFlush()).then(() => {
        selectChoice(h, 'pixiv-batch-alt');
        h.actionButton('never').click();
        return waitForFlush();
    }).then(() => {
        const h2 = initHarness({storage: h.storage, batchLayout: 'landscape'});
        h2.dispatchStorage(STATE_KEY, JSON.stringify({
            surveyId: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee', status: 'submitted',
            updatedAt: 999, snoozedUntil: 0
        }));
        h2.timers.advance(11000);
        return waitForFlush().then(() => {
            eq('storage 事件同步后不展示', h2.document.querySelectorAll('.plf-backdrop').length, 0);
        });
    });
}

/* ============================================================
   SDK / flags / survey 超时（35-37, 58-60）
============================================================ */

function testSdkLoadFailure() {
    const h = initHarness({adapter: null, batchLayout: 'landscape'});
    const promise = h.api.open();
    h.fireScriptError();
    return promise.then(() => waitForFlush()).then(() => {
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
    h.sandbox.posthog = h.adapter ? null : createFakeAdapter({surveys: [defaultSurvey()]});
    h.fireScriptLoad();
    return promise.then(() => waitForFlush()).then(() => {
        eq('SDK 加载成功后走真实 posthog 全局', h.document.querySelectorAll('.plf-backdrop').length, 1);
    });
}

function testSdkLoadTimeout() {
    const h = initHarness({adapter: null, batchLayout: 'landscape'});
    const promise = h.api.open();
    h.timers.advance(15000);
    return promise.then(() => waitForFlush()).then(() => {
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
   入口
============================================================ */

async function run() {
    const step = async (name, fn) => {
        currentTest = name;
        await fn();
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
    console.log(`\npixiv-layout-feedback.test.js: ${passed} assertions passed ✓`);
}

let currentTest = '';

run().catch(error => {
    console.error(error && error.stack ? error.stack : error);
    process.exitCode = 1;
});
