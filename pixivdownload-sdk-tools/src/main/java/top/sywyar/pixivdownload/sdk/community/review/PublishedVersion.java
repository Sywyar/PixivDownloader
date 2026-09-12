package top.sywyar.pixivdownload.sdk.community.review;

import com.fasterxml.jackson.annotation.JsonProperty;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageReader;
import top.sywyar.pixivdownload.plugin.signature.*;
import top.sywyar.pixivdownload.plugin.signature.community.CommunityPackageVerificationRequest;
import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.*;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;
import top.sywyar.pixivdownload.sdk.community.identity.PluginBinding;
import top.sywyar.pixivdownload.sdk.community.identity.Publisher;
import top.sywyar.pixivdownload.sdk.community.submission.DescriptorSnapshot;
import top.sywyar.pixivdownload.sdk.community.submission.VersionSubmission;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** 不可变发布记录，保留历史归属、原签与已冻结审核；同版本不能替换为另一份包。 */
public record PublishedVersion(int schemaVersion, Owner owner, String pluginId, String version,
                               Reference submissionRef, Reference reviewRef, String sourceCommit,
                               @JsonProperty("package") VersionSubmission.Artifact artifact,
                               Reference historicalPublisherRef, SignatureMetadata communitySignature,
                               String assuranceLevel, String publishedAt) {
    public record Key(String pluginId, String version) { }
    public record Outcome(CommunityJson.Document document, boolean replayed) { }

    /** 包路径必须是执行器持有的冻结副本；所有身份和已发布索引来自受保护事实源。 */
    public record Publication(Evidence submission, Evidence review, CommunityJson.Document currentBinding,
                               Evidence currentPublisher, VersionReview.Facts reviewFacts, Path frozenPackage,
                               String repositoryId, PluginSupplyChainVerifier communityVerifier,
                               SignatureMetadata communitySignature, String publishedAt,
                               Map<String, Evidence> evidence, int maximumReportBytes, int maximumRebuildBytes) {
        public Publication { evidence = Map.copyOf(evidence); }
    }

    public static PublishedVersion read(CommunityJson.Document document) {
        if (document.kind() != CommunityJson.Kind.PUBLISHED) throw new ContractException("SCHEMA_INVALID", "");
        var value = document.as(PublishedVersion.class);
        CommunityValues.version(value.version, "/version");
        value.artifact.validate();
        return value;
    }

    public static Outcome publish(Publication input, Map<Key, CommunityJson.Document> published,
                                   CommunityJson.Document previousVersion) {
        var submissionDocument = CommunityJson.parse(CommunityJson.Kind.SUBMISSION, input.submission.bytes());
        var submission = VersionSubmission.read(submissionDocument);
        var existing = published.get(new Key(submission.pluginId(), submission.version()));
        if (existing != null) {
            var old = read(existing);
            if (!old.pluginId.equals(submission.pluginId()) || !old.version.equals(submission.version())
                    || old.artifact.expectedSize() != submission.artifact().expectedSize()
                    || !old.artifact.sha256().equals(submission.artifact().sha256())) {
                throw new ContractException("VERSION_ALREADY_PUBLISHED", "/package");
            }
            return new Outcome(existing, true);
        }
        var reviewDocument = CommunityJson.parse(CommunityJson.Kind.REVIEW, input.review.bytes());
        var review = VersionReview.read(reviewDocument);
        var publisherDocument = CommunityJson.parse(CommunityJson.Kind.PUBLISHER, input.currentPublisher.bytes());
        var publisher = Publisher.read(publisherDocument);
        var binding = PluginBinding.read(input.currentBinding, "plugin-bindings/" + submission.pluginId() + ".json");
        binding.requireOwner(publisher.owner());
        if (!binding.owner().equals(review.owner()) || !review.submissionRef().equals(input.submission.reference())
                || !input.currentBinding.sha256().equals(input.reviewFacts.admission().snapshot().bindingSha256())
                || review.pr().mergeSha() == null || input.reviewFacts.admission().snapshot().state() != ReviewAdmission.PrState.MERGED) {
            throw new ContractException("REVIEW_MISMATCH", "/review");
        }
        if (previousVersion == null) submission.verifyPreviousSource(null);
        else {
            var previous = read(previousVersion);
            if (!previous.pluginId.equals(submission.pluginId())) throw new ContractException("BINDING_MISMATCH", "/previousVersion");
            submission.verifyPreviousSource(previous.owner.equals(publisher.owner()) ? previous.sourceCommit : null);
        }
        review.verify(submissionDocument, input.reviewFacts, input.evidence, input.maximumReportBytes, input.maximumRebuildBytes);
        var value = new PublishedVersion(1, publisher.owner(), submission.pluginId(), submission.version(),
                input.submission.reference(), input.review.reference(), submission.source().commit(), submission.artifact(),
                input.currentPublisher.reference(), input.communitySignature, "SOURCE_REVIEWED", input.publishedAt);
        var document = CommunityJson.parse(CommunityJson.Kind.PUBLISHED, CommunityJson.encode(value));
        var records = value.verifyRecords(input.evidence);
        value.verifyPackage(input.frozenPackage, publisher.trustStore(), input.communityVerifier, input.repositoryId, false, records);
        return new Outcome(document, false);
    }

    /** 历史包允许已退役原 key；仍读取其历史发布者当前 key 状态，吊销不会被旧快照掩盖。 */
    public void verifyHistory(Path frozenPackage, CommunityJson.Document currentHistoricalPublisher,
                               PluginSupplyChainVerifier communityVerifier, String repositoryId,
                               Map<String, Evidence> evidence) {
        var records = verifyRecords(evidence);
        var current = Publisher.read(currentHistoricalPublisher);
        if (!owner.equals(current.owner())) throw new ContractException("BINDING_MISMATCH", "/historicalPublisherRef");
        var originalKey = records.publisher.trustStore().findByKeyId(artifact.signature().keyId())
                .orElseThrow(() -> new ContractException("UNKNOWN_KEY", "/package/signature"));
        var currentKey = current.trustStore().findByKeyId(originalKey.keyId())
                .orElseThrow(() -> new ContractException("UNKNOWN_KEY", "/package/signature"));
        if (!originalKey.publicKeySpkiBase64().equals(currentKey.publicKeySpkiBase64())) {
            throw new ContractException("BINDING_MISMATCH", "/package/signature/keyId");
        }
        verifyPackage(frozenPackage, PluginTrustStores.community(List.of(currentKey)), communityVerifier, repositoryId, true, records);
    }

    private record Records(Publisher publisher, VersionReview review, VersionSubmission submission) { }

    private Records verifyRecords(Map<String, Evidence> evidence) {
        var submission = VersionSubmission.read(CommunityJson.parse(CommunityJson.Kind.SUBMISSION,
                CommunityValues.requireEvidence(submissionRef, evidence).bytes()));
        var review = VersionReview.read(CommunityJson.parse(CommunityJson.Kind.REVIEW,
                CommunityValues.requireEvidence(reviewRef, evidence).bytes()));
        var publisher = Publisher.read(CommunityJson.parse(CommunityJson.Kind.PUBLISHER,
                CommunityValues.requireEvidence(historicalPublisherRef, evidence).bytes()));
        if (!owner.equals(publisher.owner()) || !owner.equals(review.owner()) || !owner.publisherId().equals(submission.publisherId())
                || !pluginId.equals(submission.pluginId()) || !pluginId.equals(review.pluginId())
                || !version.equals(submission.version()) || !version.equals(review.version())
                || !sourceCommit.equals(submission.source().commit()) || !review.source().equals(submission.source())
                || !artifact.equals(submission.artifact()) || !artifact.sha256().equals(review.packageSha256())
                || artifact.expectedSize() != review.packageSize() || !submissionRef.equals(review.submissionRef())
                || !assuranceLevel.equals(review.assuranceLevel()) || review.pr().mergeSha() == null) {
            throw new ContractException("REVIEW_MISMATCH", "/published");
        }
        for (var ref : List.of(review.sourceDiffRef(), review.sbomRef(), review.dependencyReportRef(), review.licenseReportRef(),
                review.rebuildProofRef(), review.riskScan().reportRef(), review.humanReview().evidenceRef(), review.publicationApprovalRef())) {
            CommunityValues.requireEvidence(ref, evidence);
        }
        review.riskScan().decisionRefs().forEach(ref -> CommunityValues.requireEvidence(ref, evidence));
        return new Records(publisher, review, submission);
    }

    private void verifyPackage(Path path, PluginTrustStore publisherKeys, PluginSupplyChainVerifier communityVerifier,
                                String repositoryId, boolean historical, Records records) {
        var original = new PluginSupplyChainVerifier(publisherKeys).verifyArtifact(new ArtifactVerificationRequest(path,
                pluginId, version, artifact.expectedSize(), artifact.sha256(), artifact.signature(),
                historical ? VerificationPolicy.installedCustom() : VerificationPolicy.customRepository()));
        requireVerified(original, "/package/signature");
        var descriptor = PluginPackageReader.inspect(path).descriptor();
        if (!records.review.descriptor().equals(DescriptorSnapshot.from(descriptor, records.submission))) {
            throw new ContractException("DESCRIPTOR_MISMATCH", "/package");
        }
        var community = communityVerifier.verifyCommunityPackage(new CommunityPackageVerificationRequest(path, repositoryId,
                pluginId, version, artifact.expectedSize(), artifact.sha256(), assuranceLevel, sourceCommit, reviewRef.sha256(),
                communitySignature, historical));
        requireVerified(community, "/communitySignature");
    }

    private static void requireVerified(VerificationResult result, String field) {
        if (result.status() != VerificationStatus.VERIFIED) {
            throw new ContractException(result.status().name(), field, Map.of("diagnostic", result.diagnosticCode()));
        }
    }
}
