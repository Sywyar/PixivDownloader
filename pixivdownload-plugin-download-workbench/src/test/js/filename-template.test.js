'use strict';

const {test} = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const {resolve, basename} = require('node:path');
const vm = require('node:vm');

const root = resolve(__dirname, '../../main/resources/static');

function page(name) {
    const context = vm.createContext({
        PixivBatch: {state: {}}, PixivBatchAlt: {state: {}, settings: {}}
    });
    context.window = context;
    const modules = new Set(['batch-download-defaults.js', 'batch-state.js', 'alt-state.js',
        'filename-template.js', 'batch-download-quota.js', 'batch-download-artwork.js', 'alt-settings.js']);
    const html = readFileSync(resolve(root, name + '.html'), 'utf8');
    for (const [, src] of html.matchAll(/<script\b[^>]*\bsrc="([^"]+)"[^>]*>/g)) {
        if (modules.has(basename(src))) {
            vm.runInContext(readFileSync(resolve(root, '.' + src), 'utf8'), context, {filename: src});
        }
    }
    return context;
}

for (const name of ['pixiv-batch', 'pixiv-batch-alt']) {
    test(name + ' 按页面加载顺序提供文件名预览与下载使用的函数', () => {
        const c = page(name);
        const vars = {artworkId: '42', title: '标题:/ .', authorId: '7', authorName: 'CON',
            timestamp: '123', isAi: true, xRestrict: 2};
        const defaultTemplate = vm.runInContext('DEFAULT_FILE_NAME_TEMPLATE', c);
        assert.equal(c.normalizeFileNameTemplate(null), defaultTemplate);
        assert.equal(c.normalizeFileNameTemplate('  {page} '), '  {page} ');
        assert.deepEqual(Array.from(c.buildDownloadFileNames(' ', vars, 2)),
            Array.from(c.buildDownloadFileNames(defaultTemplate, vars, 2)));
        assert.deepEqual(Array.from(c.buildDownloadFileNames('{artwork_id}_p{page}', vars, 2)), ['42_p0', '42_p1']);
        assert.equal(c.formatFileNameBase(
            '{author_id}_{author_name}_{artwork_title}_{timestamp}_{page}_{count}_{ai+}_{R18+}', vars, 0, 2),
            '7__CON_标题___123_0_2_AI_R18G');
        assert.equal(c.formatFileNameBase('{ai+}_{R18+}_{unknown}', {artworkId: '42'}, 0, 1),
            'Human_SFW_{unknown}');
        if (name === 'pixiv-batch-alt') {
            assert.equal(c.PixivBatchAlt.settings.buildDownloadFileNames, c.buildDownloadFileNames);
        }
    });

    test(name + ' 保留 Windows 保留名清洗、截断与不区分大小写的碰撞处理', () => {
        const c = page(name);
        assert.equal(c.sanitizeFileNamePart('nul. '), '_nul');
        assert.equal(c.sanitizeFileNamePart('a\u0000b/c'), 'a_b_c');
        assert.deepEqual(Array.from(c.ensureUniqueBaseNames(['same', 'same_p2', 'SAME', 'same'])),
            ['same', 'same_p2', 'SAME_p2_2', 'same_p3']);
        const names = Array.from(c.buildDownloadFileNames('x'.repeat(200), {artworkId: '42'}, 3));
        assert.deepEqual(names, ['x'.repeat(180), 'x'.repeat(177) + '_p1', 'x'.repeat(177) + '_p2']);
        assert.deepEqual(Array.from(c.buildDownloadFileNames('{artwork_title}', {artworkId: '42'}, 0)), ['42_p0']);
    });
}
