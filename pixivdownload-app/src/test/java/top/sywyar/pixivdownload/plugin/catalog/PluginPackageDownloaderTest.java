package top.sywyar.pixivdownload.plugin.catalog;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * {@link PluginPackageDownloader} 单测：受信包 → 临时文件下载，包元数据完整性校验，流式上限中止 + 临时文件清理，URL 后缀
 * 推断，以及生产严格策略对 loopback 的拒绝。临时目录注入 {@code @TempDir} 以确定性断言「失败不留半成品」。
 */
@DisplayName("PluginPackageDownloader 受信包安全下载器")
class PluginPackageDownloaderTest {

    @TempDir
    Path tempDir;

    private final PluginCatalogHttpClient relaxed = new PluginCatalogHttpClient(false, true, 2000, 2000);
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("正常下载：返回临时文件、字节一致、后缀按 URL 推断为 .zip")
    void downloadsToTemp() throws Exception {
        server = CatalogTestSupport.startServer();
        byte[] body = CatalogTestSupport.explodedPluginZip("ext", "1.0.0", null);
        CatalogTestSupport.serveBytes(server, "/p.zip", body);
        PluginPackageDownloader downloader = downloader(relaxed);

        Path temp = downloader.downloadToTemp(pkg(
                CatalogTestSupport.loopbackUrl(server, "/p.zip"), (long) body.length,
                CatalogTestSupport.sha256Hex(body), null));

        assertThat(temp).exists();
        assertThat(temp.getFileName().toString()).endsWith(".zip");
        assertThat(Files.readAllBytes(temp)).isEqualTo(body);
    }

    @Test
    @DisplayName("packageUrl 首尾空白：trim 后正常下载、后缀仍按 .zip 推断（catalog 元数据带空白也安全）")
    void downloadsToTempTrimmingWhitespacePackageUrl() throws Exception {
        server = CatalogTestSupport.startServer();
        byte[] body = CatalogTestSupport.explodedPluginZip("ext", "1.0.0", null);
        CatalogTestSupport.serveBytes(server, "/p.zip", body);
        PluginPackageDownloader downloader = downloader(relaxed);

        Path temp = downloader.downloadToTemp(pkg(
                "  " + CatalogTestSupport.loopbackUrl(server, "/p.zip") + "  ", (long) body.length,
                CatalogTestSupport.sha256Hex(body), null));

        assertThat(temp).exists();
        assertThat(temp.getFileName().toString()).endsWith(".zip");
        assertThat(Files.readAllBytes(temp)).isEqualTo(body);
    }

    @Test
    @DisplayName("缺 url / 缺 sha256 / 缺或非正 expectedSize：INVALID_PACKAGE_METADATA，不建临时文件")
    void rejectsIncompleteMetadata() {
        PluginPackageDownloader downloader = downloader(relaxed);
        assertCode(downloader, pkg(null, 10L, "abc", null), PluginCatalogErrorCode.INVALID_PACKAGE_METADATA);
        assertCode(downloader, pkg("https://h.example/p.zip", null, "abc", null),
                PluginCatalogErrorCode.INVALID_PACKAGE_METADATA);
        assertCode(downloader, pkg("https://h.example/p.zip", 0L, "abc", null),
                PluginCatalogErrorCode.INVALID_PACKAGE_METADATA);
        assertCode(downloader, pkg("https://h.example/p.zip", 10L, "  ", null),
                PluginCatalogErrorCode.INVALID_PACKAGE_METADATA);
        assertThat(leftovers()).isEmpty();
    }

    @Test
    @DisplayName("URL 不以 .jar/.zip 结尾：INVALID_PACKAGE_METADATA，不建临时文件")
    void rejectsUnsupportedSuffix() {
        PluginPackageDownloader downloader = downloader(relaxed);
        assertCode(downloader, pkg("https://h.example/p.bin", 10L, "abc", null),
                PluginCatalogErrorCode.INVALID_PACKAGE_METADATA);
        assertThat(leftovers()).isEmpty();
    }

    @Test
    @DisplayName("声明体积超绝对上限：DOWNLOAD_TOO_LARGE，不建临时文件")
    void rejectsDeclaredSizeOverLimit() {
        PluginPackageDownloader downloader = new PluginPackageDownloader(relaxed, 100, tempDir);
        assertCode(downloader, pkg("https://h.example/p.zip", 1000L, "abc", null),
                PluginCatalogErrorCode.DOWNLOAD_TOO_LARGE);
        assertThat(leftovers()).isEmpty();
    }

    @Test
    @DisplayName("下载体积超过 expectedSize：中止抛 DOWNLOAD_TOO_LARGE，并清理临时文件（不留半成品）")
    void abortsAndCleansUpOnOversizeStream() {
        server = CatalogTestSupport.startServer();
        CatalogTestSupport.serveBytes(server, "/big.zip", new byte[4096]);
        PluginPackageDownloader downloader = downloader(relaxed);

        assertCode(downloader, pkg(CatalogTestSupport.loopbackUrl(server, "/big.zip"), 16L, "abc", null),
                PluginCatalogErrorCode.DOWNLOAD_TOO_LARGE);

        assertThat(leftovers()).as("超限下载必须清理临时文件").isEmpty();
    }

    @Test
    @DisplayName("生产严格策略：https loopback 下载地址被拒（BLOCKED_ADDRESS），校验阶段即拒、不连接不留半成品")
    void strictPolicyBlocksLoopback() {
        // 严格策略仅 https：用 https 的 loopback 字面量地址，scheme 校验通过后命中 IP 阻断（在任何连接之前）。
        PluginCatalogHttpClient strict = new PluginCatalogHttpClient(true, false, 2000, 2000);
        PluginPackageDownloader downloader = downloader(strict);

        assertCode(downloader, pkg("https://127.0.0.1:8443/p.zip", 3L, "abc", null),
                PluginCatalogErrorCode.BLOCKED_ADDRESS);
        assertThat(leftovers()).isEmpty();
    }

    private PluginPackageDownloader downloader(PluginCatalogHttpClient client) {
        return new PluginPackageDownloader(client, 100L * 1024 * 1024, tempDir);
    }

    private static PluginCatalogPackage pkg(String url, Long size, String sha256, String signature) {
        return new PluginCatalogPackage("1.0.0", url, size, sha256, signature, null, List.of());
    }

    private static void assertCode(PluginPackageDownloader downloader, PluginCatalogPackage pkg,
                                   PluginCatalogErrorCode expected) {
        PluginCatalogException ex = catchThrowableOfType(
                () -> downloader.downloadToTemp(pkg), PluginCatalogException.class);
        assertThat(ex).as("应抛出 PluginCatalogException").isNotNull();
        assertThat(ex.code()).isEqualTo(expected);
    }

    /** 临时目录里残留的下载临时文件（应在失败时被清理为空）。 */
    private List<Path> leftovers() {
        try (Stream<Path> stream = Files.list(tempDir)) {
            return stream.filter(p -> p.getFileName().toString().startsWith("plugin-catalog-")).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
