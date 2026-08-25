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
            try {
                const status = await getDownloadStatus(String(artworkId));
                if (status && (status.completed || status.failed)) finish(status);
            } catch {
            }
        }, 5000);

        listener = data => {
            if (data && (data.completed || data.failed || data.cancelled)) {
                finish(data);
            } else if (data && data.downloadedCount !== undefined) {
                const q = state.queue.find(x => x.id === String(artworkId));
                if (q) {
                    q.downloadedCount = data.downloadedCount;
                    q.ugoiraProgress = mergeUgoiraProgress(q.ugoiraProgress, data.ugoiraProgress);
                    q.imageProgress = data.imageProgress || q.imageProgress || null;
                    renderQueue();
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
    if (!incoming) return existing || null;
    return {...(existing || {}), ...incoming};
}

/* ============================================================
   下载总速度计量
   ============================================================ */
function speedNowMs() {
    return (typeof performance !== 'undefined' && performance.now) ? performance.now() : Date.now();
}

// 累计单条传输流的正增量：每个传输流（某作品的第 N 张图 / 动图包 / 小说封面）的字节单调递增，
// 用 key 记住上次见到的值，只把正增量计入全局累计，从而跨多作品 / 多图正确汇总。
function addSpeedSample(key, cur) {
    if (!Number.isFinite(cur) || cur < 0) return;
    const prev = state.speedSamples[key] || 0;
    if (cur > prev) {
        state.speedAccumBytes += (cur - prev);
        state.speedSamples[key] = cur;
    } else if (cur < prev) {
        // 同 key 字节回退（极少见，视作新一段传输）：把当前值整体计入增量
        state.speedAccumBytes += cur;
        state.speedSamples[key] = cur;
    }
}

function accumulateDownloadSpeed(aid, data) {
    if (!aid || !data) return;
    const ip = data.imageProgress;
    if (ip) addSpeedSample(aid + ':img:' + (ip.imageNumber != null ? ip.imageNumber : 0), Number(ip.downloadedBytes));
    const up = data.ugoiraProgress;
    if (up) addSpeedSample(aid + ':zip', Number(up.zipDownloadedBytes));
    if (data.coverDownloadedBytes != null) addSpeedSample(aid + ':cover', Number(data.coverDownloadedBytes));
    // 该作品终态：清掉其传输流基线，避免 speedSamples 无限增长。
    if (data.completed || data.failed || data.cancelled) clearSpeedSamplesForItem(aid);
}

function clearSpeedSamplesForItem(aid) {
    const prefix = aid + ':';
    Object.keys(state.speedSamples).forEach(k => {
        if (k.indexOf(prefix) === 0) delete state.speedSamples[k];
    });
}

function startSpeedMeter() {
    if (state.speedTimer) return;   // 幂等：已在计量则不重置基线
    state.speedSamples = {};
    state.speedAccumBytes = 0;
    state.speedLastAccum = 0;
    state.speedLastTime = speedNowMs();
    renderDownloadSpeed(0);
    state.speedTimer = setInterval(sampleDownloadSpeed, 1000);
}

function stopSpeedMeter() {
    if (state.speedTimer) {
        clearInterval(state.speedTimer);
        state.speedTimer = null;
    }
    state.speedSamples = {};
    renderDownloadSpeed(0);
}

function sampleDownloadSpeed() {
    const now = speedNowMs();
    const dt = (now - state.speedLastTime) / 1000;
    const delta = state.speedAccumBytes - state.speedLastAccum;
    state.speedLastAccum = state.speedAccumBytes;
    state.speedLastTime = now;
    renderDownloadSpeed(dt > 0 ? Math.max(0, delta / dt) : 0);
}
