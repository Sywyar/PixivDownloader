package top.sywyar.pixivdownload.gui.panel.configtab;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.gui.config.*;
import top.sywyar.pixivdownload.guiswing.SwingHost;
import top.sywyar.pixivdownload.plugin.api.gui.*;

import javax.swing.*;
import java.awt.*;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class DirectoryTemplatesEditorTest {
    @Test
    @DisplayName("Swing 目录覆盖加载后只显示已配置模式，切换移除保留统一保存字段")
    void editsRegisteredFields() throws Exception {
        DesktopUiHost host = (DesktopUiHost) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{DesktopUiHost.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "message" -> args[0];
                    case "coreConfigGroups", "coreConfigFields", "coreConfigSections" -> List.of();
                    case "resolveLocale" -> {
                        var locale = new DesktopUiHost.UiLocale("en-US", "English", "en");
                        yield new DesktopUiHost.UiLocaleResolution(locale, List.of(locale));
                    }
                    default -> throw new AssertionError(method.getName());
                });
        SwingHost.install(new DesktopUiContext(false, 6999, ".", Path.of("config.yaml"), "gui-swing",
                host, List.of(), List::of, text -> text.fallback(), () -> "system"));
        var fields = List.of(field("root", "pictures/{artwork_id}"), field("user", "pictures/{username}/{artwork_id}"), field("series", ""));
        var inputs = new LinkedHashMap<String, FieldRenderer.RenderedField>();
        ConfigSectionContext ctx = (ConfigSectionContext) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ConfigSectionContext.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "desktopHost" -> host;
                    case "allFields" -> fields;
                    case "registerField" -> { inputs.put(((ConfigFieldSpec) args[0]).key(), (FieldRenderer.RenderedField) args[1]); yield null; }
                    case "currentFieldValue" -> inputs.containsKey(args[0]) ? inputs.get(args[0]).getValue().get() : "";
                    case "setFieldValue" -> { inputs.get(args[0]).setValue().accept((String) args[1]); yield null; }
                    case "addFields" -> null;
                    default -> throw new AssertionError(method.getName());
                });
        var section = new GuiConfigSectionSpec("independent", "directories", "download", "Download", 0,
                "Directories", "", "", "", "", "", List.of(), GuiConfigSectionLayout.DIRECTORY_TEMPLATES, 0,
                List.of(new GuiConfigFieldLayoutSpec("root", null, "", 0),
                        new GuiConfigFieldLayoutSpec("user", "user", "User", 1),
                        new GuiConfigFieldLayoutSpec("series", "series", "Series", 2)),
                List.of(), List.of(), false, true);
        SwingUtilities.invokeAndWait(() -> {
            var editor = new DirectoryTemplatesEditor(ctx, section, fields);
            editor.valuesLoaded();
            assertThat(inputs).containsOnlyKeys("root", "user", "series");
            inputs.get("root").setValue().accept("draft/{artwork_id}");
            var help = descendants(editor).filter(JTextArea.class::isInstance).map(JTextArea.class::cast)
                    .filter(area -> area.getText().equals("Template help")).findFirst().orElseThrow();
            var toggle = descendants(editor).filter(JToggleButton.class::isInstance).map(JToggleButton.class::cast)
                    .findFirst().orElseThrow();
            assertThat(help.isVisible()).isFalse();
            toggle.doClick();
            assertThat(help.isVisible()).isTrue();
            help.getActionMap().get("close-help").actionPerformed(null);
            assertThat(help.isVisible()).isFalse();
            assertThat(inputs.get("root").getValue().get()).isEqualTo("draft/{artwork_id}");
            assertThat(inputs.get("user").panel().isVisible()).isTrue();
            assertThat(inputs.get("series").panel().isVisible()).isFalse();
            var mode = (JComboBox<?>) inputs.get("user").panel().getComponent(0);
            mode.setSelectedItem("series");
            assertThat(inputs.get("user").getValue().get()).isEmpty();
            assertThat(inputs.get("series").getValue().get()).isEqualTo("pictures/{username}/{artwork_id}");
            assertThat(inputs.get("series").panel().isVisible()).isTrue();
            button(inputs.get("series").panel(), "gui.config.directory.remove").doClick();
            assertThat(inputs.get("series").getValue().get()).isEmpty();
            assertThat(inputs.get("user").panel().isVisible()).isFalse();
            assertThat(inputs.get("series").panel().isVisible()).isFalse();
            assertThat(inputs.get("root").panel().isVisible()).isTrue();
            button(editor, "gui.config.directory.add").doClick();
            assertThat(inputs.get("user").panel().isVisible()).isTrue();
            assertThat(inputs.get("user").getValue().get()).isEmpty();
            editor.valuesLoaded();
            assertThat(inputs.get("user").panel().isVisible()).isFalse();
            inputs.values().forEach(input -> input.control().setEnabled(false));
            editor.refreshVisibility();
            assertThat(button(editor, "gui.config.directory.add").isEnabled()).isFalse();
        });
    }

    private static ConfigFieldSpec field(String key, String value) {
        return ConfigFieldSpec.builder(key, key, FieldType.STRING, "Download")
                .ownerPluginId("independent").help(key.equals("root") ? "Template help" : "").defaultValue(value).build();
    }

    private static JButton button(Component root, String label) {
        return descendants(root).filter(JButton.class::isInstance).map(JButton.class::cast)
                .filter(button -> button.getText().equals(label)).findFirst().orElseThrow();
    }

    private static Stream<Component> descendants(Component root) {
        return Stream.concat(Stream.of(root), root instanceof Container parent
                ? Arrays.stream(parent.getComponents()).flatMap(DirectoryTemplatesEditorTest::descendants) : Stream.empty());
    }
}
