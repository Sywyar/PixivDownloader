package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.guiswing.SwingHost;
import top.sywyar.pixivdownload.plugin.api.gui.*;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class DirectorySuggestionDialogTest {
    @TempDir Path temporary;

    @Test @DisplayName("目录提示显示插件红字说明，矮窗口中确认和取消始终可见")
    void guidanceAndActionsRemainAccessible() throws Exception {
        var messages = new Properties();
        try (var reader = Files.newBufferedReader(Path.of("../pixivdownload-app/src/main/resources/i18n/messages_en.properties"))) {
            messages.load(reader);
        }
        var plugin = new Properties();
        try (var reader = Files.newBufferedReader(Path.of("../pixivdownload-plugin-pixiv-batch-downloader-import/src/main/resources/i18n/web/pixiv-batch-downloader-import_en.properties"))) {
            plugin.load(reader);
        }
        var requests = new LinkedBlockingQueue<Object>();
        var host = (DesktopUiHost) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DesktopUiHost.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "message" -> java.text.MessageFormat.format(messages.getProperty((String) args[0], (String) args[0]), (Object[]) args[1]);
                    case "guiPostJson" -> {
                        requests.add(args[1]);
                        boolean dismissed = Boolean.TRUE.equals(((Map<?, ?>) args[1]).get("dismiss"));
                        yield new DesktopUiHost.GuiResponse(dismissed, dismissed ? 200 : 400, DesktopUiHost.GuiValue.of(Map.of()), "", false);
                    }
                    default -> throw new AssertionError(method.getName());
                });
        var contextField = SwingHost.class.getDeclaredField("context");
        contextField.setAccessible(true);
        var previous = contextField.get(null);
        SwingHost.install(new DesktopUiContext(false, 8080, temporary.toString(), temporary.resolve("config.yaml"), "swing", host,
                List.of(), List::of, text -> "fixture".equals(text.namespace()) ? plugin.getProperty("config.root.help") : text.fallback(), () -> "system"));
        var frame = new AtomicReference<JFrame>();
        var controller = new AtomicReference<DirectorySuggestionDialog>();
        var dialog = new AtomicReference<JDialog>();
        var snapshot = DesktopUiHost.GuiValue.of(Map.of("directories", List.of(Map.of(
                "owner", Map.of("pluginId", "sample", "packageId", "sample", "generation", 1, "publication", 2),
                "suggestion", Map.of("suggestionId", "first", "configurationKey", "sample.root", "directory", temporary.toString()),
                "displayName", Map.of("fallback", "Sample"),
                "help", Map.of("namespace", "fixture", "key", "config.root.help")))));
        try {
            SwingUtilities.invokeAndWait(() -> {
                frame.set(new JFrame());
                var parent = new JPanel();
                frame.get().setContentPane(parent);
                frame.get().setSize(800, 600);
                frame.get().setVisible(true);
                controller.set(new DirectorySuggestionDialog(parent));
                SwingUtilities.invokeLater(() -> controller.get().refresh(snapshot));
            });
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
                while (dialog.get() == null) {
                    SwingUtilities.invokeAndWait(() -> dialog.set(Arrays.stream(frame.get().getOwnedWindows())
                            .filter(JDialog.class::isInstance).map(JDialog.class::cast).filter(Window::isShowing).findFirst().orElse(null)));
                    Thread.sleep(10);
                }
            });
            SwingUtilities.invokeAndWait(() -> {
                var window = dialog.get();
                var guidance = descendants(window).filter(c -> "directory-suggestion.guidance".equals(c.getName()))
                        .map(JLabel.class::cast).findFirst().orElseThrow();
                assertFalse(guidance.isVisible());
                var path = descendants(window).filter(JTextField.class::isInstance).map(JTextField.class::cast).findFirst().orElseThrow();
                assertTrue(path.isShowing());
                path.setText(temporary.resolve("chosen").toString());
                var rule = descendants(window).filter(c -> "directory-suggestion.rule".equals(c.getName()))
                        .map(JLabel.class::cast).findFirst().orElseThrow();
                var error = UIManager.getColor("Component.error.focusedBorderColor");
                assertEquals(error != null ? error : new Color(190, 45, 45), rule.getForeground());
                descendants(window).filter(c -> "directory-suggestion.details".equals(c.getName()))
                        .map(JToggleButton.class::cast).findFirst().orElseThrow().doClick();
                assertTrue(guidance.isVisible());
                assertEquals(temporary.resolve("chosen").toString(), path.getText());
                window.setSize(620, 300);
                window.validate();
                assertTrue(guidance.getText().contains(plugin.getProperty("config.root.help").split("\n")[0]));
                var scroll = descendants(window).filter(JScrollPane.class::isInstance).map(JScrollPane.class::cast).findFirst().orElseThrow();
                assertTrue(scroll.getVerticalScrollBar().isVisible());
                scroll.getVerticalScrollBar().setValue(scroll.getVerticalScrollBar().getMaximum());
                var confirm = window.getRootPane().getDefaultButton();
                var cancel = descendants(window).filter(JButton.class::isInstance).map(JButton.class::cast)
                        .filter(button -> messages.getProperty("desktop.ui.action.cancel").equals(button.getText())).findFirst().orElseThrow();
                for (var button : List.of(confirm, cancel)) {
                    assertTrue(button.isShowing());
                    assertNull(SwingUtilities.getAncestorOfClass(JScrollPane.class, button));
                    var point = SwingUtilities.convertPoint(button, 0, 0, window);
                    assertTrue(point.y >= 0 && point.y + button.getHeight() <= window.getHeight());
                }
                confirm.doClick();
            });
            var failedSave = (Map<?, ?>) requests.poll(5, TimeUnit.SECONDS);
            assertNotNull(failedSave);
            assertEquals(false, failedSave.get("dismiss"));
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
                var ready = new java.util.concurrent.atomic.AtomicBoolean();
                while (!ready.get()) {
                    SwingUtilities.invokeAndWait(() -> ready.set(dialog.get().getRootPane().getDefaultButton().isEnabled()));
                    Thread.sleep(10);
                }
            });
            SwingUtilities.invokeAndWait(() -> {
                var window = dialog.get();
                assertEquals(300, window.getHeight());
                window.setSize(620, 520);
                window.validate();
                var scroll = descendants(window).filter(JScrollPane.class::isInstance).map(JScrollPane.class::cast).findFirst().orElseThrow();
                scroll.getVerticalScrollBar().setValue(0);
                var image = new java.awt.image.BufferedImage(window.getWidth(), window.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
                var graphics = image.createGraphics();
                window.paint(graphics);
                graphics.dispose();
                try {
                    Path output = Path.of("target/tools-ui/directory-swing.png");
                    Files.createDirectories(output.getParent());
                    javax.imageio.ImageIO.write(image, "png", output.toFile());
                } catch (java.io.IOException failure) { throw new AssertionError(failure); }
                descendants(window).filter(JButton.class::isInstance).map(JButton.class::cast)
                        .filter(button -> messages.getProperty("desktop.ui.action.cancel").equals(button.getText())).findFirst().orElseThrow().doClick();
            });
            var request = (Map<?, ?>) requests.poll(5, TimeUnit.SECONDS);
            assertNotNull(request);
            assertEquals(true, request.get("dismiss"));
            assertEquals("", request.get("directory"));
            assertFalse(dialog.get().isDisplayable());
            assertFalse(Files.exists(temporary.resolve("config.yaml")));
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                if (controller.get() != null) controller.get().dispose();
                if (frame.get() != null) frame.get().dispose();
            });
            contextField.set(null, previous);
        }
    }

    private static Stream<Component> descendants(Component component) {
        return Stream.concat(Stream.of(component), component instanceof Container container
                ? Arrays.stream(container.getComponents()).flatMap(DirectorySuggestionDialogTest::descendants) : Stream.empty());
    }
}
