'use strict';
/* 从各页面实际脚本列表装配引导，验证业务 owner、旧进度与资源生命周期。 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');

const REPO_ROOT = path.resolve(__dirname, '..', '..', '..', '..');
const SCRIPT_NAMES = [
    'pixiv-onboarding-core.js',
    'pixiv-onboarding-overlay.js',
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

function loadOnboardingModules(sandbox, page = 'batch') {
    if (page === 'core') {
        SCRIPT_SOURCES.forEach((source, index) => vm.runInContext(source, sandbox, {filename: SCRIPT_NAMES[index]}));
        return;
    }
    const module = page === 'batch' ? 'download-workbench' : 'gallery';
    const html = fs.readFileSync(path.join(REPO_ROOT, 'pixivdownload-plugin-' + module,
        'src/main/resources/static/pixiv-' + page + '.html'), 'utf8');
    onboardingScriptUrls(html).forEach(url => {
        const owner = url.startsWith('/js/') ? 'pixivdownload-app' : 'pixivdownload-plugin-' + module;
        vm.runInContext(fs.readFileSync(path.join(REPO_ROOT, owner, 'src/main/resources/static', url), 'utf8'),
            sandbox, {filename: url});
    });
}

function onboardingScriptUrls(pageSource) {
    return Array.from(pageSource.matchAll(/<script\b[^>]*\bsrc="([^"]+)"/g), match => match[1])
        .filter(src => src.startsWith('/js/pixiv-onboarding') || src.endsWith('/onboarding.js'));
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
        this.tagName = this.tag.toUpperCase();
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
    removeEventListener(type, fn) {
        this._listeners[type] = (this._listeners[type] || []).filter(listener => listener !== fn);
    }
    click() {
        (this._listeners.click || []).forEach(fn => fn.call(this));
    }
    focus() { this.focused = true; }
    contains(el) { return el === this || this.children.some(child => child.contains(el)); }
    getClientRects() { return this.hidden ? [] : [this.getBoundingClientRect()]; }
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
            this._input.setAttribute('data-i18n-placeholder',
                /data-i18n-placeholder="([^"]+)"/.exec(inputTag[0])[1]);
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
    const bundle = {};
    for (const [module, namespace] of [['app', 'tour'], ['plugin-download-workbench', 'batch'], ['plugin-gallery', 'gallery']]) {
        const lines = fs.readFileSync(path.join(REPO_ROOT, 'pixivdownload-' + module,
            'src/main/resources/i18n/web/' + namespace + suffix + '.properties'), 'utf8').split(/\r?\n/);
        for (const line of lines) {
            if (!line || line.startsWith('#') || !line.includes('=')) continue;
            const split = line.indexOf('=');
            bundle[namespace + ':' + line.slice(0, split)] = line.slice(split + 1);
        }
    }
    return {lang, t(key, fallback, args) {
        return String(bundle[key] ?? fallback ?? key)
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
    if (resultEntry) elements[RESULT_ENTRY_SELECTOR] = resultEntry;
    return {
        body,
        resultEntry,
        elements,
        documentElement: { clientWidth: 1024, clientHeight: 768 },
        createElement: tag => new El(tag),
        addEventListener() {},
        removeEventListener() {},
        querySelector(selector) {
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

function loadScenario(hasResultEntry, page = 'batch', initialState) {
    const languageListeners = [];
    const languageRequests = [];
    const document = makeDocument(hasResultEntry);
    const storage = {};
    storage[STORAGE_KEY] = JSON.stringify(initialState || { status: 'active', phase: 'await-gallery', downloaded: true });
    const events = new Map();
    const sandbox = {
        document,
        location: { href: '/pixiv-batch.html' },
        console: { warn() {}, log() {}, error() {} },
        localStorage: {
            getItem: key => Object.prototype.hasOwnProperty.call(storage, key) ? storage[key] : null,
            setItem: (key, value) => { storage[key] = String(value); }
        },
        addEventListener(type, listener) {
            if (!events.has(type)) events.set(type, new Set());
            events.get(type).add(listener);
        },
        removeEventListener(type, listener) { events.get(type)?.delete(listener); },
        AbortController,
        getComputedStyle() { return {fontSize:'16px'}; },
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
    loadOnboardingModules(sandbox, page);
    const adapter = page === 'core' ? sandbox.PixivOnboarding : page === 'batch' ? sandbox.PixivDownloadOnboarding : sandbox.PixivGalleryOnboarding;
    const config = {
        page,
        eligible: true,
        i18n: { t: (key, fallback) => fallback },
        sel: { firstDownloadResultEntry: RESULT_ENTRY_SELECTOR, restartButton: '#guide-entry' }
    };
    adapter.boot(config);
    return {
        storage,
        pop: findByClass(document.body, 'po-pop'),
        resultEntry: document.resultEntry,
        sandbox, document, languageListeners, languageRequests,
        onboarding: sandbox.PixivOnboarding, adapter, config,
        emit(type, event = {}) { Array.from(events.get(type) || []).forEach(listener => listener(event)); }
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
        AbortController,
        localStorage: {
            getItem: key => Object.prototype.hasOwnProperty.call(storage, key) ? storage[key] : null,
            setItem: (key, value) => { storage[key] = String(value); }
        },
        addEventListener() {},
        removeEventListener() {},
        getComputedStyle() { return {fontSize:'16px'}; },
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
    sandbox.PixivDownloadOnboarding.boot({
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
        const h = loadScenario(false), overlay = h.sandbox.PixivOnboardingRuntime.overlay;
        const target = new El('textarea');
        h.document.elements['#target'] = target;
        overlay.render({targetSelector:'#target', interactiveSelector:'#target',
            html:'<button data-act="skip">Skip</button>'});
        assert.ok(overlay.root.classList.contains('po-action'));
        assert.equal(overlay.cue.textContent, '在此输入');
        assert.equal(overlay.cue.hidden, false);
        assert.equal(overlay.pop.getAttribute('aria-modal'), 'false');
        overlay.refreshTranslations();
        h.emit('keydown', {key:'Tab', preventDefault() {}});
        assert.equal(target.focused, true, '键盘可进入浮层外的实际输入控件');
        overlay.render({targetSelector:'#target', html:'<p>Information</p>'});
        assert.equal(overlay.root.classList.contains('po-action'), false);
        assert.equal(overlay.cue.hidden, true);
        assert.equal(target.classList.contains('po-interactive'), false);
        assert.ok(overlay.pop.innerHTML.includes('浏览说明'));
        target.getBoundingClientRect = () => ({left:500, top:160, width:120, height:40});
        h.document.documentElement.clientHeight = 400;
        overlay.pop.offsetHeight = 300;
        overlay.render({targetSelector:'#target', interactiveSelector:'#target', html:'<p>Action</p>'});
        assert.ok(parseFloat(overlay.pop.style.left) + overlay.pop.offsetWidth < 500,
            '纵向空间不足时使用侧边空间，不遮住操作目标');
        passed++;
    }
    {
        const h = loadScenario(false), overlay = h.sandbox.PixivOnboardingRuntime.overlay;
        let skipped = 0;
        overlay.render({html:'<button data-act="skip">Skip</button>', centered:true});
        h.sandbox.PixivOnboardingRuntime.bindFoot({skip() { skipped++; overlay.destroy(); }});
        let prevented = 0;
        const event = {key:'Escape', preventDefault() { prevented++; }, stopPropagation() {}};
        h.document.elements['dialog[open]'] = new El('dialog');
        h.emit('keydown', event);
        assert.equal(skipped, 0, '原生内层弹窗优先处理 Esc');
        delete h.document.elements['dialog[open]'];
        h.emit('keydown', event);
        h.emit('keydown', event);
        assert.equal(skipped, 1, 'Esc 只跳过当前引导一次');
        assert.equal(prevented, 1, '销毁后不保留键盘监听');
        passed++;
    }
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
        entry.click();
        assert.ok(ctx.overlay.qs('#po-name-input'), '重看重新打开称呼步骤');
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
        const urls = onboardingScriptUrls(pageSource);
        assert.deepStrictEqual(urls.slice(0, 3), SCRIPT_URLS);
        assert.deepStrictEqual(urls.slice(3), [pagePath.includes('download-workbench')
            ? '/pixiv-batch/onboarding.js' : '/pixiv-gallery/onboarding.js'],
            pagePath + ' only loads its owning plugin steps');
        passed++;
    }

    {
        const { startButtonClicks, startButtonFocused } = loadMonitorScenario(false, true);
        ok('P: 聚光洞口把点击转交给层叠上下文内的真实控件', startButtonClicks === 1);
        ok('P: 聚光洞口把焦点转交给真实控件', startButtonFocused);
    }

    {
        const { pop, resultEntry, beforeStartCalls } = loadMonitorScenario(true);

        ok('M1: 下载中 marker 存在时说明结果入口',
            pop && pop.innerHTML.includes('batch:onboarding.monitor.body'));
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
        ok('API: 中性入口不包含具体示例作品', onboarding.EXAMPLE_ID === undefined);
        ok('A: 已完成下载迁成中性结果状态', state.status === 'active' && state.phase === 'result' && state.downloaded);
        ok('A: marker 存在时保留旧的点击画廊入口文案',
            pop && pop.innerHTML.indexOf('点击高亮的结果入口') >= 0);
        ok('A: marker 存在时高亮该入口', resultEntry.classList.contains('po-interactive'));
    }

    {
        const h = loadScenario(true);
        const ctx = h.sandbox.PixivOnboardingRuntime;
        h.emit('click', {target: h.document.body});
        assert.ok(ctx.overlay.root, '其它点击不能结束结果入口指引');
        const replacement = new El('a');
        const icon = replacement.appendChild(new El('span'));
        h.document.elements[RESULT_ENTRY_SELECTOR] = replacement;
        h.emit('pixivnav:rendered');
        h.emit('click', {target: h.resultEntry});
        assert.ok(ctx.overlay.root, '旧导航元素不再是有效结果入口');
        h.emit('click', {target: icon});
        assert.equal(ctx.overlay.root, null, '点击重绘后入口的子元素也关闭浮层');
        assert.equal(h.document.elements['#guide-entry'].disabled, false);
        const state = JSON.parse(h.storage[STORAGE_KEY]);
        assert.equal(state.status, 'active', '保留后续结果页的引导进度');
        assert.equal(state.phase, 'result');
        const gallery = loadScenario(false, 'gallery', state);
        assert.equal(JSON.parse(gallery.storage[STORAGE_KEY]).phase, 'gallery');
        ctx.overlay.render({html: 'another step'});
        h.emit('click', {target: icon});
        assert.ok(ctx.overlay.root, '关闭后移除点击监听，不影响后续浮层');
        passed++;
    }

    {
        const { storage, pop, resultEntry } = loadScenario(false);
        const state = JSON.parse(storage[STORAGE_KEY]);

        ok('B: marker 缺席时引导标记为 completed', state.status === 'completed');
        ok('B: marker 缺席时不渲染点击画廊入口的阻塞提示',
            pop && pop.innerHTML.indexOf('点击高亮的结果入口') < 0);
        ok('B: marker 缺席时显示中性的下载结果完成提示',
            pop && pop.innerHTML.indexOf('当前没有可打开的下载结果入口') >= 0);
        ok('B: marker 缺席时没有被高亮的入口元素', resultEntry === null);
    }

    {
        const h = loadScenario(false, 'core', {status: 'new'});
        assert.equal(h.sandbox.PixivDownloadOnboarding, undefined);
        assert.equal(h.sandbox.PixivGalleryOnboarding, undefined);
        let starts = 0;
        h.onboarding.boot({eligible: true, start(state) { assert.equal(state.status, 'new'); starts++; }});
        assert.equal(starts, 1, '未知页面可使用中性入口，不等待任一业务模块');
        h.onboarding.boot({eligible: false, start() { starts++; }});
        assert.equal(starts, 1, '不合资格时不执行业务步骤');
        passed++;
    }
    {
        const h = loadScenario(false, 'gallery', {status: 'new'});
        assert.equal(h.sandbox.PixivDownloadOnboarding, undefined);
        assert.ok(h.pop.innerHTML.includes('当前没有可用的下载入口'));
        clickAction(h.pop, 'go');
        assert.equal(h.sandbox.location.href, '/pixiv-batch.html', '无入口时不发起跳转');
        assert.equal(JSON.parse(h.storage[STORAGE_KEY]).phase, 'gallery');
        passed++;
    }
    {
        const h = loadScenario(false, 'gallery', {status: 'new'});
        const selector = 'a.app-nav-link[data-nav-markers~="preferred-download-workbench"]';
        const entry = new El('a');
        entry.href = '/independent-provider/start';
        h.document.elements[selector] = entry;
        h.adapter.boot(h.config);
        const ctx = h.sandbox.PixivOnboardingRuntime, pop = ctx.overlay.pop;
        const go = pop.querySelector('[data-act="go"]');
        const state = h.storage[STORAGE_KEY];
        h.onboarding.refreshFab(translatedClient('en-US'));
        const title = pop.querySelectorAll('[data-po-message]').find(node =>
            JSON.parse(node.getAttribute('data-po-message')).key === 'gallery:onboarding.redirect.title');
        assert.equal(title.textContent, translatedClient('en-US').t('gallery:onboarding.redirect.title'));
        assert.strictEqual(ctx.overlay.pop, pop);
        assert.strictEqual(pop.querySelector('[data-act="go"]'), go);
        assert.equal(h.storage[STORAGE_KEY], state);
        go.click();
        assert.equal(h.sandbox.location.href, entry.href, '使用当前声明的导航，不猜测具体页面');
        h.sandbox.location.href = '/retained';
        h.adapter.boot(h.config);
        const oldGo = ctx.overlay.pop.querySelector('[data-act="go"]');
        delete h.document.elements[selector];
        oldGo.click();
        assert.equal(h.sandbox.location.href, '/retained');
        assert.ok(ctx.overlay.pop.innerHTML.includes('当前没有可用的下载入口'));
        oldGo.click();
        assert.equal(h.sandbox.location.href, '/retained', '陈旧按钮不能调用 replacement');
        passed++;
    }
    {
        const h = loadScenario(true, 'batch', {status: 'new'});
        const ctx = h.sandbox.PixivOnboardingRuntime;
        const pop = ctx.overlay.pop, input = pop.querySelector('#po-name-input');
        input.value = 'draft <name>';
        h.onboarding.refreshFab(translatedClient('en-US'));
        assert.strictEqual(ctx.overlay.pop, pop);
        assert.strictEqual(pop.querySelector('#po-name-input'), input);
        assert.equal(input.value, 'draft <name>');
        assert.equal(input.focused, true);
        assert.equal(input.placeholder, translatedClient('en-US').t('batch:onboarding.welcome.name-placeholder'));
        assert.equal(h.sandbox.PixivGalleryOnboarding, undefined);
        passed++;
    }
    for (const page of ['gallery', 'artwork']) {
        const h = loadScenario(true, page, {status: 'completed', name: 'saved'});
        const {sandbox, document, languageListeners, languageRequests} = h;
        const ctx = sandbox.PixivOnboardingRuntime;
        ctx.overlay.render({html: ctx.messageHtml(ctx.message('gallery:onboarding.redirect.title', 'fallback'))});
        const pop = ctx.overlay.pop;
        languageListeners.forEach(listener => listener({lang: 'en-US'}));
        assert.equal(languageRequests.length, 0);
        const nodes = new Map();
        document.getElementById = id => {
            if (!nodes.has(id)) {
                const node = new El('div'); node.parentElement = new El('div'); nodes.set(id, node);
            }
            return nodes.get(id);
        };
        sandbox.state = {view: 'all'};
        for (const name of ['updateOrderToggleLabel', 'syncCollectionFormTitle', 'renderCollections',
            'renderTagChips', 'renderSeriesFilterChips', 'renderAuthorChips', 'syncTagFilterBar',
            'syncAuthorFilterBar', 'syncSeriesFilterBar', 'reloadCurrentView']) sandbox[name] = () => {};
        let change, creations = 0;
        sandbox.PixivI18n.create = async options => {
            assert.ok(options.namespaces.includes('tour'));
            assert.ok(options.namespaces.includes('gallery'));
            creations++; return translatedClient('zh-CN');
        };
        sandbox.PixivLangSwitcher = {mount: async options => { change = options.onChange; }};
        sandbox.PixivTheme = {mount() {}};
        sandbox.PixivNav.refresh = () => {};
        vm.runInContext(fs.readFileSync(path.join(REPO_ROOT,
            'pixivdownload-plugin-gallery/src/main/resources/static/pixiv-' + page + '/' + page + '-core.js'), 'utf8'), sandbox);
        await sandbox.initPageI18n();
        const client = translatedClient('en-US');
        change(client);
        assert.strictEqual(ctx.i18n, client);
        assert.equal(creations, 1);
        assert.strictEqual(ctx.overlay.pop, pop);
        assert.equal(pop.querySelectorAll('[data-po-message]')[0].textContent,
            client.t('gallery:onboarding.redirect.title'));
        passed++;
    }
    for (const phase of ['await-gallery', 'result', 'gallery']) {
        const h = loadScenario(false, 'gallery', {status: 'active', phase, downloaded: true, name: 'saved'});
        const ctx = h.sandbox.PixivOnboardingRuntime;
        assert.equal(JSON.parse(h.storage[STORAGE_KEY]).phase, 'gallery');
        assert.equal(h.onboarding.getName(), 'saved');
        let tick, cleared = false;
        h.sandbox.setInterval = callback => { tick = callback; return 42; };
        h.sandbox.clearInterval = id => { if (id === 42) cleared = true; };
        h.config.hooks = {getExampleCard: () => new El('a')};
        h.adapter.boot(h.config);
        ctx.skip();
        assert.equal(cleared, true, '结束时立即释放元素等待器');
        tick();
        assert.equal(ctx.overlay.pop, null, '迟到元素不能重开已结束的引导');
        passed++;
    }
    {
        const h = loadScenario(false, 'artwork', {status: 'completed'});
        const ctx = h.sandbox.PixivOnboardingRuntime;
        let tick, delayed;
        h.sandbox.setInterval = callback => { tick = callback; return 42; };
        h.sandbox.setTimeout = callback => { delayed = callback; return 43; };
        h.document.elements['#detail'] = new El('section');
        h.config.sel.detail = '#detail';
        h.storage[STORAGE_KEY] = JSON.stringify({status: 'active', phase: 'detail'});
        h.adapter.boot(h.config);
        tick(); ctx.skip(); delayed();
        assert.equal(ctx.overlay.pop, null);
        passed++;
    }
    {
        const h = loadScenario(false, 'batch', {status: 'completed'});
        const ctx = h.sandbox.PixivOnboardingRuntime;
        let resolve, signal;
        h.sandbox.fetch = (url, options) => {
            signal = options.signal;
            return new Promise(done => { resolve = done; });
        };
        h.storage[STORAGE_KEY] = JSON.stringify({status: 'active', phase: 'welcome', name: 'saved'});
        h.adapter.boot(h.config);
        ctx.skip();
        assert.equal(signal.aborted, true, '退出取消本页连通性读取');
        resolve({ok: true, json: async () => ({reachable: true, latencyMs: 1})});
        await Promise.resolve(); await Promise.resolve(); await Promise.resolve();
        assert.equal(ctx.overlay.pop, null);
        passed++;
    }
    {
        const h = loadScenario(false, 'gallery', {status: 'new'});
        const ctx = h.sandbox.PixivOnboardingRuntime, old = ctx.overlay.pop;
        h.emit('pagehide');
        assert.equal(ctx.overlay.pop, null);
        h.adapter.boot(h.config);
        assert.equal(ctx.overlay.pop, null, '离页后迟到的资格查询不能重新启动引导');
        h.emit('pageshow', {persisted: true});
        assert.ok(ctx.overlay.pop);
        assert.notStrictEqual(ctx.overlay.pop, old);
        passed++;
    }
    {
        const h = loadScenario(false, 'core', {status: 'new'});
        const ctx = h.sandbox.PixivOnboardingRuntime;
        const frames = [], timers = [];
        h.sandbox.requestAnimationFrame = callback => frames.push(callback);
        h.sandbox.setTimeout = callback => { timers.push(callback); return timers.length; };
        ctx.overlay.render({html: 'old'}); frames.shift()();
        ctx.overlay.destroy(); ctx.overlay.render({html: 'current'});
        const current = ctx.overlay.pop;
        timers.shift()();
        assert.equal(current.classList.contains('po-in'), false);
        frames.shift()(); timers.shift()();
        assert.equal(current.classList.contains('po-in'), true);
        ctx.overlay.render({html: 'discard'}); ctx.overlay.destroy();
        assert.doesNotThrow(() => frames.shift()());
        assert.equal(timers.length, 0);
        passed++;
    }

    {
        const h = loadScenario(true);
        const ctx = h.sandbox.PixivOnboardingRuntime;
        delete h.document.elements[RESULT_ENTRY_SELECTOR];
        h.emit('pixivnav:rendered');
        assert.equal(JSON.parse(h.storage[STORAGE_KEY]).status, 'completed');
        assert.ok(ctx.overlay.pop.innerHTML.includes('当前没有可打开的下载结果入口'));
        ctx.skip();
        h.emit('pixivnav:rendered');
        assert.equal(ctx.overlay.pop, null, '离开后导航事件不重开浮层');
        passed++;
    }
    for (const page of ['gallery', 'artwork']) {
        const h = loadScenario(false, page, {status: 'active', phase: 'welcome', downloaded: true});
        assert.equal(h.sandbox.PixivOnboardingRuntime.overlay.pop, null,
            '重看下载引导时不因旧 downloaded 标记跳回浏览步骤');
        assert.equal(JSON.parse(h.storage[STORAGE_KEY]).phase, 'welcome');
        passed++;
    }

    console.log(`\npixiv-onboarding.test.js: ${passed} assertions passed ✓`);
}

main().catch(err => { console.error('TEST FAILED:', err && err.stack ? err.stack : err); process.exit(1); });
