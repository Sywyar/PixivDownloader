package top.sywyar.pixivdownload.sdk.community.operation;

import top.sywyar.pixivdownload.plugin.signature.PluginSupplyChainVerifier;
import top.sywyar.pixivdownload.plugin.signature.PluginTrustStores;
import top.sywyar.pixivdownload.plugin.signature.SignatureMetadata;
import top.sywyar.pixivdownload.plugin.signature.TrustedPluginKey;
import top.sywyar.pixivdownload.plugin.signature.VerificationStatus;
import top.sywyar.pixivdownload.plugin.signature.community.CommunityOperation;
import top.sywyar.pixivdownload.plugin.signature.community.CommunityOperationVerificationRequest;
import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;
import top.sywyar.pixivdownload.sdk.community.project.CommunityPaths;

import java.util.List;
import java.util.Map;

/** 三类操作共用请求身份和证明检查，密码学仍由供应链门面负责。 */
final class OperationChecks {
    private OperationChecks() { }

    static <T> T data(CommunityJson.Document document, CommunityJson.Kind kind, Class<T> type) {
        if (document.kind() != kind) throw new ContractException("SCHEMA_INVALID", "");
        String actual = CommunityJson.sha256(CommunityJson.canonicalBody(document));
        if (!actual.equals(document.value().get("requestId").textValue())) {
            throw new ContractException("REQUEST_ID_MISMATCH", "/requestId");
        }
        return document.as(type);
    }

    static void path(String actual, String expected) {
        CommunityPaths.relative(actual, false);
        if (!expected.equals(actual)) throw new ContractException("PATH_MISMATCH", "/path");
    }

    static void baseline(String expected, CommunityJson.Document current, String field) {
        if (current == null || !expected.equals(current.sha256())) throw new ContractException("BASELINE_CHANGED", field);
    }

    static void proof(CommunityJson.Document document, CommunityOperation operation,
                      SignatureMetadata signature, TrustedPluginKey key, String field) {
        if (signature == null) throw new ContractException("PROOF_REQUIRED", field);
        var result = new PluginSupplyChainVerifier(PluginTrustStores.of(List.of(key)))
                .verifyCommunityOperation(new CommunityOperationVerificationRequest(operation,
                        CommunityJson.canonicalBody(document), document.value().get("requestId").textValue(), signature, false));
        if (result.status() != VerificationStatus.VERIFIED) {
            throw new ContractException(result.status().name(), field, Map.of("diagnostic", result.diagnosticCode()));
        }
    }
}
