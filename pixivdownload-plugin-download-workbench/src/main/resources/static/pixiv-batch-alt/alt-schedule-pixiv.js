'use strict';

// 来源负责把共享快照回灌到新版取得页面；参数控件与手动下载共用。
window.PixivBatch = window.PixivBatch || {};
window.PixivBatch.pixivScheduleCapture = context => {
    if (context.mode === QUICK_FETCH_MODE && context.quickSource?.sourceType === 'my-bookmarks') {
        return {...context, quickSource: {...context.quickSource, kind: quickState.kind}};
    }
    if (context.mode !== 'series') return context;
    const input = document.getElementById('abSeriesInput');
    const parsed = parseSeriesUrl(input ? input.value : seriesState.url);
    seriesState.seriesId = parsed && parsed.id || null;
    if (parsed && parsed.kind) seriesState.kind = parsed.kind;
    return context;
};
window.PixivBatch.pixivScheduleRestore = (restored, sourceType) => {
    const {mode, params, kind, filters} = restored;
    const source = params.source || {};
    extraFilters = filters;
    if (mode === 'user') {
        Object.assign(userState, {
            source: 'pixiv', kind: sourceType === 'user-request' ? 'request' : kind,
            input: String(source.userId || ''), userId: '', rawItems: [], items: [], error: ''
        });
    } else if (mode === 'search') {
        const maxPages = source.maxPages;
        Object.assign(searchState, {
            source: 'pixiv', kind, word: source.word || '',
            order: source.order, sMode: source.sMode,
            submode: maxPages === -1 || maxPages > 1 ? 'batch' : 'search',
            startPage: 1, endPage: maxPages, optionsOpen: true, preferenceLoaded: true,
            rawResults: [], results: [], total: 0, error: '', batchInfo: null
        });
    } else if (mode === 'series') {
        Object.assign(seriesState, {
            source: 'pixiv', kind, seriesId: source.seriesId,
            url: kind === 'novel' ? `https://www.pixiv.net/novel/series/${source.seriesId}`
                : String(source.seriesId || ''),
            info: null, rawItems: [], items: [], error: ''
        });
    } else {
        const acquisition = altAcquisition('quick', 'pixiv', kind === 'mixed' ? 'illust' : kind);
        const action = Object.entries(acquisition?.actions || {}).find(([, descriptor]) =>
            sourceType === 'collection' ? descriptor.viewType === 'collection-list'
                : descriptor.sourceType === sourceType && (!source.rest || descriptor.scheduleRest === source.rest))?.[0] || null;
        const drill = sourceType === 'collection'
            ? {type: 'collection', id: String(source.collectionId), name: String(source.collectionId)} : null;
        Object.assign(quickState, {source: 'pixiv', kind, action, drill,
            rawItems: [], items: [], error: ''});
    }
    switchMode(mode);
};
