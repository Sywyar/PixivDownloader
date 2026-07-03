'use strict';
    /* ============================================================
       快捷获取（账户相关作品）
       ============================================================
       依赖当前 cookie 主人的 uid（由后端 /api/pixiv/me/uid 从 PHPSESSID 前缀解析）。
       一层视图：插画书签 / 小说书签 / 我的插画 / 我的小说 / 关注 / 珍藏集列表。
       二层视图（在同一 Tab 内置呈现）：
         - following → 选定用户的 illust / novel（kind 切换）
         - collection-list → 选定珍藏集内的 illust / novel
    */

    const QUICK_PAGE_SIZE_ILLUST = 48;
    const QUICK_PAGE_SIZE_NOVEL = 24;
    const QUICK_FOLLOWING_PAGE_SIZE = 24;
    const QUICK_THUMB_BATCH = 10;

    const quickState = {
        action: null,
        uid: null,
        viewType: null,   // 'illust-list' | 'novel-list' | 'following-list' | 'collection-list'
        kind: 'illust',   // 当前作品列表的 kind（用于队列归类与渲染）
        rawItems: [],     // outer：当前页未经附加筛选的原始作品卡片（live 预览过滤的事实源）
        items: [],        // outer：附加筛选后用于渲染 / 入队的卡片 / 用户 / 珍藏集
        total: 0,
        offset: 0,
        page: 1,
        pageSize: QUICK_PAGE_SIZE_ILLUST,
        // 「我的作品」需先获取全部 ID 再分页取 cards
        allIds: [],
        // following 客户端搜索
        followingFilter: '',
        followingAll: [],
        // follow_latest（已关注的用户的新作）无总数，仅以 hasNext 驱动「下一页」
        followHasNext: false,
        renderToken: 0,
        // 附加筛选竞态序号 + 最近一次过滤统计（与 userState 同形）
        filterSeq: 0,
        filterSummary: {rawCount: 0, filteredCount: 0, bookmarkMetaMissing: 0, bookmarkFilterActive: false},
        blobUrls: []
    };

    // 二层钻取：点击外层的关注用户 / 珍藏集后，在外层列表「下方」追加这块作品预览（非替换式）。
    // 它有独立的状态、容器、渲染令牌与 blob 缓存，与外层互不干扰。
    const quickInner = {
        open: false,
        type: null,        // 'following-user' | 'collection'
        id: null,          // 珍藏集 cid
        userId: null,      // 关注用户 uid
        name: '',
        workCategory: null,
        kind: 'illust',
        allIllustIds: [],  // following-user：该用户全部插画/漫画 ID
        allNovelIds: [],   // following-user：该用户全部小说 ID
        allIds: [],        // 当前 kind 对应的全部 ID（following-user 用）
        rawItems: [],      // 当前页未经附加筛选的原始作品（live 预览过滤的事实源）
        items: [],         // 附加筛选后用于渲染 / 入队的作品
        total: 0,
        page: 1,
        pageSize: QUICK_PAGE_SIZE_ILLUST,
        renderToken: 0,
        // 附加筛选竞态序号 + 最近一次过滤统计
        filterSeq: 0,
        filterSummary: {rawCount: 0, filteredCount: 0, bookmarkMetaMissing: 0, bookmarkFilterActive: false},
        blobUrls: [],
        _jumpFn: null
    };

    // 当前快捷获取「作品网格」对应的作品类型；纯选择页（关注用户列表 / 珍藏集列表）返回 null。
    // 'mixed' = 珍藏集内可同时含插画与小说，附加筛选时按每件作品自身 kind 判定。
    function quickCurrentKind() {
        const qt = window.PixivBatch.queueTypes;
        if (quickInner.open) {
            if (quickInner.type === 'collection') return 'mixed';
            return qt.resolveType(quickInner.kind);
        }
        // 作品网格视图（viewType 以 -list 结尾的两类）返回其当前网格 kind；纯选择页返回 null。
        if (quickState.viewType === 'illust-list' || quickState.viewType === 'novel-list') {
            return qt.resolveType(quickState.kind);
        }
        return null;
    }

    // 当前快捷获取视图是否在展示「作品网格」（决定附加筛选卡片是否显示）。
    function quickHasWorksGrid() {
        if (state.mode !== QUICK_FETCH_MODE) return false;
        if (quickInner.open) return true;
        return quickState.viewType === 'illust-list' || quickState.viewType === 'novel-list';
    }

    // ---- 取得侧（quick 模式）行为分派：宿主只面向 queueTypes 的 quick 钩子调用，插画为内置默认路径 ----
    // 某作品类型 + item 的队列 id（小说 'n' 前缀等，由该类型 quick 钩子贡献）。
    function quickQueueId(item, kind) {
        const acq = window.PixivBatch.queueTypes.acquisition(kind || quickState.kind, 'quick');
        return acq && acq.queueId ? acq.queueId(item) : String(item.id);
    }
    // 网格卡片元素 id（小说卡 / 插画缩略图 id 前缀不同，由该类型 quick 钩子贡献）。
    function quickGridCardId(kind, idPrefix, idx) {
        const acq = window.PixivBatch.queueTypes.acquisition(kind, 'quick');
        return acq && acq.gridCardId ? acq.gridCardId(idPrefix, idx) : `${idPrefix}-thumb-${idx}`;
    }

    // 解析当前快捷获取视图能映射成的计划任务来源 {type, source, kind, label}；不能则返回 null。
    // 单层来源（收藏 / 我的作品 / 关注新作）展开即可解析；双层来源（关注 / 珍藏集）需先点进具体画师 / 珍藏集。
    // 快捷获取的内置（插画 / 通用）入口动作：action → {viewType, kind?, pageSize?, scheduleType?, scheduleRest?, load}。
    // 各作品类型的专属入口（如小说收藏 / 我的小说）由其 acquisition.quick.actions 贡献，经 quickActionMap 合并进来。
    const QUICK_BUILTIN_ACTIONS = {
        'my-illust-bookmarks-show': {viewType: 'illust-list', kind: 'illust', pageSize: QUICK_PAGE_SIZE_ILLUST, scheduleType: 'MY_BOOKMARKS', scheduleRest: 'show', bookmarkEndpoint: 'illust-bookmarks', load: () => loadQuickIllustBookmarks('show', 1)},
        'my-illust-bookmarks-hide': {viewType: 'illust-list', kind: 'illust', pageSize: QUICK_PAGE_SIZE_ILLUST, scheduleType: 'MY_BOOKMARKS', scheduleRest: 'hide', bookmarkEndpoint: 'illust-bookmarks', load: () => loadQuickIllustBookmarks('hide', 1)},
        'my-illusts': {viewType: 'illust-list', kind: 'illust', pageSize: QUICK_PAGE_SIZE_ILLUST, scheduleType: 'USER_NEW', allIdsFastPath: true, load: () => loadQuickMyWorks('illust', 1)},
        'my-request-artworks': {viewType: 'illust-list', kind: 'illust', pageSize: QUICK_PAGE_SIZE_ILLUST, scheduleType: 'USER_REQUEST', allIdsFastPath: true, load: () => loadQuickMyRequest(1)},
        'my-following-show': {viewType: 'following-list', load: () => loadQuickFollowing('show', 0)},
        'my-following-hide': {viewType: 'following-list', load: () => loadQuickFollowing('hide', 0)},
        'my-following-new': {viewType: 'illust-list', kind: 'illust', pageSize: QUICK_PAGE_SIZE_ILLUST, scheduleType: 'FOLLOW_LATEST', load: () => loadQuickFollowingNew(1)},
        'my-collections': {viewType: 'collection-list', load: () => loadQuickCollections()}
    };

    // 合并「内置动作 + 各可用作品类型贡献的 quick 动作」。某类型不可用 → 其动作缺席（其入口按钮也随 slot 缺席）。
    function quickActionMap() {
        const map = Object.assign({}, QUICK_BUILTIN_ACTIONS);
        window.PixivBatch.queueTypes.acquisitionList('quick').forEach(acq => {
            Object.assign(map, acq.actions || {});
        });
        return map;
    }

    function quickScheduleSource() {
        if (state.mode !== QUICK_FETCH_MODE) return null;
        // 二层钻取：关注画师 → USER_NEW；珍藏集 → COLLECTION（含各类型混合）
        if (quickInner.open) {
            if (quickInner.type === 'following-user' && quickInner.userId) {
                return {
                    type: 'USER_NEW',
                    source: {userId: String(quickInner.userId)},
                    kind: window.PixivBatch.queueTypes.resolveType(quickInner.kind),
                    label: bt('quick.schedule.source.user', '画师 {name}（ID {id}）',
                        {name: quickInner.name || quickInner.userId, id: quickInner.userId})
                };
            }
            if (quickInner.type === 'collection' && quickInner.id) {
                return {
                    type: 'COLLECTION',
                    source: {collectionId: String(quickInner.id)},
                    kind: 'mixed',
                    label: bt('quick.schedule.source.collection', '珍藏集 {name}（ID {id}）',
                        {name: quickInner.name || quickInner.id, id: quickInner.id})
                };
            }
            return null;
        }
        // 外层动作 → 计划来源：分类（scheduleType）与 kind 由动作映射元数据驱动，宿主不再按 action 名写死类型。
        const desc = quickActionMap()[quickState.action];
        if (!desc || !desc.scheduleType) return null;
        const kind = desc.kind || 'illust';
        if (desc.scheduleType === 'MY_BOOKMARKS') {
            const rest = desc.scheduleRest || 'show';
            const kindLabel = bt('schedule.kind.' + kind, kind);
            const restLabel = rest === 'hide'
                ? bt('quick.schedule.rest.hide', '不公开') : bt('quick.schedule.rest.show', '公开');
            return {
                type: 'MY_BOOKMARKS',
                source: {rest},
                kind,
                label: bt('quick.schedule.source.bookmarks', '我的收藏（{kind}，{rest}）',
                    {kind: kindLabel, rest: restLabel})
            };
        }
        if (desc.scheduleType === 'USER_NEW' && quickState.uid) {
            return {
                type: 'USER_NEW',
                source: {userId: String(quickState.uid)},
                kind,
                label: bt('quick.schedule.source.self', '我自己（账号 {uid}）', {uid: quickState.uid})
            };
        }
        if (desc.scheduleType === 'USER_REQUEST' && quickState.uid) {
            return {
                type: 'USER_REQUEST',
                source: {userId: String(quickState.uid)},
                kind: 'illust',
                label: bt('quick.schedule.source.self-request', '我的约稿作品（账号 {uid}）', {uid: quickState.uid})
            };
        }
        if (desc.scheduleType === 'FOLLOW_LATEST') {
            return {
                type: 'FOLLOW_LATEST',
                source: {},
                kind: 'illust',
                label: bt('quick.title.following-new', '已关注的用户的新作')
            };
        }
        return null;
    }

    // 附加筛选预览统计行（与 User 模式同口径）：仅在启用了任一附加筛选时显示「当前页 X / 筛选后 Y / N 个收藏数不可用已排除」。
    function quickFilterSummaryHtml(stats) {
        if (!hasExtraSearchFilter(normalizeSearchFilters(getSearchFiltersFromUI()))) return '';
        const parts = [
            bt('search.summary.current-page', '当前页 {count} 个', {count: stats.rawCount}),
            bt('search.summary.extra-filtered', '附加筛选后 {count} 个', {count: stats.filteredCount})
        ];
        if (stats.bookmarkMetaMissing > 0) {
            parts.push(bt('search.summary.bookmark-missing', '{count} 个收藏数不可用已排除', {count: stats.bookmarkMetaMissing}));
        }
        return `<div class="quick-filter-summary" style="font-size:12px;color:var(--muted,#888);margin-bottom:10px;">`
            + parts.map(p => `<span>${esc(p)}</span>`).join(summarySeparator()) + `</div>`;
    }

    // 混合（珍藏集内多类型）作品的附加筛选：按各作品自身 kind 逐件判定；收藏数筛选时按 kind 分组、各组用自身 kind 补 meta。
    async function quickComputeFilteredMixed(items, filters, isStale) {
        const source = Array.isArray(items) ? items : [];
        const bookmarkFilterActive = hasBookmarkFilter(filters);
        if (bookmarkFilterActive) {
            const byKind = new Map();
            source.forEach(it => {
                const k = it.kind || 'illust';
                if (!byKind.has(k)) byKind.set(k, []);
                byKind.get(k).push(it);
            });
            for (const [k, group] of byKind) {
                await ensureBookmarkMeta(group, k, isStale);
                if (isStale()) return null;
            }
        }
        const stats = {rawCount: source.length, filteredCount: 0, bookmarkMetaMissing: 0, bookmarkFilterActive};
        const filtered = source.filter(item => matchSearchFilters(item, filters, stats, item.kind || 'illust'));
        stats.filteredCount = filtered.length;
        return {filtered, stats};
    }

    // 外层作品网格（收藏 / 我的作品 / 关注新作）：按当前附加筛选过滤 rawItems 后渲染。
    async function quickRenderOuterWorks() {
        const kind = window.PixivBatch.queueTypes.resolveType(quickState.kind);
        const seq = ++quickState.filterSeq;
        const isStale = () => seq !== quickState.filterSeq;
        const filters = normalizeSearchFilters(getSearchFiltersFromUI());
        if (hasBookmarkFilter(filters) && quickState.rawItems.length) {
            const needsMeta = quickState.rawItems.some(it =>
                getInlineSearchBookmarkCount(it) === null && !(getCachedSearchMeta(it.id, kind) || {}).bookmarkResolved);
            if (needsMeta) {
                const area = document.getElementById('quick-preview-area');
                if (area) area.innerHTML = `<div class="search-spinner"><span class="search-spinner-icon"></span>${esc(bt('status.search-reading-bookmarks', '读取当前页收藏数中...'))}</div>`;
            }
        }
        const result = await computeFilteredItems(quickState.rawItems, filters, kind, isStale);
        if (!result) return;
        quickState.items = result.filtered;
        quickState.filterSummary = result.stats;
        const summaryHtml = quickFilterSummaryHtml(result.stats);
        const acq = window.PixivBatch.queueTypes.acquisition(kind, 'quick');
        if (acq && acq.render) acq.render(quickState.items, 'quick', summaryHtml);
        else renderQuickIllustGrid(quickState.items, 'quick', summaryHtml);
    }

    // 内层作品网格（关注画师作品 / 珍藏集内作品）：按当前附加筛选过滤 rawItems 后渲染。
    async function quickApplyInnerFilters() {
        const seq = ++quickInner.filterSeq;
        const isStale = () => seq !== quickInner.filterSeq;
        const filters = normalizeSearchFilters(getSearchFiltersFromUI());
        const mixed = quickInner.type === 'collection';
        if (hasBookmarkFilter(filters) && quickInner.rawItems.length) {
            const area = document.getElementById('quick-inner-area');
            if (area) area.innerHTML = `<div class="search-spinner"><span class="search-spinner-icon"></span>${esc(bt('status.search-reading-bookmarks', '读取当前页收藏数中...'))}</div>`;
        }
        const result = mixed
            ? await quickComputeFilteredMixed(quickInner.rawItems, filters, isStale)
            : await computeFilteredItems(quickInner.rawItems, filters, window.PixivBatch.queueTypes.resolveType(quickInner.kind), isStale);
        if (!result) return;
        quickInner.items = result.filtered;
        quickInner.filterSummary = result.stats;
        renderQuickInnerGrid(quickInner.items, quickFilterSummaryHtml(result.stats));
    }

    // 切换 / 重置附加筛选时，对当前展示的快捷获取作品网格实时重过滤（外层 + 二层钻取都可能在场）。
    async function quickReapplyFilters() {
        if (quickState.viewType === 'illust-list' || quickState.viewType === 'novel-list') {
            await quickRenderOuterWorks();
        }
        if (quickInner.open) {
            await quickApplyInnerFilters();
        }
    }

    function quickHasCookie() {
        return cookieHasPhpsessid();
    }

    function updateQuickAccountBar() {
        const uidEl = document.getElementById('quick-account-uid');
        const hintEl = document.getElementById('quick-account-hint');
        if (!uidEl || !hintEl) return;
        if (!quickHasCookie()) {
            uidEl.textContent = '-';
            quickState.uid = null;
            hintEl.style.display = '';
            hintEl.textContent = bt('quick.account.hint-no-cookie',
                '未检测到登录 Cookie，请先在上方保存含 PHPSESSID 的 Cookie');
            return;
        }
        hintEl.style.display = 'none';
        if (quickState.uid) {
            uidEl.textContent = quickState.uid;
            return;
        }
        // 异步解析；解析失败也只是显示 -
        fetch(`${BASE}/api/pixiv/me/uid`, {headers: pixivHeader()})
            .then(r => r.ok ? r.json() : null)
            .then(j => {
                if (j && j.uid) {
                    quickState.uid = String(j.uid);
                    uidEl.textContent = quickState.uid;
                }
            })
            .catch(() => {});
    }

    function quickResetView() {
        cleanupQuickBlobUrls();
        quickState.renderToken++;
        quickState.viewType = null;
        quickState.rawItems = [];
        quickState.items = [];
        quickState.total = 0;
        quickState.offset = 0;
        quickState.page = 1;
        quickState.allIds = [];
        quickState.followingFilter = '';
        quickState.followingAll = [];
        quickState.followHasNext = false;
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
        document.getElementById('quick-add-page').style.display = opts.showAdd ? '' : 'none';
        document.getElementById('quick-add-all').style.display = opts.showAdd ? '' : 'none';
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

    function setQuickBtnLoading(id, loading) {
        const b = document.getElementById(id);
        if (!b) return;
        b.classList.toggle('is-loading', loading);
        b.disabled = loading;
    }

    async function quickLoad(action) {
        if (!quickHasCookie()) {
            quickRenderEmpty(bt('quick.error.no-cookie', '请先保存含 PHPSESSID 的 Cookie'));
            return;
        }
        // 据动作映射（内置 + 各可用类型贡献）派发；未知 / 不可用类型的动作（如禁用小说后被触发的小说入口）
        // → 不发起任何抓取、给出空态提示（取得侧不产生其专属请求；其入口按钮通常也已随 slot 缺席）。
        const desc = quickActionMap()[action];
        if (!desc) {
            quickRenderEmpty(bt('quick.error.unknown-action', '该入口当前不可用'));
            return;
        }
        quickResetView();
        quickState.action = action;
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
            await desc.load(action);
        } catch (e) {
            quickRenderEmpty(bt('quick.error.load-failed', '加载失败：{message}', {message: e.message || String(e)}));
        } finally {
            setQuickActionLoading(action, false);
        }
    }

    async function quickFetchJson(url) {
        const res = await fetch(url, {headers: pixivHeader()});
        const data = await res.json().catch(() => ({}));
        if (!res.ok || data.error) {
            const msg = data.error || data.message || `HTTP ${res.status}`;
            throw new Error(msg);
        }
        return data;
    }

    async function loadQuickIllustBookmarks(rest, page) {
        const offset = (page - 1) * QUICK_PAGE_SIZE_ILLUST;
        const params = new URLSearchParams({rest, offset: String(offset), limit: String(QUICK_PAGE_SIZE_ILLUST)});
        const data = await quickFetchJson(`${BASE}/api/pixiv/me/illust-bookmarks?${params}`);
        quickState.rawItems = data.items || [];
        quickState.total = data.total || 0;
        quickState.offset = offset;
        quickState.page = page;
        const titleKey = rest === 'hide' ? 'quick.title.illust-bookmarks-hide' : 'quick.title.illust-bookmarks-show';
        const titleFallback = rest === 'hide' ? '我的收藏（插画/漫画，不公开）' : '我的收藏（插画/漫画，公开）';
        quickSetTitle(`${bt(titleKey, titleFallback)} · ${bt('quick.title.count', '{count} 件', {count: quickState.total.toLocaleString()})}`);
        quickShowToolbar({showBack: false, showAdd: quickState.rawItems.length > 0, showSearch: false, showKindSwitcher: false});
        await quickRenderOuterWorks();
        renderQuickPagination(page, Math.max(1, Math.ceil(quickState.total / QUICK_PAGE_SIZE_ILLUST)),
            p => loadQuickIllustBookmarks(rest, p));
        updateExtraFiltersCardVisibility();
        updateSaveScheduleCardVisibility();
        applyNovelSettingsVisibility();
    }

    async function loadQuickMyWorks(kind, page) {
        if (!quickState.uid) {
            const data = await quickFetchJson(`${BASE}/api/pixiv/me/uid`);
            quickState.uid = String(data.uid);
            const uidEl = document.getElementById('quick-account-uid');
            if (uidEl) uidEl.textContent = quickState.uid;
        }
        const uid = quickState.uid;
        // 该类型的 quick 钩子贡献「我的作品」ID 端点 / 卡片端点 / 分页大小 / 标题；插画为内置默认。
        const acq = window.PixivBatch.queueTypes.acquisition(kind, 'quick');
        // 拉全 ID 一次，缓存到 allIds
        if (!quickState.allIds.length || quickState.action.endsWith('-refresh')) {
            const endpoint = (acq && acq.myWorksIdsEndpoint) || 'artworks';
            const data = await quickFetchJson(`${BASE}/api/pixiv/user/${uid}/${endpoint}`);
            quickState.allIds = data.ids || [];
        }
        const pageSize = (acq && acq.pageSize) || QUICK_PAGE_SIZE_ILLUST;
        const total = quickState.allIds.length;
        const totalPages = Math.max(1, Math.ceil(total / pageSize));
        const safePage = Math.min(Math.max(1, page), totalPages);
        const slice = quickState.allIds.slice((safePage - 1) * pageSize, safePage * pageSize);
        let items = [];
        if (slice.length > 0) {
            const cardsEndpoint = (acq && acq.cardsEndpoint) || 'illust-cards';
            const idsQuery = slice.map(id => `ids=${encodeURIComponent(id)}`).join('&');
            const data = await quickFetchJson(`${BASE}/api/pixiv/user/${uid}/${cardsEndpoint}?${idsQuery}`);
            items = data.items || [];
        }
        quickState.kind = window.PixivBatch.queueTypes.resolveType(kind);
        quickState.rawItems = items;
        quickState.total = total;
        quickState.page = safePage;
        quickState.pageSize = pageSize;
        const titleKey = (acq && acq.myWorksTitleKey) || 'quick.title.my-illusts';
        const titleFallback = titleKey;
        quickSetTitle(`${bt(titleKey, titleFallback)} · ${bt('quick.title.count', '{count} 件', {count: total.toLocaleString()})}`);
        quickShowToolbar({showBack: false, showAdd: items.length > 0, showSearch: false, showKindSwitcher: false});
        await quickRenderOuterWorks();
        renderQuickPagination(safePage, totalPages, p => loadQuickMyWorks(kind, p));
        updateExtraFiltersCardVisibility();
        updateSaveScheduleCardVisibility();
        applyNovelSettingsVisibility();
    }

    // 我的约稿作品（账号自身已完成并公开的约稿成品）：先拉全 ID（约稿发现端点）再本地分页取 illust 卡片，渲染同插画。
    async function loadQuickMyRequest(page) {
        if (!quickState.uid) {
            const data = await quickFetchJson(`${BASE}/api/pixiv/me/uid`);
            quickState.uid = String(data.uid);
            const uidEl = document.getElementById('quick-account-uid');
            if (uidEl) uidEl.textContent = quickState.uid;
        }
        const uid = quickState.uid;
        if (!quickState.allIds.length || quickState.action.endsWith('-refresh')) {
            const data = await quickFetchJson(`${BASE}/api/pixiv/user/${uid}/request-artworks`);
            quickState.allIds = data.ids || [];
        }
        const pageSize = QUICK_PAGE_SIZE_ILLUST;
        const total = quickState.allIds.length;
        const totalPages = Math.max(1, Math.ceil(total / pageSize));
        const safePage = Math.min(Math.max(1, page), totalPages);
        const slice = quickState.allIds.slice((safePage - 1) * pageSize, safePage * pageSize);
        let items = [];
        if (slice.length > 0) {
            const idsQuery = slice.map(id => `ids=${encodeURIComponent(id)}`).join('&');
            const data = await quickFetchJson(`${BASE}/api/pixiv/user/${uid}/illust-cards?${idsQuery}`);
            items = data.items || [];
        }
        quickState.kind = 'illust';
        quickState.rawItems = items;
        quickState.total = total;
        quickState.page = safePage;
        quickState.pageSize = pageSize;
        quickSetTitle(`${bt('quick.title.my-request', '我的约稿作品')} · ${bt('quick.title.count', '{count} 件', {count: total.toLocaleString()})}`);
        quickShowToolbar({showBack: false, showAdd: items.length > 0, showSearch: false, showKindSwitcher: false});
        await quickRenderOuterWorks();
        renderQuickPagination(safePage, totalPages, p => loadQuickMyRequest(p));
        updateExtraFiltersCardVisibility();
        updateSaveScheduleCardVisibility();
        applyNovelSettingsVisibility();
    }

    async function loadQuickFollowing(rest, offset) {
        const params = new URLSearchParams({rest, offset: String(offset), limit: String(QUICK_FOLLOWING_PAGE_SIZE)});
        const data = await quickFetchJson(`${BASE}/api/pixiv/me/following?${params}`);
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
            p => loadQuickFollowing(rest, (p - 1) * QUICK_FOLLOWING_PAGE_SIZE));
        // 关注用户列表是纯选择页（无作品卡片）：隐藏附加筛选 / 存为计划任务，待点进某画师后再显示。
        updateExtraFiltersCardVisibility();
        updateSaveScheduleCardVisibility();
        applyNovelSettingsVisibility();
    }

    // 已关注的用户的新作（フォロー新着作品）：插画/漫画/动图卡片，按页翻阅。
    // Pixiv follow_latest 不返回总数，分页仅有 hasNext，故用专用的「上一页/下一页」翻页器。
    async function loadQuickFollowingNew(page) {
        const safePage = Math.max(1, page);
        const params = new URLSearchParams({p: String(safePage)});
        const data = await quickFetchJson(`${BASE}/api/pixiv/me/follow-latest?${params}`);
        quickState.rawItems = data.items || [];
        quickState.followHasNext = !!data.hasNext;
        quickState.page = safePage;
        quickState.kind = 'illust';
        quickState.viewType = 'illust-list';
        quickState.pageSize = QUICK_PAGE_SIZE_ILLUST;
        quickSetTitle(`${bt('quick.title.following-new', '已关注的用户的新作')} · ${bt('quick.title.page', '第 {page} 页', {page: safePage})}`);
        quickShowToolbar({showBack: false, showAdd: quickState.rawItems.length > 0, showSearch: false, showKindSwitcher: false});
        await quickRenderOuterWorks();
        renderQuickFollowNewPagination(safePage, quickState.followHasNext);
        updateExtraFiltersCardVisibility();
        updateSaveScheduleCardVisibility();
        applyNovelSettingsVisibility();
    }

    function renderQuickFollowNewPagination(currentPage, hasNext) {
        const pag = document.getElementById('quick-pagination');
        if (!pag) return;
        const cur = Math.max(1, Number(currentPage || 1));
        if (cur <= 1 && !hasNext) {
            pag.style.display = 'none';
            pag.innerHTML = '';
            return;
        }
        pag.style.display = 'flex';
        quickState._jumpFn = p => loadQuickFollowingNew(p);
        pag.innerHTML =
            `<button onclick="quickJumpPage(1)" ${cur === 1 ? 'disabled' : ''}>&laquo;</button>` +
            `<button onclick="quickJumpPage(${cur - 1})" ${cur === 1 ? 'disabled' : ''}>&lsaquo;</button>` +
            `<button class="pg-active" disabled>${cur}</button>` +
            `<button onclick="quickJumpPage(${cur + 1})" ${hasNext ? '' : 'disabled'}>&rsaquo;</button>` +
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
        const data = await quickFetchJson(`${BASE}/api/pixiv/me/collections`);
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
            const r18Badge = xr === 2 ? '<span class="thumb-badge" style="background:#b91c1c;">R-18G</span>'
                : xr === 1 ? '<span class="thumb-badge">R-18</span>' : '';
            const aiBadge = isAi ? '<span class="thumb-badge thumb-badge-ai">AI</span>' : '';
            const typeBadge = illustType === 2 ? `<span class="thumb-badge" style="background:#0ea5e9;">${esc(bt('search.type.ugoira', '动图'))}</span>`
                : illustType === 1 ? `<span class="thumb-badge" style="background:#f59e0b;">${esc(bt('search.type.manga', '漫画'))}</span>` : '';
            const pagesLabel = item.pageCount > 1 ? `<span class="thumb-pages">${item.pageCount}P</span>` : '';
            const inQueueClass = inQueue.has(String(item.id)) ? ' in-queue' : '';
            const fallbackTitle = bt('queue.artwork-fallback', '作品 {id}', {id: item.id});
            const title = item.title || fallbackTitle;
            return `<div class="search-thumb${inQueueClass}" id="${idPrefix}-thumb-${idx}"
                     onclick="quickToggleItemQueue(${idx})" title="${esc(title)} (${esc(item.userName || '')})">
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
            <div class="quick-following-card" onclick="quickEnterFollowingUser(${idx})"
                 title="${esc(u.userName || u.userId)} (ID: ${esc(u.userId)})">
                <div class="quick-following-avatar" id="quick-follow-ava-${idx}"></div>
                <div class="quick-following-meta">
                    <div class="quick-following-name">${esc(u.userName || u.userId)}</div>
                    <div class="quick-following-uid">ID: ${esc(u.userId)}</div>
                </div>
            </div>
        `).join('')}</div>`;
        // 头像异步加载
        users.forEach((u, idx) => {
            if (!u.profileImageUrl) return;
            const params = new URLSearchParams({url: u.profileImageUrl});
            fetch(`${BASE}/api/pixiv/thumbnail-proxy?${params}`, {headers: pixivHeader()})
                .then(r => r.ok ? r.blob() : null)
                .then(blob => {
                    if (!blob) return;
                    const blobUrl = URL.createObjectURL(blob);
                    quickState.blobUrls.push(blobUrl);
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
            const r18Badge = xr === 2 ? '<span class="thumb-badge" style="background:#b91c1c;">R-18G</span>'
                : xr === 1 ? '<span class="thumb-badge">R-18</span>' : '';
            const bm = Number(c.bookmarkCount ?? 0);
            const bmLine = bm > 0
                ? `<div class="quick-collection-count">${esc(bt('search.summary.bookmark-badge', '收藏 {count}', {count: bm.toLocaleString()}))}</div>`
                : '';
            return `<div class="quick-collection-card" onclick="quickEnterCollection(${idx})" title="${esc(c.title || c.id)}">
                <div class="quick-collection-cover" id="quick-col-cover-${idx}">${r18Badge ? `<div class="thumb-badge-stack">${r18Badge}</div>` : ''}</div>
                <div class="quick-collection-meta">
                    <div class="quick-collection-name">${esc(c.title || c.id)}</div>
                    ${bmLine}
                </div>
            </div>`;
        }).join('')}</div>`;
        collections.forEach((c, idx) => {
            if (!c.coverUrl) return;
            const params = new URLSearchParams({url: c.coverUrl});
            fetch(`${BASE}/api/pixiv/thumbnail-proxy?${params}`, {headers: pixivHeader()})
                .then(r => r.ok ? r.blob() : null)
                .then(blob => {
                    if (!blob) return;
                    const blobUrl = URL.createObjectURL(blob);
                    quickState.blobUrls.push(blobUrl);
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
            `<button onclick="quickJumpPage(1)" ${cur === 1 ? 'disabled' : ''}>&laquo;</button>` +
            `<button onclick="quickJumpPage(${cur - 1})" ${cur === 1 ? 'disabled' : ''}>&lsaquo;</button>` +
            pages.map(p => `<button onclick="${p === cur ? '' : `quickJumpPage(${p})`}" ${p === cur ? 'class="pg-active" disabled' : ''}>${p}</button>`).join('') +
            `<button onclick="quickJumpPage(${cur + 1})" ${cur === totalPages ? 'disabled' : ''}>&rsaquo;</button>` +
            `<button onclick="quickJumpPage(${totalPages})" ${cur === totalPages ? 'disabled' : ''}>&raquo;</button>` +
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
        const blobUrl = await fetchThumbnailBlobUrl(url, quickState.blobUrls);
        if (renderToken !== quickState.renderToken) return;
        if (blobUrl && imgEl.isConnected) imgEl.src = blobUrl;
    }

    function quickToggleItemQueue(idx) {
        const item = quickState.items[idx];
        if (!item) return;
        const id = quickQueueId(item, quickState.kind);
        const existing = state.queue.find(q => q.id === id);
        if (existing) {
            const removed = removeFromQueue(id);
            setStatus(removed
                    ? bt('status.removed-from-queue', '已从队列移除：{title}', {title: item.title || id})
                    : bt('status.cannot-remove-downloading', '无法移除（正在下载中）：{title}', {title: item.title || id}),
                removed ? 'info' : 'warning');
            syncQuickQueueState();
            return;
        }
        const meta = buildQuickQueueMeta(item);
        const added = addItemsToQueue([id], [meta], QUICK_FETCH_MODE, '', meta.authorId, meta.authorName);
        setStatus(added > 0
                ? bt('status.added-to-queue', '已加入队列：{title}', {title: item.title || id})
                : bt('status.already-in-queue', '已在队列中：{title}', {title: item.title || id}),
            added > 0 ? 'success' : 'info');
        syncQuickQueueState();
    }

    function buildQuickQueueMeta(item, kind = quickState.kind) {
        // 队列模型禁止 bake 翻译文案（会被持久化、跨语言切换继续显示旧译）；
        // title 直接存原始值（可为空），渲染时由 queueItemDisplayTitle(q) 派生 fallback。
        // 类型专属队列 meta（如小说 novelId/kind）由该类型 quick 钩子贡献；插画为内置默认。
        const acq = window.PixivBatch.queueTypes.acquisition(kind, 'quick');
        if (acq && acq.buildQueueMeta) return acq.buildQueueMeta(item);
        return {
            title: item.title || '',
            authorId: item.userId ? Number(item.userId) : null,
            authorName: item.userName || '',
            isAi: Number(item.aiType ?? 0) >= 2,
            xRestrict: Number(item.xRestrict ?? 0),
            tags: Array.isArray(item.tags) ? item.tags : []
        };
    }

    function syncQuickQueueState() {
        const inQueue = new Set(state.queue.map(q => q.id));
        // 外层仅在书签 / 我的作品（作品网格）时需要同步；关注 / 珍藏集外层是用户 / 集卡片，无队列态
        if (quickState.viewType === 'illust-list' || quickState.viewType === 'novel-list') {
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
        // 「已关注的用户的新作」无总数，从第 1 页逐页抓取直到 hasNext 为 false
        if (quickState.action === 'my-following-new') {
            if (!uiConfirmKey('quick.confirm.add-all-follow-new',
                '将逐页抓取「已关注的用户的新作」直到没有更多并全部加入队列，请求较多，确认继续？')) return;
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
                    metas.push(buildQuickQueueMeta(item, 'illust'));
                });
            };
            try {
                let page = 1, hasNext = true, guard = 0;
                while (hasNext && guard++ < 500) {
                    setStatus(bt('quick.status.fetching-follow-new',
                        '正在抓取已关注的用户的新作（第 {page} 页，已收集 {count} 个）…',
                        {page, count: ids.length}), 'info');
                    const data = await quickFetchJson(`${BASE}/api/pixiv/me/follow-latest?${new URLSearchParams({p: String(page)})}`);
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
                setStatus(bt('status.fetch-failed', '获取作品列表失败：{message}', {message: e.message}), 'error');
            } finally {
                setQuickBtnLoading('quick-add-all', false);
            }
            return;
        }
        // 可直接按全量 ID 入队的动作（我的作品 / 我的约稿：无须逐页拉 cards），由动作映射的 allIdsFastPath 标记。
        const allDesc = quickActionMap()[quickState.action];
        if (allDesc && allDesc.allIdsFastPath) {
            if (!uiConfirmKey('quick.confirm.add-all-my-works',
                '将把你的全部 {total} 个作品（含 hide）加入队列，确认继续？',
                {total: quickState.allIds.length})) return;
            const acq = window.PixivBatch.queueTypes.acquisition(quickState.kind, 'quick');
            const ids = quickState.allIds.map(id => quickQueueId({id}, quickState.kind));
            // 队列模型禁止 bake 翻译文案；title 留空，渲染时由 queueItemDisplayTitle(q) 派生 fallback。
            // 类型专属裸 id meta（如小说 novelId/kind）由该类型 quick 钩子贡献；插画为空 meta。
            const metas = quickState.allIds.map(id =>
                (acq && acq.buildQueueMetaFromId) ? acq.buildQueueMetaFromId(id) : {});
            const added = addItemsToQueue(ids, metas, QUICK_FETCH_MODE, '', null, '');
            setStatus(
                bt('status.added-many-to-queue', '已将 {added} 个作品加入队列（共 {total} 个，{existing} 个已在队列中）',
                    {added, total: ids.length, existing: ids.length - added}),
                added > 0 ? 'success' : 'info'
            );
            syncQuickQueueState();
            return;
        }
        // 其它（书签 / 珍藏集内 / 关注用户作品）：逐页抓取
        const totalPages = Math.max(1, Math.ceil(quickState.total / quickState.pageSize));
        if (!uiConfirmKey('quick.confirm.add-all-paged',
            '将逐页抓取 {pages} 页（共 {total} 个）并加入队列，请求较多，确认继续？',
            {pages: totalPages, total: quickState.total})) return;
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
            setStatus(bt('status.fetch-failed', '获取作品列表失败：{message}', {message: e.message}), 'error');
        } finally {
            setQuickBtnLoading('quick-add-all', false);
        }
    }

    // 外层「全部加入队列」按页抓取（仅书签外层会用到；关注 / 珍藏集外层无 add-all）。
    async function quickFetchPage(page) {
        const limit = quickState.pageSize;
        const offset = (page - 1) * limit;
        // 收藏类外层动作的逐页抓取：收藏端点（如 illust-bookmarks / novel-bookmarks）由动作映射的 bookmarkEndpoint 提供。
        const desc = quickActionMap()[quickState.action];
        if (desc && desc.bookmarkEndpoint) {
            const rest = desc.scheduleRest || (quickState.action.endsWith('hide') ? 'hide' : 'show');
            const params = new URLSearchParams({rest, offset: String(offset), limit: String(limit)});
            const data = await quickFetchJson(`${BASE}/api/pixiv/me/${desc.bookmarkEndpoint}?${params}`);
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
        quickInner.renderToken++;
        quickInner.items = [];
        quickInner.rawItems = [];
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
            document.querySelectorAll('#quick-inner-kind-switcher label').forEach(l => {
                l.classList.toggle('quick-kind-active', l.dataset.quickKind === opts.kind);
                const input = l.querySelector('input');
                if (input) input.checked = l.dataset.quickKind === opts.kind;
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
            const k = item.kind || 'illust';
            // 混合珍藏集逐件按 item 自身 kind 渲染：类型专属卡（如小说卡）由该类型 quick 钩子贡献，插画为内置卡。
            const acq = window.PixivBatch.queueTypes.acquisition(k, 'quick');
            if (acq && acq.innerCardHtml) return acq.innerCardHtml(item, idx, inQueue);
            const title = item.title || bt('queue.artwork-fallback', '作品 {id}', {id: item.id});
            const xr = Number(item.xRestrict ?? 0);
            const illustType = Number(item.illustType ?? 0);
            const isAi = Number(item.aiType ?? 0) >= 2;
            const r18Badge = xr === 2 ? '<span class="thumb-badge" style="background:#b91c1c;">R-18G</span>'
                : xr === 1 ? '<span class="thumb-badge">R-18</span>' : '';
            const aiBadge = isAi ? '<span class="thumb-badge thumb-badge-ai">AI</span>' : '';
            const typeBadge = illustType === 2 ? `<span class="thumb-badge" style="background:#0ea5e9;">${esc(bt('search.type.ugoira', '动图'))}</span>`
                : illustType === 1 ? `<span class="thumb-badge" style="background:#f59e0b;">${esc(bt('search.type.manga', '漫画'))}</span>` : '';
            const pagesLabel = item.pageCount > 1 ? `<span class="thumb-pages">${item.pageCount}P</span>` : '';
            const inQueueClass = inQueue.has(String(item.id)) ? ' in-queue' : '';
            return `<div class="search-thumb${inQueueClass}" id="quick-inner-card-${idx}"
                     onclick="quickInnerToggleQueue(${idx})" title="${esc(title)} (${esc(item.userName || '')})">
          <img id="quick-inner-img-${idx}" src="" alt="${esc(title)}">
          <div class="thumb-badge-stack">${r18Badge}${aiBadge}${typeBadge}</div>
          ${pagesLabel}
          <span class="thumb-in-queue-mark">✓</span>
          <div class="thumb-title">${esc(title)}</div>
        </div>`;
        }).join('')}</div>`;
        loadQuickInnerThumbnailsBatched(items, renderToken);
    }

    async function loadQuickInnerThumbnailsBatched(items, renderToken) {
        for (let i = 0; i < items.length; i += QUICK_THUMB_BATCH) {
            if (renderToken !== quickInner.renderToken) return;
            const batch = items.slice(i, i + QUICK_THUMB_BATCH);
            await Promise.allSettled(batch.map((item, offset) => loadQuickInnerSingleThumbnail(item, i + offset, renderToken)));
        }
    }

    async function loadQuickInnerSingleThumbnail(item, idx, renderToken) {
        const acq = window.PixivBatch.queueTypes.acquisition(item.kind || 'illust', 'quick');
        if (acq && acq.skipThumbnail) return;   // 该类型在网格中无缩略图（如小说）
        const url = item.thumbnailUrl || item.url;
        if (!url) return;
        const imgEl = document.getElementById(`quick-inner-img-${idx}`);
        if (!imgEl) return;
        const blobUrl = await fetchThumbnailBlobUrl(url, quickInner.blobUrls);
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
            `<button onclick="quickInnerJumpPage(1)" ${cur === 1 ? 'disabled' : ''}>&laquo;</button>` +
            `<button onclick="quickInnerJumpPage(${cur - 1})" ${cur === 1 ? 'disabled' : ''}>&lsaquo;</button>` +
            pages.map(p => `<button onclick="${p === cur ? '' : `quickInnerJumpPage(${p})`}" ${p === cur ? 'class="pg-active" disabled' : ''}>${p}</button>`).join('') +
            `<button onclick="quickInnerJumpPage(${cur + 1})" ${cur === totalPages ? 'disabled' : ''}>&rsaquo;</button>` +
            `<button onclick="quickInnerJumpPage(${totalPages})" ${cur === totalPages ? 'disabled' : ''}>&raquo;</button>` +
            `<span class="pg-info">${esc(bt('search.pagination.info', '第 {current} / {total} 页 · 共 {count} 个',
                {current: cur, total: totalPages, count: quickInner.total.toLocaleString()}))}</span>`;
    }

    function quickInnerJumpPage(p) {
        if (typeof quickInner._jumpFn === 'function') quickInner._jumpFn(p);
    }

    // 珍藏集 → 集内作品（插画+小说混合，一次性返回、无分页）
    async function quickEnterCollection(idx) {
        if (!quickHasCookie()) return;
        const c = quickState.items[idx];
        if (!c) return;
        const cid = String(c.id);
        quickCleanupInnerBlobUrls();
        quickInner.open = true;
        quickInner.type = 'collection';
        quickInner.id = cid;
        quickInner.name = c.title || cid;
        quickHighlightOuterCard(`#quick-preview-area .quick-collection-card:nth-of-type(${idx + 1})`);
        quickShowInnerSection();
        document.getElementById('quick-inner-area').innerHTML = quickLoadingHtml();
        quickShowInnerToolbar({showAdd: false, showKindSwitcher: false});
        try {
            const data = await quickFetchJson(`${BASE}/api/pixiv/me/collection/${cid}/works`);
            quickInner.rawItems = data.works || [];
            quickInner.total = data.total || quickInner.rawItems.length;
            quickShowInnerToolbar({showAdd: quickInner.rawItems.length > 0, showKindSwitcher: false});
            quickSetInnerTitle(`${bt('quick.title.collections', '我的珍藏集')} › ${quickInner.name} · ${bt('quick.title.count', '{count} 件', {count: quickInner.total.toLocaleString()})}`);
            await quickApplyInnerFilters();
            renderQuickInnerPagination(1, 1, () => {});
            // 珍藏集内为混合作品（无单画师来源）：显示附加筛选与小说设置，但不提供「存为计划任务」。
            updateExtraFiltersCardVisibility();
            updateSaveScheduleCardVisibility();
            applyNovelSettingsVisibility();
        } catch (e) {
            document.getElementById('quick-inner-area').innerHTML =
                `<div class="quick-empty">${esc(bt('quick.error.load-failed', '加载失败：{message}', {message: e.message || String(e)}))}</div>`;
        }
    }

    // 关注用户 → 该用户作品（插画/小说切换）
    async function quickEnterFollowingUser(idx) {
        if (!quickHasCookie()) return;
        const u = (quickState.followingRendered || quickState.followingAll)[idx];
        if (!u) return;
        const userId = String(u.userId);
        const userName = u.userName || userId;
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
            const data = await quickFetchJson(`${BASE}/api/pixiv/user/${userId}/artworks`);
            quickInner.allIllustIds = data.ids || [];
            // 第二作品类型（除插画外、第一个可用的 quick 类型，如小说）的作品 ID：仅当该类型可用时抓取，
            // 否则不发起其专属请求（取得侧无新请求）。其入口（内层 kind 单选）也已随 slot 缺席。
            const secondAcq = quickInnerSecondTypeAcq();
            if (secondAcq && secondAcq.userIdsEndpoint) {
                const secondData = await quickFetchJson(`${BASE}/api/pixiv/user/${userId}/${secondAcq.userIdsEndpoint}`);
                quickInner.allNovelIds = secondData.ids || [];
                quickInner.secondKind = secondAcq.type;
            } else {
                quickInner.allNovelIds = [];
                quickInner.secondKind = null;
            }
            quickInner.kind = quickInner.allIllustIds.length > 0
                ? 'illust'
                : (quickInner.allNovelIds.length > 0 ? (quickInner.secondKind || 'illust') : 'illust');
            await loadQuickInnerFollowingUserWorks(quickInner.kind, 1);
        } catch (e) {
            document.getElementById('quick-inner-area').innerHTML =
                `<div class="quick-empty">${esc(bt('quick.error.load-failed', '加载失败：{message}', {message: e.message || String(e)}))}</div>`;
        }
    }

    // 二层关注用户钻取的「第二作品类型」（除插画外、第一个有 userIdsEndpoint 的可用 quick 类型，如小说）。
    function quickInnerSecondTypeAcq() {
        return window.PixivBatch.queueTypes.acquisitionList('quick')
            .find(a => a.type !== 'illust' && a.userIdsEndpoint) || null;
    }

    async function loadQuickInnerFollowingUserWorks(kind, page) {
        // 第一类型恒为内置插画，其余 ID 落第二类型桶；端点 / 分页大小据该 kind 的 quick 钩子。
        const allIds = kind === 'illust' ? quickInner.allIllustIds : quickInner.allNovelIds;
        quickInner.kind = kind;
        quickInner.allIds = allIds;
        const acq = window.PixivBatch.queueTypes.acquisition(kind, 'quick');
        const limit = (acq && acq.pageSize) || QUICK_PAGE_SIZE_ILLUST;
        const totalPages = Math.max(1, Math.ceil(allIds.length / limit));
        const safePage = Math.min(Math.max(1, page), totalPages);
        const slice = allIds.slice((safePage - 1) * limit, safePage * limit);
        let items = [];
        if (slice.length > 0) {
            const endpoint = (acq && acq.cardsEndpoint) || 'illust-cards';
            const idsQuery = slice.map(id => `ids=${encodeURIComponent(id)}`).join('&');
            const data = await quickFetchJson(`${BASE}/api/pixiv/user/${quickInner.userId}/${endpoint}?${idsQuery}`);
            items = data.items || [];
        }
        items.forEach(it => { it.kind = kind; });
        quickInner.rawItems = items;
        quickInner.total = allIds.length;
        quickInner.page = safePage;
        quickInner.pageSize = limit;
        quickShowInnerToolbar({showAdd: items.length > 0, showKindSwitcher: true, kind});
        quickSetInnerTitle(`${bt('quick.preview.parent.following', '我的关注')} › ${quickInner.name} · ${bt('quick.title.count', '{count} 件', {count: allIds.length.toLocaleString()})}`);
        await quickApplyInnerFilters();
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
        const existing = state.queue.find(q => q.id === id);
        if (existing) {
            const removed = removeFromQueue(id);
            setStatus(removed
                    ? bt('status.removed-from-queue', '已从队列移除：{title}', {title: item.title || id})
                    : bt('status.cannot-remove-downloading', '无法移除（正在下载中）：{title}', {title: item.title || id}),
                removed ? 'info' : 'warning');
            syncQuickQueueState();
            return;
        }
        const meta = buildQuickQueueMeta(item, item.kind || quickInner.kind);
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
        if (!quickInner.rawItems.length) return;
        // 珍藏集集内作品一次性全部返回，直接整集入队；关注用户作品按页抓取
        if (quickInner.type === 'collection') {
            // 整集入队全量（未过滤）作品，附加筛选不符者在下载时逐作品跳过。
            const ids = quickInner.rawItems.map(quickInnerQueueId);
            const metas = quickInner.rawItems.map(item => buildQuickQueueMeta(item, item.kind || 'illust'));
            const added = addItemsToQueue(ids, metas, QUICK_FETCH_MODE, '', null, '');
            setStatus(
                bt('status.added-many-to-queue', '已将 {added} 个作品加入队列（共 {total} 个，{existing} 个已在队列中）',
                    {added, total: ids.length, existing: ids.length - added}),
                added > 0 ? 'success' : 'info'
            );
            syncQuickQueueState();
            return;
        }
        const kind = quickInner.kind;
        const totalPages = Math.max(1, Math.ceil(quickInner.total / quickInner.pageSize));
        if (!uiConfirmKey('quick.confirm.add-all-paged',
            '将逐页抓取 {pages} 页（共 {total} 个）并加入队列，请求较多，确认继续？',
            {pages: totalPages, total: quickInner.total})) return;
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
            for (let p = 1; p <= totalPages; p++) {
                if (p === quickInner.page) continue;
                setStatus(bt('status.user-fetch-all-progress', '正在抓取画师作品卡片 {done} / {total}...',
                    {done: ids.length, total: quickInner.total}), 'info');
                acc(await quickFetchInnerPage(p, kind));
            }
            const added = addItemsToQueue(ids, metas, QUICK_FETCH_MODE, '', null, '');
            setStatus(
                bt('status.added-many-to-queue', '已将 {added} 个作品加入队列（共 {total} 个，{existing} 个已在队列中）',
                    {added, total: ids.length, existing: ids.length - added}),
                added > 0 ? 'success' : 'info'
            );
            syncQuickQueueState();
        } catch (e) {
            setStatus(bt('status.fetch-failed', '获取作品列表失败：{message}', {message: e.message}), 'error');
        } finally {
            setQuickBtnLoading('quick-inner-add-all', false);
        }
    }

    // 仅关注用户作品分页抓取（珍藏集无分页，集内全部已加载）
    async function quickFetchInnerPage(page, kind) {
        if (quickInner.type !== 'following-user') return [];
        const limit = quickInner.pageSize;
        const offset = (page - 1) * limit;
        const slice = quickInner.allIds.slice(offset, offset + limit);
        if (!slice.length) return [];
        const acq = window.PixivBatch.queueTypes.acquisition(kind, 'quick');
        const endpoint = (acq && acq.cardsEndpoint) || 'illust-cards';
        const idsQuery = slice.map(id => `ids=${encodeURIComponent(id)}`).join('&');
        const data = await quickFetchJson(`${BASE}/api/pixiv/user/${quickInner.userId}/${endpoint}?${idsQuery}`);
        return data.items || [];
    }

    // 内层 kind 切换（仅关注用户钻取有插画/小说切换）
    document.addEventListener('change', (e) => {
        const target = e.target;
        if (!target || target.name !== 'quick-inner-kind') return;
        if (!quickInner.open || quickInner.type !== 'following-user') return;
        document.querySelectorAll('#quick-inner-kind-switcher label').forEach(l => {
            l.classList.toggle('quick-kind-active', l.dataset.quickKind === target.value);
        });
        loadQuickInnerFollowingUserWorks(target.value, 1);
    });


// ---- PixivBatch facade ----
window.PixivBatch.modes = window.PixivBatch.modes || {};
window.PixivBatch.modes.quick = window.PixivBatch.modes.quick || {};
window.PixivBatch.modes.quick = Object.assign(window.PixivBatch.modes.quick, { quickLoad, quickAddCurrentPageToQueue, quickAddAllToQueue, quickCloseInner, quickInnerAddCurrentPageToQueue, quickInnerAddAllToQueue, quickScheduleSource, quickHasWorksGrid, quickCurrentKind, quickReapplyFilters, syncQuickQueueState });
