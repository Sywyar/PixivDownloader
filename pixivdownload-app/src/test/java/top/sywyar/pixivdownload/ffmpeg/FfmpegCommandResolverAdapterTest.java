package top.sywyar.pixivdownload.ffmpeg;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.core.ffmpeg.ResolvedFfmpegCommand;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("宿主 FFmpeg 命令解析适配器")
class FfmpegCommandResolverAdapterTest {

    @Test
    @DisplayName("已探测安装应映射命令路径与受控来源")
    void detectedInstallationMapsCommandAndSource() {
        for (FfmpegInstallation.Source source : FfmpegInstallation.Source.values()) {
            Path executable = Path.of("tools", source.name().toLowerCase(), "ffmpeg");
            FfmpegInstallation installation = new FfmpegInstallation(
                    executable,
                    null,
                    executable.getParent(),
                    source
            );
            FfmpegCommandResolverAdapter adapter = new FfmpegCommandResolverAdapter(
                    () -> Optional.of(installation),
                    () -> "unused-fallback"
            );

            ResolvedFfmpegCommand resolved = adapter.resolve();

            assertThat(resolved.command()).isEqualTo(executable.toString());
            assertThat(resolved.source()).isEqualTo(expectedSource(source));
        }
    }

    @Test
    @DisplayName("未探测到安装时应保留平台回退命令")
    void missingInstallationUsesFallbackCommand() {
        FfmpegCommandResolverAdapter adapter = new FfmpegCommandResolverAdapter(
                Optional::empty,
                () -> "ffmpeg-fallback"
        );

        assertThat(adapter.resolve())
                .isEqualTo(new ResolvedFfmpegCommand(
                        "ffmpeg-fallback",
                        ResolvedFfmpegCommand.Source.FALLBACK
                ));
    }

    private static ResolvedFfmpegCommand.Source expectedSource(FfmpegInstallation.Source source) {
        return switch (source) {
            case MANAGED -> ResolvedFfmpegCommand.Source.MANAGED;
            case BUNDLED -> ResolvedFfmpegCommand.Source.BUNDLED;
            case SYSTEM -> ResolvedFfmpegCommand.Source.SYSTEM;
        };
    }
}
