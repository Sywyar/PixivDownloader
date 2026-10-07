package top.sywyar.pixivdownload.config;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.WebServerException;
import top.sywyar.pixivdownload.plugin.runtime.artifact.PluginDevelopmentArtifacts;

import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("开发模式实际绑定端口重试")
class DevelopmentWebServerCustomizerTest {
    @TempDir Path tempDir;
    private final List<WebServer> servers = new ArrayList<>();
    private String previousMode;

    @BeforeEach
    void enableDevelopment() {
        previousMode = System.getProperty(PluginDevelopmentArtifacts.ENABLED_PROPERTY);
        System.setProperty(PluginDevelopmentArtifacts.ENABLED_PROPERTY, "true");
    }

    @AfterEach
    void cleanup() {
        for (WebServer server : servers) {
            server.stop();
            server.destroy();
        }
        if (previousMode == null) System.clearProperty(PluginDevelopmentArtifacts.ENABLED_PROPERTY);
        else System.setProperty(PluginDevelopmentArtifacts.ENABLED_PROPERTY, previousMode);
    }

    @Test
    @DisplayName("服务器创建后端口才被抢占，实际启动仍递增并响应请求")
    void retriesConflictAfterServerCreation() throws Exception {
        int requested;
        try (ServerSocket available = new ServerSocket(0)) { requested = available.getLocalPort(); }
        WebServer server = server(requested);
        try (ServerSocket occupied = new ServerSocket(requested)) {
            server.start();
            assertThat(server.getPort()).isGreaterThan(requested);
            assertResponds(server);
            assertPublicAddresses(server, requested, "http");
            // 成功的 socket 始终归本实例持有，不能再次被其它进程绑定。
            try (ServerSocket contender = new ServerSocket()) {
                contender.setReuseAddress(false);
                assertThatThrownBy(() -> contender.bind(new InetSocketAddress(server.getPort())))
                        .isInstanceOf(BindException.class);
            }
        }
    }

    @Test
    @DisplayName("两个服务器同时争用同一端口，均能启动并占用不同端口")
    void concurrentStartsKeepDistinctListeners() throws Exception {
        int requested;
        try (ServerSocket available = new ServerSocket(0)) { requested = available.getLocalPort(); }
        WebServer first = server(requested);
        WebServer second = server(requested);
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var one = executor.submit(() -> { start.await(); first.start(); return first.getPort(); });
            var two = executor.submit(() -> { start.await(); second.start(); return second.getPort(); });
            start.countDown();
            int firstPort = one.get(30, TimeUnit.SECONDS);
            int secondPort = two.get(30, TimeUnit.SECONDS);
            assertThat(firstPort).isNotEqualTo(secondPort);
            assertThat(Math.min(firstPort, secondPort)).isEqualTo(requested);
            assertResponds(first);
            assertResponds(second);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @DisplayName("普通模式端口冲突仍失败，不自动改端口")
    void normalModeStillFailsOnConflict() throws Exception {
        System.setProperty(PluginDevelopmentArtifacts.ENABLED_PROPERTY, "false");
        try (ServerSocket occupied = new ServerSocket(0)) {
            WebServer server = server(occupied.getLocalPort());
            assertThatThrownBy(server::start).isInstanceOf(WebServerException.class);
        }
    }

    @Test
    @DisplayName("端口到达 65535 后停止重试，不回绕")
    void portLimitDoesNotWrap() throws Exception {
        try (ServerSocket occupied = new ServerSocket()) {
            try { occupied.bind(new InetSocketAddress(65_535)); }
            catch (BindException alreadyOccupied) { /* 已有监听同样验证耗尽路径。 */ }
            WebServer server = server(65_535);
            assertThatThrownBy(server::start).isInstanceOf(WebServerException.class);
        }
    }

    @Test
    @DisplayName("随机端口继续由操作系统分配")
    void randomPortStillWorks() throws Exception {
        WebServer server = server(0);
        server.start();
        assertThat(server.getPort()).isBetween(1, 65_535);
        assertResponds(server);
    }

    @Test
    @DisplayName("HTTPS 与 HTTP 重定向端口均被占用时，分别重试并重定向到最终 HTTPS 端口")
    void httpsRedirectFollowsBoundPort() throws Exception {
        Path keyStoreFile = tempDir.resolve("localhost.p12");
        Path keytool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool");
        Process certificate = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "localhost",
                "-keyalg", "RSA", "-keystore", keyStoreFile.toString(), "-storetype", "PKCS12",
                "-storepass", "test-only-password", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost",
                "-validity", "1", "-noprompt").redirectErrorStream(true)
                .redirectOutput(tempDir.resolve("keytool.log").toFile()).start();
        try {
            assertThat(certificate.waitFor(30, TimeUnit.SECONDS)).isTrue();
            assertThat(certificate.exitValue()).isZero();
        } finally {
            if (certificate.isAlive()) certificate.destroyForcibly();
        }
        try (ServerSocket occupied = new ServerSocket(0);
             ServerSocket redirectOccupied = new ServerSocket(0)) {
            var factory = new TomcatServletWebServerFactory(occupied.getLocalPort());
            factory.setBaseDirectory(tempDir.resolve("https").toFile());
            SslConfig ssl = new SslConfig();
            ssl.setType("jks");
            ssl.setHttpRedirect(true);
            ssl.setHttpRedirectPort(redirectOccupied.getLocalPort());
            var environment = new org.springframework.mock.env.MockEnvironment()
                    .withProperty("server.ssl.enabled", "true")
                    .withProperty("server.port", Integer.toString(occupied.getLocalPort()))
                    .withProperty("server.ssl.key-store", keyStoreFile.toString())
                    .withProperty("server.ssl.key-store-type", "PKCS12")
                    .withProperty("server.ssl.key-store-password", "test-only-password");
            new HttpsWebServerCustomizer(ssl, environment, new top.sywyar.pixivdownload.i18n.AppMessages(
                    new org.springframework.context.support.StaticMessageSource())).customize(factory);
            new DevelopmentWebServerCustomizer().customize(factory);
            WebServer server = server(factory);
            server.start();
            assertPublicAddresses(server, occupied.getLocalPort(), "https");
            int redirectPort = factory.getAdditionalTomcatConnectors().get(0).getLocalPort();
            assertThat(redirectPort).isGreaterThan(redirectOccupied.getLocalPort()).isNotEqualTo(server.getPort());
            var response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + redirectPort + "/"))
                            .timeout(Duration.ofSeconds(5)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isBetween(300, 399);
            URI location = URI.create(response.headers().firstValue("location").orElseThrow());
            assertThat(location.getScheme()).isEqualTo("https");
            assertThat(location.getPort()).isEqualTo(server.getPort()).isGreaterThan(occupied.getLocalPort());
            var keyStore = java.security.KeyStore.getInstance(keyStoreFile.toFile(), "test-only-password".toCharArray());
            var trust = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
            trust.init(keyStore);
            var tls = javax.net.ssl.SSLContext.getInstance("TLS");
            tls.init(null, trust.getTrustManagers(), null);
            var secured = HttpClient.newBuilder().sslContext(tls).build().send(
                    HttpRequest.newBuilder(location).timeout(Duration.ofSeconds(5)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(secured.statusCode()).isEqualTo(200);
            assertThat(secured.body()).isEqualTo("ready");
        }
    }

    private WebServer server(int port) {
        var factory = new TomcatServletWebServerFactory(port);
        factory.setBaseDirectory(tempDir.resolve("server-" + servers.size()).toFile());
        new DevelopmentWebServerCustomizer().customize(factory);
        return server(factory);
    }

    private WebServer server(TomcatServletWebServerFactory factory) {
        WebServer server = factory.getWebServer(context -> context.addServlet("probe", new HttpServlet() {
            @Override protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
                response.getWriter().print("ready");
            }
        }).addMapping("/"));
        servers.add(server);
        return server;
    }

    private static void assertResponds(WebServer server) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + server.getPort() + "/"))
                .timeout(Duration.ofSeconds(5)).build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("ready");
    }

    private static void assertPublicAddresses(WebServer server, int configuredPort, String scheme) throws Exception {
        var environment = new org.springframework.mock.env.MockEnvironment()
                .withProperty("server.port", Integer.toString(configuredPort));
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.setEnvironment(environment);
            context.registerBean(SslConfig.class);
            context.registerBean(top.sywyar.pixivdownload.config.http.ServerAddressProvider.class);
            context.refresh();
            context.publishEvent(new org.springframework.boot.web.servlet.context.ServletWebServerInitializedEvent(
                    server, new org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext()));
            var address = context.getBean(top.sywyar.pixivdownload.config.http.ServerAddressProvider.class);
            String base = scheme + "://localhost:" + server.getPort();
            assertThat(address.uri("/setup.html").toString()).isEqualTo(base + "/setup.html");
            var mapper = org.mockito.Mockito.mock(top.sywyar.pixivdownload.setup.guest.persistence.GuestInviteMapper.class);
            org.mockito.Mockito.doAnswer(call -> {
                top.sywyar.pixivdownload.setup.guest.persistence.GuestInviteRow row = call.getArgument(0);
                row.setId(1L);
                org.mockito.Mockito.when(mapper.findById(1L)).thenReturn(row);
                return null;
            }).when(mapper).insertInvite(org.mockito.ArgumentMatchers.any());
            var invites = new top.sywyar.pixivdownload.setup.guest.controller.AdminInviteController(
                    new top.sywyar.pixivdownload.setup.guest.GuestInviteService(mapper, address, null));
            var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(invites).build();
            var created = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .post("/api/admin/invites").contentType("application/json").content("{\"name\":\"address test\"}"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                    .andReturn().getResponse();
            var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(created.getContentAsByteArray());
            assertThat(json.path("url").asText()).isEqualTo(base + "/invite?code=" + json.path("code").asText());
            assertThat(invites.detail(1L).url()).isEqualTo(json.path("url").asText());
            var status = new top.sywyar.pixivdownload.gui.controller.GuiStatusController(
                    org.mockito.Mockito.mock(top.sywyar.pixivdownload.common.ServerStateProvider.class),
                    address, null, null, null, null);
            status.init();
            var request = new org.springframework.mock.web.MockHttpServletRequest();
            request.setRemoteAddr("127.0.0.1");
            var body = status.status(request).getBody();
            assertThat(body.getPort()).isEqualTo(server.getPort());
            assertThat(body.getScheme()).isEqualTo(scheme);
            assertThat(body.getDomain()).isEqualTo("localhost");
            assertThat(environment.getProperty("server.port")).isEqualTo(Integer.toString(configuredPort));
            var management = new org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext();
            management.setServerNamespace("management");
            context.publishEvent(new org.springframework.boot.web.servlet.context.ServletWebServerInitializedEvent(
                    org.mockito.Mockito.mock(WebServer.class), management));
            assertThat(address.baseUri().toString()).isEqualTo(base);
        }
    }
}
