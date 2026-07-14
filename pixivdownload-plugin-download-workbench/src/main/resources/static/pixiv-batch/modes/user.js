'use strict';

    /* ============================================================
       User 模式预览（对齐 Search / 系列：先渲染预览网格 + 分页，
       再由「将此页加入队列」「全部加入队列」入队；附加筛选实时过滤当前页）
    ============================================================ */
    const USER_PAGE_SIZE = 30;

    let userState = {
        kind: null,
        variant: null,
        userId: '',
        username: '',
        allIds: [],
        currentPage: 1,
        totalPages: 1,
        rawItems: [],   // 当前页未过滤的卡片
        items: [],      // 当前页经附加筛选后的卡片（渲染 / 「加入此页」据此）
        cardCache: new Map(), // kind+id -> 卡片元数据，翻页 / 改筛选时复用避免重复请求
        filterSummary: {rawCount: 0, filteredCount: 0, bookmarkMetaMissing: 0, bookmarkFilterActive: false},
        renderToken: 0,
        activeBlobUrls: [],
        filterSeq: 0,
        requestSeq: 0
    };

    function userCardCacheKey(id) {
        return String(userState.kind || '') + ':' + String(id);
    }

    // 当前 user 模式作品类型的取得钩子；类型不可用时返回 null，由调用方停止该模式请求。
    function userAcq() {
        return window.PixivBatch.queueTypes.acquisition(userState.kind, 'user');
    }
    function userQueueId(item) {
        const acq = userAcq();
        return acq.queueId(item);
    }
    // 队列预览卡片元素 id（小说卡 / 插画缩略图 id 前缀不同，由该类型 user 钩子贡献）。
    function userCardElementId(idx) {
        const acq = userAcq();
        return acq.cardId(idx);
    }

    function resolveUserSelection(selection, rawInput) {
        const entries = window.PixivBatch.queueTypes.acquisitionList('user');
        let variant = selection;
        let entry = null;
        for (const candidate of entries) {
            try {
                if (typeof candidate.accepts === 'function'
                    ? candidate.accepts(selection) : candidate.type === selection) {
                    entry = candidate;
                    break;
                }
            } catch (e) {
                console.warn('[user] 用户类型选择钩子失败：', candidate.type, e);
            }
        }
        for (const candidate of entries) {
            if (typeof candidate.detectVariant !== 'function') continue;
            try {
                const detected = candidate.detectVariant(rawInput, selection);
                if (detected && (typeof candidate.accepts !== 'function' || candidate.accepts(detected))) {
                    entry = candidate;
                    variant = detected;
                    break;
                }
            } catch (e) {
                console.warn('[user] 用户类型变体钩子失败：', candidate.type, e);
            }
        }
        if (!entry) {
            const type = window.PixivBatch.queueTypes.resolveTypeForMode(selection, 'user');
            entry = entries.find(candidate => candidate.type === type) || null;
        }
        return entry ? {type: entry.type, variant, acquisition: entry} : null;
    }

    async function fetchUserIds(userId, lease) {
        return userAcq().fetchIds(userId, {variant: userState.variant, signal: lease.signal});
    }

    function cleanupUserBlobUrls() {
        userState.activeBlobUrls.forEach(u => {
            try { URL.revokeObjectURL(u); } catch {}
        });
        userState.activeBlobUrls = [];
    }

    function resetUserState(kind, variant) {
        cleanupUserBlobUrls();
        userState.kind = window.PixivBatch.queueTypes.resolveTypeForMode(kind, 'user');
        userState.variant = variant;
        userState.allIds = [];
        userState.currentPage = 1;
        userState.totalPages = 1;
        userState.rawItems = [];
        userState.items = [];
        userState.cardCache = new Map();
        userState.filterSummary = {rawCount: 0, filteredCount: 0, bookmarkMetaMissing: 0, bookmarkFilterActive: false};
        userState.renderToken += 1;
        userState.filterSeq += 1;
        userState.requestSeq += 1;
        updateUserQueueButtons();
        renderUserPagination();
    }

    function userPageSize() {
        const size = Number(userAcq().pageSize);
        return Number.isFinite(size) && size > 0 ? size : USER_PAGE_SIZE;
    }

    function setUserLoading(message) {
        // 新一轮加载 / 翻页：先展开预览，使加载态与新结果可见（用户可能此前手动收起了上一次的预览）。
        resetPreviewCollapse('user-results-area', 'user-pagination');
        const area = document.getElementById('user-results-area');
        if (area) area.innerHTML = `<div class="search-spinner"><span class="search-spinner-icon"></span>${esc(message)}</div>`;
        updateUserQueueButtons(true);
    }

    function clearUserPreview() {
        resetPreviewCollapse('user-results-area', 'user-pagination');
        cleanupUserBlobUrls();
        userState.userId = '';
        userState.username = '';
        userState.allIds = [];
        userState.rawItems = [];
        userState.items = [];
        userState.cardCache = new Map();
        userState.filterSummary = {rawCount: 0, filteredCount: 0, bookmarkMetaMissing: 0, bookmarkFilterActive: false};
        userState.currentPage = 1;
        userState.totalPages = 1;
        userState.renderToken += 1;
        userState.filterSeq += 1;
        userState.requestSeq += 1;
        const area = document.getElementById('user-results-area');
        if (area) {
            area.innerHTML = `<div style="text-align:center;color:#aaa;padding:24px 0;font-size:13px;">${esc(bt('status.user-empty', '输入画师 ID 后点击「解析并预览」'))}</div>`;
        }
        renderUserPagination();
        updateUserQueueButtons();
    }

    async function loadUserPreview() {
        const input = document.getElementById('user-id-input');
        const rawInput = input.value;
        const selected = resolveUserSelection(state.settings.userKind, rawInput);
        if (!selected) {
            setStatus(bt('queue.message.type-unavailable', '该类型当前不可用（其插件已禁用），已暂停'), 'warning');
            return;
        }
        const userId = selected.acquisition.parseInput(rawInput);
        if (!userId) {
            await uiAlertKey('alert.invalid-user-id', '请输入有效的用户 ID 或画师主页链接');
            return;
        }
        // 类型 owner 可据输入识别其子类别；宿主只同步贡献方返回的选择值。
        if (selected.variant && state.settings.userKind !== selected.variant) {
            state.settings.userKind = selected.variant;
            applyKindSwitcherUI('user-kind-switcher', selected.variant);
            applyNovelSettingsVisibility();
            applySearchKindUI();
            saveSettings();
        }
        if (input.value.trim() !== userId) input.value = userId;
        resetUserState(selected.type, selected.variant);
        const requestSeq = userState.requestSeq;
        userState.userId = userId;
        state.userId = userId;
        document.getElementById('user-info-display').textContent = bt('status.fetching-user-info', '正在获取用户信息...');
        setUserLoading(bt('status.fetching-artwork-list', '正在获取作品列表...'));
        const lease = window.PixivBatch.queueTypes.acquisitionLease(selected.type, 'user');
        try {
            let name = null;
            try {
                name = await userAcq().fetchMeta(userId, {signal: lease.signal});
                lease.assertCurrent();
                if (requestSeq !== userState.requestSeq) return;
            } catch (e) {
                lease.assertCurrent();
                if (requestSeq !== userState.requestSeq) return;
                name = null;
            }
            userState.username = name || userId;
            state.username = userState.username;
            document.getElementById('user-info-display').textContent = name
                ? bt('status.user-display', '用户：{name}（ID: {id}）', {name: userState.username, id: userId})
                : bt('status.user-display-fetch-failed', 'ID: {id}（获取用户名失败）', {id: userId});

            const ids = await fetchUserIds(userId, lease);
            lease.assertCurrent();
            if (requestSeq !== userState.requestSeq) return;
            userState.allIds = Array.isArray(ids) ? ids.map(String) : [];
            userState.totalPages = Math.max(1, Math.ceil(userState.allIds.length / userPageSize()));
            if (!userState.allIds.length) {
                setStatus(bt('status.user-no-artworks', '该用户暂无作品'), 'warning');
                const area = document.getElementById('user-results-area');
                if (area) area.innerHTML = `<div style="text-align:center;color:#aaa;padding:24px 0;font-size:13px;">${esc(bt('status.user-no-artworks', '该用户暂无作品'))}</div>`;
                renderUserPagination();
                updateUserQueueButtons();
                return;
            }
            await loadUserPreviewPage(1);
        } catch (e) {
            if (requestSeq !== userState.requestSeq || !lease.isCurrent()) return;
            const area = document.getElementById('user-results-area');
            if (area) area.innerHTML = `<div style="color:#dc3545;text-align:center;padding:24px 0;">${esc(bt('status.fetch-failed', '获取作品列表失败：{message}', {message: e.message}))}</div>`;
            setStatus(bt('status.fetch-failed', '获取作品列表失败：{message}', {message: e.message}), 'error');
            updateUserQueueButtons();
        }
    }

    // 批量获取一段 ID 的卡片元数据（命中缓存的不再请求），按请求顺序返回（跳过无卡片的已删除作品）。
    async function ensureUserCards(ids) {
        const missing = ids.filter(id => !userState.cardCache.has(userCardCacheKey(id)));
        if (missing.length) {
            const acq = userAcq();
            const endpoint = acq.cardsEndpoint(userState.userId);
            const params = new URLSearchParams();
            missing.forEach(id => params.append('ids', id));
            const request = window.PixivBatch.queueTypes.prepareAcquisitionRequest(
                userState.kind, 'user', `${endpoint}?${params}`, 'cards',
                {userId: userState.userId, ids: missing.slice()});
            const res = await fetch(request.url, request.init);
            if (!res.ok) {
                const d = await res.json().catch(() => ({}));
                request.assertCurrent();
                throw new Error(d.error || `HTTP ${res.status}`);
            }
            const data = await res.json();
            request.assertCurrent();
            (data.items || []).forEach(it => userState.cardCache.set(userCardCacheKey(String(it.id)), it));
        }
        return ids.map(id => userState.cardCache.get(userCardCacheKey(id))).filter(Boolean);
    }

    async function loadUserPreviewPage(page) {
        if (!userState.allIds.length) return;
        let p = Number(page);
        if (!Number.isFinite(p) || p < 1) p = 1;
        if (p > userState.totalPages) p = userState.totalPages;
        userState.currentPage = p;
        const requestSeq = ++userState.requestSeq;
        cleanupUserBlobUrls();
        const pageSize = userPageSize();
        const base = (p - 1) * pageSize;
        const slice = userState.allIds.slice(base, base + pageSize);
        setUserLoading(bt('status.series-page-loading', '正在加载第 {page} 页...', {page: p}));
        const lease = window.PixivBatch.queueTypes.acquisitionLease(userState.kind, 'user');
        try {
            const cards = await ensureUserCards(slice);
            lease.assertCurrent();
            if (requestSeq !== userState.requestSeq) return;
            userState.rawItems = cards;
            await applyUserFilters({});
            lease.assertCurrent();
            if (requestSeq !== userState.requestSeq) return;
            renderUserPagination();
            updateUserQueueButtons();
            setStatus(bt('status.user-preview-loaded', '画师预览已加载：{name}（第 {page} / {total} 页）', {
                name: userState.username,
                page: userState.currentPage,
                total: userState.totalPages
            }), 'success');
        } catch (e) {
            if (requestSeq !== userState.requestSeq || !lease.isCurrent()) return;
            const area = document.getElementById('user-results-area');
            if (area) area.innerHTML = `<div style="color:#dc3545;text-align:center;padding:24px 0;">${esc(bt('status.fetch-failed', '获取作品列表失败：{message}', {message: e.message}))}</div>`;
            setStatus(bt('status.fetch-failed', '获取作品列表失败：{message}', {message: e.message}), 'error');
            updateUserQueueButtons();
        }
    }

    async function applyUserFilters(options = {}) {
        const filters = normalizeSearchFilters(options.filters || getSearchFiltersFromUI());
        searchState.currentFilters = filters;
        saveSearchFilterPrefs(filters);
        const kind = userState.kind;
        const seq = ++userState.filterSeq;
        const isStale = () => seq !== userState.filterSeq;

        const bookmarkActive = hasBookmarkFilter(filters);
        const needsBookmarkMeta = bookmarkActive && userState.rawItems.some(item => {
            if (getInlineSearchBookmarkCount(item) !== null) return false;
            const cached = getCachedSearchMeta(item.id, kind);
            return !cached || !cached.bookmarkResolved;
        });
        if (bookmarkActive && needsBookmarkMeta && userState.rawItems.length) {
            const area = document.getElementById('user-results-area');
            if (area) area.innerHTML = `<div class="search-spinner"><span class="search-spinner-icon"></span>${esc(bt('status.search-reading-bookmarks', '读取当前页收藏数中...'))}</div>`;
            updateUserQueueButtons(true);
        }

        const result = await computeFilteredItems(userState.rawItems, filters, kind, isStale);
        if (!result) return null;
        userState.items = result.filtered;
        userState.filterSummary = result.stats;
        renderUserResults();
        updateUserQueueButtons();

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

    function renderUserResults() {
        const area = document.getElementById('user-results-area');
        if (!area) return;
        const renderToken = ++userState.renderToken;
        if (!userState.rawItems.length) {
            area.innerHTML = `<div style="color:#aaa;text-align:center;padding:24px 0;">${esc(bt('status.user-no-artworks', '该用户暂无作品'))}</div>`;
            return;
        }
        const summary = [
            bt('series.meta.total', '共 {count} 个作品', {count: userState.allIds.length.toLocaleString()}),
            bt('search.summary.current-page-index', '当前第 {page} 页', {page: userState.currentPage}),
            bt('search.summary.current-page', '当前页 {count} 个', {count: userState.rawItems.length})
        ];
        if (hasExtraSearchFilter()) {
            summary.push(bt('search.summary.extra-filtered', '附加筛选后 {count} 个', {count: userState.items.length}));
            if (userState.filterSummary.bookmarkMetaMissing > 0) {
                summary.push(bt('search.summary.bookmark-missing', '{count} 个收藏数不可用已排除', {count: userState.filterSummary.bookmarkMetaMissing}));
            }
        }
        const summaryHtml = `<div style="font-size:12px;color:#888;margin-bottom:10px;">${summary.map(s => `<span>${esc(s)}</span>`).join(summarySeparator())}</div>`;
        if (!userState.items.length) {
            area.innerHTML = summaryHtml + `<div style="color:#aaa;text-align:center;padding:24px 0;">${esc(bt('status.search-no-filtered-results', '附加筛选后无结果'))}</div>`;
            return;
        }
        const inQueue = new Set(state.queue.map(q => q.id));
        const acq = userAcq();
        acq.render(area, {
            summaryHtml,
            inQueue,
            items: userState.items,
            username: userState.username,
            renderToken
        });
    }

    function renderUserPagination() {
        const pag = document.getElementById('user-pagination');
        if (!pag) return;
        const totalPages = Math.max(1, Number(userState.totalPages || 1));
        const cur = Math.min(Math.max(1, Number(userState.currentPage || 1)), totalPages);
        if (!userState.allIds.length || totalPages <= 1) {
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
            `<button onclick="loadUserPreviewPage(1)" ${cur === 1 ? 'disabled' : ''}>&laquo;</button>` +
            `<button onclick="loadUserPreviewPage(${cur - 1})" ${cur === 1 ? 'disabled' : ''}>&lsaquo;</button>` +
            pages.map(p =>
                `<button onclick="${p === cur ? '' : `loadUserPreviewPage(${p})`}" ${p === cur ? 'class="pg-active" disabled' : ''}>${p}</button>`
            ).join('') +
            `<button onclick="loadUserPreviewPage(${cur + 1})" ${cur === totalPages ? 'disabled' : ''}>&rsaquo;</button>` +
            `<button onclick="loadUserPreviewPage(${totalPages})" ${cur === totalPages ? 'disabled' : ''}>&raquo;</button>` +
            `<span class="pg-info">${esc(bt('search.pagination.info', '第 {current} / {total} 页 · 共 {count} 个', {
                current: cur,
                total: totalPages,
                count: userState.allIds.length.toLocaleString()
            }))}</span>`;
    }

    async function loadUserThumbnailsBatched(items, renderToken) {
        const BATCH = 10;
        for (let i = 0; i < items.length; i += BATCH) {
            if (renderToken !== userState.renderToken) return;
            const batch = items.slice(i, i + BATCH);
            await Promise.allSettled(batch.map((item, offset) => loadSingleUserThumbnail(item, i + offset, renderToken)));
        }
    }

    async function loadSingleUserThumbnail(item, idx, renderToken) {
        if (!item.thumbnailUrl) return;
        const imgEl = document.getElementById(`user-thumb-img-${idx}`);
        if (!imgEl) return;
        const blobUrl = await fetchThumbnailBlobUrl(
            item.thumbnailUrl, userState.activeBlobUrls, userState.kind, 'user');
        if (renderToken !== userState.renderToken) return;
        if (blobUrl && imgEl.isConnected) imgEl.src = blobUrl;
    }

    function buildUserQueueMeta(item) {
        const acq = userAcq();
        return acq.buildQueueMeta(item, {userId: userState.userId, username: userState.username});
    }

    function syncUserResultsQueueState() {
        if (!userState.items.length) return;
        const inQueue = new Set(state.queue.map(q => q.id));
        userState.items.forEach((item, idx) => {
            const el = document.getElementById(userCardElementId(idx));
            if (!el) return;
            el.classList.toggle('in-queue', inQueue.has(userQueueId(item)));
        });
    }

    function updateUserQueueButtons(isLoading = false) {
        const pageBtn = document.getElementById('btn-user-add-page');
        const allBtn = document.getElementById('btn-user-add-all');
        if (pageBtn) pageBtn.disabled = isLoading || userState.items.length === 0;
        if (allBtn) allBtn.disabled = isLoading || userState.allIds.length === 0;
    }

    function addUserItemToQueue(idx) {
        const item = userState.items[idx];
        if (!item) return;
        const queueId = userQueueId(item);
        const alreadyInQueue = state.queue.find(q => q.id === queueId);
        if (alreadyInQueue) {
            const removed = removeFromQueue(queueId);
            setStatus(removed
                    ? bt('status.removed-from-queue', '已从队列移除：{title}', {title: item.title})
                    : bt('status.cannot-remove-downloading', '无法移除（正在下载中）：{title}', {title: item.title}),
                removed ? 'info' : 'warning');
            return;
        }
        const added = addItemsToQueue(
            [queueId],
            [buildUserQueueMeta(item)],
            'user',
            userState.username || userState.userId,
            userState.userId,
            userState.username || userState.userId
        );
        setStatus(added > 0
                ? bt('status.added-to-queue', '已加入队列：{title}', {title: item.title})
                : bt('status.already-in-queue', '已在队列中：{title}', {title: item.title}),
            added > 0 ? 'success' : 'info');
        syncUserResultsQueueState();
    }

    function addCurrentUserPageToQueue() {
        if (!userState.items.length) return;
        const ids = userState.items.map(userQueueId);
        const metas = userState.items.map(buildUserQueueMeta);
        const added = addItemsToQueue(
            ids, metas, 'user',
            userState.username || userState.userId, userState.userId, userState.username || userState.userId
        );
        setStatus(
            bt('status.added-current-series-page-to-queue', '已将当前页 {added} 个作品加入队列（本页 {total} 个，{existing} 个已在队列中）',
                {added, total: ids.length, existing: ids.length - added}),
            added > 0 ? 'success' : 'info'
        );
        syncUserResultsQueueState();
    }

    async function addAllUserResultsToQueue() {
        if (!userState.allIds.length) return;
        const acq = userAcq();
        const metaCtx = {userId: userState.userId, username: userState.username};
        const uiFilters = normalizeSearchFilters(getSearchFiltersFromUI());
        // 无附加筛选：直接按全部 ID 入队（最省请求，等价于旧版「获取全部作品」）。
        if (!hasExtraSearchFilter(uiFilters)) {
            const ids = userState.allIds.map(id => userQueueId({id}));
            const metas = userState.allIds.map(id => acq.buildQueueMetaFromId(id, metaCtx));
            const added = addItemsToQueue(
                ids, metas, 'user',
                userState.username || userState.userId, userState.userId, userState.username || userState.userId
            );
            setStatus(
                bt('status.added-many-to-queue', '已将 {added} 个作品加入队列（共 {total} 个，{existing} 个已在队列中）',
                    {added, total: ids.length, existing: ids.length - added}),
                added > 0 ? 'success' : 'info'
            );
            syncUserResultsQueueState();
            return;
        }
        // 有附加筛选：必须逐页拉取卡片元数据做筛选，确认后继续。
        const lease = window.PixivBatch.queueTypes.acquisitionLease(userState.kind, 'user');
        const requestSeq = userState.requestSeq;
        const confirmed = await uiConfirmKey(
            'dialog.user-add-all-warning',
            '「全部加入队列」会按附加筛选逐页请求该画师的全部 {total} 个作品卡片，作品较多时会增加 Pixiv 请求量并耗时，确认继续？',
            {total: userState.allIds.length}
        );
        if (requestSeq !== userState.requestSeq || !lease.isCurrent()) return;
        lease.assertCurrent();
        if (!confirmed) return;
        updateUserQueueButtons(true);
        try {
            const filters = uiFilters;
            const kind = userState.kind;
            const matched = [];
            const total = userState.allIds.length;
            const pageSize = userPageSize();
            for (let i = 0; i < userState.allIds.length; i += pageSize) {
                const slice = userState.allIds.slice(i, i + pageSize);
                setStatus(bt('status.user-fetch-all-progress', '正在抓取画师作品卡片 {done} / {total}...', {
                    done: Math.min(i + pageSize, total),
                    total
                }), 'info');
                const cards = await ensureUserCards(slice);
                lease.assertCurrent();
                if (requestSeq !== userState.requestSeq) return;
                const result = await computeFilteredItems(cards, filters, kind, () => false);
                lease.assertCurrent();
                if (requestSeq !== userState.requestSeq) return;
                if (result) matched.push(...result.filtered);
            }
            const ids = matched.map(userQueueId);
            const metas = matched.map(buildUserQueueMeta);
            const added = addItemsToQueue(
                ids, metas, 'user',
                userState.username || userState.userId, userState.userId, userState.username || userState.userId
            );
            setStatus(
                bt('status.added-many-to-queue', '已将 {added} 个作品加入队列（共 {total} 个，{existing} 个已在队列中）',
                    {added, total: ids.length, existing: ids.length - added}),
                added > 0 ? 'success' : 'info'
            );
            syncUserResultsQueueState();
        } catch (e) {
            if (requestSeq !== userState.requestSeq || !lease.isCurrent()) return;
            setStatus(bt('status.fetch-failed', '获取作品列表失败：{message}', {message: e.message}), 'error');
        } finally {
            if (requestSeq !== userState.requestSeq || !lease.isCurrent()) return;
            updateUserQueueButtons();
        }
    }

    function reconcileUserTypeAvailability() {
        if (!userState.kind || window.PixivBatch.queueTypes.supports(userState.kind, 'user')) return false;
        userState.kind = window.PixivBatch.queueTypes.resolveTypeForMode(state.settings.userKind, 'user');
        userState.variant = state.settings.userKind;
        clearUserPreview();
        return true;
    }


// ---- PixivBatch facade ----
window.PixivBatch.modes = window.PixivBatch.modes || {};
window.PixivBatch.modes.user = window.PixivBatch.modes.user || {};
window.PixivBatch.modes.user = Object.assign(window.PixivBatch.modes.user, {
    loadUserPreview, addCurrentUserPageToQueue, addAllUserResultsToQueue, reconcileUserTypeAvailability
});
