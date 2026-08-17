package top.sywyar.pixivdownload.douyin.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Import;
import top.sywyar.pixivdownload.douyin.DouyinPluginConfiguration;
import top.sywyar.pixivdownload.douyin.settings.DouyinPluginSettingsService;
import top.sywyar.pixivdownload.douyin.settings.DouyinProxyMode;
import top.sywyar.pixivdownload.douyin.settings.DouyinRuntimeSettings;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpClient;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpClientFactory;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpClientProfile;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpCookiePolicy;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpRedirectPolicy;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpRoutePolicy;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Douyin HTTP 客户端所有权")
class DouyinHttpClientOwnershipTest {

    private static final Set<String> MANAGED_CLIENT_BEANS = Set.of(
            "douyinHttpClient",
            "douyinDirectHttpClient",
            "douyinProxyHttpClient",
            "douyinCustomProxyHttpClient",
            "douyinRedirectHttpClient",
            "douyinDirectRedirectHttpClient",
            "douyinProxyRedirectHttpClient",
            "douyinCustomProxyRedirectHttpClient");

    @Test
    @DisplayName("主插件配置显式导入 HTTP 子配置")
    void pluginConfigurationImportsHttpClientConfiguration() {
        Import imports = DouyinPluginConfiguration.class.getDeclaredAnnotation(Import.class);

        assertThat(imports).isNotNull();
        assertThat(imports.value()).contains(DouyinHttpClientConfiguration.class);
    }

    @Test
    @DisplayName("八个子上下文客户端使用精确资源配置与代理路由")
    void opensEightChildOwnedClientsWithExactProfiles() {
        List<OutboundHttpClientProfile> profiles = new ArrayList<>();
        List<OutboundHttpClient> transports = new ArrayList<>();
        OutboundHttpClientFactory factory = profile -> {
            profiles.add(profile);
            OutboundHttpClient transport = mock(OutboundHttpClient.class);
            transports.add(transport);
            return transport;
        };
        DouyinPluginSettingsService settingsService = DouyinPluginSettingsService.fixed(
                Path.of("target", "douyin-http-profile-test"),
                DouyinProxyMode.CUSTOM,
                "127.0.0.1",
                1080);
        DouyinHttpClientConfiguration configuration = new DouyinHttpClientConfiguration();

        List<OutboundHttpClient> clients = List.of(
                configuration.douyinHttpClient(factory),
                configuration.douyinDirectHttpClient(factory),
                configuration.douyinProxyHttpClient(factory),
                configuration.douyinCustomProxyHttpClient(factory, settingsService),
                configuration.douyinRedirectHttpClient(factory),
                configuration.douyinDirectRedirectHttpClient(factory),
                configuration.douyinProxyRedirectHttpClient(factory),
                configuration.douyinCustomProxyRedirectHttpClient(factory, settingsService));

        assertThat(profiles).hasSize(8);
        assertThat(profiles)
                .extracting(profile -> profile.route().policy())
                .containsExactly(
                        OutboundHttpRoutePolicy.SCOPED_OR_GLOBAL_IF_ENABLED,
                        OutboundHttpRoutePolicy.SCOPED_OR_DIRECT,
                        OutboundHttpRoutePolicy.SCOPED_OR_GLOBAL_REQUIRED,
                        OutboundHttpRoutePolicy.SCOPED_OR_EXPLICIT_REQUIRED,
                        OutboundHttpRoutePolicy.SCOPED_OR_GLOBAL_IF_ENABLED,
                        OutboundHttpRoutePolicy.SCOPED_OR_DIRECT,
                        OutboundHttpRoutePolicy.SCOPED_OR_GLOBAL_REQUIRED,
                        OutboundHttpRoutePolicy.SCOPED_OR_EXPLICIT_REQUIRED);
        assertThat(profiles.subList(0, 4)).allSatisfy(profile -> {
            assertThat(profile.connectTimeout()).isEqualTo(Duration.ofSeconds(30));
            assertThat(profile.readTimeout()).isEqualTo(Duration.ofSeconds(60));
            assertThat(profile.redirectPolicy()).isEqualTo(OutboundHttpRedirectPolicy.NEVER);
            assertThat(profile.cookiePolicy()).isEqualTo(OutboundHttpCookiePolicy.DISABLED);
            assertThat(profile.maxConnections()).isEqualTo(20);
            assertThat(profile.maxConnectionsPerRoute()).isEqualTo(10);
        });
        assertThat(profiles.subList(4, 8)).allSatisfy(profile -> {
            assertThat(profile.connectTimeout()).isEqualTo(Duration.ofSeconds(10));
            assertThat(profile.readTimeout()).isEqualTo(Duration.ofSeconds(10));
            assertThat(profile.redirectPolicy()).isEqualTo(OutboundHttpRedirectPolicy.NEVER);
            assertThat(profile.cookiePolicy()).isEqualTo(OutboundHttpCookiePolicy.ENABLED);
            assertThat(profile.maxConnections()).isEqualTo(8);
            assertThat(profile.maxConnectionsPerRoute()).isEqualTo(4);
        });
        assertThat(profiles)
                .filteredOn(profile -> profile.route().policy()
                        == OutboundHttpRoutePolicy.SCOPED_OR_EXPLICIT_REQUIRED)
                .allSatisfy(profile ->
                        assertThat(profile.route().explicitProxyProvider()).isNotNull());
        assertThat(profiles)
                .filteredOn(profile -> profile.route().policy()
                        != OutboundHttpRoutePolicy.SCOPED_OR_EXPLICIT_REQUIRED)
                .allSatisfy(profile ->
                        assertThat(profile.route().explicitProxyProvider()).isNull());

        clients.forEach(OutboundHttpClient::close);
        transports.forEach(transport -> verify(transport).close());
    }

    @Test
    @DisplayName("自定义代理端点按请求动态读取且非法值关闭失败")
    void customProxyProviderReadsCurrentSettingsForEveryRequest() {
        DouyinPluginSettingsService settingsService = mock(DouyinPluginSettingsService.class);
        when(settingsService.runtimeSettings()).thenReturn(
                settings("127.0.0.1", 1080),
                settings("proxy.internal", 7890),
                settings("http://127.0.0.1", 1080),
                settings("", 0));
        OutboundHttpClient transport = mock(OutboundHttpClient.class);
        AtomicReference<OutboundHttpClientProfile> captured = new AtomicReference<>();
        OutboundHttpClientFactory factory = profile -> {
            captured.set(profile);
            return transport;
        };
        OutboundHttpClient client = new DouyinHttpClientConfiguration()
                .douyinCustomProxyHttpClient(factory, settingsService);

        var provider = captured.get().route().explicitProxyProvider();

        assertThat(provider.resolveProxyUri()).isEqualTo(URI.create("http://127.0.0.1:1080"));
        assertThat(provider.resolveProxyUri()).isEqualTo(URI.create("http://proxy.internal:7890"));
        assertThat(provider.resolveProxyUri()).isNull();
        assertThat(provider.resolveProxyUri()).isNull();
        verify(settingsService, times(4)).runtimeSettings();

        client.close();
        verify(transport).close();
    }

    @Test
    @DisplayName("父子上下文重建时八个客户端由子上下文按代关闭")
    void childContextOwnsAndRecreatesManagedTransports() {
        CountingOutboundHttpClientFactory factory = new CountingOutboundHttpClientFactory();
        AnnotationConfigApplicationContext parent = new AnnotationConfigApplicationContext();
        parent.registerBean(
                "outboundHttpClientFactory",
                OutboundHttpClientFactory.class,
                () -> factory);
        parent.refresh();

        assertThat(parent.getBeanFactory().getBeansOfType(OutboundHttpClient.class)).isEmpty();
        assertThat(parent.getBeanFactory().getBeansOfType(DouyinPluginSettingsService.class)).isEmpty();
        assertThat(parent.getBean(OutboundHttpClientFactory.class)).isSameAs(factory);

        ChildGeneration first = openChild(parent, "first");
        List<OutboundHttpClient> firstTransports = factory.openedTransports();
        assertThat(firstTransports).hasSize(MANAGED_CLIENT_BEANS.size());

        closeRepeatedly(first);
        assertClosedExactlyOnce(firstTransports);

        ChildGeneration second = openChild(parent, "second");
        List<OutboundHttpClient> allTransports = factory.openedTransports();
        assertThat(allTransports).hasSize(MANAGED_CLIENT_BEANS.size() * 2);
        List<OutboundHttpClient> secondTransports =
                List.copyOf(allTransports.subList(MANAGED_CLIENT_BEANS.size(), allTransports.size()));
        assertThat(secondTransports)
                .hasSize(MANAGED_CLIENT_BEANS.size())
                .doesNotContainAnyElementsOf(firstTransports);

        closeRepeatedly(second);
        assertClosedExactlyOnce(firstTransports);
        assertClosedExactlyOnce(secondTransports);

        assertThat(parent.getBeanFactory().getBeansOfType(OutboundHttpClient.class)).isEmpty();
        parent.close();
        parent.close();
        assertClosedExactlyOnce(factory.openedTransports());
    }

    private static DouyinRuntimeSettings settings(String host, int port) {
        return new DouyinRuntimeSettings(
                Path.of("target", "douyin-http-profile-test"),
                DouyinProxyMode.CUSTOM,
                host,
                port);
    }

    private static ChildGeneration openChild(
            AnnotationConfigApplicationContext parent,
            String generation
    ) {
        AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext();
        child.setParent(parent);
        DouyinPluginSettingsService settingsService = DouyinPluginSettingsService.fixed(
                Path.of("target", "douyin-http-context-" + generation),
                DouyinProxyMode.CUSTOM,
                "127.0.0.1",
                1080);
        child.registerBean(
                "douyinPluginSettingsService",
                DouyinPluginSettingsService.class,
                () -> settingsService);
        child.register(DouyinHttpClientConfiguration.class);
        child.refresh();

        Map<String, OutboundHttpClient> clients =
                child.getBeanFactory().getBeansOfType(OutboundHttpClient.class);
        assertThat(clients)
                .hasSize(MANAGED_CLIENT_BEANS.size())
                .containsOnlyKeys(MANAGED_CLIENT_BEANS);
        assertThat(child.getBeanFactory().containsLocalBean("douyinPluginSettingsService")).isTrue();
        assertThat(child.getBean(DouyinPluginSettingsService.class)).isSameAs(settingsService);
        assertThat(child.getBeanFactory().containsLocalBean("outboundHttpClientFactory")).isFalse();
        assertThat(child.getBean(OutboundHttpClientFactory.class))
                .isSameAs(parent.getBean(OutboundHttpClientFactory.class));
        MANAGED_CLIENT_BEANS.forEach(beanName -> {
            assertThat(child.getBeanFactory().containsLocalBean(beanName)).as(beanName).isTrue();
            assertThat(parent.containsBean(beanName)).as(beanName).isFalse();
        });
        return new ChildGeneration(child);
    }

    private static void closeRepeatedly(ChildGeneration generation) {
        generation.context().close();
        generation.context().close();
    }

    private static void assertClosedExactlyOnce(List<OutboundHttpClient> transports) {
        transports.forEach(transport -> verify(transport).close());
    }

    private record ChildGeneration(AnnotationConfigApplicationContext context) {
    }

    private static final class CountingOutboundHttpClientFactory implements OutboundHttpClientFactory {

        private final List<OutboundHttpClient> transports = new ArrayList<>();

        @Override
        public OutboundHttpClient open(OutboundHttpClientProfile profile) {
            OutboundHttpClient transport = mock(OutboundHttpClient.class);
            transports.add(transport);
            return transport;
        }

        private List<OutboundHttpClient> openedTransports() {
            return List.copyOf(transports);
        }
    }
}
