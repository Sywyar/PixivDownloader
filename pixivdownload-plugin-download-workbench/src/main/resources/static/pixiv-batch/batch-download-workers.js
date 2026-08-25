'use strict';
    async function start() {
        if (state.isRunning) return;
        if (state.queue.length === 0) {
            setStatus(bt('status.queue-empty', '队列为空'), 'error');
            return;
        }
        if (!await checkBackend()) {
            await uiAlertKey('alert.backend-unavailable', '后端服务不可用，请确认后端已启动');
            return;
        }

        await refreshBatchCollections();
        // 后端检查 / 收藏夹刷新期间可能发生重复点击；较早的调用已经启动时，后续调用不得重置 worker 状态。
        if (state.isRunning) return;

        state.queue.forEach(q => {
            if (['idle', 'failed', 'paused'].includes(q.status)) {
                q.status = 'pending';
                q.lastMessageParts = null;
            }
        });
        state.isPaused = false;
        state.stopRequested = false;
        state.activeWorkers = 0;
        quotaExceededHandled = false;

        // completed / skipped 是终态；队列没有待处理项时直接收尾，避免打开 SSE 或启动空 worker 池。
        if (!state.queue.some(q => q.status === 'pending')) {
            state.isRunning = false;
            updateStats();
            saveQueue();
            renderQueue();
            setStatus(bt('status.batch-finished', '批量下载结束'), 'info');
            updateButtonsState();
            return;
        }
        state.isRunning = true;

        updateStats();
        updateButtonsState();
        saveQueue();
        renderQueue();
        setStatus(
            bt('status.start-download', '开始下载 (并发:{concurrent}, 间隔:{intervalMs}ms)',
                {concurrent: desiredConcurrency(), intervalMs: getIntervalMs()}),
            'info'
        );

        // 并发数与作品间隔实时生效：worker 池按当前目标并发动态伸缩，间隔在每次作品之间实时读取。
        ensureWorkers();
    }
    // 当前目标并发数（最少 1）；下载设置实时同步到 state.settings.concurrent。
    function desiredConcurrency() {
        return Math.max(1, parseInt(state.settings.concurrent, 10) || 1);
    }

    // 将运行中的 worker 数量补足到当前目标并发：用于启动、运行中追加队列、运行中调高并发数。
    // 调低并发数由各 worker 在 workerLoop 顶部自行退出，不在此处理。
    function ensureWorkers() {
        if (!state.isRunning || state.stopRequested) return;
        // 运行中调用同时负责幂等恢复被服务端关闭的聚合 SSE；即使 worker 已满也不能跳过。
        ensureSharedSSE();
        const workersToStart = Math.max(0, desiredConcurrency() - state.activeWorkers);
        if (workersToStart === 0) return;
        // 先为整批 worker 预留计数，再逐个启动。worker 可能在首次 await 前同步退出，
        // 固定启动次数与预留计数共同避免它反复改变循环条件，确保本次补充数量有界。
        state.activeWorkers += workersToStart;
        for (let i = 0; i < workersToStart; i++) {
            workerLoop().finally(handleWorkerExit);
        }
    }

    // 最后一个 worker 退出（队列已抽干）时统一收尾；调低并发数导致的多余 worker 退出不触发收尾。
    function handleWorkerExit() {
        if (!state.isRunning || state.activeWorkers > 0) return;
        finishBatch();
    }

    function finishBatch() {
        closeAllSSE();
        state.isRunning = false;
        saveQueue();
        setStatus(bt('status.batch-finished', '批量下载结束'), 'info');
        updateButtonsState();
        // 多人模式：队列完成后自动打包已下载文件（配额超限时已在 handleQuotaExceeded 中触发打包，不重复）
        if (quotaInfo.enabled) {
            const completed = state.queue.filter(q => q.status === 'completed').length;
            if (completed > 0) autoPackAfterQueue();
        }
    }

    async function workerLoop() {
        try {
            while (state.isRunning && !state.stopRequested) {
                // 并发数实时调低时，多余的 worker 在处理完当前作品后退出（不中断在途下载）。
                if (state.activeWorkers > desiredConcurrency()) break;
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
                    console.error(bt('download.log.process-single-error', '处理单个作品失败'), e);
                } finally {
                    // 作品间隔实时生效：每次作品之间按当前设置读取间隔。
                    await sleep(getIntervalMs());
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
        state.queue[idx].statusMessageKey = null;
        state.queue[idx].startTime = new Date().toISOString();
        saveQueue();
        renderQueue();
        return state.queue[idx];
    }

    // 统一队列引擎：按作品类型（item.kind）多态派发到该类型注册的下载行为。
    // 插画为内置类型（processIllustItem）；小说等由各自插件的行为模块注册（见 batch-queue-types.js）。
    function pauseUnavailableQueueType(item) {
        item.status = 'paused';
        item.statusMessageKey = null;
        item.endTime = null;
        item.lastMessageParts = null;
        item.lastMessage = bt('queue.message.type-unavailable', '该类型当前不可用（其插件已禁用），已暂停');
        updateStats();
        saveQueue();
        renderQueue();
    }

    async function processSingle(item) {
        item.lastMessageParts = null;
        const typeId = item.kind || 'illust';
        const descriptor = window.PixivBatch.queueTypes.get(typeId);
        if (!descriptor) {
            // 该类型当前不可用（如小说插件被禁用、其行为模块未注册）：标记暂停，不报错 / 不删除
            // （与计划任务「来源不可用 → 暂停」同规则），待该类型重新可用后可重试。
            pauseUnavailableQueueType(item);
            return;
        }
        try {
            await descriptor.process(item);
        } catch (error) {
            if (error && error.code === 'STALE_QUEUE_TYPE') {
                pauseUnavailableQueueType(item);
                return;
            }
            throw error;
        }
    }

    // 插画 / 漫画 / 动图下载流程（注册为内置 illust 作品类型的下载行为）。
    async function processIllustItem(item, invocation) {
        assertProcessInvocation(invocation);
        item.lastMessage = bt('queue.message.checking-history', '正在检查历史记录...');
        renderQueue();

        if (state.settings.skipHistory) {
            const downloaded = await checkDownloaded(item.id, invocation);
            assertProcessInvocation(invocation);
            if (downloaded && downloaded.deleted && state.settings.redownloadDeleted) {
                // 软删除记录 + 允许重下：当作未下载继续走正常下载流程（落库后删除标记自动复位）
            } else if (downloaded && downloaded.deleted) {
                item.status = 'skipped';
                item.lastMessage = bt('queue.message.skipped-deleted', '跳过 — 已经下载过，但被删除');
                item.endTime = new Date().toISOString();
                updateStats();
                saveQueue();
                renderQueue();
                return;
            } else if (downloaded) {
                // 若 verifyFiles=true 时是从磁盘恢复出来的裸记录（title 为空），
                // 拉 Pixiv meta 补齐后再跳过，避免画廊里这些恢复出的作品没有标题/作者/简介。
                let recoveredMeta = false;
                if (state.settings.verifyHistoryFiles && !downloaded.title) {
                    item.lastMessage = bt('queue.message.recovering-metadata', '正在补齐已下载作品的元数据...');
                    renderQueue();
                    try {
                        const meta = await getArtworkMeta(item.id, invocation);
                        assertProcessInvocation(invocation);
                        const recovered = await recoverArtworkMetadata(item.id, {
                            title: meta.illustTitle || '',
                            authorId: normalizeAuthorId(meta.authorId ?? meta.userId),
                            authorName: meta.authorName || meta.userName || '',
                            xRestrict: Number(meta.xRestrict ?? meta.xrestrict ?? 0),
                            isAi: meta?.isAi === true || Number(meta?.aiType ?? 0) >= 2,
                            description: meta.description || ''
                        }, invocation);
                        assertProcessInvocation(invocation);
                        if (recovered && recovered.title) {
                            recoveredMeta = true;
                            item.title = recovered.title;
                        }
                    } catch (e) {
                        assertProcessInvocation(invocation);
                        // best-effort：拉 meta 失败不影响跳过
                        console.warn(bt('download.log.skip-recover-meta-failed', '跳过恢复元数据失败: itemId={id}', {id: item.id}), e);
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

        assertProcessInvocation(invocation);
        item.lastMessage = bt('queue.message.fetching-info', '正在获取作品信息...');
        setCurrent(item);
        setStatus(bt('status.fetching-metadata', '获取信息：{id}', {id: item.id}), 'info');
        renderQueue();

        try {
            const meta = await getArtworkMeta(item.id, invocation);
            assertProcessInvocation(invocation);
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
                const ugoira = await getUgoiraMeta(item.id, invocation);
                assertProcessInvocation(invocation);
                ugoiraData = {zipUrl: ugoira.zipUrl, delays: ugoira.delays};
                urls = [ugoira.zipUrl];
                item.totalImages = 1;
            } else {
                urls = await getArtworkPages(item.id, invocation);
                assertProcessInvocation(invocation);
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
                meta.illustType ?? null,
                meta.rawMetaJson || null,
                invocation
            );
            assertProcessInvocation(invocation);
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
            const ssePromise = waitForFinalStatusBySSE(item.id, STATUS_TIMEOUT_MS, invocation);
            item.lastMessage = bt('queue.message.waiting-completion', '下载中，等待完成...');
            renderQueue();

            const final = await ssePromise;
            assertProcessInvocation(invocation);

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
                    const check = await getDownloadStatus(item.id, invocation);
                    assertProcessInvocation(invocation);
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
                } catch (error) {
                    assertProcessInvocation(invocation);
                    item.status = 'failed';
                    item.lastMessage = bt('queue.message.failed-status-error', '失败 — 状态查询异常');
                }
            }
        } catch (e) {
            assertProcessInvocation(invocation);
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
            if (!invocation || invocation.isActive()) {
                item.endTime = item.endTime || new Date().toISOString();
                updateStats();
                saveQueue();
                renderQueue();
                setCurrent(null);
            }
        }
    }
