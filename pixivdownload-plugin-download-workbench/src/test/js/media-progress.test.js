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
    for (const file of ['pixiv-batch/download-sse.js', 'pixiv-batch/queue-display.js', 'pixiv-batch/download-speed.js', 'pixiv-batch/batch-media-progress.js', 'pixiv-batch/batch-queue-model.js',
        ...(alt ? ['pixiv-batch-alt/alt-queue-model.js', 'pixiv-batch-alt/alt-queue.js'] : []),
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

test('alt：所有媒体固定主、子两条，成功保留最后步骤，失败与重试不继承成功', () => {
    for (const kind of ['illust', 'novel']) {
        const {context: c, q, bars} = fixture(true);
        Object.assign(q, {kind, taskId: 'attempt-a'});
        assert.deepEqual(Array.from(c.progressExtrasModel(q), entry => entry.key), ['overall', 'current']);
        Object.assign(q, {status: 'completed', downloadedCount: 1});
        assert.deepEqual(bars().map(bar => bar['aria-valuenow']), ['100', '100']);
        assert.ok(bars().every(bar => bar['aria-busy'] === 'false'));
        assert.equal(bars()[1]['aria-label'], 'batch:queue.message.completed');
        Object.assign(q, {taskId: 'attempt-b', status: 'downloading', downloadedCount: 0, novelStage: 'text'});
        assert.equal(bars()[0]['aria-valuenow'], '0');
        assert.equal(bars()[1]['aria-valuenow'], undefined);
        q.status = 'failed';
        assert.ok(bars().every(bar => bar['aria-busy'] === 'false' && bar['aria-valuenow'] !== '100'));
        q.status = 'pending';
        assert.equal(bars()[0]['aria-valuenow'], '0');
        assert.equal(bars().length, 2);
    }
});

test('alt：图片综合进度计入传输和并行处理，同图交接去重，短暂空快照不倒退', () => {
    const {context: c, q, bars} = fixture(true);
    q.totalImages = 3;
    q.imageProgress = {imageNumber: 2, totalImages: 3, progress: 50,
        processing: [{imageNumber: 1, phase: 'ffmpeg', outputIndex: 1, outputCount: 1}]};
    const first = c.queueProgressSnapshot(q);
    assert.equal(bars().length, 2);
    assert.equal(bars()[1]['aria-valuenow'], '50');
    assert.match(bars()[1]['aria-label'], /image-download/);
    const before = first.overallProgress;
    assert.ok(before > 0 && before < 100);
    q.imageProgress = {phase: 'processing', processing: [
        {imageNumber: 1, phase: 'verifying', outputIndex: 1, outputCount: 1},
        {imageNumber: 2, phase: 'ffmpeg-waiting'}]};
    assert.match(bars()[1]['aria-label'], /verifying/);
    assert.equal(bars()[1]['aria-valuenow'], undefined);
    const during = c.queueProgressSnapshot(q).overallProgress;
    q.imageProgress = null;
    assert.equal(c.queueProgressSnapshot(q).overallProgress, during);
    assert.ok(during >= before);
    assert.equal(Object.hasOwn(q, 'overallProgress'), false);
    q.downloadedCount = 3;
    assert.ok(Number(bars()[0]['aria-valuenow']) < 100);
    q.status = 'completed';
    assert.deepEqual(bars().map(bar => bar['aria-valuenow']), ['100', '100']);
    const duplicate = {...q, status: 'downloading', downloadedCount: 0,
        imageProgress: {imageNumber: 1, progress: 100, processing: [{imageNumber: 1, phase: 'verifying'}]}};
    assert.equal(c.progressExtrasModel(duplicate)[0].progress, 14);
});

test('alt：重试领取时的旧快照不抬高新执行的起始综合进度', () => {
    for (const kind of ['novel', 'illust']) {
        const {q, bars} = fixture(true);
        Object.assign(q, {kind, status: 'failed', startTime: 'old', novelStage: 'saving',
            imageProgress: {phase: 'finalizing'}});
        assert.ok(Number(bars()[0]['aria-valuenow']) > 0);
        q.status = 'pending';
        assert.equal(bars()[0]['aria-valuenow'], '0');
        Object.assign(q, {status: 'downloading', startTime: 'new'});
        assert.equal(bars()[0]['aria-valuenow'], '0');
        assert.equal(bars()[0]['aria-valuenow'], '0', '同一帧多次派生仍忽略旧快照');
        assert.equal(bars()[1]['aria-valuenow'], undefined);
        Object.assign(q, {novelStage: 'text', imageProgress: null, downloadedCount: 0});
        assert.equal(bars()[0]['aria-valuenow'], '0');
    }
});

test('alt：小说综合进度涵盖正文、图片、封面、生成和保存，未知子进度不伪造百分比', () => {
    const {context: c, q, bars} = fixture(true);
    Object.assign(q, {kind: 'novel', novelStage: 'text', novelText: {done: 5, total: 10}});
    assert.equal(bars()[0]['aria-valuenow'], '10');
    assert.equal(bars()[1]['aria-valuenow'], '50');
    Object.assign(q, {novelStage: 'downloading-images', novelText: null, novelEmbedded: {done: 1, total: 2}});
    assert.equal(bars()[0]['aria-valuenow'], '30');
    Object.assign(q, {novelStage: 'downloading-cover', novelEmbedded: null, novelCover: {done: 3, total: 0}});
    assert.equal(bars()[0]['aria-valuenow'], '40');
    assert.equal(bars()[1]['aria-valuenow'], undefined);
    assert.equal(bars()[1]['aria-busy'], 'true');
    for (const format of ['txt', 'html', 'epub']) {
        Object.assign(q, {novelStage: 'writing', novelFormat: format, novelCover: null});
        assert.equal(bars()[0]['aria-valuenow'], '60');
        assert.match(bars()[1]['aria-label'], new RegExp('novel-writing.*' + format.toUpperCase()));
        assert.equal(bars()[1]['aria-valuenow'], undefined);
    }
    q.novelStage = 'saving';
    assert.equal(bars()[0]['aria-valuenow'], '80');
    q.status = 'completed';
    assert.deepEqual(bars().map(bar => bar['aria-valuenow']), ['100', '100']);
    assert.equal(bars()[1]['aria-label'], 'batch:queue.message.completed');
});

test('alt：动图切换格式不会沿用子进度，综合进度涵盖解压与所有输出', () => {
    const {q, bars} = fixture(true);
    let previous = 0;
    for (const progress of [
        {phase: 'zip', zipProgress: 50},
        {phase: 'extract', extractedFrames: 5, totalFrames: 10},
        {phase: 'ffmpeg', outputIndex: 1, ffmpegProgress: 100},
        {phase: 'finalizing', outputIndex: 1},
        {phase: 'ffmpeg-waiting', outputIndex: 2, outputFormat: 'mp4'},
        {phase: 'ffmpeg', outputIndex: 2, ffmpegProgress: 37},
        {phase: 'finalizing', outputIndex: 2}
    ]) {
        q.ugoiraProgress = {...progress, outputCount: 2};
        assert.equal(bars().length, 2);
        const overall = Number(bars()[0]['aria-valuenow']);
        assert.ok(overall >= previous && overall < 100);
        previous = overall;
        if (progress.phase === 'ffmpeg-waiting') {
            assert.equal(bars()[1]['aria-valuenow'], undefined);
            assert.equal(bars()[1]['aria-busy'], 'true');
            assert.match(bars()[1]['aria-label'], /waiting.*MP4/);
        }
        if (progress.ffmpegProgress === 37) assert.equal(bars()[1]['aria-valuenow'], '37');
    }
    q.status = 'completed';
    assert.deepEqual(bars().map(bar => bar['aria-valuenow']), ['100', '100']);
    assert.equal(bars()[1]['aria-label'], 'batch:queue.message.completed');
});

test(`classic：下载和并行转码分别显示，等待传输结束不残留字节进度`, async () => {
    const f = fixture(false);
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
    assert.ok(f.bars().every(bar => bar['aria-busy'] === 'false' && !bar['aria-valuenow']));
    f.emit({completed: true, downloadedCount: 3, imageProgress: null});
    assert.equal((await waiting).imageProgress, null);
});

test(`classic：格式等待与校验显示真实阶段，终态隐藏旧进度，文本安全`, () => {
    const f = fixture(false);
    for (const phase of ['ffmpeg-waiting', 'ffmpeg', 'verifying', 'thumbnail', 'finalizing']) {
        f.q.imageProgress = {phase, outputFormat: 'png', outputIndex: 1, outputCount: 2, progress: 100};
        assert.match(f.render(), /queue.media\./);
        assert.match(f.render(), /PNG · 1\/2/);
        assert.doesNotMatch(f.render(), /100%/);
        assert.equal(f.bars().length, 1);
        const processing = f.bars()[0];
        assert.equal(processing['aria-busy'], 'true');
        assert.equal(processing['aria-valuenow'], undefined);
        assert.match(processing['aria-label'], /queue.media\./);
    }
    f.q.imageProgress.status = 'failed';
    assert.equal(f.bars()[0]['aria-busy'], 'false');
    assert.match(f.render(), /queue.media.failed/);
    f.q.imageProgress.outputFormat = '<img src=x onerror=alert(1)>';
    f.q.imageProgress.status = 'running';
    assert.doesNotMatch(f.render(), /<IMG/i);
    f.q.status = 'completed';
    assert.equal(f.render(), '');
    assert.equal(f.bars().length, 0);
});

test(`classic：多格式 Ugoira 进入下一轮等待时不继承上一格式 100%`, () => {
    const f = fixture(false);
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
    const conversion = f.bars().find(bar => bar['aria-label'].includes('queue.media.converting'));
    assert.equal(conversion['aria-valuenow'], '37');
    assert.equal(conversion['aria-busy'], 'false');
    f.q.ugoiraProgress = {phase: 'finalizing', status: 'running'};
    const finalizing = f.bars().find(bar => bar['aria-label'].includes('queue.media.finalizing'));
    assert.equal(finalizing['aria-busy'], 'true');
    assert.equal(finalizing['aria-valuenow'], undefined);
    f.q.ugoiraProgress = {phase: 'zip', status: 'failed'};
    assert.equal(f.bars().every(bar => bar['aria-busy'] === 'false'), true);
});


for (const alt of [false, true]) {
    const layout = alt ? 'alt' : 'classic';
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
