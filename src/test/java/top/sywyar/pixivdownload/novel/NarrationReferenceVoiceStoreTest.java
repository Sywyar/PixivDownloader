package top.sywyar.pixivdownload.novel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.config.RuntimeFiles;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("多角色朗读参考音磁盘存储（原子写 / 扩展名切换清理 / 整册目录删除）")
class NarrationReferenceVoiceStoreTest {

    @TempDir
    Path tmp;

    private String previousDataDir;

    @BeforeEach
    void redirectDataDir() {
        previousDataDir = System.getProperty(RuntimeFiles.DATA_DIR_PROPERTY);
        System.setProperty(RuntimeFiles.DATA_DIR_PROPERTY, tmp.toString());
    }

    @AfterEach
    void restoreDataDir() {
        if (previousDataDir == null) {
            System.clearProperty(RuntimeFiles.DATA_DIR_PROPERTY);
        } else {
            System.setProperty(RuntimeFiles.DATA_DIR_PROPERTY, previousDataDir);
        }
    }

    @Test
    @DisplayName("write：原子落盘目标文件、清掉同角色旧扩展名文件，不残留临时文件")
    void writeReplacesOtherExtensions() throws Exception {
        NarrationReferenceVoiceStore store = new NarrationReferenceVoiceStore();
        store.write(7L, 1, new byte[]{1, 2, 3}, "wav");
        assertThat(Files.exists(RuntimeFiles.narrationVoiceFile(7L, 1, "wav"))).isTrue();

        // 切换为 mp3：写出 mp3、清掉旧 wav
        store.write(7L, 1, new byte[]{4, 5, 6, 7}, "mp3");
        assertThat(Files.readAllBytes(RuntimeFiles.narrationVoiceFile(7L, 1, "mp3")))
                .isEqualTo(new byte[]{4, 5, 6, 7});
        assertThat(Files.exists(RuntimeFiles.narrationVoiceFile(7L, 1, "wav"))).isFalse();
        Path mp3 = RuntimeFiles.narrationVoiceFile(7L, 1, "mp3");
        assertThat(Files.exists(mp3.resolveSibling(mp3.getFileName() + ".tmp"))).isFalse();
    }

    @Test
    @DisplayName("deleteCharacterFiles：删除某角色全部扩展名文件，不影响同册其它角色")
    void deleteCharacterFilesRemovesAllExtensions() {
        NarrationReferenceVoiceStore store = new NarrationReferenceVoiceStore();
        store.write(7L, 1, new byte[]{1}, "wav");
        store.write(7L, 2, new byte[]{2}, "mp3");

        store.deleteCharacterFiles(7L, 1);

        assertThat(Files.exists(RuntimeFiles.narrationVoiceFile(7L, 1, "wav"))).isFalse();
        assertThat(Files.exists(RuntimeFiles.narrationVoiceFile(7L, 2, "mp3"))).isTrue();
    }

    @Test
    @DisplayName("deleteCastDirectory：递归删除整册目录，不误删其它册")
    void deleteCastDirectoryRemovesOnlyThatCast() {
        NarrationReferenceVoiceStore store = new NarrationReferenceVoiceStore();
        store.write(7L, 0, new byte[]{1}, "wav");
        store.write(7L, 1, new byte[]{2}, "mp3");
        store.write(8L, 0, new byte[]{3}, "wav");

        store.deleteCastDirectory(7L);

        assertThat(Files.exists(RuntimeFiles.narrationVoiceDirectory().resolve("7"))).isFalse();
        assertThat(Files.exists(RuntimeFiles.narrationVoiceFile(8L, 0, "wav"))).isTrue();
    }
}
