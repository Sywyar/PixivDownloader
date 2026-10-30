import {test} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const source = fs.readFileSync(new URL('../../../pixivdownload-app/src/main/resources/static/js/pixiv-ui-slots.js', import.meta.url), 'utf8');
const turn = () => new Promise(resolve => setImmediate(resolve));
function environment(factory) {
    class Element extends EventTarget {
        children = []; dataset = {};
        append(node) { node.remove(); node.parent = this; this.children.push(node); }
        get parentNode() { return this.parent; }
        get nextSibling() { return this.parent?.children[this.parent.children.indexOf(this) + 1] || null; }
        insertBefore(node, next) {
            node.remove(); node.parent = this;
            if (next) this.children.splice(this.children.indexOf(next), 0, node); else this.children.push(node);
        }
        remove() { if (this.parent) this.parent.children = this.parent.children.filter(node => node !== this); this.parent = null; }
        replaceChildren(...nodes) { this.children.forEach(node => { node.parent = null; }); this.children = []; nodes.forEach(node => this.append(node)); }
        getAttribute(name) { return name === 'data-ui-slot' ? 'example' : null; }
    }
    const anchor = new Element(), document = {hidden: false, createElement: () => new Element(),
        querySelectorAll: () => [anchor], head: new Element()};
    const window = new EventTarget(), intervals = new Map();
    let timer = 0, manifest = [], calls = [], manifestFetch;
    const context = vm.createContext({window, document, location: {origin: 'http://unit.test'}, URL,
        Object, JSON, Request, Headers, Response, AbortController, AbortSignal, DOMException, console, setTimeout, clearTimeout,
        setInterval(fn) { intervals.set(++timer, fn); return timer; }, clearInterval(id) { intervals.delete(id); },
        fetch: async (input, init) => {
            if (new URL(input.url || input, 'http://unit.test').pathname === '/api/web/ui-slots')
                return manifestFetch ? manifestFetch() : Response.json(manifest);
            calls.push({url: input.url, publication: init.headers.get('X-Pixiv-Ui-Publication')});
            return Response.json({ok: true});
        }});
    vm.runInContext(source, context);
    const append = document.head.append.bind(document.head);
    document.head.append = script => {
        append(script);
        queueMicrotask(() => {
            if (!script.parent) return;
            document.currentScript = script;
            window.PixivUiSlots.registerModule('/module.js', factory);
            document.currentScript = null;
            script.onload();
        });
    };
    const slot = publication => ({slotId: 'example.one', target: 'example', moduleUrl: '/module.js', publication});
    return {window, anchor, intervals, calls, slot,
        setManifest: token => { manifest = Array.isArray(token) ? token : token ? [slot(token)] : []; },
        setManifestFetch: fetch => { manifestFetch = fetch; }};
}

test('普通槽位复用不变实例，更新属性并在撤回和换代时取消旧上下文', async () => {
    const seen = [], closed = [], updated = [];
    const env = environment((root, context) => {
        seen.push(context);
        context.interval(() => {}, 100);
        context.onCleanup(() => closed.push('cleanup:' + context.publication));
        return {update(value) { updated.push(value.value); }, destroy() { closed.push('destroy:' + context.publication); }};
    });
    env.setManifest('one');
    const page = env.window.PixivUiSlots.connect({properties: {value: 1}});
    await page.refresh(); await page.refresh();
    assert.equal(seen.length, 1);
    await page.update({value: 2}); assert.deepEqual(updated, [2]);
    await seen[0].fetch('/business');
    assert.equal(env.calls[0].publication, 'one');
    env.setManifest('two'); await page.refresh();
    assert.equal(seen.length, 2);
    assert.equal(seen[0].signal.aborted, true);
    await assert.rejects(seen[0].fetch('/business'), {name: 'AbortError'});
    assert.equal(env.calls.length, 1);
    assert.deepEqual(closed, ['cleanup:one', 'destroy:one']);
    assert.equal(env.anchor.children.length, 1);
    env.setManifest(null); await page.refresh();
    assert.equal(env.anchor.children.length, 0);
    await page.destroy(); await page.destroy();
    assert.deepEqual(closed, ['cleanup:one', 'destroy:one', 'cleanup:two', 'destroy:two']);
    assert.equal(env.intervals.size, 0);
});

test('迟到挂载句柄只销毁旧根，不覆盖替代实例', async () => {
    let release, entered;
    const ready = new Promise(resolve => { entered = resolve; }), stopped = [];
    const env = environment(async (root, context) => {
        if (context.publication === 'old') { entered(); await new Promise(resolve => { release = resolve; }); }
        return {destroy() { stopped.push(context.publication); }};
    });
    env.setManifest('old');
    const page = env.window.PixivUiSlots.connect();
    const first = page.refresh(); await ready;
    env.setManifest('new'); await page.refresh();
    assert.equal(env.anchor.children.length, 1);
    release(); await first; await turn();
    assert.deepEqual(stopped, ['old']);
    assert.equal(env.anchor.children.length, 1);
    await page.destroy(); assert.deepEqual(stopped, ['old', 'new']);
    assert.equal(env.intervals.size, 0);
});

test('三轮交错刷新不会由旧工厂清除另一请求或销毁最新实例', async () => {
    const factories = new Map(), responses = [], destroyed = [];
    const env = environment((root, context) => new Promise(resolve => {
        factories.set(context.publication, () => resolve({destroy() { destroyed.push(context.publication); }}));
    }));
    env.setManifestFetch(() => new Promise(resolve => responses.push(resolve)));
    const page = env.window.PixivUiSlots.connect();
    try {
        const first = page.refresh(); await turn();
        responses[0](Response.json([env.slot('one')])); await turn();
        const second = page.refresh(); await turn();
        factories.get('one')(); await first;
        const overlapping = page.refresh(); await turn();
        assert.equal(overlapping, second);
        assert.equal(responses.length, 2);
        responses[1](Response.json([env.slot('two')])); await turn();
        const third = page.refresh(); await turn();
        responses[2](Response.json([env.slot('three')])); await turn();
        factories.get('three')(); await third;
        const newest = env.anchor.children[0];
        factories.get('two')(); await second;
        assert.equal(env.anchor.children[0], newest);
        assert.equal(env.anchor.children.length, 1);
        assert.deepEqual(destroyed, ['one', 'two']);
    } finally { await page.destroy(); }
    assert.deepEqual(destroyed, ['one', 'two', 'three']);
});

test('低 order 贡献重新启用和插入时重排 DOM，保留已有实例', async () => {
    const mounted = [], destroyed = [];
    const env = environment((root, context) => {
        mounted.push(context.slotId);
        return {destroy() { destroyed.push(context.slotId); }};
    });
    const slot = (id, order) => ({...env.slot(id), slotId: id, order});
    const a = slot('a', 10), b = slot('b', 20), c = slot('c', 15);
    const page = env.window.PixivUiSlots.connect();
    const order = () => env.anchor.children.map(node => node.dataset.uiInstance);
    try {
        env.setManifest([a, b]); await page.refresh();
        const originalB = env.anchor.children[1];
        assert.deepEqual(order(), ['a', 'b']);
        env.setManifest([b]); await page.refresh();
        env.setManifest([a, b]); await page.refresh();
        assert.deepEqual(order(), ['a', 'b']);
        env.setManifest([a, c, b]); await page.refresh();
        assert.deepEqual(order(), ['a', 'c', 'b']);
        assert.equal(env.anchor.children[2], originalB);
        assert.deepEqual(mounted, ['a', 'b', 'a', 'c']);
        assert.deepEqual(destroyed, ['a']);
    } finally { await page.destroy(); }
});

test('并行页面共享模块加载，单个实例销毁不撤销另一个实例', async () => {
    const mounted = [], disposed = [];
    const env = environment((root, scope) => {
        mounted.push(scope);
        return {destroy() { disposed.push(scope); }};
    });
    env.setManifest('shared');
    const first = env.window.PixivUiSlots.connect(), second = env.window.PixivUiSlots.connect();
    await Promise.all([first.refresh(), second.refresh()]);
    assert.equal(mounted.length, 2);
    await first.destroy();
    assert.equal(disposed.length, 1);
    await mounted[1].fetch('/business');
    await second.refresh();
    assert.equal(mounted.length, 2);
    await second.destroy();
    assert.equal(disposed.length, 2);
    assert.equal(env.intervals.size, 0);
});
