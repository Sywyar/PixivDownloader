package top.sywyar.pixivdownload.pixivbatchdownloaderimport;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.pixivbatchdownloaderimport.history.HistoryImportService;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxClient;
import top.sywyar.pixivdownload.core.work.importing.*;
import top.sywyar.pixivdownload.core.work.service.WorkQueryService;
import top.sywyar.pixivdownload.plugin.api.task.*;
import static top.sywyar.pixivdownload.plugin.api.gui.importing.DesktopFileImportTool.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("历史导入执行与取消")
class HistoryImportServiceTest {
    private final ImportLogCapture logs = new ImportLogCapture();
    @AfterEach void closeLogs() { logs.close(); }
    @TempDir(factory = HistoryTestDirectory.class) Path temporary;
    private final Set<String> present = ConcurrentHashMap.newKeySet();
    private final List<WorkFileImportRequest> imported = new CopyOnWriteArrayList<>();
    private final AtomicInteger network = new AtomicInteger();
    private final AtomicReference<RuntimeException> statisticsFailure = new AtomicReference<>();
    private String metadata(String id, int type, int pages) {
        return """
            {"error":false,"body":{"id":"%s","illustType":%s,"pageCount":%s,"aiType":1,"xRestrict":0,
            "title":"作品","userId":"90","userName":"作者","tags":{"tags":[{"tag":"标签"}]},"content":"正文"}}
            """.formatted(id, type, pages);
    }
    private HistoryImportService service(PixivAjaxClient client, PixivBatchDownloaderImportStatistics statistics) {
        var queries = (WorkQueryService) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{WorkQueryService.class}, (proxy, method, args) -> {
                    if (method.getName().equals("hasWork")) return present.contains(args[0] + ":" + args[1]);
                    throw new UnsupportedOperationException(method.getName());
                });
        PluginRuntimeTaskRegistrar tasks = new PluginRuntimeTaskRegistrar() {
            public boolean acceptsNewTasks() { return true; }
            public PluginRuntimeTask registerPeriodic(Runnable delegate) { throw new UnsupportedOperationException(); }
            public PluginRuntimeTask registerOneShot(Runnable delegate) {
                return new PluginRuntimeTask() {
                    Future<?> future;
                    public void run() { delegate.run(); }
                    public void bindCancellation(Future<?> value) { future = value; }
                    public void cancel() { if (future != null) future.cancel(true); }
                    public void discardUnsubmitted() {}
                };
            }
        };
        return new HistoryImportService(request -> {
            imported.add(request);
            return present.add(request.workType() + ":" + request.workId());
        }, queries, client, tasks, statistics, Path.of("").toAbsolutePath().relativize(temporary.resolve("cache")), logs.diagnostics);
    }
    private Request request(String records) throws Exception {
        Path root = Files.createDirectories(temporary.resolve("作品"));
        Files.writeString(root.resolve("a.png"), "source");
        Files.writeString(root.resolve("novel.txt"), "local novel");
        Path json = Files.writeString(temporary.resolve("history.json"), records, java.nio.charset.StandardCharsets.UTF_8);
        return new Request(json.toString(), root.toString());
    }
    private void await(HistoryImportService service) {
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(10), () -> {
            while (service.status().active()) Thread.sleep(10);
        });
    }

    @Test @DisplayName("预览缺失明细有界，汇总保留完整数量且不输出原路径")
    void boundsMissingFileLogs() throws Exception {
        var statistics = new PixivBatchDownloaderImportStatistics(dataSource(temporary.resolve("stats.db")));
        String records = java.util.stream.IntStream.rangeClosed(1, 120)
                .mapToObj(id -> "{\"id\":\"" + id + "_p0\",\"n\":\"private-name.png\"}")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        try (var service = service((uri, cookie) -> { throw new AssertionError("unexpected network"); }, statistics)) {
            var preview = service.inspect(request(records)).valueOrThrow();
            assertEquals(120, preview.unavailable());
            assertEquals(0, preview.candidates());
            assertEquals(100, logs.messages().stream().filter(line -> line.contains("MISSING_FILES")).count());
            assertTrue(logs.messages().stream().anyMatch(line -> line.contains("unavailable=120")));
            assertTrue(logs.messages().stream().noneMatch(line -> line.contains("private-name") || line.contains(preview.token())));
        }
    }

    @Test @DisplayName("元数据异常记录错误位置和作品，异常消息与凭据不进入日志")
    void recordsSanitizedUnexpectedFailure() throws Exception {
        var statistics = new PixivBatchDownloaderImportStatistics(dataSource(temporary.resolve("stats.db")));
        try (var service = service((uri, cookie) -> {
            throw new IllegalStateException("secret-cookie\nforged-log", new RuntimeException("secret-response"));
        }, statistics)) {
            var preview = service.inspect(request("[{\"id\":\"42_p0\",\"n\":\"a.png\"}]")).valueOrThrow();
            service.start(preview.token(), "secret-cookie").valueOrThrow();
            await(service);
            assertEquals(1, service.status().failed());
            assertTrue(logs.events.stream().anyMatch(event -> event.getLevel() == ch.qos.logback.classic.Level.ERROR
                    && event.getFormattedMessage().contains("stage=metadata")
                    && event.getFormattedMessage().contains("artwork:42")
                    && event.getFormattedMessage().contains("IllegalStateException")));
            assertTrue(logs.messages().stream().noneMatch(line -> line.contains("secret-") || line.contains("forged-log")));
            assertTrue(logs.events.stream().allMatch(event -> event.getThrowableProxy() == null));
        }
    }
    @Test @DisplayName("预览不联网或入库；开始获取元数据并登记，重复导入跳过")
    void inspectStartAndRepeat() throws Exception {
        var statistics = new PixivBatchDownloaderImportStatistics(dataSource(temporary.resolve("stats.db")));
        var request = request("[{\"id\":\"42_p0\",\"n\":\"a.png\",\"d\":\"2001/01/01\"}]");
        try (var service = service((uri, credential) -> {
            network.incrementAndGet(); assertEquals("temporary-cookie", credential);
            return metadata("42", 0, 1);
        }, statistics)) {
            var preview = service.inspect(request).valueOrThrow();
            assertEquals(1, preview.candidates()); assertEquals(0, network.get()); assertTrue(imported.isEmpty());
            assertEquals("WAITING", service.progress().results().get(0).state());
            assertNotNull(service.start("wrong", "").error());
            service.start(preview.token(), "temporary-cookie").valueOrThrow();
            await(service);
            assertEquals(Phase.COMPLETED, service.status().phase());
            assertEquals(1, service.status().imported());
            assertEquals("IMPORTED", service.progress().results().get(0).state());
            assertEquals("artwork:42", service.progress().results().get(0).workKey());
            assertTrue(logs.messages().stream().anyMatch(line -> line.contains("phase=COMPLETED")
                    && line.contains("imported=1") && line.contains("failed=0")));
            assertTrue(logs.messages().stream().noneMatch(line -> line.contains("temporary-cookie") || line.contains(preview.token())));
            int entries = logs.events.size();
            for (int i = 0; i < 20; i++) service.status();
            assertEquals(entries, logs.events.size());
            assertEquals("作品", imported.get(0).title());
            assertEquals(List.of(Path.of(request.sourceRoot()).resolve("a.png")), imported.get(0).pageFiles());
            assertEquals("source", Files.readString(imported.get(0).pageFiles().get(0)));
            assertNotNull(service.start(preview.token(), "").error());
            assertEquals(1, service.inspect(request).valueOrThrow().skipped());
            assertTrue(service.progress().results().isEmpty());
            assertEquals(1, network.get());
            assertEquals("1", statistics.snapshot().cards().get(0).primaryValue().fallback());
        }
        try (var cache = Files.list(temporary.resolve("cache"))) { assertEquals(0, cache.count()); }
    }
    @Test @DisplayName("同数值插画与小说分别识别；缺页元数据不登记")
    void novelAndIncomplete() throws Exception {
        var statistics = new PixivBatchDownloaderImportStatistics(dataSource(temporary.resolve("stats.db")));
        var request = request("[{\"id\":\"42_p0\",\"n\":\"a.png\"},{\"id\":\"42\",\"n\":\"novel.txt\"}]");
        try (var service = service((uri, credential) -> metadata("42", 0, 2), statistics)) {
            service.start(service.inspect(request).valueOrThrow().token(), "").valueOrThrow();
            await(service);
            assertEquals(1, service.status().failed());
            assertEquals(1, service.status().imported());
            assertEquals("正文", imported.get(0).novelContent());
            assertEquals("NOVEL", imported.get(0).workType().name());
            var outcomes = service.progress().results().stream().collect(java.util.stream.Collectors.toMap(
                    HistoryImportService.WorkResult::workKey, value -> value));
            assertEquals("IMPORTED", outcomes.get("novel:42").state());
            assertEquals("FAILED", outcomes.get("artwork:42").state());
            assertEquals("history.error.incomplete", outcomes.get("artwork:42").error().key());
        }
    }
    @Test @DisplayName("取消等待中的元数据请求，实际退出前保持活动且禁止下一任务")
    void cancelsBeforeRegistration() throws Exception {
        var statistics = new PixivBatchDownloaderImportStatistics(dataSource(temporary.resolve("stats.db")));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var request = request("[{\"id\":\"42_p0\",\"n\":\"a.png\"}]");
        try (var service = service((uri, credential) -> {
            entered.countDown();
            boolean interrupted = false;
            while (true) {
                try { release.await(); break; } catch (InterruptedException cancelled) { interrupted = true; }
            }
            if (interrupted) Thread.currentThread().interrupt();
            return metadata("42", 0, 1);
        }, statistics)) {
            service.start(service.inspect(request).valueOrThrow().token(), "").valueOrThrow();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals("RUNNING", service.progress().results().get(0).state());
            service.cancel();
            assertEquals(Phase.CANCELLING, service.status().phase());
            assertNotNull(service.inspect(request).error());
            release.countDown();
            await(service);
            assertEquals(Phase.CANCELLED, service.status().phase());
            assertEquals("WAITING", service.progress().results().get(0).state());
            assertTrue(logs.messages().stream().anyMatch(line -> line.contains("phase=CANCELLED")));
            assertTrue(imported.isEmpty());
        } finally { release.countDown(); }
    }
    @Test @DisplayName("新预览替换旧凭据；服务关闭释放暂存并拒绝启动")
    void replacesPlanAndCloses() throws Exception {
        var statistics = new PixivBatchDownloaderImportStatistics(dataSource(temporary.resolve("stats.db")));
        var request = request("[{\"id\":\"42_p0\",\"n\":\"a.png\"}]");
        var service = service((uri, cookie) -> { throw new AssertionError("unexpected network"); }, statistics);
        var old = service.inspect(request).valueOrThrow();
        var current = service.inspect(request).valueOrThrow();
        assertNotEquals(old.token(), current.token());
        assertNotNull(service.start(old.token(), "").error());
        service.close();
        assertNotNull(service.start(current.token(), "").error());
        try (var cache = Files.list(temporary.resolve("cache"))) { assertEquals(0, cache.count()); }
    }

    @Test @DisplayName("Pixiv 限流时停止剩余请求，不继续批量重试")
    void stopsOnRateLimit() throws Exception {
        var statistics = new PixivBatchDownloaderImportStatistics(dataSource(temporary.resolve("stats.db")));
        var request = request("[{\"id\":\"42_p0\",\"n\":\"a.png\"},{\"id\":\"43_p0\",\"n\":\"a.png\"}]");
        try (var service = service((uri, cookie) -> {
            network.incrementAndGet();
            throw new top.sywyar.pixivdownload.core.pixiv.PixivAjaxException(
                    top.sywyar.pixivdownload.core.pixiv.PixivAjaxFailure.HTTP_STATUS, 429);
        }, statistics)) {
            service.start(service.inspect(request).valueOrThrow().token(), "").valueOrThrow();
            await(service);
            assertEquals(Phase.FAILED, service.status().phase());
            assertEquals(1, service.status().failed());
            assertEquals(1, network.get());
            assertTrue(imported.isEmpty());
            assertTrue(logs.messages().stream().anyMatch(line -> line.contains("http=429") && line.contains("stage=metadata")));
            assertTrue(logs.messages().stream().anyMatch(line -> line.contains("phase=FAILED") && line.contains("failed=1")));
        }
    }
    @Test @DisplayName("候选暂存被截断时明确失败，不把未处理记录报为完成")
    void truncatedPlanFails() throws Exception {
        var statistics = new PixivBatchDownloaderImportStatistics(dataSource(temporary.resolve("stats.db")));
        var request = request("[{\"id\":\"42_p0\",\"n\":\"a.png\"}]");
        try (var service = service((uri, cookie) -> { throw new AssertionError("unexpected network"); }, statistics)) {
            var preview = service.inspect(request).valueOrThrow();
            try (var files = Files.walk(temporary.resolve("cache"))) {
                Path plan = files.filter(path -> path.getFileName().toString().equals("candidates")).findFirst().orElseThrow();
                Files.write(plan, new byte[0]);
            }
            service.start(preview.token(), "").valueOrThrow();
            await(service);
            assertEquals(Phase.FAILED, service.status().phase());
            assertEquals(0, service.status().processed());
            assertFalse(service.status().problems().isEmpty());
            assertTrue(imported.isEmpty());
        }
    }

    @Test @DisplayName("登记后统计能力失效仍保留成功数，停止后可补写统计")
    void retainsImportAfterStatisticsFailure() throws Exception {
        var statistics = new PixivBatchDownloaderImportStatistics(dataSource(temporary.resolve("stats.db")));
        var request = request("[{\"id\":\"42_p0\",\"n\":\"a.png\"}]");
        try (var service = service((uri, cookie) -> {
            statisticsFailure.set(new IllegalStateException("storage unavailable"));
            return metadata("42", 0, 1);
        }, statistics)) {
            service.start(service.inspect(request).valueOrThrow().token(), "").valueOrThrow();
            await(service);
            assertEquals(Phase.FAILED, service.status().phase());
            assertEquals(1, service.status().imported());
            assertEquals(0, service.status().failed());
            assertEquals(1, service.status().processed());
            assertEquals(1, imported.size());
            assertEquals("history.error.statistics", service.status().problems().get(0).reason().key());
            assertEquals("IMPORTED", service.progress().results().get(0).state());
            assertEquals("history.error.statistics", service.progress().results().get(0).error().key());
            statisticsFailure.set(null);
            statistics.flushPending();
            assertEquals("1", statistics.snapshot().cards().get(0).primaryValue().fallback());
        }
    }

    @Test @DisplayName("逐项结果只保留预览样本，执行时已登记的作品准确显示跳过")
    void boundsResultsAndReportsConcurrentRegistration() throws Exception {
        var statistics = new PixivBatchDownloaderImportStatistics(dataSource(temporary.resolve("stats.db")));
        String records = java.util.stream.IntStream.rangeClosed(1, 120)
                .mapToObj(id -> "{\"id\":\"" + id + "_p0\",\"n\":\"a.png\"}")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        try (var service = service((uri, cookie) -> { throw new AssertionError("unexpected network"); }, statistics)) {
            var preview = service.inspect(request(records)).valueOrThrow();
            assertEquals(120, preview.candidates());
            assertEquals(preview.samples().size(), service.progress().results().size());
            java.util.stream.IntStream.rangeClosed(1, 120).forEach(id -> present.add("ARTWORK:" + id));
            service.start(preview.token(), "").valueOrThrow();
            await(service);
            var progress = service.progress();
            assertEquals(120, progress.status().skipped());
            assertEquals(100, progress.results().size());
            assertTrue(progress.results().stream().allMatch(item -> item.state().equals("SKIPPED")));
            assertEquals(preview.samples().stream().map(Item::workKey).toList(),
                    progress.results().stream().map(HistoryImportService.WorkResult::workKey).toList());
        }
    }

    private top.sywyar.pixivdownload.plugin.api.storage.PluginDatabase dataSource(Path file) throws Exception {
        var source = new org.sqlite.SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + file);
        var target = new top.sywyar.pixivdownload.databasesqlite.SqliteDatabasePlugin().resolve(
                new top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionSettings("", "", "", file));
        var schema = top.sywyar.pixivdownload.plugin.api.storage.relational.PrivateStorageSchema.merge(
                PixivBatchDownloaderImportStatistics.schema());
        try (var connection = source.getConnection()) { target.initializeStorage(connection, schema); }
        return (top.sywyar.pixivdownload.plugin.api.storage.PluginDatabase) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{top.sywyar.pixivdownload.plugin.api.storage.PluginDatabase.class, javax.sql.DataSource.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("available")) return true;
                    if (method.getName().equals("databaseId")) return "sqlite";
                    if (method.getName().equals("read") || method.getName().equals("transaction")) {
                        if (method.getName().equals("transaction") && statisticsFailure.get() != null) throw statisticsFailure.get();
                        try (var connection = source.getConnection()) {
                            connection.setAutoCommit(false);
                            try (var session = target.storageSession(connection, schema)) {
                                @SuppressWarnings("unchecked")
                                var work = (top.sywyar.pixivdownload.plugin.api.storage.relational.DatabaseSession.Work<Object>) args[0];
                                try { var value = work.run(session); connection.commit(); return value; }
                                catch (Exception failure) { connection.rollback(); throw failure; }
                            }
                        }
                    }
                    try { return source.getClass().getMethod(method.getName(), method.getParameterTypes()).invoke(source, args); }
                    catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                });
    }


}
