package top.sywyar.pixivdownload.plugin.catalog;

import java.util.List;
import java.util.Optional;

/**
 * 受信插件 catalog 清单（市场元数据，<b>纯 JDK record、不入 {@code plugin-api}</b>）：服务端配置的受信目录返回的顶层结构。
 * {@code schemaVersion} 仅作前向兼容标记；未在本模型建模的字段（如 author / tags / downloadCount / rating /
 * updateAvailable）由解析器<b>忽略</b>，不夹带进当前最小后端闭环。
 *
 * @param schemaVersion 清单 schema 版本（前向兼容标记，可空）
 * @param generatedTime 清单生成时间（ISO-8601 字符串，可空；仅作新鲜度 / 诊断展示）
 * @param entries       插件条目列表
 */
public record PluginCatalogManifest(String schemaVersion, String generatedTime, List<PluginCatalogEntry> entries) {

    public PluginCatalogManifest {
        entries = entries != null ? List.copyOf(entries) : List.of();
    }

    /** 空清单（catalog 未启用 / 无条目）。 */
    public static PluginCatalogManifest empty() {
        return new PluginCatalogManifest(null, null, List.of());
    }

    /** 按插件 id 精确查找条目。 */
    public Optional<PluginCatalogEntry> findEntry(String pluginId) {
        if (pluginId == null) {
            return Optional.empty();
        }
        return entries.stream().filter(entry -> pluginId.equals(entry.pluginId())).findFirst();
    }
}
