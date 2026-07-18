package top.sywyar.pixivdownload.tts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.config.RuntimePathProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("TTS 运行期文件归属")
class TtsRuntimeFilesTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("构造运行期路径对象时不解析或创建可选数据目录")
    void keepsPathResolutionLazyDuringConstruction() {
        RuntimePathProvider runtimePathProvider = mock(RuntimePathProvider.class);

        new TtsRuntimeFiles(runtimePathProvider);

        verifyNoInteractions(runtimePathProvider);
        assertThat(tempDir.resolve("data/tts")).doesNotExist();
    }

    @Test
    @DisplayName("Chromium 版本缓存使用 TTS 插件数据路径")
    void resolvesChromiumVersionUnderPluginDataDirectory() {
        RuntimePathProvider runtimePathProvider = mock(RuntimePathProvider.class);
        when(runtimePathProvider.resolvePluginDataDirectory(TtsPlugin.ID))
                .thenReturn(tempDir.resolve("data/tts"));

        TtsRuntimeFiles runtimeFiles = new TtsRuntimeFiles(runtimePathProvider);

        assertThat(runtimeFiles.chromiumVersionFile())
                .isEqualTo(tempDir.resolve("data/tts/chromium-version.txt"));
        verify(runtimePathProvider).resolvePluginDataDirectory(TtsPlugin.ID);
    }

    @Test
    @DisplayName("首次解析时迁移旧 _tts 目录且重复解析保持幂等")
    void migratesLegacyDirectoryLazilyAndIdempotently() throws IOException {
        RuntimePathProvider runtimePathProvider = provider();
        Path legacyDirectory = tempDir.resolve("_tts");
        Files.createDirectories(legacyDirectory.resolve("nested"));
        Files.writeString(legacyDirectory.resolve("chromium-version.txt"),
                "148.0.3967.70", StandardCharsets.UTF_8);
        Files.writeString(legacyDirectory.resolve("nested/owned.txt"),
                "owned", StandardCharsets.UTF_8);
        TtsRuntimeFiles runtimeFiles = new TtsRuntimeFiles(runtimePathProvider);

        Path first = runtimeFiles.chromiumVersionFile();
        Path second = runtimeFiles.chromiumVersionFile();

        assertThat(second).isEqualTo(first);
        assertThat(Files.readString(first, StandardCharsets.UTF_8)).isEqualTo("148.0.3967.70");
        assertThat(Files.readString(tempDir.resolve("data/tts/nested/owned.txt"), StandardCharsets.UTF_8))
                .isEqualTo("owned");
        assertThat(legacyDirectory).doesNotExist();
    }

    @Test
    @DisplayName("canonical 与旧 TTS 文件冲突时保留两份数据")
    void keepsDivergentCanonicalAndLegacyFiles() throws IOException {
        RuntimePathProvider runtimePathProvider = provider();
        Path canonical = tempDir.resolve("data/tts/chromium-version.txt");
        Path legacy = tempDir.resolve("_tts/chromium-version.txt");
        Files.createDirectories(canonical.getParent());
        Files.createDirectories(legacy.getParent());
        Files.writeString(canonical, "canonical", StandardCharsets.UTF_8);
        Files.writeString(legacy, "legacy", StandardCharsets.UTF_8);

        Path resolved = new TtsRuntimeFiles(runtimePathProvider).chromiumVersionFile();

        assertThat(Files.readString(resolved, StandardCharsets.UTF_8)).isEqualTo("canonical");
        assertThat(Files.readString(legacy, StandardCharsets.UTF_8)).isEqualTo("legacy");
    }

    private RuntimePathProvider provider() {
        RuntimePathProvider runtimePathProvider = mock(RuntimePathProvider.class);
        when(runtimePathProvider.resolvePluginDataDirectory(TtsPlugin.ID))
                .thenReturn(tempDir.resolve("data/tts"));
        return runtimePathProvider;
    }
}
