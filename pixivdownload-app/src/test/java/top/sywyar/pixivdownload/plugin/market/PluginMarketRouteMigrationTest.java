package top.sywyar.pixivdownload.plugin.market;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.plugin.RouteAccessRegistry;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.HttpMethod;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 旧受信 catalog API（{@code /api/plugins/catalog/**}）迁移边界守卫：受信目录后端已收口为独立内置插件 {@code plugin-market}
 * 的 {@code /api/plugin-market/**}，旧路径在未发布周期内<b>破坏性迁移</b>——其 controller 已删除、不再有任何 handler 映射
 * （旧 URL 因无 handler 而 404）；新路径有 handler 且由 plugin-market 以 {@code ADMIN} 声明覆盖。<b>不保留兼容桥</b>，旧 API
 * 职责不扩大。
 */
@SpringBootTest(properties = {
        "pixivdownload.config-dir=target/test-runtime/config",
        "pixivdownload.state-dir=target/test-runtime/state",
        "pixivdownload.data-dir=target/test-runtime/data",
        "setup.browser.auto-open=false"
})
@DisplayName("旧 /api/plugins/catalog 迁移为 /api/plugin-market 边界")
class PluginMarketRouteMigrationTest {

    static {
        System.setProperty(RuntimeFiles.CONFIG_DIR_PROPERTY, "target/test-runtime/config");
        System.setProperty(RuntimeFiles.STATE_DIR_PROPERTY, "target/test-runtime/state");
        System.setProperty(RuntimeFiles.DATA_DIR_PROPERTY, "target/test-runtime/data");
    }

    @AfterAll
    static void tearDownRuntimeDirs() {
        System.clearProperty(RuntimeFiles.CONFIG_DIR_PROPERTY);
        System.clearProperty(RuntimeFiles.STATE_DIR_PROPERTY);
        System.clearProperty(RuntimeFiles.DATA_DIR_PROPERTY);
    }

    @Autowired
    private RequestMappingHandlerMapping requestMappingHandlerMapping;
    @Autowired
    private RouteAccessRegistry routeAccessRegistry;

    private Set<String> mappedPatterns() {
        return requestMappingHandlerMapping.getHandlerMethods().keySet().stream()
                .filter(info -> info.getPathPatternsCondition() != null)
                .flatMap(info -> info.getPathPatternsCondition().getPatternValues().stream())
                .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("旧 /api/plugins/catalog/** 已无任何 controller 映射（迁移、不保留兼容桥）")
    void legacyCatalogMappingsRemoved() {
        assertThat(mappedPatterns())
                .as("旧受信 catalog 端点已迁移为 /api/plugin-market/**，不应再有任何 /api/plugins/catalog 映射")
                .noneMatch(pattern -> pattern.startsWith("/api/plugins/catalog"));
    }

    @Test
    @DisplayName("新 /api/plugin-market/** 端点有 handler 映射（仓库 / catalog / 详情 / 安装）")
    void marketMappingsPresent() {
        Set<String> patterns = mappedPatterns();
        assertThat(patterns).contains(
                "/api/plugin-market/repositories",
                "/api/plugin-market/catalog",
                "/api/plugin-market/plugins/{repositoryId}/{pluginId}",
                "/api/plugin-market/{repositoryId}/{pluginId}/{version}/install");
    }

    @Test
    @DisplayName("新 /api/plugin-market/** 由 plugin-market 以 ADMIN 声明，GET / POST 均被覆盖")
    void marketRoutesDeclaredAdmin() {
        assertThat(routeAccessRegistry.isDeclared("/api/plugin-market/repositories", HttpMethod.GET)).isTrue();
        assertThat(routeAccessRegistry.isDeclared("/api/plugin-market/official/demo/1.0.0/install", HttpMethod.POST)).isTrue();
        assertThat(routeAccessRegistry.routes())
                .anySatisfy(registered -> {
                    if (registered.route().pathPattern().equals("/api/plugin-market/**")) {
                        assertThat(registered.pluginId()).isEqualTo("plugin-market");
                        assertThat(registered.route().accessPolicy()).isEqualTo(AccessPolicy.ADMIN);
                    }
                });
    }
}
