'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

test('theme labels follow language changes and discard late translations after replacement or removal', async () => {
    const element = () => ({
        style: {}, dataset: {}, attrs: {},
        setAttribute(key, value) { this.attrs[key] = value; },
        getAttribute(key) { return this.attrs[key]; },
        addEventListener() {}, removeEventListener() {},
        appendChild(child) { child.parentNode = this; },
        removeChild(child) { child.parentNode = null; }
    });
    const pending = [];
    let listener;
    let unsubscribed = 0;
    const window = {
        document: { documentElement: element(), createElement: element },
        localStorage: { getItem() { return 'light'; }, setItem() {} },
        addEventListener() {}, dispatchEvent() {},
        PixivI18n: {
            create(options) { return new Promise(resolve => pending.push({ options, resolve })); },
            onLanguageChange(callback) { listener = callback; return () => { unsubscribed++; }; }
        }
    };
    vm.runInNewContext(fs.readFileSync(path.join(__dirname,
        '../../main/resources/static/js/pixiv-theme.js'), 'utf8'), { window, document: window.document });
    const mounted = window.PixivTheme.mount({ mountPoint: element() });
    const translated = prefix => ({ t: key => prefix + key });
    pending.shift().resolve(translated('first:'));
    await new Promise(setImmediate);
    assert.equal(mounted.element.title, 'first:theme.to-dark');
    window.PixivTheme.toggle();
    assert.equal(mounted.element.attrs['aria-label'], 'first:theme.to-light');
    listener({ lang: 'old' });
    listener({ lang: 'new' });
    const old = pending.shift();
    const latest = pending.shift();
    latest.resolve(translated('new:'));
    await new Promise(setImmediate);
    old.resolve(translated('old:'));
    await new Promise(setImmediate);
    assert.equal(mounted.element.title, 'new:theme.to-light');
    listener({ lang: 'removed' });
    mounted.destroy();
    pending.shift().resolve(translated('removed:'));
    await new Promise(setImmediate);
    assert.equal(unsubscribed, 1);
    assert.equal(mounted.element.title, 'new:theme.to-light');
});
