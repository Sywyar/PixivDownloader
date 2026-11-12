'use strict';

const {test} = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const {resolve, basename} = require('node:path');
const vm = require('node:vm');

const root = resolve(__dirname, '../../main/resources/static');

function page(name) {
    const context = vm.createContext({
        addEventListener() {}, PixivBatch: {state: {}}, PixivBatchAlt: {state: {}, settings: {}}
    });
    context.window = context;
    const modules = new Set(['batch-download-defaults.js', 'batch-state.js', 'state-storage.js', 'alt-state.js',
        'filename-template.js', 'batch-archive.js', 'batch-download-quota.js', 'batch-artwork-requests.js', 'batch-download-artwork.js', 'alt-settings.js']);
    const html = readFileSync(resolve(root, name + '.html'), 'utf8');
    for (const [, src] of html.matchAll(/<script\b[^>]*\bsrc="([^"]+)"[^>]*>/g)) {
        if (modules.has(basename(src))) {
            vm.runInContext(readFileSync(resolve(root, '.' + src), 'utf8'), context, {filename: src});
        }
    }
    return context;
}

for (const name of ['pixiv-batch', 'pixiv-batch-alt']) {
    test(name + ' 下载请求携带收藏夹对应的实际基础名称，不提交收藏夹显示名称', async () => {
        const c = page(name);
        if (name === 'pixiv-batch-alt') {
            vm.runInContext(readFileSync(resolve(root, 'pixiv-batch-alt/alt-engine.js'), 'utf8'), c);
        }
        let submitted;
        Object.assign(c, {
            BASE: '',
            getImageDelayMs: () => 0, getCookie: () => '', normalizeAuthorId: value => Number(value),
            refreshBatchCollections: async () => ({collectionId: 7, collections: [{id: 7, name: '收藏/one'}]}),
            PixivMediaSettings: {snapshot: () => ({})},
            submitWithPathAction: async (_url, payload) => { submitted = payload; return {res: {ok: true}, data: {}}; }
        });
        vm.runInContext("state.settings.fileNameTemplate = '{collection_name}_{collection_id}_{artwork_id}_p{page}'; state.settings.collectionId = 7", c);
        if (name === 'pixiv-batch-alt') vm.runInContext("batchCollections = [{id: 7, name: '收藏/one'}]", c);
        await c.sendDownload(42, ['https://example.test/0.png', 'https://example.test/1.png'], 'Title',
            false, '', 9, 'Author', 0, false);
        assert.equal(submitted.other.collectionId, 7);
        assert.deepEqual(Array.from(submitted.other.fileNames), ['收藏_one_7_42_p0', '收藏_one_7_42_p1']);
        assert.equal(Object.hasOwn(submitted.other, 'collectionName'), false);
    });

    test(name + ' 按页面加载顺序提供文件名预览与下载使用的函数', () => {
        const c = page(name);
        const vars = {artworkId: '42', title: '标题:/ .', authorId: '7', authorName: 'CON',
            timestamp: '123', isAi: true, xRestrict: 2, collectionId: 9, collectionName: '收藏😀/unsafe'};
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
        assert.equal(c.formatFileNameBase('{extension}_{name}_{ex}_{artworkId}', vars, 0, 1),
            '{extension}_{name}_{ex}_{artworkId}');
        const definitions = vm.runInContext('DOWNLOAD_FILE_VARIABLES', c);
        const expected = {artwork_id: '42', artwork_title: '标题__', author_id: '7', author_name: '_CON',
            timestamp: '123', page: '0', count: '2', ai: 'AI', 'ai+': 'AI', R18: 'R18G', 'R18+': 'R18G',
            collection_id: '9', collection_name: '收藏😀_unsafe'};
        for (const variable of definitions) {
            assert.equal(c.formatFileNameBase('{' + variable.token + '}', vars, 0, 2), expected[variable.token]);
        }
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

    test(name + ' 收藏夹变量只取当前选中项，未选择或已删除时为空', () => {
        const c = page(name);
        const template = '{collection_name}_{collection_id}_{artwork_id}';
        const collections = [{id: 9, name: '收藏😀/unsafe'}];
        for (const id of [null, '', 10]) {
            const vars = {artworkId: 42, ...c.collectionFileNameVariables(id, collections)};
            assert.equal(c.formatFileNameBase(template, vars, 0, 1), '__42');
        }
        const vars = {artworkId: 42, ...c.collectionFileNameVariables('9', collections)};
        assert.equal(c.formatFileNameBase(template, vars, 0, 1), '收藏😀_unsafe_9_42');
    });
}
