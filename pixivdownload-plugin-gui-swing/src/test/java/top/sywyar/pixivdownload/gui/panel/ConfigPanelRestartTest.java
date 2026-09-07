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
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
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

    @Test
    @DisplayName("缺项空值和不可用偏好自动回退后保存不改写也不提示重启")
    void fallbackPreferencesRemainUnchangedOnSave() throws Exception {
        for (Map<String, String> stored : List.of(
                Map.<String, String>of(),
                Map.of(
                        "app.language",
                        "",
                        "app.gui-provider",
                        "",
                        "app.theme",
                        "",
                        "app.config-menu-expand-all",
                        ""
                ),
                Map.of(
                        "app.language",
                        "unknown",
                        "app.gui-provider",
                        "removed-ui",
                        "app.theme",
                        "unavailable",
                        "app.config-menu-expand-all",
                        "false"
                ),
                Map.of(
                        "app.language",
                        "zh-cn",
                        "app.gui-provider",
                        " gui-swing ",
                        "app.theme",
                        "dark",
                        "app.config-menu-expand-all",
                        "TRUE"
                )
        )) {
            SwingUtilities.invokeAndWait(() -> {
                MemoryConfigFile config = installHost(stored);
                AtomicInteger restarts = new AtomicInteger();
                ConfigPanel panel = preferencePanel(restarts);

                findButton(panel, GuiMessages.get("gui.button.save")).doClick();

                assertThat(config.values).isEqualTo(stored);
                assertThat(config.writes).isZero();
                assertThat(restarts).hasValue(0);
            });
        }
    }

    @Test
    @DisplayName("提供者还原后不写配置而实际切换保存回退主题且不重复提示重启")
    void providerRevertAndSaveKeepAnIndependentBaseline() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Map<String, String> stored = Map.of(
                    "app.language",
                    "unknown",
                    "app.gui-provider",
                    "removed-ui",
                    "app.theme",
                    "unavailable"
            );
            MemoryConfigFile config = installHost(stored);
            AtomicInteger restarts = new AtomicInteger();
            ConfigPanel panel = preferencePanel(restarts);
            selectProvider(panel, "gui-compose");
            selectProvider(panel, "gui-swing");
            findButton(panel, GuiMessages.get("gui.button.save")).doClick();
            assertThat(config.values).isEqualTo(stored);
            assertThat(config.writes).isZero();
            assertThat(restarts).hasValue(0);

            selectProvider(panel, "gui-compose");
            findButton(panel, GuiMessages.get("gui.button.save")).doClick();
            assertThat(config.values).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "app.language",
                    "unknown",
                    "app.gui-provider",
                    "gui-compose",
                    "app.theme",
                    "system"
            ));
            assertThat(config.writes).isEqualTo(1);
            assertThat(restarts).hasValue(1);

            findButton(panel, GuiMessages.get("gui.button.save")).doClick();
            assertThat(config.writes).isEqualTo(1);
            assertThat(restarts).hasValue(1);
        });
    }

    @Test
    @DisplayName("面板重建保留偏好草稿且只持久化实际修改的偏好")
    void rebuiltPanelKeepsDraftSeparateFromSavedPreferences() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Map<String, String> stored = Map.of(
                    "app.gui-provider",
                    "removed-ui",
                    "app.theme",
                    "unavailable"
            );
            MemoryConfigFile config = installHost(stored);
            Map<String, String> draft = new LinkedHashMap<>();
            ConfigPanel first = preferencePanel(draft);
            JCheckBox expand = preferenceControl(
                    first,
                    InterfacePreferencesPanel.EXPAND_ALL_CONFIG_KEY,
                    JCheckBox.class
            );
            expand.doClick();
            expand.doClick();
            findButton(first, GuiMessages.get("gui.button.save")).doClick();
            assertThat(config.writes).isZero();

            expand.doClick();
            ConfigPanel rebuilt = preferencePanel(draft);
            assertThat(preferenceControl(
                    rebuilt,
                    InterfacePreferencesPanel.EXPAND_ALL_CONFIG_KEY,
                    JCheckBox.class
            ).isSelected()).isTrue();
            findButton(rebuilt, GuiMessages.get("gui.button.save")).doClick();
            assertThat(config.values).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "app.gui-provider",
                    "removed-ui",
                    "app.theme",
                    "unavailable",
                    "app.config-menu-expand-all",
                    "true"
            ));
            assertThat(config.writes).isEqualTo(1);
            assertThat(draft).isEmpty();

            ConfigPanel saved = preferencePanel(draft);
            findButton(saved, GuiMessages.get("gui.button.save")).doClick();
            assertThat(config.writes).isEqualTo(1);
        });
    }

    private ConfigPanel preferencePanel(AtomicInteger restarts) {
        return new ConfigPanel(
                tempDir.resolve("config.yaml"),
                6999,
                path -> path,
                new ConfigFieldSnapshot(List.of(), List.of(), List.of()),
                null,
                null,
                () -> { restarts.incrementAndGet(); return false; },
                () -> false,
                () -> { restarts.incrementAndGet(); return false; },
                () -> false
        );
    }

    @Test
    @DisplayName("隐藏的证书配置无修改保存时保持原值且保留隐藏前的编辑")
    void hiddenCertificateFieldsPreserveValuesAndEdits() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Map<String, String> stored = Map.of(
                    "ssl.type",
                    "pem",
                    "server.ssl.key-store-type",
                    "JKS",
                    "server.ssl.key-store",
                    "certificate.jks",
                    "server.ssl.key-store-password",
                    "fixture-password"
            );
            MemoryConfigFile config = installHost(stored);
            String group = "Certificates";
            List<ConfigFieldSpec> fields = List.of(
                    ConfigFieldSpec.builder(
                                    "ssl.type",
                                    "Type",
                                    FieldType.ENUM,
                                    group
                            )
                            .enumValues("pem", "jks").defaultValue("pem").build(),
                    ConfigFieldSpec.builder(
                                    "server.ssl.key-store-type",
                                    "Store type",
                                    FieldType.STRING,
                                    group
                            )
                            .defaultValue("JKS").visibleWhen(snapshot -> snapshot.equals("ssl.type", "jks")).build(),
                    ConfigFieldSpec.builder(
                                    "server.ssl.key-store",
                                    "Store",
                                    FieldType.PATH_FILE,
                                    group
                            )
                            .visibleWhen(snapshot -> snapshot.equals("ssl.type", "jks")).build(),
                    ConfigFieldSpec.builder(
                                    "server.ssl.key-store-password",
                                    "Password",
                                    FieldType.PASSWORD,
                                    group
                            )
                            .visibleWhen(snapshot -> snapshot.equals("ssl.type", "jks")).build()
            );
            AtomicInteger restarts = new AtomicInteger();
            ConfigPanel panel = new ConfigPanel(
                    tempDir.resolve("config.yaml"),
                    6999,
                    path -> path,
                    new ConfigFieldSnapshot(List.of(group), fields, List.of()),
                    null,
                    null,
                    () -> { restarts.incrementAndGet(); return false; },
                    () -> false,
                    () -> { restarts.incrementAndGet(); return false; },
                    () -> false
            );

            findButton(panel, GuiMessages.get("gui.button.save")).doClick();
            assertThat(config.values).isEqualTo(stored);
            assertThat(restarts).hasValue(0);

            panel.setFieldValue("ssl.type", "jks");
            panel.setFieldValue("server.ssl.key-store", "updated.jks");
            panel.setFieldValue("ssl.type", "pem");
            panel.updateEnabledStates();
            findButton(panel, GuiMessages.get("gui.button.save")).doClick();
            Map<String, String> edited = new LinkedHashMap<>(stored);
            edited.put("server.ssl.key-store", "updated.jks");
            assertThat(config.values).isEqualTo(edited);
            assertThat(restarts).hasValue(1);

            findButton(panel, GuiMessages.get("gui.button.save")).doClick();
            assertThat(restarts).hasValue(1);
        });
    }

    private ConfigPanel preferencePanel(Map<String, String> draft) {
        return new ConfigPanel(
                tempDir.resolve("config.yaml"),
                6999,
                path -> path,
                new ConfigFieldSnapshot(List.of(), List.of(), List.of()),
                null,
                null,
                draft
        );
    }

    private static void selectProvider(ConfigPanel panel, String providerId) {
        JComboBox<?> combo = preferenceControl(
                panel,
                InterfacePreferencesPanel.GUI_PROVIDER_CONFIG_KEY,
                JComboBox.class
        );
        for (int index = 0; index < combo.getItemCount(); index++) {
            if (combo.getItemAt(index) instanceof InterfacePreferencesPanel.ProviderOption option
                    && providerId.equals(option.id())) {
                combo.setSelectedItem(option);
                return;
            }
        }
        throw new AssertionError("provider not found: " + providerId);
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
                    case "guiGet", "guiPostJson" -> DesktopUiHost.GuiResponse.unreachable();
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
                false, 6999, ".", tempDir.resolve("config.yaml"), "gui-swing", host,
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
        private int writes;

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

        @Override public void writeAll(Map<String, String> updates) {
            writes++;
            values.putAll(updates);
        }
        @Override public void removeAll(Collection<String> keys) { keys.forEach(values::remove); }
        @Override public DesktopUiHost.ConfigSnapshot snapshot() {
            return new DesktopUiHost.ConfigSnapshot(true, List.of());
        }
        @Override public void restore(DesktopUiHost.ConfigSnapshot snapshot) { }
    }
}
