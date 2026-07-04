package top.sywyar.pixivdownload.novelgallery;

import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.schema.CoreColumnUsage;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.Audience;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;
import top.sywyar.pixivdownload.plugin.api.web.LandingContribution;
import top.sywyar.pixivdownload.plugin.api.web.NavigationContribution;
import top.sywyar.pixivdownload.plugin.api.web.NavigationPlacements;
import top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;

import java.util.List;
import java.util.Set;

/**
 * 小说画廊插件：小说列表 / 详情 / 系列页面，以及 {@code /api/gallery} 下的小说展示与批量管理入口。
 * 本插件只拥有展示层入口；小说下载、正文保存、翻译状态、系列合订、TTS 与 AI 听书能力留在宿主小说核心。
 */
public class NovelGalleryPlugin implements PixivFeaturePlugin {

    private static final String ID = "novel-gallery";

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
        return "book";
    }

    @Override
    public String colorToken() {
        return "amber";
    }

    @Override
    public PluginKind kind() {
        return PluginKind.FEATURE;
    }

    @Override
    public List<WebRouteContribution> routes() {
        return List.of(
                WebRouteContribution.invitedGuest("/pixiv-novel-gallery.html"),
                WebRouteContribution.invitedGuest("/pixiv-novel.html"),
                WebRouteContribution.invitedGuest("/pixiv-novel-gallery/**"),
                WebRouteContribution.invitedGuest("/pixiv-novel/**"),
                WebRouteContribution.invitedGuest("/api/gallery/novel/**"),
                WebRouteContribution.invitedGuest("/api/gallery/novels/**"),
                WebRouteContribution.invitedGuest("/api/gallery/novels"));
    }

    @Override
    public List<StaticResourceContribution> staticResources() {
        return List.of(
                new StaticResourceContribution(ID, "classpath:/static/", "/pixiv-novel-gallery.html", true),
                new StaticResourceContribution(ID, "classpath:/static/", "/pixiv-novel.html", true),
                new StaticResourceContribution(ID, "classpath:/static/pixiv-novel-gallery/",
                        "/pixiv-novel-gallery/"),
                new StaticResourceContribution(ID, "classpath:/static/pixiv-novel/", "/pixiv-novel/"));
    }

    @Override
    public List<I18nContribution> i18n() {
        return List.of(new I18nContribution("novel-gallery", "i18n.web.novel-gallery", 12));
    }

    @Override
    public List<NavigationContribution> navigation() {
        return List.of(
                new NavigationContribution(
                        ID,
                        Set.of(NavigationPlacements.APP_TOP, NavigationPlacements.NOVEL_SIDEBAR),
                        "novel-gallery", "nav.label", "/pixiv-novel-gallery.html?view=all", "book",
                        AccessPolicy.INVITED_GUEST, 40),
                new NavigationContribution(
                        "novel-type-switch",
                        Set.of(NavigationPlacements.GALLERY_TYPE_SWITCH),
                        "novel-gallery", "nav.type-novel", "/pixiv-novel-gallery.html?view=all", "book",
                        AccessPolicy.INVITED_GUEST, 40));
    }

    @Override
    public List<LandingContribution> landings() {
        return List.of(new LandingContribution(
                ID, "novel-gallery", Audience.INVITED_GUEST, "/pixiv-novel-gallery.html", 30));
    }

    @Override
    public List<CoreColumnUsage> coreColumnUsages() {
        return List.of(
                new CoreColumnUsage("novels", List.of(
                        "novel_id", "author_id", "R18", "time", "deleted",
                        "series_id", "series_order")),
                new CoreColumnUsage("novel_tags", List.of("novel_id", "tag_id")),
                new CoreColumnUsage("tags", List.of("tag_id", "name", "translated_name")));
    }
}
