package top.sywyar.pixivdownload.schedule;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import top.sywyar.pixivdownload.config.OutboundProxyOverride;
import top.sywyar.pixivdownload.i18n.LocalizedException;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityOwner;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistry;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleSingleCapabilityLease;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledWorkKind;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledWorkRunner;
import top.sywyar.pixivdownload.core.schedule.work.ScheduledWorkTranslateStatus;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;
import top.sywyar.pixivdownload.core.schedule.ScheduledTask;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskInsert;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskStore;
import top.sywyar.pixivdownload.core.schedule.ScheduleTaskDefinitionUpdate;
import top.sywyar.pixivdownload.core.schedule.ScheduledPendingWork;
import top.sywyar.pixivdownload.core.schedule.capability.SchedulePlanningLease;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleRunToken;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleSuspendReason;
import top.sywyar.pixivdownload.download.DownloadWorkbenchPlugin;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialBindResult;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialProbeResult;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionPlan;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardDecision;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceDescriptor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceExecutor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskDefinition;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskDraft;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskPresentation;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkExecutor;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkKey;
import top.sywyar.pixivdownload.schedule.dto.AccountResumeRequest;
import top.sywyar.pixivdownload.schedule.dto.ScheduleQueueView;
import top.sywyar.pixivdownload.schedule.dto.SchedulePendingView;
import top.sywyar.pixivdownload.schedule.dto.ScheduleSourceManifestView;
import top.sywyar.pixivdownload.schedule.dto.ScheduleTaskRequest;
import top.sywyar.pixivdownload.schedule.dto.ScheduleTaskView;
import top.sywyar.pixivdownload.schedule.definition.ScheduleTaskDefinitionValidator;
import top.sywyar.pixivdownload.schedule.execution.ScheduleCredentialBindingLease;
import top.sywyar.pixivdownload.schedule.execution.ScheduleExecutionEngine;
import top.sywyar.pixivdownload.schedule.persistence.PixivSchedulePersistenceCodec;
import top.sywyar.pixivdownload.schedule.security.ScheduleCredentialRedactor;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

/**
 * 计划任务的增删改查、Cookie 授权与「立即运行」入口。
 *
 * <p>运行编排在 {@link ScheduleExecutor} / {@link ScheduleRunner}。
 */
@Slf4j
@PluginManagedBean
@RequiredArgsConstructor
public class ScheduleService {

    private static final Set<String> TRANSLATE_PHASES = Set.of(
            "QUEUED", "WAITING_SERIES", "RESOLVING", "TRANSLATING",
            "MERGING", "SAME_LANGUAGE", "DONE", "FAILED");
    private static final int MAX_PLUGIN_STATUS_ENTRIES = 16;
    private static final int MAX_PLUGIN_STATUS_VALUE_BYTES = 256;

    private final ScheduledTaskStore store;
    private final ScheduleExecutor executor;
    private final ScheduleConfig config;
    private final ScheduleRunState runState;
    private final ScheduleRunQueue runQueue;
    private final ObjectMapper objectMapper;
    private final PixivSchedulePersistenceCodec persistenceCodec;
    private final ScheduleExecutionEngine scheduleExecutionEngine;
    private final TransactionTemplate transactionTemplate;
    /**
     * 作品类型执行器注册中心：队列视图的翻译状态叠加经小说执行器（{@code novel}）取得——它实现可选的
     * {@code translateStatus} 能力。执行器缺席（小说插件被禁 / 卸载）时解析为空、不叠加翻译状态，队列视图照常返回。
     * ScheduleService 因此不再 import 任何 novel 包类型。
     */
    private final ScheduleCapabilityRegistry scheduleCapabilityRegistry;

    public ScheduleSourceManifestView sources() {
        ScheduleCapabilityRegistry.SnapshotView snapshot = scheduleCapabilityRegistry.snapshotView();
        List<ScheduleSourceManifestView.Source> sources = snapshot.owners().stream()
                .flatMap(owner -> owner.sourceDescriptors().stream()
                        .map(descriptor -> sourceView(owner, descriptor)))
                .sorted(java.util.Comparator.comparing(ScheduleSourceManifestView.Source::sourceType))
                .toList();
        return new ScheduleSourceManifestView(snapshot.epoch(), snapshot.revision(), sources);
    }

    public List<ScheduleTaskView> list() {
        Map<SourceActivationKey, String> activations = sourceActivations(
                scheduleCapabilityRegistry.snapshotView());
        return store.findAll().stream()
                .map(t -> taskView(t, runState.get(t.id()), activations))
                .toList();
    }

    public ScheduleTaskView get(long id) {
        ScheduledTask task = store.findById(id);
        if (task == null) {
            throw LocalizedException.badRequest("schedule.error.not-found", "计划任务不存在: {0}", id);
        }
        return taskView(task, runState.get(id), sourceActivations(
                scheduleCapabilityRegistry.snapshotView()));
    }

    public ScheduleTaskView create(ScheduleTaskRequest req) {
        if (req.getExpectedStateVersion() != null) {
            throw LocalizedException.badRequest(
                    "schedule.error.definition-invalid",
                    "创建计划任务时不能携带任务状态版本");
        }
        SchedulePlanningLease planning = preparePlanningLease(req);
        try (planning) {
            requirePlanningActivation(planning, req.getSourceType());
            String triggerKind = validateTrigger(req);
            long now = System.currentTimeMillis();
            ResolvedDefinition resolved = resolveDefinition(0, req, null, planning);
            ScheduledTaskInsert row = new ScheduledTaskInsert();
            row.setName(req.getName().trim());
            row.setEnabled(true);
            row.setSourceType(resolved.definition().sourceType());
            row.setSourceOwnerPluginId(resolved.sourceOwnerPluginId());
            row.setDefinitionSchema(resolved.definition().definitionSchema());
            row.setDefinitionVersion(resolved.definition().definitionVersion());
            row.setDefinitionJson(resolved.definition().definitionJson());
            row.setPresentationJson(writeJson(resolved.definition().presentation()));
            row.setTriggerKind(triggerKind);
            row.setIntervalMinutes(req.getIntervalMinutes());
            row.setCronExpr(emptyToNull(req.getCronExpr()));
            row.setProxySnapshot(null);
            row.setNextRunTime(ScheduleTiming.computeNextRun(
                    triggerKind, req.getIntervalMinutes(), req.getCronExpr(), now));
            row.setCreatedTime(now);

            return executeDefinitionSave(planning, status -> {
                if (store.countAll() >= config.getMaxTasks()) {
                    throw LocalizedException.badRequest(
                            "schedule.error.max-tasks", "计划任务数量已达上限: {0}", config.getMaxTasks());
                }
                store.insert(row);
                return get(row.getId());
            });
        }
    }

    public ScheduleTaskView update(long id, ScheduleTaskRequest req) {
        long expectedStateVersion = requireExpectedStateVersion(req);
        ScheduledTask expected = requireExisting(id);
        requireNotBusy(expected);
        requireExpectedStateVersion(expected, expectedStateVersion);
        SchedulePlanningLease planning = preparePlanningLease(req);
        try (planning) {
            requirePlanningActivation(planning, req.getSourceType());
            String triggerKind = validateTrigger(req);
            ResolvedDefinition resolved = resolveDefinition(
                    id, req, expected.sourceOwnerPluginId(), planning);
            Long nextRun = ScheduleTiming.computeNextRun(
                    triggerKind, req.getIntervalMinutes(), req.getCronExpr(), System.currentTimeMillis());
            ScheduleTaskDefinitionUpdate update = new ScheduleTaskDefinitionUpdate(
                    req.getName().trim(), resolved.definition().sourceType(), resolved.sourceOwnerPluginId(),
                    resolved.definition().definitionSchema(), resolved.definition().definitionVersion(),
                    resolved.definition().definitionJson(), writeJson(resolved.definition().presentation()),
                    triggerKind, req.getIntervalMinutes(), emptyToNull(req.getCronExpr()), nextRun);

            return executeDefinitionSave(planning, status -> {
                ScheduledTask task = requireExisting(id);
                requireNotBusy(task);
                requireExpectedStateVersion(task, expectedStateVersion);
                requireCompatibleCredentialBinding(task, resolved);
                if (store.updateDefinition(id, task.stateVersion(), update).isEmpty()) {
                    throw definitionConcurrentChange();
                }
                return get(id);
            });
        }
    }

    @Transactional
    public ScheduleTaskView setEnabled(long id, boolean enabled) {
        ScheduledTask task = requireExisting(id);
        requireNotBusy(task);
        requireChanged(store.updateEnabled(id, task.stateVersion(), enabled));
        return get(id);
    }

    @Transactional
    public void delete(long id) {
        ScheduledTask task = requireExisting(id);
        requireNotBusy(task);
        if (!store.deleteAggregate(id, task.stateVersion())) {
            throw concurrentChange();
        }
        // 连带清除内存中的本轮运行队列，避免删除后残留
        runQueue.remove(id);
    }

    /**
     * 取该任务最近一轮运行队列（管理员专用，供前端「本轮队列详情」展示）。
     * 从未运行或进程重启后返回空队列（{@code startedTime=null}、{@code items} 为空）。
     */
    public ScheduleQueueView queue(long id) {
        requireExisting(id);
        ScheduleRunQueue.Run run = runQueue.get(id);
        if (run == null) {
            return new ScheduleQueueView(id, null, false, 0, List.of());
        }
        List<ScheduleQueueView.Item> items = run.snapshot().stream()
                .map(this::toQueueItem)
                .toList();
        return new ScheduleQueueView(id, run.startedTime(), run.truncated(), items.size(), items);
    }

    /**
     * 把内存队列条目映射为对外视图，并为小说叠加「下载即自动翻译」的实时状态（读取时取，非阻塞）。
     *
     * <p>仅对<b>本轮确实提交过自动翻译</b>的条目（{@link ScheduleRunQueue.Item#isAutoTranslateSubmitted()}）叠加：
     * 翻译状态按 {@code novelId} 全局保存且终态不随轮次清理，若对所有小说一律叠加，会把同一 {@code novelId} 上一轮
     * （甚至别的任务）译过的旧 DONE/FAILED 误显示到本轮「已存在跳过」「未开启翻译」的条目上。
     */
    private ScheduleQueueView.Item toQueueItem(ScheduleRunQueue.Item it) {
        String translatePhase = null;
        Long translateElapsed = null;
        Integer translatePending = null;
        if (ScheduleRunQueue.KIND_NOVEL.equals(it.getKind()) && it.isAutoTranslateSubmitted()) {
            try {
                long novelId = Long.parseLong(it.getId());
                ScheduledWorkTranslateStatus tv = null;
                var executorHandle = scheduleCapabilityRegistry.resolveWorkExecutor(ScheduledWorkKind.NOVEL)
                        .orElse(null);
                if (executorHandle != null) {
                    ScheduleSingleCapabilityLease<ScheduledWorkExecutor> lease =
                            scheduleCapabilityRegistry.prepareAcquire(executorHandle).orElse(null);
                    try (lease) {
                        if (lease != null && scheduleCapabilityRegistry.activate(lease)) {
                            Map<String, String> status = lease.capability().status(
                                    new ScheduledWorkKey(ScheduledWorkKind.NOVEL, it.getId()));
                            tv = safeTranslateStatus(status);
                        }
                    }
                } else {
                    // 仅保留给尚未迁移的内置测试适配器；第三方旧插件不会进入新 Schedule 执行流。
                    var legacyHandle = scheduleCapabilityRegistry.resolveLegacyWorkRunner(ScheduledWorkKind.NOVEL)
                            .orElse(null);
                    if (legacyHandle != null) {
                        ScheduleSingleCapabilityLease<ScheduledWorkRunner> lease =
                                scheduleCapabilityRegistry.prepareAcquire(legacyHandle).orElse(null);
                        try (lease) {
                            if (lease != null && scheduleCapabilityRegistry.activate(lease)) {
                                tv = lease.capability().translateStatus(novelId);
                            }
                        }
                    }
                }
                if (tv != null) {
                    translatePhase = tv.phase();
                    translateElapsed = tv.elapsedSeconds();
                    translatePending = tv.seriesPending();
                }
            } catch (NumberFormatException ignored) {
                // 非数字 ID（不应出现于小说）：跳过叠加
            } catch (RuntimeException ignored) {
                // 插件执行器异常不得穿过边界破坏整份队列视图，也不保留 child classloader 的异常对象。
                log.debug("Scheduled work translation status is temporarily unavailable");
            }
        }
        return new ScheduleQueueView.Item(
                it.getId(), it.getTitle(), it.getKind(),
                it.getXRestrict(), it.getAi(), it.getStatus(), it.getMessage(),
                translatePhase, translateElapsed, translatePending);
    }

    /**
     * 为任务绑定执行计划声明的凭证。格式校验、账号键、主动探活与绑定后策略决定均经
     * {@code ScheduledCredentialPolicy}；secret 绝不写日志 / 回显。
     *
     * <p>这是凭证失效挂起的恢复入口——仅恢复当前任务的凭证挂起；其它挂起原因不会被静默恢复。
     *
     * <p><b>重新授权必须使用新的凭证</b>：提交值与当前绑定快照完全一致时，在探活和写库前拒绝，
     * 避免用同一份失效凭证清除挂起后再次空跑。
     */
    public ScheduleTaskView authorizeCookie(
            long id,
            String cookie,
            String expectedActivationToken) {
        ScheduledTask task = requireExisting(id);
        requireNotBusy(task);
        if (cookie == null || cookie.isBlank()) {
            throw LocalizedException.badRequest(
                    "schedule.error.cookie-invalid", "Cookie 无效或为空");
        }
        String trimmed = cookie.trim();
        try (ScheduleCredentialBindingLease binding =
                     scheduleExecutionEngine.prepareCredentialBinding(
                             task, expectedActivationToken)) {
            String policyOwnerPluginId = binding.policyOwnerPluginId();
            String policyId = binding.policyId();
            String existing = store.findCredentialSecret(id, policyOwnerPluginId, policyId);
            if (existing != null && existing.equals(trimmed)) {
                throw LocalizedException.badRequest(
                        "schedule.error.cookie-unchanged",
                        "Cookie 与当前已绑定的相同，未做更新；若任务因 Cookie 失效被挂起，请改用新的有效 Cookie");
            }
            ScheduledCredentialBindResult bindResult = binding.probe(trimmed);
            ScheduledCredentialProbeResult probe = bindResult.probeResult();
            if (probe.status() == ScheduledCredentialProbeResult.Status.INVALID) {
                throw LocalizedException.badRequest(
                        "schedule.error.cookie-invalid",
                        "Cookie 无效或已失效，请重新登录 Pixiv 后复制新 Cookie");
            }
            if (probe.status() == ScheduledCredentialProbeResult.Status.RETRY_LATER) {
                throw LocalizedException.badRequest(
                        "schedule.error.cookie-probe-failed",
                        "暂时无法验证 Cookie，请检查网络后重试");
            }
            transactionTemplate.executeWithoutResult(status -> {
                ScheduledTask current = requireExisting(id);
                if (current.stateVersion() != task.stateVersion()) {
                    throw concurrentChange();
                }
                requireNotBusy(current);
                requireBindingActive(binding);
                String currentSecret = store.findCredentialSecret(
                        id, policyOwnerPluginId, policyId);
                if (currentSecret != null && currentSecret.equals(trimmed)) {
                    throw LocalizedException.badRequest(
                            "schedule.error.cookie-unchanged",
                            "Cookie 与当前已绑定的相同，未做更新；若任务因 Cookie 失效被挂起，请改用新的有效 Cookie");
                }
                boolean samePolicy = policyOwnerPluginId.equals(
                        current.credentialPolicyOwnerPluginId())
                        && policyId.equals(current.credentialPolicyId());
                String policyState = samePolicy && current.credentialPolicyStateJson() != null
                        ? current.credentialPolicyStateJson()
                        : bindResult.initialPolicyStateJson();
                OptionalLong bound = store.bindCredential(
                        id, current.stateVersion(), policyOwnerPluginId, policyId,
                        probe.accountKey(), policyState, trimmed,
                        "scheduled-task:" + id + ":credential", System.currentTimeMillis());
                requireChanged(bound);
                long version = bound.getAsLong();
                if (current.suspendReason() == ScheduleSuspendReason.CREDENTIAL) {
                    OptionalLong resumed = store.resume(
                            id, version, ScheduleSuspendReason.CREDENTIAL,
                            current.suspendCode(), nextRunFor(current));
                    requireChanged(resumed);
                    version = resumed.getAsLong();
                }
                ScheduledGuardDecision postBind = bindResult.postBindResult().decision();
                if (postBind.action() == ScheduledGuardDecision.Action.SUSPEND_POLICY_TASK
                        && (current.suspendReason() == null
                        || current.suspendReason() == ScheduleSuspendReason.CREDENTIAL)) {
                    requireChanged(store.suspend(
                            id, version, ScheduleSuspendReason.POLICY,
                            postBind.reasonCode(),
                            writeJson(bindResult.postBindResult().evidence().attributes())));
                }
                requireBindingActive(binding);
            });
        } catch (ScheduleSourcePublicationChangedException failure) {
            throw definitionConcurrentChange();
        } catch (ScheduleSourceUnavailableException
                 | ScheduleExecutorUnavailableException
                 | ScheduleDefinitionException failure) {
            throw LocalizedException.badRequest(
                    "schedule.error.source-unavailable",
                    "计划任务来源或执行能力当前不可用");
        } catch (ScheduledExecutionException failure) {
            throw LocalizedException.badRequest(
                    "schedule.error.cookie-probe-failed",
                    "暂时无法验证 Cookie，请检查网络后重试");
        }
        return get(id);
    }

    /** 解除当前任务持久化记录的凭证策略绑定；旧 URL 保留为兼容入口。 */
    @Transactional
    public ScheduleTaskView revokeCookie(long id) {
        ScheduledTask task = requireExisting(id);
        requireNotBusy(task);
        if (task.credentialPolicyOwnerPluginId() == null) {
            return get(id);
        }
        if (task.credentialPolicyId() == null) {
            throw LocalizedException.badRequest(
                    "schedule.error.source-unavailable", "计划任务凭证策略当前不可用");
        }
        requireChanged(store.removeCredential(
                id, task.stateVersion(), task.credentialPolicyOwnerPluginId(),
                task.credentialPolicyId()));
        return get(id);
    }

    /**
     * 设置 / 清除「任务级单独代理」（{@code host:port}，非凭证）。设置后该任务每轮运行中对 Pixiv 的全部
     * 出站请求（发现 / 元数据 / 下载 / 站内信检测）都改走它；{@code null} / 空白 = 清除并回退全局代理设置。
     */
    @Transactional
    public ScheduleTaskView updateProxy(long id, String proxy) {
        ScheduledTask task = requireExisting(id);
        requireNotBusy(task);
        String normalized = emptyToNull(proxy);
        if (normalized != null && OutboundProxyOverride.parse(normalized) == null) {
            throw LocalizedException.badRequest(
                    "schedule.error.proxy-invalid", "代理格式无效，应为 host:port（例如 127.0.0.1:7890）");
        }
        requireChanged(store.updateProxy(id, task.stateVersion(), normalized));
        return get(id);
    }

    /**
     * 「立即运行」端点入口：在 {@link #runOnce} 之上加状态门——任务必须 {@code enabled}、未在运行 / 排队、
     * 且未处于暂停 / 挂起态才允许手动触发。前端会据此禁用按钮，这里是后端防护（防陈旧 UI / 直连 API）。
     */
    public void manualRun(long id) {
        ScheduledTask task = store.findById(id);
        if (task == null) {
            throw LocalizedException.badRequest("schedule.error.not-found", "计划任务不存在: {0}", id);
        }
        requireNotBusy(task);
        if (!task.enabled()) {
            throw LocalizedException.badRequest("schedule.error.run-disabled", "任务已停用，请先启用再运行");
        }
        if (isSuspended(task)) {
            throw LocalizedException.badRequest(
                    "schedule.error.run-suspended", "任务处于暂停 / 挂起状态，请先恢复或重新授权再运行");
        }
        runOnce(id);
    }

    /**
     * 立即运行一次（后台异步执行，不阻塞调用方）。<b>不含状态门</b>：供 {@link #manualRun} 与
     * {@link ScheduleController#resume} 复用——前者已在外层校验状态，后者在 resume 事务提交、确认 enabled 后调用。
     * 已在运行 / 排队（有 Claim）时静默跳过，靠 next_run 兜底由 tick 接管。
     */
    public void runOnce(long id) {
        ScheduleSingleCapabilityLease<ScheduleCapabilityOwner> hostLease = prepareHostLease();
        if (hostLease == null) {
            log.debug("Scheduled task {} manual run ignored: schedule host is quiesced", id);
            return;
        }
        boolean delegated = false;
        ScheduleRunState.Claim claim = null;
        ScheduleRunToken runToken = null;
        String claimToken = null;
        Long nextRun = null;
        Throwable failure = null;
        try {
            if (!scheduleCapabilityRegistry.activate(hostLease)) {
                log.debug("Scheduled task {} manual run ignored: schedule host is quiesced", id);
                return;
            }
            ScheduledTask task = requireExisting(id);
            nextRun = task.nextRunTime();
            claim = runState.tryMarkQueued(id);
            if (claim == null) {
                log.debug("Scheduled task {} manual run ignored: already queued or running", id);
                return;
            }
            claimToken = UUID.randomUUID().toString();
            runToken = store.tryQueueNow(
                            id, task.stateVersion(), claimToken)
                    .orElse(null);
            if (runToken == null) {
                runState.clear(claim);
                claim = null;
                log.debug("Scheduled task {} manual run ignored: durable claim rejected", id);
                return;
            }
            executor.runTaskAsync(id, claim, runToken, hostLease);
            delegated = true;
        } catch (Throwable e) {
            failure = e;
            try {
                if (runToken != null) {
                    executor.releaseQueued(id, runToken);
                } else if (claimToken != null) {
                    executor.releaseClaim(id, claimToken, nextRun);
                }
            } catch (Throwable cleanupFailure) {
                ScheduleExecutor.addCleanupFailure(e, cleanupFailure);
            }
            try {
                runState.clear(claim);
            } catch (Throwable cleanupFailure) {
                ScheduleExecutor.addCleanupFailure(e, cleanupFailure);
            }
            throw ScheduleExecutor.propagate(e);
        } finally {
            if (!delegated) {
                try {
                    hostLease.close();
                } catch (Throwable cleanupFailure) {
                    if (failure != null) {
                        ScheduleExecutor.addCleanupFailure(failure, cleanupFailure);
                    } else {
                        throw ScheduleExecutor.propagate(cleanupFailure);
                    }
                }
            }
        }
    }

    private static Long parseLong(String value) {
        try {
            return value == null ? null : Long.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static ScheduledWorkTranslateStatus safeTranslateStatus(Map<String, String> status) {
        if (status == null || status.isEmpty() || status.size() > MAX_PLUGIN_STATUS_ENTRIES) {
            return null;
        }
        String phase = safeStatusValue(status.get("phase"));
        if (phase == null || !TRANSLATE_PHASES.contains(phase)) {
            return null;
        }
        Long elapsed = parseLong(safeStatusValue(status.get("elapsedSeconds")));
        Integer pending = parseInteger(safeStatusValue(status.get("seriesPending")));
        if ((elapsed != null && elapsed < 0L) || (pending != null && pending < 0)) {
            return null;
        }
        return new ScheduledWorkTranslateStatus(phase, elapsed, pending);
    }

    private static String safeStatusValue(String value) {
        if (value == null
                || value.getBytes(StandardCharsets.UTF_8).length > MAX_PLUGIN_STATUS_VALUE_BYTES
                || ScheduleCredentialRedactor.containsCredentialMaterial(value)) {
            return null;
        }
        return value;
    }

    private static Integer parseInteger(String value) {
        try {
            return value == null ? null : Integer.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private ScheduleSingleCapabilityLease<ScheduleCapabilityOwner> prepareHostLease() {
        var handle = scheduleCapabilityRegistry.resolveOwner(DownloadWorkbenchPlugin.ID).orElse(null);
        if (handle == null) {
            return null;
        }
        return scheduleCapabilityRegistry.prepareAcquire(handle).orElse(null);
    }

    // ── 暂停 / 恢复 ───────────────────────────────────────────────────────────────

    /**
     * 手动挂起（{@code MANUAL}）：不冻账号、不发邮件；findDue 状态门挡住，不再到期触发。
     *
     * <p><b>仅在任务正在运行 / 排队（busy）时可暂停</b>：暂停语义是「打断当前这一轮」，空闲任务没有可打断的运行，
     * 应改用「停用」阻止自动调度。空闲时直接拒绝（前端也会禁用按钮，这里是后端防护）。
     *
     * <p>通过 {@link ScheduleRunState#requestCancel(long)} 给正在运行的本轮派发循环发协作式取消信号：
     * executor 在下一个作品派发前抛 {@link SchedulePauseException} 干净 unwind 本轮，
     * 这样「按下暂停立刻见效」，已下载的不回滚、未派发的不再继续；持久化状态先原子转为
     * {@code CANCEL_REQUESTED}，再由持有原 claim token 的执行方完成取消收尾。
     */
    @Transactional
    public ScheduleTaskView pause(long id) {
        ScheduledTask task = requireExisting(id);
        if (task.suspendReason() != null) {
            throw LocalizedException.badRequest(
                    "schedule.error.run-suspended", "任务已处于暂停或挂起状态");
        }
        if (!isBusy(task)) {
            throw LocalizedException.badRequest(
                    "schedule.error.pause-idle", "任务当前未在运行，无需暂停；如需阻止自动运行请使用「停用」");
        }
        requireChanged(store.suspend(
                id, task.stateVersion(), ScheduleSuspendReason.MANUAL, "ADMIN_PAUSE", null));
        runState.requestCancel(id);
        return get(id);
    }

    /**
     * 恢复手动暂停 / 单任务挂起：清挂起，并把 {@code next_run_time} 置为<b>当前时刻</b>，使其立刻到期。
     *
     * <p>恢复语义是「立即继续这个任务」：调用方（{@link ScheduleController}）在本方法事务提交后会再触发一次
     * 后台运行（{@code runOnce}）真正立刻跑起来；这里把 {@code next_run_time=now} 作为兜底——即便那次即时触发
     * 因竞态被跳过，下一拍调度 tick 也会因「已到期」立即把它捡起来跑，绝不让恢复后白等一个完整周期。
     */
    @Transactional
    public ScheduleTaskView resume(long id) {
        ScheduledTask task = store.findById(id);
        if (task == null) {
            throw LocalizedException.badRequest("schedule.error.not-found", "计划任务不存在: {0}", id);
        }
        if (task.suspendReason() != ScheduleSuspendReason.MANUAL) {
            throw LocalizedException.badRequest(
                    "schedule.error.resume-not-paused", "任务未处于手动暂停状态，无法恢复");
        }
        requireNotBusy(task);
        requireChanged(store.resume(
                id, task.stateVersion(), ScheduleSuspendReason.MANUAL,
                task.suspendCode(), System.currentTimeMillis()));
        return get(id);
    }

    /**
     * 账号级（过度访问）恢复，对同账号所有任务生效（{@link AccountResumeRequest}）。
     */
    @Transactional
    public void resumeAccount(String accountId, AccountResumeRequest req) {
        List<ScheduledTask> tasks = store.findByCredentialAccount(
                DownloadWorkbenchPlugin.ID,
                PixivSchedulePersistenceCodec.CREDENTIAL_POLICY_ID,
                accountId);
        if (tasks.isEmpty()) {
            throw LocalizedException.badRequest(
                    "schedule.error.account-not-found", "账号下无计划任务: {0}", accountId);
        }
        boolean hasOverusePaused = tasks.stream()
                .anyMatch(t -> t.suspendReason() == ScheduleSuspendReason.POLICY
                        && "PIXIV_OVERUSE".equals(t.suspendCode()));
        if (!hasOverusePaused) {
            // 账号级恢复仅针对过度访问暂停。AUTH_EXPIRED / 手动 PAUSED 必须各自走对应恢复入口，
            // 不能借账号级按钮一并清除（会越权放行管理员未确认的状态）。
            throw LocalizedException.badRequest(
                    "schedule.error.account-not-overuse-paused",
                    "账号下没有过度访问暂停的计划任务: {0}", accountId);
        }
        String mode = req.getMode() == null ? "" : req.getMode().trim();
        Long ackTime = latestOveruseWarning(tasks);
        long now = System.currentTimeMillis();
        long acknowledgedAt = ackTime == null ? now : ackTime;
        Long nextRun;
        if (AccountResumeRequest.MODE_IGNORE.equals(mode)) {
            nextRun = now;
        } else if (AccountResumeRequest.MODE_DEFER.equals(mode)) {
            int minutes = req.getMinutes() == null
                    ? config.getOveruseDeferDefaultMinutes() : req.getMinutes();
            if (minutes < 60) {
                throw LocalizedException.badRequest(
                        "schedule.error.defer-minutes-min", "延迟分钟数最低为 60");
            }
            nextRun = now + minutes * 60_000L;
        } else {
            throw LocalizedException.badRequest("schedule.error.resume-mode-invalid", "恢复方式无效");
        }
        for (ScheduledTask task : tasks) {
            if (task.runState() != null) {
                throw LocalizedException.badRequest(
                        "schedule.error.busy", "账号下仍有任务正在取消收尾，请稍后重试");
            }
            String oldState = task.credentialPolicyStateJson() == null
                    ? persistenceCodec.encodePolicyState(null)
                    : task.credentialPolicyStateJson();
            requireChanged(store.updateCredentialPolicyState(
                    task.id(), task.stateVersion(), DownloadWorkbenchPlugin.ID,
                    PixivSchedulePersistenceCodec.CREDENTIAL_POLICY_ID,
                    oldState, persistenceCodec.withAcknowledgedWarningTime(oldState, acknowledgedAt), now));
        }
        int expectedResumed = (int) tasks.stream()
                .filter(t -> t.suspendReason() == ScheduleSuspendReason.POLICY
                        && "PIXIV_OVERUSE".equals(t.suspendCode()))
                .count();
        int resumed = store.resumeByCredentialAccount(
                DownloadWorkbenchPlugin.ID,
                PixivSchedulePersistenceCodec.CREDENTIAL_POLICY_ID,
                accountId, ScheduleSuspendReason.POLICY, "PIXIV_OVERUSE", nextRun);
        if (resumed != expectedResumed) {
            throw concurrentChange();
        }
    }

    /** 隔离表（待重试）行视图，供前端「待重试 / 需人工」面板展示。 */
    public List<SchedulePendingView> pending(long id) {
        requireExisting(id);
        int max = config.getPendingMaxAttempts();
        return store.listPendingWork(id).stream()
                .map(p -> SchedulePendingView.of(p, max))
                .toList();
    }

    /** 手动清除隔离表中某个「需人工」条目（运行 / 排队中拒绝，避免与本轮的隔离表读写竞态）。 */
    @Transactional
    public void clearPending(long id, String workType, String workId) {
        ScheduledTask task = requireExisting(id);
        requireNotBusy(task);
        requireChanged(store.clearPendingWork(
                id, task.stateVersion(), workType, workId));
    }

    /** 取同账号 OVERUSE_PAUSED 任务里 last_message 记录的最新触发警告 modifiedAt（毫秒）。 */
    private Long latestOveruseWarning(List<ScheduledTask> tasks) {
        Long best = null;
        for (ScheduledTask t : tasks) {
            if (t.suspendReason() != ScheduleSuspendReason.POLICY
                    || !"PIXIV_OVERUSE".equals(t.suspendCode())
                    || t.suspendDetailJson() == null) {
                continue;
            }
            try {
                var node = objectMapper.readTree(t.suspendDetailJson()).path("modifiedAt");
                long v = Long.parseLong(node.asText("0"));
                if (best == null || v > best) best = v;
            } catch (Exception ignored) {
                // 安全展示载荷损坏时不猜测时间；恢复动作会用当前时间作为确认点。
            }
        }
        return best;
    }

    private static Long nextRunFor(ScheduledTask task) {
        return ScheduleTiming.computeNextRun(
                task.triggerKind(), task.intervalMinutes(), task.cronExpr(), System.currentTimeMillis());
    }

    // ── 内部 ────────────────────────────────────────────────────────────────────

    private ScheduledTask requireExisting(long id) {
        ScheduledTask task = store.findById(id);
        if (task == null) {
            throw LocalizedException.badRequest("schedule.error.not-found", "计划任务不存在: {0}", id);
        }
        return task;
    }

    private void requireBindingActive(ScheduleCredentialBindingLease binding) {
        try {
            binding.throwIfCancellationRequested();
        } catch (ScheduledExecutionException failure) {
            throw concurrentChange();
        }
    }

    /** 持久化认领是运行真相；内存镜像只补同步提交到查询之间的极短窗口。 */
    private boolean isBusy(ScheduledTask task) {
        return task.runState() != null || runState.get(task.id()) != null;
    }

    /** 运行 / 排队中拒绝结构性操作（编辑 / 启停 / 删除 / 授权 / 解绑 / 清待重试 / 恢复等）。 */
    private void requireNotBusy(ScheduledTask task) {
        if (isBusy(task)) {
            throw LocalizedException.badRequest(
                    "schedule.error.busy", "任务正在运行或排队中，请等待本轮结束后再操作");
        }
    }

    /** 是否处于暂停 / 挂起态（手动暂停 / 过度访问 / cookie 失效）。 */
    private static boolean isSuspended(ScheduledTask t) {
        return t.suspendReason() != null;
    }

    private void requirePixivTask(ScheduledTask task) {
        if (!DownloadWorkbenchPlugin.ID.equals(task.sourceOwnerPluginId())) {
            throw LocalizedException.badRequest(
                    "schedule.error.source-unavailable", "该任务不使用 Pixiv 凭证策略");
        }
    }

    private SchedulePlanningLease preparePlanningLease(ScheduleTaskRequest req) {
        String requestedType = req.getSourceType() == null ? null : req.getSourceType().trim();
        SchedulePlanningLease planning = scheduleCapabilityRegistry.prepareSource(requestedType).orElse(null);
        if (planning == null) {
            throw LocalizedException.badRequest(
                    "schedule.error.source-unavailable", "计划任务来源当前不可用: {0}", requestedType);
        }
        return planning;
    }

    private void requirePlanningActivation(SchedulePlanningLease planning, String requestedType) {
        if (!scheduleCapabilityRegistry.activate(planning)) {
            throw LocalizedException.badRequest(
                    "schedule.error.source-unavailable", "计划任务来源当前不可用: {0}", requestedType);
        }
    }

    private long requireExpectedStateVersion(ScheduleTaskRequest req) {
        Long expectedStateVersion = req.getExpectedStateVersion();
        if (expectedStateVersion == null || expectedStateVersion < 0) {
            throw LocalizedException.badRequest(
                    "schedule.error.definition-invalid",
                    "编辑计划任务时必须携带有效的任务状态版本");
        }
        return expectedStateVersion;
    }

    private void requireExpectedStateVersion(
            ScheduledTask task,
            long expectedStateVersion) {
        if (task.stateVersion() != expectedStateVersion) {
            throw definitionConcurrentChange();
        }
    }

    private LocalizedException definitionConcurrentChange() {
        return new LocalizedException(
                HttpStatus.CONFLICT,
                "schedule.error.concurrent-change",
                "任务状态已变化，请刷新后重试");
    }

    private <T> T executeDefinitionSave(
            SchedulePlanningLease planning,
            TransactionCallback<T> callback) {
        TransactionTemplate definitionSaveTransaction = new TransactionTemplate(
                Objects.requireNonNull(
                        transactionTemplate.getTransactionManager(),
                        "schedule transaction manager"),
                transactionTemplate);
        definitionSaveTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return scheduleCapabilityRegistry.whileCurrentPublication(
                        planning,
                        () -> definitionSaveTransaction.execute(callback))
                .orElseThrow(this::definitionConcurrentChange);
    }

    private ResolvedDefinition resolveDefinition(
            long taskId,
            ScheduleTaskRequest req,
            String existingOwnerPluginId,
            SchedulePlanningLease planning) {
        if (!Objects.equals(req.getActivationToken(), planning.activationToken())) {
            throw new LocalizedException(
                    HttpStatus.CONFLICT,
                    "schedule.error.concurrent-change",
                    "计划任务来源已刷新，请重新加载后重试");
        }
        String ownerPluginId = planning.owner().featurePluginId();
        if (existingOwnerPluginId != null
                && !existingOwnerPluginId.equals(ownerPluginId)) {
            throw LocalizedException.badRequest(
                    "schedule.error.source-unavailable", "不能把计划任务改为其它来源 owner");
        }

        ScheduledSourceDescriptor descriptor = planning.descriptor().orElse(null);
        ScheduledSourceExecutor sourceExecutor = planning.sourceExecutor().orElse(null);
        if (descriptor == null || sourceExecutor == null) {
            throw LocalizedException.badRequest(
                    "schedule.error.source-unavailable", "计划任务来源当前不可用于创建或修改");
        }

        try {
            ScheduledTaskPresentation seedPresentation = new ScheduledTaskPresentation(
                    req.getName().trim(), null, Map.of());
            ScheduledTaskDraft draft = new ScheduledTaskDraft(
                    taskId,
                    planning.sourceType(),
                    descriptor.definitionSchema(),
                    descriptor.definitionVersion(),
                    req.getDefinitionJson(),
                    seedPresentation);
            ScheduleTaskDefinitionValidator validator =
                    new ScheduleTaskDefinitionValidator(objectMapper);
            validator.validatePrepared(
                    draft.toDefinition(),
                    taskId,
                    planning.sourceType(),
                    descriptor.definitionSchema(),
                    descriptor.definitionVersion());
            planning.cancellation().throwIfCancellationRequested();
            ScheduledTaskDefinition prepared = invokeSourceCallback(
                    () -> sourceExecutor.prepare(draft));
            planning.cancellation().throwIfCancellationRequested();
            ScheduledTaskDefinition definition = validator.validatePrepared(
                    prepared,
                    taskId,
                    planning.sourceType(),
                    descriptor.definitionSchema(),
                    descriptor.definitionVersion());
            ScheduledExecutionPlan plan = invokeSourceCallback(
                    () -> sourceExecutor.plan(definition));
            planning.cancellation().throwIfCancellationRequested();
            validator.validatePlan(descriptor, plan);
            planning.cancellation().throwIfCancellationRequested();
            String credentialPolicyId = plan.credentialPolicyId();
            String credentialPolicyOwnerPluginId = credentialPolicyId == null
                    ? null
                    : scheduleCapabilityRegistry.resolveCredentialPolicy(credentialPolicyId)
                            .map(handle -> handle.owner().featurePluginId())
                            .orElse(null);
            return new ResolvedDefinition(
                    definition,
                    ownerPluginId,
                    credentialPolicyOwnerPluginId,
                    credentialPolicyId);
        } catch (ScheduledExecutionException | RuntimeException failure) {
            throw invalidDefinition();
        }
    }

    private static <T> T invokeSourceCallback(SourceCallback<T> callback) {
        try {
            return callback.call();
        } catch (ScheduledExecutionException | RuntimeException failure) {
            throw invalidDefinition();
        } catch (Throwable failure) {
            rethrowFatal(failure);
            throw invalidDefinition();
        }
    }

    private static LocalizedException invalidDefinition() {
        return LocalizedException.badRequest(
                "schedule.error.definition-invalid", "计划任务定义无效");
    }

    private static void rethrowFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError fatal) {
            throw fatal;
        }
        if (failure instanceof ThreadDeath fatal) {
            throw fatal;
        }
    }

    @FunctionalInterface
    private interface SourceCallback<T> {
        T call() throws ScheduledExecutionException;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to encode schedule presentation", e);
        }
    }

    private static ScheduleSourceManifestView.Source sourceView(
            ScheduleCapabilityRegistry.OwnerView owner,
            ScheduledSourceDescriptor descriptor) {
        ScheduleSourceManifestView.Presentation presentation =
                new ScheduleSourceManifestView.Presentation(
                        descriptor.presentation().displayNamespace(),
                        descriptor.presentation().displayNameKey(),
                        descriptor.presentation().descriptionKey(),
                        descriptor.presentation().iconKey(),
                        descriptor.presentation().colorToken());
        ScheduleSourceManifestView.Frontend frontend = descriptor.frontend() == null
                ? null
                : new ScheduleSourceManifestView.Frontend(
                        descriptor.frontend().contractVersion(),
                        descriptor.frontend().moduleUrl());
        return new ScheduleSourceManifestView.Source(
                descriptor.sourceType(),
                descriptor.legacyAliases().stream().sorted().toList(),
                owner.owner().featurePluginId(),
                owner.owner().packageId(),
                owner.owner().pluginGeneration(),
                owner.publicationId(),
                owner.activationToken(),
                descriptor.definitionSchema(),
                descriptor.definitionVersion(),
                presentation,
                descriptor.acquisitionModes().stream().sorted().toList(),
                descriptor.possibleWorkTypes().stream().sorted().toList(),
                frontend);
    }

    private ScheduleTaskView taskView(
            ScheduledTask task,
            String effectiveRunState,
            Map<SourceActivationKey, String> activations) {
        String activationToken = activations.get(new SourceActivationKey(
                task.sourceOwnerPluginId(), task.sourceType()));
        return ScheduleTaskView.of(
                task,
                effectiveRunState,
                persistenceCodec,
                readPresentation(task.presentationJson()),
                activationToken != null,
                activationToken);
    }

    private Map<SourceActivationKey, String> sourceActivations(
            ScheduleCapabilityRegistry.SnapshotView snapshot) {
        Map<SourceActivationKey, String> activations = new LinkedHashMap<>();
        for (ScheduleCapabilityRegistry.OwnerView owner : snapshot.owners()) {
            for (ScheduledSourceDescriptor descriptor : owner.sourceDescriptors()) {
                SourceActivationKey canonical = new SourceActivationKey(
                        owner.owner().featurePluginId(), descriptor.sourceType());
                activations.put(canonical, owner.activationToken());
                for (String alias : descriptor.legacyAliases()) {
                    activations.put(new SourceActivationKey(
                            owner.owner().featurePluginId(), alias), owner.activationToken());
                }
            }
        }
        return Map.copyOf(activations);
    }

    private ScheduledTaskPresentation readPresentation(String presentationJson) {
        if (presentationJson == null || presentationJson.isBlank()) {
            return ScheduledTaskPresentation.empty();
        }
        try {
            ScheduledTaskPresentation presentation = objectMapper.readValue(
                    presentationJson, ScheduledTaskPresentation.class);
            return new ScheduleTaskDefinitionValidator(objectMapper)
                    .validatePresentation(presentation);
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            return ScheduledTaskPresentation.empty();
        }
    }

    private void requireChanged(OptionalLong changed) {
        if (changed.isEmpty()) {
            throw concurrentChange();
        }
    }

    private void requireCompatibleCredentialBinding(
            ScheduledTask task,
            ResolvedDefinition resolved) {
        boolean hasCredentialBinding = task.credentialPolicyOwnerPluginId() != null
                || task.credentialPolicyId() != null
                || task.credentialSecretReference() != null;
        if (!hasCredentialBinding) {
            return;
        }
        boolean completeExistingIdentity = task.credentialPolicyOwnerPluginId() != null
                && task.credentialPolicyId() != null;
        if (!completeExistingIdentity
                || !Objects.equals(
                        task.credentialPolicyOwnerPluginId(),
                        resolved.credentialPolicyOwnerPluginId())
                || !Objects.equals(task.credentialPolicyId(), resolved.credentialPolicyId())) {
            throw LocalizedException.badRequest(
                    "schedule.error.definition-invalid",
                    "当前任务已绑定不同的凭证策略，请先解除凭证或重新创建任务");
        }
    }

    private LocalizedException concurrentChange() {
        return LocalizedException.badRequest(
                "schedule.error.concurrent-change", "任务状态已变化，请刷新后重试");
    }

    private record ResolvedDefinition(
            ScheduledTaskDefinition definition,
            String sourceOwnerPluginId,
            String credentialPolicyOwnerPluginId,
            String credentialPolicyId) {
    }

    private record SourceActivationKey(
            String ownerPluginId,
            String sourceType) {
    }

    private String validateTrigger(ScheduleTaskRequest req) {
        String kind = req.getTriggerKind() == null ? "" : req.getTriggerKind().trim();
        if (ScheduledTask.TRIGGER_INTERVAL.equals(kind)) {
            if (req.getIntervalMinutes() == null || req.getIntervalMinutes() <= 0) {
                throw LocalizedException.badRequest(
                        "schedule.error.interval-invalid", "固定周期分钟数必须为正整数");
            }
            return kind;
        }
        if (ScheduledTask.TRIGGER_CRON.equals(kind)) {
            String expr = req.getCronExpr();
            if (expr == null || expr.isBlank() || !CronExpression.isValidExpression(expr.trim())) {
                throw LocalizedException.badRequest(
                        "schedule.error.cron-invalid", "Cron 表达式无效");
            }
            return kind;
        }
        throw LocalizedException.badRequest("schedule.error.trigger-invalid", "触发方式无效");
    }

    private static String emptyToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
