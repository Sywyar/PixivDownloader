package top.sywyar.pixivdownload.plugin.runtime.install.model;

import top.sywyar.pixivdownload.plugin.signature.SignatureMetadata;
import top.sywyar.pixivdownload.plugin.signature.VerificationPolicy;

import java.util.Objects;

/**
 * 一个待安装插件包的来源描述：来源类别 + 该来源声明的可选完整性期望（期望大小 / SHA-256 / 结构化签名）。安装器据来源决定
 * 是否在落盘前做统一供应链验签。
 *
 * <h2>完整性期望只来自受信来源</h2>
 * 完整性期望（{@link #expectedSizeBytes()} / {@link #expectedSha256()} / {@link #signature()}）一律来自
 * <b>受信插件目录元数据</b>（{@link PluginPackageSource#MARKET_CATALOG}），<b>绝不</b>来自用户输入。本地上传
 * （{@link PluginPackageSource#LOCAL_UPLOAD}）没有可信清单背书、不携带任何期望——其安全性由安装器的结构校验、
 * 资源规模上限与 Zip Slip 防护承担。本类只建模来源与期望，<b>不</b>发起任何下载 / 网络访问。
 *
 * @param source            来源类别
 * @param expectedSizeBytes 受信清单声明的期望文件字节数（无则 {@code null}）
 * @param expectedSha256    受信清单声明的期望 SHA-256（十六进制，无则 {@code null}）
 * @param repositoryId      来源仓库 id（本地上传为 {@code null}）
 * @param officialRepository 是否官方仓库来源
 * @param signature          受信清单声明的结构化签名元数据
 */
public record PluginPackageOrigin(
        PluginPackageSource source,
        String repositoryId,
        boolean officialRepository,
        Long expectedSizeBytes,
        String expectedSha256,
        SignatureMetadata signature) {

    public PluginPackageOrigin {
        Objects.requireNonNull(source, "source");
        if (source == PluginPackageSource.LOCAL_UPLOAD
                && (expectedSizeBytes != null || hasText(expectedSha256) || signature != null
                || hasText(repositoryId) || officialRepository)) {
            // 本地上传无可信清单背书，不得携带完整性期望（防止把不可信的「期望」当成已校验）。
            throw new IllegalArgumentException("LOCAL_UPLOAD must not carry integrity expectations");
        }
        repositoryId = trimToNull(repositoryId);
        expectedSha256 = trimToNull(expectedSha256);
    }

    /** 本地上传来源：无任何完整性期望（当前唯一接入的来源）。 */
    public static PluginPackageOrigin localUpload() {
        return new PluginPackageOrigin(PluginPackageSource.LOCAL_UPLOAD, null, false, null, null, null);
    }

    /**
     * 受信目录来源：由受信插件目录清单提供期望大小 / SHA-256 / 结构化签名（任一可为 {@code null}）。本方法只构造来源描述、
     * 不发起任何网络访问。
     */
    public static PluginPackageOrigin forTrustedCatalog(String repositoryId, boolean officialRepository,
                                                        Long expectedSizeBytes, String expectedSha256,
                                                        SignatureMetadata signature) {
        return new PluginPackageOrigin(PluginPackageSource.MARKET_CATALOG, repositoryId, officialRepository,
                expectedSizeBytes, expectedSha256, signature);
    }

    /** 是否带至少一项完整性期望（本地上传恒 {@code false}）。 */
    public boolean hasIntegrityExpectations() {
        return expectedSizeBytes != null || expectedSha256 != null || signature != null;
    }

    public VerificationPolicy verificationPolicy() {
        if (source == PluginPackageSource.LOCAL_UPLOAD) {
            return VerificationPolicy.localUnsignedAllowed();
        }
        return officialRepository ? VerificationPolicy.officialRepository() : VerificationPolicy.customRepository();
    }

    public VerificationPolicy installedVerificationPolicy() {
        if (source == PluginPackageSource.LOCAL_UPLOAD) {
            return VerificationPolicy.localUnsignedAllowed();
        }
        return officialRepository ? VerificationPolicy.installedOfficial() : VerificationPolicy.installedCustom();
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
