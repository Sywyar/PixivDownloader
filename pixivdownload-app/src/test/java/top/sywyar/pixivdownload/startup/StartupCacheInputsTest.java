package top.sywyar.pixivdownload.startup;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.sywyar.pixivdownload.plugin.catalog.trust.PluginCatalogTrustStateStore;
import top.sywyar.pixivdownload.plugin.catalog.security.PluginCatalogStrictJson;
import tools.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

@DisplayName("桌面启动训练输入")
class StartupCacheInputsTest {
    @TempDir Path directory;

    private StartupCacheInputs.Snapshot capture(Path plugins, Path config) throws Exception {
        return StartupCacheInputs.capture(plugins, config, directory.resolve("trust.json"));
    }

    @Test @DisplayName("安装、卸载、改名和保留时间戳的更新均使缓存失效")
    void artifactChanges() throws Exception {
        Path plugins = Files.createDirectory(directory.resolve("plugins"));
        Path config = directory.resolve("config.yaml");
        Path first = Files.writeString(plugins.resolve("first.jar"), "original", StandardCharsets.UTF_8);
        String key = capture(plugins, config).identity();
        var timestamp = Files.getLastModifiedTime(first);
        Files.writeString(first, "modified", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(first, timestamp);
        assertThat(capture(plugins, config).identity()).isNotEqualTo(key);
        Files.writeString(first, "original", StandardCharsets.UTF_8);
        Path second = Files.writeString(plugins.resolve("second.zip"), "added", StandardCharsets.UTF_8);
        assertThat(capture(plugins, config).identity()).isNotEqualTo(key);
        Files.delete(second);
        assertThat(capture(plugins, config).identity()).isEqualTo(key);
        Files.move(first, plugins.resolve("renamed.jar"));
        assertThat(capture(plugins, config).identity()).isNotEqualTo(key);
        Files.delete(plugins.resolve("renamed.jar"));
        assertThat(capture(plugins, config).identity()).isNotEqualTo(key);
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
        var inputs = capture(plugins, directory.resolve("config.yaml"));
        Files.writeString(artifact, "modified", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> StartupCacheInputs.copy(inputs, directory.resolve("copy1/plugins"),
                directory.resolve("copy1/config.yaml"), directory.resolve("copy1/trust.json"))).isInstanceOf(StartupCacheInputs.InputsChanged.class);
        Files.writeString(artifact, "original", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> StartupCacheInputs.copy(inputs, directory.resolve("copy2/plugins"),
                directory.resolve("copy2/config.yaml"), directory.resolve("copy2/trust.json"))).isInstanceOf(java.io.IOException.class);
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

    @Test @DisplayName("撤销快照增加、替换或删除使冻结输入失效，损坏状态不能降级为空")
    void trustStateChanges() throws Exception {
        Path plugins = Files.createDirectory(directory.resolve("plugins"));
        Path config = directory.resolve("config.yaml");
        Path trust = directory.resolve("trust.json");
        var original = capture(plugins, config);
        var store = new top.sywyar.pixivdownload.plugin.catalog.trust.PluginCatalogTrustStateStore(trust);
        String now = java.time.Instant.now().toString();
        store.acceptRevocations("custom", new top.sywyar.pixivdownload.plugin.catalog.trust.PluginCatalogTrustStateStore.RevocationSnapshot(
                1, "a".repeat(64), now, now, now, java.util.List.of()));
        assertThatThrownBy(() -> StartupCacheInputs.verifyCurrent(original)).isInstanceOf(StartupCacheInputs.InputsChanged.class);
        var frozen = capture(plugins, config);
        store.acceptRevocations("custom", new top.sywyar.pixivdownload.plugin.catalog.trust.PluginCatalogTrustStateStore.RevocationSnapshot(
                2, "b".repeat(64), now, now, now, java.util.List.of()));
        assertThatThrownBy(() -> StartupCacheInputs.copy(frozen, directory.resolve("copy/plugins"),
                directory.resolve("copy/config.yaml"), directory.resolve("copy/trust.json")))
                .isInstanceOf(StartupCacheInputs.InputsChanged.class);
        assertThat(directory.resolve("copy")).doesNotExist();
        Files.delete(trust);
        assertThatThrownBy(() -> StartupCacheInputs.verifyCurrent(frozen)).isInstanceOf(StartupCacheInputs.InputsChanged.class);
        Files.writeString(trust, "invalid", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> capture(plugins, config)).isInstanceOf(java.io.IOException.class);
    }

    @Test @DisplayName("重复验证不使输入失效，训练复制仍保留冻结时的完整撤销快照")
    void refreshedSnapshotPreservesIdentityAndFrozenCopy() throws Exception {
        Path plugins = Files.createDirectory(directory.resolve("plugins"));
        Path config = directory.resolve("config.yaml");
        var store = new PluginCatalogTrustStateStore(directory.resolve("trust.json"));
        var original = new PluginCatalogTrustStateStore.RevocationSnapshot(
                1, "a".repeat(64), "2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z",
                "2026-01-02T00:00:00Z", java.util.List.of());
        store.acceptRevocations("custom", original);
        var frozen = capture(plugins, config);
        var refreshed = new PluginCatalogTrustStateStore.RevocationSnapshot(
                original.sequence(), original.documentSha256(), original.generatedTime(), original.nextUpdate(),
                "2026-01-03T00:00:00Z", original.entries());
        store.acceptRevocations("custom", refreshed);
        assertThat(capture(plugins, config).identity()).isEqualTo(frozen.identity());
        StartupCacheInputs.verifyCurrent(frozen);
        Path copiedState = directory.resolve("copy/trust.json");
        StartupCacheInputs.copy(frozen, directory.resolve("copy/plugins"), directory.resolve("copy/config.yaml"), copiedState);
        assertThat(new PluginCatalogTrustStateStore(copiedState).revocations("custom")).contains(original);
        assertThat(store.revocations("custom")).contains(refreshed);
    }

    @ParameterizedTest
    @ValueSource(strings = {"sequence", "documentSha256", "generatedTime", "nextUpdate", "entries"})
    @DisplayName("撤销序列、文档摘要、时效与条目各自变化均使缓存失效")
    void revocationContentChanges(String field) throws Exception {
        Path plugins = Files.createDirectory(directory.resolve("plugins"));
        Path config = directory.resolve("config.yaml");
        Path trust = directory.resolve("trust.json");
        new PluginCatalogTrustStateStore(trust).acceptRevocations("custom",
                new PluginCatalogTrustStateStore.RevocationSnapshot(1, "a".repeat(64),
                        "2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z", "2026-01-02T00:00:00Z", java.util.List.of()));
        var frozen = capture(plugins, config);
        var mapper = PluginCatalogStrictJson.mapper(false);
        var state = mapper.readTree(Files.readAllBytes(trust));
        var changed = (ObjectNode) state.path("repositories").path("custom").path("revocations");
        // 独立改变持久化输入，避免序列或摘要同时变化掩盖某个字段漏入身份的问题。
        switch (field) {
            case "sequence" -> changed.put(field, 2);
            case "documentSha256" -> changed.put(field, "b".repeat(64));
            case "entries" -> changed.putArray(field).addObject()
                    .put("scope", "PACKAGE_SHA256").put("packageSha256", "c".repeat(64)).put("action", "REVOKED");
            default -> changed.put(field, "2026-03-01T00:00:00Z");
        }
        Files.write(trust, mapper.writeValueAsBytes(state));
        assertThat(capture(plugins, config).identity()).isNotEqualTo(frozen.identity());
        assertThatThrownBy(() -> StartupCacheInputs.verifyCurrent(frozen)).isInstanceOf(StartupCacheInputs.InputsChanged.class);
    }
}
