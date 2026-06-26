package top.sywyar.pixivdownload.plugin.catalog;

import java.util.List;
import java.util.Optional;

/**
 * 受信 catalog 中的一个插件条目（市场元数据，<b>纯 JDK record、不入 {@code plugin-api}</b>）：一个插件 id 对应一组可安装
 * 版本包。{@code displayNameKey} / {@code descriptionKey} 是 i18n key（官方插件的展示文案由前端在对应 namespace 解析，
 * 后端只透传、不解析），与项目「展示名走 i18n key」约定一致。
 *
 * @param pluginId       插件 id
 * @param displayNameKey 展示名 i18n key（可空；后端不解析）
 * @param descriptionKey 简介 i18n key（可空；后端不解析）
 * @param packages       可安装版本包列表
 */
public record PluginCatalogEntry(
        String pluginId,
        String displayNameKey,
        String descriptionKey,
        List<PluginCatalogPackage> packages) {

    public PluginCatalogEntry {
        packages = packages != null ? List.copyOf(packages) : List.of();
    }

    /** 按版本号精确查找一个可安装包。 */
    public Optional<PluginCatalogPackage> findPackage(String version) {
        if (version == null) {
            return Optional.empty();
        }
        return packages.stream().filter(pkg -> version.equals(pkg.version())).findFirst();
    }
}
