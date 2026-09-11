package top.sywyar.pixivdownload.plugin.signature.community;

import top.sywyar.pixivdownload.plugin.signature.SignatureMetadata;
import java.nio.file.Path;

/** 社区 envelope 的期望事实；发布者原 artifact 签名仍需另行验证。 */
public record CommunityPackageVerificationRequest(
        Path artifactPath, String repositoryId, String pluginId, String version,
        long expectedSizeBytes, String expectedSha256, String assuranceLevel, String sourceCommit,
        String reviewRecordSha256, SignatureMetadata signature, boolean retiredKeysAllowed) { }
