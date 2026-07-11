'use strict';

window.PixivDouyinGallery = window.PixivDouyinGallery || {};

const DOUYIN_GALLERY_PAGE_SIZE = 24;
const DOUYIN_GALLERY_VIEWS = new Set(['all', 'image', 'video']);
let douyinGalleryI18n = null;

const douyinGalleryState = {
    view: 'all',
    items: [],
    nextCursor: null,
    hasMore: false,
    loading: false,
    requestRevision: 0,
    statusCode: 'loading',
    statusValues: {}
};

function douyinGallerySetI18n(i18n) {
    douyinGalleryI18n = i18n || null;
}

function douyinGalleryText(key, values) {
    return douyinGalleryI18n && typeof douyinGalleryI18n.t === 'function'
        ? douyinGalleryI18n.t(key, null, values || {})
        : key;
}

function douyinGalleryNormalizeView(value) {
    const normalized = String(value || '').trim().toLowerCase();
    return DOUYIN_GALLERY_VIEWS.has(normalized) ? normalized : 'all';
}

function douyinGalleryReadView(search) {
    return douyinGalleryNormalizeView(new URLSearchParams(search || '').get('view'));
}

function douyinGalleryKindForView(view) {
    if (view === 'image') return 'IMAGE';
    if (view === 'video') return 'VIDEO';
    return 'ALL';
}

function douyinGalleryUpdateLocation(view) {
    const url = new URL(window.location.href);
    url.searchParams.set('view', douyinGalleryNormalizeView(view));
    window.history.replaceState(null, '', url.pathname + url.search + url.hash);
}

function douyinGalleryBuildProjectionUrl(cursor) {
    const params = new URLSearchParams();
    params.set('kind', douyinGalleryKindForView(douyinGalleryState.view));
    params.set('cursor', cursor == null ? '' : String(cursor));
    params.set('limit', String(DOUYIN_GALLERY_PAGE_SIZE));
    return '/api/douyin/gallery/projections?' + params.toString();
}

async function douyinGalleryRequestJson(url) {
    const response = await fetch(url, {
        credentials: 'same-origin',
        headers: {'Accept': 'application/json'}
    });
    if (response.status === 401) {
        const redirect = window.location.pathname + window.location.search;
        window.location.href = '/login.html?redirect=' + encodeURIComponent(redirect);
        throw new Error('AUTH_REQUIRED');
    }
    if (!response.ok) throw new Error('HTTP_' + response.status);
    return response.json();
}

function douyinGalleryNormalizePage(payload) {
    const source = payload && typeof payload === 'object' ? payload : {};
    const items = Array.isArray(source.projections) ? source.projections
        : Array.isArray(source.items) ? source.items
            : Array.isArray(source.content) ? source.content : [];
    const nextCursor = source.nextCursor == null || source.nextCursor === ''
        ? null : String(source.nextCursor);
    return {
        items,
        nextCursor,
        hasMore: source.hasMore === true || nextCursor != null
    };
}

function douyinGalleryWorkKey(value) {
    if (!value || typeof value !== 'object') return null;
    if (value.key && value.key.workKey) return value.key.workKey;
    if (value.workKey) return value.workKey;
    return value.key && value.key.sourceWorkId != null ? value.key : null;
}

function douyinGalleryWorkId(value) {
    const key = douyinGalleryWorkKey(value);
    const candidate = key && key.sourceWorkId != null ? key.sourceWorkId
        : value && value.sourceWorkId != null ? value.sourceWorkId
            : value && value.workId != null ? value.workId
                : value && value.id != null ? value.id : null;
    return candidate == null || String(candidate).trim() === '' ? null : String(candidate).trim();
}

function douyinGalleryDetailHref(value) {
    const id = douyinGalleryWorkId(value);
    if (id == null) return null;
    const params = new URLSearchParams();
    params.set('id', id);
    if (value && value.preferredMediaId != null && String(value.preferredMediaId).trim()) {
        params.set('preferredMediaId', String(value.preferredMediaId).trim());
    }
    params.set('returnTo', window.location.pathname + window.location.search);
    return '/pixiv-douyin.html?' + params.toString();
}

function douyinGallerySafeLocalUrl(value) {
    if (typeof value !== 'string' || !value.startsWith('/') || value.startsWith('//')
        || /[\u0000-\u0020\\]/.test(value)) return null;
    const rawPath = value.split(/[?#]/, 1)[0].toLowerCase();
    if (/%(?:2e|2f|5c|00|25)/.test(rawPath)
        || rawPath.split('/').some(part => part === '.' || part === '..')) return null;
    try {
        const resolved = new URL(value, window.location.origin);
        if (resolved.origin !== window.location.origin || resolved.username || resolved.password || resolved.hash
            || !resolved.pathname.startsWith('/') || resolved.pathname.startsWith('//')
            || resolved.pathname.includes('//')) return null;
        return resolved.pathname + resolved.search;
    } catch (_) {
        return null;
    }
}

function douyinGalleryMediaKinds(value) {
    const kinds = value && Array.isArray(value.containedMediaKinds) ? value.containedMediaKinds : [];
    return [...new Set(kinds.map(kind => String(kind || '').trim().toUpperCase()).filter(Boolean))];
}

function douyinGallerySetStatus(code, values) {
    douyinGalleryState.statusCode = code;
    douyinGalleryState.statusValues = values || {};
}

async function douyinGalleryLoadPage(append) {
    if (douyinGalleryState.loading) return null;
    const revision = ++douyinGalleryState.requestRevision;
    const cursor = append ? douyinGalleryState.nextCursor : null;
    douyinGalleryState.loading = true;
    douyinGallerySetStatus('loading');
    window.PixivDouyinGallery.view.renderStatus();
    window.PixivDouyinGallery.view.renderLoadMore();
    try {
        const payload = await douyinGalleryRequestJson(douyinGalleryBuildProjectionUrl(cursor));
        if (revision !== douyinGalleryState.requestRevision) return null;
        const page = douyinGalleryNormalizePage(payload);
        douyinGalleryState.items = append
            ? douyinGalleryState.items.concat(page.items) : page.items.slice();
        douyinGalleryState.nextCursor = page.nextCursor;
        douyinGalleryState.hasMore = page.hasMore;
        douyinGallerySetStatus(douyinGalleryState.items.length ? 'ready' : 'empty', {
            count: douyinGalleryState.items.length
        });
        window.PixivDouyinGallery.view.render();
        return page;
    } catch (_) {
        if (revision === douyinGalleryState.requestRevision) {
            douyinGallerySetStatus('failed');
            window.PixivDouyinGallery.view.render();
        }
        return null;
    } finally {
        if (revision === douyinGalleryState.requestRevision) {
            douyinGalleryState.loading = false;
            window.PixivDouyinGallery.view.renderLoadMore();
        }
    }
}

async function douyinGallerySelectView(view) {
    const next = douyinGalleryNormalizeView(view);
    ++douyinGalleryState.requestRevision;
    douyinGalleryState.view = next;
    douyinGalleryState.items = [];
    douyinGalleryState.nextCursor = null;
    douyinGalleryState.hasMore = false;
    douyinGalleryState.loading = false;
    douyinGalleryUpdateLocation(next);
    douyinGallerySetStatus('loading');
    window.PixivDouyinGallery.view.render();
    return douyinGalleryLoadPage(false);
}

window.PixivDouyinGallery.state = douyinGalleryState;
window.PixivDouyinGallery.core = Object.assign(window.PixivDouyinGallery.core || {}, {
    setI18n: douyinGallerySetI18n,
    text: douyinGalleryText,
    normalizeView: douyinGalleryNormalizeView,
    readView: douyinGalleryReadView,
    kindForView: douyinGalleryKindForView,
    updateLocation: douyinGalleryUpdateLocation,
    buildProjectionUrl: douyinGalleryBuildProjectionUrl,
    normalizePage: douyinGalleryNormalizePage,
    workKey: douyinGalleryWorkKey,
    workId: douyinGalleryWorkId,
    detailHref: douyinGalleryDetailHref,
    safeLocalUrl: douyinGallerySafeLocalUrl,
    mediaKinds: douyinGalleryMediaKinds,
    setStatus: douyinGallerySetStatus,
    loadPage: douyinGalleryLoadPage,
    selectView: douyinGallerySelectView
});
