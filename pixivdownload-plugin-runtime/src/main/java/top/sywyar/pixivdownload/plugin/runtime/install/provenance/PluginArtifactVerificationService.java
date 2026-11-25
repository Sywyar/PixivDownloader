package top.sywyar.pixivdownload.plugin.runtime.install.provenance;

import top.sywyar.pixivdownload.plugin.runtime.artifact.PluginDevelopmentArtifacts;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageOrigin;
import top.sywyar.pixivdownload.plugin.signature.ArtifactVerificationRequest;
import top.sywyar.pixivdownload.plugin.signature.community.CommunityPackageVerificationRequest;
import top.sywyar.pixivdownload.plugin.signature.IdentityMigrationVerificationRequest;
import top.sywyar.pixivdownload.plugin.signature.PluginSupplyChainVerifier;
import top.sywyar.pixivdownload.plugin.signature.SignatureMetadata;
import top.sywyar.pixivdownload.plugin.signature.VerificationPolicy;
import top.sywyar.pixivdownload.plugin.signature.VerificationResult;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * 运行期把包描述符 / provenance 转换为签名模块公开门面的薄适配器。
 */
public final class PluginArtifactVerificationService {

    private final Function<PluginPackageOrigin, PluginSupplyChainVerifier> verifierResolver;
    private final BooleanSupplier developmentModeEnabled;

    public PluginArtifactVerificationService(PluginSupplyChainVerifier verifier) {
        this(origin -> Objects.requireNonNull(verifier, "verifier"), PluginDevelopmentArtifacts::enabled);
    }

    public PluginArtifactVerificationService(
            Function<PluginPackageOrigin, PluginSupplyChainVerifier> verifierResolver) {
        this(verifierResolver, PluginDevelopmentArtifacts::enabled);
    }

    public PluginArtifactVerificationService(
            Function<PluginPackageOrigin, PluginSupplyChainVerifier> verifierResolver,
            BooleanSupplier developmentModeEnabled) {
        this.verifierResolver = Objects.requireNonNull(verifierResolver, "verifierResolver");
        this.developmentModeEnabled = Objects.requireNonNull(developmentModeEnabled, "developmentModeEnabled");
    }

    public VerificationResult verifyForInstall(Path artifact, PluginDescriptor descriptor, PluginPackageOrigin origin) {
        PluginPackageOrigin effectiveOrigin = origin != null ? origin : PluginPackageOrigin.localUpload();
        if (effectiveOrigin.communityEvidence() != null) return verifyCommunity(artifact, descriptor, effectiveOrigin, false, null);
        return verifierFor(effectiveOrigin).verifyArtifact(new ArtifactVerificationRequest(
                artifact,
                descriptor.id(),
                descriptor.version(),
                effectiveOrigin.expectedSizeBytes(),
                effectiveOrigin.expectedSha256(),
                effectiveOrigin.signature(),
                effectiveOrigin.verificationPolicy(developmentModeEnabled.getAsBoolean())));
    }

    public VerificationResult verifyInstalled(Path artifact, PluginDescriptor descriptor,
                                              PluginProvenanceRecord provenance) {
        return verifyInstalled(artifact, descriptor, provenance, null);
    }

    public VerificationResult verifyInventoryEntry(InstalledPluginSnapshot snapshot) {
        return verifyInstalled(snapshot.plugin().path(), snapshot.plugin().descriptor(), snapshot.provenance(),
                new PluginSupplyChainVerifier.MeasuredArtifact(snapshot.artifactSizeBytes(), snapshot.artifactSha256()));
    }

    private VerificationResult verifyInstalled(Path artifact, PluginDescriptor descriptor, PluginProvenanceRecord provenance,
                                               PluginSupplyChainVerifier.MeasuredArtifact measured) {
        PluginPackageOrigin origin = provenance == null ? null : provenance.originForOfflineVerification();
        if (origin != null && origin.communityEvidence() != null) return verifyCommunity(artifact, descriptor, origin, true, measured);
        var request = new ArtifactVerificationRequest(artifact, descriptor.id(), descriptor.version(),
                provenance == null ? null : provenance.artifactSizeBytes(), provenance == null ? null : provenance.artifactSha256(),
                origin == null ? null : origin.signature(), origin == null ? VerificationPolicy.installedCustom()
                        : origin.installedVerificationPolicy(developmentModeEnabled.getAsBoolean()));
        var verifier = verifierFor(origin);
        return measured == null ? verifier.verifyArtifact(request) : verifier.verifyArtifact(request, measured);
    }

    private VerificationResult verifyCommunity(Path artifact, PluginDescriptor descriptor, PluginPackageOrigin origin, boolean installed,
                                                PluginSupplyChainVerifier.MeasuredArtifact measured) {
        var evidence = origin.communityEvidence();
        var request = new CommunityPackageVerificationRequest(artifact, origin.repositoryId(), descriptor.id(),
                descriptor.version(), origin.expectedSizeBytes(), origin.expectedSha256(), evidence.assuranceLevel(),
                evidence.sourceCommit(), evidence.reviewSha256(), origin.signature(), installed);
        var verifier = verifierFor(origin);
        return measured == null ? verifier.verifyCommunityPackage(request) : verifier.verifyCommunityPackage(request, measured);
    }

    public VerificationResult verifyIdentityMigration(
            String installedPluginId,
            PluginProvenanceRecord installed,
            String candidatePluginId,
            String candidateVersion,
            PluginProvenanceRecord candidate,
            SignatureMetadata authorization) {
        PluginPackageOrigin installedOrigin = installed.originForOfflineVerification();
        return verifierFor(installedOrigin).verifyIdentityMigration(new IdentityMigrationVerificationRequest(
                identity(installedPluginId, installed),
                identity(candidatePluginId, candidate),
                candidateVersion,
                candidate.artifactSizeBytes(),
                candidate.artifactSha256(),
                authorization,
                installedOrigin.installedVerificationPolicy(developmentModeEnabled.getAsBoolean())));
    }

    private static IdentityMigrationVerificationRequest.Identity identity(
            String pluginId, PluginProvenanceRecord provenance) {
        return new IdentityMigrationVerificationRequest.Identity(
                pluginId,
                provenance.source().name(),
                provenance.repositoryId(),
                provenance.officialRepository(),
                provenance.publisher(),
                provenance.keyId());
    }

    private PluginSupplyChainVerifier verifierFor(PluginPackageOrigin origin) {
        return Objects.requireNonNull(verifierResolver.apply(origin), "verifierResolver returned null");
    }
}
