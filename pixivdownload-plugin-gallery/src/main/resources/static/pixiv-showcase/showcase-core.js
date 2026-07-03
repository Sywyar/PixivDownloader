'use strict';

const HEART_SVG = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"/></svg>';
let pageI18n = null;

function interpolate(template, vars) {
    if (!vars) {
        return String(template);
    }
    return String(template).replace(/\{([a-zA-Z0-9_.-]+)\}/g, (match, name) => {
        return Object.prototype.hasOwnProperty.call(vars, name) ? String(vars[name]) : match;
    });
}

function wt(key, fallback, vars) {
    if (pageI18n) {
        return pageI18n.t('showcase:' + key, fallback, vars);
    }
    return interpolate(fallback != null ? fallback : key, vars);
}

function applyLabelBindings() {
    document.querySelectorAll('[data-i18n-label]').forEach(element => {
        element.dataset.label = wt(element.dataset.i18nLabel, element.dataset.label || '');
    });
}

function localizedArtworkTitle(artwork = state.artwork) {
    if (!artwork) {
        return '';
    }
    return artwork.title || wt('status.unknown-artwork', 'Artwork {id}', {id: artwork.artworkId});
}

function applyStaticPageTranslations() {
    if (pageI18n) {
        pageI18n.apply(document);
    }
    applyLabelBindings();
    document.title = state.artwork
        ? wt('page.artwork-title', '{title} - Artwork Showcase', {title: localizedArtworkTitle()})
        : wt('page.title', 'Artwork Showcase - Pixiv Gallery');
}

async function initPageI18n() {
    pageI18n = await PixivI18n.create({namespaces: ['showcase', 'common']});
    const controls = document.getElementById('topBarControls');
    await PixivLangSwitcher.mount({
        mountPoint: controls,
        i18n: pageI18n,
        onChange: function (nextClient) {
            pageI18n = nextClient;
            rerenderLocalizedContent();
        }
    });
    PixivTheme.mount({mountPoint: controls});
    applyStaticPageTranslations();
}

function rerenderLocalizedContent() {
    applyStaticPageTranslations();
    if (!state.artwork) {
        return;
    }
    updateDetailPageLink(state.artworkId);
    renderHero();
    renderArtworkPage();
    renderDetails();
    renderArtist();
    renderRelatedGrid();
    renderPages();
}

    const ImageCache = (() => {
        const PREFIX = 'pxImg:';
        const mem = new Map();
        const isThumb = url => url.includes('/thumbnail/');

        function get(url) {
            if (mem.has(url)) return mem.get(url);
            if (isThumb(url)) {
                try {
                    const v = sessionStorage.getItem(PREFIX + url);
                    if (v) {
                        mem.set(url, v);
                        return v;
                    }
                } catch (_) {}
            }
            return null;
        }

        function put(url, dataUri) {
            mem.set(url, dataUri);
            if (!isThumb(url)) return;
            try {
                sessionStorage.setItem(PREFIX + url, dataUri);
            } catch (_) {
                const keys = [];
                for (let i = 0; i < sessionStorage.length; i++) {
                    const k = sessionStorage.key(i);
                    if (k && k.startsWith(PREFIX)) keys.push(k);
                }
                keys.slice(0, Math.max(1, Math.floor(keys.length / 2)))
                    .forEach(k => { try { sessionStorage.removeItem(k); } catch (_) {} });
                try { sessionStorage.setItem(PREFIX + url, dataUri); } catch (_) {}
            }
        }

        return { get, put };
    })();

    const state = {
        artworkId: null,
        artwork: null,
        collections: [],
        collectionMembership: new Set(),
        lightboxIndex: 0,
        lightboxImages: [],
        currentSection: 0,
        totalSections: 6,
        relatedOffset: 0,
        relatedItems: [],
    };

    function getQueryParam(name) {
        return new URLSearchParams(location.search).get(name);
    }

    async function api(url, options = {}) {
        const res = await fetch(url, {
            credentials: 'same-origin',
            ...options,
            headers: {
                'Accept': 'application/json',
                ...(options.headers || {}),
            },
        });
        if (res.status === 401) {
            window.location.href = '/login.html?redirect=' + encodeURIComponent(location.pathname + location.search);
            throw new Error('Unauthorized');
        }
        if (!res.ok) {
            let msg = `HTTP ${res.status}`;
            try {
                const j = await res.json();
                if (j && j.error) msg = j.error;
            } catch (_) {}
            throw new Error(msg);
        }
        const ct = res.headers.get('content-type') || '';
        if (ct.includes('application/json')) return res.json();
        return res.text();
    }

    function toast(msg, type = '') {
        const container = document.getElementById('toastContainer');
        const el = document.createElement('div');
        el.className = 'toast' + (type ? ' ' + type : '');
        el.textContent = msg;
        container.appendChild(el);
        setTimeout(() => el.remove(), 2800);
    }

    function escapeHtml(s) {
        if (s == null) return '';
        return String(s).replace(/[&<>"']/g, m => ({
            '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
        }[m]));
    }

    function formatTime(ts) {
        if (!ts) return '';
        const d = new Date(ts);
        const pad = n => String(n).padStart(2, '0');
        return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
    }

    function buildGalleryFilterHref({authorId, authorName} = {}) {
        const params = new URLSearchParams();
        params.set('view', 'all');
        if (authorId != null) params.set('filterAuthorId', String(authorId));
        if (authorName) params.set('filterAuthorName', authorName);
        return `/pixiv-gallery.html?${params.toString()}`;
    }

    async function loadImage(url) {
        const cached = ImageCache.get(url);
        if (cached) return cached;
        try {
            const resp = await api(url);
            if (resp && resp.success && resp.image) {
                const ext = (resp.extension || 'jpg').toLowerCase();
                const src = `data:image/${ext === 'jpg' ? 'jpeg' : ext};base64,${resp.image}`;
                ImageCache.put(url, src);
                return src;
            }
        } catch (e) {}
        return null;
    }

    async function setImage(element, url) {
        const src = await loadImage(url);
        if (src) {
            if (element.tagName === 'IMG') {
                element.src = src;
            } else {
                element.style.backgroundImage = `url(${src})`;
                element.style.backgroundSize = 'cover';
                element.style.backgroundPosition = 'center';
            }
            return src;
        }
        return null;
    }

    async function setBgImage(element, url) {
        const src = await loadImage(url);
        if (src) {
            const img = document.createElement('img');
            img.src = src;
            img.alt = '';
            element.innerHTML = '';
            element.appendChild(img);
            return src;
        }
        return null;
    }
