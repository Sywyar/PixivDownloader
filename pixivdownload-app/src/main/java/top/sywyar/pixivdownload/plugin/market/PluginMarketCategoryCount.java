package top.sywyar.pixivdownload.plugin.market;

/**
 * 市场分类计数（{@link PluginMarketView} 用）：一个分类 id 及其在当前仓库 catalog 中的条目数，供页面侧栏分类筛选显示数量。
 * 聚合项 {@code all} 的计数为总条目数。分类 id 已经 {@link top.sywyar.pixivdownload.plugin.catalog.model.PluginCatalogCategory}
 * 归一化（未知原始分类回退到实用工具）。
 *
 * @param category 分类 id（{@code all} 为聚合，其余为已归一化的分类）
 * @param count    该分类下的条目数
 */
public record PluginMarketCategoryCount(String category, int count) {
}
