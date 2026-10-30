window.officialSlotErrors = [];
window.officialSlotEvents = [];
const i18n = {t: (key, fallback) => fallback || key, apply() {}, locale: 'en-US'};
window.officialPages = ['novel', 'series'].map(id => PixivUiSlots.connect({
    root: document.getElementById(id), targetPrefix: id + '-detail-ai-translate', i18n: () => i18n,
    properties: {novelId: 'fixture-novel', seriesId: 'fixture-series', novel: true, language: 'en',
        contentLanguage: 'en', contentLanguages: ['en', 'ja']},
    regions: {content: document.getElementById('content'), 'content-language': document.getElementById(id + '-language')},
    onEvent: (name, value) => officialSlotEvents.push({name, value}),
    onError: error => officialSlotErrors.push(error.message)
}));
Promise.all(officialPages.map(page => page.refresh())).then(async () => {
    const tts = PixivUiSlots.connect({
        root: document.getElementById('novel'), targetPrefix: 'novel-detail-tts', i18n: () => i18n,
        properties: {novelId: 'fixture-novel', language: 'en', contentLanguage: 'en'},
        regions: {content: document.getElementById('content')},
        onEvent: (name, value) => officialSlotEvents.push({name, value}),
        onError: error => officialSlotErrors.push(error.message)
    });
    officialPages.push(tts);
    await tts.refresh();
    window.officialSlotsReady = true;
});
