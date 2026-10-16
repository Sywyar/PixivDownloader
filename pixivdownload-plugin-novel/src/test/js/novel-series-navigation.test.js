'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const test = require('node:test');

const root = path.resolve(__dirname, '../../../..');
const staticRoot = path.resolve(__dirname, '../../main/resources/static');
const page = fs.readFileSync(path.join(staticRoot, 'pixiv-novel.html'), 'utf8');
const series = fs.readFileSync(path.join(staticRoot, 'pixiv-novel/novel-series.js'), 'utf8');
const content = fs.readFileSync(path.join(staticRoot, 'pixiv-novel/novel-content.js'), 'utf8');
const i18n = fs.readFileSync(path.join(root,
    'pixivdownload-app/src/main/resources/static/js/pixiv-i18n.js'), 'utf8');

for (const hasNeighbors of [false, true]) {
    test(`整页翻译刷新保留${hasNeighbors ? '有相邻章节' : '无相邻章节'}的动态导航`, async () => {
        const nodes = new Map();
        for (const match of page.matchAll(/<(?:a|div)\b([^>]*\bid="series-[^"]+"[^>]*)>/g)) {
            const attrs = Object.fromEntries([...match[1].matchAll(/([\w-]+)="([^"]*)"/g)]
                .map(attribute => [attribute[1], attribute[2]]));
            nodes.set(attrs.id, {
                textContent: '', style: {}, classList: {add() {}, remove() {}},
                getAttribute: name => attrs[name] ?? null,
                setAttribute: (name, value) => { attrs[name] = value; },
                removeAttribute: name => { delete attrs[name]; }
            });
        }
        const document = {
            getElementById: id => nodes.get(id),
            querySelectorAll: selector => [...nodes.values()]
                .filter(node => node.getAttribute(selector.slice(1, -1)) != null)
        };
        const nav = {seriesId: '7', seriesTitle: 'Series', currentOrder: 2,
            prev: hasNeighbors ? {novelId: '41', seriesOrder: 1, title: 'First'} : null,
            next: hasNeighbors ? {novelId: '43', seriesOrder: 3, title: 'Third'} : null};
        const requests = [];
        const sandbox = {document, URLSearchParams, novelId: '42', PixivNovel: {},
            activeContentLang: 'ja-JP', cachedSeriesNav: null,
            navigator: {language: 'en-US'},
            fetch: async url => {
                requests.push(url);
                return {ok: true, json: async () => url.startsWith('/api/gallery/novel/') ? nav : url.includes('/meta') ? {
                currentLang: 'en-US', defaultLang: 'en-US', supportedLocales: [{tag: 'en-US'}]
            } : {messages: {'series.prev': 'Prev #{order} {title}',
                'series.next': 'Next #{order} {title}', 'series.index': 'Contents'}}};
            }};
        sandbox.window = sandbox;
        vm.createContext(sandbox);
        vm.runInContext(i18n, sandbox);
        vm.runInContext(series, sandbox);
        sandbox.pageI18n = await sandbox.PixivI18n.create({namespaces: ['novel-gallery']});
        await sandbox.PixivNovel.series.loadSeriesNav();
        assert.deepEqual(requests.filter(url => url.startsWith('/api/gallery/novel/')),
            ['/api/gallery/novel/42/series?lang=ja-JP']);
        assert.equal(sandbox.PixivNovel.series.getCachedSeriesNav(), nav);
        const rendered = [...nodes.values()].map(node => node.textContent);
        // 槽位加载和界面语言刷新都会再次调用真实的整页翻译入口。
        sandbox.pageI18n.apply(document);
        assert.deepEqual([...nodes.values()].map(node => node.textContent), rendered);
        for (const suffix of ['', '-bottom']) {
            assert.equal(nodes.get('series-prev' + suffix).textContent,
                hasNeighbors ? 'Prev #1 First' : 'Prev #- ');
            assert.equal(nodes.get('series-next' + suffix).textContent,
                hasNeighbors ? 'Next #3 Third' : 'Next #- ');
            assert.equal(nodes.get('series-index' + suffix).textContent, 'Contents · Series');
            assert.equal(nodes.get('series-nav' + suffix).style.display, 'grid');
            assert.equal(nodes.get('series-index' + suffix).href,
                '/pixiv-series.html?type=novel&seriesId=7&currentId=42&seriesTitle=Series&order=2');
            if (hasNeighbors) {
                assert.equal(nodes.get('series-prev' + suffix).href, '/pixiv-novel.html?id=41');
                assert.equal(nodes.get('series-next' + suffix).href, '/pixiv-novel.html?id=43');
            }
        }
        vm.runInContext(content, sandbox);
        Object.assign(sandbox, {rerenderPayload: {data: {}},
            renderBadges() {}, renderMetaRow() {}, renderDescription() {}, renderTags() {}, updateHeart() {}});
        const originalI18n = sandbox.pageI18n;
        sandbox.pageI18n = {t: (...args) => 'Changed ' + originalI18n.t(...args)};
        const requestCount = requests.length;
        sandbox.PixivNovel.content.rerenderDynamic();
        assert.deepEqual([...nodes.values()].map(node => node.textContent),
            rendered.map(text => text ? 'Changed ' + text : text));
        assert.equal(requests.length, requestCount, '界面语言重绘使用已加载的导航');
    });
}
