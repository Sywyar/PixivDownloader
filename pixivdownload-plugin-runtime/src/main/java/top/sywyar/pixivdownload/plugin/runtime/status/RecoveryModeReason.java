package top.sywyar.pixivdownload.plugin.runtime.status;

import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginApiRequirement;

import java.util.List;
import java.util.Objects;

/**
 * 单条「必选插件未满足」原因：某个被 {@link RequiredPluginPolicy} 声明为必选的 pluginId 当前不是
 * {@link PluginStatus#STARTED}（缺失 / 禁用 / 启动失败 / 版本不兼容），核心壳因此无法进入正常业务状态。
 *
 * @param pluginId        必选插件 id
 * @param status          该插件当前评估状态（{@link PluginStatus#MISSING_REQUIRED} 表示根本未安装）
 * @param messageKey      面向用户的提示文案 i18n key（取自 {@link RequiredPluginPolicy.RequiredPlugin}，可空）
 * @param requiredVersion 必选策略要求的兼容版本范围（用于向用户显示「所需版本」）
 * @param messages        评估器给出的可读诊断说明（如「requires 1.1，核心仅 1.0」）
 */
public record RecoveryModeReason(
        String pluginId,
        PluginStatus status,
        String messageKey,
        PluginApiRequirement requiredVersion,
        List<String> messages) {

    public RecoveryModeReason {
        Objects.requireNonNull(pluginId, "pluginId");
        Objects.requireNonNull(status, "status");
        requiredVersion = requiredVersion != null ? requiredVersion : PluginApiRequirement.unspecified();
        messages = messages != null ? List.copyOf(messages) : List.of();
    }
}
