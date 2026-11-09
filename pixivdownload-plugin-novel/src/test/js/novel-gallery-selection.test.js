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
    for (const file of ['novel-gallery-core.js', 'novel-gallery-state.js', 'novel-gallery-filters.js', 'novel-gallery-dates.js', 'novel-gallery-batch.js']) {
        vm.runInContext(fs.readFileSync(path.join(__dirname,
            '../../main/resources/static/pixiv-novel-gallery', file), 'utf8'), context, {filename: file});
    }
    vm.runInContext("pageI18n = {t: (_key, fallback) => fallback};", context);
    return {context, nodes, state: vm.runInContext('state', context)};
}

test('下载时间排序不在小说卡片显示日期，其他日期和字数信息仍可显示', () => {
    const h = load();
    vm.runInContext(fs.readFileSync(path.join(__dirname,
        '../../main/resources/static/pixiv-novel-gallery/novel-gallery-views.js'), 'utf8'), h.context);
    vm.runInContext('pageI18n = {t: key => key};', h.context);
    h.context.loadMembershipsForNovels = () => {};
    const item = {novelId: 1, title: 'Title', authorName: 'Author', uploadTime: 1000, wordCount: 12};
    h.state.sort = 'date';
    for (const time of [2000, null]) {
        h.context.renderGrid([{...item, time}]);
        const html = h.nodes.get('grid').innerHTML;
        assert.doesNotMatch(html, /work.download-time|work.upload-time/);
        assert.match(html, /meta.word-count/);
    }
    h.state.sort = 'uploadTime';
    h.context.renderGrid([item]);
    assert.match(h.nodes.get('grid').innerHTML, /work.upload-time/);
});

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

test('小说画廊各筛选输入独立防抖，同一输入只执行最后一次', t => {
    t.mock.timers.enable({apis: ['setTimeout']});
    const context = vm.createContext({setTimeout, clearTimeout});
    vm.runInContext(fs.readFileSync(path.join(__dirname,
        '../../main/resources/static/pixiv-novel-gallery/novel-gallery-core.js'), 'utf8'), context);
    const rendered = [];
    const tags = context.debounce(value => rendered.push(['tags', value]), 200);
    const series = context.debounce(value => rendered.push(['series', value]), 200);
    tags('old');
    tags('latest');
    series('other');
    t.mock.timers.tick(199);
    assert.deepEqual(rendered, []);
    t.mock.timers.tick(1);
    assert.deepEqual(rendered, [['tags', 'latest'], ['series', 'other']]);
    t.mock.timers.tick(1000);
    assert.equal(rendered.length, 2);
});


test('小说画廊交接、保存和批量快照同时保留多维日期', () => {
    const h = load();
    const dateFilters = [
        {field: 'date', start: '2021-05-01', end: '2021-05-02'},
        {field: 'uploadTime', start: '2020-02-03', end: '2020-02-03'},
        {field: 'createTime', start: '2019-01-01', end: ''},
        {field: 'updateTime', start: '', end: '2022-01-01'}
    ];
    h.context.applyCrossTransferToNovelGallery({dateFilters, sort: 'date', order: 'asc'});
    const saved = h.context.serializeNovelGalleryState();
    assert.deepEqual(JSON.parse(JSON.stringify(saved.dateFilters)), dateFilters);
    h.context.restoreTimeRanges(saved);
    const snapshot = h.context.currentBatchFilterSnapshot();
    assert.equal(snapshot.downloadedFrom, new Date('2021-05-01T00:00:00').getTime());
    assert.equal(snapshot.downloadedBefore, new Date('2021-05-03T00:00:00').getTime());
    assert.equal(snapshot.uploadedBefore, new Date('2020-02-04T00:00:00').getTime());
    assert.equal(snapshot.createdFrom, new Date('2019-01-01T00:00:00').getTime());
    assert.equal(snapshot.updatedBefore, new Date('2022-01-02T00:00:00').getTime());
    assert.equal(snapshot.sort, 'date'); assert.equal(snapshot.order, 'asc');
    h.context.restoreTimeRanges({dateFilters: [{field: 'reuploadTime', end: '2021-05-02'}]});
    assert.deepEqual(Object.keys(h.context.galleryTimeRanges()), ['reuploadedBefore']);
});

test('小说画廊恢复旧日期偏好，无效类别不参与查询', () => {
    const h = load();
    h.context.restoreTimeRanges({downloadStartDate: '2021-05-02'});
    assert.deepEqual(Object.keys(h.context.galleryTimeRanges()), ['downloadedFrom']);
    h.context.restoreTimeRanges({dateFilters: [{field: 'bad', start: '2020-01-01'}]});
    assert.deepEqual(Object.keys(h.context.galleryTimeRanges()), []);
});
