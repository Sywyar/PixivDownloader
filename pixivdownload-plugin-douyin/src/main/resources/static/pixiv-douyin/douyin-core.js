'use strict';

const pixivDouyinDetailState = {
    id: null,
    preferredMediaId: null,
    returnTo: '/pixiv-douyin-gallery.html?view=all',
    pageI18n: null,
    work: null,
    status: null
};

function douyinDetailInterpolate(template, values) {
    return String(template == null ? '' : template).replace(/\{([a-zA-Z0-9_.-]+)}/g, (match, key) =>
        values && Object.prototype.hasOwnProperty.call(values, key) ? String(values[key]) : match);
}

function douyinDetailText(key, fallback, values) {
    if (pixivDouyinDetailState.pageI18n) {
        return pixivDouyinDetailState.pageI18n.t('douyin:' + key, fallback, values);
    }
    return douyinDetailInterpolate(fallback == null ? key : fallback, values);
}

function douyinDetailSafeId(value) {
    if (value == null) return null;
    const normalized = String(value).trim();
    return normalized && normalized.length <= 200 && !/[\u0000-\u001f\u007f]/.test(normalized)
        ? normalized : null;
}

function douyinDetailSafeReturnTo(value) {
    if (typeof value !== 'string' || !value.startsWith('/') || value.startsWith('//')
        || /[\u0000-\u0020\\]/.test(value)) return null;
    try {
        const resolved = new URL(value, window.location.origin);
        if (resolved.origin !== window.location.origin || resolved.username || resolved.password
            || resolved.hash || resolved.pathname !== '/pixiv-douyin-gallery.html') return null;
        return resolved.pathname + resolved.search;
    } catch (_) {
        return null;
    }
}

function douyinDetailSafeLocalUrl(value) {
    if (typeof value !== 'string' || !value.startsWith('/') || value.startsWith('//')
        || /[\u0000-\u0020\\]/.test(value)) return null;
    const rawPath = value.split(/[?#]/, 1)[0].toLowerCase();
    if (/%(?:2e|2f|5c|00|25)/.test(rawPath)
        || rawPath.split('/').some(part => part === '.' || part === '..')) return null;
    try {
        const resolved = new URL(value, window.location.origin);
        if (resolved.origin !== window.location.origin || resolved.username || resolved.password || resolved.hash
            || !resolved.pathname.startsWith('/') || resolved.pathname.startsWith('//')
            || resolved.pathname.includes('//')) return null;
        return resolved.href;
    } catch (_) {
        return null;
    }
}

function douyinDetailSafeExternalUrl(value) {
    if (typeof value !== 'string' || /[\u0000-\u001f\u007f]/.test(value)) return null;
    try {
        const resolved = new URL(value);
        return (resolved.protocol === 'https:' || resolved.protocol === 'http:')
            && !resolved.username && !resolved.password ? resolved.href : null;
    } catch (_) {
        return null;
    }
}

function douyinDetailParseParams(search) {
    const params = new URLSearchParams(search == null ? window.location.search : String(search));
    pixivDouyinDetailState.id = douyinDetailSafeId(params.get('id'));
    pixivDouyinDetailState.preferredMediaId = douyinDetailSafeId(params.get('preferredMediaId'));
    pixivDouyinDetailState.returnTo = douyinDetailSafeReturnTo(params.get('returnTo'))
        || '/pixiv-douyin-gallery.html?view=all';
    return pixivDouyinDetailState;
}

function douyinDetailSetI18n(pageI18n) {
    pixivDouyinDetailState.pageI18n = pageI18n || null;
}

function douyinDetailWorkPayload(payload) {
    if (!payload || typeof payload !== 'object') return null;
    const candidate = payload.work && typeof payload.work === 'object' ? payload.work
        : payload.data && typeof payload.data === 'object' ? payload.data : payload;
    return candidate && typeof candidate === 'object' ? candidate : null;
}

async function douyinDetailRequestWork(id) {
    const response = await fetch('/api/douyin/gallery/works/' + encodeURIComponent(id), {
        credentials: 'same-origin',
        headers: {'Accept': 'application/json'}
    });
    if (!response.ok) {
        const failure = new Error('Douyin work request failed');
        failure.name = 'DouyinHttpError';
        failure.httpStatus = response.status;
        throw failure;
    }
    const work = douyinDetailWorkPayload(await response.json());
    if (!work) {
        const failure = new Error('Douyin work response is missing');
        failure.name = 'DouyinPayloadError';
        throw failure;
    }
    return work;
}

function douyinDetailMediaId(media) {
    if (!media || typeof media !== 'object') return null;
    const key = media.key && typeof media.key === 'object' ? media.key : {};
    const value = key.mediaId != null ? key.mediaId
        : media.mediaId != null ? media.mediaId : media.id;
    return value == null ? null : String(value);
}

function douyinDetailFormatTime(value) {
    if (value == null || value === '') return null;
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return null;
    const language = pixivDouyinDetailState.pageI18n && pixivDouyinDetailState.pageI18n.lang
        || document.documentElement.lang || navigator.language || 'en';
    try {
        return new Intl.DateTimeFormat(language, {
            year: 'numeric', month: '2-digit', day: '2-digit',
            hour: '2-digit', minute: '2-digit'
        }).format(date);
    } catch (_) {
        return date.toISOString();
    }
}

const PixivDouyinDetailCore = Object.freeze({
    state: pixivDouyinDetailState,
    parseParams: douyinDetailParseParams,
    setI18n: douyinDetailSetI18n,
    t: douyinDetailText,
    requestWork: douyinDetailRequestWork,
    mediaId: douyinDetailMediaId,
    formatTime: douyinDetailFormatTime,
    safeLocalUrl: douyinDetailSafeLocalUrl,
    safeExternalUrl: douyinDetailSafeExternalUrl
});
