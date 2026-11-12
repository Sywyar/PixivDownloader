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
                    new Preset("Pictures", directory + "/{artwork_id}_{" + variable + "}.png", true)));
            var error = org.junit.jupiter.api.Assertions.assertThrows(VariableException.class, () -> settings.setPresets(json));
            assertThat(error.variable()).isEqualTo(variable);
            assertThat(error.getMessage()).isEqualTo(variable.equals("ai") ? "IMPORT_VARIABLE_UNAVAILABLE" : "UNKNOWN_IMPORT_VARIABLE");
            assertThat(settings.getPresets()).isEqualTo("[]");
        }
    }
    @Test @DisplayName("扁平配置保存保留变量、Unicode、启用状态与无关配置")
    void savesPresets() throws Exception {
        var presets = List.of(new Preset("作者作品", directory + "/{author_id}/{artwork_id}/", true),
                new Preset("Disabled", directory + "/{artwork_id}.{extension}", false));
        String value = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(presets);
        var editor = new ConfigFileEditor(directory.resolve("config.yaml"));
        editor.writeAll(Map.of("local-import.presets", value, "unrelated.setting", "keep"));
        assertThat(LocalImportConfig.parse(editor.read("local-import.presets"))).isEqualTo(presets);
        assertThat(editor.read("unrelated.setting")).isEqualTo("keep");
    }
    @Test @DisplayName("重复名称、非法模板、空列表对象和缺少开关不能静默覆盖有效配置")
    void rejectsInvalidConfiguration() {
        var settings = new LocalImportConfig();
        for (String value : List.of("{}", "null", "[{}]", "[{\"name\":\"x\",\"template\":\"relative/{artwork_id}/\",\"enabled\":true}]"))
            assertThatThrownBy(() -> settings.setPresets(value)).hasMessage("INVALID_IMPORT_PRESET");
        assertThat(settings.entries()).isEmpty();
    }
}
