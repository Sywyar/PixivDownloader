'use strict';

// 工具栏会随译文、文字缩放和插件入口换行，媒体区域使用实际高度。
const pixivLayoutObserver = new ResizeObserver(entries => {
    for (const {target} of entries) {
        document.documentElement.style.setProperty(
            target.dataset.layoutHeight,
            `${target.getBoundingClientRect().height}px`
        );
    }
});
document.querySelectorAll('[data-layout-height]').forEach(element => pixivLayoutObserver.observe(element));
