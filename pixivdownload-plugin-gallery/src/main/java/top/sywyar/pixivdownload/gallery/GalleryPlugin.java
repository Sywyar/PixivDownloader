package top.sywyar.pixivdownload.gallery;

import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.Audience;
import top.sywyar.pixivdownload.plugin.api.gui.GuiOnboardingStepContribution;
import top.sywyar.pixivdownload.plugin.api.schema.CoreColumnUsage;
import top.sywyar.pixivdownload.plugin.api.web.DrilldownContribution;
import top.sywyar.pixivdownload.plugin.api.web.DrilldownPlacements;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;
import top.sywyar.pixivdownload.plugin.api.web.LandingContribution;
import top.sywyar.pixivdownload.plugin.api.web.NavigationContribution;
import top.sywyar.pixivdownload.plugin.api.web.NavigationMarkers;
import top.sywyar.pixivdownload.plugin.api.web.NavigationPlacements;
import top.sywyar.pixivdownload.plugin.api.web.PageSectionContribution;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.web.StartupRouteContribution;
import top.sywyar.pixivdownload.plugin.api.web.StartupRouteContext;
import top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;

import java.util.List;
import java.util.Set;

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

    // 展示名 / 简介为纯 i18n key；namespace 由 displayNamespace() 默认取本插件首个 namespace（gallery）。
    @Override
    public String displayName() {
        return "plugin.name";
    }

    @Override
    public String description() {
        return "plugin.summary";
    }

    // 卡片展示用受控 token（非 URL / CSS / 远程资源；由插件管理页本地白名单映射）：画廊。
    @Override
    public String iconKey() {
        return "gallery";
    }

    @Override
    public String colorToken() {
        return "green";
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
                WebRouteContribution.invitedGuest("/api/gallery/tags**"),
                WebRouteContribution.invitedGuest("/api/gallery/unified/**"));
    }

    @Override
    public List<StaticResourceContribution> staticResources() {
        return List.of(
                new StaticResourceContribution(ID, "classpath:/static/", "/pixiv-gallery.html", true),
                new StaticResourceContribution(ID, "classpath:/static/", "/pixiv-artwork.html", true),
                new StaticResourceContribution(ID, "classpath:/static/", "/pixiv-showcase.html", true),
                new StaticResourceContribution(ID, "classpath:/static/", "/pixiv-series.html", true),
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
        // 画廊主入口：顶部栏 + 画廊家族侧栏（画廊 / 系列页共用）+ 中立主侧栏 app.sidebar（统计等宿主页据此显示
        // 画廊入口，禁用画廊后自然消失、宿主页不需要知道画廊），并兼任疑似重复页顶部的画廊图标
        //（duplicates.header-icons）。priority 30（功能区段首位）。href 由贡献方完整声明为 /pixiv-gallery.html?view=all
        //（与历史入口一致）——前端公共导航渲染器不再为内置插件 id 补默认 query。
        //
        // 类型切换入口：向小说画廊页的「小说↔画廊」类型切换（novel.type-switch）注册指向画廊的「漫画」tab——
        // 取代小说页此前硬编码 /pixiv-gallery.html + data-nav-requires 的做法（label 用类型名 nav.type-illust=漫画，
        // 非页面名 nav.label=画廊）。该 slot 为 label-only tab，忽略 icon；href 显式带 ?view=all。
        //
        // 统计页画廊视图快捷入口：向 stats.gallery-links 注册「全部 / 按作者 / 按系列」三条画廊视图链接——
        // 取代统计页此前硬编码这些 href 的做法。这三条链接作为「视图」区块（见 pageSections()）内嵌导航 slot 的内容；
        // priority 31/32/33 仅决定三者相对顺序。全部 INVITED_GUEST，禁用画廊后这些入口（含疑似重复页图标）一并消失。
        return List.of(
                new NavigationContribution(
                        ID,
                        Set.of(NavigationPlacements.APP_TOP, NavigationPlacements.APP_SIDEBAR,
                                NavigationPlacements.GALLERY_SIDEBAR, NavigationPlacements.DUPLICATES_HEADER_ICONS),
                        "gallery", "nav.label", "/pixiv-gallery.html?view=all", "images",
                        AccessPolicy.INVITED_GUEST, 30, Set.of(NavigationMarkers.FIRST_DOWNLOAD_RESULT)),
                new NavigationContribution(
                        "gallery-type-switch",
                        Set.of(NavigationPlacements.NOVEL_TYPE_SWITCH),
                        "gallery", "nav.type-illust", "/pixiv-gallery.html?view=all", "images",
                        AccessPolicy.INVITED_GUEST, 30),
                new NavigationContribution(
                        "gallery-view-all",
                        Set.of(NavigationPlacements.STATS_GALLERY_LINKS),
                        "gallery", "nav.all", "/pixiv-gallery.html?view=all", "grid",
                        AccessPolicy.INVITED_GUEST, 31),
                new NavigationContribution(
                        "gallery-view-authors",
                        Set.of(NavigationPlacements.STATS_GALLERY_LINKS),
                        "gallery", "nav.authors", "/pixiv-gallery.html?view=authors", "users",
                        AccessPolicy.INVITED_GUEST, 32),
                new NavigationContribution(
                        "gallery-view-series",
                        Set.of(NavigationPlacements.STATS_GALLERY_LINKS),
                        "gallery", "nav.series", "/pixiv-gallery.html?view=series", "book",
                        AccessPolicy.INVITED_GUEST, 33),
                new NavigationContribution(
                        "gallery-gui-open",
                        Set.of(NavigationPlacements.GUI_STATUS_ACTIONS, NavigationPlacements.GUI_TRAY_ACTIONS),
                        "gallery", "gui.action.open", "/pixiv-gallery.html", "images",
                        AccessPolicy.INVITED_GUEST, 33),
                new NavigationContribution(
                        "gallery-invite-manage-back",
                        Set.of(NavigationPlacements.INVITE_MANAGE_BACK),
                        "gallery", "invite.manage.back", "/pixiv-gallery.html?view=all", "images",
                        AccessPolicy.INVITED_GUEST, 33));
    }

    @Override
    public List<PageSectionContribution> pageSections() {
        // 统计页侧栏借用画廊能力的页面内区块（stats.sidebar.sections）：统计页只声明空 section slot，本插件向它贡献
        // 两个区块——禁用画廊后两区块（含其内嵌导航、创建入口、收藏夹列表）自然消失，统计页不需要知道画廊是否存在。
        //   ① 「视图」区块：内嵌导航 slot = stats.gallery-links（全部 / 按作者 / 按系列三链由本插件 navigation() 供给）。
        //   ② 「收藏夹」区块：操作入口 = 新建收藏夹（actionHref 完整声明），moduleUrl 指向本插件自有前端模块，
        //      由该模块拉 /api/collections 渲染收藏夹列表（API 调用在本插件代码内，统计页不触达 collection API）。
        // 两区块均 INVITED_GUEST（与画廊页面同级可见性，管理员 / 受邀访客可见）；title / 操作标题用本插件 namespace key。
        return List.of(
                new PageSectionContribution(
                        ID, "gallery-stats-views", NavigationPlacements.STATS_SIDEBAR_SECTIONS,
                        "gallery", "section.view", NavigationPlacements.STATS_GALLERY_LINKS,
                        null, null, null, null, null, AccessPolicy.INVITED_GUEST, 10),
                new PageSectionContribution(
                        ID, "gallery-stats-collections", NavigationPlacements.STATS_SIDEBAR_SECTIONS,
                        "gallery", "section.collections", null,
                        "/pixiv-gallery.html?view=all&createCollection=1", "plus", "gallery", "collection.new",
                        "/pixiv-gallery/gallery-stats-embed.js", AccessPolicy.INVITED_GUEST, 20));
    }

    @Override
    public List<DrilldownContribution> drilldowns() {
        // 统计页 Top 作者 / 热门标签的语义下钻（stats.top-authors / stats.top-tags）：统计页只认得这两个语义 placement，
        // 由本插件贡献「按作者 / 标签过滤画廊」的 href 模板——目标页面路径与查询参数名只出现在本贡献里，统计页源码
        // 不含任何画廊知识。模板里的 {变量名} 占位由前端通用下钻渲染器（/js/pixiv-drilldowns.js）做 encodeURIComponent
        // 后替换，统计页只提供变量值。两条均 INVITED_GUEST（与画廊页面同级可见性，管理员 / 受邀访客可见），priority 10。
        // 查询参数名与画廊筛选入口现状一致（gallery-filters.js：作者 filterAuthorId/filterAuthorName、
        // 标签 filterTagId/filterTag/filterTagTranslated），保持现有画廊入口语义；禁用画廊后这两条贡献自然消失，
        // 统计页 /api/drilldowns 对应 placement 无内容、回到纯展示。
        return List.of(
                new DrilldownContribution(
                        ID, "gallery-stats-author", DrilldownPlacements.STATS_TOP_AUTHORS,
                        "/pixiv-gallery.html?view=all&filterAuthorId={authorId}&filterAuthorName={authorName}",
                        AccessPolicy.INVITED_GUEST, 10),
                new DrilldownContribution(
                        ID, "gallery-stats-tag", DrilldownPlacements.STATS_TOP_TAGS,
                        "/pixiv-gallery.html?view=all&filterTagId={tagId}&filterTag={tagName}"
                                + "&filterTagTranslated={tagTranslatedName}",
                        AccessPolicy.INVITED_GUEST, 10));
    }

    @Override
    public List<StartupRouteContribution> startupRoutes() {
        // solo 模式默认落点：画廊页；也是 multi 模式下禁用下载工作台后按 order 回退的下一落点。
        return List.of(new StartupRouteContribution(ID, "/pixiv-gallery.html", 20, Set.of(StartupRouteContext.SOLO)));
    }

    @Override
    public List<GuiOnboardingStepContribution> guiOnboardingSteps() {
        return List.of(new GuiOnboardingStepContribution(
                ID,
                "local-gallery-guide",
                "gallery",
                "gui.onboarding.title",
                "gui.onboarding.body",
                List.of(
                        "gui.onboarding.point.search",
                        "gui.onboarding.point.collections",
                        "gui.onboarding.point.guide"),
                "gui.onboarding.button",
                "/pixiv-gallery.html",
                "gui.onboarding.waiting",
                "local-gallery-guide",
                50));
    }

    @Override
    public List<LandingContribution> landings() {
        // 受邀访客的默认落点：画廊页（landing/entrypoint priority 20，优先于小说 30）。priority 是落点优先级、
        // 不是导航 order；禁用画廊后本落点不进活动快照、邀请兑换自动回退到小说。/pixiv-gallery.html 对受邀访客
        // 可达（routes() 声明为 INVITED_GUEST），可达性由 LandingRegistryTest 守卫。
        return List.of(new LandingContribution(
                ID, "gallery", Audience.INVITED_GUEST, "/pixiv-gallery.html", 20));
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
