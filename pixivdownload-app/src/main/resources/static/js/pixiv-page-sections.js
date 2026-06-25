(function (global) {
    'use strict';
    // ============================================================
    //  通用页面区块渲染模块（PixivPageSections）
    //
    //  目标：让宿主页面只声明稳定的「区块 slot」（data-section-slot="<placement>"），把那些「借用其它插件能力的
    //  页面内业务块」（标题 / 操作入口 / 内嵌导航 slot / 由贡献方自有 JS 渲染的列表）从硬编码、按插件 id 显隐，
    //  改为由后端活动插件经 /api/page-sections 贡献——宿主页面不需要知道是哪个插件、是否启用。禁用某插件后
    //  它贡献的区块（及内嵌导航、操作入口、列表）自然消失，不再靠页面端按 id 隐藏来模拟。
    //
    //  数据来源：GET /api/page-sections?placement=<placement> —— 后端按当前请求身份过滤、按 priority 排序，
    //  只返回当前用户可见的区块。前端隐藏不是安全边界：区块内任何 href / API 仍由后端 AuthFilter 鉴权。
    //
    //  渲染路径（reactive 主路径 + 命令式回退）：区块**骨架**（page-section / header / 标题 / 操作入口 / 空的内嵌
    //  导航 slot 容器 / 空的 page-section-body 容器 / 分隔线）由 Vue（reactive 数据驱动）渲染——经共享 helper
    //  window.PixivVue.ensure() 按需懒加载核心 Vue 运行时（运行时为单一来源、路径只由 helper 解析、本模块不硬编码），用
    //  PixivVue.mountOn(slot, 组件) 把一个 Vue app 挂到该 [data-section-slot]；语言切换即由共享 reactive i18n 自动
    //  重译标题。**职责分离**：Vue 只拥有区块骨架，**内嵌导航的链接仍由 PixivNav 填充、moduleUrl body 的内容仍由
    //  贡献方模块填充**——故骨架模板把 <nav data-nav-slot> 与 .page-section-body 渲染为**空** Vue 元素（无 Vue 子
    //  节点）：Vue 在重渲染时对「无子节点」的元素不动其实际子节点，PixivNav / 贡献方填进去的内容因此**不被覆盖**。
    //  **始终保留命令式回退**：window.PixivVue 缺失 / Vue 运行时加载 / 挂载失败的 slot 一律命令式渲染（innerHTML），
    //  **绝不向宿主 init 抛异常**。slot 元素只经**固定字面**选择器 [data-section-slot] + getAttribute 定位。
    //
    //  宿主页约定（声明式 data 属性，无需每页写 JS）：
    //   <容器 data-section-slot="<placement>" ...>：空 slot 锚点。可选地用 data-section-*-class 覆盖各部件 class，
    //       缺省取常见侧栏 class（section-header / section-title / section-add / sidebar-nav / sidebar-divider /
    //       nav-item / nav-icon / nav-label），故标准侧栏只需写 data-section-slot 一项。
    //
    //  内嵌导航 slot：区块若声明 navPlacement，渲染器注入一个 data-nav-slot 元素并调用 PixivNav.refresh()，
    //  其链接由 /js/pixiv-navigation.js 据匹配该 placement 的导航贡献渲染（与主导航同一套身份过滤）。
    //  前端模块钩子：区块若声明 moduleUrl，渲染器渲染一个空的 .page-section-body 容器、按 URL 去重加载该脚本，
    //  并在每次渲染完成后派发 'pixivpagesections:rendered' —— 贡献方模块据此（重新）填充自己的容器。
    //  语言切换：同页切换由宿主在其 onChange 里调用 PixivPageSections.refresh()（重译标题、重发事件）。
    // ============================================================

    var ENDPOINT = '/api/page-sections';

    // 操作入口图标 token → 内联 SVG（stroke 等由 .section-add svg 等宿主 CSS 控制）。
    var ACTION_ICONS = {
        'plus': '<line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/>'
    };

    // 各部件 class 的默认值（标准侧栏）；宿主可经 data-section-*-class 覆盖。
    var CLASS_DEFAULTS = {
        header: 'section-header', title: 'section-title', action: 'section-add',
        nav: 'sidebar-nav', body: 'sidebar-nav', divider: 'sidebar-divider',
        navLink: 'nav-item', navActive: 'current', navIconWrap: 'nav-icon', navLabel: 'nav-label'
    };

    var slotsState = [];           // [{ el, placement, sections }]
    var loadedModules = {};        // moduleUrl → true（去重加载）
    var loaded = false;
    var inFlight = false;
    var languageSubscribed = false;
    var currentI18n = null;        // 最近一次按当前语言构造的 i18n 解析器（命令式 + Vue 共用）

    // —— Vue 主渲染状态 ——
    var vueRuntime = null;         // window.Vue（ensure() 解析后缓存）
    var vueState = null;           // Vue.reactive({ i18n })：各 slot app 共享，置换 i18n 即触发标题重译
    var slotApps = [];             // [{ el, app }] 已挂载的 slot Vue app（幂等复用）
    var vueMode = false;           // 是否已进入 Vue 主渲染稳态

    var resolveReady;
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

    // 收集需预加载的 i18n namespace。titleNamespace 后端必填（注册期 fail-fast），actionTitleNamespace 随
    // actionTitleI18nKey 条件必填、否则为 null：统一按「trim 后非空才收集」规范化，避免把 null / 纯空白当 namespace
    // 去请求空白 bundle。标题 / 操作标题的实际解析仍由渲染期 resolveText→tns 统一处理（tns 对空白 namespace 回退）。
    function collectNamespaces(sectionsBySlot) {
        var seen = { 'common': true };
        sectionsBySlot.forEach(function (slot) {
            (slot.sections || []).forEach(function (s) {
                var titleNs = s.titleNamespace == null ? '' : String(s.titleNamespace).trim();
                if (titleNs) seen[titleNs] = true;
                var actionNs = s.actionTitleNamespace == null ? '' : String(s.actionTitleNamespace).trim();
                if (actionNs) seen[actionNs] = true;
            });
        });
        return Object.keys(seen);
    }

    function resolveText(i18n, namespace, key, fallback) {
        if (i18n && typeof i18n.tns === 'function') {
            return i18n.tns(namespace, key, fallback != null ? fallback : key);
        }
        return fallback != null ? fallback : key;
    }

    function classFor(slot, name) {
        var attr = 'data-section-' + name + '-class';
        var v = slot.el.getAttribute(attr);
        return v != null ? v : CLASS_DEFAULTS[name];
    }

    function actionIconSvg(icon) {
        var inner = ACTION_ICONS[icon] || ACTION_ICONS.plus;
        return '<svg viewBox="0 0 24 24" aria-hidden="true">' + inner + '</svg>';
    }

    function navSlotHtml(slot, section) {
        if (!section.navPlacement) return '';
        var attrs = ' data-nav-slot="' + escapeAttr(section.navPlacement) + '"'
            + ' data-nav-link-class="' + escapeAttr(classFor(slot, 'navLink')) + '"'
            + ' data-nav-active-class="' + escapeAttr(classFor(slot, 'navActive')) + '"'
            + ' data-nav-icon-wrap-class="' + escapeAttr(classFor(slot, 'navIconWrap')) + '"'
            + ' data-nav-label-class="' + escapeAttr(classFor(slot, 'navLabel')) + '"';
        return '<nav class="' + escapeAttr(classFor(slot, 'nav')) + '"' + attrs + '></nav>';
    }

    function sectionHtml(slot, section, i18n) {
        var title = escapeText(resolveText(i18n, section.titleNamespace, section.titleI18nKey, section.id));
        var header = '<div class="' + escapeAttr(classFor(slot, 'header')) + '">'
            + '<span class="' + escapeAttr(classFor(slot, 'title')) + '">' + title + '</span>';
        if (section.actionHref) {
            var actionTitle = section.actionTitleI18nKey
                ? escapeAttr(resolveText(i18n, section.actionTitleNamespace, section.actionTitleI18nKey, '')) : '';
            header += '<a class="' + escapeAttr(classFor(slot, 'action')) + '"'
                + ' href="' + escapeAttr(section.actionHref) + '"'
                + (actionTitle ? ' title="' + actionTitle + '" aria-label="' + actionTitle + '"' : '')
                + '>' + actionIconSvg(section.actionIcon) + '</a>';
        }
        header += '</div>';
        var body = section.moduleUrl
            ? '<div class="' + escapeAttr(classFor(slot, 'body')) + ' page-section-body"'
                + ' data-section-id="' + escapeAttr(section.id) + '"></div>'
            : '';
        var dividerClass = classFor(slot, 'divider');
        var divider = dividerClass ? '<div class="' + escapeAttr(dividerClass) + '"></div>' : '';
        return '<div class="page-section" data-section-id="' + escapeAttr(section.id) + '">'
            + header + navSlotHtml(slot, section) + body + '</div>' + divider;
    }

    // —— Vue 主渲染 ——

    // 区块骨架模板：page-section / header / 标题 / 操作入口 / 空内嵌导航 slot / 空 body 容器 / 分隔线均为真实
    // Vue 元素。**内嵌 <nav data-nav-slot> 与 .page-section-body 刻意无 Vue 子节点**——Vue 重渲染对「无子节点」
    // 元素不动其实际子节点，故 PixivNav 填的链接 / 贡献方模块填的列表不被覆盖（职责分离）。属性为 null 时 Vue 省略。
    var SECTION_TEMPLATE =
        '<template v-for="s in sections" :key="s.id">'
        + '<div class="page-section" :data-section-id="s.id">'
        + '<div :class="cls.header">'
        + '<span :class="cls.title">{{ titleOf(s) }}</span>'
        + '<a v-if="s.actionHref" :class="cls.action" :href="s.actionHref" :title="actionTitleOf(s)"'
        + ' :aria-label="actionTitleOf(s)" v-html="actionIconOf(s)"></a>'
        + '</div>'
        + '<nav v-if="s.navPlacement" :class="cls.nav" :data-nav-slot="s.navPlacement"'
        + ' :data-nav-link-class="cls.navLink" :data-nav-active-class="cls.navActive"'
        + ' :data-nav-icon-wrap-class="cls.navIconWrap" :data-nav-label-class="cls.navLabel"></nav>'
        + '<div v-if="s.moduleUrl" :class="cls.body" class="page-section-body" :data-section-id="s.id"></div>'
        + '</div>'
        + '<div v-if="cls.divider" :class="cls.divider"></div>'
        + '</template>';

    // 据 slotState 构造其 Vue 组件：读取一次该 slot 的部件 class 与（fetch 一次得到的、静态的）sections，
    // 标题 / 操作标题渲染读共享 reactive 状态（vueState.i18n）——语言变化即自动重译。
    function buildSectionComponent(slotState) {
        var sections = slotState.sections || [];
        var cls = {
            header: classFor(slotState, 'header'), title: classFor(slotState, 'title'),
            action: classFor(slotState, 'action'), nav: classFor(slotState, 'nav'),
            body: classFor(slotState, 'body'), divider: classFor(slotState, 'divider'),
            navLink: classFor(slotState, 'navLink'), navActive: classFor(slotState, 'navActive'),
            navIconWrap: classFor(slotState, 'navIconWrap'), navLabel: classFor(slotState, 'navLabel')
        };
        return {
            setup: function () {
                return {
                    sections: sections,
                    cls: cls,
                    titleOf: function (s) { return resolveText(vueState.i18n, s.titleNamespace, s.titleI18nKey, s.id); },
                    actionTitleOf: function (s) {
                        if (!s.actionTitleI18nKey) return null;
                        return resolveText(vueState.i18n, s.actionTitleNamespace, s.actionTitleI18nKey, '') || null;
                    },
                    actionIconOf: function (s) { return actionIconSvg(s.actionIcon); }
                };
            },
            template: SECTION_TEMPLATE
        };
    }

    function hasSlotApp(el) {
        for (var i = 0; i < slotApps.length; i++) {
            if (slotApps[i].el === el) return true;
        }
        return false;
    }

    // 升级 / 维持 Vue 主渲染：懒加载运行时 → 建 / 更新共享 reactive i18n → 为「有 sections 且尚未挂载」的 slot
    // 经统一 helper mountOn 挂 Vue app。PixivVue 缺失 / 加载失败一律收敛、不抛（调用方对未接管的 slot 命令式兜底）。
    function renderSectionsVue() {
        if (!global.PixivVue || typeof global.PixivVue.ensure !== 'function') {
            return global.Promise.resolve(false);
        }
        return global.PixivVue.ensure().then(function (Vue) {
            if (!Vue) return false;
            vueRuntime = Vue;
            if (!vueState) vueState = Vue.reactive({ i18n: null });
            vueState.i18n = currentI18n;
            var pending = [];
            slotsState.forEach(function (ss) {
                if (!ss.placement || ss.sections == null || hasSlotApp(ss.el)) return;
                pending.push(global.PixivVue.mountOn(ss.el, buildSectionComponent(ss)).then(function (handle) {
                    if (handle && handle.app) slotApps.push({ el: ss.el, app: handle.app });
                }));
            });
            return global.Promise.all(pending).then(function () {
                vueMode = slotApps.length > 0;
                return vueMode;
            });
        }).catch(function (e) {
            console.warn('[PixivPageSections] Vue 运行时不可用，沿用命令式渲染：', e);
            return false;
        });
    }

    function fetchSections(placement) {
        var url = ENDPOINT + '?placement=' + encodeURIComponent(placement);
        return global.fetch(url, { credentials: 'same-origin' }).then(function (res) {
            if (!res.ok) {
                throw new Error('page-sections http ' + res.status);
            }
            return res.json();
        }).then(function (data) {
            return Array.isArray(data) ? data : [];
        });
    }

    async function buildI18n() {
        if (!global.PixivI18n || typeof global.PixivI18n.create !== 'function') {
            return null;
        }
        try {
            return await global.PixivI18n.create({ namespaces: collectNamespaces(slotsState) });
        } catch (e) {
            return null;
        }
    }

    function loadPendingModules() {
        slotsState.forEach(function (slot) {
            (slot.sections || []).forEach(function (s) {
                if (!s.moduleUrl || loadedModules[s.moduleUrl]) return;
                loadedModules[s.moduleUrl] = true;
                var script = global.document.createElement('script');
                script.src = s.moduleUrl;
                script.async = true;
                global.document.head.appendChild(script);
            });
        });
    }

    function dispatchRendered() {
        try {
            global.dispatchEvent(new global.CustomEvent('pixivpagesections:rendered'));
        } catch (e) { /* 古老环境不支持 CustomEvent 构造器：忽略 */ }
    }

    async function renderAll() {
        currentI18n = await buildI18n();
        // Vue 主渲染：把有 sections 的 slot 升级为 Vue（骨架）。失败 / 无 Vue / sections 为 null 的 slot 走命令式。
        await renderSectionsVue();
        slotsState.forEach(function (slot) {
            if (hasSlotApp(slot.el)) return;   // 已被 Vue 接管（骨架由 Vue 渲染）
            if (!slot.sections) {              // 失败降级：清空（不残留按 id 显隐的旧业务块）
                slot.el.innerHTML = '';
                return;
            }
            slot.el.innerHTML = slot.sections.map(function (s) {
                return sectionHtml(slot, s, currentI18n);
            }).join('');
        });
        // 内嵌导航 slot 此刻已注入（Vue 或命令式）：让 PixivNav 重渲染全部 slot（含新注入者）。
        if (global.PixivNav && typeof global.PixivNav.refresh === 'function') {
            global.PixivNav.refresh();
        }
        // 加载贡献方前端模块（去重），并通知已加载模块（重新）填充各自容器。
        loadPendingModules();
        dispatchRendered();
    }

    function markReady() {
        if (readyResolved) return;
        readyResolved = true;
        resolveReady();
    }

    function subscribeLanguageOnce() {
        if (languageSubscribed) return;
        if (global.PixivI18n && typeof global.PixivI18n.onLanguageChange === 'function') {
            global.PixivI18n.onLanguageChange(function () { refresh(); });
            languageSubscribed = true;
        }
    }

    async function mount() {
        if (inFlight) return;
        inFlight = true;
        slotsState = qsa('[data-section-slot]').map(function (el) {
            return { el: el, placement: el.getAttribute('data-section-slot'), sections: null };
        });
        try {
            for (var i = 0; i < slotsState.length; i++) {
                var slot = slotsState[i];
                slot.sections = slot.placement ? await fetchSections(slot.placement) : [];
            }
            loaded = true;
        } catch (e) {
            // 失败降级：清空全部 section slot（不残留按 id 显隐的旧业务块）。
            slotsState.forEach(function (slot) { slot.sections = null; });
            console.warn('[PixivPageSections] 拉取页面区块失败，隐藏借用区块：', e);
        } finally {
            inFlight = false;
        }
        await renderAll();
        subscribeLanguageOnce();
        markReady();
    }

    async function refresh() {
        if (inFlight) return;
        if (!loaded) return mount();
        await renderAll();
    }

    function autoMount() {
        if (global.document.readyState === 'loading') {
            global.document.addEventListener('DOMContentLoaded', function () { mount(); });
        } else {
            mount();
        }
    }

    global.PixivPageSections = {
        mount: mount,
        refresh: refresh,
        ready: function () { return readyPromise; }
    };

    autoMount();
})(window);
