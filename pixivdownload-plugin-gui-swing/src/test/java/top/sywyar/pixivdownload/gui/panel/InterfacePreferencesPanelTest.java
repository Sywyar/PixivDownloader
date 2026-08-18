package top.sywyar.pixivdownload.gui.panel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.guitheme.GuiSwingPlugin;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiContext;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiProvider;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiSession;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("界面配置的桌面 UI 提供者选项")
class InterfacePreferencesPanelTest {

    @Test
    @DisplayName("下拉框只接收插件提供者并使用宿主验证后的 ID")
    void optionsComeOnlyFromVerifiedDesktopProviderPlugins() {
        List<InterfacePreferencesPanel.ProviderOption> options = InterfacePreferencesPanel.providerOptions(List.of(
                source("verified-ui", new GuiSwingPlugin()),
                source("plain-plugin", new PlainPlugin())));

        assertThat(options).singleElement().satisfies(option -> {
            assertThat(option.id()).isEqualTo("verified-ui");
            assertThat(option.label()).isEqualTo("verified-ui");
            assertThat(option.defaultProvider()).isTrue();
        });
    }

    @Test
    @DisplayName("配置 ID 不可用时回退默认插件且不生成占位选项")
    void unavailableConfiguredIdFallsBackWithoutPlaceholderOption() {
        List<InterfacePreferencesPanel.ProviderOption> options = InterfacePreferencesPanel.providerOptions(List.of(
                source("default-ui", new ProviderPlugin("default-ui", true)),
                source("alternate-ui", new ProviderPlugin("alternate-ui", false))));

        InterfacePreferencesPanel.ProviderOption selected =
                InterfacePreferencesPanel.selectedProviderOption(options, "removed-ui");

        assertThat(selected.id()).isEqualTo("default-ui");
        assertThat(options).extracting(InterfacePreferencesPanel.ProviderOption::id)
                .containsExactly("default-ui", "alternate-ui")
                .doesNotContain("removed-ui");
    }

    private static DesktopUiContext.PluginSource source(String verifiedId, PixivFeaturePlugin plugin) {
        return new DesktopUiContext.PluginSource(
                verifiedId, false, plugin, InterfacePreferencesPanelTest.class.getClassLoader());
    }

    private static class PlainPlugin implements PixivFeaturePlugin {
        @Override public String id() { return "plain-plugin"; }
        @Override public String displayName() { return "plugin.name"; }
        @Override public String description() { return "plugin.summary"; }
        @Override public PluginKind kind() { return PluginKind.FEATURE; }
    }

    private static final class ProviderPlugin extends PlainPlugin implements DesktopUiProvider {
        private final String id;
        private final boolean defaultProvider;

        private ProviderPlugin(String id, boolean defaultProvider) {
            this.id = id;
            this.defaultProvider = defaultProvider;
        }

        @Override public String id() { return id; }
        @Override public boolean defaultProvider() { return defaultProvider; }
        @Override public DesktopUiSession launch(DesktopUiContext context) {
            return new DesktopUiSession() {
                @Override public void activate() { }
                @Override public void showMessage(MessageLevel level, String title, String message) { }
            };
        }
    }
}
