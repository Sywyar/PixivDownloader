'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '..', '..', 'main', 'resources', 'static',
    'monitor', 'monitor-detail.js'), 'utf8');
let errorListener;
const image = {
    addEventListener(type, listener, options) {
        assert.strictEqual(type, 'error');
        assert.strictEqual(options.once, true);
        errorListener = listener;
    }
};
const item = {
    innerHTML: '',
    onclick: () => {},
    style: {},
    querySelector(selector) {
        assert.strictEqual(selector, 'img');
        return image;
    }
};
const sandbox = {
    document: {
        getElementById(id) {
            return id === 'thumb-123-4' ? item : null;
        }
    }
};
vm.createContext(sandbox);
vm.runInContext(source, sandbox, {filename: 'monitor-detail.js'});

vm.runInContext('loadThumbnail(123, 4)', sandbox);
assert.match(item.innerHTML, /<img src="\/api\/downloaded\/thumbnail\/123\/4"/);
assert.match(item.innerHTML, /<div class="thumbnail-index">5<\/div>/);
assert.strictEqual(typeof errorListener, 'function');

errorListener();
assert.match(item.innerHTML, /class="thumbnail-error"/);
assert.strictEqual(item.style.cursor, 'default');
assert.strictEqual(item.onclick, null);

assert.doesNotThrow(() => vm.runInContext('loadThumbnail(999, 0)', sandbox));

console.log('monitor-binary-thumbnail.test.js: passed');
