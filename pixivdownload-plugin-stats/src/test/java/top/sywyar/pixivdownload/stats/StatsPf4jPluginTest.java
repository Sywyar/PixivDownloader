package top.sywyar.pixivdownload.stats;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivPluginProvider;

import java.io.InputStream;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * stats 外置 PF4J 插件包的形态守卫：证明本模块作为外置插件 jar 时，
 * <ul>
 *   <li>jar 根部含 PF4J 描述符 {@code plugin.properties}（id=stats、合法 semver 版本、requires=1.0、
 *       plugin.class 指向 {@link StatsPf4jPlugin}）；</li>
 *   <li>{@link StatsPf4jPlugin} 继承 {@code org.pf4j.Plugin} 并实现入口契约 {@link PixivPluginProvider}；</li>
 *   <li>{@link PixivPluginProvider#featurePlugin()} 暴露统计功能插件 {@link StatsPlugin}（id {@code stats}）。</li>
 * </ul>
 * 这些是核心发现桥接据以加载 / 校验 / 接入外置 stats 插件的契约面（真实 jar 端到端加载在 app 模块的集成测试覆盖）。
 */
@DisplayName("stats 外置 PF4J 插件包形态")
class StatsPf4jPluginTest {

    private Properties readPluginProperties() throws Exception {
        try (InputStream in = StatsPf4jPluginTest.class.getResourceAsStream("/plugin.properties")) {
            assertThat(in).as("plugin.properties 必须位于插件 jar 根部").isNotNull();
            Properties properties = new Properties();
            properties.load(in);
            return properties;
        }
    }

    @Test
    @DisplayName("plugin.properties 位于根部且声明 id / 合法 semver 版本 / requires / 主类")
    void pluginPropertiesDeclareDescriptor() throws Exception {
        Properties properties = readPluginProperties();
        assertThat(properties.getProperty("plugin.id")).isEqualTo("stats");
        assertThat(properties.getProperty("plugin.version"))
                .as("plugin.version 必须是合法 semver（至少 major.minor.patch）")
                .matches("\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?");
        assertThat(properties.getProperty("plugin.requires")).isEqualTo("1.0");
        assertThat(properties.getProperty("plugin.class"))
                .isEqualTo("top.sywyar.pixivdownload.stats.StatsPf4jPlugin");
    }

    @Test
    @DisplayName("plugin.class 指向的主类继承 PF4J Plugin 并实现入口契约 PixivPluginProvider")
    void mainClassIsPf4jPluginAndProvider() throws Exception {
        String pluginClass = readPluginProperties().getProperty("plugin.class");
        Class<?> mainClass = Class.forName(pluginClass);
        assertThat(org.pf4j.Plugin.class).isAssignableFrom(mainClass);
        assertThat(PixivPluginProvider.class).isAssignableFrom(mainClass);
    }

    @Test
    @DisplayName("featurePlugin() 暴露统计功能插件 StatsPlugin（id=stats）")
    void featurePluginExposesStats() {
        PixivPluginProvider provider = new StatsPf4jPlugin();
        PixivFeaturePlugin stats = provider.featurePlugin();
        assertThat(stats).isInstanceOf(StatsPlugin.class);
        assertThat(stats.id()).isEqualTo("stats");
    }
}
