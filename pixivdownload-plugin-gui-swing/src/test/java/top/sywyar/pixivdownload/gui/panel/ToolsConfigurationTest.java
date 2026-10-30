package top.sywyar.pixivdownload.gui.panel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.sywyar.pixivdownload.guiswing.SwingHost;
import top.sywyar.pixivdownload.gui.i18n.GuiMessages;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiContext;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;

import javax.swing.JLabel;
import javax.swing.JCheckBox;
import javax.swing.JButton;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Swing 工具配置继承")
class ToolsConfigurationTest {
    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(DesktopUiHost.BackfillState.class)
    @DisplayName("工具面板重建恢复自动回填状态，关闭面板不会停止后台任务")
    void restoresBackfillTask(DesktopUiHost.BackfillState state) throws Exception {
        var id = java.util.UUID.randomUUID();
        var snapshot = new DesktopUiHost.BackfillTask(id, 2, state, DesktopUiHost.BackfillPhase.NETWORK,
                true, false, 1, 0, 1, 3, 1, 0, 0, "NOVEL", 7L, null, "fixture.html");
        var locale = new DesktopUiHost.UiLocale("en-US", "English", "_en");
        var host = (DesktopUiHost) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{DesktopUiHost.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "backfillTask" -> java.util.Optional.of(snapshot);
                    case "defaultBackfillOptions" -> new DesktopUiHost.BackfillOptions("data/test.db", "", 8080, false, 0, 0, false);
                    case "readDownloadRootFromConfig" -> "downloads";
                    case "resolveDatabasePath" -> Path.of("data/test.db");
                    case "supportsDatabaseFileSelection" -> true;
                    case "resolveLocale" -> new DesktopUiHost.UiLocaleResolution(locale, List.of(locale));
                    case "mediaTools", "coreConfigGroups", "coreConfigFields" -> List.of();
                    case "backendSnapshot" -> new DesktopUiHost.BackendSnapshot(DesktopUiHost.BackendState.RUNNING, null);
                    case "subscribeBackend" -> (AutoCloseable) () -> {};
                    case "message" -> args[0];
                    default -> throw new AssertionError("Unexpected host operation: " + method.getName());
                });
        var path = Path.of("config.yaml");
        SwingHost.install(new DesktopUiContext(false, 8080, "downloads", path, "gui-swing", host,
                List.of(), List::of, text -> text.key(), () -> "system"));
        SwingUtilities.invokeAndWait(() -> {
            for (int reopen = 0; reopen < 2; reopen++) {
                var panel = new ToolsPanel(path);
                try {
                    String key = state == DesktopUiHost.BackfillState.RUNNING ? "gui.tools.backfill.phase.network"
                            : "gui.tools.backfill.state." + state.name().toLowerCase(Locale.ROOT);
                    assertTrue(descendants(panel).filter(JLabel.class::isInstance).map(JLabel.class::cast)
                            .anyMatch(label -> label.getText().contains(GuiMessages.get(key))));
                    var stop = descendants(panel).filter(JButton.class::isInstance).map(JButton.class::cast)
                            .filter(button -> button.getText().equals(GuiMessages.get("gui.tools.backfill.action.stop"))).findFirst().orElseThrow();
                    assertEquals(snapshot.active(), stop.isVisible());
                    assertEquals(state == DesktopUiHost.BackfillState.RUNNING, stop.isEnabled());
                } finally { panel.dispose(); }
            }
        });
    }
    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"2,0,0,SUCCEEDED,completed,1", "2,0,1,PARTIALLY_FAILED,partial,2",
            "0,2,1,PARTIALLY_FAILED,partial,2", "0,0,1,FAILED,failed,0"})
    @DisplayName("迁移结果按失败数显示状态与提示级别，并写入准确工具历史")
    void migrationResultMapping(int migrated, int skipped, int failed, DesktopUiHost.ToolOutcome outcome,
            String status, int messageType) throws Exception {
        var summary = new DesktopUiHost.MigrationSummary(migrated + skipped + failed, migrated, skipped, false, "opaque", failed);
        var recorded = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var record = new AtomicReference<Object[]>();
        var locale = new DesktopUiHost.UiLocale("en-US", "English", "_en");
        var host = (DesktopUiHost) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{DesktopUiHost.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "backfillTask" -> java.util.Optional.empty();
                    case "defaultBackfillOptions" -> new DesktopUiHost.BackfillOptions("data/test.db", "", 8080, false, 0, 0, false);
                    case "readDownloadRootFromConfig" -> "downloads";
                    case "resolveDatabasePath" -> Path.of("data/test.db");
                    case "supportsDatabaseFileSelection" -> true;
                    case "resolveLocale" -> new DesktopUiHost.UiLocaleResolution(locale, List.of(locale));
                    case "mediaTools", "coreConfigGroups", "coreConfigFields" -> List.of();
                    case "backendSnapshot" -> new DesktopUiHost.BackendSnapshot(DesktopUiHost.BackendState.STOPPED, null);
                    case "subscribeBackend" -> (AutoCloseable) () -> {};
                    case "runMigration" -> summary;
                    case "recordToolHistory" -> { record.set(args); recorded.countDown(); yield null; }
                    case "startBackend" -> { finished.countDown(); yield true; }
                    case "message" -> args[0];
                    default -> throw new AssertionError(method.getName());
                });
        var path = Path.of("config.yaml");
        SwingHost.install(new DesktopUiContext(false, 8080, "downloads", path, "gui-swing", host,
                List.of(), List::of, text -> text.key(), () -> "system"));
        GuiMessages.setLocale(Locale.ENGLISH);
        var panel = new AtomicReference<ToolsPanel>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                panel.set(new ToolsPanel(path));
                var feedback = ToolsPanel.migrationFeedback(summary);
                String expectedStatus = GuiMessages.get("gui.tools.migration.result." + status);
                assertEquals(expectedStatus, feedback.status());
                assertEquals(GuiMessages.get("gui.tools.dialog.migration.completed.message", expectedStatus,
                        migrated, skipped, summary.totalCandidates(), failed), feedback.details());
                assertEquals(messageType, feedback.messageType());
            });
            var run = ToolsPanel.class.getDeclaredMethod("runMigrationInBackground", DesktopUiHost.MigrationOptions.class);
            run.setAccessible(true);
            SwingUtilities.invokeAndWait(() -> {
                try { run.invoke(panel.get(), new DesktopUiHost.MigrationOptions("data/test.db", "downloads")); }
                catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            });
            assertTrue(recorded.await(5, TimeUnit.SECONDS));
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            assertEquals(outcome, record.get()[1]);
            assertEquals(summary.totalCandidates(), record.get()[3]);
            assertEquals(migrated, record.get()[4]);
            assertEquals(failed, record.get()[5]);
        } finally {
            SwingUtilities.invokeAndWait(() -> { if (panel.get() != null) panel.get().dispose(); });
            GuiMessages.clearLocaleOverride();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"backfill", "migration"})
    @DisplayName("工具页面重建后，回填和迁移准备提示使用当前语言")
    void countingStatusFollowsCurrentLocale(String operation) throws Exception {
        try {
            for (String language : List.of("ja", "en")) {
                verifyCountingStatus(language, operation);
            }
        } finally {
            GuiMessages.clearLocaleOverride();
        }
    }

    private void verifyCountingStatus(String language, String operation) throws Exception {
        var observed = new AtomicReference<DesktopUiHost.BackfillTask>();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var preparation = new AtomicReference<Thread>();
        var execution = new AtomicReference<Thread>();
        var session = (DesktopUiHost.ToolLogSession) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{DesktopUiHost.ToolLogSession.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "latestPath", "sessionPath" -> Path.of("log/fixture.html");
                    case "openLatestInBrowser", "close" -> null;
                    default -> throw new AssertionError(method.getName());
                });
        var host = (DesktopUiHost) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{DesktopUiHost.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "backfillTask" -> java.util.Optional.ofNullable(observed.get());
                    case "defaultBackfillOptions" -> new DesktopUiHost.BackfillOptions("data/test.db", "", 8080, false, 0, 0, false);
                    case "readDownloadRootFromConfig" -> "downloads";
                    case "resolveDatabasePath" -> Path.of("data/test.db");
                    case "supportsDatabaseFileSelection" -> true;
                    case "resolveLocale" -> {
                        String requested = ((Locale) args[0]).getLanguage();
                        var locale = new DesktopUiHost.UiLocale(requested.equals("ja") ? "ja-JP" : "en-US", requested, requested);
                        yield new DesktopUiHost.UiLocaleResolution(locale, List.of(locale));
                    }
                    case "mediaTools", "coreConfigGroups", "coreConfigFields" -> List.of();
                    case "backendSnapshot" -> new DesktopUiHost.BackendSnapshot(DesktopUiHost.BackendState.RUNNING, null);
                    case "subscribeBackend" -> (AutoCloseable) () -> {};
                    case "stopBackend", "startBackend" -> {
                        ((Runnable) args[0]).run();
                        yield true;
                    }
                    case "startBackfill" -> {
                        var options = (DesktopUiHost.BackfillOptions) args[0];
                        assertTrue(options.alwaysNetwork());
                        observed.set(new DesktopUiHost.BackfillTask(java.util.UUID.randomUUID(), 1,
                                DesktopUiHost.BackfillState.RUNNING, DesktopUiHost.BackfillPhase.SCANNING,
                                false, false, 1, 0, 0, null, 0, 0, 0, null, null, null, null));
                        preparation.set(Thread.currentThread());
                        entered.countDown();
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                        yield observed.get().executionId();
                    }
                    case "countBackfillCandidates", "countMigrationCandidates" -> {
                        if (args[0] instanceof DesktopUiHost.BackfillOptions options) assertTrue(options.alwaysNetwork());
                        preparation.set(Thread.currentThread());
                        entered.countDown();
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                        yield 0;
                    }
                    case "openToolLog" -> session;
                    case "runBackfill", "runMigration" -> {
                        execution.set(Thread.currentThread());
                        yield null;
                    }
                    case "recordToolHistory" -> null;
                    case "message" -> args[0];
                    default -> throw new AssertionError(method.getName());
                });
        var path = Path.of("config.yaml");
        SwingHost.install(new DesktopUiContext(false, 8080, "downloads", path, "gui-swing", host,
                List.of(), List::of, text -> text.key(), () -> "system"));
        GuiMessages.setLocale(Locale.forLanguageTag(language));
        var panel = new AtomicReference<ToolsPanel>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                panel.set(new ToolsPanel(path));
                String sourceLabel = GuiMessages.get("gui.tools.form.always-network");
                assertFalse(sourceLabel.equals("gui.tools.form.always-network"));
                var source = descendants(panel.get()).filter(JCheckBox.class::isInstance).map(JCheckBox.class::cast)
                        .filter(check -> sourceLabel.equals(check.getText())).findFirst().orElseThrow();
                assertFalse(source.isSelected());
                source.doClick();
                assertTrue(source.isSelected());
                descendants(panel.get()).filter(JButton.class::isInstance).map(JButton.class::cast)
                        .filter(button -> button.getText().equals(GuiMessages.get("gui.tools.action.start-" + operation)))
                        .findFirst().orElseThrow().doClick();
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            if (operation.equals("backfill")) {
                var refresh = ToolsPanel.class.getDeclaredMethod("refreshBackfill");
                refresh.setAccessible(true);
                SwingUtilities.invokeAndWait(() -> { try { refresh.invoke(panel.get()); } catch (Exception error) { throw new AssertionError(error); } });
            }
            SwingUtilities.invokeAndWait(() -> assertEquals(1,
                    descendants(panel.get()).filter(JLabel.class::isInstance).map(JLabel.class::cast)
                            .filter(label -> label.getText() != null && label.getText()
                                    .contains(GuiMessages.get(operation.equals("backfill") ? "gui.tools.backfill.phase.scanning" : "gui.tools.migration.status.counting")))
                            .count(), language + ": " + operation));
        } finally {
            release.countDown();
            if (preparation.get() != null) preparation.get().join(5000);
            if (execution.get() != null) execution.get().join(5000);
            SwingUtilities.invokeAndWait(() -> { if (panel.get() != null) panel.get().dispose(); });
        }
    }

    @Test
    @DisplayName("加载和刷新配置失败时显示宿主解析的错误提示")
    void configurationFailuresUseHostMessages() throws Exception {
        var failureText = new AtomicReference<>("Initial host failure message");
        var locale = new DesktopUiHost.UiLocale("en-US", "English", "_en");
        var host = (DesktopUiHost) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{DesktopUiHost.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "backfillTask" -> java.util.Optional.empty();
                    case "defaultBackfillOptions" -> throw new IllegalStateException("Configuration unavailable");
                    case "readDownloadRootFromConfig" -> "downloads";
                    case "resolveDatabasePath" -> Path.of("data/test.db");
                    case "supportsDatabaseFileSelection" -> true;
                    case "resolveLocale" -> new DesktopUiHost.UiLocaleResolution(locale, List.of(locale));
                    case "mediaTools", "coreConfigGroups", "coreConfigFields" -> List.of();
                    case "backendSnapshot" -> new DesktopUiHost.BackendSnapshot(DesktopUiHost.BackendState.STOPPED, null);
                    case "subscribeBackend" -> (AutoCloseable) () -> {};
                    case "message" -> "desktop.ui.tools.operation-failed".equals(args[0])
                            ? failureText.get() : args[0];
                    default -> throw new AssertionError("Unexpected host operation: " + method.getName());
                });
        var path = Path.of("config.yaml");
        SwingHost.install(new DesktopUiContext(false, 8080, "downloads", path, "gui-swing", host,
                List.of(), List::of, text -> text.key(), () -> "system"));
        var panel = new AtomicReference<ToolsPanel>();
        try {
            SwingUtilities.invokeAndWait(() -> panel.set(new ToolsPanel(path)));
            SwingUtilities.invokeAndWait(() -> {
                assertEquals(1, descendants(panel.get()).filter(JLabel.class::isInstance).map(JLabel.class::cast)
                        .filter(label -> label.getText() != null && label.getText().contains(failureText.get())).count());
                failureText.set("Refreshed host failure message");
                panel.get().refreshConfiguration();
            });
            SwingUtilities.invokeAndWait(() -> assertEquals(1,
                    descendants(panel.get()).filter(JLabel.class::isInstance).map(JLabel.class::cast)
                            .filter(label -> label.getText() != null && label.getText().contains(failureText.get())).count()));
        } finally {
            SwingUtilities.invokeAndWait(() -> { if (panel.get() != null) panel.get().dispose(); });
        }
    }

    @Test
    @DisplayName("打开日志在后台调用只读宿主入口，重复点击不阻塞 EDT")
    void logOpeningKeepsEventThreadResponsive() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var locale = new DesktopUiHost.UiLocale("en-US", "English", "_en");
        var host = (DesktopUiHost) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{DesktopUiHost.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "backfillTask" -> java.util.Optional.of(new DesktopUiHost.BackfillTask(java.util.UUID.randomUUID(), 1,
                            DesktopUiHost.BackfillState.COMPLETED, DesktopUiHost.BackfillPhase.FINISHED,
                            false, false, 1, 2, 1, 1, 1, 0, 0, null, null, null, "backfill-fixture.html"));
                    case "defaultBackfillOptions" -> new DesktopUiHost.BackfillOptions("data/test.db", "", 8080, false, 0, 0, false);
                    case "readDownloadRootFromConfig" -> "downloads";
                    case "resolveDatabasePath" -> Path.of("data/test.db");
                    case "supportsDatabaseFileSelection" -> true;
                    case "resolveLocale" -> new DesktopUiHost.UiLocaleResolution(locale, List.of(locale));
                    case "mediaTools", "coreConfigGroups", "coreConfigFields" -> List.of();
                    case "backendSnapshot" -> new DesktopUiHost.BackendSnapshot(DesktopUiHost.BackendState.STOPPED, null);
                    case "subscribeBackend" -> (AutoCloseable) () -> {};
                    case "findToolLog" -> java.util.Optional.of(Path.of("log", "html", (String) args[0]).toAbsolutePath());
                    case "openExternalUri" -> {
                        org.junit.jupiter.api.Assertions.assertFalse(SwingUtilities.isEventDispatchThread());
                        assertEquals("backfill-fixture.html", Path.of((java.net.URI) args[0]).getFileName().toString());
                        calls.incrementAndGet();
                        entered.countDown();
                        org.junit.jupiter.api.Assertions.assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS));
                        yield null;
                    }
                    default -> throw new AssertionError("Unexpected host operation: " + method.getName());
                });
        var path = Path.of("config.yaml");
        SwingHost.install(new DesktopUiContext(false, 8080, "downloads", path, "gui-swing", host,
                List.of(), List::of, text -> text.key(), () -> "system"));
        var panel = new AtomicReference<ToolsPanel>();
        var open = ToolsPanel.class.getDeclaredMethod("openBackfillLogPage");
        open.setAccessible(true);
        try {
            SwingUtilities.invokeAndWait(() -> panel.set(new ToolsPanel(path)));
            SwingUtilities.invokeAndWait(() -> {
                try { open.invoke(panel.get()); open.invoke(panel.get()); }
                catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            });
            org.junit.jupiter.api.Assertions.assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> assertEquals(1, calls.get()));
        } finally {
            release.countDown();
            SwingUtilities.invokeAndWait(() -> { if (panel.get() != null) panel.get().dispose(); });
        }
    }

    @Test
    @DisplayName("刷新采用新代理和下载根，保留临时覆盖且不请求配置写入")
    void refreshPreservesTemporaryOverrides() throws Exception {
        var saved = new AtomicReference<>(new DesktopUiHost.BackfillOptions(
                "data/test.db", "saved.proxy", 17891, false, 1000, 0, false));
        var root = new AtomicReference<>("downloads");
        var locale = new DesktopUiHost.UiLocale("en-US", "English", "_en");
        var host = (DesktopUiHost) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{DesktopUiHost.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "backfillTask" -> java.util.Optional.empty();
                    case "defaultBackfillOptions" -> saved.get();
                    case "readDownloadRootFromConfig" -> root.get();
                    case "resolveDatabasePath" -> Path.of("data/test.db");
                    case "supportsDatabaseFileSelection" -> true;
                    case "resolveLocale" -> new DesktopUiHost.UiLocaleResolution(locale, List.of(locale));
                    case "mediaTools", "coreConfigGroups", "coreConfigFields" -> List.of();
                    case "backendSnapshot" -> new DesktopUiHost.BackendSnapshot(DesktopUiHost.BackendState.STOPPED, null);
                    case "subscribeBackend" -> (AutoCloseable) () -> {};
                    default -> throw new AssertionError("Unexpected host operation: " + method.getName());
                });
        var path = Path.of("config.yaml");
        SwingHost.install(new DesktopUiContext(false, 8080, root.get(), path, "gui-swing", host,
                List.of(), List::of, text -> text.key(), () -> "system"));
        SwingUtilities.invokeAndWait(() -> {
            var panel = new ToolsPanel(path);
            try {
                var proxy = descendants(panel).filter(JTextField.class::isInstance).map(JTextField.class::cast)
                        .filter(field -> field.getText().equals("saved.proxy")).findFirst().orElseThrow();
                var directory = descendants(panel).filter(JTextField.class::isInstance).map(JTextField.class::cast)
                        .filter(field -> field.getText().equals("downloads")).findFirst().orElseThrow();
                proxy.setText("temporary.proxy");
                saved.set(new DesktopUiHost.BackfillOptions("data/test.db", "wizard.proxy", 17892, true, 1000, 0, false));
                root.set("new-downloads");
                panel.refreshConfiguration();
                assertEquals("temporary.proxy", proxy.getText());
                assertEquals("new-downloads", directory.getText());
                assertEquals(1, descendants(panel).filter(JSpinner.class::isInstance).map(JSpinner.class::cast)
                        .filter(spinner -> spinner.getValue().equals(17892)).count());
            } finally {
                panel.dispose();
            }
        });
    }

    private static Stream<Component> descendants(Component component) {
        return Stream.concat(Stream.of(component), component instanceof Container container
                ? Arrays.stream(container.getComponents()).flatMap(ToolsConfigurationTest::descendants) : Stream.empty());
    }
}
