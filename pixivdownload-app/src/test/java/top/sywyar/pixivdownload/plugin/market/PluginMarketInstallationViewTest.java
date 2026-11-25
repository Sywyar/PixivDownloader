package top.sywyar.pixivdownload.plugin.market;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.install.model.InstalledPlugin;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageSource;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.InstalledPluginSnapshot;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.PluginProvenanceRecord;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.ProvenanceSnapshotState;

import top.sywyar.pixivdownload.plugin.PluginTestProvenance;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogTrustStores;
import top.sywyar.pixivdownload.plugin.catalog.manifest.PluginCatalogPackage;
import top.sywyar.pixivdownload.plugin.catalog.repository.PluginRepository;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("市场包内容比较与本机来源分离")
class PluginMarketInstallationViewTest {
    private static final String HASH = "a".repeat(64);

    @Test
    @DisplayName("缺失来源记录不妨碍字节比较，但不能据此猜测本地上传或继承市场审核")
    void digestComparisonDoesNotInferSource() {
        var local = PluginMarketInstallationView.from(snapshot(null), "6.0.0", "STARTED", true);
        assertThat(local.version()).isEqualTo("7.0.0");
        assertThat(local.runtimeVersion()).isEqualTo("6.0.0");
        assertThat(local.source()).isEqualTo("unknown");
        assertThat(local.comparison("7.0.0", HASH.toUpperCase())).isEqualTo("SAME_ARTIFACT");
        assertThat(local.comparison("7.0.0", "b".repeat(64))).isEqualTo("DIFFERENT_ARTIFACT");
        assertThat(local.comparison("7.0.0", null)).isEqualTo("UNKNOWN");
        assertThat(local.comparison("8.0.0", HASH)).isEqualTo("DIFFERENT_VERSION");
        assertThat(PluginMarketInstallationView.unknown("7.0.0").comparison("7.0.0", HASH)).isEqualTo("UNKNOWN");
        assertThat(PluginMarketInstallationView.from(null, "7.0.0", "STARTED", false)
                .comparison("7.0.0", HASH)).isEqualTo("NOT_INSTALLED");
    }

    @Test
    void installerVariantRequiresMatchingBytesAndItsOwnOfficialSignature() {
        var local = PluginMarketInstallationView.from(snapshot(null), null, null, true);
        var repo = mock(PluginRepository.class);
        when(repo.official()).thenReturn(true);
        String universalHash = "b".repeat(64);
        var universalSignature = PluginTestProvenance.signArtifact("sample", local.version(), 200, universalHash);
        var installerSignature = PluginTestProvenance.signArtifact("sample", local.version(), 100, HASH);
        var mapper = JsonMapper.builder().build();
        var json = mapper.valueToTree(Map.of("version", local.version(), "deprecated", false, "expectedSizeBytes", 200, "sha256", universalHash,
                "signature", universalSignature, "installerArtifacts", Map.of("windows-x64", Map.of(
                        "expectedSizeBytes", 100, "sha256", HASH, "signature", installerSignature))));
        var pkg = mapper.treeToValue(json, PluginCatalogPackage.class);
        assertThat(pkg.signature()).isEqualTo(universalSignature);
        assertThat(pkg.installerArtifacts().get("windows-x64").signature()).isEqualTo(installerSignature);
        try (var trust = org.mockito.Mockito.mockStatic(PluginCatalogTrustStores.class)) {
            trust.when(() -> PluginCatalogTrustStores.verifierForRepository(repo)).thenReturn(PluginTestProvenance.verifier());
            assertThat(local.selectArtifact(repo, "sample", pkg)).extracting(PluginCatalogPackage::sha256, PluginCatalogPackage::expectedSizeBytes).containsExactly(HASH, 100L);
            assertThat(local.selectArtifact(repo, "other-id", pkg)).isSameAs(pkg);
            var unknown = new PluginMarketInstallationView("PRESENT", local.version(), "c".repeat(64), "unknown",
                    null, null, null, true);
            assertThat(unknown.selectArtifact(repo, "sample", pkg)).isSameAs(pkg);
            var variant = (tools.jackson.databind.node.ObjectNode) json.at("/installerArtifacts/windows-x64");
            variant.set("signature", json.get("signature"));
            var tampered = mapper.treeToValue(json, PluginCatalogPackage.class);
            assertThat(local.selectArtifact(repo, "sample", tampered)).isSameAs(tampered);
            when(repo.official()).thenReturn(false);
            assertThat(local.selectArtifact(repo, "sample", pkg)).isSameAs(pkg);
        }
    }

    @Test
    @DisplayName("来源只消费与当前包大小和摘要绑定的记录")
    void provenanceMustBindCurrentBytes() {
        var provenance = mock(PluginProvenanceRecord.class);
        when(provenance.artifactSha256()).thenReturn(HASH);
        when(provenance.artifactSizeBytes()).thenReturn(100L);
        when(provenance.source()).thenReturn(PluginPackageSource.MARKET_CATALOG);
        when(provenance.repositoryId()).thenReturn("trusted-repo");
        var local = PluginMarketInstallationView.from(snapshot(provenance), null, null, true);
        assertThat(local.source()).isEqualTo("MARKET_CATALOG");
        assertThat(local.repositoryId()).isEqualTo("trusted-repo");
        when(provenance.artifactSha256()).thenReturn("b".repeat(64));
        var stale = PluginMarketInstallationView.from(snapshot(provenance), null, null, true);
        assertThat(stale.source()).isEqualTo("unknown");
        assertThat(stale.repositoryId()).isNull();
        assertThat(stale.comparison("7.0.0", HASH)).isEqualTo("SAME_ARTIFACT");
    }

    private InstalledPluginSnapshot snapshot(PluginProvenanceRecord provenance) {
        var descriptor = mock(PluginDescriptor.class);
        when(descriptor.version()).thenReturn("7.0.0");
        return new InstalledPluginSnapshot(new InstalledPlugin(descriptor, Path.of("plugins/sample.jar")),
                100, HASH, provenance == null ? ProvenanceSnapshotState.ABSENT : ProvenanceSnapshotState.PRESENT,
                provenance, provenance == null ? 0 : 100);
    }
}
