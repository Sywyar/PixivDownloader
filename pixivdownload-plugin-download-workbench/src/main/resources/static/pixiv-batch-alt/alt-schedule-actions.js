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
        await schedulePost(task, verb);
        abToast('success', bt('schedule.feedback.saved', '操作成功'));
    } catch (e) {
        abToast('error', String(e && e.message || bt('schedule.feedback.failed', '操作失败')));
    }
    loadScheduleTasks(true);
}

async function scheduleSetEnabled(task, enabled) {
    try {
        await schedulePost(task, 'enabled?enabled=' + encodeURIComponent(enabled));
    } catch (e) {
        abToast('error', String(e && e.message || bt('schedule.feedback.failed', '操作失败')));
    }
    loadScheduleTasks(true);
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
function startScheduleQueuePolling() {
    if (scheduleQueuePollTimer) {
        clearInterval(scheduleQueuePollTimer);
        scheduleQueuePollTimer = null;
    }
    if (!scheduleState.expandedQueues.size) return;
    // 4 秒轮询 + SSE 实时进度（本页以轮询呈现）
    scheduleQueuePollTimer = setInterval(() => {
        scheduleState.expandedQueues.forEach(id => {
            const task = scheduleState.tasks.find(t => t.id === id);
            if (task) loadScheduleQueue(task, true);
        });
    }, 4000);
}

async function loadScheduleQueue(task, quiet) {
    const box = document.getElementById('abScheduleQueue-' + task.id);
    if (!box) return;
    let data;
    try {
        const res = await fetch(`${BASE}/api/schedule/tasks/${task.id}/queue`, {credentials: 'same-origin'});
        if (!res.ok) throw await scheduleHttpError(res);
        data = await res.json();
    } catch (e) {
        const vue = scheduleQueueVue();
        if (vue && typeof vue.unmountScheduleQueue === 'function') vue.unmountScheduleQueue(task.id);
        box.replaceChildren(errorBox(String(e && e.message || bt('common.request-failed', '请求失败')),
            () => loadScheduleQueue(task, false)));
        return;
    }
    if (quiet && !document.getElementById('abScheduleQueue-' + task.id)) return;
    const vue = scheduleQueueVue();
    if (vue && typeof vue.ensureScheduleQueue === 'function'
            && vue.ensureScheduleQueue(task.id, scheduleQueueVueContext(task.id, box, data))) {
        // Vue 已（将）接管该 box：合并一次 reactive 同步（Vue 据 :key 仅 patch 变化，
        // 不整块重建 .ab-schedule-queue），命令式只在 Vue 不可用 / 挂载失败时兜底。
        vue.syncScheduleQueue(task.id);
        return;
    }
    renderScheduleQueue(box, task, data);
}

const SCHEDULE_QUEUE_STATUS = {
    'pending': ['queue.status.pending', '待处理'],
    'downloaded': ['queue.status.downloaded', '已下载'],
    'skipped-downloaded': ['queue.status.skipped-downloaded', '已存在跳过'],
    'skipped-filter': ['queue.status.skipped-filter', '被筛选条件跳过'],
    'failed': ['queue.status.failed', '失败']
};

function scheduleQueueVue() {
    return window.PixivBatchAlt && window.PixivBatchAlt.queueVue;
}

// 本轮队列详情的展示模型派生（命令式 renderScheduleQueue 与 Vue 岛共用同一口径，避免两路分叉）。
// 模型只存 raw 字段 + 渲染期经 bt() 派生的展示字段；Vue 岛只读本模型、不反向 import schedule 内部模型。
function scheduleQueueDetailModel(data) {
    const items = (data && Array.isArray(data.items)) ? data.items : [];
    const total = data && data.total != null ? data.total : items.length;
    const rows = items.map((item, index) => {
        const statusDef = SCHEDULE_QUEUE_STATUS[item.status] || ['queue.status.pending', '待处理'];
        let statusText = bt(statusDef[0], statusDef[1]);
        if (item.message) {
            const reason = localizeScheduleMachineCode(item.message);
            if (reason) statusText += '：' + reason;
        }
        const translateText = item.translatePhase
            ? bt('queue.translate.label', 'AI 翻译') + ' ' +
                (item.translateElapsedSeconds != null
                    ? bt('queue.message.translating', 'AI 翻译中（{sec}s）', {sec: item.translateElapsedSeconds})
                    : '')
            : '';
        return {
            key: 'sched:' + index + ':' + String(item.status || ''),
            status: item.status,
            title: (item.title && String(item.title).trim())
                ? item.title
                : bt('schedule.queue.no-title', '（暂无标题信息）'),
            showTranslate: !!translateText,
            translateText,
            statusText
        };
    });
    return {
        startedText: bt('schedule.round.started', '本轮开始：{time}',
            {time: fmtScheduleTime(data && data.startedTime)}),
        statsText: bt('schedule.round.stats', '共 {count} 项', {count: total}),
        truncated: !!(data && data.truncated),
        truncatedText: bt('schedule.round.truncated', '作品过多，仅记录并展示前 {count} 项', {count: items.length}),
        empty: !items.length,
        emptyText: bt('schedule.round.empty', '本轮暂无记录'),
        rows
    };
}

// 计划队列详情 Vue 岛上下文：data 为最近一次 fetch 的原始响应，read() 派生展示模型。
function scheduleQueueVueContext(id, box, data) {
    return {
        boxEl: box,
        read() {
            return scheduleQueueDetailModel(data || null);
        }
    };
}

function renderScheduleQueue(box, task, data) {
    const model = scheduleQueueDetailModel(data);
    box.innerHTML = '';
    const head = el('div', 'ab-round-head');
    head.appendChild(el('span', 'ab-muted', model.startedText));
    head.appendChild(el('span', 'ab-muted', model.statsText));
    box.appendChild(head);
    if (model.truncated) {
        box.appendChild(el('p', 'ab-field-note', model.truncatedText));
    }
    if (model.empty) {
        box.appendChild(el('p', 'ab-empty-line', model.emptyText));
        return;
    }
    const list = el('div', 'ab-round-list');
    model.rows.forEach(row => {
        const item = el('div', 'ab-round-item');
        item.dataset.status = row.status;
        item.appendChild(el('span', 'ab-round-title', row.title));
        const right = el('span', 'ab-round-right');
        if (row.showTranslate) {
            right.appendChild(el('span', 'ab-mini-badge ab-mini-badge--ai', row.translateText));
        }
        right.appendChild(el('span', 'ab-round-status', row.statusText));
        item.appendChild(right);
        list.appendChild(item);
    });
    box.appendChild(list);
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
