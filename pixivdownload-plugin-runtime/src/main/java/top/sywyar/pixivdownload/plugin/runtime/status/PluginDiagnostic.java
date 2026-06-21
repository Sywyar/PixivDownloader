package top.sywyar.pixivdownload.plugin.runtime.status;

import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;

import java.util.List;
import java.util.Objects;

/**
 * 单个插件 id 的状态诊断：评估得到的 {@link PluginStatus} + 触发该状态的可读说明。
 *
 * @param id          插件 id
 * @param status      评估状态
 * @param descriptor  插件描述符（必选策略要求一个未安装的 pluginId 时为 {@code null}——此时只有要求、没有描述符）
 * @param requiredByPolicy 该 id 是否被 {@link RequiredPluginPolicy} 声明为必选
 * @param messages    诊断说明（如「requires 1.1，核心仅 1.0」「缺少依赖 download-workbench」），按发生顺序
 */
public record PluginDiagnostic(
        String id,
        PluginStatus status,
        PluginDescriptor descriptor,
        boolean requiredByPolicy,
        List<String> messages) {

    public PluginDiagnostic {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(status, "status");
        messages = messages != null ? List.copyOf(messages) : List.of();
    }
}
