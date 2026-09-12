package top.sywyar.pixivdownload.sdk.community.operation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import top.sywyar.pixivdownload.plugin.signature.SignatureMetadata;
import top.sywyar.pixivdownload.plugin.signature.TrustedPluginKey;
import top.sywyar.pixivdownload.plugin.signature.community.CommunityOperation;
import top.sywyar.pixivdownload.plugin.signature.internal.envelope.EnvelopeV1Codec;
import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.CommunityPr;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.*;
import top.sywyar.pixivdownload.sdk.community.identity.Publisher;

import java.security.KeyPair;
import java.security.Signature;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 用真实测试 key 构造请求；平台身份是显式夹具，不连接 GitHub。 */
final class OperationTestInputs {
    static final String HEAD = "ab".repeat(20);
    static final String NOW = "2025-02-03T04:05:06Z";
    static final String HASH = "ab".repeat(32);
    static final SignatureMetadata PLACEHOLDER = new SignatureMetadata(1, "Ed25519", "placeholder", Base64.getEncoder().encodeToString(new byte[64]));
    private OperationTestInputs() { }

    static String spki(KeyPair pair) { return Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()); }
    static Publisher publisher(String account, String id, String keyId, KeyPair pair) {
        return new Publisher(1, id, "Publisher", new Publisher.GithubAccount(account, "User", "login"),
                List.of(new Publisher.SigningKey(keyId, "Ed25519", spki(pair), TrustedPluginKey.State.ACTIVE)));
    }
    static CommunityJson.Document request(CommunityJson.Kind kind, Object value) {
        var tree = (ObjectNode) CommunityJson.strictTree(CommunityJson.encode(value), kind.maximumBytes());
        var original = CommunityJson.parse(kind, CommunityJson.encode(tree));
        tree.put("requestId", CommunityJson.sha256(CommunityJson.canonicalBody(original)));
        return CommunityJson.parse(kind, CommunityJson.encode(tree));
    }
    static CommunityJson.Document proof(CommunityJson.Document document, String field, String keyId, KeyPair pair) throws Exception {
        var operation = switch (document.kind()) {
            case ROTATION -> CommunityOperation.PUBLISHER_KEY_ROTATION;
            case STATUS_REQUEST -> CommunityOperation.VERSION_STATUS_REQUEST;
            case TRANSFER -> CommunityOperation.OWNERSHIP_TRANSFER;
            default -> throw new IllegalArgumentException();
        };
        byte[] bytes = CommunityJson.canonicalBody(document);
        var signer = Signature.getInstance("Ed25519"); signer.initSign(pair.getPrivate());
        signer.update(EnvelopeV1Codec.communityOperationMessage(operation, "Ed25519", keyId, bytes.length,
                HexFormat.of().parseHex(document.value().get("requestId").textValue())));
        var tree = (ObjectNode) document.value();
        ((ObjectNode) tree.get("proofs")).set(field, CommunityJson.strictTree(CommunityJson.encode(
                new SignatureMetadata(1, "Ed25519", keyId, Base64.getEncoder().encodeToString(signer.sign()))), 1024));
        return CommunityJson.parse(document.kind(), CommunityJson.encode(tree));
    }
    static OperationContext context(CommunityJson.Document request, String path, String author, boolean recovery) {
        String requestId = request.value().get("requestId").textValue();
        byte[] receipt = CommunityJson.encode(Map.of("requestId", requestId, "headSha", HEAD, "recoveryApproved", recovery));
        var evidence = new Evidence(Reference.of("approvals/" + requestId + ".json", receipt), receipt);
        var pr = pr(author, 1);
        var authority = new OperationAuthority(pr, new Account(author, "User"), List.of(),
                new OperationAuthority.Approval(requestId, HEAD, Set.of("999"), recovery, evidence), Set.of("999"));
        return new OperationContext(new Evidence(Reference.of(path, request.bytes()), request.bytes()), authority,
                recovery ? List.of(evidence.reference()) : null, NOW, Map.of(evidence.reference().path(), evidence), Map.of());
    }
    static CommunityPr pr(String author, long number) {
        return new CommunityPr("300", number, author, "400", HEAD, "cd".repeat(20), "ef".repeat(20));
    }
}
