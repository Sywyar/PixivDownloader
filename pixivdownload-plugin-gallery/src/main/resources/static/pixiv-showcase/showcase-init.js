'use strict';

    function bindPageEvents() {
        document.getElementById('relatedPrev').addEventListener('click', () => changeRelatedPage(-1));
        document.getElementById('relatedNext').addEventListener('click', () => changeRelatedPage(1));
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
    }

    (async function initShowcasePage() {
        await initPageI18n();
        bindPageEvents();
        loadArtwork();
    })();
