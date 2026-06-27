package top.sywyar.pixivdownload.plugin.catalog;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * {@link PluginCatalogHttpClient} 单测：URL scheme / 主机 / 解析 IP 的 SSRF 校验（生产严格策略），地址分类，以及对接
 * loopback HTTP 桩的真实下载（流式上限、禁用重定向、非 200）。SSRF 阻断用例一律用 IP 字面量、不触发 DNS、不连真实网络。
 */
@DisplayName("PluginCatalogHttpClient 受信下载 SSRF 安全客户端")
class PluginCatalogHttpClientTest {

    /** 生产策略：仅 https、拒绝非公网地址。 */
    private final PluginCatalogHttpClient strict = new PluginCatalogHttpClient(true, false, 2000, 2000);

    @Nested
    @DisplayName("URL / scheme 校验")
    class UrlValidation {

        @Test
        @DisplayName("https + 公网 IP 字面量：通过校验")
        void allowsHttpsPublicAddress() {
            // 8.8.8.8 是公网地址、字面量解析不触发 DNS、不发起连接（仅校验）。
            strict.verifyUrlAllowed("https://8.8.8.8/plugins/x.jar");
        }

        @Test
        @DisplayName("校验通过后返回规范化（trim 后）的 URI——发请求用的就是这一个值")
        void returnsTrimmedNormalizedUri() {
            // 首尾空白在此 trim 掉；返回的 URI 即后续 send() 实际使用的值，杜绝「校验 trim 后、发请求用原始串」的不一致。
            URI uri = strict.verifyUrlAllowed("  https://8.8.8.8/plugins/x.jar  ");
            assertThat(uri).isEqualTo(URI.create("https://8.8.8.8/plugins/x.jar"));
        }

        @Test
        @DisplayName("http:// 在仅 https 策略下被拒（INSECURE_URL）")
        void rejectsHttpWhenHttpsOnly() {
            assertCode("http://8.8.8.8/x.jar", PluginCatalogErrorCode.INSECURE_URL);
        }

        @Test
        @DisplayName("file / jar / ftp / 其它 scheme 一律被拒（INSECURE_URL）")
        void rejectsNonHttpsSchemes() {
            assertCode("file:///etc/passwd", PluginCatalogErrorCode.INSECURE_URL);
            assertCode("jar:https://example.com/a.jar!/x", PluginCatalogErrorCode.INSECURE_URL);
            assertCode("ftp://example.com/a.jar", PluginCatalogErrorCode.INSECURE_URL);
            assertCode("gopher://example.com/", PluginCatalogErrorCode.INSECURE_URL);
        }

        @Test
        @DisplayName("空 / 畸形 URL / 缺主机：被拒（INSECURE_URL）")
        void rejectsMalformed() {
            assertCode(null, PluginCatalogErrorCode.INSECURE_URL);
            assertCode("   ", PluginCatalogErrorCode.INSECURE_URL);
            assertCode("https://", PluginCatalogErrorCode.INSECURE_URL);
            assertCode("not a url", PluginCatalogErrorCode.INSECURE_URL);
        }

        @Test
        @DisplayName("https 但解析到非公网地址：被拒（BLOCKED_ADDRESS）——loopback / 私网 / link-local / 组播 / 未指定 / CGNAT / IPv6 回环")
        void rejectsNonPublicAddresses() {
            assertCode("https://127.0.0.1/x.jar", PluginCatalogErrorCode.BLOCKED_ADDRESS);
            assertCode("https://10.0.0.5/x.jar", PluginCatalogErrorCode.BLOCKED_ADDRESS);
            assertCode("https://192.168.1.1/x.jar", PluginCatalogErrorCode.BLOCKED_ADDRESS);
            assertCode("https://172.16.0.1/x.jar", PluginCatalogErrorCode.BLOCKED_ADDRESS);
            assertCode("https://169.254.1.1/x.jar", PluginCatalogErrorCode.BLOCKED_ADDRESS);
            assertCode("https://224.0.0.1/x.jar", PluginCatalogErrorCode.BLOCKED_ADDRESS);
            assertCode("https://0.0.0.0/x.jar", PluginCatalogErrorCode.BLOCKED_ADDRESS);
            assertCode("https://100.64.0.1/x.jar", PluginCatalogErrorCode.BLOCKED_ADDRESS);
            assertCode("https://[::1]/x.jar", PluginCatalogErrorCode.BLOCKED_ADDRESS);
        }

        private void assertCode(String url, PluginCatalogErrorCode expected) {
            PluginCatalogException ex = catchThrowableOfType(
                    () -> strict.verifyUrlAllowed(url), PluginCatalogException.class);
            assertThat(ex).as("应抛出 PluginCatalogException for %s", url).isNotNull();
            assertThat(ex.code()).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("地址分类 isBlockedAddress")
    class AddressClassification {

        @Test
        @DisplayName("严格策略：各类非公网地址被判阻断，公网地址放行")
        void strictPolicy() throws Exception {
            assertThat(blocked("127.0.0.1")).isTrue();
            assertThat(blocked("::1")).isTrue();
            assertThat(blocked("10.1.2.3")).isTrue();
            assertThat(blocked("192.168.0.1")).isTrue();
            assertThat(blocked("169.254.10.10")).isTrue();
            assertThat(blocked("224.0.0.1")).isTrue();
            assertThat(blocked("0.0.0.0")).isTrue();
            assertThat(blocked("100.64.0.1")).isTrue();
            assertThat(blocked("fc00::1")).isTrue();
            assertThat(blocked("8.8.8.8")).isFalse();
            assertThat(blocked("1.1.1.1")).isFalse();
        }

        @Test
        @DisplayName("放开策略（仅测试用）：loopback 等一律放行")
        void permissivePolicy() throws Exception {
            assertThat(PluginCatalogHttpClient.isBlockedAddress(
                    InetAddress.getByName("127.0.0.1"), true)).isFalse();
            assertThat(PluginCatalogHttpClient.isBlockedAddress(
                    InetAddress.getByName("10.0.0.1"), true)).isFalse();
        }

        private boolean blocked(String literal) throws Exception {
            return PluginCatalogHttpClient.isBlockedAddress(InetAddress.getByName(literal), false);
        }
    }

    @Nested
    @DisplayName("真实下载（loopback HTTP 桩，放开非公网地址 + 允许 http）")
    class RealDownload {

        private final PluginCatalogHttpClient relaxed = new PluginCatalogHttpClient(false, true, 2000, 2000);
        private HttpServer server;

        @AfterEach
        void tearDown() {
            if (server != null) {
                server.stop(0);
            }
        }

        @Test
        @DisplayName("fetchBytes：200 正常返回全部字节")
        void fetchBytesOk() {
            server = CatalogTestSupport.startServer();
            byte[] body = "hello-catalog".getBytes(StandardCharsets.UTF_8);
            CatalogTestSupport.serveBytes(server, "/manifest.json", body);

            byte[] got = relaxed.fetchBytes(CatalogTestSupport.loopbackUrl(server, "/manifest.json"), 1024);

            assertThat(got).isEqualTo(body);
        }

        @Test
        @DisplayName("streamToFile：200 正常落盘全部字节")
        void streamToFileOk(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
            server = CatalogTestSupport.startServer();
            byte[] body = new byte[4096];
            for (int i = 0; i < body.length; i++) {
                body[i] = (byte) i;
            }
            CatalogTestSupport.serveBytes(server, "/pkg.zip", body);
            Path target = dir.resolve("out.zip");

            long written = relaxed.streamToFile(CatalogTestSupport.loopbackUrl(server, "/pkg.zip"), 1L << 20, target);

            assertThat(written).isEqualTo(body.length);
            assertThat(Files.readAllBytes(target)).isEqualTo(body);
        }

        @Test
        @DisplayName("fetchBytes URL 首尾空白：trim 后正常下载（绝不因 URI.create 抛 IllegalArgumentException 而 500）")
        void fetchBytesTrimsSurroundingWhitespace() {
            server = CatalogTestSupport.startServer();
            byte[] body = "trimmed-ok".getBytes(StandardCharsets.UTF_8);
            CatalogTestSupport.serveBytes(server, "/manifest.json", body);

            // 旧实现 verifyUrlAllowed trim 校验、send 却用原始串 URI.create → 首尾空白即抛 IllegalArgumentException → 500。
            byte[] got = relaxed.fetchBytes(
                    "  " + CatalogTestSupport.loopbackUrl(server, "/manifest.json") + "  ", 1024);

            assertThat(got).isEqualTo(body);
        }

        @Test
        @DisplayName("streamToFile URL 首尾空白（含制表符）：trim 后正常落盘（packageUrl 带空白也安全）")
        void streamToFileTrimsSurroundingWhitespace(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
            server = CatalogTestSupport.startServer();
            byte[] body = "pkg-bytes".getBytes(StandardCharsets.UTF_8);
            CatalogTestSupport.serveBytes(server, "/pkg.zip", body);
            Path target = dir.resolve("out.zip");

            long written = relaxed.streamToFile(
                    "\t" + CatalogTestSupport.loopbackUrl(server, "/pkg.zip") + " ", 1L << 20, target);

            assertThat(written).isEqualTo(body.length);
            assertThat(Files.readAllBytes(target)).isEqualTo(body);
        }

        @Test
        @DisplayName("超过最大字节数上限：中止并抛 DOWNLOAD_TOO_LARGE")
        void fetchBytesTooLarge() {
            server = CatalogTestSupport.startServer();
            CatalogTestSupport.serveBytes(server, "/big", new byte[2048]);

            assertThatThrownBy(() ->
                    relaxed.fetchBytes(CatalogTestSupport.loopbackUrl(server, "/big"), 1024))
                    .isInstanceOf(PluginCatalogException.class)
                    .extracting(e -> ((PluginCatalogException) e).code())
                    .isEqualTo(PluginCatalogErrorCode.DOWNLOAD_TOO_LARGE);
        }

        @Test
        @DisplayName("3xx 重定向：禁用跟随，抛 DOWNLOAD_FAILED（绝不连二次地址）")
        void redirectNotFollowed() {
            server = CatalogTestSupport.startServer();
            CatalogTestSupport.serveRedirect(server, "/redir", "https://10.0.0.1/secret");

            assertThatThrownBy(() ->
                    relaxed.fetchBytes(CatalogTestSupport.loopbackUrl(server, "/redir"), 1024))
                    .isInstanceOf(PluginCatalogException.class)
                    .extracting(e -> ((PluginCatalogException) e).code())
                    .isEqualTo(PluginCatalogErrorCode.DOWNLOAD_FAILED);
        }

        @Test
        @DisplayName("非 200（404）：抛 DOWNLOAD_FAILED")
        void nonOkStatus() {
            server = CatalogTestSupport.startServer();
            CatalogTestSupport.serveStatus(server, "/missing", 404);

            assertThatThrownBy(() ->
                    relaxed.fetchBytes(CatalogTestSupport.loopbackUrl(server, "/missing"), 1024))
                    .isInstanceOf(PluginCatalogException.class)
                    .extracting(e -> ((PluginCatalogException) e).code())
                    .isEqualTo(PluginCatalogErrorCode.DOWNLOAD_FAILED);
        }
    }

    @Nested
    @DisplayName("受信白名单重定向 + 代理档跳过本地 SSRF（proxy-trusted）")
    class TrustedRedirectAndProxied {

        private HttpServer server;

        @AfterEach
        void tearDown() {
            if (server != null) {
                server.stop(0);
            }
        }

        /** 白名单可含 loopback 主机的放开客户端（http + 非公网放开，未代理），用于在 loopback 桩上验证「按白名单跟随一跳」。 */
        private PluginCatalogHttpClient allowlisted(String... domains) {
            return new PluginCatalogHttpClient(false, true, 2000, 2000, null, Set.of(domains));
        }

        @Test
        @DisplayName("跟随一跳白名单内重定向：取到最终 200 字节")
        void followsSingleAllowlistedRedirect() {
            server = CatalogTestSupport.startServer();
            byte[] body = "after-redirect".getBytes(StandardCharsets.UTF_8);
            CatalogTestSupport.serveBytes(server, "/final", body);
            CatalogTestSupport.serveRedirect(server, "/redir", CatalogTestSupport.loopbackUrl(server, "/final"));

            byte[] got = allowlisted("127.0.0.1").fetchBytes(CatalogTestSupport.loopbackUrl(server, "/redir"), 1024);

            assertThat(got).isEqualTo(body);
        }

        @Test
        @DisplayName("自定义策略允许任意主机一跳重定向，并支持相对 Location")
        void customPolicyFollowsSingleRelativeRedirect() {
            server = CatalogTestSupport.startServer();
            byte[] body = "relative-redirect".getBytes(StandardCharsets.UTF_8);
            CatalogTestSupport.serveBytes(server, "/final", body);
            CatalogTestSupport.serveRedirect(server, "/redir", "/final");
            PluginCatalogHttpClient custom = new PluginCatalogHttpClient(
                    false, true, 2000, 2000, null, true, Set.of(), true);

            assertThat(custom.fetchBytes(CatalogTestSupport.loopbackUrl(server, "/redir"), 1024))
                    .isEqualTo(body);
        }

        @Test
        @DisplayName("重定向目标主机不在白名单：拒绝跟随（DOWNLOAD_FAILED）")
        void rejectsNonAllowlistedRedirectTarget() {
            server = CatalogTestSupport.startServer();
            CatalogTestSupport.serveBytes(server, "/final", new byte[]{1});
            CatalogTestSupport.serveRedirect(server, "/redir", CatalogTestSupport.loopbackUrl(server, "/final"));

            // 白名单只含 githubusercontent.com；重定向目标主机是 127.0.0.1 → 不命中。
            assertThatThrownBy(() -> allowlisted("githubusercontent.com")
                    .fetchBytes(CatalogTestSupport.loopbackUrl(server, "/redir"), 1024))
                    .isInstanceOf(PluginCatalogException.class)
                    .extracting(e -> ((PluginCatalogException) e).code())
                    .isEqualTo(PluginCatalogErrorCode.DOWNLOAD_FAILED);
        }

        @Test
        @DisplayName("只跟随一跳：第二跳仍是 3xx 即失败（DOWNLOAD_FAILED）")
        void refusesSecondHop() {
            server = CatalogTestSupport.startServer();
            CatalogTestSupport.serveRedirect(server, "/b", CatalogTestSupport.loopbackUrl(server, "/c"));
            CatalogTestSupport.serveRedirect(server, "/a", CatalogTestSupport.loopbackUrl(server, "/b"));

            assertThatThrownBy(() -> allowlisted("127.0.0.1")
                    .fetchBytes(CatalogTestSupport.loopbackUrl(server, "/a"), 1024))
                    .isInstanceOf(PluginCatalogException.class)
                    .extracting(e -> ((PluginCatalogException) e).code())
                    .isEqualTo(PluginCatalogErrorCode.DOWNLOAD_FAILED);
        }

        @Test
        @DisplayName("3xx 缺 Location 头：拒绝（DOWNLOAD_FAILED）")
        void redirectWithoutLocation() {
            server = CatalogTestSupport.startServer();
            CatalogTestSupport.serveStatus(server, "/nolocation", 302);

            assertThatThrownBy(() -> allowlisted("127.0.0.1")
                    .fetchBytes(CatalogTestSupport.loopbackUrl(server, "/nolocation"), 1024))
                    .isInstanceOf(PluginCatalogException.class)
                    .extracting(e -> ((PluginCatalogException) e).code())
                    .isEqualTo(PluginCatalogErrorCode.DOWNLOAD_FAILED);
        }

        @Test
        @DisplayName("白名单子域匹配：GitHub release 资产 CDN 子域被允许、子串 / 后缀伪造被拒")
        void allowlistMatchesGithubAssetCdnSubdomains() {
            // 官方仓库 proxy-trusted 的内置白名单是 githubusercontent.com；GitHub release 下载 302 的真实目标主机
            // （release-assets / objects 子域）必须被允许、而子串 / 后缀伪造必须被拒（带点边界、非子串匹配）。
            PluginCatalogHttpClient c = new PluginCatalogHttpClient(true, false, 2000, 2000,
                    null, Set.of("githubusercontent.com"));
            assertThat(c.isAllowedRedirectHost("githubusercontent.com")).isTrue();
            assertThat(c.isAllowedRedirectHost("release-assets.githubusercontent.com")).isTrue();
            assertThat(c.isAllowedRedirectHost("objects.githubusercontent.com")).isTrue();
            assertThat(c.isAllowedRedirectHost("evil-githubusercontent.com")).isFalse();
            assertThat(c.isAllowedRedirectHost("githubusercontent.com.attacker.tld")).isFalse();
            assertThat(c.isAllowedRedirectHost("notgithubusercontent.com")).isFalse();
        }

        @Test
        @DisplayName("已代理客户端：verifyUrlAllowed 跳过本地 SSRF（私网地址不再被本地阻断，交由代理解析）")
        void proxiedSkipsLocalSsrf() {
            // proxySelector 非空即视为「已代理」；私网地址在直连档会被拒，代理档放行（仅校验、不连接）。
            PluginCatalogHttpClient proxied = new PluginCatalogHttpClient(true, false, 2000, 2000,
                    ProxySelector.of(new InetSocketAddress("127.0.0.1", 7890)), Set.of("githubusercontent.com"));

            assertThat(proxied.verifyUrlAllowed("https://10.0.0.1/x.jar"))
                    .isEqualTo(URI.create("https://10.0.0.1/x.jar"));
        }

        @Test
        @DisplayName("自定义代理策略未允许非公网地址时仍按本机解析结果阻断私网目标")
        void customProxiedStillValidatesLocalAddressWhenRequested() {
            PluginCatalogHttpClient proxied = new PluginCatalogHttpClient(true, false, 2000, 2000,
                    ProxySelector.of(new InetSocketAddress("127.0.0.1", 7890)), false, Set.of(), true);

            assertThatThrownBy(() -> proxied.verifyUrlAllowed("https://10.0.0.1/x.jar"))
                    .isInstanceOf(PluginCatalogException.class)
                    .extracting(e -> ((PluginCatalogException) e).code())
                    .isEqualTo(PluginCatalogErrorCode.BLOCKED_ADDRESS);
        }
    }
}
