package top.sywyar.pixivdownload.gui.onboarding;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.gui.i18n.GuiMessages;
import top.sywyar.pixivdownload.plugin.BuiltInPlugins;
import top.sywyar.pixivdownload.plugin.PluginToggleProperties;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GUI 欢迎页步骤 contribution 聚合")
class GuiOnboardingContributionAggregatorTest {

    @AfterEach
    void clearLocale() {
        GuiMessages.clearLocaleOverride();
    }

    @Test
    @DisplayName("启用 gallery 时欢迎页步骤出现且文案由 gallery namespace 解析")
    void galleryStepAppearsWhenGalleryEnabled() {
        GuiMessages.setLocale(Locale.SIMPLIFIED_CHINESE);

        GuiOnboardingSnapshot snapshot = GuiOnboardingContributionAggregator.from(
                new PluginRegistry(BuiltInPlugins.createAll()));

        assertThat(snapshot.steps())
                .filteredOn(step -> step.stepId().equals("local-gallery-guide"))
                .singleElement()
                .satisfies(step -> {
                    assertThat(step.pluginId()).isEqualTo("gallery");
                    assertThat(step.title()).isEqualTo("5. 浏览已下载作品");
                    assertThat(step.actionLabel()).isEqualTo("打开画廊");
                    assertThat(step.actionHref()).isEqualTo("/pixiv-gallery.html");
                    assertThat(step.completionKey()).isEqualTo("local-gallery-guide");
                });
        assertThat(snapshot.firstStep()).isPresent()
                .get()
                .extracting(GuiOnboardingStepSpec::stepId)
                .isEqualTo("local-gallery-guide");
    }

    @Test
    @DisplayName("禁用 gallery 时欢迎页步骤自然缺席")
    void galleryStepDisappearsWhenGalleryDisabled() {
        GuiOnboardingSnapshot snapshot = GuiOnboardingContributionAggregator.from(registryDisablingGallery());

        assertThat(snapshot.steps()).extracting(GuiOnboardingStepSpec::stepId)
                .doesNotContain("local-gallery-guide");
    }

    private static PluginRegistry registryDisablingGallery() {
        PluginToggleProperties toggles = new PluginToggleProperties();
        PluginToggleProperties.PluginToggle gallery = new PluginToggleProperties.PluginToggle();
        gallery.setEnabled(false);
        toggles.put("gallery", gallery);
        return new PluginRegistry(BuiltInPlugins.createAll(), toggles);
    }
}
