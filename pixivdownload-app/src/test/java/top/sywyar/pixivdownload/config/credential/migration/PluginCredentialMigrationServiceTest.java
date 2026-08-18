package top.sywyar.pixivdownload.config.credential.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.PropertyAccessorFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.plugin.api.storage.RuntimePathProvider;
import top.sywyar.pixivdownload.config.credential.PluginCredentialPropertySourceService;
import top.sywyar.pixivdownload.config.credential.PluginCredentialStore;
import top.sywyar.pixivdownload.gui.config.ConfigFileEditor;
import top.sywyar.pixivdownload.gui.config.PropertiesConfigFileEditor;
import top.sywyar.pixivdownload.i18n.CatalogLocaleBundlePolicy;
import top.sywyar.pixivdownload.i18n.LocaleBundlePolicy;
import top.sywyar.pixivdownload.i18n.LocaleCatalog;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.plugin.PluginToggleProperties;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigEffect;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldContribution;
import top.sywyar.pixivdownload.plugin.api.gui.GuiConfigFieldType;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpClient;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpClientFactory;
import top.sywyar.pixivdownload.plugin.api.http.websocket.OutboundWebSocketClient;
import top.sywyar.pixivdownload.plugin.api.http.websocket.OutboundWebSocketClientFactory;
import top.sywyar.pixivdownload.plugin.api.notification.ImmutableNotificationTemplateCatalog;
import top.sywyar.pixivdownload.plugin.api.notification.NotificationTemplateCatalog;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivPluginProvider;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentityResolver;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.runtime.context.PluginApplicationContextFactory;
import top.sywyar.pixivdownload.plugin.runtime.context.PluginContextModule;
import top.sywyar.pixivdownload.plugin.runtime.stream.PluginStreamRegistry;
import top.sywyar.pixivdownload.plugin.runtime.task.PluginRuntimeTaskRegistry;
import top.sywyar.pixivdownload.push.PushDispatcher;
import top.sywyar.pixivdownload.setup.UserDisplayNameProvider;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("旧版插件凭证迁移")
class PluginCredentialMigrationServiceTest {

    private static final String HOST_SSL_KEY = "server.ssl.key-store-password";
    private static final String HOST_SSL_VALUE = "host-ssl-password";
    private static final String AI_KEY = "ai.api-key";
    private static final String HISTORICAL_ORACLE_RESOURCE =
            "/fixtures/plugin-credentials/historical-credential-oracle.tsv";
    private static final List<String> OFFICIAL_CREDENTIAL_PLUGIN_CLASS_PROPERTIES = List.of(
            "ai.plugin.classes",
            "mail.plugin.classes",
            "tts.plugin.classes",
            "push.plugin.classes");

    /**
     * Hand-written synthetic contribution fixture for migration mechanics. Historical coverage is
     * guarded separately against dynamically loaded official plugin features.
     */
    private static final Map<String, Set<String>> SYNTHETIC_CREDENTIAL_DEFINITIONS = Map.of(
            "ai", Set.of(AI_KEY),
            "mail", Set.of("mail.password"),
            "tts", Set.of(
                    "narration-tts.voxcpm.api-key",
                    "narration-tts.mimo.api-key",
                    "narration-tts.cosyvoice.api-key",
                    "narration-tts.fish.api-key",
                    "narration-tts.minimax.api-key",
                    "narration-tts.elevenlabs.api-key",
                    "narration-tts.qwen.api-key",
                    "narration-tts.doubao.access-token"),
            "push", Set.of(
                    "push.bark.device-key",
                    "push.dingtalk.access-token",
                    "push.dingtalk.secret",
                    "push.telegram.bot-token",
                    "push.feishu.webhook-key",
                    "push.feishu.secret",
                    "push.wecom.key",
                    "push.pushplus.token",
                    "push.serverchan.send-key",
                    "push.webhook.url"));

    @TempDir
    Path tempDir;

    private String previousConfigDirectory;

    @BeforeEach
    void configureRuntimeDirectory() {
        previousConfigDirectory = System.getProperty(RuntimeFiles.CONFIG_DIR_PROPERTY);
        System.setProperty(
                RuntimeFiles.CONFIG_DIR_PROPERTY,
                tempDir.resolve("config").toString());
    }

    @AfterEach
    void restoreRuntimeDirectory() {
        if (previousConfigDirectory == null) {
            System.clearProperty(RuntimeFiles.CONFIG_DIR_PROPERTY);
        } else {
            System.setProperty(RuntimeFiles.CONFIG_DIR_PROPERTY, previousConfigDirectory);
        }
    }

    @Test
    @DisplayName("v1.10.0 基线没有插件凭证且迁移保持配置字节不变")
    void preservesV110FixtureWithoutPluginCredentials() throws Exception {
        HistoricalCredentialOracle oracle = historicalOracle("v1.10.0");
        assertFrozenOracle(
                oracle,
                "433762a37443c111b37f43825e71854c7421931f",
                0);
        copyHistoricalFixture("v1.10.0");
        PluginRegistry registry = new PluginRegistry(syntheticFixturePlugins());
        byte[] yamlBefore = Files.readAllBytes(RuntimeFiles.resolveConfigYamlPath());
        StandardEnvironment environment = legacyEnvironment(
                readHistoricalCredentialValues(oracle));
        PluginCredentialMigrationService service = service(
                registry,
                new PluginCredentialStore(),
                environment);

        service.migrateAll();

        assertThat(Files.readAllBytes(RuntimeFiles.resolveConfigYamlPath()))
                .isEqualTo(yamlBefore);
        assertThat(environment.getProperty(HOST_SSL_KEY)).isEqualTo(HOST_SSL_VALUE);
        assertCredentialDirectoryHasNoFiles();
    }

    @Test
    @DisplayName("v1.11.4 基线精确迁移 AI 与邮件两个插件凭证")
    void migratesFrozenV1114CredentialBaseline() throws Exception {
        HistoricalCredentialOracle oracle = historicalOracle("v1.11.4");
        assertFrozenOracle(
                oracle,
                "2c761573fdfb475e5b7f17478b9d569eae4dd748",
                2);
        copyHistoricalFixture("v1.11.4");
        Set<String> historicalKeys = oracle.keys();
        assertThat(historicalKeys)
                .containsExactlyInAnyOrder("ai.api-key", "mail.password");
        PluginRegistry registry = new PluginRegistry(syntheticFixturePlugins());
        Map<String, Map<String, String>> historicalValues =
                readHistoricalCredentialValues(oracle);
        StandardEnvironment environment = legacyEnvironment(historicalValues);
        PluginCredentialStore store = new PluginCredentialStore();

        service(
                registry,
                store,
                environment).migrateAll();

        for (Map.Entry<String, Map<String, String>> entry : historicalValues.entrySet()) {
            assertThat(store.readAll(entry.getKey())).containsExactlyInAnyOrderEntriesOf(
                    entry.getValue());
        }
        assertThat(yamlEditor().readAll(historicalKeys)).isEmpty();
        assertThat(yamlEditor().read(HOST_SSL_KEY)).isEqualTo(HOST_SSL_VALUE);
        assertThat(yamlEditor().read("ai.model")).isEqualTo("legacy-ai-model");
        assertThat(yamlEditor().read("mail.host")).isEqualTo("smtp.example.test");
        for (String key : historicalKeys) {
            assertThat(environment.getProperty(key)).isEmpty();
        }
    }

    @Test
    @DisplayName("headless 启动迁移 v1.13.1 全部插件凭证并保持二次运行字节不变")
    void migratesCompleteV113FixtureOnceAtHeadlessStartup() throws Exception {
        HistoricalCredentialOracle oracle = historicalOracle("v1.13.1");
        assertFrozenOracle(
                oracle,
                "bea026765a35aef8e58dde56f4ae45e7663c1048",
                20);
        copyHistoricalFixture("v1.13.1");
        PluginToggleProperties toggles = new PluginToggleProperties();
        toggles.setEnabled("tts", false);
        PluginRegistry registry =
                new PluginRegistry(syntheticFixturePlugins(), toggles);
        assertThat(registry.registeredPlugins())
                .extracting(PluginRegistry.RegisteredPlugin::id)
                .doesNotContain("tts");
        assertThat(registry.allRegisteredPlugins())
                .extracting(PluginRegistry.RegisteredPlugin::id)
                .contains("tts");

        Map<String, Map<String, String>> historicalValues =
                readHistoricalCredentialValues(oracle);
        StandardEnvironment environment = legacyEnvironment(historicalValues);
        PluginCredentialStore store = new PluginCredentialStore();
        PluginCredentialMigrationService service = service(registry, store, environment);
        PluginCredentialMigrationCoordinator coordinator =
                new PluginCredentialMigrationCoordinator(service);

        coordinator.afterSingletonsInstantiated();

        for (Map.Entry<String, Map<String, String>> entry : historicalValues.entrySet()) {
            assertThat(store.readAll(entry.getKey())).containsAllEntriesOf(entry.getValue());
        }
        ConfigFileEditor yaml = yamlEditor();
        assertThat(yaml.readAll(oracle.keys())).isEmpty();
        assertThat(yaml.read(HOST_SSL_KEY)).isEqualTo(HOST_SSL_VALUE);
        assertThat(yaml.read("ai.model")).isEqualTo("legacy-ai-model");
        assertThat(yaml.read("mail.host")).isEqualTo("smtp.example.test");
        assertThat(yaml.read("narration-tts.engine")).isEqualTo("voxcpm");
        assertThat(yaml.read("push.enabled")).isEqualTo("true");
        for (String key : oracle.keys()) {
            assertThat(environment.getProperty(key)).isEmpty();
        }
        assertThat(environment.getProperty(HOST_SSL_KEY)).isEqualTo(HOST_SSL_VALUE);
        assertThat(environment.getPropertySources().iterator().next().getName())
                .isEqualTo(PluginCredentialEnvironmentMask.PROPERTY_SOURCE_NAME);

        byte[] yamlAfterFirstRun = Files.readAllBytes(RuntimeFiles.resolveConfigYamlPath());
        Map<String, byte[]> credentialsAfterFirstRun =
                credentialFileBytes(oracle.owners());

        coordinator.afterSingletonsInstantiated();

        assertThat(Files.readAllBytes(RuntimeFiles.resolveConfigYamlPath()))
                .isEqualTo(yamlAfterFirstRun);
        for (Map.Entry<String, byte[]> entry : credentialsAfterFirstRun.entrySet()) {
            assertThat(Files.readAllBytes(RuntimeFiles.resolvePluginCredentialPath(entry.getKey())))
                    .isEqualTo(entry.getValue());
        }
    }

    @Test
    @DisplayName("真实官方插件 contribution 覆盖全部冻结历史凭证定义")
    void realOfficialPluginContributionsCoverFrozenHistoricalOracle() throws Exception {
        try (LoadedOfficialCredentialPlugins loaded =
                     loadOfficialCredentialPlugins()) {
            assertThat(loaded.features())
                    .extracting(PixivFeaturePlugin::id)
                    .containsExactlyInAnyOrder("ai", "mail", "tts", "push");
            PluginRegistry registry = new PluginRegistry(loaded.features());
            PluginCredentialDefinitionResolver.Resolution resolution =
                    resolutionFor(registry);

            assertDefinitionsCover(historicalOracle("v1.10.0"), resolution);
            assertDefinitionsCover(historicalOracle("v1.11.4"), resolution);
            assertDefinitionsCover(historicalOracle("v1.13.1"), resolution);
        }
    }

    @Test
    @DisplayName("真实官方插件子上下文绑定自己的全部凭证并遮蔽其它 owner 与宿主凭证")
    void realOfficialPluginContextsBindOnlyTheirOwnCredentials() throws Exception {
        try (LoadedOfficialCredentialPlugins loaded =
                     loadOfficialCredentialPlugins()) {
            PluginRegistry registry = new PluginRegistry(loaded.features());
            PluginCredentialDefinitionResolver resolver =
                    new PluginCredentialDefinitionResolver(
                            registry, () -> Set.of(HOST_SSL_KEY));
            PluginCredentialDefinitionResolver.Resolution resolution =
                    resolver.resolveSnapshot();
            assertThat(resolution.failures()).isEmpty();
            assertThat(resolution.validDefinitions().keySet())
                    .containsExactlyInAnyOrder("ai", "mail", "tts", "push");
            assertThat(loaded.modules().keySet())
                    .containsExactlyInAnyOrderElementsOf(
                            resolution.validDefinitions().keySet());

            Map<String, Map<String, String>> credentialsByOwner =
                    currentOfficialCredentialValues(
                            resolution.validDefinitions());
            Map<String, String> allCredentials =
                    flattenCredentialValues(credentialsByOwner);
            LinkedHashMap<String, String> legacyYaml =
                    new LinkedHashMap<>(allCredentials);
            legacyYaml.put(HOST_SSL_KEY, HOST_SSL_VALUE);
            yamlEditor().writeAll(legacyYaml);

            LinkedHashMap<String, Object> legacyEnvironmentValues =
                    new LinkedHashMap<>();
            legacyEnvironmentValues.putAll(legacyYaml);
            StandardEnvironment environment = new StandardEnvironment();
            environment.getPropertySources().addLast(
                    new MapPropertySource(
                            "real-official-legacy-yaml",
                            legacyEnvironmentValues));
            PluginCredentialStore store = new PluginCredentialStore();
            PluginCredentialEnvironmentMask mask =
                    new PluginCredentialEnvironmentMask(environment);
            PluginCredentialMigrationService migration =
                    new PluginCredentialMigrationService(
                            resolver, store, mask);
            PluginCredentialPropertySourceService propertySources =
                    new PluginCredentialPropertySourceService(
                            store, resolver, migration, mask);
            PluginApplicationContextFactory contextFactory =
                    new PluginApplicationContextFactory(
                            propertySources::snapshotFor,
                            new PluginStreamRegistry(),
                            new PluginRuntimeTaskRegistry(),
                            this::officialPluginRuntimePaths,
                            null);

            migration.migrateAll();

            for (Map.Entry<String, Map<String, String>> entry
                    : credentialsByOwner.entrySet()) {
                assertThat(store.readAll(entry.getKey()))
                        .containsExactlyInAnyOrderEntriesOf(entry.getValue());
            }
            assertThat(yamlEditor().readAll(allCredentials.keySet()))
                    .isEmpty();
            assertThat(yamlEditor().read(HOST_SSL_KEY))
                    .isEqualTo(HOST_SSL_VALUE);
            for (String key : allCredentials.keySet()) {
                assertThat(environment.getProperty(key)).isEmpty();
            }
            assertThat(environment.getProperty(HOST_SSL_KEY))
                    .isEqualTo(HOST_SSL_VALUE);

            try (AnnotationConfigApplicationContext parent =
                         officialPluginParent(environment)) {
                for (Map.Entry<String, PluginContextModule> entry
                        : loaded.modules().entrySet()) {
                    String owner = entry.getKey();
                    ConfigurableApplicationContext child =
                            contextFactory.create(parent, entry.getValue());
                    try {
                        assertRealCredentialBindings(
                                child,
                                owner,
                                credentialsByOwner.get(owner),
                                allCredentials,
                                entry.getValue().classLoader());
                    } finally {
                        child.close();
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("专用存储优先于插件 properties 且插件 properties 优先于 YAML")
    void appliesCredentialThenPropertiesThenYamlPrecedence() throws Exception {
        String owner = "precedence";
        String storedKey = "precedence.stored-token";
        String propertyKey = "precedence.property-token";
        String yamlKey = "precedence.yaml-token";
        Set<String> keys = Set.of(storedKey, propertyKey, yamlKey);
        PluginCredentialStore store = new PluginCredentialStore();
        store.update(owner, Map.of(storedKey, "legacy-store-value"));
        PropertiesConfigFileEditor properties = pluginEditor(owner);
        properties.writeAll(Map.of(
                storedKey, "property-loses",
                propertyKey, "property-wins",
                "precedence.ordinary", "keep-property"));
        yamlEditor().writeAll(Map.of(
                storedKey, "yaml-loses",
                propertyKey, "yaml-also-loses",
                yamlKey, "yaml-wins",
                "precedence.ordinary-yaml", "keep-yaml"));

        PluginRegistry registry =
                new PluginRegistry(List.of(plugin(owner, keys)));
        service(registry, store, new StandardEnvironment()).migrateOwner(owner);

        assertThat(store.readAll(owner)).containsExactlyInAnyOrderEntriesOf(Map.of(
                storedKey, "legacy-store-value",
                propertyKey, "property-wins",
                yamlKey, "yaml-wins"));
        assertThat(properties.readAll(keys)).isEmpty();
        assertThat(properties.readAll(Set.of("precedence.ordinary")))
                .containsEntry("precedence.ordinary", "keep-property");
        assertThat(yamlEditor().readAll(keys)).isEmpty();
        assertThat(yamlEditor().read("precedence.ordinary-yaml")).isEqualTo("keep-yaml");
        assertThat(Files.readString(
                RuntimeFiles.resolvePluginCredentialPath(owner), StandardCharsets.UTF_8))
                .startsWith("format=")
                .doesNotContain(storedKey, "legacy-store-value");
    }

    @Test
    @DisplayName("插件 properties 空白时继续迁移 YAML 非空凭证并清理两个旧源")
    void skipsBlankPluginPropertyAndMigratesNonBlankYamlCredential() throws Exception {
        String owner = "blank-property";
        String key = "blank-property.api-key";
        InMemoryLegacySources sources = new InMemoryLegacySources();
        sources.pluginValues.put(
                owner,
                new LinkedHashMap<>(Map.of(key, "   ")));
        sources.yamlValues.put(key, "yaml-secret");
        PluginRegistry registry =
                new PluginRegistry(List.of(plugin(owner, Set.of(key))));
        PluginCredentialStore store = new PluginCredentialStore();
        PluginCredentialDefinitionResolver resolver =
                new PluginCredentialDefinitionResolver(registry, Set::of);
        PluginCredentialMigrationService migration =
                new PluginCredentialMigrationService(
                        resolver,
                        store,
                        new PluginCredentialEnvironmentMask(new StandardEnvironment()),
                        sources);

        migration.migrateOwner(owner);

        assertThat(store.readAll(owner)).containsEntry(key, "yaml-secret");
        assertThat(sources.pluginValues.get(owner)).doesNotContainKey(key);
        assertThat(sources.yamlValues).doesNotContainKey(key);
    }

    @Test
    @DisplayName("旧源读取和清理完成前同 owner 的并发保存保持阻塞且最终更新不丢失")
    void serializesConcurrentOwnerUpdateAcrossLegacyMigration() throws Exception {
        String owner = "migration-lock";
        String key = "migration-lock.api-key";
        InMemoryLegacySources delegate = new InMemoryLegacySources();
        delegate.pluginValues.put(
                owner,
                new LinkedHashMap<>(Map.of(key, "legacy-secret")));
        BlockingLegacySources sources = new BlockingLegacySources(delegate);
        PluginRegistry registry =
                new PluginRegistry(List.of(plugin(owner, Set.of(key))));
        PluginCredentialStore migrationStore = new PluginCredentialStore();
        PluginCredentialStore concurrentStore = new PluginCredentialStore();
        PluginCredentialDefinitionResolver resolver =
                new PluginCredentialDefinitionResolver(registry, Set::of);
        PluginCredentialMigrationService migration =
                new PluginCredentialMigrationService(
                        resolver,
                        migrationStore,
                        new PluginCredentialEnvironmentMask(
                                new StandardEnvironment()),
                        sources);

        CountDownLatch concurrentCallStarted = new CountDownLatch(1);
        AtomicReference<Thread> concurrentThread = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Void> migrationFuture = executor.submit(() -> {
                migration.migrateOwner(owner);
                return null;
            });
            assertThat(sources.firstPluginReadStarted.await(
                    5, TimeUnit.SECONDS)).isTrue();

            Future<Void> concurrentFuture = executor.submit(() -> {
                concurrentThread.set(Thread.currentThread());
                concurrentCallStarted.countDown();
                concurrentStore.update(
                        owner, Map.of(key, "concurrent-secret"));
                return null;
            });
            assertThat(concurrentCallStarted.await(
                    5, TimeUnit.SECONDS)).isTrue();
            awaitBlockedCredentialUpdate(concurrentThread.get());

            sources.releaseFirstPluginRead.countDown();
            migrationFuture.get(5, TimeUnit.SECONDS);
            concurrentFuture.get(5, TimeUnit.SECONDS);

            assertThat(concurrentStore.readAll(owner))
                    .containsExactly(Map.entry(key, "concurrent-secret"));
            assertThat(delegate.pluginValues.get(owner)).doesNotContainKey(key);
        } finally {
            sources.releaseFirstPluginRead.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("AI 后安装前后其它真实子上下文均不能读取其旧凭证")
    void isolatesLegacyCredentialBeforeAndAfterOwnerIsInstalled() throws Exception {
        yamlEditor().writeAll(Map.of(
                AI_KEY, "installed-later-secret",
                HOST_SSL_KEY, HOST_SSL_VALUE));
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addLast(
                new MapPropertySource("legacy-yaml", Map.of(
                        AI_KEY, "installed-later-secret",
                        HOST_SSL_KEY, HOST_SSL_VALUE)));
        PluginRegistry registry =
                new PluginRegistry(List.of(plugin("other", Set.of())));
        PluginCredentialStore store = new PluginCredentialStore();
        PluginCredentialDefinitionResolver resolver =
                new PluginCredentialDefinitionResolver(registry, () -> Set.of(HOST_SSL_KEY));
        PluginCredentialEnvironmentMask mask =
                new PluginCredentialEnvironmentMask(environment);
        PluginCredentialMigrationService migration =
                new PluginCredentialMigrationService(resolver, store, mask);
        PluginCredentialPropertySourceService propertySources =
                new PluginCredentialPropertySourceService(
                        store, resolver, migration, mask);
        PluginApplicationContextFactory contextFactory =
                new PluginApplicationContextFactory(
                        propertySources::snapshotFor,
                        new PluginStreamRegistry(),
                        new PluginRuntimeTaskRegistry());

        migration.migrateAll();
        assertThat(yamlEditor().read(AI_KEY)).isEqualTo("installed-later-secret");
        assertThat(yamlEditor().read(HOST_SSL_KEY)).isEqualTo(HOST_SSL_VALUE);
        assertThat(environment.getProperty(AI_KEY)).isEmpty();
        assertThat(environment.getProperty(HOST_SSL_KEY)).isEqualTo(HOST_SSL_VALUE);

        try (AnnotationConfigApplicationContext parent =
                     new AnnotationConfigApplicationContext()) {
            parent.setEnvironment(environment);
            parent.refresh();
            ConfigurableApplicationContext other = contextFactory.create(
                    parent,
                    credentialBindingModule("other"));
            try {
                assertAiCredentialHidden(other);
                assertHostCredentialHidden(other);

                registry.register(plugin(
                        "ai",
                        SYNTHETIC_CREDENTIAL_DEFINITIONS.get("ai")));
                migration.migrateOwner("ai");

                assertThat(store.readAll("ai"))
                        .containsEntry(AI_KEY, "installed-later-secret");
                assertThat(yamlEditor().read(AI_KEY)).isNull();
                assertThat(yamlEditor().read(HOST_SSL_KEY)).isEqualTo(HOST_SSL_VALUE);
                assertThat(parent.getEnvironment().getProperty(AI_KEY)).isEmpty();
                assertThat(parent.getEnvironment().getProperty(HOST_SSL_KEY))
                        .isEqualTo(HOST_SSL_VALUE);

                ConfigurableApplicationContext ai = contextFactory.create(
                        parent,
                        credentialBindingModule("ai"));
                try {
                    assertAiCredentialVisible(ai, "installed-later-secret");
                    assertHostCredentialHidden(ai);
                    assertAiCredentialHidden(other);
                    assertHostCredentialHidden(other);
                } finally {
                    ai.close();
                }
            } finally {
                other.close();
            }
        }
    }

    @Test
    @DisplayName("损坏信封时不回退覆盖目标并保留全部旧来源")
    void preservesEveryLegacySourceWhenEncryptedTargetIsCorrupt() throws Exception {
        String owner = "corrupt";
        String key = "corrupt.api-key";
        Path credentialPath = RuntimeFiles.resolvePluginCredentialPath(owner);
        Files.writeString(credentialPath, String.join("\n",
                "format=pixivdownload-plugin-credentials-v1",
                "key-id=unknown",
                "nonce=invalid",
                "ciphertext=invalid",
                ""), StandardCharsets.UTF_8);
        pluginEditor(owner).writeAll(Map.of(key, "property-secret"));
        yamlEditor().write(key, "yaml-secret");
        byte[] credentialBefore = Files.readAllBytes(credentialPath);
        byte[] propertiesBefore =
                Files.readAllBytes(RuntimeFiles.resolvePluginConfigPath(owner, "properties"));
        byte[] yamlBefore = Files.readAllBytes(RuntimeFiles.resolveConfigYamlPath());
        StandardEnvironment environment = new StandardEnvironment();
        PluginCredentialMigrationService service = service(
                new PluginRegistry(List.of(plugin(owner, Set.of(key)))),
                new PluginCredentialStore(),
                environment);

        assertThatThrownBy(() -> service.migrateOwner(owner))
                .isInstanceOf(IOException.class);

        assertThat(Files.readAllBytes(credentialPath)).isEqualTo(credentialBefore);
        assertThat(Files.readAllBytes(RuntimeFiles.resolvePluginConfigPath(owner, "properties")))
                .isEqualTo(propertiesBefore);
        assertThat(Files.readAllBytes(RuntimeFiles.resolveConfigYamlPath())).isEqualTo(yamlBefore);
        assertThat(environment.getProperty(key)).isEmpty();
    }

    @Test
    @DisplayName("批量迁移中一个 owner 信封损坏不阻断健康 owner")
    void migrateAllContinuesAfterOneOwnerEnvelopeIsCorrupt() throws Exception {
        String brokenOwner = "broken-envelope";
        String brokenKey = "broken-envelope.api-key";
        String healthyOwner = "healthy-envelope";
        String healthyKey = "healthy-envelope.token";
        Path brokenCredentialPath =
                RuntimeFiles.resolvePluginCredentialPath(brokenOwner);
        Files.writeString(brokenCredentialPath, String.join("\n",
                "format=pixivdownload-plugin-credentials-v1",
                "key-id=unknown",
                "nonce=invalid",
                "ciphertext=invalid",
                ""), StandardCharsets.UTF_8);
        byte[] brokenCredentialBefore = Files.readAllBytes(brokenCredentialPath);
        yamlEditor().writeAll(Map.of(
                brokenKey, "broken-legacy-secret",
                healthyKey, "healthy-legacy-secret"));
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addLast(new MapPropertySource(
                "legacy-yaml",
                Map.of(
                        brokenKey, "broken-legacy-secret",
                        healthyKey, "healthy-legacy-secret")));
        PluginCredentialStore store = new PluginCredentialStore();
        PluginRegistry registry = new PluginRegistry(List.of(
                plugin(brokenOwner, Set.of(brokenKey)),
                plugin(healthyOwner, Set.of(healthyKey))));

        service(registry, store, environment).migrateAll();

        assertThat(Files.readAllBytes(brokenCredentialPath))
                .isEqualTo(brokenCredentialBefore);
        assertThat(yamlEditor().read(brokenKey))
                .isEqualTo("broken-legacy-secret");
        assertThat(store.readAll(healthyOwner))
                .containsExactly(Map.entry(healthyKey, "healthy-legacy-secret"));
        assertThat(yamlEditor().read(healthyKey)).isNull();
        assertThat(environment.getProperty(brokenKey)).isEmpty();
        assertThat(environment.getProperty(healthyKey)).isEmpty();
    }

    @Test
    @DisplayName("YAML 清理失败后保留已验证目标并在重试时单调收敛")
    void convergesAfterCleanupFailureWithoutLosingCredential() throws Exception {
        String owner = "retry-owner";
        String key = "retry-owner.token";
        InMemoryLegacySources sources = new InMemoryLegacySources();
        sources.pluginValues.put(owner, new LinkedHashMap<>(Map.of(key, "property-wins")));
        sources.yamlValues.put(key, "yaml-loses");
        sources.failNextYamlRemoval = true;
        PluginRegistry registry =
                new PluginRegistry(List.of(plugin(owner, Set.of(key))));
        PluginCredentialStore store = new PluginCredentialStore();
        StandardEnvironment environment = new StandardEnvironment();
        PluginCredentialDefinitionResolver resolver =
                new PluginCredentialDefinitionResolver(registry, () -> Set.of(HOST_SSL_KEY));
        PluginCredentialMigrationService service = new PluginCredentialMigrationService(
                resolver,
                store,
                new PluginCredentialEnvironmentMask(environment),
                sources);

        assertThatThrownBy(() -> service.migrateOwner(owner))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("simulated YAML cleanup failure");

        assertThat(store.readAll(owner)).containsEntry(key, "property-wins");
        assertThat(sources.pluginValues.get(owner)).doesNotContainKey(key);
        assertThat(sources.yamlValues).containsEntry(key, "yaml-loses");
        byte[] verifiedTarget =
                Files.readAllBytes(RuntimeFiles.resolvePluginCredentialPath(owner));

        service.migrateOwner(owner);

        assertThat(store.readAll(owner)).containsEntry(key, "property-wins");
        assertThat(sources.yamlValues).doesNotContainKey(key);
        assertThat(Files.readAllBytes(RuntimeFiles.resolvePluginCredentialPath(owner)))
                .isEqualTo(verifiedTarget);
    }

    @Test
    @DisplayName("插件 properties 清理首次失败时保留密文与两个旧源并在重试收敛")
    void convergesAfterPluginPropertiesCleanupFailureWithoutRewritingCiphertext()
            throws Exception {
        String owner = "properties-retry-owner";
        String key = "properties-retry-owner.token";
        InMemoryLegacySources sources = new InMemoryLegacySources();
        sources.pluginValues.put(
                owner,
                new LinkedHashMap<>(Map.of(key, "property-wins")));
        sources.yamlValues.put(key, "yaml-loses");
        sources.failNextPluginPropertiesRemoval = true;
        PluginRegistry registry =
                new PluginRegistry(List.of(plugin(owner, Set.of(key))));
        PluginCredentialStore store = new PluginCredentialStore();
        PluginCredentialDefinitionResolver resolver =
                new PluginCredentialDefinitionResolver(
                        registry, () -> Set.of(HOST_SSL_KEY));
        PluginCredentialMigrationService service =
                new PluginCredentialMigrationService(
                        resolver,
                        store,
                        new PluginCredentialEnvironmentMask(
                                new StandardEnvironment()),
                        sources);

        assertThatThrownBy(() -> service.migrateOwner(owner))
                .isInstanceOf(IOException.class)
                .hasMessageContaining(
                        "simulated plugin properties cleanup failure");

        assertThat(store.readAll(owner)).containsEntry(key, "property-wins");
        assertThat(sources.pluginValues.get(owner))
                .containsEntry(key, "property-wins");
        assertThat(sources.yamlValues).containsEntry(key, "yaml-loses");
        byte[] verifiedTarget =
                Files.readAllBytes(RuntimeFiles.resolvePluginCredentialPath(owner));

        service.migrateOwner(owner);

        assertThat(store.readAll(owner)).containsEntry(key, "property-wins");
        assertThat(sources.pluginValues.get(owner)).doesNotContainKey(key);
        assertThat(sources.yamlValues).doesNotContainKey(key);
        assertThat(Files.readAllBytes(
                RuntimeFiles.resolvePluginCredentialPath(owner)))
                .isEqualTo(verifiedTarget);
    }

    @Test
    @DisplayName("一个 owner 定义失败不会阻断其它 owner 且失败字段仍被遮罩")
    void isolatesInvalidOwnerFromHealthyMigration() throws Exception {
        String goodKey = "healthy.token";
        String badKey = "broken.token";
        PixivFeaturePlugin healthy = plugin("healthy", Set.of(goodKey));
        PixivFeaturePlugin broken = pluginWithFields("broken", List.of(
                passwordField(badKey),
                passwordField(badKey)));
        PluginRegistry registry = new PluginRegistry(List.of(healthy, broken));
        yamlEditor().writeAll(Map.of(goodKey, "healthy-secret", badKey, "broken-secret"));
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addLast(new MapPropertySource(
                "legacy-yaml", Map.of(goodKey, "healthy-secret", badKey, "broken-secret")));
        PluginCredentialStore store = new PluginCredentialStore();

        service(registry, store, environment).migrateAll();

        assertThat(store.readAll("healthy")).containsEntry(goodKey, "healthy-secret");
        assertThat(store.readAll("broken")).isEmpty();
        assertThat(yamlEditor().read(goodKey)).isNull();
        assertThat(yamlEditor().read(badKey)).isEqualTo("broken-secret");
        assertThat(environment.getProperty(goodKey)).isEmpty();
        assertThat(environment.getProperty(badKey)).isEmpty();
    }

    private PluginCredentialMigrationService service(PluginRegistry registry,
                                                     PluginCredentialStore store,
                                                     StandardEnvironment environment) {
        PluginCredentialDefinitionResolver resolver =
                new PluginCredentialDefinitionResolver(registry, () -> Set.of(HOST_SSL_KEY));
        return new PluginCredentialMigrationService(
                resolver,
                store,
                new PluginCredentialEnvironmentMask(environment));
    }

    private static void awaitBlockedCredentialUpdate(Thread thread)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            boolean blockedInStoreUpdate = thread != null
                    && thread.getState() == Thread.State.BLOCKED
                    && Arrays.stream(thread.getStackTrace()).anyMatch(frame ->
                            PluginCredentialStore.class.getName()
                                    .equals(frame.getClassName())
                                    && "update".equals(frame.getMethodName()));
            if (blockedInStoreUpdate) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError(
                "Concurrent credential update did not block on the migration owner lock");
    }

    private void copyHistoricalFixture(String version) throws IOException {
        Path target = RuntimeFiles.resolveConfigYamlPath();
        try (InputStream input = getClass().getResourceAsStream(
                "/fixtures/plugin-credentials/" + version + "-config.yaml")) {
            if (input == null) {
                throw new IOException("Missing plugin credential fixture for " + version);
            }
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static StandardEnvironment legacyEnvironment(
            Map<String, Map<String, String>> credentials) {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        credentials.values().forEach(values::putAll);
        values.put(HOST_SSL_KEY, HOST_SSL_VALUE);
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addLast(new MapPropertySource("legacy-yaml", values));
        return environment;
    }

    private static Map<String, byte[]> credentialFileBytes(Set<String> owners)
            throws IOException {
        LinkedHashMap<String, byte[]> result = new LinkedHashMap<>();
        for (String owner : owners) {
            result.put(owner, Files.readAllBytes(RuntimeFiles.resolvePluginCredentialPath(owner)));
        }
        return result;
    }

    private static List<PixivFeaturePlugin> syntheticFixturePlugins() {
        List<PixivFeaturePlugin> plugins = new ArrayList<>();
        SYNTHETIC_CREDENTIAL_DEFINITIONS.forEach(
                (owner, keys) -> plugins.add(plugin(owner, keys)));
        return List.copyOf(plugins);
    }

    private static PluginCredentialDefinitionResolver.Resolution resolutionFor(
            PluginRegistry registry) {
        return new PluginCredentialDefinitionResolver(
                registry,
                () -> Set.of(HOST_SSL_KEY)).resolveSnapshot();
    }

    private static LoadedOfficialCredentialPlugins loadOfficialCredentialPlugins()
            throws Exception {
        List<URLClassLoader> classLoaders = new ArrayList<>();
        List<PixivFeaturePlugin> features = new ArrayList<>();
        Map<String, PluginContextModule> modules = new LinkedHashMap<>();
        try {
            for (String propertyName : OFFICIAL_CREDENTIAL_PLUGIN_CLASS_PROPERTIES) {
                String configuredDirectory = System.getProperty(propertyName);
                assertThat(configuredDirectory)
                        .as("Surefire property %s", propertyName)
                        .isNotBlank();
                Path classesDirectory =
                        Path.of(configuredDirectory).toAbsolutePath().normalize();
                assertThat(classesDirectory)
                        .as("official plugin classes directory from %s", propertyName)
                        .isDirectory();
                Path descriptor = classesDirectory.resolve("plugin.properties");
                assertThat(descriptor)
                        .as("official plugin descriptor from %s", propertyName)
                        .isRegularFile();

                Properties properties = new Properties();
                try (var reader =
                             Files.newBufferedReader(descriptor, StandardCharsets.UTF_8)) {
                    properties.load(reader);
                }
                String descriptorId = properties.getProperty("plugin.id");
                String providerClassName = properties.getProperty("plugin.class");
                assertThat(descriptorId)
                        .as("plugin.id in %s", descriptor)
                        .isNotBlank();
                assertThat(providerClassName)
                        .as("plugin.class in %s", descriptor)
                        .isNotBlank();

                URLClassLoader classLoader = new URLClassLoader(
                        officialPluginClassPath(classesDirectory),
                        PixivPluginProvider.class.getClassLoader());
                classLoaders.add(classLoader);
                Class<?> providerType =
                        Class.forName(providerClassName, true, classLoader);
                assertThat(providerType.getClassLoader())
                        .as("provider %s must come from its official plugin output",
                                providerClassName)
                        .isSameAs(classLoader);
                assertThat(providerType)
                        .isAssignableTo(PixivPluginProvider.class);
                PixivPluginProvider provider = (PixivPluginProvider)
                        providerType.getDeclaredConstructor().newInstance();
                PixivFeaturePlugin feature = provider.featurePlugin();
                assertThat(feature)
                        .as("feature returned by %s", providerClassName)
                        .isNotNull();
                assertThat(feature.id()).isEqualTo(descriptorId);
                assertThat(feature.getClass().getClassLoader())
                        .as("feature %s must come from its official plugin output",
                                descriptorId)
                        .isSameAs(classLoader);
                List<Class<?>> configurationClasses =
                        List.copyOf(provider.configurationClasses());
                assertThat(configurationClasses)
                        .as("configuration classes returned by %s",
                                providerClassName)
                        .isNotEmpty()
                        .allSatisfy(configurationClass -> {
                            assertThat(configurationClass.getClassLoader())
                                        .as("configuration class %s",
                                                configurationClass.getName())
                                    .isSameAs(classLoader);
                            assertThat(AnnotatedElementUtils.hasAnnotation(
                                    configurationClass,
                                    Configuration.class))
                                    .as("@Configuration on %s",
                                            configurationClass.getName())
                                    .isTrue();
                        });
                features.add(feature);
                assertThat(modules.put(
                        descriptorId,
                        new PluginContextModule(
                                descriptorId,
                                classLoader,
                                configurationClasses)))
                        .as("duplicate official credential plugin id")
                        .isNull();
            }
            return new LoadedOfficialCredentialPlugins(
                    List.copyOf(features),
                    Map.copyOf(modules),
                    List.copyOf(classLoaders));
        } catch (Exception | LinkageError | AssertionError failure) {
            closeLoaders(classLoaders, failure);
            throw failure;
        }
    }

    private static URL[] officialPluginClassPath(Path classesDirectory)
            throws IOException {
        List<URL> urls = new ArrayList<>();
        urls.add(classesDirectory.toUri().toURL());
        Path privateLibraries = classesDirectory.resolve("lib");
        if (Files.isDirectory(privateLibraries)) {
            try (var files = Files.list(privateLibraries)) {
                for (Path library : files
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString()
                                .endsWith(".jar"))
                        .sorted()
                        .toList()) {
                    urls.add(library.toUri().toURL());
                }
            }
        }
        return urls.toArray(URL[]::new);
    }

    private static void closeLoaders(
            List<URLClassLoader> classLoaders, Throwable failure) {
        for (int index = classLoaders.size() - 1; index >= 0; index--) {
            try {
                classLoaders.get(index).close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }
    }

    private static PixivFeaturePlugin plugin(String owner, Set<String> keys) {
        return pluginWithFields(
                owner,
                keys.stream().sorted().map(PluginCredentialMigrationServiceTest::passwordField).toList());
    }

    private static PixivFeaturePlugin pluginWithFields(
            String owner, List<GuiConfigFieldContribution> fields) {
        return new PixivFeaturePlugin() {
            @Override
            public String id() {
                return owner;
            }

            @Override
            public String displayName() {
                return "plugin.name";
            }

            @Override
            public String description() {
                return "plugin.description";
            }

            @Override
            public PluginKind kind() {
                return PluginKind.FEATURE;
            }

            @Override
            public List<GuiConfigContribution> guiConfigContributions() {
                return List.of(new GuiConfigContribution(fields));
            }
        };
    }

    private static GuiConfigFieldContribution passwordField(String key) {
        return new GuiConfigFieldContribution(
                key, "fixture", key, "", GuiConfigFieldType.PASSWORD, "", 10,
                true, GuiConfigEffect.BACKEND_RESTART);
    }

    private HistoricalCredentialOracle historicalOracle(String version) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(HISTORICAL_ORACLE_RESOURCE)) {
            if (input == null) {
                throw new IOException("Missing frozen historical credential oracle");
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String revision = null;
                String zeroCredentialReason = "";
                List<HistoricalCredential> credentials = new ArrayList<>();
                int lineNumber = 0;
                String line;
                while ((line = reader.readLine()) != null) {
                    lineNumber++;
                    if (line.isBlank() || line.startsWith("#") || line.startsWith("version\t")) {
                        continue;
                    }
                    String[] columns = line.split("\t", -1);
                    if (columns.length != 7) {
                        throw new IOException(
                                "Malformed historical credential oracle line " + lineNumber);
                    }
                    if (!version.equals(columns[0])) {
                        continue;
                    }
                    if (revision == null) {
                        revision = columns[1];
                    } else if (!revision.equals(columns[1])) {
                        throw new IOException(
                                "Historical credential oracle revision mismatch for " + version);
                    }
                    if ("-".equals(columns[2]) && "-".equals(columns[3])) {
                        zeroCredentialReason = columns[6];
                        continue;
                    }
                    credentials.add(new HistoricalCredential(
                            columns[2],
                            columns[3],
                            columns[4],
                            columns[5],
                            columns[6]));
                }
                if (revision == null) {
                    throw new IOException(
                            "Historical credential oracle has no version " + version);
                }
                return new HistoricalCredentialOracle(
                        version,
                        revision,
                        List.copyOf(credentials),
                        zeroCredentialReason);
            }
        }
    }

    private static void assertFrozenOracle(
            HistoricalCredentialOracle oracle, String revision, int expectedKeyCount) {
        assertThat(oracle.revision()).isEqualTo(revision);
        assertThat(oracle.credentials()).hasSize(expectedKeyCount);
        assertThat(oracle.keys())
                .hasSize(expectedKeyCount)
                .doesNotContain(HOST_SSL_KEY);
        assertThat(oracle.credentials()).allSatisfy(credential -> {
            assertThat(credential.owner()).isNotBlank();
            assertThat(credential.key()).isNotBlank();
            assertThat(credential.legacySource()).isEqualTo("config.yaml");
            assertThat(credential.legacyFieldType()).isIn("PASSWORD", "STRING");
            assertThat(credential.reclassificationReason()).isNotBlank();
        });
        if ("v1.13.1".equals(oracle.version())) {
            HistoricalCredential webhook = oracle.credentials().stream()
                    .filter(credential -> "push.webhook.url".equals(credential.key()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "v1.13.1 oracle must include push.webhook.url"));
            assertThat(webhook.legacyFieldType()).isEqualTo("STRING");
            assertThat(webhook.reclassificationReason())
                    .isEqualTo("credential-bearing-url-reclassified-sensitive");
        }
        if (expectedKeyCount == 0) {
            assertThat(oracle.zeroCredentialReason()).isNotBlank();
        }
    }

    private static void assertDefinitionsCover(
            HistoricalCredentialOracle oracle,
            PluginCredentialDefinitionResolver.Resolution current) {
        assertThat(current.hostConfigKeys()).contains(HOST_SSL_KEY);
        assertThat(current.maskKeys()).doesNotContain(HOST_SSL_KEY);
        assertThat(current.failures()).isEmpty();
        for (HistoricalCredential credential : oracle.credentials()) {
            assertThat(current.validDefinitions())
                    .containsKey(credential.owner());
            assertThat(current.validDefinitions().get(credential.owner()))
                    .contains(credential.key());
        }
    }

    private static Map<String, Map<String, String>> currentOfficialCredentialValues(
            Map<String, Set<String>> definitions) {
        LinkedHashMap<String, Map<String, String>> valuesByOwner =
                new LinkedHashMap<>();
        int ordinal = 0;
        for (String owner : definitions.keySet().stream().sorted().toList()) {
            LinkedHashMap<String, String> ownerValues =
                    new LinkedHashMap<>();
            for (String key
                    : definitions.get(owner).stream().sorted().toList()) {
                ordinal++;
                String value = key.endsWith(".url")
                        ? "https://credential.invalid/" + owner + "/" + ordinal
                        : "credential-" + owner + "-" + ordinal;
                ownerValues.put(key, value);
            }
            valuesByOwner.put(owner, Map.copyOf(ownerValues));
        }
        return Map.copyOf(valuesByOwner);
    }

    private static Map<String, String> flattenCredentialValues(
            Map<String, Map<String, String>> valuesByOwner) {
        LinkedHashMap<String, String> flattened = new LinkedHashMap<>();
        valuesByOwner.values().forEach(values -> values.forEach(
                (key, value) -> {
                    if (flattened.putIfAbsent(key, value) != null) {
                        throw new AssertionError(
                                "Duplicate official credential key: " + key);
                    }
                }));
        return Map.copyOf(flattened);
    }

    private static Map<String, Map<String, String>> readHistoricalCredentialValues(
            HistoricalCredentialOracle oracle) throws IOException {
        Map<String, String> yamlValues = yamlEditor().readAll(oracle.keys());
        assertThat(yamlValues).hasSize(oracle.credentials().size());
        LinkedHashMap<String, Map<String, String>> byOwner = new LinkedHashMap<>();
        for (HistoricalCredential credential : oracle.credentials()) {
            assertThat(yamlValues).containsKey(credential.key());
            byOwner.computeIfAbsent(credential.owner(), ignored -> new LinkedHashMap<>())
                    .put(credential.key(), yamlValues.get(credential.key()));
        }
        LinkedHashMap<String, Map<String, String>> immutable = new LinkedHashMap<>();
        byOwner.forEach((owner, values) -> immutable.put(owner, Map.copyOf(values)));
        return Map.copyOf(immutable);
    }

    private void assertCredentialDirectoryHasNoFiles() throws IOException {
        Path directory = tempDir.resolve("config").resolve("credentials");
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (var paths = Files.list(directory)) {
            assertThat(paths.toList()).isEmpty();
        }
    }

    private PluginContextModule credentialBindingModule(String owner) {
        return new PluginContextModule(
                owner,
                getClass().getClassLoader(),
                List.of(AiCredentialBindingConfiguration.class));
    }

    private static void assertAiCredentialHidden(ConfigurableApplicationContext context) {
        assertThat(context.getEnvironment().getProperty("ai.api-key")).isEmpty();
        assertThat(Binder.get(context.getEnvironment())
                .bind("ai.api-key", String.class)
                .orElse("missing"))
                .isEmpty();
        assertThat(context.getBean(AiCredentialProperties.class).getApiKey()).isEmpty();
    }

    private static void assertAiCredentialVisible(
            ConfigurableApplicationContext context, String expected) {
        assertThat(context.getEnvironment().getProperty("ai.api-key")).isEqualTo(expected);
        assertThat(Binder.get(context.getEnvironment())
                .bind("ai.api-key", String.class)
                .orElse(null))
                .isEqualTo(expected);
        assertThat(context.getBean(AiCredentialProperties.class).getApiKey())
                .isEqualTo(expected);
    }

    private static void assertHostCredentialHidden(ConfigurableApplicationContext context) {
        assertThat(context.getEnvironment().getProperty(HOST_SSL_KEY)).isEmpty();
        assertThat(Binder.get(context.getEnvironment())
                .bind(HOST_SSL_KEY, String.class)
                .orElse("missing"))
                .isEmpty();
    }

    private AnnotationConfigApplicationContext officialPluginParent(
            StandardEnvironment environment) {
        AnnotationConfigApplicationContext parent =
                new AnnotationConfigApplicationContext();
        parent.setEnvironment(environment);
        parent.registerBean(
                MessageResolver.class,
                () -> mock(MessageResolver.class));
        parent.registerBean(
                LocaleBundlePolicy.class,
                () -> new CatalogLocaleBundlePolicy(LocaleCatalog.defaultCatalog()));
        parent.registerBean(
                NotificationTemplateCatalog.class,
                ImmutableNotificationTemplateCatalog::empty);
        parent.registerBean(
                OutboundHttpClientFactory.class,
                () -> profile -> mock(OutboundHttpClient.class));
        parent.registerBean(
                OutboundWebSocketClientFactory.class,
                () -> profile -> mock(OutboundWebSocketClient.class));
        parent.registerBean(ObjectMapper.class, () -> new ObjectMapper());
        parent.registerBean(
                RequestOwnerIdentityResolver.class,
                () -> mock(RequestOwnerIdentityResolver.class));
        parent.registerBean(
                PushDispatcher.class,
                () -> mock(PushDispatcher.class));
        parent.registerBean(
                UserDisplayNameProvider.class,
                () -> mock(UserDisplayNameProvider.class));
        parent.refresh();
        return parent;
    }

    private RuntimePathProvider officialPluginRuntimePaths(String owner) {
        Path root = tempDir.resolve("official-plugin-runtime").resolve(owner);
        return new RuntimePathProvider() {
            @Override
            public Path configFile(String extension) {
                return root.resolve("config." + extension);
            }

            @Override
            public Path stateDirectory() {
                return root.resolve("state");
            }

            @Override
            public Path dataDirectory() {
                return root.resolve("data");
            }
        };
    }

    private static void assertRealCredentialBindings(
            ConfigurableApplicationContext context,
            String owner,
            Map<String, String> ownerCredentials,
            Map<String, String> allCredentials,
            ClassLoader pluginClassLoader) {
        assertThat(ownerCredentials)
                .as("credential values for owner %s", owner)
                .isNotEmpty();
        List<ConfigurationPropertiesBean> configurationBeans =
                context.getBeansWithAnnotation(ConfigurationProperties.class)
                        .values()
                        .stream()
                        .map(ConfigurationPropertiesBean::from)
                        .toList();
        assertThat(configurationBeans)
                .as("real @ConfigurationProperties beans for owner %s", owner)
                .isNotEmpty()
                .allSatisfy(bean ->
                        assertThat(AopUtils.getTargetClass(
                                bean.bean()).getClassLoader())
                                .as("real configuration bean for %s",
                                        bean.prefix())
                                .isSameAs(pluginClassLoader));

        ownerCredentials.forEach((key, expected) -> {
            assertThat(context.getEnvironment().getProperty(key))
                    .as("owner environment value %s/%s", owner, key)
                    .isEqualTo(expected);
            assertThat(Binder.get(context.getEnvironment())
                    .bind(key, String.class)
                    .orElse(null))
                    .as("owner Binder value %s/%s", owner, key)
                    .isEqualTo(expected);

            ConfigurationPropertiesBean target =
                    configurationBeans.stream()
                            .filter(bean -> key.startsWith(
                                    bean.prefix() + "."))
                            .max((left, right) -> Integer.compare(
                                    left.prefix().length(),
                                    right.prefix().length()))
                            .orElseThrow(() -> new AssertionError(
                                    "No real configuration bean binds "
                                            + owner + "/" + key));
            String relativeKey =
                    key.substring(target.prefix().length() + 1);
            Object boundValue = PropertyAccessorFactory
                    .forBeanPropertyAccess(target.bean())
                    .getPropertyValue(beanPropertyPath(relativeKey));
            assertThat(boundValue)
                    .as("real configuration bean value %s/%s", owner, key)
                    .isEqualTo(expected);
        });

        allCredentials.forEach((key, ignored) -> {
            if (ownerCredentials.containsKey(key)) {
                return;
            }
            assertThat(context.getEnvironment().getProperty(key))
                    .as("cross-owner environment value %s/%s", owner, key)
                    .isEmpty();
            assertThat(Binder.get(context.getEnvironment())
                    .bind(key, String.class)
                    .orElse("missing"))
                    .as("cross-owner Binder value %s/%s", owner, key)
                    .isEmpty();
        });
        assertHostCredentialHidden(context);
    }

    private static String beanPropertyPath(String relaxedPath) {
        StringBuilder propertyPath =
                new StringBuilder(relaxedPath.length());
        boolean uppercaseNext = false;
        for (int index = 0; index < relaxedPath.length(); index++) {
            char character = relaxedPath.charAt(index);
            if (character == '-') {
                uppercaseNext = true;
                continue;
            }
            propertyPath.append(uppercaseNext
                    ? Character.toUpperCase(character)
                    : character);
            uppercaseNext = false;
        }
        return propertyPath.toString();
    }

    private static ConfigFileEditor yamlEditor() {
        return new ConfigFileEditor(RuntimeFiles.resolveConfigYamlPath());
    }

    private static PropertiesConfigFileEditor pluginEditor(String owner) {
        return new PropertiesConfigFileEditor(
                RuntimeFiles.resolvePluginConfigPath(owner, "properties"));
    }

    @ConfigurationProperties(prefix = "ai")
    public static class AiCredentialProperties {

        private String apiKey = "unbound";

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AiCredentialProperties.class)
    static class AiCredentialBindingConfiguration {
    }

    private record LoadedOfficialCredentialPlugins(
            List<PixivFeaturePlugin> features,
            Map<String, PluginContextModule> modules,
            List<URLClassLoader> classLoaders) implements AutoCloseable {

        @Override
        public void close() throws IOException {
            IOException failure = null;
            for (int index = classLoaders.size() - 1; index >= 0; index--) {
                try {
                    classLoaders.get(index).close();
                } catch (IOException closeFailure) {
                    if (failure == null) {
                        failure = closeFailure;
                    } else {
                        failure.addSuppressed(closeFailure);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    private record ConfigurationPropertiesBean(
            String prefix,
            Object bean) {

        private static ConfigurationPropertiesBean from(Object bean) {
            ConfigurationProperties annotation =
                    AnnotatedElementUtils.findMergedAnnotation(
                            AopUtils.getTargetClass(bean),
                            ConfigurationProperties.class);
            if (annotation == null) {
                throw new AssertionError(
                        "Missing @ConfigurationProperties on "
                                + bean.getClass().getName());
            }
            String prefix = annotation.prefix();
            if (prefix == null || prefix.isBlank()) {
                prefix = annotation.value();
            }
            if (prefix == null || prefix.isBlank()) {
                throw new AssertionError(
                        "Blank @ConfigurationProperties prefix on "
                                + bean.getClass().getName());
            }
            return new ConfigurationPropertiesBean(
                    prefix.trim(), bean);
        }
    }

    private record HistoricalCredential(
            String owner,
            String key,
            String legacySource,
            String legacyFieldType,
            String reclassificationReason) {
    }

    private record HistoricalCredentialOracle(
            String version,
            String revision,
            List<HistoricalCredential> credentials,
            String zeroCredentialReason) {

        private Set<String> keys() {
            LinkedHashSet<String> keys = new LinkedHashSet<>();
            credentials.forEach(credential -> keys.add(credential.key()));
            return Set.copyOf(keys);
        }

        private Set<String> owners() {
            LinkedHashSet<String> owners = new LinkedHashSet<>();
            credentials.forEach(credential -> owners.add(credential.owner()));
            return Set.copyOf(owners);
        }
    }

    private static final class BlockingLegacySources
            implements PluginCredentialMigrationService.LegacyCredentialSources {

        private final InMemoryLegacySources delegate;
        private final AtomicBoolean blockFirstPluginRead =
                new AtomicBoolean(true);
        private final CountDownLatch firstPluginReadStarted =
                new CountDownLatch(1);
        private final CountDownLatch releaseFirstPluginRead =
                new CountDownLatch(1);

        private BlockingLegacySources(InMemoryLegacySources delegate) {
            this.delegate = delegate;
        }

        @Override
        public Map<String, String> readPluginProperties(
                String owner, Set<String> keys) throws IOException {
            if (blockFirstPluginRead.compareAndSet(true, false)) {
                firstPluginReadStarted.countDown();
                try {
                    if (!releaseFirstPluginRead.await(5, TimeUnit.SECONDS)) {
                        throw new IOException(
                                "Timed out waiting to release legacy credential read");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(
                            "Interrupted while waiting to release legacy credential read", e);
                }
            }
            return delegate.readPluginProperties(owner, keys);
        }

        @Override
        public Map<String, String> readYaml(Set<String> keys) {
            return delegate.readYaml(keys);
        }

        @Override
        public void removePluginProperties(
                String owner, Set<String> keys) throws IOException {
            delegate.removePluginProperties(owner, keys);
        }

        @Override
        public void removeYaml(Set<String> keys) throws IOException {
            delegate.removeYaml(keys);
        }
    }

    private static final class InMemoryLegacySources
            implements PluginCredentialMigrationService.LegacyCredentialSources {

        private final Map<String, Map<String, String>> pluginValues = new LinkedHashMap<>();
        private final Map<String, String> yamlValues = new LinkedHashMap<>();
        private boolean failNextPluginPropertiesRemoval;
        private boolean failNextYamlRemoval;

        @Override
        public Map<String, String> readPluginProperties(String owner, Set<String> keys) {
            return filtered(pluginValues.getOrDefault(owner, Map.of()), keys);
        }

        @Override
        public Map<String, String> readYaml(Set<String> keys) {
            return filtered(yamlValues, keys);
        }

        @Override
        public void removePluginProperties(String owner, Set<String> keys)
                throws IOException {
            if (failNextPluginPropertiesRemoval) {
                failNextPluginPropertiesRemoval = false;
                throw new IOException(
                        "simulated plugin properties cleanup failure");
            }
            Map<String, String> values = pluginValues.get(owner);
            if (values != null) {
                keys.forEach(values::remove);
            }
        }

        @Override
        public void removeYaml(Set<String> keys) throws IOException {
            if (failNextYamlRemoval) {
                failNextYamlRemoval = false;
                throw new IOException("simulated YAML cleanup failure");
            }
            keys.forEach(yamlValues::remove);
        }

        private static Map<String, String> filtered(Map<String, String> values, Set<String> keys) {
            LinkedHashMap<String, String> result = new LinkedHashMap<>();
            for (String key : keys) {
                if (values.containsKey(key)) {
                    result.put(key, values.get(key));
                }
            }
            return Map.copyOf(result);
        }
    }
}
