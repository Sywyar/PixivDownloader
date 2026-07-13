package top.sywyar.pixivdownload.schedule.definition;

import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionPlan;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardBinding;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceDescriptor;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** 保存期与运行期共用的纯执行计划约束。 */
public final class ScheduleExecutionPlanGate {

    public static final int MAX_IN_FLIGHT = 256;
    public static final int MAX_WORK_BATCH_SIZE = 100_000;

    private ScheduleExecutionPlanGate() {
    }

    public static ScheduledExecutionPlan validate(
            ScheduledSourceDescriptor descriptor,
            ScheduledExecutionPlan plan) {
        Objects.requireNonNull(descriptor, "schedule source descriptor");
        if (plan == null) {
            throw new Violation(Reason.NULL_PLAN, "schedule execution plan must not be null");
        }
        if (plan.maxInFlight() > MAX_IN_FLIGHT) {
            throw new Violation(
                    Reason.MAX_IN_FLIGHT_TOO_LARGE,
                    "schedule execution plan exceeds the max in-flight limit");
        }

        Set<String> guardIds = new LinkedHashSet<>();
        for (ScheduledGuardBinding binding : plan.guards()) {
            if (binding == null) {
                throw new Violation(
                        Reason.UNDECLARED_GUARD,
                        "schedule execution plan contains a null guard binding");
            }
            if (binding.workBatchSize() > MAX_WORK_BATCH_SIZE) {
                throw new Violation(
                        Reason.WORK_BATCH_TOO_LARGE,
                        "schedule execution plan exceeds the work-batch limit");
            }
            if (!guardIds.add(binding.guardId())) {
                throw new Violation(
                        Reason.DUPLICATE_GUARD,
                        "schedule execution plan contains a duplicate guard binding");
            }
            if (!descriptor.guardIds().contains(binding.guardId())) {
                throw new Violation(
                        Reason.UNDECLARED_GUARD,
                        "schedule execution plan requests an undeclared guard");
            }
        }
        if (!descriptor.possibleWorkTypes().containsAll(plan.requiredWorkTypes())) {
            throw new Violation(
                    Reason.UNDECLARED_WORK_TYPE,
                    "schedule execution plan requests an undeclared work type");
        }
        if (plan.credentialPolicyId() != null
                && !descriptor.credentialPolicyIds().contains(plan.credentialPolicyId())) {
            throw new Violation(
                    Reason.UNDECLARED_CREDENTIAL_POLICY,
                    "schedule execution plan requests an undeclared credential policy");
        }
        return plan;
    }

    public enum Reason {
        NULL_PLAN,
        MAX_IN_FLIGHT_TOO_LARGE,
        WORK_BATCH_TOO_LARGE,
        DUPLICATE_GUARD,
        UNDECLARED_WORK_TYPE,
        UNDECLARED_CREDENTIAL_POLICY,
        UNDECLARED_GUARD
    }

    public static final class Violation extends IllegalArgumentException {

        private final Reason reason;

        private Violation(Reason reason, String message) {
            super(message);
            this.reason = Objects.requireNonNull(reason, "reason");
        }

        public Reason reason() {
            return reason;
        }
    }
}
