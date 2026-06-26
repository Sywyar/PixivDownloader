package top.sywyar.pixivdownload.plugin.catalog;

import org.springframework.http.HttpStatus;

/**
 * 受信 catalog 操作失败的稳定机器码：每个常量绑定固定 HTTP 状态与一条 i18n 文案 key。{@link #name()} 即对外返回的稳定
 * {@code code}（与界面语言无关，供管理入口按机器语义分支）；i18n key 仅用于解析人类可读 message。
 *
 * <p>注意：安装阶段「下载到的包本身不合规」（大小 / 哈希 / 签名不符、不兼容、Zip Slip、布局非法等）<b>不</b>在此枚举——
 * 那些由既有 {@code ExternalPluginInstaller} 裁定、复用 {@code PluginInstallOutcome} 与 {@code PluginInstallResponse}
 * （例如完整性不符 → {@code REJECTED_INTEGRITY}）。本枚举只覆盖「拿到包之前」的 catalog / 下载层失败。
 */
public enum PluginCatalogErrorCode {

    /** catalog 未启用（{@code plugin-catalog.enabled=false} 或未配置 {@code manifest-url}）。 */
    CATALOG_DISABLED(HttpStatus.CONFLICT, "plugin.catalog.error.disabled"),

    /** catalog 已启用但清单拉取 / 解析失败（网络错误、坏 JSON、不安全的清单地址等）。 */
    CATALOG_UNAVAILABLE(HttpStatus.BAD_GATEWAY, "plugin.catalog.error.unavailable"),

    /** 引用了不存在的仓库 id（不在服务端配置的仓库列表中）。 */
    UNKNOWN_REPOSITORY(HttpStatus.NOT_FOUND, "plugin.catalog.error.unknown-repository"),

    /** 目标仓库存在但已被禁用（不参与解析 / 拉取）。 */
    REPOSITORY_DISABLED(HttpStatus.CONFLICT, "plugin.catalog.error.repository-disabled"),

    /** 仓库代理策略不受支持（未知策略，或当前运行时尚未接线的 {@code proxy-trusted}）。 */
    PROXY_POLICY_UNSUPPORTED(HttpStatus.UNPROCESSABLE_ENTITY, "plugin.catalog.error.proxy-policy-unsupported"),

    /** catalog 中没有该插件 id。 */
    UNKNOWN_PLUGIN(HttpStatus.NOT_FOUND, "plugin.catalog.error.unknown-plugin"),

    /** 该插件 id 在 catalog 中没有指定版本。 */
    VERSION_NOT_FOUND(HttpStatus.NOT_FOUND, "plugin.catalog.error.version-not-found"),

    /** 包下载地址非法（非 https / 非法 URL / 受禁 scheme 如 file/http/jar/ftp）。 */
    INSECURE_URL(HttpStatus.UNPROCESSABLE_ENTITY, "plugin.catalog.error.insecure-url"),

    /** 包下载地址解析到被禁止的地址（loopback / 私网 / link-local / 组播 / 未指定等，SSRF 防护）。 */
    BLOCKED_ADDRESS(HttpStatus.UNPROCESSABLE_ENTITY, "plugin.catalog.error.blocked-address"),

    /** 下载体积超过 {@code expectedSize} 或绝对上限。 */
    DOWNLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "plugin.catalog.error.download-too-large"),

    /** 下载失败（连接 / 读取错误、非 200 响应、发生重定向等）。 */
    DOWNLOAD_FAILED(HttpStatus.BAD_GATEWAY, "plugin.catalog.error.download-failed"),

    /** catalog 包元数据不完整（缺 packageUrl / expectedSize / sha256，或 URL 不以 .jar/.zip 结尾），无法安全下载。 */
    INVALID_PACKAGE_METADATA(HttpStatus.UNPROCESSABLE_ENTITY, "plugin.catalog.error.invalid-package");

    private final HttpStatus status;
    private final String messageKey;

    PluginCatalogErrorCode(HttpStatus status, String messageKey) {
        this.status = status;
        this.messageKey = messageKey;
    }

    /** 该失败类别对应的 HTTP 状态。 */
    public HttpStatus status() {
        return status;
    }

    /** 该失败类别的本地化文案 key（在后端 {@code i18n/messages*.properties} 中解析）。 */
    public String messageKey() {
        return messageKey;
    }
}
