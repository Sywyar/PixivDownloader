package top.sywyar.pixivdownload.plugin.catalog;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * {@link PluginCatalogService} 单测：未启用 → 空 / 拒绝，UTF-8 + Jackson 解析（成功 / 空 / 坏 JSON / 忽略未知字段），以及
 * 经 loopback 桩拉取清单（成功 / 不可用）。下载地址只来自注入的属性配置，不来自请求参数。
 */
@DisplayName("PluginCatalogService 受信目录读取")
class PluginCatalogServiceTest {

    private final PluginCatalogHttpClient relaxed = new PluginCatalogHttpClient(false, true, 2000, 2000);
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("未启用（enabled=false）：isEnabled=false，load() 抛 CATALOG_DISABLED")
    void disabledByFlag() {
        PluginCatalogService service = service(false, "https://example.com/c.json");
        assertThat(service.isEnabled()).isFalse();
        assertCode(service, PluginCatalogErrorCode.CATALOG_DISABLED);
    }

    @Test
    @DisplayName("启用但未配置 manifest-url：isEnabled=false，load() 抛 CATALOG_DISABLED")
    void enabledButNoUrl() {
        PluginCatalogService service = service(true, "");
        assertThat(service.isEnabled()).isFalse();
        assertCode(service, PluginCatalogErrorCode.CATALOG_DISABLED);
    }

    @Test
    @DisplayName("解析合法清单：取出条目与版本包，且忽略未知字段（author/tags/downloadCount 等）")
    void parsesValidManifestIgnoringUnknownFields() {
        PluginCatalogService service = service(false, "");
        String json = """
                {
                  "schemaVersion": "1",
                  "author": "ignored",
                  "entries": [
                    {
                      "pluginId": "stats",
                      "displayNameKey": "stats:nav.label",
                      "descriptionKey": "stats:plugin.summary",
                      "tags": ["ignored"],
                      "downloadCount": 999,
                      "packages": [
                        {
                          "version": "1.2.3",
                          "packageUrl": "https://example.com/stats-1.2.3.jar",
                          "expectedSizeBytes": 4096,
                          "sha256": "abcdef",
                          "requiredCoreApi": "1.0",
                          "rating": 4.5
                        }
                      ]
                    }
                  ]
                }
                """;

        PluginCatalogManifest manifest = service.parseManifest(json.getBytes(StandardCharsets.UTF_8));

        assertThat(manifest.entries()).hasSize(1);
        PluginCatalogEntry entry = manifest.entries().get(0);
        assertThat(entry.pluginId()).isEqualTo("stats");
        assertThat(entry.displayNameKey()).isEqualTo("stats:nav.label");
        assertThat(entry.packages()).hasSize(1);
        PluginCatalogPackage pkg = entry.packages().get(0);
        assertThat(pkg.version()).isEqualTo("1.2.3");
        assertThat(pkg.packageUrl()).isEqualTo("https://example.com/stats-1.2.3.jar");
        assertThat(pkg.expectedSizeBytes()).isEqualTo(4096L);
        assertThat(pkg.sha256()).isEqualTo("abcdef");
        assertThat(pkg.requiredCoreApi()).isEqualTo("1.0");
    }

    @Test
    @DisplayName("空字节 / 空白：解析为空清单（不报错）")
    void parsesEmptyAsEmptyManifest() {
        PluginCatalogService service = service(false, "");
        assertThat(service.parseManifest(new byte[0]).entries()).isEmpty();
        assertThat(service.parseManifest("   ".getBytes(StandardCharsets.UTF_8)).entries()).isEmpty();
    }

    @Test
    @DisplayName("坏 JSON：抛 CATALOG_UNAVAILABLE")
    void malformedJsonRejected() {
        PluginCatalogService service = service(false, "");
        PluginCatalogException ex = catchThrowableOfType(
                () -> service.parseManifest("{ not json ".getBytes(StandardCharsets.UTF_8)),
                PluginCatalogException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.code()).isEqualTo(PluginCatalogErrorCode.CATALOG_UNAVAILABLE);
    }

    @Test
    @DisplayName("启用 + 桩返回合法清单：load() 成功解析")
    void loadFetchesAndParses() {
        server = CatalogTestSupport.startServer();
        String json = "{\"entries\":[{\"pluginId\":\"demo\",\"packages\":[]}]}";
        CatalogTestSupport.serveBytes(server, "/catalog.json", json.getBytes(StandardCharsets.UTF_8));
        PluginCatalogService service = service(true, CatalogTestSupport.loopbackUrl(server, "/catalog.json"));

        PluginCatalogManifest manifest = service.load();

        assertThat(service.isEnabled()).isTrue();
        assertThat(manifest.findEntry("demo")).isPresent();
    }

    @Test
    @DisplayName("启用 + 桩返回坏 JSON：load() 抛 CATALOG_UNAVAILABLE")
    void loadMalformedIsUnavailable() {
        server = CatalogTestSupport.startServer();
        CatalogTestSupport.serveBytes(server, "/bad.json", "<<<".getBytes(StandardCharsets.UTF_8));
        PluginCatalogService service = service(true, CatalogTestSupport.loopbackUrl(server, "/bad.json"));

        assertCode(service, PluginCatalogErrorCode.CATALOG_UNAVAILABLE);
    }

    @Test
    @DisplayName("启用但目标不可达（连接失败）：load() 抛 CATALOG_UNAVAILABLE")
    void loadNetworkFailureIsUnavailable() {
        // 指向一个未监听的 loopback 端口：连接失败 → DOWNLOAD_FAILED → 归 CATALOG_UNAVAILABLE。
        PluginCatalogService service = service(true, "http://127.0.0.1:1/catalog.json");
        assertCode(service, PluginCatalogErrorCode.CATALOG_UNAVAILABLE);
    }

    @Test
    @DisplayName("manifest-url 首尾空白：trim 后正常拉取解析（绝不因 URI.create 抛异常而 500）")
    void loadTrimsSurroundingWhitespaceManifestUrl() {
        server = CatalogTestSupport.startServer();
        String json = "{\"entries\":[{\"pluginId\":\"demo\",\"packages\":[]}]}";
        CatalogTestSupport.serveBytes(server, "/catalog.json", json.getBytes(StandardCharsets.UTF_8));
        PluginCatalogService service = service(true,
                "  " + CatalogTestSupport.loopbackUrl(server, "/catalog.json") + "  ");

        PluginCatalogManifest manifest = service.load();

        assertThat(service.isEnabled()).isTrue();
        assertThat(manifest.findEntry("demo")).isPresent();
    }

    @Test
    @DisplayName("manifest-url 为坏 URI（含非法空格）：稳定归 CATALOG_UNAVAILABLE，不产生 500")
    void loadMalformedManifestUrlIsUnavailable() {
        // 配置成坏 URI（内含非法空格）：拉取阶段 INSECURE_URL → load() 统一归 CATALOG_UNAVAILABLE，绝不逃逸为 500。
        PluginCatalogService service = service(true, "ht tp://example.com/c.json");
        assertThat(service.isEnabled()).isTrue();
        assertCode(service, PluginCatalogErrorCode.CATALOG_UNAVAILABLE);
    }

    private PluginCatalogService service(boolean enabled, String manifestUrl) {
        PluginCatalogProperties props = new PluginCatalogProperties();
        props.setEnabled(enabled);
        props.setManifestUrl(manifestUrl);
        return new PluginCatalogService(props, relaxed);
    }

    private static void assertCode(PluginCatalogService service, PluginCatalogErrorCode expected) {
        PluginCatalogException ex = catchThrowableOfType(service::load, PluginCatalogException.class);
        assertThat(ex).as("应抛出 PluginCatalogException").isNotNull();
        assertThat(ex.code()).isEqualTo(expected);
    }
}
