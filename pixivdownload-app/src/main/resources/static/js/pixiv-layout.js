'use strict';

window.PixivLayout = window.PixivLayout || {};
window.PixivLayout.previewUrl = function (url, element) {
    if (!element) return url;
    const box = element.tagName === 'IMG' ? element.parentElement || element : element;
    const bounds = box.getBoundingClientRect();
    const needed = Math.ceil(Math.max(bounds.width, bounds.height) * (window.devicePixelRatio || 1));
    let size = 128;
    while (size < needed && size < 1600) size = Math.min(1600, size * 2);
    const target = new URL(url, window.location.href);
    target.searchParams.set('size', String(needed > 0 ? size : 512));
    element.dataset.previewSrc = url;
    return target.pathname + target.search;
};

function refreshPreviewSizes() {
    document.querySelectorAll('[data-preview-src]').forEach(element => {
        const image = element.tagName === 'IMG' ? element : element.querySelector('img');
        const url = window.PixivLayout.previewUrl(element.dataset.previewSrc, element);
        if (image) {
            if (image.getAttribute('src') !== url) image.src = url;
        } else if (element.style.backgroundImage) {
            element.style.backgroundImage = `url("${url}")`;
        }
    });
}
window.addEventListener('resize', refreshPreviewSizes);

// 工具栏会随译文、文字缩放和插件入口换行，媒体区域使用实际高度。
const pixivLayoutObserver = new ResizeObserver(entries => {
    for (const {target} of entries) {
        if (!target.dataset.layoutHeight) continue;
        document.documentElement.style.setProperty(
            target.dataset.layoutHeight,
            `${target.getBoundingClientRect().height}px`
        );
    }
    refreshPreviewSizes();
});
document.querySelectorAll('[data-layout-height]').forEach(element => pixivLayoutObserver.observe(element));
pixivLayoutObserver.observe(document.documentElement);
