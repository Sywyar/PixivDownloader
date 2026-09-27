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
    const acquisition = quickAcquisition();
    if (!acquisition) return [];
    return Object.entries(acquisition.actions || {}).map(([id, descriptor]) => {
        const labelNamespace = descriptor.labelNamespace
            || (acquisition.dataSource && acquisition.dataSource.displayNamespace) || '';
        const labelKey = descriptor.labelI18nKey || ('quick.action.' + id);
        const known = QUICK_ACTIONS.find(action => action.id === id);
        return {
            id,
            icon: descriptor.iconKey || (known && known.icon)
                || (descriptor.viewType === 'collection-list' ? 'folder' : 'bookmark'),
            labelKey: known ? known.labelKey : (labelNamespace ? labelNamespace + ':' : '') + labelKey,
            label: descriptor.label || (known && known.label) || id,
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
            quickState.uid = null;
            quickState.drill = null;
            quickState.loadSeq++;
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
            quickState.drill = null;
            quickState.loadSeq++;
            quickState.action = null;
            renderStage();
        }));
    }

    const acquisition = quickAcquisition();
    const credentialOk = !(acquisition && acquisition.account
        && typeof acquisition.account.credentialMissing === 'function'
        && acquisition.account.credentialMissing());
    if (!credentialOk) {
        const gate = el('div', 'ab-credential-gate');
        gate.appendChild(abIconEl('user', 'ab-gate-icon'));
        gate.appendChild(el('h2', '', bt('quick.connect.title', '连接你的 Pixiv')));
        gate.appendChild(el('p', '', bt('quick.connect.hint', '保存 Cookie 后，即可获取收藏、关注和珍藏集中的作品。')));
        const fix = el('button', 'ab-btn ab-btn--primary', bt('quick.connect.action', '配置 Cookie'));
        fix.type = 'button';
        fix.addEventListener('click', openCookieModal);
        gate.appendChild(fix);
        panel.appendChild(gate);
        return;
    }
    const account = el('div', 'ab-account');
    account.appendChild(abIconEl('user'));
    account.appendChild(el('span', 'ab-muted', bt('quick.uid', '当前账号 UID')));
    const uid = el('span', '', quickState.uid || '—');
    uid.id = 'abQuickUid';
    account.appendChild(uid);
    panel.appendChild(account);
    const actions = el('div', 'ab-quick-actions');
    const label = el('label', 'ab-control-label', bt('quick.source', '作品来源'));
    label.htmlFor = 'abQuickAction';
    const select = el('select', 'ab-input');
    select.id = 'abQuickAction';
    const placeholder = el('option', '', bt('quick.choose', '选择收藏、关注或其他来源'));
    placeholder.value = '';
    placeholder.disabled = true;
    select.appendChild(placeholder);
    quickActionDefs().forEach(action => {
        const option = el('option', '', bt(action.labelKey, action.label));
        option.value = action.id;
        select.appendChild(option);
    });
    select.value = quickState.action || '';
    select.addEventListener('change', () => runQuickAction(quickActionDef(select.value)));
    actions.append(label, select);
    panel.appendChild(actions);

    const stage = el('div', 'ab-quick-stage');
    stage.id = 'abQuickStage';
    panel.appendChild(stage);
    renderQuickStage();

    if (credentialOk && !quickState.uid) loadQuickUid();
}

async function loadQuickUid() {
    const source = quickState.source, revision = quickState.accountRevision;
    const isCurrent = () => source === quickState.source && revision === quickState.accountRevision;
    try {
        const acquisition = quickAcquisition();
        if (!acquisition || !acquisition.account) {
            throw new Error(bt('quick.error.account-unavailable', '当前来源不支持账号获取'));
        }
        const data = await altAcquisitionJson(acquisition.type, 'quick',
            acquisition.account.buildRequest(), 'account', {});
        if (!isCurrent()) return;
        const value = acquisition.account.readId(data);
        quickState.uid = value == null ? null : String(value);
    } catch {
        if (!isCurrent()) return;
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
    if (!action) return;
    quickState.allIds = [];
    quickState.pageCursors = new Map();
    quickState.loadSeq++;
    quickState.action = action.id;
    const selector = document.getElementById('abQuickAction');
    if (selector) selector.value = action.id;
    quickState.drill = null;
    renderStagePreserve();
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
    const seq = ++quickState.loadSeq;
    const source = quickState.source, kind = quickState.kind;
    const isCurrent = () => seq === quickState.loadSeq && source === quickState.source && kind === quickState.kind;
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
                        (descriptor.buildIdsRequest || acquisition.buildMyWorksIdsRequest)(quickState.uid), 'ids', context);
                    if (!isCurrent()) return;
                    quickState.allIds = (idsData.ids || []).map(String);
                }
                total = quickState.allIds.length;
                const pageIds = quickState.allIds.slice((page - 1) * limit, page * limit);
                const data = await altAcquisitionJson(acquisition.type, 'quick',
                    (descriptor.buildCardsRequest || acquisition.buildCardsRequest)(quickState.uid, pageIds), 'cards', context);
                items = normalizeAcquisitionItems(data.items || [], acquisition, context, 'quick');
                totalPages = Math.max(1, Math.ceil(total / limit));
            } else {
                const data = await altAcquisitionJson(acquisition.type, 'quick',
                    descriptor.buildPageRequest(context), 'page', context);
                const raw = data.items || data.works || [];
                items = normalizeAcquisitionItems(raw, acquisition, context, 'quick');
                total = Number(data.total || items.length);
                hasNext = quickPageHasMore(data, page, limit, raw.length);
                if (page === 1) quickState.pageCursors = new Map();
                if (hasNext && descriptor.cursorPaging) {
                    quickState.pageCursors.set(page + 1, altNextCursor(data, cursor, true));
                }
                totalPages = Math.max(page + (hasNext ? 1 : 0), Math.ceil(total / limit));
            }
        } else {
            throw new Error(bt('quick.error.unknown-action', null));
        }
        if (!isCurrent()) return;
        quickState.items = items;
        quickState.rawItems = items;
        quickState.page = page;
        quickState.total = total;
        quickState.totalPages = totalPages;
        quickState.hasNext = hasNext;
        quickState.loading = false;
        quickState.error = '';
    } catch (e) {
        if (!isCurrent()) return;
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
    if (quickState.drill) await applyQuickDrillFilters(quickState.drill);
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
        allEnabled: quickState.rawItems.length > 0 || quickState.total > 0,
        onEnqueuePage: () => enqueueItems(quickState.items, null, {source: QUICK_FETCH_MODE}),
        onEnqueueAll: () => enqueueQuickAll(action)
    }));
    stage.appendChild(worksGrid(quickState.items, {
        source: QUICK_FETCH_MODE,
        emptyText: bt('quick.empty.works', '该范围内没有作品')
    }));
    stage.appendChild(paginationBar({
        page: quickState.page,
        totalPages: quickState.totalPages,
        total: quickState.total,
        hasNext: quickState.hasNext,
        onPage: p => loadQuickWorks(action, p)
    }));
}

async function enqueueQuickAll(action) {
    const source = quickState.source, kind = quickState.kind, seq = quickState.loadSeq;
    const isCurrent = () => source === quickState.source && kind === quickState.kind
        && action.id === quickState.action && seq === quickState.loadSeq;
    if (!await abConfirm('quick.enqueue-all.confirm',
        '将全部 {count} 个作品加入队列？需要逐页抓取约 {pages} 页，会产生较多请求。',
        {count: quickState.total || quickState.items.length, pages: quickState.totalPages || 1}) || !isCurrent()) return;
    if (action.descriptor) {
        const acquisition = quickAcquisition();
        const descriptor = action.descriptor;
        const lease = altQueueTypes().acquisitionLease(acquisition.type, 'quick');
        if (descriptor.allIdsFastPath) {
            try {
                if (!quickState.uid) await loadQuickUid();
                if (!quickState.uid) throw new Error(bt('quick.error.no-uid', '无法解析当前账号'));
                const context = {action: action.id, uid: quickState.uid,
                    accountId: quickState.uid, accountOwner: quickState.source};
                if (!quickState.allIds.length) {
                    const data = await altAcquisitionJson(acquisition.type, 'quick',
                        (descriptor.buildIdsRequest || acquisition.buildMyWorksIdsRequest)(quickState.uid), 'ids', context);
                    quickState.allIds = (data.ids || []).map(String);
                }
                lease.assertCurrent();
                if (!isCurrent()) return;
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
        for (let page = 1; ; page++) {
            const context = {
                action: action.id, page, offset: (page - 1) * limit, limit, cursor,
                rest: quickRest(action), uid: quickState.uid,
                accountId: quickState.uid, accountOwner: quickState.source
            };
            let data;
            try {
                lease.assertCurrent();
                if (!isCurrent()) return;
                data = await altAcquisitionJson(acquisition.type, 'quick',
                    descriptor.buildPageRequest(context), 'page', context);
                lease.assertCurrent();
                if (!isCurrent()) return;
            } catch (e) {
                abToast('error', String(e && e.message || bt('common.request-failed', '请求失败')));
                return;
            }
            all.push(...normalizeAcquisitionItems(data.items || data.works || [], acquisition, context, 'quick'));
            const hasMore = quickPageHasMore(data, page, limit, (data.items || data.works || []).length);
            if (!hasMore) break;
            // 已知总量按来源遍历；未知结束点沿用游标获取的累计保护。
            const totalPages = Number(data.totalPages) > 0 ? Number(data.totalPages) : Math.ceil(Number(data.total) / limit);
            const knownTotal = Number.isFinite(totalPages) && totalPages > 0;
            if (knownTotal && page >= totalPages) break;
            if (!knownTotal && page >= 1000) {
                abToast('error', bt('pagination.error.page-limit', '分页数量超出安全上限，未加入不完整结果'));
                return;
            }
            if (descriptor.cursorPaging) {
                try { cursor = altNextCursor(data, cursor, hasMore); }
                catch (error) {
                    abToast('error', String(error && error.message || bt('common.request-failed', '请求失败')));
                    return;
                }
            }
        }
        const added = enqueueItems(all, null, {source: QUICK_FETCH_MODE, silent: true});
        abToast('success', bt('queue.toast.batch-added', '已批量加入 {count} 个作品', {count: added}));
        return;
    }
    abToast('error', bt('quick.error.unknown-action', null));
}

async function loadQuickFollowing(action, offset) {
    const stage = document.getElementById('abQuickStage');
    if (!stage) return;
    const seq = ++quickState.loadSeq;
    quickState.loading = true;
    quickState.error = '';
    renderQuickStage();
    try {
        const limit = 24;
        const acquisition = quickAcquisition();
        const context = {page: Math.floor(offset / limit) + 1, offset, limit, rest: quickRest(action)};
        const data = await altAcquisitionJson(acquisition.type, 'quick',
            action.descriptor.buildPageRequest(context), 'page', context);
        if (seq !== quickState.loadSeq) return;
        quickState.users = data.users || data.items || [];
        quickState.usersTotal = Number(data.total || quickState.users.length);
        quickState.usersOffset = offset;
    } catch (e) {
        if (seq !== quickState.loadSeq) return;
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

function quickPageHasMore(data, page, limit, count) {
    if (typeof data.hasMore === 'boolean') return data.hasMore;
    if (typeof data.hasNext === 'boolean') return data.hasNext;
    if (Number(data.totalPages) > 0) return page < Number(data.totalPages);
    return page * limit < Number(data.total || 0) && count > 0;
}

function quickUserAcquisitions() {
    const descriptor = quickActionDef(quickState.action)?.descriptor;
    const allowed = new Set(descriptor?.userWorkTypes || [quickState.kind]);
    return altTypesForSource('quick', quickState.source)
        .filter(item => allowed.has(item.type))
        .map(item => altAcquisition('quick', quickState.source, item.type))
        .filter(acq => acq && (typeof acq.buildUserPageRequest === 'function'
            || (typeof acq.buildUserIdsRequest === 'function' && typeof acq.buildCardsRequest === 'function')));
}

async function drillQuickUser(user) {
    const kinds = quickUserAcquisitions();
    const kind = (kinds.find(acq => acq.type === quickState.kind) || kinds[0])?.type;
    const drill = {
        type: 'user', id: String(user.userId), name: user.userName || String(user.userId),
        kind, page: 1, total: 0, rawItems: [], cursors: new Map(), loadSeq: 0
    };
    quickState.drill = drill;
    await loadQuickDrillPage(1);
}

async function fetchQuickDrillPage(drill, page) {
    const acquisition = altAcquisition('quick', quickState.source, drill.kind);
    if (!acquisition) throw new Error(bt('queue.message.type-unavailable', '该类型当前不可用（其插件已禁用）'));
    const descriptor = quickActionDef(quickState.action)?.descriptor;
    const limit = Math.max(1, Number(descriptor?.pageSize || acquisition.pageSize) || 24);
    const lease = altQueueTypes().acquisitionLease(acquisition.type, 'quick');
    const initial = drill.type === 'collection' ? descriptor?.initialCursor : acquisition.initialCursor;
    const cursor = page === 1 ? initial ?? null : drill.cursors.get(page);
    const context = {
        page, offset: (page - 1) * limit, limit, cursor,
        userId: drill.id, username: drill.name,
        inner: {type: drill.type === 'user' ? 'following-user' : 'collection',
            id: drill.id, userId: drill.id, name: drill.name, kind: drill.kind}
    };
    const request = async (spec, operation) => {
        lease.assertCurrent();
        const data = await altAcquisitionJson(acquisition.type, 'quick', spec, operation, context);
        lease.assertCurrent();
        return data;
    };
    let data, raw, hasMore;
    if (drill.type === 'user' && typeof acquisition.buildUserPageRequest !== 'function') {
        if (!drill.ids) {
            const idsData = await request(acquisition.buildUserIdsRequest(drill.id), 'ids');
            drill.ids = (idsData.ids || []).map(String);
        }
        const ids = drill.ids.slice(context.offset, context.offset + limit);
        data = ids.length ? await request(acquisition.buildCardsRequest(drill.id, ids), 'cards') : {items: []};
        raw = data.items || [];
        data.total = drill.ids.length;
        hasMore = context.offset + limit < drill.ids.length;
    } else if (drill.type === 'collection' && typeof descriptor?.buildCollectionWorksPageRequest !== 'function') {
        if (typeof descriptor?.buildCollectionWorksRequest !== 'function') {
            throw new Error(bt('quick.error.unknown-action', '该入口当前不可用'));
        }
        data = await request(descriptor.buildCollectionWorksRequest(drill.id), 'collection-works');
        raw = data.works || data.items || [];
        hasMore = false;
    } else {
        if (page > 1 && cursor == null) {
            throw new Error(bt('pagination.error.cursor-unavailable', '分页游标不可用，请重新从第一页加载'));
        }
        const spec = drill.type === 'user'
            ? acquisition.buildUserPageRequest(drill.id, context)
            : descriptor.buildCollectionWorksPageRequest(drill.id, context);
        data = await request(spec, drill.type === 'user' ? 'page' : 'collection-works');
        raw = data.items || data.works || [];
        hasMore = !!data.hasMore;
        if (hasMore) drill.cursors.set(page + 1, altNextCursor(data, cursor, true));
    }
    // 混合珍藏集按条目的活动类型构建队列元数据，不能把小说当成插画。
    const items = raw.flatMap(item => {
        const owner = item.kind && item.kind !== acquisition.type
            ? altAcquisition('quick', quickState.source, item.kind) : acquisition;
        return owner ? normalizeAcquisitionItems([item], owner, context, 'quick') : [];
    });
    return {
        items, page, limit, hasMore,
        knownTotal: Number.isFinite(Number(data.total)) && Number(data.total) > 0,
        total: Math.max(Number(data.total) || 0, context.offset + raw.length + (hasMore ? 1 : 0))
    };
}

async function loadQuickDrillPage(page) {
    const drill = quickState.drill;
    if (!drill) return;
    const seq = ++drill.loadSeq;
    const isCurrent = () => quickState.drill === drill && seq === drill.loadSeq;
    drill.loading = true;
    drill.error = '';
    renderQuickStage();
    try {
        const data = await fetchQuickDrillPage(drill, page);
        if (!isCurrent()) return;
        Object.assign(drill, data, {rawItems: data.items});
        await applyQuickDrillFilters(drill);
    } catch (error) {
        if (!isCurrent()) return;
        drill.error = String(error?.message || bt('common.request-failed', '请求失败'));
    }
    if (!isCurrent()) return;
    drill.loading = false;
    renderQuickStage();
}

async function applyQuickDrillFilters(drill) {
    const seq = drill.filterSeq = (drill.filterSeq || 0) + 1;
    const result = await computeFilteredItems(drill.rawItems, extraFilters, drill.kind,
        () => quickState.drill !== drill || seq !== drill.filterSeq);
    if (!result || quickState.drill !== drill || seq !== drill.filterSeq) return;
    quickState.drillItems = result.filtered;
    drill.filterSummary = result.stats;
}

async function enqueueQuickDrillAll() {
    const drill = quickState.drill;
    if (!drill) return;
    const seq = drill.loadSeq;
    const isCurrent = () => quickState.drill === drill && seq === drill.loadSeq;
    if (!await abConfirm('quick.confirm.add-all-paged',
        '将逐页抓取 {pages} 页（共 {total} 个）并加入队列，请求较多，确认继续？',
        {pages: Math.max(1, Math.ceil(drill.total / drill.limit)), total: drill.total}) || !isCurrent()) return;
    const lease = altQueueTypes().acquisitionLease(drill.kind, 'quick');
    const all = [];
    try {
        for (let page = 1; ; page++) {
            if (!isCurrent()) return;
            lease.assertCurrent();
            const data = await fetchQuickDrillPage(drill, page);
            lease.assertCurrent();
            if (!isCurrent()) return;
            all.push(...data.items);
            if (!data.hasMore) break;
            if (!data.knownTotal && page >= 1000) throw new Error(bt('pagination.error.page-limit', '分页数量超出安全上限，未加入不完整结果'));
        }
        // 与现行快捷获取一致：全量入队后由下载执行阶段应用附加筛选。
        const added = enqueueItems(all, null, {source: QUICK_FETCH_MODE, silent: true});
        abToast('success', bt('queue.toast.batch-added', '已批量加入 {count} 个作品', {count: added}));
    } catch (error) {
        if (isCurrent()) abToast('error', String(error?.message || bt('common.request-failed', '请求失败')));
    }
}

async function loadQuickCollections(action) {
    const stage = document.getElementById('abQuickStage');
    if (!stage) return;
    const seq = ++quickState.loadSeq;
    quickState.loading = true;
    quickState.error = '';
    renderQuickStage();
    try {
        if (action && action.descriptor) {
            const acquisition = quickAcquisition();
            const context = {action: action.id, page: 1, cursor: action.descriptor.initialCursor || '0', limit: 24};
            const data = await altAcquisitionJson(acquisition.type, 'quick',
                action.descriptor.buildPageRequest(context), 'collections', context);
            if (seq !== quickState.loadSeq) return;
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
        if (seq !== quickState.loadSeq) return;
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
    quickState.drill = {
        type: 'collection', id: String(collection.id), name: collection.title || String(collection.id),
        kind: quickState.kind, page: 1, total: 0, rawItems: [], cursors: new Map(), loadSeq: 0
    };
    await loadQuickDrillPage(1);
}

function renderQuickDrill(stage) {
    const current = quickState.drill;
    const drill = el('div', 'ab-drill');
    const head = el('div', 'ab-drill-head');
    head.appendChild(el('h3', '', current.name));
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
    if (current.type === 'user') {
        const choices = quickUserAcquisitions().map(acq => [acq.type, altTypeLabel(acq.type)]);
        if (choices.length > 1) drill.appendChild(smallSeg(choices, current.kind, kind => {
            quickState.drill = Object.assign({}, current, {
                kind, ids: null, cursors: new Map(), page: 1, rawItems: [], loadSeq: 0
            });
            loadQuickDrillPage(1);
        }));
    }
    if (current.loading) {
        drill.appendChild(loadingGrid(bt('common.loading', '加载中…')));
    } else if (current.error) {
        drill.appendChild(errorBox(current.error, () => loadQuickDrillPage(current.page)));
    } else {
        const previewKey = 'quick-drill:' + current.type + ':' + current.id + ':' + current.kind;
        drill.appendChild(enqueueBar({
            previewKey,
            summary: bt('quick.stage.summary', '第 {page} 页 · 共 {count} 个',
                {page: current.page, count: current.total}),
            filterSummary: current.filterSummary && hasExtraSearchFilter()
                ? bt('user.summary.filtered', '附加筛选后 {count} 个', {count: current.filterSummary.filteredCount}) : '',
            pageEnabled: quickState.drillItems.length > 0,
            allEnabled: current.total > 0,
            onEnqueuePage: () => enqueueItems(quickState.drillItems, null, {source: QUICK_FETCH_MODE}),
            onEnqueueAll: enqueueQuickDrillAll
        }));
        drill.appendChild(worksGrid(quickState.drillItems, {source: QUICK_FETCH_MODE, previewKey}));
        drill.appendChild(paginationBar({
            previewKey, page: current.page,
            totalPages: current.ids ? Math.max(1, Math.ceil(current.total / current.limit)) : 0,
            total: current.total, hasNext: current.hasMore, onPage: loadQuickDrillPage
        }));
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
    textarea.setAttribute('aria-label', bt('modes.import', '批量导入'));
    textarea.setAttribute('aria-describedby', 'abImportResult');
    textarea.rows = 5;
    textarea.spellcheck = false;
    textarea.placeholder = bt('batch:input.single-import.placeholder',
        '粘贴插画/漫画/动图/小说单作品链接列表，兼容 One-Tab，N-Tab 等标签页管理插件导出格式...');
    composer.appendChild(textarea);
    composer.appendChild(el('p', 'ab-field-note', bt('import.id-hint', '数字 ID 默认为插画；小说请粘贴完整链接，或在 ID 前另起一行写 novel:。')));

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
    help.appendChild(freshBtn);
    composer.appendChild(actions);

    const result = el('div', 'ab-import-result');
    result.id = 'abImportResult';
    const announcement = el('p', 'ab-sr-only');
    announcement.id = 'abImportAnnouncement';
    announcement.setAttribute('role', 'status');
    panel.appendChild(announcement);
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
    const announce = message => {
        const live = document.getElementById('abImportAnnouncement');
        if (live) live.textContent = message;
    };
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
        announce(msg);
        textarea.setAttribute('aria-invalid', 'true');
        return;
    }
    const summary = el('p', 'ab-import-summary');
    summary.textContent = bt('status.parsed-summary', '解析完成：共 {total} 个，新增 {added} 个',
        {total: parsed.items.length, added: parsed.items.filter(i => !queueHas(i.id)).length});
    result.appendChild(summary);
    announce(summary.textContent);
    textarea.removeAttribute('aria-invalid');
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
