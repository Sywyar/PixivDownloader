'use strict';
    const RELATED_PRELOAD_ITEMS = 3;
    const relatedThumbnailGroups = new Map();

    function observeRelatedThumbnails(container) {
        relatedThumbnailGroups.get(container)?.dispose();
        const boxes = [...container.querySelectorAll('[data-src]')];
        const visible = new Set();
        let frame = null;
        let disposed = false;
        const release = box => {
            const image = box.querySelector('img');
            image?.removeAttribute('src');
            image?.remove();
            box.removeAttribute('data-preview-src');
        };
        const refresh = () => {
            if (disposed) return;
            const wanted = new Set();
            if (document.visibilityState !== 'hidden') {
                for (const box of visible) {
                    const index = boxes.indexOf(box);
                    // 按作品顺序留出前后余量，避免窗口或字号变化改变预加载数量。
                    for (let i = Math.max(0, index - RELATED_PRELOAD_ITEMS);
                        i <= Math.min(boxes.length - 1, index + RELATED_PRELOAD_ITEMS); i++) wanted.add(boxes[i]);
                }
            }
            for (const box of boxes) {
                if (wanted.has(box)) {
                    if (!box.querySelector('img')) lazyLoadBox(box);
                } else release(box);
            }
        };
        const measure = () => {
            frame = null;
            if (disposed) return;
            const bounds = container.getBoundingClientRect();
            const left = Math.max(0, bounds.left);
            const right = Math.min(window.innerWidth, bounds.right);
            const top = Math.max(0, bounds.top);
            const bottom = Math.min(window.innerHeight, bounds.bottom);
            visible.clear();
            for (const box of boxes) {
                const rect = box.getBoundingClientRect();
                if (right > left && bottom > top && rect.width > 0 && rect.height > 0
                    && rect.right > left && rect.left < right && rect.bottom > top && rect.top < bottom) visible.add(box);
            }
            refresh();
        };
        const schedule = () => {
            if (frame === null) frame = requestAnimationFrame(measure);
        };
        const observer = typeof IntersectionObserver === 'function' ? new IntersectionObserver(entries => {
            if (disposed) return;
            for (const entry of entries) {
                if (entry.isIntersecting && entry.intersectionRatio > 0) visible.add(entry.target);
                else visible.delete(entry.target);
            }
            refresh();
        }) : null;
        const group = {
            refresh: measure,
            dispose() {
                disposed = true;
                observer?.disconnect();
                if (frame !== null) cancelAnimationFrame(frame);
                window.removeEventListener('scroll', schedule, true);
                window.removeEventListener('resize', schedule);
                for (const box of boxes) release(box);
                visible.clear();
                relatedThumbnailGroups.delete(container);
            }
        };
        relatedThumbnailGroups.set(container, group);
        if (observer) boxes.forEach(box => observer.observe(box));
        else {
            window.addEventListener('scroll', schedule, {capture: true, passive: true});
            window.addEventListener('resize', schedule);
            measure();
        }
    }

    function clearRelatedThumbnails() {
        for (const group of relatedThumbnailGroups.values()) group.dispose();
        relatedThumbnailGroups.clear();
    }

    document.addEventListener('visibilitychange', () => {
        for (const group of relatedThumbnailGroups.values()) group.refresh();
    });
    window.addEventListener('pagehide', clearRelatedThumbnails);
    window.addEventListener('pageshow', () => {
        for (const id of ['byAuthorList', 'bySeriesList', 'relatedGrid']) {
            const container = document.getElementById(id);
            if (container?.querySelector('[data-src]')) observeRelatedThumbnails(container);
        }
    });

    async function loadByAuthor() {
        const artworkId = state.artworkId;
        const list = document.getElementById('byAuthorList');
        const prevBtn = document.getElementById('byAuthorPrev');
        const nextBtn = document.getElementById('byAuthorNext');
        try {
            const items = await api(`/api/gallery/artwork/${artworkId}/by-author?limit=30`);
            if (state.artworkId !== artworkId) return;
            relatedThumbnailGroups.get(list)?.dispose();
            if (!items.length) {
                list.innerHTML = '<span id="byAuthorEmpty" style="color:var(--muted); font-size:12px; padding:0 28px">' + escapeHtml(wt('status.no-other', 'No other artworks')) + '</span>';
                prevBtn.style.display = 'none';
                nextBtn.style.display = 'none';
                return;
            }
            list.innerHTML = items.map(item => `
                <a class="thumb-item" href="/pixiv-artwork.html?id=${item.artworkId}">
                    <div class="thumb-box" data-src="/api/downloaded/thumbnail/${item.artworkId}/0"></div>
                    <div class="thumb-caption">${escapeHtml(item.title || '')}</div>
                </a>
            `).join('');
            prevBtn.style.display = '';
            nextBtn.style.display = '';
            observeRelatedThumbnails(list);
            bindThumbStripArrows(list, prevBtn, nextBtn);
        } catch (e) {
            // Ignore
        }
    }

    async function loadSeriesSections() {
        const artworkId = state.artworkId;
        const nav = await loadSeriesNav();
        if (state.artworkId !== artworkId) return;
        await loadBySeries(nav);
    }

    async function loadSeriesNav() {
        const artworkId = state.artworkId;
        resetSeriesNav();
        state.seriesNav = null;
        try {
            const nav = await api(`/api/gallery/artwork/${artworkId}/series`);
            if (state.artworkId !== artworkId) return null;
            if (!nav || !nav.seriesId) return null;
            state.seriesNav = nav;
            const seriesTitle = nav.seriesTitle || state.artwork.seriesTitle || wt('series.unknown-title', 'Untitled Series');
            if (state.artwork) {
                state.artwork.seriesId = nav.seriesId;
                state.artwork.seriesTitle = seriesTitle;
                state.artwork.seriesOrder = nav.currentOrder || state.artwork.seriesOrder;
            }
            renderSeriesNav(nav);
            return nav;
        } catch (e) {
            return null;
        }
    }

    function resetSeriesNav() {
        const navWrap = document.getElementById('seriesNav');
        const prevBtn = document.getElementById('seriesPrevBtn');
        const indexBtn = document.getElementById('seriesIndexBtn');
        const nextBtn = document.getElementById('seriesNextBtn');
        navWrap.style.display = 'none';
        prevBtn.style.display = 'none';
        indexBtn.style.display = 'none';
        nextBtn.style.display = 'none';
        prevBtn.removeAttribute('href');
        indexBtn.removeAttribute('href');
        nextBtn.removeAttribute('href');
        prevBtn.textContent = '';
        indexBtn.textContent = '';
        nextBtn.textContent = '';
    }

    function numericSeriesOrder(value) {
        const n = Number(value);
        return Number.isFinite(n) && n > 0 ? n : null;
    }

    function findSeriesNeighborFromItems(items, currentOrder, previous) {
        if (!Array.isArray(items) || currentOrder == null) return null;
        let best = null;
        let bestOrder = null;
        for (const item of items) {
            if (!item || String(item.artworkId) === String(state.artworkId)) continue;
            const order = numericSeriesOrder(item.seriesOrder);
            if (order == null) continue;
            if (previous) {
                if (order >= currentOrder) continue;
                if (bestOrder == null || order > bestOrder) {
                    best = item;
                    bestOrder = order;
                }
            } else {
                if (order <= currentOrder) continue;
                if (bestOrder == null || order < bestOrder) {
                    best = item;
                    bestOrder = order;
                }
            }
        }
        return best;
    }

    function renderSeriesNav(nav = null, seriesItems = []) {
        const navWrap = document.getElementById('seriesNav');
        const prevBtn = document.getElementById('seriesPrevBtn');
        const indexBtn = document.getElementById('seriesIndexBtn');
        const nextBtn = document.getElementById('seriesNextBtn');
        resetSeriesNav();

        const currentOrder = numericSeriesOrder(nav && nav.currentOrder) || numericSeriesOrder(state.artwork && state.artwork.seriesOrder);
        const prev = (nav && nav.prev) || findSeriesNeighborFromItems(seriesItems, currentOrder, true);
        const next = (nav && nav.next) || findSeriesNeighborFromItems(seriesItems, currentOrder, false);
        const seriesId = (nav && nav.seriesId) || (state.artwork && state.artwork.seriesId);
        state.seriesNav = { ...nav, seriesId, currentOrder, prev, next };
        let visible = false;

        if (prev) {
            renderSeriesNavButton(prevBtn, 'series.prev', 'Previous #{order} {title}', prev);
            visible = true;
        }
        if (seriesId) {
            indexBtn.href = buildSeriesDirectoryHref(seriesId, state.artworkId, currentOrder);
            indexBtn.textContent = wt('series.index', 'Directory');
            indexBtn.style.display = 'inline-flex';
            visible = true;
        }
        if (next) {
            renderSeriesNavButton(nextBtn, 'series.next', 'Next #{order} {title}', next);
            visible = true;
        }
        navWrap.style.display = visible ? 'flex' : 'none';
    }

    function renderSeriesNavButton(button, key, fallback, item) {
        button.href = `/pixiv-artwork.html?id=${item.artworkId}`;
        button.textContent = wt(key, fallback, {
            order: item.seriesOrder || '',
            title: item.title || wt('status.unknown-artwork', 'Artwork {id}', {id: item.artworkId})
        });
        button.style.display = 'inline-flex';
    }

    async function loadBySeries(nav) {
        const artworkId = state.artworkId;
        const panel = document.getElementById('seriesPanel');
        const list = document.getElementById('bySeriesList');
        const prevBtn = document.getElementById('bySeriesPrev');
        const nextBtn = document.getElementById('bySeriesNext');
        panel.style.display = 'none';
        relatedThumbnailGroups.get(list)?.dispose();
        list.innerHTML = '';
        try {
            const seriesId = (nav && nav.seriesId) || state.artwork.seriesId;
            if (!seriesId) return;
            const items = await api(`/api/gallery/artwork/${artworkId}/by-series?limit=30`);
            if (state.artworkId !== artworkId) return;
            if (!items.length) return;
            const seriesTitle = (nav && nav.seriesTitle) || state.artwork.seriesTitle || wt('series.unknown-title', 'Untitled Series');
            document.getElementById('seriesPanelTitle').textContent =
                wt('panel.series-with-title', 'This Series · {title}', {title: seriesTitle});
            document.getElementById('seriesViewAllLink').href = buildGalleryFilterHref({seriesId, seriesTitle});
            document.getElementById('seriesViewAllLink').textContent = wt('series.view-all', 'View All');
            renderSeriesNav(nav, items);
            list.innerHTML = items.map(item => `
                <a class="thumb-item" href="/pixiv-artwork.html?id=${item.artworkId}">
                    <div class="thumb-box" data-src="/api/downloaded/thumbnail/${item.artworkId}/0"></div>
                    <div class="thumb-caption">${escapeHtml(item.seriesOrder ? '#' + item.seriesOrder + ' ' : '')}${escapeHtml(item.title || '')}</div>
                </a>
            `).join('');
            bindThumbStripArrows(list, prevBtn, nextBtn);
            panel.style.display = '';
            observeRelatedThumbnails(list);
        } catch (e) {
            if (state.artworkId === artworkId) panel.style.display = 'none';
        }
    }

    function bindThumbStripArrows(list, prevBtn, nextBtn) {
        const updateArrows = () => {
            const atStart = list.scrollLeft <= 1;
            const atEnd = list.scrollLeft + list.clientWidth >= list.scrollWidth - 1;
            prevBtn.disabled = atStart;
            nextBtn.disabled = atEnd || list.scrollWidth <= list.clientWidth;
        };
        prevBtn.onclick = () => list.scrollBy({left: -list.clientWidth * 0.8, behavior: 'smooth'});
        nextBtn.onclick = () => list.scrollBy({left: list.clientWidth * 0.8, behavior: 'smooth'});
        list.onscroll = updateArrows;
        requestAnimationFrame(updateArrows);
    }

    async function loadRelated() {
        const artworkId = state.artworkId;
        try {
            const items = await api(`/api/gallery/artwork/${artworkId}/related?limit=12`);
            if (state.artworkId !== artworkId) return;
            if (!items.length) return;
            document.getElementById('relatedPanel').style.display = '';
            const grid = document.getElementById('relatedGrid');
            relatedThumbnailGroups.get(grid)?.dispose();
            grid.innerHTML = items.map(item => `
                <a class="related-card" href="/pixiv-artwork.html?id=${item.artworkId}">
                    <div class="related-thumb" data-src="/api/downloaded/thumbnail/${item.artworkId}/0"></div>
                    <div class="related-title">${escapeHtml(item.title || '')}</div>
                </a>
            `).join('');
            observeRelatedThumbnails(grid);
        } catch (e) {
            // Ignore
        }
    }

    function refreshRelatedTranslations() {
        const empty = document.getElementById('byAuthorEmpty');
        if (empty) empty.textContent = wt('status.no-other', 'No other artworks');
        renderSeriesNav(state.seriesNav);
        if (document.getElementById('seriesPanel').style.display !== 'none') {
            const title = state.seriesNav?.seriesTitle || state.artwork.seriesTitle || wt('series.unknown-title', 'Untitled Series');
            document.getElementById('seriesPanelTitle').textContent = wt('panel.series-with-title', 'This Series · {title}', {title});
        }
    }

    function lazyLoadBox(box) {
        const url = window.PixivLayout.previewUrl(box.dataset.src, box);
        const img = document.createElement('img');
        img.alt = '';
        img.decoding = 'async';
        img.src = url;
        box.appendChild(img);
    }


// ---- PixivArtwork facade ----
window.PixivArtwork.related = window.PixivArtwork.related || {};
window.PixivArtwork.related = Object.assign(window.PixivArtwork.related, { loadByAuthor, loadSeriesSections, loadSeriesNav, resetSeriesNav, numericSeriesOrder, findSeriesNeighborFromItems, renderSeriesNav, renderSeriesNavButton, loadBySeries, bindThumbStripArrows, loadRelated, lazyLoadBox });
