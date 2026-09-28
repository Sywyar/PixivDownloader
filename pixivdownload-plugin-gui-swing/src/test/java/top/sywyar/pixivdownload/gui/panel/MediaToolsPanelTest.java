package top.sywyar.pixivdownload.gui.panel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.guiswing.SwingHost;
import top.sywyar.pixivdownload.plugin.api.gui.*;
import top.sywyar.pixivdownload.plugin.api.gui.media.DesktopMediaTool;

import javax.swing.*;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class MediaToolsPanelTest {
    @Test
    @DisplayName("原生工具页预览不启动，修改范围须重新预览，开始和取消调用当前能力")
    void nativeMediaWorkflow() throws Exception {
        var locale = new DesktopUiHost.UiLocale("en-US", "English", "en");
        var identity = new DesktopMediaTool.Identity("sample", "sample", 1, 1);
        var description = new DesktopMediaTool.Description(DesktopUiText.raw("Media tool"), "sample", "original", "webp");
        var tools = new AtomicReference<>(List.of(new DesktopMediaTool(identity, description)));
        var starts = new AtomicInteger();
        var request = new AtomicReference<DesktopMediaTool.Request>();
        var previewFiles = new AtomicReference<>(java.util.stream.IntStream.range(0, 25)
                .mapToObj(index -> new DesktopMediaTool.Item(42 + index, index, "source-" + index + ".jpg", List.of("png"), true)).toList());
        var formatResolutions = new AtomicInteger();
        var fileView = new AtomicReference<JTextArea>();
        var state = new AtomicReference<>(new DesktopMediaTool.Status("idle", 0, 0, 0, List.of()));
        var source = new DesktopMediaTool.Source() {
            public DesktopMediaTool.Description description() { return description; }
            public DesktopMediaTool.Result<DesktopMediaTool.Preview> preview(DesktopMediaTool.Request value) {
                request.set(value);
                return new DesktopMediaTool.Result<>(new DesktopMediaTool.Preview("preview-token", previewFiles.get(), previewFiles.get().size(), 0, false), null);
            }
            public DesktopMediaTool.Result<DesktopMediaTool.Status> start(String token) {
                assertEquals("preview-token", token);
                starts.incrementAndGet();
                state.set(new DesktopMediaTool.Status("running", 1, 0, 0, List.of()));
                return new DesktopMediaTool.Result<>(state.get(), null);
            }
            public DesktopMediaTool.Status status() { return state.get(); }
            public void cancel() { state.set(new DesktopMediaTool.Status("cancelled", 1, 0, 0, List.of())); }
            public DesktopMediaTool.Result<DesktopMediaTool.Report> capabilities() {
                return new DesktopMediaTool.Result<>(new DesktopMediaTool.Report("ffmpeg", "system", List.of(new DesktopMediaTool.Capability("png", true))), null);
            }
        };
        var host = (DesktopUiHost) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DesktopUiHost.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "mediaTools" -> tools.get();
                    case "coreConfigGroups", "coreConfigFields" -> List.of();
                    case "mediaTool" -> { assertEquals(identity, args[0]); yield source; }
                    case "resolveLocale" -> new DesktopUiHost.UiLocaleResolution(locale, List.of(locale));
                    case "readDownloadRootFromConfig" -> ".";
                    case "resolveDatabasePath" -> Path.of("data", "test.db");
                    case "defaultBackfillOptions" -> new DesktopUiHost.BackfillOptions("data/test.db", "localhost", 8080, false, 1000, 0, false);
                    case "backendSnapshot" -> new DesktopUiHost.BackendSnapshot(DesktopUiHost.BackendState.STOPPED, null);
                    case "subscribeBackend" -> (AutoCloseable) () -> {};
                    default -> throw new AssertionError(method.getName());
                });
        Path config = Path.of("nonexistent-test-config.yaml");
        var messages = new java.util.Properties();
        try (var reader = java.nio.file.Files.newBufferedReader(Path.of(
                "../pixivdownload-plugin-download-workbench/src/main/resources/i18n/web/batch_en.properties"),
                java.nio.charset.StandardCharsets.UTF_8)) { messages.load(reader); }
        SwingHost.install(new DesktopUiContext(false, 8080, ".", config, "gui-swing", host, List.of(), List::of,
                text -> {
                    if (text.key().equals("media.tools.missing-formats")) formatResolutions.incrementAndGet();
                    String pattern = messages.getProperty(text.key(), text.fallback());
                    return text.arguments().isEmpty() ? pattern : java.text.MessageFormat.format(pattern, text.arguments().toArray());
                }, () -> "system"));
        var reference = new AtomicReference<ToolsPanel>();
        SwingUtilities.invokeAndWait(() -> reference.set(new ToolsPanel(config)));
        ToolsPanel panel = reference.get();
        try {
            SwingUtilities.invokeAndWait(() -> {
                assertFalse(button(panel, "start").isEnabled());
                button(panel, "preview").doClick();
            });
            awaitEnabled(panel, "start");
            assertTrue(request.get().repairThumbnails());
            assertEquals(0, starts.get());
            SwingUtilities.invokeAndWait(() -> {
                JTextArea area = descendants(panel).filter(JTextArea.class::isInstance).map(JTextArea.class::cast)
                        .filter(value -> value.getText().startsWith("42 / 0  source-0.jpg")).findFirst().orElseThrow();
                fileView.set(area);
                assertEquals(10, area.getText().lines().count());
                assertFalse(button(panel, "previous").isEnabled());
                formatResolutions.set(0);
                button(panel, "next").doClick();
                assertEquals(10, formatResolutions.get());
                assertTrue(area.getText().startsWith("52 / 10  source-10.jpg"));
                formatResolutions.set(0);
                button(panel, "next").doClick();
                assertEquals(5, formatResolutions.get());
                assertEquals(5, area.getText().lines().count());
                assertTrue(area.getText().startsWith("62 / 20  source-20.jpg"));
                assertFalse(button(panel, "next").isEnabled());
                assertTrue(descendants(panel).filter(JLabel.class::isInstance).map(JLabel.class::cast)
                        .anyMatch(label -> "3 / 3".equals(label.getText())));
                button(panel, "previous").doClick();
                assertTrue(area.getText().startsWith("52 / 10  source-10.jpg"));
            });
            SwingUtilities.invokeAndWait(() -> {
                var media = descendants(panel).filter(MediaToolsPanel.class::isInstance)
                        .map(MediaToolsPanel.class::cast).findFirst().orElseThrow();
                media.setSize(560, 800);
                layout(media);
                layout(media);
                var screenshot = new java.awt.image.BufferedImage(560, 800, java.awt.image.BufferedImage.TYPE_INT_RGB);
                var graphics = screenshot.createGraphics();
                graphics.setColor(java.awt.Color.WHITE);
                graphics.fillRect(0, 0, 560, 800);
                media.printAll(graphics);
                graphics.dispose();
                try {
                    Path target = Path.of("target", "tools-ui", "native-media-swing.png");
                    java.nio.file.Files.createDirectories(target.getParent());
                    javax.imageio.ImageIO.write(screenshot, "png", target.toFile());
                } catch (java.io.IOException failure) { throw new AssertionError(failure); }
            });
            SwingUtilities.invokeAndWait(() -> {
                descendants(panel).filter(JCheckBox.class::isInstance).map(JCheckBox.class::cast).filter(box -> "media.thumbnails".equals(box.getName())).findFirst().orElseThrow().doClick();
                assertFalse(button(panel, "start").isEnabled());
                button(panel, "preview").doClick();
            });
            awaitEnabled(panel, "start");
            SwingUtilities.invokeAndWait(() -> button(panel, "start").doClick());
            awaitEnabled(panel, "cancel");
            assertEquals(1, starts.get());
            assertFalse(request.get().repairThumbnails());
            SwingUtilities.invokeAndWait(() -> button(panel, "cancel").doClick());
            awaitEnabled(panel, "preview");
            assertEquals("cancelled", state.get().state());
            state.set(new DesktopMediaTool.Status("completed", 25, 25, 25,
                    java.util.stream.LongStream.rangeClosed(1, 25).mapToObj(id -> new DesktopMediaTool.Failure(id, 0)).toList()));
            SwingUtilities.invokeAndWait(() -> button(panel, "refresh").doClick());
            awaitOnEdt(() -> fileView.get().getText().startsWith("1 / 0\n"));
            SwingUtilities.invokeAndWait(() -> {
                button(panel, "next").doClick();
                button(panel, "next").doClick();
                assertEquals("21 / 0\n22 / 0\n23 / 0\n24 / 0\n25 / 0", fileView.get().getText());
            });
            state.set(new DesktopMediaTool.Status("completed", 2, 2, 2,
                    List.of(new DesktopMediaTool.Failure(80, 1), new DesktopMediaTool.Failure(90, 2))));
            SwingUtilities.invokeAndWait(() -> button(panel, "refresh").doClick());
            awaitOnEdt(() -> fileView.get().getText().equals("80 / 1\n90 / 2"));
            SwingUtilities.invokeAndWait(() -> {
                assertFalse(button(panel, "previous").isEnabled());
                assertFalse(button(panel, "next").isEnabled());
                previewFiles.set(List.of());
                button(panel, "preview").doClick();
            });
            awaitOnEdt(() -> button(panel, "preview").isEnabled() && fileView.get().getText().isEmpty());
            SwingUtilities.invokeAndWait(() -> assertFalse(button(panel, "start").isEnabled()));
            SwingUtilities.invokeAndWait(() -> button(panel, "check").doClick());
            awaitEnabled(panel, "preview");
            SwingUtilities.invokeAndWait(() -> {
                assertTrue(descendants(panel).filter(JTextArea.class::isInstance).map(JTextArea.class::cast)
                        .anyMatch(area -> area.getText().contains("ffmpeg")));
                tools.set(List.of());
                descendants(panel).filter(MediaToolsPanel.class::isInstance).map(MediaToolsPanel.class::cast)
                        .forEach(MediaToolsPanel::refreshTools);
                assertFalse(descendants(panel).anyMatch(value -> "media.preview".equals(value.getName())));
            });
        } finally { SwingUtilities.invokeAndWait(panel::dispose); }
    }
    private static void awaitEnabled(Component panel, String action) {
        awaitOnEdt(() -> button(panel, action).isEnabled());
    }
    private static void awaitOnEdt(java.util.function.BooleanSupplier condition) {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            var enabled = new AtomicBoolean();
            while (!enabled.get()) {
                SwingUtilities.invokeAndWait(() -> enabled.set(condition.getAsBoolean()));
                if (!enabled.get()) Thread.sleep(10);
            }
        });
    }
    private static JButton button(Component panel, String name) {
        return descendants(panel).filter(JButton.class::isInstance).map(JButton.class::cast)
                .filter(value -> ("media." + name).equals(value.getName())).findFirst().orElseThrow();
    }
    private static Stream<Component> descendants(Component component) {
        return Stream.concat(Stream.of(component), component instanceof Container container
                ? Arrays.stream(container.getComponents()).flatMap(MediaToolsPanelTest::descendants) : Stream.empty());
    }
    private static void layout(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) if (child instanceof Container nested) layout(nested);
    }
}
