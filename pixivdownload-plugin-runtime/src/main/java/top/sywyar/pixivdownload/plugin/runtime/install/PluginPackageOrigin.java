package top.sywyar.pixivdownload.plugin.runtime.install;

import java.util.Objects;

/**
 * 一个待安装插件包的来源描述：来源类别 + 该来源声明的可选完整性期望（期望大小 / SHA-256 / 签名）。安装器据来源决定
 * 是否在落盘前做完整性校验（{@link PluginPackageIntegrity}）。
 *
 * <h2>完整性期望只来自受信来源</h2>
 * 完整性期望（{@link #expectedSizeBytes()} / {@link #expectedSha256()} / {@link #expectedSignature()}）一律来自
 * <b>受信插件目录元数据</b>（{@link PluginPackageSource#MARKET_CATALOG}），<b>绝不</b>来自用户输入。本地上传
 * （{@link PluginPackageSource#LOCAL_UPLOAD}）没有可信清单背书、不携带任何期望——其安全性由安装器的结构校验、
 * 资源规模上限与 Zip Slip 防护承担。本类只建模来源与期望，<b>不</b>发起任何下载 / 网络访问。
 *
 * @param source            来源类别
 * @param expectedSizeBytes 受信清单声明的期望文件字节数（无则 {@code null}）
 * @param expectedSha256    受信清单声明的期望 SHA-256（十六进制，无则 {@code null}）
 * @param expectedSignature 受信清单声明的期望签名（保留位，无则 {@code null}）
 */
public record PluginPackageOrigin(
        PluginPackageSource source,
        Long expectedSizeBytes,
        String expectedSha256,
        String expectedSignature) {

    public PluginPackageOrigin {
        Objects.requireNonNull(source, "source");
        if (source == PluginPackageSource.LOCAL_UPLOAD
                && (expectedSizeBytes != null || hasText(expectedSha256) || hasText(expectedSignature))) {
            // 本地上传无可信清单背书，不得携带完整性期望（防止把不可信的「期望」当成已校验）。
            throw new IllegalArgumentException("LOCAL_UPLOAD must not carry integrity expectations");
        }
        expectedSha256 = trimToNull(expectedSha256);
        expectedSignature = trimToNull(expectedSignature);
    }

    /** 本地上传来源：无任何完整性期望（当前唯一接入的来源）。 */
    public static PluginPackageOrigin localUpload() {
        return new PluginPackageOrigin(PluginPackageSource.LOCAL_UPLOAD, null, null, null);
    }

    /**
     * 受信目录来源：由受信插件目录清单提供期望大小 / SHA-256 / 签名（任一可为 {@code null}）。保留建模，供后续受信目录
     * 获取流程在落盘前据此做完整性校验；本方法只构造来源描述、不发起任何网络访问。
     */
    public static PluginPackageOrigin forTrustedCatalog(Long expectedSizeBytes, String expectedSha256,
                                                        String expectedSignature) {
        return new PluginPackageOrigin(PluginPackageSource.MARKET_CATALOG,
                expectedSizeBytes, expectedSha256, expectedSignature);
    }

    /** 是否带至少一项完整性期望（本地上传恒 {@code false}）。 */
    public boolean hasIntegrityExpectations() {
        return expectedSizeBytes != null || expectedSha256 != null || expectedSignature != null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
