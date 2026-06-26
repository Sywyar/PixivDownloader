package top.sywyar.pixivdownload.plugin.market;

import top.sywyar.pixivdownload.plugin.api.PluginApiVersion;

import java.util.List;

/**
 * {@code GET /api/plugin-market/catalog} 响应：某个仓库的可安装条目摘要 + 派生的分类计数 + 当前核心 API 版本。主开关关闭
 * 时返回 {@link #disabled()}（{@code enabled=false} + 空，200 正常「功能未开」而非错误）；启用但仓库未知 / 禁用 / 清单失败由
 * 控制器的稳定错误响应承载。
 *
 * @param repositoryId   该 catalog 所属仓库 id（禁用视图为 {@code null}）
 * @param enabled        受信 catalog / 市场是否启用且该仓库可用
 * @param coreApiVersion 当前核心插件 API 版本（semver；版本包兼容标记参考）
 * @param categories     分类计数（聚合 {@code all} 在首，其余为存在条目的分类）
 * @param entries        可安装条目摘要（含市场元数据 + 版本列表）
 */
public record PluginMarketView(
        String repositoryId,
        boolean enabled,
        String coreApiVersion,
        List<PluginMarketCategoryCount> categories,
        List<PluginMarketEntryView> entries) {

    public PluginMarketView {
        categories = categories != null ? List.copyOf(categories) : List.of();
        entries = entries != null ? List.copyOf(entries) : List.of();
    }

    /** 主开关关闭视图：enabled=false + 空分类 / 条目（仍带当前核心 API 版本，供页面渲染兼容提示）。 */
    public static PluginMarketView disabled() {
        return new PluginMarketView(null, false, PluginApiVersion.VERSION, List.of(), List.of());
    }
}
