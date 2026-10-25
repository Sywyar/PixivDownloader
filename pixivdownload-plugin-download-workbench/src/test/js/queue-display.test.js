'use strict';

const {test} = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const {resolve} = require('node:path');
const vm = require('node:vm');

const root = resolve(__dirname, '../../main/resources/static');
function harness(page) {
    let language = 'en';
    const c = vm.createContext({console, window: {PixivBatchAlt: {queue: {}}},
        QUICK_FETCH_MODE: 'quick-fetch', SINGLE_IMPORT_MODE: 'single-import',
        SINGLE_IMPORT_NOVEL_SOURCE: 'single-import-novel',
        bt: (key, fallback, args) => `${language}:${key}:${JSON.stringify(args || {})}`});
    const html = readFileSync(resolve(root, page), 'utf8');
    for (const [, src] of html.matchAll(/<script\b[^>]*\bsrc="([^"]+)"/g)) {
        if (/\/(queue-display|batch-queue-model|batch-queue-view|alt-queue(?:-model)?)\.js$/.test(src)) {
            vm.runInContext(readFileSync(resolve(root, '.' + src), 'utf8'), c, {filename: src});
        }
    }
    return {c, translate: () => { language = 'translated'; }};
}

for (const page of ['pixiv-batch.html', 'pixiv-batch-alt.html']) {
    test(`${page} 当前项优先展示在途任务，暂停只隐藏等待项且计数排除当前项`, () => {
        const {c} = harness(page);
        const queue = Object.freeze(['failed', 'pending', 'downloading', 'paused', 'downloading', 'cancelled']
            .map(status => Object.freeze({status})));
        for (const paused of [false, true]) {
            const front = c.currentFrontItem(queue, paused);
            assert.equal(front, queue[2]);
            assert.deepEqual({...c.currentRemainingCounts(queue, front)}, {downloading: 1, queued: 2});
        }
        const waiting = [queue[0], queue[1], queue[3], queue[5]];
        assert.equal(c.currentFrontItem(waiting, false), queue[1]);
        assert.equal(c.currentFrontItem(waiting, true), null);
        assert.deepEqual({...c.currentRemainingCounts(waiting, queue[1])}, {downloading: 0, queued: 1});
        assert.equal(c.currentFrontItem([], false), null);
        assert.equal(c.currentRemainingLineText(0, 0), '');
        assert.equal(c.currentRemainingLineText(1, 2), 'en:status.current-remaining.both:{"downloading":1,"queued":2}');
        assert.equal(c.currentRemainingLineText(1, 0), 'en:status.current-remaining.downloading:{"count":1}');
        assert.equal(c.currentRemainingLineText(0, 2), 'en:status.current-remaining.queued:{"count":2}');
    });

    test(`${page} 取得来源保留前缀、旧导入别名和未知值回退`, () => {
        const {c} = harness(page);
        for (const [source, mode] of [[null, 'single-import'], ['ntab', 'single-import'],
            ['ntab-novel', 'single-import'], ['single-import-novel', 'single-import'],
            ['quick-fetch-following', 'quick'], [' user-bookmarks ', 'user'], ['search-tags', 'search'],
            ['series-novel', 'series'], ['schedule-id', 'schedule'], ['unknown', 'single-import']]) {
            assert.equal(c.queueAcquisitionMode(source), mode);
        }
        assert.equal(c.queueSourceText('task'), 'en:queue.source.import:{}');
        assert.equal(c.queueSourceText('ntab'), 'en:queue.source.import:{}');
        assert.equal(c.queueStatusText('cancelled'), 'cancelled');
        assert.equal(c.queueStatusText('completed'), 'en:queue.status.completed:{}');
    });

    test(`${page} 标题与计数按当前语言派生，原始模型和进度边界保持`, () => {
        const {c, translate} = harness(page);
        const item = Object.freeze({id: 'n42', kind: 'novel', title: ''});
        assert.equal(c.queueItemDisplayTitle(item), 'en:queue.novel-fallback:{"id":"42"}');
        translate();
        assert.equal(c.queueItemDisplayTitle(item), 'translated:queue.novel-fallback:{"id":"42"}');
        assert.equal(c.queueItemDisplayTitle({title: 'Original title'}), 'Original title');
        assert.equal(c.currentRemainingLineText(0, 3), 'translated:status.current-remaining.queued:{"count":3}');
        assert.equal(c.pct({totalImages: 0, downloadedCount: 2}), 0);
        assert.equal(c.pct({totalImages: 3, downloadedCount: 2}), 67);
        assert.equal(c.pct({totalImages: 1, downloadedCount: 2}), 100);
        assert.equal(item.title, '');
    });
}
