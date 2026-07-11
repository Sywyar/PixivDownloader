'use strict';

function douyinGalleryCloseSidebar() {
    document.getElementById('douyinGallerySidebar').classList.remove('mobile-open');
    document.getElementById('douyinGalleryOverlay').classList.remove('active');
}

function douyinGalleryOpenSidebar() {
    document.getElementById('douyinGallerySidebar').classList.add('mobile-open');
    document.getElementById('douyinGalleryOverlay').classList.add('active');
}

function douyinGalleryWireEvents() {
    document.getElementById('douyinGalleryMenu').addEventListener('click', douyinGalleryOpenSidebar);
    document.getElementById('douyinGalleryOverlay').addEventListener('click', douyinGalleryCloseSidebar);
    document.getElementById('douyinGalleryLoadMore').addEventListener('click', () => {
        window.PixivDouyinGallery.core.loadPage(true);
    });
    document.querySelectorAll('[data-gallery-view]').forEach(link => {
        link.addEventListener('click', event => {
            if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
            event.preventDefault();
            douyinGalleryCloseSidebar();
            window.PixivDouyinGallery.core.selectView(link.getAttribute('data-gallery-view'));
        });
    });
    window.addEventListener('popstate', () => {
        const next = window.PixivDouyinGallery.core.readView(window.location.search);
        window.PixivDouyinGallery.core.selectView(next);
    });
}

async function douyinGalleryMountChrome() {
    let pageI18n = await PixivI18n.create({namespaces: ['douyin', 'common']});
    window.PixivDouyinGallery.core.setI18n(pageI18n);
    pageI18n.apply(document);
    await PixivLangSwitcher.mount({
        mountPoint: document.getElementById('douyinGallerySwitchers'),
        i18n: pageI18n,
        onChange(next) {
            pageI18n = next;
            window.PixivDouyinGallery.core.setI18n(next);
            next.apply(document);
            window.PixivDouyinGallery.view.render();
            if (window.PixivNav && typeof window.PixivNav.refresh === 'function') {
                window.PixivNav.refresh();
            }
        }
    });
    PixivTheme.mount({mountPoint: document.getElementById('douyinGallerySwitchers')});
}

(async function init() {
    try {
        window.PixivDouyinGallery.state.view =
            window.PixivDouyinGallery.core.readView(window.location.search);
        window.PixivDouyinGallery.core.updateLocation(window.PixivDouyinGallery.state.view);
        douyinGalleryWireEvents();
        await douyinGalleryMountChrome();
        window.PixivDouyinGallery.view.render();
        await window.PixivDouyinGallery.core.loadPage(false);
    } catch (failure) {
        window.PixivDouyinGallery.core.setStatus('failed');
        window.PixivDouyinGallery.view.render();
        console.error('douyin-gallery-init-failed', failure);
    }
})();
