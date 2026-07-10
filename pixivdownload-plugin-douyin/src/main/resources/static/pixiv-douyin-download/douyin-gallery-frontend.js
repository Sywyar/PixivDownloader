(function () {
    'use strict';

    const MODULE_URL = '/pixiv-douyin-download/douyin-gallery-frontend.js';

    function ownerDocument(context) {
        return context && context.host && context.host.ownerDocument
            ? context.host.ownerDocument : document;
    }

    function translate(context, key, params) {
        return context && typeof context.t === 'function' ? context.t(key, params) : key;
    }

    function actorName(work) {
        const actor = work && work.author;
        const value = actor && (actor.displayName || actor.name);
        return value == null ? '' : String(value);
    }

    function renderCard(context) {
        const doc = ownerDocument(context);
        const fragment = doc.createDocumentFragment();
        const source = doc.createElement('span');
        source.className = 'gallery-card-source gallery-card-source-douyin';
        source.textContent = translate(context, 'douyin:source.douyin');
        fragment.appendChild(source);

        const name = actorName(context && context.work);
        if (name) {
            const author = doc.createElement('span');
            author.className = 'gallery-card-author';
            author.textContent = name;
            fragment.appendChild(author);
        }
        return fragment;
    }

    function renderMedia(context) {
        const renderer = window.PixivGalleryFrontend.renderStandardMedia;
        if (typeof renderer !== 'function') return null;
        const media = renderer({
            work: context && context.work,
            media: context && context.media,
            host: context && context.host
        });
        if (!media) return null;
        const container = ownerDocument(context).createElement('figure');
        container.className = 'gallery-media-source gallery-media-douyin';
        container.appendChild(media);
        return container;
    }

    window.PixivGalleryFrontend.registerModule(MODULE_URL, function (api) {
        api.registerCardExtension({
            id: 'douyin.card',
            render: renderCard
        });
        api.registerMediaRenderer({
            id: 'douyin.media',
            mediaKinds: ['IMAGE', 'VIDEO', 'LIVE_PHOTO_VIDEO', 'COVER'],
            render: renderMedia
        });
    });
})();
