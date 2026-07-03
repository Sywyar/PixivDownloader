'use strict';
// PixivArtwork namespace bootstrap (additive facade over global functions).
window.PixivArtwork = window.PixivArtwork || {};
['core', 'viewer', 'related', 'collections', 'admin', 'init'].forEach(function (k) { window.PixivArtwork[k] = window.PixivArtwork[k] || {}; });
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
        document.getElementById('deleteArtworkMessage').textContent = wt('delete.message', 'Delete this artwork? Its image files will be permanently removed and cannot be recovered; the download record keeps a deletion mark, so it will not be re-downloaded by default.');
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


// ---- PixivArtwork facade ----
window.PixivArtwork.core = window.PixivArtwork.core || {};
window.PixivArtwork.core = Object.assign(window.PixivArtwork.core, { interpolate, wt, syncExpandButtonText, applyStaticPageTranslations, initPageI18n, ImageCache, state, getQueryParam, api, toast, escapeHtml, formatTime, buildGalleryFilterHref, buildSeriesDirectoryHref, buildPixivArtworkHref, buildShowcaseHref, buildPixivAuthorHref, loadImageToElement, HEART_SVG });
