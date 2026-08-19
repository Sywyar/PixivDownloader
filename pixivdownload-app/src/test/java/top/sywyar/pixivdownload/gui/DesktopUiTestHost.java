package top.sywyar.pixivdownload.gui;

import top.sywyar.pixivdownload.guiswing.SwingHost;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiCapability;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiContext;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiModel;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiDocument;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** Installs the real app desktop host for GUI tests migrated from the app module. */
public final class DesktopUiTestHost {
    private DesktopUiTestHost() {
    }

    public static synchronized void ensureInstalled() {
        if (SwingHost.installed()) {
            return;
        }
        install(Path.of("config.yaml"));
    }

    public static synchronized void install(Path configPath) {
        SwingHost.install(new DesktopUiContext(false, "Test",
                new DesktopUiModel() {
                    @Override public DesktopUiDocument document() {
                        return new DesktopUiDocument(List.of(new DesktopUiDocument.Page(
                                "test", DesktopUiNode.TextToken.raw("Test"),
                                new DesktopUiNode.Text("test.content", DesktopUiNode.TextToken.raw("Test"),
                                        DesktopUiNode.TextStyle.BODY, true, false))));
                    }
                    @Override public long revision() { return 0; }
                    @Override public void dispatch(DesktopUiNode.Event event) { }
                }, token -> token.fallback(), () -> { }, () -> "system", "test",
                Set.of(DesktopUiNode.Kind.values()), Set.of(DesktopUiCapability.values())));
    }
}
