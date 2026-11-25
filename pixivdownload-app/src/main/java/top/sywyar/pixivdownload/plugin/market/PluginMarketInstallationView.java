package top.sywyar.pixivdownload.plugin.market;

import top.sywyar.pixivdownload.plugin.catalog.manifest.PluginCatalogPackage;
import top.sywyar.pixivdownload.plugin.catalog.repository.PluginRepository;
import io.swagger.v3.oas.annotations.media.Schema;
import top.sywyar.pixivdownload.plugin.api.web.HttpApiNullable;

import top.sywyar.pixivdownload.plugin.runtime.install.provenance.InstalledPluginSnapshot;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.ProvenanceSnapshotState;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageVersion;

/** 本机磁盘包与运行实例分别投影；内容相同不代表来源、信任或激活状态相同。 */
public record PluginMarketInstallationView(
        @Schema(allowableValues = {"UNKNOWN", "ABSENT", "PRESENT"}) String state, @HttpApiNullable String version, @HttpApiNullable String sha256, String source,
        @HttpApiNullable String repositoryId,
        @HttpApiNullable String runtimeVersion, @HttpApiNullable String runtimeStatus, boolean installedArtifactsEnabled) {

    static PluginMarketInstallationView unknown(String version) {
        return unknown(version, true);
    }

    static PluginMarketInstallationView unknown(String version, boolean enabled) {
        return new PluginMarketInstallationView("UNKNOWN", version, null, "unknown", null,
                null, null, enabled);
    }

    PluginMarketInstallationView withRuntime(String runtimeVersion, String runtimeStatus) {
        return new PluginMarketInstallationView(state, version, sha256, source, repositoryId,
                runtimeVersion, runtimeStatus, installedArtifactsEnabled);
    }

    static PluginMarketInstallationView from(InstalledPluginSnapshot installed,
            String runtimeVersion, String runtimeStatus, boolean installedArtifactsEnabled) {
        if (installed == null) {
            return new PluginMarketInstallationView("ABSENT", null, null, "unknown", null,
                    runtimeVersion, runtimeStatus, installedArtifactsEnabled);
        }
        var provenance = installed.provenance();
        boolean bound = installed.provenanceState() == ProvenanceSnapshotState.PRESENT
                && provenance.artifactSha256().equals(installed.artifactSha256())
                && provenance.artifactSizeBytes() == installed.artifactSizeBytes();
        return new PluginMarketInstallationView("PRESENT", installed.plugin().version(), installed.artifactSha256(),
                bound ? provenance.source().name() : "unknown", bound ? provenance.repositoryId() : null,
                runtimeVersion, runtimeStatus, installedArtifactsEnabled);
    }

    PluginCatalogPackage selectArtifact(PluginRepository repository, String pluginId, PluginCatalogPackage pkg) {
        return pkg.forInstalledArtifact(repository, pluginId, version, sha256);
    }

    String comparison(String marketVersion, String marketSha256) {
        if ("ABSENT".equals(state)) return "NOT_INSTALLED";
        if ("UNKNOWN".equals(state)) return "UNKNOWN";
        if (version == null) return "UNKNOWN";
        if (!PluginPackageVersion.samePrecedence(version, marketVersion)) return "DIFFERENT_VERSION";
        if (sha256 == null || marketSha256 == null || !marketSha256.matches("(?i)[0-9a-f]{64}")) return "UNKNOWN";
        return sha256.equalsIgnoreCase(marketSha256) ? "SAME_ARTIFACT" : "DIFFERENT_ARTIFACT";
    }
}
