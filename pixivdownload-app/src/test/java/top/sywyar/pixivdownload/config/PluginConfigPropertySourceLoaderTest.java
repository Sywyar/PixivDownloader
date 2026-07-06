package top.sywyar.pixivdownload.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import top.sywyar.pixivdownload.core.notification.NotificationConfig;
import top.sywyar.pixivdownload.notification.NotificationConfigKeys;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("插件 properties 属性源")
class PluginConfigPropertySourceLoaderTest {

    @TempDir
    Path tempDir;

    private String previousConfigDir;
    private boolean configDirOverridden;

    @AfterEach
    void restoreConfigDirProperty() {
        if (!configDirOverridden) {
            return;
        }
        if (previousConfigDir == null) {
            System.clearProperty(RuntimeFiles.CONFIG_DIR_PROPERTY);
        } else {
            System.setProperty(RuntimeFiles.CONFIG_DIR_PROPERTY, previousConfigDir);
        }
    }

    @Test
    @DisplayName("读取插件 properties 并作为低优先级兜底绑定")
    void loadsPluginPropertiesAsLowPrecedenceFallback() throws IOException {
        Path configDir = useTempConfigDir();
        Path pluginDir = configDir.resolve(RuntimeFiles.PLUGIN_CONFIG_DIR);
        Files.createDirectories(pluginDir);
        Files.writeString(pluginDir.resolve("notification.properties"), String.join("\n",
                NotificationConfigKeys.scenarioEnabledKey("run-summary") + "=false",
                NotificationConfigKeys.scenarioEnabledKey("run-failed") + "=false",
                "server.port=1234",
                ""), StandardCharsets.UTF_8);

        MapPropertySource pluginSource = PluginConfigPropertySourceLoader.load().orElseThrow();
        assertThat(pluginSource.getProperty(NotificationConfigKeys.scenarioEnabledKey("run-summary")))
                .isEqualTo("false");
        assertThat(pluginSource.getProperty("server.port")).isNull();

        MutablePropertySources sources = new MutablePropertySources();
        sources.addLast(new MapPropertySource("yaml", Map.of(
                NotificationConfigKeys.scenarioEnabledKey("run-summary"), "true")));
        sources.addLast(pluginSource);
        NotificationConfig config = new Binder(ConfigurationPropertySources.from(sources))
                .bind("notification", Bindable.of(NotificationConfig.class))
                .orElseGet(NotificationConfig::new);

        assertThat(config.isScenarioEnabled("run-summary")).isTrue();
        assertThat(config.isScenarioEnabled("run-failed")).isFalse();
    }

    @Test
    @DisplayName("缺少插件配置目录时不创建属性源")
    void missingPluginConfigDirectoryReturnsEmpty() {
        useTempConfigDir();

        assertThat(PluginConfigPropertySourceLoader.load()).isEmpty();
    }

    @Test
    @DisplayName("启动期通过 spring.factories 注册插件配置属性源")
    void springFactoriesRegistersEnvironmentPostProcessor() throws IOException {
        Enumeration<URL> resources = Thread.currentThread().getContextClassLoader()
                .getResources("META-INF/spring.factories");
        StringBuilder text = new StringBuilder();
        while (resources.hasMoreElements()) {
            URL resource = resources.nextElement();
            try (InputStream input = resource.openStream()) {
                text.append(new String(input.readAllBytes(), StandardCharsets.UTF_8)).append('\n');
            }
        }
        assertThat(text.toString()).contains(PluginConfigEnvironmentPostProcessor.class.getName());
    }

    private Path useTempConfigDir() {
        previousConfigDir = System.getProperty(RuntimeFiles.CONFIG_DIR_PROPERTY);
        configDirOverridden = true;
        Path configDir = tempDir.resolve("config");
        System.setProperty(RuntimeFiles.CONFIG_DIR_PROPERTY, configDir.toString());
        return configDir;
    }
}
