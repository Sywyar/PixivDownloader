(function (global) {
    'use strict';
    // ============================================================
    //  共享跨插件导航渲染模块（PixivNav）
    //
    //  目标：把各页面顶部 / 侧边栏 / 类型切换 / 图标区的「跨插件入口」从硬编码 HTML 改为消费后端活动插件
    //  registry —— 禁用某插件后其入口在前端自然消失，不再靠「点开后 404」表达禁用，也不再靠页面 include/exclude
    //  /requires 过滤。前端隐藏只改善体验，后端鉴权（AuthFilter）仍是唯一权限边界。
    //
    //  数据来源：GET /api/navigation —— 后端已按当前请求身份（管理员 / 受邀访客 / multi 匿名访客）过滤、
    //  按「来源层级 → placement 内 priority → id」排序，每项带 placements（它要进入的 slot 集合），
    //  只返回当前用户「点开不会被挡」的入口。本模块按 placement 把它们渲染进各空 slot。
    //
    //  渲染路径（reactive 主路径 + 命令式回退）：每个 [data-nav-slot] 锚点的链接列表由 Vue（reactive 数据驱动）
    //  渲染——经共享 helper window.PixivVue.ensure() 按需懒加载核心 Vue 运行时（运行时为单一来源、路径只由 helper 解析，本模块
    //  不硬编码路径），用 PixivVue.mountOn(slot, 组件) 把一个 Vue app 挂到该 slot（链接成为 slot 的直接子节点，
    //  保持既有 flex / 相邻 CSS）；各 slot app 共享同一 reactive 状态（导航项 + i18n），语言切换 / 列表变化即自动
    //  重渲染、无需逐 slot 重建 innerHTML。**始终保留命令式回退**：首次渲染先命令式即时出图（首屏占位），再升级为
    //  Vue 主渲染（成功即取代命令式内容、之后稳态走 Vue）；window.PixivVue 缺失 / Vue 运行时加载 / 挂载失败一律
    //  优雅回退命令式渲染，**绝不向宿主 init 抛异常**。slot 元素只经**固定字面**选择器 [data-nav-slot] + getAttribute
    //  定位，target 绝不拼进选择器。
    //
    //  宿主页约定（声明式 data 属性，无需每页写 JS）：
    //   · <容器 data-nav-slot="<placement>" ...>：空 slot 锚点。本模块取所有 placements 含该 <placement> 的
    //       导航项、按响应顺序渲染进此容器。页面不再用 include/exclude 过滤 id —— 入口归属完全由后端 placement 决定。
    //       - data-nav-current="<id>"：显式当前项（不可点击 + aria-current）；缺省时按当前 pathname 推断。
    //       - data-nav-link-class / data-nav-active-class（默认 active）：链接元素及当前项的 class，
    //         令各页面复用自己既有的 nav CSS（顶部 app-nav-link、侧栏 nav-item、图标 icon-link、类型切换 gallery-type-option）。
    //       - data-nav-icon-wrap-class / data-nav-label-class：图标外层 / 文字 span 的 class（侧栏用 nav-icon/nav-label）。
    //       - data-nav-icon-only：仅渲染图标（label 进 title/aria-label）。
    //       - data-nav-no-icon：仅渲染文字、不渲染图标（类型切换 tab 用）。
    //       - data-nav-item-role：给每个渲染项加 role（如 tab）并按当前项写 aria-selected（类型切换 tablist 用）。
    //       - data-nav-target / data-nav-rel：链接 target / rel（如顶部 / 侧栏跨页用 _blank / noopener）。
    //
    //  失败降级：拉取失败时清空全部 slot（维持空），绝不回退旧硬编码跨插件链接（坏入口）。
    //  就绪通知：PixivNav.ready() 返回一个在首次渲染完成（成功或失败）后 resolve 的 Promise；每次渲染完成后
    //  还会在 window 上派发 'pixivnav:rendered' 事件——动态 slot 链接此时已生成，宿主页可据此对 slot 内的链接做
    //  通用处理（如类型切换页面把当前 view 同步进跨页链接、点击时登记跨页交接），无需猜测异步渲染时机。
    //  Vue 主渲染下该事件在 Vue DOM 更新（nextTick）后派发，链接已就位；宿主的 href 同步因 Vue 只在绑定值变化时
    //  才打补丁（href 由贡献方完整声明、跨语言不变）而**不被重渲染覆盖**。
    //  语言切换：跨标签页经 PixivI18n.onLanguageChange 自动重渲染；同页切换由宿主在其语言切换 onChange
    //  里调用 PixivNav.refresh()。图标用内联 SVG（Feather 线性风格），各页面无需引入 Font Awesome。
    // ============================================================

    var NAV_ENDPOINT = '/api/navigation';

    // 图标 token → 内联 SVG 内容。与各页面既有 nav 图标一致，自带 fill/stroke 故任何页面都能正确渲染。
    var ICON_PATHS = {
        'download': '<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/>',
        'images': '<rect x="3" y="3" width="18" height="18" rx="2" ry="2"/><circle cx="8.5" cy="8.5" r="1.5"/><polyline points="21 15 16 10 5 21"/>',
        'book': '<path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/><path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"/>',
        'monitor': '<rect x="2" y="3" width="20" height="14" rx="2" ry="2"/><line x1="8" y1="21" x2="16" y2="21"/><line x1="12" y1="17" x2="12" y2="21"/>',
        'chart-bar': '<line x1="18" y1="20" x2="18" y2="10"/><line x1="12" y1="20" x2="12" y2="4"/><line x1="6" y1="20" x2="6" y2="14"/>',
        'copy': '<rect x="3" y="3" width="8" height="8" rx="1"/><rect x="13" y="13" width="8" height="8" rx="1"/><path d="M13 7h5a3 3 0 0 1 3 3v5"/><path d="M11 17H6a3 3 0 0 1-3-3V9"/>',
        'invite-manage': '<path d="M9 11l3 3L22 4"/><path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11"/>',
        // 通用图标，供任意导航贡献按 icon token 引用：grid（网格）/ users（用户组）/ puzzle（拼图，插件）/ store（店面，插件市场）。
        'grid': '<rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/>',
        'users': '<path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/>',
        'puzzle': '<path d="M20.5 11H19V7a2 2 0 0 0-2-2h-4V3.5a2.5 2.5 0 0 0-5 0V5H4a2 2 0 0 0-2 2v3.8h1.5a2.2 2.2 0 1 1 0 4.4H2V19a2 2 0 0 0 2 2h3.8v-1.5a2.2 2.2 0 1 1 4.4 0V21H17a2 2 0 0 0 2-2v-4h1.5a2.5 2.5 0 0 0 0-5z"/>',
        'store': '<path d="M3 9l1.8-5.4A1 1 0 0 1 5.75 3h12.5a1 1 0 0 1 .95.6L21 9"/><path d="M4 9v9a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9"/><path d="M3 9h18"/><path d="M9 20v-5h6v5"/>'
    };
    // 未知 icon 的兜底（通用链接图标）。
    var FALLBACK_ICON = '<path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"/><path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"/>';

    var state = { items: null };   // 最近一次成功的 /api/navigation 响应（按身份过滤、已排序）
    var loaded = false;            // 是否至少成功拉取过一次
    var inFlight = false;          // 初次拉取是否在途（避免与 refresh 重复拉取）
    var languageSubscribed = false;
    var currentI18n = null;        // 最近一次按当前语言构造的 i18n 解析器（命令式 + Vue 共用）

    // —— Vue 主渲染状态 ——
    var vueRuntime = null;         // window.Vue（ensure() 解析后缓存，供 nextTick / reactive）
    var vueState = null;           // Vue.reactive({ items, i18n })：各 slot app 共享，置换即触发重渲染
    var slotApps = [];             // [{ el, app }] 已挂载的 slot Vue app（幂等复用 + 卸载）
    var vueMode = false;           // 是否已进入 Vue 主渲染稳态（至少一个 slot 挂上 Vue）

    var resolveReady;
    // 首次拉取完成（成功或失败）后 resolve；宿主页经 PixivNav.ready() 等导航数据到位再做条件渲染。
    var readyPromise = new global.Promise(function (resolve) { resolveReady = resolve; });
    var readyResolved = false;

    function qsa(selector) {
        return Array.prototype.slice.call(global.document.querySelectorAll(selector));
    }

    function escapeAttr(value) {
        return String(value == null ? '' : value)
            .replace(/&/g, '&amp;').replace(/"/g, '&quot;')
            .replace(/</g, '&lt;').replace(/>/g, '&gt;');
    }

    function escapeText(value) {
        return String(value == null ? '' : value)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    }

    // 收集需预加载的 i18n namespace。导航贡献的 labelNamespace 可空（有意回退语义）：null / "" / 纯空白都跳过——
    // 不请求空白 namespace bundle；非空的先 trim 再收集。label 的实际解析仍由渲染期 resolveLabel→tns 统一处理
    // （tns 对空白 namespace 自动退化为裸 key 回退），此处只决定「加载哪些 namespace」。
    function collectNamespaces(items) {
        var seen = { 'common': true };
        items.forEach(function (it) {
            var ns = it.labelNamespace == null ? '' : String(it.labelNamespace).trim();
            if (ns) seen[ns] = true;
        });
        return Object.keys(seen);
    }

    function iconSvg(icon, extraClass) {
        var inner = ICON_PATHS[icon] || FALLBACK_ICON;
        var cls = extraClass ? ' class="' + extraClass + '"' : '';
        return '<svg' + cls + ' viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"'
            + ' stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' + inner + '</svg>';
    }

    function baseHref(href) {
        if (!href) return '';
        var q = href.indexOf('?');
        return q >= 0 ? href.slice(0, q) : href;
    }

    function hrefFor(item) {
        // href 由贡献方完整声明（含任何 query）；公共渲染器不为任何插件 id 补默认 query。
        return item.href || '#';
    }

    function resolveLabel(i18n, item) {
        if (i18n && typeof i18n.tns === 'function') {
            return i18n.tns(item.labelNamespace, item.labelI18nKey, item.id);
        }
        return item.id;
    }

    function placementsOf(item) {
        return Array.isArray(item.placements) ? item.placements : [];
    }

    // 读取某 slot 的渲染选项（命令式与 Vue 共用同一份语义）。
    function readOpt(slot) {
        return {
            linkClass: slot.getAttribute('data-nav-link-class') || '',
            activeClass: slot.getAttribute('data-nav-active-class') || 'active',
            wrapClass: slot.getAttribute('data-nav-icon-wrap-class') || '',
            labelClass: slot.getAttribute('data-nav-label-class') || '',
            iconOnly: slot.hasAttribute('data-nav-icon-only'),
            noIcon: slot.hasAttribute('data-nav-no-icon'),
            itemRole: slot.getAttribute('data-nav-item-role') || '',
            target: slot.getAttribute('data-nav-target') || '',
            rel: slot.getAttribute('data-nav-rel') || ''
        };
    }

    // 当前项 class（链接 class + 当前项追加 active class），命令式与 Vue 共用。
    function clsFor(opt, isCurrent) {
        return opt.linkClass + (isCurrent ? (opt.linkClass ? ' ' : '') + opt.activeClass : '');
    }

    // 某导航项的内层 HTML（图标 + 文字 span），命令式与 Vue 共用（Vue 经 v-html 注入这段，外层 <a>/<span>
    // 仍是真实 Vue 元素，故 label 经 escapeText、图标为受信任内联 SVG）。
    function itemInnerHtml(item, label, opt) {
        var iconHtml = '';
        if (!opt.noIcon) {
            var icon = iconSvg(item.icon, opt.wrapClass ? '' : 'pnav-ico');
            iconHtml = opt.wrapClass ? '<span class="' + escapeAttr(opt.wrapClass) + '">' + icon + '</span>' : icon;
        }
        var labelHtml = opt.iconOnly ? ''
            : '<span' + (opt.labelClass ? ' class="' + escapeAttr(opt.labelClass) + '"' : '') + '>'
                + escapeText(label) + '</span>';
        return iconHtml + labelHtml;
    }

    function buildItemHtml(item, label, isCurrent, opt) {
        var cls = clsFor(opt, isCurrent);
        var inner = itemInnerHtml(item, label, opt);
        var roleAttr = opt.itemRole ? ' role="' + escapeAttr(opt.itemRole) + '"' : '';
        var selectedAttr = opt.itemRole ? ' aria-selected="' + (isCurrent ? 'true' : 'false') + '"' : '';
        if (isCurrent) {
            var curAria = opt.iconOnly ? ' aria-label="' + escapeAttr(label) + '"' : '';
            return '<span class="' + escapeAttr(cls) + '" aria-current="page"' + roleAttr + selectedAttr + curAria + '>'
                + inner + '</span>';
        }
        var attrs = ' href="' + escapeAttr(hrefFor(item)) + '"' + roleAttr + selectedAttr;
        if (opt.target) attrs += ' target="' + escapeAttr(opt.target) + '"';
        if (opt.rel) attrs += ' rel="' + escapeAttr(opt.rel) + '"';
        if (opt.iconOnly) attrs += ' title="' + escapeAttr(label) + '" aria-label="' + escapeAttr(label) + '"';
        return '<a class="' + escapeAttr(cls) + '"' + attrs + '>' + inner + '</a>';
    }

    function currentMatcher(slot) {
        var explicitCurrent = slot.getAttribute('data-nav-current'); // null → 按 pathname 推断
        var pathname = (global.location && global.location.pathname) || '';
        return function (item) {
            return explicitCurrent != null ? (item.id === explicitCurrent) : (baseHref(item.href) === pathname);
        };
    }

    // 命令式渲染单个 slot（首屏即时出图 + Vue 不可用时的回退；行为与历史逐字一致）。
    function renderSlot(slot, items, i18n) {
        var placement = slot.getAttribute('data-nav-slot');
        if (!placement) { slot.innerHTML = ''; return; }
        var isCurrent = currentMatcher(slot);
        var opt = readOpt(slot);
        var html = items.filter(function (it) {
            return placementsOf(it).indexOf(placement) !== -1;
        }).map(function (it) {
            return buildItemHtml(it, resolveLabel(i18n, it), isCurrent(it), opt);
        }).join('');
        slot.innerHTML = html;
    }

    // —— Vue 主渲染 ——

    // 单个导航项模板：外层 <a>/<span> 为真实 Vue 元素（成为 slot 的直接子节点，保持既有相邻 / flex CSS），
    // 图标 + 文字内层经 v-html 注入（label 已 escapeText、图标为受信任 SVG）。属性为 null 时 Vue 自动省略。
    var NAV_ITEM_TEMPLATE =
        '<template v-for="it in navItems()" :key="it.id">'
        + '<span v-if="isCur(it)" :class="clsOf(it)" aria-current="page" :role="roleAttr"'
        + ' :aria-selected="selOf(it)" :aria-label="iconLabelOf(it)" v-html="innerOf(it)"></span>'
        + '<a v-else :class="clsOf(it)" :href="hrefOf(it)" :role="roleAttr" :aria-selected="selOf(it)"'
        + ' :target="targetAttr" :rel="relAttr" :title="iconLabelOf(it)" :aria-label="iconLabelOf(it)"'
        + ' v-html="innerOf(it)"></a>'
        + '</template>';

    // 据 slot 构造其 Vue 组件：读取一次该 slot 的 opt / placement / 当前项判定，渲染读共享 reactive 状态
    //（vueState.items / vueState.i18n）——列表或语言变化即自动重渲染。
    function buildSlotComponent(slot) {
        var placement = slot.getAttribute('data-nav-slot');
        var opt = readOpt(slot);
        var isCurrent = currentMatcher(slot);
        return {
            setup: function () {
                function label(it) { return resolveLabel(vueState.i18n, it); }
                return {
                    navItems: function () {
                        return ((vueState && vueState.items) || []).filter(function (it) {
                            return placementsOf(it).indexOf(placement) !== -1;
                        });
                    },
                    isCur: isCurrent,
                    clsOf: function (it) { return clsFor(opt, isCurrent(it)); },
                    hrefOf: function (it) { return hrefFor(it); },
                    innerOf: function (it) { return itemInnerHtml(it, label(it), opt); },
                    selOf: function (it) { return opt.itemRole ? (isCurrent(it) ? 'true' : 'false') : null; },
                    iconLabelOf: function (it) { return opt.iconOnly ? label(it) : null; },
                    roleAttr: opt.itemRole || null,
                    targetAttr: opt.target || null,
                    relAttr: opt.rel || null
                };
            },
            template: NAV_ITEM_TEMPLATE
        };
    }

    function hasSlotApp(slot) {
        for (var i = 0; i < slotApps.length; i++) {
            if (slotApps[i].el === slot) return true;
        }
        return false;
    }

    // 升级 / 维持 Vue 主渲染：懒加载运行时 → 建 / 更新共享 reactive 状态 → 为尚未挂载的 slot 经统一 helper
    // mountOn 挂 Vue app（命中失败的 slot 保留命令式首屏、不阻断其它 slot）。返回是否处于 Vue 稳态。
    // PixivVue 缺失 / 运行时加载失败一律收敛、不抛——命令式首屏结果原样保留（优雅降级）。
    function upgradeSlotsToVue(slots) {
        if (!global.PixivVue || typeof global.PixivVue.ensure !== 'function') {
            return global.Promise.resolve(false);
        }
        return global.PixivVue.ensure().then(function (Vue) {
            if (!Vue) return false;
            vueRuntime = Vue;
            if (!vueState) vueState = Vue.reactive({ items: [], i18n: null });
            vueState.items = state.items || [];
            vueState.i18n = currentI18n;
            var pending = [];
            slots.forEach(function (slot) {
                if (!slot.getAttribute('data-nav-slot') || hasSlotApp(slot)) return;
                pending.push(global.PixivVue.mountOn(slot, buildSlotComponent(slot)).then(function (handle) {
                    if (handle && handle.app) slotApps.push({ el: slot, app: handle.app });
                }));
            });
            return global.Promise.all(pending).then(function () {
                vueMode = slotApps.length > 0;
                return vueMode;
            });
        }).catch(function (e) {
            console.warn('[PixivNav] Vue 运行时不可用，沿用命令式渲染：', e);
            return false;
        });
    }

    // 等 Vue 把 reactive 变更刷进 DOM（nextTick）后再派发渲染事件，确保宿主在链接就位后处理。
    function afterVueRender() {
        if (vueRuntime && typeof vueRuntime.nextTick === 'function') {
            try { return vueRuntime.nextTick(); } catch (e) { /* 忽略：退化为立即 resolve */ }
        }
        return global.Promise.resolve();
    }

    async function renderFromState() {
        var slots = qsa('[data-nav-slot]');
        if (state.items == null) {
            // 失败降级：清空全部 slot，不残留硬编码坏入口。已 Vue 化时经 reactive 置空集渲染为空。
            currentI18n = null;
            if (vueMode && vueState) {
                vueState.items = [];
                vueState.i18n = null;
                await afterVueRender();
            } else {
                slots.forEach(function (s) { s.innerHTML = ''; });
            }
            dispatchRendered();
            return;
        }
        currentI18n = await buildI18n(state.items);
        if (vueMode) {
            // Vue 主渲染稳态：更新共享 reactive（各 app 自动重渲染）+ 为新出现的 slot（如 page-section 注入者）补挂。
            await upgradeSlotsToVue(slots);
            await afterVueRender();
            dispatchRendered();
            return;
        }
        // 首次渲染：先命令式即时出图（首屏占位 + 回退），再升级为 Vue 主渲染（成功即取代命令式内容）。
        slots.forEach(function (slot) { renderSlot(slot, state.items, currentI18n); });
        await upgradeSlotsToVue(slots);
        // upgrade 内 app.mount 为同步：成功则各 slot 已是 Vue 内容，此处派发即链接已就位。
        dispatchRendered();
    }

    function dispatchRendered() {
        try {
            global.dispatchEvent(new global.CustomEvent('pixivnav:rendered', { detail: { items: state.items } }));
        } catch (e) { /* 古老环境不支持 CustomEvent 构造器：忽略（不影响渲染本身） */ }
    }

    function markReady() {
        if (readyResolved) return;
        readyResolved = true;
        resolveReady();
    }

    async function buildI18n(items) {
        if (!global.PixivI18n || typeof global.PixivI18n.create !== 'function') {
            return null;
        }
        try {
            return await global.PixivI18n.create({ namespaces: collectNamespaces(items) });
        } catch (e) {
            return null;
        }
    }

    async function fetchNav() {
        var res = await global.fetch(NAV_ENDPOINT, { credentials: 'same-origin' });
        if (!res.ok) {
            throw new Error('navigation http ' + res.status);
        }
        var data = await res.json();
        return Array.isArray(data) ? data : [];
    }

    function subscribeLanguageOnce() {
        if (languageSubscribed) return;
        if (global.PixivI18n && typeof global.PixivI18n.onLanguageChange === 'function') {
            global.PixivI18n.onLanguageChange(function () { refresh(); });
            languageSubscribed = true;
        }
    }

    // 初次装配：拉取 /api/navigation 并渲染全部 slot。幂等（重复调用在途时直接返回）。
    async function mount() {
        if (inFlight) return;
        inFlight = true;
        try {
            state.items = await fetchNav();
            loaded = true;
        } catch (e) {
            state.items = null;
            console.warn('[PixivNav] 拉取导航失败，隐藏跨插件入口：', e);
        } finally {
            inFlight = false;
        }
        await renderFromState();
        subscribeLanguageOnce();
        markReady();
    }

    // 重渲染（语言切换后由宿主调用，或跨标签页语言广播触发）：复用已缓存导航数据、按当前语言重解析标签。
    async function refresh() {
        if (inFlight) return;          // 初次装配在途：其完成时会用当前语言渲染
        if (!loaded) return mount();   // 尚未成功拉取过：走初次装配
        await renderFromState();
    }

    function autoMount() {
        if (global.document.readyState === 'loading') {
            global.document.addEventListener('DOMContentLoaded', function () { mount(); });
        } else {
            mount();
        }
    }

    global.PixivNav = {
        mount: mount,
        refresh: refresh,
        // 首次渲染完成（成功或失败）后 resolve；供宿主在导航数据到位、slot 链接渲染后再处理。
        ready: function () { return readyPromise; }
    };

    autoMount();
})(window);
