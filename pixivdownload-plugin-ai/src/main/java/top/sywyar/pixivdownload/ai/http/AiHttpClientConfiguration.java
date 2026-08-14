package top.sywyar.pixivdownload.ai.http;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.sywyar.pixivdownload.ai.AiPlugin;
import top.sywyar.pixivdownload.plugin.ConditionalOnPluginEnabled;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpClientFactory;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpClientProfile;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpRoute;
import top.sywyar.pixivdownload.plugin.runtime.http.ManagedPluginRestTemplate;
import top.sywyar.pixivdownload.plugin.runtime.http.PluginRestTemplateAdapter;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class AiHttpClientConfiguration {

    @Bean(name = "aiRestTemplate", destroyMethod = "close")
    @ConditionalOnPluginEnabled(AiPlugin.ID)
    public ManagedPluginRestTemplate aiRestTemplate(OutboundHttpClientFactory factory) {
        return PluginRestTemplateAdapter.open(factory, OutboundHttpClientProfile.credentialed(
                Duration.ofSeconds(30),
                Duration.ofSeconds(120),
                OutboundHttpRoute.direct()));
    }

    @Bean(name = "aiProxyRestTemplate", destroyMethod = "close")
    @ConditionalOnPluginEnabled(AiPlugin.ID)
    public ManagedPluginRestTemplate aiProxyRestTemplate(OutboundHttpClientFactory factory) {
        return PluginRestTemplateAdapter.open(factory, OutboundHttpClientProfile.credentialed(
                Duration.ofSeconds(30),
                Duration.ofSeconds(120),
                OutboundHttpRoute.configuredProxy()));
    }
}
