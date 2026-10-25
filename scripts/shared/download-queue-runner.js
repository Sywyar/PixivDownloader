// 领取前读取当前并发；降低并发不取消已开始的任务，冷却时间跟随当前设置。
async function runUserscriptDownloadQueue(manager, getConcurrent, signal) {
    const active = new Set();
    try {
        while (!signal?.aborted && manager.isRunning && !manager.stopRequested) {
            const configured = Number(getConcurrent());
            const limit = Number.isFinite(configured) ? Math.max(1, Math.floor(configured)) : 1;
            if (!manager.isPaused && active.size < limit) {
                const next = manager._getNextPending();
                if (next) {
                    const work = Promise.resolve().then(() => manager._processSingle(next, signal))
                        .catch(error => console.error(error))
                        .then(async () => {
                            const completedAt = Date.now();
                            while (!signal?.aborted && manager.isRunning && !manager.stopRequested
                                && Date.now() - completedAt < manager.getIntervalMs()) await manager._sleep(100);
                        }).finally(() => active.delete(work));
                    active.add(work);
                    continue;
                }
            }
            if (!manager.isPaused && !active.size && !manager.queue.some(item => item.status === 'pending')) break;
            await manager._sleep(100);
        }
    } finally {
        await Promise.allSettled(active);
    }
}

// 清空时结算并撤销本队列请求；已发送的后端写入仍由清空接口处理。
function requestUserscriptQueue(options, signal) {
    signal?.throwIfAborted();
    let handle, settled = false;
    const finish = callback => value => {
        if (settled) return;
        settled = true;
        signal?.removeEventListener('abort', cancel);
        callback?.(value);
    };
    const cancel = () => {
        finish(options.onerror)(signal.reason);
        try { handle?.abort(); } catch { /* 本地等待已结算，旧回调不再交付。 */ }
    };
    signal?.addEventListener('abort', cancel, {once: true});
    try {
        handle = GM_xmlhttpRequest({...options,
            onload: finish(options.onload), onerror: finish(options.onerror),
            ontimeout: finish(options.ontimeout || options.onerror),
            onabort: finish(options.onabort || options.onerror)
        });
        if (signal?.aborted) handle?.abort();
        return handle;
    } catch (error) {
        signal?.removeEventListener('abort', cancel);
        throw error;
    }
}

// 终态等待与队列共同撤销；状态补查每项只保留一个在途请求。
function waitForUserscriptDownload(manager, artworkId, timeoutMs, signal) {
    return new Promise((resolve, reject) => {
        if (signal.aborted) { resolve(null); return; }
        let resolved = false;
        const requestedBase = serverBase;
        let polling = false;
        let timer = null;
        let pollTimer = null;

        const finish = (data, error) => {
            if (resolved) return;
            resolved = true;
            clearTimeout(timer);
            clearInterval(pollTimer);
            signal.removeEventListener('abort', cancel);
            manager.sse.close(String(artworkId));
            if (error) reject(error);
            else resolve(data);
        };

        const targetChanged = () => {
            if (requestedBase === serverBase) return false;
            finish(null, new Error(UserscriptDownloadOptions.text('unavailable')));
            return true;
        };
        const cancel = () => finish(null);
        signal.addEventListener('abort', cancel, {once: true});
        timer = setTimeout(() => finish(null), timeoutMs);

        // 每5秒轮询一次，防止 SSE 事件丢失导致任务卡死
        pollTimer = setInterval(async () => {
            if (resolved) {
                clearInterval(pollTimer);
                return;
            }
            if (targetChanged() || polling) return;
            polling = true;
            try {
                const status = await Api.getDownloadStatus(String(artworkId), signal);
                if (!targetChanged() && status && (status.completed || status.failed)) finish(status);
            } catch (error) {
                if (error?.code === 'AUTH_REQUIRED') finish(null, error);
            } finally {
                polling = false;
            }
        }, 5000);

        const handler = (data) => {
            if (resolved || !data || targetChanged()) return;
            // 切换服务器后新领取的作品由补查观察，不消费旧批次连接的同名事件。
            if (manager.sse.url && manager.sse.url !== CONFIG.SSE_BASE) return;
            if (data.completed || data.failed || data.cancelled) {
                finish(data);
            } else {
                const q = manager.queue.find(x => x.id === String(artworkId));
                if (q) {
                    if (data.downloadedCount !== undefined) q.downloadedCount = data.downloadedCount;
                    if (data.ugoiraProgress) q.ugoiraProgress = mergeUgoiraProgress(q.ugoiraProgress, data.ugoiraProgress);
                    if (data.imageProgress) q.imageProgress = data.imageProgress;
                    manager.saveToStorage();
                    manager.ui.renderQueue(manager.queue);
                    manager.ui.setCurrent(q);
                }
                clearTimeout(timer);
                timer = setTimeout(() => finish(null), timeoutMs);
            }
        };
        manager.sse.addListener(String(artworkId), handler);
    });
}
