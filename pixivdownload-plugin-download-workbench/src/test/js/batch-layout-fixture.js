'use strict';
/*
 * 下载页布局控制器运行态契约测试。
 *
 * 无浏览器 / 无 jsdom：用 Node vm + 最小 DOM / EventTarget / localStorage 加载真实生产
 * batch-layout.js，覆盖声明式多布局、两种单布局、零布局和存储异常矩阵。
 *
 * 运行：node pixivdownload-plugin-download-workbench/src/test/js/batch-layout.test.js
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const SOURCE_PATH = path.join(__dirname, '..', '..', 'main', 'resources', 'static', 'pixiv-batch',
    'batch-layout.js');
const HTML_PATH = path.join(__dirname, '..', '..', 'main', 'resources', 'static', 'pixiv-batch.html');
const SOURCE = fs.readFileSync(SOURCE_PATH, 'utf8');
const HTML = fs.readFileSync(HTML_PATH, 'utf8');
const CSS = Array.from(HTML.matchAll(/<link\b[^>]*\bhref="(\/pixiv-batch\/[^"?]+\.css)"/g),
    ([, href]) => fs.readFileSync(path.join(path.dirname(HTML_PATH), href), 'utf8')).join('\n');
const STORAGE_KEY = 'pixiv:batch-layout:v1';
const ACTION_IDS = ['btn-start', 'btn-pause', 'btn-retry', 'btn-export', 'btn-export-failed', 'btn-clear'];
const API_FUNCTIONS = [
    'availableLayouts', 'defaultLayout', 'normalizeLayout', 'readStoredLayout',
    'applyLayout', 'applyStoredLayout', 'toggleLayout', 'bindLayoutToggle',
    'refreshLayoutToggle', 'currentLayout'
];


class MiniEventTarget {
    constructor() {
        this.listeners = new Map();
    }
    addEventListener(type, listener) {
        if (typeof listener !== 'function') return;
        const list = this.listeners.get(type) || [];
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
        if (!Object.prototype.hasOwnProperty.call(event, 'target')) event.target = this;
        event.currentTarget = this;
        (this.listeners.get(event.type) || []).slice().forEach(listener => listener.call(this, event));
        return !event.defaultPrevented;
    }
    listenerCount(type) {
        return (this.listeners.get(type) || []).length;
    }
}

function dataAttributeName(property) {
    return 'data-' + String(property).replace(/[A-Z]/g, ch => '-' + ch.toLowerCase());
}

class MiniElement extends MiniEventTarget {
    constructor(tagName) {
        super();
        this.tagName = String(tagName).toUpperCase();
        this.attributes = {};
        this.children = [];
        this.parentNode = null;
        this.ownerDocument = null;
        this.hidden = false;
        this.disabled = false;
        this._textContent = '';
        this.style = {};
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
    get title() { return this.getAttribute('title') || ''; }
    set title(value) { this.setAttribute('title', value); }
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
        if (child.ownerDocument && child.ownerDocument.activeElement === child) {
            child.ownerDocument.activeElement = null;
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
        if (selector === 'link[data-batch-layout-style]') {
            return this.tagName === 'LINK' && this.hasAttribute('data-batch-layout-style');
        }
        const attributeOnly = /^\[([a-zA-Z0-9_-]+)]$/.exec(selector);
        if (attributeOnly) return this.hasAttribute(attributeOnly[1]);
        if (selector.startsWith('#')) return this.id === selector.slice(1);
        if (selector.startsWith('.')) {
            return this.className.split(/\s+/).filter(Boolean).includes(selector.slice(1));
        }
        return this.tagName.toLowerCase() === selector.toLowerCase();
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
}

class MiniStorage {
    constructor(seed) {
        this.values = new Map(Object.entries(seed || {}));
        this.getCalls = [];
        this.setCalls = [];
        this.removeCalls = [];
        this.throwOnGet = false;
        this.throwOnSet = false;
        this.throwOnRemove = false;
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
    seed(key, value) {
        if (value == null) this.values.delete(String(key));
        else this.values.set(String(key), value);
    }
    accessCount() {
        return this.getCalls.length + this.setCalls.length + this.removeCalls.length;
    }
}

function hasOwn(object, property) {
    return Object.prototype.hasOwnProperty.call(object, property);
}

function buildDocument(options) {
    const layouts = (options.layouts || []).slice();
    const html = new MiniElement('html');
    const head = new MiniElement('head');
    const body = new MiniElement('body');
    const button = new MiniElement('button');
    const label = new MiniElement('span');
    const business = new MiniElement('section');

    const initialLayout = hasOwn(options, 'initialLayout') ? options.initialLayout : layouts[0];
    const declaredDefault = hasOwn(options, 'defaultLayout') ? options.defaultLayout : layouts[0];
    if (initialLayout !== null && initialLayout !== undefined) {
        html.setAttribute('data-batch-layout', initialLayout);
    }
    if (declaredDefault !== null && declaredDefault !== undefined) {
        html.setAttribute('data-batch-layout-default', declaredDefault);
    }
    html.setAttribute('data-sentinel', 'keep');
    button.id = 'batch-layout-toggle';
    button.setAttribute('type', 'button');
    button.hidden = true;
    label.className = 'batch-layout-toggle-label';
    business.id = 'business-sentinel';
    business.setAttribute('data-state', 'keep');

    const documentEvents = new MiniEventTarget();
    const layoutChangeEvents = [];

    const document = {
        documentElement: html,
        head,
        body,
        activeElement: null,
        documentEvents,
        layoutChangeEvents,
        addEventListener(type, listener) {
            documentEvents.addEventListener(type, listener);
        },
        removeEventListener(type, listener) {
            documentEvents.removeEventListener(type, listener);
        },
        dispatchEvent(event) {
            const result = documentEvents.dispatchEvent(event);
            if (event && event.type === 'pixiv:batch-layout-changed') {
                layoutChangeEvents.push(event.detail || null);
            }
            return result;
        },
        createElement(tagName) {
            const element = new MiniElement(tagName);
            element.ownerDocument = document;
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
        }
    };

    [html, head, body, button, label, business].forEach(element => { element.ownerDocument = document; });
    html.appendChild(head);
    html.appendChild(body);
    button.appendChild(label);
    body.appendChild(button);
    body.appendChild(business);

    const actions = new Map();
    const origins = new Map();
    const originalParents = new Map();
    let actionHost = null;
    let dashRun = null;
    let wbActions = null;
    let moreMenu = null;
    let morePanel = null;
    if (options.actionProjection !== false) {
        dashRun = document.createElement('div');
        dashRun.className = 'dash-run';
        const status = document.createElement('span');
        status.id = 'status-bar';
        dashRun.appendChild(status);

        wbActions = document.createElement('div');
        wbActions.className = 'wb-actions';
        moreMenu = document.createElement('details');
        moreMenu.className = 'more-menu';
        moreMenu.open = true;
        const moreSummary = document.createElement('summary');
        moreSummary.className = 'more-summary';
        morePanel = document.createElement('div');
        morePanel.className = 'more-menu-panel';
        moreMenu.appendChild(moreSummary);
        moreMenu.appendChild(morePanel);

        actionHost = document.createElement('div');
        actionHost.setAttribute('data-batch-layout-action-host', options.actionHostToken || 'classic');
        actionHost.setAttribute('data-batch-layout-action-order',
            (options.actionOrder || ACTION_IDS).join(' '));
        actionHost.hidden = false;

        function addAction(parent, id) {
            if (options.missingActionOrigin !== id) {
                const origin = document.createElement('template');
                origin.setAttribute('data-batch-layout-action-origin', id);
                parent.appendChild(origin);
                origins.set(id, origin);
            }
            const action = document.createElement('button');
            action.id = id;
            action.setAttribute('data-state', id + '-state');
            if (id === 'btn-pause') action.disabled = true;
            parent.appendChild(action);
            actions.set(id, action);
            originalParents.set(id, parent);
        }

        addAction(dashRun, 'btn-start');
        addAction(dashRun, 'btn-pause');
        addAction(wbActions, 'btn-retry');
        addAction(wbActions, 'btn-clear');
        addAction(morePanel, 'btn-export');
        addAction(morePanel, 'btn-export-failed');
        wbActions.appendChild(moreMenu);
        business.appendChild(dashRun);
        business.appendChild(wbActions);
        business.appendChild(actionHost);
    }

    function setLayouts(tokens) {
        head.children.forEach(child => { child.parentNode = null; });
        head.children = [];
        (tokens || []).forEach((token, index) => {
            const link = document.createElement('link');
            link.setAttribute('rel', 'stylesheet');
            link.setAttribute('href', '/layout-' + index + '.css');
            link.setAttribute('data-batch-layout-style', token);
            head.appendChild(link);
        });
    }
    setLayouts(layouts);
    return {
        document, html, head, body, button, label, business, setLayouts,
        documentEvents, layoutChangeEvents,
        actions, origins, originalParents, actionHost, dashRun, wbActions, moreMenu, morePanel
    };
}

function createHarness(options) {
    options = options || {};
    const dom = buildDocument(options);
    const storage = new MiniStorage(options.storage || {});
    storage.throwOnGet = !!options.throwOnGet;
    storage.throwOnSet = !!options.throwOnSet;
    storage.throwOnRemove = !!options.throwOnRemove;
    const windowEvents = new MiniEventTarget();
    const calls = {
        reload: 0,
        fetch: 0,
        init: 0,
        renderQueue: 0,
        startDownload: 0,
        switchMode: 0,
        state: 0,
        serverState: 0,
        appMode: 0,
        isAdmin: 0
    };
    const translationCalls = [];
    const messages = Object.assign({
        'layout.switch-to-classic': '切换到经典布局',
        'layout.switch-to-workbench': '切换到工作台布局'
    }, options.messages || {});

    function translate(key, fallback) {
        const normalized = String(key || '').replace(/^batch[:.]/, '');
        translationCalls.push(normalized);
        return hasOwn(messages, normalized)
            ? messages[normalized]
            : (fallback == null ? String(key) : String(fallback));
    }

    const sandbox = {
        document: dom.document,
        localStorage: storage,
        console: {warn() {}, log() {}, error() {}},
        location: {reload() { calls.reload++; }},
        fetch() { calls.fetch++; return Promise.reject(new Error('business fetch must not run')); },
        init() { calls.init++; },
        renderQueue() { calls.renderQueue++; },
        startDownload() { calls.startDownload++; },
        switchMode() { calls.switchMode++; },
        bt: translate,
        PixivBatch: {sentinel: {preserved: true}},
        Map,
        Set,
        Proxy,
        Promise
    };
    sandbox.window = sandbox;
    sandbox.self = sandbox;
    sandbox.addEventListener = windowEvents.addEventListener.bind(windowEvents);
    sandbox.removeEventListener = windowEvents.removeEventListener.bind(windowEvents);
    sandbox.dispatchEvent = windowEvents.dispatchEvent.bind(windowEvents);
    for (const name of ['state', 'serverState', 'appMode', 'isAdmin']) {
        Object.defineProperty(sandbox, name, {
            configurable: true,
            get() { calls[name]++; return undefined; }
        });
    }

    vm.createContext(sandbox);
    vm.runInContext(SOURCE, sandbox, {filename: 'batch-layout.js'});
    return {
        api: sandbox.PixivBatch && sandbox.PixivBatch.layout,
        sandbox,
        storage,
        messages,
        translationCalls,
        calls,
        dom,
        windowEvents,
        dispatchStorage(key, newValue) {
            windowEvents.dispatchEvent({type: 'storage', key, newValue, storageArea: storage});
        },
        resetBusinessCalls() {
            Object.keys(calls).forEach(key => { calls[key] = 0; });
        }
    };
}

function rootLayout(harness) {
    return harness.dom.html.getAttribute('data-batch-layout');
}

function buttonState(harness) {
    const button = harness.dom.document.getElementById('batch-layout-toggle');
    const label = button.querySelector('.batch-layout-toggle-label');
    return {
        button,
        label,
        hidden: button.hidden,
        disabled: button.disabled,
        text: label ? label.textContent : '',
        title: button.title,
        aria: button.getAttribute('aria-label'),
        layout: button.getAttribute('data-layout'),
        target: button.getAttribute('data-layout-target'),
        textKey: label && label.getAttribute('data-i18n'),
        titleKey: button.getAttribute('data-i18n-title'),
        ariaKey: button.getAttribute('data-i18n-aria-label')
    };
}

function businessCallCount(calls) {
    return Object.keys(calls).reduce((sum, key) => sum + calls[key], 0);
}

function actionIdsIn(parent) {
    return (parent && parent.children ? parent.children : [])
        .filter(child => ACTION_IDS.includes(child.id))
        .map(child => child.id);
}

function actionOccurrenceCount(root, target) {
    let count = 0;
    (function walk(node) {
        if (node === target) count++;
        (node.children || []).forEach(walk);
    })(root);
    return count;
}

function actionPlacementSnapshot(harness) {
    return ACTION_IDS.map(id => {
        const node = harness.dom.actions.get(id);
        return {id, node, parent: node.parentNode, index: node.parentNode.children.indexOf(node)};
    });
}

function actionPlacementsMatch(snapshot) {
    return snapshot.every(item => item.node.parentNode === item.parent
        && item.parent.children.indexOf(item.node) === item.index);
}

function actionsAreAtOrigins(harness) {
    return ACTION_IDS.every(id => {
        const origin = harness.dom.origins.get(id);
        return origin && origin.nextElementSibling === harness.dom.actions.get(id);
    });
}

module.exports = {
    HTML,
    CSS,
    STORAGE_KEY,
    ACTION_IDS,
    API_FUNCTIONS,
    createHarness,
    rootLayout,
    buttonState,
    businessCallCount,
    actionIdsIn,
    actionOccurrenceCount,
    actionPlacementSnapshot,
    actionPlacementsMatch,
    actionsAreAtOrigins
};
