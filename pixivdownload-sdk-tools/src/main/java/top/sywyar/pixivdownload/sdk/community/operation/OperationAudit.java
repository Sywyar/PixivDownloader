package top.sywyar.pixivdownload.sdk.community.operation;

import com.fasterxml.jackson.annotation.JsonInclude;
import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.CommunityPr;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.Evidence;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.Reference;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;

import java.util.List;
import java.util.Map;

/** 操作执行后的不可变事实；先保存前后状态及批准字节，再形成审计，避免循环摘要。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OperationAudit(int schemaVersion, String requestId, String action, Reference requestRef,
                              Reference beforeRef, Reference afterRef, Reference decisionRef, String actorAccountId,
                              List<String> reviewerAccountIds, List<CommunityPr> prEvidence,
                              List<Reference> recoveryEvidence, List<Reference> relatedRecords,
                              String result, String appliedAt, Long revocationSequence, String authorization) {
    public OperationAudit {
        reviewerAccountIds = List.copyOf(reviewerAccountIds);
        prEvidence = List.copyOf(prEvidence);
        recoveryEvidence = recoveryEvidence == null ? null : List.copyOf(recoveryEvidence);
        relatedRecords = List.copyOf(relatedRecords);
    }

    public static OperationAudit read(CommunityJson.Document document) {
        if (document.kind() != CommunityJson.Kind.AUDIT) throw new ContractException("SCHEMA_INVALID", "");
        var audit = document.as(OperationAudit.class);
        boolean status = List.of("YANK", "UNYANK", "REVOKE").contains(audit.action);
        if (status != (audit.revocationSequence != null)) throw new ContractException("SCHEMA_INVALID", "/revocationSequence");
        if ("SIGNED_OWNER".equals(audit.authorization) ? !(status || List.of("PUBLISHER_KEY_ROTATION", "OWNERSHIP_TRANSFER").contains(audit.action)) || !audit.reviewerAccountIds.isEmpty()
                || audit.recoveryEvidence != null : audit.reviewerAccountIds.isEmpty()) {
            throw new ContractException("REVIEW_MISMATCH", "/authorization");
        }
        audit.prEvidence.forEach(pr -> {
            pr.validate();
            if (pr.mergeSha() == null && !"PREPARED".equals(audit.result)) {
                throw new ContractException("REVIEW_MISMATCH", "/prEvidence/mergeSha");
            }
        });
        if ("PREPARED".equals(audit.result) && audit.prEvidence.stream().filter(pr -> pr.mergeSha() == null).count() != 1) {
            throw new ContractException("REVIEW_MISMATCH", "/prEvidence/mergeSha");
        }
        CommunityValues.unique(audit.prEvidence, pr -> pr.githubRepositoryId() + "/" + pr.number(), "/prEvidence");
        CommunityValues.unique(audit.relatedRecords, Reference::path, "/relatedRecords");
        return audit;
    }

    /** 结构、摘要和实际批准分别核对；有效审计不自行赋予重放或当前管理权。 */
    public void verify(CommunityJson.Document request, OperationAuthority authority, Reference expectedBefore,
                       Reference expectedAfter, List<CommunityPr> expectedPrs, List<Reference> expectedRecovery,
                       Long expectedSequence, List<Reference> expectedRelated, Map<String, Evidence> evidence) {
        String expectedId = CommunityJson.sha256(CommunityJson.canonicalBody(request));
        String expectedAction = action(request);
        if (!requestId.equals(expectedId) || !requestId.equals(request.value().get("requestId").textValue())
                || !action.equals(expectedAction)) throw new ContractException("REQUEST_ID_MISMATCH", "/requestId");
        var data = request.value();
        boolean recovery = switch (request.kind()) {
            case ROTATION -> !data.get("proofs").has("oldKey");
            case STATUS_REQUEST -> !data.get("proofs").has("activeKey");
            case TRANSFER -> "RECOVERY".equals(data.get("payload").get("mode").textValue());
            default -> throw new ContractException("SCHEMA_INVALID", "/requestRef");
        };
        authority.requireAuthorization(request, recovery);
        if (recovery && (expectedRecovery == null || expectedRecovery.isEmpty())) {
            throw new ContractException("RECOVERY_REVIEW_REQUIRED", "/recoveryEvidence");
        }
        if (!beforeRef.equals(expectedBefore) || !afterRef.equals(expectedAfter)
                || !actorAccountId.equals(authority.actualAuthor().id())
                || !decisionRef.equals(authority.decisionEvidence().reference())
                || !reviewerAccountIds.equals(authority.reviewerIds())
                || !java.util.Objects.equals(authorization, authority.signedStatus() == null ? null : "SIGNED_OWNER")
                || !prEvidence.equals(expectedPrs) || !prEvidence.contains(authority.proposalPr())
                || !java.util.Objects.equals(recoveryEvidence, expectedRecovery)
                || !relatedRecords.equals(expectedRelated)
                || !java.util.Objects.equals(revocationSequence, expectedSequence)) {
            throw new ContractException("REVIEW_MISMATCH", "/audit");
        }
        requestRef.verify(request.bytes());
        for (var ref : List.of(requestRef, beforeRef, afterRef, decisionRef)) CommunityValues.requireEvidence(ref, evidence);
        if (recoveryEvidence != null) recoveryEvidence.forEach(ref -> CommunityValues.requireEvidence(ref, evidence));
        relatedRecords.forEach(ref -> CommunityValues.requireEvidence(ref, evidence));
    }

    public static CommunityJson.Document create(CommunityJson.Document request, Reference requestRef,
                                                Reference before, Reference after, OperationAuthority authority,
                                                List<CommunityPr> prs, List<Reference> recovery, String appliedAt,
                                                Long sequence, List<Reference> related, Map<String, Evidence> evidence) {
        String expectedAction = action(request);
        String requestId = request.value().get("requestId").textValue();
        authority.requireAuthorization(request, false);
        var value = new OperationAudit(1, requestId, expectedAction, requestRef, before, after,
                authority.decisionEvidence().reference(), authority.actualAuthor().id(),
                authority.reviewerIds(), prs, recovery, related, prs.stream().anyMatch(pr -> pr.mergeSha() == null) ? "PREPARED" : "APPLIED",
                appliedAt, sequence, authority.signedStatus() == null ? null : "SIGNED_OWNER");
        var document = CommunityJson.parse(CommunityJson.Kind.AUDIT, CommunityJson.encode(value));
        read(document).verify(request, authority, before, after, prs, recovery, sequence, related, evidence);
        return document;
    }

    /** 平台须另外核实受保护生成来源和完整树差异；父链本身不授予执行权限。 */
    public void confirmPreparedMerge(CommunityJson.Document document, CommunityPr merged, Reference exactRecord,
                                     List<String> generatedParents, List<String> mergeParents) {
        merged.validate();
        exactRecord.verify(document.bytes());
        var original = prEvidence.stream().filter(pr -> pr.mergeSha() == null).findFirst()
                .orElseThrow(() -> new ContractException("REVIEW_MISMATCH", "/preparedMerge"));
        if (!equals(read(document)) || !"PREPARED".equals(result) || merged.mergeSha() == null
                || !original.githubRepositoryId().equals(merged.githubRepositoryId())
                || original.number() != merged.number() || !original.authorAccountId().equals(merged.authorAccountId())
                || !original.headRepositoryId().equals(merged.headRepositoryId())
                || !original.baseSha().equals(merged.baseSha()) || original.headSha().equals(merged.headSha())
                || !original.hasGeneratedParents(generatedParents)
                || !mergeParents.equals(List.of(original.baseSha(), merged.headSha()))) {
            throw new ContractException("REVIEW_MISMATCH", "/preparedMerge");
        }
    }

    private static String action(CommunityJson.Document request) {
        return switch (request.kind()) {
            case ROTATION -> "PUBLISHER_KEY_ROTATION";
            case STATUS_REQUEST -> request.value().get("payload").get("action").textValue();
            case TRANSFER -> "OWNERSHIP_TRANSFER";
            default -> throw new ContractException("SCHEMA_INVALID", "/requestRef");
        };
    }
}
