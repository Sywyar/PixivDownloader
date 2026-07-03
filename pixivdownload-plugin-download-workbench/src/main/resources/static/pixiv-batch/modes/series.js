'use strict';
    /* ============================================================
       Series mode
    ============================================================ */
    const seriesState = {
        kind: 'illust',
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
        renderToken: 0,
        activeBlobUrls: []
    };

    // 当前系列作品类型的 series 取得钩子（类型不可用 → null → 走宿主内置插画路径）。
    function seriesAcq() {
        return window.PixivBatch.queueTypes.acquisition(seriesState.kind, 'series');
    }

    // 内置插画系列 URL 解析：画师系列页 / 作品页（需解析其所属系列）/ 裸数字（系列 id）。
    function parseIllustSeriesUrl(text) {
        const trimmed = (text || '').trim();
        const seriesMatch = trimmed.match(/\/user\/(\d+)\/series\/(\d+)/);
        if (seriesMatch) return {seriesId: Number(seriesMatch[2])};
        const artworkMatch = trimmed.match(/\/artworks\/(\d+)/);
        if (artworkMatch) return {resolveWorkId: artworkMatch[1]};
        if (/^\d+$/.test(trimmed)) return {seriesId: Number(trimmed)};
        return null;
    }

    // 解析系列输入：先试各可用类型贡献的系列 URL 解析（如小说系列 / 小说作品页），否则内置插画。
    // 返回 {type, seriesId} | {type, resolveWorkId, resolveSeriesId} | null。不可用类型的链接此处无人认领 →
    // 落到「无效 URL」（绝不发起其专属请求）。
    function parseSeriesInput(text) {
        if (!text) return null;
        for (const acq of window.PixivBatch.queueTypes.acquisitionList('series')) {
            const r = acq.parseUrl ? acq.parseUrl(text) : null;
            if (r) return Object.assign({type: acq.type, resolveSeriesId: acq.resolveSeriesId}, r);
        }
        const illust = parseIllustSeriesUrl(text);
        if (illust) return Object.assign({type: 'illust', resolveSeriesId: resolveSeriesIdFromArtwork}, illust);
        return null;
    }

    async function resolveSeriesIdFromArtwork(artworkId) {
        const res = await fetch(`${BASE}/api/pixiv/artwork/${encodeURIComponent(artworkId)}/meta`,
            {credentials: 'same-origin', headers: pixivHeader()});
        if (!res.ok) {
            const data = await res.json().catch(() => ({}));
            throw new Error(data.error || `HTTP ${res.status}`);
        }
        const meta = await res.json();
        if (!meta.seriesId) {
            throw new Error(bt('status.series-artwork-no-series', '该作品不属于任何漫画系列'));
        }
        return Number(meta.seriesId);
    }

    function resetSeriesState(kind = 'illust') {
        cleanupSeriesBlobUrls();
        seriesState.kind = window.PixivBatch.queueTypes.resolveType(kind);
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
        return (acq && acq.pageSize) || 12;
    }

    function getSeriesFallbackOrder(idx) {
        return (Math.max(1, seriesState.currentPage) - 1) * getSeriesPageSize() + idx + 1;
    }

    function buildSeriesApiPath(seriesId, page, kind = seriesState.kind) {
        const acq = window.PixivBatch.queueTypes.acquisition(kind, 'series');
        return (acq && acq.apiPath)
            ? acq.apiPath(seriesId, page)
            : `/api/pixiv/series/${encodeURIComponent(seriesId)}?page=${page}`;
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

    async function fetchSeriesPage(page) {
        const apiPath = buildSeriesApiPath(seriesState.seriesId, page);
        const res = await fetch(`${BASE}${apiPath}`,
            {credentials: 'same-origin', headers: pixivHeader()});
        if (!res.ok) {
            const data = await res.json().catch(() => ({}));
            throw new Error(data.error || `HTTP ${res.status}`);
        }
        return await res.json();
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

    async function loadSeriesPreviewPage(page) {
        if (!seriesState.seriesId) return;
        const numericPage = Number(page);
        let safePage = Number.isFinite(numericPage) ? Math.max(1, Math.floor(numericPage)) : 1;
        if (seriesState.totalPages > 0) {
            safePage = Math.min(safePage, seriesState.totalPages);
        }
        cleanupSeriesBlobUrls();
        if (seriesState.itemsByPage.has(safePage)) {
            seriesState.currentPage = safePage;
            seriesState.rawItems = seriesState.itemsByPage.get(safePage) || [];
            await applySeriesFilters({});
            renderSeriesPagination();
            updateSeriesQueueButtons();
            return;
        }
        setSeriesLoading(bt('status.series-page-loading', '正在加载第 {page} 页...', {page: safePage}));
        try {
            const data = await fetchSeriesPage(safePage);
            cacheSeriesPageData(data, safePage);
            await applySeriesFilters({});
            renderSeriesPagination();
            updateSeriesQueueButtons();
            setStatus(bt('status.series-page-load-success', '系列页已加载：{title}（第 {page} / {total} 页）', {
                title: seriesState.seriesTitle,
                page: seriesState.currentPage,
                total: seriesState.totalPages
            }), 'success');
        } catch (e) {
            document.getElementById('series-results-area').innerHTML =
                `<div style="color:#dc3545;text-align:center;padding:24px 0;">${esc(bt('status.series-load-failed', '加载失败：{message}', {message: e.message}))}</div>`;
            updateSeriesQueueButtons();
            setStatus(bt('status.series-load-failed', '加载失败：{message}', {message: e.message}), 'error');
        }
    }

    async function loadSeriesPreview() {
        const input = document.getElementById('series-input-url');
        const parsed = parseSeriesInput(input.value);
        if (!parsed) {
            setStatus(bt('status.series-url-invalid', '请输入有效的系列 URL 或同系列作品 URL'), 'error');
            return;
        }
        const nextKind = parsed.type;
        resetSeriesState(nextKind);
        document.getElementById('series-results-area').innerHTML =
            `<div style="color:#888;text-align:center;padding:24px 0;">${esc(bt('status.series-loading', '正在加载系列信息...'))}</div>`;
        document.getElementById('series-meta-display').textContent = '';
        updateSeriesQueueButtons(true);
        try {
            // 直接给出系列 id 的用直接值；只给作品 id 的按该类型的 resolveSeriesId 解析其所属系列。
            let seriesId = parsed.seriesId;
            if (seriesId == null && parsed.resolveWorkId != null) {
                seriesId = await parsed.resolveSeriesId(parsed.resolveWorkId);
            }
            seriesState.kind = nextKind;
            seriesState.seriesId = seriesId;
            seriesState.seriesTitle = String(seriesId);
            await loadSeriesPreviewPage(1);
            applyNovelSettingsVisibility();
            // 系列类型确定后，刷新共享「附加筛选」里页数/字数字段的显隐
            updateExtraFiltersCardVisibility();
        } catch (e) {
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
        const typePart = (acq && acq.typeLabel)
            ? acq.typeLabel()
            : bt('series.meta.type-manga', '漫画系列');
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
            const tips = [bt('search.summary.pixiv-current-page-results', 'Pixiv 当前页 {count} 个结果', {count: seriesState.rawItems.length})];
            if (seriesState.filterSummary.bookmarkFilterActive && seriesState.filterSummary.bookmarkMetaMissing > 0) {
                tips.push(bt('search.summary.bookmark-missing', '{count} 个收藏数不可用已排除', {count: seriesState.filterSummary.bookmarkMetaMissing}));
            }
            area.innerHTML = `<div style="color:#aaa;text-align:center;padding:24px 0;">${esc(bt('status.search-no-filtered-results', '附加筛选后无结果'))}<br><span style="font-size:12px;">${tips.map(t => `<span>${esc(t)}</span>`).join(summarySeparator())}</span></div>`;
            renderSeriesPagination();
            updateSeriesQueueButtons();
            applyNovelSettingsVisibility();
            return;
        }
        if (acq && acq.render) {
            acq.render(area, {items: seriesState.items, inQueue, renderToken});
            renderSeriesPagination();
            updateSeriesQueueButtons();
            applyNovelSettingsVisibility();
            return;
        }
        area.innerHTML = `
    <div class="search-grid">
      ${seriesState.items.map((item, idx) => {
            const xr = Number(item.xRestrict ?? 0);
            const isAi = Number(item.aiType ?? 0) >= 2;
            const r18Badge = xr === 2
                ? '<span class="thumb-badge" style="background:#b91c1c;">R-18G</span>'
                : xr === 1 ? '<span class="thumb-badge">R-18</span>' : '';
            const aiBadge = isAi ? '<span class="thumb-badge thumb-badge-ai">AI</span>' : '';
            const seriesOrder = Number(item.seriesOrder || getSeriesFallbackOrder(idx));
            const orderBadge = `<span class="thumb-badge" style="background:#6366f1;">#${seriesOrder}</span>`;
            const pagesLabel = item.pageCount > 1 ? `<span class="thumb-pages">${item.pageCount}P</span>` : '';
            const inQueueClass = inQueue.has(item.id) ? ' in-queue' : '';
            const queueTip = buildQueueToggleTip(inQueue.has(item.id));
            return `<div class="search-thumb${inQueueClass}" id="series-thumb-${idx}"
                     onclick="addSeriesItemToQueue(${idx})" title="#${seriesOrder} ${esc(item.title)} (${esc(item.userName)})${queueTip}">
          <img id="series-thumb-img-${idx}" src="" alt="${esc(item.title)}">
          <div class="thumb-badge-stack">${orderBadge}${r18Badge}${aiBadge}</div>
          ${pagesLabel}
          <span class="thumb-in-queue-mark">✓</span>
          <div class="thumb-title">${esc(item.title)}</div>
        </div>`;
        }).join('')}
    </div>`;
        loadSeriesThumbnailsBatched(seriesState.items, renderToken);
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
        try {
            const params = new URLSearchParams({url: item.thumbnailUrl});
            const res = await fetch(`${BASE}/api/pixiv/thumbnail-proxy?${params}`, {headers: pixivHeader()});
            if (!res.ok) return;
            const blob = await res.blob();
            if (renderToken !== seriesState.renderToken) return;
            const blobUrl = URL.createObjectURL(blob);
            seriesState.activeBlobUrls.push(blobUrl);
            if (imgEl.isConnected) imgEl.src = blobUrl;
        } catch {
        }
    }

    function getSeriesQueueId(item) {
        if (!item) return '';
        const acq = seriesAcq();
        return acq && acq.queueId ? acq.queueId(item) : String(item.id);
    }

    function seriesQueueSource() {
        const acq = seriesAcq();
        return (acq && acq.queueSource) || 'series';
    }

    function buildSeriesQueueMeta(item, idx, fallbackOrder = getSeriesFallbackOrder(idx)) {
        const seriesOrder = Number(item.seriesOrder || fallbackOrder);
        const acq = seriesAcq();
        if (acq && acq.buildQueueMeta) {
            return acq.buildQueueMeta(item, seriesOrder, {
                seriesId: seriesState.seriesId,
                seriesTitle: seriesState.seriesTitle,
                seriesAuthorId: seriesState.seriesAuthorId,
                seriesAuthorName: seriesState.seriesAuthorName
            });
        }
        return {
            title: item.title,
            authorId: item.userId || seriesState.seriesAuthorId,
            authorName: item.userName || seriesState.seriesAuthorName,
            isAi: Number(item.aiType ?? 0) >= 2,
            seriesId: seriesState.seriesId,
            seriesOrder,
            seriesTitle: seriesState.seriesTitle
        };
    }

    function syncSeriesResultsQueueState() {
        if (!seriesState.items.length) return;
        const inQueue = new Set(state.queue.map(q => q.id));
        const acq = seriesAcq();
        seriesState.items.forEach((item, idx) => {
            const queueId = getSeriesQueueId(item);
            const cardId = (acq && acq.cardId) ? acq.cardId(idx) : `series-thumb-${idx}`;
            const el = document.getElementById(cardId);
            if (!el) return;
            el.classList.toggle('in-queue', inQueue.has(queueId));
        });
    }

    function addSeriesItemToQueue(idx) {
        const item = seriesState.items[idx];
        if (!item) return;
        const queueId = getSeriesQueueId(item);
        const alreadyInQueue = state.queue.find(q => q.id === queueId);
        if (alreadyInQueue) {
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
            [buildSeriesQueueMeta(item, idx)],
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

    async function ensureAllSeriesPagesLoaded() {
        let page = 1;
        while (page <= Math.max(1, seriesState.totalPages || 1)) {
            if (!seriesState.itemsByPage.has(page)) {
                setStatus(bt('status.series-fetch-all-progress', '正在补齐系列分页 {page} / {total}...', {
                    page,
                    total: seriesState.totalPages
                }), 'info');
                const data = await fetchSeriesPage(page);
                cacheSeriesPageData(data, page, false);
            }
            page += 1;
        }
        seriesState.rawItems = seriesState.itemsByPage.get(seriesState.currentPage) || seriesState.rawItems;
    }

    async function addAllSeriesResultsToQueue() {
        if (!seriesState.seriesId || (!seriesState.items.length && !seriesState.allItems.length)) return;
        const loadedPages = seriesState.itemsByPage.size;
        if (!uiConfirmKey(
            'dialog.series-add-all-warning',
            '全部加入队列会额外请求该系列的所有分页（漫画每页 12 个，小说每页 30 个），大系列可能耗时并增加 Pixiv 请求量。当前已加载 {loaded} / {total} 页，确认继续？',
            {loaded: loadedPages, total: seriesState.totalPages}
        )) {
            return;
        }
        updateSeriesQueueButtons(true);
        try {
            await ensureAllSeriesPagesLoaded();
            const rawAll = seriesState.allItems.length ? seriesState.allItems : seriesState.rawItems;
            // 附加筛选已开启时，全部入队也只入符合筛选条件的成员（与当前页预览的过滤口径一致）
            let toAdd = rawAll;
            if (hasExtraSearchFilter()) {
                const filters = normalizeSearchFilters(getSearchFiltersFromUI());
                const res = await computeFilteredItems(rawAll, filters, seriesState.kind, () => false);
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
            setStatus(bt('status.series-load-failed', '加载失败：{message}', {message: e.message}), 'error');
        } finally {
            updateSeriesQueueButtons();
        }
    }


// ---- PixivBatch facade ----
window.PixivBatch.modes = window.PixivBatch.modes || {};
window.PixivBatch.modes.series = window.PixivBatch.modes.series || {};
window.PixivBatch.modes.series = Object.assign(window.PixivBatch.modes.series, { loadSeriesPreview, addCurrentSeriesPageToQueue, addAllSeriesResultsToQueue });
