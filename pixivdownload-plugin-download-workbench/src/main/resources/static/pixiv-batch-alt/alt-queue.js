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
    if (source === 'task') return bt('batch:queue.task.external');
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
        if (item && item.taskObserved) item = Object.assign({}, item, {id: item.workId});
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
    const additions = [];
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
        additions.push(queueItem);
        existing.set(id, queueItem);
    }
    const added = additions.length;
    // 一批新任务保持取得顺序并整体前置，不反转批内顺序，也不中断已领取的任务。
    if (added) state.queue = additions.concat(state.queue);
    updateStats();
    saveQueue();
    renderQueue();
    if (state.isRunning && added > 0) {
        ensureWorkers();
    } else if (added > 0 && state.settings?.autoStartOnEnqueue === true && isAdmin && !state.isPaused) {
        start(true);
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
    renderQueue(item, true);
    return item;
}

function queueItemMessage(q) {
    if (!q) return '';
    return q.statusMessageKey
        ? bt(q.statusMessageKey, q.lastMessage || queueStatusText(q.status), {count: q.downloadedCount})
        : q.lastMessage || queueStatusText(q.status);
}

function removeFromQueue(id) {
    const idx = state.queue.findIndex(q => q.id === String(id));
    if (idx === -1) return false;
    const q = state.queue[idx];
    if (q.status === 'downloading') return false;
    window.PixivBatch.queueTasks?.dismiss([state.queue[idx]]);
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
    setDockStatus({key: 'status.exported-all', fallback: '已导出 {count} 个作品', args: {count: lines.length}}, 'success');
}

async function handleExportFailed() {
    const items = state.queue.filter(q => q.status !== 'completed');
    if (!items.length) {
        await abAlert('alert.no-undownloaded', '没有未下载的作品');
        return;
    }
    const lines = buildQueueExportLines(items);
    downloadTxt(lines.join('\n'), `pixiv_undownloaded_list_${Date.now()}.txt`);
    setDockStatus({key: 'status.exported-undownloaded', fallback: '已导出 {count} 个未下载作品', args: {count: lines.length}}, 'success');
}

/* ============================================================
   持久化（与现行页同一 storage key，互为兼容）
   ============================================================ */
function storageKey() {
    return 'pixiv_batch_queue';
}

let queueOrderChanged = false;

function orderDownloadQueue() {
    const finished = item => item.status === 'completed' || item.status === 'skipped';
    let seenFinished = false;
    for (const item of state.queue) {
        if (finished(item)) seenFinished = true;
        else if (seenFinished) {
            // 仅状态保存或恢复时重排，复用条目对象；数值进度仍按原有脏行机制刷新。
            state.queue.sort((a, b) => Number(finished(a)) - Number(finished(b)));
            queueOrderChanged = true;
            return;
        }
    }
}

function saveQueue() {
    orderDownloadQueue();
    let snapshot = {
        queue: state.queue,
        isPaused: state.isPaused,
        stats: state.stats,
        savedAt: new Date().toISOString()
    };
    let serialized;
    const serialize = () => {
        if (snapshot) {
            serialized = JSON.stringify(snapshot);
            snapshot = null;
        }
        return serialized;
    };
    // 单人模式在现有保存或同步读取时物化；替换和删除自然释放尚未序列化的队列引用。
    storeSet(storageKey(), appMode === 'solo' ? {toJSON: serialize, toString: serialize} : serialize());
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
                if (restoreInterruptedQueueItem(q)) return;
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
    orderDownloadQueue();
    renderQueue();
    updateStats();
}

function clearSavedQueue() {
    storeRemove(storageKey());
}

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
    card.appendChild(el('span', 'ab-stat-label', bt(labelKey, labelFallback)));
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
    for (const item of state.queue) {
        switch (item.status) {
            case 'completed': state.stats.success++; break;
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
    updateButtonsState(state.stats);
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

function updateButtonsState(stats) {
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
        packBtn.disabled = stats ? stats.success === 0 : !state.queue.some(q => q.status === 'completed');
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

// 队首未完成项 = 队列镜像中第一个 status 属于 downloading/pending/paused 的项（completed/failed/skipped/idle
// 视为已结束）。含 pending/paused 是为了避免并发=1 时「上一项完成 → 下一项被认领」的间隙短暂闪「无」。暂停
//（停止接受新任务）期间仍展示正在收尾下载的作品（drain）：有 downloading 项时照常展示队首；仅当没有任何
// downloading 项时才回退 idle「无」。queue 参数传 reactive 队列镜像（Vue 路径）或 state.queue（命令式路径），
// 两路共用同一口径。
function currentFrontItem(queue, isPaused) {
    for (let i = 0; i < queue.length; i++) {
        const s = queue[i].status;
        if (s === 'downloading') return queue[i];
    }
    if (isPaused) return null;
    for (let i = 0; i < queue.length; i++) {
        const s = queue[i].status;
        if (s === 'pending' || s === 'paused') return queue[i];
    }
    return null;
}

// 剩余计数原始数据：{downloading, queued}。展示的队首不计入「还有」；文案渲染期经 bt 派生（模型不 bake 翻译）。
function currentRemainingCounts(queue, front) {
    let downloading = 0, queued = 0;
    for (let i = 0; i < queue.length; i++) {
        const s = queue[i].status;
        if (s === 'downloading') downloading++;
        else if (s === 'pending' || s === 'paused') queued++;
    }
    if (front) {
        if (front.status === 'downloading') downloading--;
        else queued--;
    }
    return {downloading, queued};
}

// 剩余计数行文案（命令式回退与 Vue 路径共用；两项都为 0 时返回空串）。
function currentRemainingLineText(downloading, queued) {
    if (downloading > 0 && queued > 0) {
        return bt('status.current-remaining.both', '还有 {downloading} 个正在下载、{queued} 个排队中…',
            {downloading: downloading, queued: queued});
    }
    if (downloading > 0) {
        return bt('status.current-remaining.downloading', '还有 {count} 个正在下载…', {count: downloading});
    }
    if (queued > 0) {
        return bt('status.current-remaining.queued', '还有 {count} 个排队中…', {count: queued});
    }
    return '';
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
    row.appendChild(meta);
    card.appendChild(row);
    // 流式图片进度条（与列表行 miniProgress 同口径，由 downloadedCount / totalImages 派生，始终实时可见）。
    if (item.totalImages > 0) {
        card.appendChild(miniProgress(model.progressLabel, null, percent, 'is-image'));
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
        {downloaded: item.downloadedCount || 0, total: item.totalImages}) : '';
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
    fill.style.width = (pctValue == null ? 35 : pctValue) + '%';
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

function progressExtrasModel(q) {
    const parts = [];
    let has = false;
    const progress = (key, label, value, percent, cls, active = true) => {
        parts.push({key, kind: 'progress', label, value, progress: percent, cls, active});
        has = true;
    };
    const runtime = window.PixivBatch && window.PixivBatch.queueTypes;
    const live = runtime && typeof runtime.queueLiveStatus === 'function' ? runtime.queueLiveStatus(q) : null;
    if (live && live.label && live.message && ['info', 'success', 'warning', 'error'].includes(live.tone)) {
        parts.push({key: 'live', tone: live.tone, badge: live.label, text: live.message});
        has = true;
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
                progress('image-' + (ip.imageNumber || 0) + '-' + ip.phase, label, imageText, null,
                    ip.status === 'failed' ? 'is-failed' : 'is-ffmpeg', ip.status !== 'failed');
            } else progress('image-' + (ip.imageNumber || 0),
                bt('queue.image-download.label', '图片下载'),
                [imageText, bytesText].filter(Boolean).join(' · '),
                ip.progress,
                ip.status === 'failed' ? 'is-failed' : 'is-image', ip.status !== 'failed');
            has = true;
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
            parts.push({key: 'extract', text: up.totalFrames > 0
                    ? bt('queue.ugoira.extracting-count', '正在解压帧 {current}/{total}', {current: up.extractedFrames || 0, total: up.totalFrames})
                    : bt('queue.ugoira.extracting', '正在解压帧')});
        }
        if (phase === 'ffmpeg-waiting' || phase === 'finalizing') {
            progress('ffmpeg-waiting', mediaProgressLabel(up), '', null, 'is-ffmpeg', up.status !== 'failed');
        }
        if (phase === 'ffmpeg' && up.status !== 'failed') {
            const timeText = up.ffmpegDurationMs > 0
                ? `${formatDurationMs(up.ffmpegOutTimeMs || 0)} / ${formatDurationMs(up.ffmpegDurationMs)}`
                : '';
            progress('ffmpeg', mediaProgressLabel(up), timeText, up.ffmpegProgress, 'is-ffmpeg');
        }
        if (up.status === 'failed') {
            parts.push({key: 'ugoira-error', tone: 'error', text: bt('queue.ugoira.failed', '动图处理失败')});
        }
    }
    if (q.kind === 'novel' && q.translatePhase) {
        const msg = novelTranslateMessage(q);
        if (msg) {
            parts.push({key: 'translate', badge: bt('queue.translate.label', 'AI 翻译'), ai: true, text: msg});
            has = true;
        }
    }
    return has ? parts : [];
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
    metaLine.appendChild(document.createTextNode('ID: ' + (q.taskObserved ? q.workId : q.kind === 'novel'
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
    renderQuotaBar, renderArchiveCard, updateArchiveCountdown, queueItemCard: queueItemRow,
    novelTranslateMessage, ensureDockVue, altQueueVueActive
});
