package top.sywyar.pixivdownload.plugin.runtime.install.trust;

import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginExecutionMode;
import top.sywyar.pixivdownload.plugin.runtime.install.model.PluginPackageSource;

import java.util.Locale;
import java.util.Objects;

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
        PluginExecutionMode executionMode) {

    public PluginTrustRequirement {
        pluginId = requiredText(pluginId, "pluginId");
        version = requiredText(version, "version");
        source = Objects.requireNonNull(source, "source");
        repositoryId = optionalText(repositoryId);
        publisher = optionalText(publisher);
        publisherKeyFingerprint = optionalSha256(publisherKeyFingerprint, "publisherKeyFingerprint");
        artifactSha256 = optionalSha256(artifactSha256, "artifactSha256");
        executionMode = Objects.requireNonNull(executionMode, "executionMode");
        if (artifactSha256 == null) {
            throw new IllegalArgumentException("artifactSha256 is required");
        }
        if (signed && publisherKeyFingerprint == null) {
            throw new IllegalArgumentException("signed trust requirement requires a key fingerprint");
        }
    }

    private static String requiredText(String value, String field) {
        String normalized = optionalText(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    private static String optionalSha256(String value, String field) {
        String normalized = optionalText(value);
        if (normalized != null && !normalized.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException(field + " is not a SHA-256 digest");
        }
        return normalized != null ? normalized.toLowerCase(Locale.ROOT) : null;
    }

    private static String optionalText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
