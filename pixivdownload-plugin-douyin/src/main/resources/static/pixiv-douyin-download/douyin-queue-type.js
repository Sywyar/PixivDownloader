'use strict';
(function () {
/* global BASE, bt, esc, state, sleep, setCurrent, renderQueue, updateStats, saveQueue, setStatus,
          addUserItemToQueue, addSearchItemToQueue, addSeriesItemToQueue, quickLoad, quickToggleItemQueue,
          quickState, quickSetTitle, quickShowToolbar, quickRenderOuterWorks, renderQuickPagination,
          renderQuickCollectionGrid, updateExtraFiltersCardVisibility, updateSaveScheduleCardVisibility,
          applyNovelSettingsVisibility, loadQuickMyWorks, invalidateQuickAccount */

let _activationContext = null;
let _douyinTimer = null;
const _douyinCleanups = [];

function douyinAssertActive() {
    if (!_activationContext || !_activationContext.isActive()) {
        throw new Error('douyin queue type activation is stale');
    }
}

function bindDouyinEvent(target, eventName, handler) {
    if (!target || typeof target.addEventListener !== 'function') return;
    target.addEventListener(eventName, handler);
    _douyinCleanups.push(() => {
        if (typeof target.removeEventListener === 'function') target.removeEventListener(eventName, handler);
    });
}

const DOUYIN_PAGE_SIZE = 24;
const DOUYIN_COOKIE_REQUIRED_KEYS = ['ttwid', 'passport_csrf_token'];
const DOUYIN_COOKIE_SESSION_KEYS = ['sessionid', 'sessionid_ss', 'sid_tt', 'sid_guard'];
const DOUYIN_COOKIE_SESSION_LABEL = 'sessionid / sessionid_ss / sid_tt / sid_guard';
const DOUYIN_COOKIE_SUGGESTED_KEYS = ['msToken', 'odin_tt', 'sid_guard', 'sessionid', 'sid_tt'];

function douyinStoreGet(key) {
    if (typeof storeGet === 'function') return storeGet(key);
    try { return localStorage.getItem(key) || ''; } catch { return ''; }
}

function douyinStoreSet(key, value) {
    if (typeof storeSet === 'function') {
        storeSet(key, value || '');
        return;
    }
    try { localStorage.setItem(key, value || ''); } catch {}
}

function douyinStoreRemove(key) {
    if (typeof storeRemove === 'function') {
        storeRemove(key);
        return;
    }
    try { localStorage.removeItem(key); } catch {}
}

function douyinCookieFacade() {
    return window.PixivBatch && window.PixivBatch.cookie ? window.PixivBatch.cookie : null;
}

function douyinStoredCookieRaw() {
    const api = douyinCookieFacade();
    if (api && typeof api.getStoredCookie === 'function') {
        return api.getStoredCookie('douyin') || '';
    }
    return douyinStoreGet('pixiv_douyin_cookie');
}

function douyinSetStoredCookieRaw(raw) {
    const api = douyinCookieFacade();
    if (api && typeof api.setStoredCookie === 'function') {
        api.setStoredCookie('douyin', raw || '');
        return;
    }
    douyinStoreSet('pixiv_douyin_cookie', raw || '');
}

function douyinRemoveStoredCookieRaw() {
    const api = douyinCookieFacade();
    if (api && typeof api.removeStoredCookie === 'function') {
        api.removeStoredCookie('douyin');
        return;
    }
    douyinStoreRemove('pixiv_douyin_cookie');
}

function douyinCookieRawToHeaderString(raw) {
    const api = douyinCookieFacade();
    if (api && typeof api.parseCookieToHeaderString === 'function' && typeof api.getCookieFmt === 'function') {
        return api.parseCookieToHeaderString(raw || '', api.getCookieFmt());
    }
    return raw || '';
}

function douyinCookie() {
    const api = douyinCookieFacade();
    if (api && typeof api.getCookieHeaderStringFor === 'function') {
        return api.getCookieHeaderStringFor('douyin') || '';
    }
    return douyinCookieRawToHeaderString(douyinStoredCookieRaw());
}

function douyinCookieInputHeaderString() {
    const input = document.getElementById('douyin-cookie-input');
    return douyinCookieRawToHeaderString(input ? input.value.trim() : douyinStoredCookieRaw());
}

function douyinParseCookieFields(cookie) {
    const fields = {};
    String(cookie || '').split(';').forEach(part => {
        const idx = part.indexOf('=');
        if (idx <= 0) return;
        const key = part.substring(0, idx).trim().toLowerCase();
        const value = part.substring(idx + 1).trim();
        if (key) fields[key] = value;
    });
    return fields;
}

function douyinValidateCookie(cookie) {
    const value = String(cookie || '').trim();
    const fields = douyinParseCookieFields(value);
    const hasValue = key => Object.prototype.hasOwnProperty.call(fields, String(key).toLowerCase())
        && String(fields[String(key).toLowerCase()] || '').trim() !== '';
    const missing = DOUYIN_COOKIE_REQUIRED_KEYS.filter(key => !hasValue(key));
    if (value && !DOUYIN_COOKIE_SESSION_KEYS.some(key => hasValue(key))) {
        missing.push(DOUYIN_COOKIE_SESSION_LABEL);
    }
    const suggestedMissing = DOUYIN_COOKIE_SUGGESTED_KEYS.filter(key => !hasValue(key));
    return {
        empty: !value,
        ok: value !== '' && missing.length === 0,
        missing,
        suggestedMissing
    };
}

function douyinCookieValidationMessage(validation) {
    if (validation.empty) {
        return douyinText('settings.cookie.empty', 'Douyin Cookie is empty');
    }
    if (validation.missing.length) {
        return douyinText('settings.cookie.missing', 'Douyin Cookie is missing required fields: {fields}', {
            fields: validation.missing.join(', ')
        });
    }
    if (validation.suggestedMissing.length) {
        return douyinText('settings.cookie.optional-missing', 'Douyin Cookie is usable. Suggested fields: {fields}', {
            fields: validation.suggestedMissing.join(', ')
        });
    }
    return douyinText('settings.cookie.ok', 'Douyin Cookie fields look complete');
}

function douyinUpdateCookieStatus(showSuggested, cookieHeader) {
    const status = document.getElementById('douyin-cookie-status');
    if (!status) return;
    const validation = douyinValidateCookie(cookieHeader == null ? douyinCookie() : cookieHeader);
    status.classList.remove('is-ok', 'is-warning', 'is-error');
    status.textContent = douyinCookieValidationMessage(validation);
    if (!validation.ok) {
        status.classList.add('is-error');
    } else if (showSuggested && validation.suggestedMissing.length) {
        status.classList.add('is-warning');
    } else {
        status.classList.add('is-ok');
    }
}

function douyinEnsureCookieReady() {
    const cookie = douyinCookie();
    const validation = douyinValidateCookie(cookie);
    douyinUpdateCookieStatus(true);
    if (!validation.ok) {
        throw new Error(douyinCookieValidationMessage(validation));
    }
    return cookie;
}

function douyinAcquisitionCredentialHeaders(credential = douyinCookie()) {
    return credential ? {'X-Acquisition-Credential': credential} : {};
}

function douyinI18nKey(key) {
    if (!key) return 'douyin:error.unknown';
    return String(key).startsWith('douyin.') ? 'douyin:' + String(key).substring('douyin.'.length) : key;
}

function douyinText(key, fallback, args) {
    return bt('douyin:' + key, fallback, args || {});
}

function douyinExtractUrl(text) {
    const value = String(text || '').trim();
    const m = value.match(/https?:\/\/[^\s<>"'，。；、,;]+/);
    if (!m) {
        const bare = value.match(/(?:v\.douyin\.com|v\.iesdouyin\.com|iesdouyin\.com)\/[^\s<>"'，。；、,;]+/i);
        return bare ? 'https://' + bare[0].replace(/[)\]\},.;，。；]+$/g, '') : '';
    }
    return m[0].replace(/[)\]\},.;，。；]+$/g, '');
}

function douyinSafeId(value) {
    return String(value || '').replace(/[^A-Za-z0-9_-]+/g, '_') || 'unknown';
}

function douyinParseInput(text) {
    const raw = String(text || '').trim();
    if (/^\d{5,}$/.test(raw)) {
        return {kind: 'single', id: raw, workId: raw, url: `https://www.douyin.com/video/${raw}`};
    }
    const url = douyinExtractUrl(raw) || raw;
    let parsed;
    try {
        parsed = new URL(url);
    } catch {
        return null;
    }
    const host = parsed.hostname.toLowerCase();
    if (!(host === 'douyin.com' || host.endsWith('.douyin.com')
        || host === 'iesdouyin.com' || host.endsWith('.iesdouyin.com'))) {
        return null;
    }
    const path = parsed.pathname || '';
    if (host === 'v.douyin.com' || host === 'v.iesdouyin.com' || host === 'iesdouyin.com') {
        const code = path.split('/').filter(Boolean)[0];
        return code ? {kind: 'short', id: 'short-' + douyinSafeId(code), workId: code, url: parsed.href} : null;
    }
    const modalId = parsed.searchParams.get('modal_id');
    if (modalId && /^\d{5,}$/.test(modalId)) {
        return {kind: 'single', id: modalId, workId: modalId, url: `https://www.douyin.com/video/${modalId}`};
    }
    let m = path.match(/^\/(?:video|note|gallery|slides)\/([^/?#]+)/)
        || path.match(/^\/share\/(?:video|note|gallery|slides)\/([^/?#]+)/);
    if (m) return {kind: 'single', id: m[1], workId: m[1], url: parsed.href};
    m = path.match(/^\/user\/([^/?#]+)/);
    if (m) return {kind: 'user', id: m[1], userId: m[1], url: parsed.href};
    m = path.match(/^\/(?:collection|mix)\/([^/?#]+)/);
    if (m) return {kind: 'series', id: m[1], seriesId: m[1], url: parsed.href};
    m = path.match(/^\/music\/([^/?#]+)/);
    if (m) return {kind: 'music', id: m[1], musicId: m[1], url: parsed.href};
    return null;
}

function douyinParseUserInput(text) {
    const raw = String(text || '').trim();
    const parsed = douyinParseInput(raw);
    if (parsed && parsed.kind === 'user') return parsed.userId;
    return /^[A-Za-z0-9._-]{6,256}$/.test(raw) ? raw : null;
}

function douyinQueueId(item) {
    const sourceKind = item && (item.sourceKind || (item.typeData && item.typeData.sourceKind));
    const prefix = sourceKind && !['single', 'short'].includes(sourceKind)
        ? `d${douyinSafeId(sourceKind)}-` : 'd';
    return prefix + douyinSafeId(item.id || item.workId || item.douyinId);
}

function douyinCardId(prefix, idx) {
    return `${prefix}-douyin-card-${idx}`;
}

function douyinQueueMeta(item) {
    const douyinId = String(item.id || item.workId || item.douyinId || '');
    const url = item.url || item.pageUrl || '';
    return {
        title: item.title || douyinText('queue.fallback', 'Douyin {id}', {id: item.id}),
        douyinId,
        kind: 'douyin',
        url,
        authorId: item.userId || item.authorId || null,
        authorName: item.userName || item.authorName || '',
        typeData: douyinNormalizeQueueTypeData({
            input: url || douyinId,
            url,
            douyinId,
            seriesId: item.seriesId || null,
            seriesTitle: item.seriesTitle || '',
            sourceType: item.sourceType || null,
            sourceId: item.sourceId || null,
            sourceTitle: item.sourceTitle || '',
            sourceUrl: item.sourceUrl || null,
            sourceOrder: Number.isInteger(item.sourceOrder) ? item.sourceOrder : null
        })
    };
}

function douyinQueueTypeData(item) {
    if (item && item.typeData && typeof item.typeData === 'object') return item.typeData;
    if (item && item.pluginData && typeof item.pluginData === 'object') return item.pluginData;
    return {};
}

const DOUYIN_MAX_SOURCE_RELATIONS = 64;

function douyinLimitedSourceText(value, maxLength) {
    const normalized = value == null ? '' : String(value).trim();
    if (!normalized) return null;
    return normalized.length <= maxLength ? normalized : normalized.substring(0, maxLength);
}

function douyinNormalizeSourceRelation(value) {
    if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
    const sourceType = douyinLimitedSourceText(value.sourceType, 80);
    const sourceId = douyinLimitedSourceText(value.sourceId, 512);
    if (!sourceType || !sourceId) return null;
    return {
        sourceType,
        sourceId,
        sourceTitle: douyinLimitedSourceText(value.sourceTitle, 500),
        sourceUrl: douyinLimitedSourceText(value.sourceUrl, 2048),
        sourceOrder: Number.isSafeInteger(value.sourceOrder) ? value.sourceOrder : null
    };
}

function douyinSourceRelationKey(relation) {
    return relation.sourceType + '\u0000' + relation.sourceId;
}

function douyinMergeSourceRelation(existing, incoming) {
    return {
        sourceType: existing.sourceType,
        sourceId: existing.sourceId,
        sourceTitle: existing.sourceTitle || incoming.sourceTitle || null,
        sourceUrl: existing.sourceUrl || incoming.sourceUrl || null,
        sourceOrder: existing.sourceOrder == null ? incoming.sourceOrder : existing.sourceOrder
    };
}

function douyinAppendSourceRelation(relations, indexes, value) {
    const relation = douyinNormalizeSourceRelation(value);
    if (!relation) return;
    const key = douyinSourceRelationKey(relation);
    const existingIndex = indexes.get(key);
    if (existingIndex != null) {
        relations[existingIndex] = douyinMergeSourceRelation(relations[existingIndex], relation);
        return;
    }
    if (relations.length >= DOUYIN_MAX_SOURCE_RELATIONS) return;
    indexes.set(key, relations.length);
    relations.push(relation);
}

function douyinNormalizeQueueTypeData(value) {
    const data = value && typeof value === 'object' && !Array.isArray(value)
        ? Object.assign({}, value) : {};
    const relations = [];
    const indexes = new Map();
    if (Array.isArray(data.sourceRelations)) {
        data.sourceRelations.forEach(relation => douyinAppendSourceRelation(relations, indexes, relation));
    }
    douyinAppendSourceRelation(relations, indexes, data);
    data.sourceRelations = relations;
    if (relations.length) {
        const primary = relations[0];
        data.sourceType = primary.sourceType;
        data.sourceId = primary.sourceId;
        data.sourceTitle = primary.sourceTitle || '';
        data.sourceUrl = primary.sourceUrl || null;
        data.sourceOrder = primary.sourceOrder;
    }
    return data;
}

function douyinMergeQueueTypeData(currentValue, incomingValue) {
    const currentData = douyinNormalizeQueueTypeData(currentValue);
    const incomingData = douyinNormalizeQueueTypeData(incomingValue);
    const currentKeys = new Set(currentData.sourceRelations.map(douyinSourceRelationKey));
    const keepExisting = incomingData.sourceRelations.some(
        relation => !currentKeys.has(douyinSourceRelationKey(relation))
    );
    const merged = Object.assign({}, incomingData, currentData);
    Object.keys(incomingData).forEach(key => {
        if (merged[key] == null || merged[key] === '') merged[key] = incomingData[key];
    });
    const relations = [];
    const indexes = new Map();
    currentData.sourceRelations.forEach(relation => douyinAppendSourceRelation(relations, indexes, relation));
    incomingData.sourceRelations.forEach(relation => douyinAppendSourceRelation(relations, indexes, relation));
    merged.sourceRelations = relations;
    const typeData = douyinNormalizeQueueTypeData(merged);
    const reprocessExisting = typeData.sourceRelations.some(
        relation => !currentKeys.has(douyinSourceRelationKey(relation))
    );
    return {typeData, keepExisting, reprocessExisting};
}

function douyinSourceRelationsFingerprint(value) {
    return douyinNormalizeQueueTypeData(value).sourceRelations
        .map(douyinSourceRelationKey)
        .join('\u0001');
}

function douyinInputFromQueueItem(item) {
    const data = douyinQueueTypeData(item);
    const direct = data.input || data.url || data.pageUrl || item.url || item.pageUrl || item.originalUrl;
    if (direct) return String(direct);
    const douyinId = data.douyinId || data.workId || item.douyinId || item.workId;
    if (douyinId) return `https://www.douyin.com/video/${encodeURIComponent(String(douyinId))}`;
    const id = String(item.id || '');
    if (id.startsWith('dshort-')) return `https://v.douyin.com/${encodeURIComponent(id.substring('dshort-'.length))}/`;
    if (/^d\d+$/.test(id)) return `https://www.douyin.com/video/${encodeURIComponent(id.substring(1))}`;
    return id;
}

function douyinCanonicalQueueItemUrl(item) {
    const data = douyinQueueTypeData(item);
    const direct = data.input || data.url;
    if (direct && /^https?:\/\//i.test(String(direct).trim())) return String(direct).trim();
    const douyinId = data.douyinId || data.workId || item.douyinId || item.workId;
    if (douyinId) return `https://www.douyin.com/video/${encodeURIComponent(String(douyinId))}`;
    const fallback = douyinInputFromQueueItem(item);
    return /^https?:\/\//i.test(String(fallback || '').trim()) ? String(fallback).trim() : '';
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
    request.signal = _activationContext.signal;
    const res = await fetch(`${BASE}${path}`, request);
    douyinAssertActive();
    const data = await res.json().catch(() => ({}));
    douyinAssertActive();
    if (!res.ok) {
        const key = data.messageKey ? douyinI18nKey(data.messageKey) : 'douyin:error.request-failed';
        throw new Error(bt(key, data.message || `HTTP ${res.status}`));
    }
    return data;
}

async function processDouyinItem(item) {
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
                signal: _activationContext.signal,
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
                throw new Error(bt(key, data.message || `HTTP ${res.status}`));
            }
            const statusId = data.id;
            item.douyinStatusId = statusId;
            const start = Date.now();
            while (Date.now() - start < STATUS_TIMEOUT_MS) {
                await sleep(800);
                douyinAssertActive();
                const statusRes = await fetch(`${BASE}/api/douyin/status/${encodeURIComponent(statusId)}`, {
                    credentials: 'same-origin',
                    signal: _activationContext.signal
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
        if (!_activationContext || !_activationContext.isActive()) throw e;
        item.status = 'failed';
        item.lastMessage = bt('queue.message.failed', 'Failed - {message}', {message: e.message || String(e)});
        item.endTime = new Date().toISOString();
        updateStats();
        saveQueue();
        renderQueue();
        throw e;
    }
}

function douyinCardHtml(item, idx, ctx) {
    const idPrefix = ctx.idPrefix || 'douyin';
    const cardId = douyinCardId(idPrefix, idx);
    const queueId = douyinQueueId(item);
    const inQueue = ctx.inQueue && ctx.inQueue.has(queueId);
    const title = item.title || douyinText('queue.fallback', 'Douyin {id}', {id: item.id});
    const author = item.userName || item.authorName || '';
    return `<div class="search-thumb${inQueue ? ' in-queue' : ''}" id="${cardId}" data-douyin-idx="${idx}"
                 title="${esc(title)} (${esc(author)})">
      <div class="thumb-title">${esc(title)}</div>
      <div class="thumb-title" style="font-size:12px;color:var(--muted);">${esc(author || item.id || '')}</div>
      <span class="thumb-in-queue-mark">✓</span>
    </div>`;
}

function renderDouyinGrid(area, items, ctx) {
    if (!items.length) {
        area.innerHTML = `<div style="color:#aaa;text-align:center;padding:24px 0;">${esc(douyinText('empty', 'No Douyin items'))}</div>`;
        return;
    }
    area.innerHTML = (ctx.summaryHtml || '') + `<div class="search-grid">${
        items.map((item, idx) => douyinCardHtml(item, idx, ctx)).join('')
    }</div>`;
    area.querySelectorAll('[data-douyin-idx]').forEach(card => {
        bindDouyinEvent(card, 'click', () => ctx.onClick(Number(card.dataset.douyinIdx)));
    });
}

function renderDouyinUserResults(area, ctx) {
    renderDouyinGrid(area, ctx.items || [], {
        summaryHtml: ctx.summaryHtml,
        inQueue: ctx.inQueue,
        idPrefix: 'user',
        onClick: idx => addUserItemToQueue(idx)
    });
}

function renderDouyinSearchResults(area, view) {
    const inQueue = new Set(state.queue.map(q => q.id));
    renderDouyinGrid(area, view.items || [], {
        inQueue,
        idPrefix: 'search',
        onClick: idx => addSearchItemToQueue((view.base || 0) + idx)
    });
}

function renderDouyinSeriesResults(area, ctx) {
    renderDouyinGrid(area, ctx.items || [], {
        inQueue: ctx.inQueue,
        idPrefix: 'series',
        onClick: idx => addSeriesItemToQueue(idx)
    });
}

const douyinQuickCursors = new Map();

function douyinAssertQuickActionContext(context) {
    if (!context) return;
    if (typeof context.assertCurrent === 'function') context.assertCurrent();
    if (typeof context.isCurrent === 'function' && !context.isCurrent()) {
        throw new Error(douyinText('error.stale-request', 'This quick action is no longer active'));
    }
}

function douyinQuickFetchJson(path, operation) {
    if (typeof quickFetchJson === 'function') {
        return quickFetchJson(path, 'douyin', operation || 'quick');
    }
    // 独立 contract smoke 环境没有宿主 quick 脚本；浏览器运行态始终走上面的受控取得门。
    return douyinFetchJson(path);
}

async function loadQuickDouyinAccount(source, sourceType, titleKey, titleFallback, page, context) {
    douyinAssertQuickActionContext(context);
    const safePage = Math.max(1, Number(page) || 1);
    if (safePage === 1) {
        Array.from(douyinQuickCursors.keys())
            .filter(key => key.startsWith(`${source}:`))
            .forEach(key => douyinQuickCursors.delete(key));
    }
    const cursorKey = `${source}:${safePage}`;
    const cursor = safePage === 1 ? '0' : douyinQuickCursors.get(cursorKey);
    if (cursor == null) {
        throw new Error(douyinText('error.pagination-stalled', 'The Douyin cursor for this page is no longer available'));
    }
    const data = await douyinQuickFetchJson(
        `/api/douyin/me/${encodeURIComponent(source)}?cursor=${encodeURIComponent(cursor)}&pageSize=${DOUYIN_PAGE_SIZE}`);
    douyinAssertQuickActionContext(context);
    const items = Array.isArray(data.items) ? data.items : [];
    const nextCursor = data.nextCursor == null ? '' : String(data.nextCursor);
    if (data.hasMore && (!nextCursor || nextCursor === String(cursor))) {
        throw new Error(douyinText('error.pagination-stalled', 'The Douyin cursor did not advance'));
    }
    const accountId = quickState.accountOwner === 'douyin' ? quickState.uid : null;
    items.forEach((item, index) => {
        item.sourceType = sourceType;
        item.sourceId = accountId || source;
        item.sourceTitle = '';
        item.sourceUrl = `/api/douyin/me/${source}`;
        item.sourceOrder = (safePage - 1) * DOUYIN_PAGE_SIZE + index;
    });
    const offset = (safePage - 1) * DOUYIN_PAGE_SIZE;
    const reportedTotal = Number(data.total);
    const minimumTotal = offset + items.length + (data.hasMore ? 1 : 0);
    quickState.rawItems = items;
    const previousTotal = Number(quickState.total);
    quickState.total = Math.max(Number.isFinite(previousTotal) ? previousTotal : 0,
        Number.isFinite(reportedTotal) && reportedTotal >= 0
            ? Math.max(Math.floor(reportedTotal), minimumTotal) : minimumTotal);
    quickState.offset = offset;
    quickState.page = safePage;
    quickSetTitle(`${douyinText(titleKey, titleFallback)} · ${bt('quick.title.count', '{count} items', {count: quickState.total.toLocaleString()})}`);
    quickShowToolbar({showBack: false, showAdd: quickState.rawItems.length > 0, showSearch: false, showKindSwitcher: false});
    await quickRenderOuterWorks();
    douyinAssertQuickActionContext(context);
    if (data.hasMore) douyinQuickCursors.set(`${source}:${safePage + 1}`, nextCursor);
    const totalPages = Math.max(safePage, data.hasMore ? safePage + 1 : safePage);
    renderQuickPagination(safePage, totalPages,
        p => loadQuickDouyinAccount(source, sourceType, titleKey, titleFallback, p, context));
}

function douyinUserWorksPageEndpoint(userId, context) {
    const params = new URLSearchParams();
    params.set('offset', String(context.offset));
    params.set('limit', String(context.limit));
    const cursor = context.cursor || (Number(context.offset) === 0 ? '0' : null);
    if (cursor != null) params.set('cursor', String(cursor));
    return `/api/douyin/user/${encodeURIComponent(userId)}/works/ids?${params}`;
}

function douyinFavoriteCollectionsEndpoint(cursor, pageSize) {
    return `/api/douyin/me/favorite-collections?cursor=${encodeURIComponent(cursor)}&pageSize=${pageSize}`;
}

function douyinFavoriteCollectionWorksEndpoint(collectionId, context) {
    const params = new URLSearchParams();
    params.set('cursor', String(context.cursor == null ? '0' : context.cursor));
    params.set('pageSize', String(context.limit));
    return `/api/douyin/me/favorite-collections/${encodeURIComponent(collectionId)}/works?${params}`;
}

async function loadQuickDouyinFavoriteCollections(page, context) {
    douyinAssertQuickActionContext(context);
    const source = 'favorite-collections';
    const safePage = Math.max(1, Number(page) || 1);
    if (safePage === 1) {
        Array.from(douyinQuickCursors.keys())
            .filter(key => key.startsWith(`${source}:`))
            .forEach(key => douyinQuickCursors.delete(key));
    }
    const cursor = safePage === 1 ? '0' : douyinQuickCursors.get(`${source}:${safePage}`);
    if (cursor == null) {
        throw new Error(douyinText('error.pagination-stalled', 'The Douyin cursor for this page is no longer available'));
    }
    const data = await douyinQuickFetchJson(douyinFavoriteCollectionsEndpoint(cursor, DOUYIN_PAGE_SIZE));
    douyinAssertQuickActionContext(context);
    const collections = Array.isArray(data.collections) ? data.collections : [];
    const nextCursor = data.nextCursor == null ? '' : String(data.nextCursor);
    if (data.hasMore && (!nextCursor || nextCursor === String(cursor))) {
        throw new Error(douyinText('error.pagination-stalled', 'The Douyin collection cursor did not advance'));
    }
    if (data.hasMore) douyinQuickCursors.set(`${source}:${safePage + 1}`, nextCursor);
    const offset = (safePage - 1) * DOUYIN_PAGE_SIZE;
    const reportedTotal = Number(data.total);
    const minimumTotal = offset + collections.length + (data.hasMore ? 1 : 0);
    quickState.items = collections;
    const previousTotal = Number(quickState.total);
    quickState.total = Math.max(Number.isFinite(previousTotal) ? previousTotal : 0,
        Number.isFinite(reportedTotal) && reportedTotal >= 0
            ? Math.max(Math.floor(reportedTotal), minimumTotal) : minimumTotal);
    quickState.offset = offset;
    quickState.page = safePage;
    quickSetTitle(`${douyinText('quick.favorite-collections', 'Favorite collections')} · ${bt('quick.title.count', '{count} items', {count: quickState.total.toLocaleString()})}`);
    quickShowToolbar({showAdd: false, showSearch: false});
    renderQuickCollectionGrid(quickState.items);
    const totalPages = Math.max(safePage, data.hasMore ? safePage + 1 : safePage);
    renderQuickPagination(safePage, totalPages, p => loadQuickDouyinFavoriteCollections(p, context));
    updateExtraFiltersCardVisibility();
    updateSaveScheduleCardVisibility();
    applyNovelSettingsVisibility();
}

function renderQuickDouyinGrid(items, idPrefix, summaryHtml) {
    const area = document.getElementById('quick-preview-area');
    if (!area) return;
    renderDouyinGrid(area, items || [], {
        summaryHtml,
        inQueue: new Set(state.queue.map(q => q.id)),
        idPrefix: idPrefix || 'quick',
        onClick: idx => quickToggleItemQueue(idx)
    });
}

function douyinQuickInnerCard(item, idx, inQueue) {
    const queueId = douyinQueueId(item);
    const title = item.title || douyinText('queue.fallback', 'Douyin {id}', {id: item.id});
    const author = item.userName || item.authorName || '';
    return `<div class="search-thumb${inQueue.has(queueId) ? ' in-queue' : ''}" id="quick-inner-card-${idx}"
                 onclick="quickInnerToggleQueue(${idx})" title="${esc(title)} (${esc(author)})">
      <div class="thumb-title">${esc(title)}</div>
      <div class="thumb-title" style="font-size:12px;color:var(--muted);">${esc(author || item.id || '')}</div>
      <span class="thumb-in-queue-mark">✓</span>
    </div>`;
}

const DOUYIN_SLOTS = {
    'kind-option-user':
        '<label data-kind="douyin"><input type="radio" name="user-kind" value="douyin">' +
        '<span data-i18n="douyin:batch.kind">Douyin</span></label>',
    'kind-option-search':
        '<label data-kind="douyin"><input type="radio" name="search-kind" value="douyin">' +
        '<span data-i18n="douyin:batch.kind">Douyin</span></label>',
    'kind-option-quick':
        '<label data-quick-kind="douyin"><input type="radio" name="quick-inner-kind" value="douyin">' +
        '<span data-i18n="douyin:batch.kind">Douyin</span></label>',
    'quick-actions-bookmarks':
        '<button type="button" class="btn btn-blue quick-action" data-quick="douyin-liked" onclick="quickLoad(\'douyin-liked\')" ' +
        'data-i18n="douyin:quick.liked">Liked works</button>' +
        '<button type="button" class="btn btn-purple quick-action" data-quick="douyin-favorites" onclick="quickLoad(\'douyin-favorites\')" ' +
        'data-i18n="douyin:quick.favorites">Favorite works</button>' +
        '<button type="button" class="btn btn-yellow quick-action" data-quick="douyin-favorite-collections" onclick="quickLoad(\'douyin-favorite-collections\')" ' +
        'data-i18n="douyin:quick.favorite-collections">Favorite collections</button>',
    'quick-actions-mine':
        '<button type="button" class="btn btn-green quick-action" data-quick="douyin-own-works" onclick="quickLoad(\'douyin-own-works\')" ' +
        'data-i18n="douyin:quick.own-works">My works</button>',
    'import-hint':
        '<div><span data-i18n="douyin:import.example">Douyin URL: https://www.douyin.com/video/...</span></div>',
    'cookie-tools':
        '<section class="cookie-type-card plugin-cookie-card douyin-cookie-block" id="douyin-cookie-block" data-cookie-type="douyin">' +
        '<div class="plugin-cookie-title" data-i18n="douyin:settings.cookie.title">Douyin Cookie</div>' +
        '<div class="cookie-row plugin-cookie-row">' +
        '<input type="password" id="douyin-cookie-input" autocomplete="off" ' +
        'data-i18n-placeholder="douyin:settings.cookie.placeholder" placeholder="Paste Douyin Cookie">' +
        '<button type="button" class="btn-sm" id="douyin-cookie-toggle" data-i18n="douyin:settings.cookie.show">Show</button>' +
        '<button type="button" class="btn-cookie-save" id="douyin-cookie-save" data-i18n="common:button.save">Save</button>' +
        '<button type="button" class="btn-sm" id="douyin-cookie-validate" data-i18n="douyin:settings.cookie.validate">Validate</button>' +
        '<button type="button" class="btn-cookie-clear" id="douyin-cookie-clear" data-i18n="douyin:settings.cookie.clear">Clear</button>' +
        '</div>' +
        '<div class="cookie-status plugin-cookie-status" id="douyin-cookie-status" role="status" aria-live="polite"></div>' +
        '<div class="cookie-hint plugin-cookie-hint" data-i18n="douyin:settings.cookie.hint">Copy the full Cookie from a logged-in Douyin browser request.</div>' +
        '</section>'
};

function hydrateDouyinCookieSettings() {
    const input = document.getElementById('douyin-cookie-input');
    if (!input) return;
    input.value = douyinStoredCookieRaw();
    if (input.dataset.douyinBound !== '1') {
        input.dataset.douyinBound = '1';
        bindDouyinEvent(input, 'input', () => douyinUpdateCookieStatus(false, douyinCookieInputHeaderString()));
        const toggle = document.getElementById('douyin-cookie-toggle');
        if (toggle) {
            bindDouyinEvent(toggle, 'click', () => {
                const visible = input.type === 'text';
                input.type = visible ? 'password' : 'text';
                toggle.setAttribute('data-i18n', visible
                    ? 'douyin:settings.cookie.show'
                    : 'douyin:settings.cookie.hide');
                toggle.textContent = visible
                    ? douyinText('settings.cookie.show', 'Show')
                    : douyinText('settings.cookie.hide', 'Hide');
            });
        }
        const save = document.getElementById('douyin-cookie-save');
        if (save) {
            bindDouyinEvent(save, 'click', () => {
                const raw = input.value.trim();
                const header = douyinCookieRawToHeaderString(raw);
                const validation = douyinValidateCookie(header);
                if (!validation.ok) {
                    douyinUpdateCookieStatus(true, header);
                    return;
                }
                douyinSetStoredCookieRaw(raw);
                douyinUpdateCookieStatus(true, header);
                if (typeof invalidateQuickAccount === 'function') invalidateQuickAccount('douyin');
                if (typeof applyQuickActionCredentialUi === 'function') applyQuickActionCredentialUi();
                if (typeof updateQuickAccountBar === 'function') updateQuickAccountBar('douyin');
            });
        }
        const validate = document.getElementById('douyin-cookie-validate');
        if (validate) {
            bindDouyinEvent(validate, 'click', () => douyinUpdateCookieStatus(true, douyinCookieInputHeaderString()));
        }
        const clear = document.getElementById('douyin-cookie-clear');
        if (clear) {
            bindDouyinEvent(clear, 'click', () => {
                input.value = '';
                douyinRemoveStoredCookieRaw();
                douyinUpdateCookieStatus(false, '');
                if (typeof invalidateQuickAccount === 'function') invalidateQuickAccount('douyin');
                if (typeof applyQuickActionCredentialUi === 'function') applyQuickActionCredentialUi();
                if (typeof updateQuickAccountBar === 'function') updateQuickAccountBar('douyin');
            });
        }
    }
    douyinUpdateCookieStatus(false, douyinCookieInputHeaderString());
}

function hydrateDouyinUi() {
    hydrateDouyinCookieSettings();
    if (typeof applyQuickActionCredentialUi === 'function') applyQuickActionCredentialUi();
}

function douyinScheduleSource(sourceType, source, label) {
    return {
        sourceType,
        type: sourceType,
        source: source || {},
        kind: 'douyin',
        workTypes: ['douyin'],
        label: label || ''
    };
}

const DOUYIN_DESCRIPTOR = {
    slots: DOUYIN_SLOTS,
    process: processDouyinItem,
    mergeQueueTypeData: douyinMergeQueueTypeData,
    canonicalUrl: douyinCanonicalQueueItemUrl,
    scheduledSse: false,
    cookie: {
        parseInput: douyinParseInput,
        validate: douyinValidateCookie
    },
    import: {
        sectionType: 'douyin',
        matchUrl(line) {
            const parsed = douyinParseInput(line);
            return parsed && ['single', 'short', 'series', 'user', 'music'].includes(parsed.kind) ? parsed : null;
        },
        buildItem(match, title, _line) {
            const parsed = match && match.id ? match : douyinParseInput(String(match || ''));
            if (!parsed) return null;
            const displayId = parsed.workId || parsed.seriesId || parsed.musicId || parsed.id;
            return {
                id: douyinQueueId(parsed),
                douyinId: displayId,
                kind: 'douyin',
                url: parsed.url,
                title: title || douyinText('queue.fallback', 'Douyin {id}', {id: displayId}),
                typeData: douyinNormalizeQueueTypeData({
                    input: parsed.url,
                    url: parsed.url,
                    douyinId: displayId,
                    sourceKind: parsed.kind,
                    seriesId: parsed.seriesId || null,
                    seriesTitle: '',
                    sourceType: parsed.kind === 'series' ? 'douyin.collection'
                        : parsed.kind === 'user' ? 'douyin.user'
                            : parsed.kind === 'music' ? 'douyin.music' : 'douyin.single',
                    sourceId: displayId,
                    sourceTitle: title || '',
                    sourceUrl: parsed.url,
                    sourceOrder: null
                })
            };
        },
        source: 'single-import-douyin'
    },
    acquisition: {
        user: {
            pageSize: DOUYIN_PAGE_SIZE,
            initialCursor: '0',
            requestInit() {
                return {credentials: 'same-origin', headers: douyinAcquisitionCredentialHeaders()};
            },
            accepts(selection) { return selection === 'douyin'; },
            parseInput: douyinParseUserInput,
            fetchMeta() { return Promise.resolve(null); },
            async fetchPage(userId, context) {
                const data = await douyinFetchJson(
                    douyinUserWorksPageEndpoint(userId, context),
                    {signal: context.signal}
                );
                return {
                    items: data.items || [],
                    total: data.total,
                    nextCursor: data.nextCursor,
                    hasMore: !!data.hasMore
                };
            },
            queueId: douyinQueueId,
            cardId(idx) { return douyinCardId('user', idx); },
            render: renderDouyinUserResults,
            buildQueueMeta(item, ctx) {
                return douyinQueueMeta(Object.assign({}, item, {
                    sourceType: 'douyin.user',
                    sourceId: String(ctx.userId),
                    sourceTitle: ctx.username || String(ctx.userId),
                    sourceUrl: `https://www.douyin.com/user/${encodeURIComponent(String(ctx.userId))}`
                }));
            }
        },
        search: {
            pageSize: DOUYIN_PAGE_SIZE,
            requestInit() {
                return {credentials: 'same-origin', headers: douyinAcquisitionCredentialHeaders()};
            },
            buildRequest(ctx) {
                return {
                    endpoint: '/api/douyin/search',
                    params: {word: ctx.word, page: ctx.page, pageSize: DOUYIN_PAGE_SIZE},
                    premiumOrder: false,
                    clientFilter: 0
                };
            },
            buildRangeRequest(ctx) {
                return {
                    endpoint: '/api/douyin/search/range',
                    params: {
                        word: ctx.word,
                        startPage: ctx.startPage,
                        endPage: ctx.endPage,
                        pageSize: DOUYIN_PAGE_SIZE
                    }
                };
            },
            queueId: douyinQueueId,
            queueSource: 'search-douyin',
            emptyResultsLabel() { return douyinText('search.empty', 'No Douyin search results'); },
            render: renderDouyinSearchResults,
            buildQueueMeta(item) {
                const word = (document.getElementById('search-word') || {}).value || '';
                return douyinQueueMeta(Object.assign({}, item, {
                    sourceType: 'douyin.search', sourceId: word,
                    sourceTitle: word, sourceUrl: `https://www.douyin.com/search/${encodeURIComponent(word)}`
                }));
            },
            controls: {searchMode: false, order: false, contentFilter: false, batchRange: true, r18Blur: false}
        },
        series: {
            pageSize: DOUYIN_PAGE_SIZE,
            requestInit() {
                return {credentials: 'same-origin', headers: douyinAcquisitionCredentialHeaders()};
            },
            apiPath(seriesId, page) {
                if (String(seriesId).startsWith('music:')) {
                    const musicId = String(seriesId).substring('music:'.length);
                    return `/api/douyin/music/${encodeURIComponent(musicId)}?page=${page}&pageSize=${DOUYIN_PAGE_SIZE}`;
                }
                return `/api/douyin/series/${encodeURIComponent(seriesId)}?page=${page}&pageSize=${DOUYIN_PAGE_SIZE}`;
            },
            parseUrl(text) {
                const parsed = douyinParseInput(text);
                if (parsed && parsed.kind === 'series') return {seriesId: parsed.seriesId};
                if (parsed && parsed.kind === 'music') return {seriesId: `music:${parsed.musicId}`};
                return null;
            },
            typeLabel() { return douyinText('series.type', 'Douyin collection'); },
            queueId: douyinQueueId,
            cardId(idx) { return douyinCardId('series', idx); },
            queueSource: 'series-douyin',
            render: renderDouyinSeriesResults,
            buildQueueMeta(item, seriesOrder, ctx) {
                const meta = Object.assign(douyinQueueMeta(item), {
                    seriesId: ctx.seriesId,
                    seriesOrder,
                    seriesTitle: ctx.seriesTitle
                });
                meta.typeData = Object.assign({}, meta.typeData, {
                    seriesId: String(ctx.seriesId).startsWith('music:') ? null : ctx.seriesId,
                    seriesTitle: ctx.seriesTitle,
                    sourceType: String(ctx.seriesId).startsWith('music:') ? 'douyin.music' : 'douyin.collection',
                    sourceId: String(ctx.seriesId).replace(/^music:/, ''),
                    sourceTitle: ctx.seriesTitle || '',
                    sourceUrl: String(ctx.seriesId).startsWith('music:')
                        ? `https://www.douyin.com/music/${encodeURIComponent(String(ctx.seriesId).substring(6))}`
                        : `https://www.douyin.com/mix/${encodeURIComponent(String(ctx.seriesId))}`,
                    sourceOrder: seriesOrder
                });
                return meta;
            }
        },
        quick: {
            pageSize: DOUYIN_PAGE_SIZE,
            initialCursor: '0',
            skipThumbnail: true,
            requestInit() {
                return {credentials: 'same-origin', headers: douyinAcquisitionCredentialHeaders()};
            },
            account: {
                credentialMissing() { return !douyinValidateCookie(douyinCookie()).ok; },
                missingHint() { return douyinText('quick.cookie-required', 'Save a valid Douyin Cookie first'); },
                buildRequest() { return {endpoint: '/api/douyin/me'}; },
                readId(data) { return data && data.accountKey; }
            },
            buildMyWorksIdsRequest() { return {endpoint: '/api/douyin/me/works/ids'}; },
            buildUserPageRequest(userId, context) {
                return {endpoint: douyinUserWorksPageEndpoint(userId, context)};
            },
            buildCardsRequest(userId, ids) {
                const params = new URLSearchParams();
                (ids || []).forEach(id => params.append('ids', id));
                return {endpoint: `/api/douyin/user/${encodeURIComponent(userId)}/works/cards?${params}`};
            },
            myWorksTitleKey: 'douyin:quick.own-works',
            queueId: douyinQueueId,
            gridCardId(idPrefix, idx) { return douyinCardId(idPrefix, idx); },
            render: renderQuickDouyinGrid,
            innerCardHtml: douyinQuickInnerCard,
            buildQueueMeta(item, context) {
                const ctx = context && typeof context === 'object' ? context : {};
                const inner = ctx.inner && typeof ctx.inner === 'object' ? ctx.inner : null;
                const action = ctx.action || quickState.action || '';
                const ownWorksAccountId = ctx.accountOwner === 'douyin' && ctx.accountId
                    ? String(ctx.accountId) : 'own-works';
                const source = inner && inner.type === 'following-user' && inner.userId
                    ? ['douyin.user', inner.userId, inner.name || inner.userId,
                        'https://www.douyin.com/user/' + encodeURIComponent(inner.userId)]
                    : inner && inner.type === 'collection' && inner.id
                        ? ['douyin.account.favorite-collection', inner.id, inner.name || inner.id,
                            '/api/douyin/me/favorite-collections/' + encodeURIComponent(inner.id) + '/works']
                        : action === 'douyin-liked'
                    ? ['douyin.account.liked-works', 'liked']
                    : action === 'douyin-favorites'
                        ? ['douyin.account.favorite-works', 'favorites']
                        : action === 'douyin-favorite-collections'
                            ? ['douyin.account.favorite-collection', item.collectionId || 'collection']
                            : ['douyin.account.own-works', ownWorksAccountId];
                return douyinQueueMeta(Object.assign({}, item, {
                    sourceType: item.sourceType || source[0],
                    sourceId: item.sourceId || source[1],
                    sourceTitle: item.sourceTitle || source[2] || '',
                    sourceUrl: item.sourceUrl || source[3] || null,
                    sourceOrder: item.sourceOrder
                }));
            },
            buildQueueMetaFromId(id, context) {
                const ctx = context && typeof context === 'object' ? context : {};
                const sourceId = ctx.accountOwner === 'douyin' && ctx.accountId
                    ? String(ctx.accountId) : 'own-works';
                return {
                    kind: 'douyin',
                    typeData: douyinNormalizeQueueTypeData({
                        input: String(id), douyinId: String(id),
                        sourceType: 'douyin.account.own-works', sourceId
                    })
                };
            },
            actions: {
                'douyin-own-works': {
                    viewType: 'works-list', kind: 'douyin', sourceType: 'douyin.account.own-works',
                    allIdsFastPath: true,
                    load(_action, context) {
                        douyinAssertQuickActionContext(context);
                        return loadQuickMyWorks('douyin', 1, context);
                    },
                    scheduleSource() {
                        return douyinScheduleSource('douyin.account.own-works', {},
                            douyinText('quick.own-works', 'My Douyin works'));
                    }
                },
                'douyin-liked': {
                    viewType: 'works-list', kind: 'douyin', sourceType: 'douyin.account.liked-works',
                    cursorPaging: true, initialCursor: '0',
                    buildPageRequest(context = {}) {
                        const cursor = context.cursor == null ? '0' : String(context.cursor);
                        const pageSize = Number(context.limit) || DOUYIN_PAGE_SIZE;
                        return {endpoint: `/api/douyin/me/liked?cursor=${encodeURIComponent(cursor)}&pageSize=${pageSize}`};
                    },
                    load(_action, context) {
                        return loadQuickDouyinAccount('liked', 'douyin.account.liked-works',
                            'quick.liked', 'Liked works', 1, context);
                    },
                    scheduleSource() {
                        return douyinScheduleSource('douyin.account.liked-works', {},
                            douyinText('quick.liked', 'Liked works'));
                    }
                },
                'douyin-favorites': {
                    viewType: 'works-list', kind: 'douyin', sourceType: 'douyin.account.favorite-works',
                    cursorPaging: true, initialCursor: '0',
                    buildPageRequest(context = {}) {
                        const cursor = context.cursor == null ? '0' : String(context.cursor);
                        const pageSize = Number(context.limit) || DOUYIN_PAGE_SIZE;
                        return {endpoint: `/api/douyin/me/favorites?cursor=${encodeURIComponent(cursor)}&pageSize=${pageSize}`};
                    },
                    load(_action, context) {
                        return loadQuickDouyinAccount('favorites', 'douyin.account.favorite-works',
                            'quick.favorites', 'Favorite works', 1, context);
                    },
                    scheduleSource() {
                        return douyinScheduleSource('douyin.account.favorite-works', {},
                            douyinText('quick.favorites', 'Favorite works'));
                    }
                },
                'douyin-favorite-collections': {
                    viewType: 'collection-list', kind: 'douyin', sourceType: 'douyin.account.favorite-collection',
                    initialCursor: '0',
                    buildPageRequest(context = {}) {
                        const cursor = context.cursor == null ? '0' : String(context.cursor);
                        const pageSize = Number(context.limit) || DOUYIN_PAGE_SIZE;
                        return {endpoint: douyinFavoriteCollectionsEndpoint(cursor, pageSize)};
                    },
                    buildCollectionWorksPageRequest(collectionId, context) {
                        return {endpoint: douyinFavoriteCollectionWorksEndpoint(collectionId, context)};
                    },
                    load(_action, context) { return loadQuickDouyinFavoriteCollections(1, context); },
                    scheduleSource(context) {
                        const inner = context && context.inner;
                        if (!inner || inner.type !== 'collection' || !inner.id) return null;
                        return douyinScheduleSource('douyin.account.favorite-collection', {
                            collectionId: String(inner.id)
                        }, douyinText('schedule.quick.favorite-collection',
                            'Favorite collection {name} (ID {id})', {
                                name: inner.name || inner.id,
                                id: inner.id
                            }));
                    }
                }
            }
        }
    }
};

if (window.PixivBatch && window.PixivBatch.queueTypes) {
    window.PixivBatch.queueTypes.registerModule(function (context) {
        _activationContext = context;
        const descriptor = Object.assign({}, DOUYIN_DESCRIPTOR);
        bindDouyinEvent(window, 'pixivbatch:slotsrendered', hydrateDouyinUi);
        bindDouyinEvent(window, 'pixivbatch:storageloaded', hydrateDouyinCookieSettings);
        bindDouyinEvent(window, 'pixivbatch:cookieformatchanged', () => {
            if (typeof invalidateQuickAccount === 'function') invalidateQuickAccount('douyin');
            douyinUpdateCookieStatus(false, douyinCookieInputHeaderString());
            if (typeof updateQuickAccountBar === 'function') updateQuickAccountBar('douyin');
        });
        _douyinTimer = setTimeout(hydrateDouyinUi, 0);
        return {
            descriptor,
            dispose() {
                _activationContext = null;
                if (_douyinTimer != null) clearTimeout(_douyinTimer);
                _douyinTimer = null;
                _douyinCleanups.splice(0).reverse().forEach(cleanup => {
                    try { cleanup(); } catch (e) { /* best effort */ }
                });
                const input = document.getElementById('douyin-cookie-input');
                if (input && input.dataset) delete input.dataset.douyinBound;
            }
        };
    });
}
})();
