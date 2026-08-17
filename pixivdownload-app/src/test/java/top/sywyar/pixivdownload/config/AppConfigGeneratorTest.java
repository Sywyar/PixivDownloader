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

        assertThat(content).contains("# Pixiv Download configuration file");
        assertThat(content).contains("server.port: 6999");
        assertThat(content).contains("Service listening port");
        assertThat(content).contains("#   pack-and-delete  Pack and delete source files (default)");
        assertThat(content).contains(
                "GUI and log language (zh-CN / zh-Hant / en-US / ja-JP / ko-KR; leave blank to auto-detect from system language)");
        assertThat(content).contains("app.config-menu-expand-all: false");
        assertThat(content).contains(
                "Whether to promote every leaf page from nested configuration menus directly to the top-level tabs");
        assertThat(content.lines().filter(AppConfigGeneratorTest::isPluginToggleLine).toList()).isEmpty();
    }

    @Test
    @DisplayName("补全缺失配置项时使用当前语言注释")
    void shouldAppendMissingConfigItemsUsingCurrentLocaleComments() throws IOException {
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE);
        Path configPath = configDir.resolve(RuntimeFiles.CONFIG_YAML);
        Files.createDirectories(configPath.getParent());
        Files.writeString(configPath, "server.port: 6999\n", StandardCharsets.UTF_8);

        new AppConfigGenerator(TestI18nBeans.appMessages()).generateOrUpdateConfig();

        String content = Files.readString(configPath, StandardCharsets.UTF_8);

        assertThat(content).contains("# ---- 以下为自动补全的新增配置项（请按需修改）----");
        assertThat(content).contains("download.root-folder: pixiv-download");
        assertThat(content).contains(
                "GUI 与日志语言（zh-CN / zh-Hant / en-US / ja-JP / ko-KR，留空则跟随系统语言自动检测）");
        assertThat(content).contains("app.config-menu-expand-all: false");
        assertThat(content).contains("是否将配置页所有多级菜单的末级页面直接展开为一级标签");
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
        assertThat(content.lines()
                .map(String::strip)
                .filter(AppConfigGeneratorTest::isPluginToggleLine)
                .toList())
                .containsExactly("plugins.demo-ext.enabled: false  # keep");
    }

    private static boolean isPluginToggleLine(String line) {
        String trimmed = line.strip();
        int separator = trimmed.indexOf(':');
        if (separator <= 0) {
            return false;
        }
        String key = trimmed.substring(0, separator).trim();
        return key.startsWith("plugins.") && key.endsWith(".enabled");
    }
}
