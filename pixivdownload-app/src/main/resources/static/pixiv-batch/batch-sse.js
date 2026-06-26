'use strict';
    /* ============================================================
       SSE — 共享单连接版：所有作品复用同一条聚合 EventSource，按 artworkId 路由
    ============================================================ */
    function ensureSharedSSE() {
        // 下载开始（worker 建立共享连接）即启动总速度计量；幂等。
        startSpeedMeter();
        if (state.sharedSse) return;
        const src = new EventSource(`${BASE}/api/sse/download`);
        src.addEventListener('aggregated-ready', e => {
            state.sharedSseConnectionId = e.data || null;
        });
        src.addEventListener('sse-closing', () => {
            if (state.sharedSse === src) {
                state.sharedSse = null;
                state.sharedSseConnectionId = null;
            }
            try { src.close(); } catch {}
        });
        src.addEventListener('download-status', e => {
            try {
                const data = JSON.parse(e.data);
                const aid = data && data.artworkId !== undefined && data.artworkId !== null
                    ? String(data.artworkId) : null;
                if (!aid) return;
                // 单一聚合连接是所有作品下载进度的汇聚点：在此累计字节用于总速度计量。
                accumulateDownloadSpeed(aid, data);
                (state.sseListeners[aid] || []).forEach(fn => fn(data));
            } catch {}
        });
        // EventSource 自动重连，无需手动处理 onerror
        state.sharedSse = src;
    }

    function notifyAggregatedSSEClosed(connectionId) {
        if (!connectionId) return Promise.resolve();
        return fetch(`${BASE}/api/sse/close/aggregated/${encodeURIComponent(connectionId)}`, {
            method: 'POST',
            credentials: 'same-origin',
            keepalive: true
        }).catch(() => {});
    }

    function closeSharedSSE() {
        const src = state.sharedSse;
        const connectionId = state.sharedSseConnectionId;
        state.sharedSse = null;
        state.sharedSseConnectionId = null;
        let closed = false;
        const closeLocal = () => {
            if (closed) return;
            closed = true;
            if (src) {
                try { src.close(); } catch {}
            }
        };
        const fallbackTimer = setTimeout(closeLocal, 1000);
        notifyAggregatedSSEClosed(connectionId).finally(() => {
            clearTimeout(fallbackTimer);
            closeLocal();
        });
    }

    function openSSE(artworkId) {
        const key = String(artworkId);
        state.sseRefs[key] = (state.sseRefs[key] || 0) + 1;
    }

    function closeSSE(artworkId) {
        const key = String(artworkId);
        if (state.sseRefs[key]) {
            state.sseRefs[key] -= 1;
            if (state.sseRefs[key] <= 0) delete state.sseRefs[key];
        }
        delete state.sseListeners[key];
    }

    function closeAllSSE() {
        // 全部下载结束 / 队列清空：停止总速度计量并清零显示。
        stopSpeedMeter();
        state.sseRefs = {};
        state.sseListeners = {};
        if (state.sharedSse) {
            closeSharedSSE();
        }
    }

    function addSSEListener(artworkId, fn) {
        const key = String(artworkId);
        if (!state.sseListeners[key]) state.sseListeners[key] = [];
        state.sseListeners[key].push(fn);
    }

    // 精确移除单个监听器（仅删除自己注册的那个回调，不影响同一 artworkId 上的其它监听）。
    function removeSSEListener(artworkId, fn) {
        const key = String(artworkId);
        const arr = state.sseListeners[key];
        if (!arr) return;
        const idx = arr.indexOf(fn);
        if (idx >= 0) arr.splice(idx, 1);
        if (!arr.length) delete state.sseListeners[key];
    }

    function waitForFinalStatusBySSE(artworkId, timeoutMs) {
        return new Promise(resolve => {
            let resolved = false;
            let timer = null;
            let pollTimer = null;

            const finish = (data) => {
                if (resolved) return;
                resolved = true;
                clearTimeout(timer);
                clearInterval(pollTimer);
                resolve(data);
            };

            timer = setTimeout(() => finish(null), timeoutMs);

            // 每5秒轮询一次，防止 SSE 事件丢失导致任务卡死
            pollTimer = setInterval(async () => {
                if (resolved) {
                    clearInterval(pollTimer);
                    return;
                }
                try {
                    const status = await getDownloadStatus(String(artworkId));
                    if (status && (status.completed || status.failed)) finish(status);
                } catch {
                }
            }, 5000);

            addSSEListener(artworkId, data => {
                if (data && (data.completed || data.failed || data.cancelled)) {
                    finish(data);
                } else if (data && data.downloadedCount !== undefined) {
                    const q = state.queue.find(x => x.id === String(artworkId));
                    if (q) {
                        q.downloadedCount = data.downloadedCount;
                        q.ugoiraProgress = mergeUgoiraProgress(q.ugoiraProgress, data.ugoiraProgress);
                        q.imageProgress = data.imageProgress || q.imageProgress || null;
                        renderQueue();
                        setCurrent(q);
                    }
                    clearTimeout(timer);
                    timer = setTimeout(() => finish(null), timeoutMs);
                }
            });
        });
    }


// ---- PixivBatch facade ----
window.PixivBatch.sse = window.PixivBatch.sse || {};
window.PixivBatch.sse = Object.assign(window.PixivBatch.sse, { ensureSharedSSE, closeSharedSSE, openSSE, closeSSE, closeAllSSE, addSSEListener, removeSSEListener, waitForFinalStatusBySSE });
