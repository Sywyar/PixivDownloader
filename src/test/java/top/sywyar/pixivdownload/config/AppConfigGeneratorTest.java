package top.sywyar.pixivdownload.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AppConfigGenerator i18n tests")
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
    @DisplayName("should generate English config template when JVM locale is en-US")
    void shouldGenerateEnglishConfigTemplateWhenLocaleIsEnglish() throws IOException {
        Locale.setDefault(Locale.US);

        new AppConfigGenerator(TestI18nBeans.appMessages()).generateOrUpdateConfig();

        Path configPath = configDir.resolve(RuntimeFiles.CONFIG_YAML);
        String content = Files.readString(configPath, StandardCharsets.UTF_8);

        assertThat(content).contains("# Pixiv Download configuration file");
        assertThat(content).contains("server.port: 6999");
        assertThat(content).contains("Service listening port");
        assertThat(content).contains("#   pack-and-delete  Pack and delete source files (default)");
        assertThat(content).contains("GUI and log language (en-US/zh-CN; leave blank to auto-detect from system language)");
        assertThat(content).contains("Built-in plugin toggles");
        assertThat(content).contains("plugins.download-workbench.enabled: true");
        assertThat(content).contains("plugins.gallery.enabled: true");
        assertThat(content).contains("Whether to enable this built-in plugin");
    }

    @Test
    @DisplayName("should append missing config items using current locale comments")
    void shouldAppendMissingConfigItemsUsingCurrentLocaleComments() throws IOException {
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE);
        Path configPath = configDir.resolve(RuntimeFiles.CONFIG_YAML);
        Files.createDirectories(configPath.getParent());
        Files.writeString(configPath, "server.port: 6999\n", StandardCharsets.UTF_8);

        new AppConfigGenerator(TestI18nBeans.appMessages()).generateOrUpdateConfig();

        String content = Files.readString(configPath, StandardCharsets.UTF_8);

        assertThat(content).contains("# ---- 以下为自动补全的新增配置项（请按需修改）----");
        assertThat(content).contains("download.root-folder: pixiv-download");
        assertThat(content).contains("GUI 与日志语言（en-US/zh-CN，留空则跟随系统语言自动检测）");
    }
}
