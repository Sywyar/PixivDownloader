'use strict';

    // ---------- 批量管理（多选删除，仅管理员、仅「全部图片」视图） ----------
    function updateBatchButtonVisibility() {
        const btn = document.getElementById('batchManageBtn');
        if (!btn) return;
        btn.style.display = (isGalleryAdmin && state.view === 'all') ? 'inline-flex' : 'none';
    }

    function toggleBatchMode() {
        if (batch.active) {
            exitBatchMode();
            return;
        }
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
        document.querySelectorAll('.work-card.selected').forEach(c => c.classList.remove('selected'));
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
        return {
            sort: state.sort,
            order: state.order,
            search: state.search || null,
            searchType: state.searchType,
            r18: state.r18,
            ai: state.ai,
            formats: [...state.formats],
            collectionIds: [...state.collectionIds],
            tagIds: [...state.tagFilters.must],
            notTagIds: [...state.tagFilters.not],
            orTagIds: [...state.tagFilters.or],
            authorIds: [...state.authorFilters.must],
            notAuthorIds: [...state.authorFilters.not],
            orAuthorIds: [...state.authorFilters.or],
            seriesId: state.seriesFilter.id || null,
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
        document.querySelectorAll('#galleryGrid .work-card[data-id]').forEach(card => {
            const id = Number(card.dataset.id);
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
        document.querySelectorAll('#galleryGrid .work-card[data-id]').forEach(card => {
            batch.selected.add(Number(card.dataset.id));
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
        toast(t('manage.selected-all-results', '已选择当前筛选结果，共 {count} 项', {count: total}), 'success');
    }

    function clearBatchSelection() {
        resetBatchSelection();
        closeBatchActionMenu();
        document.querySelectorAll('.work-card.selected').forEach(c => c.classList.remove('selected'));
        updateBatchBar();
    }

    function updateBatchBar() {
        const bar = document.getElementById('batchActionBar');
        if (!bar) return;
        bar.classList.toggle('open', batch.active);
        const count = batchSelectedCount();
        document.getElementById('batchCount').textContent = t('manage.selected-count', '已选 {count} 项', {count});
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
        document.getElementById('batchDeleteMessage').textContent = t('manage.confirm-message',
            '确定要删除选中的 {count} 个作品吗？这些作品的图片文件会被永久删除且无法恢复；下载记录将保留删除标记，默认不会被重新下载。',
            {count});
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
            toast(e.message || t('manage.delete-failed', '删除失败'), 'error');
        } finally {
            confirmBtn.disabled = false;
        }
    }

    async function runBatchDelete() {
        const expected = batchSelectedCount();
        const res = await api('/api/gallery/artworks/delete', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify(buildBatchPayload()),
        });
        const deleted = res && typeof res.deleted === 'number' ? res.deleted : expected;
        toast(t('manage.delete-success', '已删除 {count} 个作品', {count: deleted}), 'success');
        closeBatchDeleteModal();
        exitBatchMode();
        loadGallery();
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
            const res = await api('/api/gallery/artworks/export', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify(payload),
            });
            closeBatchExportModal();
            if (!res || !res.archiveToken) {
                toast(t('manage.export-empty', '没有可导出的文件'), 'error');
                return;
            }
            toast(t('manage.export-started', '正在后台打包，可在任务列表中查看进度'), 'success');
            exitBatchMode();
            watchExportArchive(res.archiveToken, deleteAfter);
        } catch (e) {
            toast(e.message || t('manage.export-failed', '导出失败'), 'error');
        } finally {
            confirmBtn.disabled = false;
        }
    }

    function watchExportArchive(token, reloadWhenReady) {
        if (!window.PixivSideModules) return;
        window.PixivSideModules.trackArchive(token, {
            onReady: () => {
                toast(t('manage.export-ready', '压缩包已就绪，已开始下载'), 'success');
                if (reloadWhenReady) loadGallery();
            },
            onFailed: status => {
                toast(status === 'empty'
                    ? t('manage.export-empty', '没有可导出的文件')
                    : t('manage.export-failed', '导出失败'), 'error');
            },
        });
        window.PixivSideModules.openTasks();
    }

    function toggleBatchActionMenu() {
        const menu = document.getElementById('batchActionMenu');
        if (!menu) return;
        menu.classList.toggle('open');
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

    function renderBatchCollectionList() {
        const list = document.getElementById('batchCollectionList');
        if (!list) return;
        if (!state.collections.length) {
            list.innerHTML = '<div class="empty-state" style="padding:24px">' + escapeHtml(t('status.no-collections-hint', '暂无收藏夹，请先新建')) + '</div>';
            return;
        }
        list.innerHTML = state.collections.map(c => `
            <button class="collection-row batch-collection-choice" type="button" data-id="${c.id}">
                <div class="collection-row-icon">${iconHtml(c)}</div>
                <div class="collection-row-name">${escapeHtml(c.name)}</div>
                <span class="collection-count">${c.artworkCount}</span>
            </button>
        `).join('');
        list.querySelectorAll('.batch-collection-choice').forEach(row => {
            row.addEventListener('click', () => submitBatchCollect(Number(row.dataset.id)));
        });
    }

    async function submitBatchCollect(collectionId) {
        if (!collectionId || batchSelectedCount() === 0) return;
        try {
            const res = await api('/api/gallery/artworks/collect', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify(buildBatchPayload(collectionId)),
            });
            const changed = res && typeof res.changed === 'number' ? res.changed : 0;
            toast(t('manage.collect-success', '已添加 {count} 个作品到收藏夹', {count: changed}), 'success');
            closeBatchCollectionModal();
            exitBatchMode();
            await loadCollections();
            loadGallery();
        } catch (e) {
            toast(e.message || t('manage.collect-failed', '添加到收藏夹失败'), 'error');
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

// ---- PixivGallery facade ----
window.PixivGallery.batch = Object.assign(window.PixivGallery.batch || {}, { updateBatchButtonVisibility, toggleBatchMode, exitBatchMode, resetBatchSelection, currentBatchFilterSnapshot, currentBatchFilterKey, batchSelectedCount, isBatchCardSelected, syncVisibleBatchCards, toggleCardSelection, selectAllOnPage, selectAllResults, clearBatchSelection, updateBatchBar, buildBatchPayload, openBatchDeleteModal, closeBatchDeleteModal, confirmBatchDelete, runBatchDelete, openBatchExportModal, closeBatchExportModal, submitBatchExport, watchExportArchive, toggleBatchActionMenu, closeBatchActionMenu, openBatchCollectionModal, closeBatchCollectionModal, renderBatchCollectionList, submitBatchCollect, wireBatchManage });
