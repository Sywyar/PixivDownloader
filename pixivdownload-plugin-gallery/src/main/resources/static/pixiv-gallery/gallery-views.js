'use strict';

    let galleryStatusModel = null;

    function setGalleryStatus(code, values) {
        galleryStatusModel = {code, values: values || {}};
        renderGalleryStatus();
    }

    function renderGalleryStatus() {
        if (!galleryStatusModel) return;
        const status = document.getElementById('galleryStatus');
        if (!status) return;
        const values = galleryStatusModel.values;
        if (galleryStatusModel.code === 'range') {
            status.textContent = t('status.gallery-range', '共 {total} 条，第 {from}-{to} 条', values);
        } else if (galleryStatusModel.code === 'failure') {
            status.textContent = t('status.load-failed', '加载失败：{message}', values);
        } else {
            status.textContent = t('status.loading', '加载中...');
        }
    }

    // ---------- Gallery ----------
    async function loadGallery() {
        persistGalleryState();
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
        const params = new URLSearchParams();
        params.set('page', state.page);
        params.set('size', state.size);
        params.set('sort', state.sort);
        params.set('order', state.order);
        if (state.search) params.set('search', state.search);
        if (state.search && state.searchType && state.searchType !== 'all') params.set('searchType', state.searchType);
        if (state.r18 !== 'any') params.set('r18', state.r18);
        if (state.ai !== 'any') params.set('ai', state.ai);
        if (state.formats.size) params.set('format', [...state.formats].join(','));
        if (state.collectionIds.size) params.set('collectionIds', [...state.collectionIds].join(','));
        if (state.tagFilters.must.size) params.set('tagIds', [...state.tagFilters.must].join(','));
        if (state.tagFilters.not.size) params.set('notTagIds', [...state.tagFilters.not].join(','));
        if (state.tagFilters.or.size) params.set('orTagIds', [...state.tagFilters.or].join(','));
        if (state.authorFilters.must.size) params.set('authorIds', [...state.authorFilters.must].join(','));
        if (state.authorFilters.not.size) params.set('notAuthorIds', [...state.authorFilters.not].join(','));
        if (state.authorFilters.or.size) params.set('orAuthorIds', [...state.authorFilters.or].join(','));
        if (state.seriesFilter.id) params.set('seriesId', String(state.seriesFilter.id));

        setGalleryStatus('loading');
        try {
            const result = await api('/api/gallery/artworks?' + params.toString());
            state.totalPages = result.totalPages || 0;
            state.totalElements = result.totalElements || 0;
            // 先更新计数文案，确保即使列表为空也不会停留在「加载中…」
            const from = state.totalElements === 0 ? 0 : state.page * state.size + 1;
            const to = Math.min(state.totalElements, (state.page + 1) * state.size);
            setGalleryStatus('range', {
                total: state.totalElements,
                from,
                to
            });
            renderGallery(result.content || []);
            renderPagination();
            // 仅在存在有效搜索/筛选条件时才把搜索框与筛选按钮标红
            setSearchEmptyState(state.totalElements === 0 && hasActiveGalleryFilters());
        } catch (e) {
            state.totalElements = 0;
            setGalleryStatus('failure', {message: e.message});
            document.getElementById('galleryGrid').innerHTML = '';
            document.getElementById('pagination').innerHTML = '';
            setSearchEmptyState(false);
        }
    }

    function hasActiveGalleryFilters() {
        return !!(state.search
            || state.r18 !== 'any'
            || state.ai !== 'any'
            || state.formats.size
            || state.collectionIds.size
            || state.tagFilters.must.size || state.tagFilters.not.size || state.tagFilters.or.size
            || state.authorFilters.must.size || state.authorFilters.not.size || state.authorFilters.or.size
            || state.seriesFilter.id);
    }

    function renderGallery(items) {
        const grid = document.getElementById('galleryGrid');
        if (!items.length) {
            if (hasActiveGalleryFilters()) {
                grid.innerHTML = '<div class="empty-state" style="grid-column:1/-1">' + escapeHtml(t('status.no-matching-artworks', 'No matching artworks')) + '</div>';
            } else {
                grid.innerHTML = '<div class="empty-state empty-state-cta" style="grid-column:1/-1">'
                    + '<div>' + escapeHtml(t('status.no-downloads', '没有下载的作品，快去下载页下载吧！')) + '</div>'
                    + '<a class="btn btn-primary" href="/pixiv-batch.html" target="_blank" rel="noopener">' + escapeHtml(t('status.go-download', '前往下载页')) + '</a>'
                    + '</div>';
            }
            return;
        }
        grid.innerHTML = items.map(item => {
            const xRestrict = item.xRestrict;
            const isR18 = xRestrict === 1;
            const isR18G = xRestrict === 2;
            const isAi = item.isAi === true;
            const pages = item.count || 1;
            return `
                <div class="work-card" data-id="${item.artworkId}">
                    <div class="work-thumb thumb-loading">
                        <img data-src="/api/downloaded/thumbnail-file/${item.artworkId}/0" alt="${escapeHtml(item.title || '')}" loading="lazy">
                        <span class="card-select" aria-hidden="true">
                            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>
                        </span>
                        <div class="thumb-veil"></div>
                        <div class="thumb-badges">
                            <div class="thumb-badge-group">
                                ${isR18G ? '<span class="thumb-badge r18g">R-18G</span>' : isR18 ? '<span class="thumb-badge r18">R-18</span>' : ''}
                                ${isAi ? '<span class="thumb-badge ai">AI</span>' : ''}
                            </div>
                            ${pages > 1 ? `<span class="thumb-badge pages">${pages}P</span>` : ''}
                        </div>
                        <button class="thumb-heart" data-id="${item.artworkId}" title="${escapeHtml(t('collection.add', 'Add to Collection'))}">${HEART_SVG}</button>
                    </div>
                    <div class="work-meta">
                        <div class="work-title">${escapeHtml(item.title || t('status.untitled', 'Untitled'))}</div>
                        <div class="work-author">${escapeHtml(item.authorName || (item.authorId ? t('author.default', 'Author {id}', {id: item.authorId}) : t('author.unknown', 'Unknown author')))}</div>
                        <div class="work-collections" data-collections-for="${item.artworkId}"></div>
                    </div>
                </div>
            `;
        }).join('');

        grid.querySelectorAll('.work-card').forEach(card => {
            const id = card.dataset.id;
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
                window.location.href = `/pixiv-artwork.html?id=${id}`;
            });
            card.querySelector('.thumb-heart').addEventListener('click', e => {
                e.stopPropagation();
                openAddToCollectionModal(Number(id));
            });
        });

        // Lazy-load thumbnails through the binary thumbnail endpoint.
        grid.querySelectorAll('img[data-src]').forEach(img => {
            loadThumbnail(img);
        });

        loadMembershipsForCurrentPage(items.map(i => i.artworkId));
        if (batch.active) updateBatchBar();
    }

    async function loadMembershipsForCurrentPage(artworkIds) {
        if (!artworkIds.length) return;
        let memberships = {};
        try {
            const resp = await api('/api/collections/memberships', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({artworkIds}),
            });
            memberships = resp.memberships || {};
        } catch (e) {
            return;
        }
        const byId = new Map(state.collections.map(c => [c.id, c]));
        artworkIds.forEach(id => {
            const collectionIds = memberships[id] || [];
            const card = document.querySelector(`.work-card[data-id="${id}"]`);
            if (!card) return;
            const heart = card.querySelector('.thumb-heart');
            heart.classList.toggle('liked', collectionIds.length > 0);
            const label = card.querySelector(`[data-collections-for="${id}"]`);
            if (!label) return;
            if (collectionIds.length === 0) {
                label.textContent = '';
                return;
            }
            const names = collectionIds.map(cid => byId.get(cid)?.name).filter(Boolean);
            label.textContent = names.length ? '♥ ' + names.join(listSeparator()) : '';
        });
    }

    function renderPagination() {
        const pag = document.getElementById('pagination');
        if (state.totalPages <= 1) {
            pag.innerHTML = '';
            return;
        }
        const pages = buildPageWindow(state.page, state.totalPages);
        const parts = [];
        parts.push(`<button class="page-btn" data-page="${state.page - 1}" ${state.page === 0 ? 'disabled' : ''}>${escapeHtml(t('pagination.prev', 'Previous'))}</button>`);
        for (const p of pages) {
            if (p === '...') parts.push(buildPageJumpInput(state.totalPages));
            else parts.push(`<button class="page-btn ${p === state.page ? 'active' : ''}" data-page="${p}">${p + 1}</button>`);
        }
        parts.push(`<button class="page-btn" data-page="${state.page + 1}" ${state.page >= state.totalPages - 1 ? 'disabled' : ''}>${escapeHtml(t('pagination.next', 'Next'))}</button>`);
        pag.innerHTML = parts.join('');
        bindPaginationControls(pag, state.totalPages, p => {
            state.page = p;
            loadGallery();
        });
    }

    function buildPageJumpInput(totalPages) {
        const label = escapeHtml(t('pagination.jump', 'Jump to page'));
        return `<input class="page-jump-input" type="number" min="1" max="${totalPages}" step="1" inputmode="numeric" placeholder="..." aria-label="${label}" title="${label}">`;
    }

    function bindPaginationControls(pag, totalPages, onPageChange) {
        const goToPage = p => {
            if (!Number.isInteger(p) || p < 0 || p >= totalPages) return;
            onPageChange(p);
            scrollGalleryToTop();
        };
        pag.querySelectorAll('.page-btn').forEach(btn => {
            btn.addEventListener('click', () => {
                if (btn.disabled) return;
                const p = Number(btn.dataset.page);
                goToPage(p);
            });
        });
        pag.querySelectorAll('.page-jump-input').forEach(input => {
            let committed = false;
            const commit = () => {
                if (committed) return;
                committed = commitPageJump(input, totalPages, goToPage);
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

    function commitPageJump(input, totalPages, goToPage) {
        const raw = input.value.trim();
        if (!raw) return false;
        const pageNumber = Number(raw);
        if (!Number.isInteger(pageNumber)) {
            input.value = '';
            return false;
        }
        const target = Math.min(Math.max(pageNumber, 1), totalPages) - 1;
        goToPage(target);
        return true;
    }

    function scrollGalleryToTop() {
        const wrapper = document.querySelector('.gallery-wrapper');
        if (wrapper) wrapper.scrollTop = 0;
        window.scrollTo({top: 0, behavior: 'smooth'});
    }

    function buildPageWindow(current, total) {
        const window1 = new Set([0, total - 1, current, current - 1, current + 1]);
        const pages = [...window1].filter(n => n >= 0 && n < total).sort((a, b) => a - b);
        const out = [];
        for (let i = 0; i < pages.length; i++) {
            if (i > 0 && pages[i] - pages[i - 1] > 1) out.push('...');
            out.push(pages[i]);
        }
        return out;
    }

    // ---------- Add to collection ----------
    async function openAddToCollectionModal(artworkId) {
        state.activeArtworkId = artworkId;
        try {
            const resp = await api(`/api/collections/of/${artworkId}`);
            state.collectionMembership = new Set(resp.collectionIds || []);
        } catch (e) {
            state.collectionMembership = new Set();
        }
        renderAddToCollectionList();
        document.getElementById('modalAddToCollection').classList.add('open');
    }

    function closeAddToCollectionModal() {
        document.getElementById('modalAddToCollection').classList.remove('open');
        state.activeArtworkId = null;
        loadCollections();
        const ids = [...document.querySelectorAll('.work-card[data-id]')].map(el => Number(el.dataset.id));
        if (ids.length) loadMembershipsForCurrentPage(ids);
    }

    function renderAddToCollectionList() {
        const list = document.getElementById('addToCollectionList');
        if (!state.collections.length) {
            list.innerHTML = '<div class="empty-state" style="padding:24px">' + escapeHtml(t('status.no-collections-hint', 'No collections yet. Create one first.')) + '</div>';
            return;
        }
        list.innerHTML = state.collections.map(c => `
            <label class="collection-row" data-id="${c.id}">
                <input type="checkbox" ${state.collectionMembership.has(c.id) ? 'checked' : ''}>
                <div class="collection-row-icon">${iconHtml(c)}</div>
                <div class="collection-row-name">${escapeHtml(c.name)}</div>
                <span class="collection-count">${c.artworkCount}</span>
            </label>
        `).join('');
        list.querySelectorAll('.collection-row').forEach(row => {
            const id = Number(row.dataset.id);
            const cb = row.querySelector('input[type="checkbox"]');
            cb.addEventListener('change', async () => {
                try {
                    if (cb.checked) {
                        await api(`/api/collections/${id}/artworks/${state.activeArtworkId}`, {method: 'POST'});
                        state.collectionMembership.add(id);
                    } else {
                        await api(`/api/collections/${id}/artworks/${state.activeArtworkId}`, {method: 'DELETE'});
                        state.collectionMembership.delete(id);
                    }
                } catch (e) {
                    toast(e.message || t('toast.action-failed', 'Action failed'), 'error');
                    cb.checked = !cb.checked;
                }
            });
        });
    }

    function switchView(view) {
        view = normalizeView(view);
        const prev = state.view;
        state.view = view;
        syncViewParamInUrl();
        syncViewNavigationHrefs();
        document.querySelectorAll('.nav-item[data-view]').forEach(el => {
            el.classList.toggle('active', el.dataset.view === view);
        });
        const showAll = view === 'all';
        document.getElementById('galleryGrid').style.display = showAll ? '' : 'none';
        document.getElementById('pagination').style.display = showAll ? '' : 'none';
        document.getElementById('galleryStatus').style.display = showAll ? '' : 'none';
        syncTagFilterBar();
        syncAuthorFilterBar();
        syncSeriesFilterBar();
        document.getElementById('authorView').classList.toggle('active', !showAll);
        document.getElementById('authorPagination').style.display = showAll ? 'none' : '';
        updateSearchPlaceholder();
        if (view === 'authors') {
            loadAuthorsView();
        } else if (view === 'series') {
            loadSeriesView();
        } else if (prev !== 'all') {
            loadGallery();
        }
    }

    function ensureGalleryView() {
        if (state.view === 'all') return false;
        switchView('all');
        return true;
    }

    function refreshGalleryForCurrentState() {
        if (!ensureGalleryView()) {
            loadGallery();
        }
    }

    function reloadCurrentView() {
        if (state.view === 'authors') {
            loadAuthorsView();
            return;
        }
        if (state.view === 'series') {
            loadSeriesView();
            return;
        }
        loadGallery();
    }

    // ---------- Authors view ----------
    async function loadAuthorsView() {
        persistGalleryState();
        const container = document.getElementById('authorView');
        setSearchEmptyState(false);
        container.innerHTML = '<div class="author-works-loading">' + escapeHtml(t('status.loading-authors', 'Loading authors...')) + '</div>';
        try {
            const params = new URLSearchParams();
            params.set('page', state.authors.page);
            params.set('size', state.authors.size);
            params.set('sort', 'artworks');
            if (state.search) params.set('search', state.search);
            const resp = await api('/api/authors/paged?' + params.toString());
            state.authors.totalPages = resp.totalPages || 0;
            state.authors.totalElements = resp.totalElements || 0;
            state.authors.content = resp.content || [];
            renderAuthorsView();
            renderAuthorsPagination();
        } catch (e) {
            container.innerHTML = `<div class="author-works-loading">${escapeHtml(t('status.load-failed', 'Load failed: {message}', {message: e.message}))}</div>`;
        }
    }

    function renderAuthorsView() {
        const container = document.getElementById('authorView');
        if (!state.authors.content.length) {
            container.innerHTML = '<div class="empty-state">' + escapeHtml(t('status.no-authors', 'No authors')) + '</div>';
            return;
        }
        container.innerHTML = state.authors.content.map(a => `
            <div class="author-row" data-author-id="${a.authorId}">
                <div class="author-row-info">
                    <div class="author-row-name" data-filter-author="${a.authorId}" title="${escapeHtml(a.name || '')}">${escapeHtml(a.name || t('status.untitled', 'Untitled'))}</div>
                    <div class="author-row-count">${escapeHtml(t('author.count', '{count} artworks · #{authorId}', {count: a.artworkCount, authorId: a.authorId}))}</div>
                    <div class="author-row-actions">
                        <button class="author-row-btn" data-filter-author="${a.authorId}" type="button">${escapeHtml(t('button.view-all', 'View All'))}</button>
                    </div>
                </div>
                <div class="author-row-works">
                    <button class="author-works-arrow left" data-arrow="left" type="button" title="${escapeHtml(t('button.prev-group', 'Previous'))}">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 18 9 12 15 6"/></svg>
                    </button>
                    <div class="author-works-strip" data-strip-for="${a.authorId}">
                        <div class="author-works-loading">${escapeHtml(t('status.loading', 'Loading...'))}</div>
                    </div>
                    <button class="author-works-arrow right" data-arrow="right" type="button" title="${escapeHtml(t('button.next-group', 'Next'))}">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 18 15 12 9 6"/></svg>
                    </button>
                </div>
            </div>
        `).join('');

        container.querySelectorAll('[data-filter-author]').forEach(el => {
            el.addEventListener('click', e => {
                e.stopPropagation();
                const id = Number(el.dataset.filterAuthor);
                const author = state.authors.content.find(a => a.authorId === id);
                setAuthorFilterExclusive(id, author ? author.name : null);
            });
        });

        container.querySelectorAll('.author-row').forEach(row => {
            const authorId = Number(row.dataset.authorId);
            const strip = row.querySelector('.author-works-strip');
            const leftBtn = row.querySelector('[data-arrow="left"]');
            const rightBtn = row.querySelector('[data-arrow="right"]');

            loadAuthorWorks(authorId, strip).then(() => updateArrowState(strip, leftBtn, rightBtn));

            leftBtn.addEventListener('click', () => {
                strip.scrollBy({left: -strip.clientWidth * 0.8, behavior: 'smooth'});
            });
            rightBtn.addEventListener('click', () => {
                strip.scrollBy({left: strip.clientWidth * 0.8, behavior: 'smooth'});
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

    async function loadAuthorWorks(authorId, strip) {
        try {
            const params = new URLSearchParams();
            params.set('page', 0);
            params.set('size', AUTHOR_WORKS_PER_ROW);
            params.set('sort', 'date');
            params.set('order', 'desc');
            params.set('authorId', authorId);
            const resp = await api('/api/gallery/artworks?' + params.toString());
            const items = resp.content || [];
            if (!items.length) {
                strip.innerHTML = '<div class="author-works-empty">' + escapeHtml(t('status.no-artworks', 'No artworks')) + '</div>';
                return;
            }
            strip.innerHTML = items.map(item => {
                const pages = item.count || 1;
                return `
                    <div class="author-work-card" data-id="${item.artworkId}">
                        <div class="author-work-thumb thumb-loading">
                            <img data-src="/api/downloaded/thumbnail-file/${item.artworkId}/0" alt="${escapeHtml(item.title || '')}" loading="lazy">
                            ${pages > 1 ? `<div class="author-work-pages">${pages}P</div>` : ''}
                        </div>
                        <div class="author-work-title">${escapeHtml(item.title || t('status.untitled', 'Untitled'))}</div>
                    </div>
                `;
            }).join('');
            strip.querySelectorAll('.author-work-card').forEach(card => {
                card.addEventListener('click', () => {
                    window.location.href = `/pixiv-artwork.html?id=${card.dataset.id}`;
                });
            });
            strip.querySelectorAll('img[data-src]').forEach(img => loadThumbnail(img));
        } catch (e) {
            strip.innerHTML = `<div class="author-works-empty">${escapeHtml(t('status.load-failed', 'Load failed: {message}', {message: e.message}))}</div>`;
        }
    }

    function renderAuthorsPagination() {
        const pag = document.getElementById('authorPagination');
        const totalPages = state.authors.totalPages;
        if (totalPages <= 1) {
            pag.innerHTML = '';
            return;
        }
        const pages = buildPageWindow(state.authors.page, totalPages);
        const parts = [];
        const cur = state.authors.page;
        parts.push(`<button class="page-btn" data-page="${cur - 1}" ${cur === 0 ? 'disabled' : ''}>${escapeHtml(t('pagination.prev', 'Previous'))}</button>`);
        for (const p of pages) {
            if (p === '...') parts.push(buildPageJumpInput(totalPages));
            else parts.push(`<button class="page-btn ${p === cur ? 'active' : ''}" data-page="${p}">${p + 1}</button>`);
        }
        parts.push(`<button class="page-btn" data-page="${cur + 1}" ${cur >= totalPages - 1 ? 'disabled' : ''}>${escapeHtml(t('pagination.next', 'Next'))}</button>`);
        pag.innerHTML = parts.join('');
        bindPaginationControls(pag, totalPages, p => {
            state.authors.page = p;
            loadAuthorsView();
        });
    }

    // ---------- Series view ----------
    async function loadSeriesView() {
        persistGalleryState();
        const container = document.getElementById('authorView');
        setSearchEmptyState(false);
        container.innerHTML = '<div class="author-works-loading">' + escapeHtml(t('status.loading-series', 'Loading series...')) + '</div>';
        try {
            const params = new URLSearchParams();
            params.set('page', state.series.page);
            params.set('size', state.series.size);
            params.set('sort', 'artworks');
            if (state.search) params.set('search', state.search);
            const resp = await api('/api/series/paged?' + params.toString());
            state.series.totalPages = resp.totalPages || 0;
            state.series.totalElements = resp.totalElements || 0;
            state.series.content = resp.content || [];
            state.series.content.forEach(s => {
                if (s && s.seriesId != null && s.title) {
                    state.seriesNames.set(s.seriesId, s.title);
                }
            });
            renderSeriesView();
            renderSeriesPagination();
        } catch (e) {
            container.innerHTML = `<div class="author-works-loading">${escapeHtml(t('status.load-failed', 'Load failed: {message}', {message: e.message}))}</div>`;
        }
    }

    function renderSeriesView() {
        const container = document.getElementById('authorView');
        if (!state.series.content.length) {
            container.innerHTML = '<div class="empty-state">' + escapeHtml(t('status.no-series', 'No series')) + '</div>';
            return;
        }
        container.innerHTML = state.series.content.map(s => {
            const title = s.title || t('series.default', 'Series #{id}', {id: s.seriesId});
            const author = s.authorName
                ? `<div class="author-row-count">${escapeHtml(t('series.author-prefix', 'Author: {name}', {name: s.authorName}))}</div>`
                : '';
            return `
            <div class="author-row series-row" data-series-id="${s.seriesId}">
                <div class="author-row-info">
                    <div class="author-row-name" data-open-series-directory="${s.seriesId}" title="${escapeHtml(title)}">${escapeHtml(title)}</div>
                    <div class="author-row-count">${escapeHtml(t('series.count', '{count} artworks · #{seriesId}', {count: s.artworkCount, seriesId: s.seriesId}))}</div>
                    ${author}
                    <div class="author-row-actions">
                        <button class="author-row-btn" data-open-series-directory="${s.seriesId}" type="button">${escapeHtml(t('button.view-all', 'View All'))}</button>
                    </div>
                </div>
                <div class="author-row-works">
                    <button class="author-works-arrow left" data-arrow="left" type="button" title="${escapeHtml(t('button.prev-group', 'Previous'))}">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 18 9 12 15 6"/></svg>
                    </button>
                    <div class="author-works-strip" data-strip-for="${s.seriesId}">
                        <div class="author-works-loading">${escapeHtml(t('status.loading', 'Loading...'))}</div>
                    </div>
                    <button class="author-works-arrow right" data-arrow="right" type="button" title="${escapeHtml(t('button.next-group', 'Next'))}">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 18 15 12 9 6"/></svg>
                    </button>
                </div>
            </div>`;
        }).join('');

        container.querySelectorAll('[data-open-series-directory]').forEach(el => {
            el.addEventListener('click', e => {
                e.stopPropagation();
                const id = Number(el.dataset.openSeriesDirectory);
                const series = state.series.content.find(s => s.seriesId === id);
                const title = series ? series.title : state.seriesNames.get(id);
                window.location.assign(buildSeriesDirectoryHref({seriesId: id, seriesTitle: title}));
            });
        });

        container.querySelectorAll('.series-row').forEach(row => {
            const seriesId = Number(row.dataset.seriesId);
            const strip = row.querySelector('.author-works-strip');
            const leftBtn = row.querySelector('[data-arrow="left"]');
            const rightBtn = row.querySelector('[data-arrow="right"]');

            loadSeriesWorks(seriesId, strip).then(() => updateArrowState(strip, leftBtn, rightBtn));

            leftBtn.addEventListener('click', () => {
                strip.scrollBy({left: -strip.clientWidth * 0.8, behavior: 'smooth'});
            });
            rightBtn.addEventListener('click', () => {
                strip.scrollBy({left: strip.clientWidth * 0.8, behavior: 'smooth'});
            });
            strip.addEventListener('scroll', () => updateArrowState(strip, leftBtn, rightBtn));
        });
    }

    async function loadSeriesWorks(seriesId, strip) {
        try {
            const params = new URLSearchParams();
            params.set('page', 0);
            params.set('size', AUTHOR_WORKS_PER_ROW);
            params.set('sort', 'series');
            params.set('order', 'asc');
            params.set('seriesId', seriesId);
            const resp = await api('/api/gallery/artworks?' + params.toString());
            const items = resp.content || [];
            if (!items.length) {
                strip.innerHTML = '<div class="author-works-empty">' + escapeHtml(t('status.no-artworks', 'No artworks')) + '</div>';
                return;
            }
            strip.innerHTML = items.map(item => {
                const pages = item.count || 1;
                const order = item.seriesOrder ? `<div class="author-work-pages">#${escapeHtml(item.seriesOrder)}</div>` : '';
                return `
                    <div class="author-work-card" data-id="${item.artworkId}">
                        <div class="author-work-thumb thumb-loading">
                            <img data-src="/api/downloaded/thumbnail-file/${item.artworkId}/0" alt="${escapeHtml(item.title || '')}" loading="lazy">
                            ${order || (pages > 1 ? `<div class="author-work-pages">${pages}P</div>` : '')}
                        </div>
                        <div class="author-work-title">${escapeHtml(item.title || t('status.untitled', 'Untitled'))}</div>
                    </div>
                `;
            }).join('');
            strip.querySelectorAll('.author-work-card').forEach(card => {
                card.addEventListener('click', () => {
                    window.location.href = `/pixiv-artwork.html?id=${card.dataset.id}`;
                });
            });
            strip.querySelectorAll('img[data-src]').forEach(img => loadThumbnail(img));
        } catch (e) {
            strip.innerHTML = `<div class="author-works-empty">${escapeHtml(t('status.load-failed', 'Load failed: {message}', {message: e.message}))}</div>`;
        }
    }

    function renderSeriesPagination() {
        const pag = document.getElementById('authorPagination');
        const totalPages = state.series.totalPages;
        if (totalPages <= 1) {
            pag.innerHTML = '';
            return;
        }
        const pages = buildPageWindow(state.series.page, totalPages);
        const parts = [];
        const cur = state.series.page;
        parts.push(`<button class="page-btn" data-page="${cur - 1}" ${cur === 0 ? 'disabled' : ''}>${escapeHtml(t('pagination.prev', 'Previous'))}</button>`);
        for (const p of pages) {
            if (p === '...') parts.push(buildPageJumpInput(totalPages));
            else parts.push(`<button class="page-btn ${p === cur ? 'active' : ''}" data-page="${p}">${p + 1}</button>`);
        }
        parts.push(`<button class="page-btn" data-page="${cur + 1}" ${cur >= totalPages - 1 ? 'disabled' : ''}>${escapeHtml(t('pagination.next', 'Next'))}</button>`);
        pag.innerHTML = parts.join('');
        bindPaginationControls(pag, totalPages, p => {
            state.series.page = p;
            loadSeriesView();
        });
    }


// ---- PixivGallery facade ----
window.PixivGallery.views = Object.assign(window.PixivGallery.views || {}, { loadGallery, hasActiveGalleryFilters, renderGallery, loadMembershipsForCurrentPage, renderPagination, buildPageJumpInput, bindPaginationControls, commitPageJump, scrollGalleryToTop, buildPageWindow, openAddToCollectionModal, closeAddToCollectionModal, renderAddToCollectionList, switchView, ensureGalleryView, refreshGalleryForCurrentState, reloadCurrentView, loadAuthorsView, renderAuthorsView, updateArrowState, loadAuthorWorks, renderAuthorsPagination, loadSeriesView, renderSeriesView, loadSeriesWorks, renderSeriesPagination });
