package top.sywyar.pixivdownload.sdk.community.directory;

import com.fasterxml.jackson.annotation.JsonInclude;
import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.Owner;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.Reference;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;

import java.time.Instant;
import java.util.List;

/** 仓库发现与身份认证记录，不授予任何具体版本的安装、运行或源码保障。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DirectoryEntry(String repositoryId, String descriptorUrl, String descriptorSha256, Owner publisher,
                              String publisherLoginSnapshot, Reference certificationRef, List<CertifiedKey> certifiedKeys,
                              Status status, String firstReviewedAt, String lastReviewedAt, long directorySequence,
                              String githubRepositoryId, String reason) {
    public enum Status { LISTED, IDENTITY_VERIFIED, SUSPENDED, REMOVED }
    public record CertifiedKey(String keyId, String spkiSha256) { }
    public DirectoryEntry { certifiedKeys = List.copyOf(certifiedKeys); }

    public void validate() {
        byte[] bytes = CommunityJson.encode(this);
        CommunityJson.validateStructure("directoryEntry", CommunityJson.strictTree(bytes, bytes.length));
        CommunityValues.https(descriptorUrl, true, "/descriptorUrl");
        certificationRef.validate();
        CommunityValues.unique(certifiedKeys, CertifiedKey::keyId, "/certifiedKeys/keyId");
        CommunityValues.unique(certifiedKeys, CertifiedKey::spkiSha256, "/certifiedKeys/spkiSha256");
        if (Instant.parse(firstReviewedAt).isAfter(Instant.parse(lastReviewedAt))
                || status == Status.IDENTITY_VERIFIED && certifiedKeys.isEmpty()
                || status == Status.SUSPENDED && (reason == null || reason.isBlank())) {
            throw new ContractException("REVIEW_MISMATCH", "/directoryEntry");
        }
    }

    public boolean discoverable() { return status != Status.REMOVED; }
    public boolean offersCertifiedIdentity() { return status == Status.IDENTITY_VERIFIED; }
    public boolean allowsManualRepositoryAddition() { return status == Status.LISTED || status == Status.IDENTITY_VERIFIED; }
}
