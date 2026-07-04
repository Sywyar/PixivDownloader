package top.sywyar.pixivdownload.plugin;

import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
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
 * Test-only novel-gallery contribution model used by app-side registry tests after the production
 * novel-gallery implementation moved to the external plugin module.
 */
public final class TestNovelGalleryPlugin implements PixivFeaturePlugin {

    static final String ID = "novel-gallery";

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
}
