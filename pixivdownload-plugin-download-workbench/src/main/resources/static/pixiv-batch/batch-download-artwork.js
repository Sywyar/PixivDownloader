'use strict';
    function normalizeAuthorId(value) {
        if (value === null || value === undefined || value === '') return null;
        const parsed = Number.parseInt(String(value), 10);
        return Number.isFinite(parsed) && parsed > 0 ? parsed : null;
    }


    /**
     * 单批次内的系列元数据缓存：同一 seriesId 在一批下载中只查一次 Pixiv 系列 AJAX，
     * 节省 N 个章节下载时的 N-1 次重复请求。kind: 'illust' | 'novel'。
     * 返回 { caption, coverUrl, tags } —— 调用方只取需要的字段。
     */
    const seriesMetaPromiseCache = new Map();
    const scopedSeriesMetaPromiseCaches = new WeakMap();
    function seriesMetaCache(invocation) {
        if (!invocation || !invocation.signal) return seriesMetaPromiseCache;
        let cache = scopedSeriesMetaPromiseCaches.get(invocation.signal);
        if (!cache) {
            cache = new Map();
            scopedSeriesMetaPromiseCaches.set(invocation.signal, cache);
        }
        return cache;
    }

    function fetchSeriesEnrichmentCached(seriesId, kind, invocation) {
        assertProcessInvocation(invocation);
        const sid = Number(seriesId);
        if (!Number.isFinite(sid) || sid <= 0) return Promise.resolve(null);
        const key = (kind === 'novel' ? 'novel:' : 'illust:') + sid;
        const cache = seriesMetaCache(invocation);
        if (cache.has(key)) return cache.get(key);
        const path = kind === 'novel'
            ? `/api/pixiv/novel/series/${sid}?page=1`
            : `/api/pixiv/series/${sid}?page=1`;
        const promise = fetch(BASE + path, {
            credentials: 'same-origin',
            headers: pixivHeader(),
            signal: processSignal(invocation)
        }).then(r => {
            assertProcessInvocation(invocation);
            return r.ok ? r.json() : null;
        }).then(data => {
            assertProcessInvocation(invocation);
            const meta = data && data.series ? data.series : null;
            if (!meta) return null;
            return {
                caption: meta.caption || '',
                coverUrl: meta.coverUrl || '',
                tags: Array.isArray(meta.tags) ? meta.tags : []
            };
        }).catch(() => {
            assertProcessInvocation(invocation);
            return null;
        });
        cache.set(key, promise);
        return promise;
    }

    async function sendDownload(artworkId, imageUrls, title, isUserDownload, username, authorId, authorName, xRestrict, isAi, ugoiraData, description, tags, seriesInfo, illustType, rawMetaJson, invocation) {
        assertProcessInvocation(invocation);
        const delayMs = getImageDelayMs();
        const collectionId = await resolveBatchCollectionIdForDownload(invocation);
        assertProcessInvocation(invocation);
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
            // 系列简介/封面只在本地数据库尚无时由后端落盘，前端这里仅负责把 Pixiv 的 hint 透传过去。
            // 缓存一批查一次，失败/空值不阻塞下载。
            const enrich = seriesInfo.seriesDescription || seriesInfo.seriesCoverUrl
                ? {caption: seriesInfo.seriesDescription, coverUrl: seriesInfo.seriesCoverUrl}
                : await fetchSeriesEnrichmentCached(seriesInfo.seriesId, 'illust', invocation);
            assertProcessInvocation(invocation);
            if (enrich) {
                if (enrich.caption) other.seriesDescription = enrich.caption;
                if (enrich.coverUrl) other.seriesCoverUrl = enrich.coverUrl;
            }
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
        assertProcessInvocation(invocation);
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
       下载管理器
    ============================================================ */
