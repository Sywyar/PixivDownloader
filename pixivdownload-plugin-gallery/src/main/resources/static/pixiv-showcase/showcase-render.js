'use strict';

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
