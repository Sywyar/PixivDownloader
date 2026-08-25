'use strict';
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
