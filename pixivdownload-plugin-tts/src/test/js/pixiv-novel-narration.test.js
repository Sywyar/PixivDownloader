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

test('TTS 槽位按依赖顺序加载朗读职责模块', function () {
    const source = fs.readFileSync(path.join(STATIC_DIR, 'novel-detail-tts-slot.js'), 'utf8');
    const urls = MODULE_FILES.map(function (file) { return "BASE + '" + file + "'"; });
    const positions = urls.map(function (url) { return source.indexOf(url); });
    positions.forEach(function (position, index) {
        assert.notEqual(position, -1, MODULE_FILES[index]);
        if (index) assert.ok(position > positions[index - 1], MODULE_FILES[index]);
    });
    assert.ok(source.indexOf("BASE + 'pixiv-novel-tts.js'") > positions.at(-1));

    MODULE_FILES.forEach(function (file) {
        const moduleSource = fs.readFileSync(path.join(STATIC_DIR, file), 'utf8');
        const lines = moduleSource.split(/\r?\n/).length;
        assert.ok(lines < 1000, file + ' should remain responsibility-sized');
        assert.doesNotMatch(moduleSource, /\bwindow\.(?:alert|confirm|prompt)\s*\(/,
            file + ' should use PixivFeedback');
    });
});
