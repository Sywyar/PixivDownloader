package top.sywyar.pixivdownload.gallerytools;

import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;
import top.sywyar.pixivdownload.plugin.api.web.NavigationContribution;
import top.sywyar.pixivdownload.plugin.api.web.NavigationPlacements;
import top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;

import java.util.List;
import java.util.Set;

/**
 * 画廊工具插件：统一承载统计仪表盘与疑似重复检测。两项能力共享安装、启停和重载生命周期，
 * 但仍分别经核心统计与图片哈希语义端口读取事实数据。
 */
public class GalleryToolsPlugin implements PixivFeaturePlugin {

    private static final String ID = "gallery-tools";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "plugin.name";
    }

    @Override
    public String description() {
        return "plugin.summary";
    }

    @Override
    public String iconKey() {
        return "screwdriver-wrench";
    }

    @Override
    public String colorToken() {
        return "purple";
    }

    @Override
    public PluginKind kind() {
        return PluginKind.FEATURE;
    }

    @Override
    public List<WebRouteContribution> routes() {
        return List.of(
                WebRouteContribution.admin("/pixiv-stats.html"),
                WebRouteContribution.admin("/pixiv-stats/**"),
                WebRouteContribution.admin("/api/stats/**"),
                WebRouteContribution.admin("/pixiv-duplicates.html"),
                WebRouteContribution.admin("/pixiv-duplicates/**"),
                WebRouteContribution.admin("/api/duplicates/**"));
    }

    @Override
    public List<StaticResourceContribution> staticResources() {
        return List.of(
                new StaticResourceContribution("classpath:/static/pixiv-stats/", "/pixiv-stats/"),
                new StaticResourceContribution("classpath:/static/", "/pixiv-stats.html", true),
                new StaticResourceContribution("classpath:/static/", "/pixiv-duplicates.html", true),
                new StaticResourceContribution("classpath:/static/pixiv-duplicates/", "/pixiv-duplicates/"));
    }

    @Override
    public List<I18nContribution> i18n() {
        return List.of(
                new I18nContribution(ID, "i18n.web.gallery-tools", 9),
                new I18nContribution("stats", "i18n.web.stats", 7),
                new I18nContribution("duplicates", "i18n.web.duplicates", 8));
    }

    @Override
    public List<NavigationContribution> navigation() {
        // 同一页面只引导一次：统计与疑似重复仅进入大页面下一级导航，不进入 app.top。
        return List.of(
                new NavigationContribution(
                        "stats",
                        Set.of(NavigationPlacements.APP_SIDEBAR,
                                NavigationPlacements.GALLERY_SIDEBAR, NavigationPlacements.NOVEL_SIDEBAR,
                                NavigationPlacements.DUPLICATES_HEADER_ICONS,
                                NavigationPlacements.DESKTOP_QUICK_START),
                        "stats", "nav.label", "/pixiv-stats.html", "chart-bar", AccessPolicy.ADMIN, 50),
                new NavigationContribution(
                        "duplicate",
                        Set.of(NavigationPlacements.APP_SIDEBAR,
                                NavigationPlacements.GALLERY_SIDEBAR, NavigationPlacements.NOVEL_SIDEBAR),
                        "duplicates", "nav.label", "/pixiv-duplicates.html", "copy", AccessPolicy.ADMIN, 60));
    }
}
