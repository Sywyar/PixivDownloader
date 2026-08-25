'use strict';
    function applySeriesDataSourceUi(sources = seriesDataSources()) {
        const activeId = seriesState.dataSourceId;
        document.querySelectorAll('#series-data-source-switcher label').forEach(label => {
            const active = label.dataset.seriesDataSource === activeId;
            label.classList.toggle('active', active);
            const input = label.querySelector('input[type=radio]');
            if (input) input.checked = active;
        });
        const switcher = document.getElementById('series-data-source-switcher');
        if (switcher) switcher.style.display = sources.length ? '' : 'none';
    }

    function renderSeriesDataSourceSwitcher(preserveSelection = false) {
        const switcher = document.getElementById('series-data-source-switcher');
        if (!switcher) return false;
        const sources = seriesDataSources();
        const previousId = seriesState.dataSourceId;
        const preserveAcrossLoadingSnapshot = preserveSelection && !sources.length && previousId != null;
        if (!sources.some(source => source.id === previousId) && !preserveAcrossLoadingSnapshot) {
            seriesState.dataSourceId = sources.length ? sources[0].id : null;
        }
        switcher.replaceChildren();
        sources.forEach((source, index) => {
            const label = document.createElement('label');
            label.dataset.seriesDataSource = source.id;
            label.classList.toggle('active', source.id === seriesState.dataSourceId);

            const input = document.createElement('input');
            input.type = 'radio';
            input.name = 'series-data-source';
            input.value = source.id;
            input.checked = source.id === seriesState.dataSourceId;
            input.id = `series-data-source-${index}`;

            const text = document.createElement('span');
            if (source.displayNamespace && source.displayI18nKey) {
                text.setAttribute('data-i18n', `${source.displayNamespace}:${source.displayI18nKey}`);
            }
            text.textContent = source.id;
            label.appendChild(input);
            label.appendChild(text);
            switcher.appendChild(label);
        });
        if (typeof pageI18n !== 'undefined' && pageI18n) pageI18n.apply(switcher);
        applySeriesDataSourceUi(sources);
        renderSeriesSourceBrowser().catch(error => {
            console.warn('[series] source browser render failed:', error);
        });
        return previousId != null && previousId !== seriesState.dataSourceId;
    }

    function invalidateSeriesRequests() {
        if (seriesState.requestController) {
            try { seriesState.requestController.abort(); } catch (e) { /* best effort */ }
        }
        seriesState.requestController = typeof AbortController === 'function' ? new AbortController() : null;
        seriesState.requestSeq += 1;
    }

    function beginSeriesOperation() {
        invalidateSeriesRequests();
        const sequence = seriesState.requestSeq;
        const sourceId = seriesState.dataSourceId;
        const controller = seriesState.requestController;
        const signal = controller ? controller.signal : null;
        const isCurrent = () => sequence === seriesState.requestSeq
            && sourceId === seriesState.dataSourceId
            && (!controller || controller === seriesState.requestController)
            && (!signal || !signal.aborted);
        return Object.freeze({
            sequence,
            sourceId,
            signal,
            isCurrent,
            assertCurrent() {
                if (!isCurrent()) {
                    const error = new Error('series data source selection is stale');
                    error.code = 'STALE_SERIES_SOURCE';
                    throw error;
                }
            }
        });
    }

    function seriesSignalScope(signals) {
        const active = signals.filter(signal => signal && typeof signal.addEventListener === 'function');
        if (active.length < 2 || typeof AbortController !== 'function') {
            return {signal: active[0], dispose() {}};
        }
        const controller = new AbortController();
        const abort = () => {
            try { controller.abort(); } catch (e) { /* best effort */ }
        };
        active.forEach(signal => {
            if (signal.aborted) abort();
            else signal.addEventListener('abort', abort, {once: true});
        });
        return {
            signal: controller.signal,
            dispose() {
                active.forEach(signal => signal.removeEventListener('abort', abort));
            }
        };
    }

    async function runWithSeriesSignals(operation, activationSignal, action) {
        operation.assertCurrent();
        const scope = seriesSignalScope([operation.signal, activationSignal]);
        try {
            const result = await action(scope.signal);
            operation.assertCurrent();
            return result;
        } finally {
            scope.dispose();
        }
    }

    function seriesOwnerIdentity(kind) {
        const manifest = kind && window.PixivBatch.queueTypes.manifestDescriptor(kind);
        const owner = manifest && manifest.owner;
        if (!owner) return null;
        return [owner.pluginId, owner.packageId, owner.generation, owner.publicationId, kind].join(':');
    }

    // 当前系列作品类型的 series 取得钩子；类型不可用时返回 null，由调用方停止该模式请求。
    function seriesAcq() {
        const acquisition = window.PixivBatch.queueTypes.acquisition(seriesState.kind, 'series');
        if (!acquisition
            || seriesDataSourceDescriptor(acquisition, seriesState.kind).id !== seriesState.dataSourceId) return null;
        return acquisition;
    }
    // 解析系列输入：只试后端声明 series 能力且已成功激活的类型 hook。
    // 返回 {type, seriesId} | {type, resolveWorkId, resolveSeriesId} | null。不可用类型的链接此处无人认领 →
    // 落到「无效 URL」（绝不发起其专属请求）。
    function parseSeriesInput(text) {
        if (!text) return null;
        for (const acq of selectedSeriesAcquisitions()) {
            try {
                const r = acq.parseUrl ? acq.parseUrl(text) : null;
                if (r) return Object.assign({type: acq.type, resolveSeriesId: acq.resolveSeriesId}, r);
            } catch (e) {
                console.warn('[series] 系列输入解析钩子失败：', acq.type, e);
            }
        }
        return null;
    }

    function clearSeriesPreview(message, clearInput = true) {
        resetSeriesState(null);
        const input = document.getElementById('series-input-url');
        if (clearInput && input) input.value = '';
        const area = document.getElementById('series-results-area');
        if (area) {
            area.innerHTML = `<div class="quick-empty">${esc(message || bt(
                'status.series-empty',
                '粘贴当前数据来源支持的系列、合集或关联作品链接'
            ))}</div>`;
        }
        const meta = document.getElementById('series-meta-display');
        if (meta) meta.textContent = '';
        updateSeriesQueueButtons();
        updateExtraFiltersCardVisibility();
        updateSaveScheduleCardVisibility();
    }

    function selectSeriesDataSource(sourceId, resetView = true) {
        const requested = sourceId == null ? '' : String(sourceId);
        const sources = seriesDataSources();
        if (!sources.some(source => source.id === requested)) return false;
        seriesState.dataSourceId = requested;
        applySeriesDataSourceUi(sources);
        if (resetView) clearSeriesPreview(null, true);
        renderSeriesSourceBrowser(true).catch(error => {
            console.warn('[series] source browser render failed:', error);
        });
        return true;
    }

    function resetSeriesState(kind) {
        cleanupSeriesBlobUrls();
        invalidateSeriesRequests();
        seriesState.kind = kind
            ? window.PixivBatch.queueTypes.resolveTypeForMode(kind, 'series')
            : null;
        seriesState.ownerIdentity = seriesOwnerIdentity(seriesState.kind);
        seriesState.seriesId = null;
        seriesState.seriesTitle = '';
        seriesState.seriesAuthorId = null;
        seriesState.seriesAuthorName = '';
        seriesState.seriesTotal = 0;
        seriesState.currentPage = 1;
        seriesState.totalPages = 1;
        seriesState.isLastPage = true;
        seriesState.rawItems = [];
        seriesState.items = [];
        seriesState.allItems = [];
        seriesState.itemsByPage = new Map();
        seriesState.pageCursors = new Map();
        seriesState.filterSummary = {rawCount: 0, filteredCount: 0, bookmarkMetaMissing: 0, bookmarkFilterActive: false};
        seriesState.filterSeq += 1;
        seriesState.renderToken += 1;
        updateSeriesQueueButtons();
        renderSeriesPagination();
        applyNovelSettingsVisibility();
    }

    function cleanupSeriesBlobUrls() {
        seriesState.activeBlobUrls.forEach(u => {
            try { URL.revokeObjectURL(u); } catch {}
        });
        seriesState.activeBlobUrls = [];
    }

    function getSeriesPageSize(kind = seriesState.kind) {
        const acq = window.PixivBatch.queueTypes.acquisition(kind, 'series');
        if (!acq || seriesDataSourceDescriptor(acq, kind).id !== seriesState.dataSourceId) {
            throw new Error('series acquisition is unavailable for selected data source');
        }
        return acq.pageSize;
    }

    function getSeriesFallbackOrder(idx) {
        return (Math.max(1, seriesState.currentPage) - 1) * getSeriesPageSize() + idx + 1;
    }

    function buildSeriesApiPath(seriesId, page, kind = seriesState.kind) {
        const acq = window.PixivBatch.queueTypes.acquisition(kind, 'series');
        if (!acq || seriesDataSourceDescriptor(acq, kind).id !== seriesState.dataSourceId) {
            throw new Error('series acquisition is unavailable for selected data source');
        }
        return acq.apiPath(seriesId, page, {
            cursor: seriesState.pageCursors.get(page),
            seriesTitle: seriesState.seriesTitle,
            limit: getSeriesPageSize(kind)
        });
    }

    function seriesPaginationMode(acq = seriesAcq()) {
        if (!acq || typeof acq.paginationMode !== 'function') return 'page';
        return acq.paginationMode(seriesState.seriesId) === 'cursor' ? 'cursor' : 'page';
    }

    function initializeSeriesPageCursors() {
        seriesState.pageCursors = new Map();
        const acq = seriesAcq();
        if (!acq || seriesPaginationMode(acq) !== 'cursor') return;
        const raw = typeof acq.initialCursor === 'function'
            ? acq.initialCursor(seriesState.seriesId)
            : acq.initialCursor;
        seriesState.pageCursors.set(1, String(raw == null ? '0' : raw));
    }

    function dedupeSeriesItems(items) {
        const seen = new Set();
        const result = [];
        for (const item of Array.isArray(items) ? items : []) {
            const itemId = String(item.id || '');
            if (!itemId || seen.has(itemId)) continue;
            seen.add(itemId);
            result.push(item);
        }
        return result;
    }

    function updateSeriesMetaFromResponse(meta) {
        if (!meta) return;
        seriesState.seriesTitle = meta.title || String(seriesState.seriesId || '');
        seriesState.seriesAuthorId = meta.authorId ?? null;
        seriesState.seriesAuthorName = meta.authorName || '';
        seriesState.seriesTotal = Number(meta.total || 0);
        if (seriesState.seriesTotal > 0) {
            seriesState.totalPages = Math.max(1, Math.ceil(seriesState.seriesTotal / getSeriesPageSize()));
        }
    }

    function rebuildSeriesAllItems() {
        const seen = new Set();
        const allItems = [];
        Array.from(seriesState.itemsByPage.keys())
            .sort((a, b) => a - b)
            .forEach(page => {
                for (const item of seriesState.itemsByPage.get(page) || []) {
                    const itemId = String(item.id || '');
                    if (!itemId || seen.has(itemId)) continue;
                    seen.add(itemId);
                    allItems.push(item);
                }
            });
        seriesState.allItems = allItems;
    }

    function cacheSeriesPageData(data, requestedPage, activate = true) {
        const responsePage = Number(data.page || requestedPage);
        const page = Number.isFinite(responsePage) && responsePage > 0 ? responsePage : requestedPage;
        const items = dedupeSeriesItems(data.items || []);
        updateSeriesMetaFromResponse(data.series);
        if (seriesState.seriesTotal <= 0) {
            seriesState.totalPages = data.isLastPage
                ? page
                : Math.max(seriesState.totalPages || 1, page + 1);
        }
        if (seriesPaginationMode() === 'cursor') {
            const currentCursor = seriesState.pageCursors.get(page);
            const nextCursor = data.nextCursor == null ? '' : String(data.nextCursor);
            const hasMore = data.hasMore === true && data.isLastPage !== true;
            if (hasMore && (!nextCursor || nextCursor === currentCursor)) {
                throw new Error(bt('series.pagination.cursor-stalled', '数据来源分页游标未推进'));
            }
            if (hasMore) {
                seriesState.pageCursors.set(page + 1, nextCursor);
                seriesState.totalPages = Math.max(seriesState.totalPages || 1, page + 1);
            } else {
                seriesState.pageCursors.delete(page + 1);
                seriesState.totalPages = page;
            }
        }
        seriesState.itemsByPage.set(page, items);
        if (activate) {
            seriesState.currentPage = page;
            seriesState.isLastPage = !!data.isLastPage;
            seriesState.rawItems = items;
        }
        rebuildSeriesAllItems();
    }

    async function fetchSeriesPageRequest(page, operation) {
        const acq = seriesAcq();
        if (!acq) throw new Error('series acquisition is unavailable for selected data source');
        const cursor = seriesState.pageCursors.get(page);
        if (seriesPaginationMode(acq) === 'cursor' && cursor == null) {
            throw new Error(bt('series.pagination.cursor-missing', '无法继续当前数据来源的第 {page} 页请求', {page}));
        }
        const apiPath = buildSeriesApiPath(seriesState.seriesId, page);
        const request = window.PixivBatch.queueTypes.prepareAcquisitionRequest(
            seriesState.kind, 'series', apiPath, 'page', {
                seriesId: seriesState.seriesId,
                seriesTitle: seriesState.seriesTitle,
                cursor,
                limit: getSeriesPageSize(),
                page
            });
        return runWithSeriesSignals(operation, request.signal, async signal => {
            const res = await fetch(request.url, Object.assign({}, request.init, {signal}));
            if (!res.ok) {
                const data = await res.json().catch(() => ({}));
                request.assertCurrent();
                throw new Error(data.error || `HTTP ${res.status}`);
            }
            const raw = await res.json();
            request.assertCurrent();
            if (typeof acq.normalizePage !== 'function') return raw;
            return acq.normalizePage(raw, {
                seriesId: seriesState.seriesId,
                seriesTitle: seriesState.seriesTitle,
                cursor,
                limit: getSeriesPageSize(),
                page
            });
        });
    }

    async function fetchSeriesPage(page, operation) {
        const targetPage = Math.max(1, Number(page) || 1);
        if (seriesPaginationMode() === 'cursor' && !seriesState.pageCursors.has(targetPage)) {
            for (let cursorPage = 1; cursorPage < targetPage; cursorPage += 1) {
                operation.assertCurrent();
                if (seriesState.pageCursors.has(cursorPage + 1)) continue;
                if (!seriesState.pageCursors.has(cursorPage)) {
                    throw new Error(bt('series.pagination.cursor-missing', '无法继续当前数据来源的第 {page} 页请求', {
                        page: cursorPage
                    }));
                }
                const data = await fetchSeriesPageRequest(cursorPage, operation);
                operation.assertCurrent();
                cacheSeriesPageData(data, cursorPage, false);
            }
        }
        return fetchSeriesPageRequest(targetPage, operation);
    }

    function setSeriesLoading(message) {
        // 新一轮加载 / 翻页：先展开预览，使加载态与新结果可见。
        resetPreviewCollapse('series-results-area', 'series-pagination');
        document.getElementById('series-results-area').innerHTML =
            `<div class="preview-message preview-message--loading">${esc(message)}</div>`;
        updateSeriesQueueButtons(true);
    }

    function updateSeriesQueueButtons(isLoading = false) {
        const pageBtn = document.getElementById('btn-series-add-page');
        const allBtn = document.getElementById('btn-series-add-all');
        if (pageBtn) pageBtn.disabled = isLoading || seriesState.items.length === 0;
        if (allBtn) allBtn.disabled = isLoading || !seriesState.seriesId || (seriesState.items.length === 0 && seriesState.allItems.length === 0);
    }

    async function loadSeriesPreviewPage(page, operation = null) {
        if (!seriesState.seriesId) return;
        const activeOperation = operation || beginSeriesOperation();
        const requestSeq = activeOperation.sequence;
        const numericPage = Number(page);
        let safePage = Number.isFinite(numericPage) ? Math.max(1, Math.floor(numericPage)) : 1;
        if (seriesState.totalPages > 0) {
            safePage = Math.min(safePage, seriesState.totalPages);
        }
        cleanupSeriesBlobUrls();
        if (seriesState.itemsByPage.has(safePage)) {
            activeOperation.assertCurrent();
            seriesState.currentPage = safePage;
            seriesState.rawItems = seriesState.itemsByPage.get(safePage) || [];
            await applySeriesFilters({});
            if (!activeOperation.isCurrent()) return;
            renderSeriesPagination();
            updateSeriesQueueButtons();
            return;
        }
        setSeriesLoading(bt('status.series-page-loading', '正在加载第 {page} 页...', {page: safePage}));
        const lease = window.PixivBatch.queueTypes.acquisitionLease(seriesState.kind, 'series');
        try {
            const data = await fetchSeriesPage(safePage, activeOperation);
            lease.assertCurrent();
            activeOperation.assertCurrent();
            if (requestSeq !== seriesState.requestSeq) return;
            cacheSeriesPageData(data, safePage);
            await applySeriesFilters({});
            lease.assertCurrent();
            activeOperation.assertCurrent();
            if (requestSeq !== seriesState.requestSeq) return;
            renderSeriesPagination();
            updateSeriesQueueButtons();
            setStatus(bt('status.series-page-load-success', '系列页已加载：{title}（第 {page} / {total} 页）', {
                title: seriesState.seriesTitle,
                page: seriesState.currentPage,
                total: seriesState.totalPages
            }), 'success');
        } catch (e) {
            if (!activeOperation.isCurrent() || requestSeq !== seriesState.requestSeq || !lease.isCurrent()) return;
            document.getElementById('series-results-area').innerHTML =
                `<div class="preview-message preview-message--error">${esc(bt('status.series-load-failed', '加载失败：{message}', {message: e.message}))}</div>`;
            updateSeriesQueueButtons();
            setStatus(bt('status.series-load-failed', '加载失败：{message}', {message: e.message}), 'error');
        }
    }

    async function loadSeriesSelection(nextKind, selection) {
        const parsed = selection && typeof selection === 'object' ? selection : null;
        if (!parsed) throw new Error(bt('status.series-url-invalid', '请输入当前数据来源支持的系列、合集或关联作品 URL'));
        const selectedKind = parsed.type || nextKind;
        resetSeriesState(selectedKind);
        const operation = beginSeriesOperation();
        const requestSeq = operation.sequence;
        document.getElementById('series-results-area').innerHTML =
            `<div class="preview-message preview-message--loading">${esc(bt('status.series-loading', '正在加载系列信息...'))}</div>`;
        document.getElementById('series-meta-display').textContent = '';
        updateSeriesQueueButtons(true);
        const lease = window.PixivBatch.queueTypes.acquisitionLease(selectedKind, 'series');
        try {
            // 直接给出系列 id 的用直接值；只给作品 id 的按该类型的 resolveSeriesId 解析其所属系列。
            let seriesId = parsed.seriesId;
            if (seriesId == null && parsed.resolveWorkId != null) {
                seriesId = await runWithSeriesSignals(operation, lease.signal, signal =>
                    parsed.resolveSeriesId(parsed.resolveWorkId, {signal}));
                lease.assertCurrent();
                operation.assertCurrent();
                if (requestSeq !== seriesState.requestSeq) return;
            }
            lease.assertCurrent();
            operation.assertCurrent();
            if (seriesId == null || String(seriesId).trim() === '') {
                throw new Error(bt('status.series-url-invalid', '请输入当前数据来源支持的系列、合集或关联作品 URL'));
            }
            seriesState.kind = selectedKind;
            seriesState.ownerIdentity = seriesOwnerIdentity(selectedKind);
            seriesState.seriesId = seriesId;
            seriesState.seriesTitle = parsed.seriesTitle || String(seriesId);
            initializeSeriesPageCursors();
            await loadSeriesPreviewPage(1, operation);
            if (!operation.isCurrent()) return;
            applyNovelSettingsVisibility();
            // 系列类型确定后，刷新共享「附加筛选」里页数/字数字段的显隐
            updateExtraFiltersCardVisibility();
        } catch (e) {
            if (!operation.isCurrent() || requestSeq !== seriesState.requestSeq || !lease.isCurrent()) return;
            document.getElementById('series-results-area').innerHTML =
                `<div class="preview-message preview-message--error">${esc(bt('status.series-load-failed', '加载失败：{message}', {message: e.message}))}</div>`;
            renderSeriesPagination();
            updateSeriesQueueButtons();
            setStatus(bt('status.series-load-failed', '加载失败：{message}', {message: e.message}), 'error');
        }
    }

    async function loadSeriesPreview() {
        renderSeriesDataSourceSwitcher();
        const input = document.getElementById('series-input-url');
        const parsed = parseSeriesInput(input.value);
        if (!parsed) {
            setStatus(bt('status.series-url-invalid', '请输入当前数据来源支持的系列、合集或关联作品 URL'), 'error');
            return;
        }
        return loadSeriesSelection(parsed.type, parsed);
    }

    // 系列预览的实时附加筛选：对当前页 rawItems 套同一套 matchSearchFilters，结果写回 items 再渲染。
