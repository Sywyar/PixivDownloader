const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');

test('快速切换语言以最后选择为准，旧请求和销毁后的回调不改写界面', async () => {
    function element() {
        return {
            style: {}, children: [], events: {}, value: '',
            appendChild(child) { this.children.push(child); child.parentNode = this; },
            removeChild(child) { this.children = this.children.filter(item => item !== child); },
            addEventListener(name, callback) { this.events[name] = callback; }
        };
    }
    const pending = new Map();
    const updates = new Map();
    const disposed = [];
    const changed = [];
    function client(lang) {
        return {
            lang, cookieName: 'language', supportedLocales: [{ tag: 'en-US' }, { tag: 'zh-CN' }],
            apply() {}, dispose() { disposed.push(lang); },
            setLanguage(next) { return new Promise(resolve => pending.set(next, resolve)); },
            onUpdate(callback) { updates.set(lang, callback); return () => updates.delete(lang); }
        };
    }
    const sandbox = {
        console, document: { createElement: element }, location: { protocol: 'http:' },
        PixivI18n: { normalizeLang: value => value, notifyLanguageChange() {}, onLanguageChange: () => () => {} }
    };
    sandbox.window = sandbox;
    vm.createContext(sandbox);
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/pixiv-lang-switcher.js'), 'utf8'), sandbox);
    const mounted = await sandbox.PixivLangSwitcher.mount({
        mountPoint: element(), i18n: client('zh-CN'), onChange: value => changed.push(value.lang)
    });
    const select = mounted.element.children[0];
    select.value = 'en-US';
    select.events.change();
    select.value = 'zh-CN';
    select.events.change();
    pending.get('zh-CN')(client('zh-CN'));
    await new Promise(setImmediate);
    pending.get('en-US')(client('en-US'));
    await new Promise(setImmediate);
    assert.equal(select.value, 'zh-CN');
    assert.match(sandbox.document.cookie, /^language=zh-CN;/);
    assert.deepEqual(changed, ['zh-CN']);
    assert.ok(disposed.includes('en-US'));
    updates.get('zh-CN')(client('zh-CN'));
    assert.deepEqual(changed, ['zh-CN', 'zh-CN']);
    select.value = 'en-US';
    select.events.change();
    mounted.destroy();
    pending.get('en-US')(client('en-US'));
    await new Promise(setImmediate);
    assert.deepEqual(changed, ['zh-CN', 'zh-CN']);
});
