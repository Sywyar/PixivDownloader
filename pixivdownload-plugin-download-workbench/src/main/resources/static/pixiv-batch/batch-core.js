'use strict';
// PixivBatch namespace bootstrap (additive facade over global functions).
window.PixivBatch = window.PixivBatch || {};
['core','state','storage','userscripts','cookie','settings','filters','queue','sse','download','tabs'].forEach(function(k){ window.PixivBatch[k] = window.PixivBatch[k] || {}; });
window.PixivBatch.modes = window.PixivBatch.modes || {};
['quick','singleImport','user','search','series','schedule'].forEach(function(k){ window.PixivBatch.modes[k] = window.PixivBatch.modes[k] || {}; });
    let pageI18n = null;
    let pageLangSwitcher = null;
    let pageI18nNamespaces = [];
    let pageI18nNamespaceRefresh = null;
    let pageI18nNamespaceRefreshDirty = false;
    let pageI18nGeneration = 0;
    const BATCH_I18N_NAMESPACES = ['batch', 'common', 'ai', 'tour'];
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
        return bt('ai:batch.translate-lang-default', 'english');
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

    function addI18nNamespace(out, seen, value) {
        const namespace = value == null ? '' : String(value).trim();
        if (!namespace || seen.has(namespace)) return;
        seen.add(namespace);
        out.push(namespace);
    }

    async function batchI18nNamespaces(prefetchScheduleSources = false) {
        const out = [];
        const seen = new Set();
        BATCH_I18N_NAMESPACES.forEach(ns => addI18nNamespace(out, seen, ns));
        const qt = window.PixivBatch && window.PixivBatch.queueTypes;
        if (qt && typeof qt.i18nNamespaces === 'function') {
            try {
                const dynamicNamespaces = await qt.i18nNamespaces();
                (dynamicNamespaces || []).forEach(ns => addI18nNamespace(out, seen, ns));
            } catch (e) {
                console.warn('[batch] 读取下载类型 i18n namespace 失败：', e);
            }
        }
        const scheduleSources = window.PixivBatch && window.PixivBatch.scheduleSources;
        if (scheduleSources) {
            if (prefetchScheduleSources && typeof scheduleSources.refresh === 'function') {
                try {
                    // 来源 manifest 为 admin-only；在鉴权状态检测前预取，非管理员的 401/403 是正常降级。
                    await scheduleSources.refresh(false);
                } catch (e) {
                    if (!/HTTP 40[13]/.test(String(e && e.message || e))) {
                        console.warn('[batch] 预取计划来源 i18n namespace 失败：', e);
                    }
                }
            }
            if (typeof scheduleSources.i18nNamespaces === 'function') {
                try {
                    const sourceNamespaces = scheduleSources.i18nNamespaces();
                    (sourceNamespaces || []).forEach(ns => addI18nNamespace(out, seen, ns));
                } catch (e) {
                    console.warn('[batch] 读取计划来源 i18n namespace 失败：', e);
                }
            }
        }
        return out;
    }

    function sameI18nNamespaces(left, right) {
        return left.length === right.length && left.every((value, index) => value === right[index]);
    }

    function installPageI18nClient(nextClient, namespaces) {
        pageI18n = nextClient;
        const installedNamespaces = Array.isArray(namespaces)
            ? namespaces
            : (Array.isArray(nextClient && nextClient.namespaces) ? nextClient.namespaces : null);
        if (installedNamespaces) {
            pageI18nNamespaces = installedNamespaces.slice();
        }
        pageI18nGeneration++;
    }

    async function applyPageLanguageViews() {
        applyStaticPageTranslations();
        if (window.PixivBatch && window.PixivBatch.layout) {
            window.PixivBatch.layout.refreshLayoutToggle();
        }
        if (window.PixivNav) PixivNav.refresh();
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
        if (typeof updateSaveScheduleCardVisibility === 'function') {
            updateSaveScheduleCardVisibility();
        }
        const snapshotModal = document.getElementById('schedule-snapshot-modal');
        const snapshotTaskId = snapshotModal && !snapshotModal.hidden ? snapshotModal.dataset.taskId : null;
        if (state.mode === 'schedule') {
            // 语言切换后的卡片 / 队列正文 / 待重试面板重渲染统一由 loadScheduleTasks 内的
            // scheduleLastRenderedLang 比对触发，覆盖「切走→切回」也能取到新语言。
            await loadScheduleTasks();
        }
        if (snapshotTaskId) showScheduleSnapshot(snapshotTaskId);
        await refreshBatchCollections();
        if (_userscriptsLoaded) loadUserscripts();
        refreshGuideFab();
    }

    async function refreshPageI18nNamespaces() {
        if (!pageI18n) return false;
        if (pageI18nNamespaceRefresh) {
            pageI18nNamespaceRefreshDirty = true;
            return pageI18nNamespaceRefresh;
        }
        pageI18nNamespaceRefresh = (async () => {
            let changed = false;
            do {
                pageI18nNamespaceRefreshDirty = false;
                const namespaces = await batchI18nNamespaces(false);
                if (sameI18nNamespaces(namespaces, pageI18nNamespaces)) continue;
                let nextClient;
                while (true) {
                    const baseClient = pageI18n;
                    const baseGeneration = pageI18nGeneration;
                    const targetLang = baseClient.lang;
                    nextClient = await PixivI18n.create({lang: targetLang, namespaces});
                    if (pageI18n === baseClient
                        && pageI18nGeneration === baseGeneration
                        && pageI18n.lang === targetLang) {
                        break;
                    }
                }
                installPageI18nClient(nextClient, namespaces);
                if (pageLangSwitcher && typeof pageLangSwitcher.refresh === 'function') {
                    pageLangSwitcher.refresh(nextClient);
                }
                await applyPageLanguageViews();
                changed = true;
            } while (pageI18nNamespaceRefreshDirty);
            return changed;
        })();
        try {
            return await pageI18nNamespaceRefresh;
        } finally {
            pageI18nNamespaceRefresh = null;
        }
    }

    async function initPageI18n() {
        pageI18nNamespaces = await batchI18nNamespaces(true);
        installPageI18nClient(await PixivI18n.create({namespaces: pageI18nNamespaces}), pageI18nNamespaces);
        pageLangSwitcher = await PixivLangSwitcher.mount({
            mountPoint: document.getElementById('langSwitcherAnchor'),
            i18n: pageI18n,
            variant: 'green',
            onChange: async function (nextClient) {
                installPageI18nClient(nextClient);
                await applyPageLanguageViews();
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
                {target: '#tools-drawer', titleKey: 'tour:batch.cookie.title', bodyKey: 'tour:batch.cookie.body'},
                {target: '.tabs', titleKey: 'tour:batch.mode.title', bodyKey: 'tour:batch.mode.body'},
                {target: '#btn-start', titleKey: 'tour:batch.start.title', bodyKey: 'tour:batch.start.body'},
                {target: '#status-bar', titleKey: 'tour:batch.queue.title', bodyKey: 'tour:batch.queue.body'},
                {
                    target: 'a.app-nav-link[data-nav-markers~="first-download-result"]',
                    titleKey: 'tour:batch.gallery.title',
                    bodyKey: 'tour:batch.gallery.body'
                }
            ]
        });
    }

    function uiAlertKey(key, fallback, vars) {
        if (typeof PixivFeedback === 'undefined') {
            console.warn(bt(key, fallback, vars));
            return Promise.resolve();
        }
        return PixivFeedback.alert({
            title: bt('dialog.title.notice', '提示'),
            message: bt(key, fallback, vars),
            confirmLabel: bt('common:button.done', '完成')
        });
    }

    function uiConfirmKey(key, fallback, vars) {
        if (typeof PixivFeedback === 'undefined') return Promise.resolve(false);
        return PixivFeedback.confirm({
            title: bt('dialog.title.confirm', '请确认'),
            message: bt(key, fallback, vars),
            confirmLabel: bt('common:button.confirm', '确定'),
            cancelLabel: bt('common:button.cancel', '取消'),
            danger: true
        });
    }

    function uiPromptKey(key, fallback, value, options) {
        if (typeof PixivFeedback === 'undefined') return Promise.resolve(null);
        const inputOptions = options || {};
        return PixivFeedback.prompt({
            title: bt('dialog.title.input', '请输入'),
            message: bt(key, fallback, inputOptions.vars),
            value: value,
            inputType: inputOptions.inputType,
            min: inputOptions.min,
            max: inputOptions.max,
            step: inputOptions.step,
            confirmLabel: bt('common:button.confirm', '确定'),
            cancelLabel: bt('common:button.cancel', '取消')
        });
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

    async function apiGet(path, requestInit) {
        const init = Object.assign({}, requestInit || {});
        init.headers = Object.assign({}, pixivHeader(), init.headers || {});
        const res = await fetch(BASE + path, init);
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

    /* ============================================================
       预览结果「收起 / 展开」（User / Search / 系列 / 快捷获取 各预览界面共用）
    ============================================================ */
    // 切换某预览界面的收起 / 展开：隐藏 / 显示其结果区与分页，并同步按钮上的文案与箭头。
    function togglePreviewCollapse(btn, areaId, pagerId) {
        const area = document.getElementById(areaId);
        const collapsed = area ? !area.classList.contains('preview-collapsed') : true;
        applyPreviewCollapsed(areaId, pagerId, collapsed);
        syncPreviewCollapseBtn(btn, collapsed);
    }

    function applyPreviewCollapsed(areaId, pagerId, collapsed) {
        [areaId, pagerId].forEach(id => {
            if (!id) return;
            const el = document.getElementById(id);
            if (el) el.classList.toggle('preview-collapsed', collapsed);
        });
    }

    // 按钮文案 / 箭头随状态切换；data-i18n 同步为当前态的 key，保证切换界面语言时 pageI18n.apply 能正确重译。
    function syncPreviewCollapseBtn(btn, collapsed) {
        if (!btn) return;
        btn.classList.toggle('is-collapsed', collapsed);
        const label = btn.querySelector('.preview-collapse-label');
        if (label) {
            const key = collapsed ? 'preview.expand' : 'preview.collapse';
            label.setAttribute('data-i18n', key);
            label.textContent = bt(key, collapsed ? '展开' : '收起');
        }
    }

    // 重新加载 / 翻页某预览界面时调用：强制展开，使新内容可见，并复位对应的全部收起按钮
    //（同一结果区可能有多个收起按钮，如 Search 的搜索 / 批量两个子模式）。
    function resetPreviewCollapse(areaId, pagerId) {
        applyPreviewCollapsed(areaId, pagerId, false);
        document.querySelectorAll('.preview-collapse-btn[onclick*="' + areaId + '"]')
            .forEach(btn => syncPreviewCollapseBtn(btn, false));
    }


// ---- PixivBatch facade ----
window.PixivBatch.core = window.PixivBatch.core || {};
window.PixivBatch.core = Object.assign(window.PixivBatch.core, { bt, uiLang, interpolate, esc, escHtml, sleep, downloadTxt, formatBytes, formatSeconds, formatDurationMs, uiAlertKey, uiConfirmKey, uiPromptKey, loadAppInfo, BASE, setStatus, apiGet, checkBackend, togglePreviewCollapse, resetPreviewCollapse });
