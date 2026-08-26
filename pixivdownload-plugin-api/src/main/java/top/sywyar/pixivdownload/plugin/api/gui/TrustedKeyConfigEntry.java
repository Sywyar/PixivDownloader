package top.sywyar.pixivdownload.plugin.api.gui;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** 单个插件仓库的工具包无关公开信任密钥配置。 */
public record TrustedKeyConfigEntry(String keyId, String algorithm, String publicKey, String state,
                                    String publisher, String trustLabel, Map<String, Object> extraFields) {
    /**
     * 校验并防御性复制一项公开信任密钥配置。
     *
     * @param keyId 稳定密钥 id
     * @param algorithm 签名算法
     * @param publicKey 编码后的公钥
     * @param state 信任密钥状态
     * @param publisher 发布者身份
     * @param trustLabel 用户可见的信任标签
     * @param extraFields 往返时保留的未知字段
     */
    public TrustedKeyConfigEntry {
        keyId = keyId == null ? "" : keyId.trim();
        algorithm = algorithm == null || algorithm.isBlank() ? "Ed25519" : algorithm.trim();
        publicKey = publicKey == null ? "" : publicKey.trim();
        state = state == null || state.isBlank() ? "ACTIVE" : state.trim().toUpperCase(Locale.ROOT);
        publisher = publisher == null ? "" : publisher.trim();
        trustLabel = trustLabel == null ? "" : trustLabel.trim();
        extraFields = extraFields == null ? new LinkedHashMap<>() : new LinkedHashMap<>(extraFields);
    }

    /**
     * 创建不含未知字段的信任密钥配置。
     *
     * @param keyId 稳定密钥 id
     * @param algorithm 签名算法
     * @param publicKey 编码后的公钥
     * @param state 信任密钥状态
     * @param publisher 发布者身份
     * @param trustLabel 用户可见的信任标签
     * @return 信任密钥配置
     */
    public static TrustedKeyConfigEntry create(String keyId, String algorithm, String publicKey, String state,
                                               String publisher, String trustLabel) {
        return new TrustedKeyConfigEntry(keyId, algorithm, publicKey, state, publisher, trustLabel,
                new LinkedHashMap<>());
    }
}
