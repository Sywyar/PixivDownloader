'use strict';
    function queueVue() {
        return window.PixivBatch && window.PixivBatch.queueVue;
    }
    function downloadQueueVueActive(kind) {
        const qv = queueVue();
        return !!(qv && qv.isDownloadActive(kind));
    }

    // 队列计数门面：始终重算 state.stats 并维护 sr-only #stats-bar（读屏 / 回归保留）。
    // 仪表盘 5 张统计卡：Vue 岛激活时合并进 reactive store（与速度卡同 store），否则命令式逐项写入数字。
    function updateStats() {
        state.stats.success = state.stats.failed = state.stats.active = state.stats.skipped = 0;
        let pending = 0;
        for (const item of state.queue) {
            switch (item.status) {
                case 'completed': state.stats.success++; break;
                case 'failed': state.stats.failed++; break;
                case 'downloading': state.stats.active++; break;
                case 'skipped': state.stats.skipped++; break;
                case 'idle':
                case 'pending':
                case 'paused': pending++; break;
            }
        }
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
        if (downloadQueueVueActive('stats')) {
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
        if (downloadQueueVueActive('stats')) {
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
        // 当前下载卡现由队列派生（队首未完成项 + 剩余计数），不再跟踪单一 currentItemId 触发整卡重建；
        // item 参数仅为兼容既有调用点（processArtworkItem / processNovelItem / SSE 进度事件）与 currentItemId 语义。
        state.currentItemId = item ? String(item.id) : null;
        if (!queueProgressRows.size) refreshCurrentCard(item);
    }

    // 当前卡按队列顺序展示最前面的未完成项，避免并发进度事件改变卡片归属。
    // Vue 从队列镜像和暂停状态派生单卡；挂载失败时由 refreshCurrentCard 命令式刷新。
    // formatCurrentCardHtml 也供计划任务详情使用，因此剩余计数行在这里单独附加。

    // 队首未完成项 = 队列镜像中第一个 status 属于 downloading/pending/paused 的项（completed/failed/skipped/idle
    // 视为已结束）。含 pending/paused 是为了避免并发=1 时「上一项完成 → 下一项被认领」的间隙短暂闪「无」：该间隙
    // 里没有 downloading 项，但有 pending 项，直接显示它（直接替换，不回归「无」）。暂停（停止接受新任务）期间
    // 仍展示正在收尾下载的作品（drain）：有 downloading 项时照常展示队首；仅当没有任何 downloading 项时才
    // 回退 idle「无」。queue 参数传 reactive 队列镜像（Vue 路径）或 state.queue（命令式路径），两路共用同一口径。
    function currentFrontItem(queue, isPaused) {
        for (let i = 0; i < queue.length; i++) {
            const s = queue[i].status;
            if (s === 'downloading') return queue[i];
        }
        if (isPaused) return null;
        for (let i = 0; i < queue.length; i++) {
            const s = queue[i].status;
            if (s === 'pending' || s === 'paused') return queue[i];
        }
        return null;
    }

    // 剩余计数原始数据：{downloading, queued}。展示的队首不计入「还有」；文案渲染期经 bt 派生（模型不 bake 翻译）。
    function currentRemainingCounts(queue, front) {
        let downloading = 0, queued = 0;
        for (let i = 0; i < queue.length; i++) {
            const s = queue[i].status;
            if (s === 'downloading') downloading++;
            else if (s === 'pending' || s === 'paused') queued++;
        }
        if (front) {
            if (front.status === 'downloading') downloading--;
            else queued--;
        }
        return {downloading, queued};
    }

    // 剩余计数文案按渲染时的语言派生；两项都为 0 时不输出。
    function currentRemainingLineText(downloading, queued) {
        let text;
        if (downloading > 0 && queued > 0) {
            text = bt('status.current-remaining.both', '还有 {downloading} 个正在下载、{queued} 个排队中…',
                {downloading: downloading, queued: queued});
        } else if (downloading > 0) {
            text = bt('status.current-remaining.downloading', '还有 {count} 个正在下载…', {count: downloading});
        } else if (queued > 0) {
            text = bt('status.current-remaining.queued', '还有 {count} 个排队中…', {count: queued});
        } else {
            return '';
        }
        return text;
    }

    function buildCurrentRemainingLineHtml(downloading, queued) {
        const text = currentRemainingLineText(downloading, queued);
        return text ? '<div class="current-remaining">' + esc(text) + '</div>' : '';
    }

    // 当前卡完整 HTML：队首未完成项的进度卡 + 剩余计数行；无未完成项（含暂停）时回退 idle「无」。
    function computeCurrentCardHtml(queue, isPaused) {
        const front = currentFrontItem(queue, isPaused);
        if (!front) {
            return '<strong>' + esc(bt('label.current', '当前下载:')) + '</strong> '
                + esc(bt('status.current-idle', '无'));
        }
        const counts = currentRemainingCounts(queue, front);
        return formatCurrentCardHtml(front)
            + buildCurrentRemainingLineHtml(counts.downloading, counts.queued);
    }

    // 当前卡刷新门面：Vue 已接管**且当前卡已由 Vue 渲染**（isDownloadCurrentActive）→ 同步最新队列镜像 +
    // 暂停标志（当前卡内容由响应式从镜像派生，Vue 只 patch 单卡）；否则命令式重建整卡。每次刷新都同步队列镜像，
    // 保证任意进度 / 状态事件（setCurrent / renderCurrent / pause / resume）都让当前卡实时重算，不依赖外部是否
    // 另调 renderQueue；镜像同步与 renderQueue 的列表同步同 key 合批去重。按当前卡挂载点单独判定，避免「统计 /
    // 列表岛激活但当前卡挂载失败」时当前卡永久停留在初始「无」。两条路径共用队首与剩余计数的派生口径。
    function refreshCurrentCard(changedItem) {
        if (downloadQueueVueActive() && queueVue().isDownloadCurrentActive()) {
            queueVue().syncDownloadList(changedItem);
            queueVue().syncDownloadPaused(state.isPaused);
            return;
        }
        const el = document.getElementById('current-card');
        if (el) el.innerHTML = computeCurrentCardHtml(state.queue, state.isPaused);
    }

    // 进度按帧合并且只替换变动行；结构或状态变化仍走完整同步并更新管理员打包按钮。
    const queueProgressRows = new Set();
    let queueProgressScheduled = false;

    function renderQueue(changedItem, statusChanged = false) {
        if (!changedItem || statusChanged) renderQueueRecovery();
        if (changedItem && !downloadQueueVueActive('list')) {
            if (statusChanged) updateAdminPackButton();
            queueProgressRows.add(changedItem);
            if (!queueProgressScheduled) {
                queueProgressScheduled = true;
                const schedule = typeof requestAnimationFrame === 'function' ? requestAnimationFrame : cb => setTimeout(cb, 16);
                schedule(() => {
                    queueProgressScheduled = false;
                    if (!queueProgressRows.size) return;
                    const rows = Array.from(queueProgressRows);
                    queueProgressRows.clear();
                    const list = document.getElementById('queue-list');
                    if (!downloadQueueVueActive('list') && list && list.children.length === state.queue.length) {
                        rows.forEach(item => {
                            const index = state.queue.indexOf(item);
                            if (index >= 0) list.children[index].outerHTML = buildQueueItemHtml(item, {removable: true});
                        });
                        refreshCurrentCard();
                    } else {
                        renderQueue();
                    }
                });
            }
            return;
        }
        queueProgressRows.clear();
        // 当前下载卡由 state.queue 派生：随队列每次变化一并刷新（Vue 接管后只合批同步 store，命令式回退时重建单卡）。
        refreshCurrentCard(changedItem);
        if (downloadQueueVueActive('list')) {
            queueVue().syncDownloadList(changedItem);
        } else {
            renderQueueImperative();
        }
        if (!changedItem || statusChanged) updateAdminPackButton();
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
            const cancelButton = target && typeof target.closest === 'function'
                ? target.closest('[data-queue-cancel-id]') : null;
            if (cancelButton) {
                if (event && typeof event.preventDefault === 'function') event.preventDefault();
                if (event && typeof event.stopPropagation === 'function') event.stopPropagation();
                requestQueueItemCancel(cancelButton.getAttribute('data-queue-cancel-id'));
                return;
            }
            const removeButton = target && typeof target.closest === 'function'
                ? target.closest('[data-queue-remove-id]') : null;
            if (!removeButton) return;
            if (event && typeof event.preventDefault === 'function') event.preventDefault();
            if (event && typeof event.stopPropagation === 'function') event.stopPropagation();
            removeFromQueue(removeButton.getAttribute('data-queue-remove-id'));
        });
        queueActionRoots.add(root);
        return true;
    }

    // 单个队列项的 HTML。下载工作区底部的「下载队列」与计划任务卡片底部的「本轮队列详情」共用此函数，
    // 保证两处队列展示完全一致（进度条、数据来源/模式/分级/插件标签、小说进度等）。
    // opts.removable=false 时不渲染移除按钮（计划任务为服务端队列，前端不可移除）。
    // opts.queueKey 给行根节点打一个宿主编码的复合 data-queue-key，供「只替换单行 outerHTML」的局部刷新定位该行
    //（计划任务详情高频 SSE 刷新用，避免整块 innerHTML 重建）；不传则不输出该属性，普通队列调用不受影响。
    function buildQueueItemHtml(q, opts) {
        const model = queueItemModel(q, opts);
        const queueKeyAttr = opts && opts.queueKey != null
            ? ` data-queue-key="${esc(String(opts.queueKey))}"`
            : '';
        const prog = q.totalImages > 0
            ? `<div class="prog-wrap">
          <div class="prog-label"><span>${esc(formatImageProgressText(q.downloadedCount || 0, q.totalImages))}</span><span>${pct(q)}%</span></div>
         <div class="prog-bg"><div class="prog-fill" style="width:${pct(q)}%;background:${statusColor(q.status)}"></div></div>
         </div>` : '';
        const detailProg = formatImageDownloadProgressHtml(q.imageProgress, q.status)
            + formatUgoiraProgressHtml(q.ugoiraProgress, q.status)
            + formatNovelProgressHtml(q)
            + formatQueueLiveStatusHtml(q);
        const tags = model.tags.map(tag => `<span class="${tag.className}"${tag.sourceId != null ? ` data-source-id="${esc(tag.sourceId)}"` : ''}${tag.pluginId != null ? ` data-queue-tag-id="${esc(tag.pluginId)}"` : ''}>${esc(tag.label)}</span>`).join('');
        const descHtml = model.message.map(part => `<span style="color:${part.color};font-weight:bold;">${esc(part.text)}</span>`).join('');
        const cancelBtn = model.canCancel
            ? `<button type="button" class="queue-cancel-btn" data-queue-cancel-id="${esc(String(q.id))}" title="${esc(model.cancelLabel)}" aria-label="${esc(model.cancelLabel)}">■</button>` : '';
        const removeBtn = model.canRemove
            ? `<button type="button" class="queue-remove-btn" data-queue-remove-id="${esc(String(q.id))}" title="${esc(model.removeLabel)}">✕</button>` : '';
        const linkBtn = model.linkHref
            ? `<a href="${esc(model.linkHref)}" target="_blank" class="queue-source-link" data-pixiv-click="noop()" data-pixiv-stop="true" title="${esc(model.linkLabel)}">🔗</a>` : '';
        return `<div class="queue-item"${queueKeyAttr} style="border-left-color:${model.color}">
      <div class="q-title">
        <span class="q-title-main">${esc(model.title)}</span>
        ${linkBtn}${cancelBtn}${removeBtn}
      </div>
      <div class="q-tags">${tags}</div>
      <div class="q-meta">ID: ${esc(model.displayId)} | ${descHtml}</div>
      ${prog}
      ${detailProg}
    </div>`;
    }

    // HTML 与 Vue 共用当次展示数据；不把翻译或插件贡献缓存到队列业务项。
    function queueItemModel(q, opts) {
        const removable = !opts || opts.removable !== false;
        const desc = q.statusMessageKey
            ? bt(q.statusMessageKey, q.lastMessage || queueStatusText(q.status))
            : (q.lastMessage || queueStatusText(q.status));
        const sourceDescriptor = queueDataSource(q);
        const acquisitionMode = queueAcquisitionMode(q.source);
        const modeClass = acquisitionMode === 'single-import' ? 'import' : acquisitionMode;
        const xRestrict = q.xRestrict == null ? null : Number(q.xRestrict);
        const rating = xRestrict === 2
            ? {id: 'r18g', label: 'R-18G'}
            : xRestrict === 1
                ? {id: 'r18', label: 'R-18'}
                : xRestrict === null || !Number.isFinite(xRestrict)
                    ? {id: 'unknown', label: bt('queue.unknown', '未知')}
                    : {id: 'sfw', label: 'SFW'};
        const tags = [
            {className: 'queue-tag queue-tag--source', sourceId: sourceDescriptor ? sourceDescriptor.id : 'unknown', label: queueDataSourceText(sourceDescriptor)},
            {className: 'queue-tag queue-tag--mode queue-tag--mode-' + modeClass, label: queueSourceText(q.source)},
            {className: 'queue-tag queue-tag--rating queue-tag--rating-' + rating.id, label: rating.label}
        ];
        const queueTypes = window.PixivBatch && window.PixivBatch.queueTypes;
        const contributedTags = queueTypes && typeof queueTypes.queueTags === 'function'
            ? queueTypes.queueTags(q) : [];
        for (const tag of Array.isArray(contributedTags) ? contributedTags : []) {
            tags.push({className: 'queue-tag queue-tag--plugin', pluginId: tag.id, label: tag.label});
        }
        const canRemove = removable && q.status !== 'downloading';
        const canCancel = removable && canCancelQueueItem(q);
        const isNovel = q.kind === 'novel';
        const novelDisplayId = q.novelId != null ? String(q.novelId) : String(q.id).replace(/^n/, '');
        const displayId = isNovel ? `${novelDisplayId} (Novel)` : String(q.id == null ? '' : q.id);
        const linkHref = queueItemCanonicalUrl(q) || (isNovel
            ? `https://www.pixiv.net/novel/show.php?id=${encodeURIComponent(novelDisplayId)}`
            : '');
        return {
            title: queueItemDisplayTitle(q), color: statusColor(q.status), displayId, linkHref,
            tags, message: queueMessageModel(q, desc), canRemove, canCancel,
            cancelLabel: canCancel ? bt('queue.cancel', '取消下载') : '',
            removeLabel: canRemove ? bt('queue.remove', '移除') : '',
            linkLabel: linkHref ? bt('queue.open-artwork', '打开作品页面') : ''
        };
    }

    function pct(q) {
        if (!q.totalImages) return 0;
        return Math.min(100, Math.round((q.downloadedCount || 0) / q.totalImages * 100));
    }

    function statusColor(s) {
        return {
            completed: 'var(--brand)', downloading: 'var(--primary)', failed: 'var(--danger-bg)',
            paused: 'var(--status-muted)', skipped: 'var(--orange)'
        }[s] || 'var(--line-strong)';
    }

    /* ============================================================
       持久化
    ============================================================ */
