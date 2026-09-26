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

test('介绍页导航使用真实段落位置并尊重减少动画设置', () => {
    const scrolls = [];
    const node = () => ({
        dataset: {}, children: [], events: {},
        classList: {add() {}, toggle() {}},
        setAttribute() {},
        addEventListener(type, callback) { this.events[type] = callback; },
        appendChild(child) { this.children.push(child); }
    });
    const slides = Array.from({length: 3}, (_, index) => ({
        ...node(), scrollIntoView(options) { scrolls.push({index, behavior: options.behavior}); }
    }));
    const dots = node();
    const link = {...node(), dataset: {goto: '2'}};
    let reduced = false;
    vm.runInNewContext(source('intro/intro.js'), {
        document: {
            getElementById: id => id === 'dots' ? dots : node(),
            querySelectorAll: selector => selector === '.slide' ? slides : selector === '[data-goto]' ? [link] : [],
            createElement: node, addEventListener() {}
        },
        matchMedia: () => ({matches: reduced}),
        IntersectionObserver: class { observe() {} }
    });
    link.events.click({preventDefault() {}});
    reduced = true;
    dots.children[1].events.click();
    assert.deepEqual(scrolls, [{index: 2, behavior: 'smooth'}, {index: 1, behavior: 'instant'}]);
});
