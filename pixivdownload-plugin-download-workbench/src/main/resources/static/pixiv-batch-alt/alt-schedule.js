'use strict';
/* ============================================================
   alt-schedule — 计划任务（仅管理员）
   任务列表消费来源贡献和调度状态；展示、动作与编辑分别由同目录模块提供。
   ============================================================ */
let schedulePollTimer = null;
let scheduleQueuePollTimer = null;
const scheduleView = {query: '', filter: 'all'};

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
    releaseAllScheduleQueues();
}

async function loadScheduleTasks(quiet) {
    try { await altScheduleSources()?.refresh?.(false); } catch (e) { /* 保留持久化展示，来源动作仍由运行时校验。 */ }
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
   状态与来源展示
   ============================================================ */
function scheduleTaskCredentialPresentation(task) {
    const runtime = altScheduleSources();
    if (!runtime || !task || typeof runtime.credentialTaskPresentation !== 'function') return null;
    try {
        return runtime.credentialTaskPresentation(
            task.sourceType || task.type, task, {task});
    } catch (e) {
        return null;
    }
}

function scheduleStatusLabel(task) {
    if (task && task.suspendReason === 'USER_ACTION_REQUIRED') return bt('batch:path.overflow.waiting', null);
    const credentialPresentation = scheduleTaskCredentialPresentation(task);
    if (credentialPresentation && credentialPresentation.statusLabel) {
        return credentialPresentation.statusLabel;
    }
    const code = typeof task === 'string' ? task : task && task.lastStatus;
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

function localizeScheduleMachineCode(value, sourceType) {
    if (value === 'DOWNLOAD_PATH_ACTION_REQUIRED') return bt('batch:path.overflow.waiting', null);
    const code = safeScheduleMachineCode(value);
    if (!code) return null;
    if (code.startsWith('schedule.')) {
        const translated = bt(code, '');
        return translated && translated !== code ? translated : null;
    }
    try {
        const runtime = altScheduleSources();
        const descriptor = runtime && runtime.descriptor(sourceType);
        const presentation = descriptor && descriptor.presentation;
        const namespace = presentation && presentation.displayNamespace;
        if (typeof namespace === 'string'
                && /^[a-z][a-z0-9._-]{0,63}$/.test(namespace)
                && code.startsWith(`${namespace}.`)
                && typeof pageI18n !== 'undefined' && pageI18n) {
            const translated = pageI18n.t(
                `${namespace}:${code.slice(namespace.length + 1)}`, '');
            return translated && translated !== code ? translated : null;
        }
    } catch (e) {
        return null;
    }
    return null;
}

function scheduleFailureReason(t) {
    return t ? localizeScheduleMachineCode(t.lastMessage, t.sourceType || t.type) : null;
}

/**
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
    if (t.suspendReason === 'USER_ACTION_REQUIRED') {
        return {tone: 'yellow', live: false, text: bt('batch:path.overflow.waiting', null)};
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
        const reason = localizeScheduleMachineCode(t.suspendCode, t.sourceType || t.type);
        return {
            tone: 'red',
            live: false,
            text: reason || bt('schedule.light.migration-error', '任务数据需要修复，无法运行')
        };
    }
    const credentialPresentation = scheduleTaskCredentialPresentation(t);
    if (credentialPresentation && credentialPresentation.lightTone
            && credentialPresentation.lightText) {
        return {
            tone: credentialPresentation.lightTone,
            live: false,
            text: credentialPresentation.lightText
        };
    }
    // 挂起态优先于中断结果：挂起任务不会被自动重排，不能显示「已重新排期补齐」。
    if (t.suspendReason && t.suspendReason !== 'MANUAL') {
        const reason = localizeScheduleMachineCode(t.suspendCode, t.sourceType || t.type);
        return {
            tone: 'red',
            live: false,
            text: reason || bt('schedule.light.suspended', '任务已挂起，等待恢复')
        };
    }
    if (t.suspendReason === 'MANUAL' || t.lastStatus === 'PAUSED') {
        return {tone: 'gray', live: false, text: bt('schedule.light.paused', '已手动暂停')};
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

    const toolbar = el('div', 'ab-schedule-toolbar');
    const search = el('input', 'ab-input ab-schedule-search');
    search.id = 'abScheduleSearch';
    search.type = 'search';
    search.value = scheduleView.query;
    search.placeholder = bt('schedule.manage.search', '搜索计划名称或来源');
    search.setAttribute('aria-label', search.placeholder);
    search.addEventListener('input', () => {
        scheduleView.query = search.value;
        renderScheduleTaskList();
    });
    const filters = el('div', 'ab-seg ab-schedule-filters');
    filters.setAttribute('role', 'group');
    filters.setAttribute('aria-label', bt('schedule.manage.filter', '计划状态'));
    for (const [value, label] of [['all', '全部'], ['running', '运行中'], ['paused', '已暂停'], ['attention', '需要关注']]) {
        const button = el('button', 'ab-seg-item', bt('schedule.manage.' + value, label));
        button.type = 'button';
        button.id = 'abScheduleFilter-' + value;
        button.dataset.filter = value;
        button.addEventListener('click', () => {
            scheduleView.filter = value;
            renderScheduleTaskList();
        });
        filters.appendChild(button);
    }
    toolbar.append(search, filters);
    panel.appendChild(toolbar);
    const count = el('p', 'ab-schedule-count ab-muted');
    count.id = 'abScheduleCount';
    count.setAttribute('role', 'status');
    toolbar.appendChild(count);

    const list = el('div', 'ab-schedule-list');
    list.id = 'abScheduleList';
    panel.appendChild(list);
    if (!scheduleState.loaded) {
        list.appendChild(loadingGrid(bt('common.loading', '加载中…')));
    } else {
        renderScheduleTaskList();
    }
}

function scheduleVisibleTasks(tasks, query, filter) {
    const term = query.trim().toLocaleLowerCase();
    return tasks.filter(task => {
        if (term && ![task.name, scheduleTypeLabel(task), scheduleSourceSummary(task), task.sourceType || task.type]
            .join(' ').toLocaleLowerCase().includes(term)) return false;
        if (filter === 'running') return ['RUNNING', 'QUEUED', 'CANCEL_REQUESTED'].includes(task.runState);
        if (filter === 'paused') return !task.enabled || task.suspendReason === 'MANUAL'
            || task.lastStatus === 'PAUSED';
        if (filter === 'attention') return ['red', 'yellow'].includes(scheduleStatusLight(task).tone);
        return true;
    });
}

function renderScheduleTaskList() {
    const bannerHost = document.getElementById('abScheduleBanners');
    const list = document.getElementById('abScheduleList');
    if (!list) return;
    renderScheduleCredentialPolicyBanners(bannerHost);
    const focused = list.contains(document.activeElement) ? document.activeElement.id : null;
    const tasks = scheduleVisibleTasks(scheduleState.tasks, scheduleView.query, scheduleView.filter);
    document.querySelectorAll('.ab-schedule-filters button').forEach(button => {
        const selected = button.dataset.filter === scheduleView.filter;
        button.classList.toggle('is-active', selected);
        button.setAttribute('aria-pressed', String(selected));
    });
    const count = document.getElementById('abScheduleCount');
    if (count) count.textContent = scheduleState.error ? ''
        : bt('schedule.manage.count', '{visible} / {total} 个计划', {visible: tasks.length, total: scheduleState.tasks.length});

    // 未变化的行和队列岛保留原节点，轮询不会关闭菜单或打断键盘操作。
    const existing = new Map(Array.from(list.children, row => [row.dataset.taskId, row]));
    const retained = new Set(tasks.map(task => String(task.id)));
    for (const [id, row] of existing) {
        if (scheduleState.error || !retained.has(id)) {
            const taskId = row._scheduleTaskId;
            if (taskId != null) scheduleQueueVue()?.unmountScheduleQueue?.(taskId);
            row.remove();
        }
    }
    if (scheduleState.error) {
        releaseAllScheduleQueues();
        list.appendChild(errorBox(scheduleState.error, () => loadScheduleTasks(false)));
        return;
    }
    if (!tasks.length) {
        const empty = el('div', 'ab-empty');
        empty.appendChild(abIconEl('clock'));
        empty.appendChild(el('p', '', scheduleState.tasks.length
            ? bt('schedule.manage.empty', '没有匹配的计划，试试其他关键词或状态。')
            : bt('schedule.empty', '暂无计划任务，点击右上角「新建计划任务」开始')));
        list.appendChild(empty);
    }
    tasks.forEach((task, index) => {
        const old = existing.get(String(task.id));
        const runtime = altScheduleSources();
        const signature = JSON.stringify([task, scheduleSourceSummary(task), scheduleNextLabel(task),
            !!runtime?.isAvailable(task.sourceType || task.type), scheduleTaskCredentialUi(task)]);
        if (old && old._scheduleSignature === signature) {
            if (list.children[index] !== old) list.insertBefore(old, list.children[index] || null);
            return;
        }
        const menuOpen = old?.querySelector('.ab-schedule-more')?.open;
        const retainedResults = old?.querySelector('.ab-schedule-results');
        const row = scheduleTaskCard(task);
        if (retainedResults) {
            retainedResults._scheduleTask = task;
            retainedResults.querySelector('.ab-lamp').replaceWith(row.querySelector('.ab-lamp'));
            row.querySelector('.ab-schedule-results').replaceWith(retainedResults);
        }
        row._scheduleSignature = signature;
        row._scheduleTaskId = task.id;
        row.querySelector('.ab-schedule-more').open = !!menuOpen;
        if (old?.isConnected) old.replaceWith(row);
        else list.appendChild(row);
        if (list.children[index] !== row) list.insertBefore(row, list.children[index] || null);
        hydrateIcons(row);
        if (scheduleState.expandedQueues.has(task.id)) loadScheduleQueue(task);
    });
    if (focused) document.getElementById(focused)?.focus({preventScroll: true});
    startScheduleQueuePolling();
}

function scheduleCredentialPolicyGroups() {
    try {
        const groups = altScheduleSources()?.credentialPolicyGroups?.(scheduleState.tasks, {mode: state.mode});
        return Array.isArray(groups) ? groups : [];
    } catch (e) { return []; }
}

function renderScheduleCredentialPolicyBanners(host) {
    if (!host) return;
    const groups = scheduleCredentialPolicyGroups();
    const signature = JSON.stringify(groups);
    if (host._credentialPolicySignature === signature) return;
    host._credentialPolicySignature = signature;
    host.replaceChildren();
    groups.forEach(group => {
        const banner = el('div', 'ab-overuse');
        const head = el('div', 'ab-overuse-head');
        head.appendChild(abIconEl('alert'));
        head.appendChild(el('strong', '', group.title));
        banner.appendChild(head);
        banner.appendChild(el('p', 'ab-muted', group.description));
        const actions = el('div', 'ab-overuse-actions');
        group.actions.forEach(action => {
            const tone = action.tone === 'danger' ? 'danger' : action.tone === 'primary' ? 'primary' : 'ghost';
            const button = el('button', 'ab-btn ab-btn--sm ab-btn--' + tone, action.label);
            button.type = 'button';
            button.addEventListener('click', () => applyScheduleCredentialPolicyAction(group, action, button));
            actions.appendChild(button);
        });
        banner.appendChild(actions);
        host.appendChild(banner);
    });
}

async function applyScheduleCredentialPolicyAction(group, action, button) {
    if (action.confirmMessage && !await abConfirm('schedule.credential-policy.confirm',
        action.confirmMessage, null, {danger: action.tone === 'danger'})) return;
    const parameters = {};
    if (action.prompt) {
        const prompt = action.prompt;
        const input = await abPrompt('schedule.credential-policy.prompt', prompt.message, null, {
            value: prompt.defaultValue, inputType: prompt.inputType, min: prompt.min, step: prompt.step
        });
        if (input == null) return;
        let value = prompt.inputType === 'number' ? Number(input) : String(input);
        if (prompt.inputType === 'number' && !Number.isFinite(value)) {
            abToast('error', bt('schedule.error.credential-policy-parameter', '输入值无效，请重新输入'));
            return;
        }
        if (prompt.inputType === 'number' && Number.isFinite(prompt.min)) value = Math.max(value, prompt.min);
        parameters[prompt.parameterName] = value;
    }
    const runtime = altScheduleSources();
    if (!runtime?.applyCredentialPolicyAction) return;
    button.disabled = true;
    try {
        const result = await runtime.applyCredentialPolicyAction(group.sourceType, {
            identity: group.identity, actionId: action.actionId, parameters
        }, {mode: state.mode});
        abToast(result?.ok ? 'success' : 'error', result?.ok
            ? bt('schedule.status.credential-policy-applied', '凭证策略操作已应用')
            : result?.error || bt('schedule.error.credential-policy-action', '凭证策略操作失败'));
    } catch (e) {
        abToast('error', bt('schedule.error.credential-policy-action', '凭证策略操作失败'));
    } finally {
        if (button.isConnected) button.disabled = false;
        await loadScheduleTasks(true);
    }
}

function scheduleTaskCard(task) {
    const light = scheduleStatusLight(task);
    const credentialUi = scheduleTaskCredentialUi(task);
    const sourceType = task.sourceType || task.type;
    const runtime = altScheduleSources();
    const sourceEditable = task.sourceAvailable !== false
        && runtime && runtime.isAvailable(sourceType);
    const card = el('article', 'ab-schedule-row');
    card.dataset.taskId = String(task.id);
    card.setAttribute('aria-labelledby', 'abScheduleName-' + task.id);
    const busy = ['RUNNING', 'QUEUED', 'CANCEL_REQUESTED'].includes(task.runState);
    const suspended = !!task.suspendReason
        || task.lastStatus === 'PAUSED' || task.lastStatus === 'OVERUSE_PAUSED';

    const head = el('div', 'ab-schedule-head');
    const titleWrap = el('div', 'ab-schedule-title');
    const name = el('strong', '', task.name || ('#' + task.id));
    name.id = 'abScheduleName-' + task.id;
    titleWrap.appendChild(name);
    const subtitle = el('span', 'ab-schedule-source ab-muted', scheduleSourceSummary(task));
    titleWrap.appendChild(subtitle);
    head.appendChild(titleWrap);
    const timing = el('div', 'ab-schedule-timing');
    const cadence = el('span', 'ab-schedule-cadence', scheduleCadenceLabel(task));
    cadence.title = scheduleTriggerLabel(task);
    timing.append(cadence, el('span', 'ab-schedule-next ab-muted', scheduleNextLabel(task)));
    titleWrap.appendChild(timing);

    const enabled = switchControl(task.enabled, async value => {
        input.disabled = true;
        input.setAttribute('aria-busy', 'true');
        try {
            const saved = await scheduleSetEnabled(task, value);
            input.checked = saved ? value : !!task.enabled;
        } finally {
            input.disabled = busy;
            input.removeAttribute('aria-busy');
        }
    }, busy);
    const enableControl = el('div', 'ab-schedule-enabled');
    const input = enabled.querySelector('input');
    input.id = 'abScheduleEnabled-' + task.id;
    const enableLabel = el('label', '', bt('schedule.manage.enabled', '启用计划'));
    enableLabel.id = 'abScheduleEnabledLabel-' + task.id;
    enableLabel.setAttribute('for', input.id);
    enableControl.append(enableLabel, enabled);
    input.setAttribute('aria-labelledby', enableLabel.id + ' ' + name.id);
    if (busy) enabled.title = bt('schedule.action-disabled.busy', '运行 / 排队中不可操作');
    card.append(head, enableControl);

    const actions = el('div', 'ab-schedule-actions');
    actions.appendChild(scheduleActionBtn('play', 'schedule.actions.run', '立即运行',
        busy || !task.enabled || suspended,
        busy
            ? bt('schedule.action-disabled.busy', '运行 / 排队中不可操作')
            : !task.enabled
                ? bt('schedule.action-disabled.disabled', '已停用')
                : bt('schedule.action-disabled.suspended', '插件能力不可用 / 暂停中'),
        () => scheduleVerb(task, 'run')));
    if (suspended && (!task.suspendReason || ['MANUAL', 'USER_ACTION_REQUIRED'].includes(task.suspendReason))) {
        actions.appendChild(scheduleActionBtn('refresh', 'schedule.actions.resume', '恢复', busy, '',
            () => scheduleVerb(task, 'resume')));
    } else {
        actions.appendChild(scheduleActionBtn('pause', 'schedule.actions.pause', '暂停',
            !task.enabled || suspended || task.runState === 'CANCEL_REQUESTED',
            bt('schedule.action-disabled.not-running', '未在运行或已暂停'),
            () => scheduleVerb(task, 'pause')));
    }
    actions.appendChild(scheduleActionBtn('edit', 'schedule.actions.edit', '编辑', !sourceEditable,
        sourceEditable ? '' : bt('schedule.error.source-editor-unavailable', '计划任务来源编辑器当前不可用'),
        () => openScheduleEditor(task)));
    const more = el('details', 'ab-schedule-more');
    more.name = 'schedule-actions';
    const summary = el('summary', 'ab-btn ab-btn--ghost ab-btn--sm');
    summary.textContent = bt('workspace.more', '更多');
    summary.id = 'abScheduleMore-' + task.id;
    summary.setAttribute('aria-label', summary.textContent + ' · ' + (task.name || task.id));
    const menu = el('div', 'ab-schedule-menu');
    menu.appendChild(scheduleActionBtn('eye', 'schedule.actions.snapshot', '任务快照', false, '',
        () => openScheduleSnapshot(task)));
    if (credentialUi.showOverride) {
        menu.appendChild(scheduleActionBtn('key', 'schedule.actions.override', '代理 / 凭证', false, '',
            () => openScheduleOverride(task)));
    }
    menu.appendChild(scheduleActionBtn('alert', 'schedule.actions.pending', '待重试', false, '',
        () => openSchedulePending(task)));
    menu.appendChild(scheduleActionBtn('trash', 'schedule.actions.delete', '删除', busy,
        busy ? bt('schedule.action-disabled.busy', '运行 / 排队中不可操作') : '',
        () => deleteScheduleTask(task), true));

    more.append(summary, menu);
    actions.appendChild(more);
    card.appendChild(actions);
    card.appendChild(scheduleResults(task, light));
    card.querySelectorAll('button').forEach(button => {
        if (!button.id) button.id = 'abScheduleAction-' + task.id + '-' + button.dataset.action;
    });
    return card;
}

function scheduleActionBtn(icon, labelKey, labelFallback, disabled, disabledReason, onClick, danger) {
    const btn = el('button', 'ab-btn ab-btn--ghost ab-btn--sm' + (danger ? ' ab-btn--danger-text' : ''));
    btn.type = 'button';
    btn.appendChild(abIconEl(icon));
    btn.appendChild(el('span', '', bt(labelKey, labelFallback)));
    btn.dataset.action = labelKey;
    btn.disabled = !!disabled;
    if (disabled && disabledReason) btn.title = disabledReason;
    btn.addEventListener('click', async () => {
        if (btn.disabled) return;
        const menu = btn.closest('.ab-schedule-more');
        if (menu) { menu.open = false; menu.querySelector('summary').focus(); }
        btn.disabled = true;
        try { await onClick(); }
        finally { btn.disabled = !!disabled; }
    });
    return btn;
}

function bindScheduleMenus() {
    document.addEventListener('click', event => {
        document.querySelectorAll('.ab-schedule-more[open]').forEach(menu => {
            if (!menu.contains(event.target)) menu.open = false;
        });
    });
    document.addEventListener('keydown', event => {
        if (event.key !== 'Escape' || document.querySelector('dialog[open]')) return;
        document.querySelectorAll('.ab-schedule-more[open]').forEach(menu => {
            menu.open = false;
            menu.querySelector('summary').focus();
            event.preventDefault();
        });
    });
}
