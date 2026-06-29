package top.sywyar.pixivdownload.core.metadata.sidecar;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("作品 meta sidecar 文件命名规则")
class WorkSidecarFilesTest {

    @Test
    @DisplayName("SIDECAR_SUFFIX 应为 .meta.json")
    void sidecarSuffixIsCorrect() {
        assertEquals(".meta.json", WorkSidecarFiles.SIDECAR_SUFFIX);
    }

    @Test
    @DisplayName("fileName(7) 应返回 7.meta.json")
    void fileNameReturnsCorrectName() {
        assertEquals("7.meta.json", WorkSidecarFiles.fileName(7));
    }

    @Test
    @DisplayName("fileName(123456789) 应返回 123456789.meta.json")
    void fileNameReturnsCorrectNameForLargeId() {
        assertEquals("123456789.meta.json", WorkSidecarFiles.fileName(123456789));
    }

    @Test
    @DisplayName("isSidecarFileName 应识别 .meta.json 结尾的文件名")
    void isSidecarFileNameRecognizesSidecar() {
        assertTrue(WorkSidecarFiles.isSidecarFileName("7.meta.json"));
        assertTrue(WorkSidecarFiles.isSidecarFileName("123456789.meta.json"));
        assertTrue(WorkSidecarFiles.isSidecarFileName("0.meta.json"));
        // 后缀匹配语义：空 stem 的退化文件名 ".meta.json" 同样以 .meta.json 结尾，按既有 endsWith 语义判为 sidecar。
        assertTrue(WorkSidecarFiles.isSidecarFileName(".meta.json"));
    }

    @Test
    @DisplayName("isSidecarFileName 应拒绝非 .meta.json 结尾的文件名")
    void isSidecarFileNameRejectsNonSidecar() {
        assertFalse(WorkSidecarFiles.isSidecarFileName("7.jpg"));
        assertFalse(WorkSidecarFiles.isSidecarFileName("7.png"));
        assertFalse(WorkSidecarFiles.isSidecarFileName("7.meta.json.txt"));
        assertFalse(WorkSidecarFiles.isSidecarFileName("meta.json"));
        assertFalse(WorkSidecarFiles.isSidecarFileName(""));
    }

    @Test
    @DisplayName("isSidecarFileName 对 null 应返回 false")
    void isSidecarFileNameReturnsFalseForNull() {
        assertFalse(WorkSidecarFiles.isSidecarFileName(null));
    }

    @Test
    @DisplayName("isSidecarFile 应识别 sidecar 路径")
    void isSidecarFileRecognizesSidecarPath() {
        assertTrue(WorkSidecarFiles.isSidecarFile(Paths.get("7.meta.json")));
        assertTrue(WorkSidecarFiles.isSidecarFile(Paths.get("/some/path/123456789.meta.json")));
        assertTrue(WorkSidecarFiles.isSidecarFile(Paths.get("C:\\pixiv-download\\7\\7.meta.json")));
    }

    @Test
    @DisplayName("isSidecarFile 应拒绝非 sidecar 路径")
    void isSidecarFileRejectsNonSidecarPath() {
        assertFalse(WorkSidecarFiles.isSidecarFile(Paths.get("7.jpg")));
        assertFalse(WorkSidecarFiles.isSidecarFile(Paths.get("/some/path/7.png")));
        assertFalse(WorkSidecarFiles.isSidecarFile(Paths.get("7.meta.json.txt")));
        assertFalse(WorkSidecarFiles.isSidecarFile(Paths.get("meta.json")));
    }

    @Test
    @DisplayName("isSidecarFile 对 null 路径应返回 false")
    void isSidecarFileReturnsFalseForNullPath() {
        assertFalse(WorkSidecarFiles.isSidecarFile((Path) null));
    }

    @Test
    @DisplayName("isSidecarFile 对无文件名的路径应返回 false")
    void isSidecarFileReturnsFalseForPathWithoutFileName() {
        // 某些边界情况（如根路径）可能没有文件名
        assertFalse(WorkSidecarFiles.isSidecarFile(Paths.get("/")));
    }
}
