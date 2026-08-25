'use strict';
    async function runScheduleTask(id) {
        try {
            const res = await fetch(`${BASE}/api/schedule/tasks/${id}/run`, {method: 'POST', credentials: 'same-origin'});
            if (res.ok) {
                setScheduleCardTip(id, bt('schedule.status.run-started', '已开始后台运行'), 'success');
                // 立即刷新：后端 runOnce 同步阶段已把 runState 置为 QUEUED，刷新后状态灯立刻切到「排队中 → 运行中」，
                // 不必等下一拍 4s 轮询。
                await loadScheduleTasks();
            } else {
                // 状态门拒绝（陈旧 UI / 竞态：点击瞬间任务刚进入运行 / 挂起态）。刷新让按钮回到正确禁用态。
                setScheduleCardTip(id, bt('schedule.error.run', '当前状态不允许立即运行'), 'error');
                await loadScheduleTasks();
            }
        } catch (e) { /* ignore */ }
    }

    async function toggleScheduleTask(id, enabled) {
        try {
            await fetch(`${BASE}/api/schedule/tasks/${id}/enabled?enabled=${enabled}`,
                {method: 'POST', credentials: 'same-origin'});
            loadScheduleTasks();
        } catch (e) { /* ignore */ }
    }

    function scheduleCredentialPolicyActionClass(tone) {
        if (tone === 'danger') return 'btn-red';
        if (tone === 'primary') return 'btn-blue';
        return 'btn-gray';
    }

    function renderCredentialPolicyBanners(groups) {
        return (Array.isArray(groups) ? groups : []).map((group, groupIndex) => `
            <div class="schedule-credential-policy-banner">
                <div class="schedule-credential-policy-title">${escHtml(group.title)}</div>
                <div class="schedule-credential-policy-desc">${escHtml(group.description)}</div>
                <div class="schedule-credential-policy-actions">
                    ${group.actions.map((action, actionIndex) => `<button type="button"
                        class="btn ${scheduleCredentialPolicyActionClass(action.tone)}"
                        data-credential-policy-group="${groupIndex}"
                        data-credential-policy-action="${actionIndex}">${escHtml(action.label)}</button>`).join('')}
                </div>
            </div>`).join('');
    }

    function bindScheduleCredentialPolicyActions(container) {
        container.querySelectorAll('[data-credential-policy-action]').forEach(button => {
            button.addEventListener('click', () => {
                applyScheduleCredentialPolicyAction(
                    Number(button.dataset.credentialPolicyGroup),
                    Number(button.dataset.credentialPolicyAction),
                    button);
            });
        });
    }

    async function pauseScheduleTask(id) {
        try {
            const res = await fetch(`${BASE}/api/schedule/tasks/${id}/pause`, {method: 'POST', credentials: 'same-origin'});
            if (res.ok) {
                setScheduleCardTip(id, bt('schedule.status.paused', '已暂停该任务'), 'success');
                loadScheduleTasks();
            } else {
                setScheduleCardTip(id, bt('schedule.error.pause', '暂停失败'), 'error');
            }
        } catch (e) {
            setScheduleCardTip(id, bt('schedule.error.pause', '暂停失败'), 'error');
        }
    }

    async function resumeScheduleTask(id) {
        try {
            const res = await fetch(`${BASE}/api/schedule/tasks/${id}/resume`, {method: 'POST', credentials: 'same-origin'});
            if (res.ok) {
                setScheduleCardTip(id, bt('schedule.status.resumed', '已恢复该任务'), 'success');
                loadScheduleTasks();
            } else {
                setScheduleCardTip(id, bt('schedule.error.resume', '恢复失败'), 'error');
            }
        } catch (e) {
            setScheduleCardTip(id, bt('schedule.error.resume', '恢复失败'), 'error');
        }
    }

    function scheduleCredentialPolicyPromptValue(prompt, input) {
        if (prompt.inputType !== 'number') return String(input);
        let value = Number(input);
        if (!Number.isFinite(value)) return null;
        if (Number.isFinite(prompt.min) && value < prompt.min) value = prompt.min;
        return value;
    }

    async function applyScheduleCredentialPolicyAction(groupIndex, actionIndex, button) {
        const group = scheduleCredentialPolicyGroupsCache[groupIndex];
        const action = group && group.actions[actionIndex];
        if (!group || !action) return;
        if (action.confirmMessage) {
            const confirmed = await uiConfirmKey(
                'schedule.credential-policy.confirm', action.confirmMessage);
            if (!confirmed) return;
        }
        const parameters = {};
        if (action.prompt) {
            const prompt = action.prompt;
            const input = await uiPromptKey(
                'schedule.credential-policy.prompt',
                prompt.message,
                prompt.defaultValue,
                {inputType: prompt.inputType, min: prompt.min, step: prompt.step}
            );
            if (input == null) return;
            const value = scheduleCredentialPolicyPromptValue(prompt, input);
            if (value == null) {
                setScheduleListStatus(
                    bt('schedule.error.credential-policy-parameter', '输入值无效，请重新输入'), 'error');
                return;
            }
            parameters[prompt.parameterName] = value;
        }
        const runtime = scheduleSourceRuntime();
        if (!runtime || typeof runtime.applyCredentialPolicyAction !== 'function') return;
        if (button) button.disabled = true;
        try {
            const result = await runtime.applyCredentialPolicyAction(group.sourceType, {
                identity: group.identity,
                actionId: action.actionId,
                parameters
            }, {mode: state.mode});
            if (result && result.ok) {
                setScheduleListStatus(
                    bt('schedule.status.credential-policy-applied', '凭证策略操作已应用'), 'success');
            } else {
                setScheduleListStatus((result && result.error)
                    || bt('schedule.error.credential-policy-action', '凭证策略操作失败'), 'error');
            }
            await loadScheduleTasks();
        } catch (e) {
            setScheduleListStatus(
                bt('schedule.error.credential-policy-action', '凭证策略操作失败'), 'error');
            await loadScheduleTasks();
        } finally {
            if (button && button.isConnected) button.disabled = false;
        }
    }

    async function togglePendingPanel(id) {
        const panel = document.getElementById(`schedule-pending-${id}`);
        if (!panel) return;
        if (!panel.hidden) {
            panel.hidden = true;
            return;
        }
        panel.hidden = false;
        await loadPendingPanel(id);
    }

    async function loadPendingPanel(id) {
        const panel = document.getElementById(`schedule-pending-${id}`);
        if (!panel) return;
        try {
            const res = await fetch(`${BASE}/api/schedule/tasks/${id}/pending`, {credentials: 'same-origin'});
            if (!res.ok) {
                panel.innerHTML = `<div class="schedule-pending-empty">${escHtml(bt('schedule.pending.load-failed', '加载待重试列表失败'))}</div>`;
                return;
            }
            const items = await res.json();
            if (!Array.isArray(items) || items.length === 0) {
                panel.innerHTML = `<div class="schedule-pending-empty">${escHtml(bt('schedule.pending.empty', '暂无待重试作品'))}</div>`;
                return;
            }
            const task = scheduleTaskById(id);
            const busy = !!task && ['RUNNING', 'QUEUED', 'CANCEL_REQUESTED'].includes(task.runState);
            const clearAttr = busy
                ? `disabled title="${escHtml(bt('schedule.disabled.busy', '任务运行 / 排队中，暂不可操作'))}"`
                : '';
            const rows = items.map(p => {
                const manual = p.needsManual ? bt('schedule.pending.needs-manual', '（需人工）') : '';
                const workType = scheduleKindLabel(p.workType);
                const line = escHtml(bt('schedule.pending.item', '{workType} {workId}：已重试 {attempts} 次{manual}',
                    {workType, workId: p.workId, attempts: p.attempts, manual}));
                const reasonText = pendingReasonText(p, task && (task.sourceType || task.type));
                const reason = reasonText
                    ? `<div class="schedule-pending-reason">${escHtml(bt('schedule.pending.reason', '原因：{reason}', {reason: reasonText}))}</div>`
                    : '';
                return `<li class="schedule-pending-item${p.needsManual ? ' schedule-pending-manual' : ''}">
                    <div class="schedule-pending-line">${line}
                        <button class="btn btn-gray btn-xs" ${clearAttr} data-schedule-pending-clear
                                data-work-type="${escHtml(p.workType)}" data-work-id="${escHtml(p.workId)}">${escHtml(bt('schedule.pending.action.clear', '清除'))}</button>
                    </div>${reason}
                </li>`;
            }).join('');
            panel.innerHTML = `<div class="schedule-pending-head">${escHtml(bt('schedule.pending.title', '待重试 / 需人工'))}</div><ul class="schedule-pending-list">${rows}</ul>`;
            panel.querySelectorAll('[data-schedule-pending-clear]').forEach(button => {
                button.addEventListener('click', () => clearPendingItem(
                    id, button.dataset.workType, button.dataset.workId));
            });
        } catch (e) {
            panel.innerHTML = `<div class="schedule-pending-empty">${escHtml(bt('schedule.pending.load-failed', '加载待重试列表失败'))}</div>`;
        }
    }

    function pendingReasonText(item, sourceType) {
        if (!item) return '';
        const unavailable = () => bt('schedule.pending.reason-unavailable', '失败原因不可用');
        let detailPresent = false;
        let detailCode = null;
        if (item.reasonDetailJson != null && String(item.reasonDetailJson).trim()) {
            detailPresent = true;
            try {
                const detail = JSON.parse(item.reasonDetailJson);
                if (typeof detail === 'string') {
                    detailCode = detail;
                } else if (detail && typeof detail === 'object') {
                    detailCode = [detail.message, detail.reason, detail.reasonCode, detail.legacyReason]
                        .find(value => typeof value === 'string' && value.trim()) || null;
                }
            } catch (e) {
                return unavailable();
            }
        }
        if (detailCode) {
            return localizeScheduleMachineCode(detailCode, sourceType) || unavailable();
        }
        if (item.reasonCode != null && String(item.reasonCode).trim()) {
            return localizeScheduleMachineCode(item.reasonCode, sourceType) || unavailable();
        }
        return detailPresent ? unavailable() : '';
    }

    async function clearPendingItem(id, workType, workId) {
        try {
            const res = await fetch(`${BASE}/api/schedule/tasks/${id}/pending`, {
                method: 'DELETE',
                credentials: 'same-origin',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({workType, workId})
            });
            if (res.ok) {
                setScheduleCardTip(id, bt('schedule.status.pending-cleared', '已清除该条待重试记录'), 'success');
                await loadPendingPanel(id);
            }
        } catch (e) { /* ignore */ }
    }

    async function deleteScheduleTask(id) {
        if (!await uiConfirmKey('schedule.confirm.delete',
            '确定删除这个计划任务吗？（绑定的来源凭证也会被清除）')) return;
        try {
            await fetch(`${BASE}/api/schedule/tasks/${id}`, {method: 'DELETE', credentials: 'same-origin'});
            loadScheduleTasks();
        } catch (e) { /* ignore */ }
    }

// ---- PixivBatch facade ----
window.PixivBatch.modes = window.PixivBatch.modes || {};
window.PixivBatch.modes.schedule = window.PixivBatch.modes.schedule || {};
window.PixivBatch.modes.schedule = Object.assign(window.PixivBatch.modes.schedule, { submitScheduleTask, resetScheduleForm, closeScheduleSnapshotModal, startEditScheduleTask, loadScheduleTasks });
