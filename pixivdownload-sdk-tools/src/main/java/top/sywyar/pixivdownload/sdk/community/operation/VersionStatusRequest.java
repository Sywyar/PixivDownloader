package top.sywyar.pixivdownload.sdk.community.operation;

import com.fasterxml.jackson.annotation.JsonInclude;
import top.sywyar.pixivdownload.plugin.signature.SignatureMetadata;
import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.Account;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.Owner;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;

import java.util.Set;

/** 状态动作与原因分别核对，恢复只能引用一份已存在的 YANK 决定。 */
public record VersionStatusRequest(int schemaVersion, Payload payload, String requestId, Proofs proofs) {
    public enum Action {
        YANK(Set.of("FUNCTIONAL_DEFECT", "COMPATIBILITY_PROBLEM", "MAINTAINER_WITHDRAWAL", "LICENSE_ISSUE", "OTHER")),
        UNYANK(Set.of("ISSUE_RESOLVED", "YANK_IN_ERROR", "OTHER")),
        REVOKE(Set.of("MALICIOUS_CODE", "KEY_COMPROMISE", "CRITICAL_VULNERABILITY", "ARTIFACT_TAMPERING", "OTHER"));
        private final Set<String> reasons;
        Action(Set<String> reasons) { this.reasons = reasons; }
    }
    public record Payload(Owner owner, Account requester, String pluginBindingSha256, String pluginId,
                          String version, String packageSha256, Action action, String reasonCode, String explanation,
                          @JsonInclude(JsonInclude.Include.NON_NULL) String yankedDecisionSha256) { }
    public record Proofs(@JsonInclude(JsonInclude.Include.NON_NULL) SignatureMetadata activeKey) { }

    public static VersionStatusRequest read(CommunityJson.Document document, String path) {
        var value = OperationChecks.data(document, CommunityJson.Kind.STATUS_REQUEST, VersionStatusRequest.class);
        Payload p = value.payload;
        OperationChecks.path(path, "version-status-requests/" + p.owner.accountId() + "/" + p.pluginId + "/"
                + p.version + "/" + value.requestId + ".json");
        CommunityValues.version(p.version, "/payload/version");
        if (!"User".equals(p.requester.type())) throw new ContractException("BINDING_MISMATCH", "/payload/requester/type");
        if (!p.action.reasons.contains(p.reasonCode) || p.explanation.isBlank()) {
            throw new ContractException("SCHEMA_INVALID", "/payload/reasonCode");
        }
        return value;
    }
}
