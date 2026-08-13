package top.sywyar.pixivdownload.push.http;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.sywyar.pixivdownload.plugin.ConditionalOnPluginEnabled;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpClientFactory;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpClientProfile;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpRoute;
import top.sywyar.pixivdownload.plugin.runtime.http.ManagedPluginRestTemplate;
import top.sywyar.pixivdownload.plugin.runtime.http.PluginRestTemplateAdapter;
import top.sywyar.pixivdownload.push.PushPlugin;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class PushHttpClientConfiguration {

    @Bean(name = "pushRestTemplate", destroyMethod = "close")
    @ConditionalOnPluginEnabled(PushPlugin.ID)
    public ManagedPluginRestTemplate pushRestTemplate(OutboundHttpClientFactory factory) {
        return PluginRestTemplateAdapter.open(factory, OutboundHttpClientProfile.credentialed(
                Duration.ofSeconds(10),
                Duration.ofSeconds(15),
                OutboundHttpRoute.direct()));
    }

    @Bean(name = "pushProxyRestTemplate", destroyMethod = "close")
    @ConditionalOnPluginEnabled(PushPlugin.ID)
    public ManagedPluginRestTemplate pushProxyRestTemplate(OutboundHttpClientFactory factory) {
        return PluginRestTemplateAdapter.open(factory, OutboundHttpClientProfile.credentialed(
                Duration.ofSeconds(10),
                Duration.ofSeconds(15),
                OutboundHttpRoute.configuredProxy()));
    }
}
