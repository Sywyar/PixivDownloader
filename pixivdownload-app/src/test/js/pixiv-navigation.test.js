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
 *   6) 选择器安全：只用固定字面 slot / 偏好链接选择器（mock querySelectorAll 对其它选择器抛错即守卫）。
 *   7) 下载页偏好：工作台页面记录当前 URL，顶部导航与独立返回链接复用同一 marker 目标。
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

function makeDocument(slots, anchors, rootAttrs) {
    const body = new El('body');
    slots.forEach(s => body.appendChild(s));
    (anchors || []).forEach(a => body.appendChild(a));
    const head = new El('head');
    const documentElement = new El('html');
    Object.keys(rootAttrs || {}).forEach(k => documentElement.setAttribute(k, rootAttrs[k]));
    return {
        head, body, documentElement, readyState: 'complete',
        createElement: t => new El(t),
        addEventListener() {},
        // 仅支持两个固定字面选择器；任何其它选择器抛错 = 「target 绝不拼进选择器」守卫。
        querySelectorAll(sel) {
            if (sel !== '[data-nav-slot]' && sel !== '[data-nav-preferred-href-marker]') {
                throw new Error('Unexpected selector (target must never be interpolated): ' + sel);
            }
            const out = [];
            const attr = sel === '[data-nav-slot]' ? 'data-nav-slot' : 'data-nav-preferred-href-marker';
            (function walk(n) { n.children.forEach(c => { if (c.getAttribute(attr) !== null) out.push(c); walk(c); }); })(body);
            return out;
        }
    };
}

function makeStorage(initial) {
    const values = new Map(Object.entries(initial || {}));
    return {
        getItem: key => values.has(key) ? values.get(key) : null,
        setItem: (key, value) => values.set(String(key), String(value)),
        value: key => values.get(key)
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

// 使用真实 Vue 并禁止动态编译，避免模板在严格 CSP 下挂载失败却被桩掩盖。
function makeVue() {
    const sandbox = {};
    vm.createContext(sandbox, { codeGeneration: { strings: false, wasm: false } });
    vm.runInContext(fs.readFileSync(path.join(__dirname, '../../main/resources/static/vendor/vue/vue.global.prod.js'), 'utf8'), sandbox);
    return sandbox.Vue;
}

// 仅替代 DOM 存储；组件初始化与渲染实际执行。
function makePixivVue(record, ensureRejects) {
    const vue = makeVue();
    function remove(child) {
        if (child.parent) child.parent.children.splice(child.parent.children.indexOf(child), 1);
        child.parent = null;
    }
    const renderer = vue.createRenderer({
        createElement: tag => new El(tag),
        createText: text => Object.assign(new El('#text'), { text }),
        createComment: () => new El('#comment'),
        setText(el, text) { el.text = text; },
        setElementText(el, text) { el.text = text; el.children = []; },
        patchProp(el, key, previous, value) { el.attrs[key] = value; },
        parentNode: el => el.parent,
        nextSibling: el => el.parent?.children[el.parent.children.indexOf(el) + 1] || null,
        insert(el, parent, anchor = null) {
            remove(el);
            parent.children.splice(anchor ? parent.children.indexOf(anchor) : parent.children.length, 0, el);
            el.parent = parent;
        },
        remove
    });
    return {
        ensure: () => ensureRejects ? Promise.reject(new Error('vue load failed')) : Promise.resolve(vue),
        mountOn: (el, comp) => {
            el.innerHTML = '';
            const app = renderer.createApp(comp);
            const instance = app.mount(el);
            record.mounts.push({ el, comp });
            return Promise.resolve({ app, vm: instance, el });
        }
    };
}

function load(opts) {
    const slots = opts.slots;
    const document = makeDocument(slots, opts.anchors, opts.rootAttrs);
    const localStorage = makeStorage(opts.storage);
    const record = { mounts: [], events: [] };
    const sandbox = {
        document,
        location: { origin: 'http://localhost', pathname: opts.pathname || '/monitor.html', search: opts.search || '' },
        localStorage,
        URL,
        console: { warn() {}, log() {}, error() {} },
        setTimeout, clearTimeout, Promise,
        AbortController,
        fetch: opts.fetch || makeFetch(opts.items, opts.fetchOk),
        CustomEvent: class { constructor(type, init) { this.type = type; this.detail = init && init.detail; } },
        dispatchEvent(e) { record.events.push(e); }
    };
    sandbox.window = sandbox; // 模块用 window 作 global
    if (opts.i18n !== false) sandbox.PixivI18n = I18N;
    if (opts.pixivVue) sandbox.PixivVue = makePixivVue(record, opts.ensureRejects);
    vm.createContext(sandbox);
    vm.runInContext(SRC, sandbox);
    return { PixivNav: sandbox.PixivNav, document, localStorage, record, slots };
}

function renderedCount(record) { return record.events.filter(e => e.type === 'pixivnav:rendered').length; }

const ITEMS = [
    { id: 'gallery', placements: ['app.top', 'gallery.sidebar'], href: '/pixiv-gallery.html?view=all', icon: 'images', labelNamespace: 'gallery', labelI18nKey: 'nav.label', markers: ['first-download-result'] },
    { id: 'monitor', placements: ['app.top'], href: '/monitor.html', icon: 'monitor', labelNamespace: 'monitor', labelI18nKey: 'nav.monitor' },
    { id: 'novel-gallery', placements: ['gallery.sidebar'], href: '/pixiv-novel-gallery.html?view=all', icon: 'book', labelNamespace: 'novel-gallery', labelI18nKey: 'nav.label' }
];

async function main() {
    // ===== 场景 1：Vue 就位 → 经 ensure + mountOn 走 Vue 主路径；组件渲染逻辑正确 =====
    {
        const top = navSlot('app.top', { 'data-nav-link-class': 'app-nav-link' });
        const side = navSlot('gallery.sidebar', { 'data-nav-link-class': 'nav-item', 'data-nav-current': 'gallery' });
        const { PixivNav, record } = load({ slots: [top, side], items: ITEMS, pixivVue: true, pathname: '/monitor.html' });
        await PixivNav.ready();

        ok('1: 为 2 个 [data-nav-slot] 各经 mountOn 挂 Vue app（Vue 主路径）', record.mounts.length === 2);
        const links = top.children.filter(el => el.tag === 'a' || el.tag === 'span');
        ok('1: 严格 CSP 下实际渲染链接与当前项', links.length === 2
            && links[0].tag === 'a' && links[0].attrs.href === '/pixiv-gallery.html?view=all'
            && links[1].tag === 'span' && links[1].attrs['aria-current'] === 'page');
        ok('1: 至少派发一次 pixivnav:rendered', renderedCount(record) >= 1);

        // 组件渲染逻辑（app.top）：navItems 按 placement 过滤、href 由贡献方声明、内层含图标 SVG + i18n label、当前项 active。
        const topComp = record.mounts.find(m => m.el === top).comp.setup();
        const topItems = topComp.navItems();
        ok('1: app.top 组件只含 placements 含 app.top 的项（gallery + monitor）',
            topItems.length === 2 && topItems.map(i => i.id).sort().join(',') === 'gallery,monitor');
        const galleryItem = topItems.find(i => i.id === 'gallery');
        ok('1: href 由贡献方完整声明（含 ?view=all，渲染器不补默认 query）',
            topComp.hrefOf(galleryItem) === '/pixiv-gallery.html?view=all');
        ok('1: markers 渲染为可选择的中性 data attribute',
            topComp.markersOf(galleryItem) === 'first-download-result');
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

    // ===== 场景 7：导航统一使用贡献地址，已有浏览器偏好不改写入口 =====
    {
        const preferredKey = 'pixiv:nav-preferred:preferred-download-workbench';
        const download = {
            id: 'download-workbench', placements: ['app.top'], href: '/pixiv-batch.html', icon: 'download',
            labelNamespace: 'batch', labelI18nKey: 'nav.label', markers: ['preferred-download-workbench']
        };
        const first = load({
            slots: [], items: [download], pathname: '/pixiv-batch-alt.html',
            rootAttrs: {'data-nav-remember-marker': 'preferred-download-workbench'}
        });
        await first.PixivNav.ready();
        ok('7: 打开新版工作台不再写入浏览器偏好',
            first.localStorage.value(preferredKey) == null);

        const top = navSlot('app.top', {'data-nav-link-class': 'app-nav-link'});
        const back = new El('a');
        back.setAttribute('href', '/pixiv-batch.html');
        back.setAttribute('data-nav-preferred-href-marker', 'preferred-download-workbench');
        const second = load({
            slots: [top], anchors: [back], items: [download], pathname: '/pixiv-notifications.html',
            storage: {[preferredKey]: '/pixiv-batch-alt.html'}
        });
        await second.PixivNav.ready();
        ok('7: 顶部下载导航保持统一入口',
            top.innerHTML.indexOf('href="/pixiv-batch.html"') >= 0);
        ok('7: 独立返回链接保持统一入口', back.getAttribute('href') === '/pixiv-batch.html');
        const vueTop = navSlot('app.top');
        const vuePage = load({slots: [vueTop], items: [download], pixivVue: true,
            storage: {[preferredKey]: '/pixiv-batch-alt.html'}});
        await vuePage.PixivNav.ready();
        ok('7: Vue 渲染同样忽略浏览器旧偏好',
            vueTop.children.some(link => link.attrs.href === '/pixiv-batch.html'));

        const unsafe = load({
            slots: [navSlot('app.top')], items: [download], pathname: '/monitor.html',
            storage: {[preferredKey]: '//example.invalid/steal'}
        });
        await unsafe.PixivNav.ready();
        ok('7: 非同源旧偏好也不会影响贡献方地址',
            unsafe.slots[0].innerHTML.indexOf('href="/pixiv-batch.html"') >= 0);
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
        ok('2: 命令式 innerHTML 输出 data-nav-markers',
            top.innerHTML.indexOf('data-nav-markers="first-download-result"') >= 0);
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

    for (const pixivVue of [true, false]) {
        const slot = navSlot('app.top');
        const item = { id: 'management', placements: ['app.top'], href: '/manage', labelNamespace: 'plugins',
            labelI18nKey: 'nav.label', markers: ['plugin-update-summary'] };
        let calls = 0;
        let status = 200;
        let summary = { enabled: true, compatibleUpdates: 2, sdkBlockedUpdates: 1 };
        const runtime = load({ slots: [slot], pixivVue, fetch: async url => {
            if (url === '/api/navigation') return { ok: true, json: async () => [item] };
            calls++;
            return { ok: status === 200, status, json: async () => summary };
        } });
        await runtime.PixivNav.ready();
        await runtime.PixivNav.pluginUpdates();
        const html = () => pixivVue
            ? slot.children.filter(e => e.tag === 'a').map(e => e.attrs.innerHTML).join('') : slot.innerHTML;
        ok('更新数量与 SDK 阻断角标同时显示，带可访问文案', /pnav-update-blocked/.test(html())
            && /updates.compatible/.test(html()) && /updates.sdkBlocked/.test(html()) && html().includes('>2<'));
        ok('导航与管理页复用进行中的一次请求', calls === 1);
        summary = { enabled: true, checkFailed: true, compatibleUpdates: 0, sdkBlockedUpdates: 0 };
        await runtime.PixivNav.pluginUpdates();
        ok('后续查询失败会撤下旧角标', !html().includes('pnav-update-badge'));
        status = 404;
        const absent = await runtime.PixivNav.pluginUpdates();
        ok('市场禁用时更新能力缺席，不显示失败或旧角标', absent.enabled === false
            && absent.checkFailed === false && !html().includes('pnav-update-badge'));
    }
    console.log(`\npixiv-navigation.test.js: ${passed} assertions passed ✓`);
}

main().catch(err => { console.error('TEST FAILED:', err && err.stack ? err.stack : err); process.exit(1); });
