'use strict';
window.PixivInviteDetail = window.PixivInviteDetail || {};
['core', 'render', 'actions', 'chart', 'init'].forEach(function (k) { window.PixivInviteDetail[k] = window.PixivInviteDetail[k] || {}; });

const inviteId = new URLSearchParams(location.search).get('id');
let detail = null;
let chartDays = 7;
let pageI18n = null;
let cachedTags = null;
let cachedAuthors = null;
let cachedNovelTags = null;
let cachedNovelAuthors = null;
let chartContext = null;
let lastBuckets = [];
let lastHoverPoint = null;

function tr(key, fallback, vars) {
    if (pageI18n && pageI18n.t) return pageI18n.t(key, fallback, vars);
    return interpolate(fallback || key, vars);
}
function interpolate(template, vars) {
    if (!vars) return template;
    return String(template).replace(/\{([a-zA-Z0-9_-]+)\}/g, (m, name) =>
        Object.prototype.hasOwnProperty.call(vars, name) ? vars[name] : m);
}

async function api(url, options) {
    const res = await fetch(url, {
        credentials: 'same-origin',
        ...options,
        headers: { 'Accept': 'application/json', ...((options || {}).headers || {}) }
    });
    if (res.status === 401) {
        window.location.href = '/login.html?redirect=' + encodeURIComponent(location.pathname + location.search);
        throw new Error('Unauthorized');
    }
    if (!res.ok) {
        let msg = 'HTTP ' + res.status;
        try { const j = await res.json(); if (j && j.error) msg = j.error; } catch (_) {}
        throw new Error(msg);
    }
    return res.headers.get('content-type')?.includes('json') ? res.json() : res.text();
}

function escapeHtml(str) {
    return String(str || '').replace(/[&<>"']/g, c =>
        ({ '&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;' }[c]));
}
function fmtTime(ms) {
    if (ms == null) return '-';
    const d = new Date(ms);
    return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-'
        + String(d.getDate()).padStart(2, '0') + ' '
        + String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0');
}
function fmtTimeShort(ms) {
    const d = new Date(ms);
    return (d.getMonth() + 1) + '/' + d.getDate() + ' '
        + String(d.getHours()).padStart(2, '0') + ':00';
}
/**
 * 小时桶覆盖 [ms, ms + 3_600_000)，tooltip 显示这段时间范围。
 * 例如 5/3 10:00–11:00；跨日时 23:00–00:00。
 */
function fmtBucketRange(ms) {
    const start = new Date(ms);
    const end = new Date(ms + 3600000);
    const dateStr = (start.getMonth() + 1) + '/' + start.getDate();
    const startHH = String(start.getHours()).padStart(2, '0') + ':00';
    const endHH = String(end.getHours()).padStart(2, '0') + ':00';
    return dateStr + ' ' + startHH + '–' + endHH;
}

function ageRatingLabel(d) {
    const parts = [];
    if (d.allowSfw) parts.push(tr('invite:age.sfw', 'SFW'));
    if (d.allowR18) parts.push(tr('invite:age.r18', 'R18'));
    if (d.allowR18g) parts.push(tr('invite:age.r18g', 'R18G'));
    return parts.length ? parts.join(', ') : '(-)';
}

// ---- PixivInviteDetail facade ----
window.PixivInviteDetail.core = Object.assign(window.PixivInviteDetail.core || {}, { tr, interpolate, api, escapeHtml, fmtTime, fmtTimeShort, fmtBucketRange, ageRatingLabel });
