'use strict';

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
