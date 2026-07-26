package top.sywyar.pixivdownload.core.ffmpeg;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

@DisplayName("FFmpeg 命令解析结果")
class ResolvedFfmpegCommandTest {

    @Test
    @DisplayName("四种受控来源均保留宿主给出的命令")
    void allSourcesPreserveResolvedCommand() {
        for (ResolvedFfmpegCommand.Source source : ResolvedFfmpegCommand.Source.values()) {
            ResolvedFfmpegCommand command = new ResolvedFfmpegCommand("ffmpeg-command", source);

            assertThat(command.command()).isEqualTo("ffmpeg-command");
            assertThat(command.source()).isEqualTo(source);
        }
    }

    @Test
    @DisplayName("命令和来源不得为空")
    void commandAndSourceMustBePresent() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ResolvedFfmpegCommand(null, ResolvedFfmpegCommand.Source.SYSTEM));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ResolvedFfmpegCommand("  ", ResolvedFfmpegCommand.Source.SYSTEM));
        assertThatNullPointerException()
                .isThrownBy(() -> new ResolvedFfmpegCommand("ffmpeg", null));
    }
}
