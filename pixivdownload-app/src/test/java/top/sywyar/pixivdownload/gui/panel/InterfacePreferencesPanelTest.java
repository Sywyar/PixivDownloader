package top.sywyar.pixivdownload.gui.panel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.gui.i18n.GuiMessages;

import javax.swing.*;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("界面偏好配置页")
class InterfacePreferencesPanelTest {

    @TempDir
    Path tempDir;

    private Locale originalLocale;

    @BeforeEach
    void setUp() {
        originalLocale = Locale.getDefault();
        GuiMessages.setLocale(Locale.SIMPLIFIED_CHINESE);
    }

    @AfterEach
    void tearDown() {
        Locale.setDefault(originalLocale);
        GuiMessages.clearLocaleOverride();
    }

    @Test
    @DisplayName("同时呈现语言主题和菜单展开三项偏好")
    void exposesLanguageThemeAndMenuExpansionPreferences() throws Exception {
        Path configPath = tempDir.resolve("config.yaml");
        Files.writeString(configPath, """
                app.language: en-US
                app.theme: system
                app.config-menu-expand-all: true
                """, StandardCharsets.UTF_8);

        InterfacePreferencesPanel panel = new InterfacePreferencesPanel(
                configPath, () -> { }, () -> false, ignored -> { });

        JComboBox<?> language = preferenceControl(panel, "app.language", JComboBox.class);
        JComboBox<?> theme = preferenceControl(panel, "app.theme", JComboBox.class);
        JCheckBox expandAll = preferenceControl(
                panel, InterfacePreferencesPanel.EXPAND_ALL_CONFIG_KEY, JCheckBox.class);

        assertThat(language.getSelectedItem()).hasToString("English");
        assertThat(theme.getItemCount()).isPositive();
        assertThat(expandAll.isSelected()).isTrue();
        assertThat(expandAll.getText()).isEmpty();
        assertThat(theme.getParent().getClass()).isEqualTo(language.getParent().getClass());
        assertThat(expandAll.getParent().getClass()).isEqualTo(language.getParent().getClass());
        SwingUtilities.invokeAndWait(() -> {
            layoutFieldRow(language);
            layoutFieldRow(theme);
            layoutFieldRow(expandAll);
        });
        assertThat(theme.getX()).isEqualTo(language.getX());
        assertThat(expandAll.getX()).isEqualTo(language.getX());
        assertThat(descendants(panel, JLabel.class))
                .extracting(JLabel::getText)
                .contains(
                        GuiMessages.get("gui.interface.config-menu-expand-all.label")
                                + GuiMessages.get("gui.punctuation.colon"),
                        GuiMessages.get("gui.label.hot-reload"));
        assertThat(descendants(panel, JTextArea.class))
                .extracting(JTextArea::getText)
                .contains(GuiMessages.get("gui.interface.config-menu-expand-all.help"));
    }

    @Test
    @DisplayName("语言切换写入配置并异步通知主窗口重建界面")
    void languageSelectionPersistsAndNotifiesMainWindow() throws Exception {
        Path configPath = tempDir.resolve("config.yaml");
        Files.writeString(configPath, """
                app.language: en-US
                app.theme: system
                app.config-menu-expand-all: false
                """, StandardCharsets.UTF_8);
        AtomicInteger localeChanges = new AtomicInteger();
        InterfacePreferencesPanel panel = new InterfacePreferencesPanel(
                configPath, localeChanges::incrementAndGet, () -> false, ignored -> { });
        JComboBox<?> language = preferenceControl(panel, "app.language", JComboBox.class);

        SwingUtilities.invokeAndWait(() -> language.setSelectedIndex(2));
        SwingUtilities.invokeAndWait(() -> { });

        assertThat(Files.readString(configPath, StandardCharsets.UTF_8))
                .contains("app.language: zh-CN");
        assertThat(Locale.getDefault()).isEqualTo(Locale.SIMPLIFIED_CHINESE);
        assertThat(localeChanges).hasValue(1);
    }

    @Test
    @DisplayName("菜单展开切换即时回调并持久化布尔值")
    void menuExpansionSelectionPersistsAndNotifiesRenderer() throws Exception {
        Path configPath = tempDir.resolve("config.yaml");
        Files.writeString(configPath, """
                app.language:
                app.theme: system
                app.config-menu-expand-all: false
                """, StandardCharsets.UTF_8);
        AtomicBoolean expanded = new AtomicBoolean();
        InterfacePreferencesPanel panel = new InterfacePreferencesPanel(
                configPath, () -> { }, () -> false, expanded::set);
        JCheckBox expandAll = preferenceControl(
                panel, InterfacePreferencesPanel.EXPAND_ALL_CONFIG_KEY, JCheckBox.class);

        SwingUtilities.invokeAndWait(expandAll::doClick);

        assertThat(expanded).isTrue();
        assertThat(Files.readString(configPath, StandardCharsets.UTF_8))
                .contains("app.config-menu-expand-all: true");
    }

    private static <T extends JComponent> T preferenceControl(
            Container root, String preferenceKey, Class<T> type) {
        for (Component component : root.getComponents()) {
            if (type.isInstance(component)
                    && preferenceKey.equals(((JComponent) component).getClientProperty(
                    InterfacePreferencesPanel.PREFERENCE_KEY_PROPERTY))) {
                return type.cast(component);
            }
            if (component instanceof Container container) {
                try {
                    return preferenceControl(container, preferenceKey, type);
                } catch (AssertionError ignored) {
                    // Continue searching sibling components.
                }
            }
        }
        throw new AssertionError("Preference control not found: " + preferenceKey);
    }

    private static void layoutFieldRow(JComponent control) {
        Container row = control.getParent();
        row.setSize(900, 200);
        Dimension preferred = row.getPreferredSize();
        row.setSize(900, preferred.height);
        row.doLayout();
    }

    private static <T extends Component> java.util.List<T> descendants(Container root, Class<T> type) {
        java.util.List<T> matches = new java.util.ArrayList<>();
        for (Component component : root.getComponents()) {
            if (type.isInstance(component)) {
                matches.add(type.cast(component));
            }
            if (component instanceof Container container) {
                matches.addAll(descendants(container, type));
            }
        }
        return java.util.List.copyOf(matches);
    }
}
