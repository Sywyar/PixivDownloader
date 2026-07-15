'use strict';
/*
 * 下载页布局控制器运行态契约测试。
 *
 * 无浏览器 / 无 jsdom：用 Node vm + 最小 DOM / EventTarget / localStorage 加载真实生产
 * batch-layout.js，覆盖声明式多布局、两种单布局、零布局和存储异常矩阵。
 *
 * 运行：node pixivdownload-app/src/test/js/batch-layout.test.js
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');

const SOURCE_PATH = path.join(__dirname, '..', '..', '..', '..',
    'pixivdownload-plugin-download-workbench', 'src', 'main', 'resources', 'static', 'pixiv-batch',
    'batch-layout.js');
const HTML_PATH = path.join(__dirname, '..', '..', '..', '..',
    'pixivdownload-plugin-download-workbench', 'src', 'main', 'resources', 'static', 'pixiv-batch.html');
const CSS_PATH = path.join(__dirname, '..', '..', '..', '..',
    'pixivdownload-plugin-download-workbench', 'src', 'main', 'resources', 'static', 'pixiv-batch',
    'pixiv-batch.css');
const SOURCE = fs.readFileSync(SOURCE_PATH, 'utf8');
const HTML = fs.readFileSync(HTML_PATH, 'utf8');
const CSS = fs.readFileSync(CSS_PATH, 'utf8');
const STORAGE_KEY = 'pixiv:batch-layout:v1';
const ACTION_IDS = ['btn-start', 'btn-pause', 'btn-retry', 'btn-export', 'btn-export-failed', 'btn-clear'];
const API_FUNCTIONS = [
    'availableLayouts', 'defaultLayout', 'normalizeLayout', 'readStoredLayout',
    'applyLayout', 'applyStoredLayout', 'toggleLayout', 'bindLayoutToggle',
    'refreshLayoutToggle', 'currentLayout'
];

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

    const document = {
        documentElement: html,
        head,
        body,
        activeElement: null,
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

(async function main() {
    ok('系列下载声明与快捷获取同语义的数据来源 radiogroup',
        HTML.includes('class="series-data-source-control data-source-control"')
        && HTML.includes('id="series-data-source-switcher" role="radiogroup"')
        && HTML.includes('aria-labelledby="series-data-source-label"'));
    ok('数据来源布局样式由通用类复用而非快捷获取专属选择器',
        CSS.includes('.data-source-control {')
        && CSS.includes('.data-source-control .kind-switcher')
        && CSS.includes('.data-source-label {'));
    ok('系列下载提供来源浏览器的中性宿主容器',
        HTML.includes('class="series-source-browser"')
        && HTML.includes('id="series-source-browser" hidden'));
    const seriesBrowserCssStart = CSS.indexOf('.series-source-browser {');
    const seriesBrowserCssEnd = CSS.indexOf('\n.quick-account {', seriesBrowserCssStart);
    const seriesBrowserCss = CSS.slice(seriesBrowserCssStart, seriesBrowserCssEnd);
    ok('来源浏览器样式覆盖标题、状态、列表、项目与分页导航',
        seriesBrowserCssStart >= 0 && seriesBrowserCssEnd > seriesBrowserCssStart
        && seriesBrowserCss.includes('.series-source-browser-title')
        && seriesBrowserCss.includes('.series-source-browser-status')
        && seriesBrowserCss.includes('.series-source-browser-list')
        && seriesBrowserCss.includes('.series-source-browser-item')
        && seriesBrowserCss.includes('.series-source-browser-navigation'));
    ok('来源浏览器颜色只复用主题变量',
        seriesBrowserCss.includes('var(--surface-muted)')
        && seriesBrowserCss.includes('var(--line)')
        && seriesBrowserCss.includes('var(--text)')
        && seriesBrowserCss.includes('var(--muted)')
        && seriesBrowserCss.includes('var(--brand)')
        && !/#[0-9a-f]{3,8}\b|rgba?\s*\(/i.test(seriesBrowserCss));

    // 1) 声明式清单、默认值和首屏应用；i18n ready 前按钮保持隐藏。
    {
        const h = createHarness({
            layouts: ['workbench', 'classic'],
            defaultLayout: 'workbench',
            initialLayout: 'classic'
        });
        ok('暴露 window.PixivBatch.layout', !!h.api);
        API_FUNCTIONS.forEach(name => ok('布局 API 暴露 ' + name, typeof h.api[name] === 'function'));
        ok('加性挂载不覆盖 PixivBatch 既有门面', h.sandbox.PixivBatch.sentinel.preserved === true);
        jsonEq('availableLayouts 按 link DOM 顺序返回', Array.from(h.api.availableLayouts()), ['workbench', 'classic']);
        ok('availableLayouts 返回冻结快照', Object.isFrozen(h.api.availableLayouts()));
        eq('根声明默认布局有效时采用该值', h.api.defaultLayout(), 'workbench');
        eq('无偏好 readStoredLayout 返回声明默认值', h.api.readStoredLayout(), 'workbench');
        eq('无偏好不清理 localStorage', h.storage.removeCalls.length, 0);
        h.api.applyStoredLayout();
        eq('首屏应用声明默认布局', rootLayout(h), 'workbench');
        ok('i18n ready 前按钮仍隐藏', buttonState(h).hidden);
        eq('i18n ready 前不解析按钮文案', h.translationCalls.length, 0);
        eq('模块加载与首屏应用不提前绑定 click', h.dom.button.listenerCount('click'), 0);
        eq('模块加载与首屏应用不提前绑定 storage', h.windowEvents.listenerCount('storage'), 0);
    }

    // 2) token 发现去重且严格；default 失配回退第一项，外部快照不能反向污染。
    {
        const h = createHarness({
            layouts: ['alpha', 'alpha', '', ' Classic ', 'beta-token', 'gamma'],
            defaultLayout: 'missing',
            initialLayout: 'GAMMA'
        });
        jsonEq('清单跳过重复、空白和非 kebab token', Array.from(h.api.availableLayouts()),
            ['alpha', 'beta-token', 'gamma']);
        eq('失配 default 回退第一项', h.api.defaultLayout(), 'alpha');
        eq('陈旧根 token 经 normalize 回退 default', h.api.currentLayout(), 'alpha');
        const invalid = ['', ' ', ' alpha ', 'Alpha', 'ALPHA', 'unknown', null, undefined,
            true, false, 1, {}, [], new String('alpha')];
        invalid.forEach((value, index) => {
            eq('非法布局值 #' + index + ' 回退 default', h.api.normalizeLayout(value), 'alpha');
        });
        eq('精确可用 token 合法', h.api.normalizeLayout('gamma'), 'gamma');
        const snapshot = h.api.availableLayouts();
        doesNotThrow('外部尝试修改冻结快照不影响控制器', () => {
            try { snapshot.pop(); } catch (_) { /* frozen snapshot */ }
        });
        jsonEq('重新读取清单不受外部快照修改影响', Array.from(h.api.availableLayouts()),
            ['alpha', 'beta-token', 'gamma']);
    }

    // 3) 多布局按 DOM 顺序循环；按钮目标、持久化、语言刷新与幂等绑定一致。
    {
        const h = createHarness({
            layouts: ['workbench', 'classic'],
            defaultLayout: 'workbench',
            initialLayout: 'workbench'
        });
        h.api.bindLayoutToggle();
        h.api.bindLayoutToggle();
        h.api.bindLayoutToggle();
        let state = buttonState(h);
        ok('多布局绑定后显示按钮', !state.hidden && !state.disabled);
        eq('按钮 data-layout 记录当前布局', state.layout, 'workbench');
        eq('按钮 data-layout-target 记录下一布局', state.target, 'classic');
        eq('按钮 text/title/aria 使用目标动作文案', state.text, '切换到经典布局');
        eq('按钮 label i18n key 指向目标', state.textKey, 'layout.switch-to-classic');
        eq('按钮 title i18n key 指向目标', state.titleKey, 'layout.switch-to-classic');
        eq('按钮 aria i18n key 指向目标', state.ariaKey, 'layout.switch-to-classic');
        eq('重复 bind 只有一个 click 监听', h.dom.button.listenerCount('click'), 1);
        eq('重复 bind 只有一个 storage 监听', h.windowEvents.listenerCount('storage'), 1);

        const button = state.button;
        const label = state.label;
        const bodyChildren = h.dom.body.children.slice();
        h.dom.button.focus();
        h.resetBusinessCalls();
        h.dom.button.click();
        state = buttonState(h);
        eq('点击从 workbench 切到 classic', rootLayout(h), 'classic');
        eq('点击持久化下一布局', h.storage.values.get(STORAGE_KEY), 'classic');
        eq('一次点击只写一次 storage', h.storage.setCalls.length, 1);
        eq('classic 的下一目标是 workbench', state.target, 'workbench');
        eq('classic 状态显示 workbench 动作文案', state.text, '切换到工作台布局');
        ok('点击不替换按钮与标签节点', state.button === button && state.label === label);
        ok('点击不替换页面顶层 DOM 子节点', h.dom.body.children.length === bodyChildren.length
            && h.dom.body.children.every((node, index) => node === bodyChildren[index]));
        eq('点击保持按钮焦点', h.dom.document.activeElement, button);
        eq('点击不改根节点哨兵属性', h.dom.html.getAttribute('data-sentinel'), 'keep');
        eq('点击不触发 reload/fetch/业务函数或业务状态读取', businessCallCount(h.calls), 0);

        h.messages['layout.switch-to-classic'] = 'Switch to classic';
        h.messages['layout.switch-to-workbench'] = 'Switch to workbench';
        h.api.refreshLayoutToggle();
        eq('语言刷新从资源重新派生当前目标文案', buttonState(h).text, 'Switch to workbench');
        h.dom.button.click();
        eq('第二次点击按清单循环回 workbench', rootLayout(h), 'workbench');
        eq('循环后目标回到 classic', buttonState(h).target, 'classic');
    }

    // 4) 三布局证明控制器没有二元 token 分支。
    {
        const h = createHarness({
            layouts: ['alpha', 'beta', 'gamma'],
            defaultLayout: 'alpha',
            initialLayout: 'alpha',
            messages: {
                'layout.switch-to-alpha': 'to alpha',
                'layout.switch-to-beta': 'to beta',
                'layout.switch-to-gamma': 'to gamma'
            }
        });
        h.api.bindLayoutToggle();
        eq('三布局初始目标取 DOM 下一项', buttonState(h).target, 'beta');
        h.dom.button.click();
        eq('三布局 alpha → beta', rootLayout(h), 'beta');
        eq('beta 的目标是 gamma', buttonState(h).target, 'gamma');
        h.dom.button.click();
        eq('三布局 beta → gamma', rootLayout(h), 'gamma');
        eq('gamma 的目标循环到 alpha', buttonState(h).target, 'alpha');
        h.dom.button.click();
        eq('三布局 gamma → alpha', rootLayout(h), 'alpha');
        eq('三次切换各持久化一次', h.storage.setCalls.length, 3);
    }

    // 5) 仅 workbench：裁撤偏好回退并清理，按钮无死 click，storage 仍归一。
    {
        const h = createHarness({
            layouts: ['workbench'],
            defaultLayout: 'workbench',
            initialLayout: 'classic',
            storage: {[STORAGE_KEY]: 'classic'}
        });
        eq('单 workbench 读取已裁撤 classic 回退 workbench', h.api.readStoredLayout(), 'workbench');
        eq('单 workbench 尝试清理 stale 偏好', h.storage.removeCalls.length, 1);
        h.storage.seed(STORAGE_KEY, 'classic');
        h.api.applyStoredLayout();
        eq('单 workbench 首屏根布局归一', rootLayout(h), 'workbench');
        ok('单 workbench 六个动作恢复到各自 origin', actionsAreAtOrigins(h));
        ok('单 workbench 投影 host 保持隐藏', h.dom.actionHost.hidden);
        h.api.bindLayoutToggle();
        h.api.bindLayoutToggle();
        ok('单 workbench 按钮 hidden 且 disabled', buttonState(h).hidden && buttonState(h).disabled);
        eq('单 workbench 不解析不存在的目标 i18n', h.translationCalls.length, 0);
        eq('单 workbench 不绑定 click', h.dom.button.listenerCount('click'), 0);
        eq('单 workbench 幂等绑定一个 storage listener', h.windowEvents.listenerCount('storage'), 1);
        const setBefore = h.storage.setCalls.length;
        const removeBefore = h.storage.removeCalls.length;
        h.resetBusinessCalls();
        eq('单 workbench 显式 toggle 返回唯一布局', h.api.toggleLayout(), 'workbench');
        h.dom.button.click();
        eq('单 workbench toggle/click 不改根布局', rootLayout(h), 'workbench');
        eq('单 workbench toggle/click 不写 storage', h.storage.setCalls.length, setBefore);
        eq('单 workbench toggle/click 不触业务', businessCallCount(h.calls), 0);
        h.dom.html.setAttribute('data-batch-layout', 'classic');
        h.dispatchStorage(STORAGE_KEY, 'classic');
        eq('单 workbench storage 已裁撤 token 回退唯一布局', rootLayout(h), 'workbench');
        h.dom.html.setAttribute('data-batch-layout', 'classic');
        h.dispatchStorage(STORAGE_KEY, null);
        eq('单 workbench storage null 回退唯一布局', rootLayout(h), 'workbench');
        eq('storage 同步不写回或清理 storage', h.storage.setCalls.length, setBefore);
        eq('storage 同步不额外清理 stale key', h.storage.removeCalls.length, removeBefore);
    }

    // 6) 仅 classic：与单 workbench 对称，旧 workbench 偏好和事件安全回退。
    {
        const h = createHarness({
            layouts: ['classic'],
            defaultLayout: 'classic',
            initialLayout: 'workbench',
            storage: {[STORAGE_KEY]: 'workbench'}
        });
        h.api.applyStoredLayout();
        eq('单 classic 清理旧 workbench 偏好', h.storage.removeCalls.length, 1);
        eq('单 classic 应用唯一布局', rootLayout(h), 'classic');
        jsonEq('单 classic 按声明顺序投影六个动作', actionIdsIn(h.dom.actionHost), ACTION_IDS);
        ok('单 classic 六个动作均只有 host 一个父级', ACTION_IDS.every(id =>
            h.dom.actions.get(id).parentNode === h.dom.actionHost));
        ok('单 classic 保留暂停按钮 disabled 状态', h.dom.actions.get('btn-pause').disabled);
        h.api.bindLayoutToggle();
        ok('单 classic 按钮 hidden 且 disabled', buttonState(h).hidden && buttonState(h).disabled);
        eq('单 classic 不绑定 click', h.dom.button.listenerCount('click'), 0);
        eq('单 classic 仍绑定 storage', h.windowEvents.listenerCount('storage'), 1);
        const setBefore = h.storage.setCalls.length;
        eq('单 classic 显式 toggle 返回唯一布局', h.api.toggleLayout(), 'classic');
        h.dom.html.setAttribute('data-batch-layout', 'workbench');
        h.dispatchStorage(STORAGE_KEY, 'workbench');
        eq('单 classic storage 已裁撤 token 回退 classic', rootLayout(h), 'classic');
        h.dom.html.setAttribute('data-batch-layout', 'workbench');
        h.dispatchStorage(STORAGE_KEY, null);
        eq('单 classic storage null 回退 classic', rootLayout(h), 'classic');
        eq('单 classic toggle/storage 不写 storage', h.storage.setCalls.length, setBefore);
    }

    // 7) 零布局闭环：所有读写 API 返回 null，不改根属性，不接触 storage。
    {
        const h = createHarness({
            layouts: [],
            defaultLayout: 'workbench',
            initialLayout: 'legacy-layout',
            storage: {[STORAGE_KEY]: 'classic'}
        });
        jsonEq('零布局 availableLayouts 为空', Array.from(h.api.availableLayouts()), []);
        eq('零布局 defaultLayout 返回 null', h.api.defaultLayout(), null);
        eq('零布局 normalizeLayout 返回 null', h.api.normalizeLayout('workbench'), null);
        eq('零布局 currentLayout 返回 null', h.api.currentLayout(), null);
        eq('零布局 readStoredLayout 返回 null', h.api.readStoredLayout(), null);
        eq('零布局读取 API 不访问 storage', h.storage.accessCount(), 0);
        eq('零布局 applyStoredLayout 返回 null', h.api.applyStoredLayout(), null);
        eq('零布局 applyLayout 返回 null', h.api.applyLayout('workbench', {persist: true}), null);
        eq('零布局 toggleLayout 返回 null', h.api.toggleLayout(), null);
        h.api.bindLayoutToggle();
        h.api.bindLayoutToggle();
        h.dispatchStorage(STORAGE_KEY, null);
        h.dispatchStorage(STORAGE_KEY, 'classic');
        eq('零布局所有操作保留原根属性', rootLayout(h), 'legacy-layout');
        eq('零布局所有操作不访问 storage', h.storage.accessCount(), 0);
        ok('零布局不移动任何动作节点', actionsAreAtOrigins(h));
        ok('零布局 host 保持空且由基础 CSS 控制可见性', actionIdsIn(h.dom.actionHost).length === 0);
        ok('零布局按钮保持 hidden 且 disabled', buttonState(h).hidden && buttonState(h).disabled);
        eq('零布局无 click 监听', h.dom.button.listenerCount('click'), 0);
        eq('零布局幂等绑定一个 no-op storage 监听', h.windowEvents.listenerCount('storage'), 1);
        ok('零布局未写入 null/undefined 属性', !JSON.stringify(h.dom.button.attributes).includes('undefined')
            && !JSON.stringify(h.dom.button.attributes).includes('null'));
        ok('零布局未写入 null/undefined storage', !Array.from(h.storage.values.values())
            .some(value => value === 'null' || value === 'undefined'));
    }

    // 8) stale 偏好、storage null/非法值和 get/set/remove 异常全部安全降级。
    {
        const stale = createHarness({
            layouts: ['workbench', 'classic'],
            defaultLayout: 'classic',
            initialLayout: 'workbench',
            storage: {[STORAGE_KEY]: 'removed-layout'}
        });
        stale.api.applyStoredLayout();
        eq('stale 初始偏好回退声明 default', rootLayout(stale), 'classic');
        eq('stale 初始偏好被清理', stale.storage.removeCalls.length, 1);

        const removeFailure = createHarness({
            layouts: ['workbench', 'classic'],
            defaultLayout: 'workbench',
            initialLayout: 'classic',
            storage: {[STORAGE_KEY]: 'removed-layout'},
            throwOnRemove: true
        });
        doesNotThrow('removeItem 抛错不传播', () => removeFailure.api.applyStoredLayout());
        eq('removeItem 抛错仍应用 default', rootLayout(removeFailure), 'workbench');
        eq('removeItem 抛错仍尝试一次清理', removeFailure.storage.removeCalls.length, 1);

        const getFailure = createHarness({
            layouts: ['workbench', 'classic'],
            defaultLayout: 'classic',
            initialLayout: 'workbench',
            throwOnGet: true
        });
        doesNotThrow('getItem 抛错不传播', () => getFailure.api.applyStoredLayout());
        eq('getItem 抛错应用 default', rootLayout(getFailure), 'classic');

        const setFailure = createHarness({
            layouts: ['workbench', 'classic'],
            defaultLayout: 'workbench',
            initialLayout: 'workbench',
            throwOnSet: true
        });
        doesNotThrow('setItem 抛错不传播', () => setFailure.api.applyLayout('classic', {persist: true}));
        eq('setItem 抛错仍即时应用布局', rootLayout(setFailure), 'classic');

        const storageEvent = createHarness({
            layouts: ['workbench', 'classic'],
            defaultLayout: 'workbench',
            initialLayout: 'classic'
        });
        storageEvent.api.bindLayoutToggle();
        storageEvent.dispatchStorage('other-key', 'classic');
        eq('storage 忽略其它 key', rootLayout(storageEvent), 'classic');
        storageEvent.dispatchStorage(STORAGE_KEY, null);
        eq('storage null 应用 default', rootLayout(storageEvent), 'workbench');
        ok('storage null 同步恢复动作 origin', actionsAreAtOrigins(storageEvent));
        storageEvent.api.applyLayout('classic', {persist: false});
        jsonEq('apply classic 同步动作投影', actionIdsIn(storageEvent.dom.actionHost), ACTION_IDS);
        storageEvent.dispatchStorage(STORAGE_KEY, ' Classic ');
        eq('storage 空白/大小写非法值应用 default', rootLayout(storageEvent), 'workbench');
        ok('storage 非法值同步恢复动作 origin', actionsAreAtOrigins(storageEvent));
        storageEvent.api.applyLayout('classic', {persist: false});
        storageEvent.dispatchStorage(STORAGE_KEY, 'removed-layout');
        eq('storage 已移除 token 应用 default', rootLayout(storageEvent), 'workbench');
        storageEvent.dispatchStorage(STORAGE_KEY, 'classic');
        eq('storage 合法 token 正常同步', rootLayout(storageEvent), 'classic');
        jsonEq('storage 合法 classic 同步动作投影', actionIdsIn(storageEvent.dom.actionHost), ACTION_IDS);
        eq('storage 事件从不 setItem', storageEvent.storage.setCalls.length, 0);
        eq('storage 事件从不 removeItem', storageEvent.storage.removeCalls.length, 0);
    }

    // 9) 从多布局退化到单/零布局时旧 click 被移除，storage listener 保持幂等 no-op。
    {
        const h = createHarness({
            layouts: ['workbench', 'classic'],
            defaultLayout: 'workbench',
            initialLayout: 'workbench'
        });
        h.api.bindLayoutToggle();
        eq('多布局初始有 click', h.dom.button.listenerCount('click'), 1);
        eq('多布局初始有 storage', h.windowEvents.listenerCount('storage'), 1);
        h.dom.setLayouts(['workbench']);
        h.api.refreshLayoutToggle();
        eq('退化为单布局移除 click', h.dom.button.listenerCount('click'), 0);
        eq('退化为单布局保留 storage', h.windowEvents.listenerCount('storage'), 1);
        const setBefore = h.storage.setCalls.length;
        h.dom.button.click();
        eq('退化后的旧按钮 click 无副作用', h.storage.setCalls.length, setBefore);
        h.dom.setLayouts([]);
        h.api.refreshLayoutToggle();
        eq('退化为零布局保留一个 no-op storage', h.windowEvents.listenerCount('storage'), 1);
        const accessesBefore = h.storage.accessCount();
        h.dispatchStorage(STORAGE_KEY, null);
        eq('零布局 storage handler 不读写 storage', h.storage.accessCount(), accessesBefore);
        eq('零布局 storage handler 不改根属性', rootLayout(h), 'workbench');
        ok('退化为零布局按钮保持隐藏', buttonState(h).hidden);
    }

    // 10) 初始 classic 与反复往返只重排同一批动作节点，并完整保留节点状态。
    {
        const h = createHarness({
            layouts: ['workbench', 'classic'],
            defaultLayout: 'workbench',
            initialLayout: 'workbench',
            storage: {[STORAGE_KEY]: 'classic'}
        });
        const identities = new Map(ACTION_IDS.map(id => [id, h.dom.actions.get(id)]));
        const pause = h.dom.actions.get('btn-pause');
        pause.focus();
        h.resetBusinessCalls();
        eq('初始 classic 偏好成功应用', h.api.applyStoredLayout(), 'classic');
        eq('初始 classic 更新根 token', rootLayout(h), 'classic');
        jsonEq('初始 classic host 使用声明的旧按钮顺序', actionIdsIn(h.dom.actionHost), ACTION_IDS);
        ok('初始 classic 显示 action host', !h.dom.actionHost.hidden);
        eq('初始 classic 不回写已有偏好', h.storage.setCalls.length, 0);
        eq('投影后恢复动作按钮焦点', h.dom.document.activeElement, pause);
        ok('投影保留 pause.disabled', pause.disabled);
        ok('投影保留按钮数据状态', ACTION_IDS.every(id =>
            identities.get(id).getAttribute('data-state') === id + '-state'));
        ok('投影不触发任何业务函数或状态读取', businessCallCount(h.calls) === 0);

        eq('classic → workbench 切换成功', h.api.toggleLayout(), 'workbench');
        ok('workbench 精确恢复六个 origin', actionsAreAtOrigins(h));
        ok('workbench 隐藏 action host', h.dom.actionHost.hidden);
        ok('恢复后 more-menu open 状态不变', h.dom.moreMenu.open);
        eq('恢复后焦点仍在同一 pause 节点', h.dom.document.activeElement, pause);

        for (let index = 0; index < 10; index++) {
            const expected = index % 2 === 0 ? 'classic' : 'workbench';
            eq('反复切换 #' + (index + 1) + ' 返回预期布局', h.api.toggleLayout(), expected);
            ok('反复切换 #' + (index + 1) + ' 保持动作节点身份', ACTION_IDS.every(id =>
                h.dom.document.getElementById(id) === identities.get(id)));
            ok('反复切换 #' + (index + 1) + ' 每个动作仅出现一次', ACTION_IDS.every(id =>
                actionOccurrenceCount(h.dom.html, identities.get(id)) === 1));
            if (expected === 'classic') {
                jsonEq('反复切换 #' + (index + 1) + ' classic 顺序正确',
                    actionIdsIn(h.dom.actionHost), ACTION_IDS);
            } else {
                ok('反复切换 #' + (index + 1) + ' workbench origin 正确', actionsAreAtOrigins(h));
            }
        }
        ok('偶数次往返最终恢复 workbench origin', actionsAreAtOrigins(h));
        ok('所有动作始终只有唯一父级', ACTION_IDS.every(id => !!identities.get(id).parentNode));
        ok('所有动作最终仍保留 disabled/data 状态', pause.disabled && ACTION_IDS.every(id =>
            identities.get(id).getAttribute('data-state') === id + '-state'));
    }

    // 11) 缺少任一 origin 时预检原子失败，不移动节点、不改根布局、不持久化。
    {
        const h = createHarness({
            layouts: ['workbench', 'classic'],
            defaultLayout: 'workbench',
            initialLayout: 'workbench',
            missingActionOrigin: 'btn-export'
        });
        const placement = actionPlacementSnapshot(h);
        const hostHidden = h.dom.actionHost.hidden;
        eq('缺 origin 的 canonical workbench 也拒绝不完整投影契约', h.api.applyStoredLayout(), null);
        h.api.bindLayoutToggle();
        h.resetBusinessCalls();
        eq('缺 origin 时 toggle 返回 null', h.api.toggleLayout(), null);
        eq('缺 origin 时根布局保持 workbench', rootLayout(h), 'workbench');
        eq('缺 origin 时不写 localStorage', h.storage.setCalls.length, 0);
        ok('缺 origin 时没有任何部分移动', actionPlacementsMatch(placement));
        ok('缺 origin 时 action host 可见状态不变且为空', h.dom.actionHost.hidden === hostHidden
            && actionIdsIn(h.dom.actionHost).length === 0);
        ok('缺 origin 时六个节点仍各出现一次', ACTION_IDS.every(id =>
            actionOccurrenceCount(h.dom.html, h.dom.actions.get(id)) === 1));
        eq('缺 origin 时不触发业务副作用', businessCallCount(h.calls), 0);
    }

    // 12) 浏览器 DOM 操作意外抛错时回滚已移动节点，错误布局不得落根或持久化。
    {
        const h = createHarness({
            layouts: ['workbench', 'classic'],
            defaultLayout: 'workbench',
            initialLayout: 'workbench'
        });
        const placement = actionPlacementSnapshot(h);
        eq('异常模拟前 canonical workbench 投影同步成功', h.api.applyStoredLayout(), 'workbench');
        const pause = h.dom.actions.get('btn-pause');
        pause.focus();
        const appendChild = h.dom.actionHost.appendChild.bind(h.dom.actionHost);
        let appendCalls = 0;
        h.dom.actionHost.appendChild = function (child) {
            appendCalls++;
            if (appendCalls === 3) throw new Error('simulated append failure');
            return appendChild(child);
        };
        h.resetBusinessCalls();
        eq('DOM 中途异常时 applyLayout 返回 null',
            h.api.applyLayout('classic', {persist: true}), null);
        eq('DOM 中途异常时根布局保持 workbench', rootLayout(h), 'workbench');
        eq('DOM 中途异常时不持久化 classic', h.storage.setCalls.length, 0);
        ok('DOM 中途异常回滚全部动作位置', actionPlacementsMatch(placement));
        ok('DOM 中途异常后 host 恢复隐藏且为空', h.dom.actionHost.hidden
            && actionIdsIn(h.dom.actionHost).length === 0);
        eq('DOM 中途异常后恢复原焦点', h.dom.document.activeElement, pause);
        ok('DOM 中途异常后所有节点仍唯一', ACTION_IDS.every(id =>
            actionOccurrenceCount(h.dom.html, h.dom.actions.get(id)) === 1));
        eq('DOM 中途异常不触发业务副作用', businessCallCount(h.calls), 0);
    }

    console.log(`\nbatch-layout.test.js: ${passed} assertions passed (12 contract groups) ✓`);
})().catch(error => {
    console.error('TEST FAILED:', error && error.stack ? error.stack : error);
    process.exit(1);
});
