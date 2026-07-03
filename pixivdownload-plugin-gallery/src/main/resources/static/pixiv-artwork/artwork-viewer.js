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
        const artwork = state.artwork;
        const count = artwork.count || 1;
        document.getElementById('viewerLoading').style.display = 'none';
        document.getElementById('viewer').style.display = '';

        document.getElementById('totalPageCount').textContent = count;
        document.getElementById('metaInfo').textContent = wt('meta.pages', '{count} pages · {ext}', {
            count,
            ext: (artwork.extensions || '').toUpperCase()
        });

        state.lightboxImages = new Array(count).fill(null);

        const mainImage = document.getElementById('mainImage');
        mainImage.classList.add('loading');
        mainImage.onclick = () => openLightbox(0);
        loadImageToElement(`/api/downloaded/image/${artwork.artworkId}/0`, mainImage).then(src => {
            if (src) state.lightboxImages[0] = src;
        });

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

    async function expandAll(count) {
        const btn = document.getElementById('expandBtn');
        const collapseBtn = document.getElementById('collapseBtn');
        btn.disabled = true;
        btn.textContent = wt('button.expand-loading', 'Loading...');
        const more = document.getElementById('morePages');
        more.classList.add('open');
        
        // 并行加载所有图片
        const promises = [];
        for (let p = 1; p < count; p++) {
            const box = document.createElement('div');
            box.className = 'viewer-image';
            box.classList.add('loading');
            more.appendChild(box);
            const idx = p;
            box.addEventListener('click', () => openLightbox(idx));
            
            const promise = loadImageToElement(`/api/downloaded/image/${state.artworkId}/${p}`, box).then(src => {
                if (src) state.lightboxImages[p] = src;
            });
            promises.push(promise);
        }
        
        await Promise.all(promises);
        btn.style.display = 'none';
        collapseBtn.style.display = '';
        expanded = true;
    }

    function collapseAll() {
        const more = document.getElementById('morePages');
        more.classList.remove('open');
        more.innerHTML = '';
        const btn = document.getElementById('expandBtn');
        const collapseBtn = document.getElementById('collapseBtn');
        const count = state.artwork.count || 1;
        btn.style.display = '';
        btn.disabled = false;
        syncExpandButtonText();
        collapseBtn.style.display = 'none';
        for (let i = 1; i < state.lightboxImages.length; i++) {
            state.lightboxImages[i] = null;
        }
        expanded = false;
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
        if (!state.lightboxImages[index]) return;
        state.lightboxIndex = index;
        document.getElementById('lightboxImage').src = state.lightboxImages[index];
        document.getElementById('lightboxInfo').textContent = `${index + 1} / ${state.lightboxImages.length}`;
        document.getElementById('lightbox').classList.add('open');
        
        // 预加载所有图片
        if (!expanded) {
            const count = state.artwork.count || 1;
            expandAll(count);
        }
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
            if (!expanded) {
                const count = state.artwork.count || 1;
                expandAll(count).then(() => openLightbox(next));
                return;
            }
            toast(wt('status.image-not-ready', 'Image is still loading. Please wait.'));
            return;
        }
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
