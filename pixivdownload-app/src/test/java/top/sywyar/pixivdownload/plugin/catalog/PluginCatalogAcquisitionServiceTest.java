package top.sywyar.pixivdownload.plugin.catalog;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.install.PluginDependencyInstallResult;
import top.sywyar.pixivdownload.plugin.install.PluginInstallReport;
import top.sywyar.pixivdownload.plugin.install.PluginInstallService;
import top.sywyar.pixivdownload.plugin.install.PluginDependencyResolver;
import top.sywyar.pixivdownload.plugin.catalog.repository.PluginCatalogClientProvider;
import top.sywyar.pixivdownload.plugin.catalog.repository.PluginRepositoryRegistry;
import top.sywyar.pixivdownload.plugin.catalog.repository.RepositoryProxyPolicy;
import top.sywyar.pixivdownload.plugin.lifecycle.ExternalPluginLifecycleCoordinator;
import top.sywyar.pixivdownload.plugin.lifecycle.ExternalPluginOperation;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginLifecycleService;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginRuntimePhase;
import top.sywyar.pixivdownload.plugin.recovery.RecoveryModeService;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager;
import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginInventory;
import top.sywyar.pixivdownload.plugin.runtime.install.ExternalPluginInstaller;
import top.sywyar.pixivdownload.plugin.runtime.install.model.InstalledPlugin;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginInstallOutcome;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageLimits;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageOrigin;
import top.sywyar.pixivdownload.plugin.runtime.lifecycle.LoadedPluginPackage;
import top.sywyar.pixivdownload.plugin.runtime.lifecycle.PluginRuntimePackagePhase;
import top.sywyar.pixivdownload.plugin.signature.SignatureMetadata;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    private final List<ExternalPluginInstaller> installers = new ArrayList<>();

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
        installers.forEach(ExternalPluginInstaller::close);
        installers.clear();
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
                (url, signing) -> manifest("ext", "1.0.0", url, body.length,
                        CatalogTestSupport.sha256Hex(body), signing.artifactSignature("ext", "1.0.0", body),
                        signing));

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
                (url, signing) -> manifest("ext", "1.0.0", url, body.length,
                        CatalogTestSupport.sha256Hex(body), signing.artifactSignature("ext", "1.0.0", body),
                        signing));

        PluginCatalogException ex = catchThrowableOfType(
                () -> service.install("ext", "9.9.9"), PluginCatalogException.class);
        assertThat(ex.code()).isEqualTo(PluginCatalogErrorCode.VERSION_NOT_FOUND);
        assertThat(installedFiles()).isEmpty();
    }

    @Test
    @DisplayName("正常：下载 + 完整性校验通过 → INSTALLED，当前 generation 已激活并落盘；下载临时文件已清理")
    void happyInstall() {
        byte[] body = CatalogTestSupport.explodedPluginZip("ext", "1.0.0", null);
        PluginCatalogAcquisitionService service = setUpInstall(body,
                (url, signing) -> manifest("ext", "1.0.0", url, body.length,
                        CatalogTestSupport.sha256Hex(body), signing.artifactSignature("ext", "1.0.0", body),
                        signing));

        PluginInstallReport report = service.install("ext", "1.0.0");

        assertThat(report.outcome()).isEqualTo(PluginInstallOutcome.INSTALLED);
        assertThat(report.accepted()).isTrue();
        assertThat(report.effectiveAfterRestart()).isFalse();
        assertThat(report.activated()).isTrue();
        assertThat(report.pluginId()).isEqualTo("ext");
        assertThat(installedFiles()).containsExactly("ext-1.0.0.zip");
        assertThat(downloadLeftovers()).as("下载临时文件应被清理").isEmpty();
    }

    @Test
    @DisplayName("依赖闭环：安装 alpha 时从同一 catalog 自动先安装 beta")
    void installsRequiredDependencyFromSameCatalog() {
        server = CatalogTestSupport.startServer();
        CatalogTestSupport.SigningFixture signing = CatalogTestSupport.signingFixture();
        byte[] beta = CatalogTestSupport.explodedPluginZip("beta", "1.0.0", null);
        byte[] alpha = CatalogTestSupport.explodedPluginZip("alpha", "1.0.0", null, "beta@1.0");
        String betaUrl = servePackage("/beta.zip", beta);
        String alphaUrl = servePackage("/alpha.zip", alpha);
        PluginCatalogAcquisitionService service = setUpManifest(signing,
                entryJson("beta", "1.0.0", betaUrl, beta, signing, List.of()),
                entryJson("alpha", "1.0.0", alphaUrl, alpha, signing, List.of("beta@1.0")));

        PluginInstallReport report = service.install("alpha", "1.0.0");

        assertThat(report.outcome()).isEqualTo(PluginInstallOutcome.INSTALLED);
        assertThat(report.pluginId()).isEqualTo("alpha");
        assertThat(installedFiles()).containsExactly("alpha-1.0.0.zip", "beta-1.0.0.zip");
        assertThat(report.dependencyInstallResults())
                .extracting(PluginDependencyInstallResult::pluginId)
                .containsExactly("beta");
        assertThat(report.unsatisfiedDependencies()).isEmpty();
    }

    @Test
    @DisplayName("依赖安装留下恢复事务时阻断目标下载并向顶层报告传播机器态")
    void dependencyRecoveryBlockStopsTargetInstallAndPropagatesReceipt() {
        server = CatalogTestSupport.startServer();
        CatalogTestSupport.SigningFixture signing = CatalogTestSupport.signingFixture();
        byte[] beta = CatalogTestSupport.explodedPluginZip("beta", "1.0.0", null);
        byte[] alpha = CatalogTestSupport.explodedPluginZip("alpha", "1.0.0", null);
        String betaUrl = servePackage("/blocked-beta.zip", beta);
        AtomicInteger alphaDownloads = new AtomicInteger();
        String alphaUrl = serveCountingPackage("/blocked-alpha.zip", alpha, alphaDownloads);
        byte[] manifestBytes = ("{\"entries\":["
                + entryJson("beta", "1.0.0", betaUrl, beta, signing, List.of()) + ","
                + entryJson("alpha", "1.0.0", alphaUrl, alpha, signing, List.of("beta@1.0"))
                + "]}").getBytes(StandardCharsets.UTF_8);
        CatalogTestSupport.serveBytes(server, "/blocked-catalog.json", manifestBytes);
        CatalogTestSupport.serveBytes(server, "/blocked-catalog.json.sig",
                signing.manifestSignatureBytes("configured", manifestBytes));
        PluginCatalogProperties props = new PluginCatalogProperties();
        props.setEnabled(true);
        props.setManifestUrl(CatalogTestSupport.loopbackUrl(server, "/blocked-catalog.json"));
        props.setTrustedKeys(List.of(signing.trustedKeyConfig()));
        PluginInstallService installService = mock(PluginInstallService.class);
        PluginDependencyResolver resolver = mock(PluginDependencyResolver.class);
        PluginInstallReport blockedDependency = new PluginInstallReport(
                PluginInstallOutcome.INSTALLED, true, false, "beta", "1.0.0", null,
                List.of(), List.of(), List.of(), List.of("transaction recovery required"),
                "tx-beta-blocked", true, false, null,
                ExternalPluginOperation.FAILED, PluginRuntimePhase.STARTED, true, false);
        when(installService.installTrustedFile(
                any(Path.class), eq(false), any(PluginPackageOrigin.class))).thenReturn(blockedDependency);
        PluginCatalogAcquisitionService service = acquisition(props, installService, resolver);

        PluginInstallReport report = service.install("alpha", "1.0.0");

        assertThat(report.outcome()).isEqualTo(PluginInstallOutcome.REJECTED_DEPENDENCY);
        assertThat(report.accepted()).isFalse();
        assertThat(report.recoveryBlocked()).isTrue();
        assertThat(report.dependencyInstallResults()).singleElement().satisfies(dependency -> {
            assertThat(dependency.pluginId()).isEqualTo("beta");
            assertThat(dependency.recoveryBlocked()).isTrue();
        });
        assertThat(alphaDownloads).hasValue(0);
        verify(installService, times(1)).installTrustedFile(
                any(Path.class), eq(false), any(PluginPackageOrigin.class));
    }

    @Test
    @DisplayName("依赖闭环：安装 alpha 会递归自动安装 beta 依赖的 gamma")
    void installsTransitiveRequiredDependenciesFromSameCatalog() {
        server = CatalogTestSupport.startServer();
        CatalogTestSupport.SigningFixture signing = CatalogTestSupport.signingFixture();
        byte[] gamma = CatalogTestSupport.explodedPluginZip("gamma", "1.0.0", null);
        byte[] beta = CatalogTestSupport.explodedPluginZip("beta", "1.0.0", null, "gamma@1.0");
        byte[] alpha = CatalogTestSupport.explodedPluginZip("alpha", "1.0.0", null, "beta@1.0");
        String gammaUrl = servePackage("/gamma.zip", gamma);
        String betaUrl = servePackage("/beta.zip", beta);
        String alphaUrl = servePackage("/alpha.zip", alpha);
        PluginCatalogAcquisitionService service = setUpManifest(signing,
                entryJson("gamma", "1.0.0", gammaUrl, gamma, signing, List.of()),
                entryJson("beta", "1.0.0", betaUrl, beta, signing, List.of("gamma@1.0")),
                entryJson("alpha", "1.0.0", alphaUrl, alpha, signing, List.of("beta@1.0")));

        PluginInstallReport report = service.install("alpha", "1.0.0");

        assertThat(report.outcome()).isEqualTo(PluginInstallOutcome.INSTALLED);
        assertThat(report.pluginId()).isEqualTo("alpha");
        assertThat(installedFiles()).containsExactly(
                "alpha-1.0.0.zip", "beta-1.0.0.zip", "gamma-1.0.0.zip");
        assertThat(report.dependencyInstallResults())
                .extracting(PluginDependencyInstallResult::pluginId)
                .containsExactly("gamma", "beta");
        assertThat(report.dependencyInstallResults())
                .extracting(PluginDependencyInstallResult::outcome)
                .containsExactly("INSTALLED", "INSTALLED");
        assertThat(report.unsatisfiedDependencies()).isEmpty();
        assertThat(report.dependencyProblems()).isEmpty();
    }

    @Test
    @DisplayName("依赖闭环：目标下载失败时异常仍返回已自动安装的依赖结果")
    void targetDownloadFailureKeepsDependencyInstallResults() {
        server = CatalogTestSupport.startServer();
        CatalogTestSupport.SigningFixture signing = CatalogTestSupport.signingFixture();
        byte[] beta = CatalogTestSupport.explodedPluginZip("beta", "1.0.0", null);
        byte[] alpha = CatalogTestSupport.explodedPluginZip("alpha", "1.0.0", null, "beta@1.0");
        String betaUrl = servePackage("/beta.zip", beta);
        CatalogTestSupport.serveStatus(server, "/alpha.zip", 503);
        String alphaUrl = CatalogTestSupport.loopbackUrl(server, "/alpha.zip");
        PluginCatalogAcquisitionService service = setUpManifest(signing,
                entryJson("beta", "1.0.0", betaUrl, beta, signing, List.of()),
                entryJson("alpha", "1.0.0", alphaUrl, alpha, signing, List.of("beta@1.0")));

        PluginCatalogException ex = catchThrowableOfType(
                () -> service.install("alpha", "1.0.0"), PluginCatalogException.class);

        assertThat(ex.code()).isEqualTo(PluginCatalogErrorCode.DOWNLOAD_FAILED);
        assertThat(installedFiles()).containsExactly("beta-1.0.0.zip");
        assertThat(ex.dependencyInstallResults())
                .extracting(PluginDependencyInstallResult::pluginId)
                .containsExactly("beta");
    }

    @Test
    @DisplayName("依赖闭环：descriptor 声明的必需依赖缺失且 catalog 无条目时阻断目标安装")
    void blocksWhenDescriptorDependencyMissingFromCatalog() {
        server = CatalogTestSupport.startServer();
        CatalogTestSupport.SigningFixture signing = CatalogTestSupport.signingFixture();
        byte[] alpha = CatalogTestSupport.explodedPluginZip("alpha", "1.0.0", null, "beta@1.0");
        String alphaUrl = servePackage("/alpha.zip", alpha);
        PluginCatalogAcquisitionService service = setUpManifest(signing,
                entryJson("alpha", "1.0.0", alphaUrl, alpha, signing, List.of()));

        PluginInstallReport report = service.install("alpha", "1.0.0");

        assertThat(report.outcome()).isEqualTo(PluginInstallOutcome.REJECTED_DEPENDENCY);
        assertThat(report.accepted()).isFalse();
        assertThat(report.unsatisfiedDependencies()).containsExactly("beta");
        assertThat(report.dependencyProblems()).singleElement()
                .satisfies(problem -> assertThat(problem.reason())
                        .isEqualTo(top.sywyar.pixivdownload.plugin.install.PluginDependencyProblem.Reason.CATALOG_MISSING));
        assertThat(installedFiles()).isEmpty();
    }

    @Test
    @DisplayName("依赖闭环：beta 已安装且版本满足时安装 alpha 不重复下载依赖")
    void installedDependencyIsNotDownloadedAgain() {
        server = CatalogTestSupport.startServer();
        CatalogTestSupport.SigningFixture signing = CatalogTestSupport.signingFixture();
        byte[] beta = CatalogTestSupport.explodedPluginZip("beta", "1.0.0", null);
        byte[] alpha = CatalogTestSupport.explodedPluginZip("alpha", "1.0.0", null, "beta@1.0");
        AtomicInteger betaDownloads = new AtomicInteger();
        String betaUrl = serveCountingPackage("/beta.zip", beta, betaDownloads);
        String alphaUrl = servePackage("/alpha.zip", alpha);
        PluginCatalogAcquisitionService service = setUpManifest(signing,
                entryJson("beta", "1.0.0", betaUrl, beta, signing, List.of()),
                entryJson("alpha", "1.0.0", alphaUrl, alpha, signing, List.of("beta@1.0")));
        service.install("beta", "1.0.0");

        PluginInstallReport report = service.install("alpha", "1.0.0");

        assertThat(report.outcome()).isEqualTo(PluginInstallOutcome.INSTALLED);
        assertThat(betaDownloads).hasValue(1);
        assertThat(report.dependencyInstallResults()).isEmpty();
        assertThat(installedFiles()).containsExactly("alpha-1.0.0.zip", "beta-1.0.0.zip");
    }

    @Test
    @DisplayName("依赖闭环：同仓库没有满足版本要求的依赖版本时阻断目标安装")
    void blocksWhenCatalogDependencyVersionUnsatisfied() {
        server = CatalogTestSupport.startServer();
        CatalogTestSupport.SigningFixture signing = CatalogTestSupport.signingFixture();
        byte[] beta = CatalogTestSupport.explodedPluginZip("beta", "1.0.0", null);
        byte[] alpha = CatalogTestSupport.explodedPluginZip("alpha", "1.0.0", null, "beta@2.0");
        String betaUrl = servePackage("/beta.zip", beta);
        String alphaUrl = servePackage("/alpha.zip", alpha);
        PluginCatalogAcquisitionService service = setUpManifest(signing,
                entryJson("beta", "1.0.0", betaUrl, beta, signing, List.of()),
                entryJson("alpha", "1.0.0", alphaUrl, alpha, signing, List.of("beta@2.0")));

        PluginInstallReport report = service.install("alpha", "1.0.0");

        assertThat(report.outcome()).isEqualTo(PluginInstallOutcome.REJECTED_DEPENDENCY);
        assertThat(report.unsatisfiedDependencies()).containsExactly("beta");
        assertThat(report.dependencyProblems()).singleElement()
                .satisfies(problem -> assertThat(problem.reason())
                        .isEqualTo(top.sywyar.pixivdownload.plugin.install.PluginDependencyProblem.Reason.CATALOG_VERSION_UNSATISFIED));
        assertThat(installedFiles()).isEmpty();
    }

    @Test
    @DisplayName("依赖闭环：可选依赖缺失不阻断 catalog 安装")
    void optionalDependencyMissingDoesNotBlockCatalogInstall() {
        server = CatalogTestSupport.startServer();
        CatalogTestSupport.SigningFixture signing = CatalogTestSupport.signingFixture();
        byte[] alpha = CatalogTestSupport.explodedPluginZip("alpha", "1.0.0", null, "beta?@1.0");
        String alphaUrl = servePackage("/alpha.zip", alpha);
        PluginCatalogAcquisitionService service = setUpManifest(signing,
                entryJson("alpha", "1.0.0", alphaUrl, alpha, signing, List.of("beta?@1.0")));

        PluginInstallReport report = service.install("alpha", "1.0.0");

        assertThat(report.outcome()).isEqualTo(PluginInstallOutcome.INSTALLED);
        assertThat(report.unsatisfiedDependencies()).isEmpty();
        assertThat(installedFiles()).containsExactly("alpha-1.0.0.zip");
    }

    @Test
    @DisplayName("依赖闭环：catalog 依赖只用于预规划，descriptor 未声明时不阻断安装")
    void catalogDependencyDoesNotOverrideDescriptorAuthority() {
        server = CatalogTestSupport.startServer();
        CatalogTestSupport.SigningFixture signing = CatalogTestSupport.signingFixture();
        byte[] alpha = CatalogTestSupport.explodedPluginZip("alpha", "1.0.0", null);
        String alphaUrl = servePackage("/alpha.zip", alpha);
        PluginCatalogAcquisitionService service = setUpManifest(signing,
                entryJson("alpha", "1.0.0", alphaUrl, alpha, signing, List.of("beta@1.0")));

        PluginInstallReport report = service.install("alpha", "1.0.0");

        assertThat(report.outcome()).isEqualTo(PluginInstallOutcome.INSTALLED);
        assertThat(report.unsatisfiedDependencies()).isEmpty();
        assertThat(installedFiles()).containsExactly("alpha-1.0.0.zip");
    }

    @Test
    @DisplayName("依赖闭环：循环依赖返回明确失败且不安装半成品")
    void dependencyCycleFailsClearly() {
        server = CatalogTestSupport.startServer();
        CatalogTestSupport.SigningFixture signing = CatalogTestSupport.signingFixture();
        byte[] a = CatalogTestSupport.explodedPluginZip("alpha", "1.0.0", null, "beta@1.0");
        byte[] b = CatalogTestSupport.explodedPluginZip("beta", "1.0.0", null, "alpha@1.0");
        String aUrl = servePackage("/alpha.zip", a);
        String bUrl = servePackage("/beta.zip", b);
        PluginCatalogAcquisitionService service = setUpManifest(signing,
                entryJson("alpha", "1.0.0", aUrl, a, signing, List.of("beta@1.0")),
                entryJson("beta", "1.0.0", bUrl, b, signing, List.of("alpha@1.0")));

        PluginInstallReport report = service.install("alpha", "1.0.0");

        assertThat(report.outcome()).isEqualTo(PluginInstallOutcome.REJECTED_DEPENDENCY);
        assertThat(report.dependencyProblems()).singleElement()
                .satisfies(problem -> {
                    assertThat(problem.reason()).isEqualTo(
                            top.sywyar.pixivdownload.plugin.install.PluginDependencyProblem.Reason.CYCLE);
                    assertThat(problem.status()).contains("alpha", "beta");
                });
        assertThat(installedFiles()).isEmpty();
    }

    @Test
    @DisplayName("sha256 不符：REJECTED_INTEGRITY，零落盘，临时文件已清理")
    void sha256Mismatch() {
        byte[] body = CatalogTestSupport.explodedPluginZip("ext", "1.0.0", null);
        PluginCatalogAcquisitionService service = setUpInstall(body,
                (url, signing) -> manifest("ext", "1.0.0", url, body.length, "deadbeefdeadbeef",
                        signing.artifactSignature("ext", "1.0.0", body), signing));

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
                (url, signing) -> manifest("ext", "1.0.0", url, body.length + 100L,
                        CatalogTestSupport.sha256Hex(body), signing.artifactSignature("ext", "1.0.0", body),
                        signing));

        PluginInstallReport report = service.install("ext", "1.0.0");

        assertThat(report.outcome()).isEqualTo(PluginInstallOutcome.REJECTED_INTEGRITY);
        assertThat(installedFiles()).isEmpty();
    }

    @Test
    @DisplayName("受信目录包缺少结构化签名：fail-closed → REJECTED_INTEGRITY（大小 / 哈希正确也拒绝）")
    void signatureFailsClosed() {
        byte[] body = CatalogTestSupport.explodedPluginZip("ext", "1.0.0", null);
        PluginCatalogAcquisitionService service = setUpInstall(body,
                (url, signing) -> manifest("ext", "1.0.0", url, body.length,
                        CatalogTestSupport.sha256Hex(body), null, signing));

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
        CatalogTestSupport.SigningFixture signing = CatalogTestSupport.signingFixture();
        byte[] manifestJson = manifest("ext", "1.0.0", pkgUrl, body.length, CatalogTestSupport.sha256Hex(body),
                signing.artifactSignature("ext", "1.0.0", body), signing)
                .getBytes(StandardCharsets.UTF_8);
        CatalogTestSupport.serveBytes(server, "/strict.json", manifestJson);
        CatalogTestSupport.serveBytes(server, "/strict.json.sig",
                signing.manifestSignatureBytes("strict", manifestJson));
        CatalogTestSupport.serveBytes(server, "/trusted.json", manifestJson);
        CatalogTestSupport.serveBytes(server, "/trusted.json.sig",
                signing.manifestSignatureBytes("trusted", manifestJson));
        PluginCatalogProperties props = new PluginCatalogProperties();
        props.setEnabled(true);
        props.setOfficialRepositoryEnabled(false); // 官方仓库真实 URL 不在 loopback，禁用使默认 = 首个自定义（strict）
        props.setRepositories(List.of(
                repoConfig("strict", CatalogTestSupport.loopbackUrl(server, "/strict.json"), "direct-strict", signing),
                repoConfig("trusted", CatalogTestSupport.loopbackUrl(server, "/trusted.json"), "proxy-trusted", signing)));
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

    @Test
    @DisplayName("package artifact 安装按当前仓库 key 验签：仓库 A 的 key 不能验证仓库 B 的包")
    void packageInstallCannotUseAnotherRepositoryKey() {
        server = CatalogTestSupport.startServer();
        byte[] body = CatalogTestSupport.explodedPluginZip("ext", "1.0.0", null);
        CatalogTestSupport.serveBytes(server, "/pkg.zip", body);
        CatalogTestSupport.SigningFixture repoA = CatalogTestSupport.signingFixture("repo-a-key");
        CatalogTestSupport.SigningFixture repoB = CatalogTestSupport.signingFixture("repo-b-key");
        String pkgUrl = CatalogTestSupport.loopbackUrl(server, "/pkg.zip");
        byte[] manifestJson = manifest("ext", "1.0.0", pkgUrl, body.length,
                CatalogTestSupport.sha256Hex(body), repoA.artifactSignature("ext", "1.0.0", body), repoA)
                .getBytes(StandardCharsets.UTF_8);
        CatalogTestSupport.serveBytes(server, "/b.json", manifestJson);
        CatalogTestSupport.serveBytes(server, "/b.json.sig",
                repoB.manifestSignatureBytes("repo-b", manifestJson));
        PluginCatalogProperties props = new PluginCatalogProperties();
        props.setEnabled(true);
        props.setOfficialRepositoryEnabled(false);
        props.setRepositories(List.of(
                repoConfig("repo-a", CatalogTestSupport.loopbackUrl(server, "/a.json"), "direct-strict", repoA),
                repoConfig("repo-b", CatalogTestSupport.loopbackUrl(server, "/b.json"), "direct-strict", repoB)));
        PluginCatalogAcquisitionService service = acquisition(props);

        PluginInstallReport report = service.install("repo-b", "ext", "1.0.0");

        assertThat(report.outcome()).isEqualTo(PluginInstallOutcome.REJECTED_INTEGRITY);
        assertThat(report.accepted()).isFalse();
        assertThat(installedFiles()).isEmpty();
        assertThat(downloadLeftovers()).isEmpty();
    }

    // ---------- helpers ----------

    private PluginCatalogAcquisitionService setUpInstall(byte[] pkgBytes, ManifestFactory manifestForUrl) {
        server = CatalogTestSupport.startServer();
        CatalogTestSupport.SigningFixture signing = CatalogTestSupport.signingFixture();
        String pkgUrl = CatalogTestSupport.loopbackUrl(server, "/pkg.zip");
        CatalogTestSupport.serveBytes(server, "/pkg.zip", pkgBytes);
        byte[] manifestBytes = manifestForUrl.create(pkgUrl, signing).getBytes(StandardCharsets.UTF_8);
        CatalogTestSupport.serveBytes(server, "/catalog.json", manifestBytes);
        CatalogTestSupport.serveBytes(server, "/catalog.json.sig",
                signing.manifestSignatureBytes("configured", manifestBytes));
        PluginCatalogProperties props = new PluginCatalogProperties();
        props.setEnabled(true);
        props.setManifestUrl(CatalogTestSupport.loopbackUrl(server, "/catalog.json"));
        props.setTrustedKeys(List.of(signing.trustedKeyConfig()));
        return acquisition(props);
    }

    /**
     * 装配编排：catalog 读取与包下载<b>共用同一个</b> {@link PluginCatalogClientProvider}（忠实镜像生产策略语义、放开
     * 非公网以对接 loopback；direct-strict 禁重定向、proxy-trusted 白名单一跳、custom 按开关一跳、未知策略 fail-closed）——证明下载与清单读取
     * 同源、且按仓库代理策略下载。
     */
    private PluginCatalogAcquisitionService acquisition(PluginCatalogProperties props) {
        PluginCatalogClientProvider provider = policyFaithful();
        PluginRepositoryRegistry registry = new PluginRepositoryRegistry(props);
        PluginCatalogService catalogService = new PluginCatalogService(registry, provider);
        PluginPackageDownloader downloader = new PluginPackageDownloader(provider, downloadTempDir);
        ExternalPluginInstaller installer = new ExternalPluginInstaller(
                pluginsDir, PluginPackageLimits.defaults(), PluginCatalogTrustStores.verifierResolver(registry));
        installer.recoverPendingTransactions();
        installers.add(installer);
        PluginDependencyResolver dependencyResolver = new PluginDependencyResolver(installer);
        PluginInstallService installService = installService(installer, dependencyResolver);
        return new PluginCatalogAcquisitionService(catalogService, downloader, installService,
                dependencyResolver);
    }

    private PluginCatalogAcquisitionService acquisition(
            PluginCatalogProperties props,
            PluginInstallService installService,
            PluginDependencyResolver dependencyResolver) {
        PluginCatalogClientProvider provider = policyFaithful();
        PluginRepositoryRegistry registry = new PluginRepositoryRegistry(props);
        PluginCatalogService catalogService = new PluginCatalogService(registry, provider);
        PluginPackageDownloader downloader = new PluginPackageDownloader(provider, downloadTempDir);
        return new PluginCatalogAcquisitionService(
                catalogService, downloader, installService, dependencyResolver);
    }

    private static PluginInstallService installService(
            ExternalPluginInstaller installer, PluginDependencyResolver dependencyResolver) {
        PluginRuntimeManager runtimeManager = mock(PluginRuntimeManager.class);
        PluginLifecycleService lifecycleService = mock(PluginLifecycleService.class);
        RecoveryModeService recoveryModeService = mock(RecoveryModeService.class);
        when(runtimeManager.packagePhases()).thenReturn(Map.of());
        when(lifecycleService.phase(anyString())).thenReturn(Optional.of(PluginRuntimePhase.STARTED));
        when(runtimeManager.loadPlugin(any(Path.class))).thenAnswer(invocation ->
                loadedPackage(installer, invocation.getArgument(0)));
        ExternalPluginLifecycleCoordinator coordinator = new ExternalPluginLifecycleCoordinator(
                runtimeManager, lifecycleService, installer, recoveryModeService, dependencyResolver);
        return new PluginInstallService(coordinator, dependencyResolver);
    }

    private static LoadedPluginPackage loadedPackage(ExternalPluginInstaller installer, Path artifact) {
        InstalledPlugin installed = installer.listInstalled().stream()
                .filter(candidate -> candidate.path().equals(artifact))
                .findFirst()
                .orElseThrow();
        return new LoadedPluginPackage(
                installed.id(), installed.path(), installed.version(), 1L,
                PluginRuntimePackagePhase.LOADED, PluginInventory.empty(), List.of());
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

    private static PluginCatalogProperties.RepositoryConfig repoConfig(String id, String manifestUrl, String policy,
                                                                       CatalogTestSupport.SigningFixture signing) {
        PluginCatalogProperties.RepositoryConfig rc = new PluginCatalogProperties.RepositoryConfig();
        rc.setId(id);
        rc.setManifestUrl(manifestUrl);
        rc.setEnabled(true);
        rc.setProxyPolicy(policy);
        rc.setTrustedKeys(List.of(signing.trustedKeyConfig()));
        return rc;
    }

    private static String manifest(String pluginId, String version, String pkgUrl,
                                   long size, String sha256, SignatureMetadata signature,
                                   CatalogTestSupport.SigningFixture signing) {
        String sig = signature == null ? "" : ",\"signature\":" + signing.signatureJson(signature);
        return "{\"entries\":[{\"pluginId\":\"" + pluginId + "\",\"packages\":[{"
                + "\"version\":\"" + version + "\","
                + "\"packageUrl\":\"" + pkgUrl + "\","
                + "\"expectedSizeBytes\":" + size + ","
                + "\"sha256\":\"" + sha256 + "\"" + sig
                + "}]}]}";
    }

    private String servePackage(String path, byte[] body) {
        CatalogTestSupport.serveBytes(server, path, body);
        return CatalogTestSupport.loopbackUrl(server, path);
    }

    private String serveCountingPackage(String path, byte[] body, AtomicInteger downloads) {
        server.createContext(path, exchange -> {
            downloads.incrementAndGet();
            try {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } finally {
                exchange.close();
            }
        });
        return CatalogTestSupport.loopbackUrl(server, path);
    }

    private PluginCatalogAcquisitionService setUpManifest(CatalogTestSupport.SigningFixture signing,
                                                          String... entries) {
        byte[] manifestBytes = ("{\"entries\":[" + String.join(",", entries) + "]}")
                .getBytes(StandardCharsets.UTF_8);
        CatalogTestSupport.serveBytes(server, "/catalog.json", manifestBytes);
        CatalogTestSupport.serveBytes(server, "/catalog.json.sig",
                signing.manifestSignatureBytes("configured", manifestBytes));
        PluginCatalogProperties props = new PluginCatalogProperties();
        props.setEnabled(true);
        props.setManifestUrl(CatalogTestSupport.loopbackUrl(server, "/catalog.json"));
        props.setTrustedKeys(List.of(signing.trustedKeyConfig()));
        return acquisition(props);
    }

    private static String entryJson(String pluginId, String version, String pkgUrl, byte[] body,
                                    CatalogTestSupport.SigningFixture signing,
                                    List<String> dependencies) {
        SignatureMetadata signature = signing.artifactSignature(pluginId, version, body);
        return "{\"pluginId\":\"" + pluginId + "\",\"packages\":[{"
                + "\"version\":\"" + version + "\","
                + "\"packageUrl\":\"" + pkgUrl + "\","
                + "\"expectedSizeBytes\":" + body.length + ","
                + "\"sha256\":\"" + CatalogTestSupport.sha256Hex(body) + "\","
                + "\"dependencies\":" + dependenciesJson(dependencies) + ","
                + "\"signature\":" + signing.signatureJson(signature)
                + "}]}";
    }

    private static String dependenciesJson(List<String> dependencies) {
        return dependencies.stream()
                .map(dependency -> "\"" + dependency + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    @FunctionalInterface
    private interface ManifestFactory {
        String create(String packageUrl, CatalogTestSupport.SigningFixture signing);
    }

    private List<String> installedFiles() {
        if (!Files.isDirectory(pluginsDir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(pluginsDir)) {
            return stream.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> !n.startsWith("."))
                    .filter(n -> n.endsWith(".jar") || n.endsWith(".zip"))
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
