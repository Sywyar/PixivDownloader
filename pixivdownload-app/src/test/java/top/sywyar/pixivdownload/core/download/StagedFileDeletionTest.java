package top.sywyar.pixivdownload.core.download;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.core.asset.StagedFileDeletion;
import top.sywyar.pixivdownload.core.asset.StagedFileDeletion.UnsafeDeletionPathException;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
            protected void deleteFile(Path original) throws IOException {
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
    @DisplayName("回滚目标被并发创建为相同内容时视为已复原且清理暂存")
    void acceptsConcurrentSameContentRecreation() throws Exception {
        Path dir = Files.createDirectories(tempDir.resolve("same-content"));
        Path restored = Files.writeString(dir.resolve("a.jpg"), "a");
        Path poison = Files.writeString(dir.resolve("b.jpg"), "b");

        assertFalse(failAfterRecreating(poison, restored, "a")
                .deleteAtomically(List.of(restored, poison)));

        assertEquals("a", Files.readString(restored));
        assertEquals(0, stagingResidueCount(), "相同内容已就位时暂存目录应无残留");
    }

    @Test
    @DisplayName("回滚目标被并发创建为不同内容时不覆盖并保留恢复清单")
    void retainsStagingForConcurrentDifferentContent() throws Exception {
        Path dir = Files.createDirectories(tempDir.resolve("different-content"));
        Path restored = Files.writeString(dir.resolve("a.jpg"), "old");
        Path poison = Files.writeString(dir.resolve("b.jpg"), "b");

        assertFalse(failAfterRecreating(poison, restored, "new")
                .deleteAtomically(List.of(restored, poison)));

        assertEquals("new", Files.readString(restored), "并发创建的新内容不得被回滚覆盖");
        Path stagingDir = stagingSubdirectories().get(0);
        assertTrue(Files.exists(stagingDir.resolve("manifest.properties")), "恢复清单应保留");
        assertEquals("old", Files.readString(stagingDir.resolve("0_a.jpg")), "原内容应保留在暂存备份中");
    }

    @Test
    @DisplayName("回滚目标被并发创建为非普通文件时不触碰并保留恢复清单")
    void retainsStagingForConcurrentUnsafeTarget() throws Exception {
        Path dir = Files.createDirectories(tempDir.resolve("unsafe-target"));
        Path restored = Files.writeString(dir.resolve("a.jpg"), "a");
        Path poison = Files.writeString(dir.resolve("b.jpg"), "b");
        Path normalizedPoison = poison.toAbsolutePath().normalize();
        StagedFileDeletion failing = new StagedFileDeletion(TestI18nBeans.appMessages()) {
            @Override
            protected void deleteFile(Path original) throws IOException {
                if (original.toAbsolutePath().normalize().equals(normalizedPoison)) {
                    Files.createDirectory(restored);
                    throw new IOException("simulated delete lock");
                }
                super.deleteFile(original);
            }
        };

        assertFalse(failing.deleteAtomically(List.of(restored, poison)));

        assertTrue(Files.isDirectory(restored), "并发创建的目录不得被回滚替换");
        Path stagingDir = stagingSubdirectories().get(0);
        assertTrue(Files.exists(stagingDir.resolve("manifest.properties")), "恢复清单应保留");
        assertEquals("a", Files.readString(stagingDir.resolve("0_a.jpg")), "原内容应保留在暂存备份中");
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
        RuntimeFiles.recoverDeleteStagingLeftovers(tempDir.toString());

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
    @DisplayName("缺失路径是幂等 no-op，但存在的非普通文件使整批删除失败")
    void rejectsExistingUnsafeFilesBeforeDeletingAnything() throws Exception {
        Path dir = Files.createDirectories(tempDir.resolve("work"));
        Path file = Files.writeString(dir.resolve("keep.jpg"), "x");
        Path subDir = Files.createDirectories(dir.resolve("subdir"));
        Path missing = dir.resolve("missing.jpg");

        UnsafeDeletionPathException exception = assertThrows(
                UnsafeDeletionPathException.class,
                () -> deletion.deleteAtomically(List.of(file, subDir, missing)));

        assertEquals(subDir.toAbsolutePath().normalize().toString(), exception.path());
        assertTrue(Files.exists(file), "发现不安全路径后不得删除同批普通文件");
        assertTrue(Files.isDirectory(subDir), "目录不得被当成成功过滤项");
        assertEquals(0, stagingResidueCount(), "暂存目录应无残留");
    }

    @Test
    @DisplayName("符号链接文件和符号链接父目录使删除失败且不触达链接目标")
    void doesNotDeleteThroughSymbolicLinks() throws Exception {
        Path outsideDir = Files.createDirectories(tempDir.resolve("outside"));
        Path outsideFile = Files.writeString(outsideDir.resolve("outside.jpg"), "outside");
        Path work = Files.createDirectories(tempDir.resolve("work-links"));
        Path fileLink = work.resolve("file-link.jpg");
        Path directoryLink = work.resolve("directory-link");
        createSymbolicLinkOrSkip(fileLink, outsideFile);
        createSymbolicLinkOrSkip(directoryLink, outsideDir);

        UnsafeDeletionPathException exception = assertThrows(
                UnsafeDeletionPathException.class,
                () -> deletion.deleteAtomically(List.of(fileLink, directoryLink.resolve("outside.jpg"))));

        assertEquals(fileLink.toAbsolutePath().normalize().toString(), exception.path());
        assertTrue(Files.exists(outsideFile), "链接指向的外部文件不得被删除");
        assertTrue(Files.exists(fileLink, LinkOption.NOFOLLOW_LINKS), "文件链接本身也不属于作品普通文件");
        assertTrue(Files.exists(directoryLink, LinkOption.NOFOLLOW_LINKS), "链接父目录不得被清理");
        assertEquals(0, stagingResidueCount(), "拒绝链接路径后不应留下暂存残留");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("Windows Junction 使整批删除在暂存前失败")
    void rejectsWindowsJunctionBeforeStaging() throws Exception {
        Path outsideDir = Files.createDirectories(tempDir.resolve("junction-target"));
        Path outsideFile = Files.writeString(outsideDir.resolve("outside.jpg"), "outside");
        Path work = Files.createDirectories(tempDir.resolve("junction-work"));
        Path regular = Files.writeString(work.resolve("keep.jpg"), "keep");
        Path junction = work.resolve("junction");
        Process process = new ProcessBuilder(
                "cmd.exe", "/c", "mklink", "/J", junction.toString(), outsideDir.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        Assumptions.assumeTrue(process.waitFor() == 0, "当前 Windows 环境无法创建 Junction");

        try {
            UnsafeDeletionPathException exception = assertThrows(
                    UnsafeDeletionPathException.class,
                    () -> deletion.deleteAtomically(List.of(regular, junction)));

            assertEquals(junction.toAbsolutePath().normalize().toString(), exception.path());
            assertTrue(Files.exists(regular), "拒绝 Junction 后不得删除同批普通文件");
            assertTrue(Files.exists(outsideFile), "Junction 指向的目录不得被触达");
            assertEquals(0, stagingResidueCount(), "拒绝 Junction 后不应创建暂存内容");
        } finally {
            Files.deleteIfExists(junction);
        }
    }

    /** 删除 {@code deletePoison} 时抛 IOException（触发回滚），回滚复原 {@code restorePoison} 时再抛 IOException。 */
    private static StagedFileDeletion failDeleteAndRestore(Path deletePoison, Path restorePoison) {
        Path deleteTarget = deletePoison.toAbsolutePath().normalize();
        Path restoreTarget = restorePoison.toAbsolutePath().normalize();
        return new StagedFileDeletion(TestI18nBeans.appMessages()) {
            @Override
            protected void deleteFile(Path original) throws IOException {
                if (original.toAbsolutePath().normalize().equals(deleteTarget)) {
                    throw new IOException("simulated delete lock on " + original);
                }
                super.deleteFile(original);
            }

            @Override
            protected void restoreFile(Path staged, Path original) throws IOException {
                if (original.toAbsolutePath().normalize().equals(restoreTarget)) {
                    throw new IOException("simulated restore lock on " + original);
                }
                super.restoreFile(staged, original);
            }
        };
    }

    private static StagedFileDeletion failAfterRecreating(Path deletePoison, Path restoreTarget, String content) {
        Path normalizedPoison = deletePoison.toAbsolutePath().normalize();
        return new StagedFileDeletion(TestI18nBeans.appMessages()) {
            @Override
            protected void deleteFile(Path original) throws IOException {
                if (original.toAbsolutePath().normalize().equals(normalizedPoison)) {
                    Files.writeString(restoreTarget, content);
                    throw new IOException("simulated delete lock");
                }
                super.deleteFile(original);
            }
        };
    }

    private static void createSymbolicLinkOrSkip(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            Assumptions.assumeTrue(false, "当前文件系统不支持测试符号链接: " + unavailable.getMessage());
        }
    }
}
