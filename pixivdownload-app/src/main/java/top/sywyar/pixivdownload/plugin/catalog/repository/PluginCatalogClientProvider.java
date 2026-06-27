package top.sywyar.pixivdownload.plugin.catalog.repository;

import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogHttpClient;

/**
 * 为某个 {@link PluginRepository} 提供其代理策略对应的 SSRF 安全 HTTP 客户端。把「仓库 → 客户端」的装配抽出为接口，使
 * catalog 读取服务不直接持有固定客户端：生产实现按仓库代理策略与超时构造客户端，测试可注入对接 loopback 桩的放开客户端。
 */
public interface PluginCatalogClientProvider {

    /**
     * 为给定仓库返回一个 HTTP 客户端。{@code direct-strict} 与 {@code proxy-trusted} 均已接线；仅当代理策略串
     * <b>无法识别</b>（未知）时应抛出稳定的 {@code PROXY_POLICY_UNSUPPORTED}，<b>绝不</b>静默回落到直连。
     */
    PluginCatalogHttpClient clientFor(PluginRepository repository);
}
