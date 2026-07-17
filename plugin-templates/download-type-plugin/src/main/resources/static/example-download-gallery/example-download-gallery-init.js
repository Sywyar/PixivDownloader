'use strict';

async function exampleDownloadGalleryMountChrome() {
    let client = await PixivI18n.create({namespaces: ['example-download', 'common']});
    window.ExampleDownloadGallery.core.setI18n(client);
    client.apply(document);
    const mountPoint = document.getElementById('exampleDownloadGallerySwitchers');
    if (window.PixivLangSwitcher && typeof window.PixivLangSwitcher.mount === 'function') {
        await window.PixivLangSwitcher.mount({
            mountPoint: mountPoint,
            i18n: client,
            onChange: function (next) {
                client = next;
                window.ExampleDownloadGallery.core.setI18n(next);
                next.apply(document);
                window.ExampleDownloadGallery.view.render();
            }
        });
    }
    if (window.PixivTheme && typeof window.PixivTheme.mount === 'function') {
        window.PixivTheme.mount({mountPoint: mountPoint});
    }
}

(async function initExampleDownloadGallery() {
    document.getElementById('exampleDownloadGalleryRefresh').addEventListener('click', function () {
        window.ExampleDownloadGallery.core.load();
    });
    try {
        await exampleDownloadGalleryMountChrome();
    } catch (failure) {
        console.error('example-download-gallery-i18n-failed', failure);
    }
    window.ExampleDownloadGallery.view.render();
    await window.ExampleDownloadGallery.core.load();
})();
