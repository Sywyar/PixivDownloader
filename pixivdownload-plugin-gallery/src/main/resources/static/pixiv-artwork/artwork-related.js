'use strict';
    async function loadByAuthor() {
        const list = document.getElementById('byAuthorList');
        const prevBtn = document.getElementById('byAuthorPrev');
        const nextBtn = document.getElementById('byAuthorNext');
        try {
            const items = await api(`/api/gallery/artwork/${state.artworkId}/by-author?limit=30`);
            if (!items.length) {
                list.innerHTML = '<span style="color:var(--muted); font-size:12px; padding:0 28px">' + escapeHtml(wt('status.no-other', 'No other artworks')) + '</span>';
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
            list.querySelectorAll('.thumb-box[data-src]').forEach(box => lazyLoadBox(box));

            const updateArrows = () => {
                const atStart = list.scrollLeft <= 1;
                const atEnd = list.scrollLeft + list.clientWidth >= list.scrollWidth - 1;
                prevBtn.disabled = atStart;
                nextBtn.disabled = atEnd || list.scrollWidth <= list.clientWidth;
            };
            prevBtn.onclick = () => list.scrollBy({left: -list.clientWidth * 0.8, behavior: 'smooth'});
            nextBtn.onclick = () => list.scrollBy({left: list.clientWidth * 0.8, behavior: 'smooth'});
            list.addEventListener('scroll', updateArrows);
            requestAnimationFrame(updateArrows);
        } catch (e) {
            // Ignore
        }
    }

    async function loadSeriesSections() {
        const nav = await loadSeriesNav();
        await loadBySeries(nav);
    }

    async function loadSeriesNav() {
        resetSeriesNav();
        state.seriesNav = null;
        try {
            const nav = await api(`/api/gallery/artwork/${state.artworkId}/series`);
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
        const panel = document.getElementById('seriesPanel');
        const list = document.getElementById('bySeriesList');
        const prevBtn = document.getElementById('bySeriesPrev');
        const nextBtn = document.getElementById('bySeriesNext');
        panel.style.display = 'none';
        list.innerHTML = '';
        try {
            const seriesId = (nav && nav.seriesId) || state.artwork.seriesId;
            if (!seriesId) return;
            const items = await api(`/api/gallery/artwork/${state.artworkId}/by-series?limit=30`);
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
            list.querySelectorAll('.thumb-box[data-src]').forEach(box => lazyLoadBox(box));
            bindThumbStripArrows(list, prevBtn, nextBtn);
            panel.style.display = '';
        } catch (e) {
            panel.style.display = 'none';
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
        list.addEventListener('scroll', updateArrows);
        requestAnimationFrame(updateArrows);
    }

    async function loadRelated() {
        try {
            const items = await api(`/api/gallery/artwork/${state.artworkId}/related?limit=12`);
            if (!items.length) return;
            document.getElementById('relatedPanel').style.display = '';
            const grid = document.getElementById('relatedGrid');
            grid.innerHTML = items.map(item => `
                <a class="related-card" href="/pixiv-artwork.html?id=${item.artworkId}">
                    <div class="related-thumb" data-src="/api/downloaded/thumbnail/${item.artworkId}/0"></div>
                    <div class="related-title">${escapeHtml(item.title || '')}</div>
                </a>
            `).join('');
            grid.querySelectorAll('.related-thumb[data-src]').forEach(box => lazyLoadBox(box));
        } catch (e) {
            // Ignore
        }
    }

    async function lazyLoadBox(box) {
        const url = box.dataset.src;
        box.removeAttribute('data-src');
        const cached = ImageCache.get(url);
        if (cached) {
            const img = document.createElement('img');
            img.src = cached;
            img.alt = '';
            box.appendChild(img);
            return;
        }
        try {
            const resp = await api(url);
            if (resp && resp.success && resp.image) {
                const ext = (resp.extension || 'jpg').toLowerCase();
                const src = `data:image/${ext === 'jpg' ? 'jpeg' : ext};base64,${resp.image}`;
                ImageCache.put(url, src);
                const img = document.createElement('img');
                img.src = src;
                img.alt = '';
                box.appendChild(img);
            }
        } catch (e) {
            // Silent
        }
    }


// ---- PixivArtwork facade ----
window.PixivArtwork.related = window.PixivArtwork.related || {};
window.PixivArtwork.related = Object.assign(window.PixivArtwork.related, { loadByAuthor, loadSeriesSections, loadSeriesNav, resetSeriesNav, numericSeriesOrder, findSeriesNeighborFromItems, renderSeriesNav, renderSeriesNavButton, loadBySeries, bindThumbStripArrows, loadRelated, lazyLoadBox });
