'use strict';

    document.getElementById('mobileMenuBtn').addEventListener('click', openMobileSidebar);
    document.getElementById('mobileOverlay').addEventListener('click', closeMobileSidebar);
    document.getElementById('translateSeriesBtn').addEventListener('click', translateSeries);
    document.getElementById('downloadMergedBtn').addEventListener('click', openDownloadMergedDialog);
    document.getElementById('searchInput').addEventListener('input', e => {
        clearTimeout(searchTimer);
        searchTimer = setTimeout(() => {
            state.search = e.target.value.trim();
            state.page = 0;
            loadArtworks();
        }, 220);
    });

    (async function init() {
        readParams();
        restoreSidebarState();
        setupAdminMode();
        await initPageI18n();
        loadCollections();
        await loadSeries();
    })();
