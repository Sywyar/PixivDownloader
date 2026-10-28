'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/home/home.js'), 'utf8');

function harness({ delayed = false } = {}) {
    const events = {};
    const elements = new Map();
    let focused;
    let mounts = 0;
    let disposed = 0;
    let resolveClient;
    const client = { t: (key, fallback) => fallback, apply() {}, dispose() { disposed++; } };
    const ready = delayed ? new Promise(resolve => { resolveClient = resolve; }) : Promise.resolve(client);
    const document = {
        getElementById(id) {
            if (!elements.has(id)) elements.set(id, {
                hidden: false, children: [], attributes: {}, textContent: '',
                setAttribute(key, value) { this.attributes[key] = value; },
                addEventListener(key, callback) { this[key] = callback; },
                querySelector() { return this.children[0] || null; },
                focus() { focused = id; }
            });
            return elements.get(id);
        }
    };
    const window = {
        addEventListener: (name, handler) => { events[name] = handler; },
        PixivI18n: { create: () => ready },
        PixivLangSwitcher: { mount: async () => ({ destroy: () => { disposed++; } }) },
        PixivTheme: { mount: () => ({ destroy() {} }) },
        PixivNav: { mount: () => { mounts++; }, refresh() {} }
    };
    vm.runInNewContext(source, { window, document, console });
    return {
        get: id => document.getElementById(id),
        render: items => events['pixivnav:rendered']({ detail: { items } }),
        pagehide: persisted => events.pagehide({ persisted }),
        resolve: () => resolveClient(client),
        focused: () => focused, mounts: () => mounts, disposed: () => disposed
    };
}

test('导航加载失败、空列表和重试各自显示正确状态，成功后恢复键盘焦点', async () => {
    const h = harness();
    await new Promise(resolve => setImmediate(resolve));
    h.render(null);
    assert.equal(h.get('home-status-text').attributes['data-i18n'], 'home.error');
    assert.equal(h.get('home-retry').hidden, false);
    h.get('home-retry').click();
    assert.equal(h.mounts(), 1);
    assert.equal(h.get('home-navigation').attributes['aria-busy'], 'true');
    h.render(null);
    assert.equal(h.focused(), 'home-retry');
    h.render([]);
    assert.equal(h.get('home-status-text').attributes['data-i18n'], 'home.empty');
    assert.equal(h.get('home-retry').hidden, true);
    h.get('home-retry').click();
    let focusedLink = false;
    h.get('home-navigation').children.push({ focus() { focusedLink = true; } });
    h.render([{ href: '/feature' }]);
    assert.equal(h.get('home-status').hidden, true);
    assert.equal(h.get('home-navigation').attributes['aria-busy'], 'false');
    assert.equal(focusedLink, true);
});

test('语言初始化未完成时仍显示失败原因，离页后释放迟到客户端', async () => {
    const h = harness({ delayed: true });
    h.render(null);
    assert.match(h.get('home-status-text').textContent, /Could not load/);
    h.pagehide(false);
    h.resolve();
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(h.disposed(), 1);
});

test('页面缓存离开保留客户端，永久离开释放客户端', async () => {
    const h = harness();
    await new Promise(resolve => setImmediate(resolve));
    h.pagehide(true);
    assert.equal(h.disposed(), 0);
    h.pagehide(false);
    assert.equal(h.disposed(), 1);
});
