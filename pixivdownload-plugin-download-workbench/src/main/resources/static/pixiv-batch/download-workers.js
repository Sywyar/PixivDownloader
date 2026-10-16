'use strict';
    // 当前目标并发数（最少 1）；下载设置实时同步到 state.settings.concurrent。
    function desiredConcurrency() {
        return Math.max(1, parseInt(state.settings.concurrent, 10) || 1);
    }

    // 将运行中的 worker 数量补足到当前目标并发：用于启动、运行中追加队列、运行中调高并发数。
    // 调低并发数由各 worker 在 workerLoop 顶部自行退出，不在此处理。
    function ensureWorkers() {
        if (!state.isRunning || state.stopRequested) return;
        // 运行中调用同时负责幂等恢复被服务端关闭的聚合 SSE；即使 worker 已满也不能跳过。
        ensureSharedSSE();
        const workersToStart = Math.max(0, desiredConcurrency() - state.activeWorkers);
        if (workersToStart === 0) return;
        // 先为整批 worker 预留计数，再逐个启动。worker 可能在首次 await 前同步退出，
        // 固定启动次数与预留计数共同避免它反复改变循环条件，确保本次补充数量有界。
        state.activeWorkers += workersToStart;
        for (let i = 0; i < workersToStart; i++) {
            workerLoop().finally(handleWorkerExit);
        }
    }

    // 最后一个 worker 退出（队列已抽干）时统一收尾；调低并发数导致的多余 worker 退出不触发收尾。
    function handleWorkerExit() {
        if (!state.isRunning || state.activeWorkers > 0) return;
        finishBatch();
    }

    async function workerLoop() {
        try {
            while (state.isRunning && !state.stopRequested) {
                // 并发数实时调低时，多余的 worker 在处理完当前作品后退出（不中断在途下载）。
                if (state.activeWorkers > desiredConcurrency()) break;
                if (state.isPaused) {
                    await sleep(500);
                    continue;
                }
                const item = getNextPending();
                if (!item) {
                    if (state.queue.every(q =>
                        ['completed', 'failed', 'idle', 'paused', 'skipped'].includes(q.status))) break;
                    await sleep(500);
                    continue;
                }
                try {
                    await processSingle(item);
                } catch (e) {
                    console.error(bt('download.log.process-single-error', '处理单个作品失败'), e);
                } finally {
                    // 作品间隔实时生效：每次作品之间按当前设置读取间隔。
                    await sleep(getIntervalMs());
                }
            }
        } finally {
            state.activeWorkers--;
        }
    }

    function forceClearBackendQueue() {
        // 强制清除后端队列并终止所有正在进行的下载（多人模式下后端仅终止当前 owner 的任务）。
        // best-effort：后端失败不应阻塞前端清理。
        return fetch(BASE + '/api/download/queue/clear', {
            method: 'POST',
            credentials: 'same-origin'
        }).catch(() => {});
    }

    function clearDownloadQueue() {
        endPathActionBatch();
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
    }
