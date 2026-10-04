package top.sywyar.pixivdownload.download.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServer;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxClient;
import top.sywyar.pixivdownload.core.pixiv.PixivProxyAccessDecision;
import top.sywyar.pixivdownload.core.pixiv.PixivProxyAccessOutcome;
import top.sywyar.pixivdownload.core.pixiv.PixivProxyAccessPolicy;
import top.sywyar.pixivdownload.core.pixiv.thumbnail.PixivThumbnailFetcher;
import top.sywyar.pixivdownload.core.work.model.WorkRestriction;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.model.WorkVisibilityScope;
import top.sywyar.pixivdownload.core.work.service.WorkVisibilityService;
import top.sywyar.pixivdownload.download.PixivFetchService;
import top.sywyar.pixivdownload.download.testsupport.WorkbenchTestMessages;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentityResolver;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("真实 Servlet 作品代理输入与邀请可见性")
class PixivProxyServletTest {
    @TempDir
    static Path tempDir;
    private static final WorkRestriction RESTRICTION =
            new WorkRestriction(Set.of(0), false, List.of(10L), false, List.of(20L));
    private static final WorkVisibilityScope SCOPE = WorkVisibilityScope.restricted(RESTRICTION, RESTRICTION);
    private final PixivAjaxClient upstream = mock(PixivAjaxClient.class);
    private final WorkVisibilityService visibility = mock(WorkVisibilityService.class);
    private final HttpClient client = HttpClient.newHttpClient();
    private WebServer server;
    private AnnotationConfigWebApplicationContext context;

    @BeforeAll
    void start() throws Exception {
        var mapper = new ObjectMapper();
        var access = mock(PixivProxyAccessPolicy.class);
        when(access.evaluate(any(), anyBoolean())).thenReturn(
                new PixivProxyAccessDecision(PixivProxyAccessOutcome.ALLOWED, null, 0, 0));
        when(upstream.get(any(), nullable(String.class))).thenReturn("{\"error\":false,\"body\":{}}");
        doAnswer(call -> {
            if (call.<Long>getArgument(2) != 12345L) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN);
            }
            return null;
        }).when(visibility).requireVisible(eq(SCOPE), eq(WorkType.ARTWORK), anyLong());
        var controller = new PixivProxyController(mapper, mock(PixivThumbnailFetcher.class),
                new PixivFetchService(upstream, mapper), access, mock(RequestOwnerIdentityResolver.class),
                visibility, WorkbenchTestMessages.messages());
        context = new AnnotationConfigWebApplicationContext();
        context.register(WebConfig.class);
        context.addBeanFactoryPostProcessor(factory -> factory.registerSingleton("proxy", controller));
        var factory = new TomcatServletWebServerFactory(0);
        factory.setAddress(InetAddress.getByName("127.0.0.1"));
        factory.setBaseDirectory(tempDir.resolve("tomcat").toFile());
        server = factory.getWebServer(servletContext -> {
            context.setServletContext(servletContext);
            context.refresh();
            var servlet = servletContext.addServlet("mvc", new DispatcherServlet(context));
            servlet.setLoadOnStartup(1);
            servlet.addMapping("/");
        });
        server.start();
    }

    @AfterAll
    void stop() {
        if (server != null) server.stop();
        if (context != null) context.close();
    }

    @Test
    @DisplayName("非法及不可见作品在真实路径解码后均不触发上游")
    void rejectsInvalidAndInvisibleIdsBeforeUpstream() throws Exception {
        for (String endpoint : List.of("meta", "pages", "ugoira")) {
            for (String rawId : List.of("54321%3Flang=en", "54321%253Flang=en", "no-id",
                    "-1", "0", "9223372036854775808", "9".repeat(100), "%20", "%2012345",
                    "12345%20", "+12345", "%EF%BC%91%EF%BC%92", "54321%23fragment")) {
                clearInvocations(upstream, visibility);
                assertThat(request(rawId, endpoint).statusCode()).as(endpoint + " " + rawId).isEqualTo(400);
                verifyNoInteractions(upstream, visibility);
            }
            clearInvocations(upstream, visibility);
            assertThat(request("54321", endpoint).statusCode()).isEqualTo(403);
            verify(visibility).requireVisible(SCOPE, WorkType.ARTWORK, 54321L);
            verifyNoInteractions(upstream);
        }
    }

    @Test
    @DisplayName("可见作品的校验标识与上游路径使用同一规范十进制值")
    void visibleIdsUseTheValidatedCanonicalValue() throws Exception {
        for (String endpoint : List.of("meta", "pages", "ugoira")) {
            clearInvocations(upstream, visibility);
            assertThat(request("00012345", endpoint).statusCode()).isEqualTo(200);
            verify(visibility).requireVisible(SCOPE, WorkType.ARTWORK, 12345L);
            String suffix = switch (endpoint) {
                case "pages" -> "/pages";
                case "ugoira" -> "/ugoira_meta";
                default -> "";
            };
            verify(upstream).get(URI.create("https://www.pixiv.net/ajax/illust/12345" + suffix), null);
        }
    }

    private HttpResponse<String> request(String rawId, String endpoint) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getPort()
                        + "/api/pixiv/artwork/" + rawId + "/" + endpoint))
                .timeout(Duration.ofSeconds(10)).header("Accept-Language", "en").build();
        return client.send(request, HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    static class WebConfig implements WebMvcConfigurer {
        @Override
        public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
            // 邀请身份已由宿主鉴权；本夹具只提供受限作用域，验证真实 MVC 到上游的完整边界。
            resolvers.add(new HandlerMethodArgumentResolver() {
                @Override
                public boolean supportsParameter(MethodParameter parameter) {
                    return parameter.getParameterType() == WorkVisibilityScope.class;
                }

                @Override
                public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
                                              NativeWebRequest request, WebDataBinderFactory binder) {
                    return SCOPE;
                }
            });
        }
    }
}
