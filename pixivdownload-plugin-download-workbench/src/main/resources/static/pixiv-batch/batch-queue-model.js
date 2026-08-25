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
        parts.push(novelByteProgressHtml(q.novelText, 'queue.novel-text.label', '小说正文', 'var(--indigo)'));
        const e = q.novelEmbedded;
        if (e && e.total > 0) {
            parts.push(miniProgressHtml(
                bt('queue.novel-images.label', '内嵌图片'),
                bt('queue.novel-images.count', '{done}/{total} 张', {done: e.done || 0, total: e.total}),
                Math.round((e.done || 0) / e.total * 100),
                'var(--teal)'
            ));
        }
        parts.push(novelByteProgressHtml(q.novelCover, 'queue.novel-cover.label', '封面', 'var(--info)'));
        return parts.filter(Boolean).join('');
    }

    // 作品类型只贡献有界纯文本；共享渲染器固定 tone 样式并再次转义，不认识任何插件私有阶段。
    function formatQueueLiveStatusHtml(q) {
        const registry = window.PixivBatch && window.PixivBatch.queueTypes;
        if (!registry || typeof registry.queueLiveStatus !== 'function') return '';
        let status;
        try {
            status = registry.queueLiveStatus(q);
        } catch (e) {
            return '';
        }
        if (!status || typeof status !== 'object') return '';
        const colors = {
            info: 'var(--muted)',
            success: 'var(--brand)',
            warning: 'var(--warning-text)',
            error: 'var(--danger-bg)'
        };
        const color = colors[String(status.tone || '').toLowerCase()];
        if (!color) return '';
        const label = String(status.label == null ? '' : status.label).trim();
        const message = String(status.message == null ? '' : status.message).trim();
        if (!label || !message) return '';
        return `<div class="q-live-status" style="margin-top:4px;font-size:11px;color:${color};display:flex;align-items:center;gap:6px;">`
            + `<span style="border:1px solid currentColor;border-radius:3px;padding:0 5px;font-size:10px;">${esc(label)}</span>`
            + `<span>${esc(message)}</span></div>`;
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
            text = text + punct('colon') + reason;
        } else if (!['success', 'failed', 'skipped', 'exists'].includes(status) && reason) {
            text = text + punct('colon') + reason;
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
        const sep = punct('semicolon');
        return base + sep + parts.map(p => p.text).join(sep);
    }

    function buildPostDownloadMessageParts(base, baseTone, data) {
        const sep = punct('semicolon');
        const parts = [{text: base, tone: baseTone}].concat(postDownloadOutcomeParts(data));
        return parts.map((part, idx) => ({
            text: part.text + (idx < parts.length - 1 ? sep : ''),
            tone: part.tone
        }));
    }

    function toneColor(tone, fallback) {
        return {
            success: 'var(--brand)',
            error: 'var(--danger-bg)',
            warning: 'var(--warning-accent)',
            info: 'var(--primary)'
        }[tone] || fallback || 'var(--muted)';
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
            progress.status === 'failed' ? 'var(--danger-bg)' : 'var(--info)'
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
                'var(--info)'
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
                status === 'failed' ? 'var(--danger-bg)' : 'var(--violet)'
            ));
        }

        if (phase === 'extract') {
            const extracted = progress.totalFrames > 0
                ? bt('queue.ugoira.extracting-count', '正在解压帧 {current}/{total}', {
                    current: progress.extractedFrames || 0,
                    total: progress.totalFrames
                })
                : bt('queue.ugoira.extracting', '正在解压帧');
            parts.push(`<div class="queue-detail-note">${esc(extracted)}</div>`);
        } else if (status === 'failed') {
            parts.push(`<div class="queue-detail-note queue-detail-note--error">${esc(bt('queue.ugoira.failed', '动图处理失败'))}</div>`);
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
        return `<strong>${currentLabel}</strong> ${esc(item.title)} (ID: ${esc(item.id == null ? '' : item.id)})${prog}${formatImageDownloadProgressHtml(item.imageProgress, item.status)}${formatUgoiraProgressHtml(item.ugoiraProgress, item.status)}`;
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
