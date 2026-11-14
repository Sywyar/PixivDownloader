package top.sywyar.pixivdownload.setup;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import top.sywyar.pixivdownload.common.GuiTokenProvider;
import top.sywyar.pixivdownload.i18n.AppLocaleResolver;
import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.plugin.CorePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivPluginProvider;
import top.sywyar.pixivdownload.plugin.api.web.ProgramIdentity;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.route.RouteAccessRegistry;
import top.sywyar.pixivdownload.plugin.registry.route.StartupRouteRegistry;
import top.sywyar.pixivdownload.plugin.registry.web.LandingRegistry;
import top.sywyar.pixivdownload.plugin.registry.web.NavigationRegistry;
import top.sywyar.pixivdownload.quota.RateLimitService;
import top.sywyar.pixivdownload.setup.guest.GuestInviteService;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("真实业务路由的程序授权经过宿主过滤器")
class BusinessProgramAuthorizationTest {
    @Test
    @DisplayName("业务 owner 显式权限可调用，空授权不能借管理员或宽路由执行")
    void checksDeclaredBusinessRoutes() throws Exception {
        var modules = List.of("ai", "mail", "push", "tts", "gallery", "gallery-tools", "novel", "notification", "download-workbench", "translation", "page-editor");
        var urls = new ArrayList<URL>();
        for (String module : modules) {
            var directory = Path.of(Objects.requireNonNull(System.getProperty(module + ".plugin.classes")));
            assertThat(directory).isDirectory();
            urls.add(directory.toUri().toURL());
        }
        urls.add(Path.of(System.getProperty("novel-translation-contract.plugin.classes")).toUri().toURL());
        urls.add(Path.of(System.getProperty("novel-translation-api.classes")).toUri().toURL());
        try (var loader = new URLClassLoader(urls.toArray(URL[]::new), getClass().getClassLoader())) {
            var features = new ArrayList<PixivFeaturePlugin>();
            features.add(new CorePlugin());
            features.add(new top.sywyar.pixivdownload.plugin.market.PluginMarketPlugin());
            for (String module : modules) {
                var descriptor = new Properties();
                try (var reader = Files.newBufferedReader(Path.of(System.getProperty(module + ".plugin.classes"),
                        "plugin.properties"), StandardCharsets.UTF_8)) {
                    descriptor.load(reader);
                }
                var provider = (PixivPluginProvider) loader.loadClass(descriptor.getProperty("plugin.class"))
                        .getConstructor().newInstance();
                features.add(provider.featurePlugin());
            }
            var plugins = new PluginRegistry(features);
            var routes = new RouteAccessRegistry(plugins);
            var roots = new ArrayList<Path>();
            roots.add(Path.of(CorePlugin.class.getProtectionDomain().getCodeSource().getLocation().toURI()));
            for (String module : modules) roots.add(Path.of(System.getProperty(module + ".plugin.classes")));
            checkControllerCoverage(roots, loader, routes);
            var keys = mock(ProgramKeyService.class);
            var setup = mock(SetupService.class);
            when(setup.isAdminLoggedIn(any())).thenReturn(true);
            var messages = mock(AppMessages.class);
            when(messages.getOrDefault(nullable(Locale.class), anyString(), anyString())).thenAnswer(call -> call.getArgument(2));
            var filter = new AuthFilter(setup, mock(StaticResourceRateLimitService.class), mock(RateLimitService.class),
                    mock(AppLocaleResolver.class), messages, mock(ObjectProvider.class), mock(GuestInviteService.class),
                    mock(GuiTokenProvider.class), routes, new StartupRouteRegistry(plugins), new LandingRegistry(plugins),
                    new NavigationRegistry(plugins), keys);
            var checked = new HashSet<String>();
            for (var registered : routes.routes()) {
                for (var grant : registered.route().programPermissions().entrySet()) {
                    String permission = registered.pluginId() + ":" + grant.getValue().id();
                    String path = registered.route().pathPattern().replace("*", "123");
                    for (boolean authorized : List.of(false, true)) {
                        when(keys.authenticate("fixture", "192.0.2.1")).thenReturn(new ProgramIdentity(UUID.randomUUID(),
                                authorized ? Set.of(permission) : Set.of()));
                        var request = new MockHttpServletRequest(grant.getKey().name(), path);
                        request.setRemoteAddr("192.0.2.1");
                        request.addHeader("Authorization", "Bearer fixture");
                        var response = new MockHttpServletResponse();
                        var called = new AtomicBoolean();
                        filter.doFilter(request, response, (req, res) -> called.set(true));
                        assertThat(response.getStatus()).as("%s %s %s authorized=%s", permission, grant.getKey(), path, authorized)
                                .isEqualTo(authorized ? 200 : 403);
                        assertThat(called.get()).isEqualTo(authorized);
                    }
                    checked.add(registered.pluginId());
                }
            }
            assertThat(checked).containsAll(modules).contains("core");
        }
    }

    private static void checkControllerCoverage(List<Path> roots, ClassLoader loader, RouteAccessRegistry routes) throws Exception {
        var uncovered = new TreeSet<String>();
        for (Path root : roots) {
            try (var files = Files.walk(root)) {
                for (Path file : files.filter(path -> path.toString().endsWith("Controller.class")).toList()) {
                    String name = root.relativize(file).toString().replace('\\', '.').replace('/', '.').replaceAll("\\.class$", "");
                    Class<?> type = Class.forName(name, false, loader);
                    if (!org.springframework.core.annotation.AnnotatedElementUtils.hasAnnotation(type, org.springframework.stereotype.Controller.class)) continue;
                    var base = org.springframework.core.annotation.AnnotatedElementUtils.findMergedAnnotation(type, org.springframework.web.bind.annotation.RequestMapping.class);
                    String[] bases = base == null || base.path().length == 0 ? new String[]{""} : base.path();
                    for (var method : type.getDeclaredMethods()) {
                        var mapping = org.springframework.core.annotation.AnnotatedElementUtils.findMergedAnnotation(method, org.springframework.web.bind.annotation.RequestMapping.class);
                        if (mapping == null) continue;
                        String[] paths = mapping.path().length == 0 ? new String[]{""} : mapping.path();
                        var verbs = mapping.method().length == 0 ? new org.springframework.web.bind.annotation.RequestMethod[]{org.springframework.web.bind.annotation.RequestMethod.GET, org.springframework.web.bind.annotation.RequestMethod.POST} : mapping.method();
                        for (String prefix : bases) for (String suffix : paths) for (var verb : verbs) {
                            String path = (prefix + "/" + suffix).replaceAll("/+", "/").replaceAll("/$", "");
                            if (!path.startsWith("/api/") || browserOnly(path)) continue;
                            String concrete = path.replaceAll("\\{[^}]+}", "123");
                            var httpMethod = top.sywyar.pixivdownload.plugin.api.web.HttpMethod.valueOf(verb.name());
                            var route = routes.resolve(concrete, httpMethod).orElse(null);
                            if (route != null && (route.route().accessPolicy() == top.sywyar.pixivdownload.plugin.api.web.AccessPolicy.GUI
                                    || route.route().accessPolicy() == top.sywyar.pixivdownload.plugin.api.web.AccessPolicy.LOCAL)) continue;
                            if (route == null || !route.route().programPermissions().containsKey(httpMethod)) uncovered.add(verb + " " + path);
                        }
                    }
                }
            }
        }
        assertThat(uncovered).as("新增业务必须声明程序权限或明确其专用流程 / 等价入口").isEmpty();
    }

    private static boolean browserOnly(String path) {
        // 身份建立、浏览器装配与兼容入口；业务能力经规范任务、库和页面编辑 API 提供。
        return path.startsWith("/api/auth/") || path.startsWith("/api/setup/")
                || path.startsWith("/api/security/program-keys") || path.startsWith("/api/admin/invites")
                || path.startsWith("/api/sse/") || path.startsWith("/api/captcha/")
                || path.startsWith("/api/layout-feedback/") || path.startsWith("/api/quota/")
                || path.startsWith("/api/web/page-publications/") || path.startsWith("/api/web/page-slots/")
                || path.equals("/api/web/ui-slots")
                || Set.of("/api/navigation", "/api/drilldowns", "/api/page-sections", "/api/admin/system/password",
                        "/api/admin/system/logout-all", "/api/onboarding/steps/{stepId}/complete",
                        "/api/download/pixiv", "/api/novel/download", "/api/download/pixiv/novel",
                        "/api/download/novel/status/{novelId}", "/api/download/novel/translate-status/{novelId}",
                        "/api/download/status/{artworkId}", "/api/download/status/active",
                        "/api/cancel/{artworkId}", "/api/download/cancel/{artworkId}",
                        "/api/plugins/{id}/actions/{verb}").contains(path);
    }
}
