'use strict';
    // 结束页 = -1（「直到已下载作品为止」哨兵）仅管理员可用：管理员放开输入下限到 -1 并显示提示，
    // 非管理员保持 min=1（输入 -1 会被夹回）。该控件为普通「作品批量获取模式」共享，故仅做权限门控。
    function updateBatchEndPageAdminGate() {
        const endP = document.getElementById('batch-end-page');
        if (endP) {
            endP.min = isAdmin ? -1 : 1;
            if (!isAdmin && parseInt(endP.value, 10) === -1) endP.value = 1;
        }
        const hint = document.getElementById('batch-end-page-hint');
        if (hint) hint.style.display = isAdmin ? '' : 'none';
    }

    function applySearchKindUI() {
        // 批量导入单作品队列可同时含多种作品类型，故显示全部专属字段（下载时按各作品类型分别套用）。
        let showAll = state.mode === 'single-import';
        let kind;
        if (state.mode === QUICK_FETCH_MODE) {
            // 快捷获取按当前作品网格 kind 切换；编辑 quick 类任务时按锁定来源 kind；混合（珍藏集）显示全部字段。
            const qk = scheduleEditingQuickSource ? scheduleEditingQuickSource.kind : quickCurrentKind();
            if (qk === 'mixed') showAll = true;
            kind = qk;
        } else {
            kind = currentModeKind();
        }
        // 插画专属字段（作品类型 / 页数等）：批量导入显示全部；否则非插画类型时隐藏（纯选择页 kind=null 视为插画、照常显示）。
        const nonIllust = !!kind && kind !== 'illust';
        document.querySelectorAll('.search-illust-only').forEach(el => {
            el.style.display = showAll ? '' : (nonIllust ? 'none' : '');
        });
        // 各作品类型贡献的专属筛选字段（如小说字数）：仅遍历**可用**类型（不可用类型其字段根本未注入）；
        // 批量导入显示全部可用类型字段，否则仅当前 kind 命中的类型显示。
        window.PixivBatch.queueTypes.contributionsOf('filters').forEach(f => {
            if (!f.extraSelector) return;
            const visible = showAll || kind === f.type;
            document.querySelectorAll(f.extraSelector).forEach(el => {
                el.style.display = visible ? '' : 'none';
            });
        });
    }

    // ---- 取得侧（search 模式）行为分派：宿主只面向 queueTypes 的 search 钩子调用，插画为内置默认路径 ----
    // 当前结果类型的 search 取得钩子（类型不可用 → null → 走宿主内置插画路径）。
    function searchAcq() {
        return window.PixivBatch.queueTypes.acquisition(searchState.kind, 'search');
    }
    function searchQueueId(item) {
        const acq = searchAcq();
        return acq && acq.queueId ? acq.queueId(item) : String(item.id);
    }
    function searchQueueMeta(item) {
        const acq = searchAcq();
        if (acq && acq.buildQueueMeta) return acq.buildQueueMeta(item);
        return {
            title: item.title,
            authorId: item.userId,
            authorName: item.userName,
            isAi: Number(item.aiType ?? 0) >= 2
        };
    }
    function searchQueueSource() {
        const acq = searchAcq();
        return (acq && acq.queueSource) || 'search';
    }

    /* ============================================================
       搜索模式
    ============================================================ */
    // 本地批量获取分页：每页固定 60 个（与 Pixiv 插画搜索一致）
    const BATCH_PER_PAGE = 60;

    let searchState = {
        rawResults: [],
        results: [],
        total: 0,
        currentPage: 1,
        submode: 'search',  // 'search'=搜索模式 | 'batch'=作品批量获取模式
        localPage: 1,       // 批量获取模式下的本地分页页码
        batchInfo: null,    // 最近一次批量获取的元信息（范围/限额）
        currentWord: '',
        currentMode: 'all',
        currentOrder: 'date_d',
        currentSMode: 's_tag',
        kind: 'illust',     // 'illust' | 'novel' — 当前结果类型，由最近一次 performSearch 设置
        blurR18: false,
        noCookie: false,
        activeBlobUrls: [],
        metaCache: {},
        filterSeq: 0,
        pixivPageCount: 0,
        currentFilters: defaultSearchFilters(),
        filterSummary: {
            rawCount: 0,
            filteredCount: 0,
            bookmarkMetaMissing: 0,
            bookmarkFilterActive: false
        }
    };

    function getSearchClientModeLabel() {
        if (searchState.currentMode === 'r18') return 'R-18';
        if (searchState.currentMode === 'r18g') return 'R-18G';
        return '';
    }

    async function performSearch(page) {
        const word = document.getElementById('search-word').value.trim();
        if (!word) {
            setStatus(bt('alert.search-keyword-required', '请输入搜索关键词'), 'error');
            return;
        }

        const uiMode = (document.getElementById('search-content-filter') || {}).value || 'all';
        const sMode = document.querySelector('input[name="search-smode"]:checked').value;
        const order = document.querySelector('input[name="search-order"]:checked').value;
        const r18Family = uiMode === 'r18' || uiMode === 'r18g' || uiMode === 'r18plus';
        const mode = r18Family ? 'r18' : uiMode;
        const clientFilter = uiMode === 'r18' ? 1 : uiMode === 'r18g' ? 2 : 0;
        // 把选中 kind 解析为可用类型（不可用 → 回退插画），确保不可用类型不会触发其专属搜索请求。
        const kind = window.PixivBatch.queueTypes.resolveType(state.settings.searchKind);

        document.getElementById('search-premium-tip').style.display = order === 'popular_d' ? 'block' : 'none';

        cleanupBlobUrls();

        searchState.currentWord = word;
        searchState.currentMode = uiMode;
        searchState.currentOrder = order;
        searchState.currentSMode = sMode;
        searchState.currentPage = page;
        searchState.submode = 'search';
        searchState.batchInfo = null;
        searchState.kind = kind;
        searchState.filterSeq++;

        document.getElementById('search-results-area').innerHTML =
            `<div class="search-spinner"><span class="search-spinner-icon"></span>${esc(bt('status.searching', '搜索中...'))}</div>`;
        document.getElementById('search-pagination').style.display = 'none';
        document.getElementById('btn-add-all').disabled = true;

        try {
            const acq = window.PixivBatch.queueTypes.acquisition(kind, 'search');
            const endpoint = (acq && acq.searchEndpoint) || '/api/pixiv/search';
            const params = new URLSearchParams({word, order, mode, sMode, page});
            const res = await fetch(`${BASE}${endpoint}?${params}`, {headers: pixivHeader()});
            const data = await res.json();
            if (!res.ok) {
                document.getElementById('search-results-area').innerHTML =
                    `<div style="color:#dc3545;text-align:center;padding:24px 0;">${esc(bt('status.search-failed', '搜索失败：{message}', {message: data.error || bt('queue.unknown-error', '未知错误')}))}</div>`;
                return;
            }
            let items = data.items || [];
            const pixivPageCount = items.length;
            if (clientFilter > 0) items = items.filter(it => Number(it.xRestrict ?? 0) === clientFilter);
            searchState.pixivPageCount = pixivPageCount;
            searchState.rawResults = items;
            searchState.results = items;
            searchState.total = data.total || 0;
            searchState.noCookie = !hasPixivCookie();
            const filterStats = await applyCurrentSearchFilters();
            if (!filterStats) return;
            if (clientFilter > 0) {
                const label = clientFilter === 2
                    ? bt('search.content.r18g', 'R-18G ⚠')
                    : bt('search.content.r18', 'R-18 ⚠');
                const parts = [
                    bt('search.summary.r18plus-raw', 'R-18+ 当前页原始 {count} 个', {count: pixivPageCount}),
                    bt('search.summary.client-filtered', '{label} 筛后 {count} 个', {
                        label,
                        count: searchState.rawResults.length
                    })
                ];
                if (hasExtraSearchFilter()) {
                    parts.push(bt('search.summary.extra-filtered', '附加筛选后 {count} 个', {count: searchState.results.length}));
                    if (searchState.filterSummary.bookmarkMetaMissing > 0) {
                        parts.push(bt(
                            'search.summary.bookmark-missing',
                            '{count} 个收藏数不可用已排除',
                            {count: searchState.filterSummary.bookmarkMetaMissing}
                        ));
                    }
                }
                parts.push(bt('search.summary.pixiv-total', 'Pixiv 总数 {count}', {count: searchState.total.toLocaleString()}));
                setStatus(bt('status.search-complete', '搜索完成：{summary}', {summary: summaryJoin(parts)}), 'success');
            } else {
                const parts = [bt('search.summary.current-page', '当前页 {count} 个', {count: searchState.pixivPageCount})];
                if (hasExtraSearchFilter()) {
                    parts.push(bt('search.summary.extra-filtered', '附加筛选后 {count} 个', {count: searchState.results.length}));
                    if (searchState.filterSummary.bookmarkMetaMissing > 0) {
                        parts.push(bt(
                            'search.summary.bookmark-missing',
                            '{count} 个收藏数不可用已排除',
                            {count: searchState.filterSummary.bookmarkMetaMissing}
                        ));
                    }
                }
                parts.push(bt('search.summary.pixiv-total', 'Pixiv 总数 {count}', {count: searchState.total.toLocaleString()}));
                setStatus(bt('status.search-complete', '搜索完成：{summary}', {summary: summaryJoin(parts)}), 'success');
            }
        } catch (e) {
            document.getElementById('search-results-area').innerHTML =
                `<div style="color:#dc3545;text-align:center;padding:24px 0;">${esc(bt('status.request-failed', '请求失败：{message}', {message: e.message}))}</div>`;
        }
    }

    // 批量获取模式下结果可能跨多页：本地按 BATCH_PER_PAGE 分页。
    // base 为当前视图首项在 searchState.results 中的绝对下标，
    // 渲染与队列同步统一使用绝对下标，避免索引错位。
    function batchLocalPageCount() {
        return Math.max(1, Math.ceil(searchState.results.length / BATCH_PER_PAGE));
    }

    function getSearchView() {
        if (searchState.submode !== 'batch') {
            return {items: searchState.results, base: 0, localPage: 1, localPages: 1};
        }
        const localPages = batchLocalPageCount();
        let localPage = searchState.localPage;
        if (!Number.isFinite(localPage) || localPage < 1) localPage = 1;
        if (localPage > localPages) localPage = localPages;
        searchState.localPage = localPage;
        const base = (localPage - 1) * BATCH_PER_PAGE;
        return {
            items: searchState.results.slice(base, base + BATCH_PER_PAGE),
            base,
            localPage,
            localPages
        };
    }

    function renderSearchResults() {
        const area = document.getElementById('search-results-area');
        if (!searchState.rawResults.length) {
            const acq = searchAcq();
            const empty = (acq && acq.emptyResultsLabel)
                ? acq.emptyResultsLabel()
                : bt('status.search-no-results', '无搜索结果');
            area.innerHTML = `<div style="color:#aaa;text-align:center;padding:24px 0;">${esc(empty)}</div>`;
            return;
        }
        if (!searchState.results.length) {
            const tips = [];
            tips.push(bt('search.summary.pixiv-current-page-results', 'Pixiv 当前页 {count} 个结果', {count: searchState.rawResults.length}));
            if (searchState.filterSummary.bookmarkFilterActive && searchState.filterSummary.bookmarkMetaMissing > 0) {
                tips.push(bt(
                    'search.summary.bookmark-missing',
                    '{count} 个收藏数不可用已排除',
                    {count: searchState.filterSummary.bookmarkMetaMissing}
                ));
            }
            area.innerHTML = `<div style="color:#aaa;text-align:center;padding:24px 0;">${esc(bt('status.search-no-filtered-results', '附加筛选后无结果'))}<br><span style="font-size:12px;">${tips.map(t => `<span>${esc(t)}</span>`).join(summarySeparator())}</span></div>`;
            return;
        }
        const view = getSearchView();
        const renderAcq = searchAcq();
        if (renderAcq && renderAcq.render) {
            renderAcq.render(area, view);
            return;
        }
        const inQueue = new Set(state.queue.map(q => q.id));
        const clientModeLabel = getSearchClientModeLabel();
        const summary = searchState.submode === 'batch'
            ? [batchSummaryText(view)]
            : [
                bt('search.summary.total-results', '共 {count} 个结果', {count: searchState.total.toLocaleString()}),
                bt('search.summary.current-page-index', '当前第 {page} 页', {page: searchState.currentPage}),
                bt('search.summary.pixiv-returned', 'Pixiv 返回 {count} 个', {count: searchState.pixivPageCount})
            ];
        if (clientModeLabel) {
            summary.push(bt('search.summary.client-filtered', '{label} 筛后 {count} 个', {
                label: clientModeLabel,
                count: searchState.rawResults.length
            }));
        }
        if (hasExtraSearchFilter()) {
            summary.push(bt('search.summary.extra-filtered', '附加筛选后 {count} 个', {count: searchState.results.length}));
            if (searchState.filterSummary.bookmarkMetaMissing > 0) {
                summary.push(bt(
                    'search.summary.bookmark-missing',
                    '{count} 个收藏数不可用已排除',
                    {count: searchState.filterSummary.bookmarkMetaMissing}
                ));
            }
        }
        area.innerHTML = `
    ${searchState.noCookie ? `<div style="font-size:12px;color:#e6a700;margin-bottom:8px;">${esc(bt('status.search-no-cookie-warning', '⚠ 未保存 Cookie，搜索结果可能减少'))}</div>` : ''}
    <div style="font-size:12px;color:#888;margin-bottom:10px;">
      ${summary.map(s => `<span>${esc(s)}</span>`).join(summarySeparator())}
    </div>
    <div class="search-grid">
      ${view.items.map((item, i) => {
            const idx = view.base + i;
            const xr = Number(item.xRestrict ?? 0);
            const illustType = Number(item.illustType ?? 0);
            const isR18 = xr >= 1;
            const isAi = Number(item.aiType ?? 0) >= 2;
            const bookmarkCount = getSearchBookmarkCount(item);
            const blurClass = (isR18 && searchState.blurR18) ? 'blur-r18' : '';
            const r18Badge = xr === 2
                ? '<span class="thumb-badge" style="background:#b91c1c;">R-18G</span>'
                : xr === 1 ? '<span class="thumb-badge">R-18</span>' : '';
            const aiBadge = isAi ? '<span class="thumb-badge thumb-badge-ai">AI</span>' : '';
            const typeBadge = illustType === 2
                ? `<span class="thumb-badge" style="background:#0ea5e9;">${esc(bt('search.type.ugoira', '动图'))}</span>`
                : illustType === 1 ? `<span class="thumb-badge" style="background:#f59e0b;">${esc(bt('search.type.manga', '漫画'))}</span>` : '';
            const pagesLabel = item.pageCount > 1 ? `<span class="thumb-pages">${item.pageCount}P</span>` : '';
            const inQueueClass = inQueue.has(item.id) ? ' in-queue' : '';
            const queueTip = buildQueueToggleTip(inQueue.has(item.id));
            const bookmarkTip = buildBookmarkTip(bookmarkCount);
            return `<div class="search-thumb${inQueueClass}" id="thumb-${idx}"
                     onclick="addSearchItemToQueue(${idx})" title="${esc(item.title)} (${esc(item.userName)})${bookmarkTip}${queueTip}">
          <img id="thumb-img-${idx}" src="" alt="${esc(item.title)}" class="${blurClass}">
          <div class="thumb-badge-stack">${r18Badge}${aiBadge}${typeBadge}</div>
          ${pagesLabel}
          <span class="thumb-in-queue-mark">✓</span>
          <div class="thumb-title">${esc(item.title)}</div>
        </div>`;
        }).join('')}
    </div>`;
        loadThumbnailsBatched(view.items, view.base);
    }

    function batchSummaryText(view) {
        const parts = [
            bt('search.batch.summary.fetched', '已抓取去重 {count} 个', {count: searchState.rawResults.length})
        ];
        if (hasExtraSearchFilter()) {
            parts.push(bt('search.summary.extra-filtered', '附加筛选后 {count} 个', {count: searchState.results.length}));
            if (searchState.filterSummary.bookmarkMetaMissing > 0) {
                parts.push(bt('search.summary.bookmark-missing', '{count} 个收藏数不可用已排除',
                    {count: searchState.filterSummary.bookmarkMetaMissing}));
            }
        }
        parts.push(bt('search.batch.summary.local-page', '本地第 {page}/{total} 页',
            {page: view.localPage, total: view.localPages}));
        if (searchState.batchInfo) {
            parts.push(bt('search.batch.summary.range', '范围第 {start}–{end} 页',
                {start: searchState.batchInfo.startPage, end: searchState.batchInfo.endPage}));
        }
        return summaryJoin(parts);
    }

    async function loadThumbnailsBatched(items, base = 0) {
        const BATCH = 10;
        for (let i = 0; i < items.length; i += BATCH) {
            const batch = items.slice(i, i + BATCH);
            await Promise.allSettled(batch.map((item, offset) => loadSingleThumbnail(item, base + i + offset)));
        }
    }

    // 通用缩略图代理拉取：返回 blob URL（同时登记到 blobStore 供之后统一 revoke），失败返回 null。
    async function fetchThumbnailBlobUrl(url, blobStore) {
        if (!url) return null;
        try {
            const params = new URLSearchParams({url});
            const res = await fetch(`${BASE}/api/pixiv/thumbnail-proxy?${params}`, {headers: pixivHeader()});
            if (!res.ok) return null;
            const blob = await res.blob();
            const blobUrl = URL.createObjectURL(blob);
            if (Array.isArray(blobStore)) blobStore.push(blobUrl);
            return blobUrl;
        } catch {
            return null;
        }
    }

    async function loadSingleThumbnail(item, idx) {
        if (!item.thumbnailUrl) return;
        const imgEl = document.getElementById(`thumb-img-${idx}`);
        if (!imgEl) return;
        try {
            const params = new URLSearchParams({url: item.thumbnailUrl});
            const res = await fetch(`${BASE}/api/pixiv/thumbnail-proxy?${params}`, {headers: pixivHeader()});
            if (!res.ok) return;
            const blob = await res.blob();
            const blobUrl = URL.createObjectURL(blob);
            searchState.activeBlobUrls.push(blobUrl);
            if (imgEl.isConnected) imgEl.src = blobUrl;
        } catch {
        }
    }

    function cleanupBlobUrls() {
        searchState.activeBlobUrls.forEach(u => {
            try {
                URL.revokeObjectURL(u);
            } catch {
            }
        });
        searchState.activeBlobUrls = [];
    }

    function syncSearchResultsQueueState() {
        if (!searchState.results.length) return;
        const inQueue = new Set(state.queue.map(q => q.id));
        const acq = searchAcq();
        if (acq && acq.syncQueueState) {
            acq.syncQueueState(searchState.results, inQueue);
            return;
        }
        searchState.results.forEach((item, idx) => {
            const el = document.getElementById(`thumb-${idx}`);
            if (!el) return;
            const isInQueue = inQueue.has(item.id);
            const bookmarkCount = getSearchBookmarkCount(item);
            const bookmarkTip = buildBookmarkTip(bookmarkCount);
            el.classList.toggle('in-queue', isInQueue);
            el.title = `${item.title} (${item.userName || ''})${bookmarkTip}${buildQueueToggleTip(isInQueue)}`;
        });
    }

    function addSearchItemToQueue(idx) {
        const item = searchState.results[idx];
        if (!item) return;
        const queueId = searchQueueId(item);
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
        const added = addItemsToQueue([queueId], [searchQueueMeta(item)], searchQueueSource(), '');
        setStatus(added > 0
                ? bt('status.added-to-queue', '已加入队列：{title}', {title: item.title})
                : bt('status.already-in-queue', '已在队列中：{title}', {title: item.title}),
            added > 0 ? 'success' : 'info');
    }

    function addAllSearchResultsToQueue() {
        if (!searchState.results.length) return;
        const ids = searchState.results.map(searchQueueId);
        const metas = searchState.results.map(searchQueueMeta);
        const added = addItemsToQueue(ids, metas, searchQueueSource(), '');
        setStatus(
            bt(
                'status.added-many-to-queue',
                '已将 {added} 个作品加入队列（共 {total} 个，{existing} 个已在队列中）',
                {added, total: ids.length, existing: ids.length - added}
            ),
            'success'
        );
    }

    function renderSearchPagination() {
        const pag = document.getElementById('search-pagination');
        if (searchState.submode === 'batch') {
            renderBatchLocalPagination(pag);
            return;
        }
        const acq = searchAcq();
        const perPage = (acq && acq.pageSize) || 60;
        const totalPages = Math.ceil(searchState.total / perPage);
        const cur = searchState.currentPage;
        if (totalPages <= 1) {
            pag.style.display = 'none';
            return;
        }
        pag.style.display = 'flex';
        const radius = 3;
        const pages = [];
        for (let p = Math.max(1, cur - radius); p <= Math.min(totalPages, cur + radius); p++) {
            pages.push(p);
        }
        pag.innerHTML =
            `<button onclick="performSearch(1)" ${cur === 1 ? 'disabled' : ''}>«</button>` +
            `<button onclick="performSearch(${cur - 1})" ${cur === 1 ? 'disabled' : ''}>‹</button>` +
            pages.map(p =>
                `<button onclick="${p === cur ? '' : `performSearch(${p})`}" ${p === cur ? 'class="pg-active" disabled' : ''}>${p}</button>`
            ).join('') +
            `<button onclick="performSearch(${cur + 1})" ${cur === totalPages ? 'disabled' : ''}>›</button>` +
            `<button onclick="performSearch(${totalPages})" ${cur === totalPages ? 'disabled' : ''}>»</button>` +
            `<span class="pg-info">${esc(bt('search.pagination.info', '第 {current} / {total} 页 · 共 {count} 个', {
                current: cur,
                total: totalPages,
                count: searchState.total.toLocaleString()
            }))}</span>`;
    }

    function renderBatchLocalPagination(pag) {
        const totalPages = batchLocalPageCount();
        if (!searchState.results.length || totalPages <= 1) {
            pag.style.display = 'none';
            return;
        }
        let cur = searchState.localPage;
        if (!Number.isFinite(cur) || cur < 1) cur = 1;
        if (cur > totalPages) cur = totalPages;
        searchState.localPage = cur;
        pag.style.display = 'flex';
        const radius = 3;
        const pages = [];
        for (let p = Math.max(1, cur - radius); p <= Math.min(totalPages, cur + radius); p++) {
            pages.push(p);
        }
        pag.innerHTML =
            `<button onclick="goBatchPage(1)" ${cur === 1 ? 'disabled' : ''}>«</button>` +
            `<button onclick="goBatchPage(${cur - 1})" ${cur === 1 ? 'disabled' : ''}>‹</button>` +
            pages.map(p =>
                `<button onclick="${p === cur ? '' : `goBatchPage(${p})`}" ${p === cur ? 'class="pg-active" disabled' : ''}>${p}</button>`
            ).join('') +
            `<button onclick="goBatchPage(${cur + 1})" ${cur === totalPages ? 'disabled' : ''}>›</button>` +
            `<button onclick="goBatchPage(${totalPages})" ${cur === totalPages ? 'disabled' : ''}>»</button>` +
            `<span class="pg-info">${esc(bt('search.pagination.info', '第 {current} / {total} 页 · 共 {count} 个', {
                current: cur,
                total: totalPages,
                count: searchState.results.length.toLocaleString()
            }))}</span>`;
    }

    function goBatchPage(p) {
        const totalPages = batchLocalPageCount();
        let next = Number(p);
        if (!Number.isFinite(next) || next < 1) next = 1;
        if (next > totalPages) next = totalPages;
        searchState.localPage = next;
        cleanupBlobUrls();
        renderSearchResults();
        renderSearchPagination();
        updateBatchQueueButtons();
        window.scrollTo({top: document.getElementById('search-results-area').offsetTop - 20, behavior: 'smooth'});
    }

    function toggleBlurR18() {
        searchState.blurR18 = document.getElementById('search-blur-r18').checked;
        storeSet('pixiv_search_blur_r18', String(searchState.blurR18));
        searchState.results.forEach((item, idx) => {
            if (item.xRestrict > 0) {
                const imgEl = document.getElementById(`thumb-img-${idx}`);
                if (imgEl) imgEl.classList.toggle('blur-r18', searchState.blurR18);
            }
        });
    }

    /* ============================================================
       搜索模式 / 作品批量获取模式 子模式切换
    ============================================================ */
    function applySubmodeUI(value, opts = {}) {
        const submode = value === 'batch' ? 'batch' : 'search';
        searchState.submode = submode;
        const root = document.getElementById('search-submode-switcher');
        if (root) {
            root.querySelectorAll('label').forEach(lbl => {
                const matches = lbl.dataset.submode === submode;
                lbl.classList.toggle('active', matches);
                const input = lbl.querySelector('input[type=radio]');
                if (input) input.checked = matches;
            });
        }
        const searchActions = document.getElementById('search-mode-actions');
        const batchActions = document.getElementById('batch-mode-actions');
        if (searchActions) searchActions.style.display = submode === 'batch' ? 'none' : '';
        if (batchActions) batchActions.style.display = submode === 'batch' ? '' : 'none';
        updateBatchLimitNote();
        if (opts.clear) {
            cleanupBlobUrls();
            searchState.rawResults = [];
            searchState.results = [];
            searchState.total = 0;
            searchState.currentWord = '';
            searchState.batchInfo = null;
            searchState.localPage = 1;
            searchState.filterSeq++;
            renderSearchResults();
            renderSearchPagination();
            document.getElementById('btn-add-all').disabled = true;
            updateBatchQueueButtons();
        }
        // 切换 🔍 搜索 / 📦 批量子模式会改变 maxPages 口径，刷新首次抓取上限字段显隐。
        updateScheduleFetchLimitVisibility();
    }

    function bindSubmodeSwitcher() {
        const root = document.getElementById('search-submode-switcher');
        if (!root) return;
        root.querySelectorAll('label').forEach(lbl => {
            lbl.addEventListener('click', () => {
                const next = lbl.dataset.submode === 'batch' ? 'batch' : 'search';
                if (searchState.submode === next) return;
                storeSet('pixiv_search_submode', next);
                applySubmodeUI(next, {clear: true});
            });
        });
    }

    function loadSubmodePref() {
        const saved = storeGet('pixiv_search_submode');
        applySubmodeUI(saved === 'batch' ? 'batch' : 'search', {clear: false});
    }

    function handleSearchEnter() {
        if (searchState.submode === 'batch') {
            runBatchFetch();
        } else {
            performSearch(1);
        }
    }

    // multi 模式下每次抓取页数（end-start+1）受 multi-mode.limit-page 约束；
    // solo 模式与管理员不限制。
    function batchPageLimit() {
        return (appMode !== 'solo' && !isAdmin && multiModeLimitPage > 0) ? multiModeLimitPage : 0;
    }

    function getBatchRange() {
        const startEl = document.getElementById('batch-start-page');
        const endEl = document.getElementById('batch-end-page');
        let start = parseInt(startEl.value, 10);
        let end = parseInt(endEl.value, 10);
        if (!Number.isFinite(start) || start < 1) start = 1;
        // 管理员可把结束页设为 -1（「直到已下载作品为止」哨兵，仅用于计划任务快照）：保留在输入框，不夹取。
        // 实时批量获取（runBatchFetch）会把 -1 当作单页处理，绝不真的把 -1 发给后端。
        if (isAdmin && end === -1) {
            startEl.value = start;
            endEl.value = -1;
            return {start, end: -1};
        }
        if (!Number.isFinite(end) || end < 1) end = start;
        if (end < start) {
            const t = start;
            start = end;
            end = t;
        }
        const limit = batchPageLimit();
        if (limit > 0 && (end - start + 1) > limit) {
            end = start + limit - 1;
        }
        startEl.value = start;
        endEl.value = end;
        return {start, end};
    }

    function handleBatchRangeChange() {
        getBatchRange();
        updateBatchLimitNote();
        // 结束页改成 / 离开 -1（翻页到底）会影响 SEARCH 是否支持首次抓取上限，刷新该字段显隐。
        updateScheduleFetchLimitVisibility();
    }

    function updateBatchQueueButtons() {
        const addPage = document.getElementById('btn-batch-add-page');
        const addAll = document.getElementById('btn-batch-add-all');
        const hasResults = searchState.submode === 'batch' && searchState.results.length > 0;
        if (addAll) addAll.disabled = !hasResults;
        if (addPage) {
            const view = hasResults ? getSearchView() : null;
            addPage.disabled = !(view && view.items.length > 0);
        }
    }

    async function runBatchFetch() {
        const word = document.getElementById('search-word').value.trim();
        if (!word) {
            setStatus(bt('alert.search-keyword-required', '请输入搜索关键词'), 'error');
            return;
        }
        const {start, end} = getBatchRange();
        // 实时批量获取只翻固定页数：-1 哨仅对计划任务有意义，这里按单页（start）处理，绝不把 -1 发给后端。
        const effEnd = end === -1 ? start : end;
        const uiMode = (document.getElementById('search-content-filter') || {}).value || 'all';
        const sMode = document.querySelector('input[name="search-smode"]:checked').value;
        const order = document.querySelector('input[name="search-order"]:checked').value;
        const r18Family = uiMode === 'r18' || uiMode === 'r18g' || uiMode === 'r18plus';
        const mode = r18Family ? 'r18' : uiMode;
        const clientFilter = uiMode === 'r18' ? 1 : uiMode === 'r18g' ? 2 : 0;
        // 把选中 kind 解析为可用类型（不可用 → 回退插画），确保不可用类型不会触发其专属批量请求。
        const kind = window.PixivBatch.queueTypes.resolveType(state.settings.searchKind);

        document.getElementById('search-premium-tip').style.display = order === 'popular_d' ? 'block' : 'none';

        cleanupBlobUrls();
        searchState.submode = 'batch';
        searchState.currentWord = word;
        searchState.currentMode = uiMode;
        searchState.currentOrder = order;
        searchState.currentSMode = sMode;
        searchState.kind = kind;
        searchState.localPage = 1;
        searchState.batchInfo = null;
        searchState.filterSeq++;

        const btn = document.getElementById('btn-batch-fetch');
        btn.disabled = true;
        document.getElementById('search-results-area').innerHTML =
            `<div class="search-spinner"><span class="search-spinner-icon"></span>${esc(bt('status.batch-fetching', '批量获取第 {start}–{end} 页中...', {start, end: effEnd}))}</div>`;
        document.getElementById('search-pagination').style.display = 'none';
        document.getElementById('btn-batch-add-page').disabled = true;
        document.getElementById('btn-batch-add-all').disabled = true;

        try {
            const acq = window.PixivBatch.queueTypes.acquisition(kind, 'search');
            const endpoint = (acq && acq.rangeEndpoint) || '/api/pixiv/search/range';
            const params = new URLSearchParams({word, order, mode, sMode, startPage: start, endPage: effEnd});
            const res = await fetch(`${BASE}${endpoint}?${params}`, {headers: pixivHeader()});
            const data = await res.json();
            if (!res.ok) {
                document.getElementById('search-results-area').innerHTML =
                    `<div style="color:#dc3545;text-align:center;padding:24px 0;">${esc(bt('status.batch-fetch-failed', '批量获取失败：{message}', {message: data.error || bt('queue.unknown-error', '未知错误')}))}</div>`;
                return;
            }
            let items = data.items || [];
            if (clientFilter > 0) items = items.filter(it => Number(it.xRestrict ?? 0) === clientFilter);
            searchState.pixivPageCount = items.length;
            searchState.rawResults = items;
            searchState.results = items;
            searchState.total = data.total || 0;
            searchState.noCookie = !hasPixivCookie();
            searchState.batchInfo = {
                startPage: data.startPage,
                endPage: data.endPage,
                requestedPages: data.requestedPages,
                acceptedPages: data.acceptedPages,
                fetchedPages: data.fetchedPages,
                limitPage: data.limitPage
            };

            const filterStats = await applyCurrentSearchFilters();
            if (!filterStats) return;

            const parts = [
                bt('search.batch.summary.fetched', '已抓取去重 {count} 个', {count: searchState.rawResults.length}),
                bt('search.batch.summary.range', '范围第 {start}–{end} 页', {
                    start: data.startPage,
                    end: data.endPage
                })
            ];
            if (data.acceptedPages < data.requestedPages) {
                parts.push(bt('search.batch.summary.limit-applied',
                    'multi 模式上限 {limit} 页，请求 {requested} 页已限制',
                    {limit: data.limitPage, requested: data.requestedPages}));
            }
            if (hasExtraSearchFilter()) {
                parts.push(bt('search.summary.extra-filtered', '附加筛选后 {count} 个', {count: searchState.results.length}));
            }
            parts.push(bt('search.summary.pixiv-total', 'Pixiv 总数 {count}', {count: searchState.total.toLocaleString()}));
            setStatus(bt('status.batch-fetch-complete', '批量获取完成：{summary}', {summary: summaryJoin(parts)}), 'success');
        } catch (e) {
            document.getElementById('search-results-area').innerHTML =
                `<div style="color:#dc3545;text-align:center;padding:24px 0;">${esc(bt('status.request-failed', '请求失败：{message}', {message: e.message}))}</div>`;
        } finally {
            btn.disabled = false;
            updateBatchQueueButtons();
        }
    }

    function addCurrentBatchPageToQueue() {
        if (searchState.submode !== 'batch') return;
        const view = getSearchView();
        if (!view.items.length) return;
        const ids = view.items.map(searchQueueId);
        const metas = view.items.map(searchQueueMeta);
        const added = addItemsToQueue(ids, metas, searchQueueSource(), '');
        setStatus(
            bt(
                'status.added-many-to-queue',
                '已将 {added} 个作品加入队列（共 {total} 个，{existing} 个已在队列中）',
                {added, total: ids.length, existing: ids.length - added}
            ),
            'success'
        );
    }


// ---- PixivBatch facade ----
window.PixivBatch.modes = window.PixivBatch.modes || {};
window.PixivBatch.modes.search = window.PixivBatch.modes.search || {};
window.PixivBatch.modes.search = Object.assign(window.PixivBatch.modes.search, { performSearch, runBatchFetch, addCurrentBatchPageToQueue, addAllSearchResultsToQueue });
