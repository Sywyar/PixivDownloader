package top.sywyar.pixivdownload.externalimport;

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
class ObservationImportTest {
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
        String description = ObservationImport.parse(input, ROOT).description();
        assertFalse(description.contains("<img"));
        assertFalse(description.contains("javascript:"));
        assertTrue(description.contains("text"));
    }
    @Test @DisplayName("四种作品沿用成功事件中的实际文件路径")
    void acceptsAllWorkTypes() throws Exception {
        for (int type = 0; type < 4; type++) {
            var result = ObservationImport.parse(input(type), ROOT);
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
            assertEquals(aiType == 2, ObservationImport.parse(input, ROOT).aiGenerated());
        }
        metadata.remove("aiType");
        assertThrows(IllegalArgumentException.class, () -> ObservationImport.parse(input, ROOT));
    }
    @Test @DisplayName("未确认成功、缺页、未知限制和越界文件全部拒绝")
    void rejectsUnconfirmedEvidence() throws Exception {
        var input = input(0);
        var file = (ObjectNode)input.path("files").get(0);
        file.put("outcome", "skipped");
        assertThrows(IllegalArgumentException.class, () -> ObservationImport.parse(input, ROOT));
        file.put("outcome", "success").put("noReply", true);
        assertThrows(IllegalArgumentException.class, () -> ObservationImport.parse(input, ROOT));
        file.put("noReply", false).put("path", "../escape.png");
        assertThrows(IllegalArgumentException.class, () -> ObservationImport.parse(input, ROOT));
        file.put("path", "custom.png");
        ((ObjectNode)input.path("metadata")).put("pageCount", 2);
        assertThrows(IllegalArgumentException.class, () -> ObservationImport.parse(input, ROOT));
        ((ObjectNode)input.path("metadata")).put("pageCount", 1).put("aiType", -1);
        assertThrows(IllegalArgumentException.class, () -> ObservationImport.parse(input, ROOT));
    }
    @Test @DisplayName("只贡献本机采集接口和一次性配置，没有手动导入页面或导航")
    void declaresAutomaticImport() {
        var feature = new ExternalImportPlugin().featurePlugin();
        assertTrue(feature.navigation().isEmpty()); assertTrue(feature.staticResources().isEmpty());
        assertTrue(feature.routes().stream().allMatch(route -> route.accessPolicy() == AccessPolicy.LOCAL));
        assertEquals("external-download-observer", feature.userscripts().get(0).id());
        assertEquals("pixiv-batch-downloader-import.source-root", feature.guiConfigContributions().get(0).fields().get(0).key());
    }
    @Test @DisplayName("单次令牌、本机来源、启用目录和严格 JSON 共同约束自动接收")
    void protectsLocalBridge() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var controller = new ExternalImportController(value -> { calls.incrementAndGet(); return true; },
                (namespace, locale, key) -> Optional.of("localized"), ROOT.toString(), () -> "solo");
        assertEquals(401, controller.importWork(http(input(0).toString(), null, "127.0.0.1")).getStatusCode().value());
        assertEquals(403, controller.token(http("", null, "192.0.2.1")).getStatusCode().value());
        String token = token(controller);
        assertEquals(200, controller.importWork(http(input(0).toString(), token, "127.0.0.1")).getStatusCode().value());
        assertEquals(401, controller.importWork(http(input(0).toString(), token, "127.0.0.1")).getStatusCode().value());
        for (String body : List.of("{\"a\":1,\"a\":2}", "{} {}", "[".repeat(21) + "]".repeat(21)))
            assertEquals(400, controller.importWork(http(body, token(controller), "127.0.0.1")).getStatusCode().value());
        assertEquals(413, controller.importWork(http(" ".repeat(12*1024*1024+1), token(controller), "127.0.0.1")).getStatusCode().value());
        assertEquals(1, calls.get());
        var disabled = new ExternalImportController(value -> true, (ns,l,k)->Optional.empty(), "", ()->"solo");
        assertEquals(503, disabled.token(http("",null,"127.0.0.1")).getStatusCode().value());
        var multi = new ExternalImportController(value -> true, (ns,l,k)->Optional.empty(), ROOT.toString(), ()->"multi");
        assertEquals(403, multi.token(http("",null,"127.0.0.1")).getStatusCode().value());
    }
    private String token(ExternalImportController controller) {
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
