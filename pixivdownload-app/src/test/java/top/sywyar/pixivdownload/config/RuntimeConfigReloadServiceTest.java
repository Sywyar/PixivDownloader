package top.sywyar.pixivdownload.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.appconfig.MultiModeConfig;
import top.sywyar.pixivdownload.core.narration.NarrationTtsConfig;
import top.sywyar.pixivdownload.core.notification.NotificationConfig;
import top.sywyar.pixivdownload.maintenance.MaintenanceProperties;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginLifecycleService;
import top.sywyar.pixivdownload.plugin.runtime.context.PluginApplicationContextFactory;
import top.sywyar.pixivdownload.plugin.runtime.context.PluginContextModule;
import top.sywyar.pixivdownload.setup.SetupProperties;
import top.sywyar.pixivdownload.setup.guest.GuestInviteConfig;
import top.sywyar.pixivdownload.update.UpdateConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@DisplayName("运行期配置热重载")
class RuntimeConfigReloadServiceTest {

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
            serveContext(lifecycleService, "fixture", child);

            RuntimeConfigReloadService service = newService(provider(lifecycleService), new StandardEnvironment());

            RuntimeConfigReloadService.ReloadResult result =
                    service.reloadHotConfig(List.of("fixture.endpoint"));

            assertThat(pluginConfig.getEndpoint()).isEqualTo("new-value");
            assertThat(result.appliedKeys()).containsExactly("fixture.endpoint");
        } finally {
            child.close();
        }
    }

    @Test
    @DisplayName("首次热重载会把新宿主配置源置于子 context 的启动配置之前")
    void firstReloadOverridesInheritedStartupConfigInActivePluginContext() throws IOException {
        Path configDir = useTempConfigDir();
        Files.createDirectories(configDir);
        Path configPath = configDir.resolve(RuntimeFiles.CONFIG_YAML);
        Files.writeString(configPath,
                "guest-invite.tts-request-limit-minute: 7\n", StandardCharsets.UTF_8);

        PluginLifecycleService lifecycleService = mock(PluginLifecycleService.class);
        StandardEnvironment parentEnvironment = new StandardEnvironment();
        parentEnvironment.getPropertySources().addLast(new MapPropertySource(
                "fixtureStartupConfig", Map.of("guest-invite.tts-request-limit-minute", "30")));
        RuntimeConfigReloadService service = newService(provider(lifecycleService), parentEnvironment);

        try (AnnotationConfigApplicationContext parent = new AnnotationConfigApplicationContext()) {
            parent.setEnvironment(parentEnvironment);
            parent.refresh();
            PluginApplicationContextFactory factory = new PluginApplicationContextFactory();
            PluginContextModule module = new PluginContextModule(
                    "fixture", getClass().getClassLoader(), List.of(FixtureGuestInviteConfiguration.class));
            var child = factory.create(parent, module);
            try {
                FixtureGuestInviteConfig pluginConfig = child.getBean(FixtureGuestInviteConfig.class);
                assertThat(pluginConfig.getTtsRequestLimitMinute()).isEqualTo(30);
                serveContext(lifecycleService, "fixture", child);

                RuntimeConfigReloadService.ReloadResult result = service.reloadHotConfig();

                assertThat(pluginConfig.getTtsRequestLimitMinute()).isEqualTo(7);
                assertThat(child.getEnvironment().getProperty(
                        "guest-invite.tts-request-limit-minute")).isEqualTo("7");
                assertThat(result.appliedKeys())
                        .contains("guest-invite.tts-request-limit-minute");
            } finally {
                child.close();
            }
        }
    }

    @Test
    @DisplayName("无参热重载会刷新活动子 context 的宿主配置源并重绑插件镜像")
    void reloadsAppliedHostKeysIntoActivePluginContext() throws IOException {
        Path configDir = useTempConfigDir();
        Files.createDirectories(configDir);
        Path configPath = configDir.resolve(RuntimeFiles.CONFIG_YAML);
        Files.writeString(configPath,
                "guest-invite.tts-request-limit-minute: 30\n", StandardCharsets.UTF_8);

        PluginLifecycleService lifecycleService = mock(PluginLifecycleService.class);
        when(lifecycleService.servingPluginIds()).thenReturn(Set.of());
        StandardEnvironment parentEnvironment = new StandardEnvironment();
        RuntimeConfigReloadService service = newService(provider(lifecycleService), parentEnvironment);
        service.reloadHotConfig();

        try (AnnotationConfigApplicationContext parent = new AnnotationConfigApplicationContext()) {
            parent.setEnvironment(parentEnvironment);
            parent.refresh();
            PluginApplicationContextFactory factory = new PluginApplicationContextFactory();
            PluginContextModule module = new PluginContextModule(
                    "fixture", getClass().getClassLoader(), List.of(FixtureGuestInviteConfiguration.class));
            var child = factory.create(parent, module);
            try {
                FixtureGuestInviteConfig pluginConfig = child.getBean(FixtureGuestInviteConfig.class);
                assertThat(pluginConfig.getTtsRequestLimitMinute()).isEqualTo(30);
                serveContext(lifecycleService, "fixture", child);
                Files.writeString(configPath,
                        "guest-invite.tts-request-limit-minute: 7\n", StandardCharsets.UTF_8);

                RuntimeConfigReloadService.ReloadResult result = service.reloadHotConfig();

                assertThat(child.getBean(FixtureGuestInviteConfig.class)).isSameAs(pluginConfig);
                assertThat(pluginConfig.getTtsRequestLimitMinute()).isEqualTo(7);
                assertThat(child.getEnvironment().getProperty(
                        "guest-invite.tts-request-limit-minute")).isEqualTo("7");
                assertThat(result.appliedKeys())
                        .contains("guest-invite.tts-request-limit-minute");
            } finally {
                child.close();
            }
        }
    }

    @Test
    @DisplayName("热重载从活动 Environment 绑定并保持命令行、系统属性与环境变量优先级")
    void reloadBindsFromActiveEnvironmentWithStandardPrecedence() throws IOException {
        Path configDir = useTempConfigDir();
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve(RuntimeFiles.CONFIG_YAML), """
                proxy.host: yaml-host
                proxy.port: 7000
                ssl.domain: yaml.example
                """, StandardCharsets.UTF_8);
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("commandLineArgs", Map.of(
                "proxy.host", "command-host")));
        environment.getPropertySources().replace(
                StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
                new MapPropertySource(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, Map.of(
                        "proxy.port", "7100")));
        environment.getPropertySources().replace(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        Map.of("SSL_DOMAIN", "environment.example")));
        ProxyConfig proxyConfig = new ProxyConfig();
        SslConfig sslConfig = new SslConfig();
        RuntimeConfigReloadService service = newService(
                RuntimeConfigReloadServiceTest.<PluginLifecycleService>provider(null),
                environment, proxyConfig, sslConfig);

        service.reloadHotConfig();

        assertThat(proxyConfig.getHost()).isEqualTo("command-host");
        assertThat(proxyConfig.getPort()).isEqualTo(7100);
        assertThat(sslConfig.getDomain()).isEqualTo("environment.example");
        assertThat(environment.getProperty("proxy.host")).isEqualTo("command-host");
        assertThat(environment.getProperty("proxy.port")).isEqualTo("7100");
        assertThat(environment.getProperty("ssl.domain")).isEqualTo("environment.example");
    }

    @Test
    @DisplayName("热重载后的插件新子 context 读取活动 Environment 的同一有效值")
    void restartedPluginContextReadsReloadedEnvironmentValue() throws IOException {
        Path configDir = useTempConfigDir();
        Files.createDirectories(configDir.resolve(RuntimeFiles.PLUGIN_CONFIG_DIR));
        Files.writeString(configDir.resolve(RuntimeFiles.CONFIG_YAML),
                "download.user-flat-folder: false\n", StandardCharsets.UTF_8);
        Path pluginConfigPath = configDir.resolve(RuntimeFiles.PLUGIN_CONFIG_DIR).resolve("fixture.properties");
        Files.writeString(pluginConfigPath, "fixture.endpoint=before-reload\n", StandardCharsets.UTF_8);
        StandardEnvironment environment = new StandardEnvironment();
        RuntimeConfigReloadService service = newService(
                RuntimeConfigReloadServiceTest.<PluginLifecycleService>provider(null), environment);
        service.reloadHotConfig();

        try (AnnotationConfigApplicationContext parent = new AnnotationConfigApplicationContext()) {
            parent.setEnvironment(environment);
            parent.refresh();
            PluginApplicationContextFactory factory = new PluginApplicationContextFactory();
            PluginContextModule module = new PluginContextModule(
                    "fixture", getClass().getClassLoader(), List.of(FixturePluginConfiguration.class));
            var firstChild = factory.create(parent, module);
            assertThat(firstChild.getBean(FixturePluginConfig.class).getEndpoint()).isEqualTo("before-reload");
            firstChild.close();

            Files.writeString(pluginConfigPath, "fixture.endpoint=after-reload\n", StandardCharsets.UTF_8);
            service.reloadHotConfig(List.of("fixture.endpoint"));

            var restartedChild = factory.create(parent, module);
            try {
                assertThat(restartedChild.getBean(FixturePluginConfig.class).getEndpoint())
                        .isEqualTo("after-reload");
                assertThat(restartedChild.getEnvironment().getProperty("fixture.endpoint"))
                        .isEqualTo("after-reload");
            } finally {
                restartedChild.close();
            }
        }
    }

    @Test
    @DisplayName("热重载只向所属插件子 context 注入专用凭证")
    void reloadsCredentialIntoOwnerChildContextOnly() throws IOException {
        Path configDir = useTempConfigDir();
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve(RuntimeFiles.CONFIG_YAML),
                "download.user-flat-folder: false\n", StandardCharsets.UTF_8);
        new PluginCredentialStore().update("fixture", Map.of("fixture.api-key", FAKE_CREDENTIAL));
        FixturePluginConfig pluginConfig = new FixturePluginConfig();
        AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext();
        child.registerBean(FixturePluginConfig.class, () -> pluginConfig);
        child.refresh();
        try {
            PluginLifecycleService lifecycleService = mock(PluginLifecycleService.class);
            serveContext(lifecycleService, "fixture", child);
            StandardEnvironment parentEnvironment = new StandardEnvironment();
            RuntimeConfigReloadService service = newService(provider(lifecycleService), parentEnvironment);

            service.reloadHotConfig(List.of("fixture.api-key"));

            assertThat(pluginConfig.getApiKey()).isEqualTo(FAKE_CREDENTIAL);
            assertThat(child.getEnvironment().getProperty("fixture.api-key")).isEqualTo(FAKE_CREDENTIAL);
            assertThat(parentEnvironment.getProperty("fixture.api-key")).isNull();
        } finally {
            child.close();
        }
    }

    private RuntimeConfigReloadService newService(ObjectProvider<PluginLifecycleService> lifecycleService,
                                                  ConfigurableEnvironment environment) {
        return newService(lifecycleService, environment, new ProxyConfig(), new SslConfig());
    }

    private RuntimeConfigReloadService newService(ObjectProvider<PluginLifecycleService> lifecycleService,
                                                  ConfigurableEnvironment environment,
                                                  ProxyConfig proxyConfig,
                                                  SslConfig sslConfig) {
        return new RuntimeConfigReloadService(
                new DownloadConfig(),
                new MultiModeConfig(),
                new GuestInviteConfig(),
                new SetupProperties(),
                sslConfig,
                new MaintenanceProperties(),
                proxyConfig,
                new UpdateConfig(),
                new NarrationTtsConfig(),
                new NotificationConfig(),
                lifecycleService,
                environment,
                new PluginCredentialStore());
    }

    private static void serveContext(
            PluginLifecycleService lifecycleService,
            String pluginId,
            ConfigurableApplicationContext context) {
        when(lifecycleService.servingPluginIds()).thenReturn(Set.of(pluginId));
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<ConfigurableApplicationContext> operation = invocation.getArgument(1);
            operation.accept(context);
            return true;
        }).when(lifecycleService).withServingContext(eq(pluginId), any());
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
        private volatile String apiKey = "";

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(FixturePluginConfig.class)
    static class FixturePluginConfiguration {
    }

    @ConfigurationProperties(prefix = "guest-invite")
    public static class FixtureGuestInviteConfig {
        private volatile int ttsRequestLimitMinute = 30;

        public int getTtsRequestLimitMinute() {
            return ttsRequestLimitMinute;
        }

        public void setTtsRequestLimitMinute(int ttsRequestLimitMinute) {
            this.ttsRequestLimitMinute = ttsRequestLimitMinute;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(FixtureGuestInviteConfig.class)
    static class FixtureGuestInviteConfiguration {
    }
}
