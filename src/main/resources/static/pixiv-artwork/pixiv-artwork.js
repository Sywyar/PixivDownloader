    const HEART_SVG = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"/></svg>';
    let pageI18n = null;

    function interpolate(template, vars) {
        if (!vars) {
            return String(template);
        }
        return String(template).replace(/\{([a-zA-Z0-9_.-]+)\}/g, (match, name) => {
            return Object.prototype.hasOwnProperty.call(vars, name) ? String(vars[name]) : match;
        });
    }

    function wt(key, fallback, vars) {
        if (pageI18n) {
            return pageI18n.t('artwork:' + key, fallback, vars);
        }
        return interpolate(fallback != null ? fallback : key, vars);
    }

    function syncExpandButtonText() {
        if (!state.artwork) return;
        const count = state.artwork.count || 1;
        const expandBtn = document.getElementById('expandBtn');
        const collapseBtn = document.getElementById('collapseBtn');
        expandBtn.innerHTML = wt('button.expand', 'Expand All ({count})', {count: '<span id="totalPageCount">' + count + '</span>'});
        collapseBtn.textContent = wt('button.collapse', 'Collapse');
    }

    function applyStaticPageTranslations() {
        document.title = state.artwork
            ? `${state.artwork.title || wt('status.unknown-artwork', 'Artwork {id}', {id: state.artwork.artworkId})} - Pixiv Gallery`
            : wt('page.title', 'Artwork Detail - Pixiv Gallery');
        document.getElementById('backBtnLabel').textContent = wt('button.back', 'Back');
        document.getElementById('galleryBtnLabel').textContent = wt('button.gallery', 'Gallery');
        const deleteBtn = document.getElementById('deleteArtworkBtn');
        if (deleteBtn) {
            document.getElementById('deleteBtnLabel').textContent = wt('button.delete', 'Delete');
            deleteBtn.title = wt('button.delete', 'Delete');
        }
        document.getElementById('deleteArtworkTitle').textContent = wt('delete.title', 'Delete Artwork');
        document.getElementById('deleteArtworkMessage').textContent = wt('delete.message', 'Delete this artwork? Its image files and download record will be permanently removed and cannot be recovered.');
        document.getElementById('deleteArtworkCancel').textContent = wt('delete.cancel', 'Cancel');
        document.getElementById('deleteArtworkConfirm').textContent = wt('delete.confirm', 'Delete');
        document.getElementById('viewerLoading').textContent = wt('status.loading', 'Loading...');
        document.getElementById('mainImage').setAttribute('data-loading-text', wt('status.loading', 'Loading...'));
        document.getElementById('pixivArtworkLinkLabel').textContent = wt('button.pixiv-artwork', 'Open Original on Pixiv');
        document.getElementById('showcaseLinkLabel').textContent = wt('button.showcase', '作品展示(娱乐性功能)');
        document.getElementById('detailTagsTitle').textContent = wt('panel.tags', 'Tags');
        document.getElementById('relatedPanelTitle').textContent = wt('panel.related', 'Related Artworks');
        document.getElementById('seriesPanelTitle').textContent = wt('panel.series', 'This Series');
        document.getElementById('seriesIndexBtn').textContent = wt('series.index', 'Directory');
        document.getElementById('seriesViewAllLink').textContent = wt('series.view-all', 'View All');
        document.getElementById('authorPanelTitle').textContent = wt('panel.author', 'Author');
        document.getElementById('byAuthorTitle').textContent = wt('panel.by-author', 'More from This Author');
        document.getElementById('addToCollectionTitle').textContent = wt('panel.add-to-collection', 'Add to Collection');
        document.getElementById('quickCreateName').placeholder = wt('placeholder.quick-create', 'Quick create a collection');
        document.getElementById('quickCreateBtn').textContent = wt('button.create', 'Create');
        document.getElementById('addToCollectionDoneBtn').textContent = wt('button.done', 'Done');
        document.getElementById('byAuthorPrev').title = wt('button.prev-group', 'Previous');
        document.getElementById('byAuthorNext').title = wt('button.next-group', 'Next');
        document.getElementById('bySeriesPrev').title = wt('button.prev-group', 'Previous');
        document.getElementById('bySeriesNext').title = wt('button.next-group', 'Next');
        document.getElementById('pixivAuthorLinkLabel').textContent = wt('author.link', 'Pixiv Profile ↗');
        syncExpandButtonText();
    }

    async function initPageI18n() {
        pageI18n = await PixivI18n.create({namespaces: ['artwork', 'common', 'tour']});
        await PixivLangSwitcher.mount({
            mountPoint: document.getElementById('langSwitcherAnchor'),
            i18n: pageI18n,
            onChange: function (nextClient) {
                pageI18n = nextClient;
                applyStaticPageTranslations();
                if (state.artwork) {
                    renderDetail();
                    renderAuthor();
                    loadSeriesSections();
                    updateHeart();
                }
            }
        });
        PixivTheme.mount({
            mountPoint: document.getElementById('langSwitcherAnchor')
        });
        applyStaticPageTranslations();
    }

    // ---------- Image cache (in-memory + sessionStorage for thumbs) ----------
    const ImageCache = (() => {
        const PREFIX = 'pxImg:';
        const mem = new Map();
        const isThumb = url => url.includes('/thumbnail/');

        function get(url) {
            if (mem.has(url)) return mem.get(url);
            if (isThumb(url)) {
                try {
                    const v = sessionStorage.getItem(PREFIX + url);
                    if (v) {
                        mem.set(url, v);
                        return v;
                    }
                } catch (_) {
                }
            }
            return null;
        }

        function put(url, dataUri) {
            mem.set(url, dataUri);
            if (!isThumb(url)) return;
            try {
                sessionStorage.setItem(PREFIX + url, dataUri);
            } catch (_) {
                const keys = [];
                for (let i = 0; i < sessionStorage.length; i++) {
                    const k = sessionStorage.key(i);
                    if (k && k.startsWith(PREFIX)) keys.push(k);
                }
                keys.slice(0, Math.max(1, Math.floor(keys.length / 2)))
                    .forEach(k => {
                        try {
                            sessionStorage.removeItem(k);
                        } catch (_) {
                        }
                    });
                try {
                    sessionStorage.setItem(PREFIX + url, dataUri);
                } catch (_) {
                }
            }
        }

        return {get, put};
    })();

    const state = {
        artworkId: null,
        artwork: null,
        collections: [],
        collectionMembership: new Set(),
        lightboxIndex: 0,
        lightboxImages: [],
        seriesNav: null,
    };

    function getQueryParam(name) {
        return new URLSearchParams(location.search).get(name);
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
            } catch (_) {
            }
            throw new Error(msg);
        }
        const ct = res.headers.get('content-type') || '';
        if (ct.includes('application/json')) return res.json();
        return res.text();
    }

    function toast(msg, type = '') {
        const container = document.getElementById('toastContainer');
        const el = document.createElement('div');
        el.className = 'toast' + (type ? ' ' + type : '');
        el.textContent = msg;
        container.appendChild(el);
        setTimeout(() => el.remove(), 2800);
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

    function formatTime(ts) {
        if (!ts) return '';
        const d = new Date(ts);
        const pad = n => String(n).padStart(2, '0');
        return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
    }

    function buildGalleryFilterHref({tagId, tagName, tagTranslatedName, authorId, authorName, seriesId, seriesTitle} = {}) {
        const params = new URLSearchParams();
        params.set('view', 'all');
        if (tagId != null) params.set('filterTagId', String(tagId));
        if (tagName) params.set('filterTag', tagName);
        if (tagTranslatedName) params.set('filterTagTranslated', tagTranslatedName);
        if (authorId != null) params.set('filterAuthorId', String(authorId));
        if (authorName) params.set('filterAuthorName', authorName);
        if (seriesId != null) params.set('filterSeriesId', String(seriesId));
        if (seriesTitle) params.set('filterSeriesTitle', seriesTitle);
        return `/pixiv-gallery.html?${params.toString()}`;
    }

    function buildSeriesDirectoryHref(seriesId, currentId, currentOrder) {
        const params = new URLSearchParams();
        params.set('type', 'artwork');
        params.set('seriesId', String(seriesId));
        if (currentId != null) params.set('currentId', String(currentId));
        const order = numericSeriesOrder(currentOrder);
        if (order != null) params.set('order', String(order));
        return `/pixiv-series.html?${params.toString()}`;
    }

    function buildPixivArtworkHref(artworkId) {
        return `https://www.pixiv.net/artworks/${artworkId}`;
    }

    function buildShowcaseHref(artworkId) {
        return `/pixiv-showcase.html?id=${artworkId}`;
    }

    function buildPixivAuthorHref(authorId) {
        return `https://www.pixiv.net/users/${authorId}`;
    }

    async function loadImageToElement(url, target, {onClick} = {}) {
        const cached = ImageCache.get(url);
        if (cached) {
            const img = document.createElement('img');
            img.src = cached;
            img.alt = '';
            if (onClick) img.addEventListener('click', onClick);
            target.innerHTML = '';
            target.classList.remove('loading');
            target.appendChild(img);
            return cached;
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
                if (onClick) img.addEventListener('click', onClick);
                target.innerHTML = '';
                target.classList.remove('loading');
                target.appendChild(img);
                return src;
            }
            target.innerHTML = '<span style="color:var(--muted); padding:40px">' + escapeHtml(resp && resp.message ? resp.message : wt('status.image-unavailable', 'Image unavailable')) + '</span>';
        } catch (e) {
            target.innerHTML = '<span style="color:var(--muted); padding:40px">' + escapeHtml(wt('status.load-failed', 'Load failed')) + '</span>';
        }
        return null;
    }

    async function loadArtwork() {
        const id = getQueryParam('id');
        if (!id) {
            document.getElementById('viewerLoading').textContent = wt('status.missing-id', 'Missing id parameter');
            return;
        }
        state.artworkId = Number(id);
        try {
            state.artwork = await api(`/api/gallery/artwork/${id}`);
        } catch (e) {
            document.getElementById('viewerLoading').textContent = wt('status.artwork-load-failed', 'Load failed: {message}', {message: e.message});
            return;
        }
        document.getElementById('topbarTitle').textContent = `#${id}`;
        document.title = `${state.artwork.title || wt('status.unknown-artwork', 'Artwork {id}', {id})} - Pixiv Gallery`;
        renderViewer();
        renderDetail();
        renderAuthor();
        loadSeriesSections();
        loadRelated();
        loadMembership();
    }

    function renderViewer() {
        const artwork = state.artwork;
        const count = artwork.count || 1;
        document.getElementById('viewerLoading').style.display = 'none';
        document.getElementById('viewer').style.display = '';

        document.getElementById('totalPageCount').textContent = count;
        document.getElementById('metaInfo').textContent = wt('meta.pages', '{count} pages · {ext}', {
            count,
            ext: (artwork.extensions || '').toUpperCase()
        });

        state.lightboxImages = new Array(count).fill(null);

        const mainImage = document.getElementById('mainImage');
        mainImage.classList.add('loading');
        mainImage.onclick = () => openLightbox(0);
        loadImageToElement(`/api/downloaded/image/${artwork.artworkId}/0`, mainImage).then(src => {
            if (src) state.lightboxImages[0] = src;
        });

        const more = document.getElementById('morePages');
        more.innerHTML = '';
        const expandBtn = document.getElementById('expandBtn');
        const collapseBtn = document.getElementById('collapseBtn');
        expandBtn.style.display = count > 1 && !expanded ? '' : 'none';
        collapseBtn.style.display = expanded ? '' : 'none';
        expandBtn.onclick = () => expandAll(count);
        collapseBtn.onclick = collapseAll;
        syncExpandButtonText();
    }

    let expanded = false;

    async function expandAll(count) {
        const btn = document.getElementById('expandBtn');
        const collapseBtn = document.getElementById('collapseBtn');
        btn.disabled = true;
        btn.textContent = wt('button.expand-loading', 'Loading...');
        const more = document.getElementById('morePages');
        more.classList.add('open');
        
        // 并行加载所有图片
        const promises = [];
        for (let p = 1; p < count; p++) {
            const box = document.createElement('div');
            box.className = 'viewer-image';
            box.classList.add('loading');
            more.appendChild(box);
            const idx = p;
            box.addEventListener('click', () => openLightbox(idx));
            
            const promise = loadImageToElement(`/api/downloaded/image/${state.artworkId}/${p}`, box).then(src => {
                if (src) state.lightboxImages[p] = src;
            });
            promises.push(promise);
        }
        
        await Promise.all(promises);
        btn.style.display = 'none';
        collapseBtn.style.display = '';
        expanded = true;
    }

    function collapseAll() {
        const more = document.getElementById('morePages');
        more.classList.remove('open');
        more.innerHTML = '';
        const btn = document.getElementById('expandBtn');
        const collapseBtn = document.getElementById('collapseBtn');
        const count = state.artwork.count || 1;
        btn.style.display = '';
        btn.disabled = false;
        syncExpandButtonText();
        collapseBtn.style.display = 'none';
        for (let i = 1; i < state.lightboxImages.length; i++) {
            state.lightboxImages[i] = null;
        }
        expanded = false;
    }

    function renderDetail() {
        const artwork = state.artwork;
        document.getElementById('detailPanel').style.display = '';
        document.getElementById('artworkTitle').textContent = artwork.title || wt('status.unknown-artwork', 'Artwork {id}', {id: artwork.artworkId});
        const pixivArtworkLink = document.getElementById('pixivArtworkLink');
        pixivArtworkLink.href = buildPixivArtworkHref(artwork.artworkId);
        pixivArtworkLink.style.display = 'inline-flex';
        const showcaseLink = document.getElementById('showcaseLink');
        showcaseLink.href = buildShowcaseHref(artwork.artworkId);
        showcaseLink.style.display = 'inline-flex';

        const stats = [];
        stats.push(`<span class="artwork-stat">${escapeHtml(wt('stats.id', 'ID: {id}', {id: artwork.artworkId}))}</span>`);
        if (artwork.time) stats.push(`<span class="artwork-stat">${escapeHtml(wt('stats.download-time', 'Downloaded at {time}', {time: formatTime(artwork.time)}))}</span>`);
        if (artwork.xRestrict === 2) stats.push('<span class="artwork-stat" style="color:#b91c1c">R-18G</span>');
        else if (artwork.xRestrict === 1) stats.push('<span class="artwork-stat" style="color:var(--danger)">R-18</span>');
        if (artwork.isAi) stats.push('<span class="artwork-stat" style="color:#8b5cf6">AI</span>');
        if (artwork.moved) stats.push(`<span class="artwork-stat" style="color:#10b981">${escapeHtml(wt('stats.moved', 'Moved to {folder}', {folder: artwork.moveFolder || ''}))}</span>`);
        document.getElementById('artworkStats').innerHTML = stats.join('');

        const desc = document.getElementById('artworkDesc');
        if (artwork.description && artwork.description.trim()) {
            desc.innerHTML = artwork.description;
            desc.querySelectorAll('a').forEach(a => {
                a.target = '_blank';
                a.rel = 'noopener';
            });
        } else {
            desc.innerHTML = '<span style="color:var(--muted)">' + escapeHtml(wt('status.no-description', 'No description')) + '</span>';
        }

        const tagList = document.getElementById('tagList');
        const tags = artwork.tags || [];
        if (tags.length) {
            tagList.innerHTML = tags.map(t => `
                <a class="tag tag-link" href="${buildGalleryFilterHref({
                    tagId: t.tagId,
                    tagName: t.name,
                    tagTranslatedName: t.translatedName
                })}">
                    ${escapeHtml(t.name)}
                    ${t.translatedName ? `<span class="tag-translated">${escapeHtml(t.translatedName)}</span>` : ''}
                </a>
            `).join('');
        } else {
            tagList.innerHTML = '<span style="color:var(--muted); font-size:12px">' + escapeHtml(wt('status.no-tags', 'No tags')) + '</span>';
        }
    }

    function renderAuthor() {
        const artwork = state.artwork;
        if (!artwork.authorId) return;
        document.getElementById('authorPanel').style.display = '';
        const name = artwork.authorName || wt('author.default', 'Author {id}', {id: artwork.authorId});
        document.getElementById('authorAvatar').textContent = (name[0] || '?').toUpperCase();
        document.getElementById('authorName').textContent = name;
        document.getElementById('authorId').textContent = `ID: ${artwork.authorId}`;
        const authorCard = document.getElementById('authorCard');
        authorCard.href = buildGalleryFilterHref({authorId: artwork.authorId, authorName: name});
        authorCard.title = wt('author.filter', 'Filter by author {name}', {name});
        authorCard.setAttribute('aria-label', wt('author.filter', 'Filter by author {name}', {name}));
        const pixivAuthorLink = document.getElementById('pixivAuthorLink');
        pixivAuthorLink.href = buildPixivAuthorHref(artwork.authorId);
        pixivAuthorLink.title = wt('author.pixiv', 'Open {name} on Pixiv', {name});
        pixivAuthorLink.setAttribute('aria-label', wt('author.pixiv', 'Open {name} on Pixiv', {name}));
        pixivAuthorLink.style.display = 'inline';

        loadByAuthor();
    }

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

    // ---------- Lightbox ----------
    function openLightbox(index) {
        if (!state.lightboxImages[index]) return;
        state.lightboxIndex = index;
        document.getElementById('lightboxImage').src = state.lightboxImages[index];
        document.getElementById('lightboxInfo').textContent = `${index + 1} / ${state.lightboxImages.length}`;
        document.getElementById('lightbox').classList.add('open');
        
        // 预加载所有图片
        if (!expanded) {
            const count = state.artwork.count || 1;
            expandAll(count);
        }
    }

    function closeLightbox() {
        document.getElementById('lightbox').classList.remove('open');
    }

    function handleLightboxClick(e) {
        if (e.target.id === 'lightbox' || e.target.id === 'lightboxImage') closeLightbox();
    }

    function lightboxNav(delta) {
        const total = state.lightboxImages.length;
        let next = state.lightboxIndex + delta;
        if (next < 0) next = total - 1;
        if (next >= total) next = 0;
        if (!state.lightboxImages[next]) {
            if (!expanded) {
                const count = state.artwork.count || 1;
                expandAll(count).then(() => openLightbox(next));
                return;
            }
            toast(wt('status.image-not-ready', 'Image is still loading. Please wait.'));
            return;
        }
        openLightbox(next);
    }

    document.addEventListener('keydown', e => {
        if (!document.getElementById('lightbox').classList.contains('open')) return;
        if (e.key === 'Escape') closeLightbox();
        else if (e.key === 'ArrowLeft') lightboxNav(-1);
        else if (e.key === 'ArrowRight') lightboxNav(1);
    });

    // ---------- Collections ----------
    async function loadCollections() {
        try {
            const resp = await api('/api/collections');
            state.collections = resp.collections || [];
        } catch (e) {
            state.collections = [];
        }
    }

    async function loadMembership() {
        try {
            const resp = await api(`/api/collections/of/${state.artworkId}`);
            state.collectionMembership = new Set(resp.collectionIds || []);
            const btn = document.getElementById('heartBtn');
            if (btn) btn.style.display = '';
            updateHeart();
        } catch (e) {
            // Ignore — heart stays hidden when collections aren't accessible
        }
    }

    function updateHeart() {
        const btn = document.getElementById('heartBtn');
        const label = document.getElementById('heartLabel');
        if (!btn || !label) return;
        const liked = state.collectionMembership.size > 0;
        btn.classList.toggle('liked', liked);
        label.textContent = liked
            ? wt('button.favorite-active', 'Favorited ({count})', {count: state.collectionMembership.size})
            : wt('button.favorite', 'Favorite');
    }

    const heartBtn = document.getElementById('heartBtn');
    if (heartBtn) heartBtn.addEventListener('click', openAddToCollectionModal);

    function iconHtml(collection) {
        if (collection.iconExt) {
            return `<img src="/api/collections/${collection.id}/icon?v=${collection.createdTime}" alt="">`;
        }
        return HEART_SVG;
    }

    async function openAddToCollectionModal() {
        await loadCollections();
        await loadMembership();
        renderAddToCollectionList();
        document.getElementById('modalAddToCollection').classList.add('open');
    }

    function closeAddToCollectionModal() {
        document.getElementById('modalAddToCollection').classList.remove('open');
        updateHeart();
    }

    function renderAddToCollectionList() {
        const list = document.getElementById('addToCollectionList');
        if (!state.collections.length) {
            list.innerHTML = '<div style="padding:24px; text-align:center; color:var(--muted); font-size:13px">' + escapeHtml(wt('status.no-collections', 'No collections yet. Create one first.')) + '</div>';
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
                        await api(`/api/collections/${id}/artworks/${state.artworkId}`, {method: 'POST'});
                        state.collectionMembership.add(id);
                    } else {
                        await api(`/api/collections/${id}/artworks/${state.artworkId}`, {method: 'DELETE'});
                        state.collectionMembership.delete(id);
                    }
                    updateHeart();
                } catch (e) {
                    toast(e.message || wt('toast.action-failed', 'Action failed'), 'error');
                    cb.checked = !cb.checked;
                }
            });
        });
    }

    document.getElementById('quickCreateBtn').addEventListener('click', async () => {
        const input = document.getElementById('quickCreateName');
        const name = input.value.trim();
        if (!name) return;
        const btn = document.getElementById('quickCreateBtn');
        btn.disabled = true;
        try {
            const c = await api('/api/collections', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({name}),
            });
            input.value = '';
            state.collections.push(c);
            state.collections.sort((a, b) => a.sortOrder - b.sortOrder || a.id - b.id);
            await api(`/api/collections/${c.id}/artworks/${state.artworkId}`, {method: 'POST'});
            state.collectionMembership.add(c.id);
            renderAddToCollectionList();
            toast(wt('toast.created', 'Created'), 'success');
        } catch (e) {
            toast(e.message || wt('toast.create-failed', 'Creation failed'), 'error');
        } finally {
            btn.disabled = false;
        }
    });

    document.addEventListener('click', e => {
        const backdrop = e.target.classList && e.target.classList.contains('modal-backdrop');
        if (backdrop) e.target.classList.remove('open');
    });

    // ---------- Delete (admin only) ----------
    async function setupAdminMode() {
        try {
            const res = await fetch('/api/admin/invites/access-check', {credentials: 'same-origin'});
            if (!res.ok) return;
            document.body.classList.add('admin-mode');
            const btn = document.getElementById('deleteArtworkBtn');
            if (btn) btn.style.display = '';
        } catch (_) { /* not admin */ }
    }

    function openDeleteModal() {
        document.getElementById('modalDeleteArtwork').classList.add('open');
    }

    function closeDeleteModal() {
        document.getElementById('modalDeleteArtwork').classList.remove('open');
    }

    async function confirmDelete() {
        const btn = document.getElementById('deleteArtworkConfirm');
        if (state.artworkId == null) return;
        btn.disabled = true;
        try {
            await api(`/api/gallery/artwork/${state.artworkId}`, {method: 'DELETE'});
            toast(wt('delete.success', 'Deleted'), 'success');
            setTimeout(() => {
                window.location.href = '/pixiv-gallery.html?view=all';
            }, 600);
        } catch (e) {
            btn.disabled = false;
            closeDeleteModal();
            toast(e.message || wt('delete.failed', 'Delete failed'), 'error');
        }
    }

    (function wireDelete() {
        const btn = document.getElementById('deleteArtworkBtn');
        if (btn) btn.addEventListener('click', openDeleteModal);
        document.getElementById('deleteArtworkClose').addEventListener('click', closeDeleteModal);
        document.getElementById('deleteArtworkCancel').addEventListener('click', closeDeleteModal);
        document.getElementById('deleteArtworkConfirm').addEventListener('click', confirmDelete);
    })();

    // 跨页新手向导（详情页阶段）：逐区域讲解作品图 / 功能区 / 简介区 / 作者区 / 系列相关。
    // 资格闸 /api/onboarding/profile 仅对「全局可见」范围（solo / 已登录管理员）放行。
    async function setupArtworkOnboarding() {
        if (typeof PixivOnboarding === 'undefined') return;
        let eligible = false;
        try {
            const res = await fetch('/api/onboarding/profile', {credentials: 'same-origin'});
            eligible = res.ok;
        } catch (_) { /* 非管理员 / 网络异常：不参与向导 */ }
        PixivOnboarding.boot({
            page: 'artwork',
            i18n: pageI18n,
            eligible: eligible,
            sel: {
                viewer: '#viewerPanel',
                actions: '.viewer-actions',
                author: '#authorPanel',
                detail: '#detailPanel',
                series: '#seriesPanel',
                related: '#relatedPanel'
            }
        });
    }

    (async function initArtworkPage() {
        await initPageI18n();
        setupAdminMode();
        loadArtwork();
        setupArtworkOnboarding();
    })();
