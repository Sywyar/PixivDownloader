package top.sywyar.pixivdownload.tts.http;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.sywyar.pixivdownload.plugin.ConditionalOnPluginEnabled;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpClientFactory;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpClientProfile;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpRoute;
import top.sywyar.pixivdownload.plugin.runtime.http.ManagedPluginRestTemplate;
import top.sywyar.pixivdownload.plugin.runtime.http.PluginRestTemplateAdapter;
import top.sywyar.pixivdownload.tts.TtsPlugin;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class TtsHttpClientConfiguration {

    @Bean(name = "ttsMetadataRestTemplate", destroyMethod = "close")
    @ConditionalOnPluginEnabled(TtsPlugin.ID)
    public ManagedPluginRestTemplate ttsMetadataRestTemplate(OutboundHttpClientFactory factory) {
        return PluginRestTemplateAdapter.open(factory, OutboundHttpClientProfile.standard(
                Duration.ofSeconds(15),
                Duration.ofSeconds(30),
                OutboundHttpRoute.inherit()));
    }

    @Bean(name = "narrationTtsRestTemplate", destroyMethod = "close")
    @ConditionalOnPluginEnabled(TtsPlugin.ID)
    public ManagedPluginRestTemplate narrationTtsRestTemplate(OutboundHttpClientFactory factory) {
        return PluginRestTemplateAdapter.open(factory, OutboundHttpClientProfile.standard(
                Duration.ofSeconds(30),
                Duration.ofSeconds(300),
                OutboundHttpRoute.direct()));
    }

    @Bean(name = "narrationTtsProxyRestTemplate", destroyMethod = "close")
    @ConditionalOnPluginEnabled(TtsPlugin.ID)
    public ManagedPluginRestTemplate narrationTtsProxyRestTemplate(OutboundHttpClientFactory factory) {
        return PluginRestTemplateAdapter.open(factory, OutboundHttpClientProfile.standard(
                Duration.ofSeconds(30),
                Duration.ofSeconds(300),
                OutboundHttpRoute.configuredProxy()));
    }

    @Bean(name = "narrationTtsProbeRestTemplate", destroyMethod = "close")
    @ConditionalOnPluginEnabled(TtsPlugin.ID)
    public ManagedPluginRestTemplate narrationTtsProbeRestTemplate(OutboundHttpClientFactory factory) {
        return PluginRestTemplateAdapter.open(factory, OutboundHttpClientProfile.standard(
                Duration.ofSeconds(2),
                Duration.ofSeconds(4),
                OutboundHttpRoute.direct()));
    }

    @Bean(name = "narrationTtsProbeProxyRestTemplate", destroyMethod = "close")
    @ConditionalOnPluginEnabled(TtsPlugin.ID)
    public ManagedPluginRestTemplate narrationTtsProbeProxyRestTemplate(OutboundHttpClientFactory factory) {
        return PluginRestTemplateAdapter.open(factory, OutboundHttpClientProfile.standard(
                Duration.ofSeconds(2),
                Duration.ofSeconds(4),
                OutboundHttpRoute.configuredProxy()));
    }
}
