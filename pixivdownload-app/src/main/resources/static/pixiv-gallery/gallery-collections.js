'use strict';

    // ---------- Collections sidebar ----------
    async function loadCollections() {
        try {
            const resp = await api('/api/collections');
            state.collections = resp.collections || [];
            renderCollections();
        } catch (e) {
            console.error(t('log.collections-failed', '加载收藏夹失败'), e);
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


// ---- PixivGallery facade ----
window.PixivGallery.collections = Object.assign(window.PixivGallery.collections || {}, { loadCollections, renderCollections, renderCollectionFilterChips, iconHtml, toggleCollectionFilter, switchCollectionFilter, resetFiltersForSidebarCollection, openCollectionFormModal, closeCollectionFormModal, defaultCollectionDownloadRoot, syncCollectionDownloadRootControls, renderCollectionDownloadRootPreview, expandCollectionDownloadRoot, safeCollectionNamePathSegment, isAbsoluteCollectionDownloadRoot, stripLeadingPathSeparators, joinPreviewPath, readCollectionDownloadRoot, hasControlCharacter, updateFormIconPreview, openCollectionContextMenu, closeContextMenu, updateOrderToggleLabel, syncCollectionFormTitle, bindTristate });
