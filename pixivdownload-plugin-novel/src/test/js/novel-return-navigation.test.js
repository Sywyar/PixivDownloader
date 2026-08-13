'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const ROOT = path.join(__dirname, '..', '..', 'main', 'resources', 'static');
const I18N_ROOT = path.join(__dirname, '..', '..', 'main', 'resources', 'i18n', 'web');
const CORE = fs.readFileSync(path.join(ROOT, 'pixiv-novel', 'novel-core.js'), 'utf8');
const PAGE = fs.readFileSync(path.join(ROOT, 'pixiv-novel.html'), 'utf8');
const GALLERY_INIT = fs.readFileSync(path.join(ROOT, 'pixiv-novel-gallery', 'novel-gallery-init.js'), 'utf8');
const GALLERY_ZH = fs.readFileSync(path.join(I18N_ROOT, 'novel-gallery.properties'), 'utf8');
const GALLERY_EN = fs.readFileSync(path.join(I18N_ROOT, 'novel-gallery_en.properties'), 'utf8');

function load(stored) {
    const sandbox = {
        URL, URLSearchParams, Map, Set,
        location: {origin: 'http://localhost', pathname: '/pixiv-novel.html', search: '?id=1'},
        sessionStorage: {getItem: () => stored},
        setTimeout, clearTimeout
    };
    sandbox.window = sandbox;
    vm.createContext(sandbox);
    vm.runInContext(CORE, sandbox);
    return sandbox.PixivNovel.core;
}

assert.strictEqual(load('/pixiv-novel-gallery.html?view=authors').novelGalleryReturnTo,
    '/pixiv-novel-gallery.html?view=authors');
assert.strictEqual(load('/pixiv-gallery.html?view=all').novelGalleryReturnTo,
    '/pixiv-novel-gallery.html?view=all');
assert.strictEqual(load('https://example.invalid/steal').novelGalleryReturnTo,
    '/pixiv-novel-gallery.html?view=all');
assert.ok(PAGE.includes('id="backToGalleryLink"') && PAGE.includes('返回画廊')
    && !PAGE.includes('history.back()') && !PAGE.includes('data-i18n="button.gallery"'));
assert.ok(GALLERY_ZH.includes('button.back=返回画廊') && GALLERY_EN.includes('button.back=Back to Gallery'));
assert.ok(GALLERY_INIT.includes("sessionStorage.setItem(NOVEL_GALLERY_RETURN_KEY")
    && GALLERY_INIT.includes("window.addEventListener('pagehide', rememberNovelGalleryLocation)"));

console.log('novel-return-navigation.test.js: passed');
