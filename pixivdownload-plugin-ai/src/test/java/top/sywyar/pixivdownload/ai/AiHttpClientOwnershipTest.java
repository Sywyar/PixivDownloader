package top.sywyar.pixivdownload.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.ai.http.AiHttpClientConfiguration;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpClient;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpClientFactory;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpClientProfile;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpRequest;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpRoutePolicy;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpStreamResponse;
import top.sywyar.pixivdownload.plugin.runtime.http.ManagedPluginRestTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

@DisplayName("AI 插件自有 HTTP 客户端")
class AiHttpClientOwnershipTest {

    private static final List<String> HOST_PRIVATE_CLASS_RESOURCES = List.of(
            "top/sywyar/pixivdownload/PixivDownloadApplication.class",
            "org/apache/hc/client5/http/impl/classic/CloseableHttpClient.class",
            "org/apache/hc/core5/http/HttpRequest.class",
            "org/apache/http/client/HttpClient.class",
            "org/apache/http/nio/client/HttpAsyncClient.class");
    private static final List<String> PRIVATE_HTTP_SOURCE_REFERENCES = List.of(
            "org.apache.hc.",
            "org.apache.http.",
            "HttpComponentsClientHttpRequestFactory");
    private static final Pattern JAVA_TOKEN = Pattern.compile(
            "\"\"\"(?:\\\\.|(?!\"\"\")[\\s\\S])*\"\"\""
                    + "|\"(?:\\\\.|[^\"\\\\])*\""
                    + "|'(?:\\\\.|[^'\\\\])*'"
                    + "|(?<comment>//[^\\r\\n]*|/\\*[\\s\\S]*?\\*/)");
    private static final Pattern QUALIFIED_NAME_SEPARATOR = Pattern.compile("\\s*\\.\\s*");
    private static final String DIRECT_BEAN = "aiRestTemplate";
    private static final String PROXY_BEAN = "aiProxyRestTemplate";

    @Test
    @DisplayName("主插件配置显式导入 HTTP 子配置")
    void pluginConfigurationImportsHttpClientConfiguration() {
        Import imports = AiPluginConfiguration.class.getDeclaredAnnotation(Import.class);

        assertThat(imports).isNotNull();
        assertThat(imports.value()).contains(AiHttpClientConfiguration.class);
    }

    @Test
    @DisplayName("测试类路径与生产源码不得包含宿主私有 HTTP 实现")
    void classpathAndProductionSourcesExcludeHostPrivateHttpStack() throws IOException {
        ClassLoader classLoader = getClass().getClassLoader();
        for (String resource : HOST_PRIVATE_CLASS_RESOURCES) {
            assertThat(classLoader.getResource(resource)).as(resource).isNull();
        }
        assertProductionSourcesExcludePrivateHttpReferences();
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

    private static void assertProductionSourcesExcludePrivateHttpReferences() throws IOException {
        Path moduleRoot = moduleRoot();
        List<String> violations = new ArrayList<>();
        try (Stream<Path> sources = Files.walk(moduleRoot.resolve("src/main/java"))) {
            for (Path source : sources
                    .filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .toList()) {
                String code = normalizedSource(Files.readString(source));
                for (String forbidden : PRIVATE_HTTP_SOURCE_REFERENCES) {
                    if (code.contains(forbidden)) {
                        violations.add(moduleRoot.relativize(source) + " -> " + forbidden);
                    }
                }
            }
        }
        assertThat(violations).as("AI 插件生产源码中的宿主私有 HTTP 引用").isEmpty();
    }

    private static Path moduleRoot() {
        Path reactorModule = Path.of("pixivdownload-plugin-ai");
        return Files.isDirectory(reactorModule) ? reactorModule : Path.of(".");
    }

    private static String normalizedSource(String source) {
        Matcher tokens = JAVA_TOKEN.matcher(source);
        StringBuilder code = new StringBuilder(source.length());
        while (tokens.find()) {
            tokens.appendReplacement(
                    code,
                    tokens.group("comment") == null
                            ? Matcher.quoteReplacement(tokens.group())
                            : "");
        }
        return QUALIFIED_NAME_SEPARATOR
                .matcher(tokens.appendTail(code))
                .replaceAll(".");
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
        child.register(AiHttpClientConfiguration.class);
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
                                Duration.ofSeconds(30),
                                Duration.ofSeconds(120),
                                OutboundHttpRoutePolicy.DIRECT),
                        tuple(
                                Duration.ofSeconds(30),
                                Duration.ofSeconds(120),
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
