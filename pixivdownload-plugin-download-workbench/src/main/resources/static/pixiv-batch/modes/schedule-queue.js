'use strict';
    function renderScheduleQueueSection(t) {
        const id = Number(t.id);
        const expanded = scheduleExpandedQueues.has(id);
        const title = escHtml(bt('schedule.queue.title', '本轮队列详情'));
        const bodyHtml = expanded ? renderScheduleQueueBody(id) : '';
        return `
            <div class="schedule-queue" data-task-id="${id}">
                <button type="button" class="schedule-queue-toggle" aria-expanded="${expanded}" data-pixiv-click="toggleScheduleQueue(${id})">
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
            if (!parsed || !Array.isArray(parsed.items)) return null;
            // liveStatus 只属于当前进程/当前 publication 的瞬时投影，绝不能从持久缓存复活。
            parsed.items = scheduleQueueItemsWithoutLiveStatus(parsed.items);
            return parsed;
        } catch (e) {
            return null;
        }
    }

    function writeScheduleQueueCache(id, data) {
        try {
            const persistent = Object.assign({}, data, {
                items: scheduleQueueItemsWithoutLiveStatus(
                    data && Array.isArray(data.items) ? data.items : [])
            });
            storeSet(scheduleQueueCacheKey(id), JSON.stringify(persistent));
        } catch (e) { /* 存储不可用时忽略：内存渲染仍可工作 */ }
    }

    function scheduleQueueItemsWithoutLiveStatus(items) {
        return items.map(item => Object.assign({}, item, {liveStatus: null}));
    }

    // 缓存里随队列一起记录的「该队列所属那一轮运行的完成时刻」（写入时取任务当时的 lastRunTime）。
    // 与任务当前的 lastRunTime 比对即可判断缓存是否已过期：任务又跑过新的一轮（前端没刷到、或后端重启后），
    // 两者就不再一致。无缓存返回 undefined。
    function scheduleQueueCacheRunTime(id) {
        const cache = readScheduleQueueCache(id);
        if (!cache) return undefined;
        return cache.lastRunTime != null ? cache.lastRunTime : null;
    }

    // 缓存队列是否已不属于任务的最新一轮：把缓存记录的运行时刻与任务当前 lastRunTime 比对，不一致即过期。
    // 无缓存时不算过期（fetch 会负责填充）。
    function isScheduleQueueCacheStale(id, task) {
        const cachedRunTime = scheduleQueueCacheRunTime(id);
        if (cachedRunTime === undefined) return false;
        const latestRunTime = task && task.lastRunTime != null ? task.lastRunTime : null;
        return cachedRunTime !== latestRunTime;
    }

    // 丢弃某任务的队列缓存与内存模型：过期时清空，让 renderScheduleQueueBody 即时显示空、
    // 并避免 fetchScheduleQueue 的 keepCache 分支用陈旧队列盖住后端的空响应。
    function discardScheduleQueueCache(id) {
        id = Number(id);
        delete scheduleQueueModels[id];
        try { storeRemove(scheduleQueueCacheKey(id)); } catch (e) { /* 存储不可用时忽略 */ }
    }

    // 计划任务「本轮队列详情」的客户端模型：taskId → 队列项数组（与下载工作区 state.queue 同形，
    // 直接喂给 buildQueueItemHtml 渲染，保证两处队列完全一致）。后端 4s 快照提供权威的发现/终态，
    // SSE 提供运行中的逐图实时进度。
    const scheduleQueueModels = {};
    // 已登记的 SSE 监听器：taskId → { compositeQueueKey: {workId, fn} }，用于精确解绑、
    // 避免同 raw id 的不同 workType 覆盖彼此或误删工作区监听。
    const scheduleSseHandlers = {};
    // 上一轮轮询时仍在运行的展开任务：用于在运行结束的那一拍补拉一次最终终态快照。
    const scheduleQueueWasRunning = new Set();

    // ── 「本轮队列详情」高频刷新合批 ─────────────────────────────────────────────
    // 并发下载时 SSE 逐图进度事件会高频到达。若每个事件都整块重建 .schedule-queue-body 的 innerHTML
    //（含全部队列行），主线程会被反复的 DOM 拆建占满，交互延迟（INP）随之飙高。
    // 改为：SSE 只 patch 内存模型 + 标记脏行 id，再用节流合批，只替换发生变化的单行 outerHTML；
    // 统计栏 / 当前下载项区域用更低频的独立节流刷新。折叠 / 解绑 / 整块重渲染时清理待执行刷新与脏集合。
    const scheduleQueueDirtyRows = new Map();        // taskId → Set<queueId>：待局部刷新的脏行
    const scheduleQueueRowFlushHandles = new Map();  // taskId → setTimeout 句柄：脏行合批刷新
    const scheduleQueueMetaFlushHandles = new Map(); // taskId → setTimeout 句柄：统计/当前项低频刷新
    const SCHEDULE_QUEUE_ROW_FLUSH_MS = 150;         // 脏行批量刷新节流（100-250ms 区间）
    const SCHEDULE_QUEUE_META_FLUSH_MS = 350;        // 统计栏 / 当前下载项低频刷新（250-500ms 区间）

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

    function encodedScheduleQueueIdentityPart(value) {
        const raw = value == null ? '' : String(value);
        let encoded = '';
        for (let index = 0; index < raw.length; index++) {
            encoded += raw.charCodeAt(index).toString(16).padStart(4, '0');
        }
        return encoded;
    }

    function scheduleQueueIdentity(workType, workId) {
        const queueTypes = window.PixivBatch && window.PixivBatch.queueTypes;
        if (queueTypes && typeof queueTypes.queueKey === 'function') {
            return queueTypes.queueKey(workType, workId);
        }
        const type = workType == null ? '' : String(workType).trim();
        return `q:${encodedScheduleQueueIdentityPart(type)}.${encodedScheduleQueueIdentityPart(workId)}`;
    }

    function scheduleQueueItemKey(item) {
        const q = item && typeof item === 'object' ? item : {};
        return scheduleQueueIdentity(
            q.workType != null ? q.workType : q.kind,
            q.workId != null ? q.workId : q.id);
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
                return {status: 'failed', rawStatus: 'failed', failureCode: safeScheduleMachineCode(it.message)};
            default:
                return {status: 'pending', rawStatus: 'pending'};
        }
    }

    // 后端队列项 → 工作区队列项（同 state.queue 形状），供 buildQueueItemHtml 渲染。
    // 注意：title / lastMessage 不在这里写入；只存 rawTitle / rawStatus、校验后的 failureCode 与
    // authoritative failureWorkType，渲染时由 localizeScheduleQueueItem 用当前语言派生 title / lastMessage。
    function scheduleItemToQueue(it, type, task) {
        const mapped = scheduleStatusToQueue(it);
        const workType = String(it.workType == null ? (it.kind == null ? '' : it.kind) : it.workType).trim()
            || 'unknown';
        const workId = String(it.workId == null ? (it.id == null ? '' : it.id) : it.workId);
        const presentation = it.presentation && typeof it.presentation === 'object'
            && !Array.isArray(it.presentation) ? it.presentation : {};
        const presentationAttributes = it.presentationAttributes
            && typeof it.presentationAttributes === 'object' && !Array.isArray(it.presentationAttributes)
            ? it.presentationAttributes
            : (presentation.attributes && typeof presentation.attributes === 'object'
                && !Array.isArray(presentation.attributes) ? presentation.attributes : {});
        const result = it.result && typeof it.result === 'object' && !Array.isArray(it.result)
            ? it.result : {};
        const resultAttributes = it.resultAttributes && typeof it.resultAttributes === 'object'
            && !Array.isArray(it.resultAttributes)
            ? it.resultAttributes
            : (result.attributes && typeof result.attributes === 'object'
                && !Array.isArray(result.attributes) ? result.attributes : {});
        // 在调用 owner hook 前固定 raw 状态；owner 即使就地修改传入 DTO，也不能改写宿主随后展示的状态。
        const rawLiveStatus = it.liveStatus && typeof it.liveStatus === 'object'
            && !Array.isArray(it.liveStatus) ? Object.assign({}, it.liveStatus) : null;
        const registry = window.PixivBatch && window.PixivBatch.queueTypes;
        const base = registry && typeof registry.scheduledQueueItem === 'function'
            ? registry.scheduledQueueItem(workType, it, {
                sourceType: type,
                task: task || null
            })
            : {
                id: workId,
                kind: workType,
                rawTitle: it.title && String(it.title).trim()
                    ? String(it.title)
                    : (presentation.title && String(presentation.title).trim()
                        ? String(presentation.title) : null),
                author: it.author && String(it.author).trim()
                    ? String(it.author)
                    : (presentation.author && String(presentation.author).trim()
                        ? String(presentation.author) : null),
                thumbnailReference: it.thumbnailReference && String(it.thumbnailReference).trim()
                    ? String(it.thumbnailReference)
                    : (presentation.thumbnailReference && String(presentation.thumbnailReference).trim()
                        ? String(presentation.thumbnailReference) : null),
                presentationAttributes: Object.assign({}, presentationAttributes),
                resultAttributes: Object.assign({}, resultAttributes),
                source: 'schedule'
            };
        return Object.assign({}, base, {
            // workType + 原样 String workId 由宿主盖章；owner 映射只能补展示字段，不能改写作品身份。
            id: workId,
            kind: workType,
            workId: workId,
            workType: workType,
            queueKey: scheduleQueueIdentity(workType, workId),
            status: mapped.status,
            rawStatus: mapped.rawStatus,
            failureCode: mapped.failureCode || null,
            failureWorkType: mapped.status === 'failed' ? workType : null,
            totalImages: 0,
            downloadedCount: 0,
            imageProgress: null,
            ugoiraProgress: null,
            // 中性 raw 状态只交给 workType 所有者解释，共享计划模块不识别任何私有阶段。
            liveStatus: rawLiveStatus
        });
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
        const rawStatus = q.status === 'failed' ? 'failed' : q.rawStatus;
        switch (rawStatus) {
            case 'skipped-downloaded':
                lastMessage = bt('schedule.queue.status.skipped-downloaded', '已存在，跳过');
                break;
            case 'skipped-filter':
                lastMessage = bt('schedule.queue.status.skipped-filter', '被筛选条件跳过');
                break;
            case 'failed':
                // failureMessage / lastMessage 仅用于兼容旧 localStorage；它们仍须通过机器码校验和
                // 当前作品类型 manifest 的 owner namespace 翻译，绝不把旧缓存误投到任务来源 namespace。
                lastMessage = localizeScheduleWorkMachineCode(
                    q.failureCode || q.failureMessage || q.lastMessage,
                    q.failureWorkType || q.workType || q.kind)
                    || bt('schedule.queue.status.failed', '失败');
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
        const task = scheduleTaskById(id);
        const prevByKey = new Map();
        prev.forEach(q => { prevByKey.set(scheduleQueueItemKey(q), q); });
        return incoming.map(it => {
            const q = scheduleItemToQueue(it, type, task);
            const old = prevByKey.get(scheduleQueueItemKey(q));
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

    // 计划队列详情的 Vue reactive 岛句柄（batch-queue-vue.js 注册）。缺失 / 未激活 / 挂载失败时回退命令式。
    function scheduleQueueVue() {
        return window.PixivBatch && window.PixivBatch.queueVue;
    }

    // 给 Vue 岛提供「读当前任务本轮队列快照」的闭包：派生与命令式 renderScheduleQueueBody 完全同口径的
    // 状态 / 统计 / 当前卡 / 列表（模型仍为 raw 字段，此处经 localizeScheduleQueueItem 按当前语言派生），
    // 由 Vue 组件用共享的 buildQueueItemHtml / formatCurrentCardHtml 渲染（与普通队列不分叉）。
    function scheduleQueueVueContext(id, body) {
        id = Number(id);
        return {
            bodyEl: body,
            read: function () {
                const model = getScheduleQueueModel(id) || [];
                const localized = model.map(localizeScheduleQueueItem);
                const current = localized.find(q => q.status === 'downloading') || null;
                const s = computeScheduleQueueStats(model);
                return {
                    statusText: buildScheduleQueueStatusText(id, model),
                    statsText: formatStatsText(s.pending, s.success, s.failed, s.active, s.skipped),
                    current: current,
                    items: localized
                };
            }
        };
    }

    function renderScheduleQueueBodyInto(id) {
        if (!scheduleExpandedQueues.has(Number(id))) return;
        const wrap = document.querySelector(`.schedule-queue[data-task-id="${Number(id)}"]`);
        if (!wrap) return;
        const body = wrap.querySelector('.schedule-queue-body');
        if (!body) return;
        const qv = scheduleQueueVue();
        if (qv && qv.ensureScheduleQueue(Number(id), scheduleQueueVueContext(id, body))) {
            // Vue 已接管该 body：合并一次 reactive 同步（Vue 据 :key + v-html 仅 patch 变化，不整块重建 .schedule-queue-body）。
            qv.syncScheduleQueue(Number(id));
            cancelScheduleQueueFlush(id); // 整体已交给 reactive：丢弃命令式脏行 / 低频刷新
            return;
        }
        // —— 命令式回退（Vue 不可用 / 尚未挂载完成 / 挂载失败）——
        // 保留滚动位置：SSE / 快照刷新会替换正文 innerHTML，不保留则滚动条每次跳回顶部。
        const prevList = body.querySelector('.schedule-queue-list');
        const prevScroll = prevList ? prevList.scrollTop : 0;
        body.innerHTML = renderScheduleQueueBody(id);
        const newList = body.querySelector('.schedule-queue-list');
        if (newList) newList.scrollTop = prevScroll;
        // 整块已重渲染（含状态/统计/当前项/全部行）：丢弃此前累积的脏行与待执行的局部/低频刷新，避免重复刷新。
        cancelScheduleQueueFlush(id);
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
            cancelScheduleQueueFlush(id); // 折叠：取消待执行的局部刷新，隐藏视图不再消耗主线程
            const qvCollapse = scheduleQueueVue();
            if (qvCollapse) qvCollapse.unmountScheduleQueue(id); // 卸载 reactive 岛：再展开时命令式首屏 + 重挂
            if (body) body.hidden = true;
            if (toggleBtn) toggleBtn.setAttribute('aria-expanded', 'false');
            if (caret) caret.textContent = '▸';
            return;
        }
        scheduleExpandedQueues.add(id);
        // 展开即比对：缓存队列若不属于任务最新一轮（任务又跑过新的一轮、前端没刷到，或后端重启丢失内存），
        // 先清掉过期缓存再渲染 —— 这样立即显示空、随后 fetch；后端若已无该轮队列则保持空，不再用旧队列盖住。
        if (isScheduleQueueCacheStale(id, scheduleTaskById(id))) {
            discardScheduleQueueCache(id);
        }
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
            const running = ['RUNNING', 'QUEUED', 'CANCEL_REQUESTED'].includes(t.runState);
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
                const model = mergeScheduleQueueModel(id, incoming,
                    task ? (task.sourceType || task.type) : null);
                scheduleQueueModels[id] = model;
                writeScheduleQueueCache(id, {
                    startedTime: data.startedTime != null ? data.startedTime : null,
                    // 记录该队列所属那一轮的运行时刻（任务当前 lastRunTime），供下次展开时比对是否过期。
                    lastRunTime: task && task.lastRunTime != null ? task.lastRunTime : null,
                    truncated: !!data.truncated,
                    total: typeof data.total === 'number' ? data.total : model.length,
                    items: model,
                    savedAt: Date.now()
                });
            }
            renderScheduleQueueBodyInto(id);
            // 运行中 + 展开：订阅 SSE 逐图实时进度；否则解绑。
            if (scheduleExpandedQueues.has(id) && task
                && ['RUNNING', 'QUEUED', 'CANCEL_REQUESTED'].includes(task.runState)) {
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

    // 状态栏文案（对应工作区 #status-bar）：任务运行状态文案 + 本轮开始时间 + 截断提示。
    // 抽成独立函数，让整块渲染与低频 meta 刷新（refreshScheduleQueueMeta）共用一处口径。
    function buildScheduleQueueStatusText(id, model) {
        const task = scheduleTaskById(id);
        const meta = getScheduleQueueMeta(id);
        let statusText = task ? scheduleStatusLight(task).text : bt('schedule.light.never', '等待首次运行');
        if (meta.startedTime) {
            statusText += ' · ' + bt('schedule.queue.started', '本轮开始：{time}', {time: fmtScheduleTime(meta.startedTime)});
        }
        if (meta.truncated) {
            statusText += ' · ' + bt('schedule.queue.truncated', '作品过多，仅记录并展示前 {count} 项', {count: model.length});
        }
        return statusText;
    }

    // 完整照搬下载工作区底部的「状态栏 + 统计栏 + 当前下载卡 + 下载队列」四段结构，
    // 仅把数据源换成本任务的队列模型；各段分别复用 #status-bar / #stats-bar / #current-card / #queue-list 的样式与格式化函数。
    function renderScheduleQueueBody(id) {
        const model = getScheduleQueueModel(id) || [];
        const statusText = buildScheduleQueueStatusText(id, model);
        const s = computeScheduleQueueStats(model);
        // 渲染前用 localizeScheduleQueueItem 派生 title / lastMessage，确保跟随当前 UI 语言；
        // 模型本身仍是 raw 字段，下次语言切换重渲染再次派生即可。
        const localized = model.map(localizeScheduleQueueItem);
        const current = localized.find(q => q.status === 'downloading') || null;

        const statusLine = `<div class="schedule-queue-status">${escHtml(statusText)}</div>`;
        const statsLine = `<div class="schedule-queue-stats">${escHtml(formatStatsText(s.pending, s.success, s.failed, s.active, s.skipped))}</div>`;
        const currentCard = `<div class="schedule-queue-current">${formatCurrentCardHtml(current)}</div>`;
        // 每行带上宿主盖章的复合 data-queue-key，供 flushScheduleQueueRows 局部替换单行 outerHTML 时定位。
        const listInner = localized.length
            ? localized.map(q => buildQueueItemHtml(q, {
                removable: false,
                queueKey: scheduleQueueItemKey(q)
            })).join('')
            : `<div class="queue-empty">${escHtml(bt('status.queue-empty', '队列为空'))}</div>`;
        const listCard = `<div class="schedule-queue-list">${listInner}</div>`;
        return statusLine + statsLine + currentCard + listCard;
    }

    // 标记某任务的某行待刷新：只 patch 完模型后调用，合批后由 flushScheduleQueueRows 局部替换该行。
    function markScheduleQueueRowDirty(id, queueKey) {
        id = Number(id);
        if (!scheduleExpandedQueues.has(id)) return; // 已折叠：无可见 DOM，丢弃
        let set = scheduleQueueDirtyRows.get(id);
        if (!set) { set = new Set(); scheduleQueueDirtyRows.set(id, set); }
        set.add(String(queueKey));
        if (!scheduleQueueRowFlushHandles.has(id)) {
            scheduleQueueRowFlushHandles.set(id,
                setTimeout(() => flushScheduleQueueRows(id), SCHEDULE_QUEUE_ROW_FLUSH_MS));
        }
        armScheduleQueueMetaFlush(id);
    }

    // 安排一次低频的统计栏 / 当前下载项刷新（已排程则复用，避免每行都重算整块统计/当前卡）。
    function armScheduleQueueMetaFlush(id) {
        id = Number(id);
        if (scheduleQueueMetaFlushHandles.has(id)) return;
        scheduleQueueMetaFlushHandles.set(id, setTimeout(() => {
            scheduleQueueMetaFlushHandles.delete(id);
            refreshScheduleQueueMeta(id);
        }, SCHEDULE_QUEUE_META_FLUSH_MS));
    }

    // 合批刷新脏行：只对发生变化的行重新生成单行 HTML 并替换其 outerHTML；
    // 找不到对应 DOM 行（如刚展开还没渲染过 list / 行被快照重建移除）时退化为一次整块渲染。
    function flushScheduleQueueRows(id) {
        id = Number(id);
        scheduleQueueRowFlushHandles.delete(id);
        const dirty = scheduleQueueDirtyRows.get(id);
        scheduleQueueDirtyRows.delete(id);
        const qv = scheduleQueueVue();
        if (qv && qv.isScheduleActive(id)) {
            // Vue 已接管：脏行 patch 交给 reactive 同步（合并到一帧、仅变化的行重渲染，不整块重建 .schedule-queue-body）。
            qv.syncScheduleQueue(id);
            return;
        }
        if (!dirty || dirty.size === 0) return;
        if (!scheduleExpandedQueues.has(id)) return;
        const wrap = document.querySelector(`.schedule-queue[data-task-id="${id}"]`);
        const listEl = wrap ? wrap.querySelector('.schedule-queue-list') : null;
        const model = scheduleQueueModels[id];
        if (!wrap || !listEl || !model) {
            renderScheduleQueueBodyInto(id); // 列表尚未渲染或模型缺失：整块兜底（频率低，可接受）
            return;
        }
        const byKey = new Map();
        model.forEach(q => { byKey.set(scheduleQueueItemKey(q), q); });
        let needFull = false;
        dirty.forEach(queueKey => {
            const q = byKey.get(queueKey);
            if (!q) return; // 模型里已无此项（被快照重建移除）：留给后续整块渲染
            const row = Array.from(listEl.querySelectorAll('.queue-item[data-queue-key]'))
                .find(candidate => candidate.getAttribute('data-queue-key') === queueKey);
            if (!row) { needFull = true; return; }
            row.outerHTML = buildQueueItemHtml(localizeScheduleQueueItem(q), {
                removable: false,
                queueKey: scheduleQueueItemKey(q)
            });
        });
        if (needFull) {
            renderScheduleQueueBodyInto(id);
        } else {
            armScheduleQueueMetaFlush(id); // 行已就地更新；统计 / 当前下载项交给低频 meta 刷新
        }
    }

    // 低频刷新统计栏 + 当前下载项 + 状态栏（不触碰队列列表 DOM，避免整块重建）。
    function refreshScheduleQueueMeta(id) {
        id = Number(id);
        if (!scheduleExpandedQueues.has(id)) return;
        const qv = scheduleQueueVue();
        if (qv && qv.isScheduleActive(id)) {
            qv.syncScheduleQueue(id); // 状态 / 统计 / 当前卡随整份 reactive 同步一并更新
            return;
        }
        const wrap = document.querySelector(`.schedule-queue[data-task-id="${id}"]`);
        if (!wrap) return;
        const body = wrap.querySelector('.schedule-queue-body');
        if (!body || body.hidden) return;
        const model = getScheduleQueueModel(id) || [];
        const statusEl = body.querySelector('.schedule-queue-status');
        if (statusEl) statusEl.textContent = buildScheduleQueueStatusText(id, model);
        const statsEl = body.querySelector('.schedule-queue-stats');
        if (statsEl) {
            const s = computeScheduleQueueStats(model);
            statsEl.textContent = formatStatsText(s.pending, s.success, s.failed, s.active, s.skipped);
        }
        const currentEl = body.querySelector('.schedule-queue-current');
        if (currentEl) {
            const cur = model.find(q => q.status === 'downloading');
            currentEl.innerHTML = formatCurrentCardHtml(cur ? localizeScheduleQueueItem(cur) : null);
        }
    }

    // 取消某任务待执行的局部刷新并清空脏集合：折叠 / 解绑 SSE / 整块重渲染 / 任务下线时调用，
    // 避免隐藏视图或陈旧任务继续占用主线程做无意义的刷新。
    function cancelScheduleQueueFlush(id) {
        id = Number(id);
        const rowHandle = scheduleQueueRowFlushHandles.get(id);
        if (rowHandle != null) { clearTimeout(rowHandle); scheduleQueueRowFlushHandles.delete(id); }
        const metaHandle = scheduleQueueMetaFlushHandles.get(id);
        if (metaHandle != null) { clearTimeout(metaHandle); scheduleQueueMetaFlushHandles.delete(id); }
        scheduleQueueDirtyRows.delete(id);
    }

    // ── 计划任务队列的 SSE 实时进度同步（复用工作区的聚合 EventSource） ──────────────────────────
    // 管理员的聚合 SSE 会收到全部下载进度事件（含计划任务后台下载，userUuid=null）。运行中展开队列时，
    // 为每个插画项按 artworkId 注册监听，把逐图进度并入模型并重渲染；折叠 / 运行结束即解绑。
    function subscribeScheduleQueueSse(id) {
        id = Number(id);
        const model = scheduleQueueModels[id];
        if (!model) return;
        // 快照可能新增/移除同 raw id 的类型。每次据权威模型完整重建监听，确保既有回调也拿到
        // 最新的歧义集合，不能让先注册的单类型回调在后来出现同 id 跨类型时继续误收旧事件。
        unsubscribeScheduleQueueSse(id);
        ensureSharedSSE();
        scheduleSseHandlers[id] = Object.create(null);
        const handlers = scheduleSseHandlers[id];
        const queueTypes = window.PixivBatch && window.PixivBatch.queueTypes;
        const eligible = model.filter(q => {
            if (queueTypes && typeof queueTypes.supportsScheduledSse === 'function'
                && !queueTypes.supportsScheduledSse(
                    q.workType != null ? q.workType : q.kind)) return false;
            return true;
        });
        const identitiesByWorkId = new Map();
        eligible.forEach(q => {
            const workId = String(q.workId != null ? q.workId : q.id);
            const queueKey = scheduleQueueItemKey(q);
            let identities = identitiesByWorkId.get(workId);
            if (!identities) {
                identities = new Set();
                identitiesByWorkId.set(workId, identities);
            }
            identities.add(queueKey);
        });
        eligible.forEach(q => {
            const workId = String(q.workId != null ? q.workId : q.id);
            const queueKey = scheduleQueueItemKey(q);
            if (handlers[queueKey]) return; // 已注册
            const fn = data => {
                const eventWorkType = data && data.workType != null
                    ? String(data.workType).trim() : '';
                const eventQueueKey = eventWorkType
                    ? scheduleQueueIdentity(eventWorkType, workId) : null;
                // 旧聚合事件没有 workType：仅当该 raw id 唯一时兼容路由；一旦同 id 跨类型，
                // 必须等事件携带 workType 才能更新，宁可等待下一次快照也不能串写另一类型。
                if (eventQueueKey ? eventQueueKey !== queueKey
                    : identitiesByWorkId.get(workId).size > 1) return;
                applyScheduleQueueSse(id, queueKey, data);
            };
            handlers[queueKey] = {workId, fn};
            addSSEListener(workId, fn);
        });
    }

    function unsubscribeScheduleQueueSse(id) {
        id = Number(id);
        // 不再有逐图事件进来：取消待执行的局部 / 低频刷新合批，避免遗留定时器空转。
        cancelScheduleQueueFlush(id);
        const handlers = scheduleSseHandlers[id];
        if (!handlers) return;
        Object.keys(handlers).forEach(queueKey => {
            const handler = handlers[queueKey];
            if (handler) removeSSEListener(handler.workId, handler.fn);
        });
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
        const qvRelease = scheduleQueueVue();
        stale.forEach(id => {
            unsubscribeScheduleQueueSse(id);
            if (qvRelease) qvRelease.unmountScheduleQueue(id); // 任务下线：卸载其 reactive 岛
            scheduleExpandedQueues.delete(id);
            delete scheduleQueueModels[id];
            scheduleQueueWasRunning.delete(id);
            try { storeRemove(scheduleQueueCacheKey(id)); } catch (e) { /* 存储不可用：忽略 */ }
        });
    }

    function applyScheduleQueueSse(id, queueKey, data) {
        id = Number(id);
        const model = scheduleQueueModels[id];
        if (!model || !data) return;
        const q = model.find(item => scheduleQueueItemKey(item) === queueKey);
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
        // 只 patch 模型 + 标记脏行：不在每个事件里整块重建 DOM。合批后只替换变化的单行，
        // 统计 / 当前下载项由更低频的 meta 刷新处理，使高频进度事件不再阻塞主线程。
        markScheduleQueueRowDirty(id, queueKey);
    }
