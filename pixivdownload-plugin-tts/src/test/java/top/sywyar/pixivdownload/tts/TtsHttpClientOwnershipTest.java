package top.sywyar.pixivdownload.tts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestTemplate;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpClient;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpClientFactory;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpClientProfile;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpCookiePolicy;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpRedirectPolicy;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpRequest;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpRoutePolicy;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpStreamResponse;
import top.sywyar.pixivdownload.plugin.api.http.websocket.OutboundWebSocketClient;
import top.sywyar.pixivdownload.plugin.api.http.websocket.OutboundWebSocketClientFactory;
import top.sywyar.pixivdownload.plugin.api.http.websocket.OutboundWebSocketClientProfile;
import top.sywyar.pixivdownload.plugin.api.http.websocket.OutboundWebSocketRequest;
import top.sywyar.pixivdownload.plugin.runtime.http.ManagedPluginRestTemplate;
import top.sywyar.pixivdownload.tts.http.TtsHttpClientConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

@DisplayName("TTS 插件自有出站客户端")
class TtsHttpClientOwnershipTest {

    private static final List<String> HOST_PRIVATE_CLASS_RESOURCES = List.of(
            "top/sywyar/pixivdownload/PixivDownloadApplication.class",
            "org/apache/hc/client5/http/impl/classic/CloseableHttpClient.class",
            "org/apache/hc/core5/http/HttpRequest.class",
            "org/apache/http/client/HttpClient.class",
            "org/apache/http/nio/client/HttpAsyncClient.class");
    private static final List<String> PRIVATE_HTTP_SOURCE_REFERENCES = List.of(
            "org.apache.hc.",
            "org.apache.http.",
            "HttpComponentsClientHttpRequestFactory",
            "java.net.http.HttpClient",
            "java.net.ProxySelector");
    private static final Pattern JAVA_TOKEN = Pattern.compile(
            "\"\"\"(?:\\\\.|(?!\"\"\")[\\s\\S])*\"\"\""
                    + "|\"(?:\\\\.|[^\"\\\\])*\""
                    + "|'(?:\\\\.|[^'\\\\])*'"
                    + "|(?<comment>//[^\\r\\n]*|/\\*[\\s\\S]*?\\*/)");
    private static final Pattern QUALIFIED_NAME_SEPARATOR = Pattern.compile("\\s*\\.\\s*");
    private static final List<String> CLIENT_BEANS = List.of(
            "ttsMetadataRestTemplate",
            "narrationTtsRestTemplate",
            "narrationTtsProxyRestTemplate",
            "narrationTtsProbeRestTemplate",
            "narrationTtsProbeProxyRestTemplate");

    @Test
    @DisplayName("主插件配置显式导入 HTTP 子配置")
    void pluginConfigurationImportsHttpClientConfiguration() {
        Import imports = TtsPluginConfiguration.class.getDeclaredAnnotation(Import.class);

        assertThat(imports).isNotNull();
        assertThat(imports.value()).contains(TtsHttpClientConfiguration.class);
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
    @DisplayName("父容器只提供工厂且子容器关闭和重建各自管理全新 HTTP 与 WebSocket 客户端")
    void childContextOwnsProfilesAndLifecycle() {
        CountingFactory httpFactory = new CountingFactory();
        CountingWebSocketFactory webSocketFactory = new CountingWebSocketFactory();

        try (AnnotationConfigApplicationContext parent =
                     parentContext(httpFactory, webSocketFactory)) {
            List<OutboundHttpClientProfile> firstProfiles;
            List<CountingClient> firstClients;
            List<OutboundWebSocketClientProfile> firstWebSocketProfiles;
            List<CountingWebSocketClient> firstWebSocketClients;
            try (AnnotationConfigApplicationContext firstChild = childContext(parent)) {
                assertChildOnlyBeans(parent, firstChild);
                firstProfiles = httpFactory.profileSnapshot(0);
                firstClients = httpFactory.clientSnapshot(0);
                firstWebSocketProfiles = webSocketFactory.profileSnapshot(0);
                firstWebSocketClients = webSocketFactory.clientSnapshot(0);
                assertProfiles(firstProfiles, firstWebSocketProfiles);

                firstChild.close();
                firstChild.close();
                assertClosedOnce(firstClients);
                assertWebSocketsClosedOnce(firstWebSocketClients);
            }

            try (AnnotationConfigApplicationContext secondChild = childContext(parent)) {
                assertChildOnlyBeans(parent, secondChild);
                List<OutboundHttpClientProfile> secondProfiles =
                        httpFactory.profileSnapshot(firstProfiles.size());
                List<CountingClient> secondClients =
                        httpFactory.clientSnapshot(firstClients.size());
                List<OutboundWebSocketClientProfile> secondWebSocketProfiles =
                        webSocketFactory.profileSnapshot(firstWebSocketProfiles.size());
                List<CountingWebSocketClient> secondWebSocketClients =
                        webSocketFactory.clientSnapshot(firstWebSocketClients.size());

                assertThat(secondProfiles).containsExactlyElementsOf(firstProfiles);
                assertThat(secondClients).doesNotContainAnyElementsOf(firstClients);
                assertThat(secondWebSocketProfiles)
                        .containsExactlyElementsOf(firstWebSocketProfiles);
                assertThat(secondWebSocketClients)
                        .doesNotContainAnyElementsOf(firstWebSocketClients);
                assertClosedOnce(firstClients);
                assertWebSocketsClosedOnce(firstWebSocketClients);

                secondChild.close();
                secondChild.close();
                assertClosedOnce(secondClients);
                assertWebSocketsClosedOnce(secondWebSocketClients);
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
        assertThat(violations).as("TTS 插件生产源码中的宿主私有传输实现引用").isEmpty();
    }

    private static Path moduleRoot() {
        Path reactorModule = Path.of("pixivdownload-plugin-tts");
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

    private static AnnotationConfigApplicationContext parentContext(
            CountingFactory httpFactory,
            CountingWebSocketFactory webSocketFactory
    ) {
        AnnotationConfigApplicationContext parent = new AnnotationConfigApplicationContext();
        parent.getBeanFactory().registerSingleton("outboundHttpClientFactory", httpFactory);
        parent.getBeanFactory().registerSingleton(
                "outboundWebSocketClientFactory", webSocketFactory);
        parent.refresh();
        return parent;
    }

    private static AnnotationConfigApplicationContext childContext(
            AnnotationConfigApplicationContext parent
    ) {
        AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext();
        child.setParent(parent);
        child.register(TtsHttpClientConfiguration.class);
        child.refresh();
        return child;
    }

    private static void assertChildOnlyBeans(
            AnnotationConfigApplicationContext parent,
            AnnotationConfigApplicationContext child
    ) {
        assertThat(parent.getBeanFactory().getBeanNamesForType(RestTemplate.class)).isEmpty();
        assertThat(parent.getBeanFactory().getBeanNamesForType(OutboundWebSocketClient.class))
                .isEmpty();
        assertThat(child.getBeanFactory().getBeanNamesForType(ManagedPluginRestTemplate.class))
                .containsExactlyInAnyOrderElementsOf(CLIENT_BEANS);
        assertThat(child.getBeanFactory().getBeanNamesForType(OutboundWebSocketClient.class))
                .containsExactly("edgeTtsWebSocketClient");
        assertThat(CLIENT_BEANS)
                .allMatch(beanName -> !parent.containsLocalBean(beanName))
                .allMatch(child::containsLocalBean);
        assertThat(parent.containsLocalBean("edgeTtsWebSocketClient")).isFalse();
        assertThat(child.containsLocalBean("edgeTtsWebSocketClient")).isTrue();
    }

    private static void assertProfiles(
            List<OutboundHttpClientProfile> httpProfiles,
            List<OutboundWebSocketClientProfile> webSocketProfiles
    ) {
        assertThat(httpProfiles)
                .extracting(
                        OutboundHttpClientProfile::connectTimeout,
                        OutboundHttpClientProfile::readTimeout,
                        profile -> profile.route().policy())
                .containsExactlyInAnyOrder(
                        tuple(
                                Duration.ofSeconds(15),
                                Duration.ofSeconds(30),
                                OutboundHttpRoutePolicy.SCOPED_OR_GLOBAL_IF_ENABLED),
                        tuple(
                                Duration.ofSeconds(30),
                                Duration.ofSeconds(300),
                                OutboundHttpRoutePolicy.DIRECT),
                        tuple(
                                Duration.ofSeconds(30),
                                Duration.ofSeconds(300),
                                OutboundHttpRoutePolicy.GLOBAL_IF_CONFIGURED),
                        tuple(
                                Duration.ofSeconds(2),
                                Duration.ofSeconds(4),
                                OutboundHttpRoutePolicy.DIRECT),
                        tuple(
                                Duration.ofSeconds(2),
                                Duration.ofSeconds(4),
                                OutboundHttpRoutePolicy.GLOBAL_IF_CONFIGURED));
        assertThat(httpProfiles)
                .filteredOn(profile -> profile.redirectPolicy() == OutboundHttpRedirectPolicy.NEVER)
                .hasSize(4)
                .allSatisfy(profile -> assertThat(profile.cookiePolicy())
                        .isEqualTo(OutboundHttpCookiePolicy.DISABLED));
        assertThat(httpProfiles)
                .filteredOn(profile -> profile.redirectPolicy() == OutboundHttpRedirectPolicy.FOLLOW)
                .singleElement()
                .satisfies(profile -> assertThat(profile.cookiePolicy())
                        .isEqualTo(OutboundHttpCookiePolicy.ENABLED));
        assertThat(webSocketProfiles)
                .extracting(
                        OutboundWebSocketClientProfile::connectTimeout,
                        profile -> profile.route().policy())
                .containsExactly(tuple(
                        Duration.ofSeconds(15),
                        OutboundHttpRoutePolicy.SCOPED_OR_GLOBAL_IF_ENABLED));
    }

    private static void assertClosedOnce(List<CountingClient> clients) {
        assertThat(clients)
                .extracting(CountingClient::closeCount)
                .containsOnly(1);
    }

    private static void assertWebSocketsClosedOnce(List<CountingWebSocketClient> clients) {
        assertThat(clients)
                .extracting(CountingWebSocketClient::closeCount)
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

    private static final class CountingWebSocketFactory
            implements OutboundWebSocketClientFactory {

        private final List<OutboundWebSocketClientProfile> profiles = new ArrayList<>();
        private final List<CountingWebSocketClient> clients = new ArrayList<>();

        @Override
        public OutboundWebSocketClient open(OutboundWebSocketClientProfile profile) {
            profiles.add(profile);
            CountingWebSocketClient client = new CountingWebSocketClient();
            clients.add(client);
            return client;
        }

        private List<OutboundWebSocketClientProfile> profileSnapshot(int fromIndex) {
            return List.copyOf(profiles.subList(fromIndex, profiles.size()));
        }

        private List<CountingWebSocketClient> clientSnapshot(int fromIndex) {
            return List.copyOf(clients.subList(fromIndex, clients.size()));
        }
    }

    private static final class CountingWebSocketClient implements OutboundWebSocketClient {

        private final AtomicInteger closeCount = new AtomicInteger();

        @Override
        public CompletableFuture<WebSocket> connect(
                OutboundWebSocketRequest request,
                WebSocket.Listener listener
        ) {
            return new CompletableFuture<>();
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
