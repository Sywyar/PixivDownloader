'use strict';
    /* ============================================================
       快捷获取（账户相关作品）
       ============================================================
       账号标识、凭据校验与查询 endpoint 均由当前 quick 类型 owner 贡献。
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
        dataSourceId: null,
        ownerType: null,
        uid: null,
        accountOwner: null,
        accountIdsByOwner: new Map(),
        accountSeq: 0,
        loadSeq: 0,
        viewType: null,   // 'works-list' | 'following-list' | 'collection-list'
        kind: null,       // 当前作品列表的后端类型 id
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
        kind: null,
        idsByType: new Map(),
        userPageStates: new Map(),
        collectionPageState: null,
        allIds: [],        // 当前 kind 对应的全部 ID（following-user 用）
        rawItems: [],      // 当前页未经附加筛选的原始作品（live 预览过滤的事实源）
        items: [],         // 附加筛选后用于渲染 / 入队的作品
        total: 0,
        page: 1,
        pageSize: QUICK_PAGE_SIZE_ILLUST,
        loadSeq: 0,
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
            return qt.resolveTypeForMode(quickInner.kind, 'quick');
        }
        if (quickState.viewType === 'works-list') {
            return qt.resolveTypeForMode(quickState.kind, 'quick');
        }
        return null;
    }

    // 当前快捷获取视图是否在展示「作品网格」（决定附加筛选卡片是否显示）。
    function quickHasWorksGrid() {
        if (state.mode !== QUICK_FETCH_MODE) return false;
        if (quickInner.open) return true;
        return quickState.viewType === 'works-list';
    }

    // ---- 取得侧（quick 模式）行为分派：宿主只面向 queueTypes 的 quick 钩子调用，插画为内置默认路径 ----
    // 某作品类型 + item 的队列 id（小说 'n' 前缀等，由该类型 quick 钩子贡献）。
    function quickQueueId(item, kind) {
        const acq = window.PixivBatch.queueTypes.acquisition(kind || quickState.kind, 'quick');
        return acq.queueId(item);
    }
    // 网格卡片元素 id（小说卡 / 插画缩略图 id 前缀不同，由该类型 quick 钩子贡献）。
    function quickGridCardId(kind, idPrefix, idx) {
        const acq = window.PixivBatch.queueTypes.acquisition(kind, 'quick');
        return acq.gridCardId(idPrefix, idx);
    }

    function quickDataSourceDescriptor(acquisition) {
        const registry = window.PixivBatch.queueTypes;
        const metadata = acquisition && acquisition.dataSource && typeof acquisition.dataSource === 'object'
            ? acquisition.dataSource : {};
        const manifest = registry && typeof registry.manifestDescriptor === 'function'
            ? (registry.manifestDescriptor(acquisition && acquisition.type) || {}) : {};
        const type = acquisition && acquisition.type != null ? String(acquisition.type).trim() : '';
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

    // 多个 quick 类型可以共享同一个数据来源。来源元数据由受控加载的类型模块贡献；
    // 未声明的旧模块按自身 type / display token 降级成独立来源，不需要宿主认识任何平台 id。
    function quickDataSources() {
        const byId = new Map();
        window.PixivBatch.queueTypes.acquisitionList('quick').forEach(acquisition => {
            const candidate = quickDataSourceDescriptor(acquisition);
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
                console.warn('[quick] 同一数据来源的展示元数据不一致，保留先声明的元数据：', candidate.id);
            }
        });
        return Array.from(byId.values())
            .sort((left, right) => (left.order - right.order) || left.id.localeCompare(right.id));
    }

    function quickDataSourceOwnerType(sourceId) {
        const source = quickDataSources().find(item => item.id === sourceId);
        if (!source) return null;
        return source.ownerTypes.find(type => quickAccountAcquisition(type)) || source.ownerTypes[0] || null;
    }

    function quickDataSourceIdForOwnerType(ownerType) {
        const owner = ownerType == null ? '' : String(ownerType).trim();
        if (!owner) return null;
        const source = quickDataSources().find(item => item.ownerTypes.includes(owner));
        return source ? source.id : null;
    }

    function applyQuickDataSourceUi(sources = quickDataSources()) {
        const activeId = quickState.dataSourceId;
        document.querySelectorAll('#quick-data-source-switcher label').forEach(label => {
            const active = label.dataset.quickDataSource === activeId;
            label.classList.toggle('active', active);
            const input = label.querySelector('input[type=radio]');
            if (input) input.checked = active;
        });
        const actions = quickActionMap();
        document.querySelectorAll('.quick-action').forEach(button => {
            const descriptor = actions[button.dataset && button.dataset.quick];
            button.hidden = !descriptor || descriptor.dataSourceId !== activeId;
        });
        const switcher = document.getElementById('quick-data-source-switcher');
        if (switcher) switcher.style.display = sources.length ? '' : 'none';
    }

    function renderQuickDataSourceSwitcher(preserveSelection = false) {
        const switcher = document.getElementById('quick-data-source-switcher');
        if (!switcher) return false;
        const sources = quickDataSources();
        const previousId = quickState.dataSourceId;
        const preserveAcrossLoadingSnapshot = preserveSelection && !sources.length && previousId != null;
        if (!sources.some(source => source.id === previousId) && !preserveAcrossLoadingSnapshot) {
            quickState.dataSourceId = sources.length ? sources[0].id : null;
        }
        switcher.replaceChildren();
        sources.forEach((source, index) => {
            const label = document.createElement('label');
            label.dataset.quickDataSource = source.id;
            label.classList.toggle('active', source.id === quickState.dataSourceId);

            const input = document.createElement('input');
            input.type = 'radio';
            input.name = 'quick-data-source';
            input.value = source.id;
            input.checked = source.id === quickState.dataSourceId;
            input.id = `quick-data-source-${index}`;

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
        applyQuickDataSourceUi(sources);
        return previousId != null && previousId !== quickState.dataSourceId;
    }

    function selectQuickDataSource(sourceId, resetView = true) {
        const requested = sourceId == null ? '' : String(sourceId);
        const sources = quickDataSources();
        if (!sources.some(source => source.id === requested)) return false;
        const changed = quickState.dataSourceId !== requested;
        quickState.dataSourceId = requested;
        applyQuickDataSourceUi(sources);
        if (changed && resetView) {
            quickResetView();
            quickRenderEmpty(bt('quick.preview.empty', '点击上方按钮加载内容'));
            const toolbar = document.getElementById('quick-preview-toolbar');
            if (toolbar) toolbar.style.display = 'none';
            updateExtraFiltersCardVisibility();
            updateSaveScheduleCardVisibility();
            applyNovelSettingsVisibility();
        }
        applyQuickActionCredentialUi();
        if (resetView) {
            updateQuickAccountBar(quickDataSourceOwnerType(requested)).catch(() => undefined);
        }
        return true;
    }

    // 解析当前快捷获取视图能映射成的计划任务来源 {type, source, kind, label}；不能则返回 null。
    // 单层来源（收藏 / 我的作品 / 关注新作）展开即可解析；双层来源（关注 / 珍藏集）需先点进具体画师 / 珍藏集。
    // 所有入口动作都由声明 quick 能力的类型模块贡献。
    function quickActionMap() {
        const map = {};
        window.PixivBatch.queueTypes.acquisitionList('quick').forEach(acq => {
            const dataSource = quickDataSourceDescriptor(acq);
            try {
                Object.keys(acq.actions || {}).forEach(action => {
                    if (Object.prototype.hasOwnProperty.call(map, action)) {
                        console.warn('[quick] 快捷动作 id 冲突，已隔离后注册项：', action, acq.type);
                        return;
                    }
                    map[action] = Object.freeze(Object.assign({}, acq.actions[action], {
                        // ownerType 由 registry 的 canonical acquisition 盖章，模块不能用嵌套字段冒充别的 owner。
                        ownerType: acq.type,
                        dataSourceId: dataSource.id
                    }));
                });
            } catch (e) {
                console.warn('[quick] 快捷动作贡献失败：', acq.type, e);
            }
        });
        return map;
    }

    function quickRequestUrl(spec) {
        if (typeof spec === 'string') return spec;
        if (!spec || typeof spec !== 'object') {
            throw new Error('quick request builder returned no request');
        }
        const endpoint = String(spec.endpoint || '');
        const params = new URLSearchParams();
        Object.entries(spec.params || {}).forEach(([key, value]) => {
            if (Array.isArray(value)) value.forEach(item => params.append(key, item));
            else if (value != null) params.append(key, value);
        });
        return endpoint + (params.toString() ? (endpoint.includes('?') ? '&' : '?') + params : '');
    }

    function currentQuickAction() {
        return quickActionMap()[quickState.action] || null;
    }

    function assertQuickActionContext(context) {
        if (context && typeof context.assertCurrent === 'function') context.assertCurrent();
    }

    function quickActionUserWorkTypes(descriptor = currentQuickAction()) {
        if (!descriptor) return new Set();
        const declared = Array.isArray(descriptor.userWorkTypes)
            ? descriptor.userWorkTypes.map(String).filter(Boolean)
            : [];
        return new Set(declared.length ? declared : [descriptor.ownerType]);
    }

    function quickUserAcquisitionsForAction(descriptor = currentQuickAction()) {
        const allowedTypes = quickActionUserWorkTypes(descriptor);
        return window.PixivBatch.queueTypes.acquisitionList('quick')
            .filter(acq => allowedTypes.has(acq.type)
                && (typeof acq.buildUserPageRequest === 'function'
                    || (typeof acq.buildUserIdsRequest === 'function'
                        && typeof acq.buildCardsRequest === 'function')));
    }

    function quickAccountAcquisition(ownerType) {
        const requested = ownerType == null ? '' : String(ownerType);
        const candidates = window.PixivBatch.queueTypes.acquisitionList('quick')
            .filter(candidate => candidate.account
                && typeof candidate.account.credentialMissing === 'function'
                && typeof candidate.account.buildRequest === 'function'
                && typeof candidate.account.readId === 'function');
        return (requested && candidates.find(candidate => candidate.type === requested))
            || (!requested ? candidates[0] : null) || null;
    }

    function quickActionCredentialState(actionOrDescriptor) {
        const descriptor = typeof actionOrDescriptor === 'string'
            ? quickActionMap()[actionOrDescriptor]
            : actionOrDescriptor;
        if (!descriptor) return {missing: false, ownerType: null, hint: ''};
        const acq = quickAccountAcquisition(descriptor.ownerType);
        if (!acq) return {missing: false, ownerType: descriptor.ownerType, hint: ''};
        try {
            const missing = acq.account.credentialMissing() === true;
            return {
                missing,
                ownerType: acq.type,
                hint: missing
                    ? (typeof acq.account.missingHint === 'function'
                        ? acq.account.missingHint()
                        : bt('quick.account.hint-no-credential', '未检测到可用的登录凭据'))
                    : ''
            };
        } catch (e) {
            console.warn('[quick] 账号凭据状态钩子失败：', acq.type, e);
            return {
                missing: true,
                ownerType: acq.type,
                hint: bt('quick.account.hint-no-credential', '未检测到可用的登录凭据')
            };
        }
    }

    function applyQuickActionCredentialUi() {
        document.querySelectorAll('.quick-action').forEach(button => {
            const credential = quickActionCredentialState(button.dataset && button.dataset.quick);
            const loading = !!(button.classList && typeof button.classList.contains === 'function'
                && button.classList.contains('is-loading'));
            button.disabled = credential.missing || loading;
            button.title = credential.missing ? credential.hint : '';
        });
    }

    function quickScheduleSource() {
        if (state.mode !== QUICK_FETCH_MODE) return null;
        const desc = quickActionMap()[quickState.action];
        if (desc && typeof desc.scheduleSource === 'function') {
            const contributed = desc.scheduleSource({
                uid: quickState.uid,
                kind: quickState.kind,
                action: quickState.action,
                accountOwner: quickState.accountOwner,
                accountId: quickState.uid == null ? null : String(quickState.uid),
                inner: quickInner.open ? {
                    type: quickInner.type,
                    id: quickInner.id == null ? null : String(quickInner.id),
                    userId: quickInner.userId == null ? null : String(quickInner.userId),
                    name: quickInner.name || '',
                    kind: quickInner.kind
                } : null
            });
            if (contributed) return contributed;
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
                const k = window.PixivBatch.queueTypes.resolveTypeForMode(it.kind, 'quick', quickInner.kind);
                if (!k) return;
                if (!byKind.has(k)) byKind.set(k, []);
                byKind.get(k).push(it);
            });
            for (const [k, group] of byKind) {
                await ensureBookmarkMeta(group, k, isStale);
                if (isStale()) return null;
            }
        }
        const stats = {rawCount: source.length, filteredCount: 0, bookmarkMetaMissing: 0, bookmarkFilterActive};
        const filtered = source.filter(item => {
            const kind = window.PixivBatch.queueTypes.resolveTypeForMode(item.kind, 'quick', quickInner.kind);
            return !!kind && matchSearchFilters(item, filters, stats, kind);
        });
        stats.filteredCount = filtered.length;
        return {filtered, stats};
    }

    // 外层作品网格（收藏 / 我的作品 / 关注新作）：按当前附加筛选过滤 rawItems 后渲染。
    async function quickRenderOuterWorks() {
        const kind = window.PixivBatch.queueTypes.resolveTypeForMode(quickState.kind, 'quick');
        if (!kind) return;
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
        acq.render(quickState.items, 'quick', summaryHtml);
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
            : await computeFilteredItems(quickInner.rawItems, filters,
                window.PixivBatch.queueTypes.resolveTypeForMode(quickInner.kind, 'quick'), isStale);
        if (!result) return;
        quickInner.items = result.filtered;
        quickInner.filterSummary = result.stats;
        renderQuickInnerGrid(quickInner.items, quickFilterSummaryHtml(result.stats));
    }

    // 切换 / 重置附加筛选时，对当前展示的快捷获取作品网格实时重过滤（外层 + 二层钻取都可能在场）。
    async function quickReapplyFilters() {
        if (quickState.viewType === 'works-list') {
            await quickRenderOuterWorks();
        }
        if (quickInner.open) {
            await quickApplyInnerFilters();
        }
    }

    function invalidateQuickAccount(ownerType) {
        const owner = ownerType == null ? '' : String(ownerType);
        if (!owner) return;
        quickState.accountIdsByOwner.delete(owner);
        if (quickState.accountOwner !== owner) return;
        quickState.accountSeq++;
        quickState.uid = null;
        const uidEl = document.getElementById('quick-account-uid');
        if (uidEl) uidEl.textContent = '-';
    }

    async function updateQuickAccountBar(ownerType) {
        const uidEl = document.getElementById('quick-account-uid');
        const hintEl = document.getElementById('quick-account-hint');
        if (!uidEl || !hintEl) return;
        const explicitOwner = ownerType == null ? '' : String(ownerType).trim();
        const explicitSourceId = quickDataSourceIdForOwnerType(explicitOwner);
        // 插件配置事件可能刷新自己的账号缓存；非当前来源不得借此覆盖当前账号栏。
        if (explicitOwner && quickState.dataSourceId && explicitSourceId !== quickState.dataSourceId) return;
        const seq = ++quickState.accountSeq;
        const requestedOwner = explicitOwner || quickState.ownerType
            || (currentQuickAction() && currentQuickAction().ownerType)
            || quickDataSourceOwnerType(quickState.dataSourceId) || null;
        const acq = quickAccountAcquisition(requestedOwner);
        if (!acq) {
            uidEl.textContent = '-';
            quickState.uid = null;
            quickState.accountOwner = null;
            hintEl.style.display = '';
            hintEl.textContent = bt('quick.account.hint-unavailable', '当前没有可用的账号数据源');
            return;
        }
        let missing = true;
        try {
            missing = acq.account.credentialMissing() === true;
        } catch (e) {
            console.warn('[quick] 账号凭据状态钩子失败：', acq.type, e);
        }
        if (missing) {
            uidEl.textContent = '-';
            quickState.uid = null;
            quickState.accountOwner = acq.type;
            quickState.accountIdsByOwner.delete(acq.type);
            hintEl.style.display = '';
            hintEl.textContent = typeof acq.account.missingHint === 'function'
                ? acq.account.missingHint()
                : bt('quick.account.hint-no-credential', '未检测到可用的登录凭据');
            return;
        }
        hintEl.style.display = 'none';
        const cachedAccountId = quickState.accountIdsByOwner.get(acq.type);
        if (cachedAccountId) {
            quickState.uid = cachedAccountId;
            quickState.accountOwner = acq.type;
            uidEl.textContent = cachedAccountId;
            return;
        }
        quickState.uid = null;
        quickState.accountOwner = acq.type;
        uidEl.textContent = '-';
        let request = null;
        try {
            const spec = acq.account.buildRequest();
            request = window.PixivBatch.queueTypes.prepareAcquisitionRequest(
                acq.type, 'quick', quickRequestUrl(spec), 'account', {});
            const response = await fetch(request.url, request.init);
            const data = await response.json().catch(() => ({}));
            request.assertCurrent();
            if (seq !== quickState.accountSeq || !response.ok) return;
            const accountId = acq.account.readId(data);
            if (!accountId) return;
            quickState.uid = String(accountId);
            quickState.accountOwner = acq.type;
            quickState.accountIdsByOwner.set(acq.type, quickState.uid);
            uidEl.textContent = quickState.uid;
        } catch (e) {
            // 账号栏是 best-effort；发布已更换或请求失败时保持占位。
            if (request && !request.isCurrent()) return;
            if (seq === quickState.accountSeq) uidEl.textContent = '-';
        }
    }

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

    function reconcileQuickTypeAvailability(ready = true) {
        const registry = window.PixivBatch.queueTypes;
        const sourceChanged = renderQuickDataSourceSwitcher(ready === false);
        const outerOwnerType = quickState.ownerType || quickState.kind;
        const outerStale = outerOwnerType && !registry.supports(outerOwnerType, 'quick');
        const innerStale = quickInner.kind && !registry.supports(quickInner.kind, 'quick');
        if (!outerStale && !innerStale && !sourceChanged) return false;
        quickResetView();
        quickState.accountSeq++;
        quickState.uid = null;
        quickState.accountOwner = null;
        quickRenderEmpty(ready !== false && (outerStale || innerStale)
            ? bt('queue.message.type-unavailable', '该类型当前不可用（其插件已禁用），已暂停')
            : bt('quick.preview.empty', '点击上方按钮加载内容'));
        const toolbar = document.getElementById('quick-preview-toolbar');
        if (toolbar) toolbar.style.display = 'none';
        ['quick-add-page', 'quick-add-all'].forEach(id => {
            const button = document.getElementById(id);
            if (!button) return;
            button.style.display = 'none';
            button.disabled = true;
        });
        updateExtraFiltersCardVisibility();
        updateSaveScheduleCardVisibility();
        return true;
    }


// ---- PixivBatch facade ----
window.PixivBatch.modes = window.PixivBatch.modes || {};
window.PixivBatch.modes.quick = window.PixivBatch.modes.quick || {};
window.PixivBatch.modes.quick = Object.assign(window.PixivBatch.modes.quick, { quickLoad, quickAddCurrentPageToQueue, quickAddAllToQueue, quickCloseInner, quickInnerAddCurrentPageToQueue, quickInnerAddAllToQueue, quickScheduleSource, quickHasWorksGrid, quickCurrentKind, quickReapplyFilters, syncQuickQueueState, renderQuickDataSourceSwitcher, selectQuickDataSource, reconcileQuickTypeAvailability, invalidateQuickAccount });
