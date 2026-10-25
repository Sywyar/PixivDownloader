'use strict';

const assert = require('node:assert/strict');
const { test } = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.resolve(__dirname, '../../main/resources/static');
const tick = () => new Promise(resolve => setImmediate(resolve));
function deferred() {
    let resolve, reject;
    const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
    return { promise, resolve, reject };
}
function report(id, namespace) {
    return { plugins: [{ id, displayNamespace: namespace }] };
}
function client(lang = 'en-US') {
    return { lang, disposed: false, apply() {}, t(key) { return this.lang + ':' + key; },
        dispose() { this.disposed = true; } };
}
async function page() {
    const nodes = new Map(), events = new Map(), requests = [], mounts = [];
    const node = id => {
        if (!nodes.has(id)) nodes.set(id, {
            children: [], disabled: false, innerHTML: '', files: [],
            addEventListener(name, callback) { this[name] = callback; },
            replaceChildren() { this.children = []; }, appendChild(child) { this.children.push(child); },
            querySelectorAll() { return []; }, querySelector() { return null; }, contains() { return false; }
        });
        return nodes.get(id);
    };
    const document = { readyState: 'loading', body: {}, getElementById: node,
        createElement: () => ({}), addEventListener(name, callback) { this[name] = callback; } };
    const context = { document, console, AbortController, setTimeout, clearTimeout,
        addEventListener(name, callback) { events.set(name, callback); },
        PixivActions: { bind() {} },
        PixivNav: { ready: async () => {}, pluginUpdates: async () => {}, refresh() {}, mount() {} },
        PixivI18n: { create: async options => client(options.lang) },
        PixivLangSwitcher: { async mount(options) {
            const handle = { options, client: options.i18n, refresh(next) {
                this.client.dispose(); this.client = next;
            } };
            mounts.push(handle); return handle;
        } },
        PixivTheme: { mount() {} },
        fetch: async (url, options) => {
            requests.push({ url, options });
            return { ok: true, json: async () => report('initial') };
        }
    };
    context.window = context;
    vm.createContext(context);
    for (const file of ['js/pixiv-plugin-presentation-tokens.js', 'plugin-manage/plugin-manage-core.js',
        'plugin-manage/plugin-manage-api.js', 'plugin-manage/plugin-manage-views.js']) {
        vm.runInContext(fs.readFileSync(path.join(root, file), 'utf8'), context, { filename: file });
    }
    const PM = context.PixivPluginManage;
    const render = PM.renderAll;
    PM.renderAll = () => {};
    PM.toast = () => {};
    vm.runInContext(fs.readFileSync(path.join(root, 'plugin-manage/plugin-manage-init.js'), 'utf8'), context);
    await document.DOMContentLoaded();
    return { context, PM, nodes, events, requests, mounts, render, refresh: () => node('refreshBtn').click(), node };
}

test('状态正文乱序完成时保留最新报告', async () => {
    const p = await page(), old = deferred();
    p.context.fetch = async () => ({ ok: true, json: () => old.promise });
    p.refresh(); await tick();
    p.context.fetch = async () => ({ ok: true, json: async () => report('current') });
    p.refresh(); await tick();
    old.resolve(report('obsolete')); await tick();
    assert.equal(p.PM.state.report.plugins[0].id, 'current');
    assert.equal(p.PM.state.error, null);
});

test('旧请求失败不能结束当前加载或发布错误', async () => {
    const p = await page(), old = deferred(), current = deferred();
    p.context.fetch = () => old.promise;
    p.refresh(); await tick();
    p.context.fetch = () => current.promise;
    p.refresh(); await tick();
    old.reject(new Error('late failure')); await tick();
    assert.equal(p.PM.state.loading, true);
    assert.equal(p.PM.state.error, null);
    current.resolve({ ok: true, json: async () => report('current') }); await tick();
    assert.equal(p.PM.state.loading, false);
});

test('离页取消读取，恢复重新查询并保留搜索条件', async () => {
    const p = await page(), body = deferred();
    let signal;
    p.context.fetch = async (url, options) => {
        signal = options.signal; return { ok: true, json: () => body.promise };
    };
    p.PM.state.search = 'draft';
    p.refresh(); await tick();
    assert.equal(typeof p.events.get('pagehide'), 'function');
    p.events.get('pagehide')();
    assert.equal(signal.aborted, true);
    body.resolve(report('hidden')); await tick();
    assert.equal(p.PM.state.report.plugins[0].id, 'initial');
    p.context.fetch = async () => ({ ok: true, json: async () => report('returned') });
    p.events.get('pageshow')({ persisted: true }); await tick();
    assert.equal(p.PM.state.report.plugins[0].id, 'returned');
    assert.equal(p.PM.state.search, 'draft');
});

test('被替代的词典创建结果释放，当前切换器只挂载一次', async () => {
    const p = await page(), old = deferred(), obsolete = client();
    p.context.PixivI18n.create = options => options.namespaces.includes('old') ? old.promise : Promise.resolve(client());
    p.context.fetch = async () => ({ ok: true, json: async () => report('old', 'old') });
    p.refresh(); await tick();
    p.context.fetch = async () => ({ ok: true, json: async () => report('new', 'new') });
    p.refresh(); await tick();
    const installed = p.PM.i18n.client;
    old.resolve(obsolete); await tick();
    assert.equal(obsolete.disposed, true);
    assert.equal(p.PM.i18n.client, installed);
    assert.equal(p.PM.state.report.plugins[0].id, 'new');
    assert.equal(p.mounts.length, 1);
});

test('扩展词典期间切换语言会释放旧结果并按新语言重建', async () => {
    const p = await page(), old = deferred(), stale = client(), next = client('ja-JP');
    const languages = [];
    p.context.PixivI18n.create = options => {
        languages.push(options.lang);
        return languages.length === 1 ? old.promise : Promise.resolve(client(options.lang));
    };
    p.context.fetch = async () => ({ ok: true, json: async () => report('extra', 'extra') });
    p.refresh(); await tick();
    p.mounts[0].options.onChange(next);
    old.resolve(stale); await tick();
    assert.equal(stale.disposed, true);
    assert.equal(p.PM.i18n.client.lang, 'ja-JP');
    assert.deepEqual(languages, ['en-US', 'ja-JP']);
});

test('安装期间锁定当前选择，完成后刷新状态与导航', async () => {
    const p = await page(), result = deferred();
    let refreshes = 0, navigation = 0;
    p.node('pm-install-file').files = [{ name: 'sample.jar' }];
    p.PM.installPackageWithConfirmation = () => result.promise;
    p.context.fetch = async () => { refreshes++; return { ok: true, json: async () => report('installed') }; };
    p.context.PixivNav.mount = () => { navigation++; };
    p.node('pm-install-submit').click(); await tick();
    for (const id of ['pm-install-submit', 'pm-install-file', 'pm-install-signature', 'pm-install-allow-downgrade']) {
        assert.equal(p.node(id).disabled, true, id);
    }
    result.resolve({ outcome: 'INSTALLED', accepted: true, activated: true }); await tick();
    assert.equal(refreshes, 1);
    assert.equal(navigation, 1);
    assert.equal(p.PM.state.report.plugins[0].id, 'installed');
    assert.equal(p.node('pm-install-file').disabled, false);
});

test('信任失败保留后端本地化错误和稳定机器码', async () => {
    const p = await page();
    p.context.fetch = async () => ({ ok: false, status: 409,
        json: async () => ({ code: 'TRUST_UPDATE_FAILED', error: 'Artifact changed' }) });
    await assert.rejects(p.PM.approveTrust('sample', 'a'.repeat(64)), error =>
        error.message === 'Artifact changed' && error.code === 'TRUST_UPDATE_FAILED' && error.httpStatus === 409);
});

test('本地安装提示随语言重新渲染', async () => {
    const p = await page();
    p.node('pm-install-submit').click(); await tick();
    const first = p.node('pm-install-result').innerHTML;
    assert.ok(first.includes('en-US:plugins:install.choose-file'));
    // 使用实际视图刷新入口，当前表单的模型仍由生产模块持有。
    p.PM.i18n.client = client('ja-JP');
    p.PM.state.report = { plugins: [] };
    p.render();
    assert.ok(p.node('pm-install-result').innerHTML.includes('ja-JP:plugins:install.choose-file'));
});
