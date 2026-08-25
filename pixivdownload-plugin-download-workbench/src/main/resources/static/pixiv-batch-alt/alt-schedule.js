'use strict';
/* ============================================================
   alt-schedule — 计划任务（仅管理员）
   状态灯、任务列表和类型标签映射逐字移植 batch-schedule.js 语义；
   任务动作与编辑入口由同目录职责模块提供。
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
