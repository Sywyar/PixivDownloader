(function () {
    'use strict';

    const MODULE_URL = '/pixiv-gallery/pixiv-gallery-frontend.js';

    function ownerDocument(context) {
        return context && context.host && context.host.ownerDocument
            ? context.host.ownerDocument : document;
    }

    function translate(context, key, params) {
        return context && typeof context.t === 'function' ? context.t(key, params) : key;
    }

    function workId(work) {
        const outerKey = work && (work.key || work.workKey);
        const key = outerKey && outerKey.workKey ? outerKey.workKey : outerKey;
        const value = key && key.sourceWorkId != null ? key.sourceWorkId
            : work && work.sourceWorkId != null ? work.sourceWorkId : null;
        return value == null ? '' : String(value);
    }

    function renderPageCount(context) {
        const work = context && context.work;
        const rawCount = work && work.attributes ? work.attributes.pageCount : null;
        const count = Number(rawCount);
        if (!Number.isFinite(count) || count <= 1) return false;

        const badge = ownerDocument(context).createElement('span');
        badge.className = 'gallery-card-meta pixiv-gallery-page-count';
        badge.textContent = translate(context, 'gallery:frontend.card.pages', { count: count });
        return badge;
    }

    function renderMedia(context) {
        const renderer = window.PixivGalleryFrontend.renderStandardMedia;
        if (typeof renderer !== 'function') return false;
        return renderer({
            work: context && context.work,
            media: context && context.media,
            host: context && context.host
        }) || false;
    }

    function renderArtworkAction(context) {
        const id = workId(context && context.work);
        if (!id) return false;

        const link = ownerDocument(context).createElement('a');
        link.className = 'gallery-detail-action pixiv-gallery-artwork-action';
        link.href = '/pixiv-artwork.html?id=' + encodeURIComponent(id);
        link.textContent = translate(context, 'gallery:frontend.action.open-artwork');
        return link;
    }

    window.PixivGalleryFrontend.registerModule(MODULE_URL, function (api) {
        api.registerCardExtension({
            id: 'pixiv.card',
            render: renderPageCount
        });
        api.registerMediaRenderer({
            id: 'pixiv.media',
            mediaKinds: ['IMAGE', 'UGOIRA'],
            render: renderMedia
        });
        api.registerDetailAction({
            id: 'pixiv.detail-actions',
            render: renderArtworkAction
        });
    });
})();
