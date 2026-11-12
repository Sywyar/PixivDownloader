package top.sywyar.pixivdownload.core.importing;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.gui.config.ConfigFileEditor;
import top.sywyar.pixivdownload.plugin.api.gui.importing.LocalArtworkImports.Preset;
import top.sywyar.pixivdownload.plugin.api.gui.importing.LocalArtworkImports.VariableException;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@DisplayName("本地导入预设设置的保存和校验")
class LocalImportConfigTest {
    @TempDir Path directory;
    @Test @DisplayName("预设保留变量错误详情且校验失败不覆盖已保存内容")
    void keepsVariableErrors() {
        var settings = new LocalImportConfig();
        for (String variable : List.of("foo", "ai")) {
            String json = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(List.of(
                    new Preset(directory + "/{artwork_id}_{" + variable + "}.png")));
            var error = org.junit.jupiter.api.Assertions.assertThrows(VariableException.class, () -> settings.setPresets(json));
            assertThat(error.variable()).isEqualTo(variable);
            assertThat(error.getMessage()).isEqualTo(variable.equals("ai") ? "IMPORT_VARIABLE_UNAVAILABLE" : "UNKNOWN_IMPORT_VARIABLE");
            assertThat(settings.getPresets()).isEqualTo("[]");
        }
    }
    @Test @DisplayName("扁平配置只保存路径模板并保留 Unicode 和无关配置")
    void savesPresets() throws Exception {
        var presets = List.of(new Preset(directory + "/作者/{author_id}/{artwork_id}/"),
                new Preset(directory + "/{artwork_id}.{extension}"));
        String value = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(presets);
        var editor = new ConfigFileEditor(directory.resolve("config.yaml"));
        editor.writeAll(Map.of("local-import.presets", value, "unrelated.setting", "keep"));
        assertThat(LocalImportConfig.parse(editor.read("local-import.presets"))).isEqualTo(presets);
        assertThat(editor.read("unrelated.setting")).isEqualTo("keep");
    }
    @Test @DisplayName("非法模板、缺少路径和多余字段不能静默覆盖有效配置")
    void rejectsInvalidConfiguration() {
        var settings = new LocalImportConfig();
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        String original = json.writeValueAsString(List.of(new Preset(directory + "/{artwork_id}/")));
        settings.setPresets(original);
        for (String value : List.of("{}", "null", "[{}]", "[{\"template\":12}]", "[{\"template\":\"\"}]",
                "[{\"template\":\"relative/{artwork_id}/\"}]", "[{\"template\":\"x\",\"unexpected\":true}]",
                json.writeValueAsString(Collections.nCopies(33, new Preset(directory + "/{artwork_id}/")))))
            assertThatThrownBy(() -> settings.setPresets(value)).hasMessage("INVALID_IMPORT_PRESET");
        assertThat(settings.getPresets()).isEqualTo(original);
        settings.setPresets(json.writeValueAsString(Collections.nCopies(32, new Preset(directory + "/{artwork_id}/"))));
        assertThat(settings.entries()).hasSize(32);
    }

    @Test @DisplayName("已有预设保留输入路径，不受旧名称和停用标记影响")
    void readsOnlyPaths() {
        String template = directory + "/{artwork_id}/";
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        var entries = LocalImportConfig.parse(json.writeValueAsString(List.of(
                Map.of("name", "same", "template", template, "enabled", false),
                Map.of("name", "same", "template", template, "enabled", true))));
        assertThat(entries).containsExactly(new Preset(template), new Preset(template));
    }
}
