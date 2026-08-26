'use strict';
(function () {
if (!window.PixivBatch || !window.PixivBatch.queueTypes) return;
window.PixivBatch.queueTypes.registerModule(async function (context) {
    const shared = {context, cleanups: []};
    context.onCleanup(function () {
        shared.cleanups.splice(0).reverse().forEach(function (cleanup) {
            try { cleanup(); } catch (e) { /* best effort */ }
        });
    });
    for (const moduleUrl of [
        './novel-queue-acquisition.js',
        './novel-queue-download.js',
        './novel-queue-search.js',
        './novel-queue-view.js'
    ]) {
        await context.loadSubmodule(moduleUrl, shared);
    }
    shared.cleanups.push(shared.disposeNovelSearch);
    return {descriptor: shared.buildNovelDescriptor(context)};
});
})();
