package top.sywyar.pixivdownload.gui.config;

import org.junit.jupiter.api.*;
import top.sywyar.pixivdownload.guiswing.SwingHost;
import top.sywyar.pixivdownload.plugin.api.gui.*;
import top.sywyar.pixivdownload.plugin.api.gui.importing.LocalArtworkImports.Preset;
import javax.swing.*;
import java.awt.*;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.text.MessageFormat;
import java.time.Duration;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class LocalImportPresetEditorTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    @DisplayName("原生路径编辑器保留取消和单元格提交语义，工具入口独立保存")
    @SuppressWarnings("unchecked")
    void editsPathsWithExplicitSave(boolean persistent) throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "需要桌面窗口环境");
        var labels = new Properties();
        try (var reader = Files.newBufferedReader(Path.of("../pixivdownload-app/src/main/resources/i18n/messages_en.properties"))) { labels.load(reader); }
        var original = List.of(new Preset("D:/作品/{artwork_id}/"), new Preset("E:/{artwork_id}/"), new Preset("F:/作者/{author_id}/{artwork_id}/"));
        var formatted = new AtomicReference<List<Preset>>();
        var saved = new AtomicReference<String>();
        var callback = new java.util.concurrent.atomic.AtomicInteger();
        var config = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DesktopUiHost.ConfigFile.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("readAll")) return Map.of("local-import.presets", "original");
                    throw new AssertionError("Unexpected configuration operation: " + method.getName());
                });
        var host = (DesktopUiHost) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DesktopUiHost.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "parseLocalImportPresets" -> args[0].equals("[]") ? List.of() : args[0].equals("original") ? original : formatted.get();
                    case "applicationConfig" -> config;
                    case "saveLocalImportPresets" -> { saved.set((String) args[0]); yield null; }
                    case "formatLocalImportPresets" -> { formatted.set((List<Preset>) args[0]); yield "draft"; }
                    case "message" -> new MessageFormat(labels.getProperty(args[0].toString(), args[0].toString()), Locale.US).format(args[1]);
                    default -> throw new AssertionError("Unexpected host operation: " + method.getName());
                });
        SwingHost.install(new DesktopUiContext(false, 8080, ".", Path.of("config.yaml"), "swing", host,
                List.of(), List::of, DesktopUiText::fallback, () -> "system"));
        var frame = new AtomicReference<JFrame>(); var entry = new AtomicReference<LocalImportPresetEditor>();
        SwingUtilities.invokeAndWait(() -> {
            entry.set(persistent ? (LocalImportPresetEditor) LocalImportPresetEditor.savedPresets(callback::incrementAndGet)
                    : new LocalImportPresetEditor("original"));
            frame.set(new JFrame("Preset editor verification"));
            frame.get().add(entry.get()); frame.get().setSize(700, 400); frame.get().setVisible(true);
        });
        try {
            for (boolean cancel : List.of(true, false)) {
                SwingUtilities.invokeLater(() -> entry.get().doClick());
                var dialog = new AtomicReference<JDialog>();
                assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                    while (dialog.get() == null) {
                        SwingUtilities.invokeAndWait(() -> Arrays.stream(frame.get().getOwnedWindows())
                                .filter(Window::isShowing).filter(JDialog.class::isInstance).map(JDialog.class::cast).findFirst().ifPresent(dialog::set));
                        if (dialog.get() == null) Thread.sleep(20);
                    }
                });
                SwingUtilities.invokeAndWait(() -> {
                    var window = dialog.get(); window.setSize(cancel ? 760 : 520, cancel ? 480 : 360); window.validate();
                    var table = descendants(window).filter(JTable.class::isInstance).map(JTable.class::cast).findFirst().orElseThrow();
                    assertEquals(original.get(1).template(), table.getValueAt(1, 0));
                    table.setRowSelectionInterval(1, 1); button(window, labels.getProperty("local-import.remove")).doClick();
                    assertEquals(original.get(2).template(), table.getValueAt(1, 0));
                    button(window, labels.getProperty("local-import.add")).doClick();
                    assertTrue(table.isEditing());
                    ((JTextField) table.getEditorComponent()).setText("G:/{artwork_id}/");
                    var apply = button(window, labels.getProperty(persistent ? "local-import.save" : "local-import.apply"));
                    var dismiss = button(window, labels.getProperty("local-import.cancel"));
                    for (var action : List.of(apply, dismiss)) {
                        var bounds = SwingUtilities.convertRectangle(action.getParent(), action.getBounds(), window.getContentPane());
                        assertTrue(window.getContentPane().getBounds().contains(bounds), bounds.toString());
                    }
                    if (cancel) dismiss.doClick(); else apply.doClick();
                });
                if (cancel) { assertEquals("original", entry.get().value()); assertNull(formatted.get()); assertNull(saved.get()); }
                else {
                    if (persistent) assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                        while (callback.get() == 0) Thread.sleep(20);
                    });
                    assertEquals("draft", entry.get().value());
                    assertEquals(List.of(original.get(0), original.get(2), new Preset("G:/{artwork_id}/")), formatted.get());
                    assertEquals(persistent ? "draft" : null, saved.get());
                }
            }
        } finally { SwingUtilities.invokeAndWait(() -> frame.get().dispose()); }
    }
    private static JButton button(Container parent, String label) {
        return descendants(parent).filter(JButton.class::isInstance).map(JButton.class::cast)
                .filter(button -> label.equals(button.getText())).findFirst().orElseThrow();
    }
    private static Stream<Component> descendants(Container parent) {
        return Arrays.stream(parent.getComponents()).flatMap(c -> c instanceof Container child
                ? Stream.concat(Stream.of(c), descendants(child)) : Stream.of(c));
    }
}
