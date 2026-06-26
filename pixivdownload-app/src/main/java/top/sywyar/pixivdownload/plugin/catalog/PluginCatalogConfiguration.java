package top.sywyar.pixivdownload.plugin.catalog;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 受信 catalog 框架的 POJO Bean 装配（核心基础设施，<b>非</b> {@code @PluginManagedBean}）。SSRF 安全 HTTP 客户端与
 * 下载器需要按配置 / 安全策略构造，故经显式 {@code @Bean} 装配，生产策略固定为：<b>仅 https + 拒绝非公网地址（SSRF）+
 * 禁重定向 + 不走代理</b>。临时下载目录用系统临时目录（{@code null}）。其余 {@code @Service} / {@code @Component} 由
 * 组件扫描装配。
 */
@Configuration
public class PluginCatalogConfiguration {

    @Bean
    public PluginCatalogHttpClient pluginCatalogHttpClient(PluginCatalogProperties properties) {
        // 生产策略：仅 https、拒绝非公网地址（严格 SSRF）；超时来自配置。
        return new PluginCatalogHttpClient(true, false,
                properties.getConnectTimeoutMs(), properties.getReadTimeoutMs());
    }

    @Bean
    public PluginPackageDownloader pluginPackageDownloader(PluginCatalogHttpClient pluginCatalogHttpClient,
                                                           PluginCatalogProperties properties) {
        return new PluginPackageDownloader(pluginCatalogHttpClient, properties.getMaxPackageBytes(), null);
    }
}
