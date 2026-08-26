'use strict';
(function () {
if (!window.PixivBatch || !window.PixivBatch.queueTypes) return;
window.PixivBatch.queueTypes.registerSubmodule(function (shared) {
/* global BASE, bt, esc, state, sleep, setCurrent, renderQueue, updateStats, saveQueue, setStatus,
          addUserItemToQueue, addSearchItemToQueue, addSeriesItemToQueue, quickLoad, quickToggleItemQueue,
          quickState, quickSetTitle, quickShowToolbar, quickRenderOuterWorks, renderQuickPagination,
          renderQuickCollectionGrid, updateExtraFiltersCardVisibility, updateSaveScheduleCardVisibility,
          applyNovelSettingsVisibility, loadQuickMyWorks, invalidateQuickAccount */

function douyinAssertActive() {
    if (!shared.context || !shared.context.isActive()) {
        throw new Error('douyin queue type activation is stale');
    }
}

function bindDouyinEvent(target, eventName, handler) {
    if (!target || typeof target.addEventListener !== 'function') return;
    target.addEventListener(eventName, handler);
    shared.cleanups.push(() => {
        if (typeof target.removeEventListener === 'function') target.removeEventListener(eventName, handler);
    });
}

const DOUYIN_PAGE_SIZE = 24;
const DOUYIN_FAVORITE_FOLDER_SERIES_PREFIX = 'favorite-folder:';
const DOUYIN_USER_KIND_WORKS = 'douyin';
const DOUYIN_USER_KIND_LIKED = 'douyin-user-liked';
const DOUYIN_USER_KINDS = new Set([
    DOUYIN_USER_KIND_WORKS,
    DOUYIN_USER_KIND_LIKED
]);
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

function formatDouyinSearchStats(metric, stats) {
    const count = Number(stats && stats.count);
    const displayCount = (Number.isFinite(count) ? Math.max(0, count) : 0).toLocaleString();
    if (metric === 'total') {
        return douyinText('search.summary.total', '抖音总数 {count} 个作品', {count: displayCount});
    }
    if (metric === 'returned') {
        return douyinText('search.summary.returned', '抖音返回 {count} 个作品', {count: displayCount});
    }
    if (metric === 'batch-fetched') {
        return douyinText('search.summary.fetched', '已抓取去重 {count} 个抖音作品', {count: displayCount});
    }
    if (metric === 'current-page') {
        return douyinText('search.summary.current-page', '抖音当前页 {count} 个作品', {count: displayCount});
    }
    return '';
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

function douyinCancelWorkKey(value) {
    if (value == null) return null;
    const raw = String(value);
    return raw.length > 0 && raw.length <= 4096 && raw.trim() !== '' ? raw : null;
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
    let m = path.match(/^\/(?:share\/)?(video|note|gallery|slides)\/([^/?#]+)/);
    if (m) {
        return {
            kind: 'single', id: m[2], workId: m[2], url: parsed.href,
            mediaKindHint: m[1] === 'video' ? 'VIDEO' : 'IMAGE_NOTE'
        };
    }
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
    if (parsed && parsed.kind === 'user') {
        return parsed.userId === 'self' ? null : parsed.userId;
    }
    return raw !== 'self' && /^[A-Za-z0-9._-]{6,256}$/.test(raw) ? raw : null;
}

function douyinUserKind(context) {
    const value = context && context.variant ? String(context.variant) : DOUYIN_USER_KIND_WORKS;
    return DOUYIN_USER_KINDS.has(value) ? value : DOUYIN_USER_KIND_WORKS;
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

const DOUYIN_QUEUE_MEDIA_KINDS = new Set(['VIDEO', 'IMAGE_NOTE', 'LIVE_PHOTO', 'IMAGE']);
const DOUYIN_QUEUE_SOURCE_TAGS = Object.freeze({
    'douyin.collection': ['origin.collection', 'queue.tag.collection', '合集'],
    'douyin.music': ['origin.music', 'queue.tag.music', '音乐'],
    'douyin.account.favorite-works': ['origin.favorite', 'queue.tag.favorite', '收藏'],
    'douyin.account.favorite-folder': ['origin.favorite-folder', 'queue.tag.favorite-folder', '收藏夹'],
    'douyin.account.favorite-collection': [
        'origin.favorite-collection', 'queue.tag.favorite-collection', '收藏合集'
    ],
    'douyin.account.liked-works': ['origin.liked', 'queue.tag.liked', '喜欢'],
    'douyin.user.liked-works': ['origin.liked', 'queue.tag.liked', '喜欢']
});

function douyinQueueMediaKind(item) {
    const data = douyinQueueTypeData(item);
    const candidates = [
        item && item.mediaKind,
        data.mediaKind,
        item && item.kind !== 'douyin' ? item.kind : null
    ];
    for (const candidate of candidates) {
        const normalized = candidate == null ? '' : String(candidate).trim().toUpperCase();
        if (DOUYIN_QUEUE_MEDIA_KINDS.has(normalized)) return normalized;
    }
    return null;
}

function douyinQueueMediaCount(item) {
    const data = douyinQueueTypeData(item);
    const rawValue = item && item.mediaCount != null ? item.mediaCount : data.mediaCount;
    if (rawValue == null || String(rawValue).trim() === '') return null;
    const value = Number(rawValue);
    return Number.isSafeInteger(value) && value >= 0 ? value : null;
}

function douyinQueueMeta(item) {
    const douyinId = String(item.id || item.workId || item.douyinId || '');
    const rawWorkKey = item.cancelWorkKey != null ? item.cancelWorkKey
        : item.workId != null ? item.workId
            : item.douyinId != null ? item.douyinId : item.id;
    const url = item.url || item.pageUrl || '';
    const mediaKind = douyinQueueMediaKind(item);
    const mediaCount = douyinQueueMediaCount(item);
    return {
        title: item.title || douyinText('queue.fallback', 'Douyin {id}', {id: item.id}),
        douyinId,
        kind: 'douyin',
        cancelWorkKey: douyinCancelWorkKey(rawWorkKey),
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
            sourceOrder: Number.isInteger(item.sourceOrder) ? item.sourceOrder : null,
            mediaKind,
            mediaCount
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
    const mediaKind = data.mediaKind == null ? '' : String(data.mediaKind).trim().toUpperCase();
    if (DOUYIN_QUEUE_MEDIA_KINDS.has(mediaKind)) data.mediaKind = mediaKind;
    else delete data.mediaKind;
    const mediaCount = data.mediaCount == null || String(data.mediaCount).trim() === ''
        ? NaN : Number(data.mediaCount);
    if (Number.isSafeInteger(mediaCount) && mediaCount >= 0) data.mediaCount = mediaCount;
    else delete data.mediaCount;
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
    if ((!currentData.mediaKind || currentData.mediaKind === 'UNSUPPORTED') && incomingData.mediaKind) {
        merged.mediaKind = incomingData.mediaKind;
    }
    if (currentData.mediaCount == null && incomingData.mediaCount != null) {
        merged.mediaCount = incomingData.mediaCount;
    }
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

function douyinQueueTags(item) {
    const data = douyinNormalizeQueueTypeData(douyinQueueTypeData(item));
    const mediaKind = douyinQueueMediaKind(item);
    const tags = [];
    if (mediaKind === 'VIDEO') {
        tags.push({id: 'media.video', label: douyinText('queue.tag.video', '视频')});
    } else if (mediaKind === 'IMAGE_NOTE') {
        tags.push({id: 'media.image', label: douyinText('queue.tag.image', '图片')});
        tags.push({id: 'media.image-note', label: douyinText('queue.tag.image-note', '图文')});
    } else if (mediaKind === 'LIVE_PHOTO') {
        tags.push({id: 'media.image', label: douyinText('queue.tag.image', '图片')});
        tags.push({id: 'media.video', label: douyinText('queue.tag.video', '视频')});
        tags.push({id: 'media.live-photo', label: douyinText('queue.tag.live-photo', '实况')});
    } else if (mediaKind === 'IMAGE') {
        tags.push({id: 'media.image', label: douyinText('queue.tag.image', '图片')});
    }

    const sourceTypes = [];
    data.sourceRelations.forEach(relation => sourceTypes.push(relation.sourceType));
    if (data.sourceType) sourceTypes.push(data.sourceType);
    const seen = new Set();
    sourceTypes.forEach(sourceType => {
        if (!Object.prototype.hasOwnProperty.call(DOUYIN_QUEUE_SOURCE_TAGS, sourceType)) return;
        const contribution = DOUYIN_QUEUE_SOURCE_TAGS[sourceType];
        if (!contribution || seen.has(contribution[0])) return;
        seen.add(contribution[0]);
        tags.push({
            id: contribution[0],
            label: douyinText(contribution[1], contribution[2])
        });
    });
    return tags;
}

Object.assign(shared, {
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
});
});
})();
