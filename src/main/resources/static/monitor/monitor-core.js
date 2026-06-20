'use strict';
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
                if (window.PixivNav) PixivNav.refresh();
                renderFromCache();
                renderActiveDownloads();
                renderAuthorFilterPopupIfOpen();
                renderPageGridIfOpen();
                updateChart(allArtworksCache || []);
            }
        });
        PixivTheme.mount({
            mountPoint: document.getElementById('langSwitcherAnchor'),
            variant: 'green'
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
