'use strict';
    function quickResetView() {
        cleanupQuickBlobUrls();
        resetQuickActionUi();
        quickState.loadSeq++;
        quickState.renderToken++;
        quickState.filterSeq++;
        quickState.action = null;
        quickState.ownerType = null;
        quickState.viewType = null;
        quickState.kind = null;
        quickState.rawItems = [];
        quickState.items = [];
        quickState.total = 0;
        quickState.offset = 0;
        quickState.page = 1;
        quickState.pageSize = QUICK_PAGE_SIZE_ILLUST;
        quickState.allIds = [];
        quickState.followingFilter = '';
        quickState.followingAll = [];
        quickState.followHasNext = false;
        quickState.filterSummary = {rawCount: 0, filteredCount: 0, bookmarkMetaMissing: 0, bookmarkFilterActive: false};
        quickCloseInner();
    }

    function cleanupQuickBlobUrls() {
        quickState.blobUrls.forEach(u => {
            try { URL.revokeObjectURL(u); } catch {}
        });
        quickState.blobUrls = [];
    }

    function quickSetTitle(text) {
        const el = document.getElementById('quick-preview-title');
        if (el) el.textContent = text;
    }

    function quickShowToolbar(opts) {
        const toolbar = document.getElementById('quick-preview-toolbar');
        if (!toolbar) return;
        toolbar.style.display = '';
        const addPage = document.getElementById('quick-add-page');
        const addAll = document.getElementById('quick-add-all');
        addPage.style.display = opts.showAdd ? '' : 'none';
        addPage.disabled = !opts.showAdd;
        addAll.style.display = opts.showAdd ? '' : 'none';
        addAll.disabled = !opts.showAdd;
        document.getElementById('quick-following-search').style.display = opts.showSearch ? '' : 'none';
        // 收起按钮仅在有作品网格（可加入队列）时出现，与「加入队列」按钮同显隐。
        const collapseBtn = document.getElementById('quick-collapse-page');
        if (collapseBtn) collapseBtn.style.display = opts.showAdd ? '' : 'none';
    }

    // 加载态：转圈块 + 按钮 / 快捷按钮的 is-loading
    function quickLoadingHtml(msg) {
        return `<div class="quick-loading"><span class="quick-spinner"></span><span>${esc(msg || bt('quick.loading', '加载中…'))}</span></div>`;
    }

    function setQuickActionLoading(action, loading) {
        document.querySelectorAll('.quick-action').forEach(b => {
            if (b.dataset.quick === action) b.classList.toggle('is-loading', loading);
        });
    }

    function resetQuickActionUi() {
        document.querySelectorAll('.quick-action').forEach(button => {
            button.classList.toggle('quick-active', false);
            button.classList.toggle('is-loading', false);
        });
        applyQuickActionCredentialUi();
    }

    function setQuickBtnLoading(id, loading) {
        const b = document.getElementById(id);
        if (!b) return;
        b.classList.toggle('is-loading', loading);
        b.disabled = loading;
    }

    function shouldIgnoreQuickOperationError(error, stillCurrent) {
        if (!stillCurrent) return true;
        return !!error && (error.name === 'AbortError'
            || error.code === 'STALE_ACQUISITION'
            || error.code === 'STALE_QUEUE_TYPE');
    }

    async function quickLoad(action) {
        // 据动作映射（内置 + 各可用类型贡献）派发；未知 / 不可用类型的动作（如禁用小说后被触发的小说入口）
        // → 不发起任何抓取、给出空态提示（取得侧不产生其专属请求；其入口按钮通常也已随 slot 缺席）。
        const desc = quickActionMap()[action];
        if (!desc) {
            quickRenderEmpty(bt('quick.error.unknown-action', '该入口当前不可用'));
            return;
        }
        if (desc.dataSourceId && desc.dataSourceId !== quickState.dataSourceId) {
            selectQuickDataSource(desc.dataSourceId, false);
        }
        const credential = quickActionCredentialState(desc);
        if (credential.missing) {
            quickResetView();
            quickState.action = action;
            quickState.ownerType = desc.ownerType;
            quickRenderEmpty(credential.hint);
            applyQuickActionCredentialUi();
            await updateQuickAccountBar(desc.ownerType);
            return;
        }
        const lease = window.PixivBatch.queueTypes.acquisitionLease(desc.ownerType, 'quick');
        quickResetView();
        const loadSeq = quickState.loadSeq;
        const isCurrent = () => lease.isCurrent() && loadSeq === quickState.loadSeq;
        quickState.action = action;
        quickState.ownerType = desc.ownerType;
        const assertCurrent = () => {
            lease.assertCurrent();
            if (loadSeq !== quickState.loadSeq) {
                const error = new Error(bt('quick.error.stale-request', '快捷获取请求已过期'));
                error.code = 'STALE_ACQUISITION';
                throw error;
            }
        };
        const publishWorks = async payload => {
            assertCurrent();
            if (!payload || typeof payload !== 'object' || Array.isArray(payload)) {
                throw new Error('quick works payload must be an object');
            }
            const items = Array.isArray(payload.items) ? payload.items.slice() : [];
            const declaredTotal = Number(payload.total);
            quickState.rawItems = items;
            quickState.total = Number.isFinite(declaredTotal) && declaredTotal >= 0
                ? Math.max(items.length, Math.floor(declaredTotal)) : items.length;
            quickSetTitle(payload.title == null ? '' : String(payload.title));
            const toolbar = payload.toolbar && typeof payload.toolbar === 'object'
                && !Array.isArray(payload.toolbar) ? payload.toolbar : {};
            quickShowToolbar({
                showBack: toolbar.showBack === true,
                showAdd: toolbar.showAdd === true,
                showSearch: toolbar.showSearch === true,
                showKindSwitcher: toolbar.showKindSwitcher === true
            });
            await quickRenderOuterWorks();
            assertCurrent();
            syncQuickQueueState();
            updateExtraFiltersCardVisibility();
            updateSaveScheduleCardVisibility();
            applyNovelSettingsVisibility();
        };
        // 高亮当前按钮
        document.querySelectorAll('.quick-action').forEach(b => {
            b.classList.toggle('quick-active', b.dataset.quick === action);
        });
        // 加载态：预览区转圈 + 当前按钮转圈（先展开预览，使加载态与新结果可见）
        resetPreviewCollapse('quick-preview-area', 'quick-pagination');
        const area = document.getElementById('quick-preview-area');
        if (area) area.innerHTML = quickLoadingHtml();
        document.getElementById('quick-preview-toolbar').style.display = 'none';
        const pag = document.getElementById('quick-pagination');
        if (pag) { pag.style.display = 'none'; pag.innerHTML = ''; }
        setQuickActionLoading(action, true);
        try {
            if (desc.viewType) quickState.viewType = desc.viewType;
            if (desc.kind) quickState.kind = desc.kind;
            if (desc.pageSize) quickState.pageSize = desc.pageSize;
            await updateQuickAccountBar(desc.ownerType);
            assertCurrent();
            await desc.load(action, {
                signal: lease.signal,
                isCurrent,
                assertCurrent,
                publishWorks
            });
            assertCurrent();
            if (!isCurrent()) return;
        } catch (e) {
            if (!isCurrent()) return;
            quickRenderEmpty(bt('quick.error.load-failed', '加载失败：{message}', {message: e.message || String(e)}));
        } finally {
            if (!isCurrent()) return;
            setQuickActionLoading(action, false);
            applyQuickActionCredentialUi();
        }
    }

    async function quickFetchJson(url, kind = quickState.kind, operation = 'quick') {
        const loadSeq = quickState.loadSeq;
        const type = window.PixivBatch.queueTypes.resolveTypeForMode(kind, 'quick');
        if (!type) throw new Error(bt('quick.error.unknown-action', '该入口当前不可用'));
        const request = window.PixivBatch.queueTypes.prepareAcquisitionRequest(
            type, 'quick', url, operation, {action: quickState.action});
        const res = await fetch(request.url, request.init);
        const data = await res.json().catch(() => ({}));
        request.assertCurrent();
        if (loadSeq !== quickState.loadSeq) {
            const error = new Error(bt('quick.error.stale-request', '快捷获取请求已过期'));
            error.code = 'STALE_ACQUISITION';
            throw error;
        }
        if (!res.ok || data.error) {
            const msg = data.error || data.message || `HTTP ${res.status}`;
            throw new Error(msg);
        }
        return data;
    }

    async function loadQuickIllustBookmarks(kind, rest, page) {
        const acq = window.PixivBatch.queueTypes.acquisition(kind, 'quick');
        const action = currentQuickAction();
        if (!action || typeof action.buildPageRequest !== 'function') {
            throw new Error(bt('quick.error.unknown-action', '该入口当前不可用'));
        }
        const pageSize = acq.pageSize;
        const offset = (page - 1) * pageSize;
        const endpoint = quickRequestUrl(action.buildPageRequest({rest, offset, limit: pageSize, page}));
        const data = await quickFetchJson(endpoint, kind);
        quickState.kind = kind;
        quickState.rawItems = data.items || [];
        quickState.total = data.total || 0;
        quickState.offset = offset;
        quickState.page = page;
        const titleKey = rest === 'hide' ? 'quick.title.illust-bookmarks-hide' : 'quick.title.illust-bookmarks-show';
        const titleFallback = rest === 'hide' ? '我的收藏（插画/漫画，不公开）' : '我的收藏（插画/漫画，公开）';
        quickSetTitle(`${bt(titleKey, titleFallback)} · ${bt('quick.title.count', '{count} 件', {count: quickState.total.toLocaleString()})}`);
        quickShowToolbar({showBack: false, showAdd: quickState.rawItems.length > 0, showSearch: false, showKindSwitcher: false});
        await quickRenderOuterWorks();
        renderQuickPagination(page, Math.max(1, Math.ceil(quickState.total / pageSize)),
            p => loadQuickIllustBookmarks(kind, rest, p));
        updateExtraFiltersCardVisibility();
        updateSaveScheduleCardVisibility();
        applyNovelSettingsVisibility();
    }

    async function loadQuickMyWorks(kind, page, context) {
        assertQuickActionContext(context);
        const acq = window.PixivBatch.queueTypes.acquisition(kind, 'quick');
        const accountOwner = window.PixivBatch.queueTypes.resolveTypeForMode(kind, 'quick');
        if (!quickState.uid || quickState.accountOwner !== accountOwner) {
            const spec = acq.account && acq.account.buildRequest();
            const data = await quickFetchJson(quickRequestUrl(spec), kind, 'account');
            assertQuickActionContext(context);
            quickState.uid = String(acq.account.readId(data));
            quickState.accountOwner = accountOwner;
            quickState.accountIdsByOwner.set(accountOwner, quickState.uid);
            const uidEl = document.getElementById('quick-account-uid');
            if (uidEl) uidEl.textContent = quickState.uid;
        }
        const uid = quickState.uid;
        // 该类型的 quick 钩子贡献「我的作品」ID 端点 / 卡片端点 / 分页大小 / 标题。
        // 拉全 ID 一次，缓存到 allIds
        if (!quickState.allIds.length || quickState.action.endsWith('-refresh')) {
            const data = await quickFetchJson(
                quickRequestUrl(acq.buildMyWorksIdsRequest(uid)), kind);
            assertQuickActionContext(context);
            quickState.allIds = data.ids || [];
        }
        const pageSize = acq.pageSize;
        const total = quickState.allIds.length;
        const totalPages = Math.max(1, Math.ceil(total / pageSize));
        const safePage = Math.min(Math.max(1, page), totalPages);
        const slice = quickState.allIds.slice((safePage - 1) * pageSize, safePage * pageSize);
        let items = [];
        if (slice.length > 0) {
            const data = await quickFetchJson(
                quickRequestUrl(acq.buildCardsRequest(uid, slice)), kind);
            assertQuickActionContext(context);
            items = data.items || [];
        }
        quickState.kind = window.PixivBatch.queueTypes.resolveTypeForMode(kind, 'quick');
        quickState.rawItems = items;
        quickState.total = total;
        quickState.page = safePage;
        quickState.pageSize = pageSize;
        const titleKey = acq.myWorksTitleKey;
        const titleFallback = titleKey;
        quickSetTitle(`${bt(titleKey, titleFallback)} · ${bt('quick.title.count', '{count} 件', {count: total.toLocaleString()})}`);
        quickShowToolbar({showBack: false, showAdd: items.length > 0, showSearch: false, showKindSwitcher: false});
        await quickRenderOuterWorks();
        assertQuickActionContext(context);
        renderQuickPagination(safePage, totalPages, p => loadQuickMyWorks(kind, p, context));
        updateExtraFiltersCardVisibility();
        updateSaveScheduleCardVisibility();
        applyNovelSettingsVisibility();
    }

    // 我的约稿作品（账号自身已完成并公开的约稿成品）：先拉全 ID（约稿发现端点）再本地分页取 illust 卡片，渲染同插画。
    async function loadQuickMyRequest(kind, page) {
        const acq = window.PixivBatch.queueTypes.acquisition(kind, 'quick');
        const accountOwner = window.PixivBatch.queueTypes.resolveTypeForMode(kind, 'quick');
        const action = currentQuickAction();
        if (!action || typeof action.buildIdsRequest !== 'function'
            || typeof action.buildCardsRequest !== 'function') {
            throw new Error(bt('quick.error.unknown-action', '该入口当前不可用'));
        }
        if (!quickState.uid || quickState.accountOwner !== accountOwner) {
            const data = await quickFetchJson(
                quickRequestUrl(acq.account.buildRequest()), kind, 'account');
            quickState.uid = String(acq.account.readId(data));
            quickState.accountOwner = accountOwner;
            quickState.accountIdsByOwner.set(accountOwner, quickState.uid);
            const uidEl = document.getElementById('quick-account-uid');
            if (uidEl) uidEl.textContent = quickState.uid;
        }
        const uid = quickState.uid;
        if (!quickState.allIds.length || quickState.action.endsWith('-refresh')) {
            const data = await quickFetchJson(quickRequestUrl(action.buildIdsRequest(uid)), kind);
            quickState.allIds = data.ids || [];
        }
        const pageSize = acq.pageSize;
        const total = quickState.allIds.length;
        const totalPages = Math.max(1, Math.ceil(total / pageSize));
        const safePage = Math.min(Math.max(1, page), totalPages);
        const slice = quickState.allIds.slice((safePage - 1) * pageSize, safePage * pageSize);
        let items = [];
        if (slice.length > 0) {
            const data = await quickFetchJson(
                quickRequestUrl(action.buildCardsRequest(uid, slice)), kind);
            items = data.items || [];
        }
        quickState.kind = kind;
        quickState.rawItems = items;
        quickState.total = total;
        quickState.page = safePage;
        quickState.pageSize = pageSize;
        quickSetTitle(`${bt('quick.title.my-request', '我的约稿作品')} · ${bt('quick.title.count', '{count} 件', {count: total.toLocaleString()})}`);
        quickShowToolbar({showBack: false, showAdd: items.length > 0, showSearch: false, showKindSwitcher: false});
        await quickRenderOuterWorks();
        renderQuickPagination(safePage, totalPages, p => loadQuickMyRequest(kind, p));
        updateExtraFiltersCardVisibility();
        updateSaveScheduleCardVisibility();
        applyNovelSettingsVisibility();
    }

    async function loadQuickFollowing(rest, offset, kind = quickState.kind) {
        const action = currentQuickAction();
        if (!action || typeof action.buildPageRequest !== 'function') {
            throw new Error(bt('quick.error.unknown-action', '该入口当前不可用'));
        }
        const data = await quickFetchJson(quickRequestUrl(action.buildPageRequest({
            rest, offset, limit: QUICK_FOLLOWING_PAGE_SIZE
        })), kind);
        quickState.followingAll = data.users || [];
        quickState.total = data.total || 0;
        quickState.offset = offset;
        quickState.page = Math.floor(offset / QUICK_FOLLOWING_PAGE_SIZE) + 1;
        const titleKey = rest === 'hide' ? 'quick.title.following-hide' : 'quick.title.following-show';
        const titleFallback = rest === 'hide' ? '我的关注（不公开）' : '我的关注（公开）';
        quickSetTitle(`${bt(titleKey, titleFallback)} · ${bt('quick.title.count', '{count} 件', {count: quickState.total.toLocaleString()})}`);
        quickShowToolbar({showBack: false, showAdd: false, showSearch: true, showKindSwitcher: false});
        document.getElementById('quick-following-search').value = '';
        renderQuickFollowingGrid(quickState.followingAll, rest);
        const totalPages = Math.max(1, Math.ceil(quickState.total / QUICK_FOLLOWING_PAGE_SIZE));
        renderQuickPagination(quickState.page, totalPages,
            p => loadQuickFollowing(rest, (p - 1) * QUICK_FOLLOWING_PAGE_SIZE, kind));
        // 关注用户列表是纯选择页（无作品卡片）：隐藏附加筛选 / 存为计划任务，待点进某画师后再显示。
        updateExtraFiltersCardVisibility();
        updateSaveScheduleCardVisibility();
        applyNovelSettingsVisibility();
    }

    // 已关注的用户的新作（フォロー新着作品）：插画/漫画/动图卡片，按页翻阅。
    // Pixiv follow_latest 不返回总数，分页仅有 hasNext，故用专用的「上一页/下一页」翻页器。
    async function loadQuickFollowingNew(kind, page) {
        const safePage = Math.max(1, page);
        const action = currentQuickAction();
        if (!action || typeof action.buildPageRequest !== 'function') {
            throw new Error(bt('quick.error.unknown-action', '该入口当前不可用'));
        }
        const data = await quickFetchJson(
            quickRequestUrl(action.buildPageRequest({page: safePage})), kind);
        quickState.rawItems = data.items || [];
        quickState.followHasNext = !!data.hasNext;
        quickState.page = safePage;
        quickState.kind = kind;
        quickState.viewType = 'works-list';
        quickState.pageSize = window.PixivBatch.queueTypes.acquisition(kind, 'quick').pageSize;
        quickSetTitle(`${bt('quick.title.following-new', '已关注的用户的新作')} · ${bt('quick.title.page', '第 {page} 页', {page: safePage})}`);
        quickShowToolbar({showBack: false, showAdd: quickState.rawItems.length > 0, showSearch: false, showKindSwitcher: false});
        await quickRenderOuterWorks();
        renderQuickFollowNewPagination(safePage, quickState.followHasNext, kind);
        updateExtraFiltersCardVisibility();
        updateSaveScheduleCardVisibility();
        applyNovelSettingsVisibility();
    }

    function renderQuickFollowNewPagination(currentPage, hasNext, kind) {
        const pag = document.getElementById('quick-pagination');
        if (!pag) return;
        const cur = Math.max(1, Number(currentPage || 1));
        if (cur <= 1 && !hasNext) {
            pag.style.display = 'none';
            pag.innerHTML = '';
            return;
        }
        pag.style.display = 'flex';
        quickState._jumpFn = p => loadQuickFollowingNew(kind, p);
        pag.innerHTML =
            `<button data-pixiv-click="quickJumpPage(1)" ${cur === 1 ? 'disabled' : ''}>&laquo;</button>` +
            `<button data-pixiv-click="quickJumpPage(${cur - 1})" ${cur === 1 ? 'disabled' : ''}>&lsaquo;</button>` +
            `<button class="pg-active" disabled>${cur}</button>` +
            `<button data-pixiv-click="quickJumpPage(${cur + 1})" ${hasNext ? '' : 'disabled'}>&rsaquo;</button>` +
            `<span class="pg-info">${esc(bt('quick.title.page', '第 {page} 页', {page: cur}))}</span>`;
    }

    function quickFilterFollowing() {
        const input = document.getElementById('quick-following-search');
        const q = (input?.value || '').trim().toLowerCase();
        quickState.followingFilter = q;
        const filtered = !q ? quickState.followingAll : quickState.followingAll.filter(u =>
            (u.userName || '').toLowerCase().includes(q) || String(u.userId || '').includes(q));
        renderQuickFollowingGrid(filtered, null);
    }

    // 珍藏集列表：不分公开/不公开、不分插画/小说；Pixiv 无分页，一次性返回全部。
    async function loadQuickCollections() {
        const action = currentQuickAction();
        if (!action || typeof action.buildPageRequest !== 'function') {
            throw new Error(bt('quick.error.unknown-action', '该入口当前不可用'));
        }
        const data = await quickFetchJson(
            quickRequestUrl(action.buildPageRequest({})), action.ownerType);
        quickState.items = data.collections || [];
        quickState.total = data.total || quickState.items.length;
        quickState.page = 1;
        quickSetTitle(`${bt('quick.title.collections', '我的珍藏集')} · ${bt('quick.title.count', '{count} 件', {count: quickState.total.toLocaleString()})}`);
        quickShowToolbar({showAdd: false, showSearch: false});
        renderQuickCollectionGrid(quickState.items);
        renderQuickPagination(1, 1, () => {});
        // 珍藏集列表是纯选择页（无作品卡片）：隐藏附加筛选 / 存为计划任务，待点进某珍藏集后再显示。
        updateExtraFiltersCardVisibility();
        updateSaveScheduleCardVisibility();
        applyNovelSettingsVisibility();
    }

    function renderQuickIllustGrid(items, idPrefix, summaryHtml = '') {
        const area = document.getElementById('quick-preview-area');
        if (!area) return;
        const renderToken = ++quickState.renderToken;
        if (!items.length) {
            const emptyMsg = summaryHtml
                ? bt('status.search-no-filtered-results', '附加筛选后无结果')
                : bt('quick.empty.no-items', '该范围内没有作品');
            area.innerHTML = summaryHtml + `<div class="quick-empty">${esc(emptyMsg)}</div>`;
            return;
        }
        const inQueue = new Set(state.queue.map(q => q.id));
        area.innerHTML = summaryHtml + `<div class="search-grid">${items.map((item, idx) => {
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
            const fallbackTitle = bt('queue.artwork-fallback', '作品 {id}', {id: item.id});
            const title = item.title || fallbackTitle;
            return `<div class="search-thumb${inQueueClass}" id="${idPrefix}-thumb-${idx}"
                     data-pixiv-click="quickToggleItemQueue(${idx})" title="${esc(title)} (${esc(item.userName || '')})">
          <img id="${idPrefix}-thumb-img-${idx}" src="" alt="${esc(title)}">
          <div class="thumb-badge-stack">${r18Badge}${aiBadge}${typeBadge}</div>
          ${pagesLabel}
          <span class="thumb-in-queue-mark">✓</span>
          <div class="thumb-title">${esc(title)}</div>
        </div>`;
        }).join('')}</div>`;
        loadQuickThumbnailsBatched(items, idPrefix, renderToken);
    }

    function renderQuickFollowingGrid(users, restHint) {
        const area = document.getElementById('quick-preview-area');
        if (!area) return;
        if (!users.length) {
            area.innerHTML = `<div class="quick-empty">${esc(bt('quick.empty.no-following', '没有匹配的关注用户'))}</div>`;
            return;
        }
        // 渲染的列表（可能是过滤后的子集）单独缓存，供索引点击时取回原始对象，避免把用户名拼进内联 onclick
        quickState.followingRendered = users;
        area.innerHTML = `<div class="quick-following-grid">${users.map((u, idx) => `
            <div class="quick-following-card" data-pixiv-click="quickEnterFollowingUser(${idx})"
                 title="${esc(u.userName || u.userId)} (ID: ${esc(u.userId)})">
                <div class="quick-following-avatar" id="quick-follow-ava-${idx}"></div>
                <div class="quick-following-meta">
                    <div class="quick-following-name">${esc(u.userName || u.userId)}</div>
                    <div class="quick-following-uid">ID: ${esc(u.userId)}</div>
                </div>
            </div>
        `).join('')}</div>`;
        // 头像异步加载
        const renderToken = quickState.renderToken;
        users.forEach((u, idx) => {
            if (!u.profileImageUrl) return;
            fetchThumbnailBlobUrl(
                u.profileImageUrl, quickState.blobUrls, quickState.kind, 'quick')
                .then(blobUrl => {
                    if (!blobUrl || renderToken !== quickState.renderToken) return;
                    const el = document.getElementById(`quick-follow-ava-${idx}`);
                    if (el) el.innerHTML = `<img src="${blobUrl}" alt="">`;
                })
                .catch(() => {});
        });
    }

    function renderQuickCollectionGrid(collections) {
        const area = document.getElementById('quick-preview-area');
        if (!area) return;
        if (!collections.length) {
            area.innerHTML = `<div class="quick-empty">${esc(bt('quick.empty.no-collections', '没有珍藏集'))}</div>`;
            return;
        }
        area.innerHTML = `<div class="quick-collection-grid">${collections.map((c, idx) => {
            const xr = Number(c.xRestrict ?? 0);
            const r18Badge = xr === 2 ? '<span class="thumb-badge thumb-badge-r18g">R-18G</span>'
                : xr === 1 ? '<span class="thumb-badge">R-18</span>' : '';
            const bm = Number(c.bookmarkCount ?? 0);
            const bmLine = bm > 0
                ? `<div class="quick-collection-count">${esc(bt('search.summary.bookmark-badge', '收藏 {count}', {count: bm.toLocaleString()}))}</div>`
                : '';
            return `<div class="quick-collection-card" data-pixiv-click="quickEnterCollection(${idx})" title="${esc(c.title || c.id)}">
                <div class="quick-collection-cover" id="quick-col-cover-${idx}">${r18Badge ? `<div class="thumb-badge-stack">${r18Badge}</div>` : ''}</div>
                <div class="quick-collection-meta">
                    <div class="quick-collection-name">${esc(c.title || c.id)}</div>
                    ${bmLine}
                </div>
            </div>`;
        }).join('')}</div>`;
        const renderToken = quickState.renderToken;
        collections.forEach((c, idx) => {
            if (!c.coverUrl) return;
            fetchThumbnailBlobUrl(
                c.coverUrl, quickState.blobUrls, quickState.kind, 'quick')
                .then(blobUrl => {
                    if (!blobUrl || renderToken !== quickState.renderToken) return;
                    const el = document.getElementById(`quick-col-cover-${idx}`);
                    if (el) el.insertAdjacentHTML('afterbegin', `<img src="${blobUrl}" alt="">`);
                })
                .catch(() => {});
        });
    }

    function renderQuickPagination(currentPage, totalPages, jumpFn) {
        const pag = document.getElementById('quick-pagination');
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
        quickState._jumpFn = jumpFn;
        pag.innerHTML =
            `<button data-pixiv-click="quickJumpPage(1)" ${cur === 1 ? 'disabled' : ''}>&laquo;</button>` +
            `<button data-pixiv-click="quickJumpPage(${cur - 1})" ${cur === 1 ? 'disabled' : ''}>&lsaquo;</button>` +
            pages.map(p => `<button data-pixiv-click="${p === cur ? '' : `quickJumpPage(${p})`}" ${p === cur ? 'class="pg-active" disabled' : ''}>${p}</button>`).join('') +
            `<button data-pixiv-click="quickJumpPage(${cur + 1})" ${cur === totalPages ? 'disabled' : ''}>&rsaquo;</button>` +
            `<button data-pixiv-click="quickJumpPage(${totalPages})" ${cur === totalPages ? 'disabled' : ''}>&raquo;</button>` +
            `<span class="pg-info">${esc(bt('search.pagination.info', '第 {current} / {total} 页 · 共 {count} 个',
                {current: cur, total: totalPages, count: quickState.total.toLocaleString()}))}</span>`;
    }

    function quickJumpPage(p) {
        if (typeof quickState._jumpFn === 'function') quickState._jumpFn(p);
    }

    async function loadQuickThumbnailsBatched(items, idPrefix, renderToken) {
        for (let i = 0; i < items.length; i += QUICK_THUMB_BATCH) {
            if (renderToken !== quickState.renderToken) return;
            const batch = items.slice(i, i + QUICK_THUMB_BATCH);
            await Promise.allSettled(batch.map((item, offset) => loadQuickSingleThumbnail(item, idPrefix, i + offset, renderToken)));
        }
    }

    async function loadQuickSingleThumbnail(item, idPrefix, idx, renderToken) {
        const url = item.thumbnailUrl || item.url;
        if (!url) return;
        const imgEl = document.getElementById(`${idPrefix}-thumb-img-${idx}`);
        if (!imgEl) return;
        const blobUrl = await fetchThumbnailBlobUrl(
            url, quickState.blobUrls, quickState.kind, 'quick');
        if (renderToken !== quickState.renderToken) return;
        if (blobUrl && imgEl.isConnected) imgEl.src = blobUrl;
    }

    function quickToggleItemQueue(idx) {
        const item = quickState.items[idx];
        if (!item) return;
        const id = quickQueueId(item, quickState.kind);
        const meta = buildQuickQueueMeta(item);
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

    function currentQuickQueueContext() {
        const inner = quickInner.open ? Object.freeze({
            type: quickInner.type || '',
            id: quickInner.id == null ? null : String(quickInner.id),
            name: quickInner.name || '',
            userId: quickInner.userId == null ? null : String(quickInner.userId)
        }) : null;
        return Object.freeze({
            action: quickState.action || '',
            accountOwner: quickState.accountOwner || null,
            accountId: quickState.uid == null ? null : String(quickState.uid),
            inner
        });
    }

    function buildQuickQueueMeta(item, kind = quickState.kind) {
        // 队列模型禁止 bake 翻译文案（会被持久化、跨语言切换继续显示旧译）；
        // title 直接存原始值（可为空），渲染时由 queueItemDisplayTitle(q) 派生 fallback。
        // 类型专属队列 meta（如小说 novelId/kind）由该类型 quick 钩子贡献；插画为内置默认。
        const acq = window.PixivBatch.queueTypes.acquisition(kind, 'quick');
        return acq.buildQueueMeta(item, currentQuickQueueContext());
    }

    function syncQuickQueueState() {
        const inQueue = new Set(state.queue.map(q => q.id));
        // 外层仅在书签 / 我的作品（作品网格）时需要同步；关注 / 珍藏集外层是用户 / 集卡片，无队列态
        if (quickState.viewType === 'works-list') {
            quickSyncGridQueue(quickState.items, quickState.kind, 'quick', inQueue);
        }
        // 内层是混合作品，逐项按自身 kind 计算队列 id，卡片统一用 quick-inner-card-{idx}
        if (quickInner.open && quickInner.items.length) {
            quickInner.items.forEach((item, idx) => {
                const id = quickQueueId(item, item.kind || quickInner.kind);
                const el = document.getElementById(`quick-inner-card-${idx}`);
                if (el) el.classList.toggle('in-queue', inQueue.has(id));
            });
        }
    }

    function quickSyncGridQueue(items, kind, idPrefix, inQueue) {
        if (!items || !items.length) return;
        items.forEach((item, idx) => {
            const id = quickQueueId(item, kind);
            const el = document.getElementById(quickGridCardId(kind, idPrefix, idx));
            if (el) el.classList.toggle('in-queue', inQueue.has(id));
        });
    }

    function quickAddCurrentPageToQueue() {
        if (!quickState.items.length) return;
        const ids = quickState.items.map(item => quickQueueId(item, quickState.kind));
        const metas = quickState.items.map(buildQuickQueueMeta);
        const added = addItemsToQueue(ids, metas, QUICK_FETCH_MODE, '', null, '');
        setStatus(
            bt('status.added-current-series-page-to-queue', '已将当前页 {added} 个作品加入队列（本页 {total} 个，{existing} 个已在队列中）',
                {added, total: ids.length, existing: ids.length - added}),
            added > 0 ? 'success' : 'info'
        );
        syncQuickQueueState();
    }

    async function quickAddAllToQueue() {
        // 用 rawItems 判空：附加筛选可能把当前页全部过滤掉（items 为空），但仍有全量作品可入队。
        if (!quickState.rawItems.length && !quickState.allIds.length) return;
        const action = quickState.action;
        const loadSeq = quickState.loadSeq;
        const isCurrent = () => loadSeq === quickState.loadSeq && quickState.action === action;
        // 「已关注的用户的新作」无总数，从第 1 页逐页抓取直到 hasNext 为 false
        if (action === 'my-following-new') {
            const confirmed = await uiConfirmKey('quick.confirm.add-all-follow-new',
                '将逐页抓取「已关注的用户的新作」直到没有更多并全部加入队列，请求较多，确认继续？');
            if (!confirmed || !isCurrent()) return;
            setQuickBtnLoading('quick-add-all', true);
            const ids = [];
            const metas = [];
            const seen = new Set();
            const acc = (items) => {
                items.forEach(item => {
                    const id = String(item.id);
                    if (seen.has(id)) return;
                    seen.add(id);
                    ids.push(id);
                    metas.push(buildQuickQueueMeta(item, quickState.kind));
                });
            };
            try {
                let page = 1, hasNext = true, guard = 0;
                while (hasNext && guard++ < 500) {
                    setStatus(bt('quick.status.fetching-follow-new',
                        '正在抓取已关注的用户的新作（第 {page} 页，已收集 {count} 个）…',
                        {page, count: ids.length}), 'info');
                    const desc = currentQuickAction();
                    if (!desc || typeof desc.buildPageRequest !== 'function') {
                        throw new Error(bt('quick.error.unknown-action', '该入口当前不可用'));
                    }
                    const data = await quickFetchJson(
                        quickRequestUrl(desc.buildPageRequest({page})), desc.ownerType);
                    acc(data.items || []);
                    hasNext = !!data.hasNext;
                    page++;
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
                setQuickBtnLoading('quick-add-all', false);
            }
            return;
        }
        // 可直接按全量 ID 入队的动作（我的作品 / 我的约稿：无须逐页拉 cards），由动作映射的 allIdsFastPath 标记。
        const allDesc = quickActionMap()[action];
        if (allDesc && allDesc.allIdsFastPath) {
            const confirmed = await uiConfirmKey('quick.confirm.add-all-my-works',
                '将把你的全部 {total} 个作品（含 hide）加入队列，确认继续？',
                {total: quickState.allIds.length});
            if (!confirmed || !isCurrent()) return;
            const acq = window.PixivBatch.queueTypes.acquisition(quickState.kind, 'quick');
            const ids = quickState.allIds.map(id => quickQueueId({id}, quickState.kind));
            // 队列模型禁止 bake 翻译文案；title 留空，渲染时由 queueItemDisplayTitle(q) 派生 fallback。
            // 类型专属裸 id meta（如小说 novelId/kind）由该类型 quick 钩子贡献；插画为空 meta。
            const queueContext = currentQuickQueueContext();
            const metas = quickState.allIds.map(id => acq.buildQueueMetaFromId(id, queueContext));
            const added = addItemsToQueue(ids, metas, QUICK_FETCH_MODE, '', null, '');
            setStatus(
                bt('status.added-many-to-queue', '已将 {added} 个作品加入队列（共 {total} 个，{existing} 个已在队列中）',
                    {added, total: ids.length, existing: ids.length - added}),
                added > 0 ? 'success' : 'info'
            );
            syncQuickQueueState();
            return;
        }
        // Cursor 型账号列表不能按推算页码跳读。始终从 owner 声明的初始游标顺序推进，
        // 完整抓取成功后才一次性入队，避免游标停滞时留下半截队列。
        if (allDesc && allDesc.cursorPaging && typeof allDesc.buildPageRequest === 'function') {
            const expectedPages = Math.max(1, Math.ceil(quickState.total / quickState.pageSize));
            const confirmed = await uiConfirmKey('quick.confirm.add-all-paged',
                '将逐页抓取 {pages} 页（共 {total} 个）并加入队列，请求较多，确认继续？',
                {pages: expectedPages, total: quickState.total});
            if (!confirmed || !isCurrent()) return;
            const ids = [];
            const metas = [];
            const collectedIds = new Set();
            const seenCursors = new Set();
            let cursor = allDesc.initialCursor == null ? null : String(allDesc.initialCursor);
            setQuickBtnLoading('quick-add-all', true);
            try {
                let page = 1;
                let hasMore = true;
                while (hasMore) {
                    if (page > 1000) {
                        throw new Error(bt('pagination.error.safety-limit', '分页数量超过安全限制'));
                    }
                    const cursorKey = cursor == null ? '' : String(cursor);
                    if (seenCursors.has(cursorKey)) {
                        throw new Error(bt('pagination.error.cursor-stalled', '分页游标未推进，已停止继续加载'));
                    }
                    seenCursors.add(cursorKey);
                    const offset = (page - 1) * quickState.pageSize;
                    const rest = allDesc.scheduleRest
                        || (action.endsWith('hide') ? 'hide' : 'show');
                    const data = await quickFetchJson(quickRequestUrl(allDesc.buildPageRequest({
                        rest,
                        page,
                        offset,
                        limit: quickState.pageSize,
                        cursor
                    })), allDesc.ownerType);
                    if (loadSeq !== quickState.loadSeq || quickState.action !== action) return;
                    (data.items || []).forEach(item => {
                        const id = quickQueueId(item, quickState.kind);
                        if (collectedIds.has(id)) return;
                        collectedIds.add(id);
                        ids.push(id);
                        metas.push(buildQuickQueueMeta(item));
                    });
                    hasMore = !!data.hasMore;
                    if (hasMore) {
                        const nextCursor = data.nextCursor == null ? '' : String(data.nextCursor);
                        if (!nextCursor || seenCursors.has(nextCursor)) {
                            throw new Error(bt('pagination.error.cursor-stalled', '分页游标未推进，已停止继续加载'));
                        }
                        cursor = nextCursor;
                    }
                    setStatus(bt('status.user-fetch-all-progress',
                        '正在抓取画师作品卡片 {done} / {total}...',
                        {done: ids.length, total: Math.max(ids.length, quickState.total)}), 'info');
                    page++;
                }
                if (loadSeq !== quickState.loadSeq || quickState.action !== action) return;
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
                setQuickBtnLoading('quick-add-all', false);
            }
            return;
        }
        // 其它（书签 / 珍藏集内 / 关注用户作品）：逐页抓取
        const totalPages = Math.max(1, Math.ceil(quickState.total / quickState.pageSize));
        const confirmed = await uiConfirmKey('quick.confirm.add-all-paged',
            '将逐页抓取 {pages} 页（共 {total} 个）并加入队列，请求较多，确认继续？',
            {pages: totalPages, total: quickState.total});
        if (!confirmed || !isCurrent()) return;
        const ids = [];
        const metas = [];
        const collectedIds = new Set();
        const acc = (items) => {
            items.forEach(item => {
                const id = quickQueueId(item, quickState.kind);
                if (collectedIds.has(id)) return;
                collectedIds.add(id);
                ids.push(id);
                metas.push(buildQuickQueueMeta(item));
            });
        };
        // 「全部加入队列」入队全量（未过滤）作品，实际不符合附加筛选者在下载时逐作品跳过；预览筛选只影响「当前页加入队列」。
        // 当前页可能不是第 1 页（用户停在第 N 页才点「全部」），需要遍历 1..totalPages 全部页码，
        // 当前页直接复用 rawItems 以避免重复请求；collectedIds 兜底去重。
        acc(quickState.rawItems);
        setQuickBtnLoading('quick-add-all', true);
        try {
            for (let p = 1; p <= totalPages; p++) {
                if (p === quickState.page) continue;
                setStatus(bt('status.user-fetch-all-progress', '正在抓取画师作品卡片 {done} / {total}...',
                    {done: ids.length, total: quickState.total}), 'info');
                const more = await quickFetchPage(p);
                acc(more);
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
            setQuickBtnLoading('quick-add-all', false);
        }
    }

    // 外层「全部加入队列」按页抓取（仅书签外层会用到；关注 / 珍藏集外层无 add-all）。
