'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const root = path.resolve(__dirname, '../../../..');

// 三个入口共享同一浏览器偏好，切页不能重置用户的选择。
for (const [module, page, file] of [
    ['gallery', 'pixiv-gallery', 'gallery-sidebar'],
    ['gallery', 'pixiv-series', 'series-sidebar'],
    ['novel', 'pixiv-novel-gallery', 'novel-gallery-sidebar']
]) {
    test(`${page} 默认收起并保留显式偏好，移动抽屉开合不改写桌面选择`, () => {
        let saved = null;
        let unavailable = false;
        const classes = new Set();
        const classList = {
            add: value => classes.add(value), remove: value => classes.delete(value),
            toggle(value, force = !classes.has(value)) {
                if (force) classes.add(value); else classes.delete(value);
                return force;
            }
        };
        const context = vm.createContext({
            SIDEBAR_STATE_STORAGE_KEY: 'pixiv:gallery-sidebar-state',
            document: {getElementById: () => ({classList})},
            window: {PixivGallery: {}},
            localStorage: {
                getItem() { if (unavailable) throw Error('storage blocked'); return saved; },
                setItem(key, value) { if (unavailable) throw Error('storage blocked'); saved = value; }
            }
        });
        vm.runInContext(fs.readFileSync(path.join(root, `pixivdownload-plugin-${module}/src/main/resources/static/${page}/${file}.js`), 'utf8'), context);
        context.restoreSidebarState();
        assert.ok(classes.has('collapsed'));
        assert.equal(saved, null);
        context.openMobileSidebar();
        assert.ok(classes.has('mobile-open'));
        context.closeMobileSidebar();
        assert.ok(classes.has('collapsed'));
        assert.equal(saved, null);
        context.toggleSidebar();
        assert.equal(saved, 'open');
        context.restoreSidebarState();
        assert.equal(classes.has('collapsed'), false);
        context.toggleSidebar();
        assert.equal(saved, 'closed');
        context.restoreSidebarState();
        assert.ok(classes.has('collapsed'));
        unavailable = true;
        context.restoreSidebarState();
        context.toggleSidebar();
        assert.equal(classes.has('collapsed'), false);
    });
}
