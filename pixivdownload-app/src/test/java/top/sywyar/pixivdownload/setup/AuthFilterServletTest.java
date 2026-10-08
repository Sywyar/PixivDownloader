package top.sywyar.pixivdownload.setup;

import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServer;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import top.sywyar.pixivdownload.common.GuiTokenProvider;
import top.sywyar.pixivdownload.core.asset.WorkAssetFileController;
import top.sywyar.pixivdownload.core.work.model.WorkAssetFile;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.model.WorkVisibilityScope;
import top.sywyar.pixivdownload.core.work.service.WorkAssetService;
import top.sywyar.pixivdownload.core.work.service.WorkVisibilityService;
import top.sywyar.pixivdownload.i18n.AppLocaleResolver;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.maintenance.MaintenanceCoordinator;
import top.sywyar.pixivdownload.plugin.CorePlugin;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.route.RouteAccessRegistry;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.HttpMethod;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;
import top.sywyar.pixivdownload.quota.RateLimitService;
import top.sywyar.pixivdownload.setup.guest.GuestAccessGuard;
import top.sywyar.pixivdownload.setup.guest.GuestInviteService;
import top.sywyar.pixivdownload.setup.guest.GuestInviteSession;
import top.sywyar.pixivdownload.setup.guest.GuestWorkVisibilityScopeFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("真实 Servlet 路由与鉴权一致性")
class AuthFilterServletTest {
    @TempDir
    static Path tempDir;

    private final SetupService setup = mock(SetupService.class);
    private final RouteAccessRegistry routes = new RouteAccessRegistry(new PluginRegistry(List.of()));
    private final HttpClient client = HttpClient.newHttpClient();
    private AnnotationConfigWebApplicationContext context;
    private WebServer server;
    private byte[] image;
    private final PolicyController policyController = new PolicyController();

    @BeforeAll
    void startServer() throws Exception {
        when(setup.isSetupComplete()).thenReturn(true);
        when(setup.isValidSession("admin")).thenReturn(true);
        AppLocaleResolver locale = mock(AppLocaleResolver.class);
        when(locale.resolveLocale(any())).thenReturn(Locale.ENGLISH);
        var staticLimits = mock(StaticResourceRateLimitService.class);
        when(staticLimits.isAllowed(anyString())).thenReturn(true);
        var limits = mock(RateLimitService.class);
        when(limits.isAllowed(anyString())).thenReturn(true);
        when(limits.isAllowedForInvite(anyString())).thenReturn(true);
        var invites = mock(GuestInviteService.class);
        when(invites.resolveByCode("invited")).thenReturn(Optional.of(new GuestInviteSession(
                1L, "invited", true, false, false, true, Set.of(), true, Set.of(),
                true, Set.of(), true, Set.of())));
        routes.register("core", new CorePlugin().routes());
        AuthFilter auth = new AuthFilter(setup, staticLimits, limits, locale, TestI18nBeans.appMessages(),
                new StaticListableBeanFactory().getBeanProvider(MaintenanceCoordinator.class),
                invites, mock(GuiTokenProvider.class), routes);

        Path file = tempDir.resolve("pixel.png");
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", file.toFile());
        image = Files.readAllBytes(file);
        var assets = mock(WorkAssetService.class);
        when(assets.rawFile(eq(WorkType.ARTWORK), any(Long.class), eq(0)))
                .thenReturn(Optional.of(new WorkAssetFile(0, file, "png")));
        when(assets.thumbnail(eq(WorkType.ARTWORK), any(Long.class), eq(0), eq(512)))
                .thenReturn(Optional.of(new WorkAssetFile(0, file, "png")));
        var visibility = mock(WorkVisibilityService.class);
        doAnswer(call -> {
            WorkVisibilityScope scope = call.getArgument(0);
            long id = call.getArgument(2);
            if (scope.enforceVisibility() && id != 12345L) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN);
            }
            return null;
        }).when(visibility).requireVisible(any(), eq(WorkType.ARTWORK), any(Long.class));
        var controller = new WorkAssetFileController(assets,
                new GuestAccessGuard(new GuestWorkVisibilityScopeFactory(), visibility));

        context = new AnnotationConfigWebApplicationContext();
        context.register(WebConfig.class);
        context.addBeanFactoryPostProcessor(factory -> factory.registerSingleton("assets", controller));
        context.addBeanFactoryPostProcessor(factory -> factory.registerSingleton("policy", policyController));
        var factory = new TomcatServletWebServerFactory(0);
        factory.setAddress(InetAddress.getByName("127.0.0.1"));
        factory.setContextPath("/test");
        factory.setBaseDirectory(tempDir.resolve("tomcat").toFile());
        server = factory.getWebServer(servletContext -> {
            context.setServletContext(servletContext);
            context.refresh();
            var servlet = servletContext.addServlet("mvc", new DispatcherServlet(context));
            servlet.setLoadOnStartup(1);
            servlet.addMapping("/");
            // 用生产反向代理过滤器建立远端身份，真实请求不能命中回环 LOCAL 特例。
            servletContext.addFilter("forwarded", new TrustedForwardedRequestFilter("127.0.0.1/32"))
                    .addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), false, "/*");
            servletContext.addFilter("auth", auth)
                    .addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), true, "/*");
        });
        server.start();
    }

    @AfterAll
    void stopServer() {
        if (server != null) server.stop();
        if (context != null) context.close();
    }

    @Test
    @DisplayName("普通与编码资产路径都执行远端身份和单作品可见性检查")
    void assetPathsPreserveAuthorization() throws Exception {
        for (String mode : List.of("multi", "solo")) {
            when(setup.getMode()).thenReturn(mode);
            for (String endpoint : List.of("rawfile", "image", "thumbnail", "thumbnail-file")) {
                String base = "/api/downloaded/" + endpoint + "/";
                String encoded = "/api/downloaded/%" + Integer.toHexString(endpoint.charAt(0))
                        + endpoint.substring(1) + "/";
                for (String path : List.of(base, encoded, base.replace("/api/", "/%61pi/"),
                        base.replace("/downloaded/", "/%64ownloaded/"))) {
                    assertThat(request(path + "12345/0", "").statusCode()).as(mode + " " + path).isEqualTo(401);
                    assertThat(request(path + "12345/0", "pixiv_session=admin").body()).isEqualTo(image);
                    for (String credentials : List.of("pixiv_invite_token=invited",
                            "pixiv_invite_token=invited; pixiv_session=admin")) {
                        assertThat(request(path + "12345/0", credentials).body()).isEqualTo(image);
                        assertThat(request(path + "54321/0", credentials).statusCode()).isEqualTo(403);
                    }
                }
            }
        }
    }

    private HttpResponse<byte[]> request(String path, String cookie) throws Exception {
        return request("GET", path, cookie, false);
    }

    @Test
    @DisplayName("真实请求按方法与最具体声明鉴权，路由撤回后不保留放行")
    void effectivePolicyFollowsMethodSpecificityAndLifecycle() throws Exception {
        List<WebRouteContribution> declarations = List.of(
                new WebRouteContribution("/api/policy/method", AccessPolicy.PUBLIC, Set.of(HttpMethod.GET), false),
                new WebRouteContribution("/api/policy/method", AccessPolicy.ADMIN, Set.of(HttpMethod.POST), false),
                new WebRouteContribution("/api/policy/local-method", AccessPolicy.LOCAL, Set.of(HttpMethod.GET), false),
                WebRouteContribution.publicRoute("/api/policy/open/**"),
                WebRouteContribution.admin("/api/policy/open/admin"),
                WebRouteContribution.visitor("/api/policy/open/visitor"),
                WebRouteContribution.local("/api/policy/local/**"),
                WebRouteContribution.admin("/api/policy/local/admin"),
                WebRouteContribution.invitedGuest("/api/policy/guest/**"),
                WebRouteContribution.admin("/api/policy/guest/admin"),
                WebRouteContribution.admin("/api/policy/admin/**"),
                WebRouteContribution.publicRoute("/api/policy/admin/open"));
        for (String mode : List.of("solo", "multi")) {
            when(setup.getMode()).thenReturn(mode);
            try {
                routes.register("policy", declarations);
                expectPolicy("GET", "/method", "", false, 200);
                expectPolicy("POST", "/method", "", false, 401);
                expectPolicy("POST", "/method", "pixiv_session=admin", false, 200);
                expectPolicy("PUT", "/method", "", false, 404);
                expectPolicy("GET", "/local-method", "", true, 200);
                expectPolicy("POST", "/local-method", "", true, 404);
                expectPolicy("GET", "/open/admin", "", false, 401);
                expectPolicy("GET", "/open/admin", "pixiv_invite_token=invited", false, 403);
                expectPolicy("GET", "/open/admin", "pixiv_invite_token=invited; pixiv_session=admin", false, 403);
                expectPolicy("POST", "/method", "pixiv_invite_token=invited; pixiv_session=admin", false, 403);
                expectPolicy("GET", "/open/admin", "pixiv_session=admin", false, 200);
                expectPolicy("GET", "/open/visitor", "", false, mode.equals("multi") ? 200 : 401);
                expectPolicy("GET", "/local/admin", "", true, 401);
                expectPolicy("GET", "/guest/admin", "pixiv_invite_token=invited", false, 403);
                expectPolicy("GET", "/admin/open", "", false, 200);
                routes.unregister("policy");
                expectPolicy("GET", "/method", "", false, 404);
                expectPolicy("GET", "/local-method", "", true, 404);
                routes.register("policy", declarations);
                expectPolicy("GET", "/method", "", false, 200);
            } finally {
                routes.unregister("policy");
            }
        }
    }

    private void expectPolicy(String method, String path, String cookie, boolean local, int status) throws Exception {
        int before = policyController.calls.get();
        assertThat(request(method, "/api/policy" + path, cookie, local).statusCode())
                .as(setup.getMode() + " " + method + " " + path).isEqualTo(status);
        assertThat(policyController.calls.get()).isEqualTo(before + (status == 200 ? 1 : 0));
    }

    private HttpResponse<byte[]> request(String method, String path, String cookie, boolean local) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getPort() + "/test" + path))
                .timeout(Duration.ofSeconds(10))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .header("Forwarded", "for=" + (local ? "\"[::1]\";proto=http;host=localhost"
                        : "192.0.2.10;proto=http;host=fixture.example"))
                .header("Accept-Language", "en");
        if (!cookie.isEmpty()) request.header("Cookie", cookie);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    @RestController
    static class PolicyController {
        private final AtomicInteger calls = new AtomicInteger();

        @RequestMapping("/api/policy/**")
        String invoke() {
            calls.incrementAndGet();
            return "ok";
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    static class WebConfig {
    }
}
