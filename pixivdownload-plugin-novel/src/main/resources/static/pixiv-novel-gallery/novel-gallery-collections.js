'use strict';

let collectionPending = false;
let collectionReadRevision = 0;
let collectionModalRevision = 0;

function syncCollectionControls() {
    document.getElementById('quickCreateBtn').disabled = collectionPending;
    document.getElementById('addToCollectionList').querySelectorAll('.collection-row').forEach(row => {
        const checkbox = row.querySelector('input[type="checkbox"]');
        checkbox.disabled = collectionPending;
        if (!collectionPending) checkbox.checked = collectionState.membership.has(Number(row.dataset.id));
    });
}

function setCollectionPending(pending) {
    collectionPending = pending;
    ++collectionReadRevision;
    ++collectionLoadRevision;
    ++galleryMembershipRevision;
    syncCollectionControls();
}


let collectionFormRevision = 0;
let collectionIconPreviewUrl = null;

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
    ++collectionFormRevision;
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
    ++collectionFormRevision;
    document.getElementById('modalCollectionForm').classList.remove('open');
    state.editingCollection = null;
    state.pendingIconFile = null;
    state.pendingIconClear = false;
    updateFormIconPreview(null);
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
        ? pageI18n.t('novel-gallery:collection.form.edit', '编辑收藏夹')
        : pageI18n.t('novel-gallery:collection.form.create', '新建收藏夹');
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
    releaseCollectionIconPreview();
    const preview = document.getElementById('collectionFormIconPreview');
    const clearBtn = document.getElementById('collectionFormIconClear');
    if (state.pendingIconFile) {
        collectionIconPreviewUrl = URL.createObjectURL(state.pendingIconFile);
        preview.innerHTML = `<img src="${collectionIconPreviewUrl}" alt="">`;
        clearBtn.style.display = '';
    } else if (collection && collection.iconExt && !state.pendingIconClear) {
        preview.innerHTML = `<img src="/api/collections/${collection.id}/icon?v=${Date.now()}" alt="">`;
        clearBtn.style.display = '';
    } else {
        preview.innerHTML = HEART_SVG;
        clearBtn.style.display = 'none';
    }
}

function releaseCollectionIconPreview() {
    document.getElementById('collectionFormIconPreview').replaceChildren();
    if (collectionIconPreviewUrl) URL.revokeObjectURL(collectionIconPreviewUrl);
    collectionIconPreviewUrl = null;
}

async function submitCollectionForm() {
    const name = document.getElementById('collectionFormName').value.trim();
    if (!name) {
        toast(pageI18n.t('novel-gallery:toast.name-required', '请输入名称'), 'error');
        return;
    }
    const downloadRoot = readCollectionDownloadRoot();
    if (document.getElementById('collectionDownloadRootEnabled').checked && !downloadRoot) {
        toast(pageI18n.t('novel-gallery:toast.download-root-required', '请填写下载目录'), 'error');
        return;
    }
    if (downloadRoot && hasControlCharacter(downloadRoot)) {
        toast(pageI18n.t('novel-gallery:toast.download-root-invalid', '下载目录不能包含控制字符'), 'error');
        return;
    }

    const btn = document.getElementById('collectionFormSubmit');
    if (btn.disabled) return;
    const revision = collectionFormRevision;
    const editingCollection = state.editingCollection;
    const iconFile = state.pendingIconFile;
    const clearIcon = state.pendingIconClear;
    btn.disabled = true;
    try {
        let collection;
        if (editingCollection) {
            await collectionApi(`/api/collections/${editingCollection.id}`, {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ name })
            });
            await collectionApi(`/api/collections/${editingCollection.id}/download-root`, {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ downloadRoot })
            });
            collection = { ...editingCollection, name, downloadRoot };
        } else {
            collection = await collectionApi('/api/collections', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ name, downloadRoot })
            });
        }

        if (iconFile) {
            const form = new FormData();
            form.append('file', iconFile);
            await collectionApi(`/api/collections/${collection.id}/icon`, { method: 'POST', body: form });
        } else if (clearIcon && editingCollection) {
            await collectionApi(`/api/collections/${collection.id}/icon`, { method: 'DELETE' });
        }

        toast(
            editingCollection
                ? pageI18n.t('novel-gallery:toast.saved', '已保存')
                : pageI18n.t('novel-gallery:toast.created', '创建成功'),
            'success'
        );
        if (revision === collectionFormRevision) closeCollectionFormModal();
        await loadCollections();
    } catch (e) {
        toast(e.message || pageI18n.t('novel-gallery:toast.save-failed', '保存失败'), 'error');
    } finally {
        btn.disabled = false;
    }
}

// ---------- Collections (sidebar + filter chips) ----------
let collectionLoadRevision = 0;
async function loadCollections() {
    const revision = ++collectionLoadRevision;
    try {
        const r = await fetch('/api/collections', { credentials: 'same-origin' });
        if (!r.ok) return;
        const data = await r.json();
        if (revision !== collectionLoadRevision || collectionPending) return;
        state.collections = data.collections || [];
        renderCollections();
        renderCollectionFilterChips();
    } catch (e) { console.warn(pageI18n ? pageI18n.t('novel-gallery:log.collections-failed', '加载收藏夹失败') : '加载收藏夹失败', e); }
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

// ---------- Add to collection (novel) ----------
const collectionState = { activeNovelId: null, membership: new Set() };

async function refreshCollectionMembership() {
    const novelId = collectionState.activeNovelId;
    if (novelId == null) return;
    const revision = ++collectionReadRevision;
    try {
        const r = await fetch(`/api/collections/novels/of/${novelId}`, { credentials: 'same-origin' });
        if (!r.ok) return;
        const data = await r.json();
        if (revision !== collectionReadRevision || collectionPending || novelId !== collectionState.activeNovelId) return;
        collectionState.membership = new Set(data.collectionIds || []);
        syncCollectionControls();
    } catch (_) { /* 保留已确认的收藏状态 */ }
}

async function openAddToCollectionModal(novelId) {
    const revision = ++collectionModalRevision;
    collectionState.activeNovelId = novelId;
    collectionState.membership = new Set();
    await refreshCollectionMembership();
    if (revision !== collectionModalRevision) return;
    renderAddToCollectionList();
    document.getElementById('modalAddToCollection').classList.add('open');
}

function closeAddToCollectionModal() {
    ++collectionModalRevision;
    ++collectionReadRevision;
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
            if (collectionPending) return;
            const targetNovelId = collectionState.activeNovelId;
            if (targetNovelId == null) return;
            const membership = collectionState.membership;
            const selected = cb.checked;
            setCollectionPending(true);
            try {
                const r = await fetch(`/api/collections/${id}/novels/${encodeURIComponent(targetNovelId)}`, {
                    method: selected ? 'POST' : 'DELETE', credentials: 'same-origin'
                });
                if (!r.ok) throw new Error('HTTP ' + r.status);
                if (selected) membership.add(id);
                else membership.delete(id);
            } catch (e) {
                toast(pageI18n.t('toast.action-failed', '操作失败'), 'error');
            } finally {
                setCollectionPending(false);
                await refreshCollectionMembership();
                loadCollections();
                const ids = [...document.querySelectorAll('.card[data-novel-id]')].map(el => Number(el.dataset.novelId));
                if (ids.length) loadMembershipsForNovels(ids);
            }
        });
    });
    syncCollectionControls();
}
