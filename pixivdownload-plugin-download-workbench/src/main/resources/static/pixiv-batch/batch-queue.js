'use strict';
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
                // 刷新前正在下载的项目实际已中断，标记为失败
                state.queue.forEach(q => {
                    q.source = normalizeImportMode(q.source);
                    if (q.status === 'downloading') {
                        q.status = 'failed';
                        q.statusMessageKey = null;
                        q.lastMessage = bt('queue.message.failed-refresh', '失败 — 页面刷新导致中断');
                        q.lastMessageParts = null;
                    }
                    // 所有者的实时状态轮询不会随 localStorage 恢复，避免展示缓存中的过期运行态。
                    q.liveStatus = null;
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

    // 队列发生增 / 删 / 清空后，统一把四个模式预览网格的「✓ 在队列中」标记与最新 state.queue 对齐，
    // 避免清除队列或移除单项后 User / Search / 系列 / 快捷获取 预览残留过期的在队列标记。
    // 各 sync 在自身 state 为空或 DOM 不存在时自行早退，故任意当前模式下调用都安全。
    function syncAllResultsQueueState() {
        syncSearchResultsQueueState();
        syncSeriesResultsQueueState();
        syncUserResultsQueueState();
        syncQuickQueueState();
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


// ---- PixivBatch facade ----
window.PixivBatch.queue = window.PixivBatch.queue || {};
window.PixivBatch.queue = Object.assign(window.PixivBatch.queue, { addItemsToQueue, removeFromQueue, requestQueueItemCancel, bindQueueActions, reconcileQueueItemTypeData, commitQueueItemPatch, syncAllResultsQueueState, renderQueue, buildQueueItemHtml, updateStats, setCurrent, handleExport, handleExportFailed, dedupeQueueItems, queueItemDisplayTitle, queueItemCanonicalUrl, buildQueueExportLines, renderQueueMessageHtml });
