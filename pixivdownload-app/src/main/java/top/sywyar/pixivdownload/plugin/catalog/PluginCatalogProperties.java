package top.sywyar.pixivdownload.plugin.catalog;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 受信插件 catalog / 插件市场配置（{@code plugin-catalog.*}）。两级开关，二者正交：
 * <ul>
 *   <li><b>主开关</b> {@code enabled}（默认 {@code false}）：整个受信 catalog / 市场能力是否开启。<b>默认关闭，全新
 *       安装不联网</b>——管理员显式开启前，绝不访问任何仓库（含内嵌官方仓库）。</li>
 *   <li><b>官方仓库开关</b> {@code official-repository-enabled}（默认 {@code true}）：内嵌官方默认仓库是否在列表中启用。
 *       官方仓库地址内嵌于程序（见 {@code PluginRepository}），<b>可被禁用</b>；这只决定它启用与否，不影响主开关。</li>
 * </ul>
 *
 * <p>仓库的下载 / 清单地址只能来自这份服务端配置或内嵌官方常量，<b>绝不</b>来自请求参数。{@code manifest-url} /
 * {@code repositories[*].manifest-url} 必须是 https（由 SSRF 安全客户端在拉取时强制）。旧版单一 {@code manifest-url}
 * 保留为兼容入口：非空时由 {@code PluginRepositoryRegistry} 折成一个启用的直连兼容仓库。
 */
@Data
@Component
@ConfigurationProperties(prefix = "plugin-catalog")
public class PluginCatalogProperties {

    /** 单个清单的默认字节上限（1MB）。 */
    public static final long DEFAULT_MAX_MANIFEST_BYTES = 1L * 1024 * 1024;

    /** 单个插件包下载的默认绝对字节上限（100MB）。 */
    public static final long DEFAULT_MAX_PACKAGE_BYTES = 100L * 1024 * 1024;

    /** 是否启用受信 catalog / 插件市场主开关（默认关闭：不启用即不联网、不访问任何仓库）。 */
    private boolean enabled = false;

    /** 内嵌官方默认仓库是否启用（默认启用；官方仓库地址内嵌于程序，此项可单独禁用它）。 */
    private boolean officialRepositoryEnabled = true;

    /** 旧版单一受信 catalog 清单地址（兼容入口；必须 https；非空时折成一个启用的直连兼容仓库 {@code configured}）。 */
    private String manifestUrl = "";

    /** 自定义仓库列表（可新增 / 禁用；id 不得为空 / 保留字 / 重复，{@code manifest-url} 不得为空，否则启动期 fail-fast）。 */
    private List<RepositoryConfig> repositories = new ArrayList<>();

    /** 清单拉取的全局默认最大字节数（防超大响应；默认 1MB；可被仓库级覆盖）。 */
    private long maxManifestBytes = DEFAULT_MAX_MANIFEST_BYTES;

    /** 单个插件包下载的全局默认绝对最大字节数（默认 100MB；可被仓库级覆盖）。 */
    private long maxPackageBytes = DEFAULT_MAX_PACKAGE_BYTES;

    /** 连接超时（毫秒；全局默认，可被仓库级覆盖）。 */
    private int connectTimeoutMs = 15_000;

    /** 读取超时（毫秒；全局默认，可被仓库级覆盖）。 */
    private int readTimeoutMs = 60_000;

    /** {@code manifest-url} 是否已配置（非空）。 */
    public boolean hasManifestUrl() {
        return manifestUrl != null && !manifestUrl.isBlank();
    }

    /**
     * 单个自定义仓库的配置（{@code plugin-catalog.repositories[*]}）。可绑定的可变 POJO；由
     * {@code PluginRepositoryRegistry} 转换为不可变的 {@code PluginRepository} 领域模型。超时 / 大小上限项为 0 时回落到
     * 全局默认；{@code proxy-policy} 缺省为 {@code direct-strict}。
     */
    @Data
    public static class RepositoryConfig {

        /** 仓库稳定 id（必填、全局唯一；不得为保留字 {@code official} / {@code configured}）。 */
        private String id;

        /** 展示名 i18n key（可空；缺省按 {@code plugin.market.repository.<id>.name} 推导）。 */
        private String displayNameKey;

        /** 仓库清单地址（必填、必须 https）。 */
        private String manifestUrl;

        /** 是否启用（默认启用；禁用项仍在列表中用于状态展示，但不参与解析 / 拉取）。 */
        private boolean enabled = true;

        /** 代理策略（{@code direct-strict} 默认 / {@code proxy-trusted}；后者当前运行时未接线，拉取即稳定报「不支持」）。 */
        private String proxyPolicy = "direct-strict";

        /** 连接超时覆盖（毫秒，&le;0 用全局默认）。 */
        private long connectTimeoutMs;

        /** 读取超时覆盖（毫秒，&le;0 用全局默认）。 */
        private long readTimeoutMs;

        /** 清单字节上限覆盖（&le;0 用全局默认）。 */
        private long maxManifestBytes;

        /** 单包字节上限覆盖（&le;0 用全局默认）。 */
        private long maxPackageBytes;
    }
}
