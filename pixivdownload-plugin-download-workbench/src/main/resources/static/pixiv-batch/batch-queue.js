'use strict';
    function novelStageLabel(stage) {
        if (!stage) return '';
        return bt('queue.stage.' + stage, stage);
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

    // 「下载即自动翻译」的状态文案：模型只存 raw（phase / 已耗时 / 系列待译数），此处按当前语言派生。
    function novelTranslateMessage(q) {
        switch (q.translatePhase) {
            case 'QUEUED':
                return bt('queue.message.translate-waiting', '排队等待翻译...');
            case 'WAITING_SERIES':
                return bt('queue.message.translate-wait-series', '等待前系列小说翻译完成，还有 {n} 个',
                    {n: q.translateSeriesPending || 0});
            case 'RESOLVING':
                return bt('queue.message.translate-resolving', '识别目标语言中...');
            case 'TRANSLATING':
                return bt('queue.message.translating', 'AI 翻译中（{sec}s）', {sec: q.translateElapsed || 0});
            case 'MERGING':
                return bt('queue.message.translate-merging', '生成译文合订本中...');
            case 'SAME_LANGUAGE':
                return bt('queue.message.translate-same-lang', '完成（源语言与目标一致，已跳过）');
            case 'DONE':
                return bt('queue.message.translate-done', '完成（已翻译）');
            case 'FAILED':
                return bt('queue.message.translate-failed', '完成（翻译失败）');
            default:
                return '';
        }
    }

    // 在队列项底部渲染一行自动翻译状态（下载队列 / 计划队列共用）；无翻译态时不渲染。
    function formatNovelTranslateHtml(q) {
        if (q.kind !== 'novel' || !q.translatePhase) return '';
        const msg = novelTranslateMessage(q);
        if (!msg) return '';
        const terminal = q.translatePhase === 'DONE' || q.translatePhase === 'FAILED'
            || q.translatePhase === 'SAME_LANGUAGE';
        const color = q.translatePhase === 'FAILED' ? '#dc3545' : (terminal ? '#28a745' : '#8b5cf6');
        return `<div class="q-translate" style="margin-top:4px;font-size:11px;color:${color};display:flex;align-items:center;gap:6px;">`
            + `<span style="background:${color};color:white;border-radius:3px;padding:0 5px;font-size:10px;">${esc(bt('queue.translate.label', 'AI 翻译'))}</span>`
            + `<span>${esc(msg)}</span></div>`;
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

    // 渲染时派生队列项标题：模型里 title 只存原始字符串（可为空），
    // 此处补 i18n fallback —— 不能 bake 进模型，否则切换语言后旧译文会跟着 localStorage / 服务端快照一起留下来。
    function queueItemDisplayTitle(q) {
        if (q && q.title) return q.title;
        if (q && q.kind === 'novel') {
            const id = q.novelId || (q.id != null ? String(q.id).replace(/^n/, '') : '');
            return bt('queue.novel-fallback', '小说 {id}', {id});
        }
        return bt('queue.artwork-fallback', '作品 {id}', {id: q && q.id != null ? q.id : ''});
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

    function cloneQueueTypeData(value) {
        if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
        try {
            const json = JSON.stringify(value);
            if (!json || json.length > 4096) return null;
            const parsed = JSON.parse(json);
            return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : null;
        } catch {
            return null;
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
            const typeData = cloneQueueTypeData(m.typeData || m.pluginData);
            state.queue.push({
                id,
                kind: m.kind || 'illust',
                typeData,
                novelId: m.novelId || null,
                mergeAfterSeriesId: m.mergeAfterSeriesId || null,
                // title 存原始字符串（可为空），fallback 文案由渲染层 queueItemDisplayTitle(q) 派生，避免跨语言切换显示旧译。
                title: m.title || '',
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
        // 下载进行中追加新任务时补足 worker：worker 曾被抽干（全部退出）则重启，未满目标并发则补齐。
        if (state.isRunning && added > 0) {
            ensureWorkers();
        }
        syncAllResultsQueueState();
        return added;
    }

    async function handleExport() {
        if (!state.queue.length) {
            await uiAlertKey('alert.queue-empty', '队列为空');
            return;
        }
        const lines = state.queue.map(q =>
            `https://www.pixiv.net/artworks/${q.id} | ${queueItemDisplayTitle(q)}`);
        downloadTxt(lines.join('\n'), `pixiv_all_list_${Date.now()}.txt`);
        setStatus(bt('status.exported-all', '已导出 {count} 个作品', {count: lines.length}), 'success');
    }

    async function handleExportFailed() {
        const items = state.queue.filter(q => q.status !== 'completed');
        if (!items.length) {
            await uiAlertKey('alert.no-undownloaded', '没有未下载的作品');
            return;
        }
        const lines = items.map(q =>
            `https://www.pixiv.net/artworks/${q.id} | ${queueItemDisplayTitle(q)}`);
        downloadTxt(lines.join('\n'), `pixiv_undownloaded_list_${Date.now()}.txt`);
        setStatus(
            bt('status.exported-undownloaded', '已导出 {count} 个未下载作品', {count: lines.length}),
            'success'
        );
    }

    // 队列 Vue reactive 岛门面句柄（batch-queue-vue.js 注册）。缺失 / 未激活时各门面回退命令式渲染。
    function queueVue() {
        return window.PixivBatch && window.PixivBatch.queueVue;
    }
    function downloadQueueVueActive() {
        const qv = queueVue();
        return !!(qv && qv.isDownloadActive());
    }

    // 队列计数门面：始终重算 state.stats 并维护 sr-only #stats-bar（读屏 / 回归保留）。
    // 仪表盘 5 张统计卡：Vue 岛激活时合并进 reactive store（与速度卡同 store），否则命令式逐项写入数字。
    function updateStats() {
        state.stats.success = state.queue.filter(q => q.status === 'completed').length;
        state.stats.failed = state.queue.filter(q => q.status === 'failed').length;
        state.stats.active = state.queue.filter(q => q.status === 'downloading').length;
        state.stats.skipped = state.queue.filter(q => q.status === 'skipped').length;
        const pending = state.queue.filter(q =>
            ['idle', 'pending', 'paused'].includes(q.status)).length;
        const statsBar = document.getElementById('stats-bar');
        if (statsBar) {
            statsBar.textContent = formatStatsText(
                pending,
                state.stats.success,
                state.stats.failed,
                state.stats.active,
                state.stats.skipped
            );
        }
        if (downloadQueueVueActive()) {
            queueVue().syncDownloadStats({
                pending,
                success: state.stats.success,
                failed: state.stats.failed,
                active: state.stats.active,
                skipped: state.stats.skipped
            });
            return;
        }
        // 顶部仪表盘 5 张统计卡：与 #stats-bar 同源，逐项写入对应数字（卡片缺失即跳过）。
        setStatCount('stat-count-pending', pending);
        setStatCount('stat-count-success', state.stats.success);
        setStatCount('stat-count-failed', state.stats.failed);
        setStatCount('stat-count-active', state.stats.active);
        setStatCount('stat-count-skipped', state.stats.skipped);
    }

    function setStatCount(id, value) {
        const el = document.getElementById(id);
        if (el) el.textContent = value;
    }

    /* ============================================================
       下载总速度计量
       SSE 聚合连接是所有作品下载进度的汇聚点：每条 download-status 事件按其字节进度算「全局单调累计字节」，
       定时器每秒采样累计值的增量得到速度。基线 / 定时器随下载生命周期（共享 SSE 的建立 / 关闭）启停。
    ============================================================ */
    function speedNowMs() {
        return (typeof performance !== 'undefined' && performance.now) ? performance.now() : Date.now();
    }

    // 累计单条传输流的正增量：每个传输流（某作品的第 N 张图 / 动图包 / 小说封面）的字节单调递增，
    // 用 key 记住上次见到的值，只把正增量计入全局累计，从而跨多作品 / 多图正确汇总。
    function addSpeedSample(key, cur) {
        if (!Number.isFinite(cur) || cur < 0) return;
        const prev = state.speedSamples[key] || 0;
        if (cur > prev) {
            state.speedAccumBytes += (cur - prev);
            state.speedSamples[key] = cur;
        } else if (cur < prev) {
            // 同 key 字节回退（极少见，视作新一段传输）：把当前值整体计入增量
            state.speedAccumBytes += cur;
            state.speedSamples[key] = cur;
        }
    }

    function accumulateDownloadSpeed(aid, data) {
        if (!aid || !data) return;
        const ip = data.imageProgress;
        if (ip) addSpeedSample(aid + ':img:' + (ip.imageNumber != null ? ip.imageNumber : 0), Number(ip.downloadedBytes));
        const up = data.ugoiraProgress;
        if (up) addSpeedSample(aid + ':zip', Number(up.zipDownloadedBytes));
        if (data.coverDownloadedBytes != null) addSpeedSample(aid + ':cover', Number(data.coverDownloadedBytes));
        // 该作品终态：清掉其传输流基线，避免 speedSamples 无限增长。
        if (data.completed || data.failed || data.cancelled) clearSpeedSamplesForItem(aid);
    }

    function clearSpeedSamplesForItem(aid) {
        const prefix = aid + ':';
        Object.keys(state.speedSamples).forEach(k => {
            if (k.indexOf(prefix) === 0) delete state.speedSamples[k];
        });
    }

    function startSpeedMeter() {
        if (state.speedTimer) return;   // 幂等：已在计量则不重置基线
        state.speedSamples = {};
        state.speedAccumBytes = 0;
        state.speedLastAccum = 0;
        state.speedLastTime = speedNowMs();
        renderDownloadSpeed(0);
        state.speedTimer = setInterval(sampleDownloadSpeed, 1000);
    }

    function stopSpeedMeter() {
        if (state.speedTimer) {
            clearInterval(state.speedTimer);
            state.speedTimer = null;
        }
        state.speedSamples = {};
        renderDownloadSpeed(0);
    }

    function sampleDownloadSpeed() {
        const now = speedNowMs();
        const dt = (now - state.speedLastTime) / 1000;
        const delta = state.speedAccumBytes - state.speedLastAccum;
        state.speedLastAccum = state.speedAccumBytes;
        state.speedLastTime = now;
        renderDownloadSpeed(dt > 0 ? Math.max(0, delta / dt) : 0);
    }

    // 按速度大小自适应单位：B/s · KB/s · MB/s · GB/s。
    function formatSpeed(bytesPerSec) {
        const b = Number(bytesPerSec);
        if (!Number.isFinite(b) || b < 1) return {value: '0', unit: 'B/s'};
        const units = ['B/s', 'KB/s', 'MB/s', 'GB/s'];
        let v = b, i = 0;
        while (v >= 1024 && i < units.length - 1) {
            v /= 1024;
            i++;
        }
        let value;
        if (i === 0 || v >= 100) value = String(Math.round(v));
        else if (v >= 10) value = v.toFixed(1);
        else value = v.toFixed(2);
        return {value, unit: units[i]};
    }

    // 速度卡门面：Vue 岛激活时合并进 reactive store（速度卡随 .dash-stats 一并由 Vue 渲染），
    // 否则命令式写入数字 / 单位两个 span。formatSpeed 为两路共享口径。
    function renderDownloadSpeed(bytesPerSec) {
        const {value, unit} = formatSpeed(bytesPerSec);
        if (downloadQueueVueActive()) {
            queueVue().syncDownloadSpeed(value, unit);
            return;
        }
        const valEl = document.getElementById('stat-speed-value');
        const unitEl = document.getElementById('stat-speed-unit');
        if (!valEl && !unitEl) return;
        if (valEl) valEl.textContent = value;
        if (unitEl) unitEl.textContent = unit;
    }

    function setCurrent(item) {
        state.currentItemId = item ? String(item.id) : null;
        if (downloadQueueVueActive()) {
            queueVue().syncDownloadCurrent(item);
            return;
        }
        const el = document.getElementById('current-card');
        if (el) el.innerHTML = formatCurrentCardHtml(item);
    }

    // 队列列表门面：Vue 岛激活时合并一次 reactive 同步（按 :key + v-html 仅 patch 变化的行，不整队列重建），
    // 否则命令式整块渲染。两路都刷新管理员打包按钮（仅依赖 state.queue，与渲染路径正交）。
    function renderQueue() {
        if (downloadQueueVueActive()) {
            queueVue().syncDownloadList();
        } else {
            renderQueueImperative();
        }
        updateAdminPackButton();
    }

    function renderQueueImperative() {
        const el = document.getElementById('queue-list');
        if (!el) return;
        if (!state.queue.length) {
            el.innerHTML = `<div class="queue-empty">${esc(bt('status.queue-empty', '队列为空'))}</div>`;
            return;
        }
        el.innerHTML = state.queue.map(q => buildQueueItemHtml(q, {removable: true})).join('');
    }

    // 单个队列项的 HTML。下载工作区底部的「下载队列」与计划任务卡片底部的「本轮队列详情」共用此函数，
    // 保证两处队列展示完全一致（进度条、来源/分级/AI 标记、小说进度等）。
    // opts.removable=false 时不渲染移除按钮（计划任务为服务端队列，前端不可移除）。
    // opts.queueId 给行根节点打一个稳定的 data-queue-id，供「只替换单行 outerHTML」的局部刷新定位该行
    //（计划任务详情高频 SSE 刷新用，避免整块 innerHTML 重建）；不传则不输出该属性，普通队列调用不受影响。
    function buildQueueItemHtml(q, opts) {
        const removable = !opts || opts.removable !== false;
        const queueIdAttr = opts && opts.queueId != null
            ? ` data-queue-id="${esc(String(opts.queueId))}"`
            : '';
        const prog = q.totalImages > 0
            ? `<div class="prog-wrap">
          <div class="prog-label"><span>${esc(formatImageProgressText(q.downloadedCount || 0, q.totalImages))}</span><span>${pct(q)}%</span></div>
         <div class="prog-bg"><div class="prog-fill" style="width:${pct(q)}%;background:${statusColor(q.status)}"></div></div>
         </div>` : '';
        const detailProg = formatImageDownloadProgressHtml(q.imageProgress, q.status)
            + formatUgoiraProgressHtml(q.ugoiraProgress, q.status)
            + formatNovelProgressHtml(q)
            + formatNovelTranslateHtml(q);
        const desc = q.lastMessage || queueStatusText(q.status);
        const descHtml = renderQueueMessageHtml(q, desc);
        const sourceTone = q.source === 'user'
            ? {label: queueSourceText('user'), bg: '#007bff'}
            : q.source === 'search' || q.source === 'search-novel'
                ? {label: queueSourceText('search'), bg: '#28a745'}
                : q.source === 'series' || q.source === 'series-novel'
                    ? {label: queueSourceText('series'), bg: '#6366f1'}
                    : q.source === QUICK_FETCH_MODE
                        ? {label: queueSourceText(QUICK_FETCH_MODE), bg: '#f59e0b'}
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
        return `<div class="queue-item"${queueIdAttr} style="border-left-color:${statusColor(q.status)}">
      <div class="q-title" style="display:flex;align-items:center;gap:2px;">
        <span style="flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">${esc(queueItemDisplayTitle(q))}${novelTag}${srcLabel}${R18Label}${AILabel}</span>
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
                    // 翻译轮询在刷新后不会自动恢复：清掉未结束的翻译态，避免残留「AI 翻译中」静态文案。
                    if (q.translatePhase && q.translatePhase !== 'DONE' && q.translatePhase !== 'FAILED'
                        && q.translatePhase !== 'SAME_LANGUAGE') {
                        q.translatePhase = null;
                        q.translateElapsed = 0;
                        q.translateSeriesPending = 0;
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

    // 队列发生增 / 删 / 清空后，统一把四个模式预览网格的「✓ 在队列中」标记与最新 state.queue 对齐，
    // 避免清除队列或移除单项后 User / Search / 系列 / 快捷获取 预览残留过期的在队列标记。
    // 各 sync 在自身 state 为空或 DOM 不存在时自行早退，故任意当前模式下调用都安全。
    function syncAllResultsQueueState() {
        syncSearchResultsQueueState();
        syncSeriesResultsQueueState();
        syncUserResultsQueueState();
        syncQuickQueueState();
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
        syncAllResultsQueueState();
        return true;
    }


// ---- PixivBatch facade ----
window.PixivBatch.queue = window.PixivBatch.queue || {};
window.PixivBatch.queue = Object.assign(window.PixivBatch.queue, { addItemsToQueue, removeFromQueue, syncAllResultsQueueState, renderQueue, buildQueueItemHtml, updateStats, setCurrent, handleExport, handleExportFailed, dedupeQueueItems, queueItemDisplayTitle, renderQueueMessageHtml });
