'use strict';
/* ============================================================
   小说作品类型行为模块（download-workbench 队列引擎的 novel 下载行为）。
   由小说插件经 /pixiv-novel-download/ serving；下载页据 /api/download/extensions
   列出的 moduleUrl 在运行期动态加载本模块，模块向宿主队列引擎注册 novel 类型的下载行为。
   小说下载端点已迁至小说自有前缀 /api/novel/**（旧址 /api/download/** 由 novel 插件
   的兼容垫片 forward，供油猴脚本懒迁移）。运行在下载页全局作用域，复用宿主既有工具函数
   （bt/sleep/state/renderQueue/updateStats/saveQueue/setCurrent/getCookie/fetchJsonWithProgress/
   fetchSeriesEnrichmentCached/evaluateDownloadFilterSkip/applyNovelStage/handleQuotaExceeded 等）。
============================================================ */

    // 三态判重：null = 未下载；{downloaded:true, deleted:false} = 已下载；{downloaded:true, deleted:true} = 已下载但被画廊删除
    async function checkNovelDownloaded(novelId) {
        try {
            const res = await fetch(`${BASE}/api/gallery/novel/${encodeURIComponent(novelId)}/downloaded`,
                {credentials: 'same-origin'});
            if (!res.ok) return null;
            const data = await res.json();
            return data && data.downloaded ? data : null;
        } catch {
            return null;
        }
    }

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
                // 软删除记录 + 允许重下：当作未下载继续走正常下载流程（落库后删除标记自动复位）
                if (downloaded && !(downloaded.deleted && state.settings.redownloadDeleted)) {
                    item.status = 'skipped';
                    item.lastMessage = downloaded.deleted
                        ? bt('queue.message.skipped-deleted', '跳过 — 已经下载过，但被删除')
                        : bt('queue.message.skipped-history', '跳过 — 历史记录中已存在');
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
            const dlRes = await fetch(`${BASE}/api/novel/download`, {
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
                const sRes = await fetch(`${BASE}/api/novel/status/${encodeURIComponent(novelId)}`);
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
                res = await fetch(`${BASE}/api/novel/translate-status/${encodeURIComponent(novelId)}`,
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
            if (st.done || st.failed || st.phase === 'DONE' || st.phase === 'FAILED'
                || st.phase === 'SAME_LANGUAGE') {
                item.translateDone = !!st.done || st.phase === 'DONE' || st.phase === 'SAME_LANGUAGE';
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

// 向宿主队列引擎注册 novel 作品类型：processSingle 据 item.kind 多态派发到本行为。
if (window.PixivBatch && window.PixivBatch.queueTypes) {
    window.PixivBatch.queueTypes.register('novel', { process: processNovelItem });
}
