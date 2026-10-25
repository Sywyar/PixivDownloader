'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

function harness() {
    const nodes = new Map(), calls = [], active = new Set();
    let serial = 0;
    const node = id => {
        if (!nodes.has(id)) {
            const classes = new Set();
            nodes.set(id, {value: '', checked: false, disabled: false, innerHTML: '', style: {},
                classList: {add: c => classes.add(c), remove: c => classes.delete(c), contains: c => classes.has(c), toggle() {}},
                replaceChildren() { this.innerHTML = ''; }});
        }
        return nodes.get(id);
    };
    const runtime = vm.createContext({
        document: {getElementById: node}, state: {}, pageI18n: {t: (key, fallback) => fallback},
        HEART_SVG: '<svg></svg>', FormData, setTimeout() {}, toast() {},
        URL: {createObjectURL() { const url = 'blob:' + (++serial); active.add(url); return url; },
            revokeObjectURL(url) { assert.ok(active.delete(url)); }},
        fetch: (url, options) => new Promise(resolve => calls.push({url, options, resolve})),
    });
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../../main/resources/static/pixiv-novel-gallery/novel-gallery-collections.js'), 'utf8'), runtime);
    runtime.loadCollections = async () => {};
    const respond = index => calls[index].resolve({ok: true, headers: {get: () => 'application/json'}, json: async () => ({})});
    return {runtime, node, calls, respond, active};
}

test('小说收藏夹保存固定原对象与图标，完成时不关闭新表单', async () => {
    const h = harness();
    h.runtime.openCollectionFormModal({id: 1, name: 'First'});
    h.node('collectionFormName').value = 'Saved first';
    h.node('collectionDownloadRootEnabled').checked = true;
    h.node('collectionDownloadRoot').value = '/first';
    const file = new File(['first'], 'first.png');
    h.runtime.state.pendingIconFile = file;
    const saving = h.runtime.submitCollectionForm();
    const duplicate = h.runtime.submitCollectionForm();
    assert.equal(h.calls.length, 1);
    await duplicate;
    h.runtime.closeCollectionFormModal();
    h.runtime.openCollectionFormModal({id: 2, name: 'Second'});
    h.runtime.state.pendingIconFile = new File(['second'], 'second.png');
    h.respond(0); await new Promise(setImmediate);
    assert.equal(h.calls[1].url, '/api/collections/1/download-root');
    assert.equal(JSON.parse(h.calls[1].options.body).downloadRoot, '/first');
    h.respond(1); await new Promise(setImmediate);
    assert.equal(h.calls[2].url, '/api/collections/1/icon');
    assert.equal(h.calls[2].options.body.get('file'), file);
    h.respond(2); await saving;
    assert.equal(h.runtime.state.editingCollection.id, 2);
    assert.equal(h.node('modalCollectionForm').classList.contains('open'), true);
    assert.equal(h.node('collectionFormSubmit').disabled, false);
});

test('小说收藏夹预览替换及关闭撤销URL，临时释放保留文件草稿供恢复', () => {
    const h = harness();
    h.runtime.openCollectionFormModal(null);
    for (let i = 0; i < 10; i++) {
        h.runtime.state.pendingIconFile = {name: 'file' + i};
        h.runtime.updateFormIconPreview(null);
        assert.equal(h.active.size, 1);
    }
    const file = h.runtime.state.pendingIconFile;
    h.runtime.releaseCollectionIconPreview();
    assert.equal(h.active.size, 0);
    assert.equal(h.runtime.state.pendingIconFile, file);
    h.runtime.updateFormIconPreview(null);
    assert.equal(h.active.size, 1);
    h.runtime.closeCollectionFormModal();
    assert.equal(h.active.size, 0);
    assert.equal(h.runtime.state.pendingIconFile, null);
});
