'use strict';
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

        window.addEventListener('pixiv-theme-change', () => {
            if (allArtworksCache !== null) {
                updateChart(allArtworksCache);
            }
        });

        // i18n 在后台加载，加载完成后再补丁式应用翻译并重渲染已到达的动态内容
        initPageI18n().then(() => {
            if (allArtworksCache !== null) {
                renderFromCache();
                updateChart(allArtworksCache);
            }
            renderActiveDownloads();
        }).catch(err => console.error(t('log.i18n-failed', 'i18n 加载失败'), err));
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
