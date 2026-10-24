package top.sywyar.pixivdownload.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.FileSystemResource;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;
import top.sywyar.pixivdownload.i18n.MessageBundles;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AppConfigGenerator 默认配置生成")
class AppConfigGeneratorTest {

    @TempDir
    Path tempDir;

    private Locale originalLocale;
    private Path configDir;

    @BeforeEach
    void setUp() {
        originalLocale = Locale.getDefault();
        configDir = tempDir.resolve("config");
        System.setProperty(RuntimeFiles.CONFIG_DIR_PROPERTY, configDir.toString());
    }

    @AfterEach
    void tearDown() {
        Locale.setDefault(originalLocale);
        System.clearProperty(RuntimeFiles.CONFIG_DIR_PROPERTY);
    }

    @Test
    @DisplayName("JVM 语言为 en-US 时生成英文默认配置且不写具体插件开关")
    void shouldGenerateEnglishConfigTemplateWhenLocaleIsEnglish() throws IOException {
        Locale.setDefault(Locale.US);

        new AppConfigGenerator(TestI18nBeans.appMessages()).generateOrUpdateConfig();

        Path configPath = configDir.resolve(RuntimeFiles.CONFIG_YAML);
        String content = Files.readString(configPath, StandardCharsets.UTF_8);

        assertThat(content).doesNotContain("config.template.")
                .contains("# " + MessageBundles.get(Locale.US, "config.template.header.title"));
        assertThat(content).contains("#   " + MessageBundles.get(Locale.US,
                "config.template.multi-mode.post-download-mode.pack-and-delete"));
        for (String key : new String[]{"server.port", "app.language", "app.config-menu-expand-all"}) {
            assertThat(content).contains(MessageBundles.get(Locale.US, "config.template." + key + ".comment"));
        }
        Map<String, Object> config = new Yaml().load(content);
        assertThat(config).containsEntry("server.port", 6999).containsEntry("app.config-menu-expand-all", false);
        assertThat(config.keySet()).noneMatch(key -> key.startsWith("plugins.") && key.endsWith(".enabled"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"server.port", "'server.port'", "\"server.port\""})
    @DisplayName("补全配置保留带引号或普通键的原值，并使用当前语言注释")
    void shouldAppendMissingConfigItemsUsingCurrentLocaleComments(String portKey) throws IOException {
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE);
        Path configPath = configDir.resolve(RuntimeFiles.CONFIG_YAML);
        Files.createDirectories(configPath.getParent());
        Files.writeString(configPath, portKey + ": 7011\n", StandardCharsets.UTF_8);

        new AppConfigGenerator(TestI18nBeans.appMessages()).generateOrUpdateConfig();

        String content = Files.readString(configPath, StandardCharsets.UTF_8);

        assertThat(content).doesNotContain("config.template.")
                .contains("# " + MessageBundles.get(Locale.SIMPLIFIED_CHINESE, "config.template.section.appendix"));
        for (String key : new String[]{"app.language", "app.config-menu-expand-all"}) {
            assertThat(content).contains(MessageBundles.get(Locale.SIMPLIFIED_CHINESE,
                    "config.template." + key + ".comment"));
        }
        var sources = new YamlPropertySourceLoader().load("config", new FileSystemResource(configPath));
        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).getProperty("server.port")).isEqualTo(7011);
        assertThat(sources.get(0).getProperty("download.root-folder")).isEqualTo("pixiv-download");
        assertThat(sources.get(0).getProperty("app.config-menu-expand-all")).isEqualTo(false);

        new AppConfigGenerator(TestI18nBeans.appMessages()).generateOrUpdateConfig();
        assertThat(Files.readString(configPath, StandardCharsets.UTF_8)).isEqualTo(content);
    }

    @ParameterizedTest
    @ValueSource(strings = {"server.port: [7011", "server.port: 7011\nserver.port: 7012\n"})
    @DisplayName("无效 YAML 或重复配置键拒绝补全并保留原文件")
    void preservesInvalidConfig(String original) throws IOException {
        Path configPath = configDir.resolve(RuntimeFiles.CONFIG_YAML);
        Files.createDirectories(configPath.getParent());
        Files.writeString(configPath, original, StandardCharsets.UTF_8);

        assertThatThrownBy(() -> new AppConfigGenerator(TestI18nBeans.appMessages()).generateOrUpdateConfig())
                .isInstanceOf(YAMLException.class);
        assertThat(Files.readString(configPath, StandardCharsets.UTF_8)).isEqualTo(original);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "plugin-catalog.repositories: [{id: first}, {id: second}]\n",
            "'plugin-catalog.repositories':\n  - id: first\n  - id: second\n"
    })
    @DisplayName("补全配置保留非空列表且再次启动仍能解析")
    void preservesPopulatedListWhenAppendingDefaults(String original) throws IOException {
        Path configPath = configDir.resolve(RuntimeFiles.CONFIG_YAML);
        Files.createDirectories(configPath.getParent());
        Files.writeString(configPath, original, StandardCharsets.UTF_8);
        var generator = new AppConfigGenerator(TestI18nBeans.appMessages());

        generator.generateOrUpdateConfig();

        String content = Files.readString(configPath, StandardCharsets.UTF_8);
        assertThat(content).startsWith(original);
        var sources = new YamlPropertySourceLoader().load("config", new FileSystemResource(configPath));
        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).getProperty("plugin-catalog.repositories[0].id")).isEqualTo("first");
        assertThat(sources.get(0).getProperty("plugin-catalog.repositories[1].id")).isEqualTo("second");
        assertThat(sources.get(0).containsProperty("download.root-folder")).isTrue();

        generator.generateOrUpdateConfig();
        assertThat(Files.readString(configPath, StandardCharsets.UTF_8)).isEqualTo(content);
    }

    @Test
    @DisplayName("补全默认配置时保留已有的显式插件开关")
    void preservesExistingExplicitPluginToggle() throws IOException {
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE);
        Path configPath = configDir.resolve(RuntimeFiles.CONFIG_YAML);
        Files.createDirectories(configPath.getParent());
        Files.writeString(configPath, String.join("\n",
                "server.port: 6999",
                "plugins.demo-ext.enabled: false  # keep",
                ""), StandardCharsets.UTF_8);

        new AppConfigGenerator(TestI18nBeans.appMessages()).generateOrUpdateConfig();

        String content = Files.readString(configPath, StandardCharsets.UTF_8);
        assertThat(content.lines()).contains("plugins.demo-ext.enabled: false  # keep");
        Map<String, Object> config = new Yaml().load(content);
        assertThat(config).containsEntry("plugins.demo-ext.enabled", false);
        assertThat(config.keySet()).filteredOn(key -> key.startsWith("plugins.") && key.endsWith(".enabled"))
                .containsExactly("plugins.demo-ext.enabled");
    }
}
