'use strict';

// ---------- 批量管理（多选删除，仅管理员、仅「全部小说」视图） ----------
function updateBatchButtonVisibility() {
    const btn = document.getElementById('batchManageBtn');
    if (!btn) return;
    btn.style.display = (isNovelAdmin && state.view === 'all') ? 'inline-flex' : 'none';
}

function toggleBatchMode() {
    if (batch.active) { exitBatchMode(); return; }
    batch.active = true;
    resetBatchSelection();
    document.body.classList.add('select-mode');
    const btn = document.getElementById('batchManageBtn');
    if (btn) btn.classList.add('active');
    updateBatchBar();
}

function exitBatchMode() {
    batch.active = false;
    resetBatchSelection();
    closeBatchActionMenu();
    closeBatchDeleteModal();
    closeBatchExportModal();
    closeBatchCollectionModal();
    document.body.classList.remove('select-mode');
    const btn = document.getElementById('batchManageBtn');
    if (btn) btn.classList.remove('active');
    document.querySelectorAll('.card.selected').forEach(c => c.classList.remove('selected'));
    updateBatchBar();
}

function resetBatchSelection() {
    batch.selected.clear();
    batch.excluded.clear();
    batch.mode = 'ids';
    batch.filterSnapshot = null;
    batch.filterKey = '';
    batch.total = 0;
}

function currentBatchFilterSnapshot() {
    const tagBuckets = partitionSelectionMap(state.selectedTags);
    const authorBuckets = partitionSelectionMap(state.selectedAuthors);
    if (state.exclusiveAuthorId != null && Number.isFinite(Number(state.exclusiveAuthorId))) {
        const exId = Number(state.exclusiveAuthorId);
        if (!authorBuckets.must.includes(exId)) authorBuckets.must.push(exId);
    }
    const seriesMust = [];
    const seriesNot = [];
    state.selectedSeries.forEach((mode, id) => (mode === 'must' ? seriesMust : seriesNot).push(id));
    return {
        sort: state.sort,
        order: state.order,
        search: state.search || null,
        searchType: state.searchType,
        r18: state.r18,
        ai: state.ai,
        collectionIds: [...state.selectedCollections],
        tagIds: tagBuckets.must,
        notTagIds: tagBuckets.not,
        orTagIds: tagBuckets.or,
        authorIds: authorBuckets.must,
        notAuthorIds: authorBuckets.not,
        orAuthorIds: authorBuckets.or,
        seriesIds: seriesMust,
        notSeriesIds: seriesNot,
    };
}

function currentBatchFilterKey() {
    return JSON.stringify(currentBatchFilterSnapshot());
}

function batchSelectedCount() {
    return batch.mode === 'filter'
        ? Math.max(0, Number(batch.total || 0) - batch.excluded.size)
        : batch.selected.size;
}

function isBatchCardSelected(id) {
    return batch.mode === 'filter' ? !batch.excluded.has(id) : batch.selected.has(id);
}

function syncVisibleBatchCards() {
    document.querySelectorAll('#grid .card[data-novel-id]').forEach(card => {
        const id = Number(card.dataset.novelId);
        card.classList.toggle('selected', batch.active && isBatchCardSelected(id));
    });
}

function toggleCardSelection(id, card) {
    if (batch.mode === 'filter') {
        if (batch.excluded.has(id)) {
            batch.excluded.delete(id);
        } else {
            batch.excluded.add(id);
        }
    } else {
        if (batch.selected.has(id)) {
            batch.selected.delete(id);
        } else {
            batch.selected.add(id);
        }
    }
    card.classList.toggle('selected', isBatchCardSelected(id));
    updateBatchBar();
}

function selectAllOnPage() {
    resetBatchSelection();
    document.querySelectorAll('#grid .card[data-novel-id]').forEach(card => {
        batch.selected.add(Number(card.dataset.novelId));
        card.classList.add('selected');
    });
    updateBatchBar();
}

function selectAllResults() {
    const total = Number(state.totalElements || 0);
    if (!total) return;
    batch.mode = 'filter';
    batch.filterSnapshot = currentBatchFilterSnapshot();
    batch.filterKey = currentBatchFilterKey();
    batch.total = total;
    batch.selected.clear();
    batch.excluded.clear();
    syncVisibleBatchCards();
    updateBatchBar();
    toast(pageI18n.t('novel-gallery:manage.selected-all-results', '已选择当前筛选结果，共 {count} 项', { count: total }), 'success');
}

function clearBatchSelection() {
    resetBatchSelection();
    closeBatchActionMenu();
    document.querySelectorAll('.card.selected').forEach(c => c.classList.remove('selected'));
    updateBatchBar();
}

function updateBatchBar() {
    const bar = document.getElementById('batchActionBar');
    if (!bar) return;
    bar.classList.toggle('open', batch.active);
    const count = batchSelectedCount();
    document.getElementById('batchCount').textContent = pageI18n.t('novel-gallery:manage.selected-count', '已选 {count} 项', { count });
    const allResults = document.getElementById('batchSelectAllResults');
    if (allResults) allResults.disabled = !batch.active || Number(state.totalElements || 0) === 0;
    document.querySelectorAll('#batchActionMenu [data-action]').forEach(btn => {
        btn.disabled = count === 0;
    });
}

function buildBatchPayload(collectionId) {
    const payload = batch.mode === 'filter'
        ? {
            mode: 'filter',
            filter: batch.filterSnapshot || currentBatchFilterSnapshot(),
            excludeIds: [...batch.excluded],
        }
        : {
            mode: 'ids',
            ids: [...batch.selected],
        };
    if (collectionId != null) payload.collectionId = collectionId;
    return payload;
}

function openBatchDeleteModal() {
    const count = batchSelectedCount();
    if (count === 0) return;
    document.getElementById('batchDeleteMessage').textContent = pageI18n.t('novel-gallery:manage.confirm-message',
        '确定要删除选中的 {count} 本小说吗？这些小说的正文、封面等文件会被永久删除且无法恢复；下载记录将保留删除标记，默认不会被重新下载。',
        { count });
    document.getElementById('modalBatchDelete').classList.add('open');
}

function closeBatchDeleteModal() {
    document.getElementById('modalBatchDelete').classList.remove('open');
}

async function confirmBatchDelete() {
    if (batchSelectedCount() === 0) return;
    const confirmBtn = document.getElementById('batchDeleteConfirm');
    confirmBtn.disabled = true;
    try {
        await runBatchDelete();
    } catch (e) {
        toast(e.message || pageI18n.t('novel-gallery:manage.delete-failed', '删除失败'), 'error');
    } finally {
        confirmBtn.disabled = false;
    }
}

async function batchApi(url, options = {}) {
    const r = await fetch(url, {
        credentials: 'same-origin',
        ...options,
        headers: {
            'Accept': 'application/json',
            ...(options.headers || {}),
        },
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
        } catch (_) { /* ignore */ }
        throw new Error(msg);
    }
    if (r.status === 204) return null;
    const ct = r.headers.get('content-type') || '';
    return ct.includes('application/json') ? r.json() : r.text();
}

async function runBatchDelete() {
    const expected = batchSelectedCount();
    const data = await batchApi('/api/gallery/novels/delete', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(buildBatchPayload())
    });
    const deleted = data && typeof data.deleted === 'number' ? data.deleted : expected;
    toast(pageI18n.t('novel-gallery:manage.delete-success', '已删除 {count} 本小说', { count: deleted }), 'success');
    closeBatchDeleteModal();
    exitBatchMode();
    reloadNovels();
}

function openBatchExportModal() {
    if (batchSelectedCount() === 0) return;
    const deleteCheck = document.getElementById('batchExportDeleteAfter');
    deleteCheck.checked = false;
    document.getElementById('batchExportDeleteHint').hidden = true;
    document.getElementById('modalBatchExport').classList.add('open');
}

function closeBatchExportModal() {
    const modal = document.getElementById('modalBatchExport');
    if (modal) modal.classList.remove('open');
}

async function submitBatchExport() {
    if (batchSelectedCount() === 0) return;
    const confirmBtn = document.getElementById('batchExportConfirm');
    const deleteAfter = document.getElementById('batchExportDeleteAfter').checked;
    confirmBtn.disabled = true;
    try {
        const payload = buildBatchPayload();
        payload.groupBy = document.getElementById('batchExportGroupBy').value;
        payload.format = document.getElementById('batchExportFormat').value;
        payload.deleteAfter = deleteAfter;
        const data = await batchApi('/api/gallery/novels/export', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        });
        closeBatchExportModal();
        if (!data || !data.archiveToken) {
            toast(pageI18n.t('novel-gallery:manage.export-empty', '没有可导出的文件'), 'error');
            return;
        }
        toast(pageI18n.t('novel-gallery:manage.export-started', '正在后台打包，可在任务列表中查看进度'), 'success');
        exitBatchMode();
        watchExportArchive(data.archiveToken, deleteAfter);
    } catch (e) {
        toast(e.message || pageI18n.t('novel-gallery:manage.export-failed', '导出失败'), 'error');
    } finally {
        confirmBtn.disabled = false;
    }
}

function watchExportArchive(token, reloadWhenReady) {
    if (!window.PixivSideModules) return;
    window.PixivSideModules.trackArchive(token, {
        onReady: () => {
            toast(pageI18n.t('novel-gallery:manage.export-ready', '压缩包已就绪，已开始下载'), 'success');
            if (reloadWhenReady) reloadNovels();
        },
        onFailed: status => {
            toast(status === 'empty'
                ? pageI18n.t('novel-gallery:manage.export-empty', '没有可导出的文件')
                : pageI18n.t('novel-gallery:manage.export-failed', '导出失败'), 'error');
        },
    });
    window.PixivSideModules.openTasks();
}

function toggleBatchActionMenu() {
    const menu = document.getElementById('batchActionMenu');
    if (menu) menu.classList.toggle('open');
}

function closeBatchActionMenu() {
    const menu = document.getElementById('batchActionMenu');
    if (menu) menu.classList.remove('open');
}

async function openBatchCollectionModal() {
    if (batchSelectedCount() === 0) return;
    if (!state.collections.length) {
        await loadCollections();
    }
    renderBatchCollectionList();
    document.getElementById('modalBatchCollection').classList.add('open');
}

function closeBatchCollectionModal() {
    const modal = document.getElementById('modalBatchCollection');
    if (modal) modal.classList.remove('open');
}

function batchCollectionIconHtml(collection) {
    return collection.iconExt
        ? `<img src="/api/collections/${collection.id}/icon?v=${collection.createdTime}" alt="">`
        : HEART_SVG;
}

function renderBatchCollectionList() {
    const list = document.getElementById('batchCollectionList');
    if (!list) return;
    if (!state.collections.length) {
        list.innerHTML = `<div class="empty" style="padding:24px">${esc(pageI18n.t('status.no-collections-hint', '暂无收藏夹，请先新建'))}</div>`;
        return;
    }
    list.innerHTML = state.collections.map(c => `
        <button class="collection-row batch-collection-choice" type="button" data-id="${c.id}">
            <div class="collection-row-icon">${batchCollectionIconHtml(c)}</div>
            <div class="collection-row-name">${esc(c.name)}</div>
            <span class="collection-count">${c.novelCount ?? 0}</span>
        </button>
    `).join('');
    list.querySelectorAll('.batch-collection-choice').forEach(row => {
        row.addEventListener('click', () => submitBatchCollect(Number(row.dataset.id)));
    });
}

async function submitBatchCollect(collectionId) {
    if (!collectionId || batchSelectedCount() === 0) return;
    try {
        const data = await batchApi('/api/gallery/novels/collect', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(buildBatchPayload(collectionId))
        });
        const changed = data && typeof data.changed === 'number' ? data.changed : 0;
        toast(pageI18n.t('novel-gallery:manage.collect-success', '已添加 {count} 本小说到收藏夹', { count: changed }), 'success');
        closeBatchCollectionModal();
        exitBatchMode();
        await loadCollections();
        reloadNovels();
    } catch (e) {
        toast(e.message || pageI18n.t('novel-gallery:manage.collect-failed', '添加到收藏夹失败'), 'error');
    }
}

function wireBatchManage() {
    const manageBtn = document.getElementById('batchManageBtn');
    if (manageBtn) manageBtn.addEventListener('click', toggleBatchMode);
    document.getElementById('batchSelectAll').addEventListener('click', selectAllOnPage);
    document.getElementById('batchSelectAllResults').addEventListener('click', selectAllResults);
    document.getElementById('batchClear').addEventListener('click', clearBatchSelection);
    document.getElementById('batchExit').addEventListener('click', exitBatchMode);
    document.getElementById('batchActionMenuToggle').addEventListener('click', e => {
        e.stopPropagation();
        toggleBatchActionMenu();
    });
    document.querySelectorAll('#batchActionMenu [data-action]').forEach(btn => {
        btn.addEventListener('click', () => {
            closeBatchActionMenu();
            const action = btn.dataset.action;
            if (action === 'delete') {
                openBatchDeleteModal();
            } else if (action === 'export') {
                openBatchExportModal();
            } else if (action === 'collect') {
                openBatchCollectionModal();
            }
        });
    });
    document.addEventListener('click', e => {
        if (!e.target.closest('#batchActionMenu') && !e.target.closest('#batchActionMenuToggle')) {
            closeBatchActionMenu();
        }
    });
    document.getElementById('batchDeleteClose').addEventListener('click', closeBatchDeleteModal);
    document.getElementById('batchDeleteCancel').addEventListener('click', closeBatchDeleteModal);
    document.getElementById('batchDeleteConfirm').addEventListener('click', confirmBatchDelete);
    document.getElementById('modalBatchDelete').addEventListener('click', e => {
        if (e.target.id === 'modalBatchDelete') closeBatchDeleteModal();
    });
    document.getElementById('batchExportClose').addEventListener('click', closeBatchExportModal);
    document.getElementById('batchExportCancel').addEventListener('click', closeBatchExportModal);
    document.getElementById('batchExportConfirm').addEventListener('click', submitBatchExport);
    document.getElementById('batchExportDeleteAfter').addEventListener('change', e => {
        document.getElementById('batchExportDeleteHint').hidden = !e.target.checked;
    });
    document.getElementById('modalBatchExport').addEventListener('click', e => {
        if (e.target.id === 'modalBatchExport') closeBatchExportModal();
    });
    document.getElementById('batchCollectionClose').addEventListener('click', closeBatchCollectionModal);
    document.getElementById('batchCollectionCancel').addEventListener('click', closeBatchCollectionModal);
    document.getElementById('modalBatchCollection').addEventListener('click', e => {
        if (e.target.id === 'modalBatchCollection') closeBatchCollectionModal();
    });
}
