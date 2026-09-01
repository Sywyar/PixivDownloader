package top.sywyar.pixivdownload.plugin.runtime.install.trust;

import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginExecutionMode;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginPermissionDeclaration;

import java.time.Instant;
import java.util.Locale;
import java.util.List;
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
        ApprovalType approvalType,
        boolean permissionsDeclared,
        List<String> declaredPermissions) {

    /** 历史信任决定未记录声明状态；保留其摘要并按“未声明、完全访问”解释。 */
    public static final String EMPTY_PERMISSION_DIGEST =
            PluginPermissionDeclaration.UNDECLARED_PERMISSION_DIGEST;

    public PluginTrustDecision {
        pluginId = requiredText(pluginId, "pluginId");
        publisherKeyFingerprint = optionalSha256(publisherKeyFingerprint, "publisherKeyFingerprint");
        repositoryId = optionalText(repositoryId);
        artifactSha256 = requiredSha256(artifactSha256, "artifactSha256");
        executionMode = Objects.requireNonNull(executionMode, "executionMode");
        declaredPermissionDigest = requiredSha256(declaredPermissionDigest, "declaredPermissionDigest");
        approvedAt = Objects.requireNonNull(approvedAt, "approvedAt");
        approvalType = Objects.requireNonNull(approvalType, "approvalType");
        PluginPermissionDeclaration permissions = new PluginPermissionDeclaration(
                permissionsDeclared, declaredPermissions);
        declaredPermissions = permissions.permissions();
        if (!permissions.digest().equals(declaredPermissionDigest)) {
            throw new IllegalArgumentException("declaredPermissionDigest does not match declared permissions");
        }
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

    /** 兼容历史调用方：旧信任决定没有权限清单，按完全访问处理。 */
    public PluginTrustDecision(
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
        this(pluginId, publisherKeyFingerprint, repositoryId, repositoryOfficial, artifactSha256,
                executionMode, declaredPermissionDigest, approvedAt, approvedAppSdkMajor, approvalType,
                false, List.of());
    }

    public PluginPermissionDeclaration permissionDeclaration() {
        return new PluginPermissionDeclaration(permissionsDeclared, declaredPermissions);
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
