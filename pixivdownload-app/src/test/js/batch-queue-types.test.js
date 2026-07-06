'use strict';
/*
 * 下载页扩展点注册中心（batch-queue-types.js）的轻量运行态测试。
 *
 * 无浏览器 / 无 jsdom：用最小 DOM + fetch 桩在 Node 的 vm 沙箱里加载真实的 batch-queue-types.js，
 * 验证「取得侧行为钩子据扩展点动态派发」与「类型禁用 → 入口缺席 / 不可用」两条核心不变量：
 *   1) 作品类型可用性（isTypeAvailable / resolveType / normalizeSelectedType）。
 *   2) 取得侧钩子分派（acquisition / acquisitionList / contributionsOf / filtersFor）—— 不可用类型一律 null / 排除。
 *   3) 槽位渲染（renderSlots）—— 可用类型的 slots 注入为锚点的真实兄弟节点；禁用 → 不注入；锚点一律移除（一次性）。
 *
 * 运行： node src/test/js/batch-queue-types.test.js
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');

const QT_PATH = path.join(__dirname, '..', '..', '..', '..',
    'pixivdownload-plugin-download-workbench', 'src', 'main', 'resources', 'static', 'pixiv-batch', 'batch-queue-types.js');
const SOURCE = fs.readFileSync(QT_PATH, 'utf8');

// ---- 最小 DOM 实现（够 renderSlots / loadModule 用）----
class El {
    constructor(tag) {
        this.tag = tag;
        this.attrs = {};
        this.children = [];
        this.parent = null;
        this.injectedHtml = null; // 仅 insertAdjacentHTML 产生的节点用
        this.async = false;
        this.src = '';
        this.onload = null;
        this.onerror = null;
    }
    setAttribute(k, v) { this.attrs[k] = v; }
    getAttribute(k) { return this.attrs[k]; }
    appendChild(child) {
        child.parent = this;
        this.children.push(child);
        // 模拟动态 <script> 加载成功：下一 tick 触发 onload（loadModule 据此 resolve）。
        if (child.tag === 'script' && typeof child.onload === 'function') {
            setTimeout(() => child.onload(), 0);
        }
        return child;
    }
    insertAdjacentHTML(position, html) {
        if (position !== 'beforebegin' || !this.parent) return;
        const node = new El('#injected');
        node.injectedHtml = html;
        node.parent = this.parent;
        const i = this.parent.children.indexOf(this);
        this.parent.children.splice(i, 0, node); // 作为锚点前的真实兄弟节点
    }
    remove() {
        if (!this.parent) return;
        const i = this.parent.children.indexOf(this);
        if (i >= 0) this.parent.children.splice(i, 1);
        this.parent = null;
    }
}

function makeDom(slotNames) {
    const body = new El('body');
    const head = new El('head');
    // 为每个开放位置放一个 <template data-qt-slot="名字"> 锚点
    slotNames.forEach(name => {
        const t = new El('template');
        t.setAttribute('data-qt-slot', name);
        body.appendChild(t);
    });
    // 收集全部 <template data-qt-slot> 锚点（不按 target 过滤——按 target 定位由 renderSlots 经 getAttribute 完成）。
    function queryAll(root) {
        const out = [];
        (function walk(node) {
            if (node.tag === 'template' && 'data-qt-slot' in node.attrs) out.push(node);
            node.children.forEach(walk);
        })(root);
        return out;
    }
    const document = {
        head,
        body,
        createElement: tag => new El(tag),
        // 仅支持**固定字面** template[data-qt-slot]；把 target 拼进选择器（如 template[data-qt-slot="X"]）即抛错
        // ——这是「renderSlots 绝不把 target 拼进 querySelector」的守卫（回归到拼接形态时此处抛错、测试失败）。
        querySelectorAll(selector) {
            if (selector !== 'template[data-qt-slot]') {
                throw new Error('Unexpected selector (target must never be interpolated): ' + selector);
            }
            return queryAll(body);
        }
    };
    return {document, body};
}

// 在沙箱里加载真实的 batch-queue-types.js，返回 {queueTypes, document, body}。pixivVue 给定时注入为
// window.PixivVue（供 renderSlots 走 Vue 主路径）；不给则 window.PixivVue 缺失 → renderSlots 命令式回退。
function loadRegistry(extensionsData, slotNames, pixivVue) {
    const {document, body} = makeDom(slotNames);
    const requests = [];
    const sandbox = {
        window: {},
        document,
        BASE: '',
        pageI18n: {apply() {}},
        console: {warn() {}, log() {}, error() {}},
        setTimeout,
        clearTimeout,
        Promise,
        fetch: url => {
            requests.push(String(url));
            return Promise.resolve({
                ok: extensionsData !== null,
                json: () => Promise.resolve(extensionsData)
            });
        }
    };
    if (pixivVue) sandbox.window.PixivVue = pixivVue;
    vm.createContext(sandbox);
    vm.runInContext(SOURCE, sandbox);
    return {qt: sandbox.window.PixivBatch.queueTypes, document, body, requests};
}

// PixivVue 桩（供「Vue 主路径」场景）：记录 prepareSlotHosts / mount 调用；mount 默认成功，置 mountFails=true 模拟挂载失败。
function makePixivVueStub() {
    const record = {mounts: [], prepareCalls: 0, mountFails: false};
    return {
        record,
        stub: {
            prepareSlotHosts() { record.prepareCalls++; return []; },
            mount(target, comp) {
                record.mounts.push({target, comp});
                return Promise.resolve(record.mountFails ? null : {app: {}, vm: {}, el: {}});
            }
        }
    };
}

function injectedHtmls(body) {
    return body.children.filter(c => c.tag === '#injected').map(c => c.injectedHtml);
}
function remainingTemplates(document) {
    return document.querySelectorAll('template[data-qt-slot]');
}

// 一个最小但富 descriptor 的 novel 行为模块（镜像真实 novel-queue-type.js 的钩子形态）。
function novelDescriptor() {
    return {
        pluginId: 'novel', type: 'novel', display: 'd',
        process() {},
        slots: {
            'kind-option-user': '<label data-kind="novel">N</label>',
            'settings-card': '<div id="novel-settings-card">S</div>',
            'search-filter': '<div class="search-novel-only">W</div>'
        },
        import: {sectionType: 'novel', matchUrl: () => null, buildItem: () => ({}), source: 'single-import-novel'},
        filters: {extraSelector: '.search-novel-only', matchExtra: () => true},
        settings: {cardId: 'novel-settings-card'},
        acquisition: {
            user: {fetchIds: () => [], queueId: it => 'n' + it.id},
            search: {searchEndpoint: '/api/pixiv/novel-search', pageSize: 24, queueId: it => 'n' + it.id},
            series: {pageSize: 30, parseUrl: () => null},
            quick: {pageSize: 24, actions: {'my-novels': {kind: 'novel'}}}
        }
    };
}

const SLOT_NAMES = ['kind-option-user', 'settings-card', 'search-filter', 'quick-actions-bookmarks'];
let passed = 0;
function ok(label, cond) {
    assert.ok(cond, label);
    passed++;
}

// ========== 场景 A：novel 启用 ==========
(async function scenarioEnabled() {
    const {qt, document, body, requests} = loadRegistry(
        {
            queueTypes: [{type: 'illust', order: 1}, {type: 'novel', order: 2, moduleUrl: '/x.js'}],
            downloadTypes: [{
                contractVersion: 1,
                pluginId: 'novel',
                type: 'novel',
                order: 2,
                displayNamespace: 'novel',
                i18nNamespace: 'novel',
                gallery: {reasonNamespace: 'gallery'}
            }],
            tabs: []
        },
        SLOT_NAMES);
    const namespaces = await qt.i18nNamespaces();
    ok('A: descriptor i18n namespace 可在页面 i18n 初始化前预取', namespaces.includes('novel'));
    ok('A: gallery reason namespace 也随 descriptor 暴露给页面 i18n', namespaces.includes('gallery'));
    ok('A: 重复 namespace 去重', namespaces.filter(ns => ns === 'novel').length === 1);
    // 插画内置 + novel 行为模块（此处直接 register 模拟模块已加载注册）
    qt.register('illust', {pluginId: 'download-workbench', type: 'illust', process() {}});
    qt.register('novel', novelDescriptor());
    await qt.bootstrap();
    ok('A: i18n 预取后 bootstrap 复用扩展点响应', requests.length === 1);

    ok('A: illust 可用', qt.isTypeAvailable('illust') === true);
    ok('A: novel 可用', qt.isTypeAvailable('novel') === true);
    ok('A: resolveType(novel)=novel', qt.resolveType('novel') === 'novel');
    ok('A: resolveType(request)=illust(回退)', qt.resolveType('request') === 'illust');
    ok('A: normalizeSelectedType(novel,allowed)=novel', qt.normalizeSelectedType('novel', ['illust', 'novel', 'request']) === 'novel');
    ok('A: acquisition(novel,search) 命中', !!qt.acquisition('novel', 'search') && qt.acquisition('novel', 'search').searchEndpoint === '/api/pixiv/novel-search');
    ok('A: acquisition(illust,search)=null(内置无钩子)', qt.acquisition('illust', 'search') === null);
    ok('A: filtersFor(novel) 命中', !!qt.filtersFor('novel') && qt.filtersFor('novel').extraSelector === '.search-novel-only');
    ok('A: contributionsOf(settings) 含 novel', qt.contributionsOf('settings').some(s => s.type === 'novel' && s.cardId === 'novel-settings-card'));
    ok('A: contributionsOf(import) 含 novel', qt.contributionsOf('import').some(s => s.type === 'novel' && s.sectionType === 'novel'));
    ok('A: acquisitionList(series) 含 novel', qt.acquisitionList('series').some(a => a.type === 'novel'));

    // 槽位：novel 的 slots 注入为锚点前的真实兄弟节点
    const injected = injectedHtmls(body);
    ok('A: kind-option-user slot 已注入', injected.some(h => /data-kind="novel"/.test(h)));
    ok('A: settings-card slot 已注入', injected.some(h => /novel-settings-card/.test(h)));
    ok('A: search-filter slot 已注入', injected.some(h => /search-novel-only/.test(h)));
    // 锚点一律移除（一次性）
    ok('A: 全部 <template data-qt-slot> 锚点已移除', remainingTemplates(document).length === 0);
})()
    // ========== 场景 B：novel 禁用（不在扩展点 / 模块未加载 → 未注册）==========
    .then(async function scenarioDisabled() {
        const {qt, document, body} = loadRegistry(
            {queueTypes: [{type: 'illust', order: 1}], tabs: []}, // 后端仅报告 illust
            SLOT_NAMES);
        qt.register('illust', {pluginId: 'download-workbench', type: 'illust', process() {}});
        // novel 模块未加载 → 不 register('novel')
        await qt.bootstrap();

        ok('B: novel 不可用', qt.isTypeAvailable('novel') === false);
        ok('B: resolveType(novel)=illust(回退)', qt.resolveType('novel') === 'illust');
        ok('B: normalizeSelectedType(novel,...)=illust(残留回退)', qt.normalizeSelectedType('novel', ['illust', 'novel', 'request']) === 'illust');
        ok('B: acquisition(novel,search)=null(不暴露钩子→不发起小说请求)', qt.acquisition('novel', 'search') === null);
        ok('B: acquisition(novel,user)=null', qt.acquisition('novel', 'user') === null);
        ok('B: filtersFor(novel)=null', qt.filtersFor('novel') === null);
        ok('B: contributionsOf(settings) 不含 novel', !qt.contributionsOf('settings').some(s => s.type === 'novel'));
        ok('B: contributionsOf(import) 不含 novel(导入无小说解析器→不误建小说项)', !qt.contributionsOf('import').some(s => s.type === 'novel'));
        ok('B: acquisitionList(series) 不含 novel(系列 URL 无小说解析→不发起小说请求)', !qt.acquisitionList('series').some(a => a.type === 'novel'));

        // 槽位：novel 未注册 → 无注入；锚点仍一律移除（入口在宿主页缺席，而非隐藏）
        ok('B: 无任何 novel slot 注入', injectedHtmls(body).length === 0);
        ok('B: 全部 <template data-qt-slot> 锚点已移除', remainingTemplates(document).length === 0);
    })
    // ========== 场景 C：拉取扩展点失败（degradation）==========
    .then(async function scenarioFetchFailed() {
        const {qt} = loadRegistry(null, SLOT_NAMES); // fetch !ok
        qt.register('illust', {pluginId: 'download-workbench', type: 'illust', process() {}});
        await qt.bootstrap();
        // 拉取失败 → bootstrapped=false（isEnabled 恒真），但 novel 模块从未加载（has=false）→ 仍不可用、回退插画
        ok('C: 拉取失败时 illust 仍可用', qt.isTypeAvailable('illust') === true);
        ok('C: 拉取失败时 novel 不可用(模块未加载)', qt.isTypeAvailable('novel') === false);
        ok('C: 拉取失败时 resolveType(novel)=illust', qt.resolveType('novel') === 'illust');
    })
    // ========== 场景 D：PixivVue 就位 → 槽位片段走 Vue 主路径（不命令式注入）==========
    .then(async function scenarioVueMainPath() {
        const pv = makePixivVueStub();
        const {qt, document, body} = loadRegistry(
            {queueTypes: [{type: 'illust', order: 1}, {type: 'novel', order: 2, moduleUrl: '/x.js'}], tabs: []},
            SLOT_NAMES, pv.stub);
        qt.register('illust', {pluginId: 'download-workbench', type: 'illust', process() {}});
        qt.register('novel', novelDescriptor());
        await qt.bootstrap();

        ok('D: 经 prepareSlotHosts 在模板原位备 Vue 宿主', pv.record.prepareCalls >= 1);
        const mountedTargets = pv.record.mounts.map(m => m.target).sort();
        ok('D: 每个有片段的槽位都经 PixivVue.mount 走 Vue 主路径（novel 的 3 个 slots）',
            mountedTargets.join(',') === 'kind-option-user,search-filter,settings-card');
        ok('D: 每个 mount 收到组件定义（含 template 片段字符串）',
            pv.record.mounts.every(m => m.comp && typeof m.comp.template === 'string' && m.comp.template.length > 0));
        ok('D: kind-option-user 的 Vue 模板即该槽位片段（含 data-kind="novel"）',
            /data-kind="novel"/.test(pv.record.mounts.find(m => m.target === 'kind-option-user').comp.template));
        // 正常路径走 Vue → 不命令式注入（descriptor.slots / NOVEL_SLOTS 仅作 Vue 不可用时的回退）。
        ok('D: Vue 主路径下无命令式注入（无 #injected 兄弟节点）', injectedHtmls(body).length === 0);
        ok('D: 锚点仍一律移除（一次性消费）', remainingTemplates(document).length === 0);
    })
    // ========== 场景 E：PixivVue 就位但挂载失败 → 命令式回退注入 ==========
    .then(async function scenarioVueMountFailsFallback() {
        const pv = makePixivVueStub();
        pv.record.mountFails = true; // 模拟 Vue 挂载失败（mount 解析为 null）
        const {qt, document, body} = loadRegistry(
            {queueTypes: [{type: 'illust', order: 1}, {type: 'novel', order: 2, moduleUrl: '/x.js'}], tabs: []},
            SLOT_NAMES, pv.stub);
        qt.register('illust', {pluginId: 'download-workbench', type: 'illust', process() {}});
        qt.register('novel', novelDescriptor());
        await qt.bootstrap();

        ok('E: 挂载失败时每个有片段的槽位仍先尝试 Vue 主路径', pv.record.mounts.length === 3);
        const injected = injectedHtmls(body);
        ok('E: Vue 挂载失败 → 命令式回退注入 kind-option-user', injected.some(h => /data-kind="novel"/.test(h)));
        ok('E: Vue 挂载失败 → 命令式回退注入 settings-card', injected.some(h => /novel-settings-card/.test(h)));
        ok('E: Vue 挂载失败 → 命令式回退注入 search-filter', injected.some(h => /search-novel-only/.test(h)));
        ok('E: 锚点仍一律移除', remainingTemplates(document).length === 0);
    })
    // ========== 场景 F：无效行为模块 descriptor 明确跳过，不污染可用行为 ==========
    .then(async function scenarioInvalidDescriptorSkipped() {
        const {qt} = loadRegistry(
            {queueTypes: [{type: 'illust', order: 1}, {type: 'broken', order: 2, moduleUrl: '/broken.js'}],
                downloadTypes: [{contractVersion: 1, pluginId: 'broken', type: 'broken', order: 2, acquisitionModes: ['single-import']}],
                tabs: []},
            SLOT_NAMES);
        qt.register('illust', {pluginId: 'download-workbench', type: 'illust', process() {}});
        const registered = qt.register('broken', {pluginId: 'broken', type: 'broken', contractVersion: 99, process() {}});
        await qt.bootstrap();
        ok('F: contractVersion 不支持时 register 返回 false', registered === false);
        ok('F: broken 行为未进入 registry', qt.has('broken') === false);
        ok('F: 后端 downloadTypes descriptor 可读取', qt.downloadTypes().some(d => d.type === 'broken' && d.contractVersion === 1));
    })
    .then(() => {
        console.log(`\nbatch-queue-types.test.js: ${passed} assertions passed (6 scenarios) ✓`);
    })
    .catch(err => {
        console.error('TEST FAILED:', err && err.message ? err.message : err);
        process.exit(1);
    });
