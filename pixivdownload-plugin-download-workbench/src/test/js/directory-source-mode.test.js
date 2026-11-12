'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const {resolve} = require('node:path');
const vm = require('node:vm');
const root = resolve(__dirname, '../../main/resources/static');

for (const layout of ['classic', 'alt']) {
    test(layout + ' 插画实际 worker 使用队列来源和用户名，系列元数据不会改变来源', async () => {
        const submitted = [];
        const noop = () => {};
        const c = vm.createContext({
            window: {PixivBatch: {download: {}}, PixivBatchAlt: {engine: {}}},
            state: {settings: {}, mode: 'user', username: 'current tab', userId: '999'},
            QUICK_FETCH_MODE: 'quick-fetch', SINGLE_IMPORT_MODE: 'single-import',
            STATUS_TIMEOUT_MS: 1000, quotaInfo: {enabled: false}, dockState: {quota: {enabled: false}},
            bt: key => key, console, assertProcessInvocation: value => value.assertActive(),
            getArtworkMeta: async () => ({illustTitle: 'title', authorId: 7, authorName: 'author', seriesId: 8}),
            getArtworkPages: async () => ['https://example.test/0.png'],
            normalizeAuthorId: value => Number(value),
            evaluateDownloadFilterSkip: () => null,
            waitForFinalStatusBySSE: async () => ({completed: true, downloadedCount: 1}),
            mergeUgoiraProgress: (_previous, next) => next,
            sendDownload: async (...args) => {submitted.push(args); return {};},
            handlePathActionError: () => false
        });
        for (const name of ['setCurrent', 'setStatus', 'setDockStatus', 'renderCurrent', 'updateStats',
            'saveQueue', 'renderQueue', 'openSSE', 'closeSSE', 'notifyFirstDownloadCompleted',
            'ensureSharedSSE', 'closeAllSSE', 'initQuota', 'showArchiveCard', 'triggerAdminPack', 'ensureWorkers'])
            c[name] = noop;
        for (const file of ['pixiv-batch/queue-display.js', layout === 'classic'
            ? 'pixiv-batch/batch-download-workers.js' : 'pixiv-batch-alt/alt-engine-workers.js'])
            vm.runInContext(readFileSync(resolve(root, file), 'utf8'), c, {filename: file});
        for (const source of [undefined, 'user', 'series', 'search', 'quick-fetch', 'single-import', 'schedule']) {
            const item = {id: '42', kind: 'illust', source, username: 'source reader'};
            const invocation = {assertActive() {}, isActive: () => true, signal: new AbortController().signal};
            await c.processIllustItem(item, invocation);
            assert.equal(item.status, 'completed', item.lastMessage);
            const args = submitted.at(-1);
            assert.equal(args[4], 'source reader');
            assert.equal(args[6], 'author');
            assert.equal(args[16], source === 'quick-fetch' ? 'quick' : !source || source === 'schedule' ? null : source);
            assert.equal(args[3], source === 'user');
        }
    });
}
