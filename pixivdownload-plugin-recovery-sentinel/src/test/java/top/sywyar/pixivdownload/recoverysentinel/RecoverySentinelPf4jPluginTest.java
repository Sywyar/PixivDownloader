package top.sywyar.pixivdownload.recoverysentinel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivPluginProvider;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;

import java.io.InputStream;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * recovery-sentinel 外置 PF4J 插件包的形态守卫：证明本模块作为外置插件 jar 时，
 * <ul>
 *   <li>jar 根部含 PF4J 描述符 {@code plugin.properties}（id=recovery-sentinel、合法 semver 版本、requires=1.0、
 *       plugin.class 指向 {@link RecoverySentinelPf4jPlugin}）；</li>
 *   <li>{@link RecoverySentinelPf4jPlugin} 继承 {@code org.pf4j.Plugin} 并实现入口契约 {@link PixivPluginProvider}；</li>
 *   <li>{@link PixivPluginProvider#featurePlugins()} 暴露最小功能插件 {@link RecoverySentinelPlugin}
 *       （id {@code recovery-sentinel}）；</li>
 *   <li>该功能插件<b>不自称必选</b>（{@link PixivFeaturePlugin#required()} 为 {@code false}）、且<b>不贡献任何东西</b>
 *       （route / static / i18n / navigation / schema / 维护任务 / 调度来源 / 队列 / 标签页 / 落点 全部为空）。</li>
 * </ul>
 * 这些是核心据以加载 / 校验 / 接入外置 recovery-sentinel 插件并用其验证恢复模式的契约面。
 */
@DisplayName("recovery-sentinel 外置 PF4J 插件包形态")
class RecoverySentinelPf4jPluginTest {

    private Properties readPluginProperties() throws Exception {
        try (InputStream in = RecoverySentinelPf4jPluginTest.class.getResourceAsStream("/plugin.properties")) {
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
        assertThat(properties.getProperty("plugin.id")).isEqualTo("recovery-sentinel");
        assertThat(properties.getProperty("plugin.version"))
                .as("plugin.version 必须是合法 semver（至少 major.minor.patch）")
                .matches("\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?");
        assertThat(properties.getProperty("plugin.requires")).isEqualTo("1.0");
        assertThat(properties.getProperty("plugin.class"))
                .isEqualTo("top.sywyar.pixivdownload.recoverysentinel.RecoverySentinelPf4jPlugin");
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
    @DisplayName("featurePlugins() 暴露最小功能插件 RecoverySentinelPlugin（id=recovery-sentinel、类别 FEATURE）")
    void featurePluginsExposeRecoverySentinel() {
        PixivPluginProvider provider = new RecoverySentinelPf4jPlugin();
        List<PixivFeaturePlugin> featurePlugins = provider.featurePlugins();
        assertThat(featurePlugins).hasSize(1);
        PixivFeaturePlugin sentinel = featurePlugins.get(0);
        assertThat(sentinel).isInstanceOf(RecoverySentinelPlugin.class);
        assertThat(sentinel.id()).isEqualTo("recovery-sentinel");
        assertThat(sentinel.kind()).isEqualTo(PluginKind.FEATURE);
    }

    @Test
    @DisplayName("recovery-sentinel 不自称必选（required=false），必选性只能由核心策略声明")
    void sentinelDoesNotSelfDeclareRequired() {
        assertThat(new RecoverySentinelPlugin().required())
                .as("外置插件不得自封必选——否则 plugins.recovery-sentinel.enabled=false 无法模拟「已安装但禁用」")
                .isFalse();
    }

    @Test
    @DisplayName("recovery-sentinel 不贡献任何 route / static / i18n / navigation / schema / 维护任务 / 调度来源 / 队列 / 标签页 / 落点")
    void sentinelContributesNothing() {
        RecoverySentinelPlugin sentinel = new RecoverySentinelPlugin();
        assertThat(sentinel.routes()).isEmpty();
        assertThat(sentinel.staticResources()).isEmpty();
        assertThat(sentinel.i18n()).isEmpty();
        assertThat(sentinel.navigation()).isEmpty();
        assertThat(sentinel.schema()).isEmpty();
        assertThat(sentinel.maintenanceTasks()).isEmpty();
        assertThat(sentinel.scheduledSourceDescriptors()).isEmpty();
        assertThat(sentinel.queueTypes()).isEmpty();
        assertThat(sentinel.downloadTabs()).isEmpty();
        assertThat(sentinel.startupRoutes()).isEmpty();
        assertThat(sentinel.landings()).isEmpty();
        assertThat(sentinel.pageSections()).isEmpty();
        assertThat(sentinel.drilldowns()).isEmpty();
        assertThat(sentinel.userscripts()).isEmpty();
    }
}
