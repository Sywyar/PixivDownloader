package top.sywyar.pixivdownload.plugin.catalog.repository;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogErrorCode;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogException;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogHttpClient;

/**
 * 生产环境的 {@link PluginCatalogClientProvider}：只为 {@link RepositoryProxyPolicy#DIRECT_STRICT} 仓库装配客户端——
 * <b>仅 https + 拒非公网地址（严格 SSRF）+ 禁重定向 + 不走代理</b>，按该仓库的连接 / 读取超时构造。
 *
 * <p>{@link RepositoryProxyPolicy#PROXY_TRUSTED}（经核心出站代理拉取）的客户端装配属后续插件市场后端流程，当前运行时
 * 尚未接线；对该策略或无法识别的策略的仓库，<b>抛出稳定的</b> {@link PluginCatalogErrorCode#PROXY_POLICY_UNSUPPORTED}
 * 而非静默降级为直连，避免把「显式信任代理」的意图悄悄当成直连。
 */
@Component
public class StrictPluginCatalogClientProvider implements PluginCatalogClientProvider {

    @Override
    public PluginCatalogHttpClient clientFor(PluginRepository repository) {
        if (repository.proxyPolicy() != RepositoryProxyPolicy.DIRECT_STRICT) {
            throw new PluginCatalogException(PluginCatalogErrorCode.PROXY_POLICY_UNSUPPORTED,
                    "unsupported proxy policy '" + repository.rawProxyPolicy() + "' for repository "
                            + repository.repositoryId());
        }
        // 生产策略：仅 https、拒非公网地址（严格 SSRF）；超时取该仓库的配置（缺省时已由注册中心回落到全局默认）。
        return new PluginCatalogHttpClient(true, false,
                (int) repository.connectTimeoutMs(), (int) repository.readTimeoutMs());
    }
}
