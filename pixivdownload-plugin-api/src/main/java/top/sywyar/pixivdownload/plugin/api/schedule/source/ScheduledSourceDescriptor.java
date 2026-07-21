package top.sywyar.pixivdownload.plugin.api.schedule.source;

import java.util.Set;

/**
 * 插件声明的计划任务来源描述符。它只承载纯数据；宿主从注册条目盖上 owner、package 与 generation，
 * 不信任插件自报归属。
 */
public record ScheduledSourceDescriptor(
        String sourceType,
        Set<String> legacyAliases,
        String definitionSchema,
        int definitionVersion,
        ScheduledSourcePresentation presentation,
        Set<String> acquisitionModes,
        Set<String> possibleWorkTypes,
        Set<String> credentialPolicyIds,
        Set<String> guardIds,
        ScheduledSourceFrontendContribution frontend
) {

    public ScheduledSourceDescriptor {
        sourceType = requireText(sourceType, "source type");
        definitionSchema = requireText(definitionSchema, "definition schema");
        if (definitionVersion <= 0) {
            throw new IllegalArgumentException("definition version must be positive");
        }
        if (presentation == null) {
            throw new IllegalArgumentException("source presentation must not be null");
        }
        legacyAliases = copy(legacyAliases);
        acquisitionModes = copy(acquisitionModes);
        possibleWorkTypes = copy(possibleWorkTypes);
        credentialPolicyIds = copy(credentialPolicyIds);
        guardIds = copy(guardIds);
        if (possibleWorkTypes.isEmpty()) {
            throw new IllegalArgumentException("source must declare at least one possible work type");
        }
    }

    private static Set<String> copy(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        for (String value : values) {
            requireText(value, "descriptor value");
        }
        return Set.copyOf(values);
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value.trim();
    }
}
