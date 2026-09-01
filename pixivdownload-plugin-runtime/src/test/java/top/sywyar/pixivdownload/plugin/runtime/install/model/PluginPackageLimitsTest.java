package top.sywyar.pixivdownload.plugin.runtime.install.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("插件包安全上限配置")
class PluginPackageLimitsTest {

    @Test
    @DisplayName("JVM 属性可覆盖全部八项限制")
    void readsEveryConfiguredLimit() {
        Properties properties = new Properties();
        properties.setProperty(PluginPackageLimits.PROPERTY_PREFIX + "max-archive-bytes", "1");
        properties.setProperty(PluginPackageLimits.PROPERTY_PREFIX + "max-entries", "2");
        properties.setProperty(PluginPackageLimits.PROPERTY_PREFIX + "max-total-uncompressed-bytes", "3");
        properties.setProperty(PluginPackageLimits.PROPERTY_PREFIX + "max-entry-uncompressed-bytes", "4");
        properties.setProperty(PluginPackageLimits.PROPERTY_PREFIX + "max-descriptor-bytes", "5");
        properties.setProperty(PluginPackageLimits.PROPERTY_PREFIX + "max-compression-ratio", "6");
        properties.setProperty(PluginPackageLimits.PROPERTY_PREFIX + "max-entry-name-length", "7");
        properties.setProperty(PluginPackageLimits.PROPERTY_PREFIX + "max-entry-depth", "8");

        assertThat(PluginPackageLimits.fromProperties(properties))
                .isEqualTo(new PluginPackageLimits(1, 2, 3, 4, 5, 6, 7, 8));
    }

    @Test
    @DisplayName("非法覆盖值明确指出属性名和值")
    void rejectsInvalidConfiguredLimit() {
        Properties properties = new Properties();
        properties.setProperty(PluginPackageLimits.PROPERTY_PREFIX + "max-entry-depth", "zero");

        assertThatThrownBy(() -> PluginPackageLimits.fromProperties(properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pixivdownload.plugin.package.max-entry-depth")
                .hasMessageContaining("zero");
    }
}
