package top.sywyar.pixivdownload.plugin.catalog;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 受信插件 catalog 配置（{@code plugin-catalog.*}）。<b>默认关闭、{@code manifest-url} 为空</b>——绝不内置任何会联网的真实
 * 地址；catalog 的下载地址只能来自服务端这份配置，<b>绝不</b>来自请求参数。{@code manifest-url} 必须是 https。
 */
@Data
@Component
@ConfigurationProperties(prefix = "plugin-catalog")
public class PluginCatalogProperties {

    /** 单个清单的默认字节上限（1MB）。 */
    public static final long DEFAULT_MAX_MANIFEST_BYTES = 1L * 1024 * 1024;

    /** 单个插件包下载的默认绝对字节上限（100MB）。 */
    public static final long DEFAULT_MAX_PACKAGE_BYTES = 100L * 1024 * 1024;

    /** 是否启用受信 catalog（默认关闭：不启用即返回空 catalog、不联网）。 */
    private boolean enabled = false;

    /** 受信 catalog 清单地址（必须 https；默认空——不配置即等同未启用，绝不内置真实联网地址）。 */
    private String manifestUrl = "";

    /** 清单拉取的最大字节数（防超大响应；默认 1MB）。 */
    private long maxManifestBytes = DEFAULT_MAX_MANIFEST_BYTES;

    /** 单个插件包下载的绝对最大字节数（catalog 声明的 {@code expectedSize} 超过它即拒绝；默认 100MB）。 */
    private long maxPackageBytes = DEFAULT_MAX_PACKAGE_BYTES;

    /** 连接超时（毫秒）。 */
    private int connectTimeoutMs = 15_000;

    /** 读取超时（毫秒）。 */
    private int readTimeoutMs = 60_000;

    /** {@code manifest-url} 是否已配置（非空）。 */
    public boolean hasManifestUrl() {
        return manifestUrl != null && !manifestUrl.isBlank();
    }
}
