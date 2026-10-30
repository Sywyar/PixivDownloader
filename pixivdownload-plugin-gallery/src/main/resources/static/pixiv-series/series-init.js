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

    function seriesSlotProperties() {
        return {seriesId: state.seriesId, novel: isNovelMode(), contentLanguages: seriesTranslatedLangs, contentLanguage: activeContentLang || ''};
    }

    async function bootstrapSeriesUiSlots() {
        if (!window.PixivUiSlots || typeof window.PixivUiSlots.bootstrap !== 'function') return;
        await window.PixivUiSlots.bootstrap({
            targetPrefix: 'series-detail-', properties: seriesSlotProperties(),
            regions: {'content-language': document.getElementById('contentLangAnchor')},
            i18n: () => pageI18n,
            async onEvent(name, value) {
                if (name === 'message') setSeriesMessage(value.message);
                if (name === 'content-language') return selectSeriesContentLanguage(value.language);
                if (name !== 'translated') return;
                if (!seriesTranslatedLangs.includes(value.langCode)) seriesTranslatedLangs = seriesTranslatedLangs.concat([value.langCode]);
                activeContentLang = value.langCode;
                window.PixivUiSlots?.update('series-detail-', seriesSlotProperties());
                delete seriesTitleByLang[activeContentLang];
                delete seriesDescriptionByLang[activeContentLang];
                delete chapterTitlesByLang[activeContentLang];
                let failed = false;
                try { await Promise.all([fetchSeriesTitleForLang(activeContentLang), fetchChapterTitlesForLang(activeContentLang, state.items)]); }
                catch (_) { failed = true; }
                renderSeriesHeader(); renderGrid(state.items);
                return failed;
            }
        });
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
