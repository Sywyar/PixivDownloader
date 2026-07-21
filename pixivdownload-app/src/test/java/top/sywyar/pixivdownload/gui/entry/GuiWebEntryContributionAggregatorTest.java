package top.sywyar.pixivdownload.gui.entry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.gui.i18n.GuiMessages;
import top.sywyar.pixivdownload.plugin.PluginToggleProperties;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;
import top.sywyar.pixivdownload.plugin.api.web.NavigationContribution;
import top.sywyar.pixivdownload.plugin.api.web.NavigationPlacements;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginSource;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GUI Web 入口 contribution 聚合")
class GuiWebEntryContributionAggregatorTest {

    @AfterEach
    void clearLocale() {
        GuiMessages.clearLocaleOverride();
    }

    @Test
    @DisplayName("启用 gallery 时状态页与托盘入口出现，文案由 gallery namespace 解析")
    void galleryEntriesAppearWhenGalleryEnabled() {
        GuiMessages.setLocale(Locale.SIMPLIFIED_CHINESE);

        GuiWebEntrySnapshot snapshot = GuiWebEntryContributionAggregator.from(registryWithGallery());

        assertThat(snapshot.statusActions())
                .filteredOn(action -> action.id().equals("gallery-gui-open"))
                .singleElement()
                .satisfies(action -> {
                    assertThat(action.pluginId()).isEqualTo("gallery");
                    assertThat(action.href()).isEqualTo("/pixiv-gallery.html");
                    assertThat(action.label()).isEqualTo("本地画廊");
                });
        assertThat(snapshot.trayActions())
                .extracting(GuiWebEntrySpec::id)
                .contains("gallery-gui-open");
    }

    @Test
    @DisplayName("重新聚合 Web 入口时状态页与托盘动作按当前 GUI 语言解析")
    void rebuiltWebEntriesUseCurrentGuiLocale() {
        GuiMessages.setLocale(Locale.SIMPLIFIED_CHINESE);
        GuiWebEntrySnapshot zhSnapshot = GuiWebEntryContributionAggregator.fromRegisteredPlugins(externalGallery());

        GuiMessages.setLocale(Locale.US);
        GuiWebEntrySnapshot enSnapshot = GuiWebEntryContributionAggregator.fromRegisteredPlugins(externalGallery());

        assertThat(action(zhSnapshot.statusActions()).label()).isEqualTo("本地画廊");
        assertThat(action(zhSnapshot.trayActions()).label()).isEqualTo("本地画廊");
        assertThat(action(enSnapshot.statusActions()).label()).isEqualTo("Local Gallery");
        assertThat(action(enSnapshot.trayActions()).label()).isEqualTo("Local Gallery");
    }

    @Test
    @DisplayName("流程专用或缺失的可见性策略逐条诊断丢弃，同插件合法 GUI 入口仍保留")
    void invalidVisibilityPoliciesAreIsolatedPerEntry() {
        PixivFeaturePlugin mixed = new GalleryGuiPlugin() {
            @Override
            public List<NavigationContribution> navigation() {
                return List.of(
                        guiEntry("bad-local", AccessPolicy.LOCAL),
                        guiEntry("bad-gui", AccessPolicy.GUI),
                        guiEntry("bad-actuator", AccessPolicy.ACTUATOR_PUBLIC),
                        guiEntry("bad-null", null),
                        guiEntry("valid-admin", AccessPolicy.ADMIN));
            }
        };

        GuiWebEntrySnapshot snapshot = GuiWebEntryContributionAggregator.from(
                new PluginRegistry(List.of(mixed)));

        assertThat(snapshot.statusActions())
                .extracting(GuiWebEntrySpec::id)
                .containsExactly("valid-admin");
        assertThat(snapshot.diagnostics())
                .filteredOn(diagnostic -> diagnostic.message().contains("visibleTo must support UI visibility"))
                .hasSize(4)
                .extracting(GuiWebEntryContributionDiagnostic::key)
                .containsExactlyInAnyOrder("bad-local", "bad-gui", "bad-actuator", "bad-null");
    }

    @Test
    @DisplayName("禁用 gallery 时状态页与托盘入口自然缺席")
    void galleryEntriesDisappearWhenGalleryDisabled() {
        GuiWebEntrySnapshot snapshot = GuiWebEntryContributionAggregator.from(registryDisablingGallery());

        assertThat(snapshot.statusActions()).extracting(GuiWebEntrySpec::id)
                .doesNotContain("gallery-gui-open");
        assertThat(snapshot.trayActions()).extracting(GuiWebEntrySpec::id)
                .doesNotContain("gallery-gui-open");
    }

    private static PluginRegistry registryDisablingGallery() {
        PluginToggleProperties toggles = new PluginToggleProperties();
        PluginToggleProperties.PluginToggle gallery = new PluginToggleProperties.PluginToggle();
        gallery.setEnabled(false);
        toggles.put("gallery", gallery);
        return new PluginRegistry(List.of(new GalleryGuiPlugin()), toggles);
    }

    private static PluginRegistry registryWithGallery() {
        return new PluginRegistry(List.of(new GalleryGuiPlugin()));
    }

    private static List<PluginRegistry.RegisteredPlugin> externalGallery() {
        return List.of(new PluginRegistry.RegisteredPlugin(
                new GalleryGuiPlugin(),
                PluginSource.EXTERNAL,
                GuiWebEntryContributionAggregatorTest.class.getClassLoader()));
    }

    private static GuiWebEntrySpec action(List<GuiWebEntrySpec> actions) {
        return actions.stream()
                .filter(action -> action.id().equals("gallery-gui-open"))
                .findFirst()
                .orElseThrow();
    }

    private static NavigationContribution guiEntry(String id, AccessPolicy visibleTo) {
        return new NavigationContribution(
                id, NavigationPlacements.GUI_STATUS_ACTIONS,
                "gallery", "gui.action.open", "/pixiv-gallery.html", "images", visibleTo, 33);
    }

    private static class GalleryGuiPlugin implements PixivFeaturePlugin {
        @Override
        public String id() {
            return "gallery";
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
        public PluginKind kind() {
            return PluginKind.FEATURE;
        }

        @Override
        public List<I18nContribution> i18n() {
            return List.of(new I18nContribution("gallery", "i18n.web.gallery_test"));
        }

        @Override
        public List<NavigationContribution> navigation() {
            return List.of(new NavigationContribution(
                    "gallery-gui-open",
                    Set.of(NavigationPlacements.GUI_STATUS_ACTIONS, NavigationPlacements.GUI_TRAY_ACTIONS),
                    "gallery", "gui.action.open", "/pixiv-gallery.html", "images",
                    AccessPolicy.INVITED_GUEST, 33));
        }
    }
}
