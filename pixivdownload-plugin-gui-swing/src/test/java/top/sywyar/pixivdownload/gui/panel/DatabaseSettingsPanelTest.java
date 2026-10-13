package top.sywyar.pixivdownload.gui.panel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.gui.panel.configtab.DatabaseSettingsPanel;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopDatabaseSettings;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;

import javax.swing.*;
import java.awt.*;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Swing 主库连接检查")
class DatabaseSettingsPanelTest {
    @Test
    @DisplayName("迁移预检在后台完成，弹窗列出主私库，确认后才提交计划")
    void confirmsMigrationFromVisibleDialog() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        var started = new CountDownLatch(1);
        var panel = new AtomicReference<DatabaseSettingsPanel>();
        var dialog = new AtomicReference<JDialog>();
        DesktopDatabaseSettings settings = new DesktopDatabaseSettings() {
            public List<ProviderChoice> providers() { return List.of(); }
            public Map<String, String> prepare(Map<String, String> values) { throw new AssertionError("unexpected save"); }
            public CheckResult check(Map<String, String> values) { throw new AssertionError("unexpected connection check"); }
            public State state() { return new State("source", "source", List.of(), false); }
            public URI migrationGuide() { return URI.create("https://example.invalid/guide"); }
            public MigrationPlan previewMigration(Map<String, String> draft) {
                assertFalse(SwingUtilities.isEventDispatchThread());
                return new MigrationPlan("fixture-plan", true, List.of(
                        new MigrationStore("main", "source", "target", "REPLACE_TARGET", List.of()),
                        new MigrationStore("private:fixture", "private-source", "private-target", "READY", List.of())), "");
            }
            public void startMigration(String token, DesktopUiHost.IoOperation saveOthers) {
                assertEquals("fixture-plan", token);
                started.countDown();
            }
        };
        DesktopUiHost host = (DesktopUiHost) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{DesktopUiHost.class}, (proxy, method, args) -> {
                    if (method.getName().equals("message")) return args[0] + (args.length > 1
                            ? java.util.Arrays.deepToString((Object[]) args[1]) : "");
                    throw new AssertionError("unexpected host call: " + method.getName());
                });
        try {
            SwingUtilities.invokeAndWait(() -> {
                panel.set(new DatabaseSettingsPanel(host, settings, Map::of));
                panel.get().previewMigration(Map.of("database.provider", "fixture"), () -> true, () -> {});
            });
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                while (dialog.get() == null) {
                    SwingUtilities.invokeAndWait(() -> java.util.Arrays.stream(Window.getWindows())
                            .filter(JDialog.class::isInstance).map(JDialog.class::cast)
                            .filter(window -> window.isShowing() && window.getTitle().startsWith("gui.config.database.migrate"))
                            .findFirst().ifPresent(dialog::set));
                    Thread.sleep(10);
                }
            });
            assertEquals(1, started.getCount());
            SwingUtilities.invokeAndWait(() -> {
                assertTrue(descendants(dialog.get()).filter(JTextArea.class::isInstance).map(JTextArea.class::cast)
                        .anyMatch(area -> area.getText().contains("main") && area.getText().contains("private:fixture")
                                && area.getText().contains("gui.config.database.migration.phase.replace-target")));
                descendants(dialog.get()).filter(JButton.class::isInstance).map(JButton.class::cast)
                        .filter(button -> button.getText().startsWith("gui.config.database.migration.confirm"))
                        .findFirst().orElseThrow().doClick();
            });
            assertTrue(started.await(3, TimeUnit.SECONDS));
        } finally {
            SwingUtilities.invokeAndWait(() -> { if (dialog.get() != null) dialog.get().dispose(); });
        }
    }

    @Test
    @DisplayName("后端启动事件补刷正在读取的目标状态，保留草稿和连接结果并释放监听")
    void refreshesBackendTargetWithoutReplacingDraftOrCheckResult() throws Exception {
        var target = new AtomicReference<>("");
        var draft = new AtomicReference<>(Map.of("database.username", "unsaved-user"));
        var listener = new AtomicReference<java.util.function.Consumer<DesktopUiHost.BackendSnapshot>>();
        var blockNext = new java.util.concurrent.atomic.AtomicBoolean();
        var reading = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        var closed = new java.util.concurrent.atomic.AtomicBoolean();
        var panel = new AtomicReference<DatabaseSettingsPanel>();
        DesktopDatabaseSettings settings = new DesktopDatabaseSettings() {
            public List<ProviderChoice> providers() { return List.of(); }
            public Map<String, String> prepare(Map<String, String> values) { throw new AssertionError("unexpected save"); }
            public CheckResult check(Map<String, String> values) {
                assertFalse(SwingUtilities.isEventDispatchThread());
                assertEquals(draft.get(), values);
                return new CheckResult(Outcome.CONNECTED, "draft-target", "");
            }
            public State state() {
                assertFalse(SwingUtilities.isEventDispatchThread());
                reads.incrementAndGet();
                String observed = target.get();
                if (blockNext.compareAndSet(true, false)) {
                    reading.countDown();
                    try { assertTrue(release.await(3, TimeUnit.SECONDS)); }
                    catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
                }
                return new State(observed, "next-target", List.of(), false);
            }
            public URI migrationGuide() { return URI.create("https://example.invalid/guide"); }
        };
        DesktopUiHost host = (DesktopUiHost) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{DesktopUiHost.class}, (proxy, method, args) -> {
                    if (method.getName().equals("message")) return args[0] + (args.length > 1
                            ? java.util.Arrays.deepToString((Object[]) args[1]) : "");
                    if (method.getName().equals("subscribeBackend")) {
                        @SuppressWarnings("unchecked")
                        var callback = (java.util.function.Consumer<DesktopUiHost.BackendSnapshot>) args[0];
                        listener.set(callback);
                        return (AutoCloseable) () -> closed.set(true);
                    }
                    throw new AssertionError("unexpected host call: " + method.getName());
                });
        try {
            SwingUtilities.invokeAndWait(() -> {
                panel.set(new DatabaseSettingsPanel(host, settings, draft::get));
                panel.get().addNotify();
                ((JButton) descendants(panel.get()).filter(c -> "database.check".equals(c.getName()))
                        .findFirst().orElseThrow()).doClick();
            });
            awaitText(panel.get(), "gui.config.database.stopped");
            awaitText(panel.get(), "gui.config.database.result.connected");
            SwingUtilities.invokeAndWait(() -> {
                blockNext.set(true);
                panel.get().refreshState();
            });
            assertTrue(reading.await(2, TimeUnit.SECONDS));
            target.set("sqlite: active-target");
            listener.get().accept(new DesktopUiHost.BackendSnapshot(DesktopUiHost.BackendState.RUNNING, null));
            SwingUtilities.invokeAndWait(() -> assertEquals("unsaved-user", draft.get().get("database.username")));
            release.countDown();
            awaitText(panel.get(), "sqlite: active-target");
            awaitText(panel.get(), "gui.config.database.result.connected");
            target.set("DATABASE_UNAVAILABLE");
            listener.get().accept(new DesktopUiHost.BackendSnapshot(DesktopUiHost.BackendState.RUNNING, null));
            awaitText(panel.get(), "gui.config.database.unavailable");
            SwingUtilities.invokeAndWait(() -> assertTrue(descendants(panel.get()).filter(JTextArea.class::isInstance)
                    .map(JTextArea.class::cast).noneMatch(area -> area.getText().contains("DATABASE_UNAVAILABLE"))));
            SwingUtilities.invokeAndWait(() -> panel.get().removeNotify());
            assertTrue(closed.get());
            int before = reads.get();
            listener.get().accept(new DesktopUiHost.BackendSnapshot(DesktopUiHost.BackendState.STOPPED, null));
            SwingUtilities.invokeAndWait(() -> assertEquals(before, reads.get()));
        } finally {
            release.countDown();
            SwingUtilities.invokeAndWait(() -> {
                if (panel.get() != null && panel.get().isDisplayable()) panel.get().removeNotify();
            });
        }
    }

    @Test
    @DisplayName("网络等待不占用 EDT，草稿改变后不把旧连接成功显示为当前结果")
    void checksOffEdtAndRejectsStaleResult() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var draft = new AtomicReference<>(Map.of("database.username", "draft"));
        var button = new AtomicReference<JButton>();
        var panel = new AtomicReference<DatabaseSettingsPanel>();
        DesktopDatabaseSettings settings = new DesktopDatabaseSettings() {
            @Override public java.util.List<ProviderChoice> providers() { return java.util.List.of(); }
            public Map<String, String> prepare(Map<String, String> values) { throw new AssertionError("unexpected save"); }
            public CheckResult check(Map<String, String> values) {
                assertFalse(SwingUtilities.isEventDispatchThread());
                assertEquals(Map.of("database.username", "draft"), values);
                entered.countDown();
                try { assertTrue(release.await(3, TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
                return new CheckResult(Outcome.CONNECTED, "postgresql: fixture", "");
            }
            public State state() { return new State("", "postgresql: fixture", List.of(), false); }
            public URI migrationGuide() { return URI.create("https://example.invalid/guide"); }
        };
        DesktopUiHost host = (DesktopUiHost) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{DesktopUiHost.class}, (proxy, method, args) -> {
                    if (method.getName().equals("message")) return args[0];
                    throw new AssertionError("unexpected host call: " + method.getName());
                });
        try {
            SwingUtilities.invokeAndWait(() -> {
                panel.set(new DatabaseSettingsPanel(host, settings, draft::get));
                button.set((JButton) descendants(panel.get()).filter(c -> "database.check".equals(c.getName())).findFirst().orElseThrow());
                button.get().doClick();
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                assertFalse(button.get().isEnabled());
                draft.set(Map.of("database.username", "edited"));
            });
            release.countDown();
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                var done = new java.util.concurrent.atomic.AtomicBoolean();
                while (!done.get()) {
                    SwingUtilities.invokeAndWait(() -> done.set(button.get().isEnabled()));
                    Thread.sleep(10);
                }
            });
            SwingUtilities.invokeAndWait(() -> assertTrue(descendants(panel.get()).filter(JTextArea.class::isInstance)
                    .map(JTextArea.class::cast).anyMatch(t -> t.getText().equals("gui.config.database.stale"))));
        } finally { release.countDown(); }
    }

    private static void awaitText(DatabaseSettingsPanel panel, String expected) {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            var found = new java.util.concurrent.atomic.AtomicBoolean();
            while (!found.get()) {
                SwingUtilities.invokeAndWait(() -> found.set(descendants(panel).filter(JTextArea.class::isInstance)
                        .map(JTextArea.class::cast).anyMatch(area -> area.getText().contains(expected))));
                if (!found.get()) Thread.sleep(10);
            }
        });
    }

    private static Stream<Component> descendants(Component component) {
        return Stream.concat(Stream.of(component), component instanceof Container container
                ? java.util.Arrays.stream(container.getComponents()).flatMap(DatabaseSettingsPanelTest::descendants) : Stream.empty());
    }
}
