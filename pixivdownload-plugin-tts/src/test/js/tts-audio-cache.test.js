'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const ROOT = path.join(__dirname, '../../main/resources/static/pixiv-tts');
const tick = () => new Promise(resolve => setImmediate(resolve));

function deferred() {
    let resolve;
    const promise = new Promise(done => { resolve = done; });
    return {promise, resolve};
}

function fixture(kind, boundary) {
    const held = deferred();
    const reached = deferred();
    const writes = [], reads = [], requests = [], created = [], revoked = [], played = [], touched = [], cancelled = [];
    const blob = {voice: 'original'};
    const wait = async stage => {
        if (stage === boundary) { reached.resolve(); return held.promise; }
    };
    const store = {
        async getAudio(key) {
            reads.push(key);
            await wait('storage');
            await wait('persisted');
            return boundary === 'persisted' ? blob : null;
        },
        putAudio(...args) { writes.push(args); },
        touch(id) { touched.push(id); }
    };
    const sandbox = {
        URL: {
            createObjectURL(value) {
                const url = 'blob:' + created.length;
                created.push({url, value});
                return url;
            },
            revokeObjectURL(url) { revoked.push(url); }
        },
        Audio: class {
            play() { played.push(this.src); return Promise.resolve(); }
            pause() { this.paused = true; }
            removeAttribute(name) { delete this[name]; }
            load() {}
        },
        async fetch(url, options) {
            requests.push({url, body: JSON.parse(options.body)});
            await wait('response');
            return {ok: true, body: {async cancel() { cancelled.push(url); }},
                async blob() { await wait('blob'); return blob; }};
        },
        localStorage: {getItem() { return null; }},
        PixivTtsStore: {createAudioStore() { return store; }}
    };
    sandbox.window = sandbox;
    vm.createContext(sandbox);
    const evaluate = name => vm.runInContext(fs.readFileSync(path.join(ROOT, name), 'utf8'), sandbox, {filename: name});
    let ctx, state, start, stop, clear, cache;
    if (kind === 'edge') {
        state = {novelId: '1', segments: [{text: 'original text'}], edgeCache: new Map(), token: 0};
        ctx = {state, store, els: {voice: {value: 'original-voice'}, rate: {value: 0}},
            ratePercent: () => ctx.els.rate.value, updateProgress() {}, updateBar() {},
            stop() { state.token++; state.edgeFetching = null; state.edgePending = null; }};
        evaluate('tts/tts-engine-edge.js');
        sandbox.PixivTtsEngineEdge.install(ctx);
        start = () => ctx.speakEdge(0, state.token);
        stop = ctx.stop;
        clear = ctx.clearEdgeCache;
        cache = state.edgeCache;
    } else {
        evaluate('pixiv-novel-narration-core.js');
        evaluate('pixiv-novel-narration-playback.js');
        ctx = {marks: {buildBlocks: () => [], speakerLabel: () => '', renderMarks() {}}};
        sandbox.PixivNovelNarrationModules.core.install(ctx);
        state = ctx.core.state;
        Object.assign(state, {novelId: '1', lang: 'original-lang', castUpdatedTime: 8,
            analyzedTime: 9, lines: [{text: 'original text'}], scriptLoaded: true});
        Object.assign(ctx.core.els, {progress: {}, playPause: {}});
        sandbox.PixivNovelNarrationModules.playback.install(ctx);
        start = ctx.playback.togglePlay;
        stop = ctx.playback.stop;
        clear = ctx.playback.clearCache;
        cache = state.cache;
    }
    return {ctx, state, start, stop, clear, cache, held, reached, reads, writes, requests, created, revoked, played, touched, cancelled, sandbox};
}

for (const kind of ['edge', 'narration']) {
    for (const boundary of ['storage', 'persisted', 'response', 'blob']) {
        test(`${kind}：清空后迟到的${boundary}结果不回填、不落盘且不创建无归属URL`, async () => {
            const f = fixture(kind, boundary);
            f.start();
            await f.reached.promise;
            f.stop();
            f.clear();
            f.held.resolve();
            await tick();
            assert.equal(f.cache.size, 0);
            assert.equal(f.created.length, 0);
            assert.equal(f.writes.length, 0);
            assert.equal(f.touched.length, 0);
            assert.equal(f.played.length, 0);
            assert.equal(f.requests.length, ['storage', 'persisted'].includes(boundary) ? 0 : 1);
            assert.equal(f.cancelled.length, boundary === 'response' ? 1 : 0);
        });
    }

    test(`${kind}：停止保留在途结果供重播复用，最终清空释放URL`, async () => {
        const f = fixture(kind, 'response');
        f.start();
        await f.reached.promise;
        f.stop();
        f.held.resolve();
        await tick();
        assert.equal(f.cache.size, 1);
        assert.equal(f.writes.length, 1);
        assert.equal(f.played.length, 0);
        f.start();
        await tick();
        assert.equal(f.requests.length, 1);
        assert.deepEqual(f.played, ['blob:0']);
        f.stop();
        f.clear();
        assert.deepEqual(f.revoked, ['blob:0']);
    });

    test(`${kind}：等待读盘期间固定小说与合成参数`, async () => {
        const f = fixture(kind, 'storage');
        f.start();
        await f.reached.promise;
        f.state.novelId = '2';
        if (kind === 'edge') {
            f.ctx.els.voice.value = 'later-voice';
            f.ctx.els.rate.value = 25;
            f.state.segments[0].text = 'later text';
        } else {
            f.state.lang = 'later-lang';
            f.state.castUpdatedTime = 18;
            f.state.analyzedTime = 19;
        }
        f.held.resolve();
        await tick();
        assert.deepEqual(f.requests[0].body, kind === 'edge'
            ? {text: 'original text', voice: 'original-voice', rate: 0, pitch: 0}
            : {novelId: 1, lineIndex: 0, lang: 'original-lang'});
        assert.equal(f.writes[0][0], f.reads[0]);
        assert.equal(f.writes[0][1], '1');
        f.stop();
        f.clear();
    });

    test(`${kind}：实例清空不影响另一实例的在途音频`, async () => {
        const first = fixture(kind, 'response');
        const second = fixture(kind, 'response');
        first.start(); second.start();
        await Promise.all([first.reached.promise, second.reached.promise]);
        first.stop(); first.clear();
        first.held.resolve(); second.held.resolve();
        await tick();
        assert.equal(first.cache.size, 0);
        assert.equal(second.cache.size, 1);
        assert.deepEqual(second.played, ['blob:0']);
        second.stop(); second.clear();
    });

    test(`${kind}：命中缓存后的微任务也不能播放已清空的URL`, async () => {
        const f = fixture(kind);
        f.start();
        await tick();
        f.stop();
        f.start();
        f.clear();
        await tick();
        assert.deepEqual(f.played, ['blob:0']);
        assert.deepEqual(f.revoked, ['blob:0']);
        f.stop();
    });

    test(`${kind}：清空释放播放器来源并拒绝旧播放事件`, async () => {
        const f = fixture(kind);
        let advances = 0;
        f.ctx.speak = () => advances++;
        f.start();
        await tick();
        const audio = kind === 'edge' ? f.state.edgeAudio : f.state.audio;
        const ended = audio.onended, failed = audio.onerror;
        const index = f.state.index;
        f.clear();
        ended(); failed();
        assert.equal(audio.src, undefined);
        assert.equal(audio.paused, true);
        assert.equal(advances, 0);
        assert.equal(f.state.index, index);
        f.stop();
    });

    for (const stale of [false, true]) {
        test(`${kind}：${stale ? '清空后旧' : '当前'}请求失败${stale ? '不干扰新状态' : '仍报告错误'}`, async () => {
            const f = fixture(kind);
            const held = deferred();
            const errors = [];
            f.state.toast = (...args) => errors.push(args);
            f.sandbox.fetch = async () => { await held.promise; throw new Error('synthesis unavailable'); };
            f.start();
            await tick();
            if (stale) f.clear();
            held.resolve();
            await tick();
            assert.equal(errors.length, stale ? 0 : 1);
            assert.equal(f.created.length, 0);
            f.stop(); f.clear();
        });
    }
}

test('Edge：同键并发响应共用一个有归属的URL', async () => {
    const f = fixture('edge', 'response');
    const first = f.ctx.fetchEdgeAudio(0);
    const second = f.ctx.fetchEdgeAudio(0);
    await f.reached.promise;
    f.held.resolve();
    assert.deepEqual(await Promise.all([first, second]), ['blob:0', 'blob:0']);
    assert.equal(f.created.length, 1);
    f.clear();
    assert.deepEqual(f.revoked, ['blob:0']);
});

test('浏览器语音：结束或失败推进当前段，取消后旧事件失效', () => {
    let utterance;
    const advances = [];
    const voice = {voiceURI: 'voice', lang: 'en', localService: false};
    const sandbox = {SpeechSynthesisUtterance: class {constructor(text) { this.text = text; }},
        speechSynthesis: {cancel() {}, speak(value) { utterance = value; }}};
    sandbox.window = sandbox;
    vm.createContext(sandbox);
    vm.runInContext(fs.readFileSync(path.join(ROOT, 'tts/tts-engine-browser.js'), 'utf8'), sandbox);
    const ctx = {state: {token: 1, segments: [{text: 'Text'}], browserVoices: [voice]},
        els: {voice: {value: 'voice'}}, browserRate: () => 1.2, speak: index => advances.push(index)};
    sandbox.PixivTtsEngineBrowser.install(ctx);
    ctx.speakBrowser(0, 1);
    assert.equal(utterance.text, 'Text');
    assert.equal(utterance.voice, voice);
    assert.equal(utterance.rate, 1.2);
    utterance.onend(); utterance.onerror();
    assert.deepEqual(advances, [1, 1]);
    ctx.state.token++;
    utterance.onend(); utterance.onerror();
    assert.deepEqual(advances, [1, 1]);
});

test('朗读：旧请求结束不能移除清空后重新发起的同句请求', async () => {
    const f = fixture('narration');
    const responses = [];
    f.sandbox.fetch = () => {
        const request = deferred(); responses.push(request); return request.promise;
    };
    f.start();
    await tick();
    f.stop(); f.clear(); f.start();
    await tick();
    assert.equal(responses.length, 2);
    responses[0].resolve({ok: true, blob: async () => ({old: true})});
    await tick();
    assert.equal(f.state.inflight.size, 1);
    assert.equal(f.created.length, 0);
    responses[1].resolve({ok: true, blob: async () => ({current: true})});
    await tick();
    assert.equal(f.state.inflight.size, 0);
    assert.equal(f.cache.size, 1);
    assert.deepEqual(f.played, ['blob:0']);
    f.stop(); f.clear();
});

for (const paused of [false, true]) {
    test(`朗读：音色刷新后${paused ? '保留暂停，恢复时' : '从当前句'}使用新音频`, async () => {
        const f = fixture('narration');
        const originalFetch = f.sandbox.fetch;
        f.sandbox.fetch = (url, options) => url === '/api/narration/script'
            ? Promise.resolve({ok: true, json: async () => ({lines: [{text: 'original text'}], castUpdatedTime: 18, analyzedTime: 9})})
            : originalFetch(url, options);
        f.start();
        if (paused) f.start();
        await tick();
        assert.equal(f.played.length, paused ? 0 : 1);
        f.clear();
        await f.ctx.playback.reloadCachedScript();
        await tick();
        if (paused) {
            assert.equal(f.played.length, 0);
            assert.equal(f.state.paused, true);
            f.start();
            await tick();
        }
        assert.equal(f.requests.length, 2);
        assert.equal(f.played.at(-1), 'blob:1');
        assert.equal(f.writes.at(-1)[0], '1|original-lang|18|9|0');
        assert.deepEqual(f.revoked, ['blob:0']);
        f.stop(); f.clear();
    });
}

for (const missing of [false, true]) {
    test(`朗读：刷新脚本${missing ? '缺席' : '失败'}后恢复可重试的停止状态`, async () => {
        const f = fixture('narration');
        f.start();
        await tick();
        f.clear();
        f.sandbox.fetch = async () => {
            if (missing) return {status: 204};
            throw new Error('offline');
        };
        await f.ctx.playback.reloadCachedScript();
        assert.equal(f.state.playing, false);
        assert.equal(f.state.scriptLoaded, false);
        assert.equal(f.state.audio.src, undefined);
    });
}

test('朗读：旧脚本刷新响应不能覆盖后发的新音色信息', async () => {
    const f = fixture('narration');
    const old = deferred();
    f.sandbox.fetch = () => old.promise;
    const first = f.ctx.playback.reloadCachedScript();
    f.clear();
    f.sandbox.fetch = async () => ({ok: true, json: async () => ({lines: [{text: 'new'}], castUpdatedTime: 20})});
    await f.ctx.playback.reloadCachedScript();
    old.resolve({ok: true, json: async () => ({lines: [{text: 'old'}], castUpdatedTime: 10})});
    await first;
    assert.equal(f.state.castUpdatedTime, 20);
    assert.equal(f.state.lines[0].text, 'new');
});
