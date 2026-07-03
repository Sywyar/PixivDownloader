'use strict';

    function renderLoading() {
        document.getElementById('seriesTitle').textContent = t('status.loading', 'Loading...');
        document.getElementById('seriesStatus').textContent = t('status.loading', 'Loading...');
        document.getElementById('chapterGrid').innerHTML = '';
        document.getElementById('pagination').innerHTML = '';
        document.getElementById('metaStrip').innerHTML = '';
        const tagsEl = document.getElementById('seriesTags');
        if (tagsEl) {
            tagsEl.innerHTML = '';
            tagsEl.style.display = 'none';
        }
    }

    function renderSeriesHeader() {
        const titleEl = document.getElementById('seriesTitle');
        const authorEl = document.getElementById('seriesAuthor');
        const authorNameEl = document.getElementById('seriesAuthorName');
        const galleryBtn = document.getElementById('openGalleryFilterBtn');
        const backBtn = document.getElementById('backToArtworkBtn');
        const detail = state.detail;

        if (!detail) {
            titleEl.textContent = state.seriesId
                ? modeText('series.default', 'Series #{id}', 'Series #{id}', {id: state.seriesId})
                : '';
            authorEl.style.display = 'none';
            galleryBtn.href = (isNovelMode() ? '/pixiv-novel-gallery.html' : '/pixiv-gallery.html') + '?view=all';
            renderCover();
            renderDescription();
            renderSeriesTags();
            return;
        }

        const originalTitle = detail.title || modeText('series.default', 'Series #{id}', 'Series #{id}', {id: detail.seriesId});
        const title = isNovelMode() ? seriesDisplayTitle() : originalTitle;
        titleEl.textContent = title;
        document.title = modeText('page.title-with-name', '{title} - Manga Series', '{title} - Novel Series', {title});
        galleryBtn.href = isNovelMode()
            ? buildNovelGalleryFilterHref({seriesId: detail.seriesId, seriesTitle: title})
            : buildGalleryFilterHref({seriesId: detail.seriesId, seriesTitle: title});

        if (state.currentId) {
            backBtn.href = isNovelMode()
                ? `/pixiv-novel.html?id=${state.currentId}`
                : `/pixiv-artwork.html?id=${state.currentId}`;
            backBtn.style.display = 'inline-flex';
        } else {
            backBtn.style.display = 'none';
        }

        if (detail.authorId || detail.authorName) {
            const name = detail.authorName || t('author.default', 'Author {id}', {id: detail.authorId});
            authorNameEl.textContent = detail.authorId
                ? t('author.with-id', '{name} · #{id}', {name, id: detail.authorId})
                : name;
            if (detail.authorId) {
                authorEl.href = isNovelMode()
                    ? buildPixivAuthorHref(detail.authorId)
                    : buildPixivSeriesHref(detail.authorId, detail.seriesId);
            } else {
                authorEl.removeAttribute('href');
            }
            authorEl.style.display = 'inline-flex';
        } else {
            authorEl.style.display = 'none';
        }

        renderCover();
        renderDescription();
        renderSeriesTags();
    }

    function renderCover() {
        const wrap = document.getElementById('seriesCover');
        const img = document.getElementById('seriesCoverImg');
        if (!wrap || !img) return;
        const detail = state.detail;
        if (!detail || !detail.coverExt) {
            wrap.style.display = 'none';
            return;
        }
        const url = isNovelMode()
            ? `/api/gallery/novel/series/${detail.seriesId}/cover`
            : `/api/series/${detail.seriesId}/cover`;
        img.alt = detail.title || '';
        img.src = url + '?v=' + encodeURIComponent(detail.coverExt);
        wrap.style.display = 'block';
    }

    function renderDescription() {
        const el = document.getElementById('seriesDescription');
        if (!el) return;
        // 小说系列下若选择了内容语言，优先显示译后系列简介（缺失回退原文）；其它情况走原始 detail.description。
        const text = isNovelMode()
            ? seriesDisplayDescription()
            : (state.detail ? state.detail.description : null);
        if (!text || !String(text).trim()) {
            el.style.display = 'none';
            el.innerHTML = '';
            return;
        }
        // Pixiv 简介经过后端 PixivDescriptionHtml.normalizeLinks 仅保留 <br>/<a>，可直接 innerHTML。
        el.innerHTML = text;
        el.querySelectorAll('a').forEach(a => {
            a.target = '_blank';
            a.rel = 'noopener noreferrer';
        });
        el.style.display = 'block';
    }

    function renderSeriesTags() {
        const el = document.getElementById('seriesTags');
        if (!el) return;
        const detail = state.detail;
        const tags = isNovelMode() && detail && Array.isArray(detail.tags)
            ? detail.tags.filter(tag => {
                const tagId = Number(tag && tag.tagId);
                return Number.isFinite(tagId) && tagId > 0;
            })
            : [];
        if (!tags.length) {
            el.style.display = 'none';
            el.innerHTML = '';
            return;
        }
        el.innerHTML = tags.map(tag => {
            const label = tag.name || pageText('gallery:tag.default', 'Tag #{id}', {id: tag.tagId});
            const translated = tag.translatedName
                ? `<span class="series-tag-translated">${escapeHtml(tag.translatedName)}</span>`
                : '';
            return `<a class="series-tag" href="${escapeHtml(buildNovelGalleryTagFilterHref(tag))}">${escapeHtml(label)}${translated}</a>`;
        }).join('');
        el.style.display = 'flex';
    }

    function renderMeta() {
        const strip = document.getElementById('metaStrip');
        const detail = state.detail;
        if (!detail) {
            strip.innerHTML = '';
            return;
        }
        const chips = [
            metaChip('id', t('meta.series-id', 'Series ID: {id}', {id: detail.seriesId}), 'M4 19.5A2.5 2.5 0 0 1 6.5 17H20 M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z'),
            metaChip('count', modeText(
                'meta.local-count',
                '{count} downloaded chapters',
                '{count} downloaded novels',
                {count: isNovelMode() ? (detail.novelCount || 0) : (detail.artworkCount || 0)}
            ), 'M3 6h18 M3 12h18 M3 18h18'),
        ];
        if (state.totalElements) {
            chips.push(metaChip('shown', modeText(
                'meta.filtered-count',
                '{count} matching chapters',
                '{count} matching novels',
                {count: state.totalElements}
            ), 'M22 3H2l8 9.46V19l4 2v-8.54L22 3z'));
        }
        if (detail.updatedTime) {
            chips.push(metaChip('updated', t('meta.updated', 'Updated {time}', {time: formatTime(detail.updatedTime)}), 'M12 8v4l3 3 M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0z'));
        }
        strip.innerHTML = chips.join('');
    }

    function metaChip(type, text, path) {
        return `
            <span class="meta-chip" data-type="${escapeHtml(type)}">
                <svg viewBox="0 0 24 24"><path d="${escapeHtml(path)}"/></svg>
                ${escapeHtml(text)}
            </span>
        `;
    }

    function renderStatus() {
        const status = document.getElementById('seriesStatus');
        if (!state.seriesId) return;
        if (!state.totalElements) {
            status.textContent = state.search
                ? modeText('status.no-matching-artworks', 'No matching chapters', 'No matching novels')
                : modeText('status.no-artworks', 'No downloaded chapters in this series', 'No downloaded novels in this series');
            return;
        }
        const from = state.page * state.size + 1;
        const to = Math.min((state.page + 1) * state.size, state.totalElements);
        status.textContent = modeText('status.range', '{total} chapters, {from}-{to} shown', '{total} novels, {from}-{to} shown', {
            total: state.totalElements,
            from,
            to,
        });
    }

    function renderGrid(items) {
        const grid = document.getElementById('chapterGrid');
        if (!state.seriesId) return;
        if (isNovelMode()) {
            renderNovelGrid(items, grid);
            return;
        }
        if (!items.length) {
            grid.innerHTML = '<div class="empty-state" style="grid-column:1/-1">' +
                escapeHtml(state.search
                    ? t('status.no-matching-artworks', 'No matching chapters')
                    : t('status.no-artworks', 'No downloaded chapters in this series')) +
                '</div>';
            return;
        }
        grid.innerHTML = items.map(item => {
            const xRestrict = item.xRestrict;
            const isR18 = xRestrict === 1;
            const isR18G = xRestrict === 2;
            const isAi = item.isAi === true;
            const pages = item.count || 1;
            const isCurrent = state.currentId && String(item.artworkId) === String(state.currentId);
            return `
                <a class="chapter-card ${isCurrent ? 'current' : ''}" href="/pixiv-artwork.html?id=${item.artworkId}" data-id="${item.artworkId}">
                    <div class="chapter-thumb">
                        <img data-src="/api/downloaded/thumbnail/${item.artworkId}/0" alt="${escapeHtml(item.title || '')}" loading="lazy">
                        <div class="thumb-veil"></div>
                        <div class="thumb-badges">
                            <div class="thumb-badge-group">
                                ${item.seriesOrder ? `<span class="thumb-badge current">#${escapeHtml(item.seriesOrder)}</span>` : ''}
                                ${isCurrent ? `<span class="thumb-badge current">${escapeHtml(t('chapter.current', 'Current'))}</span>` : ''}
                                ${isR18G ? '<span class="thumb-badge r18g">R-18G</span>' : isR18 ? '<span class="thumb-badge r18">R-18</span>' : ''}
                                ${isAi ? '<span class="thumb-badge ai">AI</span>' : ''}
                            </div>
                            ${pages > 1 ? `<span class="thumb-badge pages">${pages}P</span>` : ''}
                        </div>
                    </div>
                    <div class="chapter-meta">
                        <div class="chapter-title-row">
                            ${item.seriesOrder ? `<span class="chapter-order">#${escapeHtml(item.seriesOrder)}</span>` : ''}
                            <div class="chapter-title">${escapeHtml(item.title || t('status.untitled', 'Untitled'))}</div>
                        </div>
                        <div class="chapter-sub">${escapeHtml(chapterSubText(item))}</div>
                    </div>
                </a>
            `;
        }).join('');
        grid.querySelectorAll('img[data-src]').forEach(img => loadThumbnail(img));
        if (state.currentId) {
            requestAnimationFrame(() => {
                const current = grid.querySelector('.chapter-card.current');
                if (current) {
                    current.scrollIntoView({block: 'nearest'});
                }
            });
        }
    }

    function renderNovelGrid(items, grid) {
        if (!items.length) {
            grid.innerHTML = '<div class="empty-state" style="grid-column:1/-1">' +
                escapeHtml(state.search
                    ? t('status.no-matching-artworks-novel', 'No matching novels')
                    : t('status.no-artworks-novel', 'No downloaded novels in this series')) +
                '</div>';
            return;
        }
        grid.innerHTML = items.map(item => {
            const xRestrict = item.xRestrict;
            const isR18 = xRestrict === 1;
            const isR18G = xRestrict === 2;
            const isAi = item.isAi === true;
            const isOriginal = item.isOriginal === true;
            const isCurrent = state.currentId && String(item.novelId) === String(state.currentId);
            const title = chapterDisplayTitle(item);
            const cover = item.coverExt
                ? `<img src="/api/gallery/novel/${item.novelId}/cover" alt="${escapeHtml(title)}" loading="lazy" onerror="this.replaceWith(Object.assign(document.createElement('div'),{className:'chapter-cover-placeholder',textContent:this.alt}))">`
                : `<div class="chapter-cover-placeholder">${escapeHtml(title)}</div>`;
            return `
                <a class="chapter-card novel ${isCurrent ? 'current' : ''}" href="${escapeHtml(novelChapterHref(item.novelId))}" data-id="${item.novelId}">
                    <div class="chapter-thumb">
                        ${cover}
                        <div class="thumb-veil"></div>
                        <div class="thumb-badges">
                            <div class="thumb-badge-group">
                                ${item.seriesOrder ? `<span class="thumb-badge current">#${escapeHtml(item.seriesOrder)}</span>` : ''}
                                ${isCurrent ? `<span class="thumb-badge current">${escapeHtml(t('chapter.current', 'Current'))}</span>` : ''}
                                ${isR18G ? '<span class="thumb-badge r18g">R-18G</span>' : isR18 ? '<span class="thumb-badge r18">R-18</span>' : ''}
                                ${isAi ? '<span class="thumb-badge ai">AI</span>' : ''}
                                ${isOriginal ? `<span class="thumb-badge pages">${escapeHtml(t('chapter.original', 'Original'))}</span>` : ''}
                            </div>
                        </div>
                    </div>
                    <div class="chapter-meta">
                        <div class="chapter-title-row">
                            ${item.seriesOrder ? `<span class="chapter-order">#${escapeHtml(item.seriesOrder)}</span>` : ''}
                            <div class="chapter-title">${escapeHtml(title)}</div>
                        </div>
                        <div class="chapter-sub">${escapeHtml(chapterSubText(item))}</div>
                    </div>
                </a>
            `;
        }).join('');
        if (state.currentId) {
            requestAnimationFrame(() => {
                const current = grid.querySelector('.chapter-card.current');
                if (current) {
                    current.scrollIntoView({block: 'nearest'});
                }
            });
        }
    }

    function chapterSubText(item) {
        const parts = [];
        if (isNovelMode()) {
            if (item.novelId) parts.push(t('chapter.id', 'ID: {id}', {id: item.novelId}));
            if (item.wordCount && item.wordCount > 0) parts.push(t('chapter.words', '{count} words', {count: item.wordCount}));
            else if (item.textLength && item.textLength > 0) parts.push(t('chapter.text-length', '{count} characters', {count: item.textLength}));
            const readingText = formatReadingTime(item.readingTimeSeconds);
            if (readingText) parts.push(readingText);
            if (item.xLanguage) parts.push(item.xLanguage);
            return parts.join(' · ');
        }
        if (item.artworkId) parts.push(t('chapter.id', 'ID: {id}', {id: item.artworkId}));
        if (item.count) parts.push(t('chapter.pages', '{count} pages', {count: item.count}));
        return parts.join(' · ');
    }

    function formatReadingDuration(seconds) {
        const total = Math.floor(Number(seconds || 0));
        if (!Number.isFinite(total) || total <= 0) return '';
        const hour = Math.floor(total / 3600);
        const minute = Math.floor((total % 3600) / 60);
        const second = total % 60;
        const parts = [];
        if (hour > 0) parts.push(t('duration.hour', '{count} h', {count: hour}));
        if (minute > 0) parts.push(t('duration.minute', '{count} min', {count: minute}));
        if (second > 0 && hour === 0) parts.push(t('duration.second', '{count} sec', {count: second}));
        if (!parts.length) parts.push(t('duration.second', '{count} sec', {count: total}));
        return parts.join(' ');
    }

    function formatReadingTime(seconds) {
        const text = formatReadingDuration(seconds);
        return text ? t('chapter.reading-time', 'Reading: {time}', {time: text}) : '';
    }

    async function loadThumbnail(img) {
        const url = img.dataset.src;
        img.removeAttribute('data-src');
        const cached = ImageCache.get(url);
        if (cached) {
            img.src = cached;
            return;
        }
        try {
            const resp = await api(url);
            if (resp && resp.success && resp.image) {
                const ext = (resp.extension || 'jpg').toLowerCase();
                const src = `data:image/${ext === 'jpg' ? 'jpeg' : ext};base64,${resp.image}`;
                img.src = src;
                ImageCache.put(url, src);
            }
        } catch (_) {
        }
    }

    function renderPagination() {
        const pag = document.getElementById('pagination');
        if (state.totalPages <= 1) {
            pag.innerHTML = '';
            return;
        }
        const pages = buildPageWindow(state.page, state.totalPages);
        const parts = [];
        parts.push(`<button class="page-btn" data-page="${state.page - 1}" ${state.page === 0 ? 'disabled' : ''}>${escapeHtml(t('pagination.prev', 'Previous'))}</button>`);
        for (const p of pages) {
            if (p === '...') parts.push('<span class="page-ellipsis">...</span>');
            else parts.push(`<button class="page-btn ${p === state.page ? 'active' : ''}" data-page="${p}">${p + 1}</button>`);
        }
        parts.push(`<button class="page-btn" data-page="${state.page + 1}" ${state.page >= state.totalPages - 1 ? 'disabled' : ''}>${escapeHtml(t('pagination.next', 'Next'))}</button>`);
        pag.innerHTML = parts.join('');
        pag.querySelectorAll('.page-btn').forEach(btn => {
            btn.addEventListener('click', () => {
                if (btn.disabled) return;
                const p = Number(btn.dataset.page);
                if (p < 0 || p >= state.totalPages) return;
                state.page = p;
                loadArtworks();
                document.querySelector('.series-wrapper').scrollTop = 0;
                window.scrollTo({top: 0, behavior: 'smooth'});
            });
        });
    }

    function buildPageWindow(current, total) {
        const windowSet = new Set([0, total - 1, current, current - 1, current + 1]);
        const pages = [...windowSet].filter(n => n >= 0 && n < total).sort((a, b) => a - b);
        const out = [];
        for (let i = 0; i < pages.length; i++) {
            if (i > 0 && pages[i] - pages[i - 1] > 1) out.push('...');
            out.push(pages[i]);
        }
        return out;
    }

    function buildGalleryFilterHref({seriesId, seriesTitle, authorId, authorName} = {}) {
        const params = new URLSearchParams();
        params.set('view', 'all');
        if (seriesId != null) params.set('filterSeriesId', String(seriesId));
        if (seriesTitle) params.set('filterSeriesTitle', seriesTitle);
        if (authorId != null) params.set('filterAuthorId', String(authorId));
        if (authorName) params.set('filterAuthorName', authorName);
        return '/pixiv-gallery.html' + (params.toString() ? '?' + params.toString() : '');
    }

    function buildNovelGalleryFilterHref({seriesId, seriesTitle} = {}) {
        const params = new URLSearchParams();
        params.set('view', 'all');
        if (seriesId != null) params.set('seriesId', String(seriesId));
        if (seriesTitle) params.set('seriesTitle', seriesTitle);
        return '/pixiv-novel-gallery.html' + (params.toString() ? '?' + params.toString() : '');
    }

    function buildNovelGalleryTagFilterHref(tag) {
        const params = new URLSearchParams();
        params.set('view', 'all');
        params.set('tagIds', String(tag.tagId));
        if (tag.name) params.set('tagName', tag.name);
        if (tag.translatedName) params.set('tagTranslatedName', tag.translatedName);
        return '/pixiv-novel-gallery.html?' + params.toString();
    }

    function buildPixivSeriesHref(authorId, seriesId) {
        return `https://www.pixiv.net/user/${authorId}/series/${seriesId}`;
    }

    function buildPixivAuthorHref(authorId) {
        return `https://www.pixiv.net/users/${authorId}`;
    }
