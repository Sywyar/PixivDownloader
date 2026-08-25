'use strict';
/* ============================================================
   alt-modes — 模式导轨 + 舞台框架 + Pixiv 请求适配
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
    // 舞台重建后槽位锚点（如 import-hint）随之重建，经共享 renderSlots 重挂插件贡献片段。
    refreshAltSlots();
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
