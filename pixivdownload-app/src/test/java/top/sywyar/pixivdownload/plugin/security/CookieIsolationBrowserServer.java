package top.sywyar.pixivdownload.plugin.security;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.json.JsonMapper;
import top.sywyar.pixivdownload.common.GuiTokenProvider;
import top.sywyar.pixivdownload.common.Utf8ConsoleStreams;
import top.sywyar.pixivdownload.common.UuidUtils;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.appconfig.MultiModeConfig;
import top.sywyar.pixivdownload.i18n.AppLocaleResolver;
import top.sywyar.pixivdownload.i18n.I18nController;
import top.sywyar.pixivdownload.i18n.LocaleCatalog;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.i18n.WebI18nBundleRegistry;
import top.sywyar.pixivdownload.i18n.WebI18nService;
import top.sywyar.pixivdownload.maintenance.MaintenanceCoordinator;
import top.sywyar.pixivdownload.plugin.CorePlugin;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.route.RouteAccessRegistry;
import top.sywyar.pixivdownload.plugin.registry.web.LandingRegistry;
import top.sywyar.pixivdownload.plugin.registry.web.PageContributionRegistry;
import top.sywyar.pixivdownload.plugin.registry.web.StaticResourceRegistry;
import top.sywyar.pixivdownload.plugin.web.page.PagePublicationRequest;
import top.sywyar.pixivdownload.plugin.web.resource.StaticResourceConfig;
import top.sywyar.pixivdownload.quota.RateLimitService;
import top.sywyar.pixivdownload.setup.AuthFilter;
import top.sywyar.pixivdownload.setup.CsrfProtectionFilter;
import top.sywyar.pixivdownload.setup.ProxySetupService;
import top.sywyar.pixivdownload.setup.SetupController;
import top.sywyar.pixivdownload.setup.SetupService;
import top.sywyar.pixivdownload.setup.StaticResourceRateLimitService;
import top.sywyar.pixivdownload.setup.TrustedForwardedRequestFilter;
import top.sywyar.pixivdownload.setup.guest.GuestInviteService;
import top.sywyar.pixivdownload.setup.guest.GuestInviteSession;
import top.sywyar.pixivdownload.setup.guest.controller.InviteRedeemController;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 独立 JVM 的浏览器夹具；使用生产 Cookie/鉴权链，邀请存储和挑战提供者为受控测试数据。 */
public final class CookieIsolationBrowserServer {
    public static void main(String[] args) throws Exception {
        Utf8ConsoleStreams.install();
        Path root = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(root);
        System.setProperty(RuntimeFiles.CONFIG_DIR_PROPERTY, root.resolve("config").toString());
        System.setProperty(RuntimeFiles.STATE_DIR_PROPERTY, root.resolve("state").toString());
        var config = new DownloadConfig(); config.setRootFolder(root.resolve("works").toString());
        var messages = TestI18nBeans.appMessages();
        var setup = new SetupService(config, JsonMapper.builderWithJackson2Defaults().build(), new DefaultApplicationArguments(), messages);
        if (!setup.isSetupComplete()) setup.init("cookie-fixture", "cookie-fixture-password", args.length > 2 ? args[2] : "solo");
        var catalog = LocaleCatalog.defaultCatalog();
        var locale = new AppLocaleResolver(catalog);
        var plugins = new PluginRegistry(List.of(new CorePlugin()));
        var routes = new RouteAccessRegistry(new PluginRegistry(List.of()));
        var declarations = new ArrayList<>(new CorePlugin().routes());
        declarations.addAll(List.of(WebRouteContribution.publicRoute("/cookie-fixture.html"),
                WebRouteContribution.publicRoute("/api/cookie-fixture/**"),
                WebRouteContribution.admin("/api/cookie-fixture/admin"),
                WebRouteContribution.invitedGuest("/api/cookie-fixture/guest"),
                WebRouteContribution.visitor("/api/cookie-fixture/visitor")));
        routes.register("core", declarations);
        var sessions = ConcurrentHashMap.<String>newKeySet();
        var invites = mock(GuestInviteService.class);
        var guest = new GuestInviteSession(1, "fixture", true, false, false, true, Set.of(), true, Set.of(), true, Set.of(), true, Set.of());
        when(invites.redeem(anyString())).thenAnswer(call -> {
            String token = "v1." + UUID.randomUUID(); sessions.add(token); return Optional.of(token);
        });
        when(invites.resolveSession(anyString())).thenAnswer(call -> sessions.contains(call.getArgument(0)) ? Optional.of(guest) : Optional.empty());
        doAnswer(call -> { sessions.remove(call.getArgument(0)); return null; }).when(invites).revokeSession(anyString());
        var staticLimits = mock(StaticResourceRateLimitService.class);
        when(staticLimits.isAllowed(anyString())).thenReturn(true); when(staticLimits.isAllowedForInvite(anyString())).thenReturn(true);
        var limits = mock(RateLimitService.class);
        when(limits.isAllowed(anyString())).thenReturn(true); when(limits.isAllowedForInvite(anyString())).thenReturn(true);
        var auth = new AuthFilter(setup, staticLimits, limits, locale, messages,
                new StaticListableBeanFactory().getBeanProvider(MaintenanceCoordinator.class), invites, mock(GuiTokenProvider.class), routes);
        var bundles = new WebI18nBundleRegistry(plugins);
        var context = new AnnotationConfigWebApplicationContext();
        context.register(WebConfig.class, StaticResourceConfig.class);
        context.addBeanFactoryPostProcessor(factory -> {
            factory.registerSingleton("localeResolver", locale);
            factory.registerSingleton("setup", new SetupController(setup, new MultiModeConfig(), mock(ProxySetupService.class), messages, invites));
            factory.registerSingleton("redeem", new InviteRedeemController(invites, new LandingRegistry(plugins)));
            factory.registerSingleton("i18n", new I18nController(bundles, new WebI18nService(bundles), catalog));
            factory.registerSingleton("fixture", new Fixture(locale, sessions));
            factory.registerSingleton("staticResources", new StaticResourceRegistry(plugins));
            factory.registerSingleton("pages", mock(PageContributionRegistry.class));
            factory.registerSingleton("pagePublications", mock(PagePublicationRequest.class));
        });
        var factory = new TomcatServletWebServerFactory(Integer.parseInt(args[1]));
        factory.setAddress(InetAddress.getByName("127.0.0.1"));
        factory.setBaseDirectory(root.resolve("tomcat").toFile());
        var server = factory.getWebServer(servletContext -> {
            context.setServletContext(servletContext); context.refresh();
            var servlet = servletContext.addServlet("mvc", new DispatcherServlet(context));
            servlet.setLoadOnStartup(1); servlet.addMapping("/");
            servletContext.addFilter("forwarded", new TrustedForwardedRequestFilter(""))
                    .addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), false, "/*");
            servletContext.addFilter("auth", auth).addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), true, "/*");
            servletContext.addFilter("csrf", new CsrfProtectionFilter(locale, messages, routes))
                    .addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), true, "/*");
        });
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { server.stop(); context.close(); }));
        server.start();
        Files.writeString(root.resolve("port.txt"), Integer.toString(server.getPort()), StandardCharsets.UTF_8);
        new CountDownLatch(1).await();
    }

    @Configuration @EnableWebMvc static class WebConfig { }

    @RestController
    static final class Fixture {
        private final HookChallenges hooks = new HookChallenges();
        private final AppLocaleResolver locale;
        private final Set<String> invites;
        Fixture(AppLocaleResolver locale, Set<String> invites) { this.locale = locale; this.invites = invites; }

        @GetMapping(value = "/cookie-fixture.html", produces = "text/html;charset=UTF-8")
        String page() {
            return """
                    <!doctype html><html><head><meta charset="UTF-8"><title>Cookie fixture</title>
                    <script src="/js/pixiv-i18n.js"></script><script src="/js/pixiv-lang-switcher.js"></script></head>
                    <body><span id="language"></span><script>
                    (async () => {
                        window.cookieClient = await PixivI18n.create({namespaces:['common']});
                        window.cookieSwitcher = await PixivLangSwitcher.mount({mountPoint:document.querySelector('#language'),
                            i18n:cookieClient, onChange:client => window.cookieClient = client});
                        window.cookieReady = true;
                    })();
                    </script></body></html>
                    """;
        }

        @GetMapping("/api/cookie-fixture/locale") Map<String, String> locale(HttpServletRequest request) {
            return Map.of("lang", locale.resolveLocale(request).toLanguageTag());
        }
        @GetMapping({"/api/cookie-fixture/admin", "/api/cookie-fixture/guest"}) Map<String, Boolean> access() { return Map.of("allowed", true); }
        @GetMapping("/api/cookie-fixture/visitor") Map<String, String> visitor(HttpServletRequest request) {
            return Map.of("uuid", UuidUtils.extractOrGenerateUuid(request));
        }
        @PostMapping("/api/cookie-fixture/invalidate") Map<String, Boolean> invalidate() { invites.clear(); return Map.of("ok", true); }

        private HookChallenges.Target target(HttpServletRequest request, HttpServletResponse response) {
            String subject = hooks.subject(request, response);
            return new HookChallenges.Target(subject, "scope-" + subject, "digest-" + subject);
        }
        @PostMapping("/api/cookie-fixture/prepare") Map<String, String> prepare(HttpServletRequest request, HttpServletResponse response) {
            var target = target(request, response); String token = UUID.randomUUID().toString();
            if (!hooks.retain(token, target.digest(), List.of())) throw new IllegalStateException("Fixture capacity exceeded");
            return Map.of("token", token);
        }
        @PostMapping("/api/cookie-fixture/stage") ResponseEntity<?> stage(@RequestParam String token,
                @RequestParam(defaultValue = "false") boolean nativeTicket, HttpServletRequest request, HttpServletResponse response) {
            var target = target(request, response);
            if (!hooks.stage(token, target.digest(), List.of(), Map.of("fixture", "proof"))) return ResponseEntity.status(409).build();
            if (nativeTicket) hooks.nativeTicket(request, response, token, target);
            return ResponseEntity.ok(Map.of("ok", true));
        }
        @PostMapping("/api/cookie-fixture/consume") ResponseEntity<?> consume(HttpServletRequest request, HttpServletResponse response) {
            return hooks.consume(request, response, target(request, response), List.of()).proof() == null
                    ? ResponseEntity.status(409).build() : ResponseEntity.ok(Map.of("ok", true));
        }
    }
}
