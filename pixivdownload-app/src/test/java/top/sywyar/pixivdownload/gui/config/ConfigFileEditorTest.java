package top.sywyar.pixivdownload.gui.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.FileSystemResource;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ConfigFileEditor 安全往返单元测试")
class ConfigFileEditorTest {

    @TempDir
    Path tempDir;

    private Path writeTemplate(String... lines) throws IOException {
        Path file = tempDir.resolve("config.yaml");
        Files.write(file, List.of(lines), StandardCharsets.UTF_8);
        return file;
    }

    @ParameterizedTest
    @ValueSource(strings = {"server.port", "'server.port'", "\"server.port\"", "\"server\\u002eport\""})
    @DisplayName("标量读写与删除识别引号和转义键，保留其它行与注释")
    void quotedKeysRoundTrip(String key) throws IOException {
        Path file = writeTemplate("# 配置", key + "  : 7011  # 端口", "server.port.extra: keep");
        byte[] original = Files.readAllBytes(file);
        ConfigFileEditor editor = new ConfigFileEditor(file);

        assertThat(editor.read("server.port")).isEqualTo("7011");
        assertThat(editor.readAll(List.of("server.port"))).containsExactlyEntriesOf(Map.of("server.port", "7011"));
        assertThat(Files.readAllBytes(file)).isEqualTo(original);
        editor.write("server.port", "7012");
        assertThat(Files.readAllLines(file)).containsExactly("# 配置", "server.port: 7012  # 端口", "server.port.extra: keep");

        Files.write(file, original);
        editor.writeAll(Map.of("server.port", "7013"));
        var sources = new YamlPropertySourceLoader().load("config", new FileSystemResource(file));
        assertThat(sources.get(0).getProperty("server.port")).isEqualTo(7013);
        assertThat(Files.readAllLines(file)).containsExactly("# 配置", "server.port: 7013  # 端口", "server.port.extra: keep");

        Files.write(file, original);
        editor.removeAll(List.of("server.port"));
        assertThat(Files.readAllLines(file)).containsExactly("# 配置", "server.port.extra: keep");
    }

    @Test
    @DisplayName("注释中的引号键不参与读取，保存时恢复为活跃条目")
    void quotedCommentedKeyIsOnlyRestoredWhenWriting() throws IOException {
        Path file = writeTemplate("# 'server.port': 7011  # 端口");
        ConfigFileEditor editor = new ConfigFileEditor(file);

        assertThat(editor.read("server.port")).isNull();
        assertThat(editor.readAll(List.of("server.port"))).isEmpty();
        editor.writeAll(Map.of("server.port", "7012"));
        assertThat(Files.readAllLines(file)).containsExactly("server.port: 7012  # 端口");
    }

    @Test
    @DisplayName("桌面保存失败回滚与市场保存串行，回滚不覆盖另一句柄的成功写入")
    void rollbackCannotOverwriteConcurrentSave() throws Exception {
        Path file = writeTemplate("database.provider: original", "database.password: keep");
        var desktop = new ConfigFileEditor(file).asConfigFile();
        var market = new ConfigFileEditor(file).asConfigFile();
        var written = new java.util.concurrent.CountDownLatch(1);
        var restore = new java.util.concurrent.CountDownLatch(1);
        var saving = new java.util.concurrent.CountDownLatch(1);
        var workers = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var failedSave = workers.submit(() -> {
                desktop.withWriteLock(() -> {
                    var snapshot = desktop.snapshot();
                    desktop.write("database.provider", "desktop-draft");
                    written.countDown();
                    try {
                        if (!restore.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IOException("fixture timeout");
                    } catch (InterruptedException stop) { Thread.currentThread().interrupt(); throw new IOException(stop); }
                    desktop.restore(snapshot);
                });
                return null;
            });
            assertThat(written.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var saved = workers.submit(() -> {
                saving.countDown();
                market.withWriteLock(() -> market.write("database.provider", "market-choice"));
                return null;
            });
            assertThat(saving.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> saved.get(100, java.util.concurrent.TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            restore.countDown();
            failedSave.get(5, java.util.concurrent.TimeUnit.SECONDS);
            saved.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(market.read("database.provider")).isEqualTo("market-choice");
            assertThat(market.read("database.password")).isEqualTo("keep");
        } finally { restore.countDown(); workers.shutdownNow(); }
    }

    @Test
    @DisplayName("含特殊字符的 Webhook JSON 模板写入后能原样读回，且是合法 YAML")
    void jsonTemplateRoundTrips() throws IOException {
        Path file = writeTemplate("push.webhook.body-template:  # 请求体模板");
        ConfigFileEditor editor = new ConfigFileEditor(file);
        String template = "{\"title\":\"{{title}}\",\"content\":\"{{content}}\"}";

        editor.write("push.webhook.body-template", template);

        // 经 ConfigFileEditor 读回一致
        assertThat(editor.read("push.webhook.body-template")).isEqualTo(template);
        // 经真正的 YAML 解析也一致（证明没有破坏 config.yaml）
        Map<String, Object> parsed = new Yaml().load(Files.readString(file, StandardCharsets.UTF_8));
        assertThat(flatten(parsed)).containsEntry("push.webhook.body-template", template);
        // 行尾注释保留
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).contains("# 请求体模板");
    }

    @Test
    @DisplayName("六十进制串（10:00）加引号持久化并原样读回")
    void sexagesimalIsQuoted() throws IOException {
        Path file = writeTemplate("maintenance.monday.time: \"10:00\"");
        ConfigFileEditor editor = new ConfigFileEditor(file);

        editor.write("maintenance.monday.time", "10:00");

        assertThat(editor.read("maintenance.monday.time")).isEqualTo("10:00");
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).contains("\"10:00\"");
    }

    @Test
    @DisplayName("普通标量（布尔 / 数字 / URL / content-type）不加引号")
    void plainScalarsStayBare() throws IOException {
        Path file = writeTemplate(
                "push.enabled: true",
                "server.port: 6999",
                "push.bark.server: https://api.day.app",
                "push.webhook.content-type: application/json");
        ConfigFileEditor editor = new ConfigFileEditor(file);

        editor.writeAll(Map.of(
                "push.enabled", "false",
                "server.port", "7000",
                "push.bark.server", "https://api.day.app",
                "push.webhook.content-type", "application/json"));

        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(content).contains("push.enabled: false");
        assertThat(content).contains("server.port: 7000");
        assertThat(content).contains("push.bark.server: https://api.day.app");
        assertThat(content).contains("push.webhook.content-type: application/json");
        assertThat(content).doesNotContain("\"false\"").doesNotContain("\"https://api.day.app\"");
    }

    @Test
    @DisplayName("含 # 的值加引号、且不被误当作行尾注释")
    void valueWithHashIsQuotedAndRoundTrips() throws IOException {
        Path file = writeTemplate("push.wecom.key:");
        ConfigFileEditor editor = new ConfigFileEditor(file);
        String value = "abc#def";

        editor.write("push.wecom.key", value);

        assertThat(editor.read("push.wecom.key")).isEqualTo(value);
        Map<String, Object> parsed = new Yaml().load(Files.readString(file, StandardCharsets.UTF_8));
        assertThat(flatten(parsed)).containsEntry("push.wecom.key", value);
    }

    @Test
    @DisplayName("双引号值首尾空格应原样读回")
    void quotedValueSpacesAreReadBackExactly() throws IOException {
        String value = "  abc # hash  ";
        Path file = writeTemplate("push.wecom.key: \"  abc # hash  \"");
        ConfigFileEditor editor = new ConfigFileEditor(file);

        assertThat(editor.read("push.wecom.key")).isEqualTo(value);
        assertThat(editor.readAll(List.of("push.wecom.key"))).containsEntry("push.wecom.key", value);
    }

    @Test
    @DisplayName("单引号路径保留反斜杠、井号和转义单引号，读取不回写文件")
    void singleQuotedValuesAreReadWithoutRewriting() throws IOException {
        Path file = writeTemplate(
                "download.root-folder: 'D:\\artist''s # archive'  # 下载目录",
                "ffmpeg.executable-path: 'C:\\Program Files\\FFmpeg\\ffmpeg.exe'",
                "app.language: 'en'",
                "empty.value: ''",
                "spaced.value: '  # value  '");
        byte[] before = Files.readAllBytes(file);
        ConfigFileEditor editor = new ConfigFileEditor(file);
        Map<String, String> expected = Map.of(
                "download.root-folder", "D:\\artist's # archive",
                "ffmpeg.executable-path", "C:\\Program Files\\FFmpeg\\ffmpeg.exe",
                "app.language", "en",
                "empty.value", "",
                "spaced.value", "  # value  ");

        for (var entry : expected.entrySet()) {
            assertThat(editor.read(entry.getKey())).isEqualTo(entry.getValue());
        }
        assertThat(editor.readAll(expected.keySet())).containsExactlyInAnyOrderEntriesOf(expected);
        assertThat(Files.readAllBytes(file)).isEqualTo(before);
    }

    @Test
    @DisplayName("覆盖单引号值时只保留真正的行尾注释，其它配置不变")
    void replacingSingleQuotedValuesPreservesOnlyInlineComments() throws IOException {
        Path file = writeTemplate(
                "# Paths",
                "download.root-folder: 'D:/artist''s # old'  # 下载目录",
                "ffmpeg.executable-path: 'D:/tools#old/ffmpeg.exe'",
                "server.port: 6999  # untouched");
        ConfigFileEditor editor = new ConfigFileEditor(file);

        editor.write("download.root-folder", "D:/new");
        editor.writeAll(Map.of("ffmpeg.executable-path", "D:/tools/ffmpeg.exe"));

        assertThat(Files.readAllLines(file, StandardCharsets.UTF_8)).containsExactly(
                "# Paths",
                "download.root-folder: D:/new  # 下载目录",
                "ffmpeg.executable-path: D:/tools/ffmpeg.exe",
                "server.port: 6999  # untouched");
        Map<String, Object> parsed = new Yaml().load(Files.readString(file, StandardCharsets.UTF_8));
        assertThat(parsed).containsEntry("download.root-folder", "D:/new")
                .containsEntry("ffmpeg.executable-path", "D:/tools/ffmpeg.exe");
    }

    @Test
    @DisplayName("非法配置 key 被拒绝且不改写文件")
    void unsafeKeyIsRejectedWithoutChangingFile() throws IOException {
        Path file = writeTemplate("server.port: 6999");
        ConfigFileEditor editor = new ConfigFileEditor(file);

        assertThatThrownBy(() -> editor.write("bad:key", "1"))
                .isInstanceOf(IOException.class);

        assertThat(Files.readString(file, StandardCharsets.UTF_8))
                .contains("server.port: 6999")
                .doesNotContain("bad:key");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> flatten(Map<String, Object> tree) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> e : tree.entrySet()) {
            if (e.getValue() instanceof Map<?, ?> child) {
                flatten((Map<String, Object>) child).forEach((k, v) -> out.put(e.getKey() + "." + k, v));
            } else if (e.getValue() != null) {
                out.put(e.getKey(), String.valueOf(e.getValue()));
            }
        }
        return out;
    }
}
