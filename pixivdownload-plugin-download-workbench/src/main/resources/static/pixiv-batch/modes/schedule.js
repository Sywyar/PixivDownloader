'use strict';
    // ── 计划任务（管理员专用） ────────────────────────────────────────────────────
    // 方案：删除独立的「创建表单」，改为在 User / Search / 系列模式的工作区底部用
    // 「存为计划任务」卡片，直接快照当前模式来源 + 上方全部下载 / 筛选设置；第 5 个
    // Tab 仅做任务列表与管理（运行 / 授权 / 启停 / 编辑 / 删除）。
    let scheduleEditingId = null;
    // 打开编辑器时固定的任务版本与来源 publication；列表轮询只更新 cache，绝不替换此 token。
    let scheduleEditingToken = null;
    // 编辑「快捷获取来源」类任务（收藏 / 关注新作 / 珍藏集）时锁定的来源 {type, source, kind, label}：
    // 这类任务无专属模式标签页，编辑时来源只读（换来源请删除重建），保存快照取此值而非当前 quick 视图。
    let scheduleEditingQuickSource = null;
    let scheduleTasksCache = [];
    // 计划任务列表轮询：进入第 5 Tab 时定时刷新，让「正在运行 / 排队中」等瞬时状态灯能实时更新。
    let schedulePollTimer = null;
    const SCHEDULE_POLL_MS = 4000;
    // 当前展开了「本轮队列详情」的任务 id 集合：列表重渲染会重建 DOM，据此恢复展开态。
    // 未展开的任务不请求 / 不渲染队列；展开后从本地缓存即时渲染，再按需向后端拉取最新队列。
    const scheduleExpandedQueues = new Set();
    // 上一次「整列空/非空 + 横幅」与「按卡片」的渲染签名。整列 innerHTML 重建会销毁展开的队列 DOM、
    // 中断 SSE 平滑刷新、把队列正文滚回顶部；改成按卡片 diff —— 仅在「该卡片的卡片级数据」真正变化时替换
    // 该卡片，并在替换前后保留其内部「队列正文/待重试面板」的 innerHTML / 滚动位置 / 展开折叠态。
    // 队列正文照常由 SSE / 快照单独更新。
    let scheduleBannerSignature = null;
    // 初始为 true：静态 HTML 的 #schedule-list 自带 .schedule-empty 占位符，首次加载到任务时需要先清空它再 diff。
    let scheduleEmptyStateRendered = true;
    const scheduleCardSignatures = new Map();
    // 上次成功 diff 渲染计划任务列表时的 UI 语言。卡片签名只看任务数据、不含语言，所以仅靠
    // signature diff 无法在语言切换后重渲染卡片；loadScheduleTasks 用这个变量比对，当语言变化时
    // 强制丢弃签名 / 已渲染态，让卡片走 replace 路径，再补刷展开的「本轮队列详情」与待重试面板。
    let scheduleLastRenderedLang = null;

    function startSchedulePolling() {
        stopSchedulePolling();
        schedulePollTimer = setInterval(() => {
            if (state.mode === 'schedule' && document.visibilityState !== 'hidden') {
                loadScheduleTasks();
            }
        }, SCHEDULE_POLL_MS);
    }

    function stopSchedulePolling() {
        if (schedulePollTimer) {
            clearInterval(schedulePollTimer);
            schedulePollTimer = null;
        }
        // 离开计划任务 Tab：解绑全部队列 SSE 监听；若工作区也未在下载且无其它监听，顺手关掉聚合连接。
        unsubscribeAllScheduleQueueSse();
        if (!state.isRunning && state.sharedSse && Object.keys(state.sseListeners).length === 0) {
            closeSharedSSE();
        }
    }

    function scheduleSourceRuntime() {
        return window.PixivBatch && window.PixivBatch.scheduleSources;
    }

    function scheduleLeaseCurrent(lease) {
        if (!lease || typeof lease.isCurrent !== 'function' || !lease.isCurrent()) return false;
        lease.assertCurrent();
        return true;
    }

    function scheduleSubmissionCurrent(sourceLease, editingToken) {
        return scheduleEditingToken === editingToken && scheduleLeaseCurrent(sourceLease);
    }

    function assertScheduleSubmissionCurrent(sourceLease, editingToken) {
        sourceLease.assertCurrent();
        if (scheduleEditingToken !== editingToken) {
            throw new Error('schedule editing token became stale');
        }
    }

    function scheduleSourceContext() {
        let quickSource = scheduleEditingQuickSource;
        if (!quickSource && state.mode === QUICK_FETCH_MODE && typeof quickScheduleSource === 'function') {
            quickSource = quickScheduleSource();
        }
        const qt = window.PixivBatch && window.PixivBatch.queueTypes;
        let workTypes = quickSource && Array.isArray(quickSource.workTypes)
            ? quickSource.workTypes.slice() : [];
        if (!workTypes.length && quickSource && quickSource.kind && quickSource.kind !== 'mixed' && qt) {
            const quickType = qt.resolveTypeForMode(quickSource.kind, 'quick');
            if (quickType) workTypes.push(quickType);
        }
        if (!workTypes.length && state.mode !== QUICK_FETCH_MODE
                && typeof currentModeKind === 'function') {
            const currentType = currentModeKind();
            if (currentType) workTypes.push(currentType);
        }
        workTypes = Array.from(new Set(workTypes.map(value => String(value || '').trim()).filter(Boolean)));
        const firstType = workTypes[0] || null;
        const manifest = firstType && qt && typeof qt.manifestDescriptor === 'function'
            ? qt.manifestDescriptor(firstType) : null;
        return Object.freeze({
            mode: state.mode,
            quickSource: quickSource || null,
            editing: scheduleEditingId != null,
            editingSourceType: scheduleEditingToken ? scheduleEditingToken.sourceType : null,
            workType: firstType,
            workTypes: Object.freeze(workTypes),
            workTypeOwnerPluginId: manifest && manifest.owner ? manifest.owner.pluginId : null,
            admin: !!isAdmin
        });
    }

    function currentScheduleSourcePreview() {
        const runtime = scheduleSourceRuntime();
        if (!runtime || typeof runtime.previewForMode !== 'function') return null;
        try {
            return runtime.previewForMode(state.mode, scheduleSourceContext());
        } catch (e) {
            return null;
        }
    }

    function scheduleI18nToken(value, maxLength) {
        const normalized = value == null ? '' : String(value).trim();
        return normalized.length <= maxLength && /^[a-z0-9][a-z0-9._-]*$/i.test(normalized)
            ? normalized : '';
    }

    function scheduleSourcePresentationI18nKey(sourceType, rawPresentation, property) {
        const runtime = scheduleSourceRuntime();
        const descriptor = runtime && typeof runtime.descriptor === 'function'
            ? runtime.descriptor(sourceType) : null;
        const declaredNamespace = scheduleI18nToken(
            descriptor && descriptor.presentation && descriptor.presentation.displayNamespace, 64);
        const requestedNamespace = scheduleI18nToken(
            rawPresentation && rawPresentation.namespace, 64);
        const key = scheduleI18nToken(rawPresentation && rawPresentation[property], 192);
        return declaredNamespace && requestedNamespace === declaredNamespace && key
            ? `${declaredNamespace}:${key}` : null;
    }

    function scheduleCredentialCapabilities(sourceType, context) {
        const runtime = scheduleSourceRuntime();
        const unavailable = {supportsCookie: false, supportsProxy: false, presentation: {}};
        if (!runtime || !sourceType) return unavailable;
        try {
            const actions = runtime.credentialActions(sourceType, context || {});
            if (!actions) {
                return unavailable;
            }
            if (typeof actions.then === 'function') {
                Promise.resolve(actions).catch(() => {});
                return unavailable;
            }
            const rawPresentation = actions.presentation && typeof actions.presentation === 'object'
                ? actions.presentation : {};
            const presentation = {};
            Object.keys(rawPresentation).forEach(key => {
                if (key !== 'clearProxyConfirmKey' && key !== 'clearCredentialConfirmKey'
                    && typeof rawPresentation[key] === 'string') {
                    presentation[key] = rawPresentation[key];
                }
            });
            presentation.clearProxyConfirmI18nKey = scheduleSourcePresentationI18nKey(
                sourceType, rawPresentation, 'clearProxyConfirmKey');
            presentation.clearCredentialConfirmI18nKey = scheduleSourcePresentationI18nKey(
                sourceType, rawPresentation, 'clearCredentialConfirmKey');
            return {
                supportsCookie: actions.supportsCookie === true,
                supportsProxy: actions.supportsProxy === true,
                presentation
            };
        } catch (e) {
            return unavailable;
        }
    }

    function scheduleOverrideActionLabel(capabilities) {
        const p = capabilities.presentation || {};
        if (p.overrideLabel) return p.overrideLabel;
        if (capabilities.supportsCookie && capabilities.supportsProxy) {
            return bt('schedule.action.override-both', '🔑 指定单独的代理 / 凭证');
        }
        if (capabilities.supportsCookie) {
            return bt('schedule.action.override-credential', '🔑 指定单独凭证');
        }
        return bt('schedule.action.override-proxy', '🌐 指定单独代理');
    }

    function applyScheduleCredentialPresentation(prefix, capabilities, bound) {
        const p = capabilities.presentation || {};
        const setText = (suffix, value) => {
            const element = document.getElementById(`${prefix}-${suffix}`);
            if (element && value) element.textContent = value;
        };
        setText('proxy-label', p.proxyToggleLabel
            || bt('schedule.field.proxy-enabled', '设置单独的代理'));
        setText('cookie-label', p.credentialToggleLabel
            || bt('schedule.field.credential-enabled', '设置单独的凭证'));
        setText('saved-cookie-label', p.savedCredentialLabel
            || bt('schedule.action.use-saved-credential', '使用当前保存的凭证'));
        setText('proxy-hint', p.proxyHint
            || bt('schedule.field.proxy.hint-generic', '该任务运行时会使用此 HTTP 代理（host:port）；取消勾选则使用全局代理设置。'));
        setText('cookie-hint', p.credentialHint
            || bt('schedule.field.credential.hint', '该任务会使用这份凭证访问来源站点；出于安全考虑，已绑定凭证不会回显。'));
        setScheduleCookieInput(`${prefix}-cookie`, !!bound, p);
    }

    function updateScheduleCredentialControls(prefix, sourceType, context) {
        const capabilities = scheduleCredentialCapabilities(sourceType, context);
        [['proxy', capabilities.supportsProxy], ['cookie', capabilities.supportsCookie]]
            .forEach(([kind, supported]) => {
                const checkbox = document.getElementById(`${prefix}-${kind}-enabled`);
                const row = document.getElementById(`${prefix}-${kind}-row`);
                if (!checkbox) return;
                if (!supported) checkbox.checked = false;
                const wrapper = prefix === 'sch' && typeof checkbox.closest === 'function'
                    ? checkbox.closest('.setting-item')
                    : (typeof checkbox.closest === 'function' ? checkbox.closest('label') : null);
                if (wrapper) wrapper.style.display = supported ? '' : 'none';
                if (row) row.style.display = supported && checkbox.checked ? '' : 'none';
            });
        applyScheduleCredentialPresentation(prefix, capabilities, context && context.task
            ? context.task.cookieBound : false);
        return capabilities;
    }

    function onScheduleTriggerChange() {
        const trigger = (document.getElementById('sch-trigger') || {}).value || 'interval';
        document.querySelectorAll('#save-as-schedule-card .sch-trigger-field').forEach(el => {
            el.style.display = el.classList.contains('sch-trigger-' + trigger) ? '' : 'none';
        });
    }

    function scheduleFetchLimitI18nKey(presentation, property, fallbackKey) {
        const value = presentation && typeof presentation === 'object' ? presentation : {};
        const namespace = typeof value.namespace === 'string' ? value.namespace : '';
        const key = typeof value[property] === 'string' ? value[property] : '';
        return namespace && key ? `${namespace}:${key}` : fallbackKey;
    }

    function scheduleFetchLimitText(presentation, property, fallbackKey, fallback) {
        return bt(scheduleFetchLimitI18nKey(presentation, property, fallbackKey), fallback);
    }

    // 抓取上限字段显隐 + 提示文案按情况切换：宿主只提供中性默认，来源可贡献受控 i18n key。
    function updateScheduleFetchLimitVisibility() {
        const row = document.getElementById('sch-fetch-limit-row');
        if (!row) return;
        const card = document.getElementById('save-as-schedule-card');
        const cardHidden = !card || card.style.display === 'none';
        const preview = cardHidden ? null : currentScheduleSourcePreview();
        const mode = preview ? preview.fetchLimitMode : null;
        const presentation = preview ? preview.fetchLimitPresentation : null;
        row.style.display = mode ? '' : 'none';
        const wm = document.getElementById('sch-fetch-limit-hint-watermark');
        const pr = document.getElementById('sch-fetch-limit-hint-per-run');
        if (wm) {
            wm.textContent = scheduleFetchLimitText(
                presentation,
                'watermarkHintKey',
                'schedule.field.fetch-limit.hint.watermark',
                '仅计划任务生效。首次运行最多把最新的这么多个作品纳入本轮队列；之后只增量追新，更早的历史不再补取。0 表示不设上限，大型来源可能产生较多请求，保存时会提示确认。');
            wm.style.display = mode === 'watermark' ? '' : 'none';
        }
        if (pr) {
            pr.textContent = scheduleFetchLimitText(
                presentation,
                'perRunHintKey',
                'schedule.field.fetch-limit.hint.per-run',
                '仅计划任务生效。该来源没有可靠的最新次序，因此每次运行最多纳入这么多个作品，并分多轮处理积压。0 表示不设上限，大型来源可能产生较多请求，保存时会提示确认。');
            pr.style.display = mode === 'per-run' ? '' : 'none';
        }
    }

    // 「存为计划任务」卡片显隐：非快捷模式沿用「管理员 + 可创建模式」；快捷获取下对管理员**常驻**，
    // 但仅当能解析出来源（已展开的收藏/我的作品/关注新作，或点进的画师/珍藏集；编辑时为锁定来源）才启用「创建」。
    function updateSaveScheduleCardVisibility() {
        const card = document.getElementById('save-as-schedule-card');
        if (!card) return;
        const inQuick = state.mode === QUICK_FETCH_MODE;
        // 在快捷获取里若残留「非快捷来源」的编辑态（编辑从 user/search/series 发起后切到了快捷获取），先退出编辑。
        if (inQuick && scheduleEditingId != null && !scheduleEditingQuickSource) resetScheduleForm();
        const submit = document.getElementById('sch-submit');
        if (inQuick) {
            card.style.display = isAdmin ? '' : 'none';
            const preview = currentScheduleSourcePreview();
            updateScheduleCredentialControls('sch', preview && preview.sourceType, scheduleSourceContext());
            updateScheduleQuickSourceNote(preview);
            if (submit) submit.disabled = isAdmin && !preview;
            updateScheduleFetchLimitVisibility();
            return;
        }
        const eligible = isAdmin && !!currentScheduleSourcePreview();
        const preview = eligible ? currentScheduleSourcePreview() : null;
        updateScheduleCredentialControls('sch', preview && preview.sourceType, scheduleSourceContext());
        card.style.display = eligible ? '' : 'none';
        updateScheduleQuickSourceNote(null);
        if (submit) submit.disabled = false;
        if (!eligible && scheduleEditingId != null) resetScheduleForm();
        updateScheduleFetchLimitVisibility();
    }

    // 快捷获取下「存为计划任务」卡片顶部的来源说明 / 提示：
    // 有来源 → 说明将创建的任务类型与来源（编辑态额外标注只读）；无来源 → 提示先展开具体作品列表。
    function updateScheduleQuickSourceNote(preview) {
        const el = document.getElementById('sch-quick-source');
        if (!el) return;
        if (state.mode !== QUICK_FETCH_MODE || !isAdmin) {
            el.textContent = '';
            el.style.display = 'none';
            return;
        }
        if (!preview) {
            el.textContent = bt('schedule.save.quick-hint',
                '请先展开具体的作品列表（点开收藏 / 我的作品 / 关注新作，或点进某个画师 / 珍藏集）后再创建计划任务');
            el.style.display = '';
            return;
        }
        const typeLabel = scheduleTypeLabel(preview.sourceType);
        const label = preview.preview && preview.preview.label ? preview.preview.label : typeLabel;
        el.textContent = scheduleEditingQuickSource
            ? bt('schedule.save.quick-source-editing', '编辑中（来源只读，换来源请删除重建）：{type} · {label}',
                {type: typeLabel, label})
            : bt('schedule.save.quick-source', '将创建计划任务：{type} · {label}',
                {type: typeLabel, label});
        el.style.display = '';
    }

    function setScheduleFormStatus(msg, type = 'info') {
        const el = document.getElementById('sch-form-status');
        if (!el) return;
        el.textContent = msg || '';
        el.style.color = STATUS_COLORS[type] || '#666';
    }

    // 账号级 / 横幅级操作反馈（如过度访问账号恢复），与「存为计划任务」卡片的表单状态分开
    function setScheduleListStatus(msg, type = 'info') {
        const el = document.getElementById('schedule-list-status');
        if (!el) return;
        el.textContent = msg || '';
        el.style.color = STATUS_COLORS[type] || '#666';
    }

    // 单个任务卡片内的操作反馈：显示在该卡片顶部的 tips 区域，互不干扰
    function setScheduleCardTip(id, msg, type = 'info') {
        const el = document.getElementById(`schedule-card-tip-${id}`);
        if (!el) return;
        el.textContent = msg || '';
        el.style.color = STATUS_COLORS[type] || '#666';
    }

    // 来源模块读取当前取得模式的受控 UI，并返回正式定义草稿；宿主只盖上当前 activation token。
    function buildScheduleSnapshot() {
        const runtime = scheduleSourceRuntime();
        if (!runtime || typeof runtime.captureForMode !== 'function') {
            throw new Error(bt('schedule.error.source-editor-unavailable', '计划任务来源编辑器当前不可用'));
        }
        return runtime.captureForMode(state.mode, scheduleSourceContext());
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
            setScheduleFormStatus(e.message, 'error');
            return;
        }
        if (editing && (snap.sourceType !== editingToken.sourceType
                || snap.activationToken !== editingToken.activationToken)) {
            setScheduleFormStatus(
                bt('schedule.error.concurrent-change', '任务状态已变化，请刷新后重试'), 'error');
            return;
        }
        // 单独代理 / 单独 cookie 的输入先行校验（创建与编辑共用一套规则，避免任务保存后才发现设置失败）。
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
        // 编辑时取消勾选 = 清除已生效的单独代理 / Cookie：先确认后果再保存。
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
            // 应用单独代理 / 单独 cookie（任务本体已保存，失败不回滚，提示去列表弹窗重试）。
            let overrideResult = {ok: true, applied: false};
            if (taskId != null) {
                overrideResult = await applyScheduleOverrides(
                    taskId, ov, prevTask, sourceLease.signal, sourceLease.activationToken);
                assertScheduleSubmissionCurrent(sourceLease, editingToken);
            }
            // solo 模式下新建任务且未指定单独 cookie 时，用当前输入框里的 Cookie 自动授权绑定，
            // 省去手动绑定一步（指定了单独 cookie 则以 applyScheduleOverrides 的绑定为准）。
            let autoAuthResult = null;
            if (!editing && appMode === 'solo' && !ov.cookieChecked && taskId != null) {
                try {
                    const runtime = scheduleSourceRuntime();
                    if (runtime && typeof runtime.invokeCredentialAction === 'function') {
                        autoAuthResult = await runtime.invokeCredentialAction(
                            snap.sourceType, 'autoAuthorize', [taskId], {task: saved});
                        assertScheduleSubmissionCurrent(sourceLease, editingToken);
                    }
                } catch (e) {
                    autoAuthResult = 'failed';
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
            } else if (ov.cookieChecked && ov.cookieValue && overrideResult.applied) {
                setScheduleFormStatus(bt('schedule.status.saved-overrides',
                    '已保存，专用代理 / 来源凭证设置已应用'), 'success');
            } else if (autoAuthResult === 'authorized') {
                setScheduleFormStatus(bt('schedule.status.saved-authorized',
                    '已保存并自动绑定来源凭证'), 'success');
            } else if (autoAuthResult === 'no-cookie') {
                setScheduleFormStatus(bt('schedule.status.saved-no-cookie',
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
        // 单独代理 / 单独 cookie 控件复位（取消勾选、清空输入、恢复默认占位符）。
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

    // ── 单独代理 / 单独 cookie（存为计划任务卡片与「指定单独的 代理/cookie」弹窗共用） ─────────────

    // 复选框联动：勾选才显示输入区（prefix='sch' 卡片 / 'sch-ov' 弹窗）。
    function onScheduleOverrideToggle(prefix) {
        [['proxy-enabled', 'proxy-row'], ['cookie-enabled', 'cookie-row']].forEach(([cb, row]) => {
            const checkbox = document.getElementById(`${prefix}-${cb}`);
            const rowEl = document.getElementById(`${prefix}-${row}`);
            if (checkbox && rowEl) rowEl.style.display = checkbox.checked ? '' : 'none';
        });
    }

    // cookie 输入框复位：凭证绝不回显，已绑定时仅用占位符说明「留空保持不变」。
    function setScheduleCookieInput(inputId, bound, presentation) {
        const el = document.getElementById(inputId);
        if (!el) return;
        const p = presentation || {};
        el.value = '';
        el.placeholder = bound
            ? (p.boundPlaceholder || bt('schedule.field.credential.placeholder-bound',
                '已绑定凭证（不回显）；留空保持不变，填写则替换'))
            : (p.placeholder || bt('schedule.field.credential.placeholder',
                '粘贴来源凭证，或点右侧按钮填入'));
    }

    // 「使用当前保存的cookie」：把 Cookie 卡片当前保存的 cookie 填入指定输入框。
    async function fillScheduleCookieFromSaved(inputId) {
        const el = document.getElementById(inputId);
        if (!el) return;
        const report = inputId === 'sch-ov-cookie' ? setScheduleOverrideStatus : setScheduleFormStatus;
        const task = inputId === 'sch-ov-cookie'
            ? scheduleTaskById(Number((document.getElementById('schedule-override-modal') || {}).dataset?.taskId))
            : null;
        const preview = task ? null : currentScheduleSourcePreview();
        const sourceType = task ? (task.sourceType || task.type) : (preview && preview.sourceType);
        const runtime = scheduleSourceRuntime();
        try {
            const cookie = await runtime.invokeCredentialAction(
                sourceType, 'savedCookie', [], {task, mode: state.mode});
            if (!cookie) throw new Error('empty source credential');
            el.value = String(cookie);
            report('');
        } catch (e) {
            report(bt('schedule.error.no-cookie', '当前来源没有可用的已保存凭证'), 'error');
            return;
        }
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
        return {
            supportsProxy: capabilities.supportsProxy,
            supportsCookie: capabilities.supportsCookie,
            presentation: capabilities.presentation,
            proxyChecked: capabilities.supportsProxy
                && !!(document.getElementById(`${prefix}-proxy-enabled`) || {}).checked,
            proxyValue: ((document.getElementById(`${prefix}-proxy`) || {}).value || '').trim(),
            cookieChecked: capabilities.supportsCookie
                && !!(document.getElementById(`${prefix}-cookie-enabled`) || {}).checked,
            cookieValue: ((document.getElementById(`${prefix}-cookie`) || {}).value || '').trim()
        };
    }

    // 输入合法性：代理须为 host:port；勾选单独 cookie 时，要么填了含 PHPSESSID 的值，
    // 要么任务此前已绑定（留空 = 保持不变）。返回错误文案或 null。
    async function validateScheduleOverrideInputs(ov, prevTask, sourceType) {
        if (ov.proxyChecked && !isValidProxyHostPort(ov.proxyValue)) {
            return bt('schedule.error.proxy-format', '代理格式无效，应为 host:port（例如 127.0.0.1:7890）');
        }
        if (ov.cookieChecked && ov.cookieValue) {
            try {
                const error = await scheduleSourceRuntime().invokeCredentialAction(
                    sourceType, 'validateCookie', [ov.cookieValue], {task: prevTask});
                if (error) return String(error);
            } catch (e) {
                return bt('schedule.error.authorize', '授权失败');
            }
        }
        if (ov.cookieChecked && !ov.cookieValue && !(prevTask && prevTask.cookieBound)) {
            return (ov.presentation || {}).emptyCredentialMessage
                || bt('schedule.error.override-credential-empty',
                    '请填写单独凭证（或点「使用当前保存的凭证」），或取消勾选');
        }
        return null;
    }

    // 取消勾选 = 清除已生效的单独设置：弹窗确认后果（回退全局代理 / 解除 Cookie 转受限模式）。
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
        if (ov.supportsCookie && !ov.cookieChecked && prevTask.cookieBound) {
            const confirmed = await uiConfirmKey(
                (ov.presentation || {}).clearCredentialConfirmI18nKey
                    || 'schedule.confirm.clear-cookie',
                (ov.presentation || {}).clearCredentialConfirm
                    || '将解除该任务绑定的凭证；需要登录态的来源可能无法继续运行。确定吗？');
            if (sourceLease && !scheduleLeaseCurrent(sourceLease)) return false;
            if (!confirmed) return false;
        }
        return true;
    }

    /**
     * 把单独 代理/cookie 的选择落到后端（只发必要的请求）。返回 {ok, applied, error}：
     * applied=true 表示至少发生了一次变更。任何一步失败即中止后续调用（任务本体的保存不回滚）。
     * cookie 留空且此前已绑定 = 保持不变；代理值与现状相同也不重复提交。
     */
    async function applyScheduleOverrides(taskId, ov, prevTask, signal, activationToken) {
        const hadProxy = !!(prevTask && prevTask.proxy);
        const wasBound = !!(prevTask && prevTask.cookieBound);
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
        if (ov.supportsCookie && ov.cookieChecked && ov.cookieValue) {
            const err = await postScheduleCookie(
                taskId, ov.cookieValue, activationToken, signal);
            if (err) return {ok: false, applied, error: err};
            applied = true;
        } else if (ov.supportsCookie && !ov.cookieChecked && wasBound) {
            const err = await postScheduleRevokeCookie(taskId, signal);
            if (err) return {ok: false, applied, error: err};
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

    // 授权绑定单独 cookie。成功返回 null，失败返回错误文案（含后端「与已绑定相同」等原因）。
    async function postScheduleCookie(taskId, cookie, activationToken, signal) {
        try {
            const res = await fetch(`${BASE}/api/schedule/tasks/${taskId}/authorize-cookie`, {
                method: 'POST',
                credentials: 'same-origin',
                headers: {
                    'Content-Type': 'application/json',
                    'X-Acquisition-Credential': cookie
                },
                signal,
                body: JSON.stringify({activationToken})
            });
            if (res.ok) return null;
            const err = await res.json().catch(() => ({}));
            return err.error || err.message || bt('schedule.error.authorize', '授权失败');
        } catch (e) {
            return bt('schedule.error.authorize', '授权失败');
        }
    }

    // 解除绑定的 cookie（任务转受限模式）。成功返回 null，失败返回错误文案。
    async function postScheduleRevokeCookie(taskId, signal) {
        try {
            const res = await fetch(`${BASE}/api/schedule/tasks/${taskId}/revoke-cookie`, {
                method: 'POST',
                credentials: 'same-origin',
                signal
            });
            if (res.ok) return null;
            const err = await res.json().catch(() => ({}));
            return err.error || err.message || bt('schedule.error.revoke-cookie', '解除来源凭证失败');
        } catch (e) {
            return bt('schedule.error.revoke-cookie', '解除来源凭证失败');
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
        // 单独代理 / 单独 cookie 回灌：代理可回显；cookie 凭证不回显，仅恢复勾选态（留空保存 = 保持不变）。
        const proxyEn = document.getElementById('sch-proxy-enabled');
        if (proxyEn) proxyEn.checked = !!task.proxy;
        const proxyIn = document.getElementById('sch-proxy');
        if (proxyIn) proxyIn.value = task.proxy || '';
        const cookieEn = document.getElementById('sch-cookie-enabled');
        if (cookieEn) cookieEn.checked = !!task.cookieBound;
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

    /**
     * 计算任务卡片右上角「状态灯」：返回 {tone, text}。
     * tone ∈ green / yellow / red / gray，决定灯色；text 为本地化的状态说明。
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
            return {tone: 'red', live: false, text: bt('schedule.light.migration-error', '任务数据需要修复，无法运行')};
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
            const reason = (t.lastMessage || '').trim();
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
        // last_status 为空：恢复 / 账号级恢复后清空了上轮结果。已运行过则显示「等待下次运行」，
        // 从未运行过才是「等待首次运行」，避免恢复一个跑过多次的任务后误显示成首次。
        if (t.lastRunTime != null) {
            return {tone: 'gray', live: false, text: bt('schedule.light.idle', '等待下次运行')};
        }
        return {tone: 'gray', live: false, text: bt('schedule.light.never', '等待首次运行')};
    }

    function fmtScheduleTime(ms) {
        if (!ms) return '—';
        try { return new Date(ms).toLocaleString(); } catch (e) { return '—'; }
    }

    function scheduleTypeLabel(type) {
        const sourceType = type == null ? '' : String(type);
        const runtime = scheduleSourceRuntime();
        const descriptor = runtime && runtime.descriptor(sourceType);
        const presentation = descriptor && descriptor.presentation;
        if (presentation && presentation.displayNamespace && presentation.displayNameKey && pageI18n) {
            return pageI18n.t(
                `${presentation.displayNamespace}:${presentation.displayNameKey}`,
                sourceType || bt('schedule.snapshot.value.unknown', '未知'));
        }
        return sourceType || bt('schedule.snapshot.value.unknown', '未知');
    }

    function scheduleKindLabel(kind) {
        if (kind === 'mixed') return bt('schedule.kind.mixed', '插画+小说');
        const registry = window.PixivBatch && window.PixivBatch.queueTypes;
        const descriptor = registry && typeof registry.manifestDescriptor === 'function'
            ? registry.manifestDescriptor(kind) : null;
        if (descriptor && descriptor.displayNamespace && descriptor.displayI18nKey && pageI18n) {
            return pageI18n.t(`${descriptor.displayNamespace}:${descriptor.displayI18nKey}`, kind);
        }
        return kind || bt('schedule.snapshot.value.unknown', '未知');
    }

    function scheduleTriggerLabel(t) {
        const minutes = t.intervalMinutes || 0;
        return t.triggerKind === 'cron'
            ? `${bt('schedule.trigger.cron', 'Cron 表达式')} ${t.cronExpr || ''}`
            : `${bt('schedule.trigger.interval', '固定周期')} ${bt('schedule.time.minutes', '{count} 分钟', {count: minutes})}`;
    }

    function scheduleValueOrUnset(value) {
        return value == null || value === ''
            ? bt('schedule.snapshot.value.unset', '未设置') : String(value);
    }

    function scheduleSnapshotRow(label, value) {
        return `<div class="schedule-snapshot-key">${escHtml(label)}</div>` +
            `<div class="schedule-snapshot-value">${escHtml(value)}</div>`;
    }

    function scheduleSnapshotSection(title, rows) {
        return `<section class="schedule-snapshot-section">` +
            `<div class="schedule-snapshot-section-title">${escHtml(title)}</div>` +
            `<div class="schedule-snapshot-grid">${rows.map(row => scheduleSnapshotRow(row[0], row[1])).join('')}</div>` +
            `</section>`;
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

    function scheduleTaskKind(task) {
        const presentation = scheduleTaskPresentation(task);
        const attributes = presentation.attributes && typeof presentation.attributes === 'object'
            ? presentation.attributes : {};
        const kind = attributes.kind;
        return typeof kind === 'string' && kind ? kind : null;
    }

    function presentationFallbackSections(task) {
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
            const value = attributes[key];
            if (typeof value === 'string') rows.push([key, value]);
        });
        if (!rows.length) {
            rows.push([
                bt('schedule.snapshot.field.source', '来源'),
                bt('schedule.error.source-editor-unavailable', '计划任务来源编辑器当前不可用')
            ]);
        }
        return [{title: bt('schedule.snapshot.section.source', '来源快照'), rows}];
    }

    function renderScheduleSnapshotBody(t) {
        const sourceType = t.sourceType || t.type;
        let contributed = null;
        const runtime = scheduleSourceRuntime();
        if (t.sourceAvailable !== false && runtime && runtime.isAvailable(sourceType)) {
            try {
                contributed = runtime.summary(t, {lang: uiLang()});
            } catch (e) {
                contributed = null;
            }
        }
        const kind = contributed && contributed.kind ? contributed.kind : scheduleTaskKind(t);
        const basicRows = [
            [bt('schedule.snapshot.field.name', '任务名称'), scheduleValueOrUnset(t.name)],
            [bt('schedule.snapshot.field.type', '任务类型'),
                t.sourceAvailable === false ? sourceType : scheduleTypeLabel(sourceType)],
            [bt('schedule.snapshot.field.trigger', '触发方式'), scheduleTriggerLabel(t)],
            [bt('schedule.snapshot.field.cookie', '来源凭证'), t.cookieBound
                ? bt('schedule.credential.bound', '已绑定凭证')
                : bt('schedule.credential.unbound', '未绑定凭证')],
            [bt('schedule.snapshot.field.proxy', '单独代理'), t.proxy ? t.proxy : bt('schedule.snapshot.value.global-proxy', '使用全局代理设置')],
            [bt('schedule.snapshot.field.enabled', '启用状态'), t.enabled ? bt('schedule.state.enabled', '已启用') : bt('schedule.state.disabled', '已停用')],
            [bt('schedule.snapshot.field.next-run', '下次运行'), fmtScheduleTime(t.nextRunTime)],
            [bt('schedule.snapshot.field.last-run', '上次运行'), fmtScheduleTime(t.lastRunTime)],
            [bt('schedule.snapshot.field.last-status', '运行状态'), scheduleStatusLabel(t.lastStatus)]
        ];
        if (kind) basicRows.splice(2, 0,
            [bt('schedule.snapshot.field.kind', '作品类型'), scheduleKindLabel(kind)]);
        const basicSection = scheduleSnapshotSection(bt('schedule.snapshot.section.basic', '基本信息'), basicRows);
        const sections = contributed && Array.isArray(contributed.sections)
            ? contributed.sections : presentationFallbackSections(t);
        const rendered = sections.filter(section => section && typeof section.title === 'string'
                && Array.isArray(section.rows))
            .map(section => scheduleSnapshotSection(section.title,
                section.rows.filter(row => Array.isArray(row) && row.length >= 2)
                    .map(row => [String(row[0]), String(row[1])]))).join('');
        return basicSection + rendered;
    }

    function showScheduleSnapshot(id) {
        const modal = document.getElementById('schedule-snapshot-modal');
        const body = document.getElementById('schedule-snapshot-body');
        if (!modal || !body) return;
        const task = scheduleTasksCache.find(t => Number(t.id) === Number(id));
        body.innerHTML = task
            ? renderScheduleSnapshotBody(task)
            : `<div class="schedule-snapshot-empty">${escHtml(bt('schedule.snapshot.error.not-found', '未找到任务，请重新加载列表'))}</div>`;
        modal.dataset.taskId = String(id);
        modal.hidden = false;
        document.body.classList.add('schedule-modal-open');
        const closeBtn = modal.querySelector('.schedule-snapshot-close');
        if (closeBtn) closeBtn.focus();
    }

    function closeScheduleSnapshotModal() {
        const modal = document.getElementById('schedule-snapshot-modal');
        if (!modal) return;
        modal.hidden = true;
        delete modal.dataset.taskId;
        document.body.classList.remove('schedule-modal-open');
    }

    document.addEventListener('keydown', function (e) {
        if (e.key === 'Escape') {
            closeScheduleSnapshotModal();
            closeScheduleOverrideModal();
        }
    });

    // ── 「指定单独的 代理/cookie」弹窗（计划任务卡片入口） ─────────────────────────────

    function setScheduleOverrideStatus(msg, type = 'info') {
        const el = document.getElementById('sch-ov-status');
        if (!el) return;
        el.textContent = msg || '';
        el.style.color = STATUS_COLORS[type] || '#666';
    }

    // 打开弹窗并按任务现状预填：代理可回显；cookie 仅恢复勾选态（凭证不回显，留空保存 = 保持不变）。
    function showScheduleOverrideModal(id) {
        const task = scheduleTaskById(id);
        const modal = document.getElementById('schedule-override-modal');
        if (!task || !modal) return;
        const sourceType = task.sourceType || task.type;
        const sourceActivationToken = typeof task.sourceActivationToken === 'string'
            ? task.sourceActivationToken : '';
        if (!sourceActivationToken) {
            setScheduleCardTip(id,
                bt('schedule.error.concurrent-change', '任务状态已变化，请刷新后重试'), 'error');
            loadScheduleTasks();
            return;
        }
        const proxyEn = document.getElementById('sch-ov-proxy-enabled');
        if (proxyEn) proxyEn.checked = !!task.proxy;
        const proxyIn = document.getElementById('sch-ov-proxy');
        if (proxyIn) proxyIn.value = task.proxy || '';
        const cookieEn = document.getElementById('sch-ov-cookie-enabled');
        if (cookieEn) cookieEn.checked = !!task.cookieBound;
        const capabilities = updateScheduleCredentialControls(
            'sch-ov', sourceType, {task});
        if (!capabilities.supportsCookie && !capabilities.supportsProxy) return;
        modal.dataset.taskId = String(Number(id));
        modal.dataset.sourceType = sourceType;
        modal.dataset.sourceActivationToken = sourceActivationToken;
        const title = document.getElementById('schedule-override-title');
        if (title) title.textContent = (capabilities.presentation || {}).modalTitle
            || scheduleOverrideActionLabel(capabilities).replace(/^[^\p{L}\p{N}]+/u, '');
        const intro = document.getElementById('schedule-override-intro');
        if (intro) intro.textContent = (capabilities.presentation || {}).modalIntro
            || bt('schedule.override.intro-generic', '为该计划任务指定独立的代理或来源凭证；取消已生效的选项并保存会清除对应设置。');
        onScheduleOverrideToggle('sch-ov');
        setScheduleOverrideStatus('');
        modal.hidden = false;
        document.body.classList.add('schedule-modal-open');
    }

    function closeScheduleOverrideModal() {
        const modal = document.getElementById('schedule-override-modal');
        if (!modal) return;
        modal.hidden = true;
        delete modal.dataset.taskId;
        delete modal.dataset.sourceType;
        delete modal.dataset.sourceActivationToken;
        document.body.classList.remove('schedule-modal-open');
    }

    // 弹窗保存：校验 → 取消勾选的清除确认 → 逐项调用后端。失败把原因留在弹窗里（不关闭），
    // 成功关闭弹窗并在卡片 tips 区给出反馈。
    async function saveScheduleOverride() {
        const modal = document.getElementById('schedule-override-modal');
        if (!modal || modal.hidden) return;
        const id = Number(modal.dataset.taskId);
        const task = scheduleTaskById(id);
        if (!task) {
            setScheduleOverrideStatus(bt('schedule.snapshot.error.not-found', '未找到任务，请重新加载列表'), 'error');
            return;
        }
        const sourceType = task.sourceType || task.type;
        const expectedSourceType = modal.dataset.sourceType || '';
        const expectedActivationToken = modal.dataset.sourceActivationToken || '';
        const taskActivationToken = typeof task.sourceActivationToken === 'string'
            ? task.sourceActivationToken : '';
        if (!expectedActivationToken || sourceType !== expectedSourceType
                || taskActivationToken !== expectedActivationToken) {
            setScheduleOverrideStatus(
                bt('schedule.error.concurrent-change', '任务状态已变化，请刷新后重试'), 'error');
            loadScheduleTasks();
            return;
        }
        let sourceLease;
        try {
            sourceLease = scheduleSourceRuntime().activationLease(sourceType);
        } catch (e) {
            setScheduleOverrideStatus(
                bt('schedule.error.source-editor-unavailable', '计划任务来源编辑器当前不可用'), 'error');
            return;
        }
        if (sourceLease.activationToken !== expectedActivationToken) {
            setScheduleOverrideStatus(
                bt('schedule.error.concurrent-change', '任务状态已变化，请刷新后重试'), 'error');
            loadScheduleTasks();
            return;
        }
        const ov = readScheduleOverrideInputs('sch-ov', sourceType, {task});
        const error = await validateScheduleOverrideInputs(ov, task, sourceType);
        if (!scheduleLeaseCurrent(sourceLease)) return;
        if (error) {
            setScheduleOverrideStatus(error, 'error');
            return;
        }
        const confirmedClears = await confirmScheduleOverrideClears(ov, task, sourceLease);
        if (!scheduleLeaseCurrent(sourceLease) || !confirmedClears) return;
        const result = await applyScheduleOverrides(
            id, ov, task, sourceLease.signal, expectedActivationToken);
        if (!scheduleLeaseCurrent(sourceLease)) return;
        if (!result.ok) {
            setScheduleOverrideStatus(result.error, 'error');
            loadScheduleTasks();
            return;
        }
        closeScheduleOverrideModal();
        setScheduleCardTip(id, result.applied
            ? bt('schedule.status.override-saved', '已更新该任务的专用代理 / 来源凭证设置')
            : bt('schedule.status.override-unchanged', '专用代理 / 来源凭证设置没有变化'), 'success');
        loadScheduleTasks();
    }

    // 整列渲染所依据的卡片级数据签名：仅当这些字段变化时才需要重建整列 DOM。
    // 不含队列正文 / 展开态——它们由 SSE / 快照单独更新、由用户操作单独切换，不应触发整列重建。
    // 单卡片的渲染签名：仅当该任务的卡片级数据（状态灯/动作按钮/徽章/触发与时间/参数快照）变化时才需要替换 DOM。
    // 不含队列正文 / 待重试面板内容——那两个面板的 DOM 在 diff 替换时被「内 HTML + 滚动位置」整体迁移到新卡片上。
    function scheduleCardRenderSignature(t) {
        const credentialUi = scheduleTaskCredentialUi(t);
        return JSON.stringify([
            t.name, t.enabled, t.sourceType, t.cookieBound, t.proxy, t.runState,
            t.lastStatus, t.lastMessage, t.runStartedTime, t.nextRunTime, t.lastRunTime,
            t.presentationJson, t.presentation, t.sourceAvailable, t.sourceActivationToken,
            t.accountId, t.ackWarningTime, t.pendingRetryArmed, t.suspendReason, t.suspendCode,
            credentialUi.badgeLabel, credentialUi.overrideLabel, credentialUi.showOverride
        ]);
    }

    function scheduleTaskCredentialUi(task) {
        const sourceType = task.sourceType || task.type;
        const runtime = scheduleSourceRuntime();
        const sourceActive = !!(runtime && typeof runtime.isAvailable === 'function'
            && runtime.isAvailable(sourceType));
        const capabilities = sourceActive
            ? scheduleCredentialCapabilities(sourceType, {task})
            : {supportsCookie: false, supportsProxy: false, presentation: {}};
        const p = capabilities.presentation || {};
        let badgeLabel = null;
        if (capabilities.supportsCookie) {
            badgeLabel = task.cookieBound
                ? (p.boundLabel || bt('schedule.credential.bound', '已绑定凭证'))
                : (p.unboundLabel || bt('schedule.credential.unbound', '未绑定凭证'));
        } else if (!sourceActive && task.cookieBound) {
            badgeLabel = bt('schedule.credential.bound', '已绑定凭证');
        }
        return {
            badgeLabel,
            showOverride: sourceActive && (capabilities.supportsCookie || capabilities.supportsProxy),
            overrideLabel: scheduleOverrideActionLabel(capabilities),
            proxyLabel: p.proxyBadgeLabel || bt('schedule.badge.custom-proxy', '单独代理')
        };
    }

    // 过度访问横幅是一个独立区段（按 accountId 分组、行为是账号级，不绑定任何具体卡片）；
    // 签名只看「哪些账号挂起 + 各账号挂起任务数」，签名相同就不重建横幅 DOM。
    function scheduleBannerRenderSignature(tasks) {
        const counts = new Map();
        tasks.forEach(t => {
            if (t.lastStatus === 'OVERUSE_PAUSED' && t.accountId) {
                counts.set(t.accountId, (counts.get(t.accountId) || 0) + 1);
            }
        });
        return JSON.stringify([...counts.entries()].sort());
    }

    async function loadScheduleTasks() {
        const list = document.getElementById('schedule-list');
        if (!list) return;
        const runtime = scheduleSourceRuntime();
        if (runtime && isAdmin) {
            try {
                await runtime.refresh(false);
            } catch (e) {
                // manifest 暂不可用时继续展示持久化 presentation，只禁用来源编辑。
            }
        }
        // 语言切换路径：scheduleLastRenderedLang 与当前不一致时，先把签名清空，让本轮所有卡片
        // 都通过 replaceScheduleCardPreservingInner 走 replace，确保头/徽章/状态灯/动作按钮换语言；
        // 等列表渲染完，再为展开的「本轮队列详情」与「待重试」面板补一次重渲染（preserve-inner 会
        // 把这两块的旧 innerHTML 搬到新卡片，不主动刷一次会停留在旧语言）。
        const currentLang = uiLang();
        const langChanged = scheduleLastRenderedLang != null && scheduleLastRenderedLang !== currentLang;
        if (langChanged) {
            scheduleCardSignatures.clear();
            scheduleBannerSignature = null;
        }
        try {
            const res = await fetch(`${BASE}/api/schedule/tasks`, {credentials: 'same-origin'});
            if (!res.ok) {
                renderScheduleListPlaceholder(list, bt('schedule.list.load-failed', '加载失败'));
                return;
            }
            const tasks = await res.json();
            scheduleTasksCache = Array.isArray(tasks) ? tasks : [];

            // 不论列表是否为空：清理已不存在任务的 SSE 监听 / 模型 / 缓存，
            // 否则旧 handler 残留在 state.sseListeners，可能消费同 artworkId 的事件、并阻止
            // stopSchedulePolling 关闭共享 SSE 连接（条件含 sseListeners 为空）。
            const liveIds = new Set(scheduleTasksCache.map(t => Number(t.id)));
            releaseStaleScheduleQueueIds(liveIds);

            if (scheduleTasksCache.length === 0) {
                renderScheduleListPlaceholder(list, bt('schedule.list.empty', '暂无计划任务'));
            } else {
                if (scheduleEmptyStateRendered) {
                    // 上一拍是空态占位符，清掉后才能开始按卡片 diff。
                    list.innerHTML = '';
                    scheduleEmptyStateRendered = false;
                    scheduleBannerSignature = null;
                    scheduleCardSignatures.clear();
                }
                renderScheduleBannersDiff(list);
                renderScheduleCardsDiff(list);
                if (langChanged) {
                    // 展开的「本轮队列详情」：先用 localizer 即时重渲染（已含 rawStatus 的新模型立即切语言），
                    // 再异步触发一次 fetchScheduleQueue 让后端有新数据时重建模型；旧版本 bake 过翻译的
                    // 缓存项需要靠这次重建才能跟随语言变化。
                    scheduleExpandedQueues.forEach(id => {
                        renderScheduleQueueBodyInto(id);
                        fetchScheduleQueue(id);
                    });
                    scheduleTasksCache.forEach(t => {
                        const panel = document.getElementById(`schedule-pending-${t.id}`);
                        if (panel && !panel.hidden) loadPendingPanel(t.id);
                    });
                }
            }
            scheduleLastRenderedLang = currentLang;
            // 无论是否重建整列：运行 / 排队中的展开卡片随本轮轮询拉取最新队列快照，非运行态保持缓存。
            refreshExpandedScheduleQueues();
        } catch (e) {
            renderScheduleListPlaceholder(list, bt('schedule.list.load-failed', '加载失败'));
        }
    }

    function renderScheduleListPlaceholder(list, text) {
        list.innerHTML = `<div class="schedule-empty">${escHtml(text)}</div>`;
        scheduleEmptyStateRendered = true;
        scheduleBannerSignature = null;
        scheduleCardSignatures.clear();
    }

    // 横幅按 accountId 分组、与卡片解耦：签名只看挂起账号的集合与各账号挂起任务计数，签名不变就不动 DOM。
    function renderScheduleBannersDiff(list) {
        const sig = scheduleBannerRenderSignature(scheduleTasksCache);
        if (sig === scheduleBannerSignature) return;
        scheduleBannerSignature = sig;
        const html = renderOveruseBanners(scheduleTasksCache);
        let wrap = list.querySelector(':scope > .schedule-overuse-banners');
        if (html) {
            const wrapped = `<div class="schedule-overuse-banners">${html}</div>`;
            if (wrap) {
                wrap.outerHTML = wrapped;
            } else {
                list.insertAdjacentHTML('afterbegin', wrapped);
            }
        } else if (wrap) {
            wrap.remove();
        }
    }

    // 按卡片 diff：仅对签名变化的卡片做替换，替换时把内部「队列正文/待重试面板」的 innerHTML、scrollTop、
    // 展开折叠态原样迁移到新卡片，避免「点暂停/恢复 → 整列 innerHTML 重建 → 展开的队列 DOM 被销毁 →
    // 队列滚动条回到顶部」这条问题路径。
    function renderScheduleCardsDiff(list) {
        const existing = new Map();
        list.querySelectorAll(':scope > .schedule-card').forEach(card => {
            existing.set(Number(card.dataset.taskId), card);
        });
        const liveIds = new Set();
        // 锚点：插入卡片的位置紧跟横幅之后（或在列表最前端，如无横幅）。
        const banners = list.querySelector(':scope > .schedule-overuse-banners');
        let prev = banners;
        scheduleTasksCache.forEach(t => {
            const id = Number(t.id);
            liveIds.add(id);
            const sig = scheduleCardRenderSignature(t);
            let card = existing.get(id);
            if (!card) {
                card = buildScheduleCardElement(t);
                scheduleCardSignatures.set(id, sig);
            } else if (sig !== scheduleCardSignatures.get(id)) {
                card = replaceScheduleCardPreservingInner(card, t);
                scheduleCardSignatures.set(id, sig);
            }
            const expected = prev ? prev.nextElementSibling : list.firstElementChild;
            if (expected !== card) {
                if (prev) prev.insertAdjacentElement('afterend', card);
                else list.insertAdjacentElement('afterbegin', card);
            }
            prev = card;
        });
        existing.forEach((card, id) => {
            if (!liveIds.has(id)) {
                card.remove();
                scheduleCardSignatures.delete(id);
            }
        });
    }

    function buildScheduleCardElement(t) {
        const temp = document.createElement('div');
        temp.innerHTML = renderScheduleTaskCard(t).trim();
        return temp.firstElementChild;
    }

    // 替换一张卡片但保留两个有状态子区块（队列正文 / 待重试面板）的 DOM 状态：内 HTML / 隐藏态 / 滚动位置。
    // 队列正文的折叠 caret / aria-expanded 同步矫正为保留下来的展开态。
    function replaceScheduleCardPreservingInner(existingCard, t) {
        const oldQueueBody = existingCard.querySelector('.schedule-queue-body');
        const oldPending = existingCard.querySelector('.schedule-pending-panel');
        // 卡片顶部 tips 区域：保留刚刚因操作写入的反馈，避免轮询重渲染把它清掉。
        const oldTip = existingCard.querySelector('.schedule-card-tip');
        const tipState = oldTip ? {text: oldTip.textContent, color: oldTip.style.color} : null;
        // 真正的滚动容器是 .schedule-queue-body 内部的 .schedule-queue-list（见 renderScheduleQueueBodyInto），
        // 因此 scrollTop 取内层 list 的值，替换后再写回新 list 上，避免队列滚动条跳回顶部。
        const oldQueueList = oldQueueBody ? oldQueueBody.querySelector('.schedule-queue-list') : null;
        const queueState = oldQueueBody ? {
            inner: oldQueueBody.innerHTML,
            scrollTop: oldQueueList ? oldQueueList.scrollTop : 0,
            expanded: !oldQueueBody.hasAttribute('hidden')
        } : null;
        const pendingState = oldPending ? {
            inner: oldPending.innerHTML,
            expanded: !oldPending.hasAttribute('hidden')
        } : null;

        const newCard = buildScheduleCardElement(t);

        const newQueueBody = newCard.querySelector('.schedule-queue-body');
        if (newQueueBody && queueState) {
            newQueueBody.innerHTML = queueState.inner;
            if (queueState.expanded) newQueueBody.removeAttribute('hidden');
            else newQueueBody.setAttribute('hidden', '');
            const toggle = newCard.querySelector('.schedule-queue-toggle');
            if (toggle) {
                toggle.setAttribute('aria-expanded', String(queueState.expanded));
                const caret = toggle.querySelector('.schedule-queue-caret');
                if (caret) caret.textContent = queueState.expanded ? '▾' : '▸';
            }
        }
        const newPending = newCard.querySelector('.schedule-pending-panel');
        if (newPending && pendingState) {
            newPending.innerHTML = pendingState.inner;
            if (pendingState.expanded) newPending.removeAttribute('hidden');
            else newPending.setAttribute('hidden', '');
        }
        const newTip = newCard.querySelector('.schedule-card-tip');
        if (newTip && tipState && tipState.text) {
            newTip.textContent = tipState.text;
            newTip.style.color = tipState.color;
        }

        existingCard.replaceWith(newCard);

        // scrollTop 必须在 element 已经在 document 中之后设置，否则浏览器会丢弃。
        if (newQueueBody && queueState && queueState.scrollTop) {
            const newQueueList = newQueueBody.querySelector('.schedule-queue-list');
            if (newQueueList) newQueueList.scrollTop = queueState.scrollTop;
        }
        return newCard;
    }

    function renderScheduleTaskCard(t) {
        const sourceType = t.sourceType || t.type;
        const kind = scheduleTaskKind(t);
        const typeLabel = t.sourceAvailable === false ? sourceType : scheduleTypeLabel(sourceType);
        const kindLabel = scheduleKindLabel(kind);
        const triggerLabel = scheduleTriggerLabel(t);
        const credentialUi = scheduleTaskCredentialUi(t);
        const enabledLabel = t.enabled ? bt('schedule.state.enabled', '已启用') : bt('schedule.state.disabled', '已停用');
        const light = scheduleStatusLight(t);

        // 功能区按钮的状态门（与后端 ScheduleService 守卫一致）：
        // busy=运行/排队中；suspended=任意 canonical 挂起原因（兼容旧状态字段只作降级）。
        const busy = ['RUNNING', 'QUEUED', 'CANCEL_REQUESTED'].includes(t.runState);
        const paused = t.suspendReason === 'MANUAL' || t.lastStatus === 'PAUSED';
        const suspended = !!t.suspendReason || paused
            || t.lastStatus === 'OVERUSE_PAUSED' || t.lastStatus === 'AUTH_EXPIRED';
        const automaticSuspension = ['SOURCE_UNAVAILABLE', 'EXECUTOR_UNAVAILABLE', 'QUIESCED']
            .includes(t.suspendReason);
        const manualRecoveryRequired = suspended && !automaticSuspension;
        const busyTip = bt('schedule.disabled.busy', '任务运行 / 排队中，暂不可操作');
        const runTip = busy ? busyTip
            : (!t.enabled ? bt('schedule.disabled.run-disabled', '任务已停用，请先启用')
                : (automaticSuspension
                    ? bt('schedule.disabled.run-capability', '所需插件能力暂不可用，恢复后会自动重试')
                    : bt('schedule.disabled.run-suspended', '任务暂停 / 挂起中，请先恢复或重新授权')));
        const pauseTip = bt('schedule.disabled.pause-idle', '任务未在运行，无需暂停；如需停止自动运行请用「停用」');
        const runAttr = (t.enabled && !busy && !suspended) ? '' : `disabled title="${escHtml(runTip)}"`;
        const resumeAttr = !busy ? '' : `disabled title="${escHtml(busyTip)}"`;
        const pauseAttr = busy ? '' : `disabled title="${escHtml(pauseTip)}"`;
        const busyAttr = busy ? `disabled title="${escHtml(busyTip)}"` : '';
        const sourceEditable = t.sourceAvailable !== false
            && scheduleSourceRuntime() && scheduleSourceRuntime().isAvailable(sourceType);
        const editAttr = busy
            ? busyAttr
            : (sourceEditable ? '' : `disabled title="${escHtml(bt(
                'schedule.error.source-editor-unavailable', '计划任务来源编辑器当前不可用'))}"`);
        return `
        <div class="schedule-card${t.enabled ? '' : ' schedule-card-disabled'}" data-task-id="${t.id}">
            <div class="schedule-card-tip" id="schedule-card-tip-${t.id}" role="status" aria-live="polite"></div>
            <div class="schedule-card-head">
                <div class="schedule-card-head-main">
                    <span class="schedule-card-name">${escHtml(t.name)}</span>
                    <span class="schedule-badge">${escHtml(typeLabel)}</span>
                    ${kind ? `<span class="schedule-badge">${escHtml(kindLabel)}</span>` : ''}
                    ${credentialUi.badgeLabel ? `<span class="schedule-badge${t.cookieBound ? ' schedule-badge-ok' : ''}">${escHtml(credentialUi.badgeLabel)}</span>` : ''}
                    ${t.proxy ? `<span class="schedule-badge schedule-badge-ok" title="${escHtml(t.proxy)}">${escHtml(credentialUi.proxyLabel)}</span>` : ''}
                    <span class="schedule-badge${t.enabled ? ' schedule-badge-ok' : ' schedule-badge-disabled'}">${escHtml(enabledLabel)}</span>
                </div>
                <span class="schedule-status-light schedule-status-light-${light.tone}${light.live ? ' schedule-status-light-live' : ''}" title="${escHtml(light.text)}">
                    <span class="schedule-light-dot" aria-hidden="true"></span>
                    <span class="schedule-light-text">${escHtml(light.text)}</span>
                </span>
            </div>
            <div class="schedule-card-meta">
                <div>${escHtml(bt('schedule.meta.trigger', '触发：'))}${escHtml(triggerLabel)}</div>
                <div>${escHtml(bt('schedule.meta.next', '下次运行：'))}${escHtml(manualRecoveryRequired
                    ? bt('schedule.meta.next-suspended', '需人工恢复后才会继续')
                    : (automaticSuspension
                        ? bt('schedule.meta.next-capability', '等待插件能力恢复后自动重试')
                        : fmtScheduleTime(t.nextRunTime)))}</div>
                <div>${escHtml(bt('schedule.meta.last', '上次运行：'))}${escHtml(fmtScheduleTime(t.lastRunTime))}</div>
                <div class="schedule-meta-actions">
                    <button type="button" class="btn btn-blue" onclick="showScheduleSnapshot(${t.id})">${escHtml(bt('schedule.snapshot.action.view', '查看任务快照信息'))}</button>
                </div>
            </div>
            <div class="schedule-card-actions">
                <button class="btn btn-cyan" ${runAttr} onclick="runScheduleTask(${t.id})">${escHtml(bt('schedule.action.run', '▶ 立即运行'))}</button>
                ${credentialUi.showOverride ? `<button class="btn btn-blue" ${busyAttr} onclick="showScheduleOverrideModal(${t.id})">${escHtml(credentialUi.overrideLabel)}</button>` : ''}
                ${paused
                    ? `<button class="btn btn-green" ${resumeAttr} onclick="resumeScheduleTask(${t.id})">${escHtml(bt('schedule.action.resume', '▶ 恢复'))}</button>`
                    : `<button class="btn btn-yellow" ${pauseAttr} onclick="pauseScheduleTask(${t.id})">${escHtml(bt('schedule.action.pause', '⏸ 暂停'))}</button>`}
                <button class="btn ${t.enabled ? 'btn-red' : 'btn-green'}" ${busyAttr} onclick="toggleScheduleTask(${t.id}, ${t.enabled ? 'false' : 'true'})">${escHtml(t.enabled ? bt('schedule.action.disable', '⏸ 停用') : bt('schedule.action.enable', '✔ 启用'))}</button>
                <button class="btn btn-purple" ${editAttr} onclick="startEditScheduleTask(${t.id})">${escHtml(bt('schedule.action.edit', '✏ 编辑'))}</button>
                <button class="btn btn-gray" onclick="togglePendingPanel(${t.id})">${escHtml(bt('schedule.action.pending', '🧩 待重试'))}</button>
                <button class="btn btn-red" ${busyAttr} onclick="deleteScheduleTask(${t.id})">${escHtml(bt('schedule.action.delete', '🗑 删除'))}</button>
            </div>
            <div class="schedule-pending-panel" id="schedule-pending-${t.id}" hidden></div>
            ${renderScheduleQueueSection(t)}
        </div>`;
    }

    // 卡片底部「本轮队列详情」可折叠区域：默认折叠；展开态在列表重渲染后从 scheduleExpandedQueues 恢复，
    // 并直接用本地缓存预填充内容（避免闪烁），随后 refreshExpandedScheduleQueues / 展开动作再拉取最新数据。
    function renderScheduleQueueSection(t) {
        const id = Number(t.id);
        const expanded = scheduleExpandedQueues.has(id);
        const title = escHtml(bt('schedule.queue.title', '本轮队列详情'));
        const bodyHtml = expanded ? renderScheduleQueueBody(id) : '';
        return `
            <div class="schedule-queue" data-task-id="${id}">
                <button type="button" class="schedule-queue-toggle" aria-expanded="${expanded}" onclick="toggleScheduleQueue(${id})">
                    <span class="schedule-queue-caret" aria-hidden="true">${expanded ? '▾' : '▸'}</span>
                    <span>${title}</span>
                </button>
                <div class="schedule-queue-body"${expanded ? '' : ' hidden'}>${bodyHtml}</div>
            </div>`;
    }

    function scheduleQueueCacheKey(id) {
        return 'pixiv_schedule_queue_' + Number(id);
    }

    function readScheduleQueueCache(id) {
        try {
            const raw = storeGet(scheduleQueueCacheKey(id));
            if (!raw) return null;
            const parsed = JSON.parse(raw);
            return parsed && Array.isArray(parsed.items) ? parsed : null;
        } catch (e) {
            return null;
        }
    }

    function writeScheduleQueueCache(id, data) {
        try {
            storeSet(scheduleQueueCacheKey(id), JSON.stringify(data));
        } catch (e) { /* 存储不可用时忽略：内存渲染仍可工作 */ }
    }

    // 缓存里随队列一起记录的「该队列所属那一轮运行的完成时刻」（写入时取任务当时的 lastRunTime）。
    // 与任务当前的 lastRunTime 比对即可判断缓存是否已过期：任务又跑过新的一轮（前端没刷到、或后端重启后），
    // 两者就不再一致。无缓存返回 undefined。
    function scheduleQueueCacheRunTime(id) {
        const cache = readScheduleQueueCache(id);
        if (!cache) return undefined;
        return cache.lastRunTime != null ? cache.lastRunTime : null;
    }

    // 缓存队列是否已不属于任务的最新一轮：把缓存记录的运行时刻与任务当前 lastRunTime 比对，不一致即过期。
    // 无缓存时不算过期（fetch 会负责填充）。
    function isScheduleQueueCacheStale(id, task) {
        const cachedRunTime = scheduleQueueCacheRunTime(id);
        if (cachedRunTime === undefined) return false;
        const latestRunTime = task && task.lastRunTime != null ? task.lastRunTime : null;
        return cachedRunTime !== latestRunTime;
    }

    // 丢弃某任务的队列缓存与内存模型：过期时清空，让 renderScheduleQueueBody 即时显示空、
    // 并避免 fetchScheduleQueue 的 keepCache 分支用陈旧队列盖住后端的空响应。
    function discardScheduleQueueCache(id) {
        id = Number(id);
        delete scheduleQueueModels[id];
        try { storeRemove(scheduleQueueCacheKey(id)); } catch (e) { /* 存储不可用时忽略 */ }
    }

    // 计划任务「本轮队列详情」的客户端模型：taskId → 队列项数组（与下载工作区 state.queue 同形，
    // 直接喂给 buildQueueItemHtml 渲染，保证两处队列完全一致）。后端 4s 快照提供权威的发现/终态，
    // SSE 提供运行中的逐图实时进度。
    const scheduleQueueModels = {};
    // 已登记的 SSE 监听器：taskId → { artworkIdKey: fn }，用于精确解绑、避免重复注册或误删工作区监听。
    const scheduleSseHandlers = {};
    // 上一轮轮询时仍在运行的展开任务：用于在运行结束的那一拍补拉一次最终终态快照。
    const scheduleQueueWasRunning = new Set();

    // ── 「本轮队列详情」高频刷新合批 ─────────────────────────────────────────────
    // 并发下载时 SSE 逐图进度事件会高频到达。若每个事件都整块重建 .schedule-queue-body 的 innerHTML
    //（含全部队列行），主线程会被反复的 DOM 拆建占满，交互延迟（INP）随之飙高。
    // 改为：SSE 只 patch 内存模型 + 标记脏行 id，再用节流合批，只替换发生变化的单行 outerHTML；
    // 统计栏 / 当前下载项区域用更低频的独立节流刷新。折叠 / 解绑 / 整块重渲染时清理待执行刷新与脏集合。
    const scheduleQueueDirtyRows = new Map();        // taskId → Set<queueId>：待局部刷新的脏行
    const scheduleQueueRowFlushHandles = new Map();  // taskId → setTimeout 句柄：脏行合批刷新
    const scheduleQueueMetaFlushHandles = new Map(); // taskId → setTimeout 句柄：统计/当前项低频刷新
    const SCHEDULE_QUEUE_ROW_FLUSH_MS = 150;         // 脏行批量刷新节流（100-250ms 区间）
    const SCHEDULE_QUEUE_META_FLUSH_MS = 350;        // 统计栏 / 当前下载项低频刷新（250-500ms 区间）

    // 取某任务的队列模型：内存优先，缺失时从 localStorage 缓存恢复（支持刷新 / 服务重启后继续展示）。
    function getScheduleQueueModel(id) {
        id = Number(id);
        if (scheduleQueueModels[id]) return scheduleQueueModels[id];
        const cache = readScheduleQueueCache(id);
        if (cache && Array.isArray(cache.items)) {
            scheduleQueueModels[id] = cache.items;
            return cache.items;
        }
        return null;
    }

    function getScheduleQueueMeta(id) {
        const cache = readScheduleQueueCache(id);
        return {
            startedTime: cache ? cache.startedTime : null,
            truncated: cache ? !!cache.truncated : false,
            total: cache && typeof cache.total === 'number' ? cache.total : null
        };
    }

    function scheduleTaskById(id) {
        return scheduleTasksCache.find(t => Number(t.id) === Number(id)) || null;
    }

    // 后端队列项状态 → 工作区队列状态 + 原始状态码（未翻译，渲染时再 bt()）。
    // 不在这里 bake bt() 结果：模型会落到 localStorage 与跨语言切换的渲染轮次，bake 后无法跟随语言变化。
    function scheduleStatusToQueue(it) {
        switch (it.status) {
            case 'downloaded':
                return {status: 'completed', rawStatus: 'downloaded'};
            case 'skipped-downloaded':
                return {status: 'skipped', rawStatus: 'skipped-downloaded'};
            case 'skipped-filter':
                return {status: 'skipped', rawStatus: 'skipped-filter'};
            case 'failed':
                return {status: 'failed', rawStatus: 'failed', failureMessage: it.message || null};
            default:
                return {status: 'pending', rawStatus: 'pending'};
        }
    }

    // 后端队列项 → 工作区队列项（同 state.queue 形状），供 buildQueueItemHtml 渲染。
    // 注意：title / lastMessage 不在这里写入；只存 rawTitle / rawStatus / failureMessage 这些与语言
    // 无关的原始字段，渲染时由 localizeScheduleQueueItem 用当前 bt() 派生 title / lastMessage。
    function scheduleItemToQueue(it, type, task) {
        const mapped = scheduleStatusToQueue(it);
        const workType = it.workType || it.kind;
        const registry = window.PixivBatch && window.PixivBatch.queueTypes;
        const base = registry && typeof registry.scheduledQueueItem === 'function'
            ? registry.scheduledQueueItem(workType, it, {
                sourceType: type,
                task: task || null
            })
            : {
                id: String(it.workId == null ? (it.id == null ? '' : it.id) : it.workId),
                kind: workType || 'unknown',
                rawTitle: it.title && String(it.title).trim() ? String(it.title) : null,
                source: 'schedule',
                xRestrict: it.xRestrict == null ? null : it.xRestrict,
                isAi: it.ai === true
            };
        return Object.assign({}, base, {
            status: mapped.status,
            rawStatus: mapped.rawStatus,
            failureMessage: mapped.failureMessage || null,
            totalImages: 0,
            downloadedCount: 0,
            imageProgress: null,
            ugoiraProgress: null,
            // 「下载即自动翻译」实时态（仅小说、后端读取时叠加）：raw 字段，由共享渲染器本地化展示。
            translatePhase: it.translatePhase || null,
            translateElapsed: it.translateElapsedSeconds == null ? 0 : it.translateElapsedSeconds,
            translateSeriesPending: it.translateSeriesPending == null ? 0 : it.translateSeriesPending
        });
    }

    // 渲染前根据当前 UI 语言派生显示字段。模型里禁止 bake i18n 字符串（会被持久化到 localStorage、
    // 跨语言切换继续读到旧译文），所有翻译都集中在这里。
    // 兼容旧缓存：若 rawTitle / rawStatus 不存在（旧版烤过 title / lastMessage 的缓存项），回退用旧字段，
    // 这些条目要到下一次后端拉取重建模型后才会跟随语言切换。
    function localizeScheduleQueueItem(q) {
        const hasRawTitle = Object.prototype.hasOwnProperty.call(q, 'rawTitle');
        const title = hasRawTitle
            ? (q.rawTitle || bt('schedule.queue.no-title', '（暂无标题信息）'))
            : q.title;
        let lastMessage;
        switch (q.rawStatus) {
            case 'skipped-downloaded':
                lastMessage = bt('schedule.queue.status.skipped-downloaded', '已存在，跳过');
                break;
            case 'skipped-filter':
                lastMessage = bt('schedule.queue.status.skipped-filter', '被筛选条件跳过');
                break;
            case 'failed':
                lastMessage = q.failureMessage || bt('schedule.queue.status.failed', '失败');
                break;
            case 'downloaded':
            case 'pending':
                lastMessage = null;
                break;
            default:
                // 旧缓存或 SSE 中途置位（如 downloading / completed-from-pending）没有 rawStatus；
                // lastMessage 留给共享渲染器用 queueStatusText(status) 兜底。
                lastMessage = q.lastMessage != null ? q.lastMessage : null;
        }
        return Object.assign({}, q, {title, lastMessage});
    }

    // 用后端快照重建模型，同时保留 SSE 实时进度：后端仍为 pending 而本地正在下载时沿用本地实时态，
    // 避免每 4s 快照把进行中的进度条打回原形。
    function mergeScheduleQueueModel(id, incoming, type) {
        const prev = scheduleQueueModels[Number(id)] || [];
        const task = scheduleTaskById(id);
        const prevById = {};
        prev.forEach(q => { prevById[q.id] = q; });
        return incoming.map(it => {
            const q = scheduleItemToQueue(it, type, task);
            const old = prevById[q.id];
            if (old && q.status === 'pending' && old.status === 'downloading') {
                q.status = 'downloading';
                q.totalImages = old.totalImages || 0;
                q.downloadedCount = old.downloadedCount || 0;
                q.imageProgress = old.imageProgress || null;
                q.ugoiraProgress = old.ugoiraProgress || null;
            } else if (old) {
                q.totalImages = q.totalImages || old.totalImages || 0;
                q.downloadedCount = q.downloadedCount || old.downloadedCount || 0;
            }
            return q;
        });
    }

    // 计划队列详情的 Vue reactive 岛句柄（batch-queue-vue.js 注册）。缺失 / 未激活 / 挂载失败时回退命令式。
    function scheduleQueueVue() {
        return window.PixivBatch && window.PixivBatch.queueVue;
    }

    // 给 Vue 岛提供「读当前任务本轮队列快照」的闭包：派生与命令式 renderScheduleQueueBody 完全同口径的
    // 状态 / 统计 / 当前卡 / 列表（模型仍为 raw 字段，此处经 localizeScheduleQueueItem 按当前语言派生），
    // 由 Vue 组件用共享的 buildQueueItemHtml / formatCurrentCardHtml 渲染（与普通队列不分叉）。
    function scheduleQueueVueContext(id, body) {
        id = Number(id);
        return {
            bodyEl: body,
            read: function () {
                const model = getScheduleQueueModel(id) || [];
                const localized = model.map(localizeScheduleQueueItem);
                const current = localized.find(q => q.status === 'downloading') || null;
                const s = computeScheduleQueueStats(model);
                return {
                    statusText: buildScheduleQueueStatusText(id, model),
                    statsText: formatStatsText(s.pending, s.success, s.failed, s.active, s.skipped),
                    current: current,
                    items: localized
                };
            }
        };
    }

    function renderScheduleQueueBodyInto(id) {
        if (!scheduleExpandedQueues.has(Number(id))) return;
        const wrap = document.querySelector(`.schedule-queue[data-task-id="${Number(id)}"]`);
        if (!wrap) return;
        const body = wrap.querySelector('.schedule-queue-body');
        if (!body) return;
        const qv = scheduleQueueVue();
        if (qv && qv.ensureScheduleQueue(Number(id), scheduleQueueVueContext(id, body))) {
            // Vue 已接管该 body：合并一次 reactive 同步（Vue 据 :key + v-html 仅 patch 变化，不整块重建 .schedule-queue-body）。
            qv.syncScheduleQueue(Number(id));
            cancelScheduleQueueFlush(id); // 整体已交给 reactive：丢弃命令式脏行 / 低频刷新
            return;
        }
        // —— 命令式回退（Vue 不可用 / 尚未挂载完成 / 挂载失败）——
        // 保留滚动位置：SSE / 快照刷新会替换正文 innerHTML，不保留则滚动条每次跳回顶部。
        const prevList = body.querySelector('.schedule-queue-list');
        const prevScroll = prevList ? prevList.scrollTop : 0;
        body.innerHTML = renderScheduleQueueBody(id);
        const newList = body.querySelector('.schedule-queue-list');
        if (newList) newList.scrollTop = prevScroll;
        // 整块已重渲染（含状态/统计/当前项/全部行）：丢弃此前累积的脏行与待执行的局部/低频刷新，避免重复刷新。
        cancelScheduleQueueFlush(id);
    }

    // 展开某任务的队列：切换箭头，先用缓存模型即时渲染，再向后端拉取最新一轮队列；折叠则不请求并解绑 SSE。
    function toggleScheduleQueue(id) {
        id = Number(id);
        const wrap = document.querySelector(`.schedule-queue[data-task-id="${id}"]`);
        if (!wrap) return;
        const body = wrap.querySelector('.schedule-queue-body');
        const toggleBtn = wrap.querySelector('.schedule-queue-toggle');
        const caret = wrap.querySelector('.schedule-queue-caret');
        if (scheduleExpandedQueues.has(id)) {
            scheduleExpandedQueues.delete(id);
            unsubscribeScheduleQueueSse(id);
            cancelScheduleQueueFlush(id); // 折叠：取消待执行的局部刷新，隐藏视图不再消耗主线程
            const qvCollapse = scheduleQueueVue();
            if (qvCollapse) qvCollapse.unmountScheduleQueue(id); // 卸载 reactive 岛：再展开时命令式首屏 + 重挂
            if (body) body.hidden = true;
            if (toggleBtn) toggleBtn.setAttribute('aria-expanded', 'false');
            if (caret) caret.textContent = '▸';
            return;
        }
        scheduleExpandedQueues.add(id);
        // 展开即比对：缓存队列若不属于任务最新一轮（任务又跑过新的一轮、前端没刷到，或后端重启丢失内存），
        // 先清掉过期缓存再渲染 —— 这样立即显示空、随后 fetch；后端若已无该轮队列则保持空，不再用旧队列盖住。
        if (isScheduleQueueCacheStale(id, scheduleTaskById(id))) {
            discardScheduleQueueCache(id);
        }
        if (body) {
            body.hidden = false;
            body.innerHTML = renderScheduleQueueBody(id); // 缓存模型即时渲染（可能为空）
        }
        if (toggleBtn) toggleBtn.setAttribute('aria-expanded', 'true');
        if (caret) caret.textContent = '▾';
        fetchScheduleQueue(id); // 访问即拉取最新
    }

    // 列表重渲染后：运行 / 排队中的展开卡片拉取最新队列（实现「运行中展开则自动刷新」），
    // 运行刚结束的那一拍补拉一次终态快照，其余非运行态保持缓存渲染、撤掉 SSE 监听。
    function refreshExpandedScheduleQueues() {
        scheduleTasksCache.forEach(t => {
            const id = Number(t.id);
            const running = ['RUNNING', 'QUEUED', 'CANCEL_REQUESTED'].includes(t.runState);
            if (!scheduleExpandedQueues.has(id)) {
                scheduleQueueWasRunning.delete(id);
                return;
            }
            if (running) {
                scheduleQueueWasRunning.add(id);
                fetchScheduleQueue(id);
            } else if (scheduleQueueWasRunning.has(id)) {
                scheduleQueueWasRunning.delete(id);
                fetchScheduleQueue(id); // 运行刚结束：拉取最终终态快照
            } else {
                unsubscribeScheduleQueueSse(id);
            }
        });
    }

    async function fetchScheduleQueue(id) {
        id = Number(id);
        const task = scheduleTaskById(id);
        try {
            const res = await fetch(`${BASE}/api/schedule/tasks/${id}/queue`, {credentials: 'same-origin'});
            if (!res.ok) return; // 失败时保留已有模型渲染
            const data = await res.json();
            const incoming = Array.isArray(data.items) ? data.items : [];
            const cached = readScheduleQueueCache(id);
            // 后端无当轮队列（如进程重启后）而本地仍有缓存时，保留缓存继续展示，不被空队列覆盖。
            const keepCache = incoming.length === 0 && data.startedTime == null
                && cached && Array.isArray(cached.items) && cached.items.length > 0;
            if (!keepCache) {
                const model = mergeScheduleQueueModel(id, incoming,
                    task ? (task.sourceType || task.type) : null);
                scheduleQueueModels[id] = model;
                writeScheduleQueueCache(id, {
                    startedTime: data.startedTime != null ? data.startedTime : null,
                    // 记录该队列所属那一轮的运行时刻（任务当前 lastRunTime），供下次展开时比对是否过期。
                    lastRunTime: task && task.lastRunTime != null ? task.lastRunTime : null,
                    truncated: !!data.truncated,
                    total: typeof data.total === 'number' ? data.total : model.length,
                    items: model,
                    savedAt: Date.now()
                });
            }
            renderScheduleQueueBodyInto(id);
            // 运行中 + 展开：订阅 SSE 逐图实时进度；否则解绑。
            if (scheduleExpandedQueues.has(id) && task
                && ['RUNNING', 'QUEUED', 'CANCEL_REQUESTED'].includes(task.runState)) {
                subscribeScheduleQueueSse(id);
            } else {
                unsubscribeScheduleQueueSse(id);
            }
        } catch (e) { /* 网络异常：保留模型渲染 */ }
    }

    // 按工作区口径统计模型各状态计数（与 updateStats 同义）。
    function computeScheduleQueueStats(model) {
        const count = s => model.filter(q => q.status === s).length;
        return {
            success: count('completed'),
            failed: count('failed'),
            active: count('downloading'),
            skipped: count('skipped'),
            pending: model.filter(q => ['idle', 'pending', 'paused'].includes(q.status)).length
        };
    }

    // 状态栏文案（对应工作区 #status-bar）：任务运行状态文案 + 本轮开始时间 + 截断提示。
    // 抽成独立函数，让整块渲染与低频 meta 刷新（refreshScheduleQueueMeta）共用一处口径。
    function buildScheduleQueueStatusText(id, model) {
        const task = scheduleTaskById(id);
        const meta = getScheduleQueueMeta(id);
        let statusText = task ? scheduleStatusLight(task).text : bt('schedule.light.never', '等待首次运行');
        if (meta.startedTime) {
            statusText += ' · ' + bt('schedule.queue.started', '本轮开始：{time}', {time: fmtScheduleTime(meta.startedTime)});
        }
        if (meta.truncated) {
            statusText += ' · ' + bt('schedule.queue.truncated', '作品过多，仅记录并展示前 {count} 项', {count: model.length});
        }
        return statusText;
    }

    // 完整照搬下载工作区底部的「状态栏 + 统计栏 + 当前下载卡 + 下载队列」四段结构，
    // 仅把数据源换成本任务的队列模型；各段分别复用 #status-bar / #stats-bar / #current-card / #queue-list 的样式与格式化函数。
    function renderScheduleQueueBody(id) {
        const model = getScheduleQueueModel(id) || [];
        const statusText = buildScheduleQueueStatusText(id, model);
        const s = computeScheduleQueueStats(model);
        // 渲染前用 localizeScheduleQueueItem 派生 title / lastMessage，确保跟随当前 UI 语言；
        // 模型本身仍是 raw 字段，下次语言切换重渲染再次派生即可。
        const localized = model.map(localizeScheduleQueueItem);
        const current = localized.find(q => q.status === 'downloading') || null;

        const statusLine = `<div class="schedule-queue-status">${escHtml(statusText)}</div>`;
        const statsLine = `<div class="schedule-queue-stats">${escHtml(formatStatsText(s.pending, s.success, s.failed, s.active, s.skipped))}</div>`;
        const currentCard = `<div class="schedule-queue-current">${formatCurrentCardHtml(current)}</div>`;
        // 每行带上 data-queue-id（= 模型项 id），供 flushScheduleQueueRows 局部替换单行 outerHTML 时定位。
        const listInner = localized.length
            ? localized.map(q => buildQueueItemHtml(q, {removable: false, queueId: q.id})).join('')
            : `<div class="queue-empty">${escHtml(bt('status.queue-empty', '队列为空'))}</div>`;
        const listCard = `<div class="schedule-queue-list">${listInner}</div>`;
        return statusLine + statsLine + currentCard + listCard;
    }

    // 标记某任务的某行待刷新：只 patch 完模型后调用，合批后由 flushScheduleQueueRows 局部替换该行。
    function markScheduleQueueRowDirty(id, qId) {
        id = Number(id);
        if (!scheduleExpandedQueues.has(id)) return; // 已折叠：无可见 DOM，丢弃
        let set = scheduleQueueDirtyRows.get(id);
        if (!set) { set = new Set(); scheduleQueueDirtyRows.set(id, set); }
        set.add(String(qId));
        if (!scheduleQueueRowFlushHandles.has(id)) {
            scheduleQueueRowFlushHandles.set(id,
                setTimeout(() => flushScheduleQueueRows(id), SCHEDULE_QUEUE_ROW_FLUSH_MS));
        }
        armScheduleQueueMetaFlush(id);
    }

    // 安排一次低频的统计栏 / 当前下载项刷新（已排程则复用，避免每行都重算整块统计/当前卡）。
    function armScheduleQueueMetaFlush(id) {
        id = Number(id);
        if (scheduleQueueMetaFlushHandles.has(id)) return;
        scheduleQueueMetaFlushHandles.set(id, setTimeout(() => {
            scheduleQueueMetaFlushHandles.delete(id);
            refreshScheduleQueueMeta(id);
        }, SCHEDULE_QUEUE_META_FLUSH_MS));
    }

    // 合批刷新脏行：只对发生变化的行重新生成单行 HTML 并替换其 outerHTML；
    // 找不到对应 DOM 行（如刚展开还没渲染过 list / 行被快照重建移除）时退化为一次整块渲染。
    function flushScheduleQueueRows(id) {
        id = Number(id);
        scheduleQueueRowFlushHandles.delete(id);
        const dirty = scheduleQueueDirtyRows.get(id);
        scheduleQueueDirtyRows.delete(id);
        const qv = scheduleQueueVue();
        if (qv && qv.isScheduleActive(id)) {
            // Vue 已接管：脏行 patch 交给 reactive 同步（合并到一帧、仅变化的行重渲染，不整块重建 .schedule-queue-body）。
            qv.syncScheduleQueue(id);
            return;
        }
        if (!dirty || dirty.size === 0) return;
        if (!scheduleExpandedQueues.has(id)) return;
        const wrap = document.querySelector(`.schedule-queue[data-task-id="${id}"]`);
        const listEl = wrap ? wrap.querySelector('.schedule-queue-list') : null;
        const model = scheduleQueueModels[id];
        if (!wrap || !listEl || !model) {
            renderScheduleQueueBodyInto(id); // 列表尚未渲染或模型缺失：整块兜底（频率低，可接受）
            return;
        }
        const byId = {};
        model.forEach(q => { byId[q.id] = q; });
        let needFull = false;
        dirty.forEach(qId => {
            const q = byId[qId];
            if (!q) return; // 模型里已无此项（被快照重建移除）：留给后续整块渲染
            const row = listEl.querySelector(`.queue-item[data-queue-id="${qId}"]`);
            if (!row) { needFull = true; return; }
            row.outerHTML = buildQueueItemHtml(localizeScheduleQueueItem(q), {removable: false, queueId: q.id});
        });
        if (needFull) {
            renderScheduleQueueBodyInto(id);
        } else {
            armScheduleQueueMetaFlush(id); // 行已就地更新；统计 / 当前下载项交给低频 meta 刷新
        }
    }

    // 低频刷新统计栏 + 当前下载项 + 状态栏（不触碰队列列表 DOM，避免整块重建）。
    function refreshScheduleQueueMeta(id) {
        id = Number(id);
        if (!scheduleExpandedQueues.has(id)) return;
        const qv = scheduleQueueVue();
        if (qv && qv.isScheduleActive(id)) {
            qv.syncScheduleQueue(id); // 状态 / 统计 / 当前卡随整份 reactive 同步一并更新
            return;
        }
        const wrap = document.querySelector(`.schedule-queue[data-task-id="${id}"]`);
        if (!wrap) return;
        const body = wrap.querySelector('.schedule-queue-body');
        if (!body || body.hidden) return;
        const model = getScheduleQueueModel(id) || [];
        const statusEl = body.querySelector('.schedule-queue-status');
        if (statusEl) statusEl.textContent = buildScheduleQueueStatusText(id, model);
        const statsEl = body.querySelector('.schedule-queue-stats');
        if (statsEl) {
            const s = computeScheduleQueueStats(model);
            statsEl.textContent = formatStatsText(s.pending, s.success, s.failed, s.active, s.skipped);
        }
        const currentEl = body.querySelector('.schedule-queue-current');
        if (currentEl) {
            const cur = model.find(q => q.status === 'downloading');
            currentEl.innerHTML = formatCurrentCardHtml(cur ? localizeScheduleQueueItem(cur) : null);
        }
    }

    // 取消某任务待执行的局部刷新并清空脏集合：折叠 / 解绑 SSE / 整块重渲染 / 任务下线时调用，
    // 避免隐藏视图或陈旧任务继续占用主线程做无意义的刷新。
    function cancelScheduleQueueFlush(id) {
        id = Number(id);
        const rowHandle = scheduleQueueRowFlushHandles.get(id);
        if (rowHandle != null) { clearTimeout(rowHandle); scheduleQueueRowFlushHandles.delete(id); }
        const metaHandle = scheduleQueueMetaFlushHandles.get(id);
        if (metaHandle != null) { clearTimeout(metaHandle); scheduleQueueMetaFlushHandles.delete(id); }
        scheduleQueueDirtyRows.delete(id);
    }

    // ── 计划任务队列的 SSE 实时进度同步（复用工作区的聚合 EventSource） ──────────────────────────
    // 管理员的聚合 SSE 会收到全部下载进度事件（含计划任务后台下载，userUuid=null）。运行中展开队列时，
    // 为每个插画项按 artworkId 注册监听，把逐图进度并入模型并重渲染；折叠 / 运行结束即解绑。
    function subscribeScheduleQueueSse(id) {
        id = Number(id);
        const model = scheduleQueueModels[id];
        if (!model) return;
        ensureSharedSSE();
        if (!scheduleSseHandlers[id]) scheduleSseHandlers[id] = {};
        const handlers = scheduleSseHandlers[id];
        model.forEach(q => {
            const queueTypes = window.PixivBatch && window.PixivBatch.queueTypes;
            if (queueTypes && typeof queueTypes.supportsScheduledSse === 'function'
                && !queueTypes.supportsScheduledSse(q.kind)) return;
            const key = String(q.id);
            if (handlers[key]) return; // 已注册
            const fn = data => applyScheduleQueueSse(id, key, data);
            handlers[key] = fn;
            addSSEListener(key, fn);
        });
    }

    function unsubscribeScheduleQueueSse(id) {
        id = Number(id);
        // 不再有逐图事件进来：取消待执行的局部 / 低频刷新合批，避免遗留定时器空转。
        cancelScheduleQueueFlush(id);
        const handlers = scheduleSseHandlers[id];
        if (!handlers) return;
        Object.keys(handlers).forEach(key => removeSSEListener(key, handlers[key]));
        delete scheduleSseHandlers[id];
    }

    function unsubscribeAllScheduleQueueSse() {
        Object.keys(scheduleSseHandlers).forEach(id => unsubscribeScheduleQueueSse(id));
    }

    // 计划任务列表刷新后，对已不在 liveIds 中的任务连带清理：解绑 SSE / 删除内存模型 / 撤掉展开态 /
    // 移除本地缓存。覆盖 scheduleExpandedQueues、scheduleQueueModels、scheduleSseHandlers、
    // scheduleQueueWasRunning 四张表，避免任一处残留导致旧 handler 继续消费事件或阻止
    // stopSchedulePolling 关闭共享 SSE 连接。
    function releaseStaleScheduleQueueIds(liveIds) {
        const stale = new Set();
        for (const id of scheduleExpandedQueues) if (!liveIds.has(id)) stale.add(id);
        Object.keys(scheduleQueueModels).forEach(k => {
            const id = Number(k);
            if (!liveIds.has(id)) stale.add(id);
        });
        Object.keys(scheduleSseHandlers).forEach(k => {
            const id = Number(k);
            if (!liveIds.has(id)) stale.add(id);
        });
        for (const id of scheduleQueueWasRunning) if (!liveIds.has(id)) stale.add(id);
        const qvRelease = scheduleQueueVue();
        stale.forEach(id => {
            unsubscribeScheduleQueueSse(id);
            if (qvRelease) qvRelease.unmountScheduleQueue(id); // 任务下线：卸载其 reactive 岛
            scheduleExpandedQueues.delete(id);
            delete scheduleQueueModels[id];
            scheduleQueueWasRunning.delete(id);
            try { storeRemove(scheduleQueueCacheKey(id)); } catch (e) { /* 存储不可用：忽略 */ }
        });
    }

    function applyScheduleQueueSse(id, qId, data) {
        id = Number(id);
        const model = scheduleQueueModels[id];
        if (!model || !data) return;
        const q = model.find(x => x.id === qId);
        if (!q || data.cancelled) return;
        // SSE 同步对齐 rawStatus，让 localizeScheduleQueueItem 在渲染时派生出正确语言的 lastMessage；
        // downloading 不对应后端 raw 状态，置为 'downloading' 与 q.status 同步，localizer 走默认分支
        // 让共享渲染器用 queueStatusText(status) 兜底显示「下载中」。
        if (data.completed) {
            q.status = 'completed';
            q.rawStatus = 'downloaded';
        } else if (data.failed) {
            q.status = 'failed';
            q.rawStatus = 'failed';
        } else if (data.downloadedCount !== undefined || data.totalImages !== undefined) {
            q.status = 'downloading';
            q.rawStatus = 'downloading';
            if (data.totalImages !== undefined) q.totalImages = data.totalImages;
            if (data.downloadedCount !== undefined) q.downloadedCount = data.downloadedCount;
            q.imageProgress = data.imageProgress || q.imageProgress || null;
            q.ugoiraProgress = mergeUgoiraProgress(q.ugoiraProgress, data.ugoiraProgress);
        }
        // 只 patch 模型 + 标记脏行：不在每个事件里整块重建 DOM。合批后只替换变化的单行，
        // 统计 / 当前下载项由更低频的 meta 刷新处理，使高频进度事件不再阻塞主线程。
        markScheduleQueueRowDirty(id, qId);
    }

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

    // 过度访问账号级暂停横幅：把 OVERUSE_PAUSED 的任务按 accountId 分组，每组给两个账号级恢复按钮。
    function renderOveruseBanners(tasks) {
        const groups = new Map();
        tasks.forEach(t => {
            if (t.lastStatus !== 'OVERUSE_PAUSED' || !t.accountId) return;
            const list = groups.get(t.accountId) || [];
            list.push(t);
            groups.set(t.accountId, list);
        });
        if (groups.size === 0) return '';
        const defaultMinutes = 60;
        let html = '';
        groups.forEach((list, accountId) => {
            const acc = encodeURIComponent(accountId);
            html += `
            <div class="schedule-overuse-banner">
                <div class="schedule-overuse-title">${escHtml(bt('schedule.overuse.banner.title', '⚠️ 过度访问暂停'))}</div>
                <div class="schedule-overuse-desc">${escHtml(bt('schedule.overuse.banner.desc',
                    '账号 {account} 有 {count} 个计划任务因检测到 Pixiv 过度访问警告被暂停。',
                    {account: accountId, count: list.length}))}</div>
                <div class="schedule-overuse-actions">
                    <button class="btn btn-red" onclick="resumeScheduleAccountIgnore('${acc}')">${escHtml(bt('schedule.overuse.action.ignore', '无视风险，继续下载！(可能会导致删号)'))}</button>
                    <button class="btn btn-blue" onclick="resumeScheduleAccountDefer('${acc}')">${escHtml(bt('schedule.overuse.action.defer', '我已知晓，在 {minutes} 分钟后继续所有同账号任务', {minutes: defaultMinutes}))}</button>
                </div>
            </div>`;
        });
        return html;
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

    async function resumeScheduleAccount(accountId, mode, minutes) {
        const body = {mode};
        if (mode === 'defer') body.minutes = minutes;
        try {
            const res = await fetch(`${BASE}/api/schedule/account/${accountId}/resume`, {
                method: 'POST',
                credentials: 'same-origin',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify(body)
            });
            if (res.ok) {
                setScheduleListStatus(bt('schedule.status.account-resumed', '已恢复该账号的所有任务'), 'success');
                loadScheduleTasks();
            } else {
                const err = await res.json().catch(() => ({}));
                setScheduleListStatus(err.error || err.message || bt('schedule.error.resume', '恢复失败'), 'error');
            }
        } catch (e) {
            setScheduleListStatus(bt('schedule.error.resume', '恢复失败'), 'error');
        }
    }

    async function resumeScheduleAccountIgnore(accountId) {
        if (!await uiConfirmKey('schedule.overuse.confirm.ignore',
            '确定无视过度访问警告并立即继续下载吗？短时间内继续大量下载可能导致账号被封禁。')) return;
        resumeScheduleAccount(accountId, 'ignore');
    }

    async function resumeScheduleAccountDefer(accountId) {
        const input = await uiPromptKey(
            'schedule.overuse.prompt.minutes',
            '延迟多少分钟后继续？（最低 60）',
            '60',
            {inputType: 'number', min: 60, step: 1}
        );
        if (input == null) return;
        let minutes = parseInt(input, 10);
        if (!Number.isFinite(minutes) || minutes < 60) minutes = 60;
        resumeScheduleAccount(accountId, 'defer', minutes);
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
                const reasonText = pendingReasonText(p);
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

    function pendingReasonText(item) {
        if (!item) return '';
        if (item.reasonDetailJson) {
            try {
                const detail = JSON.parse(item.reasonDetailJson);
                if (typeof detail === 'string') return detail;
                if (detail && typeof detail.message === 'string') return detail.message;
                if (detail && typeof detail.reason === 'string') return detail.reason;
                if (detail && typeof detail.legacyReason === 'string') return detail.legacyReason;
            } catch (e) {
                return item.reasonDetailJson;
            }
        }
        return item.reasonCode || '';
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
