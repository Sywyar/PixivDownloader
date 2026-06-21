package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ConditionalOnPluginEnabled} 装配语义：未配置默认装配、{@code enabled=false} 缺席、显式
 * {@code enabled=true} 装配；短横线插件 id 正常绑定；未标注本注解的 Bean 不受影响。
 * 此外，<b>必选插件</b>（{@link BuiltInPlugins#isRequired}，如 download-workbench）的开关被忽略：
 * 即便 {@code enabled=false} 其标注 Bean 仍装配。
 */
@DisplayName("@ConditionalOnPluginEnabled 装配语义")
class ConditionalOnPluginEnabledTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(GatedConfig.class);

    @Configuration
    static class GatedConfig {

        // gallery 是可禁用功能插件：用于验证「按开关装配 / 缺席」。
        @Bean
        @ConditionalOnPluginEnabled("gallery")
        String gatedBean() {
            return "gated";
        }

        // download-workbench 是必选插件：用于验证「开关被忽略、恒装配」。
        @Bean
        @ConditionalOnPluginEnabled("download-workbench")
        String requiredGatedBean() {
            return "required-gated";
        }

        @Bean
        String ungatedBean() {
            return "ungated";
        }
    }

    @Test
    @DisplayName("未配置该插件时默认装配（缺项默认启用）")
    void defaultsToEnabled() {
        runner.run(context -> {
            assertThat(context).hasBean("gatedBean");
            assertThat(context).hasBean("ungatedBean");
        });
    }

    @Test
    @DisplayName("plugins.<id>.enabled=false 时被标注的 Bean 缺席，未标注的 Bean 不受影响")
    void disabledDropsGatedBeanOnly() {
        runner.withPropertyValues("plugins.gallery.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean("gatedBean");
                    assertThat(context).hasBean("ungatedBean");
                });
    }

    @Test
    @DisplayName("显式 plugins.<id>.enabled=true 时装配（短横线 id 正常）")
    void explicitTrueAssembles() {
        runner.withPropertyValues("plugins.download-workbench.enabled=true")
                .run(context -> assertThat(context).hasBean("requiredGatedBean"));
    }

    @Test
    @DisplayName("必选插件开关被忽略：plugins.<id>.enabled=false 时其标注 Bean 仍装配")
    void requiredPluginIgnoresDisableFlag() {
        runner.withPropertyValues("plugins.download-workbench.enabled=false")
                .run(context -> assertThat(context).hasBean("requiredGatedBean"));
    }
}
