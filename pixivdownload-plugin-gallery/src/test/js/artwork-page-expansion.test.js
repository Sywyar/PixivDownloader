'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const viewer = fs.readFileSync(path.join(
    __dirname, '..', '..', 'main', 'resources', 'static', 'pixiv-artwork', 'artwork-viewer.js'
), 'utf8');

test('未加载完成时连续翻页只展开一组作品页，收起后可以重新展开', async () => {
    const nodes = new Map();
    const pending = [];
    function element() {
        const classes = new Set();
        return {
            style: {}, children: [],
            classList: {add: name => classes.add(name), remove: name => classes.delete(name)},
            appendChild(child) { this.children.push(child); },
            addEventListener() {},
            set innerHTML(value) { this.children = []; },
            get innerHTML() { return ''; }
        };
    }
    const state = {
        artwork: {count: 8}, artworkId: 123,
        lightboxImages: ['page-0', ...Array(7).fill(null)], lightboxIndex: 0
    };
    const sandbox = {
        window: {PixivArtwork: {viewer: {}}}, state,
        document: {
            getElementById(id) {
                if (!nodes.has(id)) nodes.set(id, element());
                return nodes.get(id);
            },
            createElement: element, addEventListener() {}
        },
        loadImageToElement(url) {
            return new Promise(resolve => pending.push({url, resolve}));
        },
        wt: (_, fallback) => fallback,
        syncExpandButtonText() {}, toast() {}
    };
    vm.createContext(sandbox);
    vm.runInContext(viewer, sandbox);

    sandbox.openLightbox(0);
    for (let i = 0; i < 3; i++) sandbox.lightboxNav(1);
    assert.equal(nodes.get('morePages').children.length, 7);
    assert.equal(pending.length, 7);

    pending.splice(0).forEach(({url, resolve}) => resolve(url));
    await new Promise(setImmediate);
    assert.equal(state.lightboxIndex, 1);

    sandbox.collapseAll();
    assert.equal(nodes.get('morePages').children.length, 0);
    const reexpanded = sandbox.expandAll(8);
    assert.equal(nodes.get('morePages').children.length, 7);
    pending.splice(0).forEach(({url, resolve}) => resolve(url));
    await reexpanded;
});
