'use strict';

const unifiedGalleryState = {kind: 'IMAGE', cursor: null, hasMore: false, loading: false, items: [], i18n: null};

function unifiedGalleryText(key) {
    return unifiedGalleryState.i18n ? unifiedGalleryState.i18n.t(key) : key;
}

async function unifiedGalleryJson(url) {
    const response = await fetch(url, {credentials: 'same-origin'});
    if (!response.ok) throw new Error(String(response.status));
    return response.json();
}

function unifiedGalleryQueryUrl() {
    const params = new URLSearchParams({kind: unifiedGalleryState.kind, limit: '40'});
    if (unifiedGalleryState.cursor) params.set('cursor', unifiedGalleryState.cursor);
    return '/api/gallery/unified/projections?' + params;
}

async function loadUnifiedGallery(reset) {
    if (unifiedGalleryState.loading) return;
    unifiedGalleryState.loading = true;
    UnifiedGalleryViews.status(unifiedGalleryText('unified.loading'));
    try {
        if (reset) {
            unifiedGalleryState.cursor = null;
            unifiedGalleryState.items = [];
        }
        const page = await unifiedGalleryJson(unifiedGalleryQueryUrl());
        unifiedGalleryState.items.push(...page.projections);
        unifiedGalleryState.cursor = page.nextCursor;
        unifiedGalleryState.hasMore = page.hasMore;
        UnifiedGalleryViews.render(unifiedGalleryState);
    } catch (error) {
        UnifiedGalleryViews.status(unifiedGalleryText('unified.error'));
    } finally {
        unifiedGalleryState.loading = false;
    }
}

async function loadUnifiedGalleryDetail(key) {
    const url = '/api/gallery/unified/works/'
            + [key.sourceId, key.sourceWorkNamespace, key.sourceWorkId].map(encodeURIComponent).join('/');
    const response = await unifiedGalleryJson(url);
    UnifiedGalleryViews.detail(response.work);
}

async function initUnifiedGalleryI18n() {
    unifiedGalleryState.i18n = await PixivI18n.create({namespaces: ['gallery', 'common']});
    unifiedGalleryState.i18n.apply(document);
    document.title = unifiedGalleryText('unified.title');
}

window.UnifiedGallery = {
    state: unifiedGalleryState,
    t: unifiedGalleryText,
    load: loadUnifiedGallery,
    detail: loadUnifiedGalleryDetail,
    initI18n: initUnifiedGalleryI18n
};
