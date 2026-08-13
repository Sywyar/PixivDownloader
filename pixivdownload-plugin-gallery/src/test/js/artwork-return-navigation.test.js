'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const ROOT = path.join(__dirname, '..', '..', 'main', 'resources', 'static');
const CORE = fs.readFileSync(path.join(ROOT, 'pixiv-artwork', 'artwork-core.js'), 'utf8');
const PAGE = fs.readFileSync(path.join(ROOT, 'pixiv-artwork.html'), 'utf8');
const GALLERY_INIT = fs.readFileSync(path.join(ROOT, 'pixiv-gallery', 'gallery-init.js'), 'utf8');

function load(stored) {
    const sandbox = {
        URL, URLSearchParams, Map, Set, Promise,
        location: {origin: 'http://localhost', pathname: '/pixiv-artwork.html', search: '?id=1'},
        sessionStorage: {getItem: () => stored},
        setTimeout, clearTimeout
    };
    sandbox.window = sandbox;
    vm.createContext(sandbox);
    vm.runInContext(CORE, sandbox);
    return sandbox.PixivArtwork.core;
}

assert.strictEqual(load('/pixiv-gallery.html?view=series').state.returnTo,
    '/pixiv-gallery.html?view=series');
assert.strictEqual(load('/pixiv-novel-gallery.html?view=all').state.returnTo,
    '/pixiv-gallery.html?view=all');
assert.strictEqual(load('//example.invalid/steal').state.returnTo,
    '/pixiv-gallery.html?view=all');
assert.ok(PAGE.includes('id="backToGalleryLink"') && PAGE.includes('返回画廊')
    && !PAGE.includes('history.back()') && !PAGE.includes('id="galleryBtnLabel"'));
assert.ok(GALLERY_INIT.includes("sessionStorage.setItem(ARTWORK_GALLERY_RETURN_KEY")
    && GALLERY_INIT.includes("window.addEventListener('pagehide', rememberArtworkGalleryLocation)"));

console.log('artwork-return-navigation.test.js: passed');
