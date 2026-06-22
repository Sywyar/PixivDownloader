package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager;
import top.sywyar.pixivdownload.plugin.runtime.context.PluginApplicationContextFactory;
import top.sywyar.pixivdownload.plugin.runtime.context.PluginContextModule;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 核心壳外置插件子 context 生命周期管理器测试：用 synthetic 父 context（暴露核心服务）+ 一个返回 synthetic
 * 装配定义的 {@link PluginRuntimeManager}（匿名子类、不经 PF4J），验证管理器在 {@code start()} 建立子 context、
 * 子 context Bean 注入父 context 核心服务、插件 Bean 不在父 context，{@code stop()} 关闭全部子 context；
 * 无外置插件时透明无副作用；单个插件子 context 建立失败被隔离、不影响其它插件。
 */
@DisplayName("外置插件子 ApplicationContext 生命周期管理器")
class ExternalPluginContextManagerTest {

    private final PluginApplicationContextFactory factory = new PluginApplicationContextFactory();
    /**
     * 真实但「空」的 controller 注册器：本测试聚焦子 context 生命周期，其 synthetic 插件 Bean 都不是 controller，
     * 故注册器扫到 0 个 handler、不注册任何映射（也不需要初始化 mapping）。注册器自身行为另由
     * {@link PluginControllerRegistrarTest} 覆盖。
     */
    private final PluginControllerRegistrar controllerRegistrar = new PluginControllerRegistrar(
            new PluginAwareRequestMappingHandlerMapping(), new RouteAccessRegistry(new PluginRegistry(List.of())));

    private static PluginRuntimeManager runtimeReturning(List<PluginContextModule> modules) {
        return new PluginRuntimeManager(Path.of("target/no-such-plugins-dir")) {
            @Override
            public List<PluginContextModule> inspectContextModules() {
                return modules;
            }
        };
    }

    @Test
    @DisplayName("start 为外置插件建立子 context：插件 Bean 注入父核心服务、不在父 context；stop 关闭子 context")
    void startBuildsChildContextThenStopCloses() {
        try (AnnotationConfigApplicationContext parent =
                     new AnnotationConfigApplicationContext(ParentCoreConfig.class)) {
            PluginContextModule module = new PluginContextModule(
                    "ext-demo", getClass().getClassLoader(), List.of(PluginConfig.class));
            ExternalPluginContextManager manager =
                    new ExternalPluginContextManager(parent, runtimeReturning(List.of(module)), factory,
                            controllerRegistrar);

            manager.start();

            assertThat(manager.isRunning()).isTrue();
            assertThat(manager.count()).isEqualTo(1);
            assertThat(manager.pluginIds()).containsExactly("ext-demo");
            ConfigurableApplicationContext child = manager.contextFor("ext-demo").orElseThrow();
            assertThat(child.isActive()).isTrue();
            // 子 context Bean 注入父 context 暴露的核心服务（同一实例）
            assertThat(child.getBean(PluginBean.class).coreService())
                    .isSameAs(parent.getBean(CoreApiService.class));
            // 插件 Bean 不在父 context
            assertThat(parent.getBeanNamesForType(PluginBean.class)).isEmpty();

            manager.stop();

            assertThat(manager.isRunning()).isFalse();
            assertThat(manager.count()).isZero();
            assertThat(child.isActive()).isFalse();
        }
    }

    @Test
    @DisplayName("无外置插件：管理器为空、透明无副作用")
    void noExternalPluginsIsTransparent() {
        try (AnnotationConfigApplicationContext parent =
                     new AnnotationConfigApplicationContext(ParentCoreConfig.class)) {
            ExternalPluginContextManager manager =
                    new ExternalPluginContextManager(parent, runtimeReturning(List.of()), factory,
                            controllerRegistrar);

            manager.start();

            assertThat(manager.isRunning()).isTrue();
            assertThat(manager.count()).isZero();
            assertThat(manager.pluginIds()).isEmpty();

            manager.stop();
        }
    }

    @Test
    @DisplayName("单个插件子 context 建立失败被隔离：不影响其它插件、不致核心壳启动失败")
    void failingPluginContextIsIsolated() {
        try (AnnotationConfigApplicationContext parent =
                     new AnnotationConfigApplicationContext(ParentCoreConfig.class)) {
            PluginContextModule broken = new PluginContextModule(
                    "ext-broken", getClass().getClassLoader(), List.of(BrokenPluginConfig.class));
            PluginContextModule good = new PluginContextModule(
                    "ext-good", getClass().getClassLoader(), List.of(PluginConfig.class));
            ExternalPluginContextManager manager =
                    new ExternalPluginContextManager(parent, runtimeReturning(List.of(broken, good)), factory,
                            controllerRegistrar);

            manager.start();

            // 坏插件被隔离、好插件照常建立
            assertThat(manager.isRunning()).isTrue();
            assertThat(manager.pluginIds()).containsExactly("ext-good");
            assertThat(manager.contextFor("ext-broken")).isEmpty();
            assertThat(manager.contextFor("ext-good")).isPresent();

            manager.stop();
        }
    }

    // --- 夹具 ---

    interface CoreApiService {
        String describe();
    }

    static final class CoreApiServiceImpl implements CoreApiService {
        @Override
        public String describe() {
            return "core";
        }
    }

    @Configuration
    static class ParentCoreConfig {
        @Bean
        CoreApiService coreApiService() {
            return new CoreApiServiceImpl();
        }
    }

    static final class PluginBean {
        private final CoreApiService coreService;

        PluginBean(CoreApiService coreService) {
            this.coreService = coreService;
        }

        CoreApiService coreService() {
            return coreService;
        }
    }

    @Configuration
    static class PluginConfig {
        @Bean
        PluginBean pluginBean(CoreApiService coreService) {
            return new PluginBean(coreService);
        }
    }

    /** 配置类：依赖一个父 context 不提供的类型，refresh 时无法满足 → 子 context 建立失败（用于验证失败隔离）。 */
    @Configuration
    static class BrokenPluginConfig {
        @Bean
        PluginBean brokenBean(MissingDependency missing) {
            return new PluginBean(null);
        }
    }

    /** 父 context 中不存在的依赖类型。 */
    interface MissingDependency {
    }
}
