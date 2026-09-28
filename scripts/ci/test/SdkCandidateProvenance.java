import com.fasterxml.jackson.databind.ObjectMapper;
import top.sywyar.pixivdownload.common.Utf8ConsoleStreams;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageOrigin;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.PluginProvenanceRecord;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.PluginProvenanceStore;
import top.sywyar.pixivdownload.plugin.runtime.install.trust.PluginTrustPolicy;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageReader;
import top.sywyar.pixivdownload.plugin.signature.ArtifactVerificationRequest;
import top.sywyar.pixivdownload.plugin.signature.PluginSupplyChainVerifier;
import top.sywyar.pixivdownload.plugin.signature.PluginTrustStores;
import top.sywyar.pixivdownload.plugin.signature.SignatureMetadata;
import top.sywyar.pixivdownload.plugin.signature.TrustedPluginKey;
import top.sywyar.pixivdownload.plugin.signature.VerificationPolicy;
import top.sywyar.pixivdownload.plugin.signature.VerificationStatus;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/** 仅供 CI 使用：按普通自定义仓库策略验签，并由运行时生成真实的执行确认。 */
class SdkCandidateProvenance {
    public static void main(String[] args) throws Exception {
        Utf8ConsoleStreams.install();
        Path layout = Path.of(args[0]);
        var key = new TrustedPluginKey(args[1], SignatureMetadata.ED25519, args[2],
                TrustedPluginKey.State.ACTIVE, "SDK CI", "Ephemeral SDK candidate", false);
        var verifier = new PluginSupplyChainVerifier(PluginTrustStores.of(List.of(key)));
        var mapper = new ObjectMapper();
        var store = new PluginProvenanceStore(layout.resolve("plugins"));
        for (var entry : mapper.readTree(layout.resolve("plugins-manifest.json").toFile())) {
            Path artifact = layout.resolve(entry.path("file").asText());
            var descriptor = PluginPackageReader.inspect(artifact).descriptor();
            var signature = mapper.treeToValue(entry.path("signature"), SignatureMetadata.class);
            long size = entry.path("size").asLong();
            String sha = entry.path("sha256").asText();
            var result = verifier.verifyArtifact(new ArtifactVerificationRequest(artifact,
                    descriptor.id(), descriptor.version(), size, sha, signature, VerificationPolicy.customRepository()));
            if (result.status() != VerificationStatus.VERIFIED) {
                throw new IllegalStateException("Candidate signature rejected: " + result.status());
            }
            // 同一测试签名必须被生产官方根拒绝，不能将夹具升级为官方来源。
            var official = new PluginSupplyChainVerifier(PluginTrustStores.builtInOfficialPlugins()).verifyArtifact(
                    new ArtifactVerificationRequest(artifact, descriptor.id(), descriptor.version(),
                            size, sha, signature, VerificationPolicy.officialRepository()));
            if (official.status() == VerificationStatus.VERIFIED) {
                throw new IllegalStateException("CI key unexpectedly has official trust");
            }
            var origin = PluginPackageOrigin.forTrustedCatalog("sdk-ci", false, size, sha, signature);
            var provenance = PluginProvenanceRecord.from(origin, result);
            store.write(artifact, provenance.withTrustDecision(
                    PluginTrustPolicy.approve(descriptor, provenance, Instant.now())));
        }
    }
}
