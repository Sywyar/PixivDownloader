'use strict';

// ---------- View switching ----------
function switchView(v) {
    v = normalizeView(v);
    state.view = v;
    syncViewParamInUrl();
    setActiveViewNav();
    state.page = 0;
    state.authorsView.page = 0;
    state.seriesView.page = 0;
    reloadCurrentView();
}

function setActiveViewNav() {
    syncViewNavigationHrefs();
    document.querySelectorAll('.nav-item[data-view]').forEach(el => {
        el.classList.toggle('active', el.dataset.view === state.view);
    });
    const grid = document.getElementById('grid');
    const pag = document.getElementById('pagination');
    const av = document.getElementById('authorView');
    const sv = document.getElementById('seriesView');
    const isAuthors = state.view === 'authors';
    const isSeries = state.view === 'series';
    grid.style.display = (isAuthors || isSeries) ? 'none' : '';
    pag.style.display = (isAuthors || isSeries) ? 'none' : '';
    av.classList.toggle('active', isAuthors);
    sv.classList.toggle('active', isSeries);
    // 批量管理仅适用于「全部小说」主网格：切到作者/系列视图时隐藏入口并退出选择模式。
    if (state.view !== 'all' && batch.active) exitBatchMode();
    updateBatchButtonVisibility();
    updateSearchPlaceholder();
}

function reloadCurrentView() {
    setActiveViewNav();
    if (state.view === 'authors') reloadAuthorsView();
    else if (state.view === 'series') reloadSeriesView();
    else reloadNovels();
}

// ---------- Novels list ----------
async function reloadNovels() {
    persistNovelGalleryState();
    // IDs mode remains page-scoped; filter mode survives pagination while the filter snapshot stays unchanged.
    if (batch.active) {
        if (batch.mode === 'filter') {
            if (batch.filterKey && batch.filterKey !== currentBatchFilterKey()) {
                clearBatchSelection();
            }
        } else {
            clearBatchSelection();
        }
    }
    const params = new URLSearchParams({
        page: String(state.page), size: String(PAGE_SIZE),
        sort: state.sort, order: state.order, r18: state.r18, ai: state.ai
    });
    if (state.search) params.set('search', state.search);
    if (state.search && state.searchType && state.searchType !== 'all') params.set('searchType', state.searchType);
    if (state.selectedCollections.size) params.set('collectionIds', Array.from(state.selectedCollections).join(','));

    const must = [], not = [], or = [];
    state.selectedTags.forEach((mode, id) => (mode === 'must' ? must : mode === 'not' ? not : or).push(id));
    if (must.length) params.set('tagIds', must.join(','));
    if (not.length) params.set('notTagIds', not.join(','));
    if (or.length) params.set('orTagIds', or.join(','));

    const sMust = [], sNot = [];
    state.selectedSeries.forEach((mode, id) => (mode === 'must' ? sMust : sNot).push(id));
    if (sMust.length) params.set('seriesIds', sMust.join(','));
    if (sNot.length) params.set('notSeriesIds', sNot.join(','));

    const aMust = [], aNot = [], aOr = [];
    state.selectedAuthors.forEach((mode, id) => (mode === 'must' ? aMust : mode === 'not' ? aNot : aOr).push(id));
    if (state.exclusiveAuthorId != null) aMust.push(state.exclusiveAuthorId);
    if (aMust.length) params.set('authorIds', aMust.join(','));
    if (aNot.length) params.set('notAuthorIds', aNot.join(','));
    if (aOr.length) params.set('orAuthorIds', aOr.join(','));

    try {
        const r = await fetch(`/api/gallery/novels?${params.toString()}`);
        if (!r.ok) throw new Error('HTTP ' + r.status);
        const data = await r.json();
        state.totalElements = data.totalElements || 0;
        renderGrid(data.content || []);
        renderPagination('pagination', data.totalPages || 0, data.page || 0, p => { state.page = p; reloadNovels(); });
        // 仅在存在有效搜索/筛选条件时才把搜索框与筛选按钮标红
        setSearchEmptyState((data.totalElements || 0) === 0 && hasActiveNovelFilters());
    } catch (e) {
        state.totalElements = 0;
        document.getElementById('grid').innerHTML = `<div class="empty">${esc(pageI18n.t('novel-gallery:status.load-failed', '加载失败'))}</div>`;
        document.getElementById('pagination').innerHTML = '';
        setSearchEmptyState(false);
    }
}

function formatReadingDuration(seconds) {
    const total = Math.floor(Number(seconds || 0));
    if (!Number.isFinite(total) || total <= 0) return '';
    const hour = Math.floor(total / 3600);
    const minute = Math.floor((total % 3600) / 60);
    const second = total % 60;
    const parts = [];
    if (hour > 0) parts.push(pageI18n.t('novel-gallery:duration.hour', '{count} 小时', { count: hour }));
    if (minute > 0) parts.push(pageI18n.t('novel-gallery:duration.minute', '{count} 分钟', { count: minute }));
    if (second > 0 && hour === 0) parts.push(pageI18n.t('novel-gallery:duration.second', '{count} 秒', { count: second }));
    if (!parts.length) parts.push(pageI18n.t('novel-gallery:duration.second', '{count} 秒', { count: total }));
    return parts.join(' ');
}

function formatReadingTime(seconds) {
    const text = formatReadingDuration(seconds);
    return text ? pageI18n.t('novel-gallery:meta.reading-time', '预计阅读：{time}', { time: text }) : '';
}

function hasActiveNovelFilters() {
    return !!(state.search
        || state.r18 !== 'any'
        || state.ai !== 'any'
        || state.selectedCollections.size
        || state.selectedTags.size
        || state.selectedSeries.size
        || state.selectedAuthors.size
        || state.exclusiveAuthorId != null);
}

function renderGrid(items) {
    const grid = document.getElementById('grid');
    if (!items.length) {
        if (hasActiveNovelFilters()) {
            grid.innerHTML = `<div class="empty" style="grid-column:1/-1;">${esc(pageI18n.t('novel-gallery:status.empty', '暂无小说'))}</div>`;
        } else {
            grid.innerHTML = `<div class="empty empty-cta" style="grid-column:1/-1;">`
                + `<div>${esc(pageI18n.t('novel-gallery:status.no-downloads', '没有下载的作品，快去下载页下载吧！'))}</div>`
                + `<a class="btn btn-primary" href="/pixiv-batch.html" target="_blank" rel="noopener">${esc(pageI18n.t('novel-gallery:status.go-download', '前往下载页'))}</a>`
                + `</div>`;
        }
        return;
    }
    const heartTitle = esc(pageI18n.t('collection.add', '添加到收藏夹'));
    grid.innerHTML = items.map(item => {
        const badges = [];
        if (item.xRestrict === 1) badges.push('<span class="badge r18">R-18</span>');
        if (item.xRestrict === 2) badges.push('<span class="badge r18g">R-18G</span>');
        if (item.isAi) badges.push('<span class="badge ai">AI</span>');
        if (item.isOriginal) badges.push(`<span class="badge original">${esc(pageI18n.t('novel-gallery:meta.original', '原创'))}</span>`);
        const meta = [];
        if (item.wordCount && item.wordCount > 0) meta.push(esc(pageI18n.t('novel-gallery:meta.word-count', '{count} 字', { count: item.wordCount })));
        else if (item.textLength && item.textLength > 0) meta.push(esc(pageI18n.t('novel-gallery:meta.text-length', '{count} 字符', { count: item.textLength })));
        const readingText = formatReadingTime(item.readingTimeSeconds);
        if (readingText) meta.push(esc(readingText));
        if (item.seriesId && item.seriesId > 0 && item.seriesOrder != null) meta.push('#' + item.seriesOrder);
        const cover = item.coverExt
            ? `<div class="card-cover"><img loading="lazy" src="/api/gallery/novel/${item.novelId}/cover" alt="" onerror="this.parentElement.style.display='none'"></div>`
            : '';
        return `<div class="card" data-novel-id="${item.novelId}">
            <span class="card-select" aria-hidden="true">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>
            </span>
            ${cover}
            <div class="card-title">${esc(item.title || '')}</div>
            <div class="card-author">${esc(item.authorName || pageI18n.t('novel-gallery:status.unknown-author', '未知作者'))}</div>
            <div class="badges">${badges.join('')}</div>
            <div class="card-footer">
                <div class="card-footer-info">
                    <div class="card-meta">${meta.map(m => `<span>${m}</span>`).join('')}</div>
                    <div class="work-collections" data-collections-for="${item.novelId}"></div>
                </div>
                <button class="thumb-heart" data-novel-id="${item.novelId}" title="${heartTitle}" type="button">${HEART_SVG}</button>
            </div>
        </div>`;
    }).join('');

    grid.querySelectorAll('.card').forEach(card => {
        const id = card.dataset.novelId;
        if (batch.active && isBatchCardSelected(Number(id))) {
            card.classList.add('selected');
        }
        card.addEventListener('click', e => {
            if (batch.active) {
                e.preventDefault();
                toggleCardSelection(Number(id), card);
                return;
            }
            if (e.target.closest('.thumb-heart')) return;
            window.location.href = `/pixiv-novel.html?id=${id}`;
        });
        const heart = card.querySelector('.thumb-heart');
        if (heart) {
            heart.addEventListener('click', e => {
                e.stopPropagation();
                e.preventDefault();
                openAddToCollectionModal(Number(id));
            });
        }
    });

    loadMembershipsForNovels(items.map(i => Number(i.novelId)));
    if (batch.active) updateBatchBar();
}

async function loadMembershipsForNovels(novelIds) {
    if (!novelIds.length) return;
    let memberships = {};
    try {
        const r = await fetch('/api/collections/novels/memberships', {
            method: 'POST',
            credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ novelIds })
        });
        if (!r.ok) return;
        const data = await r.json();
        memberships = data.memberships || {};
    } catch (e) { return; }
    const byId = new Map((state.collections || []).map(c => [c.id, c]));
    novelIds.forEach(id => {
        const collectionIds = memberships[id] || [];
        const card = document.querySelector(`.card[data-novel-id="${id}"]`);
        if (!card) return;
        const heart = card.querySelector('.thumb-heart');
        if (heart) heart.classList.toggle('liked', collectionIds.length > 0);
        const label = card.querySelector(`[data-collections-for="${id}"]`);
        if (!label) return;
        if (collectionIds.length === 0) { label.textContent = ''; return; }
        const names = collectionIds.map(cid => byId.get(cid)?.name).filter(Boolean);
        label.textContent = names.length ? '♥ ' + names.join(' · ') : '';
    });
}

// ---------- Authors view ----------
const AUTHOR_WORKS_PER_ROW = 30;

async function reloadAuthorsView() {
    persistNovelGalleryState();
    setSearchEmptyState(false);
    const grid = document.getElementById('authorGrid');
    grid.innerHTML = `<div class="author-works-loading">${esc(pageI18n.t('novel-gallery:status.loading', '加载中...'))}</div>`;
    try {
        const params = new URLSearchParams({
            page: String(state.authorsView.page),
            size: String(PAGE_SIZE),
            sort: 'novels'
        });
        if (state.search) params.set('search', state.search);
        const r = await fetch(`/api/gallery/novels/authors?${params.toString()}`);
        if (!r.ok) throw new Error('HTTP ' + r.status);
        const data = await r.json();
        state.authorsView.content = data.content || [];
        state.authorsView.totalPages = data.totalPages || 0;
        renderAuthorsView(state.authorsView.content);
        renderPagination('authorPagination', state.authorsView.totalPages, state.authorsView.page,
            p => { state.authorsView.page = p; reloadAuthorsView(); });
    } catch (e) {
        grid.innerHTML = `<div class="empty">${esc(pageI18n.t('novel-gallery:status.load-failed', '加载失败'))}</div>`;
        document.getElementById('authorPagination').innerHTML = '';
    }
}

function renderAuthorsView(authors) {
    const grid = document.getElementById('authorGrid');
    if (!authors.length) {
        grid.innerHTML = `<div class="empty">${esc(pageI18n.t('status.no-authors', '暂无作者'))}</div>`;
        return;
    }
    const viewAllLabel = esc(pageI18n.t('novel-gallery:series.view-all', '查看全部'));
    const loadingLabel = esc(pageI18n.t('novel-gallery:status.loading', '加载中...'));
    const prevLabel = esc(pageI18n.t('novel-gallery:author-view.prev-group', '上一组'));
    const nextLabel = esc(pageI18n.t('novel-gallery:author-view.next-group', '下一组'));
    grid.innerHTML = authors.map(a => {
        const name = a.name || ('#' + a.authorId);
        const countText = esc(pageI18n.t('novel-gallery:author-view.count', '{count} 部 · #{authorId}', { count: a.novelCount, authorId: a.authorId }));
        return `
        <div class="author-row" data-author-id="${a.authorId}" data-author-name="${esc(a.name || '')}">
            <div class="author-row-info">
                <div class="author-row-name" data-filter-author="${a.authorId}" title="${esc(name)}">${esc(name)}</div>
                <div class="author-row-count">${countText}</div>
                <div class="author-row-actions">
                    <button class="author-row-btn" data-filter-author="${a.authorId}" type="button">${viewAllLabel}</button>
                </div>
            </div>
            <div class="author-row-works">
                <button class="author-works-arrow left" data-arrow="left" type="button" title="${prevLabel}">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 18 9 12 15 6"/></svg>
                </button>
                <div class="author-works-strip" data-strip-for="${a.authorId}">
                    <div class="author-works-loading">${loadingLabel}</div>
                </div>
                <button class="author-works-arrow right" data-arrow="right" type="button" title="${nextLabel}">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 18 15 12 9 6"/></svg>
                </button>
            </div>
        </div>`;
    }).join('');

    grid.querySelectorAll('[data-filter-author]').forEach(el => {
        el.addEventListener('click', e => {
            e.stopPropagation();
            const id = Number(el.dataset.filterAuthor);
            const row = el.closest('.author-row');
            const name = row ? row.dataset.authorName : '';
            setAuthorFilterExclusive(id, name);
        });
    });

    grid.querySelectorAll('.author-row').forEach(row => {
        const authorId = Number(row.dataset.authorId);
        const strip = row.querySelector('.author-works-strip');
        const leftBtn = row.querySelector('[data-arrow="left"]');
        const rightBtn = row.querySelector('[data-arrow="right"]');

        loadAuthorWorks(authorId, strip).then(() => updateArrowState(strip, leftBtn, rightBtn));

        leftBtn.addEventListener('click', () => {
            strip.scrollBy({ left: -strip.clientWidth * 0.8, behavior: 'smooth' });
        });
        rightBtn.addEventListener('click', () => {
            strip.scrollBy({ left: strip.clientWidth * 0.8, behavior: 'smooth' });
        });
        strip.addEventListener('scroll', () => updateArrowState(strip, leftBtn, rightBtn));
    });
}

function updateArrowState(strip, leftBtn, rightBtn) {
    const atStart = strip.scrollLeft <= 1;
    const atEnd = strip.scrollLeft + strip.clientWidth >= strip.scrollWidth - 1;
    leftBtn.disabled = atStart;
    rightBtn.disabled = atEnd || strip.scrollWidth <= strip.clientWidth;
}

// ---------- Series view ----------
async function reloadSeriesView() {
    persistNovelGalleryState();
    setSearchEmptyState(false);
    const grid = document.getElementById('seriesGrid');
    grid.innerHTML = `<div class="empty" style="grid-column:1/-1;">${esc(pageI18n.t('novel-gallery:status.loading', '加载中...'))}</div>`;
    try {
        const params = new URLSearchParams({
            page: String(state.seriesView.page),
            size: String(PAGE_SIZE),
            sort: 'novels'
        });
        if (state.search) params.set('search', state.search);
        const r = await fetch(`/api/gallery/novels/series?${params.toString()}`);
        if (!r.ok) throw new Error('HTTP ' + r.status);
        const data = await r.json();
        state.seriesView.content = data.content || [];
        state.seriesView.totalPages = data.totalPages || 0;
        renderSeriesView(state.seriesView.content);
        renderPagination('seriesPagination', state.seriesView.totalPages, state.seriesView.page,
            p => { state.seriesView.page = p; reloadSeriesView(); });
    } catch (e) {
        grid.innerHTML = `<div class="empty" style="grid-column:1/-1;">${esc(pageI18n.t('novel-gallery:status.load-failed', '加载失败'))}</div>`;
        document.getElementById('seriesPagination').innerHTML = '';
    }
}

function renderSeriesView(seriesList) {
    const grid = document.getElementById('seriesGrid');
    if (!seriesList.length) {
        grid.innerHTML = `<div class="empty">${esc(pageI18n.t('novel-gallery:batch.gallery.series-empty', '暂无系列'))}</div>`;
        return;
    }
    const unknownAuthor = pageI18n.t('novel-gallery:status.unknown-author', '未知');
    const fallbackSeriesTitle = pageI18n.t('novel-gallery:series.unknown-title', '未命名系列');
    const viewAllLabel = esc(pageI18n.t('novel-gallery:series.view-all', '查看全部'));
    const loadingLabel = esc(pageI18n.t('novel-gallery:status.loading', '加载中...'));
    const prevLabel = esc(pageI18n.t('novel-gallery:author-view.prev-group', '上一组'));
    const nextLabel = esc(pageI18n.t('novel-gallery:author-view.next-group', '下一组'));
    const maxTags = 6;
    grid.innerHTML = seriesList.map(s => {
        const title = s.title || fallbackSeriesTitle;
        const author = s.authorName || (s.authorId != null ? '#' + s.authorId : unknownAuthor);
        const countText = esc(pageI18n.t('novel-gallery:series.count', '{count} 部 · #{seriesId}', { count: s.novelCount, seriesId: s.seriesId }));
        const authorLine = author
            ? `<div class="author-row-count">${esc(pageI18n.t('novel-gallery:series.author-prefix', '作者：{name}', { name: author }))}</div>`
            : '';
        const cover = s.coverExt
            ? `<div class="series-row-cover"><img src="/api/gallery/novel/series/${s.seriesId}/cover" alt="${esc(title)}" loading="lazy" onerror="this.parentElement.replaceWith(Object.assign(document.createElement('div'),{className:'series-row-cover',textContent:this.alt}))"></div>`
            : '';
        const tags = Array.isArray(s.tags) ? s.tags : [];
        const tagsHtml = tags.length
            ? `<div class="series-row-tags">${tags.slice(0, maxTags).map(tg => {
                  const label = tg.translatedName ? `${tg.name} · ${tg.translatedName}` : tg.name;
                  const tagId = Number(tg.tagId);
                  const name = tg.name || (Number.isFinite(tagId) && tagId > 0 ? `#${tagId}` : '');
                  const tagLabel = tg.translatedName ? `${name} / ${tg.translatedName}` : name;
                  if (Number.isFinite(tagId) && tagId > 0) {
                      return `<button class="series-row-tag" type="button" data-filter-series-tag-id="${tagId}" data-tag-name="${esc(name)}" data-tag-translated-name="${esc(tg.translatedName || '')}" title="${esc(tagLabel || label || name)}">${esc(name)}</button>`;
                  }
                  return `<span class="series-row-tag" title="${esc(tagLabel || label || name)}">${esc(name)}</span>`;
              }).join('')}${tags.length > maxTags ? `<span class="series-row-tag-more">${esc(pageI18n.t('novel-gallery:series.tag-more', '+{count}', { count: tags.length - maxTags }))}</span>` : ''}</div>`
            : '';
        return `
        <div class="author-row series-row" data-series-id="${s.seriesId}" data-series-title="${esc(s.title || '')}">
            <div class="author-row-info">
                ${cover}
                <div class="author-row-name" data-open-series-directory="${s.seriesId}" title="${esc(title)}">${esc(title)}</div>
                <div class="author-row-count">${countText}</div>
                ${authorLine}
                ${tagsHtml}
                <div class="author-row-actions">
                    <button class="author-row-btn" data-open-series-directory="${s.seriesId}" type="button">${viewAllLabel}</button>
                </div>
            </div>
            <div class="author-row-works">
                <button class="author-works-arrow left" data-arrow="left" type="button" title="${prevLabel}">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 18 9 12 15 6"/></svg>
                </button>
                <div class="author-works-strip" data-strip-for="${s.seriesId}">
                    <div class="author-works-loading">${loadingLabel}</div>
                </div>
                <button class="author-works-arrow right" data-arrow="right" type="button" title="${nextLabel}">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 18 15 12 9 6"/></svg>
                </button>
            </div>
        </div>`;
    }).join('');

    grid.querySelectorAll('[data-open-series-directory]').forEach(el => {
        el.addEventListener('click', e => {
            e.stopPropagation();
            const sid = Number(el.dataset.openSeriesDirectory);
            const row = el.closest('.series-row');
            const stitle = row ? row.dataset.seriesTitle : '';
            window.location.href = buildSeriesDirectoryHref(sid, stitle);
        });
    });

    grid.querySelectorAll('[data-filter-series-tag-id]').forEach(el => {
        el.addEventListener('click', e => {
            e.preventDefault();
            e.stopPropagation();
            setTagFilterExclusive(
                el.dataset.filterSeriesTagId,
                el.dataset.tagName || '',
                el.dataset.tagTranslatedName || ''
            );
        });
    });

    grid.querySelectorAll('.series-row').forEach(row => {
        const seriesId = Number(row.dataset.seriesId);
        const strip = row.querySelector('.author-works-strip');
        const leftBtn = row.querySelector('[data-arrow="left"]');
        const rightBtn = row.querySelector('[data-arrow="right"]');

        // 初始两个箭头都禁用，等 loadSeriesNovels 渲染完成后由 renderSeriesPagedStrip 接管。
        leftBtn.disabled = true;
        rightBtn.disabled = true;
        loadSeriesNovels(seriesId, strip, leftBtn, rightBtn);
    });
}

async function loadSeriesNovels(seriesId, strip, leftBtn, rightBtn) {
    try {
        const params = new URLSearchParams({
            page: '0',
            size: String(AUTHOR_WORKS_PER_ROW),
            sort: 'series',
            order: 'asc',
            seriesIds: String(seriesId)
        });
        const r = await fetch(`/api/gallery/novels?${params.toString()}`);
        if (!r.ok) throw new Error('HTTP ' + r.status);
        const data = await r.json();
        const items = data.content || [];
        if (!items.length) {
            strip.innerHTML = `<div class="author-works-empty">${esc(pageI18n.t('novel-gallery:status.empty', '暂无小说'))}</div>`;
            if (leftBtn) leftBtn.disabled = true;
            if (rightBtn) rightBtn.disabled = true;
            return;
        }
        renderSeriesPagedStrip(strip, items, leftBtn, rightBtn);
    } catch (e) {
        strip.innerHTML = `<div class="author-works-empty">${esc(pageI18n.t('novel-gallery:status.load-failed', '加载失败'))}</div>`;
        if (leftBtn) leftBtn.disabled = true;
        if (rightBtn) rightBtn.disabled = true;
    }
}

/** 系列横排尺寸常量，需与 .series-row .series-works-pager / .author-work-card 默认尺寸保持一致。 */
const SERIES_CARD_WIDTH_PX = 140;
const SERIES_COLUMN_GAP_PX = 10;
const SERIES_ROW_GAP_PX = 12;
/** 卡高 = 140 缩略图 + ~36 标题（两行）+ 上下间距，给一个保守的 180px。 */
const SERIES_CARD_HEIGHT_PX = 180;
/** 左右各 40px 留给 .author-works-arrow 浮层。 */
const SERIES_STRIP_PADDING_X_PX = 80;

function renderSeriesItemCard(item) {
    const title = item.title || pageI18n.t('novel-gallery:status.unknown-novel', '小说 {id}', { id: item.novelId });
    const order = item.seriesOrder != null
        ? `<div class="author-work-pages">#${esc(item.seriesOrder)}</div>`
        : '';
    const cover = item.coverExt
        ? `<img src="/api/gallery/novel/${item.novelId}/cover" alt="${esc(title)}" loading="lazy" onerror="this.replaceWith(Object.assign(document.createElement('div'),{className:'author-work-thumb-placeholder',textContent:this.alt}))">`
        : `<div class="author-work-thumb-placeholder">${esc(title)}</div>`;
    return `
        <div class="author-work-card" data-id="${item.novelId}">
            <div class="author-work-thumb">
                ${cover}
                ${order}
            </div>
            <div class="author-work-title">${esc(title)}</div>
        </div>`;
}

/**
 * 系列章节"分页式"横排：卡片沿用 .author-work-card 默认样式（140 缩略图 + 标题）。
 *
 * 排数 / 列数按 strip 当前尺寸算：
 *   rowsPerPage = strip.clientHeight ≥ 270 时 = 2，否则 = 1
 *   colsPerPage = floor((可用宽 + 最小列间距) / (卡宽 + 最小列间距))   ≥ 1
 *
 * 余量塞进 column-gap：colsPerPage × cardW + (colsPerPage-1) × gap 正好等于 innerW，
 * 翻页步幅 = innerW + 一段 gap（页与页之间留同样的视觉间距）。
 *
 * 填充顺序：**行优先 (grid-auto-flow: row)**，从上往下、从左往右：
 *   rowsPerPage = 2 时 row-1 = #1 #2 #3 … #cols；row-2 = #cols+1 …… #2cols。
 *   rowsPerPage = 1 时 row-1 = #1 #2 #3 ……
 * 末页不满时，留空自然出现在右下角 —— 第二行最后一格最先空。
 *
 * 模式切换：
 *   - rowsPerPage = 1：strip 加 .is-scrollable，开 overflow-x: auto，鼠标/触屏可横滑；箭头 scrollBy。
 *   - rowsPerPage = 2：strip overflow: hidden，translateX 整页平移，鼠标/触屏不响应。
 */
function renderSeriesPagedStrip(strip, items, leftBtn, rightBtn) {
    let pageIndex = 0;
    let lastLayoutKey = '';
    let scrollListenerCleanup = null;

    const layout = () => {
        const stripH = strip.clientHeight;
        const stripW = strip.clientWidth;
        if (stripH <= 0 || stripW <= 0) return;

        const rowsPerPage = stripH >= (SERIES_CARD_HEIGHT_PX + 90) ? 2 : 1;
        const innerW = Math.max(SERIES_CARD_WIDTH_PX, stripW - SERIES_STRIP_PADDING_X_PX);
        const minCardStride = SERIES_CARD_WIDTH_PX + SERIES_COLUMN_GAP_PX;
        const colsPerPage = Math.max(
            1,
            Math.floor((innerW + SERIES_COLUMN_GAP_PX) / minCardStride)
        );
        const actualGap = colsPerPage > 1
            ? (innerW - colsPerPage * SERIES_CARD_WIDTH_PX) / (colsPerPage - 1)
            : SERIES_COLUMN_GAP_PX;

        const itemsPerPage = rowsPerPage * colsPerPage;
        const totalPages = Math.max(1, Math.ceil(items.length / itemsPerPage));
        // 一页步幅 = 这一页的整宽 + 页间留一段 gap，保持节奏一致。
        const pageContentWidthPx = colsPerPage * SERIES_CARD_WIDTH_PX + (colsPerPage - 1) * actualGap;
        const pageStridePx = pageContentWidthPx + actualGap;

        const key = `${rowsPerPage}|${colsPerPage}|${items.length}|${stripW}`;
        if (key === lastLayoutKey) return;
        lastLayoutKey = key;
        if (pageIndex >= totalPages) pageIndex = totalPages - 1;

        if (scrollListenerCleanup) {
            scrollListenerCleanup();
            scrollListenerCleanup = null;
        }

        const colsTemplate = `repeat(${colsPerPage}, ${SERIES_CARD_WIDTH_PX}px)`;

        // 切片成"每页 itemsPerPage 张"，每页按"实际需要几排"决定 grid-template-rows：
        //   effectiveRows = ceil(本页卡数 / colsPerPage)，上限 = rowsPerPage（高度允许的最大值）。
        //   行优先填 (grid-auto-flow: row 默认)：r1 填满之后才会动 r2，
        //   所以 ≤ colsPerPage 张的页（包括末页和"少作品系列"的唯一一页）就只渲染一排，r2 不存在。
        //   末页留空依然出现在右下角，与本规则自洽。
        const pagesHtml = [];
        for (let p = 0; p < totalPages; p++) {
            const slice = items.slice(p * itemsPerPage, (p + 1) * itemsPerPage);
            const effectiveRows = Math.min(
                rowsPerPage,
                Math.max(1, Math.ceil(slice.length / colsPerPage))
            );
            const rowsTemplate = Array(effectiveRows)
                .fill(`${SERIES_CARD_HEIGHT_PX}px`)
                .join(' ');
            const cards = slice.map(renderSeriesItemCard).join('');
            pagesHtml.push(`<div class="series-works-page"
                style="grid-template-rows:${rowsTemplate};grid-template-columns:${colsTemplate};column-gap:${actualGap}px;">
                ${cards}</div>`);
        }
        strip.innerHTML = `<div class="series-works-pager" style="column-gap:${actualGap}px;">${pagesHtml.join('')}</div>`;
        const pager = strip.querySelector('.series-works-pager');

        const isScrollable = rowsPerPage === 1;
        strip.classList.toggle('is-scrollable', isScrollable);

        if (isScrollable) {
            // 1 排：原生横滑 + 箭头 scrollBy 整页。
            pager.style.transform = 'none';
            strip.scrollLeft = 0;

            const apply = () => {
                const atStart = strip.scrollLeft <= 0;
                const atEnd = strip.scrollLeft + strip.clientWidth >= strip.scrollWidth - 1;
                if (leftBtn) leftBtn.disabled = atStart;
                if (rightBtn) rightBtn.disabled = atEnd;
            };
            if (leftBtn) leftBtn.onclick = () => {
                strip.scrollBy({ left: -pageStridePx, behavior: 'smooth' });
            };
            if (rightBtn) rightBtn.onclick = () => {
                strip.scrollBy({ left: pageStridePx, behavior: 'smooth' });
            };
            const onScroll = () => apply();
            strip.addEventListener('scroll', onScroll, { passive: true });
            scrollListenerCleanup = () => strip.removeEventListener('scroll', onScroll);
            apply();
        } else {
            pageIndex = Math.min(pageIndex, totalPages - 1);
            const apply = () => {
                pager.style.transform = `translateX(-${pageIndex * pageStridePx}px)`;
                if (leftBtn) leftBtn.disabled = pageIndex === 0;
                if (rightBtn) rightBtn.disabled = pageIndex >= totalPages - 1;
            };
            if (leftBtn) leftBtn.onclick = () => {
                if (pageIndex > 0) { pageIndex--; apply(); }
            };
            if (rightBtn) rightBtn.onclick = () => {
                if (pageIndex < totalPages - 1) { pageIndex++; apply(); }
            };
            apply();
        }

        pager.querySelectorAll('.author-work-card').forEach(card => {
            card.addEventListener('click', () => {
                window.location.href = `/pixiv-novel.html?id=${card.dataset.id}`;
            });
        });
    };

    layout();
    if (window.ResizeObserver) {
        const ro = new ResizeObserver(() => layout());
        ro.observe(strip);
    }
}

function buildSeriesDirectoryHref(seriesId, seriesTitle) {
    const params = new URLSearchParams({
        type: 'novel',
        seriesId: String(seriesId)
    });
    if (seriesTitle) params.set('seriesTitle', seriesTitle);
    return `/pixiv-series.html?${params.toString()}`;
}

async function loadAuthorWorks(authorId, strip) {
    try {
        const params = new URLSearchParams({
            page: '0',
            size: String(AUTHOR_WORKS_PER_ROW),
            sort: 'date',
            order: 'desc',
            authorIds: String(authorId)
        });
        const r = await fetch(`/api/gallery/novels?${params.toString()}`);
        if (!r.ok) throw new Error('HTTP ' + r.status);
        const data = await r.json();
        const items = data.content || [];
        if (!items.length) {
            strip.innerHTML = `<div class="author-works-empty">${esc(pageI18n.t('novel-gallery:status.empty', '暂无小说'))}</div>`;
            return;
        }
        strip.innerHTML = items.map(item => {
            const title = item.title || pageI18n.t('novel-gallery:status.unknown-novel', '小说 {id}', { id: item.novelId });
            const order = (item.seriesId && item.seriesId > 0 && item.seriesOrder != null)
                ? `<div class="author-work-pages">#${esc(item.seriesOrder)}</div>`
                : '';
            const cover = item.coverExt
                ? `<img src="/api/gallery/novel/${item.novelId}/cover" alt="${esc(title)}" loading="lazy" onerror="this.replaceWith(Object.assign(document.createElement('div'),{className:'author-work-thumb-placeholder',textContent:this.alt}))">`
                : `<div class="author-work-thumb-placeholder">${esc(title)}</div>`;
            return `
                <div class="author-work-card" data-id="${item.novelId}">
                    <div class="author-work-thumb">
                        ${cover}
                        ${order}
                    </div>
                    <div class="author-work-title">${esc(title)}</div>
                </div>`;
        }).join('');
        strip.querySelectorAll('.author-work-card').forEach(card => {
            card.addEventListener('click', () => {
                window.location.href = `/pixiv-novel.html?id=${card.dataset.id}`;
            });
        });
    } catch (e) {
        strip.innerHTML = `<div class="author-works-empty">${esc(pageI18n.t('novel-gallery:status.load-failed', '加载失败'))}</div>`;
    }
}

// ---------- Pagination helper ----------
function renderPagination(elementId, totalPages, page, onClick) {
    const wrap = document.getElementById(elementId);
    if (totalPages <= 1) { wrap.innerHTML = ''; return; }
    const pages = buildPageWindow(page, totalPages);
    const buttons = [];
    buttons.push(`<button data-pg="${page - 1}" ${page === 0 ? 'disabled' : ''}>‹</button>`);
    for (const p of pages) {
        if (p === '...') {
            buttons.push(buildPageJumpInput(totalPages));
        } else {
            buttons.push(`<button class="${p === page ? 'active' : ''}" data-pg="${p}">${p + 1}</button>`);
        }
    }
    buttons.push(`<button data-pg="${page + 1}" ${page >= totalPages - 1 ? 'disabled' : ''}>›</button>`);
    wrap.innerHTML = buttons.join('');
    wrap.querySelectorAll('button[data-pg]').forEach(btn => {
        if (btn.disabled) return;
        btn.addEventListener('click', () => {
            const target = Number(btn.dataset.pg);
            if (!Number.isInteger(target) || target < 0 || target >= totalPages) return;
            onClick(target);
        });
    });
    wrap.querySelectorAll('.page-jump-input').forEach(input => {
        let committed = false;
        const commit = () => {
            if (committed) return;
            committed = commitPageJump(input, totalPages, onClick);
        };
        input.addEventListener('keydown', event => {
            if (event.key === 'Enter') {
                event.preventDefault();
                commit();
                input.blur();
            }
        });
        input.addEventListener('blur', commit);
    });
}

function buildPageWindow(current, total) {
    const windowPages = new Set([0, total - 1, current, current - 1, current + 1]);
    const pages = [...windowPages].filter(n => n >= 0 && n < total).sort((a, b) => a - b);
    const out = [];
    for (let i = 0; i < pages.length; i++) {
        if (i > 0 && pages[i] - pages[i - 1] > 1) out.push('...');
        out.push(pages[i]);
    }
    return out;
}

function buildPageJumpInput(totalPages) {
    const label = pageI18n
        ? pageI18n.t('novel-gallery:pagination.jump', 'Jump to page')
        : 'Jump to page';
    return `<input class="page-jump-input" type="number" min="1" max="${totalPages}" step="1" inputmode="numeric" placeholder="..." aria-label="${esc(label)}" title="${esc(label)}">`;
}

function commitPageJump(input, totalPages, onClick) {
    const raw = input.value.trim();
    if (!raw) return false;
    const pageNumber = Number(raw);
    if (!Number.isInteger(pageNumber)) {
        input.value = '';
        return false;
    }
    const target = Math.min(Math.max(pageNumber, 1), totalPages) - 1;
    onClick(target);
    return true;
}

// ---------- Add to collection (novel) ----------
const collectionState = { activeNovelId: null, membership: new Set() };

async function openAddToCollectionModal(novelId) {
    collectionState.activeNovelId = novelId;
    try {
        const r = await fetch(`/api/collections/novels/of/${novelId}`, { credentials: 'same-origin' });
        if (r.ok) {
            const data = await r.json();
            collectionState.membership = new Set(data.collectionIds || []);
        } else {
            collectionState.membership = new Set();
        }
    } catch (e) {
        collectionState.membership = new Set();
    }
    renderAddToCollectionList();
    document.getElementById('modalAddToCollection').classList.add('open');
}

function closeAddToCollectionModal() {
    document.getElementById('modalAddToCollection').classList.remove('open');
    collectionState.activeNovelId = null;
    loadCollections();
    const ids = [...document.querySelectorAll('.card[data-novel-id]')].map(el => Number(el.dataset.novelId));
    if (ids.length) loadMembershipsForNovels(ids);
}

function renderAddToCollectionList() {
    const list = document.getElementById('addToCollectionList');
    if (!state.collections.length) {
        list.innerHTML = `<div class="empty" style="padding:24px">${esc(pageI18n.t('status.no-collections-hint', '暂无收藏夹，请先新建'))}</div>`;
        return;
    }
    list.innerHTML = state.collections.map(c => {
        const icon = c.iconExt
            ? `<img src="/api/collections/${c.id}/icon?v=${c.createdTime}" alt="">`
            : HEART_SVG;
        const checked = collectionState.membership.has(c.id) ? 'checked' : '';
        return `<label class="collection-row" data-id="${c.id}">
            <input type="checkbox" ${checked}>
            <div class="collection-row-icon">${icon}</div>
            <div class="collection-row-name">${esc(c.name)}</div>
            <span class="collection-count">${c.novelCount ?? 0}</span>
        </label>`;
    }).join('');
    list.querySelectorAll('.collection-row').forEach(row => {
        const id = Number(row.dataset.id);
        const cb = row.querySelector('input[type="checkbox"]');
        cb.addEventListener('change', async () => {
            const novelId = collectionState.activeNovelId;
            if (novelId == null) return;
            try {
                if (cb.checked) {
                    const r = await fetch(`/api/collections/${id}/novels/${novelId}`, { method: 'POST', credentials: 'same-origin' });
                    if (!r.ok) throw new Error('HTTP ' + r.status);
                    collectionState.membership.add(id);
                } else {
                    const r = await fetch(`/api/collections/${id}/novels/${novelId}`, { method: 'DELETE', credentials: 'same-origin' });
                    if (!r.ok) throw new Error('HTTP ' + r.status);
                    collectionState.membership.delete(id);
                }
            } catch (e) {
                cb.checked = !cb.checked;
                toast(pageI18n.t('toast.action-failed', '操作失败'), 'error');
            }
        });
    });
}
