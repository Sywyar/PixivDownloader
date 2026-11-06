'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const STATIC_DIR = path.join(__dirname, '..', '..', 'main', 'resources', 'static', 'pixiv-translation');
const MODULE_FILES = [
    'pixiv-translate-dialog.js',
    'pixiv-content-lang.js',
    'pixiv-translate.js'
];

function createStorage() {
    const values = new Map();
    return {
        getItem(key) { return values.has(key) ? values.get(key) : null; },
        setItem(key, value) { values.set(key, String(value)); },
        removeItem(key) { values.delete(key); }
    };
}

function createSandbox(overrides) {
    const sandbox = Object.assign({
        AbortController,
        URLSearchParams,
        clearInterval,
        clearTimeout,
        console,
        fetch: async function () { return {ok: false}; },
        localStorage: createStorage(),
        setInterval,
        setTimeout
    }, overrides || {});
    sandbox.window = sandbox;
    vm.createContext(sandbox);
    return sandbox;
}

function evaluateFile(sandbox, file) {
    const source = fs.readFileSync(path.join(STATIC_DIR, file), 'utf8');
    vm.runInContext(source, sandbox, {filename: file});
}

function evaluateModules(sandbox) {
    MODULE_FILES.forEach(function (file) { evaluateFile(sandbox, file); });
}

test('阅读语言与引擎独立选择，只组合已有译文，重标文案保留选择', function () {
    function element() {
        return {children: [], style: {}, attributes: {}, listeners: {},
            appendChild(child) { this.children.push(child); },
            setAttribute(key, value) { this.attributes[key] = value; },
            addEventListener(key, callback) { this.listeners[key] = callback; },
            set innerHTML(value) { this.children = []; },
            get options() { return this.children; }};
    }
    const sandbox = createSandbox({document: {documentElement: {lang: 'en-US'}, createElement: element}});
    evaluateFile(sandbox, 'pixiv-content-lang.js');
    const i18n = {t(key) { return key; }};
    let selected;
    const controller = sandbox.PixivContentLang.mount({mountPoint: element(), i18n,
        languages: ['zh-CN', 'zh-CN@ai', 'zh-CN@youdao', 'en@ai', 'ja@libretranslate'], current: 'zh-CN@ai',
        onChange(key) { selected = key; }});
    const [languageField, engineField] = controller.element.children;
    const language = languageField.children[1], engine = engineField.children[1];
    assert.equal(controller.element.attributes['aria-label'], 'translate:switcher.title');
    assert.equal(languageField.children[0].textContent, 'translate:switcher.language');
    assert.equal(engineField.children[0].textContent, 'translate:switcher.engine');
    assert.deepEqual(language.options.map(o => o.value), ['', 'zh-CN', 'en', 'ja']);
    assert.deepEqual(engine.options.map(o => o.value), ['unknown', 'ai', 'youdao']);
    assert.match(engine.options[0].textContent, /switcher.engine.unknown$/);
    engine.value = 'youdao';
    engine.listeners.change();
    assert.equal(selected, 'zh-CN@youdao');
    assert.equal(language.value, 'zh-CN');
    assert.equal(sandbox.PixivContentLang.getStored(), selected);
    controller.relabel(i18n);
    assert.equal(controller.getValue(), selected);
    assert.equal(engine.value, 'youdao');
    language.value = 'en';
    language.listeners.change();
    assert.equal(selected, 'en@ai');
    assert.deepEqual(engine.options.map(o => o.value), ['ai']);
    language.value = 'zh-CN';
    language.listeners.change();
    assert.equal(selected, 'zh-CN@ai');
    language.value = 'ja';
    language.listeners.change();
    assert.equal(selected, 'ja@libretranslate');
    language.value = '';
    language.listeners.change();
    assert.equal(selected, null);
    assert.equal(engine.disabled, true);
    assert.equal(sandbox.PixivContentLang.getStored(), '');
    controller.setLanguages(['zh-CN'], 'zh-CN');
    assert.equal(engine.value, 'unknown');
    assert.equal(engine.disabled, false);
    controller.setLanguages([]);
    assert.equal(controller.element.hidden, true);
    assert.equal(controller.getValue(), '');
});

test('翻译目标默认值使用当前界面语言，单篇与自动翻译保持一致', async function (t) {
    const {parse} = await import('../../../../scripts/i18n/lib/properties-parser.mjs');
    const catalog = JSON.parse(fs.readFileSync(path.join(__dirname,
        '../../../../pixivdownload-app/src/main/resources/i18n/locales.json'), 'utf8'));
    for (const locale of catalog.locales.filter(l => ['source', 'supported'].includes(l.status))) {
        await t.test(locale.tag, function () {
            const suffix = locale.resourceSuffix ? '_' + locale.resourceSuffix : '';
            for (const [bundle, key] of [['translate', 'dialog.language-default'], ['translate', 'batch.translate-lang-default']]) {
                const resource = path.join(STATIC_DIR, '../../i18n/web', bundle + suffix + '.properties');
                const entries = parse(fs.readFileSync(resource, 'utf8')).entries;
                // 该值是发给翻译模型的目标语言，核对语言身份而非普通界面措辞。
                assert.equal(entries.find(entry => entry.key === key)?.value.toLowerCase(),
                    locale.nativeName.toLowerCase(), bundle);
            }
        });
    }
});

async function waitFor(predicate) {
    for (let i = 0; i < 20; i++) {
        if (predicate()) return;
        await new Promise(function (resolve) { setImmediate(resolve); });
    }
    assert.fail('slot initialization did not finish');
}

function runSlot(slotFile, pageGlobals, failedScript) {
    const loadedScripts = [];
    const styles = [];
    const toasts = [];
    let mounted = false;
    const sandbox = createSandbox(Object.assign({
        PixivFeedback: {
            toast(options) { toasts.push(options); }
        }
    }, pageGlobals(function () { mounted = true; })));
    const head = {
        appendChild(node) {
            if (node.rel === 'stylesheet') {
                styles.push(node.href);
                return node;
            }
            const file = path.posix.basename(node.src);
            loadedScripts.push(file);
            if (file === failedScript) {
                queueMicrotask(function () { node.onerror(); });
                return node;
            }
            evaluateFile(sandbox, file);
            queueMicrotask(function () { node.onload(); });
            return node;
        }
    };
    sandbox.document = {
        head,
        documentElement: head,
        createElement() { return {}; },
        querySelector() { return null; }
    };
    let mounting;
    sandbox.PixivUiSlots = {registerModule(url, factory) {
        assert.equal(url, '/pixiv-translation/' + slotFile);
        mounting = factory({}, {
            signal: new AbortController().signal,
            properties: {novelId: '42', seriesId: '7', novel: true, contentLanguages: ['en@youdao'], contentLanguage: ''},
            region: () => ({}), onCleanup() {}, emit() {}, fetch: sandbox.fetch,
            loadStyle(url) { styles.push(url); },
            async loadScript(url) {
                const file = path.posix.basename(url); loadedScripts.push(file);
                if (file === failedScript) throw new Error('UI_MODULE_UNAVAILABLE');
                evaluateFile(sandbox, file);
                if (file === 'pixiv-content-lang.js') sandbox.PixivContentLang.mount = options => {
                    assert.deepEqual(options.languages, ['en@youdao']); mounted = true; return {destroy() {}};
                };
                return true;
            }
        }).catch(() => toasts.push({kind: 'error'}));
    }};
    evaluateFile(sandbox, slotFile);
    return mounting.then(() => waitFor(function () { return failedScript ? toasts.length : mounted; })).then(function () {
        return {loadedScripts, styles, mounted, sandbox, toasts};
    });
}

test('AI 翻译模块公开稳定入口并复用配置探测结果', async function () {
    let statusRequests = 0;
    const sandbox = createSandbox({
        fetch: async function (url) {
            assert.equal(url, '/api/translation/status');
            statusRequests++;
            return {ok: true, json: async function () { return {configured: true}; }};
        }
    });
    evaluateModules(sandbox);

    assert.equal(typeof sandbox.PixivTranslateDialog.openDialog, 'function');
    assert.equal(typeof sandbox.PixivTranslate.openDialog, 'function');
    assert.equal(typeof sandbox.PixivTranslate.runSingleNovel, 'function');
    assert.equal(typeof sandbox.PixivTranslate.runSeries, 'function');
    assert.equal(typeof sandbox.PixivContentLang.mount, 'function');

    const configured = await Promise.all([
        sandbox.PixivTranslate.isConfigured(),
        sandbox.PixivTranslate.isConfigured()
    ]);
    assert.deepEqual(configured, [true, true]);
    assert.equal(statusRequests, 1);

    sandbox.PixivContentLang.setStored('ja-JP');
    assert.equal(sandbox.PixivContentLang.getStored(), 'ja-JP');
    sandbox.PixivContentLang.setStored('');
    assert.equal(sandbox.PixivContentLang.getStored(), '');
});

test('小说与系列槽位按职责顺序装载翻译模块', async function () {
    const scenarios = [
        ['novel-detail-translate-slot.js', function (mounted) {
            return {PixivNovel: {content: {mountContentLangSwitcher: mounted}}};
        }],
        ['series-detail-translate-slot.js', function (mounted) {
            return {setupNovelContentControls: mounted};
        }]
    ];

    for (const [slotFile, globals] of scenarios) {
        const result = await runSlot(slotFile, globals);
        assert.deepEqual(result.loadedScripts, MODULE_FILES, slotFile);
        assert.deepEqual(result.styles, ['/pixiv-translation/pixiv-translate.css'], slotFile);
        assert.equal(typeof result.sandbox.PixivTranslate.openDialog, 'function');
        assert.equal(typeof result.sandbox.PixivContentLang.mount, 'function');
    }
});

test('AI 翻译槽位在职责脚本加载失败时提示并停止后续初始化', async function () {
    const scenarios = [
        ['novel-detail-translate-slot.js', function (mounted) {
            return {PixivNovel: {content: {mountContentLangSwitcher: mounted}}};
        }],
        ['series-detail-translate-slot.js', function (mounted) {
            return {setupNovelContentControls: mounted};
        }]
    ];

    for (const [slotFile, globals] of scenarios) {
        const result = await runSlot(slotFile, globals, 'pixiv-content-lang.js');
        assert.deepEqual(result.loadedScripts, MODULE_FILES.slice(0, 2), slotFile);
        assert.equal(result.mounted, false, slotFile);
        assert.equal(result.toasts.length, 1, slotFile);
        assert.equal(result.toasts[0].kind, 'error', slotFile);
    }
});

function progressSandbox(fetch) {
    const nodes = new Map();
    const timers = new Set();
    function element() {
        const classes = new Set();
        return {
            style: {}, textContent: '', children: [],
            isConnected: true,
            remove() { this.isConnected = false; },
            addEventListener() {},
            focus() {},
            classList: {add: value => classes.add(value), remove: value => classes.delete(value),
                contains: value => classes.has(value)},
            appendChild(node) { this.children.push(node); },
            querySelector(selector) {
                if (!nodes.has(selector)) nodes.set(selector, element());
                return nodes.get(selector);
            }
        };
    }
    const body = element();
    const sandbox = createSandbox({fetch, crypto: require('node:crypto').webcrypto,
        document: {body, createElement: element},
        setTimeout(callback) { timers.add(callback); return callback; },
        clearTimeout(callback) { timers.delete(callback); },
        setInterval(callback) { timers.add(callback); return callback; },
        clearInterval(callback) { timers.delete(callback); }});
    evaluateFile(sandbox, 'pixiv-translate.js');
    return {api: sandbox.PixivTranslate, sandbox, nodes, timers, body};
}

function jsonResponse(data) { return {ok: true, json: async () => data}; }

test('重新打开翻译设置刷新引擎状态，单篇与系列提交固定所选引擎', async function () {
    let engine = 'ai';
    const requests = [];
    const h = progressSandbox(async (url, init) => {
        if (url === '/api/translation/status') return jsonResponse({configured: true, engine});
        requests.push(JSON.parse(init.body));
        return jsonResponse({status: 'OK', langCode: 'zh-CN'});
    });
    assert.equal((await h.api.engineStatus()).engine, 'ai');
    engine = 'youdao';
    assert.equal((await h.api.engineStatus(true)).engine, 'youdao');
    await h.api.translateNovel(42, {targetLanguage: 'zh-CN', engine: 'youdao'});
    await h.api.translateSeriesTitle(12, 'zh-CN', null, null, true, true, 'youdao');
    assert.equal(requests.length, 2);
    for (const request of requests) {
        assert.equal(request.engine, 'youdao');
        assert.equal(request.targetLanguage, 'zh-CN');
    }
});

const flush = () => new Promise(resolve => setImmediate(resolve));
const view = (state, extra) => Object.assign({id: 'fixture', state, savedSteps: 2, completed: 0, total: 1,
    language: 'zh-CN', variantKey: 'zh-CN@youdao', ok: 0, skipped: 0}, extra);

test('系列按钮忽略单章及仅元数据任务，提交全部章节并按真实结果提示', async function () {
    const submissions = [], messages = [];
    let click, mounting, choices = 0;
    const h = progressSandbox(async (url, init) => {
        if (url.includes('access-check')) return jsonResponse({});
        if (url.includes('?seriesId=')) return jsonResponse([
            view('QUEUED', {id: 'single', allChapters: false, metadataOnly: false}),
            view('PAUSED', {id: 'metadata', allChapters: false, metadataOnly: true})
        ]);
        if (init.method === 'POST') {
            submissions.push(JSON.parse(init.body));
            return jsonResponse(view('RUNNING', {allChapters: true, total: 3}));
        }
        return jsonResponse(view('COMPLETED', {allChapters: true, total: 3, completed: 3, ok: 3}));
    });
    h.sandbox.PixivTranslateDialog = {create: () => ({async openDialog() {
        choices++; return {targetLanguage: 'zh-CN', engine: 'youdao'};
    }})};
    h.sandbox.PixivContentLang = {mount: () => ({destroy() {}})};
    h.sandbox.PixivUiSlots = {registerModule(url, factory) {
        mounting = factory({querySelector: () => ({style: {}})}, {
            properties: {seriesId: 9, novel: true}, signal: new AbortController().signal,
            i18n: {apply() {}, t(key, fallback, vars) { return {key, vars}; }},
            loadScript: async () => true, loadStyle() {}, onCleanup() {}, region() {},
            fetch: h.sandbox.fetch, on(button, event, listener) { click = listener; },
            emit(event, value) { if (event === 'message') messages.push(value.message); }
        });
    }};
    evaluateFile(h.sandbox, 'series-detail-translate-slot.js');
    await mounting;
    await click();
    assert.equal(choices, 1);
    assert.equal(submissions.length, 1);
    assert.equal(submissions[0].allChapters, true);
    assert.equal(submissions[0].seriesId, 9);
    assert.equal(messages.at(-1).key, 'translate:toast.series-done');
    assert.equal(messages.at(-1).vars.ok, 3);
    assert.equal(h.timers.size, 0);
});

for (const state of ['PAUSED', 'CANCELLED']) test('系列查询保留可继续的整系列任务：' + state, async function () {
    const h = progressSandbox(async () => jsonResponse([
        view(state, {id: 'single', allChapters: false}),
        view('COMPLETED', {id: 'finished', allChapters: true}),
        view(state, {id: 'series', allChapters: true, metadataOnly: false})
    ]));
    assert.equal((await h.api.findJob(null, 9)).id, 'series');
    assert.equal((await h.api.findJob(7, null)).id, 'single');
});

test('卸载翻译组件中止本实例轮询并释放弹窗，保留后台任务及其他实例', async function () {
    const requests = [];
    const h = progressSandbox(async (url, init) => {
        requests.push({url, init});
        return new Promise(() => {});
    });
    const firstScope = new AbortController();
    const first = h.api.create({signal: firstScope.signal, fetch: h.sandbox.fetch});
    const second = h.api.create({fetch: h.sandbox.fetch});
    const pendingFirst = first.runSingleNovel({novelId: 11, job: view('RUNNING', {id: 'first'})});
    const pendingSecond = second.runSingleNovel({novelId: 12, job: view('RUNNING', {id: 'second'})});
    firstScope.abort();
    assert.equal((await pendingFirst).cancelled, true);
    assert.equal(requests[0].init.signal.aborted, true);
    assert.equal(requests[1].init.signal.aborted, false);
    assert.equal(h.body.children[0].isConnected, false);
    assert.equal(second.hasActiveJob(), true);
    assert.equal(h.timers.size, 1);
    first.destroy();
    second.destroy();
    assert.equal((await pendingSecond).cancelled, true);
    assert.equal(h.timers.size, 0);
    assert.equal(requests.every(r => r.init.method === 'GET'), true);
    assert.equal((await first.runSingleNovel({novelId: 11})).cancelled, true);
    assert.equal(requests.length, 2);
});

test('查询超时停止读取，迟到响应失效，重新连接不会提交翻译', async function () {
    let resolveRead, signal;
    const requests = [];
    const h = progressSandbox(async (url, init) => {
        requests.push(init.method);
        if (requests.length > 1) return jsonResponse(view('PAUSED'));
        signal = init.signal;
        return new Promise(resolve => { resolveRead = resolve; });
    });
    const result = h.api.runSingleNovel({novelId: 11, job: view('RUNNING')});
    await flush();
    const timeout = Array.from(h.timers)[0];
    h.timers.delete(timeout); timeout();
    assert.equal(signal.aborted, true);
    assert.equal(h.nodes.get('.pt-progress-action').textContent, 'Reconnect');
    resolveRead(jsonResponse(view('COMPLETED')));
    await flush();
    assert.equal(h.api.hasActiveJob(), true);
    h.nodes.get('.pt-progress-action').onclick();
    await flush();
    assert.equal(h.nodes.get('.pt-progress-action').textContent, 'Resume');
    assert.ok(requests.every(method => method === 'GET'));
    h.nodes.get('.pt-progress-hide').onclick();
    await result;
    assert.equal(h.timers.size, 0);
});

test('单本只提交一次持久任务，隐藏释放读取且不取消后端', async function () {
    const requests = [];
    const h = progressSandbox(async (url, init) => {
        requests.push([url, init.method, init.body && JSON.parse(init.body)]);
        return jsonResponse(view('RUNNING'));
    });
    const running = h.api.runSingleNovel({novelId: 11, seriesId: 12, choice: {targetLanguage: 'zh-CN', engine: 'youdao'}});
    await flush();
    assert.equal(await h.api.runSeries({seriesId: 99}), null);
    assert.equal(requests.filter(r => r[1] === 'POST').length, 1);
    assert.equal(requests[0][2].request.engine, 'youdao');
    assert.equal(requests[0][2].mergeAfter, true);
    assert.match(requests[0][2].requestId, /^[0-9a-f-]{36}$/);
    assert.equal(h.timers.size, 1);
    h.nodes.get('.pt-progress-hide').onclick();
    assert.equal((await running).cancelled, true);
    assert.equal(h.api.hasActiveJob(), false);
    assert.equal(h.timers.size, 0);
    assert.equal(requests.some(r => /cancel|merge/.test(r[0])), false);
});

test('重新打开恢复后端任务，暂停状态不自动恢复，继续后返回完整结果', async function () {
    let state = 'PAUSED';
    const requests = [];
    const h = progressSandbox(async (url, init) => {
        requests.push([url, init.method]);
        if (url.includes('/resume')) state = 'COMPLETED';
        return jsonResponse(view(state, {outcome: 'OK', ok: state === 'COMPLETED' ? 1 : 0}));
    });
    const result = h.api.runSingleNovel({novelId: 11, job: view('PAUSED')});
    await flush();
    assert.equal(requests.some(r => r[1] === 'POST'), false);
    assert.equal(h.nodes.get('.pt-progress-action').textContent, 'Resume');
    assert.match(h.nodes.get('.pt-progress-sub').textContent, /2/);
    h.nodes.get('.pt-progress-action').onclick();
    const complete = await result;
    assert.equal(complete.result.status, 'OK');
    assert.equal(complete.result.variantKey, 'zh-CN@youdao');
    assert.equal(h.timers.size, 0);
    assert.equal(requests.filter(r => r[1] === 'POST').length, 1);
});

test('取消显示后端停止状态，丢弃需再次确认且不删正式译文', async function () {
    let state = 'RUNNING';
    const requests = [];
    const h = progressSandbox(async (url, init) => {
        requests.push([url, init.method]);
        if (url.includes('/cancel')) state = 'CANCELLING';
        if (init.method === 'DELETE') return {ok: true, status: 204};
        return jsonResponse(view(state));
    });
    const result = h.api.runSeries({seriesId: 12, job: view('RUNNING')});
    await flush();
    h.nodes.get('.pt-progress-cancel').onclick();
    await flush();
    assert.equal(h.nodes.get('.pt-progress-cancel').disabled, true);
    assert.equal(h.api.hasActiveJob(), true);
    state = 'CANCELLED';
    for (const timer of Array.from(h.timers)) { h.timers.delete(timer); timer(); }
    await flush();
    h.nodes.get('.pt-progress-cancel').onclick();
    assert.equal(requests.some(r => r[1] === 'DELETE'), false);
    h.nodes.get('.pt-progress-cancel').onclick();
    assert.equal((await result).cancelled, true);
    assert.equal(requests.filter(r => r[1] === 'DELETE').length, 1);
    assert.ok(requests.every(r => r[0].startsWith('/api/translation/jobs/')));
});

test('迟到进度不能覆盖取消结果，读取失败不自动重发翻译请求', async function () {
    let stale;
    let reads = 0;
    const requests = [];
    const h = progressSandbox(async (url, init) => {
        requests.push([url, init.method]);
        if (url.includes('/cancel')) return jsonResponse(view('CANCELLED'));
        if (++reads === 1) return new Promise(resolve => { stale = resolve; });
        return jsonResponse(view('CANCELLED'));
    });
    const result = h.api.runSingleNovel({novelId: 11, job: view('RUNNING')});
    await flush();
    h.nodes.get('.pt-progress-cancel').onclick();
    await flush();
    stale(jsonResponse(view('RUNNING')));
    await flush();
    assert.equal(h.nodes.get('.pt-progress-action').textContent, 'Resume');
    h.nodes.get('.pt-progress-hide').onclick();
    await result;
    assert.equal(requests.filter(r => r[1] === 'POST').length, 1);
    assert.equal(h.timers.size, 0);
});
