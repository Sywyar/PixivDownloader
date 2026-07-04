'use strict';
// PixivNovel namespace bootstrap (additive facade over global functions).
window.PixivNovel = window.PixivNovel || {};
['core', 'content', 'series', 'collections', 'admin', 'init'].forEach(function (k) { window.PixivNovel[k] = window.PixivNovel[k] || {}; });
const params = new URLSearchParams(location.search);
const novelId = params.get('id');
let pageI18n;
let rerenderPayload = null;
let cachedSeriesNav = null;
let novelView = null;
let availableContentLangs = [];
let activeContentLang = '';
let contentLangCtl = null;

const HEART_DEFAULT_SVG = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"/></svg>';

const collectionState = {
    list: [],
    membership: new Set()
};

function escapeHtml(s) {
    return PixivNovelRender.escapeHtml(s);
}

function toast(msg, type) {
    const c = document.getElementById('toastContainer');
    if (!c) return;
    const el = document.createElement('div');
    el.className = 'toast' + (type ? ' ' + type : '');
    el.textContent = msg;
    c.appendChild(el);
    setTimeout(() => el.remove(), 2800);
}


// ---- PixivNovel facade ----
window.PixivNovel.core = window.PixivNovel.core || {};
window.PixivNovel.core = Object.assign(window.PixivNovel.core, { params, novelId, collectionState, HEART_DEFAULT_SVG, escapeHtml, toast });
