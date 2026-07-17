'use strict';

window.ExampleDownloadGallery = window.ExampleDownloadGallery || {};

function exampleDownloadGalleryStatusText() {
    const state = window.ExampleDownloadGallery.state;
    const core = window.ExampleDownloadGallery.core;
    if (state.status === 'loading') return core.text('gallery.loading', 'Loading example records…');
    if (state.status === 'failed') return core.text('gallery.failed', 'Example records could not be loaded');
    if (state.status === 'empty') return core.text('gallery.empty', 'No completed example records');
    return '';
}

function exampleDownloadGalleryLine(value) {
    const line = document.createElement('p');
    line.textContent = value;
    return line;
}

function exampleDownloadGalleryCard(item) {
    const core = window.ExampleDownloadGallery.core;
    const card = document.createElement('article');
    card.className = 'gallery-card';
    const heading = document.createElement('h2');
    heading.textContent = String(item.title || item.id || '');
    card.appendChild(heading);
    card.appendChild(exampleDownloadGalleryLine(
        core.text('gallery.item.id', 'ID: {id}', {id: item.id || ''})));
    card.appendChild(exampleDownloadGalleryLine(
        core.text('gallery.item.owner', 'Owner: {owner}', {owner: item.ownerKey || ''})));
    card.appendChild(exampleDownloadGalleryLine(
        core.text('gallery.item.completed', 'Completed: {time}', {time: item.completedAt || ''})));
    return card;
}

function exampleDownloadGalleryRender() {
    const state = window.ExampleDownloadGallery.state;
    const status = document.getElementById('exampleDownloadGalleryStatus');
    const items = document.getElementById('exampleDownloadGalleryItems');
    status.textContent = exampleDownloadGalleryStatusText();
    while (items.firstChild) items.removeChild(items.firstChild);
    state.items.forEach(function (item) {
        items.appendChild(exampleDownloadGalleryCard(item));
    });
}

window.ExampleDownloadGallery.view = {
    render: exampleDownloadGalleryRender
};
