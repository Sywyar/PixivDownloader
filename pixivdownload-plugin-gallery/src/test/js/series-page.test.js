'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function load() {
    const frames = [], classes = new Set(), nodes = new Map();
    const grid = {innerHTML: '', querySelectorAll: () => [], querySelector: () => grid.current};
    nodes.set('chapterGrid', grid);
    const context = vm.createContext({
        URLSearchParams, window: {}, requestAnimationFrame: fn => frames.push(fn),
        document: {body: {classList: {add: name => classes.add(name)}}, getElementById(id) {
            if (!nodes.has(id)) nodes.set(id, {innerHTML: 'old', textContent: '', style: {}});
            return nodes.get(id);
        }}
    });
    for (const file of ['series-core.js', 'series-actions.js', 'series-data.js', 'series-render.js']) {
        vm.runInContext(fs.readFileSync(path.join(__dirname,
            '../../main/resources/static/pixiv-series', file), 'utf8'), context, {filename: file});
    }
    return {context, frames, classes, nodes, grid, state: vm.runInContext('state', context)};
}

for (const type of ['artwork', 'novel']) {
    test(`系列目录 ${type} 在下一帧定位当前章节，并保留空结果语义`, () => {
        const h = load();
        Object.assign(h.state, {type, seriesId: 8, currentId: 7});
        const scrolled = [];
        h.grid.current = {scrollIntoView: options => scrolled.push(options.block)};
        h.context.renderGrid([{artworkId: 7, novelId: 7, title: '<chapter>', seriesOrder: 2}]);
        assert.match(h.grid.innerHTML, /chapter-card [^"]*current/);
        assert.match(h.grid.innerHTML, /&lt;chapter&gt;/);
        assert.equal(h.frames.length, 1);
        assert.deepEqual(scrolled, []);
        h.frames.shift()();
        assert.deepEqual(scrolled, ['nearest']);
        h.context.renderGrid([]);
        assert.match(h.grid.innerHTML, /empty-state/);
        assert.equal(h.frames.length, 0);
        h.state.currentId = null;
        h.context.renderGrid([{artworkId: 9, novelId: 9}]);
        assert.equal(h.frames.length, 0);
    });
}

test('系列目录只在管理员检查明确有效时显示管理入口', async () => {
    for (const [ok, valid, expected] of [[true, true, true], [true, false, false], [false, true, false], [true, 'true', false]]) {
        const h = load();
        const calls = [];
        h.context.fetch = async (url, options) => {
            calls.push([url, options.credentials]);
            return {ok, json: async () => ({valid})};
        };
        await h.context.setupAdminMode();
        assert.deepEqual(calls, [['/api/auth/check', 'same-origin']]);
        assert.equal(h.classes.has('admin-mode'), expected);
    }
    const h = load();
    h.context.fetch = async () => { throw new Error('offline'); };
    await h.context.setupAdminMode();
    assert.equal(h.classes.size, 0);
});

test('系列目录筛选链接保留默认视图、身份字段及特殊字符', () => {
    const {context} = load();
    assert.equal(context.buildGalleryFilterHref(), '/pixiv-gallery.html?view=all');
    assert.equal(context.buildNovelGalleryFilterHref(), '/pixiv-novel-gallery.html?view=all');
    const artwork = new URL(context.buildGalleryFilterHref({seriesId: 12, seriesTitle: 'A & B', authorId: 3, authorName: 'C/D'}), 'https://example.invalid');
    assert.deepEqual([...artwork.searchParams], [['view', 'all'], ['filterSeriesId', '12'], ['filterSeriesTitle', 'A & B'], ['filterAuthorId', '3'], ['filterAuthorName', 'C/D']]);
    const novel = new URL(context.buildNovelGalleryFilterHref({seriesId: 12, seriesTitle: 'A & B'}), 'https://example.invalid');
    assert.deepEqual([...novel.searchParams], [['view', 'all'], ['seriesId', '12'], ['seriesTitle', 'A & B']]);
});

test('系列目录时长格式保留边界和小时内秒数显示', () => {
    const {context} = load();
    for (const [input, expected] of [[0, ''], [-1, ''], [NaN, ''], [Infinity, ''], [0.9, ''], [1, '1 sec'], [61, '1 min 1 sec'], [3601, '1 h'], [3661, '1 h 1 min']]) {
        assert.equal(context.formatReadingDuration(input), expected);
    }
});

for (const entry of ['loadSeries', 'loadArtworks', 'loadNovels']) {
    test(`系列目录 ${entry} 失败时显示原因并清理旧章节与分页`, async () => {
        const h = load();
        h.state.seriesId = 8;
        h.grid.innerHTML = 'old chapters';
        h.context.api = async () => { throw new Error('unavailable'); };
        await h.context[entry]();
        assert.equal(h.nodes.get('seriesStatus').textContent, 'Load failed: unavailable');
        assert.equal(h.grid.innerHTML, '');
        assert.equal(h.nodes.get('pagination').innerHTML, '');
        if (entry === 'loadSeries') {
            assert.equal(h.nodes.get('seriesTitle').textContent, 'Unable to load series');
        }
    });
}
