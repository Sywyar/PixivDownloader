package top.sywyar.pixivdownload.gui.panel;

import org.junit.jupiter.api.*;
import top.sywyar.pixivdownload.plugin.api.gui.*;
import top.sywyar.pixivdownload.plugin.api.gui.importing.DesktopFileImportTool;
import static top.sywyar.pixivdownload.plugin.api.gui.importing.DesktopFileImportTool.*;
import javax.swing.*;
import java.awt.*;
import java.nio.file.*;
import java.util.List;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class FileImportToolsPanelTest {
    @Test @DisplayName("原生表单检查、修改、提交、取消和撤回；窄窗口主操作保持可见")
    void nativeImportWorkflow() throws Exception {
        var tool = new DesktopFileImportTool(new Identity("fixture", "fixture", 1, 1),
                new Description(DesktopUiText.raw("History import"), DesktopUiText.raw("Select history and original files. Preview checks local data.")));
        var tools = new AtomicReference<>(List.of(tool));
        var starts = new AtomicInteger();
        var secret = new AtomicReference<String>();
        var status = new AtomicReference<>(new Status(Phase.IDLE, 0, 0, 0, 0, 0, List.of()));
        Source source = new Source() {
            public Description description() { return tool.description(); }
            public Result<Preview> inspect(Request request) {
                assertFalse(SwingUtilities.isEventDispatchThread());
                status.set(new Status(Phase.READY, 1, 0, 0, 0, 0, List.of()));
                return Result.success(new Preview("token", 1, 1, 1, 0, 0, List.of(new Item("artwork:42", 1, Disposition.CANDIDATE))));
            }
            public Result<Status> start(String token, String cookie) {
                starts.incrementAndGet(); secret.set(cookie);
                status.set(new Status(Phase.RUNNING, 1, 0, 0, 0, 0, List.of())); return Result.success(status.get());
            }
            public Status status() { return status.get(); }
            public void cancel() { status.set(new Status(Phase.CANCELLED, 1, 0, 0, 0, 0, List.of())); }
        };
        var messages = new Properties();
        try (var reader = Files.newBufferedReader(Path.of("../pixivdownload-app/src/main/resources/i18n/messages_en.properties"))) { messages.load(reader); }
        var host = (DesktopUiHost) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DesktopUiHost.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "fileImportTools" -> tools.get();
                    case "fileImportTool" -> source;
                    case "message" -> java.text.MessageFormat.format(messages.getProperty((String) args[0], (String) args[0]), (Object[]) args[1]);
                    default -> throw new AssertionError(method.getName());
                });
        var context = new DesktopUiContext(false, 8080, ".", Path.of("config.yaml"), "swing", host,
                List.of(), List::of, DesktopUiText::fallback, () -> "system");
        var panel = new AtomicReference<FileImportToolsPanel>();
        var dialog = new AtomicReference<JDialog>();
        SwingUtilities.invokeAndWait(() -> {
            panel.set(new FileImportToolsPanel(context));
            ((JButton) panel.get().getComponent(0)).doClick();
            dialog.set(Arrays.stream(Window.getWindows()).filter(JDialog.class::isInstance).map(JDialog.class::cast)
                    .filter(w -> w.isVisible() && w.getTitle().equals("History import")).findFirst().orElseThrow());
            dialog.get().setSize(560, 480);
            field(dialog.get(), "file").setText("/records.json");
            field(dialog.get(), "root").setText("/source");
            field(dialog.get(), "credential").setText("secret");
            button(dialog.get(), "inspect").doClick();
        });
        try {
            await(() -> button(dialog.get(), "start").isEnabled());
            SwingUtilities.invokeAndWait(() -> {
                assertEquals(0, starts.get());
                field(dialog.get(), "root").setText("/other");
                assertFalse(button(dialog.get(), "start").isEnabled());
                button(dialog.get(), "inspect").doClick();
            });
            await(() -> button(dialog.get(), "start").isEnabled());
            SwingUtilities.invokeAndWait(() -> {
                dialog.get().setSize(560, 480);
                dialog.get().validate();
                assertTrue(button(dialog.get(), "start").isShowing());
                var image = new java.awt.image.BufferedImage(560, 480, java.awt.image.BufferedImage.TYPE_INT_RGB);
                var graphics = image.createGraphics();
                dialog.get().paint(graphics); graphics.dispose();
                try {
                    Path output = Path.of("target/tools-ui/history-swing.png"); Files.createDirectories(output.getParent());
                    javax.imageio.ImageIO.write(image, "png", output.toFile());
                } catch (java.io.IOException failure) { throw new AssertionError(failure); }
                button(dialog.get(), "start").doClick();
            });
            await(() -> starts.get() == 1 && button(dialog.get(), "cancel").isEnabled());
            SwingUtilities.invokeAndWait(() -> {
                assertEquals("secret", secret.get());
                assertEquals(0, ((JPasswordField) field(dialog.get(), "credential")).getPassword().length);
                button(dialog.get(), "cancel").doClick();
            });
            await(() -> status.get().phase() == Phase.CANCELLED && button(dialog.get(), "inspect").isEnabled());
            SwingUtilities.invokeAndWait(() -> {
                tools.set(List.of()); panel.get().refreshTools();
                assertEquals(0, panel.get().getComponentCount());
                assertFalse(dialog.get().isDisplayable());
            });
        } finally { SwingUtilities.invokeAndWait(panel.get()::close); }
    }
    private static Stream<Component> descendants(Component c) {
        return Stream.concat(Stream.of(c), c instanceof Container container
                ? Arrays.stream(container.getComponents()).flatMap(FileImportToolsPanelTest::descendants) : Stream.empty());
    }
    private static JButton button(Container c, String key) {
        return descendants(c).filter(v -> ("import." + key).equals(v.getName())).map(JButton.class::cast).findFirst().orElseThrow();
    }
    private static JTextField field(Container c, String key) {
        return descendants(c).filter(v -> ("import." + key).equals(v.getName())).map(JTextField.class::cast).findFirst().orElseThrow();
    }
    private static void await(java.util.function.BooleanSupplier condition) {
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
            var result = new AtomicBoolean();
            while (!result.get()) { SwingUtilities.invokeAndWait(() -> result.set(condition.getAsBoolean())); Thread.sleep(10); }
        });
    }
}
