package top.sywyar.pixivdownload.plugin.signature;

import java.util.List;

/** 随客户端发布的社区公钥；目录、社区包与撤销分别使用既有签名域。 */
public final class CommunityPluginTrustRoots {
    private static final TrustedPluginKey ROOT = new TrustedPluginKey(
            "community-release-b1cbdc37-b767-49fe-8f84-3b85bfde33b4",
            SignatureMetadata.ED25519,
            "MCowBQYDK2VwAyEARsZpHHn9fGnxc49pm/XdUVxwuPkRQ2H9oVNb/YSwzBA=",
            TrustedPluginKey.State.ACTIVE, "PixivDownloader Community", "COMMUNITY", false);

    private CommunityPluginTrustRoots() { }

    /** 不接受远端描述符或用户配置为内置社区根增加公钥。 */
    public static List<TrustedPluginKey> roots() { return List.of(ROOT); }

    public static PluginTrustStore trustStore() { return PluginTrustStores.community(roots()); }

    /** 按公钥字节识别用途，改 keyId 不会改变归属。 */
    public static boolean contains(TrustedPluginKey key) {
        return key != null && roots().stream()
                .anyMatch(root -> root.publicKeyFingerprint().equals(key.publicKeyFingerprint()));
    }
}
