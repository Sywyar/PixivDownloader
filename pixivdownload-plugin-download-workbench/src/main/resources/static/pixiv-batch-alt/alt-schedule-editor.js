'use strict';

function scheduleCredentialInput() {
    const input = el('input', 'ab-input');
    input.type = 'password';
    input.autocomplete = 'new-password';
    input.spellcheck = false;
    let pasted = null;
    let displayed = '';
    input.setCredential = value => {
        pasted = String(value);
        input.value = pasted;
        displayed = input.value;
    };
    input.readCredential = () => pasted !== null && input.value === displayed ? pasted : input.value;
    input.addEventListener('input', () => { pasted = null; });
    input.addEventListener('paste', event => {
        const value = event.clipboardData?.getData('text');
        // 密码输入框会删除换行，保留完整粘贴的多行凭证格式。
        if (value && /[\r\n]/.test(value)) {
            event.preventDefault();
            input.setCredential(value);
        }
    });
    return input;
}

function scheduleCredentialToggle(input) {
    const button = el('button', 'ab-btn ab-btn--ghost ab-btn--sm');
    button.type = 'button';
    const update = () => {
        button.textContent = input.type === 'password'
            ? bt('cookie.toggle.show', '显示') : bt('cookie.toggle.hide', '隐藏');
        button.setAttribute('aria-pressed', String(input.type === 'text'));
    };
    button.addEventListener('click', () => {
        input.type = input.type === 'password' ? 'text' : 'password';
        update();
    });
    update();
    return button;
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
    let lease;
    try {
        lease = altScheduleSources().activationLease(sourceType);
        if (task.sourceAvailable === false || task.sourceActivationToken !== lease.activationToken
            || !capabilities.supportsCredential && !capabilities.supportsProxy) {
            throw new Error(bt('schedule.error.concurrent-change'));
        }
    } catch (e) {
        abToast('error', bt('schedule.error.source-editor-unavailable', '计划任务来源编辑器当前不可用'));
        return;
    }
    let saving = false;
    let useSavedCredential = false;
    const bound = task.credentialPolicy?.bound === true;
    if (presentation.modalIntro) {
        body.appendChild(el('p', 'ab-field-note', presentation.modalIntro));
    }

    // —— 单独代理 ——
    const proxyFields = el('div');
    proxyFields.hidden = !capabilities.supportsProxy;
    body.appendChild(proxyFields);
    proxyFields.appendChild(el('h4', 'ab-settings-group', bt('schedule.override.proxy', '单独代理')));
    const proxyInput = el('input', 'ab-input');
    proxyInput.type = 'text';
    proxyInput.placeholder = bt('schedule.override.proxy.placeholder', 'host:port');
    proxyInput.value = task.proxy || '';
    proxyFields.appendChild(proxyInput);
    proxyFields.appendChild(el('p', 'ab-field-note',
        presentation.proxyHint || bt('schedule.override.proxy.help', '该任务运行时使用此代理；清除则用全局代理。')));

    // —— 单独凭证 ——
    const credentialFields = el('div');
    credentialFields.hidden = !capabilities.supportsCredential;
    body.appendChild(credentialFields);
    credentialFields.appendChild(el('h4', 'ab-settings-group', bt('schedule.override.credential', '单独凭证')));
    const credentialState = el('p', 'ab-field-note',
        bound
            ? bt('schedule.override.credential.bound', '已绑定凭证（不回显）')
            : bt('schedule.override.credential.none', '尚未绑定凭证，将以受限模式运行'));
    credentialFields.appendChild(credentialState);
    const cookieInput = scheduleCredentialInput();
    cookieInput.placeholder = bound
        ? presentation.boundPlaceholder || bt('schedule.override.credential.bound-placeholder', '凭证已绑定且不回显；留空保持不变')
        : presentation.placeholder || bt('schedule.override.credential.placeholder', '粘贴该来源所需的凭证');
    const credentialPlaceholder = cookieInput.placeholder;
    const clearSavedSelection = () => {
        useSavedCredential = false;
        cookieInput.placeholder = credentialPlaceholder;
    };
    cookieInput.addEventListener('input', clearSavedSelection);
    cookieInput.addEventListener('paste', clearSavedSelection);
    credentialFields.appendChild(cookieInput);
    credentialFields.appendChild(scheduleCredentialToggle(cookieInput));
    if (presentation.credentialHint) {
        credentialFields.appendChild(el('p', 'ab-field-note', presentation.credentialHint));
    }
    const useSavedBtn = el('button', 'ab-btn ab-btn--ghost ab-btn--sm',
        presentation.savedCredentialLabel || bt('schedule.override.use-saved', '使用当前保存的凭证'));
    useSavedBtn.type = 'button';
    useSavedBtn.addEventListener('click', () => {
        if (saving) return;
        try {
            lease.assertCurrent();
            useSavedCredential = true;
            cookieInput.setCredential('');
            cookieInput.placeholder = presentation.savedSelectionPlaceholder || `•••••••• · ${useSavedBtn.textContent}`;
            status.textContent = '';
        } catch (e) {
            status.textContent = bt('schedule.error.source-editor-unavailable', '计划任务来源编辑器当前不可用');
        }
    });
    credentialFields.appendChild(useSavedBtn);

    const status = el('p', 'ab-field-note');
    status.setAttribute('role', 'status');
    body.appendChild(status);
    proxyInput.setAttribute('aria-label', bt('schedule.override.proxy', '单独代理'));
    cookieInput.setAttribute('aria-label', bt('schedule.override.credential', '单独凭证'));

    async function applyOverride(action) {
        if (saving) return;
        saving = true;
        body.setAttribute('aria-busy', 'true');
        const controls = [...body.querySelectorAll('button, input')].map(node => [node, node.disabled]);
        controls.forEach(([node]) => { node.disabled = true; });
        try {
            lease.assertCurrent();
            if (await action() === false) return;
            lease.assertCurrent();
            abToast('success', bt('schedule.feedback.saved', '操作成功'));
            if (body.isConnected) closeModal();
            loadScheduleTasks();
        } catch (e) {
            status.textContent = lease.isCurrent()
                ? String(e && e.message || bt('schedule.feedback.failed', '操作失败'))
                : bt('schedule.error.source-editor-unavailable', '计划任务来源编辑器当前不可用');
        } finally {
            saving = false;
            body.removeAttribute('aria-busy');
            controls.forEach(([node, disabled]) => { node.disabled = disabled; });
        }
    }

    const actions = el('div', 'ab-cookie-actions');
    const saveBtn = el('button', 'ab-btn ab-btn--primary');
    saveBtn.type = 'button';
    saveBtn.appendChild(el('span', '', bt('common.save', '保存')));
    saveBtn.addEventListener('click', () => applyOverride(async () => {
        const proxy = capabilities.supportsProxy ? proxyInput.value.trim() : '';
        const cookie = capabilities.supportsCredential ? cookieInput.readCredential().trim() : '';
        if (proxy && !isValidProxyHostPort(proxy)) {
            status.textContent = bt('schedule.override.proxy.invalid', '代理格式无效（应为 host:port）');
            return false;
        }
        if (cookie) await validateScheduleCredential(sourceType, cookie, task);
        lease.assertCurrent();
        if (proxy && proxy !== task.proxy) await schedulePost(task, 'proxy', {proxy}, lease.signal);
        lease.assertCurrent();
        if (capabilities.supportsCredential && (cookie || useSavedCredential)) {
            await bindScheduleCredential(task.id, sourceType, cookie, task);
        }
    }));
    const clearProxyBtn = el('button', 'ab-btn ab-btn--danger-ghost');
    clearProxyBtn.type = 'button';
    clearProxyBtn.appendChild(el('span', '', bt('schedule.override.clear-proxy', '清除专用代理')));
    clearProxyBtn.hidden = !capabilities.supportsProxy;
    clearProxyBtn.addEventListener('click', () => applyOverride(async () => {
        if (!capabilities.supportsProxy) return false;
        if (!await abConfirm('schedule.override.clear-proxy.confirm',
            '确认清除该任务的专用代理（回退全局代理）？', null, {danger: true})) return false;
        lease.assertCurrent();
        await schedulePost(task, 'proxy', {proxy: null}, lease.signal);
    }));
    actions.appendChild(saveBtn);
    actions.appendChild(clearProxyBtn);
    if (capabilities.supportsCredential && bound) {
        const clearCredentialBtn = el('button', 'ab-btn ab-btn--danger-ghost');
        clearCredentialBtn.type = 'button';
        clearCredentialBtn.appendChild(el('span', '',
            bt('schedule.override.clear-credential', '清除专用凭证')));
        clearCredentialBtn.addEventListener('click', () => applyOverride(async () => {
            if (!await abConfirm('schedule.override.clear-credential.confirm',
                '确认清除该任务已绑定的专用凭证？', null, {danger: true})) return false;
            lease.assertCurrent();
            const result = await altScheduleSources().revokeCredential(sourceType, task.id, {task});
            if (!result?.ok) {
                throw new Error(result?.error || bt('schedule.error.revoke-credential', '解除来源凭证失败'));
            }
        }));
        actions.appendChild(clearCredentialBtn);
    }
    body.appendChild(actions);

    openModal({
        id: 'schedule-override',
        icon: 'key',
        title: presentation.modalTitle
            || bt('schedule.override.title', '专用代理 / 凭证 · {name}', {name: task.name || task.id}),
        body,
        beforeClose: () => !saving,
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
        meta.appendChild(el('strong', '', scheduleKindLabel(item.workType) + ' · ' + item.workId));
        const sub = el('span', 'ab-muted');
        const reason = schedulePendingReasonText(item, task.sourceType || task.type);
        sub.textContent = summaryJoin([
            bt('schedule.pending.attempts', '已重试 {count} 次', {count: item.attempts ?? 0}),
            item.needsManual ? bt('schedule.pending.needs-manual', '需人工处理') : '',
            reason
        ]);
        meta.appendChild(sub);
        row.appendChild(meta);
        const clearBtn = el('button', 'ab-btn ab-btn--ghost ab-btn--sm', bt('schedule.pending.clear', '清除'));
        clearBtn.type = 'button';
        const busy = () => ['RUNNING', 'QUEUED', 'CANCEL_REQUESTED'].includes(
            (scheduleState.tasks.find(current => current.id === task.id) || task).runState);
        clearBtn.disabled = busy();
        if (clearBtn.disabled) clearBtn.title = bt('schedule.disabled.busy', '任务运行 / 排队中，暂不可操作');
        clearBtn.addEventListener('click', async () => {
            if (busy()) return;
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

function schedulePendingReasonText(item, sourceType) {
    const unavailable = () => bt('schedule.pending.reason-unavailable', '失败原因不可用');
    const hasDetail = item.reasonDetailJson != null && String(item.reasonDetailJson).trim();
    if (hasDetail) {
        try {
            const detail = JSON.parse(item.reasonDetailJson);
            const code = typeof detail === 'string' ? detail : detail && typeof detail === 'object'
                ? [detail.message, detail.reason, detail.reasonCode, detail.legacyReason]
                    .find(value => typeof value === 'string' && value.trim()) : null;
            if (code) return localizeScheduleMachineCode(code, sourceType) || unavailable();
        } catch (e) { return unavailable(); }
    }
    if (item.reasonCode != null && String(item.reasonCode).trim()) {
        return localizeScheduleMachineCode(item.reasonCode, sourceType) || unavailable();
    }
    return hasDetail ? unavailable() : '';
}

/* ============================================================
   创建 / 编辑（「存为计划任务」）
   来源定义（definitionJson）由计划来源运行时生成。
   ============================================================ */
function openScheduleEditor(task) {
    const editing = !!task;
    let savedTask = task;
    let existingParams = {};
    let session = null;
    if (editing) {
        try { existingParams = JSON.parse(task.paramsJson || '{}'); }
        catch (e) {
            abToast('error', bt('schedule.snapshot.error.parse', '任务快照解析失败'));
            return;
        }
        try {
            session = beginScheduleEdit(task);
        } catch (e) {
            abToast('error', bt('schedule.error.source-editor-unavailable', '计划任务来源编辑器当前不可用'));
            return;
        }
    }
    const body = el('div', 'ab-schedule-editor');
    let dirty = false;
    let saving = false;
    let showSettings = null;
    let refreshEditSummary = () => {};
    body.addEventListener('input', () => { dirty = true; refreshEditSummary(); });
    body.addEventListener('change', () => { dirty = true; refreshEditSummary(); });

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
        dirty = true;
        const isCron = triggerSeg.querySelector('.ab-seg-item.is-active') === triggerSeg.children[1];
        intervalRow.style.display = isCron ? 'none' : '';
        cronRow.style.display = isCron ? '' : 'none';
        refreshEditSummary();
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

    const limitLabel = el('label', 'ab-field-label', bt('batch:schedule.field.fetch-limit', '抓取上限：'));
    body.appendChild(limitLabel);
    const limitInput = el('input', 'ab-input ab-input--num');
    limitInput.type = 'number';
    limitInput.min = '0';
    limitInput.value = editing && Number.isFinite(existingParams.fetchLimit)
        ? Math.max(0, existingParams.fetchLimit) : 0;
    body.appendChild(limitInput);
    const limitHint = el('p', 'ab-field-note');
    body.appendChild(limitHint);
    const limitKey = (snapshot, property, fallbackKey) => {
        const presentation = snapshot?.fetchLimitPresentation;
        return presentation?.namespace && presentation[property]
            ? `${presentation.namespace}:${presentation[property]}` : `batch:${fallbackKey}`;
    };
    const refreshFetchLimit = () => {
        const preview = () => altScheduleSources()?.previewForMode(state.mode, altScheduleSourceContext(limitInput.value));
        const snapshot = session ? withScheduleEditSettings(preview) : preview();
        const mode = snapshot?.fetchLimitMode;
        for (const node of [limitLabel, limitInput, limitHint]) node.hidden = !mode;
        limitHint.textContent = mode ? bt(limitKey(snapshot,
            mode === 'watermark' ? 'watermarkHintKey' : 'perRunHintKey',
            `schedule.field.fetch-limit.hint.${mode}`)) : '';
    };
    refreshFetchLimit();

    const sourceType = session?.lease.sourceType
        || altScheduleSources()?.previewForMode(state.mode, altScheduleSourceContext(limitInput.value))?.sourceType;
    const capabilities = scheduleCredentialCapabilities(sourceType, {task: task || null});
    const proxyLabel = el('label', 'ab-field-label', bt('schedule.override.proxy', '单独代理'));
    proxyLabel.hidden = !capabilities.supportsProxy;
    body.appendChild(proxyLabel);
    const proxyInput = el('input', 'ab-input');
    proxyInput.hidden = !capabilities.supportsProxy;
    proxyInput.type = 'text';
    proxyInput.placeholder = bt('schedule.override.proxy.placeholder-optional', 'host:port（可留空）');
    proxyInput.value = editing ? (task.proxy || '') : '';
    body.appendChild(proxyInput);

    const credentialLabel = el('label', 'ab-field-label', bt('schedule.override.credential', '单独凭证'));
    credentialLabel.hidden = !capabilities.supportsCredential;
    body.appendChild(credentialLabel);
    const credInput = scheduleCredentialInput();
    credInput.hidden = !capabilities.supportsCredential;
    credInput.placeholder = task?.credentialPolicy?.bound === true
        ? capabilities.presentation.boundPlaceholder
            || bt('schedule.override.credential.bound-placeholder', '凭证已绑定且不回显；留空保持不变')
        : capabilities.presentation.placeholder
            || bt('schedule.override.credential.placeholder', '粘贴该来源所需的凭证（可留空）');
    body.appendChild(credInput);
    const credentialToggle = scheduleCredentialToggle(credInput);
    credentialToggle.hidden = !capabilities.supportsCredential;
    body.appendChild(credentialToggle);

    if (editing) {
        const overrides = el('details', 'ab-schedule-overrides');
        overrides.appendChild(el('summary', '', bt('schedule.override.title', '专用代理 / 凭证', {name: task.name})));
        overrides.append(proxyLabel, proxyInput, credentialLabel, credInput, credentialToggle);
        overrides.hidden = !capabilities.supportsProxy && !capabilities.supportsCredential;
        body.appendChild(overrides);
    }

    const status = el('p', 'ab-field-note');
    status.setAttribute('role', 'status');
    [nameInput, intervalInput, cronInput, limitInput, proxyInput, credInput].forEach((input, index) => {
        input.id = 'abScheduleField' + index;
        const label = input.previousElementSibling;
        if (label && label.tagName === 'LABEL') label.htmlFor = input.id;
    });

    const actions = el('div', 'ab-drawer-actions');
    const saveBtn = el('button', 'ab-btn ab-btn--primary');
    saveBtn.type = 'button';
    saveBtn.appendChild(el('span', '', editing
        ? bt('schedule.editor.save-edit', '保存修改')
        : bt('schedule.editor.save', '保存计划任务')));
    saveBtn.addEventListener('click', async () => {
        if (saving) return;
        const name = nameInput.value.trim();
        if (!name) {
            status.textContent = bt('schedule.editor.error.name-required', '任务名称必填');
            showSettings?.();
            nameInput.focus();
            return;
        }
        const proxy = capabilities.supportsProxy ? proxyInput.value.trim() : '';
        if (proxy && !isValidProxyHostPort(proxy)) {
            status.textContent = bt('schedule.override.proxy.invalid', '代理格式无效（应为 host:port）');
            showSettings?.();
            if (proxyInput.closest('details')) proxyInput.closest('details').open = true;
            proxyInput.focus();
            return;
        }
        const firstLimit = Math.max(0, parseInt(limitInput.value, 10) || 0);
        const isCron = triggerSeg.querySelectorAll('.ab-seg-item')[1].classList.contains('is-active');
        saving = true;
        if (session) session.saving = true;
        const controls = new Map([...body.querySelectorAll('input, button, select, textarea'),
            ...actions.querySelectorAll('button')].map(control => [control, control.disabled]));
        controls.forEach((_disabled, control) => { control.disabled = true; });
        body.setAttribute('aria-busy', 'true');
        try {
            if (session) session.lease.assertCurrent();
            const snapshot = altCaptureScheduleSource(firstLimit);
            if (session && (snapshot.sourceType !== session.lease.sourceType
                || snapshot.activationToken !== session.lease.activationToken)) {
                throw new Error(bt('schedule.editor.source-changed'));
            }
            const credential = capabilities.supportsCredential ? credInput.readCredential().trim() : '';
            if (credential) await validateScheduleCredential(snapshot.sourceType, credential, savedTask);
            if (snapshot.fetchLimitMode && !(snapshot.params.fetchLimit > 0)
                    && snapshot.fetchLimitPresentation?.fullFetchConfirmRequired !== false) {
                if (!await abConfirm(limitKey(snapshot, 'fullFetchConfirmKey',
                    'schedule.confirm.full-fetch'))) return;
            }
            snapshot.lease.assertCurrent();
            const requestBody = {
                name,
                sourceType: snapshot.sourceType,
                activationToken: snapshot.activationToken,
                definitionJson: JSON.stringify(snapshot.params),
                expectedStateVersion: savedTask?.stateVersion,
                triggerKind: isCron ? 'cron' : 'interval'
            };
            if (isCron) requestBody.cronExpr = cronInput.value.trim();
            else requestBody.intervalMinutes = Math.max(1, parseInt(intervalInput.value, 10) || 1440);
            const res = await fetch(savedTask
                ? `${BASE}/api/schedule/tasks/${savedTask.id}`
                : `${BASE}/api/schedule/tasks`, {
                method: savedTask ? 'PUT' : 'POST',
                headers: {'Content-Type': 'application/json'},
                credentials: 'same-origin',
                signal: snapshot.lease.signal,
                body: JSON.stringify(requestBody)
            });
            snapshot.lease.assertCurrent();
            if (!res.ok) throw await scheduleHttpError(res, 'schedule.error.save', '保存失败');
            savedTask = await res.json();
            snapshot.lease.assertCurrent();
            if (capabilities.supportsProxy && proxy !== (savedTask.proxy || '')) {
                savedTask = await schedulePost(savedTask, 'proxy', {proxy: proxy || null}, snapshot.lease.signal);
            }
            snapshot.lease.assertCurrent();
            if (credential) {
                await bindScheduleCredential(savedTask.id, snapshot.sourceType, credential, savedTask);
            }
            snapshot.lease.assertCurrent();
            abToast('success', bt('schedule.editor.saved', '已保存'));
        } catch (e) {
            status.textContent = String(e && e.message || bt('schedule.error.save', '保存失败'));
            return;
        } finally {
            saving = false;
            if (session) session.saving = false;
            controls.forEach((disabled, control) => { control.disabled = disabled; });
            body.removeAttribute('aria-busy');
        }
        if (editing) finishScheduleEdit();
        else closeDrawer();
        loadScheduleTasks();
    });
    actions.appendChild(saveBtn);
    const footer = el('div');
    footer.append(status, actions);

    if (editing) {
        const cancel = el('button', 'ab-btn ab-btn--ghost', bt('common.cancel', '取消'));
        cancel.type = 'button';
        cancel.addEventListener('click', () => cancelScheduleEdit());
        actions.prepend(cancel);
        const section = el('section', 'ab-schedule-inline');
        section.dataset.scheduleEditor = '1';
        const info = el('div', 'ab-schedule-edit-info');
        const heading = el('h2');
        heading.id = 'abScheduleEditingTitle';
        const summary = el('p', 'ab-schedule-edit-summary');
        refreshEditSummary = () => {
            heading.textContent = bt('batch:schedule.save.editing', '正在编辑：{name}',
                {name: nameInput.value.trim() || task.name});
            summary.textContent = summaryJoin([session.quickSource?.label, scheduleCadenceLabel({
                triggerKind: triggerSeg.children[1].classList.contains('is-active') ? 'cron' : 'interval',
                intervalMinutes: intervalInput.value, cronExpr: cronInput.value
            })]);
        };
        refreshEditSummary();
        info.append(heading, summary);
        const options = el('button', 'ab-btn ab-btn--ghost ab-schedule-options', bt('schedule.editor.settings'));
        options.type = 'button';
        options.setAttribute('aria-haspopup', 'dialog');
        body.prepend(el('p', 'ab-field-note', bt('schedule.editor.settings-help')));
        const back = el('button', 'ab-btn ab-btn--ghost', bt('schedule.editor.back-to-source'));
        back.type = 'button';
        const returnToSource = () => { refreshEditSummary(); section.appendChild(status); return !saving; };
        back.addEventListener('click', () => { if (returnToSource()) closeDrawer(); });
        const settingsFooter = el('div', 'ab-drawer-actions');
        settingsFooter.appendChild(back);
        showSettings = () => {
            refreshFetchLimit();
            body.prepend(status);
            openDrawer({
                id: 'schedule-settings', icon: 'clock', title: bt('schedule.editor.settings'),
                body, footer: settingsFooter, beforeClose: returnToSource
            });
        };
        options.addEventListener('click', showSettings);
        actions.prepend(options);
        status.classList.add('ab-schedule-edit-status');
        section.setAttribute('aria-labelledby', heading.id);
        section.append(info, actions, el('p', 'ab-schedule-edit-scope', bt('schedule.editor.details.scope')), status);
        session.element = section;
        mountScheduleEdit(document.getElementById('abModePanel'));
        document.getElementById('abModePanel').querySelector('.ab-composer input')?.focus({preventScroll: true});
        return;
    }

    openDrawer({
        id: 'schedule-editor',
        icon: 'plus',
        title: bt('schedule.editor.title-new', '新建计划任务'),
        body,
        beforeClose: () => !saving && (!dirty || abConfirm('dialog.discard-draft', '放弃未保存的修改？')),
        footer
    });
}

window.PixivBatchAlt.schedule = Object.assign(window.PixivBatchAlt.schedule, {
    enterScheduleMode, loadScheduleTasks, startSchedulePolling, stopSchedulePolling,
    renderScheduleMode, renderScheduleTaskList, scheduleStatusLight,
    openScheduleEditor, openScheduleSnapshot, openScheduleOverride, openSchedulePending
});
