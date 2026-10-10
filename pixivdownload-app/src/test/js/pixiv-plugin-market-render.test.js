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
        showModal() { this.open = true; },
        addEventListener(name, handler) { this.listeners[name] = handler; },
        replaceChildren(...children) { this.children = children; },
        getAttribute(name) { return this.props[name] || null; },
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

async function mountMarket({ community = false, revocation = null, compatibleOlder = false, dependencies = [], failed = false } = {}) {
    const errors = [];
    const document = { documentElement: node('html'), createElement: tag => node(tag), addEventListener() {}, removeEventListener() {}, body: { style: {} } };
    const sandbox = { document, URL, addEventListener() {}, removeEventListener() {}, console: { warn: (...args) => errors.push(args), error: (...args) => errors.push(args) } };
    sandbox.window = sandbox;
    vm.createContext(sandbox, { codeGeneration: { strings: false, wasm: false } });
    // 确认测试环境确实拒绝 Vue 字符串模板编译需要的 new Function。
    assert.throws(() => vm.runInContext('new Function("return 1")', sandbox), /Code generation/);
    for (const resource of ['vendor/vue/vue.global.prod.js', 'js/pixiv-plugin-presentation-tokens.js',
        'plugin-market/plugin-market-core.js', 'plugin-market/plugin-market-data.js', 'plugin-market/plugin-market-api.js',
        'plugin-market/plugin-market-content.js', 'plugin-market/plugin-market-vue.js']) {
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
    if (failed) entries[1].installation = {state: 'PRESENT', runtimeStatus: 'FAILED'};
    entries[0].packages.forEach(pkg => { pkg.dependencies = dependencies; });
    entries[2].market.displayName = {en: 'Notifications', 'zh-CN': '通知'};
    if (compatibleOlder) {
        entries[0].recommendedVersion = '1.0.0';
        entries[0].compatibilityReason = '999.0';
        entries[0].packages[0].compatible = false;
    }
    if (community) {
        entries[0].assuranceLevel = 'SOURCE_REVIEWED';
        entries[0].market.sourceType = 'community';
        entries[0].packages.forEach(pkg => {
            pkg.verification = { status: 'VERIFIED_COMMUNITY', repositoryTrustSource: 'COMMUNITY',
                assuranceLevel: pkg.version === '2.0.0' ? 'SOURCE_REVIEWED' : 'PUBLISHER_SIGNED' };
        });
    }
    const catalog = { repositoryId: 'repo', entries, installedCount: 2, categories: [{ category: 'all', count: 3 }] };
    if (revocation) {
        entries[0].packages[1].verification.revocationStatus = revocation;
        entries[0].packages[1].installable = false;
    }
    let status = { recoveryMode: false };
    let enabled = true;
    let failCatalog = false;
    let finishInstall;
    const installCalls = [];
    const factCalls = [];
    market.api = {
        ...market.api,
        fetchRepositories: async () => ({ enabled, sdkVersion: '1.0.0', defaultRepositoryId: 'repo',
            repositories: [{ repositoryId: 'repo', enabled: true, official: true }] }),
        fetchPluginStatus: async () => status,
        fetchCatalog: async () => { if (failCatalog) throw new Error('offline'); return catalog; },
        fetchPluginDetail: async (repositoryId, pluginId) => entries.find(e => e.pluginId === pluginId),
        fetchPackageFacts: async (repositoryId, pluginId, version) => {
            factCalls.push([repositoryId, pluginId, version]);
            const pkg = entries.find(e => e.pluginId === pluginId).packages.find(p => p.version === version);
            return { ...pkg.verification, executionMode: 'declarative-process', revocationStatus: pkg.verification.revocationStatus || 'CLEAR',
                riskDeclaration: { present: true, signals: version === '2.0.0' ? ['network'] : [] } };
        }
    };
    let progress;
    market.installPluginWithConfirmation = (...args) => {
        installCalls.push(args.slice(0, 3));
        assert.equal(typeof args[3], 'function');
        progress = args[3];
        args[3]('confirm');
        return new Promise(resolve => { finishInstall = resolve; });
    };
    market.toast = () => {};
    const root = node('root');
    assert.equal(await market.vue.tryMount(root), true, JSON.stringify(errors));
    async function flush() { await new Promise(resolve => setImmediate(resolve)); await Vue.nextTick(); }
    await flush();
    return { root, market, errors, document, flush, installCalls, factCalls,
        progress: phase => progress(phase),
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
    assert.equal(textOf(one('pmk-hero-summary')), '<img src=x onerror=alert(1)>', '用途摘要必须以纯文本显示');
    const select = one('pmk-version-select');
    assert.equal(select.selectedIndex, 0);
    select.options.forEach(option => { option.selected = option.value === '1.0.0'; });
    select.listeners.change();
    await flush();
    one('pmk-modal-actionbar-right').children.find(n => n.tagName === 'BUTTON').props.onClick();
    await flush();
    assert.deepEqual(page.installCalls, [['repo', 'visible', '1.0.0']]);
    assert.equal(elements(root, 'pmk-install-progress').length, 2);
    elements(root, 'pmk-install-progress').forEach(node => assert.match(textOf(node), /install.phase.confirm/));
    for (const phase of ['PREPARING', 'DOWNLOADING', 'INSTALLING', 'ROLLING_BACK']) {
        page.progress(phase);
        await flush();
        elements(root, 'pmk-install-progress').forEach(node =>
            assert.ok(textOf(node).includes('operations.state.' + phase)));
    }
    assert.equal(elements(root, 'pmk-progress-bar').length, 0);
    assert.equal(one('pmk-modal').open, true);
    page.completeInstall({ pluginId: 'visible', version: '1.0.0', outcome: 'INSTALLED', accepted: true, effectiveAfterRestart: true, message: 'Restart needed' });
    await flush();
    assert.equal(elements(root, 'pmk-install-progress').length, 0);
    assert.equal(textOf(one('pmk-install-result-msg')), 'plugin-market:install.toast.accepted');
    assert.match(textOf(one('pmk-install-result-box')), /Restart needed/);
    assert.ok(one('pmk-install-restart'));
    const manageLink = one('pmk-modal-actionbar-right').children.find(n => n.tagName === 'A');
    assert.equal(manageLink.props.href, '/plugin-manage.html', '安装后提供管理入口');
    assert.equal(one('pmk-modal-actionbar-right').children.find(n => n.tagName === 'BUTTON').props.disabled, true, '不能重复安装已安装版本');

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

test('卡片与详情默认安装兼容旧版，兼容筛选保留条目且仍可手动查看最新版', async () => {
    const page = await mountMarket({compatibleOlder: true});
    const {root, flush} = page;
    assert.match(textOf(root), /compat.fallback/);
    elements(root, 'pmk-switch')[3].props.onClick();
    await flush();
    assert.equal(elements(root, 'pmk-card').length, 1);
    elements(root, 'pmk-card-name')[0].props.onClick();
    await flush();
    const select = elements(root, 'pmk-version-select')[0];
    assert.equal(select.selectedIndex, 1);
    assert.equal(page.factCalls.at(-1)[2], '1.0.0');
    const install = () => elements(root, 'pmk-modal-actionbar-right')[0].children.find(n => n.tagName === 'BUTTON');
    select.options.forEach(option => { option.selected = option.value === '2.0.0'; });
    select.listeners.change();
    await flush();
    assert.equal(install().props.disabled, true);
    select.options.forEach(option => { option.selected = option.value === '1.0.0'; });
    select.listeners.change();
    await flush();
    install().props.onClick();
    await flush();
    assert.deepEqual(page.installCalls, [['repo', 'visible', '1.0.0']]);
    assert.deepEqual(page.errors, []);
});

test('覆盖安装后刷新打开详情的本机摘要与按钮，并保留选中的历史版本', async () => {
    const page = await mountMarket();
    const plugin = entry('visible');
    plugin.versionsGeneration = 'fixture-generation';
    plugin.installedVersion = '1.0.0';
    plugin.installation = {state: 'PRESENT', version: '1.0.0', sha256: 'b'.repeat(64),
        runtimeStatus: 'STARTED', installedArtifactsEnabled: true};
    plugin.packages[1].sha256 = 'a'.repeat(64);
    plugin.packages[1].installationMatch = 'DIFFERENT_ARTIFACT';
    let current = plugin;
    page.market.api.fetchPluginDetail = async () => structuredClone(current);
    page.market.api.fetchCatalog = async () => ({repositoryId: 'repo', entries: [structuredClone(current)], categories: []});
    elements(page.root, 'pmk-card-name')[0].props.onClick();
    await page.flush();
    const select = () => elements(page.root, 'pmk-version-select')[0];
    select().options.forEach(option => { option.selected = option.value === '1.0.0'; });
    select().listeners.change();
    await page.flush();
    const button = () => elements(page.root, 'pmk-modal-actionbar-right')[0].children.find(n => n.tagName === 'BUTTON');
    button().props.onClick();
    await page.flush();
    current = structuredClone(plugin);
    current.installation.sha256 = 'a'.repeat(64);
    current.packages = [current.packages[0]];
    page.completeInstall({pluginId: 'visible', version: '1.0.0', outcome: 'INSTALLED', accepted: true, activated: true});
    await page.flush();
    await page.flush();
    assert.equal(button().props.disabled, true);
    assert.match(textOf(button()), /install.state.activated/);
    const modalText = textOf(elements(page.root, 'pmk-modal')[0]);
    assert.ok(modalText.includes('a'.repeat(64)));
    assert.ok(!modalText.includes('b'.repeat(64)));
    assert.equal(select().options[select().selectedIndex].value, '1.0.0');
    current.installation.sha256 = 'b'.repeat(64);
    current.packages = plugin.packages;
    const modal = elements(page.root, 'pmk-modal')[0];
    modal.props.onClick({target: modal, currentTarget: modal});
    await page.flush();
    elements(page.root, 'pmk-card-name')[0].props.onClick();
    await page.flush();
    select().options.forEach(option => { option.selected = option.value === '1.0.0'; });
    select().listeners.change();
    await page.flush();
    assert.equal(!!button().props.disabled, false, '新的本机包差异不能被旧成功回执覆盖');
    assert.match(textOf(button()), /install.action.selected-package/);
    assert.deepEqual(page.errors, []);
});

test('恢复查询变化后，已打开详情的安装按钮跟随当前阻断状态双向更新', async () => {
    for (const initial of [true, false]) {
        const page = await mountMarket();
        let blocked = initial;
        page.market.api.fetchRecovery = async () => ({active: true, installationBlocked: blocked});
        vm.runInNewContext(fs.readFileSync(path.join(staticRoot, 'plugin-market/plugin-market-recovery.js'), 'utf8'),
            {window: {PixivPluginMarket: page.market}});
        await page.market.recovery.refresh();
        elements(page.root, 'pmk-card-name')[0].props.onClick();
        await page.flush();
        const button = () => elements(page.root, 'pmk-modal-actionbar-right')[0].children.find(n => n.tagName === 'BUTTON');
        assert.equal(!!button().props.disabled, initial);
        blocked = !initial;
        await page.market.recovery.refresh();
        await page.flush();
        assert.equal(!!button().props.disabled, blocked);
        assert.deepEqual(page.errors, []);
    }
});

test('安装后的迟到详情不覆盖重新打开、切换插件或仓库后的对象', async () => {
    for (const target of ['visible', 'bundled', 'other-repo']) {
        const page = await mountMarket();
        page.market.api.fetchRepositories = async () => ({enabled: true, defaultRepositoryId: 'repo',
            repositories: ['repo', 'other-repo'].map(repositoryId => ({repositoryId, enabled: true}))});
        await page.reload({});
        elements(page.root, 'pmk-card-name')[0].props.onClick();
        await page.flush();
        elements(page.root, 'pmk-modal-actionbar-right')[0].children.find(n => n.tagName === 'BUTTON').props.onClick();
        await page.flush();
        const requests = [];
        page.market.api.fetchPluginDetail = () => new Promise(resolve => requests.push(resolve));
        page.completeInstall({pluginId: 'visible', version: '2.0.0', outcome: 'INSTALLED', accepted: true, activated: true});
        await page.flush();
        assert.equal(requests.length, 1);
        const modal = elements(page.root, 'pmk-modal')[0];
        modal.props.onClick({target: modal, currentTarget: modal});
        await page.flush();
        const pluginId = target === 'bundled' ? 'bundled' : 'visible';
        if (target === 'bundled') {
            elements(page.root, 'pmk-switch')[0].props.onClick();
            await page.flush();
        } else if (target === 'other-repo') {
            page.market.api.fetchCatalog = async () => ({repositoryId: 'other-repo', entries: [entry(pluginId)], categories: []});
            elements(page.root, 'pmk-repo-chip')[1].props.onClick();
            await page.flush();
        }
        elements(page.root, 'pmk-card-name').find(item => textOf(item) === pluginId).props.onClick();
        await page.flush();
        assert.equal(requests.length, 2);
        const fresh = entry(pluginId);
        fresh.installation = {state: 'PRESENT', version: '2.0.0', sha256: 'c'.repeat(64)};
        requests[1](fresh);
        await page.flush();
        const select = elements(page.root, 'pmk-version-select')[0];
        select.options.forEach(option => { option.selected = option.value === '1.0.0'; });
        select.listeners.change();
        await page.flush();
        const stale = entry('visible');
        stale.installation = {state: 'PRESENT', version: '2.0.0', sha256: 'a'.repeat(64)};
        requests[0](stale);
        await page.flush();
        const text = textOf(elements(page.root, 'pmk-modal')[0]);
        assert.ok(text.includes('c'.repeat(64)));
        assert.ok(!text.includes('a'.repeat(64)));
        assert.equal(select.options[select.selectedIndex].value, '1.0.0');
        assert.deepEqual(page.errors, []);
    }
});

test('安装后的详情刷新保留等待期间选中的版本', async () => {
    const page = await mountMarket();
    const detail = {...entry('visible'), versionsGeneration: 'fixture-generation'};
    page.market.api.fetchPluginDetail = async () => structuredClone(detail);
    elements(page.root, 'pmk-card-name')[0].props.onClick();
    await page.flush();
    elements(page.root, 'pmk-modal-actionbar-right')[0].children.find(n => n.tagName === 'BUTTON').props.onClick();
    await page.flush();
    let finishDetail;
    page.market.api.fetchPluginDetail = () => new Promise(resolve => { finishDetail = resolve; });
    page.completeInstall({pluginId: 'visible', version: '2.0.0', outcome: 'INSTALLED', accepted: true, activated: true});
    await page.flush();
    const select = elements(page.root, 'pmk-version-select')[0];
    select.options.forEach(option => { option.selected = option.value === '1.0.0'; });
    select.listeners.change();
    await page.flush();
    detail.installation = {state: 'PRESENT', version: '2.0.0', sha256: 'a'.repeat(64)};
    detail.packages = [detail.packages[0]];
    finishDetail(detail);
    await page.flush();
    assert.equal(select.options[select.selectedIndex].value, '1.0.0');
    assert.equal(page.factCalls.at(-1)[2], '1.0.0');
    assert.match(textOf(elements(page.root, 'pmk-modal-actionbar-right')[0]), /install.action.install-version/);
    assert.deepEqual(page.errors, []);
});

test('安装后详情刷新与版本分页交叉完成时保留同代次页面并拒绝旧代次页面', async () => {
    for (const order of ['refresh-first', 'page-first', 'generation-changed']) {
        const page = await mountMarket();
        const detail = {...entry('visible'), versionsGeneration: 'same', nextVersionCursor: 'older'};
        page.market.api.fetchPluginDetail = async () => structuredClone(detail);
        elements(page.root, 'pmk-card-name')[0].props.onClick();
        await page.flush();
        elements(page.root, 'pmk-modal-actionbar-right')[0].children.find(n => n.tagName === 'BUTTON').props.onClick();
        await page.flush();
        const requests = [];
        page.market.api.fetchPluginDetail = (repository, plugin, options) => new Promise(resolve => {
            requests.push({cursor: options?.cursor, resolve});
        });
        page.completeInstall({pluginId: 'visible', version: '2.0.0', outcome: 'INSTALLED', accepted: true, activated: true});
        await page.flush();
        const more = () => elements(page.root, 'pmk-btn').find(button => textOf(button) === 'plugin-market:pagination.more-versions');
        more().props.onClick();
        await page.flush();
        assert.deepEqual(requests.map(request => request.cursor), [undefined, 'older']);
        const refreshed = {...detail, packages: [detail.packages[0]], nextVersionCursor: 'first-page'};
        const older = {...detail, packages: [{...detail.packages[0], version: '0.5.0'}], nextVersionCursor: 'last-page'};
        if (order === 'generation-changed') {
            refreshed.versionsGeneration = 'changed';
            refreshed.nextVersionCursor = 'older';
        }
        if (order === 'page-first') {
            requests[1].resolve(older); await page.flush();
            requests[0].resolve(refreshed); await page.flush();
        } else {
            requests[0].resolve(refreshed); await page.flush();
            requests[1].resolve(older); await page.flush();
        }
        const versions = elements(page.root, 'pmk-version-select')[0]?.options.map(option => option.value) || [];
        assert.equal(versions.includes('0.5.0'), order !== 'generation-changed', order);
        assert.equal(requests.length, 2, '旧代次分页不能触发当前详情重载');
        more().props.onClick();
        await page.flush();
        assert.equal(requests[2].cursor, order === 'generation-changed' ? 'older' : 'last-page');
        assert.deepEqual(page.errors, []);
    }
});

test('历史版本的隐藏、撤销、未知及过期状态禁用真实详情安装控件', async () => {
    for (const revocation of ['YANKED', 'REVOKED', 'NOT_CHECKED', 'STALE']) {
        const page = await mountMarket({ revocation });
        const { root, flush } = page;
        elements(root, 'pmk-card-name')[0].props.onClick();
        await flush();
        const select = elements(root, 'pmk-version-select')[0];
        select.options.forEach(option => { option.selected = option.value === '1.0.0'; });
        select.listeners.change();
        await flush();
        const button = elements(root, 'pmk-modal-actionbar-right')[0].children.find(n => n.tagName === 'BUTTON');
        assert.equal(button.props.disabled, true, revocation);
        if (revocation === 'YANKED' || revocation === 'REVOKED') {
            assert.match(textOf(button), new RegExp('common:plugin-trust.revocation.' + revocation));
        }
        assert.equal(page.installCalls.length, 0);
    }
});

test('详情刷新移除旧版本时，展示、事实查询和安装使用同一现存版本', async () => {
    const page = await mountMarket();
    const detail = entry('visible');
    detail.latestVersion = '3.1.0';
    detail.packages = [{ ...detail.packages[0], version: '3.1.0' }];
    page.market.api.fetchPluginDetail = async () => detail;
    page.market.api.fetchPackageFacts = async (...args) => {
        page.factCalls.push(args);
        return { status: 'VERIFIED_OFFICIAL', revocationStatus: 'CLEAR' };
    };
    elements(page.root, 'pmk-card-name')[0].props.onClick();
    await page.flush();
    assert.match(textOf(elements(page.root, 'pmk-modal')[0]), /v3\.1\.0/);
    assert.deepEqual(page.factCalls, [['repo', 'visible', '3.1.0']]);
    elements(page.root, 'pmk-modal-actionbar-right')[0].children.find(n => n.tagName === 'BUTTON').props.onClick();
    await page.flush();
    assert.deepEqual(page.installCalls, [['repo', 'visible', '3.1.0']]);
    assert.deepEqual(page.errors, []);
});

test('Vue 中明确故障插件优先显示并标记，即使默认安装筛选开启', async () => {
    const page = await mountMarket({failed: true});
    const cards = elements(page.root, 'pmk-card');
    assert.equal(cards.length, 2);
    assert.match(cards[0].props.class, /pmk-card--suspected/);
    assert.match(textOf(cards[0]), /bundled/);
    assert.match(textOf(cards[0]), /recovery.focus.failed/);
    assert.doesNotMatch(cards[1].props.class, /pmk-card--suspected/);
});

test('无动态编译时仍能显示市场禁用状态和目录错误', async () => {
    const page = await mountMarket();
    await page.reload({ status: { recoveryMode: true, hostElevated: true,
        recoveryReasons: [{ pluginId: 'required', status: 'MISSING_REQUIRED', messages: [] }] }, enabled: false });
    assert.match(textOf(page.root), /plugin-market:master.disabled.title/);
    assert.match(textOf(page.root), /plugin-market:host.elevated.notice/);
    assert.equal(elements(page.root, 'pmk-body').length, 0);
    await page.reload({ enabled: true, failCatalog: true });
    assert.match(textOf(page.root), /plugin-market:error.catalog.title/);
    assert.equal(elements(page.root, 'pmk-body').length, 0);
    assert.deepEqual(page.errors, []);
});

test('详情分页切换目录代次后可继续加载，迟到请求不清除新上下文的忙状态', async () => {
    const page = await mountMarket();
    const requests = [];
    page.market.api.fetchPluginDetail = (repository, plugin, options) => new Promise(resolve => {
        requests.push({ cursor: options?.cursor, resolve });
    });
    const detail = generation => ({ ...entry('visible'), versionsGeneration: generation, nextVersionCursor: generation + '-next' });
    const more = () => elements(page.root, 'pmk-btn').find(button => textOf(button) === 'plugin-market:pagination.more-versions');
    const open = () => elements(page.root, 'pmk-card-name')[0].props.onClick();
    open(); requests[0].resolve(detail('A')); await page.flush();
    more().props.onClick(); await page.flush();
    assert.equal(more().props.disabled, true);
    requests[1].resolve(detail('B')); await page.flush();
    requests[2].resolve(detail('B')); await page.flush();
    assert.equal(more().props.disabled, false);
    more().props.onClick(); await page.flush();
    assert.deepEqual(requests.map(request => request.cursor), [undefined, 'A-next', undefined, 'B-next']);

    open(); requests[4].resolve(detail('C')); await page.flush();
    more().props.onClick(); await page.flush();
    requests[3].resolve(detail('B')); await page.flush();
    assert.equal(more().props.disabled, true, '旧请求不能解除当前分页请求的忙状态');
    requests[5].resolve({ ...detail('C'), nextVersionCursor: null }); await page.flush();
    assert.equal(more(), undefined);
    assert.deepEqual(page.errors, []);
});

test('基础详情刷新后统一版本选择、文档与来源查询，并保留仍存在的选择', async () => {
    for (const [versions, recommended, expected] of [
        [['3.1.0', '3.0.0'], '3.0.0', '3.0.0'],
        [['3.1.0', '3.0.0'], 'missing', '3.1.0'],
        [['3.1.0', '2.0.0'], '3.1.0', '2.0.0']
    ]) {
        const handlers = {}, requests = [], models = [];
        const element = () => ({ children: [], attrs: {}, listeners: {}, isConnected: true,
            addEventListener(name, listener) { this.listeners[name] = listener; },
            setAttribute(name, value) { this.attrs[name] = value; },
            getAttribute(name) { return this.attrs[name]; },
            replaceChildren() { this.children = []; }, appendChild(child) { this.children.push(child); },
            closest() { return null; }, focus() {}, showModal() {} });
        const nodes = Object.fromEntries(['h2', 'p', 'button', 'label span', 'select', '.pmk-more-versions',
            '.pmk-content', '.pmk-version-notes', '[data-pmk-facts]', '.pmk-detail-install',
            '.pmk-installation-notice', '.pmk-local-artifact', '.pmk-market-facts'].map(selector => [selector, element()]));
        nodes['[data-pmk-facts]'].attrs['data-pmk-facts'] = 'visible';
        const dialog = { ...element(), querySelector: selector => nodes[selector] };
        const root = { addEventListener: (name, callback) => { handlers[name] = callback; },
            querySelector: () => null, querySelectorAll: () => [] };
        const sandbox = { URL, console, document: { getElementById: () => null,
            createElement: tag => tag === 'dialog' ? dialog : element(), body: { appendChild() {} } } };
        sandbox.window = sandbox;
        vm.createContext(sandbox, { codeGeneration: { strings: false, wasm: false } });
        for (const resource of ['js/pixiv-plugin-presentation-tokens.js', 'plugin-market/plugin-market-core.js',
            'plugin-market/plugin-market-data.js', 'plugin-market/plugin-market-content.js', 'plugin-market/plugin-market-fallback.js']) {
            vm.runInContext(fs.readFileSync(path.join(staticRoot, resource), 'utf8'), sandbox, { filename: resource });
        }
        const market = sandbox.PixivPluginMarket;
        market.state.i18n.client = { lang: 'en-US', t: key => key };
        const plugin = entry('visible');
        let resolveDetail;
        market.content.mount = () => ({ update: model => models.push(model), dispose() {} });
        market.api = {
            fetchRepositories: async () => ({ enabled: true, defaultRepositoryId: 'repo',
                repositories: [{ repositoryId: 'repo', enabled: true }] }),
            fetchPluginStatus: async () => ({ recoveryMode: false }),
            fetchCatalog: async () => ({ repositoryId: 'repo', entries: [plugin], categories: [] }),
            fetchPluginDetail: () => new Promise(resolve => { resolveDetail = resolve; }),
            fetchPackageFacts: async (...args) => { requests.push(args); return { status: 'VERIFIED_OFFICIAL' }; }
        };
        const errors = [];
        market.toast = message => errors.push(message);
        sandbox.PixivFeedback = { alert: async () => {} };
        market.fallback.render(root);
        await new Promise(resolve => setImmediate(resolve));
        handlers.click({ target: { closest: selector => selector === '[data-pmk-detail]' ? {
            getAttribute: () => 'visible', isConnected: true, focus() {}
        } : null } });
        resolveDetail({ ...plugin, recommendedVersion: recommended, latestVersion: versions[0],
            packages: versions.map(version => ({ ...plugin.packages[0], version })) });
        await new Promise(resolve => setImmediate(resolve));
        assert.equal(nodes.select.value, expected);
        assert.ok(nodes.select.children.some(option => option.value === expected));
        assert.equal(models.at(-1).version, expected);
        const facts = nodes['[data-pmk-facts]'];
        assert.equal(facts.getAttribute('data-pmk-version'), expected);
        dialog.listeners.click({ target: { closest: selector => selector === '[data-pmk-facts]' ? facts : null } });
        await new Promise(resolve => setImmediate(resolve));
        assert.deepEqual(requests, [['repo', 'visible', expected]]);
        assert.deepEqual(errors, []);
        const installs = [];
        market.installPluginWithConfirmation = async (...args) => {
            installs.push(args.slice(0, 3));
            return {kind: 'install', body: {pluginId: 'visible', version: expected, outcome: 'INSTALLED',
                accepted: true, activated: false, effectiveAfterRestart: false, activationBlockedByDevelopmentMode: true}};
        };
        market.api.fetchPluginDetail = async () => ({...plugin, packages: [{...plugin.packages[0], version: expected,
            installationMatch: 'SAME_ARTIFACT'}], installedVersion: expected});
        nodes['.pmk-detail-install'].listeners.click();
        await new Promise(resolve => setImmediate(resolve));
        assert.deepEqual(installs, [['repo', 'visible', expected]]);
        assert.match(nodes['.pmk-detail-install'].textContent, /stored-development/);
        assert.equal(nodes['.pmk-detail-install'].disabled, true);
    }
});

test('社区版本保障与包内声明在 CSP 渲染中展示并随版本切换更新', async () => {
    const page = await mountMarket({ community: true });
    const { root, flush } = page;
    assert.match(textOf(elements(root, 'pmk-badge--community')[0]), /assurance.SOURCE_REVIEWED/);
    elements(root, 'pmk-card-name')[0].props.onClick();
    await flush();
    const modal = elements(root, 'pmk-modal')[0];
    assert.match(textOf(modal), /identity.COMMUNITY/);
    assert.match(textOf(modal), /signal.network/);
    assert.match(textOf(modal), /review-note/);
    assert.match(textOf(modal), /execution.DECLARATIVE_PROCESS/);
    const select = elements(root, 'pmk-version-select')[0];
    select.options.forEach(option => { option.selected = option.value === '1.0.0'; });
    select.listeners.change();
    await flush();
    assert.deepEqual(page.factCalls, [['repo', 'visible', '2.0.0'], ['repo', 'visible', '1.0.0']]);
    assert.match(textOf(elements(root, 'pmk-hero-pill')[0]), /assurance.PUBLISHER_SIGNED/);
    assert.match(textOf(modal), /plugin-trust.empty/);
    assert.doesNotMatch(textOf(modal), /signal.network|review-note/);
    assert.deepEqual(page.errors, []);
});

test('基础视图默认安装兼容旧版，更新撤销事实并丢弃换版后的迟到详情', async () => {
    const handlers = {};
    const root = { innerHTML: '', addEventListener(name, callback) { handlers[name] = callback; },
        querySelector: () => null, querySelectorAll: () => [] };
    const sandbox = { document: {getElementById: () => null}, console };
    sandbox.window = sandbox;
    vm.createContext(sandbox, { codeGeneration: { strings: false, wasm: false } });
    for (const resource of ['js/pixiv-plugin-presentation-tokens.js', 'plugin-market/plugin-market-core.js',
        'plugin-market/plugin-market-data.js', 'plugin-market/plugin-market-api.js', 'plugin-market/plugin-market-fallback.js']) {
        vm.runInContext(fs.readFileSync(path.join(staticRoot, resource), 'utf8'), sandbox, { filename: resource });
    }
    const market = sandbox.PixivPluginMarket;
    market.state.i18n.client = { lang: 'en-US', t: key => key };
    const plugin = entry('visible');
    plugin.installation = {state: 'PRESENT', runtimeStatus: 'CRASHED'};
    plugin.recommendedVersion = '1.0.0';
    plugin.packages[0].compatible = false;
    plugin.compatibilityReason = '999.0';
    const installCalls = [];
    market.installPluginWithConfirmation = (...args) => {
        installCalls.push(args.slice(0, 3));
        return Promise.resolve({kind: 'install', body: {accepted: true, activated: true, message: 'installed'}});
    };
    market.api = {
        ...market.api,
        fetchRepositories: async () => ({ enabled: true, defaultRepositoryId: 'repo',
            repositories: [{ repositoryId: 'repo', enabled: true }] }),
        fetchPluginStatus: async () => ({ recoveryMode: false }),
        fetchCatalog: async () => ({ repositoryId: 'repo', entries: [plugin], categories: [] }),
        fetchPackageFacts: async () => ({ status: 'VERIFIED_OFFICIAL', revocationStatus: 'REVOKED' })
    };
    const errors = [];
    market.toast = message => errors.push(message);
    market.fallback.render(root);
    await new Promise(resolve => setImmediate(resolve));
    assert.match(root.innerHTML, /data-pmk-install="visible"/);
    assert.match(root.innerHTML, /compat.fallback/);
    assert.match(root.innerHTML, /pmk-card--suspected/);
    assert.match(root.innerHTML, /recovery.focus.failed/);
    const installTag = root.innerHTML.match(/<button[^>]*data-pmk-install="visible"[^>]*>/)[0];
    assert.match(installTag, /data-pmk-version="1.0.0"/);
    handlers.click({target: {closest: selector => selector === '[data-pmk-install]' ? {
        disabled: false, getAttribute: name => ({'data-pmk-repo': 'repo', 'data-pmk-install': 'visible',
            'data-pmk-version': '1.0.0'})[name]
    } : null}});
    await new Promise(resolve => setImmediate(resolve));
    assert.deepEqual(installCalls, [['repo', 'visible', '1.0.0']]);
    const actions = { innerHTML: '' };
    let detailOptions;
    sandbox.PixivFeedback = {alert: options => { detailOptions = options; return Promise.resolve(); }};
    const facts = {
        isConnected: true,
        focus() {},
        getAttribute: name => name === 'data-pmk-facts' ? 'visible' : '1.0.0',
        closest: () => ({ querySelector: () => actions })
    };
    handlers.click({ target: { closest: selector => selector === '[data-pmk-facts]' ? facts : null } });
    await new Promise(resolve => setImmediate(resolve));
    assert.match(actions.innerHTML, / disabled/);
    assert.doesNotMatch(actions.innerHTML, /data-pmk-install/);
    assert.match(actions.innerHTML, /common:plugin-trust.revocation.REVOKED/);
    assert.match(JSON.stringify(detailOptions.sections), /revocation.REVOKED/);
    assert.doesNotMatch(root.innerHTML, /data-pmk-facts-content/);
    assert.match(root.innerHTML, /aria-haspopup="dialog"/);
    assert.deepEqual(errors, ['plugin-market:install.toast.activated']);
    let resolveFacts;
    let selectedVersion = '1.0.0';
    detailOptions = null;
    facts.getAttribute = name => name === 'data-pmk-facts' ? 'visible' : selectedVersion;
    market.api.fetchPackageFacts = () => new Promise(resolve => { resolveFacts = resolve; });
    handlers.click({target: {closest: selector => selector === '[data-pmk-facts]' ? facts : null}});
    selectedVersion = '2.0.0';
    resolveFacts({status: 'VERIFIED_OFFICIAL'});
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(detailOptions, null, '换版后不能打开旧版本的来源声明');
    assert.equal(facts.disabled, false);
});


test('市场依赖使用完整目录解析本地化名称并保留可选性和版本', async () => {
    const page = await mountMarket({dependencies: ['dependency?@>=3', 'unknown@4']});
    const {root, market, flush} = page;
    elements(root, 'pmk-card-name')[0].props.onClick();
    await flush();
    assert.match(textOf(root), /common:plugin-info.dependency-plugins/);
    assert.match(textOf(root), /Notifications\(dependency\) · >=3 · common:plugin-info.optional-dependency/);
    assert.match(textOf(root), /unknown · 4/);
    market.state.i18n.client.lang = 'zh-CN';
    market.state.activeView.rerender();
    await flush();
    assert.match(textOf(root), /通知\(dependency\)/);
    assert.doesNotMatch(textOf(root), /Notifications\(dependency\)/);
    assert.deepEqual(page.errors, []);
});
