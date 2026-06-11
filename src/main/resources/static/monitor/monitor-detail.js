'use strict';
    // ===================== 作品详情 =====================
    async function viewArtworkDetails(artworkId) {
        try {
            const res = await fetch(`/api/downloaded/${artworkId}`);
            if (!res.ok) throw new Error();
            const artwork = await res.json();

            const dt     = formatDateTime(artwork.time);
            const moveDt = artwork.moveTime ? formatDateTime(artwork.moveTime).full : null;
            const authorLabel = escapeHtml(getAuthorLabel(artwork));
            const descriptionHtml = (artwork.description && String(artwork.description).trim())
                ? `<div class="detail-section mt-3">
                        <h6><i class="fas fa-align-left me-2"></i>${escapeHtml(t('detail.description', 'Description'))}</h6>
                        <div class="artwork-description" style="font-size:.85rem;line-height:1.55;white-space:pre-wrap;word-break:break-word;max-height:260px;overflow-y:auto;padding:.6rem .8rem;border:1px solid rgba(100,116,139,.25);border-radius:6px;background:rgba(15,23,42,.04);">${artwork.description}</div>
                    </div>`
                : '';

            const tagsList = Array.isArray(artwork.tags)
                ? artwork.tags.filter(t => t && t.name)
                : [];
            const tagsHtml = tagsList.length
                ? `<div class="detail-section mt-3">
                        <h6><i class="fas fa-tags me-2"></i>${escapeHtml(t('detail.tags', 'Tags'))} <span style="color:var(--text-muted);font-weight:normal;">(${tagsList.length})</span></h6>
                        <div style="display:flex;flex-wrap:wrap;gap:.35rem;">
                            ${tagsList.map(t => {
                                const translated = t.translatedName ? ` title="${escapeHtml(t.translatedName)}"` : '';
                                return `<span${translated} style="font-size:.75rem;padding:.2rem .55rem;border:1px solid rgba(100,116,139,.35);border-radius:999px;background:rgba(100,116,139,.08);color:var(--cyan);">#${escapeHtml(t.name)}</span>`;
                            }).join('')}
                        </div>
                    </div>`
                : '';

            document.getElementById('artworkModalBody').innerHTML = `
            <div class="row g-3">
                <div class="col-md-6 detail-section">
                    <h6><i class="fas fa-info-circle me-2"></i>${escapeHtml(t('detail.artwork-info', 'Artwork Info'))}</h6>
                    <ul class="detail-list">
                        <li><span class="detail-key">${escapeHtml(t('detail.artwork-id', 'Artwork ID'))}</span><span class="detail-val" style="color:var(--cyan)">${artwork.artworkId}</span></li>
                        <li><span class="detail-key">${escapeHtml(t('detail.title', 'Title'))}</span><span class="detail-val">${escapeHtml(artwork.title || '—')}</span></li>
                        <li><span class="detail-key">${escapeHtml(t('detail.author', 'Author'))}</span><span class="detail-val">${authorLabel}</span></li>
                        <li><span class="detail-key">${escapeHtml(t('detail.images', 'Images'))}</span><span class="detail-val"><span class="count-badge">${artwork.count}</span></span></li>
                        <li><span class="detail-key">${escapeHtml(t('detail.downloaded', 'Downloaded'))}</span><span class="detail-val">${dt.full}</span></li>
                        <li><span class="detail-key">${escapeHtml(t('detail.status', 'Status'))}</span><span class="detail-val"><span class="status-badge ${artwork.moved ? 'status-moved' : 'status-completed'}">${artwork.moved ? escapeHtml(t('status.moved', 'MOVED')) : escapeHtml(t('status.done', 'DONE'))}</span></span></li>
                    </ul>
                </div>
                <div class="col-md-6 detail-section">
                    <h6><i class="fas fa-folder me-2"></i>${escapeHtml(t('detail.storage', 'Storage'))}</h6>
                    <ul class="detail-list">
                        <li style="flex-direction:column;gap:.3rem;">
                            <span class="detail-key">${escapeHtml(t('detail.original-path', 'Original Path'))}</span>
                            <code class="text-break" style="font-size:.75rem;">${escapeHtml(artwork.folder)}</code>
                        </li>
                        ${artwork.moved ? `
                        <li style="flex-direction:column;gap:.3rem;">
                            <span class="detail-key">${escapeHtml(t('detail.moved-to', 'Moved To'))}</span>
                            <code class="text-break" style="font-size:.75rem;">${escapeHtml(artwork.moveFolder)}</code>
                        </li>
                        <li><span class="detail-key">${escapeHtml(t('detail.moved-at', 'Moved At'))}</span><span class="detail-val">${moveDt}</span></li>
                        ` : ''}
                    </ul>
                </div>
            </div>
            ${descriptionHtml}
            ${tagsHtml}
            <div class="mt-3 text-center">
                <button class="btn-term" onclick="viewThumbnails(${artwork.artworkId})">
                    <i class="fas fa-images me-1"></i>${escapeHtml(t('button.view-images', 'View Images'))}
                </button>
            </div>`;

            artworkModal.show();
        } catch (e) {
            console.error(t('log.artwork-detail-failed', '加载作品详情失败'), e);
            alert(t('alert.load-artwork-failed', 'Failed to load artwork details'));
        }
    }

    // ===================== 缩略图 =====================
    async function viewThumbnails(artworkId) {
        try {
            const res = await fetch(`/api/downloaded/${artworkId}`);
            if (!res.ok) throw new Error();
            const artwork = await res.json();

            thumbArtworkId   = artworkId;
            thumbCount       = artwork.count;
            thumbTotalPages  = Math.ceil(thumbCount / THUMB_PAGE_SIZE) || 1;
            thumbCurrentPage = 1;

            document.getElementById('artworkModalBody').innerHTML = `
            <div class="thumbnail-header">
                <div>
                    <div style="font-size:.7rem;letter-spacing:.15em;text-transform:uppercase;color:var(--cyan);">${escapeHtml(t('thumbnail.preview', 'Image Preview'))}</div>
                    <div class="thumbnail-count">${escapeHtml(t('thumbnail.count', '{count} images', {count: artwork.count}))}</div>
                </div>
                <button class="btn-term" onclick="viewArtworkDetails(${artworkId})">
                    <i class="fas fa-arrow-left me-1"></i>${escapeHtml(t('button.back', 'Back'))}
                </button>
            </div>
            <div id="thumbnailGrid" class="thumbnail-grid"></div>
            <div id="thumbPagination" style="margin-top:10px;display:flex;justify-content:center;"></div>`;

            artworkModal.show();
            renderThumbnailPage();
        } catch (e) {
            console.error(t('log.thumbnail-failed', '加载缩略图失败'), e);
            alert(t('alert.load-thumbnails-failed', 'Failed to load thumbnails'));
        }
    }

    function renderThumbnailPage() {
        const artworkId = thumbArtworkId;
        const start = (thumbCurrentPage - 1) * THUMB_PAGE_SIZE;
        const end   = Math.min(start + THUMB_PAGE_SIZE, thumbCount);
        const grid  = document.getElementById('thumbnailGrid');
        if (!grid) return;

        grid.innerHTML = Array.from({ length: end - start }, (_, i) => {
            const idx = start + i;
            return `
            <div class="thumbnail-item" id="thumb-${artworkId}-${idx}"
                 onclick="viewFullImage(${artworkId}, ${idx})">
                <div class="thumbnail-loading"><div class="spinner"></div><span style="font-size:.6rem">${escapeHtml(t('status.loading-lower', 'loading'))}</span></div>
                <div class="thumbnail-index">${idx + 1}</div>
            </div>`;
        }).join('');

        renderThumbPagination();

        for (let i = start; i < end; i++) {
            loadThumbnail(artworkId, i);
        }
    }

    function renderThumbPagination() {
        const container = document.getElementById('thumbPagination');
        if (!container) return;
        if (thumbTotalPages <= 1) { container.innerHTML = ''; return; }

        const maxVisible = 5;
        let start = Math.max(1, thumbCurrentPage - Math.floor(maxVisible / 2));
        let end   = Math.min(thumbTotalPages, start + maxVisible - 1);
        if (end - start + 1 < maxVisible) start = Math.max(1, end - maxVisible + 1);

        let html = `<ul class="pagination pagination-sm mb-0" style="gap:3px;">`;
        html += `<li class="page-item ${thumbCurrentPage === 1 ? 'disabled' : ''}">
            <a class="page-link" href="#" onclick="changeThumbPage(${thumbCurrentPage - 1}); return false;"><i class="fas fa-chevron-left"></i></a>
        </li>`;
        for (let i = start; i <= end; i++) {
            html += `<li class="page-item ${i === thumbCurrentPage ? 'active' : ''}">
                <a class="page-link" href="#" onclick="changeThumbPage(${i}); return false;">${i}</a>
            </li>`;
        }
        html += `<li class="page-item ${thumbCurrentPage === thumbTotalPages ? 'disabled' : ''}">
            <a class="page-link" href="#" onclick="changeThumbPage(${thumbCurrentPage + 1}); return false;"><i class="fas fa-chevron-right"></i></a>
        </li></ul>`;

        container.innerHTML = html;
    }

    function changeThumbPage(page) {
        if (page < 1 || page > thumbTotalPages) return;
        thumbCurrentPage = page;
        renderThumbnailPage();
    }

    async function loadThumbnail(artworkId, page) {
        const item = document.getElementById(`thumb-${artworkId}-${page}`);
        if (!item) return;
        try {
            const res = await fetch(`/api/downloaded/thumbnail/${artworkId}/${page}`);
            if (!res.ok) throw new Error();
            const data = await res.json();
            if (data.success && data.image) {
                item.innerHTML = `
                <img src="data:image/${data.extension};base64,${data.image}" class="thumbnail-img" alt="thumb">
                <div class="thumbnail-index">${page + 1}</div>`;
            } else throw new Error(data.message);
        } catch (e) {
            item.innerHTML = `<div class="thumbnail-error"><i class="fas fa-exclamation-circle"></i><span>ERR</span></div>`;
            item.style.cursor = 'default';
            item.onclick = null;
        }
    }

    // ===================== 原图预览 =====================
    async function viewFullImage(artworkId, page) {
        fullImgArtworkId = artworkId;
        fullImgPage      = page;
        fullImgTotal     = (thumbArtworkId === artworkId) ? thumbCount : 0;
        const ok = await loadFullImage();
        if (ok) imageModal.show();
    }

    function loadFullImage() {
        if (fullImgLoading) return Promise.resolve(false);
        fullImgLoading = true;
        const img = document.getElementById('modalImage');
        const spinner = document.getElementById('imgSpinner');
        img.src = '';
        img.style.opacity = '0';
        spinner.classList.add('active');
        return new Promise((resolve) => {
            img.onload = () => {
                spinner.classList.remove('active');
                img.style.opacity = '1';
                updateImgNav();
                fullImgLoading = false;
                resolve(true);
            };
            img.onerror = () => {
                spinner.classList.remove('active');
                img.style.opacity = '1';
                console.error(t('log.full-image-failed', '加载完整图片失败'));
                alert(t('alert.load-image-failed', 'Failed to load image'));
                fullImgLoading = false;
                resolve(false);
            };
            img.src = `/api/downloaded/rawfile/${fullImgArtworkId}/${fullImgPage}`;
        });
    }

    async function navigateFullImg(delta) {
        const newPage = fullImgPage + delta;
        if (newPage < 0) return;
        if (fullImgTotal > 0 && newPage >= fullImgTotal) return;
        fullImgPage = newPage;
        await loadFullImage();
    }

    function updateImgNav() {
        const counter = document.getElementById('imgCounter');
        const prevBtn = document.getElementById('imgPrevBtn');
        const nextBtn = document.getElementById('imgNextBtn');
        if (counter) {
            counter.textContent = fullImgTotal > 0
                ? `${fullImgPage + 1} / ${fullImgTotal}`
                : `# ${fullImgPage + 1}`;
        }
        if (prevBtn) prevBtn.disabled = fullImgPage === 0;
        if (nextBtn) nextBtn.disabled = fullImgTotal > 0 && fullImgPage === fullImgTotal - 1;
    }
