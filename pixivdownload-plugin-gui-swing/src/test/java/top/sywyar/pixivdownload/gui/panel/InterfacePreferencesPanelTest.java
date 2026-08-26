package top.sywyar.pixivdownload.gui.panel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiPluginSnapshot;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Swing 界面配置提供者选项")
class InterfacePreferencesPanelTest {

    @Test
    @DisplayName("只显示验证快照中的桌面 UI 提供者")
    void optionsComeOnlyFromVerifiedDesktopProviderSnapshots() {
        List<InterfacePreferencesPanel.ProviderOption> options = InterfacePreferencesPanel.providerOptions(
                List.of(snapshot("gui-compose", true), snapshot("plain-plugin", false)),
                text -> "GUI: " + text.fallback());

        assertThat(options).singleElement().satisfies(option -> {
            assertThat(option.id()).isEqualTo("gui-compose");
            assertThat(option.label()).isEqualTo("GUI: gui-compose");
        });
    }

    @Test
    @DisplayName("配置 ID 不可用时回退当前实际提供者且不生成占位项")
    void unavailableConfiguredIdFallsBackWithoutPlaceholderOption() {
        List<InterfacePreferencesPanel.ProviderOption> options = InterfacePreferencesPanel.providerOptions(
                List.of(snapshot("gui-compose", true), snapshot("gui-swing", true)),
                text -> text.fallback());

        InterfacePreferencesPanel.ProviderOption selected =
                InterfacePreferencesPanel.selectedProviderOption(options, "removed-ui", "gui-compose");

        assertThat(selected.id()).isEqualTo("gui-compose");
        assertThat(options).extracting(InterfacePreferencesPanel.ProviderOption::id)
                .containsExactly("gui-compose", "gui-swing")
                .doesNotContain("removed-ui");
    }

    private static DesktopUiPluginSnapshot snapshot(String id, boolean desktopUiProvider) {
        return new DesktopUiPluginSnapshot(id, false, id, 1L, desktopUiProvider,
                null, "", List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
