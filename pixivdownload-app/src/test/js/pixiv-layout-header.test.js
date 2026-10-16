'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/pixiv-layout.js'), 'utf8');

function events(target = {}) {
    const listeners = new Map();
    return Object.assign(target, {
        addEventListener(type, fn) {
            if (!listeners.has(type)) listeners.set(type, new Set());
            listeners.get(type).add(fn);
        },
        removeEventListener(type, fn) { listeners.get(type)?.delete(fn); },
        emit(type) { listeners.get(type)?.forEach(fn => fn()); },
        count(type) { return listeners.get(type)?.size || 0; }
    });
}

function fixture({nested = false, narrow = true} = {}) {
    const classes = new Set();
    const frames = new Map();
    let nextFrame = 0;
    const scroller = events({scrollTop: 0, scrollHeight: 2000, clientHeight: 600});
    const media = events({matches: narrow});
    const header = events({
        dataset: nested ? {layoutScrollRoot: '#content'} : {},
        offsetHeight: 120,
        expanded: false,
        classList: {
            remove: key => classes.delete(key),
            toggle: (key, value) => value ? classes.add(key) : classes.delete(key)
        },
        contains: element => element?.inHeader,
        querySelector() { return this.expanded; }
    });
    const window = events({
        matchMedia: () => media,
        requestAnimationFrame(fn) { frames.set(++nextFrame, fn); return nextFrame; },
        cancelAnimationFrame: id => frames.delete(id)
    });
    const document = {
        scrollingElement: nested ? {} : scroller,
        documentElement: {},
        querySelector: () => scroller,
        querySelectorAll: selector => selector === '[data-layout-height="--layout-header-height"]' ? [header] : []
    };
    vm.runInNewContext(source, {window, document, ResizeObserver: class {observe() {}}});
    const target = nested ? scroller : window;
    function flush() {
        const pending = [...frames.values()];
        frames.clear();
        pending.forEach(fn => fn());
    }
    function scroll(y) { scroller.scrollTop = y; target.emit('scroll'); flush(); }
    return {window, document, header, media, scroller, target, frames, flush, scroll,
        hidden: () => classes.has('layout-header-hidden')};
}

test('窄屏上划收起、下划显出，页顶和边界回弹不会误收起', () => {
    const f = fixture();
    f.scroll(80);
    assert.equal(f.hidden(), false);
    f.scroll(180);
    assert.equal(f.hidden(), true);
    f.scroll(177);
    assert.equal(f.hidden(), true);
    f.scroll(166);
    assert.equal(f.hidden(), false);
    f.scroll(1400);
    assert.equal(f.hidden(), true);
    f.scroll(1450);
    f.scroll(1400);
    assert.equal(f.hidden(), true);
    f.scroll(1380);
    assert.equal(f.hidden(), false);
    f.scroll(-20);
    f.scroll(0);
    assert.equal(f.hidden(), false);
});

test('输入、键盘焦点和展开的控件保留顶部栏，宽屏与旋转恢复可见', () => {
    const f = fixture();
    f.scroll(200);
    f.document.activeElement = {inHeader: true, matches: () => true};
    f.header.emit('focusin');
    f.scroll(300);
    assert.equal(f.hidden(), false);
    f.document.activeElement = null;
    f.header.expanded = true;
    f.scroll(400);
    assert.equal(f.hidden(), false);
    f.header.expanded = false;
    f.scroll(500);
    assert.equal(f.hidden(), true);
    f.media.matches = false;
    f.media.emit('change');
    f.scroll(600);
    assert.equal(f.hidden(), false);
    f.media.matches = true;
    f.scroll(700);
    f.window.emit('resize');
    assert.equal(f.hidden(), false);
});

test('独立页面容器驱动显隐，不响应窗口或其它局部区域的滚动', () => {
    const f = fixture({nested: true});
    f.scroller.scrollTop = 200;
    f.window.emit('scroll');
    f.flush();
    assert.equal(f.hidden(), false);
    f.scroll(220);
    assert.equal(f.hidden(), true);
    f.scroll(180);
    assert.equal(f.hidden(), false);
});

test('滚动按帧合并，离页取消监听和迟到帧，返回不重复绑定', () => {
    const f = fixture();
    f.scroller.scrollTop = 200;
    f.target.emit('scroll');
    f.target.emit('scroll');
    assert.equal(f.frames.size, 1);
    f.window.emit('pagehide');
    assert.equal(f.frames.size, 0);
    assert.equal(f.target.count('scroll'), 0);
    f.flush();
    assert.equal(f.hidden(), false);
    f.window.emit('pageshow');
    f.window.emit('pageshow');
    assert.equal(f.target.count('scroll'), 1);
    f.scroll(300);
    assert.equal(f.hidden(), true);
});
