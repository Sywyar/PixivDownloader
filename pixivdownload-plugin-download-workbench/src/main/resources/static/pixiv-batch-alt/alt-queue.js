'use strict';

/* ============================================================
   下载坞渲染
   ============================================================ */
function setDockStatus(message, tone) {
    dockState.statusMessage = message;
    dockState.statusTone = tone || 'info';
    renderDockStatus();
}

function renderDockStatus() {
    const node = document.getElementById('abDockStatus');
    if (!node) return;
    const message = dockState.statusMessage;
    node.textContent = message && typeof message === 'object'
        ? bt(message.key, message.fallback, message.args)
        : message || bt('status.ready', '准备就绪');
    node.dataset.tone = dockState.statusTone;
}

function statCard(id, icon, labelKey, labelFallback) {
    const card = el('div', 'ab-stat stat-card');
    card.appendChild(abIconEl(icon, 'ab-stat-icon'));
    const value = el('strong', 'ab-stat-value', '0');
    value.id = id;
    card.appendChild(value);
    card.appendChild(el('span', 'ab-stat-label', () => bt(labelKey, labelFallback)));
    return card;
}

/* ============================================================
   下载坞 Vue 岛门面（alt-queue-vue.js）：激活时进度扇出改经 reactive store，
   未激活 / 挂载失败时以下各渲染函数继续走既有命令式路径（首屏占位 + 回退）。
   ============================================================ */
function altQueueVue() {
    return window.PixivBatchAlt && window.PixivBatchAlt.queueVue;
}

function altQueueVueActive(kind) {
    const vue = altQueueVue();
    return !!(vue && typeof vue.isActive === 'function' && vue.isActive(kind));
}

function ensureDockVue() {
    const vue = altQueueVue();
    if (!vue || typeof vue.ensure !== 'function') return;
    vue.ensure();
}

function updateStats() {
    state.stats.success = state.stats.failed = state.stats.active = state.stats.skipped = 0;
    let pending = 0;
    let hasPackableArtwork = false;
    for (const item of state.queue) {
        switch (item.status) {
            case 'completed':
                state.stats.success++;
                if (item.kind === 'illust') hasPackableArtwork = true;
                break;
            case 'failed': state.stats.failed++; break;
            case 'downloading': state.stats.active++; break;
            case 'skipped': state.stats.skipped++; break;
            case 'idle':
            case 'pending':
            case 'paused': pending++; break;
        }
    }
    if (altQueueVueActive('stats')) {
        altQueueVue().syncStats({
            pending,
            success: state.stats.success,
            failed: state.stats.failed,
            active: state.stats.active,
            skipped: state.stats.skipped
        });
    } else {
        const set = (id, value) => {
            const node = document.getElementById(id);
            if (node) animateCount(node, value);
        };
        set('abStatPending', pending);
        set('abStatSuccess', state.stats.success);
        set('abStatFailed', state.stats.failed);
        set('abStatActive', state.stats.active);
        set('abStatSkipped', state.stats.skipped);
    }
    const badge = document.getElementById('abDockBadge');
    if (badge) {
        badge.textContent = String(pending);
        badge.hidden = pending === 0;
    }
    updateButtonsState(state.stats, hasPackableArtwork);
}

function renderDownloadSpeed(bytesPerSec) {
    const {value, unit} = formatSpeed(bytesPerSec);
    dockState.speed = {value, unit};
    if (altQueueVueActive('stats')) {
        altQueueVue().syncSpeed(value, unit);
        return;
    }
    const valEl = document.getElementById('abStatSpeed');
    const unitEl = document.getElementById('abStatSpeedUnit');
    if (valEl) valEl.textContent = value;
    if (unitEl) unitEl.textContent = unit;
}

function updateButtonsState(stats, hasPackableArtwork) {
    const startBtn = document.getElementById('abBtnStart');
    const pauseBtn = document.getElementById('abBtnPause');
    if (startBtn) {
        startBtn.disabled = state.isRunning;
        startBtn.classList.toggle('is-loading', state.isRunning);
    }
    if (pauseBtn) {
        pauseBtn.disabled = !state.isRunning;
        const action = state.isPaused ? 'play' : 'pause';
        if (pauseBtn.dataset.action !== action) {
            pauseBtn.replaceChildren(abIconEl(action), el('span'));
            pauseBtn.dataset.action = action;
        }
        const label = pauseBtn.lastElementChild;
        const text = state.isPaused
            ? bt('button.resume', '继续')
            : bt('button.pause', '暂停');
        if (label.textContent !== text) label.textContent = text;
    }
    const packBtn = document.getElementById('abBtnPack');
    if (packBtn) {
        packBtn.hidden = !isAdmin;
        packBtn.disabled = !(hasPackableArtwork ?? state.queue.some(q => q.status === 'completed' && q.kind === 'illust'));
    }
    const retryBtn = document.getElementById('abBtnRetry');
    if (retryBtn) retryBtn.disabled = stats ? stats.failed === 0 : !state.queue.some(q => q.status === 'failed');
}

function renderDock() {
    const body = document.getElementById('abDockBody');
    if (!body) return;
    body.innerHTML = '';

    // 统计卡（队列 / 成功 / 失败 / 进行中 / 跳过 + 速度）
    const stats = el('div', 'ab-dock-stats');
    stats.appendChild(statCard('abStatPending', 'clock', 'stats.queued', '队列'));
    stats.appendChild(statCard('abStatSuccess', 'check', 'stats.success', '成功'));
    stats.appendChild(statCard('abStatFailed', 'x', 'stats.failed', '失败'));
    stats.appendChild(statCard('abStatActive', 'download', 'stats.active', '进行中'));
    stats.appendChild(statCard('abStatSkipped', 'chevron-right', 'stats.skipped', '跳过'));
    const speedCard = el('div', 'ab-stat stat-card ab-stat--speed');
    speedCard.appendChild(abIconEl('gauge', 'ab-stat-icon'));
    const speedValue = el('strong', 'ab-stat-value', '0');
    speedValue.id = 'abStatSpeed';
    speedCard.appendChild(speedValue);
    const speedUnit = el('span', 'ab-stat-label', 'B/s');
    speedUnit.id = 'abStatSpeedUnit';
    speedCard.appendChild(speedUnit);
    stats.appendChild(speedCard);
    body.appendChild(stats);

    // 状态行 + 开始 / 暂停
    const controls = el('div', 'ab-dock-controls card');
    const statusLine = el('p', 'ab-dock-status');
    statusLine.id = 'abDockStatus';
    controls.appendChild(statusLine);
    const btnRow = el('div', 'ab-dock-btns');
    const startBtn = el('button', 'ab-btn ab-btn--primary');
    startBtn.id = 'abBtnStart';
    startBtn.type = 'button';
    startBtn.appendChild(abIconEl('play'));
    startBtn.appendChild(el('span', '', () => bt('dock.start', '开始下载')));
    startBtn.addEventListener('click', handleStart);
    const pauseBtn = el('button', 'ab-btn ab-btn--ghost');
    pauseBtn.id = 'abBtnPause';
    pauseBtn.type = 'button';
    pauseBtn.addEventListener('click', handlePause);
    btnRow.appendChild(startBtn);
    btnRow.appendChild(pauseBtn);
    controls.appendChild(btnRow);
    body.appendChild(controls);
    const recovery = el('div');
    recovery.id = 'queue-recovery';
    recovery.dataset.buttonClass = 'ab-btn ab-btn--ghost ab-btn--sm';
    body.appendChild(recovery);

    // 配额（multi 模式启用配额时）
    const quotaBox = el('div', 'ab-quota card');
    quotaBox.id = 'abQuotaBox';
    quotaBox.hidden = true;
    body.appendChild(quotaBox);

    // 归档
    const archiveBox = el('div', 'ab-archive card');
    archiveBox.id = 'abArchiveBox';
    archiveBox.hidden = true;
    body.appendChild(archiveBox);
    renderArchiveCard();

    // 当前下载
    const current = el('div', 'ab-current card');
    current.id = 'abCurrentCard';
    body.appendChild(current);

    // 队列操作
    const ops = el('div', 'ab-queue-ops');
    const retryBtn = el('button', 'ab-btn ab-btn--ghost ab-btn--sm', () => bt('queue.retry-failed', '重试失败'));
    retryBtn.id = 'abBtnRetry';
    retryBtn.type = 'button';
    retryBtn.addEventListener('click', handleRetry);
    const exportAllBtn = el('button', 'ab-btn ab-btn--ghost ab-btn--sm', () => bt('queue.export-all', '导出全部'));
    exportAllBtn.type = 'button';
    exportAllBtn.addEventListener('click', handleExport);
    const exportUndlBtn = el('button', 'ab-btn ab-btn--ghost ab-btn--sm', () => bt('queue.export-undownloaded', '导出未下载'));
    exportUndlBtn.type = 'button';
    exportUndlBtn.addEventListener('click', handleExportFailed);
    const packBtn = el('button', 'ab-btn ab-btn--ghost ab-btn--sm', () => bt('queue.pack-done', '打包已完成'));
    packBtn.id = 'abBtnPack';
    packBtn.type = 'button';
    packBtn.addEventListener('click', triggerAdminPack);
    const clearBtn = el('button', 'ab-btn ab-btn--danger-ghost ab-btn--sm', () => bt('queue.clear', '清除队列'));
    clearBtn.type = 'button';
    clearBtn.addEventListener('click', handleClear);
    ops.appendChild(retryBtn);
    ops.appendChild(exportAllBtn);
    ops.appendChild(exportUndlBtn);
    ops.appendChild(packBtn);
    ops.appendChild(clearBtn);
    body.appendChild(ops);

    // 队列列表
    const list = el('div', 'ab-queue-list');
    list.id = 'abQueueList';
    body.appendChild(list);

    renderDockStatus();
    renderCurrent(null);
    renderQueue();
    updateStats();
    updateButtonsState();
    // 首屏已由上方命令式渲染即时出图；随后尝试把统计 / 当前卡 / 队列列表升级为 Vue 主渲染
    //（异步懒加载运行时，失败即保持命令式，不影响本函数返回）。
    ensureDockVue();
}

// 当前下载卡：进度环 + 字节进度 + 动图阶段
function renderCurrent(item) {
    // 当前下载卡现由队列派生（队首未完成项 + 剩余计数），不再跟随事件到达顺序在不同作品间跳变；
    // item 参数仅为兼容既有调用点（SSE 进度事件 / worker 启停 / 语言切换）与 currentItemId 语义。
    state.currentItemId = item ? String(item.id) : null;
    if (!altQueueProgressRows.size) refreshCurrentCard(item);
}

// 当前卡刷新门面：Vue 已接管**且当前卡已由 Vue 渲染**（isCurrentActive）→ 同步最新队列镜像 + 暂停标志
//（当前卡内容由响应式从镜像派生，Vue 只 patch 单卡）；否则命令式重建整卡（renderCurrentImperative）。
// 每次刷新都同步队列镜像，保证任意进度 / 状态事件（renderCurrent / renderQueue / pause / resume）都让当前卡
// 实时重算；镜像同步与 renderQueue 的列表同步同 key 合批去重。按当前卡挂载点单独判定，避免「统计 / 列表岛
// 激活但当前卡挂载失败」时当前卡永久停留在初始「无」。
function refreshCurrentCard(changedItem) {
    if (altQueueVueActive() && altQueueVue().isCurrentActive()) {
        altQueueVue().syncList(changedItem);
        altQueueVue().syncPaused(state.isPaused);
        return;
    }
    renderCurrentImperative(currentFrontItem(state.queue, state.isPaused));
}

function queueItemTags(q) {
    const runtime = window.PixivBatch && window.PixivBatch.queueTypes;
    const xr = q.xRestrict == null ? null : Number(q.xRestrict);
    let xrTag;
    if (xr === 2) xrTag = {key: 'xr', cls: 'ab-queue-tag--r18g', text: 'R-18G'};
    else if (xr === 1) xrTag = {key: 'xr', cls: 'ab-queue-tag--r18', text: 'R-18'};
    else if (xr === null || !Number.isFinite(xr)) {
        xrTag = {key: 'xr', cls: 'ab-queue-tag--unknown', text: bt('queue.unknown', '未知')};
    } else xrTag = {key: 'xr', cls: 'ab-queue-tag--sfw', text: 'SFW'};
    const tags = [
        {key: 'source', cls: 'ab-queue-tag--source', text: queueDataSourceText(q)},
        {key: 'mode', cls: 'ab-queue-tag--mode', text: queueSourceText(q.source)},
        xrTag
    ];
    const contributed = runtime && typeof runtime.queueTags === 'function' ? runtime.queueTags(q) : [];
    contributed.forEach(tag => {
        if (!tag || !tag.label) return;
        tags.push({key: 'plugin-' + (tag.id || tag.label), cls: 'ab-queue-tag--plugin', text: tag.label});
    });
    return tags;
}

function renderQueueTags(item) {
    const tags = el('div', 'ab-queue-tags');
    queueItemTags(item).forEach(tag => tags.appendChild(el('span', 'ab-queue-tag ' + tag.cls, tag.text)));
    return tags;
}

// 当前卡内容节点构建（命令式回退与 Vue 主路径共用同一外观）：head + 队首进度信息（进度环 + 标题 + 详情 +
// 流式图片进度条 + 附加进度）；item 为 null（空闲 / 暂停且无收尾任务）时仅 head + idle「无」。
function buildCurrentCardContent(item) {
    const model = currentCardModel(item);
    const card = el('div', '');
    const head = el('div', 'ab-current-head');
    head.appendChild(abIconEl('download'));
    head.appendChild(el('strong', '', bt('current.title', '当前下载')));
    card.appendChild(head);
    if (!item) {
        card.appendChild(el('p', 'ab-current-idle', bt('status.current-idle', '无')));
        return card;
    }
    const row = el('div', 'ab-current-row');
    const percent = model.percent;
    row.appendChild(progressRing(percent));
    const meta = el('div', 'ab-current-meta');
    meta.appendChild(el('div', 'ab-current-title', model.title));
    const detail = el('div', 'ab-current-detail');
    detail.textContent = model.detail;
    meta.appendChild(detail);
    meta.appendChild(renderQueueTags(item));
    row.appendChild(meta);
    card.appendChild(row);
    // 流式图片进度条（与列表行 miniProgress 同口径，由 downloadedCount / totalImages 派生，始终实时可见）。
    if (item.kind === 'illust' || item.totalImages > 0) {
        card.appendChild(miniProgress(model.progressLabel, null,
            item.totalImages > 0 ? percent : null, 'is-image', false));
    }
    const extras = progressExtras(item);
    if (extras) card.appendChild(extras);
    return card;
}

// 仅在当前渲染中派生文案，Vue 与命令式路径共用显示口径。
function currentCardModel(item) {
    if (!item) return null;
    const percent = item.totalImages > 0 ? pct(item) : 0;
    const progressLabel = item.totalImages > 0 ? bt('status.image-progress', '{downloaded} / {total} 张',
        {downloaded: item.downloadedCount || 0, total: item.totalImages}) : bt('queue.progress.inactive', null);
    return {title: queueItemDisplayTitle(item), percent, progressLabel,
        detail: item.totalImages > 0 ? progressLabel + ' · ' + percent + '%' : queueItemMessage(item)};
}

// 计划详情使用的当前卡完整 HTML：内容节点 +
// 剩余计数行；无未完成项（含暂停且无收尾任务）时回退 idle「无」。
function computeCurrentCardHtml(queue, isPaused) {
    const front = currentFrontItem(queue, isPaused);
    const card = buildCurrentCardContent(front);
    if (front) {
        const counts = currentRemainingCounts(queue, front);
        const line = currentRemainingLineText(counts.downloading, counts.queued);
        if (line) card.appendChild(el('p', 'ab-current-remaining', line));
    }
    return card.innerHTML;
}

function renderCurrentImperative(item) {
    const card = document.getElementById('abCurrentCard');
    if (!card) return;
    const content = buildCurrentCardContent(item);
    card.innerHTML = '';
    while (content.firstChild) card.appendChild(content.firstChild);
    // 剩余计数行：仅队首存在时追加（空闲 / 暂停且无收尾任务时 item 为 null 已提前返回）。
    if (item) {
        const counts = currentRemainingCounts(state.queue, item);
        const line = currentRemainingLineText(counts.downloading, counts.queued);
        if (line) card.appendChild(el('p', 'ab-current-remaining', line));
    }
}

function progressRing(percent) {
    const wrap = el('span', 'ab-ring');
    const radius = 26;
    const circumference = 2 * Math.PI * radius;
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('viewBox', '0 0 64 64');
    const track = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
    track.setAttribute('cx', '32');
    track.setAttribute('cy', '32');
    track.setAttribute('r', String(radius));
    track.setAttribute('class', 'ab-ring-track');
    const fill = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
    fill.setAttribute('cx', '32');
    fill.setAttribute('cy', '32');
    fill.setAttribute('r', String(radius));
    fill.setAttribute('class', 'ab-ring-fill');
    fill.style.strokeDasharray = String(circumference);
    fill.style.strokeDashoffset = String(circumference * (1 - Math.min(100, Math.max(0, percent)) / 100));
    svg.appendChild(track);
    svg.appendChild(fill);
    wrap.appendChild(svg);
    wrap.appendChild(el('span', 'ab-ring-text', Math.round(percent) + '%'));
    return wrap;
}

function miniProgress(label, valueText, progress, cls, active = true) {
    const wrap = el('div', 'ab-mini-prog');
    const head = el('div', 'ab-mini-prog-label');
    head.appendChild(el('span', '', label));
    const pctValue = progress == null || !Number.isFinite(Number(progress))
        ? null : Math.max(0, Math.min(100, Math.round(progress)));
    head.appendChild(el('span', '', [valueText, pctValue == null ? '' : pctValue + '%'].filter(Boolean).join(' · ')));
    wrap.appendChild(head);
    const bar = el('div', 'ab-mini-prog-bar');
    bar.setAttribute('role', 'progressbar');
    bar.setAttribute('aria-label', [label, valueText].filter(Boolean).join(' · '));
    bar.setAttribute('aria-valuemin', '0');
    bar.setAttribute('aria-valuemax', '100');
    bar.setAttribute('aria-busy', String(pctValue == null && active));
    if (pctValue != null) bar.setAttribute('aria-valuenow', String(pctValue));
    const fill = el('div', 'ab-mini-prog-fill' + (cls ? ' ' + cls : ''));
    fill.style.width = (pctValue == null ? (active ? 35 : 0) : pctValue) + '%';
    if (pctValue == null && active) fill.classList.add('is-indeterminate');
    bar.appendChild(fill);
    wrap.appendChild(bar);
    return wrap;
}

// 图片 / 动图 / 小说附加进度（队列项与当前卡共用）
function progressExtras(q) {
    const entries = progressExtrasModel(q);
    if (!entries.length) return null;
    const parts = el('div', 'ab-progress-extras');
    for (const entry of entries) {
        if (entry.kind === 'progress') {
            parts.appendChild(miniProgress(entry.label, entry.value, entry.progress, entry.cls, entry.active));
        } else {
            const line = el('p', 'ab-progress-note' + (entry.tone ? ' ab-progress-note--' + entry.tone : ''));
            if (entry.badge) line.appendChild(el('span', 'ab-mini-badge' + (entry.ai ? ' ab-mini-badge--ai' : ''), entry.badge));
            line.appendChild(document.createTextNode((entry.badge ? ' ' : '') + entry.text));
            parts.appendChild(line);
        }
    }
    return parts;
}

// 首次识别媒体后预留全部阶段；只保留位置，不保留旧字节、百分比或格式。
const altProgressSlots = new WeakMap();
function progressSlotsFor(q) {
    if (q.progressSlots) return q.progressSlots;
    let seen = altProgressSlots.get(q);
    if (!seen || seen.kind !== q.kind) {
        seen = {kind: q.kind, slots: []};
        altProgressSlots.set(q, seen);
    }
    const remember = (key, labelKey, cls) => {
        if (!seen.slots.some(slot => slot.key === key)) seen.slots.push({key, labelKey, cls});
    };
    const up = q.ugoiraProgress;
    if (q.kind === 'novel') {
        remember('queue.novel-text.label', 'queue.novel-text.label', 'is-image');
        remember('queue.novel-cover.label', 'queue.novel-cover.label', 'is-image');
        remember('embedded', 'queue.novel-images.label', 'is-image');
    } else if (q.illustType === 2 || up && (up.phase || up.zipProgress != null || up.zipDownloadedBytes != null)) {
        remember('zip', 'queue.ugoira.zip', 'is-zip');
        remember('ugoira-processing', 'queue.media.label', 'is-ffmpeg');
    } else if (q.imageProgress || q.illustType === 0 || q.illustType === 1) {
        remember('image-download', 'queue.image-download.label', 'is-image');
        // 图片流水线最多同时处理两张；总量未知时也预留两处，避免稍后插入。
        const count = q.imageProgress?.totalImages || q.totalImages;
        for (let i = 0; i < (count === 1 ? 1 : 2); i++) {
            remember('image-processing-' + i, 'queue.media.label', 'is-ffmpeg');
        }
    }
    return seen.slots;
}

function queueProgressSnapshot(q) {
    // Vue 的行快照携带纯值显示位置，不把派生状态写入持久队列。
    return Object.assign({}, q, {progressSlots: progressSlotsFor(q).slice()});
}

function retainQueueProgressSlots(item, previous) {
    if (previous && previous.kind === item.kind) {
        altProgressSlots.set(item, {kind: item.kind, slots: progressSlotsFor(previous).slice()});
    }
}

function progressExtrasModel(q) {
    const slots = progressSlotsFor(q);
    const parts = [];
    const progress = (key, label, value, percent, cls, active = true) => {
        parts.push({key, kind: 'progress', label, value, progress: percent, cls, active});
    };
    const runtime = window.PixivBatch && window.PixivBatch.queueTypes;
    const live = runtime && typeof runtime.queueLiveStatus === 'function' ? runtime.queueLiveStatus(q) : null;
    if (live && live.label && live.message && ['info', 'success', 'warning', 'error'].includes(live.tone)) {
        parts.push({key: 'live', tone: live.tone, badge: live.label, text: live.message});
    }
    if (q.kind === 'novel' && q.status === 'downloading') {
        for (const [value, key, fallback] of [[q.novelText, 'queue.novel-text.label', '小说正文'],
            [q.novelCover, 'queue.novel-cover.label', '封面']]) {
            if (!value || !(value.done > 0 || value.total > 0)) continue;
            const bytes = formatBytes(value.done || 0) + (value.total > 0 ? ' / ' + formatBytes(value.total) : '');
            progress(key, bt(key, fallback), bytes,
                value.total > 0 ? Math.round(value.done / value.total * 100) : null, 'is-image');
        }
        const embedded = q.novelEmbedded;
        if (embedded && embedded.total > 0) {
            progress('embedded', bt('queue.novel-images.label', '内嵌图片'),
                bt('queue.novel-images.count', '{done}/{total} 张', embedded),
                Math.round((embedded.done || 0) / embedded.total * 100), 'is-image');
        }
    }
    const snapshot = q.imageProgress;
    const images = snapshot ? [snapshot, ...(Array.isArray(snapshot.processing) ? snapshot.processing : [])] : [];
    let processingIndex = 0;
    for (const ip of images.filter(image => image.phase !== 'processing')) {
        if (ip && q.status === 'downloading') {
            const imageText = ip.imageNumber && ip.totalImages
                ? bt('queue.image-download.index', '第 {current}/{total} 张', {current: ip.imageNumber, total: ip.totalImages})
                : '';
            const bytesText = ip.totalBytes > 0
                ? `${formatBytes(ip.downloadedBytes || 0)} / ${formatBytes(ip.totalBytes)}`
                : formatBytes(ip.downloadedBytes || 0);
            if (ip.phase) {
                const label = ip.status === 'failed' ? bt('queue.media.failed', null) : mediaProgressLabel(ip);
                progress('image-processing-' + processingIndex++, label, imageText, null,
                    ip.status === 'failed' ? 'is-failed' : 'is-ffmpeg', ip.status !== 'failed');
            } else progress('image-download',
                bt('queue.image-download.label', '图片下载'),
                [imageText, bytesText].filter(Boolean).join(' · '),
                ip.progress,
                ip.status === 'failed' ? 'is-failed' : 'is-image', ip.status !== 'failed');
        }
    }
    const up = q.ugoiraProgress;
    if (up && q.status === 'downloading') {
        const phase = String(up.phase || '');
        if (phase === 'zip' || phase === 'extract' || phase === 'ffmpeg' || up.zipProgress !== undefined) {
            const zipBytes = up.zipTotalBytes > 0
                ? `${formatBytes(up.zipDownloadedBytes || 0)} / ${formatBytes(up.zipTotalBytes)}`
                : formatBytes(up.zipDownloadedBytes || 0);
            progress('zip', bt('queue.ugoira.zip', '动图压缩包'), zipBytes, up.zipProgress,
                'is-zip', phase === 'zip' && up.status !== 'failed');
        }
        if (phase === 'extract') {
            progress('ugoira-processing', bt('queue.ugoira.extracting', '正在解压帧'), up.totalFrames > 0
                    ? bt('queue.ugoira.extracting-count', '正在解压帧 {current}/{total}', {current: up.extractedFrames || 0, total: up.totalFrames})
                    : '', null, 'is-ffmpeg', up.status !== 'failed');
        }
        if (phase === 'ffmpeg-waiting' || phase === 'finalizing') {
            progress('ugoira-processing', mediaProgressLabel(up), '', null, 'is-ffmpeg', up.status !== 'failed');
        }
        if (phase === 'ffmpeg' && up.status !== 'failed') {
            const timeText = up.ffmpegDurationMs > 0
                ? `${formatDurationMs(up.ffmpegOutTimeMs || 0)} / ${formatDurationMs(up.ffmpegDurationMs)}`
                : '';
            progress('ugoira-processing', mediaProgressLabel(up), timeText, up.ffmpegProgress, 'is-ffmpeg');
        }
        if (up.status === 'failed') {
            parts.push({key: 'ugoira-error', tone: 'error', text: bt('queue.ugoira.failed', '动图处理失败')});
        }
    }
    if (q.kind === 'novel' && q.translatePhase) {
        const msg = novelTranslateMessage(q);
        if (msg) {
            parts.push({key: 'translate', badge: bt('queue.translate.label', 'AI 翻译'), ai: true, text: msg});
        }
    }
    const notes = parts.filter(part => part.kind !== 'progress');
    const bars = slots.map(slot => parts.find(part => part.key === slot.key) || {
        ...slot, kind: 'progress', label: bt(slot.labelKey, null),
        value: bt('queue.progress.inactive', '暂无当前进度'), progress: null, active: false
    });
    return [...bars, ...notes];
}

function novelTranslateMessage(q) {
    switch (q.translatePhase) {
        case 'QUEUED':
            return bt('queue.message.translate-waiting', '排队等待翻译...');
        case 'WAITING_SERIES':
            return bt('queue.message.translate-wait-series', '等待前系列小说翻译完成，还有 {n} 个', {n: q.translateSeriesPending || 0});
        case 'RESOLVING':
            return bt('queue.message.translate-resolving', '识别目标语言中...');
        case 'TRANSLATING':
            return bt('queue.message.translating', 'AI 翻译中（{sec}s）', {sec: q.translateElapsed || 0});
        case 'MERGING':
            return bt('queue.message.translate-merging', '生成译文合订本中...');
        case 'SAME_LANGUAGE':
            return bt('queue.message.translate-same-lang', '完成（源语言与目标一致，已跳过）');
        case 'DONE':
            return bt('queue.message.translate-done', '完成（已翻译）');
        case 'FAILED':
            return bt('queue.message.translate-failed', '完成（翻译失败）');
        default:
            return '';
    }
}

const altQueueProgressRows = new Set();
let altQueueProgressScheduled = false;

function renderQueue(changedItem, statusChanged = false) {
    if (queueOrderChanged) {
        changedItem = null;
        queueOrderChanged = false;
    }
    if (!changedItem || statusChanged) renderQueueRecovery();
    if (changedItem && !altQueueVueActive('list')) {
        altQueueProgressRows.add(changedItem);
        if (!altQueueProgressScheduled) {
            altQueueProgressScheduled = true;
            const schedule = typeof requestAnimationFrame === 'function' ? requestAnimationFrame : cb => setTimeout(cb, 16);
            schedule(() => {
                altQueueProgressScheduled = false;
                if (!altQueueProgressRows.size) return;
                const rows = Array.from(altQueueProgressRows);
                altQueueProgressRows.clear();
                const list = document.getElementById('abQueueList');
                if (!altQueueVueActive('list') && list && list.children.length === state.queue.length) {
                    rows.forEach(item => {
                        const index = state.queue.indexOf(item);
                        if (index >= 0) list.replaceChild(queueItemRow(item), list.children[index]);
                    });
                    refreshCurrentCard();
                } else {
                    renderQueue();
                }
            });
        }
        return;
    }
    altQueueProgressRows.clear();
    // 当前下载卡由 state.queue 派生：随队列每次变化一并刷新（Vue 接管后只合批同步 store，命令式回退时重建单卡）。
    refreshCurrentCard(changedItem);
    if (altQueueVueActive('list')) {
        altQueueVue().syncList(changedItem);
        return;
    }
    const list = document.getElementById('abQueueList');
    if (!list) return;
    list.innerHTML = '';
    if (!state.queue.length) {
        const empty = el('div', 'ab-empty ab-empty--dock');
        empty.appendChild(abIconEl('download'));
        empty.appendChild(el('p', '', bt('status.queue-empty', '队列为空')));
        list.appendChild(empty);
        return;
    }
    state.queue.forEach(q => {
        list.appendChild(queueItemRow(q));
    });
}

function queueItemRow(q, options = {}) {
    const row = el('div', 'ab-queue-item');
    row.dataset.queueId = String(q.id);
    row.dataset.status = q.status;

    const titleRow = el('div', 'ab-queue-title');
    titleRow.appendChild(el('span', 'ab-queue-name', queueItemDisplayTitle(q)));
    const link = el('a', 'ab-iconbtn ab-iconbtn--xs');
    link.href = queueItemCanonicalUrl(q);
    link.target = '_blank';
    link.rel = 'noopener';
    link.title = bt('queue.open-artwork', '打开作品页面');
    link.appendChild(abIconEl('external'));
    link.addEventListener('click', e => e.stopPropagation());
    titleRow.appendChild(link);
    const queueRuntime = window.PixivBatch && window.PixivBatch.queueTypes;
    if (!options.readOnly && q.status === 'downloading' && queueRuntime && queueRuntime.canCancel(q)) {
        const cancel = el('button', 'ab-iconbtn ab-iconbtn--xs');
        cancel.type = 'button';
        cancel.title = bt('queue.cancel', '取消下载');
        cancel.appendChild(abIconEl('stop'));
        cancel.addEventListener('click', e => {
            e.stopPropagation();
            requestQueueItemCancel(q.id);
        });
        titleRow.appendChild(cancel);
    }
    if (!options.readOnly && q.status !== 'downloading') {
        const remove = el('button', 'ab-iconbtn ab-iconbtn--xs');
        remove.type = 'button';
        remove.title = bt('queue.remove', '移除');
        remove.appendChild(abIconEl('x'));
        remove.addEventListener('click', e => {
            e.stopPropagation();
            if (removeFromQueue(q.id)) {
                abToast('info', bt('queue.toast.removed', '已从队列移除'));
            } else {
                abToast('warning', bt('queue.toast.remove-blocked', '无法移除：正在下载中'));
            }
        });
        titleRow.appendChild(remove);
    }
    row.appendChild(titleRow);

    row.appendChild(renderQueueTags(q));

    const metaLine = el('div', 'ab-queue-meta');
    metaLine.appendChild(document.createTextNode('ID: ' + (q.kind === 'novel'
        ? (q.novelId || String(q.id).replace(/^n/, '')) + ' (Novel)'
        : q.id) + ' | '));
    const statusLine = el('span', 'ab-queue-status');
    statusLine.dataset.status = q.status;
    statusLine.textContent = queueItemMessage(q);
    metaLine.appendChild(statusLine);
    row.appendChild(metaLine);

    if (q.kind === 'illust' || q.totalImages > 0) {
        row.appendChild(miniProgress(
            q.totalImages > 0 ? bt('status.image-progress', '{downloaded} / {total} 张',
                {downloaded: q.downloadedCount || 0, total: q.totalImages}) : bt('queue.progress.inactive', null),
            null, q.totalImages > 0 ? pct(q) : null, 'is-' + q.status, false));
    }
    const extras = progressExtras(q);
    if (extras) row.appendChild(extras);
    return row;
}

/* ============================================================
   配额条 / 归档卡（multi 模式；引擎与 init 均会调用）
   ============================================================ */
function renderQuotaBar() {
    const box = document.getElementById('abQuotaBox');
    if (!box) return;
    const quota = dockState.quota;
    if (!quota.enabled) {
        box.hidden = true;
        return;
    }
    box.hidden = false;
    if (!box.firstElementChild) {
        const head = el('div', 'ab-quota-head');
        head.appendChild(abIconEl('gauge'));
        head.appendChild(el('strong'));
        const bar = el('div', 'ab-mini-prog-bar');
        bar.appendChild(el('div', 'ab-mini-prog-fill'));
        box.append(head, el('div', 'ab-quota-line'), bar, el('p', 'ab-field-note'));
    }
    box.querySelector('strong').textContent = bt('quota.title', '下载配额');
    const line = box.querySelector('.ab-quota-line');
    line.textContent = bt('quota.used', '已用 {used} / {max} 个作品',
        {used: quota.artworksUsed, max: quota.maxArtworks});
    const pctValue = Math.min(100, Math.round(quota.artworksUsed / Math.max(1, quota.maxArtworks) * 100));
    const bar = box.querySelector('.ab-mini-prog-bar');
    bar.setAttribute('role', 'progressbar');
    bar.setAttribute('aria-label', bt('quota.title', '下载配额'));
    bar.setAttribute('aria-valuemin', '0');
    bar.setAttribute('aria-valuemax', '100');
    bar.setAttribute('aria-valuenow', String(pctValue));
    const fill = box.querySelector('.ab-mini-prog-fill');
    fill.className = 'ab-mini-prog-fill' + (pctValue >= 90 ? ' is-danger' : pctValue >= 70 ? ' is-warn' : '');
    fill.style.width = pctValue + '%';
    const reset = box.querySelector('.ab-field-note');
    reset.hidden = !(quota.resetSeconds > 0);
    reset.textContent = reset.hidden ? '' : bt('status.quota-reset', '配额重置剩余：{time}',
        {time: formatSeconds(quota.resetSeconds)});
}

function renderArchiveCard() {
    const box = document.getElementById('abArchiveBox');
    if (!box) return;
    const archive = dockState.archive;
    if (!archive) {
        box.hidden = true;
        return;
    }
    box.hidden = false;
    if (!box.firstElementChild) {
        const head = el('div', 'ab-quota-head');
        head.appendChild(abIconEl('box'));
        head.appendChild(el('strong'));
        const row = el('div', 'ab-archive-row');
        const dl = el('a', 'ab-btn ab-btn--primary ab-btn--sm');
        dl.append(abIconEl('download'), el('span', 'ab-archive-label'));
        const countdown = el('span', 'ab-archive-countdown');
        countdown.id = 'abArchiveCountdown';
        row.append(dl, countdown);
        box.append(head, el('p', 'ab-archive-note'), row);
    }
    box.querySelector('strong').textContent = bt(archive.title.key, archive.title.fallback, archive.title.args);
    const note = box.querySelector('.ab-archive-note');
    const messages = {
        expired: ['status.archive-expired', '链接已过期，请重新打包'],
        empty: ['status.archive-empty', '暂无可打包文件（当前下载仍在进行中，完成后自动包含）'],
        ready: ['status.archive-ready', '压缩包已就绪，请在有效期内下载：']
    };
    note.textContent = bt(...(messages[archive.status] || ['status.archive-packing', '正在打包已下载文件，请稍候...']));
    note.className = 'ab-archive-note ' + (messages[archive.status] ? 'ab-field-note' : 'ab-loading-line')
        + (archive.status === 'expired' ? ' ab-progress-note--error' : '');
    const row = box.querySelector('.ab-archive-row');
    const dl = row.querySelector('a');
    row.hidden = archive.status !== 'ready';
    if (row.hidden) dl.removeAttribute('href');
    else dl.href = BASE + '/api/archive/download/' + archive.token;
    dl.querySelector('.ab-archive-label').textContent = bt('archive.download', '下载压缩包');
    updateArchiveCountdown();
}

function updateArchiveCountdown() {
    const node = document.getElementById('abArchiveCountdown');
    if (!node) return;
    node.textContent = bt('status.archive-validity', '有效期：{time}',
        {time: formatSeconds(archiveMonitor.remainingSeconds())});
}

window.PixivBatchAlt.queue = Object.assign(window.PixivBatchAlt.queue, {
    queueHas, pct, queueStatusText, queueSourceText, queueAcquisitionMode,
    queueItemDisplayTitle, queueItemCanonicalUrl, dedupeQueueItems,
    addItemsToQueue, removeFromQueue, buildQueueExportLines, commitQueueItemPatch,
    handleExport, handleExportFailed, saveQueue, loadQueueForMode, clearSavedQueue,
    setDockStatus, updateStats, renderDownloadSpeed, updateButtonsState,
    renderDock, renderQueue, renderCurrent, progressExtras, miniProgress,
    renderQuotaBar, renderArchiveCard, updateArchiveCountdown, queueItemCard: queueItemRow,
    novelTranslateMessage, ensureDockVue, altQueueVueActive
});
