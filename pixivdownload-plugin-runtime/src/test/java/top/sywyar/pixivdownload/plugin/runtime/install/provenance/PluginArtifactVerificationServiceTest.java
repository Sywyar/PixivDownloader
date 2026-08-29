package top.sywyar.pixivdownload.plugin.runtime.install.provenance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.VersionRequirement;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageOrigin;
import top.sywyar.pixivdownload.plugin.signature.PluginSupplyChainVerifier;
import top.sywyar.pixivdownload.plugin.signature.VerificationResult;
import top.sywyar.pixivdownload.plugin.signature.VerificationStatus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("插件工件统一复验服务")
class PluginArtifactVerificationServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    @DisplayName("关闭开发模式立即撤销未签名本地包的安装与离线信任")
    void developmentSwitchRevokesUnsignedTrustOnEveryVerification() throws Exception {
        Path artifact = temporaryDirectory.resolve("demo.jar");
        Files.write(artifact, new byte[]{1, 2, 3, 4});
        PluginDescriptor descriptor = descriptor();
        PluginPackageOrigin origin = PluginPackageOrigin.localUpload();
        AtomicBoolean developmentMode = new AtomicBoolean(true);
        PluginSupplyChainVerifier verifier = new PluginSupplyChainVerifier();
        PluginArtifactVerificationService service = new PluginArtifactVerificationService(
                ignored -> verifier, developmentMode::get);

        VerificationResult installed = service.verifyForInstall(artifact, descriptor, origin);
        PluginProvenanceRecord provenance = PluginProvenanceRecord.from(origin, installed);
        developmentMode.set(false);
        VerificationResult installRevoked = service.verifyForInstall(artifact, descriptor, origin);
        VerificationResult revoked = service.verifyInstalled(artifact, descriptor, provenance);
        developmentMode.set(true);
        VerificationResult restored = service.verifyInstalled(artifact, descriptor, provenance);

        assertThat(installed.status()).isEqualTo(VerificationStatus.UNSIGNED_ALLOWED);
        assertThat(provenance.developmentOnly()).isTrue();
        assertThat(installRevoked.status()).isEqualTo(VerificationStatus.SIGNATURE_REQUIRED);
        assertThat(revoked.status()).isEqualTo(VerificationStatus.SIGNATURE_REQUIRED);
        assertThat(restored.status()).isEqualTo(VerificationStatus.UNSIGNED_ALLOWED);
    }

    private static PluginDescriptor descriptor() {
        return new PluginDescriptor(
                "demo", "demo", "1.0.0", VersionRequirement.unspecified(), List.of(),
                "example.DemoPlugin", "demo", "plugin.demo.name", null, null, null,
                PluginKind.FEATURE);
    }
}
