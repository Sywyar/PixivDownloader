'use strict';

const PAGE_SIZE = 24;
const HEART_SVG = '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z"/></svg>';
const SIDEBAR_STATE_STORAGE_KEY = 'pixiv:gallery-sidebar-state';
const GALLERY_VIEW_VALUES = ['all', 'authors', 'series'];

function parsePositiveIdList(raw) {
    return [...new Set(String(raw || '')
        .split(',')
        .map(value => Number(value))
        .filter(value => Number.isInteger(value) && value > 0))];
}

function normalizeFilterText(value) {
    return String(value || '').trim().toLowerCase();
}

function readViewParam(params) {
    const view = params.get('view');
    return GALLERY_VIEW_VALUES.includes(view) ? view : null;
}

function normalizeView(view) {
    return GALLERY_VIEW_VALUES.includes(view) ? view : 'all';
}

function buildGalleryPageHref(path, view = state.view) {
    const params = new URLSearchParams();
    params.set('view', normalizeView(view));
    return `${path}?${params.toString()}`;
}

function syncViewParamInUrl() {
    const url = new URL(location.href);
    url.searchParams.set('view', normalizeView(state.view));
    const nextUrl = url.pathname + (url.search ? url.search : '') + url.hash;
    const currentUrl = location.pathname + location.search + location.hash;
    if (nextUrl !== currentUrl) {
        history.replaceState(history.state, '', nextUrl);
    }
}

function syncViewNavigationHrefs() {
    document.querySelectorAll('.nav-item[data-view]').forEach(el => {
        if (el.tagName === 'A') {
            el.setAttribute('href', buildGalleryPageHref('/pixiv-novel-gallery.html', el.dataset.view));
        }
    });
}

let pageI18n;

// ---------- Persistence ----------
function storageGet(key) {
    try { return localStorage.getItem(key); } catch (_) { return null; }
}
function storageSet(key, value) {
    try { localStorage.setItem(key, value); } catch (_) { /* ignore quota */ }
}
function storageRemove(key) {
    try { localStorage.removeItem(key); } catch (_) { /* ignore */ }
}

function toIdArray(value) {
    if (!Array.isArray(value)) return [];
    return value.map(Number).filter(n => Number.isFinite(n) && n > 0);
}

let lastTimer;
function debounce(fn, ms) {
    return (...args) => { clearTimeout(lastTimer); lastTimer = setTimeout(() => fn(...args), ms); };
}

function esc(s) {
    if (s == null) return '';
    return String(s).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
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
