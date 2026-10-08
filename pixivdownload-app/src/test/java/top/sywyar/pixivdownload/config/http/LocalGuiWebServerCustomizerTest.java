package top.sywyar.pixivdownload.config.http;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.coyote.http11.Http11NioProtocol;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.boot.web.servlet.context.ServletWebServerInitializedEvent;
import org.springframework.mock.env.MockEnvironment;
import top.sywyar.pixivdownload.common.GuiTokenProvider;
import top.sywyar.pixivdownload.config.HttpsWebServerCustomizer;
import top.sywyar.pixivdownload.config.SslConfig;
import top.sywyar.pixivdownload.gui.GuiTokenService;
import top.sywyar.pixivdownload.i18n.AppLocaleResolver;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.maintenance.MaintenanceCoordinator;
import top.sywyar.pixivdownload.plugin.CorePlugin;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.route.RouteAccessRegistry;
import top.sywyar.pixivdownload.quota.RateLimitService;
import top.sywyar.pixivdownload.setup.AuthFilter;
import top.sywyar.pixivdownload.setup.CsrfProtectionFilter;
import top.sywyar.pixivdownload.setup.SetupService;
import top.sywyar.pixivdownload.setup.StaticResourceRateLimitService;
import top.sywyar.pixivdownload.setup.TrustedForwardedRequestFilter;
import top.sywyar.pixivdownload.setup.guest.GuestInviteService;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("GUI 本机监听与公开 HTTPS 入口隔离")
class LocalGuiWebServerCustomizerTest {
    @TempDir
    static Path temp;
    private static Path keyStoreFile;
    private static HttpClient client;

    @BeforeAll
    static void certificate() throws Exception {
        keyStoreFile = temp.resolve("localhost.p12");
        Path keytool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool");
        Process process = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "localhost",
                "-keyalg", "RSA", "-keystore", keyStoreFile.toString(), "-storetype", "PKCS12",
                "-storepass", "test-only-password", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost",
                "-validity", "1", "-noprompt").redirectErrorStream(true)
                .redirectOutput(temp.resolve("keytool.log").toFile()).start();
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isZero();
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
        var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(KeyStore.getInstance(keyStoreFile.toFile(), "test-only-password".toCharArray()));
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(null, trust.getTrustManagers(), null);
        client = HttpClient.newBuilder().sslContext(tls).connectTimeout(Duration.ofSeconds(2)).build();
    }

    @Test
    @DisplayName("仅桌面模式装配内部监听")
    void desktopOnly() {
        var runner = new ApplicationContextRunner()
                .withBean(GuiTokenProvider.class, () -> () -> "test-token")
                .withUserConfiguration(LocalGuiWebServerCustomizer.class);
        runner.run(context -> assertThat(context).doesNotHaveBean(LocalGuiWebServerCustomizer.class));
        runner.withPropertyValues("pixivdownload.headless=true")
                .run(context -> assertThat(context).doesNotHaveBean(LocalGuiWebServerCustomizer.class));
        runner.withPropertyValues("pixivdownload.headless=false")
                .run(context -> assertThat(context).hasSingleBean(LocalGuiWebServerCustomizer.class));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    @DisplayName("HTTP、HTTPS 及重定向下本机鉴权、代理隔离与重启换代保持一致")
    void localChannelIsIndependent(int mode) throws Exception {
        String oldToken = null;
        for (int generation = 0; generation < 2; generation++) {
            var tokens = new GuiTokenService();
            tokens.init();
            var local = new LocalGuiWebServerCustomizer(tokens);
            assertThatThrownBy(local::connection).isInstanceOf(IllegalStateException.class);
            var factory = new TomcatServletWebServerFactory(0);
            factory.setAddress(InetAddress.getByName("127.0.0.1"));
            factory.setBaseDirectory(temp.resolve("tomcat-" + mode + "-" + generation).toFile());
            var ssl = new SslConfig();
            ssl.setDomain("unreachable.invalid");
            ssl.setType("jks");
            ssl.setHttpRedirect(mode == 2);
            ssl.setHttpRedirectPort(0);
            var environment = new MockEnvironment().withProperty("server.port", "0")
                    .withProperty("server.ssl.enabled", Boolean.toString(mode > 0))
                    .withProperty("server.ssl.key-store", keyStoreFile.toString())
                    .withProperty("server.ssl.key-store-type", "PKCS12")
                    .withProperty("server.ssl.key-store-password", "test-only-password");
            new HttpsWebServerCustomizer(ssl, environment, TestI18nBeans.appMessages()).customize(factory);
            local.customize(factory);
            var routes = new RouteAccessRegistry(new PluginRegistry(List.of()));
            routes.register("core", new CorePlugin().routes());
            var locale = mock(AppLocaleResolver.class);
            when(locale.resolveLocale(any())).thenReturn(Locale.ENGLISH);
            var auth = new AuthFilter(mock(SetupService.class), mock(StaticResourceRateLimitService.class),
                    mock(RateLimitService.class), locale, TestI18nBeans.appMessages(),
                    new StaticListableBeanFactory().getBeanProvider(MaintenanceCoordinator.class),
                    mock(GuestInviteService.class), tokens, routes);
            TomcatWebServer server = (TomcatWebServer) factory.getWebServer(context -> {
                context.addFilter("forwarded", new TrustedForwardedRequestFilter("127.0.0.1/32"))
                        .addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), false, "/*");
                context.addFilter("auth", auth)
                        .addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), true, "/*");
                context.addFilter("csrf", new CsrfProtectionFilter(locale, TestI18nBeans.appMessages(), routes))
                        .addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), true, "/*");
                context.addServlet("probe", new HttpServlet() {
                    @Override protected void service(HttpServletRequest request, HttpServletResponse response)
                            throws IOException {
                        response.setContentType("application/json;charset=UTF-8");
                        response.getWriter().write("{\"ok\":true}");
                    }
                }).addMapping("/");
            });
            try {
                server.start();
                var connection = local.connection();
                assertThat(connection.port()).isPositive().isNotEqualTo(server.getPort());
                assertThat(connection.toString()).doesNotContain(tokens.getToken());
                var bound = factory.getAdditionalTomcatConnectors().stream()
                        .filter(connector -> connector.getLocalPort() == connection.port()).findFirst().orElseThrow();
                assertThat(((Http11NioProtocol) bound.getProtocolHandler()).getAddress().getHostAddress())
                        .isEqualTo("127.0.0.1");
                String internal = "http://127.0.0.1:" + connection.port();
                assertThat(request(internal, "/api/gui/status", tokens.getToken()).statusCode()).isEqualTo(200);
                assertThat(request(internal, "/%61pi/gui/status", tokens.getToken()).statusCode()).isEqualTo(200);
                assertThat(request(internal, "/api/gui/status", "").statusCode()).isEqualTo(403);
                assertThat(request(internal, "/api/gui/status", "wrong").statusCode()).isEqualTo(403);
                if (oldToken != null) {
                    assertThat(request(internal, "/api/gui/status", oldToken).statusCode()).isEqualTo(403);
                }
                for (String path : List.of("/", "/setup.html", "/api/auth/check", "/actuator/health")) {
                    assertThat(request(internal, path, tokens.getToken()).statusCode()).as(path).isEqualTo(404);
                }
                for (String header : List.of("Forwarded", "X-Forwarded-For", "X-Forwarded-Extra", "X-Real-IP")) {
                    assertThat(request(internal, "/api/gui/status", tokens.getToken(), header, "").statusCode())
                            .as(header).isEqualTo(400);
                }
                assertThat(request(internal, "/api/gui/status", tokens.getToken(), "Origin", "https://evil.invalid")
                        .statusCode()).isEqualTo(403);
                var post = HttpRequest.newBuilder(URI.create(internal + "/api/gui/setup/init"))
                        .header(GuiTokenProvider.HEADER_NAME, tokens.getToken())
                        .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
                assertThat(client.send(post, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);

                String primary = (mode > 0 ? "https" : "http") + "://localhost:" + server.getPort();
                assertThat(request(primary, "/api/gui/status", tokens.getToken()).statusCode()).isEqualTo(400);
                assertThat(request(primary, "/api/gui/status", tokens.getToken(), "Forwarded",
                        "for=203.0.113.10;proto=https;host=public.invalid").statusCode()).isEqualTo(403);
                var address = new ServerAddressProvider(environment, ssl);
                address.onWebServerInitialized(new ServletWebServerInitializedEvent(server,
                        new ServletWebServerApplicationContext()));
                assertThat(address.uri("/setup.html").toString()).isEqualTo(
                        (mode > 0 ? "https" : "http") + "://unreachable.invalid:" + server.getPort() + "/setup.html");
                if (mode == 2) {
                    int redirectPort = factory.getAdditionalTomcatConnectors().get(0).getLocalPort();
                    var redirect = request("http://localhost:" + redirectPort, "/setup.html?a=%E4%B8%AD", "");
                    assertThat(redirect.statusCode()).isBetween(300, 399);
                    assertThat(redirect.headers().firstValue("location")).contains(
                            primary + "/setup.html?a=%E4%B8%AD");
                }
                oldToken = tokens.getToken();
            } finally {
                server.stop();
                server.destroy();
            }
            assertThatThrownBy(local::connection).isInstanceOf(IllegalStateException.class);
        }
    }

    private static HttpResponse<String> request(String base, String path, String token, String... headers)
            throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(5));
        if (!token.isEmpty()) builder.header(GuiTokenProvider.HEADER_NAME, token);
        if (headers.length > 0) builder.headers(headers);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
