package top.sywyar.pixivdownload.plugin.catalog;

import java.util.List;

/**
 * GET /api/plugins/catalog 响应：受信 catalog 是否启用，及可安装条目摘要。未启用时 {@code enabled=false} + 空列表
 * （200，正常「功能未开」状态而非错误）。
 *
 * @param enabled 受信 catalog 是否启用
 * @param entries 可安装条目摘要（未启用时为空）
 */
public record PluginCatalogView(boolean enabled, List<PluginCatalogEntryView> entries) {

    /** 未启用视图：enabled=false + 空列表。 */
    public static PluginCatalogView disabled() {
        return new PluginCatalogView(false, List.of());
    }
}
