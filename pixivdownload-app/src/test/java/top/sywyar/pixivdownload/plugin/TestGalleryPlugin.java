package top.sywyar.pixivdownload.plugin;

import top.sywyar.pixivdownload.plugin.api.gui.GuiOnboardingStepContribution;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.schema.CoreColumnUsage;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.Audience;
import top.sywyar.pixivdownload.plugin.api.web.DrilldownContribution;
import top.sywyar.pixivdownload.plugin.api.web.DrilldownPlacements;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;
import top.sywyar.pixivdownload.plugin.api.web.LandingContribution;
import top.sywyar.pixivdownload.plugin.api.web.NavigationContribution;
import top.sywyar.pixivdownload.plugin.api.web.NavigationMarkers;
import top.sywyar.pixivdownload.plugin.api.web.NavigationPlacements;
import top.sywyar.pixivdownload.plugin.api.web.PageSectionContribution;
import top.sywyar.pixivdownload.plugin.api.web.StartupRouteContribution;
import top.sywyar.pixivdownload.plugin.api.web.StartupRouteContext;
import top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Test-only gallery contribution model used by app-side registry tests after the production gallery
 * implementation moved to the external plugin module.
 */
public final class TestGalleryPlugin implements PixivFeaturePlugin {

    static final String ID = "gallery";

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
        return List.of(
                new I18nContribution("gallery", "i18n.web.gallery_test", 6),
                new I18nContribution("artwork", "i18n.web.artwork", 9),
                new I18nContribution("showcase", "i18n.web.showcase", 10),
                new I18nContribution("series", "i18n.web.series", 11));
    }

    @Override
    public List<NavigationContribution> navigation() {
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
        return List.of(new LandingContribution(
                ID, "gallery", Audience.INVITED_GUEST, "/pixiv-gallery.html", 20));
    }

    @Override
    public List<CoreColumnUsage> coreColumnUsages() {
        List<CoreColumnUsage> usages = new ArrayList<>();
        usages.add(new CoreColumnUsage("artworks", List.of("artwork_id")));
        return usages;
    }
}
