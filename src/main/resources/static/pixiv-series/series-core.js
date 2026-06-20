'use strict';

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
    };

    let pageI18n = null;
    let searchTimer = null;
    let isAdmin = false;
    let aiConfigured = false;
    let activeContentLang = '';
    let seriesTranslatedLangs = [];
    let contentLangCtl = null;
    // 译后系列名 / 系列简介按语言缓存，避免切语言时反复请求（首次请求 /series/{id}?lang= 获取后塞入）
    const seriesTitleByLang = {};
    const seriesDescriptionByLang = {};
    // 译后章节标题按 lang+novelId 缓存：当前页章节切语言时由批量端点一次性填充
    let chapterTitlesByLang = {}; // { [lang]: { [novelId]: translatedTitle } }

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

    // translate: 命名空间快捷取词（AI 翻译共享文案）
    function tx(key, fallback, vars) {
        return pageText('translate:' + key, fallback, vars);
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
        pageI18n = await PixivI18n.create({namespaces: ['series', 'gallery', 'novel', 'common', 'translate']});
        await PixivLangSwitcher.mount({
            mountPoint: document.getElementById('langSwitcherAnchor'),
            i18n: pageI18n,
            onChange: function (nextClient) {
                pageI18n = nextClient;
                applyStaticPageTranslations();
                if (window.PixivNav) PixivNav.refresh();
                if (contentLangCtl) contentLangCtl.relabel(pageI18n);
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
