package top.sywyar.pixivdownload.gui.panel;

import org.junit.jupiter.api.*;
import top.sywyar.pixivdownload.plugin.api.gui.*;
import top.sywyar.pixivdownload.plugin.api.gui.importing.LocalArtworkImports;
import top.sywyar.pixivdownload.plugin.api.gui.importing.LocalArtworkImports.*;
import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class LocalArtworkImportPanelTest {
    @Test @DisplayName("原生窗口按 ID 扫描、预览与取消，窄矮窗口保留操作按钮")
    void nativeWorkflow() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "需要桌面窗口环境");
        var phase = new AtomicReference<>(Phase.READY);
        var opened = new AtomicReference<java.net.URI>();
        var scanFailure = new AtomicReference<VariableException>();
        String guide = "https://sywyar.github.io/PixivDownloader/#/zh-hant/local-import";
        var captured = new AtomicReference<Request>(); var starts = new AtomicInteger(); UUID id = UUID.randomUUID();
        LocalArtworkImports service = new LocalArtworkImports() {
            public List<Preset> presets() { return List.of(new Preset("D:/{artwork_id}/")); }
            public Snapshot scan(Request value) {
                assertFalse(SwingUtilities.isEventDispatchThread());
                if (scanFailure.get() != null) throw scanFailure.get();
                captured.set(value); return status(id);
            }
            public Snapshot status(UUID token) { return new Snapshot(id, phase.get(), 1, 1, 0, 0, 0, 0, false, List.of()); }
            public List<Item> items(UUID token, int offset, int limit) { return List.of(new Item(42, List.of(new File("D:/42/pic.png", null, Map.of())), Disposition.NEW, "")); }
            public Snapshot start(UUID token) { starts.incrementAndGet(); phase.set(Phase.IMPORTING); return status(id); }
            public Snapshot cancel(UUID token) { phase.set(Phase.CANCELLED); return status(id); }
        };
        var host = (DesktopUiHost) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DesktopUiHost.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "localArtworkImports" -> Optional.of(service);
                    case "parseLocalImportPresets", "coreConfigFields", "coreConfigGroups" -> List.of();
                    case "resolveLocale" -> {
                        var locale = new DesktopUiHost.UiLocale("en-US", "English", "en");
                        yield new DesktopUiHost.UiLocaleResolution(locale, List.of(locale));
                    }
                    case "message" -> "local-import.guide-url".equals(args[0]) ? guide
                            : args[0].toString().startsWith("local-import.error.")
                            ? args[0] + Arrays.toString((Object[]) args[1]) : args[0];
                    case "openExternalUri" -> { assertFalse(SwingUtilities.isEventDispatchThread()); opened.set((java.net.URI) args[0]); yield null; }
                    default -> throw new AssertionError("Unexpected host operation: " + method.getName());
                });
        var context = new DesktopUiContext(false, 8080, ".", Path.of("config.yaml"), "swing", host,
                List.of(), List::of, DesktopUiText::fallback, () -> "system");
        top.sywyar.pixivdownload.guiswing.SwingHost.install(context);
        var setting = new AtomicReference<top.sywyar.pixivdownload.gui.config.FieldRenderer.RenderedField>();
        SwingUtilities.invokeAndWait(() -> {
            var spec = top.sywyar.pixivdownload.gui.config.ConfigFieldSpec.builder(
                    "local-import.presets", "Presets",
                    top.sywyar.pixivdownload.gui.config.FieldType.PATH_TEMPLATES, "Download").defaultValue("[]").build();
            setting.set(top.sywyar.pixivdownload.gui.config.FieldRenderer.render(spec));
            button(setting.get().panel(), "guide").doClick();
        });
        await(() -> opened.get() != null);
        assertEquals(guide, opened.get().toString());
        assertEquals("[]", setting.get().getValue().get());
        assertNull(captured.get());
        assertEquals(0, starts.get());
        opened.set(null);
        var panel = new AtomicReference<LocalArtworkImportPanel>(); var frame = new AtomicReference<JFrame>(); var dialog = new AtomicReference<JDialog>();
        SwingUtilities.invokeAndWait(() -> {
            panel.set(new LocalArtworkImportPanel(context)); frame.set(new JFrame("Local import fixture"));
            frame.get().setContentPane(panel.get()); frame.get().setSize(500, 300); frame.get().setVisible(true);
            button(panel.get(), "title").doClick();
            dialog.set((JDialog) frame.get().getOwnedWindows()[0]); dialog.get().setSize(480, 360);
        });
        try {
            SwingUtilities.invokeAndWait(() -> {
                var ids = (JTextArea) descendants(dialog.get()).filter(c -> "local-import.ids".equals(c.getName())).findFirst().orElseThrow();
                ids.setText("42,43\n44"); button(dialog.get(), "guide").doClick();
            });
            await(() -> opened.get() != null);
            assertEquals(guide, opened.get().toString());
            SwingUtilities.invokeAndWait(() -> button(dialog.get(), "scan").doClick());
            await(() -> captured.get() != null && button(dialog.get(), "start").isEnabled());
            assertEquals(List.of(42L, 43L, 44L), captured.get().artworkIds());
            SwingUtilities.invokeAndWait(() -> {
                JButton start = button(dialog.get(), "start");
                Rectangle bounds = SwingUtilities.convertRectangle(start.getParent(), start.getBounds(), dialog.get().getContentPane());
                assertTrue(dialog.get().getContentPane().getBounds().contains(bounds));
                dialog.get().setVisible(false);
                button(panel.get(), "title").doClick();
                assertTrue(start.isEnabled(), "重新打开后仍可确认同一预览");
                start.doClick();
            });
            await(() -> starts.get() == 1 && button(dialog.get(), "cancel").isEnabled());
            SwingUtilities.invokeAndWait(() -> button(dialog.get(), "cancel").doClick());
            await(() -> phase.get() == Phase.CANCELLED && button(dialog.get(), "scan").isEnabled());
            assertEquals(1, starts.get());
            for (String variable : List.of("foo", "ai")) {
                scanFailure.set(new VariableException(variable, variable.equals("ai")));
                SwingUtilities.invokeAndWait(() -> {
                    var input = (JTextArea) descendants(dialog.get()).filter(c -> "local-import.templates".equals(c.getName())).findFirst().orElseThrow();
                    input.setText("D:/{artwork_id}_{" + variable + "}.png");
                    button(dialog.get(), "scan").doClick();
                });
                String expected = "local-import.error." + scanFailure.get().getMessage() + "[{" + variable + "}]";
                await(() -> descendants(dialog.get()).filter(JLabel.class::isInstance).map(JLabel.class::cast)
                        .anyMatch(label -> expected.equals(label.getText())));
                assertFalse(button(dialog.get(), "start").isEnabled());
            }
        } finally { SwingUtilities.invokeAndWait(() -> { panel.get().close(); frame.get().dispose(); }); }
    }
    private static JButton button(Container container, String name) {
        return descendants(container).filter(JButton.class::isInstance).map(JButton.class::cast)
                .filter(b -> b.getText().equals("local-import." + name)).findFirst().orElseThrow();
    }
    private static Stream<Component> descendants(Container container) {
        return Arrays.stream(container.getComponents()).flatMap(c -> c instanceof Container child ? Stream.concat(Stream.of(c), descendants(child)) : Stream.of(c));
    }
    private static void await(java.util.function.BooleanSupplier condition) {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            var done = new AtomicBoolean();
            do { SwingUtilities.invokeAndWait(() -> done.set(condition.getAsBoolean())); if (!done.get()) Thread.sleep(20); } while (!done.get());
        });
    }
}
