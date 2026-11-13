package top.sywyar.pixivdownload.startup.aot;

import aotfixture.host.Components;
import aotfixture.plugin.NewPluginComponent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.core.env.MapPropertySource;

import javax.tools.ToolProvider;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Spring AOT 宿主装配保留当前配置、生命周期与动态插件扫描")
class HostComponentsTest {
    @TempDir Path temporary;

    @Test
    @DisplayName("同一生成结果跨两种配置复用，稳定组件只初始化一次且插件子上下文独立扫描")
    void generatedDefinitionsPreserveRuntimeBehavior() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (!Files.isRegularFile(root.resolve("build-support/spring-aot/GenerateHostComponents.java"))) {
            root = root.getParent();
            if (root == null) throw new IllegalStateException("Build support source not found");
        }
        Path builder = Files.createDirectories(temporary.resolve("builder"));
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "-encoding", "UTF-8",
                "-classpath", System.getProperty("java.class.path"), "-d", builder.toString(),
                root.resolve("build-support/spring-aot/GenerateHostComponents.java").toString())).isZero();
        try (var loader = new URLClassLoader(new URL[]{builder.toUri().toURL()}, getClass().getClassLoader())) {
            loader.loadClass("GenerateHostComponents").getMethod("generate", String.class, Path.class, String.class)
                    .invoke(null, "aotfixture.host", temporary, Integer.toString(Runtime.version().feature()));
        }
        try (var loader = new URLClassLoader(new URL[]{temporary.resolve("spring-aot/classes").toUri().toURL()},
                getClass().getClassLoader())) {
            verifyContext(loader, false, "first");
            verifyContext(loader, true, "second");
        }
    }

    private void verifyContext(ClassLoader loader, boolean enabled, String value) {
        var lifecycle = new Components.Lifecycle();
        try (var context = (AnnotationConfigApplicationContext) HostComponents.contextFactory().create(WebApplicationType.NONE)) {
            context.setClassLoader(loader);
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture",
                    Map.of("fixture.enabled", enabled, "fixture.value", value)));
            context.getBeanFactory().registerSingleton("lifecycle", lifecycle);
            new HostComponents().initialize(context);
            // 构造代码确实来自 AOT；反射回退即使能启动，也不能满足这一断言。
            String beanName = context.getBeanFactory().getBeanNamesForType(Components.Stable.class)[0];
            assertThat(((AbstractBeanDefinition) context.getBeanDefinition(beanName)).getInstanceSupplier()).isNotNull();
            context.register(RootScan.class);
            context.refresh();
            assertThat(context.getBean(Components.Stable.class).value).isEqualTo(value);
            assertThat(lifecycle.initialized).hasValue(1);
            assertThat(context.getBeansOfType(Components.ConditionalComponent.class)).hasSize(enabled ? 1 : 0);
            assertThat(context.containsBean("conditionalValue")).isEqualTo(enabled);
            if (enabled) assertThat(context.getBean("conditionalValue")).isEqualTo(value);
            assertThat(context.getBeansOfType(Components.PluginOwned.class)).isEmpty();
            var scoped = context.getBean(Components.Scoped.class);
            assertThat(scoped.id()).isNotEqualTo(scoped.id());
            try (var plugin = new AnnotationConfigApplicationContext()) {
                plugin.setParent(context);
                plugin.scan("aotfixture.plugin");
                plugin.refresh();
                assertThat(plugin.getBean(NewPluginComponent.class).value()).isEqualTo("new-plugin");
                assertThat(context.getBeansOfType(NewPluginComponent.class)).isEmpty();
            }
        }
        assertThat(lifecycle.destroyed).hasValue(1);
    }

    @TestConfiguration(proxyBeanMethods = false)
    @ComponentScan("top.sywyar.pixivdownload")
    static class RootScan {}
}
