'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

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
