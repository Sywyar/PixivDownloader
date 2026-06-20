(function () {
    'use strict';

    var SVG_NS = 'http://www.w3.org/2000/svg';

    var pageI18n = null;
    var dashboard = null;

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

    // 按语义 placement 向通用下钻渲染器请求一个 href（统计页只认得 placement 与变量名，不知道目标页面 / 查询参数 /
    // 是哪个插件贡献）。无渲染器、无贡献、当前身份不可见或模板不可用时返回 null，调用方据此回退纯展示。
    function drilldownHref(placement, variables) {
        return (window.PixivDrilldowns && typeof PixivDrilldowns.href === 'function')
            ? PixivDrilldowns.href(placement, variables)
            : null;
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
            // 语义下钻（stats.top-authors）：有 href（贡献存在且当前身份可见）则渲染可点击的 a，否则保持纯展示 div。
            // 统计页只提供变量值（作者 id / 名称），不知道目标页面路径或查询参数名——这些由贡献方插件决定。
            var href = drilldownHref('stats.top-authors', { authorId: a.authorId, authorName: a.name });
            var row = document.createElement(href ? 'a' : 'div');
            row.className = 'bar-row';
            row.title = a.name;
            if (href) row.href = href;

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
            // 语义下钻（stats.top-tags）：有 href 则渲染可点击的 a，否则保持纯展示 span（见 renderAuthors 说明）。
            var href = drilldownHref('stats.top-tags', {
                tagId: tag.tagId, tagName: tag.name, tagTranslatedName: tag.translatedName || ''
            });
            var chip = document.createElement(href ? 'a' : 'span');
            chip.className = 'tag-chip';
            if (href) chip.href = href;
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
        pageI18n = await PixivI18n.create({ namespaces: ['stats', 'common'] });
        await PixivLangSwitcher.mount({
            mountPoint: document.getElementById('langSwitcherAnchor'),
            i18n: pageI18n,
            onChange: function (nextClient) {
                pageI18n = nextClient;
                applyStaticTranslations();
                if (window.PixivNav) PixivNav.refresh();
                if (window.PixivPageSections) PixivPageSections.refresh();
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
        // 侧栏借用其它插件能力的区块由 /js/pixiv-page-sections.js 据 /api/page-sections 自渲染——禁用某插件则它
        // 贡献的区块自然消失，统计页无需在此判断任何插件是否可用，也不触达任何具体插件的 href / API。
        // 先等通用下钻渲染器拉完 /api/drilldowns（成功或失败都 resolve），使首次 renderAll 能同步解析 Top 作者 /
        // 热门标签的下钻 href；无贡献时 href() 返回 null、保持纯展示。语言切换重渲染复用已缓存贡献、无需再拉取。
        if (window.PixivDrilldowns) await PixivDrilldowns.ready();
        await loadDashboard();
    });
})();
