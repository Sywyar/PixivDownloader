'use strict';

/** 布局调查测试使用的最小 DOM、存储、定时器与 PostHog adapter。 */

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
    const calls = {init: [], capture: [], onFeatureFlags: [], getSurveys: [], getAllSurveys: [], results: []};
    let flagsListener = null;
    let sdkConfig = null;
    let sdkDistinctId = null;
    const adapter = {
        calls,
        ackOk: !['throw', 'undefined', 'null', 'false', 'reject'].includes(overrides.capture),
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
        },
        getSurveys(cb, forceReload) {
            calls.getAllSurveys.push({forceReload});
            if (overrides.stallPublishedSurveys) return;
            if (overrides.surveyLoadFailed) {
                cb([], {isLoaded: false, error: 'unavailable'});
                return;
            }
            cb(overrides.publishedSurveys !== undefined
                ? overrides.publishedSurveys
                : (adapter.surveys || []), {isLoaded: true});
        }
    };
    return adapter;
}

module.exports = {
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
};
