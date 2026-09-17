'use strict';

const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const staticRoot = path.join(__dirname, '../../main/resources/static');

// Vue 原生自定义 renderer：保留真实响应式渲染和表单指令，仅替代 DOM 存储。
function node(tag, text = '') {
    return {
        tagName: tag.toUpperCase(), text, children: [], props: {}, listeners: {}, parent: null,
        get options() { return this.children; },
        addEventListener(name, handler) { this.listeners[name] = handler; },
        dispatchEvent(event) { this.listeners[event.type]?.(event); }
    };
}

function remove(child) {
    if (child.parent) child.parent.children.splice(child.parent.children.indexOf(child), 1);
    child.parent = null;
}

function elements(root, cls) {
    const found = [];
    function visit(n) {
        if ((n.props.class || '').split(' ').includes(cls)) found.push(n);
        n.children.forEach(visit);
    }
    visit(root);
    return found;
}

function textOf(n) { return n.text + n.children.map(textOf).join(''); }

function entry(id, { category = 'utility', defaultInstalled = false } = {}) {
    return {
        pluginId: id, latestVersion: '2.0.0', compatible: true, installStatus: 'NOT_INSTALLED',
        market: { displayName: { en: id }, summary: { en: '<img src=x onerror=alert(1)>' },
            category, defaultInstalled, sourceType: 'official', tags: ['tag'], rating: 4.5 },
        packages: ['2.0.0', '1.0.0'].map(version => ({ version, compatible: true,
            verification: { status: 'VERIFIED_OFFICIAL' }, dependencies: [], changeNotes: ['A change'] }))
    };
}

async function mountMarket() {
    const errors = [];
    const document = { createElement: tag => node(tag), addEventListener() {}, removeEventListener() {}, body: { style: {} } };
    const sandbox = { document, console: { warn: (...args) => errors.push(args), error: (...args) => errors.push(args) } };
    sandbox.window = sandbox;
    vm.createContext(sandbox, { codeGeneration: { strings: false, wasm: false } });
    // 确认测试环境确实拒绝 Vue 字符串模板编译需要的 new Function。
    assert.throws(() => vm.runInContext('new Function("return 1")', sandbox), /Code generation/);
    for (const resource of ['vendor/vue/vue.global.prod.js', 'js/pixiv-plugin-presentation-tokens.js',
        'plugin-market/plugin-market-core.js', 'plugin-market/plugin-market-data.js', 'plugin-market/plugin-market-vue.js']) {
        vm.runInContext(fs.readFileSync(path.join(staticRoot, resource), 'utf8'), sandbox, { filename: resource });
    }
    const Vue = sandbox.Vue;
    const renderer = Vue.createRenderer({
        createElement: tag => node(tag), createText: text => node('#text', text), createComment: () => node('#comment'),
        setText(n, text) { n.text = text; },
        setElementText(n, text) { n.text = text; n.children = []; },
        parentNode: n => n.parent,
        nextSibling: n => n.parent?.children[n.parent.children.indexOf(n) + 1] || null,
        insert(child, parent, anchor = null) {
            remove(child);
            const index = anchor ? parent.children.indexOf(anchor) : parent.children.length;
            assert.ok(index >= 0);
            parent.children.splice(index, 0, child);
            child.parent = parent;
        },
        remove,
        patchProp(n, key, previous, value) {
            assert.notEqual(key, 'innerHTML', '市场外部文本不得作为 HTML 渲染');
            n.props[key] = value;
            if (key === 'value') n.value = value;
        }
    });
    sandbox.PixivVue = { ensure: async () => ({ ...Vue, createApp: renderer.createApp }) };
    const market = sandbox.PixivPluginMarket;
    market.state.i18n.client = { lang: 'en-US', t: (key, fallback, vars) => key + (vars ? JSON.stringify(vars) : '') };
    const entries = [entry('visible'), entry('bundled', { defaultInstalled: true }), entry('dependency', { category: 'dependency' })];
    const catalog = { repositoryId: 'repo', entries, installedCount: 2, categories: [{ category: 'all', count: 3 }] };
    let status = { recoveryMode: false };
    let enabled = true;
    let failCatalog = false;
    let finishInstall;
    const installCalls = [];
    market.api = {
        fetchRepositories: async () => ({ enabled, sdkVersion: '1.0.0', defaultRepositoryId: 'repo',
            repositories: [{ repositoryId: 'repo', enabled: true, official: true }] }),
        fetchPluginStatus: async () => status,
        fetchCatalog: async () => { if (failCatalog) throw new Error('offline'); return catalog; },
        fetchPluginDetail: async (repositoryId, pluginId) => entries.find(e => e.pluginId === pluginId)
    };
    market.installPluginWithConfirmation = (...args) => {
        installCalls.push(args);
        return new Promise(resolve => { finishInstall = resolve; });
    };
    market.toast = () => {};
    const root = node('root');
    assert.equal(await market.vue.tryMount(root), true, JSON.stringify(errors));
    async function flush() { await new Promise(resolve => setImmediate(resolve)); await Vue.nextTick(); }
    await flush();
    return { root, market, errors, document, flush, installCalls,
        completeInstall: body => finishInstall({ kind: 'install', body }),
        async reload(options) {
            if (options.status) status = options.status;
            if ('enabled' in options) enabled = options.enabled;
            if ('failCatalog' in options) failCatalog = options.failCatalog;
            market.state.activeView.reload();
            await flush();
        }
    };
}

test('市场在禁止动态代码编译时挂载，筛选、详情、安装结果与语言切换保持响应', async () => {
    const page = await mountMarket();
    const { root, market, flush } = page;
    const one = cls => { const matches = elements(root, cls); assert.equal(matches.length, 1, cls); return matches[0]; };
    assert.equal(elements(root, 'pmk-card').length, 1);
    assert.equal(textOf(one('pmk-card-name')), 'visible');
    assert.equal(textOf(one('pmk-card-desc')), '<img src=x onerror=alert(1)>');
    assert.equal(textOf(one('pmk-seg-count')), '2');
    const filters = elements(root, 'pmk-switch');
    assert.equal(filters[0].props['aria-label'], 'plugin-market:filter.hide-default-installed');
    assert.equal(filters[1].props['aria-label'], 'plugin-market:filter.hide-dependencies');
    assert.equal(filters[0].props['aria-pressed'], true);
    filters[0].props.onClick();
    await flush();
    assert.equal(elements(root, 'pmk-card').length, 2);
    filters[1].props.onClick();
    await flush();
    assert.equal(elements(root, 'pmk-card').length, 3);

    const search = one('pmk-search').children.find(n => n.tagName === 'INPUT');
    search.value = 'missing';
    search.listeners.input({ target: search });
    await flush();
    assert.equal(elements(root, 'pmk-card').length, 0);
    assert.ok(one('pmk-empty'));
    search.value = 'visible';
    search.listeners.input({ target: search });
    await flush();
    assert.equal(elements(root, 'pmk-card').length, 1);

    one('pmk-card-name').props.onClick();
    await flush();
    assert.equal(page.document.body.style.overflow, 'hidden');
    assert.match(textOf(one('pmk-hero-name')), /visible/);
    const select = one('pmk-version-select');
    assert.equal(select.selectedIndex, 0);
    select.options.forEach(option => { option.selected = option.value === '1.0.0'; });
    select.listeners.change();
    await flush();
    one('pmk-modal-actionbar-right').children.find(n => n.tagName === 'BUTTON').props.onClick();
    await flush();
    assert.deepEqual(page.installCalls, [['repo', 'visible', '1.0.0']]);
    assert.equal(elements(root, 'pmk-install-progress').length, 2);
    page.completeInstall({ outcome: 'INSTALLED', accepted: true, effectiveAfterRestart: true, message: 'Restart needed' });
    await flush();
    assert.equal(elements(root, 'pmk-install-progress').length, 0);
    assert.equal(textOf(one('pmk-install-result-msg')), 'Restart needed');
    assert.ok(one('pmk-install-restart'));

    const modal = one('pmk-modal');
    modal.props.onClick({ target: one('pmk-modal-panel'), currentTarget: modal });
    assert.equal(elements(root, 'pmk-modal').length, 1);
    modal.props.onClick({ target: modal, currentTarget: modal });
    await flush();
    assert.equal(elements(root, 'pmk-modal').length, 0);
    assert.equal(page.document.body.style.overflow, '');
    market.state.i18n.client = { lang: 'en-US', t: key => key === 'plugin-market:page.heading' ? 'Plugin Market' : key };
    market.state.activeView.rerender();
    await flush();
    assert.equal(textOf(one('pmk-title')), 'Plugin Market');
    assert.equal(textOf(one('pmk-toolbar-description')), 'plugin-market:category.all.description');
    assert.deepEqual(page.errors, []);
});

test('无动态编译时仍能显示恢复模式、禁用状态和目录错误', async () => {
    const page = await mountMarket();
    await page.reload({ status: { recoveryMode: true, hostElevated: true,
        recoveryReasons: [{ pluginId: 'required', status: 'MISSING_REQUIRED', messages: [] }] }, enabled: false });
    assert.match(textOf(page.root), /plugin-market:recovery.banner.title/);
    assert.match(textOf(page.root), /required/);
    assert.match(textOf(page.root), /plugin-market:master.disabled.title/);
    assert.match(textOf(page.root), /plugin-market:host.elevated.notice/);
    assert.equal(elements(page.root, 'pmk-body').length, 0);
    await page.reload({ enabled: true, failCatalog: true });
    assert.match(textOf(page.root), /plugin-market:error.catalog.title/);
    assert.equal(elements(page.root, 'pmk-body').length, 0);
    assert.deepEqual(page.errors, []);
});
