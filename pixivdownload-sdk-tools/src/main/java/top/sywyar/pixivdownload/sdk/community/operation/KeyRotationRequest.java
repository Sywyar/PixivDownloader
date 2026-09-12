package top.sywyar.pixivdownload.sdk.community.operation;

import com.fasterxml.jackson.annotation.JsonInclude;
import top.sywyar.pixivdownload.plugin.signature.SignatureMetadata;
import top.sywyar.pixivdownload.plugin.signature.TrustedPluginKey;
import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.Account;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.Owner;

/** 换钥正文不携带其它身份或版本状态变化。 */
public record KeyRotationRequest(int schemaVersion, Payload payload, String requestId, Proofs proofs) {
    public enum Reason { ROUTINE_ROTATION, KEY_LOST, KEY_COMPROMISED }
    public record PublicKey(String keyId, String algorithm, String publicKeySpkiBase64) {
        public TrustedPluginKey trusted(String publisher) {
            return new TrustedPluginKey(keyId, algorithm, publicKeySpkiBase64,
                    TrustedPluginKey.State.ACTIVE, publisher, "community", false);
        }
    }
    public record Payload(String publisherId, Account githubAccount, String publisherRecordSha256,
                          String oldKeyId, PublicKey newKey, Reason reasonCode, String explanation) {
        public Owner owner() { return new Owner(githubAccount.id(), githubAccount.type(), publisherId); }
    }
    public record Proofs(SignatureMetadata newKey, @JsonInclude(JsonInclude.Include.NON_NULL) SignatureMetadata oldKey) { }

    public static KeyRotationRequest read(CommunityJson.Document document, String path) {
        var value = OperationChecks.data(document, CommunityJson.Kind.ROTATION, KeyRotationRequest.class);
        OperationChecks.path(path, "key-rotations/" + value.payload.githubAccount.id() + "/"
                + value.payload.publisherId + "/" + value.requestId + ".json");
        return value;
    }
}
