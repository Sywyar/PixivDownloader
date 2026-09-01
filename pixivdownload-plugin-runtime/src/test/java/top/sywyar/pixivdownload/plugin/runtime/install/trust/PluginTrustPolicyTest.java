package top.sywyar.pixivdownload.plugin.runtime.install.trust;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginExecutionMode;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginLifecyclePolicy;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginPermissionDeclaration;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.VersionRequirement;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageSource;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.PluginProvenanceRecord;
import top.sywyar.pixivdownload.plugin.signature.SignatureMetadata;
import top.sywyar.pixivdownload.plugin.signature.VerificationStatus;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("插件执行信任规则")
class PluginTrustPolicyTest {

    private static final String FINGERPRINT = "f".repeat(64);
    private static final Instant APPROVED_AT = Instant.parse("2026-09-01T00:00:00Z");

    @Test
    @DisplayName("同发布密钥更新仅在权限未增加时继承信任")
    void publisherUpdateInheritsOnlyWithoutPermissionIncrease() {
        PluginDescriptor installedDescriptor = descriptor(
                "1.0.0", PluginPermissionDeclaration.declared(List.of("network", "filesystem-write")));
        PluginProvenanceRecord installed = provenance("a".repeat(64)).withTrustDecision(
                PluginTrustPolicy.approve(installedDescriptor, provenance("a".repeat(64)), APPROVED_AT));

        assertThat(PluginTrustPolicy.inherited(
                descriptor("1.1.0", PluginPermissionDeclaration.declared(List.of("network"))),
                provenance("b".repeat(64)), installed)).isNotNull();
        assertThat(PluginTrustPolicy.inherited(
                descriptor("1.1.0", PluginPermissionDeclaration.declared(List.of("network", "process-exec"))),
                provenance("b".repeat(64)), installed)).isNull();
        assertThat(PluginTrustPolicy.inherited(
                descriptor("1.1.0", PluginPermissionDeclaration.undeclared()),
                provenance("b".repeat(64)), installed)).isNull();
    }

    @Test
    @DisplayName("运行前复核拒绝权限声明与已确认决定不一致")
    void executionReviewRejectsChangedPermissionDeclaration() {
        PluginDescriptor approvedDescriptor = descriptor(
                "1.0.0", PluginPermissionDeclaration.declared(List.of("network")));
        PluginProvenanceRecord provenance = provenance("a".repeat(64));
        PluginProvenanceRecord approved = provenance.withTrustDecision(
                PluginTrustPolicy.approve(approvedDescriptor, provenance, APPROVED_AT));

        assertThat(PluginTrustPolicy.executionDenial(approvedDescriptor, approved, false)).isNull();
        assertThat(PluginTrustPolicy.executionDenial(
                descriptor("1.0.0", PluginPermissionDeclaration.declared(List.of("network", "process-exec"))),
                approved, false)).contains("does not bind");
    }

    private static PluginDescriptor descriptor(
            String version, PluginPermissionDeclaration permissionDeclaration) {
        return new PluginDescriptor(
                "demo", "demo", version, VersionRequirement.unspecified(), List.of(),
                "com.example.DemoPlugin", null, "demo", null, null, null,
                PluginKind.FEATURE, List.of(), PluginLifecyclePolicy.HOT_RELOAD,
                PluginExecutionMode.HOST_PROCESS_FULL_TRUST, List.of(), permissionDeclaration);
    }

    private static PluginProvenanceRecord provenance(String artifactSha256) {
        SignatureMetadata signature = new SignatureMetadata(
                SignatureMetadata.FORMAT_VERSION, SignatureMetadata.ED25519, "test-key", "c2ln");
        return new PluginProvenanceRecord(
                PluginPackageSource.LOCAL_UPLOAD, null, false, false,
                null, null, 4L, artifactSha256, signature, VerificationStatus.VERIFIED,
                "test-key", "Test Publisher", "Test Trust", FINGERPRINT,
                APPROVED_AT, null, null, "VERIFIED", null, null);
    }
}
