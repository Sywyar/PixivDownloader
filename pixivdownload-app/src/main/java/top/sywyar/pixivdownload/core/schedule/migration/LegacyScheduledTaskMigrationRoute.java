package top.sywyar.pixivdownload.core.schedule.migration;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * 宿主从已验证 owner bundle 盖章的旧来源迁移目标。
 *
 * <p>完整的新来源 descriptor 或独立 persistence descriptor 在场时，同时固定定义 schema/version、允许的
 * 作品类型与宿主盖章的凭证 policy 目标；只有 1.0 身份 provider 而没有持久化规范时，本 route
 * 只用于把原数据置为明确迁移错误。
 */
public record LegacyScheduledTaskMigrationRoute(
        String canonicalSourceType,
        String definitionSchema,
        Integer definitionVersion,
        Set<String> allowedWorkTypes,
        Set<LegacyScheduledCredentialPolicyTarget> allowedCredentialPolicies
) {
    public LegacyScheduledTaskMigrationRoute {
        canonicalSourceType = requireText(canonicalSourceType, "canonical source type");
        allowedWorkTypes = allowedWorkTypes == null ? Set.of() : Set.copyOf(allowedWorkTypes);
        allowedCredentialPolicies = allowedCredentialPolicies == null
                ? Set.of() : Set.copyOf(allowedCredentialPolicies);
        boolean specUnavailable = definitionSchema == null && definitionVersion == null;
        boolean descriptorBound = definitionSchema != null && !definitionSchema.isBlank()
                && definitionVersion != null && definitionVersion > 0;
        if (!specUnavailable && !descriptorBound) {
            throw new IllegalArgumentException(
                    "definition schema and version must either both be absent or both be valid");
        }
        if (specUnavailable && (!allowedWorkTypes.isEmpty() || !allowedCredentialPolicies.isEmpty())) {
            throw new IllegalArgumentException(
                    "route without persistence spec must not claim work types or credential policies");
        }
        if (descriptorBound) {
            definitionSchema = definitionSchema.trim();
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
    }

    public static LegacyScheduledTaskMigrationRoute specUnavailable(String canonicalSourceType) {
        return new LegacyScheduledTaskMigrationRoute(
                canonicalSourceType, null, null, Set.of(), Set.of());
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

    public boolean hasPersistenceContract() {
        return definitionSchema != null;
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
