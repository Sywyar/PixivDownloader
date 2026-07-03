'use strict';

    function bindPageEvents() {
        document.getElementById('lightbox').addEventListener('click', handleLightboxClick);
        document.getElementById('lightboxClose').addEventListener('click', closeLightbox);
        document.getElementById('lightboxPrev').addEventListener('click', event => {
            event.stopPropagation();
            lightboxNav(-1);
        });
        document.getElementById('lightboxNext').addEventListener('click', event => {
            event.stopPropagation();
            lightboxNav(1);
        });
        document.getElementById('modalCloseBtn').addEventListener('click', closeAddToCollectionModal);
        document.getElementById('addToCollectionDoneBtn').addEventListener('click', closeAddToCollectionModal);
    }

    (async function initShowcasePage() {
        await initPageI18n();
        bindPageEvents();
        loadArtwork();
    })();
