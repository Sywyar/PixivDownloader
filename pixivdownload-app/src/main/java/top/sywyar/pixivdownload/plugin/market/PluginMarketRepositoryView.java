package top.sywyar.pixivdownload.plugin.market;

import top.sywyar.pixivdownload.plugin.catalog.repository.PluginRepository;

/**
 * 插件市场仓库的对外只读投影（{@code GET /api/plugin-market/repositories} 用）。只暴露管理员维护 / 浏览仓库列表所需的
 * 受控只读信息：稳定 id、展示名 i18n key（前端解析）、清单地址、启用 / 官方 / 内嵌 / 兼容标记、代理策略与是否受支持、
 * 超时与大小上限。<b>安装请求只能引用这些 {@code repositoryId}，绝不接受任意 URL</b>。
 *
 * @param repositoryId         仓库稳定 id
 * @param displayNameKey       展示名 i18n key（后端透传、前端解析）
 * @param manifestUrl          清单地址（来自服务端配置 / 内嵌常量；展示 / 诊断用）
 * @param enabled              是否启用
 * @param official             是否官方来源仓库
 * @param builtIn              是否程序内嵌（非用户配置）
 * @param legacy               是否由旧版单一 {@code manifest-url} 折出的兼容仓库（{@code configured}）
 * @param defaultRepository    是否为默认仓库（旧版无 repositoryId 入口解析到的仓库）
 * @param proxyPolicy          代理策略原始串（{@code direct-strict} / {@code proxy-trusted} / {@code custom}）
 * @param proxyPolicySupported 当前运行时是否支持该代理策略（三个内置策略均支持；仅无法识别的未知策略串 → false，拉取时稳定报错）
 * @param connectTimeoutMs     连接超时（毫秒）
 * @param readTimeoutMs        读取超时（毫秒）
 * @param maxManifestBytes     清单拉取字节上限
 * @param maxPackageBytes      单包下载绝对字节上限
 */
public record PluginMarketRepositoryView(
        String repositoryId,
        String displayNameKey,
        String manifestUrl,
        boolean enabled,
        boolean official,
        boolean builtIn,
        boolean legacy,
        boolean defaultRepository,
        String proxyPolicy,
        boolean proxyPolicySupported,
        long connectTimeoutMs,
        long readTimeoutMs,
        long maxManifestBytes,
        long maxPackageBytes) {

    static PluginMarketRepositoryView from(PluginRepository repository, boolean defaultRepository) {
        return new PluginMarketRepositoryView(
                repository.repositoryId(),
                repository.displayNameKey(),
                repository.manifestUrl(),
                repository.enabled(),
                repository.official(),
                repository.builtIn(),
                PluginRepository.LEGACY_CONFIGURED_ID.equals(repository.repositoryId()),
                defaultRepository,
                repository.rawProxyPolicy(),
                repository.isProxyPolicySupported(),
                repository.connectTimeoutMs(),
                repository.readTimeoutMs(),
                repository.maxManifestBytes(),
                repository.maxPackageBytes());
    }
}
