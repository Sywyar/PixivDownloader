(function () {
    'use strict';

    var STORE_THRESHOLD = 'pixiv_duplicates_dhash_threshold';
    var STORE_AHASH = 'pixiv_duplicates_ahash_threshold';

    var pageI18n = null;
    var pollTimer = null;
    var state = {
        threshold: 0,
        ahashThreshold: 0,
        scope: 'cross-artwork',
        page: 0,
        size: 20,
        totalGroups: 0
    };

    function el(id) {
        return document.getElementById(id);
    }

    function readStoredThreshold(key, fallback) {
        try {
            var raw = window.localStorage.getItem(key);
            if (raw === null) return fallback;
            var n = Number(raw);
            return Number.isFinite(n) ? Math.max(0, Math.min(32, Math.round(n))) : fallback;
        } catch (e) {
            return fallback;
        }
    }

    function writeStoredThreshold(key, value) {
        try {
            window.localStorage.setItem(key, String(value));
        } catch (e) {
            /* localStorage 不可用（隐私模式等）时忽略，仅本次会话生效 */
        }
    }

    function restoreThresholds() {
        state.threshold = readStoredThreshold(STORE_THRESHOLD, state.threshold);
        state.ahashThreshold = readStoredThreshold(STORE_AHASH, state.ahashThreshold);
        syncThresholdControls('threshold', state.threshold);
        syncThresholdControls('ahash', state.ahashThreshold);
    }

    function t(key, fallback, vars) {
        return pageI18n ? pageI18n.t(key, fallback, vars) : (fallback || key);
    }

    function escapeHtml(value) {
        return String(value == null ? '' : value)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    async function api(url, options) {
        var res = await fetch(url, Object.assign({
            credentials: 'same-origin',
            headers: { 'Accept': 'application/json' }
        }, options || {}));
        if (res.status === 401) {
            window.location.href = '/login.html?redirect=' + encodeURIComponent(location.pathname + location.search);
            throw new Error('Unauthorized');
        }
        var payload = null;
        try {
            payload = await res.json();
        } catch (e) {
            payload = null;
        }
        if (!res.ok) {
            throw new Error(payload && payload.error ? payload.error : ('HTTP ' + res.status));
        }
        return payload;
    }

    function queryString() {
        var params = new URLSearchParams();
        params.set('threshold', String(state.threshold));
        params.set('ahashThreshold', String(state.ahashThreshold));
        params.set('scope', state.scope);
        params.set('page', String(state.page));
        params.set('size', String(state.size));
        return params.toString();
    }

    function setBanner(key, fallback, error) {
        var banner = el('statusBanner');
        if (!banner) return;
        banner.hidden = false;
        banner.classList.toggle('error', !!error);
        banner.setAttribute('data-i18n', key);
        banner.textContent = t(key, fallback);
    }

    function hideBanner() {
        var banner = el('statusBanner');
        if (banner) banner.hidden = true;
    }

    function fmtScan(scan) {
        if (!scan || scan.state === 'IDLE') {
            return t('scan.idle', '扫描空闲');
        }
        if (scan.state === 'RUNNING') {
            return t('scan.running', '扫描中 {processed}/{total}', {
                processed: scan.processed || 0,
                total: scan.total || 0
            });
        }
        if (scan.state === 'DONE') {
            return t('scan.done', '扫描完成 {processed}/{total}', {
                processed: scan.processed || 0,
                total: scan.total || 0
            });
        }
        if (scan.state === 'FAILED') {
            return t('scan.failed', '扫描失败');
        }
        return scan.state;
    }

    function updateScan(scan) {
        var status = el('scanStatus');
        var progress = el('scanProgress');
        if (status) status.textContent = fmtScan(scan);
        var percent = 0;
        if (scan && scan.total > 0) {
            percent = Math.max(0, Math.min(100, Math.round((scan.processed / scan.total) * 100)));
        } else if (scan && scan.state === 'DONE') {
            percent = 100;
        }
        if (progress) progress.style.width = percent + '%';
        if (scan && scan.state === 'RUNNING') {
            startPolling();
        } else {
            stopPolling();
        }
    }

    function renderGroups(groups) {
        var grid = el('groupsGrid');
        if (!grid) return;
        grid.textContent = '';
        if (!groups || !groups.length) {
            grid.hidden = true;
            setBanner('status.empty', '暂无疑似重复图片', false);
            return;
        }
        hideBanner();
        grid.hidden = false;
        grid.innerHTML = groups.map(function (group) {
            var header = t('group.title', '{size} 张相似', { size: group.size });
            var distance = t('group.distance', '最大差异 {distance}', { distance: group.maxDistance });
            var items = group.items || [];
            var sameArtwork = items.length > 0 && items.every(function (it) {
                return it.artworkId === items[0].artworkId;
            });
            var scopeTag = sameArtwork
                ? '<span class="scope-tag same">' + escapeHtml(t('group.same-artwork', '同作品')) + '</span>'
                : '<span class="scope-tag cross">' + escapeHtml(t('group.cross-artwork', '跨作品')) + '</span>';
            return '<article class="group-card">'
                + '<header class="group-head">'
                + '<div class="group-head-left">'
                + '<h2 class="group-title">' + escapeHtml(header) + '</h2>'
                + scopeTag
                + '</div>'
                + '<span class="distance-badge">' + escapeHtml(distance) + '</span>'
                + '</header>'
                + '<div class="duplicate-items">' + items.map(renderItem).join('') + '</div>'
                + '</article>';
        }).join('');
        grid.querySelectorAll('img[data-fallback]').forEach(function (img) {
            img.addEventListener('error', function () {
                img.hidden = true;
                var fallback = img.parentElement.querySelector('.thumb-fallback');
                if (fallback) fallback.hidden = false;
            });
        });
    }

    function renderItem(item) {
        var href = '/pixiv-artwork.html?id=' + encodeURIComponent(item.artworkId);
        var author = item.authorName || (item.authorId ? String(item.authorId) : '');
        var restrict = item.xRestrict === 2 ? 'R18G' : (item.xRestrict === 1 ? 'R18' : '');
        return '<a class="dup-tile" href="' + escapeHtml(href) + '" target="_blank" rel="noopener" title="' + escapeHtml(item.title) + '">'
            + '<div class="dup-thumb">'
            + '<img loading="lazy" src="' + escapeHtml(item.thumbnailUrl) + '" alt="' + escapeHtml(item.title) + '" data-fallback="1">'
            + '<span class="thumb-fallback" hidden>' + escapeHtml(t('thumb.unavailable', '无缩略图')) + '</span>'
            + '<span class="dup-page">p' + escapeHtml(item.page) + '</span>'
            + (restrict ? '<span class="dup-restrict">' + restrict + '</span>' : '')
            + '</div>'
            + '<div class="dup-tile-meta">'
            + '<span class="dup-title">' + escapeHtml(item.title) + '</span>'
            + '<span class="dup-sub">#' + escapeHtml(item.artworkId) + ' · ' + escapeHtml(author) + '</span>'
            + '</div>'
            + '</a>';
    }

    function renderPager() {
        var pager = el('pager');
        if (!pager) return;
        var totalPages = Math.max(1, Math.ceil(state.totalGroups / state.size));
        pager.hidden = state.totalGroups <= state.size;
        var prev = el('prevPage');
        var next = el('nextPage');
        if (prev) prev.disabled = state.page <= 0;
        if (next) next.disabled = state.page >= totalPages - 1;
        var info = el('pagerInfo');
        if (info) {
            info.textContent = t('pager.info', '{page}/{total}', {
                page: Math.min(state.page + 1, totalPages),
                total: totalPages
            });
        }
    }

    function scopeLabel() {
        return state.scope === 'all' ? t('scope.all', '全部') : t('scope.cross', '跨作品');
    }

    function updateSummary() {
        var box = el('resultSummary');
        if (!box) return;
        if (!state.totalGroups) {
            box.hidden = true;
            box.classList.remove('flash');
            return;
        }
        box.hidden = false;
        box.classList.remove('flash');
        box.textContent = t('summary.count', '共 {total} 组 · 范围：{scope}', {
            total: state.totalGroups,
            scope: scopeLabel()
        });
    }

    function flashSummary() {
        var box = el('resultSummary');
        if (!box) return;
        box.hidden = false;
        box.classList.add('flash');
        box.textContent = t('summary.regrouped', '已重新分组') + ' · '
            + t('summary.count', '共 {total} 组 · 范围：{scope}', { total: state.totalGroups, scope: scopeLabel() });
        window.clearTimeout(flashSummary.timer);
        flashSummary.timer = window.setTimeout(updateSummary, 1800);
    }

    async function loadGroups() {
        setBanner('status.loading', '加载中...', false);
        try {
            var data = await api('/api/duplicates/groups?' + queryString());
            state.page = data.page || 0;
            state.size = data.size || state.size;
            state.totalGroups = data.totalGroups || 0;
            updateScan(data.scan);
            renderGroups(data.groups || []);
            renderPager();
            updateSummary();
        } catch (e) {
            if (e && e.message === 'Unauthorized') return;
            setBanner('status.error', '加载失败，请稍后重试', true);
        }
    }

    async function rescanGroups() {
        var button = el('rescanButton');
        if (button) button.disabled = true;
        try {
            var data = await api('/api/duplicates/rescan?' + queryString(), { method: 'POST' });
            state.page = data.page || 0;
            state.totalGroups = data.totalGroups || 0;
            updateScan(data.scan);
            renderGroups(data.groups || []);
            renderPager();
            flashSummary();
        } catch (e) {
            setBanner('status.error', '加载失败，请稍后重试', true);
        } finally {
            if (button) button.disabled = false;
        }
    }

    async function startScan() {
        var button = el('scanButton');
        if (button) button.disabled = true;
        try {
            var scan = await api('/api/duplicates/scan?force=false', { method: 'POST' });
            updateScan(scan);
            startPolling();
        } catch (e) {
            setBanner('status.scan-error', '扫描启动失败', true);
        } finally {
            if (button) button.disabled = false;
        }
    }

    async function pollScan() {
        try {
            var scan = await api('/api/duplicates/scan/status');
            updateScan(scan);
            if (scan && scan.state !== 'RUNNING') {
                await loadGroups();
            }
        } catch (e) {
            stopPolling();
        }
    }

    function startPolling() {
        if (pollTimer) return;
        pollTimer = window.setInterval(pollScan, 1500);
    }

    function stopPolling() {
        if (!pollTimer) return;
        window.clearInterval(pollTimer);
        pollTimer = null;
    }

    function clampThreshold(value, fallback) {
        var n = Number(value);
        if (!Number.isFinite(n)) return fallback;
        return Math.max(0, Math.min(32, Math.round(n)));
    }

    function syncThresholdControls(prefix, value) {
        var range = el(prefix + 'Range');
        var input = el(prefix + 'Input');
        if (range) range.value = String(value);
        if (input) input.value = String(value);
    }

    function bindThreshold(prefix, stateKey, storeKey) {
        var range = el(prefix + 'Range');
        var input = el(prefix + 'Input');
        function update(value) {
            state[stateKey] = clampThreshold(value, state[stateKey]);
            syncThresholdControls(prefix, state[stateKey]);
            writeStoredThreshold(storeKey, state[stateKey]);
            state.page = 0;
            loadGroups();
        }
        if (range) range.addEventListener('input', function () { syncThresholdControls(prefix, clampThreshold(range.value, state[stateKey])); });
        if (range) range.addEventListener('change', function () { update(range.value); });
        if (input) input.addEventListener('change', function () { update(input.value); });
    }

    function bindControls() {
        bindThreshold('threshold', 'threshold', STORE_THRESHOLD);
        bindThreshold('ahash', 'ahashThreshold', STORE_AHASH);

        document.querySelectorAll('.segment[data-scope]').forEach(function (button) {
            button.addEventListener('click', function () {
                state.scope = button.getAttribute('data-scope') || 'cross-artwork';
                state.page = 0;
                document.querySelectorAll('.segment[data-scope]').forEach(function (other) {
                    var active = other === button;
                    other.classList.toggle('active', active);
                    other.setAttribute('aria-selected', active ? 'true' : 'false');
                });
                loadGroups();
            });
        });

        var scan = el('scanButton');
        if (scan) scan.addEventListener('click', startScan);
        var rescan = el('rescanButton');
        if (rescan) rescan.addEventListener('click', rescanGroups);
        var prev = el('prevPage');
        if (prev) prev.addEventListener('click', function () {
            if (state.page > 0) {
                state.page -= 1;
                loadGroups();
            }
        });
        var next = el('nextPage');
        if (next) next.addEventListener('click', function () {
            var totalPages = Math.max(1, Math.ceil(state.totalGroups / state.size));
            if (state.page < totalPages - 1) {
                state.page += 1;
                loadGroups();
            }
        });
    }

    function applyStaticTranslations() {
        document.title = t('page.title', 'Pixiv Duplicates');
        if (pageI18n) pageI18n.apply(document.body);
        renderPager();
        updateSummary();
    }

    async function initI18n() {
        pageI18n = await PixivI18n.create({ namespaces: ['duplicates', 'gallery', 'common'] });
        await PixivLangSwitcher.mount({
            mountPoint: el('langSwitcherAnchor'),
            i18n: pageI18n,
            onChange: function (nextClient) {
                pageI18n = nextClient;
                applyStaticTranslations();
                loadGroups();
            }
        });
        PixivTheme.mount({ mountPoint: el('langSwitcherAnchor') });
        applyStaticTranslations();
    }

    document.addEventListener('DOMContentLoaded', async function () {
        restoreThresholds();
        bindControls();
        await initI18n();
        await loadGroups();
    });
})();
