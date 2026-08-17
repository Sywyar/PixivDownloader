'use strict';

const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const STATIC = path.join(__dirname, '..', '..', 'main', 'resources', 'static');
const SLOT_SOURCE = fs.readFileSync(path.join(STATIC, 'pixiv-notifications', 'batch-inbox-slot.js'), 'utf8');
const PAGE_SOURCE = fs.readFileSync(path.join(STATIC, 'pixiv-notifications', 'pixiv-notifications.js'), 'utf8');
const PAGE_HTML = fs.readFileSync(path.join(STATIC, 'pixiv-notifications.html'), 'utf8');
const CSS = fs.readFileSync(path.join(STATIC, 'pixiv-notifications', 'pixiv-notifications.css'), 'utf8');

class El {
    constructor(tag) {
        this.tag = String(tag).toLowerCase();
        this.attributes = {};
        this.children = [];
        this.parentNode = null;
        this.listeners = new Map();
        this.dataset = {};
        this.style = {};
        this.hidden = false;
        this.disabled = false;
        this.checked = false;
        this.className = '';
        this._text = '';
        this._html = '';
        this.contentWindow = this.tag === 'iframe' ? {} : undefined;
        this.classList = {
            add: (...names) => this._setClasses([...this._classes(), ...names]),
            remove: (...names) => this._setClasses(this._classes().filter(name => !names.includes(name))),
            toggle: (name, force) => {
                const present = this._classes().includes(name);
                const enabled = force === undefined ? !present : !!force;
                this._setClasses(enabled ? [...this._classes(), name] : this._classes().filter(value => value !== name));
                return enabled;
            },
            contains: name => this._classes().includes(name)
        };
    }
    _classes() { return String(this.className || '').split(/\s+/).filter(Boolean); }
    _setClasses(names) { this.className = [...new Set(names)].join(' '); }
    set id(value) { this.setAttribute('id', value); }
    get id() { return this.getAttribute('id') || ''; }
    set textContent(value) {
        this._text = String(value == null ? '' : value);
        this.children.forEach(child => { child.parentNode = null; });
        this.children = [];
    }
    get textContent() { return this._text + this.children.map(child => child.textContent).join(''); }
    set innerHTML(value) {
        this._html = String(value == null ? '' : value);
        this.children.forEach(child => { child.parentNode = null; });
        this.children = [];
    }
    get innerHTML() { return this._html; }
    get firstElementChild() { return this.children[0] || null; }
    get isConnected() {
        let current = this;
        while (current) {
            if (current._documentRoot) return true;
            current = current.parentNode;
        }
        return false;
    }
    setAttribute(name, value) {
        if (name === 'class') this.className = String(value);
        else this.attributes[name] = String(value);
    }
    getAttribute(name) {
        if (name === 'class') return this.className || null;
        return Object.prototype.hasOwnProperty.call(this.attributes, name) ? this.attributes[name] : null;
    }
    appendChild(child) {
        child.parentNode = this;
        this.children.push(child);
        return child;
    }
    append(...children) { children.forEach(child => this.appendChild(child)); }
    insertBefore(child, reference) {
        child.parentNode = this;
        const index = this.children.indexOf(reference);
        if (index < 0) this.children.push(child);
        else this.children.splice(index, 0, child);
        return child;
    }
    remove() {
        if (!this.parentNode) return;
        const index = this.parentNode.children.indexOf(this);
        if (index >= 0) this.parentNode.children.splice(index, 1);
        this.parentNode = null;
    }
    addEventListener(type, listener) {
        if (!this.listeners.has(type)) this.listeners.set(type, new Set());
        this.listeners.get(type).add(listener);
    }
    removeEventListener(type, listener) {
        const entries = this.listeners.get(type);
        if (entries) entries.delete(listener);
    }
    async emit(type, event = {}) {
        event.type = type;
        event.target = event.target || this;
        event.currentTarget = this;
        const results = [...(this.listeners.get(type) || [])].map(listener => listener(event));
        await Promise.all(results.filter(result => result && typeof result.then === 'function'));
    }
    matches(selector) {
        if (selector === ':popover-open') return !!this._popoverOpen;
        if (selector.startsWith('.')) return this._classes().includes(selector.slice(1));
        const attribute = /^\[([^=]+)="([\s\S]*)"\]$/.exec(selector);
        if (attribute) return this.getAttribute(attribute[1]) === attribute[2];
        return this.tag === selector.toLowerCase();
    }
    querySelectorAll(selector) {
        const found = [];
        const visit = node => node.children.forEach(child => {
            if (child.matches(selector)) found.push(child);
            visit(child);
        });
        visit(this);
        return found;
    }
    querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
    insertAdjacentHTML(position, html) {
        assert.equal(position, 'beforeend');
        const fragment = new El('#html');
        fragment._text = String(html);
        fragment.html = String(html);
        this.appendChild(fragment);
    }
    replaceChildren() {
        this.children.forEach(child => { child.parentNode = null; });
        this.children = [];
    }
    showPopover() { this._popoverOpen = true; this.hidden = false; }
    hidePopover() { this._popoverOpen = false; this.hidden = true; }
    scrollIntoView() { this.scrollCalls = (this.scrollCalls || 0) + 1; }
}

function createDocument({filters = false, slot = false} = {}) {
    const head = new El('head');
    const body = new El('body');
    head._documentRoot = true;
    body._documentRoot = true;
    const listeners = new Map();
    const filterButtons = [];

    if (slot) {
        const host = new El('div');
        host.setAttribute('data-vue-slot', 'topbar-actions');
        body.appendChild(host);
    }
    if (filters) {
        const filterBar = new El('div');
        filterBar.className = 'notification-filters';
        for (const category of ['', 'download', 'announcement', 'survey', 'system']) {
            const button = new El('button');
            button.dataset.category = category;
            filterButtons.push(button);
            filterBar.appendChild(button);
        }
        body.appendChild(filterBar);
    }

    const elements = {};
    const add = (id, tag = 'div') => {
        const element = new El(tag);
        element.id = id;
        elements[id] = element;
        body.appendChild(element);
        return element;
    };
    if (filters) {
        add('langSwitcherAnchor');
        add('notificationList');
        add('notificationStatus');
        add('notificationUnreadCount', 'span');
        add('notificationUnreadOnly', 'input');
        add('notificationMarkCategoryRead', 'button');
        const detail = add('notificationDetail');
        detail.appendChild(new El('p'));
        const frames = new El('div');
        frames.id = 'notificationContentFrames';
        elements.notificationContentFrames = frames;
        detail.appendChild(frames);
    }

    const document = {
        head,
        body,
        title: '',
        visibilityState: 'visible',
        createElement: tag => new El(tag),
        getElementById: id => elements[id] || null,
        querySelector(selector) {
            return body.matches(selector) ? body : body.querySelector(selector);
        },
        querySelectorAll(selector) {
            if (selector === '.notification-filters button') return filterButtons.slice();
            return body.querySelectorAll(selector);
        },
        addEventListener(type, listener) {
            if (!listeners.has(type)) listeners.set(type, new Set());
            listeners.get(type).add(listener);
        },
        removeEventListener(type, listener) {
            const entries = listeners.get(type);
            if (entries) entries.delete(listener);
        },
        async emit(type, event = {}) {
            event.type = type;
            event.target = event.target || document;
            const results = [...(listeners.get(type) || [])].map(listener => listener(event));
            await Promise.all(results.filter(result => result && typeof result.then === 'function'));
        },
        listenerCount: type => (listeners.get(type) || new Set()).size,
        filterButtons
    };
    return document;
}

function createWindow(location) {
    const listeners = new Map();
    const timeouts = new Map();
    const intervals = new Map();
    let timerId = 0;
    return {
        location,
        addEventListener(type, listener) {
            if (!listeners.has(type)) listeners.set(type, new Set());
            listeners.get(type).add(listener);
        },
        removeEventListener(type, listener) {
            const entries = listeners.get(type);
            if (entries) entries.delete(listener);
        },
        async emit(type, event = {}) {
            event.type = type;
            const results = [...(listeners.get(type) || [])].map(listener => listener(event));
            await Promise.all(results.filter(result => result && typeof result.then === 'function'));
        },
        dispatchEvent(event) { return this.emit(event.type, event); },
        listenerCount: type => (listeners.get(type) || new Set()).size,
        setTimeout(callback) { const id = ++timerId; timeouts.set(id, callback); return id; },
        clearTimeout(id) { timeouts.delete(id); },
        setInterval(callback) { const id = ++timerId; intervals.set(id, callback); return id; },
        clearInterval(id) { intervals.delete(id); },
        runTimeouts() {
            const callbacks = [...timeouts.values()];
            timeouts.clear();
            callbacks.forEach(callback => callback());
        },
        intervalCount: () => intervals.size,
        matchMedia: () => ({matches: true})
    };
}

function createLocation() {
    let current = new URL('https://local.test/pixiv-notifications.html');
    return {
        get origin() { return current.origin; },
        get pathname() { return current.pathname; },
        get search() { return current.search; },
        get href() { return current.href; },
        set href(value) { current = new URL(value, current); },
        setSearch(value) { current = new URL(current.pathname + value, current.origin); }
    };
}

function response(payload, status = 200) {
    return {status, ok: status >= 200 && status < 300, json: async () => payload};
}

function deferred() {
    let resolve;
    let reject;
    const promise = new Promise((ok, fail) => { resolve = ok; reject = fail; });
    return {promise, resolve, reject};
}

const flush = () => new Promise(resolve => setImmediate(resolve));

function fetchQueue() {
    const pending = [];
    const calls = [];
    return {
        calls,
        enqueue(value) { pending.push(value); },
        fetch(url, init = {}) {
            calls.push({url: String(url), init});
            assert.ok(pending.length, 'unexpected fetch: ' + url);
            return Promise.resolve(pending.shift());
        }
    };
}

function pageMessage(overrides = {}) {
    return Object.assign({
        id: 'message-1', category: 'system', severity: 'info', title: 'Message one', body: 'Body one',
        createdTime: 1_700_000_000_000, readTime: 1_700_000_000_100, deletable: true,
        hasHtmlContent: false, embeddedContentUrl: null, actionUrl: null
    }, overrides);
}

async function pageHarness(initialSnapshot, {surveyBridge = false} = {}) {
    const document = createDocument({filters: true});
    const location = createLocation();
    const window = createWindow(location);
    const requests = fetchQueue();
    requests.enqueue(response(initialSnapshot));
    const history = {
        pushState(_state, _title, url) { location.href = url; },
        replaceState(_state, _title, url) { location.href = url; }
    };
    const feedback = {links: [], followLink(href, options) { this.links.push({href, options}); }};
    const bridge = {attachments: [], detachments: [], config: null};
    if (surveyBridge) {
        window.PixivSurveyFrameBridge = {
            createHost(config) {
                bridge.config = config;
                return {
                    attach(frame, source) { bridge.attachments.push({frame, source}); },
                    detach(frame) { bridge.detachments.push(frame); },
                    handleStorageEvent() {},
                    publishStorage() {}
                };
            }
        };
    }
    window.PixivFeedback = feedback;
    const i18n = {lang: 'en-US', t: (_key, fallback) => fallback, apply() {}};
    const sandbox = {
        window, document, location, history,
        fetch: requests.fetch,
        URL, URLSearchParams, Intl,
        PixivI18n: {create: async () => i18n},
        PixivLangSwitcher: {mount: async () => {}},
        PixivTheme: {mount() {}},
        CustomEvent: function CustomEvent(type, init) { return {type, detail: init && init.detail}; },
        console: {warn() {}, error() {}, log() {}}
    };
    vm.createContext(sandbox);
    vm.runInContext(PAGE_SOURCE, sandbox);
    await document.emit('DOMContentLoaded');
    await flush();
    return {document, window, location, requests, feedback, bridge};
}

function listTitle(document) {
    const title = document.getElementById('notificationList').querySelector('.notification-item-title');
    return title ? title.textContent : '';
}

test('下载页站内信槽位按真实响应渲染，并在未授权或 publication 清理时撤销', async () => {
    const document = createDocument({slot: true});
    const location = createLocation();
    const window = createWindow(location);
    const requests = fetchQueue();
    requests.enqueue(response({messages: [pageMessage()], unreadCount: 3}));
    let initializer;
    let cleanup;
    let prepareCalls = 0;
    window.PixivBatch = {queueTypes: {registerUiModule(fn) { initializer = fn; }}};
    window.PixivVue = {prepareSlotHosts() { prepareCalls++; }};
    const sandbox = {
        window, document, location, fetch: requests.fetch, URLSearchParams,
        HTMLElement: function HTMLElement() {},
        pageI18n: {lang: 'en-US', t: (_key, fallback) => fallback},
        console: {warn() {}, error() {}, log() {}}
    };
    sandbox.HTMLElement.prototype.popover = '';
    vm.createContext(sandbox);
    vm.runInContext(SLOT_SOURCE, sandbox);
    assert.equal(typeof initializer, 'function');
    initializer({signal: new AbortController().signal, isActive: () => true, onCleanup(fn) { cleanup = fn; }});
    await flush();

    const host = document.querySelector('[data-vue-slot="topbar-actions"]');
    const button = host.querySelector('.notification-inbox-button');
    const popover = document.body.querySelector('.notification-inbox-popover');
    assert.equal(prepareCalls, 1);
    assert.equal(button.getAttribute('aria-label'), '打开站内信');
    assert.equal(popover.getAttribute('popover'), 'auto');
    assert.equal(button.querySelector('.notification-inbox-badge').textContent, '3');
    assert.match(requests.calls[0].url, /lang=en-US/);
    assert.equal(window.intervalCount(), 1);

    cleanup();
    assert.equal(host.querySelector('.notification-inbox-slot'), null);
    assert.equal(document.body.querySelector('.notification-inbox-popover'), null);
    assert.equal(window.intervalCount(), 0);
    assert.equal(window.listenerCount('pixivbatch:slotsrendered'), 0);
    assert.equal(document.listenerCount('visibilitychange'), 0);

    const deniedDocument = createDocument({slot: true});
    const deniedWindow = createWindow(createLocation());
    const deniedRequests = fetchQueue();
    deniedRequests.enqueue(response({}, 403));
    let deniedInitializer;
    deniedWindow.PixivBatch = {queueTypes: {registerUiModule(fn) { deniedInitializer = fn; }}};
    const deniedSandbox = {
        window: deniedWindow, document: deniedDocument, location: deniedWindow.location,
        fetch: deniedRequests.fetch, URLSearchParams,
        HTMLElement: sandbox.HTMLElement, console: sandbox.console
    };
    vm.createContext(deniedSandbox);
    vm.runInContext(SLOT_SOURCE, deniedSandbox);
    deniedInitializer({signal: new AbortController().signal, isActive: () => true, onCleanup() {}});
    await flush();
    assert.equal(deniedDocument.querySelector('[data-vue-slot="topbar-actions"]')
        .querySelector('.notification-inbox-slot'), null);
});

test('消息列表拒绝乱序响应，分类切换清除不匹配详情，非法时间不抛错', async () => {
    const invalid = pageMessage({id: 'invalid-time', category: 'download', createdTime: 'not-a-date'});
    const h = await pageHarness({messages: [invalid], categoryUnreadCount: 0});
    const time = h.document.getElementById('notificationList').querySelector('time');
    assert.equal(time.dateTime, '');
    await h.document.getElementById('notificationList').children[0].emit('click');
    assert.equal(h.document.getElementById('notificationDetail').querySelector('h2').textContent, invalid.title);

    h.requests.enqueue(response({messages: [], categoryUnreadCount: 0}));
    await h.document.filterButtons.find(button => button.dataset.category === 'survey').emit('click');
    assert.equal(h.document.getElementById('notificationDetail').querySelector('h2'), null);
    await flush();

    const older = deferred();
    const newer = deferred();
    h.requests.enqueue(older.promise);
    h.requests.enqueue(newer.promise);
    await h.document.emit('visibilitychange');
    await h.document.emit('visibilitychange');
    newer.resolve(response({messages: [pageMessage({id: 'new', title: 'Newest'})], categoryUnreadCount: 1}));
    await flush();
    assert.equal(listTitle(h.document), 'Newest');
    older.resolve(response({messages: [pageMessage({id: 'old', title: 'Stale'})], categoryUnreadCount: 1}));
    await flush();
    assert.equal(listTitle(h.document), 'Newest');
});

test('详情请求拒绝过期结果', async () => {
    const h = await pageHarness({messages: [], categoryUnreadCount: 0});
    const first = deferred();
    const second = deferred();
    h.requests.enqueue(first.promise);
    h.location.setSearch('?id=first');
    await h.window.emit('popstate');
    h.requests.enqueue(second.promise);
    h.location.setSearch('?id=second');
    await h.window.emit('popstate');

    second.resolve(response(pageMessage({id: 'second', title: 'Second'})));
    await flush();
    assert.equal(h.document.getElementById('notificationDetail').querySelector('h2').textContent, 'Second');
    first.resolve(response(pageMessage({id: 'first', title: 'First'})));
    await flush();
    assert.equal(h.document.getElementById('notificationDetail').querySelector('h2').textContent, 'Second');
});

test('HTML 正文 iframe 懒创建复用，并只接受当前不同源 frame 的受限消息', async () => {
    const htmlMessage = pageMessage({id: 'html', title: 'HTML', hasHtmlContent: true});
    const plainMessage = pageMessage({id: 'plain', title: 'Plain'});
    const h = await pageHarness({messages: [htmlMessage, plainMessage], categoryUnreadCount: 0});
    assert.equal(h.document.querySelectorAll('.notification-detail-content-frame').length, 0);

    const list = h.document.getElementById('notificationList');
    await list.children[0].emit('click');
    const frame = h.document.querySelectorAll('.notification-detail-content-frame')[0];
    assert.equal(frame.src, '/api/notifications/html/content?lang=en-US');
    assert.equal(frame.getAttribute('sandbox'), 'allow-scripts');
    assert.equal(frame.getAttribute('referrerpolicy'), 'no-referrer');
    assert.equal(frame.getAttribute('scrolling'), 'no');
    assert.match(frame.getAttribute('allow'), /camera 'none'/);

    await h.window.emit('message', {origin: 'https://local.test', source: frame.contentWindow,
        data: {type: 'pixiv-external-link', href: 'https://example.com', newTab: true}});
    await h.window.emit('message', {origin: 'null', source: {},
        data: {type: 'pixiv-external-link', href: 'https://example.com', newTab: true}});
    assert.equal(h.feedback.links.length, 0);
    await h.window.emit('message', {origin: 'null', source: frame.contentWindow,
        data: {type: 'pixiv-external-link', href: 'https://example.com', newTab: true}});
    assert.equal(h.feedback.links[0].href, 'https://example.com');
    assert.equal(h.feedback.links[0].options.newTab, true);

    await h.window.emit('message', {origin: 'null', source: frame.contentWindow,
        data: {type: 'pixiv-content-height', height: -10}});
    assert.equal(frame.style.height, '160px');
    await h.window.emit('message', {origin: 'null', source: frame.contentWindow,
        data: {type: 'pixiv-content-height', height: 5000}});
    h.window.runTimeouts();
    assert.equal(frame.style.height, '2000px');

    await list.children[1].emit('click');
    assert.equal(frame.hidden, true);
    await h.window.emit('message', {origin: 'null', source: frame.contentWindow,
        data: {type: 'pixiv-external-link', href: 'https://ignored.example', newTab: false}});
    assert.equal(h.feedback.links.length, 1);
    await list.children[0].emit('click');
    assert.equal(h.document.querySelectorAll('.notification-detail-content-frame').length, 1);
    assert.equal(h.document.querySelectorAll('.notification-detail-content-frame')[0], frame);
    assert.ok(h.document.getElementById('notificationDetail').scrollCalls > 0);
});

test('调查 iframe 通过共享 bridge 延迟挂载并携带当前语言', async () => {
    const survey = pageMessage({
        id: 'survey', category: 'survey', title: 'Survey', embeddedContentUrl: '/survey/embed',
        deletable: false, readTime: 1
    });
    const plain = pageMessage({id: 'plain', title: 'Plain'});
    const h = await pageHarness({messages: [survey, plain], categoryUnreadCount: 0}, {surveyBridge: true});
    assert.equal(h.bridge.attachments.length, 0);
    const list = h.document.getElementById('notificationList');
    await list.children[0].emit('click');
    assert.equal(h.bridge.attachments.length, 1);
    assert.match(h.bridge.attachments[0].source, /^\/survey\/embed\?notificationId=survey&lang=en-US$/);
    const frame = h.bridge.attachments[0].frame;
    assert.equal(frame.getAttribute('data-embedded-survey'), 'true');

    await list.children[1].emit('click');
    assert.equal(frame.hidden, true);
    await list.children[0].emit('click');
    assert.equal(h.document.querySelectorAll('.notification-detail-content-frame').length, 1);
    assert.equal(h.document.querySelectorAll('.notification-detail-content-frame')[0], frame);
    assert.equal(h.bridge.attachments.length, 1);
});

test('公告自动已读且当前分类全部已读使用对应 API', async () => {
    const announcement = pageMessage({
        id: 'announcement', category: 'announcement', title: 'Announcement', readTime: null
    });
    const h = await pageHarness({messages: [announcement], categoryUnreadCount: 1});
    h.requests.enqueue(response(Object.assign({}, announcement, {readTime: 2})));
    await h.document.getElementById('notificationList').children[0].emit('click');
    await flush();
    assert.ok(h.requests.calls.some(call => call.url.includes('/api/notifications/announcement/read')
        && call.init.method === 'POST'));
    assert.equal(h.document.getElementById('notificationUnreadCount').textContent, '');

    h.requests.enqueue(response({messages: [], categoryUnreadCount: 0}));
    await h.document.filterButtons.find(button => button.dataset.category === 'announcement').emit('click');
    await flush();
    h.requests.enqueue(response({}));
    h.requests.enqueue(response({messages: [], categoryUnreadCount: 0}));
    await h.document.getElementById('notificationMarkCategoryRead').emit('click');
    assert.ok(h.requests.calls.some(call => call.url === '/api/notifications/read-all?category=announcement'
        && call.init.method === 'POST'));
});

test('HTML 与 CSS 保留 CSP、主题、可见性和滚动布局边界', () => {
    assert.match(PAGE_HTML, /frame-src 'self'; child-src 'self'/);
    assert.match(PAGE_HTML, /default-src 'self'; script-src 'self'; style-src 'self'/);
    assert.match(PAGE_HTML, /connect-src 'self'/);
    assert.match(PAGE_HTML, /object-src 'none'; base-uri 'none'; form-action 'none'/);
    assert.match(PAGE_HTML, /id="notificationContentFrames"/);
    assert.match(PAGE_HTML, /src="\/js\/pixiv-navigation\.js"/);
    assert.doesNotMatch(CSS, /(^|\n)body\s*\{/);
    assert.match(CSS, /--notification-brand:\s*var\(--brand, #0096fa\)/);
    assert.match(CSS, /html\[data-theme="dark"\]\s*\{[^}]*--notification-brand:\s*var\(--brand, #4bb3ff\)/s);
    assert.match(CSS, /\.notification-detail-content-frame\[hidden\]\s*\{\s*display:\s*none;/s);
    assert.match(CSS, /\.notification-detail-content-frame\s*\{[^}]*height:\s*1px;[^}]*overflow:\s*hidden;/s);
    assert.doesNotMatch(CSS, /\.notification-detail-content-frame\s*\{[^}]*min-height:/s);
    assert.match(CSS, /\.notification-page\s*\{[^}]*height:\s*100dvh;[^}]*display:\s*flex;[^}]*overflow:\s*hidden;/s);
    assert.match(CSS, /@media \(max-width:\s*760px\)[\s\S]*\.notification-page\s*\{[^}]*height:\s*auto;[^}]*overflow:\s*visible;/s);
});
