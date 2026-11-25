package top.sywyar.pixivdownload.plugin.runtime.status;

import top.sywyar.pixivdownload.plugin.api.web.HttpApiNullable;

import top.sywyar.pixivdownload.plugin.runtime.descriptor.VersionRequirement;

import java.util.List;
import java.util.Objects;

/**
 * 核心壳进入恢复模式的原因，包括必选插件未就绪、数据库不可用或插件安装事务恢复失败。
 *
 * @param pluginId        触发恢复模式的插件 id / 失败来源
 * @param status          该插件当前评估状态（必选插件缺失或必需依赖未满足时可为 {@link PluginStatus#MISSING_REQUIRED}）
 * @param missingDependencyId 已确认缺包的必需依赖 ID，否则为空
 * @param messageKey      面向用户的提示文案 i18n key（可空）
 * @param requiredVersion 必选策略要求的兼容版本范围；非必选失败可不指定
 * @param messages        评估器给出的可读诊断说明（如「requires 1.1，核心仅 1.0」）
 */
public record RecoveryModeReason(
        String pluginId,
        PluginStatus status,
        @HttpApiNullable String messageKey,
        VersionRequirement requiredVersion,
        List<String> messages,
        @HttpApiNullable String missingDependencyId,
        java.util.Map<String, String> blockingProviders) {

    public RecoveryModeReason(String pluginId, PluginStatus status, String messageKey, VersionRequirement requiredVersion,
                              List<String> messages, String missingDependencyId) {
        this(pluginId, status, messageKey, requiredVersion, messages, missingDependencyId, java.util.Map.of());
    }

    public RecoveryModeReason(String pluginId, PluginStatus status, String messageKey,
                              VersionRequirement requiredVersion, List<String> messages) {
        this(pluginId, status, messageKey, requiredVersion, messages, null);
    }

    public Object[] messageArguments() {
        return missingDependencyId == null ? new Object[0] : new Object[]{missingDependencyId};
    }

    public RecoveryModeReason {
        Objects.requireNonNull(pluginId, "pluginId");
        Objects.requireNonNull(status, "status");
        requiredVersion = requiredVersion != null ? requiredVersion : VersionRequirement.unspecified();
        blockingProviders = java.util.Map.copyOf(blockingProviders);
        messages = messages != null ? List.copyOf(messages) : List.of();
    }
}
