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
    target.searchParams.set('preview', '2');
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

// 页面声明滚动容器；抽屉、弹窗等局部滚动不影响顶部导航。
function initLayoutHeader(header) {
    const scroller = header.dataset.layoutScrollRoot
        ? document.querySelector(header.dataset.layoutScrollRoot) : document.scrollingElement;
    if (!scroller) return;
    const scrollTarget = scroller === document.scrollingElement ? window : scroller;
    const narrow = window.matchMedia('(max-width: 56.25em)');
    let previous = 0;
    let travel = 0;
    let frame = 0;
    let active = false;

    function position() {
        return Math.max(0, Math.min(scroller.scrollTop, scroller.scrollHeight - scroller.clientHeight));
    }

    function show() {
        header.classList.remove('layout-header-hidden');
        previous = position();
        travel = 0;
    }

    function update() {
        frame = 0;
        const current = position();
        const delta = current - previous;
        previous = current;
        const focused = document.activeElement;
        const editing = header.contains(focused) && focused.matches('input, select, textarea, [contenteditable], :focus-visible');
        if (!narrow.matches || current <= 0 || editing || header.querySelector('[aria-expanded="true"], [open]')) {
            show();
            return;
        }
        travel = Math.sign(delta) === Math.sign(travel) ? travel + delta : delta;
        // 小幅抖动不翻转状态；上划越过顶部栏后收起，下划即恢复。
        if (Math.abs(travel) >= 10) {
            header.classList.toggle('layout-header-hidden', travel > 0 && current > header.offsetHeight);
            travel = 0;
        }
    }

    function onScroll() {
        if (!frame) frame = window.requestAnimationFrame(update);
    }

    function stop() {
        active = false;
        scrollTarget.removeEventListener('scroll', onScroll);
        header.removeEventListener('focusin', show);
        window.removeEventListener('resize', show);
        narrow.removeEventListener('change', show);
        if (frame) window.cancelAnimationFrame(frame);
        frame = 0;
        show();
    }

    function start() {
        if (active) return;
        active = true;
        show();
        scrollTarget.addEventListener('scroll', onScroll, {passive: true});
        header.addEventListener('focusin', show);
        window.addEventListener('resize', show);
        narrow.addEventListener('change', show);
    }

    window.addEventListener('pagehide', stop);
    window.addEventListener('pageshow', start);
    start();
}
document.querySelectorAll('[data-layout-height="--layout-header-height"]').forEach(initLayoutHeader);
