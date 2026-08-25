'use strict';
    function buildScheduleSnapshot() {
        const runtime = scheduleSourceRuntime();
        if (!runtime || typeof runtime.captureForMode !== 'function') {
            throw new Error(bt('schedule.error.source-editor-unavailable', '计划任务来源编辑器当前不可用'));
        }
        return runtime.captureForMode(state.mode, scheduleSourceContext());
    }
    function scheduleSourceErrorMessage(error) {
        if (error && error.code === 'SCHEDULE_SOURCE_EDITOR_UNAVAILABLE') {
            return bt('schedule.error.source-editor-unavailable', '计划任务来源编辑器当前不可用');
        }
        if (error && error.code === 'SCHEDULE_SOURCE_EDITOR_AMBIGUOUS') {
            return bt('schedule.error.source-editor-ambiguous', '当前取得模式匹配到多个计划任务来源，请重新选择');
        }
        if (error && error.code === 'SCHEDULE_SOURCE_DEFINITION_INVALID') {
            return bt('schedule.error.source-definition-invalid', '计划任务来源返回了无效的任务定义');
        }
        return error && error.message
            ? error.message
            : bt('schedule.error.source-editor-unavailable', '计划任务来源编辑器当前不可用');
    }

    async function submitScheduleTask() {
        const editingToken = scheduleEditingToken;
        const editing = editingToken != null;
        if ((scheduleEditingId != null) !== editing
                || (editing && Number(scheduleEditingId) !== editingToken.taskId)) {
            setScheduleFormStatus(
                bt('schedule.error.concurrent-change', '任务状态已变化，请刷新后重试'), 'error');
            return;
        }
        const name = (document.getElementById('sch-name').value || '').trim();
        if (!name) {
            setScheduleFormStatus(bt('schedule.error.name', '请填写任务名称'), 'error');
            return;
        }
        let snap;
        let sourceLease;
        try {
            snap = buildScheduleSnapshot();
            sourceLease = scheduleSourceRuntime().activationLease(snap.sourceType);
        } catch (e) {
            setScheduleFormStatus(scheduleSourceErrorMessage(e), 'error');
            return;
        }
        if (editing && (snap.sourceType !== editingToken.sourceType
                || snap.activationToken !== editingToken.activationToken)) {
            setScheduleFormStatus(
                bt('schedule.error.concurrent-change', '任务状态已变化，请刷新后重试'), 'error');
            return;
        }
        // 单独代理 / 单独凭证的输入先行校验（创建与编辑共用一套规则）。
        const prevTask = editing ? scheduleTaskById(editingToken.taskId) : null;
        const ov = readScheduleOverrideInputs('sch', snap.sourceType, {task: prevTask});
        const ovError = await validateScheduleOverrideInputs(ov, prevTask, snap.sourceType);
        if (!scheduleSubmissionCurrent(sourceLease, editingToken)) return;
        if (ovError) {
            setScheduleFormStatus(ovError, 'error');
            return;
        }
        // N=0（全量）风险确认：宿主使用中性默认，来源可用受控 i18n key 补充站点专属风险。
        if (snap.fetchLimitMode && !(snap.params.fetchLimit > 0)) {
            const confirmed = await uiConfirmKey(scheduleFetchLimitI18nKey(
                snap.fetchLimitPresentation,
                'fullFetchConfirmKey',
                'schedule.confirm.full-fetch'),
            '「首次抓取上限」为 0 表示首次运行会尝试抓取该来源的全部历史作品，可能产生大量请求、耗时或触发来源站点的保护措施。确定要全量抓取吗？');
            if (!scheduleSubmissionCurrent(sourceLease, editingToken)) return;
            if (!confirmed) return;
        }
        // 编辑时取消勾选 = 清除已生效的单独代理 / 凭证：先确认后果再保存。
        const confirmedClears = await confirmScheduleOverrideClears(ov, prevTask, sourceLease);
        if (!scheduleSubmissionCurrent(sourceLease, editingToken)) return;
        if (!confirmedClears) return;
        const triggerKind = document.getElementById('sch-trigger').value;
        const body = {
            name,
            sourceType: snap.sourceType,
            activationToken: snap.activationToken,
            definitionJson: JSON.stringify(snap.params),
            triggerKind
        };
        if (editing) body.expectedStateVersion = editingToken.stateVersion;
        if (triggerKind === 'interval') {
            body.intervalMinutes = parseInt(document.getElementById('sch-interval').value, 10) || 0;
        } else {
            body.cronExpr = (document.getElementById('sch-cron').value || '').trim();
        }
        const url = editing
            ? `${BASE}/api/schedule/tasks/${editingToken.taskId}`
            : `${BASE}/api/schedule/tasks`;
        try {
            const res = await fetch(url, {
                method: editing ? 'PUT' : 'POST',
                credentials: 'same-origin',
                headers: {'Content-Type': 'application/json'},
                signal: sourceLease.signal,
                body: JSON.stringify(body)
            });
            assertScheduleSubmissionCurrent(sourceLease, editingToken);
            if (!res.ok) {
                const err = await res.json().catch(() => ({}));
                assertScheduleSubmissionCurrent(sourceLease, editingToken);
                // 后端错误体是 {"error": "..."}（ErrorResponse）：Cron 表达式无效等具体原因都在 error 字段，
                // 读对字段才能把「Cron 表达式无效」这类原因透给用户，而不是一律退回泛化的「保存失败」。
                setScheduleFormStatus(err.error || err.message || bt('schedule.error.save', '保存失败'), 'error');
                return;
            }
            const saved = await res.json().catch(() => null);
            assertScheduleSubmissionCurrent(sourceLease, editingToken);
            const taskId = editing ? editingToken.taskId : (saved && saved.id != null ? saved.id : null);
            // 应用单独代理 / 单独凭证（任务本体已保存，失败不回滚，提示去列表弹窗重试）。
            let overrideResult = {ok: true, applied: false};
            if (taskId != null) {
                overrideResult = await applyScheduleOverrides(
                    taskId, ov, prevTask, snap.sourceType, sourceLease.signal);
                assertScheduleSubmissionCurrent(sourceLease, editingToken);
            }
            // solo 模式下新建任务且未指定单独凭证时，请来源插件在当前 publication 内尝试绑定
            // 自己保存的凭证。宿主只接收状态，不读取、缓存或回填 secret。
            let autoAuthStatus = null;
            if (!editing && appMode === 'solo' && !ov.credentialChecked && taskId != null) {
                try {
                    const runtime = scheduleSourceRuntime();
                    if (runtime && typeof runtime.bindSavedCredential === 'function') {
                        const result = await runtime.bindSavedCredential(
                            snap.sourceType, taskId, {task: saved});
                        assertScheduleSubmissionCurrent(sourceLease, editingToken);
                        autoAuthStatus = result && result.status;
                    }
                } catch (e) {
                    autoAuthStatus = 'failed';
                }
            }
            // 先重置表单（resetScheduleForm 末尾会清空状态），再写入成功提示，
            // 否则成功提示会被 resetScheduleForm 的 setScheduleFormStatus('') 立刻清掉。
            assertScheduleSubmissionCurrent(sourceLease, editingToken);
            resetScheduleForm();
            if (!overrideResult.ok) {
                setScheduleFormStatus(bt('schedule.status.saved-override-failed',
                    '任务已保存，但专用代理 / 来源凭证设置失败：{reason}；请在任务列表中重试',
                    {reason: overrideResult.error}), 'error');
            } else if (ov.credentialChecked
                    && (ov.credentialValue || ov.useSavedCredential) && overrideResult.applied) {
                setScheduleFormStatus(bt('schedule.status.saved-overrides',
                    '已保存，专用代理 / 来源凭证设置已应用'), 'success');
            } else if (autoAuthStatus === 'bound') {
                setScheduleFormStatus(bt('schedule.status.saved-authorized',
                    '已保存并自动绑定来源凭证'), 'success');
            } else if (autoAuthStatus === 'missing') {
                setScheduleFormStatus(bt('schedule.status.saved-no-credential',
                    '已保存；当前没有可用的来源凭证，任务将以受限模式运行；如需登录态，请在任务列表中绑定专用来源凭证'), 'success');
            } else {
                setScheduleFormStatus(bt('schedule.status.saved', '已保存'), 'success');
            }
            loadScheduleTasks();
        } catch (e) {
            if (sourceLease && (!sourceLease.isCurrent() || scheduleEditingToken !== editingToken)) return;
            setScheduleFormStatus(bt('schedule.error.save', '保存失败'), 'error');
        }
    }

    function resetScheduleForm() {
        scheduleEditingId = null;
        scheduleEditingToken = null;
        scheduleEditingQuickSource = null;
        const nameEl = document.getElementById('sch-name');
        if (nameEl) nameEl.value = '';
        const cronEl = document.getElementById('sch-cron');
        if (cronEl) cronEl.value = '';
        const intEl = document.getElementById('sch-interval');
        if (intEl) intEl.value = '1440';
        const flEl = document.getElementById('sch-fetch-limit');
        if (flEl) flEl.value = '0';
        const trgEl = document.getElementById('sch-trigger');
        if (trgEl) trgEl.value = 'interval';
        const subEl = document.getElementById('sch-submit');
        if (subEl) subEl.textContent = bt('schedule.action.create', '➕ 创建任务');
        const canEl = document.getElementById('sch-cancel');
        if (canEl) canEl.style.display = 'none';
        const srcEl = document.getElementById('sch-edit-source');
        if (srcEl) {
            srcEl.style.display = 'none';
            srcEl.textContent = '';
        }
        // 单独代理 / 单独凭证控件复位（取消勾选、清空输入、恢复默认占位符）。
        const proxyEn = document.getElementById('sch-proxy-enabled');
        if (proxyEn) proxyEn.checked = false;
        const proxyIn = document.getElementById('sch-proxy');
        if (proxyIn) proxyIn.value = '';
        const cookieEn = document.getElementById('sch-cookie-enabled');
        if (cookieEn) cookieEn.checked = false;
        setScheduleCookieInput('sch-cookie', false);
        onScheduleOverrideToggle('sch');
        onScheduleTriggerChange();
        setScheduleFormStatus('');
        // 退出编辑后刷新卡片显隐 / 来源提示 / 创建按钮禁用态（scheduleEditingId 已置空，不会重入本函数）。
        updateSaveScheduleCardVisibility();
    }

    // ── 单独代理 / 单独凭证（存为计划任务卡片与覆盖弹窗共用） ─────────────

    // 复选框联动：勾选才显示输入区（prefix='sch' 卡片 / 'sch-ov' 弹窗）。
    function onScheduleOverrideToggle(prefix) {
        [['proxy-enabled', 'proxy-row'], ['cookie-enabled', 'cookie-row']].forEach(([cb, row]) => {
            const checkbox = document.getElementById(`${prefix}-${cb}`);
            const rowEl = document.getElementById(`${prefix}-${row}`);
            if (checkbox && rowEl) rowEl.style.display = checkbox.checked ? '' : 'none';
        });
    }

    // 凭证输入框复位：凭证绝不回显，已绑定时仅用占位符说明「留空保持不变」。
    function setScheduleCookieInput(inputId, bound, presentation) {
        const el = document.getElementById(inputId);
        if (!el) return;
        const p = presentation || {};
        el.value = '';
        if (el.dataset) delete el.dataset.useSavedCredential;
        el.placeholder = bound
            ? (p.boundPlaceholder || bt('schedule.field.credential.placeholder-bound',
                '已绑定凭证（不回显）；留空保持不变，填写则替换'))
            : (p.placeholder || bt('schedule.field.credential.placeholder',
                '粘贴来源凭证，或点右侧按钮填入'));
    }

    // 「使用当前保存的凭证」只记录一次性选择，不把插件 secret 复制到宿主 DOM。
    async function fillScheduleCookieFromSaved(inputId) {
        const el = document.getElementById(inputId);
        if (!el) return;
        const report = inputId === 'sch-ov-cookie' ? setScheduleOverrideStatus : setScheduleFormStatus;
        const task = inputId === 'sch-ov-cookie'
            ? scheduleTaskById(Number((document.getElementById('schedule-override-modal') || {}).dataset?.taskId))
            : null;
        const preview = task ? null : currentScheduleSourcePreview();
        const sourceType = task ? (task.sourceType || task.type) : (preview && preview.sourceType);
        const capabilities = scheduleCredentialCapabilities(sourceType, {task, mode: state.mode});
        if (!capabilities.supportsCredential) {
            report(bt('schedule.error.no-credential', '当前来源没有可用的已保存凭证'), 'error');
            return;
        }
        const p = capabilities.presentation || {};
        el.value = '';
        if (el.dataset) el.dataset.useSavedCredential = 'true';
        el.placeholder = p.savedSelectionPlaceholder
            || `•••••••• · ${p.savedCredentialLabel
                || bt('schedule.action.use-saved-credential', '使用当前保存的凭证')}`;
        report(bt('schedule.status.saved-credential-selected',
            '已选择使用当前保存的来源凭证；凭证内容不会填入页面'), 'success');
    }

    // 与后端 OutboundProxyOverride.parse 同口径：严格 host:port——host 段只允许主机名 / IPv4 字符，
    // 借此拒绝带 scheme（http://…）、用户名密码（user:pass@…）、路径、空白、IPv6 等「貌似 host:port」的串；端口 1-65535。
    function isValidProxyHostPort(value) {
        if (!value) return false;
        const colon = value.lastIndexOf(':');
        if (colon <= 0 || colon === value.length - 1) return false;
        const host = value.slice(0, colon);
        if (!/^[A-Za-z0-9._-]+$/.test(host)) return false;
        const port = Number(value.slice(colon + 1));
        return Number.isInteger(port) && port >= 1 && port <= 65535;
    }

    function readScheduleOverrideInputs(prefix, sourceType, context) {
        const capabilities = scheduleCredentialCapabilities(sourceType, context);
        const credentialInput = document.getElementById(`${prefix}-cookie`);
        const credentialValue = ((credentialInput || {}).value || '').trim();
        return {
            supportsProxy: capabilities.supportsProxy,
            supportsCredential: capabilities.supportsCredential,
            presentation: capabilities.presentation,
            proxyChecked: capabilities.supportsProxy
                && !!(document.getElementById(`${prefix}-proxy-enabled`) || {}).checked,
            proxyValue: ((document.getElementById(`${prefix}-proxy`) || {}).value || '').trim(),
            credentialChecked: capabilities.supportsCredential
                && !!(document.getElementById(`${prefix}-cookie-enabled`) || {}).checked,
            credentialValue,
            useSavedCredential: capabilities.supportsCredential && !credentialValue
                && !!(credentialInput && credentialInput.dataset
                    && credentialInput.dataset.useSavedCredential === 'true')
        };
    }

    // 代理由宿主校验 host:port；凭证格式完全交给当前来源 contribution。
    // 留空只能表示保持已绑定凭证，或使用来源已保存凭证。
    async function validateScheduleOverrideInputs(ov, prevTask, sourceType) {
        if (ov.proxyChecked && !isValidProxyHostPort(ov.proxyValue)) {
            return bt('schedule.error.proxy-format', '代理格式无效，应为 host:port（例如 127.0.0.1:7890）');
        }
        if (ov.credentialChecked && ov.credentialValue) {
            try {
                const error = await scheduleSourceRuntime().validateCredential(
                    sourceType, ov.credentialValue, {task: prevTask});
                if (error) return String(error);
            } catch (e) {
                return bt('schedule.error.authorize', '授权失败');
            }
        }
        if (ov.credentialChecked && !ov.credentialValue && !ov.useSavedCredential
                && !(prevTask && scheduleTaskCredentialPolicy(prevTask).bound)) {
            return (ov.presentation || {}).emptyCredentialMessage
                || bt('schedule.error.override-credential-empty',
                    '请填写单独凭证（或点「使用当前保存的凭证」），或取消勾选');
        }
        return null;
    }

    // 取消勾选 = 清除已生效的单独设置：弹窗确认回退全局代理或解除凭证的后果。
    async function confirmScheduleOverrideClears(ov, prevTask, sourceLease) {
        if (!prevTask) return true;
        if (ov.supportsProxy && !ov.proxyChecked && prevTask.proxy) {
            const confirmed = await uiConfirmKey(
                (ov.presentation || {}).clearProxyConfirmI18nKey
                    || 'schedule.confirm.clear-proxy',
                (ov.presentation || {}).clearProxyConfirm
                    || '将清除该任务的单独代理，此后使用全局代理设置。确定吗？');
            if (sourceLease && !scheduleLeaseCurrent(sourceLease)) return false;
            if (!confirmed) return false;
        }
        if (ov.supportsCredential && !ov.credentialChecked
                && scheduleTaskCredentialPolicy(prevTask).bound) {
            const confirmed = await uiConfirmKey(
                (ov.presentation || {}).clearCredentialConfirmI18nKey
                    || 'schedule.confirm.clear-credential',
                (ov.presentation || {}).clearCredentialConfirm
                    || '将解除该任务绑定的凭证；需要登录态的来源可能无法继续运行。确定吗？');
            if (sourceLease && !scheduleLeaseCurrent(sourceLease)) return false;
            if (!confirmed) return false;
        }
        return true;
    }

    /**
     * 把单独代理 / 凭证选择交给后端与来源 contribution。返回 {ok, applied, error}：
     * applied=true 表示至少发生了一次变更。任何一步失败即中止后续调用（任务本体的保存不回滚）。
     * 凭证留空且此前已绑定 = 保持不变；代理值与现状相同也不重复提交。
     */
    async function applyScheduleOverrides(taskId, ov, prevTask, sourceType, signal) {
        const hadProxy = !!(prevTask && prevTask.proxy);
        const wasBound = !!(prevTask && scheduleTaskCredentialPolicy(prevTask).bound);
        let applied = false;
        if (ov.supportsProxy && ov.proxyChecked && ov.proxyValue && (!prevTask || prevTask.proxy !== ov.proxyValue)) {
            const err = await postScheduleProxy(taskId, ov.proxyValue, signal);
            if (err) return {ok: false, applied, error: err};
            applied = true;
        } else if (ov.supportsProxy && !ov.proxyChecked && hadProxy) {
            const err = await postScheduleProxy(taskId, null, signal);
            if (err) return {ok: false, applied, error: err};
            applied = true;
        }
        const runtime = scheduleSourceRuntime();
        if (ov.supportsCredential && ov.credentialChecked && ov.credentialValue) {
            let result;
            try {
                result = await runtime.bindCredential(
                    sourceType, taskId, ov.credentialValue, {task: prevTask});
            } catch (e) {
                result = {ok: false, error: bt('schedule.error.authorize', '授权失败')};
            }
            if (!result || !result.ok) return {ok: false, applied, error:
                (result && result.error) || bt('schedule.error.authorize', '授权失败')};
            applied = true;
        } else if (ov.supportsCredential && ov.credentialChecked && ov.useSavedCredential) {
            let result;
            try {
                result = await runtime.bindSavedCredential(sourceType, taskId, {task: prevTask});
            } catch (e) {
                result = {ok: false, error: bt('schedule.error.authorize', '授权失败')};
            }
            if (!result || !result.ok) return {ok: false, applied, error:
                (result && result.error) || (ov.presentation || {}).emptyCredentialMessage
                    || bt('schedule.error.no-credential', '当前来源没有可用的已保存凭证')};
            applied = true;
        } else if (ov.supportsCredential && !ov.credentialChecked && wasBound) {
            let result;
            try {
                result = await runtime.revokeCredential(sourceType, taskId, {task: prevTask});
            } catch (e) {
                result = {ok: false, error: bt(
                    'schedule.error.revoke-credential', '解除来源凭证失败')};
            }
            if (!result || !result.ok) return {ok: false, applied, error:
                (result && result.error)
                    || bt('schedule.error.revoke-credential', '解除来源凭证失败')};
            applied = true;
        }
        return {ok: true, applied};
    }

    // 设置 / 清除任务级单独代理（proxy=null 即清除）。成功返回 null，失败返回错误文案。
    async function postScheduleProxy(taskId, proxy, signal) {
        try {
            const res = await fetch(`${BASE}/api/schedule/tasks/${taskId}/proxy`, {
                method: 'POST',
                credentials: 'same-origin',
                headers: {'Content-Type': 'application/json'},
                signal,
                body: JSON.stringify({proxy})
            });
            if (res.ok) return null;
            const err = await res.json().catch(() => ({}));
            return err.error || err.message || bt('schedule.error.proxy-save', '单独代理设置失败');
        } catch (e) {
            return bt('schedule.error.proxy-save', '单独代理设置失败');
        }
    }

    // 编辑：来源模块负责选择取得模式并回灌业务定义；宿主只恢复触发、名称和通用覆盖设置。
    function startEditScheduleTask(id) {
        const task = scheduleTasksCache.find(t => t.id === id);
        if (!task) return;
        setScheduleFormStatus('');
        const sourceType = task.sourceType || task.type;
        const taskId = Number(task.id);
        const stateVersion = Number(task.stateVersion);
        const activationToken = typeof task.sourceActivationToken === 'string'
            ? task.sourceActivationToken : '';
        if (!Number.isSafeInteger(taskId)
                || !Number.isSafeInteger(stateVersion)
                || stateVersion < 0
                || !activationToken) {
            setScheduleCardTip(id,
                bt('schedule.error.concurrent-change', '任务状态已变化，请刷新后重试'), 'error');
            return;
        }
        const runtime = scheduleSourceRuntime();
        if (!runtime || task.sourceAvailable === false || !runtime.isAvailable(sourceType)) {
            setScheduleCardTip(id,
                bt('schedule.error.source-editor-unavailable', '计划任务来源编辑器当前不可用'), 'error');
            return;
        }
        let restored;
        try {
            restored = runtime.restoreTask(task, scheduleSourceContext());
        } catch (e) {
            setScheduleCardTip(id,
                bt('schedule.error.source-editor-unavailable', '计划任务来源编辑器当前不可用'), 'error');
            return;
        }
        if (!restored || typeof restored !== 'object') {
            setScheduleCardTip(id,
                bt('schedule.error.source-editor-unavailable', '计划任务来源编辑器当前不可用'), 'error');
            return;
        }
        scheduleEditingQuickSource = null;
        if (restored.quickSource) scheduleEditingQuickSource = restored.quickSource;

        const canonicalDescriptor = runtime.descriptor(sourceType);
        scheduleEditingToken = Object.freeze({
            taskId,
            stateVersion,
            sourceType: canonicalDescriptor ? canonicalDescriptor.sourceType : sourceType,
            activationToken
        });
        scheduleEditingId = taskId;
        document.getElementById('sch-name').value = task.name || '';
        document.getElementById('sch-trigger').value = task.triggerKind || 'interval';
        document.getElementById('sch-interval').value = task.intervalMinutes || 1440;
        document.getElementById('sch-cron').value = task.cronExpr || '';
        const flEl = document.getElementById('sch-fetch-limit');
        const params = restored.params || {};
        if (flEl) flEl.value = (Number.isFinite(params.fetchLimit) && params.fetchLimit > 0)
            ? params.fetchLimit : 0;
        // 单独代理 / 凭证回灌：代理可回显；凭证仅恢复勾选态（留空保存 = 保持不变）。
        const proxyEn = document.getElementById('sch-proxy-enabled');
        if (proxyEn) proxyEn.checked = !!task.proxy;
        const proxyIn = document.getElementById('sch-proxy');
        if (proxyIn) proxyIn.value = task.proxy || '';
        const cookieEn = document.getElementById('sch-cookie-enabled');
        if (cookieEn) cookieEn.checked = scheduleTaskCredentialPolicy(task).bound;
        updateScheduleCredentialControls('sch', sourceType, {task});
        onScheduleOverrideToggle('sch');
        document.getElementById('sch-submit').textContent = bt('schedule.action.save', '💾 保存修改');
        document.getElementById('sch-cancel').style.display = '';
        const srcEl = document.getElementById('sch-edit-source');
        if (srcEl) {
            srcEl.style.display = '';
            srcEl.textContent = bt('schedule.save.editing', '正在编辑：{name}', {name: task.name || ''});
        }
        onScheduleTriggerChange();
        updateSaveScheduleCardVisibility();
        const card = document.getElementById('save-as-schedule-card');
        // 「存为计划任务」默认折叠；进入编辑态时展开，让编辑表单立即可见。
        if (card) {
            card.open = true;
            card.scrollIntoView({behavior: 'smooth', block: 'center'});
        }
    }
