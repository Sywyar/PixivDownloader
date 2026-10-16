'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const ROOT = path.join(__dirname, '../../../../');
const KEY = 'pixiv:gallery-sidebar-state';

function load(page, storage = new Map()) {
    const nodes = new Map(['sidebar', 'mobileOverlay'].map(id => {
        const classes = new Set();
        return [id, {classList: {
            add: value => classes.add(value),
            remove: value => classes.delete(value),
            contains: value => classes.has(value),
            toggle(value, force = !classes.has(value)) {
                if (force) classes.add(value); else classes.delete(value);
                return force;
            }
        }}];
    }));
    const sandbox = {
        window: page === 'gallery' ? {PixivGallery: {}} : {},
        document: {getElementById: id => nodes.get(id)},
        localStorage: {getItem: key => storage.get(key), setItem: (key, value) => storage.set(key, value)}
    };
    vm.createContext(sandbox);
    const assets = path.join(ROOT, `pixivdownload-plugin-${page === 'novel-gallery' ? 'novel' : 'gallery'}/src/main/resources/static`);
    const html = fs.readFileSync(path.join(assets, `pixiv-${page}.html`), 'utf8');
    const scripts = [...html.matchAll(/<script src="([^"]+)"/g)].map(match => match[1]);
    const stateScript = page === 'gallery' ? '/pixiv-gallery/gallery-state.js'
        : page === 'series' ? '/pixiv-series/series-core.js' : '/pixiv-novel-gallery/novel-gallery-core.js';
    const sidebarScript = scripts.find(src => src.endsWith('-sidebar.js'));
    assert.ok(scripts.indexOf(stateScript) >= 0 && scripts.indexOf(stateScript) < scripts.indexOf(sidebarScript));
    for (const src of [stateScript, sidebarScript]) {
        vm.runInContext(fs.readFileSync(path.join(assets, src), 'utf8'), sandbox, {filename: src});
    }
    return {sandbox, nodes, storage};
}

for (const page of ['gallery', 'series', 'novel-gallery']) {
    test(`${page} 侧栏恢复与切换保留跨页存储契约`, () => {
        const {sandbox, nodes, storage} = load(page);
        const sidebar = nodes.get('sidebar').classList;
        for (const value of [undefined, 'open', 'unexpected', 'closed']) {
            storage.set(KEY, value);
            sandbox.restoreSidebarState();
            assert.equal(sidebar.contains('collapsed'), value !== 'open');
        }
        sandbox.toggleSidebar();
        assert.equal(sidebar.contains('collapsed'), false);
        assert.equal(storage.get(KEY), 'open');
        sandbox.toggleSidebar();
        assert.equal(sidebar.contains('collapsed'), true);
        assert.equal(storage.get(KEY), 'closed');
        if (page === 'gallery') assert.equal(sandbox.window.PixivGallery.sidebar.toggleSidebar, sandbox.toggleSidebar);
        else assert.equal(sandbox.window.PixivGallery, undefined);
    });

    test(`${page} 存储不可用或侧栏缺席时保留页面可操作性`, () => {
        const {sandbox, nodes} = load(page);
        sandbox.localStorage.getItem = sandbox.localStorage.setItem = () => { throw new Error('Storage disabled'); };
        nodes.get('sidebar').classList.add('collapsed');
        sandbox.restoreSidebarState();
        assert.equal(nodes.get('sidebar').classList.contains('collapsed'), true);
        sandbox.toggleSidebar();
        assert.equal(nodes.get('sidebar').classList.contains('collapsed'), false);
        nodes.delete('sidebar');
        assert.doesNotThrow(() => { sandbox.restoreSidebarState(); sandbox.toggleSidebar(); });
    });

    test(`${page} 移动侧栏与遮罩同步开关且不覆盖桌面偏好`, () => {
        const {sandbox, nodes, storage} = load(page, new Map([[KEY, 'closed']]));
        sandbox.restoreSidebarState();
        sandbox.openMobileSidebar();
        assert.equal(nodes.get('sidebar').classList.contains('mobile-open'), true);
        assert.equal(nodes.get('mobileOverlay').classList.contains('active'), true);
        sandbox.closeMobileSidebar();
        assert.equal(nodes.get('sidebar').classList.contains('mobile-open'), false);
        assert.equal(nodes.get('mobileOverlay').classList.contains('active'), false);
        assert.equal(nodes.get('sidebar').classList.contains('collapsed'), true);
        assert.equal(storage.get(KEY), 'closed');
    });
}

test('画廊的侧栏偏好能被系列页恢复，系列页修改后能返回画廊恢复', () => {
    const gallery = load('gallery');
    gallery.sandbox.toggleSidebar();
    const series = load('series', gallery.storage);
    series.sandbox.restoreSidebarState();
    assert.equal(series.nodes.get('sidebar').classList.contains('collapsed'), true);
    series.sandbox.toggleSidebar();
    gallery.sandbox.restoreSidebarState();
    assert.equal(gallery.nodes.get('sidebar').classList.contains('collapsed'), false);
});
