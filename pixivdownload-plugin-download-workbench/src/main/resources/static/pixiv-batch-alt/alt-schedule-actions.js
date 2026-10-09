'use strict';

/* ============================================================
   任务动词
   ============================================================ */
async function scheduleHttpError(response, fallbackKey, fallback) {
    const payload = await response.json().catch(() => ({}));
    return new Error(payload.error || payload.message
        || bt(fallbackKey || 'common.request-failed', fallback || '请求失败'));
}

async function schedulePost(task, verb, body) {
    const res = await fetch(`${BASE}/api/schedule/tasks/${task.id}/${verb}`, {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        credentials: 'same-origin',
        body: body ? JSON.stringify(body) : undefined
    });
    if (!res.ok) throw await scheduleHttpError(res);
    return res.json().catch(() => ({}));
}

async function scheduleCredentialValue(sourceType) {
    const runtime = altScheduleSources();
    if (!runtime) throw new Error(bt('schedule.error.source-editor-unavailable', '计划任务来源编辑器当前不可用'));
    return runtime.invokeCredentialAction(sourceType, 'savedCookie', [], {});
}

async function validateScheduleCredential(sourceType, credential, task) {
    const runtime = altScheduleSources();
    if (!runtime) throw new Error(bt('schedule.error.source-editor-unavailable', '计划任务来源编辑器当前不可用'));
    const error = await runtime.invokeCredentialAction(
        sourceType, 'validateCookie', [credential], {task: task || null});
    if (error) throw new Error(String(error));
}

async function bindScheduleCredential(taskId, sourceType, credential) {
    const runtime = altScheduleSources();
    if (!runtime) throw new Error(bt('schedule.error.source-editor-unavailable', '计划任务来源编辑器当前不可用'));
    const lease = runtime.activationLease(sourceType);
    const res = await fetch(`${BASE}/api/schedule/tasks/${taskId}/authorize-cookie`, {
        method: 'POST',
        credentials: 'same-origin',
        headers: {
            'Content-Type': 'application/json',
            'X-Acquisition-Credential': credential
        },
        signal: lease.signal,
        body: JSON.stringify({activationToken: lease.activationToken})
    });
    lease.assertCurrent();
    if (!res.ok) {
        const payload = await res.json().catch(() => ({}));
        throw new Error(payload.error || payload.message || bt('schedule.error.authorize', '授权失败'));
    }
}

async function scheduleVerb(task, verb) {
    try {
        if (verb === 'resume' && !await resolveSchedulePathActions(task)) return;
        await schedulePost(task, verb);
        abToast('success', bt('schedule.feedback.saved', '操作成功'));
    } catch (e) {
        abToast('error', String(e && e.message || bt('schedule.feedback.failed', '操作失败')));
    }
    await loadScheduleTasks(true);
}

async function scheduleSetEnabled(task, enabled) {
    let saved = false;
    try {
        await schedulePost(task, 'enabled?enabled=' + encodeURIComponent(enabled));
        saved = true;
    } catch (e) {
        abToast('error', String(e && e.message || bt('schedule.feedback.failed', '操作失败')));
    }
    await loadScheduleTasks(true);
    return saved;
}

async function deleteScheduleTask(task) {
    if (!await abConfirm('schedule.delete.confirm', '确认删除计划任务「{name}」？',
        {name: task.name || task.id}, {danger: true})) return;
    try {
        const res = await fetch(`${BASE}/api/schedule/tasks/${task.id}`, {
            method: 'DELETE',
            credentials: 'same-origin'
        });
        if (!res.ok) throw await scheduleHttpError(res);
    } catch (e) {
        abToast('error', String(e && e.message || bt('schedule.feedback.failed', '操作失败')));
    }
    // 任务下线：卸载其详情岛（列表重建后不再有该 box）。
    const vueDelete = scheduleQueueVue();
    if (vueDelete && typeof vueDelete.unmountScheduleQueue === 'function') {
        vueDelete.unmountScheduleQueue(task.id);
    }
    loadScheduleTasks(true);
}

/* ============================================================
   本轮队列详情
   ============================================================ */
const altScheduleQueues = new Map();

function releaseScheduleQueue(id) {
    const entry = altScheduleQueues.get(id);
    if (!entry) return;
    entry.sequence++;
    entry.request?.abort();
    entry.request = null;
    for (const {workId, listener} of entry.listeners || []) removeSSEListener(workId, listener);
    entry.listeners = [];
    if (entry.frame != null) cancelAnimationFrame(entry.frame);
    entry.frame = null;
}

function releaseAllScheduleQueues() {
    altScheduleQueues.forEach((_entry, id) => releaseScheduleQueue(id));
}

function startScheduleQueuePolling() {
    if (scheduleQueuePollTimer) clearInterval(scheduleQueuePollTimer);
    scheduleQueuePollTimer = null;
    altScheduleQueues.forEach((_entry, id) => {
        if (!schedulePageVisible() || !scheduleState.expandedQueues.has(id)
                || !document.getElementById('abScheduleQueue-' + id)) releaseScheduleQueue(id);
        if (!scheduleState.tasks.some(task => task.id === id)) {
            altScheduleQueues.delete(id);
            storeRemove('pixiv_schedule_queue_' + id);
        }
    });
    if (!schedulePageVisible() || !scheduleState.expandedQueues.size) return;
    const refresh = () => {
        scheduleState.expandedQueues.forEach(id => {
            const task = scheduleState.tasks.find(task => task.id === id);
            if (task) loadScheduleQueue(task, true);
        });
    };
    refresh();
    scheduleQueuePollTimer = setInterval(refresh, 4000);
}

function scheduleQueueVue() {
    return window.PixivBatchAlt && window.PixivBatchAlt.queueVue;
}

function scheduleQueueIdentity(item) {
    const runtime = altQueueTypes();
    return runtime.queueKey(item.workType ?? item.kind, item.workId ?? item.id);
}

function scheduleQueueWorkMessage(code, workType) {
    const safe = safeScheduleMachineCode(code);
    if (!safe) return null;
    if (safe.startsWith('schedule.')) {
        const result = bt(safe, '');
        return result && result !== safe ? result : null;
    }
    const runtime = altQueueTypes();
    const ns = runtime.manifestDescriptor?.(workType)?.i18nNamespace;
    if (typeof ns !== 'string' || !/^[a-z][a-z0-9._-]{0,63}$/.test(ns)
            || !safe.startsWith(ns + '.') || typeof pageI18n === 'undefined' || !pageI18n) return null;
    const key = ns + ':' + safe.slice(ns.length + 1);
    const result = pageI18n.t(key, '');
    return result && result !== key ? result : null;
}

function mapScheduleQueueItem(item, task) {
    const workType = String(item.workType ?? item.kind ?? 'unknown').trim();
    const workId = String(item.workId ?? item.id ?? '');
    const statuses = {'downloaded': 'completed', 'skipped-downloaded': 'skipped',
        'skipped-filter': 'skipped', 'failed': 'failed', 'paused': 'paused'};
    const runtime = altQueueTypes();
    const status = item.status;
    const failureCode = status === 'failed' ? safeScheduleMachineCode(item.message) : null;
    const liveStatus = item.liveStatus && typeof item.liveStatus === 'object'
        && !Array.isArray(item.liveStatus) ? Object.assign({}, item.liveStatus) : null;
    const owned = runtime.scheduledQueueItem(workType, item, {
        sourceType: task.sourceType || task.type, task
    });
    return Object.assign({}, owned, {
        id: workId, kind: workType, workType, workId,
        queueKey: runtime.queueKey(workType, workId),
        status: statuses[status] || 'pending', rawStatus: status, failureCode,
        totalImages: 0, downloadedCount: 0, imageProgress: null, ugoiraProgress: null,
        liveStatus
    });
}

function localizedScheduleQueueItem(item) {
    const messages = {'cancelled': ['queue.stage.cancelled', null], 'paused': ['path.overflow.waiting', null],
        'skipped-downloaded': ['schedule.queue.status.skipped-downloaded', '已存在，跳过'],
        'skipped-filter': ['schedule.queue.status.skipped-filter', '被筛选条件跳过']};
    const message = messages[item.rawStatus];
    return Object.assign({}, item, {
        title: item.rawTitle || item.title || bt('schedule.queue.no-title', '（暂无标题信息）'),
        lastMessage: item.status === 'failed'
            ? scheduleQueueWorkMessage(item.failureCode, item.workType) || bt('schedule.queue.status.failed', '失败')
            : message ? bt(...message) : null
    });
}

function scheduleQueueCached(task) {
    try {
        const cached = JSON.parse(storeGet('pixiv_schedule_queue_' + task.id) || 'null');
        if (!cached || !Array.isArray(cached.items)
                || (cached.lastRunTime ?? null) !== (task.lastRunTime ?? null)) return null;
        cached.items = cached.items.map(item => Object.assign({}, item, {liveStatus: null}));
        return cached;
    } catch (_error) { return null; }
}

function persistScheduleQueue(task, data) {
    storeSet('pixiv_schedule_queue_' + task.id, JSON.stringify(Object.assign({}, data, {
        lastRunTime: task.lastRunTime ?? null,
        items: data.items.map(item => Object.assign({}, item, {liveStatus: null}))
    })));
}

function refreshScheduleQueueView(task, entry) {
    const box = document.getElementById('abScheduleQueue-' + task.id);
    if (!box || !entry.data) return;
    const vue = scheduleQueueVue();
    if (vue?.ensureScheduleQueue?.(task.id, {boxEl: box, read: () => scheduleQueueDetailModel(entry.data)})) {
        vue.syncScheduleQueue(task.id);
        return;
    }
    renderScheduleQueue(box, task, entry.data);
}

function subscribeScheduleQueue(task, entry) {
    releaseScheduleQueue(task.id);
    if (state.mode !== 'schedule' || !scheduleState.expandedQueues.has(task.id)
            || !['RUNNING', 'QUEUED', 'CANCEL_REQUESTED'].includes(task.runState)) return;
    const runtime = altQueueTypes();
    const eligible = entry.data.items.filter(item => runtime.supportsScheduledSse(item.workType));
    if (!eligible.length) return;
    ensureSharedSSE();
    const identities = new Map();
    eligible.forEach(item => {
        const list = identities.get(item.workId) || new Set();
        list.add(item.queueKey);
        identities.set(item.workId, list);
    });
    entry.listeners = eligible.map(item => {
        const listener = data => {
            if (altScheduleQueues.get(task.id) !== entry) return;
            const type = String(data.workType || '').trim();
            if (type ? runtime.queueKey(type, item.workId) !== item.queueKey
                : identities.get(item.workId).size > 1) return;
            if (data.cancelled) { item.status = 'paused'; item.rawStatus = 'cancelled'; item.statusMessageKey = 'queue.stage.cancelled'; }
            else if (data.completed && !data.failed) { item.status = 'completed'; item.rawStatus = 'downloaded'; }
            else if (data.failed) { item.status = 'failed'; item.rawStatus = 'failed'; }
            else if (data.downloadedCount !== undefined || data.totalImages !== undefined) {
                item.status = 'downloading';
                item.rawStatus = 'downloading';
                if (data.totalImages !== undefined) item.totalImages = data.totalImages;
                if (data.downloadedCount !== undefined) item.downloadedCount = data.downloadedCount;
                item.imageProgress = data.imageProgress || null;
                item.ugoiraProgress = mergeUgoiraProgress(item.ugoiraProgress, data.ugoiraProgress);
            }
            if (entry.frame == null) entry.frame = requestAnimationFrame(() => {
                entry.frame = null;
                refreshScheduleQueueView(task, entry);
            });
        };
        addSSEListener(item.workId, listener);
        return {workId: item.workId, listener};
    });
}

async function loadScheduleQueue(task, quiet) {
    if (!schedulePageVisible()) return;
    const box = document.getElementById('abScheduleQueue-' + task.id);
    if (!box) return;
    let entry = altScheduleQueues.get(task.id);
    if (!entry || entry.lastRunTime !== (task.lastRunTime ?? null)) {
        releaseScheduleQueue(task.id);
        entry = {data: scheduleQueueCached(task), lastRunTime: task.lastRunTime ?? null, sequence: 0, listeners: []};
        altScheduleQueues.set(task.id, entry);
    }
    if (!quiet && entry.data) refreshScheduleQueueView(task, entry);
    if (quiet && entry.request) return;
    entry.request?.abort();
    const request = new AbortController();
    entry.request = request;
    const sequence = ++entry.sequence;
    try {
        const res = await fetch(`${BASE}/api/schedule/tasks/${task.id}/queue`, {
            credentials: 'same-origin', signal: request.signal
        });
        if (!res.ok) throw await scheduleHttpError(res);
        const data = await res.json();
        if (sequence !== entry.sequence || altScheduleQueues.get(task.id) !== entry
                || document.getElementById('abScheduleQueue-' + task.id) !== box) return;
        const incoming = Array.isArray(data.items) ? data.items : [];
        if (incoming.length || data.startedTime != null || !entry.data?.items.length) {
            const previous = new Map((entry.data?.items || []).map(item => [scheduleQueueIdentity(item), item]));
            const items = incoming.map(raw => {
                const item = mapScheduleQueueItem(raw, task);
                const old = previous.get(item.queueKey);
                if (old && item.status === 'pending' && old.status === 'downloading') {
                    Object.assign(item, {status: old.status, rawStatus: old.rawStatus,
                        totalImages: old.totalImages, downloadedCount: old.downloadedCount,
                        imageProgress: old.imageProgress, ugoiraProgress: old.ugoiraProgress});
                } else if (old) {
                    item.totalImages = old.totalImages || 0;
                    item.downloadedCount = old.downloadedCount || 0;
                }
                return item;
            });
            entry.data = Object.assign({}, data, {items});
            persistScheduleQueue(task, entry.data);
        }
        refreshScheduleQueueView(task, entry);
        subscribeScheduleQueue(task, entry);
    } catch (error) {
        if (sequence !== entry.sequence || altScheduleQueues.get(task.id) !== entry
                || document.getElementById('abScheduleQueue-' + task.id) !== box) return;
        if (quiet && entry.data) return;
        scheduleQueueVue()?.unmountScheduleQueue(task.id);
        box.replaceChildren(errorBox(String(error && error.message || bt('common.request-failed', '请求失败')),
            () => loadScheduleQueue(task, false)));
    } finally {
        if (entry.request === request) entry.request = null;
    }
}

function scheduleQueueDetailModel(data) {
    const items = (data?.items || []).map(localizedScheduleQueueItem);
    const count = status => items.filter(item => item.status === status).length;
    return {
        startedText: bt('schedule.round.started', '本轮开始：{time}', {time: fmtScheduleTime(data?.startedTime)}),
        statsText: bt('status.stats', '队列: {pending} | 成功: {success} | 失败: {failed} | 进行中: {active} | 跳过: {skipped}', {
            pending: count('pending') + count('paused'), success: count('completed'), failed: count('failed'),
            active: count('downloading'), skipped: count('skipped')
        }),
        truncated: !!data?.truncated,
        truncatedText: bt('schedule.round.truncated', '作品过多，仅记录并展示前 {count} 项', {count: items.length}),
        empty: !items.length,
        emptyText: bt('schedule.round.empty', '本轮暂无记录'),
        currentHtml: items.some(item => item.status === 'downloading') ? computeCurrentCardHtml(items, false) : '',
        rows: items.map(item => ({key: scheduleQueueIdentity(item), status: item.status,
            title: item.title, html: queueItemRow(item, {readOnly: true}).outerHTML}))
    };
}

function scheduleQueueVueContext(_id, box, data) {
    return {boxEl: box, read: () => scheduleQueueDetailModel(data)};
}

function renderScheduleQueue(box, task, data) {
    const model = scheduleQueueDetailModel(data);
    const scroll = box.querySelector('.ab-round-list')?.scrollTop || 0;
    box.replaceChildren();
    const head = el('div', 'ab-round-head');
    head.appendChild(el('span', 'ab-muted', model.startedText));
    head.appendChild(el('span', 'ab-muted', model.statsText));
    box.appendChild(head);
    if (model.truncated) box.appendChild(el('p', 'ab-field-note', model.truncatedText));
    if (model.empty) { box.appendChild(el('p', 'ab-empty-line', model.emptyText)); return; }
    if (model.currentHtml) {
        const current = el('div', 'ab-round-current');
        current.innerHTML = model.currentHtml;
        box.appendChild(current);
    }
    const list = el('div', 'ab-round-list');
    model.rows.forEach(row => {
        const item = el('div', 'ab-flatten');
        item.dataset.queueKey = row.key;
        item.innerHTML = row.html;
        list.appendChild(item);
    });
    box.appendChild(list);
    list.scrollTop = scroll;
}

/* ============================================================
   任务快照（来源 / 筛选 / 下载设置三段只读视图）
   ============================================================ */
function snapshotSection(title, rows) {
    const section = el('div', 'ab-snapshot-section');
    section.appendChild(el('h4', '', title));
    const dl = el('dl', 'ab-snapshot-dl');
    rows.forEach(([label, value]) => {
        dl.appendChild(el('dt', '', label));
        const dd = el('dd', '', value == null || value === ''
            ? bt('schedule.snapshot.value.unset', '未设置') : String(value));
        dl.appendChild(dd);
    });
    section.appendChild(dl);
    return section;
}

function schedulePresentationFallbackSections(task) {
    const presentation = scheduleTaskPresentation(task);
    const rows = [];
    if (typeof presentation.title === 'string' && presentation.title) {
        rows.push([bt('schedule.snapshot.field.name', '任务名称'), presentation.title]);
    }
    if (typeof presentation.summary === 'string' && presentation.summary) {
        rows.push([bt('schedule.snapshot.field.source', '来源'), presentation.summary]);
    }
    const attributes = presentation.attributes && typeof presentation.attributes === 'object'
        ? presentation.attributes : {};
    Object.keys(attributes).sort().forEach(key => {
        if (typeof attributes[key] === 'string') rows.push([key, attributes[key]]);
    });
    if (!rows.length) {
        rows.push([bt('schedule.snapshot.field.source', '来源'),
            bt('schedule.error.source-editor-unavailable', '计划任务来源编辑器当前不可用')]);
    }
    return [{title: bt('schedule.snapshot.section.source', '来源快照'), rows}];
}

function openScheduleSnapshot(task) {
    const body = el('div', 'ab-snapshot');
    const sourceType = task.sourceType || task.type;
    const runtime = altScheduleSources();
    let contributed = null;
    if (task.sourceAvailable !== false && runtime && runtime.isAvailable(sourceType)) {
        try { contributed = runtime.summary(task, {}); } catch (e) { contributed = null; }
    }
    const kind = (contributed && contributed.kind) || scheduleTaskKind(task);
    const credentialUi = scheduleTaskCredentialUi(task);
    const basicRows = [
        [bt('schedule.snapshot.field.name', '任务名称'), task.name],
        [bt('schedule.snapshot.field.type', '任务类型'),
            task.sourceAvailable === false ? sourceType : scheduleTypeLabel(task)],
        [bt('schedule.snapshot.field.trigger', '触发方式'), scheduleTriggerLabel(task)],
        [bt('schedule.snapshot.field.credential', '来源凭证'), credentialUi.badgeLabel
            || (task.cookieBound
                ? bt('schedule.credential.bound', '已绑定凭证')
                : bt('schedule.credential.unbound', '未绑定凭证'))],
        [bt('schedule.snapshot.field.proxy', '单独代理'), task.proxy
            || bt('schedule.snapshot.value.global-proxy', '使用全局代理设置')],
        [bt('schedule.snapshot.field.enabled', '启用状态'), task.enabled
            ? bt('schedule.state.enabled', '已启用') : bt('schedule.state.disabled', '已停用')],
        [bt('schedule.snapshot.field.next-run', '下次运行'), fmtScheduleTime(task.nextRunTime)],
        [bt('schedule.snapshot.field.last-run', '上次运行'), fmtScheduleTime(task.lastRunTime)],
        [bt('schedule.snapshot.field.last-status', '运行状态'), scheduleStatusLabel(task)]
    ];
    if (kind) basicRows.splice(2, 0,
        [bt('schedule.snapshot.field.kind', '作品类型'), scheduleKindLabel(kind)]);
    body.appendChild(snapshotSection(bt('schedule.snapshot.section.basic', '基本信息'), basicRows));

    const sections = contributed && Array.isArray(contributed.sections)
        ? contributed.sections : schedulePresentationFallbackSections(task);
    sections.filter(section => section && typeof section.title === 'string' && Array.isArray(section.rows))
        .forEach(section => body.appendChild(snapshotSection(section.title,
            section.rows.filter(row => Array.isArray(row) && row.length >= 2)
                .map(row => [String(row[0]), String(row[1])]))));
    body.appendChild(el('p', 'ab-field-note',
        bt('schedule.snapshot.note', '编辑请使用功能区域的编辑操作')));

    openModal({
        id: 'schedule-snapshot',
        icon: 'eye',
        title: bt('schedule.snapshot.title', '任务快照 · {name}', {name: task.name || task.id}),
        body,
        widthClass: 'ab-modal--wide'
    });
}
