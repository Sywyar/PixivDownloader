    const state = {
        view: 'all', // 'all' | 'authors' | 'series'
        page: 0,
        size: 24,
        sort: 'date',
        order: 'desc',
        search: '',
        searchType: 'all',
        r18: 'any',
        ai: 'any',
        formats: new Set(),
        collectionIds: new Set(),
        tagFilters: {
            must: new Set(),
            not: new Set(),
            or: new Set(),
            queue: {
                must: [],
                not: [],
                or: [],
            },
            mode: 'must',
        },
        authorFilters: {
            must: new Set(),
            not: new Set(),
            or: new Set(),
            queue: {
                must: [],
                not: [],
                or: [],
            },
            mode: 'must',
        },
        authorNames: new Map(),
        totalPages: 0,
        totalElements: 0,
        collections: [],
        tags: [],
        tagNames: new Map(),
        tagTranslatedNames: new Map(),
        tagFilterText: '',
        tagsExpanded: false,
        authorOptions: [],
        authorFilterText: '',
        authorsExpanded: false,
        seriesOptions: [],
        seriesFilterText: '',
        seriesExpanded: false,
        activeArtworkId: null,
        collectionMembership: new Map(),
        editingCollection: null,
        pendingIconFile: null,
        pendingIconClear: false,
        authors: {
            page: 0,
            size: 10,
            totalPages: 0,
            totalElements: 0,
            content: [],
        },
        series: {
            page: 0,
            size: 10,
            totalPages: 0,
            totalElements: 0,
            content: [],
        },
        seriesFilter: {id: null, title: ''},
        seriesNames: new Map(),
    };

    const AUTHOR_WORKS_PER_ROW = 30;

    const TAG_COLLAPSED_MAX_HEIGHT = 64;
    const SERIES_CHIPS_COLLAPSED_MAX_HEIGHT = 64;
    const AUTHOR_CHIPS_COLLAPSED_MAX_HEIGHT = 64;
    const SERIES_FILTER_OPTIONS_SIZE = 200;
    const AUTHOR_FILTER_OPTIONS_SIZE = 200;
    const FILTER_MODE_META = {
        must: {label: '必须有', className: 'mode-must'},
        not: {label: '不能有', className: 'mode-not'},
        or: {label: '或者有', className: 'mode-or'},
    };
    const GALLERY_VIEW_VALUES = ['all', 'authors', 'series'];
    const GALLERY_FILTER_QUERY_KEYS = [
        'filterTagId',
        'filterTag',
        'filterTagTranslated',
        'filterAuthorId',
        'filterAuthorName',
        'filterSeriesId',
        'filterSeriesTitle',
        'collectionIds',
        'openFilter',
        'createCollection',
    ];
    const SIDEBAR_STATE_STORAGE_KEY = 'pixiv:gallery-sidebar-state';
    const GALLERY_STATE_STORAGE_KEY = 'pixiv:gallery-state-v1';
    const GALLERY_CROSS_TRANSFER_KEY = 'pixiv:gallery-cross-transfer-v1';
    const GALLERY_CROSS_TRANSFER_TTL_MS = 60_000;
    const GALLERY_SORT_VALUES = new Set([
        'date', 'artworkId', 'imgs', 'status', 'authorId', 'tags', 'series'
    ]);
    const GALLERY_SEARCH_TYPE_VALUES = new Set(['all', 'title', 'author', 'id', 'authorId', 'desc', 'tag', 'tagExact']);
    const GALLERY_R18_VALUES = new Set(['any', 'r18plus', 'r18', 'r18g', 'no']);
    const GALLERY_AI_VALUES = new Set(['any', 'yes', 'no']);
    const GALLERY_FORMAT_VALUES = new Set(['jpg', 'png', 'gif', 'webp']);

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

    function serializeGalleryState() {
        return {
            view: state.view,
            page: state.page,
            sort: state.sort,
            order: state.order,
            search: state.search,
            searchType: state.searchType,
            r18: state.r18,
            ai: state.ai,
            formats: [...state.formats],
            collectionIds: [...state.collectionIds],
            tagFilters: {
                must: [...state.tagFilters.must],
                not: [...state.tagFilters.not],
                or: [...state.tagFilters.or],
                queue: {
                    must: [...state.tagFilters.queue.must],
                    not: [...state.tagFilters.queue.not],
                    or: [...state.tagFilters.queue.or],
                },
                mode: state.tagFilters.mode,
            },
            authorFilters: {
                must: [...state.authorFilters.must],
                not: [...state.authorFilters.not],
                or: [...state.authorFilters.or],
                queue: {
                    must: [...state.authorFilters.queue.must],
                    not: [...state.authorFilters.queue.not],
                    or: [...state.authorFilters.queue.or],
                },
                mode: state.authorFilters.mode,
            },
            seriesFilter: { id: state.seriesFilter.id, title: state.seriesFilter.title },
            tagFilterText: state.tagFilterText,
            authorFilterText: state.authorFilterText,
            seriesFilterText: state.seriesFilterText,
            tagNames: [...state.tagNames],
            tagTranslatedNames: [...state.tagTranslatedNames],
            authorNames: [...state.authorNames],
            seriesNames: [...state.seriesNames],
            authorsPage: state.authors.page,
            seriesPage: state.series.page,
        };
    }

    let persistGalleryTimer = null;
    function persistGalleryState() {
        clearTimeout(persistGalleryTimer);
        persistGalleryTimer = setTimeout(() => {
            try {
                storageSet(GALLERY_STATE_STORAGE_KEY, JSON.stringify(serializeGalleryState()));
            } catch (_) { /* ignore */ }
        }, 80);
    }

    function toIdArray(value) {
        if (!Array.isArray(value)) return [];
        return value.map(Number).filter(n => Number.isFinite(n) && n > 0);
    }

    function applyPersistedGalleryState(payload) {
        if (!payload || typeof payload !== 'object') return;
        if (typeof payload.view === 'string' && GALLERY_VIEW_VALUES.includes(payload.view)) {
            state.view = payload.view;
        }
        if (Number.isInteger(payload.page) && payload.page >= 0) state.page = payload.page;
        if (typeof payload.sort === 'string' && GALLERY_SORT_VALUES.has(payload.sort)) state.sort = payload.sort;
        if (payload.order === 'asc' || payload.order === 'desc') state.order = payload.order;
        if (typeof payload.search === 'string') state.search = payload.search;
        if (typeof payload.searchType === 'string' && GALLERY_SEARCH_TYPE_VALUES.has(payload.searchType)) {
            state.searchType = payload.searchType;
        }
        if (typeof payload.r18 === 'string' && GALLERY_R18_VALUES.has(payload.r18)) state.r18 = payload.r18;
        if (typeof payload.ai === 'string' && GALLERY_AI_VALUES.has(payload.ai)) state.ai = payload.ai;
        if (Array.isArray(payload.formats)) {
            state.formats = new Set(payload.formats.filter(v => GALLERY_FORMAT_VALUES.has(v)));
        }
        state.collectionIds = new Set(toIdArray(payload.collectionIds));
        if (payload.tagFilters && typeof payload.tagFilters === 'object') {
            const tf = payload.tagFilters;
            state.tagFilters.must = new Set(toIdArray(tf.must));
            state.tagFilters.not = new Set(toIdArray(tf.not));
            state.tagFilters.or = new Set(toIdArray(tf.or));
            const queue = tf.queue && typeof tf.queue === 'object' ? tf.queue : {};
            state.tagFilters.queue.must = toIdArray(queue.must);
            state.tagFilters.queue.not = toIdArray(queue.not);
            state.tagFilters.queue.or = toIdArray(queue.or);
            if (typeof tf.mode === 'string' && FILTER_MODE_META[tf.mode]) state.tagFilters.mode = tf.mode;
        }
        if (payload.authorFilters && typeof payload.authorFilters === 'object') {
            const af = payload.authorFilters;
            state.authorFilters.must = new Set(toIdArray(af.must));
            state.authorFilters.not = new Set(toIdArray(af.not));
            state.authorFilters.or = new Set(toIdArray(af.or));
            const queue = af.queue && typeof af.queue === 'object' ? af.queue : {};
            state.authorFilters.queue.must = toIdArray(queue.must);
            state.authorFilters.queue.not = toIdArray(queue.not);
            state.authorFilters.queue.or = toIdArray(queue.or);
            if (typeof af.mode === 'string' && FILTER_MODE_META[af.mode]) state.authorFilters.mode = af.mode;
        }
        if (payload.seriesFilter && typeof payload.seriesFilter === 'object') {
            const id = Number(payload.seriesFilter.id);
            const title = typeof payload.seriesFilter.title === 'string' ? payload.seriesFilter.title : '';
            if (Number.isFinite(id) && id > 0) {
                state.seriesFilter = { id, title };
                if (title) state.seriesNames.set(id, title);
            } else {
                state.seriesFilter = { id: null, title: '' };
            }
        }
        if (typeof payload.tagFilterText === 'string') state.tagFilterText = payload.tagFilterText;
        if (typeof payload.authorFilterText === 'string') state.authorFilterText = payload.authorFilterText;
        if (typeof payload.seriesFilterText === 'string') state.seriesFilterText = payload.seriesFilterText;
        rememberNameEntries(payload.tagNames, state.tagNames);
        rememberNameEntries(payload.tagTranslatedNames, state.tagTranslatedNames);
        rememberNameEntries(payload.authorNames, state.authorNames);
        rememberNameEntries(payload.seriesNames, state.seriesNames);
        if (Number.isInteger(payload.authorsPage) && payload.authorsPage >= 0) state.authors.page = payload.authorsPage;
        if (Number.isInteger(payload.seriesPage) && payload.seriesPage >= 0) state.series.page = payload.seriesPage;
    }

    function rememberNameEntries(entries, target) {
        if (!Array.isArray(entries)) return;
        entries.forEach(entry => {
            if (!Array.isArray(entry) || entry.length < 2) return;
            const id = Number(entry[0]);
            const name = entry[1];
            if (Number.isFinite(id) && typeof name === 'string' && name) target.set(id, name);
        });
    }

    function restoreGalleryState() {
        const raw = storageGet(GALLERY_STATE_STORAGE_KEY);
        if (!raw) return false;
        try {
            applyPersistedGalleryState(JSON.parse(raw));
            return true;
        } catch (_) {
            return false;
        }
    }

    function writeGalleryCrossTransfer() {
        const payload = {
            from: 'gallery',
            timestamp: Date.now(),
            view: state.view,
            search: state.search,
            searchType: state.searchType,
            r18: state.r18,
            ai: state.ai,
            sort: state.sort,
            order: state.order,
            collectionIds: [...state.collectionIds],
            tagMode: state.tagFilters.mode,
            tagIds: {
                must: [...state.tagFilters.must],
                not: [...state.tagFilters.not],
                or: [...state.tagFilters.or],
            },
            authorMode: state.authorFilters.mode,
            authorIds: {
                must: [...state.authorFilters.must],
                not: [...state.authorFilters.not],
                or: [...state.authorFilters.or],
            },
            tagNames: [...state.tagNames],
            tagTranslatedNames: [...state.tagTranslatedNames],
            authorNames: [...state.authorNames],
        };
        try {
            storageSet(GALLERY_CROSS_TRANSFER_KEY, JSON.stringify(payload));
        } catch (_) { /* ignore */ }
    }

    function consumeGalleryCrossTransfer() {
        const raw = storageGet(GALLERY_CROSS_TRANSFER_KEY);
        if (!raw) return null;
        storageRemove(GALLERY_CROSS_TRANSFER_KEY);
        try {
            const payload = JSON.parse(raw);
            if (!payload || typeof payload !== 'object') return null;
            if (payload.from === 'gallery') return null; // only accept from the OTHER gallery
            if (typeof payload.timestamp !== 'number' || Date.now() - payload.timestamp > GALLERY_CROSS_TRANSFER_TTL_MS) return null;
            return payload;
        } catch (_) { return null; }
    }

    function applyCrossTransferToGallery(transfer) {
        if (!transfer) return;
        if (typeof transfer.search === 'string') state.search = transfer.search;
        if (typeof transfer.searchType === 'string' && GALLERY_SEARCH_TYPE_VALUES.has(transfer.searchType)) {
            state.searchType = transfer.searchType;
        }
        if (typeof transfer.r18 === 'string' && GALLERY_R18_VALUES.has(transfer.r18)) state.r18 = transfer.r18;
        if (typeof transfer.ai === 'string' && GALLERY_AI_VALUES.has(transfer.ai)) state.ai = transfer.ai;
        if (typeof transfer.sort === 'string' && GALLERY_SORT_VALUES.has(transfer.sort)) state.sort = transfer.sort;
        if (transfer.order === 'asc' || transfer.order === 'desc') state.order = transfer.order;
        if (typeof transfer.view === 'string' && GALLERY_VIEW_VALUES.includes(transfer.view)) state.view = transfer.view;
        state.collectionIds = new Set(toIdArray(transfer.collectionIds));
        const tagIds = transfer.tagIds || {};
        state.tagFilters.must = new Set(toIdArray(tagIds.must));
        state.tagFilters.not = new Set(toIdArray(tagIds.not));
        state.tagFilters.or = new Set(toIdArray(tagIds.or));
        state.tagFilters.queue.must = toIdArray(tagIds.must);
        state.tagFilters.queue.not = toIdArray(tagIds.not);
        state.tagFilters.queue.or = toIdArray(tagIds.or);
        if (typeof transfer.tagMode === 'string' && FILTER_MODE_META[transfer.tagMode]) state.tagFilters.mode = transfer.tagMode;
        const authorIds = transfer.authorIds || {};
        state.authorFilters.must = new Set(toIdArray(authorIds.must));
        state.authorFilters.not = new Set(toIdArray(authorIds.not));
        state.authorFilters.or = new Set(toIdArray(authorIds.or));
        state.authorFilters.queue.must = toIdArray(authorIds.must);
        state.authorFilters.queue.not = toIdArray(authorIds.not);
        state.authorFilters.queue.or = toIdArray(authorIds.or);
        if (typeof transfer.authorMode === 'string' && FILTER_MODE_META[transfer.authorMode]) state.authorFilters.mode = transfer.authorMode;
        rememberNameEntries(transfer.tagNames, state.tagNames);
        rememberNameEntries(transfer.tagTranslatedNames, state.tagTranslatedNames);
        rememberNameEntries(transfer.authorNames, state.authorNames);
        // Cross-page: different ID spaces for series/format, reset pagination.
        state.page = 0;
        state.authors.page = 0;
        state.series.page = 0;
        state.formats.clear();
        state.seriesFilter = { id: null, title: '' };
    }

    function applyGalleryStateToUi() {
        document.querySelectorAll('.chip[data-sort]').forEach(chip => {
            chip.classList.toggle('active', chip.dataset.sort === state.sort);
        });
        const orderBtn = document.getElementById('orderToggle');
        if (orderBtn) {
            orderBtn.dataset.order = state.order;
            updateOrderToggleLabel();
        }
        document.querySelectorAll('.chip[data-r18]').forEach(c => c.classList.toggle('active', c.dataset.r18 === state.r18));
        document.querySelectorAll('.chip[data-ai]').forEach(c => c.classList.toggle('active', c.dataset.ai === state.ai));
        document.querySelectorAll('.chip[data-format]').forEach(c => c.classList.toggle('active', state.formats.has(c.dataset.format)));
        const searchInput = document.getElementById('searchInput');
        if (searchInput) searchInput.value = state.search || '';
        const searchTypeSelect = document.getElementById('searchType');
        if (searchTypeSelect) searchTypeSelect.value = state.searchType || 'all';
        updateSearchPlaceholder();
        const tagSearchInput = document.getElementById('tagSearchInput');
        if (tagSearchInput) tagSearchInput.value = state.tagFilterText || '';
        const authorSearchInput = document.getElementById('authorSearchInput');
        if (authorSearchInput) authorSearchInput.value = state.authorFilterText || '';
        const seriesSearchInput = document.getElementById('seriesSearchInput');
        if (seriesSearchInput) seriesSearchInput.value = state.seriesFilterText || '';
        renderFilterModeButtons('tag');
        renderFilterModeButtons('author');
        syncTagFilterBar();
        syncAuthorFilterBar();
        syncSeriesFilterBar();
        renderTagChips();
        renderAuthorChips();
        renderSeriesFilterChips();
        renderCollections();
        renderCollectionFilterChips();
        updateFilterBadge();
    }

    function applyGalleryViewVisibility() {
        document.querySelectorAll('.nav-item[data-view]').forEach(el => {
            el.classList.toggle('active', el.dataset.view === state.view);
        });
        document.querySelectorAll('.gallery-type-option[data-view]').forEach(el => {
            el.classList.toggle('active', el.dataset.view === state.view);
        });
        const showAll = state.view === 'all';
        const grid = document.getElementById('galleryGrid');
        const pagination = document.getElementById('pagination');
        const status = document.getElementById('galleryStatus');
        const authorView = document.getElementById('authorView');
        const authorPagination = document.getElementById('authorPagination');
        if (grid) grid.style.display = showAll ? '' : 'none';
        if (pagination) pagination.style.display = showAll ? '' : 'none';
        if (status) status.style.display = showAll ? '' : 'none';
        if (authorView) authorView.classList.toggle('active', !showAll);
        if (authorPagination) authorPagination.style.display = showAll ? 'none' : '';
        updateSearchPlaceholder();
    }

    function setupGalleryCrossPageHandoff() {
        document.querySelectorAll('.gallery-type-switch a[href]').forEach(link => {
            link.addEventListener('click', () => {
                writeGalleryCrossTransfer();
            });
        });
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
                setupTour(false);
            }
        });
        PixivTheme.mount({
            mountPoint: document.getElementById('langSwitcherAnchor')
        });
        applyStaticPageTranslations();
    }

    // 首次进入画廊页时自动展示操作指引；语言切换时刷新文案。
    function setupTour(auto) {
        if (typeof PixivTour === 'undefined') {
            return;
        }
        PixivTour.init({
            pageKey: 'gallery',
            i18n: pageI18n,
            auto: auto,
            onComplete: function () {
                // 通知后端：画廊操作指引已完成，GUI 会据此把窗口带到前台继续引导
                fetch('/api/onboarding/gallery-guide-done', {
                    method: 'POST',
                    credentials: 'same-origin'
                }).catch(function () { /* best-effort */ });
            },
            steps: [
                {target: '.search-box', titleKey: 'tour:gallery.search.title', bodyKey: 'tour:gallery.search.body'},
                {target: '#filterToggle', titleKey: 'tour:gallery.filter.title', bodyKey: 'tour:gallery.filter.body'},
                {
                    target: '.nav-item[data-view="all"]',
                    titleKey: 'tour:gallery.views.title',
                    bodyKey: 'tour:gallery.views.body'
                },
                {target: '#galleryGrid', titleKey: 'tour:gallery.grid.title', bodyKey: 'tour:gallery.grid.body'},
                {
                    target: 'a.nav-item[href="/pixiv-batch.html"]',
                    titleKey: 'tour:gallery.download.title',
                    bodyKey: 'tour:gallery.download.body'
                }
            ]
        });
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

    function normalizeFilterText(value) {
        if (value == null) return '';
        return String(value).trim().toLowerCase();
    }

    function rememberTagOption(tag) {
        if (!tag || tag.tagId == null) return;
        if (tag.name) state.tagNames.set(tag.tagId, tag.name);
        if (tag.translatedName) state.tagTranslatedNames.set(tag.tagId, tag.translatedName);
    }

    function rememberSeriesOption(series) {
        if (!series || series.seriesId == null) return;
        if (series.title) state.seriesNames.set(series.seriesId, series.title);
    }

    function getFilterBucket(kind) {
        return kind === 'tag' ? state.tagFilters : state.authorFilters;
    }

    function getFilterMode(kind) {
        return getFilterBucket(kind).mode;
    }

    function setFilterMode(kind, mode) {
        const bucket = getFilterBucket(kind);
        if (!FILTER_MODE_META[mode] || bucket.mode === mode) return;
        bucket.mode = mode;
        renderFilterModeButtons(kind);
    }

    function getFilterSelectionMode(kind, id) {
        const bucket = getFilterBucket(kind);
        if (bucket.must.has(id)) return 'must';
        if (bucket.not.has(id)) return 'not';
        if (bucket.or.has(id)) return 'or';
        return null;
    }

    function getFilterSelectionRank(kind, id) {
        const mode = getFilterSelectionMode(kind, id);
        if (mode === 'must') return 0;
        if (mode === 'not') return 1;
        if (mode === 'or') return 2;
        return 3;
    }

    function removeFilterQueueItem(bucket, mode, id) {
        const queue = bucket.queue[mode];
        const index = queue.indexOf(id);
        if (index >= 0) queue.splice(index, 1);
    }

    function clearFilterSelection(kind, id) {
        const bucket = getFilterBucket(kind);
        ['must', 'not', 'or'].forEach(mode => {
            bucket[mode].delete(id);
            removeFilterQueueItem(bucket, mode, id);
        });
    }

    function setFilterSelectionMode(kind, id, mode, metadata = {}) {
        if (!FILTER_MODE_META[mode]) return;
        if (kind === 'tag') {
            rememberTagOption({tagId: id, ...metadata});
        } else if (metadata.name) {
            state.authorNames.set(id, metadata.name);
        }
        const bucket = getFilterBucket(kind);
        clearFilterSelection(kind, id);
        bucket[mode].add(id);
        bucket.queue[mode].push(id);
    }

    function getFilterQueueIndex(kind, id) {
        const mode = getFilterSelectionMode(kind, id);
        if (!mode) return Number.MAX_SAFE_INTEGER;
        const index = getFilterBucket(kind).queue[mode].indexOf(id);
        return index >= 0 ? index : Number.MAX_SAFE_INTEGER;
    }

    function compareFilterSelectionOrder(kind, leftId, rightId) {
        const rankDiff = getFilterSelectionRank(kind, leftId) - getFilterSelectionRank(kind, rightId);
        if (rankDiff !== 0) return rankDiff;
        if (getFilterSelectionMode(kind, leftId) == null) return 0;
        return getFilterQueueIndex(kind, leftId) - getFilterQueueIndex(kind, rightId);
    }

    function getAllFilterIds(kind) {
        const bucket = getFilterBucket(kind);
        return [...new Set([
            ...bucket.queue.must,
            ...bucket.queue.not,
            ...bucket.queue.or,
            ...bucket.must,
            ...bucket.not,
            ...bucket.or,
        ])];
    }

    function getFilterCount(kind) {
        const bucket = getFilterBucket(kind);
        return bucket.must.size + bucket.not.size + bucket.or.size;
    }

    function clearFilterSearch(kind) {
        if (kind === 'tag') {
            clearTimeout(tagSearchTimer);
            state.tagFilterText = '';
            document.getElementById('tagSearchInput').value = '';
            return;
        }
        if (kind === 'series') {
            clearTimeout(seriesSearchTimer);
            state.seriesFilterText = '';
            document.getElementById('seriesSearchInput').value = '';
            return;
        }
        clearTimeout(authorSearchTimer);
        state.authorFilterText = '';
        document.getElementById('authorSearchInput').value = '';
    }

    function clearFilterSelections(kind) {
        const bucket = getFilterBucket(kind);
        bucket.must.clear();
        bucket.not.clear();
        bucket.or.clear();
        bucket.queue.must.length = 0;
        bucket.queue.not.length = 0;
        bucket.queue.or.length = 0;
    }

    function resetFilterKind(kind) {
        clearFilterSelections(kind);
        clearFilterSearch(kind);
        getFilterBucket(kind).mode = 'must';
        renderFilterModeButtons(kind);
        state.page = 0;
        if (kind === 'author') {
            syncAuthorFilterBar();
            renderAuthorChips();
        } else {
            syncTagFilterBar();
            renderTagChips();
        }
        updateFilterBadge();
        refreshGalleryForCurrentState();
    }

    function renderFilterModeButtons(kind) {
        const containerId = kind === 'tag' ? 'tagModeButtons' : 'authorModeButtons';
        const currentMode = getFilterMode(kind);
        const container = document.getElementById(containerId);
        if (!container) return;
        container.querySelectorAll('[data-filter-mode]').forEach(btn => {
            const mode = btn.dataset.filterMode;
            btn.classList.toggle('active', mode === currentMode);
            btn.setAttribute('aria-pressed', mode === currentMode ? 'true' : 'false');
        });
    }

    function toggleFilterSelection(kind, id, metadata = {}) {
        const bucket = getFilterBucket(kind);
        const targetMode = bucket.mode;
        const currentMode = getFilterSelectionMode(kind, id);
        if (currentMode !== targetMode) {
            setFilterSelectionMode(kind, id, targetMode, metadata);
        } else {
            clearFilterSelection(kind, id);
        }
    }

    function formatAuthorLabel(id) {
        const name = state.authorNames.get(id);
        return name ? `${name} (#${id})` : `#${id}`;
    }

    function formatTagLabel(id) {
        const name = state.tagNames.get(id) || t('tag.default', 'Tag #{id}', {id});
        const translated = state.tagTranslatedNames.get(id);
        if (translated && translated !== name) {
            return `${name} (${translated})`;
        }
        return name;
    }

    function buildFilterSummarySegment(ids, label, modeClass, formatter) {
        if (!ids.length) return null;
        const names = ids.map(id => escapeHtml(formatter(id)));
        const summary = names.length <= 3
            ? names.join(', ')
            : `${names.slice(0, 2).join(', ')} +${names.length}`;
        return `<span class="filter-summary-item ${modeClass}">${escapeHtml(label)}${summary}</span>`;
    }

    function buildTagFilterSummary(ids, label, modeClass) {
        return buildFilterSummarySegment(ids, label, modeClass, formatTagLabel);
    }

    function buildAuthorFilterSummary(ids, label, modeClass) {
        return buildFilterSummarySegment(ids, label, modeClass, formatAuthorLabel);
    }

    function setFilterPanelOpen(open) {
        const panel = document.getElementById('filterPanel');
        document.getElementById('filterToggle').classList.toggle('active', open);
        panel.classList.toggle('open', open);
        if (open) {
            renderTagChips();
            renderSeriesFilterChips();
            renderAuthorChips();
        }
    }

    function clearNavigationFilterQuery() {
        const url = new URL(location.href);
        let changed = false;
        GALLERY_FILTER_QUERY_KEYS.forEach(key => {
            if (url.searchParams.has(key)) {
                url.searchParams.delete(key);
                changed = true;
            }
        });
        if (!changed) return;
        const nextUrl = url.pathname + (url.search ? url.search : '') + url.hash;
        history.replaceState(history.state, '', nextUrl);
    }

    function parsePositiveIdList(raw) {
        return [...new Set(String(raw || '')
            .split(',')
            .map(value => Number(value))
            .filter(value => Number.isInteger(value) && value > 0))];
    }

    function readViewParam(params) {
        const view = params.get('view');
        return GALLERY_VIEW_VALUES.includes(view) ? view : null;
    }

    function normalizeView(view) {
        return GALLERY_VIEW_VALUES.includes(view) ? view : 'all';
    }

    function buildGalleryPageHref(path, view = state.view) {
        const params = new URLSearchParams();
        params.set('view', normalizeView(view));
        return `${path}?${params.toString()}`;
    }

    function syncViewParamInUrl() {
        const url = new URL(location.href);
        url.searchParams.set('view', normalizeView(state.view));
        const nextUrl = url.pathname + (url.search ? url.search : '') + url.hash;
        const currentUrl = location.pathname + location.search + location.hash;
        if (nextUrl !== currentUrl) {
            history.replaceState(history.state, '', nextUrl);
        }
    }

    function syncViewNavigationHrefs() {
        document.querySelectorAll('.nav-item[data-view]').forEach(el => {
            if (el.tagName === 'A') {
                el.setAttribute('href', buildGalleryPageHref('/pixiv-gallery.html', el.dataset.view));
            }
        });
        const novelGalleryLink = document.querySelector('.gallery-type-switch a[href^="/pixiv-novel-gallery.html"]');
        if (novelGalleryLink) {
            novelGalleryLink.setAttribute('href', buildGalleryPageHref('/pixiv-novel-gallery.html', state.view));
        }
    }

    function syncTagFilterBar() {
        const bar = document.getElementById('tagFilterBar');
        const labelEl = bar.querySelector('.label');
        if (labelEl) labelEl.textContent = t('summary.tags', 'Tag Filter:');
        if (getFilterCount('tag') === 0 || state.view !== 'all') {
            bar.classList.remove('visible');
            return;
        }
        const parts = [
            buildTagFilterSummary([...state.tagFilters.must], t('summary.mode.must', 'Must Have '), 'mode-must'),
            buildTagFilterSummary([...state.tagFilters.not], t('summary.mode.not', 'Must Not Have '), 'mode-not'),
            buildTagFilterSummary([...state.tagFilters.or], t('summary.mode.or', 'Either '), 'mode-or'),
        ].filter(Boolean);
        document.getElementById('tagFilterName').innerHTML = parts.join('');
        bar.classList.add('visible');
    }

    function syncAuthorFilterBar() {
        const bar = document.getElementById('authorFilterBar');
        const labelEl = bar.querySelector('.label');
        if (labelEl) labelEl.textContent = t('summary.authors', 'Author Filter:');
        if (getFilterCount('author') === 0 || state.view !== 'all') {
            bar.classList.remove('visible');
            return;
        }
        const parts = [
            buildAuthorFilterSummary([...state.authorFilters.must], t('summary.mode.must', 'Must Have '), 'mode-must'),
            buildAuthorFilterSummary([...state.authorFilters.not], t('summary.mode.not', 'Must Not Have '), 'mode-not'),
            buildAuthorFilterSummary([...state.authorFilters.or], t('summary.mode.or', 'Either '), 'mode-or'),
        ].filter(Boolean);
        document.getElementById('authorFilterName').innerHTML = parts.join('');
        bar.classList.add('visible');
    }

    function syncSeriesFilterBar() {
        const bar = document.getElementById('seriesFilterBar');
        if (!bar) return;
        const labelEl = bar.querySelector('.label');
        if (labelEl) labelEl.textContent = t('summary.series', 'Series Filter:');
        if (!state.seriesFilter.id || state.view !== 'all') {
            bar.classList.remove('visible');
            return;
        }
        const title = state.seriesFilter.title || state.seriesNames.get(state.seriesFilter.id) ||
            t('series.default', 'Series #{id}', {id: state.seriesFilter.id});
        document.getElementById('seriesFilterName').textContent = `${title} (#${state.seriesFilter.id})`;
        bar.classList.add('visible');
    }

    async function resolveIncomingTagOption(params) {
        const tagId = Number(params.get('filterTagId'));
        const tagName = (params.get('filterTag') || '').trim();
        const translatedName = (params.get('filterTagTranslated') || '').trim();
        const normalizedTagName = normalizeFilterText(tagName);
        const normalizedTranslatedName = normalizeFilterText(translatedName);

        if (Number.isFinite(tagId) && tagId > 0) {
            const loaded = state.tags.find(t => t.tagId === tagId);
            if (loaded) return loaded;
        }

        if (normalizedTagName) {
            const loadedByName = state.tags.find(t => normalizeFilterText(t.name) === normalizedTagName);
            if (loadedByName) return loadedByName;
        }

        if (normalizedTagName || normalizedTranslatedName) {
            try {
                const lookupParams = new URLSearchParams();
                if (tagName) lookupParams.set('name', tagName);
                if (translatedName) lookupParams.set('translatedName', translatedName);
                return await api('/api/gallery/tags/lookup?' + lookupParams.toString());
            } catch (_) {
                // Ignore lookup failures and fall back below.
            }
        }

        if (Number.isFinite(tagId) && tagId > 0) {
            return {
                tagId,
                name: tagName || state.tagNames.get(tagId) || t('tag.default', 'Tag #{id}', {id: tagId}),
                translatedName: translatedName || state.tagTranslatedNames.get(tagId) || null,
                artworkCount: 0,
            };
        }
        return null;
    }

    async function applyNavigationFiltersFromQuery() {
        const params = new URLSearchParams(location.search);
        const hasNavigationFilter = GALLERY_FILTER_QUERY_KEYS.some(key => params.has(key));
        if (!hasNavigationFilter) return null;

        let changed = false;
        const requestedView = readViewParam(params);

        const tag = await resolveIncomingTagOption(params);
        if (tag && tag.tagId != null) {
            clearFilterSelections('tag');
            setFilterSelectionMode('tag', tag.tagId, 'must', {
                name: tag.name,
                translatedName: tag.translatedName,
            });
            setFilterMode('tag', 'must');
            changed = true;
        }

        const authorId = Number(params.get('filterAuthorId'));
        const authorName = (params.get('filterAuthorName') || '').trim();
        if (Number.isFinite(authorId) && authorId > 0) {
            clearFilterSelections('author');
            setFilterSelectionMode('author', authorId, 'must', {name: authorName});
            setFilterMode('author', 'must');
            changed = true;
        }

        const seriesId = Number(params.get('filterSeriesId'));
        const seriesTitle = (params.get('filterSeriesTitle') || '').trim();
        if (Number.isFinite(seriesId) && seriesId > 0) {
            state.seriesFilter = {id: seriesId, title: seriesTitle};
            if (seriesTitle) state.seriesNames.set(seriesId, seriesTitle);
            changed = true;
        }

        const collectionIds = parsePositiveIdList(params.get('collectionIds'));
        if (collectionIds.length) {
            state.collectionIds = new Set(collectionIds);
            changed = true;
        }

        if (params.get('openFilter') === '1') {
            setFilterPanelOpen(true);
        }

        if (changed) {
            state.page = 0;
            syncTagFilterBar();
            syncAuthorFilterBar();
            syncSeriesFilterBar();
            renderCollections();
            renderCollectionFilterChips();
            renderTagChips();
            renderSeriesFilterChips();
            renderAuthorChips();
            updateFilterBadge();
        }

        clearNavigationFilterQuery();
        return requestedView;
    }

    // ---------- Sidebar ----------
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

    // ---------- Collections sidebar ----------
    async function loadCollections() {
        try {
            const resp = await api('/api/collections');
            state.collections = resp.collections || [];
            renderCollections();
        } catch (e) {
            console.error('load collections failed', e);
        }
    }

    function renderCollections() {
        const list = document.getElementById('collectionList');
        if (!state.collections.length) {
            list.innerHTML = '<div style="padding:8px 16px; font-size:12px; color:var(--muted)">' + escapeHtml(t('status.no-collections', 'No collections')) + '</div>';
        } else {
            list.innerHTML = state.collections.map(c => `
                <div class="collection-item ${state.collectionIds.has(c.id) ? 'selected' : ''}" data-id="${c.id}">
                    <div class="collection-icon">${iconHtml(c)}</div>
                    <span class="collection-label">${escapeHtml(c.name)}</span>
                    <span class="collection-count">${c.artworkCount}</span>
                    <button class="collection-menu" data-menu-id="${c.id}" title="${escapeHtml(t('collection.menu.more', 'More'))}">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="5" r="1"/><circle cx="12" cy="12" r="1"/><circle cx="12" cy="19" r="1"/></svg>
                    </button>
                </div>
            `).join('');

            list.querySelectorAll('.collection-item').forEach(row => {
                const id = Number(row.dataset.id);
                row.addEventListener('click', e => {
                    if (e.target.closest('.collection-menu')) return;
                    switchCollectionFilter(id);
                });
            });
            list.querySelectorAll('.collection-menu').forEach(btn => {
                btn.addEventListener('click', e => {
                    e.stopPropagation();
                    openCollectionContextMenu(Number(btn.dataset.menuId), e.clientX, e.clientY);
                });
            });
        }
        renderCollectionFilterChips();
    }

    function renderCollectionFilterChips() {
        const box = document.getElementById('filterCollectionChips');
        if (!state.collections.length) {
            box.innerHTML = '<span class="chip-empty">' + escapeHtml(t('status.no-collections', 'No collections')) + '</span>';
            return;
        }
        box.innerHTML = state.collections.map(c => `
            <button class="chip ${state.collectionIds.has(c.id) ? 'active' : ''}" data-collection-id="${c.id}">
                <span class="chip-icon">${iconHtml(c)}</span>
                ${escapeHtml(c.name)}
                <span style="color:var(--muted); margin-left:4px">${c.artworkCount}</span>
            </button>
        `).join('');
        box.querySelectorAll('.chip[data-collection-id]').forEach(chip => {
            chip.addEventListener('click', () => toggleCollectionFilter(Number(chip.dataset.collectionId)));
        });
    }

    function iconHtml(collection) {
        if (collection.iconExt) {
            return `<img src="/api/collections/${collection.id}/icon?v=${collection.createdTime}" alt="">`;
        }
        return HEART_SVG;
    }

    function toggleCollectionFilter(id) {
        if (state.collectionIds.has(id)) state.collectionIds.delete(id);
        else state.collectionIds.add(id);
        state.page = 0;
        renderCollections();
        updateFilterBadge();
        refreshGalleryForCurrentState();
    }

    function switchCollectionFilter(id) {
        const alreadyExclusiveCollection = state.collectionIds.size === 1 && state.collectionIds.has(id);
        const alreadyOnlyCollection = alreadyExclusiveCollection
            && state.view === 'all'
            && state.r18 === 'any'
            && state.ai === 'any'
            && state.formats.size === 0
            && getFilterCount('tag') === 0
            && getFilterCount('author') === 0
            && !state.seriesFilter.id;
        if (alreadyOnlyCollection) return;
        resetFiltersForSidebarCollection(id);
    }

    function resetFiltersForSidebarCollection(id) {
        state.r18 = 'any';
        state.ai = 'any';
        state.formats.clear();
        state.collectionIds.clear();
        state.collectionIds.add(id);
        clearFilterSelections('tag');
        clearFilterSelections('author');
        state.seriesFilter = {id: null, title: ''};
        clearFilterSearch('tag');
        clearFilterSearch('series');
        clearFilterSearch('author');
        state.tagFilters.mode = 'must';
        state.authorFilters.mode = 'must';
        state.authorNames.clear();
        document.querySelectorAll('.chip[data-r18]').forEach(c => c.classList.toggle('active', c.dataset.r18 === 'any'));
        document.querySelectorAll('.chip[data-ai]').forEach(c => c.classList.toggle('active', c.dataset.ai === 'any'));
        document.querySelectorAll('.chip[data-format]').forEach(c => c.classList.remove('active'));
        state.page = 0;
        renderFilterModeButtons('tag');
        renderFilterModeButtons('author');
        syncTagFilterBar();
        syncAuthorFilterBar();
        syncSeriesFilterBar();
        renderCollections();
        renderTagChips();
        renderSeriesFilterChips();
        renderAuthorChips();
        updateFilterBadge();
        refreshGalleryForCurrentState();
    }

    // ---------- Collection CRUD ----------
    document.getElementById('btnCreateCollection').addEventListener('click', () => openCollectionFormModal(null));

    function openCollectionFormModal(collection) {
        state.editingCollection = collection;
        state.pendingIconFile = null;
        state.pendingIconClear = false;
        syncCollectionFormTitle();
        document.getElementById('collectionFormName').value = collection ? collection.name : '';
        document.getElementById('collectionDownloadRootEnabled').checked = !!(collection && collection.downloadRoot);
        const existingDownloadRoot = collection && collection.downloadRoot ? collection.downloadRoot : '';
        document.getElementById('collectionDownloadRoot').value = existingDownloadRoot || defaultCollectionDownloadRoot();
        syncCollectionDownloadRootControls();
        updateFormIconPreview(collection);
        document.getElementById('modalCollectionForm').classList.add('open');
        setTimeout(() => document.getElementById('collectionFormName').focus(), 50);
    }

    function closeCollectionFormModal() {
        document.getElementById('modalCollectionForm').classList.remove('open');
        state.editingCollection = null;
        state.pendingIconFile = null;
        state.pendingIconClear = false;
        document.getElementById('collectionFormIconFile').value = '';
        document.getElementById('collectionDownloadRootEnabled').checked = false;
        document.getElementById('collectionDownloadRoot').value = '';
        document.getElementById('collectionDownloadRootPreview').value = '';
        syncCollectionDownloadRootControls();
    }

    function defaultCollectionDownloadRoot() {
        return '/{collection_name}';
    }

    function syncCollectionDownloadRootControls() {
        const enabled = document.getElementById('collectionDownloadRootEnabled').checked;
        const input = document.getElementById('collectionDownloadRoot');
        if (enabled && !input.value.trim()) {
            input.value = defaultCollectionDownloadRoot();
        }
        document.getElementById('collectionDownloadRootSettings').classList.toggle('visible', enabled);
        renderCollectionDownloadRootPreview();
    }

    function renderCollectionDownloadRootPreview() {
        const preview = document.getElementById('collectionDownloadRootPreview');
        const inputValue = document.getElementById('collectionDownloadRoot').value.trim();
        if (!document.getElementById('collectionDownloadRootEnabled').checked || !inputValue) {
            preview.value = '';
            return;
        }
        const expanded = expandCollectionDownloadRoot(inputValue);
        preview.value = isAbsoluteCollectionDownloadRoot(expanded)
            ? expanded
            : joinPreviewPath('{download.root-folder}', stripLeadingPathSeparators(expanded));
    }

    function expandCollectionDownloadRoot(value) {
        return value.replaceAll('{collection_name}', safeCollectionNamePathSegment());
    }

    function safeCollectionNamePathSegment() {
        const raw = document.getElementById('collectionFormName').value.trim();
        if (!raw) return '{collection_name}';
        const sanitized = Array.from(raw, ch => /[\/\\:*?"<>|\x00-\x1F\x7F]/.test(ch) ? '_' : ch).join('').trim();
        return sanitized && sanitized !== '.' && sanitized !== '..' ? sanitized : '{collection_name}';
    }

    function isAbsoluteCollectionDownloadRoot(value) {
        return /^[A-Za-z]:[\\/]/.test(value) || /^[\\/]{2}[^\\/]+[\\/][^\\/]+/.test(value);
    }

    function stripLeadingPathSeparators(value) {
        return value.replace(/^[\\/]+/, '');
    }

    function joinPreviewPath(root, relativePath) {
        return relativePath ? `${root}/${relativePath}` : root;
    }

    function readCollectionDownloadRoot() {
        const enabled = document.getElementById('collectionDownloadRootEnabled').checked;
        if (!enabled) {
            return null;
        }
        return document.getElementById('collectionDownloadRoot').value.trim();
    }

    function hasControlCharacter(value) {
        return /[\x00-\x1F\x7F]/.test(value);
    }

    function updateFormIconPreview(collection) {
        const preview = document.getElementById('collectionFormIconPreview');
        const clearBtn = document.getElementById('collectionFormIconClear');
        if (state.pendingIconFile) {
            const url = URL.createObjectURL(state.pendingIconFile);
            preview.innerHTML = `<img src="${url}" alt="">`;
            clearBtn.style.display = '';
        } else if (collection && collection.iconExt && !state.pendingIconClear) {
            preview.innerHTML = `<img src="/api/collections/${collection.id}/icon?v=${Date.now()}" alt="">`;
            clearBtn.style.display = '';
        } else {
            preview.innerHTML = HEART_SVG;
            clearBtn.style.display = 'none';
        }
    }

    document.getElementById('collectionFormIconFile').addEventListener('change', e => {
        const file = e.target.files[0];
        if (!file) return;
        if (file.size > 1024 * 1024) {
            toast(t('toast.icon-too-large', 'Icon size must not exceed 1MB'), 'error');
            e.target.value = '';
            return;
        }
        state.pendingIconFile = file;
        state.pendingIconClear = false;
        updateFormIconPreview(state.editingCollection);
    });

    document.getElementById('collectionFormIconClear').addEventListener('click', () => {
        state.pendingIconFile = null;
        state.pendingIconClear = true;
        document.getElementById('collectionFormIconFile').value = '';
        updateFormIconPreview(state.editingCollection);
    });

    document.getElementById('collectionFormName').addEventListener('input', () => {
        renderCollectionDownloadRootPreview();
    });

    document.getElementById('collectionDownloadRoot').addEventListener('input', () => {
        renderCollectionDownloadRootPreview();
    });

    document.getElementById('collectionDownloadRootEnabled').addEventListener('change', () => {
        syncCollectionDownloadRootControls();
    });

    document.getElementById('collectionFormSubmit').addEventListener('click', async () => {
        const name = document.getElementById('collectionFormName').value.trim();
        if (!name) {
            toast(t('toast.name-required', 'Please enter a name'), 'error');
            return;
        }
        const downloadRoot = readCollectionDownloadRoot();
        if (document.getElementById('collectionDownloadRootEnabled').checked && !downloadRoot) {
            toast(t('toast.download-root-required', '请填写下载目录'), 'error');
            return;
        }
        if (downloadRoot && hasControlCharacter(downloadRoot)) {
            toast(t('toast.download-root-invalid', '下载目录不能包含控制字符'), 'error');
            return;
        }
        const btn = document.getElementById('collectionFormSubmit');
        btn.disabled = true;
        try {
            let collection;
            if (state.editingCollection) {
                await api(`/api/collections/${state.editingCollection.id}`, {
                    method: 'PUT',
                    headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify({name}),
                });
                await api(`/api/collections/${state.editingCollection.id}/download-root`, {
                    method: 'PUT',
                    headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify({downloadRoot}),
                });
                collection = {...state.editingCollection, name, downloadRoot};
            } else {
                collection = await api('/api/collections', {
                    method: 'POST',
                    headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify({name, downloadRoot}),
                });
            }
            if (state.pendingIconFile) {
                const form = new FormData();
                form.append('file', state.pendingIconFile);
                await api(`/api/collections/${collection.id}/icon`, {method: 'POST', body: form});
            } else if (state.pendingIconClear && state.editingCollection) {
                await api(`/api/collections/${collection.id}/icon`, {method: 'DELETE'});
            }
            toast(state.editingCollection ? t('toast.saved', 'Saved') : t('toast.created', 'Created'), 'success');
            closeCollectionFormModal();
            await loadCollections();
        } catch (e) {
            toast(e.message || t('toast.save-failed', 'Save failed'), 'error');
        } finally {
            btn.disabled = false;
        }
    });

    // ---------- Collection context menu ----------
    const contextMenu = document.getElementById('collectionContextMenu');
    let contextMenuCollection = null;

    function openCollectionContextMenu(id, clientX, clientY) {
        contextMenuCollection = state.collections.find(c => c.id === id);
        if (!contextMenuCollection) return;
        contextMenu.classList.add('open');
        const menuWidth = 140;
        const left = Math.min(clientX + 4, window.innerWidth - menuWidth - 8);
        contextMenu.style.left = left + 'px';
        contextMenu.style.top = Math.min(clientY + 4, window.innerHeight - contextMenu.offsetHeight - 8) + 'px';
    }

    function closeContextMenu() {
        contextMenu.classList.remove('open');
        contextMenuCollection = null;
    }

    document.addEventListener('click', e => {
        if (!e.target.closest('#collectionContextMenu') && !e.target.closest('.collection-menu')) closeContextMenu();
    });

    contextMenu.addEventListener('click', async e => {
        const action = e.target.closest('.context-item')?.dataset.action;
        if (!action || !contextMenuCollection) return;
        const target = contextMenuCollection;
        closeContextMenu();
        if (action === 'edit') {
            openCollectionFormModal(target);
        } else if (action === 'delete') {
            if (!confirm(t('dialog.delete-collection', 'Delete "{name}"? Artwork links in this collection will be removed.', {name: target.name}))) return;
            try {
                await api(`/api/collections/${target.id}`, {method: 'DELETE'});
                state.collectionIds.delete(target.id);
                toast(t('toast.deleted', 'Deleted'), 'success');
                await loadCollections();
                updateFilterBadge();
                await loadGallery();
            } catch (err) {
                toast(err.message || t('toast.delete-failed', 'Delete failed'), 'error');
            }
        }
    });

    // ---------- Filter panel ----------
    document.getElementById('filterToggle').addEventListener('click', () => {
        const panel = document.getElementById('filterPanel');
        const willOpen = !panel.classList.contains('open');
        if (willOpen) {
            ensureGalleryView();
        }
        setFilterPanelOpen(willOpen);
    });

    document.querySelectorAll('.filter-mode-btn[data-filter-kind][data-filter-mode]').forEach(btn => {
        btn.addEventListener('click', () => {
            setFilterMode(btn.dataset.filterKind, btn.dataset.filterMode);
        });
    });

    document.getElementById('tagFilterReset').addEventListener('click', () => {
        resetFilterKind('tag');
    });

    document.getElementById('seriesFilterReset').addEventListener('click', () => {
        resetSeriesFilterOptions();
    });

    document.getElementById('authorFilterReset').addEventListener('click', () => {
        resetFilterKind('author');
    });

    document.querySelectorAll('.chip[data-sort]').forEach(chip => {
        chip.addEventListener('click', () => {
            document.querySelectorAll('.chip[data-sort]').forEach(c => c.classList.remove('active'));
            chip.classList.add('active');
            state.sort = chip.dataset.sort;
            state.page = 0;
            refreshGalleryForCurrentState();
        });
    });

    document.getElementById('orderToggle').addEventListener('click', () => {
        const btn = document.getElementById('orderToggle');
        state.order = state.order === 'desc' ? 'asc' : 'desc';
        btn.dataset.order = state.order;
        updateOrderToggleLabel();
        state.page = 0;
        refreshGalleryForCurrentState();
    });

    function updateOrderToggleLabel() {
        const btn = document.getElementById('orderToggle');
        if (!btn) return;
        btn.textContent = state.order === 'desc'
            ? t('filter.order.desc', '↓ Desc')
            : t('filter.order.asc', '↑ Asc');
    }

    function syncCollectionFormTitle() {
        const title = document.getElementById('collectionFormTitle');
        if (!title) return;
        title.textContent = state.editingCollection
            ? t('collection.form.edit', 'Edit Collection')
            : t('collection.form.create', 'New Collection');
    }

    function bindTristate(attr, key) {
        document.querySelectorAll(`.chip[data-${attr}]`).forEach(chip => {
            chip.addEventListener('click', () => {
                document.querySelectorAll(`.chip[data-${attr}]`).forEach(c => c.classList.remove('active'));
                chip.classList.add('active');
                state[key] = chip.dataset[attr];
                state.page = 0;
                updateFilterBadge();
                refreshGalleryForCurrentState();
            });
        });
    }

    bindTristate('r18', 'r18');
    bindTristate('ai', 'ai');

    document.querySelectorAll('.chip[data-format]').forEach(chip => {
        chip.addEventListener('click', () => {
            const fmt = chip.dataset.format;
            if (state.formats.has(fmt)) state.formats.delete(fmt); else state.formats.add(fmt);
            chip.classList.toggle('active');
            state.page = 0;
            updateFilterBadge();
            refreshGalleryForCurrentState();
        });
    });

    document.getElementById('filterReset').addEventListener('click', () => {
        state.r18 = 'any';
        state.ai = 'any';
        state.formats.clear();
        state.collectionIds.clear();
        clearFilterSelections('tag');
        clearFilterSelections('author');
        state.seriesFilter = {id: null, title: ''};
        clearFilterSearch('tag');
        clearFilterSearch('series');
        clearFilterSearch('author');
        state.tagFilters.mode = 'must';
        state.authorFilters.mode = 'must';
        state.authorNames.clear();
        document.querySelectorAll('.chip[data-r18]').forEach(c => c.classList.toggle('active', c.dataset.r18 === 'any'));
        document.querySelectorAll('.chip[data-ai]').forEach(c => c.classList.toggle('active', c.dataset.ai === 'any'));
        document.querySelectorAll('.chip[data-format]').forEach(c => c.classList.remove('active'));
        state.page = 0;
        renderFilterModeButtons('tag');
        renderFilterModeButtons('author');
        syncTagFilterBar();
        syncAuthorFilterBar();
        syncSeriesFilterBar();
        renderCollections();
        renderTagChips();
        renderSeriesFilterChips();
        renderAuthorChips();
        updateFilterBadge();
        refreshGalleryForCurrentState();
    });

    function updateFilterBadge() {
        let n = 0;
        if (state.r18 !== 'any') n++;
        if (state.ai !== 'any') n++;
        n += state.formats.size;
        n += state.collectionIds.size;
        n += getFilterCount('tag');
        n += getFilterCount('author');
        if (state.seriesFilter.id) n++;
        const badge = document.getElementById('filterBadge');
        badge.textContent = n;
        badge.classList.toggle('visible', n > 0);
    }

    // ---------- Tag filter ----------
    async function loadTagOptions() {
        try {
            const resp = await api('/api/gallery/tags?limit=500');
            state.tags = resp.tags || [];
            state.tags.forEach(rememberTagOption);
        } catch (e) {
            console.error('load tags failed', e);
            state.tags = [];
        }
        renderTagChips();
    }

    function renderTagChips() {
        const box = document.getElementById('filterTagChips');
        const toggleBtn = document.getElementById('tagExpandToggle');
        const kw = state.tagFilterText.trim().toLowerCase();
        let filtered = kw
            ? state.tags.filter(t =>
                (t.name && t.name.toLowerCase().includes(kw)) ||
                (t.translatedName && t.translatedName.toLowerCase().includes(kw)))
            : state.tags.slice();

        // 已选标签置顶，未选按原顺序（后端已按使用量降序）
        filtered.sort((a, b) => compareFilterSelectionOrder('tag', a.tagId, b.tagId));

        const loadedIds = new Set(filtered.map(t => t.tagId));
        const missing = getAllFilterIds('tag').filter(id => !loadedIds.has(id));
        if (missing.length) {
            const stubs = missing.map(id => ({
                tagId: id,
                name: state.tagNames.get(id) || t('tag.default', 'Tag #{id}', {id}),
                translatedName: state.tagTranslatedNames.get(id) || null,
                artworkCount: 0,
            }));
            filtered = stubs.concat(filtered);
            filtered.sort((a, b) => compareFilterSelectionOrder('tag', a.tagId, b.tagId));
        }

        const expanded = state.tagsExpanded || kw.length > 0;

        if (filtered.length === 0) {
            box.innerHTML = state.tags.length
                ? '<span class="chip-empty">' + escapeHtml(t('status.no-matching-tags', 'No matching tags')) + '</span>'
                : '<span class="chip-empty">' + escapeHtml(t('status.no-tags', 'No tags')) + '</span>';
            box.classList.remove('has-overflow');
            box.classList.toggle('expanded', expanded);
            toggleBtn.style.display = 'none';
            return;
        }

        box.innerHTML = filtered.map(t => {
            const mode = getFilterSelectionMode('tag', t.tagId);
            const meta = mode ? FILTER_MODE_META[mode] : null;
            const trans = t.translatedName
                ? `<span class="tag-trans">${escapeHtml(t.translatedName)}</span>`
                : '';
            const count = t.artworkCount > 0
                ? `<span class="chip-count">${t.artworkCount}</span>`
                : '';
            return `<button class="chip ${mode ? `filter-selected ${meta.className}` : ''}" type="button" data-tag-id="${t.tagId}">
                ${escapeHtml(t.name || '')}
                ${trans}
                ${count}
            </button>`;
        }).join('');
        const tagById = new Map(filtered.map(t => [String(t.tagId), t]));
        box.querySelectorAll('.chip[data-tag-id]').forEach(chip => {
            chip.addEventListener('click', () => toggleTagFilter(tagById.get(chip.dataset.tagId)));
        });
        box.classList.toggle('expanded', expanded);

        requestAnimationFrame(() => {
            const hasOverflow = box.scrollHeight > TAG_COLLAPSED_MAX_HEIGHT + 2;
            box.classList.toggle('has-overflow', hasOverflow && !expanded);
            if (hasOverflow || expanded) {
                toggleBtn.style.display = '';
                toggleBtn.textContent = state.tagsExpanded
                    ? t('filter.collapse', 'Collapse')
                    : t('filter.expand', 'Expand All');
                toggleBtn.classList.toggle('active', state.tagsExpanded);
            } else {
                toggleBtn.style.display = 'none';
            }
        });
    }

    function toggleTagFilter(tag) {
        if (!tag || tag.tagId == null) return;
        toggleFilterSelection('tag', Number(tag.tagId), {
            name: tag.name,
            translatedName: tag.translatedName,
        });
        state.page = 0;
        syncTagFilterBar();
        renderTagChips();
        updateFilterBadge();
        refreshGalleryForCurrentState();
    }

    function clearTagFilter() {
        clearFilterSelections('tag');
        state.page = 0;
        syncTagFilterBar();
        renderTagChips();
        updateFilterBadge();
        refreshGalleryForCurrentState();
    }

    let tagSearchTimer = null;
    document.getElementById('tagSearchInput').addEventListener('input', e => {
        const v = e.target.value;
        clearTimeout(tagSearchTimer);
        tagSearchTimer = setTimeout(() => {
            state.tagFilterText = v;
            renderTagChips();
        }, 150);
    });

    document.getElementById('tagExpandToggle').addEventListener('click', () => {
        state.tagsExpanded = !state.tagsExpanded;
        renderTagChips();
    });

    // ---------- Series filter ----------
    async function loadSeriesOptions() {
        try {
            const params = new URLSearchParams();
            params.set('page', 0);
            params.set('size', SERIES_FILTER_OPTIONS_SIZE);
            params.set('sort', 'artworks');
            const resp = await api('/api/series/paged?' + params.toString());
            state.seriesOptions = resp.content || [];
            state.seriesOptions.forEach(rememberSeriesOption);
        } catch (e) {
            console.error('load series options failed', e);
            state.seriesOptions = [];
        }
        renderSeriesFilterChips();
        syncSeriesFilterBar();
    }

    function renderSeriesFilterChips() {
        const box = document.getElementById('filterSeriesChips');
        const toggleBtn = document.getElementById('seriesExpandToggle');
        if (!box || !toggleBtn) return;
        const kw = state.seriesFilterText.trim().toLowerCase();
        const selectedId = state.seriesFilter.id ? Number(state.seriesFilter.id) : null;
        let filtered = kw
            ? state.seriesOptions.filter(s =>
                (s.title && s.title.toLowerCase().includes(kw)) ||
                (s.authorName && s.authorName.toLowerCase().includes(kw)) ||
                String(s.seriesId).includes(kw))
            : state.seriesOptions.slice();

        if (selectedId && !filtered.some(s => Number(s.seriesId) === selectedId)) {
            const loaded = state.seriesOptions.find(s => Number(s.seriesId) === selectedId);
            filtered = [{
                seriesId: selectedId,
                title: state.seriesFilter.title || state.seriesNames.get(selectedId) || loaded?.title,
                artworkCount: loaded?.artworkCount || 0,
                authorName: loaded?.authorName || null,
            }].concat(filtered);
        }

        filtered.sort((a, b) => {
            const aSelected = selectedId != null && Number(a.seriesId) === selectedId;
            const bSelected = selectedId != null && Number(b.seriesId) === selectedId;
            if (aSelected !== bSelected) return aSelected ? -1 : 1;
            return 0;
        });

        const expanded = state.seriesExpanded || kw.length > 0;

        if (filtered.length === 0) {
            box.innerHTML = state.seriesOptions.length
                ? '<span class="chip-empty">' + escapeHtml(t('status.no-matching-series', 'No matching manga series')) + '</span>'
                : '<span class="chip-empty">' + escapeHtml(t('status.no-series', 'No manga series')) + '</span>';
            box.classList.remove('has-overflow');
            box.classList.toggle('expanded', expanded);
            toggleBtn.style.display = 'none';
            return;
        }

        box.innerHTML = filtered.map(s => {
            const id = Number(s.seriesId);
            const active = selectedId === id;
            const title = s.title || t('series.default', 'Series #{id}', {id});
            const author = s.authorName
                ? `<span class="tag-trans">${escapeHtml(s.authorName)}</span>`
                : '';
            const count = s.artworkCount > 0
                ? `<span class="chip-count">${s.artworkCount}</span>`
                : '';
            return `<button class="chip ${active ? 'active' : ''}" type="button" data-series-id="${id}" title="#${id}">
                ${escapeHtml(title)}
                ${author}
                ${count}
            </button>`;
        }).join('');
        const seriesById = new Map(filtered.map(s => [String(s.seriesId), s]));
        box.querySelectorAll('.chip[data-series-id]').forEach(chip => {
            chip.addEventListener('click', () => {
                const id = Number(chip.dataset.seriesId);
                const opt = seriesById.get(chip.dataset.seriesId);
                toggleSeriesFilter(id, opt ? opt.title : state.seriesNames.get(id));
            });
        });
        box.classList.toggle('expanded', expanded);

        requestAnimationFrame(() => {
            const hasOverflow = box.scrollHeight > SERIES_CHIPS_COLLAPSED_MAX_HEIGHT + 2;
            box.classList.toggle('has-overflow', hasOverflow && !expanded);
            if (hasOverflow || expanded) {
                toggleBtn.style.display = '';
                toggleBtn.textContent = state.seriesExpanded
                    ? t('filter.collapse', 'Collapse')
                    : t('filter.expand', 'Expand All');
                toggleBtn.classList.toggle('active', state.seriesExpanded);
            } else {
                toggleBtn.style.display = 'none';
            }
        });
    }

    function toggleSeriesFilter(seriesId, seriesTitle) {
        const id = Number(seriesId);
        if (!Number.isFinite(id) || id <= 0) return;
        if (state.seriesFilter.id === id) {
            state.seriesFilter = {id: null, title: ''};
        } else {
            state.seriesFilter = {id, title: seriesTitle || ''};
            if (seriesTitle) state.seriesNames.set(id, seriesTitle);
        }
        refreshSeriesFilter();
    }

    function resetSeriesFilterOptions() {
        state.seriesFilter = {id: null, title: ''};
        state.seriesExpanded = false;
        clearFilterSearch('series');
        refreshSeriesFilter();
    }

    function refreshSeriesFilter() {
        state.page = 0;
        syncSeriesFilterBar();
        renderSeriesFilterChips();
        updateFilterBadge();
        refreshGalleryForCurrentState();
    }

    let seriesSearchTimer = null;
    document.getElementById('seriesSearchInput').addEventListener('input', e => {
        const v = e.target.value;
        clearTimeout(seriesSearchTimer);
        seriesSearchTimer = setTimeout(() => {
            state.seriesFilterText = v;
            renderSeriesFilterChips();
        }, 150);
    });

    document.getElementById('seriesExpandToggle').addEventListener('click', () => {
        state.seriesExpanded = !state.seriesExpanded;
        renderSeriesFilterChips();
    });

    // ---------- Author filter ----------
    async function loadAuthorOptions() {
        try {
            const params = new URLSearchParams();
            params.set('page', 0);
            params.set('size', AUTHOR_FILTER_OPTIONS_SIZE);
            params.set('sort', 'artworks');
            const resp = await api('/api/authors/paged?' + params.toString());
            state.authorOptions = resp.content || [];
            state.authorOptions.forEach(author => {
                if (author && author.authorId != null && author.name) {
                    state.authorNames.set(author.authorId, author.name);
                }
            });
        } catch (e) {
            console.error('load author options failed', e);
            state.authorOptions = [];
        }
        renderAuthorChips();
    }

    function renderAuthorChips() {
        const box = document.getElementById('filterAuthorChips');
        const toggleBtn = document.getElementById('authorExpandToggle');
        if (!state.authorOptions.length) {
            box.innerHTML = '<span class="chip-empty">' + escapeHtml(t('status.no-authors', 'No authors')) + '</span>';
            toggleBtn.style.display = 'none';
            return;
        }
        const kw = state.authorFilterText.trim().toLowerCase();
        let filtered = kw
            ? state.authorOptions.filter(a =>
                (a.name && a.name.toLowerCase().includes(kw)) ||
                String(a.authorId).includes(kw))
            : state.authorOptions.slice();

        // 已选作者置顶
        filtered.sort((a, b) => compareFilterSelectionOrder('author', a.authorId, b.authorId));

        // 若选中的作者不在加载的前 N 个里，补齐到列表首部
        const loadedIds = new Set(filtered.map(a => a.authorId));
        const missing = getAllFilterIds('author').filter(id => !loadedIds.has(id));
        if (missing.length) {
            const stubs = missing.map(id => ({
                authorId: id,
                name: state.authorNames.get(id) || t('author.default', 'Author {id}', {id}),
                artworkCount: 0,
            }));
            filtered = stubs.concat(filtered);
            filtered.sort((a, b) => compareFilterSelectionOrder('author', a.authorId, b.authorId));
        }

        const expanded = state.authorsExpanded || kw.length > 0;

        if (filtered.length === 0) {
            box.innerHTML = '<span class="chip-empty">' + escapeHtml(t('status.no-matching-authors', 'No matching authors')) + '</span>';
            box.classList.remove('has-overflow');
            box.classList.toggle('expanded', expanded);
            toggleBtn.style.display = 'none';
            return;
        }

        box.innerHTML = filtered.map(a => {
            const mode = getFilterSelectionMode('author', a.authorId);
            const meta = mode ? FILTER_MODE_META[mode] : null;
            const count = a.artworkCount > 0
                ? `<span class="chip-count">${a.artworkCount}</span>`
                : '';
            return `<button class="chip ${mode ? `filter-selected ${meta.className}` : ''}" type="button" data-author-id="${a.authorId}" title="#${a.authorId}">
                ${escapeHtml(a.name || t('author.default', 'Author {id}', {id: a.authorId}))}
                ${count}
            </button>`;
        }).join('');
        box.querySelectorAll('.chip[data-author-id]').forEach(chip => {
            chip.addEventListener('click', () => {
                const id = Number(chip.dataset.authorId);
                const opt = state.authorOptions.find(a => a.authorId === id);
                toggleAuthorFilter(id, opt ? opt.name : state.authorNames.get(id));
            });
        });
        box.classList.toggle('expanded', expanded);

        requestAnimationFrame(() => {
            const hasOverflow = box.scrollHeight > AUTHOR_CHIPS_COLLAPSED_MAX_HEIGHT + 2;
            box.classList.toggle('has-overflow', hasOverflow && !expanded);
            if (hasOverflow || expanded) {
                toggleBtn.style.display = '';
                toggleBtn.textContent = state.authorsExpanded
                    ? t('filter.collapse', 'Collapse')
                    : t('filter.expand', 'Expand All');
                toggleBtn.classList.toggle('active', state.authorsExpanded);
            } else {
                toggleBtn.style.display = 'none';
            }
        });
    }

    function toggleAuthorFilter(authorId, authorName) {
        toggleFilterSelection('author', authorId, {name: authorName});
        refreshAuthorFilter();
    }

    function setAuthorFilterExclusive(authorId, authorName) {
        clearFilterSelections('author');
        if (authorId) {
            setFilterSelectionMode('author', authorId, 'must', {name: authorName});
        }
        setFilterMode('author', 'must');
        refreshAuthorFilter({switchToAll: true});
    }

    function clearAuthorFilter() {
        clearFilterSelections('author');
        refreshAuthorFilter();
    }

    function refreshAuthorFilter({switchToAll = false} = {}) {
        state.page = 0;
        syncAuthorFilterBar();
        renderAuthorChips();
        updateFilterBadge();
        if (switchToAll || state.view !== 'all') {
            switchView('all');
        } else {
            loadGallery();
        }
    }

    function setSeriesFilterExclusive(seriesId, seriesTitle) {
        if (seriesId) {
            state.seriesFilter = {id: Number(seriesId), title: seriesTitle || ''};
            if (seriesTitle) state.seriesNames.set(Number(seriesId), seriesTitle);
        } else {
            state.seriesFilter = {id: null, title: ''};
        }
        refreshSeriesFilter();
    }

    function clearSeriesFilter() {
        state.seriesFilter = {id: null, title: ''};
        refreshSeriesFilter();
    }

    function buildGalleryFilterHref({seriesId, seriesTitle} = {}) {
        const params = new URLSearchParams();
        params.set('view', 'all');
        if (seriesId != null) params.set('filterSeriesId', String(seriesId));
        if (seriesTitle) params.set('filterSeriesTitle', seriesTitle);
        return `/pixiv-gallery.html?${params.toString()}`;
    }

    function buildSeriesDirectoryHref({seriesId, seriesTitle} = {}) {
        const params = new URLSearchParams();
        params.set('type', 'artwork');
        if (seriesId != null) params.set('seriesId', String(seriesId));
        if (seriesTitle) params.set('seriesTitle', seriesTitle);
        return `/pixiv-series.html?${params.toString()}`;
    }

    let authorSearchTimer = null;
    document.getElementById('authorSearchInput').addEventListener('input', e => {
        const v = e.target.value;
        clearTimeout(authorSearchTimer);
        authorSearchTimer = setTimeout(() => {
            state.authorFilterText = v;
            renderAuthorChips();
        }, 150);
    });

    document.getElementById('authorExpandToggle').addEventListener('click', () => {
        state.authorsExpanded = !state.authorsExpanded;
        renderAuthorChips();
    });

    // ---------- Search (debounced) ----------
    let searchTimer = null;
    document.getElementById('searchInput').addEventListener('input', e => {
        const v = e.target.value;
        clearTimeout(searchTimer);
        searchTimer = setTimeout(() => {
            state.search = v.trim();
            state.page = 0;
            state.authors.page = 0;
            state.series.page = 0;
            if (state.view === 'authors') {
                loadAuthorsView();
            } else if (state.view === 'series') {
                loadSeriesView();
            } else {
                loadGallery();
            }
        }, 350);
    });

    const searchTypeEl = document.getElementById('searchType');
    if (searchTypeEl) {
        searchTypeEl.addEventListener('change', e => {
            if (state.view !== 'all') {
                updateSearchPlaceholder();
                return;
            }
            const v = e.target.value;
            state.searchType = GALLERY_SEARCH_TYPE_VALUES.has(v) ? v : 'all';
            updateSearchPlaceholder();
            state.page = 0;
            state.authors.page = 0;
            state.series.page = 0;
            if (state.search) {
                loadGallery();
            } else {
                persistGalleryState();
            }
        });
    }

    // ---------- Gallery ----------
    async function loadGallery() {
        persistGalleryState();
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

        document.getElementById('galleryStatus').textContent = t('status.loading', '加载中...');
        try {
            const result = await api('/api/gallery/artworks?' + params.toString());
            state.totalPages = result.totalPages || 0;
            state.totalElements = result.totalElements || 0;
            // 先更新计数文案，确保即使列表为空也不会停留在「加载中…」
            const from = state.totalElements === 0 ? 0 : state.page * state.size + 1;
            const to = Math.min(state.totalElements, (state.page + 1) * state.size);
            document.getElementById('galleryStatus').textContent = t('status.gallery-range', '共 {total} 条，第 {from}-{to} 条', {
                total: state.totalElements,
                from,
                to
            });
            renderGallery(result.content || []);
            renderPagination();
            // 仅在存在有效搜索/筛选条件时才把搜索框与筛选按钮标红
            setSearchEmptyState(state.totalElements === 0 && hasActiveGalleryFilters());
        } catch (e) {
            document.getElementById('galleryStatus').textContent = t('status.load-failed', '加载失败：{message}', {message: e.message});
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
                    <div class="work-thumb">
                        <img data-src="/api/downloaded/thumbnail/${item.artworkId}/0" alt="${escapeHtml(item.title || '')}" loading="lazy">
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
            card.addEventListener('click', e => {
                if (e.target.closest('.thumb-heart')) return;
                window.location.href = `/pixiv-artwork.html?id=${id}`;
            });
            card.querySelector('.thumb-heart').addEventListener('click', e => {
                e.stopPropagation();
                openAddToCollectionModal(Number(id));
            });
        });

        // Lazy-load thumbnails as JSON base64
        grid.querySelectorAll('img[data-src]').forEach(img => {
            loadThumbnail(img);
        });

        loadMembershipsForCurrentPage(items.map(i => i.artworkId));
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
        } catch (e) {
            // Silently leave placeholder
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
            if (state.activeArtworkId) {
                await api(`/api/collections/${c.id}/artworks/${state.activeArtworkId}`, {method: 'POST'});
                state.collectionMembership.add(c.id);
            }
            renderAddToCollectionList();
            toast(t('toast.created', 'Created'), 'success');
        } catch (e) {
            toast(e.message || t('toast.create-failed', 'Creation failed'), 'error');
        } finally {
            btn.disabled = false;
        }
    });

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

    document.addEventListener('click', e => {
        const backdrop = e.target.classList && e.target.classList.contains('modal-backdrop');
        if (backdrop) e.target.classList.remove('open');
    });

    // ---------- View switch ----------
    document.querySelectorAll('.nav-item[data-view], .gallery-type-option[data-view]').forEach(el => {
        el.addEventListener('click', e => {
            e.preventDefault();
            switchView(el.dataset.view);
        });
    });

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

    document.getElementById('tagFilterClear').addEventListener('click', () => {
        clearTagFilter();
    });

    document.getElementById('authorFilterClear').addEventListener('click', () => {
        clearAuthorFilter();
    });

    document.getElementById('seriesFilterClear').addEventListener('click', () => {
        clearSeriesFilter();
    });

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
                        <div class="author-work-thumb">
                            <img data-src="/api/downloaded/thumbnail/${item.artworkId}/0" alt="${escapeHtml(item.title || '')}" loading="lazy">
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
                        <div class="author-work-thumb">
                            <img data-src="/api/downloaded/thumbnail/${item.artworkId}/0" alt="${escapeHtml(item.title || '')}" loading="lazy">
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

    // ---------- Boot ----------
    (async function init() {
        restoreSidebarState();

        // 立即渲染静态控件，避免主界面被网络请求阻塞
        renderFilterModeButtons('tag');
        renderFilterModeButtons('author');

        // i18n 在后台加载，加载完成后补丁式重渲染已到达的动态内容
        initPageI18n().then(() => {
            if (state.collections.length) renderCollections();
            if (state.tags.length) renderTagChips();
            if (state.seriesOptions.length) renderSeriesFilterChips();
            if (state.authorOptions.length) renderAuthorChips();
            applyGalleryStateToUi();
            setupTour(true);
        }).catch(err => console.error('i18n 加载失败', err));

        // 侧边栏与筛选选项数据请求并行触发
        loadCollections();
        loadAuthorOptions();
        loadSeriesOptions();
        const tagsPromise = loadTagOptions();

        // 仅当 URL 携带过滤参数时才需要等 tag 选项就绪后解析过滤；常规进入直接放行
        const params = new URLSearchParams(location.search);
        const hasNavigationFilter = GALLERY_FILTER_QUERY_KEYS.some(key => params.has(key));
        const shouldCreateCollection = params.get('createCollection') === '1';
        const urlView = readViewParam(params);

        // 如果 URL 没有显式筛选参数，则恢复持久化的状态（含分页 / 筛选 / 视图）
        if (!hasNavigationFilter) {
            restoreGalleryState();
            applyCrossTransferToGallery(consumeGalleryCrossTransfer());
        }

        let initialView = urlView;
        if (hasNavigationFilter) {
            await tagsPromise;
            initialView = await applyNavigationFiltersFromQuery() || initialView;
        }

        if (shouldCreateCollection) {
            openCollectionFormModal(null);
        }

        // 优先采用 URL 中的 view= 参数，否则使用恢复后的视图
        const targetView = normalizeView(initialView || state.view || 'all');
        state.view = targetView;
        applyGalleryStateToUi();
        applyGalleryViewVisibility();
        syncViewParamInUrl();
        syncViewNavigationHrefs();
        setupGalleryCrossPageHandoff();

        if (targetView === 'authors') {
            loadAuthorsView();
        } else if (targetView === 'series') {
            loadSeriesView();
        } else {
            loadGallery();
        }
    })();

    // ---------- 邀请访客 ----------
    (async function setupInviteEntry() {
        try {
            const ok = await fetch('/api/admin/invites/access-check', { credentials: 'same-origin' });
            if (!ok.ok) return;
        } catch (_) { return; }
        document.body.classList.add('admin-mode');

        const btn = document.getElementById('btnInviteGuest');
        if (!btn) return;
        btn.addEventListener('click', () => {
            const host = window.location.hostname;
            const isLocal = host === 'localhost' || host === '127.0.0.1' || host === '::1';
            if (isLocal) {
                const warning = pageI18n
                    ? pageI18n.t('invite:warning.local',
                        '当前服务器地址为本地（{host}），通过此按钮生成的链接他人可能无法访问。是否继续？',
                        { host })
                    : '当前服务器地址为本地（' + host
                        + '），通过此按钮生成的链接他人可能无法访问。是否继续？';
                if (!window.confirm(warning)) return;
            }
            openCreateModal();
        });

        async function fetchIllustTags() {
            const data = await api('/api/gallery/tags?limit=2000');
            return (data.tags || []).map(t => ({
                id: t.tagId,
                name: t.translatedName ? `${t.name} · ${t.translatedName}` : t.name,
                secondary: ''
            }));
        }
        async function fetchPagedAuthors(endpoint) {
            const all = [];
            let page = 0; let totalPages = 1;
            while (page < totalPages && page < 50) {
                const data = await api(`${endpoint}?page=${page}&size=200&sort=name`);
                const list = data.items || data.content || data.authors || data.data || [];
                for (const a of list) {
                    const id = a.authorId != null ? a.authorId : a.id;
                    if (id == null) continue;
                    all.push({ id, name: a.name || ('#' + id), secondary: '' });
                }
                totalPages = data.totalPages != null ? data.totalPages
                    : (list.length === 200 ? page + 2 : page + 1);
                page++;
                if (list.length === 0) break;
            }
            return all;
        }
        async function fetchNovelTags() {
            const data = await api('/api/gallery/novels/tags?limit=2000');
            return (data.tags || []).map(t => ({
                id: t.tagId,
                name: t.translatedName ? `${t.name} · ${t.translatedName}` : t.name,
                secondary: ''
            }));
        }

        function openCreateModal() {
            const tr = (key, fallback, vars) => pageI18n
                ? pageI18n.t('invite:' + key, fallback, vars)
                : fallback;
            window.InviteModals.openInviteFormModal({
                title: tr('modal.create.title', '邀请访客'),
                submitText: tr('modal.create.submit', '创建邀请链接'),
                fetchTags: fetchIllustTags,
                fetchAuthors: () => fetchPagedAuthors('/api/authors/paged'),
                fetchNovelTags: fetchNovelTags,
                fetchNovelAuthors: () => fetchPagedAuthors('/api/gallery/novels/authors'),
                onSubmit: async (payload) => {
                    const result = await api('/api/admin/invites', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify(payload),
                    });
                    window.InviteModals.openInviteResultModal({ code: result.code, url: result.url });
                }
            });
        }
    })();
