package top.sywyar.pixivdownload.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DeleteStagingManifest 删除暂存恢复清单与启动恢复")
class DeleteStagingManifestTest {

    @TempDir
    Path tempDir;

    private DeleteStagingManifest.Entry entry(Path original, String stagedName) {
        return new DeleteStagingManifest.Entry(original.toAbsolutePath().normalize(), stagedName);
    }

    @Test
    @DisplayName("清单写入后可原样读回（含反斜杠 / 非 ASCII 字符的路径）")
    void writesAndReadsBackEntries() throws IOException {
        Path stagingDir = Files.createDirectories(tempDir.resolve("op"));
        Path original = tempDir.resolve("作品 目录").resolve("a b").resolve("300_p0.jpg");

        DeleteStagingManifest.write(stagingDir, List.of(entry(original, "0_300_p0.jpg")));
        Optional<List<DeleteStagingManifest.Entry>> read = DeleteStagingManifest.read(stagingDir);

        assertThat(read).isPresent();
        assertThat(read.get()).hasSize(1);
        assertThat(read.get().get(0).originalFile()).isEqualTo(original.toAbsolutePath().normalize());
        assertThat(read.get().get(0).stagedFileName()).isEqualTo("0_300_p0.jpg");
    }

    @Test
    @DisplayName("恢复：缺失的原文件从暂存复制回原位、原目录被重建，全部就位后删除暂存子目录")
    void recoversMissingOriginalsAndCleansSubdirectory() throws IOException {
        Path stagingRoot = Files.createDirectories(tempDir.resolve("delete-staging"));
        Path subdir = Files.createDirectories(stagingRoot.resolve("op"));
        // 原目录此刻不存在（删除时随空目录被移除），恢复需重建
        Path original = tempDir.resolve("300").resolve("300_p0.jpg");
        Files.writeString(subdir.resolve("0_300_p0.jpg"), "p0-bytes", StandardCharsets.UTF_8);
        DeleteStagingManifest.write(subdir, List.of(entry(original, "0_300_p0.jpg")));

        DeleteStagingManifest.recoverLeftovers(stagingRoot, List.of(tempDir));

        assertThat(original).exists();
        assertThat(Files.readString(original, StandardCharsets.UTF_8)).isEqualTo("p0-bytes");
        assertThat(subdir).doesNotExist();
    }

    @Test
    @DisplayName("恢复：原文件仍在时不覆盖（按现有文件为准），但仍清理暂存子目录")
    void doesNotOverwriteExistingOriginal() throws IOException {
        Path stagingRoot = Files.createDirectories(tempDir.resolve("delete-staging"));
        Path subdir = Files.createDirectories(stagingRoot.resolve("op"));
        Path original = Files.writeString(
                Files.createDirectories(tempDir.resolve("300")).resolve("300_p0.jpg"),
                "current", StandardCharsets.UTF_8);
        Files.writeString(subdir.resolve("0_300_p0.jpg"), "stale-staged", StandardCharsets.UTF_8);
        DeleteStagingManifest.write(subdir, List.of(entry(original, "0_300_p0.jpg")));

        DeleteStagingManifest.recoverLeftovers(stagingRoot, List.of(tempDir));

        assertThat(Files.readString(original, StandardCharsets.UTF_8)).isEqualTo("current");
        assertThat(subdir).doesNotExist();
    }

    @Test
    @DisplayName("恢复：原文件已缺失且暂存副本也不存在时保留暂存子目录（该份不可恢复）")
    void keepsSubdirectoryWhenStagedCopyMissing() throws IOException {
        Path stagingRoot = Files.createDirectories(tempDir.resolve("delete-staging"));
        Path subdir = Files.createDirectories(stagingRoot.resolve("op"));
        Path original = tempDir.resolve("300").resolve("300_p0.jpg"); // 缺失
        // 故意不写入暂存副本，模拟连备份也丢失
        DeleteStagingManifest.write(subdir, List.of(entry(original, "0_300_p0.jpg")));

        DeleteStagingManifest.recoverLeftovers(stagingRoot, List.of(tempDir));

        assertThat(original).doesNotExist();
        assertThat(subdir).isDirectory();
        assertThat(subdir.resolve(DeleteStagingManifest.MANIFEST_FILE_NAME)).exists();
    }

    @Test
    @DisplayName("恢复：清单缺失的暂存子目录一律保留，绝不删除唯一备份")
    void keepsSubdirectoryWhenManifestMissing() throws IOException {
        Path stagingRoot = Files.createDirectories(tempDir.resolve("delete-staging"));
        Path subdir = Files.createDirectories(stagingRoot.resolve("op"));
        Path stagedBackup = Files.writeString(subdir.resolve("0_a.jpg"), "a", StandardCharsets.UTF_8);

        DeleteStagingManifest.recoverLeftovers(stagingRoot, List.of(tempDir));

        assertThat(subdir).isDirectory();
        assertThat(stagedBackup).exists();
        assertThat(DeleteStagingManifest.read(subdir)).isEmpty();
    }

    @Test
    @DisplayName("恢复：暂存副本名非「子目录内单个文件名」（绝对路径 / 分隔符 / .. 逃逸）视为清单损坏，保留子目录")
    void keepsSubdirectoryWhenStagedNameEscapesSubdir() throws IOException {
        Path stagingRoot = Files.createDirectories(tempDir.resolve("delete-staging"));
        Path original = tempDir.resolve("300").resolve("300_p0.jpg");
        List<String> unsafeNames = List.of(
                "../evil.jpg",
                "sub/evil.jpg",
                "a\\b.jpg",
                tempDir.resolve("outside.jpg").toAbsolutePath().toString());

        for (int i = 0; i < unsafeNames.size(); i++) {
            Path subdir = Files.createDirectories(stagingRoot.resolve("op-" + i));
            Files.writeString(subdir.resolve("0_x.jpg"), "backup", StandardCharsets.UTF_8);
            Properties manifest = new Properties();
            manifest.setProperty("version", "1");
            manifest.setProperty("count", "1");
            manifest.setProperty("0.original", original.toAbsolutePath().normalize().toString());
            manifest.setProperty("0.staged", unsafeNames.get(i));
            try (var writer = Files.newBufferedWriter(
                    subdir.resolve(DeleteStagingManifest.MANIFEST_FILE_NAME), StandardCharsets.UTF_8)) {
                manifest.store(writer, "untrusted manifest fixture");
            }

            assertThat(DeleteStagingManifest.read(subdir))
                    .as("暂存副本名 %s 应被判为损坏", unsafeNames.get(i))
                    .isEmpty();
        }

        DeleteStagingManifest.recoverLeftovers(stagingRoot, List.of(tempDir));

        // 损坏清单一律保留子目录，且不会据越权来源把任何文件写到原文件位置
        assertThat(original).doesNotExist();
        try (var children = Files.list(stagingRoot)) {
            assertThat(children.filter(Files::isDirectory).count()).isEqualTo(unsafeNames.size());
        }
    }

    @Test
    @DisplayName("恢复：清单损坏（count 非法）视为不可读，保留暂存子目录")
    void keepsSubdirectoryWhenManifestCorrupt() throws IOException {
        Path stagingRoot = Files.createDirectories(tempDir.resolve("delete-staging"));
        Path subdir = Files.createDirectories(stagingRoot.resolve("op"));
        Files.writeString(subdir.resolve("0_x.jpg"), "x", StandardCharsets.UTF_8);
        Files.writeString(subdir.resolve(DeleteStagingManifest.MANIFEST_FILE_NAME),
                "version=1\ncount=not-a-number\n", StandardCharsets.UTF_8);

        DeleteStagingManifest.recoverLeftovers(stagingRoot, List.of(tempDir));

        assertThat(subdir).isDirectory();
        assertThat(DeleteStagingManifest.read(subdir)).isEmpty();
    }

    @Test
    @DisplayName("恢复：空清单或含未声明字段的清单视为损坏并保留全部暂存文件")
    void keepsSubdirectoryForEmptyOrUndeclaredManifestEntries() throws IOException {
        Path stagingRoot = Files.createDirectories(tempDir.resolve("delete-staging"));
        Path original = tempDir.resolve("300/300_p0.jpg").toAbsolutePath();

        Path emptySubdir = Files.createDirectories(stagingRoot.resolve("empty"));
        Path emptyBackup = Files.writeString(emptySubdir.resolve("0_x.jpg"), "x", StandardCharsets.UTF_8);
        Files.writeString(emptySubdir.resolve(DeleteStagingManifest.MANIFEST_FILE_NAME),
                "version=1\ncount=0\n", StandardCharsets.UTF_8);

        Path extraSubdir = Files.createDirectories(stagingRoot.resolve("extra"));
        Path extraBackup = Files.writeString(extraSubdir.resolve("0_x.jpg"), "x", StandardCharsets.UTF_8);
        Properties extraManifest = new Properties();
        extraManifest.setProperty("version", "1");
        extraManifest.setProperty("count", "1");
        extraManifest.setProperty("0.original", original.toString());
        extraManifest.setProperty("0.staged", "0_x.jpg");
        extraManifest.setProperty("unexpected", "value");
        try (var writer = Files.newBufferedWriter(
                extraSubdir.resolve(DeleteStagingManifest.MANIFEST_FILE_NAME), StandardCharsets.UTF_8)) {
            extraManifest.store(writer, "untrusted manifest fixture");
        }

        DeleteStagingManifest.recoverLeftovers(stagingRoot, List.of(tempDir));

        assertThat(DeleteStagingManifest.read(emptySubdir)).isEmpty();
        assertThat(DeleteStagingManifest.read(extraSubdir)).isEmpty();
        assertThat(emptyBackup).exists();
        assertThat(extraBackup).exists();
    }

    @Test
    @DisplayName("恢复：暂存目录含清单未声明的文件时完成恢复但保留整个目录")
    void keepsSubdirectoryWhenItContainsUndeclaredFiles() throws IOException {
        Path stagingRoot = Files.createDirectories(tempDir.resolve("delete-staging"));
        Path subdir = Files.createDirectories(stagingRoot.resolve("op"));
        Path original = tempDir.resolve("300/300_p0.jpg").toAbsolutePath();
        Path staged = Files.writeString(subdir.resolve("0_x.jpg"), "x", StandardCharsets.UTF_8);
        Path undeclared = Files.writeString(subdir.resolve("unlisted-backup.jpg"), "backup", StandardCharsets.UTF_8);
        DeleteStagingManifest.write(subdir, List.of(entry(original, staged.getFileName().toString())));

        DeleteStagingManifest.recoverLeftovers(stagingRoot, List.of(tempDir));

        assertThat(original).hasContent("x");
        assertThat(subdir).isDirectory();
        assertThat(staged).exists();
        assertThat(undeclared).hasContent("backup");
        assertThat(subdir.resolve(DeleteStagingManifest.MANIFEST_FILE_NAME)).exists();
    }

    @Test
    @DisplayName("清单拒绝超限条目数、超长字段和重复目标或暂存项")
    void rejectsResourceLimitAndDuplicateViolations() throws IOException {
        Path stagingDir = Files.createDirectories(tempDir.resolve("bounded"));
        Path original = tempDir.resolve("300/300_p0.jpg").toAbsolutePath();

        assertThatThrownBy(() -> DeleteStagingManifest.write(stagingDir, List.of()))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> DeleteStagingManifest.write(stagingDir,
                java.util.Collections.nCopies(DeleteStagingManifest.MAX_ENTRIES + 1,
                        entry(original, "0.jpg"))))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> DeleteStagingManifest.write(stagingDir, List.of(
                entry(original, "0.jpg"), entry(original, "1.jpg"))))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> DeleteStagingManifest.write(stagingDir, List.of(
                entry(original, "0.jpg"), entry(tempDir.resolve("other.jpg"), "0.jpg"))))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> DeleteStagingManifest.write(stagingDir, List.of(entry(
                tempDir.resolve("a".repeat(DeleteStagingManifest.MAX_ORIGINAL_PATH_CHARS)), "0.jpg"))))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> DeleteStagingManifest.write(stagingDir, List.of(
                entry(original, "a".repeat(DeleteStagingManifest.MAX_STAGED_FILE_NAME_CHARS + 1)))))
                .isInstanceOf(IOException.class);

        Files.writeString(stagingDir.resolve(DeleteStagingManifest.MANIFEST_FILE_NAME),
                "version=1\ncount=" + (DeleteStagingManifest.MAX_ENTRIES + 1) + "\n",
                StandardCharsets.UTF_8);
        assertThat(DeleteStagingManifest.read(stagingDir)).isEmpty();

        Files.writeString(stagingDir.resolve(DeleteStagingManifest.MANIFEST_FILE_NAME),
                "x".repeat((int) DeleteStagingManifest.MAX_MANIFEST_BYTES + 1), StandardCharsets.UTF_8);
        assertThat(DeleteStagingManifest.read(stagingDir)).isEmpty();
    }

    @Test
    @DisplayName("恢复复制拒绝覆盖其它操作已经创建的目标")
    void restoreCopyNeverOverwritesExistingTarget() throws IOException {
        Path staged = Files.writeString(tempDir.resolve("staged.jpg"), "staged", StandardCharsets.UTF_8);
        Path original = Files.writeString(tempDir.resolve("target.jpg"), "new", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> DeleteStagingManifest.copyRestoredFile(staged, original))
                .isInstanceOf(FileAlreadyExistsException.class);
        assertThat(original).hasContent("new");
    }

    @Test
    @DisplayName("恢复清单指向授权根外部时不写入并保留暂存副本")
    void rejectsOriginalOutsideAllowedRoots() throws IOException {
        Path stagingRoot = Files.createDirectories(tempDir.resolve("delete-staging-outside"));
        Path subdir = Files.createDirectories(stagingRoot.resolve("op"));
        Path allowedRoot = Files.createDirectories(tempDir.resolve("allowed"));
        Path outside = tempDir.resolveSibling(tempDir.getFileName() + "-outside").resolve("restored.jpg");
        Files.writeString(subdir.resolve("0_restored.jpg"), "backup", StandardCharsets.UTF_8);
        DeleteStagingManifest.write(subdir, List.of(entry(outside, "0_restored.jpg")));

        DeleteStagingManifest.recoverLeftovers(stagingRoot, List.of(allowedRoot));

        assertThat(outside).doesNotExist();
        assertThat(subdir).isDirectory();
    }

    @Test
    @DisplayName("恢复拒绝相对原路径与经过符号链接父目录的目标")
    void rejectsRelativeAndSymbolicLinkOriginalPaths() throws IOException {
        Path relativeSubdir = Files.createDirectories(tempDir.resolve("delete-staging/relative"));
        Files.writeString(relativeSubdir.resolve("0_x.jpg"), "x", StandardCharsets.UTF_8);
        Files.writeString(relativeSubdir.resolve(DeleteStagingManifest.MANIFEST_FILE_NAME),
                "version=1\ncount=1\n0.original=relative/x.jpg\n0.staged=0_x.jpg\n",
                StandardCharsets.UTF_8);
        assertThat(DeleteStagingManifest.read(relativeSubdir)).isEmpty();

        Path stagingRoot = Files.createDirectories(tempDir.resolve("delete-staging-links"));
        Path subdir = Files.createDirectories(stagingRoot.resolve("op"));
        Path outside = Files.createDirectories(tempDir.resolve("outside-restore"));
        Path linkedParent = tempDir.resolve("linked-restore");
        createSymbolicLinkOrSkip(linkedParent, outside);
        Path original = linkedParent.resolve("restored.jpg");
        Files.writeString(subdir.resolve("0_restored.jpg"), "backup", StandardCharsets.UTF_8);
        DeleteStagingManifest.write(subdir, List.of(entry(original, "0_restored.jpg")));

        DeleteStagingManifest.recoverLeftovers(stagingRoot, List.of(tempDir));

        assertThat(outside.resolve("restored.jpg")).doesNotExist();
        assertThat(subdir).isDirectory();
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("恢复拒绝经过 Windows Junction 父目录写入授权根外部")
    void rejectsWindowsJunctionParentDuringRecovery() throws Exception {
        Path stagingRoot = Files.createDirectories(tempDir.resolve("delete-staging-junction"));
        Path subdir = Files.createDirectories(stagingRoot.resolve("op"));
        Path allowedRoot = Files.createDirectories(tempDir.resolve("allowed"));
        Path outside = Files.createDirectories(tempDir.resolve("outside-junction"));
        Path junction = allowedRoot.resolve("linked");
        Process process = new ProcessBuilder(
                "cmd.exe", "/c", "mklink", "/J", junction.toString(), outside.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        Assumptions.assumeTrue(process.waitFor() == 0, "当前 Windows 环境无法创建 Junction");

        try {
            Path staged = Files.writeString(subdir.resolve("0_restored.jpg"), "backup", StandardCharsets.UTF_8);
            DeleteStagingManifest.write(subdir,
                    List.of(entry(junction.resolve("restored.jpg"), staged.getFileName().toString())));

            DeleteStagingManifest.recoverLeftovers(stagingRoot, List.of(allowedRoot));

            assertThat(outside.resolve("restored.jpg")).doesNotExist();
            assertThat(subdir).isDirectory();
            assertThat(staged).hasContent("backup");
        } finally {
            Files.deleteIfExists(junction);
        }
    }

    @Test
    @DisplayName("恢复：暂存根目录不存在时安全返回，不抛异常")
    void recoverIsNoOpWhenRootMissing() {
        DeleteStagingManifest.recoverLeftovers(tempDir.resolve("nonexistent"), List.of(tempDir));
        DeleteStagingManifest.recoverLeftovers(null, List.of(tempDir));
    }

    private static void createSymbolicLinkOrSkip(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            Assumptions.assumeTrue(false, "当前文件系统不支持测试符号链接: " + unavailable.getMessage());
        }
    }
}
