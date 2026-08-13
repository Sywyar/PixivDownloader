'use strict';
/* ============================================================
   alt-engine — 下载执行引擎
   worker 池 / 插画下载流程 / 聚合 SSE / 速度计量 / 配额与归档，
   逐字移植 pixiv-batch/batch-download.js + batch-sse.js（UI 门面换成
   新坞的 renderQueue/renderCurrent/updateStats/setDockStatus）。
   插画（illust）走完整真实流程；其它类型按「类型当前不可用」暂停
   （与宿主对缺失行为模块的处理一致）。
   ============================================================ */
const STATUS_TIMEOUT_MS = 300000;

let quotaExceededHandled = false;
let archiveCountdownTimer = null;
let archivePollTimer = null;
let quotaResetTimer = null;
// 本页面会话是否已派发「首次下载完成」事件（布局偏好调查在该时刻弹出一次）。
let firstDownloadCompletedNotified = false;

function notifyFirstDownloadCompleted() {
    if (firstDownloadCompletedNotified) return;
    firstDownloadCompletedNotified = true;
    try {
        document.dispatchEvent(new CustomEvent('pixiv:first-download-completed'));
    } catch (_) {
        // 调查事件派发失败不中断下载
    }
}

/* ============================================================
   Pixiv 作品请求
   ============================================================ */
async function getArtworkMeta(artworkId) {
    const data = await apiGet(`/api/pixiv/artwork/${artworkId}/meta`);
    if (data.error) throw new Error(data.error);
    return data;
}

async function getArtworkPages(artworkId) {
    const data = await apiGet(`/api/pixiv/artwork/${artworkId}/pages`);
    if (data.error) throw new Error(data.error);
    return data.urls || [];
}

async function getUgoiraMeta(artworkId) {
    const data = await apiGet(`/api/pixiv/artwork/${artworkId}/ugoira`);
    if (data.error) throw new Error(data.error);
    return data;
}

async function checkDownloaded(artworkId) {
    try {
        const query = state.settings.verifyHistoryFiles ? '?verifyFiles=true' : '';
        const res = await fetch(`${BASE}/api/downloaded/${artworkId}${query}`);
        if (res.status === 200) {
            const data = await res.json();
            if (!data.artworkId) return null;
            return data;
        }
        return null;
    } catch {
        return null;
    }
}

// 两阶段恢复：磁盘恢复出的裸记录用前端拉到的 Pixiv 元数据补齐缺失字段（后端幂等）。
async function recoverArtworkMetadata(artworkId, meta) {
    try {
        const res = await fetch(`${BASE}/api/downloaded/${artworkId}/recover-metadata`, {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify(meta)
        });
        if (res.status === 200) {
            return await res.json();
        }
    } catch (e) {
        console.warn(bt('download.log.recover-metadata-failed', '恢复作品元数据失败: artworkId={id}', {id: artworkId}), e);
    }
    return null;
}

/* ============================================================
   下载提交（POST /api/download/pixiv，payload 与现行引擎一致）
   ============================================================ */
async function sendDownload(artworkId, imageUrls, title, isUserDownload, username, authorId, authorName, xRestrict, isAi, ugoiraData, description, tags, seriesInfo, illustType, rawMetaJson) {
    const delayMs = getImageDelayMs();
    const collectionId = state.settings.collectionId;
    const fileNameTemplate = normalizeFileNameTemplate(state.settings.fileNameTemplate);
    const fileNameTimestamp = Date.now();
    const fileNames = buildDownloadFileNames(fileNameTemplate, {
        artworkId,
        title,
        authorId: normalizeAuthorId(authorId),
        authorName,
        xRestrict,
        isAi,
        timestamp: fileNameTimestamp
    }, imageUrls.length);
    const other = {
        userDownload: isUserDownload,
        username: username || '',
        authorId: normalizeAuthorId(authorId),
        authorName: authorName || null,
        xRestrict: Number(xRestrict) || 0,
        isAi: !!isAi,
        delayMs,
        bookmark: !!state.settings.bookmark,
        collectionId,
        description: description || null,
        tags: Array.isArray(tags) && tags.length ? tags : null,
        fileNameTemplate,
        fileNames,
        fileNameTimestamp
    };
    if (seriesInfo && seriesInfo.seriesId) {
        other.seriesId = Number(seriesInfo.seriesId);
        other.seriesOrder = Number(seriesInfo.seriesOrder ?? 0);
        other.seriesTitle = seriesInfo.seriesTitle || null;
        if (seriesInfo.seriesDescription) other.seriesDescription = seriesInfo.seriesDescription;
        if (seriesInfo.seriesCoverUrl) other.seriesCoverUrl = seriesInfo.seriesCoverUrl;
    }
    if (illustType != null && Number.isFinite(Number(illustType))) {
        other.illustType = Number(illustType);
    }
    if (rawMetaJson) {
        other.rawMetaJson = rawMetaJson;
    }
    if (ugoiraData) {
        other.isUgoira = true;
        other.ugoiraZipUrl = ugoiraData.zipUrl;
        other.ugoiraDelays = ugoiraData.delays;
    }
    const payload = {
        artworkId: parseInt(artworkId),
        imageUrls,
        title,
        referer: 'https://www.pixiv.net/',
        cookie: getCookie(),
        other
    };
    const res = await fetch(`${BASE}/api/download/pixiv`, {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        credentials: 'same-origin',
        body: JSON.stringify(payload)
    });
    const data = await res.json();
    if (res.status === 429 && data.quotaExceeded) {
        if (!quotaExceededHandled) {
            quotaExceededHandled = true;
            handleQuotaExceeded(data);
        }
        const err = new Error('quota_exceeded');
        err.quotaData = data;
        throw err;
    }
    if (!res.ok) throw new Error(data.message || bt('status.backend-failure', '后端返回失败'));
    return data;
}

async function getDownloadStatus(artworkId) {
    const res = await fetch(`${BASE}/api/download/status/${artworkId}`);
    return res.json();
}

/* ============================================================
   配额 & 压缩包
   ============================================================ */
async function initQuota() {
    try {
        const res = await fetch(BASE + '/api/quota/init', {method: 'POST', credentials: 'same-origin'});
        if (!res.ok) return;
        const data = await res.json();
        dockState.quota.adminMode = !!data.adminMode;
        if (dockState.quota.adminMode || !data.enabled) {
            dockState.quota.enabled = false;
            renderQuotaBar();
            return;
        }
        dockState.quota = {
            enabled: true, adminMode: false,
            artworksUsed: data.artworksUsed, maxArtworks: data.maxArtworks,
            resetSeconds: data.resetSeconds
        };
        renderQuotaBar();
        startQuotaResetCountdown();
        // 恢复已有的压缩包链接
        if (data.archive && data.archive.token) {
            showArchiveCard(data.archive.token, data.archive.expireSeconds, data.archive.status === 'ready');
        }
    } catch {
    }
}

function startQuotaResetCountdown() {
    clearInterval(quotaResetTimer);
    if (dockState.quota.resetSeconds <= 0) return;
    quotaResetTimer = setInterval(() => {
        if (dockState.quota.resetSeconds > 0) dockState.quota.resetSeconds--;
        renderQuotaBar();
        if (dockState.quota.resetSeconds <= 0) clearInterval(quotaResetTimer);
    }, 1000);
}

function handleQuotaExceeded(data) {
    dockState.quota.artworksUsed = data.artworksUsed || dockState.quota.artworksUsed;
    renderQuotaBar();

    // 标记所有未开始/等待中的队列项为失败
    state.queue.forEach(q => {
        if (['pending', 'idle', 'paused'].includes(q.status)) {
            q.status = 'failed';
            q.lastMessage = bt('queue.message.failed-quota', '失败 - 达到限额');
            q.endTime = q.endTime || new Date().toISOString();
        }
    });
    state.stopRequested = true;
    state.isRunning = false;
    updateStats();
    saveQueue();
    renderQueue();
    updateButtonsState();
    setDockStatus(bt('status.archive-limit', '已达到下载限额'), 'error');

    const token = data.archiveToken;
    const expireSeconds = data.archiveExpireSeconds || 3600;
    showArchiveCard(token, expireSeconds, false);
}

function showArchiveCard(token, expireSeconds, ready, title) {
    clearInterval(archiveCountdownTimer);
    clearInterval(archivePollTimer);
    dockState.archive = {
        visible: true, token, expireSeconds, ready: !!ready, expired: false,
        title: title || bt('status.archive-limit', '已达到下载限额')
    };
    renderArchiveCard();
    if (ready) {
        activateArchiveDownload(token, expireSeconds);
    } else {
        pollArchiveReady(token, expireSeconds);
    }
}

function pollArchiveReady(token, expireSeconds) {
    archivePollTimer = setInterval(async () => {
        try {
            const res = await fetch(BASE + '/api/archive/status/' + token);
            const data = await res.json();
            if (data.status === 'ready') {
                clearInterval(archivePollTimer);
                activateArchiveDownload(token, data.expireSeconds || expireSeconds);
            } else if (data.status === 'expired') {
                clearInterval(archivePollTimer);
                showArchiveExpired();
            } else if (data.status === 'empty') {
                clearInterval(archivePollTimer);
                dockState.archive.ready = false;
                const box = document.getElementById('abArchiveBox');
                if (box) {
                    box.innerHTML = '';
                    box.appendChild(el('p', 'ab-field-note',
                        bt('status.archive-empty', '暂无可打包文件（当前下载仍在进行中，完成后自动包含）')));
                }
            }
        } catch {
        }
    }, 2000);
}

function activateArchiveDownload(token, expireSeconds) {
    dockState.archive.token = token;
    dockState.archive.ready = true;
    dockState.archive.expireSeconds = Math.max(0, parseInt(expireSeconds));
    renderArchiveCard();
    archiveCountdownTimer = setInterval(() => {
        dockState.archive.expireSeconds--;
        if (dockState.archive.expireSeconds <= 0) {
            clearInterval(archiveCountdownTimer);
            showArchiveExpired();
        } else {
            updateArchiveCountdown();
        }
    }, 1000);
}

function showArchiveExpired() {
    dockState.archive.ready = false;
    dockState.archive.expired = true;
    renderArchiveCard();
}

async function autoPackAfterQueue() {
    try {
        const res = await fetch(BASE + '/api/quota/pack', {
            method: 'POST',
            credentials: 'same-origin'
        });
        if (res.status === 204) return; // 无文件可打包（可能已被打包或源文件已删除）
        if (!res.ok) return;
        const data = await res.json();
        if (data.archiveToken) {
            setDockStatus(bt('status.batch-finished-packing', '批量下载结束，正在打包文件...'), 'info');
            showArchiveCard(
                data.archiveToken,
                data.archiveExpireSeconds || 3600,
                false,
                bt('status.download-complete-packing', '下载完成，正在打包')
            );
        }
    } catch {
    }
}

async function triggerAdminPack() {
    const ids = state.queue
        .filter(q => q.status === 'completed' && q.kind === 'illust')
        .map(q => Number(q.id))
        .filter(Number.isFinite);

    if (ids.length === 0) {
        setDockStatus(bt('status.no-completed-to-pack', '队列中暂无已完成的作品可供打包'), 'warning');
        return;
    }

    try {
        const res = await fetch(BASE + '/api/archive/pack-artworks', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            credentials: 'same-origin',
            body: JSON.stringify({artworkIds: ids})
        });

        if (res.status === 401) {
            isAdmin = false;
            renderAuthButton();
            updateButtonsState();
            setDockStatus(bt('status.login-expired', '登录状态已失效，请重新登录'), 'error');
            return;
        }

        if (res.status === 204) {
            setDockStatus(bt('status.pack-folder-missing', '数据库中未找到对应文件夹，可能已被移动或删除'), 'warning');
            return;
        }

        let data = null;
        try {
            data = await res.json();
        } catch {
        }

        if (!res.ok) {
            setDockStatus(
                (data && data.message)
                    ? data.message
                    : bt('status.pack-failed-http', '打包失败：HTTP {code}', {code: res.status}),
                'error');
            return;
        }

        setDockStatus(
            bt('status.pack-request-submitted', '已提交打包请求（{count} 个作品），正在生成压缩包...', {count: ids.length}),
            'info');
        showArchiveCard(
            data.archiveToken,
            data.archiveExpireSeconds || 3600,
            false,
            bt('status.admin-packing', '管理员打包中（{count} 个作品）', {count: ids.length})
        );
    } catch (e) {
        setDockStatus(bt('status.pack-request-failed', '打包请求失败：{message}', {message: e.message}), 'error');
    } finally {
        updateButtonsState();
    }
}

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

/* ============================================================
   下载管理器（worker 池）
   ============================================================ */
async function start() {
    if (state.isRunning) return;
    if (state.queue.length === 0) {
        setDockStatus(bt('status.queue-empty', '队列为空'), 'error');
        return;
    }
    if (!await checkBackend()) {
        await abAlert('alert.backend-unavailable', '后端服务不可用，请确认后端已启动');
        return;
    }

    await refreshBatchCollections();
    // 后端检查 / 收藏夹刷新期间可能发生重复点击；较早的调用已经启动时，后续调用不得重置 worker 状态。
    if (state.isRunning) return;

    state.queue.forEach(q => {
        if (['idle', 'failed', 'paused'].includes(q.status)) {
            q.status = 'pending';
        }
    });
    state.isPaused = false;
    state.stopRequested = false;
    state.activeWorkers = 0;
    quotaExceededHandled = false;

    // completed / skipped 是终态；队列没有待处理项时直接收尾，避免打开 SSE 或启动空 worker 池。
    if (!state.queue.some(q => q.status === 'pending')) {
        state.isRunning = false;
        updateStats();
        saveQueue();
        renderQueue();
        setDockStatus(bt('status.batch-finished', '批量下载结束'), 'info');
        updateButtonsState();
        return;
    }
    state.isRunning = true;

    updateStats();
    updateButtonsState();
    saveQueue();
    renderQueue();
    setDockStatus(
        bt('status.start-download', '开始下载 (并发:{concurrent}, 间隔:{intervalMs}ms)',
            {concurrent: desiredConcurrency(), intervalMs: getIntervalMs()}),
        'info');

    // 并发数与作品间隔实时生效：worker 池按当前目标并发动态伸缩，间隔在每次作品之间实时读取。
    ensureWorkers();
}

// 当前目标并发数（最少 1）；下载设置实时同步到 state.settings.concurrent。
function desiredConcurrency() {
    return Math.max(1, parseInt(state.settings.concurrent, 10) || 1);
}

// 将运行中的 worker 数量补足到当前目标并发：用于启动、运行中追加队列、运行中调高并发数。
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

function finishBatch() {
    closeAllSSE();
    state.isRunning = false;
    saveQueue();
    setDockStatus(bt('status.batch-finished', '批量下载结束'), 'info');
    updateButtonsState();
    // 多人模式：队列完成后自动打包已下载文件（配额超限时已在 handleQuotaExceeded 中触发打包，不重复）
    if (dockState.quota.enabled) {
        const completed = state.queue.filter(q => q.status === 'completed').length;
        if (completed > 0) autoPackAfterQueue();
    }
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

function getNextPending() {
    const downloadingIds = new Set(
        state.queue.filter(q => q.status === 'downloading').map(q => q.id)
    );
    const idx = state.queue.findIndex(q => q.status === 'pending' && !downloadingIds.has(q.id));
    if (idx === -1) return null;
    state.queue[idx].status = 'downloading';
    state.queue[idx].startTime = new Date().toISOString();
    saveQueue();
    renderQueue();
    return state.queue[idx];
}

// 类型当前不可用（其行为模块未装载 / 插件已禁用）：标记暂停，待类型恢复后可重试。
function pauseUnavailableQueueType(item) {
    item.status = 'paused';
    item.endTime = null;
    item.lastMessage = bt('queue.message.type-unavailable', '该类型当前不可用（其插件已禁用），已暂停');
    updateStats();
    saveQueue();
    renderQueue();
}

async function processSingle(item) {
    const typeId = item.kind || 'illust';
    const runtime = window.PixivBatch && window.PixivBatch.queueTypes;
    const descriptor = runtime && runtime.get(typeId);
    if (!descriptor) {
        pauseUnavailableQueueType(item);
        return;
    }
    try {
        await descriptor.process(item);
    } catch (error) {
        if (error && error.code === 'STALE_QUEUE_TYPE') {
            pauseUnavailableQueueType(item);
            return;
        }
        throw error;
    }
}

// 插画 / 漫画 / 动图下载流程（逐字移植 processIllustItem，UI 门面换新坞）。
async function processIllustItem(item) {
    item.lastMessage = bt('queue.message.checking-history', '正在检查历史记录...');
    renderQueue();

    if (state.settings.skipHistory) {
        const downloaded = await checkDownloaded(item.id);
        if (downloaded && downloaded.deleted && state.settings.redownloadDeleted) {
            // 软删除记录 + 允许重下：当作未下载继续走正常下载流程（落库后删除标记自动复位）
        } else if (downloaded && downloaded.deleted) {
            item.status = 'skipped';
            item.lastMessage = bt('queue.message.skipped-deleted', '跳过 — 已经下载过，但被删除');
            item.endTime = new Date().toISOString();
            updateStats();
            saveQueue();
            renderQueue();
            return;
        } else if (downloaded) {
            // 若 verifyFiles=true 时是从磁盘恢复出来的裸记录（title 为空），
            // 拉 Pixiv meta 补齐后再跳过，避免画廊里这些恢复出的作品没有标题/作者/简介。
            let recoveredMeta = false;
            if (state.settings.verifyHistoryFiles && !downloaded.title) {
                item.lastMessage = bt('queue.message.recovering-metadata', '正在补齐已下载作品的元数据...');
                renderQueue();
                try {
                    const meta = await getArtworkMeta(item.id);
                    const recovered = await recoverArtworkMetadata(item.id, {
                        title: meta.illustTitle || '',
                        authorId: normalizeAuthorId(meta.authorId ?? meta.userId),
                        authorName: meta.authorName || meta.userName || '',
                        xRestrict: Number(meta.xRestrict ?? meta.xrestrict ?? 0),
                        isAi: meta?.isAi === true || Number(meta?.aiType ?? 0) >= 2,
                        description: meta.description || ''
                    });
                    if (recovered && recovered.title) {
                        recoveredMeta = true;
                        item.title = recovered.title;
                    }
                } catch (e) {
                    // best-effort：拉 meta 失败不影响跳过
                    console.warn(bt('download.log.skip-recover-meta-failed', '跳过恢复元数据失败: itemId={id}', {id: item.id}), e);
                }
            }
            item.status = 'skipped';
            item.lastMessage = recoveredMeta
                ? bt('queue.message.skipped-history-recovered', '跳过 — 已下载（自动补齐元数据）')
                : bt('queue.message.skipped-history', '跳过 — 历史记录中已存在');
            item.endTime = new Date().toISOString();
            updateStats();
            saveQueue();
            renderQueue();
            return;
        }
    }

    item.lastMessage = bt('queue.message.fetching-info', '正在获取作品信息...');
    renderCurrent(item);
    setDockStatus(bt('status.fetching-metadata', '获取信息：{id}', {id: item.id}), 'info');
    renderQueue();

    try {
        const meta = await getArtworkMeta(item.id);
        item.title = meta.illustTitle || item.title || bt('queue.artwork-fallback', '作品 {id}', {id: item.id});
        const metaAuthorId = normalizeAuthorId(meta.authorId ?? meta.userId);
        if (metaAuthorId) item.authorId = metaAuthorId;
        if (meta.authorName || meta.userName) item.authorName = meta.authorName || meta.userName;

        const xRestrict = Number(meta.xRestrict ?? meta.xrestrict ?? 0);
        const isAi = meta?.isAi === true || Number(meta?.aiType ?? 0) >= 2;
        const filterSkipReason = evaluateDownloadFilterSkip(meta, 'illust');
        if (filterSkipReason) {
            item.status = 'skipped';
            item.lastMessage = filterSkipReason;
            item.endTime = new Date().toISOString();
            updateStats();
            saveQueue();
            renderQueue();
            return;
        }

        item.xRestrict = xRestrict;
        item.isAi = isAi;
        const isUserMode = item.source === 'user';
        let urls, ugoiraData = null;

        item.lastMessage = bt('queue.message.fetching-images', '正在获取图片地址...');
        renderQueue();

        if (meta.illustType === 2) {
            const ugoira = await getUgoiraMeta(item.id);
            ugoiraData = {zipUrl: ugoira.zipUrl, delays: ugoira.delays};
            urls = [ugoira.zipUrl];
            item.totalImages = 1;
        } else {
            urls = await getArtworkPages(item.id);
            if (!urls.length) throw new Error(bt('queue.message.no-image-url', '未获取到图片 URL'));
            item.totalImages = urls.length;
        }

        item.downloadedCount = 0;
        item.bookmarkResult = null;
        item.collectionResult = null;
        item.ugoiraProgress = null;
        item.imageProgress = null;
        saveQueue();
        renderQueue();

        setDockStatus(bt('status.downloading-title', '下载中：{title}', {title: item.title}), 'info');
        const fallbackAuthorId = isUserMode ? normalizeAuthorId(state.userId) : null;
        const fallbackAuthorName = isUserMode ? (item.username || state.username || state.userId || '') : '';
        const seriesInfo = (item.seriesId && item.seriesId > 0)
            ? {seriesId: item.seriesId, seriesOrder: item.seriesOrder ?? 0, seriesTitle: item.seriesTitle || null}
            : (meta.seriesId
                ? {seriesId: meta.seriesId, seriesOrder: meta.seriesOrder ?? 0, seriesTitle: meta.seriesTitle || null}
                : null);
        const dlData = await sendDownload(
            item.id, urls, item.title,
            isUserMode, item.username || state.username || state.userId,
            item.authorId ?? fallbackAuthorId,
            item.authorName || fallbackAuthorName,
            xRestrict, isAi, ugoiraData,
            meta.description || '',
            Array.isArray(meta.tags) ? meta.tags : [],
            seriesInfo,
            meta.illustType ?? null,
            meta.rawMetaJson || null
        );
        if (dlData && dlData.alreadyDownloaded) {
            item.status = 'skipped';
            item.lastMessage = bt('queue.message.skipped-server-downloaded', '跳过 — 已下载（服务器确认）');
            item.endTime = new Date().toISOString();
            updateStats();
            saveQueue();
            renderQueue();
            setDockStatus(bt('status.skipped-downloaded-title', '跳过：{title}（已下载）', {title: item.title}), 'info');
            return;
        }
        openSSE(item.id);
        const ssePromise = waitForFinalStatusBySSE(item.id, STATUS_TIMEOUT_MS);
        item.lastMessage = bt('queue.message.waiting-completion', '下载中，等待完成...');
        renderQueue();

        const final = await ssePromise;

        if (final && final.completed) {
            const dCount = final.downloadedCount !== undefined ? final.downloadedCount : item.totalImages;
            item.downloadedCount = dCount;
            item.bookmarkResult = final.bookmarkResult || null;
            item.collectionResult = final.collectionResult || null;
            item.ugoiraProgress = mergeUgoiraProgress(item.ugoiraProgress, final.ugoiraProgress);
            item.imageProgress = final.imageProgress || item.imageProgress || null;
            if (dCount < item.totalImages) {
                item.status = 'failed';
                item.lastMessage = bt(
                    'queue.message.failed-partial',
                    '失败 — 仅 {downloaded}/{total} 张已下载',
                    {downloaded: dCount, total: item.totalImages}
                );
                setDockStatus(bt('status.failed-files-missing-title', '失败：{title} (文件缺失)', {title: item.title}), 'error');
            } else {
                item.status = 'completed';
                item.lastMessage = bt('queue.message.completed-images', '已完成，共 {count} 张', {count: dCount});
                setDockStatus(bt('status.completed-title', '完成：{title}', {title: item.title}), 'success');
                notifyFirstDownloadCompleted();
                // 刷新配额显示（每完成一个作品计 1）
                if (dockState.quota.enabled) {
                    dockState.quota.artworksUsed = Math.min(dockState.quota.maxArtworks, dockState.quota.artworksUsed + 1);
                    renderQuotaBar();
                }
            }
        } else if (final && final.failed) {
            item.ugoiraProgress = mergeUgoiraProgress(item.ugoiraProgress, final.ugoiraProgress);
            item.imageProgress = final.imageProgress || item.imageProgress || null;
            item.status = 'failed';
            item.lastMessage = bt(
                'queue.message.failed-backend',
                '失败 — {message}',
                {message: final.message || bt('status.backend-failure', '后端返回失败')}
            );
            setDockStatus(bt('status.failed-title', '失败：{title}', {title: item.title}), 'error');
        } else {
            try {
                const check = await getDownloadStatus(item.id);
                if (check && check.completed) {
                    const dCount = check.downloadedCount !== undefined ? check.downloadedCount : 0;
                    item.downloadedCount = dCount;
                    item.bookmarkResult = check.bookmarkResult || null;
                    item.collectionResult = check.collectionResult || null;
                    item.ugoiraProgress = mergeUgoiraProgress(item.ugoiraProgress, check.ugoiraProgress);
                    item.imageProgress = check.imageProgress || check.imageProgress || null;
                    if (dCount < item.totalImages) {
                        item.status = 'failed';
                        item.lastMessage = bt(
                            'queue.message.failed-files-missing',
                            '失败 — 文件缺失 ({downloaded}/{total})',
                            {downloaded: dCount, total: item.totalImages}
                        );
                    } else {
                        item.status = 'completed';
                        item.lastMessage = bt('queue.message.completed-confirmed', '已完成（确认），共 {count} 张', {count: dCount});
                        notifyFirstDownloadCompleted();
                    }
                } else {
                    item.status = 'failed';
                    item.lastMessage = bt('queue.message.failed-timeout', '失败 — 超时未收到完成状态');
                }
            } catch (error) {
                item.status = 'failed';
                item.lastMessage = bt('queue.message.failed-status-error', '失败 — 状态查询异常');
            }
        }
    } catch (e) {
        if (e.message === 'quota_exceeded') {
            // 已在 handleQuotaExceeded 中处理，item 已标记为失败，不需要重复处理
            item.status = 'failed';
            item.lastMessage = bt('queue.message.failed-quota', '失败 - 达到限额');
        } else {
            item.status = 'failed';
            item.lastMessage = bt('queue.message.failed-backend', '失败 — {message}', {message: e.message});
            setDockStatus(bt('status.error-item', '错误：{id} — {message}', {id: item.id, message: e.message}), 'error');
        }
    } finally {
        closeSSE(item.id);
        item.endTime = item.endTime || new Date().toISOString();
        updateStats();
        saveQueue();
        renderQueue();
        renderCurrent(null);
    }
}

function pause() {
    if (!state.isRunning) return;
    state.isPaused = true;
    state.queue.forEach(q => {
        if (q.status === 'pending') q.status = 'paused';
    });
    saveQueue();
    renderQueue();
    const active = state.queue.filter(q => q.status === 'downloading').length;
    setDockStatus(
        active > 0
            ? bt('status.pausing-active', '正在暂停... (等待 {count} 个任务完成)', {count: active})
            : bt('status.paused', '已暂停'),
        'warning'
    );
    updateButtonsState();
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
    renderQueue();
    setDockStatus(bt('status.resume-download', '继续下载'), 'info');
    updateButtonsState();
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
    setDockStatus(bt('status.queue-cleared', '队列已清除'), 'info');
}

/* ============================================================
   按钮处理
   ============================================================ */
async function handleStart() {
    // 布局偏好调查预加载：点击开始下载即异步预热 SDK 与服务端状态，
    // 首个作品完成时弹窗不再等待（失败不影响下载）。
    if (window.PixivLayoutFeedback && typeof window.PixivLayoutFeedback.preload === 'function') {
        try {
            window.PixivLayoutFeedback.preload();
        } catch (_) {
            // 预加载失败不影响下载
        }
    }
    start();
}

function handlePause() {
    if (state.isPaused) resume(); else pause();
}

async function handleRetry() {
    const failed = state.queue.filter(q => q.status === 'failed');
    if (!failed.length) {
        await abAlert('alert.no-failed', '当前没有失败的作品');
        return;
    }
    failed.forEach(q => {
        q.status = 'pending';
        q.lastMessage = '';
        q.startTime = null;
        q.endTime = null;
    });
    saveQueue();
    renderQueue();
    await start();
}

async function handleClear() {
    if (!await abConfirm('dialog.confirm-clear-queue', '确认清除队列？')) return;
    stopAndClear();
}

// 单项取消：POST /api/download/queue/{queueType}/cancel（workKey + descriptor owner 四元组）
async function requestQueueItemCancel(id) {
    const item = state.queue.find(candidate => String(candidate.id) === String(id));
    if (!item || item.status !== 'downloading' || !item.cancelWorkKey) {
        setDockStatus(bt('status.cancel-failed', '取消下载请求失败'), 'error');
        return false;
    }
    const runtime = window.PixivBatch && window.PixivBatch.queueTypes;
    if (!runtime || !runtime.canCancel(item)) {
        setDockStatus(bt('status.cancel-failed', '取消下载请求失败'), 'error');
        return false;
    }
    try {
        await runtime.cancel(item);
        setDockStatus(bt('status.cancel-requested', '已请求取消下载'), 'success');
        return true;
    } catch (e) {
        console.warn('[queue] 队列单项取消请求失败：', item.kind, e);
        setDockStatus(bt('status.cancel-failed', '取消下载请求失败'), 'error');
        renderQueue();
        return false;
    }
}

window.PixivBatchAlt.engine = Object.assign(window.PixivBatchAlt.engine, {
    start, pause, resume, stopAndClear,
    handleStart, handlePause, handleRetry, handleClear,
    ensureWorkers, sendDownload, processSingle, processIllustItem,
    ensureSharedSSE, closeAllSSE, openSSE, closeSSE,
    initQuota, showArchiveCard, triggerAdminPack, requestQueueItemCancel
});
