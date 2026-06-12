package top.sywyar.pixivdownload.gallery;

import top.sywyar.pixivdownload.plugin.api.AccessLevel;
import top.sywyar.pixivdownload.plugin.api.CoreColumnUsage;
import top.sywyar.pixivdownload.plugin.api.I18nContribution;
import top.sywyar.pixivdownload.plugin.api.NavigationContribution;
import top.sywyar.pixivdownload.plugin.api.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.PluginKind;
import top.sywyar.pixivdownload.plugin.api.StaticResourceContribution;
import top.sywyar.pixivdownload.plugin.api.WebRouteContribution;

import java.util.List;
import java.util.Set;

/**
 * 画廊插件：画廊 / 作品详情 / 精选集 / 系列四个页面、{@code /api/gallery/**} 与批量管理入口。
 * <p>
 * 与 stats / duplicate 两个管理员专属插件不同，画廊全部路由是 {@code GUEST_READ}：
 * 按 monitor 语义保护（solo 会话用户或 multi 登录管理员全量可用），同时受邀访客可只读访问
 * （仅 GET/HEAD，仍需经 invite session 校验，落在 AuthFilter 的 GUEST_ALLOWED 清单）——
 * 该双重语义由路由镜像测试逐条守护。无私有表（artworks 系按卸载投影测试归 core）。
 * <p>
 * 核心列使用声明只覆盖包内直接 SQL 仓库 {@link GalleryRepository} 触及的表列；
 * 经核心接口（WorkQueryService / WorkMetadataRepository 等）读写的列由核心自行负责，
 * 不在此声明。现有查询全部由核心 schema 既有索引承载，无需向核心表补新索引
 * （{@code SchemaContribution.indexes()} 维持显式拒绝，形态决策继续推迟）。
 */
public class GalleryPlugin implements PixivFeaturePlugin {

    private static final String ID = "gallery";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "画廊";
    }

    @Override
    public PluginKind kind() {
        return PluginKind.FEATURE;
    }

    @Override
    public List<WebRouteContribution> routes() {
        // 与 AuthFilter 现行硬编码逐条对应：每条路径同时存在于 monitor 清单与
        // GUEST_ALLOWED 清单，即 GUEST_READ；方法不限（访客仅 GET/HEAD 的收窄由访问级别语义承载）。
        return List.of(
                new WebRouteContribution("/pixiv-gallery.html", AccessLevel.GUEST_READ, Set.of(), false),
                new WebRouteContribution("/pixiv-artwork.html", AccessLevel.GUEST_READ, Set.of(), false),
                new WebRouteContribution("/pixiv-showcase.html", AccessLevel.GUEST_READ, Set.of(), false),
                new WebRouteContribution("/pixiv-series.html", AccessLevel.GUEST_READ, Set.of(), false),
                new WebRouteContribution("/pixiv-gallery/**", AccessLevel.GUEST_READ, Set.of(), false),
                new WebRouteContribution("/pixiv-artwork/**", AccessLevel.GUEST_READ, Set.of(), false),
                new WebRouteContribution("/pixiv-showcase/**", AccessLevel.GUEST_READ, Set.of(), false),
                new WebRouteContribution("/pixiv-series/**", AccessLevel.GUEST_READ, Set.of(), false),
                new WebRouteContribution("/api/gallery/**", AccessLevel.GUEST_READ, Set.of(), false));
    }

    @Override
    public List<StaticResourceContribution> staticResources() {
        return List.of(
                new StaticResourceContribution(
                        ID, "classpath:/static/pixiv-gallery/", "/pixiv-gallery/", AccessLevel.GUEST_READ),
                new StaticResourceContribution(
                        ID, "classpath:/static/pixiv-artwork/", "/pixiv-artwork/", AccessLevel.GUEST_READ),
                new StaticResourceContribution(
                        ID, "classpath:/static/pixiv-showcase/", "/pixiv-showcase/", AccessLevel.GUEST_READ),
                new StaticResourceContribution(
                        ID, "classpath:/static/pixiv-series/", "/pixiv-series/", AccessLevel.GUEST_READ));
    }

    @Override
    public List<I18nContribution> i18n() {
        // 页面跟插件走：画廊 / 作品详情 / 系列 / 精选集四个页面 namespace 均归本插件。
        return List.of(
                new I18nContribution("gallery", "i18n.web.gallery"),
                new I18nContribution("artwork", "i18n.web.artwork"),
                new I18nContribution("series", "i18n.web.series"),
                new I18nContribution("showcase", "i18n.web.showcase"));
    }

    @Override
    public List<NavigationContribution> navigation() {
        return List.of(new NavigationContribution(
                ID, "nav.label", "/pixiv-gallery.html", "images", AccessLevel.GUEST_READ, 20));
    }

    @Override
    public List<CoreColumnUsage> coreColumnUsages() {
        return List.of(
                new CoreColumnUsage("artworks", List.of(
                        "artwork_id", "title", "description", "author_id", "R18", "is_ai",
                        "extensions", "count", "moved", "time", "deleted",
                        "series_id", "series_order")),
                new CoreColumnUsage("artwork_tags", List.of("artwork_id", "tag_id")),
                new CoreColumnUsage("tags", List.of("tag_id", "name", "translated_name")),
                new CoreColumnUsage("authors", List.of("author_id", "name")),
                new CoreColumnUsage("manga_series", List.of("series_id", "title")),
                new CoreColumnUsage("artwork_collections", List.of("artwork_id", "collection_id")));
    }
}
