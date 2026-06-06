'use strict';
// PixivBatch namespace bootstrap (additive facade over global functions).
window.PixivBatch = window.PixivBatch || {};
['core','state','storage','userscripts','cookie','settings','filters','queue','sse','download','tabs'].forEach(function(k){ window.PixivBatch[k] = window.PixivBatch[k] || {}; });
window.PixivBatch.modes = window.PixivBatch.modes || {};
['quick','singleImport','user','search','series','schedule'].forEach(function(k){ window.PixivBatch.modes[k] = window.PixivBatch.modes[k] || {}; });
    let pageI18n = null;
    function interpolate(template, vars) {
        if (!vars) {
            return String(template);
        }
        return String(template).replace(/\{([a-zA-Z0-9_.-]+)\}/g, (match, name) => {
            return Object.prototype.hasOwnProperty.call(vars, name) ? String(vars[name]) : match;
        });
    }

    function bt(key, fallback, vars) {
        if (pageI18n) {
            return pageI18n.t(key.includes(':') ? key : 'batch:' + key, fallback, vars);
        }
        return interpolate(fallback != null ? fallback : key, vars);
    }

    function uiLang() {
        return pageI18n ? pageI18n.lang : 'zh-CN';
    }

    // 「新下载小说自动翻译」目标语言的默认值：跟随当前页面语言（中文页默认「简体中文」、英文页默认「english」）。
    // 用户未自定义（模型为空）时由此派生，避免把某种语言的默认译名烤进设置导致切换界面语言后不更新。
    function defaultNovelTranslateLang() {
        return bt('novel:batch.translate-lang-default', 'english');
    }

    let appInfo = null;
    let appInfoLoaded = false;

    function setFooterLink(id, href) {
        const link = document.getElementById(id);
        if (link && href) {
            link.href = href;
        }
    }

    function renderFooterInfo() {
        const nameEl = document.getElementById('app-name');
        const versionEl = document.getElementById('app-version');
        if (nameEl) {
            nameEl.textContent = appInfo && appInfo.name ? appInfo.name : '...';
        }
        if (versionEl) {
            if (appInfo && appInfo.version) {
                versionEl.textContent = appInfo.version;
            } else {
                versionEl.textContent = appInfoLoaded
                    ? bt('footer.version-unknown', 'unknown')
                    : bt('footer.version-loading', 'loading...');
            }
        }
        if (appInfo) {
            setFooterLink('app-github-link', appInfo.githubUrl);
            setFooterLink('app-releases-link', appInfo.releasesUrl);
            setFooterLink('app-docs-link', appInfo.docsUrl);
            setFooterLink('app-license-link', appInfo.licenseUrl);
        }
    }

    async function loadAppInfo() {
        renderFooterInfo();
        try {
            const res = await fetch(BASE + '/api/app/info', {credentials: 'same-origin'});
            if (!res.ok) throw new Error('HTTP ' + res.status);
            appInfo = await res.json();
        } catch (e) {
            appInfo = null;
        } finally {
            appInfoLoaded = true;
            renderFooterInfo();
        }
    }

    function summarySeparator() {
        return uiLang() === 'en-US' ? ', ' : '，';
    }

    function summaryJoin(parts) {
        return parts.filter(Boolean).join(summarySeparator());
    }

    function applyStaticPageTranslations() {
        document.title = bt('page.title', 'Pixiv Batch Download');
        if (pageI18n) {
            pageI18n.apply(document.body);
        }
        applyCookieHint();
        syncCookieToggleLabel();
        renderFooterInfo();
    }

    async function initPageI18n() {
        pageI18n = await PixivI18n.create({namespaces: ['batch', 'common', 'novel', 'tour']});
        await PixivLangSwitcher.mount({
            mountPoint: document.getElementById('langSwitcherAnchor'),
            i18n: pageI18n,
            variant: 'green',
            onChange: async function (nextClient) {
                pageI18n = nextClient;
                applyStaticPageTranslations();
                // 目标语言未自定义时跟随新语言刷新默认显示值
                refreshNovelTranslateLangDefault();
                renderQuotaBar();
                updateStats();
                renderQueue();
                setCurrent(state.currentItemId ? state.queue.find(q => q.id === state.currentItemId) || null : null);
                updateButtonsState();
                renderSearchResults();
                renderSearchPagination();
                if (seriesState.seriesId) {
                    renderSeriesResults();
                    renderSeriesPagination();
                }
                if (userState.allIds.length) {
                    renderUserResults();
                    renderUserPagination();
                }
                updateBatchLimitNote();
                const snapshotModal = document.getElementById('schedule-snapshot-modal');
                const snapshotTaskId = snapshotModal && !snapshotModal.hidden ? snapshotModal.dataset.taskId : null;
                if (state.mode === 'schedule') {
                    // 语言切换后的卡片 / 队列正文 / 待重试面板重渲染统一由 loadScheduleTasks 内的
                    // scheduleLastRenderedLang 比对触发，覆盖「切走→切回」也能取到新语言。
                    await loadScheduleTasks();
                }
                if (snapshotTaskId) {
                    showScheduleSnapshot(snapshotTaskId);
                }
                await refreshBatchCollections();
                if (_userscriptsLoaded) {
                    loadUserscripts();
                }
                refreshGuideFab();
            }
        });
        PixivTheme.mount({
            mountPoint: document.getElementById('langSwitcherAnchor'),
            variant: 'green'
        });
        applyStaticPageTranslations();
    }

    // 首次进入下载页时自动展示操作指引；语言切换时刷新文案。
    function setupTour(auto) {
        if (typeof PixivTour === 'undefined') {
            return;
        }
        PixivTour.init({
            pageKey: 'batch',
            i18n: pageI18n,
            auto: auto,
            steps: [
                {target: '#cookie-input', titleKey: 'tour:batch.cookie.title', bodyKey: 'tour:batch.cookie.body'},
                {target: '.tabs', titleKey: 'tour:batch.mode.title', bodyKey: 'tour:batch.mode.body'},
                {target: '#btn-start', titleKey: 'tour:batch.start.title', bodyKey: 'tour:batch.start.body'},
                {target: '#status-bar', titleKey: 'tour:batch.queue.title', bodyKey: 'tour:batch.queue.body'},
                {
                    target: 'a.app-nav-link[href*="pixiv-gallery"]',
                    titleKey: 'tour:batch.gallery.title',
                    bodyKey: 'tour:batch.gallery.body'
                }
            ]
        });
    }

    function uiAlertKey(key, fallback, vars) {
        alert(bt(key, fallback, vars));
    }

    function uiConfirmKey(key, fallback, vars) {
        return confirm(bt(key, fallback, vars));
    }

    const BASE = '';  // 使用相对路径，自动适配访问地址
    function formatSeconds(s) {
        s = Math.max(0, Math.round(s));
        const h = Math.floor(s / 3600);
        const m = Math.floor((s % 3600) / 60);
        const sec = s % 60;
        if (h > 0) return h + 'h ' + String(m).padStart(2, '0') + 'm ' + String(sec).padStart(2, '0') + 's';
        return String(m).padStart(2, '0') + ':' + String(sec).padStart(2, '0');
    }

    function formatBytes(bytes) {
        const n = Number(bytes);
        if (!Number.isFinite(n) || n < 0) return '';
        const units = ['B', 'KB', 'MB', 'GB'];
        let value = n;
        let idx = 0;
        while (value >= 1024 && idx < units.length - 1) {
            value /= 1024;
            idx++;
        }
        const digits = idx === 0 || value >= 10 ? 0 : 1;
        return `${value.toFixed(digits)} ${units[idx]}`;
    }

    function formatDurationMs(ms) {
        const n = Number(ms);
        if (!Number.isFinite(n) || n < 0) return '';
        const totalSeconds = Math.round(n / 1000);
        const minutes = Math.floor(totalSeconds / 60);
        const seconds = totalSeconds % 60;
        return minutes > 0 ? `${minutes}:${String(seconds).padStart(2, '0')}` : `${seconds}s`;
    }

    async function apiGet(path) {
        const res = await fetch(BASE + path, {headers: pixivHeader()});
        return res.json();
    }

    async function checkBackend() {
        try {
            const res = await fetch(BASE + '/api/download/status',
                {signal: AbortSignal.timeout(2000)});
            return res.status === 200;
        } catch {
            return false;
        }
    }

    const STATUS_COLORS = {info: '#007bff', success: '#28a745', error: '#dc3545', warning: '#e6a700'};

    function setStatus(msg, type = 'info') {
        const el = document.getElementById('status-bar');
        el.textContent = msg;
        el.style.color = STATUS_COLORS[type] || '#666';
    }

    function esc(s) {
        return String(s).replace(/[&<>"']/g, c =>
            ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'})[c]);
    }

    function downloadTxt(content, filename) {
        const a = document.createElement('a');
        a.href = URL.createObjectURL(new Blob([content], {type: 'text/plain'}));
        a.download = filename;
        a.click();
        setTimeout(() => URL.revokeObjectURL(a.href), 1000);
    }

    function sleep(ms) {
        return new Promise(r => setTimeout(r, ms));
    }

    function escHtml(str) {
        return String(str)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    }


// ---- PixivBatch facade ----
window.PixivBatch.core = window.PixivBatch.core || {};
window.PixivBatch.core = Object.assign(window.PixivBatch.core, { bt, uiLang, interpolate, esc, escHtml, sleep, downloadTxt, formatBytes, formatSeconds, formatDurationMs, uiAlertKey, uiConfirmKey, loadAppInfo, BASE, setStatus, apiGet, checkBackend });
