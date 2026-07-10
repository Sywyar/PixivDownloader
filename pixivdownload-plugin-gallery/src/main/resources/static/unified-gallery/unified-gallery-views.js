'use strict';

const unifiedGalleryGrid = document.getElementById('galleryGrid');
const unifiedGalleryStatusNode = document.getElementById('status');
const unifiedGalleryMore = document.getElementById('loadMore');
const unifiedGalleryDialog = document.getElementById('detailDialog');
const unifiedGalleryContent = document.getElementById('detailContent');

function unifiedGalleryElement(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text != null) node.textContent = text;
    return node;
}

function setUnifiedGalleryStatus(text) {
    unifiedGalleryStatusNode.textContent = text || '';
}

function unifiedGalleryMediaBadges(kinds) {
    const wrap = unifiedGalleryElement('div', 'badges');
    (kinds || []).forEach(kind => wrap.append(unifiedGalleryElement(
            'span', 'badge', UnifiedGallery.t('unified.media.' + kind.toLowerCase()))));
    return wrap;
}

function unifiedGalleryCard(item) {
    const node = unifiedGalleryElement('article', 'gallery-card');
    node.tabIndex = 0;
    node.setAttribute('role', 'button');
    if (item.thumbnailUrl) {
        const image = unifiedGalleryElement('img');
        image.src = item.thumbnailUrl;
        image.alt = '';
        image.loading = 'lazy';
        node.append(image);
    }
    const body = unifiedGalleryElement('div', 'card-body');
    body.append(unifiedGalleryElement('h2', 'card-title', item.title || item.key.workKey.sourceWorkId),
            unifiedGalleryMediaBadges(item.containedMediaKinds));
    node.append(body);
    const open = () => UnifiedGallery.detail(item.key.workKey)
            .catch(() => setUnifiedGalleryStatus(UnifiedGallery.t('unified.detail-error')));
    node.addEventListener('click', open);
    node.addEventListener('keydown', event => {
        if (event.key === 'Enter' || event.key === ' ') {
            event.preventDefault();
            open();
        }
    });
    return node;
}

function renderUnifiedGallery(state) {
    unifiedGalleryGrid.replaceChildren(...state.items.map(unifiedGalleryCard));
    unifiedGalleryMore.hidden = !state.hasMore;
    setUnifiedGalleryStatus(state.items.length
            ? UnifiedGallery.t('unified.loaded').replace('{count}', state.items.length)
            : UnifiedGallery.t('unified.empty'));
}

function renderUnifiedGalleryDetail(work) {
    unifiedGalleryContent.replaceChildren();
    const body = unifiedGalleryElement('div', 'detail-body');
    body.append(unifiedGalleryElement('h2', '', work.title || work.key.sourceWorkId));
    if (work.description) body.append(unifiedGalleryElement('p', '', work.description));
    body.append(unifiedGalleryMediaBadges((work.media || []).map(item => item.kind)));
    const list = unifiedGalleryElement('div', 'media-list');
    (work.media || []).forEach(item => {
        if (item.kind === 'TEXT') {
            list.append(unifiedGalleryElement('div', 'text-media', item.content || ''));
        } else if (item.kind === 'VIDEO' || item.kind === 'LIVE_PHOTO_VIDEO') {
            const video = unifiedGalleryElement('video', 'media-video');
            video.src = item.url;
            video.controls = true;
            list.append(video);
        } else if (item.url) {
            const image = unifiedGalleryElement('img', 'media-image');
            image.src = item.url;
            image.alt = '';
            image.loading = 'lazy';
            list.append(image);
        }
    });
    body.append(list);
    unifiedGalleryContent.append(body);
    unifiedGalleryDialog.showModal();
}

window.UnifiedGalleryViews = {
    status: setUnifiedGalleryStatus,
    render: renderUnifiedGallery,
    detail: renderUnifiedGalleryDetail,
    dialog: unifiedGalleryDialog
};
