package top.sywyar.pixivdownload.novel.narration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.core.asset.FileTransactionService;
import top.sywyar.pixivdownload.core.database.DatabaseUnavailableException;
import top.sywyar.pixivdownload.novel.TestRuntimePathProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("参考音暂存与宿主文件事务边界")
class NarrationReferenceVoiceStoreTest {
    @TempDir Path tmp;

    @Test
    @DisplayName("完整音频暂存后交付文件事务，异常时释放本次暂存而不改旧音频")
    void stagesAudioAndCleansTemporaryAfterFailure() throws Exception {
        var paths = new NarrationReferenceVoicePaths(new TestRuntimePathProvider(tmp), () -> true);
        var files = mock(FileTransactionService.class);
        var store = new NarrationReferenceVoiceStore(paths, () -> true, files);
        Path old = Files.write(paths.file(7L, 1, "wav"), new byte[]{1, 2, 3});
        Path other = Files.write(paths.file(7L, 2, "mp3"), new byte[]{8});
        Files.write(old.resolveSibling("1.reference.part"), new byte[]{9, 9, 9, 9, 9, 9});
        var failure = new IllegalStateException("record update failed");
        Runnable records = () -> { throw failure; };
        doAnswer(call -> {
            Map<Path, Path> replacements = call.getArgument(0);
            assertThat(replacements).containsOnlyKeys(paths.file(7L, 1, "mp3"));
            assertThat(Files.readAllBytes(replacements.values().iterator().next())).containsExactly(4, 5, 6, 7);
            assertThat((java.util.Collection<Path>) call.getArgument(1))
                    .containsExactly(old, paths.file(7L, 1, "pcm"));
            assertThat(call.getArgument(2, Runnable.class)).isSameAs(records);
            records.run();
            return null;
        }).when(files).changeFiles(any(), any(), any());

        assertThatThrownBy(() -> store.write(7L, 1, new byte[]{4, 5, 6, 7}, "mp3", records))
                .isSameAs(failure);

        assertThat(Files.readAllBytes(old)).containsExactly(1, 2, 3);
        try (var remaining = Files.list(old.getParent())) {
            assertThat(remaining.toList()).containsExactlyInAnyOrder(old, other);
        }
    }

    @Test
    @DisplayName("拒绝链接暂存文件且不改写链接目标")
    void rejectsLinkedTemporaryFile() throws Exception {
        var paths = new NarrationReferenceVoicePaths(new TestRuntimePathProvider(tmp), () -> true);
        var files = mock(FileTransactionService.class);
        var store = new NarrationReferenceVoiceStore(paths, () -> true, files);
        Path original = Files.write(tmp.resolve("original.wav"), new byte[]{1, 2, 3});
        Path temporary = paths.file(7L, 1, "wav").resolveSibling("1.reference.part");
        try {
            Files.createSymbolicLink(temporary, original);
        } catch (IOException | UnsupportedOperationException unavailable) {
            org.junit.jupiter.api.Assumptions.abort("当前环境不能创建符号链接");
        }

        assertThatThrownBy(() -> store.write(7L, 1, new byte[]{9}, "wav", () -> {}))
                .isInstanceOf(UncheckedIOException.class);
        assertThat(Files.readAllBytes(original)).containsExactly(1, 2, 3);
        assertThat(Files.isSymbolicLink(temporary)).isTrue();
        verifyNoInteractions(files);
    }

    @Test
    @DisplayName("角色删除提交全部受支持格式及同一记录回调，不包含其它角色")
    void delegatesCharacterDeletion() throws Exception {
        var paths = new NarrationReferenceVoicePaths(new TestRuntimePathProvider(tmp), () -> true);
        var files = mock(FileTransactionService.class);
        var store = new NarrationReferenceVoiceStore(paths, () -> true, files);
        Runnable records = mock(Runnable.class);

        store.deleteCharacterFiles(7L, 1, records);

        verify(files).changeFiles(Map.of(), java.util.List.of(paths.file(7L, 1, "wav"),
                paths.file(7L, 1, "mp3"), paths.file(7L, 1, "pcm")), records);
    }

    @Test
    @DisplayName("数据库失效后拒绝覆盖与角色文件清理，保留旧参考音")
    void preservesFilesWhenDatabaseBecomesUnavailable() throws Exception {
        AtomicBoolean available = new AtomicBoolean(true);
        var paths = new NarrationReferenceVoicePaths(new TestRuntimePathProvider(tmp), available::get);
        var files = mock(FileTransactionService.class);
        var store = new NarrationReferenceVoiceStore(paths, available::get, files);
        Path original = Files.write(paths.file(7L, 1, "wav"), new byte[]{1, 2, 3});
        available.set(false);

        assertThatThrownBy(() -> store.write(7L, 1, new byte[]{9}, "wav", () -> {}))
                .isInstanceOf(DatabaseUnavailableException.class);
        assertThatThrownBy(() -> store.deleteCharacterFiles(7L, 1, () -> {}))
                .isInstanceOf(DatabaseUnavailableException.class);

        verifyNoInteractions(files);
        assertThat(Files.readAllBytes(original)).containsExactly(1, 2, 3);
        try (var remaining = Files.list(original.getParent())) {
            assertThat(remaining.toList()).containsExactly(original);
        }
    }
}
