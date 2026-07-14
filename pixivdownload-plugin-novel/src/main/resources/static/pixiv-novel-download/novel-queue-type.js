'use strict';
(function () {
let _activationContext = null;
function novelAcquisitionCredentialHeaders(credential = getCookie()) {
    return credential ? {'X-Acquisition-Credential': credential} : {};
}

function novelPreviewRequestInit() {
    return {credentials: 'same-origin', headers: novelAcquisitionCredentialHeaders()};
}

function novelBuildSearchRequest(ctx) {
    const uiMode = String(ctx.uiMode || 'all');
    const r18Family = ['r18', 'r18g', 'r18plus'].includes(uiMode);
    return {
        endpoint: '/api/pixiv/novel-search',
        params: {
            word: ctx.word,
            order: ctx.order,
            mode: r18Family ? 'r18' : uiMode,
            sMode: ctx.searchMode,
            page: ctx.page
        },
        clientFilter: uiMode === 'r18' ? 1 : (uiMode === 'r18g' ? 2 : 0),
        premiumOrder: ctx.order === 'popular_d',
        credentialMissing: !hasPixivCookie()
    };
}

function novelBuildRangeRequest(ctx) {
    const request = novelBuildSearchRequest(ctx);
    request.endpoint = '/api/pixiv/novel-search/range';
    request.params = Object.assign({}, request.params, {
        startPage: ctx.startPage,
        endPage: ctx.endPage
    });
    delete request.params.page;
    return request;
}

function requireNovelQuickSession(loader) {
    return function () {
        if (!cookieHasPhpsessid()) {
            throw new Error(bt('quick.error.no-cookie', '请先保存含 PHPSESSID 的 Cookie'));
        }
        return loader.apply(this, arguments);
    };
}

function assertNovelActivation() {
    if (_activationContext && typeof _activationContext.assertActive === 'function') {
        _activationContext.assertActive();
    }
}

function novelHookSignal(hookContext) {
    return hookContext && hookContext.signal
        ? hookContext.signal
        : (_activationContext && _activationContext.signal);
}

async function novelPreviewJson(path, hookContext) {
    assertNovelActivation();
    const response = await fetch(`${BASE}${path}`, {
        credentials: 'same-origin',
        headers: novelAcquisitionCredentialHeaders(),
        signal: novelHookSignal(hookContext)
    });
    const data = await response.json().catch(() => ({}));
    assertNovelActivation();
    if (!response.ok || data.error) {
        throw new Error(data.error || data.message || `HTTP ${response.status}`);
    }
    return data;
}

function parseNovelUserInput(raw) {
    const value = String(raw || '').trim();
    if (!value) return '';
    if (/^\d+$/.test(value)) return value;
    const match = value.match(/\/users\/(\d+)/);
    return match ? match[1] : '';
}

function novelQuickUserIdsRequest(userId) {
    return {endpoint: `/api/pixiv/user/${encodeURIComponent(userId)}/novels`};
}

function novelQuickCardsRequest(userId, ids) {
    return {
        endpoint: `/api/pixiv/user/${encodeURIComponent(userId)}/novel-cards`,
        params: {ids: (ids || []).map(String)}
    };
}

function novelBookmarkPageRequest(ctx) {
    return {
        endpoint: '/api/pixiv/me/novel-bookmarks',
        params: {rest: ctx.rest, offset: ctx.offset, limit: ctx.limit}
    };
}

async function getNovelUserMeta(userId, hookContext) {
    const data = await novelPreviewJson(
        `/api/pixiv/user/${encodeURIComponent(userId)}/meta`, hookContext);
    return data.name || '';
}
/* ============================================================
   小说作品类型行为模块（download-workbench 队列引擎的 novel 下载行为）。
   由小说插件经 /pixiv-novel-download/ serving；下载页据 /api/download/extensions
   列出的 moduleUrl 在运行期动态加载本模块，模块向宿主队列引擎注册 novel 类型的下载行为。
   小说下载端点已迁至小说自有前缀 /api/novel/**（旧址 /api/download/** 由 novel 插件
   的兼容垫片 forward，供油猴脚本懒迁移）。运行在下载页全局作用域，复用宿主既有工具函数
   （bt/state/renderQueue/updateStats/saveQueue/setCurrent/getCookie/fetchJsonWithProgress/
   fetchSeriesEnrichmentCached/evaluateDownloadFilterSkip/applyNovelStage/handleQuotaExceeded 等）。
============================================================ */

    function assertNovelProcess(invocation) {
        if (!invocation || typeof invocation.assertActive !== 'function') {
            throw new Error('novel queue process invocation is missing');
        }
        invocation.assertActive();
    }

    function waitForNovelProcess(delayMs, invocation) {
        assertNovelProcess(invocation);
        return new Promise((resolve, reject) => {
            let timer = null;
            const cleanup = () => {
                clearTimeout(timer);
                invocation.signal.removeEventListener('abort', abort);
            };
            const abort = () => {
                cleanup();
                try {
                    invocation.assertActive();
                } catch (error) {
                    reject(error);
                }
            };
            timer = setTimeout(() => {
                cleanup();
                try {
                    invocation.assertActive();
                    resolve();
                } catch (error) {
                    reject(error);
                }
            }, delayMs);
            if (invocation.signal.aborted) abort();
            else invocation.signal.addEventListener('abort', abort, {once: true});
        });
    }

    // 三态判重：null = 未下载；{downloaded:true, deleted:false} = 已下载；{downloaded:true, deleted:true} = 已下载但被画廊删除
    async function checkNovelDownloaded(novelId, invocation) {
        assertNovelProcess(invocation);
        try {
            const res = await fetch(`${BASE}/api/novel/${encodeURIComponent(novelId)}/downloaded`,
                {credentials: 'same-origin', signal: invocation.signal});
            assertNovelProcess(invocation);
            if (!res.ok) return null;
            const data = await res.json();
            assertNovelProcess(invocation);
            return data && data.downloaded ? data : null;
        } catch {
            assertNovelProcess(invocation);
            return null;
        }
    }

    async function processNovelItem(item, invocation) {
        assertNovelProcess(invocation);
        item.lastMessage = bt('queue.message.fetching-info', '正在获取作品信息...');
        setCurrent(item);
        renderQueue();
        const cookie = getCookie();
        const headers = novelAcquisitionCredentialHeaders(cookie);
        try {
            const novelId = item.novelId || String(item.id).replace(/^n/, '');

            if (state.settings.skipHistory) {
                const downloaded = await checkNovelDownloaded(novelId, invocation);
                assertNovelProcess(invocation);
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
                {credentials: 'same-origin', headers, signal: invocation.signal},
                (done, total) => {
                    assertNovelProcess(invocation);
                    item.novelText = {done, total};
                    const now = Date.now();
                    if (now - _lastTextRender > 120) {
                        _lastTextRender = now;
                        renderQueue();
                    }
                });
            assertNovelProcess(invocation);
            if (!metaRes.ok) {
                const errData = await metaRes.json().catch(() => ({}));
                assertNovelProcess(invocation);
                throw new Error(errData.error || ('meta HTTP ' + metaRes.status));
            }
            const meta = await metaRes.json();
            assertNovelProcess(invocation);
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
                ? await fetchSeriesEnrichmentCached(seriesInfo.seriesId, 'novel', invocation)
                : null;
            assertNovelProcess(invocation);
            const collectionId = await resolveBatchCollectionIdForDownload(invocation);
            assertNovelProcess(invocation);
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
                signal: invocation.signal,
                body: JSON.stringify(body)
            });
            assertNovelProcess(invocation);
            const dlData = await dlRes.json().catch(() => ({}));
            assertNovelProcess(invocation);
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
                await waitForNovelProcess(800, invocation);
                assertNovelProcess(invocation);
                const sRes = await fetch(`${BASE}/api/novel/status/${encodeURIComponent(novelId)}`, {
                    credentials: 'same-origin',
                    signal: invocation.signal
                });
                assertNovelProcess(invocation);
                if (!sRes.ok) continue;
                const status = await sRes.json();
                assertNovelProcess(invocation);
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
                        await maybeTriggerSeriesMerge(item.mergeAfterSeriesId, invocation);
                        assertNovelProcess(invocation);
                    }
                    // 下载即自动翻译：翻译在服务端独立队列异步进行，这里脱离下载 worker 单独轮询其状态，
                    // 不占下载并发名额；下载本身已成功，翻译失败只在该项附提示、不改成功状态。
                    if (item.status === 'completed' && autoTranslate) {
                        void pollNovelTranslateStatus(item, novelId, invocation).catch(() => {});
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
            assertNovelProcess(invocation);
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
    async function readMergeResponse(res, invocation) {
        try {
            const data = await res.json();
            assertNovelProcess(invocation);
            return data;
        } catch {
            assertNovelProcess(invocation);
            return null;
        }
    }

    async function maybeTriggerSeriesMerge(seriesId, invocation) {
        assertNovelProcess(invocation);
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
            const res = await fetch(`${BASE}/api/novel/series/${encodeURIComponent(seriesId)}/merge?format=${encodeURIComponent(mfmt)}`, {
                method: 'POST', credentials: 'same-origin', signal: invocation.signal
            });
            assertNovelProcess(invocation);
            const data = await readMergeResponse(res, invocation);
            assertNovelProcess(invocation);
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
            assertNovelProcess(invocation);
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
    async function pollNovelTranslateStatus(item, novelId, invocation) {
        assertNovelProcess(invocation);
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
            await waitForNovelProcess(1500, invocation);
            assertNovelProcess(invocation);
            let res;
            try {
                res = await fetch(`${BASE}/api/novel/translate-status/${encodeURIComponent(novelId)}`,
                    {credentials: 'same-origin', signal: invocation.signal});
                assertNovelProcess(invocation);
            } catch (error) {
                assertNovelProcess(invocation);
                continue;
            }
            if (res.status === 403) {
                // 非管理员：清除翻译态，停止轮询
                clearTranslateState(item, invocation);
                return;
            }
            if (res.status === 204) {
                // 尚未登记（下载完成与翻译提交之间的瞬时窗口）：容忍若干次后放弃
                if (++consecutiveEmpty >= 10) { clearTranslateState(item, invocation); return; }
                continue;
            }
            if (!res.ok) continue;
            consecutiveEmpty = 0;
            const st = await res.json().catch(() => null);
            assertNovelProcess(invocation);
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

    function clearTranslateState(item, invocation) {
        assertNovelProcess(invocation);
        item.translatePhase = null;
        item.translateElapsed = 0;
        item.translateSeriesPending = 0;
        renderQueue();
    }

/* ============================================================
   取得侧 UI 槽位：小说作品类型向下载页贡献的 DOM 片段（kind 单选 / 入口按钮 / 导入提示 /
   专属筛选 / 设置卡）。下载页据 /api/download/extensions 报告的启用情况，把这些片段注入宿主页
   同名 <template data-qt-slot> 锚点（见 batch-queue-types.js 的 renderSlots），作为真实兄弟节点
   就位。小说插件被禁用 → 本模块不加载 → 这些片段不注入 → 下载页对应取得侧入口自然消失。
   片段内的 id / class / data-* / onclick / data-i18n 与下载页既有交互契约一致：宿主在 init 阶段
   据此绑定 kind 单选、读写小说设置、按 .search-novel-only 显隐字数筛选等（这些行为仍在宿主既有模块，
   其按模式 / kind 的显隐裁决待后续细粒度取得侧钩子收口）。
============================================================ */
const NOVEL_SLOTS = {
    // User 模式 kind 单选「小说」选项（插画 / 约稿为宿主内置、留在 HTML）
    'kind-option-user':
        '<label data-kind="novel">' +
        '<input type="radio" name="user-kind" value="novel">' +
        ' <span data-i18n="novel:batch.user.kind-novel">小说</span></label>',
    // Search 模式 kind 单选「小说」选项
    'kind-option-search':
        '<label data-kind="novel">' +
        '<input type="radio" name="search-kind" value="novel">' +
        ' <span data-i18n="novel:batch.search.kind-novel">小说</span></label>',
    // 快捷获取二层预览的 kind 单选「小说」选项（珍藏集 / 用户作品里切换插画 / 小说网格）
    'kind-option-quick':
        '<label data-quick-kind="novel"><input type="radio" name="quick-inner-kind" value="novel">' +
        ' <span data-i18n="quick.preview.kind-novel">小说</span></label>',
    // 快捷获取「我的收藏（小说）」入口按钮（公开 / 不公开）
    'quick-actions-bookmarks':
        '<button class="btn btn-blue quick-action" data-quick="my-novel-bookmarks-show"' +
        ' onclick="quickLoad(\'my-novel-bookmarks-show\')" data-i18n="quick.action.novel-bookmarks-show">📚 我的收藏（小说，公开）</button>' +
        '<button class="btn btn-purple quick-action" data-quick="my-novel-bookmarks-hide"' +
        ' onclick="quickLoad(\'my-novel-bookmarks-hide\')" data-i18n="quick.action.novel-bookmarks-hide">🔒 我的收藏（小说，不公开）</button>',
    // 快捷获取「我自己的作品（小说）」入口按钮
    'quick-actions-mine':
        '<button class="btn btn-green quick-action" data-quick="my-novels"' +
        ' onclick="quickLoad(\'my-novels\')" data-i18n="quick.action.my-novels">✍ 我自己的作品（小说，含 hide）</button>',
    // 批量导入单作品的小说链接示例
    'import-hint':
        '<div><span data-i18n="label.import-novel-example">小说链接示例：</span>' +
        '<span class="import-example-code" data-i18n="label.import-novel-example-value">' +
        'https://www.pixiv.net/novel/show.php?id=12345678 | 示例标题</span></div>',
    // 附加筛选里的小说专属字段（最少 / 最多字数）；保留 .search-novel-only，由宿主 applySearchKindUI 按模式显隐
    'search-filter':
        '<div class="search-extra-item search-novel-only" style="display:none;">' +
        '<label for="search-words-min" data-i18n="novel:batch.search.words-min">最少字数</label>' +
        '<input type="number" id="search-words-min" min="0" step="100" data-i18n-placeholder="search.unlimited" placeholder="不限"' +
        ' onchange="handleSearchFilterChange()"></div>' +
        '<div class="search-extra-item search-novel-only" style="display:none;">' +
        '<label for="search-words-max" data-i18n="novel:batch.search.words-max">最多字数</label>' +
        '<input type="number" id="search-words-max" min="0" step="100" data-i18n-placeholder="search.unlimited" placeholder="不限"' +
        ' onchange="handleSearchFilterChange()"></div>',
    // 小说设置卡（格式 / 合订）；下载即自动翻译由 AI 插件追加到本卡片内。
    // id / class 与宿主 loadSettings/syncSettings/applyNovelSettingsVisibility 既有契约一致
    'settings-card':
        '<div class="card" id="novel-settings-card">' +
        '<div class="card-title" data-i18n="card.novel-settings">小说设置</div>' +
        '<div class="settings-grid">' +
        '<div class="setting-item">' +
        '<label for="s-novel-format" data-i18n="novel:batch.format-label">小说格式:</label>' +
        '<select id="s-novel-format" style="padding:4px 6px;border:1px solid #ddd;border-radius:4px;font-size:13px;">' +
        '<option value="txt" data-i18n="novel:format.txt">纯文本（TXT）</option>' +
        '<option value="html" data-i18n="novel:format.html">网页（HTML）</option>' +
        '<option value="epub" data-i18n="novel:format.epub">电子书（EPUB）</option>' +
        '</select></div>' +
        '<div class="setting-item">' +
        '<input type="checkbox" id="s-novel-merge">' +
        '<label for="s-novel-merge" style="cursor:pointer;" data-i18n="novel:batch.merge-label">系列下载完成后生成合订本</label>' +
        '</div>' +
        '<div class="setting-item" id="s-novel-merge-format-row">' +
        '<label for="s-novel-merge-format" data-i18n="novel:batch.merge-format-label">合订本格式:</label>' +
        '<select id="s-novel-merge-format" style="padding:4px 6px;border:1px solid #ddd;border-radius:4px;font-size:13px;">' +
        '<option value="epub" data-i18n="novel:batch.merge-format-epub">电子书（EPUB，推荐）</option>' +
        '<option value="txt" data-i18n="novel:batch.merge-format-txt">纯文本（TXT）</option>' +
        '<option value="html" data-i18n="novel:batch.merge-format-html">网页（HTML）</option>' +
        '</select></div>' +
        '<div class="setting-item" id="s-novel-merge-format-hint"' +
        ' style="grid-column:1/-1;font-size:12px;color:var(--muted);line-height:1.5;"' +
        ' data-i18n="novel:batch.merge-format-hint">推荐 EPUB：内嵌封面与插图、按「小说 → 章节」生成可跳转的多级目录、带书名/作者/简介等信息可在阅读器书架显示；TXT/HTML 为无插图的纯文本 / 单页备选。</div>' +
        '</div></div>'
};

/* ============================================================
   取得侧行为：小说作品类型向下载页各取得模式（user / search / series / quick）+ 批量导入 + 附加筛选
   贡献的抓取 / 渲染 / 队列 meta / 专属筛选逻辑。下载页宿主（modes/*.js、batch-filters.js、
   single-import.js）只面向 queueTypes 的 acquisition / import / filters 钩子调用，自身不再按
   作品类型字面量分流；小说插件被禁用 → 本模块不加载 → 这些钩子缺席 → 宿主回退插画内置路径、不发起
   小说专属请求。以下函数 / 卡片渲染均运行在下载页全局作用域，复用宿主既有工具函数（esc/bt/state/
   addSearchItemToQueue/addUserItemToQueue/addSeriesItemToQueue/quickInnerToggleQueue/getSearchBookmarkCount/
   getSeriesFallbackOrder/loadQuickMyWorks/quickRenderOuterWorks/renderQuickPagination 等）。
============================================================ */

const novelQueueId = item => 'n' + String(item.id);

// —— 数据抓取（取得侧；不可用时宿主不调用，故不会产生小说请求）——
async function getNovelBookmarkCountForSearch(novelId) {
    const data = await apiGet(`/api/pixiv/novel/${encodeURIComponent(novelId)}/bookmark-count`);
    if (data.error) throw new Error(data.error);
    return data;
}

async function getUserNovels(userId, hookContext) {
    const data = await novelPreviewJson(
        `/api/pixiv/user/${encodeURIComponent(userId)}/novels`, hookContext);
    return data.ids || [];
}

async function resolveSeriesIdFromNovel(novelId, hookContext) {
    const meta = await novelPreviewJson(
        `/api/pixiv/novel/${encodeURIComponent(novelId)}/meta`, hookContext);
    if (!meta.seriesId) {
        throw new Error(bt('status.series-novel-no-series', '该小说不属于任何小说系列'));
    }
    return Number(meta.seriesId);
}

/* —— Search 模式：小说搜索结果网格 + 队列态同步 ——
   Vue reactive 渲染：搜索结果网格改用 Vue（reactive 数据驱动）渲染，
   卡片列表 / in-queue 高亮 / 点击入队由 Vue 模板绑定，去掉手动 innerHTML 重建 + 逐卡 DOM 同步。
   - 运行时单一来源：核心 Vue 全局构建版（经共享 helper window.PixivVue.ensure() 按需懒加载，不全站加载；
     具体运行时路径只由 helper 解析、本模块不硬编码）。
   - 优雅缺席 / 回退：window.PixivVue 缺失（运行时未接线）或 Vue 运行时加载 / 挂载失败时，逐字回退到
     命令式渲染 applyNovelSearchImperative（旧实现原样保留），绝不向宿主 init 抛异常；小说插件被禁用 →
     本模块不加载 → 这两个钩子缺席 → 宿主回退插画内置路径（既有行为不变）。
   - 与 descriptor.slots / renderSlots 正交：当前实现只改 acquisition.search 的渲染钩子（渲染进宿主提供的
     #search-results-area），不碰 NOVEL_SLOTS 的 <template data-qt-slot> 片段注入路径与其锚点顺序。
   - 模型不 bake 翻译：reactive 模型只存原始码 / 原始数据（item 标题 / userName / 字数 / 收藏数等），
     显示文案在模板渲染期经 bt() 派生（{{ t(...) }} 方法绑定），跟随语言切换重新派生。summary 头部为
     每次 render 重算的瞬时输出（语言切换会触发 render 重算），不是跨语言复用的长生命周期模型。 */

let _novelSearchVue = null;          // { app, vm, state, root, area } 或 null（未挂载）
let _novelSearchLatestModel = null;  // 供在途异步挂载读取的最新模型
let _novelSearchMounting = false;    // 防止并发触发多次挂载

// 渲染钩子（descriptor.acquisition.search.render）：优先 Vue reactive 挂载，Vue 不可用即命令式回退。
function renderNovelSearchResults(area, view) {
    view = view || getSearchView();
    // Vue helper 未接线（运行时单一来源未加载）→ 纯命令式渲染（旧行为，逐字不变）。
    if (!window.PixivVue) {
        applyNovelSearchImperative(area, view);
        return;
    }
    const model = buildNovelSearchModel(view);
    _novelSearchLatestModel = model;
    // 已挂载且根节点仍在当前 area 内 → 仅更新 reactive 状态，卡片 / in-queue 由 Vue 自动重渲染。
    if (_novelSearchVue && _novelSearchVue.area === area && area.contains(_novelSearchVue.root)) {
        assignNovelSearchState(_novelSearchVue.state, model);
        return;
    }
    // 尚未挂载，或 area 被宿主清空（空结果 / 切到插画搜索）→ 先命令式即时出图（兼作回退与首屏占位），
    // 再异步挂载 Vue 覆盖（运行时已缓存时挂载发生在同帧微任务内、无可见闪烁）。
    applyNovelSearchImperative(area, view);
    ensureNovelSearchMounted(area);
}

// 异步确保 Vue 组件挂到 area 内的专属根节点（与宿主直接写 area.innerHTML 的命令式路径隔离，
// 便于按 area.contains(root) 探测「被宿主清空」并按需重挂）。挂载失败一律收敛、不打断宿主。
function ensureNovelSearchMounted(area) {
    if (_novelSearchMounting) return;
    _novelSearchMounting = true;
    window.PixivVue.ensure().then(function (Vue) {
        if (!_activationContext || !_activationContext.isActive()) return;
        // 卸载可能存在的旧 app（area 曾被宿主清空 → 旧挂载已失效，避免悬挂的 vnode 树）。
        if (_novelSearchVue) {
            try { _novelSearchVue.app.unmount(); } catch (e) { /* 卸载失败忽略 */ }
            _novelSearchVue = null;
        }
        const state = Vue.reactive(buildEmptyNovelSearchState());
        assignNovelSearchState(state, _novelSearchLatestModel || buildEmptyNovelSearchState());
        // 先在游离根上 createApp + mount：成功后才替换 area 内容。若 createApp / mount 抛错，
        // 此刻尚未触碰 area，命令式首屏结果（applyNovelSearchImperative 已写入 area）保持完整。
        const root = document.createElement('div');
        root.className = 'novel-search-vue-root';
        const app = Vue.createApp(buildNovelSearchComponent(state));
        const vm = app.mount(root);
        if (!_activationContext || !_activationContext.isActive()) {
            try { app.unmount(); } catch (e) { /* best effort */ }
            return;
        }
        // 挂载成功，才用 Vue 根替换命令式首屏结果，并登记句柄。
        area.innerHTML = '';
        area.appendChild(root);
        _novelSearchVue = {app: app, vm: vm, state: state, root: root, area: area};
    }).catch(function (e) {
        // Vue 运行时不可用 / createApp / mount 抛错 → 命令式首屏结果原样保留（area 未被清空），
        // _novelSearchVue 置空，绝不向宿主 init 抛异常（优雅降级）。
        _novelSearchVue = null;
        console.warn('[novel-search] Vue 运行时不可用，沿用命令式渲染：', e);
    }).finally(function () {
        _novelSearchMounting = false;
    });
}

// 据当前搜索视图构造 reactive 模型：cards 只存原始数据（显示文案模板期派生）；summary 为本次 render 的
// 瞬时文案数组（与命令式实现同源、同样每次 render 重算）；inQueueIds 为队列 id 集合（驱动 in-queue 高亮）。
function buildNovelSearchModel(view) {
    view = view || getSearchView();
    const inQueue = new Set(state.queue.map(q => q.id));
    const summary = searchState.submode === 'batch'
        ? [batchSummaryText(view)]
        : [
            bt('search.summary.total-results', '共 {count} 个结果', {count: searchState.total.toLocaleString()}),
            bt('search.summary.current-page-index', '当前第 {page} 页', {page: searchState.currentPage}),
            bt('search.summary.pixiv-returned', 'Pixiv 返回 {count} 个', {count: searchState.pixivPageCount})
        ];
    if (searchState.submode !== 'batch' && hasExtraSearchFilter()) {
        summary.push(bt('search.summary.extra-filtered', '附加筛选后 {count} 个', {count: searchState.results.length}));
    }
    const cards = view.items.map((item, i) => ({
        idx: view.base + i,
        id: item.id,
        queueId: 'n' + String(item.id),
        title: item.title || '',
        userName: item.userName || '',
        xr: Number(item.xRestrict ?? 0),
        isAi: Number(item.aiType ?? 0) >= 2,
        isOriginal: !!item.isOriginal,
        wc: Number(item.wordCount ?? item.textLength ?? 0),
        bookmarkCount: getSearchBookmarkCount(item)
    }));
    return {cards: cards, summary: summary, noCookie: !!searchState.noCookie, inQueueIds: Array.from(inQueue)};
}

function buildEmptyNovelSearchState() {
    return {cards: [], summary: [], noCookie: false, inQueueIds: new Set()};
}

// 把模型批量写入 reactive 状态（替换引用即触发重渲染；inQueueIds 用 Set 以 has() 驱动 in-queue 绑定）。
function assignNovelSearchState(target, model) {
    target.cards = model.cards;
    target.summary = model.summary;
    target.noCookie = model.noCookie;
    target.inQueueIds = new Set(model.inQueueIds);
}

// 小说搜索网格 Vue 组件模板：文案全部经 t* 方法在渲染期派生（不 bake 进模型）；in-queue 高亮与 tooltip
// 经 isInQueue() 读 reactive inQueueIds，队列变化时自动更新；点击入队复用宿主 addSearchItemToQueue。
const NOVEL_SEARCH_TEMPLATE = `
<div v-if="state.noCookie" style="font-size:12px;color:#e6a700;margin-bottom:8px;">{{ tNoCookie() }}</div>
<div style="font-size:12px;color:#888;margin-bottom:10px;">
  <template v-for="(s, i) in state.summary" :key="i"><span>{{ s }}</span>{{ i < state.summary.length - 1 ? sep() : '' }}</template>
</div>
<div class="novel-search-grid">
  <div v-for="card in state.cards" :key="card.queueId" class="novel-search-card"
       :class="{ 'in-queue': isInQueue(card) }" :data-novel-idx="card.idx"
       :title="cardTitle(card)" @click="onCardClick(card.idx)">
    <div class="nsc-title">{{ displayTitle(card) }}</div>
    <div class="nsc-author">{{ displayAuthor(card) }}</div>
    <div class="nsc-meta">
      <span v-if="card.xr === 1" class="nsc-r18">R-18</span>
      <span v-else-if="card.xr === 2" class="nsc-r18g">R-18G</span>
      <span v-if="card.isAi" class="nsc-ai">AI</span>
      <span v-if="card.isOriginal" class="nsc-original">{{ tOriginal() }}</span>
      <span v-if="card.wc > 0">{{ tWords(card.wc) }}</span>
      <span v-if="card.bookmarkCount !== null">{{ tBookmark(card.bookmarkCount) }}</span>
    </div>
    <span class="nsc-in-queue-mark">✓</span>
  </div>
</div>`;

function buildNovelSearchComponent(reactiveState) {
    return {
        setup() {
            const tNoCookie = () => bt('status.search-no-cookie-warning', '⚠ 未保存 Cookie，搜索结果可能减少');
            const tOriginal = () => bt('novel:batch.search.original', '原创');
            const tWords = (wc) => bt('novel:batch.search.summary.novel-words', '{count} 字', {count: wc.toLocaleString()});
            const tBookmark = (n) => bt('search.summary.bookmark-badge', '收藏 {count}', {count: n.toLocaleString()});
            const displayTitle = (card) => card.title || bt('novel:status.unknown-novel', '小说 {id}', {id: card.id});
            const displayAuthor = (card) => card.userName || bt('novel:status.unknown-author', '未知');
            const isInQueue = (card) => reactiveState.inQueueIds.has(card.queueId);
            const cardTitle = (card) => {
                const bookmarkTip = buildBookmarkTip(card.bookmarkCount);
                const queueTip = buildQueueToggleTip(isInQueue(card));
                return `${displayTitle(card)} (${displayAuthor(card)})${bookmarkTip}${queueTip}`;
            };
            const sep = () => summarySeparator();
            const onCardClick = (idx) => addSearchItemToQueue(Number(idx));
            return {
                state: reactiveState,
                tNoCookie, tOriginal, tWords, tBookmark,
                displayTitle, displayAuthor, isInQueue, cardTitle, sep, onCardClick
            };
        },
        template: NOVEL_SEARCH_TEMPLATE
    };
}

// 命令式回退渲染（旧实现逐字保留）：Vue 不可用 / 加载失败时使用，并兼作 Vue 挂载前的首屏即时占位。
function applyNovelSearchImperative(area, view) {
    view = view || getSearchView();
    const inQueue = new Set(state.queue.map(q => q.id));
    const summary = searchState.submode === 'batch'
        ? [batchSummaryText(view)]
        : [
            bt('search.summary.total-results', '共 {count} 个结果', {count: searchState.total.toLocaleString()}),
            bt('search.summary.current-page-index', '当前第 {page} 页', {page: searchState.currentPage}),
            bt('search.summary.pixiv-returned', 'Pixiv 返回 {count} 个', {count: searchState.pixivPageCount})
        ];
    if (searchState.submode !== 'batch' && hasExtraSearchFilter()) {
        summary.push(bt('search.summary.extra-filtered', '附加筛选后 {count} 个', {count: searchState.results.length}));
    }
    const cards = view.items.map((item, i) => {
        const idx = view.base + i;
        const xr = Number(item.xRestrict ?? 0);
        const isAi = Number(item.aiType ?? 0) >= 2;
        const wc = Number(item.wordCount ?? item.textLength ?? 0);
        const bookmarkCount = getSearchBookmarkCount(item);
        const queueId = 'n' + String(item.id);
        const inQueueClass = inQueue.has(queueId) ? ' in-queue' : '';
        const meta = [];
        if (xr === 1) meta.push('<span class="nsc-r18">R-18</span>');
        else if (xr === 2) meta.push('<span class="nsc-r18g">R-18G</span>');
        if (isAi) meta.push('<span class="nsc-ai">AI</span>');
        if (item.isOriginal) meta.push(`<span class="nsc-original">${esc(bt('novel:batch.search.original', '原创'))}</span>`);
        if (wc > 0) meta.push(`<span>${esc(bt('novel:batch.search.summary.novel-words', '{count} 字', {count: wc.toLocaleString()}))}</span>`);
        if (bookmarkCount !== null) {
            meta.push(`<span>${esc(bt('search.summary.bookmark-badge', '收藏 {count}', {count: bookmarkCount.toLocaleString()}))}</span>`);
        }
        const fallbackTitle = bt('novel:status.unknown-novel', '小说 {id}', {id: item.id});
        const fallbackAuthor = bt('novel:status.unknown-author', '未知');
        const bookmarkTip = buildBookmarkTip(bookmarkCount);
        const queueTip = buildQueueToggleTip(inQueue.has(queueId));
        const cardTitle = `${item.title || fallbackTitle} (${item.userName || fallbackAuthor})${bookmarkTip}${queueTip}`;
        return `<div class="novel-search-card${inQueueClass}" data-novel-idx="${idx}" title="${esc(cardTitle)}">
        <div class="nsc-title">${esc(item.title || fallbackTitle)}</div>
        <div class="nsc-author">${esc(item.userName || fallbackAuthor)}</div>
        <div class="nsc-meta">${meta.join('')}</div>
        <span class="nsc-in-queue-mark">✓</span>
      </div>`;
    }).join('');
    area.innerHTML = `
    ${searchState.noCookie ? `<div style="font-size:12px;color:#e6a700;margin-bottom:8px;">${esc(bt('status.search-no-cookie-warning', '⚠ 未保存 Cookie，搜索结果可能减少'))}</div>` : ''}
    <div style="font-size:12px;color:#888;margin-bottom:10px;">
      ${summary.map(s => `<span>${esc(s)}</span>`).join(summarySeparator())}
    </div>
    <div class="novel-search-grid">${cards}</div>`;
    area.querySelectorAll('.novel-search-card').forEach(card => {
        card.addEventListener('click', () => addSearchItemToQueue(Number(card.dataset.novelIdx)));
    });
}

// 队列态同步钩子（descriptor.acquisition.search.syncQueueState）：Vue 已挂载时替换 reactive in-queue 集合，
// 卡片高亮 / tooltip 自动跟随（不再手动遍历 DOM）；未挂载（命令式回退）时沿用旧的逐卡 DOM 局部更新。
function syncNovelSearchQueueState(results, inQueue) {
    if (_novelSearchVue && _novelSearchVue.state) {
        _novelSearchVue.state.inQueueIds = new Set(inQueue);
        return;
    }
    results.forEach((item, idx) => {
        const el = document.querySelector(`.novel-search-card[data-novel-idx="${idx}"]`);
        if (!el) return;
        el.classList.toggle('in-queue', inQueue.has('n' + String(item.id)));
    });
}

// —— User 模式：画师小说作品网格 ——
function renderNovelUserResults(area, ctx) {
    const inQueue = ctx.inQueue;
    const cards = ctx.items.map((item, idx) => {
        const xr = Number(item.xRestrict ?? 0);
        const isAi = Number(item.aiType ?? 0) >= 2;
        const wc = Number(item.wordCount ?? item.textLength ?? 0);
        const bookmarkCount = getSearchBookmarkCount(item, 'novel');
        const queueId = 'n' + String(item.id);
        const inQueueClass = inQueue.has(queueId) ? ' in-queue' : '';
        const meta = [];
        if (xr === 1) meta.push('<span class="nsc-r18">R-18</span>');
        else if (xr === 2) meta.push('<span class="nsc-r18g">R-18G</span>');
        if (isAi) meta.push('<span class="nsc-ai">AI</span>');
        if (item.isOriginal) meta.push(`<span class="nsc-original">${esc(bt('novel:batch.search.original', '原创'))}</span>`);
        if (wc > 0) meta.push(`<span>${esc(bt('novel:batch.search.summary.novel-words', '{count} 字', {count: wc.toLocaleString()}))}</span>`);
        if (bookmarkCount !== null) meta.push(`<span>${esc(bt('search.summary.bookmark-badge', '收藏 {count}', {count: bookmarkCount.toLocaleString()}))}</span>`);
        const fallbackTitle = bt('queue.novel-fallback', '小说 {id}', {id: item.id});
        const fallbackAuthor = ctx.username || bt('novel:status.unknown-author', '未知');
        const bookmarkTip = buildBookmarkTip(bookmarkCount);
        const queueTip = buildQueueToggleTip(inQueue.has(queueId));
        const cardTitle = `${item.title || fallbackTitle} (${item.userName || fallbackAuthor})${bookmarkTip}${queueTip}`;
        return `<div class="novel-search-card${inQueueClass}" data-user-novel-idx="${idx}" id="user-novel-card-${idx}" title="${esc(cardTitle)}">
        <div class="nsc-title">${esc(item.title || fallbackTitle)}</div>
        <div class="nsc-author">${esc(item.userName || fallbackAuthor)}</div>
        <div class="nsc-meta">${meta.join('')}</div>
        <span class="nsc-in-queue-mark">✓</span>
      </div>`;
    }).join('');
    area.innerHTML = ctx.summaryHtml + `<div class="novel-search-grid">${cards}</div>`;
    area.querySelectorAll('.novel-search-card').forEach(card => {
        card.addEventListener('click', () => addUserItemToQueue(Number(card.dataset.userNovelIdx)));
    });
}

// —— Series 模式：小说系列卡片列表 + 封面 + 专属 meta 文案 ——
function renderSeriesNovelTags(tags) {
    if (!Array.isArray(tags) || tags.length === 0) {
        return `<span>${esc(bt('series.tags-empty', '无标签'))}</span>`;
    }
    return tags.slice(0, 8).map(tag => {
        const name = typeof tag === 'string' ? tag : (tag.name || tag.tag || '');
        const translated = typeof tag === 'object' && tag ? (tag.translatedName || tag.translation || '') : '';
        const label = translated ? `${name} / ${translated}` : name;
        return label ? `<span>${esc(label)}</span>` : '';
    }).join('');
}

function formatNovelUploadDate(timestamp) {
    const value = Number(timestamp || 0);
    if (!Number.isFinite(value) || value <= 0) return '';
    const d = new Date(value);
    if (Number.isNaN(d.getTime())) return '';
    const text = d.toLocaleDateString(uiLang() === 'en-US' ? 'en-US' : 'zh-CN', {
        year: 'numeric',
        month: '2-digit',
        day: '2-digit'
    });
    return bt('series.meta.upload', '上传：{time}', {time: text});
}

function formatNovelReadingTime(seconds) {
    const text = formatNovelReadingDuration(seconds);
    return text ? bt('series.meta.reading-time', '预计阅读：{time}', {time: text}) : '';
}

function formatNovelReadingDuration(seconds) {
    const total = Math.floor(Number(seconds || 0));
    if (!Number.isFinite(total) || total <= 0) return '';
    const hour = Math.floor(total / 3600);
    const minute = Math.floor((total % 3600) / 60);
    const second = total % 60;
    const parts = [];
    if (hour > 0) parts.push(bt('duration.hour', '{count} 小时', {count: hour}));
    if (minute > 0) parts.push(bt('duration.minute', '{count} 分钟', {count: minute}));
    if (second > 0 && hour === 0) parts.push(bt('duration.second', '{count} 秒', {count: second}));
    if (!parts.length) parts.push(bt('duration.second', '{count} 秒', {count: total}));
    return parts.join(' ');
}

async function loadNovelSeriesCoversBatched(items, renderToken) {
    const BATCH = 10;
    for (let i = 0; i < items.length; i += BATCH) {
        if (renderToken !== seriesState.renderToken) return;
        const batch = items.slice(i, i + BATCH);
        await Promise.allSettled(batch.map((item, offset) => loadSingleNovelSeriesCover(item, i + offset, renderToken)));
    }
}

async function loadSingleNovelSeriesCover(item, idx, renderToken) {
    if (!item.coverUrl) return;
    const coverEl = document.getElementById(`series-novel-cover-${idx}`);
    if (!coverEl) return;
    try {
        const params = new URLSearchParams({url: item.coverUrl});
        const res = await fetch(`${BASE}/api/pixiv/thumbnail-proxy?${params}`, {headers: pixivHeader()});
        if (!res.ok) return;
        const blob = await res.blob();
        if (renderToken !== seriesState.renderToken) return;
        const blobUrl = URL.createObjectURL(blob);
        seriesState.activeBlobUrls.push(blobUrl);
        if (coverEl.isConnected) {
            coverEl.innerHTML = `<img src="${blobUrl}" alt="${esc(item.title || '')}">`;
        }
    } catch {
    }
}

function renderNovelSeriesResults(area, ctx) {
    const inQueue = ctx.inQueue;
    area.innerHTML = `
    <div class="novel-series-list">
      ${ctx.items.map((item, idx) => {
        const xr = Number(item.xRestrict ?? 0);
        const isAi = Number(item.aiType ?? 0) >= 2;
        const wc = Number(item.wordCount ?? item.textLength ?? 0);
        const seriesOrder = Number(item.seriesOrder || getSeriesFallbackOrder(idx));
        const queueId = 'n' + String(item.id);
        const inQueueClass = inQueue.has(queueId) ? ' in-queue' : '';
        const meta = [];
        if (xr === 1) meta.push('<span class="nsc-r18">R-18</span>');
        else if (xr === 2) meta.push('<span class="nsc-r18g">R-18G</span>');
        if (isAi) meta.push('<span class="nsc-ai">AI</span>');
        const uploadText = formatNovelUploadDate(item.uploadTimestamp);
        if (uploadText) meta.push(`<span>${esc(uploadText)}</span>`);
        if (wc > 0) meta.push(`<span>${esc(bt('novel:batch.search.summary.novel-words', '{count} 字', {count: wc.toLocaleString()}))}</span>`);
        const readingText = formatNovelReadingTime(item.readingTimeSeconds);
        if (readingText) meta.push(`<span>${esc(readingText)}</span>`);
        const fallbackTitle = bt('queue.novel-fallback', '小说 {id}', {id: item.id});
        const fallbackAuthor = bt('novel:status.unknown-author', '未知');
        const queueTip = buildQueueToggleTip(inQueue.has(queueId));
        const tagsHtml = renderSeriesNovelTags(item.tags || []);
        return `<div class="novel-series-card${inQueueClass}" id="series-novel-card-${idx}"
                         onclick="addSeriesItemToQueue(${idx})" title="#${seriesOrder} ${esc(item.title || fallbackTitle)} (${esc(item.userName || fallbackAuthor)})${queueTip}">
          <div class="novel-series-cover" id="series-novel-cover-${idx}"><span>${esc(bt('series.cover-placeholder', '封面'))}</span></div>
          <div class="novel-series-info">
            <div class="novel-series-title">#${seriesOrder} ${esc(item.title || fallbackTitle)}</div>
            <div class="nsc-author">${esc(item.userName || fallbackAuthor)}</div>
            <div class="novel-series-tags">${tagsHtml}</div>
            <div class="novel-series-meta">${meta.join('')}</div>
          </div>
          <span class="nsc-in-queue-mark">✓</span>
        </div>`;
    }).join('')}
    </div>`;
    loadNovelSeriesCoversBatched(ctx.items, ctx.renderToken);
}

// —— Quick 模式：小说收藏入口加载 + 小说网格 + 混合珍藏集里的小说卡 ——
async function loadQuickNovelBookmarks(rest, page) {
    const offset = (page - 1) * QUICK_PAGE_SIZE_NOVEL;
    const params = new URLSearchParams({rest, offset: String(offset), limit: String(QUICK_PAGE_SIZE_NOVEL)});
    const data = await quickFetchJson(`${BASE}/api/pixiv/me/novel-bookmarks?${params}`);
    quickState.rawItems = data.items || [];
    quickState.total = data.total || 0;
    quickState.offset = offset;
    quickState.page = page;
    const titleKey = rest === 'hide' ? 'quick.title.novel-bookmarks-hide' : 'quick.title.novel-bookmarks-show';
    const titleFallback = rest === 'hide' ? '我的收藏（小说，不公开）' : '我的收藏（小说，公开）';
    quickSetTitle(`${bt(titleKey, titleFallback)} · ${bt('quick.title.count', '{count} 件', {count: quickState.total.toLocaleString()})}`);
    quickShowToolbar({showBack: false, showAdd: quickState.rawItems.length > 0, showSearch: false, showKindSwitcher: false});
    await quickRenderOuterWorks();
    renderQuickPagination(page, Math.max(1, Math.ceil(quickState.total / QUICK_PAGE_SIZE_NOVEL)),
        p => loadQuickNovelBookmarks(rest, p));
    updateExtraFiltersCardVisibility();
    updateSaveScheduleCardVisibility();
    applyNovelSettingsVisibility();
}

function renderQuickNovelGrid(items, idPrefix, summaryHtml = '') {
    const area = document.getElementById('quick-preview-area');
    if (!area) return;
    if (!items.length) {
        const emptyMsg = summaryHtml
            ? bt('status.search-no-filtered-results', '附加筛选后无结果')
            : bt('quick.empty.no-items', '该范围内没有作品');
        area.innerHTML = summaryHtml + `<div class="quick-empty">${esc(emptyMsg)}</div>`;
        return;
    }
    const inQueue = new Set(state.queue.map(q => q.id));
    area.innerHTML = summaryHtml + `<div class="novel-search-grid">${items.map((item, idx) => {
        const xr = Number(item.xRestrict ?? 0);
        const isAi = Number(item.aiType ?? 0) >= 2;
        const wc = Number(item.wordCount ?? item.textLength ?? 0);
        const queueId = 'n' + String(item.id);
        const inQueueClass = inQueue.has(queueId) ? ' in-queue' : '';
        const meta = [];
        if (xr === 1) meta.push('<span class="nsc-r18">R-18</span>');
        else if (xr === 2) meta.push('<span class="nsc-r18g">R-18G</span>');
        if (isAi) meta.push('<span class="nsc-ai">AI</span>');
        if (item.isOriginal) meta.push(`<span class="nsc-original">${esc(bt('novel:batch.search.original', '原创'))}</span>`);
        if (wc > 0) meta.push(`<span>${esc(bt('novel:batch.search.summary.novel-words', '{count} 字', {count: wc.toLocaleString()}))}</span>`);
        const fallbackTitle = bt('queue.novel-fallback', '小说 {id}', {id: item.id});
        const title = item.title || fallbackTitle;
        return `<div class="novel-search-card${inQueueClass}" id="${idPrefix}-novel-card-${idx}"
                     onclick="quickToggleItemQueue(${idx})" title="${esc(title)} (${esc(item.userName || '')})">
          <div class="nsc-title">${esc(title)}</div>
          <div class="nsc-author">${esc(item.userName || '')}</div>
          <div class="nsc-meta">${meta.join('')}</div>
          <span class="nsc-in-queue-mark">✓</span>
        </div>`;
    }).join('')}</div>`;
}

function novelQuickInnerCard(item, idx, inQueue) {
    const title = item.title || bt('queue.novel-fallback', '小说 {id}', {id: item.id});
    const xr = Number(item.xRestrict ?? 0);
    const isAi = Number(item.aiType ?? 0) >= 2;
    const wc = Number(item.wordCount ?? item.textLength ?? 0);
    const inQueueClass = inQueue.has('n' + String(item.id)) ? ' in-queue' : '';
    const meta = [];
    if (xr === 1) meta.push('<span class="nsc-r18">R-18</span>');
    else if (xr === 2) meta.push('<span class="nsc-r18g">R-18G</span>');
    if (isAi) meta.push('<span class="nsc-ai">AI</span>');
    if (item.isOriginal) meta.push(`<span class="nsc-original">${esc(bt('novel:batch.search.original', '原创'))}</span>`);
    if (wc > 0) meta.push(`<span>${esc(bt('novel:batch.search.summary.novel-words', '{count} 字', {count: wc.toLocaleString()}))}</span>`);
    return `<div class="novel-search-card${inQueueClass}" id="quick-inner-card-${idx}"
                         onclick="quickInnerToggleQueue(${idx})" title="${esc(title)} (${esc(item.userName || '')})">
              <div class="nsc-title">${esc(title)}</div>
              <div class="nsc-author">${esc(item.userName || '')}</div>
              <div class="nsc-meta">${meta.join('')}</div>
              <span class="nsc-in-queue-mark">✓</span>
            </div>`;
}

// 小说作品类型 descriptor：下载行为（process）+ 取得侧 UI 槽位（slots）+ 取得侧行为钩子
// （acquisition：user/search/series/quick）+ 批量导入解析（import）+ 附加筛选（filters）+ 设置卡（settings）。
const NOVEL_DESCRIPTOR = {
    slots: NOVEL_SLOTS,
    process: processNovelItem,
    // 批量导入单作品：小说链接 / `novel:` 区段头 / 裸 id 的解析与入队项构造。
    import: {
        sectionType: 'novel',
        matchUrl(line) {
            const m = String(line).match(/https?:\/\/www\.pixiv\.net\/novel\/show\.php\?[^\s|]*?\bid=(\d+)/);
            return m ? m[1] : null;
        },
        buildItem(id, title) {
            return {
                id: 'n' + id,
                novelId: id,
                kind: 'novel',
                title: title || bt('queue.novel-fallback', '小说 {id}', {id})
            };
        },
        source: SINGLE_IMPORT_NOVEL_SOURCE
    },
    // 附加筛选里的小说专属字段（字数）：显隐选择器 + 逐作品匹配 + 下载跳过 + 收藏数抓取器。
    filters: {
        'novel-words': {
            extraSelector: '.search-novel-only',
            matchExtra(item, filters) {
                const wc = Number(item.wordCount ?? 0);
                if (filters.wordsMin !== null && wc < filters.wordsMin) return false;
                if (filters.wordsMax !== null && wc > filters.wordsMax) return false;
                return true;
            },
            evaluateSkip(meta, filters) {
                const wc = Number(meta.wordCount ?? 0);
                if (wc > 0) {
                    if (filters.wordsMin !== null && wc < filters.wordsMin) return bt('queue.message.skipped-filter-words', '跳过 — 字数不符附加筛选');
                    if (filters.wordsMax !== null && wc > filters.wordsMax) return bt('queue.message.skipped-filter-words', '跳过 — 字数不符附加筛选');
                }
                return null;
            },
            bookmarkCountFetch: getNovelBookmarkCountForSearch
        }
    },
    // 小说设置卡（格式 / 合订）；宿主按模式 + kind 显隐。
    settings: {'novel-settings-card': {cardId: 'novel-settings-card'}},
    acquisition: {
        user: {
            pageSize: 30,
            requestInit: novelPreviewRequestInit,
            accepts(selection) { return selection === 'novel'; },
            parseInput: parseNovelUserInput,
            fetchMeta: getNovelUserMeta,
            fetchIds: getUserNovels,
            cardsEndpoint(userId) { return `/api/pixiv/user/${encodeURIComponent(userId)}/novel-cards`; },
            queueId: novelQueueId,
            cardId(idx) { return `user-novel-card-${idx}`; },
            render: renderNovelUserResults,
            buildQueueMeta(item, ctx) {
                return {
                    title: item.title || bt('queue.novel-fallback', '小说 {id}', {id: item.id}),
                    novelId: String(item.id),
                    kind: 'novel',
                    authorId: item.userId ? Number(item.userId) : Number(ctx.userId),
                    authorName: item.userName || ctx.username || ctx.userId,
                    isAi: Number(item.aiType ?? 0) >= 2,
                    xRestrict: Number(item.xRestrict ?? 0),
                    tags: Array.isArray(item.tags) ? item.tags : []
                };
            },
            buildQueueMetaFromId(id, ctx) {
                return {
                    title: bt('queue.novel-fallback', '小说 {id}', {id}),
                    novelId: String(id),
                    kind: 'novel',
                    authorId: Number(ctx.userId),
                    authorName: ctx.username || ctx.userId
                };
            }
        },
        search: {
            pageSize: 24,
            requestInit: novelPreviewRequestInit,
            buildRequest: novelBuildSearchRequest,
            buildRangeRequest: novelBuildRangeRequest,
            queueId: novelQueueId,
            queueSource: 'search-novel',
            emptyResultsLabel() { return bt('novel:batch.search.no-novel-results', '无小说搜索结果'); },
            render: renderNovelSearchResults,
            syncQueueState: syncNovelSearchQueueState,
            buildQueueMeta(item) {
                return {
                    title: item.title,
                    novelId: String(item.id),
                    kind: 'novel',
                    authorId: item.userId ? Number(item.userId) : null,
                    authorName: item.userName || '',
                    isAi: Number(item.aiType ?? 0) >= 2,
                    xRestrict: Number(item.xRestrict ?? 0)
                };
            }
        },
        series: {
            pageSize: 30,
            requestInit: novelPreviewRequestInit,
            apiPath(seriesId, page) { return `/api/pixiv/novel/series/${encodeURIComponent(seriesId)}?page=${page}`; },
            parseUrl(text) {
                const t = String(text || '').trim();
                const s = t.match(/\/novel\/series\/(\d+)/);
                if (s) return {seriesId: Number(s[1])};
                const n = t.match(/\/novel\/show\.php\?[^\s]*?\bid=(\d+)/);
                if (n) return {resolveWorkId: n[1]};
                return null;
            },
            resolveSeriesId: resolveSeriesIdFromNovel,
            typeLabel() { return bt('series.meta.type-novel', '小说系列'); },
            queueId: novelQueueId,
            cardId(idx) { return `series-novel-card-${idx}`; },
            queueSource: 'series-novel',
            render: renderNovelSeriesResults,
            buildQueueMeta(item, seriesOrder, ctx) {
                return {
                    title: item.title || bt('queue.novel-fallback', '小说 {id}', {id: item.id}),
                    novelId: String(item.id),
                    kind: 'novel',
                    authorId: item.userId ? Number(item.userId) : ctx.seriesAuthorId,
                    authorName: item.userName || ctx.seriesAuthorName,
                    isAi: Number(item.aiType ?? 0) >= 2,
                    xRestrict: Number(item.xRestrict ?? 0),
                    tags: Array.isArray(item.tags) ? item.tags : [],
                    readingTimeSeconds: item.readingTimeSeconds ?? null,
                    coverUrl: item.coverUrl || null,
                    uploadTimestamp: item.uploadTimestamp || null,
                    seriesId: ctx.seriesId,
                    seriesOrder,
                    seriesTitle: ctx.seriesTitle,
                    // 始终记录所属系列（合订资格）；是否真正生成合订本，由系列下载完成时的实时「生成合订本」设置决定
                    mergeAfterSeriesId: Number(ctx.seriesId)
                };
            }
        },
        quick: {
            dataSource: {
                id: 'pixiv',
                displayNamespace: 'batch',
                displayI18nKey: 'quick.data-source.pixiv',
                order: 10
            },
            pageSize: QUICK_PAGE_SIZE_NOVEL,
            requestInit: novelPreviewRequestInit,
            account: {
                credentialMissing() { return !cookieHasPhpsessid(); },
                missingHint() {
                    return bt('quick.account.hint-no-cookie',
                        '未检测到登录 Cookie，请先在上方保存含 PHPSESSID 的 Cookie');
                },
                buildRequest() { return {endpoint: '/api/pixiv/me/uid'}; },
                readId(data) { return data && data.uid; }
            },
            buildMyWorksIdsRequest: novelQuickUserIdsRequest,
            buildUserIdsRequest: novelQuickUserIdsRequest,
            buildCardsRequest: novelQuickCardsRequest,
            myWorksTitleKey: 'quick.title.my-novels',
            queueId: novelQueueId,
            gridCardId(idPrefix, idx) { return `${idPrefix}-novel-card-${idx}`; },
            skipThumbnail: true,
            render: renderQuickNovelGrid,
            innerCardHtml: novelQuickInnerCard,
            buildQueueMeta(item) {
                return {
                    title: item.title || '',
                    novelId: String(item.id),
                    kind: 'novel',
                    authorId: item.userId ? Number(item.userId) : null,
                    authorName: item.userName || '',
                    isAi: Number(item.aiType ?? 0) >= 2,
                    xRestrict: Number(item.xRestrict ?? 0),
                    tags: Array.isArray(item.tags) ? item.tags : []
                };
            },
            buildQueueMetaFromId(id) {
                return {novelId: String(id), kind: 'novel'};
            },
            // 快捷获取入口动作（我的小说收藏 / 我的小说）：宿主 quickLoad / quickScheduleSource 据此派发。
            actions: {
                'my-novel-bookmarks-show': {
                    viewType: 'works-list', kind: 'novel', pageSize: QUICK_PAGE_SIZE_NOVEL,
                    sourceType: 'my-bookmarks', scheduleRest: 'show', bookmarkEndpoint: 'novel-bookmarks',
                    buildPageRequest: novelBookmarkPageRequest,
                    load: requireNovelQuickSession(() => loadQuickNovelBookmarks('show', 1)),
                    scheduleSource() {
                        return {
                            sourceType: 'my-bookmarks', type: 'MY_BOOKMARKS', source: {rest: 'show'}, kind: 'novel',
                            label: bt('quick.schedule.source.bookmarks', '我的收藏（{kind}，{rest}）', {
                                kind: bt('schedule.kind.novel', 'novel'),
                                rest: bt('quick.schedule.rest.show', '公开')
                            })
                        };
                    }
                },
                'my-novel-bookmarks-hide': {
                    viewType: 'works-list', kind: 'novel', pageSize: QUICK_PAGE_SIZE_NOVEL,
                    sourceType: 'my-bookmarks', scheduleRest: 'hide', bookmarkEndpoint: 'novel-bookmarks',
                    buildPageRequest: novelBookmarkPageRequest,
                    load: requireNovelQuickSession(() => loadQuickNovelBookmarks('hide', 1)),
                    scheduleSource() {
                        return {
                            sourceType: 'my-bookmarks', type: 'MY_BOOKMARKS', source: {rest: 'hide'}, kind: 'novel',
                            label: bt('quick.schedule.source.bookmarks', '我的收藏（{kind}，{rest}）', {
                                kind: bt('schedule.kind.novel', 'novel'),
                                rest: bt('quick.schedule.rest.hide', '不公开')
                            })
                        };
                    }
                },
                'my-novels': {
                    viewType: 'works-list', kind: 'novel', pageSize: QUICK_PAGE_SIZE_NOVEL,
                    sourceType: 'user-new', allIdsFastPath: true,
                    load: requireNovelQuickSession(() => loadQuickMyWorks(_activationContext.type, 1)),
                    scheduleSource(ctx) {
                        if (!ctx.uid) return null;
                        return {
                            sourceType: 'user-new', type: 'USER_NEW',
                            source: {userId: String(ctx.uid)}, kind: 'novel',
                            label: bt('quick.schedule.source.self', '我自己（账号 {uid}）', {uid: ctx.uid})
                        };
                    }
                }
            }
        }
    }
};

// 向宿主队列引擎注册 novel 作品类型：processSingle 据 item.kind 多态派发 process；renderSlots 据启用情况
// 注入 slots；各取得模式据 acquisition / import / filters / settings 钩子驱动（宿主不再写死小说分支）。
if (window.PixivBatch && window.PixivBatch.queueTypes) {
    window.PixivBatch.queueTypes.registerModule(function (context) {
        _activationContext = context;
        const descriptor = Object.assign({}, NOVEL_DESCRIPTOR, {
            scheduledSse: false,
            scheduledQueueItem(item, ctx) {
                const rawId = String(item.workId != null ? item.workId : (item.id == null ? '' : item.id));
                const sourceType = String(ctx.sourceType || '');
                const source = sourceType === 'search' ? 'search'
                    : sourceType === 'series' ? 'series'
                        : ['user-new', 'user-request', 'my-bookmarks', 'follow-latest', 'collection'].includes(sourceType)
                            ? 'user' : 'schedule';
                return {
                    id: 'n' + rawId,
                    novelId: rawId,
                    kind: context.type,
                    rawTitle: item.title && String(item.title).trim() ? String(item.title) : null,
                    source,
                    translatePhase: item.translatePhase || null,
                    translateElapsed: item.translateElapsedSeconds == null ? 0 : item.translateElapsedSeconds,
                    translateSeriesPending: item.translateSeriesPending == null ? 0 : item.translateSeriesPending
                };
            }
        });
        return {
            descriptor,
            dispose() {
                if (_activationContext === context) _activationContext = null;
                _novelSearchLatestModel = null;
                _novelSearchMounting = false;
                if (_novelSearchVue) {
                    try { _novelSearchVue.app.unmount(); } catch (e) { /* best effort */ }
                    _novelSearchVue = null;
                }
            }
        };
    });
}
})();
