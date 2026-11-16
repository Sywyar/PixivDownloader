'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const source = file => fs.readFileSync(path.join(__dirname, '../../main/resources/static', file), 'utf8');

test('工具栏换行和隐藏后向布局提供实际边框高度', () => {
    const properties = new Map();
    const element = {
        dataset: {layoutHeight: '--layout-header-height'},
        height: 64,
        getBoundingClientRect() { return {height: this.height}; }
    };
    let resized;
    vm.runInNewContext(source('js/pixiv-layout.js'), {
        window: {addEventListener() {}},
        document: {
            querySelectorAll: selector => selector === '[data-layout-height]' ? [element] : [],
            documentElement: {dataset: {}, style: {setProperty: (key, value) => properties.set(key, value)}}
        },
        ResizeObserver: class {
            constructor(callback) { resized = callback; }
            observe(target) { resized([{target}]); }
        }
    });
    assert.equal(properties.get('--layout-header-height'), '64px');
    element.height = 148.5;
    resized([{target: element}]);
    assert.equal(properties.get('--layout-header-height'), '148.5px');
    element.height = 0;
    resized([{target: element}]);
    assert.equal(properties.get('--layout-header-height'), '0px');
});
