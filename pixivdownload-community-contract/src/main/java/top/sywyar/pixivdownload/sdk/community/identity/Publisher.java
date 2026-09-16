package top.sywyar.pixivdownload.sdk.community.identity;

import top.sywyar.pixivdownload.plugin.signature.PluginTrustStore;
import top.sywyar.pixivdownload.plugin.signature.PluginTrustStores;
import top.sywyar.pixivdownload.plugin.signature.TrustedPluginKey;
import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.Owner;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;
import top.sywyar.pixivdownload.sdk.community.project.CommunityPaths;

import java.util.Comparator;
import java.util.List;

/** 发布者身份与完整 key 历史；路径中的数字账号不等于投稿 PR 的自然人。 */
public record Publisher(int schemaVersion, String publisherId, String displayName, GithubAccount githubAccount,
                        List<SigningKey> signingKeys) {
    public record GithubAccount(String id, String type, String loginAtRegistration) { }
    public record SigningKey(String keyId, String algorithm, String publicKeySpkiBase64, TrustedPluginKey.State state) {
        public TrustedPluginKey trusted(String publisher) {
            return new TrustedPluginKey(keyId, algorithm, publicKeySpkiBase64, state, publisher, "community", false);
        }
        public SigningKey withState(TrustedPluginKey.State next) { return new SigningKey(keyId, algorithm, publicKeySpkiBase64, next); }
    }
    public Publisher { signingKeys = List.copyOf(signingKeys); }
    public Owner owner() { return new Owner(githubAccount.id, githubAccount.type, publisherId); }
    public String path() { return "publishers/" + githubAccount.id + "/" + publisherId + ".json"; }

    /** 历史副本保持原始顺序与字节，不能用当前 publisher 替代这份文档。 */
    public static Publisher read(CommunityJson.Document document) {
        if (document.kind() != CommunityJson.Kind.PUBLISHER) throw new ContractException("SCHEMA_INVALID", "");
        var value = document.as(Publisher.class);
        CommunityValues.unique(value.signingKeys, SigningKey::keyId, "/signingKeys/keyId");
        if (value.signingKeys.stream().filter(key -> key.state == TrustedPluginKey.State.ACTIVE).count() != 1) {
            throw ContractException.invalid("ACTIVE_KEY_COUNT", "/signingKeys");
        }
        value.trustStore();
        return value;
    }

    public static Publisher read(CommunityJson.Document document, String path) {
        var value = read(document);
        CommunityPaths.relative(path, false);
        if (!value.path().equals(path)) throw new ContractException("PATH_MISMATCH", "/path");
        return value;
    }

    public PluginTrustStore trustStore() {
        try { return PluginTrustStores.community(signingKeys.stream().map(key -> key.trusted(displayName)).toList()); }
        catch (IllegalArgumentException e) { throw new ContractException("MALFORMED_SIGNATURE", "/signingKeys"); }
    }

    public SigningKey activeKey() {
        return signingKeys.stream().filter(key -> key.state == TrustedPluginKey.State.ACTIVE).findFirst()
                .orElseThrow(() -> ContractException.invalid("ACTIVE_KEY_COUNT", "/signingKeys"));
    }

    /** 只在生成新记录时排序；实际编码经过同一文档预算，超限不截断旧 key。 */
    public CommunityJson.Document document() {
        var sorted = new Publisher(schemaVersion, publisherId, displayName, githubAccount,
                signingKeys.stream().sorted(Comparator.comparing(SigningKey::keyId)).toList());
        var document = CommunityJson.parse(CommunityJson.Kind.PUBLISHER, CommunityJson.encode(sorted));
        read(document);
        return document;
    }
}
