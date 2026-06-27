package top.sywyar.pixivdownload.plugin.catalog;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.sywyar.pixivdownload.plugin.catalog.repository.PluginCatalogClientProvider;

/**
 * 受信 catalog 框架的 POJO Bean 装配（核心基础设施，<b>非</b> {@code @PluginManagedBean}）。
 *
 * <p>下载器<b>不再持有固定的全局严格 HTTP 客户端</b>：包下载与清单读取一样，按下载所属仓库的代理策略 / 超时即时装配
 * （{@link PluginCatalogClientProvider#clientFor}），由此 {@code direct-strict} 与 {@code proxy-trusted} 仓库各走对应网络
 * 档位，<b>不存在「全局单例严格客户端下载所有包」的旁路</b>。临时下载目录用系统临时目录（{@code null}）。SSRF 安全客户端本身
 * 由 {@code DefaultPluginCatalogClientProvider} 按仓库构造；其余 {@code @Service} / {@code @Component} 由组件扫描装配。
 */
@Configuration
public class PluginCatalogConfiguration {

    @Bean
    PluginPackageDownloader pluginPackageDownloader(PluginCatalogClientProvider clientProvider) {
        // 按目标仓库装配客户端（与 PluginCatalogService 拉清单同源 provider）；临时目录用系统临时目录。
        return new PluginPackageDownloader(clientProvider, null);
    }
}
