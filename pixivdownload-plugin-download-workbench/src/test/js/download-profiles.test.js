'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {MiniElement} = require('./pixiv-layout-feedback-test-dom');

function setup() {
    const context = vm.createContext({window: {}, console});
    for (const file of ['search-filters.js', 'download-profiles.js']) {
        vm.runInContext(fs.readFileSync(path.join(__dirname, '../../main/resources/static/pixiv-batch', file), 'utf8'), context);
    }
    return context.window.PixivDownloadProfiles;
}

test('方案创建关闭提示框后恢复操作焦点，刷新失败仍可重试', async () => {
    const document = {};
    const body = new MiniElement('main', document);
    document.body = body;
    document.createElement = tag => {
        const node = new MiniElement(tag, document);
        node.append = (...children) => children.forEach(child => node.appendChild(child));
        node.replaceChildren = (...children) => { node.textContent = ''; node.append(...children); };
        Object.defineProperty(node, 'isConnected', {get: () => body.contains(node)});
        return node;
    };
    const root = document.createElement('section');
    body.appendChild(root);
    let selected = '', failRead = false;
    let catalog = {revision: '0', profiles: []};
    const context = vm.createContext({window: {}, document, isAdmin: true, BASE: '', AbortController, AbortSignal,
        crypto: require('node:crypto').webcrypto,
        fetch: async (_url, options) => {
            if (options.method === 'PUT') catalog = JSON.parse(options.body);
            return {ok: options.method === 'PUT' || !failRead, status: 503, json: async () => catalog};
        }});
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../../main/resources/static/pixiv-batch/download-profiles.js'), 'utf8'), context);
    context.window.PixivDownloadProfiles.mount(root, {
        context: () => 'manual', selected: () => selected, select: id => { selected = id; },
        capture: () => ({settings: {}, filters: {}}), translate: key => key, buttonClass: '', primaryButtonClass: '',
        prompt: async () => { document.activeElement = body; return 'Daily'; }
    });
    const settle = () => new Promise(resolve => setImmediate(resolve));
    await settle();
    const create = root.querySelector('[data-i18n="batch:profiles.new"]');
    create.focus();
    create.click();
    await settle();
    assert.equal(catalog.profiles[0].name, 'Daily');
    assert.equal(document.activeElement, create);
    failRead = true;
    const reload = root.querySelector('[data-i18n="batch:profiles.reload"]');
    reload.click();
    await settle();
    assert.equal(root.querySelector('[role="status"]').textContent, 'profiles.failed');
    assert.equal(reload.disabled, false);
    failRead = false;
    reload.click();
    await settle();
    assert.equal(root.querySelector('[role="status"]').textContent, '');
    assert.equal(root.querySelector('select').value, selected);
});

test('方案只复制下载参数和筛选，不携带凭据、队列、收藏夹或来源状态', () => {
    const api = setup();
    const settings = {interval: 3, concurrent: 2, cookie: 'secret', collectionId: 17, userKind: 'novel',
        fileNameTemplates: ['a'], imageFormats: 'jpg'};
    const contributions = [{profileSettings: s => ({imageFormats: s.imageFormats})}];
    const captured = api.capture(settings, {tagsExact: ['tag']}, contributions);
    assert.deepEqual(Object.keys(captured.settings).sort(), ['concurrent', 'imageFormats', 'interval']);
    settings.interval = 99;
    assert.equal(captured.settings.interval, 3);
    captured.filters.tagsExact.push('another');
    assert.equal(api.capture(settings, {tagsExact: ['tag']}, contributions).filters.tagsExact.length, 1);
});

test('应用前完整校验，未知能力或非法参数不部分修改当前设置', () => {
    const api = setup();
    const live = {interval: 2, concurrent: 1, intervalUnit: 's'};
    for (const settings of [{concurrent: 0}, {interval: -1}, {intervalUnit: 'day'}, {cookie: 'secret'}]) {
        assert.throws(() => api.prepare({settings, filters: {}}, live, []));
        assert.equal(live.concurrent, 1);
    }
    const profile = {settings: {concurrent: 4, interval: 0}, filters: {content: 'safe'}};
    const prepared = api.prepare(profile, live, []);
    Object.assign(live, prepared.settings);
    assert.equal(live.concurrent, 4);
    profile.settings.concurrent = 10;
    assert.equal(live.concurrent, 4);
});

test('增强设置随精确插件生命周期撤回，不将未加载插件的方案误应用', () => {
    const api = setup();
    let active = true;
    let cleanup;
    api.registerSettings({isActive: () => active, onCleanup: fn => { cleanup = fn; }}, {outputLanguage: ''});
    const profile = api.capture({outputLanguage: 'en'}, {}, []);
    assert.equal(profile.settings.outputLanguage, 'en');
    active = false;
    cleanup();
    assert.throws(() => api.prepare(profile, {}, []), /profiles.unavailable/);
});
