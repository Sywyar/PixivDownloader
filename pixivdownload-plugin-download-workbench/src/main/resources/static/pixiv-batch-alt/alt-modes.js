'use strict';
/* ============================================================
   alt-modes — 模式导轨 + 舞台框架 + Pixiv 请求适配
   取得请求复用生产页扩展运行时；入队走 alt-queue 的统一队列模型。
   ============================================================ */

const AB_MODES = [
    {id: QUICK_FETCH_MODE, icon: 'zap', titleKey: 'modes.quick', titleFallback: '我的 Pixiv',
        descKey: 'modes.quick.desc', descFallback: '收藏、关注与珍藏集，喜欢的作品都在这里。'},
    {id: SINGLE_IMPORT_MODE, icon: 'clipboard', titleKey: 'modes.import', titleFallback: '链接导入',
        descKey: 'modes.import.desc', descFallback: '粘贴作品链接或 ID，开始新的下载。'},
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
    [AB_MODES[1], AB_MODES[0], ...AB_MODES.slice(2)].forEach(mode => {
        if (mode.adminOnly) return;
        const btn = el('button', 'ab-rail-item' + (state.mode === mode.id ? ' is-active' : ''));
        btn.type = 'button';
        btn.setAttribute('role', 'tab');
        btn.id = 'abMode-' + mode.id;
        btn.setAttribute('aria-controls', 'abModePanel');
        btn.tabIndex = state.mode === mode.id ? 0 : -1;
        btn.setAttribute('aria-selected', state.mode === mode.id ? 'true' : 'false');
        btn.dataset.mode = mode.id;
        btn.appendChild(abIconEl(mode.icon));
        btn.appendChild(el('span', 'ab-rail-label', bt(mode.titleKey, mode.titleFallback)));
        btn.addEventListener('click', () => switchMode(mode.id));
        btn.addEventListener('keydown', event => {
            const tabs = Array.from(rail.querySelectorAll('[role="tab"]'));
            const index = tabs.indexOf(btn);
            const next = event.key === 'Home' ? 0 : event.key === 'End' ? tabs.length - 1
                : ['ArrowRight', 'ArrowDown'].includes(event.key) ? (index + 1) % tabs.length
                : ['ArrowLeft', 'ArrowUp'].includes(event.key) ? (index + tabs.length - 1) % tabs.length : -1;
            if (next < 0) return;
            event.preventDefault();
            tabs[next].click();
            tabs[next].focus();
        });
        rail.appendChild(btn);
    });
    const scheduleTab = document.getElementById('abScheduleTab');
    if (scheduleTab) scheduleTab.hidden = !isAdmin;
}

const modeDrafts = new Map();
let lastAcquisitionMode = SINGLE_IMPORT_MODE;

function rememberModeDraft(panel) {
    if (!panel || !panel.dataset.mode) return;
    const fields = Array.from(panel.querySelectorAll('input[id], textarea[id]'))
        .filter(field => !field.closest('[data-schedule-editor]')
            && !['password', 'file', 'checkbox', 'radio', 'hidden', 'button', 'submit'].includes(field.type))
        .map(field => ({id: field.id, value: field.value, checked: field.checked,
            start: field.selectionStart, end: field.selectionEnd}));
    modeDrafts.set(panel.dataset.mode, {fields, scroll: dockState.open ? acquisitionScroll : window.scrollY,
        focus: panel.contains(document.activeElement) ? document.activeElement.id : null});
}

function restoreModeDraft(panel, restoreFocus) {
    const draft = modeDrafts.get(state.mode);
    if (!draft) return;
    draft.fields.forEach(saved => {
        const field = document.getElementById(saved.id);
        if (!field || !panel.contains(field)) return;
        if (field.tagName !== 'SELECT' || Array.from(field.options).some(option => option.value === saved.value)) {
            field.value = saved.value;
        }
        if (field.type === 'checkbox' || field.type === 'radio') field.checked = saved.checked;
        if (saved.start != null && typeof field.setSelectionRange === 'function') {
            field.setSelectionRange(saved.start, saved.end);
        }
    });
    if (restoreFocus && draft.focus) document.getElementById(draft.focus)?.focus({preventScroll: true});
    if (!dockState.open) window.scrollTo({top: draft.scroll, behavior: 'instant'});
}

function switchMode(mode) {
    let normalized = mode;
    if (normalized === 'schedule' && !isAdmin) normalized = QUICK_FETCH_MODE;
    if (!AB_MODES.some(item => item.id === normalized)) normalized = QUICK_FETCH_MODE;
    if (scheduleState.editing?.mode && normalized !== scheduleState.editing.mode) {
        cancelScheduleEdit(normalized);
        return;
    }
    if (normalized !== 'schedule') lastAcquisitionMode = normalized;
    const changed = state.mode !== normalized;
    if (changed) rememberModeDraft(document.getElementById('abModePanel'));
    state.mode = normalized;
    storeSet('pixiv_mode', normalized);
    document.querySelectorAll('#abRailModes .ab-rail-item').forEach(btn => {
        const active = btn.dataset.mode === normalized;
        btn.classList.toggle('is-active', active);
        btn.setAttribute('aria-selected', active ? 'true' : 'false');
        btn.tabIndex = active ? 0 : -1;
    });
    toggleDock(false);
    if (changed) {
        renderStage();
        animateWorkspace(document.getElementById('abModePanel'));
    }
    if (changed && normalized === 'schedule') {
        enterScheduleMode();
    }
}

function renderStage() {
    const panel = document.getElementById('abModePanel');
    if (!panel) return;
    const restoreFocus = panel.dataset.mode === state.mode && panel.contains(document.activeElement);
    if (panel.dataset.mode === state.mode) rememberModeDraft(panel);
    if (panel.dataset.mode === 'schedule') {
        scheduleState.expandedQueues.forEach(id => scheduleQueueVue()?.unmountScheduleQueue?.(id));
        if (state.mode !== 'schedule') stopSchedulePolling();
    }
    panel.innerHTML = '';
    const mode = state.mode;
    panel.dataset.mode = mode;
    panel.setAttribute('aria-labelledby', mode === 'schedule' ? 'abScheduleTab' : 'abMode-' + mode);
    if (mode === QUICK_FETCH_MODE) renderQuickMode(panel);
    else if (mode === SINGLE_IMPORT_MODE) renderImportMode(panel);
    else if (mode === 'user') renderUserMode(panel);
    else if (mode === 'search') renderSearchMode(panel);
    else if (mode === 'series') renderSeriesMode(panel);
    else if (mode === 'schedule') renderScheduleMode(panel);
    mountScheduleEdit(panel);
    hydrateIcons(panel);
    if (pageI18n) pageI18n.apply(panel);
    // 舞台重建后槽位锚点（如 import-hint）随之重建，经共享 renderSlots 重挂插件贡献片段。
    refreshAltSlots();
    restoreModeDraft(panel, restoreFocus);
    syncWorkspaceNavigation();
    syncFilterButtonBadge();
    syncWorkSelection();
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
    const heading = el('div', 'ab-mode-heading');
    heading.appendChild(head);
    if ((actions || []).some(action => action.id === 'abFilterBtn')) {
        const filters = el('div', 'ab-active-filters');
        filters.dataset.activeFilters = '1';
        heading.appendChild(filters);
    }
    return heading;
}

function filterButton(id = 'abFilterBtn') {
    const btn = el('button', 'ab-btn ab-btn--ghost ab-btn--sm');
    btn.id = id;
    btn.type = 'button';
    btn.appendChild(abIconEl('filter'));
    const label = el('span', '', bt('filters.title', '附加筛选'));
    label.setAttribute('data-i18n', 'batch-alt:filters.title');
    btn.appendChild(label);
    const badge = el('span', 'ab-badge');
    badge.dataset.filterBadge = '1';
    badge.hidden = true;
    btn.appendChild(badge);
    btn.addEventListener('click', openFiltersDrawer);
    return btn;
}

function settingsButton(id = 'abSettingsBtn') {
    const btn = el('button', 'ab-btn ab-btn--ghost ab-btn--sm');
    btn.id = id;
    btn.type = 'button';
    btn.appendChild(abIconEl('sliders'));
    const label = el('span', '', bt('settings.title', '下载设置'));
    label.setAttribute('data-i18n', 'batch-alt:settings.title');
    btn.appendChild(label);
    btn.addEventListener('click', openSettingsDrawer);
    return btn;
}

function saveScheduleButton() {
    if (!isAdmin || scheduleState.editing) return null;
    let preview = null;
    try { preview = altScheduleSources()?.previewForMode(state.mode, altScheduleSourceContext()); } catch { /* 来源不可用 */ }
    if (!preview && state.mode !== QUICK_FETCH_MODE) return null;
    const btn = el('button', 'ab-btn ab-btn--ghost ab-btn--sm');
    btn.type = 'button';
    btn.appendChild(abIconEl('clock'));
    btn.appendChild(el('span', '', bt('schedule.editor.save', '存为计划任务')));
    btn.disabled = !preview;
    if (!preview) {
        const hint = bt('schedule.save.quick-hint',
            '请先展开具体的作品列表（点开收藏 / 我的作品 / 关注新作，或点进某个画师 / 珍藏集）后再创建计划任务');
        btn.title = hint;
        const group = el('div', 'ab-field');
        group.append(btn, el('p', 'ab-field-note', hint));
        return group;
    }
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
    bindWorkSelection(card, item, kind, options);
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
    if (item.isOriginal) badges.appendChild(el('span', 'ab-mini-badge', bt('card.original', '原创')));
    thumbWrap.appendChild(badges);

    const enqueueBtn = el('button', 'ab-work-enqueue');
    enqueueBtn.type = 'button';
    enqueueBtn.setAttribute('aria-pressed', 'false');
    enqueueBtn.setAttribute('aria-label', bt('card.enqueue', '直接加入队列'));
    enqueueBtn.title = bt('card.enqueue', '直接加入队列');
    enqueueBtn.hidden = workInteractionMode === 'direct';
    enqueueBtn.appendChild(abIconEl('plus'));
    enqueueBtn.addEventListener('click', event => {
        event.stopPropagation();
        toggleWorkInQueue(item, kind, options);
    });
    card.appendChild(thumbWrap);

    const info = el('div', 'ab-work-info');
    const title = el('div', 'ab-work-title',
        item.title || bt('card.untitled', '作品 {id}', {id: item.id}));
    const authorName = item.userName || item.authorName || '';
    title.title = authorName
        ? bt('card.title-tip', '{title}（{author}）', {title: title.textContent, author: authorName})
        : title.textContent;
    const caption = el('div', 'ab-work-caption');
    caption.appendChild(title);
    if (authorName) caption.appendChild(el('div', 'ab-work-author', authorName));
    const metadata = [];
    const words = Number(item.wordCount ?? item.textLength ?? 0);
    if (words > 0) metadata.push(bt('card.words', '{count} 字', {count: words.toLocaleString(uiLang() || undefined)}));
    const bookmarks = getSearchBookmarkCount(item, kind);
    if (bookmarks !== null) metadata.push(bt('batch:search.summary.bookmark-badge', '收藏 {count}',
        {count: bookmarks.toLocaleString(uiLang() || undefined)}));
    if (metadata.length) caption.appendChild(el('div', 'ab-work-meta', summaryJoin(metadata)));
    const details = [];
    if (item.uploadTimestamp) {
        const uploaded = new Date(Number(item.uploadTimestamp));
        if (!Number.isNaN(uploaded.getTime())) details.push(uploaded.toLocaleDateString(uiLang() || undefined));
    }
    if (Number(item.readingTimeSeconds) > 0) details.push(bt('card.reading-minutes', '约 {count} 分钟',
        {count: Math.ceil(Number(item.readingTimeSeconds) / 60)}));
    if (Array.isArray(item.tags)) details.push(...item.tags.map(tag => typeof tag === 'string' ? tag : tag?.name).filter(Boolean));
    if (details.length) {
        const disclosure = el('details', 'ab-work-details');
        const summary = el('summary', '', bt('card.details', '作品信息'));
        disclosure.append(summary, el('p', '', summaryJoin(details)));
        caption.appendChild(disclosure);
    }
    info.appendChild(caption);
    info.appendChild(enqueueBtn);
    card.appendChild(info);

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
                ? acquisition.buildQueueMeta(item, item.seriesOrder || ((context?.orderOffset || 0) + index + 1), context || {})
                : acquisition.buildQueueMeta(item, context || {})
            : {};
        return Object.assign({}, item, queueMeta || {}, {
            id: String(queueId),
            __acquisitionId: String(item.id),
            kind: acquisition.type,
            __queueMeta: queueMeta || {}
        });
    });
}

const collapsedPreviews = new Set();

function previewKey(opts) {
    return String(opts?.previewKey || state.mode);
}

function bindPreview(node, opts, isGrid) {
    const key = previewKey(opts);
    node.dataset.previewKey = key;
    node.hidden = collapsedPreviews.has(key);
    if (isGrid) node.id = 'abPreview-' + encodeURIComponent(key);
    return node;
}

function worksGrid(items, opts) {
    const options = opts || {};
    const grid = el('div', 'ab-grid' + (items.length && items.every(item => (options.kind || item.kind) === 'novel') ? ' ab-grid--novels' : ''));
    if (!items.length) {
        const empty = el('div', 'ab-empty');
        empty.appendChild(abIconEl('image'));
        empty.appendChild(el('p', '', options.emptyText || bt('common.empty.works', '该范围内没有作品')));
        const holder = el('div', 'ab-grid-empty');
        holder.appendChild(empty);
        return bindPreview(holder, options, true);
    }
    items.forEach((item, idx) => {
        grid.appendChild(workCard(item, Object.assign({}, options, {index: idx})));
    });
    return bindPreview(grid, options, true);
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
    if (opts.totalPages > 1) {
        const first = el('button', 'ab-btn ab-btn--ghost ab-btn--sm', bt('common.page.first', '第一页'));
        first.type = 'button';
        first.disabled = opts.page <= 1;
        first.addEventListener('click', () => opts.onPage(1));
        const last = el('button', 'ab-btn ab-btn--ghost ab-btn--sm', bt('common.page.last', '最后一页'));
        last.type = 'button';
        last.disabled = opts.page >= opts.totalPages;
        last.addEventListener('click', () => opts.onPage(opts.totalPages));
        const jump = el('form', 'ab-page-jump');
        const page = el('input', 'ab-input');
        page.type = 'number';
        page.min = '1';
        page.max = String(opts.totalPages);
        page.step = '1';
        page.required = true;
        page.value = String(opts.page);
        page.setAttribute('aria-label', bt('common.page.number', '页码'));
        const go = el('button', 'ab-btn ab-btn--ghost ab-btn--sm', bt('common.page.go', '跳转'));
        go.type = 'submit';
        jump.addEventListener('submit', event => {
            event.preventDefault();
            const target = Number(page.value);
            if (Number.isInteger(target) && target >= 1 && target <= opts.totalPages && target !== opts.page) {
                opts.onPage(target);
            }
        });
        jump.appendChild(page);
        jump.appendChild(go);
        bar.insertBefore(first, prev);
        bar.appendChild(last);
        bar.appendChild(jump);
    }
    return bindPreview(bar, opts, false);
}

function enqueueBar(opts) {
    const bar = el('div', 'ab-enqueue-bar');
    const summary = el('span', 'ab-enqueue-summary', opts.summary || '');
    bar.appendChild(summary);
    const spacer = el('span', 'ab-enqueue-spacer');
    bar.appendChild(spacer);
    if (opts.filterSummary) {
        bar.appendChild(el('span', 'ab-pill ab-pill--brand', opts.filterSummary));
    }
    const key = previewKey(opts);
    const collapse = el('button', 'ab-btn ab-btn--ghost ab-btn--sm ab-preview-toggle');
    collapse.type = 'button';
    collapse.setAttribute('aria-controls', 'abPreview-' + encodeURIComponent(key));
    const syncCollapse = () => {
        const collapsed = collapsedPreviews.has(key);
        collapse.setAttribute('aria-expanded', String(!collapsed));
        collapse.textContent = bt(collapsed ? 'preview.expand' : 'preview.collapse',
            collapsed ? '展开作品预览' : '收起作品预览');
    };
    syncCollapse();
    collapse.addEventListener('click', () => {
        if (collapsedPreviews.has(key)) collapsedPreviews.delete(key);
        else collapsedPreviews.add(key);
        document.querySelectorAll('[data-preview-key]').forEach(node => {
            if (node.dataset.previewKey === key) node.hidden = collapsedPreviews.has(key);
        });
        syncCollapse();
    });
    bar.appendChild(workInteractionModeControl());
    bar.appendChild(collapse);
    const pageBtn = el('button', 'ab-btn ab-btn--ghost ab-btn--sm ab-select-page', bt('selection.page', '选择本页'));
    pageBtn.type = 'button';
    pageBtn.hidden = workInteractionMode === 'direct';
    pageBtn.disabled = !opts.pageEnabled;
    pageBtn.addEventListener('click', () => selectVisibleWorks());
    const allBtn = el('button', 'ab-btn ab-btn--ghost ab-btn--sm', bt('common.enqueue-all', '全部入队'));
    allBtn.type = 'button';
    allBtn.disabled = !opts.allEnabled;
    allBtn.addEventListener('click', opts.onEnqueueAll);
    bar.appendChild(pageBtn);
    bar.appendChild(allBtn);
    return bar;
}

// 队列增删后统一刷新当前舞台各网格的 ✓ 标记（聚合入口，等价 syncAllResultsQueueState）
function syncAllResultsQueueState() {
    syncWorkSelection();
    document.querySelectorAll('.ab-work[data-work-id]').forEach(card => {
        const inQueue = queueHas(card.dataset.workId);
        card.classList.toggle('in-queue', inQueue);
        const btn = card.querySelector('.ab-work-enqueue');
        if (btn) {
            btn.hidden = workInteractionMode === 'direct';
            btn.classList.toggle('is-queued', inQueue);
            btn.setAttribute('aria-pressed', String(inQueue));
            btn.setAttribute('aria-label', bt(inQueue ? 'queue.remove' : 'card.enqueue', inQueue ? '从队列移除' : '直接加入队列'));
            btn.title = btn.getAttribute('aria-label');
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
