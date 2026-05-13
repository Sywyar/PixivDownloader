    let pageI18n = null;
    function interpolate(template, vars) {
        if (!vars) {
            return String(template);
        }
        return String(template).replace(/\{([a-zA-Z0-9_.-]+)\}/g, (match, name) => {
            return Object.prototype.hasOwnProperty.call(vars, name) ? String(vars[name]) : match;
        });
    }

    function t(key, fallback, vars) {
        if (pageI18n) {
            return pageI18n.t('monitor:' + key, fallback, vars);
        }
        return interpolate(fallback != null ? fallback : key, vars);
    }

    function localeTag() {
        return pageI18n && pageI18n.lang === 'en-US' ? 'en-US' : 'zh-CN';
    }

    function applyStaticPageTranslations() {
        const title = t('page.title', 'PIXIV // DOWNLOAD MONITOR');
        document.title = title;
        if (pageI18n) {
            pageI18n.apply(document.body);
        }
        const glitch = document.getElementById('monitorTitleGlitch');
        if (glitch) {
            glitch.dataset.text = title;
        }
    }

    async function initPageI18n() {
        pageI18n = await PixivI18n.create({namespaces: ['monitor', 'common']});
        await PixivLangSwitcher.mount({
            mountPoint: document.getElementById('langSwitcherAnchor'),
            i18n: pageI18n,
            variant: 'cyberpunk',
            onChange: function (nextClient) {
                pageI18n = nextClient;
                applyStaticPageTranslations();
                renderFromCache();
                renderActiveDownloads();
                renderAuthorFilterPopupIfOpen();
                renderPageGridIfOpen();
                updateChart(allArtworksCache || []);
            }
        });
        applyStaticPageTranslations();
    }

    // ===================== 状态 =====================
    let currentPage = 1;
    let totalPages = 1;
    let totalElements = 0;
    const PAGE_SIZE = 10;

    let searchQuery = '';
    let allArtworksCache = null;
    let authorMap = new Map();

    let sortKey = 'time';
    let sortDir = 'desc';
    let formatFilter = new Set(); // 格式筛选，空=显示全部
    let authorFilter = new Set(); // 作者筛选，空=显示全部
    let authorFilterQuery = '';
    let R18Filter = null; // null=全部, 'r18'=仅R-18, 'r18g'=仅R-18G, 'sfw'=仅SFW
    let aiFilter = null; // null=全部, true=仅AI, false=仅人工

    let downloadStatsChart = null;
    let activeDownloads = [];
    let sharedSse = null;        // 共享 EventSource 单例
    let sseSubscribed = new Set(); // 当前关注的 artworkId 集合（数字）

    let updateInterval;
    let activeUpdateTimer = null;
    let lastCompletionTime = 0; // 最后一个下载完成的时间戳
    let foregroundBurstUntil = 0; // 后台切回前台后的 1s 密集轮询截止时间戳
    let artworkModal = null;
    let imageModal = null;

    let thumbArtworkId = null;
    let thumbCurrentPage = 1;
    let thumbTotalPages = 1;
    let thumbCount = 0;
    const THUMB_PAGE_SIZE = 24;

    let fullImgArtworkId = null;
    let fullImgPage = 0;
    let fullImgTotal = 0;
    let fullImgLoading = false;

    // ===================== 工具 =====================
    function escapeHtml(str) {
        return String(str ?? '')
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    }

    function formatDateTime(epochMillis) {
        const d = new Date(epochMillis);
        const locale = localeTag();
        return {
            date: d.toLocaleDateString(locale),
            time: d.toLocaleTimeString(locale, { hour: '2-digit', minute: '2-digit' }),
            full: d.toLocaleString(locale)
        };
    }

    function normalizeAuthorId(value) {
        if (value === null || value === undefined || value === '') return null;
        const parsed = Number.parseInt(String(value), 10);
        return Number.isFinite(parsed) && parsed > 0 ? parsed : null;
    }

    function getAuthorName(artwork) {
        const authorId = normalizeAuthorId(artwork?.authorId);
        const directName = String(artwork?.authorName || '').trim();
        if (directName) return directName;
        if (authorId !== null && authorMap.has(authorId)) {
            return String(authorMap.get(authorId) || '').trim() || String(authorId);
        }
        return authorId !== null ? String(authorId) : '—';
    }

    function getAuthorLabel(artwork) {
        const authorId = normalizeAuthorId(artwork?.authorId);
        if (authorId === null) return '—';
        const name = getAuthorName(artwork);
        return name === String(authorId) ? `#${authorId}` : `${name} (#${authorId})`;
    }

    function matchesArtworkTag(artwork, query) {
        if (!Array.isArray(artwork?.tags) || !query) return false;
        return artwork.tags.some(tag => {
            const name = String(tag?.name || '').toLowerCase();
            const translated = String(tag?.translatedName || '').toLowerCase();
            return name.includes(query) || translated.includes(query);
        });
    }

    function getAuthorOptions() {
        const merged = new Map(authorMap);
        (allArtworksCache || []).forEach(artwork => {
            const authorId = normalizeAuthorId(artwork.authorId);
            if (authorId === null || merged.has(authorId)) return;
            merged.set(authorId, getAuthorName(artwork));
        });
        return [...merged.entries()]
            .map(([id, name]) => ({ id, name: String(name || id) }))
            .sort((a, b) => {
                const byName = a.name.localeCompare(b.name, 'zh-CN');
                return byName !== 0 ? byName : a.id - b.id;
            });
    }

    let _activePopup = null;
    let _activeAnchor = null;
    let _activePopupType = null; // 'filter' | 'grid'

    function positionFilterPopup(th, popup) {
        const rect = th.getBoundingClientRect();
        popup.style.top = (rect.bottom + 4) + 'px';
        popup.style.left = rect.left + 'px';
        _activePopup = popup;
        _activeAnchor = th;
        _activePopupType = 'filter';
    }

    function _repositionActivePopup() {
        if (!_activePopup || !_activePopup.classList.contains('show')) return;
        if (_activePopupType === 'filter') {
            const rect = _activeAnchor.getBoundingClientRect();
            _activePopup.style.top = (rect.bottom + 4) + 'px';
            _activePopup.style.left = rect.left + 'px';
        } else if (_activePopupType === 'grid') {
            const btn = _activeAnchor;
            const bRect = btn.getBoundingClientRect();
            const pRect = _activePopup.getBoundingClientRect();
            const vw = window.innerWidth;
            const vh = window.innerHeight;
            let left = bRect.right - pRect.width;
            if (left < 8) left = 8;
            if (left + pRect.width > vw - 8) left = vw - pRect.width - 8;
            let top = bRect.top - pRect.height - 6;
            if (top < 8) top = bRect.bottom + 6;
            if (top + pRect.height > vh - 8) top = vh - pRect.height - 8;
            if (top < 8) top = 8;
            _activePopup.style.left = left + 'px';
            _activePopup.style.top = top + 'px';
        }
    }

    // ===================== 初始化 =====================
    document.addEventListener('DOMContentLoaded', () => {
        artworkModal = new bootstrap.Modal(document.getElementById('artworkModal'));
        imageModal   = new bootstrap.Modal(document.getElementById('imageModal'));

        // 点击外部关闭弹出框
        document.addEventListener('click', () => {
            document.getElementById('formatFilterPopup')?.classList.remove('show');
            document.getElementById('pageGridPopup')?.classList.remove('show');
        });

        // 滚动时跟随按钮重新定位弹出框
        window.addEventListener('scroll', _repositionActivePopup, true);

        ['artworkModal', 'imageModal'].forEach(id => {
            document.getElementById(id).addEventListener('hidden.bs.modal', () => {
                document.querySelectorAll('.modal-backdrop').forEach(el => el.remove());
                document.body.classList.remove('modal-open');
            });
        });

        // 立即异步触发数据加载（与 i18n 初始化并行），避免主界面被慢请求阻塞
        initDashboard();
        updateInterval = setInterval(updateDashboard, 5000);
        foregroundBurstUntil = Date.now() + 10000; // 页面打开后 10s 内每秒轮询
        scheduleActiveSync();

        document.addEventListener('keydown', e => {
            if (!document.getElementById('imageModal').classList.contains('show')) return;
            if (e.key === 'ArrowLeft')  navigateFullImg(-1);
            if (e.key === 'ArrowRight') navigateFullImg(1);
        });

        // i18n 在后台加载，加载完成后再补丁式应用翻译并重渲染已到达的动态内容
        initPageI18n().then(() => {
            if (allArtworksCache !== null) {
                renderFromCache();
                updateChart(allArtworksCache);
            }
            renderActiveDownloads();
        }).catch(err => console.error('i18n 加载失败', err));
    });

    document.addEventListener('visibilitychange', () => {
        if (!document.hidden) {
            foregroundBurstUntil = Date.now() + 10000; // 回到前台后 10s 内每秒轮询
            scheduleActiveSync(true); // 立即查询一次
        }
    });

    window.addEventListener('beforeunload', () => {
        clearInterval(updateInterval);
        clearTimeout(activeUpdateTimer);
        sseSubscribed.clear();
        closeSharedSSE();
    });

    function initDashboard() {
        loadStatistics();
        loadAllDataForChart();
        syncActiveIds();

        document.getElementById('th-extensions').addEventListener('click', toggleFormatFilter);
        document.getElementById('th-R18').addEventListener('click', toggleR18Filter);
        document.getElementById('th-isAi').addEventListener('click', toggleAiFilter);
        document.getElementById('pageGridBtn').addEventListener('click', togglePageGrid);
        document.getElementById('formatFilterPopup').addEventListener('click', e => e.stopPropagation());

        document.getElementById('searchInput').addEventListener('input', onSearch);
        document.getElementById('refreshHistory').addEventListener('click', () => {
            searchQuery = '';
            document.getElementById('searchInput').value = '';
            sortKey = 'time';
            sortDir = 'desc';
            formatFilter = new Set();
            authorFilter = new Set();
            authorFilterQuery = '';
            R18Filter = null;
            aiFilter = null;
            currentPage = 1;
            document.getElementById('pagination').style.display = '';
            updateFormatFilterIcon();
            updateAuthorFilterIcon();
            updateR18FilterIcon();
            updateAiFilterIcon();
            loadStatistics();
            loadAllDataForChart();
        });
    }

    // ===================== 定期更新 =====================
    function updateDashboard() {
        updateLastUpdatedTime();
        loadStatistics();
    }

    function updateLastUpdatedTime() {
        document.getElementById('lastUpdatedTime').textContent =
            new Date().toLocaleTimeString(localeTag());
    }

    // ===================== 统计 =====================
    async function loadStatistics() {
        try {
            const res = await fetch('/api/downloaded/statistics');
            if (!res.ok) return;
            const s = await res.json();
            document.getElementById('totalArtworks').textContent = s.totalArtworks ?? 0;
            document.getElementById('totalImages').textContent   = s.totalImages   ?? 0;
            document.getElementById('totalMoved').textContent    = s.totalMoved    ?? 0;
        } catch (e) { console.error('统计加载失败', e); }
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
            console.error('数据加载失败', e);
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

    // ===================== 图表 =====================
    function updateChart(artworks) {
        const ctx = document.getElementById('downloadStatsChart').getContext('2d');

        const monthly = {};
        artworks.forEach(a => {
            const d   = new Date(a.time);
            const key = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
            monthly[key] = (monthly[key] || 0) + a.count;
        });

        const labels = Object.keys(monthly).sort();
        const data   = labels.map(l => monthly[l]);

        if (downloadStatsChart) downloadStatsChart.destroy();

        downloadStatsChart = new Chart(ctx, {
            type: 'bar',
            data: {
                labels,
                datasets: [{
                    label: t('panel.monthly-images', 'Monthly Images'),
                    data,
                    backgroundColor: 'rgba(0, 229, 255, 0.15)',
                    borderColor:     'rgba(0, 229, 255, 0.8)',
                    borderWidth: 1,
                    hoverBackgroundColor: 'rgba(0, 229, 255, 0.3)',
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                scales: {
                    y: {
                        beginAtZero: true,
                        grid: { color: 'rgba(0,229,255,0.06)' },
                        ticks: { color: '#475569', font: { family: 'JetBrains Mono', size: 10 } },
                        border: { color: 'rgba(0,229,255,0.1)' }
                    },
                    x: {
                        grid: { color: 'rgba(0,229,255,0.06)' },
                        ticks: { color: '#475569', font: { family: 'JetBrains Mono', size: 10 } },
                        border: { color: 'rgba(0,229,255,0.1)' }
                    }
                },
                plugins: {
                    legend: { labels: { color: '#475569', font: { family: 'JetBrains Mono', size: 10 } } }
                }
            }
        });
    }

    // ===================== SSE 管理（共享单连接版） =====================
    function ensureSharedSSE() {
        if (sharedSse) return;
        const src = new EventSource(`/api/sse/download`);
        src.addEventListener('download-status', e => {
            try {
                const data = JSON.parse(e.data);
                if (!data || data.artworkId === undefined || data.artworkId === null) return;
                const aid = Number(data.artworkId);
                if (!sseSubscribed.has(aid)) return; // 仅处理我们关注的作品
                handleSseEvent(aid, data);
            } catch {}
        });
        // EventSource 自动重连
        sharedSse = src;
    }

    function closeSharedSSE() {
        if (sharedSse) {
            try { sharedSse.close(); } catch {}
            sharedSse = null;
        }
    }

    function openSSE(artworkId) {
        sseSubscribed.add(Number(artworkId));
        ensureSharedSSE();
    }

    function closeSSE(artworkId) {
        sseSubscribed.delete(Number(artworkId));
        if (sseSubscribed.size === 0) closeSharedSSE();
    }

    function handleSseEvent(artworkId, data) {
        if (data.completed || data.failed || data.cancelled) {
            closeSSE(artworkId);
            activeDownloads = activeDownloads.filter(d => d.artworkId !== artworkId);
            lastCompletionTime = Date.now();
            scheduleActiveSync(); // 切换到快速轮询
            document.getElementById('activeCount').textContent     = activeDownloads.length;
            document.getElementById('activeDownloads').textContent = activeDownloads.length;
            renderActiveDownloads();
            updateLastUpdatedTime();
            loadStatistics();
            currentPage = 1;
            loadAllDataForChart();
            return;
        }
        const entry = activeDownloads.find(d => d.artworkId === artworkId);
        if (entry) {
            if (data.downloadedCount !== undefined) entry.downloadedCount = data.downloadedCount;
            if (data.totalImages     !== undefined) entry.totalImages     = data.totalImages;
            if (data.progress        !== undefined) entry.progressPercentage = data.progress;
        }
        document.getElementById('activeCount').textContent     = activeDownloads.length;
        document.getElementById('activeDownloads').textContent = activeDownloads.length;
        renderActiveDownloads();
        updateLastUpdatedTime();
    }

    // ===================== 活跃下载同步 =====================
    function getActiveSyncInterval() {
        if (document.hidden) return 15000;
        if (Date.now() < foregroundBurstUntil) return 1000;
        if (activeDownloads.length > 0) return 1000;
        if (Date.now() - lastCompletionTime < 10000) return 1000;
        return 8000;
    }

    function scheduleActiveSync(immediate) {
        clearTimeout(activeUpdateTimer);
        if (immediate) {
            syncActiveIds();
        } else {
            activeUpdateTimer = setTimeout(() => syncActiveIds(), getActiveSyncInterval());
        }
    }

    async function syncActiveIds() {
        try {
            const res = await fetch('/api/download/status/active');
            if (!res.ok) return;
            const { artworkIds } = await res.json();
            const currentIds = new Set(artworkIds.map(Number));

            // 移除不再活跃的条目
            const prevCount = activeDownloads.length;
            activeDownloads.filter(d => !currentIds.has(d.artworkId)).forEach(d => closeSSE(d.artworkId));
            activeDownloads = activeDownloads.filter(d => currentIds.has(d.artworkId));
            // 清理掉已不在活跃列表里的订阅
            for (const id of Array.from(sseSubscribed)) {
                if (!currentIds.has(id)) closeSSE(id);
            }

            // 添加新条目并建立 SSE
            const existingIds = new Set(activeDownloads.map(d => d.artworkId));
            const newIds = [...currentIds].filter(id => !existingIds.has(id));
            const newDetails = await Promise.all(
                newIds.map(id => fetch(`/api/download/status/${id}`).then(r => r.ok ? r.json() : null))
            );
            newDetails.filter(d => d && !d.completed && !d.failed && !d.cancelled).forEach(d => {
                activeDownloads.push(d);
                openSSE(d.artworkId);
            });

            document.getElementById('activeCount').textContent     = activeDownloads.length;
            document.getElementById('activeDownloads').textContent = activeDownloads.length;
            renderActiveDownloads();
            updateLastUpdatedTime();

            if (prevCount > 0 && activeDownloads.length < prevCount) {
                lastCompletionTime = Date.now();
                loadStatistics();
                currentPage = 1;
                loadAllDataForChart();
            }
        } catch (e) { console.error('活跃下载同步失败', e); }
        scheduleActiveSync();
    }

    function renderActiveDownloads() {
        const container  = document.getElementById('activeDownloadsList');
        const emptyState = document.getElementById('emptyActiveState');

        if (!activeDownloads.length) {
            container.innerHTML = '';
            emptyState.classList.remove('d-none');
            return;
        }

        emptyState.classList.add('d-none');
        container.innerHTML = activeDownloads.map(d => {
            const progress = d.progressPercentage || 0;
            return `
            <div class="active-download-item">
                <div class="active-download-header">
                    <div>
                        <div class="download-artwork-title" title="${escapeHtml(d.title)}">${escapeHtml(d.title || '—')}</div>
                        <div class="download-artwork-id">#${d.artworkId}</div>
                        <div class="active-download-source">pixiv.net</div>
                    </div>
                </div>
                <div>
                    <div class="progress-info">
                        <span class="progress-count">${d.downloadedCount || 0} / ${d.totalImages || 0}</span>
                        <span class="progress-percentage">${progress.toFixed(1)}%</span>
                    </div>
                    <div class="active-download-progress">
                        <div class="active-download-progress-bar" style="width:${progress}%"></div>
                    </div>
                </div>
                <div class="download-status">
                    <div class="status-indicator"></div>
                    <span>${escapeHtml(t('status.downloading', 'DOWNLOADING...'))}</span>
                </div>
            </div>`;
        }).join('');
    }

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
            console.error('加载作品详情失败', e);
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
            console.error('加载缩略图失败', e);
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
                console.error('加载完整图片失败');
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
