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
                        "plugins.gallery.enabled=false",
                        "plugins.download-workbench.enabled=false",
                        "plugins.novel.enabled=true")
                .run(context -> {
                    PluginToggleProperties props = context.getBean(PluginToggleProperties.class);
                    assertThat(props.isEnabled("gallery")).isFalse();
                    assertThat(props.isEnabled("download-workbench")).isFalse();
                    assertThat(props.isEnabled("novel")).isTrue();
                    // 未配置的插件默认启用
                    assertThat(props.isEnabled("stats")).isTrue();
                    assertThat(props.isEnabled("duplicate")).isTrue();
                });
    }

    @Test
    @DisplayName("无任何 plugins.* 配置时全部默认启用")
    void defaultsToAllEnabled() {
        runner.run(context -> {
            PluginToggleProperties props = context.getBean(PluginToggleProperties.class);
            assertThat(props.isEnabled("gallery")).isTrue();
            assertThat(props.isEnabled("anything")).isTrue();
        });
    }

    @Test
    @DisplayName("空实例（Spring 上下文外）代表全部启用")
    void emptyInstanceMeansAllEnabled() {
        PluginToggleProperties props = new PluginToggleProperties();
        assertThat(props.isEnabled("gallery")).isTrue();
        assertThat(props.isEnabled("download-workbench")).isTrue();
    }

    @Test
    @DisplayName("静态 isEnabled(Environment) 与实例语义一致：缺项默认启用、短横线 id、enabled=false 生效")
    void staticEnvironmentReadMirrorsInstanceSemantics() {
        MockEnvironment env = new MockEnvironment();
        // 缺项默认启用
        assertThat(PluginToggleProperties.isEnabled(env, "novel")).isTrue();
        assertThat(PluginToggleProperties.isEnabled(env, null)).isTrue();
        // 短横线 id 正常绑定 + enabled=false 生效
        env.setProperty("plugins.download-workbench.enabled", "false");
        assertThat(PluginToggleProperties.isEnabled(env, "download-workbench")).isFalse();
        // 显式 true
        env.setProperty("plugins.gallery.enabled", "true");
        assertThat(PluginToggleProperties.isEnabled(env, "gallery")).isTrue();
    }
}
