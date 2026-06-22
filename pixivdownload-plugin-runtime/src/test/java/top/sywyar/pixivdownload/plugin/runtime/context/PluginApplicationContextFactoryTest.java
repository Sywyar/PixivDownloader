package top.sywyar.pixivdownload.plugin.runtime.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 每外置插件子 {@code ApplicationContext} 工厂测试：用 synthetic 父 context（暴露一个核心服务接口）+ synthetic 插件
 * 配置类（其 Bean 注入该核心服务），验证子 context 能实例化插件 Bean、向父 context 解析核心 API、插件 Bean 不进入
 * 父 context，且子 context 关闭后不再可用——不依赖 PF4J / Spring Boot。
 */
@DisplayName("每外置插件子 ApplicationContext 工厂")
class PluginApplicationContextFactoryTest {

    private final PluginApplicationContextFactory factory = new PluginApplicationContextFactory();

    @Test
    @DisplayName("插件配置类在子 context 中实例化 Bean，并注入父 context 暴露的核心服务接口；插件 Bean 不在父 context")
    void instantiatesPluginBeansInjectingParentCoreService() {
        try (AnnotationConfigApplicationContext parent =
                     new AnnotationConfigApplicationContext(ParentCoreConfig.class)) {
            PluginContextModule module = new PluginContextModule(
                    "ext-demo", getClass().getClassLoader(), List.of(PluginConfig.class));

            ConfigurableApplicationContext child = factory.create(parent, module);

            // 子 context 的父就是核心应用 context
            assertThat(child.getParent()).isSameAs(parent);
            // 子 context 创建了插件 Bean
            PluginBean pluginBean = child.getBean(PluginBean.class);
            assertThat(pluginBean).isNotNull();
            // 插件 Bean 注入的核心服务来自父 context（同一实例）——子 context 能拿到父 context 暴露的核心 API 服务
            assertThat(pluginBean.coreService()).isSameAs(parent.getBean(CoreApiService.class));
            // 插件 Bean 不出现在父 context（不进入父根扫描 / 父 BeanFactory）
            assertThat(parent.getBeanNamesForType(PluginBean.class)).isEmpty();

            child.close();
        }
    }

    @Test
    @DisplayName("插件停止后子 context 关闭、不再可用（生命周期可观测）")
    void closedChildBecomesInactiveAndUnusable() {
        try (AnnotationConfigApplicationContext parent =
                     new AnnotationConfigApplicationContext(ParentCoreConfig.class)) {
            ConfigurableApplicationContext child = factory.create(parent, new PluginContextModule(
                    "ext-demo", getClass().getClassLoader(), List.of(PluginConfig.class)));
            assertThat(child.isActive()).isTrue();

            child.close();

            assertThat(child.isActive()).isFalse();
            // 关闭后再取 Bean 抛出（子 context 不再可用）
            assertThatThrownBy(() -> child.getBean(PluginBean.class)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    @DisplayName("子 context 用插件 classloader 作为其 classloader（资源 / 类解析走插件 loader）")
    void childUsesPluginClassLoader() throws Exception {
        try (AnnotationConfigApplicationContext parent =
                     new AnnotationConfigApplicationContext(ParentCoreConfig.class);
             URLClassLoader pluginClassLoader =
                     new URLClassLoader(new URL[0], getClass().getClassLoader())) {
            ConfigurableApplicationContext child = factory.create(parent,
                    new PluginContextModule("ext-demo", pluginClassLoader, List.of(PluginConfig.class)));

            assertThat(child.getClassLoader()).isSameAs(pluginClassLoader);
            assertThat(child.getBeanFactory().getBeanClassLoader()).isSameAs(pluginClassLoader);

            child.close();
        }
    }

    // --- 夹具 ---

    /** 父 context 暴露的「核心 API / 服务接口」。 */
    interface CoreApiService {
        String describe();
    }

    static final class CoreApiServiceImpl implements CoreApiService {
        @Override
        public String describe() {
            return "core";
        }
    }

    /** 核心应用 context 的占位：只暴露一个核心服务 Bean。 */
    @Configuration
    static class ParentCoreConfig {
        @Bean
        CoreApiService coreApiService() {
            return new CoreApiServiceImpl();
        }
    }

    /** 插件 Bean：构造期注入父 context 的核心服务接口。 */
    static final class PluginBean {
        private final CoreApiService coreService;

        PluginBean(CoreApiService coreService) {
            this.coreService = coreService;
        }

        CoreApiService coreService() {
            return coreService;
        }
    }

    /** 插件配置类：在子 context 中以 @Bean 装配插件 Bean，注入父 context 的核心服务。 */
    @Configuration
    static class PluginConfig {
        @Bean
        PluginBean pluginBean(CoreApiService coreService) {
            return new PluginBean(coreService);
        }
    }
}
