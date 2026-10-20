'use strict';

const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {pathToFileURL} = require('node:url');

const REPO = path.resolve(__dirname, '../../../..');
const STATIC = path.join(__dirname, '..', '..', 'main', 'resources', 'static');
const I18N_SOURCE = fs.readFileSync(path.join(REPO,
    'pixivdownload-app/src/main/resources/static/js/pixiv-i18n.js'), 'utf8');
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
        event.currentTarget = null;
        await Promise.all(results.filter(result => result && typeof result.then === 'function'));
    }
    matches(selector) {
        if (selector === ':popover-open') return !!this._popoverOpen;
        if (selector.startsWith('.')) return this._classes().includes(selector.slice(1));
        const attribute = /^\[([^=]+)="([\s\S]*)"\]$/.exec(selector);
        if (attribute) return this.getAttribute(attribute[1]) === attribute[2];
        const presentAttribute = /^\[([^=]+)\]$/.exec(selector);
        if (presentAttribute) return this.getAttribute(presentAttribute[1]) !== null;
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
        getElementById: id => elements[id] || body.querySelector('[id="' + id + '"]'),
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

async function pageHarness(initialSnapshot, {surveyBridge = false, search = '', startupResponses = []} = {}) {
    const document = createDocument({filters: true});
    const location = createLocation();
    location.setSearch(search);
    const window = createWindow(location);
    const requests = fetchQueue();
    requests.enqueue(response(initialSnapshot));
    startupResponses.forEach(value => requests.enqueue(value));
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
        URL, URLSearchParams, Intl, AbortController, DOMException,
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

async function slotHarness(snapshot) {
    const document = createDocument({slot: true});
    const location = createLocation();
    const window = createWindow(location);
    const requests = fetchQueue();
    const abort = new AbortController();
    requests.enqueue(response(snapshot));
    let initializer;
    let cleanup;
    window.PixivBatch = {queueTypes: {registerUiModule(fn) { initializer = fn; }}};
    const sandbox = {window, document, location, fetch: requests.fetch, URLSearchParams, AbortController,
        HTMLElement: function HTMLElement() {}};
    vm.runInNewContext(SLOT_SOURCE, sandbox);
    initializer({signal: abort.signal, isActive: () => !abort.signal.aborted, onCleanup(fn) { cleanup = fn; }});
    await flush();
    return {document, window, location, requests, cleanup() { abort.abort(); cleanup(); }};
}

function listTitle(document) {
    const title = document.getElementById('notificationList').querySelector('.notification-item-title');
    return title ? title.textContent : '';
}

test('下载页站内信槽位保留节点热切语言，并在未授权或 publication 清理时撤销', async () => {
    const document = createDocument({slot: true});
    const location = createLocation();
    const window = createWindow(location);
    const requests = fetchQueue();
    requests.enqueue(response({messages: [pageMessage()], unreadCount: 3}));
    const {parse} = await import(pathToFileURL(path.join(REPO, 'scripts/i18n/lib/properties-parser.mjs')));
    const bundles = {};
    for (const [lang, suffix] of [['en-US', '_en'], ['zh-CN', '']]) {
        const parsed = parse(fs.readFileSync(path.join(STATIC, '..', 'i18n', 'web',
            'notification' + suffix + '.properties'), 'utf8'));
        assert.deepEqual(parsed.errors, []);
        bundles[lang] = Object.fromEntries(parsed.entries.map(entry => [entry.key, entry.value]));
    }
    window.document = document;
    window.fetch = async url => {
        const parsed = new URL(url, location.origin);
        const lang = parsed.searchParams.get('lang');
        if (parsed.pathname === '/api/i18n/meta') return response({
            currentLang: lang, defaultLang: 'en-US',
            supportedLocales: Object.keys(bundles).map(tag => ({tag}))
        });
        assert.equal(parsed.pathname, '/api/i18n/messages/notification');
        return response({messages: bundles[lang]});
    };
    let initializer;
    let cleanup;
    let prepareCalls = 0;
    window.PixivBatch = {queueTypes: {registerUiModule(fn) { initializer = fn; }}};
    window.PixivVue = {prepareSlotHosts() { prepareCalls++; }};
    const sandbox = {
        window, document, location, fetch: requests.fetch, URLSearchParams, AbortController,
        HTMLElement: function HTMLElement() {},
        console: {warn() {}, error() {}, log() {}}
    };
    sandbox.HTMLElement.prototype.popover = '';
    vm.createContext(sandbox);
    vm.runInContext(I18N_SOURCE, sandbox);
    sandbox.pageI18n = await window.PixivI18n.create({namespaces: ['notification'], lang: 'en-US'});
    vm.runInContext(SLOT_SOURCE, sandbox);
    assert.equal(typeof initializer, 'function');
    initializer({signal: new AbortController().signal, isActive: () => true, onCleanup(fn) { cleanup = fn; }});
    await flush();

    const host = document.querySelector('[data-vue-slot="topbar-actions"]');
    const button = host.querySelector('.notification-inbox-button');
    const popover = document.body.querySelector('.notification-inbox-popover');
    assert.equal(prepareCalls, 1);
    assert.equal(button.getAttribute('aria-label'), bundles['en-US']['inbox.open']);
    assert.equal(popover.getAttribute('popover'), 'auto');
    assert.equal(button.querySelector('.notification-inbox-badge').textContent, '3');
    const markRead = popover.querySelector('.notification-popover-mark-read');
    assert.equal(markRead.textContent, bundles['en-US']['inbox.mark-all-read']);
    assert.match(requests.calls[0].url, /lang=en-US/);
    assert.equal(window.intervalCount(), 1);

    popover.showPopover();
    const category = popover.querySelector('.notification-category');
    const title = popover.querySelector('.notification-item-title');
    for (const lang of ['zh-CN', 'en-US']) {
        sandbox.pageI18n = await sandbox.pageI18n.setLanguage(lang);
        sandbox.pageI18n.apply();
        assert.equal(host.querySelector('.notification-inbox-button'), button);
        assert.equal(document.body.querySelector('.notification-inbox-popover'), popover);
        assert.equal(popover.querySelector('.notification-category'), category);
        assert.equal(popover.matches(':popover-open'), true);
        assert.equal(button.getAttribute('aria-label'), bundles[lang]['inbox.open']);
        assert.equal(button.getAttribute('title'), bundles[lang]['inbox.open']);
        assert.equal(popover.getAttribute('aria-label'), bundles[lang]['inbox.latest']);
        assert.equal(popover.querySelector('strong').textContent, bundles[lang]['inbox.latest']);
        assert.equal(popover.querySelector('.notification-popover-all').textContent, bundles[lang]['inbox.view-all']);
        assert.equal(markRead.textContent, bundles[lang]['inbox.mark-all-read']);
        assert.equal(category.textContent, bundles[lang]['inbox.category.system']);
        assert.equal(popover.querySelector('.notification-item-title'), title);
        assert.equal(title.textContent, pageMessage().title);
        assert.equal(window.intervalCount(), 1);
        assert.equal(requests.calls.length, 1);
    }

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
        fetch: deniedRequests.fetch, URLSearchParams, AbortController,
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

for (const oldStatus of [200, 401, 503]) {
    test(`重新打开同一消息后忽略旧详情响应 ${oldStatus}`, async () => {
        const h = await pageHarness({messages: [], categoryUnreadCount: 0});
        const older = deferred();
        h.requests.enqueue(older.promise);
        h.location.setSearch('?id=first');
        await h.window.emit('popstate');
        h.requests.enqueue(response(pageMessage({id: 'second', title: 'Second'})));
        h.location.setSearch('?id=second');
        await h.window.emit('popstate');
        await flush();
        h.requests.enqueue(response(pageMessage({id: 'first', title: 'Current'})));
        h.location.setSearch('?id=first');
        await h.window.emit('popstate');
        await flush();
        older.resolve(response(pageMessage({id: 'first', title: 'Stale'}), oldStatus));
        await flush();
        assert.equal(h.document.getElementById('notificationDetail').querySelector('h2')?.textContent,
            'Current');
        assert.equal(h.location.pathname, '/pixiv-notifications.html');
        assert.equal(h.requests.calls[1].init.signal.aborted, true);
    });
}

test('离页取消列表与详情查询，返回后恢复刷新并保留当前消息', async () => {
    const h = await pageHarness({messages: [], categoryUnreadCount: 0});
    const oldList = deferred();
    h.requests.enqueue(oldList.promise);
    await h.document.emit('visibilitychange');
    const oldDetail = deferred();
    h.requests.enqueue(oldDetail.promise);
    h.location.setSearch('?id=first');
    await h.window.emit('popstate');
    assert.equal(h.window.intervalCount(), 1);
    await h.window.emit('pagehide');
    assert.equal(h.window.intervalCount(), 0);
    assert.equal(h.requests.calls[1].init.signal.aborted, true);
    assert.equal(h.requests.calls[2].init.signal.aborted, true);
    await h.document.emit('visibilitychange');
    assert.equal(h.requests.calls.length, 3);
    oldList.resolve(response({}, 401));
    oldDetail.resolve(response(pageMessage({id: 'first', title: 'Stale'})));
    await flush();
    assert.equal(h.location.pathname, '/pixiv-notifications.html');
    assert.equal(h.document.getElementById('notificationDetail').querySelector('h2'), null);
    const current = pageMessage({id: 'first', title: 'Restored'});
    h.requests.enqueue(response({messages: [current], categoryUnreadCount: 0}));
    await h.window.emit('pageshow', {persisted: true});
    await flush();
    assert.equal(h.window.intervalCount(), 1);
    assert.equal(listTitle(h.document), 'Restored');
    assert.equal(h.document.getElementById('notificationDetail').querySelector('h2').textContent, 'Restored');
});

test('列表查询取消旧响应且当前鉴权失败仍跳转登录', async () => {
    const h = await pageHarness({messages: [], categoryUnreadCount: 0});
    const older = deferred();
    h.requests.enqueue(older.promise);
    await h.document.emit('visibilitychange');
    h.requests.enqueue(response({messages: [pageMessage({title: 'Current'})], categoryUnreadCount: 0}));
    await h.document.emit('visibilitychange');
    await flush();
    older.resolve(response({}, 401));
    await flush();
    assert.equal(listTitle(h.document), 'Current');
    assert.equal(h.location.pathname, '/pixiv-notifications.html');
    assert.equal(h.requests.calls[1].init.signal.aborted, true);
    h.requests.enqueue(response({}, 401));
    await h.document.emit('visibilitychange');
    await flush();
    assert.equal(h.location.pathname, '/login.html');
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

for (const category of ['download', 'system', 'announcement', 'survey']) {
    for (const entry of ['列表', '直达链接']) {
        test(`${category} 从${entry}打开详情后自动已读且不重复提交`, async () => {
            const message = pageMessage({id: category, category, readTime: null});
            const reading = deferred();
            const h = await pageHarness({messages: [message], categoryUnreadCount: 1}, entry === '直达链接'
                ? {search: '?id=' + message.id, startupResponses: [reading.promise]} : {});
            const list = h.document.getElementById('notificationList');
            if (entry === '列表') {
                assert.equal(h.requests.calls.filter(call => call.init.method === 'POST').length, 0);
                h.requests.enqueue(reading.promise);
                await list.children[0].emit('click');
            }
            await list.children[0].emit('click');
            const readCalls = () => h.requests.calls.filter(call => call.init.method === 'POST');
            assert.equal(readCalls().length, 1);
            assert.equal(readCalls()[0].url, `/api/notifications/${category}/read?lang=en-US`);
            const titleNode = h.document.getElementById('notificationDetail').querySelector('h2');
            h.requests.enqueue(response({messages: [{...message, readTime: 2}], categoryUnreadCount: 0}));
            reading.resolve(response(Object.assign({}, message, {readTime: 2})));
            await flush();
            assert.equal(h.document.getElementById('notificationUnreadCount').textContent, '');
            assert.equal(list.children[0].classList.contains('unread'), false);
            const detail = h.document.getElementById('notificationDetail');
            assert.equal(detail.querySelector('h2'), titleNode);
            assert.equal(detail.querySelector('h2').textContent, message.title);
            assert.equal(detail.querySelector('[data-i18n="inbox.mark-read"]'), null);
            await list.children[0].emit('click');
            assert.equal(readCalls().length, 1);
        });
    }
}

test('未读筛选中的消息自动已读后移出列表并保留详情与操作链接', async () => {
    const message = pageMessage({category: 'download', readTime: null, actionUrl: '/pixiv-batch.html'});
    const h = await pageHarness({messages: [message], categoryUnreadCount: 1});
    h.requests.enqueue(response({messages: [message], categoryUnreadCount: 1}));
    const filter = h.document.getElementById('notificationUnreadOnly');
    filter.checked = true;
    await filter.emit('change');
    await flush();
    h.requests.enqueue(response(Object.assign({}, message, {readTime: 2})));
    h.requests.enqueue(response({messages: [], categoryUnreadCount: 0}));
    await h.document.getElementById('notificationList').children[0].emit('click');
    await flush();
    assert.equal(h.document.getElementById('notificationList').children.length, 0);
    const detail = h.document.getElementById('notificationDetail');
    assert.equal(detail.querySelector('h2').textContent, message.title);
    assert.equal(detail.querySelector('.notification-action-link').href, 'https://local.test/pixiv-batch.html');
    assert.equal(h.location.search, '?id=' + message.id);
});

test('自动已读失败保留未读状态并提示，重新打开可重试', async () => {
    const message = pageMessage({category: 'system', readTime: null, deletable: false});
    const h = await pageHarness({messages: [message], categoryUnreadCount: 1});
    h.requests.enqueue(response({}, 503));
    const list = h.document.getElementById('notificationList');
    await list.children[0].emit('click');
    await flush();
    assert.equal(list.children[0].classList.contains('unread'), true);
    assert.equal(h.document.getElementById('notificationUnreadCount').textContent, '1');
    assert.ok(h.document.getElementById('notificationDetailActionStatus').textContent);
    h.requests.enqueue(response(Object.assign({}, message, {readTime: 2})));
    h.requests.enqueue(response({messages: [{...message, readTime: 2}], categoryUnreadCount: 0}));
    await list.children[0].emit('click');
    await flush();
    assert.equal(list.children[0].classList.contains('unread'), false);
});

test('已读完成时更新原消息列表且保留后来打开的详情', async () => {
    const first = pageMessage({id: 'first', readTime: null});
    const second = pageMessage({id: 'second', title: 'Second'});
    const h = await pageHarness({messages: [first, second], categoryUnreadCount: 1});
    const reading = deferred();
    h.requests.enqueue(reading.promise);
    const list = h.document.getElementById('notificationList');
    await list.children[0].emit('click');
    await list.children[1].emit('click');
    h.requests.enqueue(response({messages: [{...first, readTime: 2}, second], categoryUnreadCount: 0}));
    reading.resolve(response({...first, readTime: 2}));
    await flush();
    assert.equal(list.children[0].classList.contains('unread'), false);
    assert.equal(h.document.getElementById('notificationUnreadCount').textContent, '');
    assert.equal(h.document.getElementById('notificationDetail').querySelector('h2').textContent, 'Second');
});

test('已读迟到回执不重复扣减已刷新的计数或覆盖当前正文', async () => {
    const first = pageMessage({id: 'first', title: 'Original', readTime: null});
    const second = pageMessage({id: 'second', readTime: null});
    const h = await pageHarness({messages: [first, second], categoryUnreadCount: 2});
    const reading = deferred();
    h.requests.enqueue(reading.promise);
    const list = h.document.getElementById('notificationList');
    await list.children[0].emit('click');
    h.requests.enqueue(response({messages: [{...first, title: 'Current', readTime: 2}, second],
        categoryUnreadCount: 1}));
    await h.document.emit('visibilitychange');
    await flush();
    await list.children[0].emit('click');
    h.requests.enqueue(response({messages: [{...first, title: 'Current', readTime: 2}, second],
        categoryUnreadCount: 1}));
    reading.resolve(response({...first, readTime: 2}));
    await flush();
    assert.equal(h.document.getElementById('notificationUnreadCount').textContent, '1');
    assert.equal(h.document.getElementById('notificationDetail').querySelector('h2').textContent, 'Current');
});

for (const succeeds of [true, false]) {
    test(`删除${succeeds ? '成功' : '失败'}时不改写后来打开的详情`, async () => {
        const first = pageMessage({id: 'first', title: 'First', hasHtmlContent: true});
        const second = pageMessage({id: 'second', title: 'Second'});
        const h = await pageHarness({messages: [first, second], categoryUnreadCount: 0});
        h.feedback.confirm = async () => true;
        const list = h.document.getElementById('notificationList');
        await list.children[0].emit('click');
        const deleting = deferred();
        h.requests.enqueue(deleting.promise);
        const button = h.document.getElementById('notificationDetail').querySelector('[data-i18n="inbox.delete"]');
        const operation = button.emit('click');
        await flush();
        assert.equal(button.disabled, true);
        await list.children[1].emit('click');
        if (succeeds) h.requests.enqueue(response({messages: [second], categoryUnreadCount: 0}));
        deleting.resolve(response({}, succeeds ? 204 : 503));
        await operation;
        assert.equal(list.children.length, succeeds ? 1 : 2);
        assert.equal(h.document.querySelectorAll('.notification-detail-content-frame').length,
            succeeds ? 0 : 1);
        assert.equal(h.document.getElementById('notificationDetail').querySelector('h2').textContent, 'Second');
        assert.equal(h.document.getElementById('notificationDetailActionStatus').textContent, '');
        if (!succeeds) assert.equal(button.disabled, false);
    });
}

test('删除确认不沿用离开详情前的操作', async () => {
    const first = pageMessage({id: 'first'});
    const second = pageMessage({id: 'second'});
    const h = await pageHarness({messages: [first, second], categoryUnreadCount: 0});
    const confirmation = deferred();
    h.feedback.confirm = () => confirmation.promise;
    const list = h.document.getElementById('notificationList');
    await list.children[0].emit('click');
    const operation = h.document.getElementById('notificationDetail')
        .querySelector('[data-i18n="inbox.delete"]').emit('click');
    await list.children[1].emit('click');
    await list.children[0].emit('click');
    confirmation.resolve(true);
    await operation;
    assert.equal(h.requests.calls.filter(call => call.init.method === 'DELETE').length, 0);
    assert.equal(list.children.length, 2);
});

test('已读后的列表读取失败保留详情并允许随后恢复', async () => {
    const message = pageMessage({readTime: null});
    const h = await pageHarness({messages: [message], categoryUnreadCount: 1});
    h.requests.enqueue(response({...message, readTime: 2}));
    h.requests.enqueue(response({}, 503));
    await h.document.getElementById('notificationList').children[0].emit('click');
    await flush();
    const status = h.document.getElementById('notificationStatus');
    assert.equal(status.hidden, false);
    assert.ok(status.textContent);
    assert.equal(h.document.getElementById('notificationDetail').querySelector('h2').textContent, message.title);
    h.requests.enqueue(response({messages: [{...message, readTime: 2}], categoryUnreadCount: 0}));
    await h.document.emit('visibilitychange');
    await flush();
    assert.equal(h.document.getElementById('notificationUnreadCount').textContent, '');
    assert.equal(status.hidden, true);
});

test('离页期间已读完成不再查询，返回后读取持久结果', async () => {
    const message = pageMessage({readTime: null});
    const h = await pageHarness({messages: [message], categoryUnreadCount: 1});
    const reading = deferred();
    h.requests.enqueue(reading.promise);
    await h.document.getElementById('notificationList').children[0].emit('click');
    await h.window.emit('pagehide');
    reading.resolve(response({...message, readTime: 2}));
    await flush();
    assert.equal(h.requests.calls.length, 2);
    assert.equal(h.window.intervalCount(), 0);
    h.requests.enqueue(response({messages: [{...message, readTime: 2}], categoryUnreadCount: 0}));
    await h.window.emit('pageshow', {persisted: true});
    await flush();
    assert.equal(h.document.getElementById('notificationUnreadCount').textContent, '');
    assert.equal(h.document.getElementById('notificationDetail').querySelector('h2').textContent, message.title);
    assert.equal(h.window.intervalCount(), 1);
});

test('已读失败不写入重新打开后的详情', async () => {
    const first = pageMessage({id: 'first', readTime: null});
    const second = pageMessage({id: 'second'});
    const h = await pageHarness({messages: [first, second], categoryUnreadCount: 1});
    const reading = deferred();
    h.requests.enqueue(reading.promise);
    const list = h.document.getElementById('notificationList');
    await list.children[0].emit('click');
    await list.children[1].emit('click');
    await list.children[0].emit('click');
    reading.resolve(response({}, 503));
    await flush();
    assert.equal(h.document.getElementById('notificationDetailActionStatus').textContent, '');
    assert.equal(list.children[0].classList.contains('unread'), true);
});

test('全部已读完成后刷新当前分类，不撤销新分类的未读详情', async () => {
    const announcement = pageMessage({id: 'announcement', category: 'announcement', readTime: null});
    const download = pageMessage({id: 'download', category: 'download', readTime: null});
    const h = await pageHarness({messages: [announcement], categoryUnreadCount: 1});
    h.requests.enqueue(response({messages: [announcement], categoryUnreadCount: 1}));
    await h.document.filterButtons.find(button => button.dataset.category === 'announcement').emit('click');
    await flush();
    const markingAll = deferred();
    h.requests.enqueue(markingAll.promise);
    assert.equal(h.document.getElementById('notificationMarkCategoryRead').disabled, false);
    const operation = h.document.getElementById('notificationMarkCategoryRead').emit('click');
    h.requests.enqueue(response({messages: [download], categoryUnreadCount: 1}));
    await h.document.filterButtons.find(button => button.dataset.category === 'download').emit('click');
    await flush();
    const reading = deferred();
    h.requests.enqueue(reading.promise);
    await h.document.getElementById('notificationList').children[0].emit('click');
    h.requests.enqueue(response({messages: [download], categoryUnreadCount: 1}));
    const unread = h.document.getElementById('notificationUnreadOnly');
    unread.checked = true;
    await unread.emit('change');
    await flush();
    h.requests.enqueue(response({messages: [download], categoryUnreadCount: 1}));
    markingAll.resolve(response(1));
    await operation;
    assert.match(h.requests.calls.at(-1).url, /category=download/);
    assert.equal(h.document.getElementById('notificationDetail').querySelector('h2').textContent, download.title);
    assert.equal(h.document.getElementById('notificationUnreadCount').textContent, '1');
    h.requests.enqueue(response({messages: [], categoryUnreadCount: 0}));
    reading.resolve(response({...download, readTime: 2}));
    await flush();
    assert.equal(h.document.getElementById('notificationList').children.length, 0);
});

test('顶部消息入口携带消息标识进入详情页', async () => {
    const message = pageMessage({id: 'download with spaces', category: 'download', readTime: null});
    const h = await slotHarness({messages: [message], unreadCount: 1});
    await h.document.querySelector('.notification-popover-item').emit('click');
    assert.equal(h.location.pathname, '/pixiv-notifications.html');
    assert.equal(new URLSearchParams(h.location.search).get('id'), message.id);
    h.cleanup();
});

test('顶部旧查询失败保留当前消息节点', async () => {
    const h = await slotHarness({messages: [pageMessage()], unreadCount: 1});
    const older = deferred();
    h.requests.enqueue(older.promise);
    await h.document.emit('visibilitychange');
    h.requests.enqueue(response({messages: [pageMessage({title: 'Current'})], unreadCount: 2}));
    await h.document.emit('visibilitychange');
    await flush();
    const current = h.document.querySelector('.notification-popover-item');
    older.reject(new Error('offline'));
    await flush();
    assert.equal(h.document.querySelector('.notification-popover-item'), current);
    assert.equal(current.querySelector('.notification-item-title').textContent, 'Current');
    h.cleanup();
});

test('顶部未授权撤回后槽位刷新和页面返回不恢复入口', async () => {
    const h = await slotHarness({messages: [pageMessage()], unreadCount: 1});
    h.requests.enqueue(response({}, 403));
    await h.document.emit('visibilitychange');
    await flush();
    assert.equal(h.document.querySelector('.notification-inbox-slot'), null);
    const count = h.requests.calls.length;
    await h.window.emit('pixivbatch:slotsrendered');
    await h.window.emit('pagehide');
    await h.window.emit('pageshow', {persisted: true});
    await h.document.emit('visibilitychange');
    assert.equal(h.document.querySelector('.notification-inbox-slot'), null);
    assert.equal(h.document.querySelector('.notification-inbox-popover'), null);
    assert.equal(h.requests.calls.length, count);
    assert.equal(h.window.intervalCount(), 0);
    h.cleanup();
});

test('顶部离页取消查询，返回恢复一次轮询且保留写请求', async () => {
    const h = await slotHarness({messages: [pageMessage({readTime: null})], unreadCount: 1});
    const marking = deferred();
    h.requests.enqueue(marking.promise);
    const markButton = h.document.querySelector('.notification-popover-mark-read');
    const writing = markButton.emit('click');
    const writeSignal = h.requests.calls.at(-1).init.signal;
    const older = deferred();
    h.requests.enqueue(older.promise);
    await h.document.emit('visibilitychange');
    const readSignal = h.requests.calls.at(-1).init.signal;
    await h.window.emit('pagehide');
    assert.equal(readSignal.aborted, true);
    assert.equal(writeSignal.aborted, false);
    assert.equal(h.window.intervalCount(), 0);
    const count = h.requests.calls.length;
    marking.resolve(response(1));
    await writing;
    await h.document.emit('visibilitychange');
    assert.equal(h.requests.calls.length, count);
    h.requests.enqueue(response({messages: [pageMessage({title: 'Restored'})], unreadCount: 0}));
    await h.window.emit('pageshow', {persisted: true});
    await flush();
    assert.equal(h.window.intervalCount(), 1);
    assert.equal(h.document.querySelector('.notification-popover-mark-read').disabled, true);
    const current = h.document.querySelector('.notification-popover-item');
    older.resolve(response({messages: [pageMessage({title: 'Stale'})], unreadCount: 9}));
    await flush();
    assert.equal(h.document.querySelector('.notification-popover-item'), current);
    assert.equal(current.querySelector('.notification-item-title').textContent, 'Restored');
    h.cleanup();
    assert.equal(h.window.listenerCount('pagehide'), 0);
    assert.equal(h.window.listenerCount('pageshow'), 0);
    assert.equal(h.window.intervalCount(), 0);
});

test('顶部全部已读覆盖所有消息，阻止重复点击与旧快照回写', async () => {
    const message = pageMessage({category: 'download', readTime: null});
    const h = await slotHarness({messages: [message], unreadCount: 12});
    const oldRefresh = deferred();
    h.requests.enqueue(oldRefresh.promise);
    await h.document.emit('visibilitychange');
    const marked = deferred();
    h.requests.enqueue(marked.promise);
    const button = h.document.querySelector('.notification-popover-mark-read');
    const marking = button.emit('click');
    assert.equal(button.disabled, true);
    await button.emit('click');
    const posts = h.requests.calls.filter(call => call.init.method === 'POST');
    assert.equal(posts.length, 1);
    assert.equal(posts[0].url, '/api/notifications/read-all');
    assert.equal(posts[0].init.credentials, 'same-origin');
    h.requests.enqueue(response({messages: [Object.assign({}, message, {readTime: 2})], unreadCount: 0}));
    marked.resolve(response(12));
    await marking;
    oldRefresh.resolve(response({messages: [message], unreadCount: 12}));
    await flush();
    assert.equal(h.document.querySelector('.notification-inbox-badge').hidden, true);
    assert.equal(h.document.querySelector('.notification-popover-item').classList.contains('unread'), false);
    assert.equal(button.disabled, true);
    h.cleanup();
});

test('顶部写后查询被新查询替换时不显示加载失败', async () => {
    const h = await slotHarness({messages: [pageMessage({readTime: null})], unreadCount: 1});
    const obsolete = deferred();
    h.requests.enqueue(response(1));
    h.requests.enqueue(obsolete.promise);
    const marking = h.document.querySelector('.notification-popover-mark-read').emit('click');
    await flush();
    h.requests.enqueue(response({messages: [pageMessage()], unreadCount: 0}));
    await h.document.emit('visibilitychange');
    await flush();
    obsolete.reject(new Error('aborted'));
    await marking;
    assert.equal(h.document.querySelector('.notification-popover-status').hidden, true);
    assert.equal(h.document.querySelector('.notification-inbox-badge').hidden, true);
    h.cleanup();
});

test('顶部全部已读失败显示错误且允许重试，清理后不恢复界面', async () => {
    const h = await slotHarness({messages: [pageMessage({readTime: null})], unreadCount: 1});
    const button = h.document.querySelector('.notification-popover-mark-read');
    h.requests.enqueue(response({}, 503));
    await button.emit('click');
    assert.equal(button.disabled, false);
    assert.equal(h.document.querySelector('.notification-inbox-badge').hidden, false);
    const status = h.document.querySelector('.notification-popover-status');
    assert.equal(status.hidden, false);
    assert.equal(status.getAttribute('data-i18n'), 'notification:inbox.mark-read-failed');
    const retry = deferred();
    h.requests.enqueue(retry.promise);
    const marking = button.emit('click');
    h.cleanup();
    retry.resolve(response(1));
    await marking;
    assert.equal(h.document.querySelector('.notification-inbox-popover'), null);
    assert.equal(h.window.intervalCount(), 0);
});

test('当前分类全部已读使用对应 API', async () => {
    const h = await pageHarness({messages: [], categoryUnreadCount: 0});

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
    assert.match(CSS, /@media \(max-width:\s*[^)]+\)[\s\S]*\.notification-page\s*\{[^}]*height:\s*auto;[^}]*overflow:\s*visible;/s);
});
