'use strict';
/* ============================================================
   alt-engine — 下载执行引擎
   插画下载请求 / 配额与归档，
   worker 池与聚合 SSE 由各自职责模块提供。
   ============================================================ */
const STATUS_TIMEOUT_MS = 300000;

let quotaExceededHandled = false;
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
   下载提交（POST /api/download/pixiv，payload 与现行引擎一致）
   ============================================================ */
async function sendDownload(artworkId, imageUrls, title, isUserDownload, username, authorId, authorName, xRestrict, isAi, ugoiraData, description, tags, seriesInfo, illustType, rawMetaJson, invocation) {
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
        ...window.PixivMediaSettings.snapshot(state.settings),
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
    const {res, data} = await submitWithPathAction(`${BASE}/api/download/pixiv`, payload, invocation);
    if (res.status === 429 && data.quotaExceeded) {
        if (!quotaExceededHandled) {
            quotaExceededHandled = true;
            handleQuotaExceeded(data);
        }
        const err = new Error('quota_exceeded');
        err.quotaData = data;
        throw err;
    }
    if (!res.ok) throw new Error(data.error || data.message || bt('status.backend-failure', '后端返回失败'));
    return data;
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
            q.statusMessageKey = 'batch:queue.message.failed-quota';
            q.lastMessage = '';
            q.lastMessageParts = null;
            q.endTime = q.endTime || new Date().toISOString();
        }
    });
    state.stopRequested = true;
    state.isRunning = false;
    updateStats();
    saveQueue();
    renderQueue();
    updateButtonsState();
    setDockStatus({key: 'status.archive-limit', fallback: '已达到下载限额'}, 'error');

    const token = data.archiveToken;
    const expireSeconds = data.archiveExpireSeconds ?? 3600;
    showArchiveCard(token, expireSeconds, false);
}

const archiveMonitor = new PixivArchiveMonitor(archive => {
    dockState.archive = archive;
    renderArchiveCard();
}, () => updateArchiveCountdown());

function showArchiveCard(token, expireSeconds, ready, title) {
    archiveMonitor.watch(token, expireSeconds, ready, title || {key: 'status.archive-limit', fallback: '已达到下载限额'});
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
            setDockStatus({key: 'status.batch-finished-packing', fallback: '批量下载结束，正在打包文件...'}, 'info');
            showArchiveCard(
                data.archiveToken,
                data.archiveExpireSeconds ?? 3600,
                false,
                {key: 'status.download-complete-packing', fallback: '下载完成，正在打包'}
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
        setDockStatus({key: 'status.no-completed-to-pack', fallback: '队列中暂无已完成的作品可供打包'}, 'warning');
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
            setDockStatus({key: 'status.login-expired', fallback: '登录状态已失效，请重新登录'}, 'error');
            return;
        }

        if (res.status === 204) {
            setDockStatus({key: 'status.pack-folder-missing', fallback: '数据库中未找到对应文件夹，可能已被移动或删除'}, 'warning');
            return;
        }

        let data = null;
        try {
            data = await res.json();
        } catch {
        }

        if (!res.ok) {
            setDockStatus(
                (data && data.error)
                    ? data.error
                    : {key: 'status.pack-failed-http', fallback: '打包失败：HTTP {code}', args: {code: res.status}},
                'error');
            return;
        }

        setDockStatus(
            {key: 'status.pack-request-submitted', fallback: '已提交打包请求（{count} 个作品），正在生成压缩包...', args: {count: ids.length}},
            'info');
        showArchiveCard(
            data.archiveToken,
            data.archiveExpireSeconds ?? 3600,
            false,
            {key: 'status.admin-packing', fallback: '管理员打包中（{count} 个作品）', args: {count: ids.length}}
        );
    } catch (e) {
        setDockStatus({key: 'status.pack-request-failed', fallback: '打包请求失败：{message}', args: {message: e.message}}, 'error');
    } finally {
        updateButtonsState();
    }
}
