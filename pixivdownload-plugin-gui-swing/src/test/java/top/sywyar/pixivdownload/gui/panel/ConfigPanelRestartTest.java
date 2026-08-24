package top.sywyar.pixivdownload.gui.panel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.gui.config.ConfigFieldSnapshot;
import top.sywyar.pixivdownload.gui.config.ConfigFieldSpec;
import top.sywyar.pixivdownload.gui.config.FieldType;
import top.sywyar.pixivdownload.gui.i18n.GuiMessages;
import top.sywyar.pixivdownload.guiswing.SwingHost;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiContext;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiPluginSnapshot;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigEffect;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Swing 配置页重启等级")
class ConfigPanelRestartTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("完整重启字段不会误走后端重启")
    void processRestartSettingUsesFullApplicationRestart() {
        MemoryConfigFile config = installHost(Map.of(
                "fixture.restart", "before",
                "app.language", "follow-system",
                "app.gui-provider", "gui-swing",
                "app.theme", "system",
                "app.config-menu-expand-all", "false"));
        AtomicInteger backendConfirmations = new AtomicInteger();
        AtomicInteger backendRestarts = new AtomicInteger();
        AtomicInteger processConfirmations = new AtomicInteger();
        AtomicInteger processRestarts = new AtomicInteger();
        String group = "Fixture";
        ConfigFieldSpec field = ConfigFieldSpec.builder(
                        "fixture.restart", "Restart fixture", FieldType.STRING, group)
                .defaultValue("before")
                .effect(GuiConfigEffect.PROCESS_RESTART)
                .build();
        ConfigPanel panel = new ConfigPanel(tempDir.resolve("config.yaml"), 6999, path -> path,
                new ConfigFieldSnapshot(List.of(group), List.of(field), List.of()),
                null, null,
                () -> backendConfirmations.incrementAndGet() == 1,
                () -> backendRestarts.incrementAndGet() == 1,
                () -> processConfirmations.incrementAndGet() == 1,
                () -> processRestarts.incrementAndGet() == 1);
        panel.setFieldValue("fixture.restart", "after");

        findButton(panel, GuiMessages.get("gui.button.save")).doClick();

        assertThat(processConfirmations).hasValue(1);
        assertThat(processRestarts).hasValue(1);
        assertThat(backendConfirmations).hasValue(0);
        assertThat(backendRestarts).hasValue(0);
        assertThat(config.values).containsEntry("fixture.restart", "after");
    }

    @Test
    @DisplayName("桌面 UI 提供者只由统一保存入口持久化并请求完整重启")
    void providerSelectionUsesUnifiedSaveAndFullRestart() {
        MemoryConfigFile config = installHost(Map.of(
                "app.language", "follow-system",
                "app.gui-provider", "gui-swing",
                "app.theme", "moonlight",
                "app.config-menu-expand-all", "false"));
        AtomicInteger backendRestarts = new AtomicInteger();
        AtomicInteger processRestarts = new AtomicInteger();
        ConfigPanel panel = new ConfigPanel(tempDir.resolve("config.yaml"), 6999, path -> path,
                new ConfigFieldSnapshot(List.of(), List.of(), List.of()),
                null, null,
                () -> false,
                () -> backendRestarts.incrementAndGet() == 1,
                () -> true,
                () -> processRestarts.incrementAndGet() == 1);
        JComboBox<?> provider = preferenceControl(
                panel, InterfacePreferencesPanel.GUI_PROVIDER_CONFIG_KEY, JComboBox.class);
        Object compose = null;
        for (int index = 0; index < provider.getItemCount(); index++) {
            Object option = provider.getItemAt(index);
            if (option instanceof InterfacePreferencesPanel.ProviderOption value
                    && "gui-compose".equals(value.id())) {
                compose = option;
                break;
            }
        }
        provider.setSelectedItem(compose);

        assertThat(config.values).containsEntry("app.gui-provider", "gui-swing");
        findButton(panel, GuiMessages.get("gui.button.save")).doClick();

        assertThat(config.values)
                .containsEntry("app.gui-provider", "gui-compose")
                .containsEntry("app.theme", "system");
        assertThat(processRestarts).hasValue(1);
        assertThat(backendRestarts).hasValue(0);
    }

    @SuppressWarnings("unchecked")
    private MemoryConfigFile installHost(Map<String, String> values) {
        MemoryConfigFile config = new MemoryConfigFile(values);
        DesktopUiHost.UiLocale locale = new DesktopUiHost.UiLocale("zh-CN", "简体中文", "");
        DesktopUiHost host = (DesktopUiHost) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{DesktopUiHost.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "applicationConfig", "pluginConfig" -> config;
                    case "coreConfigGroups", "coreConfigFields" -> List.of();
                    case "visibleLocales" -> List.of(locale);
                    case "matchLocale" -> Optional.of(locale).filter(ignored ->
                            args != null && args.length > 0 && "zh-CN".equalsIgnoreCase(String.valueOf(args[0])));
                    case "resolveLocale" -> new DesktopUiHost.UiLocaleResolution(locale, List.of(locale));
                    case "detectSystemLocale" -> Locale.SIMPLIFIED_CHINESE;
                    case "requireSafeConfigKey", "requireSafeConfigValue" -> args[0];
                    case "validatedConfigKeys" -> Set.copyOf((Collection<String>) args[0]);
                    case "validatedConfigValues" -> Map.copyOf((Map<String, String>) args[0]);
                    case "withCredentialLocks" -> {
                        ((DesktopUiHost.IoOperation) args[1]).run();
                        yield null;
                    }
                    case "message" -> args[0];
                    case "autoStartSupported", "autoStartEnabled", "launchedFromExecutable",
                         "currentVersionNightly", "supportsManagedFfmpegInstall" -> false;
                    case "defaultProxyPort", "minimumPasswordLength", "recommendedPasswordLength" -> 1;
                    case "applicationName", "applicationVersion", "projectUrl", "releasesUrl",
                         "defaultUpdateManifestUrl", "defaultNightlyUpdateManifestUrl", "guiToken",
                         "guiTokenHeader", "defaultProxyHost", "defaultMaintenanceTime" -> "test";
                    case "reservedPluginRepositoryIds" -> Set.of();
                    case "readCredentials" -> Map.of();
                    case "toString" -> "TestDesktopUiHost";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
        DesktopUiPluginSnapshot swing = new DesktopUiPluginSnapshot(
                "gui-swing", true, "gui-swing", 1L, true,
                null, "", List.of(), List.of(), List.of(), List.of(), List.of());
        DesktopUiPluginSnapshot compose = new DesktopUiPluginSnapshot(
                "gui-compose", true, "gui-compose", 1L, true,
                null, "", List.of(), List.of(), List.of(), List.of(), List.of());
        List<DesktopUiPluginSnapshot> snapshots = List.of(swing, compose);
        SwingHost.install(new DesktopUiContext(
                false, 6999, ".", tempDir.resolve("config.yaml"), host,
                snapshots, () -> snapshots, text -> text.fallback(), () -> "system"));
        return config;
    }

    private static <T extends JComponent> T preferenceControl(
            Container root, String preferenceKey, Class<T> type) {
        for (Component component : root.getComponents()) {
            if (type.isInstance(component)
                    && preferenceKey.equals(((JComponent) component).getClientProperty(
                    InterfacePreferencesPanel.PREFERENCE_KEY_PROPERTY))) {
                return type.cast(component);
            }
            if (component instanceof Container child) {
                try {
                    return preferenceControl(child, preferenceKey, type);
                } catch (AssertionError ignored) {
                    // Continue with the next sibling.
                }
            }
        }
        throw new AssertionError("preference control not found: " + preferenceKey);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        return null;
    }

    private static JButton findButton(Container root, String text) {
        for (Component component : root.getComponents()) {
            if (component instanceof JButton button && text.equals(button.getText())) {
                return button;
            }
            if (component instanceof Container child) {
                JButton found = findButtonOrNull(child, text);
                if (found != null) return found;
            }
        }
        throw new AssertionError("button not found: " + text);
    }

    private static JButton findButtonOrNull(Container root, String text) {
        for (Component component : root.getComponents()) {
            if (component instanceof JButton button && text.equals(button.getText())) return button;
            if (component instanceof Container child) {
                JButton found = findButtonOrNull(child, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static final class MemoryConfigFile implements DesktopUiHost.ConfigFile {
        private final Map<String, String> values = new LinkedHashMap<>();

        private MemoryConfigFile(Map<String, String> values) {
            this.values.putAll(values);
        }

        @Override
        public Map<String, String> readAll(Collection<String> keys) {
            Map<String, String> result = new LinkedHashMap<>();
            keys.forEach(key -> {
                if (values.containsKey(key)) result.put(key, values.get(key));
            });
            return result;
        }

        @Override public void writeAll(Map<String, String> updates) { values.putAll(updates); }
        @Override public void removeAll(Collection<String> keys) { keys.forEach(values::remove); }
        @Override public DesktopUiHost.ConfigSnapshot snapshot() {
            return new DesktopUiHost.ConfigSnapshot(true, List.of());
        }
        @Override public void restore(DesktopUiHost.ConfigSnapshot snapshot) { }
    }
}
