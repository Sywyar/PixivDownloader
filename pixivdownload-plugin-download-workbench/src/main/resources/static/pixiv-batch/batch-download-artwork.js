'use strict';
    function assertProcessInvocation(invocation) {
        if (invocation) invocation.assertActive();
    }

    function processSignal(invocation) {
        return invocation ? invocation.signal : undefined;
    }

    async function getArtworkMeta(artworkId, invocation) {
        assertProcessInvocation(invocation);
        const data = await apiGet(`/api/pixiv/artwork/${artworkId}/meta`, {
            signal: processSignal(invocation)
        });
        assertProcessInvocation(invocation);
        if (data.error) throw new Error(data.error);
        return data;
    }

    async function getArtworkPages(artworkId, invocation) {
        assertProcessInvocation(invocation);
        const data = await apiGet(`/api/pixiv/artwork/${artworkId}/pages`, {
            signal: processSignal(invocation)
        });
        assertProcessInvocation(invocation);
        if (data.error) throw new Error(data.error);
        return data.urls || [];
    }

    async function getUgoiraMeta(artworkId, invocation) {
        assertProcessInvocation(invocation);
        const data = await apiGet(`/api/pixiv/artwork/${artworkId}/ugoira`, {
            signal: processSignal(invocation)
        });
        assertProcessInvocation(invocation);
        if (data.error) throw new Error(data.error);
        return data;
    }

    async function checkDownloaded(artworkId, invocation) {
        assertProcessInvocation(invocation);
        try {
            const query = state.settings.verifyHistoryFiles ? '?verifyFiles=true' : '';
            const res = await fetch(`${BASE}/api/downloaded/${artworkId}${query}`, {
                signal: processSignal(invocation)
            });
            assertProcessInvocation(invocation);
            if (res.status === 200) {
                const data = await res.json();
                assertProcessInvocation(invocation);
                if (!data.artworkId) return null;
                return data;
            }
            return null;
        } catch {
            assertProcessInvocation(invocation);
            return null;
        }
    }

    // 两阶段恢复：当 verifyFiles=true 的 fallback 路径把磁盘上已有的作品恢复成一条空 title 的裸记录时，
    // 用前端拉到的 Pixiv 元数据补齐缺失字段。后端是幂等的：DB 已有完整记录直接返回原记录。
    async function recoverArtworkMetadata(artworkId, meta, invocation) {
        assertProcessInvocation(invocation);
        try {
            const res = await fetch(`${BASE}/api/downloaded/${artworkId}/recover-metadata`, {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                signal: processSignal(invocation),
                body: JSON.stringify(meta)
            });
            assertProcessInvocation(invocation);
            if (res.status === 200) {
                const recovered = await res.json();
                assertProcessInvocation(invocation);
                return recovered;
            }
        } catch (e) {
            assertProcessInvocation(invocation);
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
        const res = await fetch(`${BASE}/api/download/pixiv`, {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            credentials: 'same-origin',
            signal: processSignal(invocation),
            body: JSON.stringify(payload)
        });
        assertProcessInvocation(invocation);
        const data = await res.json();
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

    async function getDownloadStatus(artworkId, invocation) {
        assertProcessInvocation(invocation);
        const res = await fetch(`${BASE}/api/download/status/${artworkId}`, {
            signal: processSignal(invocation)
        });
        assertProcessInvocation(invocation);
        const data = await res.json();
        assertProcessInvocation(invocation);
        return data;
    }

    /* ============================================================
       下载管理器
    ============================================================ */
