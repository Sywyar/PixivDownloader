package top.sywyar.pixivdownload.douyin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Douyin PF4J 外置插件描述")
class DouyinPf4jPluginTest {

    @Test
    @DisplayName("plugin.properties 声明 Douyin 外置主类与展示元数据")
    void pluginPropertiesDeclareExternalEntry() throws IOException {
        Properties props = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/plugin.properties")) {
            assertThat(in).isNotNull();
            props.load(in);
        }

        assertThat(props.getProperty("plugin.id")).isEqualTo("douyin");
        assertThat(props.getProperty("plugin.class")).isEqualTo(DouyinPf4jPlugin.class.getName());
        assertThat(props.getProperty("pixiv.display-namespace")).isEqualTo("douyin");
        assertThat(props.getProperty("pixiv.display-name-key")).isEqualTo("plugin.name");
    }

    @Test
    @DisplayName("PF4J 主类暴露 Douyin 功能插件与子上下文配置类")
    void pf4jProviderExposesFeaturePluginAndConfiguration() {
        DouyinPf4jPlugin provider = new DouyinPf4jPlugin();

        assertThat(provider.featurePlugins()).singleElement()
                .isInstanceOf(DouyinPlugin.class)
                .extracting(plugin -> plugin.id())
                .isEqualTo("douyin");
        assertThat(provider.configurationClasses()).containsExactly(DouyinPluginConfiguration.class);
    }

    @Test
    @DisplayName("外置资源位于 Douyin 模块 classpath")
    void moduleClasspathCarriesDouyinResources() {
        ClassLoader loader = getClass().getClassLoader();

        assertThat(loader.getResource("plugin.properties")).isNotNull();
        assertThat(loader.getResource("static/pixiv-douyin-download/douyin-queue-type.js")).isNotNull();
        assertThat(loader.getResource("static/pixiv-douyin-download/douyin-schedule-sources.js")).isNotNull();
        assertThat(loader.getResource("i18n/web/douyin.properties")).isNotNull();
        assertThat(loader.getResource("i18n/web/douyin_en.properties")).isNotNull();
    }
}
