package top.sywyar.pixivdownload.plugin.signature;

import java.util.List;

/**
 * 宿主内置的官方插件发布者信任根。
 *
 * <p>这些公钥是官方插件供应链的根信任来源，随宿主版本发布；不得从插件包、catalog manifest 或远程仓库动态建立官方信任。
 * 轮换时应先发布包含新公钥的宿主版本，再用对应私钥签发新的官方插件产物。
 */
public final class OfficialPluginTrustRoots {

    public static final String OFFICIAL_KEY_ID = "pixivdownloader-official-root-2026-07";

    public static final String OFFICIAL_PUBLIC_KEY_SPKI_BASE64 =
            "MCowBQYDK2VwAyEA/Up/QM6i/q+vJA2Jb6W59H1Utq/A18v1vcfRu6yiNmI=";

    private static final TrustedPluginKey OFFICIAL_ROOT = new TrustedPluginKey(
            OFFICIAL_KEY_ID,
            SignatureMetadata.ED25519,
            OFFICIAL_PUBLIC_KEY_SPKI_BASE64,
            TrustedPluginKey.State.ACTIVE,
            "PixivDownloader",
            "PixivDownloader official plugin root",
            true);

    private OfficialPluginTrustRoots() {
    }

    public static TrustedPluginKey activeRoot() {
        return OFFICIAL_ROOT;
    }

    public static List<TrustedPluginKey> all() {
        return List.of(OFFICIAL_ROOT);
    }
}
