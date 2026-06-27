package top.sywyar.pixivdownload.plugin.catalog.repository;

/**
 * 一个已配置的插件仓库（市场来源）的<b>不可变领域模型</b>。仓库列表由 {@link PluginRepositoryRegistry} 从服务端配置
 * （{@code plugin-catalog.*}）+ 内嵌官方默认仓库合成；安装 / 拉取只能引用仓库列表里按 {@code repositoryId} 解析出的
 * 仓库，<b>绝不</b>接受请求参数里的任意仓库 URL。
 *
 * <p>纯 JDK record，<b>不入 {@code plugin-api}</b>。
 *
 * @param repositoryId    仓库稳定标识（全局唯一；{@code official} 为内嵌官方仓库保留）
 * @param displayNameKey  展示名 i18n key（后端只透传、由前端在对应 namespace 解析）
 * @param manifestUrl     该仓库的清单地址（必须 https；只来自服务端配置 / 内嵌常量，绝不来自请求）
 * @param enabled         是否启用（禁用的仓库仍保留在列表里用于状态展示，但不参与默认解析 / 拉取）
 * @param official        是否为官方来源仓库
 * @param builtIn         是否为程序内嵌（非用户配置；内嵌仓库不可被自定义配置以同 id 覆盖）
 * @param proxyPolicy     解析后的代理策略（配置串无法识别时为 {@code null} = 不支持，由拉取层稳定报错）
 * @param rawProxyPolicy  原始代理策略串（诊断用，保留用户原配置）
 * @param connectTimeoutMs 连接超时（毫秒）
 * @param readTimeoutMs    读取超时（毫秒）
 * @param maxManifestBytes 清单拉取字节上限
 * @param maxPackageBytes  单包下载绝对字节上限
 */
public record PluginRepository(
        String repositoryId,
        String displayNameKey,
        String manifestUrl,
        boolean enabled,
        boolean official,
        boolean builtIn,
        RepositoryProxyPolicy proxyPolicy,
        String rawProxyPolicy,
        long connectTimeoutMs,
        long readTimeoutMs,
        long maxManifestBytes,
        long maxPackageBytes) {

    /** 内嵌官方默认仓库的稳定 id。 */
    public static final String OFFICIAL_ID = "official";

    /** 内嵌官方默认仓库的清单地址：插件分发仓库 code 区的 manifest.json，经 raw.githubusercontent 200 直出（无重定向）。 */
    public static final String OFFICIAL_MANIFEST_URL =
            "https://raw.githubusercontent.com/Sywyar/PixivDownloader-plugins/master/manifest.json";

    /** 官方仓库展示名 i18n key。 */
    public static final String OFFICIAL_DISPLAY_NAME_KEY = "plugin.market.repository.official.name";

    /** 由旧版单一 {@code manifest-url} 折出的兼容仓库 id（见 {@link PluginRepositoryRegistry}）。 */
    public static final String LEGACY_CONFIGURED_ID = "configured";

    /** 兼容仓库展示名 i18n key。 */
    public static final String LEGACY_DISPLAY_NAME_KEY = "plugin.market.repository.configured.name";

    /**
     * 构造内嵌官方默认仓库（{@code builtIn=true}、{@code official=true}、{@link RepositoryProxyPolicy#PROXY_TRUSTED}）。
     * 官方仓库经核心出站代理拉取（GitHub 在受限网络下需代理），并按内置主机白名单跟随 GitHub release 资产的一跳重定向；
     * 完整性仍由安装器的 sha256/size 逐字节兜底。其 {@code enabled} 由 {@code plugin-catalog.official-repository-enabled}
     * （默认 {@code true}）决定，可被禁用。
     */
    public static PluginRepository official(boolean enabled,
                                            long connectTimeoutMs, long readTimeoutMs,
                                            long maxManifestBytes, long maxPackageBytes) {
        return new PluginRepository(OFFICIAL_ID, OFFICIAL_DISPLAY_NAME_KEY, OFFICIAL_MANIFEST_URL,
                enabled, true, true, RepositoryProxyPolicy.PROXY_TRUSTED, RepositoryProxyPolicy.PROXY_TRUSTED.configId(),
                connectTimeoutMs, readTimeoutMs, maxManifestBytes, maxPackageBytes);
    }

    /**
     * 该仓库的代理策略是否被当前运行时支持（{@link RepositoryProxyPolicy#DIRECT_STRICT} 与
     * {@link RepositoryProxyPolicy#PROXY_TRUSTED} 均已接线；未知策略为 {@code null} → 不支持）。
     */
    public boolean isProxyPolicySupported() {
        return proxyPolicy == RepositoryProxyPolicy.DIRECT_STRICT
                || proxyPolicy == RepositoryProxyPolicy.PROXY_TRUSTED;
    }
}
