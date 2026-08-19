package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiContext;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiProvider;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiSession;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DesktopUiSelectorTest {
    @Test
    void explicitProviderWinsOverDefault() {
        Provider swing = new Provider("gui-swing", true);
        Provider compose = new Provider("gui-compose", false);
        assertThat(DesktopUiSelector.select("gui-compose", List.of(swing, compose)).provider()).isSameAs(compose);
    }

    @Test
    void unavailableExplicitProviderFallsBackToSingleDefaultWithDiagnostic() {
        Provider swing = new Provider("gui-swing", true);
        var selection = DesktopUiSelector.select("missing", List.of(swing));
        assertThat(selection.provider()).isSameAs(swing);
        assertThat(selection.diagnostic()).contains("missing", "gui-swing");
    }

    @Test
    void ambiguousProvidersFailClosed() {
        assertThatThrownBy(() -> DesktopUiSelector.select("", List.of(
                new Provider("one", false), new Provider("two", false))))
                .isInstanceOf(IllegalStateException.class);
    }

    private record Provider(String id, boolean defaultProvider) implements DesktopUiProvider {
        @Override public Set<DesktopUiNode.Kind> supportedNodeKinds() {
            return EnumSet.allOf(DesktopUiNode.Kind.class);
        }
        @Override public DesktopUiSession launch(DesktopUiContext context) { throw new UnsupportedOperationException(); }
    }
}
