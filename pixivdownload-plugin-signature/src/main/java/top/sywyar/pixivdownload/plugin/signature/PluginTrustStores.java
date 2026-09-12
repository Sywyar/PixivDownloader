package top.sywyar.pixivdownload.plugin.signature;

import top.sywyar.pixivdownload.plugin.signature.internal.trust.StaticPluginTrustStore;
import top.sywyar.pixivdownload.plugin.signature.internal.trust.KeyParsing;

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

    /** 社区输入核对规范 SPKI 并排除官方公钥，历史 key 状态仍由验签策略判断。 */
    public static PluginTrustStore community(Collection<TrustedPluginKey> keys) {
        List<TrustedPluginKey> snapshot = List.copyOf(keys);
        for (TrustedPluginKey key : snapshot) {
            if (OfficialArtifactTrustRoots.isOfficialKey(key)) throw new IllegalArgumentException("community key must not be official");
            KeyParsing.canonicalEd25519PublicKey(key.publicKeySpkiBase64());
        }
        return of(snapshot);
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
