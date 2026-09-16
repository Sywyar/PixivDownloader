package top.sywyar.pixivdownload.plugin.runtime.install.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 社区 envelope 认证的审核事实；保存原始审核字节供离线投影，JSON 合同由客户端共享模块解释。 */
public record CommunityPackageEvidence(String assuranceLevel, String sourceCommit,
                                       String reviewSha256, String reviewJson) {
    public static final int MAX_REVIEW_BYTES = 256 * 1024;

    public CommunityPackageEvidence {
        if (!"SOURCE_REVIEWED".equals(assuranceLevel) || sourceCommit == null || !sourceCommit.matches("[0-9a-f]{40}")
                || reviewSha256 == null || !reviewSha256.matches("[0-9a-f]{64}") || reviewJson == null
                || reviewJson.length() > MAX_REVIEW_BYTES) {
            throw new IllegalArgumentException("invalid community review binding");
        }
        byte[] bytes = reviewJson.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_REVIEW_BYTES) throw new IllegalArgumentException("community review exceeds byte limit");
        try {
            if (!reviewSha256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))))
                throw new IllegalArgumentException("community review digest mismatch");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
