'use strict';
(function () {
if (!window.PixivBatch || !window.PixivBatch.queueTypes) return;
window.PixivBatch.queueTypes.registerSubmodule(function (shared) {
const {
    douyinAssertActive, bindDouyinEvent, DOUYIN_PAGE_SIZE, DOUYIN_FAVORITE_FOLDER_SERIES_PREFIX,
    DOUYIN_USER_KIND_WORKS, DOUYIN_USER_KIND_LIKED, DOUYIN_USER_KINDS, DOUYIN_COOKIE_REQUIRED_KEYS,
    DOUYIN_COOKIE_SESSION_KEYS, DOUYIN_COOKIE_SESSION_LABEL, DOUYIN_COOKIE_SUGGESTED_KEYS, douyinStoreGet,
    douyinStoreSet, douyinStoreRemove, douyinCookieFacade, douyinStoredCookieRaw,
    douyinSetStoredCookieRaw, douyinRemoveStoredCookieRaw, douyinCookieRawToHeaderString, douyinCookie,
    douyinCookieInputHeaderString, douyinParseCookieFields, douyinValidateCookie, douyinCookieValidationMessage,
    douyinUpdateCookieStatus, douyinEnsureCookieReady, douyinAcquisitionCredentialHeaders, douyinI18nKey,
    douyinText, formatDouyinSearchStats, douyinExtractUrl, douyinSafeId,
    douyinCancelWorkKey, douyinParseInput, douyinParseUserInput, douyinUserKind,
    douyinQueueId, douyinCardId, DOUYIN_QUEUE_MEDIA_KINDS, DOUYIN_QUEUE_SOURCE_TAGS,
    douyinQueueMediaKind, douyinQueueMediaCount, douyinQueueMeta, douyinQueueTypeData,
    DOUYIN_MAX_SOURCE_RELATIONS, douyinLimitedSourceText, douyinNormalizeSourceRelation, douyinSourceRelationKey,
    douyinMergeSourceRelation, douyinAppendSourceRelation, douyinNormalizeQueueTypeData, douyinMergeQueueTypeData,
    douyinSourceRelationsFingerprint, douyinInputFromQueueItem, douyinCanonicalQueueItemUrl, douyinQueueTags
} = shared;
function douyinLinkedAbortSignal(...candidates) {
    const signals = candidates.filter(signal => signal && typeof signal.addEventListener === 'function');
    if (signals.length <= 1) {
        return {signal: signals[0] || null, dispose() {}};
    }
    const controller = new AbortController();
    const listeners = [];
    const abortFrom = signal => {
        if (!controller.signal.aborted) controller.abort(signal.reason);
    };
    signals.forEach(signal => {
        if (signal.aborted) {
            abortFrom(signal);
            return;
        }
        const listener = () => abortFrom(signal);
        signal.addEventListener('abort', listener, {once: true});
        listeners.push([signal, listener]);
    });
    return {
        signal: controller.signal,
        dispose() {
            listeners.forEach(([signal, listener]) => signal.removeEventListener('abort', listener));
        }
    };
}

async function douyinFetchJson(path, options) {
    douyinAssertActive();
    const request = Object.assign({}, options || {});
    const headers = Object.assign({}, request.headers || {});
    Object.keys(headers).forEach(name => {
        const normalized = name.toLowerCase();
        if (normalized === 'x-pixiv-cookie'
            || normalized === 'x-douyin-cookie'
            || normalized === 'x-acquisition-credential') {
            delete headers[name];
        }
    });
    Object.assign(headers, douyinAcquisitionCredentialHeaders(douyinEnsureCookieReady()));
    request.credentials = request.credentials || 'same-origin';
    request.headers = headers;
    const signalLease = douyinLinkedAbortSignal(request.signal, shared.context.signal);
    request.signal = signalLease.signal;
    try {
        const res = await fetch(`${BASE}${path}`, request);
        douyinAssertActive();
        const data = await res.json().catch(() => ({}));
        douyinAssertActive();
        if (!res.ok) {
            const key = data.messageKey ? douyinI18nKey(data.messageKey) : 'douyin:error.request-failed';
            const error = new Error(bt(key, data.error || `HTTP ${res.status}`));
            error.code = data.code || null;
            error.messageKey = data.messageKey || null;
            error.status = res.status;
            throw error;
        }
        return data;
    } finally {
        signalLease.dispose();
    }
}

async function processDouyinItem(item, processContext) {
    douyinAssertActive();
    item.lastMessage = douyinText('status.queued', 'Queued');
    item.totalImages = 1;
    item.downloadedCount = 0;
    setCurrent(item);
    renderQueue();
    try {
        const cookie = douyinEnsureCookieReady();
        downloadAttempt:
        for (;;) {
            const queueTypeData = douyinNormalizeQueueTypeData(douyinQueueTypeData(item));
            const sentRelationsFingerprint = douyinSourceRelationsFingerprint(queueTypeData);
            item.typeData = queueTypeData;
            const res = await fetch(`${BASE}/api/douyin/download`, {
                method: 'POST',
                credentials: 'same-origin',
                headers: Object.assign({'Content-Type': 'application/json'},
                    douyinAcquisitionCredentialHeaders(cookie)),
                signal: shared.context.signal,
                body: JSON.stringify({
                    input: douyinInputFromQueueItem(item),
                    title: item.title || '',
                    cookie: null,
                    collectionId: (queueTypeData.seriesId || item.seriesId || null),
                    collectionTitle: (queueTypeData.seriesTitle || item.seriesTitle || null),
                    sourceType: queueTypeData.sourceType || null,
                    sourceId: queueTypeData.sourceId || null,
                    sourceTitle: queueTypeData.sourceTitle || null,
                    sourceUrl: queueTypeData.sourceUrl || null,
                    sourceOrder: queueTypeData.sourceOrder ?? null,
                    sourceRelations: queueTypeData.sourceRelations
                })
            });
            douyinAssertActive();
            const data = await res.json().catch(() => ({}));
            douyinAssertActive();
            if (!res.ok) {
                const key = data.messageKey ? douyinI18nKey(data.messageKey) : 'douyin:error.request-failed';
                throw new Error(bt(key, data.error || `HTTP ${res.status}`));
            }
            const authoritativeWorkKey = douyinCancelWorkKey(data.workId);
            if (authoritativeWorkKey && processContext
                && typeof processContext.updateItem === 'function') {
                processContext.updateItem({cancelWorkKey: authoritativeWorkKey});
            }
            const statusId = data.id;
            item.douyinStatusId = statusId;
            const start = Date.now();
            while (Date.now() - start < STATUS_TIMEOUT_MS) {
                await sleep(800);
                douyinAssertActive();
                const statusRes = await fetch(`${BASE}/api/douyin/status/${encodeURIComponent(statusId)}`, {
                    credentials: 'same-origin',
                    signal: shared.context.signal
                });
                douyinAssertActive();
                if (!statusRes.ok) continue;
                const status = await statusRes.json();
                douyinAssertActive();
                if (status.messageKey) {
                    item.lastMessage = bt(douyinI18nKey(status.messageKey), status.messageKey);
                }
                if (status.title) item.title = status.title;
                if (status.completed) {
                    if (!status.failed && !status.cancelled) {
                        const latestQueueTypeData = douyinNormalizeQueueTypeData(douyinQueueTypeData(item));
                        item.typeData = latestQueueTypeData;
                        if (douyinSourceRelationsFingerprint(latestQueueTypeData) !== sentRelationsFingerprint) {
                            item.lastMessage = douyinText('status.queued', 'Queued');
                            saveQueue();
                            renderQueue();
                            continue downloadAttempt;
                        }
                    }
                    item.endTime = new Date().toISOString();
                    if (status.failed || status.cancelled) {
                        item.status = status.cancelled ? 'skipped' : 'failed';
                        item.lastMessage = bt(douyinI18nKey(status.messageKey), status.messageKey);
                    } else {
                        item.status = 'completed';
                        item.downloadedCount = 1;
                        item.lastMessage = douyinText('status.completed', 'Completed');
                    }
                    updateStats();
                    saveQueue();
                    renderQueue();
                    return;
                }
                renderQueue();
            }
            item.status = 'failed';
            item.lastMessage = bt('queue.message.timeout', 'Timed out');
            item.endTime = new Date().toISOString();
            updateStats();
            saveQueue();
            renderQueue();
            return;
        }
    } catch (e) {
        if (!shared.context || !shared.context.isActive()) throw e;
        item.status = 'failed';
        item.lastMessage = bt('queue.message.failed', 'Failed - {message}', {message: e.message || String(e)});
        item.endTime = new Date().toISOString();
        updateStats();
        saveQueue();
        renderQueue();
        throw e;
    }
}

Object.assign(shared, {
    douyinLinkedAbortSignal, douyinFetchJson, processDouyinItem
});
});
})();
