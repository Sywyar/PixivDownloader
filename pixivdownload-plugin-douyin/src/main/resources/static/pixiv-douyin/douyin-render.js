'use strict';

const DOUYIN_DETAIL_MEDIA_LABELS = Object.freeze({
    IMAGE: ['frontend.media.image', 'Image'],
    VIDEO: ['frontend.media.video', 'Video'],
    LIVE_PHOTO_VIDEO: ['frontend.media.live-photo', 'Live photo video'],
    COVER: ['frontend.media.cover', 'Cover']
});

const DOUYIN_DETAIL_ATTRIBUTE_FIELDS = Object.freeze([
    ['collectionTitle', 'detail.attribute.collection-title', 'Collection'],
    ['collectionId', 'detail.attribute.collection-id', 'Collection ID'],
    ['collectionOrder', 'detail.attribute.collection-order', 'Collection order'],
    ['fileCount', 'detail.attribute.file-count', 'Files']
]);

function douyinDetailElement(id) {
    return document.getElementById(id);
}

function douyinDetailReplaceText(target, value) {
    if (!target) return;
    target.textContent = value == null ? '' : String(value);
}

function douyinDetailShowStatus(key, fallback, type) {
    const state = PixivDouyinDetailCore.state;
    state.status = {key, fallback, type: type || ''};
    const status = douyinDetailElement('detailStatus');
    const root = douyinDetailElement('detailRoot');
    if (root) root.hidden = true;
    if (!status) return;
    status.hidden = false;
    status.dataset.status = type || '';
    status.textContent = PixivDouyinDetailCore.t(key, fallback);
}

function douyinDetailRerenderStatus() {
    const status = PixivDouyinDetailCore.state.status;
    if (status) douyinDetailShowStatus(status.key, status.fallback, status.type);
}

function douyinDetailSummaryRow(documentRef, label, value) {
    const row = documentRef.createElement('div');
    row.className = 'summary-row';
    const term = documentRef.createElement('dt');
    term.textContent = label;
    const detail = documentRef.createElement('dd');
    detail.textContent = String(value);
    row.appendChild(term);
    row.appendChild(detail);
    return row;
}

function douyinDetailRenderTimeSummary(work) {
    const values = [
        ['createdAt', 'detail.time.created', 'Published'],
        ['downloadedAt', 'detail.time.downloaded', 'Downloaded'],
        ['updatedAt', 'detail.time.updated', 'Updated']
    ];
    const summary = douyinDetailElement('timeSummary');
    const section = douyinDetailElement('timeSection');
    summary.replaceChildren();
    values.forEach(([field, key, fallback]) => {
        const formatted = PixivDouyinDetailCore.formatTime(work[field]);
        if (formatted) summary.appendChild(douyinDetailSummaryRow(
            summary.ownerDocument, PixivDouyinDetailCore.t(key, fallback), formatted));
    });
    section.hidden = summary.children.length === 0;
}

function douyinDetailRenderTags(work) {
    const list = douyinDetailElement('tagList');
    const section = douyinDetailElement('tagSection');
    list.replaceChildren();
    const tags = Array.isArray(work.tags) ? work.tags : [];
    tags.forEach(tag => {
        const name = tag && typeof tag === 'object' ? tag.label || tag.name || tag.displayName : tag;
        if (name == null || !String(name).trim()) return;
        const item = list.ownerDocument.createElement('span');
        item.className = 'tag';
        item.textContent = String(name).trim();
        list.appendChild(item);
    });
    section.hidden = list.children.length === 0;
}

function douyinDetailRenderAttributes(work) {
    const summary = douyinDetailElement('attributeSummary');
    const section = douyinDetailElement('attributeSection');
    summary.replaceChildren();
    const attributes = work.attributes && typeof work.attributes === 'object' ? work.attributes : {};
    DOUYIN_DETAIL_ATTRIBUTE_FIELDS.forEach(([field, key, fallback]) => {
        const value = attributes[field];
        if (value == null || String(value).trim() === '') return;
        summary.appendChild(douyinDetailSummaryRow(
            summary.ownerDocument, PixivDouyinDetailCore.t(key, fallback), String(value)));
    });
    section.hidden = summary.children.length === 0;
}

function douyinDetailRenderActions(work) {
    const host = douyinDetailElement('workActions');
    host.replaceChildren();
    const attributes = work.attributes && typeof work.attributes === 'object' ? work.attributes : {};
    const sourceUrl = PixivDouyinDetailCore.safeExternalUrl(attributes.canonicalUrl)
        || PixivDouyinDetailCore.safeExternalUrl(attributes.sourceUrl);
    if (sourceUrl) {
        const link = host.ownerDocument.createElement('a');
        link.className = 'work-action';
        link.href = sourceUrl;
        link.target = '_blank';
        link.rel = 'noopener noreferrer';
        link.textContent = PixivDouyinDetailCore.t('detail.action.open-source', 'Open source');
        host.appendChild(link);
    }
    host.hidden = host.children.length === 0;
}

function douyinDetailMediaLabel(kind) {
    const definition = DOUYIN_DETAIL_MEDIA_LABELS[String(kind || '').toUpperCase()];
    return definition ? PixivDouyinDetailCore.t(definition[0], definition[1])
        : PixivDouyinDetailCore.t('detail.media.unsupported', 'Unsupported media');
}

function douyinDetailMediaUnavailable(host) {
    host.replaceChildren();
    const message = host.ownerDocument.createElement('p');
    message.className = 'media-unavailable';
    message.textContent = PixivDouyinDetailCore.t('detail.media.unavailable', 'Media unavailable');
    host.appendChild(message);
}

function douyinDetailRenderMediaAsset(media, index, total, preferredMediaId) {
    const documentRef = document;
    const kind = String(media && media.kind || '').toUpperCase();
    const item = documentRef.createElement('figure');
    item.className = 'media-item';
    const mediaId = PixivDouyinDetailCore.mediaId(media);
    if (mediaId != null) item.dataset.mediaId = mediaId;
    if (preferredMediaId && mediaId === preferredMediaId) item.classList.add('preferred');

    const frame = documentRef.createElement('div');
    frame.className = 'media-frame';
    const url = PixivDouyinDetailCore.safeLocalUrl(media && media.url);
    const thumbnail = PixivDouyinDetailCore.safeLocalUrl(media && media.thumbnailUrl);
    if (kind === 'IMAGE' || kind === 'COVER') {
        const source = url || thumbnail;
        if (source) {
            const image = documentRef.createElement('img');
            image.loading = 'lazy';
            image.decoding = 'async';
            image.src = source;
            image.alt = '';
            image.addEventListener('error', () => douyinDetailMediaUnavailable(frame), {once: true});
            frame.appendChild(image);
        } else {
            douyinDetailMediaUnavailable(frame);
        }
    } else if (kind === 'VIDEO' || kind === 'LIVE_PHOTO_VIDEO') {
        if (url) {
            const video = documentRef.createElement('video');
            video.controls = true;
            video.preload = 'metadata';
            video.playsInline = true;
            video.src = url;
            if (thumbnail) video.poster = thumbnail;
            video.setAttribute('aria-label', douyinDetailMediaLabel(kind));
            video.addEventListener('error', () => douyinDetailMediaUnavailable(frame), {once: true});
            frame.appendChild(video);
        } else {
            douyinDetailMediaUnavailable(frame);
        }
    } else {
        douyinDetailMediaUnavailable(frame);
    }
    item.appendChild(frame);

    const caption = documentRef.createElement('figcaption');
    caption.className = 'media-caption';
    const label = documentRef.createElement('span');
    label.className = 'media-caption-label';
    label.textContent = PixivDouyinDetailCore.t('detail.media.position', '{label} · {index}/{total}', {
        label: douyinDetailMediaLabel(kind), index: index + 1, total
    });
    caption.appendChild(label);
    const attributes = media && media.attributes && typeof media.attributes === 'object' ? media.attributes : {};
    const fileName = attributes.fileName || null;
    if (fileName) {
        const file = documentRef.createElement('span');
        file.className = 'media-caption-file';
        file.textContent = String(fileName);
        caption.appendChild(file);
    }
    item.appendChild(caption);
    return item;
}

function douyinDetailRenderMedia(work) {
    const list = douyinDetailElement('mediaList');
    const count = douyinDetailElement('mediaCount');
    list.replaceChildren();
    const preferred = PixivDouyinDetailCore.state.preferredMediaId;
    const media = (Array.isArray(work.media) ? work.media : [])
        .map((value, index) => ({value, index}))
        .sort((left, right) => Number(PixivDouyinDetailCore.mediaId(right.value) === preferred)
            - Number(PixivDouyinDetailCore.mediaId(left.value) === preferred) || left.index - right.index)
        .map(item => item.value);
    count.textContent = PixivDouyinDetailCore.t('detail.media.count', '{count} items', {count: media.length});
    if (media.length === 0) {
        const empty = list.ownerDocument.createElement('div');
        empty.className = 'media-item media-frame';
        douyinDetailMediaUnavailable(empty);
        list.appendChild(empty);
        return;
    }
    media.forEach((asset, index) => list.appendChild(
        douyinDetailRenderMediaAsset(asset, index, media.length, preferred)));
}

function douyinDetailRenderWork(work) {
    const state = PixivDouyinDetailCore.state;
    state.work = work;
    state.status = null;
    const status = douyinDetailElement('detailStatus');
    const root = douyinDetailElement('detailRoot');
    const title = work.title == null || !String(work.title).trim()
        ? PixivDouyinDetailCore.t('detail.untitled', 'Untitled work') : String(work.title).trim();
    douyinDetailReplaceText(douyinDetailElement('workTitle'), title);
    douyinDetailReplaceText(douyinDetailElement('topbarTitle'), title);
    document.title = PixivDouyinDetailCore.t('detail.document-title', '{title} · Douyin', {title});

    const author = work.author && typeof work.author === 'object' ? work.author : null;
    const authorName = author && (author.displayName || author.name);
    const authorElement = douyinDetailElement('workAuthor');
    douyinDetailReplaceText(authorElement, authorName);
    authorElement.hidden = !authorName;

    const description = work.description == null ? '' : String(work.description).trim();
    const descriptionElement = douyinDetailElement('workDescription');
    douyinDetailReplaceText(descriptionElement, description);
    descriptionElement.hidden = !description;

    douyinDetailRenderActions(work);
    douyinDetailRenderTimeSummary(work);
    douyinDetailRenderTags(work);
    douyinDetailRenderAttributes(work);
    douyinDetailRenderMedia(work);
    status.hidden = true;
    root.hidden = false;
}

function douyinDetailRerender() {
    if (PixivDouyinDetailCore.state.work) {
        douyinDetailRenderWork(PixivDouyinDetailCore.state.work);
    } else {
        douyinDetailRerenderStatus();
    }
}

const PixivDouyinDetailRender = Object.freeze({
    showStatus: douyinDetailShowStatus,
    renderWork: douyinDetailRenderWork,
    rerender: douyinDetailRerender
});
