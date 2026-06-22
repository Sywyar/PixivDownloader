package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.StaticWebApplicationContext;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 外置插件 controller 动态注册器测试：用真实（已初始化）的 {@link PluginAwareRequestMappingHandlerMapping} +
 * 真实 {@link RouteAccessRegistry}，验证——已声明路由的 controller 注册后映射进父分发表、注销后从分发表消失；
 * 缺路由声明 / 方法不一致的 controller 被拒绝注册（且一条都不注册，按插件原子）。
 */
@DisplayName("外置插件 controller 动态注册器")
class PluginControllerRegistrarTest {

    @Test
    @DisplayName("已声明路由的 controller：注册后映射进父分发表，注销后同 URL 不再命中 controller")
    void registersThenUnregistersDeclaredController() {
        PluginAwareRequestMappingHandlerMapping mapping = newInitializedMapping();
        RouteAccessRegistry routes = new RouteAccessRegistry(new PluginRegistry(List.of()));
        routes.register("test-plugin", List.of(WebRouteContribution.admin("/api/test/**")));
        PluginControllerRegistrar registrar = new PluginControllerRegistrar(mapping, routes);

        try (AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext(DeclaredController.class)) {
            int registered = registrar.registerControllers("test-plugin", child);

            assertThat(registered).isEqualTo(1);
            assertThat(registrar.registeredPluginIds()).containsExactly("test-plugin");
            assertThat(mappedPaths(mapping)).contains("/api/test/ping");
            // handler 实例就是子 context 中的 controller Bean
            assertThat(mapping.getHandlerMethods().values())
                    .anyMatch(hm -> hm.getBean() == child.getBean(DeclaredController.class));

            registrar.unregisterControllers("test-plugin");

            assertThat(registrar.registeredPluginIds()).doesNotContain("test-plugin");
            assertThat(mappedPaths(mapping)).doesNotContain("/api/test/ping");
        }
    }

    @Test
    @DisplayName("缺路由声明：拒绝注册并给出含违例映射的诊断，且一条映射都不注册")
    void rejectsControllerWithoutRouteDeclaration() {
        PluginAwareRequestMappingHandlerMapping mapping = newInitializedMapping();
        RouteAccessRegistry routes = new RouteAccessRegistry(new PluginRegistry(List.of())); // 无任何声明
        PluginControllerRegistrar registrar = new PluginControllerRegistrar(mapping, routes);

        try (AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext(DeclaredController.class)) {
            assertThatThrownBy(() -> registrar.registerControllers("test-plugin", child))
                    .isInstanceOf(PluginControllerRegistrationException.class)
                    .hasMessageContaining("/api/test/ping")
                    .hasMessageContaining("test-plugin");

            assertThat(registrar.registeredPluginIds()).doesNotContain("test-plugin");
            assertThat(mappedPaths(mapping)).doesNotContain("/api/test/ping");
        }
    }

    @Test
    @DisplayName("方法不一致：声明仅 GET 但 controller 是 POST → 拒绝注册（与 controller mapping 不一致）")
    void rejectsControllerWhenDeclaredMethodMismatches() {
        PluginAwareRequestMappingHandlerMapping mapping = newInitializedMapping();
        RouteAccessRegistry routes = new RouteAccessRegistry(new PluginRegistry(List.of()));
        // 仅声明 GET /api/test/**；controller 暴露的是 POST /api/test/submit
        routes.register("test-plugin", List.of(new WebRouteContribution(
                "/api/test/**", top.sywyar.pixivdownload.plugin.api.web.AccessPolicy.ADMIN,
                Set.of(top.sywyar.pixivdownload.plugin.api.web.HttpMethod.GET), false)));
        PluginControllerRegistrar registrar = new PluginControllerRegistrar(mapping, routes);

        try (AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext(PostOnlyController.class)) {
            assertThatThrownBy(() -> registrar.registerControllers("test-plugin", child))
                    .isInstanceOf(PluginControllerRegistrationException.class)
                    .hasMessageContaining("POST")
                    .hasMessageContaining("/api/test/submit");

            assertThat(mappedPaths(mapping)).doesNotContain("/api/test/submit");
        }
    }

    @Test
    @DisplayName("按插件原子：同一 controller 一个映射已声明、一个未声明 → 整体拒绝，已声明的那条也不注册")
    void rejectionIsAtomicPerPlugin() {
        PluginAwareRequestMappingHandlerMapping mapping = newInitializedMapping();
        RouteAccessRegistry routes = new RouteAccessRegistry(new PluginRegistry(List.of()));
        routes.register("test-plugin", List.of(WebRouteContribution.admin("/api/test/**"))); // 只覆盖 /api/test/**
        PluginControllerRegistrar registrar = new PluginControllerRegistrar(mapping, routes);

        try (AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext(MixedController.class)) {
            assertThatThrownBy(() -> registrar.registerControllers("test-plugin", child))
                    .isInstanceOf(PluginControllerRegistrationException.class)
                    .hasMessageContaining("/api/other/ping");

            // 已声明的 /api/test/ping 也不应注册（原子失败）
            assertThat(mappedPaths(mapping)).doesNotContain("/api/test/ping", "/api/other/ping");
            assertThat(registrar.registeredPluginIds()).isEmpty();
        }
    }

    @Test
    @DisplayName("registerMapping 冲突：第一条已注册、第二条与已有 handler 冲突 → 回滚第一条且整插件不注册")
    void rollsBackWhenLaterRegisterMappingConflicts() throws Exception {
        PluginAwareRequestMappingHandlerMapping mapping = newInitializedMapping();
        // 预先在父分发表占用 GET /api/test/conflict（模拟「已有 handler」），令插件后续的同名映射在 registerMapping 时冲突。
        Object occupant = new ConflictOccupant();
        Method occupantMethod = ConflictOccupant.class.getDeclaredMethod("conflict");
        RequestMappingInfo occupantInfo = mapping.mappingForHandlerMethod(occupantMethod, ConflictOccupant.class);
        mapping.registerMapping(occupantInfo, occupant, occupantMethod);

        RouteAccessRegistry routes = new RouteAccessRegistry(new PluginRegistry(List.of()));
        routes.register("test-plugin", List.of(WebRouteContribution.admin("/api/test/**"))); // 同时覆盖 ping 与 conflict
        PluginControllerRegistrar registrar = new PluginControllerRegistrar(mapping, routes);

        // 两个 controller 按 Bean 注册顺序检测：第一条 /api/test/ping 可注册，第二条 /api/test/conflict 与占位 handler 冲突。
        try (AnnotationConfigApplicationContext child =
                     new AnnotationConfigApplicationContext(FirstPingController.class, ConflictingController.class)) {
            assertThatThrownBy(() -> registrar.registerControllers("test-plugin", child))
                    .isInstanceOf(PluginControllerRegistrationException.class)
                    .hasMessageContaining("test-plugin")
                    .hasMessageContaining("/api/test/conflict");

            // 第一条 /api/test/ping 已回滚、不残留；该插件未进 registeredPluginIds
            assertThat(mappedPaths(mapping)).doesNotContain("/api/test/ping");
            assertThat(registrar.registeredPluginIds()).doesNotContain("test-plugin");
            assertThat(registrar.registeredPluginIds()).isEmpty();
            // 预先占用的 handler 不受插件注册失败影响、仍在父分发表中（只回滚本插件本次注册的映射）
            assertThat(mappedPaths(mapping)).contains("/api/test/conflict");
        }
    }

    // --- helpers ---

    /** 收集 mapping 当前全部 handler 映射的路径模式。 */
    private static Set<String> mappedPaths(PluginAwareRequestMappingHandlerMapping mapping) {
        return mapping.getHandlerMethods().keySet().stream()
                .flatMap(info -> info.getPatternValues().stream())
                .collect(Collectors.toSet());
    }

    /** 建立并初始化一个独立的 mapping（带空的 web 上下文，仅用于构建 {@link RequestMappingInfo} 与持有注册表）。 */
    private static PluginAwareRequestMappingHandlerMapping newInitializedMapping() {
        StaticWebApplicationContext wac = new StaticWebApplicationContext();
        wac.setServletContext(new MockServletContext());
        wac.refresh();
        PluginAwareRequestMappingHandlerMapping mapping = new PluginAwareRequestMappingHandlerMapping();
        mapping.setApplicationContext(wac);
        mapping.afterPropertiesSet(); // 空上下文：不检测到任何核心 controller，仅初始化 BuilderConfiguration 与注册表
        return mapping;
    }

    // --- 测试用 controller（注册为子 context 的 Bean，不参与组件扫描） ---

    @RestController
    @RequestMapping("/api/test")
    static class DeclaredController {
        @GetMapping("/ping")
        String ping() {
            return "pong";
        }
    }

    @RestController
    @RequestMapping("/api/test")
    static class PostOnlyController {
        @PostMapping("/submit")
        String submit() {
            return "ok";
        }
    }

    /** 两个映射：一个落在已声明的 /api/test/**，一个落在未声明的 /api/other/**。 */
    @RestController
    static class MixedController {
        @GetMapping("/api/test/ping")
        String ping() {
            return "pong";
        }

        @GetMapping("/api/other/ping")
        String other() {
            return "other";
        }
    }

    /** 第一条可注册的 controller：GET /api/test/ping。先于冲突 controller 注册（用于验证失败时回滚）。 */
    @RestController
    static class FirstPingController {
        @GetMapping("/api/test/ping")
        String ping() {
            return "pong";
        }
    }

    /** 第二条 controller：GET /api/test/conflict 与父分发表预先占用的同名映射冲突，触发 registerMapping 失败。 */
    @RestController
    static class ConflictingController {
        @GetMapping("/api/test/conflict")
        String conflict() {
            return "dup";
        }
    }

    /** 占位 handler：直接注册进父分发表占用 GET /api/test/conflict，制造与插件 controller 的映射冲突。 */
    static class ConflictOccupant {
        @GetMapping("/api/test/conflict")
        String conflict() {
            return "occupied";
        }
    }
}
