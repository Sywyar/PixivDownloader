package top.sywyar.pixivdownload.plugin.signature;

import java.util.List;

/**
 * 宿主内置的官方产物信任根注册表。
 *
 * <p>插件、应用更新与 FFmpeg 分别使用独立密钥；调用方只能取得本用途的根集合，不能把全部官方公钥合成万能 trust store。
 * 公钥随宿主版本发布，不得从产物、manifest 或远程仓库动态建立官方信任。轮换时应先发布包含新公钥的宿主版本，
 * 再用对应私钥签发该用途的新产物。
 */
public final class OfficialArtifactTrustRoots {

    public static final String PLUGIN_KEY_ID = "pixivdownloader-official-root-2026-07";
    public static final String PLUGIN_PUBLIC_KEY_SPKI_BASE64 =
            "MCowBQYDK2VwAyEA/Up/QM6i/q+vJA2Jb6W59H1Utq/A18v1vcfRu6yiNmI=";
    public static final String UPDATE_KEY_ID = "pixivdownloader-update-root-2026-08";
    public static final String UPDATE_PUBLIC_KEY_SPKI_BASE64 =
            "MCowBQYDK2VwAyEAeMnkM0bMsOyWkfugXxyWTHI2GikTUxFeXt5ss+KTaaY=";
    public static final String FFMPEG_KEY_ID = "pixivdownloader-ffmpeg-root-2026-08";
    public static final String FFMPEG_PUBLIC_KEY_SPKI_BASE64 =
            "MCowBQYDK2VwAyEAUs4EYxQt/1WPC1pMppNhXmVITu7OmTRJVbW4NbTYXXQ=";

    private static final TrustedPluginKey PLUGIN_ROOT = root(
            PLUGIN_KEY_ID, PLUGIN_PUBLIC_KEY_SPKI_BASE64, "PixivDownloader official plugin root");
    private static final TrustedPluginKey UPDATE_ROOT = root(
            UPDATE_KEY_ID, UPDATE_PUBLIC_KEY_SPKI_BASE64, "PixivDownloader official update root");
    private static final TrustedPluginKey FFMPEG_ROOT = root(
            FFMPEG_KEY_ID, FFMPEG_PUBLIC_KEY_SPKI_BASE64, "PixivDownloader official FFmpeg root");

    private OfficialArtifactTrustRoots() {
    }

    public static TrustedPluginKey activePluginRoot() {
        return PLUGIN_ROOT;
    }

    public static List<TrustedPluginKey> pluginRoots() {
        return List.of(PLUGIN_ROOT);
    }

    public static TrustedPluginKey activeUpdateRoot() {
        return UPDATE_ROOT;
    }

    public static List<TrustedPluginKey> updateRoots() {
        return List.of(UPDATE_ROOT);
    }

    public static TrustedPluginKey activeFfmpegRoot() {
        return FFMPEG_ROOT;
    }

    public static List<TrustedPluginKey> ffmpegRoots() {
        return List.of(FFMPEG_ROOT);
    }

    private static TrustedPluginKey root(String keyId, String publicKey, String trustLabel) {
        return new TrustedPluginKey(
                keyId,
                SignatureMetadata.ED25519,
                publicKey,
                TrustedPluginKey.State.ACTIVE,
                "PixivDownloader",
                trustLabel,
                true);
    }
}
