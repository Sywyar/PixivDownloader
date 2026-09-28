package top.sywyar.pixivdownload.gui.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldType;

import javax.swing.JButton;
import javax.swing.SwingUtilities;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MultiEnumFieldTest {
    @org.junit.jupiter.api.BeforeEach
    void installHost() {
        var locale = new top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost.UiLocale("en-US", "English", "en");
        var host = (top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("coreConfigGroups") || method.getName().equals("coreConfigFields")) return List.of();
                    if (method.getName().equals("resolveLocale")) {
                        return new top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost.UiLocaleResolution(locale, List.of(locale));
                    }
                    throw new AssertionError(method.getName());
                });
        top.sywyar.pixivdownload.guiswing.SwingHost.install(
                new top.sywyar.pixivdownload.plugin.api.gui.DesktopUiContext(false, 6999, ".",
                        java.nio.file.Path.of("config.yaml"), "gui-swing", host, List.of(), List::of,
                        text -> text.fallback(), () -> "system"));
    }

    @Test
    @DisplayName("多选字段往返保存选择并只显示选中项")
    void roundTripsSelectedValues() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var spec = ConfigFieldSpec.builder("sample.outputs", "Outputs", FieldType.MULTI_ENUM, "Download")
                    .defaultValue("original").enumValues("original", "png", "jpg", "webp")
                    .enumValueLabels(Map.of("original", "Original", "png", "PNG", "jpg", "JPG", "webp", "WebP"))
                    .build();
            var field = FieldRenderer.render(spec);
            assertEquals("original", field.getValue().get());
            field.setValue().accept("png,webp");
            assertEquals("png,webp", field.getValue().get());
            assertEquals("PNG, WebP  ⌄", ((JButton) field.control()).getText());
            field.setValue().accept("jpg");
            assertEquals("jpg", field.getValue().get());
        });
    }

    @Test
    @DisplayName("多选标量拒绝空选择重复值和未知值")
    void rejectsInvalidSelections() {
        var options = List.of("original", "png", "jpg", "webp");
        assertTrue(GuiConfigFieldType.validMultiSelection("png,webp", options));
        for (String invalid : List.of("", "png,png", "png,", ",jpg", "PNG", "png,unknown")) {
            assertFalse(GuiConfigFieldType.validMultiSelection(invalid, options), invalid);
        }
    }
}
