package top.sywyar.pixivdownload.douyin.http;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.sywyar.pixivdownload.config.OutboundProxyEndpoint;
import top.sywyar.pixivdownload.config.OutboundProxyOverride;
import top.sywyar.pixivdownload.douyin.settings.DouyinPluginSettingsService;
import top.sywyar.pixivdownload.douyin.settings.DouyinRuntimeSettings;
import top.sywyar.pixivdownload.plugin.ConditionalOnPluginEnabled;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpClientFactory;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpClientProfile;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpCookiePolicy;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpRedirectPolicy;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpRoute;
import top.sywyar.pixivdownload.plugin.runtime.http.ManagedPluginRestTemplate;
import top.sywyar.pixivdownload.plugin.runtime.http.PluginRestTemplateAdapter;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

@Configuration(proxyBeanMethods = false)
public class DouyinHttpClientConfiguration {

    private static final Duration MAIN_CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration MAIN_READ_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration REDIRECT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REDIRECT_READ_TIMEOUT = Duration.ofSeconds(10);

    @Bean(name = "douyinRestTemplate", destroyMethod = "close")
    @ConditionalOnPluginEnabled("douyin")
    public ManagedPluginRestTemplate douyinRestTemplate(OutboundHttpClientFactory factory) {
        return openMain(factory, OutboundHttpRoute.inherit());
    }

    @Bean(name = "douyinDirectRestTemplate", destroyMethod = "close")
    @ConditionalOnPluginEnabled("douyin")
    public ManagedPluginRestTemplate douyinDirectRestTemplate(OutboundHttpClientFactory factory) {
        return openMain(factory, OutboundHttpRoute.scopedOrDirect());
    }

    @Bean(name = "douyinProxyRestTemplate", destroyMethod = "close")
    @ConditionalOnPluginEnabled("douyin")
    public ManagedPluginRestTemplate douyinProxyRestTemplate(OutboundHttpClientFactory factory) {
        return openMain(factory, OutboundHttpRoute.requiredGlobalProxy());
    }

    @Bean(name = "douyinCustomProxyRestTemplate", destroyMethod = "close")
    @ConditionalOnPluginEnabled("douyin")
    public ManagedPluginRestTemplate douyinCustomProxyRestTemplate(
            OutboundHttpClientFactory factory,
            DouyinPluginSettingsService settingsService
    ) {
        return openMain(factory, customRoute(settingsService));
    }

    @Bean(name = "douyinRedirectRestTemplate", destroyMethod = "close")
    @ConditionalOnPluginEnabled("douyin")
    public ManagedPluginRestTemplate douyinRedirectRestTemplate(OutboundHttpClientFactory factory) {
        return openRedirect(factory, OutboundHttpRoute.inherit());
    }

    @Bean(name = "douyinDirectRedirectRestTemplate", destroyMethod = "close")
    @ConditionalOnPluginEnabled("douyin")
    public ManagedPluginRestTemplate douyinDirectRedirectRestTemplate(OutboundHttpClientFactory factory) {
        return openRedirect(factory, OutboundHttpRoute.scopedOrDirect());
    }

    @Bean(name = "douyinProxyRedirectRestTemplate", destroyMethod = "close")
    @ConditionalOnPluginEnabled("douyin")
    public ManagedPluginRestTemplate douyinProxyRedirectRestTemplate(OutboundHttpClientFactory factory) {
        return openRedirect(factory, OutboundHttpRoute.requiredGlobalProxy());
    }

    @Bean(name = "douyinCustomProxyRedirectRestTemplate", destroyMethod = "close")
    @ConditionalOnPluginEnabled("douyin")
    public ManagedPluginRestTemplate douyinCustomProxyRedirectRestTemplate(
            OutboundHttpClientFactory factory,
            DouyinPluginSettingsService settingsService
    ) {
        return openRedirect(factory, customRoute(settingsService));
    }

    private static ManagedPluginRestTemplate openMain(
            OutboundHttpClientFactory factory,
            OutboundHttpRoute route
    ) {
        return PluginRestTemplateAdapter.open(factory, new OutboundHttpClientProfile(
                MAIN_CONNECT_TIMEOUT,
                MAIN_READ_TIMEOUT,
                route,
                OutboundHttpRedirectPolicy.NEVER,
                OutboundHttpCookiePolicy.DISABLED,
                20,
                10));
    }

    private static ManagedPluginRestTemplate openRedirect(
            OutboundHttpClientFactory factory,
            OutboundHttpRoute route
    ) {
        return PluginRestTemplateAdapter.open(factory, new OutboundHttpClientProfile(
                REDIRECT_CONNECT_TIMEOUT,
                REDIRECT_READ_TIMEOUT,
                route,
                OutboundHttpRedirectPolicy.NEVER,
                OutboundHttpCookiePolicy.ENABLED,
                8,
                4));
    }

    private static OutboundHttpRoute customRoute(DouyinPluginSettingsService settingsService) {
        Objects.requireNonNull(settingsService, "settingsService");
        return OutboundHttpRoute.requiredExplicitProxy(
                () -> resolveCustomProxy(settingsService.runtimeSettings()));
    }

    private static URI resolveCustomProxy(DouyinRuntimeSettings settings) {
        if (settings == null || !settings.hasCustomProxyEndpoint()) {
            return null;
        }
        OutboundProxyEndpoint endpoint = OutboundProxyOverride.parse(
                settings.proxyHost() + ":" + settings.proxyPort());
        return endpoint == null
                ? null
                : URI.create("http://" + endpoint.hostName() + ":" + endpoint.port());
    }
}
