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
import org.springframework.core.env.StandardEnvironment;
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

    private static final String FAKE_CREDENTIAL = "fixture-credential-7f4c2a91";

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
    @DisplayName("普通插件 properties 作为权威值绑定且凭证键不进入父属性源")
    void loadsOrdinaryPluginPropertiesWithoutCredentials() throws IOException {
        Path configDir = useTempConfigDir();
        Path pluginDir = configDir.resolve(RuntimeFiles.PLUGIN_CONFIG_DIR);
        Files.createDirectories(pluginDir);
        Files.writeString(pluginDir.resolve("notification.properties"), String.join("\n",
                NotificationConfigKeys.scenarioEnabledKey("run-summary") + "=false",
                NotificationConfigKeys.scenarioEnabledKey("run-failed") + "=false",
                "notification.api-key=" + FAKE_CREDENTIAL,
                "server.port=1234",
                ""), StandardCharsets.UTF_8);

        MapPropertySource pluginSource = PluginConfigPropertySourceLoader.load().orElseThrow();
        assertThat(pluginSource.getProperty(NotificationConfigKeys.scenarioEnabledKey("run-summary")))
                .isEqualTo("false");
        assertThat(pluginSource.getProperty("server.port")).isNull();
        assertThat(pluginSource.getProperty("notification.api-key")).isNull();

        MutablePropertySources sources = new MutablePropertySources();
        sources.addLast(pluginSource);
        sources.addLast(new MapPropertySource("yaml", Map.of(
                NotificationConfigKeys.scenarioEnabledKey("run-summary"), "true")));
        NotificationConfig config = new Binder(ConfigurationPropertySources.from(sources))
                .bind("notification", Bindable.of(NotificationConfig.class))
                .orElseGet(NotificationConfig::new);

        assertThat(config.isScenarioEnabled("run-summary")).isFalse();
        assertThat(config.isScenarioEnabled("run-failed")).isFalse();
    }

    @Test
    @DisplayName("缺少插件配置目录时不创建属性源")
    void missingPluginConfigDirectoryReturnsEmpty() {
        useTempConfigDir();

        assertThat(PluginConfigPropertySourceLoader.load()).isEmpty();
    }

    @Test
    @DisplayName("启动属性源以插件 properties 覆盖旧 YAML 且保留命令行优先级")
    void environmentPostProcessorUsesAuthoritativePluginValueBelowExternalOverrides() throws IOException {
        Path configDir = useTempConfigDir();
        Path pluginDir = configDir.resolve(RuntimeFiles.PLUGIN_CONFIG_DIR);
        Files.createDirectories(pluginDir);
        Files.writeString(pluginDir.resolve("fixture.properties"),
                "fixture.mode=plugin\n", StandardCharsets.UTF_8);
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addLast(new MapPropertySource("yaml", Map.of(
                "fixture.mode", "yaml")));

        new PluginConfigEnvironmentPostProcessor().postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("fixture.mode")).isEqualTo("plugin");
        environment.getPropertySources().addFirst(new MapPropertySource("commandLineArgs", Map.of(
                "fixture.mode", "command")));
        assertThat(environment.getProperty("fixture.mode")).isEqualTo("command");
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
