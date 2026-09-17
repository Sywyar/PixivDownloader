package top.sywyar.pixivdownload.plugin.verification;

import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginRiskDeclaration;

/**
 * 插件验签状态的前端 / GUI 稳定投影。前端只消费这些字段，不根据 sha256、HTTPS、仓库名或 keyId 自行推断可信状态。
 *
 * @param status                 稳定状态码
 * @param source                 来源维度
 * @param keyId                  签名 key id
 * @param publisher              发布者标签
 * @param trustLabel             信任根标签
 * @param lastVerifiedAt         最后验签时间
 * @param offlineReverifySuccess 最近离线复验是否成功
 * @param diagnosticCode         失败诊断码
 */
public record PluginVerificationView(
        String status,
        String source,
        String keyId,
        String publisher,
        String trustLabel,
        String lastVerifiedAt,
        boolean offlineReverifySuccess,
        String diagnosticCode,
        String repositoryTrustSource,
        String assuranceLevel,
        String revocationStatus,
        String executionMode,
        PluginRiskDeclaration riskDeclaration,
        String previousExecutionMode,
        PluginRiskDeclaration previousRiskDeclaration) {

    public PluginVerificationView(String status, String source, String keyId, String publisher, String trustLabel,
                                  String lastVerifiedAt, boolean offlineReverifySuccess, String diagnosticCode) {
        this(status, source, keyId, publisher, trustLabel, lastVerifiedAt, offlineReverifySuccess, diagnosticCode,
                "official".equals(source) || "built-in".equals(source) ? "OFFICIAL"
                        : "local".equals(source) ? "LOCAL" : "SELF_TRUSTED",
                "VERIFIED_OFFICIAL".equals(status) ? "OFFICIAL"
                        : "VERIFIED_CUSTOM".equals(status) ? "PUBLISHER_SIGNED" : "UNVERIFIED",
                "NOT_CHECKED", null, null, null, null);
    }

    public PluginVerificationView withCommunity(String assurance) {
        return new PluginVerificationView("VERIFIED_CUSTOM".equals(status) ? "VERIFIED_COMMUNITY" : status,
                "community", keyId, publisher, trustLabel, lastVerifiedAt, offlineReverifySuccess, diagnosticCode,
                "COMMUNITY_VERIFIED", assurance, revocationStatus, executionMode, riskDeclaration,
                previousExecutionMode, previousRiskDeclaration);
    }

    public PluginVerificationView withFacts(String revocation, String mode, PluginRiskDeclaration risk,
                                             PluginDescriptor previous) {
        return new PluginVerificationView(status, source, keyId, publisher, trustLabel, lastVerifiedAt,
                offlineReverifySuccess, diagnosticCode, repositoryTrustSource, assuranceLevel, revocation, mode, risk,
                previous != null ? previous.executionMode().descriptorValue() : null,
                previous != null ? previous.riskDeclaration() : null);
    }

    public PluginVerificationView withDescriptor(PluginDescriptor descriptor) {
        return descriptor == null ? this : withFacts(revocationStatus, descriptor.executionMode().descriptorValue(),
                descriptor.riskDeclaration(), null);
    }

    public PluginVerificationView withRevocation(String revocation) {
        return new PluginVerificationView(status, source, keyId, publisher, trustLabel, lastVerifiedAt,
                offlineReverifySuccess, diagnosticCode, repositoryTrustSource, assuranceLevel, revocation,
                executionMode, riskDeclaration, previousExecutionMode, previousRiskDeclaration);
    }
}
