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

    private List<Path> stagingSubdirectories() throws IOException {
        try (var children = Files.list(RuntimeFiles.deleteStagingDirectory())) {
            return children.filter(Files::isDirectory).toList();
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
    @DisplayName("回滚中某文件复制失败：返回 false，暂存子目录与恢复清单保留，未复原文件留有暂存备份")
    void retainsStagingWhenRollbackCopyFails() throws Exception {
        Path dir = Files.createDirectories(tempDir.resolve("work"));
        Path a = Files.writeString(dir.resolve("a.jpg"), "a");
        Path b = Files.writeString(dir.resolve("b.jpg"), "b");
        Path c = Files.writeString(dir.resolve("c.txt"), "c");

        // 删 c 触发回滚；回滚复原 b 时再失败 → 整体回滚未完全成功
        StagedFileDeletion failing = failDeleteAndRestore(c, b);

        assertFalse(failing.deleteAtomically(List.of(a, b, c)));

        assertTrue(Files.exists(a), "a 已删后被回滚复原");
        assertFalse(Files.exists(b), "b 已删但回滚复制失败，未能复原");
        assertTrue(Files.exists(c), "c 删除失败、从未被删");

        List<Path> residue = stagingSubdirectories();
        assertEquals(1, residue.size(), "回滚未完全成功应保留暂存子目录");
        Path subdir = residue.get(0);
        assertTrue(Files.exists(subdir.resolve("manifest.properties")), "恢复清单应保留");
        assertTrue(Files.exists(subdir.resolve("1_b.jpg")), "未复原的 b 应留有暂存备份");
    }

    @Test
    @DisplayName("启动恢复接管回滚失败的残留：据清单把未复原的文件从暂存复原并清理暂存")
    void startupRecoveryRestoresFileLostByRollbackFailure() throws Exception {
        Path dir = Files.createDirectories(tempDir.resolve("work"));
        Path a = Files.writeString(dir.resolve("a.jpg"), "a");
        Path b = Files.writeString(dir.resolve("b.jpg"), "b");
        Path c = Files.writeString(dir.resolve("c.txt"), "c");

        assertFalse(failDeleteAndRestore(c, b).deleteAtomically(List.of(a, b, c)));
        assertFalse(Files.exists(b), "前置：b 因回滚复制失败而缺失");
        assertEquals(1, stagingSubdirectories().size(), "前置：暂存子目录被保留");

        // 模拟下次启动：恢复入口据清单把仍缺失的 b 从暂存复原
        RuntimeFiles.recoverDeleteStagingLeftovers();

        assertTrue(Files.exists(b), "启动恢复应复原 b");
        assertEquals("b", Files.readString(b), "复原内容应一致");
        assertEquals(0, stagingResidueCount(), "全部复原后暂存子目录应被清理");
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

    /** 删除 {@code deletePoison} 时抛 IOException（触发回滚），回滚复原 {@code restorePoison} 时再抛 IOException。 */
    private static StagedFileDeletion failDeleteAndRestore(Path deletePoison, Path restorePoison) {
        Path deleteTarget = deletePoison.toAbsolutePath().normalize();
        Path restoreTarget = restorePoison.toAbsolutePath().normalize();
        return new StagedFileDeletion(TestI18nBeans.appMessages()) {
            @Override
            void deleteFile(Path original) throws IOException {
                if (original.toAbsolutePath().normalize().equals(deleteTarget)) {
                    throw new IOException("simulated delete lock on " + original);
                }
                super.deleteFile(original);
            }

            @Override
            void restoreFile(Path staged, Path original) throws IOException {
                if (original.toAbsolutePath().normalize().equals(restoreTarget)) {
                    throw new IOException("simulated restore lock on " + original);
                }
                super.restoreFile(staged, original);
            }
        };
    }
}
