package top.sywyar.pixivdownload.plugin.api.schedule.source;

import java.util.Set;

/**
 * 插件声明的计划任务来源描述符。它只承载纯数据；宿主从注册条目盖上 owner、package 与 generation，
 * 不信任插件自报归属。
 *
 * @param sourceType 来源类型
 * @param legacyAliases 兼容旧任务定义的来源别名集合
 * @param definitionSchema 任务定义的格式标识
 * @param definitionVersion 定义版本
 * @param presentation 展示信息
 * @param acquisitionModes 来源支持的获取方式集合
 * @param possibleWorkTypes 来源可能发现的非空作品类型集合
 * @param credentialPolicyIds 凭证策略标识集合
 * @param guardIds 守卫标识集合
 * @param frontend 来源提供的前端模块声明；没有时为 null
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

    /**
     * 创建 {@code ScheduledSourceDescriptor} 实例。
     *
     * @param sourceType 来源类型
     * @param legacyAliases {@code legacyAliases} 对应的值
     * @param definitionSchema 定义模式定义
     * @param definitionVersion 定义版本
     * @param presentation 展示信息
     * @param acquisitionModes {@code acquisitionModes} 对应的值
     * @param possibleWorkTypes 可能项作品类型集合
     * @param credentialPolicyIds 凭证策略标识集合
     * @param guardIds 守卫标识集合
     * @param frontend 前端
     */
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
