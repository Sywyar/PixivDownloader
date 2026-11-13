package top.sywyar.pixivdownload.startup;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

@DisplayName("桌面启动训练输入")
class StartupCacheInputsTest {
    @TempDir Path directory;

    @Test @DisplayName("安装、卸载、改名和保留时间戳的更新均使缓存失效")
    void artifactChanges() throws Exception {
        Path plugins = Files.createDirectory(directory.resolve("plugins"));
        Path config = directory.resolve("config.yaml");
        Path first = Files.writeString(plugins.resolve("first.jar"), "original", StandardCharsets.UTF_8);
        String key = StartupCacheInputs.capture(plugins, config).identity();
        var timestamp = Files.getLastModifiedTime(first);
        Files.writeString(first, "modified", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(first, timestamp);
        assertThat(StartupCacheInputs.capture(plugins, config).identity()).isNotEqualTo(key);
        Files.writeString(first, "original", StandardCharsets.UTF_8);
        Path second = Files.writeString(plugins.resolve("second.zip"), "added", StandardCharsets.UTF_8);
        assertThat(StartupCacheInputs.capture(plugins, config).identity()).isNotEqualTo(key);
        Files.delete(second);
        assertThat(StartupCacheInputs.capture(plugins, config).identity()).isEqualTo(key);
        Files.move(first, plugins.resolve("renamed.jar"));
        assertThat(StartupCacheInputs.capture(plugins, config).identity()).isNotEqualTo(key);
        Files.delete(plugins.resolve("renamed.jar"));
        assertThat(StartupCacheInputs.capture(plugins, config).identity()).isNotEqualTo(key);
    }

    @Test @DisplayName("只复制启停、桌面偏好和公开信任配置，凭据与真实目录不进入训练")
    void sanitizedConfiguration() throws Exception {
        Path config = directory.resolve("config.yaml");
        Files.writeString(config, """
                app.gui-provider: custom-desktop
                app.theme: dark
                plugins.sample.enabled: false
                proxy.password: private-password
                database.url: jdbc:private
                download.root-folder: private-artworks
                plugin-catalog.repositories:
                  - id: custom
                    manifest-url: https://private.invalid/token
                    trusted-keys:
                      - key-id: public
                        public-key: public-value
                """, StandardCharsets.UTF_8);
        String value = StartupCacheInputs.trainingConfiguration(config);
        assertThat(value).contains("custom-desktop", "dark", "plugins.sample.enabled: false", "public-value", "custom");
        assertThat(value).doesNotContain("private-password", "jdbc:private", "private-artworks", "private.invalid");
        Files.writeString(config, "plugins.sample.enabled: true\n", StandardCharsets.UTF_8);
        String enabled = StartupCacheInputs.trainingConfiguration(config);
        Files.delete(config);
        assertThat(StartupCacheInputs.trainingConfiguration(config)).isEqualTo(enabled);
    }

    @Test @DisplayName("冻结后内容发生改变时拒绝复制，缺来源记录不会生成可用训练副本")
    void changedOrUntrustedCopy() throws Exception {
        Path plugins = Files.createDirectory(directory.resolve("plugins"));
        Path artifact = Files.writeString(plugins.resolve("a.jar"), "original", StandardCharsets.UTF_8);
        var inputs = StartupCacheInputs.capture(plugins, directory.resolve("config.yaml"));
        Files.writeString(artifact, "modified", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> StartupCacheInputs.copy(inputs, directory.resolve("copy1/plugins"),
                directory.resolve("copy1/config.yaml"))).isInstanceOf(StartupCacheInputs.InputsChanged.class);
        Files.writeString(artifact, "original", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> StartupCacheInputs.copy(inputs, directory.resolve("copy2/plugins"),
                directory.resolve("copy2/config.yaml"))).isInstanceOf(java.io.IOException.class);
    }

    @Test @DisplayName("无自定义信任列表的训练配置经过初始化编辑后仍可由 Spring 读取")
    void scalarConfigurationSurvivesSetupEdits() throws Exception {
        Path source = Files.writeString(directory.resolve("source.yaml"),
                "app.gui-provider: custom-desktop\napp.theme: dark\n", StandardCharsets.UTF_8);
        Path training = Files.writeString(directory.resolve("training.yaml"),
                StartupCacheInputs.trainingConfiguration(source), StandardCharsets.UTF_8);
        var editor = new top.sywyar.pixivdownload.gui.config.ConfigFileEditor(training);
        editor.writeAll(java.util.Map.of("proxy.enabled", "false", "proxy.host", "127.0.0.1",
                "database.provider", "fixture-database"));
        var sources = new org.springframework.boot.env.YamlPropertySourceLoader().load("training",
                new org.springframework.core.io.FileSystemResource(training));
        assertThat(sources).hasSize(1);
        var properties = sources.getFirst();
        assertThat(properties.getProperty("app.gui-provider")).isEqualTo("custom-desktop");
        assertThat(properties.getProperty("app.theme")).isEqualTo("dark");
        assertThat(properties.getProperty("proxy.enabled")).isEqualTo(false);
        assertThat(properties.getProperty("proxy.host")).isEqualTo("127.0.0.1");
        assertThat(properties.getProperty("database.provider")).isEqualTo("fixture-database");
    }
}
