'use strict';
/*
 * 通用页面区块渲染模块（pixiv-page-sections.js, window.PixivPageSections）的运行态测试。
 *
 * 无浏览器 / 无 jsdom：用最小 DOM + fetch / PixivVue / PixivI18n / PixivNav 桩在 Node 的 vm 沙箱里加载真实的
 * pixiv-page-sections.js，验证「reactive Vue 主路径 + 命令式回退 + 职责分离」契约的几条不变量：
 *   1) PixivVue 就位：为 [data-section-slot] 经 mountOn 挂 Vue app（Vue 主路径渲染区块骨架），组件 setup 渲染
 *      逻辑正确（标题 / 操作标题走 i18n、操作图标 SVG），且渲染后委托 PixivNav.refresh + 加载贡献方模块 + 派发事件。
 *   2) 职责分离不变量：骨架模板把内嵌 <nav data-nav-slot> 与 .page-section-body 渲染为**空** Vue 元素（无 Vue 子
 *      节点）——故 PixivNav 填的链接 / 贡献方模块填的列表不被 Vue 重渲染覆盖。
 *   3) 命令式回退：window.PixivVue 缺失 → 命令式 innerHTML（区块 + 空内嵌 nav slot + 空 body），仍委托 PixivNav.refresh。
 *   4) 拉取失败 → 清空 section slot、不残留按 id 显隐的旧业务块、仍委托 PixivNav.refresh、派发事件。
 *   5) 选择器安全：只用固定字面 [data-section-slot]（mock querySelectorAll 对其它选择器抛错即守卫）。
 *
 * 运行： node src/test/js/pixiv-page-sections.test.js
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');

const SRC = fs.readFileSync(
    path.join(__dirname, '..', '..', 'main', 'resources', 'static', 'js', 'pixiv-page-sections.js'), 'utf8');

let passed = 0;
function ok(label, cond) { assert.ok(cond, label); passed++; }

class El {
    constructor(tag) { this.tag = String(tag).toLowerCase(); this.attrs = {}; this.children = []; this.parent = null; this._html = ''; this.src = ''; this.async = false; }
    setAttribute(k, v) { this.attrs[k] = String(v); }
    getAttribute(k) { return Object.prototype.hasOwnProperty.call(this.attrs, k) ? this.attrs[k] : null; }
    hasAttribute(k) { return Object.prototype.hasOwnProperty.call(this.attrs, k); }
    get innerHTML() { return this._html; }
    set innerHTML(v) { this._html = String(v); this.children = []; }
    appendChild(c) { c.parent = this; this.children.push(c); return c; }
}

function sectionSlot(placement) {
    const el = new El('div');
    el.setAttribute('data-section-slot', placement);
    return el;
}

function makeDocument(slots) {
    const body = new El('body');
    slots.forEach(s => body.appendChild(s));
    const head = new El('head');
    return {
        head, body, readyState: 'complete',
        createElement: t => new El(t),
        addEventListener() {},
        querySelectorAll(sel) {
            if (sel !== '[data-section-slot]') {
                throw new Error('Unexpected selector (target must never be interpolated): ' + sel);
            }
            const out = [];
            (function walk(n) { n.children.forEach(c => { if (c.getAttribute('data-section-slot') !== null) out.push(c); walk(c); }); })(body);
            return out;
        }
    };
}

const I18N = { create() { return Promise.resolve({ t: (key, fb) => (key ? 'T:' + key : fb), tns: (ns, key, fb) => (key ? 'T:' + (ns ? ns + ':' : '') + key : fb) }); }, onLanguageChange() {} };

function makeVue() { return { reactive: o => o, nextTick: () => Promise.resolve(), createApp: () => ({ mount: () => ({}) }) }; }

function makePixivVue(record) {
    const vue = makeVue();
    return {
        ensure: () => Promise.resolve(vue),
        mountOn: (el, comp) => { record.mounts.push({ el, comp }); return Promise.resolve({ app: { unmount() {} }, vm: {}, el }); }
    };
}

// GalleryPlugin.pageSections() 服务端投影后的两条区块（前端实际消费字段）。
const SECTIONS = [
    { id: 'gallery-stats-views', titleNamespace: 'gallery', titleI18nKey: 'section.view', navPlacement: 'stats.gallery-links', actionHref: null, actionIcon: null, actionTitleNamespace: null, actionTitleI18nKey: null, moduleUrl: null },
    { id: 'gallery-stats-collections', titleNamespace: 'gallery', titleI18nKey: 'section.collections', navPlacement: null, actionHref: '/pixiv-gallery.html?view=all&createCollection=1', actionIcon: 'plus', actionTitleNamespace: 'gallery', actionTitleI18nKey: 'collection.new', moduleUrl: '/pixiv-gallery/gallery-stats-embed.js' }
];

function load(opts) {
    const document = makeDocument(opts.slots);
    const record = { mounts: [], events: [], navRefreshes: 0 };
    const sandbox = {
        document,
        console: { warn() {}, log() {}, error() {} },
        setTimeout, clearTimeout, Promise,
        fetch: () => Promise.resolve({ ok: opts.fetchOk !== false, status: opts.fetchOk === false ? 500 : 200, json: () => Promise.resolve(opts.sections) }),
        CustomEvent: class { constructor(type) { this.type = type; } },
        dispatchEvent(e) { record.events.push(e); },
        PixivNav: { refresh() { record.navRefreshes++; } }
    };
    sandbox.window = sandbox;
    if (opts.i18n !== false) sandbox.PixivI18n = I18N;
    if (opts.pixivVue) sandbox.PixivVue = makePixivVue(record);
    vm.createContext(sandbox);
    vm.runInContext(SRC, sandbox);
    return { api: sandbox.PixivPageSections, document, record };
}

function eventCount(record) { return record.events.filter(e => e.type === 'pixivpagesections:rendered').length; }
function loadedScripts(document) { return document.head.children.filter(c => c.tag === 'script').map(c => c.src); }

async function main() {
    // ===== 场景 1：Vue 就位 → mountOn 挂区块骨架；组件逻辑正确；渲染后委托 PixivNav.refresh + 加载模块 + 派发事件 =====
    {
        const slot = sectionSlot('stats.sidebar.sections');
        const { api, document, record } = load({ slots: [slot], sections: SECTIONS, pixivVue: true });
        await api.ready();

        ok('1: 为 [data-section-slot] 经 mountOn 挂 Vue app（Vue 主路径）', record.mounts.length === 1);
        const comp = record.mounts[0].comp;
        ok('1: mountOn 收到 slot 元素 + 组件（含 setup/template）',
            record.mounts[0].el === slot && typeof comp.setup === 'function' && typeof comp.template === 'string');

        const view = comp.setup();
        ok('1: 组件 sections 即两条贡献区块', view.sections.length === 2);
        ok('1: 标题走 i18n（titleOf）', view.titleOf(SECTIONS[0]) === 'T:gallery:section.view');
        ok('1: 有 actionTitleI18nKey → 操作标题走 i18n；无则 null',
            view.actionTitleOf(SECTIONS[1]) === 'T:gallery:collection.new' && view.actionTitleOf(SECTIONS[0]) === null);
        ok('1: 操作图标为 SVG', /<svg/.test(view.actionIconOf(SECTIONS[1])));
        ok('1: 默认部件 class（标准侧栏）', view.cls.header === 'section-header' && view.cls.nav === 'sidebar-nav' && view.cls.divider === 'sidebar-divider');

        // 职责分离不变量：模板里 <nav data-nav-slot> 与 .page-section-body 均为空元素（无 Vue 子节点）。
        ok('1: 内嵌 <nav data-nav-slot> 在模板中为空元素（无 Vue 子节点 → PixivNav 填的链接不被覆盖）',
            comp.template.indexOf(':data-nav-slot="s.navPlacement"') >= 0 && comp.template.indexOf('></nav>') >= 0);
        ok('1: .page-section-body 在模板中为空元素（无 Vue 子节点 → 贡献方模块填的列表不被覆盖）',
            comp.template.indexOf('class="page-section-body"') >= 0 && comp.template.indexOf('></div>') >= 0);

        // 渲染后委托：PixivNav.refresh（内嵌 nav）+ 加载贡献方模块 + 派发事件。
        ok('1: 渲染后委托 PixivNav.refresh（填内嵌导航 slot）', record.navRefreshes >= 1);
        ok('1: 加载贡献方前端模块（moduleUrl）', loadedScripts(document).indexOf('/pixiv-gallery/gallery-stats-embed.js') >= 0);
        ok('1: 派发 pixivpagesections:rendered（贡献方据此填容器）', eventCount(record) >= 1);

        // 幂等：refresh 不重复 mountOn。
        await api.refresh();
        ok('1: Vue 稳态 refresh() 不重复 mountOn', record.mounts.length === 1);
    }

    // ===== 场景 2：无 PixivVue → 命令式 innerHTML（区块 + 空内嵌 nav slot + 空 body），仍委托 PixivNav.refresh =====
    {
        const slot = sectionSlot('stats.sidebar.sections');
        const { api, document, record } = load({ slots: [slot], sections: SECTIONS, pixivVue: false });
        await api.ready();
        ok('2: 无 PixivVue 不调用 mountOn', record.mounts.length === 0);
        const html = slot.innerHTML;
        ok('2: 命令式渲染区块（page-section + 标题 i18n）', html.indexOf('class="page-section"') >= 0 && html.indexOf('T:gallery:section.view') >= 0);
        ok('2: 命令式渲染内嵌 nav slot（data-nav-slot=stats.gallery-links）', html.indexOf('data-nav-slot="stats.gallery-links"') >= 0);
        ok('2: 命令式渲染 moduleUrl 的空 body（page-section-body）', html.indexOf('page-section-body') >= 0);
        ok('2: 命令式渲染操作入口（createCollection href + 操作标题）',
            html.indexOf('view=all&amp;createCollection=1') >= 0 && html.indexOf('T:gallery:collection.new') >= 0);
        ok('2: 仍委托 PixivNav.refresh + 加载模块 + 派发事件',
            record.navRefreshes >= 1 && loadedScripts(document).indexOf('/pixiv-gallery/gallery-stats-embed.js') >= 0 && eventCount(record) >= 1);
    }

    // ===== 场景 3：拉取失败 → 清空 section slot、仍委托 PixivNav.refresh、派发事件 =====
    {
        const slot = sectionSlot('stats.sidebar.sections');
        slot.innerHTML = '<div class="page-section stale">旧业务块</div>';
        const { api, record } = load({ slots: [slot], sections: null, fetchOk: false, pixivVue: true });
        await api.ready();
        ok('3: 拉取失败清空 section slot（不残留按 id 显隐的旧业务块）', slot.innerHTML === '');
        ok('3: 拉取失败不挂 Vue（无 sections 不挂空 app）', record.mounts.length === 0);
        ok('3: 拉取失败仍委托 PixivNav.refresh（刷新页面其它 nav slot）+ 派发事件', record.navRefreshes >= 1 && eventCount(record) >= 1);
    }

    console.log(`\npixiv-page-sections.test.js: ${passed} assertions passed ✓`);
}

main().catch(err => { console.error('TEST FAILED:', err && err.stack ? err.stack : err); process.exit(1); });
