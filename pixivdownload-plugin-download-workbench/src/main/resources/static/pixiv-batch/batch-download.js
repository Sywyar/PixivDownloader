'use strict';
    function pause() {
        if (!state.isRunning) return;
        state.isPaused = true;
        state.queue.forEach(q => {
            if (q.status === 'pending') q.status = 'paused';
        });
        saveQueue();
        const active = state.queue.filter(q => q.status === 'downloading').length;
        setStatus(
            active > 0
                ? bt('status.pausing-active', '正在暂停... (等待 {count} 个任务完成)', {count: active})
                : bt('status.paused', '已暂停'),
            'warning'
        );
        updateButtonsState();
        // 暂停时清除当前下载状态（回退 idle「无」）；恢复后由 resume 的渲染门面自动重新派生队首。
        refreshCurrentCard();
    }

    function resume() {
        if (!state.isRunning) {
            start();
            return;
        }
        state.isPaused = false;
        state.queue.forEach(q => {
            if (q.status === 'paused') q.status = 'pending';
        });
        saveQueue();
        setStatus(bt('status.resume-download', '继续下载'), 'info');
        updateButtonsState();
        // 恢复后经渲染门面重新派生队首（暂停期间当前卡已回退 idle「无」）。
        refreshCurrentCard();
    }

    function forceClearBackendQueue() {
        // 强制清除后端队列并终止所有正在进行的下载（多人模式下后端仅终止当前 owner 的任务）。
        // best-effort：后端失败不应阻塞前端清理。
        return fetch(BASE + '/api/download/queue/clear', {
            method: 'POST',
            credentials: 'same-origin'
        }).catch(() => {});
    }

    function stopAndClear() {
        state.stopRequested = true;
        state.isRunning = false;
        state.isPaused = false;
        forceClearBackendQueue();
        // 立即触发所有等待中的 SSE Promise resolve，避免等 5 分钟超时
        Object.keys(state.sseListeners).forEach(id => {
            (state.sseListeners[id] || []).forEach(fn => fn({cancelled: true}));
        });
        closeAllSSE();
        state.queue = [];
        state.stats = {success: 0, failed: 0, active: 0, skipped: 0};
        clearSavedQueue();
        renderQueue();
        updateButtonsState();
        updateStats();
        syncAllResultsQueueState();
        setStatus(bt('status.queue-cleared', '队列已清除'), 'info');
    }

    /* ============================================================
       按钮处理
    ============================================================ */
    async function handleStart() {
        syncSettings();
        start();
    }

    function handlePause() {
        if (state.isPaused) resume(); else pause();
    }

    async function handleRetry() {
        const failed = state.queue.filter(q => q.status === 'failed');
        if (!failed.length) {
            await uiAlertKey('alert.no-failed', '当前没有失败的作品');
            return;
        }
        failed.forEach(q => {
            q.status = 'pending';
            q.statusMessageKey = null;
            q.lastMessage = '';
            q.startTime = null;
            q.endTime = null;
        });
        saveQueue();
        renderQueue();
        syncSettings();
        await start();
    }

    async function handleClear() {
        if (!await uiConfirmKey('dialog.confirm-clear-queue', '确认清除队列？')) return;
        stopAndClear();
    }

    function updateButtonsState() {
        document.getElementById('btn-start').disabled = state.isRunning;
        document.getElementById('btn-pause').disabled = !state.isRunning;
        document.getElementById('btn-pause').textContent = state.isPaused
            ? bt('button.resume', '▶ 继续')
            : bt('button.pause', '⏸ 暂停');
        updateAdminPackButton();
    }

    function updateAdminPackButton() {
        const btn = document.getElementById('admin-pack-btn');
        if (!btn) return;
        if (!isAdmin) {
            btn.style.display = 'none';
            btn.disabled = true;
            return;
        }
        btn.style.display = 'inline-flex';
        btn.disabled = !state.queue.some(q => q.status === 'completed');
    }

// ---- PixivBatch facade ----
window.PixivBatch.download = window.PixivBatch.download || {};
window.PixivBatch.download = Object.assign(window.PixivBatch.download, { start, pause, resume, stopAndClear, handleStart, handlePause, handleRetry, handleClear, sendDownload, triggerAdminPack, processSingle, processIllustItem });
