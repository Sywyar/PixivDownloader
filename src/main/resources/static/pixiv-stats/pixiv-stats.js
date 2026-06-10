(function () {
    'use strict';

    var SVG_NS = 'http://www.w3.org/2000/svg';
    var HEART_SVG = '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 21l-1.45-1.32C5.4 14.99 2 11.9 2 8.05 2 5.4 4.06 3.3 6.7 3.3c1.5 0 2.94.7 3.88 1.81L12 6.5l1.42-1.39A5.2 5.2 0 0 1 17.3 3.3C19.94 3.3 22 5.4 22 8.05c0 3.85-3.4 6.94-8.55 11.63L12 21z"/></svg>';

    var pageI18n = null;
    var dashboard = null;
    var state = { collections: [] };

    function t(key, fallback, vars) {
        return pageI18n ? pageI18n.t(key, fallback, vars) : (fallback || key);
    }

    function fmtNumber(n) {
        try {
            return Number(n || 0).toLocaleString();
        } catch (e) {
            return String(n || 0);
        }
    }

    function escapeHtml(value) {
        return String(value == null ? '' : value)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    async function api(url) {
        var res = await fetch(url, { credentials: 'same-origin', headers: { 'Accept': 'application/json' } });
        if (res.status === 401) {
            window.location.href = '/login.html?redirect=' + encodeURIComponent(location.pathname + location.search);
            throw new Error('Unauthorized');
        }
        if (!res.ok) {
            throw new Error('Request failed: ' + res.status);
        }
        return res.json();
    }

    // ---------- sidebar ----------
    function openMobileSidebar() {
        document.getElementById('sidebar').classList.add('mobile-open');
        document.getElementById('mobileOverlay').classList.add('active');
    }

    function closeMobileSidebar() {
        document.getElementById('sidebar').classList.remove('mobile-open');
        document.getElementById('mobileOverlay').classList.remove('active');
    }

    async function setupAdminMode() {
        try {
            var res = await fetch('/api/admin/invites/access-check', { credentials: 'same-origin' });
            if (res.ok) document.body.classList.add('admin-mode');
        } catch (_) { /* not admin */ }
    }

    async function loadCollections() {
        try {
            var resp = await api('/api/collections');
            state.collections = (resp && resp.collections) || [];
            renderCollections();
        } catch (e) {
            // 收藏夹侧栏是附属信息，加载失败不影响仪表盘
        }
    }

    function renderCollections() {
        var list = document.getElementById('collectionList');
        if (!list) return;
        if (!state.collections.length) {
            list.innerHTML = '<div class="collection-empty">'
                + escapeHtml(t('gallery:status.no-collections', 'No collections')) + '</div>';
            return;
        }
        list.innerHTML = state.collections.map(function (c) {
            var icon = c.iconExt
                ? '<img src="/api/collections/' + c.id + '/icon?v=' + encodeURIComponent(c.createdTime || '') + '" alt="">'
                : HEART_SVG;
            var href = '/pixiv-gallery.html?view=all&collectionIds=' + encodeURIComponent(c.id);
            return '<a class="collection-item" href="' + escapeHtml(href) + '">'
                + '<div class="collection-icon">' + icon + '</div>'
                + '<span class="collection-label">' + escapeHtml(c.name) + '</span>'
                + '<span class="collection-count">' + escapeHtml(c.artworkCount != null ? c.artworkCount : 0) + '</span>'
                + '</a>';
        }).join('');
    }

    // ---------- dashboard rendering ----------
    function setBanner(messageKey, fallback, isError) {
        var banner = document.getElementById('statusBanner');
        if (!banner) return;
        banner.hidden = false;
        banner.classList.toggle('error', !!isError);
        banner.setAttribute('data-i18n', messageKey);
        banner.textContent = t(messageKey, fallback);
    }

    function hideBanner() {
        var banner = document.getElementById('statusBanner');
        if (banner) banner.hidden = true;
    }

    function show(id) {
        var el = document.getElementById(id);
        if (el) el.hidden = false;
    }

    function renderOverview(o) {
        if (!o) return;
        var map = {
            ovArtworks: o.totalArtworks,
            ovImages: o.totalImages,
            ovNovels: o.totalNovels,
            ovAuthors: o.totalAuthors,
            ovTags: o.totalTags,
            ovSeries: o.totalSeries,
            ovMoved: o.totalMoved
        };
        Object.keys(map).forEach(function (id) {
            var el = document.getElementById(id);
            if (el) el.textContent = fmtNumber(map[id]);
        });
        show('overviewGrid');
    }

    function authorHref(a) {
        var params = new URLSearchParams();
        params.set('view', 'all');
        params.set('filterAuthorId', String(a.authorId));
        if (a.name) params.set('filterAuthorName', a.name);
        return '/pixiv-gallery.html?' + params.toString();
    }

    function renderAuthors(authors) {
        var list = document.getElementById('authorsList');
        if (!list) return;
        list.textContent = '';
        if (!authors || !authors.length) {
            var empty = document.createElement('div');
            empty.className = 'empty-hint';
            empty.textContent = t('authors.empty', '暂无作者数据');
            list.appendChild(empty);
            show('authorsPanel');
            return;
        }
        var max = authors.reduce(function (m, a) { return Math.max(m, a.count); }, 0) || 1;
        authors.forEach(function (a) {
            var row = document.createElement('a');
            row.className = 'bar-row';
            row.href = authorHref(a);
            row.title = a.name;

            var name = document.createElement('span');
            name.className = 'bar-name';
            name.textContent = a.name;

            var count = document.createElement('span');
            count.className = 'bar-count';
            count.textContent = fmtNumber(a.count);

            var track = document.createElement('div');
            track.className = 'bar-track';
            var fill = document.createElement('div');
            fill.className = 'bar-fill';
            fill.style.width = Math.max(2, Math.round((a.count / max) * 100)) + '%';
            track.appendChild(fill);

            row.appendChild(name);
            row.appendChild(count);
            row.appendChild(track);
            list.appendChild(row);
        });
        show('authorsPanel');
    }

    function tagHref(tag) {
        var params = new URLSearchParams();
        params.set('view', 'all');
        params.set('filterTagId', String(tag.tagId));
        if (tag.name) params.set('filterTag', tag.name);
        if (tag.translatedName) params.set('filterTagTranslated', tag.translatedName);
        return '/pixiv-gallery.html?' + params.toString();
    }

    function renderTags(tags) {
        var cloud = document.getElementById('tagCloud');
        if (!cloud) return;
        cloud.textContent = '';
        if (!tags || !tags.length) {
            var empty = document.createElement('div');
            empty.className = 'empty-hint';
            empty.textContent = t('tags.empty', '暂无标签数据');
            cloud.appendChild(empty);
            show('tagsPanel');
            return;
        }
        var max = tags.reduce(function (m, x) { return Math.max(m, x.count); }, 0) || 1;
        var min = tags.reduce(function (m, x) { return Math.min(m, x.count); }, max);
        tags.forEach(function (tag) {
            var chip = document.createElement('a');
            chip.className = 'tag-chip';
            chip.href = tagHref(tag);
            var ratio = max === min ? 1 : (tag.count - min) / (max - min);
            chip.style.fontSize = (12 + ratio * 12).toFixed(1) + 'px';

            var label = document.createElement('span');
            label.textContent = tag.translatedName && tag.translatedName.trim() ? tag.translatedName : tag.name;

            var cnt = document.createElement('span');
            cnt.className = 'tag-chip-count';
            cnt.textContent = fmtNumber(tag.count);

            chip.appendChild(label);
            chip.appendChild(cnt);
            chip.title = tag.name + ' · ' + fmtNumber(tag.count);
            cloud.appendChild(chip);
        });
        show('tagsPanel');
    }

    function svgEl(name, attrs) {
        var el = document.createElementNS(SVG_NS, name);
        if (attrs) {
            Object.keys(attrs).forEach(function (k) { el.setAttribute(k, attrs[k]); });
        }
        return el;
    }

    function renderMonthly(monthly) {
        var wrap = document.getElementById('monthlyChart');
        if (!wrap) return;
        wrap.textContent = '';
        if (!monthly || !monthly.length) {
            var empty = document.createElement('div');
            empty.className = 'empty-hint';
            empty.textContent = t('monthly.empty', '暂无下载记录');
            wrap.appendChild(empty);
            show('monthlyPanel');
            return;
        }

        var W = Math.max(640, monthly.length * 48);
        var H = 240;
        var padL = 44, padR = 16, padT = 16, padB = 32;
        var innerW = W - padL - padR;
        var innerH = H - padT - padB;
        var maxCount = monthly.reduce(function (m, x) { return Math.max(m, x.count); }, 0) || 1;

        var svg = svgEl('svg', { viewBox: '0 0 ' + W + ' ' + H, width: W, height: H,
            role: 'img', 'aria-label': t('monthly.title', '按月下载量') });

        svg.appendChild(svgEl('line', { class: 'chart-axis',
            x1: padL, y1: padT + innerH, x2: padL + innerW, y2: padT + innerH }));

        var stepX = monthly.length > 1 ? innerW / (monthly.length - 1) : 0;
        function xAt(i) { return monthly.length > 1 ? padL + i * stepX : padL + innerW / 2; }
        function yAt(c) { return padT + innerH - (c / maxCount) * innerH; }

        var linePts = monthly.map(function (m, i) { return xAt(i) + ',' + yAt(m.count); });
        var areaPts = padL + ',' + (padT + innerH) + ' ' + linePts.join(' ')
            + ' ' + (padL + (monthly.length > 1 ? innerW : innerW / 2)) + ',' + (padT + innerH);
        svg.appendChild(svgEl('polygon', { class: 'chart-area', points: areaPts }));
        svg.appendChild(svgEl('polyline', { class: 'chart-line', points: linePts.join(' ') }));

        var yMaxLabel = svgEl('text', { class: 'chart-label', x: padL - 6, y: padT + 4, 'text-anchor': 'end' });
        yMaxLabel.textContent = fmtNumber(maxCount);
        svg.appendChild(yMaxLabel);

        var labelEvery = Math.ceil(monthly.length / 12);
        monthly.forEach(function (m, i) {
            var dot = svgEl('circle', { class: 'chart-dot', cx: xAt(i), cy: yAt(m.count), r: 3 });
            var title = svgEl('title');
            title.textContent = m.month + ' · ' + fmtNumber(m.count);
            dot.appendChild(title);
            svg.appendChild(dot);

            if (i % labelEvery === 0 || i === monthly.length - 1) {
                var label = svgEl('text', { class: 'chart-label', x: xAt(i), y: H - 10, 'text-anchor': 'middle' });
                label.textContent = m.month;
                svg.appendChild(label);
            }
        });

        wrap.appendChild(svg);
        show('monthlyPanel');
    }

    function renderAll() {
        if (!dashboard) return;
        renderOverview(dashboard.overview);
        renderMonthly(dashboard.monthly);
        renderAuthors(dashboard.topAuthors);
        renderTags(dashboard.topTags);
    }

    function applyStaticTranslations() {
        document.title = t('page.title', 'Pixiv 统计');
        if (pageI18n) pageI18n.apply(document.body);
    }

    async function loadDashboard() {
        try {
            dashboard = await api('/api/stats/dashboard');
            hideBanner();
            renderAll();
        } catch (e) {
            if (e && e.message === 'Unauthorized') return;
            setBanner('status.error', '加载失败，请稍后重试', true);
        }
    }

    async function initI18n() {
        pageI18n = await PixivI18n.create({ namespaces: ['stats', 'gallery', 'common'] });
        await PixivLangSwitcher.mount({
            mountPoint: document.getElementById('langSwitcherAnchor'),
            i18n: pageI18n,
            onChange: function (nextClient) {
                pageI18n = nextClient;
                applyStaticTranslations();
                renderCollections();
                renderAll();
            }
        });
        PixivTheme.mount({ mountPoint: document.getElementById('langSwitcherAnchor') });
        applyStaticTranslations();
    }

    function wireSidebar() {
        var menuBtn = document.getElementById('mobileMenuBtn');
        if (menuBtn) menuBtn.addEventListener('click', openMobileSidebar);
        var overlay = document.getElementById('mobileOverlay');
        if (overlay) overlay.addEventListener('click', closeMobileSidebar);
    }

    document.addEventListener('DOMContentLoaded', async function () {
        wireSidebar();
        await initI18n();
        setupAdminMode();
        loadCollections();
        await loadDashboard();
    });
})();
