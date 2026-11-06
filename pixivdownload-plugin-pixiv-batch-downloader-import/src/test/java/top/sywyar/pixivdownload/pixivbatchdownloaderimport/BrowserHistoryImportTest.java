package top.sywyar.pixivdownload.pixivbatchdownloaderimport;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.core.work.importing.*;
import top.sywyar.pixivdownload.pixivbatchdownloaderimport.history.HistoryImportCompletion;
import top.sywyar.pixivdownload.core.work.service.WorkQueryService;
import top.sywyar.pixivdownload.pixivbatchdownloaderimport.history.BrowserHistoryImport;
import top.sywyar.pixivdownload.plugin.api.gui.importing.DesktopFileImportTool.OperationException;
import top.sywyar.pixivdownload.plugin.api.storage.PluginDatabase;
import top.sywyar.pixivdownload.plugin.api.task.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("浏览器历史文件导入")
class BrowserHistoryImportTest {
    private final ImportLogCapture logs = new ImportLogCapture();
    @TempDir(factory = HistoryTestDirectory.class) Path temporary;
    private final List<LocalWorkFileImportRequest> imported = new CopyOnWriteArrayList<>();
    private final Set<String> present = ConcurrentHashMap.newKeySet();
    private final List<PluginRuntimeTask> registered = new CopyOnWriteArrayList<>();
    private final HistoryImportCompletion completions = new HistoryImportCompletion();
    private Path root;
    private BrowserHistoryImport history;
    private PixivBatchDownloaderImportController controller;
    private PixivBatchDownloaderImportDirectory directory;

    @BeforeEach void prepare() throws Exception {
        root = Files.createDirectory(temporary.resolve("作品"));
        Files.writeString(root.resolve("0.png"), "original zero");
        Files.writeString(root.resolve("1.png"), "original one");
        Files.writeString(root.resolve("novel.txt"), "original novel");
        var statistics = new PixivBatchDownloaderImportStatistics(database());
        var queries = (WorkQueryService) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{WorkQueryService.class}, (proxy, method, args) -> {
                    if (method.getName().equals("hasWork")) return present.contains(args[0] + ":" + args[1]);
                    throw new UnsupportedOperationException(method.getName());
                });
        PluginRuntimeTaskRegistrar tasks = new PluginRuntimeTaskRegistrar() {
            public boolean acceptsNewTasks() { return true; }
            public PluginRuntimeTask registerPeriodic(Runnable delegate) { return registerOneShot(delegate); }
            public PluginRuntimeTask registerOneShot(Runnable delegate) {
                var task = new PluginRuntimeTask() {
                    Future<?> future;
                    public void run() { delegate.run(); }
                    public void bindCancellation(Future<?> value) { future = value; }
                    public void cancel() { if (future != null) future.cancel(true); }
                    public void discardUnsubmitted() {}
                };
                registered.add(task); return task;
            }
        };
        history = new BrowserHistoryImport(new WorkFileImporter() {
            public boolean importFiles(WorkFileImportRequest value) { throw new AssertionError("network metadata import called"); }
            public boolean importLocalFiles(LocalWorkFileImportRequest value) {
                imported.add(value); return present.add(value.workType() + ":" + value.workId());
            }
        }, queries, statistics, tasks, Path.of("").toAbsolutePath().relativize(temporary.resolve("cache")), logs.diagnostics, completions);

        directory = new PixivBatchDownloaderImportDirectory(temporary.resolve("settings.properties"));
        Files.writeString(temporary.resolve("settings.properties"), PixivBatchDownloaderImportDirectory.KEY + "=" + root.toString().replace("\\", "/"));
        controller = new PixivBatchDownloaderImportController(value -> { throw new AssertionError("not a live observation"); },
                (namespace, locale, key) -> Optional.of("localized " + key), directory, () -> "solo", statistics, history, logs.diagnostics);
    }

    @AfterEach void close() {
        try { history.close(); registered.forEach(PluginRuntimeTask::cancel); }
        finally { logs.close(); }
    }

    @Test @DisplayName("多分片预览无副作用，开始后直接登记原文件，重复检查跳过")
    void importsShards() throws Exception {
        String first = "[{\"id\":\"42_p1\",\"n\":\"1.png\"}]", second = "[{\"id\":\"42_p0\",\"n\":\"0.png\"}]";
        String id = upload(first, second);
        history.inspect(id);
        var ready = await(id, view -> view.phase().equals("READY"));
        assertEquals(2, ready.preview().records()); assertEquals(1, ready.preview().candidates());
        assertTrue(imported.isEmpty());
        history.start(id, root.toString());
        var done = await(id, view -> view.phase().equals("COMPLETED"));
        assertEquals(1, done.status().imported());
        assertEquals(1, completions.pending().orElseThrow().imported());
        assertEquals("artwork:42", done.results().get(0).workKey());
        assertEquals("IMPORTED", done.results().get(0).state());
        var headers = new HashMap<>(Map.of("Host", "localhost:6999", "X-Pixiv-Collector", "1",
                "Origin", "https://www.pixiv.net", "X-History-Session", id));
        headers.put("X-Import-Token", token(headers));
        var response = controller.history(http("{}", headers, Map.of("action", "status"), "127.0.0.1"));
        assertEquals(200, response.getStatusCode().value());
        var result = ((PixivBatchDownloaderImportController.HistoryResponse) response.getBody()).results().get(0);
        assertEquals("artwork:42", result.workKey());
        assertEquals("IMPORTED", result.state());
        assertNull(result.error());
        assertTrue(logs.messages().stream().noneMatch(line -> line.contains(id)));
        assertTrue(logs.messages().stream().anyMatch(line -> line.contains("phase=COMPLETED") && line.contains("imported=1")));
        int count = logs.events.size();
        for (int i = 0; i < 20; i++) history.status(id);
        assertEquals(count, logs.events.size());
        assertEquals(List.of(root.resolve("0.png"), root.resolve("1.png")), imported.get(0).pageFiles());
        assertEquals("original zero", Files.readString(root.resolve("0.png")));
        assertEquals("original one", Files.readString(root.resolve("1.png")));
        String next = upload(first, second);
        assertThrows(OperationException.class, () -> history.status(id));
        history.inspect(next);
        assertEquals(1, await(next, view -> view.phase().equals("READY")).preview().skipped());
        history.cancel(next);
        try (var paths = Files.walk(temporary.resolve("cache"))) { assertTrue(paths.noneMatch(Files::isRegularFile)); }
    }

    @Test @DisplayName("取消预览后旧会话不能开始登记或进入下一会话")
    void cancellation() throws Exception {
        String id = ready("[{\"id\":\"42_p0\",\"n\":\"0.png\"}]");
        history.cancel(id);
        await(id, view -> view.phase().equals("CANCELLED"));
        assertThrows(OperationException.class, () -> history.start(id, root.toString()));
        assertTrue(imported.isEmpty());
        String next = upload("[]");
        assertThrows(OperationException.class, () -> history.start(id, root.toString()));
        assertEquals("UPLOADING", history.status(next).phase());
        assertTrue(completions.pending().isEmpty());
    }

    @Test @DisplayName("浏览器不能再提交网络元数据")
    void rejectsMetadataReply() throws Exception {
        var headers = new HashMap<>(Map.of("Host", "localhost:6999", "X-Pixiv-Collector", "1", "Origin", "https://www.pixiv.net"));
        headers.put("X-Import-Token", token(headers));
        assertEquals(400, controller.history(http("{}", headers, Map.of("action", "reply"), "127.0.0.1")).getStatusCode().value());
        assertTrue(imported.isEmpty());
    }

    @Test @DisplayName("文件预算、精确偏移、会话身份和配置根约束上传与启动")
    void boundsAndRoot() throws Exception {
        assertEquals("history.error.root", assertThrows(OperationException.class, () -> history.create("")).text().key());
        String id = history.create(root.toString()).session();
        assertThrows(OperationException.class, () -> history.create(root.toString()));
        assertThrows(OperationException.class, () -> history.inspect(id));
        assertThrows(OperationException.class, () -> history.upload(id, 64, 0, new byte[]{'['}));
        assertThrows(OperationException.class, () -> history.upload("stale", 0, 0, new byte[]{'['}));
        assertThrows(OperationException.class, () -> history.upload(id, 0, 1, new byte[]{'['}));
        assertThrows(OperationException.class, () -> history.upload(id, 0, 0, new byte[BrowserHistoryImport.CHUNK_BYTES + 1]));
        history.upload(id, 0, 0, new byte[]{'['});
        assertThrows(OperationException.class, () -> history.upload(id, 0, 0, new byte[]{'['}));
        history.upload(id, 0, 1, new byte[]{']'});
        history.inspect(id); await(id, view -> view.phase().equals("READY"));
        assertEquals("history.error.root", assertThrows(OperationException.class, () -> history.start(id, "")).text().key());
        assertEquals("history.error.root-changed",
                assertThrows(OperationException.class, () -> history.start(id, temporary.toString())).text().key());
        assertTrue(imported.isEmpty());
    }

    @Test @DisplayName("本机可信来源和一次性令牌先于读取请求体；控制接口不接受本机路径")
    void httpBoundary() throws Exception {
        var headers = new HashMap<>(Map.of("Host", "localhost:6999", "X-Pixiv-Collector", "1", "Origin", "https://www.pixiv.net"));
        var params = new HashMap<>(Map.of("action", "create"));
        assertEquals(401, controller.history(http("{}", headers, params, "127.0.0.1")).getStatusCode().value());
        String token = token(headers);
        headers.put("X-Import-Token", token);
        assertEquals(403, controller.history(http("[2]", headers, params, "192.0.2.1")).getStatusCode().value());
        headers.put("Origin", "https://evil.example");
        assertEquals(403, controller.history(http("[2]", headers, params, "127.0.0.1")).getStatusCode().value());
        headers.remove("Origin");
        assertEquals(403, controller.history(http("[2]", headers, params, "127.0.0.1")).getStatusCode().value());
        headers.put("Origin", "https://www.pixiv.net");
        var response = controller.history(http("{}", headers, params, "127.0.0.1"));
        assertEquals(200, response.getStatusCode().value());
        assertEquals("no-store", response.getHeaders().getFirst("Cache-Control"));
        assertEquals(401, controller.history(http("[2]", headers, params, "127.0.0.1")).getStatusCode().value());
        headers.put("X-Import-Token", token(headers));
        assertEquals(400, controller.history(http("{\"sizes\":[2],\"root\":\"C:/\"}", headers, params, "127.0.0.1")).getStatusCode().value());
        headers.put("X-Import-Token", token(headers));
        params.put("action", "upload");
        assertEquals(413, controller.history(http(" ".repeat(BrowserHistoryImport.CHUNK_BYTES + 1), headers, params, "127.0.0.1")).getStatusCode().value());
    }

    @Test @DisplayName("流式上传按实际字节限制单片与累计大小，取消后删除临时文件")
    void streamedByteBudgets() throws Exception {
        String id = history.create(root.toString()).session();
        byte[] chunk = new byte[BrowserHistoryImport.CHUNK_BYTES];
        for (int file = 0; file < 2; file++) {
            for (int part = 0; part < 512; part++) history.upload(id, file, (long)part * chunk.length, chunk);
        }
        assertThrows(OperationException.class, () -> history.upload(id, 0, 536870912L, new byte[]{']'}));
        assertThrows(OperationException.class, () -> history.upload(id, 2, 0, new byte[]{'['}));
        assertTrue(imported.isEmpty());
        history.cancel(id);
        try (var paths = Files.walk(temporary.resolve("cache"))) { assertTrue(paths.noneMatch(Files::isRegularFile)); }
    }

    @Test @DisplayName("未闭合的浏览器历史流在预览中失败，不登记部分内容")
    void truncatedStream() throws Exception {
        String id = upload("[{\"id\":\"42_p0\",\"n\":\"0.png\"}");
        history.inspect(id);
        assertNotNull(await(id, view -> view.phase().equals("FAILED")).error());
        assertTrue(imported.isEmpty());
    }

    @Test @DisplayName("失联上传在空闲期限后清理；关闭会清理预览且不登记作品")
    void expirationAndClose() throws Exception {
        String abandoned = upload("[]");
        var sessionField = BrowserHistoryImport.class.getDeclaredField("session"); sessionField.setAccessible(true);
        Object session = sessionField.get(history);
        var touched = session.getClass().getDeclaredField("touched"); touched.setAccessible(true);
        touched.setLong(session, System.nanoTime() - TimeUnit.MINUTES.toNanos(16));
        registered.get(0).run();
        assertThrows(OperationException.class, () -> history.status(abandoned));
        String id = ready("[{\"id\":\"42_p0\",\"n\":\"0.png\"}]");
        history.close();
        assertThrows(OperationException.class, () -> history.status(id));
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
            while (true) {
                try (var paths = Files.walk(temporary.resolve("cache"))) { if (paths.noneMatch(Files::isRegularFile)) break; }
                Thread.sleep(10);
            }
        });
        assertTrue(imported.isEmpty());
        assertEquals("original zero", Files.readString(root.resolve("0.png")));
    }

    @Test @DisplayName("上传正文尚未读完时仍能查询和取消，迟到上传不能恢复已取消会话")
    void cancellationWhileReceivingBody() throws Exception {
        String id = history.create(root.toString()).session();
        var headers = new HashMap<>(Map.of("Host", "localhost:6999", "X-Pixiv-Collector", "1",
                "Origin", "https://www.pixiv.net", "X-History-Session", id));
        headers.put("X-Import-Token", token(headers));
        var original = http("[]", Map.copyOf(headers), Map.of("action", "upload", "file", "0", "offset", "0"), "127.0.0.1");
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var waiting = (jakarta.servlet.http.HttpServletRequest)java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{jakarta.servlet.http.HttpServletRequest.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getInputStream")) { entered.countDown(); release.await(); }
                    return method.invoke(original, args);
                });
        var executor = Executors.newSingleThreadExecutor();
        try {
            var upload = executor.submit(() -> controller.history(waiting));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            headers.put("X-Import-Token", token(headers));
            assertEquals(200, controller.history(http("{}", headers, Map.of("action", "status"), "127.0.0.1")).getStatusCode().value());
            headers.put("X-Import-Token", token(headers));
            var cancelled = controller.history(http("{}", headers, Map.of("action", "cancel"), "127.0.0.1"));
            assertEquals("CANCELLED", ((PixivBatchDownloaderImportController.HistoryResponse)cancelled.getBody()).phase());
            release.countDown();
            assertEquals(409, upload.get(5, TimeUnit.SECONDS).getStatusCode().value());
            try (var paths = Files.walk(temporary.resolve("cache"))) { assertTrue(paths.noneMatch(Files::isRegularFile)); }
        } finally { release.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)); }
    }

    private String token(Map<String,String> headers) {
        return (String)((Map<?,?>)controller.token(http("", headers, Map.of(), "127.0.0.1")).getBody()).get("token");
    }
    private String ready(String records) throws Exception {
        String id = upload(records); history.inspect(id); await(id, view -> view.phase().equals("READY")); return id;
    }
    private String upload(String... records) throws Exception {
        String id = history.create(root.toString()).session();
        for (int file = 0; file < records.length; file++) history.upload(id, file, 0, records[file].getBytes(StandardCharsets.UTF_8));
        return id;
    }
    private BrowserHistoryImport.View await(String id, Predicate<BrowserHistoryImport.View> condition) {
        return assertTimeoutPreemptively(java.time.Duration.ofSeconds(10), () -> {
            while (true) { var view = history.status(id); if (condition.test(view)) return view; Thread.sleep(10); }
        });
    }
    private PluginDatabase database() throws Exception {
        Path file = temporary.resolve("stats.db");
        var source = new org.sqlite.SQLiteDataSource(); source.setUrl("jdbc:sqlite:" + file);
        var target = new top.sywyar.pixivdownload.databasesqlite.SqliteDatabasePlugin().resolve(
                new top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionSettings("", "", "", file));
        var schema = top.sywyar.pixivdownload.plugin.api.storage.relational.PrivateStorageSchema.merge(PixivBatchDownloaderImportStatistics.schema());
        try (var connection = source.getConnection()) { target.initializeStorage(connection, schema); }
        return (PluginDatabase)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PluginDatabase.class}, (proxy, method, args) -> {
            if (method.getName().equals("available")) return true;
            if (method.getName().equals("databaseId")) return "sqlite";
            try (var connection = source.getConnection(); var session = target.storageSession(connection, schema)) {
                connection.setAutoCommit(false);
                @SuppressWarnings("unchecked")
                var work = (top.sywyar.pixivdownload.plugin.api.storage.relational.DatabaseSession.Work<Object>)args[0];
                try { var value = work.run(session); connection.commit(); return value; }
                catch (Exception failure) { connection.rollback(); throw failure; }
            }
        });
    }
    private jakarta.servlet.http.HttpServletRequest http(String body, Map<String,String> headers, Map<String,String> params, String remote) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        var input = new java.io.ByteArrayInputStream(bytes);
        var stream = new jakarta.servlet.ServletInputStream() {
            public int read() { return input.read(); }
            public int read(byte[] b, int o, int n) { return input.read(b,o,n); }
            public boolean isFinished() { return input.available() == 0; }
            public boolean isReady() { return true; }
            public void setReadListener(jakarta.servlet.ReadListener listener) { throw new UnsupportedOperationException(); }
        };
        return (jakarta.servlet.http.HttpServletRequest)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{jakarta.servlet.http.HttpServletRequest.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getInputStream" -> stream;
                    case "getLocale" -> Locale.ENGLISH;
                    case "getContentLengthLong" -> (long)bytes.length;
                    case "getRemoteAddr" -> remote;
                    case "getHeader" -> headers.get((String)args[0]);
                    case "getParameter" -> params.get((String)args[0]);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
