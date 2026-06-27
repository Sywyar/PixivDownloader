package top.sywyar.pixivdownload.plugin.catalog;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.PluginInstallReport;
import top.sywyar.pixivdownload.plugin.PluginInstallService;
import top.sywyar.pixivdownload.plugin.catalog.repository.PluginCatalogClientProvider;
import top.sywyar.pixivdownload.plugin.catalog.repository.PluginRepositoryRegistry;
import top.sywyar.pixivdownload.plugin.catalog.repository.RepositoryProxyPolicy;
import top.sywyar.pixivdownload.plugin.runtime.install.ExternalPluginInstaller;
import top.sywyar.pixivdownload.plugin.runtime.install.PluginInstallOutcome;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * {@link PluginCatalogAcquisitionService} 单测（真实安装器 + loopback 桩，端到端）：按 id+version 选包 → 下载 → 经
 * {@code PluginPackageOrigin.forTrustedCatalog} 完整性校验 → 安全落盘；以及未知 id / 版本缺失 / catalog 禁用的稳定错误，
 * 与 sha256 / 大小 / 签名（fail-closed）不符 → {@code REJECTED_INTEGRITY}。每次安装后断言下载临时文件已清理。
 */
@DisplayName("PluginCatalogAcquisitionService 受信目录安装编排")
class PluginCatalogAcquisitionServiceTest {

    @TempDir
    Path home;
    private Path pluginsDir;
    private Path downloadTempDir;
    private HttpServer server;

    @BeforeEach
    void setUp() throws IOException {
        pluginsDir = home.resolve("plugins");
        downloadTempDir = home.resolve("dl");
        Files.createDirectories(downloadTempDir);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("catalog 未启用：install 抛 CATALOG_DISABLED")
    void disabledRejectsInstall() {
        PluginCatalogProperties props = new PluginCatalogProperties();
        props.setEnabled(false);
        PluginCatalogAcquisitionService service = acquisition(props);

        PluginCatalogException ex = catchThrowableOfType(
                () -> service.install("ext", "1.0.0"), PluginCatalogException.class);
        assertThat(ex).isNotNull();
        assertThat(ex.code()).isEqualTo(PluginCatalogErrorCode.CATALOG_DISABLED);
    }

    @Test
    @DisplayName("未知插件 id：UNKNOWN_PLUGIN")
    void unknownPlugin() {
        byte[] body = CatalogTestSupport.explodedPluginZip("ext", "1.0.0", null);
        PluginCatalogAcquisitionService service = setUpInstall(body,
                url -> manifest("ext", "1.0.0", url, body.length, CatalogTestSupport.sha256Hex(body), null));

        PluginCatalogException ex = catchThrowableOfType(
                () -> service.install("ghost", "1.0.0"), PluginCatalogException.class);
        assertThat(ex.code()).isEqualTo(PluginCatalogErrorCode.UNKNOWN_PLUGIN);
        assertThat(installedFiles()).isEmpty();
    }

    @Test
    @DisplayName("版本不存在：VERSION_NOT_FOUND")
    void versionNotFound() {
        byte[] body = CatalogTestSupport.explodedPluginZip("ext", "1.0.0", null);
        PluginCatalogAcquisitionService service = setUpInstall(body,
                url -> manifest("ext", "1.0.0", url, body.length, CatalogTestSupport.sha256Hex(body), null));

        PluginCatalogException ex = catchThrowableOfType(
                () -> service.install("ext", "9.9.9"), PluginCatalogException.class);
        assertThat(ex.code()).isEqualTo(PluginCatalogErrorCode.VERSION_NOT_FOUND);
        assertThat(installedFiles()).isEmpty();
    }

    @Test
    @DisplayName("正常：下载 + 完整性校验通过 → INSTALLED，accepted + effectiveAfterRestart，落盘；下载临时文件已清理")
    void happyInstall() {
        byte[] body = CatalogTestSupport.explodedPluginZip("ext", "1.0.0", null);
        PluginCatalogAcquisitionService service = setUpInstall(body,
                url -> manifest("ext", "1.0.0", url, body.length, CatalogTestSupport.sha256Hex(body), null));

        PluginInstallReport report = service.install("ext", "1.0.0");

        assertThat(report.outcome()).isEqualTo(PluginInstallOutcome.INSTALLED);
        assertThat(report.accepted()).isTrue();
        assertThat(report.effectiveAfterRestart()).isTrue();
        assertThat(report.pluginId()).isEqualTo("ext");
        assertThat(installedFiles()).containsExactly("ext-1.0.0.zip");
        assertThat(downloadLeftovers()).as("下载临时文件应被清理").isEmpty();
    }

    @Test
    @DisplayName("sha256 不符：REJECTED_INTEGRITY，零落盘，临时文件已清理")
    void sha256Mismatch() {
        byte[] body = CatalogTestSupport.explodedPluginZip("ext", "1.0.0", null);
        PluginCatalogAcquisitionService service = setUpInstall(body,
                url -> manifest("ext", "1.0.0", url, body.length, "deadbeefdeadbeef", null));

        PluginInstallReport report = service.install("ext", "1.0.0");

        assertThat(report.outcome()).isEqualTo(PluginInstallOutcome.REJECTED_INTEGRITY);
        assertThat(report.accepted()).isFalse();
        assertThat(installedFiles()).isEmpty();
        assertThat(downloadLeftovers()).isEmpty();
    }

    @Test
    @DisplayName("大小不符（声明大于实际）：REJECTED_INTEGRITY，零落盘")
    void sizeMismatch() {
        byte[] body = CatalogTestSupport.explodedPluginZip("ext", "1.0.0", null);
        // 声明体积比实际大：下载完整完成（未触发流式上限），落盘前安装器的大小校验失败 → REJECTED_INTEGRITY。
        PluginCatalogAcquisitionService service = setUpInstall(body,
                url -> manifest("ext", "1.0.0", url, body.length + 100L, CatalogTestSupport.sha256Hex(body), null));

        PluginInstallReport report = service.install("ext", "1.0.0");

        assertThat(report.outcome()).isEqualTo(PluginInstallOutcome.REJECTED_INTEGRITY);
        assertThat(installedFiles()).isEmpty();
    }

    @Test
    @DisplayName("声明了签名但无校验器：fail-closed → REJECTED_INTEGRITY（大小 / 哈希正确也拒绝）")
    void signatureFailsClosed() {
        byte[] body = CatalogTestSupport.explodedPluginZip("ext", "1.0.0", null);
        PluginCatalogAcquisitionService service = setUpInstall(body,
                url -> manifest("ext", "1.0.0", url, body.length, CatalogTestSupport.sha256Hex(body), "SIG=="));

        PluginInstallReport report = service.install("ext", "1.0.0");

        assertThat(report.outcome()).isEqualTo(PluginInstallOutcome.REJECTED_INTEGRITY);
        assertThat(installedFiles()).isEmpty();
    }

    @Test
    @DisplayName("按 repositoryId 安装用该仓库的下载客户端、不退回默认（严格）仓库：受信仓库跟随重定向 → INSTALLED；默认严格仓库拒重定向")
    void installFromRepositoryUsesThatRepositoryNotDefault() {
        server = CatalogTestSupport.startServer();
        byte[] body = CatalogTestSupport.explodedPluginZip("ext", "1.0.0", null);
        CatalogTestSupport.serveBytes(server, "/final.zip", body);
        // 包经 /redir.zip（302 → /final.zip）下发：direct-strict 会拒、proxy-trusted 会跟随白名单一跳。
        CatalogTestSupport.serveRedirect(server, "/redir.zip", CatalogTestSupport.loopbackUrl(server, "/final.zip"));
        String pkgUrl = CatalogTestSupport.loopbackUrl(server, "/redir.zip");
        byte[] manifestJson = manifest("ext", "1.0.0", pkgUrl, body.length, CatalogTestSupport.sha256Hex(body), null)
                .getBytes(StandardCharsets.UTF_8);
        CatalogTestSupport.serveBytes(server, "/strict.json", manifestJson);
        CatalogTestSupport.serveBytes(server, "/trusted.json", manifestJson);
        PluginCatalogProperties props = new PluginCatalogProperties();
        props.setEnabled(true);
        props.setOfficialRepositoryEnabled(false); // 官方仓库真实 URL 不在 loopback，禁用使默认 = 首个自定义（strict）
        props.setRepositories(List.of(
                repoConfig("strict", CatalogTestSupport.loopbackUrl(server, "/strict.json"), "direct-strict"),
                repoConfig("trusted", CatalogTestSupport.loopbackUrl(server, "/trusted.json"), "proxy-trusted")));
        PluginCatalogAcquisitionService service = acquisition(props);

        // 经 trusted 仓库安装：proxy-trusted 跟随白名单一跳 → 成功落盘（若退回默认 strict 客户端会因拒重定向失败）。
        PluginInstallReport report = service.install("trusted", "ext", "1.0.0");
        assertThat(report.outcome()).isEqualTo(PluginInstallOutcome.INSTALLED);
        assertThat(installedFiles()).containsExactly("ext-1.0.0.zip");
        assertThat(downloadLeftovers()).isEmpty();

        // 对照：默认仓库 = strict（direct-strict），其包下载遇重定向被拒（证明默认确为严格、上面的成功来自 trusted 客户端）。
        PluginCatalogException ex = catchThrowableOfType(
                () -> service.install("ext", "1.0.0"), PluginCatalogException.class);
        assertThat(ex.code()).isEqualTo(PluginCatalogErrorCode.DOWNLOAD_FAILED);
        assertThat(downloadLeftovers()).isEmpty();
    }

    // ---------- helpers ----------

    private PluginCatalogAcquisitionService setUpInstall(byte[] pkgBytes,
                                                         java.util.function.Function<String, String> manifestForUrl) {
        server = CatalogTestSupport.startServer();
        String pkgUrl = CatalogTestSupport.loopbackUrl(server, "/pkg.zip");
        CatalogTestSupport.serveBytes(server, "/pkg.zip", pkgBytes);
        CatalogTestSupport.serveBytes(server, "/catalog.json",
                manifestForUrl.apply(pkgUrl).getBytes(StandardCharsets.UTF_8));
        PluginCatalogProperties props = new PluginCatalogProperties();
        props.setEnabled(true);
        props.setManifestUrl(CatalogTestSupport.loopbackUrl(server, "/catalog.json"));
        return acquisition(props);
    }

    /**
     * 装配编排：catalog 读取与包下载<b>共用同一个</b> {@link PluginCatalogClientProvider}（忠实镜像生产策略语义、放开
     * 非公网以对接 loopback；direct-strict 禁重定向、proxy-trusted 白名单一跳、custom 按开关一跳、未知策略 fail-closed）——证明下载与清单读取
     * 同源、且按仓库代理策略下载。
     */
    private PluginCatalogAcquisitionService acquisition(PluginCatalogProperties props) {
        PluginCatalogClientProvider provider = policyFaithful();
        PluginCatalogService catalogService = new PluginCatalogService(new PluginRepositoryRegistry(props), provider);
        PluginPackageDownloader downloader = new PluginPackageDownloader(provider, downloadTempDir);
        PluginInstallService installService = new PluginInstallService(new ExternalPluginInstaller(pluginsDir));
        return new PluginCatalogAcquisitionService(catalogService, downloader, installService);
    }

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

    private static PluginCatalogProperties.RepositoryConfig repoConfig(String id, String manifestUrl, String policy) {
        PluginCatalogProperties.RepositoryConfig rc = new PluginCatalogProperties.RepositoryConfig();
        rc.setId(id);
        rc.setManifestUrl(manifestUrl);
        rc.setEnabled(true);
        rc.setProxyPolicy(policy);
        return rc;
    }

    private static String manifest(String pluginId, String version, String pkgUrl,
                                   long size, String sha256, String signature) {
        String sig = signature == null ? "" : ",\"signature\":\"" + signature + "\"";
        return "{\"entries\":[{\"pluginId\":\"" + pluginId + "\",\"packages\":[{"
                + "\"version\":\"" + version + "\","
                + "\"packageUrl\":\"" + pkgUrl + "\","
                + "\"expectedSizeBytes\":" + size + ","
                + "\"sha256\":\"" + sha256 + "\"" + sig
                + "}]}]}";
    }

    private List<String> installedFiles() {
        if (!Files.isDirectory(pluginsDir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(pluginsDir)) {
            return stream.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> !n.startsWith("."))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<Path> downloadLeftovers() {
        try (Stream<Path> stream = Files.list(downloadTempDir)) {
            return stream.toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
