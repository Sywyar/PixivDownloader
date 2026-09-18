package top.sywyar.pixivdownload.plugin.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.market.*;
import top.sywyar.pixivdownload.plugin.catalog.error.*;
import top.sywyar.pixivdownload.plugin.catalog.manifest.*;
import top.sywyar.pixivdownload.plugin.catalog.page.*;
import top.sywyar.pixivdownload.plugin.catalog.repository.*;
import top.sywyar.pixivdownload.plugin.catalog.trust.*;
import top.sywyar.pixivdownload.plugin.management.PluginStatusService;
import top.sywyar.pixivdownload.plugin.install.PluginDependencyResolver;
import top.sywyar.pixivdownload.plugin.install.PluginInstallReport;
import top.sywyar.pixivdownload.plugin.install.PluginInstallService;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginInstallOutcome;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageOrigin;
import top.sywyar.pixivdownload.plugin.runtime.admission.PluginArtifactAdmissionRequest;
import top.sywyar.pixivdownload.plugin.runtime.status.PluginStatusReport;
import top.sywyar.pixivdownload.plugin.signature.*;
import top.sywyar.pixivdownload.plugin.signature.internal.envelope.EnvelopeV1Codec;

import java.nio.file.Path;
import java.nio.file.Files;
import java.security.*;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("市场、安装与离线启动共享已验签撤销状态")
class PluginCatalogRevocationIntegrationTest {
    @TempDir Path temp;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, byte[]> responses = new HashMap<>();
    private final PluginCatalogService catalog = mock(PluginCatalogService.class);
    private final PluginStatusService installed = mock(PluginStatusService.class);
    private final PluginRepositoryRegistry registry = mock(PluginRepositoryRegistry.class);
    private KeyPair key;
    private PluginRepository repository;
    private PluginCatalogTrustStateStore store;
    private PluginCatalogRevocationService revocations;
    private PluginMarketService market;
    private PluginCatalogEntry entry;

    @BeforeEach
    void prepare() throws Exception {
        key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var trust = new TrustedPluginKey("test-key", "Ed25519",
                Base64.getEncoder().encodeToString(key.getPublic().getEncoded()),
                TrustedPluginKey.State.ACTIVE, "Sample", "Sample", false);
        String url = "https://catalog.example/manifest.json";
        repository = new PluginRepository("sample.repo", null, url, true, false, false,
                RepositoryProxyPolicy.DIRECT_STRICT, "direct-strict", false, true, false, false,
                1000, 1000, 1024 * 1024, 1024 * 1024, List.of(trust),
                null, null, null, "publisher", null, "manifest-v1", url,
                "https://catalog.example/revocations.json", null, "SELF_TRUSTED", null, null);
        when(registry.featureEnabled()).thenReturn(true);
        when(registry.find(repository.repositoryId())).thenReturn(Optional.of(repository));
        when(registry.defaultRepository()).thenReturn(Optional.of(repository));
        when(installed.report()).thenReturn(PluginStatusReport.empty());
        var http = mock(PluginCatalogHttpClient.class);
        when(http.fetchBytes(anyString(), anyLong())).thenAnswer(call -> {
            var bytes = responses.get(call.getArgument(0, String.class));
            if (bytes == null) throw new PluginCatalogException(PluginCatalogErrorCode.CATALOG_UNAVAILABLE, "offline");
            assertThat(bytes.length).isLessThanOrEqualTo(call.getArgument(1, Long.class).intValue());
            return bytes;
        });
        store = new PluginCatalogTrustStateStore(temp.resolve("trust.json"));
        revocations = new PluginCatalogRevocationService(ignored -> http, store, registry);
        market = new PluginMarketService(registry, catalog, mock(PluginCatalogAcquisitionService.class), installed, revocations);
        entry = new PluginCatalogEntry("demo", "demo", "name", null, null, List.of(pkg("2.0.0", "ab"), pkg("1.0.0", "cd")));
        when(catalog.loadPage(eq(repository.repositoryId()), any())).thenAnswer(ignored ->
                new PluginCatalogPage("catalog", List.of(entry), null, 1L, Map.of("utility", 1L), false));
        when(catalog.loadEntryPage(repository.repositoryId(), "demo", null, 24)).thenAnswer(ignored ->
                new PluginCatalogDetailPage(entry, "catalog", null, (long) entry.packages().size(), false));
    }

    @Test
    @DisplayName("首次浏览取回状态，隐藏最新版本保留旧版本，恢复与全部隐藏同步更新列表和详情")
    void publishedYankedRestoredAndEmpty() throws Exception {
        publish(1, List.of(), Instant.now().plusSeconds(3600));
        assertThat(market.catalog(repository.repositoryId()).entries().get(0).latestVersion()).isEqualTo("2.0.0");
        publish(2, List.of(restriction("ab", "YANKED")), Instant.now().plusSeconds(3600));
        var visible = market.catalog(repository.repositoryId());
        assertThat(visible.entries()).singleElement().satisfies(item -> assertThat(item.latestVersion()).isEqualTo("1.0.0"));
        var detail = market.pluginDetail(repository.repositoryId(), "demo");
        assertThat(detail.packages().get(0).verification().revocationStatus()).isEqualTo("YANKED");
        assertThat(detail.packages().get(0).installable()).isFalse();
        assertThat(detail.packages().get(1).installable()).isTrue();
        assertThatThrownBy(() -> revocations.requireInstallAllowed(repository, "demo", entry.packages().get(0)))
                .isInstanceOfSatisfying(PluginCatalogException.class, ex -> assertThat(ex.code()).isEqualTo(PluginCatalogErrorCode.REVOCATION_REJECTED));
        var admission = new PluginCatalogRevocationAdmissionPolicy(registry, store);
        assertThat(admission.evaluate(request("ab")).allowed()).isTrue();
        publish(3, List.of(), Instant.now().plusSeconds(3600));
        assertThat(market.catalog(repository.repositoryId()).entries().get(0).latestVersion()).isEqualTo("2.0.0");
        publish(4, List.of(restriction("ab", "YANKED"), restriction("cd", "REVOKED")), Instant.now().plusSeconds(3600));
        var empty = market.catalog(repository.repositoryId());
        assertThat(empty.entries()).isEmpty();
        assertThat(empty.totalApproximate()).isZero();
        assertThat(empty.categories().get(0).count()).isZero();
        assertThat(empty.facets()).isEmpty();
        assertThat(market.pluginDetail(repository.repositoryId(), "demo").packages()).hasSize(2);
        assertThat(admission.evaluate(request("cd")).allowed()).isFalse();
    }

    @Test
    @DisplayName("无快照时仍可诊断但禁用安装，断网与伪造签名不清除已知撤销")
    void unavailableAndUntrustedSnapshots() throws Exception {
        var unknown = market.catalog(repository.repositoryId()).entries().get(0);
        assertThat(unknown.installStatus()).isEqualTo(MarketInstallStatus.UNAVAILABLE);
        assertThat(unknown.packages()).allSatisfy(pkg -> {
            assertThat(pkg.verification().revocationStatus()).isEqualTo("NOT_CHECKED");
            assertThat(pkg.installable()).isFalse();
        });
        publish(2, List.of(restriction("ab", "REVOKED")), Instant.now().plusSeconds(3600));
        market.catalog(repository.repositoryId());
        responses.clear();
        assertThat(market.catalog(repository.repositoryId()).entries().get(0).latestVersion()).isEqualTo("1.0.0");
        publish(3, List.of(), Instant.now().plusSeconds(3600));
        responses.put(repository.revocationsUrl() + ".sig", mapper.writeValueAsBytes(new SignatureMetadata(1, "Ed25519", "test-key", "AAAA")));
        assertThat(market.catalog(repository.repositoryId()).entries().get(0).latestVersion()).isEqualTo("1.0.0");
        assertThat(store.revocations(repository.repositoryId()).orElseThrow().sequence()).isEqualTo(2);
        publish(1, List.of(), Instant.now().plusSeconds(3600));
        assertThat(market.catalog(repository.repositoryId()).entries().get(0).latestVersion()).isEqualTo("1.0.0");
    }

    @Test
    @DisplayName("服务器成功返回过期文档也受宽限期约束，过期撤销仍阻断离线启动")
    void expiryIsCheckedAfterSuccessfulDownload() throws Exception {
        publish(1, List.of(), Instant.now().minusSeconds(3600));
        assertThat(market.catalog(repository.repositoryId()).entries().get(0).packages().get(0).installable()).isTrue();
        revocations.requireInstallAllowed(repository, "demo", entry.packages().get(0));
        publish(2, List.of(restriction("cd", "REVOKED")), Instant.now().minusSeconds(2 * 86400));
        var detail = market.pluginDetail(repository.repositoryId(), "demo");
        assertThat(detail.packages().get(0).verification().revocationStatus()).isEqualTo("STALE");
        assertThat(detail.packages()).noneMatch(PluginMarketPackageView::installable);
        assertThatThrownBy(() -> revocations.requireCurrent(repository)).isInstanceOfSatisfying(
                PluginCatalogException.class, ex -> assertThat(ex.code()).isEqualTo(PluginCatalogErrorCode.REVOCATION_UNAVAILABLE));
        assertThat(new PluginCatalogRevocationAdmissionPolicy(registry, store).evaluate(request("cd")).allowed()).isFalse();
    }

    private PluginCatalogPackage pkg(String version, String hash) {
        return new PluginCatalogPackage(version, "https://catalog.example/" + version + ".jar", 100L, hash.repeat(32),
                new SignatureMetadata(1, "Ed25519", "test-key", "AAAA"), null, "*", List.of(), null, List.of(), null, false);
    }

    @Test
    @DisplayName("拒绝目标先于依赖副作用，依赖跳过隐藏版本，下载后撤销不进入安装器")
    void acquisitionRechecksRestrictionsAndSelectsAllowedDependency() throws Exception {
        var dependencyEntry = new PluginCatalogEntry("dependency", "dependency", "name", null, null,
                List.of(pkg("1.2.0", "ef"), pkg("1.1.0", "ac")));
        var target = new PluginCatalogPackage("2.0.0", "https://catalog.example/target.jar", 100L, "ab".repeat(32),
                entry.packages().get(0).signature(), null, "*", List.of("dependency@1.0"), null, List.of(), null, false);
        var targetEntry = new PluginCatalogEntry("demo", "demo", "name", null, null, List.of(target));
        var manifest = new PluginCatalogManifest("1", null, List.of(targetEntry, dependencyEntry));
        when(catalog.load(repository.repositoryId())).thenReturn(manifest);
        when(catalog.resolvePackage(eq(repository.repositoryId()), anyString(), anyString())).thenAnswer(call -> {
            var selected = manifest.findEntry(call.getArgument(1)).orElseThrow();
            return new PluginCatalogService.ResolvedPackage(repository, selected, selected.findPackage(call.getArgument(2)).orElseThrow());
        });
        var downloader = mock(PluginPackageDownloader.class);
        var installer = mock(PluginInstallService.class);
        var dependencies = mock(PluginDependencyResolver.class);
        var acquisition = new PluginCatalogAcquisitionService(catalog, downloader, installer, dependencies, revocations);
        publish(1, List.of(restriction("ab", "YANKED")), Instant.now().plusSeconds(3600));
        assertThatThrownBy(() -> acquisition.install(repository.repositoryId(), "demo", "2.0.0"))
                .isInstanceOf(PluginCatalogException.class);
        verifyNoInteractions(downloader, installer);
        List<String> downloads = new ArrayList<>();
        when(downloader.downloadToTemp(eq(repository), any())).thenAnswer(call -> {
            var pkg = call.getArgument(1, PluginCatalogPackage.class);
            downloads.add(pkg.version());
            Path file = Files.createTempFile(temp, "download-", ".jar");
            Files.write(file, new byte[] { 1 });
            return file;
        });
        when(installer.installTrustedFile(any(), eq(false), any())).thenAnswer(call -> {
            var origin = call.getArgument(2, PluginPackageOrigin.class);
            return new PluginInstallReport(PluginInstallOutcome.INSTALLED, true, true, origin.expectedPluginId(),
                    origin.expectedVersion(), null, List.of(), List.of(), List.of());
        });
        publish(2, List.of(restriction("ef", "YANKED")), Instant.now().plusSeconds(3600));
        assertThat(acquisition.install(repository.repositoryId(), "demo", "2.0.0").accepted()).isTrue();
        assertThat(downloads).containsExactly("1.1.0", "2.0.0");
        clearInvocations(installer);
        when(dependencies.installedDependencySatisfied(any())).thenReturn(true);
        var frozen = temp.resolve("download-race.jar");
        when(downloader.downloadToTemp(eq(repository), any())).thenAnswer(call -> {
            Files.write(frozen, new byte[] { 1 });
            publish(3, List.of(restriction("ab", "REVOKED")), Instant.now().plusSeconds(3600));
            return frozen;
        });
        assertThatThrownBy(() -> acquisition.install(repository.repositoryId(), "demo", "2.0.0"))
                .isInstanceOfSatisfying(PluginCatalogException.class, ex -> assertThat(ex.code()).isEqualTo(PluginCatalogErrorCode.REVOCATION_REJECTED));
        verifyNoInteractions(installer);
        assertThat(frozen).doesNotExist();
    }

    private Map<String, Object> restriction(String hash, String action) {
        return Map.of("scope", "PACKAGE_SHA256", "packageSha256", hash.repeat(32), "action", action,
                "reasonCode", "MAINTAINER_WITHDRAWAL", "effectiveTime", Instant.now().minusSeconds(60).toString());
    }

    private PluginArtifactAdmissionRequest request(String hash) {
        return new PluginArtifactAdmissionRequest(repository.repositoryId(), "demo", "1.0.0", hash.repeat(32), "test-key", "publisher");
    }

    private void publish(long sequence, List<Map<String, Object>> entries, Instant expiry) throws Exception {
        byte[] bytes = mapper.writeValueAsBytes(Map.of("schemaVersion", 1, "repositoryId", repository.repositoryId(),
                "sequence", sequence, "generatedTime", expiry.minusSeconds(30 * 86400).toString(),
                "nextUpdate", expiry.toString(), "entries", entries));
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(key.getPrivate());
        signer.update(EnvelopeV1Codec.pluginRevocationsMessage(repository.repositoryId(), sequence, bytes.length,
                MessageDigest.getInstance("SHA-256").digest(bytes)));
        responses.put(repository.revocationsUrl(), bytes);
        responses.put(repository.revocationsUrl() + ".sig", mapper.writeValueAsBytes(new SignatureMetadata(
                1, "Ed25519", "test-key", Base64.getEncoder().encodeToString(signer.sign()))));
    }
}
