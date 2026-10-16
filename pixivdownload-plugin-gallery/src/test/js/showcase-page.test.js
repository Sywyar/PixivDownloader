'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function load() {
    const nodes = new Map(), thumbnails = [], messages = [], media = [];
    class Element {
        constructor() {
            this.children = []; this.events = {}; this.dataset = {}; this.classes = new Set();
            this.classList = {
                add: name => this.classes.add(name), remove: name => this.classes.delete(name),
                toggle: (name, enabled) => enabled ? this.classes.add(name) : this.classes.delete(name)
            };
        }
        set innerHTML(value) { this.html = value; this.children = []; }
        get innerHTML() { return this.html; }
        appendChild(child) { this.children.push(child); }
        addEventListener(name, callback) { this.events[name] = callback; }
        querySelector() { return this.html?.includes('<img') ? {html: this.html} : null; }
        querySelectorAll() { return this.cards || []; }
    }
    const get = id => {
        if (!nodes.has(id)) nodes.set(id, new Element());
        return nodes.get(id);
    };
    const runtime = vm.createContext({
        document: {getElementById: get, querySelectorAll: () => [], addEventListener() {}, createElement: () => new Element()},
        window: {location: {}}, IntersectionObserver: class { observe() {} },
        state: {artwork: {artworkId: 12, authorId: 34}, artworkId: 12, lightboxImages: [], lightboxIndex: 0, totalSections: 6},
        setBgImage() {},
        wt: (key, fallback, vars = {}) => key + Object.values(vars).map(value => ':' + value).join(''),
        escapeHtml: value => value,
        toast: message => messages.push(message)
    });
    vm.runInContext(fs.readFileSync(path.join(__dirname,
        '../../main/resources/static/pixiv-showcase/showcase-render.js'), 'utf8'), runtime);
    runtime.loadThumbLazy = image => thumbnails.push(image);
    runtime.setArtworkMedia = (image, url) => media.push(url);
    return {runtime, get, thumbnails, messages, media, Element};
}

test('展示页单页、多页和超出预览数量时保留页条与点击目标', async () => {
    for (const [count, shown, remaining] of [[undefined, 1, 0], [0, 1, 0], [1, 1, 0], [2, 2, 0], [8, 8, 0], [10, 8, 2], [-1, 1, 0], [2.5, 3, 0]]) {
        const h = load(), opened = [];
        h.runtime.state.artwork.count = count;
        h.runtime.loadAndOpenLightbox = index => opened.push(index);
        await h.runtime.renderPages();
        const items = h.get('pagesStrip').children;
        assert.equal(items.length, shown + (remaining ? 1 : 0));
        assert.equal(h.thumbnails.length, shown);
        items.slice(0, shown).forEach((item, index) => {
            assert.match(item.innerHTML, new RegExp('/api/downloaded/thumbnail/12/' + index + '"'));
            assert.ok(item.innerHTML.includes(`alt="${count > 1 ? 'pages.page-alt:' + (index + 1) : ''}"`));
            item.events.click();
        });
        assert.deepEqual(opened, Array.from({length: shown}, (_, index) => index));
        h.get('pagesViewAll').onclick();
        assert.equal(opened.at(-1), 0);
        if (remaining) {
            assert.equal(items.at(-1).classes.has('pages-strip-more'), true);
            assert.match(items.at(-1).innerHTML, /\+2<\/span>/);
            assert.equal(items.at(-1).events.click, undefined);
        }
        assert.equal(h.get('pagesTitle').textContent, count > 1 ? `pages.all-title:${count}` : 'pages.single-title');
    }
});

for (const [entry, grid] of [['renderArtist', 'artistWorksGrid'], ['renderRelatedGrid', 'relatedGrid']]) {
    test(`展示页 ${entry} 绑定作品跳转并仅加载存在的缩略图`, async () => {
        const h = load(), card = new h.Element(), withoutImage = new h.Element();
        card.dataset.id = '23'; card.innerHTML = '<img>';
        withoutImage.dataset.id = '24';
        h.get(grid).cards = [card, withoutImage];
        const items = [{artworkId: 23, title: 'Sample'}];
        h.runtime.api = async () => items;
        h.runtime.state.relatedItems = items;
        await h.runtime[entry]();
        assert.equal(h.thumbnails.length, 1);
        card.events.click();
        assert.equal(h.runtime.window.location.href, '/pixiv-showcase.html?id=23');
        withoutImage.events.click();
        assert.equal(h.runtime.window.location.href, '/pixiv-showcase.html?id=24');
    });
}

test('展示页放大导航循环切换，缺少图片只提示一次并保留当前位置', () => {
    const h = load();
    h.runtime.state.lightboxImages = ['/one', '/two'];
    h.runtime.lightboxNav(-1);
    assert.equal(h.runtime.state.lightboxIndex, 1);
    assert.equal(h.get('lightboxInfo').textContent, '2 / 2');
    h.runtime.lightboxNav(1);
    assert.equal(h.runtime.state.lightboxIndex, 0);
    assert.deepEqual(h.media, ['/two', '/one']);
    h.runtime.state.lightboxImages = ['/one', null];
    h.runtime.lightboxNav(1);
    assert.equal(h.runtime.state.lightboxIndex, 0);
    assert.equal(h.messages.length, 1);
    assert.equal(h.media.length, 2);
    h.runtime.state.lightboxImages = [];
    h.runtime.lightboxNav(1);
    assert.equal(h.messages.length, 2);
    assert.equal(h.media.length, 2);
});

test('展示页返回首区后恢复滚动提示', () => {
    const h = load();
    h.runtime.updateNav(2);
    assert.equal(h.get('scrollHint').classes.has('hidden'), true);
    assert.match(h.get('pageCounter').innerHTML, /03/);
    h.runtime.updateNav(0);
    assert.equal(h.get('scrollHint').classes.has('hidden'), false);
});
