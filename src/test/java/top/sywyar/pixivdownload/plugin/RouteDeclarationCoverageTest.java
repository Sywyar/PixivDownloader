package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.plugin.api.web.HttpMethod;
import top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全 URL 声明守卫：每个真实 controller 映射 / 静态资源目录 / 顶层 HTML 都必须落在某条
 * {@link top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution}（带 {@code AccessPolicy}）里。
 * <p>
 * {@code AuthFilter} 对命中不了任何已声明路由的请求统一 404，因此「新增 / 遗漏一个 controller 映射或静态入口
 * 却没有对应的访问声明」必须让本测试失败——否则该 URL 在运行期会被 404、或回落到默认放行造成访问语义歧义。
 * 框架内部端点（如 Spring Boot 的 {@code /error}、actuator 的独立 handler mapping）不属业务路由、由内联分支
 * 或 ERROR dispatch 旁路处理，列入豁免集合。
 */
@SpringBootTest(properties = {
        "pixivdownload.config-dir=target/test-runtime/config",
        "pixivdownload.state-dir=target/test-runtime/state",
        "pixivdownload.data-dir=target/test-runtime/data",
        "setup.browser.auto-open=false"
})
@DisplayName("全 URL 声明守卫：controller / 静态资源 / 顶层 HTML 均已声明 AccessPolicy")
class RouteDeclarationCoverageTest {

    /** 框架内部端点：非业务路由，由 ERROR dispatch / 独立 handler 旁路 AuthFilter，不要求 route 声明。 */
    private static final Set<String> FRAMEWORK_EXEMPT = Set.of("/error");

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

    @Test
    @DisplayName("全部 @RestController / @Controller 映射 URL（含 HTTP 方法）都被某条 route 声明覆盖")
    void everyControllerMappingIsDeclared() {
        assertThat(requestMappingHandlerMapping.getHandlerMethods()).as("应能枚举到 controller 映射").isNotEmpty();

        List<String> undeclared = new ArrayList<>();
        requestMappingHandlerMapping.getHandlerMethods().keySet().stream()
                .filter(info -> info.getPathPatternsCondition() != null)
                .forEach(info -> {
                    Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
                    for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
                        if (FRAMEWORK_EXEMPT.contains(pattern)) {
                            continue;
                        }
                        String concrete = toConcretePath(pattern);
                        if (methods.isEmpty()) {
                            // 无方法限定（响应全部方法）：按项目约定以 GET 代表验证「至少有声明覆盖」。
                            if (!routeAccessRegistry.isDeclared(concrete, HttpMethod.GET)) {
                                undeclared.add("* " + pattern);
                            }
                        } else {
                            for (RequestMethod rm : methods) {
                                HttpMethod hm = toHttpMethod(rm);
                                if (hm != null && !routeAccessRegistry.isDeclared(concrete, hm)) {
                                    undeclared.add(rm + " " + pattern);
                                }
                            }
                        }
                    }
                });

        assertThat(undeclared)
                .as("以下 controller URL（方法）未在任何插件 routes() 以 AccessPolicy 声明 —— "
                        + "新增 controller 必须同步声明其路由访问策略（含 HTTP 方法），否则运行期请求会被 404：%s", undeclared)
                .isEmpty();
    }

    /** Spring {@link RequestMethod} → 本项目 {@link HttpMethod}；未建模的方法（如 TRACE）返回 null（跳过）。 */
    private static HttpMethod toHttpMethod(RequestMethod requestMethod) {
        try {
            return HttpMethod.valueOf(requestMethod.name());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Test
    @DisplayName("每个声明的静态资源目录都有对应的 route 访问声明覆盖")
    void everyStaticResourceDirIsDeclared() {
        List<String> undeclared = BuiltInPlugins.createAll().stream()
                .flatMap(plugin -> plugin.staticResources().stream())
                .map(StaticResourceContribution::publicPathPrefix)
                .distinct()
                .filter(prefix -> !routeAccessRegistry.isDeclared(prefix + "__coverage_probe__"))
                .toList();

        assertThat(undeclared)
                .as("以下 serving 静态目录前缀的访问未由任何 route 声明覆盖 —— "
                        + "静态目录的 serving 与访问必须同时声明：%s", undeclared)
                .isEmpty();
    }

    @Test
    @DisplayName("每个顶层静态 HTML 页面都被某条 route 声明覆盖")
    void everyTopLevelHtmlPageIsDeclared() throws IOException {
        Resource[] pages = new PathMatchingResourcePatternResolver()
                .getResources("classpath:/static/*.html");
        assertThat(pages).as("应能枚举到顶层静态 HTML 页面").isNotEmpty();

        List<String> undeclared = new java.util.ArrayList<>();
        for (Resource page : pages) {
            String name = page.getFilename();
            if (name == null) {
                continue;
            }
            String path = "/" + name;
            if (!routeAccessRegistry.isDeclared(path)) {
                undeclared.add(path);
            }
        }

        assertThat(undeclared)
                .as("以下顶层 HTML 页面未在任何插件 routes() 以 AccessPolicy 声明：%s", undeclared)
                .isEmpty();
    }

    @Test
    @DisplayName("内置插件清单与运行期注册一致（守卫使用运行期 registry 反映实际启用插件）")
    void runtimeRegistryReflectsBuiltInPlugins() {
        // 运行期 registry（@Autowired，反映实际注册）应至少覆盖内置插件清单等价快照的全部声明，
        // 否则本守卫的覆盖结论与运行期不一致。逐条核对内置快照的每条声明在运行期 registry 中也存在。
        RouteAccessRegistry builtIn = new RouteAccessRegistry(new PluginRegistry(BuiltInPlugins.createAll()));
        Set<String> runtimeKeys = routeAccessRegistry.routes().stream()
                .map(r -> r.route().pathPattern() + "|" + r.route().accessPolicy())
                .collect(Collectors.toSet());
        List<String> missing = builtIn.routes().stream()
                .map(r -> r.route().pathPattern() + "|" + r.route().accessPolicy())
                .filter(key -> !runtimeKeys.contains(key))
                .toList();
        assertThat(missing).as("运行期 registry 缺失内置快照声明：%s", missing).isEmpty();
    }

    /** 把含 {@code {placeholder}} 的映射模式转成一条具体路径，供 registry 的 startsWith / 精确匹配判定。 */
    private static String toConcretePath(String pattern) {
        return pattern.replaceAll("\\{[^/]+}", "x");
    }
}
