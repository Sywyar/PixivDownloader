package top.sywyar.pixivdownload.plugin.market;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.sywyar.pixivdownload.i18n.AppLocaleResolver;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.plugin.ConditionalOnPluginEnabled;
import top.sywyar.pixivdownload.plugin.install.PluginInstallResponseMapper;
import top.sywyar.pixivdownload.plugin.management.PluginStatusService;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogAcquisitionService;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogService;
import top.sywyar.pixivdownload.plugin.catalog.repository.PluginRepositoryRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;

/**
 * 插件市场插件的 Bean 装配收敛点：descriptor 始终注册（{@code PluginRegistry.allPlugins()} / schema 合并 / disabledPlugins
 * 都依赖全部 descriptor 在场），市场业务 Bean（{@link PluginMarketService} / {@link PluginMarketController}）随
 * {@code plugins.plugin-market.enabled} 装配 / 缺席。
 *
 * <p><b>禁用语义</b>：{@code plugins.plugin-market.enabled=false} 时下面两个 {@code @ConditionalOnPluginEnabled} Bean 缺席——
 * {@code /api/plugin-market/**} 因「未声明即 404」不可达、市场页面 / 静态资源 / 导航入口随插件退出活动快照而撤销；重新启用后恢复。
 * 市场只消费 catalog 引擎（仓库注册中心 / 清单读取 / 受信安装编排，住 {@code plugin.catalog} 领域包、核心基础设施），
 * 不另造仓库扫描 / 下载 / 安装实现。
 */
@Configuration
public class PluginMarketPluginConfiguration {

    @Bean
    public PluginMarketPlugin pluginMarketPlugin() {
        return new PluginMarketPlugin();
    }

    @Bean
    @ConditionalOnPluginEnabled(PluginMarketPlugin.ID)
    public PluginMarketService pluginMarketService(PluginRepositoryRegistry repositoryRegistry,
                                                   PluginCatalogService catalogService,
                                                   PluginCatalogAcquisitionService acquisitionService,
                                                   PluginStatusService pluginStatusService) {
        return new PluginMarketService(repositoryRegistry, catalogService, acquisitionService, pluginStatusService);
    }

    @Bean
    @ConditionalOnPluginEnabled(PluginMarketPlugin.ID)
    public PluginMarketController pluginMarketController(PluginMarketService pluginMarketService,
                                                         PluginInstallResponseMapper installResponseMapper,
                                                         AppMessages messages, AppLocaleResolver localeResolver) {
        return new PluginMarketController(pluginMarketService, installResponseMapper, messages, localeResolver);
    }
}
