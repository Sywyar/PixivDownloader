package top.sywyar.pixivdownload.plugin.runtime.status;

import top.sywyar.pixivdownload.plugin.runtime.install.provenance.PluginProvenanceRecord;
import top.sywyar.pixivdownload.plugin.signature.VerificationResult;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/**
 * 启动或运行期加载对单个冻结 artifact 字节执行离线复验得到的结构化事实。
 *
 * <p>{@code provenance} 只保留来源 / 信任标签等投影上下文；当前 artifact 身份必须由本快照自己的
 * 路径、插件 id、版本、大小与摘要共同绑定。管理面只有在这些字段与当前磁盘快照完全一致时，才能消费
 * {@link #result()}，避免旧启动结果污染同路径替换后的新 artifact。若底层 I/O 结果无法给出摘要，快照仍保留
 * 结构化结果，但 {@link #binds(Path, String, String, long, String)} 恒不允许其作为当前 artifact 的投影依据。
 */
public record PluginRuntimeVerificationSnapshot(
        Path artifactPath,
        String pluginId,
        String version,
        long artifactSizeBytes,
        String artifactSha256,
        PluginProvenanceRecord provenance,
        VerificationResult result) {

    public PluginRuntimeVerificationSnapshot {
        artifactPath = Objects.requireNonNull(artifactPath, "artifactPath")
                .toAbsolutePath().normalize();
        pluginId = requiredText(pluginId, "pluginId");
        version = requiredText(version, "version");
        result = Objects.requireNonNull(result, "result");
        Objects.requireNonNull(result.status(), "result.status");
        if (!pluginId.equals(result.pluginId()) || !version.equals(result.version())) {
            throw new IllegalArgumentException("runtime verification identity does not match the inspected package");
        }
        if (artifactSizeBytes < 0L || artifactSizeBytes != result.sizeBytes()) {
            throw new IllegalArgumentException("runtime verification size does not match the verified artifact");
        }
        artifactSha256 = normalizedOptionalSha256(artifactSha256, "artifactSha256");
        String resultSha256 = normalizedOptionalSha256(result.sha256(), "result.sha256");
        if (!Objects.equals(artifactSha256, resultSha256)) {
            throw new IllegalArgumentException("runtime verification digest does not match the verified artifact");
        }
    }

    /** 当前磁盘快照是否仍是本次复验所消费的同一 artifact 字节与包身份。 */
    public boolean binds(Path currentPath, String currentPluginId, String currentVersion,
                         long currentSizeBytes, String currentSha256) {
        if (currentPath == null || currentPluginId == null || currentVersion == null
                || artifactSha256 == null || currentSha256 == null) {
            return false;
        }
        String normalizedCurrentSha256;
        try {
            normalizedCurrentSha256 = normalizedOptionalSha256(currentSha256, "currentSha256");
        } catch (IllegalArgumentException ignored) {
            return false;
        }
        return artifactPath.equals(currentPath.toAbsolutePath().normalize())
                && pluginId.equals(currentPluginId)
                && version.equals(currentVersion)
                && artifactSizeBytes == currentSizeBytes
                && artifactSha256.equals(normalizedCurrentSha256);
    }

    /**
     * 当前 sidecar 是否就是本次复验读取的 provenance，或是仅写入了本次离线结果后的规范形态。
     * 其它来源、信任根或安装绑定变化均不得复用旧 runtime 结论。
     */
    public boolean matchesProvenance(PluginProvenanceRecord current) {
        if (provenance == null) {
            return current == null;
        }
        if (provenance.equals(current)) {
            return true;
        }
        try {
            return provenance.withOfflineResult(result, pluginId, version).equals(current);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static String normalizedOptionalSha256(String value, String field) {
        if (value == null) {
            return null;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " is not a SHA-256 digest");
        }
        return normalized;
    }

    private static String requiredText(String value, String field) {
        if (value == null || value.isBlank() || !value.equals(value.trim())) {
            throw new IllegalArgumentException(field + " is missing or malformed");
        }
        return value;
    }
}
