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
                updateBatchLimitNote();
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
    const SINGLE_IMPORT_MODE = 'single-import';
    const SINGLE_IMPORT_NOVEL_SOURCE = 'single-import-novel';
    const WINDOWS_RESERVED_FILE_NAMES = new Set([
        'CON', 'PRN', 'AUX', 'NUL',
        'COM1', 'COM2', 'COM3', 'COM4', 'COM5', 'COM6', 'COM7', 'COM8', 'COM9',
        'LPT1', 'LPT2', 'LPT3', 'LPT4', 'LPT5', 'LPT6', 'LPT7', 'LPT8', 'LPT9'
    ]);

    let state = {
        mode: SINGLE_IMPORT_MODE,
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
            R18Only: false,
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

    async function getNovelMetaForSearch(novelId) {
        const data = await apiGet(`/api/pixiv/novel/${encodeURIComponent(novelId)}/meta`);
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
            if (state.settings.R18Only && xRestrict < 1) {
                item.status = 'skipped';
                item.lastMessage = bt('queue.message.skipped-not-r18', '跳过 — 非 R18 内容');
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
        let illustItems = [];
        const novelItems = [];
        for (const ln of lines) {
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

            if (state.settings.R18Only && Number(meta.xRestrict || 0) < 1) {
                item.status = 'skipped';
                item.lastMessage = bt('queue.message.skipped-not-r18', '跳过 — 非 R18 内容');
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
            await fetch(`${BASE}/api/gallery/novel/series/${encodeURIComponent(seriesId)}/merge?format=${encodeURIComponent(mfmt)}`, {
                method: 'POST', credentials: 'same-origin'
            });
            setStatus(bt('status.novel-series-merged', '小说系列合订本已生成（系列 {id}）', {id: seriesId}), 'success');
        } catch (e) {
            console.warn('merge failed', seriesId, e);
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

    async function handleFetchAllFresh() {
        stopAndClear();
        await fetchUserArtworks(false);
    }

    async function handleFetchAll() {
        await fetchUserArtworks(false);
    }

    async function handleFetchNew() {
        await fetchUserArtworks(true);
    }

    async function fetchUserArtworks(onlyNew) {
        const userId = document.getElementById('user-id-input').value.trim();
        if (!userId || !/^\d+$/.test(userId)) {
            uiAlertKey('alert.invalid-user-id', '请输入有效的用户 ID（纯数字）');
            return;
        }
        state.userId = userId;
        const kind = state.settings.userKind === 'novel' ? 'novel' : 'illust';
        document.getElementById('user-info-display').textContent = bt('status.fetching-user-info', '正在获取用户信息...');
        setStatus(bt('status.fetching-artwork-list', '正在获取作品列表...'), 'info');

        try {
            const name = await getUserMeta(userId);
            state.username = name || userId;
            document.getElementById('user-info-display').textContent =
                bt('status.user-display', '用户：{name}（ID: {id}）', {name: state.username, id: userId});
        } catch {
            state.username = userId;
            document.getElementById('user-info-display').textContent =
                bt('status.user-display-fetch-failed', 'ID: {id}（获取用户名失败）', {id: userId});
        }

        try {
            let added = 0;
            let total = 0;
            if (kind === 'novel') {
                const ids = await getUserNovels(userId);
                total = ids.length;
                if (!total) {
                    setStatus(bt('status.user-no-artworks', '该用户暂无作品'), 'warning');
                    return;
                }
                const items = ids.map(id => ({
                    id: 'n' + id,
                    novelId: String(id),
                    kind: 'novel',
                    title: bt('queue.novel-fallback', '小说 {id}', {id}),
                    authorId: Number(state.userId),
                    authorName: state.username || state.userId
                }));
                added = addItemsToQueue(
                    items.map(x => x.id),
                    items,
                    'user',
                    state.username || state.userId,
                    state.userId,
                    state.username || state.userId
                );
            } else {
                const ids = await getUserArtworks(userId);
                total = ids.length;
                if (!total) {
                    setStatus(bt('status.user-no-artworks', '该用户暂无作品'), 'warning');
                    return;
                }
                added = addItemsToQueue(
                    ids,
                    null,
                    'user',
                    state.username || state.userId,
                    state.userId,
                    state.username || state.userId
                );
            }
            setStatus(
                bt('status.fetch-success', '获取成功：共 {total} 个作品，新增 {added} 个', {total, added}),
                'success'
            );

            if (onlyNew) {
                document.getElementById('s-skip').checked = true;
                state.settings.skipHistory = true;
                saveSettings();
                syncSettings();
                start();
            }
        } catch (e) {
            setStatus(bt('status.fetch-failed', '获取作品列表失败：{message}', {message: e.message}), 'error');
        }
    }

    /* ============================================================
       UI
    ============================================================ */
    function switchMode(mode) {
        const normalizedMode = normalizeImportMode(mode);
        state.mode = normalizedMode;
        [SINGLE_IMPORT_MODE, 'user', 'search', 'series'].forEach(m => {
            document.getElementById('tab-' + m).classList.toggle('active', m === normalizedMode);
            document.getElementById('panel-' + m).classList.toggle('active', m === normalizedMode);
        });
        storeSet('pixiv_mode', normalizedMode);
        applyNovelSettingsVisibility();
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
        updateBatchLimitNote();
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
        el.innerHTML = state.queue.map(q => {
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
            const canRemove = q.status !== 'downloading';
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
        }).join('');
        updateAdminPackButton();
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
        document.getElementById('s-R18').checked = state.settings.R18Only;
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

    function applySearchKindUI() {
        const isNovel = state.settings.searchKind === 'novel';
        document.querySelectorAll('.search-illust-only').forEach(el => {
            el.style.display = isNovel ? 'none' : '';
        });
        document.querySelectorAll('.search-novel-only').forEach(el => {
            el.style.display = isNovel ? '' : 'none';
        });
    }

    function syncSettings() {
        state.settings.interval = Math.max(0, parseFloat(document.getElementById('s-interval').value) || 0);
        state.settings.imageDelay = Math.max(0, parseFloat(document.getElementById('s-image-delay').value) || 0);
        state.settings.concurrent = Math.max(1, parseInt(document.getElementById('s-concurrent').value) || 1);
        state.settings.skipHistory = document.getElementById('s-skip').checked;
        state.settings.verifyHistoryFiles = document.getElementById('s-verify-files').checked;
        state.settings.R18Only = document.getElementById('s-R18').checked;
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
        return filters.ai !== 'all'
            || filters.type !== 'all'
            || filters.pageMin !== null
            || filters.pageMax !== null
            || filters.wordsMin !== null
            || filters.wordsMax !== null
            || (filters.tagsExact && filters.tagsExact.length > 0)
            || (filters.tagsFuzzy && filters.tagsFuzzy.length > 0)
            || hasBookmarkFilter(filters);
    }

    // 标签筛选：逗号分隔多标签全部命中(AND)；精确=与某个作品标签完全相等，
    // 模糊=被某个作品标签包含；两框都填则两者都需满足(AND)。大小写不敏感。
    function matchTagFilters(item, filters) {
        const exact = filters.tagsExact || [];
        const fuzzy = filters.tagsFuzzy || [];
        if (!exact.length && !fuzzy.length) return true;
        const tags = (item.tags || []).map(t => String(t).toLowerCase());
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

    function getCachedSearchMeta(artworkId) {
        return searchState.metaCache[searchMetaCacheKey(artworkId)] || null;
    }

    function getSearchBookmarkCount(item) {
        const cached = getCachedSearchMeta(item.id);
        if (!cached || !cached.bookmarkResolved) return null;
        const count = Number(cached.bookmarkCount);
        return Number.isFinite(count) && count >= 0 ? count : null;
    }

    async function ensureSearchBookmarkMeta(items, requestSeq) {
        const missingIds = [];
        const seen = new Set();
        for (const item of items) {
            const id = String(item.id);
            if (seen.has(id)) continue;
            seen.add(id);
            const cached = getCachedSearchMeta(id);
            if (!cached || !cached.bookmarkResolved) missingIds.push(id);
        }
        if (!missingIds.length) return;

        const fetchMeta = searchState.kind === 'novel' ? getNovelMetaForSearch : getArtworkMeta;
        let cursor = 0;
        const workers = [];
        const workerCount = Math.min(6, missingIds.length);
        for (let i = 0; i < workerCount; i++) {
            workers.push((async () => {
                while (cursor < missingIds.length) {
                    if (requestSeq !== searchState.filterSeq) return;
                    const id = missingIds[cursor++];
                    const cacheKey = searchMetaCacheKey(id);
                    try {
                        const meta = await fetchMeta(id);
                        searchState.metaCache[cacheKey] = {
                            ...(searchState.metaCache[cacheKey] || {}),
                            bookmarkCount: Number(meta?.bookmarkCount ?? -1),
                            bookmarkResolved: true
                        };
                    } catch {
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

    function matchSearchFilters(item, filters, stats) {
        const aiType = Number(item.aiType ?? 0);
        if (filters.ai === 'exclude' && aiType >= 2) return false;
        if (filters.ai === 'only' && aiType < 2) return false;

        if (!matchTagFilters(item, filters)) return false;

        if (searchState.kind === 'novel') {
            const wc = Number(item.wordCount ?? 0);
            if (filters.wordsMin !== null && wc < filters.wordsMin) return false;
            if (filters.wordsMax !== null && wc > filters.wordsMax) return false;
            if (hasBookmarkFilter(filters)) {
                const bookmarkCount = getSearchBookmarkCount(item);
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
            const bookmarkCount = getSearchBookmarkCount(item);
            if (bookmarkCount === null) {
                stats.bookmarkMetaMissing++;
                return false;
            }
            if (filters.bookmarkMin !== null && bookmarkCount < filters.bookmarkMin) return false;
            if (filters.bookmarkMax !== null && bookmarkCount > filters.bookmarkMax) return false;
        }

        return true;
    }

    async function applyCurrentSearchFilters(options = {}) {
        const filters = normalizeSearchFilters(options.filters || getSearchFiltersFromUI());
        searchState.currentFilters = filters;
        setSearchFiltersUI(filters);
        saveSearchFilterPrefs(filters);

        const requestSeq = ++searchState.filterSeq;
        const sourceItems = searchState.rawResults || [];
        const bookmarkFilterActive = hasBookmarkFilter(filters);
        const needsBookmarkMeta = bookmarkFilterActive && sourceItems.some(item => {
            const cached = getCachedSearchMeta(item.id);
            return !cached || !cached.bookmarkResolved;
        });

        if (bookmarkFilterActive && needsBookmarkMeta && sourceItems.length) {
            document.getElementById('search-results-area').innerHTML =
                `<div class="search-spinner"><span class="search-spinner-icon"></span>${esc(bt('status.search-reading-bookmarks', '读取当前页收藏数中...'))}</div>`;
            document.getElementById('btn-add-all').disabled = true;
        }

        if (bookmarkFilterActive) {
            await ensureSearchBookmarkMeta(sourceItems, requestSeq);
            if (requestSeq !== searchState.filterSeq) return null;
        }

        const stats = {
            rawCount: sourceItems.length,
            filteredCount: 0,
            bookmarkMetaMissing: 0,
            bookmarkFilterActive
        };
        const filtered = sourceItems.filter(item => matchSearchFilters(item, filters, stats));
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

    async function handleSearchFilterChange() {
        const filters = getSearchFiltersFromUI();
        searchState.currentFilters = filters;
        saveSearchFilterPrefs(filters);
        if (!searchState.currentWord) return;
        if (searchState.submode === 'batch') searchState.localPage = 1;
        await applyCurrentSearchFilters({setStatus: true});
    }

    async function resetSearchFilters() {
        const filters = defaultSearchFilters();
        searchState.currentFilters = filters;
        setSearchFiltersUI(filters);
        saveSearchFilterPrefs(filters);
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

        const uiMode = document.querySelector('input[name="search-mode"]:checked').value;
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
        items: [],
        allItems: [],
        itemsByPage: new Map(),
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
        seriesState.items = [];
        seriesState.allItems = [];
        seriesState.itemsByPage = new Map();
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
            seriesState.items = items;
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
            seriesState.items = seriesState.itemsByPage.get(safePage) || [];
            renderSeriesResults();
            renderSeriesPagination();
            updateSeriesQueueButtons();
            return;
        }
        setSeriesLoading(bt('status.series-page-loading', '正在加载第 {page} 页...', {page: safePage}));
        try {
            const data = await fetchSeriesPage(safePage);
            cacheSeriesPageData(data, safePage);
            renderSeriesResults();
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
        } catch (e) {
            document.getElementById('series-results-area').innerHTML =
                `<div style="color:#dc3545;text-align:center;padding:24px 0;">${esc(bt('status.series-load-failed', '加载失败：{message}', {message: e.message}))}</div>`;
            renderSeriesPagination();
            updateSeriesQueueButtons();
            setStatus(bt('status.series-load-failed', '加载失败：{message}', {message: e.message}), 'error');
        }
    }

    function renderSeriesResults() {
        const area = document.getElementById('series-results-area');
        const metaEl = document.getElementById('series-meta-display');
        const renderToken = ++seriesState.renderToken;
        if (!seriesState.items.length) {
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
            esc(bt('series.meta.total', '共 {count} 个作品', {count: seriesState.seriesTotal || seriesState.allItems.length || seriesState.items.length})),
            pagePart ? esc(pagePart) : ''
        ].filter(Boolean).join(' · ');
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
        seriesState.items = seriesState.itemsByPage.get(seriesState.currentPage) || seriesState.items;
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
            const allItems = seriesState.allItems.length ? seriesState.allItems : seriesState.items;
            const result = addSeriesItemsToQueue(allItems, 0);
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
        const uiMode = document.querySelector('input[name="search-mode"]:checked').value;
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

        const btn = document.getElementById('btn-batch-fetch');
        btn.disabled = true;
        document.getElementById('search-results-area').innerHTML =
            `<div class="search-spinner"><span class="search-spinner-icon"></span>${esc(bt('status.batch-fetching', '批量获取第 {start}–{end} 页中...', {start, end}))}</div>`;
        document.getElementById('search-pagination').style.display = 'none';
        document.getElementById('btn-batch-add-page').disabled = true;
        document.getElementById('btn-batch-add-all').disabled = true;

        try {
            const endpoint = kind === 'novel' ? '/api/pixiv/novel-search/range' : '/api/pixiv/search/range';
            const params = new URLSearchParams({word, order, mode, sMode, startPage: start, endPage: end});
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
        document.getElementById('cookie-input').value = savedCookie;
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
        ['s-interval', 's-image-delay', 's-concurrent', 's-skip', 's-verify-files', 's-R18', 's-bookmark', 's-collection', 's-file-name-template', 's-novel-format', 's-novel-merge', 's-novel-merge-format'].forEach(id => {
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
            renderSearchResults();
            renderSearchPagination();
            document.getElementById('btn-add-all').disabled = true;
            updateBatchQueueButtons();
        });
        bindSubmodeSwitcher();

        // Mode
        const savedMode = normalizeImportMode(storeGet('pixiv_mode') || SINGLE_IMPORT_MODE);
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

        switchMode(savedMode);

        // Settings
        loadSettings();
        await refreshBatchCollections();

        // Queue
        loadQueueForMode();
        updateButtonsState();
        updateStats();
    }

    document.addEventListener('DOMContentLoaded', async () => {
        await initPageI18n();
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
