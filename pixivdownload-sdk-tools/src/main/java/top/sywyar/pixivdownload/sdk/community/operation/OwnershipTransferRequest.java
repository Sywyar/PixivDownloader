package top.sywyar.pixivdownload.sdk.community.operation;

import com.fasterxml.jackson.annotation.JsonInclude;
import top.sywyar.pixivdownload.plugin.signature.SignatureMetadata;
import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.Owner;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.Reference;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;

import java.util.List;

/** 目标已存在时只引用其活动 key；请求不能夹带换钥或自报发起人。 */
public record OwnershipTransferRequest(int schemaVersion, Payload payload, String requestId, Proofs proofs) {
    public enum Mode { REGULAR, RECOVERY }
    public record TargetKey(String keyId, @JsonInclude(JsonInclude.Include.NON_NULL) String algorithm,
                            @JsonInclude(JsonInclude.Include.NON_NULL) String publicKeySpkiBase64) { }
    public record Payload(String pluginId, String pluginBindingSha256, Owner from, Owner to,
                          String targetPublisherRecordSha256, TargetKey targetKey,
                          @JsonInclude(JsonInclude.Include.NON_NULL) String targetPublisherDisplayName,
                          Mode mode, String explanation,
                          @JsonInclude(JsonInclude.Include.NON_NULL) List<Reference> recoveryEvidence) {
        public Payload { recoveryEvidence = recoveryEvidence == null ? null : List.copyOf(recoveryEvidence); }
    }
    public record Proofs(SignatureMetadata targetKey) { }

    public static OwnershipTransferRequest read(CommunityJson.Document document, String path) {
        var value = OperationChecks.data(document, CommunityJson.Kind.TRANSFER, OwnershipTransferRequest.class);
        Payload p = value.payload;
        OperationChecks.path(path, "ownership-transfers/" + p.pluginId + "/" + value.requestId + "/proposal.json");
        if (p.from.equals(p.to) || p.from.accountId().equals(p.to.accountId())
                && !p.from.accountType().equals(p.to.accountType())) {
            throw new ContractException("BINDING_MISMATCH", "/payload/to");
        }
        if (p.recoveryEvidence != null) p.recoveryEvidence.forEach(Reference::validate);
        return value;
    }
}
