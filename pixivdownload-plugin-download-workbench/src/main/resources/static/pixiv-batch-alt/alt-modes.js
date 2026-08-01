'use strict';
/* ============================================================
   alt-modes — 模式导轨 + 舞台框架 + 快捷获取 / 批量导入 / User / Search / 系列
   取得请求复用生产页扩展运行时；入队走 alt-queue 的统一队列模型。
   ============================================================ */

const AB_MODES = [
    {id: QUICK_FETCH_MODE, icon: 'zap', titleKey: 'modes.quick', titleFallback: '快捷获取',
        descKey: 'modes.quick.desc', descFallback: '从账号收藏、关注、珍藏集一键取作品'},
    {id: SINGLE_IMPORT_MODE, icon: 'clipboard', titleKey: 'modes.import', titleFallback: '批量导入',
        descKey: 'modes.import.desc', descFallback: '粘贴作品链接 / ID 清单批量入队'},
    {id: 'user', icon: 'user', titleKey: 'modes.user', titleFallback: '画师',
        descKey: 'modes.user.desc', descFallback: '按画师主页批量抓取全部作品'},
    {id: 'search', icon: 'search', titleKey: 'modes.search', titleFallback: '搜索',
        descKey: 'modes.search.desc', descFallback: '按标签 / 标题搜索并批量获取'},
    {id: 'series', icon: 'layers', titleKey: 'modes.series', titleFallback: '系列',
        descKey: 'modes.series.desc', descFallback: '粘贴系列 / 合集链接整辑抓取'},
    {id: 'schedule', icon: 'clock', titleKey: 'modes.schedule', titleFallback: '计划任务',
        descKey: 'modes.schedule.desc', descFallback: '定时自动追新与增量下载', adminOnly: true}
];

let extensionManifest = null;   // /api/download/extensions 快照（来源 / 类型清单）

function pixivCancelWorkKey(value) {
    const raw = value == null ? '' : String(value);
    return /^[0-9]{1,18}$/.test(raw) ? raw : null;
}

async function fetchExtensions() {
    try {
        const res = await fetch('/api/download/extensions', {credentials: 'same-origin'});
        if (!res.ok) throw new Error('HTTP ' + res.status);
        extensionManifest = await res.json();
    } catch {
        extensionManifest = null;
    }
}

// 当前可用的数据来源（由作品类型插件贡献；运行时尚未加载时回退 Pixiv）。
function acquisitionSources(mode) {
    const out = [];
    const seen = new Set();
    const types = extensionManifest && Array.isArray(extensionManifest.downloadTypes)
        ? extensionManifest.downloadTypes : [];
    types.forEach(t => {
        if (!Array.isArray(t.acquisitionModes) || !t.acquisitionModes.includes(mode)) return;
        const ds = t.dataSource;
        if (!ds || !ds.id || seen.has(ds.id)) return;
        seen.add(ds.id);
        out.push({
            id: ds.id,
            label: ds.displayI18nKey
                ? bt((ds.displayNamespace ? ds.displayNamespace + ':' : '') + ds.displayI18nKey, ds.id)
                : ds.id
        });
    });
    if (!out.length) out.push({id: 'pixiv', label: bt('data-source.pixiv', 'Pixiv')});
    return out;
}

/* ============================================================
   导轨 + 模式切换
   ============================================================ */
function renderRail() {
    const rail = document.getElementById('abRailModes');
    if (!rail) return;
    rail.innerHTML = '';
    AB_MODES.forEach((mode, idx) => {
        if (mode.adminOnly && !isAdmin) return;
        const btn = el('button', 'ab-rail-item' + (state.mode === mode.id ? ' is-active' : ''));
        btn.type = 'button';
        btn.role = 'tab';
        btn.setAttribute('aria-selected', state.mode === mode.id ? 'true' : 'false');
        btn.dataset.mode = mode.id;
        btn.style.setProperty('--stagger', String(idx + 1));
        btn.appendChild(abIconEl(mode.icon));
        btn.appendChild(el('span', 'ab-rail-label', bt(mode.titleKey, mode.titleFallback)));
        if (mode.adminOnly) {
            const lock = el('span', 'ab-rail-lock');
            lock.appendChild(abIconEl('shield'));
            lock.title = bt('modes.schedule.admin', '仅管理员');
            btn.appendChild(lock);
        }
        btn.addEventListener('click', () => switchMode(mode.id));
        rail.appendChild(btn);
    });
    // 滑动指示器
    const indicator = el('span', 'ab-rail-indicator');
    indicator.setAttribute('aria-hidden', 'true');
    rail.appendChild(indicator);
    requestAnimationFrame(moveRailIndicator);
}

function moveRailIndicator() {
    const rail = document.getElementById('abRailModes');
    if (!rail) return;
    const indicator = rail.querySelector('.ab-rail-indicator');
    const active = rail.querySelector('.ab-rail-item.is-active');
    if (!indicator || !active) {
        if (indicator) indicator.hidden = true;
        return;
    }
    indicator.hidden = false;
    indicator.style.transform = `translateY(${active.offsetTop}px)`;
    indicator.style.height = active.offsetHeight + 'px';
}

function switchMode(mode) {
    let normalized = mode;
    if (normalized === 'schedule' && !isAdmin) normalized = QUICK_FETCH_MODE;
    if (state.mode === normalized) return;
    state.mode = normalized;
    storeSet('pixiv_mode', normalized);
    const panel = document.getElementById('abModePanel');
    document.querySelectorAll('#abRailModes .ab-rail-item').forEach(btn => {
        const active = btn.dataset.mode === normalized;
        btn.classList.toggle('is-active', active);
        btn.setAttribute('aria-selected', active ? 'true' : 'false');
    });
    moveRailIndicator();
    if (panel) {
        panel.classList.add('is-leaving');
        setTimeout(() => {
            renderStage();
            panel.classList.remove('is-leaving');
        }, 160);
    } else {
        renderStage();
    }
    if (normalized === 'schedule') {
        enterScheduleMode();
    }
}

function renderStage() {
    const panel = document.getElementById('abModePanel');
    if (!panel) return;
    panel.innerHTML = '';
    const mode = state.mode;
    if (mode === QUICK_FETCH_MODE) renderQuickMode(panel);
    else if (mode === SINGLE_IMPORT_MODE) renderImportMode(panel);
    else if (mode === 'user') renderUserMode(panel);
    else if (mode === 'search') renderSearchMode(panel);
    else if (mode === 'series') renderSeriesMode(panel);
    else if (mode === 'schedule') renderScheduleMode(panel);
    hydrateIcons(panel);
    if (pageI18n) pageI18n.apply(panel);
}

/* ============================================================
   舞台共享构件
   ============================================================ */
function modeHeader(modeDef, actions) {
    const head = el('div', 'ab-mode-head');
    const text = el('div', 'ab-mode-head-text');
    const title = el('h1', 'ab-mode-title');
    title.appendChild(abIconEl(modeDef.icon));
    title.appendChild(el('span', '', bt(modeDef.titleKey, modeDef.titleFallback)));
    text.appendChild(title);
    text.appendChild(el('p', 'ab-mode-desc', bt(modeDef.descKey, modeDef.descFallback)));
    head.appendChild(text);
    const actionWrap = el('div', 'ab-mode-actions');
    (actions || []).forEach(a => actionWrap.appendChild(a));
    head.appendChild(actionWrap);
    return head;
}

function filterButton() {
    const btn = el('button', 'ab-btn ab-btn--ghost ab-btn--sm');
    btn.id = 'abFilterBtn';
    btn.type = 'button';
    btn.appendChild(abIconEl('filter'));
    btn.appendChild(el('span', '', bt('filters.title', '附加筛选')));
    const badge = el('span', 'ab-badge');
    badge.dataset.filterBadge = '1';
    badge.hidden = true;
    btn.appendChild(badge);
    btn.addEventListener('click', openFiltersDrawer);
    return btn;
}

function settingsButton() {
    const btn = el('button', 'ab-btn ab-btn--ghost ab-btn--sm');
    btn.id = 'abSettingsBtn';
    btn.type = 'button';
    btn.appendChild(abIconEl('sliders'));
    btn.appendChild(el('span', '', bt('settings.title', '下载设置')));
    btn.addEventListener('click', openSettingsDrawer);
    return btn;
}

function saveScheduleButton() {
    if (!isAdmin) return null;
    const btn = el('button', 'ab-btn ab-btn--ghost ab-btn--sm');
    btn.type = 'button';
    btn.appendChild(abIconEl('clock'));
    btn.appendChild(el('span', '', bt('schedule.editor.save', '存为计划任务')));
    btn.addEventListener('click', () => openScheduleEditor(null));
    return btn;
}

function sourceChips(sources, current, onSelect) {
    return smallSeg(sources.map(src => [src.id, src.label]), current, onSelect);
}

function workTypeMeta(item, kind) {
    const k = kind || item.kind || 'illust';
    if (k === 'novel') return {cls: 'novel', label: bt('card.type.novel', '小说')};
    if (k !== 'illust') return {cls: 'illust', label: altTypeLabel(k)};
    const t = Number(item.illustType ?? 0);
    if (t === 1) return {cls: 'manga', label: bt('card.type.manga', '漫画')};
    if (t === 2) return {cls: 'ugoira', label: bt('card.type.ugoira', '动图')};
    return {cls: 'illust', label: bt('card.type.illust', '插画')};
}

function workCard(item, opts) {
    const options = opts || {};
    const kind = options.kind || item.kind || 'illust';
    const card = el('article', 'ab-work card');
    card.dataset.workId = String(item.id);
    card.dataset.kind = kind;
    card.style.setProperty('--stagger', String(options.index || 0));

    const thumbWrap = el('div', 'ab-thumb-wrap');
    const thumb = el('div', 'ab-thumb');
    applyThumbHue(thumb, (kind === 'novel' ? 'n' : '') + String(item.id) + (item.title || ''));
    thumb.appendChild(abIconEl(kind === 'novel' ? 'book' : 'image', 'ab-thumb-icon'));
    const rawThumb = item.thumbnailUrl || item.coverUrl;
    if (rawThumb && chromeState.backendAvailable !== false) {
        const img = el('img', 'ab-thumb-img');
        img.loading = 'lazy';
        img.alt = '';
        img.src = '/api/pixiv/thumbnail-proxy?' + new URLSearchParams({url: String(rawThumb)});
        img.addEventListener('error', () => img.remove());
        if (options.blurR18 && Number(item.xRestrict ?? 0) >= 1) img.classList.add('is-blurred');
        thumb.appendChild(img);
    }
    thumbWrap.appendChild(thumb);

    const badges = el('div', 'ab-work-badges');
    const typeMeta = workTypeMeta(item, kind);
    badges.appendChild(el('span', 'ab-mini-badge ab-mini-badge--' + typeMeta.cls, typeMeta.label));
    const xr = Number(item.xRestrict ?? 0);
    if (xr === 2) badges.appendChild(el('span', 'ab-mini-badge ab-mini-badge--r18g', 'R-18G'));
    else if (xr === 1) badges.appendChild(el('span', 'ab-mini-badge ab-mini-badge--r18', 'R-18'));
    if (Number(item.aiType ?? 0) >= 2 || item.isAi === true) {
        badges.appendChild(el('span', 'ab-mini-badge ab-mini-badge--ai', 'AI'));
    }
    if (kind !== 'novel' && Number(item.pageCount ?? 0) > 1) {
        badges.appendChild(el('span', 'ab-mini-badge', String(item.pageCount) + ' P'));
    }
    if (options.seriesOrder != null) {
        badges.appendChild(el('span', 'ab-mini-badge ab-mini-badge--order', '#' + options.seriesOrder));
    }
    thumbWrap.appendChild(badges);

    const enqueueBtn = el('button', 'ab-work-enqueue');
    enqueueBtn.type = 'button';
    enqueueBtn.setAttribute('aria-label', bt('card.enqueue', '加入队列'));
    enqueueBtn.appendChild(abIconEl('plus'));
    enqueueBtn.addEventListener('click', event => {
        event.stopPropagation();
        toggleWorkInQueue(item, kind, options);
    });
    thumbWrap.appendChild(enqueueBtn);
    card.appendChild(thumbWrap);

    const info = el('div', 'ab-work-info');
    const title = el('div', 'ab-work-title',
        item.title || bt('card.untitled', '作品 {id}', {id: item.id}));
    const authorName = item.userName || item.authorName || '';
    title.title = authorName
        ? bt('card.title-tip', '{title}（{author}）', {title: title.textContent, author: authorName})
        : title.textContent;
    info.appendChild(title);
    if (authorName) info.appendChild(el('div', 'ab-work-author', authorName));
    card.appendChild(info);

    card.addEventListener('click', () => toggleWorkInQueue(item, kind, options));
    return card;
}

function toggleWorkInQueue(item, kind, options) {
    const opts = options || {};
    const id = String(item.id);
    if (queueHas(id)) {
        if (removeFromQueue(id)) {
            abToast('info', bt('queue.toast.removed', '已从队列移除'));
        } else {
            abToast('warning', bt('queue.toast.remove-blocked', '无法移除：该作品正在下载中'));
        }
    } else {
        const added = addItemsToQueue([id], [buildQueueMeta(item, kind, opts)],
            opts.source || state.mode, opts.username || '', opts.authorId, opts.authorName);
        if (added > 0) {
            abToast('success', bt('queue.toast.added', '已加入队列'));
        } else {
            abToast('info', bt('queue.toast.in-queue', '已在队列中'));
        }
    }
    syncAllResultsQueueState();
}

// 预览条目 → 队列 meta（与 batch-queue.addItemsToQueue 消费的形状一致）
function buildQueueMeta(item, kind, opts) {
    const options = opts || {};
    const k = kind || item.kind || 'illust';
    const owned = item.__queueMeta && typeof item.__queueMeta === 'object' ? item.__queueMeta : {};
    const meta = Object.assign({}, owned, {
        id: String(item.id),
        kind: k,
        cancelWorkKey: owned.cancelWorkKey || item.cancelWorkKey || (k === 'illust' ? pixivCancelWorkKey(item.id) : null),
        typeData: owned.typeData || (item.typeData && typeof item.typeData === 'object' ? item.typeData : null),
        canonicalUrl: owned.canonicalUrl || item.canonicalUrl || item.url || null,
        title: owned.title || item.title || '',
        authorId: normalizeAuthorId(owned.authorId ?? item.userId ?? item.authorId),
        authorName: owned.authorName || item.userName || item.authorName || '',
        isAi: typeof owned.isAi === 'boolean' ? owned.isAi : item.isAi === true || Number(item.aiType ?? 0) >= 2,
        xRestrict: typeof owned.xRestrict === 'number' ? owned.xRestrict
            : typeof item.xRestrict === 'number' ? item.xRestrict : null,
        tags: Array.isArray(owned.tags) ? owned.tags : Array.isArray(item.tags) ? item.tags : null
    });
    if (k === 'novel') meta.novelId = String(item.id).replace(/^n/, '');
    if (options.seriesId || item.seriesId) {
        meta.seriesId = options.seriesId || item.seriesId;
        meta.seriesOrder = options.seriesOrder ?? item.seriesOrder ?? null;
        meta.seriesTitle = options.seriesTitle || item.seriesTitle || null;
    }
    if (options.wordCount != null) meta.wordCount = options.wordCount;
    return meta;
}

function normalizeAcquisitionItems(items, acquisition, context, mode) {
    return (items || []).map((item, index) => {
        const queueId = acquisition.queueId ? acquisition.queueId(item) : item.id;
        const queueMeta = acquisition.buildQueueMeta
            ? mode === 'series'
                ? acquisition.buildQueueMeta(item, index + 1, context || {})
                : acquisition.buildQueueMeta(item, context || {})
            : {};
        return Object.assign({}, item, queueMeta || {}, {
            id: String(queueId),
            kind: acquisition.type,
            __queueMeta: queueMeta || {}
        });
    });
}

function worksGrid(items, opts) {
    const options = opts || {};
    const grid = el('div', 'ab-grid');
    if (!items.length) {
        const empty = el('div', 'ab-empty');
        empty.appendChild(abIconEl('image'));
        empty.appendChild(el('p', '', options.emptyText || bt('common.empty.works', '该范围内没有作品')));
        const holder = el('div', 'ab-grid-empty');
        holder.appendChild(empty);
        return holder;
    }
    items.forEach((item, idx) => {
        grid.appendChild(workCard(item, Object.assign({}, options, {index: idx})));
    });
    return grid;
}

function loadingGrid(note) {
    const wrap = el('div', 'ab-grid');
    for (let i = 0; i < 8; i++) {
        const sk = el('div', 'ab-work ab-work--skeleton card');
        sk.style.setProperty('--stagger', String(i));
        sk.appendChild(el('div', 'ab-thumb-wrap ab-skeleton'));
        const info = el('div', 'ab-work-info');
        info.appendChild(el('div', 'ab-skeleton ab-skeleton-line'));
        info.appendChild(el('div', 'ab-skeleton ab-skeleton-line ab-skeleton-line--short'));
        sk.appendChild(info);
        wrap.appendChild(sk);
    }
    const holder = el('div');
    holder.appendChild(wrap);
    if (note) holder.appendChild(el('p', 'ab-loading-line', note));
    return holder;
}

function errorBox(message, onRetry) {
    const box = el('div', 'ab-error');
    box.appendChild(abIconEl('alert'));
    box.appendChild(el('p', '', message));
    if (onRetry) {
        const retry = el('button', 'ab-btn ab-btn--ghost ab-btn--sm', bt('common.retry', '重试'));
        retry.type = 'button';
        retry.addEventListener('click', onRetry);
        box.appendChild(retry);
    }
    return box;
}

function paginationBar(opts) {
    const bar = el('div', 'ab-pagination');
    const prev = el('button', 'ab-page-btn');
    prev.type = 'button';
    prev.disabled = opts.page <= 1;
    prev.appendChild(abIconEl('chevron-right', 'ab-flip'));
    prev.setAttribute('aria-label', bt('common.page.prev', '上一页'));
    prev.addEventListener('click', () => opts.onPage(opts.page - 1));
    const next = el('button', 'ab-page-btn');
    next.type = 'button';
    next.disabled = opts.totalPages ? opts.page >= opts.totalPages : !opts.hasNext;
    next.appendChild(abIconEl('chevron-right'));
    next.setAttribute('aria-label', bt('common.page.next', '下一页'));
    next.addEventListener('click', () => opts.onPage(opts.page + 1));
    const info = el('span', 'ab-page-info');
    if (opts.totalPages) {
        info.textContent = bt('common.page.info', '第 {current} / {total} 页 · 共 {count} 个',
            {current: opts.page, total: opts.totalPages, count: opts.total});
    } else {
        info.textContent = bt('common.page.simple', '第 {page} 页', {page: opts.page});
    }
    bar.appendChild(prev);
    bar.appendChild(info);
    bar.appendChild(next);
    return bar;
}

function enqueueBar(opts) {
    const bar = el('div', 'ab-enqueue-bar card');
    const summary = el('span', 'ab-enqueue-summary', opts.summary || '');
    bar.appendChild(summary);
    const spacer = el('span', 'ab-enqueue-spacer');
    bar.appendChild(spacer);
    if (opts.filterSummary) {
        bar.appendChild(el('span', 'ab-pill ab-pill--brand', opts.filterSummary));
    }
    const pageBtn = el('button', 'ab-btn ab-btn--ghost ab-btn--sm', bt('common.enqueue-page', '本页入队'));
    pageBtn.type = 'button';
    pageBtn.disabled = !opts.pageEnabled;
    pageBtn.addEventListener('click', opts.onEnqueuePage);
    const allBtn = el('button', 'ab-btn ab-btn--primary ab-btn--sm', bt('common.enqueue-all', '全部入队'));
    allBtn.type = 'button';
    allBtn.disabled = !opts.allEnabled;
    allBtn.addEventListener('click', opts.onEnqueueAll);
    bar.appendChild(pageBtn);
    bar.appendChild(allBtn);
    return bar;
}

// 队列增删后统一刷新当前舞台各网格的 ✓ 标记（聚合入口，等价 syncAllResultsQueueState）
function syncAllResultsQueueState() {
    document.querySelectorAll('.ab-work[data-work-id]').forEach(card => {
        const inQueue = queueHas(card.dataset.workId);
        card.classList.toggle('in-queue', inQueue);
        const btn = card.querySelector('.ab-work-enqueue');
        if (btn) {
            btn.classList.toggle('is-queued', inQueue);
            btn.innerHTML = abIcon(inQueue ? 'check' : 'plus');
        }
    });
}

/* ============================================================
   Pixiv 内置取得请求（/api/pixiv/** 代理；失败由调用方显示）。
   ============================================================ */
async function pixivJson(path) {
    const data = await apiGet(path);
    if (data && data.error) throw new Error(String(data.error));
    return data;
}

async function fetchIllustCards(userId, ids) {
    if (!ids.length) return {items: []};
    const query = ids.map(id => 'ids[]=' + encodeURIComponent(id)).join('&');
    return pixivJson(`/api/pixiv/user/${encodeURIComponent(userId)}/illust-cards?${query}`);
}

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

const QUICK_ACTION_LABELS = {
    'my-novel-bookmarks-show': ['quick.action.novel-bookmarks-show', '我的收藏（小说，公开）'],
    'my-novel-bookmarks-hide': ['quick.action.novel-bookmarks-hide', '我的收藏（小说，不公开）'],
    'my-novels': ['quick.action.my-novels', '我自己的作品（小说）'],
    'douyin-own-works': ['douyin:quick.own-works', '我的抖音作品'],
    'douyin-liked': ['douyin:quick.liked', '喜欢的作品'],
    'douyin-favorites': ['douyin:quick.favorites', '收藏的作品'],
    'douyin-favorite-collections': ['douyin:quick.favorite-collections', '收藏夹']
};

function quickAcquisition() {
    return altAcquisition('quick', quickState.source, quickState.kind);
}

function quickActionDefs() {
    if (quickState.source === 'pixiv' && quickState.kind === 'illust') return QUICK_ACTIONS;
    const acquisition = quickAcquisition();
    if (!acquisition) return [];
    return Object.entries(acquisition.actions || {}).map(([id, descriptor]) => {
        const label = QUICK_ACTION_LABELS[id] || [id.includes(':') ? id : 'quick.action.' + id, id];
        return {
            id,
            icon: descriptor.viewType === 'collection-list' ? 'folder' : 'bookmark',
            labelKey: label[0],
            label: label[1],
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
            // ponytail: runaway guard; raise only when real accounts exceed this verified ceiling.
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
    textarea.placeholder = bt('import.placeholder',
        '粘贴作品链接或 ID，每行一个（兼容 One-Tab / N-Tab 导出格式）\nhttps://www.pixiv.net/artworks/12345678 | 标题\n87654321\nnovel:\n1234567');
    composer.appendChild(textarea);

    const help = el('details', 'ab-import-help');
    help.appendChild(el('summary', '', bt('import.format.title', '导入格式说明')));
    const list = el('ul', 'ab-note-list');
    [
        bt('import.format.line1', '每行格式：`url | title` 或 `id | title`，标题可留空（下载前自动获取真实标题）'),
        bt('import.format.line2', '仅 ID 行默认按插画解析；`artwork:` / `novel:` 区段头（单独成行）控制其后仅 ID 行的解析类型'),
        bt('import.format.line3', '兼容本页「导出全部」「导出未下载」的产物')
    ].forEach(text => {
        const li = el('li');
        li.textContent = text.replace(/`/g, '');
        list.appendChild(li);
    });
    help.appendChild(list);
    composer.appendChild(help);

    const actions = el('div', 'ab-composer-actions');
    const parseBtn = el('button', 'ab-btn ab-btn--primary');
    parseBtn.type = 'button';
    parseBtn.appendChild(abIconEl('zap'));
    parseBtn.appendChild(el('span', '', bt('import.parse', '解析并预览')));
    parseBtn.addEventListener('click', () => runImportParse());
    const importBtn = el('button', 'ab-btn ab-btn--ghost');
    importBtn.id = 'abBtnImport';
    importBtn.type = 'button';
    importBtn.appendChild(abIconEl('plus'));
    importBtn.appendChild(el('span', '', bt('import.enqueue', '导入并加入队列')));
    importBtn.addEventListener('click', () => {
        if (!importState.parsed.length) runImportParse(true);
        else commitImport(false);
    });
    const freshBtn = el('button', 'ab-btn ab-btn--danger-ghost');
    freshBtn.type = 'button';
    freshBtn.appendChild(abIconEl('refresh'));
    freshBtn.appendChild(el('span', '', bt('import.reimport', '清空队列后重新导入')));
    freshBtn.addEventListener('click', async () => {
        if (!await abConfirm('dialog.confirm-reparse', '确认清除当前队列并重新解析？')) return;
        if (!importState.parsed.length) runImportParse(true);
        else commitImport(true);
    });
    actions.appendChild(parseBtn);
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

function runImportParse(autoCommit) {
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
    if (autoCommit) commitImport(false);
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

/* ============================================================
   User 模式（画师批量下载）
   ============================================================ */
function renderUserMode(panel) {
    const def = AB_MODES[2];
    panel.appendChild(modeHeader(def, [filterButton(), settingsButton(), saveScheduleButton()].filter(Boolean)));

    const sources = altSourcesForMode('user');
    if (!sources.some(source => source.id === userState.source)) userState.source = sources[0] && sources[0].id;
    if (sources.length > 1) {
        panel.appendChild(sourceChips(sources, userState.source, source => {
            userState.source = source;
            userState.kind = (altTypesForSource('user', source)[0] || {}).type || 'illust';
            userState.rawItems = [];
            userState.items = [];
            renderStage();
        }));
    }

    const composer = el('div', 'ab-composer card');
    const row = el('div', 'ab-composer-row');
    const input = el('input', 'ab-input');
    input.id = 'abUserInput';
    input.type = 'text';
    input.placeholder = bt('user.input.placeholder', '输入用户 ID 或画师主页链接（https://www.pixiv.net/users/…）');
    input.value = userState.input || storeGet('pixiv_user_input') || '';
    input.addEventListener('keydown', e => {
        if (e.key === 'Enter') loadUserWorks(1);
    });
    const kindSeg = el('div', 'ab-seg ab-seg--sm');
    const selections = altTypesForSource('user', userState.source)
        .map(item => [item.type, altTypeLabel(item.type)]);
    const userAcquisitions = altQueueTypes().acquisitionList('user')
        .filter(item => altTypesForSource('user', userState.source).some(type => type.type === item.type));
    [['request', bt('user.kind.request', '约稿')],
        ['douyin-user-liked', bt('douyin:user.kind.liked', '喜欢的作品')]].forEach(option => {
        if (userAcquisitions.some(item => typeof item.accepts === 'function' && item.accepts(option[0]))) {
            selections.push(option);
        }
    });
    if (!selections.some(option => option[0] === userState.kind)) {
        userState.kind = selections[0] ? selections[0][0] : 'illust';
    }
    selections.forEach(([value, label]) => {
        const btn = el('button', 'ab-seg-item' + (userState.kind === value ? ' is-active' : ''), label);
        btn.type = 'button';
        btn.addEventListener('click', () => {
            userState.kind = value;
            kindSeg.querySelectorAll('.ab-seg-item').forEach(b => b.classList.remove('is-active'));
            btn.classList.add('is-active');
            if (userState.userId) loadUserWorks(1);
        });
        kindSeg.appendChild(btn);
    });
    const loadBtn = el('button', 'ab-btn ab-btn--primary');
    loadBtn.type = 'button';
    loadBtn.appendChild(abIconEl('search'));
    loadBtn.appendChild(el('span', '', bt('user.load', '读取作品')));
    loadBtn.addEventListener('click', () => loadUserWorks(1));
    row.appendChild(input);
    row.appendChild(kindSeg);
    row.appendChild(loadBtn);
    composer.appendChild(row);
    panel.appendChild(composer);

    const stage = el('div');
    stage.id = 'abUserStage';
    panel.appendChild(stage);
    renderUserStage();
}

function parseUserInput(raw) {
    const text = String(raw || '').trim();
    if (!text) return null;
    const urlMatch = text.match(/pixiv\.net\/(?:u\/)?users\/(\d+)/) || text.match(/pixiv\.net\/u\/(\d+)/);
    if (urlMatch) return urlMatch[1];
    if (/^\d+$/.test(text)) return text;
    return null;
}

async function loadUserWorks(page) {
    const input = document.getElementById('abUserInput');
    const raw = input ? input.value : userState.input;
    const acquisition = altAcquisition('user', userState.source, userState.kind);
    const userId = acquisition && acquisition.parseInput ? acquisition.parseInput(raw) : parseUserInput(raw);
    userState.input = raw;
    storeSet('pixiv_user_input', raw || '');   // 输入草稿持久化（刷新后恢复）
    if (!userId) {
        userState.error = bt('user.error.invalid', '请输入有效的用户 ID 或画师主页链接');
        renderUserStage();
        return;
    }
    userState.userId = userId;
    userState.loading = true;
    userState.error = '';
    renderUserStage();
    try {
        if (!acquisition) throw new Error(bt('queue.message.type-unavailable', '该类型当前不可用（其插件已禁用）'));
        const lease = altQueueTypes().acquisitionLease(acquisition.type, 'user');
        const variant = typeof acquisition.detectVariant === 'function'
            ? acquisition.detectVariant(raw, userState.kind) || userState.kind : userState.kind;
        const name = typeof acquisition.fetchMeta === 'function'
            ? await acquisition.fetchMeta(userId, {signal: lease.signal}) : null;
        lease.assertCurrent();
        userState.username = typeof name === 'string' && name ? name : String(userId);
        userState.pageSize = Math.max(1, Number(acquisition.pageSize) || 30);
        const context = {variant, userId, username: userState.username};
        let rawItems;
        if (typeof acquisition.fetchPage === 'function') {
            if (page === 1 || !userState.cursors) {
                userState.cursors = new Map([[1, acquisition.initialCursor ?? null]]);
            }
            const cursor = page === 1 ? acquisition.initialCursor ?? null : userState.cursors.get(page);
            if (page > 1 && cursor == null) throw new Error(bt('pagination.error.cursor-unavailable', '分页游标不可用，请重新从第一页加载'));
            const data = await acquisition.fetchPage(userId, {
                variant, signal: lease.signal, page,
                offset: (page - 1) * userState.pageSize,
                limit: userState.pageSize, cursor
            });
            lease.assertCurrent();
            rawItems = Array.isArray(data && data.items) ? data.items : [];
            userState.total = Math.max(Number(data && data.total) || 0,
                (page - 1) * userState.pageSize + rawItems.length + (data && data.hasMore ? 1 : 0));
            if (data && data.hasMore) {
                userState.cursors.set(page + 1, altNextCursor(data, cursor, true));
            }
        } else {
            const ids = await acquisition.fetchIds(userId, {variant, signal: lease.signal});
            lease.assertCurrent();
            userState.ids = (ids || []).map(String);
            userState.total = userState.ids.length;
            const pageIds = userState.ids.slice((page - 1) * userState.pageSize, page * userState.pageSize);
            const data = await altAcquisitionJson(acquisition.type, 'user', {
                endpoint: acquisition.cardsEndpoint(userId), params: {ids: pageIds}
            }, 'cards', {userId, ids: pageIds});
            rawItems = data.items || [];
        }
        userState.rawItems = normalizeAcquisitionItems(rawItems, acquisition, context, 'user');
        userState.page = page;
    } catch (e) {
        userState.ids = [];
        userState.total = 0;
        userState.rawItems = [];
        userState.page = page;
        userState.error = String(e && e.message || bt('common.request-failed', '请求失败'));
    }
    userState.loading = false;
    await applyUserFilters();
}

async function applyUserFilters() {
    const seq = ++searchState.filterSeq;
    const acquisition = altAcquisition('user', userState.source, userState.kind);
    const result = await computeFilteredItems(userState.rawItems, extraFilters,
        acquisition ? acquisition.type : userState.kind,
        () => seq !== searchState.filterSeq);
    if (!result) return;
    userState.items = result.filtered;
    userState.filterSummary = result.stats;
    renderUserStage();
}

function userFilterSummaryText() {
    const stats = userState.filterSummary;
    if (!stats || !hasExtraSearchFilter()) return '';
    const parts = [bt('user.summary.filtered', '附加筛选后 {count} 个', {count: stats.filteredCount})];
    if (stats.bookmarkMetaMissing > 0) {
        parts.push(bt('search.summary.bookmark-missing', '{count} 个收藏数不可用已排除',
            {count: stats.bookmarkMetaMissing}));
    }
    return parts.join(' · ');
}

function renderUserStage() {
    const stage = document.getElementById('abUserStage');
    if (!stage) return;
    stage.innerHTML = '';
    if (userState.error && !userState.rawItems.length) {
        stage.appendChild(errorBox(userState.error, () => loadUserWorks(1)));
        return;
    }
    if (userState.loading) {
        stage.appendChild(loadingGrid(bt('user.status.loading', '正在获取画师作品…')));
        return;
    }
    if (!userState.userId || !userState.rawItems.length) {
        const hint = el('div', 'ab-empty ab-empty--tall');
        hint.appendChild(abIconEl('user'));
        hint.appendChild(el('p', '', bt('user.hint', '输入画师 ID 或主页链接，读取其全部作品')));
        stage.appendChild(hint);
        return;
    }
    const artistCard = el('div', 'ab-artist card');
    const avatar = el('span', 'ab-avatar ab-avatar--lg');
    applyThumbHue(avatar, 'u' + userState.userId + userState.username);
    avatar.appendChild(abIconEl('user'));
    artistCard.appendChild(avatar);
    const artistMeta = el('div', 'ab-artist-meta');
    artistMeta.appendChild(el('strong', '', userState.username || userState.userId));
    artistMeta.appendChild(el('span', 'ab-muted',
        bt('user.summary.total', '共 {count} 个作品', {count: userState.total})));
    artistCard.appendChild(artistMeta);
    const profile = el('a', 'ab-btn ab-btn--ghost ab-btn--sm');
    profile.href = userState.source === 'douyin'
        ? 'https://www.douyin.com/user/' + encodeURIComponent(userState.userId)
        : 'https://www.pixiv.net/users/' + encodeURIComponent(userState.userId);
    profile.target = '_blank';
    profile.rel = 'noopener';
    profile.appendChild(abIconEl('external'));
    artistCard.appendChild(profile);
    stage.appendChild(artistCard);

    const totalPages = Math.max(1, Math.ceil(userState.total / userState.pageSize));
    stage.appendChild(enqueueBar({
        summary: bt('user.summary.page', '第 {page} 页 · 当前页 {count} 个',
            {page: userState.page, count: userState.items.length}),
        filterSummary: userFilterSummaryText(),
        pageEnabled: userState.items.length > 0,
        allEnabled: userState.total > 0,
        onEnqueuePage: () => enqueueItems(userState.items, null,
            {source: 'user', username: userState.username, authorId: userState.userId, authorName: userState.username}),
        onEnqueueAll: enqueueUserAll
    }));
    stage.appendChild(worksGrid(userState.items, {
        source: 'user',
        authorId: userState.userId, authorName: userState.username
    }));
    stage.appendChild(paginationBar({
        page: userState.page,
        totalPages,
        total: userState.total,
        onPage: p => loadUserWorks(p)
    }));
    syncAllResultsQueueState();
}

async function enqueueUserAll() {
    const filtered = hasExtraSearchFilter();
    const totalPages = Math.max(1, Math.ceil(userState.total / userState.pageSize));
    const needFetch = totalPages > 1 || filtered;
    if (needFetch && !await abConfirm('user.enqueue-all.confirm',
        '将逐页请求该画师全部 {total} 个作品条目并加入队列？', {total: userState.total})) return;

    const acquisition = altAcquisition('user', userState.source, userState.kind);
    if (!acquisition) return;
    if (typeof acquisition.fetchPage === 'function') {
        const source = userState.source;
        const kind = userState.kind;
        const userId = userState.userId;
        const username = userState.username;
        const variant = typeof acquisition.detectVariant === 'function'
            ? acquisition.detectVariant(userState.input, kind) || kind : kind;
        const lease = altQueueTypes().acquisitionLease(acquisition.type, 'user');
        const matched = [];
        let cursor = acquisition.initialCursor ?? null;
        try {
            for (let page = 1; page <= 1000; page++) {
                const context = {variant, userId, username};
                const data = await acquisition.fetchPage(userId, {
                    variant, signal: lease.signal, page,
                    offset: (page - 1) * userState.pageSize,
                    limit: userState.pageSize, cursor
                });
                lease.assertCurrent();
                if (userState.source !== source || userState.kind !== kind || userState.userId !== userId) return;
                const pageItems = normalizeAcquisitionItems(
                    Array.isArray(data && data.items) ? data.items : [], acquisition, context, 'user');
                if (filtered) {
                    const result = await computeFilteredItems(pageItems, extraFilters, acquisition.type,
                        () => userState.source !== source || userState.kind !== kind || userState.userId !== userId);
                    lease.assertCurrent();
                    if (!result) return;
                    matched.push(...result.filtered);
                } else {
                    matched.push(...pageItems);
                }
                const hasMore = !!(data && data.hasMore);
                if (!hasMore || !pageItems.length) break;
                // ponytail: runaway guard; fail atomically instead of silently enqueuing a partial account.
                if (page === 1000) {
                    throw new Error(bt('pagination.error.page-limit', '分页数量超出安全上限，未加入不完整结果'));
                }
                cursor = altNextCursor(data, cursor, hasMore);
            }
        } catch (e) {
            abToast('error', String(e && e.message || bt('common.request-failed', '请求失败')));
            return;
        }
        const added = enqueueItems(matched, null, {
            source: 'user', username: userState.username,
            authorId: userState.userId, authorName: userState.username, silent: true
        });
        abToast('success', bt('queue.toast.batch-added', '已批量加入 {count} 个作品', {count: added}));
        return;
    }
    const ids = userState.ids.slice();
    const context = {variant: userState.kind, userId: userState.userId, username: userState.username};
    const metas = ids.map(id => Object.assign({id, kind: acquisition.type},
        acquisition.buildQueueMetaFromId ? acquisition.buildQueueMetaFromId(id, context) : {}));
    const added = addItemsToQueue(ids, metas, 'user', userState.username,
        userState.userId, userState.username);
    abToast('success', bt('queue.toast.batch-added', '已批量加入 {count} 个作品', {count: added}));
}

/* ============================================================
   Search 模式
   ============================================================ */
function renderSearchMode(panel) {
    const def = AB_MODES[3];
    panel.appendChild(modeHeader(def, [filterButton(), settingsButton(), saveScheduleButton()].filter(Boolean)));

    const sources = altSourcesForMode('search');
    if (!sources.some(source => source.id === searchState.source)) searchState.source = sources[0] && sources[0].id;
    if (sources.length > 1) {
        panel.appendChild(sourceChips(sources, searchState.source, source => {
            searchState.source = source;
            searchState.kind = (altTypesForSource('search', source)[0] || {}).type || 'illust';
            searchState.rawResults = [];
            searchState.results = [];
            renderStage();
        }));
    }
    const typeOptions = altTypesForSource('search', searchState.source)
        .map(item => [item.type, altTypeLabel(item.type)]);
    if (!typeOptions.some(option => option[0] === searchState.kind)) {
        searchState.kind = typeOptions[0] ? typeOptions[0][0] : 'illust';
    }
    if (typeOptions.length > 1) {
        panel.appendChild(smallSeg(typeOptions, searchState.kind, kind => {
            searchState.kind = kind;
            if (searchState.word) runSearch(1);
        }));
    }

    if (searchState.source === 'pixiv' && !hasPixivCookie()) {
        const warn = el('div', 'ab-inline-warn');
        warn.appendChild(abIconEl('alert'));
        warn.appendChild(el('span', '', bt('search.no-cookie-warning', '未保存 Cookie：搜索结果可能减少')));
        panel.appendChild(warn);
    }

    const composer = el('div', 'ab-composer card');
    const row = el('div', 'ab-composer-row');
    const input = el('input', 'ab-input');
    input.id = 'abSearchInput';
    input.type = 'search';
    input.placeholder = bt('search.input.placeholder', '输入搜索词（标签 / 标题）');
    input.value = searchState.word || '';
    input.addEventListener('keydown', e => {
        if (e.key === 'Enter') runSearch(1);
    });
    const searchBtn = el('button', 'ab-btn ab-btn--primary');
    searchBtn.type = 'button';
    searchBtn.appendChild(abIconEl('search'));
    searchBtn.appendChild(el('span', '', bt('search.run', '搜索')));
    searchBtn.addEventListener('click', () => runSearch(1));
    row.appendChild(input);
    row.appendChild(searchBtn);
    composer.appendChild(row);

    const controls = el('div', 'ab-search-controls');
    const searchAcquisition = altAcquisition('search', searchState.source, searchState.kind);
    const contributionControls = searchAcquisition && searchAcquisition.controls || {};
    const supportsBatchRange = contributionControls.batchRange !== false
        && searchAcquisition && typeof searchAcquisition.buildRangeRequest === 'function';
    if (!supportsBatchRange && searchState.submode === 'batch') searchState.submode = 'search';
    if (contributionControls.searchMode !== false) {
        controls.appendChild(el('span', 'ab-control-label', bt('search.mode.label', '搜索方式')));
        controls.appendChild(smallSeg([
            ['s_tag', bt('search.mode.tag', '标签')],
            ['s_tc', bt('search.mode.tc', '标题/描述')]
        ], searchState.sMode, v => { searchState.sMode = v; if (searchState.word) runSearch(1); }));
    }
    if (contributionControls.order !== false) {
        controls.appendChild(el('span', 'ab-control-label', bt('search.order.label', '排序')));
        controls.appendChild(smallSeg([
            ['date_d', bt('search.order.new', '最新')],
            ['date', bt('search.order.old', '最旧')],
            ['popular_d', bt('search.order.popular', '热门')]
        ], searchState.order, v => {
            searchState.order = v;
            if (v === 'popular_d') {
                abToast('info', bt('search.order.premium-note', '热门排序需要 Pixiv Premium，未购买时将自动按最新排序'));
            }
            if (searchState.word) runSearch(1);
        }));
    }
    controls.appendChild(el('span', 'ab-control-label', bt('search.sub.label', '子模式')));
    const submodes = [['search', bt('search.sub.search', '搜索模式')]];
    if (supportsBatchRange) submodes.push(['batch', bt('search.sub.batch', '批量获取')]);
    controls.appendChild(smallSeg(submodes, searchState.submode, v => {
        searchState.submode = v;
        renderStage();
    }));
    const blurLabel = el('label', 'ab-check');
    const blurBox = el('input');
    blurBox.type = 'checkbox';
    blurBox.checked = searchState.blurR18;
    blurBox.addEventListener('change', () => {
        searchState.blurR18 = blurBox.checked;
        storeSet('pixiv_search_blur_r18', String(blurBox.checked));
        renderSearchStage();
    });
    blurLabel.appendChild(blurBox);
    blurLabel.appendChild(el('span', '', bt('search.blur-r18', '模糊 R18 缩略图')));
    if (contributionControls.r18Blur !== false) controls.appendChild(blurLabel);
    composer.appendChild(controls);

    if (searchState.submode === 'batch') {
        const batchRow = el('div', 'ab-composer-row ab-batch-range');
        batchRow.appendChild(el('span', 'ab-control-label', bt('search.batch.range', '抓取页码范围')));
        const startInput = el('input', 'ab-input ab-input--num');
        startInput.type = 'number';
        startInput.min = '1';
        startInput.value = searchState.startPage;
        startInput.addEventListener('change', () => {
            searchState.startPage = Math.max(1, parseInt(startInput.value, 10) || 1);
        });
        const endInput = el('input', 'ab-input ab-input--num');
        endInput.type = 'number';
        endInput.min = '-1';
        endInput.value = searchState.endPage;
        endInput.addEventListener('change', () => {
            searchState.endPage = parseInt(endInput.value, 10) || 1;
        });
        batchRow.appendChild(startInput);
        batchRow.appendChild(el('span', 'ab-range-dash', '—'));
        batchRow.appendChild(endInput);
        const note = el('span', 'ab-field-note ab-field-note--inline');
        note.textContent = (isAdmin
            ? bt('search.batch.end-minus-one', '结束页填 -1 = 一直翻页直到遇到已下载作品（仅管理员）')
            : '')
            + (appMode === 'multi' && multiModeLimitPage > 0
                ? ' ' + bt('search.batch.multi-limit', 'multi 模式：每次最多 {limit} 页', {limit: multiModeLimitPage})
                : '');
        batchRow.appendChild(note);
        composer.appendChild(batchRow);
    }
    panel.appendChild(composer);

    const stage = el('div');
    stage.id = 'abSearchStage';
    panel.appendChild(stage);
    renderSearchStage();
}

function smallSeg(options, current, onSelect) {
    const seg = el('div', 'ab-seg ab-seg--sm');
    options.forEach(([value, label]) => {
        const btn = el('button', 'ab-seg-item' + (current === value ? ' is-active' : ''), label);
        btn.type = 'button';
        btn.addEventListener('click', () => {
            seg.querySelectorAll('.ab-seg-item').forEach(b => b.classList.remove('is-active'));
            btn.classList.add('is-active');
            onSelect(value);
        });
        seg.appendChild(btn);
    });
    return seg;
}

// 附加筛选的内容分级映射为搜索 API 的 mode 参数（与现行页同口径）
function searchApiMode() {
    const c = extraFilters.content;
    if (c === 'safe') return 'safe';
    if (c === 'r18plus' || c === 'r18' || c === 'r18g') return 'r18';
    return 'all';
}

async function runSearch(page) {
    const input = document.getElementById('abSearchInput');
    const word = (input ? input.value : searchState.word || '').trim();
    if (!word) {
        abToast('warning', bt('search.error.empty', '请输入搜索词'));
        return;
    }
    searchState.word = word;
    searchState.loading = true;
    searchState.error = '';
    searchState.batchInfo = null;
    renderSearchStage();
    try {
        const acquisition = altAcquisition('search', searchState.source, searchState.kind);
        if (!acquisition) throw new Error(bt('queue.message.type-unavailable', '该类型当前不可用（其插件已禁用）'));
        const context = {
            word, order: searchState.order, uiMode: searchApiMode(),
            searchMode: searchState.sMode, page,
            startPage: searchState.startPage, endPage: searchState.endPage
        };
        const range = searchState.submode === 'batch';
        const builder = range ? acquisition.buildRangeRequest : acquisition.buildRequest;
        if (typeof builder !== 'function') {
            throw new Error(bt('queue.message.type-unavailable', '该类型当前不可用（其插件已禁用）'));
        }
        const spec = builder(context);
        const data = await altAcquisitionJson(acquisition.type, 'search', spec,
            range ? 'range' : 'search', context);
        searchState.rawResults = normalizeAcquisitionItems(data.items || [], acquisition, context, 'search');
        searchState.total = Number(data.total || searchState.rawResults.length);
        if (searchState.submode === 'batch') {
            searchState.batchInfo = {
                startPage: data.startPage, endPage: data.endPage,
                requestedPages: data.requestedPages, acceptedPages: data.acceptedPages,
                fetchedPages: data.fetchedPages, limitPage: data.limitPage
            };
            searchState.localPage = 1;
        } else {
            searchState.page = Number(data.page || page);
        }
    } catch (e) {
        searchState.rawResults = [];
        searchState.total = 0;
        searchState.page = page;
        searchState.error = String(e && e.message || bt('common.request-failed', '请求失败'));
    }
    searchState.loading = false;
    await applySearchFilters();
}

async function applySearchFilters() {
    const seq = ++searchState.filterSeq;
    const acquisition = altAcquisition('search', searchState.source, searchState.kind);
    const result = await computeFilteredItems(searchState.rawResults, extraFilters,
        acquisition ? acquisition.type : searchState.kind,
        () => seq !== searchState.filterSeq);
    if (!result) return;
    searchState.results = result.filtered;
    searchState.filterSummary = result.stats;
    renderSearchStage();
}

function searchSummaryText() {
    const parts = [];
    if (searchState.batchInfo) {
        const info = searchState.batchInfo;
        parts.push(bt('search.summary.batch-range', '抓取第 {start}–{end} 页',
            {start: info.startPage, end: info.endPage}));
        if (info.limitPage) {
            parts.push(bt('search.summary.batch-limited', '受限至第 {page} 页', {page: info.limitPage}));
        }
        parts.push(bt('search.summary.batch-fetched', '本批 {count} 个', {count: searchState.filterSummary ? searchState.filterSummary.rawCount : searchState.rawResults.length}));
    } else {
        parts.push(bt('search.summary.page', '第 {page} 页 · 当前页 {count} 个',
            {page: searchState.page, count: searchState.results.length}));
    }
    if (hasExtraSearchFilter() && searchState.filterSummary) {
        parts.push(bt('search.summary.filtered-count', '筛选后 {count} 个',
            {count: searchState.filterSummary.filteredCount}));
        if (searchState.filterSummary.bookmarkMetaMissing > 0) {
            parts.push(bt('search.summary.bookmark-missing', '{count} 个收藏数不可用已排除',
                {count: searchState.filterSummary.bookmarkMetaMissing}));
        }
    }
    parts.push(bt('search.summary.total', '共 {count} 个', {count: searchState.total}));
    return summaryJoin(parts);
}

function renderSearchStage() {
    const stage = document.getElementById('abSearchStage');
    if (!stage) return;
    stage.innerHTML = '';
    if (searchState.loading) {
        stage.appendChild(loadingGrid(bt('common.loading', '加载中…')));
        return;
    }
    if (!searchState.word) {
        const hint = el('div', 'ab-empty ab-empty--tall');
        hint.appendChild(abIconEl('search'));
        hint.appendChild(el('p', '', bt('search.hint', '输入关键词开始搜索 Pixiv 作品')));
        stage.appendChild(hint);
        return;
    }
    if (searchState.error) {
        stage.appendChild(errorBox(searchState.error, () => runSearch(searchState.page || 1)));
        return;
    }
    const localPageSize = 60;
    const visibleResults = searchState.submode === 'batch'
        ? searchState.results.slice((searchState.localPage - 1) * localPageSize,
            searchState.localPage * localPageSize)
        : searchState.results;
    stage.appendChild(enqueueBar({
        summary: searchSummaryText(),
        pageEnabled: visibleResults.length > 0,
        allEnabled: searchState.results.length > 0,
        onEnqueuePage: () => enqueueItems(visibleResults, null, {source: 'search'}),
        onEnqueueAll: () => enqueueItems(searchState.results, null, {source: 'search'})
    }));
    if (!searchState.results.length) {
        const empty = el('div', 'ab-empty');
        empty.appendChild(abIconEl('search'));
        empty.appendChild(el('p', '', bt('status.search-no-results', '无搜索结果')));
        stage.appendChild(empty);
        return;
    }
    stage.appendChild(worksGrid(visibleResults, {
        source: 'search', blurR18: searchState.blurR18
    }));
    if (searchState.submode !== 'batch') {
        const acquisition = altAcquisition('search', searchState.source, searchState.kind);
        const totalPages = Math.max(1, Math.ceil(searchState.total / Math.max(1, Number(acquisition && acquisition.pageSize) || 60)));
        stage.appendChild(paginationBar({
            page: searchState.page,
            totalPages,
            total: searchState.total,
            onPage: p => runSearch(p)
        }));
    } else {
        // 批量子模式：本地分页浏览已抓取结果
        const totalPages = Math.max(1, Math.ceil(searchState.results.length / localPageSize));
        stage.appendChild(paginationBar({
            page: searchState.localPage,
            totalPages,
            total: searchState.results.length,
            onPage: p => {
                searchState.localPage = p;
                renderSearchStage();
            }
        }));
    }
    syncAllResultsQueueState();
}

/* ============================================================
   系列下载模式
   ============================================================ */
function renderSeriesMode(panel) {
    const def = AB_MODES[4];
    panel.appendChild(modeHeader(def, [filterButton(), settingsButton(), saveScheduleButton()].filter(Boolean)));

    const sources = altSourcesForMode('series');
    if (!sources.some(source => source.id === seriesState.source)) seriesState.source = sources[0] && sources[0].id;
    if (sources.length > 1) {
        panel.appendChild(sourceChips(sources, seriesState.source, source => {
            seriesState.source = source;
            seriesState.kind = (altTypesForSource('series', source)[0] || {}).type || 'illust';
            seriesState.info = null;
            seriesState.rawItems = [];
            renderStage();
        }));
    }
    const typeOptions = altTypesForSource('series', seriesState.source)
        .map(item => [item.type, altTypeLabel(item.type)]);
    if (!typeOptions.some(option => option[0] === seriesState.kind)) {
        seriesState.kind = typeOptions[0] ? typeOptions[0][0] : 'illust';
    }
    if (typeOptions.length > 1) {
        panel.appendChild(smallSeg(typeOptions, seriesState.kind, kind => {
            seriesState.kind = kind;
            seriesState.info = null;
            seriesState.rawItems = [];
            renderSeriesStage();
        }));
    }

    const composer = el('div', 'ab-composer card');
    const row = el('div', 'ab-composer-row');
    const input = el('input', 'ab-input');
    input.id = 'abSeriesInput';
    input.type = 'text';
    input.placeholder = bt('series.input.placeholder', '粘贴系列 / 合集 / 关联作品链接');
    input.value = seriesState.url || '';
    input.addEventListener('keydown', e => {
        if (e.key === 'Enter') loadSeries(1);
    });
    const loadBtn = el('button', 'ab-btn ab-btn--primary');
    loadBtn.type = 'button';
    loadBtn.appendChild(abIconEl('layers'));
    loadBtn.appendChild(el('span', '', bt('series.load', '读取系列')));
    loadBtn.addEventListener('click', () => loadSeries(1));
    row.appendChild(input);
    row.appendChild(loadBtn);
    composer.appendChild(row);
    const browserAcquisition = altQueueTypes().acquisitionList('series')
        .find(item => item.browser
            && altTypesForSource('series', seriesState.source).some(type => type.type === item.type));
    if (browserAcquisition) {
        const browseBtn = el('button', 'ab-btn ab-btn--ghost ab-btn--sm');
        browseBtn.type = 'button';
        browseBtn.appendChild(abIconEl('folder'));
        browseBtn.appendChild(el('span', '', bt('series.browser.open', '浏览可用合集')));
        browseBtn.addEventListener('click', () => openSeriesBrowser(browserAcquisition));
        const actions = el('div', 'ab-composer-actions');
        actions.appendChild(browseBtn);
        composer.appendChild(actions);
    }
    panel.appendChild(composer);

    const stage = el('div');
    stage.id = 'abSeriesStage';
    panel.appendChild(stage);
    renderSeriesStage();
}

// 解析系列链接：插画系列 / 小说系列 / 关联作品（取其 seriesId）
function parseSeriesUrl(raw) {
    const text = String(raw || '').trim();
    if (!text) return null;
    let m = text.match(/pixiv\.net\/user\/\d+\/series\/(\d+)/) || text.match(/pixiv\.net\/series\/(\d+)/);
    if (m) return {kind: 'illust', id: m[1]};
    m = text.match(/pixiv\.net\/novel\/series\/(\d+)/);
    if (m) return {kind: 'novel', id: m[1]};
    m = text.match(/pixiv\.net\/novel\/show\.php\?[^\s]*?series_id=(\d+)/);
    if (m) return {kind: 'novel', id: m[1]};
    if (/^\d+$/.test(text)) return {kind: seriesState.kind || 'illust', id: text};
    return null;
}

async function openSeriesBrowser(acquisition, cursor) {
    const browser = acquisition.browser;
    const body = el('div', 'ab-collection-grid');
    body.appendChild(el('p', 'ab-loading-line', browser.loadingLabel()));
    openDrawer({
        id: 'series-browser', icon: 'folder', title: browser.title(), body, footer: null
    });
    try {
        const context = {cursor: cursor ?? browser.initialCursor, limit: browser.pageSize};
        const data = await altAcquisitionJson(acquisition.type, 'series',
            browser.buildPageRequest(context), 'browser', context);
        const page = browser.readPage(data);
        body.innerHTML = '';
        if (!page.items.length) {
            body.appendChild(el('p', 'ab-empty-line', browser.emptyLabel()));
            return;
        }
        page.items.forEach(item => {
            const button = el('button', 'ab-collection-card card');
            button.type = 'button';
            button.appendChild(abIconEl('folder'));
            button.appendChild(el('span', 'ab-collection-meta', browser.itemLabel(item)));
            button.addEventListener('click', () => {
                const selected = browser.select(item);
                seriesState.source = acquisition.dataSource.id;
                seriesState.kind = acquisition.type;
                seriesState.url = String(selected.seriesId);
                seriesState.browserSelection = {type: acquisition.type, parsed: selected};
                closeDrawer();
                renderStage();
                loadSeries(1);
            });
            body.appendChild(button);
        });
        if (page.hasMore && page.nextCursor != null) {
            const more = el('button', 'ab-btn ab-btn--ghost', bt('common.next', '下一页'));
            more.type = 'button';
            more.addEventListener('click', () => openSeriesBrowser(acquisition, page.nextCursor));
            body.appendChild(more);
        }
    } catch (e) {
        body.replaceChildren(errorBox(String(e && e.message || bt('common.request-failed', '请求失败')),
            () => openSeriesBrowser(acquisition, cursor)));
    }
}

async function loadSeries(page) {
    const input = document.getElementById('abSeriesInput');
    const raw = input ? input.value : seriesState.url;
    seriesState.url = raw;
    const candidates = altQueueTypes().acquisitionList('series')
        .filter(item => altTypesForSource('series', seriesState.source).some(type => type.type === item.type));
    const selected = seriesState.browserSelection && seriesState.browserSelection.type === seriesState.kind
        && String(seriesState.browserSelection.parsed.seriesId) === String(raw)
        ? {acquisition: candidates.find(item => item.type === seriesState.kind), parsed: seriesState.browserSelection.parsed}
        : null;
    const matches = selected && selected.acquisition ? [selected] : candidates.map(acquisition => {
        try {
            const parsed = acquisition.parseUrl(raw);
            return parsed ? {acquisition, parsed} : null;
        } catch (e) {
            return null;
        }
    }).filter(Boolean);
    if (matches.length !== 1) {
        seriesState.error = bt('series.status.no-url', '请先粘贴有效的系列链接');
        renderSeriesStage();
        return;
    }
    const acquisition = matches[0].acquisition;
    const parsed = matches[0].parsed;
    seriesState.kind = acquisition.type;
    seriesState.loading = true;
    seriesState.error = '';
    renderSeriesStage();
    try {
        const lease = altQueueTypes().acquisitionLease(acquisition.type, 'series');
        let seriesId = parsed.seriesId ?? parsed.id;
        if (seriesId == null && parsed.resolveWorkId != null && typeof acquisition.resolveSeriesId === 'function') {
            seriesId = await acquisition.resolveSeriesId(parsed.resolveWorkId, {signal: lease.signal});
            lease.assertCurrent();
        }
        if (seriesId == null) throw new Error(bt('series.status.no-url', '请先粘贴有效的系列链接'));
        if (page === 1) seriesState.cursor = acquisition.initialCursor ? acquisition.initialCursor(seriesId) : null;
        const context = {
            seriesId, seriesTitle: seriesState.info && seriesState.info.title || '', page,
            cursor: seriesState.cursor, limit: Number(acquisition.pageSize) || 12
        };
        const spec = acquisition.apiPath(seriesId, page, context);
        let data = await altAcquisitionJson(acquisition.type, 'series', spec, 'page', context);
        if (typeof acquisition.normalizePage === 'function') data = acquisition.normalizePage(data, context);
        const info = data.series || {};
        seriesState.info = Object.assign({}, info, {
            seriesId,
            title: info.title || context.seriesTitle || String(seriesId),
            total: Number(info.total ?? data.total ?? 0)
        });
        const queueContext = {
            seriesId, seriesTitle: seriesState.info.title,
            seriesAuthorId: seriesState.info.authorId,
            seriesAuthorName: seriesState.info.authorName
        };
        seriesState.rawItems = normalizeAcquisitionItems(data.items || [], acquisition, queueContext, 'series');
        seriesState.page = Number(data.page || page);
        seriesState.isLastPage = data.isLastPage === true || data.hasMore === false;
        seriesState.cursor = data.nextCursor == null ? null : String(data.nextCursor);
    } catch (e) {
        seriesState.info = null;
        seriesState.rawItems = [];
        seriesState.page = page;
        seriesState.isLastPage = true;
        seriesState.error = String(e && e.message || bt('common.request-failed', '请求失败'));
    }
    seriesState.loading = false;
    await applySeriesFilters();
}

async function applySeriesFilters() {
    const seq = ++searchState.filterSeq;
    const result = await computeFilteredItems(seriesState.rawItems, extraFilters, seriesState.kind,
        () => seq !== searchState.filterSeq);
    if (!result) return;
    seriesState.items = result.filtered;
    seriesState.filterSummary = result.stats;
    renderSeriesStage();
}

function renderSeriesStage() {
    const stage = document.getElementById('abSeriesStage');
    if (!stage) return;
    stage.innerHTML = '';
    if (seriesState.loading) {
        stage.appendChild(loadingGrid(bt('series.status.loading-page', '正在加载该页…')));
        return;
    }
    if (seriesState.error && !seriesState.rawItems.length) {
        stage.appendChild(errorBox(seriesState.error, () => loadSeries(1)));
        return;
    }
    if (!seriesState.info) {
        const hint = el('div', 'ab-empty ab-empty--tall');
        hint.appendChild(abIconEl('layers'));
        hint.appendChild(el('p', '', bt('series.status.no-url', '请先粘贴系列链接')));
        stage.appendChild(hint);
        return;
    }
    const info = seriesState.info;
    const head = el('div', 'ab-series-head card');
    const cover = el('span', 'ab-series-cover');
    applyThumbHue(cover, 's' + String(info.seriesId) + (info.title || ''));
    cover.appendChild(abIconEl('layers'));
    head.appendChild(cover);
    const meta = el('div', 'ab-series-meta');
    meta.appendChild(el('strong', 'ab-series-title', info.title || String(info.seriesId)));
    const sub = el('span', 'ab-muted');
    sub.textContent = summaryJoin([
        altTypeLabel(seriesState.kind),
        bt('series.info.author', '作者：{name}', {name: info.authorName || info.authorId || '-'}),
        bt('series.info.total', '共 {count} 个作品', {count: info.total ?? seriesState.rawItems.length})
    ]);
    meta.appendChild(sub);
    head.appendChild(meta);
    stage.appendChild(head);

    if (!seriesState.rawItems.length) {
        const empty = el('div', 'ab-empty');
        empty.appendChild(abIconEl('layers'));
        empty.appendChild(el('p', '', bt('series.status.empty', '该系列没有可用条目')));
        stage.appendChild(empty);
        return;
    }

    const filterSummary = (seriesState.filterSummary && hasExtraSearchFilter())
        ? bt('user.summary.filtered', '附加筛选后 {count} 个', {count: seriesState.filterSummary.filteredCount})
        : '';
    stage.appendChild(enqueueBar({
        summary: bt('series.info.page', '第 {current} 页{last}', {
            current: seriesState.page,
            last: seriesState.isLastPage ? '' : ''
        }),
        filterSummary,
        pageEnabled: seriesState.items.length > 0,
        allEnabled: seriesState.items.length > 0,
        onEnqueuePage: () => enqueueSeriesItems(seriesState.items),
        onEnqueueAll: enqueueSeriesAll
    }));
    stage.appendChild(worksGrid(seriesState.items, {
        source: 'series',
        seriesId: info.seriesId,
        seriesTitle: info.title,
        emptyText: bt('series.status.empty', '该系列没有可用条目')
    }));
    stage.appendChild(paginationBar({
        page: seriesState.page,
        totalPages: 0,
        total: info.total || 0,
        hasNext: !seriesState.isLastPage,
        onPage: p => loadSeries(p)
    }));
    syncAllResultsQueueState();
}

function enqueueSeriesItems(items) {
    return enqueueItems(items, null, {
        source: 'series',
        seriesId: seriesState.info.seriesId,
        seriesTitle: seriesState.info.title
    });
}

async function enqueueSeriesAll() {
    if (!await abConfirm('series.enqueue-all.confirm',
        '将抓取并加入该系列全部作品（已加载第 {page} 页）？', {page: seriesState.page})) return;
    const acquisition = altAcquisition('series', seriesState.source, seriesState.kind);
    if (!acquisition || !seriesState.info) return;
    const seriesId = seriesState.info.seriesId;
    const all = [];
    let cursor = typeof acquisition.initialCursor === 'function'
        ? acquisition.initialCursor(seriesId) : null;
    for (let page = 1; page <= 100; page++) {
        const context = {
            seriesId, seriesTitle: seriesState.info.title || '', page, cursor,
            limit: Number(acquisition.pageSize) || 12
        };
        let data;
        try {
            data = await altAcquisitionJson(acquisition.type, 'series',
                acquisition.apiPath(seriesId, page, context), 'page', context);
            if (typeof acquisition.normalizePage === 'function') data = acquisition.normalizePage(data, context);
        } catch (e) {
            abToast('error', String(e && e.message || bt('common.request-failed', '请求失败')));
            return;
        }
        all.push(...normalizeAcquisitionItems(data.items || [], acquisition, {
            seriesId, seriesTitle: seriesState.info.title,
            seriesAuthorId: seriesState.info.authorId,
            seriesAuthorName: seriesState.info.authorName
        }, 'series'));
        if (data.isLastPage === true || data.hasMore === false || !(data.items || []).length) break;
        // ponytail: runaway guard; fail atomically instead of silently enqueuing a partial series.
        if (page === 100) {
            abToast('error', bt('pagination.error.page-limit', '分页数量超出安全上限，未加入不完整结果'));
            return;
        }
        if (data.hasMore === true) cursor = altNextCursor(data, cursor, true);
    }
    const added = enqueueSeriesItems(all.length ? all : seriesState.items);
    abToast('success', bt('queue.toast.batch-added', '已批量加入 {count} 个作品', {count: added}));
}

/* ============================================================
   筛选变更 → 当前模式预览实时重滤
   ============================================================ */
async function applyFiltersToCurrentMode() {
    if (state.mode === QUICK_FETCH_MODE && quickState.rawItems.length) {
        await applyQuickFilters();
    } else if (state.mode === 'user' && userState.rawItems.length) {
        await applyUserFilters();
    } else if (state.mode === 'series' && seriesState.rawItems.length) {
        await applySeriesFilters();
    } else if (state.mode === 'search' && searchState.rawResults.length) {
        await applySearchFilters();
    }
}

window.PixivBatchAlt.modes = Object.assign(window.PixivBatchAlt.modes, {
    renderRail, switchMode, renderStage, fetchExtensions, acquisitionSources,
    syncAllResultsQueueState, enqueueItems, buildQueueMeta, pixivCancelWorkKey,
    refreshQuickCredentialGate, applyFiltersToCurrentMode, pixivJson, parseSeriesUrl,
    workCard, worksGrid, paginationBar, enqueueBar, errorBox, loadingGrid
});
