import {readFileSync, writeFileSync} from 'node:fs';
import assert from 'node:assert/strict';

const root = new URL('../../', import.meta.url);
const defaults = JSON.parse(readFileSync(new URL('pixiv-defaults.json', import.meta.url), 'utf8'));
const constant = key => key.replace(/[A-Z]/g, value => '_' + value).toUpperCase();
const fields = Object.entries(defaults).flatMap(([group, value]) =>
    value && typeof value === 'object'
        ? Object.entries(value).map(([key, item]) => [constant(group) + '_' + constant(key), item])
        : [[constant(group), value]]);
const declarations = fields.filter(([, value]) => value !== null && !Array.isArray(value)).map(([name, value]) => {
    assert(['string', 'number', 'boolean'].includes(typeof value), name);
    const type = typeof value === 'string' ? 'String' : typeof value === 'boolean' ? 'boolean' : 'int';
    return '    public static final ' + type + ' ' + name + ' = ' + JSON.stringify(value) + ';';
}).join('\n');
const java = pkg => `// 由 scripts/schedule/generate-pixiv-defaults.mjs 生成。
package top.sywyar.pixivdownload.${pkg};

/** Pixiv 计划定义的缺省值；修改生成器旁的 JSON 后重新生成。 */
public final class PixivScheduleDefaults {
${declarations}

    private PixivScheduleDefaults() {}
}
`;
const js = `// 由 scripts/schedule/generate-pixiv-defaults.mjs 生成。
'use strict';
window.PixivBatch = window.PixivBatch || {};
window.PixivBatch.pixivScheduleDefaults = Object.freeze(${JSON.stringify(defaults, null, 4)});
`;
const outputs = [
    ['pixivdownload-plugin-download-workbench/src/main/resources/static/pixiv-batch/pixiv-schedule-defaults.js', js],
    ['pixivdownload-plugin-download-workbench/src/main/java/top/sywyar/pixivdownload/download/schedule/snapshot/PixivScheduleDefaults.java', java('download.schedule.snapshot')],
    ['pixivdownload-plugin-novel/src/main/java/top/sywyar/pixivdownload/novel/schedule/PixivScheduleDefaults.java', java('novel.schedule')]
];
for (const [path, content] of outputs) {
    const url = new URL(path, root);
    if (process.argv.includes('--check')) assert.equal(readFileSync(url, 'utf8'), content, path + ' is stale');
    else writeFileSync(url, content, 'utf8');
}
