const PAGE_SIZE = 24;
const HEART_SVG = '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z"/></svg>';
const SIDEBAR_STATE_STORAGE_KEY = 'pixiv:gallery-sidebar-state';
const NOVEL_GALLERY_STATE_STORAGE_KEY = 'pixiv:novel-gallery-state-v1';
const GALLERY_CROSS_TRANSFER_KEY = 'pixiv:gallery-cross-transfer-v1';
const GALLERY_CROSS_TRANSFER_TTL_MS = 60_000;
const GALLERY_VIEW_VALUES = ['all', 'authors', 'series'];
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

function parsePositiveIdList(raw) {
    return [...new Set(String(raw || '')
        .split(',')
        .map(value => Number(value))
        .filter(value => Number.isInteger(value) && value > 0))];
}

function normalizeFilterText(value) {
    return String(value || '').trim().toLowerCase();
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
            el.setAttribute('href', buildGalleryPageHref('/pixiv-novel-gallery.html', el.dataset.view));
        }
    });
    const artworkGalleryLink = document.querySelector('.gallery-type-switch a[href^="/pixiv-gallery.html"]');
    if (artworkGalleryLink) {
        artworkGalleryLink.setAttribute('href', buildGalleryPageHref('/pixiv-gallery.html', state.view));
    }
}

let pageI18n;
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
    authorsView: { page: 0, totalPages: 0, content: [] },
    seriesView: { page: 0, totalPages: 0, content: [] },
    editingCollection: null,
    pendingIconFile: null,
    pendingIconClear: false
};

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

function toIdArray(value) {
    if (!Array.isArray(value)) return [];
    return value.map(Number).filter(n => Number.isFinite(n) && n > 0);
}

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

function searchPlaceholderFor(type) {
    switch (type) {
        case 'title': return pageI18n.t('gallery:search.placeholder.title', '按作品标题搜索...');
        case 'author': return pageI18n.t('gallery:search.placeholder.author', '按作者名搜索...');
        case 'id': return pageI18n.t('novel:search.placeholder.id', '输入小说 ID（数字，精确匹配）...');
        case 'authorId': return pageI18n.t('gallery:search.placeholder.author-id', '输入作者 ID（数字，精确匹配）...');
        case 'desc': return pageI18n.t('gallery:search.placeholder.desc', '按作品简介搜索...');
        case 'content': return pageI18n.t('novel:search.placeholder.content', '按正文关键词全文检索...');
        case 'tag': return pageI18n.t('gallery:search.placeholder.tag', '按标签关键词搜索（模糊）...');
        case 'tagExact': return pageI18n.t('gallery:search.placeholder.tag-exact', '输入完整标签名（精确匹配）...');
        default: return pageI18n.t('novel:search.placeholder', '按标题搜索...');
    }
}

function updateSearchPlaceholder() {
    const el = document.getElementById('searchInput');
    if (el && pageI18n) el.placeholder = searchPlaceholderFor(state.searchType || 'all');
}

function setSearchEmptyState(empty) {
    const box = document.querySelector('.search-box');
    const btn = document.getElementById('filterToggle');
    if (box) box.classList.toggle('search-no-result', !!empty);
    if (btn) btn.classList.toggle('search-no-result', !!empty);
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

function setupNovelCrossPageHandoff() {
    document.querySelectorAll('.gallery-type-switch a[href]').forEach(link => {
        link.addEventListener('click', () => {
            writeNovelGalleryCrossTransfer();
        });
    });
}

function rememberTagOption(tag) {
    if (!tag || tag.tagId == null) return;
    const id = Number(tag.tagId);
    if (!Number.isFinite(id)) return;
    if (tag.name) state.tagNames.set(id, tag.name);
    if (tag.translatedName) state.tagTranslatedNames.set(id, tag.translatedName);
}

function rememberAuthorOption(author) {
    if (!author || author.authorId == null) return;
    const id = Number(author.authorId);
    if (!Number.isFinite(id)) return;
    if (author.name) state.authorNames.set(id, author.name);
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

async function init() {
    restoreSidebarState();

    pageI18n = await PixivI18n.create({ namespaces: ['gallery', 'novel', 'common'] });
    pageI18n.apply();
    updateSearchPlaceholder();
    updateOrderToggleLabel();
    await PixivLangSwitcher.mount({
        mountPoint: document.getElementById('langSwitcherAnchor'),
        i18n: pageI18n,
        onChange: (next) => {
            pageI18n = next;
            pageI18n.apply();
            updateSearchPlaceholder();
            updateOrderToggleLabel();
            syncCollectionFormTitle();
            reloadCurrentView();
        }
    });
    PixivTheme.mount({ mountPoint: document.getElementById('langSwitcherAnchor') });
    setupEventHandlers();
    setupAdminMode();

    // 在拉取列表数据前恢复持久化的状态（搜索文字会影响 loadTags / loadAuthors / loadSeries 请求）
    const params = new URLSearchParams(location.search);
    const hasUrlFilters = NOVEL_URL_FILTER_KEYS.some(key => params.has(key));
    if (!hasUrlFilters) {
        restoreNovelGalleryState();
        applyCrossTransferToNovelGallery(consumeNovelGalleryCrossTransfer());
    }

    await loadCollections();
    await loadTags();
    await loadSeries();
    await loadAuthors();
    // URL 中的导航筛选参数优先级最高（含 view 切换）
    applyInitialUrlState();
    applyNovelGalleryStateToUi();
    setActiveViewNav();
    syncViewParamInUrl();
    setupNovelCrossPageHandoff();
    reloadCurrentView();
}

function applyInitialUrlState() {
    const params = new URLSearchParams(location.search);
    let changed = false;

    const ids = parsePositiveIdList(
        [params.get('seriesId'), params.get('seriesIds')]
            .filter(Boolean)
            .join(',')
    );
    if (ids.length) {
        state.selectedSeries.clear();
        ids.forEach(id => state.selectedSeries.set(id, 'must'));
        if (ids.length === 1 && !state.series.some(s => Number(s.seriesId) === ids[0])) {
            const title = params.get('seriesTitle') || `#${ids[0]}`;
            state.series = [{ seriesId: ids[0], title, authorId: null, authorName: '', novelCount: 0 }, ...state.series];
        }
        renderSeriesChips();
        changed = true;
    }

    let tagIds = parsePositiveIdList(
        [params.get('tagId'), params.get('tagIds'), params.get('filterTagId')]
            .filter(Boolean)
            .join(',')
    );
    const tagName = params.get('tagName') || params.get('filterTag') || '';
    const translatedName = params.get('tagTranslatedName') || params.get('filterTagTranslated') || '';
    if (!tagIds.length) {
        const normalizedTagName = normalizeFilterText(tagName);
        const normalizedTranslatedName = normalizeFilterText(translatedName);
        const loaded = state.tags.find(t =>
            (normalizedTagName && normalizeFilterText(t.name) === normalizedTagName)
            || (normalizedTranslatedName && normalizeFilterText(t.translatedName) === normalizedTranslatedName));
        if (loaded && Number(loaded.tagId) > 0) {
            tagIds = [Number(loaded.tagId)];
        }
    }
    if (tagIds.length) {
        state.selectedTags.clear();
        tagIds.forEach(id => state.selectedTags.set(id, 'must'));
        if (tagIds.length === 1 && !state.tags.some(t => Number(t.tagId) === tagIds[0])) {
            state.tags = [{ tagId: tagIds[0], name: tagName || `#${tagIds[0]}`, translatedName, novelCount: 0 }, ...state.tags];
        }
        renderTagChips();
        changed = true;
    }

    const collectionIds = parsePositiveIdList(params.get('collectionIds'));
    if (collectionIds.length) {
        state.selectedCollections.clear();
        collectionIds.forEach(id => state.selectedCollections.add(id));
        renderCollections();
        renderCollectionFilterChips();
        changed = true;
    }

    const requestedView = readViewParam(params);
    if (requestedView && requestedView !== state.view) {
        state.view = requestedView;
        state.page = 0;
        state.authorsView.page = 0;
        state.seriesView.page = 0;
    }

    if (params.get('createCollection') === '1') {
        openCollectionFormModal(null);
    }

    setFilterPanelOpen(params.get('openFilter') === '1');
    if (changed) updateFilterBadge();
}

function setFilterPanelOpen(open) {
    document.getElementById('filterPanel').classList.toggle('open', open);
    document.getElementById('filterToggle').classList.toggle('active', open);
}

function updateOrderToggleLabel() {
    const btn = document.getElementById('orderToggle');
    if (!btn) return;
    const key = btn.dataset.order === 'asc' ? 'filter.order.asc' : 'filter.order.desc';
    btn.textContent = pageI18n ? pageI18n.t(key, btn.textContent) : btn.textContent;
}

function setupEventHandlers() {
    // Search
    document.getElementById('searchInput').addEventListener('input', debounce(() => {
        state.search = document.getElementById('searchInput').value.trim();
        state.page = 0;
        reloadCurrentView();
    }, 250));
    const searchTypeEl = document.getElementById('searchType');
    if (searchTypeEl) {
        searchTypeEl.addEventListener('change', () => {
            const v = searchTypeEl.value;
            state.searchType = NOVEL_SEARCH_TYPE_VALUES.has(v) ? v : 'all';
            updateSearchPlaceholder();
            state.page = 0;
            if (state.search) {
                reloadCurrentView();
            } else {
                persistNovelGalleryState();
            }
        });
    }
    // Filter toggle
    document.getElementById('filterToggle').addEventListener('click', () => {
        const panel = document.getElementById('filterPanel');
        setFilterPanelOpen(!panel.classList.contains('open'));
    });
    // Sort chips
    document.querySelectorAll('.chip[data-sort]').forEach(chip => {
        chip.addEventListener('click', () => {
            document.querySelectorAll('.chip[data-sort]').forEach(c => c.classList.remove('active'));
            chip.classList.add('active');
            state.sort = chip.dataset.sort;
            state.page = 0;
            reloadCurrentView();
        });
    });
    // Order toggle
    document.getElementById('orderToggle').addEventListener('click', () => {
        const btn = document.getElementById('orderToggle');
        const next = btn.dataset.order === 'desc' ? 'asc' : 'desc';
        btn.dataset.order = next;
        updateOrderToggleLabel();
        state.order = next;
        state.page = 0;
        reloadCurrentView();
    });
    // R18 chips
    document.querySelectorAll('.chip[data-r18]').forEach(chip => {
        chip.addEventListener('click', () => {
            document.querySelectorAll('.chip[data-r18]').forEach(c => c.classList.remove('active'));
            chip.classList.add('active');
            state.r18 = chip.dataset.r18;
            state.page = 0;
            updateFilterBadge();
            reloadCurrentView();
        });
    });
    // AI chips
    document.querySelectorAll('.chip[data-ai]').forEach(chip => {
        chip.addEventListener('click', () => {
            document.querySelectorAll('.chip[data-ai]').forEach(c => c.classList.remove('active'));
            chip.classList.add('active');
            state.ai = chip.dataset.ai;
            state.page = 0;
            updateFilterBadge();
            reloadCurrentView();
        });
    });
    // Mode group buttons (tag/author/series — series only must/not)
    bindModeGroup('tagModeButtons', mode => { state.tagMode = mode; });
    bindModeGroup('authorModeButtons', mode => { state.authorMode = mode; });
    // Search inputs
    document.getElementById('tagSearchInput').addEventListener('input', debounce(() => {
        state.tagSearch = document.getElementById('tagSearchInput').value.trim();
        loadTags();
    }, 250));
    document.getElementById('seriesSearchInput').addEventListener('input', debounce(() => {
        state.seriesSearch = document.getElementById('seriesSearchInput').value.trim();
        loadSeries();
    }, 250));
    document.getElementById('authorSearchInput').addEventListener('input', debounce(() => {
        state.authorSearch = document.getElementById('authorSearchInput').value.trim();
        loadAuthors();
    }, 250));
    // Reset / expand
    document.getElementById('tagFilterReset').addEventListener('click', () => { state.selectedTags.clear(); renderTagChips(); state.page = 0; updateFilterBadge(); reloadCurrentView(); });
    document.getElementById('seriesFilterReset').addEventListener('click', () => { state.selectedSeries.clear(); renderSeriesChips(); state.page = 0; updateFilterBadge(); reloadCurrentView(); });
    document.getElementById('authorFilterReset').addEventListener('click', () => { state.selectedAuthors.clear(); state.exclusiveAuthorId = null; renderAuthorChips(); updateAuthorFilterBar(); state.page = 0; updateFilterBadge(); reloadCurrentView(); });
    document.getElementById('tagExpandToggle').addEventListener('click', () => toggleExpand('filterTagChips', 'tagExpandToggle'));
    document.getElementById('seriesExpandToggle').addEventListener('click', () => toggleExpand('filterSeriesChips', 'seriesExpandToggle'));
    document.getElementById('authorExpandToggle').addEventListener('click', () => toggleExpand('filterAuthorChips', 'authorExpandToggle'));
    document.getElementById('filterReset').addEventListener('click', resetAllFilters);
    document.getElementById('authorFilterClear').addEventListener('click', () => {
        state.exclusiveAuthorId = null; state.selectedAuthors.clear(); renderAuthorChips(); updateAuthorFilterBar(); state.page = 0; updateFilterBadge(); reloadCurrentView();
    });

    // View nav
    document.querySelectorAll('.nav-item[data-view], .gallery-type-option[data-view]').forEach(el => {
        el.addEventListener('click', e => {
            e.preventDefault();
            switchView(el.dataset.view);
        });
    });

    document.getElementById('btnCreateCollection').addEventListener('click', () => openCollectionFormModal(null));
    document.getElementById('collectionFormClose').addEventListener('click', closeCollectionFormModal);
    document.getElementById('collectionFormCancel').addEventListener('click', closeCollectionFormModal);
    document.getElementById('collectionFormIconChoose').addEventListener('click', () => {
        document.getElementById('collectionFormIconFile').click();
    });
    document.getElementById('collectionFormIconFile').addEventListener('change', e => {
        const file = e.target.files[0];
        if (!file) return;
        if (file.size > 1024 * 1024) {
            toast(pageI18n.t('gallery:toast.icon-too-large', '图标大小不能超过 1MB'), 'error');
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
    document.getElementById('collectionFormName').addEventListener('input', renderCollectionDownloadRootPreview);
    document.getElementById('collectionDownloadRoot').addEventListener('input', renderCollectionDownloadRootPreview);
    document.getElementById('collectionDownloadRootEnabled').addEventListener('change', syncCollectionDownloadRootControls);
    document.getElementById('collectionFormSubmit').addEventListener('click', submitCollectionForm);
    document.getElementById('modalCollectionForm').addEventListener('click', e => {
        if (e.target.id === 'modalCollectionForm') closeCollectionFormModal();
    });
}

function bindModeGroup(groupId, onChange) {
    const group = document.getElementById(groupId);
    group.querySelectorAll('.filter-mode-btn').forEach(btn => {
        btn.addEventListener('click', () => {
            group.querySelectorAll('.filter-mode-btn').forEach(b => b.classList.remove('active'));
            btn.classList.add('active');
            onChange(btn.dataset.filterMode);
        });
    });
}

function toggleExpand(chipsId, btnId) {
    const el = document.getElementById(chipsId);
    el.classList.toggle('expanded');
    document.getElementById(btnId).textContent = el.classList.contains('expanded')
        ? pageI18n.t('filter.collapse', '收起')
        : pageI18n.t('filter.expand', '展开全部');
}

function resetAllFilters() {
    state.r18 = 'any'; state.ai = 'any';
    document.querySelectorAll('.chip[data-r18]').forEach(c => c.classList.toggle('active', c.dataset.r18 === 'any'));
    document.querySelectorAll('.chip[data-ai]').forEach(c => c.classList.toggle('active', c.dataset.ai === 'any'));
    state.selectedCollections.clear();
    state.selectedTags.clear(); state.selectedSeries.clear(); state.selectedAuthors.clear();
    state.exclusiveAuthorId = null;
    renderCollections(); renderTagChips(); renderSeriesChips(); renderAuthorChips();
    renderCollectionFilterChips(); updateAuthorFilterBar();
    state.page = 0;
    updateFilterBadge();
    reloadCurrentView();
}

function updateFilterBadge() {
    let n = 0;
    if (state.r18 !== 'any') n++;
    if (state.ai !== 'any') n++;
    if (state.selectedCollections.size) n += state.selectedCollections.size;
    if (state.selectedTags.size) n += state.selectedTags.size;
    if (state.selectedSeries.size) n += state.selectedSeries.size;
    if (state.selectedAuthors.size) n += state.selectedAuthors.size;
    if (state.exclusiveAuthorId != null) n++;
    const badge = document.getElementById('filterBadge');
    badge.textContent = String(n);
    badge.classList.toggle('visible', n > 0);
}

let lastTimer;
function debounce(fn, ms) {
    return (...args) => { clearTimeout(lastTimer); lastTimer = setTimeout(() => fn(...args), ms); };
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

async function setupAdminMode() {
    try {
        const ok = await fetch('/api/admin/invites/access-check', { credentials: 'same-origin' });
        if (ok.ok) document.body.classList.add('admin-mode');
    } catch (_) { /* not admin */ }
}

// ---------- Collection CRUD ----------
async function collectionApi(url, options = {}) {
    const r = await fetch(url, {
        credentials: 'same-origin',
        ...options,
        headers: {
            'Accept': 'application/json',
            ...(options.headers || {})
        }
    });
    if (r.status === 401) {
        window.location.href = '/login.html?redirect=' + encodeURIComponent(location.pathname + location.search);
        throw new Error('Unauthorized');
    }
    if (!r.ok) {
        let msg = 'HTTP ' + r.status;
        try {
            const data = await r.json();
            if (data && data.error) msg = data.error;
            else if (data && data.message) msg = data.message;
        } catch (_) { /* keep HTTP status */ }
        throw new Error(msg);
    }
    const ct = r.headers.get('content-type') || '';
    return ct.includes('application/json') ? r.json() : r.text();
}

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

function syncCollectionFormTitle() {
    const title = document.getElementById('collectionFormTitle');
    if (!title || !pageI18n) return;
    title.textContent = state.editingCollection
        ? pageI18n.t('gallery:collection.form.edit', '编辑收藏夹')
        : pageI18n.t('gallery:collection.form.create', '新建收藏夹');
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
    if (!document.getElementById('collectionDownloadRootEnabled').checked) return null;
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

async function submitCollectionForm() {
    const name = document.getElementById('collectionFormName').value.trim();
    if (!name) {
        toast(pageI18n.t('gallery:toast.name-required', '请输入名称'), 'error');
        return;
    }
    const downloadRoot = readCollectionDownloadRoot();
    if (document.getElementById('collectionDownloadRootEnabled').checked && !downloadRoot) {
        toast(pageI18n.t('gallery:toast.download-root-required', '请填写下载目录'), 'error');
        return;
    }
    if (downloadRoot && hasControlCharacter(downloadRoot)) {
        toast(pageI18n.t('gallery:toast.download-root-invalid', '下载目录不能包含控制字符'), 'error');
        return;
    }

    const btn = document.getElementById('collectionFormSubmit');
    btn.disabled = true;
    try {
        let collection;
        if (state.editingCollection) {
            await collectionApi(`/api/collections/${state.editingCollection.id}`, {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ name })
            });
            await collectionApi(`/api/collections/${state.editingCollection.id}/download-root`, {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ downloadRoot })
            });
            collection = { ...state.editingCollection, name, downloadRoot };
        } else {
            collection = await collectionApi('/api/collections', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ name, downloadRoot })
            });
        }

        if (state.pendingIconFile) {
            const form = new FormData();
            form.append('file', state.pendingIconFile);
            await collectionApi(`/api/collections/${collection.id}/icon`, { method: 'POST', body: form });
        } else if (state.pendingIconClear && state.editingCollection) {
            await collectionApi(`/api/collections/${collection.id}/icon`, { method: 'DELETE' });
        }

        toast(
            state.editingCollection
                ? pageI18n.t('gallery:toast.saved', '已保存')
                : pageI18n.t('gallery:toast.created', '创建成功'),
            'success'
        );
        closeCollectionFormModal();
        await loadCollections();
    } catch (e) {
        toast(e.message || pageI18n.t('gallery:toast.save-failed', '保存失败'), 'error');
    } finally {
        btn.disabled = false;
    }
}

// ---------- Collections (sidebar + filter chips) ----------
async function loadCollections() {
    try {
        const r = await fetch('/api/collections', { credentials: 'same-origin' });
        if (!r.ok) return;
        const data = await r.json();
        state.collections = data.collections || [];
        renderCollections();
        renderCollectionFilterChips();
    } catch (e) { console.warn('load collections failed', e); }
}

function renderCollections() {
    const list = document.getElementById('collectionList');
    if (!state.collections.length) {
        list.innerHTML = `<div style="padding:8px 16px; font-size:12px; color:var(--muted)">${esc(pageI18n.t('status.no-collections', '暂无收藏夹'))}</div>`;
        return;
    }
    list.innerHTML = state.collections.map(c => {
        const selected = state.selectedCollections.has(c.id) ? 'selected' : '';
        const icon = c.iconExt
            ? `<img src="/api/collections/${c.id}/icon?v=${c.createdTime}" alt="">`
            : HEART_SVG;
        return `<div class="collection-item ${selected}" data-id="${c.id}">
            <div class="collection-icon">${icon}</div>
            <span class="collection-label">${esc(c.name)}</span>
            <span class="collection-count">${c.novelCount ?? 0}</span>
        </div>`;
    }).join('');
    list.querySelectorAll('.collection-item').forEach(row => {
        row.addEventListener('click', () => switchCollectionFilter(Number(row.dataset.id)));
    });
}

function renderCollectionFilterChips() {
    const box = document.getElementById('filterCollectionChips');
    if (!state.collections.length) {
        box.innerHTML = `<span class="chip-empty">${esc(pageI18n.t('status.no-collections', '暂无收藏夹'))}</span>`;
        return;
    }
    box.innerHTML = state.collections.map(c => {
        const active = state.selectedCollections.has(c.id) ? 'active' : '';
        const icon = c.iconExt
            ? `<img src="/api/collections/${c.id}/icon?v=${c.createdTime}" alt="">`
            : HEART_SVG;
        return `<button class="chip ${active}" data-collection-id="${c.id}">
            <span class="chip-icon">${icon}</span>
            ${esc(c.name)}
            <span class="chip-count">${c.novelCount ?? 0}</span>
        </button>`;
    }).join('');
    box.querySelectorAll('.chip[data-collection-id]').forEach(chip => {
        chip.addEventListener('click', () => toggleCollectionFilter(Number(chip.dataset.collectionId)));
    });
}

function toggleCollectionFilter(id) {
    if (state.selectedCollections.has(id)) state.selectedCollections.delete(id);
    else state.selectedCollections.add(id);
    state.page = 0;
    renderCollections(); renderCollectionFilterChips();
    updateFilterBadge();
    reloadCurrentView();
}

function switchCollectionFilter(id) {
    const exclusive = state.selectedCollections.size === 1 && state.selectedCollections.has(id);
    const alreadyOnlyCollection = exclusive
        && state.view === 'all'
        && state.r18 === 'any'
        && state.ai === 'any'
        && state.selectedTags.size === 0
        && state.selectedSeries.size === 0
        && state.selectedAuthors.size === 0
        && state.exclusiveAuthorId == null;
    if (alreadyOnlyCollection) return;
    resetFiltersForSidebarCollection(id);
}

function resetFiltersForSidebarCollection(id) {
    state.r18 = 'any';
    state.ai = 'any';
    document.querySelectorAll('.chip[data-r18]').forEach(c => c.classList.toggle('active', c.dataset.r18 === 'any'));
    document.querySelectorAll('.chip[data-ai]').forEach(c => c.classList.toggle('active', c.dataset.ai === 'any'));
    state.selectedCollections.clear();
    state.selectedCollections.add(id);
    state.selectedTags.clear();
    state.selectedSeries.clear();
    state.selectedAuthors.clear();
    state.exclusiveAuthorId = null;
    state.exclusiveAuthorName = '';
    state.view = 'all';
    state.page = 0;
    state.authorsView.page = 0;
    state.seriesView.page = 0;
    syncViewParamInUrl();
    renderCollections(); renderCollectionFilterChips();
    renderTagChips();
    renderSeriesChips();
    renderAuthorChips();
    updateAuthorFilterBar();
    updateFilterBadge();
    reloadCurrentView();
}

// ---------- Tags ----------
async function loadTags() {
    try {
        const params = new URLSearchParams({ limit: '300' });
        if (state.tagSearch) params.set('search', state.tagSearch);
        const r = await fetch(`/api/gallery/novels/tags?${params.toString()}`);
        if (!r.ok) return;
        const data = await r.json();
        state.tags = data.tags || [];
        state.tags.forEach(rememberTagOption);
        renderTagChips();
    } catch (e) { console.warn('load tags failed', e); }
}

function renderTagChips() {
    const box = document.getElementById('filterTagChips');
    let filtered = state.tags.slice();
    filtered.sort((a, b) => compareFilterSelectionOrder(state.selectedTags, a.tagId, b.tagId));

    const loadedIds = new Set(filtered.map(t => Number(t.tagId)));
    const missing = [...state.selectedTags.keys()].filter(id => !loadedIds.has(Number(id)));
    if (missing.length) {
        const stubs = missing.map(id => ({
            tagId: id,
            name: state.tagNames.get(id) || `#${id}`,
            translatedName: state.tagTranslatedNames.get(id) || '',
            novelCount: 0
        }));
        filtered = stubs.concat(filtered);
        filtered.sort((a, b) => compareFilterSelectionOrder(state.selectedTags, a.tagId, b.tagId));
    }

    if (!filtered.length) {
        box.innerHTML = `<span class="chip-empty">${esc(pageI18n.t('filter.tags.empty', '暂无可用标签'))}</span>`;
        return;
    }
    box.innerHTML = filtered.map(t => {
        const tagId = Number(t.tagId);
        const mode = state.selectedTags.get(tagId);
        const cls = mode ? `filter-selected mode-${mode}` : '';
        const trans = t.translatedName ? `<span class="tag-trans">${esc(t.translatedName)}</span>` : '';
        return `<button class="chip ${cls}" data-tag-id="${tagId}">${esc(t.name || ('#' + tagId))}${trans}<span class="chip-count">${t.novelCount}</span></button>`;
    }).join('');
    box.querySelectorAll('.chip[data-tag-id]').forEach(chip => {
        chip.addEventListener('click', () => toggleTagSelection(Number(chip.dataset.tagId)));
    });
}

function toggleTagSelection(tagId) {
    const current = state.selectedTags.get(tagId);
    if (current === state.tagMode) state.selectedTags.delete(tagId);
    else state.selectedTags.set(tagId, state.tagMode);
    state.page = 0;
    renderTagChips();
    updateFilterBadge();
    reloadCurrentView();
}

function setTagFilterExclusive(tagId, name, translatedName) {
    tagId = Number(tagId);
    if (!Number.isFinite(tagId) || tagId <= 0) return;
    if (!state.tags.some(t => Number(t.tagId) === tagId)) {
        state.tags = [{
            tagId,
            name: name || `#${tagId}`,
            translatedName: translatedName || '',
            novelCount: 0
        }, ...state.tags];
    }
    rememberTagOption({ tagId, name: name || `#${tagId}`, translatedName: translatedName || '' });
    state.selectedTags.clear();
    state.selectedTags.set(tagId, 'must');
    state.tagMode = 'must';
    document.querySelectorAll('#tagModeButtons .filter-mode-btn').forEach(btn => {
        btn.classList.toggle('active', btn.dataset.filterMode === 'must');
    });
    state.page = 0;
    renderTagChips();
    updateFilterBadge();
    setFilterPanelOpen(false);
    if (state.view !== 'all') {
        switchView('all');
    } else {
        syncViewParamInUrl();
        reloadCurrentView();
    }
}

// ---------- Series ----------
async function loadSeries() {
    try {
        const params = new URLSearchParams({ size: '200', sort: 'novels' });
        if (state.seriesSearch) params.set('search', state.seriesSearch);
        const r = await fetch(`/api/gallery/novels/series?${params.toString()}`);
        if (!r.ok) return;
        const data = await r.json();
        state.series = data.content || [];
        renderSeriesChips();
    } catch (e) { console.warn('load series failed', e); }
}

function renderSeriesChips() {
    const box = document.getElementById('filterSeriesChips');
    if (!state.series.length) {
        box.innerHTML = `<span class="chip-empty">${esc(pageI18n.t('filter.series.empty', '暂无系列'))}</span>`;
        return;
    }
    box.innerHTML = state.series.map(s => {
        const mode = state.selectedSeries.get(s.seriesId);
        const cls = mode ? `filter-selected mode-${mode}` : '';
        return `<button class="chip ${cls}" data-series-id="${s.seriesId}">${esc(s.title)}<span class="chip-count">${s.novelCount}</span></button>`;
    }).join('');
    box.querySelectorAll('.chip[data-series-id]').forEach(chip => {
        chip.addEventListener('click', () => toggleSeriesSelection(Number(chip.dataset.seriesId)));
    });
}

function toggleSeriesSelection(seriesId) {
    const current = state.selectedSeries.get(seriesId);
    // 系列只支持 must/not（点击循环：none → must → not → none）
    const next = current === 'must' ? 'not' : (current === 'not' ? null : 'must');
    if (next === null) state.selectedSeries.delete(seriesId);
    else state.selectedSeries.set(seriesId, next);
    state.page = 0;
    renderSeriesChips();
    updateFilterBadge();
    reloadCurrentView();
}

// ---------- Authors ----------
async function loadAuthors() {
    try {
        const params = new URLSearchParams({ size: '200', sort: 'novels' });
        if (state.authorSearch) params.set('search', state.authorSearch);
        const r = await fetch(`/api/gallery/novels/authors?${params.toString()}`);
        if (!r.ok) return;
        const data = await r.json();
        state.authors = data.content || [];
        state.authors.forEach(rememberAuthorOption);
        renderAuthorChips();
    } catch (e) { console.warn('load authors failed', e); }
}

function renderAuthorChips() {
    const box = document.getElementById('filterAuthorChips');
    let filtered = state.authors.slice();
    filtered.sort((a, b) => compareFilterSelectionOrder(state.selectedAuthors, a.authorId, b.authorId));

    const loadedIds = new Set(filtered.map(a => Number(a.authorId)));
    const missing = [...state.selectedAuthors.keys()].filter(id => !loadedIds.has(Number(id)));
    if (missing.length) {
        const stubs = missing.map(id => ({
            authorId: id,
            name: state.authorNames.get(id) || `#${id}`,
            novelCount: 0
        }));
        filtered = stubs.concat(filtered);
        filtered.sort((a, b) => compareFilterSelectionOrder(state.selectedAuthors, a.authorId, b.authorId));
    }

    if (!filtered.length) {
        box.innerHTML = `<span class="chip-empty">${esc(pageI18n.t('filter.authors.empty', '暂无作者'))}</span>`;
        return;
    }
    box.innerHTML = filtered.map(a => {
        const authorId = Number(a.authorId);
        const mode = state.selectedAuthors.get(authorId);
        const cls = mode ? `filter-selected mode-${mode}` : '';
        return `<button class="chip ${cls}" data-author-id="${authorId}">${esc(a.name || ('#' + authorId))}<span class="chip-count">${a.novelCount}</span></button>`;
    }).join('');
    box.querySelectorAll('.chip[data-author-id]').forEach(chip => {
        chip.addEventListener('click', () => toggleAuthorSelection(Number(chip.dataset.authorId)));
    });
}

function toggleAuthorSelection(authorId) {
    const current = state.selectedAuthors.get(authorId);
    if (current === state.authorMode) state.selectedAuthors.delete(authorId);
    else state.selectedAuthors.set(authorId, state.authorMode);
    state.page = 0;
    renderAuthorChips();
    updateFilterBadge();
    reloadCurrentView();
}

function setAuthorFilterExclusive(authorId, name) {
    state.exclusiveAuthorId = authorId;
    state.exclusiveAuthorName = name || ('#' + authorId);
    state.selectedAuthors.clear();
    state.view = 'all';
    syncViewParamInUrl();
    setActiveViewNav();
    state.page = 0;
    updateAuthorFilterBar();
    renderAuthorChips();
    updateFilterBadge();
    reloadCurrentView();
}

function updateAuthorFilterBar() {
    const bar = document.getElementById('authorFilterBar');
    if (state.exclusiveAuthorId != null) {
        document.getElementById('authorFilterName').textContent = state.exclusiveAuthorName;
        bar.classList.add('visible');
    } else {
        bar.classList.remove('visible');
    }
}

// ---------- View switching ----------
function switchView(v) {
    v = normalizeView(v);
    state.view = v;
    syncViewParamInUrl();
    setActiveViewNav();
    state.page = 0;
    state.authorsView.page = 0;
    state.seriesView.page = 0;
    reloadCurrentView();
}

function setActiveViewNav() {
    syncViewNavigationHrefs();
    document.querySelectorAll('.nav-item[data-view]').forEach(el => {
        el.classList.toggle('active', el.dataset.view === state.view);
    });
    const grid = document.getElementById('grid');
    const pag = document.getElementById('pagination');
    const av = document.getElementById('authorView');
    const sv = document.getElementById('seriesView');
    const isAuthors = state.view === 'authors';
    const isSeries = state.view === 'series';
    grid.style.display = (isAuthors || isSeries) ? 'none' : '';
    pag.style.display = (isAuthors || isSeries) ? 'none' : '';
    av.classList.toggle('active', isAuthors);
    sv.classList.toggle('active', isSeries);
}

function reloadCurrentView() {
    setActiveViewNav();
    if (state.view === 'authors') reloadAuthorsView();
    else if (state.view === 'series') reloadSeriesView();
    else reloadNovels();
}

// ---------- Novels list ----------
async function reloadNovels() {
    persistNovelGalleryState();
    const params = new URLSearchParams({
        page: String(state.page), size: String(PAGE_SIZE),
        sort: state.sort, order: state.order, r18: state.r18, ai: state.ai
    });
    if (state.search) params.set('search', state.search);
    if (state.search && state.searchType && state.searchType !== 'all') params.set('searchType', state.searchType);
    if (state.selectedCollections.size) params.set('collectionIds', Array.from(state.selectedCollections).join(','));

    const must = [], not = [], or = [];
    state.selectedTags.forEach((mode, id) => (mode === 'must' ? must : mode === 'not' ? not : or).push(id));
    if (must.length) params.set('tagIds', must.join(','));
    if (not.length) params.set('notTagIds', not.join(','));
    if (or.length) params.set('orTagIds', or.join(','));

    const sMust = [], sNot = [];
    state.selectedSeries.forEach((mode, id) => (mode === 'must' ? sMust : sNot).push(id));
    if (sMust.length) params.set('seriesIds', sMust.join(','));
    if (sNot.length) params.set('notSeriesIds', sNot.join(','));

    const aMust = [], aNot = [], aOr = [];
    state.selectedAuthors.forEach((mode, id) => (mode === 'must' ? aMust : mode === 'not' ? aNot : aOr).push(id));
    if (state.exclusiveAuthorId != null) aMust.push(state.exclusiveAuthorId);
    if (aMust.length) params.set('authorIds', aMust.join(','));
    if (aNot.length) params.set('notAuthorIds', aNot.join(','));
    if (aOr.length) params.set('orAuthorIds', aOr.join(','));

    try {
        const r = await fetch(`/api/gallery/novels?${params.toString()}`);
        if (!r.ok) throw new Error('HTTP ' + r.status);
        const data = await r.json();
        renderGrid(data.content || []);
        renderPagination('pagination', data.totalPages || 0, data.page || 0, p => { state.page = p; reloadNovels(); });
        // 仅在存在有效搜索/筛选条件时才把搜索框与筛选按钮标红
        setSearchEmptyState((data.content || []).length === 0 && hasActiveNovelFilters());
    } catch (e) {
        document.getElementById('grid').innerHTML = `<div class="empty">${esc(pageI18n.t('novel:status.load-failed', '加载失败'))}</div>`;
        document.getElementById('pagination').innerHTML = '';
        setSearchEmptyState(false);
    }
}

function formatReadingDuration(seconds) {
    const total = Math.floor(Number(seconds || 0));
    if (!Number.isFinite(total) || total <= 0) return '';
    const hour = Math.floor(total / 3600);
    const minute = Math.floor((total % 3600) / 60);
    const second = total % 60;
    const parts = [];
    if (hour > 0) parts.push(pageI18n.t('novel:duration.hour', '{count} 小时', { count: hour }));
    if (minute > 0) parts.push(pageI18n.t('novel:duration.minute', '{count} 分钟', { count: minute }));
    if (second > 0 && hour === 0) parts.push(pageI18n.t('novel:duration.second', '{count} 秒', { count: second }));
    if (!parts.length) parts.push(pageI18n.t('novel:duration.second', '{count} 秒', { count: total }));
    return parts.join(' ');
}

function formatReadingTime(seconds) {
    const text = formatReadingDuration(seconds);
    return text ? pageI18n.t('novel:meta.reading-time', '预计阅读：{time}', { time: text }) : '';
}

function hasActiveNovelFilters() {
    return !!(state.search
        || state.r18 !== 'any'
        || state.ai !== 'any'
        || state.selectedCollections.size
        || state.selectedTags.size
        || state.selectedSeries.size
        || state.selectedAuthors.size
        || state.exclusiveAuthorId != null);
}

function renderGrid(items) {
    const grid = document.getElementById('grid');
    if (!items.length) {
        if (hasActiveNovelFilters()) {
            grid.innerHTML = `<div class="empty" style="grid-column:1/-1;">${esc(pageI18n.t('novel:status.empty', '暂无小说'))}</div>`;
        } else {
            grid.innerHTML = `<div class="empty empty-cta" style="grid-column:1/-1;">`
                + `<div>${esc(pageI18n.t('novel:status.no-downloads', '没有下载的作品，快去下载页下载吧！'))}</div>`
                + `<a class="btn btn-primary" href="/pixiv-batch.html" target="_blank" rel="noopener">${esc(pageI18n.t('novel:status.go-download', '前往下载页'))}</a>`
                + `</div>`;
        }
        return;
    }
    const heartTitle = esc(pageI18n.t('collection.add', '添加到收藏夹'));
    grid.innerHTML = items.map(item => {
        const badges = [];
        if (item.xRestrict === 1) badges.push('<span class="badge r18">R-18</span>');
        if (item.xRestrict === 2) badges.push('<span class="badge r18g">R-18G</span>');
        if (item.isAi) badges.push('<span class="badge ai">AI</span>');
        if (item.isOriginal) badges.push(`<span class="badge original">${esc(pageI18n.t('novel:meta.original', '原创'))}</span>`);
        const meta = [];
        if (item.wordCount && item.wordCount > 0) meta.push(esc(pageI18n.t('novel:meta.word-count', '{count} 字', { count: item.wordCount })));
        else if (item.textLength && item.textLength > 0) meta.push(esc(pageI18n.t('novel:meta.text-length', '{count} 字符', { count: item.textLength })));
        const readingText = formatReadingTime(item.readingTimeSeconds);
        if (readingText) meta.push(esc(readingText));
        if (item.seriesId && item.seriesId > 0 && item.seriesOrder != null) meta.push('#' + item.seriesOrder);
        const cover = item.coverExt
            ? `<div class="card-cover"><img loading="lazy" src="/api/gallery/novel/${item.novelId}/cover" alt="" onerror="this.parentElement.style.display='none'"></div>`
            : '';
        return `<div class="card" data-novel-id="${item.novelId}">
            ${cover}
            <div class="card-title">${esc(item.title || '')}</div>
            <div class="card-author">${esc(item.authorName || pageI18n.t('novel:status.unknown-author', '未知作者'))}</div>
            <div class="badges">${badges.join('')}</div>
            <div class="card-footer">
                <div class="card-footer-info">
                    <div class="card-meta">${meta.map(m => `<span>${m}</span>`).join('')}</div>
                    <div class="work-collections" data-collections-for="${item.novelId}"></div>
                </div>
                <button class="thumb-heart" data-novel-id="${item.novelId}" title="${heartTitle}" type="button">${HEART_SVG}</button>
            </div>
        </div>`;
    }).join('');

    grid.querySelectorAll('.card').forEach(card => {
        const id = card.dataset.novelId;
        card.addEventListener('click', e => {
            if (e.target.closest('.thumb-heart')) return;
            window.location.href = `/pixiv-novel.html?id=${id}`;
        });
        const heart = card.querySelector('.thumb-heart');
        if (heart) {
            heart.addEventListener('click', e => {
                e.stopPropagation();
                e.preventDefault();
                openAddToCollectionModal(Number(id));
            });
        }
    });

    loadMembershipsForNovels(items.map(i => Number(i.novelId)));
}

async function loadMembershipsForNovels(novelIds) {
    if (!novelIds.length) return;
    let memberships = {};
    try {
        const r = await fetch('/api/collections/novels/memberships', {
            method: 'POST',
            credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ novelIds })
        });
        if (!r.ok) return;
        const data = await r.json();
        memberships = data.memberships || {};
    } catch (e) { return; }
    const byId = new Map((state.collections || []).map(c => [c.id, c]));
    novelIds.forEach(id => {
        const collectionIds = memberships[id] || [];
        const card = document.querySelector(`.card[data-novel-id="${id}"]`);
        if (!card) return;
        const heart = card.querySelector('.thumb-heart');
        if (heart) heart.classList.toggle('liked', collectionIds.length > 0);
        const label = card.querySelector(`[data-collections-for="${id}"]`);
        if (!label) return;
        if (collectionIds.length === 0) { label.textContent = ''; return; }
        const names = collectionIds.map(cid => byId.get(cid)?.name).filter(Boolean);
        label.textContent = names.length ? '♥ ' + names.join(' · ') : '';
    });
}

// ---------- Authors view ----------
const AUTHOR_WORKS_PER_ROW = 30;

async function reloadAuthorsView() {
    persistNovelGalleryState();
    setSearchEmptyState(false);
    const grid = document.getElementById('authorGrid');
    grid.innerHTML = `<div class="author-works-loading">${esc(pageI18n.t('novel:status.loading', '加载中...'))}</div>`;
    try {
        const params = new URLSearchParams({
            page: String(state.authorsView.page),
            size: String(PAGE_SIZE),
            sort: 'novels'
        });
        if (state.search) params.set('search', state.search);
        const r = await fetch(`/api/gallery/novels/authors?${params.toString()}`);
        if (!r.ok) throw new Error('HTTP ' + r.status);
        const data = await r.json();
        state.authorsView.content = data.content || [];
        state.authorsView.totalPages = data.totalPages || 0;
        renderAuthorsView(state.authorsView.content);
        renderPagination('authorPagination', state.authorsView.totalPages, state.authorsView.page,
            p => { state.authorsView.page = p; reloadAuthorsView(); });
    } catch (e) {
        grid.innerHTML = `<div class="empty">${esc(pageI18n.t('novel:status.load-failed', '加载失败'))}</div>`;
        document.getElementById('authorPagination').innerHTML = '';
    }
}

function renderAuthorsView(authors) {
    const grid = document.getElementById('authorGrid');
    if (!authors.length) {
        grid.innerHTML = `<div class="empty">${esc(pageI18n.t('status.no-authors', '暂无作者'))}</div>`;
        return;
    }
    const viewAllLabel = esc(pageI18n.t('novel:series.view-all', '查看全部'));
    const loadingLabel = esc(pageI18n.t('novel:status.loading', '加载中...'));
    const prevLabel = esc(pageI18n.t('novel:author-view.prev-group', '上一组'));
    const nextLabel = esc(pageI18n.t('novel:author-view.next-group', '下一组'));
    grid.innerHTML = authors.map(a => {
        const name = a.name || ('#' + a.authorId);
        const countText = esc(pageI18n.t('novel:author-view.count', '{count} 部 · #{authorId}', { count: a.novelCount, authorId: a.authorId }));
        return `
        <div class="author-row" data-author-id="${a.authorId}" data-author-name="${esc(a.name || '')}">
            <div class="author-row-info">
                <div class="author-row-name" data-filter-author="${a.authorId}" title="${esc(name)}">${esc(name)}</div>
                <div class="author-row-count">${countText}</div>
                <div class="author-row-actions">
                    <button class="author-row-btn" data-filter-author="${a.authorId}" type="button">${viewAllLabel}</button>
                </div>
            </div>
            <div class="author-row-works">
                <button class="author-works-arrow left" data-arrow="left" type="button" title="${prevLabel}">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 18 9 12 15 6"/></svg>
                </button>
                <div class="author-works-strip" data-strip-for="${a.authorId}">
                    <div class="author-works-loading">${loadingLabel}</div>
                </div>
                <button class="author-works-arrow right" data-arrow="right" type="button" title="${nextLabel}">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 18 15 12 9 6"/></svg>
                </button>
            </div>
        </div>`;
    }).join('');

    grid.querySelectorAll('[data-filter-author]').forEach(el => {
        el.addEventListener('click', e => {
            e.stopPropagation();
            const id = Number(el.dataset.filterAuthor);
            const row = el.closest('.author-row');
            const name = row ? row.dataset.authorName : '';
            setAuthorFilterExclusive(id, name);
        });
    });

    grid.querySelectorAll('.author-row').forEach(row => {
        const authorId = Number(row.dataset.authorId);
        const strip = row.querySelector('.author-works-strip');
        const leftBtn = row.querySelector('[data-arrow="left"]');
        const rightBtn = row.querySelector('[data-arrow="right"]');

        loadAuthorWorks(authorId, strip).then(() => updateArrowState(strip, leftBtn, rightBtn));

        leftBtn.addEventListener('click', () => {
            strip.scrollBy({ left: -strip.clientWidth * 0.8, behavior: 'smooth' });
        });
        rightBtn.addEventListener('click', () => {
            strip.scrollBy({ left: strip.clientWidth * 0.8, behavior: 'smooth' });
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

// ---------- Series view ----------
async function reloadSeriesView() {
    persistNovelGalleryState();
    setSearchEmptyState(false);
    const grid = document.getElementById('seriesGrid');
    grid.innerHTML = `<div class="empty" style="grid-column:1/-1;">${esc(pageI18n.t('novel:status.loading', '加载中...'))}</div>`;
    try {
        const params = new URLSearchParams({
            page: String(state.seriesView.page),
            size: String(PAGE_SIZE),
            sort: 'novels'
        });
        if (state.search) params.set('search', state.search);
        const r = await fetch(`/api/gallery/novels/series?${params.toString()}`);
        if (!r.ok) throw new Error('HTTP ' + r.status);
        const data = await r.json();
        state.seriesView.content = data.content || [];
        state.seriesView.totalPages = data.totalPages || 0;
        renderSeriesView(state.seriesView.content);
        renderPagination('seriesPagination', state.seriesView.totalPages, state.seriesView.page,
            p => { state.seriesView.page = p; reloadSeriesView(); });
    } catch (e) {
        grid.innerHTML = `<div class="empty" style="grid-column:1/-1;">${esc(pageI18n.t('novel:status.load-failed', '加载失败'))}</div>`;
        document.getElementById('seriesPagination').innerHTML = '';
    }
}

function renderSeriesView(seriesList) {
    const grid = document.getElementById('seriesGrid');
    if (!seriesList.length) {
        grid.innerHTML = `<div class="empty">${esc(pageI18n.t('novel:batch.gallery.series-empty', '暂无系列'))}</div>`;
        return;
    }
    const unknownAuthor = pageI18n.t('novel:status.unknown-author', '未知');
    const fallbackSeriesTitle = pageI18n.t('novel:series.unknown-title', '未命名系列');
    const viewAllLabel = esc(pageI18n.t('novel:series.view-all', '查看全部'));
    const loadingLabel = esc(pageI18n.t('novel:status.loading', '加载中...'));
    const prevLabel = esc(pageI18n.t('novel:author-view.prev-group', '上一组'));
    const nextLabel = esc(pageI18n.t('novel:author-view.next-group', '下一组'));
    const maxTags = 6;
    grid.innerHTML = seriesList.map(s => {
        const title = s.title || fallbackSeriesTitle;
        const author = s.authorName || (s.authorId != null ? '#' + s.authorId : unknownAuthor);
        const countText = esc(pageI18n.t('novel:series.count', '{count} 部 · #{seriesId}', { count: s.novelCount, seriesId: s.seriesId }));
        const authorLine = author
            ? `<div class="author-row-count">${esc(pageI18n.t('novel:series.author-prefix', '作者：{name}', { name: author }))}</div>`
            : '';
        const cover = s.coverExt
            ? `<div class="series-row-cover"><img src="/api/gallery/novel/series/${s.seriesId}/cover" alt="${esc(title)}" loading="lazy" onerror="this.parentElement.replaceWith(Object.assign(document.createElement('div'),{className:'series-row-cover',textContent:this.alt}))"></div>`
            : '';
        const tags = Array.isArray(s.tags) ? s.tags : [];
        const tagsHtml = tags.length
            ? `<div class="series-row-tags">${tags.slice(0, maxTags).map(tg => {
                  const label = tg.translatedName ? `${tg.name} · ${tg.translatedName}` : tg.name;
                  const tagId = Number(tg.tagId);
                  const name = tg.name || (Number.isFinite(tagId) && tagId > 0 ? `#${tagId}` : '');
                  const tagLabel = tg.translatedName ? `${name} / ${tg.translatedName}` : name;
                  if (Number.isFinite(tagId) && tagId > 0) {
                      return `<button class="series-row-tag" type="button" data-filter-series-tag-id="${tagId}" data-tag-name="${esc(name)}" data-tag-translated-name="${esc(tg.translatedName || '')}" title="${esc(tagLabel || label || name)}">${esc(name)}</button>`;
                  }
                  return `<span class="series-row-tag" title="${esc(tagLabel || label || name)}">${esc(name)}</span>`;
              }).join('')}${tags.length > maxTags ? `<span class="series-row-tag-more">${esc(pageI18n.t('novel:series.tag-more', '+{count}', { count: tags.length - maxTags }))}</span>` : ''}</div>`
            : '';
        return `
        <div class="author-row series-row" data-series-id="${s.seriesId}" data-series-title="${esc(s.title || '')}">
            <div class="author-row-info">
                ${cover}
                <div class="author-row-name" data-open-series-directory="${s.seriesId}" title="${esc(title)}">${esc(title)}</div>
                <div class="author-row-count">${countText}</div>
                ${authorLine}
                ${tagsHtml}
                <div class="author-row-actions">
                    <button class="author-row-btn" data-open-series-directory="${s.seriesId}" type="button">${viewAllLabel}</button>
                </div>
            </div>
            <div class="author-row-works">
                <button class="author-works-arrow left" data-arrow="left" type="button" title="${prevLabel}">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 18 9 12 15 6"/></svg>
                </button>
                <div class="author-works-strip" data-strip-for="${s.seriesId}">
                    <div class="author-works-loading">${loadingLabel}</div>
                </div>
                <button class="author-works-arrow right" data-arrow="right" type="button" title="${nextLabel}">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 18 15 12 9 6"/></svg>
                </button>
            </div>
        </div>`;
    }).join('');

    grid.querySelectorAll('[data-open-series-directory]').forEach(el => {
        el.addEventListener('click', e => {
            e.stopPropagation();
            const sid = Number(el.dataset.openSeriesDirectory);
            const row = el.closest('.series-row');
            const stitle = row ? row.dataset.seriesTitle : '';
            window.location.href = buildSeriesDirectoryHref(sid, stitle);
        });
    });

    grid.querySelectorAll('[data-filter-series-tag-id]').forEach(el => {
        el.addEventListener('click', e => {
            e.preventDefault();
            e.stopPropagation();
            setTagFilterExclusive(
                el.dataset.filterSeriesTagId,
                el.dataset.tagName || '',
                el.dataset.tagTranslatedName || ''
            );
        });
    });

    grid.querySelectorAll('.series-row').forEach(row => {
        const seriesId = Number(row.dataset.seriesId);
        const strip = row.querySelector('.author-works-strip');
        const leftBtn = row.querySelector('[data-arrow="left"]');
        const rightBtn = row.querySelector('[data-arrow="right"]');

        // 初始两个箭头都禁用，等 loadSeriesNovels 渲染完成后由 renderSeriesPagedStrip 接管。
        leftBtn.disabled = true;
        rightBtn.disabled = true;
        loadSeriesNovels(seriesId, strip, leftBtn, rightBtn);
    });
}

async function loadSeriesNovels(seriesId, strip, leftBtn, rightBtn) {
    try {
        const params = new URLSearchParams({
            page: '0',
            size: String(AUTHOR_WORKS_PER_ROW),
            sort: 'series',
            order: 'asc',
            seriesIds: String(seriesId)
        });
        const r = await fetch(`/api/gallery/novels?${params.toString()}`);
        if (!r.ok) throw new Error('HTTP ' + r.status);
        const data = await r.json();
        const items = data.content || [];
        if (!items.length) {
            strip.innerHTML = `<div class="author-works-empty">${esc(pageI18n.t('novel:status.empty', '暂无小说'))}</div>`;
            if (leftBtn) leftBtn.disabled = true;
            if (rightBtn) rightBtn.disabled = true;
            return;
        }
        renderSeriesPagedStrip(strip, items, leftBtn, rightBtn);
    } catch (e) {
        strip.innerHTML = `<div class="author-works-empty">${esc(pageI18n.t('novel:status.load-failed', '加载失败'))}</div>`;
        if (leftBtn) leftBtn.disabled = true;
        if (rightBtn) rightBtn.disabled = true;
    }
}

/** 系列横排尺寸常量，需与 .series-row .series-works-pager / .author-work-card 默认尺寸保持一致。 */
const SERIES_CARD_WIDTH_PX = 140;
const SERIES_COLUMN_GAP_PX = 10;
const SERIES_ROW_GAP_PX = 12;
/** 卡高 = 140 缩略图 + ~36 标题（两行）+ 上下间距，给一个保守的 180px。 */
const SERIES_CARD_HEIGHT_PX = 180;
/** 左右各 40px 留给 .author-works-arrow 浮层。 */
const SERIES_STRIP_PADDING_X_PX = 80;

function renderSeriesItemCard(item) {
    const title = item.title || pageI18n.t('novel:status.unknown-novel', '小说 {id}', { id: item.novelId });
    const order = item.seriesOrder != null
        ? `<div class="author-work-pages">#${esc(item.seriesOrder)}</div>`
        : '';
    const cover = item.coverExt
        ? `<img src="/api/gallery/novel/${item.novelId}/cover" alt="${esc(title)}" loading="lazy" onerror="this.replaceWith(Object.assign(document.createElement('div'),{className:'author-work-thumb-placeholder',textContent:this.alt}))">`
        : `<div class="author-work-thumb-placeholder">${esc(title)}</div>`;
    return `
        <div class="author-work-card" data-id="${item.novelId}">
            <div class="author-work-thumb">
                ${cover}
                ${order}
            </div>
            <div class="author-work-title">${esc(title)}</div>
        </div>`;
}

/**
 * 系列章节"分页式"横排：卡片沿用 .author-work-card 默认样式（140 缩略图 + 标题）。
 *
 * 排数 / 列数按 strip 当前尺寸算：
 *   rowsPerPage = strip.clientHeight ≥ 270 时 = 2，否则 = 1
 *   colsPerPage = floor((可用宽 + 最小列间距) / (卡宽 + 最小列间距))   ≥ 1
 *
 * 余量塞进 column-gap：colsPerPage × cardW + (colsPerPage-1) × gap 正好等于 innerW，
 * 翻页步幅 = innerW + 一段 gap（页与页之间留同样的视觉间距）。
 *
 * 填充顺序：**行优先 (grid-auto-flow: row)**，从上往下、从左往右：
 *   rowsPerPage = 2 时 row-1 = #1 #2 #3 … #cols；row-2 = #cols+1 …… #2cols。
 *   rowsPerPage = 1 时 row-1 = #1 #2 #3 ……
 * 末页不满时，留空自然出现在右下角 —— 第二行最后一格最先空。
 *
 * 模式切换：
 *   - rowsPerPage = 1：strip 加 .is-scrollable，开 overflow-x: auto，鼠标/触屏可横滑；箭头 scrollBy。
 *   - rowsPerPage = 2：strip overflow: hidden，translateX 整页平移，鼠标/触屏不响应。
 */
function renderSeriesPagedStrip(strip, items, leftBtn, rightBtn) {
    let pageIndex = 0;
    let lastLayoutKey = '';
    let scrollListenerCleanup = null;

    const layout = () => {
        const stripH = strip.clientHeight;
        const stripW = strip.clientWidth;
        if (stripH <= 0 || stripW <= 0) return;

        const rowsPerPage = stripH >= (SERIES_CARD_HEIGHT_PX + 90) ? 2 : 1;
        const innerW = Math.max(SERIES_CARD_WIDTH_PX, stripW - SERIES_STRIP_PADDING_X_PX);
        const minCardStride = SERIES_CARD_WIDTH_PX + SERIES_COLUMN_GAP_PX;
        const colsPerPage = Math.max(
            1,
            Math.floor((innerW + SERIES_COLUMN_GAP_PX) / minCardStride)
        );
        const actualGap = colsPerPage > 1
            ? (innerW - colsPerPage * SERIES_CARD_WIDTH_PX) / (colsPerPage - 1)
            : SERIES_COLUMN_GAP_PX;

        const itemsPerPage = rowsPerPage * colsPerPage;
        const totalPages = Math.max(1, Math.ceil(items.length / itemsPerPage));
        // 一页步幅 = 这一页的整宽 + 页间留一段 gap，保持节奏一致。
        const pageContentWidthPx = colsPerPage * SERIES_CARD_WIDTH_PX + (colsPerPage - 1) * actualGap;
        const pageStridePx = pageContentWidthPx + actualGap;

        const key = `${rowsPerPage}|${colsPerPage}|${items.length}|${stripW}`;
        if (key === lastLayoutKey) return;
        lastLayoutKey = key;
        if (pageIndex >= totalPages) pageIndex = totalPages - 1;

        if (scrollListenerCleanup) {
            scrollListenerCleanup();
            scrollListenerCleanup = null;
        }

        const colsTemplate = `repeat(${colsPerPage}, ${SERIES_CARD_WIDTH_PX}px)`;

        // 切片成"每页 itemsPerPage 张"，每页按"实际需要几排"决定 grid-template-rows：
        //   effectiveRows = ceil(本页卡数 / colsPerPage)，上限 = rowsPerPage（高度允许的最大值）。
        //   行优先填 (grid-auto-flow: row 默认)：r1 填满之后才会动 r2，
        //   所以 ≤ colsPerPage 张的页（包括末页和"少作品系列"的唯一一页）就只渲染一排，r2 不存在。
        //   末页留空依然出现在右下角，与本规则自洽。
        const pagesHtml = [];
        for (let p = 0; p < totalPages; p++) {
            const slice = items.slice(p * itemsPerPage, (p + 1) * itemsPerPage);
            const effectiveRows = Math.min(
                rowsPerPage,
                Math.max(1, Math.ceil(slice.length / colsPerPage))
            );
            const rowsTemplate = Array(effectiveRows)
                .fill(`${SERIES_CARD_HEIGHT_PX}px`)
                .join(' ');
            const cards = slice.map(renderSeriesItemCard).join('');
            pagesHtml.push(`<div class="series-works-page"
                style="grid-template-rows:${rowsTemplate};grid-template-columns:${colsTemplate};column-gap:${actualGap}px;">
                ${cards}</div>`);
        }
        strip.innerHTML = `<div class="series-works-pager" style="column-gap:${actualGap}px;">${pagesHtml.join('')}</div>`;
        const pager = strip.querySelector('.series-works-pager');

        const isScrollable = rowsPerPage === 1;
        strip.classList.toggle('is-scrollable', isScrollable);

        if (isScrollable) {
            // 1 排：原生横滑 + 箭头 scrollBy 整页。
            pager.style.transform = 'none';
            strip.scrollLeft = 0;

            const apply = () => {
                const atStart = strip.scrollLeft <= 0;
                const atEnd = strip.scrollLeft + strip.clientWidth >= strip.scrollWidth - 1;
                if (leftBtn) leftBtn.disabled = atStart;
                if (rightBtn) rightBtn.disabled = atEnd;
            };
            if (leftBtn) leftBtn.onclick = () => {
                strip.scrollBy({ left: -pageStridePx, behavior: 'smooth' });
            };
            if (rightBtn) rightBtn.onclick = () => {
                strip.scrollBy({ left: pageStridePx, behavior: 'smooth' });
            };
            const onScroll = () => apply();
            strip.addEventListener('scroll', onScroll, { passive: true });
            scrollListenerCleanup = () => strip.removeEventListener('scroll', onScroll);
            apply();
        } else {
            pageIndex = Math.min(pageIndex, totalPages - 1);
            const apply = () => {
                pager.style.transform = `translateX(-${pageIndex * pageStridePx}px)`;
                if (leftBtn) leftBtn.disabled = pageIndex === 0;
                if (rightBtn) rightBtn.disabled = pageIndex >= totalPages - 1;
            };
            if (leftBtn) leftBtn.onclick = () => {
                if (pageIndex > 0) { pageIndex--; apply(); }
            };
            if (rightBtn) rightBtn.onclick = () => {
                if (pageIndex < totalPages - 1) { pageIndex++; apply(); }
            };
            apply();
        }

        pager.querySelectorAll('.author-work-card').forEach(card => {
            card.addEventListener('click', () => {
                window.location.href = `/pixiv-novel.html?id=${card.dataset.id}`;
            });
        });
    };

    layout();
    if (window.ResizeObserver) {
        const ro = new ResizeObserver(() => layout());
        ro.observe(strip);
    }
}

function buildSeriesDirectoryHref(seriesId, seriesTitle) {
    const params = new URLSearchParams({
        type: 'novel',
        seriesId: String(seriesId)
    });
    if (seriesTitle) params.set('seriesTitle', seriesTitle);
    return `/pixiv-series.html?${params.toString()}`;
}

async function loadAuthorWorks(authorId, strip) {
    try {
        const params = new URLSearchParams({
            page: '0',
            size: String(AUTHOR_WORKS_PER_ROW),
            sort: 'date',
            order: 'desc',
            authorIds: String(authorId)
        });
        const r = await fetch(`/api/gallery/novels?${params.toString()}`);
        if (!r.ok) throw new Error('HTTP ' + r.status);
        const data = await r.json();
        const items = data.content || [];
        if (!items.length) {
            strip.innerHTML = `<div class="author-works-empty">${esc(pageI18n.t('novel:status.empty', '暂无小说'))}</div>`;
            return;
        }
        strip.innerHTML = items.map(item => {
            const title = item.title || pageI18n.t('novel:status.unknown-novel', '小说 {id}', { id: item.novelId });
            const order = (item.seriesId && item.seriesId > 0 && item.seriesOrder != null)
                ? `<div class="author-work-pages">#${esc(item.seriesOrder)}</div>`
                : '';
            const cover = item.coverExt
                ? `<img src="/api/gallery/novel/${item.novelId}/cover" alt="${esc(title)}" loading="lazy" onerror="this.replaceWith(Object.assign(document.createElement('div'),{className:'author-work-thumb-placeholder',textContent:this.alt}))">`
                : `<div class="author-work-thumb-placeholder">${esc(title)}</div>`;
            return `
                <div class="author-work-card" data-id="${item.novelId}">
                    <div class="author-work-thumb">
                        ${cover}
                        ${order}
                    </div>
                    <div class="author-work-title">${esc(title)}</div>
                </div>`;
        }).join('');
        strip.querySelectorAll('.author-work-card').forEach(card => {
            card.addEventListener('click', () => {
                window.location.href = `/pixiv-novel.html?id=${card.dataset.id}`;
            });
        });
    } catch (e) {
        strip.innerHTML = `<div class="author-works-empty">${esc(pageI18n.t('novel:status.load-failed', '加载失败'))}</div>`;
    }
}

// ---------- Pagination helper ----------
function renderPagination(elementId, totalPages, page, onClick) {
    const wrap = document.getElementById(elementId);
    if (totalPages <= 1) { wrap.innerHTML = ''; return; }
    const pages = buildPageWindow(page, totalPages);
    const buttons = [];
    buttons.push(`<button data-pg="${page - 1}" ${page === 0 ? 'disabled' : ''}>‹</button>`);
    for (const p of pages) {
        if (p === '...') {
            buttons.push(buildPageJumpInput(totalPages));
        } else {
            buttons.push(`<button class="${p === page ? 'active' : ''}" data-pg="${p}">${p + 1}</button>`);
        }
    }
    buttons.push(`<button data-pg="${page + 1}" ${page >= totalPages - 1 ? 'disabled' : ''}>›</button>`);
    wrap.innerHTML = buttons.join('');
    wrap.querySelectorAll('button[data-pg]').forEach(btn => {
        if (btn.disabled) return;
        btn.addEventListener('click', () => {
            const target = Number(btn.dataset.pg);
            if (!Number.isInteger(target) || target < 0 || target >= totalPages) return;
            onClick(target);
        });
    });
    wrap.querySelectorAll('.page-jump-input').forEach(input => {
        let committed = false;
        const commit = () => {
            if (committed) return;
            committed = commitPageJump(input, totalPages, onClick);
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

function buildPageWindow(current, total) {
    const windowPages = new Set([0, total - 1, current, current - 1, current + 1]);
    const pages = [...windowPages].filter(n => n >= 0 && n < total).sort((a, b) => a - b);
    const out = [];
    for (let i = 0; i < pages.length; i++) {
        if (i > 0 && pages[i] - pages[i - 1] > 1) out.push('...');
        out.push(pages[i]);
    }
    return out;
}

function buildPageJumpInput(totalPages) {
    const label = pageI18n
        ? pageI18n.t('gallery:pagination.jump', 'Jump to page')
        : 'Jump to page';
    return `<input class="page-jump-input" type="number" min="1" max="${totalPages}" step="1" inputmode="numeric" placeholder="..." aria-label="${esc(label)}" title="${esc(label)}">`;
}

function commitPageJump(input, totalPages, onClick) {
    const raw = input.value.trim();
    if (!raw) return false;
    const pageNumber = Number(raw);
    if (!Number.isInteger(pageNumber)) {
        input.value = '';
        return false;
    }
    const target = Math.min(Math.max(pageNumber, 1), totalPages) - 1;
    onClick(target);
    return true;
}

function esc(s) {
    if (s == null) return '';
    return String(s).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
}

// ---------- Add to collection (novel) ----------
const collectionState = { activeNovelId: null, membership: new Set() };

async function openAddToCollectionModal(novelId) {
    collectionState.activeNovelId = novelId;
    try {
        const r = await fetch(`/api/collections/novels/of/${novelId}`, { credentials: 'same-origin' });
        if (r.ok) {
            const data = await r.json();
            collectionState.membership = new Set(data.collectionIds || []);
        } else {
            collectionState.membership = new Set();
        }
    } catch (e) {
        collectionState.membership = new Set();
    }
    renderAddToCollectionList();
    document.getElementById('modalAddToCollection').classList.add('open');
}

function closeAddToCollectionModal() {
    document.getElementById('modalAddToCollection').classList.remove('open');
    collectionState.activeNovelId = null;
    loadCollections();
    const ids = [...document.querySelectorAll('.card[data-novel-id]')].map(el => Number(el.dataset.novelId));
    if (ids.length) loadMembershipsForNovels(ids);
}

function renderAddToCollectionList() {
    const list = document.getElementById('addToCollectionList');
    if (!state.collections.length) {
        list.innerHTML = `<div class="empty" style="padding:24px">${esc(pageI18n.t('status.no-collections-hint', '暂无收藏夹，请先新建'))}</div>`;
        return;
    }
    list.innerHTML = state.collections.map(c => {
        const icon = c.iconExt
            ? `<img src="/api/collections/${c.id}/icon?v=${c.createdTime}" alt="">`
            : HEART_SVG;
        const checked = collectionState.membership.has(c.id) ? 'checked' : '';
        return `<label class="collection-row" data-id="${c.id}">
            <input type="checkbox" ${checked}>
            <div class="collection-row-icon">${icon}</div>
            <div class="collection-row-name">${esc(c.name)}</div>
            <span class="collection-count">${c.novelCount ?? 0}</span>
        </label>`;
    }).join('');
    list.querySelectorAll('.collection-row').forEach(row => {
        const id = Number(row.dataset.id);
        const cb = row.querySelector('input[type="checkbox"]');
        cb.addEventListener('change', async () => {
            const novelId = collectionState.activeNovelId;
            if (novelId == null) return;
            try {
                if (cb.checked) {
                    const r = await fetch(`/api/collections/${id}/novels/${novelId}`, { method: 'POST', credentials: 'same-origin' });
                    if (!r.ok) throw new Error('HTTP ' + r.status);
                    collectionState.membership.add(id);
                } else {
                    const r = await fetch(`/api/collections/${id}/novels/${novelId}`, { method: 'DELETE', credentials: 'same-origin' });
                    if (!r.ok) throw new Error('HTTP ' + r.status);
                    collectionState.membership.delete(id);
                }
            } catch (e) {
                cb.checked = !cb.checked;
                toast(pageI18n.t('toast.action-failed', '操作失败'), 'error');
            }
        });
    });
}

document.getElementById('addToCollectionClose').addEventListener('click', closeAddToCollectionModal);
document.getElementById('addToCollectionDone').addEventListener('click', closeAddToCollectionModal);
document.getElementById('modalAddToCollection').addEventListener('click', e => {
    if (e.target.id === 'modalAddToCollection') closeAddToCollectionModal();
});

document.getElementById('quickCreateBtn').addEventListener('click', async () => {
    const input = document.getElementById('quickCreateName');
    const name = input.value.trim();
    if (!name) return;
    const btn = document.getElementById('quickCreateBtn');
    btn.disabled = true;
    try {
        const r = await fetch('/api/collections', {
            method: 'POST',
            credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ name })
        });
        if (!r.ok) throw new Error('HTTP ' + r.status);
        const c = await r.json();
        input.value = '';
        state.collections.push(c);
        state.collections.sort((a, b) => (a.sortOrder - b.sortOrder) || (a.id - b.id));
        if (collectionState.activeNovelId != null) {
            await fetch(`/api/collections/${c.id}/novels/${collectionState.activeNovelId}`, { method: 'POST', credentials: 'same-origin' });
            collectionState.membership.add(c.id);
        }
        renderCollections();
        renderCollectionFilterChips();
        renderAddToCollectionList();
        toast(pageI18n.t('toast.created', '创建成功'), 'success');
    } catch (e) {
        toast(pageI18n.t('toast.create-failed', '创建失败'), 'error');
    } finally {
        btn.disabled = false;
    }
});

function toast(msg, type) {
    const c = document.getElementById('toastContainer');
    if (!c) return;
    const el = document.createElement('div');
    el.className = 'toast' + (type ? ' ' + type : '');
    el.textContent = msg;
    c.appendChild(el);
    setTimeout(() => el.remove(), 2800);
}

init();
