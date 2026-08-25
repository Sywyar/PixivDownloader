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

async function waitFor(predicate) {
    for (let i = 0; i < 20; i++) {
        if (predicate()) return;
        await new Promise(function (resolve) { setImmediate(resolve); });
    }
    assert.fail('slot initialization did not finish');
}

function runSlot(slotFile, pageGlobals) {
    const loadedScripts = [];
    const styles = [];
    let mounted = false;
    const sandbox = createSandbox(pageGlobals(function () { mounted = true; }));
    const head = {
        appendChild(node) {
            if (node.rel === 'stylesheet') {
                styles.push(node.href);
                return node;
            }
            const file = path.posix.basename(node.src);
            loadedScripts.push(file);
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
    return waitFor(function () { return mounted; }).then(function () {
        return {loadedScripts, styles, sandbox};
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
