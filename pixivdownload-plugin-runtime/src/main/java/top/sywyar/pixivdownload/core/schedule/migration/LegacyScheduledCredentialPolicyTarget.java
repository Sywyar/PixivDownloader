package top.sywyar.pixivdownload.core.schedule.migration;

/** 宿主在 capability reservation 时盖章的旧任务凭证策略目标。 */
public record LegacyScheduledCredentialPolicyTarget(
        String policyId,
        String policyOwnerPluginId
) {
    public LegacyScheduledCredentialPolicyTarget {
        policyId = requireText(policyId, "credential policy id");
        policyOwnerPluginId = requireText(policyOwnerPluginId, "credential policy owner");
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value.trim();
    }
}
