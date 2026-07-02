'use strict';
/*
 * 跨页新手向导下载结果入口回归测试。
 *
 * 无浏览器 / 无 jsdom：用最小 DOM 在 Node 的 vm 沙箱里加载真实的 pixiv-onboarding.js，验证：
 *   1) 下载中 first-download-result 入口存在时保留旧的画廊导向文案，缺席时使用中性结果文案。
 *   2) 下载成功 first-download-result 入口存在时，仍保持旧的 await-gallery 状态与“点击画廊入口”提示。
 *   3) 下载成功 first-download-result 入口缺席时，下载页引导完成，不渲染要求点击不存在入口的阻塞提示。
 *
 * 运行： node src/test/js/pixiv-onboarding.test.js
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');

const SRC = fs.readFileSync(
    path.join(__dirname, '..', '..', 'main', 'resources', 'static', 'js', 'pixiv-onboarding.js'), 'utf8');
const STORAGE_KEY = 'pixiv_onboarding_v1';
const RESULT_ENTRY_SELECTOR = 'a.app-nav-link[data-nav-markers~="first-download-result"]';

let passed = 0;
function ok(label, cond) { assert.ok(cond, label); passed++; }

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
        this._listeners = {};
        this._actionButtons = [];
    }
    setAttribute(k, v) { this.attrs[k] = String(v); }
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
    scrollIntoView() {}
    getBoundingClientRect() { return { top: 20, left: 20, width: 120, height: 32 }; }
    get innerHTML() { return this._html; }
    set innerHTML(value) {
        this._html = String(value);
        this._actionButtons = [];
        const re = /data-act="([^"]+)"/g;
        let m;
        while ((m = re.exec(this._html)) !== null) {
            const button = new El('button');
            button.setAttribute('data-act', m[1]);
            this._actionButtons.push(button);
        }
    }
    querySelector(selector) {
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
        if (selector !== '[data-act]') {
            return [];
        }
        return this._actionButtons;
    }
}

function makeDocument(hasResultEntry) {
    const body = new El('body');
    const resultEntry = hasResultEntry ? new El('a') : null;
    return {
        body,
        resultEntry,
        documentElement: { clientWidth: 1024, clientHeight: 768 },
        createElement: tag => new El(tag),
        addEventListener() {},
        removeEventListener() {},
        querySelector(selector) {
            if (selector === RESULT_ENTRY_SELECTOR) {
                return resultEntry;
            }
            return null;
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
    vm.runInContext(SRC, sandbox);
    sandbox.PixivOnboarding.boot({
        page: 'batch',
        eligible: true,
        i18n: { t: (key, fallback) => fallback },
        sel: { firstDownloadResultEntry: RESULT_ENTRY_SELECTOR }
    });
    return {
        storage,
        pop: findByClass(document.body, 'po-pop'),
        resultEntry: document.resultEntry
    };
}

function clickAction(pop, act) {
    const button = pop.querySelectorAll('[data-act]').find(b => b.getAttribute('data-act') === act);
    assert.ok(button, 'expected action button: ' + act);
    button.click();
}

function loadMonitorScenario(hasResultEntry) {
    const document = makeDocument(hasResultEntry);
    const storage = {};
    storage[STORAGE_KEY] = JSON.stringify({ status: 'active', phase: 'download' });
    let nextTimer = 1;
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
        setInterval(fn) { fn(); return nextTimer++; },
        clearInterval() {},
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
    vm.runInContext(SRC, sandbox);
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
            isRunning: () => true
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

    return {
        pop: findByClass(document.body, 'po-pop'),
        resultEntry: document.resultEntry
    };
}

async function main() {
    {
        const { pop, resultEntry } = loadMonitorScenario(true);

        ok('M1: 下载中 marker 存在时保留旧的画廊导向文案',
            pop && pop.innerHTML.indexOf('完成后会自动带你去画廊查看') >= 0);
        ok('M1: 下载中 marker 存在时仍可解析结果入口', resultEntry !== null);
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
        const { storage, pop, resultEntry } = loadScenario(true);
        const state = JSON.parse(storage[STORAGE_KEY]);

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

    console.log(`\npixiv-onboarding.test.js: ${passed} assertions passed ✓`);
}

main().catch(err => { console.error('TEST FAILED:', err && err.stack ? err.stack : err); process.exit(1); });
