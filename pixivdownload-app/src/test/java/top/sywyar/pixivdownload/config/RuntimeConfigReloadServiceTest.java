package top.sywyar.pixivdownload.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.appconfig.MultiModeConfig;
import top.sywyar.pixivdownload.core.narration.NarrationTtsConfig;
import top.sywyar.pixivdownload.core.notification.NotificationConfig;
import top.sywyar.pixivdownload.maintenance.MaintenanceProperties;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginLifecycleService;
import top.sywyar.pixivdownload.setup.SetupProperties;
import top.sywyar.pixivdownload.setup.guest.GuestInviteConfig;
import top.sywyar.pixivdownload.update.UpdateConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("运行期配置热重载")
class RuntimeConfigReloadServiceTest {

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
    @DisplayName("热重载会重绑插件配置 Bean 并返回插件字段 key")
    void reloadsPluginConfigurationPropertiesAndReportsKeys() throws IOException {
        Path configDir = useTempConfigDir();
        Files.createDirectories(configDir.resolve(RuntimeFiles.PLUGIN_CONFIG_DIR));
        Files.writeString(configDir.resolve(RuntimeFiles.CONFIG_YAML),
                "download.user-flat-folder: false\n", StandardCharsets.UTF_8);
        Files.writeString(configDir.resolve(RuntimeFiles.PLUGIN_CONFIG_DIR).resolve("fixture.properties"),
                "fixture.endpoint=new-value\n", StandardCharsets.UTF_8);

        FixturePluginConfig pluginConfig = new FixturePluginConfig();
        pluginConfig.setEndpoint("old-value");
        AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext();
        child.registerBean(FixturePluginConfig.class, () -> pluginConfig);
        child.refresh();
        try {
            PluginLifecycleService lifecycleService = mock(PluginLifecycleService.class);
            when(lifecycleService.servingPluginIds()).thenReturn(Set.of("fixture"));
            when(lifecycleService.contextFor("fixture")).thenReturn(Optional.of(child));

            RuntimeConfigReloadService service = newService(provider(lifecycleService));

            RuntimeConfigReloadService.ReloadResult result =
                    service.reloadHotConfig(List.of("fixture.endpoint"));

            assertThat(pluginConfig.getEndpoint()).isEqualTo("new-value");
            assertThat(result.appliedKeys()).containsExactly("fixture.endpoint");
        } finally {
            child.close();
        }
    }

    private RuntimeConfigReloadService newService(ObjectProvider<PluginLifecycleService> lifecycleService) {
        return new RuntimeConfigReloadService(
                new DownloadConfig(),
                new MultiModeConfig(),
                new GuestInviteConfig(),
                new SetupProperties(),
                new SslConfig(),
                new MaintenanceProperties(),
                new ProxyConfig(),
                new UpdateConfig(),
                new NarrationTtsConfig(),
                new NotificationConfig(),
                lifecycleService);
    }

    private Path useTempConfigDir() {
        previousConfigDir = System.getProperty(RuntimeFiles.CONFIG_DIR_PROPERTY);
        configDirOverridden = true;
        Path configDir = tempDir.resolve("config");
        System.setProperty(RuntimeFiles.CONFIG_DIR_PROPERTY, configDir.toString());
        return configDir;
    }

    private static <T> ObjectProvider<T> provider(T bean) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return bean;
            }

            @Override
            public T getIfAvailable() {
                return bean;
            }

            @Override
            public T getObject() {
                return bean;
            }
        };
    }

    @ConfigurationProperties(prefix = "fixture")
    public static class FixturePluginConfig {
        private volatile String endpoint = "";

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }
    }
}
