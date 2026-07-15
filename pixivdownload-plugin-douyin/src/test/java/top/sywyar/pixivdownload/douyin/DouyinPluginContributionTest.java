package top.sywyar.pixivdownload.douyin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.douyin.settings.DouyinPluginSettingsService;
import top.sywyar.pixivdownload.douyin.schedule.codec.DouyinScheduleCodec;
import top.sywyar.pixivdownload.douyin.schedule.source.DouyinScheduledSourceDescriptors;
import top.sywyar.pixivdownload.douyin.source.DouyinSourceTypes;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigCondition;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldType;
import top.sywyar.pixivdownload.i18n.WebI18nBundleRegistry;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.DownloadAcquisitionMode;
import top.sywyar.pixivdownload.plugin.api.web.HttpMethod;
import top.sywyar.pixivdownload.plugin.api.web.NavigationPlacements;
import top.sywyar.pixivdownload.plugin.api.web.QueueTypeContribution;
import top.sywyar.pixivdownload.plugin.registry.NavigationRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.QueueTypeRegistry;
import top.sywyar.pixivdownload.plugin.registry.RouteAccessRegistry;
import top.sywyar.pixivdownload.plugin.registry.StaticResourceRegistry;
import top.sywyar.pixivdownload.plugin.registry.WebUiSlotRegistry;

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
    @DisplayName("下载类型 descriptor 声明完整能力与画廊边界")
    void descriptorDeclaresCapabilitiesAndGalleryBoundary() {
        DouyinPlugin plugin = new DouyinPlugin();
        QueueTypeContribution queueType = plugin.queueTypes().get(0);

        assertThat(queueType.pluginId()).isEqualTo("douyin");
        assertThat(queueType.type()).isEqualTo("douyin");
        assertThat(queueType.moduleUrl()).isEqualTo("/pixiv-douyin-download/douyin-queue-type.js");
        assertThat(queueType.descriptor().acquisitionModes()).containsExactly(
                DownloadAcquisitionMode.SINGLE_IMPORT,
                DownloadAcquisitionMode.USER_PROFILE,
                DownloadAcquisitionMode.SEARCH,
                DownloadAcquisitionMode.SERIES_COLLECTION,
                DownloadAcquisitionMode.QUICK);
        assertThat(queueType.descriptor().queue().clearAll()).isTrue();
        assertThat(queueType.descriptor().queue().clearForOwner()).isTrue();
        assertThat(queueType.descriptor().queue().cancel()).isTrue();
        assertThat(queueType.descriptor().schedule().saveable()).isTrue();
        assertThat(queueType.descriptor().schedule().sourceSerializable()).isTrue();
        assertThat(queueType.descriptor().schedule().suspendWhenExecutorMissing()).isTrue();
        assertThat(queueType.descriptor().filters()).isEmpty();
        assertThat(queueType.descriptor().settings()).isEmpty();
        assertThat(queueType.descriptor().uiSlots()).containsExactly(
                "kind-option-quick",
                "quick-actions-bookmarks",
                "quick-actions-mine",
                "import-hint",
                "cookie-tools");
        assertThat(queueType.descriptor().gallery().unifiedGallery()).isTrue();
        assertThat(queueType.descriptor().gallery().independentPage()).isTrue();
        assertThat(queueType.descriptor().gallery().reasonI18nKey()).isNull();
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
    @DisplayName("注册后可发现，注销后 route/static/navigation/i18n/uiSlot/queueType 均撤销")
    void registriesRegisterAndUnregisterDouyinContributions() {
        PluginRegistry pluginRegistry = new PluginRegistry(List.of(new DouyinPlugin()));
        RouteAccessRegistry routes = new RouteAccessRegistry(pluginRegistry);
        StaticResourceRegistry statics = new StaticResourceRegistry(pluginRegistry);
        NavigationRegistry navigation = new NavigationRegistry(pluginRegistry);
        WebI18nBundleRegistry i18n = new WebI18nBundleRegistry(pluginRegistry);
        WebUiSlotRegistry slots = new WebUiSlotRegistry(pluginRegistry);
        QueueTypeRegistry queueTypes = new QueueTypeRegistry(pluginRegistry);
        List<String> adminGalleryPaths = List.of(
                "/pixiv-douyin-gallery.html",
                "/pixiv-douyin-gallery/pixiv-douyin-gallery.css",
                "/pixiv-douyin.html",
                "/pixiv-douyin/douyin-core.js",
                "/api/douyin/gallery/projections",
                "/api/douyin/history/7351/media/0",
                "/api/douyin/me/favorite-folders",
                "/api/douyin/me/favorite-folders/folder-a/works");

        assertThat(routes.isDeclared("/api/douyin/resolve", HttpMethod.GET)).isTrue();
        assertThat(routes.resolve("/api/douyin/resolve", HttpMethod.GET))
                .hasValueSatisfying(route -> assertThat(route.route().accessPolicy()).isEqualTo(AccessPolicy.VISITOR));
        assertThat(routes.resolve("/api/douyin/me/favorite-collections", HttpMethod.GET))
                .hasValueSatisfying(route -> assertThat(route.route().accessPolicy()).isEqualTo(AccessPolicy.VISITOR));
        for (String path : adminGalleryPaths) {
            assertThat(routes.resolve(path, HttpMethod.GET))
                    .as("%s 使用 ADMIN 访问策略", path)
                    .hasValueSatisfying(route ->
                            assertThat(route.route().accessPolicy()).isEqualTo(AccessPolicy.ADMIN));
        }
        var douyinStatics = statics.resources().stream()
                .filter(resource -> resource.pluginId().equals("douyin"))
                .toList();
        assertThat(douyinStatics).hasSize(5);
        assertThat(douyinStatics).extracting(resource -> resource.contribution().publicPathPrefix())
                .containsExactlyInAnyOrder(
                        "/pixiv-douyin-gallery.html",
                        "/pixiv-douyin.html",
                        "/pixiv-douyin-gallery/",
                        "/pixiv-douyin/",
                        "/pixiv-douyin-download/");
        assertThat(douyinStatics).filteredOn(resource -> resource.contribution().exactFile())
                .extracting(resource -> resource.contribution().publicPathPrefix())
                .containsExactlyInAnyOrder(
                        "/pixiv-douyin-gallery.html",
                        "/pixiv-douyin.html");
        assertThat(navigation.navigation())
                .filteredOn(registered -> registered.pluginId().equals("douyin"))
                .singleElement()
                .satisfies(registered -> {
                    assertThat(registered.navigation().id()).isEqualTo("douyin-gallery-type-switch");
                    assertThat(registered.navigation().placements())
                            .containsExactly(NavigationPlacements.GALLERY_TYPE_SWITCH);
                    assertThat(registered.navigation().visibleTo()).isEqualTo(AccessPolicy.ADMIN);
                    assertThat(registered.navigation().href())
                            .isEqualTo("/pixiv-douyin-gallery.html?view=all");
                });
        assertThat(i18n.resolve("douyin")).isNotNull();
        assertThat(slots.slots()).filteredOn(slot -> slot.pluginId().equals("douyin")).hasSize(5);
        assertThat(queueTypes.queueTypes()).singleElement()
                .satisfies(registered -> assertThat(registered.queueType().type()).isEqualTo("douyin"));

        routes.unregister("douyin");
        statics.unregister("douyin");
        navigation.unregister("douyin");
        i18n.unregister("douyin");
        slots.unregister("douyin");
        queueTypes.unregister("douyin");

        assertThat(routes.isDeclared("/api/douyin/resolve", HttpMethod.GET)).isFalse();
        for (String path : adminGalleryPaths) {
            assertThat(routes.isDeclared(path, HttpMethod.GET))
                    .as("注销后 %s 不再声明", path)
                    .isFalse();
        }
        assertThat(statics.resources()).noneMatch(resource -> resource.pluginId().equals("douyin"));
        assertThat(navigation.navigation()).noneMatch(registered -> registered.pluginId().equals("douyin"));
        assertThat(i18n.resolve("douyin")).isNull();
        assertThat(slots.slots()).noneMatch(slot -> slot.pluginId().equals("douyin"));
        assertThat(queueTypes.queueTypes()).isEmpty();
    }
}
