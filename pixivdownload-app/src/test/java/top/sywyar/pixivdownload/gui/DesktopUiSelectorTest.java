package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiContext;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiProvider;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiSession;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DesktopUiSelectorTest {
    @Test
    void blankConfigurationSelectsComposeDefault() {
        Provider swing = new Provider("gui-swing", false);
        Provider compose = new Provider("gui-compose", true);

        assertThat(DesktopUiSelector.select("", List.of(swing, compose)).provider()).isSameAs(compose);
    }

    @Test
    void explicitProviderWinsOverDefault() {
        Provider swing = new Provider("gui-swing", false);
        Provider compose = new Provider("gui-compose", true);
        assertThat(DesktopUiSelector.select("gui-swing", List.of(swing, compose)).provider()).isSameAs(swing);
    }

    @Test
    void unavailableExplicitProviderFallsBackToSingleDefaultWithDiagnostic() {
        Provider compose = new Provider("gui-compose", true);
        var selection = DesktopUiSelector.select("missing", List.of(compose));
        assertThat(selection.provider()).isSameAs(compose);
        assertThat(selection.diagnostic()).contains("missing", "gui-compose");
    }

    @Test
    void missingDefaultProviderFallsBackToOnlyRemainingProvider() {
        Provider swing = new Provider("gui-swing", false);
        assertThat(DesktopUiSelector.select("gui-compose", List.of(swing)).provider()).isSameAs(swing);
    }

    @Test
    void ambiguousProvidersFailClosed() {
        assertThatThrownBy(() -> DesktopUiSelector.select("", List.of(
                new Provider("one", false), new Provider("two", false))))
                .isInstanceOf(IllegalStateException.class);
    }

    private record Provider(String id, boolean defaultProvider) implements DesktopUiProvider {
        @Override public DesktopUiSession launch(DesktopUiContext context) { throw new UnsupportedOperationException(); }
    }
}
