(function (global) {
    'use strict';
    // ============================================================
    //  画廊插件在「统计页」的内嵌模块。
    //
    //  由画廊的「收藏夹」页面区块贡献（PageSectionContribution.moduleUrl 指向本文件）加载：渲染收藏夹列表进该区块
    //  容器。本脚本住画廊插件自有静态目录、仅当画廊插件启用时才被 serving / 贡献，故统计页本身完全不知道画廊——
    //  收藏夹 API（/api/collections）调用、画廊 href 构造都封装在本（画廊自有）模块内。禁用画廊 → 区块与本脚本
    //  一并消失。容器由通用渲染器 /js/pixiv-page-sections.js 渲染，本模块经稳定事件 'pixivpagesections:rendered'
    //  在每次（重）渲染后填充自己的容器（按 section id 定位，不依赖宿主页 DOM 结构）。
    // ============================================================

    var SECTION_ID = 'gallery-stats-collections';
    var HEART_SVG = '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 21l-1.45-1.32C5.4 14.99 2 11.9 2 8.05 2 5.4 4.06 3.3 6.7 3.3c1.5 0 2.94.7 3.88 1.81L12 6.5l1.42-1.39A5.2 5.2 0 0 1 17.3 3.3C19.94 3.3 22 5.4 22 8.05c0 3.85-3.4 6.94-8.55 11.63L12 21z"/></svg>';

    var collectionsCache = null;
    var i18nClient = null;

    function container() {
        return global.document.querySelector('.page-section-body[data-section-id="' + SECTION_ID + '"]');
    }

    function escapeHtml(value) {
        return String(value == null ? '' : value)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }

    function t(key, fallback) {
        return i18nClient && typeof i18nClient.t === 'function'
            ? i18nClient.t(key, fallback) : (fallback || key);
    }

    async function ensureI18n() {
        if (i18nClient) return i18nClient;
        if (global.PixivI18n && typeof global.PixivI18n.create === 'function') {
            try {
                i18nClient = await global.PixivI18n.create({ namespaces: ['gallery', 'common'] });
            } catch (_) { /* i18n 不可用：用兜底文案 */ }
        }
        return i18nClient;
    }

    async function ensureCollections() {
        if (collectionsCache) return collectionsCache;
        try {
            var res = await global.fetch('/api/collections', {
                credentials: 'same-origin', headers: { 'Accept': 'application/json' }
            });
            if (!res.ok) { collectionsCache = []; return collectionsCache; }
            var data = await res.json();
            collectionsCache = (data && data.collections) || [];
        } catch (_) {
            collectionsCache = [];
        }
        return collectionsCache;
    }

    function renderList(el, collections) {
        if (!collections.length) {
            el.innerHTML = '<div class="collection-empty">'
                + escapeHtml(t('gallery:status.no-collections', 'No collections')) + '</div>';
            return;
        }
        el.innerHTML = collections.map(function (c) {
            var icon = c.iconExt
                ? '<img src="/api/collections/' + c.id + '/icon?v=' + encodeURIComponent(c.createdTime || '') + '" alt="">'
                : HEART_SVG;
            var href = '/pixiv-gallery.html?view=all&collectionIds=' + encodeURIComponent(c.id);
            return '<a class="collection-item" href="' + escapeHtml(href) + '">'
                + '<div class="collection-icon">' + icon + '</div>'
                + '<span class="collection-label">' + escapeHtml(c.name) + '</span>'
                + '<span class="collection-count">' + escapeHtml(c.artworkCount != null ? c.artworkCount : 0) + '</span>'
                + '</a>';
        }).join('');
    }

    async function render() {
        if (!container()) return;            // 本区块未渲染（如画廊对当前身份不可见）：无操作
        await ensureI18n();
        var collections = await ensureCollections();
        var el = container();                // 重渲染后容器可能是新元素：重新取一次
        if (el) renderList(el, collections);
    }

    global.addEventListener('pixivpagesections:rendered', function () { render(); });
    if (global.PixivI18n && typeof global.PixivI18n.onLanguageChange === 'function') {
        global.PixivI18n.onLanguageChange(function () { i18nClient = null; render(); });
    }
    render();
})(window);
