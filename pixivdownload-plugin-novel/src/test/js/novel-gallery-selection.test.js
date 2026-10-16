'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function load() {
    const nodes = new Map();
    const context = vm.createContext({
        document: {getElementById(id) {
            if (!nodes.has(id)) nodes.set(id, {innerHTML: '', querySelectorAll: () => []});
            return nodes.get(id);
        }},
        pageI18n: {t: (_key, fallback) => fallback}, esc: text => String(text)
    });
    for (const file of ['novel-gallery-state.js', 'novel-gallery-filters.js']) {
        vm.runInContext(fs.readFileSync(path.join(__dirname,
            '../../main/resources/static/pixiv-novel-gallery', file), 'utf8'), context, {filename: file});
    }
    return {context, nodes, state: vm.runInContext('state', context)};
}

test('小说画廊保存选中项时规范化数字身份并保留模式和插入顺序', () => {
    const h = load();
    h.state.selectedTags.set('2', 'not').set(1, 'must');
    h.state.selectedSeries.set('4', 'must');
    h.state.selectedAuthors.set('7', 'or');
    const snapshot = h.context.serializeNovelGalleryState();
    const saved = JSON.parse(JSON.stringify(snapshot));
    assert.deepEqual(saved.selectedTags, [[2, 'not'], [1, 'must']]);
    assert.deepEqual(saved.selectedSeries, [[4, 'must']]);
    assert.deepEqual(saved.selectedAuthors, [[7, 'or']]);
    assert.deepEqual(JSON.parse(JSON.stringify([...h.state.selectedTags])), [['2', 'not'], [1, 'must']]);
    snapshot.selectedTags[0][1] = 'or';
    assert.equal(h.state.selectedTags.get('2'), 'not');
});

for (const [kind, selected, render, nodeId, idField] of [
    ['tags', 'selectedTags', 'renderTagChips', 'filterTagChips', 'tagId'],
    ['authors', 'selectedAuthors', 'renderAuthorChips', 'filterAuthorChips', 'authorId']
]) {
    test(`小说画廊 ${kind} 补齐未加载选中项并按模式及选择顺序展示`, () => {
        const h = load();
        const items = [9, 2, 7, 6].map(id => ({[idField]: id, name: `name-${id}`, novelCount: id}));
        h.state[kind] = items;
        for (const [id, mode] of [[3, 'must'], [7, 'must'], [2, 'not'], [8, 'or']]) {
            h.state[selected].set(id, mode);
        }
        h.context[render]();
        const html = h.nodes.get(nodeId).innerHTML;
        const attr = idField === 'tagId' ? 'tag-id' : 'author-id';
        assert.deepEqual([...html.matchAll(new RegExp(`data-${attr}="(\\d+)"`, 'g'))].map(match => Number(match[1])),
            [3, 7, 2, 8, 9, 6]);
        assert.match(html, />#3<span class="chip-count">0<\/span>/);
        assert.match(html, /mode-not/);
        assert.match(html, /mode-or/);
        assert.deepEqual(items.map(item => item[idField]), [9, 2, 7, 6]);
        h.state[selected].clear();
        h.state[kind] = [];
        h.context[render]();
        assert.match(h.nodes.get(nodeId).innerHTML, /chip-empty/);
    });
}
