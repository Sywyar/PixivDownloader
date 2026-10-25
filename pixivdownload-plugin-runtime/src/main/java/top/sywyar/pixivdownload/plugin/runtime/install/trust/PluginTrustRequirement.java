package top.sywyar.pixivdownload.plugin.runtime.install.trust;

import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginExecutionMode;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginRiskDeclaration;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageSource;

import java.util.Objects;

import static top.sywyar.pixivdownload.plugin.runtime.install.trust.PluginTrustPolicy.optionalSha256;
import static top.sywyar.pixivdownload.plugin.runtime.install.trust.PluginTrustPolicy.optionalText;
import static top.sywyar.pixivdownload.plugin.runtime.install.trust.PluginTrustPolicy.requiredText;

/** 安装未发布前返回给管理员的精确制品信任确认事实。 */
public record PluginTrustRequirement(
        String pluginId,
        String version,
        PluginPackageSource source,
        String repositoryId,
        boolean officialRepository,
        boolean signed,
        String publisher,
        String publisherKeyFingerprint,
        String artifactSha256,
        PluginExecutionMode executionMode,
        PluginRiskDeclaration riskDeclaration,
        PluginRiskDeclaration previousRiskDeclaration,
        PluginExecutionMode previousExecutionMode,
        String repositoryTrustSource,
        String assuranceLevel) {

    public PluginTrustRequirement(String pluginId, String version, PluginPackageSource source, String repositoryId,
                                  boolean officialRepository, boolean signed, String publisher, String publisherKeyFingerprint,
                                  String artifactSha256, PluginExecutionMode executionMode) {
        this(pluginId, version, source, repositoryId, officialRepository, signed, publisher, publisherKeyFingerprint,
                artifactSha256, executionMode, PluginRiskDeclaration.absent(), null, null,
                officialRepository ? "OFFICIAL" : source == PluginPackageSource.LOCAL_UPLOAD ? "LOCAL" : "SELF_TRUSTED",
                officialRepository ? "OFFICIAL" : signed ? "PUBLISHER_SIGNED" : "UNVERIFIED");
    }

    public PluginTrustRequirement withPrevious(PluginDescriptor previous) {
        return new PluginTrustRequirement(pluginId, version, source, repositoryId, officialRepository, signed, publisher,
                publisherKeyFingerprint, artifactSha256, executionMode, riskDeclaration,
                previous != null ? previous.riskDeclaration() : null, previous != null ? previous.executionMode() : null,
                repositoryTrustSource, assuranceLevel);
    }

    public PluginTrustRequirement {
        pluginId = requiredText(pluginId, "pluginId");
        version = requiredText(version, "version");
        source = Objects.requireNonNull(source, "source");
        repositoryId = optionalText(repositoryId);
        publisher = optionalText(publisher);
        publisherKeyFingerprint = optionalSha256(publisherKeyFingerprint, "publisherKeyFingerprint");
        artifactSha256 = optionalSha256(artifactSha256, "artifactSha256");
        executionMode = Objects.requireNonNull(executionMode, "executionMode");
        riskDeclaration = Objects.requireNonNull(riskDeclaration, "riskDeclaration");
        if (artifactSha256 == null) {
            throw new IllegalArgumentException("artifactSha256 is required");
        }
        if (signed && publisherKeyFingerprint == null) {
            throw new IllegalArgumentException("signed trust requirement requires a key fingerprint");
        }
    }
}
