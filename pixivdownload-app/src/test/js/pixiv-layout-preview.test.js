'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
let bounds = {width: 150, height: 120};
let resize;
const image = {tagName: 'IMG', dataset: {}, getAttribute() { return this.src; }};
image.parentElement = {getBoundingClientRect: () => bounds};
const sandbox = {
    URL,
    window: {devicePixelRatio: 2, location: {href: 'http://localhost/pixiv-gallery.html'},
        addEventListener(type, handler) { if (type === 'resize') resize = handler; }},
    document: {documentElement: {}, querySelectorAll: selector => selector === '[data-preview-src]' ? [image] : []},
    ResizeObserver: class { observe() {} }
};
vm.createContext(sandbox);
vm.runInContext(fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/pixiv-layout.js'), 'utf8'), sandbox);
const preview = sandbox.window.PixivLayout.previewUrl;
image.src = preview('/api/downloaded/thumbnail/42/0?v=1', image);
const revision = new URL(image.src, sandbox.window.location.href).searchParams.get('preview');
assert.ok(revision);
assert.equal(image.src, '/api/downloaded/thumbnail/42/0?v=1&size=512&preview=' + revision);
bounds = {width: 600, height: 350};
resize();
assert.equal(image.src, '/api/downloaded/thumbnail/42/0?v=1&size=1600&preview=' + revision);
sandbox.window.devicePixelRatio = 1;
resize();
assert.equal(image.src, '/api/downloaded/thumbnail/42/0?v=1&size=1024&preview=' + revision);
bounds = {width: 0, height: 0};
assert.equal(preview('/api/downloaded/thumbnail/42/0', image), '/api/downloaded/thumbnail/42/0?size=512&preview=' + revision);
assert.equal(new URL(preview('/api/downloaded/thumbnail-file/42/0?cover=true', image),
    sandbox.window.location.href).searchParams.get('cover'), 'true');
console.log('pixiv-layout-preview.test.js: passed');
