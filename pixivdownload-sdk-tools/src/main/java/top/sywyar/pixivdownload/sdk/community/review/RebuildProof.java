package top.sywyar.pixivdownload.sdk.community.review;

import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.Evidence;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.Reference;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;

import java.util.Map;

/** 固定构建的字节复现证明；执行来源仍须由无密钥 CI 的可信适配器核对。 */
public record RebuildProof(int schemaVersion, String sourceCommit, String jdkVersion, String buildToolVersion,
                           String buildImageSha256, Reference dependencyLockRef, long packageSize,
                           String packageSha256, Reference sbomRef, String result) {
    public static RebuildProof read(Evidence evidence, int maximumBytes) {
        return CommunityJson.decode("rebuildProof", evidence.bytes(), maximumBytes, RebuildProof.class);
    }

    /** actual 是执行器观测的事实，不由待验证的证明反序列化后传入。 */
    public void requireMatches(RebuildProof actual, Map<String, Evidence> evidence) {
        if (!equals(actual) || !"MATCH".equals(result)) throw new ContractException("REVIEW_MISMATCH", "/rebuildProof");
        CommunityValues.requireEvidence(dependencyLockRef, evidence);
        CommunityValues.requireEvidence(sbomRef, evidence);
    }
}
