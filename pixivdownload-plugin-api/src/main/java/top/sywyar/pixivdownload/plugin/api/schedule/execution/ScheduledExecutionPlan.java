package top.sywyar.pixivdownload.plugin.api.schedule.execution;

import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialRequirement;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardBinding;
import top.sywyar.pixivdownload.plugin.api.schedule.network.ScheduledNetworkRoute;

import java.util.List;
import java.util.Set;

/**
 * 来源执行器在任何凭证读取或网络访问前返回的纯数据执行计划。宿主据此一次解析并租用全部所需能力。
 */
public record ScheduledExecutionPlan(
        Set<String> requiredWorkTypes,
        String credentialPolicyId,
        ScheduledCredentialRequirement credentialRequirement,
        boolean anonymousFallbackAllowed,
        List<ScheduledGuardBinding> guards,
        String checkpointSchema,
        int checkpointVersion,
        int maxInFlight,
        long politeDelayMillis,
        ScheduledNetworkRoute sourceDefaultRoute
) {

    public ScheduledExecutionPlan {
        if (requiredWorkTypes == null || requiredWorkTypes.isEmpty()) {
            throw new IllegalArgumentException("execution plan must require at least one work type");
        }
        requiredWorkTypes = Set.copyOf(requiredWorkTypes);
        for (String workType : requiredWorkTypes) {
            if (workType == null || workType.isBlank()) {
                throw new IllegalArgumentException("required work type must not be blank");
            }
        }
        if (credentialRequirement == null) {
            throw new IllegalArgumentException("credential requirement must not be null");
        }
        credentialPolicyId = normalize(credentialPolicyId);
        if (credentialRequirement == ScheduledCredentialRequirement.NONE && credentialPolicyId != null) {
            throw new IllegalArgumentException("credential-free plan must not reference a credential policy");
        }
        if (credentialRequirement != ScheduledCredentialRequirement.NONE && credentialPolicyId == null) {
            throw new IllegalArgumentException("credential plan must reference a credential policy");
        }
        if (anonymousFallbackAllowed && credentialRequirement == ScheduledCredentialRequirement.NONE) {
            throw new IllegalArgumentException("credential-free plan does not need anonymous fallback");
        }
        guards = guards == null ? List.of() : List.copyOf(guards);
        checkpointSchema = normalize(checkpointSchema);
        if ((checkpointSchema == null) != (checkpointVersion == 0)) {
            throw new IllegalArgumentException("checkpoint schema and version must either both be present or both be absent");
        }
        if (checkpointVersion < 0) {
            throw new IllegalArgumentException("checkpoint version must not be negative");
        }
        if (maxInFlight <= 0) {
            throw new IllegalArgumentException("max in-flight work must be positive");
        }
        if (politeDelayMillis < 0) {
            throw new IllegalArgumentException("polite delay must not be negative");
        }
        sourceDefaultRoute = sourceDefaultRoute == null
                ? ScheduledNetworkRoute.inherit()
                : sourceDefaultRoute;
    }

    /**
     * 兼容早期 1.0 草案插件的构造入口。未声明来源默认路由时继续继承宿主全局路由。
     */
    public ScheduledExecutionPlan(
            Set<String> requiredWorkTypes,
            String credentialPolicyId,
            ScheduledCredentialRequirement credentialRequirement,
            boolean anonymousFallbackAllowed,
            List<ScheduledGuardBinding> guards,
            String checkpointSchema,
            int checkpointVersion,
            int maxInFlight,
            long politeDelayMillis) {
        this(requiredWorkTypes, credentialPolicyId, credentialRequirement,
                anonymousFallbackAllowed, guards, checkpointSchema, checkpointVersion,
                maxInFlight, politeDelayMillis, ScheduledNetworkRoute.inherit());
    }

    public static ScheduledExecutionPlan credentialFree(Set<String> workTypes) {
        return new ScheduledExecutionPlan(
                workTypes,
                null,
                ScheduledCredentialRequirement.NONE,
                false,
                List.of(),
                null,
                0,
                1,
                0L,
                ScheduledNetworkRoute.inherit());
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
