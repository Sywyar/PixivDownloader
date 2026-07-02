package top.sywyar.pixivdownload.gui.entry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.gui.i18n.GuiMessages;
import top.sywyar.pixivdownload.plugin.BuiltInPlugins;
import top.sywyar.pixivdownload.plugin.PluginToggleProperties;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;

import java.util.Locale;

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

        GuiWebEntrySnapshot snapshot = GuiWebEntryContributionAggregator.from(
                new PluginRegistry(BuiltInPlugins.createAll()));

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
        return new PluginRegistry(BuiltInPlugins.createAll(), toggles);
    }
}
