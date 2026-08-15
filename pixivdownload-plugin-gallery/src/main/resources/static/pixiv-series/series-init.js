'use strict';

    function hideSeriesCover() {
        document.getElementById('seriesCover').style.display = 'none';
    }

    function replaceChapterCover(image) {
        const placeholder = document.createElement('div');
        placeholder.className = 'chapter-cover-placeholder';
        placeholder.textContent = image.alt;
        image.replaceWith(placeholder);
    }

    PixivActions.bind(document, { error: {hideSeriesCover, replaceChapterCover} });

    document.getElementById('mobileMenuBtn').addEventListener('click', openMobileSidebar);
    document.getElementById('mobileOverlay').addEventListener('click', closeMobileSidebar);
    document.getElementById('downloadMergedBtn').addEventListener('click', openDownloadMergedDialog);
    document.getElementById('searchInput').addEventListener('input', e => {
        clearTimeout(searchTimer);
        searchTimer = setTimeout(() => {
            state.search = e.target.value.trim();
            state.page = 0;
            loadArtworks();
        }, 220);
    });

    async function bootstrapSeriesUiSlots() {
        if (!window.PixivUiSlots || typeof window.PixivUiSlots.bootstrap !== 'function') return;
        await window.PixivUiSlots.bootstrap({ targetPrefix: 'series-detail-' });
    }

    (async function init() {
        readParams();
        restoreSidebarState();
        setupAdminMode();
        await initPageI18n();
        loadCollections();
        await loadSeries();
        await bootstrapSeriesUiSlots();
    })();
