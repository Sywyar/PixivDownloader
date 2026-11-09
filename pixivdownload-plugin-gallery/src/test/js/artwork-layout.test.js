'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

for (const [name, createTime, uploadTime, expected] of [
    ['创建时间优先', 1000, 2000, 1000],
    ['旧记录回退上传时间', null, 2000, 2000],
    ['没有发布时间则省略', null, null, null],
]) {
    test('作品详情只保留发布时间：' + name, () => {
        const nodes = new Map(), messages = [];
        const sandbox = {
            window: {PixivArtwork: {}},
            state: {artwork: {artworkId: 1, createTime, uploadTime, time: 3000, updateTime: 4000, reuploadTime: 5000, xRestrict: 1}},
            document: {addEventListener() {}, getElementById(id) {
                if (!nodes.has(id)) nodes.set(id, {style: {}});
                return nodes.get(id);
            }},
            wt(key, fallback, args) { messages.push({key, args}); return key + (args?.time || ''); },
            escapeHtml: String, formatTime: value => 'time:' + value,
            buildPixivArtworkHref: () => '', buildShowcaseHref: () => '',
        };
        vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../../main/resources/static/pixiv-artwork/artwork-viewer.js'), 'utf8'), sandbox);
        sandbox.renderDetail();
        const stats = messages.filter(message => message.key.startsWith('stats.'));
        assert.deepEqual(stats.map(message => message.key), expected ? ['stats.id', 'stats.published'] : ['stats.id']);
        if (expected) assert.equal(stats[1].args.time, 'time:' + expected);
        assert.match(nodes.get('artworkStats').innerHTML, /R-18/);
    });
}

test('作品内容组使用主图自然尺寸，附页和失败图片不改变布局', () => {
    const properties = new Map();
    let loaded;
    const document = {
        getElementById(id) {
            return id === 'viewer'
                ? {addEventListener(type, callback, capture) {
                    assert.equal(type, 'load');
                    assert.equal(capture, true);
                    loaded = callback;
                }}
                : {style: {setProperty: (key, value) => properties.set(key, value)}};
        }
    };
    vm.runInNewContext(fs.readFileSync(path.join(
        __dirname, '../../main/resources/static/pixiv-artwork/artwork-layout.js'
    ), 'utf8'), {document});

    const image = {tagName: 'IMG', parentElement: {id: 'mainImage'}, naturalWidth: 240, naturalHeight: 160};
    loaded({target: image});
    assert.equal(properties.get('--artwork-natural-width'), '240px');
    assert.equal(properties.get('--artwork-ratio'), 1.5);
    loaded({target: {...image, parentElement: {id: 'morePages'}, naturalWidth: 4000}});
    loaded({target: {...image, naturalHeight: 0}});
    assert.equal(properties.get('--artwork-natural-width'), '240px');
    assert.equal(properties.get('--artwork-ratio'), 1.5);

    loaded({target: {...image, naturalWidth: 2000, naturalHeight: 4000}});
    assert.equal(properties.get('--artwork-natural-width'), '2000px');
    assert.equal(properties.get('--artwork-ratio'), .5);
});
