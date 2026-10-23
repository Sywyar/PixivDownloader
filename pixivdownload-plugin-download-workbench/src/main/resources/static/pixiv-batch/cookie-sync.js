'use strict';

let cookieSyncOperation = null;

function cancelCookieSync() {
    const operation = cookieSyncOperation;
    if (!operation) return;
    cookieSyncOperation = null;
    clearTimeout(operation.timer);
    operation.controller.abort();
}

async function fetchServerPixivCookie(signal) {
    try {
        const res = await fetch(BASE + '/api/batch/state', {credentials: 'same-origin', signal});
        if (!res.ok) return null;
        const data = await res.json();
        const st = data.state || {};
        return {
            cookie: st.pixiv_cookie != null ? String(st.pixiv_cookie) : '',
            fmt: st.pixiv_cookie_fmt || 'header',
            syncAt: st.pixiv_cookie_sync_at != null ? String(st.pixiv_cookie_sync_at) : '',
            syncStatus: st.pixiv_cookie_sync_status != null ? String(st.pixiv_cookie_sync_status) : ''
        };
    } catch {
        return null;
    }
}

function startCookieSync(win, baselineSyncAt, onImported, onTimeout) {
    cancelCookieSync();
    const operation = {controller: new AbortController(), timer: null};
    cookieSyncOperation = operation;
    const deadline = Date.now() + 25000;
    const poll = async () => {
        const snapshot = await fetchServerPixivCookie(operation.controller.signal);
        if (cookieSyncOperation !== operation) return;
        if (snapshot && snapshot.syncAt && snapshot.syncAt !== baselineSyncAt) {
            cancelCookieSync();
            try { win.close(); } catch {}
            onImported(snapshot);
            return;
        }
        if (Date.now() > deadline) {
            cancelCookieSync();
            onTimeout();
            return;
        }
        operation.timer = setTimeout(poll, 1500);
    };
    operation.timer = setTimeout(poll, 1500);
}

window.addEventListener('pagehide', cancelCookieSync);
