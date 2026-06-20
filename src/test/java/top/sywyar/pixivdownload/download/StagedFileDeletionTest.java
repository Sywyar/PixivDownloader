package top.sywyar.pixivdownload.download;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("StagedFileDeletion 原子删除（暂存 + 回滚）")
class StagedFileDeletionTest {

    @TempDir
    Path tempDir;

    private final StagedFileDeletion deletion = new StagedFileDeletion(TestI18nBeans.appMessages());

    @BeforeEach
    void isolateStagingDirectory() {
        System.setProperty(RuntimeFiles.DATA_DIR_PROPERTY, tempDir.resolve("rt-data").toString());
    }

    @AfterEach
    void clearStagingDirectoryProperty() {
        System.clearProperty(RuntimeFiles.DATA_DIR_PROPERTY);
    }

    private long stagingResidueCount() throws IOException {
        Path stagingRoot = RuntimeFiles.deleteStagingDirectory();
        try (var children = Files.list(stagingRoot)) {
            return children.count();
        }
    }

    @Test
    @DisplayName("全部删除成功：文件删净、暂存已清、返回 true")
    void deletesAllFilesAndCleansStaging() throws Exception {
        Path dir = Files.createDirectories(tempDir.resolve("work"));
        Path a = Files.writeString(dir.resolve("a.jpg"), "a");
        Path b = Files.writeString(dir.resolve("b.jpg"), "b");
        Path c = Files.writeString(dir.resolve("c.txt"), "c");

        assertTrue(deletion.deleteAtomically(List.of(a, b, c)));

        assertFalse(Files.exists(a));
        assertFalse(Files.exists(b));
        assertFalse(Files.exists(c));
        assertEquals(0, stagingResidueCount(), "成功删除后暂存目录应无残留");
    }

    @Test
    @DisplayName("某文件删除失败：全部原文件复原、暂存已清、返回 false")
    void rollsBackAllFilesWhenOneDeletionFails() throws Exception {
        Path dir = Files.createDirectories(tempDir.resolve("work"));
        Path a = Files.writeString(dir.resolve("a.jpg"), "a");
        Path b = Files.writeString(dir.resolve("b.jpg"), "b");
        Path c = Files.writeString(dir.resolve("c.txt"), "c");
        Path poison = b.toAbsolutePath().normalize();

        StagedFileDeletion failing = new StagedFileDeletion(TestI18nBeans.appMessages()) {
            @Override
            void deleteFile(Path original) throws IOException {
                if (original.toAbsolutePath().normalize().equals(poison)) {
                    throw new IOException("simulated lock");
                }
                super.deleteFile(original);
            }
        };

        assertFalse(failing.deleteAtomically(List.of(a, b, c)));

        // 无论枚举顺序如何，失败后所有原文件都应复原、内容不变
        assertTrue(Files.exists(a));
        assertTrue(Files.exists(b));
        assertTrue(Files.exists(c));
        assertEquals("a", Files.readString(a));
        assertEquals("b", Files.readString(b));
        assertEquals("c", Files.readString(c));
        assertEquals(0, stagingResidueCount(), "回滚后暂存目录应无残留");
    }

    @Test
    @DisplayName("空集合 / 无可删文件视为成功，返回 true")
    void emptyInputIsNoOp() throws Exception {
        assertTrue(deletion.deleteAtomically(List.of()));
        assertTrue(deletion.deleteAtomically(null));
        Path missing = tempDir.resolve("does-not-exist.jpg");
        assertTrue(deletion.deleteAtomically(List.of(missing)));
    }

    @Test
    @DisplayName("只删除存在的常规文件，忽略缺失路径与目录")
    void onlyDeletesExistingRegularFiles() throws Exception {
        Path dir = Files.createDirectories(tempDir.resolve("work"));
        Path file = Files.writeString(dir.resolve("keep.jpg"), "x");
        Path subDir = Files.createDirectories(dir.resolve("subdir"));
        Path missing = dir.resolve("missing.jpg");

        assertTrue(deletion.deleteAtomically(List.of(file, subDir, missing)));

        assertFalse(Files.exists(file), "存在的常规文件应被删除");
        assertTrue(Files.isDirectory(subDir), "目录不应被删除");
        assertEquals(0, stagingResidueCount(), "暂存目录应无残留");
    }
}
