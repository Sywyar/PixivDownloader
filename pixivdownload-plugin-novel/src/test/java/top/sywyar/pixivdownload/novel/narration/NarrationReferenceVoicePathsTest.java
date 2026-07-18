package top.sywyar.pixivdownload.novel.narration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.config.RuntimePathProvider;
import top.sywyar.pixivdownload.novel.TestRuntimePathProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

@DisplayName("小说参考音路径归属")
class NarrationReferenceVoicePathsTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("构造路径对象时不解析或创建可选参考音目录")
    void keepsPathResolutionLazyDuringConstruction() {
        RuntimePathProvider runtimePathProvider = mock(RuntimePathProvider.class);

        new NarrationReferenceVoicePaths(runtimePathProvider);

        verifyNoInteractions(runtimePathProvider);
        assertThat(tempDir.resolve("data/novel/narration-voice")).doesNotExist();
    }

    @Test
    @DisplayName("参考音使用小说插件数据布局并拒绝不安全扩展名")
    void resolvesOwnerLayoutAndRejectsUnsafeExtensions() {
        NarrationReferenceVoicePaths paths = new NarrationReferenceVoicePaths(
                new TestRuntimePathProvider(tempDir));

        assertThat(paths.file(7L, 3, " WAV "))
                .isEqualTo(tempDir.resolve("data/novel/narration-voice/7/3.wav"));
        assertThat(paths.file(7L, 3, null))
                .isEqualTo(tempDir.resolve("data/novel/narration-voice/7/3.wav"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> paths.file(7L, 3, "../../../escape"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> paths.file(7L, 3, "mp3.tmp"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> paths.file(7L, 3, "wav/child"));
    }

    @Test
    @DisplayName("首次解析时迁移旧参考音目录且重复解析保持幂等")
    void migratesLegacyDirectoryLazilyAndIdempotently() throws IOException {
        Path legacy = tempDir.resolve("data/narration-voice/7/3.wav");
        Files.createDirectories(legacy.getParent());
        Files.writeString(legacy, "voice", StandardCharsets.UTF_8);
        NarrationReferenceVoicePaths paths = new NarrationReferenceVoicePaths(
                new TestRuntimePathProvider(tempDir));

        Path first = paths.file(7L, 3, "wav");
        Path second = paths.file(7L, 3, "wav");

        assertThat(second).isEqualTo(first);
        assertThat(Files.readString(first, StandardCharsets.UTF_8)).isEqualTo("voice");
        assertThat(tempDir.resolve("data/narration-voice")).doesNotExist();
    }

    @Test
    @DisplayName("canonical 与旧参考音冲突时保留两份数据")
    void keepsDivergentCanonicalAndLegacyFiles() throws IOException {
        Path canonical = tempDir.resolve("data/novel/narration-voice/7/3.wav");
        Path legacy = tempDir.resolve("data/narration-voice/7/3.wav");
        Files.createDirectories(canonical.getParent());
        Files.createDirectories(legacy.getParent());
        Files.writeString(canonical, "canonical", StandardCharsets.UTF_8);
        Files.writeString(legacy, "legacy", StandardCharsets.UTF_8);
        NarrationReferenceVoicePaths paths = new NarrationReferenceVoicePaths(
                new TestRuntimePathProvider(tempDir));

        Path resolved = paths.file(7L, 3, "wav");

        assertThat(Files.readString(resolved, StandardCharsets.UTF_8)).isEqualTo("canonical");
        assertThat(Files.readString(legacy, StandardCharsets.UTF_8)).isEqualTo("legacy");
    }
}
