'use strict';

window.ExampleDownloadGallery = window.ExampleDownloadGallery || {};

const exampleDownloadGalleryState = {
    items: [],
    status: 'loading'
};

let exampleDownloadGalleryI18n = null;

function exampleDownloadGallerySetI18n(client) {
    exampleDownloadGalleryI18n = client || null;
}

function exampleDownloadGalleryText(key, fallback, values) {
    return exampleDownloadGalleryI18n && typeof exampleDownloadGalleryI18n.t === 'function'
        ? exampleDownloadGalleryI18n.t('example-download:' + key, fallback, values || {})
        : fallback;
}

async function exampleDownloadGalleryLoad() {
    exampleDownloadGalleryState.status = 'loading';
    window.ExampleDownloadGallery.view.render();
    try {
        const response = await fetch('/api/example-download/gallery', {
            credentials: 'same-origin',
            headers: {'Accept': 'application/json'}
        });
        if (!response.ok) throw new Error('HTTP_' + response.status);
        const payload = await response.json();
        exampleDownloadGalleryState.items = Array.isArray(payload && payload.items)
            ? payload.items.slice() : [];
        exampleDownloadGalleryState.status = exampleDownloadGalleryState.items.length
            ? 'ready' : 'empty';
    } catch (failure) {
        exampleDownloadGalleryState.items = [];
        exampleDownloadGalleryState.status = 'failed';
        console.error('example-download-gallery-load-failed', failure);
    }
    window.ExampleDownloadGallery.view.render();
}

window.ExampleDownloadGallery.state = exampleDownloadGalleryState;
window.ExampleDownloadGallery.core = {
    setI18n: exampleDownloadGallerySetI18n,
    text: exampleDownloadGalleryText,
    load: exampleDownloadGalleryLoad
};
