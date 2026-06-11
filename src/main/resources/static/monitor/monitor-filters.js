'use strict';
    // ===================== 格式筛选弹框 =====================
    function toggleFormatFilter(evt) {
        evt.stopPropagation();
        const popup = document.getElementById('formatFilterPopup');
        if (popup.classList.contains('show') && popup.dataset.type === 'format') {
            popup.classList.remove('show');
            return;
        }
        popup.dataset.type = 'format';

        // 收集所有可用格式
        const formats = new Set();
        if (allArtworksCache) {
            allArtworksCache.forEach(a => {
                if (a.extensions) {
                    a.extensions.split(',').forEach(f => formats.add(f.trim().toLowerCase()));
                }
            });
        }
        const sortedFormats = [...formats].sort();

        popup.innerHTML = `
            <div class="fmt-filter-header">${escapeHtml(t('filter.format.title', 'FORMAT FILTER'))}</div>
            <div class="fmt-filter-options">
                ${sortedFormats.length ? sortedFormats.map(f => `
                    <label class="fmt-filter-option">
                        <input type="checkbox" value="${f}" ${formatFilter.has(f) ? 'checked' : ''}
                               onchange="onFormatFilterChange()">
                        <span class="fmt-badge fmt-${f}">${f.toUpperCase()}</span>
                    </label>
                `).join('') : '<span style="font-size:.7rem;color:var(--text-dim)">' + escapeHtml(t('status.no-data', 'No data')) + '</span>'}
            </div>
            <div class="fmt-filter-actions">
                <button class="btn-term" style="font-size:.6rem;padding:2px 8px;width:100%;" onclick="clearFormatFilter()">${escapeHtml(t('filter.clear-all', 'CLEAR ALL'))}</button>
            </div>`;

        positionFilterPopup(document.getElementById('th-extensions'), popup);
        popup.classList.add('show');
    }

    function onFormatFilterChange() {
        const checkboxes = document.querySelectorAll('#formatFilterPopup input[type=checkbox]');
        formatFilter = new Set([...checkboxes].filter(cb => cb.checked).map(cb => cb.value));
        currentPage = 1;
        updateFormatFilterIcon();
        renderFromCache();
    }

    function clearFormatFilter() {
        formatFilter = new Set();
        document.querySelectorAll('#formatFilterPopup input[type=checkbox]').forEach(cb => cb.checked = false);
        currentPage = 1;
        updateFormatFilterIcon();
        renderFromCache();
    }

    function updateFormatFilterIcon() {
        const icon = document.getElementById('sort-extensions');
        const th   = document.getElementById('th-extensions');
        if (!icon) return;
        const active = formatFilter.size > 0;
        icon.innerHTML = `<i class="fas fa-filter" style="font-size:.55rem;opacity:${active ? 1 : .45};color:${active ? 'var(--cyan)' : 'inherit'}"></i>`;
        if (th) th.classList.toggle('th-filter-active', active);
    }

    function renderAuthorFilterOptions() {
        const container = document.getElementById('authorFilterOptions');
        if (!container) return;
        const q = authorFilterQuery;
        const authors = getAuthorOptions().filter(author => {
            if (!q) return true;
            return author.name.toLowerCase().includes(q) || String(author.id).includes(q);
        });
        container.innerHTML = authors.length
            ? authors.map(author => `
                <label class="fmt-filter-option">
                    <input type="checkbox" value="${author.id}" ${authorFilter.has(author.id) ? 'checked' : ''} onchange="toggleAuthorFilterValue(this.value, this.checked)">
                    <span class="author-filter-row">
                        <span class="author-filter-name">${escapeHtml(author.name)}</span>
                        <span class="author-filter-id">#${author.id}</span>
                    </span>
                </label>
            `).join('')
            : '<div class="filter-empty">' + escapeHtml(t('filter.author.empty', 'NO AUTHOR MATCH')) + '</div>';
    }

    function renderAuthorFilterPopup() {
        const popup = document.getElementById('formatFilterPopup');
        popup.dataset.type = 'author';
        popup.innerHTML = `
            <div class="fmt-filter-header">${escapeHtml(t('filter.author.title', 'AUTHOR FILTER'))}</div>
            <input type="text" class="search-box filter-popup-search" placeholder="${escapeHtml(t('filter.author.search', 'Search author name / ID'))}" value="${escapeHtml(authorFilterQuery)}" oninput="onAuthorFilterSearch(this.value)">
            <div id="authorFilterOptions" class="fmt-filter-options"></div>
            <div class="fmt-filter-actions">
                <button class="btn-term" style="font-size:.6rem;padding:2px 8px;width:100%;" onclick="clearAuthorFilter()">${escapeHtml(t('filter.clear-all', 'CLEAR ALL'))}</button>
            </div>`;
        renderAuthorFilterOptions();
        positionFilterPopup(document.getElementById('th-authorId'), popup);
        popup.classList.add('show');
    }

    function renderAuthorFilterPopupIfOpen() {
        const popup = document.getElementById('formatFilterPopup');
        if (popup.classList.contains('show') && popup.dataset.type === 'author') {
            renderAuthorFilterPopup();
        }
    }

    function toggleAuthorFilter(evt) {
        evt.stopPropagation();
        const popup = document.getElementById('formatFilterPopup');
        if (popup.classList.contains('show') && popup.dataset.type === 'author') {
            popup.classList.remove('show');
            return;
        }
        renderAuthorFilterPopup();
    }

    function onAuthorFilterSearch(value) {
        authorFilterQuery = String(value || '').trim().toLowerCase();
        renderAuthorFilterOptions();
    }

    function toggleAuthorFilterValue(value, checked) {
        const authorId = normalizeAuthorId(value);
        if (authorId === null) return;
        if (checked) {
            authorFilter.add(authorId);
        } else {
            authorFilter.delete(authorId);
        }
        currentPage = 1;
        updateAuthorFilterIcon();
        renderFromCache();
    }

    function clearAuthorFilter() {
        authorFilter = new Set();
        authorFilterQuery = '';
        currentPage = 1;
        updateAuthorFilterIcon();
        renderFromCache();
        if (document.getElementById('formatFilterPopup').dataset.type === 'author') {
            renderAuthorFilterPopup();
        }
    }

    function toggleAuthorInFilter(authorId) {
        const normalized = normalizeAuthorId(authorId);
        if (normalized === null) return;
        if (authorFilter.size === 1 && authorFilter.has(normalized)) {
            authorFilter = new Set();
        } else {
            const next = new Set(authorFilter);
            if (next.has(normalized)) {
                next.delete(normalized);
            } else {
                next.add(normalized);
            }
            authorFilter = next;
        }
        currentPage = 1;
        updateAuthorFilterIcon();
        renderFromCache();
        const popup = document.getElementById('formatFilterPopup');
        if (popup.classList.contains('show') && popup.dataset.type === 'author') {
            renderAuthorFilterPopup();
        }
    }

    function updateAuthorFilterIcon() {
        const th = document.getElementById('th-authorId');
        const icon = document.getElementById('filter-authorId');
        const active = authorFilter.size > 0;
        if (th) th.classList.toggle('th-filter-active', active);
        if (icon) icon.classList.toggle('active', active);
    }

    // ===================== R18 筛选弹框 =====================
    function toggleR18Filter(evt) {
        evt.stopPropagation();
        const popup = document.getElementById('formatFilterPopup');
        if (popup.classList.contains('show') && popup.dataset.type === 'R18') {
            popup.classList.remove('show');
            return;
        }
        popup.dataset.type = 'R18';

        const options = [
            { label: t('filter.option.all', 'All'), value: 'all' },
            { label: '<span class="R18-badge R18-yes">R-18</span>', value: 'r18' },
            { label: '<span class="R18-badge R18-r18g">R-18G</span>', value: 'r18g' },
            { label: '<span class="R18-badge R18-no">SFW</span>', value: 'sfw' },
        ];

        popup.innerHTML = `
            <div class="fmt-filter-header">${escapeHtml(t('filter.r18.title', 'R18 FILTER'))}</div>
            <div class="fmt-filter-options">
                ${options.map(o => `
                    <label class="fmt-filter-option">
                        <input type="radio" name="R18opt" value="${o.value}"
                               ${(o.value === 'all' && R18Filter === null) || o.value === R18Filter ? 'checked' : ''}
                               onchange="onR18FilterChange(this.value)">
                        <span style="font-size:.75rem;">${o.label}</span>
                    </label>
                `).join('')}
            </div>`;

        positionFilterPopup(document.getElementById('th-R18'), popup);
        popup.classList.add('show');
    }

    function onR18FilterChange(value) {
        R18Filter = value === 'all' ? null : value;
        currentPage = 1;
        updateR18FilterIcon();
        renderFromCache();
    }

    function updateR18FilterIcon() {
        const icon = document.getElementById('sort-R18');
        const th   = document.getElementById('th-R18');
        if (!icon) return;
        const active = R18Filter !== null;
        icon.innerHTML = `<i class="fas fa-filter" style="font-size:.55rem;opacity:${active ? 1 : .45};color:${active ? 'var(--red)' : 'inherit'}"></i>`;
        if (th) th.classList.toggle('th-filter-active', active);
    }

    function toggleAiFilter(evt) {
        evt.stopPropagation();
        const popup = document.getElementById('formatFilterPopup');
        if (popup.classList.contains('show') && popup.dataset.type === 'ai') {
            popup.classList.remove('show');
            return;
        }
        popup.dataset.type = 'ai';

        const options = [
            { label: t('filter.option.all', 'All'), value: 'all' },
            { label: '<span class="ai-badge ai-yes">AI</span>', value: 'ai' },
            { label: '<span class="ai-badge ai-no">' + escapeHtml(t('value.human', 'Human')) + '</span>', value: 'human' },
        ];

        popup.innerHTML = `
            <div class="fmt-filter-header">${escapeHtml(t('filter.ai.title', 'AI FILTER'))}</div>
            <div class="fmt-filter-options">
                ${options.map(o => `
                    <label class="fmt-filter-option">
                        <input type="radio" name="aiOpt" value="${o.value}"
                               ${(o.value === 'all' && aiFilter === null) || (o.value === 'ai' && aiFilter === true) || (o.value === 'human' && aiFilter === false) ? 'checked' : ''}
                               onchange="onAiFilterChange(this.value)">
                        <span style="font-size:.75rem;">${o.label}</span>
                    </label>
                `).join('')}
            </div>`;

        positionFilterPopup(document.getElementById('th-isAi'), popup);
        popup.classList.add('show');
    }

    function onAiFilterChange(value) {
        aiFilter = value === 'all' ? null : value === 'ai';
        currentPage = 1;
        updateAiFilterIcon();
        renderFromCache();
    }

    function updateAiFilterIcon() {
        const icon = document.getElementById('sort-isAi');
        const th   = document.getElementById('th-isAi');
        if (!icon) return;
        const active = aiFilter !== null;
        icon.innerHTML = `<i class="fas fa-filter" style="font-size:.55rem;opacity:${active ? 1 : .45};color:${active ? '#8b5cf6' : 'inherit'}"></i>`;
        if (th) th.classList.toggle('th-filter-active', active);
    }

    // ===================== 页码网格弹框 =====================
    function togglePageGrid(evt) {
        evt.stopPropagation();
        const popup = document.getElementById('pageGridPopup');
        if (popup.classList.contains('show')) {
            popup.classList.remove('show');
            return;
        }
        renderPageGrid(popup);
        popup.classList.add('show');

        // 定位：优先显示在按钮上方，空间不足则显示在下方，始终保持在视口内
        const btn   = document.getElementById('pageGridBtn');
        _activePopup = popup;
        _activeAnchor = btn;
        _activePopupType = 'grid';
        const bRect = btn.getBoundingClientRect();
        const pRect = popup.getBoundingClientRect();
        const vw    = window.innerWidth;
        const vh    = window.innerHeight;

        // 水平：右对齐按钮，超出边界则夹紧
        let left = bRect.right - pRect.width;
        if (left < 8) left = 8;
        if (left + pRect.width > vw - 8) left = vw - pRect.width - 8;

        // 垂直：优先在按钮上方，否则在下方，最后夹紧至视口
        let top = bRect.top - pRect.height - 6;
        if (top < 8) top = bRect.bottom + 6;
        if (top + pRect.height > vh - 8) top = vh - pRect.height - 8;
        if (top < 8) top = 8;

        popup.style.left = left + 'px';
        popup.style.top  = top  + 'px';
    }

    function renderPageGrid(popup) {
        let html = `<div class="page-grid-header">
            <span>${escapeHtml(t('page-grid.title', 'PAGE SELECT'))}</span>
            <span style="color:var(--text-dim);font-size:.6rem;">${currentPage} / ${totalPages}</span>
        </div><div class="page-grid">`;
        for (let i = 1; i <= totalPages; i++) {
            html += `<button class="page-grid-item ${i === currentPage ? 'pg-active' : ''}"
                onclick="changePageFromGrid(${i})">${i}</button>`;
        }
        html += `</div>`;
        popup.innerHTML = html;
    }

    function renderPageGridIfOpen() {
        const popup = document.getElementById('pageGridPopup');
        if (popup.classList.contains('show')) {
            renderPageGrid(popup);
        }
    }

    function changePageFromGrid(page) {
        document.getElementById('pageGridPopup').classList.remove('show');
        changePage(page);
    }
