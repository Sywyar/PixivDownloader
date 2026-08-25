'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const STATIC_DIR = path.join(__dirname, '..', '..', 'main', 'resources', 'static', 'pixiv-tts');
const MODULE_FILES = [
    'pixiv-novel-narration-core.js',
    'pixiv-novel-narration-marks.js',
    'pixiv-novel-narration-playback.js',
    'pixiv-novel-narration-cast.js',
    'pixiv-novel-narration-dialog.js',
    'pixiv-novel-narration.js'
];
const SLOT_SCRIPT_FILES = [
    'tts/tts-store.js',
    'tts/tts-voices.js',
    'tts/tts-ui.js',
    'tts/tts-engine-browser.js',
    'tts/tts-engine-edge.js',
    ...MODULE_FILES,
    'pixiv-novel-tts.js'
];

function createSandbox() {
    const listeners = [];
    const values = new Map();
    const sandbox = {
        clearTimeout,
        console,
        fetch: async function () { return {ok: false}; },
        localStorage: {
            getItem(key) { return values.has(key) ? values.get(key) : null; },
            setItem(key, value) { values.set(key, String(value)); }
        },
        setTimeout
    };
    sandbox.addEventListener = function (type) { listeners.push(type); };
    sandbox.window = sandbox;
    sandbox.__listeners = listeners;
    vm.createContext(sandbox);
    return sandbox;
}

function evaluate(sandbox, file) {
    const source = fs.readFileSync(path.join(STATIC_DIR, file), 'utf8');
    vm.runInContext(source, sandbox, {filename: file});
}

async function waitFor(predicate) {
    for (let i = 0; i < 20; i++) {
        if (predicate()) return;
        await new Promise(function (resolve) { setImmediate(resolve); });
    }
    assert.fail('slot initialization did not finish');
}

function runSlot(failedScript) {
    const loadedScripts = [];
    const toasts = [];
    const sandbox = createSandbox();
    const slotHost = {};
    const content = {querySelector() { return {}; }};
    const head = {
        appendChild(node) {
            if (node.rel === 'stylesheet') return node;
            const file = String(node.src).replace('/pixiv-tts/', '');
            loadedScripts.push(file);
            queueMicrotask(function () {
                if (file === failedScript) node.onerror();
                else node.onload();
            });
            return node;
        }
    };
    sandbox.PixivFeedback = {toast(options) { toasts.push(options); }};
    sandbox.document = {
        body: {insertAdjacentHTML() {}},
        documentElement: head,
        head,
        createElement() { return {}; },
        getElementById(id) {
            if (id === 'content-card') return content;
            return null;
        },
        querySelector(selector) {
            return selector === '[data-vue-slot="novel-detail-tts"]' ? slotHost : null;
        }
    };
    evaluate(sandbox, 'novel-detail-tts-slot.js');
    const expectedCount = failedScript ? SLOT_SCRIPT_FILES.indexOf(failedScript) + 1 : SLOT_SCRIPT_FILES.length;
    return waitFor(function () {
        return loadedScripts.length === expectedCount && (!failedScript || toasts.length === 1);
    }).then(function () { return {loadedScripts, toasts}; });
}

test('多角色朗读模块按显式上下文装配并保留公共门面', function () {
    const sandbox = createSandbox();
    MODULE_FILES.forEach(function (file) { evaluate(sandbox, file); });

    assert.deepEqual(Object.keys(sandbox.PixivNovelNarration).sort(), [
        'activate', 'attach', 'deactivate', 'next', 'openCast', 'prev', 'seekFrac',
        'setI18n', 'stop', 'togglePlay'
    ]);
    assert.deepEqual(Object.keys(sandbox.PixivNovelNarrationModules).sort(), [
        'cast', 'core', 'dialog', 'marks', 'playback'
    ]);
    Object.values(sandbox.PixivNovelNarrationModules).forEach(function (module) {
        assert.equal(typeof module.install, 'function');
    });

    sandbox.PixivNovelNarration.attach({
        contentEl: {querySelectorAll() { return []; }},
        els: {playPause: {}, progress: {}},
        i18n: {t(key) { return key; }},
        lang: '',
        novelId: '42',
        toast() {}
    });
    sandbox.PixivNovelNarration.setI18n({t(key) { return key; }});
    assert.deepEqual(sandbox.__listeners.sort(), ['beforeunload', 'resize']);
});

test('公共门面在任一职责模块缺失时失败关闭', function () {
    const sandbox = createSandbox();
    MODULE_FILES.slice(0, 4).forEach(function (file) { evaluate(sandbox, file); });
    assert.throws(function () { evaluate(sandbox, 'pixiv-novel-narration.js'); }, /module is missing: dialog/);
    assert.equal(sandbox.PixivNovelNarration, undefined);
});

test('TTS 槽位按依赖顺序加载朗读职责模块', async function () {
    const result = await runSlot();
    assert.deepEqual(result.loadedScripts, SLOT_SCRIPT_FILES);
});

test('TTS 槽位在职责脚本加载失败时提示并停止后续初始化', async function () {
    const failedScript = 'pixiv-novel-narration-marks.js';
    const result = await runSlot(failedScript);
    assert.deepEqual(result.loadedScripts,
        SLOT_SCRIPT_FILES.slice(0, SLOT_SCRIPT_FILES.indexOf(failedScript) + 1));
    assert.equal(result.toasts.length, 1);
    assert.equal(result.toasts[0].kind, 'error');
});

test('共享反馈组件缺失时朗读交互显示错误并失败关闭', async function () {
    const sandbox = createSandbox();
    evaluate(sandbox, 'pixiv-novel-narration-core.js');
    const ctx = {};
    const toasts = [];
    sandbox.PixivNovelNarrationModules.core.install(ctx);
    ctx.core.state.toast = function (message, kind) { toasts.push({message, kind}); };

    assert.equal(await ctx.core.feedbackPrompt('prompt', 'value'), null);
    assert.equal(await ctx.core.feedbackConfirm('confirm'), false);
    assert.deepEqual(toasts.map(function (item) { return item.kind; }), ['error', 'error']);
});
