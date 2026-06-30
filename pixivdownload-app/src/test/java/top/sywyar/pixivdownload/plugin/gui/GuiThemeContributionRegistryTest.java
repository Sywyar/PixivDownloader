package top.sywyar.pixivdownload.plugin.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.PluginRegistry;
import top.sywyar.pixivdownload.plugin.api.gui.GuiThemeAppearance;
import top.sywyar.pixivdownload.plugin.api.gui.GuiThemeContribution;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("GUI 主题 contribution 注册中心")
class GuiThemeContributionRegistryTest {

    private static GuiThemeContributionRegistry emptyRegistry() {
        return new GuiThemeContributionRegistry(new PluginRegistry(List.of()));
    }

    private static GuiThemeContribution theme(String id) {
        return new GuiThemeContribution(id, locale -> id, GuiThemeAppearance.UNKNOWN, () -> {
        });
    }

    private static PixivFeaturePlugin plugin(String pluginId, GuiThemeContribution... themes) {
        return new PixivFeaturePlugin() {
            @Override
            public String id() {
                return pluginId;
            }

            @Override
            public String displayName() {
                return pluginId + ".label";
            }

            @Override
            public String description() {
                return pluginId + ".summary";
            }

            @Override
            public PluginKind kind() {
                return PluginKind.FEATURE;
            }

            @Override
            public List<GuiThemeContribution> guiThemes() {
                return List.of(themes);
            }
        };
    }

    @Test
    @DisplayName("构造时从活动插件收集主题贡献，并按全局 themeId 建立不可变索引")
    void collectsThemesFromActivePlugins() {
        GuiThemeContributionRegistry registry = new GuiThemeContributionRegistry(new PluginRegistry(List.of(
                plugin("alpha", theme("alpha.light")),
                plugin("beta", theme("beta.dark")))));

        assertThat(registry.themes()).extracting(GuiThemeContributionRegistry.RegisteredTheme::pluginId)
                .containsExactly("alpha", "beta");
        assertThat(registry.themesById()).containsOnlyKeys("alpha.light", "beta.dark");
        assertThat(registry.find("beta.dark")).isPresent()
                .get()
                .extracting(GuiThemeContributionRegistry.RegisteredTheme::pluginId)
                .isEqualTo("beta");
        assertThatThrownBy(() -> registry.themesById().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("register → unregister → 再 register 后快照与首次注册一致")
    void registerUnregisterRoundTrip() {
        GuiThemeContributionRegistry registry = emptyRegistry();
        List<GuiThemeContribution> themes = List.of(theme("alpha.light"), theme("alpha.dark"));

        registry.register("alpha", themes);
        List<GuiThemeContributionRegistry.RegisteredTheme> first = registry.themes();
        registry.unregister("alpha");
        assertThat(registry.themes()).isEmpty();
        assertThat(registry.themesById()).isEmpty();

        registry.register("alpha", themes);
        assertThat(registry.themes()).isEqualTo(first);
        assertThat(registry.themesById()).containsOnlyKeys("alpha.light", "alpha.dark");
    }

    @Test
    @DisplayName("重复 themeId 产生明确诊断并拒绝任选")
    void duplicateThemeIdRejected() {
        assertThatThrownBy(() -> new GuiThemeContributionRegistry(new PluginRegistry(List.of(
                plugin("alpha", theme("shared.theme")),
                plugin("beta", theme("shared.theme"))))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shared.theme")
                .hasMessageContaining("alpha")
                .hasMessageContaining("beta");
    }

    @Test
    @DisplayName("同一插件内重复 themeId 同样拒绝")
    void duplicateThemeIdWithinPluginRejected() {
        GuiThemeContributionRegistry registry = emptyRegistry();

        assertThatThrownBy(() -> registry.register("alpha",
                List.of(theme("alpha.shared"), theme("alpha.shared"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("alpha.shared")
                .hasMessageContaining("alpha");
    }

    @Test
    @DisplayName("非法输入拒绝：pluginId 非空、贡献列表非空、贡献不可为 null")
    void invalidInputRejected() {
        GuiThemeContributionRegistry registry = emptyRegistry();

        assertThatThrownBy(() -> registry.register(" ", List.of(theme("alpha.light"))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.register("alpha", List.of()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.register("alpha", null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.register("alpha", java.util.Collections.singletonList(null)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("unregister 对未注册过主题的 pluginId 静默返回")
    void unregisterUnknownPluginIsSilent() {
        GuiThemeContributionRegistry registry = emptyRegistry();

        registry.unregister("never-registered");

        assertThat(registry.themes()).isEmpty();
        assertThat(registry.themesById()).isEmpty();
    }
}
