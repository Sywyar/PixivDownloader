package top.sywyar.pixivdownload.push;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpClient;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpClientFactory;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpClientProfile;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpRequest;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpRoutePolicy;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpStreamResponse;
import top.sywyar.pixivdownload.plugin.runtime.http.ManagedPluginRestTemplate;
import top.sywyar.pixivdownload.push.http.PushHttpClientConfiguration;

import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

@DisplayName("Push 插件自有 HTTP 客户端")
class PushHttpClientOwnershipTest {

    private static final String DIRECT_BEAN = "pushRestTemplate";
    private static final String PROXY_BEAN = "pushProxyRestTemplate";

    @Test
    @DisplayName("主插件配置显式导入 HTTP 子配置")
    void pluginConfigurationImportsHttpClientConfiguration() {
        Import imports = PushPluginConfiguration.class.getDeclaredAnnotation(Import.class);

        assertThat(imports).isNotNull();
        assertThat(imports.value()).contains(PushHttpClientConfiguration.class);
    }

    @Test
    @DisplayName("父容器只提供工厂且子容器关闭和重建各自管理全新客户端")
    void childContextOwnsProfilesAndLifecycle() {
        CountingFactory factory = new CountingFactory();

        try (AnnotationConfigApplicationContext parent = parentContext(factory)) {
            List<OutboundHttpClientProfile> firstProfiles;
            List<CountingClient> firstClients;
            try (AnnotationConfigApplicationContext firstChild = childContext(parent)) {
                assertChildOnlyBeans(parent, firstChild);
                firstProfiles = factory.profileSnapshot(0);
                firstClients = factory.clientSnapshot(0);
                assertProfiles(firstProfiles);

                firstChild.close();
                firstChild.close();
                assertClosedOnce(firstClients);
            }

            try (AnnotationConfigApplicationContext secondChild = childContext(parent)) {
                assertChildOnlyBeans(parent, secondChild);
                List<OutboundHttpClientProfile> secondProfiles =
                        factory.profileSnapshot(firstProfiles.size());
                List<CountingClient> secondClients = factory.clientSnapshot(firstClients.size());

                assertThat(secondProfiles).containsExactlyElementsOf(firstProfiles);
                assertThat(secondClients).doesNotContainAnyElementsOf(firstClients);
                assertClosedOnce(firstClients);

                secondChild.close();
                secondChild.close();
                assertClosedOnce(secondClients);
            }
        }
    }

    private static AnnotationConfigApplicationContext parentContext(CountingFactory factory) {
        AnnotationConfigApplicationContext parent = new AnnotationConfigApplicationContext();
        parent.getBeanFactory().registerSingleton("outboundHttpClientFactory", factory);
        parent.refresh();
        return parent;
    }

    private static AnnotationConfigApplicationContext childContext(
            AnnotationConfigApplicationContext parent
    ) {
        AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext();
        child.setParent(parent);
        child.register(PushHttpClientConfiguration.class);
        child.refresh();
        return child;
    }

    private static void assertChildOnlyBeans(
            AnnotationConfigApplicationContext parent,
            AnnotationConfigApplicationContext child
    ) {
        assertThat(parent.getBeanFactory().getBeanNamesForType(RestTemplate.class)).isEmpty();
        assertThat(child.getBeanFactory().getBeanNamesForType(ManagedPluginRestTemplate.class))
                .containsExactlyInAnyOrder(DIRECT_BEAN, PROXY_BEAN);
        assertThat(parent.containsLocalBean(DIRECT_BEAN)).isFalse();
        assertThat(parent.containsLocalBean(PROXY_BEAN)).isFalse();
        assertThat(child.containsLocalBean(DIRECT_BEAN)).isTrue();
        assertThat(child.containsLocalBean(PROXY_BEAN)).isTrue();
    }

    private static void assertProfiles(List<OutboundHttpClientProfile> profiles) {
        assertThat(profiles)
                .extracting(
                        OutboundHttpClientProfile::connectTimeout,
                        OutboundHttpClientProfile::readTimeout,
                        profile -> profile.route().policy())
                .containsExactlyInAnyOrder(
                        tuple(
                                Duration.ofSeconds(10),
                                Duration.ofSeconds(15),
                                OutboundHttpRoutePolicy.DIRECT),
                        tuple(
                                Duration.ofSeconds(10),
                                Duration.ofSeconds(15),
                                OutboundHttpRoutePolicy.GLOBAL_IF_CONFIGURED));
    }

    private static void assertClosedOnce(List<CountingClient> clients) {
        assertThat(clients)
                .extracting(CountingClient::closeCount)
                .containsOnly(1);
    }

    private static final class CountingFactory implements OutboundHttpClientFactory {

        private final List<OutboundHttpClientProfile> profiles = new ArrayList<>();
        private final List<CountingClient> clients = new ArrayList<>();

        @Override
        public OutboundHttpClient open(OutboundHttpClientProfile profile) {
            profiles.add(profile);
            CountingClient client = new CountingClient();
            clients.add(client);
            return client;
        }

        private List<OutboundHttpClientProfile> profileSnapshot(int fromIndex) {
            return List.copyOf(profiles.subList(fromIndex, profiles.size()));
        }

        private List<CountingClient> clientSnapshot(int fromIndex) {
            return List.copyOf(clients.subList(fromIndex, clients.size()));
        }
    }

    private static final class CountingClient implements OutboundHttpClient {

        private final AtomicInteger closeCount = new AtomicInteger();

        @Override
        public OutboundHttpStreamResponse exchangeStream(OutboundHttpRequest request) {
            return new OutboundHttpStreamResponse(
                    204,
                    "",
                    Map.of(),
                    InputStream.nullInputStream());
        }

        @Override
        public void close() {
            closeCount.incrementAndGet();
        }

        private int closeCount() {
            return closeCount.get();
        }
    }
}
