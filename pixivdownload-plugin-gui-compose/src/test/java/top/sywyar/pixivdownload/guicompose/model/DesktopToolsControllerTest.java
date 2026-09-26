package top.sywyar.pixivdownload.guicompose.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.function.Function;

@DisplayName("Compose 工具表单默认值")
class DesktopToolsControllerTest {
    @Test
    @DisplayName("目录工具在工作区内打开，等待停服时互斥，关闭后恢复服务")
    void opensInlineAndRestoresServiceOnClose() throws Exception {
        List<String> calls = new ArrayList<>();
        List<Runnable> stopped = new ArrayList<>();
        Map<String, Function<Object[], Object>> overrides = new HashMap<>();
        overrides.put("backendSnapshot", args -> new DesktopUiHost.BackendSnapshot(DesktopUiHost.BackendState.RUNNING, null));
        overrides.put("stopBackend", args -> { calls.add("stop"); stopped.add((Runnable) args[0]); return true; });
        overrides.put("startBackend", args -> { calls.add("start"); ((Runnable) args[0]).run(); return true; });
        overrides.put("recordToolHistory", args -> { calls.add("history"); return null; });
        try (ComposeDesktopUiModel model = DesktopConfigurationControllerTest.model(new HashMap<>(), overrides)) {
            activate(model, "tools.folder-checker.open");
            assertTrue(model.busy());
            activate(model, "tools.folder-checker.open");
            assertEquals(List.of("stop"), calls);
            stopped.get(0).run();
            assertFalse(model.busy());
            assertTrue(model.snapshot().document().dialogs().isEmpty());
            assertTrue(model.snapshot().document().pages().stream().filter(page -> page.id().equals("tools"))
                    .flatMap(page -> descendants(page.content())).anyMatch(node -> node.id().equals("tools.active.close")));
            activate(model, "tools.active.close");
            assertEquals(List.of("stop", "history", "start"), calls);
            assertFalse(model.busy());
            assertTrue(model.snapshot().document().pages().stream()
                    .filter(page -> page.id().equals("tools")).flatMap(page -> descendants(page.content()))
                    .anyMatch(DesktopUiNode.ToolsOverview.class::isInstance));
        }
    }

    @Test
    @DisplayName("迁移失败仍尝试恢复服务，回填失败不停止运行中的服务")
    void restoresOnlyToolsThatPauseTheBackend() throws Exception {
        for (boolean migration : List.of(true, false)) {
            List<String> calls = java.util.Collections.synchronizedList(new ArrayList<>());
            Map<String, Function<Object[], Object>> overrides = new HashMap<>();
            overrides.put("backendSnapshot", args -> new DesktopUiHost.BackendSnapshot(DesktopUiHost.BackendState.RUNNING, null));
            overrides.put("stopBackend", args -> { calls.add("stop"); ((Runnable) args[0]).run(); return true; });
            overrides.put("startBackend", args -> { calls.add("start"); ((Runnable) args[0]).run(); return true; });
            overrides.put("recordToolHistory", args -> { calls.add(args[1].toString()); return null; });
            overrides.put(migration ? "countMigrationCandidates" : "countBackfillCandidates",
                    args -> { throw new IllegalStateException("simulated database failure"); });
            try (ComposeDesktopUiModel model = DesktopConfigurationControllerTest.model(new HashMap<>(), overrides)) {
                activate(model, migration ? "tools.migration.run" : "tools.backfill.run");
                assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                    while (model.busy() || overview(model).activity() == null || overview(model).activity().running()) Thread.sleep(10);
                });
                assertEquals(migration ? List.of("stop", "FAILED", "start") : List.of("FAILED"), calls);
                assertTrue(overview(model).activity().failed());
                assertTrue(model.snapshot().document().dialogs().isEmpty());
            }
        }
    }

    @Test
    @DisplayName("不完整代理地址和无效数字不能启动回填，迁移不能缺少目标路径")
    void validatesBeforeDispatchingTools() throws Exception {
        Map<String, String> values = new HashMap<>(Map.of("tools.backfill.db", "test.db",
                "tools.backfill.proxy", "true", "tools.backfill.proxy-host", "127.0.0.",
                "tools.backfill.proxy-port", "7890", "tools.backfill.delay", "800", "tools.backfill.limit", "0"));
        assertTrue(ToolInputValidation.errors("backfill", values).containsKey("tools.backfill.proxy-host"));
        values.put("tools.backfill.proxy", "false");
        assertTrue(ToolInputValidation.errors("backfill", values).isEmpty());
        for (String invalid : List.of("-1", "x", "999999999999")) {
            values.put("tools.backfill.limit", invalid);
            assertTrue(ToolInputValidation.errors("backfill", values).containsKey("tools.backfill.limit"));
        }
        assertEquals(2, ToolInputValidation.errors("migration", Map.of()).size());
        List<String> calls = new ArrayList<>();
        try (ComposeDesktopUiModel model = DesktopConfigurationControllerTest.model(new HashMap<>(), Map.of(
                "backendSnapshot", args -> new DesktopUiHost.BackendSnapshot(DesktopUiHost.BackendState.RUNNING, null),
                "countBackfillCandidates", args -> { calls.add("run"); return 0; }))) {
            model.dispatch(model.snapshot(), new DesktopUiNode.Event(DesktopUiNode.EventType.CHANGE,
                    "tools.backfill.proxy", DesktopUiNode.Value.bool(true)));
            model.dispatch(model.snapshot(), new DesktopUiNode.Event(DesktopUiNode.EventType.CHANGE,
                    "tools.backfill.proxy-host", DesktopUiNode.Value.text("127.0.0.")));
            activate(model, "tools.backfill.run");
            assertTrue(calls.isEmpty());
            assertTrue(model.snapshot().document().dialogs().isEmpty());
        }
    }

    private static DesktopUiNode.ToolsOverview overview(ComposeDesktopUiModel model) {
        return model.snapshot().document().pages().stream().filter(page -> page.id().equals("tools"))
                .flatMap(page -> descendants(page.content())).filter(DesktopUiNode.ToolsOverview.class::isInstance)
                .map(DesktopUiNode.ToolsOverview.class::cast).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("媒体检查不随表单编辑重复执行，路径校验失败不写配置，安装保留真实进度")
    void mediaActionsUseHostValidationAndProgress() throws Exception {
        Map<String, String> stored = new java.util.concurrent.ConcurrentHashMap<>();
        var probes = new java.util.concurrent.atomic.AtomicInteger();
        var installed = new java.util.concurrent.atomic.AtomicBoolean();
        var release = new java.util.concurrent.CountDownLatch(1);
        var started = new java.util.concurrent.CountDownLatch(1);
        var installation = new DesktopUiHost.FfmpegInstallation(Path.of("ffmpeg"), Path.of("ffprobe"), Path.of("."), DesktopUiHost.FfmpegSource.MANAGED);
        Map<String, Function<Object[], Object>> overrides = new HashMap<>();
        overrides.put("locateFfmpeg", args -> { probes.incrementAndGet(); return installed.get() ? java.util.Optional.of(installation) : java.util.Optional.empty(); });
        overrides.put("supportsManagedFfmpegInstall", args -> true);
        overrides.put("validateCoreConfigValue", args -> {
            if (args[1].equals("bad")) throw new IllegalArgumentException("invalid executable");
            return null;
        });
        overrides.put("installManagedFfmpeg", args -> {
            ((DesktopUiHost.FfmpegProgressListener) args[1]).onProgress(DesktopUiHost.FfmpegInstallStage.DOWNLOADING, 3, 10);
            started.countDown();
            try { release.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
            installed.set(true);
            return installation;
        });
        try (ComposeDesktopUiModel model = DesktopConfigurationControllerTest.model(stored, overrides)) {
            awaitMediaReady(model);
            int baseline = probes.get();
            model.dispatch(model.snapshot(), new DesktopUiNode.Event(DesktopUiNode.EventType.CHANGE,
                    "tools.ffmpeg.path", DesktopUiNode.Value.text("bad")));
            assertEquals(baseline, probes.get());
            activate(model, "status.ffmpeg.path.save");
            awaitMediaReady(model);
            assertFalse(stored.containsKey("ffmpeg.executable-path"));
            model.dispatch(model.snapshot(), new DesktopUiNode.Event(DesktopUiNode.EventType.CHANGE,
                    "tools.ffmpeg.path", DesktopUiNode.Value.text("")));
            activate(model, "status.ffmpeg.path.save");
            awaitMediaReady(model);
            assertEquals("", stored.get("ffmpeg.executable-path"));
            activate(model, "status.ffmpeg.install");
            assertTrue(model.snapshot().document().dialogs().isEmpty());
            activate(model, "ffmpeg.confirm.install");
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
            var progress = descendants(overview(model).media()).filter(DesktopUiNode.Progress.class::isInstance)
                    .map(DesktopUiNode.Progress.class::cast).findFirst().orElseThrow();
            assertEquals(.3d, progress.progress());
            assertFalse(progress.indeterminate());
            assertTrue(model.busy());
            release.countDown();
            awaitMediaReady(model);
            assertTrue(installed.get());
            assertTrue(descendants(overview(model).media()).filter(DesktopUiNode.Text.class::isInstance)
                    .map(DesktopUiNode.Text.class::cast).anyMatch(text -> text.text().key().equals("gui.ffmpeg.badge.ready")));
        } finally { release.countDown(); }
    }

    private static void awaitMediaReady(ComposeDesktopUiModel model) {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            while (model.busy() || descendants(overview(model).media()).filter(DesktopUiNode.Button.class::isInstance)
                    .map(DesktopUiNode.Button.class::cast).noneMatch(button -> button.id().equals("status.ffmpeg.refresh") && button.enabled())) Thread.sleep(10);
        });
    }

    @Test
    @DisplayName("迁移结果保留真实计数，服务恢复抛错也释放工具互锁")
    void preservesCompletionWhenServiceRestoreFails() throws Exception {
        List<Object[]> records = java.util.Collections.synchronizedList(new ArrayList<>());
        Map<String, Function<Object[], Object>> overrides = new HashMap<>();
        overrides.put("backendSnapshot", args -> new DesktopUiHost.BackendSnapshot(DesktopUiHost.BackendState.RUNNING, null));
        overrides.put("stopBackend", args -> { ((Runnable) args[0]).run(); return true; });
        overrides.put("startBackend", args -> { throw new IllegalStateException("restart failed"); });
        overrides.put("countMigrationCandidates", args -> 8);
        overrides.put("runMigration", args -> new DesktopUiHost.MigrationSummary(8, 6, 2, false, ""));
        overrides.put("recordToolHistory", args -> { records.add(args); return null; });
        overrides.put("openToolLog", args -> new DesktopUiHost.ToolLogSession() {
            public Path latestPath() { return Path.of("latest.html"); }
            public Path sessionPath() { return Path.of("session.html"); }
            public void openLatestInBrowser() {}
            public void close() {}
        });
        try (ComposeDesktopUiModel model = DesktopConfigurationControllerTest.model(new HashMap<>(), overrides)) {
            activate(model, "tools.migration.run");
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                while (model.busy() || overview(model).activity() == null || overview(model).activity().running()) Thread.sleep(10);
            });
            assertFalse(overview(model).activity().failed());
            assertEquals(1, records.size());
            assertEquals(DesktopUiHost.ToolOutcome.SUCCEEDED, records.get(0)[1]);
            assertEquals(8, records.get(0)[3]);
            assertEquals(6, records.get(0)[4]);
            assertTrue(descendants(overview(model)).filter(DesktopUiNode.Button.class::isInstance)
                    .map(DesktopUiNode.Button.class::cast).filter(button -> button.id().equals("tools.migration.run"))
                    .allMatch(DesktopUiNode.Button::enabled));
        }
    }

    private static void activate(ComposeDesktopUiModel model, String id) {
        synchronized (model) {
            model.dispatch(model.snapshot(), new DesktopUiNode.Event(DesktopUiNode.EventType.ACTIVATE, id, DesktopUiNode.Value.empty()));
        }
    }

    private static java.util.stream.Stream<DesktopUiNode> descendants(DesktopUiNode node) {
        return java.util.stream.Stream.concat(java.util.stream.Stream.of(node), node.childNodes().stream().flatMap(DesktopToolsControllerTest::descendants));
    }

    @Test
    @DisplayName("界面显示的路径默认值同时写入动作读取的表单状态")
    void storesVisiblePathDefaultsInFormState() {
        Path database = Path.of("data", "pixiv-download.db");
        Map<String, String> values = new HashMap<>();
        values.put("tools.migration.db", "custom.db");

        DesktopToolsController controller = new DesktopToolsController(
                null,
                host(database),
                "downloads",
                values
        );

        assertEquals(database.toString(), controller.form("tools.folder.db", ""));
        assertEquals("custom.db", controller.form("tools.migration.db", ""));
        assertEquals("downloads", controller.form("tools.migration.root", ""));
    }

    private static DesktopUiHost host(Path database) {
        return (DesktopUiHost) Proxy.newProxyInstance(
                DesktopUiHost.class.getClassLoader(),
                new Class<?>[]{DesktopUiHost.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "resolveDatabasePath" -> database;
                    case "defaultBackfillOptions" -> new DesktopUiHost.BackfillOptions(
                            database.toString(),
                            "localhost",
                            8080,
                            false,
                            1000,
                            0,
                            false
                    );
                    case "loadImageClassifierSettings" -> new DesktopUiHost.ImageClassifierSettings(
                            "",
                            false,
                            "http://localhost:6999",
                            List.of()
                    );
                    default -> throw new AssertionError("unexpected DesktopUiHost call: " + method.getName());
                }
        );
    }
}
