package top.sywyar.pixivdownload.gallerytools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.duplicate.DuplicatePluginConfiguration;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivPluginProvider;
import top.sywyar.pixivdownload.stats.StatsPluginConfiguration;

import java.io.InputStream;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("gallery-tools 外置 PF4J 插件包形态")
class GalleryToolsPf4jPluginTest {

    @Test
    @DisplayName("根描述符声明单一 gallery-tools 插件身份")
    void pluginPropertiesDeclareMergedDescriptor() throws Exception {
        Properties properties = readPluginProperties();

        assertThat(properties.getProperty("plugin.id")).isEqualTo("gallery-tools");
        assertThat(properties.getProperty("plugin.version"))
                .matches("\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?");
        assertThat(properties.getProperty("plugin.requires")).isEqualTo("1.0");
        assertThat(properties.getProperty("plugin.class"))
                .isEqualTo("top.sywyar.pixivdownload.gallerytools.GalleryToolsPf4jPlugin");
        assertThat(properties.getProperty("pixiv.display-namespace")).isEqualTo("gallery-tools");
    }

    @Test
    @DisplayName("唯一入口暴露合并后的描述与两套业务配置")
    void providerExposesMergedPluginAndConfigurations() {
        PixivPluginProvider provider = new GalleryToolsPf4jPlugin();

        assertThat(provider.featurePlugin()).isInstanceOf(GalleryToolsPlugin.class)
                .extracting(plugin -> plugin.id()).isEqualTo("gallery-tools");
        assertThat(provider.configurationClasses())
                .containsExactly(StatsPluginConfiguration.class, DuplicatePluginConfiguration.class);
    }

    private Properties readPluginProperties() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/plugin.properties")) {
            assertThat(in).as("plugin.properties 必须位于插件 jar 根部").isNotNull();
            Properties properties = new Properties();
            properties.load(in);
            return properties;
        }
    }
}
