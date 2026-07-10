'use strict';

async function initUnifiedGallery() {
    await UnifiedGallery.initI18n();
    if (window.PixivTheme) {
        PixivTheme.mount({mountPoint: document.getElementById('themeAnchor')});
    }
    document.querySelectorAll('[data-kind]').forEach(button => button.addEventListener('click', () => {
        UnifiedGallery.state.kind = button.dataset.kind;
        document.querySelectorAll('[data-kind]').forEach(item =>
                item.setAttribute('aria-selected', String(item === button)));
        UnifiedGallery.load(true);
    }));
    document.getElementById('loadMore').addEventListener('click', () => UnifiedGallery.load(false));
    document.getElementById('closeDetail').addEventListener('click', () => UnifiedGalleryViews.dialog.close());
    const first = document.querySelector('[data-kind="IMAGE"]');
    first.setAttribute('aria-selected', 'true');
    first.focus();
    await UnifiedGallery.load(true);
}

initUnifiedGallery();
