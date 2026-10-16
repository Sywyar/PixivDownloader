package top.sywyar.pixivdownload.gui.panel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.guiswing.SwingHost;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiContext;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;

import javax.swing.JLabel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
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

@DisplayName("Swing 工具配置继承")
class ToolsConfigurationTest {
    @Test
    @DisplayName("加载和刷新配置失败时显示宿主解析的错误提示")
    void configurationFailuresUseHostMessages() throws Exception {
        var failureText = new AtomicReference<>("Initial host failure message");
        var locale = new DesktopUiHost.UiLocale("en-US", "English", "_en");
        var host = (DesktopUiHost) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{DesktopUiHost.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "defaultBackfillOptions" -> throw new IllegalStateException("Configuration unavailable");
                    case "readDownloadRootFromConfig" -> "downloads";
                    case "resolveDatabasePath" -> Path.of("data/test.db");
                    case "resolveLocale" -> new DesktopUiHost.UiLocaleResolution(locale, List.of(locale));
                    case "mediaTools", "coreConfigGroups", "coreConfigFields" -> List.of();
                    case "backendSnapshot" -> new DesktopUiHost.BackendSnapshot(DesktopUiHost.BackendState.STOPPED, null);
                    case "subscribeBackend" -> (AutoCloseable) () -> {};
                    case "message" -> "desktop.ui.tools.operation-failed".equals(args[0])
                            ? failureText.get() : args[0];
                    default -> throw new AssertionError("Unexpected host operation: " + method.getName());
                });
        var path = Path.of("config.yaml");
        SwingHost.install(new DesktopUiContext(false, 8080, "downloads", path, "gui-swing", host,
                List.of(), List::of, text -> text.key(), () -> "system"));
        var panel = new AtomicReference<ToolsPanel>();
        try {
            SwingUtilities.invokeAndWait(() -> panel.set(new ToolsPanel(path)));
            SwingUtilities.invokeAndWait(() -> {
                assertEquals(1, descendants(panel.get()).filter(JLabel.class::isInstance).map(JLabel.class::cast)
                        .filter(label -> label.getText() != null && label.getText().contains(failureText.get())).count());
                failureText.set("Refreshed host failure message");
                panel.get().refreshConfiguration();
            });
            SwingUtilities.invokeAndWait(() -> assertEquals(1,
                    descendants(panel.get()).filter(JLabel.class::isInstance).map(JLabel.class::cast)
                            .filter(label -> label.getText() != null && label.getText().contains(failureText.get())).count()));
        } finally {
            SwingUtilities.invokeAndWait(() -> { if (panel.get() != null) panel.get().dispose(); });
        }
    }

    @Test
    @DisplayName("打开日志在后台调用只读宿主入口，重复点击不阻塞 EDT")
    void logOpeningKeepsEventThreadResponsive() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var locale = new DesktopUiHost.UiLocale("en-US", "English", "_en");
        var host = (DesktopUiHost) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{DesktopUiHost.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "defaultBackfillOptions" -> new DesktopUiHost.BackfillOptions("data/test.db", "", 8080, false, 0, 0, false);
                    case "readDownloadRootFromConfig" -> "downloads";
                    case "resolveDatabasePath" -> Path.of("data/test.db");
                    case "resolveLocale" -> new DesktopUiHost.UiLocaleResolution(locale, List.of(locale));
                    case "mediaTools", "coreConfigGroups", "coreConfigFields" -> List.of();
                    case "backendSnapshot" -> new DesktopUiHost.BackendSnapshot(DesktopUiHost.BackendState.STOPPED, null);
                    case "subscribeBackend" -> (AutoCloseable) () -> {};
                    case "findToolLog" -> java.util.Optional.of(Path.of("log", "html", (String) args[0]).toAbsolutePath());
                    case "openExternalUri" -> {
                        org.junit.jupiter.api.Assertions.assertFalse(SwingUtilities.isEventDispatchThread());
                        assertEquals("artworks-backfill-latest.html", Path.of((java.net.URI) args[0]).getFileName().toString());
                        calls.incrementAndGet();
                        entered.countDown();
                        org.junit.jupiter.api.Assertions.assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS));
                        yield null;
                    }
                    default -> throw new AssertionError("Unexpected host operation: " + method.getName());
                });
        var path = Path.of("config.yaml");
        SwingHost.install(new DesktopUiContext(false, 8080, "downloads", path, "gui-swing", host,
                List.of(), List::of, text -> text.key(), () -> "system"));
        var panel = new AtomicReference<ToolsPanel>();
        var open = ToolsPanel.class.getDeclaredMethod("openBackfillLogPage");
        open.setAccessible(true);
        try {
            SwingUtilities.invokeAndWait(() -> panel.set(new ToolsPanel(path)));
            SwingUtilities.invokeAndWait(() -> {
                try { open.invoke(panel.get()); open.invoke(panel.get()); }
                catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            });
            org.junit.jupiter.api.Assertions.assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> assertEquals(1, calls.get()));
        } finally {
            release.countDown();
            SwingUtilities.invokeAndWait(() -> { if (panel.get() != null) panel.get().dispose(); });
        }
    }

    @Test
    @DisplayName("刷新采用新代理和下载根，保留临时覆盖且不请求配置写入")
    void refreshPreservesTemporaryOverrides() throws Exception {
        var saved = new AtomicReference<>(new DesktopUiHost.BackfillOptions(
                "data/test.db", "saved.proxy", 17891, false, 1000, 0, false));
        var root = new AtomicReference<>("downloads");
        var locale = new DesktopUiHost.UiLocale("en-US", "English", "_en");
        var host = (DesktopUiHost) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{DesktopUiHost.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "defaultBackfillOptions" -> saved.get();
                    case "readDownloadRootFromConfig" -> root.get();
                    case "resolveDatabasePath" -> Path.of("data/test.db");
                    case "resolveLocale" -> new DesktopUiHost.UiLocaleResolution(locale, List.of(locale));
                    case "mediaTools", "coreConfigGroups", "coreConfigFields" -> List.of();
                    case "backendSnapshot" -> new DesktopUiHost.BackendSnapshot(DesktopUiHost.BackendState.STOPPED, null);
                    case "subscribeBackend" -> (AutoCloseable) () -> {};
                    default -> throw new AssertionError("Unexpected host operation: " + method.getName());
                });
        var path = Path.of("config.yaml");
        SwingHost.install(new DesktopUiContext(false, 8080, root.get(), path, "gui-swing", host,
                List.of(), List::of, text -> text.key(), () -> "system"));
        SwingUtilities.invokeAndWait(() -> {
            var panel = new ToolsPanel(path);
            try {
                var proxy = descendants(panel).filter(JTextField.class::isInstance).map(JTextField.class::cast)
                        .filter(field -> field.getText().equals("saved.proxy")).findFirst().orElseThrow();
                var directory = descendants(panel).filter(JTextField.class::isInstance).map(JTextField.class::cast)
                        .filter(field -> field.getText().equals("downloads")).findFirst().orElseThrow();
                proxy.setText("temporary.proxy");
                saved.set(new DesktopUiHost.BackfillOptions("data/test.db", "wizard.proxy", 17892, true, 1000, 0, false));
                root.set("new-downloads");
                panel.refreshConfiguration();
                assertEquals("temporary.proxy", proxy.getText());
                assertEquals("new-downloads", directory.getText());
                assertEquals(1, descendants(panel).filter(JSpinner.class::isInstance).map(JSpinner.class::cast)
                        .filter(spinner -> spinner.getValue().equals(17892)).count());
            } finally {
                panel.dispose();
            }
        });
    }

    private static Stream<Component> descendants(Component component) {
        return Stream.concat(Stream.of(component), component instanceof Container container
                ? Arrays.stream(container.getComponents()).flatMap(ToolsConfigurationTest::descendants) : Stream.empty());
    }
}
