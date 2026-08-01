(function () {
    'use strict';

    const MODULE_URL = '/pixiv-novel-gallery/novel-gallery-frontend.js';

    function ownerDocument(context) {
        return context && context.host && context.host.ownerDocument
            ? context.host.ownerDocument : document;
    }

    function translate(context, key, params) {
        return context && typeof context.t === 'function' ? context.t(key, params) : key;
    }

    function workId(work) {
        const key = work && (work.key || work.workKey);
        const value = key && key.sourceWorkId != null ? key.sourceWorkId
            : work && work.sourceWorkId != null ? work.sourceWorkId : null;
        return value == null ? '' : String(value);
    }

    function showTextState(body, className, text) {
        body.className = className;
        body.textContent = text;
    }

    function renderText(context) {
        const doc = ownerDocument(context);
        const id = workId(context && context.work);
        const media = context && context.media ? context.media : {};
        const article = doc.createElement('article');
        article.className = 'gallery-media-text novel-gallery-media-text';

        const body = doc.createElement('pre');
        article.appendChild(body);
        if (!id) {
            showTextState(body, 'gallery-media-empty',
                translate(context, 'novel-gallery:frontend.text.empty'));
            return article;
        }

        const expectedUrl = '/api/gallery/novel/' + encodeURIComponent(id) + '/content';
        const mediaUrl = media.url == null ? '' : String(media.url);
        if (mediaUrl !== expectedUrl || typeof window.fetch !== 'function') {
            showTextState(body, 'gallery-media-empty',
                translate(context, 'novel-gallery:frontend.text.error'));
            return article;
        }

        const generation = window.PixivGalleryFrontend
            && typeof window.PixivGalleryFrontend.generation === 'function'
            ? window.PixivGalleryFrontend.generation() : null;
        showTextState(body, 'gallery-media-text-content gallery-media-text-loading',
            translate(context, 'novel-gallery:frontend.text.loading'));
        window.fetch(mediaUrl, {
            credentials: 'same-origin',
            headers: {'Accept': 'application/json'}
        }).then(function (response) {
            if (!response || !response.ok) throw new Error('novel-content-unavailable');
            return response.json();
        }).then(function (payload) {
            if (generation != null
                    && window.PixivGalleryFrontend.generation() !== generation) {
                return;
            }
            const content = payload && payload.content != null ? String(payload.content) : '';
            if (!content) {
                showTextState(body, 'gallery-media-empty',
                    translate(context, 'novel-gallery:frontend.text.empty'));
                return;
            }
            showTextState(body, 'gallery-media-text-content', content);
        }).catch(function () {
            if (generation != null
                    && window.PixivGalleryFrontend.generation() !== generation) {
                return;
            }
            showTextState(body, 'gallery-media-empty',
                translate(context, 'novel-gallery:frontend.text.error'));
        });
        return article;
    }

    function renderReaderAction(context) {
        const id = workId(context && context.work);
        if (!id) return null;

        const doc = ownerDocument(context);
        const link = doc.createElement('a');
        link.className = 'gallery-detail-action novel-gallery-reader-action';
        link.href = '/pixiv-novel.html?id=' + encodeURIComponent(id);
        link.textContent = translate(context, 'novel-gallery:frontend.action.open-reader');
        return link;
    }

    window.PixivGalleryFrontend.registerModule(MODULE_URL, function (api) {
        api.registerMediaRenderer({
            id: 'novel.text-renderer',
            mediaKinds: ['TEXT'],
            render: renderText
        });
        api.registerDetailAction({
            id: 'novel.detail-actions',
            render: renderReaderAction
        });
    });
})();
