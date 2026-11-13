package top.sywyar.pixivdownload.plugin.runtime.artifact.cache;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("准备指纹的遍历、布局与预算")
class PreparationFingerprintTest {
    @TempDir Path temporary;

    @Test
    @DisplayName("源目录与乱序创建的开发快照内容一致，空目录变化仍使指纹失效")
    void preservesCanonicalContentAndEmptyDirectories() throws IOException {
        Path source = Files.createDirectory(temporary.resolve("source"));
        Files.write(source.resolve("a"), new byte[]{1, 2});
        Files.write(source.resolve("z"), new byte[]{3});
        Files.createDirectory(source.resolve("empty"));
        Path image = Files.createDirectory(temporary.resolve("image"));
        Path classes = Files.createDirectory(image.resolve("classes"));
        Files.createDirectory(classes.resolve("empty"));
        Files.write(classes.resolve("z"), new byte[]{3});
        Files.write(classes.resolve("a"), new byte[]{1, 2});
        Files.write(image.resolve(".pixiv-plugin-dev-snapshot"), new byte[]{9});

        var expected = PreparationFingerprint.capture(source, false, false, null, 3, 3);
        var actual = PreparationFingerprint.capture(image, true, true, null, 3, 3);
        assertThat(actual.sha256()).isEqualTo(expected.sha256());
        assertThat(actual.bytes()).isEqualTo(3);
        Files.delete(classes.resolve("empty"));
        assertThat(PreparationFingerprint.capture(image, true, true, null, 3, 3).sha256())
                .isNotEqualTo(expected.sha256());
    }

    @Test
    @DisplayName("遍历保留条目、累计字节和深度上限")
    void enforcesTraversalAndByteBudgets() throws IOException {
        Path root = Files.createDirectory(temporary.resolve("bounded"));
        Files.write(root.resolve("a"), new byte[]{1, 2});
        Files.write(root.resolve("b"), new byte[]{3});
        assertThat(PreparationFingerprint.capture(root, false, false, null, 3, 2).bytes()).isEqualTo(3);
        assertThatThrownBy(() -> PreparationFingerprint.capture(root, false, false, null, 3, 1))
                .isInstanceOf(IOException.class).hasMessage("PREPARATION_ENTRY_LIMIT");
        assertThatThrownBy(() -> PreparationFingerprint.capture(root, false, false, null, 2, 2))
                .isInstanceOf(IOException.class).hasMessage("PREPARATION_BYTE_LIMIT");
        Path deep = Files.createDirectory(temporary.resolve("deep"));
        Files.createDirectories(deep.resolve("d/".repeat(65)));
        assertThatThrownBy(() -> PreparationFingerprint.capture(deep, false, false, null, 1, 100))
                .isInstanceOf(IOException.class).hasMessage("PREPARATION_PATH_LIMIT");
    }

    @Test
    @DisplayName("遍历拒绝指向目录外的符号链接")
    void rejectsSymbolicLink() throws IOException {
        Path root = Files.createDirectory(temporary.resolve("linked"));
        Path outside = Files.write(temporary.resolve("outside"), new byte[]{7});
        try { Files.createSymbolicLink(root.resolve("escape"), outside); }
        catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
            Assumptions.abort("当前文件系统不能创建符号链接");
        }
        assertThatThrownBy(() -> PreparationFingerprint.capture(root, false, false, null, 1, 2))
                .isInstanceOf(IOException.class).hasMessage("PREPARATION_PATH_INVALID");
        assertThat(Files.readAllBytes(outside)).containsExactly((byte) 7);
    }
}
