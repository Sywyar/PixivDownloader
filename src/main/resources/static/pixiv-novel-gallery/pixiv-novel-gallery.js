const PAGE_SIZE = 24;
const HEART_SVG = '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z"/></svg>';

let pageI18n;
const state = {
    view: 'all',                // 'all' | 'authors' | 'series'
    page: 0,
    sort: 'date',
    order: 'desc',
    r18: 'any',
    ai: 'any',
    search: '',
    collections: [],
    selectedCollections: new Set(),
    tags: [],
    selectedTags: new Map(),    // tagId -> 'must'|'not'|'or'
    tagMode: 'must',
    tagSearch: '',
    series: [],
    selectedSeries: new Map(),  // seriesId -> 'must'|'not'
    seriesMode: 'must',
    seriesSearch: '',
    authors: [],
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

async function init() {
    pageI18n = await PixivI18n.create({ namespaces: ['gallery', 'novel', 'common'] });
    pageI18n.apply();
    updateOrderToggleLabel();
    await PixivLangSwitcher.mount({
        mountPoint: document.getElementById('langSwitcherAnchor'),
        i18n: pageI18n,
        showLabel: false,
        onChange: (next) => {
            pageI18n = next;
            pageI18n.apply();
            updateOrderToggleLabel();
            syncCollectionFormTitle();
            reloadCurrentView();
        }
    });
    PixivTheme.mount({ mountPoint: document.getElementById('langSwitcherAnchor') });
    setupEventHandlers();
    setupAdminMode();
    await loadCollections();
    await loadTags();
    await loadSeries();
    await loadAuthors();
    applyInitialUrlState();
    reloadCurrentView();
}

function applyInitialUrlState() {
    const params = new URLSearchParams(location.search);
    const ids = [];
    const addId = value => {
        const id = Number(value);
        if (Number.isInteger(id) && id > 0 && !ids.includes(id)) ids.push(id);
    };
    addId(params.get('seriesId'));
    (params.get('seriesIds') || '').split(',').forEach(addId);
    if (!ids.length) return;

    state.selectedSeries.clear();
    ids.forEach(id => state.selectedSeries.set(id, 'must'));
    if (ids.length === 1 && !state.series.some(s => Number(s.seriesId) === ids[0])) {
        const title = params.get('seriesTitle') || `#${ids[0]}`;
        state.series = [{ seriesId: ids[0], title, authorId: null, authorName: '', novelCount: 0 }, ...state.series];
    }
    state.view = 'all';
    state.page = 0;
    renderSeriesChips();
    updateFilterBadge();
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
    // Filter toggle
    document.getElementById('filterToggle').addEventListener('click', () => {
        document.getElementById('filterPanel').classList.toggle('open');
        document.getElementById('filterToggle').classList.toggle('active');
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
    document.getElementById('navViewAll').addEventListener('click', e => { e.preventDefault(); switchView('all'); });
    document.getElementById('navViewAuthors').addEventListener('click', e => { e.preventDefault(); switchView('authors'); });
    document.getElementById('navViewSeries').addEventListener('click', e => { e.preventDefault(); switchView('series'); });

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

function toggleSidebar() { document.getElementById('sidebar').classList.toggle('collapsed'); }
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
    state.selectedCollections.clear();
    if (!exclusive) state.selectedCollections.add(id);
    state.page = 0;
    renderCollections(); renderCollectionFilterChips();
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
        renderTagChips();
    } catch (e) { console.warn('load tags failed', e); }
}

function renderTagChips() {
    const box = document.getElementById('filterTagChips');
    if (!state.tags.length) {
        box.innerHTML = `<span class="chip-empty">${esc(pageI18n.t('filter.tags.empty', '暂无可用标签'))}</span>`;
        return;
    }
    box.innerHTML = state.tags.map(t => {
        const mode = state.selectedTags.get(t.tagId);
        const cls = mode ? `filter-selected mode-${mode}` : '';
        const trans = t.translatedName ? `<span class="tag-trans">${esc(t.translatedName)}</span>` : '';
        return `<button class="chip ${cls}" data-tag-id="${t.tagId}">${esc(t.name)}${trans}<span class="chip-count">${t.novelCount}</span></button>`;
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
        renderAuthorChips();
    } catch (e) { console.warn('load authors failed', e); }
}

function renderAuthorChips() {
    const box = document.getElementById('filterAuthorChips');
    if (!state.authors.length) {
        box.innerHTML = `<span class="chip-empty">${esc(pageI18n.t('filter.authors.empty', '暂无作者'))}</span>`;
        return;
    }
    box.innerHTML = state.authors.map(a => {
        const mode = state.selectedAuthors.get(a.authorId);
        const cls = mode ? `filter-selected mode-${mode}` : '';
        return `<button class="chip ${cls}" data-author-id="${a.authorId}">${esc(a.name)}<span class="chip-count">${a.novelCount}</span></button>`;
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
    state.view = v;
    setActiveViewNav();
    state.page = 0;
    state.authorsView.page = 0;
    state.seriesView.page = 0;
    reloadCurrentView();
}

function setActiveViewNav() {
    document.getElementById('navViewAuthors').classList.toggle('active', state.view === 'authors');
    document.getElementById('navViewSeries').classList.toggle('active', state.view === 'series');
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
    const params = new URLSearchParams({
        page: String(state.page), size: String(PAGE_SIZE),
        sort: state.sort, order: state.order, r18: state.r18, ai: state.ai
    });
    if (state.search) params.set('search', state.search);
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
    } catch (e) {
        document.getElementById('grid').innerHTML = `<div class="empty">${esc(pageI18n.t('novel:status.load-failed', '加载失败'))}</div>`;
        document.getElementById('pagination').innerHTML = '';
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

function renderGrid(items) {
    const grid = document.getElementById('grid');
    if (!items.length) {
        grid.innerHTML = `<div class="empty" style="grid-column:1/-1;">${esc(pageI18n.t('novel:status.empty', '暂无小说'))}</div>`;
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
            <div class="card-meta">${meta.map(m => `<span>${m}</span>`).join('')}</div>
            <div class="work-collections" data-collections-for="${item.novelId}"></div>
            <button class="thumb-heart" data-novel-id="${item.novelId}" title="${heartTitle}" type="button">${HEART_SVG}</button>
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
        grid.innerHTML = `<div class="empty" style="grid-column:1/-1;">${esc(pageI18n.t('novel:batch.gallery.series-empty', '暂无系列'))}</div>`;
        return;
    }
    const unknownAuthor = pageI18n.t('novel:status.unknown-author', '未知');
    const fallbackSeriesTitle = pageI18n.t('novel:series.unknown-title', '未命名系列');
    grid.innerHTML = seriesList.map(s => {
        const title = s.title || fallbackSeriesTitle;
        const author = s.authorName || (s.authorId != null ? '#' + s.authorId : unknownAuthor);
        const countText = pageI18n.t('novel:batch.gallery.series-by-novels', '{count} 部', { count: s.novelCount });
        return `
        <div class="series-card" data-series-id="${s.seriesId}" data-series-title="${esc(s.title || '')}" title="${esc(title)}">
            <div class="series-card-title">${esc(title)}</div>
            <div class="series-card-meta">
                <span class="series-card-author">${esc(author)}</span>
                <span class="series-card-count">${esc(countText)}</span>
            </div>
        </div>`;
    }).join('');
    grid.querySelectorAll('.series-card').forEach(card => {
        card.addEventListener('click', () => {
            const sid = Number(card.dataset.seriesId);
            const stitle = card.dataset.seriesTitle || '';
            window.location.href = buildSeriesDirectoryHref(sid, stitle);
        });
    });
}

function buildSeriesDirectoryHref(seriesId, seriesTitle) {
    const params = new URLSearchParams({
        type: 'novel',
        seriesId: String(seriesId)
    });
    if (seriesTitle) params.set('seriesTitle', seriesTitle);
    return `/pixiv-series.html?${params.toString()}`;
}

function applySeriesFilterFromCard(seriesId, seriesTitle) {
    state.selectedSeries.clear();
    state.selectedSeries.set(seriesId, 'must');
    if (!state.series.some(s => s.seriesId === seriesId)) {
        state.series = [{ seriesId, title: seriesTitle, authorId: null, authorName: '', novelCount: 0 }, ...state.series];
    }
    renderSeriesChips();
    state.view = 'all';
    state.page = 0;
    setActiveViewNav();
    updateFilterBadge();
    reloadCurrentView();
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
    const max = Math.min(totalPages, 9);
    const start = Math.max(0, Math.min(page - 4, totalPages - max));
    const buttons = [];
    buttons.push(`<button data-pg="${page - 1}" ${page === 0 ? 'disabled' : ''}>‹</button>`);
    for (let i = start; i < start + max; i++) {
        buttons.push(`<button class="${i === page ? 'active' : ''}" data-pg="${i}">${i + 1}</button>`);
    }
    buttons.push(`<button data-pg="${page + 1}" ${page >= totalPages - 1 ? 'disabled' : ''}>›</button>`);
    wrap.innerHTML = buttons.join('');
    wrap.querySelectorAll('button[data-pg]').forEach(btn => {
        if (btn.disabled) return;
        btn.addEventListener('click', () => onClick(Number(btn.dataset.pg)));
    });
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
