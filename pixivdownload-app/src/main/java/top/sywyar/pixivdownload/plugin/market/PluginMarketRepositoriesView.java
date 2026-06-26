package top.sywyar.pixivdownload.plugin.market;

import java.util.List;

/**
 * {@code GET /api/plugin-market/repositories} 响应：受信 catalog / 市场主开关状态、当前核心 API 版本、默认仓库 id，
 * 以及全部已配置仓库的只读投影。主开关关闭时 {@code enabled=false} 但仍返回仓库列表（供管理员查看 / 决定是否开启）。
 *
 * @param enabled             受信 catalog / 市场主开关是否开启（{@code plugin-catalog.enabled}）
 * @param coreApiVersion      当前核心插件 API 版本（semver；供页面做兼容标记参考）
 * @param defaultRepositoryId 默认仓库 id（无可用默认仓库时为 {@code null}）
 * @param repositories        全部已配置仓库（含禁用项；官方在首、兼容仓库次之、自定义按配置顺序）
 */
public record PluginMarketRepositoriesView(
        boolean enabled,
        String coreApiVersion,
        String defaultRepositoryId,
        List<PluginMarketRepositoryView> repositories) {

    public PluginMarketRepositoriesView {
        repositories = repositories != null ? List.copyOf(repositories) : List.of();
    }
}
