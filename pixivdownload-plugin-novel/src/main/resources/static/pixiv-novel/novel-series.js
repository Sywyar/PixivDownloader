'use strict';
async function loadSeriesNav() {
    try {
        // 透传当前内容语言：后端按 lang 替换上一章 / 下一章标题与系列名为译后版本，缺失时回退原文。
        const url = `/api/gallery/novel/${encodeURIComponent(novelId)}/series`
            + (activeContentLang ? `?lang=${encodeURIComponent(activeContentLang)}` : '');
        const r = await fetch(url);
        if (!r.ok) return;
        const nav = await r.json();
        if (!nav.seriesId) return;
        cachedSeriesNav = nav;
        renderSeriesNavSet(nav, {
            wrap: 'series-nav',
            prev: 'series-prev',
            index: 'series-index',
            next: 'series-next'
        });
        renderSeriesNavSet(nav, {
            wrap: 'series-nav-bottom',
            prev: 'series-prev-bottom',
            index: 'series-index-bottom',
            next: 'series-next-bottom'
        });
    } catch {}
}

function renderSeriesNavSet(nav, ids) {
    const wrap = document.getElementById(ids.wrap);
    const prev = document.getElementById(ids.prev);
    const next = document.getElementById(ids.next);
    const index = document.getElementById(ids.index);
    if (!wrap || !prev || !next || !index) return;

    wrap.style.display = 'grid';
    if (nav.prev) {
        prev.href = `/pixiv-novel.html?id=${nav.prev.novelId}`;
        prev.classList.remove('disabled-look');
        prev.textContent = pageI18n.t('series.prev', null, { order: nav.prev.seriesOrder, title: nav.prev.title });
    } else {
        prev.classList.add('disabled-look');
        prev.textContent = pageI18n.t('series.prev', null, { order: '-', title: '' });
    }
    if (nav.next) {
        next.href = `/pixiv-novel.html?id=${nav.next.novelId}`;
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
