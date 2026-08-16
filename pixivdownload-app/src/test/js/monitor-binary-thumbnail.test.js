'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');

const source = fs.readFileSync(path.join(__dirname, '..', '..', 'main', 'resources', 'static',
    'monitor', 'monitor-detail.js'), 'utf8');
const loader = source.match(/function loadThumbnail\(artworkId, page\) \{[\s\S]*?\n    \}/)[0];

assert.ok(loader.includes('src="/api/downloaded/thumbnail/${artworkId}/${page}"'));
assert.ok(!loader.includes('base64'));
assert.ok(!loader.includes('.json()'));

console.log('monitor-binary-thumbnail.test.js: passed');
