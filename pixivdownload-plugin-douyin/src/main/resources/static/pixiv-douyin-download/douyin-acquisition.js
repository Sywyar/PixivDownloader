'use strict';
(function () {
if (!window.PixivBatch || !window.PixivBatch.queueTypes) return;
window.PixivBatch.queueTypes.registerSubmodule(function (shared) {
const {
    douyinAssertActive, bindDouyinEvent, DOUYIN_PAGE_SIZE, DOUYIN_FAVORITE_FOLDER_SERIES_PREFIX,
    DOUYIN_USER_KIND_WORKS, DOUYIN_USER_KIND_LIKED, DOUYIN_USER_KINDS, DOUYIN_COOKIE_REQUIRED_KEYS,
    DOUYIN_COOKIE_SESSION_KEYS, DOUYIN_COOKIE_SESSION_LABEL, DOUYIN_COOKIE_SUGGESTED_KEYS, douyinStoreGet,
    douyinStoreSet, douyinStoreRemove, douyinCookieFacade, douyinStoredCookieRaw,
    douyinSetStoredCookieRaw, douyinRemoveStoredCookieRaw, douyinCookieRawToHeaderString, douyinCookie,
    douyinCookieInputHeaderString, douyinParseCookieFields, douyinValidateCookie, douyinCookieValidationMessage,
    douyinUpdateCookieStatus, douyinEnsureCookieReady, douyinAcquisitionCredentialHeaders, douyinI18nKey,
    douyinText, formatDouyinSearchStats, douyinExtractUrl, douyinSafeId,
    douyinCancelWorkKey, douyinParseInput, douyinParseUserInput, douyinUserKind,
    douyinQueueId, douyinCardId, DOUYIN_QUEUE_MEDIA_KINDS, DOUYIN_QUEUE_SOURCE_TAGS,
    douyinQueueMediaKind, douyinQueueMediaCount, douyinQueueMeta, douyinQueueTypeData,
    DOUYIN_MAX_SOURCE_RELATIONS, douyinLimitedSourceText, douyinNormalizeSourceRelation, douyinSourceRelationKey,
    douyinMergeSourceRelation, douyinAppendSourceRelation, douyinNormalizeQueueTypeData, douyinMergeQueueTypeData,
    douyinSourceRelationsFingerprint, douyinInputFromQueueItem, douyinCanonicalQueueItemUrl, douyinQueueTags,
    douyinLinkedAbortSignal, douyinFetchJson, processDouyinItem, douyinCardHtml,
    renderDouyinGrid, renderDouyinUserResults, renderDouyinSearchResults, renderDouyinSeriesResults,
    douyinQuickCursors, douyinAssertQuickActionContext, douyinQuickFetchJson, loadQuickDouyinAccount,
    douyinUserWorksPageEndpoint, douyinUserLikedPageEndpoint, douyinUserProfileUrl, douyinUserSourceType,
    douyinDecorateUserItems, douyinFetchUserPage, douyinUserEmptyMessage, douyinFavoriteCollectionsEndpoint,
    douyinFavoriteCollectionWorksEndpoint, douyinFavoriteFolderWorksEndpoint, douyinFavoriteFolderSeriesId, douyinFavoriteFolderId,
    loadQuickDouyinFavoriteCollections, renderQuickDouyinGrid, douyinQuickInnerCard, DOUYIN_SLOTS,
    hydrateDouyinCookieSettings, hydrateDouyinUi
} = shared;
function douyinScheduleSource(sourceType, source, label) {
    return {
        sourceType,
        type: sourceType,
        source: source || {},
        kind: 'douyin',
        workTypes: ['douyin'],
        label: label || ''
    };
}

const DOUYIN_DESCRIPTOR = {
    slots: DOUYIN_SLOTS,
    process: processDouyinItem,
    queueTags: douyinQueueTags,
    mergeQueueTypeData: douyinMergeQueueTypeData,
    canonicalUrl: douyinCanonicalQueueItemUrl,
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
        const resultAttributes = item.resultAttributes && typeof item.resultAttributes === 'object'
            && !Array.isArray(item.resultAttributes) ? item.resultAttributes : {};
        const owned = Object.assign({}, item, presentationAttributes, resultAttributes);
        const sourceType = ctx && ctx.sourceType ? String(ctx.sourceType) : null;
        const sourceOrderValue = owned.sourceOrder == null || String(owned.sourceOrder).trim() === ''
            ? NaN : Number(owned.sourceOrder);
        const mediaCountValue = owned.mediaCount != null ? owned.mediaCount : owned.fileCount;
        return {
            id: rawId,
            kind: 'douyin',
            cancelWorkKey: douyinCancelWorkKey(rawId),
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
            typeData: douyinNormalizeQueueTypeData({
                input: owned.url || owned.pageUrl || rawId,
                url: owned.url || owned.pageUrl || '',
                douyinId: rawId,
                sourceType,
                sourceId: owned.sourceId || null,
                sourceTitle: owned.sourceTitle || '',
                sourceUrl: owned.sourceUrl || null,
                sourceOrder: Number.isSafeInteger(sourceOrderValue) ? sourceOrderValue : null,
                mediaKind: douyinQueueMediaKind(owned),
                mediaCount: douyinQueueMediaCount({mediaCount: mediaCountValue})
            })
        };
    },
    cookie: {
        parseInput: douyinParseInput,
        validate: douyinValidateCookie
    },
    import: {
        dataSource: {
            id: 'douyin',
            displayNamespace: 'douyin',
            displayI18nKey: 'source.douyin',
            order: 20
        },
        sectionType: 'douyin',
        matchUrl(line) {
            const parsed = douyinParseInput(line);
            return parsed && ['single', 'short', 'series', 'user', 'music'].includes(parsed.kind) ? parsed : null;
        },
        buildItem(match, title, _line) {
            const parsed = match && match.id ? match : douyinParseInput(String(match || ''));
            if (!parsed) return null;
            const displayId = parsed.workId || parsed.seriesId || parsed.musicId || parsed.id;
            return {
                id: douyinQueueId(parsed),
                douyinId: displayId,
                kind: 'douyin',
                cancelWorkKey: douyinCancelWorkKey(displayId),
                url: parsed.url,
                title: title || douyinText('queue.fallback', 'Douyin {id}', {id: displayId}),
                typeData: douyinNormalizeQueueTypeData({
                    input: parsed.url,
                    url: parsed.url,
                    douyinId: displayId,
                    sourceKind: parsed.kind,
                    seriesId: parsed.seriesId || null,
                    seriesTitle: '',
                    sourceType: parsed.kind === 'series' ? 'douyin.collection'
                        : parsed.kind === 'user' ? 'douyin.user'
                            : parsed.kind === 'music' ? 'douyin.music' : 'douyin.single',
                    sourceId: displayId,
                    sourceTitle: title || '',
                    sourceUrl: parsed.url,
                    sourceOrder: null,
                    mediaKind: parsed.mediaKindHint || null,
                    mediaCount: null
                })
            };
        },
        source: 'single-import-douyin'
    },
    acquisition: {
        user: {
            dataSource: {
                id: 'douyin',
                displayNamespace: 'douyin',
                displayI18nKey: 'source.douyin',
                order: 20
            },
            pageSize: DOUYIN_PAGE_SIZE,
            initialCursor: '0',
            requestInit() {
                return {credentials: 'same-origin', headers: douyinAcquisitionCredentialHeaders()};
            },
            variants: [{
                id: 'douyin-user-liked',
                labelNamespace: 'douyin',
                labelI18nKey: 'user.kind.liked',
                label: '喜欢的作品'
            }],
            accepts(selection) { return DOUYIN_USER_KINDS.has(String(selection)); },
            parseInput: douyinParseUserInput,
            profileUrl: douyinUserProfileUrl,
            fetchMeta() { return Promise.resolve(null); },
            fetchPage: douyinFetchUserPage,
            emptyMessage: douyinUserEmptyMessage,
            queueId: douyinQueueId,
            cardId(idx) { return douyinCardId('user', idx); },
            render: renderDouyinUserResults,
            buildQueueMeta(item, ctx) {
                const kind = douyinUserKind({
                    variant: item && item.douyinUserVariant ? item.douyinUserVariant : ctx.variant
                });
                const sourceType = item && item.sourceType ? item.sourceType : douyinUserSourceType(kind);
                return douyinQueueMeta(Object.assign({}, item, {
                    sourceType,
                    sourceId: item && item.sourceId ? item.sourceId : String(ctx.userId),
                    sourceTitle: item && item.sourceTitle
                        ? item.sourceTitle : (ctx.username || String(ctx.userId)),
                    sourceUrl: item && Object.prototype.hasOwnProperty.call(item, 'sourceUrl')
                        ? item.sourceUrl : douyinUserProfileUrl(ctx.userId)
                }));
            }
        },
        search: {
            dataSource: {
                id: 'douyin',
                displayNamespace: 'douyin',
                displayI18nKey: 'source.douyin',
                order: 20
            },
            pageSize: DOUYIN_PAGE_SIZE,
            requestInit() {
                return {credentials: 'same-origin', headers: douyinAcquisitionCredentialHeaders()};
            },
            buildRequest(ctx) {
                return {
                    endpoint: '/api/douyin/search',
                    params: {word: ctx.word, page: ctx.page, pageSize: DOUYIN_PAGE_SIZE},
                    premiumOrder: false,
                    clientFilter: 0
                };
            },
            buildRangeRequest(ctx) {
                return {
                    endpoint: '/api/douyin/search/range',
                    params: {
                        word: ctx.word,
                        startPage: ctx.startPage,
                        endPage: ctx.endPage,
                        pageSize: DOUYIN_PAGE_SIZE
                    }
                };
            },
            formatStats: formatDouyinSearchStats,
            queueId: douyinQueueId,
            queueSource: 'search-douyin',
            emptyResultsLabel() { return douyinText('search.empty', 'No Douyin search results'); },
            render: renderDouyinSearchResults,
            buildQueueMeta(item) {
                const word = (document.getElementById('search-word') || {}).value || '';
                return douyinQueueMeta(Object.assign({}, item, {
                    sourceType: 'douyin.search', sourceId: word,
                    sourceTitle: word, sourceUrl: `https://www.douyin.com/search/${encodeURIComponent(word)}`
                }));
            },
            controls: {searchMode: false, order: false, contentFilter: false, batchRange: true, r18Blur: false}
        },
        series: {
            dataSource: {
                id: 'douyin',
                displayNamespace: 'douyin',
                displayI18nKey: 'source.douyin',
                order: 20
            },
            browser: {
                initialCursor: '0',
                pageSize: DOUYIN_PAGE_SIZE,
                title() {
                    return douyinText('series.browser.favorite-folders', 'My favorite folders');
                },
                loadingLabel() {
                    return douyinText('series.browser.favorite-folders.loading', 'Loading favorite folders...');
                },
                emptyLabel() {
                    return douyinText('series.browser.favorite-folders.empty', 'No favorite folders are available');
                },
                buildPageRequest(context = {}) {
                    const cursor = context.cursor == null ? '0' : String(context.cursor);
                    const pageSize = Number(context.limit) || DOUYIN_PAGE_SIZE;
                    return {
                        endpoint: '/api/douyin/me/favorite-folders',
                        params: {cursor, pageSize}
                    };
                },
                readPage(data) {
                    return {
                        items: Array.isArray(data && data.folders) ? data.folders : [],
                        total: Number(data && data.total) || 0,
                        nextCursor: data && data.nextCursor,
                        hasMore: !!(data && data.hasMore)
                    };
                },
                itemId(item) { return item && item.id; },
                itemLabel(item) {
                    const id = item && item.id ? String(item.id) : '';
                    const title = item && item.title ? String(item.title) : id;
                    return douyinText('series.browser.favorite-folders.item', '{title} (ID {id})', {title, id});
                },
                select(item) {
                    const id = item && item.id ? String(item.id) : '';
                    return {
                        seriesId: douyinFavoriteFolderSeriesId(id),
                        seriesTitle: item && item.title ? String(item.title) : id
                    };
                }
            },
            pageSize: DOUYIN_PAGE_SIZE,
            requestInit() {
                return {credentials: 'same-origin', headers: douyinAcquisitionCredentialHeaders()};
            },
            paginationMode(seriesId) {
                return douyinFavoriteFolderId(seriesId) ? 'cursor' : 'page';
            },
            initialCursor(seriesId) {
                return douyinFavoriteFolderId(seriesId) ? '0' : null;
            },
            apiPath(seriesId, page, context = {}) {
                const favoriteFolderId = douyinFavoriteFolderId(seriesId);
                if (favoriteFolderId) {
                    return douyinFavoriteFolderWorksEndpoint(favoriteFolderId, context);
                }
                if (String(seriesId).startsWith('music:')) {
                    const musicId = String(seriesId).substring('music:'.length);
                    return `/api/douyin/music/${encodeURIComponent(musicId)}?page=${page}&pageSize=${DOUYIN_PAGE_SIZE}`;
                }
                return `/api/douyin/series/${encodeURIComponent(seriesId)}?page=${page}&pageSize=${DOUYIN_PAGE_SIZE}`;
            },
            normalizePage(data, context = {}) {
                const favoriteFolderId = douyinFavoriteFolderId(context.seriesId);
                if (!favoriteFolderId) return data;
                const total = Number(data && data.total);
                return {
                    series: {
                        title: context.seriesTitle || favoriteFolderId,
                        total: Number.isFinite(total) && total >= 0 ? Math.floor(total) : 0
                    },
                    total: Number.isFinite(total) && total >= 0 ? Math.floor(total) : 0,
                    items: Array.isArray(data && data.works) ? data.works : [],
                    page: Math.max(1, Number(context.page) || 1),
                    isLastPage: !(data && data.hasMore),
                    nextCursor: data && data.nextCursor,
                    hasMore: !!(data && data.hasMore)
                };
            },
            parseUrl(text) {
                const parsed = douyinParseInput(text);
                if (parsed && parsed.kind === 'series') return {seriesId: parsed.seriesId};
                if (parsed && parsed.kind === 'music') return {seriesId: `music:${parsed.musicId}`};
                return null;
            },
            typeLabel(context = {}) {
                return douyinFavoriteFolderId(context.seriesId)
                    ? douyinText('series.type.favorite-folder', 'Douyin favorite folder')
                    : douyinText('series.type', 'Douyin collection');
            },
            queueId: douyinQueueId,
            cardId(idx) { return douyinCardId('series', idx); },
            queueSource: 'series-douyin',
            render: renderDouyinSeriesResults,
            buildQueueMeta(item, seriesOrder, ctx) {
                const favoriteFolderId = douyinFavoriteFolderId(ctx.seriesId);
                const meta = Object.assign(douyinQueueMeta(item), {
                    seriesId: favoriteFolderId ? null : ctx.seriesId,
                    seriesOrder,
                    seriesTitle: favoriteFolderId ? null : ctx.seriesTitle
                });
                meta.typeData = Object.assign({}, meta.typeData, {
                    seriesId: (favoriteFolderId || String(ctx.seriesId).startsWith('music:')) ? null : ctx.seriesId,
                    seriesTitle: favoriteFolderId ? '' : ctx.seriesTitle,
                    sourceType: favoriteFolderId ? 'douyin.account.favorite-folder'
                        : String(ctx.seriesId).startsWith('music:') ? 'douyin.music' : 'douyin.collection',
                    sourceId: favoriteFolderId || String(ctx.seriesId).replace(/^music:/, ''),
                    sourceTitle: ctx.seriesTitle || '',
                    sourceUrl: favoriteFolderId
                        ? null
                        : String(ctx.seriesId).startsWith('music:')
                        ? `https://www.douyin.com/music/${encodeURIComponent(String(ctx.seriesId).substring(6))}`
                        : `https://www.douyin.com/mix/${encodeURIComponent(String(ctx.seriesId))}`,
                    sourceOrder: seriesOrder
                });
                return meta;
            }
        },
        quick: {
            dataSource: {
                id: 'douyin',
                displayNamespace: 'douyin',
                displayI18nKey: 'source.douyin',
                order: 20
            },
            pageSize: DOUYIN_PAGE_SIZE,
            initialCursor: '0',
            skipThumbnail: true,
            requestInit() {
                return {credentials: 'same-origin', headers: douyinAcquisitionCredentialHeaders()};
            },
            account: {
                credentialMissing() { return !douyinValidateCookie(douyinCookie()).ok; },
                missingHint() { return douyinText('quick.cookie-required', 'Save a valid Douyin Cookie first'); },
                buildRequest() { return {endpoint: '/api/douyin/me'}; },
                readId(data) { return data && data.accountKey; }
            },
            buildMyWorksIdsRequest() { return {endpoint: '/api/douyin/me/works/ids'}; },
            buildUserPageRequest(userId, context) {
                return {endpoint: douyinUserWorksPageEndpoint(userId, context)};
            },
            buildCardsRequest(userId, ids) {
                const params = new URLSearchParams();
                (ids || []).forEach(id => params.append('ids', id));
                return {endpoint: `/api/douyin/user/${encodeURIComponent(userId)}/works/cards?${params}`};
            },
            myWorksTitleKey: 'douyin:quick.own-works',
            queueId: douyinQueueId,
            gridCardId(idPrefix, idx) { return douyinCardId(idPrefix, idx); },
            render: renderQuickDouyinGrid,
            innerCardHtml: douyinQuickInnerCard,
            buildQueueMeta(item, context) {
                const ctx = context && typeof context === 'object' ? context : {};
                const inner = ctx.inner && typeof ctx.inner === 'object' ? ctx.inner : null;
                const action = ctx.action || quickState.action || '';
                const ownWorksAccountId = ctx.accountOwner === 'douyin' && ctx.accountId
                    ? String(ctx.accountId) : 'own-works';
                const source = inner && inner.type === 'following-user' && inner.userId
                    ? ['douyin.user', inner.userId, inner.name || inner.userId,
                        'https://www.douyin.com/user/' + encodeURIComponent(inner.userId)]
                    : inner && inner.type === 'collection' && inner.id
                        ? ['douyin.account.favorite-collection', inner.id, inner.name || inner.id,
                            '/api/douyin/me/favorite-collections/' + encodeURIComponent(inner.id) + '/works']
                        : action === 'douyin-liked'
                    ? ['douyin.account.liked-works', 'liked']
                    : action === 'douyin-favorites'
                        ? ['douyin.account.favorite-works', 'favorites']
                        : action === 'douyin-favorite-collections'
                            ? ['douyin.account.favorite-collection', item.collectionId || 'collection']
                            : ['douyin.account.own-works', ownWorksAccountId];
                return douyinQueueMeta(Object.assign({}, item, {
                    sourceType: item.sourceType || source[0],
                    sourceId: item.sourceId || source[1],
                    sourceTitle: item.sourceTitle || source[2] || '',
                    sourceUrl: item.sourceUrl || source[3] || null,
                    sourceOrder: item.sourceOrder
                }));
            },
            buildQueueMetaFromId(id, context) {
                const ctx = context && typeof context === 'object' ? context : {};
                const sourceId = ctx.accountOwner === 'douyin' && ctx.accountId
                    ? String(ctx.accountId) : 'own-works';
                return {
                    kind: 'douyin',
                    cancelWorkKey: douyinCancelWorkKey(id),
                    typeData: douyinNormalizeQueueTypeData({
                        input: String(id), douyinId: String(id),
                        sourceType: 'douyin.account.own-works', sourceId
                    })
                };
            },
            actions: {
                'douyin-own-works': {
                    labelNamespace: 'douyin', labelI18nKey: 'quick.own-works',
                    label: '我的抖音作品', iconKey: 'image',
                    viewType: 'works-list', kind: 'douyin', sourceType: 'douyin.account.own-works',
                    allIdsFastPath: true,
                    load(_action, context) {
                        douyinAssertQuickActionContext(context);
                        return loadQuickMyWorks('douyin', 1, context);
                    },
                    scheduleSource() {
                        return douyinScheduleSource('douyin.account.own-works', {},
                            douyinText('quick.own-works', 'My Douyin works'));
                    }
                },
                'douyin-liked': {
                    labelNamespace: 'douyin', labelI18nKey: 'quick.liked',
                    label: '喜欢的作品', iconKey: 'bookmark',
                    viewType: 'works-list', kind: 'douyin', sourceType: 'douyin.account.liked-works',
                    cursorPaging: true, initialCursor: '0',
                    buildPageRequest(context = {}) {
                        const cursor = context.cursor == null ? '0' : String(context.cursor);
                        const pageSize = Number(context.limit) || DOUYIN_PAGE_SIZE;
                        return {endpoint: `/api/douyin/me/liked?cursor=${encodeURIComponent(cursor)}&pageSize=${pageSize}`};
                    },
                    load(_action, context) {
                        return loadQuickDouyinAccount('liked', 'douyin.account.liked-works',
                            'quick.liked', 'Liked works', 1, context);
                    },
                    scheduleSource() {
                        return douyinScheduleSource('douyin.account.liked-works', {},
                            douyinText('quick.liked', 'Liked works'));
                    }
                },
                'douyin-favorites': {
                    labelNamespace: 'douyin', labelI18nKey: 'quick.favorites',
                    label: '收藏的作品', iconKey: 'bookmark',
                    viewType: 'works-list', kind: 'douyin', sourceType: 'douyin.account.favorite-works',
                    cursorPaging: true, initialCursor: '0',
                    buildPageRequest(context = {}) {
                        const cursor = context.cursor == null ? '0' : String(context.cursor);
                        const pageSize = Number(context.limit) || DOUYIN_PAGE_SIZE;
                        return {endpoint: `/api/douyin/me/favorites?cursor=${encodeURIComponent(cursor)}&pageSize=${pageSize}`};
                    },
                    load(_action, context) {
                        return loadQuickDouyinAccount('favorites', 'douyin.account.favorite-works',
                            'quick.favorites', 'Favorite works', 1, context);
                    },
                    scheduleSource() {
                        return douyinScheduleSource('douyin.account.favorite-works', {},
                            douyinText('quick.favorites', 'Favorite works'));
                    }
                },
                'douyin-favorite-collections': {
                    labelNamespace: 'douyin', labelI18nKey: 'quick.favorite-collections',
                    label: '收藏夹', iconKey: 'folder',
                    viewType: 'collection-list', kind: 'douyin', sourceType: 'douyin.account.favorite-collection',
                    initialCursor: '0',
                    buildPageRequest(context = {}) {
                        const cursor = context.cursor == null ? '0' : String(context.cursor);
                        const pageSize = Number(context.limit) || DOUYIN_PAGE_SIZE;
                        return {endpoint: douyinFavoriteCollectionsEndpoint(cursor, pageSize)};
                    },
                    buildCollectionWorksPageRequest(collectionId, context) {
                        return {endpoint: douyinFavoriteCollectionWorksEndpoint(collectionId, context)};
                    },
                    load(_action, context) { return loadQuickDouyinFavoriteCollections(1, context); },
                    scheduleSource(context) {
                        const inner = context && context.inner;
                        if (!inner || inner.type !== 'collection' || !inner.id) return null;
                        return douyinScheduleSource('douyin.account.favorite-collection', {
                            collectionId: String(inner.id)
                        }, douyinText('schedule.quick.favorite-collection',
                            'Favorite collection {name} (ID {id})', {
                                name: inner.name || inner.id,
                                id: inner.id
                            }));
                    }
                }
            }
        }
    }
};

shared.douyinDescriptor = DOUYIN_DESCRIPTOR;
Object.assign(shared, {
    douyinScheduleSource, DOUYIN_DESCRIPTOR
});
});
})();
