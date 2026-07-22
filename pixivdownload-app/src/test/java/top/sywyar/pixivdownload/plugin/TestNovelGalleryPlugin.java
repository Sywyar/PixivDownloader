package top.sywyar.pixivdownload.plugin;

import top.sywyar.pixivdownload.plugin.api.download.type.DownloadTypeDescriptor;
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
import top.sywyar.pixivdownload.plugin.api.web.WebUiSlotContribution;

import java.util.List;
import java.util.Set;

/**
 * Test-only novel contribution model used by app-side registry tests after the production
 * novel implementation moved to the external plugin module.
 */
public final class TestNovelGalleryPlugin implements PixivFeaturePlugin {

    static final String ID = "novel";
    private static final String NOVEL_MODULE_URL = "/pixiv-novel-download/novel-queue-type.js";
    private static final List<String> NOVEL_UI_SLOT_TARGETS = List.of(
            "kind-option-user", "kind-option-search", "kind-option-quick",
            "quick-actions-bookmarks", "quick-actions-mine",
            "import-hint", "search-filter", "settings-card");

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
                WebRouteContribution.visitor("/api/novel/download"),
                WebRouteContribution.visitor("/api/novel/status/**"),
                WebRouteContribution.visitor("/api/novel/translate-status/**"),
                WebRouteContribution.visitor("/api/novel/*/downloaded"),
                WebRouteContribution.visitor("/api/novel/series/*/merge"),
                WebRouteContribution.visitorAndInvitedGuest("/api/novel/series/*/merged"),
                WebRouteContribution.admin("/api/novel/*/translate"),
                WebRouteContribution.admin("/api/novel/translate-lang-probe"),
                WebRouteContribution.admin("/api/novel/series/*/translate-title"),
                WebRouteContribution.admin("/api/novel/series/*/novel-ids"),
                WebRouteContribution.visitor("/api/download/pixiv/novel"),
                WebRouteContribution.visitor("/api/download/novel/status/**"),
                WebRouteContribution.visitor("/api/download/novel/translate-status/**"),
                WebRouteContribution.visitorAndInvitedGuest("/api/pixiv/novel/*/meta"),
                WebRouteContribution.visitorAndInvitedGuest("/api/pixiv/novel/*/bookmark-count"),
                WebRouteContribution.visitor("/api/pixiv/novel/series/*"),
                WebRouteContribution.visitor("/api/pixiv/novel-search**"),
                WebRouteContribution.visitor("/api/pixiv/user/*/novels"),
                WebRouteContribution.visitor("/api/pixiv/user/*/novel-cards"),
                WebRouteContribution.visitor("/api/pixiv/me/novel-bookmarks"),
                WebRouteContribution.visitor("/pixiv-novel-download/**"),
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
                new StaticResourceContribution("classpath:/static/pixiv-novel-download/",
                        "/pixiv-novel-download/"),
                new StaticResourceContribution("classpath:/static/", "/pixiv-novel-gallery.html", true),
                new StaticResourceContribution("classpath:/static/", "/pixiv-novel.html", true),
                new StaticResourceContribution("classpath:/static/pixiv-novel-gallery/",
                        "/pixiv-novel-gallery/"),
                new StaticResourceContribution("classpath:/static/pixiv-novel/", "/pixiv-novel/"));
    }

    @Override
    public List<I18nContribution> i18n() {
        return List.of(
                new I18nContribution("novel", "i18n.web.novel", 10),
                new I18nContribution("narration", "i18n.web.narration", 11),
                new I18nContribution("novel-gallery", "i18n.web.novel-gallery", 12));
    }

    @Override
    public List<DownloadTypeDescriptor> downloadTypes() {
        return List.of(TestDownloadTypeDescriptors.create(
                "novel", "novel", "batch.user.kind-novel", 20, NOVEL_MODULE_URL));
    }

    @Override
    public List<WebUiSlotContribution> uiSlots() {
        return NOVEL_UI_SLOT_TARGETS.stream()
                .map(target -> new WebUiSlotContribution(ID + "." + target, target, NOVEL_MODULE_URL, 20))
                .toList();
    }

    @Override
    public List<NavigationContribution> navigation() {
        return List.of(
                new NavigationContribution(
                        "novel-gallery",
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
                "novel-gallery", Audience.INVITED_GUEST, "/pixiv-novel-gallery.html", 30));
    }
}
