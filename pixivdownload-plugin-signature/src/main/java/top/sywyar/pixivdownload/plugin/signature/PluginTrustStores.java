package top.sywyar.pixivdownload.plugin.signature;

import top.sywyar.pixivdownload.plugin.signature.internal.trust.StaticPluginTrustStore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 不可变插件信任根存储的工厂方法。
 */
public final class PluginTrustStores {

    private PluginTrustStores() {
    }

    public static TrustedPluginKey builtInOfficialRoot() {
        return OfficialPluginTrustRoots.activeRoot();
    }

    public static PluginTrustStore builtInOfficial() {
        return new StaticPluginTrustStore(OfficialPluginTrustRoots.all());
    }

    public static PluginTrustStore of(Collection<TrustedPluginKey> keys) {
        return new StaticPluginTrustStore(keys);
    }

    public static PluginTrustStore withBuiltInOfficial(Collection<TrustedPluginKey> additionalKeys) {
        List<TrustedPluginKey> keys = new ArrayList<>();
        keys.addAll(OfficialPluginTrustRoots.all());
        if (additionalKeys != null) {
            keys.addAll(additionalKeys);
        }
        return new StaticPluginTrustStore(keys);
    }
}
