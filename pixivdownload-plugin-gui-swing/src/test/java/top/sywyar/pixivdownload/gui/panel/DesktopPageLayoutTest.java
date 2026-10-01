package top.sywyar.pixivdownload.gui.panel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.guiswing.SwingHost;
import top.sywyar.pixivdownload.plugin.api.gui.*;
import top.sywyar.pixivdownload.plugin.api.gui.media.DesktopMediaTool;

import javax.swing.*;
import java.awt.*;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DesktopPageLayoutTest {
    @Test
    @DisplayName("工具和安全页在普通与窄窗口内保持左对齐、说明换行及操作可达")
    void pagesFitViewport() throws Exception {
        var identity = new DesktopMediaTool.Identity("sample", "sample", 1, 1);
        var description = new DesktopMediaTool.Description(DesktopUiText.raw("Media maintenance"), "sample", "original", "webp");
        var source = (DesktopMediaTool.Source) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{DesktopMediaTool.Source.class}, (proxy, method, args) -> {
                    if (method.getName().equals("status")) return new DesktopMediaTool.Status("idle", 0, 0, 0, List.of());
                    throw new AssertionError(method.getName());
                });
        var host = (DesktopUiHost) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DesktopUiHost.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "mediaTools" -> List.of(new DesktopMediaTool(identity, description));
                    case "mediaTool" -> source;
                    case "coreConfigGroups", "coreConfigFields" -> List.of();
                    case "resolveLocale" -> {
                        var locale = new DesktopUiHost.UiLocale("en-US", "English", "en");
                        yield new DesktopUiHost.UiLocaleResolution(locale, List.of(locale));
                    }
                    case "readDownloadRootFromConfig" -> ".";
                    case "resolveDatabasePath" -> Path.of("data", "test.db");
                    case "defaultBackfillOptions" -> new DesktopUiHost.BackfillOptions("data/test.db", "localhost", 8080, false, 1000, 0, false);
                    case "backendSnapshot" -> new DesktopUiHost.BackendSnapshot(DesktopUiHost.BackendState.STOPPED, null);
                    case "subscribeBackend" -> (AutoCloseable) () -> {};
                    default -> throw new AssertionError(method.getName());
                });
        var config = Path.of("config.yaml");
        SwingHost.install(new DesktopUiContext(false, 8080, ".", config, "gui-swing", host, List.of(), List::of,
                text -> text.key().isBlank() ? text.fallback() : text.key(), () -> "system"));
        SwingUtilities.invokeAndWait(() -> {
            var tools = new ToolsPanel(config);
            try {
                for (JPanel panel : List.of(tools, new SecurityPanel(8080))) {
                    for (int width : List.of(1120, 760)) {
                        panel.setSize(width, 560);
                        for (int pass = 0; pass < 8; pass++) layout(panel);
                        JScrollPane scroll = (JScrollPane) panel.getComponent(0);
                        Container content = (Container) scroll.getViewport().getView();
                        assertEquals(scroll.getViewport().getExtentSize().width, content.getWidth());
                        assertFalse(scroll.getHorizontalScrollBar().isVisible());
                        for (Component card : content.getComponents()) {
                            if (card instanceof Box.Filler) continue;
                            assertEquals(0, card.getX(), card.getClass().getSimpleName());
                            assertTrue(card.getWidth() <= content.getWidth());
                        }
                        assertControlsWithinPage(content, content);
                    }
                }
            } finally { tools.dispose(); }
        });
    }

    private static void layout(Container container) {
        container.invalidate();
        container.doLayout();
        for (Component component : container.getComponents()) {
            if (component instanceof Container child) layout(child);
        }
    }

    private static void assertControlsWithinPage(Container root, Container current) {
        for (Component component : current.getComponents()) {
            if (!component.isVisible()) continue;
            if (component instanceof AbstractButton || component instanceof JTextField) {
                Rectangle bounds = SwingUtilities.convertRectangle(current, component.getBounds(), root);
                assertTrue(bounds.x >= 0 && bounds.getMaxX() <= root.getWidth(), component + " bounds=" + bounds);
                assertTrue(bounds.width > 0);
            }
            if (component instanceof Container child) assertControlsWithinPage(root, child);
        }
    }
}
