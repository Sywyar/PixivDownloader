'use strict';
    async function quickFetchPage(page) {
        const limit = quickState.pageSize;
        const offset = (page - 1) * limit;
        // 收藏类外层动作的逐页抓取：收藏端点（如 illust-bookmarks / novel-bookmarks）由动作映射的 bookmarkEndpoint 提供。
        const desc = quickActionMap()[quickState.action];
        if (desc && typeof desc.buildPageRequest === 'function') {
            const rest = desc.scheduleRest || (quickState.action.endsWith('hide') ? 'hide' : 'show');
            const data = await quickFetchJson(
                quickRequestUrl(desc.buildPageRequest({rest, offset, limit, page})), desc.ownerType);
            return data.items || [];
        }
        return [];
    }

    function quickRenderEmpty(msg) {
        const area = document.getElementById('quick-preview-area');
        if (area) area.innerHTML = `<div class="quick-empty">${esc(msg)}</div>`;
        const pag = document.getElementById('quick-pagination');
        if (pag) { pag.style.display = 'none'; pag.innerHTML = ''; }
        quickCloseInner();
    }

    /* ── 二层钻取（在外层列表下方追加，非替换式）─────────────────────────── */

    function quickCleanupInnerBlobUrls() {
        quickInner.blobUrls.forEach(u => {
            try { URL.revokeObjectURL(u); } catch {}
        });
        quickInner.blobUrls = [];
    }

    function quickCloseInner() {
        quickInner.open = false;
        quickInner.loadSeq++;
        quickInner.renderToken++;
        quickInner.filterSeq++;
        quickInner.type = null;
        quickInner.id = null;
        quickInner.userId = null;
        quickInner.name = '';
        quickInner.workCategory = null;
        quickInner.kind = null;
        quickInner.idsByType = new Map();
        quickInner.userPageStates = new Map();
        quickInner.collectionPageState = null;
        quickInner.allIds = [];
        quickInner.items = [];
        quickInner.rawItems = [];
        quickInner.total = 0;
        quickInner.page = 1;
        quickInner.pageSize = QUICK_PAGE_SIZE_ILLUST;
        quickInner.filterSummary = {rawCount: 0, filteredCount: 0, bookmarkMetaMissing: 0, bookmarkFilterActive: false};
        quickInner._jumpFn = null;
        quickCleanupInnerBlobUrls();
        const section = document.getElementById('quick-inner-section');
        if (section) section.style.display = 'none';
        // 取消外层卡片的选中高亮
        document.querySelectorAll('#quick-preview-area .quick-selected')
            .forEach(el => el.classList.remove('quick-selected'));
        // 关闭二层钻取后外层若是纯选择页（关注 / 珍藏集列表），附加筛选 / 存为计划任务应随之隐藏。
        updateExtraFiltersCardVisibility();
        updateSaveScheduleCardVisibility();
        applyNovelSettingsVisibility();
    }

    function quickHighlightOuterCard(selector) {
        document.querySelectorAll('#quick-preview-area .quick-selected')
            .forEach(el => el.classList.remove('quick-selected'));
        const el = document.querySelector(selector);
        if (el) el.classList.add('quick-selected');
    }

    function quickShowInnerSection() {
        // 进入二层钻取：先展开内层预览，使新内容可见。
        resetPreviewCollapse('quick-inner-area', 'quick-inner-pagination');
        const section = document.getElementById('quick-inner-section');
        if (section) {
            section.style.display = '';
            section.scrollIntoView({behavior: 'smooth', block: 'nearest'});
        }
    }

    function quickShowInnerToolbar(opts) {
        document.getElementById('quick-inner-add-page').style.display = opts.showAdd ? '' : 'none';
        document.getElementById('quick-inner-add-all').style.display = opts.showAdd ? '' : 'none';
        // 收起按钮与「加入队列」按钮同显隐。
        const innerCollapse = document.getElementById('quick-inner-collapse-page');
        if (innerCollapse) innerCollapse.style.display = opts.showAdd ? '' : 'none';
        const sw = document.getElementById('quick-inner-kind-switcher');
        sw.style.display = opts.showKindSwitcher ? '' : 'none';
        if (opts.showKindSwitcher && opts.kind) {
            const allowedTypes = quickActionUserWorkTypes();
            document.querySelectorAll('#quick-inner-kind-switcher label').forEach(l => {
                const type = l.dataset.quickKind;
                const available = allowedTypes.has(type)
                    && window.PixivBatch.queueTypes.supports(type, 'quick');
                l.style.display = available ? '' : 'none';
                l.classList.toggle('quick-kind-active', l.dataset.quickKind === opts.kind);
                const input = l.querySelector('input');
                if (input) {
                    input.disabled = !available;
                    input.checked = l.dataset.quickKind === opts.kind;
                }
            });
        }
    }

    function quickSetInnerTitle(text) {
        const el = document.getElementById('quick-inner-title');
        if (el) el.textContent = text;
    }

    // 内层作品网格渲染：混合插画+小说，按每项自身 kind 渲染对应卡片。
    // 卡片统一 id quick-inner-card-{idx}（队列高亮用），插画缩略图 id quick-inner-img-{idx}；点击走 quickInnerToggleQueue。
    function renderQuickInnerGrid(items, summaryHtml = '') {
        const area = document.getElementById('quick-inner-area');
        if (!area) return;
        const renderToken = ++quickInner.renderToken;
        if (!items.length) {
            const emptyMsg = summaryHtml
                ? bt('status.search-no-filtered-results', '附加筛选后无结果')
                : bt('quick.empty.no-items', '该范围内没有作品');
            area.innerHTML = summaryHtml + `<div class="quick-empty">${esc(emptyMsg)}</div>`;
            return;
        }
        const inQueue = new Set(state.queue.map(q => q.id));
        area.innerHTML = summaryHtml + `<div class="quick-mixed-grid">${items.map((item, idx) => {
            const k = window.PixivBatch.queueTypes.resolveTypeForMode(item.kind, 'quick', quickInner.kind);
            const acq = window.PixivBatch.queueTypes.acquisition(k, 'quick');
            return acq.innerCardHtml(item, idx, inQueue);
        }).join('')}</div>`;
        loadQuickInnerThumbnailsBatched(items, renderToken);
    }

    function pixivQuickInnerCard(item, idx, inQueue) {
            const title = item.title || bt('queue.artwork-fallback', '作品 {id}', {id: item.id});
            const xr = Number(item.xRestrict ?? 0);
            const illustType = Number(item.illustType ?? 0);
            const isAi = Number(item.aiType ?? 0) >= 2;
            const r18Badge = xr === 2 ? '<span class="thumb-badge thumb-badge-r18g">R-18G</span>'
                : xr === 1 ? '<span class="thumb-badge">R-18</span>' : '';
            const aiBadge = isAi ? '<span class="thumb-badge thumb-badge-ai">AI</span>' : '';
            const typeBadge = illustType === 2 ? `<span class="thumb-badge thumb-badge-ugoira">${esc(bt('search.type.ugoira', '动图'))}</span>`
                : illustType === 1 ? `<span class="thumb-badge thumb-badge-manga">${esc(bt('search.type.manga', '漫画'))}</span>` : '';
            const pagesLabel = item.pageCount > 1 ? `<span class="thumb-pages">${item.pageCount}P</span>` : '';
            const inQueueClass = inQueue.has(String(item.id)) ? ' in-queue' : '';
            return `<div class="search-thumb${inQueueClass}" id="quick-inner-card-${idx}"
                     data-pixiv-click="quickInnerToggleQueue(${idx})" title="${esc(title)} (${esc(item.userName || '')})">
          <img id="quick-inner-img-${idx}" src="" alt="${esc(title)}">
          <div class="thumb-badge-stack">${r18Badge}${aiBadge}${typeBadge}</div>
          ${pagesLabel}
          <span class="thumb-in-queue-mark">✓</span>
          <div class="thumb-title">${esc(title)}</div>
        </div>`;
    }

    async function loadQuickInnerThumbnailsBatched(items, renderToken) {
        for (let i = 0; i < items.length; i += QUICK_THUMB_BATCH) {
            if (renderToken !== quickInner.renderToken) return;
            const batch = items.slice(i, i + QUICK_THUMB_BATCH);
            await Promise.allSettled(batch.map((item, offset) => loadQuickInnerSingleThumbnail(item, i + offset, renderToken)));
        }
    }

    async function loadQuickInnerSingleThumbnail(item, idx, renderToken) {
        const kind = window.PixivBatch.queueTypes.resolveTypeForMode(item.kind, 'quick', quickInner.kind);
        const acq = window.PixivBatch.queueTypes.acquisition(kind, 'quick');
        if (acq.skipThumbnail) return;
        const url = item.thumbnailUrl || item.url;
        if (!url) return;
        const imgEl = document.getElementById(`quick-inner-img-${idx}`);
        if (!imgEl) return;
        const blobUrl = await fetchThumbnailBlobUrl(url, quickInner.blobUrls, kind, 'quick');
        if (renderToken !== quickInner.renderToken) return;
        if (blobUrl && imgEl.isConnected) imgEl.src = blobUrl;
    }

    function renderQuickInnerPagination(currentPage, totalPages, jumpFn) {
        const pag = document.getElementById('quick-inner-pagination');
        if (!pag) return;
        if (totalPages <= 1) {
            pag.style.display = 'none';
            pag.innerHTML = '';
            return;
        }
        pag.style.display = 'flex';
        const cur = Math.min(Math.max(1, Number(currentPage || 1)), totalPages);
        const radius = 3;
        const pages = [];
        for (let p = Math.max(1, cur - radius); p <= Math.min(totalPages, cur + radius); p++) pages.push(p);
        quickInner._jumpFn = jumpFn;
        pag.innerHTML =
            `<button data-pixiv-click="quickInnerJumpPage(1)" ${cur === 1 ? 'disabled' : ''}>&laquo;</button>` +
            `<button data-pixiv-click="quickInnerJumpPage(${cur - 1})" ${cur === 1 ? 'disabled' : ''}>&lsaquo;</button>` +
            pages.map(p => `<button data-pixiv-click="${p === cur ? '' : `quickInnerJumpPage(${p})`}" ${p === cur ? 'class="pg-active" disabled' : ''}>${p}</button>`).join('') +
            `<button data-pixiv-click="quickInnerJumpPage(${cur + 1})" ${cur === totalPages ? 'disabled' : ''}>&rsaquo;</button>` +
            `<button data-pixiv-click="quickInnerJumpPage(${totalPages})" ${cur === totalPages ? 'disabled' : ''}>&raquo;</button>` +
            `<span class="pg-info">${esc(bt('search.pagination.info', '第 {current} / {total} 页 · 共 {count} 个',
                {current: cur, total: totalPages, count: quickInner.total.toLocaleString()}))}</span>`;
    }

    function quickInnerJumpPage(p) {
        if (typeof quickInner._jumpFn === 'function') quickInner._jumpFn(p);
    }

    // 珍藏集 → 集内作品（插画+小说混合）；支持中性 cursor 页钩子并保留旧一次性响应。
    async function quickEnterCollection(idx) {
        const c = quickState.items[idx];
        if (!c) return;
        const cid = String(c.id);
        const outerLoadSeq = quickState.loadSeq;
        const enterSeq = ++quickInner.loadSeq;
        quickCleanupInnerBlobUrls();
        quickInner.open = true;
        quickInner.type = 'collection';
        quickInner.id = cid;
        quickInner.name = c.title || cid;
        quickInner.collectionPageState = {pages: new Map(), cursors: new Map(), total: 0};
        quickHighlightOuterCard(`#quick-preview-area .quick-collection-card:nth-of-type(${idx + 1})`);
        quickShowInnerSection();
        document.getElementById('quick-inner-area').innerHTML = quickLoadingHtml();
        quickShowInnerToolbar({showAdd: false, showKindSwitcher: false});
        try {
            const action = currentQuickAction();
            if (!action || (typeof action.buildCollectionWorksPageRequest !== 'function'
                && typeof action.buildCollectionWorksRequest !== 'function')) {
                throw new Error(bt('quick.error.unknown-action', '该入口当前不可用'));
            }
            if (typeof action.buildCollectionWorksPageRequest === 'function') {
                await loadQuickInnerCollectionWorks(action, cid, 1);
            } else {
                const data = await quickFetchJson(
                    quickRequestUrl(action.buildCollectionWorksRequest(cid)), action.ownerType);
                if (enterSeq !== quickInner.loadSeq || outerLoadSeq !== quickState.loadSeq
                    || !quickInner.open || quickInner.id !== cid) return;
                quickInner.rawItems = data.works || [];
                quickInner.total = data.total || quickInner.rawItems.length;
                quickInner.page = 1;
                quickShowInnerToolbar({showAdd: quickInner.rawItems.length > 0, showKindSwitcher: false});
                quickSetInnerTitle(`${bt('quick.title.collections', '我的珍藏集')} › ${quickInner.name} · ${bt('quick.title.count', '{count} 件', {count: quickInner.total.toLocaleString()})}`);
                await quickApplyInnerFilters();
                renderQuickInnerPagination(1, 1, () => {});
            }
            // 珍藏集内为混合作品（无单画师来源）：显示附加筛选与小说设置，但不提供「存为计划任务」。
            updateExtraFiltersCardVisibility();
            updateSaveScheduleCardVisibility();
            applyNovelSettingsVisibility();
        } catch (e) {
            if (outerLoadSeq !== quickState.loadSeq || !quickInner.open || quickInner.id !== cid) return;
            document.getElementById('quick-inner-area').innerHTML =
                `<div class="quick-empty">${esc(bt('quick.error.load-failed', '加载失败：{message}', {message: e.message || String(e)}))}</div>`;
        }
    }

    function quickInnerCollectionPageState() {
        if (!quickInner.collectionPageState) {
            quickInner.collectionPageState = {pages: new Map(), cursors: new Map(), total: 0};
        }
        return quickInner.collectionPageState;
    }

    async function fetchQuickInnerCollectionPage(action, collectionId, page) {
        const pageState = quickInnerCollectionPageState();
        const cached = pageState.pages.get(page);
        if (cached) return cached;
        const acq = window.PixivBatch.queueTypes.acquisition(action.ownerType, 'quick');
        const limit = Math.max(1, Number(action.pageSize || (acq && acq.pageSize)) || QUICK_PAGE_SIZE_ILLUST);
        const offset = (page - 1) * limit;
        const cursor = page === 1
            ? (action.initialCursor == null ? null : String(action.initialCursor))
            : pageState.cursors.get(page);
        if (page > 1 && cursor == null) {
            throw new Error(bt('pagination.error.cursor-unavailable', '分页游标不可用，请重新从第一页加载'));
        }
        const data = await quickFetchJson(quickRequestUrl(action.buildCollectionWorksPageRequest(collectionId, {
            page,
            offset,
            limit,
            cursor
        })), action.ownerType);
        const items = Array.isArray(data.works) ? data.works
            : (Array.isArray(data.items) ? data.items : []);
        const hasMore = !!data.hasMore;
        const nextCursor = data.nextCursor == null ? '' : String(data.nextCursor);
        if (hasMore && (!nextCursor || (cursor != null && nextCursor === String(cursor)))) {
            throw new Error(bt('pagination.error.cursor-stalled', '分页游标未推进，已停止继续加载'));
        }
        const reportedTotal = Number(data.total);
        const minimumTotal = offset + items.length + (hasMore ? 1 : 0);
        const total = Number.isFinite(reportedTotal) && reportedTotal >= 0
            ? Math.max(Math.floor(reportedTotal), minimumTotal) : minimumTotal;
        pageState.total = Math.max(pageState.total, total);
        const result = {items, total: pageState.total, hasMore, nextCursor, limit};
        pageState.pages.set(page, result);
        if (hasMore) pageState.cursors.set(page + 1, nextCursor);
        return result;
    }

    async function loadQuickInnerCollectionWorks(action, collectionId, page) {
        const loadSeq = ++quickInner.loadSeq;
        const safePage = Math.max(1, Number(page) || 1);
        const pageData = await fetchQuickInnerCollectionPage(action, collectionId, safePage);
        if (loadSeq !== quickInner.loadSeq || !quickInner.open
            || quickInner.type !== 'collection' || quickInner.id !== collectionId) return;
        quickInner.rawItems = pageData.items;
        quickInner.total = pageData.total;
        quickInner.page = safePage;
        quickInner.pageSize = pageData.limit;
        quickShowInnerToolbar({showAdd: pageData.items.length > 0 || pageData.total > 0, showKindSwitcher: false});
        quickSetInnerTitle(`${bt('quick.title.collections', '我的珍藏集')} › ${quickInner.name} · ${bt('quick.title.count', '{count} 件', {count: quickInner.total.toLocaleString()})}`);
        await quickApplyInnerFilters();
        if (loadSeq !== quickInner.loadSeq || !quickInner.open
            || quickInner.type !== 'collection' || quickInner.id !== collectionId) return;
        const totalPages = Math.max(safePage, pageData.hasMore ? safePage + 1 : safePage);
        renderQuickInnerPagination(safePage, totalPages,
            nextPage => loadQuickInnerCollectionWorks(action, collectionId, nextPage));
    }

    function quickInnerUserPageState(type) {
        let pageState = quickInner.userPageStates.get(type);
        if (!pageState) {
            pageState = {pages: new Map(), cursors: new Map(), total: 0};
            quickInner.userPageStates.set(type, pageState);
        }
        return pageState;
    }

    async function fetchQuickInnerUserPage(acq, userId, page) {
        const pageState = quickInnerUserPageState(acq.type);
        const cached = pageState.pages.get(page);
        if (cached) return cached;
        const limit = acq.pageSize;
        const offset = (page - 1) * limit;
        const cursor = page === 1
            ? (acq.initialCursor == null ? null : String(acq.initialCursor))
            : pageState.cursors.get(page);
        if (page > 1 && cursor == null) {
            throw new Error(bt('pagination.error.cursor-unavailable', '分页游标不可用，请重新从第一页加载'));
        }
        const data = await quickFetchJson(quickRequestUrl(acq.buildUserPageRequest(userId, {
            page,
            offset,
            limit,
            cursor
        })), acq.type);
        const items = Array.isArray(data.items) ? data.items : [];
        const hasMore = !!data.hasMore;
        const reportedTotal = Number(data.total);
        const minimumTotal = offset + items.length + (hasMore ? 1 : 0);
        const total = Number.isFinite(reportedTotal) && reportedTotal >= 0
            ? Math.max(Math.floor(reportedTotal), minimumTotal) : minimumTotal;
        const nextCursor = data.nextCursor == null ? '' : String(data.nextCursor);
        if (hasMore && (!nextCursor || (cursor != null && nextCursor === String(cursor)))) {
            throw new Error(bt('pagination.error.cursor-stalled', '分页游标未推进，已停止继续加载'));
        }
        pageState.total = Math.max(pageState.total, total);
        const result = {items, total: pageState.total, hasMore, nextCursor};
        pageState.pages.set(page, result);
        if (hasMore && nextCursor) pageState.cursors.set(page + 1, nextCursor);
        return result;
    }

    // 关注用户 → 该用户作品（插画/小说切换）
    async function quickEnterFollowingUser(idx) {
        const u = (quickState.followingRendered || quickState.followingAll)[idx];
        if (!u) return;
        const userId = String(u.userId);
        const userName = u.userName || userId;
        const outerLoadSeq = quickState.loadSeq;
        const enterSeq = ++quickInner.loadSeq;
        quickCleanupInnerBlobUrls();
        quickInner.open = true;
        quickInner.type = 'following-user';
        quickInner.userId = userId;
        quickInner.name = userName;
        quickHighlightOuterCard(`#quick-preview-area .quick-following-card:nth-of-type(${idx + 1})`);
        quickShowInnerSection();
        document.getElementById('quick-inner-area').innerHTML = quickLoadingHtml();
        quickShowInnerToolbar({showAdd: false, showKindSwitcher: false});
        try {
            const acquisitions = quickUserAcquisitionsForAction();
            quickInner.idsByType = new Map();
            quickInner.userPageStates = new Map();
            await Promise.all(acquisitions.map(async acq => {
                if (typeof acq.buildUserPageRequest === 'function') {
                    await fetchQuickInnerUserPage(acq, userId, 1);
                } else {
                    const data = await quickFetchJson(
                        quickRequestUrl(acq.buildUserIdsRequest(userId)), acq.type);
                    quickInner.idsByType.set(acq.type, data.ids || []);
                }
            }));
            if (enterSeq !== quickInner.loadSeq || outerLoadSeq !== quickState.loadSeq
                || !quickInner.open || quickInner.userId !== userId) return;
            quickInner.kind = acquisitions.find(acq => {
                if (typeof acq.buildUserPageRequest === 'function') {
                    const first = quickInnerUserPageState(acq.type).pages.get(1);
                    return first && (first.items.length > 0 || first.total > 0);
                }
                return (quickInner.idsByType.get(acq.type) || []).length > 0;
            })?.type
                || (acquisitions[0] && acquisitions[0].type) || null;
            if (!quickInner.kind) {
                throw new Error(bt('quick.error.unknown-action', '该入口当前不可用'));
            }
            await loadQuickInnerFollowingUserWorks(quickInner.kind, 1);
        } catch (e) {
            if (outerLoadSeq !== quickState.loadSeq || !quickInner.open || quickInner.userId !== userId) return;
            document.getElementById('quick-inner-area').innerHTML =
                `<div class="quick-empty">${esc(bt('quick.error.load-failed', '加载失败：{message}', {message: e.message || String(e)}))}</div>`;
        }
    }

    async function loadQuickInnerFollowingUserWorks(kind, page) {
        const loadSeq = ++quickInner.loadSeq;
        quickInner.kind = kind;
        const acq = window.PixivBatch.queueTypes.acquisition(kind, 'quick');
        const limit = acq.pageSize;
        let safePage = Math.max(1, Number(page) || 1);
        let totalPages;
        let total;
        let items;
        if (typeof acq.buildUserPageRequest === 'function') {
            const pageData = await fetchQuickInnerUserPage(acq, quickInner.userId, safePage);
            if (loadSeq !== quickInner.loadSeq || !quickInner.open || quickInner.kind !== kind) return;
            items = pageData.items;
            total = pageData.total;
            totalPages = Math.max(safePage, pageData.hasMore ? safePage + 1 : safePage);
            quickInner.allIds = [];
        } else {
            const allIds = quickInner.idsByType.get(kind) || [];
            quickInner.allIds = allIds;
            totalPages = Math.max(1, Math.ceil(allIds.length / limit));
            safePage = Math.min(safePage, totalPages);
            const slice = allIds.slice((safePage - 1) * limit, safePage * limit);
            items = [];
            if (slice.length > 0) {
                const data = await quickFetchJson(
                    quickRequestUrl(acq.buildCardsRequest(quickInner.userId, slice)), kind);
                if (loadSeq !== quickInner.loadSeq || !quickInner.open || quickInner.kind !== kind) return;
                items = data.items || [];
            }
            total = allIds.length;
        }
        items.forEach(it => { it.kind = kind; });
        quickInner.rawItems = items;
        quickInner.total = total;
        quickInner.page = safePage;
        quickInner.pageSize = limit;
        quickShowInnerToolbar({showAdd: items.length > 0, showKindSwitcher: true, kind});
        quickSetInnerTitle(`${bt('quick.preview.parent.following', '我的关注')} › ${quickInner.name} · ${bt('quick.title.count', '{count} 件', {count: total.toLocaleString()})}`);
        await quickApplyInnerFilters();
        if (loadSeq !== quickInner.loadSeq || !quickInner.open || quickInner.kind !== kind) return;
        renderQuickInnerPagination(safePage, totalPages, p => loadQuickInnerFollowingUserWorks(kind, p));
        // 已预览到某关注画师的作品：显示附加筛选与「存为计划任务」（USER_NEW，来源锁定为该画师）。
        updateExtraFiltersCardVisibility();
        updateSaveScheduleCardVisibility();
        applyNovelSettingsVisibility();
    }

    function quickInnerQueueId(item) {
        return quickQueueId(item, item.kind || quickInner.kind);
    }

    function quickInnerToggleQueue(idx) {
        const item = quickInner.items[idx];
        if (!item) return;
        const id = quickInnerQueueId(item);
        const meta = buildQuickQueueMeta(item, item.kind || quickInner.kind);
        const existing = state.queue.find(q => q.id === id);
        if (existing) {
            const merged = reconcileQueueItemTypeData(existing, meta, 'toggle');
            if (merged.keepExisting) {
                if (merged.changed) {
                    updateStats();
                    saveQueue();
                    renderQueue();
                }
                setStatus(bt('status.already-in-queue', '已在队列中：{title}', {title: item.title || id}), 'info');
                return;
            }
            const removed = removeFromQueue(id);
            setStatus(removed
                    ? bt('status.removed-from-queue', '已从队列移除：{title}', {title: item.title || id})
                    : bt('status.cannot-remove-downloading', '无法移除（正在下载中）：{title}', {title: item.title || id}),
                removed ? 'info' : 'warning');
            syncQuickQueueState();
            return;
        }
        const added = addItemsToQueue([id], [meta], QUICK_FETCH_MODE, '', meta.authorId, meta.authorName);
        setStatus(added > 0
                ? bt('status.added-to-queue', '已加入队列：{title}', {title: item.title || id})
                : bt('status.already-in-queue', '已在队列中：{title}', {title: item.title || id}),
            added > 0 ? 'success' : 'info');
        syncQuickQueueState();
    }

    function quickInnerAddCurrentPageToQueue() {
        if (!quickInner.items.length) return;
        const ids = quickInner.items.map(quickInnerQueueId);
        const metas = quickInner.items.map(item => buildQuickQueueMeta(item, item.kind || quickInner.kind));
        const added = addItemsToQueue(ids, metas, QUICK_FETCH_MODE, '', null, '');
        setStatus(
            bt('status.added-current-series-page-to-queue', '已将当前页 {added} 个作品加入队列（本页 {total} 个，{existing} 个已在队列中）',
                {added, total: ids.length, existing: ids.length - added}),
            added > 0 ? 'success' : 'info'
        );
        syncQuickQueueState();
    }

    async function quickInnerAddAllToQueue() {
        // 用 rawItems 判空：附加筛选可能把当前页全部过滤掉（items 为空），但仍有全量作品可入队。
        if (!quickInner.rawItems.length && quickInner.total <= 0) return;
        // 珍藏集支持 cursor 分页；旧 action 的一次性 works 响应仍直接整集入队。
        if (quickInner.type === 'collection') {
            const action = currentQuickAction();
            const ids = [];
            const metas = [];
            const collected = new Set();
            const acc = (items) => (items || []).forEach(item => {
                const id = quickInnerQueueId(item);
                if (collected.has(id)) return;
                collected.add(id);
                const kind = window.PixivBatch.queueTypes.resolveTypeForMode(item.kind, 'quick', quickInner.kind);
                ids.push(id);
                metas.push(buildQuickQueueMeta(item, kind));
            });
            acc(quickInner.rawItems);
            if (action && typeof action.buildCollectionWorksPageRequest === 'function') {
                const expectedPages = Math.max(1, Math.ceil(quickInner.total / quickInner.pageSize));
                const outerLoadSeq = quickState.loadSeq;
                const innerLoadSeq = quickInner.loadSeq;
                const collectionId = quickInner.id;
                const isCurrent = () => outerLoadSeq === quickState.loadSeq
                    && innerLoadSeq === quickInner.loadSeq && quickInner.id === collectionId;
                const confirmed = await uiConfirmKey('quick.confirm.add-all-paged',
                    '将逐页抓取 {pages} 页（共 {total} 个）并加入队列，请求较多，确认继续？',
                    {pages: expectedPages, total: quickInner.total});
                if (!confirmed || !isCurrent()) return;
                setQuickBtnLoading('quick-inner-add-all', true);
                try {
                    let page = 1;
                    let hasMore = true;
                    while (hasMore) {
                        if (page > 1000) {
                            throw new Error(bt('pagination.error.safety-limit', '分页数量超过安全限制'));
                        }
                        const pageData = await fetchQuickInnerCollectionPage(action, collectionId, page);
                        if (innerLoadSeq !== quickInner.loadSeq || quickInner.id !== collectionId) return;
                        acc(pageData.items);
                        hasMore = pageData.hasMore;
                        setStatus(bt('status.series-fetch-all-progress', '正在补齐系列分页 {page} / {total}...',
                            {page, total: Math.max(page, expectedPages)}), 'info');
                        page++;
                    }
                } catch (e) {
                    if (shouldIgnoreQuickOperationError(e, isCurrent())) return;
                    setStatus(bt('status.fetch-failed', '获取作品列表失败：{message}', {message: e.message}), 'error');
                    return;
                } finally {
                    setQuickBtnLoading('quick-inner-add-all', false);
                }
            }
            const added = addItemsToQueue(ids, metas, QUICK_FETCH_MODE, '', null, '');
            setStatus(bt('status.added-many-to-queue', '已将 {added} 个作品加入队列（共 {total} 个，{existing} 个已在队列中）',
                {added, total: ids.length, existing: ids.length - added}), added > 0 ? 'success' : 'info');
            syncQuickQueueState();
            return;
        }
        const kind = quickInner.kind;
        const acq = window.PixivBatch.queueTypes.acquisition(kind, 'quick');
        const totalPages = Math.max(1, Math.ceil(quickInner.total / quickInner.pageSize));
        const outerLoadSeq = quickState.loadSeq;
        const innerLoadSeq = quickInner.loadSeq;
        const userId = quickInner.userId;
        const isCurrent = () => outerLoadSeq === quickState.loadSeq
            && innerLoadSeq === quickInner.loadSeq && quickInner.userId === userId;
        const confirmed = await uiConfirmKey('quick.confirm.add-all-paged',
            '将逐页抓取 {pages} 页（共 {total} 个）并加入队列，请求较多，确认继续？',
            {pages: totalPages, total: quickInner.total});
        if (!confirmed || !isCurrent()) return;
        const ids = [];
        const metas = [];
        const collected = new Set();
        const acc = (items) => {
            items.forEach(item => {
                const id = quickQueueId(item, kind);
                if (collected.has(id)) return;
                collected.add(id);
                ids.push(id);
                metas.push(buildQuickQueueMeta(item, kind));
            });
        };
        // 「全部加入队列」入队全量（未过滤）作品，附加筛选不符者在下载时逐作品跳过；预览筛选只影响「当前页加入队列」。
        // 当前页可能不是第 1 页，需要遍历 1..totalPages 全部页码，当前页直接复用 rawItems；collected 兜底去重。
        acc(quickInner.rawItems);
        setQuickBtnLoading('quick-inner-add-all', true);
        try {
            if (typeof acq.buildUserPageRequest === 'function') {
                let page = 1;
                let hasMore = true;
                while (hasMore) {
                    if (page > 1000) {
                        throw new Error(bt('pagination.error.safety-limit', '分页数量超过安全限制'));
                    }
                    const pageData = await fetchQuickInnerUserPage(acq, userId, page);
                    if (innerLoadSeq !== quickInner.loadSeq || quickInner.userId !== userId) return;
                    acc(pageData.items);
                    hasMore = pageData.hasMore;
                    setStatus(bt('status.user-fetch-all-progress', '正在抓取画师作品卡片 {done} / {total}...',
                        {done: ids.length, total: pageData.total}), 'info');
                    page++;
                }
            } else {
                for (let p = 1; p <= totalPages; p++) {
                    if (p === quickInner.page) continue;
                    setStatus(bt('status.user-fetch-all-progress', '正在抓取画师作品卡片 {done} / {total}...',
                        {done: ids.length, total: quickInner.total}), 'info');
                    acc(await quickFetchInnerPage(p, kind));
                    if (innerLoadSeq !== quickInner.loadSeq || quickInner.userId !== userId) return;
                }
            }
            const added = addItemsToQueue(ids, metas, QUICK_FETCH_MODE, '', null, '');
            setStatus(
                bt('status.added-many-to-queue', '已将 {added} 个作品加入队列（共 {total} 个，{existing} 个已在队列中）',
                    {added, total: ids.length, existing: ids.length - added}),
                added > 0 ? 'success' : 'info'
            );
            syncQuickQueueState();
        } catch (e) {
            if (shouldIgnoreQuickOperationError(e, isCurrent())) return;
            setStatus(bt('status.fetch-failed', '获取作品列表失败：{message}', {message: e.message}), 'error');
        } finally {
            setQuickBtnLoading('quick-inner-add-all', false);
        }
    }

    // 二层作品分页抓取；cursor action 返回页内 items，旧关注用户路径继续以 ids/cards 兼容。
    async function quickFetchInnerPage(page, kind) {
        if (quickInner.type === 'collection') {
            const action = currentQuickAction();
            if (action && typeof action.buildCollectionWorksPageRequest === 'function') {
                return (await fetchQuickInnerCollectionPage(action, quickInner.id, page)).items;
            }
            return page === 1 ? quickInner.rawItems : [];
        }
        if (quickInner.type !== 'following-user') return [];
        const acq = window.PixivBatch.queueTypes.acquisition(kind, 'quick');
        if (typeof acq.buildUserPageRequest === 'function') {
            return (await fetchQuickInnerUserPage(acq, quickInner.userId, page)).items;
        }
        const limit = quickInner.pageSize;
        const offset = (page - 1) * limit;
        const slice = quickInner.allIds.slice(offset, offset + limit);
        if (!slice.length) return [];
        const data = await quickFetchJson(
            quickRequestUrl(acq.buildCardsRequest(quickInner.userId, slice)), kind);
        return data.items || [];
    }

    // 内层 kind 切换（仅关注用户钻取有插画/小说切换）
    document.addEventListener('change', (e) => {
        const target = e.target;
        if (!target || target.name !== 'quick-inner-kind') return;
        if (!quickInner.open || quickInner.type !== 'following-user') return;
        if (!quickActionUserWorkTypes().has(target.value)
            || !window.PixivBatch.queueTypes.supports(target.value, 'quick')) return;
        document.querySelectorAll('#quick-inner-kind-switcher label').forEach(l => {
            l.classList.toggle('quick-kind-active', l.dataset.quickKind === target.value);
        });
        loadQuickInnerFollowingUserWorks(target.value, 1);
    });
