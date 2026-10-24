'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const root = path.resolve(__dirname, '../../../..');
const sources = ['scripts/shared/pixiv-metadata.js',
    'Pixiv 单作品图片下载器(Java后端版).user.js',
    'Pixiv URL 批量导入单作品下载器(URL Batch).user.js',
    'Pixiv User 批量下载器(User Batch).user.js',
    'Pixiv 页面批量下载器(Page Scrape).user.js'];
const plain = value => JSON.parse(JSON.stringify(value));

for (const file of sources) {
    let source = fs.readFileSync(path.join(root, file), 'utf8');
    if (file.endsWith('.user.js')) {
        source = source.split('// >>> SHARED:pixiv-metadata.js')[1]
            .split('// <<< SHARED:pixiv-metadata.js')[0];
    }
    const context = vm.createContext({URL});
    vm.runInContext(source, context);

    test(`${file}：小说正文、系列与图片元数据按既有字段传递`, () => {
        const body = {title: '小说', xRestrict: 1, aiType: 2, bookmarkCount: 0,
            userId: ' 23 ', userName: '作者', description: '<p>介绍</p>',
            tags: {tags: [{tag: '猫', translation: {en: 'cat'}}, 'story', {}]},
            seriesNavData: {seriesId: '45', order: '2', title: '系列'},
            content: '正文[newpage]后文[newpage]', wordCount: '5', characterCount: '7',
            readingTime: '12 sec', isOriginal: true, language: 'ja',
            imageUrls: {original: 'https://i.pximg.net/cover.jpg', large: 'unused'},
            uploadDate: '2020-01-02T00:00:00Z',
            textEmbeddedImages: {
                image: {urls: {original: 'https://i.pximg.net/inside.jpg'}},
                fake: {urls: {original: 'https://i.pximg.net.evil.example/inside.jpg'}},
                malformed: {urls: {original: '/relative'}}, empty: {}
            }};
        const before = structuredClone(body);
        assert.deepEqual(plain(context.buildNovelMetaFromPixivBody('123', body)), {
            novelId: 123, title: '小说', xRestrict: 1, isAi: true, bookmarkCount: 0,
            authorId: 23, authorName: '作者', description: '<p>介绍</p>',
            tags: [{name: '猫', translatedName: 'cat'}, {name: 'story', translatedName: null}],
            seriesId: 45, seriesOrder: 2, seriesTitle: '系列',
            content: '正文[newpage]后文[newpage]', wordCount: 5, textLength: 7,
            readingTimeSeconds: 12, pageCount: 3, isOriginal: true, language: 'ja',
            coverUrl: 'https://i.pximg.net/cover.jpg', uploadTimestamp: 1577923200000,
            textEmbeddedImages: {image: 'https://i.pximg.net/inside.jpg'}
        });
        assert.deepEqual(body, before);
    });

    test(`${file}：缺失元数据、替代封面字段与无效输入保持回退`, () => {
        const empty = plain(context.buildNovelMetaFromPixivBody('123', null));
        assert.equal(empty.title, '');
        assert.equal(empty.authorId, null);
        assert.equal(empty.bookmarkCount, -1);
        assert.equal(empty.pageCount, 1);
        assert.equal(empty.seriesId, null);
        assert.equal(empty.readingTimeSeconds, null);
        assert.deepEqual(empty.tags, []);
        assert.deepEqual(empty.textEmbeddedImages, {});
        assert.equal(context._pn_extractReadingTimeSeconds({readingTimeSeconds: 4.9}), 4);
        assert.equal(context._pn_extractReadingTimeSeconds({readingTime: 'unknown', readTime: '8'}), 8);
        assert.equal(context._pn_extractCoverUrl({urls: {regular: 'regular'}, url: 'fallback'}), 'regular');
        assert.equal(context._pn_extractSeriesCoverUrl({cover: {urls: {'720x720': 'cover'}}, coverImage: 'fallback'}), 'cover');
        assert.equal(context._pn_extractUploadTimestamp({uploadDate: 'invalid', createDate: '2020-01-02T00:00:00Z'}), 1577923200000);
        for (const value of [null, '', 0, -1, 'bad', Infinity]) {
            assert.equal(context._pn_parsePositiveLong(value), null);
        }
    });

    test(`${file}：转发移除无关大字段并保留原对象，序列化失败不阻断下载`, () => {
        const input = {title: 'keep', tags: ['tag'], userIllusts: {many: true},
            zoneConfig: {ads: true}, extraData: ['remove'], custom: {retained: true}};
        const before = structuredClone(input);
        assert.deepEqual(JSON.parse(context.buildForwardMetaJson(input)), {
            title: 'keep', tags: ['tag'], custom: {retained: true}
        });
        assert.deepEqual(input, before);
        const cyclic = {};
        cyclic.self = cyclic;
        assert.equal(context.buildForwardMetaJson(cyclic), null);
        assert.equal(context.buildForwardMetaJson(null), null);
        assert.equal(context.buildForwardMetaJson('invalid'), null);
    });
}
