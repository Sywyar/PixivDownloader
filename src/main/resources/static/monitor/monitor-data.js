'use strict';

    // ===================== 统计 =====================
    async function loadStatistics() {
        try {
            const res = await fetch('/api/downloaded/statistics');
            if (!res.ok) return;
            const s = await res.json();
            document.getElementById('totalArtworks').textContent = s.totalArtworks ?? 0;
            document.getElementById('totalImages').textContent   = s.totalImages   ?? 0;
            document.getElementById('totalMoved').textContent    = s.totalMoved    ?? 0;
        } catch (e) { console.error(t('log.stats-failed', '统计加载失败'), e); }
    }

    // ===================== 全量数据 =====================
    async function loadAllDataForChart() {
        try {
            const [historyRes, authorRes] = await Promise.all([
                fetch('/api/downloaded/history/paged?page=0&size=9999'),
                fetch('/api/authors')
            ]);
            if (!historyRes.ok) return;
            const data = await historyRes.json();
            const authors = authorRes.ok ? await authorRes.json() : [];
            allArtworksCache = data.content ?? [];
            authorMap = new Map((authors || [])
                .map(author => [normalizeAuthorId(author.authorId), author.name])
                .filter(([authorId]) => authorId !== null));
            updateChart(allArtworksCache);
            updateAuthorFilterIcon();
            renderFromCache();
        } catch (e) {
            console.error(t('log.data-failed', '数据加载失败'), e);
            document.getElementById('historyTableBody').innerHTML =
                '<tr><td colspan="10" class="text-center" style="color:var(--red);padding:2rem;">'
                + escapeHtml(t('status.load-failed-retry', 'LOAD FAILED — CLICK REFRESH TO RETRY'))
                + '</td></tr>';
        }
    }

    // ===================== 排序 =====================
    function getSortedCache() {
        if (!allArtworksCache) return [];
        return [...allArtworksCache].sort((a, b) => {
            let va = a[sortKey];
            let vb = b[sortKey];
            if (sortKey === 'title' || sortKey === 'extensions') {
                va = (va || '').toLowerCase();
                vb = (vb || '').toLowerCase();
                return sortDir === 'asc' ? va.localeCompare(vb) : vb.localeCompare(va);
            }
            if (sortKey === 'authorId') {
                va = normalizeAuthorId(a.authorId);
                vb = normalizeAuthorId(b.authorId);
                if (va === null && vb === null) return (b.time || 0) - (a.time || 0);
                if (va === null) return 1;
                if (vb === null) return -1;
                return sortDir === 'asc' ? va - vb : vb - va;
            }
            if (sortKey === 'moved') {
                va = a.moved ? 1 : 0;
                vb = b.moved ? 1 : 0;
            }
            if (sortKey === 'isR18') {
                va = a.xRestrict != null ? a.xRestrict : -1;
                vb = b.xRestrict != null ? b.xRestrict : -1;
            }
            if (sortKey === 'isAi') {
                va = a.isAi === true ? 1 : a.isAi === false ? 0 : -1;
                vb = b.isAi === true ? 1 : b.isAi === false ? 0 : -1;
            }
            return sortDir === 'asc' ? va - vb : vb - va;
        });
    }

    function sortBy(key) {
        if (sortKey === key) {
            sortDir = sortDir === 'asc' ? 'desc' : 'asc';
        } else {
            sortKey = key;
            sortDir = (key === 'time' || key === 'artworkId') ? 'desc' : 'asc';
        }
        currentPage = 1;
        renderSortHeaders();
        renderFromCache();
    }

    function renderSortHeaders() {
        const cols = ['artworkId', 'title', 'count', 'authorId', 'time', 'moved', 'isR18', 'isAi'];
        cols.forEach(col => {
            if (col === 'isAi') return;
            const th   = document.getElementById(`th-${col}`);
            const icon = document.getElementById(`sort-${col}`);
            if (!th || !icon) return;
            if (col === sortKey) {
                th.classList.add('sort-active');
                icon.textContent = sortDir === 'asc' ? '↑' : '↓';
            } else {
                th.classList.remove('sort-active');
                icon.textContent = '↕';
            }
        });
    }

    // ===================== 从缓存渲染（含分页） =====================
    function renderFromCache() {
        if (!allArtworksCache) return;
        let sorted = getSortedCache();

        // 应用格式筛选
        if (formatFilter.size > 0) {
            sorted = sorted.filter(a => {
                if (!a.extensions) return false;
                return a.extensions.split(',').some(f => formatFilter.has(f.trim().toLowerCase()));
            });
        }

        if (authorFilter.size > 0) {
            sorted = sorted.filter(a => {
                const authorId = normalizeAuthorId(a.authorId);
                return authorId !== null && authorFilter.has(authorId);
            });
        }

        // 应用 R18 筛选
        if (R18Filter !== null) {
            sorted = sorted.filter(a => {
                const x = a.xRestrict;
                if (R18Filter === 'r18')  return x === 1;
                if (R18Filter === 'r18g') return x === 2;
                if (R18Filter === 'sfw')  return x === 0;
                return true;
            });
        }

        if (aiFilter !== null) {
            sorted = sorted.filter(a => a.isAi !== null && a.isAi !== undefined && !!a.isAi === aiFilter);
        }

        const gridBtn = document.getElementById('pageGridBtn');
        if (searchQuery) {
            const q = searchQuery;
            const filtered = sorted.filter(a =>
                String(a.artworkId).includes(q) ||
                (a.title && a.title.toLowerCase().includes(q)) ||
                String(getAuthorLabel(a)).toLowerCase().includes(q) ||
                matchesArtworkTag(a, q)
            );
            document.getElementById('pagination').style.display = 'none';
            if (gridBtn) gridBtn.style.display = 'none';
            renderArtworkTable(filtered);
        } else {
            totalElements = sorted.length;
            totalPages    = Math.ceil(totalElements / PAGE_SIZE) || 1;
            if (currentPage > totalPages) currentPage = totalPages;
            const start = (currentPage - 1) * PAGE_SIZE;
            document.getElementById('pagination').style.display = '';
            renderArtworkTable(sorted.slice(start, start + PAGE_SIZE));
            renderPagination();
        }
    }

    // ===================== 搜索 =====================
    function onSearch() {
        searchQuery = document.getElementById('searchInput').value.trim().toLowerCase();
        if (!searchQuery) currentPage = 1;
        renderFromCache();
    }
