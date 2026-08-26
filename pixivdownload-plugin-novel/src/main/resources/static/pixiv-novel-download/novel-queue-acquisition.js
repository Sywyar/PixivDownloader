'use strict';
(function () {
if (!window.PixivBatch || !window.PixivBatch.queueTypes) return;
window.PixivBatch.queueTypes.registerSubmodule(function (shared) {
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

function formatNovelSearchStats(metric, stats) {
    const count = Number(stats && stats.count);
    const displayCount = (Number.isFinite(count) ? Math.max(0, count) : 0).toLocaleString();
    if (metric === 'total') {
        return bt('novel:batch.search.summary.total', '小说总数 {count} 部', {count: displayCount});
    }
    if (metric === 'returned') {
        return bt('novel:batch.search.summary.returned', '小说返回 {count} 部', {count: displayCount});
    }
    if (metric === 'batch-fetched') {
        return bt('novel:batch.search.summary.fetched', '已抓取去重 {count} 部小说', {count: displayCount});
    }
    if (metric === 'current-page') {
        return bt('novel:batch.search.summary.current-page', '小说当前页 {count} 部', {count: displayCount});
    }
    return '';
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
    if (shared.context && typeof shared.context.assertActive === 'function') {
        shared.context.assertActive();
    }
}

function novelHookSignal(hookContext) {
    return hookContext && hookContext.signal
        ? hookContext.signal
        : (shared.context && shared.context.signal);
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


function novelQueueSourceType(context) {
    const ctx = context && typeof context === 'object' ? context : {};
    const inner = ctx.inner && typeof ctx.inner === 'object' ? ctx.inner : null;
    if (inner && inner.type === 'collection') return 'collection';
    if (inner && inner.type === 'following-user') return 'user-new';
    return {
        'my-novel-bookmarks-show': 'my-bookmarks',
        'my-novel-bookmarks-hide': 'my-bookmarks',
        'my-novels': 'user-new'
    }[String(ctx.action || '')] || null;
}

function novelQueueTypeData(context) {
    const sourceType = novelQueueSourceType(context);
    return sourceType ? {sourceType} : null;
}

function novelQueueTags(item) {
    const data = item && item.typeData && typeof item.typeData === 'object'
        ? item.typeData : {};
    const tags = [{
        id: 'media.novel',
        label: bt('novel:batch.kind.novel', '小说')
    }];
    if (data.sourceType === 'collection') {
        tags.push({id: 'origin.collection', label: bt('queue.tag.collection', '珍藏集')});
    } else if (data.sourceType === 'my-bookmarks') {
        tags.push({id: 'origin.bookmark', label: bt('queue.tag.bookmark', '收藏')});
    }
    if (item && item.isAi === true) {
        tags.push({id: 'attribute.ai', label: bt('queue.tag.ai', 'AI')});
    }
    return tags;
}

function novelLiveStatusCount(value) {
    const count = Number(value);
    return Number.isSafeInteger(count) && count >= 0 ? count : 0;
}

// 小说插件拥有 phase 语义与文案；宿主只接收这项同步纯文本贡献，不识别翻译阶段。
function novelQueueLiveStatus(item) {
    const status = item && item.liveStatus && typeof item.liveStatus === 'object'
        && !Array.isArray(item.liveStatus) ? item.liveStatus : null;
    if (!status) return null;
    const phase = String(status.phase || '').trim().toUpperCase();
    const label = bt('novel:queue.translate.label', 'AI 翻译');
    switch (phase) {
        case 'QUEUED':
            return {
                label,
                message: bt('novel:queue.message.translate-waiting', '排队等待翻译...'),
                tone: 'info'
            };
        case 'WAITING_SERIES':
            return {
                label,
                message: bt(
                    'novel:queue.message.translate-wait-series',
                    '等待前系列小说翻译完成，还有 {n} 个',
                    {n: novelLiveStatusCount(status.seriesPending)}
                ),
                tone: 'warning'
            };
        case 'RESOLVING':
            return {
                label,
                message: bt('novel:queue.message.translate-resolving', '识别目标语言中...'),
                tone: 'info'
            };
        case 'TRANSLATING':
            return {
                label,
                message: bt(
                    'novel:queue.message.translating',
                    'AI 翻译中（{sec}s）',
                    {sec: novelLiveStatusCount(status.elapsedSeconds)}
                ),
                tone: 'info'
            };
        case 'MERGING':
            return {
                label,
                message: bt('novel:queue.message.translate-merging', '生成译文合订本中...'),
                tone: 'info'
            };
        case 'SAME_LANGUAGE':
            return {
                label,
                message: bt(
                    'novel:queue.message.translate-same-lang',
                    '完成（源语言与目标一致，已跳过）'
                ),
                tone: 'success'
            };
        case 'DONE':
            return {
                label,
                message: bt('novel:queue.message.translate-done', '完成（已翻译）'),
                tone: 'success'
            };
        case 'FAILED':
            return {
                label,
                message: bt('novel:queue.message.translate-failed', '完成（翻译失败）'),
                tone: 'error'
            };
        default:
            return null;
    }
}

function novelCanonicalQueueItemUrl(item) {
    let novelId = item && item.novelId != null ? String(item.novelId).trim() : '';
    if (!novelId && item && item.id != null) {
        novelId = String(item.id).trim().replace(/^n/, '');
    }
    return /^\d+$/.test(novelId)
        ? `https://www.pixiv.net/novel/show.php?id=${encodeURIComponent(novelId)}`
        : '';
}


function buildNovelDescriptor(context) {
    const {
        NOVEL_SLOTS, processNovelItem, renderNovelSearchResults, syncNovelSearchQueueState,
        renderNovelUserResults, renderNovelSeriesResults, renderQuickNovelGrid,
        novelQuickInnerCard, loadQuickNovelBookmarks
    } = shared;
const NOVEL_DESCRIPTOR = {
    slots: NOVEL_SLOTS,
    process: processNovelItem,
    queueTags: novelQueueTags,
    queueLiveStatus: novelQueueLiveStatus,
    canonicalUrl: novelCanonicalQueueItemUrl,
    // 批量导入单作品：小说链接 / `novel:` 区段头 / 裸 id 的解析与入队项构造。
    import: {
        dataSource: {
            id: 'pixiv',
            displayNamespace: 'batch',
            displayI18nKey: 'data-source.pixiv',
            order: 10
        },
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
            dataSource: {
                id: 'pixiv',
                displayNamespace: 'batch',
                displayI18nKey: 'data-source.pixiv',
                order: 10
            },
            pageSize: 30,
            requestInit: novelPreviewRequestInit,
            accepts(selection) { return selection === 'novel'; },
            parseInput: parseNovelUserInput,
            profileUrl(userId) { return `https://www.pixiv.net/users/${encodeURIComponent(userId)}`; },
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
                    typeData: novelQueueTypeData(ctx),
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
                    typeData: novelQueueTypeData(ctx),
                    authorId: Number(ctx.userId),
                    authorName: ctx.username || ctx.userId
                };
            }
        },
        search: {
            dataSource: {
                id: 'pixiv',
                displayNamespace: 'batch',
                displayI18nKey: 'data-source.pixiv',
                order: 10
            },
            pageSize: 24,
            requestInit: novelPreviewRequestInit,
            buildRequest: novelBuildSearchRequest,
            buildRangeRequest: novelBuildRangeRequest,
            formatStats: formatNovelSearchStats,
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
            dataSource: {
                id: 'pixiv',
                displayNamespace: 'batch',
                displayI18nKey: 'data-source.pixiv',
                order: 10
            },
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
                displayI18nKey: 'data-source.pixiv',
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
            buildQueueMeta(item, ctx) {
                return {
                    title: item.title || '',
                    novelId: String(item.id),
                    kind: 'novel',
                    typeData: novelQueueTypeData(ctx),
                    authorId: item.userId ? Number(item.userId) : null,
                    authorName: item.userName || '',
                    isAi: Number(item.aiType ?? 0) >= 2,
                    xRestrict: Number(item.xRestrict ?? 0),
                    tags: Array.isArray(item.tags) ? item.tags : []
                };
            },
            buildQueueMetaFromId(id, ctx) {
                return {novelId: String(id), kind: 'novel', typeData: novelQueueTypeData(ctx)};
            },
            // 快捷获取入口动作（我的小说收藏 / 我的小说）：宿主 quickLoad / quickScheduleSource 据此派发。
            actions: {
                'my-novel-bookmarks-show': {
                    labelNamespace: 'batch', labelI18nKey: 'quick.action.novel-bookmarks-show',
                    label: '我的收藏（小说，公开）', iconKey: 'bookmark',
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
                    labelNamespace: 'batch', labelI18nKey: 'quick.action.novel-bookmarks-hide',
                    label: '我的收藏（小说，不公开）', iconKey: 'bookmark',
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
                    labelNamespace: 'batch', labelI18nKey: 'quick.action.my-novels',
                    label: '我自己的作品（小说）', iconKey: 'image',
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
        const descriptor = Object.assign({}, NOVEL_DESCRIPTOR, {
            scheduledSse: false,
            scheduledQueueItem(item, ctx) {
                const rawId = String(item.workId != null ? item.workId : (item.id == null ? '' : item.id));
                const presentation = item.presentation && typeof item.presentation === 'object'
                    && !Array.isArray(item.presentation) ? item.presentation : {};
                const presentationAttributes = item.presentationAttributes
                    && typeof item.presentationAttributes === 'object'
                    && !Array.isArray(item.presentationAttributes)
                    ? item.presentationAttributes
                    : (presentation.attributes && typeof presentation.attributes === 'object'
                        && !Array.isArray(presentation.attributes) ? presentation.attributes : {});
                const resultAttributes = item.resultAttributes
                    && typeof item.resultAttributes === 'object' && !Array.isArray(item.resultAttributes)
                    ? item.resultAttributes : {};
                const xRestrictRaw = resultAttributes.xRestrict != null
                    ? resultAttributes.xRestrict
                    : (presentationAttributes.xRestrict != null
                        ? presentationAttributes.xRestrict : item.xRestrict);
                const xRestrictNumber = Number(xRestrictRaw);
                const aiRaw = resultAttributes.ai != null ? resultAttributes.ai
                    : (resultAttributes.isAi != null ? resultAttributes.isAi
                        : (presentationAttributes.ai != null ? presentationAttributes.ai
                            : (presentationAttributes.isAi != null
                                ? presentationAttributes.isAi
                                : (item.ai != null ? item.ai : item.isAi))));
                const normalizedAi = typeof aiRaw === 'string' ? aiRaw.trim().toLowerCase() : aiRaw;
                const sourceType = String(ctx && ctx.sourceType || '');
                const source = sourceType === 'search' ? 'search'
                    : sourceType === 'series' ? 'series'
                        : ['user-new', 'user-request', 'my-bookmarks', 'follow-latest', 'collection'].includes(sourceType)
                            ? 'user' : 'schedule';
                return {
                    id: 'n' + rawId,
                    novelId: rawId,
                    kind: context.type,
                    rawTitle: item.title && String(item.title).trim()
                        ? String(item.title)
                        : (presentation.title && String(presentation.title).trim()
                            ? String(presentation.title)
                            : (resultAttributes.title && String(resultAttributes.title).trim()
                                ? String(resultAttributes.title) : null)),
                    authorName: item.author && String(item.author).trim()
                        ? String(item.author)
                        : (presentation.author && String(presentation.author).trim()
                            ? String(presentation.author) : ''),
                    thumbnailReference: item.thumbnailReference
                        || presentation.thumbnailReference || null,
                    xRestrict: Number.isInteger(xRestrictNumber) && xRestrictNumber >= 0
                        ? xRestrictNumber : null,
                    isAi: normalizedAi === true || normalizedAi === 'true'
                        || normalizedAi === 1 || normalizedAi === '1',
                    source,
                    typeData: sourceType ? {sourceType} : null,
                    liveStatus: item.liveStatus && typeof item.liveStatus === 'object'
                        && !Array.isArray(item.liveStatus)
                        ? Object.assign({}, item.liveStatus) : null
                };
            }
        });
    return descriptor;
}
Object.assign(shared, {
    novelAcquisitionCredentialHeaders, novelPreviewRequestInit, novelBuildSearchRequest, novelBuildRangeRequest,
    formatNovelSearchStats, requireNovelQuickSession, assertNovelActivation, novelHookSignal,
    novelPreviewJson, parseNovelUserInput, novelQuickUserIdsRequest, novelQuickCardsRequest,
    novelBookmarkPageRequest, getNovelUserMeta, novelQueueId, getNovelBookmarkCountForSearch,
    getUserNovels, resolveSeriesIdFromNovel, novelQueueSourceType, novelQueueTypeData,
    novelQueueTags, novelLiveStatusCount, novelQueueLiveStatus, novelCanonicalQueueItemUrl,
    buildNovelDescriptor
});
});
})();
