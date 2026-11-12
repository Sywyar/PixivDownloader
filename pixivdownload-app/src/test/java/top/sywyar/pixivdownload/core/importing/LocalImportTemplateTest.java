package top.sywyar.pixivdownload.core.importing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Set;
import top.sywyar.pixivdownload.plugin.api.gui.importing.LocalArtworkImports.VariableException;
import static org.junit.jupiter.api.Assertions.*;

class LocalImportTemplateTest {
    @Test @DisplayName("共用变量定义不把下载专用值当成本地来源事实")
    void rejectsDownloadOnlyVariables() {
        for (String token : java.util.List.of("timestamp", "count", "ai", "ai+", "R18", "R18+", "TIMESTAMP")) {
            var failure = assertThrows(VariableException.class,
                    () -> new LocalImportTemplate(root + "/{artwork_id}_{" + token + "}.png"));
            assertEquals("IMPORT_VARIABLE_UNAVAILABLE", failure.getMessage());
            assertEquals(token, failure.variable());
        }
    }
    @Test @DisplayName("未知变量保留用户拼写，异常消息不包含变量或路径")
    void unknownVariableKeepsItsNameSeparate() {
        var failure = assertThrows(VariableException.class,
                () -> new LocalImportTemplate(root + "/{artworkId}.{Ex}"));
        assertEquals("UNKNOWN_IMPORT_VARIABLE", failure.getMessage());
        assertEquals("Ex", failure.variable());
    }
    @TempDir Path root;

    @Test @DisplayName("目录 ID 与多个变量反向解析，未提供页码保持未知")
    void directoryAndAliases() {
        var template = new LocalImportTemplate(root + "/{author_id}/{artWorkId}/");
        var match = template.match(root.resolve("10/123/sub/pic.png"));
        assertEquals(123, match.artworkId());
        assertNull(match.page());
        assertEquals("10", match.hints().get("authorId"));
        assertNull(template.match(root.resolve("bad/123/pic.png")));
    }

    @Test @DisplayName("文件模板精确匹配扩展名、页码和字面文本")
    void filenames() {
        var template = new LocalImportTemplate(root + "/{artworkId}_p{page}_{name}.{extension}");
        var match = template.match(root.resolve("123_p4_drawing.png"));
        assertEquals(4, match.page());
        assertEquals("png", match.hints().get("extension"));
        assertNull(template.match(root.resolve("sub/123_p4_drawing.png")));
        assertEquals("a.b", template.match(root.resolve("123_p4_a.b.png")).hints().get("name"));
    }

    @Test @DisplayName("重复 ID 必须一致，非法与歧义模板拒绝")
    void rejectsAmbiguity() {
        var template = new LocalImportTemplate(root + "/{artworkId}/{artwork_id}.{extension}");
        assertNotNull(template.match(root.resolve("12/12.png")));
        assertNull(template.match(root.resolve("12/13.png")));
        assertThrows(IllegalArgumentException.class, () -> new LocalImportTemplate(root + "/{name}/"));
        assertThrows(IllegalArgumentException.class, () -> new LocalImportTemplate(root + "/{artworkId}{name}.png"));
        assertThrows(IllegalArgumentException.class, () -> new LocalImportTemplate(root + "/../{artworkId}/"));
        var ambiguous = new LocalImportTemplate(root + "/{artworkId}_{authorName}_{name}.png");
        assertThrows(IllegalArgumentException.class, () -> ambiguous.match(root.resolve("12_a_b_c.png")));
    }

    @Test @DisplayName("已知 ID 可以直接限定扫描目录")
    void directRoots() {
        var template = new LocalImportTemplate(root + "/{artworkId}/");
        assertEquals(java.util.List.of(root.resolve("12"), root.resolve("34")), template.searchRoots(Set.of(34L, 12L)));
    }
}
