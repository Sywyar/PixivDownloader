'use strict';
/*
 * 跨插件导航渲染模块（pixiv-navigation.js, window.PixivNav）的运行态测试。
 *
 * 无浏览器 / 无 jsdom：用最小 DOM + fetch / PixivVue / PixivI18n 桩在 Node 的 vm 沙箱里加载真实的
 * pixiv-navigation.js，验证「reactive Vue 主路径 + 命令式回退 + 优雅降级」契约的几条不变量：
 *   1) PixivVue 就位：经 ensure() 懒加载运行时、为每个 [data-nav-slot] 经 mountOn 挂一个 Vue app（Vue 主路径），
 *      且组件 setup 的渲染逻辑正确（按 placement 过滤 / 当前项 active / href 由贡献方声明 / 图标 + 文字内层）。
 *   2) 命令式回退：window.PixivVue 缺失 → 命令式 innerHTML 渲染（与 Vue 组件同源的链接 HTML），不抛、派发 rendered。
 *   3) Vue 运行时加载失败（ensure reject）→ 优雅回退命令式、不抛、派发 rendered。
 *   4) 拉取失败 → 清空全部 slot、不残留坏入口、派发 rendered。
 *   5) 幂等：Vue 稳态下 refresh() 不重复 mountOn（复用既有 app），稳态不再走命令式。
 *   6) 选择器安全：只用固定字面 [data-nav-slot]（mock querySelectorAll 对其它选择器抛错即守卫）。
 *
 * 运行： node src/test/js/pixiv-navigation.test.js
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');

const SRC = fs.readFileSync(
    path.join(__dirname, '..', '..', 'main', 'resources', 'static', 'js', 'pixiv-navigation.js'), 'utf8');

let passed = 0;
function ok(label, cond) { assert.ok(cond, label); passed++; }

// ---- 最小 DOM ----
class El {
    constructor(tag) { this.tag = String(tag).toLowerCase(); this.attrs = {}; this.children = []; this.parent = null; this._html = ''; }
    setAttribute(k, v) { this.attrs[k] = String(v); }
    getAttribute(k) { return Object.prototype.hasOwnProperty.call(this.attrs, k) ? this.attrs[k] : null; }
    hasAttribute(k) { return Object.prototype.hasOwnProperty.call(this.attrs, k); }
    get innerHTML() { return this._html; }
    set innerHTML(v) { this._html = String(v); this.children = []; }
    appendChild(c) { c.parent = this; this.children.push(c); return c; }
}

function navSlot(placement, opts) {
    const el = new El('span');
    el.setAttribute('data-nav-slot', placement);
    Object.keys(opts || {}).forEach(k => el.setAttribute(k, opts[k]));
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
        // 仅支持固定字面 [data-nav-slot]；任何其它选择器抛错 = 「target 绝不拼进选择器」守卫。
        querySelectorAll(sel) {
            if (sel !== '[data-nav-slot]') {
                throw new Error('Unexpected selector (target must never be interpolated): ' + sel);
            }
            const out = [];
            (function walk(n) { n.children.forEach(c => { if (c.getAttribute('data-nav-slot') !== null) out.push(c); walk(c); }); })(body);
            return out;
        }
    };
}

// ---- 桩：fetch / PixivI18n / PixivVue / Vue ----
function makeFetch(items, okFlag) {
    return function () {
        return Promise.resolve({ ok: okFlag !== false, status: okFlag === false ? 500 : 200, json: () => Promise.resolve(items) });
    };
}

// i18n：t(key, fallback) 直接回 'T:'+key（便于断言 label 走 i18n），create 解析为该解析器。
const I18N = {
    lastNamespaces: null,                 // 记录最近一次 create({namespaces}) 收到的 namespace 集（断言「不加载空白 namespace」）
    create(opts) {
        I18N.lastNamespaces = (opts && opts.namespaces) || null;
        return Promise.resolve({
            t: (key, fb) => (key ? 'T:' + key : fb),
            // 显式 namespace 解析（纯 key + namespace）：回 'T:<ns>:<key>'，便于断言 label 走 i18n。
            // **镜像真实 PixivI18n.tns 的空白 namespace 规范化**：null/""/纯空白 → trim 后为空 → 退化为裸 key（'T:<key>'，无 ns 前缀），
            // 使「空白 labelNamespace 的 label 按裸 key 回退」在桩上与真实模块一致（真实 tns 的规范化另由 pixiv-i18n.test.js 验证）。
            tns: (ns, key, fb) => {
                var n = ns == null ? '' : String(ns).trim();
                return key ? 'T:' + (n ? n + ':' : '') + key : fb;
            }
        });
    },
    onLanguageChange() {}
};

// mock Vue：reactive 返回同一对象（属性置换对组件闭包可见）；nextTick 立即 resolve；createApp.mount 记录。
function makeVue() {
    return { reactive: o => o, nextTick: () => Promise.resolve(), createApp: () => ({ mount: () => ({}) }) };
}

// mock PixivVue：ensure 解析为 mock Vue；mountOn 记录 (el, comp) 并返回成功句柄（不真正渲染 DOM）。
function makePixivVue(record, ensureRejects) {
    const vue = makeVue();
    return {
        ensure: () => ensureRejects ? Promise.reject(new Error('vue load failed')) : Promise.resolve(vue),
        mountOn: (el, comp) => {
            record.mounts.push({ el, comp });
            return Promise.resolve({ app: { unmount() {} }, vm: {}, el });
        }
    };
}

function load(opts) {
    const slots = opts.slots;
    const document = makeDocument(slots);
    const record = { mounts: [], events: [] };
    const sandbox = {
        document,
        location: { pathname: opts.pathname || '/monitor.html' },
        console: { warn() {}, log() {}, error() {} },
        setTimeout, clearTimeout, Promise,
        fetch: makeFetch(opts.items, opts.fetchOk),
        CustomEvent: class { constructor(type, init) { this.type = type; this.detail = init && init.detail; } },
        dispatchEvent(e) { record.events.push(e); }
    };
    sandbox.window = sandbox; // 模块用 window 作 global
    if (opts.i18n !== false) sandbox.PixivI18n = I18N;
    if (opts.pixivVue) sandbox.PixivVue = makePixivVue(record, opts.ensureRejects);
    vm.createContext(sandbox);
    vm.runInContext(SRC, sandbox);
    return { PixivNav: sandbox.PixivNav, document, record, slots };
}

function renderedCount(record) { return record.events.filter(e => e.type === 'pixivnav:rendered').length; }

const ITEMS = [
    { id: 'gallery', placements: ['app.top', 'gallery.sidebar'], href: '/pixiv-gallery.html?view=all', icon: 'images', labelNamespace: 'gallery', labelI18nKey: 'nav.label' },
    { id: 'monitor', placements: ['app.top'], href: '/monitor.html', icon: 'monitor', labelNamespace: 'monitor', labelI18nKey: 'nav.monitor' },
    { id: 'novel', placements: ['gallery.sidebar'], href: '/pixiv-novel-gallery.html?view=all', icon: 'book', labelNamespace: 'novel', labelI18nKey: 'nav.label' }
];

async function main() {
    // ===== 场景 1：Vue 就位 → 经 ensure + mountOn 走 Vue 主路径；组件渲染逻辑正确 =====
    {
        const top = navSlot('app.top', { 'data-nav-link-class': 'app-nav-link' });
        const side = navSlot('gallery.sidebar', { 'data-nav-link-class': 'nav-item', 'data-nav-current': 'gallery' });
        const { PixivNav, record } = load({ slots: [top, side], items: ITEMS, pixivVue: true, pathname: '/monitor.html' });
        await PixivNav.ready();

        ok('1: 为 2 个 [data-nav-slot] 各经 mountOn 挂 Vue app（Vue 主路径）', record.mounts.length === 2);
        ok('1: 每个 mountOn 收到的是 slot 元素 + 组件（含 setup/template）',
            record.mounts.every(m => m.el && m.comp && typeof m.comp.setup === 'function' && typeof m.comp.template === 'string'));
        ok('1: 至少派发一次 pixivnav:rendered', renderedCount(record) >= 1);

        // 组件渲染逻辑（app.top）：navItems 按 placement 过滤、href 由贡献方声明、内层含图标 SVG + i18n label、当前项 active。
        const topComp = record.mounts.find(m => m.el === top).comp.setup();
        const topItems = topComp.navItems();
        ok('1: app.top 组件只含 placements 含 app.top 的项（gallery + monitor）',
            topItems.length === 2 && topItems.map(i => i.id).sort().join(',') === 'gallery,monitor');
        const galleryItem = topItems.find(i => i.id === 'gallery');
        ok('1: href 由贡献方完整声明（含 ?view=all，渲染器不补默认 query）',
            topComp.hrefOf(galleryItem) === '/pixiv-gallery.html?view=all');
        const inner = topComp.innerOf(galleryItem);
        ok('1: 内层含图标 SVG（images token）+ i18n label span', /<svg/.test(inner) && inner.indexOf('T:gallery:nav.label') >= 0);
        const monitorItem = topItems.find(i => i.id === 'monitor');
        ok('1: 当前页（/monitor.html）项 isCur 为真、clsOf 追加 active', topComp.isCur(monitorItem) === true
            && topComp.clsOf(monitorItem).indexOf('active') >= 0 && topComp.clsOf(galleryItem).indexOf('active') < 0);

        // 侧栏用显式 current=gallery：gallery 当前、其它非当前（与 pathname 无关）。
        const sideComp = record.mounts.find(m => m.el === side).comp.setup();
        const sideGallery = sideComp.navItems().find(i => i.id === 'gallery');
        ok('1: data-nav-current=gallery → gallery 为当前项（显式 current 优先于 pathname）', sideComp.isCur(sideGallery) === true);

        // 幂等：Vue 稳态下 refresh 不重复 mountOn。
        await PixivNav.refresh();
        ok('1: Vue 稳态 refresh() 不重复 mountOn（复用既有 app）', record.mounts.length === 2);
    }

    // ===== 场景 2：无 PixivVue → 命令式 innerHTML 渲染（与 Vue 同源链接），不抛、派发 rendered =====
    {
        const top = navSlot('app.top', { 'data-nav-link-class': 'app-nav-link' });
        const { PixivNav, record } = load({ slots: [top], items: ITEMS, pixivVue: false, pathname: '/monitor.html' });
        await PixivNav.ready();
        ok('2: 无 PixivVue 时不调用 mountOn（无 record）', record.mounts.length === 0);
        ok('2: 命令式 innerHTML 渲染了链接（含 i18n label + href）',
            top.innerHTML.indexOf('app-nav-link') >= 0 && top.innerHTML.indexOf('/pixiv-gallery.html?view=all') >= 0
            && top.innerHTML.indexOf('T:gallery:nav.label') >= 0);
        ok('2: 当前页 monitor 命令式渲染为 <span aria-current>（不可点击）', /<span[^>]*aria-current="page"/.test(top.innerHTML));
        ok('2: 派发 pixivnav:rendered', renderedCount(record) >= 1);
    }

    // ===== 场景 3：Vue 运行时加载失败（ensure reject）→ 命令式回退、不抛 =====
    {
        const top = navSlot('app.top', { 'data-nav-link-class': 'app-nav-link' });
        let threw = false;
        let r;
        try {
            r = load({ slots: [top], items: ITEMS, pixivVue: true, ensureRejects: true, pathname: '/monitor.html' });
            await r.PixivNav.ready();
        } catch (e) { threw = true; }
        ok('3: ensure 失败时模块不抛异常', threw === false);
        ok('3: ensure 失败回退命令式（slot 有命令式链接，ensure 在 mountOn 前失败故未 mountOn）',
            r.slots[0].innerHTML.indexOf('app-nav-link') >= 0 && r.record.mounts.length === 0);
        ok('3: 派发 pixivnav:rendered', renderedCount(r.record) >= 1);
    }

    // ===== 场景 4：拉取失败 → 清空全部 slot、派发 rendered =====
    {
        const top = navSlot('app.top', { 'data-nav-link-class': 'app-nav-link' });
        top.innerHTML = '<a class="stale">旧硬编码坏入口</a>'; // 预置坏入口，验证被清空
        const { PixivNav, record } = load({ slots: [top], items: null, fetchOk: false, pixivVue: true });
        await PixivNav.ready();
        ok('4: 拉取失败清空 slot（不残留坏入口）', top.innerHTML === '');
        ok('4: 拉取失败不挂 Vue（无数据不挂空 app）', record.mounts.length === 0);
        ok('4: 派发 pixivnav:rendered（监听方对零链接无操作）', renderedCount(record) >= 1);
    }

    // ===== 场景 5：图标外层 wrap + 仅图标（iconOnly）渲染分支 =====
    {
        const side = navSlot('gallery.sidebar', { 'data-nav-link-class': 'nav-item', 'data-nav-icon-wrap-class': 'nav-icon', 'data-nav-label-class': 'nav-label' });
        const iconbar = navSlot('app.top', { 'data-nav-link-class': 'icon-link', 'data-nav-icon-only': '' });
        const { PixivNav, record } = load({ slots: [side, iconbar], items: ITEMS, pixivVue: true, pathname: '/x' });
        await PixivNav.ready();
        const sideComp = record.mounts.find(m => m.el === side).comp.setup();
        const g = sideComp.navItems().find(i => i.id === 'gallery');
        ok('5: 有 wrap class 时内层图标外包 <span class="nav-icon"> + label <span class="nav-label">',
            sideComp.innerOf(g).indexOf('class="nav-icon"') >= 0 && sideComp.innerOf(g).indexOf('class="nav-label"') >= 0);
        const iconComp = record.mounts.find(m => m.el === iconbar).comp.setup();
        const gi = iconComp.navItems().find(i => i.id === 'gallery');
        ok('5: iconOnly → 内层无 label span（label 进 aria-label/title）',
            iconComp.innerOf(gi).indexOf('<span') < 0 && iconComp.iconLabelOf(gi) === 'T:gallery:nav.label');
    }

    // ===== 场景 6：labelNamespace 为纯空白（NavigationContribution 可空 = 有意回退语义）=====
    //   证明：① 前端不加载空白 namespace（collectNamespaces 跳过纯空白，create 收到的 namespaces 无空白项）；
    //         ② 该项 label 按裸 key 回退解析（resolveLabel→tns 对空白 namespace 退化为 t()，无 namespace 前缀）。
    {
        const top = navSlot('app.top', { 'data-nav-link-class': 'app-nav-link' });
        const items = [
            { id: 'gallery', placements: ['app.top'], href: '/pixiv-gallery.html', icon: 'images', labelNamespace: 'gallery', labelI18nKey: 'nav.label' },
            // 贡献方未绑定确定 namespace：labelNamespace 为纯空白（"  "）。
            { id: 'plugins', placements: ['app.top'], href: '/plugin-manage.html', icon: 'puzzle', labelNamespace: '  ', labelI18nKey: 'nav.plugins' }
        ];
        I18N.lastNamespaces = null;
        const { PixivNav, record } = load({ slots: [top], items: items, pixivVue: true, pathname: '/x' });
        await PixivNav.ready();

        // ① 不加载空白 namespace：加载集含 common + gallery、绝无空白项（每项 trim 后非空）。
        ok('6: create 收到的 namespaces 含 common + gallery',
            !!I18N.lastNamespaces && I18N.lastNamespaces.indexOf('common') >= 0 && I18N.lastNamespaces.indexOf('gallery') >= 0);
        ok('6: 加载集不含空白 namespace（无 "  " / 空串，每项 trim 后非空）',
            I18N.lastNamespaces.indexOf('  ') < 0 && I18N.lastNamespaces.indexOf('') < 0
            && I18N.lastNamespaces.every(function (ns) { return String(ns).trim().length > 0; }));

        // ② label 按裸 key 回退：空白 namespace 项 → 'T:nav.plugins'（无 namespace 前缀）；非空项仍按 namespace 解析。
        const topComp = record.mounts.find(m => m.el === top).comp.setup();
        const pluginsItem = topComp.navItems().find(i => i.id === 'plugins');
        const galleryItem = topComp.navItems().find(i => i.id === 'gallery');
        ok('6: 空白 namespace 项 label 走裸 key 回退（"T:nav.plugins"，无 namespace 前缀）',
            topComp.innerOf(pluginsItem).indexOf('T:nav.plugins') >= 0 && topComp.innerOf(pluginsItem).indexOf('T:  :') < 0);
        ok('6: 对照非空 namespace 项仍按 namespace 解析（"T:gallery:nav.label"）',
            topComp.innerOf(galleryItem).indexOf('T:gallery:nav.label') >= 0);
    }

    console.log(`\npixiv-navigation.test.js: ${passed} assertions passed ✓`);
}

main().catch(err => { console.error('TEST FAILED:', err && err.stack ? err.stack : err); process.exit(1); });
