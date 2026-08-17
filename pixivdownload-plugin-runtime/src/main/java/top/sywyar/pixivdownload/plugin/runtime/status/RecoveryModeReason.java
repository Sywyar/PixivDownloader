package top.sywyar.pixivdownload.plugin.runtime.status;

import top.sywyar.pixivdownload.plugin.runtime.descriptor.VersionRequirement;

import java.util.List;
import java.util.Objects;

/**
 * 单条恢复模式原因：某个被 {@link RequiredPluginPolicy} 声明为必选的 pluginId 当前不是
 * {@link PluginStatus#STARTED}，或插件在启动阶段失败，核心壳因此只开放诊断与修复入口。
 *
 * @param pluginId        触发恢复模式的插件 id / 失败来源
 * @param status          该插件当前评估状态（{@link PluginStatus#MISSING_REQUIRED} 表示根本未安装）
 * @param messageKey      面向用户的提示文案 i18n key（可空）
 * @param requiredVersion 必选策略要求的兼容版本范围；非必选失败可不指定
 * @param messages        评估器给出的可读诊断说明（如「requires 1.1，核心仅 1.0」）
 */
public record RecoveryModeReason(
        String pluginId,
        PluginStatus status,
        String messageKey,
        VersionRequirement requiredVersion,
        List<String> messages) {

    public RecoveryModeReason {
        Objects.requireNonNull(pluginId, "pluginId");
        Objects.requireNonNull(status, "status");
        requiredVersion = requiredVersion != null ? requiredVersion : VersionRequirement.unspecified();
        messages = messages != null ? List.copyOf(messages) : List.of();
    }
}
