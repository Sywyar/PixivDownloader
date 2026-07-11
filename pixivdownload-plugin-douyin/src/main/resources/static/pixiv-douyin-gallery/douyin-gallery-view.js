'use strict';

function douyinGalleryElement(id) {
    return document.getElementById(id);
}

function douyinGalleryRenderCategories() {
    const activeView = window.PixivDouyinGallery.state.view;
    document.querySelectorAll('[data-gallery-view]').forEach(link => {
        const active = link.getAttribute('data-gallery-view') === activeView;
        link.classList.toggle('active', active);
        if (active) link.setAttribute('aria-current', 'page');
        else link.removeAttribute('aria-current');
    });
}

function douyinGalleryStatusKey(code) {
    switch (code) {
        case 'loading': return 'gallery.status.loading';
        case 'empty': return 'gallery.status.empty';
        case 'failed': return 'gallery.status.failed';
        case 'ready': return 'gallery.status.ready';
        default: return 'gallery.status.empty';
    }
}

function douyinGalleryRenderStatus() {
    const state = window.PixivDouyinGallery.state;
    const status = douyinGalleryElement('douyinGalleryStatus');
    if (!status) return;
    status.dataset.state = state.statusCode;
    status.textContent = window.PixivDouyinGallery.core.text(
        douyinGalleryStatusKey(state.statusCode), state.statusValues);
}

function douyinGalleryMediaKey(kind) {
    switch (String(kind || '').toUpperCase()) {
        case 'IMAGE': return 'gallery.media.image';
        case 'VIDEO': return 'gallery.media.video';
        case 'LIVE_PHOTO_VIDEO': return 'gallery.media.live-photo-video';
        case 'COVER': return 'gallery.media.cover';
        default: return 'gallery.media.unknown';
    }
}

function douyinGalleryCreateThumbnail(documentRef, item) {
    const thumbnail = documentRef.createElement('span');
    thumbnail.className = 'douyin-gallery-card-thumb';
    const safeUrl = window.PixivDouyinGallery.core.safeLocalUrl(item.thumbnailUrl);
    if (safeUrl) {
        const image = documentRef.createElement('img');
        image.loading = 'lazy';
        image.decoding = 'async';
        image.src = safeUrl;
        image.alt = item.title == null ? '' : String(item.title);
        thumbnail.appendChild(image);
    } else {
        const placeholder = documentRef.createElement('span');
        placeholder.className = 'douyin-gallery-card-placeholder';
        placeholder.setAttribute('aria-hidden', 'true');
        thumbnail.appendChild(placeholder);
    }
    return thumbnail;
}

function douyinGalleryCreateCard(item) {
    const href = window.PixivDouyinGallery.core.detailHref(item);
    if (!href) return null;
    const documentRef = document;
    const article = documentRef.createElement('article');
    article.className = 'douyin-gallery-card';
    const id = window.PixivDouyinGallery.core.workId(item);
    article.dataset.workId = id;

    const link = documentRef.createElement('a');
    link.className = 'douyin-gallery-card-link';
    link.href = href;
    link.appendChild(douyinGalleryCreateThumbnail(documentRef, item));

    const body = documentRef.createElement('span');
    body.className = 'douyin-gallery-card-body';
    const title = documentRef.createElement('strong');
    title.className = 'douyin-gallery-card-title';
    title.textContent = item.title == null || String(item.title).trim() === ''
        ? window.PixivDouyinGallery.core.text('gallery.card.untitled') : String(item.title);
    body.appendChild(title);

    const authorValue = item.author && (item.author.name || item.author.displayName);
    if (authorValue != null && String(authorValue).trim() !== '') {
        const author = documentRef.createElement('span');
        author.className = 'douyin-gallery-card-author';
        author.textContent = String(authorValue);
        body.appendChild(author);
    }

    const summaryValue = item.summary != null ? item.summary : item.description;
    if (summaryValue != null && String(summaryValue).trim() !== '') {
        const summary = documentRef.createElement('span');
        summary.className = 'douyin-gallery-card-summary';
        summary.textContent = String(summaryValue);
        body.appendChild(summary);
    }

    const mediaKinds = window.PixivDouyinGallery.core.mediaKinds(item);
    if (mediaKinds.length) {
        const badges = documentRef.createElement('span');
        badges.className = 'douyin-gallery-card-kinds';
        mediaKinds.forEach(kind => {
            const badge = documentRef.createElement('span');
            badge.className = 'douyin-gallery-kind';
            badge.textContent = window.PixivDouyinGallery.core.text(douyinGalleryMediaKey(kind));
            badges.appendChild(badge);
        });
        body.appendChild(badges);
    }

    link.appendChild(body);
    article.appendChild(link);
    return article;
}

function douyinGalleryRenderCards() {
    const grid = douyinGalleryElement('douyinGalleryGrid');
    if (!grid) return;
    const fragment = document.createDocumentFragment();
    window.PixivDouyinGallery.state.items.forEach(item => {
        const card = douyinGalleryCreateCard(item);
        if (card) fragment.appendChild(card);
    });
    grid.replaceChildren(fragment);
}

function douyinGalleryRenderLoadMore() {
    const state = window.PixivDouyinGallery.state;
    const button = douyinGalleryElement('douyinGalleryLoadMore');
    if (!button) return;
    button.hidden = !state.hasMore;
    button.disabled = state.loading;
    button.setAttribute('aria-busy', state.loading ? 'true' : 'false');
}

function douyinGalleryRender() {
    douyinGalleryRenderCategories();
    douyinGalleryRenderStatus();
    douyinGalleryRenderCards();
    douyinGalleryRenderLoadMore();
}

window.PixivDouyinGallery.view = Object.assign(window.PixivDouyinGallery.view || {}, {
    renderCategories: douyinGalleryRenderCategories,
    renderStatus: douyinGalleryRenderStatus,
    createCard: douyinGalleryCreateCard,
    renderCards: douyinGalleryRenderCards,
    renderLoadMore: douyinGalleryRenderLoadMore,
    render: douyinGalleryRender
});
