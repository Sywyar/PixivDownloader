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
const SOURCE = fs.readFileSync(SOURCE_PATH, 'utf8');
const STORAGE_KEY = 'pixiv:batch-layout:v1';
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
    appendChild(child) {
        child.parentNode = this;
        child.ownerDocument = this.ownerDocument;
        this.children.push(child);
        return child;
    }
    matches(selector) {
        if (selector === 'link[data-batch-layout-style]') {
            return this.tagName === 'LINK' && this.hasAttribute('data-batch-layout-style');
        }
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
    return {document, html, head, body, button, label, business, setLayouts};
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

(async function main() {
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
        ok('点击不改业务 DOM 子节点', h.dom.body.children.length === bodyChildren.length
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
        storageEvent.api.applyLayout('classic', {persist: false});
        storageEvent.dispatchStorage(STORAGE_KEY, ' Classic ');
        eq('storage 空白/大小写非法值应用 default', rootLayout(storageEvent), 'workbench');
        storageEvent.api.applyLayout('classic', {persist: false});
        storageEvent.dispatchStorage(STORAGE_KEY, 'removed-layout');
        eq('storage 已移除 token 应用 default', rootLayout(storageEvent), 'workbench');
        storageEvent.dispatchStorage(STORAGE_KEY, 'classic');
        eq('storage 合法 token 正常同步', rootLayout(storageEvent), 'classic');
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

    console.log(`\nbatch-layout.test.js: ${passed} assertions passed (9 contract groups) ✓`);
})().catch(error => {
    console.error('TEST FAILED:', error && error.stack ? error.stack : error);
    process.exit(1);
});
