'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const root = path.join(__dirname, '../../main/resources/static');
const read = name => fs.readFileSync(path.join(root, name), 'utf8');

function element(tag, cls, text = '') {
    const node = {tag, cls, text, children: [], attributes: {}, style: {},
        appendChild(child) { this.children.push(child); },
        setAttribute(key, value) { this.attributes[key] = value; }};
    node.classList = {add(value) { node.cls += ' ' + value; }};
    return node;
}
const textOf = node => node ? node.text + node.children.map(textOf).join(' ') : '';
const nodesOf = node => node ? [node, ...node.children.flatMap(nodesOf)] : [];

function fixture(alt) {
    const timers = new Map(), intervals = new Map();
    let next = 0, queries = 0;
    const q = {id: '123', kind: 'illust', status: 'downloading', totalImages: 1, downloadedCount: 0};
    const context = vm.createContext({
        window: {PixivBatch: {}, PixivBatchAlt: {engine: {}, queue: {}}},
        STATUS_TIMEOUT_MS: 1000,
        state: {queue: [q], settings: {}, sseListeners: {}},
        document: {createTextNode: text => element('#text', '', text)}, el: element,
        bt: (key, fallback, args) => key + (args ? ' ' + Object.values(args).join('/') : ''),
        esc: value => String(value).replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;'),
        formatBytes: n => n + ' B', formatDurationMs: n => n + ' ms',
        renderQueue() {}, setCurrent() {}, renderCurrent() {},
        setTimeout: fn => { timers.set(++next, fn); return next; },
        clearTimeout: id => timers.delete(id),
        setInterval: fn => { intervals.set(++next, fn); return next; },
        clearInterval: id => intervals.delete(id),
        getDownloadStatus: async () => { queries++; return null; }
    }, {codeGeneration: {strings: false, wasm: false}});
    for (const file of ['pixiv-batch/batch-media-progress.js', 'pixiv-batch/batch-queue-model.js',
        ...(alt ? ['pixiv-batch-alt/alt-queue.js'] : []),
        alt ? 'pixiv-batch-alt/alt-engine-stream.js' : 'pixiv-batch/batch-sse.js']) {
        vm.runInContext(read(file), context);
    }
    Object.assign(context, {renderQueue() {}, setCurrent() {}, renderCurrent() {}});
    const render = () => alt ? textOf(context.progressExtras(q))
            : context.formatImageDownloadProgressHtml(q.imageProgress, q.status)
                + context.formatUgoiraProgressHtml(q.ugoiraProgress, q.status);
    return {context, q, timers, intervals, queries: () => queries, render,
        bars: () => alt ? nodesOf(context.progressExtras(q)).filter(n => n.attributes.role === 'progressbar').map(n => n.attributes)
            : [...render().matchAll(/<div\b[^>]*role="progressbar"[^>]*>/g)]
                .map(match => Object.fromEntries([...match[0].matchAll(/([\w-]+)="([^"]*)"/g)].map(m => [m[1], m[2]]))),
        emit: data => [...(context.state.sseListeners['123'] || [])].forEach(fn => fn(data))};
}

for (const alt of [false, true]) {
    const layout = alt ? 'alt' : 'classic';
    test(`${layout}：下载和并行转码分别显示，等待传输结束不残留字节进度`, async () => {
        const f = fixture(alt);
        const waiting = f.context.waitForFinalStatusBySSE('123', 1000);
        f.emit({downloadedCount: 0, imageProgress: {imageNumber: 2, totalImages: 3, downloadedBytes: 25, totalBytes: 100, progress: 25,
            processing: [{imageNumber: 1, totalImages: 3, phase: 'ffmpeg', outputFormat: 'png'}]}});
        assert.equal(f.bars().length, 2);
        assert.equal(f.bars()[0]['aria-valuenow'], '25');
        assert.match(f.bars()[1]['aria-label'], /queue.media.converting.*1\/3/);
        assert.equal(f.bars()[1]['aria-valuenow'], undefined);
        const poll = [...f.intervals.values()][0];
        f.context.getDownloadStatus = async () => ({success: true, downloadedCount: 0, imageProgress: {phase: 'processing',
            processing: [{imageNumber: 1, totalImages: 3, phase: 'ffmpeg-waiting', outputFormat: 'png'},
                {imageNumber: 2, totalImages: 3, phase: 'ffmpeg', outputFormat: 'png'}]}});
        await poll();
        assert.equal(f.bars().length, 2);
        assert.match(f.bars()[0]['aria-label'], /queue.media.waiting/);
        assert.match(f.bars()[1]['aria-label'], /queue.media.converting/);
        assert.doesNotMatch(f.render(), /25 B|25%/);
        f.emit({downloadedCount: 3, imageProgress: null});
        assert.equal(f.bars().length, 0);
        f.emit({completed: true, downloadedCount: 3, imageProgress: null});
        assert.equal((await waiting).imageProgress, null);
    });

    test(`${layout}：格式等待与校验显示真实阶段，终态隐藏旧进度，文本安全`, () => {
        const f = fixture(alt);
        for (const phase of ['ffmpeg-waiting', 'ffmpeg', 'verifying', 'thumbnail', 'finalizing']) {
            f.q.imageProgress = {phase, outputFormat: 'png', outputIndex: 1, outputCount: 2, progress: 100};
            assert.match(f.render(), /queue.media\./);
            assert.match(f.render(), /PNG · 1\/2/);
            assert.doesNotMatch(f.render(), /100%/);
            assert.equal(f.bars().length, 1);
            assert.equal(f.bars()[0]['aria-busy'], 'true');
            assert.equal(f.bars()[0]['aria-valuenow'], undefined);
            assert.match(f.bars()[0]['aria-label'], /queue.media\./);
        }
        f.q.imageProgress.status = 'failed';
        assert.equal(f.bars()[0]['aria-busy'], 'false');
        assert.match(f.render(), /queue.media.failed/);
        f.q.imageProgress.outputFormat = '<img src=x onerror=alert(1)>';
        f.q.imageProgress.status = 'running';
        if (!alt) assert.doesNotMatch(f.render(), /<IMG/i);
        else assert.equal(nodesOf(f.context.progressExtras(f.q)).some(n => n.tag.toLowerCase() === 'img'), false);
        f.q.status = 'completed';
        assert.equal(f.render(), '');
        assert.equal(f.bars().length, 0);
    });

    test(`${layout}：多格式 Ugoira 进入下一轮等待时不继承上一格式 100%`, () => {
        const f = fixture(alt);
        f.q.ugoiraProgress = f.context.mergeUgoiraProgress(
            {phase: 'ffmpeg', ffmpegProgress: 100, ffmpegOutTimeMs: 5000, outputFormat: 'webp'},
            {phase: 'ffmpeg-waiting', outputFormat: 'mp4', outputIndex: 2, outputCount: 2});
        assert.equal(f.q.ugoiraProgress.ffmpegProgress, undefined);
        assert.match(f.render(), /queue.media.waiting · MP4 · 2\/2/);
        assert.doesNotMatch(f.render(), /100%|5000/);
        assert.equal(f.bars().at(-1)['aria-busy'], 'true');
        assert.equal(f.bars().at(-1)['aria-valuenow'], undefined);
        f.q.ugoiraProgress = {phase: 'ffmpeg', status: 'running', ffmpegProgress: 37, outputFormat: 'mp4'};
        assert.match(f.render(), /37%/);
        assert.equal(f.bars().at(-1)['aria-valuenow'], '37');
        assert.equal(f.bars().at(-1)['aria-busy'], 'false');
        f.q.ugoiraProgress = {phase: 'finalizing', status: 'running'};
        assert.equal(f.bars().at(-1)['aria-busy'], 'true');
        assert.equal(f.bars().at(-1)['aria-valuenow'], undefined);
        f.q.ugoiraProgress = {phase: 'zip', status: 'failed'};
        assert.equal(f.bars().every(bar => bar['aria-busy'] === 'false'), true);
    });

    test(`${layout}：轮询恢复等待并延长看门狗，同一时刻仅一次查询且晚到响应不覆盖 SSE`, async () => {
        const f = fixture(alt);
        const waiting = f.context.waitForFinalStatusBySSE('123', 1000);
        const poll = [...f.intervals.values()][0];
        let resolve, count = 0;
        f.context.getDownloadStatus = () => { count++; return new Promise(r => { resolve = r; }); };
        const initialTimer = [...f.timers.keys()][0];
        let pending = poll();
        await poll();
        assert.equal(count, 1);
        resolve({success: true, downloadedCount: 0, imageProgress: {phase: 'ffmpeg-waiting', outputFormat: 'png'}});
        await pending;
        assert.equal(f.q.imageProgress.phase, 'ffmpeg-waiting');
        assert.equal(f.timers.has(initialTimer), false);
        pending = poll();
        f.emit({downloadedCount: 0, imageProgress: {phase: 'ffmpeg', outputFormat: 'png'}});
        resolve({success: true, downloadedCount: 0, imageProgress: {phase: 'ffmpeg-waiting'}});
        await pending;
        assert.equal(f.q.imageProgress.phase, 'ffmpeg');
        f.emit({downloadedCount: 0, imageProgress: null, ugoiraProgress: null});
        assert.equal(f.q.imageProgress, null);
        pending = poll();
        f.emit({cancelled: true, completed: true});
        resolve({success: true, downloadedCount: 0, imageProgress: {phase: 'ffmpeg'}});
        await pending;
        assert.equal((await waiting).cancelled, true);
        assert.equal(f.q.imageProgress, null);
        assert.equal(f.timers.size + f.intervals.size, 0);
        assert.equal(Object.keys(f.context.state.sseListeners).length, 0);
    });

    for (const terminal of [{cancelled: true, completed: true}, {failed: true, completed: true}]) {
        test(`${layout}：媒体取消或失败优先于 completed 标志`, async () => {
            const f = fixture(alt), c = f.context;
            Object.assign(c, {
                normalizeAuthorId: () => null, evaluateDownloadFilterSkip: () => null,
                getArtworkMeta: async () => ({illustTitle: 'test', illustType: 0}),
                getArtworkPages: async () => ['https://i.pximg.net/test.jpg'],
                sendDownload: async () => ({}), waitForFinalStatusBySSE: async () => terminal,
                openSSE() {}, closeSSE() {}, setStatus() {}, setDockStatus() {},
                saveQueue() {}, updateStats() {}, handlePathActionError: () => false,
                assertProcessInvocation() {}, notifyFirstDownloadCompleted() { assert.fail('unexpected success'); }
            });
            const name = alt ? 'pixiv-batch-alt/alt-engine-workers.js' : 'pixiv-batch/batch-download-workers.js';
            const source = read(name), start = source.indexOf('async function processIllustItem(');
            const end = alt ? source.indexOf('\nfunction pause()', start) : source.indexOf('\n    }', start) + 6;
            vm.runInContext(source.slice(start, end), c);
            await c.processIllustItem(f.q);
            assert.equal(f.q.status, terminal.cancelled ? 'paused' : 'failed', f.q.lastMessage);
            if (terminal.cancelled) assert.equal(f.q.statusMessageKey, 'queue.stage.cancelled');
        });
    }
}
