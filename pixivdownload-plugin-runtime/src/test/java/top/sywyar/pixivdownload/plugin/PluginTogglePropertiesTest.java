package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PluginToggleProperties 绑定与默认语义")
class PluginTogglePropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class);

    @EnableConfigurationProperties(PluginToggleProperties.class)
    static class Config {
    }

    @Test
    @DisplayName("plugins.<id>.enabled 绑定到以插件 id 为键的开关表（含短横线 id）")
    void bindsToggleByPluginId() {
        runner.withPropertyValues(
                        "plugins.demo.enabled=false",
                        "plugins.demo-plugin.enabled=false",
                        "plugins.explicit.enabled=true")
                .run(context -> {
                    PluginToggleProperties props = context.getBean(PluginToggleProperties.class);
                    assertThat(props.isEnabled("demo")).isFalse();
                    assertThat(props.isEnabled("demo-plugin")).isFalse();
                    assertThat(props.isEnabled("explicit")).isTrue();
                    // 未配置的插件默认启用
                    assertThat(props.isEnabled("unconfigured")).isTrue();
                    assertThat(props.isEnabled("another-plugin")).isTrue();
                });
    }

    @Test
    @DisplayName("无任何 plugins.* 配置时全部默认启用")
    void defaultsToAllEnabled() {
        runner.run(context -> {
            PluginToggleProperties props = context.getBean(PluginToggleProperties.class);
            assertThat(props.isEnabled("demo")).isTrue();
            assertThat(props.isEnabled("anything")).isTrue();
        });
    }

    @Test
    @DisplayName("空实例（Spring 上下文外）代表全部启用")
    void emptyInstanceMeansAllEnabled() {
        PluginToggleProperties props = new PluginToggleProperties();
        assertThat(props.isEnabled("demo")).isTrue();
        assertThat(props.isEnabled("demo-plugin")).isTrue();
    }

    @Test
    @DisplayName("运行期 setEnabled 原子更新开关并立即反映到查询")
    void runtimeUpdateIsVisibleToReads() {
        PluginToggleProperties props = new PluginToggleProperties();

        props.setEnabled("demo-plugin", false);
        assertThat(props.isEnabled("demo-plugin")).isFalse();

        props.setEnabled("demo-plugin", true);
        assertThat(props.isEnabled("demo-plugin")).isTrue();
    }

    @Test
    @DisplayName("静态 isEnabled(Environment) 与实例语义一致：缺项默认启用、短横线 id、enabled=false 生效")
    void staticEnvironmentReadMirrorsInstanceSemantics() {
        MockEnvironment env = new MockEnvironment();
        // 缺项默认启用
        assertThat(PluginToggleProperties.isEnabled(env, "unconfigured")).isTrue();
        assertThat(PluginToggleProperties.isEnabled(env, null)).isTrue();
        // 短横线 id 正常绑定 + enabled=false 生效
        env.setProperty("plugins.demo-plugin.enabled", "false");
        assertThat(PluginToggleProperties.isEnabled(env, "demo-plugin")).isFalse();
        // 显式 true
        env.setProperty("plugins.explicit.enabled", "true");
        assertThat(PluginToggleProperties.isEnabled(env, "explicit")).isTrue();
    }
}
