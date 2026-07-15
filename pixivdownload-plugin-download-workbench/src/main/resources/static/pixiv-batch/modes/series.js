'use strict';
    /* ============================================================
       Series mode
    ============================================================ */
    const seriesState = {
        dataSourceId: null,
        kind: null,
        ownerIdentity: null,
        seriesId: null,
        seriesTitle: '',
        seriesAuthorId: null,
        seriesAuthorName: '',
        seriesTotal: 0,
        currentPage: 1,
        totalPages: 1,
        isLastPage: true,
        rawItems: [],   // 当前页未过滤的成员（itemsByPage 缓存的也是原始未过滤数据）
        items: [],      // 当前页经附加筛选后的成员（渲染 / 「加入当前页」据此）
        allItems: [],
        itemsByPage: new Map(),
        filterSummary: {rawCount: 0, filteredCount: 0, bookmarkMetaMissing: 0, bookmarkFilterActive: false},
        filterSeq: 0,
        requestSeq: 0,
        requestController: null,
        renderToken: 0,
        activeBlobUrls: []
    };

    function seriesDataSourceDescriptor(acquisition, ownerType = acquisition && acquisition.type) {
        const registry = window.PixivBatch.queueTypes;
        const metadata = acquisition && acquisition.dataSource && typeof acquisition.dataSource === 'object'
            ? acquisition.dataSource : {};
        const manifest = registry && typeof registry.manifestDescriptor === 'function'
            ? (registry.manifestDescriptor(ownerType) || {}) : {};
        const type = ownerType != null ? String(ownerType).trim() : '';
        const id = String(metadata.id || type).trim();
        const rawOrder = metadata.order == null ? manifest.order : metadata.order;
        const order = Number(rawOrder);
        return {
            id,
            displayNamespace: String(metadata.displayNamespace || manifest.displayNamespace || '').trim(),
            displayI18nKey: String(metadata.displayI18nKey || manifest.displayI18nKey || '').trim(),
            order: Number.isFinite(order) ? order : 0,
            ownerType: type
        };
    }

    // 多个 series 类型可以共享同一数据来源（例如 Pixiv 插画与小说系列）。来源元数据由受控
    // acquisition 贡献；旧模块未声明时按自身 type / display token 降级为独立来源。
    function seriesDataSources() {
        const byId = new Map();
        window.PixivBatch.queueTypes.acquisitionList('series').forEach(acquisition => {
            const candidate = seriesDataSourceDescriptor(acquisition);
            if (!candidate.id || !candidate.ownerType) return;
            const existing = byId.get(candidate.id);
            if (!existing) {
                byId.set(candidate.id, Object.assign({}, candidate, {ownerTypes: [candidate.ownerType]}));
                return;
            }
            if (!existing.ownerTypes.includes(candidate.ownerType)) existing.ownerTypes.push(candidate.ownerType);
            if (existing.displayNamespace !== candidate.displayNamespace
                || existing.displayI18nKey !== candidate.displayI18nKey
                || existing.order !== candidate.order) {
                console.warn('[series] 同一数据来源的展示元数据不一致，保留先声明的元数据：', candidate.id);
            }
        });
        return Array.from(byId.values())
            .sort((left, right) => (left.order - right.order) || left.id.localeCompare(right.id));
    }

    function seriesDataSourceIdForOwnerType(ownerType) {
        const owner = ownerType == null ? '' : String(ownerType).trim();
        if (!owner) return null;
        const source = seriesDataSources().find(item => item.ownerTypes.includes(owner));
        return source ? source.id : null;
    }

    function selectedSeriesAcquisitions() {
        const selected = seriesState.dataSourceId;
        if (!selected) return [];
        return window.PixivBatch.queueTypes.acquisitionList('series')
            .filter(acquisition => seriesDataSourceDescriptor(acquisition).id === selected);
    }

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
        return acq.apiPath(seriesId, page);
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
        seriesState.itemsByPage.set(page, items);
        if (activate) {
            seriesState.currentPage = page;
            seriesState.isLastPage = !!data.isLastPage;
            seriesState.rawItems = items;
        }
        rebuildSeriesAllItems();
    }

    async function fetchSeriesPage(page, operation) {
        const acq = seriesAcq();
        if (!acq) throw new Error('series acquisition is unavailable for selected data source');
        const apiPath = acq.apiPath(seriesState.seriesId, page);
        const request = window.PixivBatch.queueTypes.prepareAcquisitionRequest(
            seriesState.kind, 'series', apiPath, 'page', {seriesId: seriesState.seriesId, page});
        return runWithSeriesSignals(operation, request.signal, async signal => {
            const res = await fetch(request.url, Object.assign({}, request.init, {signal}));
            if (!res.ok) {
                const data = await res.json().catch(() => ({}));
                request.assertCurrent();
                throw new Error(data.error || `HTTP ${res.status}`);
            }
            const data = await res.json();
            request.assertCurrent();
            return data;
        });
    }

    function setSeriesLoading(message) {
        // 新一轮加载 / 翻页：先展开预览，使加载态与新结果可见。
        resetPreviewCollapse('series-results-area', 'series-pagination');
        document.getElementById('series-results-area').innerHTML =
            `<div style="color:#888;text-align:center;padding:24px 0;">${esc(message)}</div>`;
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
                `<div style="color:#dc3545;text-align:center;padding:24px 0;">${esc(bt('status.series-load-failed', '加载失败：{message}', {message: e.message}))}</div>`;
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
        const nextKind = parsed.type;
        resetSeriesState(nextKind);
        const operation = beginSeriesOperation();
        const requestSeq = operation.sequence;
        document.getElementById('series-results-area').innerHTML =
            `<div style="color:#888;text-align:center;padding:24px 0;">${esc(bt('status.series-loading', '正在加载系列信息...'))}</div>`;
        document.getElementById('series-meta-display').textContent = '';
        updateSeriesQueueButtons(true);
        const lease = window.PixivBatch.queueTypes.acquisitionLease(nextKind, 'series');
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
            seriesState.kind = nextKind;
            seriesState.ownerIdentity = seriesOwnerIdentity(nextKind);
            seriesState.seriesId = seriesId;
            seriesState.seriesTitle = String(seriesId);
            await loadSeriesPreviewPage(1, operation);
            if (!operation.isCurrent()) return;
            applyNovelSettingsVisibility();
            // 系列类型确定后，刷新共享「附加筛选」里页数/字数字段的显隐
            updateExtraFiltersCardVisibility();
        } catch (e) {
            if (!operation.isCurrent() || requestSeq !== seriesState.requestSeq || !lease.isCurrent()) return;
            document.getElementById('series-results-area').innerHTML =
                `<div style="color:#dc3545;text-align:center;padding:24px 0;">${esc(bt('status.series-load-failed', '加载失败：{message}', {message: e.message}))}</div>`;
            renderSeriesPagination();
            updateSeriesQueueButtons();
            setStatus(bt('status.series-load-failed', '加载失败：{message}', {message: e.message}), 'error');
        }
    }

    // 系列预览的实时附加筛选：对当前页 rawItems 套同一套 matchSearchFilters，结果写回 items 再渲染。
    async function applySeriesFilters(options = {}) {
        const filters = normalizeSearchFilters(options.filters || getSearchFiltersFromUI());
        searchState.currentFilters = filters;
        saveSearchFilterPrefs(filters);
        const kind = seriesState.kind;
        const seq = ++seriesState.filterSeq;
        const isStale = () => seq !== seriesState.filterSeq;

        const bookmarkActive = hasBookmarkFilter(filters);
        const needsBookmarkMeta = bookmarkActive && (seriesState.rawItems || []).some(item => {
            if (getInlineSearchBookmarkCount(item) !== null) return false;
            const cached = getCachedSearchMeta(item.id, kind);
            return !cached || !cached.bookmarkResolved;
        });
        if (bookmarkActive && needsBookmarkMeta && (seriesState.rawItems || []).length) {
            const area = document.getElementById('series-results-area');
            if (area) area.innerHTML = `<div class="search-spinner"><span class="search-spinner-icon"></span>${esc(bt('status.search-reading-bookmarks', '读取当前页收藏数中...'))}</div>`;
            updateSeriesQueueButtons(true);
        }

        const result = await computeFilteredItems(seriesState.rawItems, filters, kind, isStale);
        if (!result) return null;
        seriesState.items = result.filtered;
        seriesState.filterSummary = result.stats;
        renderSeriesResults();
        updateSeriesQueueButtons();

        if (options.setStatus) {
            const parts = [bt('search.summary.current-page', '当前页 {count} 个', {count: result.stats.rawCount})];
            if (hasExtraSearchFilter(filters)) {
                parts.push(bt('search.summary.extra-filtered', '附加筛选后 {count} 个', {count: result.stats.filteredCount}));
                if (result.stats.bookmarkMetaMissing > 0) {
                    parts.push(bt('search.summary.bookmark-missing', '{count} 个收藏数不可用已排除', {count: result.stats.bookmarkMetaMissing}));
                }
            } else {
                parts.push(bt('status.search-no-extra-filters', '未启用附加筛选'));
            }
            setStatus(bt('status.search-filters-applied', '已应用筛选：') + (uiLang() === 'en-US' ? ' ' : '') + summaryJoin(parts), 'success');
        }
        return result.stats;
    }

    function renderSeriesResults() {
        const area = document.getElementById('series-results-area');
        const metaEl = document.getElementById('series-meta-display');
        const renderToken = ++seriesState.renderToken;
        if (!seriesState.rawItems.length) {
            area.innerHTML = `<div style="color:#aaa;text-align:center;padding:24px 0;">${esc(bt('status.series-no-results', '该系列没有可用条目'))}</div>`;
            metaEl.textContent = '';
            renderSeriesPagination();
            updateSeriesQueueButtons();
            applyNovelSettingsVisibility();
            return;
        }
        const acq = seriesAcq();
        const inQueue = new Set(state.queue.map(q => q.id));
        const authorPart = seriesState.seriesAuthorName
            ? bt('series.meta.author', '作者：{name}', {name: seriesState.seriesAuthorName})
            : '';
        const typePart = acq.typeLabel();
        const pagePart = seriesState.totalPages > 1
            ? bt('series.meta.page', '第 {current} / {total} 页', {
                current: seriesState.currentPage,
                total: seriesState.totalPages
            })
            : '';
        metaEl.innerHTML = [
            `<strong>${esc(seriesState.seriesTitle)}</strong>`,
            esc(typePart),
            authorPart ? esc(authorPart) : '',
            esc(bt('series.meta.total', '共 {count} 个作品', {count: seriesState.seriesTotal || seriesState.allItems.length || seriesState.rawItems.length})),
            pagePart ? esc(pagePart) : ''
        ].filter(Boolean).join(' · ');
        // 当前页原始非空、但附加筛选后为空：给出明确提示而非空白网格
        if (!seriesState.items.length) {
            const tips = [bt('search.summary.current-page', '当前页 {count} 个', {count: seriesState.rawItems.length})];
            if (seriesState.filterSummary.bookmarkFilterActive && seriesState.filterSummary.bookmarkMetaMissing > 0) {
                tips.push(bt('search.summary.bookmark-missing', '{count} 个收藏数不可用已排除', {count: seriesState.filterSummary.bookmarkMetaMissing}));
            }
            area.innerHTML = `<div style="color:#aaa;text-align:center;padding:24px 0;">${esc(bt('status.search-no-filtered-results', '附加筛选后无结果'))}<br><span style="font-size:12px;">${tips.map(t => `<span>${esc(t)}</span>`).join(summarySeparator())}</span></div>`;
            renderSeriesPagination();
            updateSeriesQueueButtons();
            applyNovelSettingsVisibility();
            return;
        }
        acq.render(area, {items: seriesState.items, inQueue, renderToken});
        renderSeriesPagination();
        updateSeriesQueueButtons();
        applyNovelSettingsVisibility();
    }

    function renderSeriesPagination() {
        const pag = document.getElementById('series-pagination');
        if (!pag) return;
        const totalPages = Math.max(1, Number(seriesState.totalPages || 1));
        const cur = Math.min(Math.max(1, Number(seriesState.currentPage || 1)), totalPages);
        if (!seriesState.seriesId || totalPages <= 1) {
            pag.style.display = 'none';
            pag.innerHTML = '';
            return;
        }
        pag.style.display = 'flex';
        const radius = 3;
        const pages = [];
        for (let p = Math.max(1, cur - radius); p <= Math.min(totalPages, cur + radius); p++) {
            pages.push(p);
        }
        pag.innerHTML =
            `<button onclick="loadSeriesPreviewPage(1)" ${cur === 1 ? 'disabled' : ''}>&laquo;</button>` +
            `<button onclick="loadSeriesPreviewPage(${cur - 1})" ${cur === 1 ? 'disabled' : ''}>&lsaquo;</button>` +
            pages.map(p =>
                `<button onclick="${p === cur ? '' : `loadSeriesPreviewPage(${p})`}" ${p === cur ? 'class="pg-active" disabled' : ''}>${p}</button>`
            ).join('') +
            `<button onclick="loadSeriesPreviewPage(${cur + 1})" ${cur === totalPages ? 'disabled' : ''}>&rsaquo;</button>` +
            `<button onclick="loadSeriesPreviewPage(${totalPages})" ${cur === totalPages ? 'disabled' : ''}>&raquo;</button>` +
            `<span class="pg-info">${esc(bt('series.pagination.info', '第 {current} / {total} 页 · 共 {count} 个', {
                current: cur,
                total: totalPages,
                count: (seriesState.seriesTotal || seriesState.allItems.length || seriesState.items.length).toLocaleString()
            }))}</span>`;
    }

    async function loadSeriesThumbnailsBatched(items, renderToken) {
        const BATCH = 10;
        for (let i = 0; i < items.length; i += BATCH) {
            if (renderToken !== seriesState.renderToken) return;
            const batch = items.slice(i, i + BATCH);
            await Promise.allSettled(batch.map((item, offset) => loadSingleSeriesThumbnail(item, i + offset, renderToken)));
        }
    }

    async function loadSingleSeriesThumbnail(item, idx, renderToken) {
        if (!item.thumbnailUrl) return;
        const imgEl = document.getElementById(`series-thumb-img-${idx}`);
        if (!imgEl) return;
        const blobUrl = await fetchThumbnailBlobUrl(
            item.thumbnailUrl, seriesState.activeBlobUrls, seriesState.kind, 'series');
        if (renderToken !== seriesState.renderToken) {
            if (blobUrl) {
                try { URL.revokeObjectURL(blobUrl); } catch {}
            }
            return;
        }
        if (blobUrl && imgEl.isConnected) imgEl.src = blobUrl;
    }

    function getSeriesQueueId(item) {
        if (!item) return '';
        return seriesAcq().queueId(item);
    }

    function seriesQueueSource() {
        return seriesAcq().queueSource;
    }

    function buildSeriesQueueMeta(item, idx, fallbackOrder = getSeriesFallbackOrder(idx)) {
        const seriesOrder = Number(item.seriesOrder || fallbackOrder);
        return seriesAcq().buildQueueMeta(item, seriesOrder, {
            seriesId: seriesState.seriesId,
            seriesTitle: seriesState.seriesTitle,
            seriesAuthorId: seriesState.seriesAuthorId,
            seriesAuthorName: seriesState.seriesAuthorName
        });
    }

    function syncSeriesResultsQueueState() {
        if (!seriesState.items.length) return;
        const inQueue = new Set(state.queue.map(q => q.id));
        const acq = seriesAcq();
        seriesState.items.forEach((item, idx) => {
            const queueId = getSeriesQueueId(item);
            const cardId = acq.cardId(idx);
            const el = document.getElementById(cardId);
            if (!el) return;
            el.classList.toggle('in-queue', inQueue.has(queueId));
        });
    }

    function addSeriesItemToQueue(idx) {
        const item = seriesState.items[idx];
        if (!item) return;
        const queueId = getSeriesQueueId(item);
        const meta = buildSeriesQueueMeta(item, idx);
        const alreadyInQueue = state.queue.find(q => q.id === queueId);
        if (alreadyInQueue) {
            const merged = reconcileQueueItemTypeData(alreadyInQueue, meta, 'toggle');
            if (merged.keepExisting) {
                if (merged.changed) {
                    updateStats();
                    saveQueue();
                    renderQueue();
                }
                setStatus(bt('status.already-in-queue', '已在队列中：{title}', {title: item.title}), 'info');
                return;
            }
            const removed = removeFromQueue(queueId);
            if (removed) {
                setStatus(bt('status.removed-from-queue', '已从队列移除：{title}', {title: item.title}), 'info');
            } else {
                setStatus(bt('status.cannot-remove-downloading', '无法移除（正在下载中）：{title}', {title: item.title}), 'warning');
            }
            return;
        }
        const added = addItemsToQueue(
            [queueId],
            [meta],
            seriesQueueSource(),
            ''
        );
        setStatus(added > 0
                ? bt('status.added-to-queue', '已加入队列：{title}', {title: item.title})
                : bt('status.already-in-queue', '已在队列中：{title}', {title: item.title}),
            added > 0 ? 'success' : 'info');
        syncSeriesResultsQueueState();
    }

    function addSeriesItemsToQueue(items, fallbackOrderBase = 0) {
        const ids = items.map(r => getSeriesQueueId(r));
        const metas = items.map((r, idx) => buildSeriesQueueMeta(r, idx, fallbackOrderBase + idx + 1));
        const added = addItemsToQueue(ids, metas, seriesQueueSource(), '');
        syncSeriesResultsQueueState();
        return {added, total: ids.length, existing: ids.length - added};
    }

    function addCurrentSeriesPageToQueue() {
        if (!seriesState.items.length) return;
        const fallbackOrderBase = (Math.max(1, seriesState.currentPage) - 1) * getSeriesPageSize();
        const result = addSeriesItemsToQueue(seriesState.items, fallbackOrderBase);
        setStatus(
            bt(
                'status.added-current-series-page-to-queue',
                '已将当前页 {added} 个作品加入队列（本页 {total} 个，{existing} 个已在队列中）',
                result
            ),
            result.added > 0 ? 'success' : 'info'
        );
    }

    async function ensureAllSeriesPagesLoaded(operation) {
        let page = 1;
        while (page <= Math.max(1, seriesState.totalPages || 1)) {
            operation.assertCurrent();
            if (!seriesState.itemsByPage.has(page)) {
                setStatus(bt('status.series-fetch-all-progress', '正在补齐系列分页 {page} / {total}...', {
                    page,
                    total: seriesState.totalPages
                }), 'info');
                const data = await fetchSeriesPage(page, operation);
                operation.assertCurrent();
                cacheSeriesPageData(data, page, false);
            }
            page += 1;
        }
        seriesState.rawItems = seriesState.itemsByPage.get(seriesState.currentPage) || seriesState.rawItems;
    }

    async function addAllSeriesResultsToQueue() {
        if (!seriesState.seriesId || (!seriesState.items.length && !seriesState.allItems.length)) return;
        const loadedPages = seriesState.itemsByPage.size;
        const operation = beginSeriesOperation();
        const lease = window.PixivBatch.queueTypes.acquisitionLease(seriesState.kind, 'series');
        const requestSeq = operation.sequence;
        const confirmed = await uiConfirmKey(
            'dialog.series-add-all-warning',
            '全部加入队列会额外请求当前数据来源的所有分页，大型系列或合集可能耗时并增加上游请求量。当前已加载 {loaded} / {total} 页，确认继续？',
            {loaded: loadedPages, total: seriesState.totalPages}
        );
        if (!operation.isCurrent() || requestSeq !== seriesState.requestSeq || !lease.isCurrent()) return;
        operation.assertCurrent();
        lease.assertCurrent();
        if (!confirmed) return;
        updateSeriesQueueButtons(true);
        try {
            await ensureAllSeriesPagesLoaded(operation);
            operation.assertCurrent();
            lease.assertCurrent();
            if (requestSeq !== seriesState.requestSeq) return;
            const rawAll = seriesState.allItems.length ? seriesState.allItems : seriesState.rawItems;
            // 附加筛选已开启时，全部入队也只入符合筛选条件的成员（与当前页预览的过滤口径一致）
            let toAdd = rawAll;
            if (hasExtraSearchFilter()) {
                const filters = normalizeSearchFilters(getSearchFiltersFromUI());
                const res = await computeFilteredItems(rawAll, filters, seriesState.kind, () => false);
                operation.assertCurrent();
                lease.assertCurrent();
                if (requestSeq !== seriesState.requestSeq) return;
                if (res) toAdd = res.filtered;
            }
            const result = addSeriesItemsToQueue(toAdd, 0);
            setStatus(
                bt(
                    'status.added-many-to-queue',
                    '已将 {added} 个作品加入队列（共 {total} 个，{existing} 个已在队列中）',
                    result
                ),
                result.added > 0 ? 'success' : 'info'
            );
            renderSeriesResults();
        } catch (e) {
            if (!operation.isCurrent() || requestSeq !== seriesState.requestSeq || !lease.isCurrent()) return;
            setStatus(bt('status.series-load-failed', '加载失败：{message}', {message: e.message}), 'error');
        } finally {
            if (!operation.isCurrent() || requestSeq !== seriesState.requestSeq || !lease.isCurrent()) return;
            updateSeriesQueueButtons();
        }
    }

    function reconcileSeriesTypeAvailability(ready = true) {
        const registry = window.PixivBatch.queueTypes;
        const sourceChanged = renderSeriesDataSourceSwitcher(ready === false);
        const activeKind = seriesState.kind;
        const kindStale = !!activeKind && (!registry.supports(activeKind, 'series')
            || seriesDataSourceIdForOwnerType(activeKind) !== seriesState.dataSourceId
            || seriesState.ownerIdentity !== seriesOwnerIdentity(activeKind));
        if (!kindStale && !sourceChanged) return false;
        const message = ready !== false && kindStale
            ? bt('queue.message.type-unavailable', '该类型当前不可用（其插件已禁用），已暂停')
            : bt('status.series-empty', '粘贴当前数据来源支持的系列、合集或关联作品链接');
        clearSeriesPreview(message, true);
        return true;
    }


// ---- PixivBatch facade ----
window.PixivBatch.modes = window.PixivBatch.modes || {};
window.PixivBatch.modes.series = window.PixivBatch.modes.series || {};
window.PixivBatch.modes.series = Object.assign(window.PixivBatch.modes.series, {
    loadSeriesPreview, addCurrentSeriesPageToQueue, addAllSeriesResultsToQueue,
    renderSeriesDataSourceSwitcher, selectSeriesDataSource, reconcileSeriesTypeAvailability
});
