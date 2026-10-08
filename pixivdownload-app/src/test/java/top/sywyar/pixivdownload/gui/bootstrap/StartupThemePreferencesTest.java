package top.sywyar.pixivdownload.gui.bootstrap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.gui.config.ConfigFileEditor;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiPluginSnapshot;
import top.sywyar.pixivdownload.plugin.api.gui.GuiThemeAppearance;
import top.sywyar.pixivdownload.plugin.api.gui.GuiThemeContribution;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("启动窗口主题配置与语义缓存")
class StartupThemePreferencesTest {
    @TempDir Path root;

    @Test
    @DisplayName("首次启动跟随系统且不写配置，显式深浅色不依赖缓存")
    void initialAndExplicitPreferences() throws Exception {
        Path config = root.resolve("config.yaml");
        Path cache = root.resolve("cache.properties");
        assertThat(StartupThemePreferences.read(config, cache)).isEqualTo("system");
        assertThat(Files.exists(config)).isFalse();
        var editor = new ConfigFileEditor(config);
        for (String theme : List.of("light", "dark", "system")) {
            editor.write("app.theme", theme);
            assertThat(StartupThemePreferences.read(config, cache)).isEqualTo(theme);
        }
    }

    @Test
    @DisplayName("专属主题缓存绑定提供者，修改主题后无需加载插件即可读取深浅语义")
    void providerBoundThemeCache() throws Exception {
        Path config = root.resolve("config.yaml");
        Path cache = root.resolve("cache.properties");
        var editor = new ConfigFileEditor(config);
        editor.writeAll(Map.of("app.gui-provider", "sample-ui", "app.theme", "system"));
        var contribution = new GuiThemeContribution("sample-dark", locale -> "Sample", GuiThemeAppearance.DARK,
                () -> { throw new AssertionError("Theme application must not run"); });
        var snapshot = new DesktopUiPluginSnapshot("sample-ui", false, "sample-ui", 1, true,
                null, "", List.of(contribution), List.of(), List.of(), List.of(), List.of());
        StartupThemePreferences.remember(config, cache, "sample-ui", List.of(snapshot));
        editor.write("app.theme", "sample-dark");
        assertThat(StartupThemePreferences.read(config, cache)).isEqualTo("dark");
        editor.write("app.gui-provider", "other-ui");
        assertThat(StartupThemePreferences.read(config, cache)).isEqualTo("system");
        assertThat(editor.read("app.theme")).isEqualTo("sample-dark");
        try (var files = Files.list(root)) {
            assertThat(files.map(path -> path.getFileName().toString()).toList())
                    .containsExactlyInAnyOrder("config.yaml", "cache.properties");
        }
    }

    @Test
    @DisplayName("损坏、超限或不可写缓存不改变配置，也不阻断启动")
    void invalidCacheFallsBackWithoutRewritingConfiguration() throws Exception {
        Path config = root.resolve("config.yaml");
        Path cache = root.resolve("cache.properties");
        Files.writeString(config, "app.theme: custom\n", StandardCharsets.UTF_8);
        byte[] original = Files.readAllBytes(config);
        Files.writeString(cache, "provider=\ntheme.custom=\\uXYZ1\n", StandardCharsets.UTF_8);
        assertThat(StartupThemePreferences.read(config, cache)).isEqualTo("system");
        Files.writeString(cache, "x".repeat(StartupThemePreferences.MAX_CACHE_BYTES + 1), StandardCharsets.UTF_8);
        assertThat(StartupThemePreferences.read(config, cache)).isEqualTo("system");
        StartupThemePreferences.remember(config, config.resolve("child"), "missing", List.of());
        assertThat(Files.readAllBytes(config)).isEqualTo(original);
    }
}
