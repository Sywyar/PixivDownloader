package top.sywyar.pixivdownload.plugin.runtime.install.trust;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginRiskDeclaration;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginExecutionMode;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginLifecyclePolicy;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.VersionRequirement;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageSource;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.PluginProvenanceRecord;
import top.sywyar.pixivdownload.plugin.signature.SignatureMetadata;
import top.sywyar.pixivdownload.plugin.signature.VerificationStatus;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("插件执行信任规则")
class PluginTrustPolicyTest {

    private static final String FINGERPRINT = "f".repeat(64);
    private static final Instant APPROVED_AT = Instant.parse("2026-09-01T00:00:00Z");

    @Test
    @DisplayName("信任决定保留文本修剪及摘要大小写规范化")
    void normalizesDecisionFields() {
        var decision = new PluginTrustDecision(" demo ", " " + "A".repeat(64) + " ", " repo ",
                false, " " + "B".repeat(64) + " ", PluginExecutionMode.DECLARATIVE_PROCESS,
                APPROVED_AT, 1, PluginTrustDecision.ApprovalType.PUBLISHER);

        assertThat(decision.pluginId()).isEqualTo("demo");
        assertThat(decision.repositoryId()).isEqualTo("repo");
        assertThat(decision.publisherKeyFingerprint()).isEqualTo("a".repeat(64));
        assertThat(decision.artifactSha256()).isEqualTo("b".repeat(64));
    }

    @Test
    @DisplayName("信任确认保留必填文本和空白可选字段的规范化")
    void normalizesRequirementFields() {
        var requirement = new PluginTrustRequirement(" demo ", " 2.3.4 ", PluginPackageSource.LOCAL_UPLOAD,
                " ", false, false, "\t", null, " " + "B".repeat(64) + " ",
                PluginExecutionMode.DECLARATIVE_PROCESS);

        assertThat(requirement.pluginId()).isEqualTo("demo");
        assertThat(requirement.version()).isEqualTo("2.3.4");
        assertThat(requirement.repositoryId()).isNull();
        assertThat(requirement.publisher()).isNull();
        assertThat(requirement.publisherKeyFingerprint()).isNull();
        assertThat(requirement.artifactSha256()).isEqualTo("b".repeat(64));
    }

    @ParameterizedTest
    @MethodSource("invalidDigests")
    @DisplayName("两类信任事实均拒绝空白、非十六进制及长度错误的制品摘要")
    void rejectsInvalidArtifactDigest(String digest) {
        assertThatThrownBy(() -> new PluginTrustDecision("demo", null, null, false, digest,
                PluginExecutionMode.DECLARATIVE_PROCESS, APPROVED_AT, 1,
                PluginTrustDecision.ApprovalType.EXACT_ARTIFACT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PluginTrustRequirement("demo", "2.3.4", PluginPackageSource.LOCAL_UPLOAD,
                null, false, false, null, null, digest, PluginExecutionMode.DECLARATIVE_PROCESS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Stream<String> invalidDigests() {
        return Stream.of(" ", "g".repeat(64), "a".repeat(63), "a".repeat(65));
    }

    @Test
    @DisplayName("摘要与执行模式同时缺失时保留两类事实各自的校验顺序")
    void preservesValidationOrder() {
        assertThatThrownBy(() -> new PluginTrustDecision("demo", null, null, false, null,
                null, APPROVED_AT, 1, PluginTrustDecision.ApprovalType.EXACT_ARTIFACT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PluginTrustRequirement("demo", "2.3.4", PluginPackageSource.LOCAL_UPLOAD,
                null, false, false, null, null, null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("同发布密钥更新可继承信任，但 worker 提升到宿主完全信任必须重确认")
    void publisherUpdateInheritsUnlessExecutionPrivilegeIncreases() {
        PluginDescriptor installedDescriptor = descriptor("1.0.0", PluginExecutionMode.DECLARATIVE_PROCESS);
        PluginProvenanceRecord installed = provenance("a".repeat(64)).withTrustDecision(
                PluginTrustPolicy.approve(installedDescriptor, provenance("a".repeat(64)), APPROVED_AT));

        assertThat(PluginTrustPolicy.inherited(
                descriptor("1.1.0", PluginExecutionMode.DECLARATIVE_PROCESS),
                provenance("b".repeat(64)), installed)).isNotNull();
        assertThat(PluginTrustPolicy.inherited(
                descriptor("1.1.0", PluginExecutionMode.HOST_PROCESS_FULL_TRUST),
                provenance("b".repeat(64)), installed)).isNull();
    }

    @Test
    @DisplayName("运行前复核拒绝执行模式与已确认决定不一致")
    void executionReviewRejectsChangedExecutionMode() {
        PluginDescriptor approvedDescriptor = descriptor("1.0.0", PluginExecutionMode.DECLARATIVE_PROCESS);
        PluginProvenanceRecord provenance = provenance("a".repeat(64));
        PluginProvenanceRecord approved = provenance.withTrustDecision(
                PluginTrustPolicy.approve(approvedDescriptor, provenance, APPROVED_AT));

        assertThat(PluginTrustPolicy.executionDenial(approvedDescriptor, approved, false)).isNull();
        assertThat(PluginTrustPolicy.executionDenial(
                descriptor("1.0.0", PluginExecutionMode.HOST_PROCESS_FULL_TRUST),
                approved, false)).contains("does not bind");
    }

    private static PluginDescriptor descriptor(
            String version, PluginExecutionMode executionMode) {
        return new PluginDescriptor(
                "demo", "demo", version, VersionRequirement.unspecified(), List.of(),
                "com.example.DemoPlugin", null, "demo", null, null, null,
                PluginKind.FEATURE, List.of(), PluginLifecyclePolicy.HOT_RELOAD,
                executionMode, List.of(), PluginRiskDeclaration.absent());
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
