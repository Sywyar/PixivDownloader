'use strict';

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
        tagsLoaded: false,
        tagNames: new Map(),
        tagTranslatedNames: new Map(),
        tagFilterText: '',
        tagsExpanded: false,
        authorOptions: [],
        authorOptionsLoaded: false,
        authorFilterText: '',
        authorsExpanded: false,
        seriesOptions: [],
        seriesOptionsLoaded: false,
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

    // 批量管理（多选删除）：仅作用于「全部图片」主网格，选择集随翻页 / 切换视图清空。
    const batch = {
        active: false,
        selected: new Set(),
        excluded: new Set(),
        mode: 'ids',
        filterSnapshot: null,
        filterKey: '',
        total: 0,
    };
    let isGalleryAdmin = false;

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
    let tagOptionsPromise = null;
    let authorOptionsPromise = null;
    let seriesOptionsPromise = null;

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
        // 批量管理仅适用于「全部图片」主网格：切到作者/系列视图时隐藏入口并退出选择模式。
        if (!showAll && batch.active) exitBatchMode();
        updateBatchButtonVisibility();
        updateSearchPlaceholder();
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


// ---- PixivGallery facade ----
window.PixivGallery.state = Object.assign(window.PixivGallery.state || {}, { serializeGalleryState, persistGalleryState, toIdArray, applyPersistedGalleryState, rememberNameEntries, restoreGalleryState, writeGalleryCrossTransfer, consumeGalleryCrossTransfer, applyCrossTransferToGallery, applyGalleryStateToUi, applyGalleryViewVisibility, readViewParam, normalizeView, buildGalleryPageHref, syncViewParamInUrl, syncViewNavigationHrefs });
