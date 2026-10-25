package top.sywyar.pixivdownload.plugin.web.resource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.accept.ContentNegotiationManager;
import org.springframework.web.context.support.StaticWebApplicationContext;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;
import org.springframework.web.util.ServletRequestPathUtils;
import top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution;
import top.sywyar.pixivdownload.plugin.registry.web.StaticResourceRegistry;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("动态静态资源映射快照刷新")
class DynamicStaticResourceHandlerMappingTest {

    @Test
    @DisplayName("锁外旧快照等待刷新锁后会在锁内重读并只构建最新快照")
    void staleOutsideReadCannotOverwriteLatestSnapshot() throws Exception {
        StaticResourceRegistry registry = mock(StaticResourceRegistry.class);
        StaticResourceRegistry.RegisteredStaticResource latest =
                mock(StaticResourceRegistry.RegisteredStaticResource.class);
        when(latest.pluginId()).thenReturn("latest");
        when(latest.contribution()).thenReturn(new StaticResourceContribution(
                "classpath:/static/", "/latest/"));
        when(latest.location()).thenReturn(new ClassPathResource("static/"));
        List<StaticResourceRegistry.RegisteredStaticResource> stale = List.of();
        List<StaticResourceRegistry.RegisteredStaticResource> current = List.of(latest);
        when(registry.resources()).thenReturn(stale, current);

        try (StaticWebApplicationContext context = new StaticWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.refresh();
            DynamicStaticResourceHandlerMapping mapping = new DynamicStaticResourceHandlerMapping(
                    registry, new ContentNegotiationManager(), new StaticResourceVersioning(registry));
            mapping.setApplicationContext(context);
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/latest/example.js");
            ServletRequestPathUtils.parseAndCache(request);

            var resolved = mapping.getHandler(request);

            assertThat(resolved).isNotNull();
            assertThat(resolved.getHandler()).isInstanceOfSatisfying(ResourceHttpRequestHandler.class,
                    handler -> assertThat(handler.getLocations()).containsExactly(new ClassPathResource("static/")));
        }
    }
}
