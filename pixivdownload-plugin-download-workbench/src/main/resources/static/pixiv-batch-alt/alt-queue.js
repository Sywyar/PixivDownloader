'use strict';
/* ============================================================
   alt-queue — 统一队列模型（逐字移植 batch-queue.js 的队列语义）+
   下载坞渲染（统计 / 当前下载 / 队列列表 / 配额 / 归档）。
   模型只存 raw 字段（title / status / lastMessage 原文或 i18n key），
   展示文案渲染时经 bt() 派生。
   ============================================================ */

function queueHas(id) {
    const key = String(id);
    return state.queue.some(q => String(q.id) === key);
}

function pct(q) {
    if (!q.totalImages) return 0;
    return Math.min(100, Math.round((q.downloadedCount || 0) / q.totalImages * 100));
}

function queueStatusText(status) {
    return {
        idle: bt('queue.status.waiting', '等待中'),
        pending: bt('queue.status.waiting', '等待中'),
        downloading: bt('queue.status.downloading', '下载中'),
        completed: bt('queue.status.completed', '已完成'),
        failed: bt('queue.status.failed', '失败'),
        paused: bt('queue.status.paused', '已暂停'),
        skipped: bt('queue.status.skipped', '已跳过')
    }[status] || status;
}

function queueAcquisitionMode(source) {
    const normalizedSource = String(source || '').trim();
    if (normalizedSource === QUICK_FETCH_MODE
        || normalizedSource.startsWith(QUICK_FETCH_MODE + '-')) return 'quick';
    if (normalizedSource === SINGLE_IMPORT_MODE
        || normalizedSource.startsWith(SINGLE_IMPORT_MODE + '-')) return 'single-import';
    if (normalizedSource === 'user' || normalizedSource.startsWith('user-')) return 'user';
    if (normalizedSource === 'search' || normalizedSource.startsWith('search-')) return 'search';
    if (normalizedSource === 'series' || normalizedSource.startsWith('series-')) return 'series';
    if (normalizedSource === 'schedule' || normalizedSource.startsWith('schedule-')) return 'schedule';
    return 'single-import';
}

function queueSourceText(source) {
    return {
        user: bt('queue.source.user', 'User'),
        search: bt('queue.source.search', 'Search'),
        series: bt('queue.source.series', 'Series'),
        quick: bt('queue.source.quick-fetch', '快捷'),
        'single-import': bt('queue.source.import', '导入'),
        schedule: bt('queue.source.schedule', '计划')
    }[queueAcquisitionMode(source)] || bt('queue.source.import', '导入');
}

function queueDataSourceText(item) {
    const runtime = window.PixivBatch && window.PixivBatch.queueTypes;
    try {
        const source = runtime && runtime.dataSourceForType(item.kind, queueAcquisitionMode(item.source));
        if (source) return typeof altSourceLabel === 'function' ? altSourceLabel(source) : source.id;
    } catch (e) {
        console.warn('[batch-alt] 队列数据来源解析失败：', item.kind, e);
    }
    return item && item.kind ? String(item.kind) : bt('queue.unknown', '未知');
}

// 渲染时派生队列项标题：模型里 title 只存原始字符串（可为空），此处补 i18n fallback。
function queueItemDisplayTitle(q) {
    if (q && q.title) return q.title;
    if (q && q.kind === 'novel') {
        const id = q.novelId || (q.id != null ? String(q.id).replace(/^n/, '') : '');
        return bt('queue.novel-fallback', '小说 {id}', {id});
    }
    return bt('queue.artwork-fallback', '作品 {id}', {id: q && q.id != null ? q.id : ''});
}

function queueItemCanonicalUrl(item) {
    if (!item) return '';
    if (item.canonicalUrl) return item.canonicalUrl;
    const runtime = window.PixivBatch && window.PixivBatch.queueTypes;
    const behavior = runtime && runtime.get(item.kind || 'illust');
    if (behavior && typeof behavior.canonicalUrl === 'function') {
        try {
            const url = behavior.canonicalUrl(item);
            if (url) return url;
        } catch (e) {
            console.warn('[batch-alt] 队列规范链接生成失败：', item.kind, e);
        }
    }
    if (item.kind === 'novel') {
        const id = item.novelId || String(item.id).replace(/^n/, '');
        return `https://www.pixiv.net/novel/show.php?id=${encodeURIComponent(id)}`;
    }
    return `https://www.pixiv.net/artworks/${item.id != null ? item.id : ''}`;
}

function dedupeQueueItems(items) {
    const seen = new Map();
    const uniqueItems = [];
    for (const item of items || []) {
        if (!item || item.id === undefined || item.id === null) continue;
        const id = String(item.id);
        if (seen.has(id)) continue;
        seen.set(id, {...item, id});
        uniqueItems.push({...item, id});
    }
    return uniqueItems;
}

function addItemsToQueue(idList, metaList, source, username, defaultAuthorId, defaultAuthorName) {
    const existing = new Map(state.queue.map(q => [String(q.id), q]));
    let added = 0;
    const meta = metaList || [];
    for (let i = 0; i < idList.length; i++) {
        const id = String(idList[i]);
        if (existing.has(id)) continue;
        const m = meta[i] || {};
        const authorId = normalizeAuthorId(m.authorId ?? defaultAuthorId);
        const queueItem = {
            id,
            kind: m.kind || 'illust',
            novelId: m.novelId || null,
            typeData: m.typeData && typeof m.typeData === 'object' ? m.typeData : null,
            canonicalUrl: m.canonicalUrl || null,
            title: m.title || '',
            status: state.isRunning ? 'pending' : 'idle',
            rawStatus: null,
            failureCode: null,
            statusMessageKey: null,
            source: source || SINGLE_IMPORT_MODE,
            username: username || '',
            authorId,
            authorName: m.authorName || defaultAuthorName || '',
            isAi: typeof m.isAi === 'boolean' ? m.isAi : null,
            xRestrict: typeof m.xRestrict === 'number' ? m.xRestrict : null,
            tags: Array.isArray(m.tags) ? m.tags : null,
            seriesId: m.seriesId ? Number(m.seriesId) : null,
            seriesOrder: m.seriesOrder != null ? Number(m.seriesOrder) : null,
            seriesTitle: m.seriesTitle || null,
            totalImages: 0,
            downloadedCount: 0,
            startTime: null,
            endTime: null,
            lastMessage: '',
            bookmarkResult: null,
            collectionResult: null,
            ugoiraProgress: null,
            imageProgress: null
        };
        if (m.cancelWorkKey) queueItem.cancelWorkKey = m.cancelWorkKey;
        queueItem.canonicalUrl = queueItemCanonicalUrl(queueItem);
        state.queue.push(queueItem);
        existing.set(id, queueItem);
        added++;
    }
    updateStats();
    saveQueue();
    renderQueue();
    if (state.isRunning && added > 0) {
        ensureWorkers();
    }
    syncAllResultsQueueState();
    return added;
}

const QUEUE_ITEM_PATCH_FIELDS = new Set([
    'status', 'rawStatus', 'failureCode', 'statusMessageKey',
    'downloadedCount', 'totalImages', 'startTime', 'endTime', 'cancelWorkKey'
]);
const QUEUE_ITEM_PROCESS_STATUSES = new Set(['downloading', 'completed', 'failed', 'skipped']);

function commitQueueItemPatch(item, patch) {
    if (!item || !state.queue.includes(item)) throw new Error('queue item is not active');
    if (!patch || typeof patch !== 'object' || Array.isArray(patch)) {
        throw new Error('queue item patch must be a plain object');
    }
    const proto = Object.getPrototypeOf(patch);
    if (proto !== Object.prototype && proto !== null) {
        throw new Error('queue item patch must be a plain object');
    }
    const keys = Object.keys(patch);
    keys.forEach(key => {
        if (!QUEUE_ITEM_PATCH_FIELDS.has(key)) throw new Error('unsupported queue item patch field: ' + key);
    });
    if (!keys.length) return item;

    const normalized = Object.create(null);
    if (Object.prototype.hasOwnProperty.call(patch, 'status')) {
        const status = String(patch.status || '').trim();
        if (!QUEUE_ITEM_PROCESS_STATUSES.has(status)) throw new Error('unsupported queue item status');
        normalized.status = status;
    }
    ['rawStatus', 'failureCode'].forEach(key => {
        if (!Object.prototype.hasOwnProperty.call(patch, key)) return;
        const value = patch[key] == null ? '' : String(patch[key]).trim();
        if (value.length > 128) throw new Error(key + ' is too long');
        normalized[key] = value || null;
    });
    if (Object.prototype.hasOwnProperty.call(patch, 'statusMessageKey')) {
        const key = patch.statusMessageKey == null ? '' : String(patch.statusMessageKey).trim();
        if (key && (key.length > 193 || !/^[a-z0-9][a-z0-9._-]{0,63}:[^\s:]{1,128}$/i.test(key))) {
            throw new Error('invalid statusMessageKey');
        }
        normalized.statusMessageKey = key || null;
    } else if (normalized.status && normalized.status !== 'failed') {
        normalized.statusMessageKey = null;
    }
    ['downloadedCount', 'totalImages'].forEach(key => {
        if (!Object.prototype.hasOwnProperty.call(patch, key)) return;
        const value = Number(patch[key]);
        if (!Number.isSafeInteger(value) || value < 0) throw new Error(key + ' must be a non-negative integer');
        normalized[key] = value;
    });
    ['startTime', 'endTime'].forEach(key => {
        if (!Object.prototype.hasOwnProperty.call(patch, key)) return;
        if (patch[key] == null) {
            normalized[key] = null;
            return;
        }
        const value = String(patch[key]).trim();
        if (!value || value.length > 64 || !Number.isFinite(Date.parse(value))
                || new Date(Date.parse(value)).toISOString() !== value) {
            throw new Error(key + ' must be an ISO-8601 timestamp or null');
        }
        normalized[key] = value;
    });
    if (Object.prototype.hasOwnProperty.call(patch, 'cancelWorkKey')) {
        const value = patch.cancelWorkKey == null ? '' : String(patch.cancelWorkKey).trim();
        if (!value || value.length > 256) throw new Error('cancelWorkKey must be a non-blank string');
        normalized.cancelWorkKey = value;
    }
    Object.keys(normalized).forEach(key => { item[key] = normalized[key]; });
    updateStats();
    saveQueue();
    renderQueue();
    return item;
}

function queueItemMessage(q) {
    if (!q) return '';
    return q.statusMessageKey
        ? bt(q.statusMessageKey, q.lastMessage || queueStatusText(q.status))
        : q.lastMessage || queueStatusText(q.status);
}

function removeFromQueue(id) {
    const idx = state.queue.findIndex(q => q.id === String(id));
    if (idx === -1) return false;
    const q = state.queue[idx];
    if (q.status === 'downloading') return false;
    state.queue.splice(idx, 1);
    updateStats();
    saveQueue();
    renderQueue();
    syncAllResultsQueueState();
    return true;
}

function buildQueueExportLines(items) {
    return (items || []).map(q =>
        `${queueItemCanonicalUrl(q)} | ${queueItemDisplayTitle(q)}`);
}

async function handleExport() {
    if (!state.queue.length) {
        await abAlert('alert.queue-empty', '队列为空');
        return;
    }
    const lines = buildQueueExportLines(state.queue);
    downloadTxt(lines.join('\n'), `pixiv_all_list_${Date.now()}.txt`);
    setDockStatus(bt('status.exported-all', '已导出 {count} 个作品', {count: lines.length}), 'success');
}

async function handleExportFailed() {
    const items = state.queue.filter(q => q.status !== 'completed');
    if (!items.length) {
        await abAlert('alert.no-undownloaded', '没有未下载的作品');
        return;
    }
    const lines = buildQueueExportLines(items);
    downloadTxt(lines.join('\n'), `pixiv_undownloaded_list_${Date.now()}.txt`);
    setDockStatus(bt('status.exported-undownloaded', '已导出 {count} 个未下载作品', {count: lines.length}), 'success');
}

/* ============================================================
   持久化（与现行页同一 storage key，互为兼容）
   ============================================================ */
function storageKey() {
    return 'pixiv_batch_queue';
}

function saveQueue() {
    storeSet(storageKey(), JSON.stringify({
        queue: state.queue,
        isPaused: state.isPaused,
        stats: state.stats,
        savedAt: new Date().toISOString()
    }));
}

function loadQueueForMode() {
    try {
        const raw = storeGet(storageKey());
        if (!raw) {
            state.queue = [];
            renderQueue();
            updateStats();
            return;
        }
        const parsed = JSON.parse(raw);
        if (Array.isArray(parsed.queue)) {
            state.queue = dedupeQueueItems(parsed.queue);
            state.queue.forEach(q => {
                // 刷新前正在下载的项目实际已中断，标记为失败
                if (q.status === 'downloading') {
                    q.status = 'failed';
                    q.lastMessage = bt('queue.message.failed-refresh', '失败 — 页面刷新导致中断');
                }
            });
            state.isPaused = !!parsed.isPaused;
            state.stats = parsed.stats || {success: 0, failed: 0, active: 0, skipped: 0};
        } else {
            state.queue = [];
        }
    } catch {
        state.queue = [];
    }
    renderQueue();
    updateStats();
}

function clearSavedQueue() {
    storeRemove(storageKey());
}

/* ============================================================
   下载坞渲染
   ============================================================ */
function setDockStatus(text, tone) {
    dockState.statusText = text || '';
    dockState.statusTone = tone || 'info';
    const node = document.getElementById('abDockStatus');
    if (!node) return;
    node.textContent = dockState.statusText;
    node.dataset.tone = dockState.statusTone;
}

function statCard(id, icon, labelKey, labelFallback) {
    const card = el('div', 'ab-stat stat-card');
    card.appendChild(abIconEl(icon, 'ab-stat-icon'));
    const value = el('strong', 'ab-stat-value', '0');
    value.id = id;
    card.appendChild(value);
    card.appendChild(el('span', 'ab-stat-label', bt(labelKey, labelFallback)));
    return card;
}

function updateStats() {
    state.stats.success = state.queue.filter(q => q.status === 'completed').length;
    state.stats.failed = state.queue.filter(q => q.status === 'failed').length;
    state.stats.active = state.queue.filter(q => q.status === 'downloading').length;
    state.stats.skipped = state.queue.filter(q => q.status === 'skipped').length;
    const pending = state.queue.filter(q =>
        ['idle', 'pending', 'paused'].includes(q.status)).length;
    const set = (id, value) => {
        const node = document.getElementById(id);
        if (node) animateCount(node, value);
    };
    set('abStatPending', pending);
    set('abStatSuccess', state.stats.success);
    set('abStatFailed', state.stats.failed);
    set('abStatActive', state.stats.active);
    set('abStatSkipped', state.stats.skipped);
    const badge = document.getElementById('abDockBadge');
    if (badge) {
        badge.textContent = String(pending);
        badge.hidden = pending === 0;
    }
    updateButtonsState();
}

function renderDownloadSpeed(bytesPerSec) {
    const {value, unit} = formatSpeed(bytesPerSec);
    const valEl = document.getElementById('abStatSpeed');
    const unitEl = document.getElementById('abStatSpeedUnit');
    if (valEl) valEl.textContent = value;
    if (unitEl) unitEl.textContent = unit;
}

function updateButtonsState() {
    const startBtn = document.getElementById('abBtnStart');
    const pauseBtn = document.getElementById('abBtnPause');
    if (startBtn) {
        startBtn.disabled = state.isRunning;
        startBtn.classList.toggle('is-loading', state.isRunning);
    }
    if (pauseBtn) {
        pauseBtn.disabled = !state.isRunning;
        pauseBtn.innerHTML = '';
        pauseBtn.appendChild(abIconEl(state.isPaused ? 'play' : 'pause'));
        pauseBtn.appendChild(el('span', '', state.isPaused
            ? bt('button.resume', '继续')
            : bt('button.pause', '暂停')));
    }
    const packBtn = document.getElementById('abBtnPack');
    if (packBtn) {
        packBtn.hidden = !isAdmin;
        packBtn.disabled = !state.queue.some(q => q.status === 'completed');
    }
    const retryBtn = document.getElementById('abBtnRetry');
    if (retryBtn) retryBtn.disabled = !state.queue.some(q => q.status === 'failed');
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
    statusLine.dataset.tone = 'info';
    statusLine.textContent = dockState.statusText || bt('status.ready', '准备就绪');
    controls.appendChild(statusLine);
    const btnRow = el('div', 'ab-dock-btns');
    const startBtn = el('button', 'ab-btn ab-btn--primary');
    startBtn.id = 'abBtnStart';
    startBtn.type = 'button';
    startBtn.appendChild(abIconEl('play'));
    startBtn.appendChild(el('span', '', bt('dock.start', '开始下载')));
    startBtn.addEventListener('click', handleStart);
    const pauseBtn = el('button', 'ab-btn ab-btn--ghost');
    pauseBtn.id = 'abBtnPause';
    pauseBtn.type = 'button';
    pauseBtn.addEventListener('click', handlePause);
    btnRow.appendChild(startBtn);
    btnRow.appendChild(pauseBtn);
    controls.appendChild(btnRow);
    body.appendChild(controls);

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

    // 当前下载
    const current = el('div', 'ab-current card');
    current.id = 'abCurrentCard';
    body.appendChild(current);

    // 队列操作
    const ops = el('div', 'ab-queue-ops');
    const retryBtn = el('button', 'ab-btn ab-btn--ghost ab-btn--sm', bt('queue.retry-failed', '重试失败'));
    retryBtn.id = 'abBtnRetry';
    retryBtn.type = 'button';
    retryBtn.addEventListener('click', handleRetry);
    const exportAllBtn = el('button', 'ab-btn ab-btn--ghost ab-btn--sm', bt('queue.export-all', '导出全部'));
    exportAllBtn.type = 'button';
    exportAllBtn.addEventListener('click', handleExport);
    const exportUndlBtn = el('button', 'ab-btn ab-btn--ghost ab-btn--sm', bt('queue.export-undownloaded', '导出未下载'));
    exportUndlBtn.type = 'button';
    exportUndlBtn.addEventListener('click', handleExportFailed);
    const packBtn = el('button', 'ab-btn ab-btn--ghost ab-btn--sm', bt('queue.pack-done', '打包已完成'));
    packBtn.id = 'abBtnPack';
    packBtn.type = 'button';
    packBtn.addEventListener('click', triggerAdminPack);
    const clearBtn = el('button', 'ab-btn ab-btn--danger-ghost ab-btn--sm', bt('queue.clear', '清除队列'));
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

    renderCurrent(null);
    renderQueue();
    updateStats();
    updateButtonsState();
}

// 当前下载卡：进度环 + 字节进度 + 动图阶段
function renderCurrent(item) {
    const card = document.getElementById('abCurrentCard');
    if (!card) return;
    card.innerHTML = '';
    const head = el('div', 'ab-current-head');
    head.appendChild(abIconEl('download'));
    head.appendChild(el('strong', '', bt('current.title', '当前下载')));
    card.appendChild(head);
    if (!item) {
        card.appendChild(el('p', 'ab-current-idle', bt('status.current-idle', '无')));
        return;
    }
    const row = el('div', 'ab-current-row');
    const percent = item.totalImages > 0 ? pct(item) : 0;
    row.appendChild(progressRing(percent));
    const meta = el('div', 'ab-current-meta');
    meta.appendChild(el('div', 'ab-current-title', queueItemDisplayTitle(item)));
    const detail = el('div', 'ab-current-detail');
    if (item.totalImages > 0) {
        detail.textContent = bt('status.image-progress', '{downloaded} / {total} 张',
            {downloaded: item.downloadedCount || 0, total: item.totalImages}) + ' · ' + percent + '%';
    } else {
        detail.textContent = queueItemMessage(item);
    }
    meta.appendChild(detail);
    row.appendChild(meta);
    card.appendChild(row);
    const extras = progressExtras(item);
    if (extras) card.appendChild(extras);
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

function miniProgress(label, valueText, progress, cls) {
    const wrap = el('div', 'ab-mini-prog');
    const head = el('div', 'ab-mini-prog-label');
    head.appendChild(el('span', '', label));
    const pctValue = progress == null ? null : Math.max(0, Math.min(100, Math.round(progress)));
    head.appendChild(el('span', '', [valueText, pctValue == null ? '' : pctValue + '%'].filter(Boolean).join(' · ')));
    wrap.appendChild(head);
    const bar = el('div', 'ab-mini-prog-bar');
    const fill = el('div', 'ab-mini-prog-fill' + (cls ? ' ' + cls : ''));
    fill.style.width = (pctValue == null ? 100 : pctValue) + '%';
    if (pctValue == null) fill.classList.add('is-indeterminate');
    bar.appendChild(fill);
    wrap.appendChild(bar);
    return wrap;
}

// 图片 / 动图 / 小说附加进度（队列项与当前卡共用）
function progressExtras(q) {
    const parts = el('div', 'ab-progress-extras');
    let has = false;
    const ip = q.imageProgress;
    if (ip && !['completed', 'failed', 'skipped'].includes(q.status)) {
        const imageText = ip.imageNumber && ip.totalImages
            ? bt('queue.image-download.index', '第 {current}/{total} 张', {current: ip.imageNumber, total: ip.totalImages})
            : '';
        const bytesText = ip.totalBytes > 0
            ? `${formatBytes(ip.downloadedBytes || 0)} / ${formatBytes(ip.totalBytes)}`
            : formatBytes(ip.downloadedBytes || 0);
        parts.appendChild(miniProgress(
            bt('queue.image-download.label', '图片下载'),
            [imageText, bytesText].filter(Boolean).join(' · '),
            ip.progress,
            'is-image'));
        has = true;
    }
    const up = q.ugoiraProgress;
    if (up && q.status !== 'completed' && up.status !== 'completed') {
        const phase = String(up.phase || '');
        if (phase === 'zip' || phase === 'extract' || phase === 'ffmpeg' || up.zipProgress !== undefined) {
            const zipBytes = up.zipTotalBytes > 0
                ? `${formatBytes(up.zipDownloadedBytes || 0)} / ${formatBytes(up.zipTotalBytes)}`
                : formatBytes(up.zipDownloadedBytes || 0);
            parts.appendChild(miniProgress(bt('queue.ugoira.zip', '动图压缩包'), zipBytes, up.zipProgress, 'is-zip'));
            has = true;
        }
        if (phase === 'extract') {
            parts.appendChild(el('p', 'ab-progress-note',
                up.totalFrames > 0
                    ? bt('queue.ugoira.extracting-count', '正在解压帧 {current}/{total}', {current: up.extractedFrames || 0, total: up.totalFrames})
                    : bt('queue.ugoira.extracting', '正在解压帧')));
        }
        if (phase === 'ffmpeg' || up.ffmpegProgress !== undefined) {
            const timeText = up.ffmpegDurationMs > 0
                ? `${formatDurationMs(up.ffmpegOutTimeMs || 0)} / ${formatDurationMs(up.ffmpegDurationMs)}`
                : '';
            parts.appendChild(miniProgress(bt('queue.ugoira.ffmpeg', 'ffmpeg 转换'), timeText, up.ffmpegProgress, 'is-ffmpeg'));
            has = true;
        }
        if (up.status === 'failed') {
            parts.appendChild(el('p', 'ab-progress-note ab-progress-note--error', bt('queue.ugoira.failed', '动图处理失败')));
        }
    }
    if (q.kind === 'novel' && q.translatePhase) {
        const msg = novelTranslateMessage(q);
        if (msg) {
            const line = el('p', 'ab-progress-note');
            line.appendChild(el('span', 'ab-mini-badge ab-mini-badge--ai', bt('queue.translate.label', 'AI 翻译')));
            line.appendChild(document.createTextNode(' ' + msg));
            parts.appendChild(line);
            has = true;
        }
    }
    return has ? parts : null;
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

function renderQueue() {
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
    state.queue.forEach((q, idx) => {
        list.appendChild(queueItemRow(q, idx));
    });
}

function queueItemRow(q, idx) {
    const row = el('div', 'ab-queue-item');
    row.dataset.queueId = String(q.id);
    row.dataset.status = q.status;
    row.style.setProperty('--stagger', String(Math.min(idx, 12)));

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
    if (q.status === 'downloading' && queueRuntime && queueRuntime.canCancel(q)) {
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
    if (q.status !== 'downloading') {
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

    const tags = el('div', 'ab-queue-tags');
    tags.appendChild(el('span', 'ab-queue-tag ab-queue-tag--source', queueDataSourceText(q)));
    tags.appendChild(el('span', 'ab-queue-tag ab-queue-tag--mode', queueSourceText(q.source)));
    const xr = q.xRestrict == null ? null : Number(q.xRestrict);
    if (xr === 2) tags.appendChild(el('span', 'ab-queue-tag ab-queue-tag--r18g', 'R-18G'));
    else if (xr === 1) tags.appendChild(el('span', 'ab-queue-tag ab-queue-tag--r18', 'R-18'));
    else if (xr === null || !Number.isFinite(xr)) {
        tags.appendChild(el('span', 'ab-queue-tag ab-queue-tag--unknown', bt('queue.unknown', '未知')));
    } else tags.appendChild(el('span', 'ab-queue-tag ab-queue-tag--sfw', 'SFW'));
    const contributedTags = queueRuntime ? queueRuntime.queueTags(q) : [];
    contributedTags.forEach(tag => {
        if (!tag || !tag.label) return;
        tags.appendChild(el('span', 'ab-queue-tag ab-queue-tag--plugin', tag.label));
    });
    row.appendChild(tags);

    const metaLine = el('div', 'ab-queue-meta');
    metaLine.appendChild(document.createTextNode('ID: ' + (q.kind === 'novel'
        ? (q.novelId || String(q.id).replace(/^n/, '')) + ' (Novel)'
        : q.id) + ' | '));
    const statusLine = el('span', 'ab-queue-status');
    statusLine.dataset.status = q.status;
    statusLine.textContent = queueItemMessage(q);
    metaLine.appendChild(statusLine);
    row.appendChild(metaLine);

    if (q.totalImages > 0) {
        row.appendChild(miniProgress(
            bt('status.image-progress', '{downloaded} / {total} 张',
                {downloaded: q.downloadedCount || 0, total: q.totalImages}),
            null, pct(q), 'is-' + q.status));
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
    box.innerHTML = '';
    const head = el('div', 'ab-quota-head');
    head.appendChild(abIconEl('gauge'));
    head.appendChild(el('strong', '', bt('quota.title', '下载配额')));
    box.appendChild(head);
    const line = el('div', 'ab-quota-line');
    line.textContent = bt('quota.used', '已用 {used} / {max} 个作品',
        {used: quota.artworksUsed, max: quota.maxArtworks});
    box.appendChild(line);
    const pctValue = Math.min(100, Math.round(quota.artworksUsed / Math.max(1, quota.maxArtworks) * 100));
    const bar = el('div', 'ab-mini-prog-bar');
    const fill = el('div', 'ab-mini-prog-fill' + (pctValue >= 90 ? ' is-danger' : pctValue >= 70 ? ' is-warn' : ''));
    fill.style.width = pctValue + '%';
    bar.appendChild(fill);
    box.appendChild(bar);
    if (quota.resetSeconds > 0) {
        box.appendChild(el('p', 'ab-field-note',
            bt('status.quota-reset', '配额重置剩余：{time}', {time: formatSeconds(quota.resetSeconds)})));
    }
}

function renderArchiveCard() {
    const box = document.getElementById('abArchiveBox');
    if (!box) return;
    const archive = dockState.archive;
    if (!archive.visible) {
        box.hidden = true;
        return;
    }
    box.hidden = false;
    box.innerHTML = '';
    const head = el('div', 'ab-quota-head');
    head.appendChild(abIconEl('box'));
    head.appendChild(el('strong', '', archive.title || bt('status.archive-limit', '已达到下载限额')));
    box.appendChild(head);
    if (archive.expired) {
        box.appendChild(el('p', 'ab-field-note ab-progress-note--error',
            bt('status.archive-expired', '链接已过期，请重新打包')));
        return;
    }
    if (!archive.ready) {
        const line = el('p', 'ab-loading-line', bt('status.archive-packing', '正在打包已下载文件，请稍候...'));
        box.appendChild(line);
        return;
    }
    box.appendChild(el('p', 'ab-field-note', bt('status.archive-ready', '压缩包已就绪，请在有效期内下载：')));
    const row = el('div', 'ab-archive-row');
    const dl = el('a', 'ab-btn ab-btn--primary ab-btn--sm');
    dl.href = BASE + '/api/archive/download/' + archive.token;
    dl.appendChild(abIconEl('download'));
    dl.appendChild(el('span', '', bt('archive.download', '下载压缩包')));
    row.appendChild(dl);
    const countdown = el('span', 'ab-archive-countdown');
    countdown.id = 'abArchiveCountdown';
    row.appendChild(countdown);
    box.appendChild(row);
    updateArchiveCountdown();
}

function updateArchiveCountdown() {
    const node = document.getElementById('abArchiveCountdown');
    if (!node) return;
    node.textContent = bt('status.archive-validity', '有效期：{time}',
        {time: formatSeconds(Math.max(0, dockState.archive.expireSeconds))});
}

window.PixivBatchAlt.queue = Object.assign(window.PixivBatchAlt.queue, {
    queueHas, pct, queueStatusText, queueSourceText, queueAcquisitionMode,
    queueItemDisplayTitle, queueItemCanonicalUrl, dedupeQueueItems,
    addItemsToQueue, removeFromQueue, buildQueueExportLines, commitQueueItemPatch,
    handleExport, handleExportFailed, saveQueue, loadQueueForMode, clearSavedQueue,
    setDockStatus, updateStats, renderDownloadSpeed, updateButtonsState,
    renderDock, renderQueue, renderCurrent, progressExtras, miniProgress,
    renderQuotaBar, renderArchiveCard, updateArchiveCountdown, queueItemCard,
    novelTranslateMessage
});
