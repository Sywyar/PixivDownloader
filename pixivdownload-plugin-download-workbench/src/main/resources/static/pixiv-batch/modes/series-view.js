'use strict';
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
            setStatus(bt('status.search-filters-applied', '已应用筛选：') + summaryJoin(parts), 'success');
        }
        return result.stats;
    }
    function renderSeriesResults() {
        const area = document.getElementById('series-results-area');
        const metaEl = document.getElementById('series-meta-display');
        const renderToken = ++seriesState.renderToken;
        if (!seriesState.rawItems.length) {
            area.innerHTML = `<div class="preview-message">${esc(bt('status.series-no-results', '该系列没有可用条目'))}</div>`;
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
        const typePart = acq.typeLabel({
            seriesId: seriesState.seriesId,
            seriesTitle: seriesState.seriesTitle
        });
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
            area.innerHTML = `<div class="preview-message">${esc(bt('status.search-no-filtered-results', '附加筛选后无结果'))}<br><span class="preview-message-detail">${tips.map(t => `<span>${esc(t)}</span>`).join(summarySeparator())}</span></div>`;
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
            `<button data-pixiv-click="loadSeriesPreviewPage(1)" ${cur === 1 ? 'disabled' : ''}>&laquo;</button>` +
            `<button data-pixiv-click="loadSeriesPreviewPage(${cur - 1})" ${cur === 1 ? 'disabled' : ''}>&lsaquo;</button>` +
            pages.map(p =>
                `<button data-pixiv-click="${p === cur ? '' : `loadSeriesPreviewPage(${p})`}" ${p === cur ? 'class="pg-active" disabled' : ''}>${p}</button>`
            ).join('') +
            `<button data-pixiv-click="loadSeriesPreviewPage(${cur + 1})" ${cur === totalPages ? 'disabled' : ''}>&rsaquo;</button>` +
            `<button data-pixiv-click="loadSeriesPreviewPage(${totalPages})" ${cur === totalPages ? 'disabled' : ''}>&raquo;</button>` +
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
