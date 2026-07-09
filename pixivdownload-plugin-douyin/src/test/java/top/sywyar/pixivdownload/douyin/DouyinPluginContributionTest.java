package top.sywyar.pixivdownload.douyin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.douyin.settings.DouyinPluginSettingsService;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigCondition;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldType;
import top.sywyar.pixivdownload.i18n.WebI18nBundleRegistry;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.DownloadAcquisitionMode;
import top.sywyar.pixivdownload.plugin.api.web.HttpMethod;
import top.sywyar.pixivdownload.plugin.api.web.QueueTypeContribution;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.QueueTypeRegistry;
import top.sywyar.pixivdownload.plugin.registry.RouteAccessRegistry;
import top.sywyar.pixivdownload.plugin.registry.StaticResourceRegistry;
import top.sywyar.pixivdownload.plugin.registry.WebUiSlotRegistry;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DouyinPlugin 外置贡献契约")
class DouyinPluginContributionTest {

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
                DownloadAcquisitionMode.SERIES_COLLECTION);
        assertThat(queueType.descriptor().queue().clearAll()).isTrue();
        assertThat(queueType.descriptor().queue().clearForOwner()).isTrue();
        assertThat(queueType.descriptor().queue().cancel()).isTrue();
        assertThat(queueType.descriptor().schedule().saveable()).isFalse();
        assertThat(queueType.descriptor().schedule().suspendWhenExecutorMissing()).isTrue();
        assertThat(queueType.descriptor().filters()).containsExactly("douyin-public");
        assertThat(queueType.descriptor().settings()).isEmpty();
        assertThat(queueType.descriptor().uiSlots()).containsExactly(
                "import-hint",
                "cookie-tools");
        assertThat(queueType.descriptor().gallery().unifiedGallery()).isTrue();
        assertThat(queueType.descriptor().gallery().independentPage()).isFalse();
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
                        DouyinPluginSettingsService.KEY_PROXY_PORT);
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
    }

    @Test
    @DisplayName("注册后可发现，注销后 route/static/i18n/uiSlot/queueType 均撤销")
    void registriesRegisterAndUnregisterDouyinContributions() {
        PluginRegistry pluginRegistry = new PluginRegistry(List.of(new DouyinPlugin()));
        RouteAccessRegistry routes = new RouteAccessRegistry(pluginRegistry);
        StaticResourceRegistry statics = new StaticResourceRegistry(pluginRegistry);
        WebI18nBundleRegistry i18n = new WebI18nBundleRegistry(pluginRegistry);
        WebUiSlotRegistry slots = new WebUiSlotRegistry(pluginRegistry);
        QueueTypeRegistry queueTypes = new QueueTypeRegistry(pluginRegistry);

        assertThat(routes.isDeclared("/api/douyin/resolve", HttpMethod.GET)).isTrue();
        assertThat(routes.resolve("/api/douyin/resolve", HttpMethod.GET))
                .hasValueSatisfying(route -> assertThat(route.route().accessPolicy()).isEqualTo(AccessPolicy.VISITOR));
        assertThat(routes.resolve("/api/douyin/history/7351/media/0", HttpMethod.GET))
                .hasValueSatisfying(route -> assertThat(route.route().accessPolicy()).isEqualTo(AccessPolicy.ADMIN));
        assertThat(statics.resources()).anyMatch(resource -> resource.pluginId().equals("douyin"));
        assertThat(i18n.resolve("douyin")).isNotNull();
        assertThat(slots.slots()).filteredOn(slot -> slot.pluginId().equals("douyin")).hasSize(2);
        assertThat(queueTypes.queueTypes()).singleElement()
                .satisfies(registered -> assertThat(registered.queueType().type()).isEqualTo("douyin"));

        routes.unregister("douyin");
        statics.unregister("douyin");
        i18n.unregister("douyin");
        slots.unregister("douyin");
        queueTypes.unregister("douyin");

        assertThat(routes.isDeclared("/api/douyin/resolve", HttpMethod.GET)).isFalse();
        assertThat(statics.resources()).noneMatch(resource -> resource.pluginId().equals("douyin"));
        assertThat(i18n.resolve("douyin")).isNull();
        assertThat(slots.slots()).noneMatch(slot -> slot.pluginId().equals("douyin"));
        assertThat(queueTypes.queueTypes()).isEmpty();
    }
}
