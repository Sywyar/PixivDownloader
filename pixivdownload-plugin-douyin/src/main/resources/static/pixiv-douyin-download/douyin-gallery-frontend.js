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

    function sameOriginUrl(value) {
        if (typeof value !== 'string' || !value.startsWith('/')) return null;
        try {
            const resolved = new URL(value, window.location.origin);
            return resolved.origin === window.location.origin ? resolved.href : null;
        } catch (failure) {
            return null;
        }
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

    function mediaLabel(context, kind) {
        const keys = {
            IMAGE: 'douyin:frontend.media.image',
            VIDEO: 'douyin:frontend.media.video',
            LIVE_PHOTO_VIDEO: 'douyin:frontend.media.live-photo',
            COVER: 'douyin:frontend.media.cover'
        };
        return translate(context, keys[kind] || 'douyin:source.douyin');
    }

    function renderMedia(context) {
        const media = context && context.media;
        if (!media) return null;
        const url = sameOriginUrl(media.url);
        if (!url) return null;

        const doc = ownerDocument(context);
        const kind = String(media.kind || '');
        const figure = doc.createElement('figure');
        figure.className = 'gallery-media gallery-media-douyin gallery-media-' + kind.toLowerCase();

        if (kind === 'VIDEO' || kind === 'LIVE_PHOTO_VIDEO') {
            const video = doc.createElement('video');
            video.controls = true;
            video.preload = 'metadata';
            video.src = url;
            const poster = sameOriginUrl(media.thumbnailUrl);
            if (poster) video.poster = poster;
            video.setAttribute('aria-label', mediaLabel(context, kind));
            figure.appendChild(video);
            return figure;
        }

        if (kind === 'IMAGE' || kind === 'COVER') {
            const image = doc.createElement('img');
            image.loading = 'lazy';
            image.src = url;
            image.alt = mediaLabel(context, kind);
            figure.appendChild(image);
            return figure;
        }
        return null;
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
