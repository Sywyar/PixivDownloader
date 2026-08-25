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
    douyinLinkedAbortSignal, douyinFetchJson, processDouyinItem
} = shared;
function douyinCardHtml(item, idx, ctx) {
    const idPrefix = ctx.idPrefix || 'douyin';
    const cardId = douyinCardId(idPrefix, idx);
    const queueId = douyinQueueId(item);
    const inQueue = ctx.inQueue && ctx.inQueue.has(queueId);
    const title = item.title || douyinText('queue.fallback', 'Douyin {id}', {id: item.id});
    const author = item.userName || item.authorName || '';
    return `<div class="search-thumb${inQueue ? ' in-queue' : ''}" id="${cardId}" data-douyin-idx="${idx}"
                 title="${esc(title)} (${esc(author)})">
      <div class="thumb-title">${esc(title)}</div>
      <div class="thumb-title" style="font-size:12px;color:var(--muted);">${esc(author || item.id || '')}</div>
      <span class="thumb-in-queue-mark">✓</span>
    </div>`;
}

function renderDouyinGrid(area, items, ctx) {
    if (!items.length) {
        area.innerHTML = `<div style="color:var(--muted);text-align:center;padding:24px 0;">${esc(douyinText('empty', 'No Douyin items'))}</div>`;
        return;
    }
    area.innerHTML = (ctx.summaryHtml || '') + `<div class="search-grid">${
        items.map((item, idx) => douyinCardHtml(item, idx, ctx)).join('')
    }</div>`;
    area.querySelectorAll('[data-douyin-idx]').forEach(card => {
        bindDouyinEvent(card, 'click', () => ctx.onClick(Number(card.dataset.douyinIdx)));
    });
}

function renderDouyinUserResults(area, ctx) {
    renderDouyinGrid(area, ctx.items || [], {
        summaryHtml: ctx.summaryHtml,
        inQueue: ctx.inQueue,
        idPrefix: 'user',
        onClick: idx => addUserItemToQueue(idx)
    });
}

function renderDouyinSearchResults(area, view) {
    const inQueue = new Set(state.queue.map(q => q.id));
    renderDouyinGrid(area, view.items || [], {
        inQueue,
        idPrefix: 'search',
        onClick: idx => addSearchItemToQueue((view.base || 0) + idx)
    });
}

function renderDouyinSeriesResults(area, ctx) {
    renderDouyinGrid(area, ctx.items || [], {
        inQueue: ctx.inQueue,
        idPrefix: 'series',
        onClick: idx => addSeriesItemToQueue(idx)
    });
}

const douyinQuickCursors = new Map();

function douyinAssertQuickActionContext(context) {
    if (!context) return;
    if (typeof context.assertCurrent === 'function') context.assertCurrent();
    if (typeof context.isCurrent === 'function' && !context.isCurrent()) {
        throw new Error(douyinText('error.stale-request', 'This quick action is no longer active'));
    }
}

function douyinQuickFetchJson(path, operation) {
    if (typeof quickFetchJson === 'function') {
        return quickFetchJson(path, 'douyin', operation || 'quick');
    }
    // 独立 contract smoke 环境没有宿主 quick 脚本；浏览器运行态始终走上面的受控取得门。
    return douyinFetchJson(path);
}

async function loadQuickDouyinAccount(source, sourceType, titleKey, titleFallback, page, context) {
    douyinAssertQuickActionContext(context);
    const safePage = Math.max(1, Number(page) || 1);
    if (safePage === 1) {
        Array.from(douyinQuickCursors.keys())
            .filter(key => key.startsWith(`${source}:`))
            .forEach(key => douyinQuickCursors.delete(key));
    }
    const cursorKey = `${source}:${safePage}`;
    const cursor = safePage === 1 ? '0' : douyinQuickCursors.get(cursorKey);
    if (cursor == null) {
        throw new Error(douyinText('error.pagination-stalled', 'The Douyin cursor for this page is no longer available'));
    }
    const data = await douyinQuickFetchJson(
        `/api/douyin/me/${encodeURIComponent(source)}?cursor=${encodeURIComponent(cursor)}&pageSize=${DOUYIN_PAGE_SIZE}`);
    douyinAssertQuickActionContext(context);
    const items = Array.isArray(data.items) ? data.items : [];
    const nextCursor = data.nextCursor == null ? '' : String(data.nextCursor);
    if (data.hasMore && (!nextCursor || nextCursor === String(cursor))) {
        throw new Error(douyinText('error.pagination-stalled', 'The Douyin cursor did not advance'));
    }
    items.forEach((item, index) => {
        item.sourceType = sourceType;
        item.sourceId = source;
        item.sourceTitle = '';
        item.sourceUrl = null;
        item.sourceOrder = (safePage - 1) * DOUYIN_PAGE_SIZE + index;
    });
    const offset = (safePage - 1) * DOUYIN_PAGE_SIZE;
    const reportedTotal = Number(data.total);
    const minimumTotal = offset + items.length + (data.hasMore ? 1 : 0);
    quickState.rawItems = items;
    const previousTotal = Number(quickState.total);
    quickState.total = Math.max(Number.isFinite(previousTotal) ? previousTotal : 0,
        Number.isFinite(reportedTotal) && reportedTotal >= 0
            ? Math.max(Math.floor(reportedTotal), minimumTotal) : minimumTotal);
    quickState.offset = offset;
    quickState.page = safePage;
    quickSetTitle(`${douyinText(titleKey, titleFallback)} · ${bt('quick.title.count', '{count} items', {count: quickState.total.toLocaleString()})}`);
    quickShowToolbar({showBack: false, showAdd: quickState.rawItems.length > 0, showSearch: false, showKindSwitcher: false});
    await quickRenderOuterWorks();
    douyinAssertQuickActionContext(context);
    if (data.hasMore) douyinQuickCursors.set(`${source}:${safePage + 1}`, nextCursor);
    const totalPages = Math.max(safePage, data.hasMore ? safePage + 1 : safePage);
    renderQuickPagination(safePage, totalPages,
        p => loadQuickDouyinAccount(source, sourceType, titleKey, titleFallback, p, context));
}

function douyinUserWorksPageEndpoint(userId, context) {
    const params = new URLSearchParams();
    params.set('offset', String(context.offset));
    params.set('limit', String(context.limit));
    const cursor = context.cursor || (Number(context.offset) === 0 ? '0' : null);
    if (cursor != null) params.set('cursor', String(cursor));
    return `/api/douyin/user/${encodeURIComponent(userId)}/works/ids?${params}`;
}

function douyinUserLikedPageEndpoint(userId, context) {
    const params = new URLSearchParams();
    params.set('offset', String(context.offset));
    params.set('limit', String(context.limit));
    const cursor = context.cursor || (Number(context.offset) === 0 ? '0' : null);
    if (cursor != null) params.set('cursor', String(cursor));
    return `/api/douyin/user/${encodeURIComponent(userId)}/liked/ids?${params}`;
}

function douyinUserProfileUrl(userId) {
    return `https://www.douyin.com/user/${encodeURIComponent(String(userId))}`;
}

function douyinUserSourceType(kind) {
    if (kind === DOUYIN_USER_KIND_LIKED) return 'douyin.user.liked-works';
    return 'douyin.user';
}

function douyinDecorateUserItems(items, userId, kind, offset) {
    const sourceType = douyinUserSourceType(kind);
    return (Array.isArray(items) ? items : []).map((item, index) => Object.assign({}, item, {
        douyinUserVariant: kind,
        sourceType,
        sourceId: String(userId),
        sourceTitle: String(userId),
        sourceUrl: douyinUserProfileUrl(userId),
        sourceOrder: Math.max(0, Number(offset) || 0) + index
    }));
}

async function douyinFetchUserPage(userId, context) {
    const kind = douyinUserKind(context);
    const targetUserId = String(userId);
    const endpoint = kind === DOUYIN_USER_KIND_LIKED
        ? douyinUserLikedPageEndpoint(targetUserId, context)
        : douyinUserWorksPageEndpoint(targetUserId, context);
    let data;
    try {
        data = await douyinFetchJson(endpoint, {signal: context.signal});
    } catch (error) {
        if (kind === DOUYIN_USER_KIND_LIKED && error && error.code === 'PERMISSION_DENIED') {
            const hidden = new Error(douyinText('user.error.liked-hidden',
                'This user has hidden their liked works, or the current Cookie cannot access them'));
            hidden.code = error.code;
            throw hidden;
        }
        throw error;
    }
    return {
        items: douyinDecorateUserItems(data.items, targetUserId, kind, context.offset),
        total: data.total,
        nextCursor: data.nextCursor,
        hasMore: !!data.hasMore
    };
}

function douyinUserEmptyMessage(context) {
    const kind = douyinUserKind(context);
    if (kind === DOUYIN_USER_KIND_LIKED) {
        return douyinText('user.empty.liked',
            'No liked works were returned; the list may be empty, hidden, or inaccessible with the current Cookie');
    }
    return douyinText('user.empty.works', 'This user has no works');
}

function douyinFavoriteCollectionsEndpoint(cursor, pageSize) {
    return `/api/douyin/me/favorite-collections?cursor=${encodeURIComponent(cursor)}&pageSize=${pageSize}`;
}

function douyinFavoriteCollectionWorksEndpoint(collectionId, context) {
    const params = new URLSearchParams();
    params.set('cursor', String(context.cursor == null ? '0' : context.cursor));
    params.set('pageSize', String(context.limit));
    return `/api/douyin/me/favorite-collections/${encodeURIComponent(collectionId)}/works?${params}`;
}

function douyinFavoriteFolderWorksEndpoint(folderId, context) {
    const params = new URLSearchParams();
    params.set('cursor', String(context.cursor == null ? '0' : context.cursor));
    params.set('pageSize', String(context.limit || DOUYIN_PAGE_SIZE));
    return `/api/douyin/me/favorite-folders/${encodeURIComponent(folderId)}/works?${params}`;
}

function douyinFavoriteFolderSeriesId(folderId) {
    return DOUYIN_FAVORITE_FOLDER_SERIES_PREFIX + String(folderId || '');
}

function douyinFavoriteFolderId(seriesId) {
    const value = String(seriesId || '');
    if (!value.startsWith(DOUYIN_FAVORITE_FOLDER_SERIES_PREFIX)) return null;
    const folderId = value.substring(DOUYIN_FAVORITE_FOLDER_SERIES_PREFIX.length);
    return folderId || null;
}

async function loadQuickDouyinFavoriteCollections(page, context) {
    douyinAssertQuickActionContext(context);
    const source = 'favorite-collections';
    const safePage = Math.max(1, Number(page) || 1);
    if (safePage === 1) {
        Array.from(douyinQuickCursors.keys())
            .filter(key => key.startsWith(`${source}:`))
            .forEach(key => douyinQuickCursors.delete(key));
    }
    const cursor = safePage === 1 ? '0' : douyinQuickCursors.get(`${source}:${safePage}`);
    if (cursor == null) {
        throw new Error(douyinText('error.pagination-stalled', 'The Douyin cursor for this page is no longer available'));
    }
    const data = await douyinQuickFetchJson(douyinFavoriteCollectionsEndpoint(cursor, DOUYIN_PAGE_SIZE));
    douyinAssertQuickActionContext(context);
    const collections = Array.isArray(data.collections) ? data.collections : [];
    const nextCursor = data.nextCursor == null ? '' : String(data.nextCursor);
    if (data.hasMore && (!nextCursor || nextCursor === String(cursor))) {
        throw new Error(douyinText('error.pagination-stalled', 'The Douyin collection cursor did not advance'));
    }
    if (data.hasMore) douyinQuickCursors.set(`${source}:${safePage + 1}`, nextCursor);
    const offset = (safePage - 1) * DOUYIN_PAGE_SIZE;
    const reportedTotal = Number(data.total);
    const minimumTotal = offset + collections.length + (data.hasMore ? 1 : 0);
    quickState.items = collections;
    const previousTotal = Number(quickState.total);
    quickState.total = Math.max(Number.isFinite(previousTotal) ? previousTotal : 0,
        Number.isFinite(reportedTotal) && reportedTotal >= 0
            ? Math.max(Math.floor(reportedTotal), minimumTotal) : minimumTotal);
    quickState.offset = offset;
    quickState.page = safePage;
    quickSetTitle(`${douyinText('quick.favorite-collections', 'Favorite collections')} · ${bt('quick.title.count', '{count} items', {count: quickState.total.toLocaleString()})}`);
    quickShowToolbar({showAdd: false, showSearch: false});
    renderQuickCollectionGrid(quickState.items);
    const totalPages = Math.max(safePage, data.hasMore ? safePage + 1 : safePage);
    renderQuickPagination(safePage, totalPages, p => loadQuickDouyinFavoriteCollections(p, context));
    updateExtraFiltersCardVisibility();
    updateSaveScheduleCardVisibility();
    applyNovelSettingsVisibility();
}

function renderQuickDouyinGrid(items, idPrefix, summaryHtml) {
    const area = document.getElementById('quick-preview-area');
    if (!area) return;
    renderDouyinGrid(area, items || [], {
        summaryHtml,
        inQueue: new Set(state.queue.map(q => q.id)),
        idPrefix: idPrefix || 'quick',
        onClick: idx => quickToggleItemQueue(idx)
    });
}

function douyinQuickInnerCard(item, idx, inQueue) {
    const queueId = douyinQueueId(item);
    const title = item.title || douyinText('queue.fallback', 'Douyin {id}', {id: item.id});
    const author = item.userName || item.authorName || '';
    return `<div class="search-thumb${inQueue.has(queueId) ? ' in-queue' : ''}" id="quick-inner-card-${idx}"
                 data-pixiv-click="quickInnerToggleQueue(${idx})" title="${esc(title)} (${esc(author)})">
      <div class="thumb-title">${esc(title)}</div>
      <div class="thumb-title" style="font-size:12px;color:var(--muted);">${esc(author || item.id || '')}</div>
      <span class="thumb-in-queue-mark">✓</span>
    </div>`;
}

const DOUYIN_SLOTS = {
    'kind-option-user':
        '<label data-kind="douyin"><input type="radio" name="user-kind" value="douyin">' +
        '<span data-i18n="douyin:user.kind.works">Works</span></label>' +
        '<label data-kind="douyin-user-liked" data-i18n-title="douyin:user.visibility-hint" ' +
        'title="This list may be hidden or access-restricted"><input type="radio" name="user-kind" value="douyin-user-liked">' +
        '<span data-i18n="douyin:user.kind.liked">Liked</span></label>',
    'kind-option-quick':
        '<label data-quick-kind="douyin"><input type="radio" name="quick-inner-kind" value="douyin">' +
        '<span data-i18n="douyin:batch.kind">Douyin</span></label>',
    'quick-actions-bookmarks':
        '<button type="button" class="btn btn-blue quick-action" data-quick="douyin-liked" data-pixiv-click="quickLoad(\'douyin-liked\')" ' +
        'data-i18n="douyin:quick.liked">Liked works</button>' +
        '<button type="button" class="btn btn-purple quick-action" data-quick="douyin-favorites" data-pixiv-click="quickLoad(\'douyin-favorites\')" ' +
        'data-i18n="douyin:quick.favorites">Favorite works</button>' +
        '<button type="button" class="btn btn-yellow quick-action" data-quick="douyin-favorite-collections" data-pixiv-click="quickLoad(\'douyin-favorite-collections\')" ' +
        'data-i18n="douyin:quick.favorite-collections">Favorite collections</button>',
    'quick-actions-mine':
        '<button type="button" class="btn btn-green quick-action" data-quick="douyin-own-works" data-pixiv-click="quickLoad(\'douyin-own-works\')" ' +
        'data-i18n="douyin:quick.own-works">My works</button>',
    'import-hint':
        '<div><span data-i18n="douyin:import.example">Douyin URL: https://www.douyin.com/video/...</span></div>',
    'cookie-tools':
        '<section class="cookie-type-card plugin-cookie-card douyin-cookie-block" id="douyin-cookie-block" data-cookie-type="douyin">' +
        '<div class="plugin-cookie-title" data-i18n="douyin:settings.cookie.title">Douyin Cookie</div>' +
        '<div class="cookie-row plugin-cookie-row">' +
        '<input type="password" id="douyin-cookie-input" autocomplete="off" ' +
        'data-i18n-placeholder="douyin:settings.cookie.placeholder" placeholder="Paste Douyin Cookie">' +
        '<button type="button" class="btn-sm" id="douyin-cookie-toggle" data-i18n="douyin:settings.cookie.show">Show</button>' +
        '<button type="button" class="btn-cookie-save" id="douyin-cookie-save" data-i18n="common:button.save">Save</button>' +
        '<button type="button" class="btn-sm" id="douyin-cookie-validate" data-i18n="douyin:settings.cookie.validate">Validate</button>' +
        '<button type="button" class="btn-cookie-clear" id="douyin-cookie-clear" data-i18n="douyin:settings.cookie.clear">Clear</button>' +
        '</div>' +
        '<div class="cookie-status plugin-cookie-status" id="douyin-cookie-status" role="status" aria-live="polite"></div>' +
        '<div class="cookie-hint plugin-cookie-hint" data-i18n="douyin:settings.cookie.hint">Copy the full Cookie from a logged-in Douyin browser request.</div>' +
        '</section>'
};

function hydrateDouyinCookieSettings() {
    const input = document.getElementById('douyin-cookie-input');
    if (!input) return;
    input.value = douyinStoredCookieRaw();
    if (input.dataset.douyinBound !== '1') {
        input.dataset.douyinBound = '1';
        bindDouyinEvent(input, 'input', () => douyinUpdateCookieStatus(false, douyinCookieInputHeaderString()));
        const toggle = document.getElementById('douyin-cookie-toggle');
        if (toggle) {
            bindDouyinEvent(toggle, 'click', () => {
                const visible = input.type === 'text';
                input.type = visible ? 'password' : 'text';
                toggle.setAttribute('data-i18n', visible
                    ? 'douyin:settings.cookie.show'
                    : 'douyin:settings.cookie.hide');
                toggle.textContent = visible
                    ? douyinText('settings.cookie.show', 'Show')
                    : douyinText('settings.cookie.hide', 'Hide');
            });
        }
        const save = document.getElementById('douyin-cookie-save');
        if (save) {
            bindDouyinEvent(save, 'click', () => {
                const raw = input.value.trim();
                const header = douyinCookieRawToHeaderString(raw);
                const validation = douyinValidateCookie(header);
                if (!validation.ok) {
                    douyinUpdateCookieStatus(true, header);
                    return;
                }
                douyinSetStoredCookieRaw(raw);
                douyinUpdateCookieStatus(true, header);
                if (typeof invalidateQuickAccount === 'function') invalidateQuickAccount('douyin');
                if (typeof applyQuickActionCredentialUi === 'function') applyQuickActionCredentialUi();
                if (typeof updateQuickAccountBar === 'function') updateQuickAccountBar('douyin');
            });
        }
        const validate = document.getElementById('douyin-cookie-validate');
        if (validate) {
            bindDouyinEvent(validate, 'click', () => douyinUpdateCookieStatus(true, douyinCookieInputHeaderString()));
        }
        const clear = document.getElementById('douyin-cookie-clear');
        if (clear) {
            bindDouyinEvent(clear, 'click', () => {
                input.value = '';
                douyinRemoveStoredCookieRaw();
                douyinUpdateCookieStatus(false, '');
                if (typeof invalidateQuickAccount === 'function') invalidateQuickAccount('douyin');
                if (typeof applyQuickActionCredentialUi === 'function') applyQuickActionCredentialUi();
                if (typeof updateQuickAccountBar === 'function') updateQuickAccountBar('douyin');
            });
        }
    }
    douyinUpdateCookieStatus(false, douyinCookieInputHeaderString());
}

function hydrateDouyinUi() {
    hydrateDouyinCookieSettings();
    if (typeof applyQuickActionCredentialUi === 'function') applyQuickActionCredentialUi();
}

Object.assign(shared, {
    douyinCardHtml, renderDouyinGrid, renderDouyinUserResults, renderDouyinSearchResults,
    renderDouyinSeriesResults, douyinQuickCursors, douyinAssertQuickActionContext, douyinQuickFetchJson,
    loadQuickDouyinAccount, douyinUserWorksPageEndpoint, douyinUserLikedPageEndpoint, douyinUserProfileUrl,
    douyinUserSourceType, douyinDecorateUserItems, douyinFetchUserPage, douyinUserEmptyMessage,
    douyinFavoriteCollectionsEndpoint, douyinFavoriteCollectionWorksEndpoint, douyinFavoriteFolderWorksEndpoint, douyinFavoriteFolderSeriesId,
    douyinFavoriteFolderId, loadQuickDouyinFavoriteCollections, renderQuickDouyinGrid, douyinQuickInnerCard,
    DOUYIN_SLOTS, hydrateDouyinCookieSettings, hydrateDouyinUi
});
});
})();
