package top.sywyar.pixivdownload.gallery;

import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.schema.CoreColumnUsage;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;
import top.sywyar.pixivdownload.plugin.api.web.NavigationContribution;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.web.StartupRouteContribution;
import top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;

import java.util.List;

/**
 * 画廊插件：画廊 / 作品详情 / 精选集 / 系列四个页面，以及 {@code /api/gallery} 下插画作品 /
 * 标签子面（{@code /api/gallery/artwork(s)} 与 {@code /api/gallery/tags}）与批量管理入口。
 * <p>
 * 与 stats / duplicate 两个管理员专属插件不同，画廊全部路由是 {@code INVITED_GUEST}：
 * 按 monitor 语义保护（solo 会话用户或 multi 登录管理员全量可用），同时受邀访客可只读访问
 * （仅 GET/HEAD，仍需经 invite session 校验，落在 AuthFilter 的 GUEST_ALLOWED 清单）——
 * 该双重语义由路由镜像测试逐条守护。无私有表（artworks 系按卸载投影测试归 core）。
 * <p>
 * 核心列使用声明只覆盖核心数据层 SQL 仓库
 * {@link top.sywyar.pixivdownload.core.metadata.artwork.GalleryRepository}（已收编进核心）触及的表列；
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

    // 展示名 / 简介在本插件自有 namespace（gallery）解析：名称复用已有的导航标签 nav.label，简介用专用 key。
    @Override
    public String displayName() {
        return "nav.label";
    }

    @Override
    public String description() {
        return "plugin.summary";
    }

    @Override
    public PluginKind kind() {
        return PluginKind.FEATURE;
    }

    @Override
    public List<WebRouteContribution> routes() {
        // 画廊页面 + 画廊自身的 /api/gallery 子面（插画作品与标签），全部 INVITED_GUEST：
        // 同时进入 monitor 清单与访客邀请白名单（访客仅 GET/HEAD 的收窄由访问级别语义承载）。
        // /api/gallery API 历史上由单一 /api/gallery/** 覆盖，现按控制器实际归属拆分——画廊只声明
        // 非小说的 artwork/tags 前缀，小说子面 /api/gallery/novel(s) 由小说插件声明，互不越界。
        // 无尾斜杠前缀同 /api/authors** 写法：/api/gallery/artwork** 既命中 /api/gallery/artworks
        // 也命中 /api/gallery/artwork/{id}；/api/gallery/tags** 既命中 /api/gallery/tags 也命中 /tags/lookup。
        return List.of(
                WebRouteContribution.invitedGuest("/pixiv-gallery.html"),
                WebRouteContribution.invitedGuest("/pixiv-artwork.html"),
                WebRouteContribution.invitedGuest("/pixiv-showcase.html"),
                WebRouteContribution.invitedGuest("/pixiv-series.html"),
                WebRouteContribution.invitedGuest("/pixiv-gallery/**"),
                WebRouteContribution.invitedGuest("/pixiv-artwork/**"),
                WebRouteContribution.invitedGuest("/pixiv-showcase/**"),
                WebRouteContribution.invitedGuest("/pixiv-series/**"),
                WebRouteContribution.invitedGuest("/api/gallery/artwork**"),
                WebRouteContribution.invitedGuest("/api/gallery/tags**"));
    }

    @Override
    public List<StaticResourceContribution> staticResources() {
        return List.of(
                new StaticResourceContribution(ID, "classpath:/static/pixiv-gallery/", "/pixiv-gallery/"),
                new StaticResourceContribution(ID, "classpath:/static/pixiv-artwork/", "/pixiv-artwork/"),
                new StaticResourceContribution(ID, "classpath:/static/pixiv-showcase/", "/pixiv-showcase/"),
                new StaticResourceContribution(ID, "classpath:/static/pixiv-series/", "/pixiv-series/"));
    }

    @Override
    public List<I18nContribution> i18n() {
        // 页面跟插件走：画廊 / 作品详情 / 系列 / 精选集四个页面 namespace 均归本插件。
        // 第三参为 /api/i18n/meta 的全局展示顺序（保持历史 namespace 顺序）。
        return List.of(
                new I18nContribution("gallery", "i18n.web.gallery", 6),
                new I18nContribution("artwork", "i18n.web.artwork", 9),
                new I18nContribution("showcase", "i18n.web.showcase", 10),
                new I18nContribution("series", "i18n.web.series", 11));
    }

    @Override
    public List<NavigationContribution> navigation() {
        return List.of(new NavigationContribution(
                ID, "nav.label", "/pixiv-gallery.html", "images", AccessPolicy.INVITED_GUEST, 20));
    }

    @Override
    public List<StartupRouteContribution> startupRoutes() {
        // solo 模式默认落点：画廊页（/redirect 在 solo 模式以本插件为首选）；
        // 也是 multi 模式下禁用下载工作台后按 order 回退的下一落点。
        return List.of(new StartupRouteContribution(ID, "/pixiv-gallery.html", 20));
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
