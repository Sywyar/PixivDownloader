'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname,
    '../../main/resources/static/js/pixiv-tour.js'), 'utf8');

function scenario() {
    const nodes = [];
    const frames = [];
    const timers = [];
    const listeners = new Map();
    const stored = new Map();
    function element() {
        const children = new Map();
        const classes = new Set();
        const node = {
            style: {}, textContent: '', offsetWidth: 300, offsetHeight: 120,
            classList: {add: value => classes.add(value), remove: value => classes.delete(value),
                contains: value => classes.has(value)},
            appendChild(child) { child.parentNode = this; },
            removeChild(child) { child.parentNode = null; },
            setAttribute() {},
            addEventListener(type, handler) { this[type] = handler; },
            querySelector(selector) {
                if (!children.has(selector)) children.set(selector, element());
                return children.get(selector);
            }
        };
        nodes.push(node);
        return node;
    }
    const document = {
        body: element(), documentElement: {clientWidth: 800, clientHeight: 600},
        createElement: element, querySelector: () => null,
        addEventListener: (type, handler) => listeners.set(type, handler),
        removeEventListener: type => listeners.delete(type)
    };
    const window = {
        localStorage: {getItem: key => stored.get(key), setItem: (key, value) => stored.set(key, value)},
        addEventListener: (type, handler) => listeners.set(type, handler),
        removeEventListener: type => listeners.delete(type),
        requestAnimationFrame: callback => frames.push(callback),
        setTimeout: callback => timers.push(callback),
        setInterval: callback => timers.push(callback), clearInterval() {}
    };
    vm.runInNewContext(source, {window, document});
    const client = prefix => ({t: key => prefix + key});
    const options = {pageKey: 'test-guide', noHelpFab: true, auto: true, i18n: client('first:'),
        steps: [{titleKey: 'title-one', bodyKey: 'body-one'}, {titleKey: 'title-two', bodyKey: 'body-two'}]};
    const controller = window.PixivTour.init(options);
    return {window, nodes, frames, timers, listeners, stored, options, controller, client};
}

test('关闭页面指引后迟到动画不抛错，也不改变重开的浮层', () => {
    for (const reopen of [false, true]) {
        const s = scenario();
        const pop = s.nodes.find(node => node.className === 'pt-pop');
        s.frames.shift()();
        pop.querySelector('.pt-btn-skip').click();
        assert.equal(s.listeners.size, 0);
        assert.equal(s.stored.get('pixiv_tour_seen_test-guide'), '1');
        if (reopen) s.controller.start(true);
        assert.doesNotThrow(() => s.timers.shift()());
        const current = s.nodes.filter(node => node.className === 'pt-pop').at(-1);
        assert.equal(current.classList.contains('pt-pop-in'), false);
        if (reopen) {
            s.frames.shift()();
            s.timers.shift()();
            assert.equal(current.classList.contains('pt-pop-in'), true);
        }
    }
});

test('更新页面词典时刷新正在显示的指引并保留当前步骤', () => {
    const s = scenario();
    const pop = s.nodes.find(node => node.className === 'pt-pop');
    pop.querySelector('.pt-btn-next').click();
    s.window.PixivTour.refreshTranslations(s.client('next:'));
    assert.equal(s.window.PixivTour.get(s.options.pageKey), s.controller);
    assert.equal(s.nodes.filter(node => node.className === 'pt-pop').length, 1);
    assert.equal(pop.querySelector('.pt-pop-title').textContent, 'next:title-two');
    assert.equal(pop.querySelector('.pt-btn-next').textContent, 'next:tour:common.done');
});
