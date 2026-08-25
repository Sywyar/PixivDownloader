'use strict';

/* ============================================================
   快捷获取模式
   ============================================================ */
const QUICK_PAGE_SIZE = 12;

const QUICK_ACTIONS = [
    {id: 'my-illust-bookmarks-show', icon: 'bookmark', labelKey: 'quick.action.bookmarks-show', label: '我的收藏（公开）', view: 'works'},
    {id: 'my-illust-bookmarks-hide', icon: 'bookmark', labelKey: 'quick.action.bookmarks-hide', label: '我的收藏（不公开）', view: 'works'},
    {id: 'my-illusts', icon: 'image', labelKey: 'quick.action.my-works', label: '我自己的作品', view: 'works'},
    {id: 'my-request-artworks', icon: 'send', labelKey: 'quick.action.my-requests', label: '我的约稿作品', view: 'works'},
    {id: 'my-following-show', icon: 'users', labelKey: 'quick.action.following-show', label: '我的关注（公开）', view: 'following'},
    {id: 'my-following-hide', icon: 'users', labelKey: 'quick.action.following-hide', label: '我的关注（不公开）', view: 'following'},
    {id: 'my-following-new', icon: 'refresh', labelKey: 'quick.action.follow-latest', label: '关注用户的新作', view: 'works'},
    {id: 'my-collections', icon: 'folder', labelKey: 'quick.action.collections', label: '我的珍藏集', view: 'collections'}
];

function quickAcquisition() {
    return altAcquisition('quick', quickState.source, quickState.kind);
}

function quickActionDefs() {
    if (quickState.source === 'pixiv' && quickState.kind === 'illust') return QUICK_ACTIONS;
    const acquisition = quickAcquisition();
    if (!acquisition) return [];
    return Object.entries(acquisition.actions || {}).map(([id, descriptor]) => {
        const labelNamespace = descriptor.labelNamespace
            || (acquisition.dataSource && acquisition.dataSource.displayNamespace) || '';
        const labelKey = descriptor.labelI18nKey || ('quick.action.' + id);
        return {
            id,
            icon: descriptor.iconKey
                || (descriptor.viewType === 'collection-list' ? 'folder' : 'bookmark'),
            labelKey: (labelNamespace ? labelNamespace + ':' : '') + labelKey,
            label: descriptor.label || id,
            view: descriptor.viewType === 'following-list' ? 'following'
                : descriptor.viewType === 'collection-list' ? 'collections' : 'works',
            descriptor
        };
    });
}

function renderQuickMode(panel) {
    const def = AB_MODES[0];
    panel.appendChild(modeHeader(def, [filterButton(), settingsButton(), saveScheduleButton()].filter(Boolean)));

    const sources = altSourcesForMode('quick');
    if (!sources.some(source => source.id === quickState.source)) quickState.source = sources[0] && sources[0].id;
    if (sources.length > 1) {
        panel.appendChild(sourceChips(sources, quickState.source, source => {
            quickState.source = source;
            quickState.kind = (altTypesForSource('quick', source)[0] || {}).type || 'illust';
            quickState.action = null;
            renderStage();
        }));
    }
    const typeOptions = altTypesForSource('quick', quickState.source)
        .map(item => [item.type, altTypeLabel(item.type)]);
    if (!typeOptions.some(option => option[0] === quickState.kind)) {
        quickState.kind = typeOptions[0] ? typeOptions[0][0] : 'illust';
    }
    if (typeOptions.length > 1) {
        panel.appendChild(smallSeg(typeOptions, quickState.kind, kind => {
            quickState.kind = kind;
            quickState.action = null;
            renderStage();
        }));
    }

    const acquisition = quickAcquisition();
    const credentialOk = !(acquisition && acquisition.account
        && typeof acquisition.account.credentialMissing === 'function'
        && acquisition.account.credentialMissing());
    const accountCard = el('div', 'ab-account card');
    const uidLine = el('div', 'ab-account-uid');
    uidLine.appendChild(abIconEl('user'));
    uidLine.appendChild(el('span', 'ab-muted', bt('quick.uid', '当前账号 UID')));
    const uidValue = el('strong', '', quickState.uid || '-');
    uidValue.id = 'abQuickUid';
    uidLine.appendChild(uidValue);
    accountCard.appendChild(uidLine);
    if (!credentialOk) {
        const warn = el('div', 'ab-account-warn');
        warn.appendChild(abIconEl('alert'));
        warn.appendChild(el('span', '', bt('quick.no-credential', '未检测到可用的登录凭据，请先保存含 PHPSESSID 的 Cookie')));
        const fix = el('button', 'ab-btn ab-btn--ghost ab-btn--sm', bt('cookie.title', 'Pixiv Cookie'));
        fix.type = 'button';
        fix.addEventListener('click', openCookieModal);
        warn.appendChild(fix);
        accountCard.appendChild(warn);
    }
    panel.appendChild(accountCard);

    const actions = el('div', 'ab-quick-actions');
    quickActionDefs().forEach((action, idx) => {
        const btn = el('button', 'ab-quick-action card'
            + (quickState.action === action.id ? ' is-active' : ''));
        btn.type = 'button';
        btn.style.setProperty('--stagger', String(idx));
        btn.disabled = !credentialOk;
        if (!credentialOk) btn.title = bt('cookie.requires-phpsessid', '无有效cookie(PHPSESSID)此功能不可用');
        btn.appendChild(abIconEl(action.icon));
        btn.appendChild(el('span', '', bt(action.labelKey, action.label)));
        btn.addEventListener('click', () => runQuickAction(action));
        actions.appendChild(btn);
    });
    panel.appendChild(actions);

    const stage = el('div', 'ab-quick-stage');
    stage.id = 'abQuickStage';
    panel.appendChild(stage);
    renderQuickStage();

    if (credentialOk && !quickState.uid) loadQuickUid();
}

async function loadQuickUid() {
    try {
        const acquisition = quickAcquisition();
        if (!acquisition || !acquisition.account) {
            throw new Error(bt('quick.error.account-unavailable', '当前来源不支持账号获取'));
        }
        const data = await altAcquisitionJson(acquisition.type, 'quick',
            acquisition.account.buildRequest(), 'account', {});
        const value = acquisition.account.readId(data);
        quickState.uid = value == null ? null : String(value);
    } catch {
        quickState.uid = null;
    }
    const node = document.getElementById('abQuickUid');
    if (node) node.textContent = quickState.uid || '-';
}

function refreshQuickCredentialGate() {
    if (state.mode === QUICK_FETCH_MODE) renderStage();
}

function quickActionDef(id) {
    const actions = quickActionDefs();
    return actions.find(a => a.id === id) || actions[0];
}

function runQuickAction(action, page) {
    quickState.action = action.id;
    quickState.drill = null;
    document.querySelectorAll('.ab-quick-action').forEach(btn => btn.classList.remove('is-active'));
    renderStagePreserve(() => {
        document.querySelectorAll('.ab-quick-action').forEach(btn => {
            const label = btn.querySelector('span:last-child');
            if (label && label.textContent === bt(action.labelKey, action.label)) btn.classList.add('is-active');
        });
    });
    if (action.view === 'works') loadQuickWorks(action, page || 1);
    else if (action.view === 'following') loadQuickFollowing(action, 0);
    else loadQuickCollections(action);
}

// 仅重绘舞台区，保留头部 / 动作按钮（避免闪烁）
function renderStagePreserve(after) {
    const stage = document.getElementById('abQuickStage');
    if (stage) stage.innerHTML = '';
    if (typeof after === 'function') after();
}

function quickRest(action) {
    return action.id.endsWith('-hide') ? 'hide' : 'show';
}

async function loadQuickWorks(action, page) {
    const stage = document.getElementById('abQuickStage');
    if (!stage) return;
    quickState.loading = true;
    quickState.error = '';
    stage.innerHTML = '';
    stage.appendChild(loadingGrid(bt('common.loading', '加载中…')));
    try {
        let items, total, hasNext = false, totalPages = 1;
        if (action.descriptor) {
            const acquisition = quickAcquisition();
            const descriptor = action.descriptor;
            const limit = Math.max(1, Number(descriptor.pageSize || acquisition.pageSize) || 24);
            const cursor = page === 1 ? descriptor.initialCursor ?? acquisition.initialCursor ?? null
                : quickState.pageCursors && quickState.pageCursors.get(page);
            const context = {
                action: action.id, page, offset: (page - 1) * limit, limit, cursor,
                rest: quickRest(action), uid: quickState.uid,
                accountId: quickState.uid, accountOwner: quickState.source
            };
            if (descriptor.allIdsFastPath) {
                if (!quickState.uid) await loadQuickUid();
                if (!quickState.uid) throw new Error(bt('quick.error.no-uid', '无法解析当前账号'));
                if (page === 1 || !quickState.allIds.length) {
                    const idsData = await altAcquisitionJson(acquisition.type, 'quick',
                        acquisition.buildMyWorksIdsRequest(quickState.uid), 'ids', context);
                    quickState.allIds = (idsData.ids || []).map(String);
                }
                total = quickState.allIds.length;
                const pageIds = quickState.allIds.slice((page - 1) * limit, page * limit);
                const data = await altAcquisitionJson(acquisition.type, 'quick',
                    acquisition.buildCardsRequest(quickState.uid, pageIds), 'cards', context);
                items = normalizeAcquisitionItems(data.items || [], acquisition, context, 'quick');
                totalPages = Math.max(1, Math.ceil(total / limit));
            } else {
                const data = await altAcquisitionJson(acquisition.type, 'quick',
                    descriptor.buildPageRequest(context), 'page', context);
                const raw = data.items || data.works || [];
                items = normalizeAcquisitionItems(raw, acquisition, context, 'quick');
                total = Number(data.total || items.length);
                hasNext = !!(data.hasNext || data.hasMore);
                if (page === 1) quickState.pageCursors = new Map();
                if (hasNext && descriptor.cursorPaging) {
                    quickState.pageCursors.set(page + 1, altNextCursor(data, cursor, true));
                }
                totalPages = total > 0 ? Math.max(1, Math.ceil(total / limit)) : 0;
            }
        } else if (action.id === 'my-illust-bookmarks-show' || action.id === 'my-illust-bookmarks-hide') {
            const limit = 48;
            const data = await pixivJson(`/api/pixiv/me/illust-bookmarks?rest=${quickRest(action)}&offset=${(page - 1) * limit}&limit=${limit}`);
            items = data.items || [];
            total = Number(data.total || items.length);
            totalPages = Math.max(1, Math.ceil(total / limit));
        } else if (action.id === 'my-following-new') {
            const data = await pixivJson(`/api/pixiv/me/follow-latest?p=${page}`);
            items = data.items || [];
            total = 0;
            hasNext = !!data.hasNext;
        } else {
            // 我的作品 / 约稿：先取全部 ID，再分页取卡片
            if (!quickState.uid) await loadQuickUid();
            if (!quickState.uid) throw new Error(bt('quick.error.no-uid', '无法解析当前账号'));
            const endpoint = action.id === 'my-request-artworks' ? 'request-artworks' : 'artworks';
            const idsData = await pixivJson(`/api/pixiv/user/${encodeURIComponent(quickState.uid)}/${endpoint}`);
            const ids = (idsData.ids || []).map(String);
            total = ids.length;
            totalPages = Math.max(1, Math.ceil(total / QUICK_PAGE_SIZE));
            const pageIds = ids.slice((page - 1) * QUICK_PAGE_SIZE, page * QUICK_PAGE_SIZE);
            const cards = await fetchIllustCards(quickState.uid, pageIds);
            items = cards.items || [];
        }
        quickState.items = items;
        quickState.rawItems = items;
        quickState.page = page;
        quickState.total = total;
        quickState.totalPages = totalPages;
        quickState.hasNext = hasNext;
        quickState.loading = false;
        quickState.error = '';
    } catch (e) {
        quickState.items = [];
        quickState.rawItems = [];
        quickState.page = page;
        quickState.total = 0;
        quickState.totalPages = 1;
        quickState.hasNext = false;
        quickState.loading = false;
        quickState.error = String(e && e.message || bt('common.request-failed', '请求失败'));
    }
    await applyQuickFilters();
}

async function applyQuickFilters() {
    const stage = document.getElementById('abQuickStage');
    if (!stage) return;
    const seq = ++searchState.filterSeq;
    const result = await computeFilteredItems(quickState.rawItems, extraFilters, quickState.kind,
        () => seq !== searchState.filterSeq);
    if (!result) return;
    quickState.items = result.filtered;
    quickState.filterSummary = result.stats;
    renderQuickStage();
}

function quickFilterSummaryText() {
    const stats = quickState.filterSummary;
    if (!stats || !hasExtraSearchFilter()) return '';
    const parts = [bt('quick.filter-summary', '{label} 筛后 {count} 个',
        {label: bt('filters.title', '附加筛选'), count: stats.filteredCount})];
    if (stats.bookmarkMetaMissing > 0) {
        parts.push(bt('search.summary.bookmark-missing', '{count} 个收藏数不可用已排除',
            {count: stats.bookmarkMetaMissing}));
    }
    return parts.join(' · ');
}

function renderQuickStage() {
    const stage = document.getElementById('abQuickStage');
    if (!stage) return;
    stage.innerHTML = '';
    const action = quickActionDef(quickState.action);
    if (!quickState.action) {
        const hint = el('div', 'ab-empty ab-empty--tall');
        hint.appendChild(abIconEl('zap'));
        hint.appendChild(el('p', '', bt('quick.hint', '选择上方任一快捷动作开始取作品')));
        stage.appendChild(hint);
        return;
    }
    if (quickState.loading) {
        stage.appendChild(loadingGrid(bt('common.loading', '加载中…')));
        return;
    }

    // 标题行：来源名称 + 数量 + 页码（钻取时带「› 名称」）
    const titleRow = el('div', 'ab-stage-title');
    let titleText = bt(action.labelKey, action.label);
    if (quickState.drill && quickState.drill.name) {
        titleText += ' › ' + quickState.drill.name;
    }
    titleRow.appendChild(el('h2', 'ab-stage-heading', titleText));
    if (quickState.total > 0) {
        titleRow.appendChild(el('span', 'ab-stage-count',
            bt('quick.stage.count', '{count} 件', {count: quickState.total})));
    }
    stage.appendChild(titleRow);

    if (quickState.error) {
        stage.appendChild(errorBox(quickState.error, () => {
            if (action.view === 'following') loadQuickFollowing(action, quickState.usersOffset || 0);
            else if (action.view === 'collections') loadQuickCollections(action);
            else loadQuickWorks(action, quickState.page || 1);
        }));
        return;
    }

    if (action.view === 'following') {
        renderQuickFollowing(stage, action);
    } else if (action.view === 'collections') {
        renderQuickCollections(stage);
    } else {
        renderQuickWorks(stage, action);
    }
    if (quickState.drill) renderQuickDrill(stage);
    syncAllResultsQueueState();
}

function renderQuickWorks(stage, action) {
    stage.appendChild(enqueueBar({
        summary: quickState.total
            ? bt('quick.stage.summary', '第 {page} 页 · 共 {count} 个', {page: quickState.page, count: quickState.total || quickState.items.length})
            : bt('common.page.simple', '第 {page} 页', {page: quickState.page}),
        filterSummary: quickFilterSummaryText(),
        pageEnabled: quickState.items.length > 0,
        allEnabled: quickState.items.length > 0,
        onEnqueuePage: () => enqueueItems(quickState.items, null, {source: QUICK_FETCH_MODE}),
        onEnqueueAll: () => enqueueQuickAll(action)
    }));
    stage.appendChild(worksGrid(quickState.items, {
        source: QUICK_FETCH_MODE,
        emptyText: bt('quick.empty.works', '该范围内没有作品')
    }));
    stage.appendChild(paginationBar({
        page: quickState.page,
        totalPages: action.id === 'my-following-new' ? 0 : quickState.totalPages,
        total: quickState.total,
        hasNext: quickState.hasNext,
        onPage: p => loadQuickWorks(action, p)
    }));
}

async function enqueueQuickAll(action) {
    if (!await abConfirm('quick.enqueue-all.confirm',
        '将全部 {count} 个作品加入队列？需要逐页抓取约 {pages} 页，会产生较多请求。',
        {count: quickState.total || quickState.items.length, pages: quickState.totalPages || 1})) return;
    if (action.descriptor) {
        const acquisition = quickAcquisition();
        const descriptor = action.descriptor;
        if (descriptor.allIdsFastPath) {
            try {
                if (!quickState.uid) await loadQuickUid();
                if (!quickState.uid) throw new Error(bt('quick.error.no-uid', '无法解析当前账号'));
                const context = {action: action.id, uid: quickState.uid,
                    accountId: quickState.uid, accountOwner: quickState.source};
                if (!quickState.allIds.length) {
                    const data = await altAcquisitionJson(acquisition.type, 'quick',
                        acquisition.buildMyWorksIdsRequest(quickState.uid), 'ids', context);
                    quickState.allIds = (data.ids || []).map(String);
                }
                const items = quickState.allIds.map(id => {
                    const owned = acquisition.buildQueueMetaFromId
                        ? acquisition.buildQueueMetaFromId(id, context) : {};
                    const queueId = acquisition.queueId ? acquisition.queueId({id}) : id;
                    return Object.assign({id: String(queueId), kind: acquisition.type}, owned || {}, {
                        __queueMeta: owned || {}
                    });
                });
                const added = enqueueItems(items, null, {source: QUICK_FETCH_MODE, silent: true});
                abToast('success', bt('queue.toast.batch-added', '已批量加入 {count} 个作品', {count: added}));
            } catch (e) {
                abToast('error', String(e && e.message || bt('common.request-failed', '请求失败')));
            }
            return;
        }
        const limit = Math.max(1, Number(descriptor.pageSize || acquisition.pageSize) || 24);
        const all = [];
        let cursor = descriptor.initialCursor ?? acquisition.initialCursor ?? null;
        for (let page = 1; page <= 200; page++) {
            const context = {
                action: action.id, page, offset: (page - 1) * limit, limit, cursor,
                rest: quickRest(action), uid: quickState.uid,
                accountId: quickState.uid, accountOwner: quickState.source
            };
            let data;
            try {
                data = await altAcquisitionJson(acquisition.type, 'quick',
                    descriptor.buildPageRequest(context), 'page', context);
            } catch (e) {
                abToast('error', String(e && e.message || bt('common.request-failed', '请求失败')));
                return;
            }
            all.push(...normalizeAcquisitionItems(data.items || data.works || [], acquisition, context, 'quick'));
            const hasMore = !!(data.hasNext || data.hasMore);
            if (!hasMore || !(data.items || data.works || []).length) break;
            // ponytail: 防止失控循环；仅在真实账号数超过已验证上限时再提高此值。
            if (page === 200) {
                abToast('error', bt('pagination.error.page-limit', '分页数量超出安全上限，未加入不完整结果'));
                return;
            }
            if (descriptor.cursorPaging) cursor = altNextCursor(data, cursor, hasMore);
        }
        const added = enqueueItems(all, null, {source: QUICK_FETCH_MODE, silent: true});
        abToast('success', bt('queue.toast.batch-added', '已批量加入 {count} 个作品', {count: added}));
        return;
    }
    if (action.id === 'my-following-new' || action.id.startsWith('my-illust-bookmarks')) {
        // 书签 / 新作：逐页抓取直到没有下一页。
        const all = [];
        if (action.id.startsWith('my-illust-bookmarks')) {
            const limit = 100;
            let offset = 0;
            for (; ;) {
                const data = await pixivJson(`/api/pixiv/me/illust-bookmarks?rest=${quickRest(action)}&offset=${offset}&limit=${limit}`);
                const items = data.items || [];
                all.push(...items);
                offset += items.length;
                if (items.length < limit || offset >= Number(data.total || 0)) break;
            }
        } else {
            let page = 1;
            for (; ;) {
                const data = await pixivJson(`/api/pixiv/me/follow-latest?p=${page}`);
                const items = data.items || [];
                all.push(...items);
                if (!data.hasNext) break;
                page++;
                if (page > 200) break;   // 分页护栏：超过安全页数即停止
            }
        }
        enqueueItems(all, 'illust', {source: QUICK_FETCH_MODE, silent: true});
        const added = all.length;
        abToast('success', bt('queue.toast.batch-added', '已批量加入 {count} 个作品', {count: added}));
    } else {
        // 我的作品 / 约稿：ID 全量已在服务端，直接逐页取卡片入队
        try {
            const endpoint = action.id === 'my-request-artworks' ? 'request-artworks' : 'artworks';
            const idsData = await pixivJson(`/api/pixiv/user/${encodeURIComponent(quickState.uid)}/${endpoint}`);
            const ids = (idsData.ids || []).map(String);
            const metas = ids.map(id => buildQueueMeta({id}, 'illust', {}));
            const added = addItemsToQueue(ids, metas, QUICK_FETCH_MODE);
            abToast('success', bt('queue.toast.batch-added', '已批量加入 {count} 个作品', {count: added}));
        } catch (e) {
            abToast('error', String(e && e.message || bt('common.request-failed', '请求失败')));
        }
    }
    syncAllResultsQueueState();
}

async function loadQuickFollowing(action, offset) {
    const stage = document.getElementById('abQuickStage');
    if (!stage) return;
    quickState.loading = true;
    quickState.error = '';
    renderQuickStage();
    try {
        const limit = 24;
        const data = await pixivJson(`/api/pixiv/me/following?rest=${quickRest(action)}&offset=${offset}&limit=${limit}`);
        quickState.users = data.users || [];
        quickState.usersTotal = Number(data.total || quickState.users.length);
        quickState.usersOffset = offset;
    } catch (e) {
        quickState.users = [];
        quickState.usersTotal = 0;
        quickState.usersOffset = offset;
        quickState.error = String(e && e.message || bt('common.request-failed', '请求失败'));
    }
    quickState.loading = false;
    renderQuickStage();
}

function renderQuickFollowing(stage, action) {
    const filterRow = el('div', 'ab-follow-filter');
    const input = el('input', 'ab-input');
    input.type = 'search';
    input.placeholder = bt('quick.follow.filter', '按用户名 / 用户 ID 过滤');
    input.value = quickState.usersFilter || '';
    input.addEventListener('input', debounce(() => {
        quickState.usersFilter = input.value.trim();
        renderQuickStage();
    }, 200));
    filterRow.appendChild(input);
    stage.appendChild(filterRow);

    const term = (quickState.usersFilter || '').toLowerCase();
    const users = term
        ? quickState.users.filter(u =>
            String(u.userName || '').toLowerCase().includes(term) || String(u.userId).includes(term))
        : quickState.users;

    if (!users.length) {
        const empty = el('div', 'ab-empty');
        empty.appendChild(abIconEl('users'));
        empty.appendChild(el('p', '', bt('quick.follow.empty', '没有匹配的关注用户')));
        stage.appendChild(empty);
        return;
    }
    const grid = el('div', 'ab-user-grid');
    users.forEach((u, idx) => {
        const card = el('button', 'ab-user-card card');
        card.type = 'button';
        card.style.setProperty('--stagger', String(idx));
        const avatar = el('span', 'ab-avatar');
        applyThumbHue(avatar, 'u' + String(u.userId) + (u.userName || ''));
        avatar.appendChild(abIconEl('user'));
        card.appendChild(avatar);
        const meta = el('span', 'ab-user-meta');
        meta.appendChild(el('strong', '', u.userName || String(u.userId)));
        meta.appendChild(el('span', 'ab-muted', 'ID: ' + u.userId));
        card.appendChild(meta);
        card.appendChild(abIconEl('chevron-right', 'ab-user-go'));
        card.addEventListener('click', () => drillQuickUser(u));
        grid.appendChild(card);
    });
    stage.appendChild(grid);
    const limit = 24;
    stage.appendChild(paginationBar({
        page: Math.floor(quickState.usersOffset / limit) + 1,
        totalPages: Math.max(1, Math.ceil(quickState.usersTotal / limit)),
        total: quickState.usersTotal,
        onPage: p => loadQuickFollowing(action, (p - 1) * limit)
    }));
}

async function drillQuickUser(user) {
    quickState.drill = {type: 'user', id: String(user.userId), name: user.userName || String(user.userId)};
    quickState.drillItems = [];
    quickState.error = '';
    renderQuickStage();
    const stage = document.getElementById('abQuickStage');
    const drillBox = stage && stage.querySelector('.ab-drill');
    if (drillBox) drillBox.appendChild(loadingGrid(bt('common.loading', '加载中…')));
    try {
        const idsData = await pixivJson(`/api/pixiv/user/${encodeURIComponent(user.userId)}/artworks`);
        const ids = (idsData.ids || []).map(String).slice(0, 24);
        const cards = await fetchIllustCards(user.userId, ids);
        quickState.drillItems = cards.items || [];
    } catch (e) {
        quickState.error = String(e && e.message || bt('common.request-failed', '请求失败'));
    }
    renderQuickStage();
    const box = document.querySelector('.ab-drill');
    if (box && box.scrollIntoView) box.scrollIntoView({block: 'nearest', behavior: 'smooth'});
}

async function loadQuickCollections(action) {
    const stage = document.getElementById('abQuickStage');
    if (!stage) return;
    quickState.loading = true;
    quickState.error = '';
    renderQuickStage();
    try {
        if (action && action.descriptor) {
            const acquisition = quickAcquisition();
            const context = {action: action.id, page: 1, cursor: action.descriptor.initialCursor || '0', limit: 24};
            const data = await altAcquisitionJson(acquisition.type, 'quick',
                action.descriptor.buildPageRequest(context), 'collections', context);
            quickState.collections = (data.collections || data.items || data.folders || []).map(item => ({
                id: item.id || item.collectionId || item.folderId,
                title: item.title || item.name || item.id,
                bookmarkCount: item.bookmarkCount ?? item.total ?? item.count ?? 0,
                raw: item
            }));
        } else {
            const data = await pixivJson('/api/pixiv/me/collections');
            quickState.collections = data.collections || [];
        }
    } catch (e) {
        quickState.collections = [];
        quickState.error = String(e && e.message || bt('common.request-failed', '请求失败'));
    }
    quickState.loading = false;
    renderQuickStage();
}

function renderQuickCollections(stage) {
    if (!quickState.collections.length) {
        const empty = el('div', 'ab-empty');
        empty.appendChild(abIconEl('folder'));
        empty.appendChild(el('p', '', bt('quick.empty.collections', '没有珍藏集')));
        stage.appendChild(empty);
        return;
    }
    const grid = el('div', 'ab-collection-grid');
    quickState.collections.forEach((c, idx) => {
        const card = el('button', 'ab-collection-card card');
        card.type = 'button';
        card.style.setProperty('--stagger', String(idx));
        const cover = el('span', 'ab-collection-cover');
        applyThumbHue(cover, 'c' + String(c.id) + (c.title || ''));
        cover.appendChild(abIconEl('folder'));
        card.appendChild(cover);
        const meta = el('span', 'ab-collection-meta');
        meta.appendChild(el('strong', '', c.title || String(c.id)));
        const sub = el('span', 'ab-muted');
        sub.textContent = bt('quick.collection.count', '{count} 个收藏', {count: c.bookmarkCount ?? 0});
        meta.appendChild(sub);
        if (Number(c.xRestrict ?? 0) >= 1) {
            meta.appendChild(el('span', 'ab-mini-badge ab-mini-badge--r18', 'R-18'));
        }
        card.appendChild(meta);
        card.appendChild(abIconEl('chevron-right', 'ab-user-go'));
        card.addEventListener('click', () => drillQuickCollection(c));
        grid.appendChild(card);
    });
    stage.appendChild(grid);
}

async function drillQuickCollection(collection) {
    quickState.drill = {type: 'collection', id: String(collection.id), name: collection.title || String(collection.id)};
    quickState.drillItems = [];
    quickState.error = '';
    renderQuickStage();
    try {
        const action = quickActionDef(quickState.action);
        if (action && action.descriptor && typeof action.descriptor.buildCollectionWorksPageRequest === 'function') {
            const acquisition = quickAcquisition();
            const context = {action: action.id, page: 1, cursor: '0', limit: 24,
                inner: {type: 'collection', id: String(collection.id), name: collection.title}};
            const data = await altAcquisitionJson(acquisition.type, 'quick',
                action.descriptor.buildCollectionWorksPageRequest(collection.id, context), 'collection-works', context);
            quickState.drillItems = normalizeAcquisitionItems(data.items || data.works || [], acquisition, context, 'quick');
        } else {
            const data = await pixivJson(`/api/pixiv/me/collection/${encodeURIComponent(collection.id)}/works`);
            quickState.drillItems = (data.works || []).map(w => Object.assign({}, w, {kind: w.kind || 'illust'}));
        }
    } catch (e) {
        quickState.error = String(e && e.message || bt('common.request-failed', '请求失败'));
    }
    renderQuickStage();
    const box = document.querySelector('.ab-drill');
    if (box && box.scrollIntoView) box.scrollIntoView({block: 'nearest', behavior: 'smooth'});
}

function renderQuickDrill(stage) {
    const drill = el('div', 'ab-drill');
    const head = el('div', 'ab-drill-head');
    head.appendChild(el('h3', '', quickState.drill.name));
    const close = el('button', 'ab-iconbtn');
    close.type = 'button';
    close.setAttribute('aria-label', bt('common.close', '关闭'));
    close.appendChild(abIconEl('x'));
    close.addEventListener('click', () => {
        quickState.drill = null;
        quickState.drillItems = [];
        renderQuickStage();
    });
    head.appendChild(close);
    drill.appendChild(head);
    if (!quickState.drillItems.length) {
        drill.appendChild(loadingGrid(bt('common.loading', '加载中…')));
    } else {
        drill.appendChild(enqueueBar({
            summary: bt('quick.drill.summary', '{count} 个作品', {count: quickState.drillItems.length}),
            pageEnabled: true,
            allEnabled: true,
            onEnqueuePage: () => enqueueItems(quickState.drillItems, null, {source: QUICK_FETCH_MODE}),
            onEnqueueAll: () => enqueueItems(quickState.drillItems, null, {source: QUICK_FETCH_MODE})
        }));
        drill.appendChild(worksGrid(quickState.drillItems, {source: QUICK_FETCH_MODE}));
    }
    stage.appendChild(drill);
}

// 统一入队 + 反馈（kind=null 时按条目自身 kind）
function enqueueItems(items, kind, opts) {
    const options = opts || {};
    const list = (items || []).filter(Boolean);
    if (!list.length) {
        abToast('warning', bt('status.queue-empty', '队列为空'));
        return 0;
    }
    const ids = list.map(item => String(item.id));
    const metas = list.map(item => buildQueueMeta(item, kind || item.kind || 'illust', options));
    const added = addItemsToQueue(ids, metas, options.source || state.mode,
        options.username || '', options.authorId, options.authorName);
    if (!options.silent) {
        if (added > 0) {
            abToast('success', list.length === 1
                ? bt('queue.toast.added', '已加入队列')
                : bt('queue.toast.page-added', '本页已加入 {count} 个作品', {count: added}));
        } else {
            abToast('info', bt('queue.toast.in-queue', '已在队列中'));
        }
    }
    syncAllResultsQueueState();
    return added;
}

/* ============================================================
   批量导入单作品模式
   ============================================================ */
function renderImportMode(panel) {
    const def = AB_MODES[1];
    panel.appendChild(modeHeader(def, [filterButton(), settingsButton()]));

    const sources = el('div', 'ab-import-sources');
    sources.appendChild(el('span', 'ab-muted', bt('import.sources', '支持的数据来源：')));
    altSourcesForMode('single-import').forEach(src => {
        sources.appendChild(el('span', 'ab-pill ab-pill--brand', src.label));
    });
    panel.appendChild(sources);

    const composer = el('div', 'ab-composer card');
    const textarea = el('textarea', 'ab-input ab-import-input');
    textarea.id = 'abImportInput';
    textarea.rows = 8;
    textarea.spellcheck = false;
    textarea.placeholder = bt('batch:input.single-import.placeholder',
        '粘贴插画/漫画/动图/小说单作品链接列表，兼容 One-Tab，N-Tab 等标签页管理插件导出格式...');
    composer.appendChild(textarea);

    const help = el('details', 'ab-import-help');
    help.appendChild(el('summary', '', bt('import.format.title', '导入格式说明')));
    const list = el('ul', 'ab-note-list');
    [
        bt('batch:label.import-format', '导入格式：') + ' url | title '
            + bt('batch:label.import-format-or', '或') + ' id | title',
        bt('batch:label.import-example', '每行一条，例如：')
            + bt('batch:label.import-example-value', 'https://www.pixiv.net/artworks/12345678 | 示例标题'),
        bt('batch:label.import-bare-id-example', '仅 ID 示例：')
            + bt('batch:label.import-bare-id-example-value', '12345678 | 示例标题'),
        bt('batch:hint.import-bare-id', '仅写数字 ID 时默认按插画解析（等同于 https://www.pixiv.net/artworks/{id}）；若需要按小说解析，请在该行之前加一行 <code>novel:</code> 作为区段头。'),
        bt('batch:hint.import-section-header', '区段头 <code>artwork:</code> / <code>novel:</code> 单独成行（大小写不敏感，全/半角冒号均可）；其下方的所有「仅 ID / id | title」按该类型解析，直到遇到下一个区段头或文本结束。明确的链接始终按链接自身类型解析，与所在区段无关。'),
        bt('batch:hint.import-title-optional', '标题可留空；下载前会自动获取真实标题。兼容 One-Tab，N-Tab 等标签页管理插件导出格式。'),
        bt('batch:hint.import-reimport', '也兼容下方“导出全部”“导出未下载”按钮生成的作品列表，可直接重新导入。')
    ].forEach(text => {
        const li = el('li');
        li.textContent = text.replace(/<\/?code>/g, '');
        list.appendChild(li);
    });
    help.appendChild(list);
    // 取得侧导入示例槽位：作品类型插件经 queueTypes 贡献各自来源的链接示例
    //（如来源插件自己的 URL 示例），与旧布局 import-hint 槽位同契约；插件禁用时缺席。
    const importHintSlot = document.createElement('template');
    importHintSlot.setAttribute('data-qt-slot', 'import-hint');
    help.appendChild(importHintSlot);
    composer.appendChild(help);

    const actions = el('div', 'ab-composer-actions');
    const importBtn = el('button', 'ab-btn ab-btn--primary');
    importBtn.id = 'abBtnImport';
    importBtn.type = 'button';
    importBtn.appendChild(abIconEl('plus'));
    importBtn.appendChild(el('span', '', bt('import.enqueue', '导入并加入队列')));
    importBtn.addEventListener('click', () => runImportParse(false));
    const freshBtn = el('button', 'ab-btn ab-btn--danger-ghost');
    freshBtn.type = 'button';
    freshBtn.appendChild(abIconEl('refresh'));
    freshBtn.appendChild(el('span', '', bt('import.reimport', '清空队列后重新导入')));
    freshBtn.addEventListener('click', async () => {
        if (!await abConfirm('dialog.confirm-reparse', '确认清除当前队列并重新解析？')) return;
        runImportParse(true);
    });
    actions.appendChild(importBtn);
    actions.appendChild(freshBtn);
    composer.appendChild(actions);

    const result = el('div', 'ab-import-result');
    result.id = 'abImportResult';
    composer.appendChild(result);
    panel.appendChild(composer);
}

// 解析语义与 batch single-import 对齐：区段头 / 显式 URL 优先 / 裸 ID 按区段。
function parseImportText(text) {
    const contributed = altParseImportText(text);
    if (contributed) return contributed;
    const lines = String(text || '').split('\n').map(l => l.trim()).filter(Boolean);
    const bareIdRegex = /^(\d+)\s*(?:\|\s*(.*))?$/;
    const sectionHeaderRegex = /^([A-Za-z]+)\s*[:：]\s*$/;
    const urlMatchers = [
        {kind: 'illust', re: /https?:\/\/www\.pixiv\.net\/artworks\/(\d+)/},
        {kind: 'novel', re: /https?:\/\/www\.pixiv\.net\/novel\/show\.php\?[^\s|]*?\bid=(\d+)/}
    ];
    let section = 'illust';
    let sectionExplicit = false;
    const items = [];
    let skippedUnavailable = 0;
    const rejected = [];
    for (const ln of lines) {
        const head = ln.match(sectionHeaderRegex);
        if (head) {
            const token = head[1].toLowerCase();
            if (token === 'artwork' || token === 'illust') {
                section = 'illust';
                sectionExplicit = true;
            } else if (token === 'novel') {
                section = 'novel';
                sectionExplicit = true;
            } else {
                section = null;
                sectionExplicit = true;
            }
            continue;
        }
        let matched = null;
        for (const m of urlMatchers) {
            const hit = ln.match(m.re);
            if (hit) {
                if (matched) { matched = 'ambiguous'; break; }
                matched = {kind: m.kind, id: hit[1]};
            }
        }
        if (matched === 'ambiguous') {
            rejected.push(ln);
            continue;
        }
        if (matched) {
            const titleRaw = (ln.split('|')[1] || '').trim();
            items.push({id: matched.id, kind: matched.kind, title: titleRaw});
            continue;
        }
        const bare = ln.match(bareIdRegex);
        if (bare) {
            if (!section) {
                skippedUnavailable++;
                continue;
            }
            items.push({id: bare[1], kind: section, title: (bare[2] || '').trim()});
            continue;
        }
        if (/^https?:\/\//.test(ln)) rejected.push(ln);
    }
    // 按 id+kind 去重
    const seen = new Set();
    const unique = items.filter(item => {
        const key = item.kind + ':' + item.id;
        if (seen.has(key)) return false;
        seen.add(key);
        return true;
    });
    return {items: unique, skippedUnavailable, rejected};
}

function runImportParse(clearFirst) {
    const textarea = document.getElementById('abImportInput');
    const result = document.getElementById('abImportResult');
    if (!textarea || !result) return;
    const parsed = parseImportText(textarea.value);
    importState.parsed = parsed.items;
    result.innerHTML = '';
    if (!parsed.items.length) {
        const msg = parsed.skippedUnavailable > 0
            ? bt('status.single-import-skipped-unavailable', '已跳过 {count} 个：所属作品类型当前不可用', {count: parsed.skippedUnavailable})
            : parsed.rejected.length
                ? bt('status.single-import-ambiguous', '已拒绝 {count} 个归属不明确的单作品输入', {count: parsed.rejected.length})
                : bt('status.single-import-none', '未解析到任何单作品链接');
        result.appendChild(el('p', 'ab-import-summary ab-import-summary--error', msg));
        return;
    }
    const summary = el('p', 'ab-import-summary');
    summary.textContent = bt('status.parsed-summary', '解析完成：共 {total} 个，新增 {added} 个',
        {total: parsed.items.length, added: parsed.items.filter(i => !queueHas(i.id)).length});
    result.appendChild(summary);
    const preview = el('div', 'ab-import-preview');
    parsed.items.slice(0, 60).forEach(item => {
        const row = el('div', 'ab-import-row');
        row.appendChild(abIconEl(item.kind === 'novel' ? 'book' : 'image'));
        row.appendChild(el('span', 'ab-import-id', String(item.id)));
        row.appendChild(el('span', 'ab-import-title',
            item.title || bt('import.auto-title', '（下载前自动获取标题）')));
        const inQueue = queueHas(item.id);
        if (inQueue) row.appendChild(el('span', 'ab-pill ab-pill--ok', bt('queue.toast.in-queue', '已在队列中')));
        preview.appendChild(row);
    });
    if (parsed.items.length > 60) {
        preview.appendChild(el('p', 'ab-muted',
            bt('import.preview-more', '…以及另外 {count} 个', {count: parsed.items.length - 60})));
    }
    result.appendChild(preview);
    commitImport(!!clearFirst);
}

function commitImport(clearFirst) {
    if (clearFirst) stopAndClear();
    const items = importState.parsed;
    if (!items.length) return;
    let added = 0;
    const groups = new Map();
    items.forEach(item => {
        const source = item.source || SINGLE_IMPORT_MODE;
        if (!groups.has(source)) groups.set(source, []);
        groups.get(source).push(item);
    });
    groups.forEach((group, source) => {
        added += addItemsToQueue(group.map(item => item.id),
            group.map(item => buildQueueMeta(item, item.kind, {})), source);
    });
    abToast('success', bt('status.parsed-summary', '解析完成：共 {total} 个，新增 {added} 个',
        {total: items.length, added}));
}
