package top.sywyar.pixivdownload.plugin.catalog.content;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogHttpClient;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogService;
import top.sywyar.pixivdownload.plugin.catalog.error.PluginCatalogException;
import top.sywyar.pixivdownload.plugin.catalog.manifest.PluginCatalogPackage;
import top.sywyar.pixivdownload.plugin.catalog.repository.PluginCatalogClientProvider;
import top.sywyar.pixivdownload.plugin.catalog.repository.PluginRepository;
import top.sywyar.pixivdownload.sdk.community.content.MarketContent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PluginCatalogContentServiceTest {
    private final PluginCatalogService catalog = mock(PluginCatalogService.class);
    private final PluginCatalogClientProvider clients = mock(PluginCatalogClientProvider.class);
    private final PluginCatalogHttpClient http = mock(PluginCatalogHttpClient.class);
    private final PluginRepository repository = mock(PluginRepository.class);

    @Test @DisplayName("按当前目录读取附件，复用已验证字节且关闭后释放访问")
    void verifiedReuse() throws Exception {
        byte[] bytes = "<h1>说明</h1><script>alert(1)</script><img src='missing.png'><a href='docs/usage.md'>Guide</a>".getBytes(StandardCharsets.UTF_8);
        var asset = fixture(bytes);
        try (var service = new PluginCatalogContentService(catalog, clients)) {
            var result = service.document("repo", "plugin", "3.2.4", "readme", "en", asset.sha256());
            assertThat(result.html()).contains("<h1>说明</h1>").doesNotContain("script", "alert(1)", "src=");
            assertThat(result.missingResources()).isTrue();
            assertThat(result.html()).contains("https://github.com/example/plugin/blob/" + "a".repeat(40) + "/docs/usage.md");
            assertThat(service.document("repo", "plugin", "3.2.4", "readme", "en", asset.sha256())).isEqualTo(result);
            verify(http).fetchBytes(asset.url(), asset.size());
            verify(catalog, times(2)).resolvePackage("repo", "plugin", "3.2.4");
            service.close();
            assertThatThrownBy(() -> service.document("repo", "plugin", "3.2.4", "readme", "en", asset.sha256()))
                    .isInstanceOf(PluginCatalogException.class);
        }
    }

    @Test @DisplayName("附件篡改与客户端旧摘要不能命中缓存或触发任意下载")
    void integrityAndIdentity() throws Exception {
        byte[] bytes = "<h1>Original</h1>".getBytes(StandardCharsets.UTF_8);
        var asset = fixture(bytes);
        try (var service = new PluginCatalogContentService(catalog, clients)) {
            assertThatThrownBy(() -> service.document("repo", "plugin", "3.2.4", "readme", "en", "0".repeat(64)))
                    .isInstanceOf(PluginCatalogException.class);
            verifyNoInteractions(http);
            when(http.fetchBytes(asset.url(), asset.size())).thenReturn("<h1>Modified</h1>".getBytes(StandardCharsets.UTF_8));
            assertThatThrownBy(() -> service.document("repo", "plugin", "3.2.4", "readme", "en", asset.sha256()))
                    .isInstanceOf(PluginCatalogException.class);
            when(http.fetchBytes(asset.url(), asset.size())).thenReturn(bytes);
            assertThat(service.document("repo", "plugin", "3.2.4", "readme", "en", asset.sha256()).html())
                    .contains("Original");
            when(catalog.resolvePackage("repo", "plugin", "3.2.4"))
                    .thenThrow(new PluginCatalogException(top.sywyar.pixivdownload.plugin.catalog.error.PluginCatalogErrorCode.REPOSITORY_DISABLED, "disabled"));
            assertThatThrownBy(() -> service.document("repo", "plugin", "3.2.4", "readme", "en", asset.sha256()))
                    .isInstanceOf(PluginCatalogException.class);
            verify(http, times(2)).fetchBytes(asset.url(), asset.size());
        }
    }

    @Test @DisplayName("并发满额立即拒绝额外读取，完成后恢复读取且不重复下载缓存")
    void boundedConcurrentReads() throws Exception {
        byte[] bytes = "<h1>Concurrent</h1>".getBytes(StandardCharsets.UTF_8);
        var asset = fixture(bytes);
        var entered = new CountDownLatch(4);
        var release = new CountDownLatch(1);
        when(http.fetchBytes(asset.url(), asset.size())).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("read was not released");
            return bytes;
        });
        var executor = Executors.newFixedThreadPool(4);
        try (var service = new PluginCatalogContentService(catalog, clients)) {
            var pending = new ArrayList<Future<PluginCatalogContentService.DocumentView>>();
            for (int i = 0; i < 4; i++) pending.add(executor.submit(() ->
                    service.document("repo", "plugin", "3.2.4", "readme", "en", asset.sha256())));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> service.document("repo", "plugin", "3.2.4", "readme", "en", asset.sha256()))
                    .isInstanceOf(PluginCatalogException.class);
            release.countDown();
            for (var result : pending) assertThat(result.get(10, TimeUnit.SECONDS).html()).contains("Concurrent");
            assertThat(service.document("repo", "plugin", "3.2.4", "readme", "en", asset.sha256()).html()).contains("Concurrent");
            verify(http, times(4)).fetchBytes(asset.url(), asset.size());
        } finally {
            release.countDown(); executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private MarketContent.Asset fixture(byte[] bytes) throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        var asset = new MarketContent.Asset("content-" + hash + ".html", "https://example.test/docs.html", "text/html", bytes.length, hash);
        var content = new MarketContent(Map.of("en", new MarketContent.Document("html", asset, "README.html", null,
                "https://github.com/example/plugin/blob/" + "a".repeat(40) + "/README.html")), null, null);
        var pkg = mock(PluginCatalogPackage.class);
        when(pkg.content()).thenReturn(content);
        when(catalog.resolvePackage("repo", "plugin", "3.2.4"))
                .thenReturn(new PluginCatalogService.ResolvedPackage(repository, null, pkg));
        when(clients.clientFor(repository)).thenReturn(http);
        when(http.fetchBytes(asset.url(), asset.size())).thenReturn(bytes);
        return asset;
    }
}
