'use strict';
/* ============================================================
   alt-engine — 下载执行引擎
   插画下载请求 / 配额与归档，
   逐字移植 pixiv-batch/batch-download.js + batch-sse.js（UI 门面换成
   新坞的 renderQueue/renderCurrent/updateStats/setDockStatus）。
   worker 池与聚合 SSE 由同目录职责模块提供。
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
    if (!res.ok) throw new Error(data.error || data.message || bt('status.backend-failure', '后端返回失败'));
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
                (data && data.error)
                    ? data.error
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
