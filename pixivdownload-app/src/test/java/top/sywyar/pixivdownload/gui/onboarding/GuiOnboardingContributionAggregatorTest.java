package top.sywyar.pixivdownload.gui.onboarding;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.gui.i18n.GuiMessages;
import top.sywyar.pixivdownload.plugin.PluginToggleProperties;
import top.sywyar.pixivdownload.plugin.api.gui.GuiOnboardingStepContribution;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;

import java.util.List;
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

        GuiOnboardingSnapshot snapshot = GuiOnboardingContributionAggregator.from(registryWithGallery());

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
        return new PluginRegistry(List.of(new GalleryGuiPlugin()), toggles);
    }

    private static PluginRegistry registryWithGallery() {
        return new PluginRegistry(List.of(new GalleryGuiPlugin()));
    }

    private static final class GalleryGuiPlugin implements PixivFeaturePlugin {
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
        public List<GuiOnboardingStepContribution> guiOnboardingSteps() {
            return List.of(new GuiOnboardingStepContribution(
                    "gallery",
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
    }
}
