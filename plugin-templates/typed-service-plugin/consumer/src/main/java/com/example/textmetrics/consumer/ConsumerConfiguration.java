package com.example.textmetrics.consumer;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.sywyar.pixivdownload.plugin.api.service.PluginServices;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.i18n.LocaleBundlePolicy;
import top.sywyar.pixivdownload.i18n.ResourceBundleMessageResolver;

/** 只在构造时保存访问器，业务请求到达后再解析服务。 */
@Configuration(proxyBeanMethods = false)
public class ConsumerConfiguration {
    @Bean
    pixivdownload.generated.remote.TextMetricsRemoteAdapter remoteMetrics(
            top.sywyar.pixivdownload.plugin.api.web.RemoteOperations operations) {
        return new pixivdownload.generated.remote.TextMetricsRemoteAdapter(operations);
    }

    @Bean
    MetricsController metricsController(PluginServices services, MessageResolver messages, LocaleBundlePolicy policy) {
        return new MetricsController(services, ResourceBundleMessageResolver.of(messages,
                ConsumerPlugin.class.getClassLoader(), policy, "i18n.web.text-metrics-consumer"));
    }
}
