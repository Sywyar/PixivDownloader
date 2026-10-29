package top.sywyar.pixivdownload.transfer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.config.credential.PluginCredentialStore;
import top.sywyar.pixivdownload.plugin.api.gui.*;
import top.sywyar.pixivdownload.transfer.archive.TransferArchive;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("配置迁移的选择、预检和目标合并")
class ConfigurationTransferTest {
    @TempDir Path root;
    private final String previous = System.getProperty(RuntimeFiles.CONFIG_DIR_PROPERTY);

    @AfterEach void restoreProperties() {
        if (previous == null) System.clearProperty(RuntimeFiles.CONFIG_DIR_PROPERTY);
        else System.setProperty(RuntimeFiles.CONFIG_DIR_PROPERTY, previous);
    }

    @Test @DisplayName("默认只导出已保存普通字段，选择导入保留目标注释、未知键、凭据与数据库连接")
    void importsSelectedSavedFieldsWithoutWholeFileReplacement() throws Exception {
        var host = host();
        var plugin = plugin();
        var credentials = new PluginCredentialStore();
        var configuration = new ConfigurationTransfer(host, () -> List.of(plugin), credentials,
                top.sywyar.pixivdownload.plugin.runtime.status.RequiredPluginPolicy.empty());
        configure("source", "# source\nserver.port: 8123\nserver.ssl.key-store-password: source-secret\n"
                + "database.provider: source-provider\nunknown: source-unknown\ndownload.root-folder: D:/old\n");
        Files.createDirectories(RuntimeFiles.peekPluginConfigPath("fixture").getParent());
        Files.writeString(RuntimeFiles.peekPluginConfigPath("fixture"), "fixture.value=from-source\nfixture.unmanaged=source\n");
        credentials.update("fixture", Map.of("fixture.token", "private-token"));
        Path archive = root.resolve("settings");
        var codec = new TransferArchive();
        TransferArchive.Preview preview;
        try (var snapshot = configuration.export(false)) {
            preview = codec.write(archive, null, "fixture", snapshot.sources());
        }
        assertThat(preview.items()).allSatisfy(item -> assertThat(item.descriptor().category()).isEqualTo(TransferArchive.Category.CONFIG));
        configure("target", "# target comment\nserver.port: 7001\nserver.ssl.key-store-password: keep-secret\n"
                + "database.provider: keep-provider\nunknown: keep-unknown\ndownload.root-folder: target-downloads\n");
        Files.createDirectories(RuntimeFiles.peekPluginConfigPath("fixture").getParent());
        Files.writeString(RuntimeFiles.peekPluginConfigPath("fixture"), "# plugin comment\nfixture.value=old\nfixture.unmanaged=keep\n");
        try (var contents = codec.readSelected(archive, null, preview, ConfigurationTransfer.indices(preview))) {
            assertThat(new String(contents.get(0), java.nio.charset.StandardCharsets.UTF_8))
                    .doesNotContain("source-secret", "unknown", "database.provider");
            var changes = configuration.preview(preview, contents);
            Path staging = Files.createDirectory(root.resolve("rendered"));
            var port = new ConfigurationTransfer.Key("host", "server.port");
            var value = new ConfigurationTransfer.Key("fixture", "fixture.value");
            var rendered = configuration.render(preview, contents, changes, Set.of(port, value), Map.of(), staging);
            assertThat(Files.readString(RuntimeFiles.peekConfigYamlPath())).contains("server.port: 7001");
            var transaction = new FileRestoreTransaction(root.resolve("run"), rendered.targets());
            transaction.prepare(rendered.replacements());
            transaction.commit();
        }
        assertThat(Files.readString(RuntimeFiles.peekConfigYamlPath())).contains("# target comment", "server.port: 8123",
                "keep-secret", "keep-provider", "keep-unknown", "target-downloads").doesNotContain("# source");
        assertThat(Files.readString(RuntimeFiles.peekPluginConfigPath("fixture"))).contains("# plugin comment",
                "fixture.value=from-source", "fixture.unmanaged=keep");
        assertThat(RuntimeFiles.peekPluginCredentialPath("fixture")).doesNotExist();
    }

    @Test @DisplayName("敏感预览隐藏内容，路径只按明确字段映射，修改后的目标阻止导入")
    void redactsCredentialsAndMapsOnlyDeclaredPaths() throws Exception {
        var host = host();
        var configuration = new ConfigurationTransfer(host, List::of, new PluginCredentialStore(),
                top.sywyar.pixivdownload.plugin.runtime.status.RequiredPluginPolicy.empty());
        configure("source", "server.port: 8123\nserver.ssl.key-store-password: unique-source-secret\ndownload.root-folder: D:/old\n");
        Path archive = root.resolve("encrypted");
        char[] password = "fixture-password".toCharArray();
        var codec = new TransferArchive();
        TransferArchive.Preview preview;
        try (var snapshot = configuration.export(true)) { preview = codec.write(archive, password, "fixture", snapshot.sources()); }
        configure("target", "# target\nserver.port: 7001\ndownload.root-folder: downloads\n");
        try (var contents = codec.readSelected(archive, password, preview, ConfigurationTransfer.indices(preview))) {
            var changes = configuration.preview(preview, contents);
            assertThat(changes.toString()).doesNotContain("unique-source-secret");
            var path = new ConfigurationTransfer.Key("host", "download.root-folder");
            var secret = new ConfigurationTransfer.Key("host", "server.ssl.key-store-password");
            var rendered = configuration.render(preview, contents, changes, Set.of(path, secret),
                    Map.of(path, "E:/new"), Files.createDirectory(root.resolve("rendered")));
            var transaction = new FileRestoreTransaction(root.resolve("run"), rendered.targets());
            transaction.prepare(rendered.replacements());
            transaction.commit();
            assertThat(Files.readString(RuntimeFiles.peekConfigYamlPath())).contains("E:/new", "unique-source-secret", "server.port: 7001");
            assertThatThrownBy(() -> configuration.render(preview, contents, changes, Set.of(path), Map.of(),
                    Files.createDirectory(root.resolve("stale")))).hasMessage("transfer.target-changed");
        }
    }

    private void configure(String name, String text) throws Exception {
        Path config = Files.createDirectory(root.resolve(name));
        System.setProperty(RuntimeFiles.CONFIG_DIR_PROPERTY, config.toString());
        Files.writeString(RuntimeFiles.peekConfigYamlPath(), text);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"host", "properties", "credentials"})
    @DisplayName("渲染后未选字段被正常入口修改时，准备事务拒绝旧副本且保留新值")
    void rejectsConcurrentUnselectedChangesAfterRendering(String kind) throws Exception {
        var credentials = new PluginCredentialStore();
        var transfer = new ConfigurationTransfer(host(), () -> List.of(plugin()), credentials,
                top.sywyar.pixivdownload.plugin.runtime.status.RequiredPluginPolicy.empty());
        configure("source", "server.port: 8123\n");
        Files.createDirectories(RuntimeFiles.peekPluginConfigPath("fixture").getParent());
        Files.writeString(RuntimeFiles.peekPluginConfigPath("fixture"), "fixture.value=source\n");
        credentials.update("fixture", Map.of("fixture.token", "source-token"));
        Path bundle = root.resolve("settings");
        char[] password = "fixture-password".toCharArray();
        var codec = new TransferArchive();
        TransferArchive.Preview archive;
        try (var snapshot = transfer.export(true)) {
            archive = codec.write(bundle, password, "fixture", snapshot.sources());
        }
        configure("target", "server.port: 7001\nunselected: old\n");
        Files.createDirectories(RuntimeFiles.peekPluginConfigPath("fixture").getParent());
        Files.writeString(RuntimeFiles.peekPluginConfigPath("fixture"), "fixture.value=old\nfixture.unselected=old\n");
        credentials.update("fixture", Map.of("fixture.token", "old-token", "fixture.unselected", "old"));
        var selected = switch (kind) {
            case "host" -> new ConfigurationTransfer.Key("host", "server.port");
            case "properties" -> new ConfigurationTransfer.Key("fixture", "fixture.value");
            default -> new ConfigurationTransfer.Key("fixture", "fixture.token");
        };
        try (var contents = codec.readSelected(bundle, password, archive, ConfigurationTransfer.indices(archive))) {
            var rendered = transfer.render(archive, contents, transfer.preview(archive, contents),
                    Set.of(selected), Map.of(), Files.createDirectory(root.resolve("staging")));
            Path target;
            if (kind.equals("host")) {
                target = RuntimeFiles.peekConfigYamlPath();
                new top.sywyar.pixivdownload.gui.config.ConfigFileEditor(target).write("unselected", "concurrent-update");
            } else if (kind.equals("properties")) {
                target = RuntimeFiles.peekPluginConfigPath("fixture");
                new top.sywyar.pixivdownload.gui.config.PropertiesConfigFileEditor(target)
                        .writeAll(Map.of("fixture.unselected", "concurrent-update"));
            } else {
                target = RuntimeFiles.peekPluginCredentialPath("fixture");
                credentials.update("fixture", Map.of("fixture.unselected", "concurrent-update"));
            }
            byte[] latest = Files.readAllBytes(target);
            var transaction = new FileRestoreTransaction(root.resolve("run"), rendered.targets());
            assertThatThrownBy(() -> transaction.prepare(rendered.replacements()))
                    .isInstanceOf(java.io.IOException.class).hasMessage("transfer.target-changed");
            assertThat(Files.readAllBytes(target)).isEqualTo(latest);
            assertThat(root.resolve("run/journal.json")).doesNotExist();
            if (kind.equals("credentials")) assertThat(credentials.readForTransfer("fixture"))
                    .containsEntry("fixture.token", "old-token").containsEntry("fixture.unselected", "concurrent-update");
            else assertThat(Files.readString(target)).contains("concurrent-update").doesNotContain("8123", "=source");
        }
    }

    @Test @DisplayName("界面偏好随已保存配置导出，插件换代使旧预览失效")
    void includesPreferencesAndRejectsReplacedDeclarations() throws Exception {
        var current = new java.util.concurrent.atomic.AtomicReference<>(plugin());
        var configuration = new ConfigurationTransfer(host(), () -> List.of(current.get()), new PluginCredentialStore(),
                top.sywyar.pixivdownload.plugin.runtime.status.RequiredPluginPolicy.empty());
        configure("source", "app.language: en-US\napp.theme: dark\napp.gui-provider: fixture-ui\napp.config-menu-expand-all: true\n");
        var codec = new TransferArchive();
        Path archive = root.resolve("preferences");
        TransferArchive.Preview inspected;
        try (var snapshot = configuration.export(false)) { inspected = codec.write(archive, null, "fixture", snapshot.sources()); }
        configure("target", "app.theme: light\n");
        try (var contents = codec.readSelected(archive, null, inspected, ConfigurationTransfer.indices(inspected))) {
            var preview = configuration.preview(inspected, contents);
            assertThat(preview.changes()).extracting(change -> change.key().name()).containsExactlyInAnyOrder(
                    "app.language", "app.theme", "app.gui-provider", "app.config-menu-expand-all");
            var original = current.get();
            current.set(new DesktopUiPluginSnapshot(original.id(), false, original.packageId(), original.generation() + 1,
                    false, null, "", List.of(), original.configContributions(), List.of(), List.of(), List.of()));
            assertThatThrownBy(() -> configuration.render(inspected, contents, preview,
                    Set.of(new ConfigurationTransfer.Key("host", "app.theme")), Map.of(), root))
                    .hasMessage("transfer.target-changed");
            assertThat(Files.readString(RuntimeFiles.peekConfigYamlPath())).isEqualTo("app.theme: light\n");
        }
    }
    private DesktopUiHost host() {
        var host = mock(DesktopUiHost.class);
        when(host.coreConfigFields()).thenReturn(List.of(field("server.port", GuiConfigFieldType.PORT),
                field("server.ssl.key-store-password", GuiConfigFieldType.PASSWORD),
                field("database.provider", GuiConfigFieldType.STRING), field("download.root-folder", GuiConfigFieldType.PATH_DIR)));
        return host;
    }

    @Test @DisplayName("迁移已安装插件开关，目标必选策略拒绝禁用，未知界面偏好不写入")
    void validatesTogglesAndPreferences() throws Exception {
        var empty = top.sywyar.pixivdownload.plugin.runtime.status.RequiredPluginPolicy.empty();
        var transfer = new ConfigurationTransfer(host(), () -> List.of(plugin()), new PluginCredentialStore(), empty);
        configure("source", "plugins.fixture.enabled: false\napp.theme: missing-theme\n");
        var archive = new TransferArchive();
        Path file = root.resolve("toggles");
        TransferArchive.Preview inspected;
        try (var snapshot = transfer.export(false)) { inspected = archive.write(file, null, "fixture", snapshot.sources()); }
        configure("target", "# target\napp.theme: light\n");
        var toggle = new ConfigurationTransfer.Key("host", "plugins.fixture.enabled");
        try (var contents = archive.readSelected(file, null, inspected, ConfigurationTransfer.indices(inspected))) {
            var preview = transfer.preview(inspected, contents);
            assertThat(preview.changes()).anySatisfy(change -> {
                assertThat(change.key()).isEqualTo(toggle); assertThat(change.before()).isEqualTo("true");
                assertThat(change.after()).isEqualTo("false");
            });
            var rendered = transfer.render(inspected, contents, preview, Set.of(toggle), Map.of(), Files.createDirectory(root.resolve("toggle-rendered")));
            var transaction = new FileRestoreTransaction(root.resolve("toggle-run"), rendered.targets());
            transaction.prepare(rendered.replacements()); transaction.commit();
            assertThat(Files.readString(RuntimeFiles.peekConfigYamlPath())).contains("plugins.fixture.enabled: false", "# target");
            assertThatThrownBy(() -> transfer.render(inspected, contents, transfer.preview(inspected, contents),
                    Set.of(new ConfigurationTransfer.Key("host", "app.theme")), Map.of(), Files.createDirectory(root.resolve("bad-theme"))))
                    .hasMessage("transfer.config-value-invalid");
            var required = top.sywyar.pixivdownload.plugin.runtime.status.RequiredPluginPolicy.of(List.of(
                    new top.sywyar.pixivdownload.plugin.runtime.status.RequiredPluginPolicy.RequiredPlugin("fixture", null, false, "fixture.required")));
            var protectedTransfer = new ConfigurationTransfer(host(), () -> List.of(plugin()), new PluginCredentialStore(), required);
            assertThatThrownBy(() -> protectedTransfer.render(inspected, contents, protectedTransfer.preview(inspected, contents),
                    Set.of(toggle), Map.of(), Files.createDirectory(root.resolve("required"))))
                    .hasMessage("transfer.config-value-invalid");
        }
    }
    private DesktopUiPluginSnapshot plugin() {
        return new DesktopUiPluginSnapshot("fixture", false, "fixture", 1, false, null, "", List.of(),
                List.of(new GuiConfigContribution(List.of(field("fixture.value", GuiConfigFieldType.STRING),
                        field("fixture.token", GuiConfigFieldType.PASSWORD)))), List.of(), List.of(), List.of());
    }
    private GuiConfigFieldContribution field(String key, GuiConfigFieldType type) {
        return new GuiConfigFieldContribution(key, "fixture", "fixture.label", type, "", 1);
    }
}
