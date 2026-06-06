'use strict';
    /**
     * 流式抓取 JSON：边读边通过 onProgress(已接收, 总字节) 上报进度，
     * 用于小说正文（meta）下载的流式进度条。返回一个与 Response 兼容的轻量对象
     * （含 ok / status / json()）。非 2xx 或浏览器不支持流时回退为原始 Response。
     */
    async function fetchJsonWithProgress(url, opts, onProgress) {
        const res = await fetch(url, opts);
        if (!res.ok || !res.body || typeof res.body.getReader !== 'function') {
            return res;
        }
        const totalHeader = res.headers.get('Content-Length');
        const total = totalHeader ? Number(totalHeader) : 0;
        const reader = res.body.getReader();
        const chunks = [];
        let received = 0;
        for (; ;) {
            const {done, value} = await reader.read();
            if (done) break;
            chunks.push(value);
            received += value.length;
            if (onProgress) onProgress(received, total);
        }
        let len = 0;
        chunks.forEach(c => len += c.length);
        const buf = new Uint8Array(len);
        let off = 0;
        chunks.forEach(c => {
            buf.set(c, off);
            off += c.length;
        });
        const text = new TextDecoder('utf-8').decode(buf);
        return {
            ok: res.ok,
            status: res.status,
            json: () => Promise.resolve(JSON.parse(text))
        };
    }

    const WINDOWS_RESERVED_FILE_NAMES = new Set([
        'CON', 'PRN', 'AUX', 'NUL',
        'COM1', 'COM2', 'COM3', 'COM4', 'COM5', 'COM6', 'COM7', 'COM8', 'COM9',
        'LPT1', 'LPT2', 'LPT3', 'LPT4', 'LPT5', 'LPT6', 'LPT7', 'LPT8', 'LPT9'
    ]);

    const STATUS_TIMEOUT_MS = 300000;

    /* ============================================================
       配额 & 压缩包
    ============================================================ */
    let quotaInfo = {enabled: false, adminMode: false, artworksUsed: 0, maxArtworks: 50, resetSeconds: 0};
    let archiveCountdownTimer = null;
    let archivePollTimer = null;
    let quotaResetTimer = null;
    let quotaExceededHandled = false;

    async function initQuota() {
        try {
            const res = await fetch(BASE + '/api/quota/init', {method: 'POST', credentials: 'same-origin'});
            if (!res.ok) return;
            const data = await res.json();
            quotaInfo.adminMode = !!data.adminMode;
            if (quotaInfo.adminMode) {
                quotaInfo.enabled = false;
                hideQuotaBar();
                return;
            }
            if (!data.enabled) {
                quotaInfo.enabled = false;
                hideQuotaBar();
                return;
            }
            quotaInfo = {
                enabled: true, adminMode: false, artworksUsed: data.artworksUsed, maxArtworks: data.maxArtworks,
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

    function hideQuotaBar() {
        document.getElementById('quota-bar').style.display = 'none';
        document.getElementById('qb-reset').textContent = '';
        clearInterval(quotaResetTimer);
    }

    function renderQuotaBar() {
        if (!quotaInfo.enabled) {
            hideQuotaBar();
            return;
        }
        document.getElementById('quota-bar').style.display = 'block';
        document.getElementById('qb-used').textContent = quotaInfo.artworksUsed;
        document.getElementById('qb-max').textContent = quotaInfo.maxArtworks;
        const pct = Math.min(100, Math.round(quotaInfo.artworksUsed / quotaInfo.maxArtworks * 100));
        const fill = document.getElementById('qb-fill');
        fill.style.width = pct + '%';
        fill.className = 'qb-fill' + (pct >= 90 ? ' danger' : pct >= 70 ? ' warn' : '');
        document.getElementById('qb-reset').textContent =
            quotaInfo.resetSeconds > 0
                ? bt('status.quota-reset', '配额重置剩余：{time}', {time: formatSeconds(quotaInfo.resetSeconds)})
                : '';
    }

    function startQuotaResetCountdown() {
        clearInterval(quotaResetTimer);
        if (quotaInfo.resetSeconds <= 0) return;
        quotaResetTimer = setInterval(() => {
            if (quotaInfo.resetSeconds > 0) quotaInfo.resetSeconds--;
            renderQuotaBar();
            if (quotaInfo.resetSeconds <= 0) clearInterval(quotaResetTimer);
        }, 1000);
    }

    function handleQuotaExceeded(data) {
        quotaInfo.artworksUsed = data.artworksUsed || quotaInfo.artworksUsed;
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
        setStatus(bt('status.archive-limit', '已达到下载限额'), 'error');

        const token = data.archiveToken;
        const expireSeconds = data.archiveExpireSeconds || 3600;
        showArchiveCard(token, expireSeconds, false);
    }

    function showArchiveCard(token, expireSeconds, ready, title = bt('status.archive-limit', '已达到下载限额')) {
        clearInterval(archiveCountdownTimer);
        clearInterval(archivePollTimer);

        document.getElementById('archive-card').style.display = 'block';
        document.getElementById('ac-title').textContent = title;
        document.getElementById('ac-expired-area').style.display = 'none';
        document.getElementById('ac-dl-area').style.display = 'none';

        if (ready) {
            activateArchiveDownload(token, expireSeconds);
        } else {
            document.getElementById('ac-status').textContent = bt('status.archive-packing', '正在打包已下载文件，请稍候...');
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
                    document.getElementById('ac-status').textContent = bt(
                        'status.archive-empty',
                        '暂无可打包文件（当前下载仍在进行中，完成后自动包含）'
                    );
                }
            } catch {
            }
        }, 2000);
    }

    function activateArchiveDownload(token, expireSeconds) {
        document.getElementById('ac-status').textContent = bt(
            'status.archive-ready',
            '压缩包已就绪，请在有效期内下载：'
        );
        const dlArea = document.getElementById('ac-dl-area');
        dlArea.style.display = 'block';
        const btn = document.getElementById('ac-dl-btn');
        btn.href = BASE + '/api/archive/download/' + token;
        btn.removeAttribute('disabled');

        let remaining = Math.max(0, parseInt(expireSeconds));
        document.getElementById('ac-countdown').textContent = bt(
            'status.archive-validity',
            '下载链接有效期：{time}',
            {time: formatSeconds(remaining)}
        );
        archiveCountdownTimer = setInterval(() => {
            remaining--;
            if (remaining <= 0) {
                clearInterval(archiveCountdownTimer);
                showArchiveExpired();
            } else {
                document.getElementById('ac-countdown').textContent = bt(
                    'status.archive-validity',
                    '下载链接有效期：{time}',
                    {time: formatSeconds(remaining)}
                );
            }
        }, 1000);
    }

    function showArchiveExpired() {
        document.getElementById('ac-dl-area').style.display = 'none';
        document.getElementById('ac-expired-area').style.display = 'block';
        document.getElementById('ac-status').textContent = '';
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
                setStatus(bt('status.batch-finished-packing', '批量下载结束，正在打包文件...'), 'info');
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
            .filter(q => q.status === 'completed')
            .map(q => Number(q.id))
            .filter(Number.isFinite);

        if (ids.length === 0) {
            setStatus(bt('status.no-completed-to-pack', '队列中暂无已完成的作品可供打包'), 'warning');
            return;
        }

        const btn = document.getElementById('admin-pack-btn');
        btn.disabled = true;

        try {
            const res = await fetch(BASE + '/api/archive/pack-artworks', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                credentials: 'same-origin',
                body: JSON.stringify({artworkIds: ids})
            });

            if (res.status === 401) {
                isAdmin = false;
                updateAuthButtons();
                updateAdminPackButton();
                setStatus(bt('status.login-expired', '登录状态已失效，请重新登录'), 'error');
                return;
            }

            if (res.status === 204) {
                setStatus(bt('status.pack-folder-missing', '数据库中未找到对应文件夹，可能已被移动或删除'), 'warning');
                return;
            }

            let data = null;
            try {
                data = await res.json();
            } catch {
            }

            if (!res.ok) {
                setStatus(
                    (data && data.message)
                        ? data.message
                        : bt('status.pack-failed-http', '打包失败：HTTP {code}', {code: res.status}),
                    'error'
                );
                return;
            }

            setStatus(
                bt('status.pack-request-submitted', '已提交打包请求（{count} 个作品），正在生成压缩包...', {count: ids.length}),
                'info'
            );
            showArchiveCard(
                data.archiveToken,
                data.archiveExpireSeconds || 3600,
                false,
                bt('status.admin-packing', '管理员打包中（{count} 个作品）', {count: ids.length})
            );
        } catch (e) {
            setStatus(bt('status.pack-request-failed', '打包请求失败：{message}', {message: e.message}), 'error');
        } finally {
            updateAdminPackButton();
        }
    }

    async function checkNovelDownloaded(novelId) {
        try {
            const res = await fetch(`${BASE}/api/gallery/novel/${encodeURIComponent(novelId)}`,
                {credentials: 'same-origin'});
            return res.ok;
        } catch {
            return false;
        }
    }

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

    // 两阶段恢复：当 verifyFiles=true 的 fallback 路径把磁盘上已有的作品恢复成一条空 title 的裸记录时，
    // 用前端拉到的 Pixiv 元数据补齐缺失字段。后端是幂等的：DB 已有完整记录直接返回原记录。
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
            // best-effort：失败不影响跳过逻辑，至少裸记录仍在
            console.warn(bt('download.log.recover-metadata-failed', '恢复作品元数据失败: artworkId={id}', {id: artworkId}), e);
        }
        return null;
    }

    function normalizeAuthorId(value) {
        if (value === null || value === undefined || value === '') return null;
        const parsed = Number.parseInt(String(value), 10);
        return Number.isFinite(parsed) && parsed > 0 ? parsed : null;
    }

    function normalizeFileNameTemplate(value) {
        const raw = value === null || value === undefined ? '' : String(value);
        return raw.trim() ? raw : DEFAULT_FILE_NAME_TEMPLATE;
    }

    function sanitizeFileNamePart(value) {
        let cleaned = value === null || value === undefined ? '' : String(value);
        cleaned = cleaned.replace(/[\\/:*?"<>|\x00-\x1F\x7F-\x9F]/g, '_').trim().replace(/[. ]+$/g, '');
        if (WINDOWS_RESERVED_FILE_NAMES.has(cleaned.toUpperCase())) cleaned = '_' + cleaned;
        return cleaned;
    }

    function normalizeBaseName(value, fallback) {
        let cleaned = sanitizeFileNamePart(value);
        if (!cleaned) cleaned = sanitizeFileNamePart(fallback);
        if (!cleaned) cleaned = 'untitled';
        return cleaned.length > 180 ? cleaned.slice(0, 180) : cleaned;
    }

    function appendFileNameSuffix(base, suffix) {
        const maxBase = Math.max(1, 180 - suffix.length);
        const trimmed = base.length > maxBase ? base.slice(0, maxBase) : base;
        return trimmed + suffix;
    }

    function ensureUniqueBaseNames(names) {
        const used = new Set();
        const baseCounts = new Map();
        return names.map((base, page) => {
            const baseKey = base.toLowerCase();
            const duplicate = baseCounts.get(baseKey) || 0;
            let candidate = base;
            if (duplicate > 0 || used.has(baseKey)) {
                let suffixIndex = 1;
                do {
                    const suffix = `_p${page}${suffixIndex > 1 ? '_' + suffixIndex : ''}`;
                    candidate = appendFileNameSuffix(base, suffix);
                    suffixIndex++;
                } while (used.has(candidate.toLowerCase()));
            }
            baseCounts.set(baseKey, duplicate + 1);
            used.add(candidate.toLowerCase());
            return candidate;
        });
    }

    function formatFileNameBase(template, vars, page, count) {
        const normalizedTemplate = normalizeFileNameTemplate(template);
        const xRestrict = Number(vars.xRestrict) || 0;
        const isAi = !!vars.isAi;
        const replacements = {
            artwork_id: String(vars.artworkId || ''),
            artwork_title: sanitizeFileNamePart(vars.title || ''),
            author_id: vars.authorId ? String(vars.authorId) : '',
            author_name: sanitizeFileNamePart(vars.authorName || ''),
            timestamp: String(vars.timestamp || ''),
            page: String(page),
            count: String(count),
            ai: isAi ? 'AI' : '',
            'ai+': isAi ? 'AI' : 'Human',
            R18: xRestrict === 2 ? 'R18G' : (xRestrict === 1 ? 'R18' : ''),
            'R18+': xRestrict === 2 ? 'R18G' : (xRestrict === 1 ? 'R18' : 'SFW')
        };
        const rendered = normalizedTemplate.replace(
            /\{(artwork_id|artwork_title|author_id|author_name|timestamp|page|count|ai\+?|R18\+?)\}/g,
            (_, key) => replacements[key] ?? ''
        );
        return normalizeBaseName(rendered, `${vars.artworkId}_p${page}`);
    }

    function buildDownloadFileNames(template, vars, count) {
        const safeCount = Math.max(1, Number(count) || 1);
        const names = [];
        for (let page = 0; page < safeCount; page++) {
            names.push(formatFileNameBase(template, vars, page, safeCount));
        }
        return ensureUniqueBaseNames(names);
    }

    /**
     * 单批次内的系列元数据缓存：同一 seriesId 在一批下载中只查一次 Pixiv 系列 AJAX，
     * 节省 N 个章节下载时的 N-1 次重复请求。kind: 'illust' | 'novel'。
     * 返回 { caption, coverUrl, tags } —— 调用方只取需要的字段。
     */
    const seriesMetaPromiseCache = new Map();
    function fetchSeriesEnrichmentCached(seriesId, kind) {
        const sid = Number(seriesId);
        if (!Number.isFinite(sid) || sid <= 0) return Promise.resolve(null);
        const key = (kind === 'novel' ? 'novel:' : 'illust:') + sid;
        if (seriesMetaPromiseCache.has(key)) return seriesMetaPromiseCache.get(key);
        const path = kind === 'novel'
            ? `/api/pixiv/novel/series/${sid}?page=1`
            : `/api/pixiv/series/${sid}?page=1`;
        const promise = fetch(BASE + path, {
            credentials: 'same-origin',
            headers: pixivHeader()
        }).then(r => r.ok ? r.json() : null).then(data => {
            const meta = data && data.series ? data.series : null;
            if (!meta) return null;
            return {
                caption: meta.caption || '',
                coverUrl: meta.coverUrl || '',
                tags: Array.isArray(meta.tags) ? meta.tags : []
            };
        }).catch(() => null);
        seriesMetaPromiseCache.set(key, promise);
        return promise;
    }

    async function sendDownload(artworkId, imageUrls, title, isUserDownload, username, authorId, authorName, xRestrict, isAi, ugoiraData, description, tags, seriesInfo, illustType) {
        const delayMs = getImageDelayMs();
        const collectionId = await resolveBatchCollectionIdForDownload();
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
            // 系列简介/封面只在本地数据库尚无时由后端落盘，前端这里仅负责把 Pixiv 的 hint 透传过去。
            // 缓存一批查一次，失败/空值不阻塞下载。
            const enrich = seriesInfo.seriesDescription || seriesInfo.seriesCoverUrl
                ? {caption: seriesInfo.seriesDescription, coverUrl: seriesInfo.seriesCoverUrl}
                : await fetchSeriesEnrichmentCached(seriesInfo.seriesId, 'illust');
            if (enrich) {
                if (enrich.caption) other.seriesDescription = enrich.caption;
                if (enrich.coverUrl) other.seriesCoverUrl = enrich.coverUrl;
            }
        }
        if (illustType != null && Number.isFinite(Number(illustType))) {
            other.illustType = Number(illustType);
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
       下载管理器
    ============================================================ */
    async function start() {
        if (state.queue.length === 0) {
            setStatus(bt('status.queue-empty', '队列为空'), 'error');
            return;
        }
        if (!await checkBackend()) {
            uiAlertKey('alert.backend-unavailable', '后端服务不可用，请确认后端已启动');
            return;
        }

        await refreshBatchCollections();

        state.queue.forEach(q => {
            if (['idle', 'failed', 'paused'].includes(q.status)) {
                q.status = 'pending';
                q.lastMessageParts = null;
            }
        });
        state.isRunning = true;
        state.isPaused = false;
        state.stopRequested = false;
        state.activeWorkers = 0;
        quotaExceededHandled = false;

        updateStats();
        updateButtonsState();
        saveQueue();
        renderQueue();
        setStatus(
            bt('status.start-download', '开始下载 (并发:{concurrent}, 间隔:{intervalMs}ms)',
                {concurrent: desiredConcurrency(), intervalMs: getIntervalMs()}),
            'info'
        );

        // 并发数与作品间隔实时生效：worker 池按当前目标并发动态伸缩，间隔在每次作品之间实时读取。
        ensureWorkers();
    }

    // 当前目标并发数（最少 1）；下载设置实时同步到 state.settings.concurrent。
    function desiredConcurrency() {
        return Math.max(1, parseInt(state.settings.concurrent, 10) || 1);
    }

    // 将运行中的 worker 数量补足到当前目标并发：用于启动、运行中追加队列、运行中调高并发数。
    // 调低并发数由各 worker 在 workerLoop 顶部自行退出，不在此处理。
    function ensureWorkers() {
        if (!state.isRunning || state.stopRequested) return;
        ensureSharedSSE();
        while (state.activeWorkers < desiredConcurrency()) {
            workerLoop().finally(handleWorkerExit);
        }
    }

    // 最后一个 worker 退出（队列已抽干）时统一收尾；调低并发数导致的多余 worker 退出不触发收尾。
    function handleWorkerExit() {
        if (!state.isRunning || state.activeWorkers > 0) return;
        closeAllSSE();
        state.isRunning = false;
        saveQueue();
        setStatus(bt('status.batch-finished', '批量下载结束'), 'info');
        updateButtonsState();
        // 多人模式：队列完成后自动打包已下载文件（配额超限时已在 handleQuotaExceeded 中触发打包，不重复）
        if (quotaInfo.enabled) {
            const completed = state.queue.filter(q => q.status === 'completed').length;
            if (completed > 0) autoPackAfterQueue();
        }
    }

    async function workerLoop() {
        state.activeWorkers++;
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

    async function processSingle(item) {
        item.lastMessageParts = null;
        if (item.kind === 'novel') {
            await processNovelItem(item);
            return;
        }
        item.lastMessage = bt('queue.message.checking-history', '正在检查历史记录...');
        renderQueue();

        if (state.settings.skipHistory) {
            const downloaded = await checkDownloaded(item.id);
            if (downloaded) {
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
        setCurrent(item);
        setStatus(bt('status.fetching-metadata', '获取信息：{id}', {id: item.id}), 'info');
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

            setStatus(bt('status.downloading-title', '下载中：{title}', {title: item.title}), 'info');
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
                meta.illustType ?? null
            );
            if (dlData && dlData.alreadyDownloaded) {
                item.status = 'skipped';
                item.lastMessage = bt('queue.message.skipped-server-downloaded', '跳过 — 已下载（服务器确认）');
                item.endTime = new Date().toISOString();
                updateStats();
                saveQueue();
                renderQueue();
                setStatus(bt('status.skipped-downloaded-title', '跳过：{title}（已下载）', {title: item.title}), 'info');
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
                    const baseMessage = bt(
                        'queue.message.failed-partial',
                        '失败 — 仅 {downloaded}/{total} 张已下载',
                        {downloaded: dCount, total: item.totalImages}
                    );
                    item.lastMessage = appendPostDownloadOutcome(
                        baseMessage,
                        final
                    );
                    item.lastMessageParts = buildPostDownloadMessageParts(baseMessage, 'error', final);
                    setStatus(bt('status.failed-files-missing-title', '失败：{title} (文件缺失)', {title: item.title}), 'error');
                } else {
                    item.status = 'completed';
                    const baseMessage = bt('queue.message.completed-images', '已完成，共 {count} 张', {count: dCount});
                    item.lastMessage = appendPostDownloadOutcome(
                        baseMessage,
                        final
                    );
                    item.lastMessageParts = buildPostDownloadMessageParts(baseMessage, 'success', final);
                    setStatus(bt('status.completed-title', '完成：{title}', {title: item.title}), 'success');
                    // 刷新配额显示（每完成一个作品计 1）
                    if (quotaInfo.enabled) {
                        quotaInfo.artworksUsed = Math.min(quotaInfo.maxArtworks, quotaInfo.artworksUsed + 1);
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
                setStatus(bt('status.failed-title', '失败：{title}', {title: item.title}), 'error');
            } else {
                try {
                    const check = await getDownloadStatus(item.id);
                    if (check && check.completed) {
                        const dCount = check.downloadedCount !== undefined ? check.downloadedCount : 0;
                        item.downloadedCount = dCount;
                        item.bookmarkResult = check.bookmarkResult || null;
                        item.collectionResult = check.collectionResult || null;
                        item.ugoiraProgress = mergeUgoiraProgress(item.ugoiraProgress, check.ugoiraProgress);
                        item.imageProgress = check.imageProgress || item.imageProgress || null;
                        if (dCount < item.totalImages) {
                            item.status = 'failed';
                            const baseMessage = bt(
                                'queue.message.failed-files-missing',
                                '失败 — 文件缺失 ({downloaded}/{total})',
                                {downloaded: dCount, total: item.totalImages}
                            );
                            item.lastMessage = appendPostDownloadOutcome(
                                baseMessage,
                                check
                            );
                            item.lastMessageParts = buildPostDownloadMessageParts(baseMessage, 'error', check);
                        } else {
                            item.status = 'completed';
                            const baseMessage = bt(
                                'queue.message.completed-confirmed',
                                '已完成（确认），共 {count} 张',
                                {count: dCount}
                            );
                            item.lastMessage = appendPostDownloadOutcome(
                                baseMessage,
                                check
                            );
                            item.lastMessageParts = buildPostDownloadMessageParts(baseMessage, 'success', check);
                        }
                    } else {
                        item.status = 'failed';
                        item.lastMessage = bt('queue.message.failed-timeout', '失败 — 超时未收到完成状态');
                    }
                } catch {
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
                setStatus(bt('status.error-item', '错误：{id} — {message}', {id: item.id, message: e.message}), 'error');
            }
        } finally {
            closeSSE(item.id);
            item.endTime = item.endTime || new Date().toISOString();
            updateStats();
            saveQueue();
            renderQueue();
            setCurrent(null);
        }
    }

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
        syncSearchResultsQueueState();
        setStatus(bt('status.queue-cleared', '队列已清除'), 'info');
    }

    /* ============================================================
       小说下载通道：作为 kind=='novel' 的队列项参与统一调度
       —— ID 命名空间用 'n' 前缀避免与插画 ID 冲突
    ============================================================ */
    async function processNovelItem(item) {
        item.lastMessage = bt('queue.message.fetching-info', '正在获取作品信息...');
        setCurrent(item);
        renderQueue();
        const cookie = getCookie();
        const headers = {};
        if (cookie) headers['X-Pixiv-Cookie'] = cookie;
        try {
            const novelId = item.novelId || String(item.id).replace(/^n/, '');

            if (state.settings.skipHistory) {
                const downloaded = await checkNovelDownloaded(novelId);
                if (downloaded) {
                    item.status = 'skipped';
                    item.lastMessage = bt('queue.message.skipped-history', '跳过 — 历史记录中已存在');
                    item.endTime = new Date().toISOString();
                    updateStats();
                    saveQueue();
                    renderQueue();
                    return;
                }
            }

            let _lastTextRender = 0;
            const metaRes = await fetchJsonWithProgress(
                `${BASE}/api/pixiv/novel/${encodeURIComponent(novelId)}/meta`,
                {headers},
                (done, total) => {
                    item.novelText = {done, total};
                    const now = Date.now();
                    if (now - _lastTextRender > 120) {
                        _lastTextRender = now;
                        renderQueue();
                    }
                });
            if (!metaRes.ok) {
                const errData = await metaRes.json().catch(() => ({}));
                throw new Error(errData.error || ('meta HTTP ' + metaRes.status));
            }
            const meta = await metaRes.json();
            item.novelText = null;
            renderQueue();

            const filterSkipReason = evaluateDownloadFilterSkip(meta, 'novel');
            if (filterSkipReason) {
                item.status = 'skipped';
                item.lastMessage = filterSkipReason;
                item.endTime = new Date().toISOString();
                updateStats();
                saveQueue();
                renderQueue();
                return;
            }

            item.title = meta.title || item.title;
            if (meta.authorId) item.authorId = meta.authorId;
            if (meta.authorName) item.authorName = meta.authorName;
            item.xRestrict = Number(meta.xRestrict || 0);
            item.isAi = !!meta.isAi;
            item.totalImages = 1;
            item.downloadedCount = 0;
            saveQueue();
            renderQueue();

            const fmt = (state.settings.novelFormat || 'txt').toLowerCase();
            const autoTranslate = !!(isAdmin && state.settings.novelAutoTranslate);
            const seriesInfo = (item.seriesId && item.seriesId > 0) ? {
                seriesId: Number(item.seriesId),
                seriesOrder: item.seriesOrder,
                seriesTitle: item.seriesTitle
            } : (meta.seriesId ? {
                seriesId: meta.seriesId,
                seriesOrder: meta.seriesOrder,
                seriesTitle: meta.seriesTitle
            } : null);
            // 系列简介/封面/tags：一批共享一次查询，best-effort；失败则不附加。
            const seriesEnrichment = seriesInfo
                ? await fetchSeriesEnrichmentCached(seriesInfo.seriesId, 'novel')
                : null;
            const collectionId = await resolveBatchCollectionIdForDownload();
            const body = {
                novelId: Number(novelId),
                title: meta.title,
                cookie: cookie || null,
                content: meta.content,
                other: {
                    authorId: meta.authorId,
                    authorName: meta.authorName,
                    xRestrict: meta.xRestrict,
                    ai: meta.isAi,
                    original: meta.isOriginal,
                    language: meta.language,
                    wordCount: meta.wordCount,
                    textLength: meta.textLength,
                    readingTimeSeconds: meta.readingTimeSeconds ?? item.readingTimeSeconds ?? null,
                    pageCount: meta.pageCount,
                    description: meta.description,
                    tags: Array.isArray(meta.tags) && meta.tags.length ? meta.tags : (item.tags || []),
                    seriesId: seriesInfo ? seriesInfo.seriesId : null,
                    seriesOrder: seriesInfo ? seriesInfo.seriesOrder : null,
                    seriesTitle: seriesInfo ? seriesInfo.seriesTitle : null,
                    seriesDescription: seriesEnrichment && seriesEnrichment.caption ? seriesEnrichment.caption : null,
                    seriesCoverUrl: seriesEnrichment && seriesEnrichment.coverUrl ? seriesEnrichment.coverUrl : null,
                    seriesTags: seriesEnrichment && seriesEnrichment.tags && seriesEnrichment.tags.length
                            ? seriesEnrichment.tags : null,
                    fileNameTemplate: state.settings.fileNameTemplate,
                    bookmark: !!state.settings.bookmark,
                    collectionId,
                    format: fmt,
                    uploadTimestamp: meta.uploadTimestamp || item.uploadTimestamp || null,
                    coverUrl: meta.coverUrl || item.coverUrl || '',
                    embeddedImages: meta.textEmbeddedImages || {},
                    // 下载即自动翻译（仅管理员；后端再次校验）。译文合订沿用「生成合订本」设置。
                    autoTranslate,
                    autoTranslateLanguage: state.settings.novelTranslateLang || defaultNovelTranslateLang(),
                    autoTranslateSegmentSize: Math.max(0, parseInt(state.settings.novelTranslateSeg, 10) || 0),
                    autoTranslateMerge: !!state.settings.mergeNovelSeries,
                    autoTranslateMergeFormat: (state.settings.mergeNovelFormat || 'epub').toLowerCase()
                }
            };
            item.lastMessage = bt('queue.message.waiting-completion', '下载中，等待完成...');
            renderQueue();
            const dlRes = await fetch(`${BASE}/api/download/pixiv/novel`, {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                credentials: 'same-origin',
                body: JSON.stringify(body)
            });
            const dlData = await dlRes.json().catch(() => ({}));
            if (dlRes.status === 429 && dlData.quotaExceeded) {
                if (!quotaExceededHandled) {
                    quotaExceededHandled = true;
                    handleQuotaExceeded(dlData);
                }
                throw new Error('quota_exceeded');
            }
            if (dlRes.status === 200 && dlData.alreadyDownloaded) {
                item.status = 'skipped';
                item.lastMessage = bt('queue.message.skipped-server-downloaded', '跳过 — 已下载（服务器确认）');
                item.endTime = new Date().toISOString();
                updateStats();
                saveQueue();
                renderQueue();
                return;
            }
            if (!dlRes.ok) throw new Error(dlData.message || ('HTTP ' + dlRes.status));

            // 轮询小说下载状态
            const start = Date.now();
            while (Date.now() - start < STATUS_TIMEOUT_MS) {
                await sleep(800);
                const sRes = await fetch(`${BASE}/api/download/novel/status/${encodeURIComponent(novelId)}`);
                if (!sRes.ok) continue;
                const status = await sRes.json();
                if (status.completed) {
                    if (status.failed) {
                        item.status = 'failed';
                        item.lastMessage = bt('queue.message.failed', '失败 — {message}',
                            {message: status.message || ''});
                    } else {
                        item.status = 'completed';
                        item.downloadedCount = 1;
                        item.bookmarkResult = status.bookmarkResult || null;
                        item.collectionResult = status.collectionResult || null;
                        item.lastMessage = bt('queue.message.completed', '完成');
                    }
                    item.endTime = new Date().toISOString();
                    updateStats();
                    saveQueue();
                    renderQueue();
                    // 「生成合订本」实时生效：系列下载完成时按当前设置决定是否合订（而非入队时的设置）
                    if (item.status === 'completed' && item.mergeAfterSeriesId && state.settings.mergeNovelSeries) {
                        await maybeTriggerSeriesMerge(item.mergeAfterSeriesId);
                    }
                    // 下载即自动翻译：翻译在服务端独立队列异步进行，这里脱离下载 worker 单独轮询其状态，
                    // 不占下载并发名额；下载本身已成功，翻译失败只在该项附提示、不改成功状态。
                    if (item.status === 'completed' && autoTranslate) {
                        pollNovelTranslateStatus(item, novelId);
                    }
                    return;
                }
                if (status.stage) {
                    applyNovelStage(item, status);
                    renderQueue();
                }
            }
            item.status = 'failed';
            item.lastMessage = bt('queue.message.timeout', '超时');
            item.endTime = new Date().toISOString();
            updateStats();
            saveQueue();
            renderQueue();
        } catch (e) {
            item.status = 'failed';
            item.lastMessage = bt('queue.message.failed', '失败 — {message}', {message: e.message || String(e)});
            item.endTime = new Date().toISOString();
            updateStats();
            saveQueue();
            renderQueue();
            throw e;
        }
    }

    // 当系列内最后一个待合并章节完成后，触发合订
    const _novelMergeFiredSeries = new Set();
    async function readMergeResponse(res) {
        try {
            return await res.json();
        } catch {
            return null;
        }
    }

    async function maybeTriggerSeriesMerge(seriesId) {
        if (!seriesId) return;
        const remaining = state.queue.filter(q => q.kind === 'novel'
            && q.mergeAfterSeriesId === seriesId
            && !['completed', 'failed', 'skipped'].includes(q.status));
        if (remaining.length > 0) return;
        if (_novelMergeFiredSeries.has(seriesId)) return;
        _novelMergeFiredSeries.add(seriesId);
        try {
            // 合订本格式独立于单章下载格式（novelFormat），默认推荐 EPUB
            const mfmt = (state.settings.mergeNovelFormat || 'epub').toLowerCase();
            const res = await fetch(`${BASE}/api/gallery/novel/series/${encodeURIComponent(seriesId)}/merge?format=${encodeURIComponent(mfmt)}`, {
                method: 'POST', credentials: 'same-origin'
            });
            const data = await readMergeResponse(res);
            if (res.status === 401) {
                _novelMergeFiredSeries.delete(seriesId);
                isAdmin = false;
                updateAuthButtons();
                updateAdminPackButton();
                setStatus(bt('status.login-expired', '登录状态已失效，请重新登录'), 'error');
                return;
            }
            if (!res.ok || !data || data.success !== true) {
                const message = data && data.message ? data.message : `HTTP ${res.status}`;
                throw new Error(message);
            }
            setStatus(bt('status.novel-series-merged', '小说系列合订本已生成（系列 {id}）', {id: seriesId}), 'success');
        } catch (e) {
            console.warn(bt('download.log.novel-merge-failed', '小说合订本生成失败: seriesId={id}', {id: seriesId}), e);
            _novelMergeFiredSeries.delete(seriesId);
            setStatus(bt('status.novel-series-merge-failed',
                '小说系列合订本生成失败（系列 {id}）：{message}',
                {id: seriesId, message: e.message || String(e)}), 'error');
        }
    }

    /* ============================================================
       下载即自动翻译：脱离下载 worker 的状态轮询
       —— 翻译生命周期在服务端，前端只可视化；写入队列项的 raw 字段，渲染时再本地化文案。
    ============================================================ */
    async function pollNovelTranslateStatus(item, novelId) {
        item.translatePhase = 'QUEUED';
        item.translateElapsed = 0;
        item.translateSeriesPending = 0;
        item.translateDone = false;
        item.translateFailed = false;
        renderQueue();
        const startedAt = Date.now();
        const MAX_POLL_MS = 30 * 60 * 1000;   // 安全上限：30 分钟后停止轮询
        let consecutiveEmpty = 0;
        while (Date.now() - startedAt < MAX_POLL_MS) {
            await sleep(1500);
            let res;
            try {
                res = await fetch(`${BASE}/api/download/novel/translate-status/${encodeURIComponent(novelId)}`,
                    {credentials: 'same-origin'});
            } catch {
                continue;
            }
            if (res.status === 403) {
                // 非管理员：清除翻译态，停止轮询
                clearTranslateState(item);
                return;
            }
            if (res.status === 204) {
                // 尚未登记（下载完成与翻译提交之间的瞬时窗口）：容忍若干次后放弃
                if (++consecutiveEmpty >= 10) { clearTranslateState(item); return; }
                continue;
            }
            if (!res.ok) continue;
            consecutiveEmpty = 0;
            const st = await res.json().catch(() => null);
            if (!st || !st.phase) continue;
            item.translatePhase = st.phase;
            item.translateElapsed = st.elapsedSeconds || 0;
            item.translateSeriesPending = st.seriesPending || 0;
            if (st.done || st.failed || st.phase === 'DONE' || st.phase === 'FAILED') {
                item.translateDone = !!st.done || st.phase === 'DONE';
                item.translateFailed = !!st.failed || st.phase === 'FAILED';
                saveQueue();
                renderQueue();
                return;
            }
            renderQueue();
        }
    }

    function clearTranslateState(item) {
        item.translatePhase = null;
        item.translateElapsed = 0;
        item.translateSeriesPending = 0;
        renderQueue();
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

    function handleRetry() {
        const failed = state.queue.filter(q => q.status === 'failed');
        if (!failed.length) {
            uiAlertKey('alert.no-failed', '当前没有失败的作品');
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
        syncSettings();
        start();
    }

    function handleClear() {
        if (!uiConfirmKey('dialog.confirm-clear-queue', '确认清除队列？')) return;
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
window.PixivBatch.download = Object.assign(window.PixivBatch.download, { start, pause, resume, stopAndClear, handleStart, handlePause, handleRetry, handleClear, sendDownload, triggerAdminPack, processSingle, processNovelItem });
