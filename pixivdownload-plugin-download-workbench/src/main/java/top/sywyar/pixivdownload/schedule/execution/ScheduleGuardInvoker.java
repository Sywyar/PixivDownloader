package top.sywyar.pixivdownload.schedule.execution;

import top.sywyar.pixivdownload.core.schedule.ScheduledTask;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskStore;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleSuspendReason;
import top.sywyar.pixivdownload.plugin.api.schedule.capability.ScheduleCapabilityOwner;
import top.sywyar.pixivdownload.plugin.api.schedule.capability.ScheduleExecutionLease;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialAccountIncident;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialIncidentPresentation;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialTaskSnapshot;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledCancellation;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionPlan;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledFailure;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledExecutionGuard;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardBinding;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardContext;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardDecision;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardEvidence;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardPoint;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardResult;
import top.sywyar.pixivdownload.plugin.api.schedule.network.ScheduledNetworkRoute;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskDefinition;
import top.sywyar.pixivdownload.schedule.ScheduleRunState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static top.sywyar.pixivdownload.schedule.execution.ScheduleExecutionSafety.*;

/** 计划执行 Guard 的检查点调用、失败隔离与账号事件投影。 */
final class ScheduleGuardInvoker {

    private final ScheduledTask taskRow;
    private final ScheduledTaskDefinition definition;
    private final ScheduledNetworkRoute route;
    private final ScheduledCancellation cancellation;
    private final ScheduleCredentialMaterial credential;
    private final ScheduleExecutionLease execution;
    private final ScheduledExecutionPlan plan;
    private final ScheduledTaskStore store;
    private final ScheduleRunState runState;
    private boolean failureInvoked;
    private ScheduleExecutionControlException failureDecision;

    ScheduleGuardInvoker(
            ScheduledTask taskRow,
            ScheduledTaskDefinition definition,
            ScheduledNetworkRoute route,
            ScheduledCancellation cancellation,
            ScheduleCredentialMaterial credential,
            ScheduleExecutionLease execution,
            ScheduledExecutionPlan plan,
            ScheduledTaskStore store,
            ScheduleRunState runState) {
        this.taskRow = taskRow;
        this.definition = definition;
        this.route = route;
        this.cancellation = cancellation;
        this.credential = credential;
        this.execution = execution;
        this.plan = plan;
        this.store = store;
        this.runState = runState;
    }

    boolean hasBatchGuardAt(long attempted) {
        return plan.guards().stream().anyMatch(binding ->
                binding.points().contains(ScheduledGuardPoint.WORK_BATCH)
                        && attempted % binding.workBatchSize() == 0);
    }

    boolean invoke(ScheduledGuardPoint point, long attempted, ScheduledFailure failure)
            throws ScheduleExecutionControlException, ScheduledExecutionException {
        if (point != ScheduledGuardPoint.RUN_FAILURE) {
            cancellation.throwIfCancellationRequested();
        }
        boolean revoked = false;
        for (ScheduledGuardBinding binding : plan.guards()) {
            if (!binding.points().contains(point)) {
                continue;
            }
            if (point == ScheduledGuardPoint.WORK_BATCH
                    && attempted % binding.workBatchSize() != 0) {
                continue;
            }
            if (point != ScheduledGuardPoint.RUN_FAILURE) {
                cancellation.throwIfCancellationRequested();
            }
            ScheduledExecutionGuard guard = execution.guard(binding.guardId())
                    .orElseThrow(() -> pluginFailure("schedule.guard.unavailable"));
            ScheduledGuardResult result = invokeOne(
                    guard, binding.guardId(), point, attempted, failure);
            ScheduledGuardDecision decision = result.decision();
            if (decision.action() == ScheduledGuardDecision.Action.CONTINUE) {
                continue;
            }
            if (!isSafeMachineCode(decision.reasonCode())) {
                throw pluginFailure("schedule.guard.invalid-result");
            }
            if (decision.action() == ScheduledGuardDecision.Action.REVOKE_CREDENTIAL_AND_CONTINUE
                    && point == ScheduledGuardPoint.RUN_START
                    && plan.anonymousFallbackAllowed()) {
                credential.revoke();
                revoked = true;
                continue;
            }
            throw control(
                    decision.action(),
                    decision.reasonCode(),
                    decision.retryAfterMillis(),
                    result.evidence(),
                    incidentPresentation(decision, result.evidence()));
        }
        return revoked;
    }

    ScheduleExecutionControlException invokeFailureOnce(
            long attempted,
            ScheduledFailure failure,
            ScheduleExecutionSafety.DeferredFatal fatalFailures) {
        if (failureInvoked) {
            return failureDecision;
        }
        failureInvoked = true;
        for (ScheduledGuardBinding binding : plan.guards()) {
            if (!binding.points().contains(ScheduledGuardPoint.RUN_FAILURE)) {
                continue;
            }
            try {
                ScheduledExecutionGuard guard = execution.guard(binding.guardId())
                        .orElseThrow(() -> pluginFailure("schedule.guard.unavailable"));
                ScheduledGuardResult result = invokeOne(
                        guard,
                        binding.guardId(),
                        ScheduledGuardPoint.RUN_FAILURE,
                        attempted,
                        failure);
                ScheduledGuardDecision decision = result.decision();
                if (decision.action() == ScheduledGuardDecision.Action.CONTINUE
                        || decision.action()
                        == ScheduledGuardDecision.Action.REVOKE_CREDENTIAL_AND_CONTINUE) {
                    // 失败轮次不能伪装成匿名降级成功；凭证撤销只允许走成功返回通道。
                    continue;
                }
                if (!isSafeMachineCode(decision.reasonCode())) {
                    throw pluginFailure("schedule.guard.invalid-result");
                }
                ScheduleExecutionControlException candidate = control(
                        decision.action(),
                        decision.reasonCode(),
                        decision.retryAfterMillis(),
                        result.evidence(),
                        incidentPresentation(decision, result.evidence()));
                if (failureDecision == null) {
                    failureDecision = candidate;
                }
            } catch (Throwable guardFailure) {
                // 每个 failure Guard 独立 best-effort；非致命失败不覆盖主失败，fatal 延后传播。
                fatalFailures.capture(guardFailure);
            }
        }
        return failureDecision;
    }

    private ScheduledGuardResult invokeOne(
            ScheduledExecutionGuard guard,
            String guardId,
            ScheduledGuardPoint point,
            long attempted,
            ScheduledFailure failure) throws ScheduledExecutionException {
        ScheduleCapabilityOwner guardOwner = execution.guardOwner(guardId).orElse(null);
        ScheduleCapabilityOwner policyOwner = execution.credentialPolicyOwner().orElse(null);
        Optional<String> policyState = guardOwner != null && guardOwner.equals(policyOwner)
                ? Optional.ofNullable(taskRow.credentialPolicyStateJson())
                : Optional.empty();
        try (var handle = credential.openHandle()) {
            ScheduledGuardContext context = new ScheduledGuardContext() {
                @Override
                public ScheduledGuardPoint point() {
                    return point;
                }

                @Override
                public long attemptedWorkCount() {
                    return attempted;
                }

                @Override
                public Optional<String> credentialPolicyStateJson() {
                    return policyState;
                }

                @Override
                public ScheduledFailure failure() {
                    return failure;
                }

                @Override
                public ScheduledTaskDefinition task() {
                    return definition;
                }

                @Override
                public ScheduledNetworkRoute route() {
                    return route;
                }

                @Override
                public top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialHandle credential() {
                    return handle;
                }

                @Override
                public ScheduledCancellation cancellation() {
                    return cancellation;
                }
            };
            ScheduledGuardResult result;
            try {
                result = guard.evaluate(context);
            } catch (ScheduledExecutionException scheduled) {
                throw safePluginException(
                        scheduled, "schedule.guard.plugin-failure", credential);
            } catch (Throwable callbackFailure) {
                rethrowFatal(callbackFailure);
                throw pluginFailure("schedule.guard.plugin-failure");
            }
            return validateGuardResult(result);
        }
    }

    private ScheduledCredentialIncidentPresentation incidentPresentation(
            ScheduledGuardDecision decision,
            ScheduledGuardEvidence evidence) {
        if (decision.action() != ScheduledGuardDecision.Action.SUSPEND_POLICY_ACCOUNT
                || taskRow.credentialPolicyOwnerPluginId() == null
                || taskRow.credentialPolicyId() == null
                || taskRow.credentialAccountKey() == null) {
            return ScheduledCredentialIncidentPresentation.empty();
        }
        ScheduleCapabilityOwner policyOwner = execution.credentialPolicyOwner().orElse(null);
        var policy = execution.credentialPolicy().orElse(null);
        if (policyOwner == null || policy == null
                || !taskRow.credentialPolicyOwnerPluginId().equals(
                policyOwner.featurePluginId())
                || !taskRow.credentialPolicyId().equals(plan.credentialPolicyId())) {
            return ScheduledCredentialIncidentPresentation.empty();
        }
        try {
            List<ScheduledTask> affected = new ArrayList<>(
                    store.findByCredentialAccount(
                            taskRow.credentialPolicyOwnerPluginId(),
                            taskRow.credentialPolicyId(),
                            taskRow.credentialAccountKey()));
            if (affected.isEmpty()) {
                affected.add(taskRow);
            }
            affected.sort(Comparator.comparingLong(ScheduledTask::id));
            List<ScheduledCredentialTaskSnapshot> snapshots = affected.stream()
                    .map(row -> new ScheduledCredentialTaskSnapshot(
                            row.id(),
                            row.stateVersion(),
                            row.suspendReason() == ScheduleSuspendReason.CREDENTIAL,
                            row.suspendReason() == ScheduleSuspendReason.POLICY,
                            row.runState() != null || runState.get(row.id()) != null,
                            row.suspendCode(),
                            row.suspendDetailJson(),
                            row.credentialPolicyStateJson()))
                    .toList();
            ScheduledCredentialIncidentPresentation presentation =
                    policy.incidentPresentation(new ScheduledCredentialAccountIncident(
                            taskRow.credentialAccountKey(),
                            decision.reasonCode(),
                            evidence,
                            System.currentTimeMillis(),
                            snapshots));
            return presentation == null
                    ? ScheduledCredentialIncidentPresentation.empty()
                    : presentation;
        } catch (Throwable failure) {
            rethrowFatal(failure);
            return ScheduledCredentialIncidentPresentation.empty();
        }
    }

    private ScheduledGuardResult validateGuardResult(ScheduledGuardResult result)
            throws ScheduledExecutionException {
        if (result == null) {
            throw pluginFailure("schedule.guard.null-result");
        }
        ScheduledGuardDecision decision = result.decision();
        if (decision.action() != ScheduledGuardDecision.Action.CONTINUE
                && (!isSafeMachineCode(decision.reasonCode())
                || credential.containsEcho(decision.reasonCode()))) {
            throw pluginFailure("schedule.guard.invalid-result");
        }
        ScheduledGuardDecision safeDecision = new ScheduledGuardDecision(
                decision.action(), decision.reasonCode(), decision.retryAfterMillis());
        ScheduledGuardEvidence safeEvidence = sanitizeEvidence(
                result.evidence(), credential, "schedule.guard.invalid-result");
        return new ScheduledGuardResult(safeDecision, safeEvidence);
    }
}
