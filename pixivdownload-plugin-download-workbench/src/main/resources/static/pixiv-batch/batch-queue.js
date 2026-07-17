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

    function queueAcquisitionMode(source) {
        const normalizedSource = String(normalizeImportMode(source) || '').trim();
        if (normalizedSource === QUICK_FETCH_MODE
            || normalizedSource.startsWith(QUICK_FETCH_MODE + '-')) return 'quick';
        if (normalizedSource === SINGLE_IMPORT_MODE
            || normalizedSource.startsWith(SINGLE_IMPORT_MODE + '-')) return 'single-import';
        if (normalizedSource === 'user' || normalizedSource.startsWith('user-')) return 'user';
        if (normalizedSource === 'search' || normalizedSource.startsWith('search-')) return 'search';
        if (normalizedSource === 'series' || normalizedSource.startsWith('series-')) return 'series';
        if (normalizedSource === 'schedule' || normalizedSource.startsWith('schedule-')) return 'schedule';
        return 'single-import';
    }

    function queueSourceText(source) {
        return {
            user: bt('queue.source.user', 'User'),
            search: bt('queue.source.search', 'Search'),
            series: bt('queue.source.series', 'Series'),
            quick: bt('queue.source.quick-fetch', '快捷'),
            'single-import': bt('queue.source.import', '导入'),
            schedule: bt('queue.source.schedule', '计划')
        }[queueAcquisitionMode(source)] || bt('queue.source.import', '导入');
    }

    function normalizeQueueDataSource(value) {
        if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
        const id = value.id == null ? '' : String(value.id).trim();
        const displayNamespace = value.displayNamespace == null
            ? '' : String(value.displayNamespace).trim();
        const displayI18nKey = value.displayI18nKey == null
            ? '' : String(value.displayI18nKey).trim();
        if (!id || id.length > 64 || displayNamespace.length > 128 || displayI18nKey.length > 160) {
            return null;
        }
        return {id, displayNamespace, displayI18nKey};
    }

    function activeQueueDataSource(kind, source) {
        const queueTypes = window.PixivBatch && window.PixivBatch.queueTypes;
        if (!queueTypes || typeof queueTypes.dataSourceForType !== 'function') return null;
        try {
            return normalizeQueueDataSource(
                queueTypes.dataSourceForType(kind, queueAcquisitionMode(source)));
        } catch (e) {
            console.warn('[queue] 队列数据来源解析失败：', kind, e);
            return null;
        }
    }

    function queueDataSource(item) {
        const live = activeQueueDataSource(item && item.kind, item && item.source);
        const stored = normalizeQueueDataSource(item && item.dataSource);
        if (live || stored) return live || stored;
        const fallback = item && item.kind != null ? String(item.kind).trim() : '';
        return fallback ? {id: fallback, displayNamespace: '', displayI18nKey: ''} : null;
    }

    function queueDataSourceText(source) {
        if (!source) return bt('queue.unknown', '未知');
        if (!source.displayI18nKey) return source.id;
        const key = source.displayNamespace
            ? source.displayNamespace + ':' + source.displayI18nKey
            : source.displayI18nKey;
        return bt(key, source.id);
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
        if (!q.statusMessageKey && Array.isArray(q.lastMessageParts) && q.lastMessageParts.length) {
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
    const MAX_QUEUE_CANCEL_WORK_KEY_LENGTH = 4096;
    const queueActionRoots = new WeakSet();

    function normalizeQueueCancelWorkKey(value) {
        if (typeof value !== 'string' || value.length === 0
            || value.length > MAX_QUEUE_CANCEL_WORK_KEY_LENGTH || value.trim() === '') {
            return null;
        }
        return value;
    }

    function dedupeQueueItems(items) {
        const seen = new Map();
        const uniqueItems = [];
        for (const item of items || []) {
            if (!item || item.id === undefined || item.id === null) continue;
            const id = String(item.id);
            const existing = seen.get(id);
            if (existing) {
                reconcileQueueItemTypeData(existing, item, 'restore');
                continue;
            }
            const normalized = {...item, id};
            const cancelWorkKey = normalizeQueueCancelWorkKey(normalized.cancelWorkKey);
            if (cancelWorkKey === null) delete normalized.cancelWorkKey;
            else normalized.cancelWorkKey = cancelWorkKey;
            normalized.typeData = reconcileQueueTypeData(
                normalized.kind || 'illust',
                normalized.typeData || normalized.pluginData,
                null,
                'restore'
            ).typeData;
            normalized.dataSource = activeQueueDataSource(
                normalized.kind || 'illust', normalized.source)
                || normalizeQueueDataSource(normalized.dataSource);
            normalized.canonicalUrl = queueItemCanonicalUrl(normalized);
            seen.set(id, normalized);
            uniqueItems.push(normalized);
        }
        return uniqueItems;
    }

    const MAX_QUEUE_TYPE_DATA_LENGTH = 65536;
    const MAX_QUEUE_CANONICAL_URL_LENGTH = 4096;

    function normalizeQueueCanonicalUrl(value) {
        const normalized = value == null ? '' : String(value).trim();
        if (!normalized || normalized.length > MAX_QUEUE_CANONICAL_URL_LENGTH
            || !/^https?:\/\/[^\s]+$/i.test(normalized)) return '';
        return normalized;
    }

    function queueItemStoredCanonicalUrl(item) {
        const stored = normalizeQueueCanonicalUrl(item && item.canonicalUrl);
        if (stored) return stored;
        const typeData = item && (item.typeData || item.pluginData);
        if (!typeData || typeof typeData !== 'object' || Array.isArray(typeData)) return '';
        return normalizeQueueCanonicalUrl(typeData.canonicalUrl)
            || normalizeQueueCanonicalUrl(typeData.input)
            || normalizeQueueCanonicalUrl(typeData.url);
    }

    function cloneQueueTypeData(value) {
        if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
        try {
            const json = JSON.stringify(value);
            if (!json || json.length > MAX_QUEUE_TYPE_DATA_LENGTH) return null;
            const parsed = JSON.parse(json);
            return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : null;
        } catch {
            return null;
        }
    }

    function reconcileQueueTypeData(kind, currentValue, incomingValue, reason) {
        const currentData = cloneQueueTypeData(currentValue);
        const incomingData = cloneQueueTypeData(incomingValue);
        const fallback = currentData || incomingData;
        const queueTypes = window.PixivBatch && window.PixivBatch.queueTypes;
        const behavior = queueTypes && typeof queueTypes.get === 'function'
            ? queueTypes.get(String(kind || '')) : null;
        if (!behavior || typeof behavior.mergeQueueTypeData !== 'function') {
            return {typeData: fallback, keepExisting: false, changed: false, reprocessExisting: false};
        }
        try {
            const result = behavior.mergeQueueTypeData(
                currentData,
                incomingData,
                Object.freeze({reason: String(reason || 'add')})
            );
            if (!result || typeof result !== 'object' || typeof result.then === 'function'
                || !Object.prototype.hasOwnProperty.call(result, 'typeData')) {
                throw new Error('mergeQueueTypeData must return a synchronous result with typeData');
            }
            const nextData = result.typeData == null ? null : cloneQueueTypeData(result.typeData);
            if (result.typeData != null && !nextData) {
                throw new Error('mergeQueueTypeData returned invalid or oversized typeData');
            }
            const changed = JSON.stringify(currentData) !== JSON.stringify(nextData);
            return {
                typeData: nextData,
                keepExisting: result.keepExisting === true,
                changed,
                reprocessExisting: result.reprocessExisting === true && changed
            };
        } catch (e) {
            console.warn('[queue] 队列类型数据合并失败：', kind, e);
            return {typeData: fallback, keepExisting: false, changed: false, reprocessExisting: false};
        }
    }

    function reconcileQueueItemTypeData(existingItem, incomingMeta, reason) {
        if (!existingItem || typeof existingItem !== 'object') {
            return {
                typeData: null,
                keepExisting: false,
                changed: false,
                reprocessExisting: false,
                requeued: false
            };
        }
        const incoming = incomingMeta && typeof incomingMeta === 'object' ? incomingMeta : {};
        const result = reconcileQueueTypeData(
            existingItem.kind || incoming.kind || 'illust',
            existingItem.typeData || existingItem.pluginData,
            incoming.typeData || incoming.pluginData,
            reason
        );
        if (result.changed) existingItem.typeData = result.typeData;
        const activeDataSource = activeQueueDataSource(
            existingItem.kind || incoming.kind || 'illust',
            existingItem.source || incoming.source
        );
        if (activeDataSource) {
            existingItem.dataSource = activeDataSource;
        } else if (!normalizeQueueDataSource(existingItem.dataSource)) {
            existingItem.dataSource = normalizeQueueDataSource(incoming.dataSource);
        }
        if (!normalizeQueueCanonicalUrl(existingItem.canonicalUrl)) {
            const incomingCanonical = queueItemStoredCanonicalUrl(incoming);
            existingItem.canonicalUrl = incomingCanonical || queueItemCanonicalUrl(existingItem);
        }
        if (normalizeQueueCancelWorkKey(existingItem.cancelWorkKey) === null) {
            const incomingCancelWorkKey = normalizeQueueCancelWorkKey(incoming.cancelWorkKey);
            if (incomingCancelWorkKey !== null) existingItem.cancelWorkKey = incomingCancelWorkKey;
        }
        let requeued = false;
        if (result.reprocessExisting
            && ['completed', 'failed', 'skipped'].includes(String(existingItem.status || ''))) {
            existingItem.status = state.isRunning ? 'pending' : 'idle';
            existingItem.statusMessageKey = null;
            existingItem.lastMessage = '';
            existingItem.lastMessageParts = null;
            existingItem.startTime = null;
            existingItem.endTime = null;
            requeued = true;
            if (state.isRunning && typeof ensureWorkers === 'function') ensureWorkers();
        }
        return Object.assign({}, result, {requeued});
    }

    function addItemsToQueue(idList, metaList, source, username, defaultAuthorId, defaultAuthorName) {
        const existing = new Map(state.queue.map(q => [String(q.id), q]));
        let added = 0;
        const meta = metaList || [];
        for (let i = 0; i < idList.length; i++) {
            const id = String(idList[i]);
            const m = meta[i] || {};
            const queued = existing.get(id);
            if (queued) {
                reconcileQueueItemTypeData(queued, m, 'add');
                continue;
            }
            const authorId = normalizeAuthorId(m.authorId ?? defaultAuthorId);
            const authorName = m.authorName || defaultAuthorName || '';
            const typeData = reconcileQueueTypeData(
                m.kind || 'illust', null, m.typeData || m.pluginData, 'add'
            ).typeData;
            const normalizedSource = normalizeImportMode(source || SINGLE_IMPORT_MODE);
            const queueItem = {
                id,
                kind: m.kind || 'illust',
                typeData,
                dataSource: activeQueueDataSource(m.kind || 'illust', normalizedSource)
                    || normalizeQueueDataSource(m.dataSource),
                novelId: m.novelId || null,
                mergeAfterSeriesId: m.mergeAfterSeriesId || null,
                // title 存原始字符串（可为空），fallback 文案由渲染层 queueItemDisplayTitle(q) 派生，避免跨语言切换显示旧译。
                title: m.title || '',
                status: state.isRunning ? 'pending' : 'idle',
                source: normalizedSource,
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
                statusMessageKey: null,
                lastMessage: '',
                lastMessageParts: null,
                bookmarkResult: null,
                collectionResult: null,
                ugoiraProgress: null,
                imageProgress: null
            };
            const cancelWorkKey = normalizeQueueCancelWorkKey(m.cancelWorkKey);
            if (cancelWorkKey !== null) queueItem.cancelWorkKey = cancelWorkKey;
            queueItem.canonicalUrl = normalizeQueueCanonicalUrl(m.canonicalUrl)
                || queueItemCanonicalUrl(queueItem);
            state.queue.push(queueItem);
            existing.set(id, queueItem);
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

    const QUEUE_ITEM_PATCH_FIELDS = new Set([
        'status', 'rawStatus', 'failureCode', 'statusMessageKey',
        'downloadedCount', 'totalImages', 'startTime', 'endTime', 'cancelWorkKey'
    ]);
    const QUEUE_ITEM_PROCESS_STATUSES = new Set(['downloading', 'completed', 'failed', 'skipped']);

    // 外部下载类型只能通过此宿主桥回写受控的运行态字段。完整校验通过后才一次性应用，
    // 随即重算统计、持久化并渲染，避免插件握有 state/saveQueue/renderQueue 等宿主私有能力。
    function commitQueueItemPatch(item, patch) {
        if (!item || !state.queue.includes(item)) throw new Error('queue item is not active');
        if (!patch || typeof patch !== 'object' || Array.isArray(patch)) {
            throw new Error('queue item patch must be a plain object');
        }
        const proto = Object.getPrototypeOf(patch);
        if (proto !== Object.prototype && proto !== null) {
            throw new Error('queue item patch must be a plain object');
        }
        const keys = Object.keys(patch);
        if (!keys.length) return item;
        keys.forEach(key => {
            if (!QUEUE_ITEM_PATCH_FIELDS.has(key)) throw new Error('unsupported queue item patch field: ' + key);
        });

        const normalized = Object.create(null);
        if (Object.prototype.hasOwnProperty.call(patch, 'status')) {
            const status = String(patch.status || '').trim();
            if (!QUEUE_ITEM_PROCESS_STATUSES.has(status)) throw new Error('unsupported queue item status');
            normalized.status = status;
        }
        ['rawStatus', 'failureCode'].forEach(key => {
            if (!Object.prototype.hasOwnProperty.call(patch, key)) return;
            if (patch[key] == null || String(patch[key]).trim() === '') {
                normalized[key] = null;
                return;
            }
            const value = String(patch[key]).trim();
            if (value.length > 128) throw new Error(key + ' is too long');
            normalized[key] = value;
        });
        if (Object.prototype.hasOwnProperty.call(patch, 'statusMessageKey')) {
            if (patch.statusMessageKey == null || String(patch.statusMessageKey).trim() === '') {
                normalized.statusMessageKey = null;
            } else {
                const key = String(patch.statusMessageKey).trim();
                if (key.length > 193 || !/^[a-z0-9][a-z0-9._-]{0,63}:[^\s:]{1,128}$/i.test(key)) {
                    throw new Error('invalid statusMessageKey');
                }
                normalized.statusMessageKey = key;
            }
        } else if (Object.prototype.hasOwnProperty.call(normalized, 'status')
            && normalized.status !== 'failed') {
            normalized.statusMessageKey = null;
        }
        ['downloadedCount', 'totalImages'].forEach(key => {
            if (!Object.prototype.hasOwnProperty.call(patch, key)) return;
            const value = Number(patch[key]);
            if (!Number.isSafeInteger(value) || value < 0) throw new Error(key + ' must be a non-negative integer');
            normalized[key] = value;
        });
        ['startTime', 'endTime'].forEach(key => {
            if (!Object.prototype.hasOwnProperty.call(patch, key)) return;
            if (patch[key] == null) {
                normalized[key] = null;
                return;
            }
            const value = String(patch[key]).trim();
            const timestamp = Date.parse(value);
            if (!value || value.length > 64 || !Number.isFinite(timestamp)
                || new Date(timestamp).toISOString() !== value) {
                throw new Error(key + ' must be an ISO-8601 timestamp or null');
            }
            normalized[key] = value;
        });
        if (Object.prototype.hasOwnProperty.call(patch, 'cancelWorkKey')) {
            const value = normalizeQueueCancelWorkKey(patch.cancelWorkKey);
            if (value === null) throw new Error('cancelWorkKey must be a non-blank string');
            normalized.cancelWorkKey = value;
        }

        Object.keys(normalized).forEach(key => { item[key] = normalized[key]; });
        updateStats();
        saveQueue();
        renderQueue();
        return item;
    }

    function queueItemCanonicalUrl(item) {
        const kind = String(item && item.kind || 'illust');
        const fallback = kind === 'illust'
            ? `https://www.pixiv.net/artworks/${item && item.id != null ? item.id : ''}`
            : '';
        const stored = queueItemStoredCanonicalUrl(item);
        if (stored) return stored;
        const queueTypes = window.PixivBatch && window.PixivBatch.queueTypes;
        const behavior = queueTypes && typeof queueTypes.get === 'function'
            ? queueTypes.get(kind) : null;
        if (!behavior || typeof behavior.canonicalUrl !== 'function') return fallback;
        try {
            const typeData = cloneQueueTypeData(item && (item.typeData || item.pluginData));
            const snapshot = Object.freeze(Object.assign({}, item || {}, {typeData}));
            const value = behavior.canonicalUrl(snapshot);
            if (value && typeof value.then === 'function') {
                throw new Error('canonicalUrl must return a synchronous value');
            }
            const normalized = normalizeQueueCanonicalUrl(value);
            return normalized || fallback;
        } catch (e) {
            console.warn('[queue] 队列类型规范 URL 解析失败：', item && item.kind, e);
            return fallback;
        }
    }

    function buildQueueExportLines(items) {
        return (items || []).map(q =>
            `${queueItemCanonicalUrl(q)} | ${queueItemDisplayTitle(q)}`);
    }

    async function handleExport() {
        if (!state.queue.length) {
            await uiAlertKey('alert.queue-empty', '队列为空');
            return;
        }
        const lines = buildQueueExportLines(state.queue);
        downloadTxt(lines.join('\n'), `pixiv_all_list_${Date.now()}.txt`);
        setStatus(bt('status.exported-all', '已导出 {count} 个作品', {count: lines.length}), 'success');
    }

    async function handleExportFailed() {
        const items = state.queue.filter(q => q.status !== 'completed');
        if (!items.length) {
            await uiAlertKey('alert.no-undownloaded', '没有未下载的作品');
            return;
        }
        const lines = buildQueueExportLines(items);
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

    function canCancelQueueItem(item) {
        if (!item || item.status !== 'downloading') return false;
        const queueTypes = window.PixivBatch && window.PixivBatch.queueTypes;
        if (!queueTypes || typeof queueTypes.canCancel !== 'function') return false;
        try {
            return queueTypes.canCancel(item) === true;
        } catch (e) {
            console.warn('[queue] 队列单项取消能力检查失败：', item.kind, e);
            return false;
        }
    }

    async function requestQueueItemCancel(id) {
        const item = state.queue.find(candidate => String(candidate.id) === String(id));
        const queueTypes = window.PixivBatch && window.PixivBatch.queueTypes;
        if (!item || item.status !== 'downloading' || !queueTypes
            || typeof queueTypes.cancel !== 'function' || !canCancelQueueItem(item)) {
            setStatus(bt('status.cancel-failed', '取消下载请求失败'), 'error');
            renderQueue();
            return false;
        }
        try {
            await queueTypes.cancel(item);
            setStatus(bt('status.cancel-requested', '已请求取消下载'), 'success');
            return true;
        } catch (e) {
            console.warn('[queue] 队列单项取消请求失败：', item.kind, e);
            setStatus(bt('status.cancel-failed', '取消下载请求失败'), 'error');
            renderQueue();
            return false;
        }
    }

    function bindQueueActions(root) {
        if (!root || typeof root.addEventListener !== 'function' || queueActionRoots.has(root)) return false;
        root.addEventListener('click', event => {
            const target = event && event.target;
            const button = target && typeof target.closest === 'function'
                ? target.closest('[data-queue-cancel-id]') : null;
            if (!button) return;
            if (event && typeof event.preventDefault === 'function') event.preventDefault();
            if (event && typeof event.stopPropagation === 'function') event.stopPropagation();
            requestQueueItemCancel(button.getAttribute('data-queue-cancel-id'));
        });
        queueActionRoots.add(root);
        return true;
    }

    // 单个队列项的 HTML。下载工作区底部的「下载队列」与计划任务卡片底部的「本轮队列详情」共用此函数，
    // 保证两处队列展示完全一致（进度条、数据来源/模式/分级/插件标签、小说进度等）。
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
        const desc = q.statusMessageKey
            ? bt(q.statusMessageKey, q.lastMessage || queueStatusText(q.status))
            : (q.lastMessage || queueStatusText(q.status));
        const descHtml = renderQueueMessageHtml(q, desc);
        const sourceDescriptor = queueDataSource(q);
        const sourceLabel = `<span class="queue-tag queue-tag--source" data-source-id="${esc(sourceDescriptor ? sourceDescriptor.id : 'unknown')}">${esc(queueDataSourceText(sourceDescriptor))}</span>`;
        const acquisitionMode = queueAcquisitionMode(q.source);
        const modeClass = acquisitionMode === 'single-import' ? 'import' : acquisitionMode;
        const modeLabel = `<span class="queue-tag queue-tag--mode queue-tag--mode-${modeClass}">${esc(queueSourceText(q.source))}</span>`;
        const xRestrict = q.xRestrict == null ? null : Number(q.xRestrict);
        const rating = xRestrict === 2
            ? {id: 'r18g', label: 'R-18G'}
            : xRestrict === 1
                ? {id: 'r18', label: 'R-18'}
                : xRestrict === null || !Number.isFinite(xRestrict)
                    ? {id: 'unknown', label: bt('queue.unknown', '未知')}
                    : {id: 'sfw', label: 'SFW'};
        const ratingLabel = `<span class="queue-tag queue-tag--rating queue-tag--rating-${rating.id}">${esc(rating.label)}</span>`;
        const queueTypes = window.PixivBatch && window.PixivBatch.queueTypes;
        const contributedTags = queueTypes && typeof queueTypes.queueTags === 'function'
            ? queueTypes.queueTags(q) : [];
        const pluginLabels = (Array.isArray(contributedTags) ? contributedTags : [])
            .map(tag => `<span class="queue-tag queue-tag--plugin" data-queue-tag-id="${esc(tag.id)}">${esc(tag.label)}</span>`)
            .join('');
        const canRemove = removable && q.status !== 'downloading';
        const cancelBtn = removable && canCancelQueueItem(q)
            ? `<button type="button" class="queue-cancel-btn" data-queue-cancel-id="${esc(String(q.id))}" title="${esc(bt('queue.cancel', '取消下载'))}" aria-label="${esc(bt('queue.cancel', '取消下载'))}">■</button>`
            : '';
        const removeBtn = canRemove
            ? `<button onclick="removeFromQueue('${q.id}');event.stopPropagation();" title="${esc(bt('queue.remove', '移除'))}" style="background:none;border:none;color:#aaa;cursor:pointer;font-size:13px;padding:0 2px;line-height:1;" onmouseover="this.style.color='#dc3545'" onmouseout="this.style.color='#aaa'">✕</button>`
            : '';
        const isNovel = q.kind === 'novel';
        const linkHref = queueItemCanonicalUrl(q) || (isNovel
            ? `https://www.pixiv.net/novel/show.php?id=${encodeURIComponent(q.novelId || String(q.id).replace(/^n/, ''))}`
            : '');
        const linkBtn = linkHref
            ? `<a href="${esc(linkHref)}" target="_blank" onclick="event.stopPropagation();" title="${esc(bt('queue.open-artwork', '打开作品页面'))}" style="color:#007bff;font-size:13px;padding:0 2px;text-decoration:none;line-height:1;">🔗</a>`
            : '';
        return `<div class="queue-item"${queueIdAttr} style="border-left-color:${statusColor(q.status)}">
      <div class="q-title">
        <span class="q-title-main">${esc(queueItemDisplayTitle(q))}</span>
        ${linkBtn}${cancelBtn}${removeBtn}
      </div>
      <div class="q-tags">${sourceLabel}${modeLabel}${ratingLabel}${pluginLabels}</div>
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
                        q.statusMessageKey = null;
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
window.PixivBatch.queue = Object.assign(window.PixivBatch.queue, { addItemsToQueue, removeFromQueue, requestQueueItemCancel, bindQueueActions, reconcileQueueItemTypeData, commitQueueItemPatch, syncAllResultsQueueState, renderQueue, buildQueueItemHtml, updateStats, setCurrent, handleExport, handleExportFailed, dedupeQueueItems, queueItemDisplayTitle, queueItemCanonicalUrl, buildQueueExportLines, renderQueueMessageHtml });
