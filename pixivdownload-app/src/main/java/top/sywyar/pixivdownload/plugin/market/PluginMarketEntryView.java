package top.sywyar.pixivdownload.plugin.market;

import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogEntry;

import java.util.List;

/**
 * 市场视图中一个插件条目的对外投影（卡片摘要 + 详情共用）。{@code displayNameKey} / {@code descriptionKey} 是 i18n key
 * （前端在对应 namespace 解析；插件已安装时其 i18n 包才解析得出，未安装浏览用 {@link PluginMarketMetaView} 的字面文本兜底）。
 * {@code market} 为净化后的市场展示元数据（可空），{@code packages} 为可安装版本列表（含版本历史 / 兼容标记）。
 *
 * @param pluginId       插件 id
 * @param displayNameKey 展示名 i18n key（可空）
 * @param descriptionKey 简介 i18n key（可空）
 * @param latestVersion  最新可用版本（取市场元数据声明、否则取首个版本包；可空）
 * @param market         净化后的市场展示元数据（可空）
 * @param packages       可安装版本包列表
 */
public record PluginMarketEntryView(
        String pluginId,
        String displayNameKey,
        String descriptionKey,
        String latestVersion,
        PluginMarketMetaView market,
        List<PluginMarketPackageView> packages) {

    public PluginMarketEntryView {
        packages = packages != null ? List.copyOf(packages) : List.of();
    }

    static PluginMarketEntryView from(PluginCatalogEntry entry) {
        List<PluginMarketPackageView> packages = entry.packages().stream()
                .map(PluginMarketPackageView::from)
                .toList();
        PluginMarketMetaView market = PluginMarketMetaView.from(entry.market());
        return new PluginMarketEntryView(
                entry.pluginId(),
                entry.displayNameKey(),
                entry.descriptionKey(),
                resolveLatestVersion(market, packages),
                market,
                packages);
    }

    /** 最新版本：优先市场元数据声明的 {@code latestVersion}，否则取首个版本包的版本（清单约定新版本在前），都无则 {@code null}。 */
    private static String resolveLatestVersion(PluginMarketMetaView market, List<PluginMarketPackageView> packages) {
        if (market != null && market.latestVersion() != null && !market.latestVersion().isBlank()) {
            return market.latestVersion();
        }
        return packages.isEmpty() ? null : packages.get(0).version();
    }
}
