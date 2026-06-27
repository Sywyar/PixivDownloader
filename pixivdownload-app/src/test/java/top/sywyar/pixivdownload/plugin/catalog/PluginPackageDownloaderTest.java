package top.sywyar.pixivdownload.plugin.catalog;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.catalog.repository.PluginCatalogClientProvider;
import top.sywyar.pixivdownload.plugin.catalog.repository.PluginRepository;
import top.sywyar.pixivdownload.plugin.catalog.repository.RepositoryProxyPolicy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * {@link PluginPackageDownloader} 单测：<b>按目标仓库</b>装配客户端（与清单读取同源 {@link PluginCatalogClientProvider}）的
 * 受信包下载——下载器不持固定全局客户端，而是对每次下载所属的 {@link PluginRepository} 调
 * {@link PluginCatalogClientProvider#clientFor} 取客户端、用 {@link PluginRepository#maxPackageBytes()} 作上限。覆盖：
 * 包元数据完整性校验、流式上限中止 + 临时文件清理、URL 后缀推断、direct-strict 拒重定向、proxy-trusted 跟随白名单一跳、
 * 非白名单重定向拒绝、每仓库更严格上限、未知策略 fail-closed、官方风格 GitHub release URL 经受信策略下载，以及下载器
 * 把<b>确切仓库</b>（其超时 / 策略）传给 provider（不退回默认 / 全局）。临时目录注入 {@code @TempDir} 以确定性断言清理。
 */
@DisplayName("PluginPackageDownloader 按仓库装配的受信包安全下载器")
class PluginPackageDownloaderTest {

    @TempDir
    Path tempDir;

    /** 放开 http + 非公网的测试客户端（对接 loopback 桩）。 */
    private final PluginCatalogHttpClient relaxed = new PluginCatalogHttpClient(false, true, 2000, 2000);
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ---------- 元数据 / 上限 / 后缀（client 固定为 relaxed） ----------

    @Test
    @DisplayName("正常下载：返回临时文件、字节一致、后缀按 URL 推断为 .zip")
    void downloadsToTemp() throws Exception {
        server = CatalogTestSupport.startServer();
        byte[] body = CatalogTestSupport.explodedPluginZip("ext", "1.0.0", null);
        CatalogTestSupport.serveBytes(server, "/p.zip", body);
        PluginPackageDownloader downloader = downloader(fixed(relaxed));

        Path temp = downloader.downloadToTemp(repo(RepositoryProxyPolicy.DIRECT_STRICT, BIG), pkg(
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
        PluginPackageDownloader downloader = downloader(fixed(relaxed));

        Path temp = downloader.downloadToTemp(repo(RepositoryProxyPolicy.DIRECT_STRICT, BIG), pkg(
                "  " + CatalogTestSupport.loopbackUrl(server, "/p.zip") + "  ", (long) body.length,
                CatalogTestSupport.sha256Hex(body), null));

        assertThat(temp).exists();
        assertThat(temp.getFileName().toString()).endsWith(".zip");
        assertThat(Files.readAllBytes(temp)).isEqualTo(body);
    }

    @Test
    @DisplayName("缺 url / 缺 sha256 / 缺或非正 expectedSize：INVALID_PACKAGE_METADATA，不建临时文件")
    void rejectsIncompleteMetadata() {
        PluginPackageDownloader downloader = downloader(fixed(relaxed));
        PluginRepository repo = repo(RepositoryProxyPolicy.DIRECT_STRICT, BIG);
        assertCode(downloader, repo, pkg(null, 10L, "abc", null), PluginCatalogErrorCode.INVALID_PACKAGE_METADATA);
        assertCode(downloader, repo, pkg("https://h.example/p.zip", null, "abc", null),
                PluginCatalogErrorCode.INVALID_PACKAGE_METADATA);
        assertCode(downloader, repo, pkg("https://h.example/p.zip", 0L, "abc", null),
                PluginCatalogErrorCode.INVALID_PACKAGE_METADATA);
        assertCode(downloader, repo, pkg("https://h.example/p.zip", 10L, "  ", null),
                PluginCatalogErrorCode.INVALID_PACKAGE_METADATA);
        assertThat(leftovers()).isEmpty();
    }

    @Test
    @DisplayName("URL 不以 .jar/.zip 结尾：INVALID_PACKAGE_METADATA，不建临时文件")
    void rejectsUnsupportedSuffix() {
        PluginPackageDownloader downloader = downloader(fixed(relaxed));
        assertCode(downloader, repo(RepositoryProxyPolicy.DIRECT_STRICT, BIG),
                pkg("https://h.example/p.bin", 10L, "abc", null), PluginCatalogErrorCode.INVALID_PACKAGE_METADATA);
        assertThat(leftovers()).isEmpty();
    }

    @Test
    @DisplayName("每仓库 max-package-bytes 比全局更小：声明体积超该仓库上限即 DOWNLOAD_TOO_LARGE（不建临时文件）")
    void perRepositoryMaxPackageBytesEnforcedTighterThanGlobal() {
        // 仓库上限 100（远小于全局默认 100MB）；声明体积 1000 > 100 → 按该仓库上限拒绝（证明用的是仓库上限、非全局）。
        PluginPackageDownloader downloader = downloader(fixed(relaxed));
        assertCode(downloader, repo(RepositoryProxyPolicy.DIRECT_STRICT, 100L),
                pkg("https://h.example/p.zip", 1000L, "abc", null), PluginCatalogErrorCode.DOWNLOAD_TOO_LARGE);
        assertThat(leftovers()).isEmpty();
    }

    @Test
    @DisplayName("下载体积超过 expectedSize：中止抛 DOWNLOAD_TOO_LARGE，并清理临时文件（不留半成品）")
    void abortsAndCleansUpOnOversizeStream() {
        server = CatalogTestSupport.startServer();
        CatalogTestSupport.serveBytes(server, "/big.zip", new byte[4096]);
        PluginPackageDownloader downloader = downloader(fixed(relaxed));

        assertCode(downloader, repo(RepositoryProxyPolicy.DIRECT_STRICT, BIG),
                pkg(CatalogTestSupport.loopbackUrl(server, "/big.zip"), 16L, "abc", null),
                PluginCatalogErrorCode.DOWNLOAD_TOO_LARGE);

        assertThat(leftovers()).as("超限下载必须清理临时文件").isEmpty();
    }

    // ---------- 按仓库代理策略：重定向 / 未知策略 ----------

    @Test
    @DisplayName("direct-strict 仓库：包下载遇重定向被拒（DOWNLOAD_FAILED），清理临时文件")
    void directStrictRejectsRedirect() {
        server = CatalogTestSupport.startServer();
        CatalogTestSupport.serveBytes(server, "/final.zip", new byte[]{1, 2, 3});
        CatalogTestSupport.serveRedirect(server, "/redir.zip", CatalogTestSupport.loopbackUrl(server, "/final.zip"));
        PluginPackageDownloader downloader = downloader(policyFaithful());

        assertCode(downloader, repo(RepositoryProxyPolicy.DIRECT_STRICT, BIG),
                pkg(CatalogTestSupport.loopbackUrl(server, "/redir.zip"), 3L, "abc", null),
                PluginCatalogErrorCode.DOWNLOAD_FAILED);
        assertThat(leftovers()).isEmpty();
    }

    @Test
    @DisplayName("proxy-trusted 仓库：跟随白名单内一跳重定向，取到最终字节")
    void proxyTrustedFollowsOneAllowlistedRedirect() throws Exception {
        server = CatalogTestSupport.startServer();
        byte[] body = CatalogTestSupport.explodedPluginZip("ext", "1.0.0", null);
        CatalogTestSupport.serveBytes(server, "/final.zip", body);
        CatalogTestSupport.serveRedirect(server, "/redir.zip", CatalogTestSupport.loopbackUrl(server, "/final.zip"));
        PluginPackageDownloader downloader = downloader(policyFaithful());

        Path temp = downloader.downloadToTemp(repo(RepositoryProxyPolicy.PROXY_TRUSTED, BIG), pkg(
                CatalogTestSupport.loopbackUrl(server, "/redir.zip"), (long) body.length,
                CatalogTestSupport.sha256Hex(body), null));

        assertThat(temp).exists();
        assertThat(Files.readAllBytes(temp)).isEqualTo(body);
    }

    @Test
    @DisplayName("proxy-trusted 仓库但重定向目标不在白名单：拒绝跟随（DOWNLOAD_FAILED）")
    void proxyTrustedRejectsNonAllowlistedRedirect() {
        server = CatalogTestSupport.startServer();
        CatalogTestSupport.serveBytes(server, "/final.zip", new byte[]{9});
        CatalogTestSupport.serveRedirect(server, "/redir.zip", CatalogTestSupport.loopbackUrl(server, "/final.zip"));
        // 受信客户端的白名单只含 githubusercontent.com；loopback 重定向目标主机是 127.0.0.1 → 不命中、拒绝。
        PluginCatalogHttpClient trustedGithubOnly = new PluginCatalogHttpClient(false, true, 2000, 2000,
                null, Set.of("githubusercontent.com"));
        PluginPackageDownloader downloader = downloader(fixed(trustedGithubOnly));

        assertCode(downloader, repo(RepositoryProxyPolicy.PROXY_TRUSTED, BIG),
                pkg(CatalogTestSupport.loopbackUrl(server, "/redir.zip"), 1L, "abc", null),
                PluginCatalogErrorCode.DOWNLOAD_FAILED);
        assertThat(leftovers()).isEmpty();
    }

    @Test
    @DisplayName("官方风格 GitHub release 下载 URL（.jar）：proxy-trusted 跟随一跳 CDN 重定向，取到 jar 字节、后缀 .jar")
    void followsTrustedRedirectForGithubReleaseStyleJarUrl() throws Exception {
        server = CatalogTestSupport.startServer();
        byte[] body = "thin-plugin-jar-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        // 模拟 GitHub：/<owner>/<repo>/releases/download/<tag>/<jar>（稳定入口）→ 302 → /cdn/<jar>（资产 CDN）。
        String releasePath = "/Sywyar/PixivDownloader-plugins/releases/download/stats-v1.0.0/"
                + "pixivdownload-plugin-stats-1.0.0.jar";
        String cdnPath = "/cdn/pixivdownload-plugin-stats-1.0.0.jar";
        CatalogTestSupport.serveBytes(server, cdnPath, body);
        CatalogTestSupport.serveRedirect(server, releasePath, CatalogTestSupport.loopbackUrl(server, cdnPath));
        PluginPackageDownloader downloader = downloader(policyFaithful());

        Path temp = downloader.downloadToTemp(repo(RepositoryProxyPolicy.PROXY_TRUSTED, BIG), pkg(
                CatalogTestSupport.loopbackUrl(server, releasePath), (long) body.length,
                CatalogTestSupport.sha256Hex(body), null));

        assertThat(temp).exists();
        assertThat(temp.getFileName().toString()).endsWith(".jar");
        assertThat(Files.readAllBytes(temp)).isEqualTo(body);
    }

    @Test
    @DisplayName("direct-strict 客户端：https loopback 地址被拒（BLOCKED_ADDRESS），校验阶段即拒、不连接不留半成品")
    void directStrictClientBlocksLoopback() {
        // 真实严格客户端仅 https + 拒非公网：用 https loopback 字面量，scheme 通过后命中 IP 阻断（连接之前）。
        PluginCatalogHttpClient strict = new PluginCatalogHttpClient(true, false, 2000, 2000);
        PluginPackageDownloader downloader = downloader(fixed(strict));

        assertCode(downloader, repo(RepositoryProxyPolicy.DIRECT_STRICT, BIG),
                pkg("https://127.0.0.1:8443/p.zip", 3L, "abc", null), PluginCatalogErrorCode.BLOCKED_ADDRESS);
        assertThat(leftovers()).isEmpty();
    }

    @Test
    @DisplayName("未知代理策略仓库：clientFor fail-closed → PROXY_POLICY_UNSUPPORTED（下载前，不建临时文件）")
    void unknownProxyPolicyFailsClosed() {
        PluginPackageDownloader downloader = downloader(policyFaithful());
        assertCode(downloader, repo(null, BIG),
                pkg("https://h.example/p.zip", 10L, "abc", null), PluginCatalogErrorCode.PROXY_POLICY_UNSUPPORTED);
        assertThat(leftovers()).isEmpty();
    }

    // ---------- 下载器把「确切仓库」（其超时 / 策略 / 上限）传给 provider ----------

    @Test
    @DisplayName("下载用确切仓库装配客户端：provider 收到的就是该仓库（含其超时 / 策略 / 上限），不退回默认 / 全局")
    void usesExactRepositoryForClientAssembly() throws Exception {
        server = CatalogTestSupport.startServer();
        byte[] body = CatalogTestSupport.explodedPluginZip("ext", "1.0.0", null);
        CatalogTestSupport.serveBytes(server, "/p.zip", body);
        RecordingProvider provider = new RecordingProvider(relaxed);
        PluginRepository repo = new PluginRepository("trusted-repo", "k", "https://x.example/m.json",
                true, false, false, RepositoryProxyPolicy.PROXY_TRUSTED, "proxy-trusted",
                false, true, false, false,
                1234, 5678, 4096, 52_428_800L);
        PluginPackageDownloader downloader = downloader(provider);

        Path temp = downloader.downloadToTemp(repo, pkg(
                CatalogTestSupport.loopbackUrl(server, "/p.zip"), (long) body.length,
                CatalogTestSupport.sha256Hex(body), null));

        assertThat(temp).exists();
        assertThat(provider.captured).as("downloader 必须把确切仓库传给 clientFor").isSameAs(repo);
        assertThat(provider.captured.connectTimeoutMs()).isEqualTo(1234);
        assertThat(provider.captured.readTimeoutMs()).isEqualTo(5678);
        assertThat(provider.captured.proxyPolicy()).isEqualTo(RepositoryProxyPolicy.PROXY_TRUSTED);
        assertThat(provider.captured.maxPackageBytes()).isEqualTo(52_428_800L);
    }

    // ---------- helpers ----------

    private static final long BIG = 100L * 1024 * 1024;

    private PluginPackageDownloader downloader(PluginCatalogClientProvider provider) {
        return new PluginPackageDownloader(provider, tempDir);
    }

    /** 固定返回同一个客户端的 provider（忽略仓库）——用于测元数据 / 上限 / 后缀等与客户端无关的逻辑。 */
    private static PluginCatalogClientProvider fixed(PluginCatalogHttpClient client) {
        return repository -> client;
    }

    /**
     * 忠实镜像 {@code DefaultPluginCatalogClientProvider} 策略语义、但放开 http + 非公网以对接 loopback 桩的 provider：
     * direct-strict = 禁重定向；proxy-trusted = 白名单（loopback 用 {@code 127.0.0.1} 镜像生产 {@code githubusercontent.com}）
     * 内跟随一跳；custom = 按仓库开关允许任意目标一跳；未知策略 = {@code PROXY_POLICY_UNSUPPORTED}。
     */
    private static PluginCatalogClientProvider policyFaithful() {
        return repository -> {
            RepositoryProxyPolicy policy = repository.proxyPolicy();
            if (policy == RepositoryProxyPolicy.DIRECT_STRICT) {
                return new PluginCatalogHttpClient(false, true,
                        (int) repository.connectTimeoutMs(), (int) repository.readTimeoutMs());
            }
            if (policy == RepositoryProxyPolicy.PROXY_TRUSTED) {
                return new PluginCatalogHttpClient(false, true,
                        (int) repository.connectTimeoutMs(), (int) repository.readTimeoutMs(), null, Set.of("127.0.0.1"));
            }
            if (policy == RepositoryProxyPolicy.CUSTOM) {
                return new PluginCatalogHttpClient(false, true,
                        (int) repository.connectTimeoutMs(), (int) repository.readTimeoutMs(), null,
                        repository.allowRedirects(), Set.of(), true);
            }
            throw new PluginCatalogException(PluginCatalogErrorCode.PROXY_POLICY_UNSUPPORTED,
                    "unsupported proxy policy for repository " + repository.repositoryId());
        };
    }

    /** 记录被传入 {@link #clientFor} 的仓库，返回固定客户端——证明下载器用的是确切仓库。 */
    private static final class RecordingProvider implements PluginCatalogClientProvider {
        private final PluginCatalogHttpClient client;
        private PluginRepository captured;

        RecordingProvider(PluginCatalogHttpClient client) {
            this.client = client;
        }

        @Override
        public PluginCatalogHttpClient clientFor(PluginRepository repository) {
            this.captured = repository;
            return client;
        }
    }

    private static PluginRepository repo(RepositoryProxyPolicy policy, long maxPackageBytes) {
        return new PluginRepository("r", "k", "https://x.example/m.json", true, false, false,
                policy, policy == null ? "socks5" : policy.configId(), false, true, false, false,
                2000, 2000, 4096, maxPackageBytes);
    }

    private static PluginCatalogPackage pkg(String url, Long size, String sha256, String signature) {
        return new PluginCatalogPackage("1.0.0", url, size, sha256, signature, null, List.of(), null, List.of(), null, false);
    }

    private static void assertCode(PluginPackageDownloader downloader, PluginRepository repository,
                                   PluginCatalogPackage pkg, PluginCatalogErrorCode expected) {
        PluginCatalogException ex = catchThrowableOfType(
                () -> downloader.downloadToTemp(repository, pkg), PluginCatalogException.class);
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
