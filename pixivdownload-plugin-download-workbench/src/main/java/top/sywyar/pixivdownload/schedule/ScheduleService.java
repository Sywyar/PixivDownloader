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
import top.sywyar.pixivdownload.download.web.LocalizedException;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;
import top.sywyar.pixivdownload.core.schedule.ScheduledTask;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskCreate;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskStore;
import top.sywyar.pixivdownload.core.schedule.ScheduleTaskDefinitionUpdate;
import top.sywyar.pixivdownload.core.schedule.ScheduledPendingWork;
import top.sywyar.pixivdownload.plugin.api.schedule.capability.ScheduleCapabilityAccess;
import top.sywyar.pixivdownload.plugin.api.schedule.capability.ScheduleCapabilityLease;
import top.sywyar.pixivdownload.plugin.api.schedule.capability.ScheduleCapabilityOwner;
import top.sywyar.pixivdownload.plugin.api.schedule.capability.ScheduleCapabilityOwnerSnapshot;
import top.sywyar.pixivdownload.plugin.api.schedule.capability.ScheduleCapabilitySnapshot;
import top.sywyar.pixivdownload.plugin.api.schedule.capability.SchedulePlanningLease;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleRunToken;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleSuspendReason;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionPlan;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceDescriptor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceExecutor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskDefinition;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskDraft;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskPresentation;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkExecutor;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkKey;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkResult;
import top.sywyar.pixivdownload.schedule.dto.ScheduleCredentialPolicyActionRequest;
import top.sywyar.pixivdownload.schedule.dto.ScheduleQueueView;
import top.sywyar.pixivdownload.schedule.dto.SchedulePendingView;
import top.sywyar.pixivdownload.schedule.dto.ScheduleSourceManifestView;
import top.sywyar.pixivdownload.schedule.dto.ScheduleTaskRequest;
import top.sywyar.pixivdownload.schedule.dto.ScheduleTaskView;
import top.sywyar.pixivdownload.schedule.definition.ScheduleTaskDefinitionValidator;
import top.sywyar.pixivdownload.schedule.security.ScheduleCredentialRedactor;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 计划任务的增删改查、凭证聚合委托与「立即运行」入口。
 *
 * <p>运行编排在 {@link ScheduleExecutor} / {@link ScheduleRunner}。
 */
@Slf4j
@PluginManagedBean
@RequiredArgsConstructor
public class ScheduleService {

    private static final int MAX_PLUGIN_STATUS_ENTRIES = 16;
    private static final int MAX_PLUGIN_STATUS_KEY_BYTES = 64;
    private static final int MAX_PLUGIN_STATUS_VALUE_BYTES = 256;
    private static final int MAX_PLUGIN_STATUS_TOTAL_BYTES = 4_096;
    static final int MAX_PLUGIN_STATUS_RESPONSE_BYTES = 512 * 1024;
    private static final Pattern PLUGIN_STATUS_KEY =
            Pattern.compile("[A-Za-z][A-Za-z0-9._-]{0,63}");

    private final ScheduledTaskStore store;
    private final ScheduleExecutor executor;
    private final ScheduleConfig config;
    private final ScheduleRunState runState;
    private final ScheduleRunQueue runQueue;
    private final ObjectMapper objectMapper;
    private final ScheduleCredentialService credentialService;
    private final TransactionTemplate transactionTemplate;
    /** 作品类型执行器注册中心；队列只经短租约读取插件主动开放的中性实时状态。 */
    private final ScheduleCapabilityAccess scheduleCapabilityRegistry;
    private final ScheduleHostIdentity hostIdentity;

    public ScheduleSourceManifestView sources() {
        ScheduleCapabilitySnapshot snapshot = scheduleCapabilityRegistry.snapshot();
        List<ScheduleSourceManifestView.Source> sources = snapshot.owners().stream()
                .flatMap(owner -> owner.sourceDescriptors().stream()
                        .map(descriptor -> sourceView(owner, descriptor)))
                .sorted(java.util.Comparator.comparing(ScheduleSourceManifestView.Source::sourceType))
                .toList();
        return new ScheduleSourceManifestView(snapshot.epoch(), snapshot.revision(), sources);
    }

    public List<ScheduleTaskView> list() {
        Map<SourceActivationKey, SourceProjection> activations = sourceActivations(
                scheduleCapabilityRegistry.snapshot());
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
                scheduleCapabilityRegistry.snapshot()));
    }

    public ScheduleTaskView create(ScheduleTaskRequest req) {
        if (req.getExpectedStateVersion() != null) {
            throw LocalizedException.badRequest(
                    "schedule.error.create-state-version",
                    "创建计划任务时不能携带任务状态版本");
        }
        SchedulePlanningLease planning = preparePlanningLease(req);
        try (planning) {
            requirePlanningActivation(planning, req.getSourceType());
            String triggerKind = validateTrigger(req);
            long now = System.currentTimeMillis();
            ResolvedDefinition resolved = resolveDefinition(0, req, null, planning);
            ScheduledTaskCreate command = new ScheduledTaskCreate(
                    req.getName().trim(),
                    resolved.definition().sourceType(),
                    resolved.sourceOwnerPluginId(),
                    resolved.definition().definitionSchema(),
                    resolved.definition().definitionVersion(),
                    resolved.definition().definitionJson(),
                    writeJson(resolved.definition().presentation()),
                    triggerKind,
                    req.getIntervalMinutes(),
                    emptyToNull(req.getCronExpr()),
                    ScheduleTiming.computeNextRun(
                            triggerKind, req.getIntervalMinutes(), req.getCronExpr(), now),
                    now);

            return executeDefinitionSave(planning, status -> {
                if (store.countAll() >= config.getMaxTasks()) {
                    throw LocalizedException.badRequest(
                            "schedule.error.max-tasks", "计划任务数量已达上限: {0}", config.getMaxTasks());
                }
                return get(store.create(command));
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
        List<ScheduleRunQueue.Item> snapshot = run.snapshot();
        LiveStatusProjection liveStatusProjection = loadLiveStatuses(snapshot);
        List<ScheduleQueueView.Item> items = snapshot.stream()
                .map(item -> toQueueItem(
                        item,
                        liveStatusProjection.statuses()
                                .getOrDefault(item.key(), Map.of())))
                .toList();
        return new ScheduleQueueView(
                id,
                run.startedTime(),
                run.truncated() || liveStatusProjection.truncated(),
                items.size(),
                items);
    }

    /**
     * 把内存队列条目映射为中性对外视图。展示属性和结果属性保持原始机器数据，由作品类型 owner
     * 的前端模块解释；宿主不把未知属性提升成固定 wire 字段。
     */
    private static ScheduleQueueView.Item toQueueItem(
            ScheduleRunQueue.Item item,
            Map<String, String> liveStatus) {
        var presentation = item.presentation();
        ScheduledWorkResult result = item.result();
        return new ScheduleQueueView.Item(
                item.key().id(),
                item.key().workType(),
                presentation.title(),
                presentation.author(),
                presentation.thumbnailReference(),
                presentation.attributes(),
                item.status(),
                item.message(),
                result == null ? Map.of() : result.attributes(),
                liveStatus);
    }

    /**
     * 为任务绑定执行计划声明的凭证。格式校验、探活、策略状态与精确 publication CAS
     * 由凭证聚合服务编排；本 CRUD 服务不解释具体凭证类型。
     */
    public ScheduleTaskView bindCredential(
            long id,
            String secret,
            String expectedActivationToken) {
        credentialService.bind(id, secret, expectedActivationToken);
        return get(id);
    }

    /** 解除任务持久化记录的凭证策略绑定；即使策略插件暂时缺席也可完成。 */
    public ScheduleTaskView revokeCredential(long id) {
        credentialService.revoke(id);
        return get(id);
    }

    /** 在当前策略 publication 上规划并以精确事务 CAS 应用账号级动作。 */
    public void applyCredentialPolicyAction(ScheduleCredentialPolicyActionRequest request) {
        if (request == null || request.getPublicationId() == null) {
            throw credentialPolicyPublicationChanged();
        }
        credentialService.applyAccountAction(
                request.getOwnerPluginId(), request.getPolicyId(), request.getPublicationId(),
                request.getAccountKey(), request.getActionId(), request.getParameters());
    }

    /**
     * 供来源自有旧版 HTTP 适配器解析当前策略 publication；随后仍由凭证服务按精确 publication
     * 取得租约并执行，快照与租约之间发生 reload 时会安全拒绝。
     */
    public void applyCurrentCredentialPolicyAction(
            String ownerPluginId,
            String policyId,
            String accountKey,
            String actionId,
            Map<String, String> parameters) {
        long publicationId = scheduleCapabilityRegistry.snapshot().owners().stream()
                .filter(owner -> owner.owner().featurePluginId().equals(ownerPluginId))
                .filter(owner -> owner.credentialPolicyIds().contains(policyId))
                .mapToLong(ScheduleCapabilityOwnerSnapshot::publicationId)
                .findFirst()
                .orElseThrow(this::credentialPolicyUnavailable);
        credentialService.applyAccountAction(
                ownerPluginId, policyId, publicationId,
                accountKey, actionId, parameters);
    }

    /**
     * 设置 / 清除任务级网络代理覆盖（{@code host:port}，非凭证）。设置后由来源能力决定哪些出站请求使用它；
     * {@code null} / 空白 = 清除并回退全局代理设置。
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
        ScheduleCapabilityLease<ScheduleCapabilityOwner> hostLease = prepareHostLease();
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

    private LiveStatusProjection loadLiveStatuses(
            List<ScheduleRunQueue.Item> items) {
        Map<LiveStatusOwnerKey, List<ScheduleRunQueue.Item>> byOwner =
                new LinkedHashMap<>();
        for (ScheduleRunQueue.Item item : items) {
            ScheduledWorkResult result = item.result();
            if (result != null && result.liveStatusAvailable()) {
                LiveStatusOwnerKey ownerKey = new LiveStatusOwnerKey(
                        item.key().workType(),
                        item.workExecutorOwner(),
                        item.workExecutorPublicationId());
                byOwner.computeIfAbsent(ownerKey, ignored -> new ArrayList<>()).add(item);
            }
        }
        if (byOwner.isEmpty()) {
            return LiveStatusProjection.empty();
        }

        Map<ScheduledWorkKey, Map<String, String>> statuses = new LinkedHashMap<>();
        LiveStatusBudget budget = new LiveStatusBudget();
        for (Map.Entry<LiveStatusOwnerKey, List<ScheduleRunQueue.Item>> entry
                : byOwner.entrySet()) {
            loadLiveStatuses(entry.getKey(), entry.getValue(), statuses, budget);
            if (budget.truncated()) {
                break;
            }
        }
        return new LiveStatusProjection(Map.copyOf(statuses), budget.truncated());
    }

    private void loadLiveStatuses(
            LiveStatusOwnerKey ownerKey,
            List<ScheduleRunQueue.Item> items,
            Map<ScheduledWorkKey, Map<String, String>> target,
            LiveStatusBudget budget) {
        try {
            ScheduleCapabilityLease<ScheduledWorkExecutor> lease =
                    scheduleCapabilityRegistry.prepareWorkExecutor(
                            ownerKey.workType()).orElse(null);
            try (lease) {
                if (lease == null
                        || !ownerKey.owner().equals(lease.owner())
                        || ownerKey.publicationId() != lease.publicationId()
                        || !scheduleCapabilityRegistry.activate(lease)) {
                    return;
                }
                ScheduledWorkExecutor executor = lease.capability();
                for (ScheduleRunQueue.Item item : items) {
                    try {
                        Map<String, String> status =
                                safeLiveStatus(executor.status(item.key()));
                        if (!status.isEmpty()) {
                            if (!budget.reserve(liveStatusUtf8Bytes(status))) {
                                return;
                            }
                            target.put(item.key(), status);
                        }
                    } catch (Throwable failure) {
                        rethrowFatal(failure);
                        log.debug(
                                "Scheduled work live status item is temporarily unavailable for work type {}",
                                ownerKey.workType());
                    }
                }
            }
        } catch (Throwable failure) {
            rethrowFatal(failure);
            log.debug(
                    "Scheduled work live status capability is temporarily unavailable for work type {}",
                    ownerKey.workType());
        }
    }

    private static Map<String, String> safeLiveStatus(Map<String, String> status) {
        if (status == null || status.isEmpty()) {
            return Map.of();
        }
        if (status.size() > MAX_PLUGIN_STATUS_ENTRIES) {
            return Map.of();
        }
        Map<String, String> copy = new LinkedHashMap<>();
        int totalBytes = 0;
        for (Map.Entry<String, String> entry : status.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null
                    || key.length() > MAX_PLUGIN_STATUS_KEY_BYTES
                    || !PLUGIN_STATUS_KEY.matcher(key).matches()
                    || value == null
                    || value.length() > MAX_PLUGIN_STATUS_VALUE_BYTES
                    || containsControlCharacter(value)
                    || ScheduleCredentialRedactor.isSensitiveFieldName(key)
                    || (ScheduleCredentialRedactor.isSensitiveMetadataFieldName(key)
                    && !ScheduleCredentialRedactor.isSafeMetadataValue(key, value))
                    || ScheduleCredentialRedactor.containsCredentialMaterial(value)) {
                return Map.of();
            }
            int keyBytes = key.getBytes(StandardCharsets.UTF_8).length;
            int valueBytes = value.getBytes(StandardCharsets.UTF_8).length;
            if (keyBytes > MAX_PLUGIN_STATUS_KEY_BYTES
                    || valueBytes > MAX_PLUGIN_STATUS_VALUE_BYTES) {
                return Map.of();
            }
            totalBytes = Math.addExact(totalBytes, Math.addExact(keyBytes, valueBytes));
            if (totalBytes > MAX_PLUGIN_STATUS_TOTAL_BYTES) {
                return Map.of();
            }
            copy.put(key, value);
        }
        return Map.copyOf(copy);
    }

    private static int liveStatusUtf8Bytes(Map<String, String> status) {
        int total = 0;
        for (Map.Entry<String, String> entry : status.entrySet()) {
            total += entry.getKey().getBytes(StandardCharsets.UTF_8).length;
            total += entry.getValue().getBytes(StandardCharsets.UTF_8).length;
        }
        return total;
    }

    private static boolean containsControlCharacter(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                return true;
            }
        }
        return false;
    }

    private record LiveStatusProjection(
            Map<ScheduledWorkKey, Map<String, String>> statuses,
            boolean truncated) {

        private static LiveStatusProjection empty() {
            return new LiveStatusProjection(Map.of(), false);
        }
    }

    private record LiveStatusOwnerKey(
            String workType,
            ScheduleCapabilityOwner owner,
            long publicationId) {
    }

    private static final class LiveStatusBudget {

        private int retainedBytes;
        private boolean truncated;

        private boolean reserve(int bytes) {
            if (bytes > MAX_PLUGIN_STATUS_RESPONSE_BYTES - retainedBytes) {
                truncated = true;
                return false;
            }
            retainedBytes += bytes;
            return true;
        }

        private boolean truncated() {
            return truncated;
        }
    }

    private ScheduleCapabilityLease<ScheduleCapabilityOwner> prepareHostLease() {
        return scheduleCapabilityRegistry.prepareOwner(hostIdentity.featurePluginId()).orElse(null);
    }

    // ── 暂停 / 恢复 ───────────────────────────────────────────────────────────────

    /**
     * 手动挂起（{@code MANUAL}）：不冻账号、不发邮件；findDue 状态门挡住，不再到期触发。
     *
     * <p><b>仅在任务正在运行 / 排队（busy）时可暂停</b>：暂停语义是「打断当前这一轮」，空闲任务没有可打断的运行，
     * 应改用「停用」阻止自动调度。空闲时直接拒绝（前端也会禁用按钮，这里是后端防护）。
     *
     * <p>通过 {@link ScheduleRunState#requestCancel(long)} 给正在运行的本轮派发循环发协作式取消信号：
     * 通用执行引擎在下一个作品边界观察取消并干净收束本轮，
     * 这样「按下暂停立刻见效」，已下载的不回滚、未派发的不再继续；持久化状态先原子转为
     * {@code CANCEL_REQUESTED}，再由持有原 claim token 的执行方完成取消收尾。
     */
    @Transactional
    public ScheduleTaskView pause(long id) {
        ScheduledTask task = requireExisting(id);
        if (task.suspendReason() != null) {
            throw LocalizedException.badRequest(
                    "schedule.error.already-suspended", "任务已处于暂停或挂起状态");
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

    /** 是否处于任一正交暂停 / 挂起态。 */
    private static boolean isSuspended(ScheduledTask t) {
        return t.suspendReason() != null;
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
                    "schedule.error.expected-state-version",
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
                    "schedule.error.source-publication-changed",
                    "计划任务来源已刷新，请重新加载后重试");
        }
        String ownerPluginId = planning.owner().featurePluginId();
        if (existingOwnerPluginId != null
                && !existingOwnerPluginId.equals(ownerPluginId)) {
            throw LocalizedException.badRequest(
                    "schedule.error.source-owner-change-forbidden", "不能把计划任务改为其它来源 owner");
        }

        ScheduledSourceDescriptor descriptor = planning.descriptor().orElse(null);
        ScheduledSourceExecutor sourceExecutor = planning.sourceExecutor().orElse(null);
        if (descriptor == null || sourceExecutor == null) {
            throw LocalizedException.badRequest(
                    "schedule.error.source-write-unavailable", "计划任务来源当前不可用于创建或修改");
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
                    : scheduleCapabilityRegistry.credentialPolicyOwner(credentialPolicyId)
                            .map(ScheduleCapabilityOwner::featurePluginId)
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
            ScheduleCapabilityOwnerSnapshot owner,
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
            Map<SourceActivationKey, SourceProjection> activations) {
        SourceProjection source = activations.get(new SourceActivationKey(
                task.sourceOwnerPluginId(), task.sourceType()));
        return ScheduleTaskView.of(
                task,
                effectiveRunState,
                source == null ? task.sourceType() : source.legacyType(),
                readPresentation(task.presentationJson()),
                source != null,
                source == null ? null : source.activationToken(),
                credentialService.project(task));
    }

    private Map<SourceActivationKey, SourceProjection> sourceActivations(
            ScheduleCapabilitySnapshot snapshot) {
        Map<SourceActivationKey, SourceProjection> activations = new LinkedHashMap<>();
        for (ScheduleCapabilityOwnerSnapshot owner : snapshot.owners()) {
            for (ScheduledSourceDescriptor descriptor : owner.sourceDescriptors()) {
                String legacyType = descriptor.legacyAliases().stream()
                        .sorted()
                        .findFirst()
                        .orElse(descriptor.sourceType());
                SourceProjection projection = new SourceProjection(
                        owner.activationToken(), legacyType);
                SourceActivationKey canonical = new SourceActivationKey(
                        owner.owner().featurePluginId(), descriptor.sourceType());
                activations.put(canonical, projection);
                for (String alias : descriptor.legacyAliases()) {
                    activations.put(new SourceActivationKey(
                            owner.owner().featurePluginId(), alias), projection);
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
                    "schedule.error.credential-policy-changed",
                    "当前任务已绑定不同的凭证策略，请先解除凭证或重新创建任务");
        }
    }

    private LocalizedException concurrentChange() {
        return LocalizedException.badRequest(
                "schedule.error.concurrent-change", "任务状态已变化，请刷新后重试");
    }

    private LocalizedException credentialPolicyPublicationChanged() {
        return LocalizedException.badRequest(
                "schedule.error.credential-policy-publication-changed",
                "凭证策略已更新，请刷新后重试");
    }

    private LocalizedException credentialPolicyUnavailable() {
        return LocalizedException.badRequest(
                "schedule.error.credential-policy-unavailable",
                "计划任务凭证策略当前不可用");
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

    private record SourceProjection(
            String activationToken,
            String legacyType) {
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
