const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');
const { Element, installVue } = require('./vue-render-fixture');

const flush = () => new Promise(setImmediate);
const meta = {
    currentLang: 'en-US', defaultLang: 'en-US', languageCookieName: 'language',
    supportedLocales: [{ tag: 'en-US' }, { tag: 'zh-CN' }]
};

function element() {
    return {
        style: {}, children: [], events: {}, value: '',
        appendChild(child) { this.children.push(child); child.parentNode = this; },
        removeChild(child) { this.children = this.children.filter(item => item !== child); child.parentNode = null; },
        addEventListener(name, callback) { this.events[name] = callback; }
    };
}

function harness(withAbortController = true) {
    const state = { calls: [], timers: new Map(), events: new Map(), stored: new Map(), warnings: [], hold: () => false, fail: false };
    let timerId = 0;
    const sandbox = {
        AbortController: withAbortController ? AbortController : undefined,
        navigator: { language: 'en-US' }, location: { protocol: 'http:' },
        document: { createElement: element, documentElement: {}, querySelectorAll: () => [] },
        console: { warn: (...args) => state.warnings.push(args) },
        localStorage: { getItem: key => state.stored.get(key) || null, setItem: (key, value) => state.stored.set(key, value) },
        setTimeout(callback, delay) { state.timers.set(++timerId, { callback, delay }); return timerId; },
        clearTimeout(id) { state.timers.delete(id); },
        addEventListener(name, callback) {
            if (!state.events.has(name)) state.events.set(name, new Set());
            state.events.get(name).add(callback);
        },
        removeEventListener(name, callback) { state.events.get(name)?.delete(callback); },
        fetch(url, options) {
            const call = { url, signal: options.signal };
            state.calls.push(call);
            const isMeta = url.includes('/meta');
            const lang = new URL(url, 'http://localhost/').searchParams.get('lang') || 'en-US';
            const response = { ok: true, json: async () => isMeta ? structuredClone(meta) : { messages: { title: lang } } };
            if (state.hold(url)) {
                return new Promise((resolve, reject) => {
                    call.finish = () => resolve(response);
                    call.signal?.addEventListener('abort', () => reject(call.signal.reason), { once: true });
                });
            }
            return Promise.resolve(state.fail && !isMeta ? { ok: false, status: 503 } : response);
        }
    };
    sandbox.window = sandbox;
    vm.createContext(sandbox, { codeGeneration: { strings: false, wasm: false } });
    for (const file of ['pixiv-i18n.js', 'pixiv-lang-switcher.js']) {
        vm.runInContext(fs.readFileSync(path.join(__dirname, '../../main/resources/static/js', file), 'utf8'), sandbox);
    }
    state.window = sandbox;
    state.create = options => sandbox.PixivI18n.create(options);
    state.emit = (name, event) => [...(state.events.get(name) || [])].forEach(callback => callback(event));
    state.runTimer = delay => {
        const [id, timer] = [...state.timers].find(([, value]) => value.delay === delay);
        state.timers.delete(id);
        timer.callback();
    };
    return state;
}

test('页面区块刷新只保留当前字典的恢复监听与请求', async () => {
    const h = harness();
    h.fail = true;
    const slot = new Element('div');
    slot.setAttribute('data-section-slot', 'side.sections');
    Object.assign(h.window.document, {head: new Element('head'), readyState: 'complete',
        querySelectorAll: () => [slot]});
    h.window.Promise = Promise;
    const Vue = installVue(h.window);
    h.window.PixivVue = {ensure: async () => Vue, mountOn: async (el, component) => {
        const app = Vue.createApp(component);
        app.mount(el);
        return {app};
    }};
    const fetch = h.window.fetch;
    h.window.fetch = (url, options) => url.startsWith('/api/page-sections')
        ? Promise.resolve({ok: true, json: async () => [
            {id: 'demo', titleNamespace: 'demo', titleI18nKey: 'title'}]})
        : fetch(url, options);
    vm.runInContext(fs.readFileSync(path.join(__dirname,
        '../../main/resources/static/js/pixiv-page-sections.js'), 'utf8'), h.window);
    await h.window.PixivPageSections.ready();
    for (let i = 0; i < 20; i++) await h.window.PixivPageSections.refresh();
    assert.equal(h.timers.size, 1);
    for (const name of ['focus', 'online', 'pagehide', 'pageshow']) assert.equal(h.events.get(name).size, 1);
    h.hold = () => true;
    const start = h.calls.length;
    h.emit('focus');
    assert.equal(h.calls.length - start, 2);
    h.emit('pagehide');
    assert.ok(h.calls.slice(start).every(call => call.signal.aborted));
    await flush();
    assert.equal(h.timers.size, 0);
});

test('销毁客户端立即取消整轮恢复，不启动静态回退、通知或下一轮重试', async () => {
    const h = harness();
    h.fail = true;
    const client = await h.create({ namespaces: ['common', 'gallery'] });
    let updates = 0;
    client.onUpdate(() => updates++);
    h.hold = () => true;
    const start = h.calls.length;
    h.emit('focus');
    assert.equal(h.calls.length - start, 2);
    client.dispose();
    assert.ok(h.calls.slice(start).every(call => call.signal.aborted));
    await flush();
    assert.equal(h.calls.length - start, 2);
    assert.equal(h.timers.size, 0);
    assert.equal(updates, 0);
    for (const name of ['focus', 'online', 'pagehide']) assert.equal(h.events.get(name).size, 0);
});

for (const stage of ['meta', 'messages/common', 'i18n-static/']) {
    test(`取消 ${stage} 加载后不回退、不加载后续字典、不保存语言`, async () => {
        const h = harness();
        h.fail = stage === 'i18n-static/';
        h.hold = url => url.includes(stage);
        const controller = new AbortController();
        const result = h.create({ lang: 'zh-CN', namespaces: ['common', 'gallery'], signal: controller.signal }).catch(error => error);
        await flush();
        const pending = h.calls.at(-1);
        const count = h.calls.length;
        controller.abort();
        assert.equal(pending.signal.aborted, true);
        assert.equal((await result).name, 'AbortError');
        assert.equal(h.calls.length, count);
        assert.equal(h.stored.size, 0);
        assert.equal(h.timers.size, 0);
        assert.equal(h.events.get('pagehide')?.size || 0, 0);
        assert.equal(h.warnings.length, 0);
    });
}

test('预先取消的创建不发请求，也不改变偏好', async () => {
    const h = harness();
    const controller = new AbortController();
    controller.abort();
    await assert.rejects(h.create({ signal: controller.signal }), { name: 'AbortError' });
    assert.equal(h.calls.length, 0);
    assert.equal(h.stored.size, 0);
});

test('主题按钮的临时词典失败后释放恢复资源，反复挂载销毁不遗留请求', async () => {
    const h = harness();
    h.fail = true;
    function themeElement() {
        return Object.assign(element(), {dataset: {}, attrs: {},
            setAttribute(key, value) { this.attrs[key] = value; },
            getAttribute(key) { return this.attrs[key]; },
            removeEventListener(name) { delete this.events[name]; }
        });
    }
    h.window.document.documentElement = themeElement();
    h.window.document.createElement = themeElement;
    vm.runInContext(fs.readFileSync(path.join(__dirname,
        '../../main/resources/static/js/pixiv-theme.js'), 'utf8'), h.window);
    for (let i = 0; i < 5; i++) {
        const mounted = h.window.PixivTheme.mount({mountPoint: themeElement()});
        const fallbackTitle = mounted.element.title;
        await flush();
        assert.equal(mounted.element.title, fallbackTitle);
        assert.equal(h.timers.size, 0);
        mounted.destroy();
        for (const name of ['focus', 'online', 'pagehide', 'pageshow']) assert.equal(h.events.get(name)?.size || 0, 0);
    }
    const count = h.calls.length;
    h.emit('focus'); h.emit('online'); h.emit('pageshow', {persisted: true});
    await flush();
    assert.equal(h.calls.length, count);
    assert.equal(h.timers.size, 0);
});

test('取消响应体读取后拒绝迟到 JSON，不发布客户端或继续加载', async () => {
    const h = harness();
    const fetch = h.window.fetch;
    let finishBody;
    h.window.fetch = async (url, options) => {
        const response = await fetch(url, options);
        if (url.includes('/messages/common')) response.json = () => new Promise(resolve => { finishBody = resolve; });
        return response;
    };
    const controller = new AbortController();
    const result = h.create({ namespaces: ['common', 'gallery'], signal: controller.signal }).catch(error => error);
    await flush();
    controller.abort();
    assert.equal(h.calls.at(-1).signal.aborted, true);
    finishBody({ messages: { title: 'Late result' } });
    assert.equal((await result).name, 'AbortError');
    assert.equal(h.calls.length, 2);
    assert.equal(h.stored.size, 0);
    assert.equal(h.timers.size, 0);
});

test('离开页面取消在途恢复，成功创建后解除调用方的取消监听', async () => {
    const h = harness();
    h.fail = true;
    const controller = new AbortController();
    const client = await h.create({ signal: controller.signal });
    controller.abort();
    h.hold = () => true;
    h.emit('focus');
    const pending = h.calls.at(-1);
    assert.equal(pending.signal.aborted, false);
    h.emit('pagehide');
    assert.equal(pending.signal.aborted, true);
    await flush();
    assert.equal(h.timers.size, 0);
    assert.equal(client.has('title'), false);
});

test('BFCache 挂起释放请求和计时器，恢复后补取缺失字典并更新原绑定', async () => {
    const h = harness();
    h.fail = true;
    const client = await h.create();
    const label = { textContent: 'Fallback', getAttribute: name => name === 'data-i18n' ? 'title' : null };
    client.apply({querySelectorAll: selector => selector === '[data-i18n]' ? [label] : []});
    let updates = 0;
    client.onUpdate(() => updates++);
    h.hold = () => true;
    h.emit('focus');
    const pending = h.calls.at(-1);
    h.emit('pagehide', {persisted: true});
    assert.equal(pending.signal.aborted, true);
    await flush();
    const count = h.calls.length;
    h.emit('online'); h.emit('focus');
    assert.equal(h.calls.length, count);
    assert.equal(h.timers.size, 0);
    h.hold = () => false;
    h.fail = false;
    h.emit('pageshow', {persisted: true});
    h.emit('online'); h.emit('focus');
    await flush();
    assert.equal(h.calls.length, count + 1);
    assert.equal(label.textContent, 'en-US');
    assert.equal(updates, 1);
    assert.equal(h.timers.size, 0);
    for (const name of ['focus', 'online', 'pagehide', 'pageshow']) assert.equal(h.events.get(name)?.size || 0, 0);
});

test('BFCache 恢复不会复活已销毁的旧语言客户端，离线重试仍有界', async () => {
    const h = harness();
    h.fail = true;
    const old = await h.create();
    h.emit('pagehide', {persisted: true});
    old.dispose();
    h.fail = false;
    const current = await h.create({lang: 'zh-CN'});
    const count = h.calls.length;
    h.emit('pageshow', {persisted: true}); h.emit('online'); h.emit('focus');
    await flush();
    assert.equal(h.calls.length, count);
    assert.equal(current.t('title'), 'zh-CN');
    assert.equal(old.has('title'), false);
    h.fail = true;
    const offline = await h.create();
    h.emit('pagehide', {persisted: true});
    h.emit('pageshow', {persisted: true});
    await flush();
    for (const delay of [1500, 5000, 15000]) { h.runTimer(delay); await flush(); }
    assert.equal(h.timers.size, 0);
    offline.dispose();
});

test('无 AbortController 时 BFCache 恢复拒绝旧响应且不清除新一轮请求状态', async () => {
    const h = harness(false);
    h.fail = true;
    const client = await h.create();
    let updates = 0;
    client.onUpdate(() => updates++);
    h.hold = () => true;
    h.emit('online');
    const old = h.calls.at(-1);
    h.emit('pagehide', {persisted: true});
    h.emit('pageshow', {persisted: true});
    const current = h.calls.at(-1);
    assert.notEqual(old, current);
    old.finish(); await flush();
    assert.equal(client.has('title'), false);
    const count = h.calls.length;
    h.emit('focus');
    assert.equal(h.calls.length, count);
    current.finish(); await flush();
    assert.equal(client.t('title'), 'en-US');
    assert.equal(updates, 1);
    assert.equal(h.timers.size, 0);
});

test('请求超时仍允许静态回退，取消一个客户端不影响其它客户端', async () => {
    const h = harness();
    h.hold = url => url.includes('/messages/common');
    const pending = h.create();
    await flush();
    h.runTimer(10000);
    const client = await pending;
    assert.equal(client.t('title'), 'en-US');
    assert.ok(h.calls.some(call => call.url.startsWith('i18n-static/common.')));
    h.hold = () => true;
    const controller = new AbortController();
    const first = h.create({ signal: controller.signal }).catch(error => error);
    const second = h.create();
    const otherRequest = h.calls.at(-1);
    controller.abort();
    assert.equal((await first).name, 'AbortError');
    assert.equal(otherRequest.signal.aborted, false);
    h.hold = () => false;
    otherRequest.finish();
    assert.equal((await second).t('title'), 'en-US');
    assert.equal(h.timers.size, 0);
});

test('无 AbortController 时销毁仍拒绝迟到结果和后续回退', async () => {
    const h = harness(false);
    h.fail = true;
    const client = await h.create();
    h.hold = () => true;
    h.emit('online');
    const count = h.calls.length;
    client.dispose();
    h.calls.at(-1).finish();
    await flush();
    assert.equal(h.calls.length, count);
    assert.equal(client.has('title'), false);
    assert.equal(h.timers.size, 0);
});

test('语言控件替换、刷新和销毁取消各自的在途请求，最新选择正常生效', async () => {
    const h = harness();
    const initial = await h.create();
    const changed = [];
    const mounted = await h.window.PixivLangSwitcher.mount({ mountPoint: element(), i18n: initial, onChange: client => changed.push(client.lang) });
    const select = mounted.element.children[0];
    function choose(lang) { select.value = lang; select.events.change(); }
    h.hold = () => true;
    choose('zh-CN');
    const old = h.calls.at(-1);
    choose('en-US');
    assert.equal(old.signal.aborted, true);
    h.hold = () => false;
    h.calls.at(-1).finish();
    await flush();
    assert.deepEqual(changed, ['en-US']);
    assert.equal(select.value, 'en-US');
    h.hold = () => true;
    choose('zh-CN');
    const refreshed = h.calls.at(-1);
    mounted.refresh();
    assert.equal(refreshed.signal.aborted, true);
    choose('zh-CN');
    const destroyed = h.calls.at(-1);
    mounted.destroy();
    assert.equal(destroyed.signal.aborted, true);
    await flush();
    assert.deepEqual(changed, ['en-US']);
    assert.equal(h.warnings.length, 0);
    assert.equal(h.timers.size, 0);
});
