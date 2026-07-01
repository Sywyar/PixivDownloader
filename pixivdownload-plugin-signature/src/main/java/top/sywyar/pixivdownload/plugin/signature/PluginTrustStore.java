package top.sywyar.pixivdownload.plugin.signature;

import java.util.Optional;

/**
 * 宿主 owned 的信任根查找入口。实现不得从 key id 字符串内容推断信任。
 */
public interface PluginTrustStore {

    Optional<TrustedPluginKey> findByKeyId(String keyId);
}
