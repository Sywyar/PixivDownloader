package top.sywyar.pixivdownload.plugin.catalog;

import top.sywyar.pixivdownload.plugin.catalog.model.PluginCatalogMarketMeta;

import java.util.List;
import java.util.Optional;

/**
 * 受信 catalog 中的一个插件条目（市场元数据，<b>纯 JDK record、不入 {@code plugin-api}</b>）：一个插件 id 对应一组可安装
 * 版本条目。{@code displayNamespace} / {@code displayNameKey} / {@code descriptionKey} 是插件身份展示 canonical 元数据
 * （官方插件从包描述符 {@code pixiv.*} 派生，市场字面文案由发布脚本按这组 key 解析），与项目「展示名走 i18n key」约定一致。
 * {@code market} 为可空的市场展示元数据（分类 / 作者 / 标签 / 评分 / 下载量 / 图标 token 等），仅供检索 / 排序 / 展示，
 * 不参与安装安全决策。
 *
 * @param pluginId       插件 id
 * @param displayNamespace 展示名 / 简介 i18n namespace（可空；后端不解析）
 * @param displayNameKey 展示名 i18n key（纯 key，可空；后端不解析）
 * @param descriptionKey 简介 i18n key（纯 key，可空；后端不解析）
 * @param market         市场展示元数据（可空；仅展示 / 检索 / 排序）
 * @param packages       可安装版本条目列表
 */
public record PluginCatalogEntry(
        String pluginId,
        String displayNamespace,
        String displayNameKey,
        String descriptionKey,
        PluginCatalogMarketMeta market,
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
