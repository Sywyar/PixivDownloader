'use strict';
    async function fetchUserIds(userId, lease) {
        const linked = linkedUserRequestSignal(lease.signal);
        try {
            return await userAcq().fetchIds(userId, {
                variant: userState.variant,
                signal: linked.signal
            });
        } finally {
            linked.dispose();
        }
    }
    async function fetchUserPage(page, lease) {
        const snapshot = {
            kind: userState.kind,
            variant: userState.variant,
            userId: userState.userId,
            pageCache: userState.pageCache,
            pageCursors: userState.pageCursors,
            acquisition: userAcq()
        };
        const assertSnapshotCurrent = () => {
            lease.assertCurrent();
            if (userState.kind !== snapshot.kind
                || userState.variant !== snapshot.variant
                || userState.userId !== snapshot.userId
                || userState.pageCache !== snapshot.pageCache
                || userState.pageCursors !== snapshot.pageCursors) {
                const error = new Error(bt('pagination.error.stale-request', '分页请求已过期'));
                error.code = 'STALE_ACQUISITION';
                throw error;
            }
        };
        const cached = snapshot.pageCache.get(page);
        if (cached) {
            assertSnapshotCurrent();
            return cached;
        }
        const declaredPageSize = Number(snapshot.acquisition.pageSize);
        const pageSize = Number.isFinite(declaredPageSize) && declaredPageSize > 0
            ? declaredPageSize : USER_PAGE_SIZE;
        const offset = (page - 1) * pageSize;
        const cursor = page === 1
            ? (snapshot.acquisition.initialCursor == null
                ? null : String(snapshot.acquisition.initialCursor))
            : snapshot.pageCursors.get(page);
        if (page > 1 && cursor == null) {
            throw new Error(bt('pagination.error.cursor-unavailable', '分页游标不可用，请重新从第一页加载'));
        }
        const linked = linkedUserRequestSignal(lease.signal);
        let data;
        try {
            data = await snapshot.acquisition.fetchPage(snapshot.userId, {
                variant: snapshot.variant,
                signal: linked.signal,
                page,
                offset,
                limit: pageSize,
                cursor
            });
        } finally {
            linked.dispose();
        }
        assertSnapshotCurrent();
        const items = Array.isArray(data && data.items) ? data.items : [];
        const hasMore = !!(data && data.hasMore);
        const reportedTotal = Number(data && data.total);
        const minimumTotal = offset + items.length + (hasMore ? 1 : 0);
        const total = Number.isFinite(reportedTotal) && reportedTotal >= 0
            ? Math.max(Math.floor(reportedTotal), minimumTotal) : minimumTotal;
        const nextCursor = data && data.nextCursor != null ? String(data.nextCursor) : '';
        if (hasMore && (!nextCursor || (cursor != null && nextCursor === String(cursor)))) {
            throw new Error(bt('pagination.error.cursor-stalled', '分页游标未推进，已停止继续加载'));
        }
        const result = {items, total, hasMore, nextCursor};
        assertSnapshotCurrent();
        snapshot.pageCache.set(page, result);
        if (hasMore && nextCursor) snapshot.pageCursors.set(page + 1, nextCursor);
        return result;
    }

    function userResultTotal() {
        return userState.pagedAcquisition ? userState.total : userState.allIds.length;
    }

    function hasUserResults() {
        return userResultTotal() > 0;
    }

    function cleanupUserBlobUrls() {
        userState.activeBlobUrls.forEach(u => {
            try { URL.revokeObjectURL(u); } catch {}
        });
        userState.activeBlobUrls = [];
    }

    function resetUserState(kind, variant) {
        beginUserRequestGeneration();
        cleanupUserBlobUrls();
        userState.kind = window.PixivBatch.queueTypes.resolveTypeForMode(kind, 'user');
        userState.variant = variant;
        userState.allIds = [];
        userState.total = 0;
        userState.pagedAcquisition = false;
        userState.pageCache = new Map();
        userState.pageCursors = new Map();
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
        abortUserRequests();
        resetPreviewCollapse('user-results-area', 'user-pagination');
        cleanupUserBlobUrls();
        userState.userId = '';
        userState.username = '';
        userState.allIds = [];
        userState.total = 0;
        userState.pagedAcquisition = false;
        userState.pageCache = new Map();
        userState.pageCursors = new Map();
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
            area.innerHTML = `<div class="preview-message preview-message--compact">${esc(bt('status.user-empty', '输入画师 ID 后点击「解析并预览」'))}</div>`;
        }
        renderUserPagination();
        updateUserQueueButtons();
    }

    async function loadUserPreview() {
        const input = document.getElementById('user-id-input');
        const rawInput = input.value;
        saveUserInputDraft(selectedUserSourceId() || userInputDraftSourceId, rawInput);
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
        const controls = window.PixivBatch && window.PixivBatch.modeControls;
        if (controls) controls.selectType('user', selected.type, false);
        if (selected.variant && state.settings.userKind !== selected.variant) {
            state.settings.userKind = selected.variant;
            applyKindSwitcherUI('user-kind-switcher', selected.variant);
            applyNovelSettingsVisibility();
            applySearchKindUI();
            saveSettings();
        }
        resetUserState(selected.type, selected.variant);
        beginUserOperation();
        const requestSeq = userState.requestSeq;
        userState.userId = userId;
        state.userId = userId;
        document.getElementById('user-info-display').textContent = bt('status.fetching-user-info', '正在获取用户信息...');
        setUserLoading(bt('status.fetching-artwork-list', '正在获取作品列表...'));
        const lease = window.PixivBatch.queueTypes.acquisitionLease(selected.type, 'user');
        try {
            let name = null;
            try {
                const linked = linkedUserRequestSignal(lease.signal);
                try {
                    name = await userAcq().fetchMeta(userId, {signal: linked.signal});
                } finally {
                    linked.dispose();
                }
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

            userState.pagedAcquisition = typeof userAcq().fetchPage === 'function';
            if (!userState.pagedAcquisition) {
                const ids = await fetchUserIds(userId, lease);
                lease.assertCurrent();
                if (requestSeq !== userState.requestSeq) return;
                userState.allIds = Array.isArray(ids) ? ids.map(String) : [];
                userState.total = userState.allIds.length;
                userState.totalPages = Math.max(1, Math.ceil(userState.total / userPageSize()));
                if (!userState.allIds.length) {
                    const emptyMessage = userEmptyMessage();
                    setStatus(emptyMessage, 'warning');
                    const area = document.getElementById('user-results-area');
                    if (area) area.innerHTML = `<div class="preview-message preview-message--compact">${esc(emptyMessage)}</div>`;
                    renderUserPagination();
                    updateUserQueueButtons();
                    return;
                }
            }
            await loadUserPreviewPage(1);
        } catch (e) {
            if (requestSeq !== userState.requestSeq || !lease.isCurrent()) return;
            const area = document.getElementById('user-results-area');
            if (area) area.innerHTML = `<div class="preview-message preview-message--error">${esc(bt('status.fetch-failed', '获取作品列表失败：{message}', {message: e.message}))}</div>`;
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
            const linked = linkedUserRequestSignal(request.init && request.init.signal);
            try {
                const init = Object.assign({}, request.init || {}, {signal: linked.signal});
                const res = await fetch(request.url, init);
                if (!res.ok) {
                    const d = await res.json().catch(() => ({}));
                    request.assertCurrent();
                    throw new Error(d.error || `HTTP ${res.status}`);
                }
                const data = await res.json();
                request.assertCurrent();
                (data.items || []).forEach(it => userState.cardCache.set(userCardCacheKey(String(it.id)), it));
            } finally {
                linked.dispose();
            }
        }
        return ids.map(id => userState.cardCache.get(userCardCacheKey(id))).filter(Boolean);
    }

    async function loadUserPreviewPage(page) {
        if (!userState.pagedAcquisition && !userState.allIds.length) return;
        beginUserOperation();
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
            let cards;
            if (userState.pagedAcquisition) {
                const pageData = await fetchUserPage(p, lease);
                cards = pageData.items;
                userState.total = Math.max(userState.total, pageData.total);
                userState.totalPages = Math.max(
                    userState.totalPages,
                    p,
                    pageData.hasMore ? p + 1 : p,
                    Math.max(1, Math.ceil(userState.total / pageSize))
                );
            } else {
                cards = await ensureUserCards(slice);
            }
            lease.assertCurrent();
            if (requestSeq !== userState.requestSeq) return;
            userState.rawItems = cards;
            if (userState.pagedAcquisition && p === 1 && !cards.length
                && !userState.pageCache.get(p).hasMore) {
                const emptyMessage = userEmptyMessage();
                setStatus(emptyMessage, 'warning');
                const area = document.getElementById('user-results-area');
                if (area) area.innerHTML = `<div class="preview-message preview-message--compact">${esc(emptyMessage)}</div>`;
                renderUserPagination();
                updateUserQueueButtons();
                return;
            }
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
            if (area) area.innerHTML = `<div class="preview-message preview-message--error">${esc(bt('status.fetch-failed', '获取作品列表失败：{message}', {message: e.message}))}</div>`;
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
            setStatus(bt('status.search-filters-applied', '已应用筛选：') + summaryJoin(parts), 'success');
        }
        return result.stats;
    }
