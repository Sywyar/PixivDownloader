package top.sywyar.pixivdownload.plugin.runtime.status;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 插件状态报告（不可变）：全部插件 id 的状态诊断快照，供后端服务查询。本报告只描述事实，不据此改变任何运行行为。
 *
 * @param diagnostics 各插件的状态诊断（按评估顺序：先各已安装插件，后必选策略要求但未安装的 pluginId）
 */
public record PluginStatusReport(List<PluginDiagnostic> diagnostics) {

    public PluginStatusReport {
        diagnostics = diagnostics != null ? List.copyOf(diagnostics) : List.of();
    }

    /** 空报告。 */
    public static PluginStatusReport empty() {
        return new PluginStatusReport(List.of());
    }

    /** 按 id 查诊断（{@code pluginId} 为 {@code null} 时返回空，不抛出）。 */
    public Optional<PluginDiagnostic> byId(String pluginId) {
        if (pluginId == null) {
            return Optional.empty();
        }
        return diagnostics.stream().filter(d -> Objects.equals(d.id(), pluginId)).findFirst();
    }

    /** 处于给定状态的全部诊断。 */
    public List<PluginDiagnostic> withStatus(PluginStatus status) {
        return diagnostics.stream().filter(d -> d.status() == status).toList();
    }

    /** 是否存在「被要求但不可用」（必选 / 依赖未满足）的诊断。 */
    public boolean hasUnmetRequirement() {
        return diagnostics.stream().anyMatch(d -> d.status().isUnmetRequirement());
    }
}
