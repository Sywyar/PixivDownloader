package top.sywyar.pixivdownload.plugin.signature;

import top.sywyar.pixivdownload.plugin.signature.internal.trust.StaticPluginTrustStore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 不可变签名信任根存储的工厂方法。
 */
public final class PluginTrustStores {

    private PluginTrustStores() {
    }

    public static TrustedPluginKey builtInOfficialPluginRoot() {
        return OfficialArtifactTrustRoots.activePluginRoot();
    }

    public static PluginTrustStore builtInOfficialPlugins() {
        return new StaticPluginTrustStore(OfficialArtifactTrustRoots.pluginRoots());
    }

    public static PluginTrustStore builtInOfficialUpdates() {
        return new StaticPluginTrustStore(OfficialArtifactTrustRoots.updateRoots());
    }

    public static PluginTrustStore builtInOfficialFfmpeg() {
        return new StaticPluginTrustStore(OfficialArtifactTrustRoots.ffmpegRoots());
    }

    public static PluginTrustStore of(Collection<TrustedPluginKey> keys) {
        return new StaticPluginTrustStore(keys);
    }

    public static PluginTrustStore withBuiltInOfficialPlugins(Collection<TrustedPluginKey> additionalKeys) {
        List<TrustedPluginKey> keys = new ArrayList<>();
        keys.addAll(OfficialArtifactTrustRoots.pluginRoots());
        if (additionalKeys != null) {
            keys.addAll(additionalKeys);
        }
        return new StaticPluginTrustStore(keys);
    }
}
