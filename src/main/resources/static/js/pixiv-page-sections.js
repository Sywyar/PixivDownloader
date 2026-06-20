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

    function namespaceOf(key) {
        if (!key) return 'common';
        var i = key.indexOf(':');
        return i >= 0 ? key.slice(0, i) : 'common';
    }

    function collectNamespaces(sectionsBySlot) {
        var seen = { 'common': true };
        sectionsBySlot.forEach(function (slot) {
            (slot.sections || []).forEach(function (s) {
                seen[namespaceOf(s.titleI18nKey)] = true;
                if (s.actionTitleI18nKey) seen[namespaceOf(s.actionTitleI18nKey)] = true;
            });
        });
        return Object.keys(seen);
    }

    function resolveText(i18n, key, fallback) {
        if (i18n && typeof i18n.t === 'function') {
            return i18n.t(key, fallback != null ? fallback : key);
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
        var title = escapeText(resolveText(i18n, section.titleI18nKey, section.id));
        var header = '<div class="' + escapeAttr(classFor(slot, 'header')) + '">'
            + '<span class="' + escapeAttr(classFor(slot, 'title')) + '">' + title + '</span>';
        if (section.actionHref) {
            var actionTitle = section.actionTitleI18nKey
                ? escapeAttr(resolveText(i18n, section.actionTitleI18nKey, '')) : '';
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

    async function fetchSections(placement) {
        var url = ENDPOINT + '?placement=' + encodeURIComponent(placement);
        var res = await global.fetch(url, { credentials: 'same-origin' });
        if (!res.ok) {
            throw new Error('page-sections http ' + res.status);
        }
        var data = await res.json();
        return Array.isArray(data) ? data : [];
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
        var i18n = await buildI18n();
        slotsState.forEach(function (slot) {
            if (!slot.sections) {
                slot.el.innerHTML = '';
                return;
            }
            slot.el.innerHTML = slot.sections.map(function (s) {
                return sectionHtml(slot, s, i18n);
            }).join('');
        });
        // 内嵌导航 slot 此刻已注入：让 PixivNav 重渲染全部 slot（含新注入者）。
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
