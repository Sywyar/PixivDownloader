package top.sywyar.pixivdownload.novel.narration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.novel.TestRuntimePathProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import top.sywyar.pixivdownload.core.database.DatabaseUnavailableException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("多角色朗读参考音磁盘存储（原子写 / 扩展名切换清理 / 整册目录删除）")
class NarrationReferenceVoiceStoreTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("write：原子落盘目标文件、清掉同角色旧扩展名文件，不残留临时文件")
    void writeReplacesOtherExtensions() throws Exception {
        TestRuntimePathProvider runtimePaths = new TestRuntimePathProvider(tmp);
        NarrationReferenceVoicePaths paths = new NarrationReferenceVoicePaths(runtimePaths, () -> true);
        NarrationReferenceVoiceStore store = new NarrationReferenceVoiceStore(paths, () -> true);
        store.write(7L, 1, new byte[]{1, 2, 3}, "wav");
        assertThat(Files.exists(paths.file(7L, 1, "wav"))).isTrue();

        // 切换为 mp3：写出 mp3、清掉旧 wav
        store.write(7L, 1, new byte[]{4, 5, 6, 7}, "mp3");
        assertThat(Files.readAllBytes(paths.file(7L, 1, "mp3")))
                .isEqualTo(new byte[]{4, 5, 6, 7});
        assertThat(Files.exists(paths.file(7L, 1, "wav"))).isFalse();
        Path mp3 = paths.file(7L, 1, "mp3");
        assertThat(Files.exists(mp3.resolveSibling(mp3.getFileName() + ".tmp"))).isFalse();
    }

    @Test
    @DisplayName("deleteCharacterFiles：删除某角色全部扩展名文件，不影响同册其它角色")
    void deleteCharacterFilesRemovesAllExtensions() {
        TestRuntimePathProvider runtimePaths = new TestRuntimePathProvider(tmp);
        NarrationReferenceVoicePaths paths = new NarrationReferenceVoicePaths(runtimePaths, () -> true);
        NarrationReferenceVoiceStore store = new NarrationReferenceVoiceStore(paths, () -> true);
        store.write(7L, 1, new byte[]{1}, "wav");
        store.write(7L, 2, new byte[]{2}, "mp3");

        store.deleteCharacterFiles(7L, 1);

        assertThat(Files.exists(paths.file(7L, 1, "wav"))).isFalse();
        assertThat(Files.exists(paths.file(7L, 2, "mp3"))).isTrue();
    }

    @Test
    @DisplayName("deleteCastDirectory：递归删除整册目录，不误删其它册")
    void deleteCastDirectoryRemovesOnlyThatCast() {
        TestRuntimePathProvider runtimePaths = new TestRuntimePathProvider(tmp);
        NarrationReferenceVoicePaths paths = new NarrationReferenceVoicePaths(runtimePaths, () -> true);
        NarrationReferenceVoiceStore store = new NarrationReferenceVoiceStore(paths, () -> true);
        store.write(7L, 0, new byte[]{1}, "wav");
        store.write(7L, 1, new byte[]{2}, "mp3");
        store.write(8L, 0, new byte[]{3}, "wav");

        store.deleteCastDirectory(7L);

        assertThat(Files.exists(runtimePaths.narrationVoicePath(7L))).isFalse();
        assertThat(Files.exists(paths.file(8L, 0, "wav"))).isTrue();
    }

    @Test
    @DisplayName("数据库失效后拒绝覆盖、扩展名清理和整册删除，保留旧参考音")
    void preservesFilesWhenDatabaseBecomesUnavailable() throws Exception {
        AtomicBoolean available = new AtomicBoolean(true);
        var paths = new NarrationReferenceVoicePaths(new TestRuntimePathProvider(tmp), available::get);
        var store = new NarrationReferenceVoiceStore(paths, available::get);
        store.write(7L, 1, new byte[]{1, 2, 3}, "wav");
        Path original = paths.file(7L, 1, "wav");
        available.set(false);

        assertThatThrownBy(() -> store.write(7L, 1, new byte[]{9}, "wav"))
                .isInstanceOf(DatabaseUnavailableException.class);
        assertThatThrownBy(() -> store.write(7L, 1, new byte[]{9}, "mp3"))
                .isInstanceOf(DatabaseUnavailableException.class);
        assertThatThrownBy(() -> store.deleteCharacterFiles(7L, 1))
                .isInstanceOf(DatabaseUnavailableException.class);
        assertThatThrownBy(() -> store.deleteCastDirectory(7L))
                .isInstanceOf(DatabaseUnavailableException.class);

        assertThat(Files.readAllBytes(original)).containsExactly(1, 2, 3);
        try (var files = Files.list(original.getParent())) {
            assertThat(files.toList()).containsExactly(original);
        }
    }
}
