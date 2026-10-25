'use strict';

const assert = require('node:assert/strict');
const {test} = require('node:test');
const {readFileSync} = require('node:fs');
const {resolve} = require('node:path');
const vm = require('node:vm');

const root = resolve(__dirname, '../../main/resources/static');

for (const alt of [false, true]) {
    test(`${alt ? '新版' : '经典'}工作台按页面顺序加载后保留格式门面和边界输出`, () => {
        const page = readFileSync(resolve(root, alt ? 'pixiv-batch-alt.html' : 'pixiv-batch.html'), 'utf8');
        const core = alt ? '/pixiv-batch-alt/alt-core.js' : '/pixiv-batch/batch-core.js';
        const c = vm.createContext({window: {}, document: {addEventListener() {}}});
        for (const [, src] of page.matchAll(/<script\b[^>]*\bsrc="([^"]+)"[^>]*>/g)) {
            if (src === core || src === '/pixiv-batch/queue-display.js') {
                vm.runInContext(readFileSync(resolve(root, '.' + src), 'utf8'), c, {filename: src});
            }
        }
        const facade = c.window[alt ? 'PixivBatchAlt' : 'PixivBatch'].core;
        const cases = {
            formatSeconds: [[-2, '00:00'], [0, '00:00'], [59.5, '01:00'],
                ['3661', '1h 01m 01s'], [NaN, 'NaN:NaN']],
            formatBytes: [[-1, ''], [Infinity, ''], ['invalid', ''], [null, '0 B'],
                [1023, '1023 B'], [1024, '1.0 KB'], [10240, '10 KB'],
                [1048576, '1.0 MB'], [1073741824, '1.0 GB'], [1099511627776, '1024 GB']],
            formatDurationMs: [[-1, ''], [NaN, ''], [Infinity, ''], [null, '0s'],
                [499, '0s'], [500, '1s'], [59500, '1:00'], ['3661000', '61:01']]
        };
        for (const [name, values] of Object.entries(cases)) {
            assert.equal(facade[name], c[name]);
            for (const [input, expected] of values) {
                assert.equal(facade[name](input), expected, `${name}(${input})`);
            }
        }
    });
}
