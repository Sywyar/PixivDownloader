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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.function.Function;

@DisplayName("Compose 工具表单默认值")
class DesktopToolsControllerTest {
    @Test
    @DisplayName("工具配置读取失败不会阻断页面加载")
    void configurationFailureKeepsToolsAvailable() throws Exception {
        var overrides = new HashMap<String, Function<Object[], Object>>();
        overrides.put("defaultBackfillOptions", args -> { throw new IllegalStateException("Configuration unavailable"); });
        try (var model = DesktopConfigurationControllerTest.model(new HashMap<>(), overrides)) {
            awaitMediaReady(model);
            assertTrue(descendants(overview(model)).anyMatch(node -> "tools.backfill.run".equals(node.id())));
        }
    }

    @Test
    @DisplayName("远程主库在回填、导入和目录检查中保留宿主目标且禁止文件选择")
    void remoteDatabaseTargetRemainsReadOnly() throws Exception {
        String target = "postgresql:configured-database";
        var overrides = new HashMap<String, Function<Object[], Object>>();
        overrides.put("supportsDatabaseFileSelection", args -> false);
        overrides.put("resolveDatabasePath", args -> { throw new AssertionError("remote target is not a file"); });
        overrides.put("defaultBackfillOptions", args -> new DesktopUiHost.BackfillOptions(
                target, "localhost", 8080, false, 1000, 0, false));
        try (var model = DesktopConfigurationControllerTest.model(new HashMap<>(), overrides)) {
            awaitMediaReady(model);
            for (String id : List.of("tools.backfill.db", "tools.migration.db")) {
                var input = descendants(overview(model)).filter(DesktopUiNode.TextInput.class::isInstance)
                        .map(DesktopUiNode.TextInput.class::cast).filter(node -> node.id().equals(id))
                        .findFirst().orElseThrow();
                assertEquals(target, input.value());
                assertFalse(input.enabled());
            }
            activate(model, "tools.folder-checker.open");
            var input = model.snapshot().document().pages().stream()
                    .flatMap(page -> descendants(page.content())).filter(DesktopUiNode.TextInput.class::isInstance)
                    .map(DesktopUiNode.TextInput.class::cast).filter(node -> node.id().equals("tools.folder.db"))
                    .findFirst().orElseThrow();
            assertEquals(target, input.value());
            assertFalse(input.enabled());
        }
    }

    @Test
    @DisplayName("查看日志只打开已有页面，不新建或覆盖日志会话")
    void viewingLogsDoesNotCreateSessions() throws Exception {
        var opened = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var created = new ArrayList<Path>();
        var overrides = new HashMap<String, Function<Object[], Object>>();
        overrides.put("findToolLog", args -> java.util.Optional.of(Path.of("log", "html", (String) args[0]).toAbsolutePath()));
        overrides.put("openExternalUri", args -> { opened.add(Path.of((java.net.URI) args[0]).getFileName().toString()); return null; });
        overrides.put("openToolLog", args -> { throw new AssertionError("Viewing must not create a session"); });
        try {
            for (String stem : List.of("artworks-backfill", "json-to-sqlite-migration")) {
                Path log = Path.of("log", "html", stem + "-latest.html");
                java.nio.file.Files.createDirectories(log.getParent());
                if (!java.nio.file.Files.exists(log)) {
                    java.nio.file.Files.writeString(log, "existing diagnostic");
                    created.add(log);
                }
            }
            try (var model = DesktopConfigurationControllerTest.model(new HashMap<>(), overrides)) {
                awaitMediaReady(model);
                activate(model, "tools.backfill.log");
                DesktopConfigurationControllerTest.awaitReady(model);
                activate(model, "tools.migration.log");
                DesktopConfigurationControllerTest.awaitReady(model);
                assertEquals(List.of("artworks-backfill-latest.html", "json-to-sqlite-migration-latest.html"), opened);
            }
        } finally {
            for (Path log : created) {
                assertEquals("existing diagnostic", java.nio.file.Files.readString(log));
                java.nio.file.Files.delete(log);
            }
        }
    }

    @Test
    @DisplayName("回填跟随已保存代理，临时覆盖不写配置且后续保存不覆盖临时草稿")
    void backfillInheritsConfigurationAndKeepsTemporaryOverrides() throws Exception {
        Map<String, String> stored = new java.util.concurrent.ConcurrentHashMap<>(Map.of(
                "proxy.enabled", "false", "proxy.host", "saved.proxy", "proxy.port", "17891"));
        var captured = new java.util.concurrent.atomic.AtomicReference<DesktopUiHost.BackfillOptions>();
        Map<String, Function<Object[], Object>> overrides = new HashMap<>();
        overrides.put("defaultBackfillOptions", args -> new DesktopUiHost.BackfillOptions(
                "data/test.db", stored.get("proxy.host"), Integer.parseInt(stored.get("proxy.port")),
                Boolean.parseBoolean(stored.get("proxy.enabled")), 0, 0, false));
        overrides.put("backendSnapshot", args -> new DesktopUiHost.BackendSnapshot(DesktopUiHost.BackendState.RUNNING, null));
        overrides.put("countBackfillCandidates", args -> 0);
        overrides.put("recordToolHistory", args -> null);
        overrides.put("openToolLog", args -> new DesktopUiHost.ToolLogSession() {
            public Path latestPath() { return Path.of("latest.html"); }
            public Path sessionPath() { return Path.of("session.html"); }
            public void openLatestInBrowser() {}
            public void close() {}
        });
        overrides.put("runBackfill", args -> {
            captured.set((DesktopUiHost.BackfillOptions) args[0]);
            return new DesktopUiHost.BackfillSummary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false, false);
        });
        try (var model = DesktopConfigurationControllerTest.model(stored, overrides)) {
            awaitMediaReady(model);
            stored.put("proxy.port", "17892");
            activate(model, "tools.backfill.run");
            DesktopConfigurationControllerTest.awaitReady(model);
            assertEquals(17892, captured.get().proxyPort());
            assertFalse(captured.get().useProxy());
            model.dispatch(model.snapshot(), new DesktopUiNode.Event(DesktopUiNode.EventType.CHANGE,
                    "tools.backfill.proxy", DesktopUiNode.Value.bool(true)));
            model.dispatch(model.snapshot(), new DesktopUiNode.Event(DesktopUiNode.EventType.CHANGE,
                    "tools.backfill.proxy-host", DesktopUiNode.Value.text("temporary.proxy")));
            stored.put("proxy.host", "wizard.proxy");
            model.coreConfigValuesSaved(Map.of("proxy.host", "wizard.proxy"));
            model.rebuild();
            activate(model, "tools.backfill.run");
            DesktopConfigurationControllerTest.awaitReady(model);
            assertEquals("temporary.proxy", captured.get().proxyHost());
            assertTrue(captured.get().useProxy());
            assertEquals("wizard.proxy", stored.get("proxy.host"));
            assertEquals(3, stored.size());
        }
    }

    @Test
    @DisplayName("分类器设置在工具工作区中打开、编辑并关闭时端点保持唯一")
    void classifierSettingsKeepUniqueEndpoints() throws Exception {
        try (var model = DesktopConfigurationControllerTest.model(new HashMap<>())) {
            awaitMediaReady(model);
            activate(model, "tools.image-classifier.open");
            activate(model, "classifier.settings");
            var document = model.snapshot().document();
            assertEquals(1, document.dialogs().size());
            assertTrue(DesktopUiEventProtocol.index(document).containsKey("classifier.settings"));
            assertTrue(DesktopUiEventProtocol.index(document).containsKey("classifier.settings.folder"));
            model.dispatch(model.snapshot(), new DesktopUiNode.Event(DesktopUiNode.EventType.CHANGE,
                    "classifier.settings.folder", DesktopUiNode.Value.text("test-folder")));
            activate(model, "classifier.settings.cancel");
            assertTrue(model.snapshot().document().dialogs().isEmpty());
            assertTrue(DesktopUiEventProtocol.index(model.snapshot().document()).containsKey("classifier.settings"));
        }
    }

    @Test
    @DisplayName("回填展示真实进度，取消后释放互锁且不把中断带入后续动作")
    @SuppressWarnings("unchecked")
    void cancelsBackfillWithoutInterruptingLaterWork() throws Exception {
        for (boolean cancelDuringCount : List.of(false, true)) {
            for (boolean dryRun : List.of(false, true)) {
                var started = new java.util.concurrent.CountDownLatch(1);
                var records = new java.util.concurrent.CopyOnWriteArrayList<Object[]>();
                Map<String, Function<Object[], Object>> overrides = new HashMap<>();
                overrides.put("backendSnapshot", args -> new DesktopUiHost.BackendSnapshot(DesktopUiHost.BackendState.RUNNING, null));
                overrides.put("countBackfillCandidates", args -> {
                    if (cancelDuringCount) {
                        started.countDown();
                        try { new java.util.concurrent.CountDownLatch(1).await(); }
                        catch (InterruptedException expected) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(new java.sql.SQLException("connection wait interrupted", expected));
                        }
                    }
                    return 3;
                });
                overrides.put("recordToolHistory", args -> {
                    assertFalse(Thread.currentThread().isInterrupted());
                    records.add(args);
                    return null;
                });
                overrides.put("openToolLog", args -> new DesktopUiHost.ToolLogSession() {
                    public Path latestPath() { return Path.of("latest.html"); }
                    public Path sessionPath() { return Path.of("session.html"); }
                    public void openLatestInBrowser() {}
                    public void close() {}
                });
                overrides.put("runBackfill", args -> {
                    var progress = (java.util.function.BiConsumer<Integer, Integer>) args[1];
                    progress.accept(1, 3);
                    started.countDown();
                    try { new java.util.concurrent.CountDownLatch(1).await(); }
                    catch (InterruptedException expected) {
                        Thread.currentThread().interrupt();
                        progress.accept(1, 3);
                    }
                    throw new AssertionError("cancel must abort the operation");
                });
                try (var model = DesktopConfigurationControllerTest.model(new HashMap<>(), overrides)) {
                    awaitMediaReady(model);
                    model.dispatch(model.snapshot(), new DesktopUiNode.Event(DesktopUiNode.EventType.CHANGE,
                            "tools.backfill.dry", DesktopUiNode.Value.bool(dryRun)));
                    activate(model, "tools.backfill.run");
                    assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
                    if (!cancelDuringCount) {
                        var progress = descendants(overview(model)).filter(DesktopUiNode.Progress.class::isInstance)
                                .map(DesktopUiNode.Progress.class::cast)
                                .filter(node -> node.id().equals("tools.backfill.progress")).findFirst().orElseThrow();
                        assertEquals(1d / 3, progress.progress());
                    }
                    activate(model, "tools.backfill.cancel");
                    assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                        while (model.busy() || overview(model).activity().running()) Thread.sleep(10);
                    });
                    assertFalse(overview(model).activity().running());
                    assertFalse(overview(model).activity().failed());
                    assertEquals(dryRun ? "gui.tools.history.outcome.cancelled" : "gui.compose.tools.workspace.cancelled",
                            overview(model).activity().message().key());
                    assertEquals("gui.tools.backend-status", overview(model).backend().text().key());
                    assertEquals(DesktopUiHost.ToolOutcome.CANCELLED, records.get(0)[1]);
                    assertEquals(cancelDuringCount ? null : 1, records.get(0)[3]);
                    assertEquals(cancelDuringCount ? null : Path.of("session.html"), records.get(0)[6]);
                    var next = new java.util.concurrent.CompletableFuture<Boolean>();
                    model.executeAsync(() -> next.complete(Thread.currentThread().isInterrupted()));
                    assertFalse(next.get(5, java.util.concurrent.TimeUnit.SECONDS));
                }
            }
        }
    }

    @Test
    @DisplayName("回填和迁移失败后仍记录本次日志路径并关闭日志会话")
    void failedToolsKeepTheirLogPath() throws Exception {
        for (boolean migration : List.of(false, true)) {
            var records = new java.util.concurrent.CopyOnWriteArrayList<Object[]>();
            var closed = new java.util.concurrent.atomic.AtomicBoolean();
            Map<String, Function<Object[], Object>> overrides = new HashMap<>();
            overrides.put("backendSnapshot", args -> new DesktopUiHost.BackendSnapshot(DesktopUiHost.BackendState.RUNNING, null));
            overrides.put("stopBackend", args -> { ((Runnable) args[0]).run(); return true; });
            overrides.put("startBackend", args -> { ((Runnable) args[0]).run(); return true; });
            overrides.put("recordToolHistory", args -> { records.add(args); return null; });
            overrides.put("openToolLog", args -> new DesktopUiHost.ToolLogSession() {
                public Path latestPath() { return Path.of("latest.html"); }
                public Path sessionPath() { return Path.of("session.html"); }
                public void openLatestInBrowser() {}
                public void close() { closed.set(true); }
            });
            overrides.put(migration ? "runMigration" : "runBackfill", args -> {
                throw new IllegalStateException("simulated operation failure");
            });
            try (var model = DesktopConfigurationControllerTest.model(new HashMap<>(), overrides)) {
                awaitMediaReady(model);
                activate(model, migration ? "tools.migration.run" : "tools.backfill.run");
                DesktopConfigurationControllerTest.awaitReady(model);
                assertEquals(1, records.size());
                assertEquals(DesktopUiHost.ToolOutcome.FAILED, records.get(0)[1]);
                assertEquals(Path.of("session.html"), records.get(0)[6]);
                assertTrue(closed.get());
            }
        }
    }

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
            awaitMediaReady(model);
            model.dispatch(model.snapshot(), new DesktopUiNode.Event(DesktopUiNode.EventType.CHANGE,
                    "tools.backfill.proxy", DesktopUiNode.Value.bool(true)));
            model.dispatch(model.snapshot(), new DesktopUiNode.Event(DesktopUiNode.EventType.CHANGE,
                    "tools.backfill.proxy-host", DesktopUiNode.Value.text("127.0.0.")));
            activate(model, "tools.backfill.run");
            assertNull(overview(model).activity());
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
    @DisplayName("媒体检查不随表单编辑重复执行，工具不保存路径，安装保留真实进度")
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
                    "tools.backfill.proxy-host", DesktopUiNode.Value.text("temporary.proxy")));
            assertEquals(baseline, probes.get());
            assertFalse(descendants(overview(model).media()).anyMatch(node -> node.id().equals("tools.ffmpeg.path")));
            activate(model, "status.ffmpeg.settings");
            assertFalse(stored.containsKey("ffmpeg.executable-path"));
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

    static void awaitMediaReady(ComposeDesktopUiModel model) {
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
