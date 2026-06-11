'use strict';

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
    } catch (e) { console.warn(pageI18n ? pageI18n.t('gallery:log.collections-failed', '加载收藏夹失败') : '加载收藏夹失败', e); }
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
