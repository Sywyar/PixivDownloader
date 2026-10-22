package top.sywyar.pixivdownload.gui.panel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.sywyar.pixivdownload.gui.config.ConfigFieldSnapshot;
import top.sywyar.pixivdownload.gui.config.ConfigFieldSpec;
import top.sywyar.pixivdownload.gui.config.FieldType;
import top.sywyar.pixivdownload.gui.i18n.GuiMessages;
import top.sywyar.pixivdownload.guiswing.SwingHost;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiContext;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiPluginSnapshot;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigEffect;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigCondition;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldType;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
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
    private top.sywyar.pixivdownload.plugin.api.gui.DesktopDatabaseSettings databaseSettings;
    private java.util.function.Supplier<DesktopUiHost.GuiResponse> reloadResponse = DesktopUiHost.GuiResponse::unreachable;

    @Test
    @DisplayName("地址与凭据按声明联动，切回本地库和重新加载均隐藏字段")
    void connectionFieldsFollowSelectionAndReload() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var config = installHost(Map.of("database.provider", "remote"),
                    new DesktopUiHost.UiLocale("en-US", "English", ""));
            String group = "Database";
            var specs = new java.util.ArrayList<ConfigFieldSpec>();
            specs.add(ConfigFieldSpec.builder("database.provider", "Provider", FieldType.ENUM, group)
                    .enumValues("file", "remote").build());
            for (String key : List.of("jdbc-url", "username", "password")) {
                specs.add(ConfigFieldSpec.builder("database." + key, key,
                                key.equals("password") ? FieldType.PASSWORD : FieldType.STRING, group)
                        .visibleWhen(snapshot -> snapshot.equals("database.provider", "remote")).build());
            }
            var panel = new ConfigPanel(tempDir.resolve("config.yaml"), 6999, path -> path,
                    new ConfigFieldSnapshot(List.of(group), specs, List.of()), null, null, new LinkedHashMap<>());
            assertThat(fieldRow(panel, "jdbc-url").isVisible()).isTrue();
            panel.setFieldValue("database.jdbc-url", "draft-target");
            panel.setFieldValue("database.provider", "file");
            for (String key : List.of("jdbc-url", "username", "password"))
                assertThat(fieldRow(panel, key).isVisible()).isFalse();
            panel.setFieldValue("database.provider", "remote");
            assertThat(fieldRow(panel, "jdbc-url").isVisible()).isTrue();
            assertThat(panel.currentFieldValue("database.jdbc-url")).isEqualTo("draft-target");
            config.values.put("database.provider", "file");
            panel.reloadFromDisk();
            assertThat(fieldRow(panel, "jdbc-url").isVisible()).isFalse();
        });
    }

    private static Container fieldRow(Container root, String labelText) {
        for (Component child : root.getComponents()) {
            if (child instanceof JComponent component
                    && ("database." + labelText).equals(component.getClientProperty("pixivdownload.guiConfig.fieldRowKey")))
                return component;
            if (child instanceof Container container) {
                var found = fieldRow(container, labelText);
                if (found != null) return found;
            }
        }
        return null;
    }

    @Test
    @DisplayName("重新加载保留数据库草稿和迁移动作，匹配最新保存值后恢复保存")
    void databaseSaveLabelFollowsDraft() throws Exception {
        databaseSettings = new top.sywyar.pixivdownload.plugin.api.gui.DesktopDatabaseSettings() {
            public List<ProviderChoice> providers() { return List.of(); }
            public Map<String, String> prepare(Map<String, String> values) { throw new AssertionError(); }
            public CheckResult check(Map<String, String> values) { throw new AssertionError(); }
            public State state() { return new State("", "", List.of(), false); }
            public java.net.URI migrationGuide() { return java.net.URI.create("https://example.invalid/guide"); }
        };
        SwingUtilities.invokeAndWait(() -> {
            var config = installHost(Map.of("database.jdbc-url", "saved-target", "database.password", "stored-secret"),
                    new DesktopUiHost.UiLocale("en-US", "English", "en"));
            var group = top.sywyar.pixivdownload.plugin.api.gui.GuiConfigGroups.DATABASE;
            var field = ConfigFieldSpec.builder("database.jdbc-url", "URL", FieldType.STRING, group).build();
            var password = ConfigFieldSpec.builder("database.password", "Password", FieldType.PASSWORD, group).build();
            var panel = new ConfigPanel(tempDir.resolve("config.yaml"), 6999, path -> path,
                    ConfigFieldSnapshot.withGroupSpecs(List.of(new top.sywyar.pixivdownload.gui.config.ConfigGroupSpec(
                            group, group, 0, true)), List.of(field, password), List.of(), List.of()), null, null,
                    () -> false, () -> false, () -> false, () -> false);
            var save = findButton(panel, GuiMessages.get("gui.button.save"));
            panel.setFieldValue("database.jdbc-url", "draft-target");
            assertThat(save.getText()).isEqualTo("gui.config.database.migrate");
            panel.reloadFromDisk();
            assertThat(panel.currentFieldValue("database.jdbc-url")).isEqualTo("draft-target");
            assertThat(save.getText()).isEqualTo("gui.config.database.migrate");
            panel.setFieldValue("database.jdbc-url", "saved-target");
            assertThat(save.getText()).isEqualTo(GuiMessages.get("gui.button.save"));
            panel.setFieldValue("database.jdbc-url", "draft-target");
            config.values.put("database.jdbc-url", "external-target");
            panel.reloadFromDisk();
            assertThat(panel.currentFieldValue("database.jdbc-url")).isEqualTo("draft-target");
            assertThat(save.getText()).isEqualTo("gui.config.database.migrate");
            config.values.put("database.jdbc-url", "draft-target");
            panel.reloadFromDisk();
            assertThat(save.getText()).isEqualTo(GuiMessages.get("gui.button.save"));
            assertThat(panel.currentFieldValue("database.password")).isEmpty();
            panel.setFieldValue("database.password", "new-secret");
            panel.reloadFromDisk();
            assertThat(panel.currentFieldValue("database.password")).isEqualTo("new-secret");
            assertThat(save.getText()).isEqualTo("gui.config.database.migrate");
            panel.setFieldValue("database.password", "");
            assertThat(save.getText()).isEqualTo(GuiMessages.get("gui.button.save"));
            findButton(fieldRow(panel, "password"), GuiMessages.get("gui.button.clear-credential")).doClick();
            assertThat(save.getText()).isEqualTo("gui.config.database.migrate");
            panel.reloadFromDisk();
            assertThat(save.getText()).isEqualTo("gui.config.database.migrate");
            assertThat(config.values).containsEntry("database.password", "stored-secret");
        });
    }

    @Test
    @DisplayName("新提示重新计时，旧提示到期不隐藏新提示，移除面板清除提示")
    void noticeLifetimeFollowsLatestMessage() throws Exception {
        var panel = new java.util.concurrent.atomic.AtomicReference<ConfigPanel>();
        var notice = new java.util.concurrent.atomic.AtomicReference<JLabel>();
        SwingUtilities.invokeAndWait(() -> {
            installHost(Map.of(), new DesktopUiHost.UiLocale("en-US", "English", "en"));
            panel.set(preferencePanel(new LinkedHashMap<>()));
            panel.get().showNotice("First notice");
            notice.set(findLabel(panel.get(), "  First notice"));
            assertThat(notice.get()).isNotNull();
        });
        try {
            Thread.sleep(6_000);
            SwingUtilities.invokeAndWait(() -> panel.get().showNotice("Second notice"));
            Thread.sleep(5_000);
            SwingUtilities.invokeAndWait(() -> {
                assertThat(notice.get().getText()).isEqualTo("  Second notice");
                assertThat(notice.get().isVisible()).isTrue();
            });
            Thread.sleep(6_000);
            SwingUtilities.invokeAndWait(() -> {
                assertThat(notice.get().isVisible()).isFalse();
                panel.get().showNotice("Final notice");
                panel.get().removeNotify();
                assertThat(notice.get().isVisible()).isFalse();
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> panel.get().removeNotify());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("热重载迟到结果不能覆盖新提示或恢复已移除面板的提示")
    void staleReloadNoticeIsIgnored(boolean removePanel) throws Exception {
        var started = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var returned = new java.util.concurrent.CountDownLatch(1);
        var overwritten = new java.util.concurrent.CountDownLatch(1);
        var panel = new java.util.concurrent.atomic.AtomicReference<ConfigPanel>();
        var notice = new java.util.concurrent.atomic.AtomicReference<JLabel>();
        reloadResponse = () -> {
            started.countDown();
            try {
                assertThat(release.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                return new DesktopUiHost.GuiResponse(true, 200, null, "", false);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            } finally {
                returned.countDown();
            }
        };
        try {
            SwingUtilities.invokeAndWait(() -> {
                installHost(Map.of("fixture.hot", "before"),
                        new DesktopUiHost.UiLocale("en-US", "English", "en"));
                String group = "Fixture";
                var field = ConfigFieldSpec.builder("fixture.hot", "Hot fixture", FieldType.STRING, group)
                        .effect(GuiConfigEffect.HOT_RELOAD).build();
                panel.set(new ConfigPanel(tempDir.resolve("config.yaml"), 6999, path -> path,
                        new ConfigFieldSnapshot(List.of(group), List.of(field), List.of()), null, null,
                        () -> false, () -> false, () -> false, () -> false));
                panel.get().setFieldValue("fixture.hot", "after");
                findButton(panel.get(), GuiMessages.get("gui.button.save")).doClick();
                notice.set(findLabel(panel.get(), "  " + GuiMessages.get("gui.config.notice.hot-reloading")));
                assertThat(notice.get()).isNotNull();
            });
            assertThat(started.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            SwingUtilities.invokeAndWait(() -> {
                panel.get().showNotice("Latest notice");
                if (removePanel) panel.get().removeNotify();
                notice.get().addPropertyChangeListener("text", event -> overwritten.countDown());
            });
            release.countDown();
            assertThat(returned.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(overwritten.await(1, java.util.concurrent.TimeUnit.SECONDS)).isFalse();
            SwingUtilities.invokeAndWait(() -> {
                assertThat(notice.get().getText()).isEqualTo("  Latest notice");
                assertThat(notice.get().isVisible()).isEqualTo(!removePanel);
            });
        } finally {
            release.countDown();
            SwingUtilities.invokeAndWait(() -> {
                if (panel.get() != null) panel.get().removeNotify();
            });
        }
    }

    private static JLabel findLabel(Container root, String text) {
        for (Component component : root.getComponents()) {
            if (component instanceof JLabel label && text.equals(label.getText())) return label;
            if (component instanceof Container child) {
                JLabel found = findLabel(child, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    @Test
    @DisplayName("迁移核验不占用 EDT，新草稿不被覆盖，宿主派生键随重试保存")
    void preparesDatabaseSettingsInBackground() throws Exception {
        var started = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var finished = new java.util.concurrent.CountDownLatch(1);
        var onEdt = new java.util.concurrent.atomic.AtomicBoolean(true);
        databaseSettings = new top.sywyar.pixivdownload.plugin.api.gui.DesktopDatabaseSettings() {
            @Override public List<ProviderChoice> providers() { return List.of(); }
            @Override public Map<String, String> prepare(Map<String, String> values) throws java.io.IOException {
                onEdt.set(SwingUtilities.isEventDispatchThread());
                started.countDown();
                try {
                    if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new java.io.IOException("fixture timed out");
                } catch (InterruptedException failure) { throw new java.io.IOException(failure); }
                var prepared = new LinkedHashMap<>(values);
                prepared.put("database.engine", "postgresql");
                return prepared;
            }
            @Override public CheckResult check(Map<String, String> values) { throw new AssertionError(); }
            @Override public State state() { return new State("", "", List.of(), false); }
            @Override public java.net.URI migrationGuide() { return java.net.URI.create("https://example.test/guide"); }
        };
        var panel = new java.util.concurrent.atomic.AtomicReference<ConfigPanel>();
        var file = new java.util.concurrent.atomic.AtomicReference<MemoryConfigFile>();
        SwingUtilities.invokeAndWait(() -> {
            file.set(installHost(Map.of("database.username", "original")));
            var field = ConfigFieldSpec.builder("database.username", "Username", FieldType.STRING, "Database")
                    .effect(GuiConfigEffect.PROCESS_RESTART).build();
            panel.set(new ConfigPanel(tempDir.resolve("config.yaml"), 6999, path -> path,
                    new ConfigFieldSnapshot(List.of("Database"), List.of(field), List.of()), null, null,
                    () -> false, () -> false, () -> false, () -> false));
            var save = findButton(panel.get(), GuiMessages.get("gui.button.save"));
            save.addPropertyChangeListener("enabled", change -> {
                if (Boolean.TRUE.equals(change.getNewValue())) finished.countDown();
            });
            panel.get().setFieldValue("database.username", "first draft");
            save.doClick();
        });
        try {
            assertThat(started.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(onEdt).isFalse();
            SwingUtilities.invokeAndWait(() -> {
                assertThat(findButton(panel.get(), GuiMessages.get("gui.button.save")).isEnabled()).isFalse();
                panel.get().setFieldValue("database.username", "new draft");
                assertThat(file.get().writes).isZero();
            });
        } finally { release.countDown(); }
        assertThat(finished.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        var saved = new java.util.concurrent.CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> {
            assertThat(file.get().values).containsEntry("database.username", "original");
            assertThat(panel.get().currentFieldValue("database.username")).isEqualTo("new draft");
            var save = findButton(panel.get(), GuiMessages.get("gui.button.save"));
            save.addPropertyChangeListener("enabled", change -> {
                if (Boolean.TRUE.equals(change.getNewValue())) saved.countDown();
            });
            save.doClick();
        });
        assertThat(saved.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        SwingUtilities.invokeAndWait(() -> assertThat(file.get().values)
                .containsEntry("database.username", "new draft")
                .containsEntry("database.engine", "postgresql"));
    }

    @Test
    @DisplayName("市场在 Swing 预检后更新密码，空输入保存保留锁内最新秘密且不占用 EDT")
    void passwordKeepDoesNotRestoreConcurrentOldSecret() throws Exception {
        var prepared = new java.util.concurrent.CountDownLatch(1);
        var resume = new java.util.concurrent.CountDownLatch(1);
        var saved = new java.util.concurrent.CountDownLatch(1);
        var file = new java.util.concurrent.atomic.AtomicReference<MemoryConfigFile>();
        var calls = new AtomicInteger();
        databaseSettings = new top.sywyar.pixivdownload.plugin.api.gui.DesktopDatabaseSettings() {
            public List<ProviderChoice> providers() { return List.of(); }
            public Map<String, String> prepare(Map<String, String> values) throws java.io.IOException {
                assertThat(SwingUtilities.isEventDispatchThread()).isFalse();
                var result = new LinkedHashMap<>(values);
                assertThat(result).doesNotContainKey("database.password");
                result.put("database.password", file.get().values.get("database.password"));
                if (calls.incrementAndGet() == 1) {
                    prepared.countDown();
                    try {
                        if (!resume.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new java.io.IOException("fixture timed out");
                    } catch (InterruptedException failure) { throw new java.io.IOException(failure); }
                } else assertThat(Thread.holdsLock(file.get())).isTrue();
                return result;
            }
            public CheckResult check(Map<String, String> values) { throw new AssertionError(); }
            public State state() { return new State("", "", List.of(), false); }
            public java.net.URI migrationGuide() { return java.net.URI.create("https://example.invalid/guide"); }
        };
        SwingUtilities.invokeAndWait(() -> {
            file.set(installHost(Map.of("database.username", "before", "database.password", "old-secret")));
            var fields = List.of(
                    ConfigFieldSpec.builder("database.username", "Username", FieldType.STRING, "Database").build(),
                    ConfigFieldSpec.builder("database.password", "Password", FieldType.PASSWORD, "Database").build());
            var panel = new ConfigPanel(tempDir.resolve("config.yaml"), 6999, path -> path,
                    new ConfigFieldSnapshot(List.of("Database"), fields, List.of()), null, null,
                    () -> false, () -> false, () -> false, () -> false);
            panel.setFieldValue("database.username", "after");
            var save = findButton(panel, GuiMessages.get("gui.button.save"));
            save.addPropertyChangeListener("enabled", change -> {
                if (Boolean.TRUE.equals(change.getNewValue())) saved.countDown();
            });
            save.doClick();
        });
        try {
            assertThat(prepared.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            file.get().withWriteLock(() -> file.get().writeAll(Map.of("database.password", "market-secret")));
        } finally { resume.countDown(); }
        assertThat(saved.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(calls).hasValue(2);
        assertThat(file.get().values).containsEntry("database.password", "market-secret").containsEntry("database.username", "after");
        assertThat(file.get().writesOnEdt).isFalse();
    }

    @Test
    @DisplayName("后台停止时主库密码不回填，编辑保留空格，清除只在保存后生效且要求进程重启")
    void savesCoreDatabasePasswordWithoutEchoingIt() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            MemoryConfigFile config = installHost(Map.of("database.username", "before", "database.password", "stored-secret"));
            AtomicInteger processRestarts = new AtomicInteger();
            AtomicInteger backendRestarts = new AtomicInteger();
            var fields = List.of(
                    ConfigFieldSpec.builder("database.username", "Username", FieldType.STRING, "Database")
                            .effect(GuiConfigEffect.PROCESS_RESTART).build(),
                    ConfigFieldSpec.builder("database.password", "Password", FieldType.PASSWORD, "Database")
                            .effect(GuiConfigEffect.PROCESS_RESTART).build());
            ConfigPanel panel = new ConfigPanel(tempDir.resolve("config.yaml"), 6999, path -> path,
                    new ConfigFieldSnapshot(List.of("Database"), fields, List.of()), null, null,
                    () -> { backendRestarts.incrementAndGet(); return false; }, () -> false,
                    () -> { processRestarts.incrementAndGet(); return false; }, () -> false);
            assertThat(panel.currentFieldValue("database.password")).isEmpty();
            panel.setFieldValue("database.username", "after");
            findButton(panel, GuiMessages.get("gui.button.save")).doClick();
            assertThat(config.values).containsEntry("database.password", "stored-secret");
            panel.setFieldValue("database.password", " spaced secret ");
            findButton(panel, GuiMessages.get("gui.button.save")).doClick();
            assertThat(config.values).containsEntry("database.password", " spaced secret ");
            assertThat(panel.currentFieldValue("database.password")).isEmpty();
            panel.setFieldValue("database.password", "spaced secret");
            findButton(panel, GuiMessages.get("gui.button.save")).doClick();
            assertThat(processRestarts).hasValue(3);
            panel.setFieldValue("database.password", "   ");
            findButton(panel, GuiMessages.get("gui.button.save")).doClick();
            panel.requestCredentialClear("database.password");
            assertThat(config.values).containsEntry("database.password", "   ");
            findButton(panel, GuiMessages.get("gui.button.save")).doClick();
            assertThat(config.values).containsEntry("database.password", "");
            assertThat(processRestarts).hasValue(5);
            assertThat(backendRestarts).hasValue(0);
        });
    }

    @Test
    @DisplayName("刷新保留草稿且保存不覆盖外部修改，缺项读取不补写")
    void preservesDraftsAndExternalConfiguration() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            MemoryConfigFile config = installHost(Map.of("fixture.proxy", "saved-proxy"));
            var fields = new ConfigFieldSnapshot(List.of("Fixture"), List.of(
                    ConfigFieldSpec.builder("fixture.proxy", "Proxy", FieldType.STRING, "Fixture")
                            .defaultValue("default-proxy").build(),
                    ConfigFieldSpec.builder("fixture.other", "Other", FieldType.STRING, "Fixture")
                            .defaultValue("default-other").build()), List.of());
            var panel = new ConfigPanel(tempDir.resolve("config.yaml"), 6999, path -> path,
                    fields, null, null, () -> false, () -> false, () -> false, () -> false);
            assertThat(config.values).containsOnlyKeys("fixture.proxy");
            panel.setFieldValue("fixture.other", "draft");
            config.values.put("fixture.proxy", "wizard-proxy");
            panel.reloadFromDisk();
            assertThat(panel.currentFieldValue("fixture.proxy")).isEqualTo("wizard-proxy");
            assertThat(panel.currentFieldValue("fixture.other")).isEqualTo("draft");
            config.values.put("fixture.proxy", "external-proxy");
            findButton(panel, GuiMessages.get("gui.button.save")).doClick();
            assertThat(config.values).containsEntry("fixture.proxy", "external-proxy")
                    .containsEntry("fixture.other", "draft");
            assertThat(panel.currentFieldValue("fixture.proxy")).isEqualTo("external-proxy");
        });
    }

    @Test
    @DisplayName("凭据必填识别新输入和已存值，显式清除或外部删除后拒绝保存")
    void requiredCredentialsUseCurrentStorage() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Map<String, String> credentials = new LinkedHashMap<>();
            MemoryConfigFile plugin = new MemoryConfigFile(Map.of("demo.enabled", "false"));
            MemoryConfigFile config = installHost(Map.of(), new DesktopUiHost.UiLocale("en-US", "English", "en"),
                    plugin, credentials);
            var required = new GuiConfigFieldContribution("demo.secret", "demo", "secret", GuiConfigFieldType.PASSWORD, "", 2)
                    .requiredWhen(GuiConfigCondition.isTrue("demo.enabled"));
            String group = "Fixture";
            var fields = List.of(
                    ConfigFieldSpec.builder("demo.enabled", "Enabled", FieldType.BOOL, group)
                            .ownerPluginId("demo").defaultValue("false").build(),
                    ConfigFieldSpec.builder("demo.secret", "Secret", FieldType.PASSWORD, group)
                            .ownerPluginId("demo")
                            .requiredValueMissing((snapshot, stored) -> required.missingRequiredValue(snapshot.values(), stored)).build());
            ConfigPanel panel = new ConfigPanel(tempDir.resolve("config.yaml"), 6999, path -> path,
                    new ConfigFieldSnapshot(List.of(group), fields, List.of()), null, null,
                    () -> false, () -> false, () -> false, () -> false);
            panel.setFieldValue("demo.enabled", "true");
            for (String blank : List.of("", " \t")) {
                panel.setFieldValue("demo.secret", blank);
                findButton(panel, GuiMessages.get("gui.button.save")).doClick();
                assertThat(plugin.writes).isZero();
                assertThat(config.writes).isZero();
                assertThat(credentials).isEmpty();
            }
            panel.setFieldValue("demo.secret", "fixture-secret");
            findButton(panel, GuiMessages.get("gui.button.save")).doClick();
            assertThat(plugin.values).containsEntry("demo.enabled", "true").doesNotContainKey("demo.secret");
            assertThat(credentials).containsEntry("demo.secret", "fixture-secret");
            assertThat(panel.currentFieldValue("demo.secret")).isEmpty();
            findButton(panel, GuiMessages.get("gui.button.save")).doClick();
            assertThat(credentials).containsEntry("demo.secret", "fixture-secret");
            int writes = plugin.writes;
            credentials.clear();
            findButton(panel, GuiMessages.get("gui.button.save")).doClick();
            assertThat(plugin.writes).isEqualTo(writes);
            credentials.put("demo.secret", "fixture-secret");
            panel.requestCredentialClear("demo.secret");
            findButton(panel, GuiMessages.get("gui.button.save")).doClick();
            assertThat(plugin.writes).isEqualTo(writes);
            assertThat(credentials).containsEntry("demo.secret", "fixture-secret");
            panel.setFieldValue("demo.enabled", "false");
            findButton(panel, GuiMessages.get("gui.button.save")).doClick();
            assertThat(plugin.values).containsEntry("demo.enabled", "false");
            assertThat(credentials).isEmpty();
        });
    }

    @Test
    @DisplayName("统一保存按完整草稿检查条件必填，失败不落盘且关闭条件后允许空值")
    void requiredValuesUseTheWholeDraft() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            MemoryConfigFile config = installHost(
                    Map.of("demo.enabled", "false", "demo.value", ""),
                    new DesktopUiHost.UiLocale("en-US", "English", "en")
            );
            var required = new GuiConfigFieldContribution(
                    "demo.value",
                    "demo",
                    "value",
                    GuiConfigFieldType.STRING,
                    "",
                    2
            ).requiredWhen(GuiConfigCondition.isTrue("demo.enabled"));
            String group = "Fixture";
            var fields = List.of(
                    ConfigFieldSpec.builder("demo.enabled", "Enabled", FieldType.BOOL, group)
                            .defaultValue("false").build(),
                    ConfigFieldSpec.builder("demo.value", "Value", FieldType.STRING, group)
                            .requiredValueMissing((snapshot, stored) -> required.missingRequiredValue(snapshot.values(), stored)).build()
            );
            ConfigPanel panel = new ConfigPanel(
                    tempDir.resolve("config.yaml"),
                    6999,
                    path -> path,
                    new ConfigFieldSnapshot(List.of(group), fields, List.of()),
                    null,
                    null,
                    () -> false,
                    () -> false,
                    () -> false,
                    () -> false
            );
            panel.setFieldValue("demo.enabled", "true");
            findButton(panel, GuiMessages.get("gui.button.save")).doClick();
            assertThat(config.writes).isZero();
            panel.setFieldValue("demo.value", " \t");
            findButton(panel, GuiMessages.get("gui.button.save")).doClick();
            assertThat(config.writes).isZero();
            panel.setFieldValue("demo.value", "fixture/custom");
            findButton(panel, GuiMessages.get("gui.button.save")).doClick();
            assertThat(config.values).containsEntry("demo.enabled", "true").containsEntry("demo.value", "fixture/custom");
            panel.setFieldValue("demo.enabled", "false");
            panel.setFieldValue("demo.value", "");
            findButton(panel, GuiMessages.get("gui.button.save")).doClick();
            assertThat(config.values).containsEntry("demo.enabled", "false").containsEntry("demo.value", "");
        });
    }

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
        return installHost(values, new DesktopUiHost.UiLocale("zh-CN", "简体中文", ""));
    }

    private MemoryConfigFile installHost(Map<String, String> values, DesktopUiHost.UiLocale locale) {
        return installHost(values, locale, null, new LinkedHashMap<>());
    }

    @SuppressWarnings("unchecked")
    private MemoryConfigFile installHost(Map<String, String> values, DesktopUiHost.UiLocale locale,
                                         MemoryConfigFile plugin, Map<String, String> credentials) {
        MemoryConfigFile config = new MemoryConfigFile(values);
        DesktopUiHost host = (DesktopUiHost) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{DesktopUiHost.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "applicationConfig" -> config;
                    case "databaseSettings" -> Optional.ofNullable(databaseSettings);
                    case "pluginConfig" -> plugin == null ? config : plugin;
                    case "coreConfigGroups", "coreConfigFields" -> List.of();
                    case "visibleLocales" -> List.of(locale);
                    case "matchLocale" -> Optional.of(locale).filter(ignored ->
                            args != null && args.length > 0 && locale.tag().equalsIgnoreCase(String.valueOf(args[0])));
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
                    case "guiGet" -> DesktopUiHost.GuiResponse.unreachable();
                    case "guiPostJson" -> reloadResponse.get();
                    case "autoStartSupported", "autoStartEnabled", "launchedFromExecutable",
                         "currentVersionNightly", "supportsManagedFfmpegInstall" -> false;
                    case "defaultProxyPort", "minimumPasswordLength", "recommendedPasswordLength" -> 1;
                    case "applicationName", "applicationVersion", "projectUrl", "releasesUrl",
                         "defaultUpdateManifestUrl", "defaultNightlyUpdateManifestUrl", "guiToken",
                         "guiTokenHeader", "defaultProxyHost", "defaultMaintenanceTime" -> "test";
                    case "reservedPluginRepositoryIds" -> Set.of();
                    case "readCredentials" -> Map.copyOf(credentials);
                    case "snapshotCredentials" -> new DesktopUiHost.CredentialSnapshot(false, new byte[0]);
                    case "updateCredentials" -> {
                        ((Map<String, String>) args[1]).forEach((key, value) -> {
                            if (value.isBlank()) credentials.remove(key); else credentials.put(key, value);
                        });
                        yield null;
                    }
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
        if (type == Optional.class) return Optional.empty();
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
        private boolean writesOnEdt;

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
            writesOnEdt |= SwingUtilities.isEventDispatchThread();
            values.putAll(updates);
        }
        @Override public void removeAll(Collection<String> keys) { keys.forEach(values::remove); }
        @Override public DesktopUiHost.ConfigSnapshot snapshot() {
            return new DesktopUiHost.ConfigSnapshot(true, List.of());
        }
        @Override public void restore(DesktopUiHost.ConfigSnapshot snapshot) { }
    }
}
