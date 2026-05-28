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

    function bt(key, fallback, vars) {
        if (pageI18n) {
            return pageI18n.t(key.includes(':') ? key : 'batch:' + key, fallback, vars);
        }
        return interpolate(fallback != null ? fallback : key, vars);
    }

    function uiLang() {
        return pageI18n ? pageI18n.lang : 'zh-CN';
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
            setFooterLink('app-wiki-link', appInfo.wikiUrl);
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

    function novelStageLabel(stage) {
        if (!stage) return '';
        return bt('queue.stage.' + stage, stage);
    }

    /**
     * 流式抓取 JSON：边读边通过 onProgress(已接收, 总字节) 上报进度，
     * 用于小说正文（meta）下载的流式进度条。返回一个与 Response 兼容的轻量对象
     * （含 ok / status / json()）。非 2xx 或浏览器不支持流时回退为原始 Response。
     */
    async function fetchJsonWithProgress(url, opts, onProgress) {
        const res = await fetch(url, opts);
        if (!res.ok || !res.body || typeof res.body.getReader !== 'function') {
            return res;
        }
        const totalHeader = res.headers.get('Content-Length');
        const total = totalHeader ? Number(totalHeader) : 0;
        const reader = res.body.getReader();
        const chunks = [];
        let received = 0;
        for (; ;) {
            const {done, value} = await reader.read();
            if (done) break;
            chunks.push(value);
            received += value.length;
            if (onProgress) onProgress(received, total);
        }
        let len = 0;
        chunks.forEach(c => len += c.length);
        const buf = new Uint8Array(len);
        let off = 0;
        chunks.forEach(c => {
            buf.set(c, off);
            off += c.length;
        });
        const text = new TextDecoder('utf-8').decode(buf);
        return {
            ok: res.ok,
            status: res.status,
            json: () => Promise.resolve(JSON.parse(text))
        };
    }

    /**
     * 把后端小说下载状态写入队列项，提供比单一“阶段：X”更细的展示：
     * 下载内嵌图片时附带 (已完成/总数) 计数；下载封面时附带流式字节进度。
     * 维护 item.novelEmbedded / item.novelCover 供进度条渲染。
     */
    function applyNovelStage(item, status) {
        const stage = status.stage;
        const eTotal = Number(status.embeddedTotal || 0);
        const eDone = Number(status.embeddedDone || 0);
        const cTotal = Number(status.coverTotalBytes || 0);
        const cDone = Number(status.coverDownloadedBytes || 0);
        item.novelEmbedded = (stage === 'downloading-images' && eTotal > 0)
            ? {done: eDone, total: eTotal} : null;
        item.novelCover = (stage === 'downloading-cover')
            ? {done: cDone, total: cTotal} : null;
        if (stage === 'downloading-images' && eTotal > 0) {
            item.lastMessage = bt('queue.message.novel-images',
                '阶段：下载内嵌图片（{done}/{total}）', {done: eDone, total: eTotal});
        } else {
            item.lastMessage = bt('queue.message.stage', '阶段：{stage}',
                {stage: novelStageLabel(stage)});
        }
    }

    function novelByteProgressHtml(p, labelKey, labelDefault, color) {
        if (!p || !(p.done > 0 || p.total > 0)) return '';
        const valueText = p.total > 0
            ? `${formatBytes(p.done || 0)} / ${formatBytes(p.total)}`
            : formatBytes(p.done || 0);
        return miniProgressHtml(
            bt(labelKey, labelDefault),
            valueText,
            p.total > 0 ? Math.round((p.done || 0) / p.total * 100) : null,
            color
        );
    }

    function formatNovelProgressHtml(q) {
        if (q.kind !== 'novel' || q.status !== 'downloading') return '';
        const parts = [];
        parts.push(novelByteProgressHtml(q.novelText, 'queue.novel-text.label', '小说正文', '#6366f1'));
        const e = q.novelEmbedded;
        if (e && e.total > 0) {
            parts.push(miniProgressHtml(
                bt('queue.novel-images.label', '内嵌图片'),
                bt('queue.novel-images.count', '{done}/{total} 张', {done: e.done || 0, total: e.total}),
                Math.round((e.done || 0) / e.total * 100),
                '#0d9488'
            ));
        }
        parts.push(novelByteProgressHtml(q.novelCover, 'queue.novel-cover.label', '封面', '#0ea5e9'));
        return parts.filter(Boolean).join('');
    }

    function summaryJoin(parts) {
        return parts.filter(Boolean).join(summarySeparator());
    }

    function syncCookieToggleLabel() {
        const input = document.getElementById('cookie-input');
        const button = document.getElementById('cookie-toggle');
        if (!input || !button) return;
        button.textContent = input.type === 'password'
            ? bt('cookie.toggle.show', '显示')
            : bt('cookie.toggle.hide', '隐藏');
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
                setupTour(false);
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

    /* ============================================================
       状态
    ============================================================ */
    const DEFAULT_FILE_NAME_TEMPLATE = '{artwork_id}_p{page}';
    const QUICK_FETCH_MODE = 'quick-fetch';
    const SINGLE_IMPORT_MODE = 'single-import';
    const SINGLE_IMPORT_NOVEL_SOURCE = 'single-import-novel';
    const WINDOWS_RESERVED_FILE_NAMES = new Set([
        'CON', 'PRN', 'AUX', 'NUL',
        'COM1', 'COM2', 'COM3', 'COM4', 'COM5', 'COM6', 'COM7', 'COM8', 'COM9',
        'LPT1', 'LPT2', 'LPT3', 'LPT4', 'LPT5', 'LPT6', 'LPT7', 'LPT8', 'LPT9'
    ]);

    let state = {
        mode: QUICK_FETCH_MODE,
        queue: [],
        isRunning: false,
        isPaused: false,
        stopRequested: false,
        activeWorkers: 0,
        currentItemId: null,
        userId: '',
        username: '',
        sharedSse: null,        // 共享 EventSource 单例
        sharedSseConnectionId: null,
        sseRefs: {},            // artworkId -> 引用计数；共享连接由批量任务生命周期统一关闭
        sseListeners: {},
        stats: {success: 0, failed: 0, active: 0, skipped: 0},
        settings: {
            interval: 2,
            intervalUnit: 's',
            imageDelay: 0,
            imageDelayUnit: 'ms',
            concurrent: 1,
            skipHistory: false,
            verifyHistoryFiles: false,
            bookmark: false,
            collectionId: null,
            fileNameTemplate: DEFAULT_FILE_NAME_TEMPLATE,
            novelFormat: 'txt',
            mergeNovelSeries: false,
            mergeNovelFormat: 'epub',
            userKind: 'illust',     // 'illust' | 'novel' — User 模式作品类型
            searchKind: 'illust'    // 'illust' | 'novel' — Search 模式作品类型
        }
    };

    const BASE = '';  // 使用相对路径，自动适配访问地址
    const STATUS_TIMEOUT_MS = 300000;

    /* ============================================================
       配额 & 压缩包
    ============================================================ */
    let quotaInfo = {enabled: false, adminMode: false, artworksUsed: 0, maxArtworks: 50, resetSeconds: 0};
    let archiveCountdownTimer = null;
    let archivePollTimer = null;
    let quotaResetTimer = null;
    let quotaExceededHandled = false;

    async function initQuota() {
        try {
            const res = await fetch(BASE + '/api/quota/init', {method: 'POST', credentials: 'same-origin'});
            if (!res.ok) return;
            const data = await res.json();
            quotaInfo.adminMode = !!data.adminMode;
            if (quotaInfo.adminMode) {
                quotaInfo.enabled = false;
                hideQuotaBar();
                return;
            }
            if (!data.enabled) {
                quotaInfo.enabled = false;
                hideQuotaBar();
                return;
            }
            quotaInfo = {
                enabled: true, adminMode: false, artworksUsed: data.artworksUsed, maxArtworks: data.maxArtworks,
                resetSeconds: data.resetSeconds
            };
            renderQuotaBar();
            startQuotaResetCountdown();
            // 恢复已有的压缩包链接
            if (data.archive && data.archive.token) {
                showArchiveCard(data.archive.token, data.archive.expireSeconds, data.archive.status === 'ready');
            }
        } catch {
        }
    }

    function hideQuotaBar() {
        document.getElementById('quota-bar').style.display = 'none';
        document.getElementById('qb-reset').textContent = '';
        clearInterval(quotaResetTimer);
    }

    function renderQuotaBar() {
        if (!quotaInfo.enabled) {
            hideQuotaBar();
            return;
        }
        document.getElementById('quota-bar').style.display = 'block';
        document.getElementById('qb-used').textContent = quotaInfo.artworksUsed;
        document.getElementById('qb-max').textContent = quotaInfo.maxArtworks;
        const pct = Math.min(100, Math.round(quotaInfo.artworksUsed / quotaInfo.maxArtworks * 100));
        const fill = document.getElementById('qb-fill');
        fill.style.width = pct + '%';
        fill.className = 'qb-fill' + (pct >= 90 ? ' danger' : pct >= 70 ? ' warn' : '');
        document.getElementById('qb-reset').textContent =
            quotaInfo.resetSeconds > 0
                ? bt('status.quota-reset', '配额重置剩余：{time}', {time: formatSeconds(quotaInfo.resetSeconds)})
                : '';
    }

    function startQuotaResetCountdown() {
        clearInterval(quotaResetTimer);
        if (quotaInfo.resetSeconds <= 0) return;
        quotaResetTimer = setInterval(() => {
            if (quotaInfo.resetSeconds > 0) quotaInfo.resetSeconds--;
            renderQuotaBar();
            if (quotaInfo.resetSeconds <= 0) clearInterval(quotaResetTimer);
        }, 1000);
    }

    function handleQuotaExceeded(data) {
        quotaInfo.artworksUsed = data.artworksUsed || quotaInfo.artworksUsed;
        renderQuotaBar();

        // 标记所有未开始/等待中的队列项为失败
        state.queue.forEach(q => {
            if (['pending', 'idle', 'paused'].includes(q.status)) {
                q.status = 'failed';
                q.lastMessage = bt('queue.message.failed-quota', '失败 - 达到限额');
                q.endTime = q.endTime || new Date().toISOString();
            }
        });
        state.stopRequested = true;
        state.isRunning = false;
        updateStats();
        saveQueue();
        renderQueue();
        updateButtonsState();
        setStatus(bt('status.archive-limit', '已达到下载限额'), 'error');

        const token = data.archiveToken;
        const expireSeconds = data.archiveExpireSeconds || 3600;
        showArchiveCard(token, expireSeconds, false);
    }

    function showArchiveCard(token, expireSeconds, ready, title = bt('status.archive-limit', '已达到下载限额')) {
        clearInterval(archiveCountdownTimer);
        clearInterval(archivePollTimer);

        document.getElementById('archive-card').style.display = 'block';
        document.getElementById('ac-title').textContent = title;
        document.getElementById('ac-expired-area').style.display = 'none';
        document.getElementById('ac-dl-area').style.display = 'none';

        if (ready) {
            activateArchiveDownload(token, expireSeconds);
        } else {
            document.getElementById('ac-status').textContent = bt('status.archive-packing', '正在打包已下载文件，请稍候...');
            pollArchiveReady(token, expireSeconds);
        }
    }

    function pollArchiveReady(token, expireSeconds) {
        archivePollTimer = setInterval(async () => {
            try {
                const res = await fetch(BASE + '/api/archive/status/' + token);
                const data = await res.json();
                if (data.status === 'ready') {
                    clearInterval(archivePollTimer);
                    activateArchiveDownload(token, data.expireSeconds || expireSeconds);
                } else if (data.status === 'expired') {
                    clearInterval(archivePollTimer);
                    showArchiveExpired();
                } else if (data.status === 'empty') {
                    clearInterval(archivePollTimer);
                    document.getElementById('ac-status').textContent = bt(
                        'status.archive-empty',
                        '暂无可打包文件（当前下载仍在进行中，完成后自动包含）'
                    );
                }
            } catch {
            }
        }, 2000);
    }

    function activateArchiveDownload(token, expireSeconds) {
        document.getElementById('ac-status').textContent = bt(
            'status.archive-ready',
            '压缩包已就绪，请在有效期内下载：'
        );
        const dlArea = document.getElementById('ac-dl-area');
        dlArea.style.display = 'block';
        const btn = document.getElementById('ac-dl-btn');
        btn.href = BASE + '/api/archive/download/' + token;
        btn.removeAttribute('disabled');

        let remaining = Math.max(0, parseInt(expireSeconds));
        document.getElementById('ac-countdown').textContent = bt(
            'status.archive-validity',
            '下载链接有效期：{time}',
            {time: formatSeconds(remaining)}
        );
        archiveCountdownTimer = setInterval(() => {
            remaining--;
            if (remaining <= 0) {
                clearInterval(archiveCountdownTimer);
                showArchiveExpired();
            } else {
                document.getElementById('ac-countdown').textContent = bt(
                    'status.archive-validity',
                    '下载链接有效期：{time}',
                    {time: formatSeconds(remaining)}
                );
            }
        }, 1000);
    }

    function showArchiveExpired() {
        document.getElementById('ac-dl-area').style.display = 'none';
        document.getElementById('ac-expired-area').style.display = 'block';
        document.getElementById('ac-status').textContent = '';
    }

    async function autoPackAfterQueue() {
        try {
            const res = await fetch(BASE + '/api/quota/pack', {
                method: 'POST',
                credentials: 'same-origin'
            });
            if (res.status === 204) return; // 无文件可打包（可能已被打包或源文件已删除）
            if (!res.ok) return;
            const data = await res.json();
            if (data.archiveToken) {
                setStatus(bt('status.batch-finished-packing', '批量下载结束，正在打包文件...'), 'info');
                showArchiveCard(
                    data.archiveToken,
                    data.archiveExpireSeconds || 3600,
                    false,
                    bt('status.download-complete-packing', '下载完成，正在打包')
                );
            }
        } catch {
        }
    }

    async function triggerAdminPack() {
        const ids = state.queue
            .filter(q => q.status === 'completed')
            .map(q => Number(q.id))
            .filter(Number.isFinite);

        if (ids.length === 0) {
            setStatus(bt('status.no-completed-to-pack', '队列中暂无已完成的作品可供打包'), 'warning');
            return;
        }

        const btn = document.getElementById('admin-pack-btn');
        btn.disabled = true;

        try {
            const res = await fetch(BASE + '/api/archive/pack-artworks', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                credentials: 'same-origin',
                body: JSON.stringify({artworkIds: ids})
            });

            if (res.status === 401) {
                isAdmin = false;
                updateAuthButtons();
                updateAdminPackButton();
                setStatus(bt('status.login-expired', '登录状态已失效，请重新登录'), 'error');
                return;
            }

            if (res.status === 204) {
                setStatus(bt('status.pack-folder-missing', '数据库中未找到对应文件夹，可能已被移动或删除'), 'warning');
                return;
            }

            let data = null;
            try {
                data = await res.json();
            } catch {
            }

            if (!res.ok) {
                setStatus(
                    (data && data.message)
                        ? data.message
                        : bt('status.pack-failed-http', '打包失败：HTTP {code}', {code: res.status}),
                    'error'
                );
                return;
            }

            setStatus(
                bt('status.pack-request-submitted', '已提交打包请求（{count} 个作品），正在生成压缩包...', {count: ids.length}),
                'info'
            );
            showArchiveCard(
                data.archiveToken,
                data.archiveExpireSeconds || 3600,
                false,
                bt('status.admin-packing', '管理员打包中（{count} 个作品）', {count: ids.length})
            );
        } catch (e) {
            setStatus(bt('status.pack-request-failed', '打包请求失败：{message}', {message: e.message}), 'error');
        } finally {
            updateAdminPackButton();
        }
    }

    function formatSeconds(s) {
        s = Math.max(0, Math.round(s));
        const h = Math.floor(s / 3600);
        const m = Math.floor((s % 3600) / 60);
        const sec = s % 60;
        if (h > 0) return h + 'h ' + String(m).padStart(2, '0') + 'm ' + String(sec).padStart(2, '0') + 's';
        return String(m).padStart(2, '0') + ':' + String(sec).padStart(2, '0');
    }

    function formatStatsText(pending, success, failed, active, skipped) {
        return bt(
            'status.stats',
            '队列: {pending} | 成功: {success} | 失败: {failed} | 进行中: {active} | 跳过: {skipped}',
            {pending, success, failed, active, skipped}
        );
    }

    function formatImageProgressText(downloaded, total) {
        return bt('status.image-progress', '{downloaded} / {total} 张', {downloaded, total});
    }

    function queueStatusText(status) {
        return {
            idle: bt('queue.status.waiting', '等待中'),
            pending: bt('queue.status.waiting', '等待中'),
            downloading: bt('queue.status.downloading', '下载中'),
            completed: bt('queue.status.completed', '已完成'),
            failed: bt('queue.status.failed', '失败'),
            paused: bt('queue.status.paused', '已暂停'),
            skipped: bt('queue.status.skipped', '已跳过')
        }[status] || status;
    }

    function queueSourceText(source) {
        const normalizedSource = normalizeImportMode(source);
        return {
            user: bt('queue.source.user', 'User'),
            search: bt('queue.source.search', 'Search'),
            series: bt('queue.source.series', 'Series'),
            [QUICK_FETCH_MODE]: bt('queue.source.quick-fetch', '快捷'),
            [SINGLE_IMPORT_MODE]: bt('queue.source.import', '导入'),
            [SINGLE_IMPORT_NOVEL_SOURCE]: bt('queue.source.import', '导入')
        }[normalizedSource] || bt('queue.source.import', '导入');
    }

    // 兼容旧版本：返回旧版本的 ntab 模式标识符
    function legacyImportMode() {
        return 'n' + 'tab';
    }

    // 兼容旧版本：将旧版本 ntab 模式标识符归一化为新的 single-import 模式标识符
    function normalizeImportMode(mode) {
        const legacyMode = legacyImportMode();
        if (mode === legacyMode) return SINGLE_IMPORT_MODE;
        if (mode === legacyMode + '-novel') return SINGLE_IMPORT_NOVEL_SOURCE;
        return mode;
    }

    function actionOutcomePart(action, labels) {
        if (!action) return '';
        const status = String(action.status || '').toLowerCase();
        const reason = action.message ? String(action.message) : '';
        let text = labels.unknown;
        if (status === 'success') text = labels.success;
        else if (status === 'failed') text = labels.failed;
        else if (status === 'skipped') text = labels.skipped;
        else if (status === 'exists') text = labels.exists;

        if ((status === 'failed' || status === 'skipped') && reason) {
            text = text + (uiLang() === 'en-US' ? ': ' : '：') + reason;
        } else if (!['success', 'failed', 'skipped', 'exists'].includes(status) && reason) {
            text = text + (uiLang() === 'en-US' ? ': ' : '：') + reason;
        }
        const tone = status === 'success' || status === 'exists'
            ? 'success'
            : status === 'failed' || status === 'skipped'
                ? 'error'
                : 'warning';
        return {text, tone};
    }

    function actionOutcomeText(action, labels) {
        const part = actionOutcomePart(action, labels);
        return part ? part.text : '';
    }

    function postDownloadOutcomeParts(data) {
        const parts = [];
        if (data && data.bookmarkResult) {
            parts.push(actionOutcomePart(data.bookmarkResult, {
                success: bt('queue.outcome.pixiv-bookmark.success', 'Pixiv 收藏成功'),
                failed: bt('queue.outcome.pixiv-bookmark.failed', 'Pixiv 收藏失败'),
                skipped: bt('queue.outcome.pixiv-bookmark.skipped', 'Pixiv 收藏跳过'),
                exists: bt('queue.outcome.pixiv-bookmark.exists', 'Pixiv 已收藏'),
                unknown: bt('queue.outcome.pixiv-bookmark.unknown', 'Pixiv 收藏状态未知')
            }));
        }
        if (data && data.collectionResult) {
            parts.push(actionOutcomePart(data.collectionResult, {
                success: bt('queue.outcome.collection.success', '加入收藏夹成功'),
                failed: bt('queue.outcome.collection.failed', '加入收藏夹失败'),
                skipped: bt('queue.outcome.collection.skipped', '收藏夹加入跳过'),
                exists: bt('queue.outcome.collection.exists', '已在收藏夹中'),
                unknown: bt('queue.outcome.collection.unknown', '收藏夹状态未知')
            }));
        }
        return parts.filter(Boolean);
    }

    function appendPostDownloadOutcome(base, data) {
        const parts = postDownloadOutcomeParts(data);
        if (!parts.length) return base;
        const sep = uiLang() === 'en-US' ? '; ' : '；';
        return base + sep + parts.map(p => p.text).join(sep);
    }

    function buildPostDownloadMessageParts(base, baseTone, data) {
        const sep = uiLang() === 'en-US' ? '; ' : '；';
        const parts = [{text: base, tone: baseTone}].concat(postDownloadOutcomeParts(data));
        return parts.map((part, idx) => ({
            text: part.text + (idx < parts.length - 1 ? sep : ''),
            tone: part.tone
        }));
    }

    function toneColor(tone, fallback) {
        return {
            success: '#28a745',
            error: '#dc3545',
            warning: '#e6a700',
            info: '#007bff'
        }[tone] || fallback || '#666';
    }

    function renderQueueMessageHtml(q, fallbackText) {
        if (Array.isArray(q.lastMessageParts) && q.lastMessageParts.length) {
            return q.lastMessageParts
                .map(part => `<span style="color:${toneColor(part.tone, statusColor(q.status))};font-weight:bold;">${esc(part.text)}</span>`)
                .join('');
        }
        return `<span style="color:${statusColor(q.status)};font-weight:bold;">${esc(fallbackText)}</span>`;
    }

    function mergeUgoiraProgress(existing, incoming) {
        if (!incoming) return existing || null;
        return {...(existing || {}), ...incoming};
    }

    function clampProgressValue(value) {
        const n = Number(value);
        if (!Number.isFinite(n)) return null;
        return Math.max(0, Math.min(100, Math.round(n)));
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

    function miniProgressHtml(label, valueText, progress, color) {
        const pctValue = clampProgressValue(progress);
        const pctText = pctValue === null ? '' : `${pctValue}%`;
        const right = [valueText, pctText].filter(Boolean).join(' · ');
        const width = pctValue === null ? 100 : pctValue;
        const opacity = pctValue === null ? '.28' : '1';
        return `<div class="prog-wrap" style="margin-top:4px;">
        <div class="prog-label"><span>${esc(label)}</span><span>${esc(right)}</span></div>
        <div class="prog-bg"><div class="prog-fill" style="width:${width}%;background:${color};opacity:${opacity};height:4px;"></div></div>
       </div>`;
    }

    function formatImageDownloadProgressHtml(progress, status) {
        if (!progress || ['completed', 'failed', 'skipped'].includes(status)) return '';
        const imageText = progress.imageNumber && progress.totalImages
            ? bt('queue.image-download.index', '第 {current}/{total} 张', {
                current: progress.imageNumber,
                total: progress.totalImages
            })
            : '';
        const bytesText = progress.totalBytes > 0
            ? `${formatBytes(progress.downloadedBytes || 0)} / ${formatBytes(progress.totalBytes)}`
            : formatBytes(progress.downloadedBytes || 0);
        const valueText = [imageText, bytesText].filter(Boolean).join(' · ');
        return miniProgressHtml(
            bt('queue.image-download.label', '图片下载'),
            valueText,
            progress.progress,
            progress.status === 'failed' ? '#dc3545' : '#0ea5e9'
        );
    }

    function formatUgoiraProgressHtml(progress, itemStatus) {
        if (!progress || itemStatus === 'completed' || progress.status === 'completed') return '';
        const phase = String(progress.phase || '');
        const status = String(progress.status || '');
        const parts = [];

        const hasZip = phase === 'zip' || phase === 'extract' || phase === 'ffmpeg'
            || progress.zipDownloadedBytes !== undefined || progress.zipProgress !== undefined;
        if (hasZip) {
            const zipBytes = progress.zipTotalBytes > 0
                ? `${formatBytes(progress.zipDownloadedBytes || 0)} / ${formatBytes(progress.zipTotalBytes)}`
                : formatBytes(progress.zipDownloadedBytes || 0);
            parts.push(miniProgressHtml(
                bt('queue.ugoira.zip', '动图压缩包'),
                zipBytes,
                progress.zipProgress,
                '#0ea5e9'
            ));
        }

        const hasFfmpeg = phase === 'ffmpeg' || progress.ffmpegProgress !== undefined || status === 'completed';
        if (hasFfmpeg) {
            const timeText = progress.ffmpegDurationMs > 0
                ? `${formatDurationMs(progress.ffmpegOutTimeMs || 0)} / ${formatDurationMs(progress.ffmpegDurationMs)}`
                : '';
            parts.push(miniProgressHtml(
                bt('queue.ugoira.ffmpeg', 'ffmpeg 转换'),
                timeText,
                progress.ffmpegProgress,
                status === 'failed' ? '#dc3545' : '#6610f2'
            ));
        }

        if (phase === 'extract') {
            const extracted = progress.totalFrames > 0
                ? bt('queue.ugoira.extracting-count', '正在解压帧 {current}/{total}', {
                    current: progress.extractedFrames || 0,
                    total: progress.totalFrames
                })
                : bt('queue.ugoira.extracting', '正在解压帧');
            parts.push(`<div style="font-size:10px;color:#666;margin-top:4px;">${esc(extracted)}</div>`);
        } else if (status === 'failed') {
            parts.push(`<div style="font-size:10px;color:#dc3545;margin-top:4px;">${esc(bt('queue.ugoira.failed', '动图处理失败'))}</div>`);
        }

        return parts.length ? `<div class="ugoira-progress">${parts.join('')}</div>` : '';
    }

    function formatCurrentCardHtml(item) {
        const currentLabel = esc(bt('label.current', '当前下载:'));
        if (!item) {
            return `<strong>${currentLabel}</strong> ${esc(bt('status.current-idle', '无'))}`;
        }
        const prog = item.totalImages > 0
            ? `<div class="prog-wrap">
        <div class="prog-label"><span>${esc(formatImageProgressText(item.downloadedCount || 0, item.totalImages))}</span><span>${pct(item)}%</span></div>
        <div class="prog-bg"><div class="prog-fill green" style="width:${pct(item)}%"></div></div>
       </div>` : '';
        return `<strong>${currentLabel}</strong> ${esc(item.title)} (ID: ${item.id})${prog}${formatImageDownloadProgressHtml(item.imageProgress, item.status)}${formatUgoiraProgressHtml(item.ugoiraProgress, item.status)}`;
    }

    function buildBookmarkTip(bookmarkCount) {
        if (bookmarkCount === null || bookmarkCount === undefined) {
            return '';
        }
        return bt('search.summary.bookmarks', ' · 收藏 {count}', {count: Number(bookmarkCount).toLocaleString()});
    }

    function buildQueueToggleTip(isInQueue) {
        return isInQueue
            ? bt('queue.action.click-remove', ' · 点击移除')
            : bt('queue.action.click-add', ' · 点击加入队列');
    }

    /* ============================================================
       模式检测 & 存储抽象（solo=服务器，multi=localStorage）
    ============================================================ */
    let appMode = 'multi';   // 'solo' | 'multi'，init() 中确定
    let isAdmin = false;
    let serverState = {};    // solo 模式下的状态内存镜像
    let multiModeLimitPage = 0;  // multi 模式下补页上限（0=不限制），来自 /api/setup/status
    let batchCollectionsRefreshPromise = null;

    function applyCookieHint() {
        const el = document.getElementById('cookie-hint');
        if (!el) return;
        if (appMode === 'solo') {
            el.textContent = bt(
                'cookie.hint.server',
                'Cookie 保存在服务器，所有设备共享同一配置。支持三种格式：Header String（直接复制浏览器请求头）、JSON（对象格式）、Netscape（EditThisCookie 等工具导出）。'
            );
            return;
        }
        if (pageI18n) {
            pageI18n.apply(el);
        }
    }

    async function detectMode() {
        try {
            const res = await fetch('/api/setup/status');
            if (res.ok) {
                const data = await res.json();
                appMode = data.mode === 'solo' ? 'solo' : 'multi';
                multiModeLimitPage = Math.max(0, data.multiModeLimitPage ?? 0);
            }
        } catch {
            appMode = 'multi';
        }
    }

    async function loadServerState() {
        try {
            const res = await fetch(BASE + '/api/batch/state');
            if (res.ok) {
                const data = await res.json();
                serverState = data.state ?? {};
            }
        } catch {
        }
    }

    async function detectAuthState() {
        try {
            const res = await fetch('/api/auth/check', {credentials: 'same-origin'});
            if (!res.ok) {
                isAdmin = false;
                return;
            }
            const data = await res.json();
            isAdmin = !!data.valid;
        } catch {
            isAdmin = false;
        }
    }

    let _saveTimer = null;

    function scheduleServerSave() {
        if (_saveTimer) clearTimeout(_saveTimer);
        _saveTimer = setTimeout(() => {
            fetch(BASE + '/api/batch/state', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({state: serverState}),
                credentials: 'same-origin'
            }).catch(() => {
            });
        }, 400);
    }

    /** 统一存储读取：solo 模式读服务器内存，multi 模式读 localStorage */
    function storeGet(key) {
        if (appMode === 'solo') {
            const v = serverState[key];
            return v != null ? String(v) : null;
        }
        return localStorage.getItem(key);
    }

    function storeSet(key, value) {
        if (appMode === 'solo') {
            serverState[key] = value;
            scheduleServerSave();
        } else localStorage.setItem(key, value);
    }

    function storeRemove(key) {
        if (appMode === 'solo') {
            delete serverState[key];
            scheduleServerSave();
        } else localStorage.removeItem(key);
    }

    async function doLogout() {
        // solo 模式下退出登录同时清除服务器保存的 Cookie；必须在 logout 使 session 失效前持久化
        if (appMode === 'solo') {
            if (_saveTimer) clearTimeout(_saveTimer);
            delete serverState['pixiv_cookie'];
            try {
                await fetch(BASE + '/api/batch/state', {
                    method: 'POST',
                    headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify({state: serverState}),
                    credentials: 'same-origin'
                });
            } catch {
            }
        }
        try {
            await fetch('/api/auth/logout', {method: 'POST', credentials: 'same-origin'});
        } catch {
        }
        window.location.href = '/pixiv-batch.html';
    }

    /* ============================================================
       API
    ============================================================ */
    function getCookieFmt() {
        return storeGet('pixiv_cookie_fmt') || 'header';
    }

    function setCookieFmt(fmt) {
        storeSet('pixiv_cookie_fmt', fmt);
        ['header', 'json', 'netscape'].forEach(f => {
            document.getElementById('fmt-' + f).classList.toggle('active', f === fmt);
        });
        applyCookieDependentUi();
    }

    function parseCookieToHeaderString(raw, fmt) {
        if (!raw) return '';
        try {
            if (fmt === 'json') {
                const obj = JSON.parse(raw);
                return Object.entries(obj).map(([k, v]) => `${k}=${v}`).join('; ');
            }
            if (fmt === 'netscape') {
                return raw.split('\n')
                    .filter(l => l.trim() && !l.trim().startsWith('#'))
                    .map(l => {
                        const p = l.split('\t');
                        return p.length >= 7 ? `${p[5]}=${p[6].trim()}` : null;
                    })
                    .filter(Boolean)
                    .join('; ');
            }
        } catch (e) {
            console.warn('Cookie 解析失败，原样使用:', e.message);
        }
        // header string 或解析失败时原样返回
        return raw;
    }

    function getCookie() {
        const raw = storeGet('pixiv_cookie') || '';
        return parseCookieToHeaderString(raw, getCookieFmt());
    }

    function getCookieInputHeaderString() {
        const input = document.getElementById('cookie-input');
        const raw = input ? input.value.trim() : (storeGet('pixiv_cookie') || '');
        return parseCookieToHeaderString(raw, getCookieFmt());
    }

    /** 当前输入框 Cookie 是否含 PHPSESSID（登录态）。先归一化为 header 串以兼容 JSON / Netscape 格式。 */
    function cookieHasPhpsessid() {
        return /(?:^|;\s*)PHPSESSID=/.test(getCookieInputHeaderString());
    }

    /**
     * 根据是否有含 PHPSESSID 的有效登录 Cookie，启用/禁用依赖登录态才有意义的组件：
     * 下载后自动收藏、内容分级里的 R-18+/R-18/R-18G 选项、计划列表的「授权 Cookie」按钮。
     * 禁用时统一加悬停提示。Cookie 变化（保存/清除/导入）与列表重渲染后都应调用本函数。
     */
    function applyCookieDependentUi() {
        const ok = cookieHasPhpsessid();
        const title = ok ? '' : bt('cookie.requires-phpsessid', '无有效cookie(PHPSESSID)此功能不可用');
        // 1) 下载后自动收藏。注意：「收藏到」是本地画廊收藏夹，不依赖 Pixiv Cookie。
        ['s-bookmark'].forEach(id => {
            const el = document.getElementById(id);
            if (!el) return;
            el.disabled = !ok;
            el.title = title;
            const container = el.closest('.setting-item');
            if (container) container.title = title;
            const label = container?.querySelector('label');
            if (label) label.title = title;
        });
        // 2) 内容分级下拉里的 R18 档位（全部 / 全年龄保持可用）
        const contentSel = document.getElementById('search-content-filter');
        if (contentSel) {
            const r18Values = ['r18plus', 'r18', 'r18g'];
            r18Values.forEach(val => {
                const opt = contentSel.querySelector(`option[value="${val}"]`);
                if (!opt) return;
                opt.disabled = !ok;
                opt.title = title;
            });
            contentSel.title = title;
            const container = contentSel.closest('.search-extra-item');
            if (container) container.title = title;
            const label = container?.querySelector('label');
            if (label) label.title = title;
            if (!ok && r18Values.includes(contentSel.value)) {
                contentSel.value = 'all';
                handleSearchFilterChange();
            }
        }
        // 3) 计划列表里每个任务的「授权 Cookie」按钮（动态渲染，按 class 重扫）。
        //    禁用条件 = 无有效 Cookie 或任务运行 / 排队中（data-busy 由卡片渲染时写入）。
        document.querySelectorAll('.js-authorize-cookie-btn').forEach(btn => {
            const busy = btn.dataset.busy === '1';
            const reason = busy ? bt('schedule.disabled.busy', '任务运行 / 排队中，暂不可操作') : title;
            btn.disabled = !ok || busy;
            btn.title = reason;
            const wrapper = btn.closest('.cookie-dependent-action');
            if (wrapper) wrapper.title = reason;
        });
        // 4) 快捷获取 Tab 的所有动作按钮（基于「我的」数据，必须有 PHPSESSID）
        document.querySelectorAll('.quick-action').forEach(btn => {
            btn.disabled = !ok;
            btn.title = title;
        });
        // 5) 帐号栏的提示文本（无 Cookie 时显示「未检测到登录 Cookie...」）
        updateQuickAccountBar();
    }

    function validateAndParseCookie(raw, fmt) {
        if (!raw.trim()) {
            return {ok: false, error: bt('cookie.error.empty', 'Cookie 不能为空')};
        }

        let headerString;
        try {
            if (fmt === 'json') {
                const obj = JSON.parse(raw);
                if (typeof obj !== 'object' || Array.isArray(obj) || obj === null)
                    throw new Error(bt('cookie.error.invalid-json', '需要 JSON 对象格式 {"key":"value",...}'));
                headerString = Object.entries(obj).map(([k, v]) => `${k}=${v}`).join('; ');
            } else if (fmt === 'netscape') {
                const lines = raw.split('\n')
                    .filter(l => l.trim() && !l.trim().startsWith('#'))
                    .map(l => {
                        const p = l.split('\t');
                        return p.length >= 7 ? `${p[5]}=${p[6].trim()}` : null;
                    })
                    .filter(Boolean);
                if (!lines.length) {
                    throw new Error(bt('cookie.error.invalid-netscape', '未解析到有效的 Cookie 行（需要 7 列 tab 分隔格式）'));
                }
                headerString = lines.join('; ');
            } else {
                headerString = raw.trim();
            }
        } catch (e) {
            return {
                ok: false,
                error: bt('cookie.error.parse-failed', '格式解析失败：{message}', {message: e.message})
            };
        }

        // 校验所有键值对格式是否合法
        const pairs = headerString.split(';').map(s => s.trim()).filter(Boolean);
        const invalid = pairs.filter(p => !/^[^=]+=/.test(p));
        if (invalid.length) {
            return {
                ok: false,
                error: bt(
                    'cookie.error.invalid-pairs',
                    '包含无效键值对：{pairs}',
                    {pairs: invalid.slice(0, 3).map(s => `"${s}"`).join(uiLang() === 'en-US' ? ', ' : '、')}
                )
            };
        }

        // 警告：缺少关键字段
        const warnings = [];
        if (!pairs.some(p => p.startsWith('PHPSESSID='))) {
            warnings.push(bt('cookie.warning.no-phpsessid', '未检测到 PHPSESSID，可能无法访问需要登录的内容'));
        }

        return {ok: true, count: pairs.length, warnings};
    }

    function pixivHeader() {
        const c = getCookie();
        return c ? {'X-Pixiv-Cookie': c} : {};
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

    async function getUserArtworks(userId) {
        const data = await apiGet(`/api/pixiv/user/${userId}/artworks`);
        if (data.error) throw new Error(data.error);
        return data.ids || [];
    }

    async function getUserNovels(userId) {
        const data = await apiGet(`/api/pixiv/user/${userId}/novels`);
        if (data.error) throw new Error(data.error);
        return data.ids || [];
    }

    async function getUserMeta(userId) {
        const data = await apiGet(`/api/pixiv/user/${userId}/meta`);
        if (data.error) throw new Error(data.error);
        return data.name || '';
    }

    async function checkNovelDownloaded(novelId) {
        try {
            const res = await fetch(`${BASE}/api/gallery/novel/${encodeURIComponent(novelId)}`,
                {credentials: 'same-origin'});
            return res.ok;
        } catch {
            return false;
        }
    }

    async function getArtworkMeta(artworkId) {
        const data = await apiGet(`/api/pixiv/artwork/${artworkId}/meta`);
        if (data.error) throw new Error(data.error);
        return data;
    }

    async function getNovelBookmarkCountForSearch(novelId) {
        const data = await apiGet(`/api/pixiv/novel/${encodeURIComponent(novelId)}/bookmark-count`);
        if (data.error) throw new Error(data.error);
        return data;
    }

    async function getArtworkPages(artworkId) {
        const data = await apiGet(`/api/pixiv/artwork/${artworkId}/pages`);
        if (data.error) throw new Error(data.error);
        return data.urls || [];
    }

    async function getUgoiraMeta(artworkId) {
        const data = await apiGet(`/api/pixiv/artwork/${artworkId}/ugoira`);
        if (data.error) throw new Error(data.error);
        return data;
    }

    async function checkDownloaded(artworkId) {
        try {
            const query = state.settings.verifyHistoryFiles ? '?verifyFiles=true' : '';
            const res = await fetch(`${BASE}/api/downloaded/${artworkId}${query}`);
            if (res.status === 200) {
                const data = await res.json();
                if (!data.artworkId) return null;
                return data;
            }
            return null;
        } catch {
            return null;
        }
    }

    // 两阶段恢复：当 verifyFiles=true 的 fallback 路径把磁盘上已有的作品恢复成一条空 title 的裸记录时，
    // 用前端拉到的 Pixiv 元数据补齐缺失字段。后端是幂等的：DB 已有完整记录直接返回原记录。
    async function recoverArtworkMetadata(artworkId, meta) {
        try {
            const res = await fetch(`${BASE}/api/downloaded/${artworkId}/recover-metadata`, {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify(meta)
            });
            if (res.status === 200) {
                return await res.json();
            }
        } catch (e) {
            // best-effort：失败不影响跳过逻辑，至少裸记录仍在
            console.warn('recoverArtworkMetadata failed', artworkId, e);
        }
        return null;
    }

    function getIntervalMs() {
        const {interval, intervalUnit} = state.settings;
        return intervalUnit === 's' ? Math.round(interval * 1000) : Math.round(interval);
    }

    function getImageDelayMs() {
        const {imageDelay, imageDelayUnit} = state.settings;
        return imageDelayUnit === 's' ? Math.round(imageDelay * 1000) : Math.round(imageDelay);
    }

    function toggleImageDelayUnit() {
        const btn = document.getElementById('s-image-delay-unit');
        const input = document.getElementById('s-image-delay');
        const cur = parseFloat(input.value) || 0;
        if (state.settings.imageDelayUnit === 's') {
            state.settings.imageDelayUnit = 'ms';
            input.value = Math.round(cur * 1000);
            btn.textContent = 'ms';
        } else {
            state.settings.imageDelayUnit = 's';
            input.value = +(cur / 1000).toFixed(3);
            btn.textContent = 's';
        }
        state.settings.imageDelay = parseFloat(input.value) || 0;
        saveSettings();
    }

    function toggleIntervalUnit() {
        const btn = document.getElementById('s-interval-unit');
        const input = document.getElementById('s-interval');
        const cur = parseFloat(input.value) || 0;
        if (state.settings.intervalUnit === 's') {
            state.settings.intervalUnit = 'ms';
            input.value = Math.round(cur * 1000);
            btn.textContent = 'ms';
        } else {
            state.settings.intervalUnit = 's';
            input.value = +(cur / 1000).toFixed(3);
            btn.textContent = 's';
        }
        state.settings.interval = parseFloat(input.value) || 0;
        saveSettings();
    }

    function normalizeAuthorId(value) {
        if (value === null || value === undefined || value === '') return null;
        const parsed = Number.parseInt(String(value), 10);
        return Number.isFinite(parsed) && parsed > 0 ? parsed : null;
    }

    function normalizeFileNameTemplate(value) {
        const raw = value === null || value === undefined ? '' : String(value);
        return raw.trim() ? raw : DEFAULT_FILE_NAME_TEMPLATE;
    }

    function sanitizeFileNamePart(value) {
        let cleaned = value === null || value === undefined ? '' : String(value);
        cleaned = cleaned.replace(/[\\/:*?"<>|\x00-\x1F\x7F-\x9F]/g, '_').trim().replace(/[. ]+$/g, '');
        if (WINDOWS_RESERVED_FILE_NAMES.has(cleaned.toUpperCase())) cleaned = '_' + cleaned;
        return cleaned;
    }

    function normalizeBaseName(value, fallback) {
        let cleaned = sanitizeFileNamePart(value);
        if (!cleaned) cleaned = sanitizeFileNamePart(fallback);
        if (!cleaned) cleaned = 'untitled';
        return cleaned.length > 180 ? cleaned.slice(0, 180) : cleaned;
    }

    function appendFileNameSuffix(base, suffix) {
        const maxBase = Math.max(1, 180 - suffix.length);
        const trimmed = base.length > maxBase ? base.slice(0, maxBase) : base;
        return trimmed + suffix;
    }

    function ensureUniqueBaseNames(names) {
        const used = new Set();
        const baseCounts = new Map();
        return names.map((base, page) => {
            const baseKey = base.toLowerCase();
            const duplicate = baseCounts.get(baseKey) || 0;
            let candidate = base;
            if (duplicate > 0 || used.has(baseKey)) {
                let suffixIndex = 1;
                do {
                    const suffix = `_p${page}${suffixIndex > 1 ? '_' + suffixIndex : ''}`;
                    candidate = appendFileNameSuffix(base, suffix);
                    suffixIndex++;
                } while (used.has(candidate.toLowerCase()));
            }
            baseCounts.set(baseKey, duplicate + 1);
            used.add(candidate.toLowerCase());
            return candidate;
        });
    }

    function formatFileNameBase(template, vars, page, count) {
        const normalizedTemplate = normalizeFileNameTemplate(template);
        const xRestrict = Number(vars.xRestrict) || 0;
        const isAi = !!vars.isAi;
        const replacements = {
            artwork_id: String(vars.artworkId || ''),
            artwork_title: sanitizeFileNamePart(vars.title || ''),
            author_id: vars.authorId ? String(vars.authorId) : '',
            author_name: sanitizeFileNamePart(vars.authorName || ''),
            timestamp: String(vars.timestamp || ''),
            page: String(page),
            count: String(count),
            ai: isAi ? 'AI' : '',
            'ai+': isAi ? 'AI' : 'Human',
            R18: xRestrict === 2 ? 'R18G' : (xRestrict === 1 ? 'R18' : ''),
            'R18+': xRestrict === 2 ? 'R18G' : (xRestrict === 1 ? 'R18' : 'SFW')
        };
        const rendered = normalizedTemplate.replace(
            /\{(artwork_id|artwork_title|author_id|author_name|timestamp|page|count|ai\+?|R18\+?)\}/g,
            (_, key) => replacements[key] ?? ''
        );
        return normalizeBaseName(rendered, `${vars.artworkId}_p${page}`);
    }

    function buildDownloadFileNames(template, vars, count) {
        const safeCount = Math.max(1, Number(count) || 1);
        const names = [];
        for (let page = 0; page < safeCount; page++) {
            names.push(formatFileNameBase(template, vars, page, safeCount));
        }
        return ensureUniqueBaseNames(names);
    }

    /**
     * 单批次内的系列元数据缓存：同一 seriesId 在一批下载中只查一次 Pixiv 系列 AJAX，
     * 节省 N 个章节下载时的 N-1 次重复请求。kind: 'illust' | 'novel'。
     * 返回 { caption, coverUrl, tags } —— 调用方只取需要的字段。
     */
    const seriesMetaPromiseCache = new Map();
    function fetchSeriesEnrichmentCached(seriesId, kind) {
        const sid = Number(seriesId);
        if (!Number.isFinite(sid) || sid <= 0) return Promise.resolve(null);
        const key = (kind === 'novel' ? 'novel:' : 'illust:') + sid;
        if (seriesMetaPromiseCache.has(key)) return seriesMetaPromiseCache.get(key);
        const path = kind === 'novel'
            ? `/api/pixiv/novel/series/${sid}?page=1`
            : `/api/pixiv/series/${sid}?page=1`;
        const promise = fetch(BASE + path, {
            credentials: 'same-origin',
            headers: pixivHeader()
        }).then(r => r.ok ? r.json() : null).then(data => {
            const meta = data && data.series ? data.series : null;
            if (!meta) return null;
            return {
                caption: meta.caption || '',
                coverUrl: meta.coverUrl || '',
                tags: Array.isArray(meta.tags) ? meta.tags : []
            };
        }).catch(() => null);
        seriesMetaPromiseCache.set(key, promise);
        return promise;
    }

    async function sendDownload(artworkId, imageUrls, title, isUserDownload, username, authorId, authorName, xRestrict, isAi, ugoiraData, description, tags, seriesInfo, illustType) {
        const delayMs = getImageDelayMs();
        const collectionId = await resolveBatchCollectionIdForDownload();
        const fileNameTemplate = normalizeFileNameTemplate(state.settings.fileNameTemplate);
        const fileNameTimestamp = Date.now();
        const fileNames = buildDownloadFileNames(fileNameTemplate, {
            artworkId,
            title,
            authorId: normalizeAuthorId(authorId),
            authorName,
            xRestrict,
            isAi,
            timestamp: fileNameTimestamp
        }, imageUrls.length);
        const other = {
            userDownload: isUserDownload,
            username: username || '',
            authorId: normalizeAuthorId(authorId),
            authorName: authorName || null,
            xRestrict: Number(xRestrict) || 0,
            isAi: !!isAi,
            delayMs,
            bookmark: !!state.settings.bookmark,
            collectionId,
            description: description || null,
            tags: Array.isArray(tags) && tags.length ? tags : null,
            fileNameTemplate,
            fileNames,
            fileNameTimestamp
        };
        if (seriesInfo && seriesInfo.seriesId) {
            other.seriesId = Number(seriesInfo.seriesId);
            other.seriesOrder = Number(seriesInfo.seriesOrder ?? 0);
            other.seriesTitle = seriesInfo.seriesTitle || null;
            // 系列简介/封面只在本地数据库尚无时由后端落盘，前端这里仅负责把 Pixiv 的 hint 透传过去。
            // 缓存一批查一次，失败/空值不阻塞下载。
            const enrich = seriesInfo.seriesDescription || seriesInfo.seriesCoverUrl
                ? {caption: seriesInfo.seriesDescription, coverUrl: seriesInfo.seriesCoverUrl}
                : await fetchSeriesEnrichmentCached(seriesInfo.seriesId, 'illust');
            if (enrich) {
                if (enrich.caption) other.seriesDescription = enrich.caption;
                if (enrich.coverUrl) other.seriesCoverUrl = enrich.coverUrl;
            }
        }
        if (illustType != null && Number.isFinite(Number(illustType))) {
            other.illustType = Number(illustType);
        }
        if (ugoiraData) {
            other.isUgoira = true;
            other.ugoiraZipUrl = ugoiraData.zipUrl;
            other.ugoiraDelays = ugoiraData.delays;
        }
        const payload = {
            artworkId: parseInt(artworkId),
            imageUrls,
            title,
            referer: 'https://www.pixiv.net/',
            cookie: getCookie(),
            other
        };
        const res = await fetch(`${BASE}/api/download/pixiv`, {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            credentials: 'same-origin',
            body: JSON.stringify(payload)
        });
        const data = await res.json();
        if (res.status === 429 && data.quotaExceeded) {
            if (!quotaExceededHandled) {
                quotaExceededHandled = true;
                handleQuotaExceeded(data);
            }
            const err = new Error('quota_exceeded');
            err.quotaData = data;
            throw err;
        }
        if (!res.ok) throw new Error(data.message || bt('status.backend-failure', '后端返回失败'));
        return data;
    }

    async function getDownloadStatus(artworkId) {
        const res = await fetch(`${BASE}/api/download/status/${artworkId}`);
        return res.json();
    }

    /* ============================================================
       SSE — 共享单连接版：所有作品复用同一条聚合 EventSource，按 artworkId 路由
    ============================================================ */
    function ensureSharedSSE() {
        if (state.sharedSse) return;
        const src = new EventSource(`${BASE}/api/sse/download`);
        src.addEventListener('aggregated-ready', e => {
            state.sharedSseConnectionId = e.data || null;
        });
        src.addEventListener('sse-closing', () => {
            if (state.sharedSse === src) {
                state.sharedSse = null;
                state.sharedSseConnectionId = null;
            }
            try { src.close(); } catch {}
        });
        src.addEventListener('download-status', e => {
            try {
                const data = JSON.parse(e.data);
                const aid = data && data.artworkId !== undefined && data.artworkId !== null
                    ? String(data.artworkId) : null;
                if (!aid) return;
                (state.sseListeners[aid] || []).forEach(fn => fn(data));
            } catch {}
        });
        // EventSource 自动重连，无需手动处理 onerror
        state.sharedSse = src;
    }

    function notifyAggregatedSSEClosed(connectionId) {
        if (!connectionId) return Promise.resolve();
        return fetch(`${BASE}/api/sse/close/aggregated/${encodeURIComponent(connectionId)}`, {
            method: 'POST',
            credentials: 'same-origin',
            keepalive: true
        }).catch(() => {});
    }

    function closeSharedSSE() {
        const src = state.sharedSse;
        const connectionId = state.sharedSseConnectionId;
        state.sharedSse = null;
        state.sharedSseConnectionId = null;
        let closed = false;
        const closeLocal = () => {
            if (closed) return;
            closed = true;
            if (src) {
                try { src.close(); } catch {}
            }
        };
        const fallbackTimer = setTimeout(closeLocal, 1000);
        notifyAggregatedSSEClosed(connectionId).finally(() => {
            clearTimeout(fallbackTimer);
            closeLocal();
        });
    }

    function openSSE(artworkId) {
        const key = String(artworkId);
        state.sseRefs[key] = (state.sseRefs[key] || 0) + 1;
    }

    function closeSSE(artworkId) {
        const key = String(artworkId);
        if (state.sseRefs[key]) {
            state.sseRefs[key] -= 1;
            if (state.sseRefs[key] <= 0) delete state.sseRefs[key];
        }
        delete state.sseListeners[key];
    }

    function closeAllSSE() {
        state.sseRefs = {};
        state.sseListeners = {};
        if (state.sharedSse) {
            closeSharedSSE();
        }
    }

    function addSSEListener(artworkId, fn) {
        const key = String(artworkId);
        if (!state.sseListeners[key]) state.sseListeners[key] = [];
        state.sseListeners[key].push(fn);
    }

    // 精确移除单个监听器（仅删除自己注册的那个回调，不影响同一 artworkId 上的其它监听）。
    function removeSSEListener(artworkId, fn) {
        const key = String(artworkId);
        const arr = state.sseListeners[key];
        if (!arr) return;
        const idx = arr.indexOf(fn);
        if (idx >= 0) arr.splice(idx, 1);
        if (!arr.length) delete state.sseListeners[key];
    }

    function waitForFinalStatusBySSE(artworkId, timeoutMs) {
        return new Promise(resolve => {
            let resolved = false;
            let timer = null;
            let pollTimer = null;

            const finish = (data) => {
                if (resolved) return;
                resolved = true;
                clearTimeout(timer);
                clearInterval(pollTimer);
                resolve(data);
            };

            timer = setTimeout(() => finish(null), timeoutMs);

            // 每5秒轮询一次，防止 SSE 事件丢失导致任务卡死
            pollTimer = setInterval(async () => {
                if (resolved) {
                    clearInterval(pollTimer);
                    return;
                }
                try {
                    const status = await getDownloadStatus(String(artworkId));
                    if (status && (status.completed || status.failed)) finish(status);
                } catch {
                }
            }, 5000);

            addSSEListener(artworkId, data => {
                if (data && (data.completed || data.failed || data.cancelled)) {
                    finish(data);
                } else if (data && data.downloadedCount !== undefined) {
                    const q = state.queue.find(x => x.id === String(artworkId));
                    if (q) {
                        q.downloadedCount = data.downloadedCount;
                        q.ugoiraProgress = mergeUgoiraProgress(q.ugoiraProgress, data.ugoiraProgress);
                        q.imageProgress = data.imageProgress || q.imageProgress || null;
                        renderQueue();
                        setCurrent(q);
                    }
                    clearTimeout(timer);
                    timer = setTimeout(() => finish(null), timeoutMs);
                }
            });
        });
    }

    /* ============================================================
       下载管理器
    ============================================================ */
    async function start() {
        if (state.queue.length === 0) {
            setStatus(bt('status.queue-empty', '队列为空'), 'error');
            return;
        }
        if (!await checkBackend()) {
            uiAlertKey('alert.backend-unavailable', '后端服务不可用，请确认后端已启动');
            return;
        }

        await refreshBatchCollections();

        const {concurrent} = state.settings;
        const intervalMs = getIntervalMs();
        state.queue.forEach(q => {
            if (['idle', 'failed', 'paused'].includes(q.status)) {
                q.status = 'pending';
                q.lastMessageParts = null;
            }
        });
        state.isRunning = true;
        state.isPaused = false;
        state.stopRequested = false;
        state.activeWorkers = 0;
        quotaExceededHandled = false;

        updateStats();
        updateButtonsState();
        saveQueue();
        renderQueue();
        setStatus(
            bt('status.start-download', '开始下载 (并发:{concurrent}, 间隔:{intervalMs}ms)', {concurrent, intervalMs}),
            'info'
        );

        ensureSharedSSE();
        try {
            const workers = [];
            for (let i = 0; i < Math.max(1, concurrent); i++) {
                workers.push(workerLoop(intervalMs));
            }
            await Promise.all(workers);
        } finally {
            closeAllSSE();
        }

        state.isRunning = false;
        saveQueue();
        setStatus(bt('status.batch-finished', '批量下载结束'), 'info');
        updateButtonsState();

        // 多人模式：队列完成后自动打包已下载文件（配额超限时已在 handleQuotaExceeded 中触发打包，不重复）
        if (quotaInfo.enabled) {
            const completed = state.queue.filter(q => q.status === 'completed').length;
            if (completed > 0) {
                autoPackAfterQueue();
            }
        }
    }

    async function workerLoop(intervalMs) { // intervalMs already in ms
        state.activeWorkers++;
        try {
            while (state.isRunning && !state.stopRequested) {
                if (state.isPaused) {
                    await sleep(500);
                    continue;
                }
                const item = getNextPending();
                if (!item) {
                    if (state.queue.every(q =>
                        ['completed', 'failed', 'idle', 'paused', 'skipped'].includes(q.status))) break;
                    await sleep(500);
                    continue;
                }
                try {
                    await processSingle(item);
                } catch (e) {
                    console.error(e);
                } finally {
                    await sleep(intervalMs);
                }
            }
        } finally {
            state.activeWorkers--;
        }
    }

    function getNextPending() {
        const downloadingIds = new Set(
            state.queue.filter(q => q.status === 'downloading').map(q => q.id)
        );
        const idx = state.queue.findIndex(q => q.status === 'pending' && !downloadingIds.has(q.id));
        if (idx === -1) return null;
        state.queue[idx].status = 'downloading';
        state.queue[idx].startTime = new Date().toISOString();
        saveQueue();
        renderQueue();
        return state.queue[idx];
    }

    async function processSingle(item) {
        item.lastMessageParts = null;
        if (item.kind === 'novel') {
            await processNovelItem(item);
            return;
        }
        item.lastMessage = bt('queue.message.checking-history', '正在检查历史记录...');
        renderQueue();

        if (state.settings.skipHistory) {
            const downloaded = await checkDownloaded(item.id);
            if (downloaded) {
                // 若 verifyFiles=true 时是从磁盘恢复出来的裸记录（title 为空），
                // 拉 Pixiv meta 补齐后再跳过，避免画廊里这些恢复出的作品没有标题/作者/简介。
                let recoveredMeta = false;
                if (state.settings.verifyHistoryFiles && !downloaded.title) {
                    item.lastMessage = bt('queue.message.recovering-metadata', '正在补齐已下载作品的元数据...');
                    renderQueue();
                    try {
                        const meta = await getArtworkMeta(item.id);
                        const recovered = await recoverArtworkMetadata(item.id, {
                            title: meta.illustTitle || '',
                            authorId: normalizeAuthorId(meta.authorId ?? meta.userId),
                            authorName: meta.authorName || meta.userName || '',
                            xRestrict: Number(meta.xRestrict ?? meta.xrestrict ?? 0),
                            isAi: meta?.isAi === true || Number(meta?.aiType ?? 0) >= 2,
                            description: meta.description || ''
                        });
                        if (recovered && recovered.title) {
                            recoveredMeta = true;
                            item.title = recovered.title;
                        }
                    } catch (e) {
                        // best-effort：拉 meta 失败不影响跳过
                        console.warn('recover metadata failed', item.id, e);
                    }
                }
                item.status = 'skipped';
                item.lastMessage = recoveredMeta
                    ? bt('queue.message.skipped-history-recovered', '跳过 — 已下载（自动补齐元数据）')
                    : bt('queue.message.skipped-history', '跳过 — 历史记录中已存在');
                item.endTime = new Date().toISOString();
                updateStats();
                saveQueue();
                renderQueue();
                return;
            }
        }

        item.lastMessage = bt('queue.message.fetching-info', '正在获取作品信息...');
        setCurrent(item);
        setStatus(bt('status.fetching-metadata', '获取信息：{id}', {id: item.id}), 'info');
        renderQueue();

        try {
            const meta = await getArtworkMeta(item.id);
            item.title = meta.illustTitle || item.title || bt('queue.artwork-fallback', '作品 {id}', {id: item.id});
            const metaAuthorId = normalizeAuthorId(meta.authorId ?? meta.userId);
            if (metaAuthorId) item.authorId = metaAuthorId;
            if (meta.authorName || meta.userName) item.authorName = meta.authorName || meta.userName;

            const xRestrict = Number(meta.xRestrict ?? meta.xrestrict ?? 0);
            const isAi = meta?.isAi === true || Number(meta?.aiType ?? 0) >= 2;
            const filterSkipReason = evaluateDownloadFilterSkip(meta, 'illust');
            if (filterSkipReason) {
                item.status = 'skipped';
                item.lastMessage = filterSkipReason;
                item.endTime = new Date().toISOString();
                updateStats();
                saveQueue();
                renderQueue();
                return;
            }

            item.xRestrict = xRestrict;
            item.isAi = isAi;
            const isUserMode = item.source === 'user';
            let urls, ugoiraData = null;

            item.lastMessage = bt('queue.message.fetching-images', '正在获取图片地址...');
            renderQueue();

            if (meta.illustType === 2) {
                const ugoira = await getUgoiraMeta(item.id);
                ugoiraData = {zipUrl: ugoira.zipUrl, delays: ugoira.delays};
                urls = [ugoira.zipUrl];
                item.totalImages = 1;
            } else {
                urls = await getArtworkPages(item.id);
                if (!urls.length) throw new Error(bt('queue.message.no-image-url', '未获取到图片 URL'));
                item.totalImages = urls.length;
            }

            item.downloadedCount = 0;
            item.bookmarkResult = null;
            item.collectionResult = null;
            item.ugoiraProgress = null;
            item.imageProgress = null;
            saveQueue();
            renderQueue();

            setStatus(bt('status.downloading-title', '下载中：{title}', {title: item.title}), 'info');
            const fallbackAuthorId = isUserMode ? normalizeAuthorId(state.userId) : null;
            const fallbackAuthorName = isUserMode ? (item.username || state.username || state.userId || '') : '';
            const seriesInfo = (item.seriesId && item.seriesId > 0)
                ? {seriesId: item.seriesId, seriesOrder: item.seriesOrder ?? 0, seriesTitle: item.seriesTitle || null}
                : (meta.seriesId
                    ? {seriesId: meta.seriesId, seriesOrder: meta.seriesOrder ?? 0, seriesTitle: meta.seriesTitle || null}
                    : null);
            const dlData = await sendDownload(
                item.id, urls, item.title,
                isUserMode, item.username || state.username || state.userId,
                item.authorId ?? fallbackAuthorId,
                item.authorName || fallbackAuthorName,
                xRestrict, isAi, ugoiraData,
                meta.description || '',
                Array.isArray(meta.tags) ? meta.tags : [],
                seriesInfo,
                meta.illustType ?? null
            );
            if (dlData && dlData.alreadyDownloaded) {
                item.status = 'skipped';
                item.lastMessage = bt('queue.message.skipped-server-downloaded', '跳过 — 已下载（服务器确认）');
                item.endTime = new Date().toISOString();
                updateStats();
                saveQueue();
                renderQueue();
                setStatus(bt('status.skipped-downloaded-title', '跳过：{title}（已下载）', {title: item.title}), 'info');
                return;
            }
            openSSE(item.id);
            const ssePromise = waitForFinalStatusBySSE(item.id, STATUS_TIMEOUT_MS);
            item.lastMessage = bt('queue.message.waiting-completion', '下载中，等待完成...');
            renderQueue();

            const final = await ssePromise;

            if (final && final.completed) {
                const dCount = final.downloadedCount !== undefined ? final.downloadedCount : item.totalImages;
                item.downloadedCount = dCount;
                item.bookmarkResult = final.bookmarkResult || null;
                item.collectionResult = final.collectionResult || null;
                item.ugoiraProgress = mergeUgoiraProgress(item.ugoiraProgress, final.ugoiraProgress);
                item.imageProgress = final.imageProgress || item.imageProgress || null;
                if (dCount < item.totalImages) {
                    item.status = 'failed';
                    const baseMessage = bt(
                        'queue.message.failed-partial',
                        '失败 — 仅 {downloaded}/{total} 张已下载',
                        {downloaded: dCount, total: item.totalImages}
                    );
                    item.lastMessage = appendPostDownloadOutcome(
                        baseMessage,
                        final
                    );
                    item.lastMessageParts = buildPostDownloadMessageParts(baseMessage, 'error', final);
                    setStatus(bt('status.failed-files-missing-title', '失败：{title} (文件缺失)', {title: item.title}), 'error');
                } else {
                    item.status = 'completed';
                    const baseMessage = bt('queue.message.completed-images', '已完成，共 {count} 张', {count: dCount});
                    item.lastMessage = appendPostDownloadOutcome(
                        baseMessage,
                        final
                    );
                    item.lastMessageParts = buildPostDownloadMessageParts(baseMessage, 'success', final);
                    setStatus(bt('status.completed-title', '完成：{title}', {title: item.title}), 'success');
                    // 刷新配额显示（每完成一个作品计 1）
                    if (quotaInfo.enabled) {
                        quotaInfo.artworksUsed = Math.min(quotaInfo.maxArtworks, quotaInfo.artworksUsed + 1);
                        renderQuotaBar();
                    }
                }
            } else if (final && final.failed) {
                item.ugoiraProgress = mergeUgoiraProgress(item.ugoiraProgress, final.ugoiraProgress);
                item.imageProgress = final.imageProgress || item.imageProgress || null;
                item.status = 'failed';
                item.lastMessage = bt(
                    'queue.message.failed-backend',
                    '失败 — {message}',
                    {message: final.message || bt('status.backend-failure', '后端返回失败')}
                );
                setStatus(bt('status.failed-title', '失败：{title}', {title: item.title}), 'error');
            } else {
                try {
                    const check = await getDownloadStatus(item.id);
                    if (check && check.completed) {
                        const dCount = check.downloadedCount !== undefined ? check.downloadedCount : 0;
                        item.downloadedCount = dCount;
                        item.bookmarkResult = check.bookmarkResult || null;
                        item.collectionResult = check.collectionResult || null;
                        item.ugoiraProgress = mergeUgoiraProgress(item.ugoiraProgress, check.ugoiraProgress);
                        item.imageProgress = check.imageProgress || item.imageProgress || null;
                        if (dCount < item.totalImages) {
                            item.status = 'failed';
                            const baseMessage = bt(
                                'queue.message.failed-files-missing',
                                '失败 — 文件缺失 ({downloaded}/{total})',
                                {downloaded: dCount, total: item.totalImages}
                            );
                            item.lastMessage = appendPostDownloadOutcome(
                                baseMessage,
                                check
                            );
                            item.lastMessageParts = buildPostDownloadMessageParts(baseMessage, 'error', check);
                        } else {
                            item.status = 'completed';
                            const baseMessage = bt(
                                'queue.message.completed-confirmed',
                                '已完成（确认），共 {count} 张',
                                {count: dCount}
                            );
                            item.lastMessage = appendPostDownloadOutcome(
                                baseMessage,
                                check
                            );
                            item.lastMessageParts = buildPostDownloadMessageParts(baseMessage, 'success', check);
                        }
                    } else {
                        item.status = 'failed';
                        item.lastMessage = bt('queue.message.failed-timeout', '失败 — 超时未收到完成状态');
                    }
                } catch {
                    item.status = 'failed';
                    item.lastMessage = bt('queue.message.failed-status-error', '失败 — 状态查询异常');
                }
            }
        } catch (e) {
            if (e.message === 'quota_exceeded') {
                // 已在 handleQuotaExceeded 中处理，item 已标记为失败，不需要重复处理
                item.status = 'failed';
                item.lastMessage = bt('queue.message.failed-quota', '失败 - 达到限额');
            } else {
                item.status = 'failed';
                item.lastMessage = bt('queue.message.failed-backend', '失败 — {message}', {message: e.message});
                setStatus(bt('status.error-item', '错误：{id} — {message}', {id: item.id, message: e.message}), 'error');
            }
        } finally {
            closeSSE(item.id);
            item.endTime = item.endTime || new Date().toISOString();
            updateStats();
            saveQueue();
            renderQueue();
            setCurrent(null);
        }
    }

    function pause() {
        if (!state.isRunning) return;
        state.isPaused = true;
        state.queue.forEach(q => {
            if (q.status === 'pending') q.status = 'paused';
        });
        saveQueue();
        const active = state.queue.filter(q => q.status === 'downloading').length;
        setStatus(
            active > 0
                ? bt('status.pausing-active', '正在暂停... (等待 {count} 个任务完成)', {count: active})
                : bt('status.paused', '已暂停'),
            'warning'
        );
        updateButtonsState();
    }

    function resume() {
        if (!state.isRunning) {
            start();
            return;
        }
        state.isPaused = false;
        state.queue.forEach(q => {
            if (q.status === 'paused') q.status = 'pending';
        });
        saveQueue();
        setStatus(bt('status.resume-download', '继续下载'), 'info');
        updateButtonsState();
    }

    function forceClearBackendQueue() {
        // 强制清除后端队列并终止所有正在进行的下载（多人模式下后端仅终止当前 owner 的任务）。
        // best-effort：后端失败不应阻塞前端清理。
        return fetch(BASE + '/api/download/queue/clear', {
            method: 'POST',
            credentials: 'same-origin'
        }).catch(() => {});
    }

    function stopAndClear() {
        state.stopRequested = true;
        state.isRunning = false;
        state.isPaused = false;
        forceClearBackendQueue();
        // 立即触发所有等待中的 SSE Promise resolve，避免等 5 分钟超时
        Object.keys(state.sseListeners).forEach(id => {
            (state.sseListeners[id] || []).forEach(fn => fn({cancelled: true}));
        });
        closeAllSSE();
        state.queue = [];
        state.stats = {success: 0, failed: 0, active: 0, skipped: 0};
        clearSavedQueue();
        renderQueue();
        updateButtonsState();
        updateStats();
        syncSearchResultsQueueState();
        setStatus(bt('status.queue-cleared', '队列已清除'), 'info');
    }

    /* ============================================================
       队列管理
    ============================================================ */
    function dedupeQueueItems(items) {
        const seen = new Set();
        const uniqueItems = [];
        for (const item of items || []) {
            if (!item || item.id === undefined || item.id === null) continue;
            const id = String(item.id);
            if (seen.has(id)) continue;
            seen.add(id);
            uniqueItems.push({...item, id});
        }
        return uniqueItems;
    }

    function parseSingleImport() {
        const text = document.getElementById('single-import-textarea').value;
        const lines = text.split('\n').map(l => l.trim()).filter(Boolean);
        const illustRegex = /https?:\/\/www\.pixiv\.net\/artworks\/(\d+)/;
        const novelRegex = /https?:\/\/www\.pixiv\.net\/novel\/show\.php\?[^\s|]*?\bid=(\d+)/;
        // 区段头：整行就是 `artwork:` 或 `novel:`，大小写不敏感、支持全/半角冒号；之后直到下一个区段头或结束，裸 ID 按此类型解析。
        const sectionHeaderRegex = /^(artwork|novel)\s*[:：]\s*$/i;
        // 裸 ID（可选 `| title`）。
        const bareIdRegex = /^(\d+)\s*(?:\|\s*(.*))?$/;
        let illustItems = [];
        const novelItems = [];
        let currentKind = 'illust';
        for (const ln of lines) {
            const head = ln.match(sectionHeaderRegex);
            if (head) {
                currentKind = head[1].toLowerCase() === 'novel' ? 'novel' : 'illust';
                continue;
            }
            // 显式 URL 始终按其自身类型解析，无视所在区段。
            const n = ln.match(novelRegex);
            if (n) {
                const novelId = n[1];
                const titleRaw = (ln.split('|')[1] || '').trim();
                novelItems.push({
                    id: 'n' + novelId,
                    novelId,
                    kind: 'novel',
                    title: titleRaw || bt('queue.novel-fallback', '小说 {id}', {id: novelId})
                });
                continue;
            }
            const m = ln.match(illustRegex);
            if (m) {
                const id = m[1];
                const titleRaw = (ln.split('|')[1] || '').trim();
                illustItems.push({id, title: titleRaw || bt('queue.artwork-fallback', '作品 {id}', {id})});
                continue;
            }
            // 裸 ID / `id | title`：默认按当前区段（无区段头时为插画）解析。
            const bare = ln.match(bareIdRegex);
            if (bare) {
                const id = bare[1];
                const titleRaw = (bare[2] || '').trim();
                if (currentKind === 'novel') {
                    novelItems.push({
                        id: 'n' + id,
                        novelId: id,
                        kind: 'novel',
                        title: titleRaw || bt('queue.novel-fallback', '小说 {id}', {id})
                    });
                } else {
                    illustItems.push({id, title: titleRaw || bt('queue.artwork-fallback', '作品 {id}', {id})});
                }
            }
        }
        illustItems = dedupeQueueItems(illustItems);
        const dedupedNovelItems = dedupeQueueItems(novelItems);
        if (!illustItems.length && !dedupedNovelItems.length) {
            setStatus(bt('status.single-import-none', '未解析到任何单作品链接'), 'error');
            return;
        }
        const addedIllusts = illustItems.length
            ? addItemsToQueue(illustItems.map(x => x.id), illustItems, SINGLE_IMPORT_MODE, '') : 0;
        const addedNovels = dedupedNovelItems.length
            ? addItemsToQueue(dedupedNovelItems.map(x => x.id), dedupedNovelItems, SINGLE_IMPORT_NOVEL_SOURCE, '') : 0;
        const total = illustItems.length + dedupedNovelItems.length;
        const added = addedIllusts + addedNovels;
        setStatus(
            bt('status.parsed-summary', '解析完成：共 {total} 个，新增 {added} 个',
                {total, added}),
            'success'
        );
    }

    /* ============================================================
       小说下载通道：作为 kind=='novel' 的队列项参与统一调度
       —— ID 命名空间用 'n' 前缀避免与插画 ID 冲突
    ============================================================ */
    async function processNovelItem(item) {
        item.lastMessage = bt('queue.message.fetching-info', '正在获取作品信息...');
        setCurrent(item);
        renderQueue();
        const cookie = getCookie();
        const headers = {};
        if (cookie) headers['X-Pixiv-Cookie'] = cookie;
        try {
            const novelId = item.novelId || String(item.id).replace(/^n/, '');

            if (state.settings.skipHistory) {
                const downloaded = await checkNovelDownloaded(novelId);
                if (downloaded) {
                    item.status = 'skipped';
                    item.lastMessage = bt('queue.message.skipped-history', '跳过 — 历史记录中已存在');
                    item.endTime = new Date().toISOString();
                    updateStats();
                    saveQueue();
                    renderQueue();
                    return;
                }
            }

            let _lastTextRender = 0;
            const metaRes = await fetchJsonWithProgress(
                `${BASE}/api/pixiv/novel/${encodeURIComponent(novelId)}/meta`,
                {headers},
                (done, total) => {
                    item.novelText = {done, total};
                    const now = Date.now();
                    if (now - _lastTextRender > 120) {
                        _lastTextRender = now;
                        renderQueue();
                    }
                });
            if (!metaRes.ok) {
                const errData = await metaRes.json().catch(() => ({}));
                throw new Error(errData.error || ('meta HTTP ' + metaRes.status));
            }
            const meta = await metaRes.json();
            item.novelText = null;
            renderQueue();

            const filterSkipReason = evaluateDownloadFilterSkip(meta, 'novel');
            if (filterSkipReason) {
                item.status = 'skipped';
                item.lastMessage = filterSkipReason;
                item.endTime = new Date().toISOString();
                updateStats();
                saveQueue();
                renderQueue();
                return;
            }

            item.title = meta.title || item.title;
            if (meta.authorId) item.authorId = meta.authorId;
            if (meta.authorName) item.authorName = meta.authorName;
            item.xRestrict = Number(meta.xRestrict || 0);
            item.isAi = !!meta.isAi;
            item.totalImages = 1;
            item.downloadedCount = 0;
            saveQueue();
            renderQueue();

            const fmt = (state.settings.novelFormat || 'txt').toLowerCase();
            const seriesInfo = (item.seriesId && item.seriesId > 0) ? {
                seriesId: Number(item.seriesId),
                seriesOrder: item.seriesOrder,
                seriesTitle: item.seriesTitle
            } : (meta.seriesId ? {
                seriesId: meta.seriesId,
                seriesOrder: meta.seriesOrder,
                seriesTitle: meta.seriesTitle
            } : null);
            // 系列简介/封面/tags：一批共享一次查询，best-effort；失败则不附加。
            const seriesEnrichment = seriesInfo
                ? await fetchSeriesEnrichmentCached(seriesInfo.seriesId, 'novel')
                : null;
            const collectionId = await resolveBatchCollectionIdForDownload();
            const body = {
                novelId: Number(novelId),
                title: meta.title,
                cookie: cookie || null,
                content: meta.content,
                other: {
                    authorId: meta.authorId,
                    authorName: meta.authorName,
                    xRestrict: meta.xRestrict,
                    ai: meta.isAi,
                    original: meta.isOriginal,
                    language: meta.language,
                    wordCount: meta.wordCount,
                    textLength: meta.textLength,
                    readingTimeSeconds: meta.readingTimeSeconds ?? item.readingTimeSeconds ?? null,
                    pageCount: meta.pageCount,
                    description: meta.description,
                    tags: Array.isArray(meta.tags) && meta.tags.length ? meta.tags : (item.tags || []),
                    seriesId: seriesInfo ? seriesInfo.seriesId : null,
                    seriesOrder: seriesInfo ? seriesInfo.seriesOrder : null,
                    seriesTitle: seriesInfo ? seriesInfo.seriesTitle : null,
                    seriesDescription: seriesEnrichment && seriesEnrichment.caption ? seriesEnrichment.caption : null,
                    seriesCoverUrl: seriesEnrichment && seriesEnrichment.coverUrl ? seriesEnrichment.coverUrl : null,
                    seriesTags: seriesEnrichment && seriesEnrichment.tags && seriesEnrichment.tags.length
                            ? seriesEnrichment.tags : null,
                    fileNameTemplate: state.settings.fileNameTemplate,
                    bookmark: !!state.settings.bookmark,
                    collectionId,
                    format: fmt,
                    uploadTimestamp: meta.uploadTimestamp || item.uploadTimestamp || null,
                    coverUrl: meta.coverUrl || item.coverUrl || '',
                    embeddedImages: meta.textEmbeddedImages || {}
                }
            };
            item.lastMessage = bt('queue.message.waiting-completion', '下载中，等待完成...');
            renderQueue();
            const dlRes = await fetch(`${BASE}/api/download/pixiv/novel`, {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                credentials: 'same-origin',
                body: JSON.stringify(body)
            });
            const dlData = await dlRes.json().catch(() => ({}));
            if (dlRes.status === 429 && dlData.quotaExceeded) {
                if (!quotaExceededHandled) {
                    quotaExceededHandled = true;
                    handleQuotaExceeded(dlData);
                }
                throw new Error('quota_exceeded');
            }
            if (dlRes.status === 200 && dlData.alreadyDownloaded) {
                item.status = 'skipped';
                item.lastMessage = bt('queue.message.skipped-server-downloaded', '跳过 — 已下载（服务器确认）');
                item.endTime = new Date().toISOString();
                updateStats();
                saveQueue();
                renderQueue();
                return;
            }
            if (!dlRes.ok) throw new Error(dlData.message || ('HTTP ' + dlRes.status));

            // 轮询小说下载状态
            const start = Date.now();
            while (Date.now() - start < STATUS_TIMEOUT_MS) {
                await sleep(800);
                const sRes = await fetch(`${BASE}/api/download/novel/status/${encodeURIComponent(novelId)}`);
                if (!sRes.ok) continue;
                const status = await sRes.json();
                if (status.completed) {
                    if (status.failed) {
                        item.status = 'failed';
                        item.lastMessage = bt('queue.message.failed', '失败 — {message}',
                            {message: status.message || ''});
                    } else {
                        item.status = 'completed';
                        item.downloadedCount = 1;
                        item.bookmarkResult = status.bookmarkResult || null;
                        item.collectionResult = status.collectionResult || null;
                        item.lastMessage = bt('queue.message.completed', '完成');
                    }
                    item.endTime = new Date().toISOString();
                    updateStats();
                    saveQueue();
                    renderQueue();
                    if (item.status === 'completed' && item.mergeAfterSeriesId) {
                        await maybeTriggerSeriesMerge(item.mergeAfterSeriesId);
                    }
                    return;
                }
                if (status.stage) {
                    applyNovelStage(item, status);
                    renderQueue();
                }
            }
            item.status = 'failed';
            item.lastMessage = bt('queue.message.timeout', '超时');
            item.endTime = new Date().toISOString();
            updateStats();
            saveQueue();
            renderQueue();
        } catch (e) {
            item.status = 'failed';
            item.lastMessage = bt('queue.message.failed', '失败 — {message}', {message: e.message || String(e)});
            item.endTime = new Date().toISOString();
            updateStats();
            saveQueue();
            renderQueue();
            throw e;
        }
    }

    // 当系列内最后一个待合并章节完成后，触发合订
    const _novelMergeFiredSeries = new Set();
    async function readMergeResponse(res) {
        try {
            return await res.json();
        } catch {
            return null;
        }
    }

    async function maybeTriggerSeriesMerge(seriesId) {
        if (!seriesId) return;
        const remaining = state.queue.filter(q => q.kind === 'novel'
            && q.mergeAfterSeriesId === seriesId
            && !['completed', 'failed', 'skipped'].includes(q.status));
        if (remaining.length > 0) return;
        if (_novelMergeFiredSeries.has(seriesId)) return;
        _novelMergeFiredSeries.add(seriesId);
        try {
            // 合订本格式独立于单章下载格式（novelFormat），默认推荐 EPUB
            const mfmt = (state.settings.mergeNovelFormat || 'epub').toLowerCase();
            const res = await fetch(`${BASE}/api/gallery/novel/series/${encodeURIComponent(seriesId)}/merge?format=${encodeURIComponent(mfmt)}`, {
                method: 'POST', credentials: 'same-origin'
            });
            const data = await readMergeResponse(res);
            if (res.status === 401) {
                _novelMergeFiredSeries.delete(seriesId);
                isAdmin = false;
                updateAuthButtons();
                updateAdminPackButton();
                setStatus(bt('status.login-expired', '登录状态已失效，请重新登录'), 'error');
                return;
            }
            if (!res.ok || !data || data.success !== true) {
                const message = data && data.message ? data.message : `HTTP ${res.status}`;
                throw new Error(message);
            }
            setStatus(bt('status.novel-series-merged', '小说系列合订本已生成（系列 {id}）', {id: seriesId}), 'success');
        } catch (e) {
            console.warn('merge failed', seriesId, e);
            _novelMergeFiredSeries.delete(seriesId);
            setStatus(bt('status.novel-series-merge-failed',
                '小说系列合订本生成失败（系列 {id}）：{message}',
                {id: seriesId, message: e.message || String(e)}), 'error');
        }
    }

    function addItemsToQueue(idList, metaList, source, username, defaultAuthorId, defaultAuthorName) {
        const existing = new Set(state.queue.map(q => q.id));
        let added = 0;
        const meta = metaList || [];
        for (let i = 0; i < idList.length; i++) {
            const id = String(idList[i]);
            if (existing.has(id)) continue;
            const m = meta[i] || {};
            const authorId = normalizeAuthorId(m.authorId ?? defaultAuthorId);
            const authorName = m.authorName || defaultAuthorName || '';
            state.queue.push({
                id,
                kind: m.kind || 'illust',
                novelId: m.novelId || null,
                mergeAfterSeriesId: m.mergeAfterSeriesId || null,
                title: m.title || bt('queue.artwork-fallback', '作品 {id}', {id}),
                status: state.isRunning ? 'pending' : 'idle',
                source: normalizeImportMode(source || SINGLE_IMPORT_MODE),
                username: username || '',
                authorId,
                authorName,
                isAi: typeof m.isAi === 'boolean' ? m.isAi : null,
                xRestrict: typeof m.xRestrict === 'number' ? m.xRestrict : null,
                tags: Array.isArray(m.tags) ? m.tags : null,
                readingTimeSeconds: m.readingTimeSeconds != null ? Number(m.readingTimeSeconds) : null,
                coverUrl: m.coverUrl || null,
                uploadTimestamp: m.uploadTimestamp != null ? Number(m.uploadTimestamp) : null,
                seriesId: m.seriesId ? Number(m.seriesId) : null,
                seriesOrder: m.seriesOrder != null ? Number(m.seriesOrder) : null,
                seriesTitle: m.seriesTitle || null,
                totalImages: 0,
                downloadedCount: 0,
                startTime: null,
                endTime: null,
                lastMessage: '',
                lastMessageParts: null,
                bookmarkResult: null,
                collectionResult: null,
                ugoiraProgress: null,
                imageProgress: null
            });
            existing.add(id);
            added++;
        }
        updateStats();
        saveQueue();
        renderQueue();
        // 下载进行中但 workers 已全部退出时，重启 workers 处理新加入的任务
        if (state.isRunning && added > 0 && state.activeWorkers === 0) {
            const concurrent = Math.max(1, state.settings.concurrent);
            const intervalMs = getIntervalMs();
            ensureSharedSSE();
            const workers = [];
            for (let i = 0; i < concurrent; i++) workers.push(workerLoop(intervalMs));
            Promise.all(workers).finally(() => {
                closeAllSSE();
            }).then(() => {
                state.isRunning = false;
                saveQueue();
                setStatus(bt('status.batch-finished', '批量下载结束'), 'info');
                updateButtonsState();
                if (quotaInfo.enabled) {
                    const completed = state.queue.filter(q => q.status === 'completed').length;
                    if (completed > 0) autoPackAfterQueue();
                }
            });
        }
        syncSearchResultsQueueState();
        syncSeriesResultsQueueState();
        syncUserResultsQueueState();
        return added;
    }

    /* ============================================================
       按钮处理
    ============================================================ */
    async function handleStart() {
        syncSettings();
        start();
    }

    function handlePause() {
        if (state.isPaused) resume(); else pause();
    }

    function handleRetry() {
        const failed = state.queue.filter(q => q.status === 'failed');
        if (!failed.length) {
            uiAlertKey('alert.no-failed', '当前没有失败的作品');
            return;
        }
        failed.forEach(q => {
            q.status = 'pending';
            q.lastMessage = '';
            q.startTime = null;
            q.endTime = null;
        });
        saveQueue();
        renderQueue();
        syncSettings();
        start();
    }

    function handleExport() {
        if (!state.queue.length) {
            uiAlertKey('alert.queue-empty', '队列为空');
            return;
        }
        const lines = state.queue.map(q =>
            `https://www.pixiv.net/artworks/${q.id} | ${q.title}`);
        downloadTxt(lines.join('\n'), `pixiv_all_list_${Date.now()}.txt`);
        setStatus(bt('status.exported-all', '已导出 {count} 个作品', {count: lines.length}), 'success');
    }

    function handleExportFailed() {
        const items = state.queue.filter(q => q.status !== 'completed');
        if (!items.length) {
            uiAlertKey('alert.no-undownloaded', '没有未下载的作品');
            return;
        }
        const lines = items.map(q =>
            `https://www.pixiv.net/artworks/${q.id} | ${q.title}`);
        downloadTxt(lines.join('\n'), `pixiv_undownloaded_list_${Date.now()}.txt`);
        setStatus(
            bt('status.exported-undownloaded', '已导出 {count} 个未下载作品', {count: lines.length}),
            'success'
        );
    }

    function handleClear() {
        if (!uiConfirmKey('dialog.confirm-clear-queue', '确认清除队列？')) return;
        stopAndClear();
    }

    function parseSingleImportFresh() {
        if (!uiConfirmKey('dialog.confirm-reparse', '确认清除当前队列并重新解析？')) return;
        stopAndClear();
        parseSingleImport();
    }

    /* ============================================================
       User 模式预览（对齐 Search / 系列：先渲染预览网格 + 分页，
       再由「将此页加入队列」「全部加入队列」入队；附加筛选实时过滤当前页）
    ============================================================ */
    const USER_PAGE_SIZE = 30;

    let userState = {
        kind: 'illust',
        userId: '',
        username: '',
        allIds: [],
        currentPage: 1,
        totalPages: 1,
        rawItems: [],   // 当前页未过滤的卡片
        items: [],      // 当前页经附加筛选后的卡片（渲染 / 「加入此页」据此）
        cardCache: new Map(), // kind+id -> 卡片元数据，翻页 / 改筛选时复用避免重复请求
        filterSummary: {rawCount: 0, filteredCount: 0, bookmarkMetaMissing: 0, bookmarkFilterActive: false},
        renderToken: 0,
        activeBlobUrls: [],
        filterSeq: 0
    };

    function userCardCacheKey(id) {
        return (userState.kind === 'novel' ? 'n:' : 'i:') + String(id);
    }

    function cleanupUserBlobUrls() {
        userState.activeBlobUrls.forEach(u => {
            try { URL.revokeObjectURL(u); } catch {}
        });
        userState.activeBlobUrls = [];
    }

    function resetUserState(kind = 'illust') {
        cleanupUserBlobUrls();
        userState.kind = kind === 'novel' ? 'novel' : 'illust';
        userState.allIds = [];
        userState.currentPage = 1;
        userState.totalPages = 1;
        userState.rawItems = [];
        userState.items = [];
        userState.cardCache = new Map();
        userState.filterSummary = {rawCount: 0, filteredCount: 0, bookmarkMetaMissing: 0, bookmarkFilterActive: false};
        userState.renderToken += 1;
        userState.filterSeq += 1;
        updateUserQueueButtons();
        renderUserPagination();
    }

    function setUserLoading(message) {
        const area = document.getElementById('user-results-area');
        if (area) area.innerHTML = `<div class="search-spinner"><span class="search-spinner-icon"></span>${esc(message)}</div>`;
        updateUserQueueButtons(true);
    }

    function clearUserPreview() {
        cleanupUserBlobUrls();
        userState.allIds = [];
        userState.rawItems = [];
        userState.items = [];
        userState.currentPage = 1;
        userState.totalPages = 1;
        userState.renderToken += 1;
        userState.filterSeq += 1;
        const area = document.getElementById('user-results-area');
        if (area) {
            area.innerHTML = `<div style="text-align:center;color:#aaa;padding:24px 0;font-size:13px;">${esc(bt('status.user-empty', '输入画师 ID 后点击「解析并预览」'))}</div>`;
        }
        renderUserPagination();
        updateUserQueueButtons();
    }

    // 接受纯数字 ID 或形如 https://www.pixiv.net/users/{id}[/...] 的画师主页 URL（含语言前缀变体）。
    function parseUserIdInput(raw) {
        const s = (raw || '').trim();
        if (!s) return '';
        if (/^\d+$/.test(s)) return s;
        const m = s.match(/\/users\/(\d+)/);
        return m ? m[1] : '';
    }

    async function loadUserPreview() {
        const input = document.getElementById('user-id-input');
        const userId = parseUserIdInput(input.value);
        if (!userId) {
            uiAlertKey('alert.invalid-user-id', '请输入有效的用户 ID 或画师主页链接');
            return;
        }
        if (input.value.trim() !== userId) input.value = userId;
        const kind = state.settings.userKind === 'novel' ? 'novel' : 'illust';
        resetUserState(kind);
        userState.userId = userId;
        state.userId = userId;
        document.getElementById('user-info-display').textContent = bt('status.fetching-user-info', '正在获取用户信息...');
        setUserLoading(bt('status.fetching-artwork-list', '正在获取作品列表...'));
        try {
            let name = null;
            try { name = await getUserMeta(userId); } catch { name = null; }
            userState.username = name || userId;
            state.username = userState.username;
            document.getElementById('user-info-display').textContent = name
                ? bt('status.user-display', '用户：{name}（ID: {id}）', {name: userState.username, id: userId})
                : bt('status.user-display-fetch-failed', 'ID: {id}（获取用户名失败）', {id: userId});

            const ids = kind === 'novel' ? await getUserNovels(userId) : await getUserArtworks(userId);
            userState.allIds = Array.isArray(ids) ? ids.map(String) : [];
            userState.totalPages = Math.max(1, Math.ceil(userState.allIds.length / USER_PAGE_SIZE));
            if (!userState.allIds.length) {
                setStatus(bt('status.user-no-artworks', '该用户暂无作品'), 'warning');
                const area = document.getElementById('user-results-area');
                if (area) area.innerHTML = `<div style="text-align:center;color:#aaa;padding:24px 0;font-size:13px;">${esc(bt('status.user-no-artworks', '该用户暂无作品'))}</div>`;
                renderUserPagination();
                updateUserQueueButtons();
                return;
            }
            await loadUserPreviewPage(1);
        } catch (e) {
            const area = document.getElementById('user-results-area');
            if (area) area.innerHTML = `<div style="color:#dc3545;text-align:center;padding:24px 0;">${esc(bt('status.fetch-failed', '获取作品列表失败：{message}', {message: e.message}))}</div>`;
            setStatus(bt('status.fetch-failed', '获取作品列表失败：{message}', {message: e.message}), 'error');
            updateUserQueueButtons();
        }
    }

    // 批量获取一段 ID 的卡片元数据（命中缓存的不再请求），按请求顺序返回（跳过无卡片的已删除作品）。
    async function ensureUserCards(ids) {
        const missing = ids.filter(id => !userState.cardCache.has(userCardCacheKey(id)));
        if (missing.length) {
            const endpoint = userState.kind === 'novel'
                ? `/api/pixiv/user/${encodeURIComponent(userState.userId)}/novel-cards`
                : `/api/pixiv/user/${encodeURIComponent(userState.userId)}/illust-cards`;
            const params = new URLSearchParams();
            missing.forEach(id => params.append('ids', id));
            const res = await fetch(`${BASE}${endpoint}?${params}`, {headers: pixivHeader()});
            if (!res.ok) {
                const d = await res.json().catch(() => ({}));
                throw new Error(d.error || `HTTP ${res.status}`);
            }
            const data = await res.json();
            (data.items || []).forEach(it => userState.cardCache.set(userCardCacheKey(String(it.id)), it));
        }
        return ids.map(id => userState.cardCache.get(userCardCacheKey(id))).filter(Boolean);
    }

    async function loadUserPreviewPage(page) {
        if (!userState.allIds.length) return;
        let p = Number(page);
        if (!Number.isFinite(p) || p < 1) p = 1;
        if (p > userState.totalPages) p = userState.totalPages;
        userState.currentPage = p;
        cleanupUserBlobUrls();
        const base = (p - 1) * USER_PAGE_SIZE;
        const slice = userState.allIds.slice(base, base + USER_PAGE_SIZE);
        setUserLoading(bt('status.series-page-loading', '正在加载第 {page} 页...', {page: p}));
        try {
            const cards = await ensureUserCards(slice);
            userState.rawItems = cards;
            await applyUserFilters({});
            renderUserPagination();
            updateUserQueueButtons();
            setStatus(bt('status.user-preview-loaded', '画师预览已加载：{name}（第 {page} / {total} 页）', {
                name: userState.username,
                page: userState.currentPage,
                total: userState.totalPages
            }), 'success');
        } catch (e) {
            const area = document.getElementById('user-results-area');
            if (area) area.innerHTML = `<div style="color:#dc3545;text-align:center;padding:24px 0;">${esc(bt('status.fetch-failed', '获取作品列表失败：{message}', {message: e.message}))}</div>`;
            setStatus(bt('status.fetch-failed', '获取作品列表失败：{message}', {message: e.message}), 'error');
            updateUserQueueButtons();
        }
    }

    async function applyUserFilters(options = {}) {
        const filters = normalizeSearchFilters(options.filters || getSearchFiltersFromUI());
        searchState.currentFilters = filters;
        saveSearchFilterPrefs(filters);
        const kind = userState.kind;
        const seq = ++userState.filterSeq;
        const isStale = () => seq !== userState.filterSeq;

        const bookmarkActive = hasBookmarkFilter(filters);
        const needsBookmarkMeta = bookmarkActive && userState.rawItems.some(item => {
            if (getInlineSearchBookmarkCount(item) !== null) return false;
            const cached = getCachedSearchMeta(item.id, kind);
            return !cached || !cached.bookmarkResolved;
        });
        if (bookmarkActive && needsBookmarkMeta && userState.rawItems.length) {
            const area = document.getElementById('user-results-area');
            if (area) area.innerHTML = `<div class="search-spinner"><span class="search-spinner-icon"></span>${esc(bt('status.search-reading-bookmarks', '读取当前页收藏数中...'))}</div>`;
            updateUserQueueButtons(true);
        }

        const result = await computeFilteredItems(userState.rawItems, filters, kind, isStale);
        if (!result) return null;
        userState.items = result.filtered;
        userState.filterSummary = result.stats;
        renderUserResults();
        updateUserQueueButtons();

        if (options.setStatus) {
            const parts = [bt('search.summary.current-page', '当前页 {count} 个', {count: result.stats.rawCount})];
            if (hasExtraSearchFilter(filters)) {
                parts.push(bt('search.summary.extra-filtered', '附加筛选后 {count} 个', {count: result.stats.filteredCount}));
                if (result.stats.bookmarkMetaMissing > 0) {
                    parts.push(bt('search.summary.bookmark-missing', '{count} 个收藏数不可用已排除', {count: result.stats.bookmarkMetaMissing}));
                }
            } else {
                parts.push(bt('status.search-no-extra-filters', '未启用附加筛选'));
            }
            setStatus(bt('status.search-filters-applied', '已应用筛选：') + (uiLang() === 'en-US' ? ' ' : '') + summaryJoin(parts), 'success');
        }
        return result.stats;
    }

    function renderUserResults() {
        const area = document.getElementById('user-results-area');
        if (!area) return;
        const renderToken = ++userState.renderToken;
        if (!userState.rawItems.length) {
            area.innerHTML = `<div style="color:#aaa;text-align:center;padding:24px 0;">${esc(bt('status.user-no-artworks', '该用户暂无作品'))}</div>`;
            return;
        }
        const summary = [
            bt('series.meta.total', '共 {count} 个作品', {count: userState.allIds.length.toLocaleString()}),
            bt('search.summary.current-page-index', '当前第 {page} 页', {page: userState.currentPage}),
            bt('search.summary.current-page', '当前页 {count} 个', {count: userState.rawItems.length})
        ];
        if (hasExtraSearchFilter()) {
            summary.push(bt('search.summary.extra-filtered', '附加筛选后 {count} 个', {count: userState.items.length}));
            if (userState.filterSummary.bookmarkMetaMissing > 0) {
                summary.push(bt('search.summary.bookmark-missing', '{count} 个收藏数不可用已排除', {count: userState.filterSummary.bookmarkMetaMissing}));
            }
        }
        const summaryHtml = `<div style="font-size:12px;color:#888;margin-bottom:10px;">${summary.map(s => `<span>${esc(s)}</span>`).join(summarySeparator())}</div>`;
        if (!userState.items.length) {
            area.innerHTML = summaryHtml + `<div style="color:#aaa;text-align:center;padding:24px 0;">${esc(bt('status.search-no-filtered-results', '附加筛选后无结果'))}</div>`;
            return;
        }
        const inQueue = new Set(state.queue.map(q => q.id));
        if (userState.kind === 'novel') {
            const cards = userState.items.map((item, idx) => {
                const xr = Number(item.xRestrict ?? 0);
                const isAi = Number(item.aiType ?? 0) >= 2;
                const wc = Number(item.wordCount ?? item.textLength ?? 0);
                const bookmarkCount = getSearchBookmarkCount(item, 'novel');
                const queueId = 'n' + String(item.id);
                const inQueueClass = inQueue.has(queueId) ? ' in-queue' : '';
                const meta = [];
                if (xr === 1) meta.push('<span class="nsc-r18">R-18</span>');
                else if (xr === 2) meta.push('<span class="nsc-r18g">R-18G</span>');
                if (isAi) meta.push('<span class="nsc-ai">AI</span>');
                if (item.isOriginal) meta.push(`<span class="nsc-original">${esc(bt('novel:batch.search.original', '原创'))}</span>`);
                if (wc > 0) meta.push(`<span>${esc(bt('novel:batch.search.summary.novel-words', '{count} 字', {count: wc.toLocaleString()}))}</span>`);
                if (bookmarkCount !== null) meta.push(`<span>${esc(bt('search.summary.bookmark-badge', '收藏 {count}', {count: bookmarkCount.toLocaleString()}))}</span>`);
                const fallbackTitle = bt('queue.novel-fallback', '小说 {id}', {id: item.id});
                const fallbackAuthor = userState.username || bt('novel:status.unknown-author', '未知');
                const bookmarkTip = buildBookmarkTip(bookmarkCount);
                const queueTip = buildQueueToggleTip(inQueue.has(queueId));
                const cardTitle = `${item.title || fallbackTitle} (${item.userName || fallbackAuthor})${bookmarkTip}${queueTip}`;
                return `<div class="novel-search-card${inQueueClass}" data-user-novel-idx="${idx}" id="user-novel-card-${idx}" title="${esc(cardTitle)}">
        <div class="nsc-title">${esc(item.title || fallbackTitle)}</div>
        <div class="nsc-author">${esc(item.userName || fallbackAuthor)}</div>
        <div class="nsc-meta">${meta.join('')}</div>
        <span class="nsc-in-queue-mark">✓</span>
      </div>`;
            }).join('');
            area.innerHTML = summaryHtml + `<div class="novel-search-grid">${cards}</div>`;
            area.querySelectorAll('.novel-search-card').forEach(card => {
                card.addEventListener('click', () => addUserItemToQueue(Number(card.dataset.userNovelIdx)));
            });
            return;
        }
        area.innerHTML = summaryHtml + `<div class="search-grid">
      ${userState.items.map((item, idx) => {
            const xr = Number(item.xRestrict ?? 0);
            const illustType = Number(item.illustType ?? 0);
            const isAi = Number(item.aiType ?? 0) >= 2;
            const r18Badge = xr === 2
                ? '<span class="thumb-badge" style="background:#b91c1c;">R-18G</span>'
                : xr === 1 ? '<span class="thumb-badge">R-18</span>' : '';
            const aiBadge = isAi ? '<span class="thumb-badge thumb-badge-ai">AI</span>' : '';
            const typeBadge = illustType === 2
                ? `<span class="thumb-badge" style="background:#0ea5e9;">${esc(bt('search.type.ugoira', '动图'))}</span>`
                : illustType === 1 ? `<span class="thumb-badge" style="background:#f59e0b;">${esc(bt('search.type.manga', '漫画'))}</span>` : '';
            const pagesLabel = item.pageCount > 1 ? `<span class="thumb-pages">${item.pageCount}P</span>` : '';
            const inQueueClass = inQueue.has(String(item.id)) ? ' in-queue' : '';
            const queueTip = buildQueueToggleTip(inQueue.has(String(item.id)));
            return `<div class="search-thumb${inQueueClass}" id="user-thumb-${idx}"
                     onclick="addUserItemToQueue(${idx})" title="${esc(item.title)} (${esc(item.userName)})${queueTip}">
          <img id="user-thumb-img-${idx}" src="" alt="${esc(item.title)}">
          <div class="thumb-badge-stack">${r18Badge}${aiBadge}${typeBadge}</div>
          ${pagesLabel}
          <span class="thumb-in-queue-mark">✓</span>
          <div class="thumb-title">${esc(item.title)}</div>
        </div>`;
        }).join('')}
    </div>`;
        loadUserThumbnailsBatched(userState.items, renderToken);
    }

    function renderUserPagination() {
        const pag = document.getElementById('user-pagination');
        if (!pag) return;
        const totalPages = Math.max(1, Number(userState.totalPages || 1));
        const cur = Math.min(Math.max(1, Number(userState.currentPage || 1)), totalPages);
        if (!userState.allIds.length || totalPages <= 1) {
            pag.style.display = 'none';
            pag.innerHTML = '';
            return;
        }
        pag.style.display = 'flex';
        const radius = 3;
        const pages = [];
        for (let p = Math.max(1, cur - radius); p <= Math.min(totalPages, cur + radius); p++) {
            pages.push(p);
        }
        pag.innerHTML =
            `<button onclick="loadUserPreviewPage(1)" ${cur === 1 ? 'disabled' : ''}>&laquo;</button>` +
            `<button onclick="loadUserPreviewPage(${cur - 1})" ${cur === 1 ? 'disabled' : ''}>&lsaquo;</button>` +
            pages.map(p =>
                `<button onclick="${p === cur ? '' : `loadUserPreviewPage(${p})`}" ${p === cur ? 'class="pg-active" disabled' : ''}>${p}</button>`
            ).join('') +
            `<button onclick="loadUserPreviewPage(${cur + 1})" ${cur === totalPages ? 'disabled' : ''}>&rsaquo;</button>` +
            `<button onclick="loadUserPreviewPage(${totalPages})" ${cur === totalPages ? 'disabled' : ''}>&raquo;</button>` +
            `<span class="pg-info">${esc(bt('search.pagination.info', '第 {current} / {total} 页 · 共 {count} 个', {
                current: cur,
                total: totalPages,
                count: userState.allIds.length.toLocaleString()
            }))}</span>`;
    }

    async function loadUserThumbnailsBatched(items, renderToken) {
        const BATCH = 10;
        for (let i = 0; i < items.length; i += BATCH) {
            if (renderToken !== userState.renderToken) return;
            const batch = items.slice(i, i + BATCH);
            await Promise.allSettled(batch.map((item, offset) => loadSingleUserThumbnail(item, i + offset, renderToken)));
        }
    }

    async function loadSingleUserThumbnail(item, idx, renderToken) {
        if (!item.thumbnailUrl) return;
        const imgEl = document.getElementById(`user-thumb-img-${idx}`);
        if (!imgEl) return;
        const blobUrl = await fetchThumbnailBlobUrl(item.thumbnailUrl, userState.activeBlobUrls);
        if (renderToken !== userState.renderToken) return;
        if (blobUrl && imgEl.isConnected) imgEl.src = blobUrl;
    }

    function buildUserQueueMeta(item) {
        if (userState.kind === 'novel') {
            return {
                title: item.title || bt('queue.novel-fallback', '小说 {id}', {id: item.id}),
                novelId: String(item.id),
                kind: 'novel',
                authorId: item.userId ? Number(item.userId) : Number(userState.userId),
                authorName: item.userName || userState.username || userState.userId,
                isAi: Number(item.aiType ?? 0) >= 2,
                xRestrict: Number(item.xRestrict ?? 0),
                tags: Array.isArray(item.tags) ? item.tags : []
            };
        }
        return {
            title: item.title,
            authorId: item.userId ? Number(item.userId) : Number(userState.userId),
            authorName: item.userName || userState.username || userState.userId,
            isAi: Number(item.aiType ?? 0) >= 2,
            xRestrict: Number(item.xRestrict ?? 0)
        };
    }

    function userQueueId(item) {
        return userState.kind === 'novel' ? 'n' + String(item.id) : String(item.id);
    }

    function syncUserResultsQueueState() {
        if (!userState.items.length) return;
        const inQueue = new Set(state.queue.map(q => q.id));
        userState.items.forEach((item, idx) => {
            const el = document.getElementById(
                userState.kind === 'novel' ? `user-novel-card-${idx}` : `user-thumb-${idx}`
            );
            if (!el) return;
            el.classList.toggle('in-queue', inQueue.has(userQueueId(item)));
        });
    }

    function updateUserQueueButtons(isLoading = false) {
        const pageBtn = document.getElementById('btn-user-add-page');
        const allBtn = document.getElementById('btn-user-add-all');
        if (pageBtn) pageBtn.disabled = isLoading || userState.items.length === 0;
        if (allBtn) allBtn.disabled = isLoading || userState.allIds.length === 0;
    }

    function addUserItemToQueue(idx) {
        const item = userState.items[idx];
        if (!item) return;
        const queueId = userQueueId(item);
        const alreadyInQueue = state.queue.find(q => q.id === queueId);
        if (alreadyInQueue) {
            const removed = removeFromQueue(queueId);
            setStatus(removed
                    ? bt('status.removed-from-queue', '已从队列移除：{title}', {title: item.title})
                    : bt('status.cannot-remove-downloading', '无法移除（正在下载中）：{title}', {title: item.title}),
                removed ? 'info' : 'warning');
            return;
        }
        const added = addItemsToQueue(
            [queueId],
            [buildUserQueueMeta(item)],
            'user',
            userState.username || userState.userId,
            userState.userId,
            userState.username || userState.userId
        );
        setStatus(added > 0
                ? bt('status.added-to-queue', '已加入队列：{title}', {title: item.title})
                : bt('status.already-in-queue', '已在队列中：{title}', {title: item.title}),
            added > 0 ? 'success' : 'info');
        syncUserResultsQueueState();
    }

    function addCurrentUserPageToQueue() {
        if (!userState.items.length) return;
        const isNovel = userState.kind === 'novel';
        const ids = userState.items.map(item => isNovel ? 'n' + String(item.id) : String(item.id));
        const metas = userState.items.map(buildUserQueueMeta);
        const added = addItemsToQueue(
            ids, metas, 'user',
            userState.username || userState.userId, userState.userId, userState.username || userState.userId
        );
        setStatus(
            bt('status.added-current-series-page-to-queue', '已将当前页 {added} 个作品加入队列（本页 {total} 个，{existing} 个已在队列中）',
                {added, total: ids.length, existing: ids.length - added}),
            added > 0 ? 'success' : 'info'
        );
        syncUserResultsQueueState();
    }

    async function addAllUserResultsToQueue() {
        if (!userState.allIds.length) return;
        const isNovel = userState.kind === 'novel';
        const uiFilters = normalizeSearchFilters(getSearchFiltersFromUI());
        // 无附加筛选：直接按全部 ID 入队（最省请求，等价于旧版「获取全部作品」）。
        if (!hasExtraSearchFilter(uiFilters)) {
            const ids = userState.allIds.map(id => isNovel ? 'n' + id : id);
            const metas = userState.allIds.map(id => isNovel
                ? {
                    title: bt('queue.novel-fallback', '小说 {id}', {id}),
                    novelId: String(id),
                    kind: 'novel',
                    authorId: Number(userState.userId),
                    authorName: userState.username || userState.userId
                }
                : {
                    authorId: Number(userState.userId),
                    authorName: userState.username || userState.userId
                });
            const added = addItemsToQueue(
                ids, metas, 'user',
                userState.username || userState.userId, userState.userId, userState.username || userState.userId
            );
            setStatus(
                bt('status.added-many-to-queue', '已将 {added} 个作品加入队列（共 {total} 个，{existing} 个已在队列中）',
                    {added, total: ids.length, existing: ids.length - added}),
                added > 0 ? 'success' : 'info'
            );
            syncUserResultsQueueState();
            return;
        }
        // 有附加筛选：必须逐页拉取卡片元数据做筛选，确认后继续。
        if (!uiConfirmKey(
            'dialog.user-add-all-warning',
            '「全部加入队列」会按附加筛选逐页请求该画师的全部 {total} 个作品卡片，作品较多时会增加 Pixiv 请求量并耗时，确认继续？',
            {total: userState.allIds.length}
        )) {
            return;
        }
        updateUserQueueButtons(true);
        try {
            const filters = uiFilters;
            const kind = userState.kind;
            const matched = [];
            const total = userState.allIds.length;
            for (let i = 0; i < userState.allIds.length; i += USER_PAGE_SIZE) {
                const slice = userState.allIds.slice(i, i + USER_PAGE_SIZE);
                setStatus(bt('status.user-fetch-all-progress', '正在抓取画师作品卡片 {done} / {total}...', {
                    done: Math.min(i + USER_PAGE_SIZE, total),
                    total
                }), 'info');
                const cards = await ensureUserCards(slice);
                const result = await computeFilteredItems(cards, filters, kind, () => false);
                if (result) matched.push(...result.filtered);
            }
            const ids = matched.map(item => isNovel ? 'n' + String(item.id) : String(item.id));
            const metas = matched.map(buildUserQueueMeta);
            const added = addItemsToQueue(
                ids, metas, 'user',
                userState.username || userState.userId, userState.userId, userState.username || userState.userId
            );
            setStatus(
                bt('status.added-many-to-queue', '已将 {added} 个作品加入队列（共 {total} 个，{existing} 个已在队列中）',
                    {added, total: ids.length, existing: ids.length - added}),
                added > 0 ? 'success' : 'info'
            );
            syncUserResultsQueueState();
        } catch (e) {
            setStatus(bt('status.fetch-failed', '获取作品列表失败：{message}', {message: e.message}), 'error');
        } finally {
            updateUserQueueButtons();
        }
    }

    /* ============================================================
       UI
    ============================================================ */
    function switchMode(mode) {
        let normalizedMode = normalizeImportMode(mode);
        // 计划任务仅管理员可进入；非管理员请求时回退到默认模式
        if (normalizedMode === 'schedule' && !isAdmin) normalizedMode = QUICK_FETCH_MODE;
        state.mode = normalizedMode;
        [QUICK_FETCH_MODE, SINGLE_IMPORT_MODE, 'user', 'search', 'series', 'schedule'].forEach(m => {
            const tab = document.getElementById('tab-' + m);
            const panel = document.getElementById('panel-' + m);
            if (tab) tab.classList.toggle('active', m === normalizedMode);
            if (panel) panel.classList.toggle('active', m === normalizedMode);
        });
        // 计划任务模式下隐藏共享的下载设置 / 队列工作台
        const workbench = document.getElementById('download-workbench');
        if (workbench) workbench.style.display = (normalizedMode === 'schedule') ? 'none' : '';
        storeSet('pixiv_mode', normalizedMode);
        applyNovelSettingsVisibility();
        updateSaveScheduleCardVisibility();
        updateExtraFiltersCardVisibility();
        // 进入带预览的模式时，按当前附加筛选输入重新过滤已加载的预览页（筛选可能在别的模式被改过）
        if (normalizedMode === 'user' && userState.rawItems.length) {
            applyUserFilters({});
        } else if (normalizedMode === 'series' && seriesState.rawItems.length) {
            applySeriesFilters({});
        }
        if (normalizedMode === QUICK_FETCH_MODE) {
            updateQuickAccountBar();
        }
        if (normalizedMode === 'schedule') {
            loadScheduleTasks();
            startSchedulePolling();
        } else {
            stopSchedulePolling();
        }
    }

    function applyNovelSettingsVisibility() {
        const card = document.getElementById('novel-settings-card');
        if (!card) return;
        const mode = state.mode;
        let visible;
        if (mode === SINGLE_IMPORT_MODE) {
            visible = true;
        } else if (mode === 'user') {
            visible = state.settings.userKind === 'novel';
        } else if (mode === 'search') {
            visible = state.settings.searchKind === 'novel';
        } else if (mode === 'series') {
            visible = true;
        } else {
            visible = false;
        }
        card.style.display = visible ? '' : 'none';
    }

    const STATUS_COLORS = {info: '#007bff', success: '#28a745', error: '#dc3545', warning: '#e6a700'};

    function setStatus(msg, type = 'info') {
        const el = document.getElementById('status-bar');
        el.textContent = msg;
        el.style.color = STATUS_COLORS[type] || '#666';
    }

    // Cookie 相关提示显示在 Cookie 区域，而非下载队列状态栏
    function setCookieStatus(msg, type = 'info') {
        const el = document.getElementById('cookie-status');
        if (!el) {
            setStatus(msg, type);
            return;
        }
        el.textContent = msg;
        el.style.color = STATUS_COLORS[type] || '#666';
    }

    function updateStats() {
        state.stats.success = state.queue.filter(q => q.status === 'completed').length;
        state.stats.failed = state.queue.filter(q => q.status === 'failed').length;
        state.stats.active = state.queue.filter(q => q.status === 'downloading').length;
        state.stats.skipped = state.queue.filter(q => q.status === 'skipped').length;
        const pending = state.queue.filter(q =>
            ['idle', 'pending', 'paused'].includes(q.status)).length;
        document.getElementById('stats-bar').textContent = formatStatsText(
            pending,
            state.stats.success,
            state.stats.failed,
            state.stats.active,
            state.stats.skipped
        );
    }

    function updateButtonsState() {
        document.getElementById('btn-start').disabled = state.isRunning;
        document.getElementById('btn-pause').disabled = !state.isRunning;
        document.getElementById('btn-pause').textContent = state.isPaused
            ? bt('button.resume', '▶ 继续')
            : bt('button.pause', '⏸ 暂停');
        updateAdminPackButton();
    }

    function updateAuthButtons() {
        document.getElementById('login-btn').style.display = isAdmin ? 'none' : 'block';
        document.getElementById('logout-btn').style.display = isAdmin ? 'block' : 'none';
        // 计划任务为管理员专用：非管理员隐藏该 tab
        const schedTab = document.getElementById('tab-schedule');
        if (schedTab) {
            schedTab.style.display = isAdmin ? '' : 'none';
            // 非管理员若停留在 schedule 模式则退回默认模式
            if (!isAdmin && state.mode === 'schedule') switchMode(SINGLE_IMPORT_MODE);
        }
        updateSaveScheduleCardVisibility();
        updateExtraFiltersCardVisibility();
        updateBatchLimitNote();
        updateBatchEndPageAdminGate();
    }

    // 结束页 = -1（「直到已下载作品为止」哨兵）仅管理员可用：管理员放开输入下限到 -1 并显示提示，
    // 非管理员保持 min=1（输入 -1 会被夹回）。该控件为普通「作品批量获取模式」共享，故仅做权限门控。
    function updateBatchEndPageAdminGate() {
        const endP = document.getElementById('batch-end-page');
        if (endP) {
            endP.min = isAdmin ? -1 : 1;
            if (!isAdmin && parseInt(endP.value, 10) === -1) endP.value = 1;
        }
        const hint = document.getElementById('batch-end-page-hint');
        if (hint) hint.style.display = isAdmin ? '' : 'none';
    }

    function updateAdminPackButton() {
        const btn = document.getElementById('admin-pack-btn');
        if (!btn) return;
        if (!isAdmin) {
            btn.style.display = 'none';
            btn.disabled = true;
            return;
        }
        btn.style.display = 'inline-flex';
        btn.disabled = !state.queue.some(q => q.status === 'completed');
    }

    function setCurrent(item) {
        const el = document.getElementById('current-card');
        state.currentItemId = item ? String(item.id) : null;
        el.innerHTML = formatCurrentCardHtml(item);
    }

    function renderQueue() {
        const el = document.getElementById('queue-list');
        if (!state.queue.length) {
            el.innerHTML = `<div class="queue-empty">${esc(bt('status.queue-empty', '队列为空'))}</div>`;
            updateAdminPackButton();
            return;
        }
        el.innerHTML = state.queue.map(q => buildQueueItemHtml(q, {removable: true})).join('');
        updateAdminPackButton();
    }

    // 单个队列项的 HTML。下载工作区底部的「下载队列」与计划任务卡片底部的「本轮队列详情」共用此函数，
    // 保证两处队列展示完全一致（进度条、来源/分级/AI 标记、小说进度等）。
    // opts.removable=false 时不渲染移除按钮（计划任务为服务端队列，前端不可移除）。
    function buildQueueItemHtml(q, opts) {
        const removable = !opts || opts.removable !== false;
        const prog = q.totalImages > 0
            ? `<div class="prog-wrap">
          <div class="prog-label"><span>${esc(formatImageProgressText(q.downloadedCount || 0, q.totalImages))}</span><span>${pct(q)}%</span></div>
         <div class="prog-bg"><div class="prog-fill" style="width:${pct(q)}%;background:${statusColor(q.status)}"></div></div>
         </div>` : '';
        const detailProg = formatImageDownloadProgressHtml(q.imageProgress, q.status)
            + formatUgoiraProgressHtml(q.ugoiraProgress, q.status)
            + formatNovelProgressHtml(q);
        const desc = q.lastMessage || queueStatusText(q.status);
        const descHtml = renderQueueMessageHtml(q, desc);
        const sourceTone = q.source === 'user'
            ? {label: queueSourceText('user'), bg: '#007bff'}
            : q.source === 'search' || q.source === 'search-novel'
                ? {label: queueSourceText('search'), bg: '#28a745'}
                : q.source === 'series' || q.source === 'series-novel'
                    ? {label: queueSourceText('series'), bg: '#6366f1'}
                    : {label: queueSourceText(SINGLE_IMPORT_MODE), bg: '#6610f2'};
        const srcLabel = `<span style="background:${sourceTone.bg};color:white;border-radius:3px;padding:1px 5px;font-size:10px;margin-left:5px;vertical-align:middle;">${esc(sourceTone.label)}</span>`;
        const R18Label = q.xRestrict == null
            ? `<span style="background:rgba(100,116,139,.15);color:#64748b;border-radius:3px;padding:1px 5px;font-size:10px;margin-left:3px;vertical-align:middle;">${esc(bt('queue.unknown', '未知'))}</span>`
            : q.xRestrict === 2
                ? `<span style="background:#b91c1c;color:white;border-radius:3px;padding:1px 5px;font-size:10px;margin-left:3px;vertical-align:middle;">R-18G</span>`
                : q.xRestrict === 1
                    ? `<span style="background:#dc3545;color:white;border-radius:3px;padding:1px 5px;font-size:10px;margin-left:3px;vertical-align:middle;">R-18</span>`
                    : `<span style="background:#198754;color:white;border-radius:3px;padding:1px 5px;font-size:10px;margin-left:3px;vertical-align:middle;">SFW</span>`;
        const AILabel = q.isAi === true
            ? `<span style="background:#d946ef;color:white;border-radius:3px;padding:1px 5px;font-size:10px;margin-left:3px;vertical-align:middle;">AI</span>`
            : '';
        const canRemove = removable && q.status !== 'downloading';
        const removeBtn = canRemove
            ? `<button onclick="removeFromQueue('${q.id}');event.stopPropagation();" title="${esc(bt('queue.remove', '移除'))}" style="background:none;border:none;color:#aaa;cursor:pointer;font-size:13px;padding:0 2px;line-height:1;" onmouseover="this.style.color='#dc3545'" onmouseout="this.style.color='#aaa'">✕</button>`
            : '';
        const isNovel = q.kind === 'novel';
        const linkHref = isNovel
            ? `https://www.pixiv.net/novel/show.php?id=${encodeURIComponent(q.novelId || String(q.id).replace(/^n/, ''))}`
            : `https://www.pixiv.net/artworks/${q.id}`;
        const linkBtn = `<a href="${linkHref}" target="_blank" onclick="event.stopPropagation();" title="${esc(bt('queue.open-artwork', '打开作品页面'))}" style="color:#007bff;font-size:13px;padding:0 2px;text-decoration:none;line-height:1;">🔗</a>`;
        const novelTag = isNovel
            ? `<span style="background:#0d9488;color:white;border-radius:3px;padding:1px 5px;font-size:10px;margin-left:3px;vertical-align:middle;">📕 ${esc(bt('queue.novel', '小说'))}</span>`
            : '';
        return `<div class="queue-item" style="border-left-color:${statusColor(q.status)}">
      <div class="q-title" style="display:flex;align-items:center;gap:2px;">
        <span style="flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">${esc(q.title)}${novelTag}${srcLabel}${R18Label}${AILabel}</span>
        ${linkBtn}${removeBtn}
      </div>
      <div class="q-meta">ID: ${isNovel ? (q.novelId || String(q.id).replace(/^n/, '')) + ' (Novel)' : q.id} | ${descHtml}</div>
      ${prog}
      ${detailProg}
    </div>`;
    }

    function pct(q) {
        if (!q.totalImages) return 0;
        return Math.min(100, Math.round((q.downloadedCount || 0) / q.totalImages * 100));
    }

    function statusColor(s) {
        return {
            completed: '#28a745', downloading: '#007bff', failed: '#dc3545',
            paused: '#6c757d', skipped: '#fd7e14'
        }[s] || '#ccc';
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

    /* ============================================================
       持久化
    ============================================================ */
    function storageKey() {
        return 'pixiv_batch_queue';
    }

    function saveQueue() {
        if (state.userId) storeSet('pixiv_batch_last_user_id', state.userId);
        if (state.username) storeSet('pixiv_batch_last_username', state.username);
        storeSet(storageKey(), JSON.stringify({
            queue: state.queue,
            isPaused: state.isPaused,
            stats: state.stats,
            savedAt: new Date().toISOString()
        }));
    }

    function loadQueueForMode() {
        try {
            const raw = storeGet(storageKey());
            if (!raw) {
                state.queue = [];
                renderQueue();
                updateStats();
                return;
            }
            const parsed = JSON.parse(raw);
            if (Array.isArray(parsed.queue)) {
                state.queue = dedupeQueueItems(parsed.queue);
                // 刷新前正在下载的项目实际已中断，标记为失败
                state.queue.forEach(q => {
                    q.source = normalizeImportMode(q.source);
                    if (q.status === 'downloading') {
                        q.status = 'failed';
                        q.lastMessage = bt('queue.message.failed-refresh', '失败 — 页面刷新导致中断');
                        q.lastMessageParts = null;
                    }
                });
                state.isPaused = !!parsed.isPaused;
                state.stats = parsed.stats || {success: 0, failed: 0, active: 0, skipped: 0};
            } else {
                state.queue = [];
            }
        } catch {
            state.queue = [];
        }
        renderQueue();
        updateStats();
    }

    function clearSavedQueue() {
        storeRemove(storageKey());
    }

    function saveSettings() {
        storeSet('pixiv_batch_settings', JSON.stringify(state.settings));
    }

    function loadSettings() {
        try {
            const raw = storeGet('pixiv_batch_settings');
            if (raw) Object.assign(state.settings, JSON.parse(raw));
        } catch {
        }
        document.getElementById('s-interval').value = state.settings.interval;
        document.getElementById('s-concurrent').value = state.settings.concurrent;
        document.getElementById('s-skip').checked = state.settings.skipHistory;
        document.getElementById('s-verify-files').checked = state.settings.verifyHistoryFiles ?? false;
        document.getElementById('s-bookmark').checked = state.settings.bookmark ?? false;
        document.getElementById('s-image-delay').value = state.settings.imageDelay ?? 0;
        state.settings.fileNameTemplate = normalizeFileNameTemplate(state.settings.fileNameTemplate);
        document.getElementById('s-file-name-template').value = state.settings.fileNameTemplate;
        const unit = state.settings.intervalUnit || 's';
        state.settings.intervalUnit = unit;
        document.getElementById('s-interval-unit').textContent = unit;
        const imgUnit = state.settings.imageDelayUnit || 'ms';
        state.settings.imageDelayUnit = imgUnit;
        document.getElementById('s-image-delay-unit').textContent = imgUnit;
        if (state.settings.collectionId) {
            const sel = document.getElementById('s-collection');
            sel.value = String(state.settings.collectionId);
        }
        const fmtEl = document.getElementById('s-novel-format');
        if (fmtEl) fmtEl.value = (state.settings.novelFormat || 'txt');
        const mergeEl = document.getElementById('s-novel-merge');
        if (mergeEl) mergeEl.checked = !!state.settings.mergeNovelSeries;
        const mergeFmtEl = document.getElementById('s-novel-merge-format');
        if (mergeFmtEl) mergeFmtEl.value = (state.settings.mergeNovelFormat || 'epub');
        updateMergeFormatVisibility();
        state.settings.userKind = state.settings.userKind === 'novel' ? 'novel' : 'illust';
        state.settings.searchKind = state.settings.searchKind === 'novel' ? 'novel' : 'illust';
        applyKindSwitcherUI('user-kind-switcher', state.settings.userKind);
        applyKindSwitcherUI('search-kind-switcher', state.settings.searchKind);
        applySearchKindUI();
        applyNovelSettingsVisibility();
        toggleSkipHistoryOptions();
    }

    function applyKindSwitcherUI(switcherId, value) {
        const root = document.getElementById(switcherId);
        if (!root) return;
        root.querySelectorAll('label').forEach(lbl => {
            const matches = lbl.dataset.kind === value;
            lbl.classList.toggle('active', matches);
            const input = lbl.querySelector('input[type=radio]');
            if (input) input.checked = matches;
        });
    }

    function bindKindSwitcher(switcherId, settingKey, onChange) {
        const root = document.getElementById(switcherId);
        if (!root) return;
        root.querySelectorAll('label').forEach(lbl => {
            lbl.addEventListener('click', () => {
                const next = lbl.dataset.kind === 'novel' ? 'novel' : 'illust';
                if (state.settings[settingKey] === next) return;
                state.settings[settingKey] = next;
                applyKindSwitcherUI(switcherId, next);
                saveSettings();
                if (typeof onChange === 'function') onChange(next);
            });
        });
    }

    // 当前模式对应的作品类型（插画/小说）——共享「附加筛选」里页数/字数字段的显隐据此切换
    function currentModeKind() {
        if (state.mode === 'user') return state.settings.userKind === 'novel' ? 'novel' : 'illust';
        if (state.mode === 'search') return state.settings.searchKind === 'novel' ? 'novel' : 'illust';
        if (state.mode === 'series') return seriesState.kind === 'novel' ? 'novel' : 'illust';
        return 'illust';
    }

    function applySearchKindUI() {
        // 批量导入单作品队列可同时含插画与小说，故显示全部专属字段（下载时按各作品类型分别套用）。
        const showAll = state.mode === 'single-import';
        const isNovel = currentModeKind() === 'novel';
        document.querySelectorAll('.search-illust-only').forEach(el => {
            el.style.display = showAll ? '' : (isNovel ? 'none' : '');
        });
        document.querySelectorAll('.search-novel-only').forEach(el => {
            el.style.display = showAll ? '' : (isNovel ? '' : 'none');
        });
    }

    // 共享「附加筛选」卡片：批量导入单作品 / Search / User / 系列四模式对所有用户显示。
    // Search/User/系列：实时过滤当前预览页；所有模式：实际下载时按此条件逐作品过滤并跳过；
    // 管理员另外可在 User/Search/系列模式下把同一份筛选条件快照进「存为计划任务」（后台逐作品执行）。
    function updateExtraFiltersCardVisibility() {
        const card = document.getElementById('extra-filters-card');
        if (!card) return;
        const mode = state.mode;
        const visible = mode === 'search' || mode === 'user' || mode === 'series' || mode === 'single-import';
        card.style.display = visible ? '' : 'none';
        if (visible) applySearchKindUI();
    }

    function syncSettings() {
        state.settings.interval = Math.max(0, parseFloat(document.getElementById('s-interval').value) || 0);
        state.settings.imageDelay = Math.max(0, parseFloat(document.getElementById('s-image-delay').value) || 0);
        state.settings.concurrent = Math.max(1, parseInt(document.getElementById('s-concurrent').value) || 1);
        state.settings.skipHistory = document.getElementById('s-skip').checked;
        state.settings.verifyHistoryFiles = document.getElementById('s-verify-files').checked;
        state.settings.bookmark = document.getElementById('s-bookmark').checked;
        state.settings.fileNameTemplate = normalizeFileNameTemplate(document.getElementById('s-file-name-template').value);
        const sel = document.getElementById('s-collection');
        state.settings.collectionId = sel.value ? Number(sel.value) : null;
        const fmtEl = document.getElementById('s-novel-format');
        if (fmtEl) state.settings.novelFormat = (fmtEl.value || 'txt').toLowerCase();
        const mergeEl = document.getElementById('s-novel-merge');
        if (mergeEl) state.settings.mergeNovelSeries = !!mergeEl.checked;
        const mergeFmtEl = document.getElementById('s-novel-merge-format');
        if (mergeFmtEl) state.settings.mergeNovelFormat = (mergeFmtEl.value || 'epub').toLowerCase();
        updateMergeFormatVisibility();
        toggleSkipHistoryOptions();
        saveSettings();
    }

    // 合订本格式选择仅在勾选「生成合订本」时可见
    function updateMergeFormatVisibility() {
        const on = !!(document.getElementById('s-novel-merge') || {}).checked;
        ['s-novel-merge-format-row', 's-novel-merge-format-hint'].forEach(id => {
            const el = document.getElementById(id);
            if (el) el.style.display = on ? '' : 'none';
        });
    }

    function toggleSkipHistoryOptions() {
        const wrap = document.getElementById('s-verify-files-wrap');
        if (!wrap) return;
        wrap.style.display = document.getElementById('s-skip').checked ? '' : 'none';
    }

    /* ============================================================
       搜索模式
    ============================================================ */
    // 本地批量获取分页：每页固定 60 个（与 Pixiv 插画搜索一致）
    const BATCH_PER_PAGE = 60;

    function defaultSearchFilters() {
        return {
            content: 'all',
            ai: 'all',
            type: 'all',
            pageMin: null,
            pageMax: null,
            bookmarkMin: null,
            bookmarkMax: null,
            wordsMin: null,
            wordsMax: null,
            tagsExact: [],
            tagsFuzzy: []
        };
    }

    // 标签输入：逗号（半/全角）分隔，去空白去重；不按空格切分（标签本身可能含空格）
    function parseTagTerms(value) {
        if (Array.isArray(value)) {
            value = value.join(',');
        }
        const seen = new Set();
        const out = [];
        String(value || '').split(/[,，]/).forEach(raw => {
            const t = raw.trim();
            if (t && !seen.has(t.toLowerCase())) {
                seen.add(t.toLowerCase());
                out.push(t);
            }
        });
        return out;
    }

    let searchState = {
        rawResults: [],
        results: [],
        total: 0,
        currentPage: 1,
        submode: 'search',  // 'search'=搜索模式 | 'batch'=作品批量获取模式
        localPage: 1,       // 批量获取模式下的本地分页页码
        batchInfo: null,    // 最近一次批量获取的元信息（范围/限额）
        currentWord: '',
        currentMode: 'all',
        currentOrder: 'date_d',
        currentSMode: 's_tag',
        kind: 'illust',     // 'illust' | 'novel' — 当前结果类型，由最近一次 performSearch 设置
        blurR18: false,
        noCookie: false,
        activeBlobUrls: [],
        metaCache: {},
        filterSeq: 0,
        pixivPageCount: 0,
        currentFilters: defaultSearchFilters(),
        filterSummary: {
            rawCount: 0,
            filteredCount: 0,
            bookmarkMetaMissing: 0,
            bookmarkFilterActive: false
        }
    };

    function parseSearchFilterNumber(value, min) {
        if (value === null || value === undefined) return null;
        const raw = String(value).trim();
        if (!raw) return null;
        const n = Math.floor(Number(raw));
        if (!Number.isFinite(n)) return null;
        return Math.max(min, n);
    }

    function normalizeSearchFilters(filters) {
        const out = defaultSearchFilters();
        out.content = ['all', 'safe', 'r18plus', 'r18', 'r18g'].includes(filters?.content) ? filters.content : 'all';
        out.ai = ['all', 'exclude', 'only'].includes(filters?.ai) ? filters.ai : 'all';
        out.type = ['all', 'illust', 'manga', 'ugoira'].includes(filters?.type) ? filters.type : 'all';
        out.pageMin = parseSearchFilterNumber(filters?.pageMin, 1);
        out.pageMax = parseSearchFilterNumber(filters?.pageMax, 1);
        out.bookmarkMin = parseSearchFilterNumber(filters?.bookmarkMin, 0);
        out.bookmarkMax = parseSearchFilterNumber(filters?.bookmarkMax, 0);
        out.wordsMin = parseSearchFilterNumber(filters?.wordsMin, 0);
        out.wordsMax = parseSearchFilterNumber(filters?.wordsMax, 0);
        out.tagsExact = parseTagTerms(filters?.tagsExact);
        out.tagsFuzzy = parseTagTerms(filters?.tagsFuzzy);
        if (out.pageMin !== null && out.pageMax !== null && out.pageMin > out.pageMax) {
            [out.pageMin, out.pageMax] = [out.pageMax, out.pageMin];
        }
        if (out.bookmarkMin !== null && out.bookmarkMax !== null && out.bookmarkMin > out.bookmarkMax) {
            [out.bookmarkMin, out.bookmarkMax] = [out.bookmarkMax, out.bookmarkMin];
        }
        if (out.wordsMin !== null && out.wordsMax !== null && out.wordsMin > out.wordsMax) {
            [out.wordsMin, out.wordsMax] = [out.wordsMax, out.wordsMin];
        }
        return out;
    }

    function setSearchFiltersUI(filters) {
        const contentEl = document.getElementById('search-content-filter');
        if (contentEl) contentEl.value = filters.content;
        document.getElementById('search-ai-filter').value = filters.ai;
        document.getElementById('search-type-filter').value = filters.type;
        document.getElementById('search-pages-min').value = filters.pageMin ?? '';
        document.getElementById('search-pages-max').value = filters.pageMax ?? '';
        document.getElementById('search-bookmarks-min').value = filters.bookmarkMin ?? '';
        document.getElementById('search-bookmarks-max').value = filters.bookmarkMax ?? '';
        const wMin = document.getElementById('search-words-min');
        if (wMin) wMin.value = filters.wordsMin ?? '';
        const wMax = document.getElementById('search-words-max');
        if (wMax) wMax.value = filters.wordsMax ?? '';
        const tEx = document.getElementById('search-tags-exact');
        if (tEx) tEx.value = (filters.tagsExact || []).join(', ');
        const tFz = document.getElementById('search-tags-fuzzy');
        if (tFz) tFz.value = (filters.tagsFuzzy || []).join(', ');
    }

    function getSearchFiltersFromUI() {
        const wMin = document.getElementById('search-words-min');
        const wMax = document.getElementById('search-words-max');
        return normalizeSearchFilters({
            content: (document.getElementById('search-content-filter') || {}).value || 'all',
            ai: document.getElementById('search-ai-filter').value,
            type: document.getElementById('search-type-filter').value,
            pageMin: document.getElementById('search-pages-min').value,
            pageMax: document.getElementById('search-pages-max').value,
            bookmarkMin: document.getElementById('search-bookmarks-min').value,
            bookmarkMax: document.getElementById('search-bookmarks-max').value,
            wordsMin: wMin ? wMin.value : null,
            wordsMax: wMax ? wMax.value : null,
            tagsExact: (document.getElementById('search-tags-exact') || {}).value || '',
            tagsFuzzy: (document.getElementById('search-tags-fuzzy') || {}).value || ''
        });
    }

    function saveSearchFilterPrefs(filters) {
        storeSet('pixiv_search_filters', JSON.stringify({
            content: filters.content,
            ai: filters.ai,
            type: filters.type,
            pageMin: filters.pageMin,
            pageMax: filters.pageMax,
            bookmarkMin: filters.bookmarkMin,
            bookmarkMax: filters.bookmarkMax,
            wordsMin: filters.wordsMin,
            wordsMax: filters.wordsMax,
            tagsExact: filters.tagsExact,
            tagsFuzzy: filters.tagsFuzzy
        }));
    }

    function loadSearchFilterPrefs() {
        try {
            const raw = storeGet('pixiv_search_filters');
            const filters = raw ? normalizeSearchFilters(JSON.parse(raw)) : defaultSearchFilters();
            searchState.currentFilters = filters;
            setSearchFiltersUI(filters);
        } catch {
            const filters = defaultSearchFilters();
            searchState.currentFilters = filters;
            setSearchFiltersUI(filters);
        }
    }

    function hasBookmarkFilter(filters = searchState.currentFilters) {
        return filters.bookmarkMin !== null || filters.bookmarkMax !== null;
    }

    function hasExtraSearchFilter(filters = searchState.currentFilters) {
        return filters.content !== 'all'
            || filters.ai !== 'all'
            || filters.type !== 'all'
            || filters.pageMin !== null
            || filters.pageMax !== null
            || filters.wordsMin !== null
            || filters.wordsMax !== null
            || (filters.tagsExact && filters.tagsExact.length > 0)
            || (filters.tagsFuzzy && filters.tagsFuzzy.length > 0)
            || hasBookmarkFilter(filters);
    }

    // 作品标签词元：兼容字符串数组（搜索 / 画师卡片）与 TagDto 对象数组（小说系列）。
    // 对象同时取原名与英文翻译作为可匹配词元（与计划任务后台逐作品筛选语义一致）。
    function itemTagTokens(item) {
        const out = [];
        for (const t of (Array.isArray(item.tags) ? item.tags : [])) {
            if (typeof t === 'string') {
                if (t) out.push(t.toLowerCase());
            } else if (t) {
                const name = t.name || t.tag;
                if (name) out.push(String(name).toLowerCase());
                const tr = t.translatedName || t.translation;
                if (tr) out.push(String(tr).toLowerCase());
            }
        }
        return out;
    }

    // 标签筛选：逗号分隔多标签全部命中(AND)；精确=与某个作品标签完全相等，
    // 模糊=被某个作品标签包含；两框都填则两者都需满足(AND)。大小写不敏感。
    function matchTagFilters(item, filters) {
        const exact = filters.tagsExact || [];
        const fuzzy = filters.tagsFuzzy || [];
        if (!exact.length && !fuzzy.length) return true;
        const tags = itemTagTokens(item);
        for (const term of exact) {
            const t = term.toLowerCase();
            if (!tags.some(x => x === t)) return false;
        }
        for (const term of fuzzy) {
            const t = term.toLowerCase();
            if (!tags.some(x => x.includes(t))) return false;
        }
        return true;
    }

    // 缓存键带 kind 前缀：插画与小说 ID 命名空间不同，相同数字可能指向不同作品。
    function searchMetaCacheKey(id, kind = searchState.kind) {
        return (kind === 'novel' ? 'n:' : 'i:') + String(id);
    }

    function getCachedSearchMeta(id, kind = searchState.kind) {
        return searchState.metaCache[searchMetaCacheKey(id, kind)] || null;
    }

    function getInlineSearchBookmarkCount(item) {
        const count = Number(item?.bookmarkCount);
        return Number.isFinite(count) && count >= 0 ? count : null;
    }

    function getSearchBookmarkCount(item, kind = searchState.kind) {
        const cached = getCachedSearchMeta(item.id, kind);
        if (cached && cached.bookmarkResolved) {
            const count = Number(cached.bookmarkCount);
            if (Number.isFinite(count) && count >= 0) return count;
        }
        return getInlineSearchBookmarkCount(item);
    }

    // 按需补齐逐作品收藏数 meta（收藏数筛选用）。收藏数缓存按 kind+id 全局共享（searchState.metaCache），
    // 故 Search / User / 系列三种预览可复用。isStale() 用于在新一轮筛选发起后提前放弃过期请求。
    async function ensureBookmarkMeta(items, kind, isStale) {
        const missingIds = [];
        const seen = new Set();
        for (const item of items) {
            const id = String(item.id);
            if (seen.has(id)) continue;
            seen.add(id);
            if (getInlineSearchBookmarkCount(item) !== null) continue;
            const cached = getCachedSearchMeta(id, kind);
            if (!cached || !cached.bookmarkResolved) missingIds.push(id);
        }
        if (!missingIds.length) return;

        const fetchMeta = kind === 'novel' ? getNovelBookmarkCountForSearch : getArtworkMeta;
        let cursor = 0;
        const workers = [];
        const workerCount = Math.min(6, missingIds.length);
        for (let i = 0; i < workerCount; i++) {
            workers.push((async () => {
                while (cursor < missingIds.length) {
                    if (isStale()) return;
                    const id = missingIds[cursor++];
                    const cacheKey = searchMetaCacheKey(id, kind);
                    try {
                        const meta = await fetchMeta(id);
                        if (isStale()) return;
                        searchState.metaCache[cacheKey] = {
                            ...(searchState.metaCache[cacheKey] || {}),
                            bookmarkCount: Number(meta?.bookmarkCount ?? -1),
                            bookmarkResolved: true
                        };
                    } catch {
                        if (isStale()) return;
                        searchState.metaCache[cacheKey] = {
                            ...(searchState.metaCache[cacheKey] || {}),
                            bookmarkCount: -1,
                            bookmarkResolved: true,
                            bookmarkError: true
                        };
                    }
                }
            })());
        }
        await Promise.all(workers);
    }

    // 通用「逐作品附加筛选」：对任意作品数组应用同一套 matchSearchFilters。
    // 返回 {filtered, stats}；当 isStale() 在 await 收藏数 meta 期间变 true 时返回 null（调用方应丢弃）。
    async function computeFilteredItems(items, filters, kind, isStale) {
        const source = Array.isArray(items) ? items : [];
        const bookmarkFilterActive = hasBookmarkFilter(filters);
        if (bookmarkFilterActive) {
            await ensureBookmarkMeta(source, kind, isStale);
            if (isStale()) return null;
        }
        const stats = {
            rawCount: source.length,
            filteredCount: 0,
            bookmarkMetaMissing: 0,
            bookmarkFilterActive
        };
        const filtered = source.filter(item => matchSearchFilters(item, filters, stats, kind));
        stats.filteredCount = filtered.length;
        return {filtered, stats};
    }

    // 内容分级匹配：all=不限 / safe=仅全年龄 / r18plus=R-18+R-18G / r18=仅 R-18 / r18g=仅 R-18G。
    function matchContentRating(xRestrict, content) {
        const xr = Number(xRestrict ?? 0);
        switch (content) {
            case 'safe': return xr === 0;
            case 'r18plus': return xr >= 1;
            case 'r18': return xr === 1;
            case 'r18g': return xr === 2;
            default: return true; // all
        }
    }

    function matchSearchFilters(item, filters, stats, kind = searchState.kind) {
        if (!matchContentRating(item.xRestrict, filters.content)) return false;

        const aiType = Number(item.aiType ?? 0);
        if (filters.ai === 'exclude' && aiType >= 2) return false;
        if (filters.ai === 'only' && aiType < 2) return false;

        if (!matchTagFilters(item, filters)) return false;

        if (kind === 'novel') {
            const wc = Number(item.wordCount ?? 0);
            if (filters.wordsMin !== null && wc < filters.wordsMin) return false;
            if (filters.wordsMax !== null && wc > filters.wordsMax) return false;
            if (hasBookmarkFilter(filters)) {
                const bookmarkCount = getSearchBookmarkCount(item, kind);
                if (bookmarkCount === null) {
                    stats.bookmarkMetaMissing++;
                    return false;
                }
                if (filters.bookmarkMin !== null && bookmarkCount < filters.bookmarkMin) return false;
                if (filters.bookmarkMax !== null && bookmarkCount > filters.bookmarkMax) return false;
            }
            return true;
        }

        const illustType = Number(item.illustType ?? 0);
        if (filters.type === 'illust' && illustType !== 0) return false;
        if (filters.type === 'manga' && illustType !== 1) return false;
        if (filters.type === 'ugoira' && illustType !== 2) return false;

        const pageCount = Number(item.pageCount ?? 0);
        if (filters.pageMin !== null && pageCount < filters.pageMin) return false;
        if (filters.pageMax !== null && pageCount > filters.pageMax) return false;

        if (hasBookmarkFilter(filters)) {
            const bookmarkCount = getSearchBookmarkCount(item, kind);
            if (bookmarkCount === null) {
                stats.bookmarkMetaMissing++;
                return false;
            }
            if (filters.bookmarkMin !== null && bookmarkCount < filters.bookmarkMin) return false;
            if (filters.bookmarkMax !== null && bookmarkCount > filters.bookmarkMax) return false;
        }

        return true;
    }

    // 实际下载时的「附加筛选」判定：拉到作品 meta 后调用，返回 null=通过、否则返回本地化的跳过原因。
    // 与预览用的 matchSearchFilters 同口径（内容分级 / AI / 标签 / 类型 / 页数 / 字数 / 收藏数），
    // 取当前附加筛选 UI 为准；插画/小说按 kind 分别套用对应专属判定。
    function evaluateDownloadFilterSkip(meta, kind) {
        const filters = normalizeSearchFilters(getSearchFiltersFromUI());
        const xr = Number(meta.xRestrict ?? meta.xrestrict ?? 0);
        if (!matchContentRating(xr, filters.content)) {
            return bt('queue.message.skipped-filter-content', '跳过 — 内容分级不符（要求 {label}）',
                {label: bt('search.content.' + filters.content, filters.content)});
        }
        const isAi = meta?.isAi === true || Number(meta?.aiType ?? 0) >= 2;
        if (filters.ai === 'exclude' && isAi) {
            return bt('queue.message.skipped-filter-ai-exclude', '跳过 — AI 作品（附加筛选已设为排除 AI）');
        }
        if (filters.ai === 'only' && !isAi) {
            return bt('queue.message.skipped-filter-ai-only', '跳过 — 非 AI 作品（附加筛选已设为仅 AI）');
        }
        if (!matchTagFilters({tags: Array.isArray(meta.tags) ? meta.tags : []}, filters)) {
            return bt('queue.message.skipped-filter-tags', '跳过 — 标签不匹配附加筛选');
        }
        if (kind === 'novel') {
            const wc = Number(meta.wordCount ?? 0);
            if (wc > 0) {
                if (filters.wordsMin !== null && wc < filters.wordsMin) return bt('queue.message.skipped-filter-words', '跳过 — 字数不符附加筛选');
                if (filters.wordsMax !== null && wc > filters.wordsMax) return bt('queue.message.skipped-filter-words', '跳过 — 字数不符附加筛选');
            }
        } else {
            const illustType = Number(meta.illustType ?? 0);
            if ((filters.type === 'illust' && illustType !== 0)
                || (filters.type === 'manga' && illustType !== 1)
                || (filters.type === 'ugoira' && illustType !== 2)) {
                return bt('queue.message.skipped-filter-type', '跳过 — 作品类型不符附加筛选');
            }
            const pageCount = Number(meta.pageCount ?? 0);
            if (pageCount > 0) {
                if (filters.pageMin !== null && pageCount < filters.pageMin) return bt('queue.message.skipped-filter-pages', '跳过 — 页数不符附加筛选');
                if (filters.pageMax !== null && pageCount > filters.pageMax) return bt('queue.message.skipped-filter-pages', '跳过 — 页数不符附加筛选');
            }
        }
        if (hasBookmarkFilter(filters)) {
            const bc = Number(meta.bookmarkCount ?? -1);
            if (!Number.isFinite(bc) || bc < 0) {
                return bt('queue.message.skipped-filter-bookmarks-unavailable', '跳过 — 收藏数不可用（无法按附加筛选判定）');
            }
            if (filters.bookmarkMin !== null && bc < filters.bookmarkMin) return bt('queue.message.skipped-filter-bookmarks', '跳过 — 收藏数不符附加筛选');
            if (filters.bookmarkMax !== null && bc > filters.bookmarkMax) return bt('queue.message.skipped-filter-bookmarks', '跳过 — 收藏数不符附加筛选');
        }
        return null;
    }

    async function applyCurrentSearchFilters(options = {}) {
        const filters = normalizeSearchFilters(options.filters || getSearchFiltersFromUI());
        const kind = searchState.kind;
        searchState.currentFilters = filters;
        setSearchFiltersUI(filters);
        saveSearchFilterPrefs(filters);

        const requestSeq = ++searchState.filterSeq;
        const sourceItems = searchState.rawResults || [];
        const bookmarkFilterActive = hasBookmarkFilter(filters);
        const needsBookmarkMeta = bookmarkFilterActive && sourceItems.some(item => {
            if (getInlineSearchBookmarkCount(item) !== null) return false;
            const cached = getCachedSearchMeta(item.id, kind);
            return !cached || !cached.bookmarkResolved;
        });

        if (bookmarkFilterActive && needsBookmarkMeta && sourceItems.length) {
            document.getElementById('search-results-area').innerHTML =
                `<div class="search-spinner"><span class="search-spinner-icon"></span>${esc(bt('status.search-reading-bookmarks', '读取当前页收藏数中...'))}</div>`;
            document.getElementById('btn-add-all').disabled = true;
        }

        if (bookmarkFilterActive) {
            await ensureBookmarkMeta(sourceItems, kind, () => requestSeq !== searchState.filterSeq);
            if (requestSeq !== searchState.filterSeq) return null;
        }

        const stats = {
            rawCount: sourceItems.length,
            filteredCount: 0,
            bookmarkMetaMissing: 0,
            bookmarkFilterActive
        };
        const filtered = sourceItems.filter(item => matchSearchFilters(item, filters, stats, kind));
        stats.filteredCount = filtered.length;

        searchState.results = filtered;
        searchState.filterSummary = stats;

        if (searchState.submode === 'batch') {
            const totalPages = batchLocalPageCount();
            if (searchState.localPage > totalPages) searchState.localPage = totalPages;
            if (searchState.localPage < 1) searchState.localPage = 1;
        }

        renderSearchResults();
        renderSearchPagination();
        document.getElementById('btn-add-all').disabled = searchState.results.length === 0;
        updateBatchQueueButtons();

        if (options.setStatus && searchState.currentWord) {
            const prefix = options.statusPrefix || bt('status.search-filters-applied', '已应用筛选：');
            const parts = [searchState.submode === 'batch'
                ? bt('search.batch.summary.fetched', '已抓取去重 {count} 个', {count: stats.rawCount})
                : bt('search.summary.current-page', '当前页 {count} 个', {count: stats.rawCount})];
            if (hasExtraSearchFilter(filters)) {
                parts.push(bt('search.summary.filtered-count', '筛选后 {count} 个', {count: stats.filteredCount}));
                if (stats.bookmarkMetaMissing > 0) {
                    parts.push(bt(
                        'search.summary.bookmark-missing',
                        '{count} 个收藏数不可用已排除',
                        {count: stats.bookmarkMetaMissing}
                    ));
                }
            } else {
                parts.push(bt('status.search-no-extra-filters', '未启用附加筛选'));
            }
            parts.push(bt('search.summary.pixiv-total', 'Pixiv 总数 {count}', {count: searchState.total.toLocaleString()}));
            setStatus(prefix + (uiLang() === 'en-US' ? ' ' : '') + summaryJoin(parts), 'success');
        }

        return stats;
    }

    // 附加筛选卡片现为 Search / User / 系列三模式共享，实时过滤当前预览页：按当前模式分派。
    async function handleSearchFilterChange() {
        const filters = getSearchFiltersFromUI();
        searchState.currentFilters = filters;
        saveSearchFilterPrefs(filters);
        if (state.mode === 'user') {
            await applyUserFilters({setStatus: true});
            return;
        }
        if (state.mode === 'series') {
            await applySeriesFilters({setStatus: true});
            return;
        }
        if (!searchState.currentWord) return;
        if (searchState.submode === 'batch') searchState.localPage = 1;
        await applyCurrentSearchFilters({setStatus: true});
    }

    async function resetSearchFilters() {
        const filters = defaultSearchFilters();
        searchState.currentFilters = filters;
        setSearchFiltersUI(filters);
        saveSearchFilterPrefs(filters);
        if (state.mode === 'user') {
            await applyUserFilters({setStatus: true});
            return;
        }
        if (state.mode === 'series') {
            await applySeriesFilters({setStatus: true});
            return;
        }
        if (!searchState.currentWord) return;
        if (searchState.submode === 'batch') searchState.localPage = 1;
        await applyCurrentSearchFilters({setStatus: true});
    }

    function getSearchClientModeLabel() {
        if (searchState.currentMode === 'r18') return 'R-18';
        if (searchState.currentMode === 'r18g') return 'R-18G';
        return '';
    }

    function hasPixivCookie() {
        return !!getCookie().trim();
    }

    async function performSearch(page) {
        const word = document.getElementById('search-word').value.trim();
        if (!word) {
            setStatus(bt('alert.search-keyword-required', '请输入搜索关键词'), 'error');
            return;
        }

        const uiMode = (document.getElementById('search-content-filter') || {}).value || 'all';
        const sMode = document.querySelector('input[name="search-smode"]:checked').value;
        const order = document.querySelector('input[name="search-order"]:checked').value;
        const r18Family = uiMode === 'r18' || uiMode === 'r18g' || uiMode === 'r18plus';
        const mode = r18Family ? 'r18' : uiMode;
        const clientFilter = uiMode === 'r18' ? 1 : uiMode === 'r18g' ? 2 : 0;
        const kind = state.settings.searchKind === 'novel' ? 'novel' : 'illust';

        document.getElementById('search-premium-tip').style.display = order === 'popular_d' ? 'block' : 'none';

        cleanupBlobUrls();

        searchState.currentWord = word;
        searchState.currentMode = uiMode;
        searchState.currentOrder = order;
        searchState.currentSMode = sMode;
        searchState.currentPage = page;
        searchState.submode = 'search';
        searchState.batchInfo = null;
        searchState.kind = kind;
        searchState.filterSeq++;

        document.getElementById('search-results-area').innerHTML =
            `<div class="search-spinner"><span class="search-spinner-icon"></span>${esc(bt('status.searching', '搜索中...'))}</div>`;
        document.getElementById('search-pagination').style.display = 'none';
        document.getElementById('btn-add-all').disabled = true;

        try {
            const endpoint = kind === 'novel' ? '/api/pixiv/novel-search' : '/api/pixiv/search';
            const params = new URLSearchParams({word, order, mode, sMode, page});
            const res = await fetch(`${BASE}${endpoint}?${params}`, {headers: pixivHeader()});
            const data = await res.json();
            if (!res.ok) {
                document.getElementById('search-results-area').innerHTML =
                    `<div style="color:#dc3545;text-align:center;padding:24px 0;">${esc(bt('status.search-failed', '搜索失败：{message}', {message: data.error || bt('queue.unknown-error', '未知错误')}))}</div>`;
                return;
            }
            let items = data.items || [];
            const pixivPageCount = items.length;
            if (clientFilter > 0) items = items.filter(it => Number(it.xRestrict ?? 0) === clientFilter);
            searchState.pixivPageCount = pixivPageCount;
            searchState.rawResults = items;
            searchState.results = items;
            searchState.total = data.total || 0;
            searchState.noCookie = !hasPixivCookie();
            const filterStats = await applyCurrentSearchFilters();
            if (!filterStats) return;
            if (clientFilter > 0) {
                const label = clientFilter === 2
                    ? bt('search.content.r18g', 'R-18G ⚠')
                    : bt('search.content.r18', 'R-18 ⚠');
                const parts = [
                    bt('search.summary.r18plus-raw', 'R-18+ 当前页原始 {count} 个', {count: pixivPageCount}),
                    bt('search.summary.client-filtered', '{label} 筛后 {count} 个', {
                        label,
                        count: searchState.rawResults.length
                    })
                ];
                if (hasExtraSearchFilter()) {
                    parts.push(bt('search.summary.extra-filtered', '附加筛选后 {count} 个', {count: searchState.results.length}));
                    if (searchState.filterSummary.bookmarkMetaMissing > 0) {
                        parts.push(bt(
                            'search.summary.bookmark-missing',
                            '{count} 个收藏数不可用已排除',
                            {count: searchState.filterSummary.bookmarkMetaMissing}
                        ));
                    }
                }
                parts.push(bt('search.summary.pixiv-total', 'Pixiv 总数 {count}', {count: searchState.total.toLocaleString()}));
                setStatus(bt('status.search-complete', '搜索完成：{summary}', {summary: summaryJoin(parts)}), 'success');
            } else {
                const parts = [bt('search.summary.current-page', '当前页 {count} 个', {count: searchState.pixivPageCount})];
                if (hasExtraSearchFilter()) {
                    parts.push(bt('search.summary.extra-filtered', '附加筛选后 {count} 个', {count: searchState.results.length}));
                    if (searchState.filterSummary.bookmarkMetaMissing > 0) {
                        parts.push(bt(
                            'search.summary.bookmark-missing',
                            '{count} 个收藏数不可用已排除',
                            {count: searchState.filterSummary.bookmarkMetaMissing}
                        ));
                    }
                }
                parts.push(bt('search.summary.pixiv-total', 'Pixiv 总数 {count}', {count: searchState.total.toLocaleString()}));
                setStatus(bt('status.search-complete', '搜索完成：{summary}', {summary: summaryJoin(parts)}), 'success');
            }
        } catch (e) {
            document.getElementById('search-results-area').innerHTML =
                `<div style="color:#dc3545;text-align:center;padding:24px 0;">${esc(bt('status.request-failed', '请求失败：{message}', {message: e.message}))}</div>`;
        }
    }

    // 批量获取模式下结果可能跨多页：本地按 BATCH_PER_PAGE 分页。
    // base 为当前视图首项在 searchState.results 中的绝对下标，
    // 渲染与队列同步统一使用绝对下标，避免索引错位。
    function batchLocalPageCount() {
        return Math.max(1, Math.ceil(searchState.results.length / BATCH_PER_PAGE));
    }

    function getSearchView() {
        if (searchState.submode !== 'batch') {
            return {items: searchState.results, base: 0, localPage: 1, localPages: 1};
        }
        const localPages = batchLocalPageCount();
        let localPage = searchState.localPage;
        if (!Number.isFinite(localPage) || localPage < 1) localPage = 1;
        if (localPage > localPages) localPage = localPages;
        searchState.localPage = localPage;
        const base = (localPage - 1) * BATCH_PER_PAGE;
        return {
            items: searchState.results.slice(base, base + BATCH_PER_PAGE),
            base,
            localPage,
            localPages
        };
    }

    function renderSearchResults() {
        const area = document.getElementById('search-results-area');
        if (!searchState.rawResults.length) {
            const empty = searchState.kind === 'novel'
                ? bt('novel:batch.search.no-novel-results', '无小说搜索结果')
                : bt('status.search-no-results', '无搜索结果');
            area.innerHTML = `<div style="color:#aaa;text-align:center;padding:24px 0;">${esc(empty)}</div>`;
            return;
        }
        if (!searchState.results.length) {
            const tips = [];
            tips.push(bt('search.summary.pixiv-current-page-results', 'Pixiv 当前页 {count} 个结果', {count: searchState.rawResults.length}));
            if (searchState.filterSummary.bookmarkFilterActive && searchState.filterSummary.bookmarkMetaMissing > 0) {
                tips.push(bt(
                    'search.summary.bookmark-missing',
                    '{count} 个收藏数不可用已排除',
                    {count: searchState.filterSummary.bookmarkMetaMissing}
                ));
            }
            area.innerHTML = `<div style="color:#aaa;text-align:center;padding:24px 0;">${esc(bt('status.search-no-filtered-results', '附加筛选后无结果'))}<br><span style="font-size:12px;">${tips.map(t => `<span>${esc(t)}</span>`).join(summarySeparator())}</span></div>`;
            return;
        }
        const view = getSearchView();
        if (searchState.kind === 'novel') {
            renderNovelSearchResults(area, view);
            return;
        }
        const inQueue = new Set(state.queue.map(q => q.id));
        const clientModeLabel = getSearchClientModeLabel();
        const summary = searchState.submode === 'batch'
            ? [batchSummaryText(view)]
            : [
                bt('search.summary.total-results', '共 {count} 个结果', {count: searchState.total.toLocaleString()}),
                bt('search.summary.current-page-index', '当前第 {page} 页', {page: searchState.currentPage}),
                bt('search.summary.pixiv-returned', 'Pixiv 返回 {count} 个', {count: searchState.pixivPageCount})
            ];
        if (clientModeLabel) {
            summary.push(bt('search.summary.client-filtered', '{label} 筛后 {count} 个', {
                label: clientModeLabel,
                count: searchState.rawResults.length
            }));
        }
        if (hasExtraSearchFilter()) {
            summary.push(bt('search.summary.extra-filtered', '附加筛选后 {count} 个', {count: searchState.results.length}));
            if (searchState.filterSummary.bookmarkMetaMissing > 0) {
                summary.push(bt(
                    'search.summary.bookmark-missing',
                    '{count} 个收藏数不可用已排除',
                    {count: searchState.filterSummary.bookmarkMetaMissing}
                ));
            }
        }
        area.innerHTML = `
    ${searchState.noCookie ? `<div style="font-size:12px;color:#e6a700;margin-bottom:8px;">${esc(bt('status.search-no-cookie-warning', '⚠ 未保存 Cookie，搜索结果可能减少'))}</div>` : ''}
    <div style="font-size:12px;color:#888;margin-bottom:10px;">
      ${summary.map(s => `<span>${esc(s)}</span>`).join(summarySeparator())}
    </div>
    <div class="search-grid">
      ${view.items.map((item, i) => {
            const idx = view.base + i;
            const xr = Number(item.xRestrict ?? 0);
            const illustType = Number(item.illustType ?? 0);
            const isR18 = xr >= 1;
            const isAi = Number(item.aiType ?? 0) >= 2;
            const bookmarkCount = getSearchBookmarkCount(item);
            const blurClass = (isR18 && searchState.blurR18) ? 'blur-r18' : '';
            const r18Badge = xr === 2
                ? '<span class="thumb-badge" style="background:#b91c1c;">R-18G</span>'
                : xr === 1 ? '<span class="thumb-badge">R-18</span>' : '';
            const aiBadge = isAi ? '<span class="thumb-badge thumb-badge-ai">AI</span>' : '';
            const typeBadge = illustType === 2
                ? `<span class="thumb-badge" style="background:#0ea5e9;">${esc(bt('search.type.ugoira', '动图'))}</span>`
                : illustType === 1 ? `<span class="thumb-badge" style="background:#f59e0b;">${esc(bt('search.type.manga', '漫画'))}</span>` : '';
            const pagesLabel = item.pageCount > 1 ? `<span class="thumb-pages">${item.pageCount}P</span>` : '';
            const inQueueClass = inQueue.has(item.id) ? ' in-queue' : '';
            const queueTip = buildQueueToggleTip(inQueue.has(item.id));
            const bookmarkTip = buildBookmarkTip(bookmarkCount);
            return `<div class="search-thumb${inQueueClass}" id="thumb-${idx}"
                     onclick="addSearchItemToQueue(${idx})" title="${esc(item.title)} (${esc(item.userName)})${bookmarkTip}${queueTip}">
          <img id="thumb-img-${idx}" src="" alt="${esc(item.title)}" class="${blurClass}">
          <div class="thumb-badge-stack">${r18Badge}${aiBadge}${typeBadge}</div>
          ${pagesLabel}
          <span class="thumb-in-queue-mark">✓</span>
          <div class="thumb-title">${esc(item.title)}</div>
        </div>`;
        }).join('')}
    </div>`;
        loadThumbnailsBatched(view.items, view.base);
    }

    function batchSummaryText(view) {
        const parts = [
            bt('search.batch.summary.fetched', '已抓取去重 {count} 个', {count: searchState.rawResults.length})
        ];
        if (hasExtraSearchFilter()) {
            parts.push(bt('search.summary.extra-filtered', '附加筛选后 {count} 个', {count: searchState.results.length}));
            if (searchState.filterSummary.bookmarkMetaMissing > 0) {
                parts.push(bt('search.summary.bookmark-missing', '{count} 个收藏数不可用已排除',
                    {count: searchState.filterSummary.bookmarkMetaMissing}));
            }
        }
        parts.push(bt('search.batch.summary.local-page', '本地第 {page}/{total} 页',
            {page: view.localPage, total: view.localPages}));
        if (searchState.batchInfo) {
            parts.push(bt('search.batch.summary.range', '范围第 {start}–{end} 页',
                {start: searchState.batchInfo.startPage, end: searchState.batchInfo.endPage}));
        }
        return summaryJoin(parts);
    }

    async function loadThumbnailsBatched(items, base = 0) {
        const BATCH = 10;
        for (let i = 0; i < items.length; i += BATCH) {
            const batch = items.slice(i, i + BATCH);
            await Promise.allSettled(batch.map((item, offset) => loadSingleThumbnail(item, base + i + offset)));
        }
    }

    function renderNovelSearchResults(area, view) {
        view = view || getSearchView();
        const inQueue = new Set(state.queue.map(q => q.id));
        const summary = searchState.submode === 'batch'
            ? [batchSummaryText(view)]
            : [
                bt('search.summary.total-results', '共 {count} 个结果', {count: searchState.total.toLocaleString()}),
                bt('search.summary.current-page-index', '当前第 {page} 页', {page: searchState.currentPage}),
                bt('search.summary.pixiv-returned', 'Pixiv 返回 {count} 个', {count: searchState.pixivPageCount})
            ];
        if (searchState.submode !== 'batch' && hasExtraSearchFilter()) {
            summary.push(bt('search.summary.extra-filtered', '附加筛选后 {count} 个', {count: searchState.results.length}));
        }
        const cards = view.items.map((item, i) => {
            const idx = view.base + i;
            const xr = Number(item.xRestrict ?? 0);
            const isAi = Number(item.aiType ?? 0) >= 2;
            const wc = Number(item.wordCount ?? item.textLength ?? 0);
            const bookmarkCount = getSearchBookmarkCount(item);
            const queueId = 'n' + String(item.id);
            const inQueueClass = inQueue.has(queueId) ? ' in-queue' : '';
            const meta = [];
            if (xr === 1) meta.push('<span class="nsc-r18">R-18</span>');
            else if (xr === 2) meta.push('<span class="nsc-r18g">R-18G</span>');
            if (isAi) meta.push('<span class="nsc-ai">AI</span>');
            if (item.isOriginal) meta.push(`<span class="nsc-original">${esc(bt('novel:batch.search.original', '原创'))}</span>`);
            if (wc > 0) meta.push(`<span>${esc(bt('novel:batch.search.summary.novel-words', '{count} 字', {count: wc.toLocaleString()}))}</span>`);
            if (bookmarkCount !== null) {
                meta.push(`<span>${esc(bt('search.summary.bookmark-badge', '收藏 {count}', {count: bookmarkCount.toLocaleString()}))}</span>`);
            }
            const fallbackTitle = bt('novel:status.unknown-novel', '小说 {id}', {id: item.id});
            const fallbackAuthor = bt('novel:status.unknown-author', '未知');
            const bookmarkTip = buildBookmarkTip(bookmarkCount);
            const queueTip = buildQueueToggleTip(inQueue.has(queueId));
            const cardTitle = `${item.title || fallbackTitle} (${item.userName || fallbackAuthor})${bookmarkTip}${queueTip}`;
            return `<div class="novel-search-card${inQueueClass}" data-novel-idx="${idx}" title="${esc(cardTitle)}">
        <div class="nsc-title">${esc(item.title || fallbackTitle)}</div>
        <div class="nsc-author">${esc(item.userName || fallbackAuthor)}</div>
        <div class="nsc-meta">${meta.join('')}</div>
        <span class="nsc-in-queue-mark">✓</span>
      </div>`;
        }).join('');
        area.innerHTML = `
    ${searchState.noCookie ? `<div style="font-size:12px;color:#e6a700;margin-bottom:8px;">${esc(bt('status.search-no-cookie-warning', '⚠ 未保存 Cookie，搜索结果可能减少'))}</div>` : ''}
    <div style="font-size:12px;color:#888;margin-bottom:10px;">
      ${summary.map(s => `<span>${esc(s)}</span>`).join(summarySeparator())}
    </div>
    <div class="novel-search-grid">${cards}</div>`;
        area.querySelectorAll('.novel-search-card').forEach(card => {
            card.addEventListener('click', () => addSearchItemToQueue(Number(card.dataset.novelIdx)));
        });
    }

    // 通用缩略图代理拉取：返回 blob URL（同时登记到 blobStore 供之后统一 revoke），失败返回 null。
    async function fetchThumbnailBlobUrl(url, blobStore) {
        if (!url) return null;
        try {
            const params = new URLSearchParams({url});
            const res = await fetch(`${BASE}/api/pixiv/thumbnail-proxy?${params}`, {headers: pixivHeader()});
            if (!res.ok) return null;
            const blob = await res.blob();
            const blobUrl = URL.createObjectURL(blob);
            if (Array.isArray(blobStore)) blobStore.push(blobUrl);
            return blobUrl;
        } catch {
            return null;
        }
    }

    async function loadSingleThumbnail(item, idx) {
        if (!item.thumbnailUrl) return;
        const imgEl = document.getElementById(`thumb-img-${idx}`);
        if (!imgEl) return;
        try {
            const params = new URLSearchParams({url: item.thumbnailUrl});
            const res = await fetch(`${BASE}/api/pixiv/thumbnail-proxy?${params}`, {headers: pixivHeader()});
            if (!res.ok) return;
            const blob = await res.blob();
            const blobUrl = URL.createObjectURL(blob);
            searchState.activeBlobUrls.push(blobUrl);
            if (imgEl.isConnected) imgEl.src = blobUrl;
        } catch {
        }
    }

    function cleanupBlobUrls() {
        searchState.activeBlobUrls.forEach(u => {
            try {
                URL.revokeObjectURL(u);
            } catch {
            }
        });
        searchState.activeBlobUrls = [];
    }

    function syncSearchResultsQueueState() {
        if (!searchState.results.length) return;
        const inQueue = new Set(state.queue.map(q => q.id));
        if (searchState.kind === 'novel') {
            // 批量模式下仅渲染当前本地页，按绝对下标定位卡片
            searchState.results.forEach((item, idx) => {
                const el = document.querySelector(`.novel-search-card[data-novel-idx="${idx}"]`);
                if (!el) return;
                el.classList.toggle('in-queue', inQueue.has('n' + String(item.id)));
            });
            return;
        }
        searchState.results.forEach((item, idx) => {
            const el = document.getElementById(`thumb-${idx}`);
            if (!el) return;
            const isInQueue = inQueue.has(item.id);
            const bookmarkCount = getSearchBookmarkCount(item);
            const bookmarkTip = buildBookmarkTip(bookmarkCount);
            el.classList.toggle('in-queue', isInQueue);
            el.title = `${item.title} (${item.userName || ''})${bookmarkTip}${buildQueueToggleTip(isInQueue)}`;
        });
    }

    function removeFromQueue(id) {
        const idx = state.queue.findIndex(q => q.id === String(id));
        if (idx === -1) return false;
        const q = state.queue[idx];
        if (q.status === 'downloading') return false;
        state.queue.splice(idx, 1);
        updateStats();
        saveQueue();
        renderQueue();
        syncSearchResultsQueueState();
        syncSeriesResultsQueueState();
        syncUserResultsQueueState();
        return true;
    }

    function addSearchItemToQueue(idx) {
        const item = searchState.results[idx];
        if (!item) return;
        const isNovel = searchState.kind === 'novel';
        const queueId = isNovel ? 'n' + String(item.id) : String(item.id);
        const alreadyInQueue = state.queue.find(q => q.id === queueId);
        if (alreadyInQueue) {
            const removed = removeFromQueue(queueId);
            if (removed) {
                setStatus(bt('status.removed-from-queue', '已从队列移除：{title}', {title: item.title}), 'info');
            } else {
                setStatus(bt('status.cannot-remove-downloading', '无法移除（正在下载中）：{title}', {title: item.title}), 'warning');
            }
            return;
        }
        const meta = isNovel
            ? {
                title: item.title,
                novelId: String(item.id),
                kind: 'novel',
                authorId: item.userId ? Number(item.userId) : null,
                authorName: item.userName || '',
                isAi: Number(item.aiType ?? 0) >= 2,
                xRestrict: Number(item.xRestrict ?? 0)
            }
            : {
                title: item.title,
                authorId: item.userId,
                authorName: item.userName,
                isAi: Number(item.aiType ?? 0) >= 2
            };
        const added = addItemsToQueue([queueId], [meta], isNovel ? 'search-novel' : 'search', '');
        setStatus(added > 0
                ? bt('status.added-to-queue', '已加入队列：{title}', {title: item.title})
                : bt('status.already-in-queue', '已在队列中：{title}', {title: item.title}),
            added > 0 ? 'success' : 'info');
    }

    /* ============================================================
       Series mode
    ============================================================ */
    const seriesState = {
        kind: 'illust',
        seriesId: null,
        seriesTitle: '',
        seriesAuthorId: null,
        seriesAuthorName: '',
        seriesTotal: 0,
        currentPage: 1,
        totalPages: 1,
        isLastPage: true,
        rawItems: [],   // 当前页未过滤的成员（itemsByPage 缓存的也是原始未过滤数据）
        items: [],      // 当前页经附加筛选后的成员（渲染 / 「加入当前页」据此）
        allItems: [],
        itemsByPage: new Map(),
        filterSummary: {rawCount: 0, filteredCount: 0, bookmarkMetaMissing: 0, bookmarkFilterActive: false},
        filterSeq: 0,
        renderToken: 0,
        activeBlobUrls: []
    };

    function parseSeriesInputUrl(text) {
        if (!text) return null;
        const trimmed = text.trim();
        const novelSeriesMatch = trimmed.match(/\/novel\/series\/(\d+)/);
        if (novelSeriesMatch) {
            return {kind: 'novel-series', seriesId: Number(novelSeriesMatch[1])};
        }
        const novelMatch = trimmed.match(/\/novel\/show\.php\?[^\s]*?\bid=(\d+)/);
        if (novelMatch) {
            return {kind: 'novel', novelId: novelMatch[1]};
        }
        const seriesMatch = trimmed.match(/\/user\/(\d+)\/series\/(\d+)/);
        if (seriesMatch) {
            return {kind: 'illust-series', seriesId: Number(seriesMatch[2])};
        }
        const artworkMatch = trimmed.match(/\/artworks\/(\d+)/);
        if (artworkMatch) {
            return {kind: 'artwork', artworkId: artworkMatch[1]};
        }
        if (/^\d+$/.test(trimmed)) {
            return {kind: 'illust-series', seriesId: Number(trimmed)};
        }
        return null;
    }

    async function resolveSeriesIdFromArtwork(artworkId) {
        const res = await fetch(`${BASE}/api/pixiv/artwork/${encodeURIComponent(artworkId)}/meta`,
            {credentials: 'same-origin', headers: pixivHeader()});
        if (!res.ok) {
            const data = await res.json().catch(() => ({}));
            throw new Error(data.error || `HTTP ${res.status}`);
        }
        const meta = await res.json();
        if (!meta.seriesId) {
            throw new Error(bt('status.series-artwork-no-series', '该作品不属于任何漫画系列'));
        }
        return Number(meta.seriesId);
    }

    async function resolveSeriesIdFromNovel(novelId) {
        const res = await fetch(`${BASE}/api/pixiv/novel/${encodeURIComponent(novelId)}/meta`,
            {credentials: 'same-origin', headers: pixivHeader()});
        if (!res.ok) {
            const data = await res.json().catch(() => ({}));
            throw new Error(data.error || `HTTP ${res.status}`);
        }
        const meta = await res.json();
        if (!meta.seriesId) {
            throw new Error(bt('status.series-novel-no-series', '该小说不属于任何小说系列'));
        }
        return Number(meta.seriesId);
    }

    function resetSeriesState(kind = 'illust') {
        cleanupSeriesBlobUrls();
        seriesState.kind = kind;
        seriesState.seriesId = null;
        seriesState.seriesTitle = '';
        seriesState.seriesAuthorId = null;
        seriesState.seriesAuthorName = '';
        seriesState.seriesTotal = 0;
        seriesState.currentPage = 1;
        seriesState.totalPages = 1;
        seriesState.isLastPage = true;
        seriesState.rawItems = [];
        seriesState.items = [];
        seriesState.allItems = [];
        seriesState.itemsByPage = new Map();
        seriesState.filterSummary = {rawCount: 0, filteredCount: 0, bookmarkMetaMissing: 0, bookmarkFilterActive: false};
        seriesState.filterSeq += 1;
        seriesState.renderToken += 1;
        updateSeriesQueueButtons();
        renderSeriesPagination();
        applyNovelSettingsVisibility();
    }

    function cleanupSeriesBlobUrls() {
        seriesState.activeBlobUrls.forEach(u => {
            try { URL.revokeObjectURL(u); } catch {}
        });
        seriesState.activeBlobUrls = [];
    }

    function getSeriesPageSize(kind = seriesState.kind) {
        return kind === 'novel' ? 30 : 12;
    }

    function getSeriesFallbackOrder(idx) {
        return (Math.max(1, seriesState.currentPage) - 1) * getSeriesPageSize() + idx + 1;
    }

    function buildSeriesApiPath(seriesId, page, kind = seriesState.kind) {
        return kind === 'novel'
            ? `/api/pixiv/novel/series/${encodeURIComponent(seriesId)}?page=${page}`
            : `/api/pixiv/series/${encodeURIComponent(seriesId)}?page=${page}`;
    }

    function dedupeSeriesItems(items) {
        const seen = new Set();
        const result = [];
        for (const item of Array.isArray(items) ? items : []) {
            const itemId = String(item.id || '');
            if (!itemId || seen.has(itemId)) continue;
            seen.add(itemId);
            result.push(item);
        }
        return result;
    }

    function updateSeriesMetaFromResponse(meta) {
        if (!meta) return;
        seriesState.seriesTitle = meta.title || String(seriesState.seriesId || '');
        seriesState.seriesAuthorId = meta.authorId ?? null;
        seriesState.seriesAuthorName = meta.authorName || '';
        seriesState.seriesTotal = Number(meta.total || 0);
        if (seriesState.seriesTotal > 0) {
            seriesState.totalPages = Math.max(1, Math.ceil(seriesState.seriesTotal / getSeriesPageSize()));
        }
    }

    function rebuildSeriesAllItems() {
        const seen = new Set();
        const allItems = [];
        Array.from(seriesState.itemsByPage.keys())
            .sort((a, b) => a - b)
            .forEach(page => {
                for (const item of seriesState.itemsByPage.get(page) || []) {
                    const itemId = String(item.id || '');
                    if (!itemId || seen.has(itemId)) continue;
                    seen.add(itemId);
                    allItems.push(item);
                }
            });
        seriesState.allItems = allItems;
    }

    function cacheSeriesPageData(data, requestedPage, activate = true) {
        const responsePage = Number(data.page || requestedPage);
        const page = Number.isFinite(responsePage) && responsePage > 0 ? responsePage : requestedPage;
        const items = dedupeSeriesItems(data.items || []);
        updateSeriesMetaFromResponse(data.series);
        if (seriesState.seriesTotal <= 0) {
            seriesState.totalPages = data.isLastPage
                ? page
                : Math.max(seriesState.totalPages || 1, page + 1);
        }
        seriesState.itemsByPage.set(page, items);
        if (activate) {
            seriesState.currentPage = page;
            seriesState.isLastPage = !!data.isLastPage;
            seriesState.rawItems = items;
        }
        rebuildSeriesAllItems();
    }

    async function fetchSeriesPage(page) {
        const apiPath = buildSeriesApiPath(seriesState.seriesId, page);
        const res = await fetch(`${BASE}${apiPath}`,
            {credentials: 'same-origin', headers: pixivHeader()});
        if (!res.ok) {
            const data = await res.json().catch(() => ({}));
            throw new Error(data.error || `HTTP ${res.status}`);
        }
        return await res.json();
    }

    function setSeriesLoading(message) {
        document.getElementById('series-results-area').innerHTML =
            `<div style="color:#888;text-align:center;padding:24px 0;">${esc(message)}</div>`;
        updateSeriesQueueButtons(true);
    }

    function updateSeriesQueueButtons(isLoading = false) {
        const pageBtn = document.getElementById('btn-series-add-page');
        const allBtn = document.getElementById('btn-series-add-all');
        if (pageBtn) pageBtn.disabled = isLoading || seriesState.items.length === 0;
        if (allBtn) allBtn.disabled = isLoading || !seriesState.seriesId || (seriesState.items.length === 0 && seriesState.allItems.length === 0);
    }

    async function loadSeriesPreviewPage(page) {
        if (!seriesState.seriesId) return;
        const numericPage = Number(page);
        let safePage = Number.isFinite(numericPage) ? Math.max(1, Math.floor(numericPage)) : 1;
        if (seriesState.totalPages > 0) {
            safePage = Math.min(safePage, seriesState.totalPages);
        }
        cleanupSeriesBlobUrls();
        if (seriesState.itemsByPage.has(safePage)) {
            seriesState.currentPage = safePage;
            seriesState.rawItems = seriesState.itemsByPage.get(safePage) || [];
            await applySeriesFilters({});
            renderSeriesPagination();
            updateSeriesQueueButtons();
            return;
        }
        setSeriesLoading(bt('status.series-page-loading', '正在加载第 {page} 页...', {page: safePage}));
        try {
            const data = await fetchSeriesPage(safePage);
            cacheSeriesPageData(data, safePage);
            await applySeriesFilters({});
            renderSeriesPagination();
            updateSeriesQueueButtons();
            setStatus(bt('status.series-page-load-success', '系列页已加载：{title}（第 {page} / {total} 页）', {
                title: seriesState.seriesTitle,
                page: seriesState.currentPage,
                total: seriesState.totalPages
            }), 'success');
        } catch (e) {
            document.getElementById('series-results-area').innerHTML =
                `<div style="color:#dc3545;text-align:center;padding:24px 0;">${esc(bt('status.series-load-failed', '加载失败：{message}', {message: e.message}))}</div>`;
            updateSeriesQueueButtons();
            setStatus(bt('status.series-load-failed', '加载失败：{message}', {message: e.message}), 'error');
        }
    }

    async function loadSeriesPreview() {
        const input = document.getElementById('series-input-url');
        const parsed = parseSeriesInputUrl(input.value);
        if (!parsed) {
            setStatus(bt('status.series-url-invalid', '请输入有效的系列 URL 或同系列作品 URL'), 'error');
            return;
        }
        const nextKind = parsed.kind === 'novel' || parsed.kind === 'novel-series' ? 'novel' : 'illust';
        resetSeriesState(nextKind);
        document.getElementById('series-results-area').innerHTML =
            `<div style="color:#888;text-align:center;padding:24px 0;">${esc(bt('status.series-loading', '正在加载系列信息...'))}</div>`;
        document.getElementById('series-meta-display').textContent = '';
        updateSeriesQueueButtons(true);
        try {
            let seriesId;
            if (parsed.kind === 'novel') {
                seriesId = await resolveSeriesIdFromNovel(parsed.novelId);
            } else if (parsed.kind === 'novel-series') {
                seriesId = parsed.seriesId;
            } else if (parsed.kind === 'artwork') {
                seriesId = await resolveSeriesIdFromArtwork(parsed.artworkId);
            } else {
                seriesId = parsed.seriesId;
            }
            seriesState.kind = nextKind;
            seriesState.seriesId = seriesId;
            seriesState.seriesTitle = String(seriesId);
            await loadSeriesPreviewPage(1);
            applyNovelSettingsVisibility();
            // 系列类型确定后，刷新共享「附加筛选」里页数/字数字段的显隐
            updateExtraFiltersCardVisibility();
        } catch (e) {
            document.getElementById('series-results-area').innerHTML =
                `<div style="color:#dc3545;text-align:center;padding:24px 0;">${esc(bt('status.series-load-failed', '加载失败：{message}', {message: e.message}))}</div>`;
            renderSeriesPagination();
            updateSeriesQueueButtons();
            setStatus(bt('status.series-load-failed', '加载失败：{message}', {message: e.message}), 'error');
        }
    }

    // 系列预览的实时附加筛选：对当前页 rawItems 套同一套 matchSearchFilters，结果写回 items 再渲染。
    async function applySeriesFilters(options = {}) {
        const filters = normalizeSearchFilters(options.filters || getSearchFiltersFromUI());
        searchState.currentFilters = filters;
        saveSearchFilterPrefs(filters);
        const kind = seriesState.kind;
        const seq = ++seriesState.filterSeq;
        const isStale = () => seq !== seriesState.filterSeq;

        const bookmarkActive = hasBookmarkFilter(filters);
        const needsBookmarkMeta = bookmarkActive && (seriesState.rawItems || []).some(item => {
            if (getInlineSearchBookmarkCount(item) !== null) return false;
            const cached = getCachedSearchMeta(item.id, kind);
            return !cached || !cached.bookmarkResolved;
        });
        if (bookmarkActive && needsBookmarkMeta && (seriesState.rawItems || []).length) {
            const area = document.getElementById('series-results-area');
            if (area) area.innerHTML = `<div class="search-spinner"><span class="search-spinner-icon"></span>${esc(bt('status.search-reading-bookmarks', '读取当前页收藏数中...'))}</div>`;
            updateSeriesQueueButtons(true);
        }

        const result = await computeFilteredItems(seriesState.rawItems, filters, kind, isStale);
        if (!result) return null;
        seriesState.items = result.filtered;
        seriesState.filterSummary = result.stats;
        renderSeriesResults();
        updateSeriesQueueButtons();

        if (options.setStatus) {
            const parts = [bt('search.summary.current-page', '当前页 {count} 个', {count: result.stats.rawCount})];
            if (hasExtraSearchFilter(filters)) {
                parts.push(bt('search.summary.extra-filtered', '附加筛选后 {count} 个', {count: result.stats.filteredCount}));
                if (result.stats.bookmarkMetaMissing > 0) {
                    parts.push(bt('search.summary.bookmark-missing', '{count} 个收藏数不可用已排除', {count: result.stats.bookmarkMetaMissing}));
                }
            } else {
                parts.push(bt('status.search-no-extra-filters', '未启用附加筛选'));
            }
            setStatus(bt('status.search-filters-applied', '已应用筛选：') + (uiLang() === 'en-US' ? ' ' : '') + summaryJoin(parts), 'success');
        }
        return result.stats;
    }

    function renderSeriesResults() {
        const area = document.getElementById('series-results-area');
        const metaEl = document.getElementById('series-meta-display');
        const renderToken = ++seriesState.renderToken;
        if (!seriesState.rawItems.length) {
            area.innerHTML = `<div style="color:#aaa;text-align:center;padding:24px 0;">${esc(bt('status.series-no-results', '该系列没有可用条目'))}</div>`;
            metaEl.textContent = '';
            renderSeriesPagination();
            updateSeriesQueueButtons();
            applyNovelSettingsVisibility();
            return;
        }
        const isNovel = seriesState.kind === 'novel';
        const inQueue = new Set(state.queue.map(q => q.id));
        const authorPart = seriesState.seriesAuthorName
            ? bt('series.meta.author', '作者：{name}', {name: seriesState.seriesAuthorName})
            : '';
        const typePart = isNovel
            ? bt('series.meta.type-novel', '小说系列')
            : bt('series.meta.type-manga', '漫画系列');
        const pagePart = seriesState.totalPages > 1
            ? bt('series.meta.page', '第 {current} / {total} 页', {
                current: seriesState.currentPage,
                total: seriesState.totalPages
            })
            : '';
        metaEl.innerHTML = [
            `<strong>${esc(seriesState.seriesTitle)}</strong>`,
            esc(typePart),
            authorPart ? esc(authorPart) : '',
            esc(bt('series.meta.total', '共 {count} 个作品', {count: seriesState.seriesTotal || seriesState.allItems.length || seriesState.rawItems.length})),
            pagePart ? esc(pagePart) : ''
        ].filter(Boolean).join(' · ');
        // 当前页原始非空、但附加筛选后为空：给出明确提示而非空白网格
        if (!seriesState.items.length) {
            const tips = [bt('search.summary.pixiv-current-page-results', 'Pixiv 当前页 {count} 个结果', {count: seriesState.rawItems.length})];
            if (seriesState.filterSummary.bookmarkFilterActive && seriesState.filterSummary.bookmarkMetaMissing > 0) {
                tips.push(bt('search.summary.bookmark-missing', '{count} 个收藏数不可用已排除', {count: seriesState.filterSummary.bookmarkMetaMissing}));
            }
            area.innerHTML = `<div style="color:#aaa;text-align:center;padding:24px 0;">${esc(bt('status.search-no-filtered-results', '附加筛选后无结果'))}<br><span style="font-size:12px;">${tips.map(t => `<span>${esc(t)}</span>`).join(summarySeparator())}</span></div>`;
            renderSeriesPagination();
            updateSeriesQueueButtons();
            applyNovelSettingsVisibility();
            return;
        }
        if (isNovel) {
            area.innerHTML = `
    <div class="novel-series-list">
      ${seriesState.items.map((item, idx) => {
                const xr = Number(item.xRestrict ?? 0);
                const isAi = Number(item.aiType ?? 0) >= 2;
                const wc = Number(item.wordCount ?? item.textLength ?? 0);
                const seriesOrder = Number(item.seriesOrder || getSeriesFallbackOrder(idx));
                const queueId = getSeriesQueueId(item);
                const inQueueClass = inQueue.has(queueId) ? ' in-queue' : '';
                const meta = [];
                if (xr === 1) meta.push('<span class="nsc-r18">R-18</span>');
                else if (xr === 2) meta.push('<span class="nsc-r18g">R-18G</span>');
                if (isAi) meta.push('<span class="nsc-ai">AI</span>');
                const uploadText = formatNovelUploadDate(item.uploadTimestamp);
                if (uploadText) meta.push(`<span>${esc(uploadText)}</span>`);
                if (wc > 0) meta.push(`<span>${esc(bt('novel:batch.search.summary.novel-words', '{count} 字', {count: wc.toLocaleString()}))}</span>`);
                const readingText = formatNovelReadingTime(item.readingTimeSeconds);
                if (readingText) meta.push(`<span>${esc(readingText)}</span>`);
                const fallbackTitle = bt('queue.novel-fallback', '小说 {id}', {id: item.id});
                const fallbackAuthor = bt('novel:status.unknown-author', '未知');
                const queueTip = buildQueueToggleTip(inQueue.has(queueId));
                const tagsHtml = renderSeriesNovelTags(item.tags || []);
                return `<div class="novel-series-card${inQueueClass}" id="series-novel-card-${idx}"
                         onclick="addSeriesItemToQueue(${idx})" title="#${seriesOrder} ${esc(item.title || fallbackTitle)} (${esc(item.userName || fallbackAuthor)})${queueTip}">
          <div class="novel-series-cover" id="series-novel-cover-${idx}"><span>${esc(bt('series.cover-placeholder', '封面'))}</span></div>
          <div class="novel-series-info">
            <div class="novel-series-title">#${seriesOrder} ${esc(item.title || fallbackTitle)}</div>
            <div class="nsc-author">${esc(item.userName || fallbackAuthor)}</div>
            <div class="novel-series-tags">${tagsHtml}</div>
            <div class="novel-series-meta">${meta.join('')}</div>
          </div>
          <span class="nsc-in-queue-mark">✓</span>
        </div>`;
            }).join('')}
    </div>`;
            loadNovelSeriesCoversBatched(seriesState.items, renderToken);
            renderSeriesPagination();
            updateSeriesQueueButtons();
            applyNovelSettingsVisibility();
            return;
        }
        area.innerHTML = `
    <div class="search-grid">
      ${seriesState.items.map((item, idx) => {
            const xr = Number(item.xRestrict ?? 0);
            const isAi = Number(item.aiType ?? 0) >= 2;
            const r18Badge = xr === 2
                ? '<span class="thumb-badge" style="background:#b91c1c;">R-18G</span>'
                : xr === 1 ? '<span class="thumb-badge">R-18</span>' : '';
            const aiBadge = isAi ? '<span class="thumb-badge thumb-badge-ai">AI</span>' : '';
            const seriesOrder = Number(item.seriesOrder || getSeriesFallbackOrder(idx));
            const orderBadge = `<span class="thumb-badge" style="background:#6366f1;">#${seriesOrder}</span>`;
            const pagesLabel = item.pageCount > 1 ? `<span class="thumb-pages">${item.pageCount}P</span>` : '';
            const inQueueClass = inQueue.has(item.id) ? ' in-queue' : '';
            const queueTip = buildQueueToggleTip(inQueue.has(item.id));
            return `<div class="search-thumb${inQueueClass}" id="series-thumb-${idx}"
                     onclick="addSeriesItemToQueue(${idx})" title="#${seriesOrder} ${esc(item.title)} (${esc(item.userName)})${queueTip}">
          <img id="series-thumb-img-${idx}" src="" alt="${esc(item.title)}">
          <div class="thumb-badge-stack">${orderBadge}${r18Badge}${aiBadge}</div>
          ${pagesLabel}
          <span class="thumb-in-queue-mark">✓</span>
          <div class="thumb-title">${esc(item.title)}</div>
        </div>`;
        }).join('')}
    </div>`;
        loadSeriesThumbnailsBatched(seriesState.items, renderToken);
        renderSeriesPagination();
        updateSeriesQueueButtons();
        applyNovelSettingsVisibility();
    }

    function renderSeriesPagination() {
        const pag = document.getElementById('series-pagination');
        if (!pag) return;
        const totalPages = Math.max(1, Number(seriesState.totalPages || 1));
        const cur = Math.min(Math.max(1, Number(seriesState.currentPage || 1)), totalPages);
        if (!seriesState.seriesId || totalPages <= 1) {
            pag.style.display = 'none';
            pag.innerHTML = '';
            return;
        }
        pag.style.display = 'flex';
        const radius = 3;
        const pages = [];
        for (let p = Math.max(1, cur - radius); p <= Math.min(totalPages, cur + radius); p++) {
            pages.push(p);
        }
        pag.innerHTML =
            `<button onclick="loadSeriesPreviewPage(1)" ${cur === 1 ? 'disabled' : ''}>&laquo;</button>` +
            `<button onclick="loadSeriesPreviewPage(${cur - 1})" ${cur === 1 ? 'disabled' : ''}>&lsaquo;</button>` +
            pages.map(p =>
                `<button onclick="${p === cur ? '' : `loadSeriesPreviewPage(${p})`}" ${p === cur ? 'class="pg-active" disabled' : ''}>${p}</button>`
            ).join('') +
            `<button onclick="loadSeriesPreviewPage(${cur + 1})" ${cur === totalPages ? 'disabled' : ''}>&rsaquo;</button>` +
            `<button onclick="loadSeriesPreviewPage(${totalPages})" ${cur === totalPages ? 'disabled' : ''}>&raquo;</button>` +
            `<span class="pg-info">${esc(bt('series.pagination.info', '第 {current} / {total} 页 · 共 {count} 个', {
                current: cur,
                total: totalPages,
                count: (seriesState.seriesTotal || seriesState.allItems.length || seriesState.items.length).toLocaleString()
            }))}</span>`;
    }

    function renderSeriesNovelTags(tags) {
        if (!Array.isArray(tags) || tags.length === 0) {
            return `<span>${esc(bt('series.tags-empty', '无标签'))}</span>`;
        }
        return tags.slice(0, 8).map(tag => {
            const name = typeof tag === 'string' ? tag : (tag.name || tag.tag || '');
            const translated = typeof tag === 'object' && tag ? (tag.translatedName || tag.translation || '') : '';
            const label = translated ? `${name} / ${translated}` : name;
            return label ? `<span>${esc(label)}</span>` : '';
        }).join('');
    }

    function formatNovelUploadDate(timestamp) {
        const value = Number(timestamp || 0);
        if (!Number.isFinite(value) || value <= 0) return '';
        const d = new Date(value);
        if (Number.isNaN(d.getTime())) return '';
        const text = d.toLocaleDateString(uiLang() === 'en-US' ? 'en-US' : 'zh-CN', {
            year: 'numeric',
            month: '2-digit',
            day: '2-digit'
        });
        return bt('series.meta.upload', '上传：{time}', {time: text});
    }

    function formatNovelReadingTime(seconds) {
        const text = formatNovelReadingDuration(seconds);
        return text ? bt('series.meta.reading-time', '预计阅读：{time}', {time: text}) : '';
    }

    function formatNovelReadingDuration(seconds) {
        const total = Math.floor(Number(seconds || 0));
        if (!Number.isFinite(total) || total <= 0) return '';
        const hour = Math.floor(total / 3600);
        const minute = Math.floor((total % 3600) / 60);
        const second = total % 60;
        const parts = [];
        if (hour > 0) parts.push(bt('duration.hour', '{count} 小时', {count: hour}));
        if (minute > 0) parts.push(bt('duration.minute', '{count} 分钟', {count: minute}));
        if (second > 0 && hour === 0) parts.push(bt('duration.second', '{count} 秒', {count: second}));
        if (!parts.length) parts.push(bt('duration.second', '{count} 秒', {count: total}));
        return parts.join(' ');
    }

    async function loadSeriesThumbnailsBatched(items, renderToken) {
        const BATCH = 10;
        for (let i = 0; i < items.length; i += BATCH) {
            if (renderToken !== seriesState.renderToken) return;
            const batch = items.slice(i, i + BATCH);
            await Promise.allSettled(batch.map((item, offset) => loadSingleSeriesThumbnail(item, i + offset, renderToken)));
        }
    }

    async function loadNovelSeriesCoversBatched(items, renderToken) {
        const BATCH = 10;
        for (let i = 0; i < items.length; i += BATCH) {
            if (renderToken !== seriesState.renderToken) return;
            const batch = items.slice(i, i + BATCH);
            await Promise.allSettled(batch.map((item, offset) => loadSingleNovelSeriesCover(item, i + offset, renderToken)));
        }
    }

    async function loadSingleNovelSeriesCover(item, idx, renderToken) {
        if (!item.coverUrl) return;
        const coverEl = document.getElementById(`series-novel-cover-${idx}`);
        if (!coverEl) return;
        try {
            const params = new URLSearchParams({url: item.coverUrl});
            const res = await fetch(`${BASE}/api/pixiv/thumbnail-proxy?${params}`, {headers: pixivHeader()});
            if (!res.ok) return;
            const blob = await res.blob();
            if (renderToken !== seriesState.renderToken) return;
            const blobUrl = URL.createObjectURL(blob);
            seriesState.activeBlobUrls.push(blobUrl);
            if (coverEl.isConnected) {
                coverEl.innerHTML = `<img src="${blobUrl}" alt="${esc(item.title || '')}">`;
            }
        } catch {
        }
    }

    async function loadSingleSeriesThumbnail(item, idx, renderToken) {
        if (!item.thumbnailUrl) return;
        const imgEl = document.getElementById(`series-thumb-img-${idx}`);
        if (!imgEl) return;
        try {
            const params = new URLSearchParams({url: item.thumbnailUrl});
            const res = await fetch(`${BASE}/api/pixiv/thumbnail-proxy?${params}`, {headers: pixivHeader()});
            if (!res.ok) return;
            const blob = await res.blob();
            if (renderToken !== seriesState.renderToken) return;
            const blobUrl = URL.createObjectURL(blob);
            seriesState.activeBlobUrls.push(blobUrl);
            if (imgEl.isConnected) imgEl.src = blobUrl;
        } catch {
        }
    }

    function getSeriesQueueId(item) {
        if (!item) return '';
        return seriesState.kind === 'novel' ? 'n' + String(item.id) : String(item.id);
    }

    function buildSeriesQueueMeta(item, idx, fallbackOrder = getSeriesFallbackOrder(idx)) {
        const seriesOrder = Number(item.seriesOrder || fallbackOrder);
        if (seriesState.kind === 'novel') {
            return {
                title: item.title || bt('queue.novel-fallback', '小说 {id}', {id: item.id}),
                novelId: String(item.id),
                kind: 'novel',
                authorId: item.userId ? Number(item.userId) : seriesState.seriesAuthorId,
                authorName: item.userName || seriesState.seriesAuthorName,
                isAi: Number(item.aiType ?? 0) >= 2,
                xRestrict: Number(item.xRestrict ?? 0),
                tags: Array.isArray(item.tags) ? item.tags : [],
                readingTimeSeconds: item.readingTimeSeconds ?? null,
                coverUrl: item.coverUrl || null,
                uploadTimestamp: item.uploadTimestamp || null,
                seriesId: seriesState.seriesId,
                seriesOrder,
                seriesTitle: seriesState.seriesTitle,
                mergeAfterSeriesId: !!state.settings.mergeNovelSeries ? Number(seriesState.seriesId) : null
            };
        }
        return {
            title: item.title,
            authorId: item.userId || seriesState.seriesAuthorId,
            authorName: item.userName || seriesState.seriesAuthorName,
            isAi: Number(item.aiType ?? 0) >= 2,
            seriesId: seriesState.seriesId,
            seriesOrder,
            seriesTitle: seriesState.seriesTitle
        };
    }

    function syncSeriesResultsQueueState() {
        if (!seriesState.items.length) return;
        const inQueue = new Set(state.queue.map(q => q.id));
        seriesState.items.forEach((item, idx) => {
            const queueId = getSeriesQueueId(item);
            const el = document.getElementById(
                seriesState.kind === 'novel' ? `series-novel-card-${idx}` : `series-thumb-${idx}`
            );
            if (!el) return;
            el.classList.toggle('in-queue', inQueue.has(queueId));
        });
    }

    function addSeriesItemToQueue(idx) {
        const item = seriesState.items[idx];
        if (!item) return;
        const queueId = getSeriesQueueId(item);
        const alreadyInQueue = state.queue.find(q => q.id === queueId);
        if (alreadyInQueue) {
            const removed = removeFromQueue(queueId);
            if (removed) {
                setStatus(bt('status.removed-from-queue', '已从队列移除：{title}', {title: item.title}), 'info');
            } else {
                setStatus(bt('status.cannot-remove-downloading', '无法移除（正在下载中）：{title}', {title: item.title}), 'warning');
            }
            return;
        }
        const added = addItemsToQueue(
            [queueId],
            [buildSeriesQueueMeta(item, idx)],
            seriesState.kind === 'novel' ? 'series-novel' : 'series',
            ''
        );
        setStatus(added > 0
                ? bt('status.added-to-queue', '已加入队列：{title}', {title: item.title})
                : bt('status.already-in-queue', '已在队列中：{title}', {title: item.title}),
            added > 0 ? 'success' : 'info');
        syncSeriesResultsQueueState();
    }

    function addSeriesItemsToQueue(items, fallbackOrderBase = 0) {
        const ids = items.map(r => getSeriesQueueId(r));
        const metas = items.map((r, idx) => buildSeriesQueueMeta(r, idx, fallbackOrderBase + idx + 1));
        const added = addItemsToQueue(ids, metas, seriesState.kind === 'novel' ? 'series-novel' : 'series', '');
        syncSeriesResultsQueueState();
        return {added, total: ids.length, existing: ids.length - added};
    }

    function addCurrentSeriesPageToQueue() {
        if (!seriesState.items.length) return;
        const fallbackOrderBase = (Math.max(1, seriesState.currentPage) - 1) * getSeriesPageSize();
        const result = addSeriesItemsToQueue(seriesState.items, fallbackOrderBase);
        setStatus(
            bt(
                'status.added-current-series-page-to-queue',
                '已将当前页 {added} 个作品加入队列（本页 {total} 个，{existing} 个已在队列中）',
                result
            ),
            result.added > 0 ? 'success' : 'info'
        );
    }

    async function ensureAllSeriesPagesLoaded() {
        let page = 1;
        while (page <= Math.max(1, seriesState.totalPages || 1)) {
            if (!seriesState.itemsByPage.has(page)) {
                setStatus(bt('status.series-fetch-all-progress', '正在补齐系列分页 {page} / {total}...', {
                    page,
                    total: seriesState.totalPages
                }), 'info');
                const data = await fetchSeriesPage(page);
                cacheSeriesPageData(data, page, false);
            }
            page += 1;
        }
        seriesState.rawItems = seriesState.itemsByPage.get(seriesState.currentPage) || seriesState.rawItems;
    }

    async function addAllSeriesResultsToQueue() {
        if (!seriesState.seriesId || (!seriesState.items.length && !seriesState.allItems.length)) return;
        const loadedPages = seriesState.itemsByPage.size;
        if (!uiConfirmKey(
            'dialog.series-add-all-warning',
            '全部加入队列会额外请求该系列的所有分页（漫画每页 12 个，小说每页 30 个），大系列可能耗时并增加 Pixiv 请求量。当前已加载 {loaded} / {total} 页，确认继续？',
            {loaded: loadedPages, total: seriesState.totalPages}
        )) {
            return;
        }
        updateSeriesQueueButtons(true);
        try {
            await ensureAllSeriesPagesLoaded();
            const rawAll = seriesState.allItems.length ? seriesState.allItems : seriesState.rawItems;
            // 附加筛选已开启时，全部入队也只入符合筛选条件的成员（与当前页预览的过滤口径一致）
            let toAdd = rawAll;
            if (hasExtraSearchFilter()) {
                const filters = normalizeSearchFilters(getSearchFiltersFromUI());
                const res = await computeFilteredItems(rawAll, filters, seriesState.kind, () => false);
                if (res) toAdd = res.filtered;
            }
            const result = addSeriesItemsToQueue(toAdd, 0);
            setStatus(
                bt(
                    'status.added-many-to-queue',
                    '已将 {added} 个作品加入队列（共 {total} 个，{existing} 个已在队列中）',
                    result
                ),
                result.added > 0 ? 'success' : 'info'
            );
            renderSeriesResults();
        } catch (e) {
            setStatus(bt('status.series-load-failed', '加载失败：{message}', {message: e.message}), 'error');
        } finally {
            updateSeriesQueueButtons();
        }
    }

    function addAllSearchResultsToQueue() {
        if (!searchState.results.length) return;
        const isNovel = searchState.kind === 'novel';
        const ids = searchState.results.map(r => isNovel ? 'n' + String(r.id) : String(r.id));
        const metas = searchState.results.map(r => isNovel
            ? {
                title: r.title,
                novelId: String(r.id),
                kind: 'novel',
                authorId: r.userId ? Number(r.userId) : null,
                authorName: r.userName || '',
                isAi: Number(r.aiType ?? 0) >= 2,
                xRestrict: Number(r.xRestrict ?? 0)
            }
            : {
                title: r.title,
                authorId: r.userId,
                authorName: r.userName,
                isAi: Number(r.aiType ?? 0) >= 2
            });
        const added = addItemsToQueue(ids, metas, isNovel ? 'search-novel' : 'search', '');
        setStatus(
            bt(
                'status.added-many-to-queue',
                '已将 {added} 个作品加入队列（共 {total} 个，{existing} 个已在队列中）',
                {added, total: ids.length, existing: ids.length - added}
            ),
            'success'
        );
    }

    function renderSearchPagination() {
        const pag = document.getElementById('search-pagination');
        if (searchState.submode === 'batch') {
            renderBatchLocalPagination(pag);
            return;
        }
        const perPage = searchState.kind === 'novel' ? 24 : 60;
        const totalPages = Math.ceil(searchState.total / perPage);
        const cur = searchState.currentPage;
        if (totalPages <= 1) {
            pag.style.display = 'none';
            return;
        }
        pag.style.display = 'flex';
        const radius = 3;
        const pages = [];
        for (let p = Math.max(1, cur - radius); p <= Math.min(totalPages, cur + radius); p++) {
            pages.push(p);
        }
        pag.innerHTML =
            `<button onclick="performSearch(1)" ${cur === 1 ? 'disabled' : ''}>«</button>` +
            `<button onclick="performSearch(${cur - 1})" ${cur === 1 ? 'disabled' : ''}>‹</button>` +
            pages.map(p =>
                `<button onclick="${p === cur ? '' : `performSearch(${p})`}" ${p === cur ? 'class="pg-active" disabled' : ''}>${p}</button>`
            ).join('') +
            `<button onclick="performSearch(${cur + 1})" ${cur === totalPages ? 'disabled' : ''}>›</button>` +
            `<button onclick="performSearch(${totalPages})" ${cur === totalPages ? 'disabled' : ''}>»</button>` +
            `<span class="pg-info">${esc(bt('search.pagination.info', '第 {current} / {total} 页 · 共 {count} 个', {
                current: cur,
                total: totalPages,
                count: searchState.total.toLocaleString()
            }))}</span>`;
    }

    function renderBatchLocalPagination(pag) {
        const totalPages = batchLocalPageCount();
        if (!searchState.results.length || totalPages <= 1) {
            pag.style.display = 'none';
            return;
        }
        let cur = searchState.localPage;
        if (!Number.isFinite(cur) || cur < 1) cur = 1;
        if (cur > totalPages) cur = totalPages;
        searchState.localPage = cur;
        pag.style.display = 'flex';
        const radius = 3;
        const pages = [];
        for (let p = Math.max(1, cur - radius); p <= Math.min(totalPages, cur + radius); p++) {
            pages.push(p);
        }
        pag.innerHTML =
            `<button onclick="goBatchPage(1)" ${cur === 1 ? 'disabled' : ''}>«</button>` +
            `<button onclick="goBatchPage(${cur - 1})" ${cur === 1 ? 'disabled' : ''}>‹</button>` +
            pages.map(p =>
                `<button onclick="${p === cur ? '' : `goBatchPage(${p})`}" ${p === cur ? 'class="pg-active" disabled' : ''}>${p}</button>`
            ).join('') +
            `<button onclick="goBatchPage(${cur + 1})" ${cur === totalPages ? 'disabled' : ''}>›</button>` +
            `<button onclick="goBatchPage(${totalPages})" ${cur === totalPages ? 'disabled' : ''}>»</button>` +
            `<span class="pg-info">${esc(bt('search.pagination.info', '第 {current} / {total} 页 · 共 {count} 个', {
                current: cur,
                total: totalPages,
                count: searchState.results.length.toLocaleString()
            }))}</span>`;
    }

    function goBatchPage(p) {
        const totalPages = batchLocalPageCount();
        let next = Number(p);
        if (!Number.isFinite(next) || next < 1) next = 1;
        if (next > totalPages) next = totalPages;
        searchState.localPage = next;
        cleanupBlobUrls();
        renderSearchResults();
        renderSearchPagination();
        updateBatchQueueButtons();
        window.scrollTo({top: document.getElementById('search-results-area').offsetTop - 20, behavior: 'smooth'});
    }

    function toggleBlurR18() {
        searchState.blurR18 = document.getElementById('search-blur-r18').checked;
        storeSet('pixiv_search_blur_r18', String(searchState.blurR18));
        searchState.results.forEach((item, idx) => {
            if (item.xRestrict > 0) {
                const imgEl = document.getElementById(`thumb-img-${idx}`);
                if (imgEl) imgEl.classList.toggle('blur-r18', searchState.blurR18);
            }
        });
    }

    /* ============================================================
       搜索模式 / 作品批量获取模式 子模式切换
    ============================================================ */
    function applySubmodeUI(value, opts = {}) {
        const submode = value === 'batch' ? 'batch' : 'search';
        searchState.submode = submode;
        const root = document.getElementById('search-submode-switcher');
        if (root) {
            root.querySelectorAll('label').forEach(lbl => {
                const matches = lbl.dataset.submode === submode;
                lbl.classList.toggle('active', matches);
                const input = lbl.querySelector('input[type=radio]');
                if (input) input.checked = matches;
            });
        }
        const searchActions = document.getElementById('search-mode-actions');
        const batchActions = document.getElementById('batch-mode-actions');
        if (searchActions) searchActions.style.display = submode === 'batch' ? 'none' : '';
        if (batchActions) batchActions.style.display = submode === 'batch' ? '' : 'none';
        updateBatchLimitNote();
        if (opts.clear) {
            cleanupBlobUrls();
            searchState.rawResults = [];
            searchState.results = [];
            searchState.total = 0;
            searchState.currentWord = '';
            searchState.batchInfo = null;
            searchState.localPage = 1;
            searchState.filterSeq++;
            renderSearchResults();
            renderSearchPagination();
            document.getElementById('btn-add-all').disabled = true;
            updateBatchQueueButtons();
        }
    }

    function bindSubmodeSwitcher() {
        const root = document.getElementById('search-submode-switcher');
        if (!root) return;
        root.querySelectorAll('label').forEach(lbl => {
            lbl.addEventListener('click', () => {
                const next = lbl.dataset.submode === 'batch' ? 'batch' : 'search';
                if (searchState.submode === next) return;
                storeSet('pixiv_search_submode', next);
                applySubmodeUI(next, {clear: true});
            });
        });
    }

    function loadSubmodePref() {
        const saved = storeGet('pixiv_search_submode');
        applySubmodeUI(saved === 'batch' ? 'batch' : 'search', {clear: false});
    }

    function handleSearchEnter() {
        if (searchState.submode === 'batch') {
            runBatchFetch();
        } else {
            performSearch(1);
        }
    }

    // multi 模式下每次抓取页数（end-start+1）受 multi-mode.limit-page 约束；
    // solo 模式与管理员不限制。
    function batchPageLimit() {
        return (appMode !== 'solo' && !isAdmin && multiModeLimitPage > 0) ? multiModeLimitPage : 0;
    }

    function getBatchRange() {
        const startEl = document.getElementById('batch-start-page');
        const endEl = document.getElementById('batch-end-page');
        let start = parseInt(startEl.value, 10);
        let end = parseInt(endEl.value, 10);
        if (!Number.isFinite(start) || start < 1) start = 1;
        // 管理员可把结束页设为 -1（「直到已下载作品为止」哨兵，仅用于计划任务快照）：保留在输入框，不夹取。
        // 实时批量获取（runBatchFetch）会把 -1 当作单页处理，绝不真的把 -1 发给后端。
        if (isAdmin && end === -1) {
            startEl.value = start;
            endEl.value = -1;
            return {start, end: -1};
        }
        if (!Number.isFinite(end) || end < 1) end = start;
        if (end < start) {
            const t = start;
            start = end;
            end = t;
        }
        const limit = batchPageLimit();
        if (limit > 0 && (end - start + 1) > limit) {
            end = start + limit - 1;
        }
        startEl.value = start;
        endEl.value = end;
        return {start, end};
    }

    function handleBatchRangeChange() {
        getBatchRange();
        updateBatchLimitNote();
    }

    function updateBatchLimitNote() {
        const note = document.getElementById('batch-limit-note');
        if (!note) return;
        const limit = batchPageLimit();
        if (limit > 0) {
            note.textContent = bt('search.batch.note.multi-limited',
                'multi 模式：每次最多 {limit} 页', {limit});
        } else if (appMode === 'solo') {
            note.textContent = bt('search.batch.note.solo', 'solo 模式：不限制');
        } else {
            note.textContent = bt('search.batch.note.multi-unlimited', 'multi 模式：不限制');
        }
    }

    function updateBatchQueueButtons() {
        const addPage = document.getElementById('btn-batch-add-page');
        const addAll = document.getElementById('btn-batch-add-all');
        const hasResults = searchState.submode === 'batch' && searchState.results.length > 0;
        if (addAll) addAll.disabled = !hasResults;
        if (addPage) {
            const view = hasResults ? getSearchView() : null;
            addPage.disabled = !(view && view.items.length > 0);
        }
    }

    async function runBatchFetch() {
        const word = document.getElementById('search-word').value.trim();
        if (!word) {
            setStatus(bt('alert.search-keyword-required', '请输入搜索关键词'), 'error');
            return;
        }
        const {start, end} = getBatchRange();
        // 实时批量获取只翻固定页数：-1 哨仅对计划任务有意义，这里按单页（start）处理，绝不把 -1 发给后端。
        const effEnd = end === -1 ? start : end;
        const uiMode = (document.getElementById('search-content-filter') || {}).value || 'all';
        const sMode = document.querySelector('input[name="search-smode"]:checked').value;
        const order = document.querySelector('input[name="search-order"]:checked').value;
        const r18Family = uiMode === 'r18' || uiMode === 'r18g' || uiMode === 'r18plus';
        const mode = r18Family ? 'r18' : uiMode;
        const clientFilter = uiMode === 'r18' ? 1 : uiMode === 'r18g' ? 2 : 0;
        const kind = state.settings.searchKind === 'novel' ? 'novel' : 'illust';

        document.getElementById('search-premium-tip').style.display = order === 'popular_d' ? 'block' : 'none';

        cleanupBlobUrls();
        searchState.submode = 'batch';
        searchState.currentWord = word;
        searchState.currentMode = uiMode;
        searchState.currentOrder = order;
        searchState.currentSMode = sMode;
        searchState.kind = kind;
        searchState.localPage = 1;
        searchState.batchInfo = null;
        searchState.filterSeq++;

        const btn = document.getElementById('btn-batch-fetch');
        btn.disabled = true;
        document.getElementById('search-results-area').innerHTML =
            `<div class="search-spinner"><span class="search-spinner-icon"></span>${esc(bt('status.batch-fetching', '批量获取第 {start}–{end} 页中...', {start, end: effEnd}))}</div>`;
        document.getElementById('search-pagination').style.display = 'none';
        document.getElementById('btn-batch-add-page').disabled = true;
        document.getElementById('btn-batch-add-all').disabled = true;

        try {
            const endpoint = kind === 'novel' ? '/api/pixiv/novel-search/range' : '/api/pixiv/search/range';
            const params = new URLSearchParams({word, order, mode, sMode, startPage: start, endPage: effEnd});
            const res = await fetch(`${BASE}${endpoint}?${params}`, {headers: pixivHeader()});
            const data = await res.json();
            if (!res.ok) {
                document.getElementById('search-results-area').innerHTML =
                    `<div style="color:#dc3545;text-align:center;padding:24px 0;">${esc(bt('status.batch-fetch-failed', '批量获取失败：{message}', {message: data.error || bt('queue.unknown-error', '未知错误')}))}</div>`;
                return;
            }
            let items = data.items || [];
            if (clientFilter > 0) items = items.filter(it => Number(it.xRestrict ?? 0) === clientFilter);
            searchState.pixivPageCount = items.length;
            searchState.rawResults = items;
            searchState.results = items;
            searchState.total = data.total || 0;
            searchState.noCookie = !hasPixivCookie();
            searchState.batchInfo = {
                startPage: data.startPage,
                endPage: data.endPage,
                requestedPages: data.requestedPages,
                acceptedPages: data.acceptedPages,
                fetchedPages: data.fetchedPages,
                limitPage: data.limitPage
            };

            const filterStats = await applyCurrentSearchFilters();
            if (!filterStats) return;

            const parts = [
                bt('search.batch.summary.fetched', '已抓取去重 {count} 个', {count: searchState.rawResults.length}),
                bt('search.batch.summary.range', '范围第 {start}–{end} 页', {
                    start: data.startPage,
                    end: data.endPage
                })
            ];
            if (data.acceptedPages < data.requestedPages) {
                parts.push(bt('search.batch.summary.limit-applied',
                    'multi 模式上限 {limit} 页，请求 {requested} 页已限制',
                    {limit: data.limitPage, requested: data.requestedPages}));
            }
            if (hasExtraSearchFilter()) {
                parts.push(bt('search.summary.extra-filtered', '附加筛选后 {count} 个', {count: searchState.results.length}));
            }
            parts.push(bt('search.summary.pixiv-total', 'Pixiv 总数 {count}', {count: searchState.total.toLocaleString()}));
            setStatus(bt('status.batch-fetch-complete', '批量获取完成：{summary}', {summary: summaryJoin(parts)}), 'success');
        } catch (e) {
            document.getElementById('search-results-area').innerHTML =
                `<div style="color:#dc3545;text-align:center;padding:24px 0;">${esc(bt('status.request-failed', '请求失败：{message}', {message: e.message}))}</div>`;
        } finally {
            btn.disabled = false;
            updateBatchQueueButtons();
        }
    }

    function addCurrentBatchPageToQueue() {
        if (searchState.submode !== 'batch') return;
        const view = getSearchView();
        if (!view.items.length) return;
        const isNovel = searchState.kind === 'novel';
        const ids = view.items.map(r => isNovel ? 'n' + String(r.id) : String(r.id));
        const metas = view.items.map(r => isNovel
            ? {
                title: r.title,
                novelId: String(r.id),
                kind: 'novel',
                authorId: r.userId ? Number(r.userId) : null,
                authorName: r.userName || '',
                isAi: Number(r.aiType ?? 0) >= 2,
                xRestrict: Number(r.xRestrict ?? 0)
            }
            : {
                title: r.title,
                authorId: r.userId,
                authorName: r.userName,
                isAi: Number(r.aiType ?? 0) >= 2
            });
        const added = addItemsToQueue(ids, metas, isNovel ? 'search-novel' : 'search', '');
        setStatus(
            bt(
                'status.added-many-to-queue',
                '已将 {added} 个作品加入队列（共 {total} 个，{existing} 个已在队列中）',
                {added, total: ids.length, existing: ids.length - added}
            ),
            'success'
        );
    }

    /* ============================================================
       收藏夹选择器（solo / multi 管理员）
    ============================================================ */
    function normalizeBatchCollectionId(value) {
        if (value === null || value === undefined || value === '') return null;
        const parsed = Number.parseInt(String(value), 10);
        return Number.isFinite(parsed) && parsed > 0 ? parsed : null;
    }

    function setBatchCollectionSetting(value) {
        const next = normalizeBatchCollectionId(value);
        const current = normalizeBatchCollectionId(state.settings.collectionId);
        state.settings.collectionId = next;
        if (current === next) return false;
        saveSettings();
        return true;
    }

    function resetBatchCollectionSelect() {
        const sel = document.getElementById('s-collection');
        sel.innerHTML = '';
        const opt = document.createElement('option');
        opt.value = '';
        opt.textContent = bt('option.collection.none', '（不加入收藏夹）');
        sel.appendChild(opt);
        sel.value = '';
        document.getElementById('s-collection-wrap').style.display = 'none';
    }

    function renderBatchCollections(collections, selectedId) {
        const sel = document.getElementById('s-collection');
        resetBatchCollectionSelect();
        collections.forEach(c => {
            const opt = document.createElement('option');
            opt.value = String(c.id);
            opt.textContent = c.name;
            sel.appendChild(opt);
        });
        if (collections.length > 0) {
            document.getElementById('s-collection-wrap').style.display = '';
        }
        sel.value = selectedId === null ? '' : String(selectedId);
    }

    async function refreshBatchCollections() {
        if (batchCollectionsRefreshPromise) {
            return batchCollectionsRefreshPromise;
        }
        batchCollectionsRefreshPromise = (async () => {
            const canUseCollections = appMode === 'solo' || isAdmin;
            if (!canUseCollections) {
                setBatchCollectionSetting(null);
                resetBatchCollectionSelect();
                return {collectionId: null, collections: []};
            }
            try {
                const res = await fetch(BASE + '/api/collections', {credentials: 'same-origin'});
                if (!res.ok) {
                    if (res.status === 401 || res.status === 403) {
                        isAdmin = false;
                        updateAuthButtons();
                    }
                    setBatchCollectionSetting(null);
                    resetBatchCollectionSelect();
                    return {collectionId: null, collections: []};
                }
                const data = await res.json();
                const collections = Array.isArray(data.collections) ? data.collections : [];
                const current = normalizeBatchCollectionId(state.settings.collectionId);
                const validIds = new Set(collections
                    .map(c => normalizeBatchCollectionId(c.id))
                    .filter(id => id !== null));
                const selectedId = current !== null && validIds.has(current) ? current : null;
                setBatchCollectionSetting(selectedId);
                renderBatchCollections(collections, selectedId);
                return {collectionId: selectedId, collections};
            } catch {
                setBatchCollectionSetting(null);
                resetBatchCollectionSelect();
                return {collectionId: null, collections: []};
            } finally {
                batchCollectionsRefreshPromise = null;
            }
        })();
        return batchCollectionsRefreshPromise;
    }

    async function resolveBatchCollectionIdForDownload() {
        const result = await refreshBatchCollections();
        return result.collectionId;
    }

    /* ============================================================
       初始化
    ============================================================ */
    async function init() {
        // 检测使用模式，solo 模式则从服务器加载状态
        await detectMode();
        applyCookieHint();
        updateBatchLimitNote();
        await detectAuthState();
        // 多人模式：初始化配额状态
        if (appMode === 'multi') {
            await initQuota();
        }
        updateAuthButtons();
        if (appMode === 'solo') {
            await loadServerState();
            applyCookieHint();
        }

        // Cookie
        const savedCookie = storeGet('pixiv_cookie') || '';
        const cookieInput = document.getElementById('cookie-input');
        cookieInput.value = savedCookie;
        cookieInput.addEventListener('input', applyCookieDependentUi);
        setCookieFmt(getCookieFmt());

        document.getElementById('cookie-save').addEventListener('click', () => {
            const raw = document.getElementById('cookie-input').value.trim();
            const result = validateAndParseCookie(raw, getCookieFmt());
            if (!result.ok) {
                setCookieStatus(bt('status.cookie-save-failed', 'Cookie 保存失败：{message}', {message: result.error}), 'error');
                return;
            }
            storeSet('pixiv_cookie', raw);
            if (result.warnings.length) {
                setCookieStatus(
                    bt('status.cookie-saved-warning', 'Cookie 已保存（{count} 个字段）⚠ {warnings}', {
                        count: result.count,
                        warnings: result.warnings.join(uiLang() === 'en-US' ? '; ' : '；')
                    }),
                    'warning'
                );
            } else {
                setCookieStatus(bt('status.cookie-saved', 'Cookie 已保存，共 {count} 个字段', {count: result.count}), 'success');
            }
            applyCookieDependentUi();
        });

        document.getElementById('cookie-toggle').addEventListener('click', () => {
            const inp = document.getElementById('cookie-input');
            if (inp.type === 'password') {
                inp.type = 'text';
            } else {
                inp.type = 'password';
            }
            syncCookieToggleLabel();
        });

        document.getElementById('cookie-clear').addEventListener('click', () => {
            if (!uiConfirmKey('dialog.confirm-clear-cookie', '确认清除已保存的 Cookie？')) return;
            storeRemove('pixiv_cookie');
            document.getElementById('cookie-input').value = '';
            setCookieStatus(bt('status.cookie-cleared', 'Cookie 已清除'), 'success');
            applyCookieDependentUi();
        });

        // 一键导入 Cookie：仅 solo 模式可用（依赖服务器端 /api/batch/state 中转）
        const cookieImportBtn = document.getElementById('cookie-import');
        const cookieImportHint = document.getElementById('cookie-import-hint');
        if (cookieImportBtn) {
            if (appMode === 'solo') {
                cookieImportBtn.hidden = false;
                cookieImportBtn.addEventListener('click', importCookieViaScript);
                if (cookieImportHint) cookieImportHint.hidden = false;
            } else {
                cookieImportBtn.hidden = true;
                if (cookieImportHint) cookieImportHint.hidden = true;
            }
        }

        // Settings change → auto-save
        ['s-interval', 's-image-delay', 's-concurrent', 's-skip', 's-verify-files', 's-bookmark', 's-collection', 's-file-name-template', 's-novel-format', 's-novel-merge', 's-novel-merge-format'].forEach(id => {
            const el = document.getElementById(id);
            if (!el) return;
            el.addEventListener('change', syncSettings);
            if (el.type === 'number' || el.type === 'text') el.addEventListener('input', syncSettings);
        });
        document.getElementById('s-collection').addEventListener('click', () => {
            refreshBatchCollections();
        });

        // Kind switchers (User / Search)
        bindKindSwitcher('user-kind-switcher', 'userKind', () => {
            applyNovelSettingsVisibility();
            // User 模式作品类型变化时，同步共享「附加筛选」里页数/字数字段的显隐
            applySearchKindUI();
            // 切换插画/小说后旧预览结果不再适用，清空避免误导
            clearUserPreview();
        });
        bindKindSwitcher('search-kind-switcher', 'searchKind', () => {
            applySearchKindUI();
            applyNovelSettingsVisibility();
            // 切换 kind 后清掉旧结果，避免误导
            cleanupBlobUrls();
            searchState.rawResults = [];
            searchState.results = [];
            searchState.total = 0;
            searchState.currentWord = '';
            searchState.batchInfo = null;
            searchState.localPage = 1;
            searchState.filterSeq++;
            renderSearchResults();
            renderSearchPagination();
            document.getElementById('btn-add-all').disabled = true;
            updateBatchQueueButtons();
        });
        bindSubmodeSwitcher();

        // Mode
        const savedMode = normalizeImportMode(storeGet('pixiv_mode') || QUICK_FETCH_MODE);
        state.mode = savedMode;

        // 在 switchMode 前先恢复 userId，否则 user 模式下 storageKey() 用空串找不到队列
        if (savedMode === 'user') {
            const savedUserId = storeGet('pixiv_batch_last_user_id') || '';
            const savedUsername = storeGet('pixiv_batch_last_username') || '';
            if (savedUserId) {
                state.userId = savedUserId;
                state.username = savedUsername;
                document.getElementById('user-id-input').value = savedUserId;
                document.getElementById('user-info-display').textContent =
                    savedUsername
                        ? bt('status.user-display', '用户：{name}（ID: {id}）', {name: savedUsername, id: savedUserId})
                        : bt('status.user-display-id', 'ID: {id}', {id: savedUserId});
            }
        }

        if (savedMode === 'search') {
            const blurPref = storeGet('pixiv_search_blur_r18');
            if (blurPref !== null) {
                searchState.blurR18 = blurPref === 'true';
                document.getElementById('search-blur-r18').checked = searchState.blurR18;
            }
        }
        loadSearchFilterPrefs();
        loadSubmodePref();
        // 初始化时按触发方式下拉框的当前选择只渲染对应输入框（否则周期与 Cron 两个输入框会同时出现）
        onScheduleTriggerChange();

        switchMode(savedMode);

        // Settings
        loadSettings();
        await refreshBatchCollections();
        applyCookieDependentUi();

        // Queue
        loadQueueForMode();
        updateButtonsState();
        updateStats();
    }

    document.addEventListener('DOMContentLoaded', async () => {
        await initPageI18n();
        loadAppInfo();
        init();
        setupTour(true);
    });

    /* ============================================================
       油猴脚本安装面板
    ============================================================ */
    let _userscriptsLoaded = false;

    function toggleUserscripts() {
        const panel = document.getElementById('userscripts-panel');
        if (panel.hidden) {
            panel.hidden = false;
            if (!_userscriptsLoaded) {
                _userscriptsLoaded = true;
                loadUserscripts();
            }
        } else {
            panel.hidden = true;
        }
    }

    async function loadUserscripts() {
        const list = document.getElementById('userscripts-list');
        list.textContent = bt('userscripts.loading', '加载中…');
        try {
            const resp = await fetch('/api/scripts?lang=' + encodeURIComponent(uiLang()));
            if (!resp.ok) throw new Error('HTTP ' + resp.status);
            const data = await resp.json();

            // 显示 host 提示
            const hostHint = document.getElementById('userscripts-host-hint');
            if (data.detectedHost && data.detectedHost !== 'localhost' && data.detectedHost !== '127.0.0.1') {
                hostHint.textContent = bt(
                    'userscripts.host-hint',
                    '此安装链接将自动把脚本 @connect 指向：{host}',
                    {host: data.detectedHost}
                );
                hostHint.style.display = 'block';
            } else {
                hostHint.textContent = '';
                hostHint.style.display = 'none';
            }

            list.innerHTML = '';
            if (!data.scripts || data.scripts.length === 0) {
                list.textContent = bt('userscripts.empty', '暂无可安装的脚本。');
                return;
            }
            data.scripts.forEach(s => {
                const item = document.createElement('div');
                item.className = 'userscript-card';
                item.innerHTML =
                    '<div class="userscript-card-head">' +
                        '<strong class="userscript-card-title">' + escHtml(s.displayName) + '</strong>' +
                        '<span class="userscript-card-version">v' + escHtml(s.version) + '</span>' +
                    '</div>' +
                    '<div class="userscript-card-desc">' + escHtml(s.description) + '</div>' +
                    '<div class="userscript-card-actions">' +
                        '<button class="btn btn-green userscript-card-btn" data-install-id="' + escHtml(s.id) + '" onclick="installScript(\'' + escHtml(s.id) + '\')">' +
                            escHtml(bt('userscripts.install', '⬇ 安装')) +
                        '</button>' +
                        '<a class="btn btn-blue userscript-card-btn userscript-card-source" ' +
                            'href="/api/scripts/' + encodeURIComponent(s.id) + '?raw=true" target="_blank">' +
                            escHtml(bt('userscripts.view-source', '📄 查看源码')) +
                        '</a>' +
                    '</div>';
                list.appendChild(item);
            });
        } catch (e) {
            list.textContent = bt('userscripts.load-failed', '加载失败：{message}', {message: e.message});
        }
    }

    /* ------------------------------------------------------------------
       脚本安装追踪（按浏览器记录，localStorage）
       仅记录「用户点过哪个脚本的安装按钮」，无法真正探测 Tampermonkey 是否
       装好；用于「一键导入 Cookie」判断是否需要先引导安装体验增强工具箱。
       All-in-One 合并包内含除「Local Download」外的全部脚本，安装它视为
       这些脚本（含体验增强工具箱）均已安装。
    ------------------------------------------------------------------ */
    const SCRIPT_ID_TOOLBOX = 'experience-toolbox';
    const SCRIPT_ID_ALL_IN_ONE = 'all-in-one';
    const SCRIPT_ID_LOCAL_DOWNLOAD = 'artwork-local';
    // All-in-One 覆盖的脚本 id（除 Local Download 外的全部）
    const ALL_IN_ONE_SCRIPT_IDS = [
        'experience-toolbox', 'artwork-java', 'user-batch', 'page-batch', 'import-batch'
    ];
    const INSTALLED_SCRIPTS_KEY = 'pixiv_userscript_installed';

    function getInstalledScripts() {
        try {
            return JSON.parse(localStorage.getItem(INSTALLED_SCRIPTS_KEY) || '{}') || {};
        } catch (e) {
            return {};
        }
    }

    function markScriptInstalled(id) {
        const map = getInstalledScripts();
        map[id] = true;
        if (id === SCRIPT_ID_ALL_IN_ONE) {
            ALL_IN_ONE_SCRIPT_IDS.forEach(sid => {
                map[sid] = true;
            });
        }
        try {
            localStorage.setItem(INSTALLED_SCRIPTS_KEY, JSON.stringify(map));
        } catch (e) {
            /* 隐私模式等场景静默降级 */
        }
    }

    function isToolboxInstalled() {
        const map = getInstalledScripts();
        return map[SCRIPT_ID_TOOLBOX] === true || map[SCRIPT_ID_ALL_IN_ONE] === true;
    }

    function installScript(id) {
        // 记录该脚本安装按钮已被点击（All-in-One 连带标记其覆盖的脚本）
        markScriptInstalled(id);
        // URL 必须以 .user.js 结尾，Tampermonkey 才会拦截并弹出安装确认页
        window.location.href = '/api/scripts/' + encodeURIComponent(id) + '.user.js';
    }

    /* ------------------------------------------------------------------
       一键导入 Cookie：让 pixiv.net 上的体验增强工具箱自动取 Cookie 回传
    ------------------------------------------------------------------ */
    const COOKIE_SYNC_SIGNAL = '__pixiv_cookie_sync__';

    function ensureUserscriptsExpanded() {
        const panel = document.getElementById('userscripts-panel');
        if (panel && panel.hidden) {
            toggleUserscripts();
        } else if (!_userscriptsLoaded) {
            _userscriptsLoaded = true;
            loadUserscripts();
        }
        const card = document.getElementById('userscripts-card');
        if (card && card.scrollIntoView) {
            card.scrollIntoView({block: 'center', behavior: 'smooth'});
        }
    }

    async function fetchServerPixivCookie() {
        try {
            const res = await fetch(BASE + '/api/batch/state', {credentials: 'same-origin'});
            if (!res.ok) return null;
            const data = await res.json();
            const st = data.state || {};
            return {
                cookie: st.pixiv_cookie != null ? String(st.pixiv_cookie) : '',
                fmt: st.pixiv_cookie_fmt || 'header',
                syncAt: st.pixiv_cookie_sync_at != null ? String(st.pixiv_cookie_sync_at) : '',
                syncStatus: st.pixiv_cookie_sync_status != null ? String(st.pixiv_cookie_sync_status) : ''
            };
        } catch (e) {
            return null;
        }
    }

    function applyImportedCookie(snapshot) {
        serverState['pixiv_cookie'] = snapshot.cookie;
        serverState['pixiv_cookie_fmt'] = snapshot.fmt;
        if (snapshot.syncAt) serverState['pixiv_cookie_sync_at'] = snapshot.syncAt;
        setCookieFmt(snapshot.fmt);
        const input = document.getElementById('cookie-input');
        if (input) input.value = snapshot.cookie;
        applyCookieDependentUi();
        const hasPhp = /(?:^|;\s*)PHPSESSID=/.test(snapshot.cookie);
        if (hasPhp) {
            setCookieStatus(bt('status.cookie-imported', '已从 Pixiv 自动导入并保存 Cookie'), 'success');
        } else {
            setCookieStatus(bt('status.cookie-imported-no-phpsessid',
                '已导入 Cookie，但未检测到 PHPSESSID，可能未登录 Pixiv'), 'warning');
        }
    }

    function runScriptCookieImport() {
        if (appMode !== 'solo') {
            setCookieStatus(bt('status.cookie-import-solo-only', '一键导入仅在 solo 模式可用'), 'error');
            return;
        }
        // window.open 必须在用户手势内同步调用（await 会让弹窗被拦截），故同步取
        // 内存里的同步时间戳作基线。基线必须在每次同步结束后（成功或缺 PHPSESSID）
        // 都同步更新到 serverState，否则上次遗留的时间戳会让下次重试被瞬间误判。
        const baselineSyncAt = serverState['pixiv_cookie_sync_at'] != null
            ? String(serverState['pixiv_cookie_sync_at']) : '';
        const win = window.open(
            'https://www.pixiv.net/#' + COOKIE_SYNC_SIGNAL,
            'pixivCookieSync',
            'width=560,height=420'
        );
        if (!win) {
            setCookieStatus(bt('status.cookie-import-popup-blocked',
                '弹窗被拦截，请允许本站弹窗后重试'), 'error');
            return;
        }
        setCookieStatus(bt('status.cookie-import-opening', '正在打开 Pixiv 自动获取 Cookie...'), 'info');
        const deadline = Date.now() + 25000;
        const poll = () => {
            setTimeout(async () => {
                const cur = await fetchServerPixivCookie();
                // 本次同步已结束（时间戳变化）。无论成功与否工具箱都会更新时间戳，
                // 故缺 PHPSESSID 时也能立即停下并给出明确提示，不再空等到超时。
                if (cur && cur.syncAt && cur.syncAt !== baselineSyncAt) {
                    try { win.close(); } catch (e) {}
                    // 同步内存基线，避免下次重试用旧时间戳瞬间误判
                    serverState['pixiv_cookie_sync_at'] = cur.syncAt;
                    if (cur.syncStatus === 'ok' || /(?:^|;\s*)PHPSESSID=/.test(cur.cookie || '')) {
                        applyImportedCookie(cur);
                    } else {
                        setCookieStatus(bt('status.cookie-imported-no-phpsessid',
                            '已导入 Cookie，但未检测到 PHPSESSID，可能未登录 Pixiv'), 'error');
                    }
                    return;
                }
                if (Date.now() > deadline) {
                    setCookieStatus(bt('status.cookie-import-timeout',
                        '未能自动获取 Cookie，请确认已安装并启用「体验增强工具箱」且已登录 Pixiv，或手动粘贴'),
                        'error');
                    return;
                }
                poll();
            }, 1500);
        };
        poll();
    }

    function importCookieViaScript() {
        if (appMode !== 'solo') {
            setCookieStatus(bt('status.cookie-import-solo-only', '一键导入仅在 solo 模式可用'), 'error');
            return;
        }
        if (isToolboxInstalled()) {
            runScriptCookieImport();
            return;
        }
        // 未安装工具箱：复用引导遮罩，门槛步骤须先点安装按钮才能进入下一步
        if (typeof PixivTour === 'undefined') {
            setCookieStatus(bt('status.cookie-import-need-toolbox',
                '请先在「油猴脚本」面板安装「体验增强工具箱」'), 'error');
            ensureUserscriptsExpanded();
            return;
        }
        const ctrl = PixivTour.init({
            pageKey: 'cookie-import',
            i18n: pageI18n,
            noHelpFab: true,
            onFinish: runScriptCookieImport,
            steps: [
                {
                    target: '#userscripts-card',
                    interactiveSelector: '#userscripts-list [data-install-id="' + SCRIPT_ID_TOOLBOX + '"]',
                    titleKey: 'tour:batch.cookie-import.install.title',
                    bodyKey: 'tour:batch.cookie-import.install.body',
                    fallbackTitle: '① 安装体验增强工具箱',
                    fallbackBody: '一键导入需要 pixiv.net 上的「体验增强工具箱」配合。请点击下方高亮的「体验增强工具箱」安装按钮（引导期间页面其它内容不可点击，安装后才能进入下一步）。',
                    onShow: ctrl => {
                        ensureUserscriptsExpanded();
                        setTimeout(() => ctrl.refresh(), 400);
                    },
                    gate: () => isToolboxInstalled(),
                    actionKey: 'tour:batch.cookie-import.install.have-aio',
                    actionFallback: '我已安装 All-in-One',
                    onAction: ctrl => {
                        // 用户声明已装 All-in-One（含工具箱）：记录并直接结束引导、开始获取
                        markScriptInstalled(SCRIPT_ID_ALL_IN_ONE);
                        ctrl.end(true, 'finish');
                    }
                },
                {
                    target: '#cookie-import',
                    titleKey: 'tour:batch.cookie-import.ready.title',
                    bodyKey: 'tour:batch.cookie-import.ready.body',
                    fallbackTitle: '② 开始导入',
                    fallbackBody: '请确保已在本浏览器登录 Pixiv。点击「完成」后会打开 Pixiv 页面自动获取 Cookie 并返回，整个过程几秒内完成。'
                }
            ]
        });
        if (ctrl) ctrl.start(true);
    }

    function escHtml(str) {
        return String(str)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    }

    // ── 计划任务（管理员专用） ────────────────────────────────────────────────────
    // 方案：删除独立的「创建表单」，改为在 User / Search / 系列模式的工作区底部用
    // 「存为计划任务」卡片，直接快照当前模式来源 + 上方全部下载 / 筛选设置；第 5 个
    // Tab 仅做任务列表与管理（运行 / 授权 / 启停 / 编辑 / 删除）。
    let scheduleEditingId = null;
    let scheduleTasksCache = [];
    // 计划任务列表轮询：进入第 5 Tab 时定时刷新，让「正在运行 / 排队中」等瞬时状态灯能实时更新。
    let schedulePollTimer = null;
    const SCHEDULE_POLL_MS = 4000;
    // 当前展开了「本轮队列详情」的任务 id 集合：列表重渲染会重建 DOM，据此恢复展开态。
    // 未展开的任务不请求 / 不渲染队列；展开后从本地缓存即时渲染，再按需向后端拉取最新队列。
    const scheduleExpandedQueues = new Set();
    // 上一次「整列空/非空 + 横幅」与「按卡片」的渲染签名。整列 innerHTML 重建会销毁展开的队列 DOM、
    // 中断 SSE 平滑刷新、把队列正文滚回顶部；改成按卡片 diff —— 仅在「该卡片的卡片级数据」真正变化时替换
    // 该卡片，并在替换前后保留其内部「队列正文/待重试面板」的 innerHTML / 滚动位置 / 展开折叠态。
    // 队列正文照常由 SSE / 快照单独更新。
    let scheduleBannerSignature = null;
    // 初始为 true：静态 HTML 的 #schedule-list 自带 .schedule-empty 占位符，首次加载到任务时需要先清空它再 diff。
    let scheduleEmptyStateRendered = true;
    const scheduleCardSignatures = new Map();
    // 上次成功 diff 渲染计划任务列表时的 UI 语言。卡片签名只看任务数据、不含语言，所以仅靠
    // signature diff 无法在语言切换后重渲染卡片；loadScheduleTasks 用这个变量比对，当语言变化时
    // 强制丢弃签名 / 已渲染态，让卡片走 replace 路径，再补刷展开的「本轮队列详情」与待重试面板。
    let scheduleLastRenderedLang = null;

    function startSchedulePolling() {
        stopSchedulePolling();
        schedulePollTimer = setInterval(() => {
            if (state.mode === 'schedule' && document.visibilityState !== 'hidden') {
                loadScheduleTasks();
            }
        }, SCHEDULE_POLL_MS);
    }

    function stopSchedulePolling() {
        if (schedulePollTimer) {
            clearInterval(schedulePollTimer);
            schedulePollTimer = null;
        }
        // 离开计划任务 Tab：解绑全部队列 SSE 监听；若工作区也未在下载且无其它监听，顺手关掉聚合连接。
        unsubscribeAllScheduleQueueSse();
        if (!state.isRunning && state.sharedSse && Object.keys(state.sseListeners).length === 0) {
            closeSharedSSE();
        }
    }

    // 哪些模式可以创建计划任务（单作品导入无对应来源类型）
    const SCHEDULE_MODE_TYPE = {user: 'USER_NEW', search: 'SEARCH', series: 'SERIES'};
    const SCHEDULE_TYPE_MODE = {USER_NEW: 'user', SEARCH: 'search', SERIES: 'series'};

    function onScheduleTriggerChange() {
        const trigger = (document.getElementById('sch-trigger') || {}).value || 'interval';
        document.querySelectorAll('#save-as-schedule-card .sch-trigger-field').forEach(el => {
            el.style.display = el.classList.contains('sch-trigger-' + trigger) ? '' : 'none';
        });
    }

    // 「存为计划任务」卡片仅在管理员 + 可创建模式下显示；离开时自动退出编辑态
    function updateSaveScheduleCardVisibility() {
        const card = document.getElementById('save-as-schedule-card');
        if (!card) return;
        const eligible = isAdmin && !!SCHEDULE_MODE_TYPE[state.mode];
        card.style.display = eligible ? '' : 'none';
        if (!eligible && scheduleEditingId != null) resetScheduleForm();
    }

    function setScheduleFormStatus(msg, type = 'info') {
        const el = document.getElementById('sch-form-status');
        if (!el) return;
        el.textContent = msg || '';
        el.style.color = STATUS_COLORS[type] || '#666';
    }

    // 账号级 / 横幅级操作反馈（如过度访问账号恢复），与「存为计划任务」卡片的表单状态分开
    function setScheduleListStatus(msg, type = 'info') {
        const el = document.getElementById('schedule-list-status');
        if (!el) return;
        el.textContent = msg || '';
        el.style.color = STATUS_COLORS[type] || '#666';
    }

    // 单个任务卡片内的操作反馈：显示在该卡片顶部的 tips 区域，互不干扰
    function setScheduleCardTip(id, msg, type = 'info') {
        const el = document.getElementById(`schedule-card-tip-${id}`);
        if (!el) return;
        el.textContent = msg || '';
        el.style.color = STATUS_COLORS[type] || '#666';
    }

    // 按当前模式来源 + 上方全部下载 / 筛选设置，快照成 params v2。
    // 附加筛选（内容分级 / AI / 标签 / 收藏 / 页数 / 字数 / 类型）现为 User / Search / Series 共享的卡片，
    // 三种模式均携带；内容分级在 Search 还会派生出 Pixiv 查询的 source.mode。
    function buildScheduleSnapshot() {
        const mode = state.mode;
        const type = SCHEDULE_MODE_TYPE[mode];
        if (!type) throw new Error(bt('schedule.error.mode', '当前模式不支持创建计划任务'));
        let kind, source;
        if (mode === 'user') {
            kind = state.settings.userKind === 'novel' ? 'novel' : 'illust';
            const userId = parseUserIdInput(document.getElementById('user-id-input').value);
            if (!userId) throw new Error(bt('schedule.error.user-id', '请填写有效的画师 ID 或画师主页链接'));
            source = {userId};
        } else if (mode === 'search') {
            kind = state.settings.searchKind === 'novel' ? 'novel' : 'illust';
            const word = (document.getElementById('search-word').value || '').trim();
            if (!word) throw new Error(bt('schedule.error.word', '请填写搜索关键词'));
            const uiMode = (document.getElementById('search-content-filter') || {}).value || 'all';
            const sMode = (document.querySelector('input[name="search-smode"]:checked') || {}).value || 's_tag';
            const order = (document.querySelector('input[name="search-order"]:checked') || {}).value || 'date_d';
            const pixivMode = (uiMode === 'r18' || uiMode === 'r18g' || uiMode === 'r18plus') ? 'r18' : uiMode;
            // 子模式语义：🔍 搜索模式 = 只取第一页（maxPages 恒为 1）；📦 作品批量获取模式 = 读结束页输入框。
            // 结束页 = -1 是「直到已下载作品为止」哨兵，仅管理员可用（计划任务本就 admin-only）。
            const batchSubmode = searchState.submode === 'batch'
                || ((document.querySelector('input[name="search-submode"]:checked') || {}).value === 'batch');
            let maxPages;
            if (batchSubmode) {
                const raw = parseInt((document.getElementById('batch-end-page') || {}).value, 10);
                maxPages = (isAdmin && raw === -1) ? -1 : Math.max(1, Number.isFinite(raw) ? raw : 3);
            } else {
                maxPages = 1;
            }
            source = {word, order, mode: pixivMode, sMode, maxPages};
        } else {
            kind = seriesState.kind === 'novel' ? 'novel' : 'illust';
            if (!seriesState.seriesId) throw new Error(bt('schedule.error.series-id', '请先在上方解析并预览系列'));
            source = {seriesId: String(seriesState.seriesId)};
        }
        syncSettings();
        const f = getSearchFiltersFromUI();
        const filters = {
            content: f.content,
            aiFilter: f.ai,
            tagsExact: f.tagsExact,
            tagsFuzzy: f.tagsFuzzy,
            typeFilter: f.type,
            pagesMin: f.pageMin,
            pagesMax: f.pageMax,
            wordsMin: f.wordsMin,
            wordsMax: f.wordsMax,
            bookmarksMin: f.bookmarkMin,
            bookmarksMax: f.bookmarkMax
        };
        const download = {
            fileNameTemplate: state.settings.fileNameTemplate,
            bookmark: !!state.settings.bookmark,
            collectionId: state.settings.collectionId,
            // 队列调度项也纳入快照（统一存毫秒整数，避免 s/ms 单位歧义）：
            concurrent: Math.max(1, parseInt(state.settings.concurrent, 10) || 1),
            intervalMs: getIntervalMs(),
            imageDelayMs: getImageDelayMs(),
            verifyFiles: !!state.settings.verifyHistoryFiles,
            novelFormat: state.settings.novelFormat || 'txt',
            novelMerge: !!state.settings.mergeNovelSeries,
            novelMergeFormat: state.settings.mergeNovelFormat || 'epub'
        };
        return {type, kind, params: {kind, source, filters, download}};
    }

    async function submitScheduleTask() {
        const name = (document.getElementById('sch-name').value || '').trim();
        if (!name) {
            setScheduleFormStatus(bt('schedule.error.name', '请填写任务名称'), 'error');
            return;
        }
        let snap;
        try {
            snap = buildScheduleSnapshot();
        } catch (e) {
            setScheduleFormStatus(e.message, 'error');
            return;
        }
        const triggerKind = document.getElementById('sch-trigger').value;
        const body = {name, type: snap.type, paramsJson: JSON.stringify(snap.params), triggerKind};
        if (triggerKind === 'interval') {
            body.intervalMinutes = parseInt(document.getElementById('sch-interval').value, 10) || 0;
        } else {
            body.cronExpr = (document.getElementById('sch-cron').value || '').trim();
        }
        const editing = scheduleEditingId != null;
        const url = editing ? `${BASE}/api/schedule/tasks/${scheduleEditingId}` : `${BASE}/api/schedule/tasks`;
        try {
            const res = await fetch(url, {
                method: editing ? 'PUT' : 'POST',
                credentials: 'same-origin',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify(body)
            });
            if (!res.ok) {
                const err = await res.json().catch(() => ({}));
                setScheduleFormStatus(err.message || bt('schedule.error.save', '保存失败'), 'error');
                return;
            }
            // solo 模式下新建任务时，用当前输入框里的 Cookie 自动授权绑定，省去手动「授权 Cookie」一步。
            let autoAuthResult = null;
            if (!editing && appMode === 'solo') {
                const created = await res.json().catch(() => null);
                if (created && created.id != null) {
                    autoAuthResult = await autoAuthorizeScheduleCookie(created.id);
                }
            }
            // 先重置表单（resetScheduleForm 末尾会清空状态），再写入成功提示，
            // 否则成功提示会被 resetScheduleForm 的 setScheduleFormStatus('') 立刻清掉。
            resetScheduleForm();
            if (autoAuthResult === 'authorized') {
                setScheduleFormStatus(bt('schedule.status.saved-authorized', '已保存并自动授权 Cookie'), 'success');
            } else if (autoAuthResult === 'no-cookie') {
                setScheduleFormStatus(bt('schedule.status.saved-no-cookie', '已保存；当前无含 PHPSESSID 的 Cookie，任务将以受限模式运行，请在列表中「授权 Cookie」'), 'success');
            } else {
                setScheduleFormStatus(bt('schedule.status.saved', '已保存'), 'success');
            }
            loadScheduleTasks();
        } catch (e) {
            setScheduleFormStatus(bt('schedule.error.save', '保存失败'), 'error');
        }
    }

    function resetScheduleForm() {
        scheduleEditingId = null;
        const nameEl = document.getElementById('sch-name');
        if (nameEl) nameEl.value = '';
        const cronEl = document.getElementById('sch-cron');
        if (cronEl) cronEl.value = '';
        const intEl = document.getElementById('sch-interval');
        if (intEl) intEl.value = '1440';
        const trgEl = document.getElementById('sch-trigger');
        if (trgEl) trgEl.value = 'interval';
        const subEl = document.getElementById('sch-submit');
        if (subEl) subEl.textContent = bt('schedule.action.create', '➕ 创建任务');
        const canEl = document.getElementById('sch-cancel');
        if (canEl) canEl.style.display = 'none';
        const srcEl = document.getElementById('sch-edit-source');
        if (srcEl) {
            srcEl.style.display = 'none';
            srcEl.textContent = '';
        }
        onScheduleTriggerChange();
        setScheduleFormStatus('');
    }

    function setScheduleRadio(name, value) {
        if (value == null) return;
        const el = document.querySelector(`input[name="${name}"][value="${value}"]`);
        if (el) el.checked = true;
    }

    function applyScheduleKind(modePrefix, kind) {
        const k = kind === 'novel' ? 'novel' : 'illust';
        if (modePrefix === 'user') state.settings.userKind = k;
        else state.settings.searchKind = k;
        const radio = document.querySelector(`input[name="${modePrefix}-kind"][value="${k}"]`);
        if (radio) radio.checked = true;
        applyKindSwitcherUI(`${modePrefix}-kind-switcher`, k);
    }

    // 编辑：切到对应模式，把快照回灌进该模式来源 + 共享下载 / 筛选设置，再进入编辑态。
    function startEditScheduleTask(id) {
        const task = scheduleTasksCache.find(t => t.id === id);
        if (!task) return;
        // 进入编辑态时清掉上一次创建 / 保存留下的成功或失败提示。
        setScheduleFormStatus('');
        let params = {};
        try { params = JSON.parse(task.paramsJson || '{}'); } catch (e) { params = {}; }
        const kind = params.kind === 'novel' ? 'novel' : 'illust';
        const source = params.source || {};
        const filters = params.filters || {};
        const download = params.download || {};
        const targetMode = SCHEDULE_TYPE_MODE[task.type] || 'user';

        switchMode(targetMode);

        // 1) 来源 + kind
        if (task.type === 'USER_NEW') {
            applyScheduleKind('user', kind);
            const u = document.getElementById('user-id-input');
            if (u) u.value = source.userId || '';
        } else if (task.type === 'SEARCH') {
            applyScheduleKind('search', kind);
            const w = document.getElementById('search-word');
            if (w) w.value = source.word || '';
            setScheduleRadio('search-order', source.order || 'date_d');
            setScheduleRadio('search-smode', source.sMode || 's_tag');
            // 内容分级回灌走下方 setSearchFiltersUI(filters.content)；source.mode 仅是据其派生的 Pixiv 查询档位。
            // maxPages=1 ↔ 🔍 搜索模式（只取第一页）；-1 或 >=2 ↔ 📦 作品批量获取模式（回填结束页，-1 为哨兵）
            const mp = source.maxPages;
            const batchSubmode = mp === -1 || (typeof mp === 'number' && mp >= 2);
            applySubmodeUI(batchSubmode ? 'batch' : 'search', {clear: false});
            const endP = document.getElementById('batch-end-page');
            if (endP && batchSubmode) endP.value = mp;
        } else if (task.type === 'SERIES') {
            // 回填系列 URL 并自动解析预览（loadSeriesPreview 会同步 seriesState.kind / seriesId）
            const sid = source.seriesId || '';
            const urlInput = document.getElementById('series-input-url');
            if (urlInput) {
                urlInput.value = sid
                    ? (kind === 'novel' ? `https://www.pixiv.net/novel/series/${sid}` : String(sid))
                    : '';
            }
            seriesState.kind = kind;
            seriesState.seriesId = sid ? Number(sid) : null;
            if (sid) loadSeriesPreview();
        }

        // 2) 共享下载设置 + 筛选回灌（附加筛选现为三种模式共享，统一回灌）
        applyScheduleDownloadUI(download);
        setSearchFiltersUI(normalizeSearchFilters({
            content: filters.content, ai: filters.aiFilter, type: filters.typeFilter,
            pageMin: filters.pagesMin, pageMax: filters.pagesMax,
            bookmarkMin: filters.bookmarksMin, bookmarkMax: filters.bookmarksMax,
            wordsMin: filters.wordsMin, wordsMax: filters.wordsMax,
            tagsExact: filters.tagsExact, tagsFuzzy: filters.tagsFuzzy
        }));
        syncSettings();
        applyNovelSettingsVisibility();
        updateExtraFiltersCardVisibility();

        // 3) 触发 + 名称，进入编辑态
        scheduleEditingId = task.id;
        document.getElementById('sch-name').value = task.name || '';
        document.getElementById('sch-trigger').value = task.triggerKind || 'interval';
        document.getElementById('sch-interval').value = task.intervalMinutes || 1440;
        document.getElementById('sch-cron').value = task.cronExpr || '';
        document.getElementById('sch-submit').textContent = bt('schedule.action.save', '💾 保存修改');
        document.getElementById('sch-cancel').style.display = '';
        const srcEl = document.getElementById('sch-edit-source');
        if (srcEl) {
            srcEl.style.display = '';
            srcEl.textContent = bt('schedule.save.editing', '正在编辑：{name}', {name: task.name || ''});
        }
        onScheduleTriggerChange();
        updateSaveScheduleCardVisibility();
        const card = document.getElementById('save-as-schedule-card');
        if (card) card.scrollIntoView({behavior: 'smooth', block: 'center'});
    }

    // 把快照的下载设置写回共享控件（不含内容分级等附加筛选，它们在 filters 段）
    function applyScheduleDownloadUI(d) {
        if (!d) return;
        if (typeof d.fileNameTemplate === 'string' && d.fileNameTemplate) {
            document.getElementById('s-file-name-template').value = d.fileNameTemplate;
        }
        const bm = document.getElementById('s-bookmark');
        if (bm) bm.checked = !!d.bookmark;
        const col = document.getElementById('s-collection');
        if (col) col.value = d.collectionId != null ? String(d.collectionId) : '';
        const fmt = document.getElementById('s-novel-format');
        if (fmt && d.novelFormat) fmt.value = d.novelFormat;
        const mg = document.getElementById('s-novel-merge');
        if (mg) mg.checked = !!d.novelMerge;
        const mgf = document.getElementById('s-novel-merge-format');
        if (mgf && d.novelMergeFormat) mgf.value = d.novelMergeFormat;
        // 队列调度项回灌：最大并发数 / 作品间隔 / 图片间隔（快照存毫秒，统一以 ms 单位回填并把单位按钮切到 ms）/ 实际目录检测。
        const conc = document.getElementById('s-concurrent');
        if (conc && Number.isFinite(d.concurrent) && d.concurrent >= 1) {
            conc.value = d.concurrent;
            state.settings.concurrent = d.concurrent;
        }
        if (Number.isFinite(d.intervalMs) && d.intervalMs >= 0) {
            const iv = document.getElementById('s-interval');
            const ivUnit = document.getElementById('s-interval-unit');
            const ms = Math.round(d.intervalMs);
            if (iv) iv.value = ms;
            if (ivUnit) ivUnit.textContent = 'ms';
            state.settings.intervalUnit = 'ms';
            state.settings.interval = ms;
        }
        if (Number.isFinite(d.imageDelayMs) && d.imageDelayMs >= 0) {
            const im = document.getElementById('s-image-delay');
            const imUnit = document.getElementById('s-image-delay-unit');
            const ms = Math.round(d.imageDelayMs);
            if (im) im.value = ms;
            if (imUnit) imUnit.textContent = 'ms';
            state.settings.imageDelayUnit = 'ms';
            state.settings.imageDelay = ms;
        }
        const vf = document.getElementById('s-verify-files');
        if (vf) {
            vf.checked = !!d.verifyFiles;
            state.settings.verifyHistoryFiles = !!d.verifyFiles;
        }
        updateMergeFormatVisibility();
    }

    function scheduleStatusLabel(code) {
        if (!code) return bt('schedule.run-status.none', '尚未运行');
        if (code === 'OK') return bt('schedule.run-status.ok', '正常');
        if (code === 'AUTH_EXPIRED') return bt('schedule.run-status.auth-expired', '登录态失效，请重新授权 Cookie');
        if (code === 'ERROR') return bt('schedule.run-status.error', '运行出错');
        if (code === 'PAUSED') return bt('schedule.run-status.paused', '已手动暂停');
        if (code === 'OVERUSE_PAUSED') return bt('schedule.run-status.overuse-paused', '已暂停：检测到过度访问警告');
        return code;
    }

    /**
     * 计算任务卡片右上角「状态灯」：返回 {tone, text}。
     * tone ∈ green / yellow / red / gray，决定灯色；text 为本地化的状态说明。
     * 优先级：瞬时运行态（运行中 / 排队中）> 已停用 > 上一轮持久化结果（cookie 失效 / 失败 / 成功）> 首次未运行。
     */
    function scheduleStatusLight(t) {
        if (t.runState === 'RUNNING') {
            return {tone: 'green', live: true, text: bt('schedule.light.running', '正在运行')};
        }
        if (t.runState === 'QUEUED') {
            return {tone: 'yellow', live: true, text: bt('schedule.light.queued', '排队中')};
        }
        if (!t.enabled) {
            return {tone: 'gray', live: false, text: bt('schedule.light.disabled', '已停用，不会自动运行')};
        }
        // 挂起态优先于「中断」哨兵：挂起任务不会被自动重排，若残留 runStartedTime（暂停/挂起途中被强杀）
        // 仍显示中断红灯「已重新排期补齐」会与事实矛盾，故先判挂起态。
        if (t.lastStatus === 'OVERUSE_PAUSED') {
            return {tone: 'red', live: false, text: bt('schedule.light.overuse-paused', '已暂停：检测到过度访问警告（账号级）')};
        }
        if (t.lastStatus === 'PAUSED') {
            return {tone: 'gray', live: false, text: bt('schedule.light.paused', '已手动暂停')};
        }
        if (t.lastStatus === 'AUTH_EXPIRED') {
            return {tone: 'red', live: false, text: bt('schedule.light.auth-expired', '运行失败，Cookie 失效，请重新授权有效 Cookie')};
        }
        if (t.runStartedTime != null) {
            // 残留的开始时刻 = 上次运行未走到结果落库（进程被强杀中断）；重跑会刷新并最终补齐后清除。
            return {tone: 'red', live: false, text: bt('schedule.light.interrupted', '运行失败，上次运行被中断，已重新排期补齐')};
        }
        if (t.lastStatus === 'ERROR') {
            const reason = (t.lastMessage || '').trim();
            return {
                tone: 'red',
                live: false,
                text: reason
                    ? bt('schedule.light.error-reason', '运行失败，因为：{reason}', {reason})
                    : bt('schedule.light.error', '运行失败')
            };
        }
        if (t.lastStatus === 'OK') {
            return {tone: 'green', live: false, text: bt('schedule.light.ok', '运行成功，等待下次运行')};
        }
        // last_status 为空：恢复 / 账号级恢复后清空了上轮结果。已运行过则显示「等待下次运行」，
        // 从未运行过才是「等待首次运行」，避免恢复一个跑过多次的任务后误显示成首次。
        if (t.lastRunTime != null) {
            return {tone: 'gray', live: false, text: bt('schedule.light.idle', '等待下次运行')};
        }
        return {tone: 'gray', live: false, text: bt('schedule.light.never', '等待首次运行')};
    }

    function fmtScheduleTime(ms) {
        if (!ms) return '—';
        try { return new Date(ms).toLocaleString(); } catch (e) { return '—'; }
    }

    function parseScheduleParams(task) {
        try {
            const parsed = JSON.parse((task || {}).paramsJson || '{}');
            return parsed && typeof parsed === 'object' ? parsed : {};
        } catch (e) {
            return null;
        }
    }

    function scheduleTypeLabel(type) {
        return {
            USER_NEW: bt('mode.user', '👤 User 模式'),
            SEARCH: bt('mode.search', '🔍 Search 模式'),
            SERIES: bt('mode.series', '📚 系列下载')
        }[type] || type || bt('schedule.snapshot.value.unknown', '未知');
    }

    function scheduleKindFromParams(params) {
        if (!params || !params.kind) return null;
        return params.kind === 'novel' ? 'novel' : 'illust';
    }

    function scheduleKindLabel(kind) {
        if (kind === 'novel') return bt('schedule.kind.novel', '小说');
        if (kind === 'illust') return bt('schedule.kind.illust', '插画');
        return bt('schedule.snapshot.value.unknown', '未知');
    }

    function scheduleTriggerLabel(t) {
        const minutes = t.intervalMinutes || 0;
        return t.triggerKind === 'cron'
            ? `${bt('schedule.trigger.cron', 'Cron 表达式')} ${t.cronExpr || ''}`
            : `${bt('schedule.trigger.interval', '固定周期')} ${bt('schedule.time.minutes', '{count} 分钟', {count: minutes})}`;
    }

    function scheduleBoolLabel(value) {
        return value
            ? bt('schedule.snapshot.value.yes', '是')
            : bt('schedule.snapshot.value.no', '否');
    }

    function scheduleUnsetLabel() {
        return bt('schedule.snapshot.value.unset', '未设置');
    }

    function scheduleUnlimitedLabel() {
        return bt('schedule.snapshot.value.unlimited', '不限');
    }

    function scheduleValueOrUnset(value) {
        return value == null || value === '' ? scheduleUnsetLabel() : String(value);
    }

    function scheduleListValue(value) {
        const list = Array.isArray(value)
            ? value.map(v => String(v).trim()).filter(Boolean)
            : String(value || '').split(',').map(v => v.trim()).filter(Boolean);
        return list.length ? list.join(', ') : scheduleUnsetLabel();
    }

    function scheduleRangeValue(min, max) {
        const hasMin = min != null && min !== '';
        const hasMax = max != null && max !== '';
        if (hasMin && hasMax) {
            return bt('schedule.snapshot.value.range', '{min} - {max}', {min, max});
        }
        if (hasMin) {
            return bt('schedule.snapshot.value.at-least', '≥ {value}', {value: min});
        }
        if (hasMax) {
            return bt('schedule.snapshot.value.at-most', '≤ {value}', {value: max});
        }
        return scheduleUnlimitedLabel();
    }

    function scheduleContentLabel(value) {
        const labels = {
            all: bt('search.content.all', '全部'),
            safe: bt('search.content.safe', '全年龄'),
            r18plus: bt('search.content.r18plus', 'R18+(R-18 + R-18G)'),
            r18: bt('search.content.r18', 'R-18'),
            r18g: bt('search.content.r18g', 'R-18G')
        };
        return labels[value || 'all'] || scheduleValueOrUnset(value);
    }

    function scheduleAiLabel(value) {
        const labels = {
            all: bt('search.filter.all', '全部'),
            exclude: bt('search.filter.exclude-ai', '排除 AI'),
            only: bt('search.filter.only-ai', '仅 AI')
        };
        return labels[value || 'all'] || scheduleValueOrUnset(value);
    }

    function scheduleWorkTypeLabel(value) {
        const labels = {
            all: bt('search.filter.all', '全部'),
            illust: bt('search.type.illust', '插画'),
            manga: bt('search.type.manga', '漫画'),
            ugoira: bt('search.type.ugoira', '动图')
        };
        return labels[value || 'all'] || scheduleValueOrUnset(value);
    }

    function scheduleSearchModeLabel(value) {
        const labels = {
            s_tag: bt('search.mode.tag', '标签'),
            s_tc: bt('search.mode.title-desc', '标题/描述')
        };
        return labels[value || 's_tag'] || scheduleValueOrUnset(value);
    }

    function scheduleSearchOrderLabel(value) {
        const labels = {
            date_d: bt('search.order.latest', '最新'),
            date: bt('search.order.oldest', '最旧'),
            popular_d: bt('search.order.popular', '热门 ⚠')
        };
        return labels[value || 'date_d'] || scheduleValueOrUnset(value);
    }

    function scheduleNovelFormatLabel(value) {
        const labels = {
            txt: bt('novel:format.txt', '纯文本（TXT）'),
            html: bt('novel:format.html', '网页（HTML）'),
            epub: bt('novel:format.epub', '电子书（EPUB）')
        };
        return labels[value || 'txt'] || scheduleValueOrUnset(value);
    }

    function scheduleMaxPagesLabel(value) {
        if (Number(value) === -1) {
            return bt('schedule.snapshot.value.until-downloaded', '直到遇到已下载作品为止');
        }
        const parsed = Number(value);
        const count = Number.isFinite(parsed) && parsed > 0 ? parsed : 1;
        return bt('schedule.snapshot.value.pages-count', '{count} 页', {count});
    }

    function scheduleCollectionLabel(collectionId) {
        if (collectionId == null || collectionId === '') {
            return bt('option.collection.none', '（不加入收藏夹）');
        }
        const id = String(collectionId);
        const select = document.getElementById('s-collection');
        let optionText = '';
        if (select && select.options) {
            const opt = Array.from(select.options).find(o => String(o.value) === id);
            optionText = opt ? (opt.textContent || '').trim() : '';
        }
        const idText = bt('schedule.snapshot.value.id', 'ID');
        return optionText ? `${optionText} (${idText} ${id})` : `${idText} ${id}`;
    }

    function scheduleSnapshotRow(label, value) {
        return `<div class="schedule-snapshot-key">${escHtml(label)}</div>` +
            `<div class="schedule-snapshot-value">${escHtml(value)}</div>`;
    }

    function scheduleSnapshotSection(title, rows) {
        return `<section class="schedule-snapshot-section">` +
            `<div class="schedule-snapshot-section-title">${escHtml(title)}</div>` +
            `<div class="schedule-snapshot-grid">${rows.map(row => scheduleSnapshotRow(row[0], row[1])).join('')}</div>` +
            `</section>`;
    }

    function buildScheduleSnapshotSourceRows(type, source) {
        if (type === 'USER_NEW') {
            return [[bt('schedule.snapshot.field.user-id', '画师 ID'), scheduleValueOrUnset(source.userId)]];
        }
        if (type === 'SEARCH') {
            return [
                [bt('schedule.snapshot.field.keyword', '搜索关键词'), scheduleValueOrUnset(source.word)],
                [bt('schedule.snapshot.field.search-order', '排序'), scheduleSearchOrderLabel(source.order)],
                [bt('schedule.snapshot.field.search-mode', '搜索方式'), scheduleSearchModeLabel(source.sMode)],
                [bt('schedule.snapshot.field.pixiv-mode', 'Pixiv 内容范围'), scheduleContentLabel(source.mode)],
                [bt('schedule.snapshot.field.max-pages', '发现页数'), scheduleMaxPagesLabel(source.maxPages)]
            ];
        }
        if (type === 'SERIES') {
            return [[bt('schedule.snapshot.field.series-id', '系列 ID'), scheduleValueOrUnset(source.seriesId)]];
        }
        return [[
            bt('schedule.snapshot.field.source-json', '来源快照'),
            JSON.stringify(source || {})
        ]];
    }

    function scheduleConcurrentValue(n) {
        return (typeof n === 'number' && n >= 1)
            ? bt('schedule.snapshot.value.concurrent', '{n} 路并发', {n})
            : bt('schedule.snapshot.value.unset', '未设置');
    }

    function scheduleMsValue(ms) {
        return (typeof ms === 'number' && ms >= 0)
            ? bt('schedule.snapshot.value.ms', '{ms} ms', {ms})
            : bt('schedule.snapshot.value.unset', '未设置');
    }

    function renderScheduleSnapshotBody(t) {
        const params = parseScheduleParams(t);
        const kind = scheduleKindFromParams(params);
        const basicRows = [
            [bt('schedule.snapshot.field.name', '任务名称'), scheduleValueOrUnset(t.name)],
            [bt('schedule.snapshot.field.type', '任务类型'), scheduleTypeLabel(t.type)],
            [bt('schedule.snapshot.field.kind', '作品类型'), scheduleKindLabel(kind)],
            [bt('schedule.snapshot.field.trigger', '触发方式'), scheduleTriggerLabel(t)],
            [bt('schedule.snapshot.field.cookie', 'Cookie 模式'), t.cookieBound ? bt('schedule.cookie.bound', '已绑定 Cookie') : bt('schedule.cookie.restricted', '受限模式（无 Cookie）')],
            [bt('schedule.snapshot.field.enabled', '启用状态'), t.enabled ? bt('schedule.state.enabled', '已启用') : bt('schedule.state.disabled', '已停用')],
            [bt('schedule.snapshot.field.next-run', '下次运行'), fmtScheduleTime(t.nextRunTime)],
            [bt('schedule.snapshot.field.last-run', '上次运行'), fmtScheduleTime(t.lastRunTime)],
            [bt('schedule.snapshot.field.last-status', '运行状态'), scheduleStatusLabel(t.lastStatus)]
        ];
        const basicSection = scheduleSnapshotSection(bt('schedule.snapshot.section.basic', '基本信息'), basicRows);
        if (!params) {
            return basicSection +
                `<div class="schedule-snapshot-empty">${escHtml(bt('schedule.snapshot.error.parse', '任务快照解析失败'))}</div>`;
        }
        const source = params.source || {};
        const filters = params.filters || {};
        const download = params.download || {};
        const sourceSection = scheduleSnapshotSection(
            bt('schedule.snapshot.section.source', '来源快照'),
            buildScheduleSnapshotSourceRows(t.type, source)
        );
        const filterSection = scheduleSnapshotSection(
            bt('schedule.snapshot.section.filters', '筛选快照'),
            [
                [bt('label.search-content-rating', '内容分级'), scheduleContentLabel(filters.content)],
                [bt('label.search-ai', 'AI 作品'), scheduleAiLabel(filters.aiFilter)],
                [bt('label.search-tags-exact', '标签(精确匹配)'), scheduleListValue(filters.tagsExact)],
                [bt('label.search-tags-fuzzy', '标签(模糊匹配)'), scheduleListValue(filters.tagsFuzzy)],
                [bt('label.search-type', '作品类型'), scheduleWorkTypeLabel(filters.typeFilter)],
                [bt('schedule.snapshot.field.pages-range', '页数范围'), scheduleRangeValue(filters.pagesMin, filters.pagesMax)],
                [bt('schedule.snapshot.field.words-range', '字数范围'), scheduleRangeValue(filters.wordsMin, filters.wordsMax)],
                [bt('schedule.snapshot.field.bookmarks-range', '收藏数范围'), scheduleRangeValue(filters.bookmarksMin, filters.bookmarksMax)]
            ]
        );
        const downloadRows = [
            [bt('label.settings.skip', '跳过已下载作品'), bt('schedule.snapshot.value.always-on', '始终开启')],
            [bt('label.settings.filename-template', '文件名格式:'), scheduleValueOrUnset(download.fileNameTemplate)],
            [bt('label.settings.bookmark', '下载后自动收藏'), scheduleBoolLabel(!!download.bookmark)],
            [bt('label.settings.collection', '收藏到:'), scheduleCollectionLabel(download.collectionId)],
            [bt('label.settings.concurrent', '最大并发数:'), scheduleConcurrentValue(download.concurrent)],
            [bt('label.settings.interval', '作品间隔:'), scheduleMsValue(download.intervalMs)]
        ];
        // 图片间隔与实际目录检测仅对插画生效，小说快照不展示。
        if (kind !== 'novel') {
            downloadRows.push([bt('label.settings.image-delay', '图片间隔:'), scheduleMsValue(download.imageDelayMs)]);
            downloadRows.push([bt('label.settings.verify', '实际目录检测'), scheduleBoolLabel(!!download.verifyFiles)]);
        }
        downloadRows.push([bt('novel:batch.format-label', '小说格式'), scheduleNovelFormatLabel(download.novelFormat)]);
        downloadRows.push([bt('novel:batch.merge-label', '系列下载完成后生成合订本'), scheduleBoolLabel(!!download.novelMerge)]);
        downloadRows.push([bt('novel:batch.merge-format-label', '合订本格式'), scheduleNovelFormatLabel(download.novelMergeFormat || 'epub')]);
        const downloadSection = scheduleSnapshotSection(
            bt('schedule.snapshot.section.download', '下载设置快照'),
            downloadRows
        );
        return basicSection + sourceSection + filterSection + downloadSection;
    }

    function showScheduleSnapshot(id) {
        const modal = document.getElementById('schedule-snapshot-modal');
        const body = document.getElementById('schedule-snapshot-body');
        if (!modal || !body) return;
        const task = scheduleTasksCache.find(t => Number(t.id) === Number(id));
        body.innerHTML = task
            ? renderScheduleSnapshotBody(task)
            : `<div class="schedule-snapshot-empty">${escHtml(bt('schedule.snapshot.error.not-found', '未找到任务，请重新加载列表'))}</div>`;
        modal.dataset.taskId = String(id);
        modal.hidden = false;
        document.body.classList.add('schedule-modal-open');
        const closeBtn = modal.querySelector('.schedule-snapshot-close');
        if (closeBtn) closeBtn.focus();
    }

    function closeScheduleSnapshotModal() {
        const modal = document.getElementById('schedule-snapshot-modal');
        if (!modal) return;
        modal.hidden = true;
        delete modal.dataset.taskId;
        document.body.classList.remove('schedule-modal-open');
    }

    document.addEventListener('keydown', function (e) {
        if (e.key === 'Escape') closeScheduleSnapshotModal();
    });

    // 整列渲染所依据的卡片级数据签名：仅当这些字段变化时才需要重建整列 DOM。
    // 不含队列正文 / 展开态——它们由 SSE / 快照单独更新、由用户操作单独切换，不应触发整列重建。
    // 单卡片的渲染签名：仅当该任务的卡片级数据（状态灯/动作按钮/徽章/触发与时间/参数快照）变化时才需要替换 DOM。
    // 不含队列正文 / 待重试面板内容——那两个面板的 DOM 在 diff 替换时被「内 HTML + 滚动位置」整体迁移到新卡片上。
    function scheduleCardRenderSignature(t) {
        return JSON.stringify([
            t.name, t.enabled, t.type, t.cookieBound, t.runState,
            t.lastStatus, t.lastMessage, t.runStartedTime, t.nextRunTime, t.lastRunTime, t.paramsJson,
            t.accountId, t.ackWarningTime, t.pendingRetryArmed
        ]);
    }

    // 过度访问横幅是一个独立区段（按 accountId 分组、行为是账号级，不绑定任何具体卡片）；
    // 签名只看「哪些账号挂起 + 各账号挂起任务数」，签名相同就不重建横幅 DOM。
    function scheduleBannerRenderSignature(tasks) {
        const counts = new Map();
        tasks.forEach(t => {
            if (t.lastStatus === 'OVERUSE_PAUSED' && t.accountId) {
                counts.set(t.accountId, (counts.get(t.accountId) || 0) + 1);
            }
        });
        return JSON.stringify([...counts.entries()].sort());
    }

    async function loadScheduleTasks() {
        const list = document.getElementById('schedule-list');
        if (!list) return;
        // 语言切换路径：scheduleLastRenderedLang 与当前不一致时，先把签名清空，让本轮所有卡片
        // 都通过 replaceScheduleCardPreservingInner 走 replace，确保头/徽章/状态灯/动作按钮换语言；
        // 等列表渲染完，再为展开的「本轮队列详情」与「待重试」面板补一次重渲染（preserve-inner 会
        // 把这两块的旧 innerHTML 搬到新卡片，不主动刷一次会停留在旧语言）。
        const currentLang = uiLang();
        const langChanged = scheduleLastRenderedLang != null && scheduleLastRenderedLang !== currentLang;
        if (langChanged) {
            scheduleCardSignatures.clear();
            scheduleBannerSignature = null;
        }
        try {
            const res = await fetch(`${BASE}/api/schedule/tasks`, {credentials: 'same-origin'});
            if (!res.ok) {
                renderScheduleListPlaceholder(list, bt('schedule.list.load-failed', '加载失败'));
                return;
            }
            const tasks = await res.json();
            scheduleTasksCache = Array.isArray(tasks) ? tasks : [];

            // 不论列表是否为空：清理已不存在任务的 SSE 监听 / 模型 / 缓存，
            // 否则旧 handler 残留在 state.sseListeners，可能消费同 artworkId 的事件、并阻止
            // stopSchedulePolling 关闭共享 SSE 连接（条件含 sseListeners 为空）。
            const liveIds = new Set(scheduleTasksCache.map(t => Number(t.id)));
            releaseStaleScheduleQueueIds(liveIds);

            if (scheduleTasksCache.length === 0) {
                renderScheduleListPlaceholder(list, bt('schedule.list.empty', '暂无计划任务'));
            } else {
                if (scheduleEmptyStateRendered) {
                    // 上一拍是空态占位符，清掉后才能开始按卡片 diff。
                    list.innerHTML = '';
                    scheduleEmptyStateRendered = false;
                    scheduleBannerSignature = null;
                    scheduleCardSignatures.clear();
                }
                renderScheduleBannersDiff(list);
                renderScheduleCardsDiff(list);
                applyCookieDependentUi();
                if (langChanged) {
                    // 展开的「本轮队列详情」：先用 localizer 即时重渲染（已含 rawStatus 的新模型立即切语言），
                    // 再异步触发一次 fetchScheduleQueue 让后端有新数据时重建模型；旧版本 bake 过翻译的
                    // 缓存项需要靠这次重建才能跟随语言变化。
                    scheduleExpandedQueues.forEach(id => {
                        renderScheduleQueueBodyInto(id);
                        fetchScheduleQueue(id);
                    });
                    scheduleTasksCache.forEach(t => {
                        const panel = document.getElementById(`schedule-pending-${t.id}`);
                        if (panel && !panel.hidden) loadPendingPanel(t.id);
                    });
                }
            }
            scheduleLastRenderedLang = currentLang;
            // 无论是否重建整列：运行 / 排队中的展开卡片随本轮轮询拉取最新队列快照，非运行态保持缓存。
            refreshExpandedScheduleQueues();
        } catch (e) {
            renderScheduleListPlaceholder(list, bt('schedule.list.load-failed', '加载失败'));
        }
    }

    function renderScheduleListPlaceholder(list, text) {
        list.innerHTML = `<div class="schedule-empty">${escHtml(text)}</div>`;
        scheduleEmptyStateRendered = true;
        scheduleBannerSignature = null;
        scheduleCardSignatures.clear();
    }

    // 横幅按 accountId 分组、与卡片解耦：签名只看挂起账号的集合与各账号挂起任务计数，签名不变就不动 DOM。
    function renderScheduleBannersDiff(list) {
        const sig = scheduleBannerRenderSignature(scheduleTasksCache);
        if (sig === scheduleBannerSignature) return;
        scheduleBannerSignature = sig;
        const html = renderOveruseBanners(scheduleTasksCache);
        let wrap = list.querySelector(':scope > .schedule-overuse-banners');
        if (html) {
            const wrapped = `<div class="schedule-overuse-banners">${html}</div>`;
            if (wrap) {
                wrap.outerHTML = wrapped;
            } else {
                list.insertAdjacentHTML('afterbegin', wrapped);
            }
        } else if (wrap) {
            wrap.remove();
        }
    }

    // 按卡片 diff：仅对签名变化的卡片做替换，替换时把内部「队列正文/待重试面板」的 innerHTML、scrollTop、
    // 展开折叠态原样迁移到新卡片，避免「点暂停/恢复 → 整列 innerHTML 重建 → 展开的队列 DOM 被销毁 →
    // 队列滚动条回到顶部」这条问题路径。
    function renderScheduleCardsDiff(list) {
        const existing = new Map();
        list.querySelectorAll(':scope > .schedule-card').forEach(card => {
            existing.set(Number(card.dataset.taskId), card);
        });
        const liveIds = new Set();
        // 锚点：插入卡片的位置紧跟横幅之后（或在列表最前端，如无横幅）。
        const banners = list.querySelector(':scope > .schedule-overuse-banners');
        let prev = banners;
        scheduleTasksCache.forEach(t => {
            const id = Number(t.id);
            liveIds.add(id);
            const sig = scheduleCardRenderSignature(t);
            let card = existing.get(id);
            if (!card) {
                card = buildScheduleCardElement(t);
                scheduleCardSignatures.set(id, sig);
            } else if (sig !== scheduleCardSignatures.get(id)) {
                card = replaceScheduleCardPreservingInner(card, t);
                scheduleCardSignatures.set(id, sig);
            }
            const expected = prev ? prev.nextElementSibling : list.firstElementChild;
            if (expected !== card) {
                if (prev) prev.insertAdjacentElement('afterend', card);
                else list.insertAdjacentElement('afterbegin', card);
            }
            prev = card;
        });
        existing.forEach((card, id) => {
            if (!liveIds.has(id)) {
                card.remove();
                scheduleCardSignatures.delete(id);
            }
        });
    }

    function buildScheduleCardElement(t) {
        const temp = document.createElement('div');
        temp.innerHTML = renderScheduleTaskCard(t).trim();
        return temp.firstElementChild;
    }

    // 替换一张卡片但保留两个有状态子区块（队列正文 / 待重试面板）的 DOM 状态：内 HTML / 隐藏态 / 滚动位置。
    // 队列正文的折叠 caret / aria-expanded 同步矫正为保留下来的展开态。
    function replaceScheduleCardPreservingInner(existingCard, t) {
        const oldQueueBody = existingCard.querySelector('.schedule-queue-body');
        const oldPending = existingCard.querySelector('.schedule-pending-panel');
        // 卡片顶部 tips 区域：保留刚刚因操作写入的反馈，避免轮询重渲染把它清掉。
        const oldTip = existingCard.querySelector('.schedule-card-tip');
        const tipState = oldTip ? {text: oldTip.textContent, color: oldTip.style.color} : null;
        // 真正的滚动容器是 .schedule-queue-body 内部的 .schedule-queue-list（见 renderScheduleQueueBodyInto），
        // 因此 scrollTop 取内层 list 的值，替换后再写回新 list 上，避免队列滚动条跳回顶部。
        const oldQueueList = oldQueueBody ? oldQueueBody.querySelector('.schedule-queue-list') : null;
        const queueState = oldQueueBody ? {
            inner: oldQueueBody.innerHTML,
            scrollTop: oldQueueList ? oldQueueList.scrollTop : 0,
            expanded: !oldQueueBody.hasAttribute('hidden')
        } : null;
        const pendingState = oldPending ? {
            inner: oldPending.innerHTML,
            expanded: !oldPending.hasAttribute('hidden')
        } : null;

        const newCard = buildScheduleCardElement(t);

        const newQueueBody = newCard.querySelector('.schedule-queue-body');
        if (newQueueBody && queueState) {
            newQueueBody.innerHTML = queueState.inner;
            if (queueState.expanded) newQueueBody.removeAttribute('hidden');
            else newQueueBody.setAttribute('hidden', '');
            const toggle = newCard.querySelector('.schedule-queue-toggle');
            if (toggle) {
                toggle.setAttribute('aria-expanded', String(queueState.expanded));
                const caret = toggle.querySelector('.schedule-queue-caret');
                if (caret) caret.textContent = queueState.expanded ? '▾' : '▸';
            }
        }
        const newPending = newCard.querySelector('.schedule-pending-panel');
        if (newPending && pendingState) {
            newPending.innerHTML = pendingState.inner;
            if (pendingState.expanded) newPending.removeAttribute('hidden');
            else newPending.setAttribute('hidden', '');
        }
        const newTip = newCard.querySelector('.schedule-card-tip');
        if (newTip && tipState && tipState.text) {
            newTip.textContent = tipState.text;
            newTip.style.color = tipState.color;
        }

        existingCard.replaceWith(newCard);

        // scrollTop 必须在 element 已经在 document 中之后设置，否则浏览器会丢弃。
        if (newQueueBody && queueState && queueState.scrollTop) {
            const newQueueList = newQueueBody.querySelector('.schedule-queue-list');
            if (newQueueList) newQueueList.scrollTop = queueState.scrollTop;
        }
        return newCard;
    }

    function renderScheduleTaskCard(t) {
        const params = parseScheduleParams(t);
        const kind = scheduleKindFromParams(params);
        const typeLabel = scheduleTypeLabel(t.type);
        const kindLabel = scheduleKindLabel(kind);
        const triggerLabel = scheduleTriggerLabel(t);
        const cookieLabel = t.cookieBound
            ? bt('schedule.cookie.bound', '已绑定 Cookie')
            : bt('schedule.cookie.restricted', '受限模式（无 Cookie）');
        const enabledLabel = t.enabled ? bt('schedule.state.enabled', '已启用') : bt('schedule.state.disabled', '已停用');
        const light = scheduleStatusLight(t);

        // 功能区按钮的状态门（与后端 ScheduleService 守卫一致）：
        // busy=运行/排队中；suspended=暂停/过度访问/cookie 失效。
        const busy = t.runState === 'RUNNING' || t.runState === 'QUEUED';
        const paused = t.lastStatus === 'PAUSED';
        const suspended = paused || t.lastStatus === 'OVERUSE_PAUSED' || t.lastStatus === 'AUTH_EXPIRED';
        const busyTip = bt('schedule.disabled.busy', '任务运行 / 排队中，暂不可操作');
        const runTip = busy ? busyTip
            : (!t.enabled ? bt('schedule.disabled.run-disabled', '任务已停用，请先启用')
                : bt('schedule.disabled.run-suspended', '任务暂停 / 挂起中，请先恢复或重新授权'));
        const pauseTip = bt('schedule.disabled.pause-idle', '任务未在运行，无需暂停；如需停止自动运行请用「停用」');
        const runAttr = (t.enabled && !busy && !suspended) ? '' : `disabled title="${escHtml(runTip)}"`;
        const resumeAttr = !busy ? '' : `disabled title="${escHtml(busyTip)}"`;
        const pauseAttr = busy ? '' : `disabled title="${escHtml(pauseTip)}"`;
        const busyAttr = busy ? `disabled title="${escHtml(busyTip)}"` : '';
        return `
        <div class="schedule-card${t.enabled ? '' : ' schedule-card-disabled'}" data-task-id="${t.id}">
            <div class="schedule-card-tip" id="schedule-card-tip-${t.id}" role="status" aria-live="polite"></div>
            <div class="schedule-card-head">
                <div class="schedule-card-head-main">
                    <span class="schedule-card-name">${escHtml(t.name)}</span>
                    <span class="schedule-badge">${escHtml(typeLabel)}</span>
                    <span class="schedule-badge">${escHtml(kindLabel)}</span>
                    <span class="schedule-badge${t.cookieBound ? ' schedule-badge-ok' : ''}">${escHtml(cookieLabel)}</span>
                    <span class="schedule-badge${t.enabled ? ' schedule-badge-ok' : ' schedule-badge-disabled'}">${escHtml(enabledLabel)}</span>
                </div>
                <span class="schedule-status-light schedule-status-light-${light.tone}${light.live ? ' schedule-status-light-live' : ''}" title="${escHtml(light.text)}">
                    <span class="schedule-light-dot" aria-hidden="true"></span>
                    <span class="schedule-light-text">${escHtml(light.text)}</span>
                </span>
            </div>
            <div class="schedule-card-meta">
                <div>${escHtml(bt('schedule.meta.trigger', '触发：'))}${escHtml(triggerLabel)}</div>
                <div>${escHtml(bt('schedule.meta.next', '下次运行：'))}${escHtml(fmtScheduleTime(t.nextRunTime))}</div>
                <div>${escHtml(bt('schedule.meta.last', '上次运行：'))}${escHtml(fmtScheduleTime(t.lastRunTime))}</div>
                <div class="schedule-meta-actions">
                    <button type="button" class="btn btn-blue" onclick="showScheduleSnapshot(${t.id})">${escHtml(bt('schedule.snapshot.action.view', '查看任务快照信息'))}</button>
                </div>
            </div>
            <div class="schedule-card-actions">
                <button class="btn btn-cyan" ${runAttr} onclick="runScheduleTask(${t.id})">${escHtml(bt('schedule.action.run', '▶ 立即运行'))}</button>
                <span class="cookie-dependent-action"><button class="btn btn-blue js-authorize-cookie-btn" data-busy="${busy ? '1' : '0'}" onclick="authorizeScheduleCookie(${t.id})">${escHtml(bt('schedule.action.authorize', '🔑 授权 Cookie'))}</button></span>
                ${paused
                    ? `<button class="btn btn-green" ${resumeAttr} onclick="resumeScheduleTask(${t.id})">${escHtml(bt('schedule.action.resume', '▶ 恢复'))}</button>`
                    : `<button class="btn btn-yellow" ${pauseAttr} onclick="pauseScheduleTask(${t.id})">${escHtml(bt('schedule.action.pause', '⏸ 暂停'))}</button>`}
                <button class="btn ${t.enabled ? 'btn-red' : 'btn-green'}" ${busyAttr} onclick="toggleScheduleTask(${t.id}, ${t.enabled ? 'false' : 'true'})">${escHtml(t.enabled ? bt('schedule.action.disable', '⏸ 停用') : bt('schedule.action.enable', '✔ 启用'))}</button>
                <button class="btn btn-purple" ${busyAttr} onclick="startEditScheduleTask(${t.id})">${escHtml(bt('schedule.action.edit', '✏ 编辑'))}</button>
                <button class="btn btn-gray" onclick="togglePendingPanel(${t.id})">${escHtml(bt('schedule.action.pending', '🧩 待重试'))}</button>
                <button class="btn btn-red" ${busyAttr} onclick="deleteScheduleTask(${t.id})">${escHtml(bt('schedule.action.delete', '🗑 删除'))}</button>
            </div>
            <div class="schedule-pending-panel" id="schedule-pending-${t.id}" hidden></div>
            ${renderScheduleQueueSection(t)}
        </div>`;
    }

    // 卡片底部「本轮队列详情」可折叠区域：默认折叠；展开态在列表重渲染后从 scheduleExpandedQueues 恢复，
    // 并直接用本地缓存预填充内容（避免闪烁），随后 refreshExpandedScheduleQueues / 展开动作再拉取最新数据。
    function renderScheduleQueueSection(t) {
        const id = Number(t.id);
        const expanded = scheduleExpandedQueues.has(id);
        const title = escHtml(bt('schedule.queue.title', '本轮队列详情'));
        const bodyHtml = expanded ? renderScheduleQueueBody(id) : '';
        return `
            <div class="schedule-queue" data-task-id="${id}">
                <button type="button" class="schedule-queue-toggle" aria-expanded="${expanded}" onclick="toggleScheduleQueue(${id})">
                    <span class="schedule-queue-caret" aria-hidden="true">${expanded ? '▾' : '▸'}</span>
                    <span>${title}</span>
                </button>
                <div class="schedule-queue-body"${expanded ? '' : ' hidden'}>${bodyHtml}</div>
            </div>`;
    }

    function scheduleQueueCacheKey(id) {
        return 'pixiv_schedule_queue_' + Number(id);
    }

    function readScheduleQueueCache(id) {
        try {
            const raw = storeGet(scheduleQueueCacheKey(id));
            if (!raw) return null;
            const parsed = JSON.parse(raw);
            return parsed && Array.isArray(parsed.items) ? parsed : null;
        } catch (e) {
            return null;
        }
    }

    function writeScheduleQueueCache(id, data) {
        try {
            storeSet(scheduleQueueCacheKey(id), JSON.stringify(data));
        } catch (e) { /* 存储不可用时忽略：内存渲染仍可工作 */ }
    }

    // 计划任务「本轮队列详情」的客户端模型：taskId → 队列项数组（与下载工作区 state.queue 同形，
    // 直接喂给 buildQueueItemHtml 渲染，保证两处队列完全一致）。后端 4s 快照提供权威的发现/终态，
    // SSE 提供运行中的逐图实时进度。
    const scheduleQueueModels = {};
    // 已登记的 SSE 监听器：taskId → { artworkIdKey: fn }，用于精确解绑、避免重复注册或误删工作区监听。
    const scheduleSseHandlers = {};
    // 上一轮轮询时仍在运行的展开任务：用于在运行结束的那一拍补拉一次最终终态快照。
    const scheduleQueueWasRunning = new Set();

    // 取某任务的队列模型：内存优先，缺失时从 localStorage 缓存恢复（支持刷新 / 服务重启后继续展示）。
    function getScheduleQueueModel(id) {
        id = Number(id);
        if (scheduleQueueModels[id]) return scheduleQueueModels[id];
        const cache = readScheduleQueueCache(id);
        if (cache && Array.isArray(cache.items)) {
            scheduleQueueModels[id] = cache.items;
            return cache.items;
        }
        return null;
    }

    function getScheduleQueueMeta(id) {
        const cache = readScheduleQueueCache(id);
        return {
            startedTime: cache ? cache.startedTime : null,
            truncated: cache ? !!cache.truncated : false,
            total: cache && typeof cache.total === 'number' ? cache.total : null
        };
    }

    function scheduleTaskById(id) {
        return scheduleTasksCache.find(t => Number(t.id) === Number(id)) || null;
    }

    // 任务类型 → 队列项来源标识，复用工作区队列项的来源色块（user / search / series）。
    function scheduleSourceForType(type) {
        if (type === 'SEARCH') return 'search';
        if (type === 'SERIES') return 'series';
        return 'user';
    }

    // 后端队列项状态 → 工作区队列状态 + 原始状态码（未翻译，渲染时再 bt()）。
    // 不在这里 bake bt() 结果：模型会落到 localStorage 与跨语言切换的渲染轮次，bake 后无法跟随语言变化。
    function scheduleStatusToQueue(it) {
        switch (it.status) {
            case 'downloaded':
                return {status: 'completed', rawStatus: 'downloaded'};
            case 'skipped-downloaded':
                return {status: 'skipped', rawStatus: 'skipped-downloaded'};
            case 'skipped-filter':
                return {status: 'skipped', rawStatus: 'skipped-filter'};
            case 'failed':
                return {status: 'failed', rawStatus: 'failed', failureMessage: it.message || null};
            default:
                return {status: 'pending', rawStatus: 'pending'};
        }
    }

    // 后端队列项 → 工作区队列项（同 state.queue 形状），供 buildQueueItemHtml 渲染。
    // 注意：title / lastMessage 不在这里写入；只存 rawTitle / rawStatus / failureMessage 这些与语言
    // 无关的原始字段，渲染时由 localizeScheduleQueueItem 用当前 bt() 派生 title / lastMessage。
    function scheduleItemToQueue(it, type) {
        const isNovel = it.kind === 'novel';
        const rawId = String(it.id == null ? '' : it.id);
        const mapped = scheduleStatusToQueue(it);
        return {
            id: isNovel ? ('n' + rawId) : rawId,
            novelId: isNovel ? rawId : undefined,
            kind: isNovel ? 'novel' : 'illust',
            rawTitle: it.title && String(it.title).trim() ? String(it.title) : null,
            source: scheduleSourceForType(type),
            xRestrict: it.xRestrict == null ? null : it.xRestrict,
            isAi: it.ai === true,
            status: mapped.status,
            rawStatus: mapped.rawStatus,
            failureMessage: mapped.failureMessage || null,
            totalImages: 0,
            downloadedCount: 0,
            imageProgress: null,
            ugoiraProgress: null
        };
    }

    // 渲染前根据当前 UI 语言派生显示字段。模型里禁止 bake i18n 字符串（会被持久化到 localStorage、
    // 跨语言切换继续读到旧译文），所有翻译都集中在这里。
    // 兼容旧缓存：若 rawTitle / rawStatus 不存在（旧版烤过 title / lastMessage 的缓存项），回退用旧字段，
    // 这些条目要到下一次后端拉取重建模型后才会跟随语言切换。
    function localizeScheduleQueueItem(q) {
        const hasRawTitle = Object.prototype.hasOwnProperty.call(q, 'rawTitle');
        const title = hasRawTitle
            ? (q.rawTitle || bt('schedule.queue.no-title', '（暂无标题信息）'))
            : q.title;
        let lastMessage;
        switch (q.rawStatus) {
            case 'skipped-downloaded':
                lastMessage = bt('schedule.queue.status.skipped-downloaded', '已存在，跳过');
                break;
            case 'skipped-filter':
                lastMessage = bt('schedule.queue.status.skipped-filter', '被筛选条件跳过');
                break;
            case 'failed':
                lastMessage = q.failureMessage || bt('schedule.queue.status.failed', '失败');
                break;
            case 'downloaded':
            case 'pending':
                lastMessage = null;
                break;
            default:
                // 旧缓存或 SSE 中途置位（如 downloading / completed-from-pending）没有 rawStatus；
                // lastMessage 留给共享渲染器用 queueStatusText(status) 兜底。
                lastMessage = q.lastMessage != null ? q.lastMessage : null;
        }
        return Object.assign({}, q, {title, lastMessage});
    }

    // 用后端快照重建模型，同时保留 SSE 实时进度：后端仍为 pending 而本地正在下载时沿用本地实时态，
    // 避免每 4s 快照把进行中的进度条打回原形。
    function mergeScheduleQueueModel(id, incoming, type) {
        const prev = scheduleQueueModels[Number(id)] || [];
        const prevById = {};
        prev.forEach(q => { prevById[q.id] = q; });
        return incoming.map(it => {
            const q = scheduleItemToQueue(it, type);
            const old = prevById[q.id];
            if (old && q.status === 'pending' && old.status === 'downloading') {
                q.status = 'downloading';
                q.totalImages = old.totalImages || 0;
                q.downloadedCount = old.downloadedCount || 0;
                q.imageProgress = old.imageProgress || null;
                q.ugoiraProgress = old.ugoiraProgress || null;
            } else if (old) {
                q.totalImages = q.totalImages || old.totalImages || 0;
                q.downloadedCount = q.downloadedCount || old.downloadedCount || 0;
            }
            return q;
        });
    }

    function renderScheduleQueueBodyInto(id) {
        if (!scheduleExpandedQueues.has(Number(id))) return;
        const wrap = document.querySelector(`.schedule-queue[data-task-id="${Number(id)}"]`);
        if (!wrap) return;
        const body = wrap.querySelector('.schedule-queue-body');
        if (!body) return;
        // 保留滚动位置：SSE / 快照刷新会替换正文 innerHTML，不保留则滚动条每次跳回顶部。
        const prevList = body.querySelector('.schedule-queue-list');
        const prevScroll = prevList ? prevList.scrollTop : 0;
        body.innerHTML = renderScheduleQueueBody(id);
        const newList = body.querySelector('.schedule-queue-list');
        if (newList) newList.scrollTop = prevScroll;
    }

    // 展开某任务的队列：切换箭头，先用缓存模型即时渲染，再向后端拉取最新一轮队列；折叠则不请求并解绑 SSE。
    function toggleScheduleQueue(id) {
        id = Number(id);
        const wrap = document.querySelector(`.schedule-queue[data-task-id="${id}"]`);
        if (!wrap) return;
        const body = wrap.querySelector('.schedule-queue-body');
        const toggleBtn = wrap.querySelector('.schedule-queue-toggle');
        const caret = wrap.querySelector('.schedule-queue-caret');
        if (scheduleExpandedQueues.has(id)) {
            scheduleExpandedQueues.delete(id);
            unsubscribeScheduleQueueSse(id);
            if (body) body.hidden = true;
            if (toggleBtn) toggleBtn.setAttribute('aria-expanded', 'false');
            if (caret) caret.textContent = '▸';
            return;
        }
        scheduleExpandedQueues.add(id);
        if (body) {
            body.hidden = false;
            body.innerHTML = renderScheduleQueueBody(id); // 缓存模型即时渲染（可能为空）
        }
        if (toggleBtn) toggleBtn.setAttribute('aria-expanded', 'true');
        if (caret) caret.textContent = '▾';
        fetchScheduleQueue(id); // 访问即拉取最新
    }

    // 列表重渲染后：运行 / 排队中的展开卡片拉取最新队列（实现「运行中展开则自动刷新」），
    // 运行刚结束的那一拍补拉一次终态快照，其余非运行态保持缓存渲染、撤掉 SSE 监听。
    function refreshExpandedScheduleQueues() {
        scheduleTasksCache.forEach(t => {
            const id = Number(t.id);
            const running = t.runState === 'RUNNING' || t.runState === 'QUEUED';
            if (!scheduleExpandedQueues.has(id)) {
                scheduleQueueWasRunning.delete(id);
                return;
            }
            if (running) {
                scheduleQueueWasRunning.add(id);
                fetchScheduleQueue(id);
            } else if (scheduleQueueWasRunning.has(id)) {
                scheduleQueueWasRunning.delete(id);
                fetchScheduleQueue(id); // 运行刚结束：拉取最终终态快照
            } else {
                unsubscribeScheduleQueueSse(id);
            }
        });
    }

    async function fetchScheduleQueue(id) {
        id = Number(id);
        const task = scheduleTaskById(id);
        try {
            const res = await fetch(`${BASE}/api/schedule/tasks/${id}/queue`, {credentials: 'same-origin'});
            if (!res.ok) return; // 失败时保留已有模型渲染
            const data = await res.json();
            const incoming = Array.isArray(data.items) ? data.items : [];
            const cached = readScheduleQueueCache(id);
            // 后端无当轮队列（如进程重启后）而本地仍有缓存时，保留缓存继续展示，不被空队列覆盖。
            const keepCache = incoming.length === 0 && data.startedTime == null
                && cached && Array.isArray(cached.items) && cached.items.length > 0;
            if (!keepCache) {
                const model = mergeScheduleQueueModel(id, incoming, task ? task.type : null);
                scheduleQueueModels[id] = model;
                writeScheduleQueueCache(id, {
                    startedTime: data.startedTime != null ? data.startedTime : null,
                    truncated: !!data.truncated,
                    total: typeof data.total === 'number' ? data.total : model.length,
                    items: model,
                    savedAt: Date.now()
                });
            }
            renderScheduleQueueBodyInto(id);
            // 运行中 + 展开：订阅 SSE 逐图实时进度；否则解绑。
            if (scheduleExpandedQueues.has(id) && task && (task.runState === 'RUNNING' || task.runState === 'QUEUED')) {
                subscribeScheduleQueueSse(id);
            } else {
                unsubscribeScheduleQueueSse(id);
            }
        } catch (e) { /* 网络异常：保留模型渲染 */ }
    }

    // 按工作区口径统计模型各状态计数（与 updateStats 同义）。
    function computeScheduleQueueStats(model) {
        const count = s => model.filter(q => q.status === s).length;
        return {
            success: count('completed'),
            failed: count('failed'),
            active: count('downloading'),
            skipped: count('skipped'),
            pending: model.filter(q => ['idle', 'pending', 'paused'].includes(q.status)).length
        };
    }

    // 完整照搬下载工作区底部的「状态栏 + 统计栏 + 当前下载卡 + 下载队列」四段结构，
    // 仅把数据源换成本任务的队列模型；各段分别复用 #status-bar / #stats-bar / #current-card / #queue-list 的样式与格式化函数。
    function renderScheduleQueueBody(id) {
        const model = getScheduleQueueModel(id) || [];
        const task = scheduleTaskById(id);
        const meta = getScheduleQueueMeta(id);

        // 状态栏（对应 #status-bar）：任务运行状态文案 + 本轮开始时间 + 截断提示
        let statusText = task ? scheduleStatusLight(task).text : bt('schedule.light.never', '等待首次运行');
        if (meta.startedTime) {
            statusText += ' · ' + bt('schedule.queue.started', '本轮开始：{time}', {time: fmtScheduleTime(meta.startedTime)});
        }
        if (meta.truncated) {
            statusText += ' · ' + bt('schedule.queue.truncated', '作品过多，仅记录并展示前 {count} 项', {count: model.length});
        }
        const s = computeScheduleQueueStats(model);
        // 渲染前用 localizeScheduleQueueItem 派生 title / lastMessage，确保跟随当前 UI 语言；
        // 模型本身仍是 raw 字段，下次语言切换重渲染再次派生即可。
        const localized = model.map(localizeScheduleQueueItem);
        const current = localized.find(q => q.status === 'downloading') || null;

        const statusLine = `<div class="schedule-queue-status">${escHtml(statusText)}</div>`;
        const statsLine = `<div class="schedule-queue-stats">${escHtml(formatStatsText(s.pending, s.success, s.failed, s.active, s.skipped))}</div>`;
        const currentCard = `<div class="schedule-queue-current">${formatCurrentCardHtml(current)}</div>`;
        const listInner = localized.length
            ? localized.map(q => buildQueueItemHtml(q, {removable: false})).join('')
            : `<div class="queue-empty">${escHtml(bt('status.queue-empty', '队列为空'))}</div>`;
        const listCard = `<div class="schedule-queue-list">${listInner}</div>`;
        return statusLine + statsLine + currentCard + listCard;
    }

    // ── 计划任务队列的 SSE 实时进度同步（复用工作区的聚合 EventSource） ──────────────────────────
    // 管理员的聚合 SSE 会收到全部下载进度事件（含计划任务后台下载，userUuid=null）。运行中展开队列时，
    // 为每个插画项按 artworkId 注册监听，把逐图进度并入模型并重渲染；折叠 / 运行结束即解绑。
    function subscribeScheduleQueueSse(id) {
        id = Number(id);
        const model = scheduleQueueModels[id];
        if (!model) return;
        ensureSharedSSE();
        if (!scheduleSseHandlers[id]) scheduleSseHandlers[id] = {};
        const handlers = scheduleSseHandlers[id];
        model.forEach(q => {
            if (q.kind === 'novel') return; // 小说无逐图 SSE，靠 4s 快照同步终态
            const key = String(q.id);
            if (handlers[key]) return; // 已注册
            const fn = data => applyScheduleQueueSse(id, key, data);
            handlers[key] = fn;
            addSSEListener(key, fn);
        });
    }

    function unsubscribeScheduleQueueSse(id) {
        id = Number(id);
        const handlers = scheduleSseHandlers[id];
        if (!handlers) return;
        Object.keys(handlers).forEach(key => removeSSEListener(key, handlers[key]));
        delete scheduleSseHandlers[id];
    }

    function unsubscribeAllScheduleQueueSse() {
        Object.keys(scheduleSseHandlers).forEach(id => unsubscribeScheduleQueueSse(id));
    }

    // 计划任务列表刷新后，对已不在 liveIds 中的任务连带清理：解绑 SSE / 删除内存模型 / 撤掉展开态 /
    // 移除本地缓存。覆盖 scheduleExpandedQueues、scheduleQueueModels、scheduleSseHandlers、
    // scheduleQueueWasRunning 四张表，避免任一处残留导致旧 handler 继续消费事件或阻止
    // stopSchedulePolling 关闭共享 SSE 连接。
    function releaseStaleScheduleQueueIds(liveIds) {
        const stale = new Set();
        for (const id of scheduleExpandedQueues) if (!liveIds.has(id)) stale.add(id);
        Object.keys(scheduleQueueModels).forEach(k => {
            const id = Number(k);
            if (!liveIds.has(id)) stale.add(id);
        });
        Object.keys(scheduleSseHandlers).forEach(k => {
            const id = Number(k);
            if (!liveIds.has(id)) stale.add(id);
        });
        for (const id of scheduleQueueWasRunning) if (!liveIds.has(id)) stale.add(id);
        stale.forEach(id => {
            unsubscribeScheduleQueueSse(id);
            scheduleExpandedQueues.delete(id);
            delete scheduleQueueModels[id];
            scheduleQueueWasRunning.delete(id);
            try { storeRemove(scheduleQueueCacheKey(id)); } catch (e) { /* 存储不可用：忽略 */ }
        });
    }

    function applyScheduleQueueSse(id, qId, data) {
        id = Number(id);
        const model = scheduleQueueModels[id];
        if (!model || !data) return;
        const q = model.find(x => x.id === qId);
        if (!q || data.cancelled) return;
        // SSE 同步对齐 rawStatus，让 localizeScheduleQueueItem 在渲染时派生出正确语言的 lastMessage；
        // downloading 不对应后端 raw 状态，置为 'downloading' 与 q.status 同步，localizer 走默认分支
        // 让共享渲染器用 queueStatusText(status) 兜底显示「下载中」。
        if (data.completed) {
            q.status = 'completed';
            q.rawStatus = 'downloaded';
        } else if (data.failed) {
            q.status = 'failed';
            q.rawStatus = 'failed';
        } else if (data.downloadedCount !== undefined || data.totalImages !== undefined) {
            q.status = 'downloading';
            q.rawStatus = 'downloading';
            if (data.totalImages !== undefined) q.totalImages = data.totalImages;
            if (data.downloadedCount !== undefined) q.downloadedCount = data.downloadedCount;
            q.imageProgress = data.imageProgress || q.imageProgress || null;
            q.ugoiraProgress = mergeUgoiraProgress(q.ugoiraProgress, data.ugoiraProgress);
        }
        renderScheduleQueueBodyInto(id);
    }

    async function runScheduleTask(id) {
        try {
            const res = await fetch(`${BASE}/api/schedule/tasks/${id}/run`, {method: 'POST', credentials: 'same-origin'});
            if (res.ok) {
                setScheduleCardTip(id, bt('schedule.status.run-started', '已开始后台运行'), 'success');
                // 立即刷新：后端 runOnce 同步阶段已把 runState 置为 QUEUED，刷新后状态灯立刻切到「排队中 → 运行中」，
                // 不必等下一拍 4s 轮询。
                await loadScheduleTasks();
            } else {
                // 状态门拒绝（陈旧 UI / 竞态：点击瞬间任务刚进入运行 / 挂起态）。刷新让按钮回到正确禁用态。
                setScheduleCardTip(id, bt('schedule.error.run', '当前状态不允许立即运行'), 'error');
                await loadScheduleTasks();
            }
        } catch (e) { /* ignore */ }
    }

    async function toggleScheduleTask(id, enabled) {
        try {
            await fetch(`${BASE}/api/schedule/tasks/${id}/enabled?enabled=${enabled}`,
                {method: 'POST', credentials: 'same-origin'});
            loadScheduleTasks();
        } catch (e) { /* ignore */ }
    }

    // 过度访问账号级暂停横幅：把 OVERUSE_PAUSED 的任务按 accountId 分组，每组给两个账号级恢复按钮。
    function renderOveruseBanners(tasks) {
        const groups = new Map();
        tasks.forEach(t => {
            if (t.lastStatus !== 'OVERUSE_PAUSED' || !t.accountId) return;
            const list = groups.get(t.accountId) || [];
            list.push(t);
            groups.set(t.accountId, list);
        });
        if (groups.size === 0) return '';
        const defaultMinutes = 60;
        let html = '';
        groups.forEach((list, accountId) => {
            const acc = encodeURIComponent(accountId);
            html += `
            <div class="schedule-overuse-banner">
                <div class="schedule-overuse-title">${escHtml(bt('schedule.overuse.banner.title', '⚠️ 过度访问暂停'))}</div>
                <div class="schedule-overuse-desc">${escHtml(bt('schedule.overuse.banner.desc',
                    '账号 {account} 有 {count} 个计划任务因检测到 Pixiv 过度访问警告被暂停。',
                    {account: accountId, count: list.length}))}</div>
                <div class="schedule-overuse-actions">
                    <button class="btn btn-red" onclick="resumeScheduleAccountIgnore('${acc}')">${escHtml(bt('schedule.overuse.action.ignore', '无视风险，继续下载！(可能会导致删号)'))}</button>
                    <button class="btn btn-blue" onclick="resumeScheduleAccountDefer('${acc}')">${escHtml(bt('schedule.overuse.action.defer', '我已知晓，在 {minutes} 分钟后继续所有同账号任务', {minutes: defaultMinutes}))}</button>
                </div>
            </div>`;
        });
        return html;
    }

    async function pauseScheduleTask(id) {
        try {
            const res = await fetch(`${BASE}/api/schedule/tasks/${id}/pause`, {method: 'POST', credentials: 'same-origin'});
            if (res.ok) {
                setScheduleCardTip(id, bt('schedule.status.paused', '已暂停该任务'), 'success');
                loadScheduleTasks();
            } else {
                setScheduleCardTip(id, bt('schedule.error.pause', '暂停失败'), 'error');
            }
        } catch (e) {
            setScheduleCardTip(id, bt('schedule.error.pause', '暂停失败'), 'error');
        }
    }

    async function resumeScheduleTask(id) {
        try {
            const res = await fetch(`${BASE}/api/schedule/tasks/${id}/resume`, {method: 'POST', credentials: 'same-origin'});
            if (res.ok) {
                setScheduleCardTip(id, bt('schedule.status.resumed', '已恢复该任务'), 'success');
                loadScheduleTasks();
            } else {
                setScheduleCardTip(id, bt('schedule.error.resume', '恢复失败'), 'error');
            }
        } catch (e) {
            setScheduleCardTip(id, bt('schedule.error.resume', '恢复失败'), 'error');
        }
    }

    async function resumeScheduleAccount(accountId, mode, minutes) {
        const body = {mode};
        if (mode === 'defer') body.minutes = minutes;
        try {
            const res = await fetch(`${BASE}/api/schedule/account/${accountId}/resume`, {
                method: 'POST',
                credentials: 'same-origin',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify(body)
            });
            if (res.ok) {
                setScheduleListStatus(bt('schedule.status.account-resumed', '已恢复该账号的所有任务'), 'success');
                loadScheduleTasks();
            } else {
                const err = await res.json().catch(() => ({}));
                setScheduleListStatus(err.message || bt('schedule.error.resume', '恢复失败'), 'error');
            }
        } catch (e) {
            setScheduleListStatus(bt('schedule.error.resume', '恢复失败'), 'error');
        }
    }

    function resumeScheduleAccountIgnore(accountId) {
        if (!confirm(bt('schedule.overuse.confirm.ignore',
            '确定无视过度访问警告并立即继续下载吗？短时间内继续大量下载可能导致账号被封禁。'))) return;
        resumeScheduleAccount(accountId, 'ignore');
    }

    function resumeScheduleAccountDefer(accountId) {
        const input = prompt(bt('schedule.overuse.prompt.minutes', '延迟多少分钟后继续？（最低 60）'), '60');
        if (input == null) return;
        let minutes = parseInt(input, 10);
        if (!Number.isFinite(minutes) || minutes < 60) minutes = 60;
        resumeScheduleAccount(accountId, 'defer', minutes);
    }

    async function togglePendingPanel(id) {
        const panel = document.getElementById(`schedule-pending-${id}`);
        if (!panel) return;
        if (!panel.hidden) {
            panel.hidden = true;
            return;
        }
        panel.hidden = false;
        await loadPendingPanel(id);
    }

    async function loadPendingPanel(id) {
        const panel = document.getElementById(`schedule-pending-${id}`);
        if (!panel) return;
        try {
            const res = await fetch(`${BASE}/api/schedule/tasks/${id}/pending`, {credentials: 'same-origin'});
            if (!res.ok) {
                panel.innerHTML = `<div class="schedule-pending-empty">${escHtml(bt('schedule.pending.load-failed', '加载待重试列表失败'))}</div>`;
                return;
            }
            const items = await res.json();
            if (!Array.isArray(items) || items.length === 0) {
                panel.innerHTML = `<div class="schedule-pending-empty">${escHtml(bt('schedule.pending.empty', '暂无待重试作品'))}</div>`;
                return;
            }
            const task = scheduleTaskById(id);
            const busy = !!task && (task.runState === 'RUNNING' || task.runState === 'QUEUED');
            const clearAttr = busy
                ? `disabled title="${escHtml(bt('schedule.disabled.busy', '任务运行 / 排队中，暂不可操作'))}"`
                : '';
            const rows = items.map(p => {
                const manual = p.needsManual ? bt('schedule.pending.needs-manual', '（需人工）') : '';
                const line = escHtml(bt('schedule.pending.item', '作品 {workId}：已重试 {attempts} 次{manual}',
                    {workId: p.workId, attempts: p.attempts, manual}));
                const reason = p.reason
                    ? `<div class="schedule-pending-reason">${escHtml(bt('schedule.pending.reason', '原因：{reason}', {reason: p.reason}))}</div>`
                    : '';
                return `<li class="schedule-pending-item${p.needsManual ? ' schedule-pending-manual' : ''}">
                    <div class="schedule-pending-line">${line}
                        <button class="btn btn-gray btn-xs" ${clearAttr} onclick="clearPendingItem(${id}, ${p.workId})">${escHtml(bt('schedule.pending.action.clear', '清除'))}</button>
                    </div>${reason}
                </li>`;
            }).join('');
            panel.innerHTML = `<div class="schedule-pending-head">${escHtml(bt('schedule.pending.title', '待重试 / 需人工'))}</div><ul class="schedule-pending-list">${rows}</ul>`;
        } catch (e) {
            panel.innerHTML = `<div class="schedule-pending-empty">${escHtml(bt('schedule.pending.load-failed', '加载待重试列表失败'))}</div>`;
        }
    }

    async function clearPendingItem(id, workId) {
        try {
            const res = await fetch(`${BASE}/api/schedule/tasks/${id}/pending/${workId}`, {method: 'DELETE', credentials: 'same-origin'});
            if (res.ok) {
                setScheduleCardTip(id, bt('schedule.status.pending-cleared', '已清除该条待重试记录'), 'success');
                await loadPendingPanel(id);
            }
        } catch (e) { /* ignore */ }
    }

    async function deleteScheduleTask(id) {
        if (!confirm(bt('schedule.confirm.delete', '确定删除这个计划任务吗？（绑定的 Cookie 也会被清除）'))) return;
        try {
            await fetch(`${BASE}/api/schedule/tasks/${id}`, {method: 'DELETE', credentials: 'same-origin'});
            loadScheduleTasks();
        } catch (e) { /* ignore */ }
    }

    async function authorizeScheduleCookie(id) {
        const cookie = getCookieInputHeaderString().trim();
        if (!cookie) {
            setScheduleCardTip(id, bt('schedule.error.no-cookie', '请先在上方 Cookie 卡片保存含 PHPSESSID 的 Cookie'), 'error');
            return;
        }
        if (!/(?:^|;\s*)PHPSESSID=/.test(cookie)) {
            setScheduleCardTip(id, bt('schedule.error.cookie-no-phpsessid', '当前 Cookie 不含 PHPSESSID，无法授权'), 'error');
            return;
        }
        try {
            const res = await fetch(`${BASE}/api/schedule/tasks/${id}/authorize-cookie`, {
                method: 'POST',
                credentials: 'same-origin',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({cookie})
            });
            if (!res.ok) {
                const err = await res.json().catch(() => ({}));
                setScheduleCardTip(id, err.message || bt('schedule.error.authorize', '授权失败'), 'error');
                return;
            }
            setScheduleCardTip(id, bt('schedule.status.authorized', 'Cookie 已授权绑定到该任务'), 'success');
            loadScheduleTasks();
        } catch (e) {
            setScheduleCardTip(id, bt('schedule.error.authorize', '授权失败'), 'error');
        }
    }

    // solo 模式创建任务后自动用当前输入框里的 Cookie 绑定该任务；best-effort，不阻断创建流程。
    // 返回值：'authorized' 成功 / 'no-cookie' 当前无可用含 PHPSESSID 的 Cookie / 'failed' 请求失败。
    async function autoAuthorizeScheduleCookie(id) {
        const cookie = getCookieInputHeaderString().trim();
        if (!cookie || !/(?:^|;\s*)PHPSESSID=/.test(cookie)) return 'no-cookie';
        try {
            const res = await fetch(`${BASE}/api/schedule/tasks/${id}/authorize-cookie`, {
                method: 'POST',
                credentials: 'same-origin',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({cookie})
            });
            return res.ok ? 'authorized' : 'failed';
        } catch (e) {
            return 'failed';
        }
    }

    /* ============================================================
       快捷获取（账户相关作品）
       ============================================================
       依赖当前 cookie 主人的 uid（由后端 /api/pixiv/me/uid 从 PHPSESSID 前缀解析）。
       一层视图：插画书签 / 小说书签 / 我的插画 / 我的小说 / 关注 / 珍藏集列表。
       二层视图（在同一 Tab 内置呈现）：
         - following → 选定用户的 illust / novel（kind 切换）
         - collection-list → 选定珍藏集内的 illust / novel
    */

    const QUICK_PAGE_SIZE_ILLUST = 48;
    const QUICK_PAGE_SIZE_NOVEL = 24;
    const QUICK_FOLLOWING_PAGE_SIZE = 24;
    const QUICK_THUMB_BATCH = 10;

    const quickState = {
        action: null,
        uid: null,
        viewType: null,   // 'illust-list' | 'novel-list' | 'following-list' | 'collection-list'
        kind: 'illust',   // 当前作品列表的 kind（用于队列归类与渲染）
        items: [],        // outer：当前页的卡片 / 用户 / 珍藏集
        total: 0,
        offset: 0,
        page: 1,
        pageSize: QUICK_PAGE_SIZE_ILLUST,
        // 「我的作品」需先获取全部 ID 再分页取 cards
        allIds: [],
        // following 客户端搜索
        followingFilter: '',
        followingAll: [],
        renderToken: 0,
        blobUrls: []
    };

    // 二层钻取：点击外层的关注用户 / 珍藏集后，在外层列表「下方」追加这块作品预览（非替换式）。
    // 它有独立的状态、容器、渲染令牌与 blob 缓存，与外层互不干扰。
    const quickInner = {
        open: false,
        type: null,        // 'following-user' | 'collection'
        id: null,          // 珍藏集 cid
        userId: null,      // 关注用户 uid
        name: '',
        workCategory: null,
        kind: 'illust',
        allIllustIds: [],  // following-user：该用户全部插画/漫画 ID
        allNovelIds: [],   // following-user：该用户全部小说 ID
        allIds: [],        // 当前 kind 对应的全部 ID（following-user 用）
        items: [],
        total: 0,
        page: 1,
        pageSize: QUICK_PAGE_SIZE_ILLUST,
        renderToken: 0,
        blobUrls: [],
        _jumpFn: null
    };

    function quickHasCookie() {
        return cookieHasPhpsessid();
    }

    function updateQuickAccountBar() {
        const uidEl = document.getElementById('quick-account-uid');
        const hintEl = document.getElementById('quick-account-hint');
        if (!uidEl || !hintEl) return;
        if (!quickHasCookie()) {
            uidEl.textContent = '-';
            quickState.uid = null;
            hintEl.style.display = '';
            hintEl.textContent = bt('quick.account.hint-no-cookie',
                '未检测到登录 Cookie，请先在上方保存含 PHPSESSID 的 Cookie');
            return;
        }
        hintEl.style.display = 'none';
        if (quickState.uid) {
            uidEl.textContent = quickState.uid;
            return;
        }
        // 异步解析；解析失败也只是显示 -
        fetch(`${BASE}/api/pixiv/me/uid`, {headers: pixivHeader()})
            .then(r => r.ok ? r.json() : null)
            .then(j => {
                if (j && j.uid) {
                    quickState.uid = String(j.uid);
                    uidEl.textContent = quickState.uid;
                }
            })
            .catch(() => {});
    }

    function quickResetView() {
        cleanupQuickBlobUrls();
        quickState.renderToken++;
        quickState.items = [];
        quickState.total = 0;
        quickState.offset = 0;
        quickState.page = 1;
        quickState.allIds = [];
        quickState.followingFilter = '';
        quickState.followingAll = [];
        quickCloseInner();
    }

    function cleanupQuickBlobUrls() {
        quickState.blobUrls.forEach(u => {
            try { URL.revokeObjectURL(u); } catch {}
        });
        quickState.blobUrls = [];
    }

    function quickSetTitle(text) {
        const el = document.getElementById('quick-preview-title');
        if (el) el.textContent = text;
    }

    function quickShowToolbar(opts) {
        const toolbar = document.getElementById('quick-preview-toolbar');
        if (!toolbar) return;
        toolbar.style.display = '';
        document.getElementById('quick-add-page').style.display = opts.showAdd ? '' : 'none';
        document.getElementById('quick-add-all').style.display = opts.showAdd ? '' : 'none';
        document.getElementById('quick-following-search').style.display = opts.showSearch ? '' : 'none';
    }

    // 加载态：转圈块 + 按钮 / 快捷按钮的 is-loading
    function quickLoadingHtml(msg) {
        return `<div class="quick-loading"><span class="quick-spinner"></span><span>${esc(msg || bt('quick.loading', '加载中…'))}</span></div>`;
    }

    function setQuickActionLoading(action, loading) {
        document.querySelectorAll('.quick-action').forEach(b => {
            if (b.dataset.quick === action) b.classList.toggle('is-loading', loading);
        });
    }

    function setQuickBtnLoading(id, loading) {
        const b = document.getElementById(id);
        if (!b) return;
        b.classList.toggle('is-loading', loading);
        b.disabled = loading;
    }

    async function quickLoad(action) {
        if (!quickHasCookie()) {
            quickRenderEmpty(bt('quick.error.no-cookie', '请先保存含 PHPSESSID 的 Cookie'));
            return;
        }
        quickResetView();
        quickState.action = action;
        // 高亮当前按钮
        document.querySelectorAll('.quick-action').forEach(b => {
            b.classList.toggle('quick-active', b.dataset.quick === action);
        });
        // 加载态：预览区转圈 + 当前按钮转圈
        const area = document.getElementById('quick-preview-area');
        if (area) area.innerHTML = quickLoadingHtml();
        document.getElementById('quick-preview-toolbar').style.display = 'none';
        const pag = document.getElementById('quick-pagination');
        if (pag) { pag.style.display = 'none'; pag.innerHTML = ''; }
        setQuickActionLoading(action, true);
        try {
            switch (action) {
                case 'my-illust-bookmarks-show':
                case 'my-illust-bookmarks-hide':
                    quickState.viewType = 'illust-list';
                    quickState.kind = 'illust';
                    quickState.pageSize = QUICK_PAGE_SIZE_ILLUST;
                    await loadQuickIllustBookmarks(action.endsWith('hide') ? 'hide' : 'show', 1);
                    break;
                case 'my-novel-bookmarks-show':
                case 'my-novel-bookmarks-hide':
                    quickState.viewType = 'novel-list';
                    quickState.kind = 'novel';
                    quickState.pageSize = QUICK_PAGE_SIZE_NOVEL;
                    await loadQuickNovelBookmarks(action.endsWith('hide') ? 'hide' : 'show', 1);
                    break;
                case 'my-illusts':
                    quickState.viewType = 'illust-list';
                    quickState.kind = 'illust';
                    quickState.pageSize = QUICK_PAGE_SIZE_ILLUST;
                    await loadQuickMyWorks('illust', 1);
                    break;
                case 'my-novels':
                    quickState.viewType = 'novel-list';
                    quickState.kind = 'novel';
                    quickState.pageSize = QUICK_PAGE_SIZE_NOVEL;
                    await loadQuickMyWorks('novel', 1);
                    break;
                case 'my-following-show':
                case 'my-following-hide':
                    quickState.viewType = 'following-list';
                    await loadQuickFollowing(action.endsWith('hide') ? 'hide' : 'show', 0);
                    break;
                case 'my-collections':
                    quickState.viewType = 'collection-list';
                    await loadQuickCollections();
                    break;
            }
        } catch (e) {
            quickRenderEmpty(bt('quick.error.load-failed', '加载失败：{message}', {message: e.message || String(e)}));
        } finally {
            setQuickActionLoading(action, false);
        }
    }

    async function quickFetchJson(url) {
        const res = await fetch(url, {headers: pixivHeader()});
        const data = await res.json().catch(() => ({}));
        if (!res.ok || data.error) {
            const msg = data.error || data.message || `HTTP ${res.status}`;
            throw new Error(msg);
        }
        return data;
    }

    async function loadQuickIllustBookmarks(rest, page) {
        const offset = (page - 1) * QUICK_PAGE_SIZE_ILLUST;
        const params = new URLSearchParams({rest, offset: String(offset), limit: String(QUICK_PAGE_SIZE_ILLUST)});
        const data = await quickFetchJson(`${BASE}/api/pixiv/me/illust-bookmarks?${params}`);
        quickState.items = data.items || [];
        quickState.total = data.total || 0;
        quickState.offset = offset;
        quickState.page = page;
        const titleKey = rest === 'hide' ? 'quick.title.illust-bookmarks-hide' : 'quick.title.illust-bookmarks-show';
        const titleFallback = rest === 'hide' ? '我的收藏（插画/漫画，不公开）' : '我的收藏（插画/漫画，公开）';
        quickSetTitle(`${bt(titleKey, titleFallback)} · ${bt('quick.title.count', '{count} 件', {count: quickState.total.toLocaleString()})}`);
        quickShowToolbar({showBack: false, showAdd: quickState.items.length > 0, showSearch: false, showKindSwitcher: false});
        renderQuickIllustGrid(quickState.items, 'quick');
        renderQuickPagination(page, Math.max(1, Math.ceil(quickState.total / QUICK_PAGE_SIZE_ILLUST)),
            p => loadQuickIllustBookmarks(rest, p));
    }

    async function loadQuickNovelBookmarks(rest, page) {
        const offset = (page - 1) * QUICK_PAGE_SIZE_NOVEL;
        const params = new URLSearchParams({rest, offset: String(offset), limit: String(QUICK_PAGE_SIZE_NOVEL)});
        const data = await quickFetchJson(`${BASE}/api/pixiv/me/novel-bookmarks?${params}`);
        quickState.items = data.items || [];
        quickState.total = data.total || 0;
        quickState.offset = offset;
        quickState.page = page;
        const titleKey = rest === 'hide' ? 'quick.title.novel-bookmarks-hide' : 'quick.title.novel-bookmarks-show';
        const titleFallback = rest === 'hide' ? '我的收藏（小说，不公开）' : '我的收藏（小说，公开）';
        quickSetTitle(`${bt(titleKey, titleFallback)} · ${bt('quick.title.count', '{count} 件', {count: quickState.total.toLocaleString()})}`);
        quickShowToolbar({showBack: false, showAdd: quickState.items.length > 0, showSearch: false, showKindSwitcher: false});
        renderQuickNovelGrid(quickState.items, 'quick');
        renderQuickPagination(page, Math.max(1, Math.ceil(quickState.total / QUICK_PAGE_SIZE_NOVEL)),
            p => loadQuickNovelBookmarks(rest, p));
    }

    async function loadQuickMyWorks(kind, page) {
        if (!quickState.uid) {
            const data = await quickFetchJson(`${BASE}/api/pixiv/me/uid`);
            quickState.uid = String(data.uid);
            const uidEl = document.getElementById('quick-account-uid');
            if (uidEl) uidEl.textContent = quickState.uid;
        }
        const uid = quickState.uid;
        // 拉全 ID 一次，缓存到 allIds
        if (!quickState.allIds.length || quickState.action.endsWith('-refresh')) {
            const endpoint = kind === 'novel' ? 'novels' : 'artworks';
            const data = await quickFetchJson(`${BASE}/api/pixiv/user/${uid}/${endpoint}`);
            quickState.allIds = data.ids || [];
        }
        const pageSize = kind === 'novel' ? QUICK_PAGE_SIZE_NOVEL : QUICK_PAGE_SIZE_ILLUST;
        const total = quickState.allIds.length;
        const totalPages = Math.max(1, Math.ceil(total / pageSize));
        const safePage = Math.min(Math.max(1, page), totalPages);
        const slice = quickState.allIds.slice((safePage - 1) * pageSize, safePage * pageSize);
        let items = [];
        if (slice.length > 0) {
            const endpoint = kind === 'novel' ? 'novel-cards' : 'illust-cards';
            const idsQuery = slice.map(id => `ids=${encodeURIComponent(id)}`).join('&');
            const data = await quickFetchJson(`${BASE}/api/pixiv/user/${uid}/${endpoint}?${idsQuery}`);
            items = data.items || [];
        }
        quickState.items = items;
        quickState.total = total;
        quickState.page = safePage;
        quickState.pageSize = pageSize;
        const titleKey = kind === 'novel' ? 'quick.title.my-novels' : 'quick.title.my-illusts';
        const titleFallback = kind === 'novel' ? '我自己的作品（小说，含 hide）' : '我自己的作品（插画/漫画，含 hide）';
        quickSetTitle(`${bt(titleKey, titleFallback)} · ${bt('quick.title.count', '{count} 件', {count: total.toLocaleString()})}`);
        quickShowToolbar({showBack: false, showAdd: items.length > 0, showSearch: false, showKindSwitcher: false});
        if (kind === 'novel') renderQuickNovelGrid(items, 'quick');
        else renderQuickIllustGrid(items, 'quick');
        renderQuickPagination(safePage, totalPages, p => loadQuickMyWorks(kind, p));
    }

    async function loadQuickFollowing(rest, offset) {
        const params = new URLSearchParams({rest, offset: String(offset), limit: String(QUICK_FOLLOWING_PAGE_SIZE)});
        const data = await quickFetchJson(`${BASE}/api/pixiv/me/following?${params}`);
        quickState.followingAll = data.users || [];
        quickState.total = data.total || 0;
        quickState.offset = offset;
        quickState.page = Math.floor(offset / QUICK_FOLLOWING_PAGE_SIZE) + 1;
        const titleKey = rest === 'hide' ? 'quick.title.following-hide' : 'quick.title.following-show';
        const titleFallback = rest === 'hide' ? '我的关注（不公开）' : '我的关注（公开）';
        quickSetTitle(`${bt(titleKey, titleFallback)} · ${bt('quick.title.count', '{count} 件', {count: quickState.total.toLocaleString()})}`);
        quickShowToolbar({showBack: false, showAdd: false, showSearch: true, showKindSwitcher: false});
        document.getElementById('quick-following-search').value = '';
        renderQuickFollowingGrid(quickState.followingAll, rest);
        const totalPages = Math.max(1, Math.ceil(quickState.total / QUICK_FOLLOWING_PAGE_SIZE));
        renderQuickPagination(quickState.page, totalPages,
            p => loadQuickFollowing(rest, (p - 1) * QUICK_FOLLOWING_PAGE_SIZE));
    }

    function quickFilterFollowing() {
        const input = document.getElementById('quick-following-search');
        const q = (input?.value || '').trim().toLowerCase();
        quickState.followingFilter = q;
        const filtered = !q ? quickState.followingAll : quickState.followingAll.filter(u =>
            (u.userName || '').toLowerCase().includes(q) || String(u.userId || '').includes(q));
        renderQuickFollowingGrid(filtered, null);
    }

    // 珍藏集列表：不分公开/不公开、不分插画/小说；Pixiv 无分页，一次性返回全部。
    async function loadQuickCollections() {
        const data = await quickFetchJson(`${BASE}/api/pixiv/me/collections`);
        quickState.items = data.collections || [];
        quickState.total = data.total || quickState.items.length;
        quickState.page = 1;
        quickSetTitle(`${bt('quick.title.collections', '我的珍藏集')} · ${bt('quick.title.count', '{count} 件', {count: quickState.total.toLocaleString()})}`);
        quickShowToolbar({showAdd: false, showSearch: false});
        renderQuickCollectionGrid(quickState.items);
        renderQuickPagination(1, 1, () => {});
    }

    function renderQuickIllustGrid(items, idPrefix) {
        const area = document.getElementById('quick-preview-area');
        if (!area) return;
        const renderToken = ++quickState.renderToken;
        if (!items.length) {
            area.innerHTML = `<div class="quick-empty">${esc(bt('quick.empty.no-items', '该范围内没有作品'))}</div>`;
            return;
        }
        const inQueue = new Set(state.queue.map(q => q.id));
        area.innerHTML = `<div class="search-grid">${items.map((item, idx) => {
            const xr = Number(item.xRestrict ?? 0);
            const illustType = Number(item.illustType ?? 0);
            const isAi = Number(item.aiType ?? 0) >= 2;
            const r18Badge = xr === 2 ? '<span class="thumb-badge" style="background:#b91c1c;">R-18G</span>'
                : xr === 1 ? '<span class="thumb-badge">R-18</span>' : '';
            const aiBadge = isAi ? '<span class="thumb-badge thumb-badge-ai">AI</span>' : '';
            const typeBadge = illustType === 2 ? `<span class="thumb-badge" style="background:#0ea5e9;">${esc(bt('search.type.ugoira', '动图'))}</span>`
                : illustType === 1 ? `<span class="thumb-badge" style="background:#f59e0b;">${esc(bt('search.type.manga', '漫画'))}</span>` : '';
            const pagesLabel = item.pageCount > 1 ? `<span class="thumb-pages">${item.pageCount}P</span>` : '';
            const inQueueClass = inQueue.has(String(item.id)) ? ' in-queue' : '';
            const fallbackTitle = bt('queue.artwork-fallback', '作品 {id}', {id: item.id});
            const title = item.title || fallbackTitle;
            return `<div class="search-thumb${inQueueClass}" id="${idPrefix}-thumb-${idx}"
                     onclick="quickToggleItemQueue(${idx})" title="${esc(title)} (${esc(item.userName || '')})">
          <img id="${idPrefix}-thumb-img-${idx}" src="" alt="${esc(title)}">
          <div class="thumb-badge-stack">${r18Badge}${aiBadge}${typeBadge}</div>
          ${pagesLabel}
          <span class="thumb-in-queue-mark">✓</span>
          <div class="thumb-title">${esc(title)}</div>
        </div>`;
        }).join('')}</div>`;
        loadQuickThumbnailsBatched(items, idPrefix, renderToken);
    }

    function renderQuickNovelGrid(items, idPrefix) {
        const area = document.getElementById('quick-preview-area');
        if (!area) return;
        if (!items.length) {
            area.innerHTML = `<div class="quick-empty">${esc(bt('quick.empty.no-items', '该范围内没有作品'))}</div>`;
            return;
        }
        const inQueue = new Set(state.queue.map(q => q.id));
        area.innerHTML = `<div class="novel-search-grid">${items.map((item, idx) => {
            const xr = Number(item.xRestrict ?? 0);
            const isAi = Number(item.aiType ?? 0) >= 2;
            const wc = Number(item.wordCount ?? item.textLength ?? 0);
            const queueId = 'n' + String(item.id);
            const inQueueClass = inQueue.has(queueId) ? ' in-queue' : '';
            const meta = [];
            if (xr === 1) meta.push('<span class="nsc-r18">R-18</span>');
            else if (xr === 2) meta.push('<span class="nsc-r18g">R-18G</span>');
            if (isAi) meta.push('<span class="nsc-ai">AI</span>');
            if (item.isOriginal) meta.push(`<span class="nsc-original">${esc(bt('novel:batch.search.original', '原创'))}</span>`);
            if (wc > 0) meta.push(`<span>${esc(bt('novel:batch.search.summary.novel-words', '{count} 字', {count: wc.toLocaleString()}))}</span>`);
            const fallbackTitle = bt('queue.novel-fallback', '小说 {id}', {id: item.id});
            const title = item.title || fallbackTitle;
            return `<div class="novel-search-card${inQueueClass}" id="${idPrefix}-novel-card-${idx}"
                     onclick="quickToggleItemQueue(${idx})" title="${esc(title)} (${esc(item.userName || '')})">
          <div class="nsc-title">${esc(title)}</div>
          <div class="nsc-author">${esc(item.userName || '')}</div>
          <div class="nsc-meta">${meta.join('')}</div>
          <span class="nsc-in-queue-mark">✓</span>
        </div>`;
        }).join('')}</div>`;
    }

    function renderQuickFollowingGrid(users, restHint) {
        const area = document.getElementById('quick-preview-area');
        if (!area) return;
        if (!users.length) {
            area.innerHTML = `<div class="quick-empty">${esc(bt('quick.empty.no-following', '没有匹配的关注用户'))}</div>`;
            return;
        }
        // 渲染的列表（可能是过滤后的子集）单独缓存，供索引点击时取回原始对象，避免把用户名拼进内联 onclick
        quickState.followingRendered = users;
        area.innerHTML = `<div class="quick-following-grid">${users.map((u, idx) => `
            <div class="quick-following-card" onclick="quickEnterFollowingUser(${idx})"
                 title="${esc(u.userName || u.userId)} (ID: ${esc(u.userId)})">
                <div class="quick-following-avatar" id="quick-follow-ava-${idx}"></div>
                <div class="quick-following-meta">
                    <div class="quick-following-name">${esc(u.userName || u.userId)}</div>
                    <div class="quick-following-uid">ID: ${esc(u.userId)}</div>
                </div>
            </div>
        `).join('')}</div>`;
        // 头像异步加载
        users.forEach((u, idx) => {
            if (!u.profileImageUrl) return;
            const params = new URLSearchParams({url: u.profileImageUrl});
            fetch(`${BASE}/api/pixiv/thumbnail-proxy?${params}`, {headers: pixivHeader()})
                .then(r => r.ok ? r.blob() : null)
                .then(blob => {
                    if (!blob) return;
                    const blobUrl = URL.createObjectURL(blob);
                    quickState.blobUrls.push(blobUrl);
                    const el = document.getElementById(`quick-follow-ava-${idx}`);
                    if (el) el.innerHTML = `<img src="${blobUrl}" alt="">`;
                })
                .catch(() => {});
        });
    }

    function renderQuickCollectionGrid(collections) {
        const area = document.getElementById('quick-preview-area');
        if (!area) return;
        if (!collections.length) {
            area.innerHTML = `<div class="quick-empty">${esc(bt('quick.empty.no-collections', '没有珍藏集'))}</div>`;
            return;
        }
        area.innerHTML = `<div class="quick-collection-grid">${collections.map((c, idx) => {
            const xr = Number(c.xRestrict ?? 0);
            const r18Badge = xr === 2 ? '<span class="thumb-badge" style="background:#b91c1c;">R-18G</span>'
                : xr === 1 ? '<span class="thumb-badge">R-18</span>' : '';
            const bm = Number(c.bookmarkCount ?? 0);
            const bmLine = bm > 0
                ? `<div class="quick-collection-count">${esc(bt('search.summary.bookmark-badge', '收藏 {count}', {count: bm.toLocaleString()}))}</div>`
                : '';
            return `<div class="quick-collection-card" onclick="quickEnterCollection(${idx})" title="${esc(c.title || c.id)}">
                <div class="quick-collection-cover" id="quick-col-cover-${idx}">${r18Badge ? `<div class="thumb-badge-stack">${r18Badge}</div>` : ''}</div>
                <div class="quick-collection-meta">
                    <div class="quick-collection-name">${esc(c.title || c.id)}</div>
                    ${bmLine}
                </div>
            </div>`;
        }).join('')}</div>`;
        collections.forEach((c, idx) => {
            if (!c.coverUrl) return;
            const params = new URLSearchParams({url: c.coverUrl});
            fetch(`${BASE}/api/pixiv/thumbnail-proxy?${params}`, {headers: pixivHeader()})
                .then(r => r.ok ? r.blob() : null)
                .then(blob => {
                    if (!blob) return;
                    const blobUrl = URL.createObjectURL(blob);
                    quickState.blobUrls.push(blobUrl);
                    const el = document.getElementById(`quick-col-cover-${idx}`);
                    if (el) el.insertAdjacentHTML('afterbegin', `<img src="${blobUrl}" alt="">`);
                })
                .catch(() => {});
        });
    }

    function renderQuickPagination(currentPage, totalPages, jumpFn) {
        const pag = document.getElementById('quick-pagination');
        if (!pag) return;
        if (totalPages <= 1) {
            pag.style.display = 'none';
            pag.innerHTML = '';
            return;
        }
        pag.style.display = 'flex';
        const cur = Math.min(Math.max(1, Number(currentPage || 1)), totalPages);
        const radius = 3;
        const pages = [];
        for (let p = Math.max(1, cur - radius); p <= Math.min(totalPages, cur + radius); p++) pages.push(p);
        quickState._jumpFn = jumpFn;
        pag.innerHTML =
            `<button onclick="quickJumpPage(1)" ${cur === 1 ? 'disabled' : ''}>&laquo;</button>` +
            `<button onclick="quickJumpPage(${cur - 1})" ${cur === 1 ? 'disabled' : ''}>&lsaquo;</button>` +
            pages.map(p => `<button onclick="${p === cur ? '' : `quickJumpPage(${p})`}" ${p === cur ? 'class="pg-active" disabled' : ''}>${p}</button>`).join('') +
            `<button onclick="quickJumpPage(${cur + 1})" ${cur === totalPages ? 'disabled' : ''}>&rsaquo;</button>` +
            `<button onclick="quickJumpPage(${totalPages})" ${cur === totalPages ? 'disabled' : ''}>&raquo;</button>` +
            `<span class="pg-info">${esc(bt('search.pagination.info', '第 {current} / {total} 页 · 共 {count} 个',
                {current: cur, total: totalPages, count: quickState.total.toLocaleString()}))}</span>`;
    }

    function quickJumpPage(p) {
        if (typeof quickState._jumpFn === 'function') quickState._jumpFn(p);
    }

    async function loadQuickThumbnailsBatched(items, idPrefix, renderToken) {
        for (let i = 0; i < items.length; i += QUICK_THUMB_BATCH) {
            if (renderToken !== quickState.renderToken) return;
            const batch = items.slice(i, i + QUICK_THUMB_BATCH);
            await Promise.allSettled(batch.map((item, offset) => loadQuickSingleThumbnail(item, idPrefix, i + offset, renderToken)));
        }
    }

    async function loadQuickSingleThumbnail(item, idPrefix, idx, renderToken) {
        const url = item.thumbnailUrl || item.url;
        if (!url) return;
        const imgEl = document.getElementById(`${idPrefix}-thumb-img-${idx}`);
        if (!imgEl) return;
        const blobUrl = await fetchThumbnailBlobUrl(url, quickState.blobUrls);
        if (renderToken !== quickState.renderToken) return;
        if (blobUrl && imgEl.isConnected) imgEl.src = blobUrl;
    }

    function quickToggleItemQueue(idx) {
        const item = quickState.items[idx];
        if (!item) return;
        const isNovel = quickState.kind === 'novel';
        const id = isNovel ? 'n' + String(item.id) : String(item.id);
        const existing = state.queue.find(q => q.id === id);
        if (existing) {
            const removed = removeFromQueue(id);
            setStatus(removed
                    ? bt('status.removed-from-queue', '已从队列移除：{title}', {title: item.title || id})
                    : bt('status.cannot-remove-downloading', '无法移除（正在下载中）：{title}', {title: item.title || id}),
                removed ? 'info' : 'warning');
            syncQuickQueueState();
            return;
        }
        const meta = buildQuickQueueMeta(item);
        const added = addItemsToQueue([id], [meta], QUICK_FETCH_MODE, '', meta.authorId, meta.authorName);
        setStatus(added > 0
                ? bt('status.added-to-queue', '已加入队列：{title}', {title: item.title || id})
                : bt('status.already-in-queue', '已在队列中：{title}', {title: item.title || id}),
            added > 0 ? 'success' : 'info');
        syncQuickQueueState();
    }

    function buildQuickQueueMeta(item, kind = quickState.kind) {
        if (kind === 'novel') {
            return {
                title: item.title || bt('queue.novel-fallback', '小说 {id}', {id: item.id}),
                novelId: String(item.id),
                kind: 'novel',
                authorId: item.userId ? Number(item.userId) : null,
                authorName: item.userName || '',
                isAi: Number(item.aiType ?? 0) >= 2,
                xRestrict: Number(item.xRestrict ?? 0),
                tags: Array.isArray(item.tags) ? item.tags : []
            };
        }
        return {
            title: item.title,
            authorId: item.userId ? Number(item.userId) : null,
            authorName: item.userName || '',
            isAi: Number(item.aiType ?? 0) >= 2,
            xRestrict: Number(item.xRestrict ?? 0),
            tags: Array.isArray(item.tags) ? item.tags : []
        };
    }

    function syncQuickQueueState() {
        const inQueue = new Set(state.queue.map(q => q.id));
        // 外层仅在书签 / 我的作品（作品网格）时需要同步；关注 / 珍藏集外层是用户 / 集卡片，无队列态
        if (quickState.viewType === 'illust-list' || quickState.viewType === 'novel-list') {
            quickSyncGridQueue(quickState.items, quickState.kind, 'quick', inQueue);
        }
        // 内层是混合作品，逐项按自身 kind 计算队列 id，卡片统一用 quick-inner-card-{idx}
        if (quickInner.open && quickInner.items.length) {
            quickInner.items.forEach((item, idx) => {
                const k = item.kind || quickInner.kind;
                const id = k === 'novel' ? 'n' + String(item.id) : String(item.id);
                const el = document.getElementById(`quick-inner-card-${idx}`);
                if (el) el.classList.toggle('in-queue', inQueue.has(id));
            });
        }
    }

    function quickSyncGridQueue(items, kind, idPrefix, inQueue) {
        if (!items || !items.length) return;
        items.forEach((item, idx) => {
            const id = kind === 'novel' ? 'n' + String(item.id) : String(item.id);
            const el = document.getElementById(kind === 'novel'
                ? `${idPrefix}-novel-card-${idx}` : `${idPrefix}-thumb-${idx}`);
            if (el) el.classList.toggle('in-queue', inQueue.has(id));
        });
    }

    function quickAddCurrentPageToQueue() {
        if (!quickState.items.length) return;
        const isNovel = quickState.kind === 'novel';
        const ids = quickState.items.map(item => isNovel ? 'n' + String(item.id) : String(item.id));
        const metas = quickState.items.map(buildQuickQueueMeta);
        const added = addItemsToQueue(ids, metas, QUICK_FETCH_MODE, '', null, '');
        setStatus(
            bt('status.added-current-series-page-to-queue', '已将当前页 {added} 个作品加入队列（本页 {total} 个，{existing} 个已在队列中）',
                {added, total: ids.length, existing: ids.length - added}),
            added > 0 ? 'success' : 'info'
        );
        syncQuickQueueState();
    }

    async function quickAddAllToQueue() {
        if (!quickState.items.length) return;
        // 「我的作品」可直接按全量 ID 入队（无须逐页拉 cards）
        if (quickState.action === 'my-illusts' || quickState.action === 'my-novels') {
            if (!uiConfirmKey('quick.confirm.add-all-my-works',
                '将把你的全部 {total} 个作品（含 hide）加入队列，确认继续？',
                {total: quickState.allIds.length})) return;
            const isNovel = quickState.action === 'my-novels';
            const ids = quickState.allIds.map(id => isNovel ? 'n' + id : id);
            const metas = quickState.allIds.map(id => isNovel
                ? {title: bt('queue.novel-fallback', '小说 {id}', {id}), novelId: String(id), kind: 'novel'}
                : {});
            const added = addItemsToQueue(ids, metas, QUICK_FETCH_MODE, '', null, '');
            setStatus(
                bt('status.added-many-to-queue', '已将 {added} 个作品加入队列（共 {total} 个，{existing} 个已在队列中）',
                    {added, total: ids.length, existing: ids.length - added}),
                added > 0 ? 'success' : 'info'
            );
            syncQuickQueueState();
            return;
        }
        // 其它（书签 / 珍藏集内 / 关注用户作品）：逐页抓取
        const totalPages = Math.max(1, Math.ceil(quickState.total / quickState.pageSize));
        if (!uiConfirmKey('quick.confirm.add-all-paged',
            '将逐页抓取 {pages} 页（共 {total} 个）并加入队列，请求较多，确认继续？',
            {pages: totalPages, total: quickState.total})) return;
        const isNovel = quickState.kind === 'novel';
        const ids = [];
        const metas = [];
        const collectedIds = new Set();
        const acc = (items) => {
            items.forEach(item => {
                const id = isNovel ? 'n' + String(item.id) : String(item.id);
                if (collectedIds.has(id)) return;
                collectedIds.add(id);
                ids.push(id);
                metas.push(buildQuickQueueMeta(item));
            });
        };
        acc(quickState.items);
        setQuickBtnLoading('quick-add-all', true);
        try {
            for (let p = quickState.page + 1; p <= totalPages; p++) {
                setStatus(bt('status.user-fetch-all-progress', '正在抓取画师作品卡片 {done} / {total}...',
                    {done: ids.length, total: quickState.total}), 'info');
                const more = await quickFetchPage(p);
                acc(more);
            }
            const added = addItemsToQueue(ids, metas, QUICK_FETCH_MODE, '', null, '');
            setStatus(
                bt('status.added-many-to-queue', '已将 {added} 个作品加入队列（共 {total} 个，{existing} 个已在队列中）',
                    {added, total: ids.length, existing: ids.length - added}),
                added > 0 ? 'success' : 'info'
            );
            syncQuickQueueState();
        } catch (e) {
            setStatus(bt('status.fetch-failed', '获取作品列表失败：{message}', {message: e.message}), 'error');
        } finally {
            setQuickBtnLoading('quick-add-all', false);
        }
    }

    // 外层「全部加入队列」按页抓取（仅书签外层会用到；关注 / 珍藏集外层无 add-all）。
    async function quickFetchPage(page) {
        const limit = quickState.pageSize;
        const offset = (page - 1) * limit;
        const action = quickState.action;
        if (action === 'my-illust-bookmarks-show' || action === 'my-illust-bookmarks-hide') {
            const rest = action.endsWith('hide') ? 'hide' : 'show';
            const params = new URLSearchParams({rest, offset: String(offset), limit: String(limit)});
            const data = await quickFetchJson(`${BASE}/api/pixiv/me/illust-bookmarks?${params}`);
            return data.items || [];
        }
        if (action === 'my-novel-bookmarks-show' || action === 'my-novel-bookmarks-hide') {
            const rest = action.endsWith('hide') ? 'hide' : 'show';
            const params = new URLSearchParams({rest, offset: String(offset), limit: String(limit)});
            const data = await quickFetchJson(`${BASE}/api/pixiv/me/novel-bookmarks?${params}`);
            return data.items || [];
        }
        return [];
    }

    function quickRenderEmpty(msg) {
        const area = document.getElementById('quick-preview-area');
        if (area) area.innerHTML = `<div class="quick-empty">${esc(msg)}</div>`;
        const pag = document.getElementById('quick-pagination');
        if (pag) { pag.style.display = 'none'; pag.innerHTML = ''; }
        quickCloseInner();
    }

    /* ── 二层钻取（在外层列表下方追加，非替换式）─────────────────────────── */

    function quickCleanupInnerBlobUrls() {
        quickInner.blobUrls.forEach(u => {
            try { URL.revokeObjectURL(u); } catch {}
        });
        quickInner.blobUrls = [];
    }

    function quickCloseInner() {
        quickInner.open = false;
        quickInner.renderToken++;
        quickInner.items = [];
        quickCleanupInnerBlobUrls();
        const section = document.getElementById('quick-inner-section');
        if (section) section.style.display = 'none';
        // 取消外层卡片的选中高亮
        document.querySelectorAll('#quick-preview-area .quick-selected')
            .forEach(el => el.classList.remove('quick-selected'));
    }

    function quickHighlightOuterCard(selector) {
        document.querySelectorAll('#quick-preview-area .quick-selected')
            .forEach(el => el.classList.remove('quick-selected'));
        const el = document.querySelector(selector);
        if (el) el.classList.add('quick-selected');
    }

    function quickShowInnerSection() {
        const section = document.getElementById('quick-inner-section');
        if (section) {
            section.style.display = '';
            section.scrollIntoView({behavior: 'smooth', block: 'nearest'});
        }
    }

    function quickShowInnerToolbar(opts) {
        document.getElementById('quick-inner-add-page').style.display = opts.showAdd ? '' : 'none';
        document.getElementById('quick-inner-add-all').style.display = opts.showAdd ? '' : 'none';
        const sw = document.getElementById('quick-inner-kind-switcher');
        sw.style.display = opts.showKindSwitcher ? '' : 'none';
        if (opts.showKindSwitcher && opts.kind) {
            document.querySelectorAll('#quick-inner-kind-switcher label').forEach(l => {
                l.classList.toggle('quick-kind-active', l.dataset.quickKind === opts.kind);
                const input = l.querySelector('input');
                if (input) input.checked = l.dataset.quickKind === opts.kind;
            });
        }
    }

    function quickSetInnerTitle(text) {
        const el = document.getElementById('quick-inner-title');
        if (el) el.textContent = text;
    }

    // 内层作品网格渲染：混合插画+小说，按每项自身 kind 渲染对应卡片。
    // 卡片统一 id quick-inner-card-{idx}（队列高亮用），插画缩略图 id quick-inner-img-{idx}；点击走 quickInnerToggleQueue。
    function renderQuickInnerGrid(items) {
        const area = document.getElementById('quick-inner-area');
        if (!area) return;
        const renderToken = ++quickInner.renderToken;
        if (!items.length) {
            area.innerHTML = `<div class="quick-empty">${esc(bt('quick.empty.no-items', '该范围内没有作品'))}</div>`;
            return;
        }
        const inQueue = new Set(state.queue.map(q => q.id));
        area.innerHTML = `<div class="quick-mixed-grid">${items.map((item, idx) => {
            const k = item.kind || 'illust';
            const title = item.title || bt(k === 'novel' ? 'queue.novel-fallback' : 'queue.artwork-fallback',
                k === 'novel' ? '小说 {id}' : '作品 {id}', {id: item.id});
            if (k === 'novel') {
                const xr = Number(item.xRestrict ?? 0);
                const isAi = Number(item.aiType ?? 0) >= 2;
                const wc = Number(item.wordCount ?? item.textLength ?? 0);
                const inQueueClass = inQueue.has('n' + String(item.id)) ? ' in-queue' : '';
                const meta = [];
                if (xr === 1) meta.push('<span class="nsc-r18">R-18</span>');
                else if (xr === 2) meta.push('<span class="nsc-r18g">R-18G</span>');
                if (isAi) meta.push('<span class="nsc-ai">AI</span>');
                if (item.isOriginal) meta.push(`<span class="nsc-original">${esc(bt('novel:batch.search.original', '原创'))}</span>`);
                if (wc > 0) meta.push(`<span>${esc(bt('novel:batch.search.summary.novel-words', '{count} 字', {count: wc.toLocaleString()}))}</span>`);
                return `<div class="novel-search-card${inQueueClass}" id="quick-inner-card-${idx}"
                         onclick="quickInnerToggleQueue(${idx})" title="${esc(title)} (${esc(item.userName || '')})">
              <div class="nsc-title">${esc(title)}</div>
              <div class="nsc-author">${esc(item.userName || '')}</div>
              <div class="nsc-meta">${meta.join('')}</div>
              <span class="nsc-in-queue-mark">✓</span>
            </div>`;
            }
            const xr = Number(item.xRestrict ?? 0);
            const illustType = Number(item.illustType ?? 0);
            const isAi = Number(item.aiType ?? 0) >= 2;
            const r18Badge = xr === 2 ? '<span class="thumb-badge" style="background:#b91c1c;">R-18G</span>'
                : xr === 1 ? '<span class="thumb-badge">R-18</span>' : '';
            const aiBadge = isAi ? '<span class="thumb-badge thumb-badge-ai">AI</span>' : '';
            const typeBadge = illustType === 2 ? `<span class="thumb-badge" style="background:#0ea5e9;">${esc(bt('search.type.ugoira', '动图'))}</span>`
                : illustType === 1 ? `<span class="thumb-badge" style="background:#f59e0b;">${esc(bt('search.type.manga', '漫画'))}</span>` : '';
            const pagesLabel = item.pageCount > 1 ? `<span class="thumb-pages">${item.pageCount}P</span>` : '';
            const inQueueClass = inQueue.has(String(item.id)) ? ' in-queue' : '';
            return `<div class="search-thumb${inQueueClass}" id="quick-inner-card-${idx}"
                     onclick="quickInnerToggleQueue(${idx})" title="${esc(title)} (${esc(item.userName || '')})">
          <img id="quick-inner-img-${idx}" src="" alt="${esc(title)}">
          <div class="thumb-badge-stack">${r18Badge}${aiBadge}${typeBadge}</div>
          ${pagesLabel}
          <span class="thumb-in-queue-mark">✓</span>
          <div class="thumb-title">${esc(title)}</div>
        </div>`;
        }).join('')}</div>`;
        loadQuickInnerThumbnailsBatched(items, renderToken);
    }

    async function loadQuickInnerThumbnailsBatched(items, renderToken) {
        for (let i = 0; i < items.length; i += QUICK_THUMB_BATCH) {
            if (renderToken !== quickInner.renderToken) return;
            const batch = items.slice(i, i + QUICK_THUMB_BATCH);
            await Promise.allSettled(batch.map((item, offset) => loadQuickInnerSingleThumbnail(item, i + offset, renderToken)));
        }
    }

    async function loadQuickInnerSingleThumbnail(item, idx, renderToken) {
        if ((item.kind || 'illust') === 'novel') return;
        const url = item.thumbnailUrl || item.url;
        if (!url) return;
        const imgEl = document.getElementById(`quick-inner-img-${idx}`);
        if (!imgEl) return;
        const blobUrl = await fetchThumbnailBlobUrl(url, quickInner.blobUrls);
        if (renderToken !== quickInner.renderToken) return;
        if (blobUrl && imgEl.isConnected) imgEl.src = blobUrl;
    }

    function renderQuickInnerPagination(currentPage, totalPages, jumpFn) {
        const pag = document.getElementById('quick-inner-pagination');
        if (!pag) return;
        if (totalPages <= 1) {
            pag.style.display = 'none';
            pag.innerHTML = '';
            return;
        }
        pag.style.display = 'flex';
        const cur = Math.min(Math.max(1, Number(currentPage || 1)), totalPages);
        const radius = 3;
        const pages = [];
        for (let p = Math.max(1, cur - radius); p <= Math.min(totalPages, cur + radius); p++) pages.push(p);
        quickInner._jumpFn = jumpFn;
        pag.innerHTML =
            `<button onclick="quickInnerJumpPage(1)" ${cur === 1 ? 'disabled' : ''}>&laquo;</button>` +
            `<button onclick="quickInnerJumpPage(${cur - 1})" ${cur === 1 ? 'disabled' : ''}>&lsaquo;</button>` +
            pages.map(p => `<button onclick="${p === cur ? '' : `quickInnerJumpPage(${p})`}" ${p === cur ? 'class="pg-active" disabled' : ''}>${p}</button>`).join('') +
            `<button onclick="quickInnerJumpPage(${cur + 1})" ${cur === totalPages ? 'disabled' : ''}>&rsaquo;</button>` +
            `<button onclick="quickInnerJumpPage(${totalPages})" ${cur === totalPages ? 'disabled' : ''}>&raquo;</button>` +
            `<span class="pg-info">${esc(bt('search.pagination.info', '第 {current} / {total} 页 · 共 {count} 个',
                {current: cur, total: totalPages, count: quickInner.total.toLocaleString()}))}</span>`;
    }

    function quickInnerJumpPage(p) {
        if (typeof quickInner._jumpFn === 'function') quickInner._jumpFn(p);
    }

    // 珍藏集 → 集内作品（插画+小说混合，一次性返回、无分页）
    async function quickEnterCollection(idx) {
        if (!quickHasCookie()) return;
        const c = quickState.items[idx];
        if (!c) return;
        const cid = String(c.id);
        quickCleanupInnerBlobUrls();
        quickInner.open = true;
        quickInner.type = 'collection';
        quickInner.id = cid;
        quickInner.name = c.title || cid;
        quickHighlightOuterCard(`#quick-preview-area .quick-collection-card:nth-of-type(${idx + 1})`);
        quickShowInnerSection();
        document.getElementById('quick-inner-area').innerHTML = quickLoadingHtml();
        quickShowInnerToolbar({showAdd: false, showKindSwitcher: false});
        try {
            const data = await quickFetchJson(`${BASE}/api/pixiv/me/collection/${cid}/works`);
            quickInner.items = data.works || [];
            quickInner.total = data.total || quickInner.items.length;
            quickShowInnerToolbar({showAdd: quickInner.items.length > 0, showKindSwitcher: false});
            quickSetInnerTitle(`${bt('quick.title.collections', '我的珍藏集')} › ${quickInner.name} · ${bt('quick.title.count', '{count} 件', {count: quickInner.total.toLocaleString()})}`);
            renderQuickInnerGrid(quickInner.items);
            renderQuickInnerPagination(1, 1, () => {});
        } catch (e) {
            document.getElementById('quick-inner-area').innerHTML =
                `<div class="quick-empty">${esc(bt('quick.error.load-failed', '加载失败：{message}', {message: e.message || String(e)}))}</div>`;
        }
    }

    // 关注用户 → 该用户作品（插画/小说切换）
    async function quickEnterFollowingUser(idx) {
        if (!quickHasCookie()) return;
        const u = (quickState.followingRendered || quickState.followingAll)[idx];
        if (!u) return;
        const userId = String(u.userId);
        const userName = u.userName || userId;
        quickCleanupInnerBlobUrls();
        quickInner.open = true;
        quickInner.type = 'following-user';
        quickInner.userId = userId;
        quickInner.name = userName;
        quickHighlightOuterCard(`#quick-preview-area .quick-following-card:nth-of-type(${idx + 1})`);
        quickShowInnerSection();
        document.getElementById('quick-inner-area').innerHTML = quickLoadingHtml();
        quickShowInnerToolbar({showAdd: false, showKindSwitcher: false});
        try {
            const data = await quickFetchJson(`${BASE}/api/pixiv/user/${userId}/artworks`);
            quickInner.allIllustIds = data.ids || [];
            const novelData = await quickFetchJson(`${BASE}/api/pixiv/user/${userId}/novels`);
            quickInner.allNovelIds = novelData.ids || [];
            quickInner.kind = quickInner.allIllustIds.length > 0 ? 'illust' : 'novel';
            await loadQuickInnerFollowingUserWorks(quickInner.kind, 1);
        } catch (e) {
            document.getElementById('quick-inner-area').innerHTML =
                `<div class="quick-empty">${esc(bt('quick.error.load-failed', '加载失败：{message}', {message: e.message || String(e)}))}</div>`;
        }
    }

    async function loadQuickInnerFollowingUserWorks(kind, page) {
        const allIds = kind === 'novel' ? quickInner.allNovelIds : quickInner.allIllustIds;
        quickInner.kind = kind;
        quickInner.allIds = allIds;
        const limit = kind === 'novel' ? QUICK_PAGE_SIZE_NOVEL : QUICK_PAGE_SIZE_ILLUST;
        const totalPages = Math.max(1, Math.ceil(allIds.length / limit));
        const safePage = Math.min(Math.max(1, page), totalPages);
        const slice = allIds.slice((safePage - 1) * limit, safePage * limit);
        let items = [];
        if (slice.length > 0) {
            const endpoint = kind === 'novel' ? 'novel-cards' : 'illust-cards';
            const idsQuery = slice.map(id => `ids=${encodeURIComponent(id)}`).join('&');
            const data = await quickFetchJson(`${BASE}/api/pixiv/user/${quickInner.userId}/${endpoint}?${idsQuery}`);
            items = data.items || [];
        }
        items.forEach(it => { it.kind = kind; });
        quickInner.items = items;
        quickInner.total = allIds.length;
        quickInner.page = safePage;
        quickInner.pageSize = limit;
        quickShowInnerToolbar({showAdd: items.length > 0, showKindSwitcher: true, kind});
        quickSetInnerTitle(`${bt('quick.preview.parent.following', '我的关注')} › ${quickInner.name} · ${bt('quick.title.count', '{count} 件', {count: allIds.length.toLocaleString()})}`);
        renderQuickInnerGrid(items);
        renderQuickInnerPagination(safePage, totalPages, p => loadQuickInnerFollowingUserWorks(kind, p));
    }

    function quickInnerQueueId(item) {
        return (item.kind || quickInner.kind) === 'novel' ? 'n' + String(item.id) : String(item.id);
    }

    function quickInnerToggleQueue(idx) {
        const item = quickInner.items[idx];
        if (!item) return;
        const id = quickInnerQueueId(item);
        const existing = state.queue.find(q => q.id === id);
        if (existing) {
            const removed = removeFromQueue(id);
            setStatus(removed
                    ? bt('status.removed-from-queue', '已从队列移除：{title}', {title: item.title || id})
                    : bt('status.cannot-remove-downloading', '无法移除（正在下载中）：{title}', {title: item.title || id}),
                removed ? 'info' : 'warning');
            syncQuickQueueState();
            return;
        }
        const meta = buildQuickQueueMeta(item, item.kind || quickInner.kind);
        const added = addItemsToQueue([id], [meta], QUICK_FETCH_MODE, '', meta.authorId, meta.authorName);
        setStatus(added > 0
                ? bt('status.added-to-queue', '已加入队列：{title}', {title: item.title || id})
                : bt('status.already-in-queue', '已在队列中：{title}', {title: item.title || id}),
            added > 0 ? 'success' : 'info');
        syncQuickQueueState();
    }

    function quickInnerAddCurrentPageToQueue() {
        if (!quickInner.items.length) return;
        const ids = quickInner.items.map(quickInnerQueueId);
        const metas = quickInner.items.map(item => buildQuickQueueMeta(item, item.kind || quickInner.kind));
        const added = addItemsToQueue(ids, metas, QUICK_FETCH_MODE, '', null, '');
        setStatus(
            bt('status.added-current-series-page-to-queue', '已将当前页 {added} 个作品加入队列（本页 {total} 个，{existing} 个已在队列中）',
                {added, total: ids.length, existing: ids.length - added}),
            added > 0 ? 'success' : 'info'
        );
        syncQuickQueueState();
    }

    async function quickInnerAddAllToQueue() {
        if (!quickInner.items.length) return;
        // 珍藏集集内作品一次性全部返回，直接整集入队；关注用户作品按页抓取
        if (quickInner.type === 'collection') {
            const ids = quickInner.items.map(quickInnerQueueId);
            const metas = quickInner.items.map(item => buildQuickQueueMeta(item, item.kind || 'illust'));
            const added = addItemsToQueue(ids, metas, QUICK_FETCH_MODE, '', null, '');
            setStatus(
                bt('status.added-many-to-queue', '已将 {added} 个作品加入队列（共 {total} 个，{existing} 个已在队列中）',
                    {added, total: ids.length, existing: ids.length - added}),
                added > 0 ? 'success' : 'info'
            );
            syncQuickQueueState();
            return;
        }
        const kind = quickInner.kind;
        const totalPages = Math.max(1, Math.ceil(quickInner.total / quickInner.pageSize));
        if (!uiConfirmKey('quick.confirm.add-all-paged',
            '将逐页抓取 {pages} 页（共 {total} 个）并加入队列，请求较多，确认继续？',
            {pages: totalPages, total: quickInner.total})) return;
        const isNovel = kind === 'novel';
        const ids = [];
        const metas = [];
        const collected = new Set();
        const acc = (items) => {
            items.forEach(item => {
                const id = isNovel ? 'n' + String(item.id) : String(item.id);
                if (collected.has(id)) return;
                collected.add(id);
                ids.push(id);
                metas.push(buildQuickQueueMeta(item, kind));
            });
        };
        acc(quickInner.items);
        setQuickBtnLoading('quick-inner-add-all', true);
        try {
            for (let p = quickInner.page + 1; p <= totalPages; p++) {
                setStatus(bt('status.user-fetch-all-progress', '正在抓取画师作品卡片 {done} / {total}...',
                    {done: ids.length, total: quickInner.total}), 'info');
                acc(await quickFetchInnerPage(p, kind));
            }
            const added = addItemsToQueue(ids, metas, QUICK_FETCH_MODE, '', null, '');
            setStatus(
                bt('status.added-many-to-queue', '已将 {added} 个作品加入队列（共 {total} 个，{existing} 个已在队列中）',
                    {added, total: ids.length, existing: ids.length - added}),
                added > 0 ? 'success' : 'info'
            );
            syncQuickQueueState();
        } catch (e) {
            setStatus(bt('status.fetch-failed', '获取作品列表失败：{message}', {message: e.message}), 'error');
        } finally {
            setQuickBtnLoading('quick-inner-add-all', false);
        }
    }

    // 仅关注用户作品分页抓取（珍藏集无分页，集内全部已加载）
    async function quickFetchInnerPage(page, kind) {
        if (quickInner.type !== 'following-user') return [];
        const limit = quickInner.pageSize;
        const offset = (page - 1) * limit;
        const slice = quickInner.allIds.slice(offset, offset + limit);
        if (!slice.length) return [];
        const endpoint = kind === 'novel' ? 'novel-cards' : 'illust-cards';
        const idsQuery = slice.map(id => `ids=${encodeURIComponent(id)}`).join('&');
        const data = await quickFetchJson(`${BASE}/api/pixiv/user/${quickInner.userId}/${endpoint}?${idsQuery}`);
        return data.items || [];
    }

    // 内层 kind 切换（仅关注用户钻取有插画/小说切换）
    document.addEventListener('change', (e) => {
        const target = e.target;
        if (!target || target.name !== 'quick-inner-kind') return;
        if (!quickInner.open || quickInner.type !== 'following-user') return;
        document.querySelectorAll('#quick-inner-kind-switcher label').forEach(l => {
            l.classList.toggle('quick-kind-active', l.dataset.quickKind === target.value);
        });
        loadQuickInnerFollowingUserWorks(target.value, 1);
    });
