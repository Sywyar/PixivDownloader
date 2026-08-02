'use strict';
/* ============================================================
   alt-schedule — 计划任务（仅管理员）
   状态灯 / 触发 / 类型标签映射逐字移植 batch-schedule.js 语义；
   任务列表 / 动词 / 待重试 / 账号级恢复走真实 /api/schedule/**。
   ============================================================ */
let schedulePollTimer = null;
let scheduleQueuePollTimer = null;

function enterScheduleMode() {
    loadScheduleTasks();
    startSchedulePolling();
}

function startSchedulePolling() {
    stopSchedulePolling();
    schedulePollTimer = setInterval(() => {
        if (state.mode === 'schedule') loadScheduleTasks(true);
    }, 10000);
}

function stopSchedulePolling() {
    if (schedulePollTimer) {
        clearInterval(schedulePollTimer);
        schedulePollTimer = null;
    }
    if (scheduleQueuePollTimer) {
        clearInterval(scheduleQueuePollTimer);
        scheduleQueuePollTimer = null;
    }
}

async function loadScheduleTasks(quiet) {
    try {
        const res = await fetch(`${BASE}/api/schedule/tasks`, {credentials: 'same-origin'});
        if (!res.ok) throw await scheduleHttpError(res);
        const data = await res.json();
        scheduleState.tasks = Array.isArray(data) ? data : [];
        scheduleState.error = '';
    } catch (e) {
        scheduleState.tasks = [];
        scheduleState.error = String(e && e.message || bt('common.request-failed', '请求失败'));
    }
    scheduleState.loaded = true;
    if (!quiet || state.mode === 'schedule') renderScheduleTaskList();
}

/* ============================================================
   视图模型（逐字移植）
   ============================================================ */
function scheduleStatusLabel(code) {
    if (!code) return bt('schedule.run-status.none', '尚未运行');
    if (code === 'OK') return bt('schedule.run-status.ok', '正常');
    if (code === 'AUTH_EXPIRED') return bt('schedule.run-status.auth-expired',
        '登录凭证已失效，请重新绑定有效凭证');
    if (code === 'ERROR') return bt('schedule.run-status.error', '运行出错');
    if (code === 'PAUSED') return bt('schedule.run-status.paused', '已手动暂停');
    if (code === 'OVERUSE_PAUSED') return bt('schedule.run-status.overuse-paused', '已暂停：检测到过度访问警告');
    if (code === 'SOURCE_UNAVAILABLE') return bt('schedule.light.source-unavailable', '来源能力当前不可用，等待插件恢复');
    if (code === 'EXECUTOR_UNAVAILABLE') return bt('schedule.light.executor-unavailable', '作品执行能力当前不可用，等待插件恢复');
    if (code === 'QUIESCED') return bt('schedule.light.quiesced', '插件正在安全停用，等待能力恢复');
    if (code === 'MIGRATION_ERROR') return bt('schedule.light.migration-error', '任务数据需要修复，无法运行');
    return code;
}

function safeScheduleMachineCode(value) {
    const code = typeof value === 'string' ? value.trim() : '';
    return /^[a-z][a-z0-9._-]{1,159}$/.test(code) ? code : null;
}

function localizeScheduleMachineCode(value) {
    const code = safeScheduleMachineCode(value);
    if (!code) return null;
    if (code.startsWith('schedule.')) {
        const translated = bt(code, '');
        return translated && translated !== code ? translated : null;
    }
    return null;
}

function scheduleFailureReason(t) {
    return t ? localizeScheduleMachineCode(t.lastMessage) : null;
}

/**
 * 计算任务卡片右上角「状态灯」：返回 {tone, text}。
 * 优先级：瞬时运行态（运行中 / 排队中）> 已停用 > 挂起原因 > 上一轮持久化结果 > 首次未运行。
 */
function scheduleStatusLight(t) {
    if (t.runState === 'RUNNING') {
        return {tone: 'green', live: true, text: bt('schedule.light.running', '正在运行')};
    }
    if (t.runState === 'QUEUED') {
        return {tone: 'yellow', live: true, text: bt('schedule.light.queued', '排队中')};
    }
    if (t.runState === 'CANCEL_REQUESTED') {
        return {tone: 'yellow', live: true, text: bt('schedule.light.cancel-requested', '正在取消并安全收尾')};
    }
    if (!t.enabled) {
        return {tone: 'gray', live: false, text: bt('schedule.light.disabled', '已停用，不会自动运行')};
    }
    if (t.suspendReason === 'SOURCE_UNAVAILABLE') {
        return {tone: 'red', live: false, text: bt('schedule.light.source-unavailable', '来源能力当前不可用，等待插件恢复')};
    }
    if (t.suspendReason === 'EXECUTOR_UNAVAILABLE') {
        return {tone: 'red', live: false, text: bt('schedule.light.executor-unavailable', '作品执行能力当前不可用，等待插件恢复')};
    }
    if (t.suspendReason === 'QUIESCED') {
        return {tone: 'yellow', live: false, text: bt('schedule.light.quiesced', '插件正在安全停用，等待能力恢复')};
    }
    if (t.suspendReason === 'MIGRATION_ERROR') {
        const reason = localizeScheduleMachineCode(t.suspendCode);
        return {
            tone: 'red',
            live: false,
            text: reason || bt('schedule.light.migration-error', '任务数据需要修复，无法运行')
        };
    }
    // 挂起态优先于中断结果：挂起任务不会被自动重排，不能显示「已重新排期补齐」。
    if (t.lastStatus === 'OVERUSE_PAUSED') {
        return {tone: 'red', live: false, text: bt('schedule.light.overuse-paused', '已暂停：检测到过度访问警告（账号级）')};
    }
    if (t.lastStatus === 'PAUSED') {
        return {tone: 'gray', live: false, text: bt('schedule.light.paused', '已手动暂停')};
    }
    if (t.lastStatus === 'AUTH_EXPIRED') {
        return {tone: 'red', live: false, text: bt('schedule.light.auth-expired',
            '运行失败，来源登录凭证已失效，请重新绑定有效凭证')};
    }
    if (t.lastOutcome === 'INTERRUPTED' || t.lastStatus === 'INTERRUPTED') {
        return {tone: 'red', live: false, text: bt('schedule.light.interrupted', '运行失败，上次运行被中断，已重新排期补齐')};
    }
    if (t.lastStatus === 'ERROR') {
        const reason = scheduleFailureReason(t);
        return {
            tone: 'red',
            live: false,
            text: reason
                ? bt('schedule.light.error-reason', '运行失败，因为：{reason}', {reason})
                : bt('schedule.light.error', '运行失败')
        };
    }
    if (t.lastStatus === 'OK') {
        return {tone: 'green', live: false, text: bt('schedule.light.ok', '运行成功，等待下次运行')};
    }
    if (t.lastRunTime != null) {
        return {tone: 'gray', live: false, text: bt('schedule.light.idle', '等待下次运行')};
    }
    return {tone: 'gray', live: false, text: bt('schedule.light.never', '等待首次运行')};
}

function fmtScheduleTime(ms) {
    if (!ms) return '—';
    try { return new Date(ms).toLocaleString(); } catch (e) { return '—'; }
}

function scheduleTypeLabel(t) {
    const sourceType = typeof t === 'string' ? t : t && (t.sourceType || t.type);
    const runtime = altScheduleSources();
    const descriptor = runtime && runtime.descriptor(sourceType);
    const presentation = (descriptor && descriptor.presentation) || (t && t.presentation);
    if (presentation && presentation.displayNamespace && presentation.displayNameKey && pageI18n) {
        return pageI18n.t(
            `${presentation.displayNamespace}:${presentation.displayNameKey}`,
            sourceType || bt('schedule.snapshot.value.unknown', '未知'));
    }
    return sourceType || bt('schedule.snapshot.value.unknown', '未知');
}

function scheduleKindLabel(kind) {
    if (kind === 'mixed') return bt('schedule.kind.mixed', '插画+小说');
    if (kind === 'novel') return bt('schedule.kind.novel', '小说');
    return bt('schedule.kind.illust', '插画');
}

function scheduleTaskKind(task) {
    const presentation = scheduleTaskPresentation(task);
    const attributes = presentation.attributes && typeof presentation.attributes === 'object'
        ? presentation.attributes : {};
    return attributes.kind || presentation.kind || null;
}

function scheduleTaskPresentation(task) {
    if (task && task.presentation && typeof task.presentation === 'object') {
        return task.presentation;
    }
    try {
        const value = JSON.parse((task || {}).presentationJson || '{}');
        return value && typeof value === 'object' && !Array.isArray(value) ? value : {};
    } catch (e) {
        return {};
    }
}

function scheduleCredentialCapabilities(sourceType, context) {
    const runtime = altScheduleSources();
    const unavailable = {supportsCookie: false, supportsProxy: false, presentation: {}};
    if (!runtime || !sourceType || !runtime.isAvailable(sourceType)) return unavailable;
    try {
        const actions = runtime.credentialActions(sourceType, context || {});
        if (!actions || typeof actions.then === 'function') {
            if (actions) Promise.resolve(actions).catch(() => {});
            return unavailable;
        }
        return {
            supportsCookie: actions.supportsCookie === true,
            supportsProxy: actions.supportsProxy === true,
            presentation: actions.presentation && typeof actions.presentation === 'object'
                ? actions.presentation : {}
        };
    } catch (e) {
        return unavailable;
    }
}

function scheduleTaskCredentialUi(task) {
    const sourceType = task.sourceType || task.type;
    const runtime = altScheduleSources();
    const sourceActive = !!(runtime && runtime.isAvailable(sourceType));
    const capabilities = sourceActive
        ? scheduleCredentialCapabilities(sourceType, {task})
        : {supportsCookie: false, supportsProxy: false, presentation: {}};
    const presentation = capabilities.presentation;
    return {
        badgeLabel: capabilities.supportsCookie
            ? (task.cookieBound
                ? presentation.boundLabel || bt('schedule.credential.bound', '已绑定凭证')
                : presentation.unboundLabel || bt('schedule.credential.unbound', '未绑定凭证'))
            : (!sourceActive && task.cookieBound
                ? bt('schedule.credential.bound', '已绑定凭证') : null),
        showOverride: sourceActive && (capabilities.supportsCookie || capabilities.supportsProxy)
    };
}

function scheduleTriggerLabel(t) {
    const minutes = t.intervalMinutes || 0;
    return t.triggerKind === 'cron'
        ? `${bt('schedule.trigger.cron', 'Cron 表达式')} ${t.cronExpr || ''}`
        : `${bt('schedule.trigger.interval', '固定周期')} ${bt('schedule.time.minutes', '{count} 分钟', {count: minutes})}`;
}

/* ============================================================
   模式渲染
   ============================================================ */
function renderScheduleMode(panel) {
    const def = AB_MODES[5];
    const createBtn = el('button', 'ab-btn ab-btn--primary ab-btn--sm');
    createBtn.type = 'button';
    createBtn.appendChild(abIconEl('plus'));
    createBtn.appendChild(el('span', '', bt('schedule.create', '新建计划任务')));
    createBtn.addEventListener('click', () => {
        switchMode(QUICK_FETCH_MODE);
        abToast('info', bt('schedule.editor.configure-source',
            '请先配置并预览来源，再点击「存为计划任务」'));
    });
    panel.appendChild(modeHeader(def, [createBtn]));

    const bannerHost = el('div');
    bannerHost.id = 'abScheduleBanners';
    panel.appendChild(bannerHost);

    const list = el('div', 'ab-schedule-list');
    list.id = 'abScheduleList';
    panel.appendChild(list);
    if (!scheduleState.loaded) {
        list.appendChild(loadingGrid(bt('common.loading', '加载中…')));
    } else {
        renderScheduleTaskList();
    }
}

function renderScheduleTaskList() {
    const bannerHost = document.getElementById('abScheduleBanners');
    const list = document.getElementById('abScheduleList');
    if (!list) return;
    renderOveruseBanners(bannerHost);
    list.innerHTML = '';
    if (scheduleState.error) {
        list.appendChild(errorBox(scheduleState.error, () => loadScheduleTasks(false)));
        return;
    }
    if (!scheduleState.tasks.length) {
        const empty = el('div', 'ab-empty ab-empty--tall');
        empty.appendChild(abIconEl('clock'));
        empty.appendChild(el('p', '', bt('schedule.empty', '暂无计划任务，点击右上角「新建计划任务」开始')));
        list.appendChild(empty);
        return;
    }
    scheduleState.tasks.forEach((task, idx) => {
        list.appendChild(scheduleTaskCard(task, idx));
    });
    hydrateIcons(list);
    startScheduleQueuePolling();
}

// 过度访问（按账号分组）横幅
function renderOveruseBanners(host) {
    if (!host) return;
    host.innerHTML = '';
    const groups = new Map();
    scheduleState.tasks.forEach(t => {
        if (t.lastStatus !== 'OVERUSE_PAUSED' && t.suspendReason !== 'OVERUSE_PAUSED') return;
        const account = t.accountId || '-';
        if (!groups.has(account)) groups.set(account, []);
        groups.get(account).push(t);
    });
    groups.forEach((tasks, account) => {
        const banner = el('div', 'ab-overuse card');
        const head = el('div', 'ab-overuse-head');
        head.appendChild(abIconEl('alert'));
        head.appendChild(el('span', '',
            bt('schedule.overuse.message', '账号 {account} 有 {count} 个计划任务因检测到 Pixiv 过度访问警告被暂停',
                {account, count: tasks.length})));
        banner.appendChild(head);
        const actions = el('div', 'ab-overuse-actions');
        const ignoreBtn = el('button', 'ab-btn ab-btn--danger ab-btn--sm',
            bt('schedule.overuse.ignore', '无视风险，继续下载（可能导致删号）'));
        ignoreBtn.type = 'button';
        ignoreBtn.addEventListener('click', async () => {
            if (!await abConfirm('schedule.overuse.ignore.confirm',
                '确认无视过度访问警告并恢复该账号的全部任务？可能导致账号被封禁。',
                null, {danger: true})) return;
            await resumeOveruseAccount(account, 'ignore', 0);
        });
        const deferBtn = el('button', 'ab-btn ab-btn--ghost ab-btn--sm',
            bt('schedule.overuse.defer', '延迟 N 分钟后继续所有同账号任务'));
        deferBtn.type = 'button';
        deferBtn.addEventListener('click', async () => {
            const value = await abPrompt('schedule.overuse.defer.prompt',
                '输入延迟分钟数（最低 60 分钟）', null,
                {inputType: 'number', min: 60, value: '60'});
            const minutes = parseInt(value, 10);
            if (!Number.isFinite(minutes) || minutes < 60) {
                if (value !== null) abToast('warning', bt('schedule.overuse.defer.min', '延迟分钟数最低为 60'));
                return;
            }
            await resumeOveruseAccount(account, 'defer', minutes);
        });
        actions.appendChild(ignoreBtn);
        actions.appendChild(deferBtn);
        banner.appendChild(actions);
        host.appendChild(banner);
    });
}

async function resumeOveruseAccount(account, mode, minutes) {
    try {
        const res = await fetch(`${BASE}/api/schedule/account/${encodeURIComponent(account)}/resume`, {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            credentials: 'same-origin',
            body: JSON.stringify({mode, minutes})
        });
        if (!res.ok) throw await scheduleHttpError(res);
    } catch (e) {
        abToast('error', String(e && e.message || bt('schedule.feedback.failed', '操作失败')));
        return;
    }
    abToast('success', bt('schedule.overuse.resumed', '已恢复该账号的所有任务'));
    loadScheduleTasks(true);
}

function scheduleTaskCard(task, idx) {
    const light = scheduleStatusLight(task);
    const kind = scheduleTaskKind(task);
    const credentialUi = scheduleTaskCredentialUi(task);
    const sourceType = task.sourceType || task.type;
    const runtime = altScheduleSources();
    const sourceEditable = task.sourceAvailable !== false
        && runtime && runtime.isAvailable(sourceType);
    const card = el('div', 'ab-schedule-card card');
    card.style.setProperty('--stagger', String(Math.min(idx, 10)));

    const head = el('div', 'ab-schedule-head');
    const titleWrap = el('div', 'ab-schedule-title');
    titleWrap.appendChild(el('strong', '', task.name || ('#' + task.id)));
    const subtitle = el('span', 'ab-muted');
    subtitle.textContent = summaryJoin([
        scheduleTypeLabel(task),
        kind ? scheduleKindLabel(kind) : null
    ]);
    titleWrap.appendChild(subtitle);
    head.appendChild(titleWrap);
    const lamp = el('span', 'ab-lamp ab-lamp--' + light.tone + (light.live ? ' is-live' : ''));
    lamp.appendChild(el('span', 'ab-lamp-dot'));
    lamp.appendChild(el('span', '', light.text));
    head.appendChild(lamp);
    card.appendChild(head);

    const metaGrid = el('div', 'ab-schedule-meta');
    if (credentialUi.badgeLabel) {
        metaGrid.appendChild(scheduleMetaItem('schedule.meta.credential', '凭证',
            credentialUi.badgeLabel, task.cookieBound ? 'ok' : 'warn'));
    }
    if (task.proxy) {
        metaGrid.appendChild(scheduleMetaItem('schedule.meta.proxy', '代理', String(task.proxy), 'brand'));
    }
    metaGrid.appendChild(scheduleMetaItem('schedule.meta.trigger', '触发方式', scheduleTriggerLabel(task)));
    metaGrid.appendChild(scheduleMetaItem('schedule.meta.next-run', '下次运行',
        task.nextRunTime
            ? fmtScheduleTime(task.nextRunTime)
            : (task.suspendReason
                ? bt('schedule.next-run.capability', '等待插件能力恢复后自动重试')
                : (task.lastStatus === 'PAUSED' || task.lastStatus === 'OVERUSE_PAUSED')
                    ? bt('schedule.next-run.suspended', '需人工恢复后才会继续')
                    : '—')));
    metaGrid.appendChild(scheduleMetaItem('schedule.meta.last-run', '上次运行',
        task.lastRunTime ? fmtScheduleTime(task.lastRunTime) : '-'));
    card.appendChild(metaGrid);

    const actions = el('div', 'ab-schedule-actions');
    const busy = task.runState === 'RUNNING' || task.runState === 'QUEUED';
    const suspended = !!task.suspendReason
        || task.lastStatus === 'PAUSED' || task.lastStatus === 'OVERUSE_PAUSED';

    actions.appendChild(scheduleActionBtn('play', 'schedule.actions.run', '立即运行',
        busy || !task.enabled || !!task.suspendReason,
        busy
            ? bt('schedule.action-disabled.busy', '运行 / 排队中不可操作')
            : !task.enabled
                ? bt('schedule.action-disabled.disabled', '已停用')
                : bt('schedule.action-disabled.suspended', '插件能力不可用 / 暂停中'),
        () => scheduleVerb(task, 'run')));
    if (suspended && !task.suspendReason) {
        actions.appendChild(scheduleActionBtn('refresh', 'schedule.actions.resume', '恢复', false, '',
            () => scheduleVerb(task, 'resume')));
    } else {
        actions.appendChild(scheduleActionBtn('pause', 'schedule.actions.pause', '暂停',
            !task.enabled || task.lastStatus === 'PAUSED',
            bt('schedule.action-disabled.not-running', '未在运行或已暂停'),
            () => scheduleVerb(task, 'pause')));
    }
    actions.appendChild(scheduleActionBtn(task.enabled ? 'stop' : 'play',
        task.enabled ? 'schedule.actions.disable' : 'schedule.actions.enable',
        task.enabled ? '停用' : '启用', busy, busy ? bt('schedule.action-disabled.busy', '运行 / 排队中不可操作') : '',
        () => scheduleSetEnabled(task, !task.enabled)));
    actions.appendChild(scheduleActionBtn('edit', 'schedule.actions.edit', '编辑', !sourceEditable,
        sourceEditable ? '' : bt('schedule.error.source-editor-unavailable', '计划任务来源编辑器当前不可用'),
        () => openScheduleEditor(task)));
    actions.appendChild(scheduleActionBtn('eye', 'schedule.actions.snapshot', '任务快照', false, '',
        () => openScheduleSnapshot(task)));
    if (credentialUi.showOverride) {
        actions.appendChild(scheduleActionBtn('key', 'schedule.actions.override', '代理 / 凭证', false, '',
            () => openScheduleOverride(task)));
    }
    actions.appendChild(scheduleActionBtn('alert', 'schedule.actions.pending', '待重试', false, '',
        () => openSchedulePending(task)));
    actions.appendChild(scheduleActionBtn('trash', 'schedule.actions.delete', '删除', busy,
        busy ? bt('schedule.action-disabled.busy', '运行 / 排队中不可操作') : '',
        () => deleteScheduleTask(task), true));
    card.appendChild(actions);

    // 本轮队列详情（可折叠）
    const queueToggle = el('button', 'ab-schedule-queue-toggle');
    queueToggle.type = 'button';
    const expanded = scheduleState.expandedQueues.has(task.id);
    queueToggle.appendChild(abIconEl('chevron-down', expanded ? '' : 'ab-collapsed'));
    queueToggle.appendChild(el('span', '', bt('schedule.round.title', '本轮队列详情')));
    queueToggle.addEventListener('click', () => {
        if (scheduleState.expandedQueues.has(task.id)) {
            scheduleState.expandedQueues.delete(task.id);
            // 折叠：卸载该任务详情岛（再展开时命令式首屏 + 重挂）。
            const vueCollapse = scheduleQueueVue();
            if (vueCollapse && typeof vueCollapse.unmountScheduleQueue === 'function') {
                vueCollapse.unmountScheduleQueue(task.id);
            }
        } else {
            scheduleState.expandedQueues.add(task.id);
        }
        renderScheduleTaskList();
    });
    card.appendChild(queueToggle);
    if (expanded) {
        const queueBox = el('div', 'ab-schedule-queue');
        queueBox.id = 'abScheduleQueue-' + task.id;
        queueBox.appendChild(el('p', 'ab-loading-line', bt('common.loading', '加载中…')));
        card.appendChild(queueBox);
        loadScheduleQueue(task);
    }
    return card;
}

function scheduleMetaItem(labelKey, labelFallback, value, tone) {
    const item = el('div', 'ab-schedule-meta-item');
    item.appendChild(el('span', 'ab-schedule-meta-label', bt(labelKey, labelFallback)));
    const valueEl = el('span', 'ab-schedule-meta-value' + (tone ? ' ab-pill ab-pill--' + tone : ''));
    valueEl.textContent = value;
    item.appendChild(valueEl);
    return item;
}

function scheduleActionBtn(icon, labelKey, labelFallback, disabled, disabledReason, onClick, danger) {
    const btn = el('button', 'ab-btn ab-btn--ghost ab-btn--sm' + (danger ? ' ab-btn--danger-text' : ''));
    btn.type = 'button';
    btn.appendChild(abIconEl(icon));
    btn.appendChild(el('span', '', bt(labelKey, labelFallback)));
    btn.disabled = !!disabled;
    if (disabled && disabledReason) btn.title = disabledReason;
    btn.addEventListener('click', onClick);
    return btn;
}

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
        [bt('schedule.snapshot.field.last-status', '运行状态'), scheduleStatusLabel(task.lastStatus)]
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

/* ============================================================
   专用代理 / 凭证覆写
   ============================================================ */
function isValidProxyHostPort(value) {
    const normalized = String(value || '').trim();
    const colon = normalized.lastIndexOf(':');
    if (colon <= 0 || colon === normalized.length - 1) return false;
    if (!/^[A-Za-z0-9._-]+$/.test(normalized.slice(0, colon))) return false;
    const port = Number(normalized.slice(colon + 1));
    return Number.isInteger(port) && port >= 1 && port <= 65535;
}

function openScheduleOverride(task) {
    const body = el('div', 'ab-override');
    const sourceType = task.sourceType || task.type;
    const capabilities = scheduleCredentialCapabilities(sourceType, {task});
    const presentation = capabilities.presentation;
    if (presentation.modalIntro) {
        body.appendChild(el('p', 'ab-field-note', presentation.modalIntro));
    }

    // —— 单独代理 ——
    body.appendChild(el('h4', 'ab-settings-group', bt('schedule.override.proxy', '单独代理')));
    const proxyInput = el('input', 'ab-input');
    proxyInput.type = 'text';
    proxyInput.placeholder = bt('schedule.override.proxy.placeholder', 'host:port');
    proxyInput.value = task.proxy || '';
    body.appendChild(proxyInput);
    body.appendChild(el('p', 'ab-field-note',
        presentation.proxyHint || bt('schedule.override.proxy.help', '该任务运行时使用此代理；清除则用全局代理。')));

    // —— 单独凭证 ——
    body.appendChild(el('h4', 'ab-settings-group', bt('schedule.override.credential', '单独凭证')));
    const credentialState = el('p', 'ab-field-note',
        task.cookieBound
            ? bt('schedule.override.credential.bound', '已绑定凭证（不回显）')
            : bt('schedule.override.credential.none', '尚未绑定凭证，将以受限模式运行'));
    body.appendChild(credentialState);
    const cookieInput = el('textarea', 'ab-input');
    cookieInput.rows = 3;
    cookieInput.spellcheck = false;
    cookieInput.placeholder = task.cookieBound
        ? presentation.boundPlaceholder || bt('schedule.override.credential.bound-placeholder', '凭证已绑定且不回显；留空保持不变')
        : presentation.placeholder || bt('schedule.override.credential.placeholder', '粘贴该来源所需的凭证');
    body.appendChild(cookieInput);
    if (presentation.credentialHint) {
        body.appendChild(el('p', 'ab-field-note', presentation.credentialHint));
    }
    const useSavedBtn = el('button', 'ab-btn ab-btn--ghost ab-btn--sm',
        presentation.savedCredentialLabel || bt('schedule.override.use-saved', '使用当前保存的凭证'));
    useSavedBtn.type = 'button';
    useSavedBtn.addEventListener('click', async () => {
        try {
            const credential = await scheduleCredentialValue(sourceType);
            if (!credential) throw new Error(bt('schedule.error.no-cookie', '当前来源没有可用的已保存凭证'));
            cookieInput.value = String(credential);
            status.textContent = '';
        } catch (e) {
            status.textContent = String(e && e.message || bt('schedule.error.no-cookie', '当前来源没有可用的已保存凭证'));
        }
    });
    body.appendChild(useSavedBtn);

    const status = el('p', 'ab-field-note');
    body.appendChild(status);

    const actions = el('div', 'ab-cookie-actions');
    const saveBtn = el('button', 'ab-btn ab-btn--primary');
    saveBtn.type = 'button';
    saveBtn.appendChild(el('span', '', bt('common.save', '保存')));
    saveBtn.addEventListener('click', async () => {
        const proxy = proxyInput.value.trim();
        const cookie = cookieInput.value.trim();
        if (proxy && !isValidProxyHostPort(proxy)) {
            status.textContent = bt('schedule.override.proxy.invalid', '代理格式无效（应为 host:port）');
            return;
        }
        try {
            if (cookie) await validateScheduleCredential(sourceType, cookie, task);
            if (proxy) await schedulePost(task, 'proxy', {proxy});
            if (cookie) await bindScheduleCredential(task.id, sourceType, cookie);
            abToast('success', bt('schedule.feedback.saved', '操作成功'));
        } catch (e) {
            status.textContent = String(e && e.message || bt('schedule.feedback.failed', '操作失败'));
            return;
        }
        closeModal();
        loadScheduleTasks(true);
    });
    const clearProxyBtn = el('button', 'ab-btn ab-btn--danger-ghost');
    clearProxyBtn.type = 'button';
    clearProxyBtn.appendChild(el('span', '', bt('schedule.override.clear-proxy', '清除专用代理')));
    clearProxyBtn.addEventListener('click', async () => {
        if (!await abConfirm('schedule.override.clear-proxy.confirm',
            '确认清除该任务的专用代理（回退全局代理）？', null, {danger: true})) return;
        try {
            await schedulePost(task, 'proxy', {proxy: null});
        } catch (e) {
            status.textContent = String(e && e.message || bt('schedule.feedback.failed', '操作失败'));
            return;
        }
        abToast('success', bt('schedule.feedback.saved', '操作成功'));
        closeModal();
        loadScheduleTasks(true);
    });
    actions.appendChild(saveBtn);
    actions.appendChild(clearProxyBtn);
    if (task.cookieBound) {
        const clearCredentialBtn = el('button', 'ab-btn ab-btn--danger-ghost');
        clearCredentialBtn.type = 'button';
        clearCredentialBtn.appendChild(el('span', '',
            bt('schedule.override.clear-credential', '清除专用凭证')));
        clearCredentialBtn.addEventListener('click', async () => {
            if (!await abConfirm('schedule.override.clear-credential.confirm',
                '确认清除该任务已绑定的专用凭证？', null, {danger: true})) return;
            try {
                await schedulePost(task, 'revoke-cookie');
            } catch (e) {
                status.textContent = String(e && e.message || bt('schedule.feedback.failed', '操作失败'));
                return;
            }
            abToast('success', bt('schedule.feedback.saved', '操作成功'));
            closeModal();
            loadScheduleTasks(true);
        });
        actions.appendChild(clearCredentialBtn);
    }
    body.appendChild(actions);

    openModal({
        id: 'schedule-override',
        icon: 'key',
        title: presentation.modalTitle
            || bt('schedule.override.title', '专用代理 / 凭证 · {name}', {name: task.name || task.id}),
        body,
        widthClass: 'ab-modal--wide'
    });
}

/* ============================================================
   待重试
   ============================================================ */
async function openSchedulePending(task) {
    const body = el('div', 'ab-pending');
    body.appendChild(el('p', 'ab-loading-line', bt('common.loading', '加载中…')));
    openModal({
        id: 'schedule-pending',
        icon: 'alert',
        title: bt('schedule.pending.title', '待重试 · {name}', {name: task.name || task.id}),
        body,
        widthClass: 'ab-modal--wide'
    });
    let items;
    try {
        const res = await fetch(`${BASE}/api/schedule/tasks/${task.id}/pending`, {credentials: 'same-origin'});
        if (!res.ok) throw await scheduleHttpError(res);
        items = await res.json();
    } catch (e) {
        body.replaceChildren(errorBox(String(e && e.message || bt('common.request-failed', '请求失败')),
            () => openSchedulePending(task)));
        return;
    }
    body.innerHTML = '';
    if (!items || !items.length) {
        body.appendChild(el('p', 'ab-empty-line', bt('schedule.pending.empty', '暂无待重试作品')));
        return;
    }
    items.forEach(item => {
        const row = el('div', 'ab-pending-item card');
        const meta = el('div', 'ab-pending-meta');
        meta.appendChild(el('strong', '', (item.workType || 'illust') + ' · ' + item.workId));
        const sub = el('span', 'ab-muted');
        const reason = localizeScheduleMachineCode(item.reasonCode)
            || bt('schedule.pending.reason-unknown', '原因不可用');
        sub.textContent = summaryJoin([
            bt('schedule.pending.attempts', '已重试 {count} 次', {count: item.attempts ?? 0}),
            item.needsManual ? bt('schedule.pending.needs-manual', '需人工处理') : '',
            reason
        ]);
        meta.appendChild(sub);
        row.appendChild(meta);
        const clearBtn = el('button', 'ab-btn ab-btn--ghost ab-btn--sm', bt('schedule.pending.clear', '清除'));
        clearBtn.type = 'button';
        clearBtn.addEventListener('click', async () => {
            try {
                const res = await fetch(`${BASE}/api/schedule/tasks/${task.id}/pending`, {
                    method: 'DELETE',
                    headers: {'Content-Type': 'application/json'},
                    credentials: 'same-origin',
                    body: JSON.stringify({workType: item.workType, workId: item.workId})
                });
                if (!res.ok) throw await scheduleHttpError(res);
            } catch (e) {
                abToast('error', String(e && e.message || bt('schedule.feedback.failed', '操作失败')));
                return;
            }
            row.remove();
            abToast('success', bt('schedule.pending.cleared', '已清除该条待重试记录'));
            if (!body.querySelector('.ab-pending-item')) {
                body.innerHTML = '';
                body.appendChild(el('p', 'ab-empty-line', bt('schedule.pending.empty', '暂无待重试作品')));
            }
        });
        row.appendChild(clearBtn);
        body.appendChild(row);
    });
}

/* ============================================================
   创建 / 编辑（「存为计划任务」）
   来源定义（definitionJson）由计划来源运行时生成。
   ============================================================ */
function openScheduleEditor(task) {
    const editing = !!task;
    let existingParams = {};
    if (editing) {
        try { existingParams = JSON.parse(task.paramsJson || '{}'); }
        catch (e) {
            abToast('error', bt('schedule.snapshot.error.parse', '任务快照解析失败'));
            return;
        }
    }
    const body = el('div', 'ab-schedule-editor');

    if (editing) {
        body.appendChild(el('p', 'ab-field-note',
            bt('schedule.editor.source-readonly', '编辑态下来源为只读，仅可调整名称与触发方式')));
    }

    body.appendChild(el('label', 'ab-field-label', bt('schedule.editor.name', '任务名称')));
    const nameInput = el('input', 'ab-input');
    nameInput.type = 'text';
    nameInput.value = editing ? (task.name || '') : '';
    nameInput.placeholder = bt('schedule.editor.name.placeholder', '例如：每日收藏追新');
    body.appendChild(nameInput);

    body.appendChild(el('label', 'ab-field-label', bt('schedule.editor.trigger', '触发方式')));
    const triggerSeg = smallSeg([
        ['interval', bt('schedule.trigger.interval', '固定周期')],
        ['cron', bt('schedule.trigger.cron', 'Cron 表达式')]
    ], editing && task.triggerKind === 'cron' ? 'cron' : 'interval', () => {
        const isCron = triggerSeg.querySelector('.ab-seg-item.is-active') === triggerSeg.children[1];
        intervalRow.style.display = isCron ? 'none' : '';
        cronRow.style.display = isCron ? '' : 'none';
    });
    body.appendChild(triggerSeg);

    const intervalRow = el('div');
    intervalRow.appendChild(el('label', 'ab-field-label', bt('schedule.editor.interval', '周期分钟数')));
    const intervalInput = el('input', 'ab-input ab-input--num');
    intervalInput.type = 'number';
    intervalInput.min = '1';
    intervalInput.value = editing && task.intervalMinutes ? task.intervalMinutes : 1440;
    intervalRow.appendChild(intervalInput);
    intervalRow.appendChild(el('p', 'ab-field-note', bt('schedule.editor.interval.help', '从上次运行算起，默认 1440 分钟（一天）')));
    const cronRow = el('div');
    cronRow.style.display = 'none';
    cronRow.appendChild(el('label', 'ab-field-label', bt('schedule.editor.cron', 'Cron 表达式')));
    const cronInput = el('input', 'ab-input ab-input--mono');
    cronInput.type = 'text';
    cronInput.value = editing && task.cronExpr ? task.cronExpr : '';
    cronInput.placeholder = '0 0 3 * * *';
    cronRow.appendChild(cronInput);
    cronRow.appendChild(el('p', 'ab-field-note', bt('schedule.editor.cron.help', '6 位格式含秒，例如 0 0 3 * * * = 每天 03:00:00')));
    if (editing && task.triggerKind === 'cron') {
        intervalRow.style.display = 'none';
        cronRow.style.display = '';
    }
    body.appendChild(intervalRow);
    body.appendChild(cronRow);

    body.appendChild(el('label', 'ab-field-label', bt('schedule.editor.first-limit', '首次抓取上限')));
    const limitInput = el('input', 'ab-input ab-input--num');
    limitInput.type = 'number';
    limitInput.min = '0';
    limitInput.value = editing && Number.isFinite(existingParams.fetchLimit)
        ? Math.max(0, existingParams.fetchLimit) : 0;
    body.appendChild(limitInput);
    body.appendChild(el('p', 'ab-field-note',
        bt('schedule.editor.first-limit.help', '0 = 不限。watermark 语义：首次运行纳入最新 N 个后增量追新；per-run 语义：每次运行最多 N 个并分多轮处理积压')));

    body.appendChild(el('label', 'ab-field-label', bt('schedule.override.proxy', '单独代理')));
    const proxyInput = el('input', 'ab-input');
    proxyInput.type = 'text';
    proxyInput.placeholder = bt('schedule.override.proxy.placeholder-optional', 'host:port（可留空）');
    proxyInput.value = editing ? (task.proxy || '') : '';
    body.appendChild(proxyInput);

    body.appendChild(el('label', 'ab-field-label', bt('schedule.override.credential', '单独凭证')));
    const credInput = el('textarea', 'ab-input');
    credInput.rows = 2;
    credInput.spellcheck = false;
    credInput.placeholder = bt('schedule.override.credential.placeholder', '粘贴该来源所需的凭证（可留空）');
    body.appendChild(credInput);

    const status = el('p', 'ab-field-note');
    body.appendChild(status);

    const actions = el('div', 'ab-cookie-actions');
    const saveBtn = el('button', 'ab-btn ab-btn--primary');
    saveBtn.type = 'button';
    saveBtn.appendChild(el('span', '', editing
        ? bt('schedule.editor.save-edit', '保存修改')
        : bt('schedule.editor.save', '保存计划任务')));
    saveBtn.addEventListener('click', async () => {
        const name = nameInput.value.trim();
        if (!name) {
            status.textContent = bt('schedule.editor.error.name-required', '任务名称必填');
            return;
        }
        const proxy = proxyInput.value.trim();
        if (proxy && !isValidProxyHostPort(proxy)) {
            status.textContent = bt('schedule.override.proxy.invalid', '代理格式无效（应为 host:port）');
            return;
        }
        const firstLimit = Math.max(0, parseInt(limitInput.value, 10) || 0);
        if (firstLimit === 0 && !editing) {
            if (!await abConfirm('schedule.editor.full-confirm',
                '首次抓取上限为 0（全量不限），确认创建？首次运行可能抓取大量作品。')) return;
        }
        const isCron = triggerSeg.querySelectorAll('.ab-seg-item')[1].classList.contains('is-active');
        try {
            const snapshot = editing ? (() => {
                const sourceType = task.sourceType || task.type;
                const lease = altScheduleSources().activationLease(sourceType);
                return {
                    sourceType,
                    activationToken: lease.activationToken,
                    lease,
                    params: Object.assign({}, existingParams, {fetchLimit: firstLimit})
                };
            })() : altCaptureScheduleSource(firstLimit);
            const credential = credInput.value.trim();
            if (credential) await validateScheduleCredential(snapshot.sourceType, credential, editing ? task : null);
            snapshot.lease.assertCurrent();
            const requestBody = {
                name,
                sourceType: snapshot.sourceType,
                activationToken: snapshot.activationToken,
                definitionJson: JSON.stringify(snapshot.params),
                expectedStateVersion: editing ? task.stateVersion : undefined,
                triggerKind: isCron ? 'cron' : 'interval'
            };
            if (isCron) requestBody.cronExpr = cronInput.value.trim();
            else requestBody.intervalMinutes = Math.max(1, parseInt(intervalInput.value, 10) || 1440);
            const res = await fetch(editing
                ? `${BASE}/api/schedule/tasks/${task.id}`
                : `${BASE}/api/schedule/tasks`, {
                method: editing ? 'PUT' : 'POST',
                headers: {'Content-Type': 'application/json'},
                credentials: 'same-origin',
                signal: snapshot.lease.signal,
                body: JSON.stringify(requestBody)
            });
            snapshot.lease.assertCurrent();
            if (!res.ok) throw await scheduleHttpError(res, 'schedule.error.save', '保存失败');
            const saved = await res.json().catch(() => null);
            snapshot.lease.assertCurrent();
            const taskId = editing ? task.id : saved && saved.id;
            if (taskId != null && proxy) await schedulePost({id: taskId}, 'proxy', {proxy});
            if (taskId != null && credential) {
                await bindScheduleCredential(taskId, snapshot.sourceType, credential);
            }
            abToast('success', bt('schedule.editor.saved', '已保存'));
        } catch (e) {
            status.textContent = String(e && e.message || bt('schedule.error.save', '保存失败'));
            return;
        }
        closeDrawer();
        loadScheduleTasks(true);
    });
    actions.appendChild(saveBtn);
    body.appendChild(actions);

    openDrawer({
        id: 'schedule-editor',
        icon: editing ? 'edit' : 'plus',
        title: editing
            ? bt('schedule.editor.title-edit', '编辑计划任务')
            : bt('schedule.editor.title-new', '新建计划任务'),
        body,
        footer: null
    });
}

window.PixivBatchAlt.schedule = Object.assign(window.PixivBatchAlt.schedule, {
    enterScheduleMode, loadScheduleTasks, startSchedulePolling, stopSchedulePolling,
    renderScheduleMode, renderScheduleTaskList, scheduleStatusLight,
    openScheduleEditor, openScheduleSnapshot, openScheduleOverride, openSchedulePending
});
