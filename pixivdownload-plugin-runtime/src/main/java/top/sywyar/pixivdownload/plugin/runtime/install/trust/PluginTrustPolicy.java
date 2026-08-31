package top.sywyar.pixivdownload.plugin.runtime.install.trust;

import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginExecutionMode;
import top.sywyar.pixivdownload.plugin.runtime.install.provenance.PluginProvenanceRecord;
import top.sywyar.pixivdownload.sdk.SdkVersion;

import java.time.Instant;
import java.util.Objects;

/** 安装确认、更新继承与运行时复核共用的最小信任规则。 */
public final class PluginTrustPolicy {

    private PluginTrustPolicy() {
    }

    public static PluginTrustRequirement requirement(
            PluginDescriptor descriptor, PluginProvenanceRecord provenance) {
        return new PluginTrustRequirement(
                descriptor.id(), descriptor.version(), provenance.source(), provenance.repositoryId(),
                provenance.officialRepository(), provenance.signature() != null, provenance.publisher(),
                provenance.publisherKeyFingerprint(), provenance.artifactSha256(), descriptor.executionMode());
    }

    public static PluginTrustDecision approve(
            PluginDescriptor descriptor, PluginProvenanceRecord provenance, Instant approvedAt) {
        PluginTrustDecision.ApprovalType type = provenance.officialRepository()
                ? PluginTrustDecision.ApprovalType.OFFICIAL
                : provenance.publisherKeyFingerprint() != null
                        ? PluginTrustDecision.ApprovalType.PUBLISHER
                        : PluginTrustDecision.ApprovalType.EXACT_ARTIFACT;
        return decision(descriptor, provenance, Objects.requireNonNull(approvedAt, "approvedAt"), type);
    }

    public static PluginTrustDecision official(
            PluginDescriptor descriptor, PluginProvenanceRecord provenance, Instant approvedAt) {
        if (!provenance.officialRepository()) {
            throw new IllegalArgumentException("official trust requires an official repository");
        }
        return decision(descriptor, provenance, approvedAt, PluginTrustDecision.ApprovalType.OFFICIAL);
    }

    /** 已批准发布密钥的普通更新可继承；未签名制品、撤销、SDK 主版本或执行权限升级均须重确认。 */
    public static PluginTrustDecision inherited(
            PluginDescriptor descriptor,
            PluginProvenanceRecord candidate,
            PluginProvenanceRecord installed) {
        PluginTrustDecision previous = installed != null ? installed.trustDecision() : null;
        if (previous == null || installed.trustRevokedAt() != null
                || previous.approvalType() != PluginTrustDecision.ApprovalType.PUBLISHER
                || candidate.publisherKeyFingerprint() == null
                || !descriptor.id().equals(previous.pluginId())
                || !candidate.publisherKeyFingerprint().equals(previous.publisherKeyFingerprint())
                || previous.approvedAppSdkMajor() != SdkVersion.MAJOR
                || !PluginTrustDecision.EMPTY_PERMISSION_DIGEST.equals(previous.declaredPermissionDigest())
                || executionPrivilegeIncreased(previous.executionMode(), descriptor.executionMode())) {
            return null;
        }
        return decision(descriptor, candidate, previous.approvedAt(), previous.approvalType());
    }

    /** 返回 {@code null} 表示可执行；否则返回稳定英文诊断，不触碰任何插件代码。 */
    public static String executionDenial(
            PluginDescriptor descriptor,
            PluginProvenanceRecord provenance,
            boolean developmentModeEnabled) {
        if (provenance == null) {
            return "plugin execution trust provenance is missing";
        }
        if (provenance.developmentOnly()) {
            return developmentModeEnabled ? null : "development-only plugin requires active development mode";
        }
        if (provenance.trustRevokedAt() != null) {
            return "plugin execution trust was revoked";
        }
        PluginTrustDecision decision = provenance.trustDecision();
        if (decision == null) {
            return provenance.officialRepository() ? null : "plugin execution trust confirmation is missing";
        }
        if (!descriptor.id().equals(decision.pluginId())
                || !provenance.artifactSha256().equals(decision.artifactSha256())
                || !Objects.equals(provenance.publisherKeyFingerprint(), decision.publisherKeyFingerprint())
                || !Objects.equals(provenance.repositoryId(), decision.repositoryId())
                || provenance.officialRepository() != decision.repositoryOfficial()
                || descriptor.executionMode() != decision.executionMode()
                || !PluginTrustDecision.EMPTY_PERMISSION_DIGEST.equals(decision.declaredPermissionDigest())
                || decision.approvedAppSdkMajor() != SdkVersion.MAJOR) {
            return "plugin execution trust decision does not bind the current candidate";
        }
        return null;
    }

    private static PluginTrustDecision decision(
            PluginDescriptor descriptor,
            PluginProvenanceRecord provenance,
            Instant approvedAt,
            PluginTrustDecision.ApprovalType type) {
        return new PluginTrustDecision(
                descriptor.id(), provenance.publisherKeyFingerprint(), provenance.repositoryId(),
                provenance.officialRepository(), provenance.artifactSha256(), descriptor.executionMode(),
                PluginTrustDecision.EMPTY_PERMISSION_DIGEST, approvedAt, SdkVersion.MAJOR, type);
    }

    private static boolean executionPrivilegeIncreased(
            PluginExecutionMode previous, PluginExecutionMode candidate) {
        return previous == PluginExecutionMode.DECLARATIVE_PROCESS
                && candidate == PluginExecutionMode.HOST_PROCESS_FULL_TRUST;
    }
}
