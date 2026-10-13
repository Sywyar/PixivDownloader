package top.sywyar.pixivdownload.guicompose.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode;
import top.sywyar.pixivdownload.plugin.api.gui.*;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Compose 主库设置交互")
class DesktopDatabaseSettingsTest {
    @Test
    @DisplayName("地址与认证字段随选择立即隐藏，重新加载本地库也不显示空地址栏")
    void updatesAllConnectionFieldVisibility() throws Exception {
        var saved = new HashMap<String, String>(Map.of("database.engine", "postgresql"));
        try (var model = model(saved, new Probe())) {
            assertTrue(nodes(model).anyMatch(n -> n.id().equals("config.app.database.jdbc-url.input")));
            edit(model, "database.jdbc-url", "draft-target");
            dispatch(model, new DesktopUiNode.Event(DesktopUiNode.EventType.SELECTION,
                    "config.app.database.engine.input", DesktopUiNode.Value.selection("option.0")));
            for (String key : List.of("jdbc-url", "username", "password"))
                assertFalse(nodes(model).anyMatch(n -> n.id().equals("config.app.database." + key + ".input")));
            selectPostgres(model);
            assertTrue(nodes(model).anyMatch(n -> n instanceof DesktopUiNode.TextInput input
                    && input.id().equals("config.app.database.jdbc-url.input") && input.value().equals("draft-target")));
        }
        saved.put("database.engine", "sqlite");
        try (var model = model(saved, new Probe())) {
            assertFalse(nodes(model).anyMatch(n -> n.id().equals("config.app.database.jdbc-url.input")));
        }
    }
    @Test
    @DisplayName("后台启停自动更新数据库目标，保留未保存字段和草稿检查结果")
    void refreshesTargetsOnBackendChangesWithoutReloadingDraft() throws Exception {
        Map<String, String> saved = new HashMap<>(Map.of("database.engine", "postgresql"));
        Probe probe = new Probe();
        try (var model = model(saved, probe)) {
            Map<String, String> before = Map.copyOf(saved);
            edit(model, "database.username", "unsaved-user");
            edit(model, "database.password", "unsaved-secret");
            activate(model, "database.check");
            ready(model);
            var checked = text(model, "database.result");
            var passwordInput = nodes(model).filter(n -> n.id().equals("config.app.database.password.input"))
                    .findFirst().orElseThrow();
            assertEquals("gui.config.database.result.connected", checked.key());
            assertTrue(text(model, "database.targets").arguments().contains("gui.config.database.stopped"));
            assertEquals("gui.config.database.stopped", aboutDatabase(model).key());

            probe.target.set("sqlite: active-target");
            probe.backendListener.get().accept(new DesktopUiHost.BackendSnapshot(DesktopUiHost.BackendState.RUNNING, null));
            awaitTarget(model, "sqlite: active-target");
            assertEquals("sqlite: active-target", aboutDatabase(model).fallback());
            assertNotSame(Thread.currentThread(), probe.stateReader.get());
            assertEquals(checked, text(model, "database.result"));
            assertTrue(nodes(model).anyMatch(n -> n instanceof DesktopUiNode.TextInput input
                    && input.id().equals("config.app.database.username.input") && input.value().equals("unsaved-user")));
            assertEquals(passwordInput, nodes(model).filter(n -> n.id().equals("config.app.database.password.input"))
                    .findFirst().orElseThrow());

            probe.target.set("");
            probe.backendListener.get().accept(new DesktopUiHost.BackendSnapshot(DesktopUiHost.BackendState.STOPPED, null));
            awaitTarget(model, "gui.config.database.stopped");
            assertEquals("gui.config.database.stopped", aboutDatabase(model).key());
            probe.target.set("DATABASE_UNAVAILABLE");
            probe.backendListener.get().accept(new DesktopUiHost.BackendSnapshot(DesktopUiHost.BackendState.RUNNING, null));
            awaitTarget(model, "gui.config.database.unavailable");
            assertEquals("gui.config.database.unavailable", aboutDatabase(model).key());
            assertFalse(text(model, "database.targets").arguments().contains("DATABASE_UNAVAILABLE"));
            assertEquals(checked, text(model, "database.result"));
            activate(model, "database.check");
            ready(model);
            assertEquals("unsaved-secret", probe.checked.get().get("database.password"));
            assertEquals(before, saved);
        }
        assertNull(probe.backendListener.get());
    }

    @Test
    @DisplayName("检查列出主私库且不保存，确认后才安排迁移，密码保留替换与清除语义不变")
    void confirmsMigrationWithoutSavingConnectionEarly() throws Exception {
        for (String password : List.of("KEEP", " spaced secret ", "")) {
            Map<String, String> saved = new HashMap<>(Map.of("database.engine", "postgresql", "database.password", "stored-secret"));
            Probe probe = new Probe();
            try (var model = model(saved, probe)) {
                Map<String, String> before = Map.copyOf(saved);
                edit(model, "database.username", "fixture");
                if (password.isEmpty()) activate(model, "config.app.database.password.clear.button");
                else if (!password.equals("KEEP")) edit(model, "database.password", password);
                save(model);
                assertEquals(before, saved);
                var dialog = model.snapshot().document().dialogs().stream().filter(d -> d.id().equals("database.migration")).findFirst().orElseThrow();
                assertTrue(descendants(dialog.content()).anyMatch(node -> node.id().equals("database.confirm.store.main")));
                assertTrue(descendants(dialog.content()).filter(DesktopUiNode.Text.class::isInstance)
                        .map(DesktopUiNode.Text.class::cast).anyMatch(node -> node.text().arguments()
                                .contains("gui.config.database.migration.phase.replace-target")));
                assertTrue(descendants(dialog.content()).anyMatch(node -> node.id().equals("database.confirm.store.private:fixture")));
                if (password.equals("KEEP")) assertFalse(probe.planned.get().containsKey("database.password"));
                else assertEquals(password, probe.planned.get().get("database.password"));
                assertEquals(0, probe.starts);
                activate(model, "database.migration-confirm"); ready(model);
                assertEquals(1, probe.starts);
                assertEquals(before, saved);
            }
        }
    }

    @Test
    @DisplayName("草稿检查在后台执行且不保存，后续编辑废弃检查结果")
    void checksDraftWithoutSavingOrBlocking() throws Exception {
        Map<String, String> saved = new HashMap<>(Map.of("database.engine", "postgresql", "database.password", "stored-secret"));
        Probe probe = new Probe();
        probe.wait = true;
        try (var model = model(saved, probe)) {
            Map<String, String> before = Map.copyOf(saved);
            edit(model, "database.username", "draft-user");
            activate(model, "database.check");
            assertTrue(probe.entered.await(2, TimeUnit.SECONDS));
            assertTrue(model.busy());
            assertEquals("draft-user", probe.checked.get().get("database.username"));
            assertFalse(probe.checked.get().containsKey("database.password"));
            assertEquals(before, saved);
            probe.release.countDown();
            ready(model);
            assertTrue(nodes(model).anyMatch(n -> n instanceof DesktopUiNode.Text t
                    && "gui.config.database.result.connected".equals(t.text().key())));
            edit(model, "database.jdbc-url", "jdbc:postgresql://other/db");
            assertFalse(nodes(model).anyMatch(n -> n.id().equals("database.result")));
        } finally { probe.release.countDown(); }
    }

    @Test
    @DisplayName("宿主拒绝连接保存时显示错误提示，配置与密码均保持原值")
    void refusesInvalidBeforeWriting() throws Exception {
        Map<String, String> saved = new HashMap<>(Map.of("database.engine", "postgresql", "database.password", "stored-secret"));
        Probe probe = new Probe();
        probe.reject = true;
        try (var model = model(saved, probe)) {
            Map<String, String> before = Map.copyOf(saved);
            edit(model, "database.jdbc-url", "invalid");
            save(model);
            assertEquals(before, saved);
            assertEquals(DesktopUiNode.TextStyle.ERROR, nodes(model)
                    .filter(n -> n.id().equals("config.notice")).map(DesktopUiNode.Text.class::cast)
                    .findFirst().orElseThrow().style());
        }
    }

    private static DesktopUiNode.TextToken aboutDatabase(ComposeDesktopUiModel model) {
        return DesktopAboutViewTest.overview(model).facts().stream()
                .filter(fact -> fact.id().equals("database")).findFirst().orElseThrow().value();
    }

    @Test
    @DisplayName("关于页只显示活动数据库，不使用已保存或未保存的下次启动目标")
    void aboutUsesActiveDatabase() throws Exception {
        var probe = new Probe();
        probe.target.set("custom-store: active");
        try (var model = model(new HashMap<>(Map.of("database.engine", "postgresql",
                "database.jdbc-url", "jdbc:postgresql://next/db", "database.password", "fixture-secret")), probe)) {
            assertEquals("custom-store: active", aboutDatabase(model).fallback());
            edit(model, "database.jdbc-url", "jdbc:postgresql://draft/db");
            assertEquals("custom-store: active", aboutDatabase(model).fallback());
            assertFalse(DesktopAboutViewTest.overview(model).facts().toString().contains("fixture-secret"));
        }
    }

    private static ComposeDesktopUiModel model(Map<String, String> saved, Probe probe) {
        return model(saved, probe, null);
    }

    @Test
    @DisplayName("安排迁移失败保留原连接与草稿，不把确认当作保存成功")
    void schedulingFailureKeepsDraft() throws Exception {
        Map<String, String> saved = new HashMap<>(Map.of("database.engine", "postgresql", "database.password", "original",
                "database.username", "fixture", "database.jdbc-url", "jdbc:postgresql://fixture/db"));
        Probe probe = new Probe();
        probe.failStart = true;
        try (var model = model(saved, probe)) {
            Map<String, String> before = Map.copyOf(saved);
            edit(model, "database.jdbc-url", "jdbc:postgresql://other/db");
            edit(model, "database.password", "replacement");
            save(model);
            activate(model, "database.migration-confirm"); ready(model);
            assertEquals(before, saved);
            assertTrue(nodes(model).anyMatch(n -> n instanceof DesktopUiNode.TextInput input
                    && input.value().equals("jdbc:postgresql://other/db")));
            assertEquals("gui.config.database.migration.failed", text(model, "database.result").key());
        }
    }

    private static ComposeDesktopUiModel model(Map<String, String> saved, Probe probe, DesktopUiHost.ConfigFile file) {
        List<GuiConfigFieldContribution> fields = new ArrayList<>();
        for (String name : List.of("engine", "jdbc-url", "username", "password")) {
            boolean secret = name.equals("password");
            var visible = !name.equals("engine")
                    ? List.of(GuiConfigCondition.equalsTo("database.engine", "postgresql")) : List.<GuiConfigCondition>of();
            fields.add(new GuiConfigFieldContribution("database." + name, GuiConfigGroups.DATABASE, name, "", null,
                    name.equals("engine") ? GuiConfigFieldType.ENUM : secret ? GuiConfigFieldType.PASSWORD : GuiConfigFieldType.STRING,
                    name.equals("engine") ? "sqlite" : "", fields.size(), secret, GuiConfigEffect.PROCESS_RESTART,
                    name.equals("engine") ? List.of("sqlite", "postgresql") : List.of(), visible, visible, null, null));
        }
        Map<String, java.util.function.Function<Object[], Object>> overrides = new HashMap<>(Map.of(
                "coreConfigGroups", args -> List.of(new GuiConfigGroupContribution(GuiConfigGroups.DATABASE, "Database", 1)),
                "coreConfigFields", args -> fields,
                "validateCoreConfigValue", args -> null,
                "databaseSettings", args -> Optional.of(probe)));
        overrides.put("subscribeBackend", args -> {
            @SuppressWarnings("unchecked")
            var listener = (java.util.function.Consumer<DesktopUiHost.BackendSnapshot>) args[0];
            probe.backendListener.set(listener);
            return (AutoCloseable) () -> probe.backendListener.compareAndSet(listener, null);
        });
        if (file != null) overrides.put("applicationConfig", args -> file);
        return DesktopConfigurationControllerTest.model(saved, overrides);
    }

    private static void selectPostgres(ComposeDesktopUiModel model) {
        dispatch(model, new DesktopUiNode.Event(DesktopUiNode.EventType.SELECTION,
                "config.app.database.engine.input", DesktopUiNode.Value.selection("option.1")));
    }
    private static void edit(ComposeDesktopUiModel model, String key, String value) {
        dispatch(model, new DesktopUiNode.Event(DesktopUiNode.EventType.CHANGE,
                "config.app." + key + ".input", DesktopUiNode.Value.text(value)));
    }
    private static void activate(ComposeDesktopUiModel model, String id) {
        dispatch(model, new DesktopUiNode.Event(DesktopUiNode.EventType.ACTIVATE, id, DesktopUiNode.Value.empty()));
    }
    private static void dispatch(ComposeDesktopUiModel model, DesktopUiNode.Event event) {
        synchronized (model) { model.dispatch(model.snapshot(), event); }
    }
    private static void save(ComposeDesktopUiModel model) { activate(model, "config.save"); ready(model); }
    private static void ready(ComposeDesktopUiModel model) {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            while (true) {
                var dialogs = model.snapshot().document().dialogs();
                var actions = dialogs.isEmpty() ? nodes(model).filter(n -> n.id().equals("database.check"))
                        : dialogs.stream().flatMap(d -> descendants(d.content()));
                boolean enabled = actions.filter(DesktopUiNode.Button.class::isInstance)
                        .map(DesktopUiNode.Button.class::cast).anyMatch(DesktopUiNode.Button::enabled);
                if (!model.busy() && enabled) return;
                Thread.sleep(10);
            }
        });
    }
    private static void awaitTarget(ComposeDesktopUiModel model, String expected) {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            while (!text(model, "database.targets").arguments().contains(expected)) Thread.sleep(10);
        });
    }

    private static DesktopUiNode.TextToken text(ComposeDesktopUiModel model, String id) {
        return nodes(model).filter(n -> n.id().equals(id)).map(DesktopUiNode.Text.class::cast)
                .map(DesktopUiNode.Text::text).findFirst().orElseThrow();
    }

    private static Stream<DesktopUiNode> nodes(ComposeDesktopUiModel model) {
        return model.snapshot().document().pages().stream().filter(p -> p.id().equals("settings"))
                .flatMap(p -> descendants(p.content()));
    }
    private static Stream<DesktopUiNode> descendants(DesktopUiNode node) {
        return Stream.concat(Stream.of(node), node.childNodes().stream().flatMap(DesktopDatabaseSettingsTest::descendants));
    }
    private static class Probe implements DesktopDatabaseSettings {
        @Override public java.util.List<ProviderChoice> providers() { return java.util.List.of(); }
        boolean wait;
        boolean reject;
        boolean failStart;
        int starts;
        final AtomicReference<Map<String, String>> planned = new AtomicReference<>();
        @Override public MigrationPlan previewMigration(Map<String, String> values) throws IOException {
            planned.set(Map.copyOf(prepare(values)));
            return new MigrationPlan("fixture", true, List.of(
                    new MigrationStore("main", "source", "target", "REPLACE_TARGET", List.of()),
                    new MigrationStore("private:fixture", "owner-source", "owner-target", "READY", List.of())), "");
        }
        @Override public void startMigration(String token, DesktopUiHost.IoOperation saveOtherSettings) throws IOException {
            assertEquals("fixture", token);
            if (failStart) throw new IOException("RESTART_FAILED");
            saveOtherSettings.run();
            starts++;
        }
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicReference<Map<String, String>> checked = new AtomicReference<>();
        final AtomicReference<String> target = new AtomicReference<>("");
        final AtomicReference<Thread> stateReader = new AtomicReference<>();
        final AtomicReference<java.util.function.Consumer<DesktopUiHost.BackendSnapshot>> backendListener = new AtomicReference<>();
        public Map<String, String> prepare(Map<String, String> values) throws IOException {
            if (reject) throw new IOException("invalid target");
            return values;
        }
        public CheckResult check(Map<String, String> values) throws IOException {
            checked.set(Map.copyOf(values));
            entered.countDown();
            try { if (wait && !release.await(3, TimeUnit.SECONDS)) throw new IOException("timeout"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
            return new CheckResult(Outcome.CONNECTED, "postgresql: fixture", "");
        }
        public State state() {
            stateReader.set(Thread.currentThread());
            return new State(target.get(), "postgresql: fixture", List.of("database.engine"), false);
        }
        public URI migrationGuide() { return URI.create("https://example.invalid/guide"); }
    }
}
