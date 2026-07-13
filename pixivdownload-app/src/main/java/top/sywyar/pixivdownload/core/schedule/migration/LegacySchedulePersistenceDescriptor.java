package top.sywyar.pixivdownload.core.schedule.migration;

import java.util.Set;

/**
 * 旧来源迁移前由独立元数据 provider 声明的纯值持久化契约。
 *
 * <p>除定义和作品类型外，凭证 policy id 也必须在 owner bundle 中预先盖章；迁移 adapter
 * 不能借单条结果把旧 secret 绑定到未声明的策略。
 */
public record LegacySchedulePersistenceDescriptor(
        String sourceType,
        String definitionSchema,
        int definitionVersion,
        Set<String> possibleWorkTypes,
        Set<String> credentialPolicyIds
) {
    public LegacySchedulePersistenceDescriptor {
        sourceType = requireText(sourceType, "source type");
        definitionSchema = requireText(definitionSchema, "definition schema");
        if (definitionVersion <= 0) {
            throw new IllegalArgumentException("definition version must be positive");
        }
        possibleWorkTypes = possibleWorkTypes == null ? Set.of() : Set.copyOf(possibleWorkTypes);
        if (possibleWorkTypes.isEmpty()) {
            throw new IllegalArgumentException("possible work types must not be empty");
        }
        for (String workType : possibleWorkTypes) {
            requireText(workType, "possible work type");
        }
        credentialPolicyIds = credentialPolicyIds == null ? Set.of() : Set.copyOf(credentialPolicyIds);
        for (String policyId : credentialPolicyIds) {
            requireText(policyId, "credential policy id");
        }
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value.trim();
    }
}
