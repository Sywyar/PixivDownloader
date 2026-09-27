'use strict';
    async function loadArtwork() {
        const id = getQueryParam('id');
        if (!id) {
            document.getElementById('viewerLoading').textContent = wt('status.missing-id', 'Missing id parameter');
            return;
        }
        state.artworkId = Number(id);
        try {
            state.artwork = await api(`/api/gallery/artwork/${id}`);
        } catch (e) {
            document.getElementById('viewerLoading').textContent = wt('status.artwork-load-failed', 'Load failed: {message}', {message: e.message});
            return;
        }
        document.getElementById('topbarTitle').textContent = `#${id}`;
        document.title = `${state.artwork.title || wt('status.unknown-artwork', 'Artwork {id}', {id})} - Pixiv Gallery`;
        renderViewer();
        renderDetail();
        renderAuthor();
        loadSeriesSections();
        loadRelated();
        loadMembership();
    }

    function renderViewer() {
        mainImageLoad?.abort();
        if (expanded) collapseAll();
        closeLightbox();
        originalPageObserver?.disconnect();
        originalVisibleBoxes.clear();
        clearArtworkPreviews();
        const artwork = state.artwork;
        const count = artwork.count || 1;
        document.getElementById('viewerLoading').style.display = 'none';
        document.getElementById('viewer').style.display = '';

        document.getElementById('totalPageCount').textContent = count;
        document.getElementById('metaInfo').textContent = wt('meta.pages', '{count} pages · {ext}', {
            count,
            ext: (artwork.extensions || '').toUpperCase()
        });

        const mainImage = document.getElementById('mainImage');
        mainImage.dataset.pageIndex = '0';
        mainImage.classList.add('loading');
        mainImage.onclick = () => openLightbox(0);
        if (typeof IntersectionObserver !== 'undefined') {
            originalPageObserver = new IntersectionObserver(entries => {
                for (const entry of entries) {
                    if (!entry.target.isConnected) continue;
                    if (entry.isIntersecting) originalVisibleBoxes.add(entry.target);
                    else originalVisibleBoxes.delete(entry.target);
                    updateBoxOriginal(entry.target);
                }
            });
            originalPageObserver.observe(mainImage);
        }
        mainImageLoad = new AbortController();
        loadImageToElement(artworkPreviewUrl(0), mainImage, {
            signal: mainImageLoad.signal,
            onLoad: () => updateBoxOriginal(mainImage),
            onError: () => updateBoxOriginal(mainImage)
        });
        refreshOriginalVisibility();

        const more = document.getElementById('morePages');
        more.innerHTML = '';
        const expandBtn = document.getElementById('expandBtn');
        const collapseBtn = document.getElementById('collapseBtn');
        expandBtn.style.display = count > 1 && !expanded ? '' : 'none';
        collapseBtn.style.display = expanded ? '' : 'none';
        expandBtn.onclick = () => expandAll(count);
        collapseBtn.onclick = collapseAll;
        syncExpandButtonText();
    }

    let expanded = false;
    let expandedPageObserver = null;
    const expandedPageLoads = new Map();
    const ORIGINAL_IMAGES_KEY = 'pixiv:artwork-original-images';
    const originalVisibleBoxes = new Set();
    let originalImagesEnabled = false;
    let originalPageObserver = null;
    let mainImageLoad = null;
    let originalRefreshFrame = null;

    function updateBoxOriginal(box) {
        const image = box.querySelector('img');
        if (!image) return;
        const visible = originalVisibleBoxes.has(box)
            && !document.getElementById('lightbox').classList.contains('open');
        if (originalImagesEnabled && visible) {
            loadArtworkOriginal(image, `/api/downloaded/image/${state.artworkId}/${box.dataset.pageIndex}`);
        } else {
            releaseArtworkOriginal(image);
        }
    }

    function refreshOriginalVisibility() {
        const boxes = [document.getElementById('mainImage'), ...expandedPageLoads.keys()];
        for (const box of boxes) {
            if (!originalPageObserver) {
                const rect = box.getBoundingClientRect();
                if (rect.bottom > 0 && rect.top < window.innerHeight && rect.right > 0 && rect.left < window.innerWidth) {
                    originalVisibleBoxes.add(box);
                } else originalVisibleBoxes.delete(box);
            }
            updateBoxOriginal(box);
        }
        const image = document.getElementById('lightboxImage');
        if (originalImagesEnabled && document.getElementById('lightbox').classList.contains('open')) {
            loadArtworkOriginal(image, `/api/downloaded/image/${state.artworkId}/${state.lightboxIndex}`);
        } else releaseArtworkOriginal(image);
    }

    function setOriginalImagesEnabled(enabled, persist = true) {
        originalImagesEnabled = enabled;
        for (const id of ['originalImagesToggle', 'lightboxOriginalImagesToggle']) {
            document.getElementById(id).checked = enabled;
        }
        if (persist) {
            try { localStorage.setItem(ORIGINAL_IMAGES_KEY, String(enabled)); } catch (_) { /* 存储不可用时保留本页选择。 */ }
        }
        refreshOriginalVisibility();
    }

    function readOriginalImagesPreference() {
        try { return localStorage.getItem(ORIGINAL_IMAGES_KEY) === 'true'; } catch (_) { return false; }
    }

    function initOriginalImages() {
        for (const id of ['originalImagesToggle', 'lightboxOriginalImagesToggle']) {
            document.getElementById(id).addEventListener('change', event => setOriginalImagesEnabled(event.target.checked));
        }
        setOriginalImagesEnabled(readOriginalImagesPreference(), false);
        window.addEventListener('storage', event => {
            if (event.key === ORIGINAL_IMAGES_KEY || event.key === null) {
                setOriginalImagesEnabled(readOriginalImagesPreference(), false);
            }
        });
        window.addEventListener('pageshow', () => setOriginalImagesEnabled(readOriginalImagesPreference(), false));
        document.addEventListener('visibilitychange', refreshOriginalVisibility);
        const scheduleRefresh = () => {
            if (originalPageObserver || originalRefreshFrame !== null) return;
            originalRefreshFrame = requestAnimationFrame(() => {
                originalRefreshFrame = null;
                refreshOriginalVisibility();
            });
        };
        window.addEventListener('scroll', scheduleRefresh, {passive: true});
        window.addEventListener('resize', scheduleRefresh);
    }

    function artworkPreviewUrl(index) {
        // GIF / WebP 可能是动图，静态缩略图不能替代其播放。
        const animatedFormat = /(?:^|,)(?:gif|webp|apng)(?:,|$)/i.test(state.artwork.extensions || '');
        const kind = animatedFormat ? 'image' : 'thumbnail';
        return `/api/downloaded/${kind}/${state.artworkId}/${index}`;
    }

    function loadExpandedPage(box) {
        if (expandedPageLoads.has(box)) return;
        const controller = new AbortController();
        expandedPageLoads.set(box, controller);
        if (!originalPageObserver) refreshOriginalVisibility();
        loadImageToElement(box.dataset.imageUrl, box, {
            signal: controller.signal,
            loading: expandedPageObserver ? 'eager' : 'lazy',
            onLoad: () => updateBoxOriginal(box),
            onError: () => updateBoxOriginal(box)
        }).then(src => {
            if (!src || controller.signal.aborted) return;
            const image = box.querySelector('img');
            box.style.aspectRatio = `${image.naturalWidth} / ${image.naturalHeight}`;
            box.style.maxHeight = `min(var(--artwork-height), ${image.naturalHeight}px)`;
        });
    }

    function unloadExpandedPage(box) {
        expandedPageLoads.get(box)?.abort();
        expandedPageLoads.delete(box);
        originalVisibleBoxes.delete(box);
        box.classList.add('loading');
    }

    function expandAll(count) {
        if (expanded) return Promise.resolve();
        expanded = true;
        const btn = document.getElementById('expandBtn');
        const collapseBtn = document.getElementById('collapseBtn');
        btn.style.display = 'none';
        collapseBtn.style.display = '';
        const more = document.getElementById('morePages');
        more.classList.add('open');
        if (typeof IntersectionObserver !== 'undefined') {
            expandedPageObserver = new IntersectionObserver(entries => {
                for (const entry of entries) {
                    if (!expanded || !entry.target.isConnected) continue;
                    if (entry.isIntersecting) loadExpandedPage(entry.target);
                    else unloadExpandedPage(entry.target);
                }
            }, {rootMargin: '200px'});
        }
        for (let p = 1; p < count; p++) {
            const box = document.createElement('div');
            box.className = 'viewer-image';
            box.classList.add('loading');
            box.setAttribute('data-loading-text', wt('status.loading', 'Loading...'));
            box.dataset.imageUrl = artworkPreviewUrl(p);
            box.dataset.pageIndex = String(p);
            more.appendChild(box);
            const idx = p;
            box.addEventListener('click', () => openLightbox(idx));
            if (expandedPageObserver) expandedPageObserver.observe(box);
            else loadExpandedPage(box);
            originalPageObserver?.observe(box);
        }
        return Promise.resolve();
    }

    function collapseAll() {
        expanded = false;
        expandedPageObserver?.disconnect();
        expandedPageObserver = null;
        for (const box of expandedPageLoads.keys()) unloadExpandedPage(box);
        const more = document.getElementById('morePages');
        for (const box of more.children) {
            originalPageObserver?.unobserve(box);
            originalVisibleBoxes.delete(box);
        }
        more.classList.remove('open');
        more.innerHTML = '';
        const btn = document.getElementById('expandBtn');
        const collapseBtn = document.getElementById('collapseBtn');
        btn.style.display = '';
        btn.disabled = false;
        syncExpandButtonText();
        collapseBtn.style.display = 'none';
    }

    function renderDetail() {
        const artwork = state.artwork;
        document.getElementById('detailPanel').style.display = '';
        document.getElementById('artworkTitle').textContent = artwork.title || wt('status.unknown-artwork', 'Artwork {id}', {id: artwork.artworkId});
        const pixivArtworkLink = document.getElementById('pixivArtworkLink');
        pixivArtworkLink.href = buildPixivArtworkHref(artwork.artworkId);
        pixivArtworkLink.style.display = 'inline-flex';
        const showcaseLink = document.getElementById('showcaseLink');
        showcaseLink.href = buildShowcaseHref(artwork.artworkId);
        showcaseLink.style.display = 'inline-flex';

        const stats = [];
        stats.push(`<span class="artwork-stat">${escapeHtml(wt('stats.id', 'ID: {id}', {id: artwork.artworkId}))}</span>`);
        if (artwork.time) stats.push(`<span class="artwork-stat">${escapeHtml(wt('stats.download-time', 'Downloaded at {time}', {time: formatTime(artwork.time)}))}</span>`);
        if (artwork.xRestrict === 2) stats.push('<span class="artwork-stat" style="color:#b91c1c">R-18G</span>');
        else if (artwork.xRestrict === 1) stats.push('<span class="artwork-stat" style="color:var(--danger)">R-18</span>');
        if (artwork.isAi) stats.push('<span class="artwork-stat" style="color:#8b5cf6">AI</span>');
        if (artwork.moved) stats.push(`<span class="artwork-stat" style="color:#10b981">${escapeHtml(wt('stats.moved', 'Moved to {folder}', {folder: artwork.moveFolder || ''}))}</span>`);
        document.getElementById('artworkStats').innerHTML = stats.join('');

        const desc = document.getElementById('artworkDesc');
        if (artwork.description && artwork.description.trim()) {
            desc.innerHTML = artwork.description;
            desc.querySelectorAll('a').forEach(a => {
                a.target = '_blank';
                a.rel = 'noopener';
            });
        } else {
            desc.innerHTML = '<span style="color:var(--muted)">' + escapeHtml(wt('status.no-description', 'No description')) + '</span>';
        }

        const tagList = document.getElementById('tagList');
        const tags = artwork.tags || [];
        if (tags.length) {
            tagList.innerHTML = tags.map(t => `
                <a class="tag tag-link" href="${buildGalleryFilterHref({
                    tagId: t.tagId,
                    tagName: t.name,
                    tagTranslatedName: t.translatedName
                })}">
                    ${escapeHtml(t.name)}
                    ${t.translatedName ? `<span class="tag-translated">${escapeHtml(t.translatedName)}</span>` : ''}
                </a>
            `).join('');
        } else {
            tagList.innerHTML = '<span style="color:var(--muted); font-size:12px">' + escapeHtml(wt('status.no-tags', 'No tags')) + '</span>';
        }
    }

    function renderAuthor() {
        const artwork = state.artwork;
        if (!artwork.authorId) return;
        document.getElementById('authorPanel').style.display = '';
        const name = artwork.authorName || wt('author.default', 'Author {id}', {id: artwork.authorId});
        document.getElementById('authorAvatar').textContent = (name[0] || '?').toUpperCase();
        document.getElementById('authorName').textContent = name;
        document.getElementById('authorId').textContent = `ID: ${artwork.authorId}`;
        const authorCard = document.getElementById('authorCard');
        authorCard.href = buildGalleryFilterHref({authorId: artwork.authorId, authorName: name});
        authorCard.title = wt('author.filter', 'Filter by author {name}', {name});
        authorCard.setAttribute('aria-label', wt('author.filter', 'Filter by author {name}', {name}));
        const pixivAuthorLink = document.getElementById('pixivAuthorLink');
        pixivAuthorLink.href = buildPixivAuthorHref(artwork.authorId);
        pixivAuthorLink.title = wt('author.pixiv', 'Open {name} on Pixiv', {name});
        pixivAuthorLink.setAttribute('aria-label', wt('author.pixiv', 'Open {name} on Pixiv', {name}));
        pixivAuthorLink.style.display = 'inline';

        loadByAuthor();
    }

    // ---------- Lightbox ----------
    function openLightbox(index) {
        const count = state.artwork?.count || 1;
        if (!Number.isInteger(index) || index < 0 || index >= count) return;
        state.lightboxIndex = index;
        const lightbox = document.getElementById('lightbox');
        const mainImage = document.getElementById('mainImage').querySelector('img');
        lightbox.style.setProperty('--lightbox-image-ratio', mainImage?.naturalHeight
            ? mainImage.naturalWidth / mainImage.naturalHeight : 1);
        lightbox.classList.add('open');
        const image = document.getElementById('lightboxImage');
        releaseArtworkOriginal(image, false);
        image.onerror = () => {
            toast(wt('status.load-failed', 'Load failed'));
            refreshOriginalVisibility();
        };
        const url = artworkPreviewUrl(index);
        image.onload = () => {
            lightbox.style.setProperty('--lightbox-image-ratio', image.naturalWidth / image.naturalHeight);
            if (!isArtworkOriginal(image) && url.includes('/thumbnail/')) {
                retainArtworkPreview(image);
                const fittedUrl = window.PixivLayout.previewUrl(url, image);
                if (image.getAttribute('src') !== fittedUrl) {
                    image.src = fittedUrl;
                    return;
                }
            }
            refreshOriginalVisibility();
        };
        image.src = url.includes('/thumbnail/') ? window.PixivLayout.previewUrl(url, image) : url;
        document.getElementById('lightboxInfo').textContent = `${index + 1} / ${count}`;
        refreshOriginalVisibility();
    }

    function closeLightbox() {
        document.getElementById('lightbox').classList.remove('open');
        const image = document.getElementById('lightboxImage');
        releaseArtworkOriginal(image, false);
        image.onload = null;
        image.onerror = null;
        image.removeAttribute('src');
        image.removeAttribute('data-preview-src');
        refreshOriginalVisibility();
    }

    function handleLightboxClick(e) {
        if (e.target.id === 'lightbox' || e.target.id === 'lightboxImage'
            || e.target.classList.contains('lightbox-image-frame')) closeLightbox();
    }

    function lightboxNav(delta) {
        const total = state.artwork?.count || 1;
        let next = state.lightboxIndex + delta;
        if (next < 0) next = total - 1;
        if (next >= total) next = 0;
        openLightbox(next);
    }

    document.addEventListener('keydown', e => {
        if (!document.getElementById('lightbox').classList.contains('open')) return;
        if (e.key === 'Escape') closeLightbox();
        else if (e.key === 'ArrowLeft') lightboxNav(-1);
        else if (e.key === 'ArrowRight') lightboxNav(1);
    });


// ---- PixivArtwork facade ----
window.PixivArtwork.viewer = window.PixivArtwork.viewer || {};
window.PixivArtwork.viewer = Object.assign(window.PixivArtwork.viewer, { loadArtwork, renderViewer, expandAll, collapseAll, renderDetail, renderAuthor, openLightbox, closeLightbox, handleLightboxClick, lightboxNav });
