package top.sywyar.pixivdownload.gui.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("插件 properties 配置编辑器")
class PropertiesConfigFileEditorTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("写入值应按 properties 规则转义并原样读回")
    void shouldEscapeAndRoundTripValues() throws Exception {
        Path file = tempDir.resolve("fixture.properties");
        PropertiesConfigFileEditor editor = new PropertiesConfigFileEditor(file);
        String value = "  a\\b\nc\t ";

        editor.writeAll(Map.of("fixture.value", value));

        assertThat(editor.readAll(Map.of("fixture.value", "").keySet()))
                .containsEntry("fixture.value", value);
        assertThat(Files.readString(file, StandardCharsets.UTF_8))
                .contains("fixture.value=\\ ")
                .contains("\\\\b")
                .contains("\\n")
                .contains("\\t")
                .contains("\\ ");
    }

    @Test
    @DisplayName("重复活跃 key 应拒绝读取和写入，避免覆盖含糊")
    void shouldRejectDuplicateManagedKeys() throws Exception {
        Path file = tempDir.resolve("fixture.properties");
        Files.writeString(file, String.join("\n",
                "fixture.value=one",
                "fixture.value=two",
                ""), StandardCharsets.UTF_8);
        PropertiesConfigFileEditor editor = new PropertiesConfigFileEditor(file);

        assertThatThrownBy(() -> editor.readAll(Map.of("fixture.value", "").keySet()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Duplicate active plugin config key");
        assertThatThrownBy(() -> editor.writeAll(Map.of("fixture.value", "three")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Duplicate active plugin config key");

        assertThat(Files.readString(file, StandardCharsets.UTF_8)).contains("fixture.value=one");
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).contains("fixture.value=two");
    }

    @Test
    @DisplayName("快照恢复应能回到写入前状态")
    void shouldRestoreSnapshot() throws Exception {
        Path file = tempDir.resolve("fixture.properties");
        PropertiesConfigFileEditor editor = new PropertiesConfigFileEditor(file);
        PropertiesConfigFileEditor.FileSnapshot snapshot = editor.snapshot();

        editor.writeAll(Map.of("fixture.value", "new"));
        editor.restore(snapshot);

        assertThat(file).doesNotExist();
    }

    @Test
    @DisplayName("移除托管 key 时保留其它 properties 内容")
    void shouldRemoveManagedKeysOnly() throws Exception {
        Path file = tempDir.resolve("fixture.properties");
        Files.writeString(file, String.join("\n",
                "# keep comment",
                "fixture.api-key=legacy",
                "fixture.mode=direct",
                ""), StandardCharsets.UTF_8);
        PropertiesConfigFileEditor editor = new PropertiesConfigFileEditor(file);

        editor.removeAll(Map.of("fixture.api-key", "").keySet());

        assertThat(editor.readAll(Map.of("fixture.api-key", "").keySet())).isEmpty();
        assertThat(Files.readString(file, StandardCharsets.UTF_8))
                .contains("# keep comment")
                .contains("fixture.mode=direct")
                .doesNotContain("fixture.api-key");
    }

    @Test
    @DisplayName("迁移应识别空白分隔和无分隔符的旧 properties 凭证")
    void shouldRemoveWhitespaceSeparatedAndValuelessManagedKeys() throws Exception {
        Path file = tempDir.resolve("fixture.properties");
        Files.writeString(file, String.join("\n",
                "fixture.api-key legacy-api-key",
                "fixture.token",
                "fixture.mode=direct",
                ""), StandardCharsets.UTF_8);
        PropertiesConfigFileEditor editor = new PropertiesConfigFileEditor(file);

        assertThat(editor.readAll(Map.of(
                "fixture.api-key", "",
                "fixture.token", "").keySet()))
                .containsEntry("fixture.api-key", "legacy-api-key")
                .containsEntry("fixture.token", "");

        editor.removeAll(Map.of(
                "fixture.api-key", "",
                "fixture.token", "").keySet());

        assertThat(editor.readAll(Map.of(
                "fixture.api-key", "",
                "fixture.token", "").keySet())).isEmpty();
        assertThat(Files.readString(file, StandardCharsets.UTF_8))
                .contains("fixture.mode=direct")
                .doesNotContain("fixture.api-key", "fixture.token");
    }

    @ParameterizedTest
    @ValueSource(strings = {"fixture.api\\u002dkey", "fixture.api\\-key", "fixture.api-\\\n  key"})
    @DisplayName("转义或续行键的保存与移除必须定位同一个配置项")
    void shouldEditDecodedKeys(String encodedKey) throws Exception {
        Path file = tempDir.resolve("fixture.properties");
        String untouched = "# 保留说明\nother.value=未修改\n";
        Files.writeString(file, encodedKey + "=before\n" + untouched, StandardCharsets.UTF_8);
        var editor = new PropertiesConfigFileEditor(file);

        editor.writeAll(Map.of("fixture.api-key", "after"));

        assertThat(load(file)).containsEntry("fixture.api-key", "after");
        assertThat(editor.readAll(List.of("fixture.api-key"))).containsEntry("fixture.api-key", "after");
        editor.removeAll(List.of("fixture.api-key"));
        assertThat(load(file)).doesNotContainKey("fixture.api-key");
        assertThat(Files.readAllLines(file, StandardCharsets.UTF_8)).containsExactly("# 保留说明", "other.value=未修改");

        Files.writeString(file, encodedKey + "=legacy\n" + untouched, StandardCharsets.UTF_8);
        editor.removeAll(List.of("fixture.api-key"));
        assertThat(load(file)).doesNotContainKey("fixture.api-key");
    }

    @Test
    @DisplayName("替换或删除续行值时不把其内容变成其它配置")
    void shouldEditWholeLogicalEntry() throws Exception {
        Path file = tempDir.resolve("fixture.properties");
        String original = "# comment\\\nfixture.value=before\\\n  fixture.injected=part\\\n  !still-value\nother.value=keep\\\\\n";
        var editor = new PropertiesConfigFileEditor(file);
        for (boolean remove : List.of(false, true)) {
            Files.writeString(file, original, StandardCharsets.UTF_8);
            assertThat(load(file)).doesNotContainKey("fixture.injected");
            if (remove) editor.removeAll(List.of("fixture.value"));
            else editor.writeAll(Map.of("fixture.value", "after"));
            Properties result = load(file);
            assertThat(result).doesNotContainKey("fixture.injected").containsEntry("other.value", "keep\\");
            if (remove) assertThat(result).doesNotContainKey("fixture.value");
            else assertThat(result).containsEntry("fixture.value", "after");
            assertThat(Files.readAllLines(file, StandardCharsets.UTF_8))
                    .contains("# comment\\", "other.value=keep\\\\")
                    .doesNotContain("  fixture.injected=part\\", "  !still-value");
        }
    }

    @Test
    @DisplayName("重复键按解码后的身份拒绝且失败不改文件")
    void shouldRejectEscapedDuplicateBeforeEditing() throws Exception {
        Path file = tempDir.resolve("fixture.properties");
        String original = "fixture.value=first\nfixture.val\\u0075e=second\n";
        Files.writeString(file, original, StandardCharsets.UTF_8);
        var editor = new PropertiesConfigFileEditor(file);

        assertThatThrownBy(() -> editor.readAll(List.of("fixture.value"))).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> editor.writeAll(Map.of("fixture.value", "after"))).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> editor.removeAll(List.of("fixture.value"))).isInstanceOf(IOException.class);
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo(original);
    }

    @Test
    @DisplayName("非法转义拒绝读取和编辑且保留原文件")
    void shouldPreserveFileWithInvalidEscape() throws Exception {
        Path file = tempDir.resolve("fixture.properties");
        String original = "fixture.value=before\nother.value=\\uZZZZ\n";
        Files.writeString(file, original, StandardCharsets.UTF_8);
        var editor = new PropertiesConfigFileEditor(file);

        assertThatThrownBy(() -> editor.readAll(List.of("fixture.value"))).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> editor.writeAll(Map.of("fixture.value", "after"))).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> editor.removeAll(List.of("fixture.value"))).isInstanceOf(IOException.class);
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo(original);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "fixture.value=before\\",
            "\\\n  fixture.value=before\nother.value=keep\n",
            "\\\n # comment\\\n fixture.value=before\nother.value=keep\n",
            "fixture.value=before\\\n \t\f\nother.value=keep\n",
            "fixture.value=before\\\\\\\n  tail\nother.value=keep\n",
            "fixture.value=before\\\n  # tail\nother.value=keep\n"
    })
    @DisplayName("续行边界与 JDK 读取一致且编辑不改变其它键值")
    void shouldPreserveContinuationBoundaries(String original) throws Exception {
        Path file = tempDir.resolve("fixture.properties");
        var editor = new PropertiesConfigFileEditor(file);
        for (boolean remove : List.of(false, true)) {
            Files.writeString(file, original, StandardCharsets.UTF_8);
            Properties expected = load(file);
            assertThat(editor.readAll(List.of("fixture.value")))
                    .containsEntry("fixture.value", expected.getProperty("fixture.value"));
            if (remove) {
                editor.removeAll(List.of("fixture.value"));
                expected.remove("fixture.value");
            } else {
                editor.writeAll(Map.of("fixture.value", "after"));
                expected.setProperty("fixture.value", "after");
            }
            assertThat(load(file)).isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("批量保存保留原位置并按请求顺序读取和追加缺失键")
    void shouldEditMultipleEntriesInOrder() throws Exception {
        Path file = tempDir.resolve("fixture.properties");
        Files.writeString(file, "fixture.fir\\u0073t=old\\\n  tail\n# keep\nother.value=keep\nfixture.second=old\n",
                StandardCharsets.UTF_8);
        Map<String, String> values = new LinkedHashMap<>();
        values.put("fixture.second", "two");
        values.put("fixture.first", "one");
        values.put("fixture.third", "three");
        var editor = new PropertiesConfigFileEditor(file);

        editor.writeAll(values);

        assertThat(editor.readAll(values.keySet())).containsExactlyEntriesOf(values);
        assertThat(load(file)).containsAllEntriesOf(values).containsEntry("other.value", "keep");
        assertThat(Files.readAllLines(file, StandardCharsets.UTF_8)).containsExactly(
                "fixture.first=one", "# keep", "other.value=keep", "fixture.second=two", "fixture.third=three");
        editor.removeAll(values.keySet());
        assertThat(Files.readAllLines(file, StandardCharsets.UTF_8)).containsExactly("# keep", "other.value=keep");
    }

    private static Properties load(Path file) throws IOException {
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }
}
