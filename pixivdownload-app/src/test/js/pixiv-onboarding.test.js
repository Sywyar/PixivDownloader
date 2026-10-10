'use strict';
/*
 * 跨页新手向导下载结果入口回归测试。
 *
 * 无浏览器 / 无 jsdom：用最小 DOM 在 Node 的 vm 沙箱里按生产顺序加载真实引导模块，验证：
 *   1) 四个页面按 core → overlay → download → gallery → facade 顺序装配。
 *   2) 下载中 first-download-result 入口存在时保留旧的画廊导向文案，缺席时使用中性结果文案。
 *   3) 下载成功 first-download-result 入口存在时，仍保持旧的 await-gallery 状态与“点击画廊入口”提示。
 *   4) 下载成功 first-download-result 入口缺席时，下载页引导完成，不渲染要求点击不存在入口的阻塞提示。
 *
 * 运行： node src/test/js/pixiv-onboarding.test.js
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');

const REPO_ROOT = path.resolve(__dirname, '..', '..', '..', '..');
const SCRIPT_NAMES = [
    'pixiv-onboarding-core.js',
    'pixiv-onboarding-overlay.js',
    'pixiv-onboarding-download.js',
    'pixiv-onboarding-gallery.js',
    'pixiv-onboarding.js'
];
const SCRIPT_SOURCES = SCRIPT_NAMES.map(name => fs.readFileSync(
    path.join(__dirname, '..', '..', 'main', 'resources', 'static', 'js', name), 'utf8'));
const SCRIPT_URLS = SCRIPT_NAMES.map(name => '/js/' + name);
const PAGE_PATHS = [
    'pixivdownload-plugin-download-workbench/src/main/resources/static/pixiv-batch.html',
    'pixivdownload-plugin-download-workbench/src/main/resources/static/pixiv-batch-alt.html',
    'pixivdownload-plugin-gallery/src/main/resources/static/pixiv-gallery.html',
    'pixivdownload-plugin-gallery/src/main/resources/static/pixiv-artwork.html'
];
const STORAGE_KEY = 'pixiv_onboarding_v1';
const RESULT_ENTRY_SELECTOR = 'a.app-nav-link[data-nav-markers~="first-download-result"]';

let passed = 0;
function ok(label, cond) { assert.ok(cond, label); passed++; }

function loadOnboardingModules(sandbox) {
    SCRIPT_SOURCES.forEach((source, index) => {
        vm.runInContext(source, sandbox, { filename: SCRIPT_NAMES[index] });
    });
}

function onboardingScriptUrls(pageSource) {
    return Array.from(pageSource.matchAll(/<script\b[^>]*\bsrc="([^"]+)"/g), match => match[1])
        .filter(src => src.startsWith('/js/pixiv-onboarding'));
}

class ClassList {
    constructor() { this.values = new Set(); }
    add(name) { this.values.add(name); }
    remove(name) { this.values.delete(name); }
    contains(name) { return this.values.has(name); }
    toggle(name, force) {
        if (force === undefined ? !this.values.has(name) : !!force) {
            this.values.add(name);
        } else {
            this.values.delete(name);
        }
    }
}

class El {
    constructor(tag) {
        this.tag = String(tag).toLowerCase();
        this.attrs = {};
        this.children = [];
        this.parentNode = null;
        this.style = {};
        this.classList = new ClassList();
        this.className = '';
        this.hidden = false;
        this._html = '';
        this.offsetWidth = 260;
        this.offsetHeight = 120;
        this.offsetParent = this;
        this.textContent = '';
        this.title = '';
        this.value = '';
        this.disabled = false;
        this.focused = false;
        this._listeners = {};
        this._actionButtons = [];
    }
    setAttribute(k, v) { this.attrs[k] = String(v); }
    removeAttribute(k) { delete this.attrs[k]; }
    getAttribute(k) { return Object.prototype.hasOwnProperty.call(this.attrs, k) ? this.attrs[k] : null; }
    appendChild(child) { child.parentNode = this; this.children.push(child); return child; }
    removeChild(child) {
        this.children = this.children.filter(c => c !== child);
        child.parentNode = null;
        return child;
    }
    addEventListener(type, fn) {
        this._listeners[type] = this._listeners[type] || [];
        this._listeners[type].push(fn);
    }
    click() {
        (this._listeners.click || []).forEach(fn => fn.call(this));
    }
    focus() { this.focused = true; }
    scrollIntoView() {}
    getBoundingClientRect() { return { top: 20, left: 20, width: 120, height: 32 }; }
    get innerHTML() { return this._html; }
    set innerHTML(value) {
        this._html = String(value);
        this._actionButtons = [];
        this._messages = Array.from(this._html.matchAll(/<span data-po-message="([^"]+)">([^<]*)<\/span>/g), match => {
            const element = new El('span');
            element.setAttribute('data-po-message', decodeHtml(match[1]));
            element.textContent = decodeHtml(match[2]);
            return element;
        });
        this._input = null;
        const inputTag = /<input[^>]*id="po-name-input"[^>]*>/.exec(this._html);
        if (inputTag) {
            this._input = new El('input');
            this._input.setAttribute('data-i18n-placeholder', 'tour:onboarding.welcome.name-placeholder');
        }
        const re = /data-act="([^"]+)"/g;
        let m;
        while ((m = re.exec(this._html)) !== null) {
            const button = new El('button');
            button.setAttribute('data-act', m[1]);
            this._actionButtons.push(button);
        }
    }
    querySelector(selector) {
        if (selector === '#po-name-input') return this._input;
        if (selector === '.po-help-fab-label') {
            if (!this._helpLabel) {
                this._helpLabel = new El('span');
            }
            return this._helpLabel;
        }
        if (selector.indexOf('[data-act="') === 0) {
            const act = selector.slice(11, -2);
            return this._actionButtons.find(button => button.getAttribute('data-act') === act) || null;
        }
        return null;
    }
    querySelectorAll(selector) {
        if (selector === '[data-po-message]') return this._messages || [];
        if (selector === '[data-i18n-placeholder]') return this._input ? [this._input] : [];
        if (selector !== '[data-act]') {
            return [];
        }
        return this._actionButtons;
    }
}

function decodeHtml(value) {
    return value.replace(/&quot;/g, '"').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&');
}

function translatedClient(lang) {
    const suffix = lang === 'zh-CN' ? '' : '_en';
    const bundle = Object.fromEntries(fs.readFileSync(path.join(__dirname,
        '../../main/resources/i18n/web/tour' + suffix + '.properties'), 'utf8')
        .split(/\r?\n/).filter(line => line && !line.startsWith('#') && line.includes('='))
        .map(line => [line.slice(0, line.indexOf('=')), line.slice(line.indexOf('=') + 1)]));
    return {lang, t(key, fallback, args) {
        return String(bundle[key.replace(/^tour:/, '')] ?? fallback ?? key)
            .replace(/\{([^}]+)\}/g, (match, key) => args?.[key] ?? match);
    }, apply(root) {
        root.querySelectorAll('[data-i18n-placeholder]').forEach(node => {
            node.placeholder = this.t(node.getAttribute('data-i18n-placeholder'));
        });
    }};
}

function makeDocument(hasResultEntry) {
    const body = new El('body');
    const resultEntry = hasResultEntry ? new El('a') : null;
    const restartButton = new El('button');
    restartButton.hidden = true;
    body.appendChild(restartButton);
    const elements = {'#guide-entry': restartButton};
    return {
        body,
        resultEntry,
        elements,
        documentElement: { clientWidth: 1024, clientHeight: 768 },
        createElement: tag => new El(tag),
        addEventListener() {},
        removeEventListener() {},
        querySelector(selector) {
            if (selector === RESULT_ENTRY_SELECTOR) {
                return resultEntry;
            }
            return elements[selector] || null;
        }
    };
}

function findByClass(root, className) {
    if ((root.className || '').split(/\s+/).indexOf(className) >= 0) {
        return root;
    }
    for (const child of root.children) {
        const found = findByClass(child, className);
        if (found) {
            return found;
        }
    }
    return null;
}

function loadScenario(hasResultEntry) {
    const languageListeners = [];
    const languageRequests = [];
    const document = makeDocument(hasResultEntry);
    const storage = {};
    storage[STORAGE_KEY] = JSON.stringify({ status: 'active', phase: 'await-gallery', downloaded: true });
    const sandbox = {
        document,
        location: { href: '/pixiv-batch.html' },
        console: { warn() {}, log() {}, error() {} },
        localStorage: {
            getItem: key => Object.prototype.hasOwnProperty.call(storage, key) ? storage[key] : null,
            setItem: (key, value) => { storage[key] = String(value); }
        },
        addEventListener() {},
        removeEventListener() {},
        requestAnimationFrame(fn) { fn(); },
        setTimeout(fn) { fn(); return 1; },
        clearTimeout() {},
        setInterval() { return 1; },
        clearInterval() {},
        fetch() { throw new Error('unexpected fetch'); },
        PixivI18n: {
            onLanguageChange(listener) { languageListeners.push(listener); },
            create(options) {
                return new Promise(resolve => languageRequests.push({options, resolve}));
            }
        },
        PixivNav: {
            ready() {
                return {
                    then(resolve) {
                        resolve();
                    }
                };
            }
        }
    };
    sandbox.window = sandbox;
    vm.createContext(sandbox);
    loadOnboardingModules(sandbox);
    sandbox.PixivOnboarding.boot({
        page: 'batch',
        eligible: true,
        i18n: { t: (key, fallback) => fallback },
        sel: { firstDownloadResultEntry: RESULT_ENTRY_SELECTOR, restartButton: '#guide-entry' }
    });
    return {
        storage,
        pop: findByClass(document.body, 'po-pop'),
        resultEntry: document.resultEntry,
        sandbox, document, languageListeners, languageRequests,
        onboarding: sandbox.PixivOnboarding
    };
}

function clickAction(pop, act) {
    const button = pop.querySelectorAll('[data-act]').find(b => b.getAttribute('data-act') === act);
    assert.ok(button, 'expected action button: ' + act);
    button.click();
}

function loadMonitorScenario(hasResultEntry, holdAtStart, options = {}) {
    const document = makeDocument(hasResultEntry);
    const startButton = new El('button');
    let startButtonClicks = 0;
    startButton.addEventListener('click', () => { startButtonClicks++; });
    document.elements['#btn-start'] = startButton;
    const storage = {};
    storage[STORAGE_KEY] = JSON.stringify({ status: 'active', phase: 'download' });
    let nextTimer = 1;
    let beforeStartCalls = 0;
    let now = Date.now();
    const timers = new Map();
    const sandbox = {
        document,
        location: { href: '/pixiv-batch.html' },
        console: { warn() {}, log() {}, error() {} },
        localStorage: {
            getItem: key => Object.prototype.hasOwnProperty.call(storage, key) ? storage[key] : null,
            setItem: (key, value) => { storage[key] = String(value); }
        },
        addEventListener() {},
        removeEventListener() {},
        requestAnimationFrame(fn) { fn(); },
        setTimeout(fn) { fn(); return nextTimer++; },
        clearTimeout() {},
        setInterval(fn) { const id = nextTimer++; timers.set(id, fn); fn(); return id; },
        clearInterval(id) { timers.delete(id); },
        Date: class extends Date { static now() { return now; } },
        fetch() { return Promise.resolve({ ok: false, status: 404, json: () => Promise.resolve(null) }); },
        PixivNav: {
            ready() {
                return {
                    then(resolve) {
                        resolve();
                    }
                };
            }
        }
    };
    sandbox.window = sandbox;
    vm.createContext(sandbox);
    loadOnboardingModules(sandbox);
    sandbox.PixivOnboarding.boot({
        page: 'batch',
        eligible: true,
        i18n: { t: (key, fallback) => fallback },
        sel: {
            cookieCard: '#tools-drawer',
            scriptsCard: '#tools-drawer',
            tabs: '.tabs',
            singleImportTab: '#tab-single-import',
            importTextarea: '#single-import-textarea',
            importButton: '#btn-single-import',
            filtersCard: '#extra-filters-card',
            settingsCard: '#download-settings-card',
            startButton: '#btn-start',
            progressArea: '#download-progress-area',
            firstDownloadResultEntry: RESULT_ENTRY_SELECTOR
        },
        hooks: {
            switchToSingleImport() {},
            isExampleQueued: () => true,
            beforeStart: () => { beforeStartCalls++; },
            isRunning: () => !holdAtStart,
            exampleProgress: () => options.progress,
            retryExample: () => { options.retried = true; return true; }
        }
    });

    let pop = findByClass(document.body, 'po-pop');
    clickAction(pop, 'next');
    pop = findByClass(document.body, 'po-pop');
    clickAction(pop, 'next');
    pop = findByClass(document.body, 'po-pop');
    clickAction(pop, 'next');
    pop = findByClass(document.body, 'po-pop');
    clickAction(pop, 'next');
    pop = findByClass(document.body, 'po-pop');
    clickAction(pop, 'next');
    pop = findByClass(document.body, 'po-pop');
    clickAction(pop, 'next');
    pop = findByClass(document.body, 'po-pop');
    clickAction(pop, 'next');

    const spot = findByClass(document.body, 'po-spot');
    if (holdAtStart && spot) spot.click();
    return {
        pop: findByClass(document.body, 'po-pop'),
        resultEntry: document.resultEntry,
        beforeStartCalls,
        startButtonClicks,
        startButtonFocused: startButton.focused,
        sandbox, storage, timers,
        advance(ms) { now += ms; Array.from(timers.values()).forEach(fn => fn()); }
    };
}

async function main() {
    {
        const h = loadScenario(true), ctx = h.sandbox.PixivOnboardingRuntime;
        const entry = h.document.querySelector('#guide-entry');
        assert.equal(entry.hidden, false);
        assert.equal(entry.disabled, true);
        assert.equal(findByClass(h.document.body, 'pt-help-fab'), null);
        ctx.skip();
        assert.equal(entry.disabled, false);
        assert.equal(entry.focused, true);
        h.onboarding.boot(ctx.config);
        assert.equal(ctx.overlay.pop, null);
        let restarts = 0;
        ctx.download.phaseWelcome = () => { restarts++; };
        entry.click();
        assert.equal(restarts, 1);
        assert.equal(entry.disabled, true);
        h.onboarding.refreshFab(translatedClient('en-US'));
        assert.equal(entry.getAttribute('aria-label'), translatedClient('en-US').t('tour:common.help'));
        assert.equal(entry.title, entry.getAttribute('aria-label'));
        passed++;
    }
    for (const status of ['skipped', 'failed', 'paused', 'cancelled', 'removed']) {
        const options = {progress:{status, running:false, message:'<unsafe> reason'}};
        const h = loadMonitorScenario(false, true, options);
        assert.ok(h.pop.innerHTML.includes('下载未成功'), status);
        assert.ok(!h.pop.innerHTML.includes('po-spinner'), status);
        assert.equal(h.pop.style.top, '', status + ': centered result clears target coordinates');
        assert.equal(h.pop.style.left, '', status + ': centered result clears target coordinates');
        assert.ok(h.pop.innerHTML.includes('&lt;unsafe&gt;'), status);
        assert.notEqual(JSON.parse(h.storage[STORAGE_KEY]).downloaded, true, status);
        clickAction(h.pop, 'retry');
        assert.equal(options.retried, true);
        assert.ok(h.pop.innerHTML.includes('粘贴示例作品链接'));
        passed++;
    }
    {
        const h = loadMonitorScenario(false, true, {progress:{status:'completed', running:false}});
        assert.ok(h.pop.innerHTML.includes('下载成功'));
        assert.equal(JSON.parse(h.storage[STORAGE_KEY]).downloaded, true);
        passed++;
    }
    {
        const h = loadMonitorScenario(false, false, {progress:{status:'downloading', running:true}});
        h.advance(300000);
        assert.ok(h.pop.innerHTML.includes('暂未确认'));
        clickAction(h.pop, 'retry');
        assert.ok(h.pop.innerHTML.includes('po-spinner'));
        clickAction(h.pop, 'skip');
        h.advance(300000);
        assert.equal(h.sandbox.PixivOnboardingRuntime.overlay.pop, null);
        passed++;
    }
    for (const pagePath of PAGE_PATHS) {
        const pageSource = fs.readFileSync(path.join(REPO_ROOT, pagePath), 'utf8');
        assert.deepStrictEqual(onboardingScriptUrls(pageSource), SCRIPT_URLS,
            pagePath + ' must load the onboarding modules in dependency order');
        passed++;
    }

    {
        const { startButtonClicks, startButtonFocused } = loadMonitorScenario(false, true);
        ok('P: 聚光洞口把点击转交给层叠上下文内的真实控件', startButtonClicks === 1);
        ok('P: 聚光洞口把焦点转交给真实控件', startButtonFocused);
    }

    {
        const { pop, resultEntry, beforeStartCalls } = loadMonitorScenario(true);

        ok('M1: 下载中 marker 存在时保留旧的画廊导向文案',
            pop && pop.innerHTML.indexOf('完成后会自动带你去画廊查看') >= 0);
        ok('M1: 下载中 marker 存在时仍可解析结果入口', resultEntry !== null);
        ok('M1: 开始下载前执行页面准备钩子', beforeStartCalls === 1);
    }

    {
        const { pop, resultEntry } = loadMonitorScenario(false);

        ok('M2: 下载中 marker 缺席时不再提示会自动带去画廊',
            pop && pop.innerHTML.indexOf('完成后会自动带你去画廊查看') < 0);
        ok('M2: 下载中 marker 缺席时提示查看下载结果',
            pop && pop.innerHTML.indexOf('完成后会提示你查看下载结果') >= 0);
        ok('M2: 下载中 marker 缺席时没有结果入口元素', resultEntry === null);
    }

    {
        const { storage, pop, resultEntry, onboarding } = loadScenario(true);
        const state = JSON.parse(storage[STORAGE_KEY]);

        ok('API: facade 保留 boot', typeof onboarding.boot === 'function');
        ok('API: facade 保留 restart', typeof onboarding.restart === 'function');
        ok('API: facade 保留 refreshFab', typeof onboarding.refreshFab === 'function');
        ok('API: facade 保留 getName', typeof onboarding.getName === 'function');
        ok('API: facade 保留示例作品常量',
            onboarding.EXAMPLE_ID === '145378118'
            && onboarding.EXAMPLE_URL === 'https://www.pixiv.net/artworks/145378118');
        ok('A: marker 存在时仍停在 await-gallery，供画廊页续跑', state.status === 'active' && state.phase === 'await-gallery');
        ok('A: marker 存在时保留旧的点击画廊入口文案',
            pop && pop.innerHTML.indexOf('点击高亮的「画廊」入口') >= 0);
        ok('A: marker 存在时高亮该入口', resultEntry.classList.contains('po-interactive'));
    }

    {
        const { storage, pop, resultEntry } = loadScenario(false);
        const state = JSON.parse(storage[STORAGE_KEY]);

        ok('B: marker 缺席时引导标记为 completed', state.status === 'completed');
        ok('B: marker 缺席时不渲染点击画廊入口的阻塞提示',
            pop && pop.innerHTML.indexOf('点击高亮的「画廊」入口') < 0);
        ok('B: marker 缺席时显示中性的下载结果完成提示',
            pop && pop.innerHTML.indexOf('当前没有可打开的下载结果入口') >= 0);
        ok('B: marker 缺席时没有被高亮的入口元素', resultEntry === null);
    }

    {
        const {sandbox, storage, onboarding} = loadScenario(true);
        const ctx = sandbox.PixivOnboardingRuntime;
        ctx.gallery.phaseGalleryRedirect();
        const pop = ctx.overlay.pop;
        const go = pop.querySelector('[data-act="go"]');
        const state = storage[STORAGE_KEY];
        onboarding.refreshFab(translatedClient('en-US'));
        const title = pop.querySelectorAll('[data-po-message]').find(node =>
            JSON.parse(node.getAttribute('data-po-message')).key === 'onboarding.redirect.title');
        assert.strictEqual(title.textContent, translatedClient('en-US').t('tour:onboarding.redirect.title'));
        assert.strictEqual(ctx.overlay.pop, pop);
        assert.strictEqual(pop.querySelector('[data-act="go"]'), go);
        assert.strictEqual(storage[STORAGE_KEY], state);
        go.click();
        assert.strictEqual(sandbox.location.href, '/pixiv-batch.html');
        passed++;

        ctx.patchState({name: '', phase: 'welcome'});
        ctx.download.phaseWelcome();
        const namePop = ctx.overlay.pop;
        const input = namePop.querySelector('#po-name-input');
        input.value = 'draft <name>';
        onboarding.refreshFab(translatedClient('zh-CN'));
        assert.strictEqual(ctx.overlay.pop, namePop);
        assert.strictEqual(namePop.querySelector('#po-name-input'), input);
        assert.strictEqual(input.value, 'draft <name>');
        assert.strictEqual(input.focused, true);
        assert.strictEqual(input.placeholder, translatedClient('zh-CN').t('tour:onboarding.welcome.name-placeholder'));
        passed++;
    }

    {
        const {sandbox, languageListeners, languageRequests} = loadScenario(true);
        const ctx = sandbox.PixivOnboardingRuntime;
        ctx.gallery.phaseGalleryRedirect();
        const pop = ctx.overlay.pop;
        const oldChange = languageListeners[0]({lang: 'zh-CN'});
        const newChange = languageListeners[0]({lang: 'en-US'});
        languageRequests[1].resolve(translatedClient('en-US'));
        await newChange;
        languageRequests[0].resolve(translatedClient('zh-CN'));
        await oldChange;
        assert.strictEqual(ctx.i18n.lang, 'en-US');
        assert.strictEqual(ctx.overlay.pop, pop);
        const title = pop.querySelectorAll('[data-po-message]').find(node =>
            JSON.parse(node.getAttribute('data-po-message')).key === 'onboarding.redirect.title');
        assert.strictEqual(title.textContent, translatedClient('en-US').t('tour:onboarding.redirect.title'));
        passed++;
    }

    {
        const {sandbox} = loadScenario(true);
        const ctx = sandbox.PixivOnboardingRuntime;
        const frames = [], timers = [];
        sandbox.requestAnimationFrame = callback => frames.push(callback);
        sandbox.setTimeout = callback => timers.push(callback);
        ctx.gallery.phaseGalleryRedirect();
        frames.shift()();
        ctx.overlay.destroy();
        assert.doesNotThrow(() => timers.shift()());
        ctx.gallery.phaseGalleryRedirect();
        frames.shift()();
        ctx.overlay.destroy();
        ctx.gallery.phaseGalleryRedirect();
        const current = ctx.overlay.pop;
        timers.shift()();
        assert.strictEqual(current.classList.contains('po-in'), false);
        frames.shift()();
        timers.shift()();
        assert.strictEqual(current.classList.contains('po-in'), true);
        ctx.gallery.phaseGalleryRedirect();
        ctx.overlay.destroy();
        assert.doesNotThrow(() => frames.shift()());
        assert.strictEqual(timers.length, 0);
        passed++;
    }

    console.log(`\npixiv-onboarding.test.js: ${passed} assertions passed ✓`);
}

main().catch(err => { console.error('TEST FAILED:', err && err.stack ? err.stack : err); process.exit(1); });
