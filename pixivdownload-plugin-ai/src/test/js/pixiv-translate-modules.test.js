'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const STATIC_DIR = path.join(__dirname, '..', '..', 'main', 'resources', 'static', 'pixiv-ai');
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

test('翻译目标默认值使用当前界面语言，单篇与自动翻译保持一致', async function (t) {
    const {parse} = await import('../../../../scripts/i18n/lib/properties-parser.mjs');
    const catalog = JSON.parse(fs.readFileSync(path.join(__dirname,
        '../../../../pixivdownload-app/src/main/resources/i18n/locales.json'), 'utf8'));
    for (const locale of catalog.locales.filter(l => ['source', 'supported'].includes(l.status))) {
        await t.test(locale.tag, function () {
            const suffix = locale.resourceSuffix ? '_' + locale.resourceSuffix : '';
            for (const [bundle, key] of [['translate', 'dialog.language-default'], ['ai', 'batch.translate-lang-default']]) {
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
    evaluateFile(sandbox, slotFile);
    return waitFor(function () { return failedScript ? toasts.length : mounted; }).then(function () {
        return {loadedScripts, styles, mounted, sandbox, toasts};
    });
}

test('AI 翻译模块公开稳定入口并复用配置探测结果', async function () {
    let statusRequests = 0;
    const sandbox = createSandbox({
        fetch: async function (url) {
            assert.equal(url, '/api/admin/ai/status');
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
        sandbox.PixivTranslate.isAiConfigured(),
        sandbox.PixivTranslate.isAiConfigured()
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
        ['novel-detail-ai-translate-slot.js', function (mounted) {
            return {PixivNovel: {content: {mountContentLangSwitcher: mounted}}};
        }],
        ['series-detail-ai-translate-slot.js', function (mounted) {
            return {setupNovelContentControls: mounted};
        }]
    ];

    for (const [slotFile, globals] of scenarios) {
        const result = await runSlot(slotFile, globals);
        assert.deepEqual(result.loadedScripts, MODULE_FILES, slotFile);
        assert.deepEqual(result.styles, ['/pixiv-ai/pixiv-translate.css'], slotFile);
        assert.equal(typeof result.sandbox.PixivTranslate.openDialog, 'function');
        assert.equal(typeof result.sandbox.PixivContentLang.mount, 'function');
    }
});

test('AI 翻译槽位在职责脚本加载失败时提示并停止后续初始化', async function () {
    const scenarios = [
        ['novel-detail-ai-translate-slot.js', function (mounted) {
            return {PixivNovel: {content: {mountContentLangSwitcher: mounted}}};
        }],
        ['series-detail-ai-translate-slot.js', function (mounted) {
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
    const sandbox = createSandbox({fetch, document: {body, createElement: element},
        setInterval(callback) { timers.add(callback); return callback; },
        clearInterval(callback) { timers.delete(callback); }});
    evaluateFile(sandbox, 'pixiv-translate.js');
    return {api: sandbox.PixivTranslate, nodes, timers, body};
}

function jsonResponse(data) { return {ok: true, json: async () => data}; }

test('单本翻译重复打开只展示当前进度，合订失败仍保留翻译结果并释放计时器', async function () {
    let complete;
    const requests = [];
    const h = progressSandbox(async (url, init) => {
        requests.push([url, init]);
        if (url.endsWith('/translate')) return new Promise(resolve => { complete = resolve; });
        return {ok: false, status: 503};
    });
    const running = h.api.runSingleNovel({novelId: 11, seriesId: 12,
        choice: {targetLanguage: 'example-language', glossaryId: 3}});
    assert.equal(h.api.hasActiveJob(), true);
    assert.equal(h.timers.size, 1);
    h.nodes.get('.pt-progress-hide').onclick();
    assert.equal(h.body.children[0].classList.contains('open'), false);
    assert.equal(await h.api.runSeries({seriesId: 99}), null);
    assert.equal(h.body.children[0].classList.contains('open'), true);
    assert.equal(h.nodes.get('.pt-progress-stats-text').style.display, 'none');
    assert.equal(requests.length, 1);
    complete(jsonResponse({status: 'OK', langCode: 'en'}));
    const outcome = await running;
    assert.equal(outcome.result.status, 'OK');
    assert.equal(outcome.mergeFailed.message, 'HTTP 503');
    assert.deepEqual(requests.map(r => r[0]), ['/api/novel/11/translate',
        '/api/novel/series/12/translate-title', '/api/novel/series/12/merge?format=epub&lang=en']);
    assert.equal(h.api.hasActiveJob(), false);
    assert.equal(h.timers.size, 0);
    assert.equal(h.body.children[0].classList.contains('open'), false);
});

test('单本翻译失败或取消均结束任务，取消不触发合订', async function () {
    for (const cancel of [false, true]) {
        let reject;
        let signal;
        let requests = 0;
        const h = progressSandbox((url, init) => {
            requests++;
            signal = init.signal;
            return new Promise((resolve, fail) => { reject = fail; });
        });
        const running = h.api.runSingleNovel({novelId: 11, seriesId: 12, choice: {}});
        if (cancel) h.nodes.get('.pt-progress-cancel').onclick();
        assert.equal(signal.aborted, cancel);
        reject(new Error('injected failure'));
        const result = await running;
        if (cancel) assert.equal(result.cancelled, true);
        else assert.equal(result.error.message, 'injected failure');
        assert.equal(requests, 1);
        assert.equal(h.api.hasActiveJob(), false);
        assert.equal(h.timers.size, 0);
    }
});

test('系列探测失败后继续串行翻译，保留跳过与失败计数和返回的语言', async function () {
    const requests = [];
    const replies = ['SAME_LANGUAGE', 'OK', 'SKIPPED', 'failure'];
    const h = progressSandbox(async (url, init) => {
        requests.push([url, init]);
        if (url.endsWith('/novel-ids')) return jsonResponse({novelIds: [1, 2, 3, 4]});
        if (url.endsWith('/translate-lang-probe')) throw new Error('probe unavailable');
        if (url.endsWith('/translate')) {
            const index = Number(url.split('/')[3]) - 1;
            assert.equal(h.nodes.get('.pt-progress-sub-text').textContent,
                'Translating chapter ' + (index + 1) + ' / 4');
            assert.equal(h.nodes.get('.pt-progress-stats-text').style.display, '');
            const body = JSON.parse(init.body);
            assert.equal(body.langHint, index < 2 ? null : 'en');
            if (index === 3) throw new Error('chapter failed');
            return jsonResponse({status: replies[index], langCode: 'en'});
        }
        return jsonResponse({});
    });
    const result = await h.api.runSeries({seriesId: 5, choice: {targetLanguage: 'example-language'}});
    assert.deepEqual(JSON.parse(JSON.stringify(result)), {cancelled: false, invalid: false,
        ok: 1, skipped: 2, failed: 1, langCode: 'en', mergeFailed: null});
    assert.deepEqual(requests.filter(r => r[0].endsWith('/translate')).map(r => r[0]),
        [1, 2, 3, 4].map(id => '/api/novel/' + id + '/translate'));
    assert.equal(requests.at(-1)[0], '/api/novel/series/5/merge?format=epub&lang=en');
    assert.equal(h.timers.size, 0);
});

test('系列取消终止后续章节，但仍合订已完成译文', async function () {
    let interrupted;
    const translated = [];
    let merged = false;
    const h = progressSandbox(async (url, init) => {
        if (url.endsWith('/novel-ids')) return jsonResponse({novelIds: [1, 2, 3]});
        if (url.endsWith('/translate')) {
            translated.push(url);
            if (translated.length === 1) return jsonResponse({status: 'OK', langCode: 'en'});
            return new Promise((resolve, reject) => { interrupted = () => {
                assert.equal(init.signal.aborted, true);
                reject(new Error('cancelled request'));
            }; });
        }
        if (url.includes('/merge?')) merged = true;
        return jsonResponse({});
    });
    const running = h.api.runSeries({seriesId: 5, choice: {overwrite: true}});
    await waitFor(() => interrupted);
    h.nodes.get('.pt-progress-cancel').onclick();
    interrupted();
    const result = await running;
    assert.equal(result.cancelled, true);
    assert.equal(result.ok, 1);
    assert.equal(result.failed, 0);
    assert.equal(translated.length, 2);
    assert.equal(merged, true);
    assert.equal(h.api.hasActiveJob(), false);
    assert.equal(h.timers.size, 0);
});

test('同语言、无效语言、空系列及列表失败都不合订并释放进度', async function () {
    for (const status of ['SAME_LANGUAGE', 'INVALID_LANGUAGE', 'empty', 'error']) {
        const h = progressSandbox(async url => {
            if (url.endsWith('/novel-ids')) {
                if (status === 'error') throw new Error('list failed');
                return jsonResponse({novelIds: status === 'empty' ? [] : [1]});
            }
            assert.ok(url.endsWith('/translate'));
            return jsonResponse({status, langCode: 'en'});
        });
        const result = await h.api.runSeries({seriesId: 5, choice: {overwrite: true}});
        if (status === 'error') assert.equal(result.error.message, 'list failed');
        else if (status === 'empty') assert.equal(result.empty, true);
        else {
            assert.equal(result.skipped, status === 'SAME_LANGUAGE' ? 1 : 0);
            assert.equal(result.invalid, status === 'INVALID_LANGUAGE');
            assert.equal(result.langCode, null);
        }
        assert.equal(h.api.hasActiveJob(), false);
        assert.equal(h.timers.size, 0);
    }
});
