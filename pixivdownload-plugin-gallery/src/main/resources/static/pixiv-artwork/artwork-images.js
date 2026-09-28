'use strict';

const ARTWORK_PREVIEW_MAX_IMAGES = 32;
const ARTWORK_PREVIEW_MAX_PIXELS = 48 * 1024 * 1024;
const artworkPreviews = new Map();
let artworkPreviewPixels = 0;
let artworkImagesActive = true;
const artworkOriginals = new Map();

function discardArtworkImage(image) {
    image.onload = image.onerror = null;
    image.removeAttribute('src');
}

function retainArtworkPreview(image) {
    const src = image.currentSrc || image.src;
    const pixels = image.naturalWidth * image.naturalHeight;
    if (!artworkImagesActive || !src || !src.includes('/api/downloaded/thumbnail/')
        || !Number.isSafeInteger(pixels) || pixels <= 0 || pixels > ARTWORK_PREVIEW_MAX_PIXELS) return;
    const existing = artworkPreviews.get(src);
    if (existing) {
        artworkPreviews.delete(src);
        artworkPreviews.set(src, existing);
        return;
    }
    while (artworkPreviews.size >= ARTWORK_PREVIEW_MAX_IMAGES
        || artworkPreviewPixels + pixels > ARTWORK_PREVIEW_MAX_PIXELS) {
        const [key, entry] = artworkPreviews.entries().next().value;
        discardArtworkImage(entry.image);
        artworkPreviews.delete(key);
        artworkPreviewPixels -= entry.pixels;
    }
    // 显示节点会反复换 src；独立引用保留同一预览资源，避免旧解码资源尚未回收时重新建立。
    const retained = new Image();
    retained.decoding = 'async';
    retained.src = src;
    artworkPreviews.set(src, {image: retained, pixels});
    artworkPreviewPixels += pixels;
}

function clearArtworkPreviews() {
    for (const entry of artworkPreviews.values()) discardArtworkImage(entry.image);
    artworkPreviews.clear();
    artworkPreviewPixels = 0;
}

function releaseArtworkOriginal(image, restorePreview = true) {
    const entry = artworkOriginals.get(image);
    if (!entry) return;
    artworkOriginals.delete(image);
    if (entry.request) discardArtworkImage(entry.request);
    if (entry.committed && restorePreview) {
        if (entry.previewSource) entry.previewTarget.dataset.previewSrc = entry.previewSource;
        image.src = entry.previewSource
            ? window.PixivLayout.previewUrl(entry.previewSource, entry.previewTarget) : entry.previewUrl;
    }
}

function isArtworkOriginal(image) {
    return artworkOriginals.get(image)?.committed === true;
}

function loadArtworkOriginal(image, url) {
    if (!artworkImagesActive || artworkOriginals.has(image) || !image.complete) return;
    const previewUrl = image.getAttribute('src');
    if (!previewUrl?.includes('/api/downloaded/thumbnail/')) return;
    retainArtworkPreview(image);
    const previewTarget = image.dataset.previewSrc ? image : image.parentElement;
    const request = new Image();
    const entry = {request, previewUrl, previewTarget, previewSource: previewTarget?.dataset.previewSrc, committed: false};
    artworkOriginals.set(image, entry);
    const fail = () => {
        if (artworkOriginals.get(image) !== entry) return;
        discardArtworkImage(request);
        entry.request = null;
        toast(image.naturalWidth
            ? wt('status.original-load-failed', 'Could not load the original image. Keeping the preview.')
            : wt('status.load-failed', 'Load failed'));
    };
    request.decoding = 'async';
    request.onerror = fail;
    request.onload = async () => {
        try {
            // 在离屏节点完成解码后再替换，原图加载期间保留已经显示的预览。
            if (request.decode) await request.decode();
            if (artworkOriginals.get(image) !== entry) return;
            entry.committed = true;
            image.removeAttribute('data-preview-src');
            entry.previewTarget?.removeAttribute('data-preview-src');
            image.src = url;
            discardArtworkImage(request);
            entry.request = null;
        } catch (_) {
            fail();
        }
    };
    request.src = url;
}

window.addEventListener('pagehide', () => {
    artworkImagesActive = false;
    for (const image of artworkOriginals.keys()) releaseArtworkOriginal(image);
    clearArtworkPreviews();
});
window.addEventListener('pageshow', () => { artworkImagesActive = true; });
document.addEventListener('visibilitychange', () => {
    artworkImagesActive = document.visibilityState !== 'hidden';
    if (!artworkImagesActive) {
        for (const image of artworkOriginals.keys()) releaseArtworkOriginal(image);
        clearArtworkPreviews();
    }
});
