package top.sywyar.pixivdownload.sdk.community.operation;

import top.sywyar.pixivdownload.plugin.signature.TrustedPluginKey;
import top.sywyar.pixivdownload.plugin.signature.community.CommunityOperation;
import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;
import top.sywyar.pixivdownload.sdk.community.identity.Publisher;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 换钥只更新同一发布者的 key 历史；版本处置与当前插件归属不参与此操作。 */
public final class PublisherKeyRotation {
    private PublisherKeyRotation() { }

    public static OperationResult.Outcome apply(OperationContext context, CommunityJson.Document currentPublisher) {
        var document = context.document(CommunityJson.Kind.ROTATION);
        var request = KeyRotationRequest.read(document, context.request().reference().path());
        var replay = context.replay(document);
        if (replay != null) return replay;
        var p = request.payload();
        OperationChecks.baseline(p.publisherRecordSha256(), currentPublisher, "/payload/publisherRecordSha256");
        var publisher = Publisher.read(currentPublisher);
        if (!publisher.owner().equals(p.owner()) || !publisher.activeKey().keyId().equals(p.oldKeyId())) {
            throw new ContractException("BINDING_MISMATCH", "/payload/oldKeyId");
        }
        if (publisher.signingKeys().stream().anyMatch(key -> key.keyId().equals(p.newKey().keyId()))) {
            throw ContractException.invalid("KEY_ID_REUSED", "/payload/newKey/keyId");
        }
        var authority = context.authority();
        authority.requireApproval(request.requestId(), request.proofs().oldKey() == null);
        authority.requireRepresentative(publisher.owner(), authority.actualAuthor().id());
        OperationChecks.proof(document, CommunityOperation.PUBLISHER_KEY_ROTATION, request.proofs().newKey(),
                p.newKey().trusted(publisher.displayName()), "/proofs/newKey");
        if (request.proofs().oldKey() != null) OperationChecks.proof(document, CommunityOperation.PUBLISHER_KEY_ROTATION,
                request.proofs().oldKey(), publisher.activeKey().trusted(publisher.displayName()), "/proofs/oldKey");
        var next = new ArrayList<Publisher.SigningKey>();
        for (var key : publisher.signingKeys()) next.add(key.keyId().equals(p.oldKeyId())
                ? key.withState(p.reasonCode() == KeyRotationRequest.Reason.KEY_COMPROMISED
                    ? TrustedPluginKey.State.REVOKED : TrustedPluginKey.State.RETIRED) : key);
        next.add(new Publisher.SigningKey(p.newKey().keyId(), p.newKey().algorithm(), p.newKey().publicKeySpkiBase64(), TrustedPluginKey.State.ACTIVE));
        var updated = new Publisher(1, publisher.publisherId(), publisher.displayName(), publisher.githubAccount(), next).document();
        var before = OperationContext.archive(currentPublisher.bytes());
        var after = OperationContext.archive(updated.bytes());
        return context.finish(document, before, after, Map.of(publisher.path(), after), List.of(), List.of(authority.proposalPr()), null);
    }
}
