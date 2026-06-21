package top.sywyar.pixivdownload.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

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

        DeleteStagingManifest.recoverLeftovers(stagingRoot);

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

        DeleteStagingManifest.recoverLeftovers(stagingRoot);

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

        DeleteStagingManifest.recoverLeftovers(stagingRoot);

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

        DeleteStagingManifest.recoverLeftovers(stagingRoot);

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
            DeleteStagingManifest.write(subdir, List.of(new DeleteStagingManifest.Entry(
                    original.toAbsolutePath().normalize(), unsafeNames.get(i))));

            assertThat(DeleteStagingManifest.read(subdir))
                    .as("暂存副本名 %s 应被判为损坏", unsafeNames.get(i))
                    .isEmpty();
        }

        DeleteStagingManifest.recoverLeftovers(stagingRoot);

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

        DeleteStagingManifest.recoverLeftovers(stagingRoot);

        assertThat(subdir).isDirectory();
        assertThat(DeleteStagingManifest.read(subdir)).isEmpty();
    }

    @Test
    @DisplayName("恢复：暂存根目录不存在时安全返回，不抛异常")
    void recoverIsNoOpWhenRootMissing() {
        DeleteStagingManifest.recoverLeftovers(tempDir.resolve("nonexistent"));
        DeleteStagingManifest.recoverLeftovers(null);
    }
}
