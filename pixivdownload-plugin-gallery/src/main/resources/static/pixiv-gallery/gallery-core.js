'use strict';
window.PixivGallery = window.PixivGallery || {};
['core','state','filters','collections','batch','views','sidebar','init'].forEach(function(k){ window.PixivGallery[k] = window.PixivGallery[k] || {}; });

    const THUMBNAIL_CONCURRENCY = 4;
    const THUMBNAIL_ROOT_MARGIN = '500px 0px';
    // ---------- Persistence ----------
    function storageGet(key) {
        try { return localStorage.getItem(key); } catch (_) { return null; }
    }
    function storageSet(key, value) {
        try { localStorage.setItem(key, value); } catch (_) { /* ignore quota */ }
    }
    function storageRemove(key) {
        try { localStorage.removeItem(key); } catch (_) { /* ignore */ }
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
                // Quota exceeded — evict half of stored thumbs and retry once
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

    let thumbnailObserver = null;
    const thumbnailQueue = [];
    let activeThumbnailLoads = 0;

    function getThumbnailObserver() {
        if (!('IntersectionObserver' in window)) return null;
        if (!thumbnailObserver) {
            thumbnailObserver = new IntersectionObserver(entries => {
                entries.forEach(entry => {
                    if (!entry.isIntersecting) return;
                    thumbnailObserver.unobserve(entry.target);
                    enqueueThumbnail(entry.target);
                });
            }, {root: null, rootMargin: THUMBNAIL_ROOT_MARGIN, threshold: 0.01});
        }
        return thumbnailObserver;
    }

    function scheduleThumbnail(img) {
        if (!img || !img.dataset.src || img.dataset.thumbScheduled === '1') return;
        img.dataset.thumbScheduled = '1';
        const observer = getThumbnailObserver();
        if (observer) {
            observer.observe(img);
            return;
        }
        enqueueThumbnail(img);
    }

    function enqueueThumbnail(img) {
        if (!img || !img.dataset.src || img.dataset.thumbQueued === '1') return;
        img.dataset.thumbQueued = '1';
        thumbnailQueue.push(img);
        pumpThumbnailQueue();
    }

    function pumpThumbnailQueue() {
        while (activeThumbnailLoads < THUMBNAIL_CONCURRENCY && thumbnailQueue.length) {
            const img = thumbnailQueue.shift();
            const url = img && img.dataset ? img.dataset.src : null;
            if (!url) continue;
            img.removeAttribute('data-src');
            activeThumbnailLoads++;
            setThumbnailState(img, 'loading');
            const cleanup = () => {
                img.removeEventListener('load', onLoad);
                img.removeEventListener('error', onError);
                activeThumbnailLoads = Math.max(0, activeThumbnailLoads - 1);
                pumpThumbnailQueue();
            };
            const onLoad = () => {
                setThumbnailState(img, 'loaded');
                cleanup();
            };
            const onError = () => {
                img.removeAttribute('src');
                setThumbnailState(img, 'failed');
                cleanup();
            };
            img.addEventListener('load', onLoad, {once: true});
            img.addEventListener('error', onError, {once: true});
            img.src = url;
            if (img.complete) {
                if (img.naturalWidth > 0) onLoad();
                else onError();
            }
        }
    }

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

    function t(key, fallback, vars) {
        if (pageI18n) {
            return pageI18n.t('gallery:' + key, fallback, vars);
        }
        return interpolate(fallback != null ? fallback : key, vars);
    }

    function uiLang() {
        return pageI18n ? pageI18n.lang : 'zh-CN';
    }

    function listSeparator() {
        return uiLang() === 'en-US' ? ', ' : '、';
    }

    function searchPlaceholderFor(type) {
        switch (type) {
            case 'title': return t('search.placeholder.title', '按作品标题搜索...');
            case 'author': return t('search.placeholder.author', '按作者名搜索...');
            case 'id': return t('search.placeholder.id', '输入作品 ID（数字，精确匹配）...');
            case 'authorId': return t('search.placeholder.author-id', '输入作者 ID（数字，精确匹配）...');
            case 'desc': return t('search.placeholder.desc', '按作品简介搜索...');
            case 'tag': return t('search.placeholder.tag', '按标签关键词搜索（模糊）...');
            case 'tagExact': return t('search.placeholder.tag-exact', '输入完整标签名（精确匹配）...');
            default: return t('search.placeholder', '搜索作品标题或画师...');
        }
    }

    function searchPlaceholderForCurrentView() {
        if (state.view === 'authors') {
            return t('filter.authors.search.placeholder', 'Search authors...');
        }
        if (state.view === 'series') {
            return t('filter.series.search.placeholder', 'Search manga series...');
        }
        return searchPlaceholderFor(state.searchType || 'all');
    }

    function syncSearchTypeSelect() {
        const select = document.getElementById('searchType');
        if (!select) return;
        const enabled = state.view === 'all';
        select.hidden = !enabled;
        select.disabled = !enabled;
        select.value = enabled ? (state.searchType || 'all') : 'all';
    }

    function updateSearchPlaceholder() {
        const el = document.getElementById('searchInput');
        if (el) el.placeholder = searchPlaceholderForCurrentView();
        syncSearchTypeSelect();
    }

    function setSearchEmptyState(empty) {
        const box = document.querySelector('.search-box');
        const btn = document.getElementById('filterToggle');
        if (box) box.classList.toggle('search-no-result', !!empty);
        if (btn) btn.classList.toggle('search-no-result', !!empty);
    }

    function applyStaticPageTranslations() {
        document.title = t('page.title', 'Pixiv Gallery');
        if (pageI18n) {
            pageI18n.apply(document.body);
            if (typeof renderGalleryStatus === 'function') renderGalleryStatus();
        }
        updateSearchPlaceholder();
        updateOrderToggleLabel();
        syncCollectionFormTitle();
    }

    async function initPageI18n() {
        pageI18n = await PixivI18n.create({namespaces: ['gallery', 'invite', 'common', 'tour']});
        window.PixivInvitesI18n = pageI18n;
        await PixivLangSwitcher.mount({
            mountPoint: document.getElementById('langSwitcherAnchor'),
            i18n: pageI18n,
            onChange: function (nextClient) {
                pageI18n = nextClient;
                window.PixivInvitesI18n = nextClient;
                applyStaticPageTranslations();
                if (window.PixivNav) PixivNav.refresh();
                const frontend = window.PixivGalleryFrontend;
                const frontendLanguageReady = frontend && typeof frontend.setLanguage === 'function'
                    ? frontend.setLanguage(nextClient.lang) : Promise.resolve();
                if (frontend && typeof frontend.isGenericRequest === 'function'
                    && frontend.isGenericRequest(location.search)) {
                    frontendLanguageReady.then(() => frontend.rerenderGeneric());
                    return;
                }
                renderCollections();
                renderTagChips();
                renderSeriesFilterChips();
                renderAuthorChips();
                syncTagFilterBar();
                syncAuthorFilterBar();
                syncSeriesFilterBar();
                if (state.activeArtworkId != null) {
                    renderAddToCollectionList();
                }
                reloadCurrentView();
            }
        });
        PixivTheme.mount({
            mountPoint: document.getElementById('langSwitcherAnchor')
        });
        applyStaticPageTranslations();
    }

    // ---------- API helpers ----------
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

    function toast(msg, type = '') {
        const container = document.getElementById('toastContainer');
        const el = document.createElement('div');
        el.className = 'toast' + (type ? ' ' + type : '');
        el.textContent = msg;
        container.appendChild(el);
        setTimeout(() => el.remove(), 2800);
    }

    function setThumbnailState(img, stateName) {
        const frame = img.closest('.work-thumb, .author-work-thumb');
        if (!frame) return;
        frame.classList.toggle('thumb-loading', stateName === 'loading');
        frame.classList.toggle('thumb-loaded', stateName === 'loaded');
        frame.classList.toggle('thumb-failed', stateName === 'failed');
    }

    function setThumbnailSource(img, src) {
        const cleanup = () => {
            img.removeEventListener('load', onLoad);
            img.removeEventListener('error', onError);
        };
        const onLoad = () => {
            cleanup();
            setThumbnailState(img, 'loaded');
        };
        const onError = () => {
            cleanup();
            img.removeAttribute('src');
            setThumbnailState(img, 'failed');
        };
        img.addEventListener('load', onLoad, {once: true});
        img.addEventListener('error', onError, {once: true});
        img.src = src;
        if (img.complete) {
            if (img.naturalWidth > 0) onLoad();
            else onError();
        }
    }

    function loadThumbnail(img) {
        scheduleThumbnail(img);
    }

    // ---------- Utilities ----------
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


// ---- PixivGallery facade ----
window.PixivGallery.core = Object.assign(window.PixivGallery.core || {}, { storageGet, storageSet, storageRemove, getThumbnailObserver, scheduleThumbnail, enqueueThumbnail, pumpThumbnailQueue, interpolate, t, uiLang, listSeparator, searchPlaceholderFor, searchPlaceholderForCurrentView, syncSearchTypeSelect, updateSearchPlaceholder, setSearchEmptyState, applyStaticPageTranslations, initPageI18n, api, toast, setThumbnailState, setThumbnailSource, loadThumbnail, escapeHtml });
