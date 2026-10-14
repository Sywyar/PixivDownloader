'use strict';

/* ============================================================
   聚合 SSE（单一长连接 + 按 artworkId 路由 + 速度计量）
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
    src.addEventListener('plugin-unavailable', e => {
        // 下载类型所属插件停用时后端统一推流关闭：按消息提示并终止当前批次
        const message = e && e.data ? String(e.data) : '';
        if (message) setDockStatus(message, 'error');
    });
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

function removeSSEListener(artworkId, fn) {
    const key = String(artworkId);
    const arr = state.sseListeners[key];
    if (!arr) return;
    const idx = arr.indexOf(fn);
    if (idx >= 0) arr.splice(idx, 1);
    if (!arr.length) delete state.sseListeners[key];
}

function waitForFinalStatusBySSE(artworkId, timeoutMs) {
    return new Promise((resolve, reject) => {
        let settled = false;
        let timer = null;
        let pollTimer = null;
        let listener = null;
        let polling = false;
        let revision = 0;

        const cleanup = () => {
            clearTimeout(timer);
            clearInterval(pollTimer);
            if (listener) removeSSEListener(artworkId, listener);
        };

        const finish = (data) => {
            if (settled) return;
            settled = true;
            cleanup();
            resolve(data);
        };

        timer = setTimeout(() => finish(null), timeoutMs);

        // 每5秒轮询一次，防止 SSE 事件丢失导致任务卡死
        pollTimer = setInterval(async () => {
            if (settled) {
                clearInterval(pollTimer);
                return;
            }
            if (polling) return;
            polling = true;
            const observedRevision = revision;
            try {
                const status = await getDownloadStatus(String(artworkId));
                if (!settled && observedRevision === revision && status && status.success !== false) listener(status);
            } catch {
            } finally { polling = false; }
        }, 5000);

        listener = data => {
            if (settled) return;
            revision += 1;
            if (data && (data.completed || data.failed || data.cancelled)) {
                finish(data);
            } else if (data && data.downloadedCount !== undefined) {
                const q = state.queue.find(x => x.id === String(artworkId));
                if (q) {
                    q.downloadedCount = data.downloadedCount;
                    q.ugoiraProgress = mergeUgoiraProgress(q.ugoiraProgress, data.ugoiraProgress);
                    q.imageProgress = data.imageProgress || null;
                    renderQueue(q);
                    renderCurrent(q);
                }
                clearTimeout(timer);
                timer = setTimeout(() => finish(null), timeoutMs);
            }
        };
        addSSEListener(artworkId, listener);
    });
}

function mergeUgoiraProgress(existing, incoming) {
    return incoming === undefined ? existing || null : incoming ? {...incoming} : null;
}
