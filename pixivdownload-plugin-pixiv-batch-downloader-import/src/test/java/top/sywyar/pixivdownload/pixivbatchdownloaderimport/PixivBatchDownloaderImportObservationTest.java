package top.sywyar.pixivdownload.pixivbatchdownloaderimport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("自动采集接收、配置与本机认证")
class PixivBatchDownloaderImportObservationTest {
    @org.junit.jupiter.api.io.TempDir Path temporary;

    private PixivBatchDownloaderImportDirectory directory(String initial) throws java.io.IOException {
        Path config = temporary.resolve("settings.properties");
        if (!initial.isBlank()) {
            Properties values = new Properties();
            values.setProperty(PixivBatchDownloaderImportDirectory.KEY, initial);
            try (var writer = java.nio.file.Files.newBufferedWriter(config, java.nio.charset.StandardCharsets.UTF_8)) {
                values.store(writer, null);
            }
        }
        return new PixivBatchDownloaderImportDirectory(config);
    }

    private PixivBatchDownloaderImportStatistics statistics() throws Exception {
        return new PixivBatchDownloaderImportStatistics(dataSource(temporary.resolve("plugin.db")));
    }

    private top.sywyar.pixivdownload.plugin.api.storage.PluginDataSource dataSource(Path file) {
        var source = new org.sqlite.SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + file);
        return (top.sywyar.pixivdownload.plugin.api.storage.PluginDataSource) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{top.sywyar.pixivdownload.plugin.api.storage.PluginDataSource.class},
                (proxy, method, args) -> {
                    try { return method.invoke(source, args); }
                    catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                });
    }

    @Test @DisplayName("累计导入按作品身份去重，重启后保留，已有作品和失败不计数")
    void contributesPersistentUniqueImportCount() throws Exception {
        var statistics = statistics();
        var feature = new PixivBatchDownloaderImportPlugin().featurePlugin();
        var initial = statistics.snapshot().cards().get(0);
        assertEquals("0", initial.primaryValue().fallback());
        assertEquals(feature.i18n().get(0).namespace(), initial.title().namespace());
        assertEquals("overview.importedWorks", initial.title().key());
        var written = new HashSet<String>();
        var controller = new PixivBatchDownloaderImportController(value -> {
            if (value.workId() == 99) throw new java.io.IOException("unavailable");
            if (value.workId() == 84) return false;
            return written.add(value.workType() + ":" + value.workId());
        }, (ns, locale, key) -> Optional.empty(), directory(ROOT.toString()), () -> "solo", statistics);

        // 插画与小说可使用相同数值 ID；漫画页数不构成新的作品身份。
        for (int type : List.of(0, 0, 3))
            assertEquals(200, controller.importWork(http(input(type).toString(), token(controller), "127.0.0.1")).getStatusCode().value());
        assertEquals("2", statistics.snapshot().cards().get(0).primaryValue().fallback());
        var existing = input(0);
        ((ObjectNode) existing.path("metadata")).put("id", "84");
        ((ObjectNode) existing.path("files").get(0)).put("fileId", "84_p0");
        assertEquals(200, controller.importWork(http(existing.toString(), token(controller), "127.0.0.1")).getStatusCode().value());
        ((ObjectNode) existing.path("metadata")).put("id", "99");
        ((ObjectNode) existing.path("files").get(0)).put("fileId", "99_p0");
        assertEquals(503, controller.importWork(http(existing.toString(), token(controller), "127.0.0.1")).getStatusCode().value());
        // 模拟删除后重新创建同一作品：累计唯一作品数仍不增加。
        written.clear();
        assertEquals(200, controller.importWork(http(input(0).toString(), token(controller), "127.0.0.1")).getStatusCode().value());
        assertEquals("2", statistics().snapshot().cards().get(0).primaryValue().fallback());
    }

    @Test @DisplayName("导入成功后统计暂不可写时保留待计数身份，重试不重复导入或漏记")
    void retriesStatisticsBeforeAcknowledgingExistingWork() throws Exception {
        var source = dataSource(temporary.resolve("retry.db"));
        var statistics = new PixivBatchDownloaderImportStatistics(source);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var controller = new PixivBatchDownloaderImportController(value -> calls.incrementAndGet() == 1,
                (ns, locale, key) -> Optional.empty(), directory(ROOT.toString()), () -> "solo", statistics);
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TRIGGER unavailable BEFORE INSERT ON imported_works BEGIN SELECT RAISE(ABORT, 'unavailable'); END");
            assertEquals(503, controller.importWork(http(input(0).toString(), token(controller), "127.0.0.1")).getStatusCode().value());
            assertThrows(IllegalStateException.class, statistics::snapshot);
            assertEquals(503, controller.importWork(http(input(0).toString(), token(controller), "127.0.0.1")).getStatusCode().value());
            assertEquals(1, calls.get());
            statement.executeUpdate("DROP TRIGGER unavailable");
        }
        assertEquals(200, controller.importWork(http(input(0).toString(), token(controller), "127.0.0.1")).getStatusCode().value());
        assertEquals("1", statistics.snapshot().cards().get(0).primaryValue().fallback());
    }

    @Test @DisplayName("统计查询失败向宿主报告不可用，不伪造零值")
    void doesNotReportZeroOnStorageFailure() throws Exception {
        var source = dataSource(temporary.resolve("failed.db"));
        var statistics = new PixivBatchDownloaderImportStatistics(source);
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE imported_works");
        }
        assertThrows(IllegalStateException.class, statistics::snapshot);
    }
    private static final Path ROOT = Path.of(".").toAbsolutePath().normalize();
    private ObjectNode input(int type) throws Exception {
        ObjectNode input = (ObjectNode) new ObjectMapper().readTree("""
                {"schemaVersion":1,"source":"pixiv-batch-downloader","taskBatch":1,"tabId":2,
                "metadata":{"id":"42","type":0,"title":"Title","authorId":"84","pageCount":1,
                "restriction":0,"aiType":1,"tags":["tag"],"novelContent":"Novel content"},
                "files":[{"fileId":"42_p0","page":0,"outcome":"success","path":"custom.png"}]}
                """);
        ((ObjectNode)input.path("metadata")).put("type", type);
        if (type >= 2) ((ObjectNode)input.path("files").get(0)).put("fileId", "42");
        return input;
    }
    @Test @DisplayName("导入简介沿用公开的 Pixiv HTML 清理规则")
    void sanitizesDescription() throws Exception {
        var input = input(0);
        ((ObjectNode)input.path("metadata")).put("description", "<img src=x onerror=alert(1)><a href='javascript:alert(1)'>link</a><br>text");
        String description = PixivBatchDownloaderImportObservation.parse(input, ROOT).description();
        assertFalse(description.contains("<img"));
        assertFalse(description.contains("javascript:"));
        assertTrue(description.contains("text"));
    }
    @Test @DisplayName("四种作品沿用成功事件中的实际文件路径")
    void acceptsAllWorkTypes() throws Exception {
        for (int type = 0; type < 4; type++) {
            var result = PixivBatchDownloaderImportObservation.parse(input(type), ROOT);
            assertEquals(type == 3 ? WorkType.NOVEL : WorkType.ARTWORK, result.workType());
            assertEquals(ROOT.resolve("custom.png"), result.pageFiles().get(0));
        }
    }
    @Test @DisplayName("未标注 AI 的历史作品可导入，缺失和非法标记仍拒绝")
    void acceptsUnlabelledAiStatus() throws Exception {
        var input = input(2);
        var metadata = (ObjectNode) input.path("metadata");
        for (int aiType = 0; aiType <= 2; aiType++) {
            metadata.put("aiType", aiType);
            assertEquals(aiType == 2, PixivBatchDownloaderImportObservation.parse(input, ROOT).aiGenerated());
        }
        metadata.remove("aiType");
        assertThrows(IllegalArgumentException.class, () -> PixivBatchDownloaderImportObservation.parse(input, ROOT));
    }
    @Test @DisplayName("未确认成功、缺页、未知限制和越界文件全部拒绝")
    void rejectsUnconfirmedEvidence() throws Exception {
        var input = input(0);
        var file = (ObjectNode)input.path("files").get(0);
        file.put("outcome", "skipped");
        assertThrows(IllegalArgumentException.class, () -> PixivBatchDownloaderImportObservation.parse(input, ROOT));
        file.put("outcome", "success").put("noReply", true);
        assertThrows(IllegalArgumentException.class, () -> PixivBatchDownloaderImportObservation.parse(input, ROOT));
        file.put("noReply", false).put("path", "../escape.png");
        assertThrows(IllegalArgumentException.class, () -> PixivBatchDownloaderImportObservation.parse(input, ROOT));
        file.put("path", "custom.png");
        ((ObjectNode)input.path("metadata")).put("pageCount", 2);
        assertThrows(IllegalArgumentException.class, () -> PixivBatchDownloaderImportObservation.parse(input, ROOT));
        ((ObjectNode)input.path("metadata")).put("pageCount", 1).put("aiType", -1);
        assertThrows(IllegalArgumentException.class, () -> PixivBatchDownloaderImportObservation.parse(input, ROOT));
    }
    @Test @DisplayName("只贡献本机采集接口和一次性配置，没有手动导入页面或导航")
    void declaresAutomaticImport() {
        var feature = new PixivBatchDownloaderImportPlugin().featurePlugin();
        assertTrue(feature.navigation().isEmpty()); assertTrue(feature.staticResources().isEmpty());
        assertTrue(feature.routes().stream().allMatch(route -> route.accessPolicy() == AccessPolicy.LOCAL));
        assertEquals("pixiv-batch-downloader-import", feature.userscripts().get(0).id());
        assertEquals("pixiv-batch-downloader-import.source-root", feature.guiConfigContributions().get(0).fields().get(0).key());
    }
    @Test @DisplayName("单次令牌、本机来源、启用目录和严格 JSON 共同约束自动接收")
    void protectsLocalBridge() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var controller = new PixivBatchDownloaderImportController(value -> { calls.incrementAndGet(); return true; },
                (namespace, locale, key) -> Optional.of("localized"), directory(ROOT.toString()), () -> "solo", statistics());
        assertEquals(401, controller.importWork(http(input(0).toString(), null, "127.0.0.1")).getStatusCode().value());
        assertEquals(403, controller.token(http("", null, "192.0.2.1")).getStatusCode().value());
        String token = token(controller);
        assertEquals(200, controller.importWork(http(input(0).toString(), token, "127.0.0.1")).getStatusCode().value());
        assertEquals(401, controller.importWork(http(input(0).toString(), token, "127.0.0.1")).getStatusCode().value());
        for (String body : List.of("{\"a\":1,\"a\":2}", "{} {}", "[".repeat(21) + "]".repeat(21)))
            assertEquals(400, controller.importWork(http(body, token(controller), "127.0.0.1")).getStatusCode().value());
        assertEquals(413, controller.importWork(http(" ".repeat(12*1024*1024+1), token(controller), "127.0.0.1")).getStatusCode().value());
        assertEquals(1, calls.get());
        java.nio.file.Files.delete(temporary.resolve("settings.properties"));
        var disabled = new PixivBatchDownloaderImportController(value -> true, (ns,l,k)->Optional.empty(), directory(""), ()->"solo", statistics());
        assertEquals(200, disabled.token(http("",null,"127.0.0.1")).getStatusCode().value());
        assertEquals(503, disabled.importWork(http(input(0).toString(), token(disabled), "127.0.0.1")).getStatusCode().value());
        var multi = new PixivBatchDownloaderImportController(value -> true, (ns,l,k)->Optional.empty(), directory(ROOT.toString()), ()->"multi", statistics());
        assertEquals(403, multi.token(http("",null,"127.0.0.1")).getStatusCode().value());
    }

    @Test @DisplayName("首次完整绝对路径只产生候选，确认前不导入，保存后自动重试无需重启")
    void waitsForConfirmedDirectoryAndReadsSavedValue() throws Exception {
        var directory = directory("");
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var controller = new PixivBatchDownloaderImportController(value -> {
            assertEquals(temporary, value.sourceRoot());
            calls.incrementAndGet(); return true;
        }, (ns,l,k) -> Optional.empty(), directory, () -> "solo", statistics());
        var observation = input(0);
        assertEquals(503, controller.importWork(http(observation.toString(), token(controller), "127.0.0.1")).getStatusCode().value());
        assertTrue(directory.directorySuggestion().isEmpty());
        ((ObjectNode) observation.path("files").get(0)).put("path", temporary.resolve("art/custom.png").toString());
        assertEquals(503, controller.importWork(http(observation.toString(), token(controller), "127.0.0.1")).getStatusCode().value());
        var candidate = directory.directorySuggestion().orElseThrow();
        assertEquals(temporary.resolve("art").toString(), candidate.directory());
        assertEquals(0, calls.get());
        assertFalse(java.nio.file.Files.exists(temporary.resolve("settings.properties")));
        assertEquals(503, controller.importWork(http(observation.toString(), token(controller), "127.0.0.1")).getStatusCode().value());
        assertEquals(candidate, directory.directorySuggestion().orElseThrow());
        Properties settings = new Properties();
        settings.setProperty(PixivBatchDownloaderImportDirectory.KEY, temporary.toString());
        try (var writer = java.nio.file.Files.newBufferedWriter(temporary.resolve("settings.properties"), java.nio.charset.StandardCharsets.UTF_8)) {
            settings.store(writer, null);
        }
        assertTrue(directory.directorySuggestion().isEmpty());
        assertEquals(200, controller.importWork(http(observation.toString(), token(controller), "127.0.0.1")).getStatusCode().value());
        assertEquals(1, calls.get());
    }
    private String token(PixivBatchDownloaderImportController controller) {
        return (String)((Map<?,?>)controller.token(http("",null,"127.0.0.1")).getBody()).get("token");
    }
    private jakarta.servlet.http.HttpServletRequest http(String body, String token, String remote) {
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var input = new java.io.ByteArrayInputStream(bytes);
        var stream = new jakarta.servlet.ServletInputStream() {
            @Override public int read() { return input.read(); }
            @Override public int read(byte[] b, int o, int n) { return input.read(b,o,n); }
            @Override public boolean isFinished() { return input.available()==0; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener(jakarta.servlet.ReadListener listener) { throw new UnsupportedOperationException(); }
        };
        return (jakarta.servlet.http.HttpServletRequest)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{jakarta.servlet.http.HttpServletRequest.class}, (proxy,method,args)->switch(method.getName()) {
                    case "getInputStream" -> stream;
                    case "getLocale" -> Locale.ENGLISH;
                    case "getContentLengthLong" -> (long)bytes.length;
                    case "getRemoteAddr" -> remote;
                    case "getHeader" -> switch((String)args[0]) {
                        case "Host" -> "localhost:6999";
                        case "X-Pixiv-Collector" -> "1";
                        case "X-Import-Token" -> token;
                        default -> null;
                    };
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
