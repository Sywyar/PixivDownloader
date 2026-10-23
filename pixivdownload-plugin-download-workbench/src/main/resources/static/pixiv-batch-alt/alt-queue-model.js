'use strict';

function queueHas(id) {
    const key = String(id);
    return state.queue.some(q => String(q.id) === key);
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
    const seen = new Set();
    const uniqueItems = [];
    for (const item of items || []) {
        if (!item || item.id === undefined || item.id === null) continue;
        const id = String(item.id);
        if (seen.has(id)) continue;
        seen.add(id);
        uniqueItems.push({...item, id});
    }
    return uniqueItems;
}

function addItemsToQueue(idList, metaList, source, username, defaultAuthorId, defaultAuthorName) {
    const existing = new Set(state.queue.map(q => String(q.id)));
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
        existing.add(id);
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
    let removedObserved = false;
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
            const manual = parsed.queue.filter(item => item?.taskObserved !== true);
            state.queue = dedupeQueueItems(manual);
            state.queue.forEach(q => {
                if (restoreInterruptedQueueItem(q)) return;
                if (q.status === 'downloading') {
                    q.status = 'failed';
                    q.lastMessage = bt('queue.message.failed-refresh', '失败 — 页面刷新导致中断');
                }
            });
            state.isPaused = !!parsed.isPaused;
            state.stats = parsed.stats || {success: 0, failed: 0, active: 0, skipped: 0};
            removedObserved = manual.length !== parsed.queue.length;
        } else {
            state.queue = [];
        }
    } catch {
        state.queue = [];
    }
    orderDownloadQueue();
    renderQueue();
    updateStats();
    if (removedObserved) saveQueue();
}

function clearSavedQueue() {
    storeRemove(storageKey());
}
