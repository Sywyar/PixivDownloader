'use strict';
(function () {
if (!window.PixivBatch || !window.PixivBatch.queueTypes) return;
window.PixivBatch.queueTypes.registerModule(async function (context) {
    const shared = {context, cleanups: [], timer: null};
    context.onCleanup(function () {
        if (shared.timer != null) clearTimeout(shared.timer);
        shared.timer = null;
        shared.cleanups.splice(0).reverse().forEach(function (cleanup) {
            try { cleanup(); } catch (e) { /* best effort */ }
        });
        const input = document.getElementById('douyin-cookie-input');
        if (input && input.dataset) delete input.dataset.douyinBound;
    });
    for (const moduleUrl of [
        './douyin-queue.js',
        './douyin-download.js',
        './douyin-view.js',
        './douyin-acquisition.js'
    ]) {
        await context.loadSubmodule(moduleUrl, shared);
    }
    shared.bindDouyinEvent(window, 'pixivbatch:slotsrendered', shared.hydrateDouyinUi);
    shared.bindDouyinEvent(window, 'pixivbatch:storageloaded', shared.hydrateDouyinCookieSettings);
    shared.bindDouyinEvent(window, 'pixivbatch:cookieformatchanged', function () {
        if (typeof invalidateQuickAccount === 'function') invalidateQuickAccount('douyin');
        shared.douyinUpdateCookieStatus(false, shared.douyinCookieInputHeaderString());
        if (typeof updateQuickAccountBar === 'function') updateQuickAccountBar('douyin');
    });
    shared.timer = setTimeout(shared.hydrateDouyinUi, 0);
    return {descriptor: Object.assign({}, shared.douyinDescriptor)};
});
})();
