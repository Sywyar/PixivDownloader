'use strict';
/* ============================================================
   alt-schedule — 计划任务（仅管理员）
   状态灯、任务列表和类型标签映射逐字移植 batch-schedule.js 语义；
   任务动作与编辑入口由同目录职责模块提供。
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
    if (t.lastStatus === 'PAUSED') {
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
    panel.appendChild(count);

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
        if (term && ![task.name, scheduleTypeLabel(task), task.sourceType || task.type]
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
    renderOveruseBanners(bannerHost);
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
        const signature = JSON.stringify([task, scheduleState.expandedQueues.has(task.id),
            !!runtime?.isAvailable(task.sourceType || task.type), scheduleTaskCredentialUi(task)]);
        if (old && old._scheduleSignature === signature) {
            if (list.children[index] !== old) list.insertBefore(old, list.children[index] || null);
            return;
        }
        const menuOpen = old?.querySelector('.ab-schedule-more')?.open;
        scheduleQueueVue()?.unmountScheduleQueue?.(task.id);
        const row = scheduleTaskCard(task);
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

function scheduleTaskCard(task) {
    const light = scheduleStatusLight(task);
    const kind = scheduleTaskKind(task);
    const credentialUi = scheduleTaskCredentialUi(task);
    const sourceType = task.sourceType || task.type;
    const runtime = altScheduleSources();
    const sourceEditable = task.sourceAvailable !== false
        && runtime && runtime.isAvailable(sourceType);
    const card = el('article', 'ab-schedule-row');
    card.dataset.taskId = String(task.id);
    card.setAttribute('aria-labelledby', 'abScheduleName-' + task.id);

    const head = el('div', 'ab-schedule-head');
    const titleWrap = el('div', 'ab-schedule-title');
    const name = el('strong', '', task.name || ('#' + task.id));
    name.id = 'abScheduleName-' + task.id;
    titleWrap.appendChild(name);
    const subtitle = el('span', 'ab-muted');
    subtitle.textContent = summaryJoin([
        scheduleTypeLabel(task),
        kind ? scheduleKindLabel(kind) : null, scheduleTriggerLabel(task)
    ]);
    titleWrap.appendChild(subtitle);
    head.appendChild(titleWrap);
    const lamp = el('span', 'ab-lamp ab-lamp--' + light.tone + (light.live ? ' is-live' : ''));
    lamp.appendChild(el('span', 'ab-lamp-dot'));
    lamp.appendChild(el('span', '', light.text));
    card.appendChild(head);

    const nextRun = scheduleMetaItem('schedule.meta.next-run', '下次运行',
        task.enabled && !task.suspendReason && !['PAUSED', 'OVERUSE_PAUSED'].includes(task.lastStatus)
            ? fmtScheduleTime(task.nextRunTime) : '—');
    nextRun.classList.add('ab-schedule-next');
    card.appendChild(nextRun);

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
    if (suspended && (!task.suspendReason || ['MANUAL', 'USER_ACTION_REQUIRED'].includes(task.suspendReason))) {
        actions.appendChild(scheduleActionBtn('refresh', 'schedule.actions.resume', '恢复', busy, '',
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
    const footer = el('div', 'ab-schedule-row-footer');
    footer.appendChild(lamp);
    card.appendChild(footer);

    // 本轮队列详情（可折叠）
    const queueToggle = el('button', 'ab-schedule-queue-toggle');
    queueToggle.type = 'button';
    queueToggle.id = 'abScheduleQueueToggle-' + task.id;
    queueToggle.setAttribute('aria-controls', 'abScheduleQueue-' + task.id);
    const expanded = scheduleState.expandedQueues.has(task.id);
    queueToggle.setAttribute('aria-expanded', String(expanded));
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
    footer.appendChild(queueToggle);
    if (expanded) {
        const queueBox = el('div', 'ab-schedule-queue');
        queueBox.id = 'abScheduleQueue-' + task.id;
        queueBox.appendChild(el('p', 'ab-loading-line', bt('common.loading', '加载中…')));
        card.appendChild(queueBox);
    }
    card.querySelectorAll('button').forEach(button => {
        if (!button.id) button.id = 'abScheduleAction-' + task.id + '-' + button.dataset.action;
    });
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
    btn.dataset.action = labelKey;
    btn.disabled = !!disabled;
    if (disabled && disabledReason) btn.title = disabledReason;
    btn.addEventListener('click', () => {
        const menu = btn.closest('.ab-schedule-more');
        if (menu) { menu.open = false; menu.querySelector('summary').focus(); }
        onClick();
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
