package top.sywyar.pixivdownload.plugin.signature;

import top.sywyar.pixivdownload.plugin.signature.internal.trust.StaticPluginTrustStore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 不可变插件信任根存储的工厂方法。
 */
public final class PluginTrustStores {

    private static final TrustedPluginKey OFFICIAL_ROOT = new TrustedPluginKey(
            "pixivdownloader-official-root-2026-07",
            SignatureMetadata.ED25519,
            "MCowBQYDK2VwAyEA8no36HyWNxrjbl10qGcIumILxcgau/0egy3RODVNUIc=",
            TrustedPluginKey.State.ACTIVE,
            "PixivDownloader",
            "PixivDownloader official plugin root",
            true);

    private PluginTrustStores() {
    }

    public static TrustedPluginKey builtInOfficialRoot() {
        return OFFICIAL_ROOT;
    }

    public static PluginTrustStore builtInOfficial() {
        return new StaticPluginTrustStore(List.of(OFFICIAL_ROOT));
    }

    public static PluginTrustStore of(Collection<TrustedPluginKey> keys) {
        return new StaticPluginTrustStore(keys);
    }

    public static PluginTrustStore withBuiltInOfficial(Collection<TrustedPluginKey> additionalKeys) {
        List<TrustedPluginKey> keys = new ArrayList<>();
        keys.add(OFFICIAL_ROOT);
        if (additionalKeys != null) {
            keys.addAll(additionalKeys);
        }
        return new StaticPluginTrustStore(keys);
    }
}
