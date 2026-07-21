package top.sywyar.pixivdownload.core.schedule.migration;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * 宿主从已验证 owner bundle 盖章的旧来源迁移目标。
 *
 * <p>当前来源 descriptor 与可选 persistence descriptor 共同固定定义 schema/version、允许的作品类型与
 * 宿主盖章的凭证 policy 目标。旧来源运行时 provider 已退出，迁移路由必须始终绑定完整持久化契约。
 */
public record LegacyScheduledTaskMigrationRoute(
        String canonicalSourceType,
        String definitionSchema,
        int definitionVersion,
        Set<String> allowedWorkTypes,
        Set<LegacyScheduledCredentialPolicyTarget> allowedCredentialPolicies
) {
    public LegacyScheduledTaskMigrationRoute {
        canonicalSourceType = requireText(canonicalSourceType, "canonical source type");
        definitionSchema = requireText(definitionSchema, "definition schema");
        if (definitionVersion <= 0) {
            throw new IllegalArgumentException("definition version must be positive");
        }
        allowedWorkTypes = allowedWorkTypes == null ? Set.of() : Set.copyOf(allowedWorkTypes);
        allowedCredentialPolicies = allowedCredentialPolicies == null
                ? Set.of() : Set.copyOf(allowedCredentialPolicies);
        if (allowedWorkTypes.isEmpty()) {
            throw new IllegalArgumentException("persistence route must allow at least one work type");
        }
        for (String workType : allowedWorkTypes) {
            requireText(workType, "allowed work type");
        }
        Set<String> policyIds = new HashSet<>();
        for (LegacyScheduledCredentialPolicyTarget policy : allowedCredentialPolicies) {
            if (!policyIds.add(policy.policyId())) {
                throw new IllegalArgumentException(
                        "credential policy target ids must be unique: " + policy.policyId());
            }
        }
    }

    public static LegacyScheduledTaskMigrationRoute descriptorBound(
            String canonicalSourceType,
            String definitionSchema,
            int definitionVersion,
            Set<String> allowedWorkTypes,
            Set<LegacyScheduledCredentialPolicyTarget> allowedCredentialPolicies) {
        return new LegacyScheduledTaskMigrationRoute(
                canonicalSourceType, definitionSchema, definitionVersion,
                allowedWorkTypes, allowedCredentialPolicies);
    }

    public Optional<LegacyScheduledCredentialPolicyTarget> credentialPolicyTarget(String policyId) {
        if (policyId == null) {
            return Optional.empty();
        }
        return allowedCredentialPolicies.stream()
                .filter(target -> target.policyId().equals(policyId))
                .findFirst();
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value.trim();
    }
}
