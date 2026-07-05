package top.sywyar.pixivdownload.plugin.runtime.context;

import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * 每个外置插件子 {@code ApplicationContext} 共享的 Spring 基础设施。
 */
@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement(proxyTargetClass = true)
class PluginContextInfrastructureConfiguration {
}
