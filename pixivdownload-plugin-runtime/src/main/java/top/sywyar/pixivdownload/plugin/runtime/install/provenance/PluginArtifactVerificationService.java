package top.sywyar.pixivdownload.plugin.runtime.install.provenance;

import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageOrigin;
import top.sywyar.pixivdownload.plugin.signature.ArtifactVerificationRequest;
import top.sywyar.pixivdownload.plugin.signature.PluginSupplyChainVerifier;
import top.sywyar.pixivdownload.plugin.signature.VerificationPolicy;
import top.sywyar.pixivdownload.plugin.signature.VerificationResult;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Function;

/**
 * 运行期把包描述符 / provenance 转换为签名模块公开门面的薄适配器。
 */
public final class PluginArtifactVerificationService {

    private final Function<PluginPackageOrigin, PluginSupplyChainVerifier> verifierResolver;

    public PluginArtifactVerificationService(PluginSupplyChainVerifier verifier) {
        this(origin -> Objects.requireNonNull(verifier, "verifier"));
    }

    public PluginArtifactVerificationService(
            Function<PluginPackageOrigin, PluginSupplyChainVerifier> verifierResolver) {
        this.verifierResolver = Objects.requireNonNull(verifierResolver, "verifierResolver");
    }

    public VerificationResult verifyForInstall(Path artifact, PluginDescriptor descriptor, PluginPackageOrigin origin) {
        PluginPackageOrigin effectiveOrigin = origin != null ? origin : PluginPackageOrigin.localUpload();
        return verifierFor(effectiveOrigin).verifyArtifact(new ArtifactVerificationRequest(
                artifact,
                descriptor.id(),
                descriptor.version(),
                effectiveOrigin.expectedSizeBytes(),
                effectiveOrigin.expectedSha256(),
                effectiveOrigin.signature(),
                effectiveOrigin.verificationPolicy()));
    }

    public VerificationResult verifyInstalled(Path artifact, PluginDescriptor descriptor,
                                              PluginProvenanceRecord provenance) {
        if (provenance == null) {
            return verifierFor(null).verifyArtifact(new ArtifactVerificationRequest(
                    artifact,
                    descriptor.id(),
                    descriptor.version(),
                    null,
                    null,
                    null,
                    VerificationPolicy.installedCustom()));
        }
        PluginPackageOrigin origin = provenance.originForOfflineVerification();
        return verifierFor(origin).verifyArtifact(new ArtifactVerificationRequest(
                artifact,
                descriptor.id(),
                descriptor.version(),
                provenance.artifactSizeBytes(),
                provenance.artifactSha256(),
                origin.signature(),
                origin.installedVerificationPolicy()));
    }

    private PluginSupplyChainVerifier verifierFor(PluginPackageOrigin origin) {
        return Objects.requireNonNull(verifierResolver.apply(origin), "verifierResolver returned null");
    }
}
