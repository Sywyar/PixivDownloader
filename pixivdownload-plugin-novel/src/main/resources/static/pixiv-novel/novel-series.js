'use strict';
async function loadSeriesNav() {
    try {
        // 导航沿用当前译文的语言与引擎，缺失的标题回退原文。
        const url = `/api/gallery/novel/${encodeURIComponent(novelId)}/series`
            + (activeContentLang ? `?variant=${encodeURIComponent(activeContentLang)}` : '');
        const r = await fetch(url);
        if (!r.ok) return;
        const nav = await r.json();
        if (!nav.seriesId) return;
        cachedSeriesNav = nav;
        renderSeriesNav(nav);
    } catch {}
}

function renderSeriesNav(nav) {
    for (const suffix of ['', '-bottom']) {
        renderSeriesNavSet(nav, {
            wrap: 'series-nav' + suffix,
            prev: 'series-prev' + suffix,
            index: 'series-index' + suffix,
            next: 'series-next' + suffix
        });
    }
}

function renderSeriesNavSet(nav, ids) {
    const wrap = document.getElementById(ids.wrap);
    const prev = document.getElementById(ids.prev);
    const next = document.getElementById(ids.next);
    const index = document.getElementById(ids.index);
    if (!wrap || !prev || !next || !index) return;

    wrap.style.display = 'grid';
    const variantQuery = activeContentLang ? `&variant=${encodeURIComponent(activeContentLang)}` : '';
    if (nav.prev) {
        prev.href = `/pixiv-novel.html?id=${nav.prev.novelId}${variantQuery}`;
        prev.classList.remove('disabled-look');
        prev.textContent = pageI18n.t('series.prev', null, { order: nav.prev.seriesOrder, title: nav.prev.title });
    } else {
        prev.classList.add('disabled-look');
        prev.textContent = pageI18n.t('series.prev', null, { order: '-', title: '' });
    }
    if (nav.next) {
        next.href = `/pixiv-novel.html?id=${nav.next.novelId}${variantQuery}`;
        next.classList.remove('disabled-look');
        next.textContent = pageI18n.t('series.next', null, { order: nav.next.seriesOrder, title: nav.next.title });
    } else {
        next.classList.add('disabled-look');
        next.textContent = pageI18n.t('series.next', null, { order: '-', title: '' });
    }
    const indexParams = new URLSearchParams({
        type: 'novel',
        seriesId: String(nav.seriesId),
        currentId: String(novelId)
    });
    if (nav.seriesTitle) indexParams.set('seriesTitle', nav.seriesTitle);
    if (nav.currentOrder != null) indexParams.set('order', String(nav.currentOrder));
    if (activeContentLang) indexParams.set('variant', activeContentLang);
    index.href = `/pixiv-series.html?${indexParams.toString()}`;
    index.removeAttribute('target');
    index.removeAttribute('rel');
    index.textContent = pageI18n.t('series.index') + (nav.seriesTitle ? ` · ${nav.seriesTitle}` : '');
}

function getCachedSeriesNav() {
    return cachedSeriesNav;
}


// ---- PixivNovel facade ----
window.PixivNovel.series = window.PixivNovel.series || {};
window.PixivNovel.series = Object.assign(window.PixivNovel.series, { loadSeriesNav, renderSeriesNavSet, getCachedSeriesNav });
