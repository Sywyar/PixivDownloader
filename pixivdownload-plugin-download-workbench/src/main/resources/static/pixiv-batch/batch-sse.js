'use strict';

    function ensureSharedSSE() {
        ensureDownloadSSE();
    }

// ---- PixivBatch facade ----
window.PixivBatch.sse = window.PixivBatch.sse || {};
window.PixivBatch.sse = Object.assign(window.PixivBatch.sse, { ensureSharedSSE, closeSharedSSE, openSSE, closeSSE, closeAllSSE, addSSEListener, removeSSEListener, waitForFinalStatusBySSE });
