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
    let scheduleCredentialPolicyGroupsCache = [];
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

    function scheduleAcquisitionInput(mode) {
        if (mode !== 'single-import') return null;
        const field = document.getElementById('single-import-textarea');
        return field ? String(field.value || '') : null;
    }

    function restoreScheduleAcquisition(mode, value) {
        if (mode !== 'single-import') return false;
        switchMode('single-import');
        const field = document.getElementById('single-import-textarea');
        if (!field) return false;
        field.value = String(value == null ? '' : value);
        return true;
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
        return Object.freeze({
            mode: state.mode,
            quickSource: quickSource || null,
            editingSourceType: scheduleEditingToken ? scheduleEditingToken.sourceType : null,
            workType: firstType,
            workTypes: Object.freeze(workTypes),
            __scheduleAcquisitionHost: Object.freeze({
                input: scheduleAcquisitionInput,
                restore: restoreScheduleAcquisition
            })
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

    function scheduleCredentialCapabilities(sourceType, context) {
        const runtime = scheduleSourceRuntime();
        const unavailable = {supportsCredential: false, supportsProxy: false, presentation: {}};
        if (!runtime || !sourceType) return unavailable;
        try {
            const contribution = runtime.credentialContribution(sourceType, context || {});
            if (!contribution) return unavailable;
            return {
                supportsCredential: contribution.supportsCredential === true,
                supportsProxy: contribution.supportsProxy === true,
                presentation: contribution.presentation || {}
            };
        } catch (e) {
            return unavailable;
        }
    }

    function scheduleTaskCredentialPolicy(task) {
        const value = task && task.credentialPolicy && typeof task.credentialPolicy === 'object'
            ? task.credentialPolicy : {};
        const publicationId = Number(value.publicationId);
        return Object.freeze({
            ownerPluginId: String(value.ownerPluginId || ''),
            policyId: String(value.policyId || ''),
            accountKey: String(value.accountKey || ''),
            bound: value.bound === true,
            available: value.available === true,
            publicationId: Number.isSafeInteger(publicationId) && publicationId > 0
                ? publicationId : null,
            statusCode: typeof value.statusCode === 'string' ? value.statusCode : null,
            acknowledgedEventTime: value.acknowledgedEventTime == null
                ? null : value.acknowledgedEventTime
        });
    }

    function scheduleTaskCredentialPresentation(task) {
        const runtime = scheduleSourceRuntime();
        if (!runtime || !task || typeof runtime.credentialTaskPresentation !== 'function') return null;
        try {
            return runtime.credentialTaskPresentation(
                task.sourceType || task.type, task, {task, mode: state.mode});
        } catch (e) {
            return null;
        }
    }

    function scheduleOverrideActionLabel(capabilities) {
        const p = capabilities.presentation || {};
        if (p.overrideLabel) return p.overrideLabel;
        if (capabilities.supportsCredential && capabilities.supportsProxy) {
            return bt('schedule.action.override-both', '🔑 指定单独的代理 / 凭证');
        }
        if (capabilities.supportsCredential) {
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
        [['proxy', capabilities.supportsProxy], ['cookie', capabilities.supportsCredential]]
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
            ? scheduleTaskCredentialPolicy(context.task).bound : false);
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
        el.style.color = STATUS_COLORS[type] || 'var(--muted)';
    }

    // 账号级 / 横幅级操作反馈（如过度访问账号恢复），与「存为计划任务」卡片的表单状态分开
    function setScheduleListStatus(msg, type = 'info') {
        const el = document.getElementById('schedule-list-status');
        if (!el) return;
        el.textContent = msg || '';
        el.style.color = STATUS_COLORS[type] || 'var(--muted)';
    }

    // 单个任务卡片内的操作反馈：显示在该卡片顶部的 tips 区域，互不干扰
    function setScheduleCardTip(id, msg, type = 'info') {
        const el = document.getElementById(`schedule-card-tip-${id}`);
        if (!el) return;
        el.textContent = msg || '';
        el.style.color = STATUS_COLORS[type] || 'var(--muted)';
    }

    // 来源模块读取当前取得模式的受控 UI，并返回正式定义草稿；宿主只盖上当前 activation token。
