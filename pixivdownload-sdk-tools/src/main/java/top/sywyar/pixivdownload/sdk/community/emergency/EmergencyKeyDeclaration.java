package top.sywyar.pixivdownload.sdk.community.emergency;

import top.sywyar.pixivdownload.plugin.signature.TrustedPluginKey;
import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.CommunityPr;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.Owner;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.Reference;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;
import top.sywyar.pixivdownload.sdk.community.identity.Publisher;
import top.sywyar.pixivdownload.sdk.community.operation.OperationAuthority;
import top.sywyar.pixivdownload.sdk.community.project.CommunityPaths;

import java.util.List;
import java.util.Map;

/** 仅停用后续社区授权；不改变发布者历史信任状态或既有版本的签名证据。 */
public record EmergencyKeyDeclaration(int schemaVersion, Payload payload, String requestId) {
    public record Key(String keyId, String fingerprint) { }
    public record Payload(String operation, Owner owner, String publisherRecordSha256, List<Key> keys) {
        public Payload { keys = List.copyOf(keys); }
    }
    public record Block(int schemaVersion, Owner owner, Key key, Reference requestRef, CommunityPr pr) {
        public String path() { return "key-blocks/" + key.fingerprint() + ".json"; }
        public CommunityJson.Document document() {
            return CommunityJson.parse(CommunityJson.Kind.EMERGENCY_KEY_BLOCK, CommunityJson.encode(this));
        }
        public static Block read(CommunityJson.Document document, String path) {
            if (document.kind() != CommunityJson.Kind.EMERGENCY_KEY_BLOCK) throw new ContractException("SCHEMA_INVALID", "");
            var value = document.as(Block.class);
            value.pr.validate();
            value.requestRef.validate();
            if (!value.path().equals(path) || !value.requestRef.path().startsWith("requests/" + value.owner.accountId()
                    + "/" + value.owner.publisherId() + "/")) throw new ContractException("PATH_MISMATCH", "/path");
            return value;
        }
        public void verifyRequest(CommunityJson.Document document) {
            requestRef.verify(document.bytes());
            var request = EmergencyKeyDeclaration.read(document, requestRef.path());
            if (!owner.equals(request.payload.owner) || !request.payload.keys.contains(key)
                    || "User".equals(owner.accountType()) && !owner.accountId().equals(pr.authorAccountId())) {
                throw new ContractException("EMERGENCY_RECORD_MISMATCH", "/requestRef");
            }
        }
    }

    public String path() { return "requests/" + payload.owner.accountId() + "/" + payload.owner.publisherId() + "/" + requestId + ".json"; }

    public static EmergencyKeyDeclaration read(CommunityJson.Document document, String path) {
        if (document.kind() != CommunityJson.Kind.EMERGENCY_REQUEST) throw new ContractException("SCHEMA_INVALID", "");
        var value = document.as(EmergencyKeyDeclaration.class);
        if (!CommunityJson.sha256(CommunityJson.canonicalBody(document)).equals(value.requestId)) {
            throw new ContractException("REQUEST_ID_MISMATCH", "/requestId");
        }
        CommunityPaths.relative(path, false);
        if (!value.path().equals(path)) throw new ContractException("PATH_MISMATCH", "/path");
        CommunityValues.unique(value.payload.keys, Key::keyId, "/payload/keys/keyId");
        CommunityValues.unique(value.payload.keys, Key::fingerprint, "/payload/keys/fingerprint");
        return value;
    }

    /** 原生 PR 与组织管理授权由可信平台适配器取得，不能来自申请正文。 */
    public List<Block> authorize(CommunityJson.Document request, CommunityJson.Document currentPublisher, OperationAuthority authority) {
        var actual = read(request, path());
        if (!equals(actual)) throw new ContractException("REQUEST_ID_MISMATCH", "/requestId");
        var publisher = Publisher.read(currentPublisher);
        if (!publisher.owner().equals(payload.owner)) throw new ContractException("BINDING_MISMATCH", "/payload/owner");
        if (!currentPublisher.sha256().equals(payload.publisherRecordSha256)) throw new ContractException("BASELINE_CHANGED", "/payload/publisherRecordSha256");
        authority.proposalPr().requireHuman(authority.actualAuthor());
        authority.requireRepresentative(payload.owner, authority.actualAuthor().id());
        for (var selected : payload.keys) {
            if (publisher.signingKeys().stream().noneMatch(key -> key.keyId().equals(selected.keyId)
                    && key.trusted(publisher.displayName()).publicKeyFingerprint().equals(selected.fingerprint))) {
                throw new ContractException("UNKNOWN_KEY", "/payload/keys");
            }
        }
        var reference = Reference.of(path(), request.bytes());
        return payload.keys.stream().map(key -> new Block(1, payload.owner, key, reference, authority.proposalPr())).toList();
    }

    /** 仅用于尚未生效的操作；历史包复验不得调用此拒绝规则。 */
    public static void requireAllowed(TrustedPluginKey key, List<Block> blocks) {
        if (blocks.stream().anyMatch(block -> block.key.fingerprint.equals(key.publicKeyFingerprint()))) {
            throw new ContractException("KEY_DECLARED_COMPROMISED", "/keyId", Map.of("keyId", key.keyId()));
        }
    }
}
