package top.sywyar.pixivdownload.gui.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
