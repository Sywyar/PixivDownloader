package top.sywyar.pixivdownload.gallery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.schema.CoreColumnUsage;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.Audience;
import top.sywyar.pixivdownload.plugin.api.web.NavigationMarkers;
import top.sywyar.pixivdownload.plugin.api.web.NavigationPlacements;
import top.sywyar.pixivdownload.plugin.api.web.StartupRouteContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("gallery 插件入口 contribution 声明")
class GalleryPluginContributionTest {

    private final GalleryPlugin plugin = new GalleryPlugin();

    @Test
    @DisplayName("gallery 默认启动落点由插件声明并绑定 solo 上下文")
    void startupRouteIsOwnedByGalleryPlugin() {
        assertThat(plugin.startupRoutes())
                .singleElement()
                .satisfies(route -> {
                    assertThat(route.pluginId()).isEqualTo("gallery");
                    assertThat(route.path()).isEqualTo("/pixiv-gallery.html");
                    assertThat(route.preferredContexts()).containsExactly(StartupRouteContext.SOLO);
                });
    }

    @Test
    @DisplayName("gallery 以成熟页面为主入口并保留已过时的只读兼容 API")
    void pageRoutesAndStaticResourcesUseMatureGalleryOnly() {
        assertThat(plugin.routes())
                .extracting(route -> route.pathPattern())
                .contains("/pixiv-gallery.html", "/pixiv-gallery/**", "/api/gallery/unified/**")
                .doesNotContain("/unified-gallery.html", "/unified-gallery/**");
        assertThat(plugin.routes())
                .filteredOn(route -> route.pathPattern().equals("/api/gallery/unified/**"))
                .singleElement()
                .satisfies(route -> assertThat(route.accessPolicy()).isEqualTo(AccessPolicy.INVITED_GUEST));
        assertThat(plugin.staticResources())
                .extracting(resource -> resource.publicPathPrefix())
                .contains("/pixiv-gallery.html", "/pixiv-gallery/")
                .doesNotContain("/unified-gallery.html", "/unified-gallery/");
    }

    @Test
    @DisplayName("gallery GUI Web 入口由导航 contribution 声明并使用 gallery i18n namespace")
    void guiWebEntryIsOwnedByGalleryPlugin() {
        assertThat(plugin.navigation())
                .filteredOn(nav -> nav.id().equals("gallery"))
                .singleElement()
                .satisfies(nav -> {
                    assertThat(nav.href()).isEqualTo("/pixiv-gallery.html?view=all");
                    assertThat(nav.markers()).containsExactly(NavigationMarkers.FIRST_DOWNLOAD_RESULT);
                });
        assertThat(plugin.navigation())
                .filteredOn(nav -> nav.id().equals("gallery-type-switch"))
                .singleElement()
                .satisfies(nav -> {
                    assertThat(nav.placements()).containsExactly(NavigationPlacements.GALLERY_TYPE_SWITCH);
                    assertThat(nav.labelNamespace()).isEqualTo("gallery");
                    assertThat(nav.labelI18nKey()).isEqualTo("nav.type-illust");
                    assertThat(nav.href()).isEqualTo("/pixiv-gallery.html?view=all");
                });
        assertThat(plugin.navigation())
                .filteredOn(nav -> nav.id().equals("gallery-gui-open"))
                .singleElement()
                .satisfies(nav -> {
                    assertThat(nav.placements()).containsExactlyInAnyOrder(
                            NavigationPlacements.GUI_STATUS_ACTIONS,
                            NavigationPlacements.GUI_TRAY_ACTIONS);
                    assertThat(nav.labelNamespace()).isEqualTo("gallery");
                    assertThat(nav.labelI18nKey()).isEqualTo("gui.action.open");
                    assertThat(nav.href()).isEqualTo("/pixiv-gallery.html");
                });
        assertThat(plugin.navigation())
                .filteredOn(nav -> nav.id().equals("gallery-invite-manage-back"))
                .singleElement()
                .satisfies(nav -> {
                    assertThat(nav.placements()).containsExactly(NavigationPlacements.INVITE_MANAGE_BACK);
                    assertThat(nav.labelNamespace()).isEqualTo("gallery");
                    assertThat(nav.labelI18nKey()).isEqualTo("invite.manage.back");
                    assertThat(nav.href()).isEqualTo("/pixiv-gallery.html?view=all");
                });
    }

    @Test
    @DisplayName("gallery 为邀请访客声明成熟画廊落点")
    void invitedGuestLandingUsesMatureGallery() {
        assertThat(plugin.landings())
                .singleElement()
                .satisfies(landing -> {
                    assertThat(landing.audience()).isEqualTo(Audience.INVITED_GUEST);
                    assertThat(landing.href()).isEqualTo("/pixiv-gallery.html");
                });
    }

    @Test
    @DisplayName("gallery 欢迎页步骤由插件声明并使用 gallery i18n namespace")
    void onboardingStepIsOwnedByGalleryPlugin() {
        assertThat(plugin.guiOnboardingSteps())
                .singleElement()
                .satisfies(step -> {
                    assertThat(step.pluginId()).isEqualTo("gallery");
                    assertThat(step.stepId()).isEqualTo("local-gallery-guide");
                    assertThat(step.i18nNamespace()).isEqualTo("gallery");
                    assertThat(step.titleKey()).isEqualTo("gui.onboarding.title");
                    assertThat(step.actionLabelKey()).isEqualTo("gui.onboarding.button");
                    assertThat(step.actionHref()).isEqualTo("/pixiv-gallery.html");
                    assertThat(step.completionKey()).isEqualTo("local-gallery-guide");
                });
    }

    @Test
    @DisplayName("gallery 精确声明读取的核心表列")
    void coreColumnUsagesDeclareGalleryReadModel() {
        assertThat(plugin.coreColumnUsages()).containsExactly(
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
