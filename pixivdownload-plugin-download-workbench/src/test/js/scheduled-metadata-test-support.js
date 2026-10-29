'use strict';

const {readFileSync} = require('node:fs');
const {resolve} = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const {harness, manifest, typeDescriptor} = require('./batch-queue-types-fixture');

async function metadataRuntime() {
    const h = harness([manifest(1, [typeDescriptor({type: 'illust'})])], {
        '/modules/demo.js': {source: readFileSync(resolve(__dirname,
            '../../main/resources/static/pixiv-batch/pixiv-queue-type.js'), 'utf8')}
    });
    Object.assign(h.sandbox, {
        processIllustItem() {}, renderPixivSearchResults() {}, renderQuickIllustGrid() {}, pixivQuickInnerCard() {},
        QUICK_PAGE_SIZE_ILLUST: 60, QUICK_PAGE_SIZE_NOVEL: 24,
        SINGLE_IMPORT_MODE: 'single-import', SINGLE_IMPORT_NOVEL_SOURCE: 'single-import-novel',
        bt: (_key, fallback) => fallback
    });
    await h.qt.bootstrap();
    assert.ok(h.qt.get('illust'), 'load the production Pixiv descriptor');
    const parse = vm.runInContext('JSON.parse', h.sandbox);
    const runtime = {...h.qt};
    for (const name of ['scheduledQueueItem', 'queueTags']) {
        runtime[name] = (...args) => h.qt[name](...parse(JSON.stringify(args)));
    }
    return runtime;
}

module.exports = {metadataRuntime};
