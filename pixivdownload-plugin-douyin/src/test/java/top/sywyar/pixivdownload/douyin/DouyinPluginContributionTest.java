package top.sywyar.pixivdownload.douyin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.douyin.settings.DouyinPluginSettingsService;
import top.sywyar.pixivdownload.douyin.schedule.codec.DouyinScheduleCodec;
import top.sywyar.pixivdownload.douyin.schedule.source.DouyinScheduledSourceDescriptors;
import top.sywyar.pixivdownload.douyin.source.DouyinSourceTypes;
import top.sywyar.pixivdownload.plugin.api.download.type.DownloadAcquisitionMode;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigCondition;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldType;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.NavigationPlacements;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DouyinPlugin 外置贡献契约")
class DouyinPluginContributionTest {

    @Test
    @DisplayName("计划任务贡献恰好声明九类稳定来源与统一能力")
    void scheduleDescriptorsDeclareExactStableSourceSet() {
        DouyinPlugin plugin = new DouyinPlugin();

        assertThat(plugin.scheduledSourceDescriptors())
                .extracting(descriptor -> descriptor.sourceType())
                .containsExactly(
                        DouyinSourceTypes.USER,
                        DouyinSourceTypes.SEARCH,
                        DouyinSourceTypes.COLLECTION,
                        DouyinSourceTypes.MUSIC,
                        DouyinSourceTypes.ACCOUNT_OWN_WORKS,
                        DouyinSourceTypes.ACCOUNT_LIKED_WORKS,
                        DouyinSourceTypes.ACCOUNT_FAVORITE_WORKS,
                        DouyinSourceTypes.ACCOUNT_FAVORITE_FOLDER,
                        DouyinSourceTypes.ACCOUNT_FAVORITE_COLLECTION);
        assertThat(plugin.scheduledSourceDescriptors()).allSatisfy(descriptor -> {
            assertThat(descriptor.definitionSchema())
                    .isEqualTo(DouyinScheduleCodec.DEFINITION_SCHEMA);
            assertThat(descriptor.definitionVersion())
                    .isEqualTo(DouyinScheduleCodec.DEFINITION_VERSION);
            assertThat(descriptor.possibleWorkTypes())
                    .isEqualTo(Set.of(DouyinScheduleCodec.WORK_TYPE));
            assertThat(descriptor.credentialPolicyIds())
                    .isEqualTo(Set.of(DouyinScheduledSourceDescriptors.CREDENTIAL_POLICY_ID));
            assertThat(descriptor.guardIds())
                    .isEqualTo(Set.of(DouyinScheduledSourceDescriptors.GUARD_ID));
            assertThat(descriptor.frontend().moduleUrl())
                    .isEqualTo(DouyinScheduledSourceDescriptors.FRONTEND_MODULE_URL);
            assertThat(descriptor.presentation().displayNamespace()).isEqualTo("douyin");
            assertThat(descriptor.legacyAliases()).isEmpty();
        });
        assertThat(plugin.scheduledSourceDescriptors())
                .filteredOn(descriptor -> descriptor.sourceType()
                        .equals(DouyinSourceTypes.ACCOUNT_FAVORITE_FOLDER))
                .singleElement()
                .satisfies(descriptor -> assertThat(descriptor.acquisitionModes())
                        .containsExactly(DownloadAcquisitionMode.SERIES_COLLECTION.code()));
    }

    @Test
    @DisplayName("下载类型 descriptor 声明取得模式与单项取消边界")
    void descriptorDeclaresAcquisitionAndCancellationBoundary() {
        DouyinPlugin plugin = new DouyinPlugin();
        var descriptor = plugin.downloadTypes().get(0);

        assertThat(descriptor.type()).isEqualTo("douyin");
        assertThat(descriptor.moduleUrl()).isEqualTo("/pixiv-douyin-download/douyin-queue-type.js");
        assertThat(descriptor.acquisitionModes()).containsExactly(
                DownloadAcquisitionMode.SINGLE_IMPORT,
                DownloadAcquisitionMode.USER_PROFILE,
                DownloadAcquisitionMode.SEARCH,
                DownloadAcquisitionMode.SERIES_COLLECTION,
                DownloadAcquisitionMode.QUICK);
        assertThat(descriptor.cancelSupported()).isTrue();
        assertThat(descriptor.filters()).isEmpty();
        assertThat(descriptor.settings()).isEmpty();
        assertThat(plugin.uiSlots()).extracting(slot -> slot.target()).containsExactly(
                "kind-option-quick",
                "kind-option-user",
                "quick-actions-bookmarks",
                "quick-actions-mine",
                "import-hint",
                "cookie-tools");
    }

    @Test
    @DisplayName("GUI 配置页贡献抖音下载设置字段")
    void contributesGuiConfigFields() {
        DouyinPlugin plugin = new DouyinPlugin();

        GuiConfigContribution contribution = plugin.guiConfigContributions().get(0);

        assertThat(contribution.groups()).singleElement().satisfies(group -> {
            assertThat(group.groupId()).isEqualTo("douyin");
            assertThat(group.labelKey()).isEqualTo("settings.download.title");
            assertThat(group.i18nNamespace()).isEqualTo("douyin");
            assertThat(group.visibleInTabs()).isTrue();
        });
        assertThat(contribution.fields()).extracting(GuiConfigFieldContribution::key)
                .containsExactly(
                        DouyinPluginSettingsService.KEY_DOWNLOAD_DIRECTORY,
                        DouyinPluginSettingsService.KEY_PROXY_MODE,
                        DouyinPluginSettingsService.KEY_PROXY_HOST,
                        DouyinPluginSettingsService.KEY_PROXY_PORT,
                        DouyinPluginSettingsService.KEY_INCLUDE_COVER);
        assertThat(contribution.fields())
                .filteredOn(field -> field.key().equals(DouyinPluginSettingsService.KEY_DOWNLOAD_DIRECTORY))
                .singleElement()
                .satisfies(field -> {
                    assertThat(field.groupId()).isEqualTo("douyin");
                    assertThat(field.type()).isEqualTo(GuiConfigFieldType.PATH_DIR);
                    assertThat(field.requiresRestart()).isFalse();
                });
        assertThat(contribution.fields())
                .filteredOn(field -> field.key().equals(DouyinPluginSettingsService.KEY_PROXY_MODE))
                .singleElement()
                .satisfies(field -> {
                    assertThat(field.type()).isEqualTo(GuiConfigFieldType.ENUM);
                    assertThat(field.enumValues()).containsExactly("inherit", "proxy", "custom", "direct");
                    assertThat(field.enumValueLabelKeys()).containsKeys("inherit", "proxy", "custom", "direct");
                    assertThat(field.visibleWhen()).isEmpty();
                    assertThat(field.requiresRestart()).isFalse();
                });
        assertThat(contribution.fields())
                .filteredOn(field -> field.key().equals(DouyinPluginSettingsService.KEY_PROXY_HOST))
                .singleElement()
                .satisfies(field -> {
                    assertThat(field.type()).isEqualTo(GuiConfigFieldType.STRING);
                    assertThat(field.visibleWhen()).containsExactly(
                            GuiConfigCondition.equalsTo(DouyinPluginSettingsService.KEY_PROXY_MODE, "custom"));
                    assertThat(field.requiresRestart()).isFalse();
                });
        assertThat(contribution.fields())
                .filteredOn(field -> field.key().equals(DouyinPluginSettingsService.KEY_PROXY_PORT))
                .singleElement()
                .satisfies(field -> {
                    assertThat(field.type()).isEqualTo(GuiConfigFieldType.PORT);
                    assertThat(field.visibleWhen()).containsExactly(
                            GuiConfigCondition.equalsTo(DouyinPluginSettingsService.KEY_PROXY_MODE, "custom"));
                    assertThat(field.requiresRestart()).isFalse();
                });
        assertThat(contribution.fields())
                .filteredOn(field -> field.key().equals(DouyinPluginSettingsService.KEY_INCLUDE_COVER))
                .singleElement()
                .satisfies(field -> {
                    assertThat(field.type()).isEqualTo(GuiConfigFieldType.BOOL);
                    assertThat(field.defaultValue()).isEqualTo("false");
                    assertThat(field.requiresRestart()).isFalse();
                });
    }

    @Test
    @DisplayName("插件入口直接声明 route/static/navigation/i18n/uiSlot/downloadType 契约")
    void pluginDeclaresAllWebContributionsWithoutHostRegistryDependency() {
        DouyinPlugin plugin = new DouyinPlugin();
        List<String> adminGalleryPaths = List.of(
                "/pixiv-douyin-gallery.html",
                "/pixiv-douyin-gallery/pixiv-douyin-gallery.css",
                "/pixiv-douyin.html",
                "/pixiv-douyin/douyin-core.js",
                "/api/douyin/gallery/projections",
                "/api/douyin/history/7351/media/0");
        List<String> visitorAcquisitionPaths = List.of(
                "/api/douyin/me/favorites",
                "/api/douyin/me/favorite-folders",
                "/api/douyin/me/favorite-folders/folder-a/works");

        assertThat(routePolicy(plugin, "/api/douyin/resolve")).isEqualTo(AccessPolicy.VISITOR);
        assertThat(routePolicy(plugin, "/api/douyin/user/sec-user/liked/ids"))
                .isEqualTo(AccessPolicy.VISITOR);
        assertThat(routePolicy(plugin, "/api/douyin/me/favorite-collections"))
                .isEqualTo(AccessPolicy.VISITOR);
        for (String path : visitorAcquisitionPaths) {
            assertThat(routePolicy(plugin, path))
                    .as("%s 使用 VISITOR 访问策略", path)
                    .isEqualTo(AccessPolicy.VISITOR);
        }
        for (String path : adminGalleryPaths) {
            assertThat(routePolicy(plugin, path))
                    .as("%s 使用 ADMIN 访问策略", path)
                    .isEqualTo(AccessPolicy.ADMIN);
        }
        var douyinStatics = plugin.staticResources();
        assertThat(douyinStatics).hasSize(5);
        assertThat(douyinStatics).extracting(resource -> resource.publicPathPrefix())
                .containsExactlyInAnyOrder(
                        "/pixiv-douyin-gallery.html",
                        "/pixiv-douyin.html",
                        "/pixiv-douyin-gallery/",
                        "/pixiv-douyin/",
                        "/pixiv-douyin-download/");
        assertThat(douyinStatics).filteredOn(resource -> resource.exactFile())
                .extracting(resource -> resource.publicPathPrefix())
                .containsExactlyInAnyOrder(
                        "/pixiv-douyin-gallery.html",
                        "/pixiv-douyin.html");
        assertThat(plugin.navigation())
                .singleElement()
                .satisfies(navigation -> {
                    assertThat(navigation.id()).isEqualTo("douyin-gallery-type-switch");
                    assertThat(navigation.placements())
                            .containsExactly(NavigationPlacements.GALLERY_TYPE_SWITCH);
                    assertThat(navigation.visibleTo()).isEqualTo(AccessPolicy.ADMIN);
                    assertThat(navigation.href())
                            .isEqualTo("/pixiv-douyin-gallery.html?view=all");
                });
        assertThat(plugin.i18n()).singleElement().satisfies(i18n -> {
            assertThat(i18n.namespace()).isEqualTo("douyin");
            assertThat(i18n.baseName()).isEqualTo("i18n.web.douyin");
        });
        assertThat(plugin.uiSlots()).hasSize(6);
        assertThat(plugin.downloadTypes()).singleElement()
                .satisfies(descriptor -> assertThat(descriptor.type()).isEqualTo("douyin"));
    }

    private static AccessPolicy routePolicy(DouyinPlugin plugin, String path) {
        return plugin.routes().stream()
                .filter(route -> route.matches(path))
                .findFirst()
                .map(route -> route.accessPolicy())
                .orElseThrow();
    }
}
