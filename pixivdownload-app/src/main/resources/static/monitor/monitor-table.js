'use strict';
    // ===================== 渲染表格 =====================
    function renderArtworkTable(artworks) {
        const tbody      = document.getElementById('historyTableBody');
        const emptyState = document.getElementById('emptyHistoryState');

        if (!artworks.length) {
            tbody.innerHTML = '';
            emptyState.classList.remove('d-none');
            if (!searchQuery) document.getElementById('pagination').innerHTML = '';
            return;
        }

        emptyState.classList.add('d-none');
        tbody.innerHTML = artworks.map(artwork => {
            const dt          = formatDateTime(artwork.time);
            const status      = artwork.moved ? t('status.moved', 'MOVED') : t('status.done', 'DONE');
            const statusClass = artwork.moved ? 'status-moved' : 'status-completed';
            const title       = escapeHtml(artwork.title || '—');
            const authorId    = normalizeAuthorId(artwork.authorId);
            const authorName  = escapeHtml(getAuthorName(artwork));
            const fmtBadges = (artwork.extensions || '—').split(',')
                .map(f => `<span class="fmt-badge fmt-${f.trim()}">${f.trim().toUpperCase()}</span>`).join(' ');
            return `
            <tr>
                <td class="artwork-id-cell">${artwork.artworkId}</td>
                <td class="artwork-title" title="${title}">${title}</td>
                <td><span class="count-badge">${artwork.count}</span></td>
                <td>${fmtBadges}</td>
                <td class="author-cell">
                    ${authorId === null
                        ? '<span class="author-chip author-empty">—</span>'
                        : `<button type="button" class="author-chip ${authorFilter.has(authorId) ? 'active' : ''}" onclick="toggleAuthorInFilter(${authorId})" title="${escapeHtml(t('author.filter', 'Filter by author'))}">${authorName}</button>
                           <div class="author-id-sub">${authorId}</div>`}
                </td>
                <td class="time-cell">
                    <div class="date">${dt.date}</div>
                    <div class="time">${dt.time}</div>
                </td>
                <td><span class="status-badge ${statusClass}">${status}</span></td>
                <td>${artwork.xRestrict == null
                    ? '<span class="R18-badge" style="background:rgba(100,116,139,.12);color:#64748b;border-color:rgba(100,116,139,.3)">' + escapeHtml(t('value.unknown', 'Unknown')) + '</span>'
                    : artwork.xRestrict === 2
                        ? '<span class="R18-badge R18-r18g">R-18G</span>'
                        : artwork.xRestrict === 1
                            ? '<span class="R18-badge R18-yes">R-18</span>'
                            : '<span class="R18-badge R18-no">SFW</span>'}</td>
                <td>${artwork.isAi === null || artwork.isAi === undefined
                    ? '<span class="ai-badge ai-unknown">' + escapeHtml(t('value.unknown', 'Unknown')) + '</span>'
                    : `<span class="ai-badge ${artwork.isAi ? 'ai-yes' : 'ai-no'}">${artwork.isAi ? 'AI' : escapeHtml(t('value.human', 'Human'))}</span>`}</td>
                <td>
                    <div class="action-buttons">
                        <button class="btn-term" onclick="viewArtworkDetails(${artwork.artworkId})" title="${escapeHtml(t('button.details', 'Details'))}">
                            <i class="fas fa-eye"></i>
                        </button>
                        <button class="btn-term btn-info-term" onclick="viewThumbnails(${artwork.artworkId})" title="${escapeHtml(t('button.images', 'Images'))}">
                            <i class="fas fa-images"></i>
                        </button>
                    </div>
                </td>
            </tr>`;
        }).join('');
    }

    // ===================== 分页 =====================
    function renderPagination() {
        const pagination = document.getElementById('pagination');
        const gridBtn    = document.getElementById('pageGridBtn');
        if (totalPages <= 1) {
            pagination.innerHTML = '';
            if (gridBtn) gridBtn.style.display = 'none';
            return;
        }
        if (gridBtn) gridBtn.style.display = '';

        const maxVisible = 5;
        let start = Math.max(1, currentPage - Math.floor(maxVisible / 2));
        let end   = Math.min(totalPages, start + maxVisible - 1);
        if (end - start + 1 < maxVisible) start = Math.max(1, end - maxVisible + 1);

        let html = `
        <li class="page-item ${currentPage === 1 ? 'disabled' : ''}">
            <a class="page-link" href="#" onclick="changePage(${currentPage - 1}); return false;">
                <i class="fas fa-chevron-left"></i></a>
        </li>`;

        for (let i = start; i <= end; i++) {
            html += `
            <li class="page-item ${i === currentPage ? 'active' : ''}">
                <a class="page-link" href="#" onclick="changePage(${i}); return false;">${i}</a>
            </li>`;
        }

        html += `
        <li class="page-item ${currentPage === totalPages ? 'disabled' : ''}">
            <a class="page-link" href="#" onclick="changePage(${currentPage + 1}); return false;">
                <i class="fas fa-chevron-right"></i></a>
        </li>`;

        pagination.innerHTML = html;
    }

    function changePage(page) {
        if (page < 1 || page > totalPages || searchQuery) return;
        currentPage = page;
        renderFromCache();
    }
