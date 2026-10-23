'use strict';

function ensureSharedSSE() {
    ensureDownloadSSE(message => setDockStatus(message, 'error'));
}

function mergeUgoiraProgress(existing, incoming) {
    return incoming === undefined ? existing || null : incoming ? {...incoming} : null;
}
