'use strict';

const NOVEL_GALLERY_STATE_STORAGE_KEY = 'pixiv:novel-gallery-state-v1';
const GALLERY_CROSS_TRANSFER_KEY = 'pixiv:gallery-cross-transfer-v1';
const GALLERY_CROSS_TRANSFER_TTL_MS = 60_000;
const FILTER_SELECTION_RANK = { must: 0, not: 1, or: 2 };
const NOVEL_SORT_VALUES = new Set(['date', 'novelId', 'wordCount', 'series']);
const NOVEL_R18_VALUES = new Set(['any', 'r18plus', 'r18', 'r18g', 'no']);
const NOVEL_AI_VALUES = new Set(['any', 'yes', 'no']);
const NOVEL_SEARCH_TYPE_VALUES = new Set(['all', 'title', 'author', 'id', 'authorId', 'desc', 'content', 'tag', 'tagExact']);
const FILTER_MODE_VALUES = new Set(['must', 'not', 'or']);
const NOVEL_URL_FILTER_KEYS = [
    'tagId', 'tagIds', 'filterTagId', 'tagName', 'tagTranslatedName', 'filterTag', 'filterTagTranslated',
    'seriesId', 'seriesIds', 'seriesTitle',
    'collectionIds', 'createCollection', 'openFilter'
];
const state = {
    view: 'all',                // 'all' | 'authors' | 'series'
    page: 0,
    sort: 'date',
    order: 'desc',
    r18: 'any',
    ai: 'any',
    search: '',
    searchType: 'all',
    collections: [],
    selectedCollections: new Set(),
    tags: [],
    tagNames: new Map(),
    tagTranslatedNames: new Map(),
    selectedTags: new Map(),    // tagId -> 'must'|'not'|'or'
    tagMode: 'must',
    tagSearch: '',
    series: [],
    selectedSeries: new Map(),  // seriesId -> 'must'|'not'
    seriesMode: 'must',
    seriesSearch: '',
    authors: [],
    authorNames: new Map(),
    selectedAuthors: new Map(), // authorId -> 'must'|'not'|'or'
    authorMode: 'must',
    authorSearch: '',
    exclusiveAuthorId: null,
    exclusiveAuthorName: '',
    totalElements: 0,
    authorsView: { page: 0, totalPages: 0, content: [] },
    seriesView: { page: 0, totalPages: 0, content: [] },
    editingCollection: null,
    pendingIconFile: null,
    pendingIconClear: false
};

// 批量管理（多选删除）：仅作用于「全部小说」主网格，选择集随翻页 / 切换视图清空。
const batch = {
    active: false,
    selected: new Set(),
    excluded: new Set(),
    mode: 'ids',
    filterSnapshot: null,
    filterKey: '',
    total: 0,
};
let isNovelAdmin = false;

function selectionMapToEntries(map) {
    return Array.from(map.entries()).map(([id, mode]) => [Number(id), mode]);
}

function partitionSelectionMap(map) {
    const must = [], not = [], or = [];
    map.forEach((mode, id) => {
        const numId = Number(id);
        if (!Number.isFinite(numId) || numId <= 0) return;
        if (mode === 'must') must.push(numId);
        else if (mode === 'not') not.push(numId);
        else if (mode === 'or') or.push(numId);
    });
    return { must, not, or };
}

function serializeNovelGalleryState() {
    return {
        view: state.view,
        page: state.page,
        sort: state.sort,
        order: state.order,
        r18: state.r18,
        ai: state.ai,
        search: state.search,
        searchType: state.searchType,
        tagSearch: state.tagSearch,
        seriesSearch: state.seriesSearch,
        authorSearch: state.authorSearch,
        tagMode: state.tagMode,
        seriesMode: state.seriesMode,
        authorMode: state.authorMode,
        selectedCollections: [...state.selectedCollections],
        selectedTags: selectionMapToEntries(state.selectedTags),
        selectedSeries: selectionMapToEntries(state.selectedSeries),
        selectedAuthors: selectionMapToEntries(state.selectedAuthors),
        exclusiveAuthorId: state.exclusiveAuthorId,
        exclusiveAuthorName: state.exclusiveAuthorName,
        tagNames: [...state.tagNames],
        tagTranslatedNames: [...state.tagTranslatedNames],
        authorNames: [...state.authorNames],
        authorsViewPage: state.authorsView.page,
        seriesViewPage: state.seriesView.page,
    };
}

let persistNovelTimer = null;
function persistNovelGalleryState() {
    clearTimeout(persistNovelTimer);
    persistNovelTimer = setTimeout(() => {
        try {
            storageSet(NOVEL_GALLERY_STATE_STORAGE_KEY, JSON.stringify(serializeNovelGalleryState()));
        } catch (_) { /* ignore */ }
    }, 80);
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

function applySelectionEntries(entries, target, allowOr = true) {
    target.clear();
    if (!Array.isArray(entries)) return;
    entries.forEach(entry => {
        if (!Array.isArray(entry) || entry.length < 2) return;
        const id = Number(entry[0]);
        const mode = entry[1];
        if (!Number.isFinite(id) || id <= 0) return;
        if (mode !== 'must' && mode !== 'not' && (!allowOr || mode !== 'or')) return;
        target.set(id, mode);
    });
}

function applyPersistedNovelGalleryState(payload) {
    if (!payload || typeof payload !== 'object') return;
    if (typeof payload.view === 'string' && GALLERY_VIEW_VALUES.includes(payload.view)) state.view = payload.view;
    if (Number.isInteger(payload.page) && payload.page >= 0) state.page = payload.page;
    if (typeof payload.sort === 'string' && NOVEL_SORT_VALUES.has(payload.sort)) state.sort = payload.sort;
    if (payload.order === 'asc' || payload.order === 'desc') state.order = payload.order;
    if (typeof payload.r18 === 'string' && NOVEL_R18_VALUES.has(payload.r18)) state.r18 = payload.r18;
    if (typeof payload.ai === 'string' && NOVEL_AI_VALUES.has(payload.ai)) state.ai = payload.ai;
    if (typeof payload.search === 'string') state.search = payload.search;
    if (typeof payload.searchType === 'string' && NOVEL_SEARCH_TYPE_VALUES.has(payload.searchType)) {
        state.searchType = payload.searchType;
    }
    if (typeof payload.tagSearch === 'string') state.tagSearch = payload.tagSearch;
    if (typeof payload.seriesSearch === 'string') state.seriesSearch = payload.seriesSearch;
    if (typeof payload.authorSearch === 'string') state.authorSearch = payload.authorSearch;
    if (typeof payload.tagMode === 'string' && FILTER_MODE_VALUES.has(payload.tagMode)) state.tagMode = payload.tagMode;
    if (typeof payload.seriesMode === 'string' && FILTER_MODE_VALUES.has(payload.seriesMode)) state.seriesMode = payload.seriesMode;
    if (typeof payload.authorMode === 'string' && FILTER_MODE_VALUES.has(payload.authorMode)) state.authorMode = payload.authorMode;
    state.selectedCollections = new Set(toIdArray(payload.selectedCollections));
    applySelectionEntries(payload.selectedTags, state.selectedTags, true);
    applySelectionEntries(payload.selectedSeries, state.selectedSeries, false);
    applySelectionEntries(payload.selectedAuthors, state.selectedAuthors, true);
    if (Number.isInteger(payload.exclusiveAuthorId) && payload.exclusiveAuthorId > 0) {
        state.exclusiveAuthorId = payload.exclusiveAuthorId;
        state.exclusiveAuthorName = typeof payload.exclusiveAuthorName === 'string' ? payload.exclusiveAuthorName : '';
    } else {
        state.exclusiveAuthorId = null;
        state.exclusiveAuthorName = '';
    }
    rememberNameEntries(payload.tagNames, state.tagNames);
    rememberNameEntries(payload.tagTranslatedNames, state.tagTranslatedNames);
    rememberNameEntries(payload.authorNames, state.authorNames);
    if (Number.isInteger(payload.authorsViewPage) && payload.authorsViewPage >= 0) state.authorsView.page = payload.authorsViewPage;
    if (Number.isInteger(payload.seriesViewPage) && payload.seriesViewPage >= 0) state.seriesView.page = payload.seriesViewPage;
}

function restoreNovelGalleryState() {
    const raw = storageGet(NOVEL_GALLERY_STATE_STORAGE_KEY);
    if (!raw) return false;
    try {
        applyPersistedNovelGalleryState(JSON.parse(raw));
        return true;
    } catch (_) {
        return false;
    }
}

function writeNovelGalleryCrossTransfer() {
    const tagBuckets = partitionSelectionMap(state.selectedTags);
    const authorBuckets = partitionSelectionMap(state.selectedAuthors);
    if (state.exclusiveAuthorId != null && Number.isFinite(Number(state.exclusiveAuthorId))) {
        const exId = Number(state.exclusiveAuthorId);
        if (!authorBuckets.must.includes(exId)) authorBuckets.must.push(exId);
    }
    const payload = {
        from: 'novel',
        timestamp: Date.now(),
        view: state.view,
        search: state.search,
        searchType: state.searchType,
        r18: state.r18,
        ai: state.ai,
        sort: state.sort,
        order: state.order,
        collectionIds: [...state.selectedCollections],
        tagMode: state.tagMode,
        tagIds: tagBuckets,
        authorMode: state.authorMode,
        authorIds: authorBuckets,
        tagNames: [...state.tagNames],
        tagTranslatedNames: [...state.tagTranslatedNames],
        authorNames: [...state.authorNames],
    };
    try {
        storageSet(GALLERY_CROSS_TRANSFER_KEY, JSON.stringify(payload));
    } catch (_) { /* ignore */ }
}

function consumeNovelGalleryCrossTransfer() {
    const raw = storageGet(GALLERY_CROSS_TRANSFER_KEY);
    if (!raw) return null;
    storageRemove(GALLERY_CROSS_TRANSFER_KEY);
    try {
        const payload = JSON.parse(raw);
        if (!payload || typeof payload !== 'object') return null;
        // novel page only accepts payloads originating from the illust gallery
        if (payload.from === 'novel') return null;
        if (typeof payload.timestamp !== 'number' || Date.now() - payload.timestamp > GALLERY_CROSS_TRANSFER_TTL_MS) return null;
        return payload;
    } catch (_) { return null; }
}

function applyCrossTransferToNovelGallery(transfer) {
    if (!transfer) return;
    if (typeof transfer.search === 'string') state.search = transfer.search;
    if (typeof transfer.searchType === 'string' && NOVEL_SEARCH_TYPE_VALUES.has(transfer.searchType)) {
        state.searchType = transfer.searchType;
    }
    if (typeof transfer.r18 === 'string' && NOVEL_R18_VALUES.has(transfer.r18)) state.r18 = transfer.r18;
    if (typeof transfer.ai === 'string' && NOVEL_AI_VALUES.has(transfer.ai)) state.ai = transfer.ai;
    if (typeof transfer.sort === 'string' && NOVEL_SORT_VALUES.has(transfer.sort)) state.sort = transfer.sort;
    if (transfer.order === 'asc' || transfer.order === 'desc') state.order = transfer.order;
    if (typeof transfer.view === 'string' && GALLERY_VIEW_VALUES.includes(transfer.view)) state.view = transfer.view;
    state.selectedCollections = new Set(toIdArray(transfer.collectionIds));
    const tagIds = transfer.tagIds || {};
    state.selectedTags.clear();
    toIdArray(tagIds.must).forEach(id => state.selectedTags.set(id, 'must'));
    toIdArray(tagIds.not).forEach(id => state.selectedTags.set(id, 'not'));
    toIdArray(tagIds.or).forEach(id => state.selectedTags.set(id, 'or'));
    if (typeof transfer.tagMode === 'string' && FILTER_MODE_VALUES.has(transfer.tagMode)) state.tagMode = transfer.tagMode;
    const authorIds = transfer.authorIds || {};
    state.selectedAuthors.clear();
    toIdArray(authorIds.must).forEach(id => state.selectedAuthors.set(id, 'must'));
    toIdArray(authorIds.not).forEach(id => state.selectedAuthors.set(id, 'not'));
    toIdArray(authorIds.or).forEach(id => state.selectedAuthors.set(id, 'or'));
    if (typeof transfer.authorMode === 'string' && FILTER_MODE_VALUES.has(transfer.authorMode)) state.authorMode = transfer.authorMode;
    rememberNameEntries(transfer.tagNames, state.tagNames);
    rememberNameEntries(transfer.tagTranslatedNames, state.tagTranslatedNames);
    rememberNameEntries(transfer.authorNames, state.authorNames);
    // Different ID spaces for series; clear cross-page-incompatible state.
    state.selectedSeries.clear();
    state.exclusiveAuthorId = null;
    state.exclusiveAuthorName = '';
    state.page = 0;
    state.authorsView.page = 0;
    state.seriesView.page = 0;
}

function applyNovelGalleryStateToUi() {
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
    document.querySelectorAll('#tagModeButtons .filter-mode-btn').forEach(btn => {
        btn.classList.toggle('active', btn.dataset.filterMode === state.tagMode);
    });
    document.querySelectorAll('#authorModeButtons .filter-mode-btn').forEach(btn => {
        btn.classList.toggle('active', btn.dataset.filterMode === state.authorMode);
    });
    const searchInput = document.getElementById('searchInput');
    if (searchInput) searchInput.value = state.search || '';
    const searchTypeSelect = document.getElementById('searchType');
    if (searchTypeSelect) searchTypeSelect.value = state.searchType || 'all';
    updateSearchPlaceholder();
    const tagSearchInput = document.getElementById('tagSearchInput');
    if (tagSearchInput) tagSearchInput.value = state.tagSearch || '';
    const seriesSearchInput = document.getElementById('seriesSearchInput');
    if (seriesSearchInput) seriesSearchInput.value = state.seriesSearch || '';
    const authorSearchInput = document.getElementById('authorSearchInput');
    if (authorSearchInput) authorSearchInput.value = state.authorSearch || '';
    renderCollections();
    renderCollectionFilterChips();
    renderTagChips();
    renderSeriesChips();
    renderAuthorChips();
    updateAuthorFilterBar();
    updateFilterBadge();
}

function getSelectionRank(selectedMap, id) {
    const mode = selectedMap.get(Number(id));
    return mode ? (FILTER_SELECTION_RANK[mode] ?? 3) : 3;
}

function getSelectionIndex(selectedMap, id) {
    const normalizedId = Number(id);
    let index = 0;
    for (const key of selectedMap.keys()) {
        if (Number(key) === normalizedId) return index;
        index++;
    }
    return Number.MAX_SAFE_INTEGER;
}

function compareFilterSelectionOrder(selectedMap, leftId, rightId) {
    const rankDiff = getSelectionRank(selectedMap, leftId) - getSelectionRank(selectedMap, rightId);
    if (rankDiff !== 0) return rankDiff;
    if (!selectedMap.has(Number(leftId)) && !selectedMap.has(Number(rightId))) return 0;
    return getSelectionIndex(selectedMap, leftId) - getSelectionIndex(selectedMap, rightId);
}
