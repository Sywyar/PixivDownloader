'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

test('收藏夹图标替换、清空、关闭和释放后恢复均只保留当前预览', () => {
    const nodes = new Map(), active = new Set();
    let nextUrl = 0;
    const document = {getElementById(id) {
        if (!nodes.has(id)) {
            const classes = new Set();
            nodes.set(id, {style: {}, value: '', checked: false, innerHTML: '',
                classList: {add: key => classes.add(key), remove: key => classes.delete(key), contains: key => classes.has(key), toggle() {}},
                replaceChildren() { this.innerHTML = ''; }});
        }
        return nodes.get(id);
    }};
    const sandbox = {
        document, state: {pendingIconFile: null, pendingIconClear: false}, HEART_SVG: '<svg></svg>',
        window: {PixivGallery: {}},
        URL: {
            createObjectURL(file) { assert.ok(file); const url = 'blob:fixture-' + (++nextUrl); active.add(url); return url; },
            revokeObjectURL(url) { assert.ok(active.delete(url), 'An active preview URL is revoked exactly once'); }
        }
    };
    vm.createContext(sandbox);
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../../main/resources/static/pixiv-gallery/gallery-collections.js'), 'utf8'), sandbox);
    const preview = document.getElementById('collectionFormIconPreview');
    for (let i = 0; i < 10; i++) {
        const file = {name: 'icon-' + i};
        sandbox.state.pendingIconFile = file;
        sandbox.updateFormIconPreview(null);
        assert.equal(active.size, 1);
        assert.equal(sandbox.state.pendingIconFile, file);
        assert.ok(preview.innerHTML.includes([...active][0]));
    }
    sandbox.state.pendingIconFile = null; sandbox.state.pendingIconClear = true;
    sandbox.updateFormIconPreview({id: 1, iconExt: 'png'});
    assert.equal(active.size, 0); assert.equal(preview.innerHTML, '<svg></svg>');
    sandbox.state.pendingIconFile = {name: 'restored-icon'};
    document.getElementById('modalCollectionForm').classList.add('open');
    sandbox.updateFormIconPreview(null);
    sandbox.releaseCollectionIconPreview();
    assert.equal(active.size, 0); assert.equal(preview.innerHTML, '');
    sandbox.updateFormIconPreview(null);
    assert.equal(active.size, 1);
    assert.equal(sandbox.state.pendingIconFile.name, 'restored-icon');
    sandbox.closeCollectionFormModal();
    assert.equal(active.size, 0); assert.equal(sandbox.state.pendingIconFile, null);
    assert.equal(preview.innerHTML, '<svg></svg>');
    sandbox.closeCollectionFormModal(); sandbox.releaseCollectionIconPreview();
    assert.equal(active.size, 0);
});
