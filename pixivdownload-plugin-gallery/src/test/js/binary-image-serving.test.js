'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..', '..', 'main', 'resources', 'static');
const artworkCore = fs.readFileSync(path.join(ROOT, 'pixiv-artwork', 'artwork-core.js'), 'utf8');
const artworkRelated = fs.readFileSync(path.join(ROOT, 'pixiv-artwork', 'artwork-related.js'), 'utf8');
const showcaseCore = fs.readFileSync(path.join(ROOT, 'pixiv-showcase', 'showcase-core.js'), 'utf8');
const seriesRender = fs.readFileSync(path.join(ROOT, 'pixiv-series', 'series-render.js'), 'utf8');

assert.ok(!artworkCore.includes(';base64,'));
assert.ok(!artworkRelated.includes(';base64,'));
assert.ok(!showcaseCore.includes(';base64,'));
assert.ok(!seriesRender.includes(';base64,'));
assert.ok(artworkCore.includes('image.src = url'));
assert.ok(artworkRelated.includes('img.src = ImageCache.get(url) || url'));
assert.ok(showcaseCore.includes('image.src = url'));
assert.ok(seriesRender.includes('img.src = ImageCache.get(url) || url'));

console.log('binary-image-serving.test.js: passed');
