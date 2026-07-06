package top.sywyar.pixivdownload.gui.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

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
}
