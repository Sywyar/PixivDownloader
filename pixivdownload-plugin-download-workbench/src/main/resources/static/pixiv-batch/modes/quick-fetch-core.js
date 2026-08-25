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
        return `<div class="quick-filter-summary preview-summary">`
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
