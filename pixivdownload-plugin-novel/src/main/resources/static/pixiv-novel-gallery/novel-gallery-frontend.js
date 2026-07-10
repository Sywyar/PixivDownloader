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

    function renderText(context) {
        const doc = ownerDocument(context);
        const media = context && context.media ? context.media : {};
        const article = doc.createElement('article');
        article.className = 'gallery-media-text novel-gallery-media-text';

        const content = media.content == null ? '' : String(media.content);
        if (!content) {
            const empty = doc.createElement('p');
            empty.className = 'gallery-media-empty';
            empty.textContent = translate(context, 'novel-gallery:frontend.text.empty');
            article.appendChild(empty);
            return article;
        }

        const body = doc.createElement('pre');
        body.className = 'gallery-media-text-content';
        body.textContent = content;
        article.appendChild(body);
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
