package top.sywyar.pixivdownload.plugin.runtime.install.trust;

import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginExecutionMode;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/** 宿主对一个已验证插件制品作出的持久化执行信任决定。 */
public record PluginTrustDecision(
        String pluginId,
        String publisherKeyFingerprint,
        String repositoryId,
        boolean repositoryOfficial,
        String artifactSha256,
        PluginExecutionMode executionMode,
        String declaredPermissionDigest,
        Instant approvedAt,
        int approvedAppSdkMajor,
        ApprovalType approvalType) {

    /** 当前描述符尚无权限清单；用稳定空集摘要保留未来“权限增加需重确认”的比较位。 */
    public static final String EMPTY_PERMISSION_DIGEST =
            "4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945";

    public PluginTrustDecision {
        pluginId = requiredText(pluginId, "pluginId");
        publisherKeyFingerprint = optionalSha256(publisherKeyFingerprint, "publisherKeyFingerprint");
        repositoryId = optionalText(repositoryId);
        artifactSha256 = requiredSha256(artifactSha256, "artifactSha256");
        executionMode = Objects.requireNonNull(executionMode, "executionMode");
        declaredPermissionDigest = requiredSha256(declaredPermissionDigest, "declaredPermissionDigest");
        approvedAt = Objects.requireNonNull(approvedAt, "approvedAt");
        approvalType = Objects.requireNonNull(approvalType, "approvalType");
        if (approvedAppSdkMajor < 0) {
            throw new IllegalArgumentException("approvedAppSdkMajor must not be negative");
        }
        if (approvalType == ApprovalType.OFFICIAL && !repositoryOfficial) {
            throw new IllegalArgumentException("official approval requires an official repository");
        }
        if (approvalType == ApprovalType.PUBLISHER && publisherKeyFingerprint == null) {
            throw new IllegalArgumentException("publisher approval requires a key fingerprint");
        }
        if (approvalType == ApprovalType.REPOSITORY && repositoryId == null) {
            throw new IllegalArgumentException("repository approval requires a repository id");
        }
    }

    public enum ApprovalType {
        OFFICIAL,
        PUBLISHER,
        EXACT_ARTIFACT,
        REPOSITORY
    }

    private static String requiredText(String value, String field) {
        String normalized = optionalText(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    private static String requiredSha256(String value, String field) {
        String normalized = optionalSha256(value, field);
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
