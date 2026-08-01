package top.sywyar.pixivdownload.schedule;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import top.sywyar.pixivdownload.core.schedule.ScheduledTask;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskStore;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleSuspendReason;
import top.sywyar.pixivdownload.download.web.LocalizedException;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;
import top.sywyar.pixivdownload.plugin.api.schedule.capability.ScheduleCapabilityAccess;
import top.sywyar.pixivdownload.plugin.api.schedule.capability.ScheduleCapabilityLease;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialAccountActionPlan;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialAccountActionRequest;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialBindResult;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialPolicy;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialProbeResult;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialTaskPresentation;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialTaskSnapshot;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialTaskStateUpdate;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardDecision;
import top.sywyar.pixivdownload.schedule.dto.ScheduleCredentialPolicyView;
import top.sywyar.pixivdownload.schedule.execution.ScheduleCredentialBindingLease;
import top.sywyar.pixivdownload.schedule.execution.ScheduleExecutionEngine;

import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * 宿主拥有的凭证聚合编排：secret 绑定、精确删除、策略纯值投影与账号动作 CAS。具体格式、状态和风险动作
 * 只由当前 {@link ScheduledCredentialPolicy} publication 解释。
 */
@PluginManagedBean
public final class ScheduleCredentialService {

    private final ScheduledTaskStore store;
    private final ScheduleRunState runState;
    private final ScheduleExecutionEngine executionEngine;
    private final ScheduleCapabilityAccess capabilities;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;

    public ScheduleCredentialService(
            ScheduledTaskStore store,
            ScheduleRunState runState,
            ScheduleExecutionEngine executionEngine,
            ScheduleCapabilityAccess capabilities,
            TransactionTemplate transactions,
            ObjectMapper objectMapper) {
        this.store = Objects.requireNonNull(store, "store");
        this.runState = Objects.requireNonNull(runState, "runState");
        this.executionEngine = Objects.requireNonNull(executionEngine, "executionEngine");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public void bind(long taskId, String secret, String expectedSourceActivationToken) {
        ScheduledTask task = requireExisting(taskId);
        requireIdle(task);
        if (secret == null || secret.isBlank()) {
            throw badRequest("schedule.error.credential-empty", "凭证无效或为空");
        }
        String normalizedSecret = secret.trim();
        try (ScheduleCredentialBindingLease binding =
                     executionEngine.prepareCredentialBinding(
                             task, expectedSourceActivationToken)) {
            String policyOwnerPluginId = binding.policyOwnerPluginId();
            String policyId = binding.policyId();
            rejectUnchangedSecret(taskId, policyOwnerPluginId, policyId, normalizedSecret);
            ScheduledCredentialBindResult bindResult = binding.probe(normalizedSecret);
            ScheduledCredentialProbeResult probe = bindResult.probeResult();
            if (probe.status() == ScheduledCredentialProbeResult.Status.INVALID) {
                throw badRequest("schedule.error.credential-invalid", "凭证无效或已失效");
            }
            if (probe.status() == ScheduledCredentialProbeResult.Status.RETRY_LATER) {
                throw badRequest(
                        "schedule.error.credential-probe-failed",
                        "暂时无法验证凭证，请检查网络后重试");
            }
            transactions.executeWithoutResult(status -> bindInTransaction(
                    task, binding, bindResult, normalizedSecret));
        } catch (ScheduleSourcePublicationChangedException failure) {
            throw conflict(
                    "schedule.error.source-publication-changed",
                    "计划任务来源已更新，请刷新后重试");
        } catch (ScheduleSourceUnavailableException
                 | ScheduleExecutorUnavailableException
                 | ScheduleDefinitionException failure) {
            throw badRequest(
                    "schedule.error.execution-capability-unavailable",
                    "计划任务来源或执行能力当前不可用");
        } catch (ScheduledExecutionException failure) {
            throw badRequest(
                    "schedule.error.credential-probe-failed",
                    "暂时无法验证凭证，请检查网络后重试");
        }
    }

    public void revoke(long taskId) {
        transactions.executeWithoutResult(status -> {
            ScheduledTask task = requireExisting(taskId);
            requireIdle(task);
            if (task.credentialPolicyOwnerPluginId() == null) {
                return;
            }
            if (task.credentialPolicyId() == null) {
                throw badRequest(
                        "schedule.error.credential-policy-unavailable",
                        "计划任务凭证策略当前不可用");
            }
            requireChanged(store.removeCredential(
                    taskId, task.stateVersion(), task.credentialPolicyOwnerPluginId(),
                    task.credentialPolicyId()));
        });
    }

    public ScheduleCredentialPolicyView project(ScheduledTask task) {
        String ownerPluginId = task.credentialPolicyOwnerPluginId();
        String policyId = task.credentialPolicyId();
        boolean bound = ownerPluginId != null && task.credentialSecretReference() != null;
        if (ownerPluginId == null || policyId == null) {
            return ScheduleCredentialPolicyView.unavailable(
                    ownerPluginId, policyId, task.credentialAccountKey(), bound);
        }
        ScheduleCapabilityLease<ScheduledCredentialPolicy> lease =
                preparePolicyLease(policyId);
        try (lease) {
            if (lease == null
                    || !ownerPluginId.equals(lease.owner().featurePluginId())
                    || !capabilities.activate(lease)) {
                return ScheduleCredentialPolicyView.unavailable(
                        ownerPluginId, policyId, task.credentialAccountKey(), bound);
            }
            ScheduledCredentialTaskPresentation presentation;
            try {
                presentation = lease.capability().taskPresentation(snapshot(task));
            } catch (RuntimeException failure) {
                presentation = ScheduledCredentialTaskPresentation.empty();
            }
            return ScheduleCredentialPolicyView.available(
                    ownerPluginId, policyId, task.credentialAccountKey(), bound,
                    lease.publicationId(), presentation);
        }
    }

    public void applyAccountAction(
            String expectedOwnerPluginId,
            String policyId,
            long expectedPublicationId,
            String accountKey,
            String actionId,
            Map<String, String> parameters) {
        ScheduleCapabilityLease<ScheduledCredentialPolicy> lease = preparePolicyLease(policyId);
        try (lease) {
            if (lease == null
                    || !Objects.equals(expectedOwnerPluginId, lease.owner().featurePluginId())
                    || expectedPublicationId != lease.publicationId()
                    || !capabilities.activate(lease)) {
                throw conflict(
                        "schedule.error.credential-policy-publication-changed",
                        "凭证策略已更新，请刷新后重试");
            }
            List<ScheduledTask> tasks = accountTasks(
                    expectedOwnerPluginId, policyId, accountKey);
            if (tasks.isEmpty()) {
                throw badRequest(
                        "schedule.error.credential-account-not-found",
                        "凭证账号下无计划任务");
            }
            List<ScheduledCredentialTaskSnapshot> snapshots = snapshots(tasks);
            ScheduledCredentialAccountActionPlan plan;
            try {
                plan = lease.capability().prepareAccountAction(
                                new ScheduledCredentialAccountActionRequest(
                                        accountKey, actionId,
                                        parameters == null ? Map.of() : parameters,
                                        System.currentTimeMillis(), snapshots))
                        .orElseThrow(() -> badRequest(
                                "schedule.error.credential-account-action-unsupported",
                                "当前凭证策略不支持该账号动作"));
            } catch (LocalizedException failure) {
                throw failure;
            } catch (IllegalArgumentException failure) {
                throw badRequest(
                        "schedule.error.credential-account-action-invalid",
                        "凭证账号动作无效");
            } catch (RuntimeException failure) {
                throw badRequest(
                        "schedule.error.credential-account-action-failed",
                        "凭证账号动作暂时无法完成，请刷新后重试");
            }
            if (lease.cancellation().isCancellationRequested()) {
                throw conflict(
                        "schedule.error.credential-policy-publication-changed",
                        "凭证策略已更新，请刷新后重试");
            }
            Optional<Boolean> applied = capabilities.whileCurrentPublication(
                    lease,
                    () -> Boolean.TRUE.equals(transactions.execute(status -> {
                        applyPlan(expectedOwnerPluginId, policyId, accountKey, snapshots, plan);
                        return Boolean.TRUE;
                    })));
            if (applied.isEmpty() || !applied.orElse(false)) {
                throw conflict(
                        "schedule.error.credential-policy-publication-changed",
                        "凭证策略已更新，请刷新后重试");
            }
        }
    }

    private void bindInTransaction(
            ScheduledTask expectedTask,
            ScheduleCredentialBindingLease binding,
            ScheduledCredentialBindResult bindResult,
            String secret) {
        ScheduledTask current = requireExisting(expectedTask.id());
        if (current.stateVersion() != expectedTask.stateVersion()) {
            throw concurrentChange();
        }
        requireIdle(current);
        requireBindingActive(binding);
        String ownerPluginId = binding.policyOwnerPluginId();
        String policyId = binding.policyId();
        rejectUnchangedSecret(current.id(), ownerPluginId, policyId, secret);
        boolean sameBinding = ownerPluginId.equals(current.credentialPolicyOwnerPluginId())
                && policyId.equals(current.credentialPolicyId())
                && Objects.equals(
                        bindResult.probeResult().accountKey(), current.credentialAccountKey());
        String policyState = sameBinding && current.credentialPolicyStateJson() != null
                ? current.credentialPolicyStateJson()
                : bindResult.initialPolicyStateJson();
        OptionalLong bound = store.bindCredential(
                current.id(), current.stateVersion(), ownerPluginId, policyId,
                bindResult.probeResult().accountKey(), policyState, secret,
                "scheduled-task:" + current.id() + ":credential",
                System.currentTimeMillis());
        requireChanged(bound);
        long version = bound.getAsLong();
        if (current.suspendReason() == ScheduleSuspendReason.CREDENTIAL) {
            OptionalLong resumed = store.resume(
                    current.id(), version, ScheduleSuspendReason.CREDENTIAL,
                    current.suspendCode(), nextRunFor(current));
            requireChanged(resumed);
            version = resumed.getAsLong();
        }
        ScheduledGuardDecision postBind = bindResult.postBindResult().decision();
        if (postBind.action() == ScheduledGuardDecision.Action.SUSPEND_POLICY_TASK
                && (current.suspendReason() == null
                || current.suspendReason() == ScheduleSuspendReason.CREDENTIAL)) {
            requireChanged(store.suspend(
                    current.id(), version, ScheduleSuspendReason.POLICY,
                    postBind.reasonCode(), writeJson(
                            bindResult.postBindResult().evidence().attributes())));
        }
        requireBindingActive(binding);
    }

    private void applyPlan(
            String ownerPluginId,
            String policyId,
            String accountKey,
            List<ScheduledCredentialTaskSnapshot> expectedSnapshots,
            ScheduledCredentialAccountActionPlan plan) {
        List<ScheduledTask> currentTasks = accountTasks(ownerPluginId, policyId, accountKey);
        if (!snapshots(currentTasks).equals(expectedSnapshots)) {
            throw concurrentChange();
        }
        Map<Long, ScheduledCredentialTaskSnapshot> expectedById = new LinkedHashMap<>();
        expectedSnapshots.forEach(task -> expectedById.put(task.taskId(), task));
        Map<Long, Long> currentVersions = new LinkedHashMap<>();
        expectedSnapshots.forEach(task -> currentVersions.put(
                task.taskId(), task.stateVersion()));
        long updatedTime = System.currentTimeMillis();
        for (ScheduledCredentialTaskStateUpdate update : plan.stateUpdates()) {
            ScheduledCredentialTaskSnapshot expected = expectedById.get(update.taskId());
            if (expected == null || expected.stateVersion() != update.expectedStateVersion()) {
                throw badRequest(
                        "schedule.error.credential-account-action-invalid",
                        "凭证账号动作返回了无效任务状态");
            }
            OptionalLong changed = store.updateCredentialPolicyState(
                    update.taskId(), update.expectedStateVersion(), ownerPluginId, policyId,
                    update.expectedPolicyStateJson(), update.nextPolicyStateJson(),
                    updatedTime);
            requireChanged(changed);
            currentVersions.put(update.taskId(), changed.getAsLong());
        }
        List<ScheduledCredentialTaskSnapshot> resumeTargets = expectedSnapshots.stream()
                .filter(ScheduledCredentialTaskSnapshot::policySuspended)
                .filter(task -> plan.expectedSuspendCode().equals(task.suspendCode()))
                .toList();
        if (resumeTargets.isEmpty()) {
            throw badRequest(
                    "schedule.error.credential-account-action-invalid",
                    "凭证账号下没有可由该动作恢复的任务");
        }
        for (ScheduledCredentialTaskSnapshot target : resumeTargets) {
            requireChanged(store.resume(
                    target.taskId(), currentVersions.get(target.taskId()),
                    ScheduleSuspendReason.POLICY, plan.expectedSuspendCode(),
                    plan.nextRunTime()));
        }
    }

    private List<ScheduledTask> accountTasks(
            String ownerPluginId,
            String policyId,
            String accountKey) {
        List<ScheduledTask> tasks = new ArrayList<>();
        for (ScheduledTask task : store.findByCredentialAccount(
                ownerPluginId, policyId, accountKey)) {
            if (task != null
                    && Objects.equals(ownerPluginId,
                    task.credentialPolicyOwnerPluginId())
                    && Objects.equals(policyId, task.credentialPolicyId())
                    && Objects.equals(accountKey, task.credentialAccountKey())) {
                tasks.add(task);
            }
        }
        tasks.sort(Comparator.comparingLong(ScheduledTask::id));
        return List.copyOf(tasks);
    }

    private List<ScheduledCredentialTaskSnapshot> snapshots(List<ScheduledTask> tasks) {
        return tasks.stream().map(this::snapshot).toList();
    }

    private ScheduledCredentialTaskSnapshot snapshot(ScheduledTask task) {
        return new ScheduledCredentialTaskSnapshot(
                task.id(), task.stateVersion(),
                task.suspendReason() == ScheduleSuspendReason.CREDENTIAL,
                task.suspendReason() == ScheduleSuspendReason.POLICY,
                isBusy(task), task.suspendCode(), task.suspendDetailJson(),
                task.credentialPolicyStateJson());
    }

    private ScheduleCapabilityLease<ScheduledCredentialPolicy> preparePolicyLease(
            String policyId) {
        if (policyId == null || policyId.isBlank()) {
            return null;
        }
        @SuppressWarnings("unchecked")
        ScheduleCapabilityLease<ScheduledCredentialPolicy> lease =
                (ScheduleCapabilityLease<ScheduledCredentialPolicy>) capabilities
                        .prepareCredentialPolicy(policyId.trim())
                        .orElse(null);
        return lease;
    }

    private ScheduledTask requireExisting(long taskId) {
        ScheduledTask task = store.findById(taskId);
        if (task == null) {
            throw badRequest("schedule.error.not-found", "计划任务不存在: {0}", taskId);
        }
        return task;
    }

    private void requireIdle(ScheduledTask task) {
        if (isBusy(task)) {
            throw badRequest("schedule.error.busy", "任务正在运行或排队中，请等待本轮结束后再操作");
        }
    }

    private boolean isBusy(ScheduledTask task) {
        return task.runState() != null || runState.get(task.id()) != null;
    }

    private void rejectUnchangedSecret(
            long taskId,
            String ownerPluginId,
            String policyId,
            String secret) {
        String current = store.findCredentialSecret(taskId, ownerPluginId, policyId);
        if (current != null && current.equals(secret)) {
            throw badRequest(
                    "schedule.error.credential-unchanged",
                    "凭证与当前已绑定的相同，未做更新；若任务因凭证失效被挂起，请改用新的有效凭证");
        }
    }

    private void requireBindingActive(ScheduleCredentialBindingLease binding) {
        try {
            binding.throwIfCancellationRequested();
        } catch (ScheduledExecutionException failure) {
            throw concurrentChange();
        }
    }

    private void requireChanged(OptionalLong changed) {
        if (changed.isEmpty()) {
            throw concurrentChange();
        }
    }

    private String writeJson(Map<String, String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? Map.of() : values);
        } catch (JsonProcessingException failure) {
            throw badRequest(
                    "schedule.error.credential-policy-result-invalid",
                    "凭证策略返回了无效结果");
        }
    }

    private static Long nextRunFor(ScheduledTask task) {
        return task.nextRunTime() == null ? System.currentTimeMillis() : task.nextRunTime();
    }

    private static LocalizedException badRequest(
            String code,
            String message,
            Object... args) {
        return LocalizedException.badRequest(code, message, args);
    }

    private static LocalizedException conflict(
            String code,
            String message,
            Object... args) {
        return new LocalizedException(HttpStatus.CONFLICT, code, message, args);
    }

    private static LocalizedException concurrentChange() {
        return conflict(
                "schedule.error.concurrent-change",
                "任务状态已变化，请刷新后重试");
    }
}
