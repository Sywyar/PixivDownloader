package top.sywyar.pixivdownload.plugin.catalog;

import java.util.List;

/**
 * 受信 catalog 中一个插件条目的对外摘要（GET /api/plugins/catalog 用）。{@code displayNameKey} / {@code descriptionKey}
 * 为 i18n key、由前端解析（后端透传不解析）。
 *
 * @param pluginId       插件 id
 * @param displayNameKey 展示名 i18n key（可空）
 * @param descriptionKey 简介 i18n key（可空）
 * @param packages       可安装版本包摘要
 */
public record PluginCatalogEntryView(
        String pluginId,
        String displayNameKey,
        String descriptionKey,
        List<PluginCatalogPackageView> packages) {

    static PluginCatalogEntryView from(PluginCatalogEntry entry) {
        return new PluginCatalogEntryView(
                entry.pluginId(),
                entry.displayNameKey(),
                entry.descriptionKey(),
                entry.packages().stream().map(PluginCatalogPackageView::from).toList());
    }
}
