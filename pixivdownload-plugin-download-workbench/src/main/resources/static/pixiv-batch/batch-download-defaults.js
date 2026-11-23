'use strict';
window.PixivBatch = window.PixivBatch || {};
window.PixivBatch.downloadDefaults = Object.freeze({
    interval: 2, intervalUnit: 's', concurrent: 1, skipHistory: false
});

window.PixivBatch.defaultSearchPageDelay = maxPages => {
    const defaults = window.PixivBatch.pixivScheduleDefaults.search;
    return Number(maxPages) === -1 ? defaults.incrementalPageDelayMs : defaults.fixedPageDelayMs;
};
