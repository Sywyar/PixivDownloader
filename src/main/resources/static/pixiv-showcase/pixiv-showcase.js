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

    async function loadArtwork() {
        const id = getQueryParam('id');
        if (!id) {
            document.getElementById('heroTitle').textContent = wt('status.missing-id', 'Missing id parameter');
            return;
        }
        state.artworkId = Number(id);
        updateDetailPageLink(id);
        try {
            state.artwork = await api(`/api/gallery/artwork/${id}`);
        } catch (e) {
            document.getElementById('heroTitle').textContent = wt('status.artwork-load-failed', 'Load failed: {message}', {message: e.message});
            return;
        }
        applyStaticPageTranslations();
        renderHero();
        renderArtworkPage();
        renderDetails();
        renderArtist();
        loadRelated();
        renderPages();
    }

    function updateDetailPageLink(id) {
        const href = `/pixiv-artwork.html?id=${encodeURIComponent(id)}`;
        const detailLink = document.getElementById('detailPageLink');
        const backDetailLink = document.getElementById('pagesBackDetail');
        if (detailLink) detailLink.href = href;
        if (backDetailLink) backDetailLink.href = href;
        document.getElementById('detailPageLinkLabel').textContent = wt('button.detail', 'Detail');
        document.getElementById('pagesBackDetailLabel').textContent = wt('button.back-detail', 'Back to Detail');
    }

    const COMFORT_COLORS = [
        { name: 'Pearl White',     hex: '#F0EDE5', r: 240, g: 237, b: 229 },
        { name: 'Warm Ivory',      hex: '#E8E0D0', r: 232, g: 224, b: 208 },
        { name: 'Soft Cream',      hex: '#F5E6CC', r: 245, g: 230, b: 204 },
        { name: 'Blush Pink',      hex: '#F2D7D5', r: 242, g: 215, b: 213 },
        { name: 'Rose Mist',       hex: '#E8C4C4', r: 232, g: 196, b: 196 },
        { name: 'Coral Kiss',      hex: '#F0B8A8', r: 240, g: 184, b: 168 },
        { name: 'Peach Bloom',     hex: '#F5C6AA', r: 245, g: 198, b: 170 },
        { name: 'Salmon Glow',     hex: '#E8A598', r: 232, g: 165, b: 152 },
        { name: 'Lavender Haze',   hex: '#D4C5E2', r: 212, g: 197, b: 226 },
        { name: 'Wisteria Dream',  hex: '#C4B0D8', r: 196, g: 176, b: 216 },
        { name: 'Lilac Whisper',   hex: '#DCD0F0', r: 220, g: 208, b: 240 },
        { name: 'Sky Breath',      hex: '#B8D4E8', r: 184, g: 212, b: 232 },
        { name: 'Arctic Blue',     hex: '#A8C8E0', r: 168, g: 200, b: 224 },
        { name: 'Ocean Foam',      hex: '#9CC8D8', r: 156, g: 200, b: 216 },
        { name: 'Mint Cloud',      hex: '#B8E0D0', r: 184, g: 224, b: 208 },
        { name: 'Sage Breeze',     hex: '#A8D0B8', r: 168, g: 208, b: 184 },
        { name: 'Spring Dew',      hex: '#C0E0A8', r: 192, g: 224, b: 168 },
        { name: 'Lemon Sorbet',    hex: '#F0E8A0', r: 240, g: 232, b: 160 },
        { name: 'Honey Glow',      hex: '#E8D8A0', r: 232, g: 216, b: 160 },
        { name: 'Champagne Gold',  hex: '#E0D0A8', r: 224, g: 208, b: 168 },
        { name: 'Silver Frost',    hex: '#C8D0D8', r: 200, g: 208, b: 216 },
        { name: 'Slate Moon',      hex: '#A8B8C8', r: 168, g: 184, b: 200 },
        { name: 'Dusty Rose',      hex: '#D4A8A8', r: 212, g: 168, b: 168 },
        { name: 'Mauve Dusk',      hex: '#C8A0B8', r: 200, g: 160, b: 184 },
    ];

    function colorDistance(c1, c2) {
        const dr = c1.r - c2.r;
        const dg = c1.g - c2.g;
        const db = c1.b - c2.b;
        return dr * dr + dg * dg + db * db;
    }

    function invertColor(r, g, b) {
        return { r: 255 - r, g: 255 - g, b: 255 - b };
    }

    function findClosestPaletteColor(r, g, b) {
        const target = { r, g, b };
        let closest = COMFORT_COLORS[0];
        let minDist = colorDistance(target, closest);
        for (let i = 1; i < COMFORT_COLORS.length; i++) {
            const d = colorDistance(target, COMFORT_COLORS[i]);
            if (d < minDist) {
                minDist = d;
                closest = COMFORT_COLORS[i];
            }
        }
        return closest;
    }

    function extractAverageColor(imgElement) {
        try {
            const canvas = document.createElement('canvas');
            const ctx = canvas.getContext('2d');
            const size = 32;
            canvas.width = size;
            canvas.height = size;
            ctx.drawImage(imgElement, 0, 0, size, size);
            const data = ctx.getImageData(0, 0, size, size).data;
            let rSum = 0, gSum = 0, bSum = 0, count = 0;
            for (let i = 0; i < data.length; i += 4) {
                rSum += data[i];
                gSum += data[i + 1];
                bSum += data[i + 2];
                count++;
            }
            return {
                r: Math.round(rSum / count),
                g: Math.round(gSum / count),
                b: Math.round(bSum / count),
            };
        } catch (e) {
            return null;
        }
    }

    function applyHeroAccentColor(avgColor) {
        const inverted = invertColor(avgColor.r, avgColor.g, avgColor.b);
        const palette = findClosestPaletteColor(inverted.r, inverted.g, inverted.b);
        const root = document.documentElement;
        root.style.setProperty('--hero-accent', palette.hex);
        root.style.setProperty('--hero-accent-glow', palette.hex + '4D');
        root.style.setProperty('--hero-accent-bg', palette.hex + '14');
        const textR = Math.min(255, palette.r + 10);
        const textG = Math.min(255, palette.g + 10);
        const textB = Math.min(255, palette.b + 10);
        root.style.setProperty('--hero-text', `rgb(${textR}, ${textG}, ${textB})`);
        const secR = Math.round(palette.r * 0.85);
        const secG = Math.round(palette.g * 0.85);
        const secB = Math.round(palette.b * 0.85);
        root.style.setProperty('--hero-text-secondary', `rgb(${secR}, ${secG}, ${secB})`);
        const mutR = Math.round(palette.r * 0.65);
        const mutG = Math.round(palette.g * 0.65);
        const mutB = Math.round(palette.b * 0.65);
        root.style.setProperty('--hero-muted', `rgb(${mutR}, ${mutG}, ${mutB})`);
    }

    function triggerHeroAnimations() {
        const artwork = document.getElementById('heroArtwork');
        const label = document.querySelector('.hero-label');
        const title = document.getElementById('heroTitle');
        const author = document.getElementById('heroAuthor');
        const desc = document.getElementById('heroDesc');
        const tags = document.getElementById('heroTags');

        requestAnimationFrame(() => {
            artwork.classList.add('animate-in');
            setTimeout(() => label.classList.add('animate-in'), 200);
            setTimeout(() => title.classList.add('animate-in'), 400);
            setTimeout(() => author.classList.add('animate-in'), 600);
            setTimeout(() => desc.classList.add('animate-in'), 800);
            setTimeout(() => tags.classList.add('animate-in'), 1000);
        });
    }

    async function renderHero() {
        const a = state.artwork;
        const thumbUrl = `/api/downloaded/thumbnail/${a.artworkId}/0`;

        setBgImage(document.getElementById('heroBg'), thumbUrl);
        const heroSrc = await setImage(document.getElementById('heroArtworkImg'), thumbUrl);

        if (heroSrc) {
            const imgEl = document.getElementById('heroArtworkImg');
            if (imgEl.complete && imgEl.naturalWidth > 0) {
                const avg = extractAverageColor(imgEl);
                if (avg) applyHeroAccentColor(avg);
            } else {
                imgEl.addEventListener('load', () => {
                    const avg = extractAverageColor(imgEl);
                    if (avg) applyHeroAccentColor(avg);
                }, { once: true });
            }
        }

        document.getElementById('heroTitle').textContent = localizedArtworkTitle(a);

        const badges = [];
        if (a.xRestrict === 2) badges.push('<span class="hero-badge r18">R-18G</span>');
        else if (a.xRestrict === 1) badges.push('<span class="hero-badge r18">R-18</span>');
        if (a.isAi) badges.push('<span class="hero-badge ai">AI</span>');
        if ((a.count || 1) > 1) badges.push(`<span class="hero-badge pages">${a.count}P</span>`);
        document.getElementById('heroBadges').innerHTML = badges.join('');

        const name = a.authorName || wt('artist.fallback-name', 'Artist {id}', {id: a.authorId || ''});
        document.getElementById('heroAuthorAvatar').textContent = (name[0] || '?').toUpperCase();
        document.getElementById('heroAuthorName').textContent = name;
        document.getElementById('heroAuthorId').textContent = a.authorId ? `ID: ${a.authorId}` : '';

        const desc = a.description ? a.description.replace(/<[^>]*>/g, '').trim() : '';
        document.getElementById('heroDesc').textContent = desc || wt('status.no-description', 'No description');

        const tags = a.tags || [];
        document.getElementById('heroTags').innerHTML = tags.slice(0, 8).map(t =>
            `<span class="hero-tag">${escapeHtml(t.name)}${t.translatedName ? `<span class="tag-trans">${escapeHtml(t.translatedName)}</span>` : ''}</span>`
        ).join('');

        document.getElementById('heroArtwork').onclick = () => {
            if (heroSrc) openLightbox(0, [heroSrc]);
        };

        triggerHeroAnimations();
    }

    async function renderArtworkPage() {
        const a = state.artwork;
        const imageUrl = `/api/downloaded/image/${a.artworkId}/0`;
        const thumbUrl = `/api/downloaded/thumbnail/${a.artworkId}/0`;

        setBgImage(document.getElementById('artworkBg'), thumbUrl);
        const src = await setImage(document.getElementById('artworkImg'), imageUrl);

        document.getElementById('artworkOverlayTitle').textContent = localizedArtworkTitle(a);
        document.getElementById('artworkOverlayAuthor').textContent = a.authorName || '';

        if (src) {
            state.lightboxImages = [src];
            document.getElementById('artworkFrame').onclick = () => openLightbox(0, state.lightboxImages);
        }
    }

    async function renderDetails() {
        const a = state.artwork;
        const thumbUrl = `/api/downloaded/thumbnail/${a.artworkId}/0`;

        setBgImage(document.getElementById('detailsBg'), thumbUrl);
        setImage(document.getElementById('detailsThumbImg'), thumbUrl);

        document.getElementById('detailsTitle').textContent = localizedArtworkTitle(a);

        const stats = [];
        stats.push(`<div class="details-stat"><div class="details-stat-value">${a.artworkId}</div><div class="details-stat-label">${escapeHtml(wt('stats.artwork-id', 'Artwork ID'))}</div></div>`);
        stats.push(`<div class="details-stat"><div class="details-stat-value">${a.count || 1}</div><div class="details-stat-label">${escapeHtml(wt('stats.pages', 'Pages'))}</div></div>`);
        if (a.time) stats.push(`<div class="details-stat"><div class="details-stat-value">${formatTime(a.time).split(' ')[0]}</div><div class="details-stat-label">${escapeHtml(wt('stats.download-date', 'Download Date'))}</div></div>`);
        const ext = (a.extensions || 'jpg').toUpperCase();
        stats.push(`<div class="details-stat"><div class="details-stat-value">${ext}</div><div class="details-stat-label">${escapeHtml(wt('stats.format', 'Format'))}</div></div>`);
        document.getElementById('detailsStats').innerHTML = stats.join('');

        const descEl = document.getElementById('detailsDesc');
        if (a.description && a.description.trim()) {
            descEl.innerHTML = a.description;
            descEl.querySelectorAll('a').forEach(el => { el.target = '_blank'; el.rel = 'noopener'; });
        } else {
            descEl.textContent = wt('status.no-description', 'No description');
        }

        const tags = a.tags || [];
        document.getElementById('detailsTags').innerHTML = tags.map(t =>
            `<span class="details-tag">${escapeHtml(t.name)}${t.translatedName ? `<span class="tag-trans">${escapeHtml(t.translatedName)}</span>` : ''}</span>`
        ).join('');
    }

    async function renderArtist() {
        const a = state.artwork;
        if (!a.authorId) return;

        const name = a.authorName || wt('artist.fallback-name', 'Artist {id}', {id: a.authorId});
        document.getElementById('artistAvatar').textContent = (name[0] || '?').toUpperCase();
        document.getElementById('artistName').textContent = name;
        document.getElementById('artistId').textContent = `ID: ${a.authorId}`;
        document.getElementById('artistIdStat').textContent = a.authorId;

        document.getElementById('artistViewAll').onclick = () => {
            window.location.href = buildGalleryFilterHref({authorId: a.authorId, authorName: name});
        };

        try {
            const items = await api(`/api/gallery/artwork/${state.artworkId}/by-author?limit=9`);
            document.getElementById('artistWorkCount').textContent = items.length > 0 ? `${items.length}+` : '0';
            const grid = document.getElementById('artistWorksGrid');
            grid.innerHTML = items.slice(0, 9).map(item => `
                <div class="artist-work-card" data-id="${item.artworkId}">
                    <img data-src="/api/downloaded/thumbnail/${item.artworkId}/0" alt="${escapeHtml(item.title || '')}" loading="lazy">
                    <div class="artist-work-card-overlay">
                        <span class="artist-work-card-title">${escapeHtml(item.title || '')}</span>
                    </div>
                </div>
            `).join('');
            grid.querySelectorAll('.artist-work-card').forEach(card => {
                card.addEventListener('click', () => {
                    window.location.href = `/pixiv-showcase.html?id=${card.dataset.id}`;
                });
                const img = card.querySelector('img[data-src]');
                if (img) loadThumbLazy(img);
            });
        } catch (e) {
            document.getElementById('artistWorkCount').textContent = '0';
        }
    }

    async function loadRelated() {
        try {
            const items = await api(`/api/gallery/artwork/${state.artworkId}/related?limit=30`);
            state.relatedItems = items || [];
            renderRelatedGrid();
        } catch (e) {
            state.relatedItems = [];
        }
    }

    function renderRelatedGrid() {
        const items = state.relatedItems;
        const grid = document.getElementById('relatedGrid');
        if (!items.length) {
            grid.innerHTML = `<div class="loading-state related-empty">${escapeHtml(wt('status.no-related', 'No related artworks'))}</div>`;
            return;
        }
        const display = items.slice(0, 10);
        grid.innerHTML = display.map(item => `
            <div class="related-card" data-id="${item.artworkId}">
                <img data-src="/api/downloaded/thumbnail/${item.artworkId}/0" alt="${escapeHtml(item.title || '')}" loading="lazy">
                <div class="related-card-overlay">
                    <div class="related-card-title">${escapeHtml(item.title || '')}</div>
                    <div class="related-card-author">${escapeHtml(item.authorName || '')}</div>
                </div>
            </div>
        `).join('');
        grid.querySelectorAll('.related-card').forEach(card => {
            card.addEventListener('click', () => {
                window.location.href = `/pixiv-showcase.html?id=${card.dataset.id}`;
            });
            const img = card.querySelector('img[data-src]');
            if (img) loadThumbLazy(img);
        });
    }

    async function renderPages() {
        const a = state.artwork;
        const count = a.count || 1;
        const thumbUrl = `/api/downloaded/thumbnail/${a.artworkId}/0`;

        setBgImage(document.getElementById('pagesBg'), thumbUrl);

        document.getElementById('pagesTitle').textContent = count > 1
            ? wt('pages.all-title', 'All {count} Pages', {count})
            : wt('pages.single-title', 'Single-Page Artwork');
        document.getElementById('pagesSubtitle').textContent = count > 1
            ? wt('pages.all-subtitle', 'Click a thumbnail to view the full image. {count} pages total.', {count})
            : wt('pages.single-subtitle', 'This artwork is a single-page illustration.');

        const strip = document.getElementById('pagesStrip');
        strip.innerHTML = '';
        if (count > 1) {
            const maxShow = Math.min(count, 8);
            for (let i = 0; i < maxShow; i++) {
                const item = document.createElement('div');
                item.className = 'pages-strip-item';
                item.innerHTML = `<img data-src="/api/downloaded/thumbnail/${a.artworkId}/${i}" alt="${escapeHtml(wt('pages.page-alt', 'Page {page}', {page: i + 1}))}" loading="lazy"><div class="pages-strip-item-index">${i + 1}</div>`;
                item.addEventListener('click', () => loadAndOpenLightbox(i));
                strip.appendChild(item);
                const img = item.querySelector('img[data-src]');
                if (img) loadThumbLazy(img);
            }
            if (count > maxShow) {
                const more = document.createElement('div');
                more.className = 'pages-strip-item';
                more.classList.add('pages-strip-more');
                more.innerHTML = `<span class="pages-strip-more-text">+${count - maxShow}</span>`;
                strip.appendChild(more);
            }
        } else {
            const item = document.createElement('div');
            item.className = 'pages-strip-item';
            item.innerHTML = `<img data-src="/api/downloaded/thumbnail/${a.artworkId}/0" alt="" loading="lazy"><div class="pages-strip-item-index">1</div>`;
            item.addEventListener('click', () => loadAndOpenLightbox(0));
            strip.appendChild(item);
            const img = item.querySelector('img[data-src]');
            if (img) loadThumbLazy(img);
        }

        document.getElementById('pagesViewAll').onclick = () => loadAndOpenLightbox(0);
    }

    async function loadAndOpenLightbox(pageIndex) {
        const a = state.artwork;
        const count = a.count || 1;
        if (state.lightboxImages.length === count && state.lightboxImages[pageIndex]) {
            openLightbox(pageIndex, state.lightboxImages);
            return;
        }
        state.lightboxImages = new Array(count).fill(null);
        const firstSrc = await loadImage(`/api/downloaded/image/${a.artworkId}/${pageIndex}`);
        if (firstSrc) state.lightboxImages[pageIndex] = firstSrc;
        openLightbox(pageIndex, state.lightboxImages);
        for (let i = 0; i < count; i++) {
            if (i === pageIndex) continue;
            loadImage(`/api/downloaded/image/${a.artworkId}/${i}`).then(src => {
                if (src) state.lightboxImages[i] = src;
            });
        }
    }

    async function loadThumbLazy(img) {
        const url = img.dataset.src;
        if (!url) return;
        img.removeAttribute('data-src');
        const src = await loadImage(url);
        if (src) img.src = src;
    }

    function openLightbox(index, images) {
        if (images) state.lightboxImages = images;
        if (!state.lightboxImages[index]) {
            toast(wt('status.image-loading', 'Image is loading. Please wait.'));
            return;
        }
        state.lightboxIndex = index;
        document.getElementById('lightboxImage').src = state.lightboxImages[index];
        document.getElementById('lightboxInfo').textContent = `${index + 1} / ${state.lightboxImages.length}`;
        document.getElementById('lightbox').classList.add('open');
    }

    function closeLightbox() {
        document.getElementById('lightbox').classList.remove('open');
    }

    function handleLightboxClick(e) {
        if (e.target.id === 'lightbox' || e.target.id === 'lightboxImage') closeLightbox();
    }

    function lightboxNav(delta) {
        const total = state.lightboxImages.length;
        let next = state.lightboxIndex + delta;
        if (next < 0) next = total - 1;
        if (next >= total) next = 0;
        if (!state.lightboxImages[next]) {
            toast(wt('status.image-loading', 'Image is loading. Please wait.'));
            return;
        }
        openLightbox(next);
    }

    document.addEventListener('keydown', e => {
        if (document.getElementById('lightbox').classList.contains('open')) {
            if (e.key === 'Escape') closeLightbox();
            else if (e.key === 'ArrowLeft') lightboxNav(-1);
            else if (e.key === 'ArrowRight') lightboxNav(1);
            return;
        }
        if (e.key === 'ArrowDown' || e.key === 'PageDown') {
            e.preventDefault();
            scrollToSection(state.currentSection + 1);
        } else if (e.key === 'ArrowUp' || e.key === 'PageUp') {
            e.preventDefault();
            scrollToSection(state.currentSection - 1);
        } else if (e.key === 'Home') {
            e.preventDefault();
            scrollToSection(0);
        } else if (e.key === 'End') {
            e.preventDefault();
            scrollToSection(state.totalSections - 1);
        }
    });

    function scrollToSection(index) {
        if (index < 0 || index >= state.totalSections) return;
        const section = document.getElementById(`section${index}`);
        if (section) section.scrollIntoView({ behavior: 'smooth' });
    }

    const scrollContainer = document.getElementById('scrollContainer');
    const sections = document.querySelectorAll('.section');
    const dots = document.querySelectorAll('.side-nav-dot');

    dots.forEach(dot => {
        dot.addEventListener('click', () => {
            scrollToSection(Number(dot.dataset.index));
        });
    });

    const sectionObserver = new IntersectionObserver(entries => {
        entries.forEach(entry => {
            if (entry.isIntersecting) {
                const index = Number(entry.target.id.replace('section', ''));
                state.currentSection = index;
                updateNav(index);
            }
        });
    }, {
        root: scrollContainer,
        threshold: 0.5,
    });

    sections.forEach(section => sectionObserver.observe(section));

    function updateNav(index) {
        dots.forEach((dot, i) => {
            dot.classList.toggle('active', i === index);
        });
        document.getElementById('pageCounter').innerHTML =
            `<span class="current">${String(index + 1).padStart(2, '0')}</span><span class="separator">/</span><span>${String(state.totalSections).padStart(2, '0')}</span>`;

        const hint = document.getElementById('scrollHint');
        if (index > 0) hint.classList.add('hidden');
        else hint.classList.remove('hidden');
    }

    async function loadCollections() {
        try {
            const resp = await api('/api/collections');
            state.collections = resp.collections || [];
        } catch (e) {
            state.collections = [];
        }
    }

    async function loadMembership() {
        try {
            const resp = await api(`/api/collections/of/${state.artworkId}`);
            state.collectionMembership = new Set(resp.collectionIds || []);
            updateHeartBtn();
        } catch (e) {}
    }

    function updateHeartBtn() {
        const btn = document.getElementById('heartBtnTop');
        if (!btn) return;
        const liked = state.collectionMembership.size > 0;
        if (liked) {
            btn.style.borderColor = 'var(--danger)';
            btn.style.color = 'var(--danger)';
            btn.style.background = 'rgba(220, 38, 38, 0.1)';
        } else {
            btn.style.borderColor = '';
            btn.style.color = '';
            btn.style.background = '';
        }
    }

    function iconHtml(collection) {
        if (collection.iconExt) {
            return `<img src="/api/collections/${collection.id}/icon?v=${collection.createdTime}" alt="">`;
        }
        return HEART_SVG;
    }

    async function openAddToCollectionModal() {
        await loadCollections();
        await loadMembership();
        renderAddToCollectionList();
        document.getElementById('modalAddToCollection').classList.add('open');
    }

    function closeAddToCollectionModal() {
        document.getElementById('modalAddToCollection').classList.remove('open');
        updateHeartBtn();
    }

    function renderAddToCollectionList() {
        const list = document.getElementById('addToCollectionList');
        if (!state.collections.length) {
            list.innerHTML = `<div class="collection-empty">${escapeHtml(wt('status.no-collections', 'No collections yet. Create one first.'))}</div>`;
            return;
        }
        list.innerHTML = state.collections.map(c => `
            <label class="collection-row" data-id="${c.id}">
                <input type="checkbox" ${state.collectionMembership.has(c.id) ? 'checked' : ''}>
                <div class="collection-row-icon">${iconHtml(c)}</div>
                <div class="collection-row-name">${escapeHtml(c.name)}</div>
                <span class="collection-count">${c.artworkCount}</span>
            </label>
        `).join('');
        list.querySelectorAll('.collection-row').forEach(row => {
            const id = Number(row.dataset.id);
            const cb = row.querySelector('input[type="checkbox"]');
            cb.addEventListener('change', async () => {
                try {
                    if (cb.checked) {
                        await api(`/api/collections/${id}/artworks/${state.artworkId}`, { method: 'POST' });
                        state.collectionMembership.add(id);
                    } else {
                        await api(`/api/collections/${id}/artworks/${state.artworkId}`, { method: 'DELETE' });
                        state.collectionMembership.delete(id);
                    }
                    updateHeartBtn();
                } catch (e) {
                    toast(e.message || wt('toast.action-failed', 'Action failed'), 'error');
                    cb.checked = !cb.checked;
                }
            });
        });
    }

    document.getElementById('quickCreateBtn').addEventListener('click', async () => {
        const input = document.getElementById('quickCreateName');
        const name = input.value.trim();
        if (!name) return;
        const btn = document.getElementById('quickCreateBtn');
        btn.disabled = true;
        try {
            const c = await api('/api/collections', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ name }),
            });
            input.value = '';
            state.collections.push(c);
            state.collections.sort((a, b) => a.sortOrder - b.sortOrder || a.id - b.id);
            await api(`/api/collections/${c.id}/artworks/${state.artworkId}`, { method: 'POST' });
            state.collectionMembership.add(c.id);
            renderAddToCollectionList();
            updateHeartBtn();
            toast(wt('toast.created', 'Created'), 'success');
        } catch (e) {
            toast(e.message || wt('toast.create-failed', 'Creation failed'), 'error');
        } finally {
            btn.disabled = false;
        }
    });

    document.addEventListener('click', e => {
        const backdrop = e.target.classList && e.target.classList.contains('modal-backdrop');
        if (backdrop) e.target.classList.remove('open');
    });

    function bindPageEvents() {
        document.getElementById('lightbox').addEventListener('click', handleLightboxClick);
        document.getElementById('lightboxClose').addEventListener('click', closeLightbox);
        document.getElementById('lightboxPrev').addEventListener('click', event => {
            event.stopPropagation();
            lightboxNav(-1);
        });
        document.getElementById('lightboxNext').addEventListener('click', event => {
            event.stopPropagation();
            lightboxNav(1);
        });
        document.getElementById('modalCloseBtn').addEventListener('click', closeAddToCollectionModal);
        document.getElementById('addToCollectionDoneBtn').addEventListener('click', closeAddToCollectionModal);
    }

    (async function initShowcasePage() {
        await initPageI18n();
        bindPageEvents();
        loadArtwork();
    })();
