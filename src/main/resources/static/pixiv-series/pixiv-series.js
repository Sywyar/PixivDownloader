    const HEART_SVG = '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z"/></svg>';
    const SIDEBAR_STATE_STORAGE_KEY = 'pixiv:gallery-sidebar-state';

    const state = {
        type: 'artwork',
        seriesId: null,
        currentId: null,
        initialSeriesTitle: '',
        detail: null,
        page: 0,
        size: 60,
        totalPages: 0,
        totalElements: 0,
        search: '',
        items: [],
        collections: [],
        autoRefreshTried: false,
        refreshing: false,
    };

    function readStoredCookie() {
        try {
            const raw = localStorage.getItem('pixiv_cookie');
            if (!raw) return '';
            const fmt = localStorage.getItem('pixiv_cookie_fmt') || 'header';
            if (fmt === 'json') {
                const obj = JSON.parse(raw);
                return Object.entries(obj).map(([k, v]) => `${k}=${v}`).join('; ');
            }
            if (fmt === 'netscape') {
                return raw.split('\n')
                    .filter(l => l.trim() && !l.trim().startsWith('#'))
                    .map(l => {
                        const p = l.split('\t');
                        return p.length >= 7 ? `${p[5]}=${p[6].trim()}` : null;
                    })
                    .filter(Boolean)
                    .join('; ');
            }
            return raw.trim();
        } catch (_) {
            return '';
        }
    }

    let pageI18n = null;
    let searchTimer = null;

    const ImageCache = (() => {
        const PREFIX = 'pxImg:';
        const mem = new Map();

        function get(url) {
            if (mem.has(url)) return mem.get(url);
            try {
                const cached = sessionStorage.getItem(PREFIX + url);
                if (cached) {
                    mem.set(url, cached);
                    return cached;
                }
            } catch (_) {
            }
            return null;
        }

        function put(url, dataUri) {
            mem.set(url, dataUri);
            try {
                sessionStorage.setItem(PREFIX + url, dataUri);
            } catch (_) {
            }
        }

        return {get, put};
    })();

    function interpolate(template, vars) {
        if (!vars) return String(template);
        return String(template).replace(/\{([a-zA-Z0-9_.-]+)\}/g, (match, name) => {
            return Object.prototype.hasOwnProperty.call(vars, name) ? String(vars[name]) : match;
        });
    }

    function t(key, fallback, vars) {
        if (pageI18n) {
            return pageI18n.t('series:' + key, fallback, vars);
        }
        return interpolate(fallback != null ? fallback : key, vars);
    }

    function isNovelMode() {
        return state.type === 'novel';
    }

    function modeKey(key) {
        return isNovelMode() ? `${key}-novel` : key;
    }

    function modeText(key, artworkFallback, novelFallback, vars) {
        return t(modeKey(key), isNovelMode() ? novelFallback : artworkFallback, vars);
    }

    function pageText(key, fallback, vars) {
        if (pageI18n) {
            return pageI18n.t(key, fallback, vars);
        }
        return interpolate(fallback != null ? fallback : key, vars);
    }

    function applyStaticPageTranslations() {
        document.title = state.detail && state.detail.title
            ? modeText('page.title-with-name', '{title} - Manga Series', '{title} - Novel Series', {title: state.detail.title})
            : modeText('page.title', 'Manga Series Directory', 'Novel Series Directory');
        if (pageI18n) {
            pageI18n.apply(document.body);
        }
        applyModeTranslations();
        renderSeriesHeader();
        renderMeta();
        renderStatus();
        renderGrid(state.items);
        renderPagination();
    }

    function applyModeTranslations() {
        const setText = (selector, text) => {
            const el = document.querySelector(selector);
            if (el) el.textContent = text;
        };
        const setHref = (selector, href) => {
            const el = document.querySelector(selector);
            if (el) el.href = href;
        };
        const galleryHref = isNovelMode() ? '/pixiv-novel-gallery.html' : '/pixiv-gallery.html';
        const galleryAllHref = `${galleryHref}?view=all`;
        setText('#navViewAll .nav-label', isNovelMode()
            ? pageText('novel:nav.all', 'All novels')
            : pageText('gallery:nav.all', 'All Artworks'));
        setText('#navViewAuthors .nav-label', isNovelMode()
            ? pageText('novel:nav.by-author', 'By author')
            : pageText('gallery:nav.authors', 'Browse by Author'));
        setText('#navViewSeries .nav-label', isNovelMode()
            ? pageText('novel:nav.by-series', 'By series')
            : pageText('gallery:nav.series', 'Browse by Manga Series'));
        setText('#navGalleryHome .nav-label', isNovelMode()
            ? pageText('novel:gallery.type.novel', 'Novels')
            : pageText('gallery:nav.gallery', 'Gallery'));
        setHref('#navViewAll', galleryAllHref);
        setHref('#navViewAuthors', `${galleryHref}?view=authors`);
        setHref('#navViewSeries', `${galleryHref}?view=series`);
        setHref('#navGalleryHome', galleryAllHref);
        setHref('#btnCreateCollection', `${galleryAllHref}&createCollection=1`);
        setText('.series-kicker', modeText('page.kicker', 'Manga Series Directory', 'Novel Series Directory'));
        setText('.nav-item.current .nav-label', modeText('nav.series-directory', 'Manga Series Directory', 'Novel Series Directory'));
        setText('#backToArtworkBtn span', modeText('button.back-to-artwork', 'Back to Current Artwork', 'Back to Current Novel'));
        setText('#openGalleryFilterBtn span', modeText('button.open-gallery-filter', 'Filter in Gallery', 'Filter in Novel Gallery'));
        const searchInput = document.getElementById('searchInput');
        if (searchInput) {
            searchInput.placeholder = modeText('search.placeholder', 'Search this series...', 'Search this novel series...');
        }
        renderCollections();
    }

    async function initPageI18n() {
        pageI18n = await PixivI18n.create({namespaces: ['series', 'gallery', 'novel', 'common']});
        await PixivLangSwitcher.mount({
            mountPoint: document.getElementById('langSwitcherAnchor'),
            i18n: pageI18n,
            onChange: function (nextClient) {
                pageI18n = nextClient;
                applyStaticPageTranslations();
            }
        });
        PixivTheme.mount({
            mountPoint: document.getElementById('langSwitcherAnchor')
        });
        applyStaticPageTranslations();
    }

    async function api(url, options = {}) {
        const res = await fetch(url, {
            credentials: 'same-origin',
            ...options,
            headers: {
                'Accept': 'application/json',
                ...(options.headers || {}),
            },
        });
        if (res.status === 401) {
            window.location.href = '/login.html?redirect=' + encodeURIComponent(location.pathname + location.search);
            throw new Error('Unauthorized');
        }
        if (!res.ok) {
            let msg = `HTTP ${res.status}`;
            try {
                const j = await res.json();
                if (j && j.error) msg = j.error;
                else if (j && j.message) msg = j.message;
            } catch (_) {
            }
            throw new Error(msg);
        }
        const ct = res.headers.get('content-type') || '';
        if (ct.includes('application/json')) return res.json();
        return res.text();
    }

    function readParams() {
        const params = new URLSearchParams(location.search);
        state.type = params.get('type') === 'novel' ? 'novel' : 'artwork';
        const seriesId = Number(params.get('seriesId') || params.get('id'));
        const currentId = Number(params.get('currentId') || params.get('novelId') || params.get('artworkId'));
        const order = Number(params.get('order'));
        state.seriesId = Number.isFinite(seriesId) && seriesId > 0 ? seriesId : null;
        state.currentId = Number.isFinite(currentId) && currentId > 0 ? currentId : null;
        state.initialSeriesTitle = params.get('seriesTitle') || '';
        if (Number.isFinite(order) && order > 0) {
            state.page = Math.floor((order - 1) / state.size);
        }
    }

    async function setupAdminMode() {
        try {
            const res = await fetch('/api/admin/invites/access-check', {credentials: 'same-origin'});
            if (res.ok) document.body.classList.add('admin-mode');
        } catch (_) {
        }
    }

    async function loadCollections() {
        try {
            const resp = await api('/api/collections');
            state.collections = resp.collections || [];
            renderCollections();
        } catch (e) {
            console.warn('load collections failed', e);
        }
    }

    function renderCollections() {
        const list = document.getElementById('collectionList');
        if (!list) return;
        if (!state.collections.length) {
            list.innerHTML = '<div class="collection-empty">' +
                escapeHtml(pageText('gallery:status.no-collections', 'No collections')) +
                '</div>';
            return;
        }
        list.innerHTML = state.collections.map(collection => {
            const count = isNovelMode()
                ? (collection.novelCount ?? 0)
                : (collection.artworkCount ?? 0);
            return `
                <a class="collection-item" href="${escapeHtml(buildCollectionHref(collection.id))}">
                    <div class="collection-icon">${iconHtml(collection)}</div>
                    <span class="collection-label">${escapeHtml(collection.name)}</span>
                    <span class="collection-count">${escapeHtml(count)}</span>
                </a>
            `;
        }).join('');
    }

    function iconHtml(collection) {
        if (collection.iconExt) {
            return `<img src="/api/collections/${collection.id}/icon?v=${encodeURIComponent(collection.createdTime || '')}" alt="">`;
        }
        return HEART_SVG;
    }

    function buildCollectionHref(collectionId) {
        const params = new URLSearchParams();
        params.set('view', 'all');
        params.set('collectionIds', String(collectionId));
        return (isNovelMode() ? '/pixiv-novel-gallery.html' : '/pixiv-gallery.html') + '?' + params.toString();
    }

    async function loadSeries() {
        if (!state.seriesId) {
            document.getElementById('seriesTitle').textContent = t('status.missing-id', 'Missing series id');
            document.getElementById('seriesStatus').textContent = modeText(
                'status.missing-id-hint',
                'Open this page from an artwork in a manga series.',
                'Open this page from a novel in a novel series.'
            );
            return;
        }
        renderLoading();
        if (isNovelMode()) {
            state.detail = buildNovelDetail();
            // 同步拉取小说系列封面/简介（标题/作者由章节列表回填）。
            try {
                const series = await api(`/api/gallery/novel/series/${state.seriesId}`);
                if (series) {
                    state.detail = Object.assign({}, state.detail, {
                        title: series.title || state.detail.title,
                        authorId: series.authorId != null ? series.authorId : state.detail.authorId,
                        description: series.description,
                        coverExt: series.coverExt,
                    });
                }
            } catch (_) {
                // 404 表示尚未观测过该系列：保留章节回填出的字段即可。
            }
            renderSeriesHeader();
            renderMeta();
            await loadArtworks();
            return;
        }
        try {
            const detail = await api(`/api/series/${state.seriesId}`);
            state.detail = detail;
            renderSeriesHeader();
            renderMeta();
            await loadArtworks();
        } catch (e) {
            document.getElementById('seriesTitle').textContent = modeText(
                'status.load-failed-title',
                'Unable to load series',
                'Unable to load novel series'
            );
            document.getElementById('seriesStatus').textContent = t('status.load-failed', 'Load failed: {message}', {message: e.message});
            document.getElementById('chapterGrid').innerHTML = '';
            document.getElementById('pagination').innerHTML = '';
        }
    }

    async function loadArtworks() {
        if (isNovelMode()) {
            await loadNovels();
            return;
        }
        const params = new URLSearchParams();
        params.set('page', String(state.page));
        params.set('size', String(state.size));
        params.set('sort', 'series');
        params.set('order', 'asc');
        params.set('seriesId', String(state.seriesId));
        if (state.search) params.set('search', state.search);
        try {
            document.getElementById('seriesStatus').textContent = t('status.loading-artworks', 'Loading chapters...');
            const resp = await api('/api/gallery/artworks?' + params.toString());
            state.items = resp.content || [];
            state.totalPages = resp.totalPages || 0;
            state.totalElements = resp.totalElements || 0;
            if (!state.items.length && state.totalElements > 0 && state.page > 0) {
                state.page = 0;
                await loadArtworks();
                return;
            }
            renderMeta();
            renderStatus();
            renderGrid(state.items);
            renderPagination();
        } catch (e) {
            document.getElementById('seriesStatus').textContent = t('status.load-failed', 'Load failed: {message}', {message: e.message});
            document.getElementById('chapterGrid').innerHTML = '';
            document.getElementById('pagination').innerHTML = '';
        }
    }

    async function loadNovels() {
        const params = new URLSearchParams();
        params.set('page', String(state.page));
        params.set('size', String(state.size));
        params.set('sort', 'series');
        params.set('order', 'asc');
        params.set('seriesId', String(state.seriesId));
        if (state.search) params.set('search', state.search);
        try {
            document.getElementById('seriesStatus').textContent = t('status.loading-novels', 'Loading novels...');
            const resp = await api('/api/gallery/novels?' + params.toString());
            state.items = resp.content || [];
            state.totalPages = resp.totalPages || 0;
            state.totalElements = resp.totalElements || 0;
            if (!state.items.length && state.totalElements > 0 && state.page > 0) {
                state.page = 0;
                await loadNovels();
                return;
            }
            state.detail = buildNovelDetail();
            renderSeriesHeader();
            renderMeta();
            renderStatus();
            renderGrid(state.items);
            renderPagination();
        } catch (e) {
            document.getElementById('seriesStatus').textContent = t('status.load-failed', 'Load failed: {message}', {message: e.message});
            document.getElementById('chapterGrid').innerHTML = '';
            document.getElementById('pagination').innerHTML = '';
        }
    }

    function buildNovelDetail() {
        const first = state.items.find(item => Number(item.seriesId) === Number(state.seriesId)) || state.items[0] || {};
        const updatedTime = state.items.reduce((max, item) => Math.max(max, Number(item.time || 0)), 0);
        const previous = state.detail || {};
        return {
            seriesId: state.seriesId,
            title: previous.title || state.initialSeriesTitle
                || modeText('series.default', 'Series #{id}', 'Series #{id}', {id: state.seriesId}),
            authorId: previous.authorId != null ? previous.authorId : (first.authorId || null),
            authorName: previous.authorName || first.authorName || '',
            novelCount: state.totalElements || state.items.length || 0,
            updatedTime: updatedTime || previous.updatedTime || null,
            description: previous.description,
            coverExt: previous.coverExt,
        };
    }

    function renderLoading() {
        document.getElementById('seriesTitle').textContent = t('status.loading', 'Loading...');
        document.getElementById('seriesStatus').textContent = t('status.loading', 'Loading...');
        document.getElementById('chapterGrid').innerHTML = '';
        document.getElementById('pagination').innerHTML = '';
        document.getElementById('metaStrip').innerHTML = '';
    }

    function renderSeriesHeader() {
        const titleEl = document.getElementById('seriesTitle');
        const authorEl = document.getElementById('seriesAuthor');
        const authorNameEl = document.getElementById('seriesAuthorName');
        const galleryBtn = document.getElementById('openGalleryFilterBtn');
        const backBtn = document.getElementById('backToArtworkBtn');
        const detail = state.detail;

        if (!detail) {
            titleEl.textContent = state.seriesId
                ? modeText('series.default', 'Series #{id}', 'Series #{id}', {id: state.seriesId})
                : '';
            authorEl.style.display = 'none';
            galleryBtn.href = (isNovelMode() ? '/pixiv-novel-gallery.html' : '/pixiv-gallery.html') + '?view=all';
            renderCover();
            renderDescription();
            renderRefreshButton();
            return;
        }

        const title = detail.title || modeText('series.default', 'Series #{id}', 'Series #{id}', {id: detail.seriesId});
        titleEl.textContent = title;
        document.title = modeText('page.title-with-name', '{title} - Manga Series', '{title} - Novel Series', {title});
        galleryBtn.href = isNovelMode()
            ? buildNovelGalleryFilterHref({seriesId: detail.seriesId, seriesTitle: title})
            : buildGalleryFilterHref({seriesId: detail.seriesId, seriesTitle: title});

        if (state.currentId) {
            backBtn.href = isNovelMode()
                ? `/pixiv-novel.html?id=${state.currentId}`
                : `/pixiv-artwork.html?id=${state.currentId}`;
            backBtn.style.display = 'inline-flex';
        } else {
            backBtn.style.display = 'none';
        }

        if (detail.authorId || detail.authorName) {
            const name = detail.authorName || t('author.default', 'Author {id}', {id: detail.authorId});
            authorNameEl.textContent = detail.authorId
                ? t('author.with-id', '{name} · #{id}', {name, id: detail.authorId})
                : name;
            if (detail.authorId) {
                authorEl.href = isNovelMode()
                    ? buildPixivAuthorHref(detail.authorId)
                    : buildPixivSeriesHref(detail.authorId, detail.seriesId);
            } else {
                authorEl.removeAttribute('href');
            }
            authorEl.style.display = 'inline-flex';
        } else {
            authorEl.style.display = 'none';
        }

        renderCover();
        renderDescription();
        renderRefreshButton();
        maybeAutoRefresh();
    }

    function renderCover() {
        const wrap = document.getElementById('seriesCover');
        const img = document.getElementById('seriesCoverImg');
        if (!wrap || !img) return;
        const detail = state.detail;
        if (!detail || !detail.coverExt) {
            wrap.style.display = 'none';
            return;
        }
        const url = isNovelMode()
            ? `/api/gallery/novel/series/${detail.seriesId}/cover`
            : `/api/series/${detail.seriesId}/cover`;
        img.alt = detail.title || '';
        img.src = url + '?v=' + encodeURIComponent(detail.coverExt);
        wrap.style.display = 'block';
    }

    function renderDescription() {
        const el = document.getElementById('seriesDescription');
        if (!el) return;
        const text = state.detail ? state.detail.description : null;
        if (!text || !String(text).trim()) {
            el.style.display = 'none';
            el.innerHTML = '';
            return;
        }
        // Pixiv 简介经过后端 PixivDescriptionHtml.normalizeLinks 仅保留 <br>/<a>，可直接 innerHTML。
        el.innerHTML = text;
        el.querySelectorAll('a').forEach(a => {
            a.target = '_blank';
            a.rel = 'noopener noreferrer';
        });
        el.style.display = 'block';
    }

    function renderRefreshButton() {
        const btn = document.getElementById('refreshMetaBtn');
        if (!btn) return;
        // 仅在已知 seriesId 时展示；guest 模式下后端会 403，由 maybeAutoRefresh 静默处理。
        btn.style.display = state.seriesId ? 'inline-flex' : 'none';
        btn.disabled = state.refreshing;
        const label = document.getElementById('refreshMetaLabel');
        if (label) {
            label.textContent = state.refreshing
                ? t('button.refresh-meta-loading', 'Refreshing...')
                : t('button.refresh-meta', 'Refresh from Pixiv');
        }
    }

    function maybeAutoRefresh() {
        if (state.autoRefreshTried || state.refreshing) return;
        const detail = state.detail;
        if (!detail || !state.seriesId) return;
        // 仅在本地既无封面也无简介时尝试一次自动刷新，避免反复打扰 Pixiv。
        const hasCover = !!detail.coverExt;
        const hasDescription = detail.description && String(detail.description).trim();
        if (hasCover || hasDescription) {
            state.autoRefreshTried = true;
            return;
        }
        const cookie = readStoredCookie();
        if (!cookie) {
            state.autoRefreshTried = true;
            return;
        }
        state.autoRefreshTried = true;
        refreshSeriesMeta({silent: true});
    }

    async function refreshSeriesMeta(opts) {
        if (!state.seriesId || state.refreshing) return;
        const silent = opts && opts.silent;
        const url = isNovelMode()
            ? `/api/gallery/novel/series/${state.seriesId}/refresh`
            : `/api/series/${state.seriesId}/refresh`;
        const headers = {Accept: 'application/json'};
        const cookie = readStoredCookie();
        if (cookie) headers['X-Pixiv-Cookie'] = cookie;
        state.refreshing = true;
        renderRefreshButton();
        try {
            const res = await fetch(url, {
                method: 'POST',
                credentials: 'same-origin',
                headers,
            });
            if (!res.ok) {
                if (!silent) {
                    document.getElementById('seriesStatus').textContent = t(
                        'status.refresh-failed',
                        'Refresh failed: HTTP {status}',
                        {status: res.status}
                    );
                }
                return;
            }
            const updated = await res.json();
            if (!updated) return;
            // 合并到 detail，保留章节数、作者名等 detail 独有字段
            state.detail = Object.assign({}, state.detail || {}, {
                description: updated.description,
                coverExt: updated.coverExt,
                title: updated.title || (state.detail && state.detail.title),
                authorId: updated.authorId != null ? updated.authorId : (state.detail && state.detail.authorId),
            });
            renderSeriesHeader();
        } catch (e) {
            if (!silent) {
                document.getElementById('seriesStatus').textContent = t(
                    'status.refresh-failed',
                    'Refresh failed: {message}',
                    {message: e.message}
                );
            }
        } finally {
            state.refreshing = false;
            renderRefreshButton();
        }
    }

    function renderMeta() {
        const strip = document.getElementById('metaStrip');
        const detail = state.detail;
        if (!detail) {
            strip.innerHTML = '';
            return;
        }
        const chips = [
            metaChip('id', t('meta.series-id', 'Series ID: {id}', {id: detail.seriesId}), 'M4 19.5A2.5 2.5 0 0 1 6.5 17H20 M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z'),
            metaChip('count', modeText(
                'meta.local-count',
                '{count} downloaded chapters',
                '{count} downloaded novels',
                {count: isNovelMode() ? (detail.novelCount || 0) : (detail.artworkCount || 0)}
            ), 'M3 6h18 M3 12h18 M3 18h18'),
        ];
        if (state.totalElements) {
            chips.push(metaChip('shown', modeText(
                'meta.filtered-count',
                '{count} matching chapters',
                '{count} matching novels',
                {count: state.totalElements}
            ), 'M22 3H2l8 9.46V19l4 2v-8.54L22 3z'));
        }
        if (detail.updatedTime) {
            chips.push(metaChip('updated', t('meta.updated', 'Updated {time}', {time: formatTime(detail.updatedTime)}), 'M12 8v4l3 3 M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0z'));
        }
        strip.innerHTML = chips.join('');
    }

    function metaChip(type, text, path) {
        return `
            <span class="meta-chip" data-type="${escapeHtml(type)}">
                <svg viewBox="0 0 24 24"><path d="${escapeHtml(path)}"/></svg>
                ${escapeHtml(text)}
            </span>
        `;
    }

    function renderStatus() {
        const status = document.getElementById('seriesStatus');
        if (!state.seriesId) return;
        if (!state.totalElements) {
            status.textContent = state.search
                ? modeText('status.no-matching-artworks', 'No matching chapters', 'No matching novels')
                : modeText('status.no-artworks', 'No downloaded chapters in this series', 'No downloaded novels in this series');
            return;
        }
        const from = state.page * state.size + 1;
        const to = Math.min((state.page + 1) * state.size, state.totalElements);
        status.textContent = modeText('status.range', '{total} chapters, {from}-{to} shown', '{total} novels, {from}-{to} shown', {
            total: state.totalElements,
            from,
            to,
        });
    }

    function renderGrid(items) {
        const grid = document.getElementById('chapterGrid');
        if (!state.seriesId) return;
        if (isNovelMode()) {
            renderNovelGrid(items, grid);
            return;
        }
        if (!items.length) {
            grid.innerHTML = '<div class="empty-state" style="grid-column:1/-1">' +
                escapeHtml(state.search
                    ? t('status.no-matching-artworks', 'No matching chapters')
                    : t('status.no-artworks', 'No downloaded chapters in this series')) +
                '</div>';
            return;
        }
        grid.innerHTML = items.map(item => {
            const xRestrict = item.xRestrict;
            const isR18 = xRestrict === 1;
            const isR18G = xRestrict === 2;
            const isAi = item.isAi === true;
            const pages = item.count || 1;
            const isCurrent = state.currentId && String(item.artworkId) === String(state.currentId);
            return `
                <a class="chapter-card ${isCurrent ? 'current' : ''}" href="/pixiv-artwork.html?id=${item.artworkId}" data-id="${item.artworkId}">
                    <div class="chapter-thumb">
                        <img data-src="/api/downloaded/thumbnail/${item.artworkId}/0" alt="${escapeHtml(item.title || '')}" loading="lazy">
                        <div class="thumb-veil"></div>
                        <div class="thumb-badges">
                            <div class="thumb-badge-group">
                                ${item.seriesOrder ? `<span class="thumb-badge current">#${escapeHtml(item.seriesOrder)}</span>` : ''}
                                ${isCurrent ? `<span class="thumb-badge current">${escapeHtml(t('chapter.current', 'Current'))}</span>` : ''}
                                ${isR18G ? '<span class="thumb-badge r18g">R-18G</span>' : isR18 ? '<span class="thumb-badge r18">R-18</span>' : ''}
                                ${isAi ? '<span class="thumb-badge ai">AI</span>' : ''}
                            </div>
                            ${pages > 1 ? `<span class="thumb-badge pages">${pages}P</span>` : ''}
                        </div>
                    </div>
                    <div class="chapter-meta">
                        <div class="chapter-title-row">
                            ${item.seriesOrder ? `<span class="chapter-order">#${escapeHtml(item.seriesOrder)}</span>` : ''}
                            <div class="chapter-title">${escapeHtml(item.title || t('status.untitled', 'Untitled'))}</div>
                        </div>
                        <div class="chapter-sub">${escapeHtml(chapterSubText(item))}</div>
                    </div>
                </a>
            `;
        }).join('');
        grid.querySelectorAll('img[data-src]').forEach(img => loadThumbnail(img));
        if (state.currentId) {
            requestAnimationFrame(() => {
                const current = grid.querySelector('.chapter-card.current');
                if (current) {
                    current.scrollIntoView({block: 'nearest'});
                }
            });
        }
    }

    function renderNovelGrid(items, grid) {
        if (!items.length) {
            grid.innerHTML = '<div class="empty-state" style="grid-column:1/-1">' +
                escapeHtml(state.search
                    ? t('status.no-matching-artworks-novel', 'No matching novels')
                    : t('status.no-artworks-novel', 'No downloaded novels in this series')) +
                '</div>';
            return;
        }
        grid.innerHTML = items.map(item => {
            const xRestrict = item.xRestrict;
            const isR18 = xRestrict === 1;
            const isR18G = xRestrict === 2;
            const isAi = item.isAi === true;
            const isOriginal = item.isOriginal === true;
            const isCurrent = state.currentId && String(item.novelId) === String(state.currentId);
            const title = item.title || t('status.untitled', 'Untitled');
            const cover = item.coverExt
                ? `<img src="/api/gallery/novel/${item.novelId}/cover" alt="${escapeHtml(title)}" loading="lazy" onerror="this.replaceWith(Object.assign(document.createElement('div'),{className:'chapter-cover-placeholder',textContent:this.alt}))">`
                : `<div class="chapter-cover-placeholder">${escapeHtml(title)}</div>`;
            return `
                <a class="chapter-card novel ${isCurrent ? 'current' : ''}" href="/pixiv-novel.html?id=${item.novelId}" data-id="${item.novelId}">
                    <div class="chapter-thumb">
                        ${cover}
                        <div class="thumb-veil"></div>
                        <div class="thumb-badges">
                            <div class="thumb-badge-group">
                                ${item.seriesOrder ? `<span class="thumb-badge current">#${escapeHtml(item.seriesOrder)}</span>` : ''}
                                ${isCurrent ? `<span class="thumb-badge current">${escapeHtml(t('chapter.current', 'Current'))}</span>` : ''}
                                ${isR18G ? '<span class="thumb-badge r18g">R-18G</span>' : isR18 ? '<span class="thumb-badge r18">R-18</span>' : ''}
                                ${isAi ? '<span class="thumb-badge ai">AI</span>' : ''}
                                ${isOriginal ? `<span class="thumb-badge pages">${escapeHtml(t('chapter.original', 'Original'))}</span>` : ''}
                            </div>
                        </div>
                    </div>
                    <div class="chapter-meta">
                        <div class="chapter-title-row">
                            ${item.seriesOrder ? `<span class="chapter-order">#${escapeHtml(item.seriesOrder)}</span>` : ''}
                            <div class="chapter-title">${escapeHtml(title)}</div>
                        </div>
                        <div class="chapter-sub">${escapeHtml(chapterSubText(item))}</div>
                    </div>
                </a>
            `;
        }).join('');
        if (state.currentId) {
            requestAnimationFrame(() => {
                const current = grid.querySelector('.chapter-card.current');
                if (current) {
                    current.scrollIntoView({block: 'nearest'});
                }
            });
        }
    }

    function chapterSubText(item) {
        const parts = [];
        if (isNovelMode()) {
            if (item.novelId) parts.push(t('chapter.id', 'ID: {id}', {id: item.novelId}));
            if (item.wordCount && item.wordCount > 0) parts.push(t('chapter.words', '{count} words', {count: item.wordCount}));
            else if (item.textLength && item.textLength > 0) parts.push(t('chapter.text-length', '{count} characters', {count: item.textLength}));
            const readingText = formatReadingTime(item.readingTimeSeconds);
            if (readingText) parts.push(readingText);
            if (item.xLanguage) parts.push(item.xLanguage);
            return parts.join(' · ');
        }
        if (item.artworkId) parts.push(t('chapter.id', 'ID: {id}', {id: item.artworkId}));
        if (item.count) parts.push(t('chapter.pages', '{count} pages', {count: item.count}));
        return parts.join(' · ');
    }

    function formatReadingDuration(seconds) {
        const total = Math.floor(Number(seconds || 0));
        if (!Number.isFinite(total) || total <= 0) return '';
        const hour = Math.floor(total / 3600);
        const minute = Math.floor((total % 3600) / 60);
        const second = total % 60;
        const parts = [];
        if (hour > 0) parts.push(t('duration.hour', '{count} h', {count: hour}));
        if (minute > 0) parts.push(t('duration.minute', '{count} min', {count: minute}));
        if (second > 0 && hour === 0) parts.push(t('duration.second', '{count} sec', {count: second}));
        if (!parts.length) parts.push(t('duration.second', '{count} sec', {count: total}));
        return parts.join(' ');
    }

    function formatReadingTime(seconds) {
        const text = formatReadingDuration(seconds);
        return text ? t('chapter.reading-time', 'Reading: {time}', {time: text}) : '';
    }

    async function loadThumbnail(img) {
        const url = img.dataset.src;
        img.removeAttribute('data-src');
        const cached = ImageCache.get(url);
        if (cached) {
            img.src = cached;
            return;
        }
        try {
            const resp = await api(url);
            if (resp && resp.success && resp.image) {
                const ext = (resp.extension || 'jpg').toLowerCase();
                const src = `data:image/${ext === 'jpg' ? 'jpeg' : ext};base64,${resp.image}`;
                img.src = src;
                ImageCache.put(url, src);
            }
        } catch (_) {
        }
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
            if (p === '...') parts.push('<span class="page-ellipsis">...</span>');
            else parts.push(`<button class="page-btn ${p === state.page ? 'active' : ''}" data-page="${p}">${p + 1}</button>`);
        }
        parts.push(`<button class="page-btn" data-page="${state.page + 1}" ${state.page >= state.totalPages - 1 ? 'disabled' : ''}>${escapeHtml(t('pagination.next', 'Next'))}</button>`);
        pag.innerHTML = parts.join('');
        pag.querySelectorAll('.page-btn').forEach(btn => {
            btn.addEventListener('click', () => {
                if (btn.disabled) return;
                const p = Number(btn.dataset.page);
                if (p < 0 || p >= state.totalPages) return;
                state.page = p;
                loadArtworks();
                document.querySelector('.series-wrapper').scrollTop = 0;
                window.scrollTo({top: 0, behavior: 'smooth'});
            });
        });
    }

    function buildPageWindow(current, total) {
        const windowSet = new Set([0, total - 1, current, current - 1, current + 1]);
        const pages = [...windowSet].filter(n => n >= 0 && n < total).sort((a, b) => a - b);
        const out = [];
        for (let i = 0; i < pages.length; i++) {
            if (i > 0 && pages[i] - pages[i - 1] > 1) out.push('...');
            out.push(pages[i]);
        }
        return out;
    }

    function buildGalleryFilterHref({seriesId, seriesTitle, authorId, authorName} = {}) {
        const params = new URLSearchParams();
        params.set('view', 'all');
        if (seriesId != null) params.set('filterSeriesId', String(seriesId));
        if (seriesTitle) params.set('filterSeriesTitle', seriesTitle);
        if (authorId != null) params.set('filterAuthorId', String(authorId));
        if (authorName) params.set('filterAuthorName', authorName);
        return '/pixiv-gallery.html' + (params.toString() ? '?' + params.toString() : '');
    }

    function buildNovelGalleryFilterHref({seriesId, seriesTitle} = {}) {
        const params = new URLSearchParams();
        params.set('view', 'all');
        if (seriesId != null) params.set('seriesId', String(seriesId));
        if (seriesTitle) params.set('seriesTitle', seriesTitle);
        return '/pixiv-novel-gallery.html' + (params.toString() ? '?' + params.toString() : '');
    }

    function buildPixivSeriesHref(authorId, seriesId) {
        return `https://www.pixiv.net/user/${authorId}/series/${seriesId}`;
    }

    function buildPixivAuthorHref(authorId) {
        return `https://www.pixiv.net/users/${authorId}`;
    }

    function formatTime(epochMillis) {
        if (!epochMillis) return '';
        const date = new Date(Number(epochMillis));
        if (Number.isNaN(date.getTime())) return '';
        const locale = pageI18n ? pageI18n.lang : undefined;
        return new Intl.DateTimeFormat(locale, {
            year: 'numeric',
            month: 'short',
            day: 'numeric',
        }).format(date);
    }

    function escapeHtml(s) {
        if (s == null) return '';
        return String(s).replace(/[&<>"']/g, m => ({
            '&': '&amp;',
            '<': '&lt;',
            '>': '&gt;',
            '"': '&quot;',
            "'": '&#39;'
        }[m]));
    }

    function restoreSidebarState() {
        const sidebar = document.getElementById('sidebar');
        if (!sidebar) return;
        let savedState = null;
        try {
            savedState = localStorage.getItem(SIDEBAR_STATE_STORAGE_KEY);
        } catch (_) {
        }
        sidebar.classList.toggle('collapsed', savedState === 'closed');
    }

    function saveSidebarState(collapsed) {
        try {
            localStorage.setItem(SIDEBAR_STATE_STORAGE_KEY, collapsed ? 'closed' : 'open');
        } catch (_) {
        }
    }

    function toggleSidebar() {
        const sidebar = document.getElementById('sidebar');
        if (!sidebar) return;
        const collapsed = sidebar.classList.toggle('collapsed');
        saveSidebarState(collapsed);
    }

    function openMobileSidebar() {
        document.getElementById('sidebar').classList.add('mobile-open');
        document.getElementById('mobileOverlay').classList.add('active');
    }

    function closeMobileSidebar() {
        document.getElementById('sidebar').classList.remove('mobile-open');
        document.getElementById('mobileOverlay').classList.remove('active');
    }

    document.getElementById('sidebarToggle').addEventListener('click', toggleSidebar);
    document.getElementById('mobileMenuBtn').addEventListener('click', openMobileSidebar);
    document.getElementById('mobileOverlay').addEventListener('click', closeMobileSidebar);
    const refreshMetaBtn = document.getElementById('refreshMetaBtn');
    if (refreshMetaBtn) {
        refreshMetaBtn.addEventListener('click', () => refreshSeriesMeta({silent: false}));
    }
    document.getElementById('searchInput').addEventListener('input', e => {
        clearTimeout(searchTimer);
        searchTimer = setTimeout(() => {
            state.search = e.target.value.trim();
            state.page = 0;
            loadArtworks();
        }, 220);
    });

    (async function init() {
        readParams();
        restoreSidebarState();
        setupAdminMode();
        await initPageI18n();
        loadCollections();
        await loadSeries();
    })();
