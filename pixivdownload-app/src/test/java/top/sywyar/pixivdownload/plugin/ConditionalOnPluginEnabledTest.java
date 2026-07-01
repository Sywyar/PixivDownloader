package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;

/**
 * {@link ConditionalOnPluginEnabled} 装配语义：未配置默认装配、{@code enabled=false} 缺席、显式
 * {@code enabled=true} 装配；短横线插件 id 正常绑定；未标注本注解的 Bean 不受影响。
 * <p>
 * 本条件<b>只读 {@code plugins.<id>.enabled} 开关、不区分必选 / 可选插件</b>（把 id 当作不透明开关键）：
 * 必选插件「不可禁用」由 app 侧 {@link PluginRegistry} 注册期强制，且必选插件业务 Bean 一律不标本注解
 * （恒无条件装配），该不变量由 {@code PluginApiDependencyGuardTest} 守护——故此处不再用必选插件 id 验证
 * 「开关被忽略」（旧的 {@code isRequired} 短路分支已随 plugin-runtime 抽取删除）。
 */
@DisplayName("@ConditionalOnPluginEnabled 装配语义")
class ConditionalOnPluginEnabledTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(GatedConfig.class);

    @Configuration
    static class GatedConfig {

        // 单字 id：验证「按开关装配 / 缺席」。
        @Bean
        @ConditionalOnPluginEnabled("gallery")
        String gatedBean() {
            return "gated";
        }

        // 短横线 id：验证 relaxed binding（条件把 id 当作不透明开关键，不特判必选 / 内置）。
        @Bean
        @ConditionalOnPluginEnabled("demo-feature")
        String dashIdGatedBean() {
            return "dash-gated";
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
            assertThat(context).hasBean("dashIdGatedBean");
            assertThat(context).hasBean("ungatedBean");
        });
    }

    @Test
    @DisplayName("plugins.<id>.enabled=false 时被标注的 Bean 缺席，未标注的 Bean 不受影响")
    void disabledDropsGatedBeanOnly() {
        runner.withPropertyValues("plugins.gallery.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean("gatedBean");
                    assertThat(context).hasBean("dashIdGatedBean");
                    assertThat(context).hasBean("ungatedBean");
                });
    }

    @Test
    @DisplayName("短横线 id 正常绑定：plugins.<dash-id>.enabled=false 时其标注 Bean 缺席")
    void dashIdBindsAndGates() {
        runner.withPropertyValues("plugins.demo-feature.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean("dashIdGatedBean");
                    assertThat(context).hasBean("gatedBean");
                });
    }

    @Test
    @DisplayName("显式 plugins.<id>.enabled=true 时装配")
    void explicitTrueAssembles() {
        runner.withPropertyValues("plugins.gallery.enabled=true")
                .run(context -> assertThat(context).hasBean("gatedBean"));
    }
}
