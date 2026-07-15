(function () {
    'use strict';

    const queueTypes = window.PixivBatch && window.PixivBatch.queueTypes;
    if (!queueTypes) return;

    queueTypes.registerModule(function (context) {
        const type = context.type;

        function acquisitionCredentialHeaders() {
            const credential = getCookie();
            return credential ? {'X-Acquisition-Credential': credential} : {};
        }

        function previewRequestInit() {
            return {credentials: 'same-origin', headers: acquisitionCredentialHeaders()};
        }

        function thumbnailEndpoint(url) {
            return `/api/pixiv/thumbnail-proxy?${new URLSearchParams({url: String(url || '')})}`;
        }

        function pixivUserIdsRequest(userId, endpoint) {
            return {endpoint: `/api/pixiv/user/${encodeURIComponent(userId)}/${endpoint}`};
        }

        function pixivUserCardsRequest(userId, endpoint, ids) {
            return {
                endpoint: `/api/pixiv/user/${encodeURIComponent(userId)}/${endpoint}`,
                params: {ids: (ids || []).map(String)}
            };
        }

        function pixivMePageRequest(endpoint, params) {
            return {endpoint: `/api/pixiv/me/${endpoint}`, params: params || {}};
        }

        function hookSignal(hookContext) {
            return hookContext && hookContext.signal ? hookContext.signal : context.signal;
        }

        async function pixivPreviewJson(path, hookContext) {
            context.assertActive();
            const response = await fetch(`${BASE}${path}`, {
                credentials: 'same-origin',
                headers: acquisitionCredentialHeaders(),
                signal: hookSignal(hookContext)
            });
            const data = await response.json().catch(() => ({}));
            context.assertActive();
            if (!response.ok || data.error) {
                throw new Error(data.error || data.message || `HTTP ${response.status}`);
            }
            return data;
        }

        function parsePixivUserInput(raw) {
            const value = String(raw || '').trim();
            if (!value) return '';
            if (/^\d+$/.test(value)) return value;
            const match = value.match(/\/users\/(\d+)/);
            return match ? match[1] : '';
        }

        async function fetchPixivUserMeta(userId, hookContext) {
            const data = await pixivPreviewJson(
                `/api/pixiv/user/${encodeURIComponent(userId)}/meta`, hookContext);
            return data.name || '';
        }

        async function fetchPixivUserArtworks(userId, hookContext) {
            const data = await pixivPreviewJson(
                `/api/pixiv/user/${encodeURIComponent(userId)}/artworks`, hookContext);
            return data.ids || [];
        }

        async function fetchPixivUserRequests(userId, hookContext) {
            const data = await pixivPreviewJson(
                `/api/pixiv/user/${encodeURIComponent(userId)}/request-artworks`, hookContext);
            return data.ids || [];
        }

        function parsePixivSeriesUrl(text) {
            const value = String(text || '').trim();
            const series = value.match(/\/user\/(\d+)\/series\/(\d+)/);
            if (series) return {seriesId: Number(series[2])};
            const artwork = value.match(/\/artworks\/(\d+)/);
            if (artwork) return {resolveWorkId: artwork[1]};
            if (/^\d+$/.test(value)) return {seriesId: Number(value)};
            return null;
        }

        async function resolvePixivSeriesId(artworkId, hookContext) {
            const meta = await pixivPreviewJson(
                `/api/pixiv/artwork/${encodeURIComponent(artworkId)}/meta`, hookContext);
            if (!meta.seriesId) {
                throw new Error(bt('status.series-artwork-no-series', '该作品不属于任何漫画系列'));
            }
            return Number(meta.seriesId);
        }

        function buildSearchRequest(ctx) {
            const uiMode = String(ctx.uiMode || 'all');
            const r18Family = ['r18', 'r18g', 'r18plus'].includes(uiMode);
            return {
                endpoint: '/api/pixiv/search',
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

        function buildRangeRequest(ctx) {
            const request = buildSearchRequest(ctx);
            request.endpoint = '/api/pixiv/search/range';
            request.params = Object.assign({}, request.params, {
                startPage: ctx.startPage,
                endPage: ctx.endPage
            });
            delete request.params.page;
            return request;
        }

        function formatPixivSearchStats(metric, stats) {
            const count = Number(stats && stats.count);
            const displayCount = (Number.isFinite(count) ? Math.max(0, count) : 0).toLocaleString();
            if (metric === 'total') {
                return bt('search.summary.pixiv-total', 'Pixiv 总数 {count}', {count: displayCount});
            }
            if (metric === 'returned') {
                return bt('search.summary.pixiv-returned', 'Pixiv 返回 {count} 个', {count: displayCount});
            }
            if (metric === 'batch-fetched') {
                return bt('search.batch.summary.pixiv-fetched', 'Pixiv 已抓取去重 {count} 个', {count: displayCount});
            }
            if (metric === 'current-page') {
                return bt('search.summary.pixiv-current-page-results',
                    'Pixiv 当前页 {count} 个结果', {count: displayCount});
            }
            return '';
        }

        function requirePixivQuickSession(loader) {
            return function () {
                if (!cookieHasPhpsessid()) {
                    throw new Error(bt('quick.error.no-cookie', '请先保存含 PHPSESSID 的 Cookie'));
                }
                return loader.apply(this, arguments);
            };
        }

        function artworkQueueMeta(item) {
            return {
                title: item.title || '',
                kind: type,
                authorId: item.userId ? Number(item.userId) : null,
                authorName: item.userName || '',
                isAi: Number(item.aiType ?? 0) >= 2,
                xRestrict: Number(item.xRestrict ?? 0),
                tags: Array.isArray(item.tags) ? item.tags : []
            };
        }

        function userQueueMeta(item, ctx) {
            const meta = artworkQueueMeta(item);
            meta.authorId = item.userId ? Number(item.userId) : Number(ctx.userId);
            meta.authorName = item.userName || ctx.username || ctx.userId;
            return meta;
        }

        function seriesQueueMeta(item, seriesOrder, ctx) {
            const meta = artworkQueueMeta(item);
            meta.authorId = item.userId || ctx.seriesAuthorId;
            meta.authorName = item.userName || ctx.seriesAuthorName;
            meta.seriesId = ctx.seriesId;
            meta.seriesOrder = seriesOrder;
            meta.seriesTitle = ctx.seriesTitle;
            return meta;
        }

        function renderPixivUserResults(area, ctx) {
            const items = ctx.items;
            const inQueue = ctx.inQueue;
            area.innerHTML = ctx.summaryHtml + `<div class="search-grid">
      ${items.map((item, idx) => {
                const xr = Number(item.xRestrict ?? 0);
                const illustType = Number(item.illustType ?? 0);
                const isAi = Number(item.aiType ?? 0) >= 2;
                const r18Badge = xr === 2
                    ? '<span class="thumb-badge thumb-badge-r18g">R-18G</span>'
                    : xr === 1 ? '<span class="thumb-badge">R-18</span>' : '';
                const aiBadge = isAi ? '<span class="thumb-badge thumb-badge-ai">AI</span>' : '';
                const typeBadge = illustType === 2
                    ? `<span class="thumb-badge thumb-badge-ugoira">${esc(bt('search.type.ugoira', '动图'))}</span>`
                    : illustType === 1 ? `<span class="thumb-badge thumb-badge-manga">${esc(bt('search.type.manga', '漫画'))}</span>` : '';
                const pagesLabel = item.pageCount > 1 ? `<span class="thumb-pages">${item.pageCount}P</span>` : '';
                const inQueueClass = inQueue.has(String(item.id)) ? ' in-queue' : '';
                const queueTip = buildQueueToggleTip(inQueue.has(String(item.id)));
                return `<div class="search-thumb${inQueueClass}" id="user-thumb-${idx}"
                     onclick="addUserItemToQueue(${idx})" title="${esc(item.title)} (${esc(item.userName)})${queueTip}">
          <img id="user-thumb-img-${idx}" src="" alt="${esc(item.title)}">
          <div class="thumb-badge-stack">${r18Badge}${aiBadge}${typeBadge}</div>
          ${pagesLabel}
          <span class="thumb-in-queue-mark">✓</span>
          <div class="thumb-title">${esc(item.title)}</div>
        </div>`;
            }).join('')}
    </div>`;
            loadUserThumbnailsBatched(items, ctx.renderToken);
        }

        function renderPixivSeriesResults(area, ctx) {
            const inQueue = ctx.inQueue;
            area.innerHTML = `
    <div class="search-grid">
      ${ctx.items.map((item, idx) => {
                const xr = Number(item.xRestrict ?? 0);
                const isAi = Number(item.aiType ?? 0) >= 2;
                const r18Badge = xr === 2
                    ? '<span class="thumb-badge thumb-badge-r18g">R-18G</span>'
                    : xr === 1 ? '<span class="thumb-badge">R-18</span>' : '';
                const aiBadge = isAi ? '<span class="thumb-badge thumb-badge-ai">AI</span>' : '';
                const seriesOrder = Number(item.seriesOrder || getSeriesFallbackOrder(idx));
                const orderBadge = `<span class="thumb-badge thumb-badge-series-order">#${seriesOrder}</span>`;
                const pagesLabel = item.pageCount > 1 ? `<span class="thumb-pages">${item.pageCount}P</span>` : '';
                const inQueueClass = inQueue.has(item.id) ? ' in-queue' : '';
                const queueTip = buildQueueToggleTip(inQueue.has(item.id));
                return `<div class="search-thumb${inQueueClass}" id="series-thumb-${idx}"
                     onclick="addSeriesItemToQueue(${idx})" title="#${seriesOrder} ${esc(item.title)} (${esc(item.userName)})${queueTip}">
          <img id="series-thumb-img-${idx}" src="" alt="${esc(item.title)}">
          <div class="thumb-badge-stack">${orderBadge}${r18Badge}${aiBadge}</div>
          ${pagesLabel}
          <span class="thumb-in-queue-mark">✓</span>
          <div class="thumb-title">${esc(item.title)}</div>
        </div>`;
            }).join('')}
    </div>`;
            loadSeriesThumbnailsBatched(ctx.items, ctx.renderToken);
        }

        function scheduleSource(sourceType, legacyType, source, kind, label) {
            return {sourceType, type: legacyType, source, kind, label};
        }

        function scheduledSourceStyle(sourceType) {
            if (sourceType === 'search') return 'search';
            if (sourceType === 'series') return 'series';
            if (['user-new', 'user-request', 'my-bookmarks', 'follow-latest', 'collection']
                .includes(sourceType)) return 'user';
            return 'schedule';
        }

        function selfLabel(uid) {
            return bt('quick.schedule.source.self', '我自己（账号 {uid}）', {uid});
        }

        const descriptor = {
            process: processIllustItem,
            scheduledSse: true,
            scheduledQueueItem(item, ctx) {
                const rawId = String(item.workId != null ? item.workId : (item.id == null ? '' : item.id));
                return {
                    id: rawId,
                    kind: type,
                    rawTitle: item.title && String(item.title).trim() ? String(item.title) : null,
                    source: scheduledSourceStyle(ctx.sourceType)
                };
            },
            import: {
                bareDefault: true,
                sectionType: 'artwork',
                sectionAliases: ['illust'],
                matchUrl(line) {
                    const match = String(line).match(/https?:\/\/www\.pixiv\.net\/artworks\/(\d+)/);
                    return match ? match[1] : null;
                },
                buildItem(id, title) {
                    return {
                        id: String(id),
                        kind: type,
                        title: title || bt('queue.artwork-fallback', '作品 {id}', {id})
                    };
                },
                source: SINGLE_IMPORT_MODE
            },
            filters: {
                'illust-extra': {
                    extraSelector: '.search-illust-only',
                    bookmarkCountFetch(artworkId) {
                        return pixivPreviewJson(
                            `/api/pixiv/artwork/${encodeURIComponent(artworkId)}/meta`);
                    }
                }
            },
            acquisition: {
                user: {
                    pageSize: 30,
                    requestInit: previewRequestInit,
                    thumbnailEndpoint,
                    accepts(selection) {
                        return selection === type || selection === 'request';
                    },
                    parseInput: parsePixivUserInput,
                    detectVariant(raw, selected) {
                        return /\/request\b/.test(String(raw || '')) ? 'request' : selected;
                    },
                    fetchMeta: fetchPixivUserMeta,
                    fetchIds(userId, ctx) {
                        return ctx && ctx.variant === 'request'
                            ? fetchPixivUserRequests(userId, ctx)
                            : fetchPixivUserArtworks(userId, ctx);
                    },
                    cardsEndpoint(userId) {
                        return `/api/pixiv/user/${encodeURIComponent(userId)}/illust-cards`;
                    },
                    queueId(item) { return String(item.id); },
                    cardId(idx) { return `user-thumb-${idx}`; },
                    render: renderPixivUserResults,
                    buildQueueMeta: userQueueMeta,
                    buildQueueMetaFromId(_id, ctx) {
                        return {
                            kind: type,
                            authorId: Number(ctx.userId),
                            authorName: ctx.username || ctx.userId
                        };
                    }
                },
                search: {
                    pageSize: 60,
                    requestInit: previewRequestInit,
                    thumbnailEndpoint,
                    buildRequest: buildSearchRequest,
                    buildRangeRequest,
                    formatStats: formatPixivSearchStats,
                    queueId(item) { return String(item.id); },
                    queueSource: 'search',
                    emptyResultsLabel() { return bt('status.search-no-results', '无搜索结果'); },
                    render: renderPixivSearchResults,
                    buildQueueMeta: artworkQueueMeta
                },
                series: {
                    pageSize: 12,
                    requestInit: previewRequestInit,
                    thumbnailEndpoint,
                    apiPath(seriesId, page) {
                        return `/api/pixiv/series/${encodeURIComponent(seriesId)}?page=${page}`;
                    },
                    parseUrl: parsePixivSeriesUrl,
                    resolveSeriesId: resolvePixivSeriesId,
                    typeLabel() { return bt('series.meta.type-manga', '漫画系列'); },
                    queueId(item) { return String(item.id); },
                    cardId(idx) { return `series-thumb-${idx}`; },
                    queueSource: 'series',
                    render: renderPixivSeriesResults,
                    buildQueueMeta: seriesQueueMeta
                },
                quick: {
                    dataSource: {
                        id: 'pixiv',
                        displayNamespace: 'batch',
                        displayI18nKey: 'quick.data-source.pixiv',
                        order: 10
                    },
                    pageSize: QUICK_PAGE_SIZE_ILLUST,
                    requestInit: previewRequestInit,
                    thumbnailEndpoint,
                    account: {
                        credentialMissing() { return !cookieHasPhpsessid(); },
                        missingHint() {
                            return bt('quick.account.hint-no-cookie',
                                '未检测到登录 Cookie，请先在上方保存含 PHPSESSID 的 Cookie');
                        },
                        buildRequest() { return {endpoint: '/api/pixiv/me/uid'}; },
                        readId(data) { return data && data.uid; }
                    },
                    buildMyWorksIdsRequest(userId) {
                        return pixivUserIdsRequest(userId, 'artworks');
                    },
                    buildUserIdsRequest(userId) {
                        return pixivUserIdsRequest(userId, 'artworks');
                    },
                    buildCardsRequest(userId, ids) {
                        return pixivUserCardsRequest(userId, 'illust-cards', ids);
                    },
                    myWorksTitleKey: 'quick.title.my-illusts',
                    queueId(item) { return String(item.id); },
                    gridCardId(idPrefix, idx) { return `${idPrefix}-thumb-${idx}`; },
                    render: renderQuickIllustGrid,
                    innerCardHtml: pixivQuickInnerCard,
                    buildQueueMeta: artworkQueueMeta,
                    buildQueueMetaFromId() { return {kind: type}; },
                    actions: {
                        'my-illust-bookmarks-show': {
                            viewType: 'works-list', kind: type, pageSize: QUICK_PAGE_SIZE_ILLUST,
                            sourceType: 'my-bookmarks', scheduleRest: 'show', bookmarkEndpoint: 'illust-bookmarks',
                            buildPageRequest(ctx) {
                                return pixivMePageRequest('illust-bookmarks', {
                                    rest: ctx.rest, offset: ctx.offset, limit: ctx.limit
                                });
                            },
                            load: requirePixivQuickSession(() => loadQuickIllustBookmarks(type, 'show', 1)),
                            scheduleSource(ctx) {
                                const kindLabel = bt('schedule.kind.' + type, type);
                                const restLabel = bt('quick.schedule.rest.show', '公开');
                                return scheduleSource('my-bookmarks', 'MY_BOOKMARKS', {rest: 'show'}, type,
                                    bt('quick.schedule.source.bookmarks', '我的收藏（{kind}，{rest}）',
                                        {kind: kindLabel, rest: restLabel}));
                            }
                        },
                        'my-illust-bookmarks-hide': {
                            viewType: 'works-list', kind: type, pageSize: QUICK_PAGE_SIZE_ILLUST,
                            sourceType: 'my-bookmarks', scheduleRest: 'hide', bookmarkEndpoint: 'illust-bookmarks',
                            buildPageRequest(ctx) {
                                return pixivMePageRequest('illust-bookmarks', {
                                    rest: ctx.rest, offset: ctx.offset, limit: ctx.limit
                                });
                            },
                            load: requirePixivQuickSession(() => loadQuickIllustBookmarks(type, 'hide', 1)),
                            scheduleSource() {
                                const kindLabel = bt('schedule.kind.' + type, type);
                                const restLabel = bt('quick.schedule.rest.hide', '不公开');
                                return scheduleSource('my-bookmarks', 'MY_BOOKMARKS', {rest: 'hide'}, type,
                                    bt('quick.schedule.source.bookmarks', '我的收藏（{kind}，{rest}）',
                                        {kind: kindLabel, rest: restLabel}));
                            }
                        },
                        'my-illusts': {
                            viewType: 'works-list', kind: type, pageSize: QUICK_PAGE_SIZE_ILLUST,
                            sourceType: 'user-new', allIdsFastPath: true,
                            load: requirePixivQuickSession(() => loadQuickMyWorks(type, 1)),
                            scheduleSource(ctx) {
                                if (!ctx.uid) return null;
                                return scheduleSource('user-new', 'USER_NEW', {userId: String(ctx.uid)}, type,
                                    selfLabel(ctx.uid));
                            }
                        },
                        'my-request-artworks': {
                            viewType: 'works-list', kind: type, pageSize: QUICK_PAGE_SIZE_ILLUST,
                            sourceType: 'user-request', allIdsFastPath: true,
                            buildIdsRequest(userId) {
                                return pixivUserIdsRequest(userId, 'request-artworks');
                            },
                            buildCardsRequest(userId, ids) {
                                return pixivUserCardsRequest(userId, 'illust-cards', ids);
                            },
                            load: requirePixivQuickSession(() => loadQuickMyRequest(type, 1)),
                            scheduleSource(ctx) {
                                if (!ctx.uid) return null;
                                return scheduleSource('user-request', 'USER_REQUEST',
                                    {userId: String(ctx.uid)}, type,
                                    bt('quick.schedule.source.self-request', '我的约稿作品（账号 {uid}）', {uid: ctx.uid}));
                            }
                        },
                        'my-following-show': {
                            viewType: 'following-list', kind: type, userWorkTypes: ['illust', 'novel'],
                            buildPageRequest(ctx) {
                                return pixivMePageRequest('following', {
                                    rest: ctx.rest, offset: ctx.offset, limit: ctx.limit
                                });
                            },
                            load: requirePixivQuickSession(() => loadQuickFollowing('show', 0))
                        },
                        'my-following-hide': {
                            viewType: 'following-list', kind: type, userWorkTypes: ['illust', 'novel'],
                            buildPageRequest(ctx) {
                                return pixivMePageRequest('following', {
                                    rest: ctx.rest, offset: ctx.offset, limit: ctx.limit
                                });
                            },
                            load: requirePixivQuickSession(() => loadQuickFollowing('hide', 0))
                        },
                        'my-following-new': {
                            viewType: 'works-list', kind: type, pageSize: QUICK_PAGE_SIZE_ILLUST,
                            sourceType: 'follow-latest',
                            buildPageRequest(ctx) {
                                return pixivMePageRequest('follow-latest', {p: ctx.page});
                            },
                            load: requirePixivQuickSession(() => loadQuickFollowingNew(type, 1)),
                            scheduleSource() {
                                return scheduleSource('follow-latest', 'FOLLOW_LATEST', {}, type,
                                    bt('quick.title.following-new', '已关注的用户的新作'));
                            }
                        },
                        'my-collections': {
                            viewType: 'collection-list', kind: type,
                            buildPageRequest() { return pixivMePageRequest('collections'); },
                            buildCollectionWorksRequest(collectionId) {
                                return pixivMePageRequest(
                                    `collection/${encodeURIComponent(collectionId)}/works`);
                            },
                            load: requirePixivQuickSession(() => loadQuickCollections())
                        }
                    }
                }
            }
        };
        return {descriptor};
    });
})();
