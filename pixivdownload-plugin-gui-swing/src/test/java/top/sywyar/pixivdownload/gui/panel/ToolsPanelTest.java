package top.sywyar.pixivdownload.gui.panel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.gui.entry.GuiWebEntrySnapshot;
import top.sywyar.pixivdownload.gui.entry.GuiWebEntrySpec;
import top.sywyar.pixivdownload.guiswing.SwingHost;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiContext;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;

import javax.swing.JButton;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolsPanelTest {
    @Test
    @DisplayName("工具页显示插件入口且点击后打开贡献的页面")
    void opensContributedTool() throws Exception {
        var locale = new DesktopUiHost.UiLocale("en-US", "English", "en");
        var host = (DesktopUiHost) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{DesktopUiHost.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "resolveLocale" -> new DesktopUiHost.UiLocaleResolution(locale, List.of(locale));
                    case "readDownloadRootFromConfig" -> ".";
                    case "resolveDatabasePath" -> Path.of("data", "test.db");
                    case "defaultBackfillOptions" -> new DesktopUiHost.BackfillOptions(
                            "data/test.db", "localhost", 8080, false, 1000, 0, false);
                    case "backendSnapshot" -> new DesktopUiHost.BackendSnapshot(DesktopUiHost.BackendState.STOPPED, null);
                    case "subscribeBackend" -> (AutoCloseable) () -> {};
                    default -> throw new AssertionError(method.getName());
                }
        );
        Path config = Path.of("nonexistent-test-config.yaml");
        SwingHost.install(new DesktopUiContext(
                false, 8080, ".", config, "gui-swing", host, List.of(), List::of,
                text -> text.fallback(), () -> "system"));
        var entry = new GuiWebEntrySpec("sample", "tool", "Sample tool", null, "",
                "/sample-tool.html", "images", 1);
        var entries = new GuiWebEntrySnapshot(List.of(), List.of(), List.of(entry), List.of());
        var opened = new AtomicReference<String>();
        SwingUtilities.invokeAndWait(() -> {
            var panel = new ToolsPanel(config, () -> entries, opened::set);
            try {
                var button = descendants(panel).filter(JButton.class::isInstance)
                        .map(JButton.class::cast).filter(value -> value.getText().equals("Sample tool"))
                        .findFirst().orElseThrow();
                assertTrue(button.isVisible());
                assertTrue(button.isEnabled());
                button.doClick();
                assertEquals("/sample-tool.html", opened.get());
            } finally {
                panel.dispose();
            }
        });
    }

    private static Stream<Component> descendants(Component component) {
        return Stream.concat(Stream.of(component), component instanceof Container container
                ? Arrays.stream(container.getComponents()).flatMap(ToolsPanelTest::descendants) : Stream.empty());
    }
}
