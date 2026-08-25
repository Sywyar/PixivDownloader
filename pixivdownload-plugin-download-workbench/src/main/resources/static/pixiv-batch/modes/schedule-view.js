'use strict';
    function scheduleStatusLabel(task) {
        const credentialPresentation = scheduleTaskCredentialPresentation(task);
        if (credentialPresentation && credentialPresentation.statusLabel) {
            return credentialPresentation.statusLabel;
        }
        const code = task && task.lastStatus;
        if (!code) return bt('schedule.run-status.none', '尚未运行');
        if (code === 'OK') return bt('schedule.run-status.ok', '正常');
        if (code === 'ERROR') return bt('schedule.run-status.error', '运行出错');
        if (code === 'PAUSED') return bt('schedule.run-status.paused', '已手动暂停');
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
        const code = safeScheduleMachineCode(value);
        if (!code) return null;
        if (code.startsWith('schedule.')) {
            const translated = bt(code, '');
            return translated && translated !== code ? translated : null;
        }
        try {
            const runtime = scheduleSourceRuntime();
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

    // 单项作品失败属于 workType owner，不属于取得来源。只信任活动下载类型 manifest 声明的
    // i18nNamespace；插件缺席、namespace 不匹配或旧缓存只有 sourceType 时一律退回宿主通用文案。
    function localizeScheduleWorkMachineCode(value, workType) {
        const code = safeScheduleMachineCode(value);
        if (!code) return null;
        if (code.startsWith('schedule.')) {
            const translated = bt(code, '');
            return translated && translated !== code ? translated : null;
        }
        try {
            const registry = window.PixivBatch && window.PixivBatch.queueTypes;
            const descriptor = registry && typeof registry.manifestDescriptor === 'function'
                ? registry.manifestDescriptor(workType) : null;
            const namespace = scheduleI18nToken(descriptor && descriptor.i18nNamespace, 64);
            if (!namespace || !code.startsWith(`${namespace}.`)
                    || typeof pageI18n === 'undefined' || !pageI18n) return null;
            const translated = pageI18n.t(
                `${namespace}:${code.slice(namespace.length + 1)}`, '');
            return translated && translated !== code ? translated : null;
        } catch (e) {
            return null;
        }
    }

    function scheduleFailureReason(t) {
        return t ? localizeScheduleMachineCode(t.lastMessage, t.sourceType || t.type) : null;
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
            const reason = localizeScheduleMachineCode(
                t.suspendCode, t.sourceType || t.type);
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
            const reason = localizeScheduleMachineCode(
                t.suspendCode, t.sourceType || t.type);
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
            [bt('schedule.snapshot.field.cookie', '来源凭证'), scheduleTaskCredentialPolicy(t).bound
                ? bt('schedule.credential.bound', '已绑定凭证')
                : bt('schedule.credential.unbound', '未绑定凭证')],
            [bt('schedule.snapshot.field.proxy', '单独代理'), t.proxy ? t.proxy : bt('schedule.snapshot.value.global-proxy', '使用全局代理设置')],
            [bt('schedule.snapshot.field.enabled', '启用状态'), t.enabled ? bt('schedule.state.enabled', '已启用') : bt('schedule.state.disabled', '已停用')],
            [bt('schedule.snapshot.field.next-run', '下次运行'), fmtScheduleTime(t.nextRunTime)],
            [bt('schedule.snapshot.field.last-run', '上次运行'), fmtScheduleTime(t.lastRunTime)],
            [bt('schedule.snapshot.field.last-status', '运行状态'), scheduleStatusLabel(t)]
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
        el.style.color = STATUS_COLORS[type] || 'var(--muted)';
    }

    // 打开弹窗并按任务现状预填：代理可回显；凭证只恢复勾选态，不回显原值。
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
        if (cookieEn) cookieEn.checked = scheduleTaskCredentialPolicy(task).bound;
        const capabilities = updateScheduleCredentialControls(
            'sch-ov', sourceType, {task});
        if (!capabilities.supportsCredential && !capabilities.supportsProxy) return;
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
            id, ov, task, sourceType, sourceLease.signal);
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
        const credentialPolicy = scheduleTaskCredentialPolicy(t);
        const credentialPresentation = scheduleTaskCredentialPresentation(t) || {};
        return JSON.stringify([
            t.name, t.enabled, t.sourceType, credentialPolicy, t.proxy, t.runState,
            t.lastStatus, t.lastMessage, t.runStartedTime, t.nextRunTime, t.lastRunTime,
            t.presentationJson, t.presentation, t.sourceAvailable, t.sourceActivationToken,
            t.pendingRetryArmed, t.suspendReason, t.suspendCode,
            credentialPresentation.statusLabel, credentialPresentation.lightTone,
            credentialPresentation.lightText, credentialPresentation.suspended,
            credentialPresentation.manualRecoveryRequired,
            credentialUi.badgeLabel, credentialUi.overrideLabel, credentialUi.showOverride,
            credentialUi.bound
        ]);
    }

    function scheduleTaskCredentialUi(task) {
        const sourceType = task.sourceType || task.type;
        const runtime = scheduleSourceRuntime();
        const sourceActive = !!(runtime && typeof runtime.isAvailable === 'function'
            && runtime.isAvailable(sourceType));
        const capabilities = sourceActive
            ? scheduleCredentialCapabilities(sourceType, {task})
            : {supportsCredential: false, supportsProxy: false, presentation: {}};
        const p = capabilities.presentation || {};
        const bound = scheduleTaskCredentialPolicy(task).bound;
        let badgeLabel = null;
        if (capabilities.supportsCredential) {
            badgeLabel = bound
                ? (p.boundLabel || bt('schedule.credential.bound', '已绑定凭证'))
                : (p.unboundLabel || bt('schedule.credential.unbound', '未绑定凭证'));
        } else if (!sourceActive && bound) {
            badgeLabel = bt('schedule.credential.bound', '已绑定凭证');
        }
        return {
            badgeLabel,
            bound,
            showOverride: sourceActive && (capabilities.supportsCredential || capabilities.supportsProxy),
            overrideLabel: scheduleOverrideActionLabel(capabilities),
            proxyLabel: p.proxyBadgeLabel || bt('schedule.badge.custom-proxy', '单独代理')
        };
    }

    // 凭证策略横幅是插件贡献的纯展示数据；签名包含完整 owner/policy/publication/account/
    // suspend identity，避免不同 publication 或不同挂起事件被宿主错误合并。
    function scheduleBannerRenderSignature(groups) {
        return JSON.stringify((Array.isArray(groups) ? groups : []).map(group => [
            group.identityKey,
            group.sourceType,
            group.title,
            group.description,
            group.actions
        ]));
    }

    function scheduleCredentialPolicyGroups(tasks) {
        const runtime = scheduleSourceRuntime();
        if (!runtime || typeof runtime.credentialPolicyGroups !== 'function') return [];
        try {
            const groups = runtime.credentialPolicyGroups(tasks, {mode: state.mode});
            return Array.isArray(groups) ? groups : [];
        } catch (e) {
            return [];
        }
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
            scheduleCredentialPolicyGroupsCache = scheduleCredentialPolicyGroups(scheduleTasksCache);

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
        scheduleCredentialPolicyGroupsCache = [];
        scheduleCardSignatures.clear();
    }

    // 横幅分组与文案由 owner 插件贡献；宿主只按规范化 pure data diff 并绑定固定动作入口。
    function renderScheduleBannersDiff(list) {
        const sig = scheduleBannerRenderSignature(scheduleCredentialPolicyGroupsCache);
        if (sig === scheduleBannerSignature) return;
        scheduleBannerSignature = sig;
        const html = renderCredentialPolicyBanners(scheduleCredentialPolicyGroupsCache);
        let wrap = list.querySelector(':scope > .schedule-credential-policy-banners');
        if (html) {
            if (!wrap) {
                wrap = document.createElement('div');
                wrap.className = 'schedule-credential-policy-banners';
                list.insertAdjacentElement('afterbegin', wrap);
            }
            wrap.innerHTML = html;
            bindScheduleCredentialPolicyActions(wrap);
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
        const banners = list.querySelector(':scope > .schedule-credential-policy-banners');
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
        const credentialPresentation = scheduleTaskCredentialPresentation(t) || {};
        const enabledLabel = t.enabled ? bt('schedule.state.enabled', '已启用') : bt('schedule.state.disabled', '已停用');
        const light = scheduleStatusLight(t);

        // 功能区按钮的状态门（与后端 ScheduleService 守卫一致）：
        // busy=运行/排队中；suspended=任意 canonical 挂起原因（兼容旧状态字段只作降级）。
        const busy = ['RUNNING', 'QUEUED', 'CANCEL_REQUESTED'].includes(t.runState);
        const paused = t.suspendReason === 'MANUAL' || t.lastStatus === 'PAUSED';
        const suspended = !!t.suspendReason || paused || credentialPresentation.suspended === true;
        const automaticSuspension = ['SOURCE_UNAVAILABLE', 'EXECUTOR_UNAVAILABLE', 'QUIESCED']
            .includes(t.suspendReason);
        const manualRecoveryRequired = credentialPresentation.manualRecoveryRequired === true
            || (suspended && !automaticSuspension);
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
                    ${credentialUi.badgeLabel ? `<span class="schedule-badge${credentialUi.bound ? ' schedule-badge-ok' : ''}">${escHtml(credentialUi.badgeLabel)}</span>` : ''}
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
                    <button type="button" class="btn btn-blue" data-pixiv-click="showScheduleSnapshot(${t.id})">${escHtml(bt('schedule.snapshot.action.view', '查看任务快照信息'))}</button>
                </div>
            </div>
            <div class="schedule-card-actions">
                <button class="btn btn-cyan" ${runAttr} data-pixiv-click="runScheduleTask(${t.id})">${escHtml(bt('schedule.action.run', '▶ 立即运行'))}</button>
                ${credentialUi.showOverride ? `<button class="btn btn-blue" ${busyAttr} data-pixiv-click="showScheduleOverrideModal(${t.id})">${escHtml(credentialUi.overrideLabel)}</button>` : ''}
                ${paused
                    ? `<button class="btn btn-green" ${resumeAttr} data-pixiv-click="resumeScheduleTask(${t.id})">${escHtml(bt('schedule.action.resume', '▶ 恢复'))}</button>`
                    : `<button class="btn btn-yellow" ${pauseAttr} data-pixiv-click="pauseScheduleTask(${t.id})">${escHtml(bt('schedule.action.pause', '⏸ 暂停'))}</button>`}
                <button class="btn ${t.enabled ? 'btn-red' : 'btn-green'}" ${busyAttr} data-pixiv-click="toggleScheduleTask(${t.id}, ${t.enabled ? 'false' : 'true'})">${escHtml(t.enabled ? bt('schedule.action.disable', '⏸ 停用') : bt('schedule.action.enable', '✔ 启用'))}</button>
                <button class="btn btn-purple" ${editAttr} data-pixiv-click="startEditScheduleTask(${t.id})">${escHtml(bt('schedule.action.edit', '✏ 编辑'))}</button>
                <button class="btn btn-gray" data-pixiv-click="togglePendingPanel(${t.id})">${escHtml(bt('schedule.action.pending', '🧩 待重试'))}</button>
                <button class="btn btn-red" ${busyAttr} data-pixiv-click="deleteScheduleTask(${t.id})">${escHtml(bt('schedule.action.delete', '🗑 删除'))}</button>
            </div>
            <div class="schedule-pending-panel" id="schedule-pending-${t.id}" hidden></div>
            ${renderScheduleQueueSection(t)}
        </div>`;
    }

    // 卡片底部「本轮队列详情」可折叠区域：默认折叠；展开态在列表重渲染后从 scheduleExpandedQueues 恢复，
    // 并直接用本地缓存预填充内容（避免闪烁），随后 refreshExpandedScheduleQueues / 展开动作再拉取最新数据。
